package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.ColorUtils
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.Contrast
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.Hct
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 代替配色（全ロール生成）の妥当性。 */
class FallbackColorSchemeTest {
    private fun assertContrast(name: String, scheme: ColorScheme, pair: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>, min: Double) {
        val ratio = Contrast.ratioOfArgbs(pair.first.toArgb(), pair.second.toArgb())
        assertTrue("$name ratio=$ratio", ratio >= min)
    }

    private fun checkScheme(label: String, s: ColorScheme) {
        assertContrast("$label primary/onPrimary", s, s.primary to s.onPrimary, 4.5)
        assertContrast("$label primaryContainer/onPrimaryContainer", s, s.primaryContainer to s.onPrimaryContainer, 4.5)
        assertContrast("$label secondaryContainer/onSecondaryContainer", s, s.secondaryContainer to s.onSecondaryContainer, 4.5)
        assertContrast("$label tertiaryContainer/onTertiaryContainer", s, s.tertiaryContainer to s.onTertiaryContainer, 4.5)
        assertContrast("$label surface/onSurface", s, s.surface to s.onSurface, 4.5)
        assertContrast("$label surfaceVariant/onSurfaceVariant", s, s.surfaceVariant to s.onSurfaceVariant, 4.5)
        assertContrast("$label error/onError", s, s.error to s.onError, 4.5)
        assertContrast("$label errorContainer/onErrorContainer", s, s.errorContainer to s.onErrorContainer, 4.5)
        assertContrast("$label surface/outline", s, s.surface to s.outline, 3.0)
    }

    @Test
    fun `ライト_主要なロールの組がコントラスト比を満たす`() = checkScheme("light", FallbackLightColorScheme)

    @Test
    fun `ダーク_主要なロールの組がコントラスト比を満たす`() = checkScheme("dark", FallbackDarkColorScheme)

    @Test
    fun `ライトのprimary_従来の代替配色の値に近い`() {
        // 従来は #006A6A。トーナルパレット経由でも各成分 ±4 階調以内に収まる（見た目を変えない）。
        val argb = FallbackLightColorScheme.primary.toArgb()
        val expected = FallbackColorSeeds.PRIMARY_SEED
        assertTrue(abs(ColorUtils.redFromArgb(argb) - ColorUtils.redFromArgb(expected)) <= 4)
        assertTrue(abs(ColorUtils.greenFromArgb(argb) - ColorUtils.greenFromArgb(expected)) <= 4)
        assertTrue(abs(ColorUtils.blueFromArgb(argb) - ColorUtils.blueFromArgb(expected)) <= 4)
    }

    @Test
    fun `surfaceContainer系のトーンが仕様どおり`() {
        val light = FallbackLightColorScheme
        assertEquals(100.0, Hct.fromInt(light.surfaceContainerLowest.toArgb()).tone, 0.5)
        assertEquals(96.0, Hct.fromInt(light.surfaceContainerLow.toArgb()).tone, 0.5)
        assertEquals(90.0, Hct.fromInt(light.surfaceContainerHighest.toArgb()).tone, 0.5)
        val dark = FallbackDarkColorScheme
        assertEquals(4.0, Hct.fromInt(dark.surfaceContainerLowest.toArgb()).tone, 0.5)
        assertEquals(22.0, Hct.fromInt(dark.surfaceContainerHighest.toArgb()).tone, 0.5)
    }
}
