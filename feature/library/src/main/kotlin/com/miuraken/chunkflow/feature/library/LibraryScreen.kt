package com.miuraken.chunkflow.feature.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.miuraken.chunkflow.core.data.library.SyncError
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.testing.fixture.SampleLibraryPreviewParameterProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import java.util.Locale

private val TARGET_LANGUAGE_PRESETS: List<String> =
    listOf("ko", "zh", "yue", "id", "en", "ja", "hi", "ar")

private val BASE_LANGUAGE_PRESETS: List<String?> =
    listOf(null, "ja", "en", "zh", "ko")

/**
 * ライブラリ画面の Route。画面遷移のコールバックと SAF ピッカー（フォルダ指定・ファイル選択・文字起こし追加）を扱い、[LibraryScreen] に状態を渡す。
 */
@Composable
fun LibraryRoute(
    onOpenPlayer: (MediaId) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            val scanned = scanDocumentTree(context.contentResolver, treeUri)
            if (scanned.documentUris.isNotEmpty()) {
                viewModel.onEvent(
                    LibraryEvent.ImportDirectory(
                        directoryName = scanned.directoryName,
                        uris = scanned.documentUris,
                    ),
                )
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.onEvent(LibraryEvent.ImportUris(uris))
        }
    }

    val transcriptPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.onEvent(LibraryEvent.ImportTranscriptUri(uri))
        }
    }

    LibraryScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                LibraryEvent.RequestImportDirectory -> directoryPickerLauncher.launch(null)

                LibraryEvent.RequestImport -> filePickerLauncher.launch(
                    arrayOf(
                        "audio/*",
                        "application/json",
                        "text/vtt",
                        "application/x-subrip",
                        "text/plain",
                        "*/*",
                    ),
                )

                LibraryEvent.RequestImportTranscript -> transcriptPickerLauncher.launch(
                    arrayOf(
                        "application/json",
                        "text/vtt",
                        "application/x-subrip",
                        "text/plain",
                        "*/*",
                    ),
                )

                is LibraryEvent.OpenItem -> onOpenPlayer(event.id)

                LibraryEvent.OpenSettings -> onOpenSettings()

                else -> viewModel.onEvent(event)
            }
        },
        modifier = modifier,
    )
}

/**
 * ライブラリ画面（ディレクトリ構成・Drive 同期・メタデータ編集に対応し、状態を受け取って描くだけの Composable。§7.5.1、§10.5.1）。
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
                    IconButton(onClick = { onEvent(LibraryEvent.SyncFromDrive) }) {
                        Icon(
                            imageVector = ChunkFlowIcons.Sync,
                            contentDescription = stringResource(R.string.library_drive_sync_button),
                        )
                    }
                    FilledTonalButton(
                        onClick = { onEvent(LibraryEvent.RequestImportDirectory) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.library_import_directory_button),
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
                transcriptImportState = state.transcriptImportState,
                syncState = state.syncState,
                onDismiss = { onEvent(LibraryEvent.DismissImportStatus) },
            )

            if (state.items.isEmpty()) {
                LibraryEmptyContent(
                    onImportDirectoryClick = { onEvent(LibraryEvent.RequestImportDirectory) },
                    onImportFilesClick = { onEvent(LibraryEvent.RequestImport) },
                    onSyncDriveClick = { onEvent(LibraryEvent.SyncFromDrive) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LibraryActionToolbar(
                    onSelectDirectory = { onEvent(LibraryEvent.RequestImportDirectory) },
                    onImportFiles = { onEvent(LibraryEvent.RequestImport) },
                    onImportTranscript = { onEvent(LibraryEvent.RequestImportTranscript) },
                    onSyncFromDrive = { onEvent(LibraryEvent.SyncFromDrive) },
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(
                        items = state.directories,
                        key = { it.name ?: "__uncategorized__" },
                    ) { directory ->
                        LibraryDirectoryCard(
                            directory = directory,
                            onToggleExpand = {
                                onEvent(LibraryEvent.ToggleDirectory(directory.name))
                            },
                            onOpenItem = { mediaId ->
                                onEvent(LibraryEvent.OpenItem(mediaId))
                            },
                            onEditItemMetadata = { item ->
                                onEvent(LibraryEvent.RequestEditMetadata(item))
                            },
                        )
                    }
                }
            }
        }
    }

    state.pendingEditItem?.let { itemToEdit ->
        EditItemMetadataDialog(
            item = itemToEdit,
            onConfirm = { course, languages ->
                onEvent(
                    LibraryEvent.ConfirmEditMetadata(
                        id = itemToEdit.id,
                        course = course,
                        languages = languages,
                    ),
                )
            },
            onDismiss = { onEvent(LibraryEvent.CancelEditMetadata) },
        )
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
private fun LibraryActionToolbar(
    onSelectDirectory: () -> Unit,
    onImportFiles: () -> Unit,
    onImportTranscript: () -> Unit,
    onSyncFromDrive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onSelectDirectory,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.library_import_directory_button),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            OutlinedButton(
                onClick = onImportFiles,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.library_import_button),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            OutlinedButton(
                onClick = onImportTranscript,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.Description,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.library_import_transcript_button),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            OutlinedButton(
                onClick = onSyncFromDrive,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.library_drive_sync_button),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.library_directory_structure_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LibraryDirectoryCard(
    directory: LibraryDirectoryUiModel,
    onToggleExpand: () -> Unit,
    onOpenItem: (MediaId) -> Unit,
    onEditItemMetadata: (AudioItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val directoryTitle = directory.name ?: stringResource(R.string.library_directory_uncategorized)
    val toggleDescription = if (directory.isExpanded) {
        stringResource(R.string.library_directory_collapse)
    } else {
        stringResource(R.string.library_directory_expand)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
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
                        Text(
                            text = stringResource(
                                R.string.library_directory_item_count,
                                directory.items.size,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = directory.transcriptionPathHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (directory.isExpanded) {
                        ChunkFlowIcons.ExpandLess
                    } else {
                        ChunkFlowIcons.ExpandMore
                    },
                    contentDescription = toggleDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (directory.isExpanded) {
                HorizontalDivider()
                directory.items.forEachIndexed { index, itemModel ->
                    LibraryItemRow(
                        itemModel = itemModel,
                        onClick = { onOpenItem(itemModel.item.id) },
                        onEditMetadataClick = { onEditItemMetadata(itemModel.item) },
                    )
                    if (index < directory.items.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportStatusBanner(
    importState: LibraryImportUiState,
    transcriptImportState: TranscriptImportUiState,
    syncState: DriveSyncUiState,
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
            val doneMessage = if (importState.transcriptsAttached > 0) {
                stringResource(
                    R.string.library_import_done_with_transcripts,
                    importState.addedCount,
                    importState.duplicates,
                    importState.transcriptsAttached,
                )
            } else {
                stringResource(
                    R.string.library_import_done,
                    importState.addedCount,
                    importState.duplicates,
                )
            }
            StatusBannerCard(
                message = doneMessage,
                isError = false,
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }

        is LibraryImportUiState.Error -> {
            val errorText = when (importState.error) {
                ImportError.UNSUPPORTED_FORMAT -> stringResource(R.string.library_import_error_unsupported)
                ImportError.TOO_LARGE -> stringResource(R.string.library_import_error_too_large)
                ImportError.NO_SPACE -> stringResource(R.string.library_import_error_no_space)
                ImportError.IO -> stringResource(R.string.library_import_error_io)
                ImportError.CANCELLED -> stringResource(R.string.library_import_error_cancelled)
            }
            StatusBannerCard(
                message = errorText,
                isError = true,
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }
    }

    when (transcriptImportState) {
        TranscriptImportUiState.Idle -> Unit

        is TranscriptImportUiState.Attached -> {
            val message = if (transcriptImportState.durationMismatch) {
                stringResource(
                    R.string.library_transcript_attached_mismatch,
                    transcriptImportState.chunkCount,
                )
            } else {
                stringResource(
                    R.string.library_transcript_attached,
                    transcriptImportState.chunkCount,
                )
            }
            StatusBannerCard(
                message = message,
                isError = false,
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }

        is TranscriptImportUiState.NoMatchingAudio -> {
            val fileName = transcriptImportState.audioFileName
            val message = if (!fileName.isNullOrBlank()) {
                stringResource(R.string.library_transcript_no_matching_audio, fileName)
            } else {
                stringResource(R.string.library_transcript_no_matching_audio_unknown)
            }
            StatusBannerCard(
                message = message,
                isError = true,
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }

        is TranscriptImportUiState.Invalid -> {
            StatusBannerCard(
                message = stringResource(R.string.library_transcript_invalid),
                isError = true,
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }
    }

    when (syncState) {
        DriveSyncUiState.Idle -> Unit

        DriveSyncUiState.Listing -> {
            Surface(
                modifier = modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.library_drive_sync_listing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }

        is DriveSyncUiState.Downloading -> {
            val fraction = if (syncState.total > 0) {
                (syncState.done.toFloat() / syncState.total.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
            Surface(
                modifier = modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(
                            R.string.library_drive_sync_downloading,
                            syncState.done,
                            syncState.total,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        is DriveSyncUiState.Done -> {
            val message = if (syncState.uploadedTranscripts > 0) {
                stringResource(
                    R.string.library_drive_sync_done_with_uploads,
                    syncState.newAudio,
                    syncState.newTranscripts,
                    syncState.uploadedTranscripts,
                )
            } else {
                stringResource(
                    R.string.library_drive_sync_done,
                    syncState.newAudio,
                    syncState.newTranscripts,
                )
            }
            StatusBannerCard(
                message = message,
                isError = false,
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }

        is DriveSyncUiState.Error -> {
            val errorText = when (syncState.error) {
                SyncError.NOT_AUTHORIZED -> stringResource(R.string.library_drive_sync_error_not_authorized)
                SyncError.NETWORK -> stringResource(R.string.library_drive_sync_error_network)
                SyncError.FOLDER_NOT_FOUND -> stringResource(R.string.library_drive_sync_error_folder_not_found)
                SyncError.NO_SPACE -> stringResource(R.string.library_drive_sync_error_no_space)
                SyncError.IO -> stringResource(R.string.library_drive_sync_error_io)
                SyncError.CANCELLED -> stringResource(R.string.library_drive_sync_error_cancelled)
            }
            StatusBannerCard(
                message = errorText,
                isError = true,
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun StatusBannerCard(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
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

@Composable
private fun LibraryEmptyContent(
    onImportDirectoryClick: () -> Unit,
    onImportFilesClick: () -> Unit,
    onSyncDriveClick: () -> Unit,
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
            imageVector = ChunkFlowIcons.Folder,
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
        Button(onClick = onImportDirectoryClick) {
            Icon(
                imageVector = ChunkFlowIcons.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.library_empty_import_directory_action))
        }
        OutlinedButton(onClick = onImportFilesClick) {
            Icon(
                imageVector = ChunkFlowIcons.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.library_empty_import_action))
        }
        OutlinedButton(onClick = onSyncDriveClick) {
            Icon(
                imageVector = ChunkFlowIcons.Sync,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.library_drive_sync_button))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryItemRow(
    itemModel: LibraryItemUiModel,
    onClick: () -> Unit,
    onEditMetadataClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = itemModel.item
    val formattedDuration = formatDurationMs(item.durationMs)
    val editCd = stringResource(R.string.library_edit_metadata_cd)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            LanguagePairBadge(
                pair = item.languagePair,
                modifier = Modifier
                    .clickable(onClick = onEditMetadataClick)
                    .semantics { contentDescription = editCd },
            )
            Text(
                text = formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (itemModel.isAnalyzing) {
                Text(
                    text = stringResource(R.string.library_item_analyzing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else if (itemModel.chunkCount > 0) {
                Text(
                    text = stringResource(R.string.library_item_chunk_count, itemModel.chunkCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            if (itemModel.hasTranscript) {
                Text(
                    text = stringResource(R.string.library_item_transcript_ready),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (itemModel.isAudioMissing) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = stringResource(R.string.library_item_missing_audio),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = itemModel.transcriptRelativePath,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditItemMetadataDialog(
    item: AudioItem,
    onConfirm: (String?, LanguagePair) -> Unit,
    onDismiss: () -> Unit,
) {
    var courseText by remember(item) { mutableStateOf(item.course.orEmpty()) }
    var targetLangText by remember(item) { mutableStateOf(item.languagePair.target.value) }
    var baseLangText by remember(item) { mutableStateOf(item.languagePair.base?.value.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.library_edit_metadata_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = courseText,
                    onValueChange = { courseText = it },
                    label = { Text(text = stringResource(R.string.library_edit_course_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = targetLangText,
                    onValueChange = { targetLangText = it },
                    label = { Text(text = stringResource(R.string.library_edit_target_lang_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TARGET_LANGUAGE_PRESETS.forEach { code ->
                        FilterChip(
                            selected = targetLangText.trim().equals(code, ignoreCase = true),
                            onClick = { targetLangText = code },
                            label = { Text(text = code.uppercase(Locale.ROOT)) },
                        )
                    }
                }

                OutlinedTextField(
                    value = baseLangText,
                    onValueChange = { baseLangText = it },
                    label = { Text(text = stringResource(R.string.library_edit_base_lang_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BASE_LANGUAGE_PRESETS.forEach { code ->
                        val isSelected = if (code == null) {
                            baseLangText.isBlank()
                        } else {
                            baseLangText.trim().equals(code, ignoreCase = true)
                        }
                        val label = code?.uppercase(Locale.ROOT)
                            ?: stringResource(R.string.library_edit_base_lang_none)
                        FilterChip(
                            selected = isSelected,
                            onClick = { baseLangText = code.orEmpty() },
                            label = { Text(text = label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cleanTarget = targetLangText.trim().lowercase(Locale.ROOT).ifBlank { "en" }
                    val cleanBase = baseLangText.trim().lowercase(Locale.ROOT).ifBlank { null }
                    onConfirm(
                        courseText.trim().ifBlank { null },
                        LanguagePair(
                            target = LanguageTag(cleanTarget),
                            base = cleanBase?.let { LanguageTag(it) },
                        ),
                    )
                },
            ) {
                Text(text = stringResource(R.string.library_edit_save))
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
            chunkCount = if (index == 0) 6 else 0,
            hasTranscript = index == 0,
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
                items = listOf(
                    LibraryItemUiModel(
                        item = first,
                        positionMs = 15_000L,
                        speed = 1.0f,
                        chunkCount = 6,
                        hasTranscript = true,
                    ),
                ),
                importState = LibraryImportUiState.Error(ImportError.UNSUPPORTED_FORMAT),
            ),
            onEvent = {},
        )
    }
}
