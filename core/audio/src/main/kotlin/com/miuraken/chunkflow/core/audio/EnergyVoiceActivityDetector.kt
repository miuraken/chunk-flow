package com.miuraken.chunkflow.core.audio

import com.miuraken.chunkflow.core.model.SegmentationParams

/**
 * エネルギーに基づく既定の [VoiceActivityDetector] 実装（§7.3.3 手順 1〜3）。
 *
 * 1. フレームエネルギー `E[n]`（dBFS、10ms ホップ）を 5 フレームのメディアンフィルタで平滑化する。
 * 2. ノイズフロア `N = P10`、発話レベル `S = P90` から適応閾値 `T = (N + thresholdRatio * (S - N)).coerceIn(-60f, -25f)` を求める。
 * 3. ヒステリシス（`E > T + 3dB` で発話開始、`E < T` で発話終了）で発話フレームを判定する。
 */
class EnergyVoiceActivityDetector : VoiceActivityDetector {

    override fun detect(
        logEnergyDb: FloatArray,
        params: SegmentationParams,
    ): VadResult {
        val n = logEnergyDb.size
        if (n == 0) {
            return VadResult(
                smoothedEnergyDb = FloatArray(0),
                thresholdDb = MIN_THRESHOLD_DB,
                speechFrames = BooleanArray(0),
            )
        }

        val smoothed = medianFilter5(logEnergyDb)
        val sorted = smoothed.copyOf().apply { sort() }
        val noiseFloorDb = percentileSorted(sorted, NOISE_PERCENTILE)
        val speechLevelDb = percentileSorted(sorted, SPEECH_PERCENTILE)
        val ratio = params.thresholdRatio.coerceIn(0f, 1f)
        val thresholdDb = (noiseFloorDb + ratio * (speechLevelDb - noiseFloorDb))
            .coerceIn(MIN_THRESHOLD_DB, MAX_THRESHOLD_DB)

        val onsetThresholdDb = thresholdDb + HYSTERESIS_ONSET_OFFSET_DB
        val speechFrames = BooleanArray(n)
        var inSpeech = false
        for (i in 0 until n) {
            val e = smoothed[i]
            if (!inSpeech && e > onsetThresholdDb) {
                inSpeech = true
            } else if (inSpeech && e < thresholdDb) {
                inSpeech = false
            }
            speechFrames[i] = inSpeech
        }

        return VadResult(
            smoothedEnergyDb = smoothed,
            thresholdDb = thresholdDb,
            speechFrames = speechFrames,
        )
    }

    companion object {
        /** 適応閾値の下限（dBFS、§7.3.3 手順 2）。 */
        const val MIN_THRESHOLD_DB: Float = -60f

        /** 適応閾値の上限（dBFS、§7.3.3 手順 2）。 */
        const val MAX_THRESHOLD_DB: Float = -25f

        /** ヒステリシスの発話開始オフセット（+3dB、§7.3.3 手順 3）。 */
        const val HYSTERESIS_ONSET_OFFSET_DB: Float = 3f

        private const val NOISE_PERCENTILE: Float = 0.10f
        private const val SPEECH_PERCENTILE: Float = 0.90f

        /**
         * 5 フレームのメディアンフィルタを適用する（§7.3.3 手順 1）。
         * 端のフレームは境界値を反復して 5 点窓を構成する。
         */
        internal fun medianFilter5(input: FloatArray): FloatArray {
            val n = input.size
            if (n == 0) return FloatArray(0)
            val out = FloatArray(n)
            val win = FloatArray(5)
            for (i in 0 until n) {
                win[0] = input[(i - 2).coerceIn(0, n - 1)]
                win[1] = input[(i - 1).coerceIn(0, n - 1)]
                win[2] = input[i]
                win[3] = input[(i + 1).coerceIn(0, n - 1)]
                win[4] = input[(i + 2).coerceIn(0, n - 1)]
                sort5(win)
                out[i] = win[2]
            }
            return out
        }

        private fun sort5(a: FloatArray) {
            for (i in 1 until 5) {
                val v = a[i]
                var j = i - 1
                while (j >= 0 && a[j] > v) {
                    a[j + 1] = a[j]
                    j--
                }
                a[j + 1] = v
            }
        }

        /** 昇順ソート済み配列 [sorted] から [p]（0..1）パーセンタイルを線形補間で求める。 */
        internal fun percentileSorted(sorted: FloatArray, p: Float): Float {
            if (sorted.isEmpty()) return MIN_THRESHOLD_DB
            if (sorted.size == 1) return sorted[0]
            val clampedP = p.coerceIn(0f, 1f)
            val pos = clampedP * (sorted.size - 1)
            val lower = pos.toInt().coerceIn(0, sorted.size - 1)
            val upper = (lower + 1).coerceAtMost(sorted.size - 1)
            val weight = pos - lower
            return sorted[lower] + weight * (sorted[upper] - sorted[lower])
        }
    }
}
