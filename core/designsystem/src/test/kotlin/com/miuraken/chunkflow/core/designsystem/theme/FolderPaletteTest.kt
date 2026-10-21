package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.ui.graphics.toArgb
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.Contrast
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.Hct
import com.miuraken.chunkflow.core.model.LibraryFolderColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * フォルダのパレット（[ChunkFlowFolderPalette]、ADR 0013）の制約を固定する。
 *
 * 文字の色は代替の配色（[FallbackLightColorScheme] / [FallbackDarkColorScheme]）で確かめる。
 * `onSurface`（トーン 10 / 90）と `onSurfaceVariant`（トーン 30 / 80）は、ダイナミックカラーの配色でも同じトーンになる。
 */
class FolderPaletteTest {
    private val schemes = listOf(false to FallbackLightColorScheme, true to FallbackDarkColorScheme)

    @Test
    fun `色の数がパレットの番号の数と同じ`() {
        assertEquals(LibraryFolderColors.PALETTE_SIZE, FolderPaletteTones.HUES.size)
        for (dark in listOf(false, true)) {
            val palette = folderPaletteFor(dark)
            assertEquals(LibraryFolderColors.PALETTE_SIZE, palette.containers.size)
            assertEquals(LibraryFolderColors.PALETTE_SIZE, palette.selectedContainers.size)
        }
    }

    @Test
    fun `行の背景と文字のコントラスト比が4_5以上_ライトとダーク`() {
        for ((dark, scheme) in schemes) {
            val palette = folderPaletteFor(dark)
            val backgrounds = palette.containers + palette.selectedContainers
            backgrounds.forEachIndexed { index, background ->
                val texts = listOf("onSurface" to scheme.onSurface, "onSurfaceVariant" to scheme.onSurfaceVariant)
                for ((name, text) in texts) {
                    val ratio = Contrast.ratioOfArgbs(background.toArgb(), text.toArgb())
                    assertTrue("dark=$dark #$index $name ratio=$ratio", ratio >= 4.5)
                }
            }
        }
    }

    @Test
    fun `フォルダのアイコンの色と行の背景のコントラスト比が3以上_ライトとダーク`() {
        for ((dark, scheme) in schemes) {
            val palette = folderPaletteFor(dark)
            val backgrounds = palette.containers + palette.selectedContainers
            backgrounds.forEachIndexed { index, background ->
                for ((name, icon) in listOf("primary" to scheme.primary, "tertiary" to scheme.tertiary)) {
                    val ratio = Contrast.ratioOfArgbs(background.toArgb(), icon.toArgb())
                    assertTrue("dark=$dark #$index $name ratio=$ratio", ratio >= 3.0)
                }
            }
        }
    }

    @Test
    fun `同じ色はない_ライトとダーク`() {
        for (dark in listOf(false, true)) {
            val palette = folderPaletteFor(dark)
            assertEquals(palette.containers.size, palette.containers.map { it.toArgb() }.toSet().size)
            assertEquals(palette.selectedContainers.size, palette.selectedContainers.map { it.toArgb() }.toSet().size)
        }
    }

    @Test
    fun `選択中の背景は通常の背景とトーンが6以上違う`() {
        for (dark in listOf(false, true)) {
            val palette = folderPaletteFor(dark)
            palette.containers.zip(palette.selectedContainers).forEachIndexed { index, (normal, selected) ->
                val diff = kotlin.math.abs(Hct.fromInt(normal.toArgb()).tone - Hct.fromInt(selected.toArgb()).tone)
                assertTrue("dark=$dark #$index tone diff=$diff", diff >= 6.0)
            }
        }
    }

    @Test
    fun `範囲外の番号は色なし`() {
        val palette = folderPaletteFor(darkTheme = false)
        assertEquals(null, palette.containerOrNull(null))
        assertEquals(null, palette.containerOrNull(-1))
        assertEquals(null, palette.containerOrNull(LibraryFolderColors.PALETTE_SIZE))
        assertEquals(palette.containers[0], palette.containerOrNull(0))
        assertEquals(palette.selectedContainers[7], palette.selectedContainerOrNull(7))
    }
}
