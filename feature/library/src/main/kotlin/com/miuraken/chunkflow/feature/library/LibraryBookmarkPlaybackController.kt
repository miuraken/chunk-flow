package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowPlayPauseButton
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowTransportButton
import com.miuraken.chunkflow.core.designsystem.component.playback.PlaybackControlStrip
import com.miuraken.chunkflow.core.designsystem.component.playback.SpeedSheetContent
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs

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

            PlaybackControlStrip(
                speed = state.playbackSpeed,
                customSpeed = state.customPlaybackSpeed,
                practice = state.practice,
                onSetSpeed = { speed -> onEvent(LibraryEvent.BookmarkPlayerSetSpeed(speed)) },
                onOpenSpeedSheet = { showSpeedSheet = true },
                onToggleRepeat = { onEvent(LibraryEvent.BookmarkPlayerToggleRepeat) },
                onEnableWholeFileRepeat = { onEvent(LibraryEvent.BookmarkPlayerEnableWholeFileRepeat) },
                onToggleSkipSilence = { onEvent(LibraryEvent.BookmarkPlayerToggleSkipSilence) },
                onOpenPlaybackSettings = { onEvent(LibraryEvent.OpenSettings) },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChunkFlowTransportButton(
                    icon = ChunkFlowIcons.SeekBack,
                    contentDescription = stringResource(
                        R.string.library_bookmarks_seek_back_cd,
                        seekBackSeconds,
                    ),
                    onClick = { onEvent(LibraryEvent.BookmarkPlayerSeekBack) },
                    label = stringResource(
                        R.string.library_bookmarks_seek_back_label,
                        seekBackSeconds,
                    ),
                    enabled = hasPlayableTarget,
                    size = BookmarkTransportButtonSize,
                )

                ChunkFlowTransportButton(
                    icon = ChunkFlowIcons.SkipPrevious,
                    contentDescription = stringResource(R.string.library_bookmarks_prev_chunk_cd),
                    onClick = { onEvent(LibraryEvent.BookmarkPlayerPreviousChunk) },
                    label = stringResource(R.string.library_bookmarks_prev_chunk_label),
                    enabled = hasPlayableTarget,
                    size = BookmarkTransportButtonSize,
                )

                ChunkFlowPlayPauseButton(
                    isPlaying = isPlaying,
                    onClick = { onEvent(LibraryEvent.BookmarkPlayerTogglePlayPause) },
                    size = BookmarkPlayPauseButtonSize,
                    enabled = hasPlayableTarget,
                )

                ChunkFlowTransportButton(
                    icon = ChunkFlowIcons.SkipNext,
                    contentDescription = stringResource(R.string.library_bookmarks_next_chunk_cd),
                    onClick = { onEvent(LibraryEvent.BookmarkPlayerNextChunk) },
                    label = stringResource(R.string.library_bookmarks_next_chunk_label),
                    enabled = hasPlayableTarget,
                    size = BookmarkTransportButtonSize,
                )

                ChunkFlowTransportButton(
                    icon = ChunkFlowIcons.SeekForward,
                    contentDescription = stringResource(
                        R.string.library_bookmarks_seek_forward_cd,
                        seekForwardSeconds,
                    ),
                    onClick = { onEvent(LibraryEvent.BookmarkPlayerSeekForward) },
                    label = stringResource(
                        R.string.library_bookmarks_seek_forward_label,
                        seekForwardSeconds,
                    ),
                    enabled = hasPlayableTarget,
                    size = BookmarkTransportButtonSize,
                )
            }
        }
    }

    if (showSpeedSheet) {
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
            SpeedSheetContent(
                speed = state.customPlaybackSpeed,
                presets = BOOKMARK_SPEED_PRESETS,
                closeLabel = stringResource(R.string.library_edit_cancel),
                onSetSpeed = { speed -> onEvent(LibraryEvent.BookmarkPlayerSetCustomSpeed(speed)) },
                onAdjustSpeed = { delta -> onEvent(LibraryEvent.BookmarkPlayerAdjustCustomSpeed(delta)) },
                onClose = { showSpeedSheet = false },
            )
        }
    }
}

private val BOOKMARK_SPEED_PRESETS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)

/** 前後ブックマーク・秒送りボタンの一辺（M3 の最小タップ領域 48dp）。 */
private val BookmarkTransportButtonSize = 48.dp

/** 再生 / 一時停止ボタンの一辺。 */
private val BookmarkPlayPauseButtonSize = 56.dp
