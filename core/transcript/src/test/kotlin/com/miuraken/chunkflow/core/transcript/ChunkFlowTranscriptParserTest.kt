package com.miuraken.chunkflow.core.transcript

import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import java.io.File
import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ChunkFlowTranscriptParserTest {

    private val parser = ChunkFlowTranscriptParser()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ExpectationsFile(
        val files: List<InvalidExpectation>,
    )

    @Serializable
    private data class InvalidExpectation(
        val file: String,
        val layer: String,
        val error: String,
        val rule: String,
    )

    private fun resolveExamplesDir(): File {
        val direct = File("../../docs/format/examples")
        if (direct.isDirectory) return direct.canonicalFile
        var current: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (current != null) {
            val candidate = File(current, "docs/format/examples")
            if (candidate.isDirectory) return candidate
            current = current.parentFile
        }
        error("Could not locate docs/format/examples directory")
    }

    @Test
    fun `parse_すべての正しいサンプルファイルが検証を通過して正規化済みDocumentを返す`() {
        val examplesDir = resolveExamplesDir()
        val validFiles = examplesDir.listFiles { file ->
            file.isFile && file.name.endsWith(".chunkflow.json")
        }?.sortedBy { it.name }.orEmpty()

        assertTrue(validFiles.isNotEmpty(), "Valid example files must exist in $examplesDir")

        for (file in validFiles) {
            val bytes = file.readBytes()
            val result = parser.parse(bytes, file.name)
            assertTrue(
                result is TranscriptParseResult.Ok,
                "Expected valid example ${file.name} to succeed, but got: $result",
            )
            val doc = result.document
            assertNotNull(doc.audioSha256)
            assertEquals(64, doc.audioSha256.length)
            assertNotNull(doc.audioFileName)
            assertNotNull(doc.durationMs)
            assertTrue(doc.durationMs > 0L)
            assertNotNull(doc.languages)
            assertNotNull(doc.createdAt)
            assertNotNull(ChunkFlowTranscriptParser.parseCreatedAtEpochMs(doc.createdAt))

            for (seg in doc.segments) {
                assertTrue(seg.startMs >= 0L)
                assertTrue(seg.startMs < seg.endMs)
                assertTrue(seg.endMs <= doc.durationMs)
                assertTrue(Normalizer.isNormalized(seg.text, Normalizer.Form.NFC))
                seg.textVariants.values.forEach { variant ->
                    assertTrue(Normalizer.isNormalized(variant, Normalizer.Form.NFC))
                }
                seg.words.forEach { word ->
                    assertTrue(Normalizer.isNormalized(word.text, Normalizer.Form.NFC))
                }
            }
        }
    }

    @Test
    fun `parse_すべての不正なサンプルファイルがexpectations通りのTranscriptErrorで拒否される`() {
        val invalidDir = File(resolveExamplesDir(), "invalid")
        val expectationsFile = File(invalidDir, "expectations.json")
        assertTrue(expectationsFile.isFile, "expectations.json must exist in $invalidDir")

        val expectations = json.decodeFromString<ExpectationsFile>(expectationsFile.readText(Charsets.UTF_8))
        val expectedByFile = expectations.files.associateBy { it.file }

        val invalidFiles = invalidDir.listFiles { file ->
            file.isFile && file.name.endsWith(".chunkflow.json")
        }?.sortedBy { it.name }.orEmpty()

        assertEquals(
            expectedByFile.keys,
            invalidFiles.map { it.name }.toSet(),
            "Invalid files in directory must match expectations.json entries",
        )

        for (file in invalidFiles) {
            val expectation = expectedByFile.getValue(file.name)
            val expectedError = TranscriptError.valueOf(expectation.error)
            val result = parser.parse(file.readBytes(), file.name)
            assertTrue(
                result is TranscriptParseResult.Error,
                "Expected invalid example ${file.name} (${expectation.rule}) to fail, but succeeded",
            )
            assertEquals(
                expectedError,
                result.error,
                "Mismatch for ${file.name} (${expectation.rule}): detail=${result.detail}",
            )
        }
    }

    @Test
    fun `parse_ファイルサイズが20MBを超える場合はTOO_LARGEで拒否する`() {
        val oversized = ByteArray(ChunkFlowTranscriptParser.MAX_FILE_SIZE_BYTES + 1) { ' '.code.toByte() }
        val result = parser.parse(oversized, "oversized.chunkflow.json")
        assertTrue(result is TranscriptParseResult.Error)
        assertEquals(TranscriptError.TOO_LARGE, result.error)
    }

    @Test
    fun `parse_セグメント数が50000件を超える場合はTOO_LARGEで拒否する`() {
        val count = ChunkFlowTranscriptParser.MAX_SEGMENTS + 1
        val sb = StringBuilder(count * 44 + 512)
        sb.append(
            """
            {
              "format": "chunkflow-transcript",
              "version": 1,
              "audio": {
                "fileName": "many.mp3",
                "sha256": "${"a".repeat(64)}",
                "durationMs": ${count * 10L + 1000L}
              },
              "languages": { "target": "ko", "base": "ja" },
              "generator": {
                "tool": "chunkflow-transcribe 0.1.0",
                "engine": "faster-whisper 1.1.1",
                "model": "large-v3-turbo",
                "createdAt": "2026-10-14T12:00:00Z"
              },
              "segments": [
            """.trimIndent(),
        )
        for (i in 0 until count) {
            if (i > 0) sb.append(',')
            val start = i * 10L
            val end = start + 5L
            sb.append("""{"startMs":$start,"endMs":$end,"text":"a"}""")
        }
        sb.append("]}")

        val bytes = sb.toString().encodeToByteArray()
        assertTrue(bytes.size <= ChunkFlowTranscriptParser.MAX_FILE_SIZE_BYTES)

        val result = parser.parse(bytes, "many.chunkflow.json")
        assertTrue(result is TranscriptParseResult.Error)
        assertEquals(TranscriptError.TOO_LARGE, result.error)
    }

    @Test
    fun `parse_textおよびtextVariantsが2000文字を超える場合はTOO_LARGEで拒否する`() {
        val over2000 = "あ".repeat(ChunkFlowTranscriptParser.MAX_SEGMENT_TEXT_CODE_POINTS + 1)
        val jsonVariantTooLong = """
            {
              "format": "chunkflow-transcript",
              "version": 1,
              "audio": {
                "fileName": "lesson.mp3",
                "sha256": "${"b".repeat(64)}",
                "durationMs": 10000
              },
              "languages": { "target": "zh", "base": "ja" },
              "generator": {
                "tool": "chunkflow-transcribe 0.1.0",
                "engine": "faster-whisper 1.1.1",
                "model": "large-v3-turbo",
                "createdAt": "2026-10-14T12:00:00Z"
              },
              "segments": [
                {
                  "startMs": 100,
                  "endMs": 2000,
                  "lang": "zh",
                  "text": "你好",
                  "textVariants": {
                    "Hant": "$over2000"
                  }
                }
              ]
            }
        """.trimIndent().encodeToByteArray()

        val result = parser.parse(jsonVariantTooLong, "variant-too-long.chunkflow.json")
        assertTrue(result is TranscriptParseResult.Error)
        assertEquals(TranscriptError.TOO_LARGE, result.error)
    }

    @Test
    fun `parse_NFDで分解されたベトナム語や日本語をUnicode_NFCに正規化する`() {
        // ベトナム語 "Tiếng Việt" と日本語 "がぎぐげご" を NFD（分解形）で作る
        val nfdVi = Normalizer.normalize("Tiếng Việt", Normalizer.Form.NFD)
        val nfdJa = Normalizer.normalize("がぎぐげご", Normalizer.Form.NFD)
        val nfdWord = "  " + Normalizer.normalize("Tiếng", Normalizer.Form.NFD) + "  "

        val payload = """
            {
              "format": "chunkflow-transcript",
              "version": 1,
              "audio": {
                "fileName": "vi.mp3",
                "sha256": "${"c".repeat(64)}",
                "durationMs": 10000
              },
              "languages": { "target": "vi", "base": "ja" },
              "generator": {
                "tool": "chunkflow-transcribe 0.1.0",
                "engine": "mlx-whisper",
                "model": "large-v3-turbo",
                "createdAt": "2026-10-14T12:00:00Z"
              },
              "segments": [
                {
                  "startMs": 500,
                  "endMs": 2500,
                  "lang": "vi",
                  "langProb": 0.95,
                  "text": "$nfdVi",
                  "textVariants": {
                    "Hans": "$nfdJa"
                  },
                  "words": [
                    { "startMs": 500, "endMs": 1200, "text": "$nfdWord", "prob": 0.9 }
                  ]
                }
              ]
            }
        """.trimIndent().encodeToByteArray()

        val result = parser.parse(payload, "vi.chunkflow.json")
        assertTrue(result is TranscriptParseResult.Ok)
        val seg = result.document.segments.single()
        assertEquals("Tiếng Việt", seg.text)
        assertTrue(Normalizer.isNormalized(seg.text, Normalizer.Form.NFC))
        assertEquals("がぎぐげご", seg.textVariants["Hans"])
        assertEquals("Tiếng", seg.words.single().text)
    }

    @Test
    fun `parse_知らないフィールドが含まれていても無視して前方互換で読み込める`() {
        val payload = """
            {
              "format": "chunkflow-transcript",
              "version": 1,
              "futureRootField": { "enabled": true, "items": [1, 2, 3] },
              "audio": {
                "fileName": "lesson.mp3",
                "sha256": "${"d".repeat(64)}",
                "durationMs": 15000,
                "channels": 2
              },
              "languages": { "target": "ko", "base": "ja", "dialect": "seoul" },
              "generator": {
                "tool": "chunkflow-transcribe 0.2.0",
                "engine": "mlx-whisper",
                "model": "large-v3",
                "createdAt": "2026-10-14T15:30:00+09:00",
                "extraMeta": "ignored"
              },
              "segments": [
                {
                  "startMs": 1000,
                  "endMs": 3000,
                  "lang": null,
                  "langProb": 0.45,
                  "text": "안녕하세요",
                  "speaker": "Speaker 1",
                  "words": [
                    { "startMs": 1000, "endMs": 3000, "text": "안녕하세요", "speaker": "S1" }
                  ]
                }
              ]
            }
        """.trimIndent().encodeToByteArray()

        val result = parser.parse(payload, "compat.chunkflow.json")
        assertTrue(result is TranscriptParseResult.Ok)
        val doc = result.document
        assertEquals(EngineId("large-v3"), doc.generator)
        assertEquals(LanguagePair(LanguageTag("ko"), LanguageTag("ja")), doc.languages)
        assertEquals("2026-10-14T15:30:00+09:00", doc.createdAt)
        // lang が null の場合は langProb があっても無視して null にする（docs/format/README.md）
        val seg = doc.segments.single()
        assertNull(seg.language)
        assertNull(seg.languageConfidence)
    }

    @Test
    fun `parse_durationMsが0以下の場合はSCHEMA_VIOLATIONで拒否する`() {
        val payload = """
            {
              "format": "chunkflow-transcript",
              "version": 1,
              "audio": {
                "fileName": "zero.mp3",
                "sha256": "${"e".repeat(64)}",
                "durationMs": 0
              },
              "languages": { "target": "ko" },
              "generator": {
                "tool": "chunkflow-transcribe 0.1.0",
                "engine": "mlx-whisper",
                "model": "large-v3-turbo",
                "createdAt": "2026-10-14T12:00:00Z"
              },
              "segments": []
            }
        """.trimIndent().encodeToByteArray()

        val result = parser.parse(payload, "zero.chunkflow.json")
        assertTrue(result is TranscriptParseResult.Error)
        assertEquals(TranscriptError.SCHEMA_VIOLATION, result.error)
    }

    @Test
    fun `extractCreatedAt_JSONバイト列からcreatedAtとエポックミリ秒を取得できる`() {
        val bytes = """
            {
              "generator": {
                "createdAt": "2026-10-14T12:00:00Z"
              }
            }
        """.trimIndent().encodeToByteArray()

        assertEquals("2026-10-14T12:00:00Z", ChunkFlowTranscriptParser.extractCreatedAt(bytes))
        assertEquals(1_791_979_200_000L, ChunkFlowTranscriptParser.extractCreatedAtEpochMs(bytes))
    }
}
