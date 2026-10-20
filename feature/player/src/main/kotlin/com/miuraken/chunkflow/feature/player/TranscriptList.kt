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
import androidx.compose.foundation.interaction.DragInteraction
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowWavyLinearProgress
import com.miuraken.chunkflow.core.designsystem.component.language.LanguageBadgeRole
import com.miuraken.chunkflow.core.designsystem.component.language.LanguageRoleBadge
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
 * 「より詳しく文字起こし」ダイアログの実行前チェック結果から表示する警告カード情報。
 */
internal data class DetailedPrecheckWarningUiModel(
    val issue: DetailedTranscriptionPrecheckIssue,
    val message: String,
    val actionLabel: String?,
    val isErrorStyle: Boolean,
)

/**
 * [DetailedTranscriptionDialogState] と選択中の言語・エンジン・マイク権限から、
 * 実行前に表示する警告カードモデルを返す。問題がない場合は `null` を返す。
 */
internal fun resolveDetailedPrecheckWarning(
    dialogState: DetailedTranscriptionDialogState,
    selectedLanguage: LanguageTag = dialogState.selectedLanguageTag,
    selectedEngine: OnDeviceTranscriptionEngine = dialogState.selectedEngine,
    hasRecordAudioPermission: Boolean = dialogState.hasRecordAudioPermission,
): DetailedPrecheckWarningUiModel? {
    val issue = dialogState.precheckIssue(
        language = selectedLanguage,
        targetEngine = selectedEngine,
        recordAudioPermissionGranted = hasRecordAudioPermission,
    ) ?: return null
    return when (issue) {
        DetailedTranscriptionPrecheckIssue.MIC_PERMISSION_REQUIRED ->
            DetailedPrecheckWarningUiModel(
                issue = issue,
                message = "SpeechRecognizer の使用にはマイク権限（RECORD_AUDIO）が必要です",
                actionLabel = "マイク権限を許可する",
                isErrorStyle = true,
            )

        DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOAD_REQUIRED ->
            DetailedPrecheckWarningUiModel(
                issue = issue,
                message = "選択中の言語（${selectedLanguage.value}）とエンジンの音声認識モデルがダウンロードされていません。このままでは文字起こしに失敗します。",
                actionLabel = "モデルをダウンロード",
                isErrorStyle = true,
            )

        DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOADING ->
            DetailedPrecheckWarningUiModel(
                issue = issue,
                message = "現在モデルをダウンロード中です。完了するまでお待ちいただくか、別のエンジンを選択してください。",
                actionLabel = null,
                isErrorStyle = false,
            )

        DetailedTranscriptionPrecheckIssue.ENGINE_UNSUPPORTED ->
            DetailedPrecheckWarningUiModel(
                issue = issue,
                message = "このエンジンは選択した言語に対応していません。別のエンジンを選択してください。",
                actionLabel = null,
                isErrorStyle = true,
            )
    }
}

/**
 * 先読み文字起こしの `recognitionModelIssues` のうち、未導入・失敗・非対応のモデル問題に対して
 * 進捗ツールチップ等に表示する警告文言を組み立てる。
 */
internal fun formatProgressModelIssueWarning(languageTag: LanguageTag): String = "設定の確認: ${languageTag.value} の音声認識モデルが未準備です（設定からダウンロードまたはエンジン変更が必要です）"

/**
 * 先読み文字起こしの `recognitionModelIssues` から、未準備（`NOT_INSTALLED` / `DOWNLOAD_FAILED` / `UNSUPPORTED`）の
 * 言語タグ一覧を抽出する。
 */
internal fun extractUnpreparedModelIssueLanguages(
    recognitionModelIssues: Map<LanguageTag, OnDeviceRecognitionModelState>,
): List<LanguageTag> = recognitionModelIssues.entries
    .filter { (_, state) ->
        state == OnDeviceRecognitionModelState.NOT_INSTALLED ||
            state == OnDeviceRecognitionModelState.DOWNLOAD_FAILED ||
            state == OnDeviceRecognitionModelState.UNSUPPORTED
    }
    .map { (lang, _) -> lang }

/** 文字起こし進捗ペーンのバックグラウンド文字起こし継続チェックボックスのラベル。 */
internal const val BACKGROUND_TRANSCRIPTION_CHECKBOX_LABEL: String = "バックグラウンドで文字起こしを行う"

/**
 * 文字起こし進捗ペーン（[TranscriptionProgressOverlay]）に表示するチェックボックス情報。
 */
internal data class TranscriptionProgressCheckboxUiModel(
    val label: String = BACKGROUND_TRANSCRIPTION_CHECKBOX_LABEL,
    val checked: Boolean,
)

/**
 * [LookaheadTranscriptionStatus] から文字起こし進捗ペーンのバックグラウンド文字起こしチェックボックス状態を組み立てる。
 */
internal fun resolveTranscriptionProgressCheckbox(
    lookaheadStatus: LookaheadTranscriptionStatus,
): TranscriptionProgressCheckboxUiModel = TranscriptionProgressCheckboxUiModel(
    label = BACKGROUND_TRANSCRIPTION_CHECKBOX_LABEL,
    checked = lookaheadStatus.backgroundEnabled,
)

/**
 * 再生位置に同期したチャンク / スクリプト一覧（F5.3、F7.1〜F7.7、F8.1、§7.5.5）。
 *
 * - `LazyColumn` の `key` にチャンク ID を指定し、現在のチャンク（[activeChunkIndex]）を背景色と左端バーで強調する。
 * - 現在のチャンクが変わったら自動スクロールで追従し、ユーザーがチャンクリストをスクロール・操作したときは、
 *   コントローラーや波形での再生・シーク・チャンク移動（[autoFollowResetToken] の更新）または「現在位置へ戻る」ボタンのタップまで追従を止める。
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
    onSeekToChunk: (ChunkId) -> Unit,
    onSetLoopMode: (LoopMode) -> Unit,
    onToggleRevealChunk: (ChunkId) -> Unit,
    modifier: Modifier = Modifier,
    showTargetRomanization: Boolean = false,
    showTargetTranslation: Boolean = false,
    bookmarksOnly: Boolean = false,
    positionProvider: PositionProvider = PositionProvider { 0L },
    lookaheadStatus: LookaheadTranscriptionStatus = LookaheadTranscriptionStatus(),
    showTranscriptionProgress: Boolean = lookaheadStatus.inFlightChunkId != null ||
        lookaheadStatus.isForcedChunkInFlight ||
        (lookaheadStatus.enabled && lookaheadStatus.queuedChunkIds.isNotEmpty()),
    onToggleBookmark: (ChunkId) -> Unit = {},
    onToggleRepeatChunk: (ChunkId) -> Unit = {},
    onSetChunksSkipped: (Set<ChunkId>, Boolean) -> Unit = { _, _ -> },
    onUpdateChunkLanguage: (ChunkId, LanguageTag?) -> Unit = { _, _ -> },
    onUpdateChunkText: (ChunkId, String) -> Unit = { _, _ -> },
    onRetranscribeChunk: (ChunkId) -> Unit = {},
    onMoveChunkBoundary: (ChunkId, Long, Long) -> Unit = { _, _, _ -> },
    onDeleteChunk: (ChunkId) -> Unit = {},
    onBulkSetUnknownLanguage: (LanguageTag) -> Unit = {},
    onToggleBackgroundTranscription: (Boolean) -> Unit = {},
    onDismissTranscriptionProgress: () -> Unit = {},
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
    hasSameTargetAndBaseLanguage: Boolean = languagePair?.base != null &&
        matchesLanguageRole(languagePair.target, languagePair.base),
    isAllTranscriptionEmptyAfterCompletion: Boolean = false,
    onOpenFileSettings: () -> Unit = {},
    hasRecordAudioPermission: Boolean = detailedTranscriptionDialog?.hasRecordAudioPermission ?: true,
    onRequestRecordAudioPermission: () -> Unit = {},
    autoFollowResetToken: Long = 0L,
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isInspectionMode = LocalInspectionMode.current

    var programmaticScrollCount by remember { mutableIntStateOf(0) }
    var isAutoFollowPaused by remember { mutableStateOf(false) }
    var lastHandledResetToken by remember { mutableLongStateOf(autoFollowResetToken) }
    if (autoFollowResetToken != lastHandledResetToken) {
        lastHandledResetToken = autoFollowResetToken
        isAutoFollowPaused = false
    }
    var rangeSelectionStartChunkId by remember { mutableStateOf<ChunkId?>(null) }
    var editingTextChunk by remember { mutableStateOf<Pair<Int, ChunkUi>?>(null) }

    val displayedChunks = remember(chunks, bookmarksOnly) {
        chunks.mapIndexedNotNull { originalIndex, chunk ->
            if (!bookmarksOnly || chunk.isBookmarked) {
                originalIndex to chunk
            } else {
                null
            }
        }
    }
    var polledActiveChunkIndex by remember(activeChunkIndex, chunks) {
        mutableStateOf(
            activeChunkIndex ?: resolveChunkIndexAtPosition(
                chunks = chunks,
                positionMs = positionProvider.currentPositionMs(),
            ),
        )
    }
    if (!isInspectionMode && chunks.isNotEmpty()) {
        LaunchedEffect(activeChunkIndex, chunks, positionProvider) {
            if (activeChunkIndex != null) {
                polledActiveChunkIndex = activeChunkIndex
            } else {
                while (true) {
                    val posMs = positionProvider.currentPositionMs()
                    val resolved = resolveChunkIndexAtPosition(chunks, posMs)
                    if (resolved != polledActiveChunkIndex) {
                        polledActiveChunkIndex = resolved
                    }
                    delay(100L)
                }
            }
        }
    }
    val effectiveActiveChunkIndex = activeChunkIndex ?: polledActiveChunkIndex

    val activeDisplayIndex = remember(effectiveActiveChunkIndex, displayedChunks) {
        effectiveActiveChunkIndex?.let { targetIdx ->
            displayedChunks.indexOfFirst { (originalIdx, _) -> originalIdx == targetIdx }.takeIf { it >= 0 }
        }
    }

    // ユーザーによるドラッグ開始の検出
    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) {
                isAutoFollowPaused = true
            }
        }
    }

    // ユーザーによるスクロールの検出（プログラムによるスクロール中は除外）
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && programmaticScrollCount == 0) {
            isAutoFollowPaused = true
        }
    }

    val userScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && programmaticScrollCount == 0 && available.y != 0f) {
                    isAutoFollowPaused = true
                }
                return Offset.Zero
            }
        }
    }

    // activeChunkIndex または autoFollowResetToken が変わったときの自動追従スクロール
    if (!isInspectionMode) {
        LaunchedEffect(activeDisplayIndex, displayedChunks.size, autoFollowResetToken) {
            val targetIdx = activeDisplayIndex ?: return@LaunchedEffect
            if (!isAutoFollowPaused && targetIdx in displayedChunks.indices) {
                programmaticScrollCount++
                try {
                    listState.animateScrollToItem(resolveAutoFollowScrollTargetIndex(targetIdx))
                } finally {
                    programmaticScrollCount--
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

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            for ((issueLanguage, issueState) in lookaheadStatus.recognitionModelIssues) {
                RecognitionModelIssueBanner(
                    language = issueLanguage,
                    state = issueState,
                    onRequestDownload = { onRequestRecognitionModelDownload(issueLanguage) },
                    onOpenFileSettings = onOpenFileSettings,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = hasSameTargetAndBaseLanguage && languagePair != null,
            ) {
                if (languagePair != null) {
                    SameTargetAndBaseLanguageBanner(
                        language = languagePair.target,
                        onOpenFileSettings = onOpenFileSettings,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = isAllTranscriptionEmptyAfterCompletion,
            ) {
                AllTranscriptionEmptyWarningBanner(
                    languagePair = languagePair,
                    onOpenFileSettings = onOpenFileSettings,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = hasUnknownLanguageChunks && languagePair != null,
            ) {
                if (languagePair != null) {
                    UnknownLanguageBanner(
                        languagePair = languagePair,
                        onBulkSetUnknownLanguage = onBulkSetUnknownLanguage,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = rangeSelectionStartChunkId != null,
            ) {
                RangeSelectionBanner(
                    onCancel = { rangeSelectionStartChunkId = null },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .nestedScroll(userScrollConnection),
                contentPadding = PaddingValues(top = 4.dp, bottom = 4.dp),
            ) {
                items(
                    items = displayedChunks,
                    key = { (_, chunk) -> chunk.id.value },
                ) { (index, chunk) ->
                    val isActive = index == effectiveActiveChunkIndex
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

                    Column {
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
                            onUserInteract = {
                                isAutoFollowPaused = true
                            },
                            onRowClick = {
                                val startId = rangeSelectionStartChunkId
                                if (startId != null) {
                                    isAutoFollowPaused = true
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
                                    isAutoFollowPaused = true
                                    onToggleRevealChunk(chunk.id)
                                } else {
                                    onSeekToChunk(chunk.id)
                                }
                            },
                            onTimestampClick = {
                                onSeekToChunk(chunk.id)
                            },
                            onToggleBookmark = {
                                isAutoFollowPaused = true
                                onToggleBookmark(chunk.id)
                            },
                            onToggleRepeatChunk = {
                                isAutoFollowPaused = true
                                onToggleRepeatChunk(chunk.id)
                            },
                            onStartRangeSelection = {
                                isAutoFollowPaused = true
                                rangeSelectionStartChunkId = chunk.id
                            },
                            onUpdateLanguage = { newLang ->
                                isAutoFollowPaused = true
                                onUpdateChunkLanguage(chunk.id, newLang)
                            },
                            onRequestEditText = {
                                isAutoFollowPaused = true
                                editingTextChunk = index to chunk
                            },
                            onRetranscribeChunk = {
                                isAutoFollowPaused = true
                                onRetranscribeChunk(chunk.id)
                            },
                            onOpenDetailedTranscription = {
                                isAutoFollowPaused = true
                                onOpenDetailedTranscription(chunk.id)
                            },
                            onMoveChunkBoundary = { newStartMs, newEndMs ->
                                isAutoFollowPaused = true
                                onMoveChunkBoundary(chunk.id, newStartMs, newEndMs)
                            },
                            onDeleteChunk = {
                                isAutoFollowPaused = true
                                onDeleteChunk(chunk.id)
                            },
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    }
                }
            }
        }

        if (isAutoFollowPaused && activeDisplayIndex != null && activeDisplayIndex in displayedChunks.indices) {
            AssistChip(
                onClick = {
                    isAutoFollowPaused = false
                    coroutineScope.launch {
                        programmaticScrollCount++
                        try {
                            listState.animateScrollToItem(resolveAutoFollowScrollTargetIndex(activeDisplayIndex))
                        } finally {
                            programmaticScrollCount--
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
            hasRecordAudioPermission = hasRecordAudioPermission,
            onRequestRecordAudioPermission = onRequestRecordAudioPermission,
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
    onOpenFileSettings: () -> Unit = {},
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
        shape = MaterialTheme.shapes.medium,
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
                Row(
                    modifier = Modifier.align(Alignment.End),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onOpenFileSettings) {
                        Text(text = stringResource(R.string.player_open_file_settings_action))
                    }
                    TextButton(onClick = onRequestDownload) {
                        Text(text = stringResource(R.string.player_recognition_model_download_action))
                    }
                }
            } else if (state == OnDeviceRecognitionModelState.UNSUPPORTED) {
                TextButton(
                    onClick = onOpenFileSettings,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(text = stringResource(R.string.player_open_file_settings_action))
                }
            }
        }
    }
}

/**
 * 学習言語（target）と母語（base）に同じ言語が設定されているときの警告バナー。
 */
@Composable
internal fun SameTargetAndBaseLanguageBanner(
    language: LanguageTag,
    onOpenFileSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.player_same_target_base_language_warning,
                    language.value,
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            TextButton(
                onClick = onOpenFileSettings,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(text = stringResource(R.string.player_open_file_settings_action))
            }
        }
    }
}

/**
 * 全チャンクの文字起こしが完了したにもかかわらず認識テキストが 0 件だったときの案内バナー。
 */
@Composable
internal fun AllTranscriptionEmptyWarningBanner(
    languagePair: LanguagePair?,
    onOpenFileSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pairLabel = buildString {
        append(languagePair?.target?.value ?: "und")
        languagePair?.base?.takeIf { !matchesLanguageRole(it, languagePair.target) }?.let { base ->
            append(" / ")
            append(base.value)
        }
    }
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
        ) {
            Text(
                text = stringResource(
                    R.string.player_all_transcription_empty_warning,
                    pairLabel,
                ),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
            TextButton(
                onClick = onOpenFileSettings,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(text = stringResource(R.string.player_open_file_settings_action))
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
    onDeleteChunk: () -> Unit = {},
    onUserInteract: () -> Unit = {},
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val langPalette = chunkFlowLanguagePalette()
    val isTargetLang = matchesLanguageRole(chunk.language, languagePair?.target)
    val isBaseLang = matchesLanguageRole(chunk.language, languagePair?.base)

    val rowBackground by androidx.compose.animation.animateColorAsState(
        targetValue = when {
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
        },
        label = "chunkRowBackground",
    )
    val indicatorColor by androidx.compose.animation.animateColorAsState(
        targetValue = when {
            isForcedInFlight -> colorScheme.tertiary
            isActive -> colorScheme.primary
            isInLoopRange -> colorScheme.tertiary
            else -> Color.Transparent
        },
        label = "chunkIndicatorColor",
    )

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
                    onLongClick = {
                        onUserInteract()
                        menuExpanded = true
                    },
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

            // タイムスタンプ（ブラインド中もタップで頭出し可能。normal ⇔ bold で幅が変わって右の言語バッジが動かないよう Bold 幅で領域を固定）
            val timestampText = formatDurationMs(chunk.startMs)
            val timestampStyle = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")
            Box(
                modifier = Modifier
                    .clickable(onClick = onTimestampClick)
                    .padding(vertical = 10.dp, horizontal = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = timestampText,
                    style = timestampStyle,
                    fontWeight = FontWeight.Bold,
                    color = Color.Transparent,
                    modifier = Modifier.clearAndSetSemantics {},
                )
                Text(
                    text = timestampText,
                    style = timestampStyle,
                    color = if (isActive) colorScheme.primary else colorScheme.onSurfaceVariant,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                )
            }

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
                                Box {
                                    Text(
                                        text = phoneticReading,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.Transparent,
                                        modifier = Modifier.clearAndSetSemantics {},
                                    )
                                    Text(
                                        text = phoneticReading,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                        color = primaryTextColor,
                                    )
                                }
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
                            shape = MaterialTheme.shapes.small,
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
                        Box {
                            Text(
                                text = displayText,
                                style = localizedTextStyle,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Transparent,
                                modifier = Modifier.clearAndSetSemantics {},
                            )
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
                            val chunkNumText = stringResource(R.string.player_chunk_number_format, index + 1)
                            Box {
                                Text(
                                    text = chunkNumText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.Transparent,
                                    modifier = Modifier.clearAndSetSemantics {},
                                )
                                Text(
                                    text = chunkNumText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                    color = primaryTextColor,
                                )
                            }
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
                        ChunkFlowWavyLinearProgress(
                            progress = { stepProgress },
                            modifier = Modifier.fillMaxWidth(),
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
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.player_chunk_delete)) },
                onClick = {
                    menuExpanded = false
                    onDeleteChunk()
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
    val normalizedValue = language?.value?.trim()?.takeUnless {
        it.isEmpty() || it.equals("und", ignoreCase = true)
    }
    val role = when {
        matchesLanguageRole(language, languagePair?.target) -> LanguageBadgeRole.TARGET
        matchesLanguageRole(language, languagePair?.base) -> LanguageBadgeRole.BASE
        normalizedValue != null -> LanguageBadgeRole.OTHER
        else -> LanguageBadgeRole.UNKNOWN
    }
    val badgeText = normalizedValue?.lowercase(JavaLocale.ROOT)?.ifBlank { "-" } ?: "-"

    LanguageRoleBadge(
        code = badgeText,
        role = role,
        modifier = modifier,
    )
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
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
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
                .clip(MaterialTheme.shapes.extraSmall)
                .background(if (checked) colorScheme.tertiary else colorScheme.surface)
                .border(1.dp, boxBorderColor, MaterialTheme.shapes.extraSmall),
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
                onSeekToChunk = {},
                onSetLoopMode = {},
                onToggleRevealChunk = {},
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
            )
        }
    }
}

/** 自動追従スクロール時に再生中のチャンクの上に表示する直前チャンクの数（再生中のチャンクを上から 3 番目に配置する）。 */
internal const val AUTO_FOLLOW_LEADING_CONTEXT_ITEMS: Int = 2

/**
 * 再生中のチャンクの表示インデックス [activeDisplayIndex] に対し、
 * 自動追従スクロールで先頭に合わせるべきアイテムのインデックスを返す。
 */
internal fun resolveAutoFollowScrollTargetIndex(activeDisplayIndex: Int): Int = (activeDisplayIndex - AUTO_FOLLOW_LEADING_CONTEXT_ITEMS).coerceAtLeast(0)

/**
 * 再生位置 [positionMs] に対応するチャンクのインデックスを二分探索で返す。
 * チャンク間の無音区間にある場合は、直前に開始したチャンクのインデックスを返す。
 */
internal fun resolveChunkIndexAtPosition(chunks: List<ChunkUi>, positionMs: Long): Int? {
    if (chunks.isEmpty()) return null
    var low = 0
    var high = chunks.lastIndex
    var bestIndex: Int? = null
    while (low <= high) {
        val mid = (low + high) ushr 1
        val chunk = chunks[mid]
        when {
            positionMs < chunk.startMs -> high = mid - 1

            positionMs >= chunk.endMs -> {
                bestIndex = mid
                low = mid + 1
            }

            else -> return mid
        }
    }
    return bestIndex ?: 0
}
