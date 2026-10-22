package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowFolderPalette
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.MediaId
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LibraryFoldersTabContent(
    state: LibraryUiState,
    listState: LazyListState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
    isSearchExpanded: Boolean = false,
    onSearchExpandedChange: (Boolean) -> Unit = {},
) {
    val currentDirs = state.currentDirectories
    val currentFiles = state.currentFiles
    val currentFolderPath = state.currentDirectoryPath?.trim('/')?.takeIf { it.isNotBlank() }
    val isSearchActive = state.fileSearchQuery.trim().isNotEmpty()
    val showSearchBox = isSearchExpanded || state.fileSearchQuery.isNotEmpty()
    val showSortControl = currentFolderPath != null || isSearchActive || currentFiles.isNotEmpty()
    val displayTags = state.tagsSortedByItemCount
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val activeFilterCode = state.fileTargetLanguageFilter?.value?.lowercase(Locale.ROOT)
    val allLanguagesLabel = stringResource(R.string.library_files_filter_all_languages)
    val currentLanguageFilterLabel = activeFilterCode ?: allLanguagesLabel

    LaunchedEffect(isSearchExpanded) {
        if (isSearchExpanded) {
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    @Composable
    fun sortOptionLabel(option: LibraryFileSortOption, ascending: Boolean): String {
        val optionName = stringResource(
            when (option) {
                LibraryFileSortOption.NAME -> R.string.library_files_sort_name
                LibraryFileSortOption.ACCESS_TIME -> R.string.library_files_sort_access_time
                LibraryFileSortOption.ADDED_TIME -> R.string.library_files_sort_added_time
                LibraryFileSortOption.COMPLETION -> R.string.library_files_sort_completion
            },
        )
        val orderName = stringResource(
            if (ascending) R.string.library_files_sort_asc else R.string.library_files_sort_desc,
        )
        return stringResource(R.string.library_files_sort_option_format, optionName, orderName)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 最上部の検索ボックス（虫眼鏡アイコン押下時または検索キーワード入力時に表示）
        if (showSearchBox) {
            OutlinedTextField(
                value = state.fileSearchQuery,
                onValueChange = { onEvent(LibraryEvent.UpdateFileSearchQuery(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .focusRequester(searchFocusRequester),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = MaterialTheme.shapes.small,
                placeholder = {
                    Text(
                        text = stringResource(R.string.library_files_search_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = ChunkFlowIcons.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    IconButton(
                        onClick = {
                            if (state.fileSearchQuery.isNotEmpty()) {
                                onEvent(LibraryEvent.UpdateFileSearchQuery(""))
                            }
                            onSearchExpandedChange(false)
                        },
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Close,
                            contentDescription = stringResource(R.string.library_selection_clear),
                        )
                    }
                },
            )
        }

        // 言語絞り込み ＆ 並び替え行（ルートのフォルダだけのビューでは並び替えは非表示）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Surface(
                    onClick = { languageMenuExpanded = true },
                    modifier = Modifier.height(38.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                R.string.library_files_language_filter_button_format,
                                currentLanguageFilterLabel,
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                DropdownMenu(
                    expanded = languageMenuExpanded,
                    onDismissRequest = { languageMenuExpanded = false },
                    shape = MaterialTheme.shapes.medium,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Column(
                        modifier = Modifier
                            .widthIn(min = 180.dp, max = 260.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LanguageFilterPillChip(
                            label = allLanguagesLabel,
                            selected = activeFilterCode == null,
                            onClick = {
                                languageMenuExpanded = false
                                onEvent(LibraryEvent.SelectFileTargetLanguageFilter(null))
                            },
                        )
                        if (state.availableTargetLanguages.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                state.availableTargetLanguages.forEach { lang ->
                                    val code = lang.value.lowercase(Locale.ROOT)
                                    val isSelected = activeFilterCode == code
                                    LanguageFilterPillChip(
                                        label = code,
                                        selected = isSelected,
                                        onClick = {
                                            languageMenuExpanded = false
                                            onEvent(
                                                LibraryEvent.SelectFileTargetLanguageFilter(
                                                    if (isSelected) null else LanguageTag(code),
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (showSortControl) {
                Box {
                    Surface(
                        onClick = { sortMenuExpanded = true },
                        modifier = Modifier.height(38.dp),
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = sortOptionLabel(state.fileSortOption, state.fileSortAscending),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortMenuExpanded)
                        }
                    }
                    DropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        LibraryFileSortOption.entries.forEach { option ->
                            listOf(true, false).forEach { ascending ->
                                val itemLabel = sortOptionLabel(option, ascending)
                                val isSelected = state.fileSortOption == option &&
                                    state.fileSortAscending == ascending
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = itemLabel,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        sortMenuExpanded = false
                                        onEvent(LibraryEvent.SelectFileSortOption(option, ascending))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // タグでの絞り込み（タグがまだ付けられていない場合は非表示、アイテム数が多い順で1行横スクロール）
        if (displayTags.isNotEmpty()) {
            FileTagFilterRow(
                tags = displayTags,
                selectedTags = state.fileTagFilter,
                onToggleTag = { tag -> onEvent(LibraryEvent.ToggleFileTagFilter(tag)) },
                onClear = { onEvent(LibraryEvent.ClearFileTagFilter) },
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        if (isSearchActive) {
            val filteredFiles = state.filteredAllFiles
            if (!state.isLoading && filteredFiles.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (state.isFileListEmptyBecauseOfTagFilter) {
                                R.string.library_files_no_matching_tag_results
                            } else {
                                R.string.library_files_no_matching_results
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.fileTagFilter.isNotEmpty()) {
                        TextButton(onClick = { onEvent(LibraryEvent.ClearFileTagFilter) }) {
                            Text(text = stringResource(R.string.library_files_tag_filter_clear))
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(
                        items = filteredFiles,
                        key = { _, itemModel -> "search_file_${itemModel.item.id.value}" },
                    ) { index, itemModel ->
                        val isFirstFile = index == 0
                        val isLastFile = index == filteredFiles.lastIndex
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
                        val containerColor by androidx.compose.animation.animateColorAsState(
                            targetValue = if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            label = "searchFileSelectionColor",
                        )
                        Surface(
                            modifier = Modifier
                                .animateItem()
                                .fillMaxWidth(),
                            color = containerColor,
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
            }
        } else {
            if (currentFolderPath != null) {
                val totalItemsInFolder = state
                    .itemsInDirectoryTree(currentFolderPath)
                    .count { state.matchesLanguageAndTagFilter(it) }
                LibraryCurrentFolderHeader(
                    folderPath = currentFolderPath,
                    itemCount = totalItemsInFolder,
                    isRealFolder = state.isRealFolder(currentFolderPath),
                    isSelectionMode = state.isSelectionMode,
                    isSelected = currentFolderPath in state.selectedDirectoryPaths,
                    colorIndex = state.folderColorIndexFor(currentFolderPath),
                    onNavigateUp = { onEvent(LibraryEvent.NavigateUp) },
                    onLongClick = { onEvent(LibraryEvent.ToggleDirectorySelection(currentFolderPath)) },
                    onEditDirectoryMetadata = {
                        onEvent(LibraryEvent.RequestEditDirectoryMetadata(currentFolderPath))
                    },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                )
            } else {
                LibraryRootFolderBreadcrumbHeader(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                )
            }

            val hasActiveFilter = state.fileTargetLanguageFilter != null || state.fileTagFilter.isNotEmpty()
            if (!state.isLoading && hasActiveFilter && currentDirs.isEmpty() && currentFiles.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (state.fileTagFilter.isNotEmpty()) {
                                R.string.library_files_no_matching_tag_results
                            } else {
                                R.string.library_files_no_matching_results
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.fileTagFilter.isNotEmpty()) {
                        TextButton(onClick = { onEvent(LibraryEvent.ClearFileTagFilter) }) {
                            Text(text = stringResource(R.string.library_files_tag_filter_clear))
                        }
                    }
                }
            } else {
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
                            modifier = Modifier
                                .animateItem()
                                .padding(
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
                        val containerColor by androidx.compose.animation.animateColorAsState(
                            targetValue = if (isSelected) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                            label = "folderFileSelectionColor",
                        )
                        Surface(
                            modifier = Modifier
                                .animateItem()
                                .fillMaxWidth(),
                            color = containerColor,
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
            }
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
    isSelectionMode: Boolean,
    isSelected: Boolean,
    colorIndex: Int?,
    onNavigateUp: () -> Unit,
    onLongClick: () -> Unit,
    onEditDirectoryMetadata: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hierarchicalTitle = folderPath.replace("/", " / ")
    val navigateUpCd = stringResource(R.string.library_navigate_up)
    val folderPalette = chunkFlowFolderPalette()
    Surface(
        modifier = modifier.fillMaxWidth(),
        // フォルダの色（ADR 0013）で薄く塗る。色がなければ従来どおり透明
        color = if (isSelected) {
            folderPalette.selectedContainerOrNull(colorIndex) ?: MaterialTheme.colorScheme.secondaryContainer
        } else {
            folderPalette.containerOrNull(colorIndex) ?: Color.Transparent
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
            if (!isSelectionMode) {
                IconButton(
                    onClick = onEditDirectoryMetadata,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Tune,
                        contentDescription = stringResource(R.string.library_edit_directory_metadata_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
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
    val folderPalette = chunkFlowFolderPalette()
    // フォルダの色（ADR 0013）で薄く塗る。選択中は同じ色相の濃い色にする（色がなければ従来の色）
    val containerColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            isSelected ->
                folderPalette.selectedContainerOrNull(directory.colorIndex)
                    ?: MaterialTheme.colorScheme.secondaryContainer

            else ->
                folderPalette.containerOrNull(directory.colorIndex)
                    ?: MaterialTheme.colorScheme.surfaceContainer
        },
        label = "directorySelectionColor",
    )
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
                            imageVector = ChunkFlowIcons.Tune,
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

/**
 * 仮想フォルダに付ける「仮想」のバッジ。実フォルダ（[isRealFolder] が `true`）のときは何も表示しない。
 *
 * フォルダの行・開いているフォルダの見出し・フォルダの設定のシートの見出しで共通に使う。
 */
@Composable
internal fun FolderKindBadge(
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
