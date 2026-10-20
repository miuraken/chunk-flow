package com.miuraken.chunkflow.core.designsystem.component.playback

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * ON / OFF のトグルをボタンの明度で表現する [androidx.compose.material3.FilterChip] カラー設定。
 *
 * ON（`selected = true`）は明るく強調し、OFF（`selected = false`）は暗め（低明度・低不透明度）にする。
 * 再生操作ストリップ（[PlaybackControlStrip]）とプレイヤーのチャンク表示設定行で共有する。
 */
@Composable
fun brightnessToggleChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f),
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/**
 * 全チャンクの文字起こしが完了したことを示すバッジ（緑丸 + 白抜きアイコン）の背景色。
 *
 * ライブラリの行とプレイヤーの TopAppBar で同じ値を使う。
 * TODO(Phase A): `ChunkFlowExtendedColors` に移してテーマ化する（docs/ux/m3-migration-plan.md §3.1）。
 */
val CompletedTranscriptBadgeColor: Color = Color(0xFF19532B)

/**
 * 「ファイル全体をリピート」が ON のときのリピートチップの背景色（文字色は白）。
 *
 * TODO(Phase A): `ChunkFlowExtendedColors.wholeFileRepeat` に移してテーマ化する。
 */
internal val WholeFileRepeatContainerColor: Color = Color(0xFF1976D2)
