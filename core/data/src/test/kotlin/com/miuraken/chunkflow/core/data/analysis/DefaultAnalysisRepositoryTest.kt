package com.miuraken.chunkflow.core.data.analysis

import android.content.Context
import com.miuraken.chunkflow.core.audio.AnalysisBinaryFormat
import com.miuraken.chunkflow.core.audio.CURRENT_ALGORITHM_VERSION
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.audio.StoredFeatures
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.toEntity
import com.miuraken.chunkflow.core.media.AudioDecodeResult
import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import com.miuraken.chunkflow.core.media.DecodeProgress
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriber
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriptionResult
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.model.WordTiming
import com.miuraken.chunkflow.core.transcript.ChunkFlowTranscriptParser
import com.miuraken.chunkflow.core.transcript.TranscriptParseResult
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultAnalysisRepositoryTest {

    private lateinit var context: Context
    private lateinit var analysisStateDao: FakeAnalysisStateDao
    private lateinit var transcriptDao: FakeTranscriptDao
    private lateinit var chunkDao: FakeChunkDao
    private lateinit var itemStateDao: FakeItemStateDao
    private lateinit var mediaItemDao: FakeMediaItemDao
    private lateinit var bookmarkDao: FakeBookmarkDao
    private lateinit var mediaFileLocator: MediaFileLocator
    private var currentEpochMs: Long = 1_700_000_000_000L

    @BeforeTest
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "media").deleteRecursively()
        File(context.filesDir, "analysis").deleteRecursively()

        analysisStateDao = FakeAnalysisStateDao()
        transcriptDao = FakeTranscriptDao()
        chunkDao = FakeChunkDao(transcriptDao)
        itemStateDao = FakeItemStateDao()
        mediaItemDao = FakeMediaItemDao()
        bookmarkDao = FakeBookmarkDao()
        mediaFileLocator = MediaFileLocator(context.filesDir)
    }

    private fun createRepository(
        testScheduler: TestCoroutineScheduler,
        onDeviceChunkTranscriber: OnDeviceChunkTranscriber? = null,
    ): DefaultAnalysisRepository = if (onDeviceChunkTranscriber != null) {
        DefaultAnalysisRepository(
            context = context,
            analysisStateDao = analysisStateDao,
            chunkDao = chunkDao,
            transcriptDao = transcriptDao,
            itemStateDao = itemStateDao,
            mediaItemDao = mediaItemDao,
            mediaFileResolver = mediaFileLocator,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            onDeviceChunkTranscriber = onDeviceChunkTranscriber,
            bookmarkDao = bookmarkDao,
            clock = { currentEpochMs },
        )
    } else {
        DefaultAnalysisRepository(
            context = context,
            analysisStateDao = analysisStateDao,
            chunkDao = chunkDao,
            transcriptDao = transcriptDao,
            itemStateDao = itemStateDao,
            mediaItemDao = mediaItemDao,
            mediaFileResolver = mediaFileLocator,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            bookmarkDao = bookmarkDao,
            clock = { currentEpochMs },
        )
    }

    @Test
    fun `observeWaveform_waveformBinが存在する場合に読み込みステータス更新に追従する`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_01")
        assertNull(repository.observeWaveform(mediaId).first())

        val waveformFile = File(File(File(context.filesDir, "analysis"), mediaId.value), "waveform.bin")
        val expectedWaveform = Waveform(
            binMs = 20,
            peak = byteArrayOf(50, 120, -10),
            rms = byteArrayOf(25, 80, -60),
        )
        assertTrue(AnalysisBinaryFormat.writeWaveformAtomic(waveformFile, expectedWaveform))
        analysisStateDao.upsert(
            StageStatus.Done.toEntity(
                mediaId = mediaId,
                stage = AnalysisStage.FEATURES,
                algorithmVersion = CURRENT_ALGORITHM_VERSION,
                updatedAtEpochMs = currentEpochMs,
            ),
        )

        val actual = repository.observeWaveform(mediaId).first()
        assertNotNull(actual)
        assertEquals(expectedWaveform.binMs, actual.binMs)
        assertContentEquals(expectedWaveform.peak, actual.peak)
        assertContentEquals(expectedWaveform.rms, actual.rms)
    }

    @Test
    fun `observeChunks_setChunkSourceに応じて対象originのチャンクを返し未存在時は優先順フォールバックする`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_02")

        val autoChunks = listOf(
            ChunkEntity(
                id = 1L,
                mediaId = mediaId.value,
                startMs = 0L,
                endMs = 1_500L,
                origin = ChunkOrigin.AUTO.name,
            ),
        )
        val importedChunks = listOf(
            ChunkEntity(
                id = 2L,
                mediaId = mediaId.value,
                startMs = 100L,
                endMs = 1_400L,
                origin = ChunkOrigin.IMPORTED.name,
            ),
        )
        chunkDao.insertAll(autoChunks + importedChunks)

        // item_state が AUTO 指定のときは AUTO を返す
        repository.setChunkSource(mediaId, ChunkOrigin.AUTO)
        val activeAuto = repository.observeChunks(mediaId).first()
        assertEquals(1, activeAuto.size)
        assertEquals(ChunkOrigin.AUTO, activeAuto.first().origin)

        // IMPORTED に切り替えると IMPORTED を返す
        repository.setChunkSource(mediaId, ChunkOrigin.IMPORTED)
        val activeImported = repository.observeChunks(mediaId).first()
        assertEquals(1, activeImported.size)
        assertEquals(ChunkOrigin.IMPORTED, activeImported.first().origin)

        // MANUAL に切り替えても MANUAL チャンクが未作成なら IMPORTED にフォールバックする
        repository.setChunkSource(mediaId, ChunkOrigin.MANUAL)
        val fallback = repository.observeChunks(mediaId).first()
        assertEquals(ChunkOrigin.IMPORTED, fallback.first().origin)

        val availableOrigins = repository.observeAvailableChunkOrigins(mediaId).first()
        assertEquals(setOf(ChunkOrigin.AUTO, ChunkOrigin.IMPORTED), availableOrigins)
    }

    @Test
    fun `observeTranscripts_AUTOチャンク選択時にIMPORTED文字起こしを最大時間重なりで対応付けオフセットを反映する`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_03")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Sample",
                durationMs = 5_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1024L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )

        val autoChunk0 = ChunkEntity(
            id = 10L,
            mediaId = mediaId.value,
            startMs = 0L,
            endMs = 2_000L,
            origin = ChunkOrigin.AUTO.name,
        )
        val autoChunk1 = ChunkEntity(
            id = 11L,
            mediaId = mediaId.value,
            startMs = 2_000L,
            endMs = 4_000L,
            origin = ChunkOrigin.AUTO.name,
        )
        val importedChunk0 = ChunkEntity(
            id = 20L,
            mediaId = mediaId.value,
            startMs = 100L,
            endMs = 1_800L,
            origin = ChunkOrigin.IMPORTED.name,
        )
        chunkDao.insertAll(listOf(autoChunk0, autoChunk1, importedChunk0))

        val importedTranscript = Transcript(
            chunkId = ChunkId(20L),
            text = "Hello world",
            language = LanguageTag("en"),
            languageConfidence = 0.92f,
            words = listOf(
                WordTiming(startMs = 100L, endMs = 600L, text = "Hello", confidence = 0.9f),
                WordTiming(startMs = 700L, endMs = 1_200L, text = "world", confidence = 0.95f),
            ),
            engine = EngineId("whisper-large-v3-turbo"),
            userEdited = false,
        )
        transcriptDao.upsert(importedTranscript.toEntity())

        repository.setChunkSource(mediaId, ChunkOrigin.AUTO)
        val mapped = repository.observeTranscripts(mediaId).first()
        val mappedForAuto0 = mapped[ChunkId(10L)]
        assertNotNull(mappedForAuto0)
        assertEquals("Hello world", mappedForAuto0.text)
        assertEquals(100L, mappedForAuto0.words.first().startMs)

        // オフセット +2,000ms を設定すると imp_0 (2,100..3,800ms) は auto_1 (2,000..4,000ms) に重なる
        repository.setTranscriptOffset(mediaId, 2_000)
        val shiftedMapped = repository.observeTranscripts(mediaId).first()
        val mappedForAuto1 = shiftedMapped[ChunkId(11L)]
        assertNotNull(mappedForAuto1)
        assertEquals(2_100L, mappedForAuto1.words.first().startMs)
        assertEquals(2_600L, mappedForAuto1.words.first().endMs)
    }

    @Test
    fun `resegment_featuresBinから再デコードなしでAUTOチャンクを再計算し保存する`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_04")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Resegment Test",
                durationMs = 3_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 2048L,
                sha256 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )
        itemStateDao.upsert(
            ItemStateEntity(
                mediaId = mediaId.value,
                positionMs = 0L,
                speed = 1.0f,
                chunkSource = ChunkOrigin.AUTO.name,
                updatedAt = currentEpochMs,
            ),
        )

        // 0..1000ms 発話 (-15dB), 1000..1600ms 無音 (-65dB), 1600..3000ms 発話 (-15dB)
        val logEnergyDb = FloatArray(150) { idx ->
            val timeMs = idx * 20
            if (timeMs in 1_000 until 1_600) {
                -65f
            } else {
                -15f
            }
        }
        val featuresFile = File(File(File(context.filesDir, "analysis"), mediaId.value), "features.bin")
        assertTrue(
            AnalysisBinaryFormat.writeFeaturesAtomic(
                file = featuresFile,
                features = StoredFeatures(binMs = 20, logEnergyDb = logEnergyDb),
            ),
        )

        // minSilenceMs = 400ms なら 600ms の無音で 2 チャンクに分割される
        repository.resegment(
            id = mediaId,
            params = SegmentationParams(
                minSilenceMs = 400,
                minSpeechMs = 250,
                mergeGapMs = 400,
                maxChunkMs = 10_000,
            ),
        )
        val twoChunks = repository.observeChunks(mediaId).first()
        assertEquals(2, twoChunks.size)

        // minSilenceMs = 800ms に変更して再分割すると 1 チャンクになる
        repository.resegment(
            id = mediaId,
            params = SegmentationParams(
                minSilenceMs = 800,
                minSpeechMs = 250,
                mergeGapMs = 800,
                maxChunkMs = 10_000,
            ),
        )
        val oneChunk = repository.observeChunks(mediaId).first()
        assertEquals(1, oneChunk.size)

        val status = repository.observeStatus(mediaId).first()[AnalysisStage.SEGMENTATION]
        assertTrue(status is StageStatus.Done)
    }

    @Test
    fun `updateChunkLanguageとbulkSetUnknownTranscriptLanguage_言語を更新しchunkflowJsonへ書き戻す`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_lang_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Language Test",
                durationMs = 4_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1024L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )

        val c1 = ChunkEntity(id = 101L, mediaId = mediaId.value, startMs = 0L, endMs = 1_500L, origin = ChunkOrigin.IMPORTED.name)
        val c2 = ChunkEntity(id = 102L, mediaId = mediaId.value, startMs = 1_600L, endMs = 3_200L, origin = ChunkOrigin.IMPORTED.name)
        chunkDao.insertAll(listOf(c1, c2))

        transcriptDao.upsertAll(
            listOf(
                Transcript(
                    chunkId = ChunkId(101L),
                    text = "Good morning",
                    language = LanguageTag("en"),
                    languageConfidence = 0.8f,
                    engine = EngineId("whisper"),
                    userEdited = false,
                ).toEntity(),
                Transcript(
                    chunkId = ChunkId(102L),
                    text = "おはようございます",
                    language = null,
                    languageConfidence = null,
                    engine = EngineId("whisper"),
                    userEdited = false,
                ).toEntity(),
            ),
        )

        // 1. 単一チャンクの言語変更
        repository.updateChunkLanguage(mediaId, ChunkId(101L), LanguageTag("ja"))
        val updated101 = transcriptDao.getByChunkId(101L)
        assertNotNull(updated101)
        assertEquals("ja", updated101.language)
        assertEquals(true, updated101.userEdited)

        // 2. 不明言語の一括設定
        repository.bulkSetUnknownTranscriptLanguage(mediaId, LanguageTag("en"))
        val updated102 = transcriptDao.getByChunkId(102L)
        assertNotNull(updated102)
        assertEquals("en", updated102.language)
        assertEquals(true, updated102.userEdited)

        // 3. filesDir/media/<MediaId>.transcript.chunkflow.json に有効な JSON が書き戻されていることを検証
        val backupFile = File(File(context.filesDir, "media"), "${mediaId.value}.transcript.chunkflow.json")
        assertTrue(backupFile.isFile)
        val parseResult = ChunkFlowTranscriptParser().parse(backupFile.readBytes(), backupFile.name)
        assertTrue(parseResult is TranscriptParseResult.Ok)
        assertEquals(2, parseResult.document.segments.size)
        assertEquals(LanguageTag("ja"), parseResult.document.segments[0].language)
        assertEquals(LanguageTag("en"), parseResult.document.segments[1].language)
    }

    @Test
    fun `autoAlignTranscriptOffset_featuresBinとチャンク開始時刻から最適オフセットを推定して更新する`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_align_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Align Test",
                durationMs = 5_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1024L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )

        // 文字起こしセグメントは 1,000ms と 3,000ms 開始だが、実音声の立ち上がりは +150ms 後ろ（1,150ms と 3,150ms）
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 201L, mediaId = mediaId.value, startMs = 1_000L, endMs = 2_000L, origin = ChunkOrigin.IMPORTED.name),
                ChunkEntity(id = 202L, mediaId = mediaId.value, startMs = 3_000L, endMs = 4_000L, origin = ChunkOrigin.IMPORTED.name),
            ),
        )

        // 10ms ホップで 500 フレーム (5,000ms)
        val energyDb = FloatArray(500) { frame ->
            val ms = frame * 10
            if (ms in 1_150 until 2_150 || ms in 3_150 until 4_150) {
                -12f
            } else {
                -70f
            }
        }
        val featuresFile = File(File(File(context.filesDir, "analysis"), mediaId.value), "features.bin")
        assertTrue(
            AnalysisBinaryFormat.writeFeaturesAtomic(
                file = featuresFile,
                features = StoredFeatures(binMs = 10, logEnergyDb = energyDb),
            ),
        )

        val alignedOffset = repository.autoAlignTranscriptOffset(mediaId)
        assertNotNull(alignedOffset)
        assertEquals(150, alignedOffset)
        assertEquals(150, mediaItemDao.getById(mediaId.value)?.transcriptOffsetMs)
    }

    @Test
    fun `triggerLookaheadAt_現在位置から最大6チャンクを先読み認識しステータスと文字起こしを更新する`() = runTest {
        val transcribedRanges = mutableListOf<Pair<Long, Long>>()
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
            ): OnDeviceChunkTranscriptionResult {
                transcribedRanges.add(startMs to endMs)
                return OnDeviceChunkTranscriptionResult(
                    text = "Chunk $startMs-$endMs",
                    language = languagePair?.target ?: LanguageTag("en"),
                    confidence = 0.88f,
                )
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_lookahead_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Lookahead Test",
                durationMs = 10_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1024L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )

        // 音声ダミーファイルを配置
        val audioFile = mediaFileLocator.audioFileFor(mediaId, "mp3")
        audioFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03))

        // 8 個の AUTO チャンク（チャンク間に 200ms の無音ギャップあり）
        val chunks = (0 until 8).map { idx ->
            ChunkEntity(
                id = (300 + idx).toLong(),
                mediaId = mediaId.value,
                startMs = idx * 1_000L,
                endMs = idx * 1_000L + 800L,
                origin = ChunkOrigin.AUTO.name,
            )
        }
        chunkDao.insertAll(chunks)

        repository.setLookaheadEnabled(mediaId, true)
        // インデックス 1 からトリガーすると 1..6 の 6 チャンクのみが対象になり、チャンク間の無音（800..1000ms 等）は渡されない
        repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(301L))

        assertEquals(6, transcribedRanges.size)
        assertEquals(1_000L to 1_800L, transcribedRanges.first())
        assertEquals(6_000L to 6_800L, transcribedRanges.last())

        val status = repository.observeLookaheadStatus(mediaId).first()
        assertTrue(status.enabled)
        assertTrue(status.engineAvailable)
        assertNull(status.inFlightChunkId)
        assertEquals(6, status.completedCount)
        assertEquals(6, status.totalTargetCount)

        val transcripts = repository.observeTranscripts(mediaId).first()
        assertEquals("Chunk 1000-1800", transcripts[ChunkId(301L)]?.text)
        assertEquals("Chunk 6000-6800", transcripts[ChunkId(306L)]?.text)
        assertNull(transcripts[ChunkId(307L)])
    }

    @Test
    fun `toggleChunkBookmark_チャンク内のブックマークをトグルし再分割後も時刻を保持する`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_bookmark_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Bookmark Test",
                durationMs = 3_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1024L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )
        chunkDao.insertAll(
            listOf(
                ChunkEntity(
                    id = 401L,
                    mediaId = mediaId.value,
                    startMs = 0L,
                    endMs = 1_000L,
                    origin = ChunkOrigin.AUTO.name,
                ),
                ChunkEntity(
                    id = 402L,
                    mediaId = mediaId.value,
                    startMs = 1_600L,
                    endMs = 3_000L,
                    origin = ChunkOrigin.AUTO.name,
                ),
            ),
        )

        // 1回目トグル: 追加され true を返す（positionMs = (0 + 1000) / 2 = 500ms）
        val added = repository.toggleChunkBookmark(mediaId, ChunkId(401L), note = "  重要フレーズ  ")
        assertTrue(added)
        val bookmarksAfterAdd = repository.observeBookmarks(mediaId).first()
        assertEquals(1, bookmarksAfterAdd.size)
        assertEquals(500L, bookmarksAfterAdd.first().positionMs)
        assertEquals("重要フレーズ", bookmarksAfterAdd.first().note)

        // AUTO チャンクを再分割してもブックマークの時刻（500ms）は消えずに残る（§6.4、F8.1）
        val logEnergyDb = FloatArray(150) { idx ->
            if (idx * 20 in 1_000 until 1_600) -65f else -15f
        }
        val featuresFile = File(File(File(context.filesDir, "analysis"), mediaId.value), "features.bin")
        assertTrue(
            AnalysisBinaryFormat.writeFeaturesAtomic(
                file = featuresFile,
                features = StoredFeatures(binMs = 20, logEnergyDb = logEnergyDb),
            ),
        )
        repository.resegment(mediaId, SegmentationParams(minSilenceMs = 800, mergeGapMs = 800))
        val resegmentedChunks = repository.observeChunks(mediaId).first()
        assertEquals(1, resegmentedChunks.size)

        val bookmarksAfterResegment = repository.observeBookmarks(mediaId).first()
        assertEquals(1, bookmarksAfterResegment.size)
        assertEquals(500L, bookmarksAfterResegment.first().positionMs)

        // 2回目トグル: 同じ区間を含む新しいチャンクでトグルすると削除され false を返す
        val removed = repository.toggleChunkBookmark(mediaId, resegmentedChunks.first().id)
        assertFalse(removed)
        assertTrue(repository.observeBookmarks(mediaId).first().isEmpty())
    }

    @Test
    fun `updateChunkText_NFC正規化と2000文字上限を適用しuserEditedとchunkflowJsonを更新する`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_text_edit_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Text Edit Test",
                durationMs = 3_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1024L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "ja",
                baseLang = "en",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )
        chunkDao.insertAll(
            listOf(
                ChunkEntity(
                    id = 501L,
                    mediaId = mediaId.value,
                    startMs = 0L,
                    endMs = 2_000L,
                    origin = ChunkOrigin.AUTO.name,
                ),
            ),
        )

        // NFD の「が」（か + 濁点 U+3099）を含む文字列と 2,000 文字超の入力
        val nfdGa = "か\u3099"
        val longSuffix = "あ".repeat(2_010)
        repository.updateChunkText(mediaId, ChunkId(501L), "  $nfdGa$longSuffix  ")

        val saved = transcriptDao.getByChunkId(501L)
        assertNotNull(saved)
        assertTrue(saved.userEdited)
        assertEquals("user", saved.engine)
        assertTrue(saved.text.startsWith("が"))
        assertEquals(2_000, saved.text.codePointCount(0, saved.text.length))

        val backupFile = File(File(context.filesDir, "media"), "${mediaId.value}.transcript.chunkflow.json")
        assertTrue(backupFile.isFile)
        val parsed = ChunkFlowTranscriptParser().parse(backupFile.readBytes(), backupFile.name)
        assertTrue(parsed is TranscriptParseResult.Ok)
        assertEquals(1, parsed.document.segments.size)
        assertEquals(saved.text, parsed.document.segments.first().text)
    }

    @Test
    fun `splitChunk_wordsありとwordsなしの両方でMANUALチャンクへ分割し1トランザクション保存する`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_split_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Split Test",
                durationMs = 4_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1024L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )
        chunkDao.insertAll(
            listOf(
                ChunkEntity(
                    id = 601L,
                    mediaId = mediaId.value,
                    startMs = 0L,
                    endMs = 2_000L,
                    origin = ChunkOrigin.IMPORTED.name,
                ),
                ChunkEntity(
                    id = 602L,
                    mediaId = mediaId.value,
                    startMs = 2_000L,
                    endMs = 4_000L,
                    origin = ChunkOrigin.IMPORTED.name,
                ),
            ),
        )
        transcriptDao.upsertAll(
            listOf(
                Transcript(
                    chunkId = ChunkId(601L),
                    text = "Hello brave world",
                    language = LanguageTag("en"),
                    languageConfidence = 0.9f,
                    words = listOf(
                        WordTiming(startMs = 100L, endMs = 500L, text = "Hello", confidence = 0.9f),
                        WordTiming(startMs = 600L, endMs = 1_100L, text = "brave", confidence = 0.9f),
                        WordTiming(startMs = 1_200L, endMs = 1_800L, text = "world", confidence = 0.9f),
                    ),
                    engine = EngineId("whisper"),
                    userEdited = false,
                ).toEntity(),
                Transcript(
                    chunkId = ChunkId(602L),
                    text = "No word timings here",
                    language = LanguageTag("en"),
                    languageConfidence = 0.9f,
                    words = emptyList(),
                    engine = EngineId("whisper"),
                    userEdited = false,
                ).toEntity(),
            ),
        )

        // 範囲外の分割位置は false
        assertFalse(repository.splitChunk(mediaId, ChunkId(601L), 0L))
        assertFalse(repository.splitChunk(mediaId, ChunkId(601L), 2_000L))

        // 1. words ありのチャンク (601L: 0..2000ms) を 1,150ms で分割
        assertTrue(repository.splitChunk(mediaId, ChunkId(601L), 1_150L))
        val chunksAfterFirstSplit = repository.observeChunks(mediaId).first()
        assertEquals(3, chunksAfterFirstSplit.size)
        assertTrue(chunksAfterFirstSplit.all { it.origin == ChunkOrigin.MANUAL })
        assertEquals(0L to 1_150L, chunksAfterFirstSplit[0].startMs to chunksAfterFirstSplit[0].endMs)
        assertEquals(1_150L to 2_000L, chunksAfterFirstSplit[1].startMs to chunksAfterFirstSplit[1].endMs)

        val transcriptsAfterFirstSplit = repository.observeTranscripts(mediaId).first()
        val firstHalfTr = transcriptsAfterFirstSplit[chunksAfterFirstSplit[0].id]
        val secondHalfTr = transcriptsAfterFirstSplit[chunksAfterFirstSplit[1].id]
        assertNotNull(firstHalfTr)
        assertNotNull(secondHalfTr)
        assertEquals("Hello brave", firstHalfTr.text)
        assertEquals(2, firstHalfTr.words.size)
        assertTrue(firstHalfTr.userEdited)
        assertEquals("world", secondHalfTr.text)
        assertEquals(1, secondHalfTr.words.size)
        assertTrue(secondHalfTr.userEdited)

        // 2. words なしのチャンク (chunksAfterFirstSplit[2]: 2000..4000ms) を 3,000ms で分割
        val thirdChunkId = chunksAfterFirstSplit[2].id
        assertTrue(repository.splitChunk(mediaId, thirdChunkId, 3_000L))
        val chunksAfterSecondSplit = repository.observeChunks(mediaId).first()
        assertEquals(4, chunksAfterSecondSplit.size)
        val transcriptsAfterSecondSplit = repository.observeTranscripts(mediaId).first()
        val noWordsFirstHalf = transcriptsAfterSecondSplit[chunksAfterSecondSplit[2].id]
        val noWordsSecondHalf = transcriptsAfterSecondSplit[chunksAfterSecondSplit[3].id]
        assertNotNull(noWordsFirstHalf)
        assertEquals("No word timings here", noWordsFirstHalf.text)
        assertTrue(noWordsFirstHalf.userEdited)
        assertNull(noWordsSecondHalf)
    }

    @Test
    fun `mergeWithNextChunkとmoveChunkBoundary_MANUALチャンクを生成し文字起こしと境界を更新する`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_merge_move_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Merge Move Test",
                durationMs = 4_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1024L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )
        chunkDao.insertAll(
            listOf(
                ChunkEntity(
                    id = 701L,
                    mediaId = mediaId.value,
                    startMs = 0L,
                    endMs = 1_000L,
                    origin = ChunkOrigin.AUTO.name,
                ),
                ChunkEntity(
                    id = 702L,
                    mediaId = mediaId.value,
                    startMs = 1_000L,
                    endMs = 2_000L,
                    origin = ChunkOrigin.AUTO.name,
                ),
                ChunkEntity(
                    id = 703L,
                    mediaId = mediaId.value,
                    startMs = 2_200L,
                    endMs = 3_500L,
                    origin = ChunkOrigin.AUTO.name,
                ),
            ),
        )
        transcriptDao.upsertAll(
            listOf(
                Transcript(
                    chunkId = ChunkId(701L),
                    text = "Part one",
                    language = LanguageTag("en"),
                    languageConfidence = 0.9f,
                    words = listOf(WordTiming(startMs = 100L, endMs = 800L, text = "Part", confidence = 0.9f)),
                    engine = EngineId("whisper"),
                    userEdited = false,
                ).toEntity(),
                Transcript(
                    chunkId = ChunkId(702L),
                    text = "Part two",
                    language = LanguageTag("en"),
                    languageConfidence = 0.9f,
                    words = listOf(WordTiming(startMs = 1_100L, endMs = 1_800L, text = "two", confidence = 0.9f)),
                    engine = EngineId("whisper"),
                    userEdited = false,
                ).toEntity(),
            ),
        )

        // 末尾チャンクは直後のチャンクがないため mergeWithNextChunk は false
        assertFalse(repository.mergeWithNextChunk(mediaId, ChunkId(703L)))

        // 701L と 702L を結合 -> [0..2000ms] と [2200..3500ms] の 2 チャンク（MANUAL）になる
        assertTrue(repository.mergeWithNextChunk(mediaId, ChunkId(701L)))
        val mergedChunks = repository.observeChunks(mediaId).first()
        assertEquals(2, mergedChunks.size)
        assertEquals(ChunkOrigin.MANUAL, mergedChunks[0].origin)
        assertEquals(0L to 2_000L, mergedChunks[0].startMs to mergedChunks[0].endMs)

        val mergedTranscript = repository.observeTranscripts(mediaId).first()[mergedChunks[0].id]
        assertNotNull(mergedTranscript)
        assertEquals("Part one Part two", mergedTranscript.text)
        assertEquals(2, mergedTranscript.words.size)
        assertTrue(mergedTranscript.userEdited)

        // 2つ目のチャンク (2200..3500ms) の開始を 1,500ms（前のチャンク 0..2000ms に食い込む値）へ移動すると 2,000ms にクランプされる
        val secondChunkId = mergedChunks[1].id
        assertTrue(repository.moveChunkBoundary(mediaId, secondChunkId, newStartMs = 1_500L, newEndMs = 3_800L))
        val movedChunks = repository.observeChunks(mediaId).first()
        assertEquals(2, movedChunks.size)
        assertEquals(2_000L to 3_800L, movedChunks[1].startMs to movedChunks[1].endMs)

        val availableOrigins = repository.observeAvailableChunkOrigins(mediaId).first()
        assertTrue(ChunkOrigin.MANUAL in availableOrigins)
    }

    @Test
    fun `requestAnalysis_複数ファイルを一括要求したとき同時に走らず1件ずつ順番に直列実行される`() = runTest {
        val mediaIds = listOf(
            MediaId("media_seq_1"),
            MediaId("media_seq_2"),
            MediaId("media_seq_3"),
        )
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        for (id in mediaIds) {
            File(mediaDir, "${id.value}.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
            mediaItemDao.upsert(
                MediaItemEntity(
                    id = id.value,
                    title = "Sequential ${id.value}",
                    durationMs = 1_000L,
                    mimeType = "audio/mpeg",
                    sizeBytes = 4L,
                    sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    course = null,
                    targetLang = "en",
                    baseLang = "ja",
                    transcriptOffsetMs = 0,
                    importedAt = currentEpochMs,
                ),
            )
        }

        var activeDecodes = 0
        var maxActiveDecodes = 0
        val executionLog = mutableListOf<String>()
        val statusDuringFirstDecode = mutableMapOf<MediaId, Map<AnalysisStage, StageStatus>>()

        lateinit var repository: DefaultAnalysisRepository
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                val idName = file.nameWithoutExtension
                activeDecodes += 1
                if (activeDecodes > maxActiveDecodes) {
                    maxActiveDecodes = activeDecodes
                }
                executionLog += "start:$idName"

                delay(50L)
                if (idName == "media_seq_1") {
                    for (id in mediaIds) {
                        statusDuringFirstDecode[id] = repository.observeStatus(id).first()
                    }
                }

                val totalSamples = outputSampleRate
                val samples = FloatArray(totalSamples) { i ->
                    val timeSec = i.toDouble() / outputSampleRate
                    (0.4 * sin(2.0 * PI * 440.0 * timeSec)).toFloat()
                }
                sink.onSamples(samples, samples.size)
                onProgress(
                    DecodeProgress(
                        decodedDurationMs = 1_000L,
                        totalDurationMs = 1_000L,
                        fraction = 1.0f,
                    ),
                )

                executionLog += "end:$idName"
                activeDecodes -= 1
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = totalSamples.toLong(),
                    resampledSamples = totalSamples.toLong(),
                    durationMs = 1_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/mpeg",
                )
            }
        }

        repository = DefaultAnalysisRepository(
            context = context,
            analysisStateDao = analysisStateDao,
            chunkDao = chunkDao,
            transcriptDao = transcriptDao,
            itemStateDao = itemStateDao,
            mediaItemDao = mediaItemDao,
            mediaFileResolver = mediaFileLocator,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            bookmarkDao = bookmarkDao,
            clock = { currentEpochMs },
            audioStreamDecoder = fakeDecoder,
        )

        val stages = setOf(AnalysisStage.FEATURES, AnalysisStage.SEGMENTATION)
        val jobs = mediaIds.map { id ->
            launch(UnconfinedTestDispatcher(testScheduler)) {
                repository.requestAnalysis(id, stages)
            }
        }
        for (job in jobs) {
            job.join()
        }

        assertEquals(1, maxActiveDecodes)
        assertEquals(
            listOf(
                "start:media_seq_1",
                "end:media_seq_1",
                "start:media_seq_2",
                "end:media_seq_2",
                "start:media_seq_3",
                "end:media_seq_3",
            ),
            executionLog,
        )

        // 1件目のデコード中は2件目・3件目が NotStarted（待機中）であること
        assertTrue(statusDuringFirstDecode[MediaId("media_seq_1")]?.get(AnalysisStage.FEATURES) is StageStatus.Running)
        assertEquals(
            StageStatus.NotStarted,
            statusDuringFirstDecode[MediaId("media_seq_2")]?.get(AnalysisStage.FEATURES),
        )
        assertEquals(
            StageStatus.NotStarted,
            statusDuringFirstDecode[MediaId("media_seq_3")]?.get(AnalysisStage.FEATURES),
        )

        for (id in mediaIds) {
            val finalStatus = repository.observeStatus(id).first()
            assertEquals(StageStatus.Done, finalStatus[AnalysisStage.FEATURES])
            assertEquals(StageStatus.Done, finalStatus[AnalysisStage.SEGMENTATION])
            assertNotNull(repository.observeWaveform(id).first())
            assertTrue(repository.observeChunks(id).first().isNotEmpty())
        }
    }

    @Test
    fun `triggerLookaheadAt_複数回同時に呼ばれてもlookaheadMutexにより直列化され後続未認識チャンクを継続処理する`() = runTest {
        var activeRecognitions = 0
        var maxActiveRecognitions = 0
        val transcribedRanges = mutableListOf<Pair<Long, Long>>()

        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
            ): OnDeviceChunkTranscriptionResult {
                activeRecognitions += 1
                if (activeRecognitions > maxActiveRecognitions) {
                    maxActiveRecognitions = activeRecognitions
                }
                delay(30L)
                transcribedRanges.add(startMs to endMs)
                activeRecognitions -= 1
                return OnDeviceChunkTranscriptionResult(
                    text = "Text $startMs-$endMs",
                    language = languagePair?.target ?: LanguageTag("ja"),
                    confidence = 0.9f,
                )
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_lookahead_serial_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Lookahead Serial Test",
                durationMs = 10_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1024L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "ja",
                baseLang = "en",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )

        val audioFile = mediaFileLocator.audioFileFor(mediaId, "mp3")
        audioFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03))

        // 8 個の AUTO チャンク (0..7)
        val chunks = (0 until 8).map { idx ->
            ChunkEntity(
                id = (800 + idx).toLong(),
                mediaId = mediaId.value,
                startMs = idx * 1_000L,
                endMs = idx * 1_000L + 800L,
                origin = ChunkOrigin.AUTO.name,
            )
        }
        chunkDao.insertAll(chunks)

        repository.setLookaheadEnabled(mediaId, true)

        // 2 つのコルーチンから同時に triggerLookaheadAt(chunkId = 800L) を呼び出す
        val job1 = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(800L))
        }
        val job2 = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(800L))
        }
        job1.join()
        job2.join()

        // 同時に 2 個以上の認識が走らないこと (ERROR_RECOGNIZER_BUSY 防止)
        assertEquals(1, maxActiveRecognitions)
        // 1回目のバッチで 0..5 (6個) が文字起こしされ、2回目の呼び出しで残りの未文字起こし 6..7 (2個) が重複なく継続処理される
        assertEquals(8, transcribedRanges.size)
        assertEquals(8, transcribedRanges.distinct().size)
    }

    @Test
    fun `triggerLookaheadAt_冒頭チャンクが認識失敗nullでもスキップして7チャンク目以降へ順次進む`() = runTest {
        val attemptedStartMsList = mutableListOf<Long>()
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
            ): OnDeviceChunkTranscriptionResult? {
                attemptedStartMsList += startMs
                // 先頭チャンク (0ms) と 2番目チャンク (1,000ms) はチャイム・無音想定で null を返す
                if (startMs == 0L || startMs == 1_000L) {
                    return null
                }
                return OnDeviceChunkTranscriptionResult(
                    text = "Recognized $startMs",
                    language = LanguageTag("ko"),
                    confidence = 0.9f,
                )
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_lookahead_skip_empty_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Skip Empty Test",
                durationMs = 10_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1024L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "ko",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )

        val audioFile = mediaFileLocator.audioFileFor(mediaId, "mp3")
        audioFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03))

        // 8 個の AUTO チャンク (900L..907L, startMs = 0..7000ms)
        val chunks = (0 until 8).map { idx ->
            ChunkEntity(
                id = (900 + idx).toLong(),
                mediaId = mediaId.value,
                startMs = idx * 1_000L,
                endMs = idx * 1_000L + 800L,
                origin = ChunkOrigin.AUTO.name,
            )
        }
        chunkDao.insertAll(chunks)

        repository.setLookaheadEnabled(mediaId, true)

        // 1回目: チャンク 0..5 (0ms..5000ms) の 6 件を試行 (0ms と 1000ms は null)
        repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(900L))
        assertEquals(listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L), attemptedStartMsList)

        // 2回目: 同じ先頭チャンク (900L) からトリガーされても、null だった 0ms/1000ms で止まらず後続の 6000ms, 7000ms へ進む
        repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(900L))
        assertEquals(
            listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L),
            attemptedStartMsList,
        )

        val transcripts = repository.observeTranscripts(mediaId).first()
        assertNull(transcripts[ChunkId(900L)])
        assertNull(transcripts[ChunkId(901L)])
        assertEquals("Recognized 6000", transcripts[ChunkId(906L)]?.text)
        assertEquals("Recognized 7000", transcripts[ChunkId(907L)]?.text)
    }
}
