package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ダイナミックカラーを使わないとき（プレビュー、スクリーンショットテスト）の代替の配色。
// 雛形の配色（Phase 0）。ui が core:designsystem で作り直す。
private val Teal40 = Color(0xFF006A6A)
private val Teal80 = Color(0xFF4CDADA)
private val Slate40 = Color(0xFF4A6363)
private val Slate80 = Color(0xFFB0CCCB)
private val Amber40 = Color(0xFF7A5900)
private val Amber80 = Color(0xFFF8BD2A)

/** ライトテーマの代替の配色。 */
internal val FallbackLightColorScheme = lightColorScheme(
    primary = Teal40,
    secondary = Slate40,
    tertiary = Amber40,
)

/** ダークテーマの代替の配色。 */
internal val FallbackDarkColorScheme = darkColorScheme(
    primary = Teal80,
    secondary = Slate80,
    tertiary = Amber80,
)
