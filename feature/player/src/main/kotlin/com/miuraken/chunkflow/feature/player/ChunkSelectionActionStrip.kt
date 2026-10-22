package com.miuraken.chunkflow.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.playback.PositionProvider
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 選択範囲に関する操作の行（1 ペイン・2 ペインとも、波形（拡大波形）のすぐ下に置く）。
 *
 * レイアウトが跳ねないよう 3 つのボタンを常に並べ、使えないときは無効（押せない見た目）にする。
 * 1 行に収まらないとき（文字サイズが大きいときや、2 ペインの左側が狭いとき）は、横にスクロールさせずに次の行へ折り返す。
 * 「再生位置で分割」が途中で切れて「再生位置」とだけ読めると、押したときの動作がわからなくなるため。
 * - 「全選択解除」: チャンクを 1 つ以上選択しているとき
 * - 「範囲変更」: チャンクを 1 つだけ選択しているとき（タップで拡大波形の境界ハンドル表示切替、長押しで ±100ms 調整メニュー）
 * - 「連結」: チャンクを 2 つ以上選択しているとき（[canMergeSelectedChunks]）。押すと確認ダイアログを表示する
 * - 「再生位置で分割」: 再生位置（拡大波形の中央のカーソル）がいずれかのチャンクの分割可能な範囲内（両端から [MIN_SPLIT_PIECE_MS] 以上内側）にあるとき（[canSplitChunkAtPosition]）
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChunkSelectionActionStrip(
    state: PlayerUiState,
    positionProvider: PositionProvider,
    onEvent: (PlayerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedChunkCount = state.chunks.count { it.isRepeatChecked }
    val hasSelection = selectedChunkCount > 0
    val isSingleChunkSelected = selectedChunkCount == 1
    val mergeSummary = remember(state.chunks) { summarizeMergeSelection(state.chunks) }
    val canMergeSelection = mergeSummary != null
    val abRange = (state.practice.loop as? LoopMode.AbRange)?.takeIf { it.startMs < it.endMs }
    val overlappingRangeChunks = remember(state.chunks, abRange) {
        if (abRange != null) {
            chunksOverlappingRange(state.chunks, abRange.startMs, abRange.endMs)
        } else {
            emptyList()
        }
    }
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
    var showRangeMergeConfirmDialog by remember { mutableStateOf(false) }
    var showRangeDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showEditRangeMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (abRange != null) {
            AssistChip(
                onClick = { onEvent(PlayerEvent.ClearWaveformRangeToCenter) },
                enabled = true,
                label = { Text(text = stringResource(R.string.player_selection_clear_range), maxLines = 1) },
            )
            when {
                overlappingRangeChunks.isEmpty() -> {
                    AssistChip(
                        onClick = { onEvent(PlayerEvent.AddChunkInSelectedRange) },
                        enabled = true,
                        label = { Text(text = stringResource(R.string.player_selection_add_chunk), maxLines = 1) },
                    )
                }

                overlappingRangeChunks.size >= 2 -> {
                    AssistChip(
                        onClick = { showRangeMergeConfirmDialog = true },
                        enabled = true,
                        label = { Text(text = stringResource(R.string.player_selection_merge), maxLines = 1) },
                    )
                    AssistChip(
                        onClick = { showRangeDeleteConfirmDialog = true },
                        enabled = true,
                        label = { Text(text = stringResource(R.string.player_selection_delete_chunks), maxLines = 1) },
                    )
                }

                else -> {
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
            }
        } else {
            AssistChip(
                onClick = { onEvent(PlayerEvent.ClearChunkSelection) },
                enabled = hasSelection,
                label = { Text(text = stringResource(R.string.player_selection_clear_all), maxLines = 1) },
            )
            if (isSingleChunkSelected) {
                val isEditingRange = state.isChunkBoundaryEditMode
                val chipShape = AssistChipDefaults.shape
                val containerColor = if (isEditingRange) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                }
                val contentColor = if (isEditingRange) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
                val borderStroke = if (isEditingRange) {
                    BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary)
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                }
                Box(modifier = Modifier.padding(vertical = 8.dp)) {
                    Surface(
                        shape = chipShape,
                        color = containerColor,
                        contentColor = contentColor,
                        border = borderStroke,
                        modifier = Modifier
                            .defaultMinSize(minHeight = 32.dp)
                            .clip(chipShape)
                            .semantics {
                                role = Role.Button
                                selected = isEditingRange
                            }
                            .combinedClickable(
                                onClick = { onEvent(PlayerEvent.ToggleChunkBoundaryEditMode) },
                                onLongClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showEditRangeMenu = true
                                },
                            ),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.player_selection_edit_range),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showEditRangeMenu,
                        onDismissRequest = { showEditRangeMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.player_edit_range_start_minus)) },
                            onClick = {
                                onEvent(PlayerEvent.NudgeSelectedChunkBoundary(isStart = true, deltaMs = -100L))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.player_edit_range_start_plus)) },
                            onClick = {
                                onEvent(PlayerEvent.NudgeSelectedChunkBoundary(isStart = true, deltaMs = 100L))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.player_edit_range_end_minus)) },
                            onClick = {
                                onEvent(PlayerEvent.NudgeSelectedChunkBoundary(isStart = false, deltaMs = -100L))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.player_edit_range_end_plus)) },
                            onClick = {
                                onEvent(PlayerEvent.NudgeSelectedChunkBoundary(isStart = false, deltaMs = 100L))
                            },
                        )
                    }
                }
            } else {
                AssistChip(
                    onClick = { showMergeConfirmDialog = true },
                    enabled = canMergeSelection,
                    label = { Text(text = stringResource(R.string.player_selection_merge), maxLines = 1) },
                )
            }
            AssistChip(
                onClick = { onEvent(PlayerEvent.SplitChunkAtCurrentPosition) },
                enabled = canSplitAtCursor,
                label = { Text(text = stringResource(R.string.player_split_at_cursor), maxLines = 1) },
            )
        }
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

    if (showRangeMergeConfirmDialog && overlappingRangeChunks.size >= 2) {
        val firstChunk = overlappingRangeChunks.first()
        val lastChunk = overlappingRangeChunks.last()
        val firstChunkNumber = state.chunks.indexOfFirst { it.id == firstChunk.id }.coerceAtLeast(0) + 1
        val lastChunkNumber = state.chunks.indexOfFirst { it.id == lastChunk.id }.coerceAtLeast(0) + 1
        val rawLangCode = firstChunk.language?.value?.trim()?.takeUnless {
            it.isEmpty() || it.equals("und", ignoreCase = true)
        }?.lowercase(Locale.ROOT)
        val langLabel = rawLangCode ?: stringResource(R.string.player_merge_lang_unknown)
        val confirmMessage = stringResource(
            R.string.player_merge_confirm_message_contiguous,
            firstChunkNumber,
            lastChunkNumber,
            overlappingRangeChunks.size,
            langLabel,
        )

        AlertDialog(
            onDismissRequest = { showRangeMergeConfirmDialog = false },
            title = {
                Text(text = stringResource(R.string.player_range_merge_confirm_title))
            },
            text = {
                Text(text = confirmMessage)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRangeMergeConfirmDialog = false
                        onEvent(PlayerEvent.MergeChunksInSelectedRange)
                    },
                ) {
                    Text(text = stringResource(R.string.player_merge_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRangeMergeConfirmDialog = false }) {
                    Text(text = stringResource(R.string.player_chunk_edit_cancel))
                }
            },
        )
    }

    if (showRangeDeleteConfirmDialog && overlappingRangeChunks.isNotEmpty()) {
        val firstChunk = overlappingRangeChunks.first()
        val lastChunk = overlappingRangeChunks.last()
        val firstChunkNumber = state.chunks.indexOfFirst { it.id == firstChunk.id }.coerceAtLeast(0) + 1
        val lastChunkNumber = state.chunks.indexOfFirst { it.id == lastChunk.id }.coerceAtLeast(0) + 1
        val confirmMessage = stringResource(
            R.string.player_range_delete_confirm_message,
            firstChunkNumber,
            lastChunkNumber,
            overlappingRangeChunks.size,
        )

        AlertDialog(
            onDismissRequest = { showRangeDeleteConfirmDialog = false },
            title = {
                Text(text = stringResource(R.string.player_range_delete_confirm_title))
            },
            text = {
                Text(text = confirmMessage)
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRangeDeleteConfirmDialog = false
                        onEvent(PlayerEvent.DeleteChunksInSelectedRange)
                    },
                ) {
                    Text(text = stringResource(R.string.player_range_delete_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRangeDeleteConfirmDialog = false }) {
                    Text(text = stringResource(R.string.player_chunk_edit_cancel))
                }
            },
        )
    }
}
