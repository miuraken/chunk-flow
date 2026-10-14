package com.miuraken.chunkflow.core.testing.fixture

import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.Waveform

/** 決定的に作る合成の波形（実際の音声は使わない）。 */
object SampleWaveforms {
    /** 既定のビン幅（10ms）。 */
    const val BIN_MS: Int = 10

    /**
     * [chunks] の範囲を発話、それ以外を無音とした波形を作る。
     *
     * 発話のビンは 150〜250、無音のビンは 5〜25 程度の値になる（0..255 の量子化値）。
     */
    fun forChunks(durationMs: Long, chunks: List<Chunk>, binMs: Int = BIN_MS): Waveform {
        val bins = (durationMs / binMs).toInt()
        val peak = ByteArray(bins)
        val rms = ByteArray(bins)
        var chunkIndex = 0
        for (i in 0 until bins) {
            val t = i.toLong() * binMs
            while (chunkIndex < chunks.size && chunks[chunkIndex].endMs <= t) chunkIndex++
            val inSpeech = chunkIndex < chunks.size && chunks[chunkIndex].startMs <= t
            val p = if (inSpeech) 150 + (i * 37) % 100 else 5 + (i * 13) % 20
            peak[i] = p.toByte()
            rms[i] = (p * 3 / 4).toByte()
        }
        return Waveform(binMs, peak, rms)
    }

    /** 無音だけの波形。 */
    fun silence(durationMs: Long, binMs: Int = BIN_MS): Waveform {
        val bins = (durationMs / binMs).toInt()
        return Waveform(binMs, ByteArray(bins), ByteArray(bins))
    }
}
