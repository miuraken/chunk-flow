package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.MediaId
import java.util.Locale

@Composable
internal fun LibraryBookmarksTabContent(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bookmarkedFiles = state.bookmarkedFiles
    val activePlaybackMediaId = state.activePlaybackMediaId ?: state.miniPlayer?.mediaId
    val activePlaybackChunkId = state.activePlaybackChunkId
    val isPlaying = state.miniPlayer?.isPlaying == true

    if (bookmarkedFiles.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.BookmarkBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
                Text(
                    text = stringResource(R.string.library_bookmarks_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.library_bookmarks_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    var pendingClearFile by remember { mutableStateOf<LibraryBookmarkedFileUiModel?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = bookmarkedFiles,
            key = { fileModel -> "bookmark_file_${fileModel.mediaId.value}" },
        ) { fileModel ->
            LibraryBookmarkedFileAccordionCard(
                fileModel = fileModel,
                activePlaybackMediaId = activePlaybackMediaId,
                activePlaybackChunkId = activePlaybackChunkId,
                isPlaying = isPlaying,
                onToggleExpand = {
                    onEvent(LibraryEvent.ToggleBookmarkFileAccordion(fileModel.mediaId))
                },
                onPlayChunk = { chunkId ->
                    onEvent(LibraryEvent.PlayBookmarkedChunk(fileModel.mediaId, chunkId))
                },
                onOpenChunkInPlayer = { chunkId ->
                    onEvent(LibraryEvent.OpenBookmarkedChunkInPlayer(fileModel.mediaId, chunkId))
                },
                onRequestClearFileBookmarks = {
                    pendingClearFile = fileModel
                },
            )
        }
    }

    pendingClearFile?.let { targetFile ->
        AlertDialog(
            onDismissRequest = { pendingClearFile = null },
            text = {
                Text(
                    text = stringResource(
                        R.string.library_bookmarks_clear_file_confirm_message,
                        targetFile.title,
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingClearFile = null
                        onEvent(LibraryEvent.ClearBookmarksForMedia(targetFile.mediaId))
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(text = stringResource(R.string.library_bookmarks_clear_file_yes))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingClearFile = null },
                ) {
                    Text(text = stringResource(R.string.library_bookmarks_clear_file_no))
                }
            },
        )
    }
}

@Composable
private fun LibraryBookmarkedFileAccordionCard(
    fileModel: LibraryBookmarkedFileUiModel,
    activePlaybackMediaId: MediaId?,
    activePlaybackChunkId: ChunkId?,
    isPlaying: Boolean,
    onToggleExpand: () -> Unit,
    onPlayChunk: (ChunkId) -> Unit,
    onOpenChunkInPlayer: (ChunkId) -> Unit,
    onRequestClearFileBookmarks: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = fileModel.itemModel.item
    val folderText = fileModel.folderName?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.library_bookmarks_unassigned_folder)
    val expandCollapseCd = if (fileModel.isExpanded) {
        stringResource(R.string.library_bookmarks_collapse_cd, item.title)
    } else {
        stringResource(R.string.library_bookmarks_expand_cd, item.title)
    }
    val isActiveFile = activePlaybackMediaId == item.id

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = if (isActiveFile) 2.dp else 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .semantics { contentDescription = expandCollapseCd }
                    .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = folderText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Bookmark,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = stringResource(
                                R.string.library_bookmarks_chunk_count,
                                fileModel.bookmarkedChunks.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                IconButton(
                    onClick = onRequestClearFileBookmarks,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Delete,
                        contentDescription = stringResource(
                            R.string.library_bookmarks_clear_file_cd,
                            item.title,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }

                Icon(
                    imageVector = if (fileModel.isExpanded) {
                        ChunkFlowIcons.ExpandLess
                    } else {
                        ChunkFlowIcons.ExpandMore
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }

            if (fileModel.isExpanded) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    fileModel.bookmarkedChunks.forEachIndexed { index, chunkModel ->
                        val isActiveChunk = isActiveFile &&
                            activePlaybackChunkId == chunkModel.chunk.id
                        LibraryBookmarkedChunkRow(
                            chunkModel = chunkModel,
                            languagePair = item.languagePair,
                            isActive = isActiveChunk,
                            isPlaying = isActiveChunk && isPlaying,
                            onClick = { onPlayChunk(chunkModel.chunk.id) },
                            onOpenInPlayer = { onOpenChunkInPlayer(chunkModel.chunk.id) },
                        )
                        if (index < fileModel.bookmarkedChunks.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryBookmarkedChunkRow(
    chunkModel: LibraryBookmarkedChunkUiModel,
    languagePair: LanguagePair,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onOpenInPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chunk = chunkModel.chunk
    val transcript = chunkModel.transcript
    val languageTag = transcript?.language
    val langPalette = chunkFlowLanguagePalette()
    val containerColor = if (isActive) {
        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
    } else {
        Color.Transparent
    }
    val textColor = if (isActive) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val fallbackText = stringResource(
        R.string.library_bookmarks_chunk_fallback_label,
        chunkModel.chunkNumber,
    )
    val displayText = transcript?.text?.takeIf { it.isNotBlank() } ?: fallbackText

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = containerColor,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(
                        if (isActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                    ),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatDurationMs(chunk.startMs),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .widthIn(min = 44.dp)
                        .padding(end = 6.dp),
                )
                if (languageTag != null) {
                    val isBase = languageTag == languagePair.base
                    val badgeContainer = if (isBase) {
                        langPalette.baseBadgeContainer
                    } else {
                        langPalette.targetBadgeContainer
                    }
                    val badgeContent = if (isBase) {
                        langPalette.onBaseBadgeContainer
                    } else {
                        langPalette.onTargetBadgeContainer
                    }
                    Surface(
                        color = badgeContainer,
                        contentColor = badgeContent,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = languageTag.value.lowercase(Locale.ROOT),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = displayText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = textColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (isPlaying) {
                Icon(
                    imageVector = ChunkFlowIcons.AudioFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(16.dp),
                )
            }
            IconButton(
                onClick = onOpenInPlayer,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.ArrowOutward,
                    contentDescription = stringResource(R.string.library_bookmarks_open_in_player_cd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
