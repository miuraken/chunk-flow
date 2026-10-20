package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.Contrast
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.Hct
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.MathUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 言語役割カラーの調和の制約（計画書 §3.2）を、代表的な `primary` に対して固定する。
 *
 * - (a) 母語と学習言語の色相差 ≥ 60°
 * - (b) バッジコンテナとバッジ文字のコントラスト比 ≥ 4.5:1（ライト / ダーク）
 * - (c) 学習言語と `primary` の色相差 ≥ 30°
 */
class LanguagePaletteTest {
    /** 角度の計算の丸め誤差（度）。 */
    private val epsilon = 1e-6

    /** 代表的な `primary`（名前 → ARGB）。 */
    private val primaries: Map<String, Int> = linkedMapOf(
        "Teal(代替配色)" to 0xFF006A6A.toInt(),
        "赤" to 0xFFB3261E.toInt(),
        "青" to 0xFF1976D2.toInt(),
        "緑" to 0xFF2E7D32.toInt(),
        "紫(M3 baseline)" to 0xFF6750A4.toInt(),
        "橙" to 0xFFE65100.toInt(),
        "ピンク(学習言語の基準色相付近)" to 0xFFC2185B.toInt(),
        "グレー" to 0xFF777777.toInt(),
    )

    @Test
    fun `a_母語と学習言語の色相差が60度以上`() {
        for ((name, argb) in primaries) {
            val hues = ChunkFlowHues.from(argb)
            val diff = MathUtils.differenceDegrees(hues.base, hues.target)
            assertTrue("$name: base=${hues.base} target=${hues.target} diff=$diff", diff >= ChunkFlowHues.MIN_BASE_TARGET_DISTANCE - epsilon)
        }
    }

    @Test
    fun `c_学習言語とprimaryの色相差が30度以上`() {
        for ((name, argb) in primaries) {
            val hues = ChunkFlowHues.from(argb)
            if (hues.primaryChroma < ChunkFlowHues.MIN_PRIMARY_CHROMA_FOR_HARMONIZATION) continue
            val primaryHue = Hct.fromInt(argb).hue
            val diff = MathUtils.differenceDegrees(hues.target, primaryHue)
            assertTrue("$name: primary=$primaryHue target=${hues.target} diff=$diff", diff >= ChunkFlowHues.MIN_TARGET_PRIMARY_DISTANCE - epsilon)
        }
    }

    @Test
    fun `b_バッジコンテナと文字のコントラスト比が4_5以上_ライトとダーク`() {
        for ((name, argb) in primaries) {
            for (dark in listOf(false, true)) {
                val palette = languagePaletteFor(Color(argb), darkTheme = dark)
                val base = Contrast.ratioOfArgbs(palette.baseBadgeContainer.toArgb(), palette.onBaseBadgeContainer.toArgb())
                val target = Contrast.ratioOfArgbs(palette.targetBadgeContainer.toArgb(), palette.onTargetBadgeContainer.toArgb())
                assertTrue("$name dark=$dark base badge contrast=$base", base >= 4.5)
                assertTrue("$name dark=$dark target badge contrast=$target", target >= 4.5)
            }
        }
    }

    @Test
    fun `調和による色相の移動は最大15度_学習言語の退避を除く`() {
        for ((name, argb) in primaries) {
            val hues = ChunkFlowHues.from(argb)
            assertTrue("$name base", MathUtils.differenceDegrees(hues.base, ChunkFlowHues.BASE_HUE) <= 15.0 + 1e-6)
            assertTrue("$name completed", MathUtils.differenceDegrees(hues.completed, ChunkFlowHues.COMPLETED_HUE) <= 15.0 + 1e-6)
            assertTrue("$name favorite", MathUtils.differenceDegrees(hues.favorite, ChunkFlowHues.FAVORITE_HUE) <= 15.0 + 1e-6)
        }
    }

    @Test
    fun `グレーのprimary_調和せず基準色相のまま`() {
        val hues = ChunkFlowHues.fromPrimaryHue(primaryHue = 123.0, primaryChroma = 2.0)
        assertEquals(ChunkFlowHues.BASE_HUE, hues.base, 0.0)
        assertEquals(ChunkFlowHues.TARGET_HUE, hues.target, 0.0)
        assertEquals(ChunkFlowHues.COMPLETED_HUE, hues.completed, 0.0)
    }

    @Test
    fun `primaryが学習言語の基準色相と一致_学習言語は母語から遠い側へ30度逃げる`() {
        val hues = ChunkFlowHues.fromPrimaryHue(primaryHue = 350.0, primaryChroma = 50.0)
        // 母語は 250 → 265（15° 寄る）。学習言語は 350 ± 30 のうち、265 から遠い 20 側へ。
        assertEquals(265.0, hues.base, 1e-6)
        assertEquals(20.0, hues.target, 1e-6)
        assertTrue(MathUtils.differenceDegrees(hues.base, hues.target) >= 60.0)
    }

    @Test
    fun `生成したパレット_トーンがライトとダークの設計値になる`() {
        val light = languagePaletteFor(Color(0xFF006A6A), darkTheme = false)
        assertEquals(LanguagePaletteTones.LIGHT_BADGE_CONTAINER, Hct.fromInt(light.baseBadgeContainer.toArgb()).tone, 1.0)
        assertEquals(LanguagePaletteTones.LIGHT_ON_BADGE_CONTAINER, Hct.fromInt(light.onTargetBadgeContainer.toArgb()).tone, 1.0)
        val dark = languagePaletteFor(Color(0xFF4CDADA), darkTheme = true)
        assertEquals(LanguagePaletteTones.DARK_BADGE_CONTAINER, Hct.fromInt(dark.targetBadgeContainer.toArgb()).tone, 1.0)
        assertEquals(LanguagePaletteTones.DARK_ROW_BACKGROUND_ALPHA, dark.baseRowBackground.alpha, 0.01f)
    }

    /** 報告用: primary ごとの色相差とコントラスト比を標準出力に表で出す。 */
    @Test
    fun `報告用_primaryごとの制約値を出力する`() {
        println("| primary | base hue | target hue | base-target | target-primary | badge contrast light(base/target) | dark(base/target) |")
        for ((name, argb) in primaries) {
            val hues = ChunkFlowHues.from(argb)
            val primaryHue = Hct.fromInt(argb).hue
            val light = languagePaletteFor(Color(argb), darkTheme = false)
            val dark = languagePaletteFor(Color(argb), darkTheme = true)
            fun ratio(p: ChunkFlowLanguagePalette, base: Boolean) = if (base) {
                Contrast.ratioOfArgbs(p.baseBadgeContainer.toArgb(), p.onBaseBadgeContainer.toArgb())
            } else {
                Contrast.ratioOfArgbs(p.targetBadgeContainer.toArgb(), p.onTargetBadgeContainer.toArgb())
            }
            println(
                "| $name (h=${"%.0f".format(primaryHue)}) | ${"%.0f".format(hues.base)} | ${"%.0f".format(hues.target)} | " +
                    "${"%.0f".format(MathUtils.differenceDegrees(hues.base, hues.target))} | " +
                    "${"%.0f".format(MathUtils.differenceDegrees(hues.target, primaryHue))} | " +
                    "${"%.1f".format(ratio(light, true))} / ${"%.1f".format(ratio(light, false))} | " +
                    "${"%.1f".format(ratio(dark, true))} / ${"%.1f".format(ratio(dark, false))} |",
            )
        }
    }
}
