package com.miuraken.chunkflow.core.data.analysis

import android.content.Context
import com.miuraken.chunkflow.core.audio.AnalysisBinaryFormat
import com.miuraken.chunkflow.core.audio.CURRENT_ALGORITHM_VERSION
import com.miuraken.chunkflow.core.audio.StoredFeatures
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.toEntity
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
}
