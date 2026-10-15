package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LanguageTag
import kotlin.math.max
import kotlin.math.min

/** チャンクを分割するとき、分けたあとの各チャンクに最低限残す長さ（ミリ秒）。 */
internal const val MIN_SPLIT_PIECE_MS: Long = 200L

/**
 * 選択中（[ChunkUi.isRepeatChecked]）のチャンクを開始時刻の順に返す。
 */
internal fun selectedChunksInOrder(chunks: List<ChunkUi>): List<ChunkUi> = chunks.filter { it.isRepeatChecked }.sortedBy { it.startMs }

/**
 * 選択中のチャンクを 1 つに連結できるかどうかを判定する。
 *
 * 選択が 2 件以上で、チャンクリスト上で途切れずに連続しており、先頭と末尾のチャンクの言語が同じ
 * （どちらも未判定の場合を含む）ときに `true` を返す。
 */
internal fun canMergeSelectedChunks(chunks: List<ChunkUi>): Boolean {
    val selectedIndices = chunks.indices.filter { chunks[it].isRepeatChecked }
    if (selectedIndices.size < 2) return false
    val firstIndex = selectedIndices.first()
    val lastIndex = selectedIndices.last()
    if (lastIndex - firstIndex + 1 != selectedIndices.size) return false
    return isSameChunkLanguage(chunks[firstIndex].language, chunks[lastIndex].language)
}

/**
 * 連結するチャンクの ID を開始時刻の順に返す。連結できない場合は空のリストを返す。
 */
internal fun mergeableSelectedChunkIds(chunks: List<ChunkUi>): List<ChunkId> = if (canMergeSelectedChunks(chunks)) {
    selectedChunksInOrder(chunks).map { it.id }
} else {
    emptyList()
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

private fun isSameChunkLanguage(first: LanguageTag?, second: LanguageTag?): Boolean {
    val firstKnown = first?.value?.trim()?.takeUnless { it.isEmpty() || it.equals("und", ignoreCase = true) }
    val secondKnown = second?.value?.trim()?.takeUnless { it.isEmpty() || it.equals("und", ignoreCase = true) }
    if (firstKnown == null && secondKnown == null) return true
    return matchesLanguageRole(first, second)
}
