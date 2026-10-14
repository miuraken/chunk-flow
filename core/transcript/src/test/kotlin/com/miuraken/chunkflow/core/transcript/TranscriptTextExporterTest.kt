package com.miuraken.chunkflow.core.transcript

import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.TranscriptExportFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TranscriptTextExporterTest {

    private val subtitleParser = SubtitleTranscriptParser()

    private val sampleChunks = listOf(
        Chunk(
            id = ChunkId(2L),
            startMs = 65_430L,
            endMs = 68_900L,
            origin = ChunkOrigin.IMPORTED,
        ),
        Chunk(
            id = ChunkId(1L),
            startMs = 1_250L,
            endMs = 3_500L,
            origin = ChunkOrigin.IMPORTED,
        ),
        Chunk(
            id = ChunkId(3L),
            startMs = 120_000L,
            endMs = 125_000L,
            origin = ChunkOrigin.IMPORTED,
        ),
    )

    private val sampleTranscripts = mapOf(
        ChunkId(1L) to Transcript(
            chunkId = ChunkId(1L),
            text = "では、始めましょう。",
            language = LanguageTag("ja"),
            languageConfidence = 0.99f,
            engine = EngineId("whisper"),
            userEdited = false,
        ),
        ChunkId(2L) to Transcript(
            chunkId = ChunkId(2L),
            text = "Hello world!\nWelcome to Lesson 1.",
            language = LanguageTag("en"),
            languageConfidence = 0.97f,
            engine = EngineId("whisper"),
            userEdited = true,
        ),
        ChunkId(3L) to Transcript(
            chunkId = ChunkId(3L),
            text = "言語未設定の行",
            language = null,
            languageConfidence = null,
            engine = EngineId("whisper"),
            userEdited = false,
        ),
    )

    @Test
    fun `exportSrt_startMs昇順で1始まりの連番と標準SubRipタイムスタンプで出力する`() {
        val srt = TranscriptTextExporter.exportSrt(sampleChunks, sampleTranscripts)

        val expected = """
            1
            00:00:01,250 --> 00:00:03,500
            では、始めましょう。

            2
            00:01:05,430 --> 00:01:08,900
            Hello world!
            Welcome to Lesson 1.

            3
            00:02:00,000 --> 00:02:05,000
            言語未設定の行
        """.trimIndent() + "\n\n"

        assertEquals(expected, srt)
        assertEquals(srt, TranscriptTextExporter.export(sampleChunks, sampleTranscripts, TranscriptExportFormat.SRT))
    }

    @Test
    fun `exportLrc_startMs昇順で複数行テキストを空白連結したLRC形式で出力する`() {
        val lrc = TranscriptTextExporter.exportLrc(sampleChunks, sampleTranscripts)

        val expected = """
            [00:01.25]では、始めましょう。
            [01:05.43]Hello world! Welcome to Lesson 1.
            [02:00.00]言語未設定の行
        """.trimIndent()

        assertEquals(expected, lrc)
        assertEquals(lrc, TranscriptTextExporter.export(sampleChunks, sampleTranscripts, TranscriptExportFormat.LRC))
    }

    @Test
    fun `exportTxt_startMs昇順で言語タグ有無に応じたTXT形式で1行ずつ出力する`() {
        val txt = TranscriptTextExporter.exportTxt(sampleChunks, sampleTranscripts)

        val expected = """
            [00:01] [JA] では、始めましょう。
            [01:05] [EN] Hello world! Welcome to Lesson 1.
            [02:00] 言語未設定の行
        """.trimIndent()

        assertEquals(expected, txt)
        assertEquals(txt, TranscriptTextExporter.export(sampleChunks, sampleTranscripts, TranscriptExportFormat.TXT))
    }

    @Test
    fun `exportSrtとexportLrcの出力がSubtitleTranscriptParserで再パースできる`() {
        val srtText = TranscriptTextExporter.exportSrt(sampleChunks, sampleTranscripts)
        val srtParsed = assertIs<TranscriptParseResult.Ok>(
            subtitleParser.parse(srtText.encodeToByteArray(), "exported.srt"),
        ).document

        assertEquals(3, srtParsed.segments.size)
        assertEquals(1_250L, srtParsed.segments[0].startMs)
        assertEquals(3_500L, srtParsed.segments[0].endMs)
        assertEquals("では、始めましょう。", srtParsed.segments[0].text)

        assertEquals(65_430L, srtParsed.segments[1].startMs)
        assertEquals(68_900L, srtParsed.segments[1].endMs)
        assertEquals("Hello world!\nWelcome to Lesson 1.", srtParsed.segments[1].text)

        assertEquals(120_000L, srtParsed.segments[2].startMs)
        assertEquals(125_000L, srtParsed.segments[2].endMs)
        assertEquals("言語未設定の行", srtParsed.segments[2].text)

        val lrcText = TranscriptTextExporter.exportLrc(sampleChunks, sampleTranscripts)
        val lrcParsed = assertIs<TranscriptParseResult.Ok>(
            subtitleParser.parse(lrcText.encodeToByteArray(), "exported.lrc"),
        ).document

        assertEquals(3, lrcParsed.segments.size)
        assertEquals(1_250L, lrcParsed.segments[0].startMs)
        assertEquals(65_430L, lrcParsed.segments[0].endMs)
        assertEquals("では、始めましょう。", lrcParsed.segments[0].text)

        assertEquals(65_430L, lrcParsed.segments[1].startMs)
        assertEquals(120_000L, lrcParsed.segments[1].endMs)
        assertEquals("Hello world! Welcome to Lesson 1.", lrcParsed.segments[1].text)

        assertEquals(120_000L, lrcParsed.segments[2].startMs)
        assertEquals(123_000L, lrcParsed.segments[2].endMs)
        assertEquals("言語未設定の行", lrcParsed.segments[2].text)
    }

    @Test
    fun `空リストや文字起こし未設定チャンクや空白のみのチャンクをスキップする`() {
        assertEquals("", TranscriptTextExporter.exportSrt(emptyList(), emptyMap()))
        assertEquals("", TranscriptTextExporter.exportLrc(emptyList(), emptyMap()))
        assertEquals("", TranscriptTextExporter.exportTxt(emptyList(), emptyMap()))

        val chunks = listOf(
            Chunk(id = ChunkId(10L), startMs = 0L, endMs = 1_000L, origin = ChunkOrigin.AUTO),
            Chunk(id = ChunkId(11L), startMs = 1_000L, endMs = 2_000L, origin = ChunkOrigin.AUTO),
            Chunk(id = ChunkId(12L), startMs = 2_000L, endMs = 3_000L, origin = ChunkOrigin.AUTO),
        )
        val partialTranscripts = mapOf(
            ChunkId(10L) to Transcript(
                chunkId = ChunkId(10L),
                text = "   \n  ",
                language = LanguageTag("ja"),
                languageConfidence = null,
                engine = EngineId("whisper"),
                userEdited = false,
            ),
            ChunkId(12L) to Transcript(
                chunkId = ChunkId(12L),
                text = "有効なチャンクのみ出力される",
                language = LanguageTag("ja"),
                languageConfidence = null,
                engine = EngineId("whisper"),
                userEdited = false,
            ),
        )

        val srt = TranscriptTextExporter.exportSrt(chunks, partialTranscripts)
        assertEquals(
            "1\n00:00:02,000 --> 00:00:03,000\n有効なチャンクのみ出力される\n\n",
            srt,
        )
        assertEquals(
            "[00:02.00]有効なチャンクのみ出力される",
            TranscriptTextExporter.exportLrc(chunks, partialTranscripts),
        )
        assertEquals(
            "[00:02] [JA] 有効なチャンクのみ出力される",
            TranscriptTextExporter.exportTxt(chunks, partialTranscripts),
        )
    }

    @Test
    fun `suggestedExtensionとsuggestedMimeTypeが各形式に対応する値を返す`() {
        assertEquals("srt", TranscriptTextExporter.suggestedExtension(TranscriptExportFormat.SRT))
        assertEquals("lrc", TranscriptTextExporter.suggestedExtension(TranscriptExportFormat.LRC))
        assertEquals("txt", TranscriptTextExporter.suggestedExtension(TranscriptExportFormat.TXT))

        assertEquals("application/x-subrip", TranscriptTextExporter.suggestedMimeType(TranscriptExportFormat.SRT))
        assertEquals("text/plain", TranscriptTextExporter.suggestedMimeType(TranscriptExportFormat.LRC))
        assertEquals("text/plain", TranscriptTextExporter.suggestedMimeType(TranscriptExportFormat.TXT))
    }
}
