package com.miuraken.chunkflow.feature.library

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.data.library.ImportError
import com.miuraken.chunkflow.core.data.library.SyncError
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.testing.fixture.SampleLibraryPreviewParameterProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val LAST_ACCESSED_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT)

/** この割合（%）を超えて聴いた教材を既習として、進捗バーと再生状況を緑系で表示する。 */
private const val COMPLETED_PROGRESS_PERCENT_THRESHOLD = 95

private fun formatEpochMsLocal(epochMs: Long): String = runCatching {
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(LAST_ACCESSED_FORMATTER)
}.getOrDefault("")

internal data class LanguageDropdownOption(
    val code: String,
    val label: String,
)

internal val ALPHABETICAL_LANGUAGE_OPTIONS: List<LanguageDropdownOption> =
    com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.ITEM_LANGUAGE_ENTRIES.map { entry ->
        LanguageDropdownOption(code = entry.code, label = "${entry.code} - ${entry.japaneseName}")
    }

internal fun normalizeDialogLanguageCode(rawCode: String): String {
    val cleaned = rawCode.trim().lowercase(Locale.ROOT).replace('_', '-')
    return when (cleaned) {
        "zh", "zh-hans", "zh-cn" -> "zh-cn"
        "zh-hant", "zh-tw", "zh-hk" -> "zh-tw"
        else -> cleaned
    }
}

internal fun buildDialogLanguageOptions(
    currentCode: String,
    noneLabel: String? = null,
): List<LanguageDropdownOption> {
    val normalizedCurrent = normalizeDialogLanguageCode(currentCode)
    val baseList = ALPHABETICAL_LANGUAGE_OPTIONS.toMutableList()
    if (normalizedCurrent.isNotBlank() && baseList.none { it.code == normalizedCurrent }) {
        baseList.add(LanguageDropdownOption(normalizedCurrent, normalizedCurrent))
        baseList.sortBy { it.code }
    }
    return if (noneLabel != null) {
        listOf(LanguageDropdownOption("", noneLabel)) + baseList
    } else {
        baseList
    }
}

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
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val scanned = scanDocumentTree(context.contentResolver, treeUri)
            val targetUris = scanned.documentUris.ifEmpty { listOf(treeUri) }
            viewModel.onEvent(
                LibraryEvent.ImportDirectory(
                    directoryName = scanned.directoryName,
                    uris = targetUris,
                ),
            )
        }
    }

    val driveSyncFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            val scanned = scanDocumentTree(context.contentResolver, treeUri)
            viewModel.onEvent(
                LibraryEvent.SelectDriveSyncFolder(
                    directoryName = scanned.directoryName,
                    treeUri = treeUri,
                ),
            )
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.onEvent(LibraryEvent.ImportUris(uris))
        }
    }

    var targetTranscriptItemId by remember { mutableStateOf<MediaId?>(null) }

    val transcriptPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val itemId = targetTranscriptItemId
        targetTranscriptItemId = null
        if (uri != null) {
            if (itemId != null) {
                viewModel.onEvent(LibraryEvent.ImportTranscriptForItem(id = itemId, uri = uri))
            } else {
                viewModel.onEvent(LibraryEvent.ImportTranscriptUri(uri))
            }
        }
    }

    LaunchedEffect(state.syncState) {
        val currentSync = state.syncState
        if (currentSync is DriveSyncUiState.Error && currentSync.error == SyncError.NOT_AUTHORIZED) {
            driveSyncFolderLauncher.launch(null)
        }
    }

    LibraryScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                LibraryEvent.RequestImportDirectory -> directoryPickerLauncher.launch(null)

                LibraryEvent.RequestSelectDriveSyncFolder -> driveSyncFolderLauncher.launch(null)

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

                LibraryEvent.RequestImportTranscript -> {
                    targetTranscriptItemId = null
                    transcriptPickerLauncher.launch(
                        arrayOf(
                            "application/json",
                            "text/vtt",
                            "application/x-subrip",
                            "text/plain",
                            "*/*",
                        ),
                    )
                }

                is LibraryEvent.RequestImportTranscriptForItem -> {
                    targetTranscriptItemId = event.id
                    viewModel.onEvent(LibraryEvent.CancelEditMetadata)
                    transcriptPickerLauncher.launch(
                        arrayOf(
                            "application/json",
                            "text/vtt",
                            "application/x-subrip",
                            "text/plain",
                            "*/*",
                        ),
                    )
                }

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
    val listState = rememberLazyListState()
    var lastScrolledDirectoryPath by remember { mutableStateOf<String?>("__uninitialized__") }

    val currentDirs = state.currentDirectories
    val currentFiles = state.currentFiles

    BackHandler(enabled = state.isSelectionMode || state.currentDirectoryPath != null) {
        onEvent(LibraryEvent.NavigateUp)
    }

    LaunchedEffect(state.currentDirectoryPath, currentFiles.size) {
        if (lastScrolledDirectoryPath != state.currentDirectoryPath &&
            (currentDirs.isNotEmpty() || currentFiles.isNotEmpty())
        ) {
            lastScrolledDirectoryPath = state.currentDirectoryPath
            val targetIdx = state.initialVisibleLazyIndexInCurrentDirectory ?: 0
            listState.scrollToItem(targetIdx)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (state.isSelectionMode) {
                        IconButton(onClick = { onEvent(LibraryEvent.ClearSelection) }) {
                            Icon(
                                imageVector = ChunkFlowIcons.Close,
                                contentDescription = stringResource(R.string.library_selection_clear),
                            )
                        }
                    } else if (state.currentDirectoryPath != null) {
                        IconButton(onClick = { onEvent(LibraryEvent.NavigateUp) }) {
                            Icon(
                                imageVector = ChunkFlowIcons.ArrowBack,
                                contentDescription = stringResource(R.string.library_navigate_up),
                            )
                        }
                    }
                },
                title = {
                    val titleText = when {
                        state.isSelectionMode -> stringResource(
                            R.string.library_selection_count,
                            state.selectedCount,
                        )

                        state.currentDirectoryPath != null -> {
                            state.currentDirectoryPath.substringAfterLast('/')
                        }

                        else -> stringResource(R.string.library_title)
                    }
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (state.isSelectionMode) {
                        FilledTonalButton(
                            onClick = { onEvent(LibraryEvent.RequestDeleteSelected) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Icon(
                                imageVector = ChunkFlowIcons.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(
                                    R.string.library_selection_delete_action,
                                    state.selectedCount,
                                ),
                                maxLines = 1,
                            )
                        }
                    } else {
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
                onSelectDriveSyncFolder = { onEvent(LibraryEvent.RequestSelectDriveSyncFolder) },
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
                    state = listState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    items(
                        items = currentDirs,
                        key = { dir -> "dir_${dir.name.orEmpty()}" },
                    ) { directory ->
                        val dirPath = directory.name.orEmpty()
                        val isSelected = dirPath in state.selectedDirectoryPaths
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
                            onDeleteDirectory = {
                                if (dirPath.isNotEmpty()) {
                                    onEvent(LibraryEvent.RequestDeleteDirectory(dirPath))
                                }
                            },
                            modifier = Modifier.padding(bottom = 8.dp),
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

    state.pendingImportConfig?.let { config ->
        ImportConfigDialog(
            config = config,
            enabledLanguages = state.enabledLanguages,
            onConfirm = { course, languages ->
                onEvent(
                    LibraryEvent.ConfirmImportConfig(
                        course = course,
                        languages = languages,
                    ),
                )
            },
            onDismiss = { onEvent(LibraryEvent.CancelImportConfig) },
        )
    }

    state.pendingEditItem?.let { itemToEdit ->
        EditItemMetadataDialog(
            item = itemToEdit,
            enabledLanguages = state.enabledLanguages,
            onImportTranscript = {
                onEvent(LibraryEvent.RequestImportTranscriptForItem(itemToEdit.id))
            },
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

    state.pendingEditDirectory?.let { directoryToEdit ->
        EditDirectoryMetadataDialog(
            directoryEdit = directoryToEdit,
            enabledLanguages = state.enabledLanguages,
            onConfirm = { newCourse, languages ->
                onEvent(
                    LibraryEvent.ConfirmEditDirectoryMetadata(
                        currentCourse = directoryToEdit.currentCourse,
                        newCourse = newCourse,
                        languages = languages,
                    ),
                )
            },
            onDismiss = { onEvent(LibraryEvent.CancelEditDirectoryMetadata) },
        )
    }

    if (state.hasPendingDeleteConfirmation) {
        val deleteDialogTitle: String
        val deleteDialogMessage: String
        when {
            state.pendingDeleteItem != null -> {
                deleteDialogTitle = stringResource(R.string.library_delete_dialog_title)
                deleteDialogMessage = stringResource(
                    R.string.library_delete_dialog_message,
                    state.pendingDeleteItem.title,
                )
            }

            state.pendingDeleteDirectoryPath != null -> {
                val dirDisplayName = state.pendingDeleteDirectoryPath.substringAfterLast('/')
                val targetCount = state.pendingDeleteTargetItems.size
                deleteDialogTitle = stringResource(R.string.library_delete_directory_dialog_title)
                deleteDialogMessage = stringResource(
                    R.string.library_delete_directory_dialog_message,
                    dirDisplayName,
                    targetCount,
                )
            }

            else -> {
                val selectedCount = state.selectedCount
                val targetCount = state.pendingDeleteTargetItems.size
                deleteDialogTitle = stringResource(R.string.library_delete_selected_dialog_title)
                deleteDialogMessage = stringResource(
                    R.string.library_delete_selected_dialog_message,
                    selectedCount,
                    targetCount,
                )
            }
        }
        AlertDialog(
            onDismissRequest = { onEvent(LibraryEvent.CancelDelete) },
            title = { Text(text = deleteDialogTitle) },
            text = { Text(text = deleteDialogMessage) },
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
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
    onDeleteDirectory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val directoryTitle = directory.displayName ?: directory.name.orEmpty()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = RoundedCornerShape(12.dp),
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
                tint = MaterialTheme.colorScheme.primary,
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
                IconButton(
                    onClick = onDeleteDirectory,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Delete,
                        contentDescription = stringResource(
                            R.string.library_delete_content_description,
                            directoryTitle,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
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
    onSelectDriveSyncFolder: () -> Unit = {},
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
            val needsFolderAction = syncState.error == SyncError.NOT_AUTHORIZED ||
                syncState.error == SyncError.FOLDER_NOT_FOUND
            StatusBannerCard(
                message = errorText,
                isError = true,
                onDismiss = onDismiss,
                actionLabel = if (needsFolderAction) {
                    stringResource(R.string.library_drive_sync_select_folder_action)
                } else {
                    null
                },
                onAction = if (needsFolderAction) onSelectDriveSyncFolder else null,
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
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
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
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(
                        text = actionLabel,
                        color = contentColor,
                    )
                }
            }
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

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun LibraryItemRow(
    itemModel: LibraryItemUiModel,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onEditMetadataClick: () -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = itemModel.item
    val formattedDuration = formatDurationMs(item.durationMs)
    val editCd = stringResource(R.string.library_edit_metadata_cd)
    val deleteCd = stringResource(R.string.library_delete_content_description, item.title)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onClick() },
                )
            }
            Icon(
                imageVector = ChunkFlowIcons.AudioFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!isSelectionMode) {
                IconButton(
                    onClick = onEditMetadataClick,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Edit,
                        contentDescription = editCd,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Delete,
                        contentDescription = deleteCd,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            // 言語タグのタップは行のタップと同じ扱いにする（プレイヤーを開く / 選択を切り替える）。
            // 言語の編集は鉛筆アイコンから行う。
            LanguagePairBadge(pair = item.languagePair)
            if (itemModel.isAnalyzing) {
                Text(
                    text = stringResource(R.string.library_item_analyzing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
            val transcriptStatusText: String
            val transcriptStatusColor = when (itemModel.transcriptStatus) {
                LibraryTranscriptStatus.NONE -> {
                    transcriptStatusText = stringResource(R.string.library_item_transcript_none)
                    MaterialTheme.colorScheme.onSurfaceVariant
                }

                LibraryTranscriptStatus.PARTIAL -> {
                    transcriptStatusText = stringResource(
                        R.string.library_item_transcript_partial,
                        itemModel.transcriptProgressPercent,
                    )
                    MaterialTheme.colorScheme.secondary
                }

                LibraryTranscriptStatus.COMPLETED -> {
                    transcriptStatusText = stringResource(R.string.library_item_transcript_completed)
                    MaterialTheme.colorScheme.primary
                }
            }
            Text(
                text = transcriptStatusText,
                style = MaterialTheme.typography.labelSmall,
                color = transcriptStatusColor,
            )
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
        Spacer(modifier = Modifier.height(6.dp))
        val langPalette = chunkFlowLanguagePalette()
        val maxPercent = itemModel.maxProgressPercent
        // 既習（95% 超）の教材は、進捗バーを緑系にして一目でわかるようにする。
        val isCompleted = maxPercent > COMPLETED_PROGRESS_PERCENT_THRESHOLD
        LinearProgressIndicator(
            progress = { itemModel.maxProgressFraction },
            modifier = Modifier.fillMaxWidth(),
            color = if (isCompleted) {
                langPalette.completedProgressIndicator
            } else {
                ProgressIndicatorDefaults.linearColor
            },
        )
        Spacer(modifier = Modifier.height(2.dp))
        val hasProgress = itemModel.maxPositionMs > 0L || itemModel.positionMs > 0L
        val durationProgressText = if (hasProgress) {
            val baseResume = stringResource(
                R.string.library_item_resume_position,
                formatDurationMs(itemModel.positionMs),
                formattedDuration,
                maxPercent,
            )
            val speedSuffix = if (itemModel.speed != 1.0f) {
                " · ${formatSpeed(itemModel.speed)}"
            } else {
                ""
            }
            baseResume + speedSuffix
        } else {
            stringResource(
                R.string.library_item_duration_progress,
                formatDurationMs(0L),
                formattedDuration,
            )
        }
        val lastAccessedText = itemModel.lastAccessedAtEpochMs?.let { epochMs ->
            val formatted = formatEpochMsLocal(epochMs)
            if (formatted.isNotEmpty()) {
                stringResource(R.string.library_item_last_accessed, formatted)
            } else {
                null
            }
        }
        val progressStatusColor = when {
            isCompleted -> langPalette.completedProgressText
            hasProgress || itemModel.lastAccessedAtEpochMs != null -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        // 「• 最終アクセス: …」の部分は既習でも緑にせず、いつも同じ色にする。
        val lastAccessedColor = MaterialTheme.colorScheme.primary
        val statusText = buildAnnotatedString {
            withStyle(SpanStyle(color = progressStatusColor)) {
                append(durationProgressText)
            }
            if (lastAccessedText != null) {
                withStyle(SpanStyle(color = lastAccessedColor)) {
                    append("  •  ")
                    append(lastAccessedText)
                }
            }
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePairDropdownSection(
    targetLangCode: String,
    baseLangCode: String,
    onTargetLangChange: (String) -> Unit,
    onBaseLangChange: (String) -> Unit,
) {
    val langPalette = chunkFlowLanguagePalette()
    val noneLabel = stringResource(R.string.library_edit_base_lang_none)

    val normalizedTarget = normalizeDialogLanguageCode(targetLangCode)
    val normalizedBase = normalizeDialogLanguageCode(baseLangCode)

    val targetOptions = remember(normalizedTarget) {
        buildDialogLanguageOptions(currentCode = normalizedTarget, noneLabel = null)
    }
    val baseOptions = remember(normalizedBase, noneLabel) {
        buildDialogLanguageOptions(currentCode = normalizedBase, noneLabel = noneLabel)
    }

    val selectedTargetLabel = targetOptions.firstOrNull { it.code == normalizedTarget }?.label
        ?: normalizedTarget.ifBlank { "en" }
    val selectedBaseLabel = baseOptions.firstOrNull { it.code == normalizedBase }?.label
        ?: noneLabel

    var targetExpanded by remember { mutableStateOf(false) }
    var baseExpanded by remember { mutableStateOf(false) }

    Surface(
        color = langPalette.targetRowBackground,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ExposedDropdownMenuBox(
                expanded = targetExpanded,
                onExpandedChange = { targetExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = selectedTargetLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.library_edit_target_lang_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetExpanded)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = langPalette.onTargetBadgeContainer,
                        focusedLabelColor = langPalette.onTargetBadgeContainer,
                    ),
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = targetExpanded,
                    onDismissRequest = { targetExpanded = false },
                ) {
                    targetOptions.forEach { option ->
                        val isSelected = option.code == normalizedTarget
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.label,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                targetExpanded = false
                                onTargetLangChange(option.code)
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }
        }
    }

    Surface(
        color = langPalette.baseRowBackground,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ExposedDropdownMenuBox(
                expanded = baseExpanded,
                onExpandedChange = { baseExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = selectedBaseLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.library_edit_base_lang_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = baseExpanded)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = langPalette.onBaseBadgeContainer,
                        focusedLabelColor = langPalette.onBaseBadgeContainer,
                    ),
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = baseExpanded,
                    onDismissRequest = { baseExpanded = false },
                ) {
                    baseOptions.forEach { option ->
                        val isSelected = option.code == normalizedBase
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.label,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                baseExpanded = false
                                onBaseLangChange(option.code)
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportConfigDialog(
    config: PendingImportConfig,
    @Suppress("UNUSED_PARAMETER") enabledLanguages: List<LanguageTag>,
    onConfirm: (String?, LanguagePair) -> Unit,
    onDismiss: () -> Unit,
) {
    var courseText by remember(config) { mutableStateOf(config.initialCourse.orEmpty()) }
    var targetLangText by remember(config) {
        mutableStateOf(normalizeDialogLanguageCode(config.initialLanguages.target.value))
    }
    var baseLangText by remember(config) {
        mutableStateOf(normalizeDialogLanguageCode(config.initialLanguages.base?.value.orEmpty()))
    }

    val dialogTitle = if (config.isDriveSyncFolder) {
        stringResource(R.string.library_drive_sync_config_title)
    } else {
        stringResource(R.string.library_import_config_title)
    }
    val dialogHint = if (config.isDriveSyncFolder) {
        stringResource(R.string.library_drive_sync_config_hint)
    } else {
        stringResource(R.string.library_import_config_hint, config.uris.size)
    }
    val confirmLabel = if (config.isDriveSyncFolder) {
        stringResource(R.string.library_drive_sync_config_confirm)
    } else {
        stringResource(R.string.library_import_config_confirm)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = dialogTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = dialogHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = courseText,
                    onValueChange = { courseText = it },
                    label = { Text(text = stringResource(R.string.library_edit_course_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                LanguagePairDropdownSection(
                    targetLangCode = targetLangText,
                    baseLangCode = baseLangText,
                    onTargetLangChange = { targetLangText = it },
                    onBaseLangChange = { baseLangText = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanTarget = normalizeDialogLanguageCode(targetLangText).ifBlank { "en" }
                    val cleanBase = normalizeDialogLanguageCode(baseLangText).ifBlank { null }
                    onConfirm(
                        courseText.trim().ifBlank { null },
                        LanguagePair(
                            target = LanguageTag(cleanTarget),
                            base = cleanBase?.let { LanguageTag(it) },
                        ),
                    )
                },
            ) {
                Text(text = confirmLabel)
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
private fun EditItemMetadataDialog(
    item: AudioItem,
    @Suppress("UNUSED_PARAMETER") enabledLanguages: List<LanguageTag>,
    onImportTranscript: () -> Unit,
    onConfirm: (String?, LanguagePair) -> Unit,
    onDismiss: () -> Unit,
) {
    var courseText by remember(item) { mutableStateOf(item.course.orEmpty()) }
    var targetLangText by remember(item) {
        mutableStateOf(normalizeDialogLanguageCode(item.languagePair.target.value))
    }
    var baseLangText by remember(item) {
        mutableStateOf(normalizeDialogLanguageCode(item.languagePair.base?.value.orEmpty()))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
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

                LanguagePairDropdownSection(
                    targetLangCode = targetLangText,
                    baseLangCode = baseLangText,
                    onTargetLangChange = { targetLangText = it },
                    onBaseLangChange = { baseLangText = it },
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                Text(
                    text = stringResource(R.string.library_edit_transcript_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                FilledTonalButton(
                    onClick = onImportTranscript,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Description,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.library_edit_import_transcript_button))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cleanTarget = normalizeDialogLanguageCode(targetLangText).ifBlank { "en" }
                    val cleanBase = normalizeDialogLanguageCode(baseLangText).ifBlank { null }
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
private fun EditDirectoryMetadataDialog(
    directoryEdit: PendingDirectoryEdit,
    @Suppress("UNUSED_PARAMETER") enabledLanguages: List<LanguageTag>,
    onConfirm: (String?, LanguagePair) -> Unit,
    onDismiss: () -> Unit,
) {
    var courseText by remember(directoryEdit) {
        mutableStateOf(directoryEdit.currentCourse?.substringAfterLast('/').orEmpty())
    }
    var targetLangText by remember(directoryEdit) {
        mutableStateOf(normalizeDialogLanguageCode(directoryEdit.initialLanguages.target.value))
    }
    var baseLangText by remember(directoryEdit) {
        mutableStateOf(normalizeDialogLanguageCode(directoryEdit.initialLanguages.base?.value.orEmpty()))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
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

                LanguagePairDropdownSection(
                    targetLangCode = targetLangText,
                    baseLangCode = baseLangText,
                    onTargetLangChange = { targetLangText = it },
                    onBaseLangChange = { baseLangText = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanTarget = normalizeDialogLanguageCode(targetLangText).ifBlank { "en" }
                    val cleanBase = normalizeDialogLanguageCode(baseLangText).ifBlank { null }
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
    val langPalette = chunkFlowLanguagePalette()
    val targetUpper = pair.target.value.uppercase(Locale.ROOT)
    val baseUpper = pair.base?.value?.uppercase(Locale.ROOT)

    if (baseUpper != null) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                color = langPalette.baseBadgeContainer,
                contentColor = langPalette.onBaseBadgeContainer,
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    text = baseUpper,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Text(
                text = "→",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = langPalette.targetBadgeContainer,
                contentColor = langPalette.onTargetBadgeContainer,
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    text = targetUpper,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
    } else {
        Surface(
            modifier = modifier,
            color = langPalette.targetBadgeContainer,
            contentColor = langPalette.onTargetBadgeContainer,
            shape = MaterialTheme.shapes.extraSmall,
        ) {
            Text(
                text = targetUpper,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
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
            isListened = index == 0,
            lastAccessedAtEpochMs = if (index == 0) 1_760_000_000_000L else null,
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

/** フォルダを開いた状態。95% を超えて聴いた既習の教材は、進捗バーと再生状況が緑系になる。 */
@ChunkFlowPreviews
@Composable
private fun LibraryScreenFolderPreview() {
    val course = "korean-course"
    val materials = listOf(SampleMaterials.jaKo, SampleMaterials.longSentence, SampleMaterials.noTranscript)
    val uiItems = materials.mapIndexed { index, material ->
        val audioItem = material.item.copy(course = course)
        LibraryItemUiModel(
            item = audioItem,
            positionMs = when (index) {
                0 -> audioItem.durationMs * 97 / 100
                1 -> audioItem.durationMs * 40 / 100
                else -> 0L
            },
            speed = if (index == 1) 1.25f else 1.0f,
            isListened = index == 0,
            lastAccessedAtEpochMs = if (index <= 1) 1_760_000_000_000L else null,
        )
    }
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(items = uiItems, currentDirectoryPath = course),
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

@ChunkFlowPreviews
@Composable
private fun LibraryScreenImportConfigDialogPreview() {
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
                pendingImportConfig = PendingImportConfig(
                    uris = emptyList(),
                    initialCourse = "韓国語講座",
                    initialLanguages = LanguagePair(
                        target = LanguageTag("ko"),
                        base = LanguageTag("ja"),
                    ),
                    isDriveSyncFolder = false,
                ),
            ),
            onEvent = {},
        )
    }
}
