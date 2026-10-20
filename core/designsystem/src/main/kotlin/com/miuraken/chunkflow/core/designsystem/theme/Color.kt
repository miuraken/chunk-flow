package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

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
