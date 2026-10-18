package com.miuraken.chunkflow.core.media

import android.content.Context
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 量子化 Whisper Tiny Encoder + 1-Step Fused Language Head による 2 言語ペア判定結果（F6.2）。
 *
 * 確率は、それぞれの言語に同系の言語（例: ヒンディー語に対するウルドゥー語）の確率を合算してから 2 言語で正規化する
 * （[WhisperTinyLanguageDetector.languageFamilyIndices]）。
 *
 * @property selectedTag 判定された優先言語タグ（呼び出し元の `targetTag` または `baseTag`）。
 * @property fallbackTag もう一方の言語タグ。
 * @property selectedProbability 選択された言語の 2 クラス softmax 確率（`0.5..1.0`）。
 * @property targetProbability `targetTag` の 2 クラス softmax 確率（`0.0..1.0`）。
 * @property baseProbability `baseTag` の 2 クラス softmax 確率（`0.0..1.0`）。
 * @property inferenceTimeMs 言語判定にかかった推論時間（ミリ秒）。
 * @property pairMass 99 言語の確率のうち、2 言語（同系の言語を含む）に集まった割合（`0.0..1.0`）。
 *   小さいときはモデルがどちらの言語とも判断できておらず、2 言語で正規化した確率は過信になりやすい。
 *   Whisper 以外から作った判定（保存済みの判定結果など）では `1.0`。
 */
internal data class LanguagePairPrediction(
    val selectedTag: String,
    val fallbackTag: String,
    val selectedProbability: Float,
    val targetProbability: Float,
    val baseProbability: Float,
    val inferenceTimeMs: Long,
    val pairMass: Float = 1.0f,
)

/**
 * 2 言語ペアの判定結果と、同じ推論で得たエンコーダ出力の平均ベクトル（[WhisperTinyLanguageDetector.analyzeLanguagePair]）。
 *
 * @property prediction 2 言語ペアの判定結果。
 * @property encoderEmbedding 発話を含む位置のエンコーダ出力（LayerNorm 後）の平均（384 次元）。
 *   話者の声色と言語の音響的な特徴を含み、ファイル内での判定の補正（[VoiceProfileLanguageTracker]）に使う。
 */
internal class LanguagePairAnalysis(
    val prediction: LanguagePairPrediction,
    val encoderEmbedding: FloatArray,
)

/**
 * 量子化 Whisper Tiny（`whisper_tiny_lid_q8q4.bin`、約 10.7 MB）を用いて、
 * `SpeechRecognizer` を呼び出す前にミリ秒単位で `target` 言語と `base` 言語の確率を算出する言語判定器（F6.2）。
 *
 * - 3.0 秒（48,000 サンプル @ 16kHz）の 80-bin Log-Mel スペクトログラム（300 フレーム）から
 *   4 層 Audio Encoder（`T_enc = 150`, `d_model = 384`）と 1-Step Fused Text Decoder（`T_dec = 1`）を実行し、
 *   99 言語トークンのロジットを算出する。
 * - 重みは Q8（行単位スケール）および Q4Group64（64 要素ブロック単位スケール）のパック形式で保持し、
 *   初回推論時に 1 回だけ遅延ロードする。
 */
@Singleton
class WhisperTinyLanguageDetector @Inject constructor(
    @ApplicationContext private val context: Context?,
) {
    internal constructor() : this(context = null)

    @Volatile
    private var cachedModel: QuantizedWhisperLidModel? = null
    private val loadLock = Any()
    private val workspacePool = ArrayDeque<InferenceWorkspace>()

    /**
     * モデルに含まれる言語コード一覧（99 言語）を返す。モデルがロードできない場合は空リストを返す。
     */
    internal fun supportedLanguageCodes(): List<String> = ensureModelLoaded()?.languageCodes.orEmpty()

    /**
     * `sherpa-onnx` Whisper 推論向けに、16kHz モノラル 16-bit PCM（最大 30 秒 = 3,000 フレーム）から
     * `[1, 80, 3000]` 形状（`melIdx * 3000 + frameIdx`）の正規化 Log-Mel スペクトログラムを算出する。
     */
    internal fun computeMelSpectrogram80x3000(
        pcm16kMono: ShortArray,
        allowSilentForTest: Boolean = false,
    ): FloatArray? {
        if (pcm16kMono.isEmpty()) return null
        val model = ensureModelLoaded() ?: return null
        return computeMelSpectrogram80x3000WithFilters(
            pcm16kMono = pcm16kMono,
            melFilters201x80 = model.melFilters201x80,
            allowSilentForTest = allowSilentForTest,
        )?.first
    }

    /**
     * 16kHz モノラル 16-bit PCM（[pcm16kMono]）から `targetTag` と `baseTag` の 2 言語確率を算出する。
     *
     * 無音チャンク、極端に短いチャンク、非対応言語ペア、またはモデル未ロード時は `null` を返す。
     *
     * @param pcm16kMono 16kHz モノラル PCM サンプル列。
     * @param targetTag 学習対象言語の BCP-47 タグ。
     * @param baseTag 基準言語（母語・解説言語）の BCP-47 タグ。
     * @param maxWindowSamples 発話開始位置から切り出す最大サンプル数（既定は 2.0 秒 = [DEFAULT_LID_WINDOW_SAMPLES]）。
     */
    internal fun detectLanguagePair(
        pcm16kMono: ShortArray,
        targetTag: String,
        baseTag: String,
        maxWindowSamples: Int = DEFAULT_LID_WINDOW_SAMPLES,
    ): LanguagePairPrediction? = analyzeLanguagePair(
        pcm16kMono = pcm16kMono,
        targetTag = targetTag,
        baseTag = baseTag,
        maxWindowSamples = maxWindowSamples,
    )?.prediction

    /**
     * [detectLanguagePair] と同じ判定に加えて、同じ推論で得たエンコーダ出力の平均ベクトルを返す。
     *
     * 平均ベクトルは推論結果を集計するだけで、追加の推論はしない。
     * 無音チャンク、極端に短いチャンク、非対応言語ペア、またはモデル未ロード時は `null` を返す。
     *
     * @param pcm16kMono 16kHz モノラル PCM サンプル列。
     * @param targetTag 学習対象言語の BCP-47 タグ。
     * @param baseTag 基準言語（母語・解説言語）の BCP-47 タグ。
     * @param maxWindowSamples 発話開始位置から切り出す最大サンプル数（既定は 2.0 秒 = [DEFAULT_LID_WINDOW_SAMPLES]）。
     */
    internal fun analyzeLanguagePair(
        pcm16kMono: ShortArray,
        targetTag: String,
        baseTag: String,
        maxWindowSamples: Int = DEFAULT_LID_WINDOW_SAMPLES,
    ): LanguagePairAnalysis? {
        if (pcm16kMono.size < MIN_VALID_PCM_SAMPLES) return null
        val model = ensureModelLoaded() ?: return null

        val targetCode = resolveWhisperLanguageCode(targetTag, model.languageIndexByCode) ?: return null
        val baseCode = resolveWhisperLanguageCode(baseTag, model.languageIndexByCode) ?: return null
        if (targetCode == baseCode) return null

        val targetIndices = languageFamilyIndices(targetCode, baseCode, model.languageIndexByCode)
        val baseIndices = languageFamilyIndices(baseCode, targetCode, model.languageIndexByCode)
        if (targetIndices.isEmpty() || baseIndices.isEmpty()) return null

        val startRealtimeMs = elapsedMillis()
        val workspace = acquireWorkspace()
        return try {
            val copiedSamples = preparePcm48kWindow(
                pcm16kMono = pcm16kMono,
                out48k = workspace.pcm48k,
                maxWindowSamples = maxWindowSamples,
            )
            if (copiedSamples <= 0) return null

            val logits = runInference(model, workspace, copiedSamples)
            val embedding = meanPoolEncoderOutput(workspace.audioFeatures150x384, copiedSamples)
            val elapsedMs = (elapsedMillis() - startRealtimeMs).coerceAtLeast(0L)
            LanguagePairAnalysis(
                prediction = buildFamilyPairPrediction(
                    logits = logits,
                    targetIndices = targetIndices,
                    baseIndices = baseIndices,
                    targetTag = targetTag,
                    baseTag = baseTag,
                    inferenceTimeMs = elapsedMs,
                ),
                encoderEmbedding = embedding,
            )
        } finally {
            releaseWorkspace(workspace)
        }
    }

    /**
     * テスト・検証用に 99 言語すべてのロジットを返す。
     */
    internal fun predictAllLanguageLogits(pcm16kMono: ShortArray): Map<String, Float>? {
        if (pcm16kMono.isEmpty()) return null
        val model = ensureModelLoaded() ?: return null
        val workspace = acquireWorkspace()
        return try {
            val copiedSamples = preparePcm48kWindow(
                pcm16kMono = pcm16kMono,
                out48k = workspace.pcm48k,
                maxWindowSamples = TARGET_PCM_SAMPLES,
                allowSilentForTest = true,
            )
            val logits = runInference(model, workspace, copiedSamples.coerceAtLeast(1))
            val result = LinkedHashMap<String, Float>(model.languageCodes.size)
            for (i in model.languageCodes.indices) {
                result[model.languageCodes[i]] = logits[i]
            }
            result
        } finally {
            releaseWorkspace(workspace)
        }
    }

    private fun acquireWorkspace(): InferenceWorkspace = synchronized(workspacePool) {
        workspacePool.removeFirstOrNull() ?: InferenceWorkspace()
    }

    private fun releaseWorkspace(workspace: InferenceWorkspace) {
        synchronized(workspacePool) {
            if (workspacePool.size < MAX_POOLED_WORKSPACES) {
                workspacePool.addLast(workspace)
            }
        }
    }

    private fun ensureModelLoaded(): QuantizedWhisperLidModel? {
        sharedLoadedModel?.let {
            cachedModel = it
            return it
        }
        synchronized(loadLock) {
            sharedLoadedModel?.let {
                cachedModel = it
                return it
            }
            for (streamProvider in candidateStreamProviders()) {
                val model = runCatching {
                    streamProvider()?.use { stream ->
                        parseModelBytes(stream.readBytes())
                    }
                }.getOrNull()
                if (model != null) {
                    sharedLoadedModel = model
                    cachedModel = model
                    return model
                }
            }
            return null
        }
    }

    private fun candidateStreamProviders(): List<() -> InputStream?> = listOf(
        {
            context?.let { ctx ->
                runCatching { ctx.assets.open(ASSET_FILE_NAME) }.getOrNull()
            }
        },
        {
            val candidateFiles = listOf(
                File("src/main/assets/$ASSET_FILE_NAME"),
                File("core/media/src/main/assets/$ASSET_FILE_NAME"),
                File("../core/media/src/main/assets/$ASSET_FILE_NAME"),
            )
            candidateFiles.firstOrNull { it.isFile }?.inputStream()
        },
        {
            WhisperTinyLanguageDetector::class.java.classLoader?.getResourceAsStream(ASSET_FILE_NAME)
        },
    )

    private fun elapsedMillis(): Long = try {
        val rt = SystemClock.elapsedRealtime()
        if (rt > 0L) rt else System.nanoTime() / 1_000_000L
    } catch (_: Throwable) {
        System.nanoTime() / 1_000_000L
    }

    companion object {
        internal const val ASSET_FILE_NAME: String = "whisper_tiny_lid_q8q4.bin"
        internal const val SAMPLE_RATE_HZ: Int = 16_000
        internal const val TARGET_PCM_SAMPLES: Int = 48_000

        /** 2 言語ペア LID の既定窓長（2.0 秒 = 32,000 サンプル @ 16kHz）。 */
        internal const val DEFAULT_LID_WINDOW_SAMPLES: Int = 32_000

        /** 話者プロファイルがある程度一致している場合の高速 LID 窓長（1.5 秒 = 24,000 サンプル @ 16kHz）。 */
        internal const val FAST_LID_WINDOW_SAMPLES: Int = 24_000
        private const val MIN_VALID_PCM_SAMPLES: Int = 1_600
        private const val N_FFT: Int = 400
        private const val HOP_LENGTH: Int = 160
        internal const val N_FREQ_BINS: Int = 201
        internal const val N_MELS: Int = 80
        private const val N_FRAMES: Int = 300
        private const val N_AUDIO_CTX: Int = 150
        private const val D_MODEL: Int = 384
        private const val N_HEAD: Int = 6
        private const val D_HEAD: Int = 64
        private const val D_FF: Int = 1_536
        private const val N_AUDIO_LAYER: Int = 4
        private const val N_TEXT_LAYER: Int = 4
        private const val Q4_GROUP_SIZE: Int = 64
        private const val ATTN_SCALE: Float = 0.35355338f // 64^-0.25
        private const val LAYER_NORM_EPS: Float = 1e-5f
        private const val INV_SQRT_2: Float = 0.70710677f
        private const val INV_LN_10: Float = 0.43429448f
        private const val ONSET_RMS_FRAME_SAMPLES: Int = 320 // 20ms @ 16kHz
        private const val ONSET_PRE_ROLL_SAMPLES: Int = 960 // 60ms @ 16kHz
        private const val SPEECH_ONSET_RMS_THRESHOLD: Double = 120.0
        private const val MIN_PEAK_AMPLITUDE: Int = 64

        /** 使い回す作業領域（1 つ約 3.5MB）の数。一括言語判定の同時推論数の上限（3）に合わせる。 */
        private const val MAX_POOLED_WORKSPACES: Int = 3

        /** Whisper Tiny (v1/v2) 多言語モデルがサポートする 99 言語コード（トークン ID 50259..50357 の順）。 */
        internal val WHISPER_99_LANGUAGE_CODES: List<String> = listOf(
            "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr",
            "pl", "ca", "nl", "ar", "sv", "it", "id", "hi", "fi", "vi",
            "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
            "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk",
            "te", "fa", "lv", "bn", "sr", "az", "sl", "kn", "et", "mk",
            "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
            "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc",
            "ka", "be", "tg", "sd", "gu", "am", "yi", "lo", "uz", "fo",
            "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
            "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su",
        )

        private val WHISPER_LANGUAGE_INDEX_MAP: Map<String, Int> =
            WHISPER_99_LANGUAGE_CODES.withIndex().associate { (idx, code) -> code to idx }

        /**
         * 言語タグ [rawTag] に対応する Whisper 言語インデックス（`0..99`）を返す。非対応言語は `null`。
         */
        internal fun languageIndexForTag(rawTag: String): Int? {
            val code = resolveWhisperLanguageCode(rawTag, WHISPER_LANGUAGE_INDEX_MAP) ?: return null
            return WHISPER_LANGUAGE_INDEX_MAP[code]
        }

        /**
         * 話し言葉ではほぼ同じで、Whisper が確率を分け合いやすい言語の組（同系の言語）。
         *
         * 例: 話し言葉のヒンディー語はウルドゥー語と区別しにくく、Whisper はヒンディー語の発話にも
         * ウルドゥー語の確率を大きく割り当てる。2 言語ペアの判定では、これらの確率を合算して扱う。
         */
        private val LANGUAGE_FAMILY_ALIASES: Map<String, List<String>> = mapOf(
            "hi" to listOf("ur"),
            "ur" to listOf("hi"),
            "no" to listOf("nn"),
            "nn" to listOf("no"),
            "ms" to listOf("id"),
            "id" to listOf("ms"),
            "sr" to listOf("hr", "bs"),
            "hr" to listOf("sr", "bs"),
            "bs" to listOf("sr", "hr"),
        )

        /**
         * 言語 [code] の確率として合算する Whisper 言語インデックス（[code] 自身と同系の言語）を返す。
         *
         * もう一方の言語 [otherCode] とその同系の言語は含めない（例: ヒンディー語とウルドゥー語のペアでは合算しない）。
         */
        internal fun languageFamilyIndices(
            code: String,
            otherCode: String,
            languageIndexByCode: Map<String, Int>,
        ): IntArray {
            val otherFamily = HashSet<String>().apply {
                add(otherCode)
                addAll(LANGUAGE_FAMILY_ALIASES[otherCode].orEmpty())
            }
            val codes = LinkedHashSet<String>()
            codes.add(code)
            for (alias in LANGUAGE_FAMILY_ALIASES[code].orEmpty()) {
                if (alias !in otherFamily) codes.add(alias)
            }
            return codes.mapNotNull { languageIndexByCode[it] }.toIntArray()
        }

        /**
         * 99 言語のロジット [logits] から、同系の言語を合算した 2 言語の確率と [LanguagePairPrediction.pairMass] を求める。
         *
         * @param targetIndices `targetTag` として合算する言語インデックス（[languageFamilyIndices]）。
         * @param baseIndices `baseTag` として合算する言語インデックス。
         */
        internal fun buildFamilyPairPrediction(
            logits: FloatArray,
            targetIndices: IntArray,
            baseIndices: IntArray,
            targetTag: String,
            baseTag: String,
            inferenceTimeMs: Long,
        ): LanguagePairPrediction {
            val targetLse = logSumExp(logits, targetIndices)
            val baseLse = logSumExp(logits, baseIndices)
            val allLse = logSumExp(logits, null)
            val diff = (targetLse - baseLse).coerceIn(-60.0, 60.0)
            val targetProb = (1.0 / (1.0 + exp(-diff))).toFloat().coerceIn(0.0f, 1.0f)
            val baseProb = (1.0f - targetProb).coerceIn(0.0f, 1.0f)
            val pairLse = max(targetLse, baseLse) + ln(1.0 + exp(-abs(targetLse - baseLse)))
            val pairMass = exp((pairLse - allLse).coerceAtMost(0.0)).toFloat().coerceIn(0.0f, 1.0f)
            return if (targetProb >= baseProb) {
                LanguagePairPrediction(
                    selectedTag = targetTag,
                    fallbackTag = baseTag,
                    selectedProbability = targetProb,
                    targetProbability = targetProb,
                    baseProbability = baseProb,
                    inferenceTimeMs = inferenceTimeMs,
                    pairMass = pairMass,
                )
            } else {
                LanguagePairPrediction(
                    selectedTag = baseTag,
                    fallbackTag = targetTag,
                    selectedProbability = baseProb,
                    targetProbability = targetProb,
                    baseProbability = baseProb,
                    inferenceTimeMs = inferenceTimeMs,
                    pairMass = pairMass,
                )
            }
        }

        /** [indices] のロジット（`null` なら全ロジット）の log-sum-exp。 */
        private fun logSumExp(logits: FloatArray, indices: IntArray?): Double {
            var maxLogit = Double.NEGATIVE_INFINITY
            if (indices == null) {
                for (v in logits) if (v > maxLogit) maxLogit = v.toDouble()
            } else {
                for (i in indices) if (logits[i] > maxLogit) maxLogit = logits[i].toDouble()
            }
            if (maxLogit == Double.NEGATIVE_INFINITY) return maxLogit
            var sum = 0.0
            if (indices == null) {
                for (v in logits) sum += exp(v - maxLogit)
            } else {
                for (i in indices) sum += exp(logits[i] - maxLogit)
            }
            return maxLogit + ln(sum)
        }

        /** PCM の有効サンプル数 [copiedSamples] に対応する、音声を含む Log-Mel フレーム数（最大 300）。 */
        private fun activeMelFrameCount(copiedSamples: Int): Int {
            val safeCopied = copiedSamples.coerceIn(1, TARGET_PCM_SAMPLES)
            return min(N_FRAMES, ((N_FFT / 2 + safeCopied + HOP_LENGTH - 1) / HOP_LENGTH).coerceAtLeast(1))
        }

        /** エンコーダ出力 [audioFeatures]（150 x 384）のうち、音声を含む位置の平均ベクトルを返す。 */
        private fun meanPoolEncoderOutput(audioFeatures: FloatArray, copiedSamples: Int): FloatArray {
            val positions = ((activeMelFrameCount(copiedSamples) + 1) / 2).coerceIn(1, N_AUDIO_CTX)
            val embedding = FloatArray(D_MODEL)
            for (t in 0 until positions) {
                val off = t * D_MODEL
                for (c in 0 until D_MODEL) {
                    embedding[c] += audioFeatures[off + c]
                }
            }
            val inv = 1.0f / positions.toFloat()
            for (c in 0 until D_MODEL) {
                embedding[c] *= inv
            }
            return embedding
        }

        @Volatile
        private var sharedLoadedModel: QuantizedWhisperLidModel? = null

        // 400点 RFFT 用の周期的 Hann 窓と 20x20 Cooley-Tukey 分解テーブル
        private val HANN_WINDOW_400: FloatArray = FloatArray(N_FFT) { n ->
            (0.5 * (1.0 - cos(2.0 * Math.PI * n.toDouble() / N_FFT.toDouble()))).toFloat()
        }
        private val DFT20_COS: FloatArray = FloatArray(20 * 20).apply {
            for (m in 0 until 20) {
                for (n1 in 0 until 20) {
                    this[m * 20 + n1] = cos(-2.0 * Math.PI * m * n1 / 20.0).toFloat()
                }
            }
        }
        private val DFT20_SIN: FloatArray = FloatArray(20 * 20).apply {
            for (m in 0 until 20) {
                for (n1 in 0 until 20) {
                    this[m * 20 + n1] = sin(-2.0 * Math.PI * m * n1 / 20.0).toFloat()
                }
            }
        }
        private val TWIDDLE_RE_201_20: FloatArray = FloatArray(N_FREQ_BINS * 20).apply {
            for (k in 0 until N_FREQ_BINS) {
                for (n0 in 0 until 20) {
                    this[k * 20 + n0] = cos(-2.0 * Math.PI * k * n0 / N_FFT.toDouble()).toFloat()
                }
            }
        }
        private val TWIDDLE_IM_201_20: FloatArray = FloatArray(N_FREQ_BINS * 20).apply {
            for (k in 0 until N_FREQ_BINS) {
                for (n0 in 0 until 20) {
                    this[k * 20 + n0] = sin(-2.0 * Math.PI * k * n0 / N_FFT.toDouble()).toFloat()
                }
            }
        }

        /**
         * BCP-47 言語タグ（例: `"ja-JP"`, `"ko-KR"`, `"zh-HK"`, `"yue-Hant-HK"`）を
         * Whisper の言語コード（`"ja"`, `"ko"`, `"zh"`, `"yue"` 等）へ解決する。
         */
        internal fun resolveWhisperLanguageCode(
            rawTag: String,
            supportedCodes: Map<String, Int>,
        ): String? {
            val normalized = rawTag.trim().replace('_', '-').lowercase(Locale.ROOT)
            if (normalized.isEmpty()) return null
            if (normalized in supportedCodes) return normalized

            // 広東語 / 香港・マカオ系タグの特例処理
            if (normalized.startsWith("yue") || normalized == "zh-hk" || normalized == "zh-mo" || normalized.contains("-hk")) {
                if ("yue" in supportedCodes) return "yue"
                if ("zh" in supportedCodes) return "zh"
            }

            val primary = normalized.substringBefore('-')
            val mapped = when (primary) {
                "iw" -> "he"
                "in" -> "id"
                "ji" -> "yi"
                "jv" -> "jw"
                "fil" -> "tl"
                "nb" -> "no"
                "cmn" -> "zh"
                "yue" -> if ("yue" in supportedCodes) "yue" else "zh"
                else -> primary
            }
            return mapped.takeIf { it in supportedCodes }
        }

        private fun preparePcm48kWindow(
            pcm16kMono: ShortArray,
            out48k: FloatArray,
            maxWindowSamples: Int = DEFAULT_LID_WINDOW_SAMPLES,
            allowSilentForTest: Boolean = false,
        ): Int {
            out48k.fill(0f)
            if (pcm16kMono.isEmpty()) return 0

            var maxAbs = 0
            for (s in pcm16kMono) {
                val a = abs(s.toInt())
                if (a > maxAbs) maxAbs = a
            }
            if (!allowSilentForTest && maxAbs < MIN_PEAK_AMPLITUDE) {
                return 0
            }

            val effectiveWindowSamples = maxWindowSamples.coerceIn(MIN_VALID_PCM_SAMPLES, TARGET_PCM_SAMPLES)
            var startSample = 0
            if (pcm16kMono.size > ONSET_RMS_FRAME_SAMPLES * 2) {
                var offset = 0
                while (offset + ONSET_RMS_FRAME_SAMPLES <= pcm16kMono.size) {
                    var sumSq = 0.0
                    for (i in offset until offset + ONSET_RMS_FRAME_SAMPLES) {
                        val v = pcm16kMono[i].toDouble()
                        sumSq += v * v
                    }
                    val rms = sqrt(sumSq / ONSET_RMS_FRAME_SAMPLES.toDouble())
                    if (rms >= SPEECH_ONSET_RMS_THRESHOLD) {
                        startSample = (offset - ONSET_PRE_ROLL_SAMPLES).coerceAtLeast(0)
                        break
                    }
                    offset += ONSET_RMS_FRAME_SAMPLES
                }
            }

            val copyLen = min(effectiveWindowSamples, pcm16kMono.size - startSample).coerceAtLeast(0)
            val norm = 1.0f / 32768.0f
            for (i in 0 until copyLen) {
                out48k[i] = pcm16kMono[startSample + i].toFloat() * norm
            }
            return copyLen
        }

        private fun runInference(
            model: QuantizedWhisperLidModel,
            ws: InferenceWorkspace,
            copiedSamples: Int = TARGET_PCM_SAMPLES,
        ): FloatArray {
            val activeMelFrames = computeLogMelSpectrogram(
                pcm48k = ws.pcm48k,
                melFilters201x80 = model.melFilters201x80,
                ws = ws,
                copiedSamples = copiedSamples,
            )
            runAudioEncoder(model, ws, activeMelFrames)
            return runFusedDecoder(model, ws)
        }

        private fun computeLogMelSpectrogram(
            pcm48k: FloatArray,
            melFilters201x80: FloatArray,
            ws: InferenceWorkspace,
            copiedSamples: Int = TARGET_PCM_SAMPLES,
        ): Int {
            val padded = ws.paddedPcm48400
            val pad = N_FFT / 2 // 200
            for (i in 0 until pad) {
                padded[i] = pcm48k[pad - i]
            }
            System.arraycopy(pcm48k, 0, padded, pad, TARGET_PCM_SAMPLES)
            for (i in 0 until pad) {
                padded[pad + TARGET_PCM_SAMPLES + i] = pcm48k[TARGET_PCM_SAMPLES - 2 - i]
            }

            val activeMelFrames = activeMelFrameCount(copiedSamples)

            val aRe = ws.dftStage1Re
            val aIm = ws.dftStage1Im
            val mag = ws.magnitudes201
            val melOut = ws.mel300x80
            var globalMaxLog = Float.NEGATIVE_INFINITY

            for (t in 0 until activeMelFrames) {
                val baseIdx = t * HOP_LENGTH
                // Stage 1: 20 x 20 (n = 20 * n1 + n0)
                for (m in 0 until 20) {
                    val mRow = m * 20
                    for (n0 in 0 until 20) {
                        var sumRe = 0f
                        var sumIm = 0f
                        for (n1 in 0 until 20) {
                            val idx = n1 * 20 + n0
                            val xw = padded[baseIdx + idx] * HANN_WINDOW_400[idx]
                            sumRe += xw * DFT20_COS[mRow + n1]
                            sumIm += xw * DFT20_SIN[mRow + n1]
                        }
                        aRe[mRow + n0] = sumRe
                        aIm[mRow + n0] = sumIm
                    }
                }

                // Stage 2: k = 0..200
                for (k in 0 until N_FREQ_BINS) {
                    val mRow = (k % 20) * 20
                    val kRow = k * 20
                    var re = 0f
                    var im = 0f
                    for (n0 in 0 until 20) {
                        val ar = aRe[mRow + n0]
                        val ai = aIm[mRow + n0]
                        val tr = TWIDDLE_RE_201_20[kRow + n0]
                        val ti = TWIDDLE_IM_201_20[kRow + n0]
                        re += ar * tr - ai * ti
                        im += ar * ti + ai * tr
                    }
                    mag[k] = re * re + im * im
                }

                // Mel projection (201 -> 80) + log10
                val melRowOffset = t * N_MELS
                for (melIdx in 0 until N_MELS) {
                    melOut[melRowOffset + melIdx] = 0f
                }
                for (k in 0 until N_FREQ_BINS) {
                    val mk = mag[k]
                    val filterRow = k * N_MELS
                    for (melIdx in 0 until N_MELS) {
                        melOut[melRowOffset + melIdx] += mk * melFilters201x80[filterRow + melIdx]
                    }
                }
                for (melIdx in 0 until N_MELS) {
                    val v = max(melOut[melRowOffset + melIdx], 1e-10f)
                    val log10Val = ln(v) * INV_LN_10
                    melOut[melRowOffset + melIdx] = log10Val
                    if (log10Val > globalMaxLog) {
                        globalMaxLog = log10Val
                    }
                }
            }

            if (activeMelFrames < N_FRAMES) {
                if (-10.0f > globalMaxLog) {
                    globalMaxLog = -10.0f
                }
                val startSilentIdx = activeMelFrames * N_MELS
                melOut.fill(-10.0f, startSilentIdx, melOut.size)
            }

            val minClamp = globalMaxLog - 8.0f
            for (i in melOut.indices) {
                val clamped = max(melOut[i], minClamp)
                melOut[i] = (clamped + 4.0f) * 0.25f
            }
            return activeMelFrames
        }

        internal const val MAX_30S_FRAMES: Int = 3_000
        internal const val MEL_FRAMES_30S: Int = MAX_30S_FRAMES
        private const val MAX_30S_SAMPLES: Int = MAX_30S_FRAMES * HOP_LENGTH

        internal fun computeMelSpectrogram80x3000WithFilters(
            pcm16kMono: ShortArray,
            melFilters201x80: FloatArray,
            allowSilentForTest: Boolean = false,
        ): Pair<FloatArray, Int>? {
            if (pcm16kMono.isEmpty() || melFilters201x80.size < N_FREQ_BINS * N_MELS) return null
            var maxAbs = 0
            for (s in pcm16kMono) {
                val a = abs(s.toInt())
                if (a > maxAbs) maxAbs = a
            }
            if (!allowSilentForTest && maxAbs < MIN_PEAK_AMPLITUDE) {
                return null
            }

            val cappedSamples = min(pcm16kMono.size, MAX_30S_SAMPLES)
            val actualFrames = ((cappedSamples + HOP_LENGTH - 1) / HOP_LENGTH).coerceIn(1, MAX_30S_FRAMES)
            val windowSamples = actualFrames * HOP_LENGTH
            val pad = N_FFT / 2 // 200
            val padded = FloatArray(windowSamples + N_FFT)
            val norm = 1.0f / 32768.0f

            for (i in 0 until windowSamples) {
                padded[pad + i] = if (i < cappedSamples) pcm16kMono[i].toFloat() * norm else 0f
            }
            for (i in 0 until pad) {
                val srcIdx = (pad - i).coerceIn(0, windowSamples - 1)
                padded[i] = padded[pad + srcIdx]
            }
            for (i in 0 until pad) {
                val srcIdx = (windowSamples - 2 - i).coerceIn(0, windowSamples - 1)
                padded[pad + windowSamples + i] = padded[pad + srcIdx]
            }

            val aRe = FloatArray(20 * 20)
            val aIm = FloatArray(20 * 20)
            val mag = FloatArray(N_FREQ_BINS)
            val tempMel = FloatArray(actualFrames * N_MELS)
            var globalMaxLog = Float.NEGATIVE_INFINITY

            for (t in 0 until actualFrames) {
                val baseIdx = t * HOP_LENGTH
                for (m in 0 until 20) {
                    val mRow = m * 20
                    for (n0 in 0 until 20) {
                        var sumRe = 0f
                        var sumIm = 0f
                        for (n1 in 0 until 20) {
                            val idx = n1 * 20 + n0
                            val xw = padded[baseIdx + idx] * HANN_WINDOW_400[idx]
                            sumRe += xw * DFT20_COS[mRow + n1]
                            sumIm += xw * DFT20_SIN[mRow + n1]
                        }
                        aRe[mRow + n0] = sumRe
                        aIm[mRow + n0] = sumIm
                    }
                }

                for (k in 0 until N_FREQ_BINS) {
                    val mRow = (k % 20) * 20
                    val kRow = k * 20
                    var re = 0f
                    var im = 0f
                    for (n0 in 0 until 20) {
                        val ar = aRe[mRow + n0]
                        val ai = aIm[mRow + n0]
                        val tr = TWIDDLE_RE_201_20[kRow + n0]
                        val ti = TWIDDLE_IM_201_20[kRow + n0]
                        re += ar * tr - ai * ti
                        im += ar * ti + ai * tr
                    }
                    mag[k] = re * re + im * im
                }

                val melRowOffset = t * N_MELS
                for (k in 0 until N_FREQ_BINS) {
                    val mk = mag[k]
                    val filterRow = k * N_MELS
                    for (melIdx in 0 until N_MELS) {
                        tempMel[melRowOffset + melIdx] += mk * melFilters201x80[filterRow + melIdx]
                    }
                }
                for (melIdx in 0 until N_MELS) {
                    val v = max(tempMel[melRowOffset + melIdx], 1e-10f)
                    val log10Val = ln(v) * INV_LN_10
                    tempMel[melRowOffset + melIdx] = log10Val
                    if (log10Val > globalMaxLog) {
                        globalMaxLog = log10Val
                    }
                }
            }

            if (actualFrames < MAX_30S_FRAMES && -10.0f > globalMaxLog) {
                globalMaxLog = -10.0f
            }
            val minClamp = globalMaxLog - 8.0f
            val silenceNormalized = (max(-10.0f, minClamp) + 4.0f) * 0.25f
            val mel80x3000 = FloatArray(N_MELS * MAX_30S_FRAMES) { silenceNormalized }
            for (t in 0 until actualFrames) {
                val melRowOffset = t * N_MELS
                for (melIdx in 0 until N_MELS) {
                    val clamped = max(tempMel[melRowOffset + melIdx], minClamp)
                    mel80x3000[melIdx * MAX_30S_FRAMES + t] = (clamped + 4.0f) * 0.25f
                }
            }
            return mel80x3000 to actualFrames
        }

        private fun runAudioEncoder(
            model: QuantizedWhisperLidModel,
            ws: InferenceWorkspace,
            activeMelFrames: Int = N_FRAMES,
        ) {
            // Conv1: input mel300x80 (300 x 80), kernel=3, pad=1, stride=1 -> conv1Out (300 x 384)
            val mel = ws.mel300x80
            val conv1Out = ws.conv1Out300x384
            val conv1Scales = model.conv1Weight.scales
            val conv1Quants = model.conv1Weight.quants
            val conv1Bias = model.conv1Bias
            val firstConstConv1Frame = (activeMelFrames + 1).coerceAtMost(N_FRAMES - 1)
            for (o in 0 until D_MODEL) {
                val scale = conv1Scales[o]
                val bias = conv1Bias[o]
                val wBase = o * (3 * N_MELS)
                val w0Off = wBase
                val w1Off = wBase + N_MELS
                val w2Off = wBase + 2 * N_MELS
                var constConv1Val = Float.NaN
                for (t in 0 until N_FRAMES) {
                    if (t in (firstConstConv1Frame + 1) until (N_FRAMES - 1) && !constConv1Val.isNaN()) {
                        conv1Out[t * D_MODEL + o] = constConv1Val
                        continue
                    }
                    var acc = 0f
                    if (t > 0) {
                        val inOff = (t - 1) * N_MELS
                        for (c in 0 until N_MELS) {
                            acc += mel[inOff + c] * conv1Quants[w0Off + c].toInt()
                        }
                    }
                    val in1Off = t * N_MELS
                    for (c in 0 until N_MELS) {
                        acc += mel[in1Off + c] * conv1Quants[w1Off + c].toInt()
                    }
                    if (t + 1 < N_FRAMES) {
                        val in2Off = (t + 1) * N_MELS
                        for (c in 0 until N_MELS) {
                            acc += mel[in2Off + c] * conv1Quants[w2Off + c].toInt()
                        }
                    }
                    val outVal = gelu(acc * scale + bias)
                    conv1Out[t * D_MODEL + o] = outVal
                    if (t == firstConstConv1Frame && t < N_FRAMES - 1) {
                        constConv1Val = outVal
                    }
                }
            }

            // Conv2: input conv1Out (300 x 384), kernel=3, pad=1, stride=2 -> encX (150 x 384) + encPos
            val encX = ws.encX150x384
            val conv2Scales = model.conv2Weight.scales
            val conv2Quants = model.conv2Weight.quants
            val conv2Bias = model.conv2Bias
            val encPos = model.encPos150x384
            val firstConstConv2Out = ((firstConstConv1Frame + 2) / 2).coerceAtMost(N_AUDIO_CTX - 1)
            for (o in 0 until D_MODEL) {
                val scale = conv2Scales[o]
                val bias = conv2Bias[o]
                val wBase = o * (3 * D_MODEL)
                val w0Off = wBase
                val w1Off = wBase + D_MODEL
                val w2Off = wBase + 2 * D_MODEL
                var constConv2Gelu = Float.NaN
                for (tOut in 0 until N_AUDIO_CTX) {
                    val tIn = tOut * 2
                    val idx = tOut * D_MODEL + o
                    if (
                        tOut > firstConstConv2Out &&
                        tIn + 1 < N_FRAMES - 1 &&
                        !constConv2Gelu.isNaN()
                    ) {
                        encX[idx] = constConv2Gelu + encPos[idx]
                        continue
                    }
                    var acc = 0f
                    if (tIn > 0) {
                        val in0Off = (tIn - 1) * D_MODEL
                        for (c in 0 until D_MODEL) {
                            acc += conv1Out[in0Off + c] * conv2Quants[w0Off + c].toInt()
                        }
                    }
                    val in1Off = tIn * D_MODEL
                    for (c in 0 until D_MODEL) {
                        acc += conv1Out[in1Off + c] * conv2Quants[w1Off + c].toInt()
                    }
                    if (tIn + 1 < N_FRAMES) {
                        val in2Off = (tIn + 1) * D_MODEL
                        for (c in 0 until D_MODEL) {
                            acc += conv1Out[in2Off + c] * conv2Quants[w2Off + c].toInt()
                        }
                    }
                    val geluVal = gelu(acc * scale + bias)
                    if (tOut == firstConstConv2Out && tIn - 1 >= firstConstConv1Frame && tIn + 1 < N_FRAMES - 1) {
                        constConv2Gelu = geluVal
                    }
                    encX[idx] = geluVal + encPos[idx]
                }
            }

            // 4層の Encoder Block
            for (layerIdx in 0 until N_AUDIO_LAYER) {
                val blk = model.encBlocks[layerIdx]
                layerNormRows(encX, ws.encNorm150x384, N_AUDIO_CTX, D_MODEL, blk.attnLnW, blk.attnLnB)

                linearQ8Batch(ws.encNorm150x384, N_AUDIO_CTX, blk.qW, blk.qB, ws.encQ150x384, ws.dequantRowBuf)
                linearQ8Batch(ws.encNorm150x384, N_AUDIO_CTX, blk.kW, null, ws.encK150x384, ws.dequantRowBuf)
                linearQ8Batch(ws.encNorm150x384, N_AUDIO_CTX, blk.vW, blk.vB, ws.encV150x384, ws.dequantRowBuf)

                // Multi-head self-attention (6 heads, T=150, D_HEAD=64)
                val q = ws.encQ150x384
                val k = ws.encK150x384
                val v = ws.encV150x384
                val wv = ws.encAttnOut150x384
                val scores = ws.attnScores150
                for (h in 0 until N_HEAD) {
                    val headColOffset = h * D_HEAD
                    for (tQ in 0 until N_AUDIO_CTX) {
                        val qOff = tQ * D_MODEL + headColOffset
                        var maxScore = Float.NEGATIVE_INFINITY
                        for (tK in 0 until N_AUDIO_CTX) {
                            val kOff = tK * D_MODEL + headColOffset
                            var dot = 0f
                            for (d in 0 until D_HEAD) {
                                dot += (q[qOff + d] * ATTN_SCALE) * (k[kOff + d] * ATTN_SCALE)
                            }
                            scores[tK] = dot
                            if (dot > maxScore) maxScore = dot
                        }
                        var sumExp = 0f
                        for (tK in 0 until N_AUDIO_CTX) {
                            val e = exp((scores[tK] - maxScore).toDouble()).toFloat()
                            scores[tK] = e
                            sumExp += e
                        }
                        val invSum = 1.0f / sumExp
                        for (d in 0 until D_HEAD) {
                            var weighted = 0f
                            for (tK in 0 until N_AUDIO_CTX) {
                                weighted += (scores[tK] * invSum) * v[tK * D_MODEL + headColOffset + d]
                            }
                            wv[tQ * D_MODEL + headColOffset + d] = weighted
                        }
                    }
                }

                linearQ8BatchAddResidual(wv, N_AUDIO_CTX, blk.outW, blk.outB, encX, ws.dequantRowBuf)

                // MLP: x = x + mlp2(gelu(mlp1(layerNorm(x))))
                layerNormRows(encX, ws.encNorm150x384, N_AUDIO_CTX, D_MODEL, blk.mlpLnW, blk.mlpLnB)
                linearQ4BatchGelu(ws.encNorm150x384, N_AUDIO_CTX, blk.mlp1W, blk.mlp1B, ws.encMlpHidden150x1536, ws.dequantRowBuf)
                linearQ4BatchAddResidual(ws.encMlpHidden150x1536, N_AUDIO_CTX, blk.mlp2W, blk.mlp2B, encX, ws.dequantRowBuf)
            }

            // Encoder post LayerNorm -> audioFeatures (150 x 384)
            layerNormRows(encX, ws.audioFeatures150x384, N_AUDIO_CTX, D_MODEL, model.lnPostW, model.lnPostB)
        }

        private fun runFusedDecoder(
            model: QuantizedWhisperLidModel,
            ws: InferenceWorkspace,
        ): FloatArray {
            val audioFeatures = ws.audioFeatures150x384
            val xd = ws.decXd384
            System.arraycopy(model.x0AfterSelf384, 0, xd, 0, D_MODEL)

            for (layerIdx in 0 until N_TEXT_LAYER) {
                val blk = model.decBlocks[layerIdx]
                val qk6x384 = if (layerIdx == 0) {
                    model.qk0Const6x384
                } else {
                    // 1-step self-attention: xd = xd + (w_self @ layerNorm(xd) + b_self)
                    layerNormSingle(xd, ws.decNorm384, D_MODEL, blk.attnLnW, blk.attnLnB)
                    linearQ8SingleAddResidual(ws.decNorm384, blk.wSelf, blk.bSelf, xd)

                    // Cross-attention query & key synthesis: qk = einsum('hd,hde->he', q * scale, wk * scale)
                    layerNormSingle(xd, ws.decNorm384, D_MODEL, blk.crossLnW, blk.crossLnB)
                    linearQ8SingleScaled(ws.decNorm384, blk.cqW, blk.cqB, ATTN_SCALE, ws.decQ384)
                    computeCrossQk(ws.decQ384, blk.ckW, ws.decQk6x384)
                    ws.decQk6x384
                }

                // scores = qk @ audioFeatures.T (6 x 150) -> softmax -> pooled = probs @ audioFeatures (6 x 384)
                val pooled = ws.decPooled6x384
                val scores = ws.attnScores150
                for (h in 0 until N_HEAD) {
                    val qkOff = h * D_MODEL
                    var maxScore = Float.NEGATIVE_INFINITY
                    for (t in 0 until N_AUDIO_CTX) {
                        val afOff = t * D_MODEL
                        var dot = 0f
                        for (c in 0 until D_MODEL) {
                            dot += qk6x384[qkOff + c] * audioFeatures[afOff + c]
                        }
                        scores[t] = dot
                        if (dot > maxScore) maxScore = dot
                    }
                    var sumExp = 0f
                    for (t in 0 until N_AUDIO_CTX) {
                        val e = exp((scores[t] - maxScore).toDouble()).toFloat()
                        scores[t] = e
                        sumExp += e
                    }
                    val invSum = 1.0f / sumExp
                    val poolOff = h * D_MODEL
                    for (c in 0 until D_MODEL) {
                        pooled[poolOff + c] = 0f
                    }
                    for (t in 0 until N_AUDIO_CTX) {
                        val p = scores[t] * invSum
                        val afOff = t * D_MODEL
                        for (c in 0 until D_MODEL) {
                            pooled[poolOff + c] += p * audioFeatures[afOff + c]
                        }
                    }
                }

                // wv_out[h] = pooled[h] @ W_v[h].T + b_v[h] -> (384)
                val cvScales = blk.cvW.scales
                val cvQuants = blk.cvW.quants
                val cvBias = blk.cvB
                val wvOut = ws.decWvOut384
                for (h in 0 until N_HEAD) {
                    val poolOff = h * D_MODEL
                    val rowStart = h * D_HEAD
                    for (d in 0 until D_HEAD) {
                        val r = rowStart + d
                        val qRowOff = r * D_MODEL
                        var acc = 0f
                        for (c in 0 until D_MODEL) {
                            acc += pooled[poolOff + c] * cvQuants[qRowOff + c].toInt()
                        }
                        wvOut[r] = acc * cvScales[r] + cvBias[r]
                    }
                }

                // xd = xd + (wvOut @ W_out.T + b_out)
                linearQ8SingleAddResidual(wvOut, blk.coutW, blk.coutB, xd)

                // MLP: xd = xd + mlp2(gelu(mlp1(layerNorm(xd))))
                layerNormSingle(xd, ws.decNorm384, D_MODEL, blk.mlpLnW, blk.mlpLnB)
                linearQ4SingleGelu(ws.decNorm384, blk.mlp1W, blk.mlp1B, ws.decMlpHidden1536)
                linearQ4SingleAddResidual(ws.decMlpHidden1536, blk.mlp2W, blk.mlp2B, xd)
            }

            // Final LayerNorm + 99 language logits
            layerNormSingle(xd, ws.decNorm384, D_MODEL, model.decLnW, model.decLnB)
            val nLangs = model.languageCodes.size
            val logits = FloatArray(nLangs)
            val langScales = model.langEmbW.scales
            val langQuants = model.langEmbW.quants
            for (l in 0 until nLangs) {
                val qRowOff = l * D_MODEL
                var acc = 0f
                for (c in 0 until D_MODEL) {
                    acc += ws.decNorm384[c] * langQuants[qRowOff + c].toInt()
                }
                logits[l] = acc * langScales[l]
            }
            return logits
        }

        private fun layerNormRows(
            input: FloatArray,
            output: FloatArray,
            rows: Int,
            cols: Int,
            weight: FloatArray,
            bias: FloatArray,
        ) {
            val invCols = 1.0f / cols.toFloat()
            for (r in 0 until rows) {
                val off = r * cols
                var sum = 0f
                for (c in 0 until cols) {
                    sum += input[off + c]
                }
                val mean = sum * invCols
                var varSum = 0f
                for (c in 0 until cols) {
                    val diff = input[off + c] - mean
                    varSum += diff * diff
                }
                val invStd = 1.0f / sqrt(varSum * invCols + LAYER_NORM_EPS)
                for (c in 0 until cols) {
                    output[off + c] = (input[off + c] - mean) * invStd * weight[c] + bias[c]
                }
            }
        }

        private fun layerNormSingle(
            input: FloatArray,
            output: FloatArray,
            cols: Int,
            weight: FloatArray?,
            bias: FloatArray?,
        ) {
            if (weight == null || bias == null) return
            val invCols = 1.0f / cols.toFloat()
            var sum = 0f
            for (c in 0 until cols) {
                sum += input[c]
            }
            val mean = sum * invCols
            var varSum = 0f
            for (c in 0 until cols) {
                val diff = input[c] - mean
                varSum += diff * diff
            }
            val invStd = 1.0f / sqrt(varSum * invCols + LAYER_NORM_EPS)
            for (c in 0 until cols) {
                output[c] = (input[c] - mean) * invStd * weight[c] + bias[c]
            }
        }

        private fun linearQ8Batch(
            input: FloatArray,
            batchRows: Int,
            weight: Q8Matrix,
            bias: FloatArray?,
            output: FloatArray,
            rowBuf: FloatArray,
        ) {
            val outDim = weight.rows
            val inDim = weight.cols
            val scales = weight.scales
            val quants = weight.quants
            for (r in 0 until outDim) {
                val scale = scales[r]
                val b = bias?.get(r) ?: 0f
                val qOff = r * inDim
                for (c in 0 until inDim) {
                    rowBuf[c] = quants[qOff + c].toFloat() * scale
                }
                for (t in 0 until batchRows) {
                    val inOff = t * inDim
                    var dot = b
                    for (c in 0 until inDim) {
                        dot += input[inOff + c] * rowBuf[c]
                    }
                    output[t * outDim + r] = dot
                }
            }
        }

        private fun linearQ8BatchAddResidual(
            input: FloatArray,
            batchRows: Int,
            weight: Q8Matrix,
            bias: FloatArray,
            residualInOut: FloatArray,
            rowBuf: FloatArray,
        ) {
            val outDim = weight.rows
            val inDim = weight.cols
            val scales = weight.scales
            val quants = weight.quants
            for (r in 0 until outDim) {
                val scale = scales[r]
                val b = bias[r]
                val qOff = r * inDim
                for (c in 0 until inDim) {
                    rowBuf[c] = quants[qOff + c].toFloat() * scale
                }
                for (t in 0 until batchRows) {
                    val inOff = t * inDim
                    var dot = b
                    for (c in 0 until inDim) {
                        dot += input[inOff + c] * rowBuf[c]
                    }
                    residualInOut[t * outDim + r] += dot
                }
            }
        }

        private fun dequantizeQ4Row(
            weight: Q4Group64Matrix,
            rowIndex: Int,
            rowBuf: FloatArray,
        ) {
            val inDim = weight.cols
            val groupsPerRow = inDim / Q4_GROUP_SIZE
            val groupStart = rowIndex * groupsPerRow
            val scales = weight.scales
            val packed = weight.packedQuants
            var col = 0
            for (g in 0 until groupsPerRow) {
                val gIdx = groupStart + g
                val scale = scales[gIdx]
                var pIdx = gIdx * (Q4_GROUP_SIZE / 2)
                val pEnd = pIdx + (Q4_GROUP_SIZE / 2)
                while (pIdx < pEnd) {
                    val byteVal = packed[pIdx].toInt() and 0xFF
                    val q0 = (byteVal and 0x0F) - 8
                    val q1 = ((byteVal ushr 4) and 0x0F) - 8
                    rowBuf[col] = q0.toFloat() * scale
                    rowBuf[col + 1] = q1.toFloat() * scale
                    col += 2
                    pIdx += 1
                }
            }
        }

        private fun linearQ4BatchGelu(
            input: FloatArray,
            batchRows: Int,
            weight: Q4Group64Matrix,
            bias: FloatArray,
            output: FloatArray,
            rowBuf: FloatArray,
        ) {
            val outDim = weight.rows
            val inDim = weight.cols
            for (r in 0 until outDim) {
                dequantizeQ4Row(weight, r, rowBuf)
                val b = bias[r]
                for (t in 0 until batchRows) {
                    val inOff = t * inDim
                    var dot = b
                    for (c in 0 until inDim) {
                        dot += input[inOff + c] * rowBuf[c]
                    }
                    output[t * outDim + r] = gelu(dot)
                }
            }
        }

        private fun linearQ4BatchAddResidual(
            input: FloatArray,
            batchRows: Int,
            weight: Q4Group64Matrix,
            bias: FloatArray,
            residualInOut: FloatArray,
            rowBuf: FloatArray,
        ) {
            val outDim = weight.rows
            val inDim = weight.cols
            for (r in 0 until outDim) {
                dequantizeQ4Row(weight, r, rowBuf)
                val b = bias[r]
                for (t in 0 until batchRows) {
                    val inOff = t * inDim
                    var dot = b
                    for (c in 0 until inDim) {
                        dot += input[inOff + c] * rowBuf[c]
                    }
                    residualInOut[t * outDim + r] += dot
                }
            }
        }

        private fun linearQ8SingleAddResidual(
            input: FloatArray,
            weight: Q8Matrix?,
            bias: FloatArray?,
            residualInOut: FloatArray,
        ) {
            if (weight == null || bias == null) return
            val outDim = weight.rows
            val inDim = weight.cols
            val scales = weight.scales
            val quants = weight.quants
            for (r in 0 until outDim) {
                val qOff = r * inDim
                var acc = 0f
                for (c in 0 until inDim) {
                    acc += input[c] * quants[qOff + c].toInt()
                }
                residualInOut[r] += acc * scales[r] + bias[r]
            }
        }

        private fun linearQ8SingleScaled(
            input: FloatArray,
            weight: Q8Matrix?,
            bias: FloatArray?,
            postScale: Float,
            output: FloatArray,
        ) {
            if (weight == null || bias == null) return
            val outDim = weight.rows
            val inDim = weight.cols
            val scales = weight.scales
            val quants = weight.quants
            for (r in 0 until outDim) {
                val qOff = r * inDim
                var acc = 0f
                for (c in 0 until inDim) {
                    acc += input[c] * quants[qOff + c].toInt()
                }
                output[r] = (acc * scales[r] + bias[r]) * postScale
            }
        }

        private fun computeCrossQk(
            qScaled384: FloatArray,
            ckW: Q8Matrix?,
            outQk6x384: FloatArray,
        ) {
            if (ckW == null) return
            val scales = ckW.scales
            val quants = ckW.quants
            outQk6x384.fill(0f)
            for (h in 0 until N_HEAD) {
                val headStart = h * D_HEAD
                val outOff = h * D_MODEL
                for (d in 0 until D_HEAD) {
                    val r = headStart + d
                    val qVal = qScaled384[r] * scales[r] * ATTN_SCALE
                    val kRowOff = r * D_MODEL
                    for (e in 0 until D_MODEL) {
                        outQk6x384[outOff + e] += qVal * quants[kRowOff + e].toInt()
                    }
                }
            }
        }

        private fun linearQ4SingleGelu(
            input: FloatArray,
            weight: Q4Group64Matrix,
            bias: FloatArray,
            output: FloatArray,
        ) {
            val outDim = weight.rows
            val inDim = weight.cols
            val groupsPerRow = inDim / Q4_GROUP_SIZE
            val scales = weight.scales
            val packed = weight.packedQuants
            for (r in 0 until outDim) {
                val groupStart = r * groupsPerRow
                var dot = bias[r]
                var col = 0
                for (g in 0 until groupsPerRow) {
                    val gIdx = groupStart + g
                    val scale = scales[gIdx]
                    var groupAcc = 0f
                    var pIdx = gIdx * (Q4_GROUP_SIZE / 2)
                    val pEnd = pIdx + (Q4_GROUP_SIZE / 2)
                    while (pIdx < pEnd) {
                        val byteVal = packed[pIdx].toInt() and 0xFF
                        val q0 = (byteVal and 0x0F) - 8
                        val q1 = ((byteVal ushr 4) and 0x0F) - 8
                        groupAcc += input[col] * q0 + input[col + 1] * q1
                        col += 2
                        pIdx += 1
                    }
                    dot += groupAcc * scale
                }
                output[r] = gelu(dot)
            }
        }

        private fun linearQ4SingleAddResidual(
            input: FloatArray,
            weight: Q4Group64Matrix,
            bias: FloatArray,
            residualInOut: FloatArray,
        ) {
            val outDim = weight.rows
            val inDim = weight.cols
            val groupsPerRow = inDim / Q4_GROUP_SIZE
            val scales = weight.scales
            val packed = weight.packedQuants
            for (r in 0 until outDim) {
                val groupStart = r * groupsPerRow
                var dot = bias[r]
                var col = 0
                for (g in 0 until groupsPerRow) {
                    val gIdx = groupStart + g
                    val scale = scales[gIdx]
                    var groupAcc = 0f
                    var pIdx = gIdx * (Q4_GROUP_SIZE / 2)
                    val pEnd = pIdx + (Q4_GROUP_SIZE / 2)
                    while (pIdx < pEnd) {
                        val byteVal = packed[pIdx].toInt() and 0xFF
                        val q0 = (byteVal and 0x0F) - 8
                        val q1 = ((byteVal ushr 4) and 0x0F) - 8
                        groupAcc += input[col] * q0 + input[col + 1] * q1
                        col += 2
                        pIdx += 1
                    }
                    dot += groupAcc * scale
                }
                residualInOut[r] += dot
            }
        }

        private fun erf(x: Float): Float {
            val sign = if (x < 0f) -1f else 1f
            val ax = abs(x)
            val t = 1.0f / (1.0f + 0.3275911f * ax)
            val poly = (((((1.0614054f * t - 1.453152f) * t) + 1.4214137f) * t - 0.28449672f) * t + 0.2548296f) * t
            val y = 1.0f - poly * exp(-ax * ax)
            return sign * y
        }

        private fun gelu(x: Float): Float = 0.5f * x * (1.0f + erf(x * INV_SQRT_2))

        internal fun parseModelBytes(bytes: ByteArray): QuantizedWhisperLidModel? {
            if (bytes.size < 64) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val m0 = buf.get().toInt().toChar()
            val m1 = buf.get().toInt().toChar()
            val m2 = buf.get().toInt().toChar()
            val m3 = buf.get().toInt().toChar()
            if (m0 != 'W' || m1 != 'L' || m2 != 'I' || m3 != 'D') return null

            val version = buf.int
            val nMels = buf.int
            val nFrames = buf.int
            val nAudioCtx = buf.int
            val dModel = buf.int
            val nHead = buf.int
            val dHead = buf.int
            val dFf = buf.int
            val nAudioLayer = buf.int
            val nTextLayer = buf.int
            val nLangs = buf.int
            val q4GroupSize = buf.int

            if (
                version != 1 ||
                nMels != N_MELS ||
                nFrames != N_FRAMES ||
                nAudioCtx != N_AUDIO_CTX ||
                dModel != D_MODEL ||
                nHead != N_HEAD ||
                dHead != D_HEAD ||
                dFf != D_FF ||
                nAudioLayer != N_AUDIO_LAYER ||
                nTextLayer != N_TEXT_LAYER ||
                nLangs <= 0 ||
                q4GroupSize != Q4_GROUP_SIZE
            ) {
                return null
            }

            val languageCodes = ArrayList<String>(nLangs)
            val languageIndexByCode = LinkedHashMap<String, Int>(nLangs)
            for (i in 0 until nLangs) {
                val len = buf.get().toInt() and 0xFF
                val strBytes = ByteArray(len)
                buf.get(strBytes)
                val code = String(strBytes, Charsets.UTF_8)
                languageCodes.add(code)
                languageIndexByCode[code] = i
            }

            fun readFloatArray(count: Int): FloatArray {
                val out = FloatArray(count)
                for (i in 0 until count) {
                    out[i] = buf.float
                }
                return out
            }

            fun readQ8(rows: Int, cols: Int): Q8Matrix {
                val scales = readFloatArray(rows)
                val quants = ByteArray(rows * cols)
                buf.get(quants)
                return Q8Matrix(rows = rows, cols = cols, scales = scales, quants = quants)
            }

            fun readQ4(rows: Int, cols: Int): Q4Group64Matrix {
                val totalElements = rows * cols
                val numGroups = totalElements / Q4_GROUP_SIZE
                val scales = readFloatArray(numGroups)
                val packed = ByteArray(totalElements / 2)
                buf.get(packed)
                return Q4Group64Matrix(rows = rows, cols = cols, scales = scales, packedQuants = packed)
            }

            val melFilters = readFloatArray(N_FREQ_BINS * N_MELS)
            val conv1W = readQ8(D_MODEL, 3 * N_MELS)
            val conv1B = readFloatArray(D_MODEL)
            val conv2W = readQ8(D_MODEL, 3 * D_MODEL)
            val conv2B = readFloatArray(D_MODEL)
            val encPos = readFloatArray(N_AUDIO_CTX * D_MODEL)

            val encBlocks = Array(N_AUDIO_LAYER) {
                EncoderBlockWeights(
                    attnLnW = readFloatArray(D_MODEL),
                    attnLnB = readFloatArray(D_MODEL),
                    qW = readQ8(D_MODEL, D_MODEL),
                    qB = readFloatArray(D_MODEL),
                    kW = readQ8(D_MODEL, D_MODEL),
                    vW = readQ8(D_MODEL, D_MODEL),
                    vB = readFloatArray(D_MODEL),
                    outW = readQ8(D_MODEL, D_MODEL),
                    outB = readFloatArray(D_MODEL),
                    mlpLnW = readFloatArray(D_MODEL),
                    mlpLnB = readFloatArray(D_MODEL),
                    mlp1W = readQ4(D_FF, D_MODEL),
                    mlp1B = readFloatArray(D_FF),
                    mlp2W = readQ4(D_MODEL, D_FF),
                    mlp2B = readFloatArray(D_MODEL),
                )
            }

            val lnPostW = readFloatArray(D_MODEL)
            val lnPostB = readFloatArray(D_MODEL)
            val x0AfterSelf = readFloatArray(D_MODEL)
            val qk0Const = readFloatArray(N_HEAD * D_MODEL)

            val decBlocks = Array(N_TEXT_LAYER) { i ->
                if (i == 0) {
                    DecoderBlockWeights(
                        attnLnW = null,
                        attnLnB = null,
                        wSelf = null,
                        bSelf = null,
                        crossLnW = null,
                        crossLnB = null,
                        cqW = null,
                        cqB = null,
                        ckW = null,
                        cvW = readQ8(D_MODEL, D_MODEL),
                        cvB = readFloatArray(D_MODEL),
                        coutW = readQ8(D_MODEL, D_MODEL),
                        coutB = readFloatArray(D_MODEL),
                        mlpLnW = readFloatArray(D_MODEL),
                        mlpLnB = readFloatArray(D_MODEL),
                        mlp1W = readQ4(D_FF, D_MODEL),
                        mlp1B = readFloatArray(D_FF),
                        mlp2W = readQ4(D_MODEL, D_FF),
                        mlp2B = readFloatArray(D_MODEL),
                    )
                } else {
                    DecoderBlockWeights(
                        attnLnW = readFloatArray(D_MODEL),
                        attnLnB = readFloatArray(D_MODEL),
                        wSelf = readQ8(D_MODEL, D_MODEL),
                        bSelf = readFloatArray(D_MODEL),
                        crossLnW = readFloatArray(D_MODEL),
                        crossLnB = readFloatArray(D_MODEL),
                        cqW = readQ8(D_MODEL, D_MODEL),
                        cqB = readFloatArray(D_MODEL),
                        ckW = readQ8(D_MODEL, D_MODEL),
                        cvW = readQ8(D_MODEL, D_MODEL),
                        cvB = readFloatArray(D_MODEL),
                        coutW = readQ8(D_MODEL, D_MODEL),
                        coutB = readFloatArray(D_MODEL),
                        mlpLnW = readFloatArray(D_MODEL),
                        mlpLnB = readFloatArray(D_MODEL),
                        mlp1W = readQ4(D_FF, D_MODEL),
                        mlp1B = readFloatArray(D_FF),
                        mlp2W = readQ4(D_MODEL, D_FF),
                        mlp2B = readFloatArray(D_MODEL),
                    )
                }
            }

            val decLnW = readFloatArray(D_MODEL)
            val decLnB = readFloatArray(D_MODEL)
            val langEmbW = readQ8(nLangs, D_MODEL)

            return QuantizedWhisperLidModel(
                languageCodes = languageCodes,
                languageIndexByCode = languageIndexByCode,
                melFilters201x80 = melFilters,
                conv1Weight = conv1W,
                conv1Bias = conv1B,
                conv2Weight = conv2W,
                conv2Bias = conv2B,
                encPos150x384 = encPos,
                encBlocks = encBlocks,
                lnPostW = lnPostW,
                lnPostB = lnPostB,
                x0AfterSelf384 = x0AfterSelf,
                qk0Const6x384 = qk0Const,
                decBlocks = decBlocks,
                decLnW = decLnW,
                decLnB = decLnB,
                langEmbW = langEmbW,
            )
        }
    }

    internal class Q8Matrix(
        val rows: Int,
        val cols: Int,
        val scales: FloatArray,
        val quants: ByteArray,
    )

    internal class Q4Group64Matrix(
        val rows: Int,
        val cols: Int,
        val scales: FloatArray,
        val packedQuants: ByteArray,
    )

    internal class EncoderBlockWeights(
        val attnLnW: FloatArray,
        val attnLnB: FloatArray,
        val qW: Q8Matrix,
        val qB: FloatArray,
        val kW: Q8Matrix,
        val vW: Q8Matrix,
        val vB: FloatArray,
        val outW: Q8Matrix,
        val outB: FloatArray,
        val mlpLnW: FloatArray,
        val mlpLnB: FloatArray,
        val mlp1W: Q4Group64Matrix,
        val mlp1B: FloatArray,
        val mlp2W: Q4Group64Matrix,
        val mlp2B: FloatArray,
    )

    internal class DecoderBlockWeights(
        val attnLnW: FloatArray?,
        val attnLnB: FloatArray?,
        val wSelf: Q8Matrix?,
        val bSelf: FloatArray?,
        val crossLnW: FloatArray?,
        val crossLnB: FloatArray?,
        val cqW: Q8Matrix?,
        val cqB: FloatArray?,
        val ckW: Q8Matrix?,
        val cvW: Q8Matrix,
        val cvB: FloatArray,
        val coutW: Q8Matrix,
        val coutB: FloatArray,
        val mlpLnW: FloatArray,
        val mlpLnB: FloatArray,
        val mlp1W: Q4Group64Matrix,
        val mlp1B: FloatArray,
        val mlp2W: Q4Group64Matrix,
        val mlp2B: FloatArray,
    )

    internal class QuantizedWhisperLidModel(
        val languageCodes: List<String>,
        val languageIndexByCode: Map<String, Int>,
        val melFilters201x80: FloatArray,
        val conv1Weight: Q8Matrix,
        val conv1Bias: FloatArray,
        val conv2Weight: Q8Matrix,
        val conv2Bias: FloatArray,
        val encPos150x384: FloatArray,
        val encBlocks: Array<EncoderBlockWeights>,
        val lnPostW: FloatArray,
        val lnPostB: FloatArray,
        val x0AfterSelf384: FloatArray,
        val qk0Const6x384: FloatArray,
        val decBlocks: Array<DecoderBlockWeights>,
        val decLnW: FloatArray,
        val decLnB: FloatArray,
        val langEmbW: Q8Matrix,
    )

    private class InferenceWorkspace {
        val pcm48k = FloatArray(TARGET_PCM_SAMPLES)
        val paddedPcm48400 = FloatArray(TARGET_PCM_SAMPLES + N_FFT)
        val dftStage1Re = FloatArray(20 * 20)
        val dftStage1Im = FloatArray(20 * 20)
        val magnitudes201 = FloatArray(N_FREQ_BINS)
        val mel300x80 = FloatArray(N_FRAMES * N_MELS)
        val conv1Out300x384 = FloatArray(N_FRAMES * D_MODEL)
        val encX150x384 = FloatArray(N_AUDIO_CTX * D_MODEL)
        val encNorm150x384 = FloatArray(N_AUDIO_CTX * D_MODEL)
        val encQ150x384 = FloatArray(N_AUDIO_CTX * D_MODEL)
        val encK150x384 = FloatArray(N_AUDIO_CTX * D_MODEL)
        val encV150x384 = FloatArray(N_AUDIO_CTX * D_MODEL)
        val encAttnOut150x384 = FloatArray(N_AUDIO_CTX * D_MODEL)
        val encMlpHidden150x1536 = FloatArray(N_AUDIO_CTX * D_FF)
        val audioFeatures150x384 = FloatArray(N_AUDIO_CTX * D_MODEL)
        val dequantRowBuf = FloatArray(D_FF)
        val attnScores150 = FloatArray(N_AUDIO_CTX)
        val decXd384 = FloatArray(D_MODEL)
        val decNorm384 = FloatArray(D_MODEL)
        val decQ384 = FloatArray(D_MODEL)
        val decQk6x384 = FloatArray(N_HEAD * D_MODEL)
        val decPooled6x384 = FloatArray(N_HEAD * D_MODEL)
        val decWvOut384 = FloatArray(D_MODEL)
        val decMlpHidden1536 = FloatArray(D_FF)
    }
}
