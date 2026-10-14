package com.miuraken.chunkflow.feature.player

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.formatDurationMs
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import java.util.Locale as JavaLocale
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 手動スクロール後に自動追従を再開するまでの待機時間（5 秒、§7.5.5）。 */
private const val AUTO_FOLLOW_RESUME_DELAY_MS: Long = 5_000L

/**
 * 再生位置に同期したチャンク / スクリプト一覧（F7.1、F7.2、F6.1、§7.5.5）。
 *
 * - `LazyColumn` の `key` にチャンク ID を指定し、現在のチャンク（[activeChunkIndex]）を背景色と左端バーで強調する。
 * - 現在のチャンクが変わったら自動スクロールで追従し、手動スクロール後 5 秒間は追従を止めて「現在位置へ戻る」ボタンを表示する。
 * - 各行に言語タグと言語設定（[chineseScript] / [cantoneseScript]）から作った `LocaleList` を適用する。
 * - ブラインドモード（[blindMode]）では対象行をマスクし、タップで一時表示できる。
 * - 長押しメニューから単一チャンクリピート（3 回 / 無限）および複数チャンク範囲リピートを設定できる。
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
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val isInspectionMode = LocalInspectionMode.current

    var isProgrammaticScroll by remember { mutableStateOf(false) }
    var isAutoFollowPaused by remember { mutableStateOf(false) }
    var manualScrollTick by remember { mutableLongStateOf(0L) }
    var rangeSelectionStartChunkId by remember { mutableStateOf<ChunkId?>(null) }

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

            if (rangeSelectionStartChunkId != null) {
                RangeSelectionBanner(
                    onCancel = { rangeSelectionStartChunkId = null },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (!hasAnyTranscript) {
                NoTranscriptBannerCard(
                    onPickTranscriptJson = onPickTranscriptJson,
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
                        isActive = isActive,
                        isInLoopRange = isInLoopRange,
                        isMasked = isMasked,
                        languagePair = languagePair,
                        chineseScript = chineseScript,
                        cantoneseScript = cantoneseScript,
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
                        onRepeatSingle3 = {
                            onSeekToChunk(chunk.id)
                            onSetLoopMode(LoopMode.SingleChunk(chunk.id, times = 3))
                        },
                        onRepeatSingleInfinite = {
                            onSeekToChunk(chunk.id)
                            onSetLoopMode(LoopMode.SingleChunk(chunk.id, times = null))
                        },
                        onStartRangeSelection = {
                            rangeSelectionStartChunkId = chunk.id
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
                chunk.language == languagePair.target
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
    isActive: Boolean,
    isInLoopRange: Boolean,
    isMasked: Boolean,
    languagePair: LanguagePair?,
    chineseScript: ChineseScript,
    cantoneseScript: ChineseScript,
    onRowClick: () -> Unit,
    onTimestampClick: () -> Unit,
    onRepeatSingle3: () -> Unit,
    onRepeatSingleInfinite: () -> Unit,
    onStartRangeSelection: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val colorScheme = MaterialTheme.colorScheme
    val rowBackground = when {
        isActive -> colorScheme.primaryContainer.copy(alpha = 0.36f)
        isInLoopRange -> colorScheme.tertiaryContainer.copy(alpha = 0.28f)
        else -> colorScheme.surface
    }
    val indicatorColor = when {
        isActive -> colorScheme.primary
        isInLoopRange -> colorScheme.tertiary
        else -> colorScheme.surface
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
                .padding(end = 12.dp),
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

            if (displayText != null) {
                ChunkLanguageBadge(
                    language = chunk.language,
                    languagePair = languagePair,
                )
                Spacer(modifier = Modifier.width(8.dp))
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
                    Text(
                        text = displayText,
                        style = localizedTextStyle,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                        color = colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                    )
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
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(text = stringResource(R.string.player_chunk_repeat_3)) },
                onClick = {
                    menuExpanded = false
                    onRepeatSingle3()
                },
            )
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
        }
    }
}

/**
 * 言語コードバッジ（§7.5.5: 色とテキストの両方で役割を示し、未判定は `?`）。
 */
@Composable
internal fun ChunkLanguageBadge(
    language: LanguageTag?,
    languagePair: LanguagePair?,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isTarget = language != null && languagePair != null && language == languagePair.target
    val isBase = language != null && languagePair?.base != null && language == languagePair.base

    val containerColor = when {
        isTarget -> colorScheme.primaryContainer
        isBase -> colorScheme.tertiaryContainer
        language != null -> colorScheme.secondaryContainer
        else -> colorScheme.surfaceVariant
    }
    val contentColor = when {
        isTarget -> colorScheme.onPrimaryContainer
        isBase -> colorScheme.onTertiaryContainer
        language != null -> colorScheme.onSecondaryContainer
        else -> colorScheme.onSurfaceVariant
    }
    val badgeText = language?.value?.uppercase(JavaLocale.ROOT)?.ifBlank { "?" } ?: "?"

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
private fun NoTranscriptBannerCard(
    onPickTranscriptJson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.player_no_transcript_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
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
