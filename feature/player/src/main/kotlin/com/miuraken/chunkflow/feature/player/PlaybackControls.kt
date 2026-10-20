package com.miuraken.chunkflow.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowPlayPauseButton
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowTransportButton
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons

private enum class TransportOverlayMenu {
    SEEK_BACK,
    PREVIOUS,
    NEXT,
    SEEK_FORWARD,
}

/** 再生 / 一時停止ボタンの一辺（M3 Expressive の大サイズ `FilledIconButton` 相当。計画書 §3.4）。 */
private val PlayPauseButtonSize = 72.dp

/**
 * トランスポート 5 ボタン（`-3s` / `prev` / 再生 / `next` / `+5s`）。
 *
 * 4 つの補助ボタンは [ChunkFlowTransportButton]（`FilledTonalIconButton` + ラベル）、中央は
 * [ChunkFlowPlayPauseButton]（大サイズ `FilledIconButton` + shape morphing）で描く（調査 §3.2 C3）。
 * 長押しのメニュー（秒数プリセット / ジャンプ）はそのまま残し、長押しでできることは `TooltipBox` で説明する（C2）。
 * 再生ボタンの長押しはフローティング操作バーの表示切替。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerTransportControls(
    isPlaying: Boolean,
    seekBackMs: Long,
    seekForwardMs: Long,
    hasChunks: Boolean,
    hasPreviousCheckedChunk: Boolean,
    hasNextCheckedChunk: Boolean,
    hasNextMediaItem: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    onSetSeekBackMs: (Long) -> Unit,
    onSetSeekForwardMs: (Long) -> Unit,
    onSeekToFileStart: () -> Unit,
    onSeekToPreviousCheckedChunk: () -> Unit,
    onSeekToNextCheckedChunk: () -> Unit,
    onOpenNextMediaItem: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFloatingControls: () -> Unit = {},
) {
    var activeOverlay by remember { mutableStateOf<TransportOverlayMenu?>(null) }
    val seekBackSeconds = (seekBackMs / 1_000L).coerceAtLeast(1L)
    val seekForwardSeconds = (seekForwardMs / 1_000L).coerceAtLeast(1L)
    val seekBackCd = stringResource(R.string.player_seek_back_cd, seekBackSeconds)
    val seekForwardCd = stringResource(R.string.player_seek_forward_cd, seekForwardSeconds)
    val prevChunkCd = stringResource(R.string.player_prev_chunk_cd)
    val nextChunkCd = stringResource(R.string.player_next_chunk_cd)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. 巻き戻し（長押し: 秒数プリセット）
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            LongPressTooltip(text = stringResource(R.string.player_seek_back_long_press_tooltip)) {
                ChunkFlowTransportButton(
                    icon = ChunkFlowIcons.SeekBack,
                    contentDescription = seekBackCd,
                    onClick = onSeekBack,
                    onLongClick = { activeOverlay = TransportOverlayMenu.SEEK_BACK },
                    label = stringResource(R.string.player_seek_back_label, seekBackSeconds),
                )
            }
            DropdownMenu(
                expanded = activeOverlay == TransportOverlayMenu.SEEK_BACK,
                onDismissRequest = { activeOverlay = null },
            ) {
                SeekPresetRow(
                    presetsMs = PlayerUiState.SEEK_BACK_PRESETS_MS,
                    selectedMs = seekBackMs,
                    onSelect = { presetMs ->
                        activeOverlay = null
                        onSetSeekBackMs(presetMs)
                    },
                )
            }
        }

        // 2. 前のチャンク（長押し: ファイルの先頭 / 前のチェック済みチャンク）
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            LongPressTooltip(text = stringResource(R.string.player_prev_chunk_long_press_tooltip)) {
                ChunkFlowTransportButton(
                    icon = ChunkFlowIcons.SkipPrevious,
                    contentDescription = prevChunkCd,
                    onClick = onPreviousChunk,
                    onLongClick = { activeOverlay = TransportOverlayMenu.PREVIOUS },
                    label = stringResource(R.string.player_prev_chunk_label),
                    enabled = hasChunks,
                )
            }
            DropdownMenu(
                expanded = activeOverlay == TransportOverlayMenu.PREVIOUS,
                onDismissRequest = { activeOverlay = null },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            activeOverlay = null
                            onSeekToFileStart()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.player_long_press_file_start),
                            maxLines = 1,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            activeOverlay = null
                            onSeekToPreviousCheckedChunk()
                        },
                        enabled = hasPreviousCheckedChunk,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.player_long_press_prev_checked),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // 3. 再生 / 一時停止（長押し: フローティング操作バーの表示切替）
        LongPressTooltip(text = stringResource(R.string.player_play_pause_long_press_tooltip)) {
            ChunkFlowPlayPauseButton(
                isPlaying = isPlaying,
                onClick = onTogglePlayPause,
                onLongClick = onToggleFloatingControls,
                size = PlayPauseButtonSize,
            )
        }

        // 4. 次のチャンク（長押し: 次のチェック済みチャンク / 次のファイル）
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            LongPressTooltip(text = stringResource(R.string.player_next_chunk_long_press_tooltip)) {
                ChunkFlowTransportButton(
                    icon = ChunkFlowIcons.SkipNext,
                    contentDescription = nextChunkCd,
                    onClick = { if (hasChunks) onNextChunk() },
                    onLongClick = { activeOverlay = TransportOverlayMenu.NEXT },
                    label = stringResource(R.string.player_next_chunk_label),
                    enabled = hasChunks || hasNextMediaItem,
                )
            }
            DropdownMenu(
                expanded = activeOverlay == TransportOverlayMenu.NEXT,
                onDismissRequest = { activeOverlay = null },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            activeOverlay = null
                            onSeekToNextCheckedChunk()
                        },
                        enabled = hasNextCheckedChunk,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.player_long_press_next_checked),
                            maxLines = 1,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            activeOverlay = null
                            onOpenNextMediaItem()
                        },
                        enabled = hasNextMediaItem,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.player_long_press_next_file),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        // 5. 早送り（長押し: 秒数プリセット）
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            LongPressTooltip(text = stringResource(R.string.player_seek_forward_long_press_tooltip)) {
                ChunkFlowTransportButton(
                    icon = ChunkFlowIcons.SeekForward,
                    contentDescription = seekForwardCd,
                    onClick = onSeekForward,
                    onLongClick = { activeOverlay = TransportOverlayMenu.SEEK_FORWARD },
                    label = stringResource(R.string.player_seek_forward_label, seekForwardSeconds),
                )
            }
            DropdownMenu(
                expanded = activeOverlay == TransportOverlayMenu.SEEK_FORWARD,
                onDismissRequest = { activeOverlay = null },
            ) {
                SeekPresetRow(
                    presetsMs = PlayerUiState.SEEK_FORWARD_PRESETS_MS,
                    selectedMs = seekForwardMs,
                    onSelect = { presetMs ->
                        activeOverlay = null
                        onSetSeekForwardMs(presetMs)
                    },
                )
            }
        }
    }
}

/** 巻き戻し / 早送りの秒数プリセット（長押しメニューの中身）。 */
@Composable
private fun SeekPresetRow(
    presetsMs: List<Long>,
    selectedMs: Long,
    onSelect: (Long) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presetsMs.forEach { presetMs ->
            val seconds = (presetMs / 1_000L).toInt()
            FilterChip(
                selected = selectedMs == presetMs,
                onClick = { onSelect(presetMs) },
                label = {
                    Text(
                        text = stringResource(R.string.player_seek_preset_seconds_format, seconds),
                        maxLines = 1,
                    )
                },
            )
        }
    }
}

/**
 * 長押しでできることを説明する `PlainTooltip`（ホバー / TalkBack の長押しで表示。調査 §3.2 C2）。
 * `core:designsystem` の `PlaybackControlStrip` 内の同名の部品と同じ書き方。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LongPressTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
}
