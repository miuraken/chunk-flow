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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.miuraken.chunkflow.core.designsystem.component.LanguageSettingsModalDialog
import com.miuraken.chunkflow.core.designsystem.component.MissingLanguageHintWithModalLink
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.AudioStorageState
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

/** お気に入り登録済み（⭐️）の塗りつぶし星アイコン色。 */
private val FavoriteStarColor = Color(0xFFFFB300)

/** この割合（%）を超えて聴いた教材を既習として、進捗バーと再生状況を緑系で表示する。 */
private const val COMPLETED_PROGRESS_PERCENT_THRESHOLD = 95

private fun formatEpochMsLocal(epochMs: Long): String = runCatching {
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(LAST_ACCESSED_FORMATTER)
}.getOrDefault("")

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
            val targetUris = scanned.documentUris.ifEmpty { listOf(treeUri) }
            viewModel.onEvent(
                LibraryEvent.ImportDirectory(
                    directoryName = scanned.directoryName,
                    uris = targetUris,
                    treeUri = treeUri,
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

                is LibraryEvent.OpenItem -> {
                    viewModel.onEvent(event)
                    onOpenPlayer(event.id)
                }

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
    var showCreateFolderDialog by remember { mutableStateOf(false) }

    val currentDirs = state.currentDirectories
    val currentFiles = state.currentFiles

    val canNavigateBack = state.isSelectionMode ||
        (state.selectedTab == LibraryViewTab.FOLDERS && state.currentDirectoryPath != null) ||
        (state.selectedTab == LibraryViewTab.PLAYLISTS && state.selectedPlaylistCategory != null)

    BackHandler(enabled = canNavigateBack) {
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
                    }
                },
                title = {
                    val titleText = if (state.isSelectionMode) {
                        stringResource(
                            R.string.library_selection_count,
                            state.selectedCount,
                        )
                    } else {
                        stringResource(R.string.library_title)
                    }
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    if (state.isSelectionMode) {
                        val hasSelectedFiles = state.selectedItemIds.isNotEmpty() ||
                            state.selectedTargetItemIds.isNotEmpty()
                        if (hasSelectedFiles) {
                            val allFavorite = state.areAllSelectedFavorite
                            FilledTonalButton(
                                onClick = { onEvent(LibraryEvent.ToggleFavoriteSelected) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    imageVector = if (allFavorite) {
                                        ChunkFlowIcons.Favorite
                                    } else {
                                        ChunkFlowIcons.FavoriteBorder
                                    },
                                    contentDescription = null,
                                    tint = if (allFavorite) {
                                        FavoriteStarColor
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                                    },
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.library_selection_favorite_badge),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        FilledTonalButton(
                            onClick = { onEvent(LibraryEvent.RequestDeleteSelected) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ),
                        ) {
                            Icon(
                                imageVector = ChunkFlowIcons.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.library_selection_delete_badge),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        IconButton(onClick = { onEvent(LibraryEvent.RescanLocalDirectories) }) {
                            Icon(
                                imageVector = ChunkFlowIcons.Sync,
                                contentDescription = stringResource(R.string.library_refresh_cd),
                            )
                        }
                        IconButton(onClick = { onEvent(LibraryEvent.RequestImportDirectory) }) {
                            Icon(
                                imageVector = ChunkFlowIcons.CreateNewFolder,
                                contentDescription = stringResource(R.string.library_add_folder_cd),
                            )
                        }
                        IconButton(onClick = { onEvent(LibraryEvent.RequestImport) }) {
                            Icon(
                                imageVector = ChunkFlowIcons.NoteAdd,
                                contentDescription = stringResource(R.string.library_add_file_cd),
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

            LibraryViewTabRow(
                selectedTab = state.selectedTab,
                onSelectTab = { tab -> onEvent(LibraryEvent.SelectTab(tab)) },
            )

            if (state.items.isEmpty() && state.courseFolders.isEmpty() && state.selectedTab != LibraryViewTab.PLAYLISTS) {
                LibraryEmptyContent(
                    onImportDirectoryClick = { onEvent(LibraryEvent.RequestImportDirectory) },
                    onImportFilesClick = { onEvent(LibraryEvent.RequestImport) },
                    onSyncDriveClick = { onEvent(LibraryEvent.SyncFromDrive) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                when (state.selectedTab) {
                    LibraryViewTab.FOLDERS -> {
                        LibraryFoldersTabContent(
                            state = state,
                            listState = listState,
                            onEvent = onEvent,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    LibraryViewTab.FILES -> {
                        LibraryFilesTabContent(
                            state = state,
                            onEvent = onEvent,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    LibraryViewTab.PLAYLISTS -> {
                        LibraryPlaylistsTabContent(
                            state = state,
                            onEvent = onEvent,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    if (state.showLanguageOnboardingDialog) {
        LanguageSettingsModalDialog(
            enabledLanguages = state.enabledLanguages.toSet(),
            onToggleLanguage = { onEvent(LibraryEvent.ToggleEnabledLanguage(it)) },
            onDismiss = { onEvent(LibraryEvent.CompleteLanguageOnboarding) },
            isFirstLaunchOnboarding = true,
        )
    }

    state.pendingImportConfig?.let { config ->
        ImportConfigDialog(
            config = config,
            enabledLanguages = state.enabledLanguages,
            onToggleEnabledLanguage = { onEvent(LibraryEvent.ToggleEnabledLanguage(it)) },
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

    if (showCreateFolderDialog) {
        CreateVirtualFolderDialog(
            enabledLanguages = state.enabledLanguages,
            onToggleEnabledLanguage = { onEvent(LibraryEvent.ToggleEnabledLanguage(it)) },
            onConfirm = { folderName, languages ->
                showCreateFolderDialog = false
                onEvent(
                    LibraryEvent.CreateVirtualFolder(
                        folderName = folderName,
                        languages = languages,
                    ),
                )
            },
            onDismiss = { showCreateFolderDialog = false },
        )
    }

    state.pendingEditItem?.let { itemToEdit ->
        EditItemMetadataDialog(
            item = itemToEdit,
            enabledLanguages = state.enabledLanguages,
            onToggleEnabledLanguage = { onEvent(LibraryEvent.ToggleEnabledLanguage(it)) },
            onImportTranscript = {
                onEvent(LibraryEvent.RequestImportTranscriptForItem(itemToEdit.id))
            },
            onDeleteLocalAudioCopy = if (itemToEdit.storageState == AudioStorageState.DRIVE_DOWNLOADED) {
                { onEvent(LibraryEvent.DeleteLocalAudioCopy(itemToEdit.id)) }
            } else {
                null
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
            onToggleEnabledLanguage = { onEvent(LibraryEvent.ToggleEnabledLanguage(it)) },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryViewTabRow(
    selectedTab: LibraryViewTab,
    onSelectTab: (LibraryViewTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    PrimaryTabRow(
        selectedTabIndex = selectedTab.ordinal,
        modifier = modifier.fillMaxWidth(),
    ) {
        LibraryViewTab.entries.forEach { tab ->
            val labelRes = when (tab) {
                LibraryViewTab.FOLDERS -> R.string.library_tab_folders
                LibraryViewTab.FILES -> R.string.library_tab_files
                LibraryViewTab.PLAYLISTS -> R.string.library_tab_playlists
            }
            Tab(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                text = {
                    Text(
                        text = stringResource(labelRes),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        }
    }
}

@Composable
private fun LibraryFoldersTabContent(
    state: LibraryUiState,
    listState: LazyListState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentDirs = state.currentDirectories
    val currentFiles = state.currentFiles
    val hasRecentItems = state.recentItems.isNotEmpty()
    var isRecentFilesExpanded by rememberSaveable { mutableStateOf(true) }
    val currentFolderPath = state.currentDirectoryPath?.trim('/')?.takeIf { it.isNotBlank() }

    Column(modifier = modifier.fillMaxSize()) {
        if (currentFolderPath != null) {
            val totalItemsInFolder = state.itemsInDirectoryTree(currentFolderPath).size
            LibraryCurrentFolderHeader(
                folderPath = currentFolderPath,
                itemCount = totalItemsInFolder,
                isSelectionMode = state.isSelectionMode,
                isSelected = currentFolderPath in state.selectedDirectoryPaths,
                onNavigateUp = { onEvent(LibraryEvent.NavigateUp) },
                onLongClick = { onEvent(LibraryEvent.ToggleDirectorySelection(currentFolderPath)) },
                onEditDirectoryMetadata = {
                    onEvent(LibraryEvent.RequestEditDirectoryMetadata(currentFolderPath))
                },
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(if (hasRecentItems && isRecentFilesExpanded) 2f else 1f),
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
                            onDownloadDriveAudioClick = {
                                onEvent(LibraryEvent.DownloadDriveAudio(itemModel.item.id))
                            },
                            isFavorite = itemModel.item.id in state.favoriteItemIds,
                            onToggleFavoriteClick = {
                                onEvent(LibraryEvent.ToggleFavorite(itemModel.item.id))
                            },
                            isQueued = itemModel.item.id in state.queuedItemIds,
                            onToggleQueueClick = {
                                onEvent(LibraryEvent.ToggleQueueItem(itemModel.item.id))
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
                modifier = if (isRecentFilesExpanded) {
                    Modifier.weight(1f)
                } else {
                    Modifier
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryCurrentFolderHeader(
    folderPath: String,
    itemCount: Int,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onNavigateUp: () -> Unit,
    onLongClick: () -> Unit,
    onEditDirectoryMetadata: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hierarchicalTitle = folderPath.replace("/", " / ")
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
                    onClick = onNavigateUp,
                    onLongClick = onLongClick,
                )
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ChunkFlowIcons.Folder,
                contentDescription = stringResource(R.string.library_navigate_up),
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
                    text = hierarchicalTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
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

@Composable
private fun LibraryFilesTabContent(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filteredFiles = state.filteredAllFiles
    val targetLanguages = state.availableTargetLanguages

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
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(),
            )

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

        if (filteredFiles.isEmpty()) {
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
        } else {
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
                                onDownloadDriveAudioClick = {
                                    onEvent(LibraryEvent.DownloadDriveAudio(itemModel.item.id))
                                },
                                isFavorite = itemModel.item.id in state.favoriteItemIds,
                                onToggleFavoriteClick = {
                                    onEvent(LibraryEvent.ToggleFavorite(itemModel.item.id))
                                },
                                isQueued = itemModel.item.id in state.queuedItemIds,
                                onToggleQueueClick = {
                                    onEvent(LibraryEvent.ToggleQueueItem(itemModel.item.id))
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
private fun LibraryPlaylistsTabContent(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeCategory = state.selectedPlaylistCategory

    if (activeCategory == null) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(
                items = LibraryPlaylistCategory.entries,
                key = { _, category -> "playlist_${category.name}" },
            ) { index, category ->
                val count = state.playlistItemsFor(category).size
                LibraryPlaylistCategoryRow(
                    category = category,
                    itemCount = count,
                    onClick = { onEvent(LibraryEvent.SelectPlaylistCategory(category)) },
                )
                if (index < LibraryPlaylistCategory.entries.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    } else {
        val playlistItems = state.playlistItemsFor(activeCategory)
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
                        .clickable { onEvent(LibraryEvent.SelectPlaylistCategory(null)) }
                        .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { onEvent(LibraryEvent.SelectPlaylistCategory(null)) }) {
                        Icon(
                            imageVector = ChunkFlowIcons.ArrowBack,
                            contentDescription = stringResource(R.string.library_navigate_up),
                        )
                    }
                    Icon(
                        imageVector = playlistCategoryIcon(activeCategory),
                        contentDescription = null,
                        tint = if (activeCategory == LibraryPlaylistCategory.FAVORITES) {
                            FavoriteStarColor
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = playlistCategoryTitle(activeCategory),
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
                                    onDownloadDriveAudioClick = {
                                        onEvent(LibraryEvent.DownloadDriveAudio(itemModel.item.id))
                                    },
                                    isFavorite = itemModel.item.id in state.favoriteItemIds,
                                    onToggleFavoriteClick = {
                                        onEvent(LibraryEvent.ToggleFavorite(itemModel.item.id))
                                    },
                                    isQueued = itemModel.item.id in state.queuedItemIds,
                                    onToggleQueueClick = {
                                        onEvent(LibraryEvent.ToggleQueueItem(itemModel.item.id))
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
private fun LibraryPlaylistCategoryRow(
    category: LibraryPlaylistCategory,
    itemCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(14.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = playlistCategoryIcon(category),
                    contentDescription = null,
                    tint = if (category == LibraryPlaylistCategory.FAVORITES) {
                        FavoriteStarColor
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlistCategoryTitle(category),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.library_directory_item_count, itemCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = ChunkFlowIcons.MoreHoriz,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
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
    modifier: Modifier = Modifier,
    onDeleteClick: () -> Unit = {},
    onDownloadDriveAudioClick: () -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavoriteClick: (() -> Unit)? = null,
    isQueued: Boolean = false,
    onToggleQueueClick: (() -> Unit)? = null,
) {
    val item = itemModel.item
    val formattedDuration = formatDurationMs(item.durationMs)
    val editCd = stringResource(R.string.library_edit_metadata_cd)
    val favoriteCd = stringResource(
        if (isFavorite) R.string.library_favorite_remove_cd else R.string.library_favorite_add_cd,
    )
    val queueCd = stringResource(
        if (isQueued) R.string.library_queue_remove_cd else R.string.library_queue_add_cd,
    )

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
            if (onToggleFavoriteClick != null) {
                IconButton(
                    onClick = onToggleFavoriteClick,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (isFavorite) {
                            ChunkFlowIcons.Favorite
                        } else {
                            ChunkFlowIcons.FavoriteBorder
                        },
                        contentDescription = favoriteCd,
                        tint = if (isFavorite) {
                            FavoriteStarColor
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (!isSelectionMode) {
                if (onToggleQueueClick != null) {
                    IconButton(
                        onClick = onToggleQueueClick,
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.QueueMusic,
                            contentDescription = queueCd,
                            tint = if (isQueued) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
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
            if (itemModel.isTranscribing && itemModel.transcriptStatus != LibraryTranscriptStatus.COMPLETED) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(10.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(
                        text = stringResource(
                            R.string.library_item_transcript_in_progress,
                            itemModel.transcriptProgressPercent,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            } else {
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
            }
            if (item.isRemoteOnly) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.clickable(onClick = onDownloadDriveAudioClick),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                        )
                        Text(
                            text = stringResource(R.string.library_item_drive_remote_only_badge),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            } else if (itemModel.isAudioMissing) {
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

@Composable
private fun LanguagePairBadge(
    pair: LanguagePair,
    modifier: Modifier = Modifier,
) {
    val langPalette = chunkFlowLanguagePalette()
    val targetUpper = pair.target.value.lowercase(Locale.ROOT)
    val baseUpper = pair.base?.value?.lowercase(Locale.ROOT)

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
            modifier = if (isExpanded) {
                Modifier.fillMaxSize()
            } else {
                Modifier.fillMaxWidth()
            },
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
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    itemsIndexed(
                        items = recentItems,
                        key = { _, itemModel -> "recent_${itemModel.item.id.value}" },
                    ) { index, itemModel ->
                        val isFirst = index == 0
                        val isLast = index == recentItems.lastIndex
                        val shape = when {
                            isFirst && isLast -> RoundedCornerShape(10.dp)
                            isFirst -> RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)
                            isLast -> RoundedCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
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
    val durationAndPositionText = if (miniPlayer.positionMs > 0L) {
        stringResource(
            R.string.library_item_duration_progress,
            formatDurationMs(miniPlayer.positionMs),
            formatDurationMs(miniPlayer.durationMs),
        )
    } else {
        formatDurationMs(miniPlayer.durationMs)
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
                    text = durationAndPositionText,
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

@ChunkFlowPreviews
@Composable
private fun LibraryScreenFilesTabPreview() {
    val materials = listOf(SampleMaterials.jaKo, SampleMaterials.longSentence, SampleMaterials.noTranscript)
    val uiItems = materials.mapIndexed { index, material ->
        LibraryItemUiModel(
            item = material.item,
            positionMs = if (index == 0) 12_000L else 0L,
            isListened = index == 0,
            lastAccessedAtEpochMs = if (index == 0) 1_760_000_000_000L else null,
        )
    }
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = uiItems,
                selectedTab = LibraryViewTab.FILES,
                favoriteItemIds = setOf(materials.first().item.id),
            ),
            onEvent = {},
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun LibraryScreenPlaylistsTabPreview() {
    val materials = listOf(SampleMaterials.jaKo, SampleMaterials.longSentence)
    val uiItems = materials.mapIndexed { index, material ->
        LibraryItemUiModel(
            item = material.item,
            positionMs = if (index == 0) 12_000L else 0L,
            isListened = index == 0,
            lastAccessedAtEpochMs = 1_760_000_000_000L,
        )
    }
    val first = materials.first().item
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = uiItems,
                selectedTab = LibraryViewTab.PLAYLISTS,
                favoriteItemIds = setOf(first.id),
                queuedItemIds = listOf(first.id),
                miniPlayer = MiniPlayerUiState(
                    mediaId = first.id,
                    title = first.title,
                    isPlaying = true,
                    durationMs = first.durationMs,
                ),
            ),
            onEvent = {},
        )
    }
}
