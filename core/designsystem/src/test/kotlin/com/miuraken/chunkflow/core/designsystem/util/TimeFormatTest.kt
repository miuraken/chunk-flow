package com.miuraken.chunkflow.core.designsystem.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    @Test
    fun `formatDurationMs_1時間未満_分と秒を2桁で返す`() {
        assertEquals("00:00", formatDurationMs(0L))
        assertEquals("00:00", formatDurationMs(-500L))
        assertEquals("00:05", formatDurationMs(5_400L))
        assertEquals("03:45", formatDurationMs(225_000L))
        assertEquals("59:59", formatDurationMs(3_599_000L))
    }

    @Test
    fun `formatDurationMs_1時間以上_時分秒を返す`() {
        assertEquals("1:00:00", formatDurationMs(3_600_000L))
        assertEquals("2:05:09", formatDurationMs(7_509_000L))
    }

    @Test
    fun `formatSpeed_小数2桁で整形する`() {
        assertEquals("1.00x", formatSpeed(1.0f))
        assertEquals("0.75x", formatSpeed(0.75f))
        assertEquals("1.25x", formatSpeed(1.25f))
    }

    @Test
    fun `formatCompactSpeed_末尾の不要な0を省略して整形する`() {
        assertEquals("1x", formatCompactSpeed(1.0f))
        assertEquals("1.5x", formatCompactSpeed(1.5f))
        assertEquals("2x", formatCompactSpeed(2.0f))
        assertEquals("1.25x", formatCompactSpeed(1.25f))
        assertEquals("0.75x", formatCompactSpeed(0.75f))
        assertEquals("0.8x", formatCompactSpeed(0.80f))
    }
}
