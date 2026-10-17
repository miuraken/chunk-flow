package com.miuraken.chunkflow.feature.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.OnDeviceRecognitionModelState
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.TranscriptionDiagnosticEntry
import com.miuraken.chunkflow.core.model.TranscriptionStep
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.model.WordTiming
import com.miuraken.chunkflow.core.playback.PositionProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import java.util.Locale as JavaLocale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 手動スクロール後に自動追従を再開するまでの待機時間（5 秒、§7.5.5）。 */
private const val AUTO_FOLLOW_RESUME_DELAY_MS: Long = 5_000L

/** 1 チャンクの文字起こしが停滞しているとみなして警告を出す経過時間（8 秒）。 */
private const val TRANSCRIPTION_STALL_WARNING_MS: Long = 8_000L

/**
 * チャンクごとの文字起こし進捗段階。
 */
internal enum class ChunkTranscriptionStage {
    /** 文字起こし待機・実行対象外。 */
    IDLE,

    /** 段階 1: 文字起こしの順番待ち（待機中）。 */
    WAITING,

    /** 段階 2: 現在文字起こしを実行中。 */
    TRANSCRIBING,

    /** 言語は判定できたが、その言語の端末内音声認識モデルが使えないため本文がない（ダウンロード待ちなど）。 */
    MODEL_UNAVAILABLE,
}

/**
 * チャンク [chunk] と先読み状態 [lookaheadStatus] から 2 段階の文字起こし状態を判定する。
 *
 * - 強制文字起こし中（`isForcedChunkInFlight == true` かつ `chunk.id == inFlightChunkId`）は本文の有無によらず [ChunkTranscriptionStage.TRANSCRIBING] を返す。
 * - 本文がなく、チャンクの言語の音声認識モデルが使えない場合は [ChunkTranscriptionStage.MODEL_UNAVAILABLE] を返す。
 * - 認識結果が空でスキップ済みのチャンク（`skippedEmptyChunkIds`）や、全対象チャンクの試行が完了した後は [ChunkTranscriptionStage.IDLE] に戻し、「待機中…」のまま残らないようにする。
 */
internal fun resolveChunkTranscriptionStage(
    chunk: ChunkUi,
    lookaheadStatus: LookaheadTranscriptionStatus,
): ChunkTranscriptionStage {
    if (chunk.id == lookaheadStatus.inFlightChunkId &&
        (chunk.text.isNullOrBlank() || lookaheadStatus.isForcedChunkInFlight)
    ) {
        return ChunkTranscriptionStage.TRANSCRIBING
    }
    if (!chunk.text.isNullOrBlank()) {
        return ChunkTranscriptionStage.IDLE
    }
    val hasModelIssue = lookaheadStatus.recognitionModelIssues.keys.any { language ->
        matchesLanguageRole(chunk.language, language)
    }
    if (hasModelIssue) {
        return ChunkTranscriptionStage.MODEL_UNAVAILABLE
    }
    if (chunk.id in lookaheadStatus.skippedEmptyChunkIds) {
        return ChunkTranscriptionStage.IDLE
    }
    if (chunk.id in lookaheadStatus.queuedChunkIds) {
        return ChunkTranscriptionStage.WAITING
    }
    val allTargetsAttempted = lookaheadStatus.totalTargetCount > 0 &&
        lookaheadStatus.completedCount >= lookaheadStatus.totalTargetCount &&
        lookaheadStatus.inFlightChunkId == null &&
        lookaheadStatus.queuedChunkIds.isEmpty()
    if (lookaheadStatus.enabled && !allTargetsAttempted) {
        return ChunkTranscriptionStage.WAITING
    }
    return ChunkTranscriptionStage.IDLE
}

/**
 * 単語タイムスタンプに対応する表示テキスト内の文字オフセット範囲（F7.7、§7.5.5）。
 *
 * @property startCharIndex `displayText` 内の開始インデックス（0 始まり、含む）。
 * @property endCharIndex `displayText` 内の終了インデックス（含まない）。
 * @property startMs 単語の開始時刻（ミリ秒）。
 * @property endMs 単語の終了時刻（ミリ秒）。
 */
internal data class WordCharRange(
    val startCharIndex: Int,
    val endCharIndex: Int,
    val startMs: Long,
    val endMs: Long,
)

/**
 * [WordTiming] のリストを [displayText] の文字オフセット範囲へ対応付ける（F7.7、§7.5.5）。
 *
 * - まず [displayText] に対して各単語を先頭から順に対応付ける。
 * - スクリプト切替（簡体字 / 繁体字など）で [displayText] と [rawText] の文字数が一致する場合は、
 *   [rawText] で対応付けた文字オフセットを [displayText] にそのまま適用する。
 * - ピンイン等で文字数が異なり、[displayText] とも一致しない場合は `null` を返し、行単位ハイライトにフォールバックする。
 */
internal fun mapWordsToCharRanges(
    displayText: String,
    rawText: String?,
    words: List<WordTiming>,
): List<WordCharRange>? {
    if (displayText.isEmpty() || words.isEmpty()) return null
    matchWordsSequentially(displayText, words)?.let { return it }
    if (rawText != null && rawText.length == displayText.length) {
        return matchWordsSequentially(rawText, words)
    }
    return null
}

private fun matchWordsSequentially(
    targetText: String,
    words: List<WordTiming>,
): List<WordCharRange>? {
    if (targetText.isEmpty() || words.isEmpty()) return null
    val ranges = ArrayList<WordCharRange>(words.size)
    var cursor = 0
    for (word in words) {
        val token = word.text.trim()
        if (token.isEmpty()) continue
        val foundIdx = targetText.indexOf(token, startIndex = cursor)
        if (foundIdx < 0) return null
        val endIdx = foundIdx + token.length
        if (endIdx > targetText.length) return null
        ranges.add(
            WordCharRange(
                startCharIndex = foundIdx,
                endCharIndex = endIdx,
                startMs = word.startMs,
                endMs = word.endMs,
            ),
        )
        cursor = endIdx
    }
    return ranges.takeIf { it.isNotEmpty() }
}

/**
 * 再生位置 [positionMs] において発話中の単語の文字範囲を返す（F7.7）。
 */
internal fun findActiveWordRange(
    ranges: List<WordCharRange>,
    positionMs: Long,
): WordCharRange? = ranges.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }

/**
 * 言語バッジをタップしたときに切り替える次の言語タグを返す（F7.3）。
 *
 * - 未検出（`null` / 空 / `"und"`）のとき: 学習言語（[LanguagePair.target]）を返す。
 * - 学習言語のとき: 母語（[LanguagePair.base] が異なり存在すれば母語、なければ学習言語）を返す。
 * - 母語またはその他の言語のとき: 学習言語を返す。
 */
internal fun nextToggledChunkLanguage(
    currentLanguage: LanguageTag?,
    languagePair: LanguagePair?,
): LanguageTag? {
    val target = languagePair?.target?.takeIf { it.value.isNotBlank() } ?: return null
    val base = languagePair.base
        ?.takeIf { it.value.isNotBlank() && !matchesLanguageRole(it, target) }
    val currentCode = currentLanguage?.value?.trim()
        ?.takeUnless { it.isEmpty() || it.equals("und", ignoreCase = true) }
    return when {
        currentCode == null -> target
        matchesLanguageRole(currentLanguage, target) -> base ?: target
        else -> target
    }
}

/**
 * 再生位置に同期したチャンク / スクリプト一覧（F5.3、F7.1〜F7.7、F8.1、§7.5.5）。
 *
 * - `LazyColumn` の `key` にチャンク ID を指定し、現在のチャンク（[activeChunkIndex]）を背景色と左端バーで強調する。
 * - 現在のチャンクが変わったら自動スクロールで追従し、手動スクロール後 5 秒間は追従を止めて「現在位置へ戻る」ボタンを表示する。
 * - 各行に言語タグと言語設定（[chineseScript] / [cantoneseScript]）から作った `LocaleList` を適用する。
 * - ブラインドモード（[blindMode]）では対象行をマスクし、タップで一時表示できる。
 * - 言語バッジのタップまたは長押しメニューからチャンクの言語ラベル（学習言語 / 母語 / 未判定 `?`）を手動修正できる（F7.3）。
 * - 未判定言語のチャンクがある場合は一括で学習言語に設定するチップを表示する（F1.4）。
 * - 現在のチャンクに単語タイムスタンプ（`words`）がある場合、アクティブ行の内部でのみ [positionProvider] を監視して発話中の単語をカラオケ強調する（F7.7）。
 * - ブックマーク（F8.1）、テキスト手動編集（F7.4）、チャンク分割・結合・境界微調整（F5.3）を提供する。
 * - 文字起こしの実行中は、進捗をリストの下部に重ねたカード（[TranscriptionProgressOverlay]）で表示する。
 *   カードが出ている間は、カードの高さの分だけリストの下に余白を付け、最後のチャンクがカードの後ろに隠れたままにならないようにする。
 */
@Composable
fun TranscriptList(
    chunks: List<ChunkUi>,
    activeChunkIndex: Int?,
    languagePair: LanguagePair?,
    loopMode: LoopMode,
    blindMode: BlindMode,
    revealedChunkIds: Set<ChunkId>,
    chineseScript: ChineseScript,
    cantoneseScript: ChineseScript,
    transcriptImportBanner: TranscriptImportBannerState?,
    onSeekToChunk: (ChunkId) -> Unit,
    onSetLoopMode: (LoopMode) -> Unit,
    onToggleRevealChunk: (ChunkId) -> Unit,
    @Suppress("UNUSED_PARAMETER") onPickTranscriptJson: () -> Unit,
    onDismissTranscriptBanner: () -> Unit,
    modifier: Modifier = Modifier,
    showTargetRomanization: Boolean = false,
    showTargetTranslation: Boolean = false,
    positionProvider: PositionProvider = PositionProvider { 0L },
    lookaheadStatus: LookaheadTranscriptionStatus = LookaheadTranscriptionStatus(),
    showTranscriptionProgress: Boolean = lookaheadStatus.inFlightChunkId != null ||
        lookaheadStatus.isForcedChunkInFlight ||
        (lookaheadStatus.enabled && lookaheadStatus.queuedChunkIds.isNotEmpty()),
    transcriptExportBanner: TranscriptExportBannerState? = null,
    onDismissExportBanner: () -> Unit = {},
    onToggleBookmark: (ChunkId) -> Unit = {},
    onToggleRepeatChunk: (ChunkId) -> Unit = {},
    onSetChunksSkipped: (Set<ChunkId>, Boolean) -> Unit = { _, _ -> },
    onUpdateChunkLanguage: (ChunkId, LanguageTag?) -> Unit = { _, _ -> },
    onUpdateChunkText: (ChunkId, String) -> Unit = { _, _ -> },
    onRetranscribeChunk: (ChunkId) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onSplitChunk: (ChunkId, Long) -> Unit = { _, _ -> },
    @Suppress("UNUSED_PARAMETER") onMergeWithNextChunk: (ChunkId) -> Unit = {},
    onMoveChunkBoundary: (ChunkId, Long, Long) -> Unit = { _, _, _ -> },
    onBulkSetUnknownLanguage: (LanguageTag) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onSetLookaheadEnabled: (Boolean) -> Unit = {},
    onResetTranscription: () -> Unit = {},
    onRequestRecognitionModelDownload: (LanguageTag) -> Unit = {},
    waveform: Waveform? = null,
    detailedTranscriptionDialog: DetailedTranscriptionDialogState? = null,
    onOpenDetailedTranscription: (ChunkId) -> Unit = {},
    onDismissDetailedTranscription: () -> Unit = {},
    onSelectDetailedTranscriptionLanguage: (LanguageTag) -> Unit = {},
    onSelectDetailedTranscriptionEngine: (OnDeviceTranscriptionEngine) -> Unit = {},
    onRequestDetailedTranscriptionModelDownload: (LanguageTag, OnDeviceTranscriptionEngine) -> Unit = { _, _ -> },
    onPlayDetailedTranscriptionPreview: (ChunkId, Float) -> Unit = { _, _ -> },
    onStopDetailedTranscriptionPreview: () -> Unit = {},
    onRunDetailedTranscriptionTrial: (ChunkId, OnDeviceTranscriptionEngine, LanguageTag?, Float) -> Unit =
        { _, _, _, _ -> },
    onApplyDetailedTranscriptionResult: (ChunkId, String, LanguageTag?) -> Unit = { _, _, _ -> },
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isInspectionMode = LocalInspectionMode.current

    var isProgrammaticScroll by remember { mutableStateOf(false) }
    var isAutoFollowPaused by remember { mutableStateOf(false) }
    var manualScrollTick by remember { mutableLongStateOf(0L) }
    var rangeSelectionStartChunkId by remember { mutableStateOf<ChunkId?>(null) }
    var editingTextChunk by remember { mutableStateOf<Pair<Int, ChunkUi>?>(null) }
    var progressOverlayHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // ユーザーによる手動スクロールの検出
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !isProgrammaticScroll) {
            isAutoFollowPaused = true
            manualScrollTick++
        }
    }

    // 手動スクロール終了から 5 秒後に自動追従へ復帰
    LaunchedEffect(isAutoFollowPaused, listState.isScrollInProgress, manualScrollTick) {
        if (isAutoFollowPaused && !listState.isScrollInProgress) {
            delay(AUTO_FOLLOW_RESUME_DELAY_MS)
            isAutoFollowPaused = false
        }
    }

    // activeChunkIndex が変わったときの自動追従スクロール
    if (!isInspectionMode) {
        LaunchedEffect(activeChunkIndex, chunks.size) {
            val targetIdx = activeChunkIndex ?: return@LaunchedEffect
            if (!isAutoFollowPaused && targetIdx in chunks.indices) {
                val scrollTargetIdx = (targetIdx - 2).coerceAtLeast(0)
                isProgrammaticScroll = true
                try {
                    listState.animateScrollToItem(scrollTargetIdx)
                } finally {
                    isProgrammaticScroll = false
                }
            }
        }
    }

    val hasUnknownLanguageChunks = remember(chunks) {
        chunks.any { chunk ->
            chunk.text != null &&
                (chunk.language == null || chunk.language.value.isBlank() || chunk.language.value == "und")
        }
    }
    val loopHighlightedIds = remember(loopMode, chunks) {
        resolveLoopChunkIds(loopMode, chunks)
    }
    val selectedChunks = remember(chunks) {
        chunks.filter { it.isRepeatChecked }
    }

    // 進捗カードと、リストの端・ほかの部品との間隔
    val progressOverlayMargin = 8.dp

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            transcriptImportBanner?.let { banner ->
                TranscriptImportStatusBanner(
                    banner = banner,
                    onDismiss = onDismissTranscriptBanner,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            transcriptExportBanner?.let { exportBanner ->
                TranscriptExportStatusBanner(
                    banner = exportBanner,
                    onDismiss = onDismissExportBanner,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            for ((issueLanguage, issueState) in lookaheadStatus.recognitionModelIssues) {
                RecognitionModelIssueBanner(
                    language = issueLanguage,
                    state = issueState,
                    onRequestDownload = { onRequestRecognitionModelDownload(issueLanguage) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (hasUnknownLanguageChunks && languagePair != null) {
                UnknownLanguageBanner(
                    languagePair = languagePair,
                    onBulkSetUnknownLanguage = onBulkSetUnknownLanguage,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }

            if (rangeSelectionStartChunkId != null) {
                RangeSelectionBanner(
                    onCancel = { rangeSelectionStartChunkId = null },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp),
            ) {
                itemsIndexed(
                    items = chunks,
                    key = { _, chunk -> chunk.id.value },
                ) { index, chunk ->
                    val isActive = index == activeChunkIndex
                    val isInLoopRange = chunk.id in loopHighlightedIds ||
                        chunk.isRepeatChecked ||
                        chunk.id == rangeSelectionStartChunkId
                    val isMasked = isChunkMasked(
                        chunk = chunk,
                        blindMode = blindMode,
                        languagePair = languagePair,
                        revealedChunkIds = revealedChunkIds,
                    )
                    val transcriptionStage = resolveChunkTranscriptionStage(
                        chunk = chunk,
                        lookaheadStatus = lookaheadStatus,
                    )
                    val isThisChunkInFlight = chunk.id == lookaheadStatus.inFlightChunkId

                    TranscriptChunkRow(
                        index = index,
                        chunk = chunk,
                        isActive = isActive,
                        isInLoopRange = isInLoopRange,
                        isMasked = isMasked,
                        transcriptionStage = transcriptionStage,
                        isForcedInFlight = isThisChunkInFlight && lookaheadStatus.isForcedChunkInFlight,
                        inFlightStep = if (isThisChunkInFlight) lookaheadStatus.inFlightStep else null,
                        inFlightDetail = if (isThisChunkInFlight) lookaheadStatus.inFlightDetail else null,
                        inFlightStartedAtEpochMs = if (isThisChunkInFlight) {
                            lookaheadStatus.inFlightStartedAtEpochMs
                        } else {
                            null
                        },
                        languagePair = languagePair,
                        chineseScript = chineseScript,
                        cantoneseScript = cantoneseScript,
                        showTargetRomanization = showTargetRomanization,
                        showTargetTranslation = showTargetTranslation,
                        selectedChunks = selectedChunks,
                        onSetChunksSkipped = onSetChunksSkipped,
                        positionProvider = positionProvider,
                        onRowClick = {
                            val startId = rangeSelectionStartChunkId
                            if (startId != null) {
                                rangeSelectionStartChunkId = null
                                val ordered = orderChunkRange(chunks, startId, chunk.id)
                                if (ordered != null) {
                                    onSetLoopMode(
                                        LoopMode.ChunkRange(
                                            firstChunkId = ordered.first,
                                            lastChunkId = ordered.second,
                                            times = null,
                                        ),
                                    )
                                }
                            } else if (isMasked) {
                                onToggleRevealChunk(chunk.id)
                            } else {
                                onSeekToChunk(chunk.id)
                            }
                        },
                        onTimestampClick = {
                            onSeekToChunk(chunk.id)
                        },
                        onToggleBookmark = {
                            onToggleBookmark(chunk.id)
                        },
                        onToggleRepeatChunk = {
                            onToggleRepeatChunk(chunk.id)
                        },
                        onStartRangeSelection = {
                            rangeSelectionStartChunkId = chunk.id
                        },
                        onUpdateLanguage = { newLang ->
                            onUpdateChunkLanguage(chunk.id, newLang)
                        },
                        onRequestEditText = {
                            editingTextChunk = index to chunk
                        },
                        onRetranscribeChunk = {
                            onRetranscribeChunk(chunk.id)
                        },
                        onOpenDetailedTranscription = {
                            onOpenDetailedTranscription(chunk.id)
                        },
                        onMoveChunkBoundary = { newStartMs, newEndMs ->
                            onMoveChunkBoundary(chunk.id, newStartMs, newEndMs)
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }

        if (showTranscriptionProgress) {
            val activeChunkNumber = lookaheadStatus.inFlightChunkId?.let { inFlightId ->
                chunks.indexOfFirst { it.id == inFlightId }.takeIf { it >= 0 }?.plus(1)
            }
            TranscriptionProgressOverlay(
                lookaheadStatus = lookaheadStatus,
                inFlightChunkNumber = activeChunkNumber,
                onAbortTranscription = onResetTranscription,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = 12.dp, vertical = progressOverlayMargin)
                    .onSizeChanged { size -> progressOverlayHeightPx = size.height },
            )
        }

        if (isAutoFollowPaused && activeChunkIndex != null && activeChunkIndex in chunks.indices) {
            AssistChip(
                onClick = {
                    isAutoFollowPaused = false
                    coroutineScope.launch {
                        val scrollTargetIdx = (activeChunkIndex - 2).coerceAtLeast(0)
                        isProgrammaticScroll = true
                        try {
                            listState.animateScrollToItem(scrollTargetIdx)
                        } finally {
                            isProgrammaticScroll = false
                        }
                    }
                },
                label = { Text(text = stringResource(R.string.player_return_to_current)) },
                leadingIcon = {
                    Icon(
                        imageVector = ChunkFlowIcons.ArrowDownward,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                elevation = AssistChipDefaults.elevatedAssistChipElevation(elevation = 4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(
                        start = 12.dp,
                        top = 12.dp,
                        end = 12.dp,
                        bottom = 12.dp,
                    ),
            )
        }
    }

    editingTextChunk?.let { (chunkIndex, targetChunk) ->
        EditChunkTextDialog(
            chunkNumber = chunkIndex + 1,
            initialText = targetChunk.text.orEmpty(),
            onConfirm = { newText ->
                editingTextChunk = null
                onUpdateChunkText(targetChunk.id, newText)
            },
            onDismiss = { editingTextChunk = null },
        )
    }

    detailedTranscriptionDialog?.let { dialogState ->
        val targetChunk = chunks.firstOrNull { it.id == dialogState.chunkId }
            ?: chunks.getOrNull(dialogState.chunkIndex)
        DetailedChunkTranscriptionDialog(
            dialogState = dialogState,
            targetChunk = targetChunk,
            waveform = waveform,
            languagePair = languagePair,
            positionProvider = positionProvider,
            onSelectLanguage = onSelectDetailedTranscriptionLanguage,
            onSelectEngine = onSelectDetailedTranscriptionEngine,
            onRequestModelDownload = onRequestDetailedTranscriptionModelDownload,
            onPlayPreview = { speed ->
                onPlayDetailedTranscriptionPreview(dialogState.chunkId, speed)
            },
            onStopPreview = onStopDetailedTranscriptionPreview,
            onRunTrial = { engine, langOverride, speed ->
                onRunDetailedTranscriptionTrial(dialogState.chunkId, engine, langOverride, speed)
            },
            onApply = { text, lang ->
                onApplyDetailedTranscriptionResult(dialogState.chunkId, text, lang)
            },
            onDismiss = onDismissDetailedTranscription,
        )
    }
}

@Composable
private fun EditChunkTextDialog(
    chunkNumber: Int,
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var textValue by remember(chunkNumber, initialText) { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.player_chunk_edit_text_dialog_title, chunkNumber))
        },
        text = {
            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                label = { Text(text = stringResource(R.string.player_chunk_edit_text_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(textValue) }) {
                Text(text = stringResource(R.string.player_chunk_edit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.player_chunk_edit_cancel))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailedChunkTranscriptionDialog(
    dialogState: DetailedTranscriptionDialogState,
    targetChunk: ChunkUi?,
    waveform: Waveform?,
    languagePair: LanguagePair?,
    positionProvider: PositionProvider,
    onSelectLanguage: (LanguageTag) -> Unit,
    onSelectEngine: (OnDeviceTranscriptionEngine) -> Unit,
    onRequestModelDownload: (LanguageTag, OnDeviceTranscriptionEngine) -> Unit,
    onPlayPreview: (Float) -> Unit,
    onStopPreview: () -> Unit,
    onRunTrial: (OnDeviceTranscriptionEngine, LanguageTag?, Float) -> Unit,
    onApply: (String, LanguageTag?) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultLang = dialogState.languageOverride
        ?: resolveInitialDetailedLanguage(targetChunk?.language, languagePair)
    var selectedLanguage by remember(dialogState.chunkId, dialogState.languageOverride) {
        mutableStateOf(defaultLang)
    }
    var selectedEngine by remember(dialogState.chunkId, dialogState.engine) {
        mutableStateOf(dialogState.engine)
    }
    var selectedSpeed by remember(dialogState.chunkId) {
        mutableFloatStateOf(dialogState.audioSpeed)
    }
    var editableText by remember(dialogState.chunkId, dialogState.resultText, dialogState.hasAttempted) {
        mutableStateOf(dialogState.resultText)
    }
    var pendingDownloadEngine by remember(dialogState.chunkId) {
        mutableStateOf<OnDeviceTranscriptionEngine?>(null)
    }

    val selectedEngineStatus = dialogState.engineStatus(selectedLanguage, selectedEngine)
    val isAnyEngineDownloading = DetailedTranscriptionDialogState.ENGINE_OPTIONS.any { engine ->
        dialogState.engineStatus(selectedLanguage, engine) == OnDeviceLanguageModelStatus.DOWNLOADING
    }
    val canRunTrial = !dialogState.isRunning &&
        selectedEngineStatus != OnDeviceLanguageModelStatus.DOWNLOADING &&
        selectedEngineStatus != OnDeviceLanguageModelStatus.UNSUPPORTED

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.player_detailed_transcribe_dialog_title),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 1. 認識言語（自動判定なし、チャンク言語または学習対象言語が初期選択）
                Text(
                    text = stringResource(R.string.player_detailed_transcribe_lang_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (languagePair != null) {
                        val targetCode = languagePair.target.value.lowercase(JavaLocale.ROOT)
                        FilterChip(
                            selected = matchesLanguageRole(selectedLanguage, languagePair.target),
                            onClick = {
                                val nextLang = languagePair.target
                                selectedLanguage = nextLang
                                selectedEngine = dialogState.selectDefaultEngineForLanguage(nextLang)
                                onSelectLanguage(nextLang)
                            },
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.player_detailed_transcribe_lang_target,
                                        targetCode,
                                    ),
                                )
                            },
                            enabled = !dialogState.isRunning,
                        )
                        languagePair.base?.takeIf { !matchesLanguageRole(it, languagePair.target) }?.let { baseLang ->
                            val baseCode = baseLang.value.lowercase(JavaLocale.ROOT)
                            FilterChip(
                                selected = matchesLanguageRole(selectedLanguage, baseLang),
                                onClick = {
                                    selectedLanguage = baseLang
                                    selectedEngine = dialogState.selectDefaultEngineForLanguage(baseLang)
                                    onSelectLanguage(baseLang)
                                },
                                label = {
                                    Text(
                                        text = stringResource(
                                            R.string.player_detailed_transcribe_lang_base,
                                            baseCode,
                                        ),
                                    )
                                },
                                enabled = !dialogState.isRunning,
                            )
                        }
                    } else {
                        val langCode = selectedLanguage.value.lowercase(JavaLocale.ROOT)
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.player_detailed_transcribe_lang_target,
                                        langCode,
                                    ),
                                )
                            },
                            enabled = !dialogState.isRunning,
                        )
                    }
                }

                // 2. 文字起こしエンジン（ML Kit, SpeechRecognizer, sherpa-onnx の順、自動なし）
                Text(
                    text = stringResource(R.string.player_detailed_transcribe_engine_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (engine in DetailedTranscriptionDialogState.ENGINE_OPTIONS) {
                        val labelRes = when (engine) {
                            OnDeviceTranscriptionEngine.ML_KIT,
                            OnDeviceTranscriptionEngine.AUTO,
                            -> R.string.player_detailed_transcribe_engine_mlkit

                            OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER ->
                                R.string.player_detailed_transcribe_engine_os

                            OnDeviceTranscriptionEngine.SHERPA_ONNX ->
                                R.string.player_detailed_transcribe_engine_sherpa
                        }
                        val status = dialogState.engineStatus(selectedLanguage, engine)
                        val isUnsupported = status == OnDeviceLanguageModelStatus.UNSUPPORTED
                        val isDownloading = status == OnDeviceLanguageModelStatus.DOWNLOADING
                        val needsDownload = status == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
                            status == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                        FilterChip(
                            selected = selectedEngine == engine,
                            onClick = {
                                if (needsDownload) {
                                    pendingDownloadEngine = engine
                                } else {
                                    selectedEngine = engine
                                    onSelectEngine(engine)
                                }
                            },
                            leadingIcon = if (isDownloading) {
                                {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            } else {
                                null
                            },
                            label = { Text(text = stringResource(labelRes)) },
                            enabled = !dialogState.isRunning && !isUnsupported,
                        )
                    }
                }
                if (isAnyEngineDownloading) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.player_detailed_transcribe_model_downloading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // 3. 音声の入力速度（0.5x, 0.75x, 1.0x, 1.5x、デフォルト 1.0x）
                Text(
                    text = stringResource(R.string.player_detailed_transcribe_speed_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (speedOption in DetailedTranscriptionDialogState.AUDIO_SPEED_OPTIONS) {
                        val speedLabel = when {
                            abs(speedOption - 0.5f) < 0.01f -> "0.5x"
                            abs(speedOption - 0.75f) < 0.01f -> "0.75x"
                            abs(speedOption - 1.0f) < 0.01f -> "1.0x"
                            abs(speedOption - 1.5f) < 0.01f -> "1.5x"
                            else -> String.format(JavaLocale.ROOT, "%.2fx", speedOption)
                        }
                        FilterChip(
                            selected = abs(selectedSpeed - speedOption) < 0.01f,
                            onClick = {
                                selectedSpeed = speedOption
                                if (dialogState.isPreviewPlaying) {
                                    onPlayPreview(speedOption)
                                }
                            },
                            label = { Text(text = speedLabel) },
                            enabled = !dialogState.isRunning,
                        )
                    }
                }

                // 4. チャンクの波形と再生/停止ボタン（3の再生速度で再生）
                Text(
                    text = stringResource(R.string.player_detailed_transcribe_preview_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                DetailedChunkWaveformPreviewRow(
                    chunk = targetChunk,
                    waveform = waveform,
                    isPreviewPlaying = dialogState.isPreviewPlaying,
                    positionProvider = positionProvider,
                    enabled = !dialogState.isRunning,
                    onTogglePlayStop = {
                        if (dialogState.isPreviewPlaying) {
                            onStopPreview()
                        } else {
                            onPlayPreview(selectedSpeed)
                        }
                    },
                )

                // 5. 「この条件で文字起こしを試す」ボタン
                FilledTonalButton(
                    onClick = {
                        onRunTrial(selectedEngine, selectedLanguage, selectedSpeed)
                    },
                    enabled = canRunTrial,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (dialogState.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.player_detailed_transcribe_running))
                    } else {
                        Text(text = stringResource(R.string.player_detailed_transcribe_run_button))
                    }
                }

                // 6. 認識結果テキストボックス（編集可）
                if (dialogState.recognizedEngineId != null) {
                    val langText = (dialogState.recognizedLanguage ?: selectedLanguage).value
                        .lowercase(JavaLocale.ROOT)
                    Text(
                        text = stringResource(
                            R.string.player_detailed_transcribe_result_meta,
                            dialogState.recognizedEngineId,
                            langText,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (dialogState.hasAttempted && dialogState.resultText.isBlank() && !dialogState.isRunning) {
                    Text(
                        text = stringResource(R.string.player_detailed_transcribe_result_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedTextField(
                    value = editableText,
                    onValueChange = { editableText = it },
                    label = { Text(text = stringResource(R.string.player_detailed_transcribe_result_label)) },
                    enabled = !dialogState.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(editableText, selectedLanguage)
                },
                enabled = !dialogState.isRunning,
            ) {
                Text(text = stringResource(R.string.player_detailed_transcribe_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.player_chunk_edit_cancel))
            }
        },
    )

    pendingDownloadEngine?.let { engineToDownload ->
        AlertDialog(
            onDismissRequest = { pendingDownloadEngine = null },
            title = {
                Text(text = stringResource(R.string.player_detailed_transcribe_model_download_confirm_title))
            },
            text = {
                Text(text = stringResource(R.string.player_detailed_transcribe_model_download_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        selectedEngine = engineToDownload
                        pendingDownloadEngine = null
                        onRequestModelDownload(selectedLanguage, engineToDownload)
                    },
                ) {
                    Text(text = stringResource(R.string.player_detailed_transcribe_model_download_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDownloadEngine = null }) {
                    Text(text = stringResource(R.string.player_chunk_edit_cancel))
                }
            },
        )
    }
}

@Composable
private fun DetailedChunkWaveformPreviewRow(
    chunk: ChunkUi?,
    waveform: Waveform?,
    isPreviewPlaying: Boolean,
    positionProvider: PositionProvider,
    enabled: Boolean,
    onTogglePlayStop: () -> Unit,
) {
    val downsampler = remember { WaveformDownsampler(maxCacheEntries = 4) }
    val barColor = MaterialTheme.colorScheme.primary
    val playheadColor = MaterialTheme.colorScheme.tertiary
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val isInspectionMode = LocalInspectionMode.current
    var currentPosMs by remember(chunk?.id, isPreviewPlaying) {
        mutableLongStateOf(positionProvider.currentPositionMs())
    }
    if (isPreviewPlaying && !isInspectionMode) {
        LaunchedEffect(chunk?.id, isPreviewPlaying) {
            while (isActive) {
                withFrameNanos {
                    currentPosMs = positionProvider.currentPositionMs()
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                val centerY = size.height / 2f
                drawLine(
                    color = baselineColor,
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = 1.dp.toPx(),
                )
                if (waveform != null && chunk != null && chunk.endMs > chunk.startMs && size.width > 0f) {
                    val colWidthPx = 3.dp.toPx().coerceAtLeast(1f)
                    val columnCount = (size.width / colWidthPx).toInt().coerceIn(8, 120)
                    val sampled = downsampler.downsample(
                        waveform = waveform,
                        startMs = chunk.startMs,
                        endMs = chunk.endMs,
                        columnCount = columnCount,
                    )
                    val stepX = size.width / columnCount
                    val strokeW = (stepX * 0.65f).coerceAtLeast(1.5f)
                    for (i in 0 until sampled.size) {
                        val amp = sampled.peak[i].coerceIn(0.05f, 1f)
                        val halfH = (size.height * 0.46f) * amp
                        val x = (i + 0.5f) * stepX
                        drawLine(
                            color = barColor,
                            start = Offset(x, centerY - halfH),
                            end = Offset(x, centerY + halfH),
                            strokeWidth = strokeW,
                            cap = StrokeCap.Round,
                        )
                    }
                    if (isPreviewPlaying && currentPosMs in chunk.startMs..chunk.endMs) {
                        val fraction = ((currentPosMs - chunk.startMs).toFloat() / (chunk.endMs - chunk.startMs).toFloat())
                            .coerceIn(0f, 1f)
                        val playheadX = size.width * fraction
                        drawLine(
                            color = playheadColor,
                            start = Offset(playheadX, 0f),
                            end = Offset(playheadX, size.height),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }
            }
        }

        FilledTonalButton(
            onClick = onTogglePlayStop,
            enabled = enabled && chunk != null,
        ) {
            Icon(
                imageVector = if (isPreviewPlaying) ChunkFlowIcons.Pause else ChunkFlowIcons.Play,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(
                    if (isPreviewPlaying) {
                        R.string.player_detailed_transcribe_preview_stop
                    } else {
                        R.string.player_detailed_transcribe_preview_play
                    },
                ),
            )
        }
    }
}

@Composable
internal fun transcriptionStepLabel(step: TranscriptionStep?): String = when (step) {
    TranscriptionStep.WAITING_LOCK -> stringResource(R.string.player_transcription_step_waiting_lock)
    TranscriptionStep.EXTRACTING_PCM -> stringResource(R.string.player_transcription_step_extracting_pcm)
    TranscriptionStep.DETECTING_LANGUAGE -> stringResource(R.string.player_transcription_step_detecting_language)
    TranscriptionStep.RECOGNIZING_PRIMARY -> stringResource(R.string.player_transcription_step_recognizing_primary)
    TranscriptionStep.RECOGNIZING_FALLBACK -> stringResource(R.string.player_transcription_step_recognizing_fallback)
    TranscriptionStep.SAVING -> stringResource(R.string.player_transcription_step_saving)
    null -> stringResource(R.string.player_chunk_transcription_active)
}

/**
 * 文字起こしの進捗を、チャンクリストの下部に重ねて表示するカード。
 *
 * - 1 行目: 全体の進捗（完了数 / 対象数と待機数。強制文字起こし中はその要約）と「中断」ボタン
 * - 2 行目: 進捗バー
 * - 3 行目: 処理中のチャンクの番号・ステップ・補足（エンジンや言語）・経過時間
 *
 * 同じチャンクで [TRANSCRIPTION_STALL_WARNING_MS] 以上止まっているときは、カードを errorContainer の色にし、
 * 3 行目の末尾に短い注意を付ける（行は増やさない）。診断ログはファイル設定シートから開く。
 */
@Composable
private fun TranscriptionProgressOverlay(
    lookaheadStatus: LookaheadTranscriptionStatus,
    inFlightChunkNumber: Int?,
    onAbortTranscription: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isInspectionMode = LocalInspectionMode.current
    val startedAtMs = lookaheadStatus.inFlightStartedAtEpochMs
    var elapsedMs by remember(startedAtMs, lookaheadStatus.inFlightChunkId) {
        val initial = if (isInspectionMode || startedAtMs == null) {
            0L
        } else {
            (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        }
        mutableLongStateOf(initial)
    }
    if (!isInspectionMode && startedAtMs != null && lookaheadStatus.inFlightChunkId != null) {
        LaunchedEffect(startedAtMs, lookaheadStatus.inFlightChunkId) {
            while (isActive) {
                elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
                delay(200L)
            }
        }
    }

    val isStalled = elapsedMs >= TRANSCRIPTION_STALL_WARNING_MS
    val containerColor = when {
        isStalled -> MaterialTheme.colorScheme.errorContainer
        lookaheadStatus.isForcedChunkInFlight -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        isStalled -> MaterialTheme.colorScheme.onErrorContainer
        lookaheadStatus.isForcedChunkInFlight -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    val totalCount = max(lookaheadStatus.totalTargetCount, lookaheadStatus.completedCount)
    val stepFraction = lookaheadStatus.inFlightStep?.progressFraction ?: 0.35f
    val overallProgress = if (totalCount > 0) {
        val activeBonus = if (lookaheadStatus.inFlightChunkId != null) stepFraction else 0f
        ((lookaheadStatus.completedCount.toFloat() + activeBonus) / totalCount.toFloat()).coerceIn(0f, 1f)
    } else {
        stepFraction
    }

    val summaryText = if (lookaheadStatus.isForcedChunkInFlight) {
        stringResource(
            R.string.player_transcription_progress_forced_summary,
            lookaheadStatus.completedCount,
            totalCount,
        )
    } else {
        stringResource(
            R.string.player_transcription_progress_summary,
            lookaheadStatus.completedCount,
            totalCount,
        )
    }
    val stepLabel = transcriptionStepLabel(lookaheadStatus.inFlightStep)
    val chunkPrefix = if (inFlightChunkNumber != null) {
        "#$inFlightChunkNumber: "
    } else {
        ""
    }
    val detailSuffix = lookaheadStatus.inFlightDetail
        ?.takeIf { it.isNotBlank() }
        ?.let { " [$it]" }
        .orEmpty()
    val elapsedSuffix = if (elapsedMs > 0L) {
        String.format(JavaLocale.ROOT, " (%.1fs)", elapsedMs / 1_000f)
    } else {
        ""
    }
    val stallSuffix = if (isStalled) {
        " " + stringResource(R.string.player_transcription_stall_short)
    } else {
        ""
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(12.dp),
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 2.dp, end = 4.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ChunkTranscriptionSpinner(stage = ChunkTranscriptionStage.TRANSCRIBING)
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = onAbortTranscription,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.player_transcription_abort_button),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            LinearProgressIndicator(
                progress = { overallProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                // 既定のトラックの色はカードの色（secondaryContainer）と同じで見えないため、文字色を薄くして使う
                trackColor = contentColor.copy(alpha = 0.18f),
            )

            Text(
                text = "$chunkPrefix$stepLabel$detailSuffix$elapsedSuffix$stallSuffix",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (isStalled) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
    }
}

/**
 * 文字起こしの現在の状態・進行中ステップ・直近の診断履歴を確認し、強制リセットできるダイアログ。
 *
 * プレイヤー画面のファイル設定シート（右上の設定アイコン）の「診断ログ」ボタンから開く。
 */
@Composable
internal fun TranscriptionDiagnosticsDialog(
    lookaheadStatus: LookaheadTranscriptionStatus,
    isAudioAnalysisRunning: Boolean,
    onResetTranscription: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val stepText = lookaheadStatus.inFlightStep?.name ?: "IDLE"
    val statusSummary = buildString {
        appendLine("enabled=${lookaheadStatus.enabled}, engineAvailable=${lookaheadStatus.engineAvailable}")
        appendLine(
            "inFlightChunkId=${lookaheadStatus.inFlightChunkId?.value}, " +
                "isForced=${lookaheadStatus.isForcedChunkInFlight}, step=$stepText",
        )
        appendLine("detail=${lookaheadStatus.inFlightDetail.orEmpty()}")
        appendLine(
            "progress=${lookaheadStatus.completedCount}/${lookaheadStatus.totalTargetCount}, " +
                "queued=${lookaheadStatus.queuedChunkIds.size}, " +
                "skippedEmpty=${lookaheadStatus.skippedEmptyChunkIds.size}",
        )
        appendLine("concurrentAudioAnalysis=$isAudioAnalysisRunning")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.player_transcription_diagnostics_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = statusSummary.trimEnd(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }

                FilledTonalButton(
                    onClick = onResetTranscription,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.player_transcription_diagnostics_reset),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()

                if (lookaheadStatus.diagnostics.isEmpty()) {
                    Text(
                        text = stringResource(R.string.player_transcription_diagnostics_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for (entry in lookaheadStatus.diagnostics.asReversed()) {
                        DiagnosticEntryCard(entry = entry)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val fullLog = buildString {
                        appendLine("=== Transcription Status ===")
                        appendLine(statusSummary)
                        appendLine("=== Recent Diagnostics ===")
                        for (entry in lookaheadStatus.diagnostics) {
                            appendLine(
                                "[${entry.timestampEpochMs}] [${entry.stage}] ${entry.message}" +
                                    if (entry.isWarningOrError) " (WARN/ERR)" else "",
                            )
                        }
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Chunk Flow STT Diagnostics", fullLog))
                },
            ) {
                Text(text = stringResource(R.string.player_transcription_diagnostics_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.player_sheet_close))
            }
        },
    )
}

@Composable
private fun DiagnosticEntryCard(
    entry: TranscriptionDiagnosticEntry,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (entry.isWarningOrError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "[${entry.stage}]",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 端末内音声認識モデルが使えない言語の案内（未ダウンロード・ダウンロード中・失敗・非対応）。
 *
 * 未ダウンロードと失敗のときは、OS にダウンロードを要求するボタンを出す。
 */
@Composable
internal fun RecognitionModelIssueBanner(
    language: LanguageTag,
    state: OnDeviceRecognitionModelState,
    onRequestDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiLocale = LocalLocale.current.platformLocale
    val languageName = remember(language, uiLocale) {
        JavaLocale.forLanguageTag(language.value).getDisplayLanguage(uiLocale)
            .takeIf { it.isNotBlank() }
            ?: language.value
    }
    val message = when (state) {
        OnDeviceRecognitionModelState.NOT_INSTALLED ->
            stringResource(R.string.player_recognition_model_not_installed, languageName)

        OnDeviceRecognitionModelState.DOWNLOADING ->
            stringResource(R.string.player_recognition_model_downloading, languageName)

        OnDeviceRecognitionModelState.DOWNLOAD_FAILED ->
            stringResource(R.string.player_recognition_model_download_failed, languageName)

        OnDeviceRecognitionModelState.UNSUPPORTED ->
            stringResource(R.string.player_recognition_model_unsupported, languageName)
    }
    val canRequestDownload = state == OnDeviceRecognitionModelState.NOT_INSTALLED ||
        state == OnDeviceRecognitionModelState.DOWNLOAD_FAILED
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(
                start = 12.dp,
                end = 12.dp,
                top = 8.dp,
                bottom = if (canRequestDownload) 0.dp else 8.dp,
            ),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = ChunkFlowIcons.Download,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
            }
            if (canRequestDownload) {
                TextButton(
                    onClick = onRequestDownload,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(text = stringResource(R.string.player_recognition_model_download_action))
                }
            }
        }
    }
}

internal fun isChunkMasked(
    chunk: ChunkUi,
    blindMode: BlindMode,
    languagePair: LanguagePair?,
    revealedChunkIds: Set<ChunkId>,
): Boolean {
    if (chunk.text == null || chunk.id in revealedChunkIds) return false
    return when (blindMode) {
        BlindMode.OFF -> false

        BlindMode.HIDE_ALL -> true

        BlindMode.HIDE_TARGET_ONLY -> {
            if (languagePair == null) {
                true
            } else {
                matchesLanguageRole(chunk.language, languagePair.target)
            }
        }
    }
}

private fun resolveLoopChunkIds(
    loopMode: LoopMode,
    chunks: List<ChunkUi>,
): Set<ChunkId> = when (loopMode) {
    LoopMode.Off, is LoopMode.AbRange -> emptySet()

    is LoopMode.SingleChunk -> setOf(loopMode.chunkId)

    is LoopMode.ChunkRange -> {
        val firstIdx = chunks.indexOfFirst { it.id == loopMode.firstChunkId }
        val lastIdx = chunks.indexOfFirst { it.id == loopMode.lastChunkId }
        if (firstIdx >= 0 && lastIdx >= 0) {
            val start = min(firstIdx, lastIdx)
            val end = max(firstIdx, lastIdx)
            chunks.subList(start, end + 1).map { it.id }.toSet()
        } else {
            setOf(loopMode.firstChunkId, loopMode.lastChunkId)
        }
    }
}

private fun orderChunkRange(
    chunks: List<ChunkUi>,
    firstId: ChunkId,
    secondId: ChunkId,
): Pair<ChunkId, ChunkId>? {
    val idx1 = chunks.indexOfFirst { it.id == firstId }
    val idx2 = chunks.indexOfFirst { it.id == secondId }
    if (idx1 < 0 || idx2 < 0) return null
    return if (idx1 <= idx2) {
        firstId to secondId
    } else {
        secondId to firstId
    }
}

/**
 * 文字起こしデータがない（またはクリアされた）チャンクで
 * 「段階 1: 順番待ち（待機中）」と「段階 2: 文字起こし実行中」を視覚的に区別する 2 段階スピナー。
 *
 * - [ChunkTranscriptionStage.WAITING]: 点線リングがゆっくり回転（落ち着いた色）。
 * - [ChunkTranscriptionStage.TRANSCRIBING]: 二重アークが高速で回転（プライマリ色）。
 */
@Composable
internal fun ChunkTranscriptionSpinner(
    stage: ChunkTranscriptionStage,
    modifier: Modifier = Modifier,
) {
    if (stage == ChunkTranscriptionStage.IDLE || stage == ChunkTranscriptionStage.MODEL_UNAVAILABLE) return

    val colorScheme = MaterialTheme.colorScheme
    val isInspectionMode = LocalInspectionMode.current
    val cdText = when (stage) {
        ChunkTranscriptionStage.WAITING -> stringResource(R.string.player_chunk_transcription_waiting_cd)
        ChunkTranscriptionStage.TRANSCRIBING -> stringResource(R.string.player_chunk_transcription_active_cd)
        ChunkTranscriptionStage.IDLE, ChunkTranscriptionStage.MODEL_UNAVAILABLE -> ""
    }

    val angle = if (isInspectionMode) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "chunkTranscriptionSpinner")
        val durationMs = if (stage == ChunkTranscriptionStage.TRANSCRIBING) 720 else 2400
        val animatedAngle by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = durationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "spinnerRotation",
        )
        animatedAngle
    }

    val waitingColor = colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    val activeColor = colorScheme.primary
    val activeTrackColor = colorScheme.primary.copy(alpha = 0.22f)

    Canvas(
        modifier = modifier
            .size(16.dp)
            .semantics { contentDescription = cdText },
    ) {
        val strokePx = if (stage == ChunkTranscriptionStage.TRANSCRIBING) {
            2.2.dp.toPx()
        } else {
            1.6.dp.toPx()
        }
        when (stage) {
            ChunkTranscriptionStage.WAITING -> {
                // 段階1（待機中）: 6 セグメントの破線リングがゆっくり回る
                for (segmentIndex in 0 until 6) {
                    drawArc(
                        color = waitingColor,
                        startAngle = angle + segmentIndex * 60f,
                        sweepAngle = 28f,
                        useCenter = false,
                        style = Stroke(width = strokePx, cap = StrokeCap.Round),
                    )
                }
            }

            ChunkTranscriptionStage.TRANSCRIBING -> {
                // 段階2（文字起こし中）: 薄いトラック上に鮮やかな二重アークが高速回転する
                drawCircle(
                    color = activeTrackColor,
                    style = Stroke(width = strokePx),
                )
                drawArc(
                    color = activeColor,
                    startAngle = angle,
                    sweepAngle = 120f,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
                drawArc(
                    color = activeColor.copy(alpha = 0.75f),
                    startAngle = angle + 180f,
                    sweepAngle = 70f,
                    useCenter = false,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }

            ChunkTranscriptionStage.IDLE, ChunkTranscriptionStage.MODEL_UNAVAILABLE -> Unit
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptChunkRow(
    index: Int,
    chunk: ChunkUi,
    isActive: Boolean,
    isInLoopRange: Boolean,
    isMasked: Boolean,
    transcriptionStage: ChunkTranscriptionStage,
    languagePair: LanguagePair?,
    chineseScript: ChineseScript,
    cantoneseScript: ChineseScript,
    showTargetRomanization: Boolean,
    positionProvider: PositionProvider,
    onRowClick: () -> Unit,
    onTimestampClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleRepeatChunk: () -> Unit,
    onStartRangeSelection: () -> Unit,
    onUpdateLanguage: (LanguageTag?) -> Unit,
    onRequestEditText: () -> Unit,
    showTargetTranslation: Boolean = false,
    selectedChunks: List<ChunkUi> = emptyList(),
    onSetChunksSkipped: (Set<ChunkId>, Boolean) -> Unit = { _, _ -> },
    isForcedInFlight: Boolean = false,
    inFlightStep: TranscriptionStep? = null,
    inFlightDetail: String? = null,
    inFlightStartedAtEpochMs: Long? = null,
    onRetranscribeChunk: () -> Unit = {},
    onOpenDetailedTranscription: () -> Unit = {},
    onMoveChunkBoundary: (Long, Long) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val langPalette = chunkFlowLanguagePalette()
    val isTargetLang = matchesLanguageRole(chunk.language, languagePair?.target)
    val isBaseLang = matchesLanguageRole(chunk.language, languagePair?.base)

    val rowBackground = when {
        isForcedInFlight -> colorScheme.tertiaryContainer.copy(alpha = 0.45f)

        isActive -> when {
            isTargetLang -> langPalette.targetActiveRowBackground
            isBaseLang -> langPalette.baseActiveRowBackground
            else -> colorScheme.primaryContainer.copy(alpha = 0.36f)
        }

        isInLoopRange -> when {
            isTargetLang -> langPalette.targetLoopRowBackground
            isBaseLang -> langPalette.baseLoopRowBackground
            else -> colorScheme.tertiaryContainer.copy(alpha = 0.28f)
        }

        isTargetLang -> langPalette.targetRowBackground

        isBaseLang -> langPalette.baseRowBackground

        else -> colorScheme.surface
    }
    val indicatorColor = when {
        isForcedInFlight -> colorScheme.tertiary
        isActive -> colorScheme.primary
        isInLoopRange -> colorScheme.tertiary
        else -> Color.Transparent
    }

    val displayText = chunk.resolveDisplayText(
        chineseScript = chineseScript,
        cantoneseScript = cantoneseScript,
    )
    val localeTag = chunk.resolveLocaleTag(
        chineseScript = chineseScript,
        cantoneseScript = cantoneseScript,
    )
    val baseTextStyle = MaterialTheme.typography.bodyLarge
    val localizedTextStyle = remember(baseTextStyle, localeTag) {
        if (localeTag != null) {
            baseTextStyle.copy(localeList = LocaleList(Locale(localeTag)))
        } else {
            baseTextStyle
        }
    }

    // 単語レベルのカラオケ強調（F7.7、§7.5.5: アクティブ行の内部でのみ PositionProvider を監視）
    val wordRanges = remember(displayText, chunk.text, chunk.words) {
        if (displayText != null && chunk.words.isNotEmpty()) {
            mapWordsToCharRanges(
                displayText = displayText,
                rawText = chunk.text,
                words = chunk.words,
            )
        } else {
            null
        }
    }
    var activeWordRange by remember(isActive, isMasked, wordRanges, positionProvider) {
        val initialRange = if (isActive && !isMasked && wordRanges != null) {
            findActiveWordRange(wordRanges, positionProvider.currentPositionMs())
        } else {
            null
        }
        mutableStateOf(initialRange)
    }
    if (!LocalInspectionMode.current && isActive && !isMasked && wordRanges != null) {
        LaunchedEffect(isActive, isMasked, wordRanges, positionProvider) {
            while (isActive) {
                withFrameNanos {
                    val currentRange = findActiveWordRange(wordRanges, positionProvider.currentPositionMs())
                    if (currentRange != activeWordRange) {
                        activeWordRange = currentRange
                    }
                }
            }
        }
    }

    val bookmarkCd = if (chunk.isBookmarked) {
        stringResource(R.string.player_bookmark_toggle_remove)
    } else {
        stringResource(R.string.player_bookmark_toggle_add)
    }
    val repeatCheckCd = if (chunk.isRepeatChecked) {
        stringResource(R.string.player_repeat_check_remove)
    } else {
        stringResource(R.string.player_repeat_check_add)
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .heightIn(min = 48.dp)
                .background(rowBackground)
                .combinedClickable(
                    onClick = onRowClick,
                    onLongClick = { menuExpanded = true },
                )
                .padding(end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左端のインジケーターバー（§7.5.5）
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(indicatorColor),
            )

            Spacer(modifier = Modifier.width(6.dp))

            RepeatChunkCheckbox(
                checked = chunk.isRepeatChecked,
                contentDescription = repeatCheckCd,
                onToggle = onToggleRepeatChunk,
            )

            Spacer(modifier = Modifier.width(4.dp))

            // タイムスタンプ（ブラインド中もタップで頭出し可能）
            Text(
                text = formatDurationMs(chunk.startMs),
                style = MaterialTheme.typography.labelMedium,
                color = if (isActive) colorScheme.primary else colorScheme.onSurfaceVariant,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .clickable(onClick = onTimestampClick)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
            )

            Spacer(modifier = Modifier.width(6.dp))

            ChunkLanguageBadge(
                language = chunk.language,
                languagePair = languagePair,
                modifier = Modifier.clickable(enabled = languagePair != null) {
                    val nextLang = nextToggledChunkLanguage(chunk.language, languagePair)
                    if (nextLang != null) {
                        onUpdateLanguage(nextLang)
                    }
                },
            )
            if (transcriptionStage != ChunkTranscriptionStage.IDLE && !displayText.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                ChunkTranscriptionSpinner(stage = transcriptionStage)
            }
            Spacer(modifier = Modifier.width(8.dp))

            val primaryTextColor = if (chunk.isSkipped) {
                colorScheme.onSurface.copy(alpha = 0.38f)
            } else {
                colorScheme.onSurface
            }
            val secondaryTextColor = if (chunk.isSkipped) {
                colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            } else {
                colorScheme.onSurfaceVariant
            }

            if (!displayText.isNullOrBlank() && !isForcedInFlight) {
                val isTargetOrNoPair = isTargetLang || languagePair == null
                val shouldShowPhonetic = showTargetRomanization && isTargetOrNoPair
                val phoneticReading = remember(
                    shouldShowPhonetic,
                    chunk,
                    chineseScript,
                    cantoneseScript,
                ) {
                    if (shouldShowPhonetic) {
                        chunk.resolvePhoneticReading(
                            chineseScript = chineseScript,
                            cantoneseScript = cantoneseScript,
                        )
                    } else {
                        null
                    }
                }
                val translatedReading = if (showTargetTranslation && isTargetOrNoPair) {
                    chunk.translatedText?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
                if (isMasked) {
                    if (phoneticReading != null || translatedReading != null) {
                        // 「学習言語を隠す」かつ「読み」または「翻訳」がオンで表示するテキストがあるときは、マスクバナーや本文は出さず「読み」とその下に「翻訳」だけを表示する
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            if (phoneticReading != null) {
                                Text(
                                    text = phoneticReading,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    color = primaryTextColor,
                                )
                            }
                            if (translatedReading != null) {
                                Text(
                                    text = translatedReading,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = secondaryTextColor,
                                )
                            }
                        }
                    } else {
                        Surface(
                            color = colorScheme.surfaceContainerHighest,
                            contentColor = secondaryTextColor,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 8.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(
                                    imageVector = ChunkFlowIcons.VisibilityOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Text(
                                    text = stringResource(R.string.player_blind_masked_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                } else {
                    val currentActiveWord = activeWordRange
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (currentActiveWord != null &&
                            currentActiveWord.startCharIndex in 0 until displayText.length &&
                            currentActiveWord.endCharIndex in (currentActiveWord.startCharIndex + 1)..displayText.length
                        ) {
                            val highlightedText: AnnotatedString = buildAnnotatedString {
                                append(displayText)
                                addStyle(
                                    style = SpanStyle(
                                        background = colorScheme.primaryContainer,
                                        color = colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Bold,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                    start = currentActiveWord.startCharIndex,
                                    end = currentActiveWord.endCharIndex,
                                )
                            }
                            Text(
                                text = highlightedText,
                                style = localizedTextStyle,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = primaryTextColor,
                            )
                        } else {
                            Text(
                                text = displayText,
                                style = localizedTextStyle,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = primaryTextColor,
                            )
                        }
                        if (phoneticReading != null) {
                            Text(
                                text = phoneticReading,
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryTextColor,
                            )
                        }
                        if (translatedReading != null) {
                            Text(
                                text = translatedReading,
                                style = MaterialTheme.typography.bodySmall,
                                color = secondaryTextColor,
                            )
                        }
                    }
                }
            } else {
                // 文字起こしがない（または強制文字起こし割り込み中）チャンク:
                // 待機中 / 文字起こし中の2段階スピナーとステップ進捗を表示する
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (transcriptionStage != ChunkTranscriptionStage.IDLE) {
                            ChunkTranscriptionSpinner(stage = transcriptionStage)
                            val stageLabel = when {
                                isForcedInFlight ->
                                    stringResource(R.string.player_chunk_transcription_forced_active)

                                transcriptionStage == ChunkTranscriptionStage.TRANSCRIBING ->
                                    transcriptionStepLabel(inFlightStep)

                                transcriptionStage == ChunkTranscriptionStage.WAITING ->
                                    stringResource(R.string.player_chunk_transcription_waiting)

                                transcriptionStage == ChunkTranscriptionStage.MODEL_UNAVAILABLE ->
                                    stringResource(R.string.player_chunk_transcription_model_unavailable)

                                else -> ""
                            }
                            val stageColor = when {
                                isForcedInFlight -> colorScheme.tertiary
                                transcriptionStage == ChunkTranscriptionStage.TRANSCRIBING -> colorScheme.primary
                                else -> colorScheme.onSurfaceVariant
                            }
                            Text(
                                text = stageLabel,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (transcriptionStage == ChunkTranscriptionStage.TRANSCRIBING) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                color = stageColor,
                            )
                        } else {
                            Text(
                                text = stringResource(R.string.player_chunk_number_format, index + 1),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = primaryTextColor,
                            )
                        }
                        // 時刻の範囲は左の開始時刻と重なる補足情報のため、幅が足りないときは省略して状態の表示を優先する
                        Text(
                            text = "${formatDurationMs(chunk.startMs)} - ${formatDurationMs(chunk.endMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = secondaryTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }

                    if (transcriptionStage == ChunkTranscriptionStage.TRANSCRIBING) {
                        val stepProgress = inFlightStep?.progressFraction ?: 0.35f
                        LinearProgressIndicator(
                            progress = { stepProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                        val detailParts = buildList {
                            if (isForcedInFlight && inFlightStep != null) {
                                add(transcriptionStepLabel(inFlightStep))
                            }
                            if (!inFlightDetail.isNullOrBlank()) {
                                add(inFlightDetail)
                            }
                            if (!LocalInspectionMode.current && inFlightStartedAtEpochMs != null) {
                                val elapsedSec =
                                    ((System.currentTimeMillis() - inFlightStartedAtEpochMs).coerceAtLeast(0L)) / 1_000f
                                if (elapsedSec >= 0.5f) {
                                    add(String.format(JavaLocale.ROOT, "%.1fs", elapsedSec))
                                }
                            }
                        }
                        if (detailParts.isNotEmpty()) {
                            Text(
                                text = detailParts.joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onToggleBookmark,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (chunk.isBookmarked) {
                        ChunkFlowIcons.Bookmark
                    } else {
                        ChunkFlowIcons.BookmarkBorder
                    },
                    contentDescription = bookmarkCd,
                    tint = if (chunk.isBookmarked) {
                        colorScheme.primary
                    } else {
                        colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    },
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.player_chunk_edit_text)) },
                onClick = {
                    menuExpanded = false
                    onRequestEditText()
                },
            )
            val textToUse = displayText ?: chunk.text
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.player_chunk_copy_text)) },
                enabled = !textToUse.isNullOrBlank(),
                onClick = {
                    menuExpanded = false
                    if (!textToUse.isNullOrBlank()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Chunk Flow transcript", textToUse))
                    }
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(
                            if (chunk.isSkipped) {
                                R.string.player_chunk_skip_disable
                            } else {
                                R.string.player_chunk_skip_enable
                            },
                        ),
                    )
                },
                onClick = {
                    menuExpanded = false
                    onSetChunksSkipped(setOf(chunk.id), !chunk.isSkipped)
                },
            )
            val targetChunks = if (selectedChunks.isNotEmpty()) {
                if (chunk.isRepeatChecked) selectedChunks else (selectedChunks + chunk).distinctBy { it.id }
            } else {
                emptyList()
            }
            if (targetChunks.size >= 2) {
                val targetChunkIds = targetChunks.map { it.id }.toSet()
                if (targetChunks.any { !it.isSkipped }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    R.string.player_chunk_skip_enable_selected,
                                    targetChunks.size,
                                ),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onSetChunksSkipped(targetChunkIds, true)
                        },
                    )
                }
                if (targetChunks.any { it.isSkipped }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    R.string.player_chunk_skip_disable_selected,
                                    targetChunks.size,
                                ),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onSetChunksSkipped(targetChunkIds, false)
                        },
                    )
                }
            }
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.player_chunk_retranscribe)) },
                onClick = {
                    menuExpanded = false
                    onRetranscribeChunk()
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.player_chunk_detailed_transcribe)) },
                onClick = {
                    menuExpanded = false
                    onOpenDetailedTranscription()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.player_chunk_range_select_start)) },
                onClick = {
                    menuExpanded = false
                    onStartRangeSelection()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.player_chunk_boundary_start_minus)) },
                onClick = {
                    menuExpanded = false
                    onMoveChunkBoundary((chunk.startMs - 100L).coerceAtLeast(0L), chunk.endMs)
                },
            )
            if (chunk.startMs + 100L < chunk.endMs) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_chunk_boundary_start_plus)) },
                    onClick = {
                        menuExpanded = false
                        onMoveChunkBoundary(chunk.startMs + 100L, chunk.endMs)
                    },
                )
            }
            if (chunk.endMs - 100L > chunk.startMs) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_chunk_boundary_end_minus)) },
                    onClick = {
                        menuExpanded = false
                        onMoveChunkBoundary(chunk.startMs, chunk.endMs - 100L)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.player_chunk_boundary_end_plus)) },
                onClick = {
                    menuExpanded = false
                    onMoveChunkBoundary(chunk.startMs, chunk.endMs + 100L)
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UnknownLanguageBanner(
    languagePair: LanguagePair,
    onBulkSetUnknownLanguage: (LanguageTag) -> Unit,
    modifier: Modifier = Modifier,
) {
    val langPalette = chunkFlowLanguagePalette()
    val targetCode = languagePair.target.value.lowercase(JavaLocale.ROOT)
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AssistChip(
            onClick = { onBulkSetUnknownLanguage(languagePair.target) },
            label = {
                Text(
                    text = stringResource(
                        R.string.player_bulk_set_unknown_target,
                        targetCode,
                    ),
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = ChunkFlowIcons.Translate,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            },
            colors = AssistChipDefaults.assistChipColors(
                containerColor = langPalette.targetBadgeContainer,
                labelColor = langPalette.onTargetBadgeContainer,
                leadingIconContentColor = langPalette.onTargetBadgeContainer,
            ),
        )
        languagePair.base?.let { baseLang ->
            val baseCode = baseLang.value.lowercase(JavaLocale.ROOT)
            AssistChip(
                onClick = { onBulkSetUnknownLanguage(baseLang) },
                label = {
                    Text(
                        text = stringResource(
                            R.string.player_bulk_set_unknown_base,
                            baseCode,
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = ChunkFlowIcons.Translate,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = langPalette.baseBadgeContainer,
                    labelColor = langPalette.onBaseBadgeContainer,
                    leadingIconContentColor = langPalette.onBaseBadgeContainer,
                ),
            )
        }
    }
}

/**
 * 言語コードバッジ（§7.5.5: 色とテキストの両方で役割を示し、未検出は `-`）。
 */
@Composable
internal fun ChunkLanguageBadge(
    language: LanguageTag?,
    languagePair: LanguagePair?,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val langPalette = chunkFlowLanguagePalette()
    val normalizedValue = language?.value?.trim()?.takeUnless {
        it.isEmpty() || it.equals("und", ignoreCase = true)
    }
    val isTarget = matchesLanguageRole(language, languagePair?.target)
    val isBase = matchesLanguageRole(language, languagePair?.base)

    val containerColor = when {
        isTarget -> langPalette.targetBadgeContainer
        isBase -> langPalette.baseBadgeContainer
        normalizedValue != null -> colorScheme.secondaryContainer
        else -> colorScheme.surfaceVariant
    }
    val contentColor = when {
        isTarget -> langPalette.onTargetBadgeContainer
        isBase -> langPalette.onBaseBadgeContainer
        normalizedValue != null -> colorScheme.onSecondaryContainer
        else -> colorScheme.onSurfaceVariant
    }
    val badgeText = normalizedValue?.lowercase(JavaLocale.ROOT)?.ifBlank { "-" } ?: "-"

    Surface(
        modifier = modifier.clip(RoundedCornerShape(4.dp)),
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun RepeatChunkCheckbox(
    checked: Boolean,
    contentDescription: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (checked) {
        colorScheme.tertiaryContainer
    } else {
        colorScheme.surfaceContainerLow
    }
    val contentColor = if (checked) {
        colorScheme.onTertiaryContainer
    } else {
        colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    }
    val boxBorderColor = if (checked) {
        colorScheme.tertiary
    } else {
        colorScheme.outline.copy(alpha = 0.7f)
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(6.dp),
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .semantics(mergeDescendants = true) {
                this.contentDescription = contentDescription
            },
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 5.dp, vertical = 5.dp)
                .size(16.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (checked) colorScheme.tertiary else colorScheme.surface)
                .border(1.dp, boxBorderColor, RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = ChunkFlowIcons.Check,
                    contentDescription = null,
                    tint = colorScheme.onTertiary,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun RangeSelectionBanner(
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.player_chunk_range_selecting_banner),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) {
                Text(text = stringResource(R.string.player_chunk_range_cancel))
            }
        }
    }
}

@Composable
private fun TranscriptImportStatusBanner(
    banner: TranscriptImportBannerState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isError = banner !is TranscriptImportBannerState.Attached
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
    val message = when (banner) {
        is TranscriptImportBannerState.Attached ->
            if (banner.durationMismatch) {
                stringResource(R.string.player_transcript_attached_mismatch, banner.chunkCount)
            } else {
                stringResource(R.string.player_transcript_attached, banner.chunkCount)
            }

        is TranscriptImportBannerState.NoMatchingAudio ->
            stringResource(R.string.player_transcript_no_matching_audio)

        is TranscriptImportBannerState.Invalid ->
            stringResource(R.string.player_transcript_invalid)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = ChunkFlowIcons.Close,
                    contentDescription = stringResource(R.string.player_dismiss_banner),
                )
            }
        }
    }
}

@Composable
private fun TranscriptExportStatusBanner(
    banner: TranscriptExportBannerState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isError = banner is TranscriptExportBannerState.Failed
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
    val message = when (banner) {
        is TranscriptExportBannerState.Exported ->
            stringResource(
                R.string.player_export_done,
                banner.format.name,
            )

        TranscriptExportBannerState.ResetCompleted ->
            stringResource(R.string.player_transcript_reset_done)

        TranscriptExportBannerState.Failed ->
            stringResource(R.string.player_export_failed)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = ChunkFlowIcons.Close,
                    contentDescription = stringResource(R.string.player_dismiss_banner),
                )
            }
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun TranscriptListPreview() {
    val material = SampleMaterials.jaZh
    val chunks = material.chunks.map { chunk ->
        val transcript = material.transcripts[chunk.id]
        ChunkUi(
            id = chunk.id,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            text = transcript?.text,
            language = transcript?.language,
            textVariants = transcript?.textVariants.orEmpty(),
            words = transcript?.words.orEmpty(),
        )
    }
    ChunkFlowTheme {
        Surface {
            TranscriptList(
                chunks = chunks,
                activeChunkIndex = 1,
                languagePair = material.item.languagePair,
                loopMode = LoopMode.Off,
                blindMode = BlindMode.OFF,
                revealedChunkIds = emptySet(),
                chineseScript = ChineseScript.HANT,
                cantoneseScript = ChineseScript.HANT,
                transcriptImportBanner = null,
                onSeekToChunk = {},
                onSetLoopMode = {},
                onToggleRevealChunk = {},
                onPickTranscriptJson = {},
                onDismissTranscriptBanner = {},
            )
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun TranscriptListTranscriptionStagesPreview() {
    val material = SampleMaterials.jaKo
    val chunks = material.chunks.mapIndexed { index, chunk ->
        val transcript = material.transcripts[chunk.id]
        ChunkUi(
            id = chunk.id,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            text = if (index == 0) transcript?.text else null,
            language = transcript?.language,
        )
    }
    ChunkFlowTheme {
        Surface {
            TranscriptList(
                chunks = chunks,
                activeChunkIndex = 1,
                languagePair = material.item.languagePair,
                loopMode = LoopMode.Off,
                blindMode = BlindMode.OFF,
                revealedChunkIds = emptySet(),
                chineseScript = ChineseScript.HANS,
                cantoneseScript = ChineseScript.HANT,
                transcriptImportBanner = null,
                lookaheadStatus = LookaheadTranscriptionStatus(
                    enabled = true,
                    engineAvailable = true,
                    inFlightChunkId = chunks.getOrNull(1)?.id,
                    queuedChunkIds = chunks.drop(2).map { it.id }.toSet(),
                    completedCount = 1,
                    totalTargetCount = chunks.size,
                ),
                onSeekToChunk = {},
                onSetLoopMode = {},
                onToggleRevealChunk = {},
                onPickTranscriptJson = {},
                onDismissTranscriptBanner = {},
            )
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun TranscriptListRecognitionModelMissingPreview() {
    val material = SampleMaterials.jaKo
    val koTag = material.item.languagePair.target
    val chunks = material.chunks.map { chunk ->
        val transcript = material.transcripts[chunk.id]
        val isKorean = transcript?.language?.value?.startsWith(koTag.value) == true
        ChunkUi(
            id = chunk.id,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            // 韓国語の音声認識モデルがないため、韓国語のチャンクは言語だけ判定されて本文がない状態
            text = if (isKorean) null else transcript?.text,
            language = transcript?.language,
        )
    }
    ChunkFlowTheme {
        Surface {
            TranscriptList(
                chunks = chunks,
                activeChunkIndex = 0,
                languagePair = material.item.languagePair,
                loopMode = LoopMode.Off,
                blindMode = BlindMode.OFF,
                revealedChunkIds = emptySet(),
                chineseScript = ChineseScript.HANS,
                cantoneseScript = ChineseScript.HANT,
                transcriptImportBanner = null,
                lookaheadStatus = LookaheadTranscriptionStatus(
                    enabled = true,
                    engineAvailable = true,
                    completedCount = chunks.size,
                    totalTargetCount = chunks.size,
                    recognitionModelIssues = mapOf(koTag to OnDeviceRecognitionModelState.NOT_INSTALLED),
                ),
                onSeekToChunk = {},
                onSetLoopMode = {},
                onToggleRevealChunk = {},
                onPickTranscriptJson = {},
                onDismissTranscriptBanner = {},
            )
        }
    }
}
