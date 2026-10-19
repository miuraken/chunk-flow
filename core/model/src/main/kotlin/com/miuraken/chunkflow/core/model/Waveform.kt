package com.miuraken.chunkflow.core.model

/**
 * 固定時間分解能の波形（§7.3.2）。
 *
 * 各ビンの値は dBFS（-60〜0dB）を 0..255 に量子化したもの（符号なしとして読む: `byte.toInt() and 0xFF`）。
 * 表示時は表示範囲と幅に合わせて max プーリングで縮約する（core:audio の `WaveformReducer`）。
 *
 * 配列は呼び出し側と共有されるため、作成後に書き換えないこと。
 *
 * @property binMs 1 ビンの長さ（ミリ秒）。既定は 10ms。
 * @property peak ビンごとのピーク。
 * @property rms ビンごとの RMS。[peak] と同じ長さ。
 */
class Waveform(
    val binMs: Int,
    val peak: ByteArray,
    val rms: ByteArray,
) {
    init {
        require(peak.size == rms.size)
    }

    /** 波形の長さ（ミリ秒）。ビン数 × [binMs]。 */
    val durationMs: Long get() = peak.size.toLong() * binMs

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Waveform) return false
        return binMs == other.binMs &&
            peak.contentEquals(other.peak) &&
            rms.contentEquals(other.rms)
    }

    override fun hashCode(): Int {
        var result = binMs
        result = 31 * result + peak.contentHashCode()
        result = 31 * result + rms.contentHashCode()
        return result
    }
}
