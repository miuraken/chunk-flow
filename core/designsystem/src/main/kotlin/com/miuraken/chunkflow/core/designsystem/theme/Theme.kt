package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
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
 * ADR 0012 の方針どおり **M3 Expressive** のテーマ（[MaterialExpressiveTheme] + [MotionScheme.expressive]）を使う。
 * Expressive API の OptIn は `core:designsystem` の中だけに置き、feature には
 * `component/expressive/` のラッパー（`ChunkFlowToggleButtonGroup` など）を安定した名前で公開する。
 *
 * ダイナミックカラー（Material You）に対応する。プレビューとスクリーンショットテストでは、
 * 画像の比較が安定するように常に代替の配色（[FallbackLightColorScheme] / [FallbackDarkColorScheme]）を使う（§10.5.1）。
 * 言語役割カラー（[chunkFlowLanguagePalette]）と拡張色（[chunkFlowExtendedColors]）は、
 * `primary` の色相に調和させて生成する（計画書 §3.1、§3.2）。
 * ライブラリのフォルダの色分け（[chunkFlowFolderPalette]）は、決め打ちの色をライト / ダークで切り替える（ADR 0013）。
 * [ChunkFlowTypography] と [ChunkFlowShapes] をここで渡す。
 *
 * @param darkTheme ダークテーマにするかどうか。
 * @param dynamicColor ダイナミックカラーを使うかどうか（minSdk 34 なので常に利用できる）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    val folderPalette = remember(darkTheme) { folderPaletteFor(darkTheme) }
    CompositionLocalProvider(
        LocalChunkFlowLanguagePalette provides languagePalette,
        LocalChunkFlowExtendedColors provides extendedColors,
        LocalChunkFlowFolderPalette provides folderPalette,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = ChunkFlowShapes,
            typography = ChunkFlowTypography,
            content = content,
        )
    }
}
