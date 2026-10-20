package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * `MaterialTheme.colorScheme` にない、Chunk Flow 固有の役割色（M3 の custom colors。計画書 §3.1）。
 *
 * 値は固定ではなく、`primary` の色相に調和させて [extendedColorsFor] で実行時に生成する。
 * 画面からは [chunkFlowExtendedColors] で取得する。
 *
 * @property favorite お気に入り（星アイコン）の色。琥珀系。
 * @property onFavorite [favorite] の上に置く文字 / アイコンの色。
 * @property success 成功・既習を表すアクセント色。緑系。
 * @property onSuccess [success] の上に置く文字 / アイコンの色。
 * @property successContainer 成功・既習を表す面の色（バナー、バッジの背景）。
 * @property onSuccessContainer [successContainer] の上に置く文字 / アイコンの色。
 * @property wholeFileRepeat 「ファイル全体をリピート」が ON のチップの背景色。青系。
 * @property onWholeFileRepeat [wholeFileRepeat] の上に置く文字の色。
 * @property transcriptCompleted 全チャンクの文字起こしが完了したことを示すバッジの背景色。緑系（[success] より深い）。
 * @property onTranscriptCompleted [transcriptCompleted] の上に置くアイコンの色。
 * @property autoPauseWaveform 自動ポーズ区間の拡大波形バーの色。茶色系。
 */
@Immutable
data class ChunkFlowExtendedColors(
    val favorite: Color,
    val onFavorite: Color,
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val wholeFileRepeat: Color,
    val onWholeFileRepeat: Color,
    val transcriptCompleted: Color,
    val onTranscriptCompleted: Color,
    val autoPauseWaveform: Color,
)

/** 拡張色の各ロールのトーン（ライト / ダーク）と彩度。 */
internal object ExtendedColorTones {
    const val ACCENT_CHROMA: Double = 48.0
    const val FAVORITE_CHROMA: Double = 80.0
    const val AUTO_PAUSE_WAVEFORM_CHROMA: Double = 32.0

    const val LIGHT_FAVORITE: Double = 75.0
    const val LIGHT_ON_FAVORITE: Double = 10.0
    const val LIGHT_ACCENT: Double = 40.0
    const val LIGHT_ON_ACCENT: Double = 100.0
    const val LIGHT_CONTAINER: Double = 90.0
    const val LIGHT_ON_CONTAINER: Double = 10.0
    const val LIGHT_TRANSCRIPT_COMPLETED: Double = 35.0
    const val LIGHT_AUTO_PAUSE_WAVEFORM: Double = 48.0

    const val DARK_FAVORITE: Double = 80.0
    const val DARK_ON_FAVORITE: Double = 20.0
    const val DARK_ACCENT: Double = 80.0
    const val DARK_ON_ACCENT: Double = 20.0
    const val DARK_CONTAINER: Double = 30.0
    const val DARK_ON_CONTAINER: Double = 90.0
    const val DARK_TRANSCRIPT_COMPLETED: Double = 80.0
    const val DARK_AUTO_PAUSE_WAVEFORM: Double = 72.0
}

/**
 * `primary` に調和させた拡張色を生成する。
 *
 * @param primary `MaterialTheme.colorScheme.primary`（ダイナミックカラーまたは代替配色）。
 * @param darkTheme ダークテーマかどうか。
 */
fun extendedColorsFor(primary: Color, darkTheme: Boolean): ChunkFlowExtendedColors = extendedColorsFor(ChunkFlowHues.from(primary.toArgb()), darkTheme)

internal fun extendedColorsFor(hues: ChunkFlowHues, darkTheme: Boolean): ChunkFlowExtendedColors = with(ExtendedColorTones) {
    if (darkTheme) {
        ChunkFlowExtendedColors(
            favorite = hctColor(hues.favorite, FAVORITE_CHROMA, DARK_FAVORITE),
            onFavorite = hctColor(hues.favorite, FAVORITE_CHROMA, DARK_ON_FAVORITE),
            success = hctColor(hues.completed, ACCENT_CHROMA, DARK_ACCENT),
            onSuccess = hctColor(hues.completed, ACCENT_CHROMA, DARK_ON_ACCENT),
            successContainer = hctColor(hues.completed, ACCENT_CHROMA, DARK_CONTAINER),
            onSuccessContainer = hctColor(hues.completed, ACCENT_CHROMA, DARK_ON_CONTAINER),
            wholeFileRepeat = hctColor(hues.base, ACCENT_CHROMA, DARK_ACCENT),
            onWholeFileRepeat = hctColor(hues.base, ACCENT_CHROMA, DARK_ON_ACCENT),
            transcriptCompleted = hctColor(hues.completed, ACCENT_CHROMA, DARK_TRANSCRIPT_COMPLETED),
            onTranscriptCompleted = hctColor(hues.completed, ACCENT_CHROMA, DARK_ON_ACCENT),
            autoPauseWaveform = hctColor(hues.autoPause, AUTO_PAUSE_WAVEFORM_CHROMA, DARK_AUTO_PAUSE_WAVEFORM),
        )
    } else {
        ChunkFlowExtendedColors(
            favorite = hctColor(hues.favorite, FAVORITE_CHROMA, LIGHT_FAVORITE),
            onFavorite = hctColor(hues.favorite, FAVORITE_CHROMA, LIGHT_ON_FAVORITE),
            success = hctColor(hues.completed, ACCENT_CHROMA, LIGHT_ACCENT),
            onSuccess = hctColor(hues.completed, ACCENT_CHROMA, LIGHT_ON_ACCENT),
            successContainer = hctColor(hues.completed, ACCENT_CHROMA, LIGHT_CONTAINER),
            onSuccessContainer = hctColor(hues.completed, ACCENT_CHROMA, LIGHT_ON_CONTAINER),
            wholeFileRepeat = hctColor(hues.base, ACCENT_CHROMA, LIGHT_ACCENT),
            onWholeFileRepeat = hctColor(hues.base, ACCENT_CHROMA, LIGHT_ON_ACCENT),
            transcriptCompleted = hctColor(hues.completed, ACCENT_CHROMA, LIGHT_TRANSCRIPT_COMPLETED),
            onTranscriptCompleted = hctColor(hues.completed, ACCENT_CHROMA, LIGHT_ON_ACCENT),
            autoPauseWaveform = hctColor(hues.autoPause, AUTO_PAUSE_WAVEFORM_CHROMA, LIGHT_AUTO_PAUSE_WAVEFORM),
        )
    }
}

internal val LocalChunkFlowExtendedColors = staticCompositionLocalOf {
    extendedColorsFor(FallbackLightColorScheme.primary, darkTheme = false)
}

/** 現在のテーマ（ライト / ダーク、`primary` の色相）に応じた [ChunkFlowExtendedColors] を取得する。 */
@Composable
@ReadOnlyComposable
fun chunkFlowExtendedColors(): ChunkFlowExtendedColors = LocalChunkFlowExtendedColors.current
