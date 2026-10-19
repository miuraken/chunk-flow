package com.miuraken.chunkflow.feature.library

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
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
import androidx.core.net.toUri
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
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.LibraryFolderKind
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PlaylistId
import com.miuraken.chunkflow.core.testing.fixture.SampleLibraryPreviewParameterProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val LAST_ACCESSED_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT)

/** お気に入り登録済み（⭐️）の塗りつぶし星アイコン色。 */
private val FavoriteStarColor = Color(0xFFFFB300)

/** デフォルトの音符サムネイル背景色（落ち着いたグレー）。 */
private val DefaultThumbnailBackgroundColor = Color(0xFF8A8A8E)

/** この割合（%）を超えて聴いた教材を既習として、進捗バーと再生状況を緑系で表示する。 */
private const val COMPLETED_PROGRESS_PERCENT_THRESHOLD = 95

/** 音声ファイルの埋め込みカバーアート（ImageBitmap）のメモリキャッシュ。 */
private val embeddedArtworkCache = object : LruCache<String, ImageBitmap>(64) {}

private val noEmbeddedArtworkKeys = java.util.Collections.synchronizedSet(HashSet<String>())

private fun loadEmbeddedArtworkBitmap(
    context: Context,
    mediaId: MediaId,
    localFilePath: String?,
    sourceUri: String?,
): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        var initialized = false
        if (!localFilePath.isNullOrBlank()) {
            val file = File(localFilePath)
            if (file.exists()) {
                runCatching {
                    retriever.setDataSource(file.absolutePath)
                    initialized = true
                }
            }
        }
        if (!initialized) {
            val internalMediaDir = File(context.filesDir, "media")
            val directFile = File(internalMediaDir, mediaId.value)
            val candidateFile = when {
                directFile.exists() -> directFile

                internalMediaDir.exists() -> internalMediaDir.listFiles()?.firstOrNull {
                    it.name == mediaId.value || it.name.startsWith("${mediaId.value}.")
                }

                else -> null
            }
            if (candidateFile != null && candidateFile.exists()) {
                runCatching {
                    retriever.setDataSource(candidateFile.absolutePath)
                    initialized = true
                }
            }
        }
        if (!initialized && !sourceUri.isNullOrBlank()) {
            runCatching {
                retriever.setDataSource(context, sourceUri.toUri())
                initialized = true
            }
        }
        if (!initialized) {
            null
        } else {
            val pictureBytes = retriever.embeddedPicture
            if (pictureBytes != null && pictureBytes.isNotEmpty()) {
                BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.size)?.asImageBitmap()
            } else {
                null
            }
        }
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

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
    var showCreatePlaylistDialog by rememberSaveable { mutableStateOf(false) }
    var showAddToPlaylistDialog by rememberSaveable { mutableStateOf(false) }

    val currentDirs = state.currentDirectories
    val currentFiles = state.currentFiles

    val canNavigateBack = state.isSelectionMode ||
        (state.selectedTab == LibraryViewTab.FOLDERS && state.currentDirectoryPath != null) ||
        (state.selectedTab == LibraryViewTab.PLAYLISTS && state.activePlaylist != null)

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
                            FilledTonalButton(
                                onClick = { showAddToPlaylistDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    imageVector = ChunkFlowIcons.PlaylistAdd,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.library_selection_playlist_badge),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
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
                        IconButton(onClick = { onEvent(LibraryEvent.OpenSettings) }) {
                            Icon(
                                imageVector = ChunkFlowIcons.Settings,
                                contentDescription = stringResource(R.string.library_open_settings),
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.selectedTab == LibraryViewTab.PLAYLISTS &&
                state.activePlaylist == null &&
                !state.isSelectionMode
            ) {
                FloatingActionButton(
                    onClick = { showCreatePlaylistDialog = true },
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Add,
                        contentDescription = stringResource(R.string.library_playlist_create_fab_cd),
                    )
                }
            }
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

    if (showCreatePlaylistDialog) {
        CreatePlaylistDialog(
            onConfirm = { name ->
                showCreatePlaylistDialog = false
                onEvent(LibraryEvent.CreatePlaylist(name))
            },
            onDismiss = { showCreatePlaylistDialog = false },
        )
    }

    if (showAddToPlaylistDialog) {
        AddToPlaylistDialog(
            userPlaylists = state.userPlaylists,
            onSelectPlaylist = { playlistId ->
                showAddToPlaylistDialog = false
                onEvent(LibraryEvent.AddSelectedItemsToPlaylist(playlistId))
            },
            onCreateAndAdd = { name ->
                showAddToPlaylistDialog = false
                onEvent(LibraryEvent.CreatePlaylistAndAddSelected(name))
            },
            onDismiss = { showAddToPlaylistDialog = false },
        )
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
            availableFolders = state.availableImportFolders,
            onCreateVirtualFolder = { folderName, languages ->
                onEvent(
                    LibraryEvent.CreateVirtualFolder(
                        folderName = folderName,
                        languages = languages,
                    ),
                )
            },
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
                    modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
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
        shape = RoundedCornerShape(8.dp),
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.library_files_sort_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LibraryFileSortOption.entries.forEach { option ->
                    val labelRes = when (option) {
                        LibraryFileSortOption.NAME -> R.string.library_files_sort_name
                        LibraryFileSortOption.ACCESS_TIME -> R.string.library_files_sort_access_time
                        LibraryFileSortOption.COMPLETION -> R.string.library_files_sort_completion
                    }
                    FilterChip(
                        selected = state.fileSortOption == option,
                        onClick = { onEvent(LibraryEvent.SelectFileSortOption(option)) },
                        label = { Text(text = stringResource(labelRes)) },
                    )
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
private fun LibraryPlaylistsTabContent(
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
                                    onDownloadDriveAudioClick = {
                                        onEvent(LibraryEvent.DownloadDriveAudio(itemModel.item.id))
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

/**
 * 左端のドラッグハンドル。上下へのドラッグ量を検知して 1 行分の高さごとに [onMove] を発火する。
 */
@Composable
private fun ReorderDragHandle(
    currentIndex: Int,
    totalCount: Int,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val stepPx = with(density) { 56.dp.toPx() }
    val updatedIndex by rememberUpdatedState(currentIndex)
    val updatedTotalCount by rememberUpdatedState(totalCount)
    val updatedOnMove by rememberUpdatedState(onMove)
    var accumulatedDragY by remember { mutableFloatStateOf(0f) }
    val dragHandleCd = stringResource(R.string.library_drag_handle_cd)

    Box(
        modifier = modifier
            .size(32.dp)
            .semantics { contentDescription = dragHandleCd }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { accumulatedDragY = 0f },
                    onDragEnd = { accumulatedDragY = 0f },
                    onDragCancel = { accumulatedDragY = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        accumulatedDragY += dragAmount
                        val steps = (accumulatedDragY / stepPx).toInt()
                        if (steps != 0) {
                            val from = updatedIndex
                            val to = (from + steps).coerceIn(0, (updatedTotalCount - 1).coerceAtLeast(0))
                            if (to != from) {
                                updatedOnMove(from, to)
                                accumulatedDragY -= (to - from) * stepPx
                            } else {
                                accumulatedDragY = 0f
                            }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ChunkFlowIcons.DragHandle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CreatePlaylistDialog(
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
private fun AddToPlaylistDialog(
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

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
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

@Composable
private fun FolderKindBadge(
    isRealFolder: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(
        if (isRealFolder) R.string.library_folder_kind_real else R.string.library_folder_kind_virtual,
    )
    val containerColor = if (isRealFolder) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isRealFolder) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    Surface(
        modifier = modifier,
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(6.dp),
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
    onRemoveFromPlaylistClick: (() -> Unit)? = null,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val item = itemModel.item
    val formattedDuration = formatDurationMs(item.durationMs)
    val editCd = stringResource(R.string.library_edit_metadata_cd)
    val favoriteCd = stringResource(
        if (isFavorite) R.string.library_favorite_remove_cd else R.string.library_favorite_add_cd,
    )
    val removeFromPlaylistCd = stringResource(R.string.library_playlist_remove_item_cd)
    val langPalette = chunkFlowLanguagePalette()
    val maxPercent = itemModel.maxProgressPercent
    // 既習（95% 超）の教材は、進捗バーを緑系にして一目でわかるようにする。
    val isCompleted = maxPercent > COMPLETED_PROGRESS_PERCENT_THRESHOLD

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(
                start = if (dragHandle != null && !isSelectionMode) 8.dp else 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 8.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(top = 2.dp),
            )
        } else if (dragHandle != null) {
            Box(modifier = Modifier.padding(top = 4.dp)) {
                dragHandle()
            }
        }

        AudioItemThumbnail(
            mediaId = item.id,
            localFilePath = item.localFilePath,
            sourceUri = item.sourceUri,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(40.dp),
        )

        Column(
            modifier = Modifier.weight(1f),
        ) {
            // 1 行目: ファイル名（タイトル）を横幅いっぱいに表示
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(2.dp))

            // 2 行目: 言語ペアバッジ + SpeechToText 文字起こし状態 + 右端に ☆ と ✏️
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LanguagePairBadge(pair = item.languagePair)
                    TranscriptStatusIndicator(
                        transcriptStatus = itemModel.transcriptStatus,
                        transcriptProgressPercent = itemModel.transcriptProgressPercent,
                        isTranscribing = itemModel.isTranscribing,
                        completedBadgeColor = langPalette.completedProgressIndicator,
                    )
                    if (itemModel.isAnalyzing) {
                        Text(
                            text = stringResource(R.string.library_item_analyzing),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
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
                                    maxLines = 1,
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
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (onToggleFavoriteClick != null) {
                    IconButton(
                        onClick = onToggleFavoriteClick,
                        modifier = Modifier.size(28.dp),
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
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
                if (!isSelectionMode) {
                    IconButton(
                        onClick = onEditMetadataClick,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Edit,
                            contentDescription = editCd,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                    if (onRemoveFromPlaylistClick != null) {
                        IconButton(
                            onClick = onRemoveFromPlaylistClick,
                            modifier = Modifier.size(28.dp),
                        ) {
                            Icon(
                                imageVector = ChunkFlowIcons.Close,
                                contentDescription = removeFromPlaylistCd,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(3.dp))
            LinearProgressIndicator(
                progress = { itemModel.maxProgressFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = if (isCompleted) {
                    langPalette.completedProgressIndicator
                } else {
                    ProgressIndicatorDefaults.linearColor
                },
            )
            Spacer(modifier = Modifier.height(2.dp))

            // 3 行目: 再生時間・進捗 と 最終アクセス日時（History アイコン + 日時）
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
            val formattedLastAccessed = itemModel.lastAccessedAtEpochMs?.let { epochMs ->
                formatEpochMsLocal(epochMs).takeIf { it.isNotEmpty() }
            }
            val progressStatusColor = when {
                isCompleted -> langPalette.completedProgressText
                hasProgress || itemModel.lastAccessedAtEpochMs != null -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val lastAccessedColor = MaterialTheme.colorScheme.onSurfaceVariant

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = durationProgressText,
                    style = MaterialTheme.typography.labelSmall,
                    color = progressStatusColor,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                if (formattedLastAccessed != null) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterVertically),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = lastAccessedColor,
                        )
                        Icon(
                            imageVector = ChunkFlowIcons.History,
                            contentDescription = null,
                            tint = lastAccessedColor,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = formattedLastAccessed,
                            style = MaterialTheme.typography.labelSmall,
                            color = lastAccessedColor,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptStatusIndicator(
    transcriptStatus: LibraryTranscriptStatus,
    transcriptProgressPercent: Int,
    isTranscribing: Boolean,
    completedBadgeColor: Color,
    modifier: Modifier = Modifier,
) {
    when {
        transcriptStatus == LibraryTranscriptStatus.COMPLETED -> {
            val completedCd = stringResource(R.string.library_item_transcript_completed)
            Box(
                modifier = modifier
                    .size(22.dp)
                    .background(color = completedBadgeColor, shape = CircleShape)
                    .semantics { contentDescription = completedCd },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.SpeechToText,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        isTranscribing || transcriptStatus == LibraryTranscriptStatus.PARTIAL -> {
            val statusCd = if (isTranscribing) {
                stringResource(R.string.library_item_transcript_in_progress, transcriptProgressPercent)
            } else {
                stringResource(R.string.library_item_transcript_partial, transcriptProgressPercent)
            }
            Row(
                modifier = modifier.semantics(mergeDescendants = true) {
                    contentDescription = statusCd
                },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.SpeechToText,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                if (isTranscribing) {
                    if (LocalInspectionMode.current) {
                        CircularProgressIndicator(
                            progress = { 0.75f },
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            trackColor = Color.Transparent,
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = "$transcriptProgressPercent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> {
            val noneCd = stringResource(R.string.library_item_transcript_none)
            Icon(
                imageVector = ChunkFlowIcons.SpeechToText,
                contentDescription = noneCd,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
                modifier = modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun AudioItemThumbnail(
    mediaId: MediaId,
    localFilePath: String?,
    sourceUri: String?,
    modifier: Modifier = Modifier,
) {
    val isInspectionMode = LocalInspectionMode.current
    val context = LocalContext.current
    val cacheKey = remember(mediaId.value, localFilePath, sourceUri) {
        "${mediaId.value}|${localFilePath.orEmpty()}|${sourceUri.orEmpty()}"
    }
    var bitmap by remember(cacheKey) {
        mutableStateOf(
            if (isInspectionMode) null else embeddedArtworkCache.get(cacheKey),
        )
    }

    LaunchedEffect(cacheKey, isInspectionMode) {
        if (!isInspectionMode && bitmap == null && !noEmbeddedArtworkKeys.contains(cacheKey)) {
            val loaded = withContext(Dispatchers.IO) {
                loadEmbeddedArtworkBitmap(
                    context = context,
                    mediaId = mediaId,
                    localFilePath = localFilePath,
                    sourceUri = sourceUri,
                )
            }
            if (loaded != null) {
                embeddedArtworkCache.put(cacheKey, loaded)
                bitmap = loaded
            } else {
                noEmbeddedArtworkKeys.add(cacheKey)
            }
        }
    }

    val shape = RoundedCornerShape(6.dp)
    val previewStyle = if (isInspectionMode) {
        when {
            localFilePath == "preview:cover_pimsleur" || sourceUri == "preview:cover_pimsleur" -> 1
            localFilePath == "preview:cover_book" || sourceUri == "preview:cover_book" -> 2
            else -> 0
        }
    } else {
        0
    }

    val currentBitmap = bitmap
    when {
        currentBitmap != null -> {
            Image(
                bitmap = currentBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier.clip(shape),
            )
        }

        previewStyle == 1 -> {
            PreviewPimsleurArtwork(modifier = modifier.clip(shape))
        }

        previewStyle == 2 -> {
            PreviewBookArtwork(modifier = modifier.clip(shape))
        }

        else -> {
            Box(
                modifier = modifier
                    .background(color = DefaultThumbnailBackgroundColor, shape = shape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun PreviewPimsleurArtwork(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color.White)) {
        val w = size.width
        val h = size.height
        drawRect(
            color = Color(0xFF0D47A1),
            topLeft = Offset.Zero,
            size = Size(w * 0.55f, h),
        )
        drawCircle(
            color = Color(0xFF1976D2),
            radius = w * 0.42f,
            center = Offset(w * 0.35f, h * 0.45f),
        )
        val wavePath = Path().apply {
            moveTo(0f, h * 0.65f)
            cubicTo(w * 0.3f, h * 0.5f, w * 0.5f, h * 0.85f, w, h * 0.7f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(path = wavePath, color = Color(0xFF42A5F5).copy(alpha = 0.75f))
    }
}

@Composable
private fun PreviewBookArtwork(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.background(Color(0xFFECEFF1))) {
        val w = size.width
        val h = size.height
        drawRect(
            color = Color(0xFF81D4FA),
            topLeft = Offset(w * 0.08f, h * 0.08f),
            size = Size(w * 0.84f, h * 0.28f),
        )
        drawRect(
            color = Color(0xFF90A4AE),
            topLeft = Offset(w * 0.08f, h * 0.36f),
            size = Size(w * 0.84f, h * 0.56f),
        )
        drawRect(
            color = Color(0xFF546E7A),
            topLeft = Offset(w * 0.18f, h * 0.48f),
            size = Size(w * 0.64f, h * 0.34f),
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
            AudioItemThumbnail(
                mediaId = miniPlayer.mediaId,
                localFilePath = miniPlayer.localFilePath,
                sourceUri = miniPlayer.sourceUri,
                modifier = Modifier.size(40.dp),
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
                courseFolders = listOf(
                    LibraryCourseFolder(
                        coursePath = "korean-course",
                        kind = LibraryFolderKind.LOCAL_DIRECTORY,
                        languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
                    ),
                ),
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
    val course = "ピンズラー韓国語 I"
    val baseItem = SampleMaterials.jaKo.item
    val uiItems = listOf(
        LibraryItemUiModel(
            item = baseItem.copy(
                id = MediaId("pimsleur-01"),
                title = "ピンズラー 韓国語 01",
                durationMs = 1_834_000L,
                course = course,
                localFilePath = "preview:cover_pimsleur",
            ),
            positionMs = 1_806_000L,
            maxPositionMs = 1_816_000L,
            hasTranscript = true,
            transcriptStatus = LibraryTranscriptStatus.COMPLETED,
            transcriptProgressPercent = 100,
            isListened = true,
            lastAccessedAtEpochMs = 1_792_338_600_000L,
        ),
        LibraryItemUiModel(
            item = baseItem.copy(
                id = MediaId("pimsleur-02"),
                title = "ピンズラー 韓国語 02",
                durationMs = 1_825_000L,
                course = course,
                localFilePath = "preview:cover_book",
            ),
            positionMs = 586_000L,
            maxPositionMs = 949_000L,
            hasTranscript = true,
            transcriptStatus = LibraryTranscriptStatus.PARTIAL,
            transcriptProgressPercent = 82,
            lastAccessedAtEpochMs = 1_792_338_600_000L,
        ),
        LibraryItemUiModel(
            item = baseItem.copy(
                id = MediaId("pimsleur-03"),
                title = "ピンズラー 韓国語 03",
                durationMs = 1_710_000L,
                course = course,
                localFilePath = "preview:cover_pimsleur",
            ),
            positionMs = 0L,
            hasTranscript = true,
            transcriptStatus = LibraryTranscriptStatus.PARTIAL,
            transcriptProgressPercent = 35,
            isTranscribing = true,
        ),
        LibraryItemUiModel(
            item = baseItem.copy(
                id = MediaId("pimsleur-04"),
                title = "ピンズラー 韓国語 04",
                durationMs = 1_669_000L,
                course = course,
            ),
            positionMs = 0L,
            hasTranscript = false,
            transcriptStatus = LibraryTranscriptStatus.NONE,
        ),
    )
    val recentHindiItems = listOf(1, 3, 7, 8).mapIndexed { idx, num ->
        LibraryItemUiModel(
            item = baseItem.copy(
                id = MediaId("hindi-$num"),
                title = "Lesson $num",
                course = "Hindi_1",
                languagePair = LanguagePair(target = LanguageTag("hi"), base = LanguageTag("ja")),
            ),
            positionMs = (10 - idx) * 60_000L,
            maxPositionMs = (12 - idx) * 60_000L,
            lastAccessedAtEpochMs = 1_792_338_000_000L - idx * 60_000L,
        )
    }
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = uiItems + recentHindiItems,
                currentDirectoryPath = course,
                courseFolders = listOf(
                    LibraryCourseFolder(
                        coursePath = course,
                        kind = LibraryFolderKind.LOCAL_DIRECTORY,
                        languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
                    ),
                ),
                miniPlayer = MiniPlayerUiState(
                    mediaId = uiItems.first().item.id,
                    title = "Lesson 1",
                    isPlaying = false,
                    durationMs = 1_852_000L,
                    positionMs = 102_000L,
                    localFilePath = "preview:cover_pimsleur",
                ),
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
                    initialCourse = LibraryCourseFolder.DEFAULT_IMPORTED_COURSE,
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
