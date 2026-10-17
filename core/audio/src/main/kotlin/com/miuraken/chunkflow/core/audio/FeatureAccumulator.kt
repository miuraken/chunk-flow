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

    /**
     * 先行して取得した低解像度の全体波形 [coarseOverview] がある場合、
     * これまでに確定した高解像度（[binMs]）の先頭区間 `[0, size)` で [coarseOverview] の該当範囲を置き換えた
     * 合成 [Waveform] を返す。
     *
     * これにより、解析開始直後から音声全体の波形を表示しつつ、30 秒幅などの高解像度解析データが
     * 揃った区間から順次置き換えていくことができる。
     */
    fun toProgressiveWaveform(coarseOverview: Waveform? = null): Waveform {
        if (coarseOverview == null || coarseOverview.peak.isEmpty() || coarseOverview.binMs <= 0) {
            return toWaveform()
        }
        val coarseDurationMs = coarseOverview.durationMs
        val decodedDurationMs = size.toLong() * binMs
        if (decodedDurationMs >= coarseDurationMs) {
            return toWaveform()
        }
        val totalBins = ((coarseDurationMs + binMs - 1L) / binMs)
            .toInt()
            .coerceIn(size, AnalysisBinaryFormat.MAX_BIN_COUNT)
        val mergedPeak = ByteArray(totalBins)
        val mergedRms = ByteArray(totalBins)
        if (size > 0) {
            peak.copyInto(mergedPeak, destinationOffset = 0, startIndex = 0, endIndex = size)
            rms.copyInto(mergedRms, destinationOffset = 0, startIndex = 0, endIndex = size)
        }
        val coarseBinMs = coarseOverview.binMs.toLong()
        val coarseCount = coarseOverview.peak.size
        for (i in size until totalBins) {
            val timeMs = i.toLong() * binMs
            val coarseIdx = (timeMs / coarseBinMs).toInt().coerceIn(0, coarseCount - 1)
            mergedPeak[i] = coarseOverview.peak[coarseIdx]
            mergedRms[i] = coarseOverview.rms[coarseIdx]
        }
        return Waveform(binMs = binMs, peak = mergedPeak, rms = mergedRms)
    }

    /** 貯めた対数エネルギー（dBFS、コピー）。 */
    fun logEnergyDb(): FloatArray = energy.copyOf(size)

    companion object {
        /** 拡大波形の 1 画面分（30 秒 = 30,000ms）に相当する既定ビン数（10ms × 3,000 = 30,000ms）。 */
        const val BINS_PER_30_SECONDS: Int = 3_000

        /**
         * 音声全体の長さ [totalDurationMs] と全体波形の横幅（カラム数）から算出したサンプリング列
         * （[coarsePeak], [coarseRms]）を、[fineBinMs]（既定 10ms）間隔の [Waveform] に展開する。
         */
        fun expandCoarseToFineWaveform(
            coarsePeak: ByteArray,
            coarseRms: ByteArray,
            totalDurationMs: Long,
            fineBinMs: Int = 10,
        ): Waveform {
            require(coarsePeak.size == coarseRms.size) {
                "coarsePeak.size (${coarsePeak.size}) != coarseRms.size (${coarseRms.size})"
            }
            require(fineBinMs > 0) { "fineBinMs must be > 0: $fineBinMs" }
            if (coarsePeak.isEmpty() || totalDurationMs <= 0L) {
                return Waveform(fineBinMs, ByteArray(0), ByteArray(0))
            }
            val totalBins = ((totalDurationMs + fineBinMs - 1L) / fineBinMs)
                .toInt()
                .coerceIn(1, AnalysisBinaryFormat.MAX_BIN_COUNT)
            val outPeak = ByteArray(totalBins)
            val outRms = ByteArray(totalBins)
            val coarseCols = coarsePeak.size
            for (i in 0 until totalBins) {
                val col = ((i.toLong() * coarseCols) / totalBins).toInt().coerceIn(0, coarseCols - 1)
                outPeak[i] = coarsePeak[col]
                outRms[i] = coarseRms[col]
            }
            return Waveform(binMs = fineBinMs, peak = outPeak, rms = outRms)
        }
    }
}
