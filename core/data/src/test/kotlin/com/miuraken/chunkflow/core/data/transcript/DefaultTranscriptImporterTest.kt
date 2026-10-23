package com.miuraken.chunkflow.core.data.transcript

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.data.library.TranscriptImportResult
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import com.miuraken.chunkflow.core.database.entity.toExternalModel
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.WordTiming
import com.miuraken.chunkflow.core.testing.fake.FakeTranscriptParser
import com.miuraken.chunkflow.core.transcript.ChunkFlowTranscriptParser
import com.miuraken.chunkflow.core.transcript.CompositeTranscriptParser
import com.miuraken.chunkflow.core.transcript.TranscriptDocument
import com.miuraken.chunkflow.core.transcript.TranscriptError
import com.miuraken.chunkflow.core.transcript.TranscriptParseResult
import com.miuraken.chunkflow.core.transcript.TranscriptParser
import com.miuraken.chunkflow.core.transcript.TranscriptSegment
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.text.Normalizer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultTranscriptImporterTest {

    private lateinit var context: Context
    private lateinit var mediaItemDao: InMemoryMediaItemDao
    private lateinit var chunkDao: InMemoryChunkDao
    private lateinit var itemStateDao: InMemoryItemStateDao
    private lateinit var mediaFileLocator: MediaFileLocator
    private var currentEpochMs: Long = 1_750_000_000_000L

    private val sampleMediaId = "00000000-0000-4000-8000-000000000101"
    private val sampleSha256 = "a".repeat(64)

    @BeforeTest
    fun setUp() {
        TestTranscriptContentProvider.reset()
        Robolectric.buildContentProvider(TestTranscriptContentProvider::class.java)
            .create(TestTranscriptContentProvider.AUTHORITY)
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "media").deleteRecursively()
        File(context.filesDir, "analysis").deleteRecursively()

        mediaItemDao = InMemoryMediaItemDao()
        chunkDao = InMemoryChunkDao()
        itemStateDao = InMemoryItemStateDao()
        mediaFileLocator = MediaFileLocator(context.filesDir)
    }

    @AfterTest
    fun tearDown() {
        TestTranscriptContentProvider.reset()
    }

    private fun createImporter(
        parser: TranscriptParser = CompositeTranscriptParser(),
    ): DefaultTranscriptImporter = DefaultTranscriptImporter(
        context = context,
        mediaItemDao = mediaItemDao,
        chunkDao = chunkDao,
        itemStateDao = itemStateDao,
        libraryMediaFileResolver = mediaFileLocator,
        ioDispatcher = UnconfinedTestDispatcher(),
        transcriptParser = parser,
        clock = { currentEpochMs },
    )

    private suspend fun insertAudioItem(
        id: String = sampleMediaId,
        title: String = "lesson01",
        sha256: String = sampleSha256,
        durationMs: Long = 30_000L,
        targetLang: String = "en",
        baseLang: String? = "ja",
    ): MediaItemEntity {
        val entity = MediaItemEntity(
            id = id,
            title = title,
            durationMs = durationMs,
            mimeType = "audio/mpeg",
            sizeBytes = 480_000L,
            sha256 = sha256,
            course = "course-1",
            targetLang = targetLang,
            baseLang = baseLang,
            transcriptOffsetMs = 0,
            importedAt = 1_000L,
        )
        mediaItemDao.upsert(entity)
        itemStateDao.upsert(
            ItemStateEntity(
                mediaId = id,
                chunkSource = ChunkOrigin.AUTO.name,
                updatedAt = 1_000L,
            ),
        )
        return entity
    }

    private fun buildValidTranscriptJson(
        sha256: String = sampleSha256,
        fileName: String = "lesson01.mp3",
        durationMs: Long = 30_000L,
        targetLang: String = "zh",
        baseLang: String? = "ja",
        model: String = "large-v3-turbo",
        createdAt: String = "2026-10-14T12:00:00Z",
        segmentsJson: String = """
            [
              {
                "startMs": 1000,
                "endMs": 2700,
                "lang": "ja",
                "langProb": 0.99,
                "text": "では、聞いてみましょう。"
              },
              {
                "startMs": 3200,
                "endMs": 5470,
                "lang": "zh",
                "langProb": 0.97,
                "text": "你们好，欢迎来到北京。",
                "textVariants": { "Hant": "你們好，歡迎來到北京。" },
                "words": [
                  { "startMs": 3200, "endMs": 3980, "text": "你们好，", "prob": 0.95 },
                  { "startMs": 4110, "endMs": 5470, "text": "欢迎来到北京。", "prob": 0.96 }
                ]
              }
            ]
        """.trimIndent(),
    ): ByteArray {
        val baseField = if (baseLang == null) "null" else "\"$baseLang\""
        return """
            {
              "format": "chunkflow-transcript",
              "version": 1,
              "audio": {
                "fileName": "$fileName",
                "sha256": "$sha256",
                "durationMs": $durationMs
              },
              "languages": {
                "target": "$targetLang",
                "base": $baseField
              },
              "generator": {
                "tool": "chunkflow-transcribe 0.1.0",
                "engine": "faster-whisper 1.1.1",
                "model": "$model",
                "createdAt": "$createdAt"
              },
              "segments": $segmentsJson
            }
        """.trimIndent().encodeToByteArray()
    }

    @Test
    fun `importTranscript_SHA256が一致する教材にパディング付きチャンクと文字起こしと原本JSONを保存し言語ペアとchunkSourceを更新する`() = runTest {
        insertAudioItem(durationMs = 30_000L, targetLang = "en", baseLang = "ja")
        val jsonBytes = buildValidTranscriptJson()
        val uri = registerContent("t1", "lesson01.chunkflow.json", jsonBytes.size.toLong(), jsonBytes)

        val importer = createImporter()
        val result = importer.importTranscript(uri)

        assertEquals(
            TranscriptImportResult.Attached(
                mediaId = MediaId(sampleMediaId),
                chunkCount = 2,
                durationMismatch = false,
            ),
            result,
        )

        // 原本ファイルが filesDir/media/<MediaId>.transcript.chunkflow.json に保存されていること（§6.4）
        val rawFile = File(mediaFileLocator.mediaDir, "$sampleMediaId.transcript.chunkflow.json")
        assertTrue(rawFile.isFile)
        assertTrue(rawFile.readBytes().contentEquals(jsonBytes))

        // チャンクに前後のパディングが付与されて保存されていること
        val savedChunks = chunkDao.getByMediaIdAndOrigin(sampleMediaId, ChunkOrigin.IMPORTED.name)
        assertEquals(2, savedChunks.size)
        // 1つ目: 1000..2700 -> pre 150ms = 850, post 200ms = 2900 (2つ目 3200 との中点は 2950 なので 2900)
        assertEquals(850L, savedChunks[0].startMs)
        assertEquals(2_900L, savedChunks[0].endMs)
        // 2つ目: 3200..5470 -> pre 150ms = 3050, post 200ms = 5670
        assertEquals(3_050L, savedChunks[1].startMs)
        assertEquals(5_670L, savedChunks[1].endMs)

        // 文字起こしの textVariants と words と engine が正しく保存されていること
        val savedTranscripts = chunkDao.getTranscriptsByChunkIds(savedChunks.map { it.id })
            .associateBy { it.chunkId }
        val secondTranscript = savedTranscripts.getValue(savedChunks[1].id).toExternalModel()
        assertEquals("你们好，欢迎来到北京。", secondTranscript.text)
        assertEquals(mapOf("Hant" to "你們好，歡迎來到北京。"), secondTranscript.textVariants)
        assertEquals(LanguageTag("zh"), secondTranscript.language)
        assertEquals(EngineId("large-v3-turbo"), secondTranscript.engine)
        assertEquals(2, secondTranscript.words.size)
        assertEquals("你们好，", secondTranscript.words[0].text)

        // 教材の言語ペアと ItemState.chunkSource が更新されていること
        val updatedItem = mediaItemDao.getById(sampleMediaId)
        assertNotNull(updatedItem)
        assertEquals("zh", updatedItem.targetLang)
        assertEquals("ja", updatedItem.baseLang)

        val updatedState = itemStateDao.getByMediaId(sampleMediaId)
        assertNotNull(updatedState)
        assertEquals(ChunkOrigin.IMPORTED.name, updatedState.chunkSource)
        assertEquals(currentEpochMs, updatedState.updatedAt)
    }

    @Test
    fun `importTranscript_対応する音声がない場合はNoMatchingAudioを返す`() = runTest {
        val jsonBytes = buildValidTranscriptJson(
            sha256 = "f".repeat(64),
            fileName = "unimported_lesson.mp3",
        )
        val uri = registerContent("missing", "unimported_lesson.chunkflow.json", jsonBytes.size.toLong(), jsonBytes)

        val importer = createImporter()
        val result = importer.importTranscript(uri)

        assertEquals(TranscriptImportResult.NoMatchingAudio("unimported_lesson.mp3"), result)
    }

    @Test
    fun `importTranscript_audioSha256がnullの形式ではベースファイル名で教材を照合する`() = runTest {
        insertAudioItem(title = "dialogue_unit02")
        val fakeParser = FakeTranscriptParser { _, _ ->
            TranscriptParseResult.Ok(
                TranscriptDocument(
                    audioSha256 = null,
                    audioFileName = "dialogue_unit02.mp3",
                    durationMs = null,
                    languages = null,
                    generator = EngineId("srt"),
                    segments = listOf(
                        TranscriptSegment(
                            startMs = 500L,
                            endMs = 2_000L,
                            language = null,
                            languageConfidence = null,
                            text = "Subtitle line",
                            textVariants = emptyMap(),
                            words = emptyList(),
                        ),
                    ),
                ),
            )
        }
        val dummyBytes = "1\n00:00:00,500 --> 00:00:02,000\nSubtitle line\n".encodeToByteArray()
        val uri = registerContent("srt1", "dialogue_unit02.srt", dummyBytes.size.toLong(), dummyBytes)

        val importer = createImporter(parser = fakeParser)
        val result = importer.importTranscript(uri)

        assertEquals(
            TranscriptImportResult.Attached(
                mediaId = MediaId(sampleMediaId),
                chunkCount = 1,
                durationMismatch = false,
            ),
            result,
        )
    }

    @Test
    fun `importTranscript_SRTとWebVTTとLRCをNFC正規化と大文字小文字無視のファイル名stem照合で取り込める`() = runTest {
        val importer = createImporter()

        // 1. SRT: NFD ファイル名 & 大文字小文字違い -> NFC タイトルの教材に一致
        val itemSrtId = "00000000-0000-4000-8000-000000000201"
        insertAudioItem(id = itemSrtId, title = "がっこう_Lesson01.mp3", sha256 = "1".repeat(64))
        val srtBytes = """
            1
            00:00:01,000 --> 00:00:03,000
            <b>Hello</b> SRT!
        """.trimIndent().encodeToByteArray()
        val nfdFileName = Normalizer.normalize("がっこう_lesson01.SRT", Normalizer.Form.NFD)
        val srtUri = registerContent("sub_srt", nfdFileName, srtBytes.size.toLong(), srtBytes, "application/x-subrip")

        val srtResult = importer.importTranscript(srtUri)
        assertEquals(
            TranscriptImportResult.Attached(
                mediaId = MediaId(itemSrtId),
                chunkCount = 1,
                durationMismatch = false,
            ),
            srtResult,
        )
        val srtChunks = chunkDao.getByMediaIdAndOrigin(itemSrtId, ChunkOrigin.IMPORTED.name)
        assertEquals(1, srtChunks.size)
        val srtTranscript = chunkDao.getTranscriptsByChunkIds(listOf(srtChunks[0].id)).single().toExternalModel()
        assertEquals("Hello SRT!", srtTranscript.text)
        assertEquals(EngineId("subtitle-srt"), srtTranscript.engine)

        // 2. WebVTT
        val itemVttId = "00000000-0000-4000-8000-000000000202"
        insertAudioItem(id = itemVttId, title = "Dialogue_Unit02", sha256 = "2".repeat(64))
        val vttBytes = """
            WEBVTT

            00:01.000 --> 00:02.500
            <c.yellow>WebVTT</c> line 1

            00:03.000 --> 00:04.500
            WebVTT line 2
        """.trimIndent().encodeToByteArray()
        val vttUri = registerContent("sub_vtt", "dialogue_unit02.vtt", vttBytes.size.toLong(), vttBytes, "text/vtt")

        val vttResult = importer.importTranscript(vttUri)
        assertEquals(
            TranscriptImportResult.Attached(
                mediaId = MediaId(itemVttId),
                chunkCount = 2,
                durationMismatch = false,
            ),
            vttResult,
        )

        // 3. LRC
        val itemLrcId = "00000000-0000-4000-8000-000000000203"
        insertAudioItem(id = itemLrcId, title = "Track_03.m4a", sha256 = "3".repeat(64))
        val lrcBytes = """
            [ti:Track 03]
            [00:01.00]First LRC line
            [00:03.50]Second LRC line
        """.trimIndent().encodeToByteArray()
        val lrcUri = registerContent("sub_lrc", "track_03.lrc", lrcBytes.size.toLong(), lrcBytes, "text/plain")

        val lrcResult = importer.importTranscript(lrcUri)
        assertEquals(
            TranscriptImportResult.Attached(
                mediaId = MediaId(itemLrcId),
                chunkCount = 2,
                durationMismatch = false,
            ),
            lrcResult,
        )
    }

    @Test
    fun `importTranscript_音声長とJSONのdurationMsが500ms以上異なる場合はdurationMismatchをtrueにする`() = runTest {
        insertAudioItem(durationMs = 30_500L) // JSON は 30_000L -> 差 500ms
        val jsonBytes = buildValidTranscriptJson(durationMs = 30_000L)
        val uri = registerContent("mismatch", "lesson01.chunkflow.json", jsonBytes.size.toLong(), jsonBytes)

        val importer = createImporter()
        val result = importer.importTranscript(uri)
        assertTrue(result is TranscriptImportResult.Attached)
        assertTrue(result.durationMismatch)

        // 差が 499ms の場合は false
        val existingItem = assertNotNull(mediaItemDao.getById(sampleMediaId))
        mediaItemDao.upsert(existingItem.copy(durationMs = 30_499L))
        val uri2 = registerContent("match", "lesson01.chunkflow.json", jsonBytes.size.toLong(), jsonBytes)
        val result2 = importer.importTranscript(uri2)
        assertTrue(result2 is TranscriptImportResult.Attached)
        assertFalse(result2.durationMismatch)
    }

    @Test
    fun `importTranscript_再取り込み時にuserEditedがtrueの文字起こしを50ms以内一致および最大重なりで引き継ぐ`() = runTest {
        insertAudioItem(durationMs = 30_000L)
        val importer = createImporter()

        // 初回取り込み:
        // seg0: 1000..3000 -> padded 850..3200
        // seg1: 5000..8000 -> padded 4850..8200
        // seg2: 10000..12000 -> padded 9850..12200
        val initialSegments = """
            [
              { "startMs": 1000, "endMs": 3000, "lang": "ja", "text": "初回1" },
              { "startMs": 5000, "endMs": 8000, "lang": "zh", "text": "初回2" },
              { "startMs": 10000, "endMs": 12000, "lang": "zh", "text": "初回3" }
            ]
        """.trimIndent()
        val firstBytes = buildValidTranscriptJson(segmentsJson = initialSegments)
        val uri1 = registerContent("re1", "lesson01.chunkflow.json", firstBytes.size.toLong(), firstBytes)
        assertTrue(importer.importTranscript(uri1) is TranscriptImportResult.Attached)

        val firstChunks = chunkDao.getByMediaIdAndOrigin(sampleMediaId, ChunkOrigin.IMPORTED.name)
        assertEquals(3, firstChunks.size)

        // seg0 と seg1 の文字起こしを手動修正済み（userEdited = true）に更新する
        val existingTranscripts = chunkDao.getTranscriptsByChunkIds(firstChunks.map { it.id })
            .associateBy { it.chunkId }
        chunkDao.upsertTranscripts(
            listOf(
                existingTranscripts.getValue(firstChunks[0].id).copy(
                    text = "手動修正1（±50ms境界一致）",
                    userEdited = true,
                ),
                existingTranscripts.getValue(firstChunks[1].id).copy(
                    text = "手動修正2（最大重なり）",
                    userEdited = true,
                ),
            ),
        )

        // 再取り込み:
        // 新seg0: 1030..2980 -> padded 880..3180（旧 850..3200 と開始+30ms、終了-20ms で ±50ms 以内一致）
        // 新seg1: 5300..8600 -> padded 5150..8800（旧 4850..8200 と境界差 300ms/600ms だが重なり 3050ms で最大）
        // 新seg2: 10000..12000 -> userEdited = false なので新しいテキスト「再生成3」に置き換わる
        val secondSegments = """
            [
              { "startMs": 1030, "endMs": 2980, "lang": "ja", "text": "再生成1" },
              { "startMs": 5300, "endMs": 8600, "lang": "zh", "text": "再生成2" },
              { "startMs": 10000, "endMs": 12000, "lang": "zh", "text": "再生成3" }
            ]
        """.trimIndent()
        val secondBytes = buildValidTranscriptJson(
            createdAt = "2026-10-14T13:00:00Z",
            segmentsJson = secondSegments,
        )
        val uri2 = registerContent("re2", "lesson01.chunkflow.json", secondBytes.size.toLong(), secondBytes)
        assertTrue(importer.importTranscript(uri2) is TranscriptImportResult.Attached)

        val secondChunks = chunkDao.getByMediaIdAndOrigin(sampleMediaId, ChunkOrigin.IMPORTED.name)
        assertEquals(3, secondChunks.size)
        val secondTranscripts = chunkDao.getTranscriptsByChunkIds(secondChunks.map { it.id })
            .associateBy { it.chunkId }

        assertEquals("手動修正1（±50ms境界一致）", secondTranscripts.getValue(secondChunks[0].id).text)
        assertTrue(secondTranscripts.getValue(secondChunks[0].id).userEdited)

        assertEquals("手動修正2（最大重なり）", secondTranscripts.getValue(secondChunks[1].id).text)
        assertTrue(secondTranscripts.getValue(secondChunks[1].id).userEdited)

        assertEquals("再生成3", secondTranscripts.getValue(secondChunks[2].id).text)
        assertFalse(secondTranscripts.getValue(secondChunks[2].id).userEdited)
    }

    @Test
    fun `importBatch_同じ音声に対する複数のJSONがある場合はcreatedAtが最も新しいものだけを取り込む`() = runTest {
        insertAudioItem(durationMs = 30_000L)

        val olderBytes = buildValidTranscriptJson(
            createdAt = "2026-10-14T10:00:00Z",
            segmentsJson = """[{ "startMs": 1000, "endMs": 2000, "lang": "ja", "text": "古い版" }]""",
        )
        val newestBytes = buildValidTranscriptJson(
            createdAt = "2026-10-14T15:30:00Z",
            segmentsJson = """
                [
                  { "startMs": 1000, "endMs": 2000, "lang": "ja", "text": "最新版1" },
                  { "startMs": 3000, "endMs": 4000, "lang": "zh", "text": "最新版2" }
                ]
            """.trimIndent(),
        )
        val middleBytes = buildValidTranscriptJson(
            createdAt = "2026-10-14T12:00:00Z",
            segmentsJson = """[{ "startMs": 1000, "endMs": 2000, "lang": "ja", "text": "中間の版" }]""",
        )

        val uriOlder = registerContent("b1", "lesson01_old.chunkflow.json", olderBytes.size.toLong(), olderBytes)
        val uriNewest = registerContent("b2", "lesson01_new.chunkflow.json", newestBytes.size.toLong(), newestBytes)
        val uriMiddle = registerContent("b3", "lesson01_mid.chunkflow.json", middleBytes.size.toLong(), middleBytes)

        val importer = createImporter()
        val summary = importer.importBatch(listOf(uriOlder, uriNewest, uriMiddle))

        assertEquals(1, summary.attachedCount)
        assertEquals(2, summary.skippedDuplicateCount)
        assertEquals(0, summary.invalidCount)
        assertEquals(0, summary.noMatchingAudioCount)

        val chunks = chunkDao.getByMediaIdAndOrigin(sampleMediaId, ChunkOrigin.IMPORTED.name)
        assertEquals(2, chunks.size)
        val transcripts = chunkDao.getTranscriptsByChunkIds(chunks.map { it.id }).sortedBy { it.chunkId }
        assertEquals(listOf("最新版1", "最新版2"), transcripts.map { it.text })

        // 保存された原本ファイルも最新版のバイト列であること
        val rawFile = File(mediaFileLocator.mediaDir, "$sampleMediaId.transcript.chunkflow.json")
        assertTrue(rawFile.readBytes().contentEquals(newestBytes))
    }

    @Test
    fun `importTranscript_content以外のスキームや20MB超のストリームを拒否する`() = runTest {
        val importer = createImporter()

        val fileUri = Uri.parse("file:///sdcard/lesson01.chunkflow.json")
        assertEquals(
            TranscriptImportResult.Invalid(TranscriptError.UNKNOWN_FORMAT),
            importer.importTranscript(fileUri),
        )

        // サイズ申告が null でもストリームが 20MB + 1 バイト流れてきたら打ち切って TOO_LARGE にする
        val oversizedStream = object : InputStream() {
            private var remaining = ChunkFlowTranscriptParser.MAX_FILE_SIZE_BYTES + 1024
            override fun read(): Int = if (remaining-- > 0) ' '.code else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining <= 0) return -1
                val toRead = minOf(len, remaining)
                b.fill(' '.code.toByte(), off, off + toRead)
                remaining -= toRead
                return toRead
            }
        }
        val streamUri = Uri.parse("content://${TestTranscriptContentProvider.AUTHORITY}/huge_stream")
        TestTranscriptContentProvider.entries[streamUri] = TestTranscriptContentProvider.Entry(
            displayName = "huge.chunkflow.json",
            sizeBytes = null,
            mimeType = "application/json",
        )
        shadowOf(context.contentResolver).registerInputStream(streamUri, oversizedStream)

        assertEquals(
            TranscriptImportResult.Invalid(TranscriptError.TOO_LARGE),
            importer.importTranscript(streamUri),
        )
    }

    private fun registerContent(
        id: String,
        displayName: String,
        sizeBytes: Long?,
        bytes: ByteArray,
        mimeType: String = "application/json",
    ): Uri {
        val uri = Uri.parse("content://${TestTranscriptContentProvider.AUTHORITY}/$id")
        TestTranscriptContentProvider.entries[uri] = TestTranscriptContentProvider.Entry(
            displayName = displayName,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
        )
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private class InMemoryMediaItemDao : MediaItemDao {
        private val items = MutableStateFlow<Map<String, MediaItemEntity>>(emptyMap())

        override fun observeAll(): Flow<List<MediaItemEntity>> = items.map { it.values.toList() }
        override fun observeById(id: String): Flow<MediaItemEntity?> = items.map { it[id] }
        override suspend fun getById(id: String): MediaItemEntity? = items.value[id]
        override suspend fun getBySha256(sha256: String): MediaItemEntity? = items.value.values.firstOrNull { it.sha256 == sha256 }

        override suspend fun upsert(item: MediaItemEntity) {
            items.update { it + (item.id to item) }
        }

        override suspend fun insertOrIgnore(item: MediaItemEntity): Long {
            if (items.value.containsKey(item.id)) return -1L
            items.update { it + (item.id to item) }
            return 1L
        }

        override suspend fun update(item: MediaItemEntity) {
            items.update { it + (item.id to item) }
        }

        override suspend fun updateTranscriptOffset(id: String, offsetMs: Int) {
            items.update { current ->
                val existing = current[id] ?: return@update current
                current + (id to existing.copy(transcriptOffsetMs = offsetMs))
            }
        }

        override suspend fun updateDisplayName(id: String, displayName: String?): Int {
            var updated = 0
            items.update { current ->
                val existing = current[id] ?: return@update current
                updated = 1
                current + (id to existing.copy(displayName = displayName))
            }
            return updated
        }

        override suspend fun getItemsMissingDisplayName(): List<MediaItemEntity> = items.value.values
            .filter { it.displayName == null && (it.sourceUri != null || it.localFilePath != null) }
            .sortedWith(compareBy<MediaItemEntity> { it.importedAt }.thenBy { it.id })

        override suspend fun updateLanguagePair(id: String, targetLang: String, baseLang: String?) {
            items.update { current ->
                val existing = current[id] ?: return@update current
                current + (id to existing.copy(targetLang = targetLang, baseLang = baseLang))
            }
        }

        override suspend fun updateCourse(id: String, course: String?) {
            items.update { current ->
                val existing = current[id] ?: return@update current
                current + (id to existing.copy(course = course))
            }
        }

        override suspend fun updateCourseAndLanguagePair(
            id: String,
            course: String?,
            targetLang: String,
            baseLang: String?,
        ) {
            items.update { current ->
                val existing = current[id] ?: return@update current
                current + (id to existing.copy(course = course, targetLang = targetLang, baseLang = baseLang))
            }
        }

        override suspend fun updateCourseMetadata(
            oldCourse: String,
            newCourse: String?,
            targetLang: String,
            baseLang: String?,
        ) {
            items.update { current ->
                current.mapValues { (_, item) ->
                    if (item.course == oldCourse) {
                        item.copy(course = newCourse, targetLang = targetLang, baseLang = baseLang)
                    } else {
                        item
                    }
                }
            }
        }

        override suspend fun updateUncategorizedCourseMetadata(
            newCourse: String?,
            targetLang: String,
            baseLang: String?,
        ) {
            items.update { current ->
                current.mapValues { (_, item) ->
                    if (item.course == null) {
                        item.copy(course = newCourse, targetLang = targetLang, baseLang = baseLang)
                    } else {
                        item
                    }
                }
            }
        }

        override suspend fun deleteById(id: String) {
            items.update { it - id }
        }
    }

    private class InMemoryChunkDao : ChunkDao {
        private var nextId = 1L
        private val chunks = MutableStateFlow<Map<Long, ChunkEntity>>(emptyMap())
        private val transcripts = MutableStateFlow<Map<Long, TranscriptEntity>>(emptyMap())

        override fun observeByMediaIdAndOrigin(mediaId: String, origin: String): Flow<List<ChunkEntity>> = chunks.map { map ->
            map.values
                .filter { it.mediaId == mediaId && it.origin == origin }
                .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
        }

        override fun observeByMediaId(mediaId: String): Flow<List<ChunkEntity>> = chunks.map { map ->
            map.values
                .filter { it.mediaId == mediaId }
                .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
        }

        override fun observeOriginsByMediaId(mediaId: String): Flow<List<String>> = chunks.map { map ->
            map.values.filter { it.mediaId == mediaId }.map { it.origin }.distinct()
        }

        override suspend fun getByMediaIdAndOrigin(mediaId: String, origin: String): List<ChunkEntity> = chunks.value.values
            .filter { it.mediaId == mediaId && it.origin == origin }
            .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })

        override suspend fun insertAll(chunks: List<ChunkEntity>): List<Long> {
            val ids = mutableListOf<Long>()
            this.chunks.update { current ->
                val updated = current.toMutableMap()
                for (chunk in chunks) {
                    val assignedId = if (chunk.id == 0L) nextId++ else chunk.id
                    updated[assignedId] = chunk.copy(id = assignedId)
                    ids += assignedId
                }
                updated
            }
            return ids
        }

        override suspend fun getTranscriptsByChunkIds(chunkIds: List<Long>): List<TranscriptEntity> {
            val idSet = chunkIds.toSet()
            return transcripts.value.values.filter { it.chunkId in idSet }
        }

        override suspend fun upsertTranscripts(transcripts: List<TranscriptEntity>) {
            this.transcripts.update { current ->
                val updated = current.toMutableMap()
                for (t in transcripts) {
                    updated[t.chunkId] = t
                }
                updated
            }
        }

        override suspend fun deleteByIds(chunkIds: List<Long>) {
            val idSet = chunkIds.toSet()
            chunks.update { it - idSet }
            // ON DELETE CASCADE の模擬
            transcripts.update { it - idSet }
        }

        override suspend fun deleteByMediaIdAndOrigin(mediaId: String, origin: String) {
            val targetIds = chunks.value.values
                .filter { it.mediaId == mediaId && it.origin == origin }
                .map { it.id }
            deleteByIds(targetIds)
        }

        override suspend fun updateSkippedByIds(mediaId: String, chunkIds: List<Long>, skipped: Boolean) {
            val idSet = chunkIds.toSet()
            chunks.update { current ->
                current.mapValues { (id, chunk) ->
                    if (chunk.mediaId == mediaId && id in idSet) chunk.copy(isSkipped = skipped) else chunk
                }
            }
        }
    }

    private class InMemoryItemStateDao : ItemStateDao {
        private val states = MutableStateFlow<Map<String, ItemStateEntity>>(emptyMap())

        override fun observeAll(): Flow<List<ItemStateEntity>> = states.map { it.values.toList() }
        override fun observeByMediaId(mediaId: String): Flow<ItemStateEntity?> = states.map { it[mediaId] }
        override suspend fun getByMediaId(mediaId: String): ItemStateEntity? = states.value[mediaId]

        override suspend fun upsert(state: ItemStateEntity) {
            states.update { it + (state.mediaId to state) }
        }

        /** このテストでは、教材はいつも `media_item` にあるものとする。 */
        override suspend fun mediaItemExists(mediaId: String): Boolean = true

        override suspend fun updateChunkSource(mediaId: String, chunkSource: String, updatedAt: Long): Int {
            val existing = states.value[mediaId] ?: return 0
            states.update { it + (mediaId to existing.copy(chunkSource = chunkSource, updatedAt = updatedAt)) }
            return 1
        }

        override suspend fun getMaxQueuedAt(): Long = states.value.values.maxOfOrNull { it.queuedAt } ?: 0L
    }

    class TestTranscriptContentProvider : ContentProvider() {
        data class Entry(
            val displayName: String,
            val sizeBytes: Long?,
            val mimeType: String,
        )

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            val entry = entries[uri] ?: return null
            val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            cursor.addRow(arrayOf<Any?>(entry.displayName, entry.sizeBytes))
            return cursor
        }

        override fun getType(uri: Uri): String? = entries[uri]?.mimeType
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

        companion object {
            const val AUTHORITY = "com.miuraken.chunkflow.test.transcript.provider"
            val entries = mutableMapOf<Uri, Entry>()

            fun reset() {
                entries.clear()
            }
        }
    }
}
