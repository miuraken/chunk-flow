package com.miuraken.chunkflow.core.transcript

import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.WordTiming
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleTranscriptParserTest {

    private val subtitleParser = SubtitleTranscriptParser()
    private val compositeParser = CompositeTranscriptParser()
    private val jsonParser = ChunkFlowTranscriptParser()

    @Test
    fun `SRT正常系_BOM除去と複数行テキストとメタデータを正しく解析する`() {
        val srt = "\uFEFF" + """
            1
            00:00:01,200 --> 00:00:03,450
            Hello, world!
            Second line.

            2
            00:00:04,000 --> 00:00:06,500
            How are you?
        """.trimIndent()

        val result = subtitleParser.parse(srt.encodeToByteArray(), "lesson01.srt")
        val ok = assertIs<TranscriptParseResult.Ok>(result)
        val doc = ok.document

        assertNull(doc.audioSha256)
        assertEquals("lesson01", doc.audioFileName)
        assertNull(doc.durationMs)
        assertNull(doc.languages)
        assertEquals(EngineId("subtitle-srt"), doc.generator)
        assertEquals(2, doc.segments.size)

        assertEquals(1_200L, doc.segments[0].startMs)
        assertEquals(3_450L, doc.segments[0].endMs)
        assertEquals("Hello, world!\nSecond line.", doc.segments[0].text)
        assertNull(doc.segments[0].language)
        assertNull(doc.segments[0].languageConfidence)

        assertEquals(4_000L, doc.segments[1].startMs)
        assertEquals(6_500L, doc.segments[1].endMs)
        assertEquals("How are you?", doc.segments[1].text)
    }

    @Test
    fun `WebVTT正常系_NOTEやSTYLEブロックをスキップし省略時タイムスタンプと設定付きキューを解析する`() {
        val vtt = """
            WEBVTT - Sample Subtitle

            NOTE
            This is a comment block that should be skipped.

            STYLE
            ::cue { color: yellow; }

            cue-1
            00:01.500 --> 00:03.200 align:start position:10%
            <v Teacher>Welcome to <b>Unit 2</b>.</v>

            00:00:04.000 --> 00:00:06.800
            <c.yellow>Let's</c> <00:04.500>begin!
        """.trimIndent()

        val result = subtitleParser.parse(vtt.encodeToByteArray(), "unit02.vtt")
        val ok = assertIs<TranscriptParseResult.Ok>(result)
        val doc = ok.document

        assertNull(doc.audioSha256)
        assertEquals("unit02", doc.audioFileName)
        assertEquals(EngineId("subtitle-vtt"), doc.generator)
        assertEquals(2, doc.segments.size)

        assertEquals(1_500L, doc.segments[0].startMs)
        assertEquals(3_200L, doc.segments[0].endMs)
        assertEquals("Welcome to Unit 2.", doc.segments[0].text)

        assertEquals(4_000L, doc.segments[1].startMs)
        assertEquals(6_800L, doc.segments[1].endMs)
        assertEquals("Let's begin!", doc.segments[1].text)
    }

    @Test
    fun `LRC正常系_メタデータタグを無視し次行のstartMsをendMsとし最終行をプラス3000msにする`() {
        val lrc = """
            [ti:Sample Song]
            [ar:Speaker]
            [al:Course 1]
            [00:01.50]First lyric line
            [00:03.00]
            [00:04.25]<00:04.50>Second <b>lyric</b> line
            [01:02.000]Final lyric line
        """.trimIndent()

        val result = subtitleParser.parse(lrc.encodeToByteArray(), "track03.lrc")
        val ok = assertIs<TranscriptParseResult.Ok>(result)
        val doc = ok.document

        assertNull(doc.audioSha256)
        assertEquals("track03", doc.audioFileName)
        assertEquals(EngineId("subtitle-lrc"), doc.generator)
        assertEquals(3, doc.segments.size)

        assertEquals(1_500L, doc.segments[0].startMs)
        assertEquals(4_250L, doc.segments[0].endMs)
        assertEquals("First lyric line", doc.segments[0].text)

        assertEquals(4_250L, doc.segments[1].startMs)
        assertEquals(62_000L, doc.segments[1].endMs)
        assertEquals("Second lyric line", doc.segments[1].text)

        assertEquals(62_000L, doc.segments[2].startMs)
        assertEquals(65_000L, doc.segments[2].endMs)
        assertEquals("Final lyric line", doc.segments[2].text)
    }

    @Test
    fun `タグ除去とNFC正規化_NFD文字列をNFCに正規化し空テキストのキューをスキップする`() {
        val nfdText = Normalizer.normalize("がぎぐげご", Normalizer.Form.NFD)
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            <b><i><c.color><00:01.20>   </c></i></b>

            2
            00:00:02,500 --> 00:00:04,500
            <b>$nfdText</b> &amp; <i>テスト</i>
        """.trimIndent()

        val result = subtitleParser.parse(srt.encodeToByteArray(), "nfc_test.srt")
        val ok = assertIs<TranscriptParseResult.Ok>(result)
        assertEquals(1, ok.document.segments.size)
        val seg = ok.document.segments[0]
        assertEquals("がぎぐげご & テスト", seg.text)
        assertTrue(Normalizer.isNormalized(seg.text, Normalizer.Form.NFC))
    }

    @Test
    fun `全キューが空テキストになった場合はSCHEMA_VIOLATIONを返す`() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            <b><i><00:01.50></i></b>
        """.trimIndent()

        val result = subtitleParser.parse(srt.encodeToByteArray(), "empty.srt")
        val error = assertIs<TranscriptParseResult.Error>(result)
        assertEquals(TranscriptError.SCHEMA_VIOLATION, error.error)
    }

    @Test
    fun `重なりクランプ_開始時刻順に整列し前のendMsが次のstartMsを超える場合はクランプする`() {
        val srt = """
            1
            00:00:05,000 --> 00:00:08,000
            Later cue

            2
            00:00:01,000 --> 00:00:06,500
            Earlier overlapping cue

            3
            00:00:05,000 --> 00:00:07,000
            Same start time cue
        """.trimIndent()

        val result = subtitleParser.parse(srt.encodeToByteArray(), "overlap.srt")
        val ok = assertIs<TranscriptParseResult.Ok>(result)
        val segments = ok.document.segments

        // 1000..6500 は次の 5000 にクランプされて 1000..5000 になり、
        // 5000 開始の2件のうちクランプでゼロ長になる方は除去され 5000..8000 が残る
        assertEquals(2, segments.size)
        assertEquals(1_000L, segments[0].startMs)
        assertEquals(5_000L, segments[0].endMs)
        assertEquals("Earlier overlapping cue", segments[0].text)

        assertEquals(5_000L, segments[1].startMs)
        assertEquals(8_000L, segments[1].endMs)
    }

    @Test
    fun `時刻逆転または同一タイムスタンプはTIME_OUT_OF_RANGEを返す`() {
        val reversedSrt = """
            1
            00:00:05,000 --> 00:00:03,000
            Reversed timing
        """.trimIndent()
        val srtResult = subtitleParser.parse(reversedSrt.encodeToByteArray(), "bad.srt")
        val srtError = assertIs<TranscriptParseResult.Error>(srtResult)
        assertEquals(TranscriptError.TIME_OUT_OF_RANGE, srtError.error)

        val reversedLrc = """
            [00:05.00]First
            [00:03.00]Second
        """.trimIndent()
        val lrcResult = subtitleParser.parse(reversedLrc.encodeToByteArray(), "bad.lrc")
        val lrcError = assertIs<TranscriptParseResult.Error>(lrcResult)
        assertEquals(TranscriptError.TIME_OUT_OF_RANGE, lrcError.error)
    }

    @Test
    fun `上限検証_20MB超と50000セグメント超はTOO_LARGEを返し2000文字超はSCHEMA_VIOLATIONを返す`() {
        // 1. 20MB 超
        val hugeBytes = ByteArray(SubtitleTranscriptParser.MAX_FILE_SIZE_BYTES + 1) { 'a'.code.toByte() }
        val sizeResult = subtitleParser.parse(hugeBytes, "huge.srt")
        val sizeError = assertIs<TranscriptParseResult.Error>(sizeResult)
        assertEquals(TranscriptError.TOO_LARGE, sizeError.error)

        // 2. 50,000 セグメント超（LRC で 50,001 行）
        val sb = StringBuilder(1_200_000)
        for (idx in 0..SubtitleTranscriptParser.MAX_SEGMENTS) {
            val totalSeconds = idx + 1
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            sb.append('[')
                .append(minutes.toString().padStart(2, '0'))
                .append(':')
                .append(seconds.toString().padStart(2, '0'))
                .append(".00]Line ")
                .append(idx)
                .append('\n')
        }
        val manySegmentsResult = subtitleParser.parse(sb.toString().encodeToByteArray(), "many.lrc")
        val manySegmentsError = assertIs<TranscriptParseResult.Error>(manySegmentsResult)
        assertEquals(TranscriptError.TOO_LARGE, manySegmentsError.error)

        // 3. 1 セグメントあたり 2,000 コードポイント超
        val over2000Text = "あ".repeat(SubtitleTranscriptParser.MAX_SEGMENT_TEXT_CODE_POINTS + 1)
        val longCueSrt = """
            1
            00:00:01,000 --> 00:00:05,000
            $over2000Text
        """.trimIndent()
        val longTextResult = subtitleParser.parse(longCueSrt.encodeToByteArray(), "long.srt")
        val longTextError = assertIs<TranscriptParseResult.Error>(longTextResult)
        assertEquals(TranscriptError.SCHEMA_VIOLATION, longTextError.error)
    }

    @Test
    fun `CompositeTranscriptParser_拡張子および先頭内容からJSONとSRTとVTTとLRCを振り分ける`() {
        val srtBytes = "1\n00:00:01,000 --> 00:00:02,000\nHello SRT\n".encodeToByteArray()
        val vttBytes = "WEBVTT\n\n00:01.000 --> 00:02.000\nHello VTT\n".encodeToByteArray()
        val lrcBytes = "[00:01.00]Hello LRC\n".encodeToByteArray()

        val srtRes = assertIs<TranscriptParseResult.Ok>(compositeParser.parse(srtBytes, "a.srt"))
        assertEquals(EngineId("subtitle-srt"), srtRes.document.generator)

        val vttRes = assertIs<TranscriptParseResult.Ok>(compositeParser.parse(vttBytes, ""))
        assertEquals(EngineId("subtitle-vtt"), vttRes.document.generator)

        val lrcRes = assertIs<TranscriptParseResult.Ok>(compositeParser.parse(lrcBytes, ""))
        assertEquals(EngineId("subtitle-lrc"), lrcRes.document.generator)
    }

    @Test
    fun `ChunkFlowTranscriptSerializer_直列化したJSONがChunkFlowTranscriptParserで往復パースできる`() {
        val nfdInput = Normalizer.normalize("がっこうへ行こう", Normalizer.Form.NFD)
        val originalDoc = TranscriptDocument(
            audioSha256 = "b".repeat(64),
            audioFileName = "lesson05.mp3",
            durationMs = 20_000L,
            languages = LanguagePair(
                target = LanguageTag("zh"),
                base = LanguageTag("ja"),
            ),
            generator = EngineId("whisper-base"),
            segments = listOf(
                TranscriptSegment(
                    startMs = 500L,
                    endMs = 2_200L,
                    language = LanguageTag("ja"),
                    languageConfidence = 0.98f,
                    text = nfdInput,
                    textVariants = emptyMap(),
                    words = listOf(
                        WordTiming(startMs = 500L, endMs = 1_200L, text = "がっこうへ", confidence = 0.95f),
                        WordTiming(startMs = 1_200L, endMs = 2_200L, text = "行こう", confidence = 0.99f),
                    ),
                ),
                TranscriptSegment(
                    startMs = 3_000L,
                    endMs = 5_500L,
                    language = LanguageTag("zh"),
                    languageConfidence = 0.96f,
                    text = "我们去学校吧。",
                    textVariants = mapOf("Hant" to "我們去學校吧。"),
                    words = emptyList(),
                ),
            ),
            createdAt = "2026-10-14T18:00:00Z",
        )

        val serializer = ChunkFlowTranscriptSerializer()
        val jsonString = serializer.serialize(originalDoc)
        assertTrue(Normalizer.isNormalized(jsonString, Normalizer.Form.NFC))

        val parsedResult = jsonParser.parse(jsonString.encodeToByteArray(), "lesson05.chunkflow.json")
        val reparsed = assertIs<TranscriptParseResult.Ok>(parsedResult).document

        assertEquals(originalDoc.audioSha256, reparsed.audioSha256)
        assertEquals("lesson05.mp3", reparsed.audioFileName)
        assertEquals(20_000L, reparsed.durationMs)
        assertEquals(originalDoc.languages, reparsed.languages)
        assertEquals(EngineId("whisper-base"), reparsed.generator)
        assertEquals("2026-10-14T18:00:00Z", reparsed.createdAt)
        assertEquals(2, reparsed.segments.size)
        assertEquals("がっこうへ行こう", reparsed.segments[0].text)
        assertEquals(2, reparsed.segments[0].words.size)
        assertEquals(mapOf("Hant" to "我們去學校吧。"), reparsed.segments[1].textVariants)

        // SRT から得た TranscriptDocument に音声メタデータを補完して直列化・再パースできることも確認
        val srtParsed = assertIs<TranscriptParseResult.Ok>(
            subtitleParser.parse(
                "1\n00:00:01,000 --> 00:00:03,000\nHello from SRT\n".encodeToByteArray(),
                "lesson05.srt",
            ),
        ).document
        val convertedBytes = serializer.serializeToBytes(
            document = srtParsed,
            sha256 = "c".repeat(64),
            fileName = "lesson05.mp3",
            durationMs = 10_000L,
            languages = LanguagePair(target = LanguageTag("en"), base = null),
        )
        val reparsedFromSrt = assertIs<TranscriptParseResult.Ok>(
            jsonParser.parse(convertedBytes, "lesson05.chunkflow.json"),
        ).document
        assertEquals("c".repeat(64), reparsedFromSrt.audioSha256)
        assertEquals(1, reparsedFromSrt.segments.size)
        assertEquals("Hello from SRT", reparsedFromSrt.segments[0].text)
    }
}
