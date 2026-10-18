package com.miuraken.chunkflow.core.media

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 音声ファイル内で言語が確定したチャンクの「声と言語の特徴ベクトル」を集めて、
 * 言語判定（[WhisperTinyLanguageDetector]）をファイルごとに補正するトラッカー（F6.2）。
 *
 * 語学教材では、解説言語（`base`）を 1 人のナレーターが話し、対象言語（`target`）を複数のネイティブ話者が話すことが多い。
 * 話者の声色と言語の音響的な違いは、Whisper エンコーダ出力の平均ベクトル（[LanguagePairAnalysis.encoderEmbedding]）に
 * よく表れるため、これを特徴量に使う（言語判定と同じ推論の集計なので、追加の計算はほとんどない）。
 *
 * - **アンカー**: Whisper 単体で高確信のチャンク（[isAnchorEligible]）。統合後の確率ではなく Whisper 単体の確信度で選び、
 *   自分の予測で自分を学習する自己強化を防ぐ。同じチャンクを登録し直すと置き換える（二重登録しない）。
 * - **予測**: 言語ごとに、類似度が上位 [KNN_K] 件のアンカーの平均類似度を求め、その差を対数オッズに校正する。
 *   校正は、アンカー自身の Leave-One-Out の差に当てはめたロジスティック回帰（Platt スケーリング）で行う。
 *   話者が言語で分かれていない教材（1 人が両言語を話すなど）では傾きがほぼ 0 になり、自動的に効かなくなる。
 *   対象言語の話者が複数いても、近傍で判定するので話者ごとのクラスタを作る必要はない。
 * - **統合**: Whisper の 2 言語の対数オッズは、ペア外の言語に確率が流れているとき（[LanguagePairPrediction.pairMass] が小さい）や
 *   短いチャンクで過信しやすいため割り引き（[whisperReliability]）、校正済みの近傍の対数オッズを足す。
 *
 * スレッドセーフ。ファイルごとの学習内容はメモリ内だけに持ち、最近使った [MAX_TRACKED_FILES] 件まで保持する。
 */
internal class VoiceProfileLanguageTracker {

    /**
     * ファイル内で学習したアンカーから求めた 2 言語ペアの判定。
     *
     * @property targetProbability `targetTag` である確率（校正済み、`0.0..1.0`）。
     * @property baseProbability `baseTag` である確率（校正済み、`0.0..1.0`）。
     * @property logOdds `targetTag` の対数オッズ（校正済み）。Whisper の対数オッズに足して使う。
     * @property targetAnchorCount 判定に使った `targetTag` のアンカー数。
     * @property baseAnchorCount 判定に使った `baseTag` のアンカー数。
     */
    data class SpeakerLanguagePrediction(
        val targetProbability: Float,
        val baseProbability: Float,
        val logOdds: Float,
        val targetAnchorCount: Int,
        val baseAnchorCount: Int,
    ) {
        /** 選ばれた言語の確率（`0.5..1.0`）。 */
        val selectedProbability: Float
            get() = max(targetProbability, baseProbability)
    }

    private class Anchor(
        val observationKey: Long?,
        val embedding: FloatArray,
    )

    private class FileModel(val dimension: Int) {
        val anchorsByLang = HashMap<String, ArrayDeque<Anchor>>()
        var version = 0
        var calibrationPairKey: String? = null
        var calibrationVersion = 0
        var calibrationSlope = 0.0
        var calibrationBias = 0.0
    }

    // 最近使った順（accessOrder = true）。古いファイルから捨てる。
    private val modelsByScope = LinkedHashMap<String, FileModel>(16, 0.75f, true)

    /** 指定した音声ファイル識別子 [audioScopeKey] の学習内容を消す。 */
    fun clearScope(audioScopeKey: String) {
        synchronized(modelsByScope) {
            modelsByScope.remove(audioScopeKey)
        }
    }

    /** すべてのファイルの学習内容を消す。 */
    fun clearAll() {
        synchronized(modelsByScope) {
            modelsByScope.clear()
        }
    }

    /**
     * 特徴ベクトル [voiceProfile]（[LanguagePairAnalysis.encoderEmbedding]）から、[audioScopeKey] のファイル内で学習した
     * アンカーに基づく `targetTag` / `baseTag` の判定を返す。
     *
     * 類似度は、2 言語のアンカーの平均を引いて（ファイル内で共通の成分を除いて）から測る。
     * どちらかの言語のアンカーが [MIN_ANCHORS_PER_LANG] 件未満のときは `null` を返す。
     */
    fun predictLanguageFromVoiceProfile(
        audioScopeKey: String,
        voiceProfile: FloatArray,
        targetTag: String,
        baseTag: String,
    ): SpeakerLanguagePrediction? {
        if (audioScopeKey.isBlank() || !isUsableVector(voiceProfile)) return null
        val targetKey = normalizeLangKey(targetTag)
        val baseKey = normalizeLangKey(baseTag)
        if (targetKey.isEmpty() || baseKey.isEmpty() || targetKey == baseKey) return null
        val model = synchronized(modelsByScope) { modelsByScope[audioScopeKey] } ?: return null
        if (model.dimension != voiceProfile.size) return null

        synchronized(model) {
            val targetAnchors = model.anchorsByLang[targetKey] ?: return null
            val baseAnchors = model.anchorsByLang[baseKey] ?: return null
            if (targetAnchors.size < MIN_ANCHORS_PER_LANG || baseAnchors.size < MIN_ANCHORS_PER_LANG) return null

            val count = targetAnchors.size + baseAnchors.size
            val raw = Array(count) { idx ->
                if (idx < targetAnchors.size) targetAnchors[idx].embedding else baseAnchors[idx - targetAnchors.size].embedding
            }
            val mean = FloatArray(model.dimension)
            for (vector in raw) {
                for (i in mean.indices) {
                    mean[i] += vector[i]
                }
            }
            for (i in mean.indices) {
                mean[i] /= count.toFloat()
            }
            val vectors = Array(count) { centeredUnitVector(raw[it], mean) }
            val isTarget = BooleanArray(count) { it < targetAnchors.size }

            val pairKey = "$targetKey|$baseKey"
            if (model.calibrationPairKey != pairKey || model.version - model.calibrationVersion >= REFIT_INTERVAL_ANCHORS) {
                val (slope, bias) = fitCalibration(vectors, isTarget)
                model.calibrationSlope = slope
                model.calibrationBias = bias
                model.calibrationPairKey = pairKey
                model.calibrationVersion = model.version
            }

            val query = centeredUnitVector(voiceProfile, mean)
            val similarities = FloatArray(count) { dot(query, vectors[it]) }
            val score = classKnnScore(similarities, isTarget, excludeIndex = -1) ?: return null
            val logOdds = (model.calibrationSlope * score + model.calibrationBias)
                .coerceIn(-MAX_SPEAKER_LOG_ODDS, MAX_SPEAKER_LOG_ODDS)
            val targetProb = sigmoid(logOdds).toFloat()
            return SpeakerLanguagePrediction(
                targetProbability = targetProb,
                baseProbability = 1.0f - targetProb,
                logOdds = logOdds.toFloat(),
                targetAnchorCount = targetAnchors.size,
                baseAnchorCount = baseAnchors.size,
            )
        }
    }

    /**
     * 言語が確定したチャンクの特徴ベクトル [voiceProfile] を、[audioScopeKey] のファイルの [languageTag] のアンカーとして登録する。
     *
     * 自動判定の結果を登録するときは、Whisper 単体の判定が [isAnchorEligible] を満たす場合に限ること。
     *
     * @param observationKey チャンクを識別するキー（チャンクの開始時刻など）。同じキーで登録し直すと、
     *   言語が変わっていても前の登録を置き換える（二重登録しない）。`null` なら置き換えない。
     */
    fun recordSpeakerObservation(
        audioScopeKey: String,
        observationKey: Long?,
        languageTag: String,
        voiceProfile: FloatArray,
    ) {
        if (audioScopeKey.isBlank() || !isUsableVector(voiceProfile)) return
        val langKey = normalizeLangKey(languageTag)
        if (langKey.isEmpty() || langKey == "und") return
        val model = obtainModel(audioScopeKey, voiceProfile.size) ?: return

        synchronized(model) {
            if (observationKey != null) {
                for (anchors in model.anchorsByLang.values) {
                    anchors.removeAll { it.observationKey == observationKey }
                }
            }
            val anchors = model.anchorsByLang.getOrPut(langKey) { ArrayDeque() }
            anchors.addLast(Anchor(observationKey = observationKey, embedding = voiceProfile.copyOf()))
            while (anchors.size > MAX_ANCHORS_PER_LANG) {
                anchors.removeFirst()
            }
            model.version += 1
        }
    }

    /**
     * Whisper の判定 [whisperPrediction] と、ファイル内で学習した判定 [speakerPrediction] を対数オッズで統合する。
     *
     * Whisper の対数オッズは [whisperReliability] で割り引いてから足す。[speakerPrediction] が `null` でも割り引きは行う
     * （確率が過信にならないようにし、後段の文字起こしで両言語を比べるかどうかの判断に使えるようにする）。
     *
     * @param analyzedDurationMs 言語判定に渡した音声の長さ（ミリ秒）。不明なら `null`（長さによる割り引きをしない）。
     */
    fun combineWithWhisperPrediction(
        whisperPrediction: LanguagePairPrediction,
        speakerPrediction: SpeakerLanguagePrediction?,
        targetTag: String,
        baseTag: String,
        analyzedDurationMs: Long? = null,
    ): LanguagePairPrediction {
        val eps = 1e-4f
        val wTarget = whisperPrediction.targetProbability.coerceIn(eps, 1.0f - eps)
        val wBase = whisperPrediction.baseProbability.coerceIn(eps, 1.0f - eps)
        val whisperLogOdds = ln((wTarget / wBase).toDouble()).coerceIn(-MAX_WHISPER_LOG_ODDS, MAX_WHISPER_LOG_ODDS)
        val reliability = whisperReliability(whisperPrediction.pairMass, analyzedDurationMs).toDouble()
        val speakerLogOdds = speakerPrediction?.logOdds?.toDouble() ?: 0.0
        val combinedLogOdds = (whisperLogOdds * reliability + speakerLogOdds)
            .coerceIn(-MAX_COMBINED_LOG_ODDS, MAX_COMBINED_LOG_ODDS)
        val targetProb = sigmoid(combinedLogOdds).toFloat().coerceIn(0.0f, 1.0f)
        val baseProb = (1.0f - targetProb).coerceIn(0.0f, 1.0f)

        return if (targetProb >= baseProb) {
            LanguagePairPrediction(
                selectedTag = targetTag,
                fallbackTag = baseTag,
                selectedProbability = targetProb,
                targetProbability = targetProb,
                baseProbability = baseProb,
                inferenceTimeMs = whisperPrediction.inferenceTimeMs,
                pairMass = whisperPrediction.pairMass,
            )
        } else {
            LanguagePairPrediction(
                selectedTag = baseTag,
                fallbackTag = targetTag,
                selectedProbability = baseProb,
                targetProbability = targetProb,
                baseProbability = baseProb,
                inferenceTimeMs = whisperPrediction.inferenceTimeMs,
                pairMass = whisperPrediction.pairMass,
            )
        }
    }

    private fun obtainModel(audioScopeKey: String, dimension: Int): FileModel? {
        synchronized(modelsByScope) {
            val existing = modelsByScope[audioScopeKey]
            if (existing != null) {
                return if (existing.dimension == dimension) existing else null
            }
            while (modelsByScope.size >= MAX_TRACKED_FILES) {
                val eldestKey = modelsByScope.keys.firstOrNull() ?: break
                modelsByScope.remove(eldestKey)
            }
            return FileModel(dimension).also { modelsByScope[audioScopeKey] = it }
        }
    }

    companion object {
        /** 近傍の数（言語ごとに、類似度が上位何件のアンカーの平均を使うか）。 */
        internal const val KNN_K: Int = 7

        /** 判定に必要な言語ごとの最小アンカー数。 */
        internal const val MIN_ANCHORS_PER_LANG: Int = 3

        /** 言語ごとに保持するアンカーの上限（古いものから捨てる）。 */
        internal const val MAX_ANCHORS_PER_LANG: Int = 160

        /** 保持するファイル数の上限（最近使ったものを残す）。 */
        internal const val MAX_TRACKED_FILES: Int = 8

        /** 校正をやり直すまでに追加・変更されるアンカー数。 */
        private const val REFIT_INTERVAL_ANCHORS: Int = 4

        /** アンカーにする Whisper 単体の 2 言語確率の下限。 */
        internal const val ANCHOR_MIN_WHISPER_PROBABILITY: Float = 0.95f

        /** アンカーにする Whisper の [LanguagePairPrediction.pairMass] の下限。 */
        internal const val ANCHOR_MIN_PAIR_MASS: Float = 0.70f

        private const val CALIBRATION_L2_SLOPE: Double = 0.5
        private const val CALIBRATION_L2_BIAS: Double = 0.05
        private const val CALIBRATION_MAX_ITERATIONS: Int = 30
        private const val MAX_SPEAKER_LOG_ODDS: Double = 8.0
        private const val MAX_WHISPER_LOG_ODDS: Double = 10.0
        private const val MAX_COMBINED_LOG_ODDS: Double = 10.0

        // Whisper の割り引き: pairMass が小さいほど、音声が短いほど、2 言語の対数オッズを小さくする
        private const val RELIABILITY_MASS_LOW: Float = 0.05f
        private const val RELIABILITY_MASS_HIGH: Float = 0.70f
        private const val RELIABILITY_MASS_FLOOR: Float = 0.15f
        private const val RELIABILITY_DURATION_LOW_MS: Float = 800f
        private const val RELIABILITY_DURATION_HIGH_MS: Float = 1_600f
        private const val RELIABILITY_DURATION_FLOOR: Float = 0.5f

        /**
         * Whisper 単体の判定 [whisperPrediction] が、アンカー（ファイル内で学習する教師）にできるほど確かかどうか。
         *
         * 2 言語確率が [ANCHOR_MIN_WHISPER_PROBABILITY] 以上で、かつ 99 言語の確率の [ANCHOR_MIN_PAIR_MASS] 以上が 2 言語に集まっていること。
         */
        internal fun isAnchorEligible(whisperPrediction: LanguagePairPrediction): Boolean = whisperPrediction.selectedProbability >= ANCHOR_MIN_WHISPER_PROBABILITY &&
            whisperPrediction.pairMass >= ANCHOR_MIN_PAIR_MASS

        /**
         * Whisper の 2 言語の対数オッズに掛ける信頼度（`0.075..1.0`）。
         *
         * 実教材での検証では、[LanguagePairPrediction.pairMass] が 0.5 未満のときや 1.5 秒未満の短いチャンクで、
         * 2 言語確率が 0.9 を超えていても誤りが多かった。
         */
        internal fun whisperReliability(pairMass: Float, analyzedDurationMs: Long?): Float {
            val massWeight = ((pairMass - RELIABILITY_MASS_LOW) / (RELIABILITY_MASS_HIGH - RELIABILITY_MASS_LOW))
                .coerceIn(RELIABILITY_MASS_FLOOR, 1.0f)
            val durationWeight = if (analyzedDurationMs == null) {
                1.0f
            } else {
                val ratio = (analyzedDurationMs - RELIABILITY_DURATION_LOW_MS) /
                    (RELIABILITY_DURATION_HIGH_MS - RELIABILITY_DURATION_LOW_MS)
                (RELIABILITY_DURATION_FLOOR + (1.0f - RELIABILITY_DURATION_FLOOR) * ratio)
                    .coerceIn(RELIABILITY_DURATION_FLOOR, 1.0f)
            }
            return massWeight * durationWeight
        }

        internal fun normalizeLangKey(rawTag: String): String = rawTag.trim().replace('_', '-').lowercase(Locale.ROOT).substringBefore('-')

        private fun isUsableVector(vector: FloatArray): Boolean = vector.isNotEmpty() && vector.all { it.isFinite() }

        private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var sum = 0f
            for (i in a.indices) {
                sum += a[i] * b[i]
            }
            return sum
        }

        /** [vector] からファイル内の平均 [mean] を引き、長さ 1 に正規化したベクトル（長さ 0 なら零ベクトル）。 */
        private fun centeredUnitVector(vector: FloatArray, mean: FloatArray): FloatArray {
            val out = FloatArray(vector.size) { vector[it] - mean[it] }
            var normSq = 0f
            for (v in out) {
                normSq += v * v
            }
            if (normSq < 1e-12f) return FloatArray(vector.size)
            val inv = 1.0f / sqrt(normSq)
            for (i in out.indices) {
                out[i] *= inv
            }
            return out
        }

        /**
         * 言語ごとに類似度 [similarities] の上位 [KNN_K] 件を平均し、`target - base` の差を返す（正なら `target` 寄り）。
         *
         * [excludeIndex] のアンカーは除く（Leave-One-Out 用）。どちらかの言語に候補がなければ `null`。
         */
        private fun classKnnScore(similarities: FloatArray, isTarget: BooleanArray, excludeIndex: Int): Double? {
            val topTarget = FloatArray(KNN_K) { Float.NEGATIVE_INFINITY }
            val topBase = FloatArray(KNN_K) { Float.NEGATIVE_INFINITY }
            var targetCount = 0
            var baseCount = 0
            for (i in similarities.indices) {
                if (i == excludeIndex) continue
                if (isTarget[i]) {
                    insertTopK(topTarget, similarities[i])
                    targetCount += 1
                } else {
                    insertTopK(topBase, similarities[i])
                    baseCount += 1
                }
            }
            if (targetCount == 0 || baseCount == 0) return null
            return meanOfTop(topTarget, targetCount) - meanOfTop(topBase, baseCount)
        }

        /** 降順に並んだ [top] に [value] を挿入する（上位 k 件だけを残す）。 */
        private fun insertTopK(top: FloatArray, value: Float) {
            if (value <= top[top.size - 1]) return
            var pos = top.size - 1
            while (pos > 0 && top[pos - 1] < value) {
                top[pos] = top[pos - 1]
                pos -= 1
            }
            top[pos] = value
        }

        private fun meanOfTop(top: FloatArray, available: Int): Double {
            val n = minOf(top.size, available)
            var sum = 0.0
            for (i in 0 until n) {
                sum += top[i]
            }
            return sum / n
        }

        /**
         * アンカー自身の Leave-One-Out スコアから、スコアを対数オッズに変換する傾きと切片を求める（L2 正則化つきのニュートン法）。
         *
         * 言語で声が分かれていない（Leave-One-Out で当たらない）ときは、傾きが 0 に近くなる。
         */
        private fun fitCalibration(vectors: Array<FloatArray>, isTarget: BooleanArray): Pair<Double, Double> {
            val n = vectors.size
            val looScores = DoubleArray(n)
            val similarities = FloatArray(n)
            for (j in 0 until n) {
                for (m in 0 until n) {
                    similarities[m] = if (m == j) 0f else dot(vectors[j], vectors[m])
                }
                looScores[j] = classKnnScore(similarities, isTarget, excludeIndex = j) ?: 0.0
            }
            var slope = 0.0
            var bias = 0.0
            repeat(CALIBRATION_MAX_ITERATIONS) {
                var gradSlope = CALIBRATION_L2_SLOPE * slope
                var gradBias = CALIBRATION_L2_BIAS * bias
                var hSlope = CALIBRATION_L2_SLOPE
                var hBias = CALIBRATION_L2_BIAS
                var hCross = 0.0
                for (j in 0 until n) {
                    val x = looScores[j]
                    val y = if (isTarget[j]) 1.0 else 0.0
                    val p = sigmoid(slope * x + bias)
                    val w = p * (1.0 - p)
                    gradSlope += (p - y) * x
                    gradBias += p - y
                    hSlope += w * x * x
                    hBias += w
                    hCross += w * x
                }
                val det = hSlope * hBias - hCross * hCross
                if (det <= 1e-12) return slope to bias
                val stepSlope = (hBias * gradSlope - hCross * gradBias) / det
                val stepBias = (hSlope * gradBias - hCross * gradSlope) / det
                slope -= stepSlope
                bias -= stepBias
                if (abs(stepSlope) + abs(stepBias) < 1e-6) return slope to bias
            }
            return slope to bias
        }
    }
}
