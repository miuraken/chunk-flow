package com.miuraken.chunkflow.core.audio

import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PcmSlidingWindowBufferTest {

    @Test
    fun `appendFloatsとsliceLeadingPcm_指定区間の先頭最大2秒分のPCMを切り出せる`() {
        val sampleRate = 1_000
        val buffer = PcmSlidingWindowBuffer(
            sampleRateHz = sampleRate,
            blockSizeSamples = sampleRate,
            maxRetainedSamples = sampleRate * 10,
        )

        // 4秒分（4,000サンプル）を追加
        val samples = FloatArray(4_000) { idx ->
            ((idx % 100) / 100f) * 2f - 1f
        }
        buffer.appendFloats(samples)

        assertEquals(4_000L, buffer.totalSamplesWritten)
        assertEquals(0L, buffer.baseSampleIndex)
        assertEquals(4_000, buffer.retainedSampleCount)

        // 500ms..3500ms を要求すると、既定 maxDurationMs=2000ms により 500ms..2500ms（2,000サンプル）が返る
        val slice = buffer.sliceLeadingPcm(startMs = 500L, endMs = 3_500L)
        assertNotNull(slice)
        val nonNullSlice = requireNotNull(slice)
        assertEquals(2_000, nonNullSlice.size)

        val expected = ShortArray(2_000) { i ->
            val s = samples[500 + i].coerceIn(-1f, 1f)
            (s * Short.MAX_VALUE.toFloat()).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        assertArrayEquals(expected, nonNullSlice)
    }

    @Test
    fun `discardBeforeMs_指定時刻より前の完了済みブロックを破棄してメモリを解放する`() {
        val sampleRate = 1_000
        val buffer = PcmSlidingWindowBuffer(
            sampleRateHz = sampleRate,
            blockSizeSamples = sampleRate,
            maxRetainedSamples = sampleRate * 10,
        )

        val samples = FloatArray(3_500) { 0.25f }
        buffer.appendFloats(samples)

        // 2,500ms より前を破棄すると、0..1000ms と 1000..2000ms の 2 ブロックが破棄される
        buffer.discardBeforeMs(keepFromMs = 2_500L)
        assertEquals(2_000L, buffer.baseSampleIndex)
        assertEquals(1_500, buffer.retainedSampleCount)

        // 破棄済み区間（0..1500ms）は取得不可
        assertNull(buffer.sliceLeadingPcm(startMs = 0L, endMs = 1_500L))

        // 保持区間（2500..3200ms）は正常に取得可能
        val validSlice = buffer.sliceLeadingPcm(startMs = 2_500L, endMs = 3_200L)
        assertNotNull(validSlice)
        assertEquals(700, requireNotNull(validSlice).size)
    }

    @Test
    fun `appendFloats_最大保持サンプル数を超えた古いブロックが自動破棄される`() {
        val sampleRate = 1_000
        val buffer = PcmSlidingWindowBuffer(
            sampleRateHz = sampleRate,
            blockSizeSamples = sampleRate,
            maxRetainedSamples = sampleRate * 3, // 最大 3 秒分
        )

        // 5 秒分追加
        buffer.appendFloats(FloatArray(5_000) { 0.5f })

        assertEquals(5_000L, buffer.totalSamplesWritten)
        assertEquals(2_000L, buffer.baseSampleIndex)
        assertEquals(3_000, buffer.retainedSampleCount)

        // 先頭 0..2000ms は破棄済み
        assertNull(buffer.sliceLeadingPcm(startMs = 0L, endMs = 1_000L))

        // 2000..3500ms は取得可能
        val slice = buffer.sliceLeadingPcm(startMs = 2_000L, endMs = 3_500L)
        assertNotNull(slice)
        assertEquals(1_500, requireNotNull(slice).size)

        buffer.clear()
        assertEquals(0L, buffer.totalSamplesWritten)
        assertEquals(0L, buffer.baseSampleIndex)
        assertEquals(0, buffer.retainedSampleCount)
    }
}
