package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.model.MediaId
import java.util.Locale

@Composable
internal fun LibraryFoldersTabContent(
    state: LibraryUiState,
    listState: LazyListState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDirs = state.currentDirectories
    val currentFiles = state.currentFiles
    val hasRecentItems = state.recentItems.isNotEmpty()
    var isRecentFilesExpanded by rememberSaveable { mutableStateOf(false) }
    val currentFolderPath = state.currentDirectoryPath?.trim('/')?.takeIf { it.isNotBlank() }

    Column(modifier = modifier.fillMaxSize()) {
        if (currentFolderPath != null) {
            val totalItemsInFolder = state.itemsInDirectoryTree(currentFolderPath).size
            LibraryCurrentFolderHeader(
                folderPath = currentFolderPath,
                itemCount = totalItemsInFolder,
                isRealFolder = state.isRealFolder(currentFolderPath),
                isSelected = currentFolderPath in state.selectedDirectoryPaths,
                onNavigateUp = { onEvent(LibraryEvent.NavigateUp) },
                onLongClick = { onEvent(LibraryEvent.ToggleDirectorySelection(currentFolderPath)) },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            )
        } else {
            LibraryRootFolderBreadcrumbHeader(
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            itemsIndexed(
                items = currentDirs,
                key = { _, dir -> "dir_${dir.name.orEmpty()}" },
            ) { index, directory ->
                val dirPath = directory.name.orEmpty()
                val isSelected = dirPath in state.selectedDirectoryPaths
                val showRootDragHandle =
                    currentFolderPath == null && state.isSelectionMode && dirPath.isNotEmpty()
                LibraryDirectoryRow(
                    directory = directory,
                    isSelectionMode = state.isSelectionMode,
                    isSelected = isSelected,
                    onClick = {
                        if (state.isSelectionMode) {
                            onEvent(LibraryEvent.ToggleDirectorySelection(dirPath))
                        } else {
                            onEvent(LibraryEvent.OpenDirectory(dirPath))
                        }
                    },
                    onLongClick = {
                        if (dirPath.isNotEmpty()) {
                            onEvent(LibraryEvent.ToggleDirectorySelection(dirPath))
                        }
                    },
                    onEditDirectoryMetadata = {
                        onEvent(LibraryEvent.RequestEditDirectoryMetadata(directory.name))
                    },
                    dragHandle = if (showRootDragHandle) {
                        {
                            ReorderDragHandle(
                                currentIndex = index,
                                totalCount = currentDirs.size,
                                onMove = { from, to ->
                                    onEvent(LibraryEvent.MoveRootDirectory(from, to))
                                },
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.padding(
                        start = if (showRootDragHandle) 0.dp else 24.dp,
                        bottom = 8.dp,
                    ),
                )
            }
            itemsIndexed(
                items = currentFiles,
                key = { _, itemModel -> "file_${itemModel.item.id.value}" },
            ) { index, itemModel ->
                val isFirstFile = index == 0
                val isLastFile = index == currentFiles.lastIndex
                val isSelected = itemModel.item.id in state.selectedItemIds
                val shape = when {
                    isFirstFile && isLastFile -> MaterialTheme.shapes.medium

                    isFirstFile -> MaterialTheme.shapes.medium.copy(
                        bottomStart = CornerSize(0.dp),
                        bottomEnd = CornerSize(0.dp),
                    )

                    isLastFile -> MaterialTheme.shapes.medium.copy(
                        topStart = CornerSize(0.dp),
                        topEnd = CornerSize(0.dp),
                    )

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
                            isFavorite = itemModel.item.id in state.favoriteItemIds,
                            onToggleFavoriteClick = {
                                onEvent(LibraryEvent.ToggleFavorite(itemModel.item.id))
                            },
                        )
                        if (!isLastFile) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        }
                    }
                }
            }
        }

        if (hasRecentItems) {
            RecentFilesSection(
                recentItems = state.recentItems,
                isExpanded = isRecentFilesExpanded,
                onToggleExpanded = { isRecentFilesExpanded = !isRecentFilesExpanded },
                onOpenItem = { id -> onEvent(LibraryEvent.OpenItem(id)) },
            )
        }
    }
}

@Composable
private fun LibraryRootFolderBreadcrumbHeader(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ChunkFlowIcons.Home,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(2.dp))
        Icon(
            imageVector = ChunkFlowIcons.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCurrentFolderHeader(
    folderPath: String,
    itemCount: Int,
    isRealFolder: Boolean,
    isSelected: Boolean,
    onNavigateUp: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hierarchicalTitle = folderPath.replace("/", " / ")
    val navigateUpCd = stringResource(R.string.library_navigate_up)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onNavigateUp,
                    onLongClick = onLongClick,
                )
                .semantics { contentDescription = navigateUpCd }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ChunkFlowIcons.Home,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(2.dp))
            Icon(
                imageVector = ChunkFlowIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = ChunkFlowIcons.Folder,
                contentDescription = null,
                tint = if (isRealFolder) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.tertiary
                },
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = hierarchicalTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                FolderKindBadge(isRealFolder = isRealFolder)
                Text(
                    text = stringResource(
                        R.string.library_directory_item_count,
                        itemCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryDirectoryRow(
    directory: LibraryDirectoryUiModel,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEditDirectoryMetadata: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val directoryTitle = directory.displayName ?: directory.name.orEmpty()
    val containerColor = when {
        isSelected -> MaterialTheme.colorScheme.secondaryContainer
        directory.isRealFolder -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
        else -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.32f)
    }
    val folderIconTint = if (directory.isRealFolder) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.tertiary
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandle != null) {
            dragHandle()
            Spacer(modifier = Modifier.width(4.dp))
        }
        Surface(
            modifier = Modifier.weight(1f),
            color = containerColor,
            shape = MaterialTheme.shapes.medium,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = onLongClick,
                    )
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onClick() },
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Icon(
                    imageVector = ChunkFlowIcons.Folder,
                    contentDescription = null,
                    tint = folderIconTint,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = directoryTitle,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    FolderKindBadge(isRealFolder = directory.isRealFolder)
                    Text(
                        text = stringResource(
                            R.string.library_directory_item_count,
                            directory.items.size,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!isSelectionMode) {
                    IconButton(
                        onClick = onEditDirectoryMetadata,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Edit,
                            contentDescription = stringResource(R.string.library_edit_directory_metadata_cd),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderKindBadge(
    isRealFolder: Boolean,
    modifier: Modifier = Modifier,
) {
    if (isRealFolder) return
    val label = stringResource(R.string.library_folder_kind_virtual)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun RecentFilesSection(
    recentItems: List<LibraryItemUiModel>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOpenItem: (MediaId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
        ) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.library_recent_files_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (isExpanded) {
                        ChunkFlowIcons.ExpandMore
                    } else {
                        ChunkFlowIcons.ExpandLess
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            if (isExpanded) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 136.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(
                        items = recentItems,
                        key = { _, itemModel -> "recent_${itemModel.item.id.value}" },
                    ) { index, itemModel ->
                        val isFirst = index == 0
                        val isLast = index == recentItems.lastIndex
                        val shape = when {
                            isFirst && isLast -> MaterialTheme.shapes.medium

                            isFirst -> MaterialTheme.shapes.medium.copy(
                                bottomStart = CornerSize(0.dp),
                                bottomEnd = CornerSize(0.dp),
                            )

                            isLast -> MaterialTheme.shapes.medium.copy(
                                topStart = CornerSize(0.dp),
                                topEnd = CornerSize(0.dp),
                            )

                            else -> RectangleShape
                        }
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            shape = shape,
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                RecentFileCompactRow(
                                    itemModel = itemModel,
                                    onClick = { onOpenItem(itemModel.item.id) },
                                )
                                if (!isLast) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
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
private fun RecentFileCompactRow(
    itemModel: LibraryItemUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = itemModel.item
    val langPalette = chunkFlowLanguagePalette()
    val maxPercent = itemModel.maxProgressPercent
    val isCompleted = maxPercent > COMPLETED_PROGRESS_PERCENT_THRESHOLD
    val targetCode = item.languagePair.target.value.lowercase(Locale.ROOT)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ChunkFlowIcons.AudioFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Surface(
                color = langPalette.targetBadgeContainer,
                contentColor = langPalette.onTargetBadgeContainer,
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    text = targetCode,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!item.course.isNullOrBlank()) {
                Text(
                    text = item.course.orEmpty().substringAfterLast('/'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { itemModel.maxProgressFraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp),
            color = if (isCompleted) {
                langPalette.completedProgressIndicator
            } else {
                ProgressIndicatorDefaults.linearColor
            },
        )
    }
}
