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
)

/** ライトテーマ用の言語役割別カラーパレット（ベース言語: ブルー系、対象言語: ピンク/レッド系）。 */
val LightChunkFlowLanguagePalette = ChunkFlowLanguagePalette(
    baseRowBackground = Color(0xFFEDF5FF),
    baseActiveRowBackground = Color(0xFFD6EAFF),
    baseLoopRowBackground = Color(0xFFE0EFFF),
    baseBadgeContainer = Color(0xFFD4E8FF),
    onBaseBadgeContainer = Color(0xFF0A3966),
    baseChunkBackground = Color(0xFFB3D7FF),
    baseWaveformPeak = Color(0xFF42A5F5).copy(alpha = 0.65f),
    baseWaveformRms = Color(0xFF1565C0),
    targetRowBackground = Color(0xFFFFEFF5),
    targetActiveRowBackground = Color(0xFFFFDCE9),
    targetLoopRowBackground = Color(0xFFFFE5EF),
    targetBadgeContainer = Color(0xFFFFD6E5),
    onTargetBadgeContainer = Color(0xFF6B0F36),
    targetChunkBackground = Color(0xFFFFB5D0),
    targetWaveformPeak = Color(0xFFEF5350).copy(alpha = 0.65f),
    targetWaveformRms = Color(0xFFD32F2F),
    completedProgressText = Color(0xFF2E7D32),
)

/** ダークテーマ用の言語役割別カラーパレット（ベース言語: ブルー系、対象言語: ピンク/レッド系）。 */
val DarkChunkFlowLanguagePalette = ChunkFlowLanguagePalette(
    baseRowBackground = Color(0xFF64B5F6).copy(alpha = 0.14f),
    baseActiveRowBackground = Color(0xFF64B5F6).copy(alpha = 0.28f),
    baseLoopRowBackground = Color(0xFF64B5F6).copy(alpha = 0.22f),
    baseBadgeContainer = Color(0xFF144272),
    onBaseBadgeContainer = Color(0xFFD4E8FF),
    baseChunkBackground = Color(0xFF1E5696),
    baseWaveformPeak = Color(0xFF90CAF9).copy(alpha = 0.65f),
    baseWaveformRms = Color(0xFF42A5F5),
    targetRowBackground = Color(0xFFF48FB1).copy(alpha = 0.14f),
    targetActiveRowBackground = Color(0xFFF48FB1).copy(alpha = 0.28f),
    targetLoopRowBackground = Color(0xFFF48FB1).copy(alpha = 0.22f),
    targetBadgeContainer = Color(0xFF6A1B3D),
    onTargetBadgeContainer = Color(0xFFFFD6E5),
    targetChunkBackground = Color(0xFF84244E),
    targetWaveformPeak = Color(0xFFE57373).copy(alpha = 0.65f),
    targetWaveformRms = Color(0xFFEF5350),
    completedProgressText = Color(0xFF81C784),
)

internal val LocalChunkFlowLanguagePalette = staticCompositionLocalOf {
    LightChunkFlowLanguagePalette
}

/** 現在のテーマ（ライト / ダーク）に応じた [ChunkFlowLanguagePalette] を取得する。 */
@Composable
@ReadOnlyComposable
fun chunkFlowLanguagePalette(): ChunkFlowLanguagePalette = LocalChunkFlowLanguagePalette.current

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
