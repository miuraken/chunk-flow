package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LanguageTag
import kotlin.math.max
import kotlin.math.min

/** チャンクを分割するとき、分けたあとの各チャンクに最低限残す長さ（ミリ秒）。 */
internal const val MIN_SPLIT_PIECE_MS: Long = 200L

/**
 * 選択したチャンクを連結するときの範囲と内容の要約（確認ダイアログ表示用）。
 *
 * @property firstChunkNumber 連結範囲の先頭チャンク番号（1 始まり）。
 * @property lastChunkNumber 連結範囲の末尾チャンク番号（1 始まり）。
 * @property selectedCount ユーザーが選択したチャンクの件数。
 * @property totalSpanCount 先頭から末尾までに含まれる全チャンク件数（途中の未選択チャンクを含む）。
 * @property unselectedGapCount 途中に挟まれている未選択チャンクの件数（`totalSpanCount - selectedCount`）。
 * @property targetLanguage 連結後のチャンクに設定される言語（先頭チャンクの言語）。
 */
internal data class MergeSelectionSummary(
    val firstChunkNumber: Int,
    val lastChunkNumber: Int,
    val selectedCount: Int,
    val totalSpanCount: Int,
    val unselectedGapCount: Int,
    val targetLanguage: LanguageTag?,
)

/**
 * 選択中（[ChunkUi.isRepeatChecked]）のチャンクを開始時刻の順に返す。
 */
internal fun selectedChunksInOrder(chunks: List<ChunkUi>): List<ChunkUi> = chunks.filter { it.isRepeatChecked }.sortedBy { it.startMs }

/**
 * 選択中のチャンクを 1 つに連結できるかどうかを判定する。
 *
 * 選択が 2 件以上であれば、連続していなくても、また異なる言語が混じっていても `true` を返す。
 */
internal fun canMergeSelectedChunks(chunks: List<ChunkUi>): Boolean = chunks.count { it.isRepeatChecked } >= 2

/**
 * 連結するチャンクの ID を開始時刻の順に返す。連結できない場合は空のリストを返す。
 */
internal fun mergeableSelectedChunkIds(chunks: List<ChunkUi>): List<ChunkId> = if (canMergeSelectedChunks(chunks)) {
    selectedChunksInOrder(chunks).map { it.id }
} else {
    emptyList()
}

/**
 * 選択中のチャンクから連結確認ダイアログ用の [MergeSelectionSummary] を計算する。
 * 連結できない場合（選択が 2 件未満）は `null` を返す。
 */
internal fun summarizeMergeSelection(chunks: List<ChunkUi>): MergeSelectionSummary? {
    val selectedIndices = chunks.indices.filter { chunks[it].isRepeatChecked }
    if (selectedIndices.size < 2) return null
    val firstIndex = selectedIndices.first()
    val lastIndex = selectedIndices.last()
    val totalSpanCount = lastIndex - firstIndex + 1
    val selectedCount = selectedIndices.size
    return MergeSelectionSummary(
        firstChunkNumber = firstIndex + 1,
        lastChunkNumber = lastIndex + 1,
        selectedCount = selectedCount,
        totalSpanCount = totalSpanCount,
        unselectedGapCount = (totalSpanCount - selectedCount).coerceAtLeast(0),
        targetLanguage = chunks[firstIndex].language,
    )
}

/**
 * 再生位置 [positionMs] でチャンクを 2 つに分ける場合の対象チャンクを返す。
 *
 * 位置を含むチャンクがない場合や、分けたあとのどちらかが [MIN_SPLIT_PIECE_MS] より短くなる場合は `null` を返す。
 */
internal fun findSplitTargetChunk(chunks: List<ChunkUi>, positionMs: Long): ChunkUi? {
    val target = chunks.firstOrNull { positionMs > it.startMs && positionMs < it.endMs } ?: return null
    if (positionMs - target.startMs < MIN_SPLIT_PIECE_MS || target.endMs - positionMs < MIN_SPLIT_PIECE_MS) {
        return null
    }
    return target
}

/**
 * 再生位置 [positionMs] でチャンクを分割できる状態かどうかを判定する。
 *
 * チャンクが 1 件以上あり、選択中のチャンクがなく、かつ [findSplitTargetChunk] が対象チャンクを返すときのみ `true` を返す。
 */
internal fun canSplitChunkAtPosition(
    chunks: List<ChunkUi>,
    positionMs: Long,
): Boolean {
    if (chunks.isEmpty() || chunks.any { it.isRepeatChecked }) return false
    return findSplitTargetChunk(chunks, positionMs) != null
}

/**
 * 時刻 [positionMs] を含むチャンク（`startMs <= positionMs < endMs`）を返す。含むチャンクがなければ `null`。
 *
 * 隣り合うチャンクの境界の時刻では、後ろのチャンクを返す。
 */
internal fun findChunkAt(chunks: List<ChunkUi>, positionMs: Long): ChunkUi? = chunks.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }

/**
 * 時刻 [fromMs] から [toMs] まで（どちらが先でもよい）の範囲に重なるチャンクを、リストの順に返す。
 *
 * 拡大波形を長押ししたまま指でなぞったとき、速く動かして飛び越えた短いチャンクも選択するために使う。
 */
internal fun chunksInRange(chunks: List<ChunkUi>, fromMs: Long, toMs: Long): List<ChunkUi> {
    val startMs = min(fromMs, toMs)
    val endMs = max(fromMs, toMs)
    return chunks.filter { it.startMs <= endMs && it.endMs > startMs }
}
