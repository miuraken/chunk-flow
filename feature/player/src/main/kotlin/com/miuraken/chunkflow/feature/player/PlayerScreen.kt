package com.miuraken.chunkflow.feature.player

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.model.SegmentationParams
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
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    LaunchedEffect(mediaId) {
        viewModel.open(mediaId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
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
            when (event) {
                PlayerEvent.Back -> onBack()
                else -> viewModel.onEvent(event)
            }
        },
        onPickTranscriptJson = {
            transcriptPickerLauncher.launch(
                arrayOf("application/json", "text/vtt", "application/x-subrip", "text/plain", "*/*"),
            )
        },
        modifier = modifier,
    )
}

/**
 * プレイヤー画面（状態を受け取って描くだけの Composable。§6.2、§7.5.2〜§7.5.6）。
 *
 * 再生位置は [PlayerUiState] に含めず、[positionProvider] を子 Composable（[WaveformSection]・[PlayerProgressSection]）にのみ渡す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    positionProvider: PositionProvider,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
    onPickTranscriptJson: () -> Unit = {},
) {
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showResegmentSheet by remember { mutableStateOf(false) }

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
                            contentDescription = stringResource(R.string.player_chip_resegment),
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
                            onSeekTo = { targetMs -> onEvent(PlayerEvent.SeekTo(targetMs)) },
                            onSetAbRange = { startMs, endMs ->
                                onEvent(PlayerEvent.SetLoopMode(LoopMode.AbRange(startMs, endMs)))
                            },
                            onPreviousChunk = { onEvent(PlayerEvent.PreviousChunk) },
                            onNextChunk = { onEvent(PlayerEvent.NextChunk) },
                            onSeekBack = { onEvent(PlayerEvent.SeekBack) },
                        )

                        PlayerPracticeControlStrip(
                            state = state,
                            positionProvider = positionProvider,
                            onEvent = onEvent,
                            onOpenSpeedSheet = { showSpeedSheet = true },
                            onOpenResegmentSheet = { showResegmentSheet = true },
                        )

                        PlayerProgressSection(
                            durationMs = state.durationMs,
                            isPlaying = state.isPlaying,
                            positionProvider = positionProvider,
                            onSeekTo = { targetMs -> onEvent(PlayerEvent.SeekTo(targetMs)) },
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

                    TranscriptList(
                        chunks = state.chunks,
                        activeChunkIndex = state.activeChunkIndex,
                        languagePair = state.languagePair,
                        loopMode = state.practice.loop,
                        blindMode = state.blindMode,
                        revealedChunkIds = state.revealedChunkIds,
                        chineseScript = state.chineseScript,
                        cantoneseScript = state.cantoneseScript,
                        transcriptImportBanner = state.transcriptImportBanner,
                        lookaheadStatus = state.lookaheadStatus,
                        localTranscriptExported = state.localTranscriptExported,
                        onSeekToChunk = { chunkId -> onEvent(PlayerEvent.SeekToChunk(chunkId)) },
                        onSetLoopMode = { loop -> onEvent(PlayerEvent.SetLoopMode(loop)) },
                        onToggleRevealChunk = { chunkId -> onEvent(PlayerEvent.ToggleRevealChunk(chunkId)) },
                        onPickTranscriptJson = onPickTranscriptJson,
                        onDismissTranscriptBanner = { onEvent(PlayerEvent.DismissTranscriptBanner) },
                        onUpdateChunkLanguage = { chunkId, lang ->
                            onEvent(PlayerEvent.UpdateChunkLanguage(chunkId, lang))
                        },
                        onBulkSetUnknownLanguage = { lang ->
                            onEvent(PlayerEvent.BulkSetUnknownTranscriptLanguage(lang))
                        },
                        onSetLookaheadEnabled = { enabled ->
                            onEvent(PlayerEvent.SetLookaheadEnabled(enabled))
                        },
                        onExportLocalTranscriptJson = {
                            onEvent(PlayerEvent.ExportLocalTranscriptJson)
                        },
                        modifier = Modifier
                            .weight(0.52f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
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
                            onSeekTo = { targetMs -> onEvent(PlayerEvent.SeekTo(targetMs)) },
                            onSetAbRange = { startMs, endMs ->
                                onEvent(PlayerEvent.SetLoopMode(LoopMode.AbRange(startMs, endMs)))
                            },
                            onPreviousChunk = { onEvent(PlayerEvent.PreviousChunk) },
                            onNextChunk = { onEvent(PlayerEvent.NextChunk) },
                            onSeekBack = { onEvent(PlayerEvent.SeekBack) },
                        )
                    }

                    TranscriptList(
                        chunks = state.chunks,
                        activeChunkIndex = state.activeChunkIndex,
                        languagePair = state.languagePair,
                        loopMode = state.practice.loop,
                        blindMode = state.blindMode,
                        revealedChunkIds = state.revealedChunkIds,
                        chineseScript = state.chineseScript,
                        cantoneseScript = state.cantoneseScript,
                        transcriptImportBanner = state.transcriptImportBanner,
                        lookaheadStatus = state.lookaheadStatus,
                        localTranscriptExported = state.localTranscriptExported,
                        onSeekToChunk = { chunkId -> onEvent(PlayerEvent.SeekToChunk(chunkId)) },
                        onSetLoopMode = { loop -> onEvent(PlayerEvent.SetLoopMode(loop)) },
                        onToggleRevealChunk = { chunkId -> onEvent(PlayerEvent.ToggleRevealChunk(chunkId)) },
                        onPickTranscriptJson = onPickTranscriptJson,
                        onDismissTranscriptBanner = { onEvent(PlayerEvent.DismissTranscriptBanner) },
                        onUpdateChunkLanguage = { chunkId, lang ->
                            onEvent(PlayerEvent.UpdateChunkLanguage(chunkId, lang))
                        },
                        onBulkSetUnknownLanguage = { lang ->
                            onEvent(PlayerEvent.BulkSetUnknownTranscriptLanguage(lang))
                        },
                        onSetLookaheadEnabled = { enabled ->
                            onEvent(PlayerEvent.SetLookaheadEnabled(enabled))
                        },
                        onExportLocalTranscriptJson = {
                            onEvent(PlayerEvent.ExportLocalTranscriptJson)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )

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
                            PlayerPracticeControlStrip(
                                state = state,
                                positionProvider = positionProvider,
                                onEvent = onEvent,
                                onOpenSpeedSheet = { showSpeedSheet = true },
                                onOpenResegmentSheet = { showResegmentSheet = true },
                            )

                            PlayerProgressSection(
                                durationMs = state.durationMs,
                                isPlaying = state.isPlaying,
                                positionProvider = positionProvider,
                                onSeekTo = { targetMs -> onEvent(PlayerEvent.SeekTo(targetMs)) },
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
                transcriptOffsetMs = state.transcriptOffsetMs,
                onAdjustTranscriptOffset = { deltaMs ->
                    onEvent(PlayerEvent.AdjustTranscriptOffset(deltaMs))
                },
                onAutoAlignTranscriptOffset = {
                    onEvent(PlayerEvent.AutoAlignTranscriptOffset)
                },
                onApply = { params ->
                    onEvent(PlayerEvent.Resegment(params))
                    showResegmentSheet = false
                },
                onClose = { showResegmentSheet = false },
            )
        }
    }
}

@Composable
private fun PlayerTitleMetaRow(
    course: String?,
    languagePair: LanguagePair?,
) {
    if (languagePair == null && course == null) return
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        languagePair?.let { pair ->
            val targetText = pair.target.value.uppercase(Locale.ROOT)
            val badgeText = pair.base?.value?.uppercase(Locale.ROOT)?.let { baseText ->
                "$baseText → $targetText"
            } ?: targetText
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
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
 * 学習コントロールのチップ列（リピート、A-B クイック設定、自動ポーズ、瞬間作文、言語フィルタ、ブラインドモード、先読み文字起こし、チャンク出どころ、分割調整、速度）。
 */
@Composable
private fun PlayerPracticeControlStrip(
    state: PlayerUiState,
    positionProvider: PositionProvider,
    onEvent: (PlayerEvent) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onOpenResegmentSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var repeatMenuExpanded by remember { mutableStateOf(false) }
    var autoPauseMenuExpanded by remember { mutableStateOf(false) }
    var blindMenuExpanded by remember { mutableStateOf(false) }
    var langFilterMenuExpanded by remember { mutableStateOf(false) }
    var pendingAbStartMs by remember { mutableStateOf<Long?>(null) }

    val loop = state.practice.loop
    val repeatLabel = when (loop) {
        LoopMode.Off -> stringResource(R.string.player_chip_repeat_off)

        is LoopMode.SingleChunk -> if (loop.times != null) {
            stringResource(R.string.player_chip_repeat_single_3)
        } else {
            stringResource(R.string.player_chip_repeat_single_inf)
        }

        is LoopMode.ChunkRange -> stringResource(R.string.player_chip_repeat_range)

        is LoopMode.AbRange -> stringResource(R.string.player_chip_repeat_ab)
    }

    val autoPause = state.practice.autoPause
    val autoPauseLabel = when (autoPause) {
        AutoPause.Off -> stringResource(R.string.player_chip_autopause_off)

        is AutoPause.Fixed -> stringResource(
            R.string.player_chip_autopause_fixed,
            (autoPause.pauseMs / 1_000L).toInt(),
        )

        is AutoPause.Proportional -> stringResource(
            R.string.player_chip_autopause_prop,
            String.format(Locale.ROOT, "%.1f", autoPause.factor),
        )
    }

    val isInstantCompositionOn =
        state.practice.autoPauseScope == AutoPauseScope.AFTER_BASE_LANGUAGE &&
            state.practice.autoPause !is AutoPause.Off
    val instantCompositionLabel = if (isInstantCompositionOn) {
        stringResource(R.string.player_chip_instant_comp_on)
    } else {
        stringResource(R.string.player_chip_instant_comp_off)
    }

    val languageFilter = state.practice.languageFilter
    val languageFilterLabel = when (languageFilter) {
        LanguageFilter.ALL -> stringResource(R.string.player_chip_lang_filter_all)
        LanguageFilter.TARGET_ONLY -> stringResource(R.string.player_chip_lang_filter_target)
        LanguageFilter.BASE_ONLY -> stringResource(R.string.player_chip_lang_filter_base)
    }

    val blindLabel = when (state.blindMode) {
        BlindMode.OFF -> stringResource(R.string.player_chip_blind_off)
        BlindMode.HIDE_TARGET_ONLY -> stringResource(R.string.player_chip_blind_target)
        BlindMode.HIDE_ALL -> stringResource(R.string.player_chip_blind_all)
    }

    val activeChunkId = state.activeChunkIndex?.let { state.chunks.getOrNull(it)?.id }
        ?: state.chunks.firstOrNull()?.id

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. リピートチップ + メニュー
        Column {
            FilterChip(
                selected = loop !is LoopMode.Off,
                onClick = { repeatMenuExpanded = true },
                label = { Text(text = repeatLabel, maxLines = 1) },
                leadingIcon = {
                    Icon(
                        imageVector = ChunkFlowIcons.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            DropdownMenu(
                expanded = repeatMenuExpanded,
                onDismissRequest = { repeatMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_chip_repeat_off)) },
                    onClick = {
                        repeatMenuExpanded = false
                        pendingAbStartMs = null
                        onEvent(PlayerEvent.SetLoopMode(LoopMode.Off))
                    },
                )
                if (activeChunkId != null) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.player_chip_repeat_single_3)) },
                        onClick = {
                            repeatMenuExpanded = false
                            onEvent(PlayerEvent.SetLoopMode(LoopMode.SingleChunk(activeChunkId, times = 3)))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.player_chip_repeat_single_inf)) },
                        onClick = {
                            repeatMenuExpanded = false
                            onEvent(PlayerEvent.SetLoopMode(LoopMode.SingleChunk(activeChunkId, times = null)))
                        },
                    )
                }
            }
        }

        // A-B クイック設定チップ ([A] / [B] / 解除, F6.2)
        FilterChip(
            selected = pendingAbStartMs != null || loop is LoopMode.AbRange,
            onClick = {
                val nowMs = positionProvider.currentPositionMs().coerceAtLeast(0L)
                pendingAbStartMs = nowMs
                if (loop is LoopMode.AbRange && nowMs < loop.endMs) {
                    onEvent(PlayerEvent.SetLoopMode(LoopMode.AbRange(nowMs, loop.endMs)))
                }
            },
            label = { Text(text = stringResource(R.string.player_ab_set_a), maxLines = 1) },
        )
        FilterChip(
            selected = loop is LoopMode.AbRange,
            onClick = {
                val nowMs = positionProvider.currentPositionMs().coerceAtLeast(0L)
                val startCandidate = pendingAbStartMs
                    ?: (loop as? LoopMode.AbRange)?.startMs
                    ?: 0L
                val minMs = min(startCandidate, nowMs)
                val maxMs = max(startCandidate, nowMs)
                val effectiveEndMs = if (maxMs > minMs) {
                    maxMs
                } else {
                    (minMs + 2_000L).coerceAtMost(max(minMs + 500L, state.durationMs))
                }
                pendingAbStartMs = null
                onEvent(PlayerEvent.SetLoopMode(LoopMode.AbRange(minMs, effectiveEndMs)))
            },
            label = { Text(text = stringResource(R.string.player_ab_set_b), maxLines = 1) },
        )
        if (loop !is LoopMode.Off) {
            FilterChip(
                selected = false,
                onClick = {
                    pendingAbStartMs = null
                    onEvent(PlayerEvent.SetLoopMode(LoopMode.Off))
                },
                label = { Text(text = stringResource(R.string.player_ab_clear), maxLines = 1) },
            )
        }

        // 2. 自動ポーズチップ (F6.3)
        Column {
            FilterChip(
                selected = autoPause !is AutoPause.Off,
                onClick = { autoPauseMenuExpanded = true },
                label = { Text(text = autoPauseLabel, maxLines = 1) },
                leadingIcon = {
                    Icon(
                        imageVector = ChunkFlowIcons.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            DropdownMenu(
                expanded = autoPauseMenuExpanded,
                onDismissRequest = { autoPauseMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_chip_autopause_off)) },
                    onClick = {
                        autoPauseMenuExpanded = false
                        onEvent(PlayerEvent.SetAutoPause(AutoPause.Off))
                    },
                )
                listOf(2_000L, 3_000L, 5_000L).forEach { pauseMs ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    R.string.player_chip_autopause_fixed,
                                    (pauseMs / 1_000L).toInt(),
                                ),
                            )
                        },
                        onClick = {
                            autoPauseMenuExpanded = false
                            onEvent(PlayerEvent.SetAutoPause(AutoPause.Fixed(pauseMs)))
                        },
                    )
                }
                listOf(1.0f, 1.5f).forEach { factor ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    R.string.player_chip_autopause_prop,
                                    String.format(Locale.ROOT, "%.1f", factor),
                                ),
                            )
                        },
                        onClick = {
                            autoPauseMenuExpanded = false
                            onEvent(PlayerEvent.SetAutoPause(AutoPause.Proportional(factor = factor)))
                        },
                    )
                }
            }
        }

        // 3. 瞬間作文モードチップ (F6.5: 母語チャンクの後だけ自動ポーズ)
        if (state.languagePair?.base != null) {
            FilterChip(
                selected = isInstantCompositionOn,
                onClick = { onEvent(PlayerEvent.ToggleInstantComposition) },
                label = { Text(text = instantCompositionLabel, maxLines = 1) },
                leadingIcon = {
                    Icon(
                        imageVector = ChunkFlowIcons.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }

        // 4. 言語フィルタチップ (F6.4)
        if (state.languagePair?.base != null) {
            Column {
                FilterChip(
                    selected = languageFilter != LanguageFilter.ALL,
                    onClick = { langFilterMenuExpanded = true },
                    label = { Text(text = languageFilterLabel, maxLines = 1) },
                    leadingIcon = {
                        Icon(
                            imageVector = ChunkFlowIcons.Translate,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
                DropdownMenu(
                    expanded = langFilterMenuExpanded,
                    onDismissRequest = { langFilterMenuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.player_chip_lang_filter_all)) },
                        onClick = {
                            langFilterMenuExpanded = false
                            onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.ALL))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.player_chip_lang_filter_target)) },
                        onClick = {
                            langFilterMenuExpanded = false
                            onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.TARGET_ONLY))
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.player_chip_lang_filter_base)) },
                        onClick = {
                            langFilterMenuExpanded = false
                            onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.BASE_ONLY))
                        },
                    )
                }
            }
        }

        // 5. ブラインドモードチップ (F7.2)
        Column {
            FilterChip(
                selected = state.blindMode != BlindMode.OFF,
                onClick = { blindMenuExpanded = true },
                label = { Text(text = blindLabel, maxLines = 1) },
                leadingIcon = {
                    Icon(
                        imageVector = if (state.blindMode == BlindMode.OFF) {
                            ChunkFlowIcons.Visibility
                        } else {
                            ChunkFlowIcons.VisibilityOff
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
            DropdownMenu(
                expanded = blindMenuExpanded,
                onDismissRequest = { blindMenuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_chip_blind_off)) },
                    onClick = {
                        blindMenuExpanded = false
                        onEvent(PlayerEvent.SetBlindMode(BlindMode.OFF))
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_chip_blind_target)) },
                    onClick = {
                        blindMenuExpanded = false
                        onEvent(PlayerEvent.SetBlindMode(BlindMode.HIDE_TARGET_ONLY))
                    },
                )
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_chip_blind_all)) },
                    onClick = {
                        blindMenuExpanded = false
                        onEvent(PlayerEvent.SetBlindMode(BlindMode.HIDE_ALL))
                    },
                )
            }
        }

        // 6. 端末内の先読み簡易文字起こしトグルチップ (F6.2)
        val lookahead = state.lookaheadStatus
        val lookaheadTotal = if (lookahead.totalTargetCount > 0) {
            lookahead.totalTargetCount
        } else {
            state.chunks.size
        }
        val lookaheadLabel = if (lookahead.enabled) {
            stringResource(R.string.player_lookahead_chip_on, lookahead.completedCount, lookaheadTotal)
        } else {
            stringResource(R.string.player_lookahead_chip_off)
        }
        FilterChip(
            selected = lookahead.enabled,
            onClick = { onEvent(PlayerEvent.SetLookaheadEnabled(!lookahead.enabled)) },
            label = { Text(text = lookaheadLabel, maxLines = 1) },
            leadingIcon = {
                Icon(
                    imageVector = ChunkFlowIcons.Bolt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )

        // 7. チャンク出どころ切り替えチップ (F5.4)
        val canSwitchOrigin = state.availableChunkOrigins.contains(ChunkOrigin.IMPORTED) &&
            state.availableChunkOrigins.contains(ChunkOrigin.AUTO)
        if (canSwitchOrigin || state.availableChunkOrigins.isNotEmpty()) {
            val isImported = state.chunkSource == ChunkOrigin.IMPORTED
            val sourceLabel = if (isImported) {
                stringResource(R.string.player_chip_source_imported)
            } else {
                stringResource(R.string.player_chip_source_auto)
            }
            FilterChip(
                selected = isImported,
                onClick = {
                    val nextOrigin = if (isImported) ChunkOrigin.AUTO else ChunkOrigin.IMPORTED
                    onEvent(PlayerEvent.SetChunkSource(nextOrigin))
                },
                label = { Text(text = sourceLabel, maxLines = 1) },
            )
        }

        // 8. 分割調整・時刻ずれ補正チップ (F5.2, F7.6)
        FilterChip(
            selected = state.transcriptOffsetMs != 0,
            onClick = onOpenResegmentSheet,
            label = { Text(text = stringResource(R.string.player_chip_resegment), maxLines = 1) },
            leadingIcon = {
                Icon(
                    imageVector = ChunkFlowIcons.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
        )

        // 9. 再生速度チップ (F3.2)
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

/**
 * 再生位置とシークバーを表示する子 Composable（§6.2）。
 *
 * [PositionProvider] のサンプリングをこの Composable の内部に閉じ込めることで、
 * 再生位置の更新によって [PlayerScreen] 全体が再コンポーズされるのを防ぐ。
 */
@Composable
private fun PlayerProgressSection(
    durationMs: Long,
    isPlaying: Boolean,
    positionProvider: PositionProvider,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isInspectionMode = LocalInspectionMode.current
    var sampledPositionMs by remember(positionProvider) {
        mutableLongStateOf(positionProvider.currentPositionMs())
    }
    var isDragging by remember { mutableStateOf(false) }
    var dragPositionMs by remember { mutableLongStateOf(0L) }

    if (!isInspectionMode) {
        LaunchedEffect(positionProvider, isPlaying) {
            sampledPositionMs = positionProvider.currentPositionMs()
            while (isActive) {
                sampledPositionMs = positionProvider.currentPositionMs()
                delay(if (isPlaying) 50L else 200L)
            }
        }
    }

    val safeDurationMs = durationMs.coerceAtLeast(0L)
    val displayPositionMs = if (isDragging) {
        dragPositionMs
    } else if (safeDurationMs > 0L) {
        sampledPositionMs.coerceIn(0L, safeDurationMs)
    } else {
        sampledPositionMs.coerceAtLeast(0L)
    }
    val sliderFraction = if (safeDurationMs > 0L) {
        (displayPositionMs.toFloat() / safeDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val sliderDescription = stringResource(R.string.player_seek_slider_cd)

    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = sliderFraction,
            onValueChange = { fraction ->
                isDragging = true
                dragPositionMs = (fraction * safeDurationMs).roundToLong()
            },
            onValueChangeFinished = {
                if (isDragging) {
                    isDragging = false
                    sampledPositionMs = dragPositionMs
                    onSeekTo(dragPositionMs)
                }
            },
            enabled = safeDurationMs > 0L,
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .semantics { contentDescription = sliderDescription },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatDurationMs(displayPositionMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatDurationMs(safeDurationMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(
            onClick = onPreviousChunk,
            enabled = hasChunks,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = ChunkFlowIcons.SkipPrevious,
                contentDescription = prevChunkCd,
                modifier = Modifier.size(22.dp),
            )
        }

        FilledTonalButton(
            onClick = onSeekBack,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = seekBackCd },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = ChunkFlowIcons.SeekBack,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.player_seek_back_label, seekBackSeconds),
                maxLines = 1,
            )
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
            onClick = onSeekForward,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { contentDescription = seekForwardCd },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.player_seek_forward_label, seekForwardSeconds),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = ChunkFlowIcons.SeekForward,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }

        FilledTonalIconButton(
            onClick = onNextChunk,
            enabled = hasChunks,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = ChunkFlowIcons.SkipNext,
                contentDescription = nextChunkCd,
                modifier = Modifier.size(22.dp),
            )
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
    transcriptOffsetMs: Int,
    onAdjustTranscriptOffset: (Int) -> Unit,
    onAutoAlignTranscriptOffset: () -> Unit,
    onApply: (SegmentationParams) -> Unit,
    onClose: () -> Unit,
) {
    var minSilenceMs by remember(initialParams) { mutableIntStateOf(initialParams.minSilenceMs) }
    var prePaddingMs by remember(initialParams) { mutableIntStateOf(initialParams.prePaddingMs) }
    var postPaddingMs by remember(initialParams) { mutableIntStateOf(initialParams.postPaddingMs) }
    var thresholdRatio by remember(initialParams) { mutableFloatStateOf(initialParams.thresholdRatio) }

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
            textVariants = transcript?.textVariants.orEmpty(),
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
