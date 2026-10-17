package com.miuraken.chunkflow.feature.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PhoneticGuideKind
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
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
    DisposableEffect(mediaId) {
        onDispose {
            viewModel.close(mediaId)
        }
    }
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var hasRequestedAudioPermissionForMedia by remember(mediaId) { mutableStateOf(false) }
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { isGranted ->
        if (isGranted) {
            val activeChunkId = state.activeChunkIndex
                ?.let { idx -> state.chunks.getOrNull(idx)?.id }
                ?: state.chunks.firstOrNull()?.id
            viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))
            viewModel.onEvent(PlayerEvent.TriggerLookahead(activeChunkId))
        }
    }

    LaunchedEffect(mediaId, state.lookaheadStatus.enabled) {
        if (state.lookaheadStatus.enabled && !hasRequestedAudioPermissionForMedia) {
            hasRequestedAudioPermissionForMedia = true
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
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

                event is PlayerEvent.SetLookaheadEnabled && event.enabled -> {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        viewModel.onEvent(event)
                    } else {
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
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
        modifier = modifier,
    )
}

/**
 * プレイヤー画面（状態を受け取って描くだけの Composable。§6.2、§7.5.2〜§7.5.6）。
 *
 * 1 ペイン（幅 600dp 未満）では、上から次の順に並べる。
 * 1. チャンクの表示に関する設定（言語・学習言語を隠す・読み）
 * 2. チャンクリスト（残りの高さをすべて使う。文字起こしの進捗はリストの下部に重ねて表示する）
 * 3. 選択範囲に関する操作（全選択解除・連結・分割）
 * 4. 波形（全体波形 + 拡大波形。自動ポーズ中はそのすぐ上にカウントダウンを出す）
 * 5. 再生の操作（速度・リピート・再生設定と、再生ボタン類）
 *
 * 2 ペイン（幅 600dp 以上）では、左に選択範囲の操作・波形・再生の操作を、右にチャンクの表示設定とチャンクリストを置く。
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
) {
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showResegmentSheet by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
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
                    if (state.isTranscriptionCompleted && state.lookaheadStatus.inFlightChunkId == null) {
                        Text(
                            text = "✅️",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    } else {
                        val isTranscribeOn = state.lookaheadStatus.enabled ||
                            state.lookaheadStatus.isForcedChunkInFlight
                        val baseChipLabel = stringResource(R.string.player_chip_transcribe)
                        val chipLabel = if (isTranscribeOn && state.lookaheadStatus.totalTargetCount > 0) {
                            "$baseChipLabel ${state.lookaheadStatus.completedCount}/${state.lookaheadStatus.totalTargetCount}"
                        } else {
                            baseChipLabel
                        }
                        FilterChip(
                            selected = isTranscribeOn,
                            onClick = {
                                if (isTranscribeOn) {
                                    onEvent(PlayerEvent.SetLookaheadEnabled(false))
                                } else {
                                    onEvent(PlayerEvent.SetLookaheadEnabled(true))
                                }
                            },
                            label = {
                                Text(
                                    text = chipLabel,
                                    fontWeight = if (isTranscribeOn) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                )
                            },
                            colors = brightnessToggleChipColors(),
                        )
                    }
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
                    // 左ペイン: バナー → 選択範囲に関する操作 → 波形 → 再生の操作
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

                        ChunkSelectionActionStrip(
                            state = state,
                            onEvent = onEvent,
                        )

                        PlayerWaveform(
                            state = state,
                            positionProvider = positionProvider,
                            seekBackSeconds = seekBackSeconds,
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
                            onTogglePlayPause = { onEvent(PlayerEvent.TogglePlayPause) },
                            onSeekBack = { onEvent(PlayerEvent.SeekBack) },
                            onSeekForward = { onEvent(PlayerEvent.SeekForward) },
                            onPreviousChunk = { onEvent(PlayerEvent.PreviousChunk) },
                            onNextChunk = { onEvent(PlayerEvent.NextChunk) },
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
                            onPickTranscriptJson = onPickTranscriptJson,
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
                        onPickTranscriptJson = onPickTranscriptJson,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )

                    // 3. 選択範囲に関する操作 → 4. 波形（自動ポーズ中は波形のすぐ上にカウントダウンを出す）
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ChunkSelectionActionStrip(
                            state = state,
                            onEvent = onEvent,
                        )

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
                    }

                    // 5. 再生の操作（画面の下部）
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
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
                                onTogglePlayPause = { onEvent(PlayerEvent.TogglePlayPause) },
                                onSeekBack = { onEvent(PlayerEvent.SeekBack) },
                                onSeekForward = { onEvent(PlayerEvent.SeekForward) },
                                onPreviousChunk = { onEvent(PlayerEvent.PreviousChunk) },
                                onNextChunk = { onEvent(PlayerEvent.NextChunk) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSpeedSheet) {
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }) {
            PlayerSpeedSheetContent(
                speed = state.speed,
                onSetSpeed = { speed -> onEvent(PlayerEvent.SetSpeed(speed)) },
                onAdjustSpeed = { delta -> onEvent(PlayerEvent.AdjustSpeed(delta)) },
                onClose = { showSpeedSheet = false },
            )
        }
    }

    if (showResegmentSheet) {
        ModalBottomSheet(onDismissRequest = { showResegmentSheet = false }) {
            ResegmentSheetContent(
                initialParams = state.segmentationParams,
                languagePair = state.languagePair,
                onChangeLanguages = { pair ->
                    onEvent(PlayerEvent.UpdateItemLanguages(target = pair.target, base = pair.base))
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
    onPickTranscriptJson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TranscriptList(
        chunks = state.chunks,
        activeChunkIndex = state.activeChunkIndex,
        languagePair = state.languagePair,
        loopMode = state.practice.loop,
        blindMode = state.blindMode,
        revealedChunkIds = state.revealedChunkIds,
        chineseScript = state.chineseScript,
        cantoneseScript = state.cantoneseScript,
        showTargetRomanization = state.showTargetRomanization,
        transcriptImportBanner = state.transcriptImportBanner,
        positionProvider = positionProvider,
        lookaheadStatus = state.lookaheadStatus,
        localTranscriptExported = state.localTranscriptExported,
        transcriptExportBanner = state.transcriptExportBanner,
        onDismissExportBanner = { onEvent(PlayerEvent.DismissExportBanner) },
        onToggleBookmark = { chunkId -> onEvent(PlayerEvent.ToggleBookmark(chunkId)) },
        onToggleRepeatChunk = { chunkId -> onEvent(PlayerEvent.ToggleChunkRepeat(chunkId)) },
        onSeekToChunk = { chunkId -> onEvent(PlayerEvent.SeekToChunk(chunkId)) },
        onSetLoopMode = { loop -> onEvent(PlayerEvent.SetLoopMode(loop)) },
        onToggleRevealChunk = { chunkId -> onEvent(PlayerEvent.ToggleRevealChunk(chunkId)) },
        onPickTranscriptJson = onPickTranscriptJson,
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
        onSplitChunk = { chunkId, splitMs ->
            onEvent(PlayerEvent.SplitChunk(chunkId, splitMs))
        },
        onMergeWithNextChunk = { chunkId ->
            onEvent(PlayerEvent.MergeWithNextChunk(chunkId))
        },
        onMoveChunkBoundary = { chunkId, newStartMs, newEndMs ->
            onEvent(PlayerEvent.MoveChunkBoundary(chunkId, newStartMs, newEndMs))
        },
        onBulkSetUnknownLanguage = { lang ->
            onEvent(PlayerEvent.BulkSetUnknownTranscriptLanguage(lang))
        },
        onSetLookaheadEnabled = { enabled ->
            onEvent(PlayerEvent.SetLookaheadEnabled(enabled))
        },
        onResetTranscription = {
            onEvent(PlayerEvent.ResetTranscription)
        },
        onExportLocalTranscriptJson = {
            onEvent(PlayerEvent.ExportLocalTranscriptJson)
        },
        onRequestRecognitionModelDownload = { language ->
            onEvent(PlayerEvent.RequestRecognitionModelDownload(language))
        },
        detailedTranscriptionDialog = state.detailedTranscriptionDialog,
        onOpenDetailedTranscription = { chunkId ->
            onEvent(PlayerEvent.OpenDetailedTranscription(chunkId))
        },
        onDismissDetailedTranscription = {
            onEvent(PlayerEvent.DismissDetailedTranscription)
        },
        onRunDetailedTranscriptionTrial = { chunkId, engine, languageOverride, audioSpeed ->
            onEvent(
                PlayerEvent.RunDetailedTranscriptionTrial(
                    chunkId = chunkId,
                    engine = engine,
                    languageOverride = languageOverride,
                    audioSpeed = audioSpeed,
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
            val targetText = pair.target.value.uppercase(Locale.ROOT)
            val baseText = pair.base?.value?.uppercase(Locale.ROOT)
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
 * 選択範囲に関する操作の行（1 ペインではチャンクリストと波形の間、2 ペインでは波形の上に置く）。
 *
 * レイアウトが跳ねないよう 3 つのボタンを常に並べ、使えないときは無効（押せない見た目）にする。
 * - 「全選択解除」: チャンクを 1 つ以上選択しているとき
 * - 「連結」: 選択が連続していて、両端のチャンクの言語が同じとき（[canMergeSelectedChunks]）
 * - 「分割」: チャンクがあり、選択がないとき（現在位置でチャンクを 2 つに分ける）
 */
@Composable
private fun ChunkSelectionActionStrip(
    state: PlayerUiState,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasSelection = state.chunks.any { it.isRepeatChecked }
    val canMergeSelection = remember(state.chunks) { canMergeSelectedChunks(state.chunks) }
    val canSplitAtCursor = state.chunks.isNotEmpty() && !hasSelection

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = { onEvent(PlayerEvent.ClearChunkSelection) },
            enabled = hasSelection,
            label = { Text(text = stringResource(R.string.player_selection_clear_all), maxLines = 1) },
        )
        AssistChip(
            onClick = { onEvent(PlayerEvent.MergeSelectedChunks) },
            enabled = canMergeSelection,
            label = { Text(text = stringResource(R.string.player_selection_merge), maxLines = 1) },
        )
        AssistChip(
            onClick = { onEvent(PlayerEvent.SplitChunkAtCurrentPosition) },
            enabled = canSplitAtCursor,
            label = { Text(text = stringResource(R.string.player_split_at_cursor), maxLines = 1) },
        )
    }
}

/**
 * チャンクの表示に関する設定の行（チャンクリストの上に置く）。
 *
 * 表示項目:
 * - 「言語」（*すべて、学習言語のみ、母語のみ）
 * - 「学習言語を隠す」（on, *off）
 * - 「読み」（on, *off）
 */
@Composable
private fun ChunkDisplaySettingsStrip(
    state: PlayerUiState,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    var langFilterMenuExpanded by remember { mutableStateOf(false) }

    val languageFilter = state.practice.languageFilter
    val languageFilterLabel = when (languageFilter) {
        LanguageFilter.ALL -> stringResource(R.string.player_chip_lang_filter_all)
        LanguageFilter.TARGET_ONLY -> stringResource(R.string.player_chip_lang_filter_target)
        LanguageFilter.BASE_ONLY -> stringResource(R.string.player_chip_lang_filter_base)
    }

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
        // 1. 「言語」（*すべて、学習言語のみ、母語のみ）
        Column {
            FilterChip(
                selected = languageFilter != LanguageFilter.ALL,
                onClick = { langFilterMenuExpanded = true },
                label = { Text(text = languageFilterLabel, maxLines = 1) },
            )
            DropdownMenu(
                expanded = langFilterMenuExpanded,
                onDismissRequest = { langFilterMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_menu_lang_filter_all)) },
                    onClick = {
                        langFilterMenuExpanded = false
                        onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.ALL))
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_menu_lang_filter_target)) },
                    onClick = {
                        langFilterMenuExpanded = false
                        onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.TARGET_ONLY))
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_menu_lang_filter_base)) },
                    onClick = {
                        langFilterMenuExpanded = false
                        onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.BASE_ONLY))
                    },
                )
            }
        }

        // 2. 「学習言語を隠す」（on, *off）
        FilterChip(
            selected = isHideTargetLangOn,
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
                    text = stringResource(R.string.player_chip_blind_target),
                    fontWeight = if (isHideTargetLangOn) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            },
            colors = toggleColors,
        )

        // 3. 「読み」（on, *off）
        FilterChip(
            selected = isReadingOn,
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
        )
    }
}

/**
 * b) 再生に関するボタン列（下部の再生ボタン類のすぐ上部に配置）。
 *
 * 表示項目:
 * - 「1.00x」（再生速度）
 * - 「リピート」（on, *off）
 * - 「再生設定」（全体設定の再生設定タブへ移動）
 */
@Composable
private fun PlaybackControlStrip(
    state: PlayerUiState,
    onEvent: (PlayerEvent) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlaybackSettings: () -> Unit = {},
) {
    val isRepeatOn = state.practice.loop !is LoopMode.Off || state.practice.repeatCheckedOnly
    val toggleColors = brightnessToggleChipColors()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. 「1.00x」（再生速度）
        FilterChip(
            selected = abs(state.speed - 1.0f) >= 0.01f,
            onClick = onOpenSpeedSheet,
            label = {
                Text(
                    text = formatSpeed(state.speed),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            },
        )

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

@Composable
private fun PlayerTransportControls(
    isPlaying: Boolean,
    seekBackMs: Long,
    seekForwardMs: Long,
    hasChunks: Boolean,
    onTogglePlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPreviousChunk: () -> Unit,
    onNextChunk: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = onSeekBack,
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = seekBackCd },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = ChunkFlowIcons.SeekBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.player_seek_back_label, seekBackSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        FilledTonalButton(
            onClick = onPreviousChunk,
            enabled = hasChunks,
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = prevChunkCd },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = ChunkFlowIcons.SkipPrevious,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.player_prev_chunk_label),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        FilledIconButton(
            onClick = onTogglePlayPause,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                imageVector = if (isPlaying) ChunkFlowIcons.Pause else ChunkFlowIcons.Play,
                contentDescription = playPauseCd,
                modifier = Modifier.size(28.dp),
            )
        }

        FilledTonalButton(
            onClick = onNextChunk,
            enabled = hasChunks,
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = nextChunkCd },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = ChunkFlowIcons.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.player_next_chunk_label),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        FilledTonalButton(
            onClick = onSeekForward,
            modifier = Modifier
                .weight(1f, fill = false)
                .heightIn(min = 48.dp)
                .semantics { contentDescription = seekForwardCd },
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = ChunkFlowIcons.SeekForward,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.player_seek_forward_label, seekForwardSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                    text = formatSpeed(speed),
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
                val labelText = String.format(
                    Locale.ROOT,
                    if (preset * 100f % 10f == 0f) "%.1fx" else "%.2fx",
                    preset,
                )
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
    languagePair: LanguagePair?,
    onChangeLanguages: (LanguagePair) -> Unit,
    transcriptOffsetMs: Int,
    onAdjustTranscriptOffset: (Int) -> Unit,
    onAutoAlignTranscriptOffset: () -> Unit,
    onImportTranscript: () -> Unit,
    onExportTranscript: (TranscriptExportFormat) -> Unit,
    onResetTranscripts: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onApply: (SegmentationParams) -> Unit,
    onOpenGlobalSettings: () -> Unit,
    onClose: () -> Unit,
) {
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 教材の言語（学習言語・ベース言語）を選び直す
        ItemLanguageSettingsSection(
            languagePair = languagePair,
            onChangeLanguages = onChangeLanguages,
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // 文字起こしファイル (F1.3, F1.4, F7.5)
        Text(
            text = stringResource(R.string.player_export_section_title),
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
        // 文字起こしの診断ログ（状態の要約・直近のログ・コピー・強制リセット）を開く
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

        // 文字起こしの時刻ずれ補正 (F7.6)
        Text(
            text = stringResource(R.string.player_offset_section_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
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

        // 自動チャンク分割の調整 (F5.2)
        Text(
            text = stringResource(R.string.player_resegment_sheet_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Text(
            text = stringResource(R.string.player_resegment_min_silence, minSilenceMs),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = minSilenceMs.toFloat(),
            onValueChange = { minSilenceMs = ((it / 50f).roundToInt() * 50).coerceIn(200, 1_500) },
            valueRange = 200f..1_500f,
        )

        Text(
            text = stringResource(R.string.player_resegment_pre_padding, prePaddingMs),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = prePaddingMs.toFloat(),
            onValueChange = { prePaddingMs = ((it / 25f).roundToInt() * 25).coerceIn(0, 500) },
            valueRange = 0f..500f,
        )

        Text(
            text = stringResource(R.string.player_resegment_post_padding, postPaddingMs),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = postPaddingMs.toFloat(),
            onValueChange = { postPaddingMs = ((it / 25f).roundToInt() * 25).coerceIn(0, 500) },
            valueRange = 0f..500f,
        )

        Text(
            text = stringResource(
                R.string.player_resegment_threshold,
                String.format(Locale.ROOT, "%.2f", thresholdRatio),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = thresholdRatio,
            onValueChange = { thresholdRatio = ((it * 20f).roundToInt() / 20f).coerceIn(0.05f, 0.80f) },
            valueRange = 0.05f..0.80f,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) {
                Text(text = stringResource(R.string.player_sheet_close))
            }
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
            ) {
                Text(text = stringResource(R.string.player_resegment_apply))
            }
        }

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
    localTranscriptExported: Boolean = false,
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
        localTranscriptExported = localTranscriptExported,
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
