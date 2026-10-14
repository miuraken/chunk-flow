package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ダイナミックカラーを使わないとき（プレビュー、スクリーンショットテスト）の代替の配色。
private val Teal10 = Color(0xFF002020)
private val Teal20 = Color(0xFF003737)
private val Teal30 = Color(0xFF004F50)
private val Teal40 = Color(0xFF006A6A)
private val Teal80 = Color(0xFF4CDADA)
private val Teal90 = Color(0xFF6FF7F7)

private val Slate10 = Color(0xFF051F1F)
private val Slate20 = Color(0xFF1B3435)
private val Slate30 = Color(0xFF324B4B)
private val Slate40 = Color(0xFF4A6363)
private val Slate80 = Color(0xFFB0CCCB)
private val Slate90 = Color(0xFFCCE8E7)

private val Amber10 = Color(0xFF261900)
private val Amber20 = Color(0xFF402D00)
private val Amber30 = Color(0xFF5C4200)
private val Amber40 = Color(0xFF7A5900)
private val Amber80 = Color(0xFFF8BD2A)
private val Amber90 = Color(0xFFFFDEA0)

/** ライトテーマの代替の配色。 */
internal val FallbackLightColorScheme = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    secondary = Slate40,
    onSecondary = Color.White,
    secondaryContainer = Slate90,
    onSecondaryContainer = Slate10,
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,
)

/** ダークテーマの代替の配色。 */
internal val FallbackDarkColorScheme = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal20,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    secondary = Slate80,
    onSecondary = Slate20,
    secondaryContainer = Slate30,
    onSecondaryContainer = Slate90,
    tertiary = Amber80,
    onTertiary = Amber20,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
)
