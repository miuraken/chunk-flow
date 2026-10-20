package com.miuraken.chunkflow.core.designsystem.theme.colorutil

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlendTest {
    @Test
    fun `harmonize_赤を青に寄せる_上流の既知の値になる`() {
        // 上流 BlendTest: harmonize(red, blue) = 0xFFFB0057。
        // 探索ベースの解法（Hct の KDoc 参照）なので、各成分 ±3 階調以内を許容する。
        val result = Blend.harmonize(0xFFFF0000.toInt(), 0xFF0000FF.toInt())
        assertEquals(0xFB.toDouble(), ColorUtils.redFromArgb(result).toDouble(), 3.0)
        assertEquals(0x00.toDouble(), ColorUtils.greenFromArgb(result).toDouble(), 3.0)
        assertEquals(0x57.toDouble(), ColorUtils.blueFromArgb(result).toDouble(), 3.0)
    }

    @Test
    fun `harmonize_色相の移動は最大15度`() {
        val sources = listOf(0xFF006A6A, 0xFFB3261E, 0xFF0000FF, 0xFF2E7D32, 0xFF6750A4, 0xFFFF6D00, 0xFF777777)
        val designs = listOf(0xFF1976D2, 0xFFE91E63, 0xFFFFB300, 0xFF2E7D32)
        for (source in sources) {
            for (design in designs) {
                val from = Hct.fromInt(design.toInt())
                val result = Hct.fromInt(Blend.harmonize(design.toInt(), source.toInt()))
                val moved = MathUtils.differenceDegrees(from.hue, result.hue)
                assertTrue("design=0x${design.toString(16)} source=0x${source.toString(16)} moved=$moved", moved <= 15.0 + 1.0)
            }
        }
    }

    @Test
    fun `harmonize_トーンと彩度を保つ`() {
        val design = 0xFF1976D2.toInt()
        val from = Hct.fromInt(design)
        val result = Hct.fromInt(Blend.harmonize(design, 0xFFB3261E.toInt()))
        assertEquals(from.tone, result.tone, 1.0)
        assertEquals(from.chroma, result.chroma, 3.0)
    }

    @Test
    fun `harmonizeHue_差が30度未満なら半分だけ寄せ_30度以上なら15度寄せる`() {
        assertEquals(260.0, Blend.harmonizeHue(250.0, 270.0), 1e-9)
        assertEquals(265.0, Blend.harmonizeHue(250.0, 350.0), 1e-9)
        assertEquals(235.0, Blend.harmonizeHue(250.0, 150.0), 1e-9)
        // 0° をまたぐ
        assertEquals(5.0, Blend.harmonizeHue(350.0, 20.0), 1e-9)
    }
}
