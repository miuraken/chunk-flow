package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.model.LanguageTag
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryFilesTabContent(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredFiles = state.filteredAllFiles
    val targetLanguages = state.availableTargetLanguages
    var sortMenuExpanded by remember { mutableStateOf(false) }

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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = state.fileSearchQuery,
                onValueChange = { onEvent(LibraryEvent.UpdateFileSearchQuery(it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(text = stringResource(R.string.library_files_search_placeholder))
                },
                leadingIcon = {
                    Icon(
                        imageVector = ChunkFlowIcons.Search,
                        contentDescription = null,
                    )
                },
                trailingIcon = {
                    if (state.fileSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { onEvent(LibraryEvent.UpdateFileSearchQuery("")) }) {
                            Icon(
                                imageVector = ChunkFlowIcons.Close,
                                contentDescription = stringResource(R.string.library_selection_clear),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.library_files_sort_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ExposedDropdownMenuBox(
                    expanded = sortMenuExpanded,
                    onExpandedChange = { sortMenuExpanded = it },
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = sortOptionLabel(state.fileSortOption, state.fileSortAscending),
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = sortMenuExpanded)
                        },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                    ExposedDropdownMenu(
                        expanded = sortMenuExpanded,
                        onDismissRequest = { sortMenuExpanded = false },
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

            if (targetLanguages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val activeFilterCode = state.fileTargetLanguageFilter?.value?.lowercase(Locale.ROOT)
                    FilterChip(
                        selected = activeFilterCode == null,
                        onClick = { onEvent(LibraryEvent.SelectFileTargetLanguageFilter(null)) },
                        label = { Text(text = stringResource(R.string.library_files_filter_all_languages)) },
                    )
                    targetLanguages.forEach { lang ->
                        val code = lang.value.lowercase(Locale.ROOT)
                        val isSelected = activeFilterCode == code
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                onEvent(
                                    LibraryEvent.SelectFileTargetLanguageFilter(
                                        if (isSelected) null else LanguageTag(code),
                                    ),
                                )
                            },
                            label = { Text(text = code) },
                        )
                    }
                }
            }
        }

        if (!state.isLoading && filteredFiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.library_files_no_matching_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
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
