package com.miuraken.chunkflow.core.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.miuraken.chunkflow.core.model.DownloadableSttModel
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguageTag
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln

/**
 * `sherpa-onnx` 形式の Whisper Tiny / Base / Small int8 ONNX モデルを
 * ONNX Runtime で直接実行し、16kHz モノラル PCM チャンクのオフライン音声認識を行うクラス（Stage 2、F6.2）。
 *
 * - Android 標準の `SpeechRecognizer`（SODA）が非対応の言語（Pixel 上の広東語 `yue`・ギリシャ語 `el` 等）や、
 *   利用者が設定の「文字起こし」タブで `sherpa-onnx` ライブラリを選択した場合に使用される。
 */
@Singleton
class SherpaOnnxWhisperTranscriber @Inject constructor(
    private val modelManager: SherpaOnnxModelManager,
    private val languageDetector: WhisperTinyLanguageDetector,
) : AutoCloseable {

    private val sessionLock = Any()
    private val loadedBundles = LinkedHashMap<DownloadableSttModel, LoadedModelBundle>()

    private class LoadedModelBundle(
        val encoderPath: String,
        val encoderLastModified: Long,
        val decoderPath: String,
        val decoderLastModified: Long,
        val tokensPath: String,
        val tokensLastModified: Long,
        val env: OrtEnvironment,
        val encoderSession: OrtSession,
        val decoderSession: OrtSession,
        val tokenBytesById: Map<Int, ByteArray>,
        val config: WhisperModelConfig,
    ) : AutoCloseable {
        override fun close() {
            runCatching { encoderSession.close() }
            runCatching { decoderSession.close() }
        }
    }

    internal data class WhisperModelConfig(
        val nTextLayer: Int = DEFAULT_N_TEXT_LAYER,
        val nTextCtx: Int = DEFAULT_N_TEXT_CTX,
        val nTextState: Int = DEFAULT_N_TEXT_STATE,
        val nVocab: Int = DEFAULT_N_VOCAB,
        val sotToken: Long = DEFAULT_SOT_TOKEN,
        val eotToken: Long = DEFAULT_EOT_TOKEN,
        val transcribeToken: Long = DEFAULT_TRANSCRIBE_TOKEN,
        val noTimestampsToken: Long = DEFAULT_NO_TIMESTAMPS_TOKEN,
        val noSpeechToken: Long = DEFAULT_NO_SPEECH_TOKEN,
        val languageTokenByCode: Map<String, Long> = emptyMap(),
    ) {
        fun resolveLanguageToken(language: LanguageTag): Long? {
            val normalized = SherpaOnnxModelManager.normalizeLanguageKey(language)
            languageTokenByCode[normalized]?.let { return it }
            val langIdx = WhisperTinyLanguageDetector.languageIndexForTag(normalized) ?: return null
            return FIRST_LANGUAGE_TOKEN_ID + langIdx.toLong()
        }
    }

    /**
     * 指定した言語 [language] で `sherpa-onnx` ローカルモデル（[model]）による認識が可能かどうか。
     */
    fun isReadyForLanguage(
        language: LanguageTag,
        model: DownloadableSttModel = DownloadableSttModel.SHERPA_WHISPER_TINY,
    ): Boolean = modelManager.getLanguageModelStatus(model, language) ==
        com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED

    /**
     * 指定した言語コード [languageCode] で `sherpa-onnx` ローカルモデル（[model]）による認識が可能かどうか。
     */
    fun isReadyForLanguage(
        languageCode: String,
        model: DownloadableSttModel = DownloadableSttModel.SHERPA_WHISPER_TINY,
    ): Boolean = isReadyForLanguage(LanguageTag(languageCode), model)

    /**
     * 16kHz モノラル 16-bit リトルエンディアン PCM（[pcm16LeBytes]）を指定言語 [language] で音声認識する。
     *
     * @param ignoreLanguageMarker `true` の場合は言語ごとの `.ready` マーカー有無を問わず、共通モデルファイルが存在すれば推論を実行する。
     * @param model 使用する Whisper モデル（Tiny / Base / Small）。
     */
    fun transcribePcm16Le(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
        language: LanguageTag,
        ignoreLanguageMarker: Boolean = false,
        model: com.miuraken.chunkflow.core.model.DownloadableSttModel = com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_WHISPER_TINY,
    ): OnDeviceChunkTranscriptionResult? {
        if (pcm16LeBytes.isEmpty() || sampleRateHz <= 0) return null
        val shorts = ChunkPcmExtractor.pcm16LeBytesToShorts(pcm16LeBytes)
        val resampled = if (sampleRateHz == OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ) {
            shorts
        } else {
            resampleTo16k(shorts, sampleRateHz)
        }
        return transcribePcm16kMono(
            pcm16kMono = resampled,
            language = language,
            ignoreLanguageMarker = ignoreLanguageMarker,
            model = model,
        )
    }

    /**
     * 16kHz モノラル 16-bit PCM（[pcm16kMono]）を指定言語 [language] で音声認識する。
     */
    fun transcribePcm16kMono(
        pcm16kMono: ShortArray,
        language: LanguageTag,
        ignoreLanguageMarker: Boolean = false,
        model: com.miuraken.chunkflow.core.model.DownloadableSttModel = com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_WHISPER_TINY,
    ): OnDeviceChunkTranscriptionResult? {
        if (pcm16kMono.isEmpty()) return null
        if (!ignoreLanguageMarker && !isReadyForLanguage(language, model)) {
            return null
        }
        val mel = languageDetector.computeMelSpectrogram80x3000(pcm16kMono) ?: return null

        synchronized(sessionLock) {
            val bundle = ensureLoadedBundleLocked(model) ?: return null
            val langToken = bundle.config.resolveLanguageToken(language) ?: return null
            return try {
                runGreedyDecodeLocked(
                    bundle = bundle,
                    mel80x3000 = mel,
                    language = language,
                    languageToken = langToken,
                    engineId = EngineId(model.modelId),
                )
            } catch (e: Exception) {
                logInfo("sherpa-onnx inference failed model=${model.modelId} lang=${language.value} error=${e.javaClass.simpleName}")
                null
            }
        }
    }

    private fun runGreedyDecodeLocked(
        bundle: LoadedModelBundle,
        mel80x3000: FloatArray,
        language: LanguageTag,
        languageToken: Long,
        engineId: EngineId = SHERPA_ONNX_ENGINE_ID,
    ): OnDeviceChunkTranscriptionResult? {
        val env = bundle.env
        val config = bundle.config

        val melShape = longArrayOf(1L, WhisperTinyLanguageDetector.N_MELS.toLong(), WhisperTinyLanguageDetector.MEL_FRAMES_30S.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mel80x3000), melShape).use { melTensor ->
            bundle.encoderSession.run(mapOf(INPUT_MEL to melTensor)).use { encoderResult ->
                val crossK = encoderResult.get(OUTPUT_CROSS_K).orElse(null) as? OnnxTensor ?: return null
                val crossV = encoderResult.get(OUTPUT_CROSS_V).orElse(null) as? OnnxTensor ?: return null

                val cacheElementCount = config.nTextLayer * 1 * config.nTextCtx * config.nTextState
                val cacheShape = longArrayOf(
                    config.nTextLayer.toLong(),
                    1L,
                    config.nTextCtx.toLong(),
                    config.nTextState.toLong(),
                )
                val initialKCache = OnnxTensor.createTensor(env, FloatBuffer.allocate(cacheElementCount), cacheShape)
                val initialVCache = OnnxTensor.createTensor(env, FloatBuffer.allocate(cacheElementCount), cacheShape)

                var currentKCache: OnnxTensor = initialKCache
                var currentVCache: OnnxTensor = initialVCache
                var activeDecoderResult: OrtSession.Result? = null

                try {
                    val promptTokens = longArrayOf(
                        config.sotToken,
                        languageToken,
                        config.transcribeToken,
                        config.noTimestampsToken,
                    )
                    var nextToken: Long
                    var offset = 0L
                    var sumLogProb = 0.0
                    var generatedCount = 0
                    val generatedTokenIds = IntArray(MAX_DECODE_STEPS)

                    val promptShape = longArrayOf(1L, promptTokens.size.toLong())
                    val offsetShape = longArrayOf(1L)

                    OnnxTensor.createTensor(env, LongBuffer.wrap(promptTokens), promptShape).use { tokensTensor ->
                        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(offset)), offsetShape).use { offsetTensor ->
                            val inputs = mapOf(
                                INPUT_TOKENS to tokensTensor,
                                INPUT_SELF_K_CACHE to currentKCache,
                                INPUT_SELF_V_CACHE to currentVCache,
                                INPUT_CROSS_K to crossK,
                                INPUT_CROSS_V to crossV,
                                INPUT_OFFSET to offsetTensor,
                            )
                            val stepResult = bundle.decoderSession.run(inputs)
                            val logitsTensor = stepResult.get(OUTPUT_LOGITS).orElse(null) as? OnnxTensor
                            val outK = stepResult.get(OUTPUT_SELF_K_CACHE).orElse(null) as? OnnxTensor
                            val outV = stepResult.get(OUTPUT_SELF_V_CACHE).orElse(null) as? OnnxTensor
                            if (logitsTensor == null || outK == null || outV == null) {
                                stepResult.close()
                                return null
                            }
                            var picked = selectGreedyNextToken(
                                logitsBuffer = logitsTensor.floatBuffer,
                                seqLength = promptTokens.size,
                                vocabSize = config.nVocab,
                                suppressBlank = true,
                                suppressEot = false,
                                config = config,
                            )
                            if (picked.tokenId.toLong() == config.eotToken) {
                                val nonEotPicked = selectGreedyNextToken(
                                    logitsBuffer = logitsTensor.floatBuffer,
                                    seqLength = promptTokens.size,
                                    vocabSize = config.nVocab,
                                    suppressBlank = true,
                                    suppressEot = true,
                                    config = config,
                                )
                                if (nonEotPicked.tokenId.toLong() != config.eotToken &&
                                    nonEotPicked.logProb >= MIN_INITIAL_NON_EOT_LOG_PROB
                                ) {
                                    picked = nonEotPicked
                                }
                            }
                            nextToken = picked.tokenId.toLong()
                            if (nextToken != config.eotToken) {
                                sumLogProb += picked.logProb
                            }
                            offset += promptTokens.size.toLong()

                            currentKCache.close()
                            currentVCache.close()
                            activeDecoderResult = stepResult
                            currentKCache = outK
                            currentVCache = outV
                        }
                    }

                    val singleTokenShape = longArrayOf(1L, 1L)
                    while (generatedCount < MAX_DECODE_STEPS && nextToken != config.eotToken) {
                        if (isRepeatedTailLoop(generatedTokenIds, generatedCount, nextToken.toInt())) {
                            break
                        }
                        generatedTokenIds[generatedCount] = nextToken.toInt()
                        generatedCount += 1

                        OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(nextToken)), singleTokenShape).use { tokenTensor ->
                            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(offset)), offsetShape).use { offsetTensor ->
                                val inputs = mapOf(
                                    INPUT_TOKENS to tokenTensor,
                                    INPUT_SELF_K_CACHE to currentKCache,
                                    INPUT_SELF_V_CACHE to currentVCache,
                                    INPUT_CROSS_K to crossK,
                                    INPUT_CROSS_V to crossV,
                                    INPUT_OFFSET to offsetTensor,
                                )
                                val stepResult = bundle.decoderSession.run(inputs)
                                val logitsTensor = stepResult.get(OUTPUT_LOGITS).orElse(null) as? OnnxTensor
                                val outK = stepResult.get(OUTPUT_SELF_K_CACHE).orElse(null) as? OnnxTensor
                                val outV = stepResult.get(OUTPUT_SELF_V_CACHE).orElse(null) as? OnnxTensor
                                if (logitsTensor == null || outK == null || outV == null) {
                                    stepResult.close()
                                    break
                                }
                                val picked = selectGreedyNextToken(
                                    logitsBuffer = logitsTensor.floatBuffer,
                                    seqLength = 1,
                                    vocabSize = config.nVocab,
                                    suppressBlank = false,
                                    suppressEot = false,
                                    config = config,
                                )
                                nextToken = picked.tokenId.toLong()
                                if (nextToken != config.eotToken) {
                                    sumLogProb += picked.logProb
                                }
                                offset += 1L

                                activeDecoderResult?.close()
                                activeDecoderResult = stepResult
                                currentKCache = outK
                                currentVCache = outV
                            }
                        }
                    }

                    if (generatedCount == 0) return null
                    val decodedText = decodeTokensToString(
                        tokenIds = generatedTokenIds,
                        count = generatedCount,
                        tokenBytesById = bundle.tokenBytesById,
                        eotTokenId = config.eotToken.toInt(),
                    ).trim()
                    if (decodedText.isEmpty()) return null

                    val avgLogProb = sumLogProb / generatedCount.toDouble()
                    val confidence = exp(avgLogProb).toFloat().coerceIn(0.05f, 1.0f)

                    return OnDeviceChunkTranscriptionResult(
                        text = decodedText,
                        language = language,
                        confidence = confidence,
                        engineId = engineId,
                    )
                } finally {
                    runCatching { activeDecoderResult?.close() }
                    runCatching { initialKCache.close() }
                    runCatching { initialVCache.close() }
                }
            }
        }
    }

    private data class TokenPick(
        val tokenId: Int,
        val logProb: Double,
    )

    private fun selectGreedyNextToken(
        logitsBuffer: FloatBuffer,
        seqLength: Int,
        vocabSize: Int,
        suppressBlank: Boolean,
        suppressEot: Boolean = false,
        config: WhisperModelConfig,
    ): TokenPick {
        val lastTokenOffset = (seqLength - 1) * vocabSize
        val eotId = config.eotToken.toInt()
        val sotId = config.sotToken.toInt()

        var bestId = eotId
        var bestLogit = Float.NEGATIVE_INFINITY
        var maxLogitForSoftmax = Float.NEGATIVE_INFINITY

        for (tokenId in 0 until vocabSize) {
            val rawLogit = logitsBuffer.get(lastTokenOffset + tokenId)
            if (rawLogit > maxLogitForSoftmax) {
                maxLogitForSoftmax = rawLogit
            }
            if (isSuppressedToken(tokenId, eotId, sotId, suppressBlank, suppressEot, config)) {
                continue
            }
            if (rawLogit > bestLogit) {
                bestLogit = rawLogit
                bestId = tokenId
            }
        }

        if (!bestLogit.isFinite() || !maxLogitForSoftmax.isFinite()) {
            return TokenPick(tokenId = eotId, logProb = -10.0)
        }

        var sumExp = 0.0
        for (tokenId in 0 until vocabSize) {
            val rawLogit = logitsBuffer.get(lastTokenOffset + tokenId)
            sumExp += exp((rawLogit - maxLogitForSoftmax).toDouble())
        }
        val logProb = if (sumExp > 0.0) {
            (bestLogit - maxLogitForSoftmax).toDouble() - ln(sumExp)
        } else {
            -5.0
        }
        return TokenPick(tokenId = bestId, logProb = logProb)
    }

    private fun isSuppressedToken(
        tokenId: Int,
        eotId: Int,
        sotId: Int,
        suppressBlank: Boolean,
        suppressEot: Boolean,
        config: WhisperModelConfig,
    ): Boolean {
        if (tokenId == eotId) return suppressEot
        if (suppressBlank && tokenId == SPACE_TOKEN_ID) return true
        if (tokenId == config.noSpeechToken.toInt()) return true
        // SOT 以降の特殊トークン（言語トークン・タスク・タイムスタンプ等）は通常の文字生成では抑制する
        return tokenId >= sotId
    }

    private fun isRepeatedTailLoop(
        generatedTokenIds: IntArray,
        generatedCount: Int,
        candidateTokenId: Int,
    ): Boolean {
        // 1. 同一トークンが 6 回連続した場合はループとみなして打ち切る
        if (generatedCount >= MAX_IDENTICAL_TOKEN_REPEAT) {
            var allSame = true
            for (i in 1..MAX_IDENTICAL_TOKEN_REPEAT) {
                if (generatedTokenIds[generatedCount - i] != candidateTokenId) {
                    allSame = false
                    break
                }
            }
            if (allSame) return true
        }
        // 2. 2〜8 トークン周期のフレーズ反復（アラビア語等のマルチバイト BPE ループ）が 3 回以上連続した場合も打ち切る
        val nextLen = generatedCount + 1
        for (period in 2..MAX_NGRAM_LOOP_PERIOD) {
            val requiredLen = period * MIN_NGRAM_LOOP_REPEATS
            if (nextLen < requiredLen) continue
            var isRepeating = true
            for (offset in 0 until (requiredLen - period)) {
                val idxA = nextLen - 1 - offset
                val idxB = idxA - period
                val tokenA = if (idxA == generatedCount) candidateTokenId else generatedTokenIds[idxA]
                val tokenB = generatedTokenIds[idxB]
                if (tokenA != tokenB) {
                    isRepeating = false
                    break
                }
            }
            if (isRepeating) return true
        }
        return false
    }

    private fun ensureLoadedBundleLocked(
        model: DownloadableSttModel = DownloadableSttModel.SHERPA_WHISPER_TINY,
    ): LoadedModelBundle? {
        val paths = modelManager.resolveWhisperModelPaths(model) ?: run {
            loadedBundles.remove(model)?.close()
            return null
        }

        val encMod = paths.encoderFile.lastModified()
        val decMod = paths.decoderFile.lastModified()
        val tokMod = paths.tokensFile.lastModified()

        val existing = loadedBundles[model]
        if (existing != null &&
            existing.encoderPath == paths.encoderFile.absolutePath &&
            existing.encoderLastModified == encMod &&
            existing.decoderPath == paths.decoderFile.absolutePath &&
            existing.decoderLastModified == decMod &&
            existing.tokensPath == paths.tokensFile.absolutePath &&
            existing.tokensLastModified == tokMod
        ) {
            return existing
        }

        existing?.close()
        loadedBundles.remove(model)

        return try {
            val tokenMap = parseTokensFile(paths.tokensFile)
            if (tokenMap.isEmpty()) return null

            val env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }
            val encoderSession = sessionOptions.use { opts ->
                env.createSession(paths.encoderFile.absolutePath, opts)
            }
            val decoderSession = try {
                OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(2)
                }.use { opts ->
                    env.createSession(paths.decoderFile.absolutePath, opts)
                }
            } catch (e: Exception) {
                runCatching { encoderSession.close() }
                throw e
            }

            val config = parseEncoderMetadataConfig(encoderSession)
            LoadedModelBundle(
                encoderPath = paths.encoderFile.absolutePath,
                encoderLastModified = encMod,
                decoderPath = paths.decoderFile.absolutePath,
                decoderLastModified = decMod,
                tokensPath = paths.tokensFile.absolutePath,
                tokensLastModified = tokMod,
                env = env,
                encoderSession = encoderSession,
                decoderSession = decoderSession,
                tokenBytesById = tokenMap,
                config = config,
            ).also {
                loadedBundles[model] = it
            }
        } catch (e: Exception) {
            logInfo("failed to load sherpa-onnx sessions error=${e.javaClass.simpleName}")
            null
        }
    }

    private fun parseEncoderMetadataConfig(encoderSession: OrtSession): WhisperModelConfig {
        val custom = runCatching { encoderSession.metadata.customMetadata }.getOrNull().orEmpty()
        val nTextLayer = custom["n_text_layer"]?.toIntOrNull() ?: DEFAULT_N_TEXT_LAYER
        val nTextCtx = custom["n_text_ctx"]?.toIntOrNull() ?: DEFAULT_N_TEXT_CTX
        val nTextState = custom["n_text_state"]?.toIntOrNull() ?: DEFAULT_N_TEXT_STATE
        val nVocab = custom["n_vocab"]?.toIntOrNull() ?: DEFAULT_N_VOCAB
        val sotToken = custom["sot"]?.toLongOrNull() ?: DEFAULT_SOT_TOKEN
        val eotToken = custom["eot"]?.toLongOrNull() ?: DEFAULT_EOT_TOKEN
        val transcribeToken = custom["transcribe"]?.toLongOrNull() ?: DEFAULT_TRANSCRIBE_TOKEN
        val noTimestampsToken = custom["no_timestamps"]?.toLongOrNull() ?: DEFAULT_NO_TIMESTAMPS_TOKEN
        val noSpeechToken = custom["no_speech"]?.toLongOrNull() ?: DEFAULT_NO_SPEECH_TOKEN

        val langCodes = custom["all_language_codes"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val langTokens = custom["all_language_tokens"]
            ?.split(',')
            ?.mapNotNull { it.trim().toLongOrNull() }
            .orEmpty()
        val langMap = if (langCodes.isNotEmpty() && langCodes.size == langTokens.size) {
            langCodes.zip(langTokens).toMap()
        } else {
            emptyMap()
        }

        return WhisperModelConfig(
            nTextLayer = nTextLayer,
            nTextCtx = nTextCtx,
            nTextState = nTextState,
            nVocab = nVocab,
            sotToken = sotToken,
            eotToken = eotToken,
            transcribeToken = transcribeToken,
            noTimestampsToken = noTimestampsToken,
            noSpeechToken = noSpeechToken,
            languageTokenByCode = langMap,
        )
    }

    override fun close() {
        synchronized(sessionLock) {
            for (bundle in loadedBundles.values) {
                bundle.close()
            }
            loadedBundles.clear()
        }
    }

    companion object {
        val SHERPA_ONNX_ENGINE_ID: EngineId = EngineId(SherpaOnnxModelManager.MODEL_ID)
        val SHERPA_ONNX_BASE_ENGINE_ID: EngineId = EngineId(DownloadableSttModel.SHERPA_WHISPER_BASE.modelId)
        val SHERPA_ONNX_SMALL_ENGINE_ID: EngineId = EngineId(DownloadableSttModel.SHERPA_WHISPER_SMALL.modelId)

        private const val INPUT_MEL: String = "mel"
        private const val OUTPUT_CROSS_K: String = "n_layer_cross_k"
        private const val OUTPUT_CROSS_V: String = "n_layer_cross_v"

        private const val INPUT_TOKENS: String = "tokens"
        private const val INPUT_SELF_K_CACHE: String = "in_n_layer_self_k_cache"
        private const val INPUT_SELF_V_CACHE: String = "in_n_layer_self_v_cache"
        private const val INPUT_CROSS_K: String = "n_layer_cross_k"
        private const val INPUT_CROSS_V: String = "n_layer_cross_v"
        private const val INPUT_OFFSET: String = "offset"

        private const val OUTPUT_LOGITS: String = "logits"
        private const val OUTPUT_SELF_K_CACHE: String = "out_n_layer_self_k_cache"
        private const val OUTPUT_SELF_V_CACHE: String = "out_n_layer_self_v_cache"

        private const val DEFAULT_N_TEXT_LAYER: Int = 4
        private const val DEFAULT_N_TEXT_CTX: Int = 448
        private const val DEFAULT_N_TEXT_STATE: Int = 384
        private const val DEFAULT_N_VOCAB: Int = 51_865
        private const val DEFAULT_EOT_TOKEN: Long = 50_257L
        private const val DEFAULT_SOT_TOKEN: Long = 50_258L
        private const val FIRST_LANGUAGE_TOKEN_ID: Long = 50_259L
        private const val DEFAULT_TRANSCRIBE_TOKEN: Long = 50_359L
        private const val DEFAULT_NO_SPEECH_TOKEN: Long = 50_362L
        private const val DEFAULT_NO_TIMESTAMPS_TOKEN: Long = 50_363L

        private const val SPACE_TOKEN_ID: Int = 220
        private const val MAX_DECODE_STEPS: Int = 160
        private const val MAX_IDENTICAL_TOKEN_REPEAT: Int = 6
        private const val MAX_NGRAM_LOOP_PERIOD: Int = 8
        private const val MIN_NGRAM_LOOP_REPEATS: Int = 3
        private const val MIN_INITIAL_NON_EOT_LOG_PROB: Double = -3.0
        private const val LOG_TAG: String = "ChunkFlowSherpa"

        /**
         * `sherpa-onnx` の `tiny-tokens.txt`（各行 `<base64_bytes> <token_id>`）を解析し、
         * トークン ID から UTF-8 バイト列へのマップを返す。
         */
        internal fun parseTokensFile(tokensFile: File): Map<Int, ByteArray> {
            if (!tokensFile.isFile) return emptyMap()
            val decoder = Base64.getDecoder()
            val map = HashMap<Int, ByteArray>(52_000)
            tokensFile.useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@forEach
                    val spaceIdx = line.indexOf(' ')
                    if (spaceIdx <= 0) return@forEach
                    val b64Part = line.substring(0, spaceIdx).trim()
                    val idPart = line.substring(spaceIdx + 1).trim()
                    val tokenId = idPart.toIntOrNull() ?: return@forEach
                    val bytes = runCatching { decoder.decode(b64Part) }.getOrNull() ?: return@forEach
                    map[tokenId] = bytes
                }
            }
            return map
        }

        /**
         * 生成されたトークン ID 列 [tokenIds]（先頭 [count] 個）を UTF-8 文字列へ変換する。
         * `<|...✨|>` のような特殊制御トークンは出力文字列から除外する。
         */
        internal fun decodeTokensToString(
            tokenIds: IntArray,
            count: Int = tokenIds.size,
            tokenBytesById: Map<Int, ByteArray>,
            eotTokenId: Int = DEFAULT_EOT_TOKEN.toInt(),
        ): String {
            val out = ByteArrayOutputStream(count * 4)
            val safeCount = count.coerceIn(0, tokenIds.size)
            for (i in 0 until safeCount) {
                val id = tokenIds[i]
                if (id == eotTokenId || id >= DEFAULT_SOT_TOKEN.toInt()) continue
                val bytes = tokenBytesById[id] ?: continue
                if (isSpecialControlTokenBytes(bytes)) continue
                out.write(bytes)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        private fun isSpecialControlTokenBytes(bytes: ByteArray): Boolean = bytes.size >= 4 &&
            bytes[0] == '<'.code.toByte() &&
            bytes[1] == '|'.code.toByte() &&
            bytes[bytes.size - 2] == '|'.code.toByte() &&
            bytes[bytes.size - 1] == '>'.code.toByte()

        private fun resampleTo16k(input: ShortArray, inputRateHz: Int): ShortArray {
            if (inputRateHz <= 0 || input.isEmpty()) return input
            val targetRate = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ
            if (inputRateHz == targetRate) return input
            val outLength = ((input.size.toLong() * targetRate) / inputRateHz).toInt().coerceAtLeast(1)
            val out = ShortArray(outLength)
            val ratio = inputRateHz.toDouble() / targetRate.toDouble()
            for (i in 0 until outLength) {
                val srcPos = i * ratio
                val idx = srcPos.toInt().coerceIn(0, input.lastIndex)
                out[i] = input[idx]
            }
            return out
        }

        private fun logInfo(message: String) {
            runCatching {
                if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                    Log.i(LOG_TAG, message)
                }
            }
        }
    }
}
