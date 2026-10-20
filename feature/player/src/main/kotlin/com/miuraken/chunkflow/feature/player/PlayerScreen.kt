package com.miuraken.chunkflow.feature.player

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.designsystem.component.playback.PlaybackControlStrip
import com.miuraken.chunkflow.core.designsystem.component.playback.SpeedSheetContent
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowExtendedColors
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.playback.PositionProvider
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * プレイヤー画面の Route。ViewModel から状態と [PositionProvider] を受け取り、[PlayerScreen] に渡す。
 */
@Composable
fun PlayerRoute(
    mediaId: MediaId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialChunkId: ChunkId? = null,
    onOpenPlaybackSettings: () -> Unit = {},
    onOpenLanguageSettings: () -> Unit = onOpenPlaybackSettings,
    onOpenTranscriptExport: (MediaId) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    LaunchedEffect(mediaId, initialChunkId) {
        viewModel.open(mediaId, initialChunkId)
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.close()
        }
    }
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var hasRecordAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    LaunchedEffect(hasRecordAudioPermission) {
        viewModel.onEvent(PlayerEvent.SetRecordAudioPermissionGranted(hasRecordAudioPermission))
    }
    var showRecordAudioRationaleDialog by rememberSaveable { mutableStateOf(false) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        hasRecordAudioPermission = isGranted
        viewModel.onEvent(PlayerEvent.SetRecordAudioPermissionGranted(isGranted))
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))
        if (isGranted) {
            val activeChunkId = state.activeChunkIndex
                ?.let { idx -> state.chunks.getOrNull(idx)?.id }
                ?: state.chunks.firstOrNull()?.id
            viewModel.onEvent(PlayerEvent.TriggerLookahead(activeChunkId))
        }
    }
    val detailedRecordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        hasRecordAudioPermission = isGranted
        viewModel.onEvent(PlayerEvent.SetRecordAudioPermissionGranted(isGranted))
    }
    val transcriptPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            viewModel.onEvent(PlayerEvent.ImportTranscript(uri))
        }
    }

    PlayerScreen(
        state = state,
        positionProvider = viewModel.positionProvider,
        onEvent = { event ->
            when {
                event is PlayerEvent.Back -> {
                    viewModel.onEvent(event)
                    onBack()
                }

                event is PlayerEvent.SetLookaheadEnabled && event.enabled && !state.lookaheadStatus.enabled -> {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.onEvent(event)
                    } else {
                        showRecordAudioRationaleDialog = true
                    }
                }

                else -> viewModel.onEvent(event)
            }
        },
        onOpenPlaybackSettings = onOpenPlaybackSettings,
        onOpenLanguageSettings = onOpenLanguageSettings,
        onPickTranscriptJson = {
            transcriptPickerLauncher.launch(
                arrayOf("*/*"),
            )
        },
        onOpenTranscriptExport = {
            val targetId = state.mediaId ?: mediaId
            onOpenTranscriptExport(targetId)
        },
        hasRecordAudioPermission = hasRecordAudioPermission,
        onRequestRecordAudioPermission = {
            detailedRecordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        modifier = modifier,
    )

    if (showRecordAudioRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showRecordAudioRationaleDialog = false },
            title = {
                Text(text = "音声認識のための権限について")
            },
            text = {
                Text(
                    text = "文字起こしには端末の音声認識機能を使用するため、Android の仕様により「録音（マイク）」へのアクセス権限が必要です。\n\n※このアプリ内でマイクを使った実際の録音は一切行われません。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRecordAudioRationaleDialog = false
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                ) {
                    Text(text = "次へ")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecordAudioRationaleDialog = false }) {
                    Text(text = "キャンセル")
                }
            },
        )
    }
}

/**
 * プレイヤー画面（状態を受け取って描くだけの Composable。§6.2、§7.5.2〜§7.5.6）。
 *
 * 1 ペイン（幅 600dp 未満）では、上から次の順に並べる。
 * 1. チャンクの表示に関する設定（言語・学習言語を隠す・読み）
 * 2. チャンクリスト（残りの高さをすべて使う。文字起こしの進捗はリストの下部に重ねて表示する）
 * 3. 波形（全体波形 + 拡大波形。自動ポーズ中はそのすぐ上にカウントダウンを出す）
 * 4. 選択範囲に関する操作（全選択解除・連結・再生位置で分割。拡大波形のすぐ下）
 * 5. 再生の操作（速度・リピート・再生設定と、再生ボタン類）
 *
 * 2 ペイン（幅 600dp 以上）では、左に波形・選択範囲の操作・再生の操作を、右にチャンクの表示設定とチャンクリストを置く。
 * 文字起こしの診断ログ（[TranscriptionDiagnosticsDialog]）は、右上の設定アイコンで開くファイル設定シートから開く。
 *
 * 再生位置は [PlayerUiState] に含めず、[positionProvider] を子 Composable（[WaveformSection]・[TranscriptList]）にのみ渡す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    positionProvider: PositionProvider,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlaybackSettings: () -> Unit = {},
    onOpenLanguageSettings: () -> Unit = onOpenPlaybackSettings,
    onPickTranscriptJson: () -> Unit = {},
    onOpenTranscriptExport: () -> Unit = {},
    hasRecordAudioPermission: Boolean = true,
    onRequestRecordAudioPermission: () -> Unit = {},
) {
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showResegmentSheet by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showFloatingControls by rememberSaveable { mutableStateOf(false) }
    var floatingOffsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var floatingOffsetY by rememberSaveable { mutableFloatStateOf(0f) }
    var isFloatingOffsetInitialized by rememberSaveable { mutableStateOf(false) }
    val seekBackSeconds = (state.seekBackMs / 1_000L).toInt().coerceAtLeast(1)
    val isAudioAnalysisRunning = state.analysis[AnalysisStage.FEATURES] is StageStatus.Running ||
        state.analysis[AnalysisStage.SEGMENTATION] is StageStatus.Running

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.title.ifEmpty { stringResource(R.string.player_title) },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        PlayerTitleMetaRow(
                            course = state.course,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { onEvent(PlayerEvent.Back) }) {
                        Icon(
                            imageVector = ChunkFlowIcons.ArrowBack,
                            contentDescription = stringResource(R.string.player_back),
                        )
                    }
                },
                actions = {
                    PlayerTranscriptionStatusBadge(
                        state = state,
                        onToggleTranscription = { enabled ->
                            onEvent(PlayerEvent.SetLookaheadEnabled(enabled))
                        },
                    )
                    IconButton(onClick = { showResegmentSheet = true }) {
                        Icon(
                            imageVector = ChunkFlowIcons.Tune,
                            contentDescription = stringResource(R.string.player_file_settings_cd),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val isTwoPane = maxWidth >= 600.dp

            if (isTwoPane) {
                Row(modifier = Modifier.fillMaxSize()) {
                    // 左ペイン: バナー → 波形 → 選択範囲に関する操作（拡大波形のすぐ下） → 再生の操作
                    Column(
                        modifier = Modifier
                            .weight(0.48f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        state.error?.let { error ->
                            PlayerErrorBanner(error = error)
                        }
                        state.autoPauseEndsAtMs?.let { endsAtMs ->
                            AutoPauseCountdownBanner(
                                autoPauseEndsAtMs = endsAtMs,
                                onResumeNow = { onEvent(PlayerEvent.TogglePlayPause) },
                            )
                        }

                        PlayerWaveform(
                            state = state,
                            positionProvider = positionProvider,
                            seekBackSeconds = seekBackSeconds,
                            onEvent = onEvent,
                        )

                        ChunkSelectionActionStrip(
                            state = state,
                            positionProvider = positionProvider,
                            onEvent = onEvent,
                        )

                        PlaybackControlStrip(
                            speed = state.speed,
                            customSpeed = state.customSpeed,
                            practice = state.practice,
                            onSetSpeed = { speed -> onEvent(PlayerEvent.SetSpeed(speed)) },
                            onOpenSpeedSheet = { showSpeedSheet = true },
                            onToggleRepeat = { onEvent(PlayerEvent.ToggleRepeat) },
                            onEnableWholeFileRepeat = { onEvent(PlayerEvent.EnableWholeFileRepeat) },
                            onToggleSkipSilence = { onEvent(PlayerEvent.ToggleSkipSilence) },
                            onOpenPlaybackSettings = onOpenPlaybackSettings,
                        )

                        PlayerTransportControls(
                            isPlaying = state.isPlaying,
                            seekBackMs = state.seekBackMs,
                            seekForwardMs = state.seekForwardMs,
                            hasChunks = state.chunks.isNotEmpty(),
                            hasPreviousCheckedChunk = state.hasPreviousCheckedChunk,
                            hasNextCheckedChunk = state.hasNextCheckedChunk,
                            hasNextMediaItem = state.nextMediaId != null,
                            onTogglePlayPause = { onEvent(PlayerEvent.TogglePlayPause) },
                            onSeekBack = { onEvent(PlayerEvent.SeekBack) },
                            onSeekForward = { onEvent(PlayerEvent.SeekForward) },
                            onPreviousChunk = { onEvent(PlayerEvent.PreviousChunk) },
                            onNextChunk = { onEvent(PlayerEvent.NextChunk) },
                            onSetSeekBackMs = { ms -> onEvent(PlayerEvent.SetSeekBackMs(ms)) },
                            onSetSeekForwardMs = { ms -> onEvent(PlayerEvent.SetSeekForwardMs(ms)) },
                            onSeekToFileStart = { onEvent(PlayerEvent.SeekToFileStart) },
                            onSeekToPreviousCheckedChunk = { onEvent(PlayerEvent.SeekToPreviousCheckedChunk) },
                            onSeekToNextCheckedChunk = { onEvent(PlayerEvent.SeekToNextCheckedChunk) },
                            onOpenNextMediaItem = { onEvent(PlayerEvent.OpenNextMediaItem) },
                            onToggleFloatingControls = { showFloatingControls = !showFloatingControls },
                        )
                    }

                    VerticalDivider()

                    // 右ペイン: チャンクの表示に関する設定 → チャンクリスト（文字起こしの進捗は下部に重ねる）
                    Column(
                        modifier = Modifier
                            .weight(0.52f)
                            .fillMaxHeight(),
                    ) {
                        ChunkDisplaySettingsStrip(
                            state = state,
                            onEvent = onEvent,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )

                        PlayerChunkList(
                            state = state,
                            positionProvider = positionProvider,
                            onEvent = onEvent,
                            hasRecordAudioPermission = hasRecordAudioPermission,
                            onRequestRecordAudioPermission = onRequestRecordAudioPermission,
                            onOpenFileSettings = { showResegmentSheet = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        )
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    state.error?.let { error ->
                        PlayerErrorBanner(
                            error = error,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }

                    // 1. チャンクの表示に関する設定（チャンクリストの上）
                    ChunkDisplaySettingsStrip(
                        state = state,
                        onEvent = onEvent,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )

                    // 2. チャンクリスト（残りの高さをすべて使う。文字起こしの進捗はリストの下部に重ねる）
                    PlayerChunkList(
                        state = state,
                        positionProvider = positionProvider,
                        onEvent = onEvent,
                        hasRecordAudioPermission = hasRecordAudioPermission,
                        onRequestRecordAudioPermission = onRequestRecordAudioPermission,
                        onOpenFileSettings = { showResegmentSheet = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )

                    // 3. 波形（自動ポーズ中は波形のすぐ上にカウントダウンを出す）→ 4. 選択範囲に関する操作（拡大波形のすぐ下）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.autoPauseEndsAtMs?.let { endsAtMs ->
                            AutoPauseCountdownBanner(
                                autoPauseEndsAtMs = endsAtMs,
                                onResumeNow = { onEvent(PlayerEvent.TogglePlayPause) },
                            )
                        }

                        PlayerWaveform(
                            state = state,
                            positionProvider = positionProvider,
                            seekBackSeconds = seekBackSeconds,
                            onEvent = onEvent,
                        )

                        ChunkSelectionActionStrip(
                            state = state,
                            positionProvider = positionProvider,
                            onEvent = onEvent,
                        )
                    }

                    // 5. 再生の操作（画面の下部）
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { showFloatingControls = !showFloatingControls },
                            ),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 2.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // b) 再生に関するボタン類（再生ボタン類のすぐ上部）
                            PlaybackControlStrip(
                                speed = state.speed,
                                customSpeed = state.customSpeed,
                                practice = state.practice,
                                onSetSpeed = { speed -> onEvent(PlayerEvent.SetSpeed(speed)) },
                                onOpenSpeedSheet = { showSpeedSheet = true },
                                onToggleRepeat = { onEvent(PlayerEvent.ToggleRepeat) },
                                onEnableWholeFileRepeat = { onEvent(PlayerEvent.EnableWholeFileRepeat) },
                                onToggleSkipSilence = { onEvent(PlayerEvent.ToggleSkipSilence) },
                                onOpenPlaybackSettings = onOpenPlaybackSettings,
                            )

                            PlayerTransportControls(
                                isPlaying = state.isPlaying,
                                seekBackMs = state.seekBackMs,
                                seekForwardMs = state.seekForwardMs,
                                hasChunks = state.chunks.isNotEmpty(),
                                hasPreviousCheckedChunk = state.hasPreviousCheckedChunk,
                                hasNextCheckedChunk = state.hasNextCheckedChunk,
                                hasNextMediaItem = state.nextMediaId != null,
                                onTogglePlayPause = { onEvent(PlayerEvent.TogglePlayPause) },
                                onSeekBack = { onEvent(PlayerEvent.SeekBack) },
                                onSeekForward = { onEvent(PlayerEvent.SeekForward) },
                                onPreviousChunk = { onEvent(PlayerEvent.PreviousChunk) },
                                onNextChunk = { onEvent(PlayerEvent.NextChunk) },
                                onSetSeekBackMs = { ms -> onEvent(PlayerEvent.SetSeekBackMs(ms)) },
                                onSetSeekForwardMs = { ms -> onEvent(PlayerEvent.SetSeekForwardMs(ms)) },
                                onSeekToFileStart = { onEvent(PlayerEvent.SeekToFileStart) },
                                onSeekToPreviousCheckedChunk = { onEvent(PlayerEvent.SeekToPreviousCheckedChunk) },
                                onSeekToNextCheckedChunk = { onEvent(PlayerEvent.SeekToNextCheckedChunk) },
                                onOpenNextMediaItem = { onEvent(PlayerEvent.OpenNextMediaItem) },
                                onToggleFloatingControls = { showFloatingControls = !showFloatingControls },
                            )
                        }
                    }
                }
            }

            if (showFloatingControls) {
                val density = LocalDensity.current
                val maxWidthPx = with(density) { maxWidth.toPx() }
                val maxHeightPx = with(density) { maxHeight.toPx() }
                var floatingSize by remember { mutableStateOf(IntSize.Zero) }
                if (!isFloatingOffsetInitialized && maxWidthPx > 0f && maxHeightPx > 0f) {
                    floatingOffsetX = with(density) { 12.dp.toPx() }
                    floatingOffsetY = (maxHeightPx * 0.45f).coerceAtLeast(0f)
                    isFloatingOffsetInitialized = true
                }
                val maxOffsetX = (maxWidthPx - floatingSize.width).coerceAtLeast(0f)
                val maxOffsetY = (maxHeightPx - floatingSize.height).coerceAtLeast(0f)
                val clampedX = floatingOffsetX.coerceIn(0f, maxOffsetX)
                val clampedY = floatingOffsetY.coerceIn(0f, maxOffsetY)

                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .offset { IntOffset(clampedX.roundToInt(), clampedY.roundToInt()) }
                        .widthIn(max = (maxWidth - 16.dp).coerceAtLeast(260.dp))
                        .fillMaxWidth(if (isTwoPane) 0.52f else 0.94f)
                        .onSizeChanged { floatingSize = it }
                        .pointerInput(maxOffsetX, maxOffsetY) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                floatingOffsetX = (floatingOffsetX + dragAmount.x).coerceIn(0f, maxOffsetX)
                                floatingOffsetY = (floatingOffsetY + dragAmount.y).coerceIn(0f, maxOffsetY)
                            }
                        },
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = ChunkFlowIcons.DragHandle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.player_floating_controls_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            IconButton(
                                onClick = { showFloatingControls = false },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = ChunkFlowIcons.Close,
                                    contentDescription = stringResource(R.string.player_floating_controls_close_cd),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }

                        PlayerTransportControls(
                            isPlaying = state.isPlaying,
                            seekBackMs = state.seekBackMs,
                            seekForwardMs = state.seekForwardMs,
                            hasChunks = state.chunks.isNotEmpty(),
                            hasPreviousCheckedChunk = state.hasPreviousCheckedChunk,
                            hasNextCheckedChunk = state.hasNextCheckedChunk,
                            hasNextMediaItem = state.nextMediaId != null,
                            onTogglePlayPause = { onEvent(PlayerEvent.TogglePlayPause) },
                            onSeekBack = { onEvent(PlayerEvent.SeekBack) },
                            onSeekForward = { onEvent(PlayerEvent.SeekForward) },
                            onPreviousChunk = { onEvent(PlayerEvent.PreviousChunk) },
                            onNextChunk = { onEvent(PlayerEvent.NextChunk) },
                            onSetSeekBackMs = { ms -> onEvent(PlayerEvent.SetSeekBackMs(ms)) },
                            onSetSeekForwardMs = { ms -> onEvent(PlayerEvent.SetSeekForwardMs(ms)) },
                            onSeekToFileStart = { onEvent(PlayerEvent.SeekToFileStart) },
                            onSeekToPreviousCheckedChunk = { onEvent(PlayerEvent.SeekToPreviousCheckedChunk) },
                            onSeekToNextCheckedChunk = { onEvent(PlayerEvent.SeekToNextCheckedChunk) },
                            onOpenNextMediaItem = { onEvent(PlayerEvent.OpenNextMediaItem) },
                            onToggleFloatingControls = { showFloatingControls = false },
                        )
                    }
                }
            }
        }
    }

    if (showSpeedSheet) {
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
            SpeedSheetContent(
                speed = state.customSpeed,
                presets = PlayerUiState.SPEED_PRESETS,
                closeLabel = stringResource(R.string.player_sheet_close),
                onSetSpeed = { speed -> onEvent(PlayerEvent.SetCustomSpeed(speed)) },
                onAdjustSpeed = { delta -> onEvent(PlayerEvent.AdjustCustomSpeed(delta)) },
                onClose = { showSpeedSheet = false },
            )
        }
    }

    if (showResegmentSheet) {
        ModalBottomSheet(onDismissRequest = { showResegmentSheet = false }) {
            ResegmentSheetContent(
                initialParams = state.segmentationParams,
                useGlobalSegmentation = state.useGlobalSegmentationParams,
                languagePair = state.languagePair,
                itemSttOverride = state.itemSttOverride,
                sttEngineByLanguage = state.sttEngineByLanguage,
                languageModelInfos = state.languageModelInfos,
                enabledLanguages = state.enabledLanguages,
                onChangeLanguages = { pair ->
                    onEvent(PlayerEvent.UpdateItemLanguages(target = pair.target, base = pair.base))
                },
                onToggleEnabledLanguage = { language ->
                    onEvent(PlayerEvent.ToggleEnabledLanguage(language))
                },
                onChangeTargetEngine = { engine ->
                    onEvent(PlayerEvent.SetTargetEngineOverride(engine))
                },
                onChangeBaseEngine = { engine ->
                    onEvent(PlayerEvent.SetBaseEngineOverride(engine))
                },
                transcriptOffsetMs = state.transcriptOffsetMs,
                onAdjustTranscriptOffset = { deltaMs ->
                    onEvent(PlayerEvent.AdjustTranscriptOffset(deltaMs))
                },
                onAutoAlignTranscriptOffset = {
                    onEvent(PlayerEvent.AutoAlignTranscriptOffset)
                },
                onImportTranscript = {
                    showResegmentSheet = false
                    onPickTranscriptJson()
                },
                onExportTranscript = {
                    showResegmentSheet = false
                    onOpenTranscriptExport()
                },
                onResetTranscripts = {
                    showResegmentSheet = false
                    onEvent(PlayerEvent.ResetTranscripts)
                },
                onOpenDiagnostics = {
                    showResegmentSheet = false
                    showDiagnosticsDialog = true
                },
                onToggleUseGlobalSegmentation = { useGlobal ->
                    onEvent(PlayerEvent.SetUseGlobalSegmentation(useGlobal))
                },
                onResetSegmentation = { customParams ->
                    onEvent(PlayerEvent.ResetChunkSegmentation(customParams))
                    showResegmentSheet = false
                },
                onApply = { params ->
                    onEvent(PlayerEvent.Resegment(params))
                    showResegmentSheet = false
                },
                onOpenGlobalSettings = {
                    showResegmentSheet = false
                    onOpenLanguageSettings()
                },
                onClose = { showResegmentSheet = false },
            )
        }
    }

    if (showDiagnosticsDialog) {
        TranscriptionDiagnosticsDialog(
            lookaheadStatus = state.lookaheadStatus,
            isAudioAnalysisRunning = isAudioAnalysisRunning,
            onResetTranscription = {
                onEvent(PlayerEvent.ResetTranscription)
                showDiagnosticsDialog = false
            },
            onDismiss = { showDiagnosticsDialog = false },
        )
    }

    state.backgroundSpeechRecognizerWarning?.let { warning ->
        BackgroundSpeechRecognizerWarningDialog(
            warning = warning,
            onDismiss = { onEvent(PlayerEvent.DismissBackgroundSpeechRecognizerWarning) },
        )
    }
}

/**
 * 再生画面 TopAppBar の設定アイコン左に表示する文字起こし進捗バッジ。
 *
 * - 全チャンクの文字起こしが完了している場合は、ライブラリ画面と同様に緑丸＋白抜きの文字起こしアイコンを表示する。
 * - 未完了の場合は `{文字起こしアイコン}{スピナー（実行中のみ）}{進捗率%}` をトグルバッジとして表示し、タップでオン（再開）/ オフ（一時停止）を切り替えられる。
 */
@Composable
private fun PlayerTranscriptionStatusBadge(
    state: PlayerUiState,
    onToggleTranscription: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.chunks.isEmpty() && !state.isTranscriptionRunning) return

    if (state.isAllTranscriptionCompleted) {
        val extendedColors = chunkFlowExtendedColors()
        Surface(
            color = extendedColors.transcriptCompleted,
            shape = CircleShape,
            modifier = modifier
                .size(22.dp)
                .semantics { contentDescription = "文字起こし完了" },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = ChunkFlowIcons.SpeechToText,
                    contentDescription = null,
                    tint = extendedColors.onTranscriptCompleted,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        return
    }

    val isRunning = state.isTranscriptionRunning
    val totalCount = if (state.chunks.isNotEmpty()) {
        state.chunks.size
    } else {
        state.lookaheadStatus.totalTargetCount
    }
    val completedCount = if (state.chunks.isNotEmpty()) {
        state.chunks.count { !it.text.isNullOrBlank() }
    } else {
        state.lookaheadStatus.completedCount
    }.coerceIn(0, totalCount.coerceAtLeast(0))
    val progressPercent = if (totalCount > 0) {
        ((completedCount * 100) / totalCount).coerceIn(0, 100)
    } else {
        0
    }
    val statusDescription = if (isRunning) {
        "文字起こし中 $progressPercent%（タップして一時停止）"
    } else {
        "文字起こし停止中 $progressPercent%（タップして再開）"
    }

    val badgeContainerColor = if (isRunning) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f)
    }
    val badgeContentColor = if (isRunning) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
    }
    val badgeBorder = if (isRunning) {
        null
    } else {
        FilterChipDefaults.filterChipBorder(enabled = true, selected = false)
    }

    Surface(
        shape = FilterChipDefaults.shape,
        color = badgeContainerColor,
        contentColor = badgeContentColor,
        border = badgeBorder,
        modifier = modifier
            .heightIn(min = FilterChipDefaults.Height)
            .clip(FilterChipDefaults.shape)
            .clickable { onToggleTranscription(!isRunning) }
            .semantics(mergeDescendants = true) {
                contentDescription = statusDescription
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ChunkFlowIcons.SpeechToText,
                contentDescription = null,
                tint = badgeContentColor,
                modifier = Modifier.size(18.dp),
            )
            if (isRunning) {
                if (LocalInspectionMode.current) {
                    CircularProgressIndicator(
                        progress = { 0.75f },
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = badgeContentColor,
                        trackColor = Color.Transparent,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = badgeContentColor,
                    )
                }
            }
            Text(
                text = "$progressPercent%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isRunning) FontWeight.SemiBold else FontWeight.Normal,
                color = badgeContentColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * [PlayerUiState] とイベントを [WaveformSection]（全体波形 + 拡大波形）につなぐ。1 ペインと 2 ペインで共通に使う。
 */
@Composable
private fun PlayerWaveform(
    state: PlayerUiState,
    positionProvider: PositionProvider,
    seekBackSeconds: Int,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    WaveformSection(
        waveform = state.waveform,
        durationMs = state.durationMs,
        isPlaying = state.isPlaying,
        chunks = state.chunks,
        activeChunkIndex = state.activeChunkIndex,
        languagePair = state.languagePair,
        loopMode = state.practice.loop,
        analysis = state.analysis,
        positionProvider = positionProvider,
        seekBackSeconds = seekBackSeconds,
        onSeekTo = { targetMs -> onEvent(PlayerEvent.SeekTo(targetMs)) },
        onDismissAbRangeAndSeek = { targetMs ->
            onEvent(PlayerEvent.DismissWaveformRangeAndSeek(targetMs))
        },
        onSeekToChunk = { chunkId -> onEvent(PlayerEvent.SeekToChunk(chunkId)) },
        onSetChunkSelected = { chunkId, selected ->
            onEvent(PlayerEvent.SetChunkSelected(chunkId, selected))
        },
        onSetAbRange = { startMs, endMs ->
            onEvent(PlayerEvent.SetLoopMode(LoopMode.AbRange(startMs, endMs)))
        },
        onPreviousChunk = { onEvent(PlayerEvent.PreviousChunk) },
        onNextChunk = { onEvent(PlayerEvent.NextChunk) },
        onSeekBack = { onEvent(PlayerEvent.SeekBack) },
        lookaheadStatus = state.lookaheadStatus,
        isTranscriptionRunning = state.isTranscriptionRunning,
        baseChunks = state.baseWaveformChunks,
        isOverviewWaveformRenderComplete = state.isOverviewWaveformRenderComplete,
        isZoomedWaveformRenderComplete = state.isZoomedWaveformRenderComplete,
        renderGeneration = state.waveformRenderGeneration,
        onOverviewWaveformRenderComplete = {
            onEvent(PlayerEvent.MarkOverviewWaveformRenderComplete)
        },
        onZoomedWaveformRenderComplete = {
            onEvent(PlayerEvent.MarkZoomedWaveformRenderComplete)
        },
        modifier = modifier,
    )
}

/**
 * [PlayerUiState] とイベントを [TranscriptList]（チャンクリスト）につなぐ。1 ペインと 2 ペインで共通に使う。
 */
@Composable
private fun PlayerChunkList(
    state: PlayerUiState,
    positionProvider: PositionProvider,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
    hasRecordAudioPermission: Boolean = true,
    onRequestRecordAudioPermission: () -> Unit = {},
    onOpenFileSettings: () -> Unit = {},
) {
    TranscriptList(
        chunks = state.chunks,
        activeChunkIndex = state.activeChunkIndex,
        autoFollowResetToken = state.autoFollowResetToken,
        languagePair = state.languagePair,
        hasSameTargetAndBaseLanguage = state.hasSameTargetAndBaseLanguage,
        isAllTranscriptionEmptyAfterCompletion = state.isAllTranscriptionEmptyAfterCompletion,
        onOpenFileSettings = onOpenFileSettings,
        hasRecordAudioPermission = hasRecordAudioPermission,
        onRequestRecordAudioPermission = onRequestRecordAudioPermission,
        loopMode = state.practice.loop,
        blindMode = state.blindMode,
        revealedChunkIds = state.revealedChunkIds,
        chineseScript = state.chineseScript,
        cantoneseScript = state.cantoneseScript,
        showTargetRomanization = state.showTargetRomanization,
        showTargetTranslation = state.showTargetTranslation,
        bookmarksOnly = state.practice.bookmarksOnly,
        transcriptImportBanner = state.transcriptImportBanner,
        positionProvider = positionProvider,
        lookaheadStatus = state.lookaheadStatus,
        showTranscriptionProgress = state.isTranscriptionProgressVisible,
        transcriptExportBanner = state.transcriptExportBanner,
        onDismissExportBanner = { onEvent(PlayerEvent.DismissExportBanner) },
        onToggleBookmark = { chunkId -> onEvent(PlayerEvent.ToggleBookmark(chunkId)) },
        onToggleRepeatChunk = { chunkId -> onEvent(PlayerEvent.ToggleChunkRepeat(chunkId)) },
        onSetChunksSkipped = { chunkIds, skipped ->
            onEvent(PlayerEvent.SetChunksSkipped(chunkIds, skipped))
        },
        onSeekToChunk = { chunkId -> onEvent(PlayerEvent.SeekToChunk(chunkId)) },
        onSetLoopMode = { loop -> onEvent(PlayerEvent.SetLoopMode(loop)) },
        onToggleRevealChunk = { chunkId -> onEvent(PlayerEvent.ToggleRevealChunk(chunkId)) },
        onDismissTranscriptBanner = { onEvent(PlayerEvent.DismissTranscriptBanner) },
        onUpdateChunkLanguage = { chunkId, lang ->
            onEvent(PlayerEvent.UpdateChunkLanguage(chunkId, lang))
        },
        onUpdateChunkText = { chunkId, text ->
            onEvent(PlayerEvent.UpdateChunkText(chunkId, text))
        },
        onRetranscribeChunk = { chunkId ->
            onEvent(PlayerEvent.RetranscribeChunk(chunkId))
        },
        onMoveChunkBoundary = { chunkId, newStartMs, newEndMs ->
            onEvent(PlayerEvent.MoveChunkBoundary(chunkId, newStartMs, newEndMs))
        },
        onDeleteChunk = { chunkId ->
            onEvent(PlayerEvent.DeleteChunk(chunkId))
        },
        onBulkSetUnknownLanguage = { lang ->
            onEvent(PlayerEvent.BulkSetUnknownTranscriptLanguage(lang))
        },
        onToggleBackgroundTranscription = { enabled ->
            onEvent(PlayerEvent.SetBackgroundTranscriptionEnabled(enabled))
        },
        onDismissTranscriptionProgress = {
            onEvent(PlayerEvent.ToggleTranscriptionProgress)
        },
        onResetTranscription = {
            onEvent(PlayerEvent.ResetTranscription)
        },
        onRequestRecognitionModelDownload = { language ->
            onEvent(PlayerEvent.RequestRecognitionModelDownload(language))
        },
        waveform = state.waveform,
        detailedTranscriptionDialog = state.detailedTranscriptionDialog,
        onOpenDetailedTranscription = { chunkId ->
            onEvent(PlayerEvent.OpenDetailedTranscription(chunkId))
        },
        onDismissDetailedTranscription = {
            onEvent(PlayerEvent.DismissDetailedTranscription)
        },
        onSelectDetailedTranscriptionLanguage = { language ->
            onEvent(PlayerEvent.SelectDetailedTranscriptionLanguage(language))
        },
        onSelectDetailedTranscriptionEngine = { engine ->
            onEvent(PlayerEvent.SelectDetailedTranscriptionEngine(engine))
        },
        onRequestDetailedTranscriptionModelDownload = { language, engine ->
            onEvent(PlayerEvent.RequestDetailedTranscriptionModelDownload(language, engine))
        },
        onPlayDetailedTranscriptionPreview = { chunkId, speed ->
            onEvent(PlayerEvent.PlayDetailedTranscriptionChunkPreview(chunkId, speed))
        },
        onStopDetailedTranscriptionPreview = {
            onEvent(PlayerEvent.StopDetailedTranscriptionChunkPreview)
        },
        onRunDetailedTranscriptionTrial = { chunkId, engine, languageOverride, audioSpeed ->
            onEvent(
                PlayerEvent.RunDetailedTranscriptionTrial(
                    chunkId = chunkId,
                    engine = engine,
                    languageOverride = languageOverride,
                    audioSpeed = audioSpeed,
                    hasRecordAudioPermission = hasRecordAudioPermission,
                ),
            )
        },
        onApplyDetailedTranscriptionResult = { chunkId, text, language ->
            onEvent(
                PlayerEvent.ApplyDetailedTranscriptionResult(
                    chunkId = chunkId,
                    text = text,
                    language = language,
                ),
            )
        },
        modifier = modifier,
    )
}

@Composable
private fun PlayerTitleMetaRow(
    course: String?,
) {
    if (course == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = course,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
