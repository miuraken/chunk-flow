package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LanguageTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `canMergeSelectedChunks_連続していて両端の言語が同じときだけ連結できる`() {
        // 両端が ko で連続（途中の ja は端ではないので問わない）
        val contiguous = listOf(
            chunk(1, 0, 1_000, "ja"),
            chunk(2, 1_000, 2_000, "ko", selected = true),
            chunk(3, 2_000, 3_000, "ja", selected = true),
            chunk(4, 3_000, 4_000, "ko-KR", selected = true),
        )
        assertTrue(canMergeSelectedChunks(contiguous))
        assertEquals(listOf(ChunkId(2), ChunkId(3), ChunkId(4)), mergeableSelectedChunkIds(contiguous))

        // 途中に選ばれていないチャンクがある
        val gap = listOf(
            chunk(1, 0, 1_000, "ko", selected = true),
            chunk(2, 1_000, 2_000, "ko"),
            chunk(3, 2_000, 3_000, "ko", selected = true),
        )
        assertFalse(canMergeSelectedChunks(gap))
        assertTrue(mergeableSelectedChunkIds(gap).isEmpty())

        // 両端の言語が違う
        val differentEdges = listOf(
            chunk(1, 0, 1_000, "ja", selected = true),
            chunk(2, 1_000, 2_000, "ko", selected = true),
        )
        assertFalse(canMergeSelectedChunks(differentEdges))

        // 両端とも未判定（null / und）なら同じ言語とみなす
        val unknownEdges = listOf(
            chunk(1, 0, 1_000, null, selected = true),
            chunk(2, 1_000, 2_000, "und", selected = true),
        )
        assertTrue(canMergeSelectedChunks(unknownEdges))

        // 1 件だけの選択では連結しない
        assertFalse(canMergeSelectedChunks(listOf(chunk(1, 0, 1_000, "ko", selected = true))))
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
