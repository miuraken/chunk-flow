package com.miuraken.chunkflow.feature.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.designsystem.util.formatCompactSpeed
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PhoneticGuideKind
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.SupportedLanguageCatalog
import com.miuraken.chunkflow.core.model.TranscriptExportFormat
import com.miuraken.chunkflow.core.model.TranscriptionStep
import com.miuraken.chunkflow.core.playback.PlaybackError
import com.miuraken.chunkflow.core.playback.PositionProvider
import com.miuraken.chunkflow.core.testing.fake.FakePositionProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterial
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterialPreviewParameterProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * プレイヤー画面の Route。ViewModel から状態と [PositionProvider] を受け取り、[PlayerScreen] に渡す。
 */
@Composable
fun PlayerRoute(
    mediaId: MediaId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlaybackSettings: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    LaunchedEffect(mediaId) {
        viewModel.open(mediaId)
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

    var pendingExportFormat by remember { mutableStateOf<TranscriptExportFormat?>(null) }
    val exportDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        val format = pendingExportFormat
        pendingExportFormat = null
        if (uri != null && format != null) {
            viewModel.onEvent(PlayerEvent.ExportTranscriptToUri(format = format, uri = uri))
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
        onPickTranscriptJson = {
            transcriptPickerLauncher.launch(
                arrayOf("*/*"),
            )
        },
        onRequestExportTranscript = { format ->
            pendingExportFormat = format
            val baseName = state.title.ifBlank { "transcript" }
            exportDocumentLauncher.launch("$baseName.${format.name.lowercase(Locale.ROOT)}")
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
    onPickTranscriptJson: () -> Unit = {},
    onRequestExportTranscript: (TranscriptExportFormat) -> Unit = {},
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
                            languagePair = state.languagePair,
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
                            state = state,
                            onEvent = onEvent,
                            onOpenSpeedSheet = { showSpeedSheet = true },
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
                                state = state,
                                onEvent = onEvent,
                                onOpenSpeedSheet = { showSpeedSheet = true },
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
                    shape = RoundedCornerShape(20.dp),
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
            PlayerSpeedSheetContent(
                speed = state.customSpeed,
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
                onExportTranscript = { format ->
                    showResegmentSheet = false
                    onRequestExportTranscript(format)
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
                onApply = { params ->
                    onEvent(PlayerEvent.Resegment(params))
                    showResegmentSheet = false
                },
                onOpenGlobalSettings = {
                    showResegmentSheet = false
                    onOpenPlaybackSettings()
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
    languagePair: LanguagePair?,
) {
    if (languagePair == null && course == null) return
    val langPalette = chunkFlowLanguagePalette()
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        languagePair?.let { pair ->
            val targetText = pair.target.value.lowercase(Locale.ROOT)
            val baseText = pair.base?.value?.lowercase(Locale.ROOT)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (baseText != null) {
                    Surface(
                        color = langPalette.baseBadgeContainer,
                        contentColor = langPalette.onBaseBadgeContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                    ) {
                        Text(
                            text = baseText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        text = "→",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Surface(
                    color = langPalette.targetBadgeContainer,
                    contentColor = langPalette.onTargetBadgeContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = targetText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
        }
        course?.let { courseName ->
            Text(
                text = courseName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * ON / OFF のトグルをボタンの明度で表現する [FilterChip] カラー設定。
 *
 * ON（`selected = true`）は明るく強調し、OFF（`selected = false`）は暗め（低明度・低不透明度）にする。
 */
@Composable
private fun brightnessToggleChipColors() = FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f),
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
    iconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f),
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
)

/**
 * 選択範囲に関する操作の行（1 ペイン・2 ペインとも、波形（拡大波形）のすぐ下に置く）。
 *
 * レイアウトが跳ねないよう 3 つのボタンを常に並べ、使えないときは無効（押せない見た目）にする。
 * 1 行に収まらないとき（文字サイズが大きいときや、2 ペインの左側が狭いとき）は、横にスクロールさせずに次の行へ折り返す。
 * 「再生位置で分割」が途中で切れて「再生位置」とだけ読めると、押したときの動作がわからなくなるため。
 * - 「全選択解除」: チャンクを 1 つ以上選択しているとき
 * - 「連結」: チャンクを 2 つ以上選択しているとき（[canMergeSelectedChunks]）。押すと確認ダイアログを表示する
 * - 「再生位置で分割」: 再生位置（拡大波形の中央のカーソル）がいずれかのチャンクの分割可能な範囲内（両端から [MIN_SPLIT_PIECE_MS] 以上内側）にあるとき（[canSplitChunkAtPosition]）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChunkSelectionActionStrip(
    state: PlayerUiState,
    positionProvider: PositionProvider,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasSelection = state.chunks.any { it.isRepeatChecked }
    val mergeSummary = remember(state.chunks) { summarizeMergeSelection(state.chunks) }
    val canMergeSelection = mergeSummary != null
    val isInspectionMode = LocalInspectionMode.current
    var isPositionInsideSplittableChunk by remember(state.chunks, positionProvider) {
        mutableStateOf(canSplitChunkAtPosition(state.chunks, positionProvider.currentPositionMs()))
    }
    if (!isInspectionMode) {
        LaunchedEffect(state.chunks, state.isPlaying, positionProvider) {
            while (isActive) {
                val insideSplitTarget =
                    canSplitChunkAtPosition(state.chunks, positionProvider.currentPositionMs())
                if (isPositionInsideSplittableChunk != insideSplitTarget) {
                    isPositionInsideSplittableChunk = insideSplitTarget
                }
                delay(if (state.isPlaying) 50L else 100L)
            }
        }
    }
    val canSplitAtCursor = isPositionInsideSplittableChunk
    var showMergeConfirmDialog by remember { mutableStateOf(false) }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AssistChip(
            onClick = { onEvent(PlayerEvent.ClearChunkSelection) },
            enabled = hasSelection,
            label = { Text(text = stringResource(R.string.player_selection_clear_all), maxLines = 1) },
        )
        AssistChip(
            onClick = { showMergeConfirmDialog = true },
            enabled = canMergeSelection,
            label = { Text(text = stringResource(R.string.player_selection_merge), maxLines = 1) },
        )
        AssistChip(
            onClick = { onEvent(PlayerEvent.SplitChunkAtCurrentPosition) },
            enabled = canSplitAtCursor,
            label = { Text(text = stringResource(R.string.player_split_at_cursor), maxLines = 1) },
        )
    }

    val currentSummary = mergeSummary
    if (showMergeConfirmDialog && currentSummary != null) {
        val rawLangCode = currentSummary.targetLanguage?.value?.trim()?.takeUnless {
            it.isEmpty() || it.equals("und", ignoreCase = true)
        }?.lowercase(Locale.ROOT)
        val langLabel = rawLangCode ?: stringResource(R.string.player_merge_lang_unknown)
        val confirmMessage = if (currentSummary.unselectedGapCount > 0) {
            stringResource(
                R.string.player_merge_confirm_message_with_gaps,
                currentSummary.firstChunkNumber,
                currentSummary.lastChunkNumber,
                currentSummary.unselectedGapCount,
                currentSummary.totalSpanCount,
                langLabel,
            )
        } else {
            stringResource(
                R.string.player_merge_confirm_message_contiguous,
                currentSummary.firstChunkNumber,
                currentSummary.lastChunkNumber,
                currentSummary.totalSpanCount,
                langLabel,
            )
        }

        AlertDialog(
            onDismissRequest = { showMergeConfirmDialog = false },
            title = {
                Text(text = stringResource(R.string.player_merge_confirm_title))
            },
            text = {
                Text(text = confirmMessage)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMergeConfirmDialog = false
                        onEvent(PlayerEvent.MergeSelectedChunks)
                    },
                ) {
                    Text(text = stringResource(R.string.player_merge_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showMergeConfirmDialog = false }) {
                    Text(text = stringResource(R.string.player_chunk_edit_cancel))
                }
            },
        )
    }
}

@Composable
private fun LanguageCodePill(
    code: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier,
    ) {
        Text(
            text = code,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/**
 * チャンクの表示に関する設定の行（チャンクリストの上に置く）。
 *
 * 表示項目:
 * - 「言語」（`[base][target]`、`[base]`、`[target]`）
 * - 「{target}を隠す」（on, *off）
 * - 「読み」（on, *off）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChunkDisplaySettingsStrip(
    state: PlayerUiState,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var langFilterMenuExpanded by remember { mutableStateOf(false) }

    val langPalette = chunkFlowLanguagePalette()
    val targetLangCode = state.languagePair?.target?.value?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase(Locale.ROOT)
    val baseLangCode = state.languagePair?.base?.value?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.lowercase(Locale.ROOT)

    val languageFilter = state.practice.languageFilter
    val isHideTargetLangOn = state.blindMode != BlindMode.OFF
    val isReadingOn = state.showTargetRomanization
    val toggleColors = brightnessToggleChipColors()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 0. 「文字起こし」（未完了または実行中のみ表示）
        if (state.isTranscriptionBadgeVisible) {
            val isRunning = state.isTranscriptionRunning
            val baseChipLabel = stringResource(R.string.player_chip_transcribe)
            val transcribedCount = state.chunks.count { !it.text.isNullOrBlank() }
            val chipLabel = if (!isRunning && state.chunks.isNotEmpty() &&
                (transcribedCount > 0 || state.isAllTranscriptionEmptyAfterCompletion || state.lookaheadStatus.skippedEmptyChunkIds.isNotEmpty())
            ) {
                "$baseChipLabel $transcribedCount/${state.chunks.size}"
            } else {
                baseChipLabel
            }
            FilterChip(
                selected = isRunning,
                onClick = {
                    if (isRunning) {
                        onEvent(PlayerEvent.ToggleTranscriptionProgress)
                    } else {
                        onEvent(PlayerEvent.SetLookaheadEnabled(true))
                    }
                },
                leadingIcon = if (isRunning) {
                    {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    null
                },
                label = {
                    Text(
                        text = chipLabel,
                        fontWeight = if (isRunning) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                },
                colors = toggleColors,
            )
        }

        // 1. 「言語」（[base][target]、[base]、[target]）
        val transcriptChipModifier = Modifier.alpha(if (state.hasTranscript) 1f else 0.28f)
        val isLanguageFilterEnabled = state.hasTranscript || state.hasChunkLanguage
        val isLangFilterSelected = languageFilter != LanguageFilter.ALL
        val langFilterContainerColor = if (isLangFilterSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f)
        }
        val langFilterBorder = if (isLangFilterSelected) {
            null
        } else {
            FilterChipDefaults.filterChipBorder(enabled = isLanguageFilterEnabled, selected = false)
        }
        val baseDisplayCode = baseLangCode ?: stringResource(R.string.player_settings_base_lang_label)
        val targetDisplayCode = targetLangCode ?: stringResource(R.string.player_settings_target_lang_label)
        Column {
            Surface(
                shape = FilterChipDefaults.shape,
                color = langFilterContainerColor,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                border = langFilterBorder,
                modifier = Modifier
                    .alpha(if (isLanguageFilterEnabled) 1f else 0.28f)
                    .heightIn(min = FilterChipDefaults.Height)
                    .clip(FilterChipDefaults.shape)
                    .combinedClickable(
                        enabled = isLanguageFilterEnabled,
                        onClick = { langFilterMenuExpanded = true },
                        onLongClick = { langFilterMenuExpanded = true },
                    ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (languageFilter) {
                        LanguageFilter.ALL -> {
                            if (baseLangCode != null) {
                                LanguageCodePill(
                                    code = baseLangCode,
                                    containerColor = langPalette.baseBadgeContainer,
                                    contentColor = langPalette.onBaseBadgeContainer,
                                )
                            }
                            if (targetLangCode != null) {
                                LanguageCodePill(
                                    code = targetLangCode,
                                    containerColor = langPalette.targetBadgeContainer,
                                    contentColor = langPalette.onTargetBadgeContainer,
                                )
                            }
                            if (baseLangCode == null && targetLangCode == null) {
                                Text(
                                    text = stringResource(R.string.player_chip_lang_filter_all),
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                )
                            }
                        }

                        LanguageFilter.BASE_ONLY -> {
                            LanguageCodePill(
                                code = baseDisplayCode,
                                containerColor = langPalette.baseBadgeContainer,
                                contentColor = langPalette.onBaseBadgeContainer,
                            )
                        }

                        LanguageFilter.TARGET_ONLY -> {
                            LanguageCodePill(
                                code = targetDisplayCode,
                                containerColor = langPalette.targetBadgeContainer,
                                contentColor = langPalette.onTargetBadgeContainer,
                            )
                        }
                    }
                }
            }
            DropdownMenu(
                expanded = langFilterMenuExpanded,
                onDismissRequest = { langFilterMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (baseLangCode != null) {
                                LanguageCodePill(
                                    code = baseLangCode,
                                    containerColor = langPalette.baseBadgeContainer,
                                    contentColor = langPalette.onBaseBadgeContainer,
                                )
                            }
                            LanguageCodePill(
                                code = targetDisplayCode,
                                containerColor = langPalette.targetBadgeContainer,
                                contentColor = langPalette.onTargetBadgeContainer,
                            )
                            Text(
                                text = stringResource(R.string.player_menu_lang_filter_both_play),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        }
                    },
                    onClick = {
                        langFilterMenuExpanded = false
                        onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.ALL))
                    },
                )
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LanguageCodePill(
                                code = baseDisplayCode,
                                containerColor = langPalette.baseBadgeContainer,
                                contentColor = langPalette.onBaseBadgeContainer,
                            )
                            Text(
                                text = stringResource(R.string.player_menu_lang_filter_only_play),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        }
                    },
                    onClick = {
                        langFilterMenuExpanded = false
                        onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.BASE_ONLY))
                    },
                )
                DropdownMenuItem(
                    text = {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LanguageCodePill(
                                code = targetDisplayCode,
                                containerColor = langPalette.targetBadgeContainer,
                                contentColor = langPalette.onTargetBadgeContainer,
                            )
                            Text(
                                text = stringResource(R.string.player_menu_lang_filter_only_play),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        }
                    },
                    onClick = {
                        langFilterMenuExpanded = false
                        onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.TARGET_ONLY))
                    },
                )
            }
        }

        // 2. 「{target_lang_code}を隠す」（on, *off）
        val blindChipLabel = if (targetLangCode != null) {
            stringResource(R.string.player_chip_blind_target_code, targetLangCode)
        } else {
            stringResource(R.string.player_chip_blind_target)
        }
        FilterChip(
            selected = isHideTargetLangOn,
            enabled = state.hasTranscript,
            onClick = {
                val nextBlindMode = if (isHideTargetLangOn) {
                    BlindMode.OFF
                } else {
                    BlindMode.HIDE_TARGET_ONLY
                }
                onEvent(PlayerEvent.SetBlindMode(nextBlindMode))
            },
            label = {
                Text(
                    text = blindChipLabel,
                    fontWeight = if (isHideTargetLangOn) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            },
            colors = toggleColors,
            modifier = transcriptChipModifier,
        )

        // 3. 「読み」（on, *off）
        FilterChip(
            selected = isReadingOn,
            enabled = state.hasTranscript,
            onClick = {
                onEvent(PlayerEvent.SetShowTargetRomanization(!isReadingOn))
            },
            label = {
                Text(
                    text = stringResource(R.string.player_chip_reading),
                    fontWeight = if (isReadingOn) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            },
            colors = toggleColors,
            modifier = transcriptChipModifier,
        )

        // 4. 「翻訳」（on, *off）
        if (state.isTranslationSupported) {
            FilterChip(
                selected = state.showTargetTranslation,
                enabled = state.hasTranscript,
                onClick = {
                    onEvent(PlayerEvent.SetShowTargetTranslation(!state.showTargetTranslation))
                },
                label = {
                    Text(
                        text = stringResource(R.string.player_chip_translate),
                        fontWeight = if (state.showTargetTranslation) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                    )
                },
                colors = toggleColors,
                modifier = transcriptChipModifier,
            )
        }
    }
}

/**
 * b) 再生に関するボタン列（下部の再生ボタン類のすぐ上部に配置）。
 *
 * 表示項目:
 * - 「1x」（通常再生速度）
 * - 「{指定速度}」（短押しで指定速度に切り替え、長押しで速度設定シートを開く）
 * - 「リピート」（on, *off）
 * - 「再生設定」（全体設定の再生設定タブへ移動）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaybackControlStrip(
    state: PlayerUiState,
    onEvent: (PlayerEvent) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlaybackSettings: () -> Unit = {},
) {
    val isNormalSpeed = abs(state.speed - 1.0f) < 0.01f
    val isCustomSpeedSelected = !isNormalSpeed
    val isRepeatOn = state.practice.loop !is LoopMode.Off || state.practice.repeatCheckedOnly
    val toggleColors = brightnessToggleChipColors()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. 「1x」（通常再生速度）
        FilterChip(
            selected = isNormalSpeed,
            onClick = { onEvent(PlayerEvent.SetSpeed(1.0f)) },
            label = {
                Text(
                    text = "1x",
                    fontWeight = if (isNormalSpeed) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            },
            colors = toggleColors,
        )

        // 2. 「{指定速度}」（短押しで指定速度に切り替え、長押しで速度設定シートを開く）
        val customChipContainerColor = if (isCustomSpeedSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.55f)
        }
        val customChipContentColor = if (isCustomSpeedSelected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
        }
        val customChipBorder = if (isCustomSpeedSelected) {
            null
        } else {
            FilterChipDefaults.filterChipBorder(enabled = true, selected = false)
        }
        Surface(
            shape = FilterChipDefaults.shape,
            color = customChipContainerColor,
            contentColor = customChipContentColor,
            border = customChipBorder,
            modifier = Modifier
                .heightIn(min = FilterChipDefaults.Height)
                .clip(FilterChipDefaults.shape)
                .combinedClickable(
                    onClick = { onEvent(PlayerEvent.SetSpeed(state.customSpeed)) },
                    onLongClick = onOpenSpeedSheet,
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatCompactSpeed(state.customSpeed),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isCustomSpeedSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }

        // 2. 「リピート」（on, *off）
        FilterChip(
            selected = isRepeatOn,
            onClick = { onEvent(PlayerEvent.ToggleRepeat) },
            label = {
                Text(
                    text = stringResource(R.string.player_chip_repeat),
                    fontWeight = if (isRepeatOn) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            },
            colors = toggleColors,
        )

        // 3. 「再生設定」
        AssistChip(
            onClick = onOpenPlaybackSettings,
            label = {
                Text(
                    text = stringResource(R.string.player_chip_playback_settings),
                    maxLines = 1,
                )
            },
        )
    }
}

@Composable
private fun AutoPauseCountdownBanner(
    autoPauseEndsAtMs: Long,
    onResumeNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isInspectionMode = LocalInspectionMode.current
    var remainingMs by remember(autoPauseEndsAtMs) {
        val initial = if (isInspectionMode) {
            2_400L
        } else {
            (autoPauseEndsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }
        mutableLongStateOf(initial)
    }
    if (!isInspectionMode) {
        LaunchedEffect(autoPauseEndsAtMs) {
            while (isActive) {
                remainingMs = (autoPauseEndsAtMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                delay(100L)
            }
        }
    }

    val secondsStr = String.format(Locale.ROOT, "%.1f", remainingMs / 1_000f)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ChunkFlowIcons.Timer,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.player_autopause_active_banner, secondsStr),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onResumeNow) {
                Text(text = stringResource(R.string.player_autopause_resume_now))
            }
        }
    }
}

@Composable
private fun PlayerErrorBanner(
    error: PlaybackError,
    modifier: Modifier = Modifier,
) {
    val errorText = when (error) {
        PlaybackError.SOURCE_MISSING -> stringResource(R.string.player_error_source_missing)
        PlaybackError.DECODER -> stringResource(R.string.player_error_decoder)
        PlaybackError.UNKNOWN -> stringResource(R.string.player_error_unknown)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Text(
            text = errorText,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private enum class TransportOverlayMenu {
    SEEK_BACK,
    PREVIOUS,
    NEXT,
    SEEK_FORWARD,
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransportTonalButton(
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    contentDescriptionText: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Surface(
        shape = ButtonDefaults.filledTonalShape,
        color = containerColor,
        contentColor = contentColor,
        modifier = modifier
            .widthIn(min = 60.dp)
            .heightIn(min = 58.dp)
            .clip(ButtonDefaults.filledTonalShape)
            .combinedClickable(
                enabled = enabled,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = contentDescriptionText },
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerTransportControls(
    isPlaying: Boolean,
    seekBackMs: Long,
    seekForwardMs: Long,
    hasChunks: Boolean,
    hasPreviousCheckedChunk: Boolean,
    hasNextCheckedChunk: Boolean,
    hasNextMediaItem: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    onSetSeekBackMs: (Long) -> Unit,
    onSetSeekForwardMs: (Long) -> Unit,
    onSeekToFileStart: () -> Unit,
    onSeekToPreviousCheckedChunk: () -> Unit,
    onSeekToNextCheckedChunk: () -> Unit,
    onOpenNextMediaItem: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleFloatingControls: () -> Unit = {},
) {
    var activeOverlay by remember { mutableStateOf<TransportOverlayMenu?>(null) }
    val seekBackSeconds = (seekBackMs / 1_000L).coerceAtLeast(1L)
    val seekForwardSeconds = (seekForwardMs / 1_000L).coerceAtLeast(1L)
    val seekBackCd = stringResource(R.string.player_seek_back_cd, seekBackSeconds)
    val seekForwardCd = stringResource(R.string.player_seek_forward_cd, seekForwardSeconds)
    val prevChunkCd = stringResource(R.string.player_prev_chunk_cd)
    val nextChunkCd = stringResource(R.string.player_next_chunk_cd)
    val playPauseCd = if (isPlaying) {
        stringResource(R.string.player_pause)
    } else {
        stringResource(R.string.player_play)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            TransportTonalButton(
                onClick = onSeekBack,
                onLongClick = { activeOverlay = TransportOverlayMenu.SEEK_BACK },
                contentDescriptionText = seekBackCd,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = ChunkFlowIcons.SeekBack,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.player_seek_back_label, seekBackSeconds),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(
                expanded = activeOverlay == TransportOverlayMenu.SEEK_BACK,
                onDismissRequest = { activeOverlay = null },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerUiState.SEEK_BACK_PRESETS_MS.forEach { presetMs ->
                        val seconds = (presetMs / 1_000L).toInt()
                        val isSelected = seekBackMs == presetMs
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                activeOverlay = null
                                onSetSeekBackMs(presetMs)
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.player_seek_preset_seconds_format, seconds),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            TransportTonalButton(
                onClick = onPreviousChunk,
                onLongClick = { activeOverlay = TransportOverlayMenu.PREVIOUS },
                enabled = hasChunks,
                contentDescriptionText = prevChunkCd,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = ChunkFlowIcons.SkipPrevious,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.player_prev_chunk_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(
                expanded = activeOverlay == TransportOverlayMenu.PREVIOUS,
                onDismissRequest = { activeOverlay = null },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            activeOverlay = null
                            onSeekToFileStart()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.player_long_press_file_start),
                            maxLines = 1,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            activeOverlay = null
                            onSeekToPreviousCheckedChunk()
                        },
                        enabled = hasPreviousCheckedChunk,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.player_long_press_prev_checked),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onTogglePlayPause,
                    onLongClick = onToggleFloatingControls,
                )
                .semantics {
                    role = Role.Button
                    contentDescription = playPauseCd
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (isPlaying) ChunkFlowIcons.Pause else ChunkFlowIcons.Play,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            TransportTonalButton(
                onClick = { if (hasChunks) onNextChunk() },
                onLongClick = { activeOverlay = TransportOverlayMenu.NEXT },
                enabled = hasChunks || hasNextMediaItem,
                contentDescriptionText = nextChunkCd,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = ChunkFlowIcons.SkipNext,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.player_next_chunk_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(
                expanded = activeOverlay == TransportOverlayMenu.NEXT,
                onDismissRequest = { activeOverlay = null },
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            activeOverlay = null
                            onSeekToNextCheckedChunk()
                        },
                        enabled = hasNextCheckedChunk,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.player_long_press_next_checked),
                            maxLines = 1,
                        )
                    }
                    FilledTonalButton(
                        onClick = {
                            activeOverlay = null
                            onOpenNextMediaItem()
                        },
                        enabled = hasNextMediaItem,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.player_long_press_next_file),
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            TransportTonalButton(
                onClick = onSeekForward,
                onLongClick = { activeOverlay = TransportOverlayMenu.SEEK_FORWARD },
                contentDescriptionText = seekForwardCd,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = ChunkFlowIcons.SeekForward,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(
                        text = stringResource(R.string.player_seek_forward_label, seekForwardSeconds),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            DropdownMenu(
                expanded = activeOverlay == TransportOverlayMenu.SEEK_FORWARD,
                onDismissRequest = { activeOverlay = null },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlayerUiState.SEEK_FORWARD_PRESETS_MS.forEach { presetMs ->
                        val seconds = (presetMs / 1_000L).toInt()
                        val isSelected = seekForwardMs == presetMs
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                activeOverlay = null
                                onSetSeekForwardMs(presetMs)
                            },
                            label = {
                                Text(
                                    text = stringResource(R.string.player_seek_preset_seconds_format, seconds),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlayerSpeedSheetContent(
    speed: Float,
    onSetSpeed: (Float) -> Unit,
    onAdjustSpeed: (Float) -> Unit,
    onClose: () -> Unit,
) {
    val decreaseCd = stringResource(R.string.player_speed_decrease_cd)
    val increaseCd = stringResource(R.string.player_speed_increase_cd)
    val sliderCd = stringResource(R.string.player_speed_slider_cd)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.player_speed_section_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = formatCompactSpeed(speed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onAdjustSpeed(-PlayerUiState.SPEED_STEP) },
                enabled = speed > ItemState.MIN_SPEED + 0.001f,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = decreaseCd },
            ) {
                Text(text = "-0.05x")
            }
            OutlinedButton(
                onClick = { onAdjustSpeed(PlayerUiState.SPEED_STEP) },
                enabled = speed < ItemState.MAX_SPEED - 0.001f,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = increaseCd },
            ) {
                Text(text = "+0.05x")
            }
        }

        Slider(
            value = speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED),
            onValueChange = onSetSpeed,
            valueRange = ItemState.MIN_SPEED..ItemState.MAX_SPEED,
            steps = 29,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = sliderCd },
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PlayerUiState.SPEED_PRESETS.forEach { preset ->
                val isSelected = abs(speed - preset) < 0.01f
                val labelText = formatCompactSpeed(preset)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSetSpeed(preset) },
                    label = { Text(text = labelText) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onClose) {
                Text(text = stringResource(R.string.player_sheet_close))
            }
        }
    }
}

@Composable
private fun ResegmentSheetContent(
    initialParams: SegmentationParams,
    useGlobalSegmentation: Boolean,
    languagePair: LanguagePair?,
    itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
    sttEngineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
    languageModelInfos: Map<LanguageTag, com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo>,
    enabledLanguages: List<com.miuraken.chunkflow.core.model.LanguageTag>,
    onChangeLanguages: (LanguagePair) -> Unit,
    onChangeTargetEngine: (com.miuraken.chunkflow.core.model.SttEngine?) -> Unit,
    onChangeBaseEngine: (com.miuraken.chunkflow.core.model.SttEngine?) -> Unit,
    onToggleEnabledLanguage: (com.miuraken.chunkflow.core.model.LanguageTag) -> Unit = {},
    transcriptOffsetMs: Int,
    onAdjustTranscriptOffset: (Int) -> Unit,
    onAutoAlignTranscriptOffset: () -> Unit,
    onImportTranscript: () -> Unit,
    onExportTranscript: (TranscriptExportFormat) -> Unit,
    onResetTranscripts: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onToggleUseGlobalSegmentation: (Boolean) -> Unit,
    onApply: (SegmentationParams) -> Unit,
    onOpenGlobalSettings: () -> Unit,
    onClose: () -> Unit,
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    var minSilenceMs by remember(initialParams) { mutableIntStateOf(initialParams.minSilenceMs) }
    var prePaddingMs by remember(initialParams) { mutableIntStateOf(initialParams.prePaddingMs) }
    var postPaddingMs by remember(initialParams) { mutableIntStateOf(initialParams.postPaddingMs) }
    var thresholdRatio by remember(initialParams) { mutableFloatStateOf(initialParams.thresholdRatio) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    val formattedOffset = if (transcriptOffsetMs >= 0) {
        "+$transcriptOffsetMs"
    } else {
        transcriptOffsetMs.toString()
    }

    val tabTitles = listOf(
        stringResource(R.string.player_file_settings_tab_language),
        stringResource(R.string.player_file_settings_tab_transcript_file),
        stringResource(R.string.player_file_settings_tab_segmentation),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.player_file_settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onClose) {
                Text(text = stringResource(R.string.player_sheet_close))
            }
        }

        PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        when (selectedTabIndex) {
            0 -> {
                // 「言語」タブ: 学習言語、ベース言語とそれぞれの文字起こしエンジン
                ItemLanguageSettingsSection(
                    languagePair = languagePair,
                    itemSttOverride = itemSttOverride,
                    onChangeLanguages = onChangeLanguages,
                    onChangeTargetEngine = onChangeTargetEngine,
                    onChangeBaseEngine = onChangeBaseEngine,
                    enabledLanguages = enabledLanguages,
                    onToggleEnabledLanguage = onToggleEnabledLanguage,
                    sttEngineByLanguage = sttEngineByLanguage,
                    languageModelInfos = languageModelInfos,
                    showSectionTitle = false,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedButton(
                    onClick = onOpenGlobalSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Tune,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.player_open_global_settings))
                }
            }

            1 -> {
                // 「文字起こしファイル」タブ: エクスポート / インポート / リセット / ログ
                // 1. エクスポート
                Text(
                    text = stringResource(R.string.player_file_settings_section_export),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onExportTranscript(TranscriptExportFormat.SRT) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.player_export_srt),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    OutlinedButton(
                        onClick = { onExportTranscript(TranscriptExportFormat.LRC) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.player_export_lrc),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    OutlinedButton(
                        onClick = { onExportTranscript(TranscriptExportFormat.TXT) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.player_export_txt),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 2. インポート（文字起こし・字幕ファイルのインポート + 時刻補正）
                Text(
                    text = stringResource(R.string.player_file_settings_section_import),
                    style = MaterialTheme.typography.titleMedium,
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
                    Text(text = stringResource(R.string.player_transcript_file_import))
                }
                Text(
                    text = stringResource(R.string.player_offset_section_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.player_offset_current_value, formattedOffset),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(-50, -10, 10, 50).forEach { delta ->
                        val label = if (delta > 0) "+${delta}ms" else "${delta}ms"
                        OutlinedButton(
                            onClick = { onAdjustTranscriptOffset(delta) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        ) {
                            Text(text = label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                FilledTonalButton(
                    onClick = onAutoAlignTranscriptOffset,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.player_offset_auto_align))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 3. リセット
                Text(
                    text = stringResource(R.string.player_file_settings_section_reset),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = { showResetConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.player_transcript_reset_button))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 4. ログ
                Text(
                    text = stringResource(R.string.player_file_settings_section_log),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Description,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.player_transcription_diagnostics_button))
                }
            }

            2 -> {
                // 「チャンク分割」タブ: 全体設定に従うチェックボックス + 個別調整スライダー
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleUseGlobalSegmentation(!useGlobalSegmentation) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = useGlobalSegmentation,
                        onCheckedChange = { checked -> onToggleUseGlobalSegmentation(checked) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.player_segmentation_use_global),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }

                val slidersEnabled = !useGlobalSegmentation

                Text(
                    text = stringResource(R.string.player_resegment_min_silence, minSilenceMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (slidersEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
                Slider(
                    value = minSilenceMs.toFloat(),
                    onValueChange = { minSilenceMs = ((it / 50f).roundToInt() * 50).coerceIn(200, 1_500) },
                    valueRange = 200f..1_500f,
                    enabled = slidersEnabled,
                )

                Text(
                    text = stringResource(R.string.player_resegment_pre_padding, prePaddingMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (slidersEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
                Slider(
                    value = prePaddingMs.toFloat(),
                    onValueChange = { prePaddingMs = ((it / 25f).roundToInt() * 25).coerceIn(0, 500) },
                    valueRange = 0f..500f,
                    enabled = slidersEnabled,
                )

                Text(
                    text = stringResource(R.string.player_resegment_post_padding, postPaddingMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (slidersEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
                Slider(
                    value = postPaddingMs.toFloat(),
                    onValueChange = { postPaddingMs = ((it / 25f).roundToInt() * 25).coerceIn(0, 500) },
                    valueRange = 0f..500f,
                    enabled = slidersEnabled,
                )

                Text(
                    text = stringResource(
                        R.string.player_resegment_threshold,
                        String.format(Locale.ROOT, "%.2f", thresholdRatio),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (slidersEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
                Slider(
                    value = thresholdRatio,
                    onValueChange = { thresholdRatio = ((it * 20f).roundToInt() / 20f).coerceIn(0.05f, 0.80f) },
                    valueRange = 0.05f..0.80f,
                    enabled = slidersEnabled,
                )

                Button(
                    onClick = {
                        val updatedMergeGap = max(initialParams.mergeGapMs, minSilenceMs)
                        onApply(
                            initialParams.copy(
                                minSilenceMs = minSilenceMs,
                                mergeGapMs = updatedMergeGap,
                                prePaddingMs = prePaddingMs,
                                postPaddingMs = postPaddingMs,
                                thresholdRatio = thresholdRatio,
                            ),
                        )
                    },
                    enabled = slidersEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.player_resegment_apply))
                }
            }
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = {
                Text(text = stringResource(R.string.player_transcript_reset_confirm_title))
            },
            text = {
                Text(text = stringResource(R.string.player_transcript_reset_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmDialog = false
                        onResetTranscripts()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(text = stringResource(R.string.player_transcript_reset_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text(text = stringResource(R.string.player_chunk_edit_cancel))
                }
            },
        )
    }
}

/**
 * 言語タグから表示用の言語名（例: `韓国語 (ko)`）を返す。
 */
internal fun formatLanguageDisplayName(language: LanguageTag): String {
    val entry = SupportedLanguageCatalog.findByCode(language.value)
    return if (entry != null) {
        "${entry.japaneseName} (${language.value})"
    } else {
        language.value
    }
}

/**
 * バックグラウンド文字起こし有効化時の `SpeechRecognizer` 制限警告ダイアログの本文を組み立てる。
 */
internal fun formatBackgroundSpeechRecognizerWarningMessage(
    warning: BackgroundSpeechRecognizerWarningState,
): String {
    val langLabel = warning.speechRecognizerLanguages.joinToString("・") {
        formatLanguageDisplayName(it)
    }
    return buildString {
        append("${langLabel}では標準の音声認識（SpeechRecognizer）が使用されるため、バックグラウンドでは処理が進まない可能性があります。")
        if (warning.sherpaSupportedLanguages.isNotEmpty()) {
            val sherpaLangLabel = warning.sherpaSupportedLanguages.joinToString("・") {
                formatLanguageDisplayName(it)
            }
            append("\n\n${sherpaLangLabel}ではオフラインモデルの sherpa-onnx が利用可能です。sherpa-onnx の導入を検討してください。")
        }
    }
}

@Composable
private fun BackgroundSpeechRecognizerWarningDialog(
    warning: BackgroundSpeechRecognizerWarningState,
    onDismiss: () -> Unit,
) {
    val message = remember(warning) { formatBackgroundSpeechRecognizerWarningMessage(warning) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.player_background_speech_recognizer_warning_title))
        },
        text = {
            Text(text = message)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.player_background_speech_recognizer_warning_close))
            }
        },
    )
}

/**
 * プレビュー・スクリーンショットテスト向けに [SampleMaterial] から [PlayerUiState] を組み立てるヘルパー。
 */
internal fun SampleMaterial.toPlayerUiState(
    isPlaying: Boolean = true,
    speed: Float = 1.0f,
    activeChunkIndex: Int? = if (chunks.isNotEmpty()) 1.coerceAtMost(chunks.lastIndex) else null,
    practice: PracticeSettings = PracticeSettings(),
    blindMode: BlindMode = BlindMode.OFF,
    autoPauseEndsAtMs: Long? = null,
    error: PlaybackError? = null,
    lookaheadStatus: LookaheadTranscriptionStatus = LookaheadTranscriptionStatus(),
): PlayerUiState {
    val chunkUis = chunks.map { chunk ->
        val transcript = transcripts[chunk.id]
        ChunkUi(
            id = chunk.id,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            text = transcript?.text,
            language = transcript?.language,
            isRepeatChecked = chunk.id in practice.repeatChunkIds,
            isSkipped = chunk.isSkipped,
            translatedText = transcript?.translatedText,
            translatedLanguage = transcript?.translatedLanguage,
            textVariants = transcript?.textVariants.orEmpty(),
            words = transcript?.words.orEmpty(),
            origin = chunk.origin,
        )
    }
    val origins = if (transcripts.isNotEmpty()) {
        setOf(ChunkOrigin.IMPORTED, ChunkOrigin.AUTO)
    } else if (chunks.isNotEmpty()) {
        setOf(ChunkOrigin.AUTO)
    } else {
        emptySet()
    }
    return PlayerUiState(
        mediaId = item.id,
        title = item.title,
        course = item.course,
        languagePair = item.languagePair,
        isPlaying = isPlaying,
        durationMs = item.durationMs,
        speed = speed,
        practice = practice,
        autoPauseEndsAtMs = autoPauseEndsAtMs,
        waveform = waveform,
        chunks = chunkUis,
        activeChunkIndex = activeChunkIndex,
        analysis = analysis,
        error = error,
        blindMode = blindMode,
        chunkSource = if (transcripts.isNotEmpty()) ChunkOrigin.IMPORTED else ChunkOrigin.AUTO,
        availableChunkOrigins = origins,
        lookaheadStatus = lookaheadStatus,
    )
}

@ChunkFlowPreviews
@Composable
private fun PlayerScreenPreview() {
    val material = SampleMaterials.jaKo
    ChunkFlowTheme {
        PlayerScreen(
            state = material.toPlayerUiState(
                isPlaying = true,
                speed = 1.25f,
            ),
            positionProvider = FakePositionProvider(5_000L),
            onEvent = {},
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun PlayerScreenMaterialPreview(
    @PreviewParameter(SampleMaterialPreviewParameterProvider::class) material: SampleMaterial,
) {
    ChunkFlowTheme {
        PlayerScreen(
            state = material.toPlayerUiState(isPlaying = false),
            positionProvider = FakePositionProvider(4_500L),
            onEvent = {},
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun PlayerScreenBlindAndAutoPausePreview() {
    val material = SampleMaterials.enYue
    ChunkFlowTheme {
        PlayerScreen(
            state = material.toPlayerUiState(
                isPlaying = false,
                blindMode = BlindMode.HIDE_TARGET_ONLY,
                practice = PracticeSettings(
                    loop = LoopMode.SingleChunk(material.chunks[1].id, times = 3),
                    autoPause = AutoPause.Fixed(3_000L),
                ),
                autoPauseEndsAtMs = 3_000L,
            ),
            positionProvider = FakePositionProvider(4_500L),
            onEvent = {},
        )
    }
}

/** 文字起こしの実行中（チャンクリストの下部に進捗のカードが重なる状態）のプレビュー。 */
@ChunkFlowPreviews
@Composable
private fun PlayerScreenTranscriptionProgressPreview() {
    val material = SampleMaterials.jaKo
    val baseState = material.toPlayerUiState(isPlaying = true, activeChunkIndex = 0)
    // 先頭の 2 件は文字起こし済み、3 件目を文字起こし中、残りは待機中にする
    val chunks = baseState.chunks.mapIndexed { index, chunk ->
        if (index < 2) chunk else chunk.copy(text = null, language = null)
    }
    ChunkFlowTheme {
        PlayerScreen(
            state = baseState.copy(
                chunks = chunks,
                lookaheadStatus = LookaheadTranscriptionStatus(
                    enabled = true,
                    engineAvailable = true,
                    inFlightChunkId = chunks.getOrNull(2)?.id,
                    queuedChunkIds = chunks.drop(3).map { it.id }.toSet(),
                    completedCount = 2,
                    totalTargetCount = chunks.size,
                    inFlightStep = TranscriptionStep.DETECTING_LANGUAGE,
                    inFlightDetail = "ko / ja",
                ),
            ),
            positionProvider = FakePositionProvider(5_000L),
            onEvent = {},
        )
    }
}
