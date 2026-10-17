package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LanguageTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChunkSelectionTest {
    private fun chunk(
        id: Long,
        startMs: Long,
        endMs: Long,
        language: String?,
        selected: Boolean = false,
    ) = ChunkUi(
        id = ChunkId(id),
        startMs = startMs,
        endMs = endMs,
        text = "chunk$id",
        language = language?.let(::LanguageTag),
        isRepeatChecked = selected,
    )

    @Test
    fun `canMergeSelectedChunks_2件以上選択されていれば非連続や異言語でも連結でき要約を返す`() {
        // 連続・異言語混在
        val contiguous = listOf(
            chunk(1, 0, 1_000, "ja"),
            chunk(2, 1_000, 2_000, "ko", selected = true),
            chunk(3, 2_000, 3_000, "ja", selected = true),
            chunk(4, 3_000, 4_000, "ko-KR", selected = true),
        )
        assertTrue(canMergeSelectedChunks(contiguous))
        assertEquals(listOf(ChunkId(2), ChunkId(3), ChunkId(4)), mergeableSelectedChunkIds(contiguous))
        val contiguousSummary = summarizeMergeSelection(contiguous)
        assertNotNull(contiguousSummary)
        assertEquals(2, contiguousSummary.firstChunkNumber)
        assertEquals(4, contiguousSummary.lastChunkNumber)
        assertEquals(3, contiguousSummary.selectedCount)
        assertEquals(3, contiguousSummary.totalSpanCount)
        assertEquals(0, contiguousSummary.unselectedGapCount)
        assertEquals(LanguageTag("ko"), contiguousSummary.targetLanguage)

        // 途中に選ばれていないチャンクがある場合（非連続）も連結可能で、unselectedGapCount が計算される
        val gap = listOf(
            chunk(1, 0, 1_000, "ja", selected = true),
            chunk(2, 1_000, 2_000, "ko"),
            chunk(3, 2_000, 3_000, "ko", selected = true),
        )
        assertTrue(canMergeSelectedChunks(gap))
        assertEquals(listOf(ChunkId(1), ChunkId(3)), mergeableSelectedChunkIds(gap))
        val gapSummary = summarizeMergeSelection(gap)
        assertNotNull(gapSummary)
        assertEquals(1, gapSummary.firstChunkNumber)
        assertEquals(3, gapSummary.lastChunkNumber)
        assertEquals(2, gapSummary.selectedCount)
        assertEquals(3, gapSummary.totalSpanCount)
        assertEquals(1, gapSummary.unselectedGapCount)
        assertEquals(LanguageTag("ja"), gapSummary.targetLanguage)

        // 両端の言語が違う場合も連結でき、先頭チャンクの言語が targetLanguage になる
        val differentEdges = listOf(
            chunk(1, 0, 1_000, "ja", selected = true),
            chunk(2, 1_000, 2_000, "ko", selected = true),
        )
        assertTrue(canMergeSelectedChunks(differentEdges))
        assertEquals(LanguageTag("ja"), summarizeMergeSelection(differentEdges)?.targetLanguage)

        // 1 件だけの選択では連結しない
        val singleSelected = listOf(chunk(1, 0, 1_000, "ko", selected = true))
        assertFalse(canMergeSelectedChunks(singleSelected))
        assertTrue(mergeableSelectedChunkIds(singleSelected).isEmpty())
        assertNull(summarizeMergeSelection(singleSelected))
    }

    @Test
    fun `findSplitTargetChunk_位置を含むチャンクを返し端から200ms未満やチャンクの外ではnull`() {
        val chunks = listOf(
            chunk(1, 1_000, 3_000, "ja"),
            chunk(2, 4_000, 6_000, "ko"),
        )
        assertEquals(ChunkId(1), findSplitTargetChunk(chunks, 2_000)?.id)
        assertEquals(ChunkId(2), findSplitTargetChunk(chunks, 4_000 + MIN_SPLIT_PIECE_MS)?.id)
        assertNull(findSplitTargetChunk(chunks, 4_000 + MIN_SPLIT_PIECE_MS - 1))
        assertNull(findSplitTargetChunk(chunks, 6_000 - MIN_SPLIT_PIECE_MS + 1))
        assertNull(findSplitTargetChunk(chunks, 3_500))
    }

    @Test
    fun `canSplitChunkAtPosition_再生位置が分割可能なチャンク内にあれば他のチャンクが選択状態でもtrue`() {
        val chunks = listOf(
            chunk(1, 1_000, 3_000, "ja"),
            chunk(2, 4_000, 6_000, "ko"),
        )
        // チャンク内の分割可能な位置では true
        assertTrue(canSplitChunkAtPosition(chunks, 2_000))
        assertTrue(canSplitChunkAtPosition(chunks, 1_000 + MIN_SPLIT_PIECE_MS))
        assertTrue(canSplitChunkAtPosition(chunks, 3_000 - MIN_SPLIT_PIECE_MS))

        // チャンク間のギャップ（無音区間）や先頭チャンクより前・末尾チャンクより後では false
        assertFalse(canSplitChunkAtPosition(chunks, 0))
        assertFalse(canSplitChunkAtPosition(chunks, 3_500))
        assertFalse(canSplitChunkAtPosition(chunks, 6_500))

        // チャンクの端（開始・終了位置そのもの、または端から MIN_SPLIT_PIECE_MS 未満）では false
        assertFalse(canSplitChunkAtPosition(chunks, 1_000))
        assertFalse(canSplitChunkAtPosition(chunks, 1_000 + MIN_SPLIT_PIECE_MS - 1))
        assertFalse(canSplitChunkAtPosition(chunks, 3_000 - MIN_SPLIT_PIECE_MS + 1))
        assertFalse(canSplitChunkAtPosition(chunks, 3_000))

        // 他のチャンクが選択中（isRepeatChecked = true）でも再生位置が分割可能なチャンク内にあれば true
        val chunksWithSelection = listOf(
            chunk(1, 1_000, 3_000, "ja"),
            chunk(2, 4_000, 6_000, "ko", selected = true),
        )
        assertTrue(canSplitChunkAtPosition(chunksWithSelection, 2_000))

        // チャンクが空のときは false
        assertFalse(canSplitChunkAtPosition(emptyList(), 2_000))
    }

    @Test
    fun `findChunkAt_境界では後ろのチャンクを返しすき間ではnull`() {
        val chunks = listOf(
            chunk(1, 1_000, 2_000, "ja"),
            chunk(2, 2_000, 3_000, "ko"),
            chunk(3, 4_000, 5_000, "ko"),
        )
        assertEquals(ChunkId(1), findChunkAt(chunks, 1_000)?.id)
        assertEquals(ChunkId(2), findChunkAt(chunks, 2_000)?.id)
        assertNull(findChunkAt(chunks, 3_500))
        assertNull(findChunkAt(chunks, 5_000))
    }

    @Test
    fun `chunksInRange_なぞった範囲に重なるチャンクをどちら向きでも返す`() {
        val chunks = listOf(
            chunk(1, 1_000, 2_000, "ja"),
            chunk(2, 2_000, 2_300, "ko"),
            chunk(3, 2_600, 4_000, "ko"),
            chunk(4, 5_000, 6_000, "ja"),
        )
        // 速く動かして短いチャンク 2 を飛び越えても選ばれる
        assertEquals(listOf(ChunkId(1), ChunkId(2), ChunkId(3)), chunksInRange(chunks, 1_500, 3_000).map { it.id })
        assertEquals(listOf(ChunkId(1), ChunkId(2), ChunkId(3)), chunksInRange(chunks, 3_000, 1_500).map { it.id })
        // 同じ位置なら、その位置を含むチャンクだけ
        assertEquals(listOf(ChunkId(3)), chunksInRange(chunks, 3_000, 3_000).map { it.id })
        // すき間だけなら空
        assertTrue(chunksInRange(chunks, 4_200, 4_800).isEmpty())
    }
}
