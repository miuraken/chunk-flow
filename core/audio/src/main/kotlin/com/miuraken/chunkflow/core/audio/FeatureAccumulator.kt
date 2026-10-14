package com.miuraken.chunkflow.core.audio

import com.miuraken.chunkflow.core.model.Waveform

/**
 * [FrameFeatureSink] の出力をメモリに貯める（30 分で約 1MB: ピーク・RMS 各 1 バイト + 対数エネルギー 4 バイト / 10ms）。
 *
 * 本実装では数秒ごとにファイルへ途中保存する（§7.3.1）。この型はスパイクとテストのための簡易版。
 *
 * @param binMs ビンの長さ（ミリ秒）。[toWaveform] に使う
 */
class FeatureAccumulator(private val binMs: Int = 10, initialCapacity: Int = 4096) : FrameFeatureSink {
    private var peak = ByteArray(initialCapacity)
    private var rms = ByteArray(initialCapacity)
    private var energy = FloatArray(initialCapacity)

    /** 貯めたフレーム数 */
    var size: Int = 0
        private set

    override fun onFrame(index: Int, peak: Float, rms: Float, logEnergyDb: Float) {
        check(index == size) { "フレームの順番が飛んだ: index=$index, size=$size" }
        if (size == this.peak.size) grow()
        this.peak[size] = DbfsQuantizer.quantize(peak)
        this.rms[size] = DbfsQuantizer.quantize(rms)
        energy[size] = logEnergyDb
        size++
    }

    private fun grow() {
        val n = peak.size * 2
        peak = peak.copyOf(n)
        rms = rms.copyOf(n)
        energy = energy.copyOf(n)
    }

    /** 貯めた波形（コピー）。 */
    fun toWaveform(): Waveform = Waveform(binMs, peak.copyOf(size), rms.copyOf(size))

    /** 貯めた対数エネルギー（dBFS、コピー）。 */
    fun logEnergyDb(): FloatArray = energy.copyOf(size)
}
