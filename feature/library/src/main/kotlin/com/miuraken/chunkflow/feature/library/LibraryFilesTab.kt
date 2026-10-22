package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.model.LanguageTag
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LibraryFilesTabContent(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredFiles = state.filteredAllFiles
    val targetLanguages = state.availableTargetLanguages
    var sortMenuExpanded by remember { mutableStateOf(false) }
    var languageMenuExpanded by remember { mutableStateOf(false) }
    var isSearchExpanded by rememberSaveable { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val showExpandedSearch = isSearchExpanded || state.fileSearchQuery.isNotEmpty()

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showExpandedSearch) {
                OutlinedTextField(
                    value = state.fileSearchQuery,
                    onValueChange = { onEvent(LibraryEvent.UpdateFileSearchQuery(it)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester),
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
                                isSearchExpanded = false
                            },
                        ) {
                            Icon(
                                imageVector = ChunkFlowIcons.Close,
                                contentDescription = stringResource(R.string.library_selection_clear),
                            )
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = MaterialTheme.shapes.small,
                    colors = OutlinedTextFieldDefaults.colors(),
                )
            } else {
                Surface(
                    onClick = { isSearchExpanded = true },
                    modifier = Modifier.height(38.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Search,
                            contentDescription = stringResource(R.string.library_files_search_placeholder),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                val activeFilterCode = state.fileTargetLanguageFilter?.value?.lowercase(Locale.ROOT)
                val allLanguagesLabel = stringResource(R.string.library_files_filter_all_languages)
                val currentLanguageLabel = activeFilterCode ?: allLanguagesLabel

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
                                    currentLanguageLabel,
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
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
                            if (targetLanguages.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    targetLanguages.forEach { lang ->
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

        if (state.allTags.isNotEmpty()) {
            FileTagFilterRow(
                tags = state.allTags,
                selectedTags = state.fileTagFilter,
                onToggleTag = { tag -> onEvent(LibraryEvent.ToggleFileTagFilter(tag)) },
                onClear = { onEvent(LibraryEvent.ClearFileTagFilter) },
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }

        if (!state.isLoading && filteredFiles.isEmpty()) {
            val isEmptyBecauseOfTags = state.isFileListEmptyBecauseOfTagFilter
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(
                        if (isEmptyBecauseOfTags) {
                            R.string.library_files_no_matching_tag_results
                        } else {
                            R.string.library_files_no_matching_results
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                // タグで絞り込んでいるときは、選択を解除する手段を一覧の場所にも出す
                if (state.fileTagFilter.isNotEmpty()) {
                    TextButton(onClick = { onEvent(LibraryEvent.ClearFileTagFilter) }) {
                        Text(text = stringResource(R.string.library_files_tag_filter_clear))
                    }
                }
            }
        } else if (filteredFiles.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                itemsIndexed(
                    items = filteredFiles,
                    key = { _, itemModel -> "all_file_${itemModel.item.id.value}" },
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
                        label = "filesTabSelectionColor",
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
                                onDeleteClick = {
                                    onEvent(LibraryEvent.RequestDelete(itemModel.item))
                                },
                                isFavorite = itemModel.item.id in state.favoriteItemIds,
                                onToggleFavoriteClick = {
                                    onEvent(LibraryEvent.ToggleFavorite(itemModel.item.id))
                                },
                                dragHandle = if (!state.isSelectionMode && filteredFiles.size > 1) {
                                    {
                                        ReorderDragHandle(
                                            currentIndex = index,
                                            totalCount = filteredFiles.size,
                                            onMove = { from, to ->
                                                onEvent(LibraryEvent.MoveFileTabItem(from, to))
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

@Composable
internal fun LanguageFilterPillChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = CircleShape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        border = if (selected) {
            null
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}
