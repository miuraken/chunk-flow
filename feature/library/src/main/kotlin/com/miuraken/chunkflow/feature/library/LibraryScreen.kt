package com.miuraken.chunkflow.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.data.library.ImportError
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.testing.fixture.SampleLibraries
import com.miuraken.chunkflow.core.testing.fixture.SampleLibraryPreviewParameterProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import java.util.Locale

/**
 * ライブラリ画面の Route。画面遷移のコールバックと SAF ピッカーを扱い、[LibraryScreen] に状態を渡す。
 */
@Composable
fun LibraryRoute(
    onOpenPlayer: (MediaId) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.onEvent(LibraryEvent.ImportUris(uris))
        }
    }

    LibraryScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                LibraryEvent.RequestImport -> audioPickerLauncher.launch(arrayOf("audio/*"))
                is LibraryEvent.OpenItem -> onOpenPlayer(event.id)
                LibraryEvent.OpenSettings -> onOpenSettings()
                else -> viewModel.onEvent(event)
            }
        },
        modifier = modifier,
    )
}

/**
 * ライブラリ画面（状態を受け取って描くだけの Composable。§7.5.1、§10.5.1）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.library_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    FilledTonalButton(
                        onClick = { onEvent(LibraryEvent.RequestImport) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.library_import_button),
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = { onEvent(LibraryEvent.OpenSettings) }) {
                        Text(
                            text = stringResource(R.string.library_open_settings),
                            maxLines = 1,
                        )
                    }
                },
            )
        },
        bottomBar = {
            state.miniPlayer?.let { miniPlayer ->
                MiniPlayerBar(
                    miniPlayer = miniPlayer,
                    onOpenPlayer = { onEvent(LibraryEvent.OpenItem(miniPlayer.mediaId)) },
                    onTogglePlayPause = { onEvent(LibraryEvent.ToggleMiniPlayerPlayPause) },
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            ImportStatusBanner(
                importState = state.importState,
                onDismiss = { onEvent(LibraryEvent.DismissImportStatus) },
            )

            if (state.items.isEmpty()) {
                LibraryEmptyContent(
                    onImportClick = { onEvent(LibraryEvent.RequestImport) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(state.items, key = { it.item.id.value }) { itemModel ->
                        LibraryItemRow(
                            itemModel = itemModel,
                            onClick = { onEvent(LibraryEvent.OpenItem(itemModel.item.id)) },
                            onDeleteClick = { onEvent(LibraryEvent.RequestDelete(itemModel.item)) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    state.pendingDeleteItem?.let { itemToDelete ->
        AlertDialog(
            onDismissRequest = { onEvent(LibraryEvent.CancelDelete) },
            title = { Text(text = stringResource(R.string.library_delete_dialog_title)) },
            text = {
                Text(text = stringResource(R.string.library_delete_dialog_message, itemToDelete.title))
            },
            confirmButton = {
                TextButton(onClick = { onEvent(LibraryEvent.ConfirmDelete) }) {
                    Text(
                        text = stringResource(R.string.library_delete_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { onEvent(LibraryEvent.CancelDelete) }) {
                    Text(text = stringResource(R.string.library_delete_cancel))
                }
            },
        )
    }
}

@Composable
private fun ImportStatusBanner(
    importState: LibraryImportUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (importState) {
        LibraryImportUiState.Idle -> Unit

        is LibraryImportUiState.Copying -> {
            Surface(
                modifier = modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.library_importing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val fraction = importState.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        is LibraryImportUiState.Done -> {
            Card(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.library_import_done,
                            importState.addedCount,
                            importState.duplicates,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = ChunkFlowIcons.Close,
                            contentDescription = stringResource(R.string.library_dismiss_banner),
                        )
                    }
                }
            }
        }

        is LibraryImportUiState.Error -> {
            val errorText = when (importState.error) {
                ImportError.UNSUPPORTED_FORMAT -> stringResource(R.string.library_import_error_unsupported)
                ImportError.TOO_LARGE -> stringResource(R.string.library_import_error_too_large)
                ImportError.NO_SPACE -> stringResource(R.string.library_import_error_no_space)
                ImportError.IO -> stringResource(R.string.library_import_error_io)
                ImportError.CANCELLED -> stringResource(R.string.library_import_error_cancelled)
            }
            Card(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = errorText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = ChunkFlowIcons.Close,
                            contentDescription = stringResource(R.string.library_dismiss_banner),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryEmptyContent(
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = ChunkFlowIcons.AudioFile,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.library_empty),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.library_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onImportClick) {
            Icon(
                imageVector = ChunkFlowIcons.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.library_empty_import_action))
        }
    }
}

@Composable
private fun LibraryItemRow(
    itemModel: LibraryItemUiModel,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = itemModel.item
    val formattedDuration = formatDurationMs(item.durationMs)
    val deleteDescription = stringResource(R.string.library_delete_content_description, item.title)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LanguagePairBadge(pair = item.languagePair)
                item.course?.let { course ->
                    Text(
                        text = course,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                Text(
                    text = formattedDuration,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (itemModel.positionMs > 0L) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { itemModel.progressFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(2.dp))
                val resumePositionText = stringResource(
                    R.string.library_item_resume_position,
                    formatDurationMs(itemModel.positionMs),
                    formattedDuration,
                )
                val speedSuffix = if (itemModel.speed != 1.0f) {
                    " · ${formatSpeed(itemModel.speed)}"
                } else {
                    ""
                }
                Text(
                    text = resumePositionText + speedSuffix,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        IconButton(onClick = onDeleteClick) {
            Icon(
                imageVector = ChunkFlowIcons.Delete,
                contentDescription = deleteDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LanguagePairBadge(
    pair: LanguagePair,
    modifier: Modifier = Modifier,
) {
    val targetUpper = pair.target.value.uppercase(Locale.ROOT)
    val label = pair.base?.value?.uppercase(Locale.ROOT)?.let { baseUpper ->
        "$baseUpper → $targetUpper"
    } ?: targetUpper

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun MiniPlayerBar(
    miniPlayer: MiniPlayerUiState,
    onOpenPlayer: () -> Unit,
    onTogglePlayPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val openPlayerDescription = stringResource(R.string.library_mini_player_open, miniPlayer.title)
    val playPauseDescription = if (miniPlayer.isPlaying) {
        stringResource(R.string.library_mini_player_pause)
    } else {
        stringResource(R.string.library_mini_player_play)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPlayer)
                .semantics { contentDescription = openPlayerDescription }
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ChunkFlowIcons.AudioFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = miniPlayer.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatDurationMs(miniPlayer.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    imageVector = if (miniPlayer.isPlaying) {
                        ChunkFlowIcons.Pause
                    } else {
                        ChunkFlowIcons.Play
                    },
                    contentDescription = playPauseDescription,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun LibraryScreenPreview(
    @PreviewParameter(SampleLibraryPreviewParameterProvider::class) items: List<AudioItem>,
) {
    val uiItems = items.mapIndexed { index, audioItem ->
        LibraryItemUiModel(
            item = audioItem,
            positionMs = if (index == 0) 12_000L else 0L,
            speed = if (index == 0) 1.25f else 1.0f,
        )
    }
    val miniPlayer = items.firstOrNull()?.let { first ->
        MiniPlayerUiState(
            mediaId = first.id,
            title = first.title,
            isPlaying = true,
            durationMs = first.durationMs,
        )
    }
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = uiItems,
                miniPlayer = miniPlayer,
            ),
            onEvent = {},
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun LibraryScreenImportErrorPreview() {
    val first = SampleMaterials.jaKo.item
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = listOf(LibraryItemUiModel(item = first, positionMs = 15_000L, speed = 1.0f)),
                importState = LibraryImportUiState.Error(ImportError.UNSUPPORTED_FORMAT),
            ),
            onEvent = {},
        )
    }
}
