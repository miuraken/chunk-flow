package com.miuraken.chunkflow.core.audio

import kotlin.math.max
import kotlin.math.min

/**
 * 16kHz モノラル PCM（`FloatArray`, `-1.0f..1.0f`）をストリーミングで受け取り、
 * フレーム（`binMs`、既定 10ms）ごとの発話確率配列（`0.0f..1.0f`）を生成するインターフェース。
 *
 * `core:audio` は純 Kotlin モジュールであるため、ONNX Runtime や Android API には依存せず、
 * 実装（`SileroVadEngine`）は `core:media` から [SpeechProbabilityStreamFactory] 経由で供給する。
 */
interface SpeechProbabilityStream : SampleSink {

    /**
     * これまでに蓄積された PCM から算出可能な 10ms（[binMs]）ごとの発話確率配列を返す（プログレッシブ分割用）。
     *
     * @param targetFrameCount 期待する 10ms フレーム数（`FeatureAccumulator.size` と揃える）。
     * @param binMs 1 フレームの長さ（ミリ秒、既定 10ms）。
     */
    fun currentProbabilities(
        targetFrameCount: Int,
        binMs: Int = ChunkSegmenter.DEFAULT_BIN_MS,
    ): FloatArray

    /**
     * ストリーム末尾の端数サンプルをフラッシュし、全体の 10ms（[binMs]）ごとの発話確率配列を返す。
     *
     * @param targetFrameCount 最終的な 10ms フレーム数（`FeatureAccumulator.size` と揃える）。
     * @param binMs 1 フレームの長さ（ミリ秒、既定 10ms）。
     */
    fun finish(
        targetFrameCount: Int,
        binMs: Int = ChunkSegmenter.DEFAULT_BIN_MS,
    ): FloatArray
}

/**
 * 音声デコードストリームごとに独立した状態を持つ [SpeechProbabilityStream] を生成するファクトリ。
 */
fun interface SpeechProbabilityStreamFactory {
    /** 新しいストリーム用の [SpeechProbabilityStream] を返す。推論エンジンが利用できない場合は `null` を返してよい。 */
    fun createStream(): SpeechProbabilityStream?
}

/**
 * Silero VAD の 32ms（512 サンプル @ 16kHz）単位の発話確率列を、
 * 10ms（`binMs`）単位のフレーム特徴量配列と同じ長さへ線形補間して変換する純 Kotlin ヘルパー。
 */
object SpeechProbabilityInterpolator {

    /** Silero VAD (16kHz) の 1 ウィンドウあたりのサンプル数（512 サンプル = 32ms）。 */
    const val SILERO_WINDOW_SAMPLES_16K: Int = 512

    /** Silero VAD (16kHz) のコンテキストサンプル数（64 サンプル = 4ms）。 */
    const val SILERO_CONTEXT_SAMPLES_16K: Int = 64

    /** Silero VAD (16kHz) の 1 ウィンドウの時間長（32ms）。 */
    const val SILERO_WINDOW_MS: Float = 32.0f

    /**
     * 32ms 窓ごとの発話確率配列 [probs32ms]（有効長 [probsCount]）を、
     * [binMs]（既定 10ms）間隔の [targetFrameCount] フレームの確率配列へ線形補間する。
     *
     * 各 32ms 窓 `k` の中心時刻は `(k + 0.5f) * 32ms`、
     * 各 10ms フレーム `i` の中心時刻は `(i + 0.5f) * binMs` として補間し、出力は `0.0f..1.0f` にクランプする。
     */
    fun interpolate32msToBins(
        probs32ms: FloatArray,
        probsCount: Int = probs32ms.size,
        targetFrameCount: Int,
        binMs: Int = ChunkSegmenter.DEFAULT_BIN_MS,
        windowMs: Float = SILERO_WINDOW_MS,
    ): FloatArray {
        require(targetFrameCount >= 0) { "targetFrameCount must be >= 0: $targetFrameCount" }
        require(binMs > 0) { "binMs must be > 0: $binMs" }
        require(windowMs > 0f) { "windowMs must be > 0: $windowMs" }
        if (targetFrameCount == 0) return FloatArray(0)
        val count = min(probsCount, probs32ms.size).coerceAtLeast(0)
        if (count == 0) return FloatArray(targetFrameCount)
        if (count == 1) {
            val single = probs32ms[0].takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
            return FloatArray(targetFrameCount) { single }
        }

        val out = FloatArray(targetFrameCount)
        val maxIdx = count - 1
        for (i in 0 until targetFrameCount) {
            val centerMs = (i + 0.5f) * binMs
            val pos = (centerMs / windowMs) - 0.5f
            val idx0 = pos.toInt().coerceIn(0, maxIdx)
            val idx1 = min(maxIdx, max(0, if (pos < 0f) 0 else idx0 + 1))
            val frac = (pos - idx0).coerceIn(0f, 1f)
            val p0 = probs32ms[idx0].takeIf { it.isFinite() } ?: 0f
            val p1 = probs32ms[idx1].takeIf { it.isFinite() } ?: 0f
            out[i] = (p0 + (p1 - p0) * frac).coerceIn(0f, 1f)
        }
        return out
    }
}
