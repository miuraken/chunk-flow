package com.miuraken.chunkflow.feature.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.designsystem.component.LanguageSettingsModalDialog
import com.miuraken.chunkflow.core.designsystem.component.feedback.ChunkFlowSnackbarHost
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowExtendedColors
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.MediaId

/**
 * システムのファイル選択画面（DocumentsUI）の上部に出す案内文の extra（`DocumentsContract.EXTRA_PROMPT`。SDK では非公開）。
 * 対応していない版では無視されるだけなので、案内はトースト（[showPickerPromptToast]）でも出す。
 */
private const val EXTRA_PICKER_PROMPT = "android.provider.extra.PROMPT"

internal class OpenMultipleAudioDocumentsInput(
    val mimeTypes: Array<String>,
    val initialDirectoryUri: Uri? = null,
    val prompt: String? = null,
)

internal object OpenMultipleAudioDocumentsContract :
    ActivityResultContract<OpenMultipleAudioDocumentsInput, List<Uri>>() {
    private val delegate = ActivityResultContracts.OpenMultipleDocuments()

    override fun createIntent(context: Context, input: OpenMultipleAudioDocumentsInput): Intent = delegate.createIntent(context, input.mimeTypes).apply {
        if (input.initialDirectoryUri != null) {
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, input.initialDirectoryUri)
        }
        if (input.prompt != null) {
            putExtra(EXTRA_PICKER_PROMPT, input.prompt)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> = delegate.parseResult(resultCode, intent)
}

/** フォルダ選択（`ACTION_OPEN_DOCUMENT_TREE`）に案内文（[prompt]）を付けて開く入力。 */
internal class OpenDocumentTreeWithPromptInput(
    val prompt: String? = null,
)

/** フォルダ選択の画面を、案内文の extra を付けて開く契約。結果は [ActivityResultContracts.OpenDocumentTree] と同じ。 */
internal object OpenDocumentTreeWithPromptContract :
    ActivityResultContract<OpenDocumentTreeWithPromptInput, Uri?>() {
    private val delegate = ActivityResultContracts.OpenDocumentTree()

    override fun createIntent(context: Context, input: OpenDocumentTreeWithPromptInput): Intent = delegate.createIntent(context, null).apply {
        if (input.prompt != null) {
            putExtra(EXTRA_PICKER_PROMPT, input.prompt)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = delegate.parseResult(resultCode, intent)
}

/**
 * フォルダ選択とファイル選択のシステム画面は見た目がほぼ同じなので、どちらを選ぶ場面かを開く直前にトーストで知らせる
 * （テキストのトーストはシステムの画面の上にも出る）。
 */
private fun showPickerPromptToast(context: Context, message: String) {
    android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
}

/**
 * ライブラリ画面の Route。画面遷移のコールバックと SAF ピッカー（フォルダ指定・ファイル選択）を扱い、[LibraryScreen] に状態を渡す。
 */
@Composable
fun LibraryRoute(
    onOpenPlayer: (MediaId) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenLanguageSettings: () -> Unit = onOpenSettings,
    onOpenPlayerAtChunk: (MediaId, ChunkId) -> Unit = { id, _ -> onOpenPlayer(id) },
    onOpenTranscriptExport: (List<MediaId>) -> Unit = {},
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val directoryPickerLauncher = rememberLauncherForActivityResult(
        contract = OpenDocumentTreeWithPromptContract,
    ) { treeUri ->
        if (treeUri != null) {
            // 利用者のフォルダには書き込まない方針なので、読み取りの権限だけを永続化する
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
                    treeUri = treeUri,
                ),
            )
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = OpenMultipleAudioDocumentsContract,
    ) { uris ->
        if (uris.isNotEmpty()) {
            val firstUri = uris.first()
            viewModel.onEvent(
                LibraryEvent.ImportUris(
                    uris = uris,
                    parentFolderName = extractParentFolderNameFromUri(firstUri, context.contentResolver),
                    parentDirectoryUri = extractParentDirectoryUriFromFileUri(firstUri, context.contentResolver),
                ),
            )
        }
    }

    LibraryScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                LibraryEvent.RequestImportDirectory -> {
                    val prompt = context.getString(R.string.library_picker_prompt_folder)
                    showPickerPromptToast(context, prompt)
                    directoryPickerLauncher.launch(OpenDocumentTreeWithPromptInput(prompt = prompt))
                }

                LibraryEvent.RequestImport -> {
                    val initialDirUri = runCatching {
                        state.resolveInitialImportDirectoryUriString().toUri()
                    }.getOrNull()
                    val prompt = context.getString(R.string.library_picker_prompt_files)
                    showPickerPromptToast(context, prompt)
                    filePickerLauncher.launch(
                        OpenMultipleAudioDocumentsInput(
                            mimeTypes = arrayOf("audio/*"),
                            initialDirectoryUri = initialDirUri,
                            prompt = prompt,
                        ),
                    )
                }

                is LibraryEvent.OpenItem -> {
                    viewModel.onEvent(event)
                    onOpenPlayer(event.id)
                }

                is LibraryEvent.OpenBookmarkedChunkInPlayer -> {
                    viewModel.onEvent(event)
                    onOpenPlayerAtChunk(event.mediaId, event.chunkId)
                }

                LibraryEvent.OpenSettings -> onOpenSettings()

                else -> viewModel.onEvent(event)
            }
        },
        onOpenTranscriptExport = onOpenTranscriptExport,
        modifier = modifier,
    )
}

/**
 * ライブラリ画面（ディレクトリ構成・メタデータ編集に対応し、状態を受け取って描くだけの Composable。§7.5.1、§10.5.1）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenTranscriptExport: (List<MediaId>) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val topAppBarScrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
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

    ImportStatusSnackbarEffect(
        importState = state.importState,
        snackbarHostState = snackbarHostState,
        onOpenItem = { mediaId -> onEvent(LibraryEvent.OpenItem(mediaId)) },
        onOpenFolder = { folder ->
            onEvent(LibraryEvent.SelectTab(LibraryViewTab.FOLDERS))
            onEvent(LibraryEvent.OpenDirectory(folder))
        },
        onDismiss = { onEvent(LibraryEvent.DismissImportStatus) },
    )

    NavigationSuiteScaffold(
        modifier = modifier,
        navigationSuiteItems = {
            LibraryViewTab.entries.forEach { tab ->
                val labelRes = when (tab) {
                    LibraryViewTab.FOLDERS -> R.string.library_tab_folders
                    LibraryViewTab.FILES -> R.string.library_tab_files
                    LibraryViewTab.PLAYLISTS -> R.string.library_tab_playlists
                    LibraryViewTab.BOOKMARKS -> R.string.library_tab_bookmarks
                }
                val iconVector = when (tab) {
                    LibraryViewTab.FOLDERS -> ChunkFlowIcons.Folder
                    LibraryViewTab.FILES -> ChunkFlowIcons.AudioFile
                    LibraryViewTab.PLAYLISTS -> ChunkFlowIcons.QueueMusic
                    LibraryViewTab.BOOKMARKS -> ChunkFlowIcons.Bookmark
                }
                item(
                    selected = state.selectedTab == tab,
                    onClick = { onEvent(LibraryEvent.SelectTab(tab)) },
                    icon = {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(labelRes),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        },
    ) {
        Scaffold(
            modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
            snackbarHost = { ChunkFlowSnackbarHost(hostState = snackbarHostState) },
            topBar = {
                MediumTopAppBar(
                    scrollBehavior = topAppBarScrollBehavior,
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
                        AnimatedContent(
                            targetState = state.isSelectionMode to state.selectedCount,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "topAppBarTitle",
                        ) { (isSelection, selectedCount) ->
                            val titleText = if (isSelection) {
                                stringResource(
                                    R.string.library_selection_count,
                                    selectedCount,
                                )
                            } else {
                                stringResource(R.string.library_title)
                            }
                            Text(
                                text = titleText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (!isSelection) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                    ) { onEvent(LibraryEvent.ResetToFoldersRoot) }
                                } else {
                                    Modifier
                                },
                            )
                        }
                    },
                    actions = {
                        AnimatedContent(
                            targetState = state.isSelectionMode,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "topAppBarActions",
                        ) { isSelection ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isSelection) {
                                    val showFileOnlySelectionBadges =
                                        state.selectedItemIds.isNotEmpty() && state.selectedDirectoryPaths.isEmpty()
                                    val hasSelectedFiles = state.selectedItemIds.isNotEmpty() ||
                                        state.selectedTargetItemIds.isNotEmpty()
                                    if (showFileOnlySelectionBadges) {
                                        FilledTonalButton(
                                            onClick = { showAddToPlaylistDialog = true },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
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
                                        Spacer(modifier = Modifier.width(4.dp))
                                        val allFavorite = state.areAllSelectedFavorite
                                        FilledTonalButton(
                                            onClick = { onEvent(LibraryEvent.ToggleFavoriteSelected) },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (allFavorite) {
                                                    ChunkFlowIcons.Favorite
                                                } else {
                                                    ChunkFlowIcons.FavoriteBorder
                                                },
                                                contentDescription = null,
                                                tint = if (allFavorite) {
                                                    chunkFlowExtendedColors().favorite
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
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    if (hasSelectedFiles) {
                                        FilledTonalButton(
                                            onClick = {
                                                val targetIds = state.selectedTargetItemIds
                                                onEvent(LibraryEvent.ClearSelection)
                                                if (targetIds.isNotEmpty()) {
                                                    onOpenTranscriptExport(targetIds)
                                                }
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                        ) {
                                            Icon(
                                                imageVector = ChunkFlowIcons.Download,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = stringResource(R.string.library_selection_export_badge),
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    FilledTonalButton(
                                        onClick = { onEvent(LibraryEvent.RequestDeleteSelected) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
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
                ExpandableBottomPlayerBar(
                    state = state,
                    onEvent = onEvent,
                )
            },
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                ImportStatusBanner(
                    importState = state.importState,
                    onCancel = { onEvent(LibraryEvent.CancelImport) },
                )

                if (state.isLoading) {
                    // 初回ロード中は「教材がまだありません」を表示しない（空の領域を確保）
                    Spacer(modifier = Modifier.weight(1f))
                } else if (state.items.isEmpty() &&
                    state.courseFolders.isEmpty() &&
                    state.selectedTab != LibraryViewTab.PLAYLISTS &&
                    state.selectedTab != LibraryViewTab.BOOKMARKS
                ) {
                    LibraryEmptyContent(
                        onImportDirectoryClick = { onEvent(LibraryEvent.RequestImportDirectory) },
                        onImportFilesClick = { onEvent(LibraryEvent.RequestImport) },
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

                        LibraryViewTab.BOOKMARKS -> {
                            LibraryBookmarksTabContent(
                                state = state,
                                onEvent = onEvent,
                                modifier = Modifier.weight(1f),
                            )
                        }
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
            onCreateVirtualFolder = { folderName, languages, sttOverride ->
                onEvent(
                    LibraryEvent.CreateVirtualFolder(
                        folderName = folderName,
                        languages = languages,
                        sttOverride = sttOverride,
                    ),
                )
            },
            onConfirm = { course, languages, sttOverride ->
                onEvent(
                    LibraryEvent.ConfirmImportConfig(
                        course = course,
                        languages = languages,
                        sttOverride = sttOverride,
                    ),
                )
            },
            onDismiss = { onEvent(LibraryEvent.CancelImportConfig) },
            sttEngineByLanguage = state.sttEngineByLanguage,
            onOpenLanguageSettings = { onEvent(LibraryEvent.OpenLanguageSettings) },
        )
    }

    if (showCreateFolderDialog) {
        CreateVirtualFolderDialog(
            enabledLanguages = state.enabledLanguages,
            onToggleEnabledLanguage = { onEvent(LibraryEvent.ToggleEnabledLanguage(it)) },
            onConfirm = { folderName, languages, sttOverride ->
                showCreateFolderDialog = false
                onEvent(
                    LibraryEvent.CreateVirtualFolder(
                        folderName = folderName,
                        languages = languages,
                        sttOverride = sttOverride,
                    ),
                )
            },
            onDismiss = { showCreateFolderDialog = false },
            sttEngineByLanguage = state.sttEngineByLanguage,
            onOpenLanguageSettings = {
                onEvent(LibraryEvent.OpenLanguageSettings)
            },
        )
    }

    state.pendingEditItem?.let { itemToEdit ->
        EditItemMetadataDialog(
            item = itemToEdit,
            enabledLanguages = state.enabledLanguages,
            onToggleEnabledLanguage = { onEvent(LibraryEvent.ToggleEnabledLanguage(it)) },
            onConfirm = { course, languages, sttOverride ->
                onEvent(
                    LibraryEvent.ConfirmEditMetadata(
                        id = itemToEdit.id,
                        course = course,
                        languages = languages,
                        sttOverride = sttOverride,
                    ),
                )
            },
            onDismiss = { onEvent(LibraryEvent.CancelEditMetadata) },
            initialSttOverride = state.pendingEditItemSttOverride,
            sttEngineByLanguage = state.sttEngineByLanguage,
            onOpenLanguageSettings = { onEvent(LibraryEvent.OpenLanguageSettings) },
            onDelete = {
                onEvent(LibraryEvent.CancelEditMetadata)
                onEvent(LibraryEvent.RequestDelete(itemToEdit))
            },
        )
    }

    state.pendingEditDirectory?.let { directoryToEdit ->
        EditDirectoryMetadataDialog(
            directoryEdit = directoryToEdit,
            enabledLanguages = state.enabledLanguages,
            onToggleEnabledLanguage = { onEvent(LibraryEvent.ToggleEnabledLanguage(it)) },
            onConfirm = { newCourse, languages, sttOverride ->
                onEvent(
                    LibraryEvent.ConfirmEditDirectoryMetadata(
                        currentCourse = directoryToEdit.currentCourse,
                        newCourse = newCourse,
                        languages = languages,
                        sttOverride = sttOverride,
                    ),
                )
            },
            onDismiss = { onEvent(LibraryEvent.CancelEditDirectoryMetadata) },
            sttEngineByLanguage = state.sttEngineByLanguage,
            onOpenLanguageSettings = { onEvent(LibraryEvent.OpenLanguageSettings) },
            onDelete = directoryToEdit.currentCourse?.let { path ->
                {
                    onEvent(LibraryEvent.CancelEditDirectoryMetadata)
                    onEvent(LibraryEvent.RequestDeleteDirectory(path))
                }
            },
        )
    }

    if (state.showLanguageSettingsModal) {
        LanguageSettingsModalDialog(
            enabledLanguages = state.enabledLanguages.toSet(),
            onToggleLanguage = { language ->
                onEvent(LibraryEvent.ToggleEnabledLanguage(language))
            },
            onDismiss = { onEvent(LibraryEvent.DismissLanguageSettingsModal) },
            isFirstLaunchOnboarding = false,
        )
    }

    if (state.hasPendingDeleteConfirmation) {
        val pendingDirs = state.pendingDeleteDirectoryPaths
        val isFolderDeletion = pendingDirs.isNotEmpty()
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

            pendingDirs.size == 1 && state.selectedItemIds.isEmpty() -> {
                val dirDisplayName = pendingDirs.first().substringAfterLast('/')
                val targetCount = state.pendingDeleteTargetItems.size
                deleteDialogTitle = stringResource(R.string.library_delete_directory_dialog_title)
                deleteDialogMessage = stringResource(
                    R.string.library_delete_directory_dialog_message,
                    dirDisplayName,
                    targetCount,
                )
            }

            isFolderDeletion -> {
                val targetCount = state.pendingDeleteTargetItems.size
                deleteDialogTitle = stringResource(R.string.library_delete_directory_dialog_title)
                deleteDialogMessage = stringResource(
                    R.string.library_delete_selected_directories_dialog_message,
                    pendingDirs.size,
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
                Button(
                    onClick = { onEvent(LibraryEvent.ConfirmDelete) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(
                        text = stringResource(
                            if (isFolderDeletion) {
                                R.string.library_delete_directory_confirm_ok
                            } else {
                                R.string.library_delete_confirm
                            },
                        ),
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
