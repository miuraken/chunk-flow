package com.miuraken.chunkflow.core.audio

import kotlin.math.abs
import kotlin.math.max

/**
 * 文字起こしセグメントの発話開始時刻と、端末内の音声解析で得た対数エネルギー配列（`features.bin`）の
 * 音声立ち上がり（オンセット）を照合し、時刻のずれ（オフセット）を自動推定するクラス（F7.6、§7.4.6、ADR 0008）。
 *
 * `core:audio` は純 Kotlin モジュールであり、`android.*` や `androidx.*` には依存しない。
 */
object TranscriptOffsetAligner {

    /** 探索幅の既定値（±500ms、§7.4.6）。 */
    const val DEFAULT_SEARCH_RANGE_MS: Int = 500

    /** 探索刻み幅の既定値（10ms、§7.4.6）。 */
    const val DEFAULT_STEP_MS: Int = 10

    /**
     * [segmentStartMsList] の各発話開始時刻に対して、`[initialOffsetMs - searchRangeMs .. initialOffsetMs + searchRangeMs]`
     * の範囲を [stepMs] 刻みで走査し、音声の立ち上がり（例: `energyDb[frame] - energyDb[frame - 2]` の正の立ち上がり量と
     * 発話エネルギーの和）と最もよく一致するオフセット（ミリ秒）を返す。
     *
     * オフセット `d` の符号は [com.miuraken.chunkflow.core.model.MediaItem.transcriptOffsetMs] と同じく、
     * 「文字起こしの時刻 `t` に `d` を足した `t + d` が実際の音声の時刻になる」向きとする。
     *
     * @param segmentStartMsList 文字起こしセグメントの発話開始時刻リスト（ミリ秒）。
     * @param energyDb `features.bin` から読み込んだ対数エネルギー配列（dBFS）。
     * @param hopMs [energyDb] の 1 フレームあたりのホップ長（ミリ秒、既定 10ms）。
     * @param initialOffsetMs 探索中心となる初期オフセット（ミリ秒、既定 0ms）。
     * @param searchRangeMs [initialOffsetMs] の前後に探索する最大幅（ミリ秒、既定 500ms）。
     * @param stepMs 探索の刻み幅（ミリ秒、既定 10ms）。
     * @return 推定された最適オフセット（ミリ秒）。入力が空で評価できない場合は [initialOffsetMs] を返す。
     */
    fun align(
        segmentStartMsList: List<Long>,
        energyDb: FloatArray,
        hopMs: Int = 10,
        initialOffsetMs: Int = 0,
        searchRangeMs: Int = DEFAULT_SEARCH_RANGE_MS,
        stepMs: Int = DEFAULT_STEP_MS,
    ): Int {
        if (segmentStartMsList.isEmpty() || energyDb.size < 3 || hopMs <= 0 || stepMs <= 0 || searchRangeMs < 0) {
            return initialOffsetMs
        }

        val sortedEnergy = energyDb.copyOf().apply { sort() }
        val noiseFloorDb = EnergyVoiceActivityDetector.percentileSorted(sortedEnergy, 0.20f)
        val minOffset = initialOffsetMs - searchRangeMs
        val maxOffset = initialOffsetMs + searchRangeMs

        var bestOffsetMs = initialOffsetMs
        var bestScore = Double.NEGATIVE_INFINITY

        var candidateOffsetMs = minOffset
        while (candidateOffsetMs <= maxOffset) {
            var totalScore = 0.0
            var evaluatedCount = 0

            for (startMs in segmentStartMsList) {
                val shiftedMs = startMs + candidateOffsetMs.toLong()
                if (shiftedMs < 0L) continue
                val frame = ((shiftedMs + hopMs / 2) / hopMs).toInt()
                if (frame !in 1 until energyDb.size) continue

                totalScore += computeOnsetScoreAtFrame(
                    energyDb = energyDb,
                    frame = frame,
                    noiseFloorDb = noiseFloorDb,
                )
                evaluatedCount++
            }

            if (evaluatedCount > 0) {
                val meanScore = totalScore / evaluatedCount.toDouble()
                val isBetter = if (meanScore > bestScore + SCORE_EPSILON) {
                    true
                } else if (abs(meanScore - bestScore) <= SCORE_EPSILON) {
                    abs(candidateOffsetMs - initialOffsetMs) < abs(bestOffsetMs - initialOffsetMs)
                } else {
                    false
                }
                if (isBetter) {
                    bestScore = meanScore
                    bestOffsetMs = candidateOffsetMs
                }
            }

            candidateOffsetMs += stepMs
        }

        return bestOffsetMs
    }

    /**
     * フレーム [frame] における音声の立ち上がりスコアを算出する。
     *
     * - 直前フレームからの 1 ステップ立ち上がり量 `max(0, energyDb[frame] - energyDb[frame - 1])`
     * - 2 フレーム前からの立ち上がり量 `max(0, energyDb[frame] - energyDb[frame - 2])`
     * - 前後 2 フレーム窓の平均エネルギー差 `max(0, postMean - preMean)`
     * - ノイズフロアからの発話エネルギー量 `max(0, postMean - noiseFloorDb)`
     */
    internal fun computeOnsetScoreAtFrame(
        energyDb: FloatArray,
        frame: Int,
        noiseFloorDb: Float,
    ): Double {
        val lastIdx = energyDb.lastIndex
        val pre1 = energyDb[(frame - 1).coerceIn(0, lastIdx)]
        val pre2 = energyDb[(frame - 2).coerceIn(0, lastIdx)]
        val cur0 = energyDb[frame.coerceIn(0, lastIdx)]
        val post1 = energyDb[(frame + 1).coerceIn(0, lastIdx)]

        val preMean = 0.5f * (pre1 + pre2)
        val postMean = 0.5f * (cur0 + post1)

        val immediateRise = max(0f, cur0 - pre1)
        val twoFrameRise = max(0f, cur0 - pre2)
        val windowRise = max(0f, postMean - preMean)
        val speechEnergy = max(0f, postMean - noiseFloorDb)

        return (immediateRise + windowRise + 0.5f * twoFrameRise + 0.1f * speechEnergy).toDouble()
    }

    private const val SCORE_EPSILON: Double = 1e-6
}
