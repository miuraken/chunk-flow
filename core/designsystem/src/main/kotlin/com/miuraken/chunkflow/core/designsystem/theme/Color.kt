package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
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

/**
 * ベース言語（ブルー系）と対象言語（ピンク系）の役割別カラーパレット。
 *
 * チャンク一覧の控えめな行背景色と、チャンク行・ライブラリ画面の言語タグバッジ色を共通化する。
 * あわせて、ライブラリ画面で 95% を超えて聴いた教材（既習）を示す緑系の色（再生状況の文字
 * [completedProgressText] と進捗バー [completedProgressIndicator]）も持つ。
 *
 * 値は固定ではなく、`primary`（ダイナミックカラーまたは代替配色）の色相に調和させて
 * [languagePaletteFor] で実行時に生成する（計画書 §3.2）。画面からは [chunkFlowLanguagePalette] で取得する。
 */
@Immutable
data class ChunkFlowLanguagePalette(
    val baseRowBackground: Color,
    val baseActiveRowBackground: Color,
    val baseLoopRowBackground: Color,
    val baseBadgeContainer: Color,
    val onBaseBadgeContainer: Color,
    val baseChunkBackground: Color,
    val baseWaveformPeak: Color,
    val baseWaveformRms: Color,
    val targetRowBackground: Color,
    val targetActiveRowBackground: Color,
    val targetLoopRowBackground: Color,
    val targetBadgeContainer: Color,
    val onTargetBadgeContainer: Color,
    val targetChunkBackground: Color,
    val targetWaveformPeak: Color,
    val targetWaveformRms: Color,
    val completedProgressText: Color,
    val completedProgressIndicator: Color,
)

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

/**
 * ライトテーマ用の言語役割別カラーパレット（代替配色の `primary` に調和させた値）。
 *
 * 互換のために残している。画面からは [chunkFlowLanguagePalette] を使う（ダイナミックカラーに追従する）。
 */
@Deprecated("chunkFlowLanguagePalette() を使う（ダイナミックカラーの primary に調和した値になる）")
val LightChunkFlowLanguagePalette: ChunkFlowLanguagePalette by lazy {
    languagePaletteFor(FallbackLightColorScheme.primary, darkTheme = false)
}

/**
 * ダークテーマ用の言語役割別カラーパレット（代替配色の `primary` に調和させた値）。
 *
 * 互換のために残している。画面からは [chunkFlowLanguagePalette] を使う（ダイナミックカラーに追従する）。
 */
@Deprecated("chunkFlowLanguagePalette() を使う（ダイナミックカラーの primary に調和した値になる）")
val DarkChunkFlowLanguagePalette: ChunkFlowLanguagePalette by lazy {
    languagePaletteFor(FallbackDarkColorScheme.primary, darkTheme = true)
}

internal val LocalChunkFlowLanguagePalette = staticCompositionLocalOf {
    languagePaletteFor(FallbackLightColorScheme.primary, darkTheme = false)
}

/** 現在のテーマ（ライト / ダーク、`primary` の色相）に応じた [ChunkFlowLanguagePalette] を取得する。 */
@Composable
@ReadOnlyComposable
fun chunkFlowLanguagePalette(): ChunkFlowLanguagePalette = LocalChunkFlowLanguagePalette.current
