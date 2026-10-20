package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.util.formatCompactSpeed
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LoopMode
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryBookmarkPlaybackController(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeMediaId = state.activePlaybackMediaId ?: state.miniPlayer?.mediaId
    val activeFile = state.bookmarkedFiles.firstOrNull { it.mediaId == activeMediaId }
    val activeChunk = state.activeBookmarkedChunk
    val miniPlayer = state.miniPlayer
    val isPlaying = miniPlayer?.isPlaying == true
    val hasPlayableTarget = activeChunk != null ||
        miniPlayer != null ||
        state.bookmarkedFiles.any { it.bookmarkedChunks.isNotEmpty() }

    val seekBackSeconds = (state.seekBackMs / 1_000L).coerceAtLeast(1L).toInt()
    val seekForwardSeconds = (state.seekForwardMs / 1_000L).coerceAtLeast(1L).toInt()
    val playPauseCd = if (isPlaying) {
        stringResource(R.string.library_mini_player_pause)
    } else {
        stringResource(R.string.library_mini_player_play)
    }

    val titleText = activeFile?.title
        ?: miniPlayer?.title
        ?: stringResource(R.string.library_bookmarks_controller_idle)
    val subtitleText: String? = if (activeChunk != null) {
        val chunkLabel = activeChunk.transcript?.text?.takeIf { it.isNotBlank() }
            ?: stringResource(
                R.string.library_bookmarks_chunk_fallback_label,
                activeChunk.chunkNumber,
            )
        "${formatDurationMs(activeChunk.chunk.startMs)} · $chunkLabel"
    } else if (miniPlayer != null) {
        if (miniPlayer.positionMs > 0L) {
            stringResource(
                R.string.library_item_duration_progress,
                formatDurationMs(miniPlayer.positionMs),
                formatDurationMs(miniPlayer.durationMs),
            )
        } else {
            formatDurationMs(miniPlayer.durationMs)
        }
    } else {
        null
    }
    val openMediaId = activeFile?.mediaId ?: miniPlayer?.mediaId
    var showSpeedSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (openMediaId != null) {
                            Modifier.clickable { onEvent(LibraryEvent.OpenItem(openMediaId)) }
                        } else {
                            Modifier
                        },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.Bookmark,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitleText != null) {
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            BookmarkPlaybackControlStrip(
                state = state,
                onEvent = onEvent,
                onOpenSpeedSheet = { showSpeedSheet = true },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookmarkSeekStepButton(
                    icon = ChunkFlowIcons.SeekBack,
                    badgeLabel = stringResource(
                        R.string.library_bookmarks_seek_back_label,
                        seekBackSeconds,
                    ),
                    contentDescription = stringResource(
                        R.string.library_bookmarks_seek_back_cd,
                        seekBackSeconds,
                    ),
                    enabled = hasPlayableTarget,
                    onClick = { onEvent(LibraryEvent.BookmarkPlayerSeekBack) },
                )

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { onEvent(LibraryEvent.BookmarkPlayerPreviousChunk) },
                        enabled = hasPlayableTarget,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.SkipPrevious,
                            contentDescription = stringResource(
                                R.string.library_bookmarks_prev_chunk_cd,
                            ),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.library_bookmarks_prev_chunk_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                FilledIconButton(
                    onClick = { onEvent(LibraryEvent.BookmarkPlayerTogglePlayPause) },
                    enabled = hasPlayableTarget,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (isPlaying) {
                            ChunkFlowIcons.Pause
                        } else {
                            ChunkFlowIcons.Play
                        },
                        contentDescription = playPauseCd,
                        modifier = Modifier.size(26.dp),
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(
                        onClick = { onEvent(LibraryEvent.BookmarkPlayerNextChunk) },
                        enabled = hasPlayableTarget,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.SkipNext,
                            contentDescription = stringResource(
                                R.string.library_bookmarks_next_chunk_cd,
                            ),
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Text(
                        text = stringResource(R.string.library_bookmarks_next_chunk_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                BookmarkSeekStepButton(
                    icon = ChunkFlowIcons.SeekForward,
                    badgeLabel = stringResource(
                        R.string.library_bookmarks_seek_forward_label,
                        seekForwardSeconds,
                    ),
                    contentDescription = stringResource(
                        R.string.library_bookmarks_seek_forward_cd,
                        seekForwardSeconds,
                    ),
                    enabled = hasPlayableTarget,
                    onClick = { onEvent(LibraryEvent.BookmarkPlayerSeekForward) },
                )
            }
        }
    }

    if (showSpeedSheet) {
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
            BookmarkSpeedControlSheetContent(
                speed = state.customPlaybackSpeed,
                onAdjustSpeed = { delta -> onEvent(LibraryEvent.BookmarkPlayerAdjustCustomSpeed(delta)) },
                onSetSpeed = { speed -> onEvent(LibraryEvent.BookmarkPlayerSetCustomSpeed(speed)) },
                onClose = { showSpeedSheet = false },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkPlaybackControlStrip(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isNormalSpeed = abs(state.playbackSpeed - 1.0f) < 0.01f
    val isCustomSpeedSelected = !isNormalSpeed
    val isWholeFileRepeat = state.practice.repeatWholeFile
    val isRepeatOn = isWholeFileRepeat || state.practice.loop !is LoopMode.Off || state.practice.repeatCheckedOnly
    val isSkipSilenceOn = state.practice.skipOriginalSilence
    val toggleColors = FilterChipDefaults.filterChipColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f),
        labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
        iconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    var showWholeFileRepeatDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = isNormalSpeed,
            onClick = { onEvent(LibraryEvent.BookmarkPlayerSetSpeed(1.0f)) },
            label = {
                Text(
                    text = "1x",
                    fontWeight = if (isNormalSpeed) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            },
            colors = toggleColors,
        )

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
                    onClick = { onEvent(LibraryEvent.BookmarkPlayerSetSpeed(state.customPlaybackSpeed)) },
                    onLongClick = onOpenSpeedSheet,
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatCompactSpeed(state.customPlaybackSpeed),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isCustomSpeedSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }

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
                    onClick = { onEvent(LibraryEvent.BookmarkPlayerToggleRepeat) },
                    onLongClick = { showWholeFileRepeatDialog = true },
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isWholeFileRepeat) {
                        stringResource(R.string.library_bookmarks_chip_repeat_whole_file)
                    } else {
                        stringResource(R.string.library_bookmarks_chip_repeat)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isRepeatOn) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }

        FilterChip(
            selected = isSkipSilenceOn,
            onClick = { onEvent(LibraryEvent.BookmarkPlayerToggleSkipSilence) },
            label = {
                Text(
                    text = stringResource(R.string.library_bookmarks_chip_skip_silence),
                    fontWeight = if (isSkipSilenceOn) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            },
            colors = toggleColors,
        )

        AssistChip(
            onClick = { onEvent(LibraryEvent.OpenSettings) },
            label = {
                Icon(
                    imageVector = ChunkFlowIcons.Settings,
                    contentDescription = stringResource(R.string.library_bookmarks_chip_playback_settings),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }

    if (showWholeFileRepeatDialog) {
        AlertDialog(
            onDismissRequest = { showWholeFileRepeatDialog = false },
            text = {
                Text(text = stringResource(R.string.library_bookmarks_whole_file_repeat_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWholeFileRepeatDialog = false
                        onEvent(LibraryEvent.BookmarkPlayerEnableWholeFileRepeat)
                    },
                ) {
                    Text(text = stringResource(R.string.library_bookmarks_whole_file_repeat_yes))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showWholeFileRepeatDialog = false },
                ) {
                    Text(text = stringResource(R.string.library_bookmarks_whole_file_repeat_no))
                }
            },
        )
    }
}

private val BOOKMARK_SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookmarkSpeedControlSheetContent(
    speed: Float,
    onAdjustSpeed: (Float) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onClose: () -> Unit,
) {
    val decreaseCd = stringResource(R.string.library_bookmarks_speed_decrease_cd)
    val increaseCd = stringResource(R.string.library_bookmarks_speed_increase_cd)
    val sliderCd = stringResource(R.string.library_bookmarks_speed_slider_cd)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.library_bookmarks_speed_section_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = formatCompactSpeed(speed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onAdjustSpeed(-0.05f) },
                enabled = speed > ItemState.MIN_SPEED + 0.001f,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = decreaseCd },
            ) {
                Text(text = "-0.05x")
            }
            OutlinedButton(
                onClick = { onAdjustSpeed(0.05f) },
                enabled = speed < ItemState.MAX_SPEED - 0.001f,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = increaseCd },
            ) {
                Text(text = "+0.05x")
            }
        }

        Slider(
            value = speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED),
            onValueChange = onSetSpeed,
            valueRange = ItemState.MIN_SPEED..ItemState.MAX_SPEED,
            steps = 29,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = sliderCd },
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BOOKMARK_SPEED_PRESETS.forEach { preset ->
                val isSelected = abs(speed - preset) < 0.01f
                val labelText = formatCompactSpeed(preset)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSetSpeed(preset) },
                    label = { Text(text = labelText) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onClose) {
                Text(text = stringResource(R.string.library_edit_cancel))
            }
        }
    }
}

@Composable
private fun BookmarkSeekStepButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    badgeLabel: String,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = badgeLabel,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
