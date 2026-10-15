package com.miuraken.chunkflow.feature.player

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
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
import com.miuraken.chunkflow.core.model.WordTiming
import com.miuraken.chunkflow.core.playback.PositionProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import java.util.Locale as JavaLocale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 手動スクロール後に自動追従を再開するまでの待機時間（5 秒、§7.5.5）。 */
private const val AUTO_FOLLOW_RESUME_DELAY_MS: Long = 5_000L

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
    onPickTranscriptJson: () -> Unit,
    onDismissTranscriptBanner: () -> Unit,
    modifier: Modifier = Modifier,
    showTargetRomanization: Boolean = false,
    positionProvider: PositionProvider = PositionProvider { 0L },
    lookaheadStatus: LookaheadTranscriptionStatus = LookaheadTranscriptionStatus(),
    localTranscriptExported: Boolean = false,
    transcriptExportBanner: TranscriptExportBannerState? = null,
    onDismissExportBanner: () -> Unit = {},
    onToggleBookmark: (ChunkId) -> Unit = {},
    onToggleRepeatChunk: (ChunkId) -> Unit = {},
    onUpdateChunkLanguage: (ChunkId, LanguageTag?) -> Unit = { _, _ -> },
    onUpdateChunkText: (ChunkId, String) -> Unit = { _, _ -> },
    onRetranscribeChunk: (ChunkId) -> Unit = {},
    onSplitChunk: (ChunkId, Long) -> Unit = { _, _ -> },
    onMergeWithNextChunk: (ChunkId) -> Unit = {},
    onMoveChunkBoundary: (ChunkId, Long, Long) -> Unit = { _, _, _ -> },
    onBulkSetUnknownLanguage: (LanguageTag) -> Unit = {},
    onSetLookaheadEnabled: (Boolean) -> Unit = {},
    onExportLocalTranscriptJson: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isInspectionMode = LocalInspectionMode.current

    var isProgrammaticScroll by remember { mutableStateOf(false) }
    var isAutoFollowPaused by remember { mutableStateOf(false) }
    var manualScrollTick by remember { mutableLongStateOf(0L) }
    var rangeSelectionStartChunkId by remember { mutableStateOf<ChunkId?>(null) }
    var editingTextChunk by remember { mutableStateOf<Pair<Int, ChunkUi>?>(null) }

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
                isProgrammaticScroll = true
                try {
                    listState.animateScrollToItem(targetIdx)
                } finally {
                    isProgrammaticScroll = false
                }
            }
        }
    }

    val hasAnyTranscript = remember(chunks) { chunks.any { it.text != null } }
    val hasUnknownLanguageChunks = remember(chunks) {
        chunks.any { chunk ->
            chunk.text != null &&
                (chunk.language == null || chunk.language.value.isBlank() || chunk.language.value == "und")
        }
    }
    val loopHighlightedIds = remember(loopMode, chunks) {
        resolveLoopChunkIds(loopMode, chunks)
    }

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

            if (lookaheadStatus.enabled || localTranscriptExported) {
                LookaheadAndExportBanner(
                    lookaheadStatus = lookaheadStatus,
                    localTranscriptExported = localTranscriptExported,
                    totalChunkCount = chunks.size,
                    onToggleLookahead = { onSetLookaheadEnabled(!lookaheadStatus.enabled) },
                    onExportLocalTranscriptJson = onExportLocalTranscriptJson,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
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

            if (!hasAnyTranscript) {
                NoTranscriptBannerCard(
                    onPickTranscriptJson = onPickTranscriptJson,
                    lookaheadEnabled = lookaheadStatus.enabled,
                    onStartLookahead = { onSetLookaheadEnabled(true) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
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

                    TranscriptChunkRow(
                        index = index,
                        chunk = chunk,
                        hasNextChunk = index < chunks.lastIndex,
                        isActive = isActive,
                        isInLoopRange = isInLoopRange,
                        isMasked = isMasked,
                        isLookaheadInFlight = chunk.id == lookaheadStatus.inFlightChunkId,
                        languagePair = languagePair,
                        chineseScript = chineseScript,
                        cantoneseScript = cantoneseScript,
                        showTargetRomanization = showTargetRomanization,
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
                        onRepeatSingleInfinite = {
                            onSetLoopMode(LoopMode.SingleChunk(chunk.id, times = null))
                            onSeekToChunk(chunk.id)
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
                        onSplitChunk = {
                            val nowMs = positionProvider.currentPositionMs()
                            val splitMs = if (nowMs > chunk.startMs + 50L && nowMs < chunk.endMs - 50L) {
                                nowMs
                            } else {
                                (chunk.startMs + chunk.endMs) / 2L
                            }
                            if (splitMs > chunk.startMs && splitMs < chunk.endMs) {
                                onSplitChunk(chunk.id, splitMs)
                            }
                        },
                        onMergeWithNextChunk = {
                            onMergeWithNextChunk(chunk.id)
                        },
                        onMoveChunkBoundary = { newStartMs, newEndMs ->
                            onMoveChunkBoundary(chunk.id, newStartMs, newEndMs)
                        },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }

        if (isAutoFollowPaused && activeChunkIndex != null && activeChunkIndex in chunks.indices) {
            AssistChip(
                onClick = {
                    isAutoFollowPaused = false
                    coroutineScope.launch {
                        isProgrammaticScroll = true
                        try {
                            listState.animateScrollToItem(activeChunkIndex)
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
                    .padding(12.dp),
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

@Composable
private fun LookaheadAndExportBanner(
    lookaheadStatus: LookaheadTranscriptionStatus,
    localTranscriptExported: Boolean,
    totalChunkCount: Int,
    onToggleLookahead: () -> Unit,
    onExportLocalTranscriptJson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val totalCount = if (lookaheadStatus.totalTargetCount > 0) {
        lookaheadStatus.totalTargetCount
    } else {
        totalChunkCount
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lookaheadStatus.enabled) {
            FilterChip(
                selected = true,
                onClick = onToggleLookahead,
                label = {
                    Text(
                        text = stringResource(
                            R.string.player_lookahead_chip_on,
                            lookaheadStatus.completedCount,
                            totalCount,
                        ),
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = ChunkFlowIcons.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
        }
        if (localTranscriptExported) {
            AssistChip(
                onClick = onExportLocalTranscriptJson,
                label = { Text(text = stringResource(R.string.player_lookahead_exported_badge)) },
                leadingIcon = {
                    Icon(
                        imageVector = ChunkFlowIcons.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                },
            )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TranscriptChunkRow(
    index: Int,
    chunk: ChunkUi,
    hasNextChunk: Boolean,
    isActive: Boolean,
    isInLoopRange: Boolean,
    isMasked: Boolean,
    isLookaheadInFlight: Boolean,
    languagePair: LanguagePair?,
    chineseScript: ChineseScript,
    cantoneseScript: ChineseScript,
    showTargetRomanization: Boolean,
    positionProvider: PositionProvider,
    onRowClick: () -> Unit,
    onTimestampClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    onToggleRepeatChunk: () -> Unit,
    onRepeatSingleInfinite: () -> Unit,
    onStartRangeSelection: () -> Unit,
    onUpdateLanguage: (LanguageTag?) -> Unit,
    onRequestEditText: () -> Unit,
    onRetranscribeChunk: () -> Unit = {},
    onSplitChunk: () -> Unit,
    onMergeWithNextChunk: () -> Unit,
    onMoveChunkBoundary: (Long, Long) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val langPalette = chunkFlowLanguagePalette()
    val isTargetLang = matchesLanguageRole(chunk.language, languagePair?.target)
    val isBaseLang = matchesLanguageRole(chunk.language, languagePair?.base)

    val rowBackground = when {
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

            Spacer(modifier = Modifier.width(8.dp))

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
            if (isLookaheadInFlight) {
                Spacer(modifier = Modifier.width(4.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))

            if (!displayText.isNullOrBlank()) {
                if (isMasked) {
                    Surface(
                        color = colorScheme.surfaceContainerHighest,
                        contentColor = colorScheme.onSurfaceVariant,
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
                } else {
                    val currentActiveWord = activeWordRange
                    val shouldShowPhonetic = showTargetRomanization &&
                        (isTargetLang || languagePair == null)
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
                                color = colorScheme.onSurface,
                            )
                        } else {
                            Text(
                                text = displayText,
                                style = localizedTextStyle,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                color = colorScheme.onSurface,
                            )
                        }
                        if (phoneticReading != null) {
                            Text(
                                text = phoneticReading,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                // 文字起こしがないチャンク: チャンク番号と時刻範囲を表示（§7.5.5）
                Text(
                    text = stringResource(R.string.player_chunk_number_format, index + 1),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    color = colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${formatDurationMs(chunk.startMs)} - ${formatDurationMs(chunk.endMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                )
            }

            RepeatChunkCheckbox(
                checked = chunk.isRepeatChecked,
                contentDescription = repeatCheckCd,
                onToggle = onToggleRepeatChunk,
            )

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
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.player_chunk_retranscribe)) },
                onClick = {
                    menuExpanded = false
                    onRetranscribeChunk()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.player_chunk_repeat_infinite)) },
                onClick = {
                    menuExpanded = false
                    onRepeatSingleInfinite()
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.player_chunk_range_select_start)) },
                onClick = {
                    menuExpanded = false
                    onStartRangeSelection()
                },
            )
            HorizontalDivider()
            if (chunk.endMs - chunk.startMs >= 200L) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_chunk_split_here)) },
                    onClick = {
                        menuExpanded = false
                        onSplitChunk()
                    },
                )
            }
            if (hasNextChunk) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_chunk_merge_next)) },
                    onClick = {
                        menuExpanded = false
                        onMergeWithNextChunk()
                    },
                )
            }
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
            if (languagePair != null) {
                HorizontalDivider()
                val targetCode = languagePair.target.value.uppercase(JavaLocale.ROOT)
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(
                                R.string.player_chunk_set_lang_target,
                                targetCode,
                            ),
                        )
                    },
                    onClick = {
                        menuExpanded = false
                        onUpdateLanguage(languagePair.target)
                    },
                )
                languagePair.base?.let { baseLang ->
                    val baseCode = baseLang.value.uppercase(JavaLocale.ROOT)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    R.string.player_chunk_set_lang_base,
                                    baseCode,
                                ),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onUpdateLanguage(baseLang)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.player_chunk_set_lang_unknown)) },
                    onClick = {
                        menuExpanded = false
                        onUpdateLanguage(null)
                    },
                )
            }
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
    val targetCode = languagePair.target.value.uppercase(JavaLocale.ROOT)
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
            val baseCode = baseLang.value.uppercase(JavaLocale.ROOT)
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
    val badgeText = normalizedValue?.uppercase(JavaLocale.ROOT)?.ifBlank { "-" } ?: "-"

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
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
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
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
            Icon(
                imageVector = ChunkFlowIcons.Repeat,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoTranscriptBannerCard(
    onPickTranscriptJson: () -> Unit,
    modifier: Modifier = Modifier,
    lookaheadEnabled: Boolean = false,
    onStartLookahead: () -> Unit = {},
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.player_no_transcript_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!lookaheadEnabled) {
                    FilledTonalButton(
                        onClick = onStartLookahead,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.player_start_lookahead_stt),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                FilledTonalButton(
                    onClick = onPickTranscriptJson,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Description,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.player_pick_transcript_json),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
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
