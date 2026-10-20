package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * Chunk Flow のテーマ（§7.5.6）。
 *
 * ダイナミックカラー（Material You）に対応する。プレビューとスクリーンショットテストでは、
 * 画像の比較が安定するように常に代替の配色（[FallbackLightColorScheme] / [FallbackDarkColorScheme]）を使う（§10.5.1）。
 * 言語役割カラー（[chunkFlowLanguagePalette]）と拡張色（[chunkFlowExtendedColors]）は、
 * `primary` の色相に調和させて生成する（計画書 §3.1、§3.2）。
 * [ChunkFlowTypography] と [ChunkFlowShapes] をここで渡す。
 *
 * TODO(Phase A / lead): ADR 0012 の方針どおり `MaterialExpressiveTheme` + `MotionScheme.expressive()` に切り替える。
 *   Compose BOM 2026.09.00（material3 **1.4.0**）では `MaterialExpressiveTheme`、`MotionScheme`、
 *   `ExperimentalMaterial3ExpressiveApi` がすべて `internal` で利用できない（material3 1.5.0-alpha 以降が必要）。
 *   依存の更新は lead の担当なので、更新後にこの関数の `MaterialTheme(...)` を
 *   `MaterialExpressiveTheme(colorScheme, MotionScheme.expressive(), ChunkFlowShapes, ChunkFlowTypography, content)` に
 *   置き換える（OptIn はこのファイルの中だけに置く）。切替点はこの 1 箇所。
 *
 * @param darkTheme ダークテーマにするかどうか。
 * @param dynamicColor ダイナミックカラーを使うかどうか（minSdk 34 なので常に利用できる）。
 */
@Composable
fun ChunkFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val useDynamic = dynamicColor && !LocalInspectionMode.current
    val colorScheme = when {
        useDynamic && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
        useDynamic -> dynamicLightColorScheme(LocalContext.current)
        darkTheme -> FallbackDarkColorScheme
        else -> FallbackLightColorScheme
    }
    val primary = colorScheme.primary
    val languagePalette = remember(primary, darkTheme) { languagePaletteFor(primary, darkTheme) }
    val extendedColors = remember(primary, darkTheme) { extendedColorsFor(primary, darkTheme) }
    CompositionLocalProvider(
        LocalChunkFlowLanguagePalette provides languagePalette,
        LocalChunkFlowExtendedColors provides extendedColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = ChunkFlowShapes,
            typography = ChunkFlowTypography,
            content = content,
        )
    }
}
