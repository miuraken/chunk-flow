package com.miuraken.chunkflow.feature.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import kotlin.math.min

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
