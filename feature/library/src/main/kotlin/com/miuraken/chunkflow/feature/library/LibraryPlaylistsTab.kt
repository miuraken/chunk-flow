package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.model.PlaylistId

@Composable
internal fun LibraryPlaylistsTabContent(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activePlaylist = state.activePlaylist
    val playlistEntries = state.playlistEntries

    if (activePlaylist == null) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 88.dp),
        ) {
            itemsIndexed(
                items = playlistEntries,
                key = { _, entry -> "playlist_${entry.id.value}" },
            ) { index, entry ->
                LibraryPlaylistEntryRow(
                    entry = entry,
                    index = index,
                    totalCount = playlistEntries.size,
                    onClick = { onEvent(LibraryEvent.SelectPlaylist(entry.id)) },
                    onMove = { from, to -> onEvent(LibraryEvent.MovePlaylist(from, to)) },
                    onDeleteClick = if (!entry.isBuiltIn) {
                        { onEvent(LibraryEvent.DeletePlaylist(entry.id)) }
                    } else {
                        null
                    },
                )
                if (index < playlistEntries.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    } else {
        val playlistItems = activePlaylist.items
        val activeTitle = playlistEntryTitle(activePlaylist)
        val activeIcon = playlistEntryIcon(activePlaylist)
        Column(modifier = modifier.fillMaxSize()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEvent(LibraryEvent.SelectPlaylist(null)) }
                        .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { onEvent(LibraryEvent.SelectPlaylist(null)) }) {
                        Icon(
                            imageVector = ChunkFlowIcons.ArrowBack,
                            contentDescription = stringResource(R.string.library_navigate_up),
                        )
                    }
                    Icon(
                        imageVector = activeIcon,
                        contentDescription = null,
                        tint = if (activePlaylist.builtInCategory == LibraryPlaylistCategory.FAVORITES) {
                            FavoriteStarColor
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = activeTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.library_directory_item_count, playlistItems.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (playlistItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.library_playlist_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(
                        items = playlistItems,
                        key = { _, itemModel -> "playlist_item_${itemModel.item.id.value}" },
                    ) { index, itemModel ->
                        val isFirstFile = index == 0
                        val isLastFile = index == playlistItems.lastIndex
                        val isSelected = itemModel.item.id in state.selectedItemIds
                        val shape = when {
                            isFirstFile && isLastFile -> RoundedCornerShape(12.dp)
                            isFirstFile -> RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                            isLastFile -> RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                            else -> RectangleShape
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            shape = shape,
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                LibraryItemRow(
                                    itemModel = itemModel,
                                    isSelectionMode = state.isSelectionMode,
                                    isSelected = isSelected,
                                    onClick = {
                                        if (state.isSelectionMode) {
                                            onEvent(LibraryEvent.ToggleItemSelection(itemModel.item.id))
                                        } else {
                                            onEvent(LibraryEvent.OpenItem(itemModel.item.id))
                                        }
                                    },
                                    onLongClick = {
                                        onEvent(LibraryEvent.ToggleItemSelection(itemModel.item.id))
                                    },
                                    onEditMetadataClick = {
                                        onEvent(LibraryEvent.RequestEditMetadata(itemModel.item))
                                    },
                                    onDeleteClick = {
                                        onEvent(LibraryEvent.RequestDelete(itemModel.item))
                                    },
                                    isFavorite = itemModel.item.id in state.favoriteItemIds,
                                    onToggleFavoriteClick = {
                                        onEvent(LibraryEvent.ToggleFavorite(itemModel.item.id))
                                    },
                                    onRemoveFromPlaylistClick = if (!activePlaylist.isBuiltIn && !state.isSelectionMode) {
                                        {
                                            onEvent(
                                                LibraryEvent.RemoveItemFromPlaylist(
                                                    playlistId = activePlaylist.id,
                                                    mediaId = itemModel.item.id,
                                                ),
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    dragHandle = if (!state.isSelectionMode && playlistItems.size > 1) {
                                        {
                                            ReorderDragHandle(
                                                currentIndex = index,
                                                totalCount = playlistItems.size,
                                                onMove = { from, to ->
                                                    onEvent(
                                                        LibraryEvent.MovePlaylistItem(
                                                            playlistId = activePlaylist.id,
                                                            fromIndex = from,
                                                            toIndex = to,
                                                        ),
                                                    )
                                                },
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                )
                                if (!isLastFile) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryPlaylistEntryRow(
    entry: LibraryPlaylistEntryUiModel,
    index: Int,
    totalCount: Int,
    onClick: () -> Unit,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
    onDeleteClick: (() -> Unit)? = null,
) {
    val title = playlistEntryTitle(entry)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReorderDragHandle(
            currentIndex = index,
            totalCount = totalCount,
            onMove = onMove,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Surface(
            modifier = Modifier.size(52.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = playlistEntryIcon(entry),
                    contentDescription = null,
                    tint = if (entry.builtInCategory == LibraryPlaylistCategory.FAVORITES) {
                        FavoriteStarColor
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.library_directory_item_count, entry.items.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onDeleteClick != null) {
            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.Delete,
                    contentDescription = stringResource(R.string.library_playlist_delete_cd, title),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        } else {
            Icon(
                imageVector = ChunkFlowIcons.MoreHoriz,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun playlistEntryTitle(entry: LibraryPlaylistEntryUiModel): String {
    val category = entry.builtInCategory
    return if (category != null) {
        playlistCategoryTitle(category)
    } else {
        entry.name
    }
}

private fun playlistEntryIcon(entry: LibraryPlaylistEntryUiModel): ImageVector {
    val category = entry.builtInCategory
    return if (category != null) {
        playlistCategoryIcon(category)
    } else {
        ChunkFlowIcons.QueueMusic
    }
}

@Composable
private fun playlistCategoryTitle(category: LibraryPlaylistCategory): String = when (category) {
    LibraryPlaylistCategory.RECENTLY_OPENED -> stringResource(R.string.library_playlist_recently_opened)
    LibraryPlaylistCategory.FREQUENTLY_ACCESSED -> stringResource(R.string.library_playlist_frequently_accessed)
    LibraryPlaylistCategory.FAVORITES -> stringResource(R.string.library_playlist_favorites)
    LibraryPlaylistCategory.UP_NEXT_QUEUE -> stringResource(R.string.library_playlist_up_next_queue)
}

private fun playlistCategoryIcon(category: LibraryPlaylistCategory): ImageVector = when (category) {
    LibraryPlaylistCategory.RECENTLY_OPENED -> ChunkFlowIcons.Schedule
    LibraryPlaylistCategory.FREQUENTLY_ACCESSED -> ChunkFlowIcons.TrendingUp
    LibraryPlaylistCategory.FAVORITES -> ChunkFlowIcons.Favorite
    LibraryPlaylistCategory.UP_NEXT_QUEUE -> ChunkFlowIcons.QueueMusic
}

@Composable
internal fun CreatePlaylistDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.library_playlist_create_dialog_title))
        },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = {
                    Text(text = stringResource(R.string.library_playlist_create_name_label))
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(text = stringResource(R.string.library_playlist_create_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.library_edit_cancel))
            }
        },
    )
}

@Composable
internal fun AddToPlaylistDialog(
    userPlaylists: List<LibraryPlaylistEntryUiModel>,
    onSelectPlaylist: (PlaylistId) -> Unit,
    onCreateAndAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var isCreatingNew by rememberSaveable { mutableStateOf(userPlaylists.isEmpty()) }
    var newPlaylistName by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(
                    if (isCreatingNew) {
                        R.string.library_playlist_create_dialog_title
                    } else {
                        R.string.library_playlist_add_dialog_title
                    },
                ),
            )
        },
        text = {
            if (isCreatingNew) {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = {
                        Text(text = stringResource(R.string.library_playlist_create_name_label))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    userPlaylists.forEach { playlist ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectPlaylist(playlist.id) },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = ChunkFlowIcons.QueueMusic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(
                                        R.string.library_directory_item_count,
                                        playlist.items.size,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedButton(
                        onClick = { isCreatingNew = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.library_playlist_add_new_button))
                    }
                }
            }
        },
        confirmButton = {
            if (isCreatingNew) {
                TextButton(
                    onClick = { onCreateAndAdd(newPlaylistName.trim()) },
                    enabled = newPlaylistName.isNotBlank(),
                ) {
                    Text(text = stringResource(R.string.library_playlist_create_confirm))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.library_edit_cancel))
            }
        },
    )
}
