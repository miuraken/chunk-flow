package com.miuraken.chunkflow.feature.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WaveformSectionTest {
    @Test
    fun `computeWaveformStripDrawBounds_表示窓の右端直前0点5px未満のセグメントでも例外を投げず有効範囲に収まる`() {
        // 再現ケース: canvasWidthPx = 952, window = [0, 30_000], visible = [29_999, 30_000]
        // (29_999 / 30_000) * 952 = 951.968... -> roundToInt() = 952 となり、
        // 旧実装では dstEndX の coerceIn(953, 952) で IllegalArgumentException が発生していた。
        val bounds = computeWaveformStripDrawBounds(
            stripStartMs = 0L,
            stripEndMs = 60_000L,
            stripTotalWidthPx = 1_904,
            windowStartMs = 0L,
            windowEndMs = 30_000L,
            clipStartMs = 29_999L,
            clipEndMs = 30_000L,
            canvasWidthPx = 952,
        )

        assertNotNull(bounds)
        assertEquals(951, bounds.dstStartX)
        assertEquals(952, bounds.dstEndX)
        assertTrue(bounds.dstStartX in 0 until bounds.dstEndX)
        assertTrue(bounds.dstEndX <= 952)
        assertTrue(bounds.srcStartPx in 0 until bounds.srcEndPx)
        assertTrue(bounds.srcEndPx <= 1_904)
    }

    @Test
    fun `computeWaveformStripDrawBounds_任意の端数境界と1px幅でも常に不変条件を満たす`() {
        val canvasWidths = listOf(1, 2, 3, 411, 952, 1_080, 2_560)
        val stripWidths = listOf(1, 2, 952, 3_808, 16_384)
        val windowStartMs = 10_000L
        val windowEndMs = 40_000L
        val stripStartMs = 0L
        val stripEndMs = 60_000L

        val clipCandidates = listOf(
            // 表示窓の左端直後（0.5px 未満）
            10_000L to 10_001L,
            9_500L to 10_001L,
            // 表示窓の右端直前（0.5px 未満）
            39_999L to 40_000L,
            39_999L to 45_000L,
            // ストリップ右端直前（0.5px 未満）
            59_999L to 60_000L,
            // 極小 1ms セグメント（中央・端）
            25_000L to 25_001L,
            10_015L to 10_016L,
            39_985L to 39_986L,
            // 全幅一致
            10_000L to 40_000L,
        )

        for (canvasW in canvasWidths) {
            for (stripW in stripWidths) {
                for ((clipStart, clipEnd) in clipCandidates) {
                    val bounds = computeWaveformStripDrawBounds(
                        stripStartMs = stripStartMs,
                        stripEndMs = stripEndMs,
                        stripTotalWidthPx = stripW,
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMs,
                        clipStartMs = clipStart,
                        clipEndMs = clipEnd,
                        canvasWidthPx = canvasW,
                    )
                    if (bounds != null) {
                        assertTrue(
                            bounds.dstStartX in 0 until bounds.dstEndX && bounds.dstEndX <= canvasW,
                            "dst bounds invalid: $bounds (canvasW=$canvasW, clip=$clipStart..$clipEnd)",
                        )
                        assertTrue(
                            bounds.srcStartPx in 0 until bounds.srcEndPx && bounds.srcEndPx <= stripW,
                            "src bounds invalid: $bounds (stripW=$stripW, clip=$clipStart..$clipEnd)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `computeWaveformStripDrawBounds_表示範囲外または幅0以下ではnullを返す`() {
        // クリップ範囲が表示窓の外側
        assertNull(
            computeWaveformStripDrawBounds(
                stripStartMs = 0L,
                stripEndMs = 60_000L,
                stripTotalWidthPx = 1_000,
                windowStartMs = 10_000L,
                windowEndMs = 40_000L,
                clipStartMs = 40_000L,
                clipEndMs = 50_000L,
                canvasWidthPx = 952,
            ),
        )
        // 幅 0 以下
        assertNull(
            computeWaveformStripDrawBounds(
                stripStartMs = 0L,
                stripEndMs = 60_000L,
                stripTotalWidthPx = 1_000,
                windowStartMs = 0L,
                windowEndMs = 30_000L,
                canvasWidthPx = 0,
            ),
        )
        assertNull(
            computeWaveformStripDrawBounds(
                stripStartMs = 0L,
                stripEndMs = 60_000L,
                stripTotalWidthPx = 0,
                windowStartMs = 0L,
                windowEndMs = 30_000L,
                canvasWidthPx = 952,
            ),
        )
    }
}
