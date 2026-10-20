package com.miuraken.chunkflow.core.designsystem.theme.colorutil

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `material-color-utilities` の既知の値（上流のテスト）に対する移植の数値テスト。
 */
class HctTest {
    @Test
    fun `ColorUtils_白と黒_トーンが100と0になる`() {
        assertEquals(100.0, ColorUtils.lstarFromArgb(0xFFFFFFFF.toInt()), 0.01)
        assertEquals(0.0, ColorUtils.lstarFromArgb(0xFF000000.toInt()), 0.01)
        assertEquals(0xFFFFFFFF.toInt(), ColorUtils.argbFromLstar(100.0))
        assertEquals(0xFF000000.toInt(), ColorUtils.argbFromLstar(0.0))
    }

    @Test
    fun `ColorUtils_既知のグレー_L*とYが往復する`() {
        // 上流テスト: L* 50 のグレーは 0xFF777777。
        assertEquals(0xFF777777.toInt(), ColorUtils.argbFromLstar(50.0))
        assertEquals(50.0, ColorUtils.lstarFromY(ColorUtils.yFromLstar(50.0)), 1e-9)
        assertEquals(100.0, ColorUtils.yFromLstar(100.0), 1e-9)
    }

    @Test
    fun `Hct_赤_既知の色相彩度トーンになる`() {
        val hct = Hct.fromInt(0xFFFF0000.toInt())
        assertEquals(27.408, hct.hue, 0.01)
        assertEquals(113.357, hct.chroma, 0.01)
        assertEquals(53.237, hct.tone, 0.01)
    }

    @Test
    fun `Hct_緑_既知の色相彩度トーンになる`() {
        val hct = Hct.fromInt(0xFF00FF00.toInt())
        assertEquals(142.139, hct.hue, 0.01)
        assertEquals(108.410, hct.chroma, 0.01)
        assertEquals(87.737, hct.tone, 0.01)
    }

    @Test
    fun `Hct_青_既知の色相彩度トーンになる`() {
        val hct = Hct.fromInt(0xFF0000FF.toInt())
        assertEquals(282.788, hct.hue, 0.01)
        assertEquals(87.230, hct.chroma, 0.01)
        assertEquals(32.302, hct.tone, 0.01)
    }

    @Test
    fun `Hct_白と黒_トーンが100と0で彩度が0に近い`() {
        val white = Hct.fromInt(0xFFFFFFFF.toInt())
        assertEquals(100.0, white.tone, 0.01)
        // 純白でも CAM16 の彩度は 0 にならない（上流の値 ≈ 2.87）。
        assertTrue(white.chroma < 5.0)
        val black = Hct.fromInt(0xFF000000.toInt())
        assertEquals(0.0, black.tone, 0.01)
        assertTrue(black.chroma < 1.0)
    }

    @Test
    fun `Hct_from_色域内の指定_色相と彩度とトーンを保って往復する`() {
        // 上流テスト（HctTest.gamutMap）に倣い、色域に収まる組み合わせでは指定値が保たれる。
        val hues = listOf(15.0, 45.0, 105.0, 165.0, 225.0, 285.0, 345.0)
        val tones = listOf(20.0, 40.0, 60.0, 80.0)
        for (hue in hues) {
            for (tone in tones) {
                val hct = Hct.from(hue, 20.0, tone)
                assertEquals("hue=$hue tone=$tone", hue, hct.hue, 2.0)
                assertEquals("hue=$hue tone=$tone", 20.0, hct.chroma, 2.5)
                assertEquals("hue=$hue tone=$tone", tone, hct.tone, 0.5)
            }
        }
    }

    @Test
    fun `Hct_from_色域外の高彩度_トーンと色相を保ち彩度だけ下がる`() {
        val hues = listOf(15.0, 105.0, 225.0, 285.0, 345.0)
        for (hue in hues) {
            val hct = Hct.from(hue, 200.0, 50.0)
            assertEquals("hue=$hue", 50.0, hct.tone, 0.5)
            assertEquals("hue=$hue", hue, hct.hue, 2.0)
            assertTrue("hue=$hue chroma=${hct.chroma}", hct.chroma in 20.0..200.0)
        }
    }

    @Test
    fun `Hct_from_既知のARGB_同じARGBにほぼ戻る`() {
        // ARGB → HCT → ARGB の往復。各成分の差が 2 階調以内。
        val colors = listOf(0xFF0000FF, 0xFF006A6A, 0xFF1976D2, 0xFF19532B, 0xFFFFB300, 0xFFD32F2F, 0xFF144272)
        for (argb in colors) {
            val src = argb.toInt()
            val hct = Hct.fromInt(src)
            val back = Hct.from(hct.hue, hct.chroma, hct.tone).toInt()
            val dr = abs(ColorUtils.redFromArgb(src) - ColorUtils.redFromArgb(back))
            val dg = abs(ColorUtils.greenFromArgb(src) - ColorUtils.greenFromArgb(back))
            val db = abs(ColorUtils.blueFromArgb(src) - ColorUtils.blueFromArgb(back))
            assertTrue("0x${argb.toString(16)} -> 0x${back.toUInt().toString(16)}", dr <= 2 && dg <= 2 && db <= 2)
        }
    }

    @Test
    fun `Cam16_fromInt_toInt_不透明な色が往復する`() {
        val colors = listOf(0xFF0000FF, 0xFF00FF00, 0xFFFF0000, 0xFF777777, 0xFF006A6A)
        for (argb in colors) {
            val src = argb.toInt()
            assertEquals("0x${argb.toString(16)}", src, Cam16.fromInt(src).toInt())
        }
    }

    @Test
    fun `MathUtils_角度の差と回転方向`() {
        assertEquals(100.0, MathUtils.differenceDegrees(250.0, 350.0), 1e-9)
        assertEquals(20.0, MathUtils.differenceDegrees(350.0, 10.0), 1e-9)
        assertEquals(1.0, MathUtils.rotationDirection(250.0, 350.0), 0.0)
        assertEquals(-1.0, MathUtils.rotationDirection(350.0, 250.0), 0.0)
        assertEquals(1.0, MathUtils.rotationDirection(350.0, 10.0), 0.0)
        assertEquals(10.0, MathUtils.sanitizeDegreesDouble(370.0), 1e-9)
        assertEquals(350.0, MathUtils.sanitizeDegreesDouble(-10.0), 1e-9)
    }

    @Test
    fun `Contrast_白黒は21で同色は1`() {
        assertEquals(21.0, Contrast.ratioOfTones(0.0, 100.0), 0.01)
        assertEquals(1.0, Contrast.ratioOfTones(50.0, 50.0), 0.01)
        assertEquals(21.0, Contrast.ratioOfArgbs(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }
}
