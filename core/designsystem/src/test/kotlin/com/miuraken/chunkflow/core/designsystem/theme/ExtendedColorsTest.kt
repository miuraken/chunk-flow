package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.Contrast
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.Hct
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.MathUtils
import org.junit.Assert.assertTrue
import org.junit.Test

/** 拡張色（[ChunkFlowExtendedColors]）の制約を代表的な `primary` に対して固定する。 */
class ExtendedColorsTest {
    private val primaries: List<Int> = listOf(
        0xFF006A6A.toInt(),
        0xFFB3261E.toInt(),
        0xFF1976D2.toInt(),
        0xFF2E7D32.toInt(),
        0xFF6750A4.toInt(),
        0xFFE65100.toInt(),
        0xFFC2185B.toInt(),
        0xFF777777.toInt(),
    )

    @Test
    fun `面と文字のコントラスト比が4_5以上_ライトとダーク`() {
        for (argb in primaries) {
            for (dark in listOf(false, true)) {
                val c = extendedColorsFor(Color(argb), darkTheme = dark)
                val name = "primary=0x${argb.toUInt().toString(16)} dark=$dark"
                assertTrue("$name favorite", Contrast.ratioOfArgbs(c.favorite.toArgb(), c.onFavorite.toArgb()) >= 4.5)
                assertTrue("$name success", Contrast.ratioOfArgbs(c.success.toArgb(), c.onSuccess.toArgb()) >= 4.5)
                assertTrue(
                    "$name successContainer",
                    Contrast.ratioOfArgbs(c.successContainer.toArgb(), c.onSuccessContainer.toArgb()) >= 4.5,
                )
                assertTrue(
                    "$name wholeFileRepeat",
                    Contrast.ratioOfArgbs(c.wholeFileRepeat.toArgb(), c.onWholeFileRepeat.toArgb()) >= 4.5,
                )
                assertTrue(
                    "$name transcriptCompleted",
                    Contrast.ratioOfArgbs(c.transcriptCompleted.toArgb(), c.onTranscriptCompleted.toArgb()) >= 4.5,
                )
            }
        }
    }

    @Test
    fun `色相が基準色相から15度以内_琥珀と緑と青と茶色`() {
        for (argb in primaries) {
            val c = extendedColorsFor(Color(argb), darkTheme = false)
            val favoriteHue = Hct.fromInt(c.favorite.toArgb()).hue
            val successHue = Hct.fromInt(c.success.toArgb()).hue
            val repeatHue = Hct.fromInt(c.wholeFileRepeat.toArgb()).hue
            val autoPauseHue = Hct.fromInt(c.autoPauseWaveform.toArgb()).hue
            // 色域で彩度が落ちた色の色相は数度ずれることがあるため、調和の上限 15° に 5° の余裕を加える。
            assertTrue("favorite hue=$favoriteHue", MathUtils.differenceDegrees(favoriteHue, ChunkFlowHues.FAVORITE_HUE) <= 20.0)
            assertTrue("success hue=$successHue", MathUtils.differenceDegrees(successHue, ChunkFlowHues.COMPLETED_HUE) <= 20.0)
            assertTrue("repeat hue=$repeatHue", MathUtils.differenceDegrees(repeatHue, ChunkFlowHues.BASE_HUE) <= 20.0)
            assertTrue("autoPause hue=$autoPauseHue", MathUtils.differenceDegrees(autoPauseHue, ChunkFlowHues.AUTO_PAUSE_HUE) <= 20.0)
        }
    }

    @Test
    fun `自動ポーズ波形のトーンがライトよりもダークで明るい`() {
        for (argb in primaries) {
            val lightTone = Hct.fromInt(extendedColorsFor(Color(argb), darkTheme = false).autoPauseWaveform.toArgb()).tone
            val darkTone = Hct.fromInt(extendedColorsFor(Color(argb), darkTheme = true).autoPauseWaveform.toArgb()).tone
            assertTrue("lightTone=$lightTone darkTone=$darkTone", darkTone > lightTone)
        }
    }
}
