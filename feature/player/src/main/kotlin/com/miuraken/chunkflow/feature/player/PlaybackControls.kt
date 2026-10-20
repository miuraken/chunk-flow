package com.miuraken.chunkflow.feature.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.util.formatCompactSpeed
import com.miuraken.chunkflow.core.model.LoopMode
import kotlin.math.abs
import kotlin.math.min

/**
 * b) 再生に関するボタン列（下部の再生ボタン類のすぐ上部に配置）。
 *
 * 表示項目:
 * - 「1x」（通常再生速度）
 * - 「{指定速度}」（短押しで指定速度に切り替え、長押しで速度設定シートを開く）
 * - 「リピート」（on, *off）
 * - 「再生設定」（全体設定の再生設定タブへ移動）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlaybackControlStrip(
    state: PlayerUiState,
    onEvent: (PlayerEvent) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlaybackSettings: () -> Unit = {},
) {
    val isNormalSpeed = abs(state.speed - 1.0f) < 0.01f
    val isCustomSpeedSelected = !isNormalSpeed
    val isWholeFileRepeat = state.practice.repeatWholeFile
    val isRepeatOn = isWholeFileRepeat || state.practice.loop !is LoopMode.Off || state.practice.repeatCheckedOnly
    val isSkipSilenceOn = state.practice.skipOriginalSilence
    val toggleColors = brightnessToggleChipColors()
    var showWholeFileRepeatDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. 「1x」（通常再生速度）
        FilterChip(
            selected = isNormalSpeed,
            onClick = { onEvent(PlayerEvent.SetSpeed(1.0f)) },
            label = {
                Text(
                    text = "1x",
                    fontWeight = if (isNormalSpeed) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            },
            colors = toggleColors,
        )

        // 2. 「{指定速度}」（短押しで指定速度に切り替え、長押しで速度設定シートを開く）
        val customChipContainerColor = if (isCustomSpeedSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f)
        }
        val customChipContentColor = if (isCustomSpeedSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
        }
        val customChipBorder = if (isCustomSpeedSelected) {
            null
        } else {
            FilterChipDefaults.filterChipBorder(enabled = true, selected = false)
        }
        Surface(
            shape = FilterChipDefaults.shape,
            color = customChipContainerColor,
            contentColor = customChipContentColor,
            border = customChipBorder,
            modifier = Modifier
                .heightIn(min = FilterChipDefaults.Height)
                .clip(FilterChipDefaults.shape)
                .combinedClickable(
                    onClick = { onEvent(PlayerEvent.SetSpeed(state.customSpeed)) },
                    onLongClick = onOpenSpeedSheet,
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatCompactSpeed(state.customSpeed),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isCustomSpeedSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }

        // 3. 「リピート」（短押しでチャンク・全ファイルリピートの ON/OFF、長押しでファイル全体リピートの確認ダイアログ）
        val repeatChipContainerColor = when {
            isWholeFileRepeat -> Color(0xFF1976D2)
            isRepeatOn -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f)
        }
        val repeatChipContentColor = when {
            isWholeFileRepeat -> Color.White
            isRepeatOn -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
        }
        val repeatChipBorder = if (isRepeatOn) {
            null
        } else {
            FilterChipDefaults.filterChipBorder(enabled = true, selected = false)
        }
        Surface(
            shape = FilterChipDefaults.shape,
            color = repeatChipContainerColor,
            contentColor = repeatChipContentColor,
            border = repeatChipBorder,
            modifier = Modifier
                .heightIn(min = FilterChipDefaults.Height)
                .clip(FilterChipDefaults.shape)
                .combinedClickable(
                    onClick = { onEvent(PlayerEvent.ToggleRepeat) },
                    onLongClick = { showWholeFileRepeatDialog = true },
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isWholeFileRepeat) {
                        stringResource(R.string.player_chip_repeat_whole_file)
                    } else {
                        stringResource(R.string.player_chip_repeat)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isRepeatOn) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }

        // 4. 「無音スキップ」（再生設定の「チャンク間の無音をスキップ」と同期）
        FilterChip(
            selected = isSkipSilenceOn,
            onClick = { onEvent(PlayerEvent.ToggleSkipSilence) },
            label = {
                Text(
                    text = stringResource(R.string.player_chip_skip_silence),
                    fontWeight = if (isSkipSilenceOn) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            },
            colors = toggleColors,
        )

        // 5. 「再生設定」（⚙️ アイコン）
        AssistChip(
            onClick = onOpenPlaybackSettings,
            label = {
                Icon(
                    imageVector = ChunkFlowIcons.Settings,
                    contentDescription = stringResource(R.string.player_chip_playback_settings),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }

    if (showWholeFileRepeatDialog) {
        AlertDialog(
            onDismissRequest = { showWholeFileRepeatDialog = false },
            text = {
                Text(text = stringResource(R.string.player_whole_file_repeat_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWholeFileRepeatDialog = false
                        onEvent(PlayerEvent.EnableWholeFileRepeat)
                    },
                ) {
                    Text(text = stringResource(R.string.player_whole_file_repeat_yes))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showWholeFileRepeatDialog = false },
                ) {
                    Text(text = stringResource(R.string.player_whole_file_repeat_no))
                }
            },
        )
    }
}

private enum class TransportOverlayMenu {
    SEEK_BACK,
    PREVIOUS,
    NEXT,
    SEEK_FORWARD,
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransportTonalButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    contentDescriptionText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Surface(
        shape = ButtonDefaults.filledTonalShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .widthIn(min = 60.dp)
            .heightIn(min = 58.dp)
            .clip(ButtonDefaults.filledTonalShape)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = contentDescriptionText },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    val playPauseCd = if (isPlaying) {
        stringResource(R.string.player_pause)
    } else {
        stringResource(R.string.player_play)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            TransportTonalButton(
                onClick = onSeekBack,
                onLongClick = { activeOverlay = TransportOverlayMenu.SEEK_BACK },
                contentDescriptionText = seekBackCd,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = ChunkFlowIcons.SeekBack,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.player_seek_back_label, seekBackSeconds),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(
                expanded = activeOverlay == TransportOverlayMenu.SEEK_BACK,
                onDismissRequest = { activeOverlay = null },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerUiState.SEEK_BACK_PRESETS_MS.forEach { presetMs ->
                        val seconds = (presetMs / 1_000L).toInt()
                        val isSelected = seekBackMs == presetMs
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                activeOverlay = null
                                onSetSeekBackMs(presetMs)
                            },
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
        }

        Box(modifier = Modifier.weight(1f)) {
            TransportTonalButton(
                onClick = onPreviousChunk,
                onLongClick = { activeOverlay = TransportOverlayMenu.PREVIOUS },
                enabled = hasChunks,
                contentDescriptionText = prevChunkCd,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = ChunkFlowIcons.SkipPrevious,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.player_prev_chunk_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onTogglePlayPause,
                    onLongClick = onToggleFloatingControls,
                )
                .semantics {
                    role = Role.Button
                    contentDescription = playPauseCd
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) ChunkFlowIcons.Pause else ChunkFlowIcons.Play,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            TransportTonalButton(
                onClick = { if (hasChunks) onNextChunk() },
                onLongClick = { activeOverlay = TransportOverlayMenu.NEXT },
                enabled = hasChunks || hasNextMediaItem,
                contentDescriptionText = nextChunkCd,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = ChunkFlowIcons.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.player_next_chunk_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

        Box(modifier = Modifier.weight(1f)) {
            TransportTonalButton(
                onClick = onSeekForward,
                onLongClick = { activeOverlay = TransportOverlayMenu.SEEK_FORWARD },
                contentDescriptionText = seekForwardCd,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = ChunkFlowIcons.SeekForward,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.player_seek_forward_label, seekForwardSeconds),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(
                expanded = activeOverlay == TransportOverlayMenu.SEEK_FORWARD,
                onDismissRequest = { activeOverlay = null },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerUiState.SEEK_FORWARD_PRESETS_MS.forEach { presetMs ->
                        val seconds = (presetMs / 1_000L).toInt()
                        val isSelected = seekForwardMs == presetMs
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                activeOverlay = null
                                onSetSeekForwardMs(presetMs)
                            },
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
        }
    }
}
