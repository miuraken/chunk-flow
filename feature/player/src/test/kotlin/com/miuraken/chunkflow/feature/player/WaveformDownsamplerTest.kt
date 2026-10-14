package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.Waveform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WaveformDownsamplerTest {
    @Test
    fun `downsample_指定カラム数に最大値プーリングで縮小しキャッシュを再利用する`() {
        val peak = ByteArray(100) { index -> (index % 120).toByte() }
        val rms = ByteArray(100) { index -> (index % 60).toByte() }
        val waveform = Waveform(
            binMs = 20,
            peak = peak,
            rms = rms,
        )
        val downsampler = WaveformDownsampler(maxCacheEntries = 4)

        val first = downsampler.downsample(
            waveform = waveform,
            startMs = 0L,
            endMs = 2_000L,
            columnCount = 10,
        )
        assertEquals(10, first.size)
        assertTrue(first.peak.all { it in 0f..1f })
        assertTrue(first.rms.all { it in 0f..1f })

        val cached = downsampler.downsample(
            waveform = waveform,
            startMs = 0L,
            endMs = 2_000L,
            columnCount = 10,
        )
        assertSame(first, cached)
    }

    @Test
    fun `downsample_無効範囲では空配列を返す`() {
        val waveform = Waveform(
            binMs = 20,
            peak = ByteArray(10),
            rms = ByteArray(10),
        )
        val downsampler = WaveformDownsampler()
        val result = downsampler.downsample(
            waveform = waveform,
            startMs = 1_000L,
            endMs = 500L,
            columnCount = 8,
        )
        assertEquals(0, result.size)
    }
}
