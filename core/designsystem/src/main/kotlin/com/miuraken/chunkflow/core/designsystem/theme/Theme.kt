package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/**
 * Chunk Flow のテーマ（§7.5.6）。
 *
 * ダイナミックカラー（Material You）に対応する。プレビューとスクリーンショットテストでは、
 * 画像の比較が安定するように常に代替の配色を使う（§10.5.1）。
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
    val languagePalette = if (darkTheme) {
        DarkChunkFlowLanguagePalette
    } else {
        LightChunkFlowLanguagePalette
    }
    CompositionLocalProvider(LocalChunkFlowLanguagePalette provides languagePalette) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
