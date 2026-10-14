package com.miuraken.chunkflow.core.transcript

import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.WordTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptChunkConverterTest {

    private fun segment(
        startMs: Long,
        endMs: Long,
        text: String = "sample",
        words: List<WordTiming> = emptyList(),
    ): TranscriptSegment = TranscriptSegment(
        startMs = startMs,
        endMs = endMs,
        language = LanguageTag("ko"),
        languageConfidence = 0.98f,
        text = text,
        textVariants = emptyMap(),
        words = words,
    )

    @Test
    fun `convert_十分な無音がある場合は既定のprePadding150msとpostPadding200msを付与する`() {
        val segments = listOf(
            segment(1_000L, 2_500L, "first"),
            segment(4_000L, 5_500L, "second"),
        )
        val converted = TranscriptChunkConverter.convert(
            segments = segments,
            audioDurationMs = 10_000L,
        )

        assertEquals(2, converted.size)
        assertEquals(850L, converted[0].startMs)
        assertEquals(2_700L, converted[0].endMs)
        assertEquals(3_850L, converted[1].startMs)
        assertEquals(5_700L, converted[1].endMs)
    }

    @Test
    fun `convert_隣接するセグメント間の無音が短い場合は無音の中点を越えない`() {
        // 1つ目: 1000..2000, 2つ目: 2200..3000 -> 無音は 2000..2200 (200ms), 中点は 2100ms
        // 2つ目と3つ目は接している: 3000..4000 -> 中点は 3000ms
        val segments = listOf(
            segment(1_000L, 2_000L, "one"),
            segment(2_200L, 3_000L, "two"),
            segment(3_000L, 4_000L, "three"),
        )
        val converted = TranscriptChunkConverter().convert(
            segments = segments,
            audioDurationMs = 4_100L,
            prePaddingMs = 150,
            postPaddingMs = 200,
        )

        assertEquals(3, converted.size)
        assertEquals(850L, converted[0].startMs)
        assertEquals(2_100L, converted[0].endMs)

        assertEquals(2_100L, converted[1].startMs)
        assertEquals(3_000L, converted[1].endMs)

        assertEquals(3_000L, converted[2].startMs)
        // 音声終端 4100ms でクランプされる
        assertEquals(4_100L, converted[2].endMs)
    }

    @Test
    fun `convert_先頭0msと音声長audioDurationMsを超えず範囲外のセグメントを切り詰めまたは除外する`() {
        val segments = listOf(
            segment(50L, 1_000L, "near-start"),
            segment(
                startMs = 2_000L,
                endMs = 5_500L,
                text = "extends-past-end",
                words = listOf(
                    WordTiming(2_000L, 3_000L, "in-range", 0.9f),
                    WordTiming(4_800L, 5_300L, "clamped-word", 0.9f),
                    WordTiming(5_100L, 5_500L, "dropped-word", 0.9f),
                ),
            ),
            segment(5_000L, 6_000L, "at-or-after-duration"),
            segment(6_200L, 7_000L, "way-after-duration"),
        )

        val converted = TranscriptChunkConverter.convert(
            segments = segments,
            audioDurationMs = 5_000L,
        )

        assertEquals(2, converted.size)
        // 1つ目: 50 - 150 -> 0ms にクランプ
        assertEquals(0L, converted[0].startMs)
        assertEquals(1_200L, converted[0].endMs)

        // 2つ目: endMs が 5500 -> 5000 に切り詰められ、5000 以降のセグメントは除外される
        assertEquals(1_850L, converted[1].startMs)
        assertEquals(5_000L, converted[1].endMs)
        assertEquals(2_000L, converted[1].segment.startMs)
        assertEquals(5_000L, converted[1].segment.endMs)
        assertEquals(2, converted[1].segment.words.size)
        assertEquals(WordTiming(2_000L, 3_000L, "in-range", 0.9f), converted[1].segment.words[0])
        assertEquals(WordTiming(4_800L, 5_000L, "clamped-word", 0.9f), converted[1].segment.words[1])
    }

    @Test
    fun `convert_audioDurationMsが0以下または空リストの場合は空リストを返す`() {
        assertTrue(TranscriptChunkConverter.convert(emptyList(), 10_000L).isEmpty())
        assertTrue(TranscriptChunkConverter.convert(listOf(segment(0L, 1_000L)), 0L).isEmpty())
    }
}
