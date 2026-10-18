package com.miuraken.chunkflow.core.media

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 音声ファイル内で言語ごとに話者（声のプロファイル）をオンライン学習し、
 * 言語判定（[WhisperTinyLanguageDetector]）の補助・曖昧チャンク補正・高速化を行うトラッカー（F6.2）。
 *
 * 語学教材では対象言語（`target`）と解説言語（`base`）が別々の話者（例：日本語ナレーター＋外国語ネイティブ話者）で
 * 話されることが多い特性を利用する。
 *
 * - 16kHz モノラル PCM の有声（発話）フレームから、声の高さ（基本周波数 $\log F_0$ の統計量）、
 *   スペクトル傾斜・重心、および低次 MFCC（声道包絡の統計量）からなる 32 次元の正規化声色ベクトルを 1ms 未満で抽出する。
 * - 高確証度で言語が確定したチャンク（または手動で言語指定されたチャンク）をアンカーとして、
 *   ファイル（`audioScopeKey`）ごとに `target` 話者クラスタと `base` 話者クラスタ（ダイアログ教材の男女ペアにも対応できるよう各言語最大 2 クラスタ）を更新する。
 */
internal class VoiceProfileLanguageTracker {

    /**
     * 声のプロファイルに基づく 2 言語ペアの事前確率予測。
     *
     * @property targetProbability `targetTag` 話者である事後確率（`0.0..1.0`）。
     * @property baseProbability `baseTag` 話者である事後確率（`0.0..1.0`）。
     * @property targetSimilarity `target` 話者プロトタイプとの最大コサイン類似度（`-1.0..1.0`）。
     * @property baseSimilarity `base` 話者プロトタイプとの最大コサイン類似度（`-1.0..1.0`）。
     * @property isHighConfidenceSpeakerMatch 音声 LID（Whisper Tiny）を省略して即決できるほど
     *   両言語の話者クラスタが分離されており、かつ一方の話者に強く一致しているかどうか。
     */
    data class SpeakerLanguagePrediction(
        val targetProbability: Float,
        val baseProbability: Float,
        val targetSimilarity: Float,
        val baseSimilarity: Float,
        val isHighConfidenceSpeakerMatch: Boolean,
    ) {
        val selectedProbability: Float
            get() = max(targetProbability, baseProbability)
    }

    private class SpeakerCluster(
        val centroid: FloatArray,
        var sampleCount: Int,
    )

    private class FileSpeakerModel {
        val clustersByLang = HashMap<String, MutableList<SpeakerCluster>>()
    }

    private val modelsByScope = ConcurrentHashMap<String, FileSpeakerModel>()

    /**
     * 指定した音声ファイル識別子 [audioScopeKey] の話者プロファイルをクリアする。
     */
    fun clearScope(audioScopeKey: String) {
        modelsByScope.remove(audioScopeKey)
    }

    /**
     * すべてのキャッシュ済み話者プロファイルをクリアする。
     */
    fun clearAll() {
        modelsByScope.clear()
    }

    /**
     * 16kHz モノラル PCM [pcm16kMono] から声のプロファイル（32次元 L2 正規化ベクトル）を抽出する。
     *
     * 有声フレームが不足している極短・無音チャンクでは `null` を返す。
     */
    fun extractVoiceProfile(
        pcm16kMono: ShortArray,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    ): FloatArray? = Companion.extractVoiceProfileVector(pcm16kMono, sampleRateHz)

    /**
     * 声のプロファイル [voiceProfile] をもとに、[audioScopeKey] 内の `targetTag` / `baseTag` 話者との一致度を評価する。
     *
     * まだ両言語の話者サンプルが十分に溜まっていない場合は `null` を返す。
     */
    fun predictLanguageFromVoiceProfile(
        audioScopeKey: String,
        voiceProfile: FloatArray,
        targetTag: String,
        baseTag: String,
    ): SpeakerLanguagePrediction? {
        if (audioScopeKey.isBlank() || voiceProfile.size != PROFILE_DIM) return null
        val model = modelsByScope[audioScopeKey] ?: return null
        val targetKey = normalizeLangKey(targetTag)
        val baseKey = normalizeLangKey(baseTag)
        if (targetKey == baseKey) return null

        synchronized(model) {
            val targetClusters = model.clustersByLang[targetKey]?.takeIf { it.isNotEmpty() } ?: return null
            val baseClusters = model.clustersByLang[baseKey]?.takeIf { it.isNotEmpty() } ?: return null

            val targetTotalSamples = targetClusters.sumOf { it.sampleCount }
            val baseTotalSamples = baseClusters.sumOf { it.sampleCount }
            if (targetTotalSamples < MIN_ANCHOR_SAMPLES_PER_LANG || baseTotalSamples < MIN_ANCHOR_SAMPLES_PER_LANG) {
                return null
            }

            // 同一人物が両言語を話している教材（自己対話等）で誤誘導しないよう、言語間の重心分離度を確認する
            val maxInterLangSim = maxCrossClusterSimilarity(targetClusters, baseClusters)
            if (maxInterLangSim > MAX_INTER_SPEAKER_SIMILARITY_FOR_PRIOR) {
                return null
            }

            val simTarget = targetClusters.maxOf { cosineSimilarity(voiceProfile, it.centroid) }
            val simBase = baseClusters.maxOf { cosineSimilarity(voiceProfile, it.centroid) }
            val diff = simTarget - simBase
            if (abs(diff) < MIN_SIMILARITY_MARGIN) {
                return null
            }

            // コサイン類似度差を温度付きシグモイドで確率 [0, 1] に変換する
            val logit = (diff * SIMILARITY_LOGIT_SCALE).coerceIn(-8.0f, 8.0f)
            val targetProb = (1.0f / (1.0f + exp(-logit.toDouble()).toFloat())).coerceIn(0.05f, 0.95f)
            val baseProb = (1.0f - targetProb).coerceIn(0.05f, 0.95f)

            val bestSim = max(simTarget, simBase)
            val isFastSkipEligible =
                targetTotalSamples >= MIN_SAMPLES_FOR_FAST_SKIP &&
                    baseTotalSamples >= MIN_SAMPLES_FOR_FAST_SKIP &&
                    maxInterLangSim <= MAX_INTER_SPEAKER_SIMILARITY_FOR_FAST_SKIP &&
                    bestSim >= MIN_BEST_SIMILARITY_FOR_FAST_SKIP &&
                    abs(diff) >= MIN_MARGIN_FOR_FAST_SKIP

            return SpeakerLanguagePrediction(
                targetProbability = targetProb,
                baseProbability = baseProb,
                targetSimilarity = simTarget,
                baseSimilarity = simBase,
                isHighConfidenceSpeakerMatch = isFastSkipEligible,
            )
        }
    }

    /**
     * 言語が確定したチャンクの声プロファイル [voiceProfile] を [audioScopeKey] の [languageTag] 話者モデルへ登録する。
     *
     * @param weight 更新の重み（通常チャンクは `1`、ユーザーの手動言語指定など強い教師データは `2`）。
     */
    fun recordSpeakerObservation(
        audioScopeKey: String,
        languageTag: String,
        voiceProfile: FloatArray,
        weight: Int = 1,
    ) {
        if (audioScopeKey.isBlank() || voiceProfile.size != PROFILE_DIM || weight <= 0) return
        val langKey = normalizeLangKey(languageTag)
        if (langKey.isEmpty() || langKey == "und") return

        if (modelsByScope.size >= MAX_TRACKED_FILES && !modelsByScope.containsKey(audioScopeKey)) {
            modelsByScope.keys.firstOrNull()?.let { modelsByScope.remove(it) }
        }
        val model = modelsByScope.computeIfAbsent(audioScopeKey) { FileSpeakerModel() }

        synchronized(model) {
            val clusters = model.clustersByLang.getOrPut(langKey) { mutableListOf() }
            if (clusters.isEmpty()) {
                clusters.add(SpeakerCluster(centroid = voiceProfile.copyOf(), sampleCount = weight))
                return
            }

            var bestIdx = 0
            var bestSim = Float.NEGATIVE_INFINITY
            for (i in clusters.indices) {
                val sim = cosineSimilarity(voiceProfile, clusters[i].centroid)
                if (sim > bestSim) {
                    bestSim = sim
                    bestIdx = i
                }
            }

            // 既存のクラスタと声が大きく異なり（例：男女ペアのダイアログ）、まだ上限クラスタ数未満なら第2話者クラスタを作る
            if (bestSim < NEW_SPEAKER_CLUSTER_SIMILARITY_THRESHOLD && clusters.size < MAX_CLUSTERS_PER_LANG) {
                clusters.add(SpeakerCluster(centroid = voiceProfile.copyOf(), sampleCount = weight))
                return
            }

            val targetCluster = clusters[bestIdx]
            val oldCount = targetCluster.sampleCount.coerceAtMost(MAX_EFFECTIVE_CLUSTER_COUNT)
            val newCount = oldCount + weight
            val alpha = weight.toFloat() / newCount.toFloat()
            val oneMinusAlpha = 1.0f - alpha
            val c = targetCluster.centroid
            for (i in 0 until PROFILE_DIM) {
                c[i] = c[i] * oneMinusAlpha + voiceProfile[i] * alpha
            }
            l2NormalizeInPlace(c)
            targetCluster.sampleCount += weight
        }
    }

    /**
     * Whisper Tiny LID の予測 [whisperPrediction] と、話者プロファイルの予測 [speakerPrediction] を
     * 対数オッズ空間（ベイズ更新）で統合した [LanguagePairPrediction] を返す。
     */
    fun combineWithWhisperPrediction(
        whisperPrediction: LanguagePairPrediction,
        speakerPrediction: SpeakerLanguagePrediction?,
        targetTag: String,
        baseTag: String,
    ): LanguagePairPrediction {
        if (speakerPrediction == null) return whisperPrediction

        val eps = 1e-4f
        val wTarget = whisperPrediction.targetProbability.coerceIn(eps, 1.0f - eps)
        val wBase = whisperPrediction.baseProbability.coerceIn(eps, 1.0f - eps)
        val whisperLogOdds = ln((wTarget / wBase).toDouble()).toFloat()

        val sTarget = speakerPrediction.targetProbability.coerceIn(eps, 1.0f - eps)
        val sBase = speakerPrediction.baseProbability.coerceIn(eps, 1.0f - eps)
        val speakerLogOdds = ln((sTarget / sBase).toDouble()).toFloat()

        // Whisper LID の確信度が低い（0.82 未満）ほど、声のプロファイルの重みを強く効かせる
        val speakerWeight = if (whisperPrediction.selectedProbability < 0.82f) {
            SPEAKER_WEIGHT_WHEN_WHISPER_AMBIGUOUS
        } else {
            SPEAKER_WEIGHT_WHEN_WHISPER_CONFIDENT
        }
        val combinedLogOdds = (whisperLogOdds + speakerWeight * speakerLogOdds).coerceIn(-10.0f, 10.0f)
        val targetProb = (1.0f / (1.0f + exp(-combinedLogOdds.toDouble()).toFloat())).coerceIn(0.0f, 1.0f)
        val baseProb = (1.0f - targetProb).coerceIn(0.0f, 1.0f)

        return if (targetProb >= baseProb) {
            LanguagePairPrediction(
                selectedTag = targetTag,
                fallbackTag = baseTag,
                selectedProbability = targetProb,
                targetProbability = targetProb,
                baseProbability = baseProb,
                inferenceTimeMs = whisperPrediction.inferenceTimeMs,
            )
        } else {
            LanguagePairPrediction(
                selectedTag = baseTag,
                fallbackTag = targetTag,
                selectedProbability = baseProb,
                targetProbability = targetProb,
                baseProbability = baseProb,
                inferenceTimeMs = whisperPrediction.inferenceTimeMs,
            )
        }
    }

    companion object {
        const val PROFILE_DIM: Int = 32
        private const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000
        private const val FRAME_SAMPLES: Int = 400 // 25ms @ 16kHz
        private const val HOP_SAMPLES: Int = 320 // 20ms @ 16kHz
        private const val MAX_ANALYZED_SAMPLES: Int = 40_000 // 冒頭 2.5 秒で十分に声色を取得可能
        private const val MIN_VOICED_FRAMES: Int = 4
        private const val VOICED_RMS_THRESHOLD: Float = 0.008f
        private const val N_FILTER_BANKS: Int = 20
        private const val N_MFCC: Int = 12
        private const val PITCH_SUBSAMPLE_STEP: Int = 2 // 8kHz 相当で自己相関ピッチ推定

        private const val MIN_ANCHOR_SAMPLES_PER_LANG: Int = 1
        private const val MIN_SAMPLES_FOR_FAST_SKIP: Int = 2
        private const val MAX_CLUSTERS_PER_LANG: Int = 2
        private const val MAX_EFFECTIVE_CLUSTER_COUNT: Int = 16
        private const val MAX_TRACKED_FILES: Int = 32

        private const val NEW_SPEAKER_CLUSTER_SIMILARITY_THRESHOLD: Float = 0.55f
        private const val MAX_INTER_SPEAKER_SIMILARITY_FOR_PRIOR: Float = 0.92f
        private const val MAX_INTER_SPEAKER_SIMILARITY_FOR_FAST_SKIP: Float = 0.78f
        private const val MIN_SIMILARITY_MARGIN: Float = 0.06f
        private const val MIN_BEST_SIMILARITY_FOR_FAST_SKIP: Float = 0.78f
        private const val MIN_MARGIN_FOR_FAST_SKIP: Float = 0.20f
        private const val SIMILARITY_LOGIT_SCALE: Float = 6.0f
        private const val SPEAKER_WEIGHT_WHEN_WHISPER_AMBIGUOUS: Float = 0.80f
        private const val SPEAKER_WEIGHT_WHEN_WHISPER_CONFIDENT: Float = 0.35f

        // 1..12 次 MFCC 用の DCT-II テーブル (12 x 20)
        private val DCT_12_BY_20: FloatArray = FloatArray(N_MFCC * N_FILTER_BANKS).apply {
            val scale = sqrt(2.0 / N_FILTER_BANKS.toDouble()).toFloat()
            for (c in 0 until N_MFCC) {
                val m = c + 1
                for (b in 0 until N_FILTER_BANKS) {
                    this[c * N_FILTER_BANKS + b] =
                        (scale * cos(PI * m * (b + 0.5) / N_FILTER_BANKS.toDouble())).toFloat()
                }
            }
        }

        // 各特徴量次元のスケール差（MFCC 平均・標準偏差・ピッチ・スペクトル特徴）を揃える重み
        private val FEATURE_DIM_WEIGHTS: FloatArray = FloatArray(PROFILE_DIM) { idx ->
            when (idx) {
                0 -> 0.05f

                // MFCC 1次（全体の単調スロープは録音環境に依存しやすいため抑制）
                in 1 until N_MFCC -> 0.35f

                // MFCC 2..12次平均（声道フォルマント形状の主成分）
                in N_MFCC until (N_MFCC * 2) -> 0.25f

                // MFCC 標準偏差
                24, 25 -> 2.00f

                // log(F0) 平均・標準偏差（声の高さ：話者差が最も顕著）
                26, 27 -> 1.20f

                // 周期性（ハーモニクス強度）・ゼロ交差率
                else -> 0.80f // サブバンドエネルギー比・スペクトル傾斜
            }
        }

        internal fun normalizeLangKey(rawTag: String): String = rawTag.trim().replace('_', '-').lowercase(Locale.ROOT).substringBefore('-')

        internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            val len = min(a.size, b.size)
            var dot = 0f
            for (i in 0 until len) {
                dot += a[i] * b[i]
            }
            return dot.coerceIn(-1.0f, 1.0f)
        }

        private fun maxCrossClusterSimilarity(
            clustersA: List<SpeakerCluster>,
            clustersB: List<SpeakerCluster>,
        ): Float {
            var maxSim = Float.NEGATIVE_INFINITY
            for (a in clustersA) {
                for (b in clustersB) {
                    val sim = cosineSimilarity(a.centroid, b.centroid)
                    if (sim > maxSim) maxSim = sim
                }
            }
            return maxSim
        }

        private fun l2NormalizeInPlace(vec: FloatArray): Boolean {
            var normSq = 0f
            for (v in vec) {
                normSq += v * v
            }
            if (normSq < 1e-12f) return false
            val invNorm = 1.0f / sqrt(normSq)
            for (i in vec.indices) {
                vec[i] *= invNorm
            }
            return true
        }

        /**
         * 16kHz モノラル PCM から 32 次元の正規化声色プロファイルベクトルを抽出する。
         *
         * 構成（計 32 次元）:
         * - `0..11`: 12 次元 MFCC の有声フレーム平均（ケプストラム平均正規化前後の声道形状）
         * - `12..23`: 12 次元 MFCC の有声フレーム標準偏差
         * - `24`: 有声フレームの正規化 $\log(F_0)$ 平均（ピッチ：男女・個人差）
         * - `25`: 有声フレームの正規化 $\log(F_0)$ 標準偏差（抑揚レンジ）
         * - `26`: 平均ハーモニクス周期性（声の張りと倍音明瞭度）
         * - `27`: 有声フレームの平均ゼロ交差率（ZCR）
         * - `28..31`: 低域・中低域・中高域・高域の 4 サブバンド対数エネルギー比（スペクトル傾斜）
         */
        internal fun extractVoiceProfileVector(
            pcm16kMono: ShortArray,
            sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        ): FloatArray? {
            if (pcm16kMono.size < FRAME_SAMPLES || sampleRateHz <= 0) return null

            // 発話開始位置の探索（先頭の長い無音をスキップ）
            var startOffset = 0
            val norm = 1.0f / 32768.0f
            while (startOffset + FRAME_SAMPLES <= pcm16kMono.size) {
                var sumSq = 0f
                for (i in startOffset until startOffset + FRAME_SAMPLES) {
                    val v = pcm16kMono[i] * norm
                    sumSq += v * v
                }
                if (sqrt(sumSq / FRAME_SAMPLES) >= VOICED_RMS_THRESHOLD) {
                    break
                }
                startOffset += HOP_SAMPLES
            }
            if (startOffset + FRAME_SAMPLES > pcm16kMono.size) {
                startOffset = 0
            }

            val endSample = min(pcm16kMono.size, startOffset + MAX_ANALYZED_SAMPLES)
            if (endSample - startOffset < FRAME_SAMPLES) return null

            val mfccSum = FloatArray(N_MFCC)
            val mfccSqSum = FloatArray(N_MFCC)
            val bandSum = FloatArray(4)
            var voicedCount = 0
            var pitchedCount = 0
            var logF0Sum = 0f
            var logF0SqSum = 0f
            var periodicitySum = 0f
            var zcrSum = 0f

            val framePreemph = FloatArray(FRAME_SAMPLES)
            val subsampled = FloatArray(FRAME_SAMPLES / PITCH_SUBSAMPLE_STEP)
            val filterEnergies = FloatArray(N_FILTER_BANKS)

            // 80Hz .. 380Hz に対応するラグ範囲（8kHz サブサンプル空間）
            val subSampleRate = (sampleRateHz / PITCH_SUBSAMPLE_STEP).coerceAtLeast(2_000)
            val minLag = (subSampleRate / 380).coerceAtLeast(4)
            val maxLag = (subSampleRate / 75).coerceAtMost(subsampled.size / 2)
            val corrByLag = FloatArray(maxLag + 2)

            var offset = startOffset
            while (offset + FRAME_SAMPLES <= endSample) {
                var sumSq = 0f
                var zcr = 0
                var prevRaw = pcm16kMono[offset] * norm
                for (i in 0 until FRAME_SAMPLES) {
                    val curRaw = pcm16kMono[offset + i] * norm
                    sumSq += curRaw * curRaw
                    if (i > 0 && ((curRaw >= 0f && prevRaw < 0f) || (curRaw < 0f && prevRaw >= 0f))) {
                        zcr += 1
                    }
                    // プリエンファシス (1 - 0.97 z^-1)
                    framePreemph[i] = curRaw - 0.97f * prevRaw
                    if (i % PITCH_SUBSAMPLE_STEP == 0) {
                        subsampled[i / PITCH_SUBSAMPLE_STEP] = curRaw
                    }
                    prevRaw = curRaw
                }

                val rms = sqrt(sumSq / FRAME_SAMPLES)
                if (rms >= VOICED_RMS_THRESHOLD) {
                    voicedCount += 1
                    zcrSum += zcr.toFloat() / FRAME_SAMPLES.toFloat()

                    // 1. 自己相関による基本周波数 (F0) と周期性の推定
                    if (maxLag > minLag) {
                        var energy0 = 0f
                        val corrLen = subsampled.size - maxLag
                        for (i in 0 until corrLen) {
                            energy0 += subsampled[i] * subsampled[i]
                        }
                        if (energy0 > 1e-7f) {
                            var bestLag = minLag
                            var bestCorr = -1.0f
                            for (lag in minLag..maxLag) {
                                var corr = 0f
                                var energyLag = 0f
                                for (i in 0 until corrLen) {
                                    val a = subsampled[i]
                                    val b = subsampled[i + lag]
                                    corr += a * b
                                    energyLag += b * b
                                }
                                val denom = sqrt(energy0 * max(energyLag, 1e-7f))
                                val normCorr = if (denom > 0f) corr / denom else 0f
                                corrByLag[lag] = normCorr
                                if (normCorr > bestCorr) {
                                    bestCorr = normCorr
                                    bestLag = lag
                                }
                            }
                            if (bestCorr >= 0.35f) {
                                // サブハーモニック（2*T0, 3*T0）への誤ロックを防ぐため、
                                // 0ラグからの初期下降区間を過ぎて最初に現れる真の局所ピーク（最小ラグ = 基本周期 T0）を採用する
                                val peakThreshold = bestCorr * 0.80f
                                for (lag in (minLag + 1) until maxLag) {
                                    val cur = corrByLag[lag]
                                    val prev = corrByLag[lag - 1]
                                    val next = corrByLag[lag + 1]
                                    if (cur >= peakThreshold && cur > prev && cur >= next) {
                                        bestLag = lag
                                        break
                                    }
                                }
                                val f0Hz = subSampleRate.toFloat() / bestLag.toFloat()
                                // log(F0) を概ね [-1, +1] スケール（基準 160Hz）に正規化
                                val normLogF0 = (ln(f0Hz.toDouble()) - ln(160.0)).toFloat()
                                logF0Sum += normLogF0
                                logF0SqSum += normLogF0 * normLogF0
                                periodicitySum += bestCorr
                                pitchedCount += 1
                            }
                        }
                    }

                    // 2. マルチスケール・タイムドメイン/自己相関フィルタバンクによる 20 バンド対数スペクトル包絡
                    // （各ラグ k = 1..20 の正規化自己相関と差分エネルギーから声道共振特性を高速近似）
                    var eTotal = 1e-8f
                    for (i in 0 until FRAME_SAMPLES) {
                        eTotal += framePreemph[i] * framePreemph[i]
                    }
                    val logTotal = ln(eTotal.toDouble()).toFloat()
                    for (b in 0 until N_FILTER_BANKS) {
                        val lag = b + 1
                        var diffSq = 0f
                        val limit = FRAME_SAMPLES - lag
                        for (i in 0 until limit) {
                            val d = framePreemph[i] - framePreemph[i + lag]
                            diffSq += d * d
                        }
                        val bandRatio = (diffSq / (limit.toFloat() * (eTotal / FRAME_SAMPLES))).coerceAtLeast(1e-6f)
                        filterEnergies[b] = ln(bandRatio.toDouble()).toFloat() + 0.1f * logTotal
                    }

                    // 4 サブバンドのエネルギー分布（スペクトル傾斜）
                    for (sb in 0 until 4) {
                        var sbAcc = 0f
                        val startB = sb * 5
                        for (b in startB until (startB + 5)) {
                            sbAcc += filterEnergies[b]
                        }
                        bandSum[sb] += sbAcc * 0.2f
                    }

                    // DCT-II による 12 次元 MFCC 算出
                    for (c in 0 until N_MFCC) {
                        val dctRow = c * N_FILTER_BANKS
                        var mfccVal = 0f
                        for (b in 0 until N_FILTER_BANKS) {
                            mfccVal += filterEnergies[b] * DCT_12_BY_20[dctRow + b]
                        }
                        mfccSum[c] += mfccVal
                        mfccSqSum[c] += mfccVal * mfccVal
                    }
                }

                offset += HOP_SAMPLES
            }

            if (voicedCount < MIN_VOICED_FRAMES) return null

            val invVoiced = 1.0f / voicedCount.toFloat()
            val out = FloatArray(PROFILE_DIM)

            var mfccMeanAvg = 0f
            var mfccStdAvg = 0f
            for (c in 0 until N_MFCC) {
                val mean = mfccSum[c] * invVoiced
                val variance = max(0f, mfccSqSum[c] * invVoiced - mean * mean)
                val std = sqrt(variance)
                out[c] = mean
                out[N_MFCC + c] = std
                mfccMeanAvg += mean
                mfccStdAvg += std
            }
            mfccMeanAvg /= N_MFCC.toFloat()
            mfccStdAvg /= N_MFCC.toFloat()
            for (c in 0 until N_MFCC) {
                out[c] -= mfccMeanAvg
                out[N_MFCC + c] -= mfccStdAvg
            }

            if (pitchedCount > 0) {
                val invPitched = 1.0f / pitchedCount.toFloat()
                val f0Mean = logF0Sum * invPitched
                val f0Var = max(0f, logF0SqSum * invPitched - f0Mean * f0Mean)
                out[24] = f0Mean
                out[25] = sqrt(f0Var) - 0.12f
                out[26] = (periodicitySum * invPitched) - 0.65f
            } else {
                out[24] = 0f
                out[25] = 0f
                out[26] = 0f
            }

            out[27] = (zcrSum * invVoiced) - 0.15f

            val bandMeanAll = (bandSum[0] + bandSum[1] + bandSum[2] + bandSum[3]) * 0.25f * invVoiced
            for (sb in 0 until 4) {
                out[28 + sb] = (bandSum[sb] * invVoiced) - bandMeanAll
            }

            for (i in 0 until PROFILE_DIM) {
                out[i] *= FEATURE_DIM_WEIGHTS[i]
            }
            return if (l2NormalizeInPlace(out)) out else null
        }
    }
}
