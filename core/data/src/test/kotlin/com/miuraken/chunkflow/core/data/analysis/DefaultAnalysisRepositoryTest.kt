package com.miuraken.chunkflow.core.data.analysis

import android.content.Context
import android.net.Uri
import com.miuraken.chunkflow.core.audio.AnalysisBinaryFormat
import com.miuraken.chunkflow.core.audio.CURRENT_ALGORITHM_VERSION
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.audio.StoredFeatures
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import com.miuraken.chunkflow.core.database.entity.toEntity
import com.miuraken.chunkflow.core.media.AudioDecodeResult
import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import com.miuraken.chunkflow.core.media.ChunkTimeRange
import com.miuraken.chunkflow.core.media.DecodeProgress
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriber
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriptionResult
import com.miuraken.chunkflow.core.media.OnDeviceTextTranslator
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.OnDeviceRecognitionModelState
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    fun `triggerLookaheadAt_現在位置から後ろ続けて先頭からの順で残りのすべてのチャンクを先読み認識しステータスと文字起こしを更新する`() = runTest {
        val transcribedRanges = mutableListOf<Pair<Long, Long>>()
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? {
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
        // インデックス 1 からトリガーすると、1..7 のあとに先頭の 0 へ戻って残りのすべてのチャンクを認識し、
        // チャンク間の無音（800..1000ms 等）は渡されない
        repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(301L))

        assertEquals(8, transcribedRanges.size)
        assertEquals(1_000L to 1_800L, transcribedRanges.first())
        assertEquals(7_000L to 7_800L, transcribedRanges[6])
        assertEquals(0L to 800L, transcribedRanges.last())

        val status = repository.observeLookaheadStatus(mediaId).first()
        assertTrue(status.enabled)
        assertTrue(status.engineAvailable)
        assertNull(status.inFlightChunkId)
        assertEquals(8, status.completedCount)
        assertEquals(8, status.totalTargetCount)

        val transcripts = repository.observeTranscripts(mediaId).first()
        assertEquals("Chunk 1000-1800", transcripts[ChunkId(301L)]?.text)
        assertEquals("Chunk 7000-7800", transcripts[ChunkId(307L)]?.text)
        assertEquals("Chunk 0-800", transcripts[ChunkId(300L)]?.text)
    }

    @Test
    fun `triggerLookaheadAt_音声認識モデルがなく言語判定のみの結果は本文なしで言語だけ保存しモデル導入後は両言語で認識し直す`() = runTest {
        val requestedPairs = mutableListOf<LanguagePair?>()
        var modelInstalled = false
        val modelIssues = MutableStateFlow<Map<LanguageTag, OnDeviceRecognitionModelState>>(
            mapOf(LanguageTag("ko") to OnDeviceRecognitionModelState.DOWNLOADING),
        )
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override val recognitionModelIssues: StateFlow<Map<LanguageTag, OnDeviceRecognitionModelState>> =
                modelIssues

            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? {
                requestedPairs += languagePair
                return if (modelInstalled) {
                    OnDeviceChunkTranscriptionResult(text = "안녕하세요", language = LanguageTag("ko"), confidence = 0.9f)
                } else {
                    OnDeviceChunkTranscriptionResult(
                        text = "",
                        language = LanguageTag("ko"),
                        confidence = 0.97f,
                        engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
                    )
                }
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_lid_only_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "LID only",
                durationMs = 5_000L,
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
        mediaFileLocator.audioFileFor(mediaId, "mp3").writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 900L, mediaId = mediaId.value, startMs = 0L, endMs = 900L, origin = ChunkOrigin.AUTO.name),
            ),
        )

        repository.setLookaheadEnabled(mediaId, true)
        repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(900L))

        // 本文なし・言語は韓国語・言語判定のみのエンジンとして保存される（利用者の編集扱いにはしない）
        val stored = transcriptDao.getByChunkId(900L)
        assertNotNull(stored)
        assertEquals("", stored.text)
        assertEquals("ko", stored.language)
        assertEquals(OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID.value, stored.engine)
        assertFalse(stored.userEdited)
        // モデルの問題が先読みの状態に含まれる
        val status = repository.observeLookaheadStatus(mediaId).first()
        assertEquals(
            mapOf(LanguageTag("ko") to OnDeviceRecognitionModelState.DOWNLOADING),
            status.recognitionModelIssues,
        )

        // モデルが入ったあと先読みをやり直すと、言語判定のみの言語に固定せず両言語で判定し直して本文を保存する
        modelInstalled = true
        modelIssues.value = emptyMap()
        repository.setLookaheadEnabled(mediaId, true)
        repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(900L))
        assertEquals(LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")), requestedPairs.last())
        assertEquals("안녕하세요", transcriptDao.getByChunkId(900L)?.text)
        assertTrue(repository.observeLookaheadStatus(mediaId).first().recognitionModelIssues.isEmpty())
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
    fun `splitChunk_featuresBinが存在する場合に元チャンク範囲内で無音検出し前後の無音部分をトリミングしてMANUALチャンクを保存する`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_split_trim_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Split Trim Silence Test",
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
        chunkDao.insertAll(
            listOf(
                ChunkEntity(
                    id = 610L,
                    mediaId = mediaId.value,
                    startMs = 200L,
                    endMs = 4_600L,
                    origin = ChunkOrigin.AUTO.name,
                ),
            ),
        )
        transcriptDao.upsert(
            Transcript(
                chunkId = ChunkId(610L),
                text = "First sentence Second sentence",
                language = LanguageTag("en"),
                languageConfidence = 0.9f,
                words = listOf(
                    WordTiming(startMs = 500L, endMs = 1_500L, text = "First sentence", confidence = 0.9f),
                    WordTiming(startMs = 2_700L, endMs = 3_900L, text = "Second sentence", confidence = 0.9f),
                ),
                engine = EngineId("whisper"),
                userEdited = false,
            ).toEntity(),
        )

        // 10ms ホップで 500 フレーム (5,000ms)
        // 元チャンク [200..4600ms] のうち:
        // - 前半の発話: [600..1500ms] (frame 60..150)
        // - 中間の無音: [1500..2700ms]（分割点 2100ms）
        // - 後半の発話: [2700..3800ms] (frame 270..380)
        val logEnergyDb = FloatArray(500) { frame ->
            val ms = frame * 10
            if (ms in 600 until 1_500 || ms in 2_700 until 3_800) {
                -12f
            } else {
                -65f
            }
        }
        val featuresFile = File(File(File(context.filesDir, "analysis"), mediaId.value), "features.bin")
        assertTrue(
            AnalysisBinaryFormat.writeFeaturesAtomic(
                file = featuresFile,
                features = StoredFeatures(binMs = 10, logEnergyDb = logEnergyDb),
            ),
        )

        // 2,100ms（中間の無音区間内）で分割を実行
        // 削った無音に対する最小限パディング（最大 30ms）により:
        // - 前半チャンク: [600 - 30, 1500 + 30] = [570..1530ms]
        // - 後半チャンク: [2700 - 30, 3800 + 30] = [2670..3830ms]
        assertTrue(repository.splitChunk(mediaId, ChunkId(610L), 2_100L))

        val chunksAfterSplit = repository.observeChunks(mediaId).first()
        assertEquals(2, chunksAfterSplit.size)
        assertTrue(chunksAfterSplit.all { it.origin == ChunkOrigin.MANUAL })
        assertEquals(570L to 1_530L, chunksAfterSplit[0].startMs to chunksAfterSplit[0].endMs)
        assertEquals(2_670L to 3_830L, chunksAfterSplit[1].startMs to chunksAfterSplit[1].endMs)

        // 単語タイムスタンプもトリミング後のチャンク区間にクランプされて引き継がれる
        val transcriptsAfterSplit = repository.observeTranscripts(mediaId).first()
        val firstTr = transcriptsAfterSplit[chunksAfterSplit[0].id]
        val secondTr = transcriptsAfterSplit[chunksAfterSplit[1].id]
        assertNotNull(firstTr)
        assertNotNull(secondTr)
        assertEquals("First sentence", firstTr.text)
        assertEquals(1, firstTr.words.size)
        assertTrue(firstTr.words[0].startMs >= 570L && firstTr.words[0].endMs <= 1_530L)
        assertEquals("Second sentence", secondTr.text)
        assertEquals(1, secondTr.words.size)
        assertTrue(secondTr.words[0].startMs >= 2_670L && secondTr.words[0].endMs <= 3_830L)
    }

    @Test
    fun `splitChunk_片側の半区間が無音のみの場合は無音側チャンクが消えてトリミング済み1チャンクになり文字起こしを引き継ぐ`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_split_trim_single_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Split Trim Single Piece Test",
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
                    id = 620L,
                    mediaId = mediaId.value,
                    startMs = 200L,
                    endMs = 3_500L,
                    origin = ChunkOrigin.AUTO.name,
                ),
            ),
        )
        transcriptDao.upsert(
            Transcript(
                chunkId = ChunkId(620L),
                text = "Trimmed speech",
                language = LanguageTag("en"),
                languageConfidence = 0.95f,
                words = listOf(
                    WordTiming(startMs = 1_250L, endMs = 2_900L, text = "Trimmed speech", confidence = 0.95f),
                ),
                engine = EngineId("whisper"),
                userEdited = false,
            ).toEntity(),
        )

        // 前半 [200..1100ms] は無音 (-45dB)、後半 [1200..3000ms] のみ発話 (-12dB)
        val logEnergyDb = FloatArray(400) { frame ->
            val ms = frame * 10
            if (ms in 1_200 until 3_000) -12f else -45f
        }
        val featuresFile = File(File(File(context.filesDir, "analysis"), mediaId.value), "features.bin")
        assertTrue(
            AnalysisBinaryFormat.writeFeaturesAtomic(
                file = featuresFile,
                features = StoredFeatures(binMs = 10, logEnergyDb = logEnergyDb),
            ),
        )

        // 先頭の無音区間内の 1,000ms で分割すると、前半は無音のみのため消去され後半のみの 1 チャンクになる
        assertTrue(repository.splitChunk(mediaId, ChunkId(620L), 1_000L))

        val chunksAfterSplit = repository.observeChunks(mediaId).first()
        assertEquals(1, chunksAfterSplit.size)
        val singleChunk = chunksAfterSplit.first()
        assertEquals(ChunkOrigin.MANUAL, singleChunk.origin)
        assertEquals(1_170L to 3_030L, singleChunk.startMs to singleChunk.endMs)

        val transcriptsAfterSplit = repository.observeTranscripts(mediaId).first()
        val tr = transcriptsAfterSplit[singleChunk.id]
        assertNotNull(tr)
        assertEquals("Trimmed speech", tr.text)
        assertEquals(LanguageTag("en"), tr.language)
        assertTrue(tr.userEdited)
        assertEquals(1, tr.words.size)
        assertEquals(1_250L to 2_900L, tr.words[0].startMs to tr.words[0].endMs)
    }

    @Test
    fun `deleteChunk_指定したチャンクを解除し前後のチャンクの時刻と文字起こしをそのまま保持する`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_delete_chunk_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Delete Chunk Test",
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
        chunkDao.insertAll(
            listOf(
                ChunkEntity(
                    id = 801L,
                    mediaId = mediaId.value,
                    startMs = 100L,
                    endMs = 1_100L,
                    origin = ChunkOrigin.AUTO.name,
                ),
                ChunkEntity(
                    id = 802L,
                    mediaId = mediaId.value,
                    startMs = 1_300L,
                    endMs = 2_300L,
                    origin = ChunkOrigin.AUTO.name,
                ),
                ChunkEntity(
                    id = 803L,
                    mediaId = mediaId.value,
                    startMs = 2_600L,
                    endMs = 3_800L,
                    origin = ChunkOrigin.AUTO.name,
                ),
            ),
        )
        transcriptDao.upsertAll(
            listOf(
                Transcript(
                    chunkId = ChunkId(801L),
                    text = "Keep first",
                    language = LanguageTag("en"),
                    languageConfidence = 0.9f,
                    engine = EngineId("whisper"),
                    userEdited = false,
                ).toEntity(),
                Transcript(
                    chunkId = ChunkId(802L),
                    text = "Noise to delete",
                    language = LanguageTag("en"),
                    languageConfidence = 0.5f,
                    engine = EngineId("whisper"),
                    userEdited = false,
                ).toEntity(),
                Transcript(
                    chunkId = ChunkId(803L),
                    text = "Keep third",
                    language = LanguageTag("ja"),
                    languageConfidence = 0.95f,
                    engine = EngineId("whisper"),
                    userEdited = true,
                ).toEntity(),
            ),
        )

        // 存在しないチャンク ID の指定は false
        assertFalse(repository.deleteChunk(mediaId, ChunkId(9_999L)))

        // 真ん中のチャンク (802L: 1300..2300ms) を解除
        assertTrue(repository.deleteChunk(mediaId, ChunkId(802L)))

        val remainingChunks = repository.observeChunks(mediaId).first()
        assertEquals(2, remainingChunks.size)
        assertTrue(remainingChunks.all { it.origin == ChunkOrigin.MANUAL })
        // 前後のチャンクの時刻は変わらず、解除した [1300..2300ms] はチャンク間無音になる
        assertEquals(100L to 1_100L, remainingChunks[0].startMs to remainingChunks[0].endMs)
        assertEquals(2_600L to 3_800L, remainingChunks[1].startMs to remainingChunks[1].endMs)

        val transcripts = repository.observeTranscripts(mediaId).first()
        assertEquals("Keep first", transcripts[remainingChunks[0].id]?.text)
        assertEquals("Keep third", transcripts[remainingChunks[1].id]?.text)
        assertEquals(LanguageTag("ja"), transcripts[remainingChunks[1].id]?.language)
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
    fun `mergeChunks_非連続チャンクも含めて先頭から末尾までを1つに連結し先頭チャンクの言語設定に合わせる`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_merge_many_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Merge Many Test",
                durationMs = 6_000L,
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
        chunkDao.insertAll(
            (0 until 4).map { idx ->
                ChunkEntity(
                    id = 1_401L + idx,
                    mediaId = mediaId.value,
                    startMs = idx * 1_200L,
                    endMs = idx * 1_200L + 1_000L,
                    origin = ChunkOrigin.AUTO.name,
                )
            },
        )
        val texts = listOf("하나", "둘", "셋", "넷")
        val languages = listOf("ko", "ja", "ko", "ja")
        transcriptDao.upsertAll(
            texts.indices.map { idx ->
                Transcript(
                    chunkId = ChunkId(1_401L + idx),
                    text = texts[idx],
                    language = LanguageTag(languages[idx]),
                    languageConfidence = 0.9f,
                    engine = EngineId("whisper"),
                    userEdited = false,
                ).toEntity()
            },
        )

        // 1 件だけの場合は連結しない
        assertFalse(repository.mergeChunks(mediaId, listOf(ChunkId(1_401L))))
        assertEquals(4, repository.observeChunks(mediaId).first().size)

        // 非連続のチャンク（0番目の 1401 と 2番目の 1403）を指定して連結 ->
        // 間の 1402 も含めて 0..2 が [0..3400ms] に連結され、残りの [3600..4600ms] と合わせて 2 チャンク（MANUAL）になる
        // 言語は先頭チャンク (1401: ko) に合わせられる
        assertTrue(repository.mergeChunks(mediaId, listOf(ChunkId(1_401L), ChunkId(1_403L))))
        val merged = repository.observeChunks(mediaId).first()
        assertEquals(2, merged.size)
        assertEquals(ChunkOrigin.MANUAL, merged[0].origin)
        assertEquals(0L to 3_400L, merged[0].startMs to merged[0].endMs)
        assertEquals(3_600L to 4_600L, merged[1].startMs to merged[1].endMs)

        val transcripts = repository.observeTranscripts(mediaId).first()
        val mergedTranscript = transcripts[merged[0].id]
        assertNotNull(mergedTranscript)
        assertEquals("하나 둘 셋", mergedTranscript.text)
        assertEquals(LanguageTag("ko"), mergedTranscript.language)
        assertTrue(mergedTranscript.userEdited)
        assertEquals("넷", transcripts[merged[1].id]?.text)

        // 先頭チャンクの言語が null で後続チャンクが ja の場合、連結後の言語も先頭に合わせて null になる
        transcriptDao.upsert(
            Transcript(
                chunkId = merged[0].id,
                text = "하나 둘 셋",
                language = null,
                languageConfidence = null,
                engine = EngineId("user"),
                userEdited = true,
            ).toEntity(),
        )
        assertTrue(repository.mergeChunks(mediaId, listOf(merged[0].id, merged[1].id)))
        val mergedAll = repository.observeChunks(mediaId).first()
        assertEquals(1, mergedAll.size)
        val mergedAllTranscript = repository.observeTranscripts(mediaId).first()[mergedAll[0].id]
        assertNotNull(mergedAllTranscript)
        assertEquals("하나 둘 셋 넷", mergedAllTranscript.text)
        assertNull(mergedAllTranscript.language)
        assertNull(mergedAllTranscript.languageConfidence)
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
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? {
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
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
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

        // 1回目: 0ms と 1000ms が null でも止まらず、残りのすべてのチャンク (0ms..7000ms) を順に試行する
        repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(900L))
        val allStarts = listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L, 7_000L)
        assertEquals(allStarts, attemptedStartMsList)

        // 2回目: 同じ先頭チャンク (900L) からトリガーされても、null だった 0ms/1000ms はこのセッションでは試し直さない
        repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(900L))
        assertEquals(allStarts, attemptedStartMsList)

        val transcripts = repository.observeTranscripts(mediaId).first()
        assertNull(transcripts[ChunkId(900L)])
        assertNull(transcripts[ChunkId(901L)])
        assertEquals("Recognized 6000", transcripts[ChunkId(906L)]?.text)
        assertEquals("Recognized 7000", transcripts[ChunkId(907L)]?.text)
    }

    @Test
    fun `updateChunkLanguage_言語切替時に既存文字起こしを即座にクリアしキャッシュを使わず毎回強制再認識する`() = runTest {
        data class CallRecord(
            val languagePair: LanguagePair?,
            val forceRefresh: Boolean,
            val highPriority: Boolean,
        )

        val transcribeCalls = mutableListOf<CallRecord>()
        var callSeq = 0
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
                audioFile = audioFile,
                startMs = startMs,
                endMs = endMs,
                languagePair = languagePair,
                forceRefresh = false,
                highPriority = false,
            )

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                forceRefresh: Boolean,
                highPriority: Boolean,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? {
                callSeq += 1
                transcribeCalls += CallRecord(
                    languagePair = languagePair,
                    forceRefresh = forceRefresh,
                    highPriority = highPriority,
                )
                return when (languagePair?.target?.value) {
                    "ko" -> OnDeviceChunkTranscriptionResult(
                        text = "안녕하세요_$callSeq",
                        language = LanguageTag("ko"),
                        confidence = 0.94f,
                    )

                    "ja" -> OnDeviceChunkTranscriptionResult(
                        text = "こんにちは_$callSeq",
                        language = LanguageTag("ja"),
                        confidence = 0.91f,
                    )

                    else -> null
                }
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_lang_toggle_force_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Lang Toggle Force Test",
                durationMs = 4_000L,
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

        val chunkId = ChunkId(1001L)
        chunkDao.insertAll(
            listOf(
                ChunkEntity(
                    id = chunkId.value,
                    mediaId = mediaId.value,
                    startMs = 0L,
                    endMs = 2_000L,
                    origin = ChunkOrigin.AUTO.name,
                ),
            ),
        )
        // 初期状態: 日本語として認識されていたチャンク
        transcriptDao.upsert(
            Transcript(
                chunkId = chunkId,
                text = "アンニョンハセヨパンガプスミダ",
                language = LanguageTag("ja"),
                languageConfidence = 0.72f,
                engine = EngineId("android-on-device-speech"),
                userEdited = false,
            ).toEntity(),
        )

        // 1. ja -> ko へ切替: forceRefresh = true, highPriority = true で ko 単一言語再認識が呼ばれる
        repository.updateChunkLanguage(mediaId, chunkId, LanguageTag("ko"))
        assertEquals(
            listOf(
                CallRecord(
                    languagePair = LanguagePair(target = LanguageTag("ko"), base = null),
                    forceRefresh = true,
                    highPriority = true,
                ),
            ),
            transcribeCalls,
        )
        val afterKo = repository.observeTranscripts(mediaId).first()[chunkId]
        assertNotNull(afterKo)
        assertEquals("안녕하세요_1", afterKo.text)
        assertEquals(LanguageTag("ko"), afterKo.language)
        assertTrue(afterKo.userEdited)

        // 2. ko -> ja へ戻す: キャッシュを流用せず、文字起こしデータをクリアした上で再度強制的に ja で文字起こしする
        repository.updateChunkLanguage(mediaId, chunkId, LanguageTag("ja"))
        assertEquals(2, transcribeCalls.size)
        assertTrue(transcribeCalls.last().forceRefresh)
        assertTrue(transcribeCalls.last().highPriority)
        val afterBackToJa = repository.observeTranscripts(mediaId).first()[chunkId]
        assertNotNull(afterBackToJa)
        assertEquals("こんにちは_2", afterBackToJa.text)
        assertEquals(LanguageTag("ja"), afterBackToJa.language)

        // 3. ja -> ko へ再度切替: 同様に再度強制的に ko で文字起こしする
        repository.updateChunkLanguage(mediaId, chunkId, LanguageTag("ko"))
        assertEquals(3, transcribeCalls.size)
        val afterSecondKo = repository.observeTranscripts(mediaId).first()[chunkId]
        assertNotNull(afterSecondKo)
        assertEquals("안녕하세요_3", afterSecondKo.text)
        assertEquals(LanguageTag("ko"), afterSecondKo.language)
    }

    @Test
    fun `triggerLookaheadAt_デフォルトONで動作し言語未確定チャンクも先読み認識してキャッシュに保存する`() = runTest {
        var transcribeCallCount = 0
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? {
                transcribeCallCount += 1
                return OnDeviceChunkTranscriptionResult(
                    text = "自動認識テキスト",
                    language = LanguageTag("ja"),
                    confidence = 0.9f,
                )
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_default_on_lookahead_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Default On Lookahead Test",
                durationMs = 3_000L,
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

        val chunkId = ChunkId(1101L)
        chunkDao.insertAll(
            listOf(
                ChunkEntity(
                    id = chunkId.value,
                    mediaId = mediaId.value,
                    startMs = 0L,
                    endMs = 1_500L,
                    origin = ChunkOrigin.AUTO.name,
                ),
            ),
        )
        // 言語未確定 (language = null, userEdited = false) の既存レコード
        transcriptDao.upsert(
            Transcript(
                chunkId = chunkId,
                text = "仮テキスト",
                language = null,
                languageConfidence = null,
                engine = EngineId("whisper"),
                userEdited = false,
            ).toEntity(),
        )

        // setLookaheadEnabled を呼ばなくてもデフォルトで enabled == true
        assertTrue(repository.observeLookaheadStatus(mediaId).first().enabled)

        repository.triggerLookaheadAt(mediaId, currentChunkId = chunkId)
        assertEquals(1, transcribeCallCount)

        val updated = repository.observeTranscripts(mediaId).first()[chunkId]
        assertNotNull(updated)
        assertEquals("自動認識テキスト", updated.text)
        assertEquals(LanguageTag("ja"), updated.language)
    }

    @Test
    fun `retranscribeChunk_既割当言語がある場合はuserEditedにかかわらず単一言語指定で再認識し言語未割当時のみmediaLanguagePairで再認識する`() = runTest {
        data class CallRecord(
            val startMs: Long,
            val endMs: Long,
            val languagePair: LanguagePair?,
            val forceRefresh: Boolean,
            val highPriority: Boolean,
        )

        val calls = mutableListOf<CallRecord>()
        var callCounter = 0
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
                audioFile = audioFile,
                startMs = startMs,
                endMs = endMs,
                languagePair = languagePair,
                forceRefresh = false,
                highPriority = false,
            )

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                forceRefresh: Boolean,
                highPriority: Boolean,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? {
                callCounter += 1
                calls += CallRecord(
                    startMs = startMs,
                    endMs = endMs,
                    languagePair = languagePair,
                    forceRefresh = forceRefresh,
                    highPriority = highPriority,
                )
                val lang = languagePair?.target ?: LanguageTag("ko")
                return OnDeviceChunkTranscriptionResult(
                    text = "再認識_${callCounter}_${lang.value}",
                    language = lang,
                    confidence = 0.95f,
                )
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_retranscribe_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Retranscribe Test",
                durationMs = 4_000L,
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

        val chunkId = ChunkId(1201L)
        chunkDao.insertAll(
            listOf(
                ChunkEntity(
                    id = chunkId.value,
                    mediaId = mediaId.value,
                    startMs = 0L,
                    endMs = 2_000L,
                    origin = ChunkOrigin.AUTO.name,
                ),
            ),
        )

        // 1. まだ文字起こし（言語）が割り当てられていない状態で retranscribeChunk を呼ぶと、
        //    mediaItem の languagePair (target=ko, base=ja) で言語推定＋文字起こしが実行される
        repository.retranscribeChunk(mediaId, chunkId)
        assertEquals(1, calls.size)
        assertEquals(
            CallRecord(
                startMs = 0L,
                endMs = 2_000L,
                languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
                forceRefresh = true,
                highPriority = true,
            ),
            calls.last(),
        )
        val afterUnassignedRetranscribe = repository.observeTranscripts(mediaId).first()[chunkId]
        assertNotNull(afterUnassignedRetranscribe)
        assertEquals("再認識_1_ko", afterUnassignedRetranscribe.text)
        assertEquals(LanguageTag("ko"), afterUnassignedRetranscribe.language)
        assertFalse(afterUnassignedRetranscribe.userEdited)

        // 2. 自動検出済み (userEdited = false, language = "ko") の状態で再度 retranscribeChunk を呼ぶと、
        //    言語推定をやり直さず、既に割り当てられている "ko" 単一言語 (target=ko, base=null) で再認識され、言語 "ko" が維持される
        repository.retranscribeChunk(mediaId, chunkId)
        assertEquals(2, calls.size)
        assertEquals(
            CallRecord(
                startMs = 0L,
                endMs = 2_000L,
                languagePair = LanguagePair(target = LanguageTag("ko"), base = null),
                forceRefresh = true,
                highPriority = true,
            ),
            calls.last(),
        )
        val afterAutoAssignedRetranscribe = repository.observeTranscripts(mediaId).first()[chunkId]
        assertNotNull(afterAutoAssignedRetranscribe)
        assertEquals("再認識_2_ko", afterAutoAssignedRetranscribe.text)
        assertEquals(LanguageTag("ko"), afterAutoAssignedRetranscribe.language)
        assertFalse(afterAutoAssignedRetranscribe.userEdited)

        // 3. ユーザーが手動で ja に言語変更する
        repository.updateChunkLanguage(mediaId, chunkId, LanguageTag("ja"))
        assertEquals(3, calls.size)
        assertEquals("再認識_3_ja", repository.observeTranscripts(mediaId).first()[chunkId]?.text)

        // 4. userEdited == true かつ language == "ja" の状態で retranscribeChunk を呼ぶと、
        //    キャッシュを破棄して指定言語 (target=ja, base=null) で強制再認識される
        repository.retranscribeChunk(mediaId, chunkId)
        assertEquals(4, calls.size)
        assertEquals(
            CallRecord(
                startMs = 0L,
                endMs = 2_000L,
                languagePair = LanguagePair(target = LanguageTag("ja"), base = null),
                forceRefresh = true,
                highPriority = true,
            ),
            calls.last(),
        )
        val afterManualRetranscribe = repository.observeTranscripts(mediaId).first()[chunkId]
        assertNotNull(afterManualRetranscribe)
        assertEquals("再認識_4_ja", afterManualRetranscribe.text)
        assertEquals(LanguageTag("ja"), afterManualRetranscribe.language)
        assertTrue(afterManualRetranscribe.userEdited)

        // 5. ko に切り替えるとキャッシュヒットせず新規認識が走る
        repository.updateChunkLanguage(mediaId, chunkId, LanguageTag("ko"))
        assertEquals(5, calls.size)
        assertEquals("再認識_5_ko", repository.observeTranscripts(mediaId).first()[chunkId]?.text)
    }

    @Test
    fun `triggerLookaheadAt実行中にretranscribeChunkやupdateChunkLanguageやprioritizeChunkTranscriptionが呼ばれたとき実行中チャンクを中断せず次の対象として優先実行される`() = runTest {
        val firstChunkStarted = CompletableDeferred<Unit>()
        val releaseFirstChunk = CompletableDeferred<Unit>()
        val executionLog = mutableListOf<String>()

        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
                audioFile = audioFile,
                startMs = startMs,
                endMs = endMs,
                languagePair = languagePair,
                forceRefresh = false,
                highPriority = false,
            )

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                forceRefresh: Boolean,
                highPriority: Boolean,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? {
                val tag = if (forceRefresh && languagePair?.base == null && languagePair?.target?.value == "ja") {
                    "langToggle:$startMs:${languagePair.target.value}"
                } else if (forceRefresh) {
                    "retranscribe:$startMs"
                } else if (highPriority) {
                    "prioritize:$startMs"
                } else {
                    "lookahead:$startMs"
                }
                executionLog += tag
                if (startMs == 0L && !highPriority) {
                    firstChunkStarted.complete(Unit)
                    releaseFirstChunk.await()
                }
                return OnDeviceChunkTranscriptionResult(
                    text = "Text($tag)",
                    language = languagePair?.target ?: LanguageTag("ko"),
                    confidence = 0.92f,
                )
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_priority_preempt_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Priority Preempt Test",
                durationMs = 8_000L,
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

        // 5 個の AUTO チャンク (1300L..1304L, startMs = 0, 1000, 2000, 3000, 4000)
        val chunks = (0 until 5).map { idx ->
            ChunkEntity(
                id = (1300 + idx).toLong(),
                mediaId = mediaId.value,
                startMs = idx * 1_000L,
                endMs = idx * 1_000L + 800L,
                origin = ChunkOrigin.AUTO.name,
            )
        }
        chunkDao.insertAll(chunks)
        // チャンク 1303L と 1304L には初期テキストを入れておき、待機中にクリアされることも検証する
        transcriptDao.upsertAll(
            listOf(
                Transcript(
                    chunkId = ChunkId(1303L),
                    text = "Old 3000",
                    language = LanguageTag("ko"),
                    languageConfidence = 0.8f,
                    engine = EngineId("whisper"),
                    userEdited = false,
                ).toEntity(),
                Transcript(
                    chunkId = ChunkId(1304L),
                    text = "Old 4000",
                    language = LanguageTag("ko"),
                    languageConfidence = 0.8f,
                    engine = EngineId("whisper"),
                    userEdited = false,
                ).toEntity(),
            ),
        )

        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        // 1. 先読みを開始し、1件目のチャンク (1300L: startMs=0) の認識中に待機させる
        val lookaheadJob = launch(dispatcher) {
            repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(1300L))
        }
        firstChunkStarted.await()

        // 2. 先読み 1件目の実行中に、後方のチャンク 1303L (startMs=3000) の強制再文字起こし、
        //    チャンク 1304L (startMs=4000) の手動言語切替、およびチャンク 1302L (startMs=2000) の選択優先を要求する
        val retranscribeJob = launch(dispatcher) {
            repository.retranscribeChunk(mediaId, ChunkId(1303L))
        }
        val langToggleJob = launch(dispatcher) {
            repository.updateChunkLanguage(mediaId, ChunkId(1304L), LanguageTag("ja"))
        }
        val prioritizeJob = launch(dispatcher) {
            repository.prioritizeChunkTranscription(mediaId, ChunkId(1302L))
        }

        // 実行中チャンク (1300L) がまだ終わっていない段階でも、1303L と 1304L のテキストは即座に空にクリアされ、
        // queuedChunkIds に待機中チャンクとして入っていること
        val midTranscripts = repository.observeTranscripts(mediaId).first()
        assertEquals("", midTranscripts[ChunkId(1303L)]?.text)
        assertEquals("", midTranscripts[ChunkId(1304L)]?.text)
        assertEquals(LanguageTag("ja"), midTranscripts[ChunkId(1304L)]?.language)
        val midStatus = repository.observeLookaheadStatus(mediaId).first()
        assertEquals(ChunkId(1300L), midStatus.inFlightChunkId)
        assertTrue(ChunkId(1303L) in midStatus.queuedChunkIds)
        assertTrue(ChunkId(1304L) in midStatus.queuedChunkIds)
        assertTrue(ChunkId(1302L) in midStatus.queuedChunkIds)

        // 3. 先読み 1件目を完了させると、中断されることなく 1件目が完了した直後に、最優先の要求が
        //    スタックの順（後から来た要求が先）で 1302L, 1304L, 1303L と処理され、その後に残りの 1301L が処理される
        releaseFirstChunk.complete(Unit)
        retranscribeJob.join()
        langToggleJob.join()
        prioritizeJob.join()
        lookaheadJob.join()

        assertEquals(
            listOf(
                "lookahead:0",
                "prioritize:2000",
                "langToggle:4000:ja",
                "retranscribe:3000",
                "lookahead:1000",
            ),
            executionLog,
        )

        val transcripts = repository.observeTranscripts(mediaId).first()
        assertEquals("Text(lookahead:0)", transcripts[ChunkId(1300L)]?.text)
        assertEquals("Text(lookahead:1000)", transcripts[ChunkId(1301L)]?.text)
        assertEquals("Text(prioritize:2000)", transcripts[ChunkId(1302L)]?.text)
        assertEquals("Text(retranscribe:3000)", transcripts[ChunkId(1303L)]?.text)
        assertEquals("Text(langToggle:4000:ja)", transcripts[ChunkId(1304L)]?.text)
        assertEquals(LanguageTag("ja"), transcripts[ChunkId(1304L)]?.language)
    }

    @Test
    fun `setMediaTranscriptionSessionOpen_開いているファイルのみ新規文字起こしを行い画面を閉じても実行中チャンクは最後まで処理して出力する`() = runTest {
        val firstChunkStarted = CompletableDeferred<Unit>()
        val releaseFirstChunk = CompletableDeferred<Unit>()
        val transcribedChunks = mutableListOf<Pair<String, Long>>()

        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? {
                val idName = audioFile.nameWithoutExtension
                transcribedChunks += idName to startMs
                if (idName == "media_open_1" && startMs == 0L) {
                    firstChunkStarted.complete(Unit)
                    releaseFirstChunk.await()
                }
                return OnDeviceChunkTranscriptionResult(
                    text = "Transcribed($idName:$startMs)",
                    language = LanguageTag("en"),
                    confidence = 0.93f,
                )
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val openMediaId = MediaId("media_open_1")
        val closedMediaId = MediaId("media_closed_2")
        for (id in listOf(openMediaId, closedMediaId)) {
            mediaItemDao.upsert(
                MediaItemEntity(
                    id = id.value,
                    title = "Session ${id.value}",
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
            mediaFileLocator.audioFileFor(id, "mp3").writeBytes(byteArrayOf(1, 2, 3))
        }
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 1401L, mediaId = openMediaId.value, startMs = 0L, endMs = 800L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 1402L, mediaId = openMediaId.value, startMs = 1_000L, endMs = 1_800L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 1403L, mediaId = openMediaId.value, startMs = 2_000L, endMs = 2_800L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 1501L, mediaId = closedMediaId.value, startMs = 0L, endMs = 800L, origin = ChunkOrigin.AUTO.name),
            ),
        )

        // 1. openMediaId を開いている状態にする。closedMediaId は開いていないため triggerLookaheadAt しても無視される
        repository.setMediaTranscriptionSessionOpen(openMediaId, true)
        repository.triggerLookaheadAt(closedMediaId, currentChunkId = ChunkId(1501L))
        assertTrue(transcribedChunks.isEmpty())

        // 2. openMediaId の先読みを開始し、チャンク 1401L (0ms) の処理中にファイル画面を閉じる
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val lookaheadJob = launch(dispatcher) {
            repository.triggerLookaheadAt(openMediaId, currentChunkId = ChunkId(1401L))
        }
        firstChunkStarted.await()

        // 画面を閉じると未着手の待機キュー (1402L, 1403L) はクリアされるが、処理中の 1401L は中断されない
        repository.setMediaTranscriptionSessionOpen(openMediaId, false)
        val midStatus = repository.observeLookaheadStatus(openMediaId).first()
        assertEquals(ChunkId(1401L), midStatus.inFlightChunkId)
        assertTrue(midStatus.queuedChunkIds.isEmpty())

        // 3. 処理中だった 1401L が完了すると、画面を閉じた後でも DB と .transcript.chunkflow.json に出力され、後続の 1402L/1403L は実行されない
        releaseFirstChunk.complete(Unit)
        lookaheadJob.join()

        assertEquals(listOf("media_open_1" to 0L), transcribedChunks)
        val savedTranscripts = repository.observeTranscripts(openMediaId).first()
        assertEquals("Transcribed(media_open_1:0)", savedTranscripts[ChunkId(1401L)]?.text)
        assertNull(savedTranscripts[ChunkId(1402L)])
        assertNull(savedTranscripts[ChunkId(1403L)])

        val backupFile = File(File(context.filesDir, "media"), "${openMediaId.value}.transcript.chunkflow.json")
        assertTrue(backupFile.isFile)
    }

    @Test
    fun `prioritizeOpenedMediaAnalysis_開いたファイルの解析がバックグラウンド解析に割り込んで最優先実行され未アクセス上限2件まで補充される`() = runTest {
        val epIds = (1..4).map { idx -> MediaId("course_ep_$idx") }
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        for ((index, id) in epIds.withIndex()) {
            File(mediaDir, "${id.value}.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
            mediaItemDao.upsert(
                MediaItemEntity(
                    id = id.value,
                    title = "ep_${index + 1}",
                    durationMs = 1_000L,
                    mimeType = "audio/mpeg",
                    sizeBytes = 4L,
                    sha256 = "%064x".format(index + 1),
                    course = "PriorityCourse",
                    targetLang = "en",
                    baseLang = "ja",
                    transcriptOffsetMs = 0,
                    importedAt = currentEpochMs,
                ),
            )
            // 初期状態はすべて未アクセス (updatedAt = 0L)
            itemStateDao.upsert(
                ItemStateEntity(
                    mediaId = id.value,
                    positionMs = 0L,
                    speed = 1.0f,
                    chunkSource = ChunkOrigin.AUTO.name,
                    updatedAt = 0L,
                    isListened = false,
                ),
            )
        }

        val ep1FirstAttemptStarted = CompletableDeferred<Unit>()
        val releaseEp1FirstAttempt = CompletableDeferred<Unit>()
        val decodeCompletionOrder = mutableListOf<String>()
        var ep1Attempts = 0

        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                val idName = file.nameWithoutExtension
                if (idName == "course_ep_1" && ep1Attempts == 0) {
                    ep1Attempts += 1
                    ep1FirstAttemptStarted.complete(Unit)
                    releaseEp1FirstAttempt.await()
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
                decodeCompletionOrder += idName
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

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = DefaultAnalysisRepository(
            context = context,
            analysisStateDao = analysisStateDao,
            chunkDao = chunkDao,
            transcriptDao = transcriptDao,
            itemStateDao = itemStateDao,
            mediaItemDao = mediaItemDao,
            mediaFileResolver = mediaFileLocator,
            ioDispatcher = dispatcher,
            bookmarkDao = bookmarkDao,
            clock = { currentEpochMs },
            audioStreamDecoder = fakeDecoder,
        )

        // 1. バックグラウンドで ep_1 の解析が走っている状態を作る
        val bgJob = launch(dispatcher) {
            repository.requestAnalysis(epIds[0], setOf(AnalysisStage.FEATURES, AnalysisStage.SEGMENTATION))
        }
        ep1FirstAttemptStarted.await()

        // 2. ユーザーが未解析の ep_3 を開く -> ep_1 のバックグラウンド解析に割り込んで ep_3 が先に完了し、
        //    その後 ep_1 が再開・完了する（未選択の ep_2 / ep_4 は自動開始しない）
        val openJob = launch(dispatcher) {
            repository.prioritizeOpenedMediaAnalysis(epIds[2])
        }
        releaseEp1FirstAttempt.complete(Unit)
        openJob.join()
        bgJob.join()

        // ep_3 が最優先で最初に完了していること
        assertEquals("course_ep_3", decodeCompletionOrder.first())
        // 開いた ep_3 はアクセス済み (updatedAt > 0) として記録されていること
        assertTrue((itemStateDao.getByMediaId(epIds[2].value)?.updatedAt ?: 0L) > 0L)

        // 開いた ep_3 と実行中だった ep_1 が完了し、未選択の ep_2・ep_4 は自動解析されないこと
        assertEquals(StageStatus.Done, repository.observeStatus(epIds[2]).first()[AnalysisStage.SEGMENTATION])
        assertEquals(StageStatus.Done, repository.observeStatus(epIds[0]).first()[AnalysisStage.SEGMENTATION])
        assertNull(repository.observeStatus(epIds[1]).first()[AnalysisStage.SEGMENTATION])
        assertNull(repository.observeStatus(epIds[3]).first()[AnalysisStage.SEGMENTATION])
    }

    @Test
    fun `requestAnalysis_解析開始直後に低解像度全体波形を即座に公開し30秒区間ごとに高解像度波形へ順次置き換える`() = runTest {
        val mediaId = MediaId("coarse_progressive_01")
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        File(mediaDir, "${mediaId.value}.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Progressive Waveform Test",
                durationMs = 60_000L,
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

        val observedSnapshots = mutableListOf<com.miuraken.chunkflow.core.model.Waveform>()
        lateinit var repository: DefaultAnalysisRepository

        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decodeCoarseOverviewWaveform(
                file: File,
                expectedDurationMs: Long?,
                targetColumns: Int,
                fineBinMs: Int,
            ): com.miuraken.chunkflow.core.model.Waveform = com.miuraken.chunkflow.core.audio.FeatureAccumulator.expandCoarseToFineWaveform(
                coarsePeak = byteArrayOf(40, 80),
                coarseRms = byteArrayOf(20, 40),
                totalDurationMs = 60_000L,
                fineBinMs = fineBinMs,
            )

            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                // 1. 本デコード開始時点ですでに粗い全体波形 (60秒 = 6,000ビン) が保存・公開されていること
                repository.observeWaveform(mediaId).first()?.let { observedSnapshots += it }

                // 2. 前半 30.1秒分 (3,010 ビン > BINS_PER_30_SECONDS) の PCM サンプルを流す
                //    clock (currentEpochMs) は進めない（PROGRESS_SAVE_INTERVAL_MS 未経過でも 30秒条件で保存されることを検証）
                val samples30s = FloatArray(outputSampleRate * 30 + (outputSampleRate / 10)) { 0.75f }
                sink.onSamples(samples30s, samples30s.size)
                onProgress(
                    DecodeProgress(
                        decodedDurationMs = 30_100L,
                        totalDurationMs = 60_000L,
                        fraction = 0.5f,
                    ),
                )
                repository.observeWaveform(mediaId).first()?.let { observedSnapshots += it }

                // 3. 残り 29.9秒分の PCM サンプルを流して完了させる
                val remainingSamples = FloatArray(outputSampleRate * 60 - samples30s.size) { 0.75f }
                sink.onSamples(remainingSamples, remainingSamples.size)
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = (outputSampleRate * 60).toLong(),
                    resampledSamples = (outputSampleRate * 60).toLong(),
                    durationMs = 60_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/mpeg",
                )
            }
        }

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repository = DefaultAnalysisRepository(
            context = context,
            analysisStateDao = analysisStateDao,
            chunkDao = chunkDao,
            transcriptDao = transcriptDao,
            itemStateDao = itemStateDao,
            mediaItemDao = mediaItemDao,
            mediaFileResolver = mediaFileLocator,
            ioDispatcher = dispatcher,
            bookmarkDao = bookmarkDao,
            clock = { currentEpochMs },
            audioStreamDecoder = fakeDecoder,
        )

        repository.requestAnalysis(mediaId, setOf(AnalysisStage.FEATURES))
        repository.observeWaveform(mediaId).first()?.let { observedSnapshots += it }

        assertEquals(3, observedSnapshots.size)

        // スナップショット 0: 粗い全体波形（全体長 60秒 = 6,000ビン、前半 40 / 後半 80）
        val initialCoarse = observedSnapshots[0]
        assertEquals(60_000L, initialCoarse.durationMs)
        assertEquals(40, initialCoarse.peak[0].toInt() and 0xFF)
        assertEquals(80, initialCoarse.peak[5_000].toInt() and 0xFF)

        // スナップショット 1: 30秒区間完了時（全体長 60秒を維持しつつ、前半 3,000 ビンが高解像度データ > 180 に置換され、後半は粗い 80 を維持）
        val midProgressive = observedSnapshots[1]
        assertEquals(60_000L, midProgressive.durationMs)
        assertTrue((midProgressive.peak[100].toInt() and 0xFF) > 180)
        assertEquals(80, midProgressive.peak[5_000].toInt() and 0xFF)

        // スナップショット 2: 全区間デコード完了時（後半も含め全区間が高解像度データ > 180 に置換される）
        val finalFull = observedSnapshots[2]
        assertEquals(60_000L, finalFull.durationMs)
        assertTrue((finalFull.peak[100].toInt() and 0xFF) > 180)
        assertTrue((finalFull.peak[5_000].toInt() and 0xFF) > 180)
    }

    @Test
    fun `setLookaheadEnabledFalseとretranscribeChunk_文字起こしOFFで進行中ジョブを中断し強制文字起こしは先読みOFFでも最優先で実行する`() = runTest {
        var cancelCalls = 0
        var lastOnlyLowPriority: Boolean? = null
        var resetCalls = 0
        val chunk1Started = CompletableDeferred<Unit>()
        val releaseChunk1 = CompletableDeferred<Unit>()

        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override fun cancelActiveTranscription(onlyLowPriority: Boolean) {
                cancelCalls += 1
                lastOnlyLowPriority = onlyLowPriority
                if (!onlyLowPriority) {
                    releaseChunk1.complete(Unit)
                }
            }

            override fun resetTranscriberState() {
                resetCalls += 1
                releaseChunk1.complete(Unit)
            }

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
                audioFile = audioFile,
                startMs = startMs,
                endMs = endMs,
                languagePair = languagePair,
                forceRefresh = false,
                highPriority = false,
            )

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                forceRefresh: Boolean,
                highPriority: Boolean,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? {
                if (!highPriority && startMs == 0L) {
                    chunk1Started.complete(Unit)
                    releaseChunk1.await()
                    return null
                }
                return if (highPriority) {
                    OnDeviceChunkTranscriptionResult(
                        text = "強制文字起こし結果",
                        language = LanguageTag("ko"),
                        confidence = 0.95f,
                    )
                } else {
                    null
                }
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_abort_force_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Abort and Force Test",
                durationMs = 3_000L,
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
        mediaFileLocator.audioFileFor(mediaId, "mp3").writeBytes(byteArrayOf(1, 2, 3))
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 701L, mediaId = mediaId.value, startMs = 0L, endMs = 1_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 702L, mediaId = mediaId.value, startMs = 1_000L, endMs = 2_000L, origin = ChunkOrigin.AUTO.name),
            ),
        )

        repository.setLookaheadEnabled(mediaId, true)
        val lookaheadJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            repository.triggerLookaheadAt(mediaId, ChunkId(701L))
        }
        chunk1Started.await()

        // 1. 先読み中に setLookaheadEnabled(false) を呼ぶと、cancelActiveTranscription(onlyLowPriority = false) が呼ばれ即時中断される
        repository.setLookaheadEnabled(mediaId, false)
        lookaheadJob.join()
        assertTrue(cancelCalls >= 1)
        assertEquals(false, lastOnlyLowPriority)
        val statusAfterDisable = repository.observeLookaheadStatus(mediaId).first()
        assertFalse(statusAfterDisable.enabled)
        assertNull(statusAfterDisable.inFlightChunkId)
        assertTrue(statusAfterDisable.queuedChunkIds.isEmpty())

        // 2. 先読み OFF の状態でも retranscribeChunk を呼ぶと、cancelActiveTranscription(onlyLowPriority = true) で低優先度を退かしつつ即座に実行される
        val prevCancelCalls = cancelCalls
        repository.retranscribeChunk(mediaId, ChunkId(702L))
        assertTrue(cancelCalls > prevCancelCalls)
        assertEquals(true, lastOnlyLowPriority)
        assertEquals("強制文字起こし結果", transcriptDao.getByChunkId(702L)?.text)

        // 3. resetTranscription を呼ぶと resetTranscriberState が呼ばれ状態が初期化される
        repository.resetTranscription(mediaId)
        assertEquals(1, resetCalls)
    }

    @Test
    fun `setMediaTranscriptionSessionOpen_開いている教材がなくなったときだけ切り出し用の資源を解放する`() = runTest {
        var releaseCalls = 0
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<com.miuraken.chunkflow.core.model.LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = null

            override fun releaseIdleResources() {
                releaseCalls += 1
            }
        }
        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val first = MediaId("media_release_01")
        val second = MediaId("media_release_02")

        // 別の教材に切り替えても、開いている教材がある間は解放しない
        repository.setMediaTranscriptionSessionOpen(first, true)
        repository.setMediaTranscriptionSessionOpen(second, true)
        assertEquals(0, releaseCalls)

        // プレイヤー画面を閉じて開いている教材がなくなったら解放する
        repository.setMediaTranscriptionSessionOpen(second, false)
        assertEquals(1, releaseCalls)
    }

    @Test
    fun `requestAnalysis_開いているファイルでも解析が済んでいれば同じ解析をもう一度行わない`() = runTest {
        val mediaId = MediaId("double_run_01")
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        File(mediaDir, "${mediaId.value}.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Double Run Test",
                durationMs = 5_000L,
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
        var decodeCalls = 0
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                decodeCalls++
                val samples = FloatArray(outputSampleRate * 5) { idx -> if ((idx / outputSampleRate) % 2 == 0) 0.5f else 0f }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = samples.size.toLong(),
                    resampledSamples = samples.size.toLong(),
                    durationMs = 5_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/mpeg",
                )
            }
        }
        val repository = DefaultAnalysisRepository(
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

        // プレイヤーで開いている（高優先度）
        repository.setMediaTranscriptionSessionOpen(mediaId, true)
        try {
            repository.requestAnalysis(mediaId, stages)
            assertEquals(1, decodeCalls)
            assertEquals(StageStatus.Done, repository.observeStatus(mediaId).first()[AnalysisStage.SEGMENTATION])

            // 同じ要求の 2 本目（WorkManager のワーカーと直接の実行のうち後のほう）は、済んでいるのでデコードしない
            repository.requestAnalysis(mediaId, stages)
            assertEquals(1, decodeCalls)
            assertEquals(StageStatus.Done, repository.observeStatus(mediaId).first()[AnalysisStage.FEATURES])
        } finally {
            repository.setMediaTranscriptionSessionOpen(mediaId, false)
        }
    }

    @Test
    fun `translateChunk_翻訳結果をDBへ保存し同一言語での再呼び出し時はキャッシュを返しテキスト編集でリセットされる`() = runTest {
        var translateCallCount = 0
        val fakeTranslator = object : OnDeviceTextTranslator {
            override fun isSupported(sourceLanguage: LanguageTag?, targetLanguage: LanguageTag?): Boolean = sourceLanguage?.value == "en" && targetLanguage?.value == "ja"

            override suspend fun translate(
                text: String,
                sourceLanguage: LanguageTag,
                targetLanguage: LanguageTag,
            ): String? {
                if (!isSupported(sourceLanguage, targetLanguage)) return null
                translateCallCount += 1
                return "訳:$text"
            }
        }
        val repository = DefaultAnalysisRepository(
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
            textTranslator = fakeTranslator,
        )

        assertTrue(repository.isTranslationSupported(LanguageTag("en"), LanguageTag("ja")))
        assertFalse(repository.isTranslationSupported(LanguageTag("yue"), LanguageTag("ja")))

        val mediaId = MediaId("media_translate_01")
        chunkDao.insertAll(
            listOf(
                ChunkEntity(
                    id = 10L,
                    mediaId = mediaId.value,
                    startMs = 0L,
                    endMs = 1_000L,
                    origin = ChunkOrigin.AUTO.name,
                ),
            ),
        )
        transcriptDao.upsert(
            TranscriptEntity(
                chunkId = 10L,
                text = "Hello world",
                language = "en",
                languageConfidence = 0.95f,
                engine = "whisper",
                createdAt = currentEpochMs,
            ),
        )

        val firstTranslated = repository.translateChunk(
            id = mediaId,
            chunkId = ChunkId(10L),
            sourceLanguage = LanguageTag("en"),
            targetLanguage = LanguageTag("ja"),
        )
        assertEquals("訳:Hello world", firstTranslated)
        assertEquals(1, translateCallCount)

        val stored = repository.observeTranscripts(mediaId).first()[ChunkId(10L)]
        assertNotNull(stored)
        assertEquals("訳:Hello world", stored.translatedText)
        assertEquals(LanguageTag("ja"), stored.translatedLanguage)

        // 同じ targetLanguage なら再翻訳せずキャッシュを返す
        val cachedTranslated = repository.translateChunk(
            id = mediaId,
            chunkId = ChunkId(10L),
            sourceLanguage = LanguageTag("en"),
            targetLanguage = LanguageTag("JA"),
        )
        assertEquals("訳:Hello world", cachedTranslated)
        assertEquals(1, translateCallCount)

        // 手動編集でテキストが上書きされたら翻訳がリセットされる
        repository.updateChunkText(mediaId, ChunkId(10L), "Updated text")
        val afterEdit = repository.observeTranscripts(mediaId).first()[ChunkId(10L)]
        assertNotNull(afterEdit)
        assertNull(afterEdit.translatedText)
        assertNull(afterEdit.translatedLanguage)

        // 再翻訳後に applyChunkTranscriptionTrial でもリセットされることを確認
        val retranslated = repository.translateChunk(
            id = mediaId,
            chunkId = ChunkId(10L),
            sourceLanguage = LanguageTag("en"),
            targetLanguage = LanguageTag("ja"),
        )
        assertEquals("訳:Updated text", retranslated)
        assertEquals(2, translateCallCount)

        repository.applyChunkTranscriptionTrial(
            id = mediaId,
            chunkId = ChunkId(10L),
            text = "Trial text",
            language = LanguageTag("en"),
        )
        val afterTrial = repository.observeTranscripts(mediaId).first()[ChunkId(10L)]
        assertNotNull(afterTrial)
        assertNull(afterTrial.translatedText)
        assertNull(afterTrial.translatedLanguage)
    }

    @Test
    fun `mergeWithNextChunk_mergeChunks_翻訳テキストが非空なら空白で連結し片方のみなら引き継ぐ`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_merge_translation_01")
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 1L, mediaId = mediaId.value, startMs = 0L, endMs = 1_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 2L, mediaId = mediaId.value, startMs = 1_000L, endMs = 2_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 3L, mediaId = mediaId.value, startMs = 2_000L, endMs = 3_000L, origin = ChunkOrigin.AUTO.name),
            ),
        )
        transcriptDao.upsertAll(
            listOf(
                TranscriptEntity(
                    chunkId = 1L,
                    text = "Good morning",
                    language = "en",
                    engine = "whisper",
                    createdAt = currentEpochMs,
                    translatedText = "おはよう",
                    translatedLang = "ja",
                ),
                TranscriptEntity(
                    chunkId = 2L,
                    text = "everyone",
                    language = "en",
                    engine = "whisper",
                    createdAt = currentEpochMs,
                    translatedText = "みなさん",
                    translatedLang = "ja",
                ),
                TranscriptEntity(
                    chunkId = 3L,
                    text = "let us start",
                    language = "en",
                    engine = "whisper",
                    createdAt = currentEpochMs,
                    translatedText = null,
                    translatedLang = null,
                ),
            ),
        )

        assertTrue(repository.mergeWithNextChunk(mediaId, ChunkId(1L)))
        val afterFirstMergeChunks = repository.observeChunks(mediaId).first()
        assertEquals(2, afterFirstMergeChunks.size)
        val firstMergedTranscript = repository.observeTranscripts(mediaId).first()[afterFirstMergeChunks[0].id]
        assertNotNull(firstMergedTranscript)
        assertEquals("おはよう みなさん", firstMergedTranscript.translatedText)
        assertEquals(LanguageTag("ja"), firstMergedTranscript.translatedLanguage)

        // 片方のみ翻訳がある 2 チャンクを mergeChunks で結合するとそのまま引き継がれる
        assertTrue(repository.mergeChunks(mediaId, afterFirstMergeChunks.map { it.id }))
        val afterSecondMergeChunks = repository.observeChunks(mediaId).first()
        assertEquals(1, afterSecondMergeChunks.size)
        val secondMergedTranscript = repository.observeTranscripts(mediaId).first()[afterSecondMergeChunks[0].id]
        assertNotNull(secondMergedTranscript)
        assertEquals("おはよう みなさん", secondMergedTranscript.translatedText)
        assertEquals(LanguageTag("ja"), secondMergedTranscript.translatedLanguage)
    }

    @Test
    fun `setChunksSkipped_スキップ状態の切り替えとチャンク分割結合境界移動での引き継ぎ`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_skip_01")
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 1L, mediaId = mediaId.value, startMs = 0L, endMs = 1_000L, origin = ChunkOrigin.AUTO.name, isSkipped = false),
                ChunkEntity(id = 2L, mediaId = mediaId.value, startMs = 1_000L, endMs = 2_000L, origin = ChunkOrigin.AUTO.name, isSkipped = false),
                ChunkEntity(id = 3L, mediaId = mediaId.value, startMs = 2_000L, endMs = 3_000L, origin = ChunkOrigin.AUTO.name, isSkipped = false),
            ),
        )

        repository.setChunksSkipped(mediaId, setOf(ChunkId(1L), ChunkId(2L)), true)
        val afterSkip = repository.observeChunks(mediaId).first()
        assertEquals(listOf(true, true, false), afterSkip.map { it.isSkipped })

        // splitChunk: 分割後の前後 2 チャンクとも target.isSkipped (true) を引き継ぐ
        assertTrue(repository.splitChunk(mediaId, ChunkId(1L), 500L))
        val afterSplit = repository.observeChunks(mediaId).first()
        assertEquals(4, afterSplit.size)
        assertEquals(listOf(true, true, true, false), afterSplit.map { it.isSkipped })

        // mergeWithNextChunk: 両方 true (index 0, 1) の結合では true
        assertTrue(repository.mergeWithNextChunk(mediaId, afterSplit[0].id))
        val afterMergeBothSkipped = repository.observeChunks(mediaId).first()
        assertEquals(3, afterMergeBothSkipped.size)
        assertEquals(listOf(true, true, false), afterMergeBothSkipped.map { it.isSkipped })

        // mergeWithNextChunk: 片方 true・片方 false (index 1, 2) の結合では false
        assertTrue(repository.mergeWithNextChunk(mediaId, afterMergeBothSkipped[1].id))
        val afterMergeMixed = repository.observeChunks(mediaId).first()
        assertEquals(2, afterMergeMixed.size)
        assertEquals(listOf(true, false), afterMergeMixed.map { it.isSkipped })

        // moveChunkBoundary: 元の isSkipped を維持する
        assertTrue(repository.moveChunkBoundary(mediaId, afterMergeMixed[0].id, 0L, 1_200L))
        val afterMove = repository.observeChunks(mediaId).first()
        assertEquals(listOf(true, false), afterMove.map { it.isSkipped })

        // mergeChunks: すべて true でない場合は false
        assertTrue(repository.mergeChunks(mediaId, afterMove.map { it.id }))
        val afterMergeAll = repository.observeChunks(mediaId).first()
        assertEquals(1, afterMergeAll.size)
        assertEquals(false, afterMergeAll[0].isSkipped)
    }

    @Test
    fun `requestAnalysis_filesDir配下に音声が無くてもlocalFilePathやsourceUriからデコードできる`() = runTest {
        val externalDir = File(context.cacheDir, "external_audio").apply { mkdirs() }
        val localFile = File(externalDir, "local_ref_1.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val uriFile = File(externalDir, "local_ref_2.mp3").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }

        val mediaIdLocal = MediaId("media_local_ref_path")
        val mediaIdUri = MediaId("media_local_ref_uri")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaIdLocal.value,
                title = "Local Path Audio",
                durationMs = 2_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 4L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
                storageState = "LOCAL_REFERENCE",
                sourceUri = null,
                localFilePath = localFile.absolutePath,
            ),
        )
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaIdUri.value,
                title = "Source Uri Audio",
                durationMs = 2_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 4L,
                sha256 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
                storageState = "LOCAL_REFERENCE",
                sourceUri = Uri.fromFile(uriFile).toString(),
                localFilePath = null,
            ),
        )

        val decodedPaths = mutableListOf<String>()
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                decodedPaths += file.absolutePath
                val samples = FloatArray(outputSampleRate * 2) { idx -> if (idx < outputSampleRate) 0.5f else 0f }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = samples.size.toLong(),
                    resampledSamples = samples.size.toLong(),
                    durationMs = 2_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/mpeg",
                )
            }
        }
        val repository = DefaultAnalysisRepository(
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

        repository.requestAnalysis(mediaIdLocal, stages)
        assertEquals(StageStatus.Done, repository.observeStatus(mediaIdLocal).first()[AnalysisStage.SEGMENTATION])
        assertNotNull(repository.observeWaveform(mediaIdLocal).first())

        repository.requestAnalysis(mediaIdUri, stages)
        assertEquals(StageStatus.Done, repository.observeStatus(mediaIdUri).first()[AnalysisStage.SEGMENTATION])
        assertNotNull(repository.observeWaveform(mediaIdUri).first())

        assertEquals(listOf(localFile.absolutePath, uriFile.absolutePath), decodedPaths)
    }

    @Test
    fun `sourceUriのみのLOCAL_REFERENCE教材に対して詳細文字起こしと先読み文字起こしと再文字起こしが正常に動作する`() = runTest {
        val externalDir = File(context.cacheDir, "content_provider_audio").apply { mkdirs() }
        val backingAudioFile = File(externalDir, "uri_only_sample.m4a").apply {
            writeBytes(byteArrayOf(10, 20, 30, 40, 50, 60))
        }
        // content:// スキームかつ path に実ファイルのパスを持たせることで ContentResolver フォールバックまたは inputStream 経由で読めるようにする
        val contentUri = Uri.parse("content://com.miuraken.chunkflow.testprovider${backingAudioFile.absolutePath}")
        org.robolectric.Shadows.shadowOf(context.contentResolver).registerInputStream(
            contentUri,
            java.io.ByteArrayInputStream(backingAudioFile.readBytes()),
        )

        val mediaId = MediaId("media_source_uri_stt")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Content Uri Audio",
                durationMs = 4_000L,
                mimeType = "audio/mp4",
                sizeBytes = 6L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
                storageState = "LOCAL_REFERENCE",
                sourceUri = contentUri.toString(),
                localFilePath = null,
            ),
        )
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 101L, mediaId = mediaId.value, startMs = 0L, endMs = 2_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 102L, mediaId = mediaId.value, startMs = 2_000L, endMs = 4_000L, origin = ChunkOrigin.AUTO.name),
            ),
        )

        val transcribedFiles = mutableListOf<File>()
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult = transcribeChunk(
                audioFile = audioFile,
                startMs = startMs,
                endMs = endMs,
                languagePair = languagePair,
                forceRefresh = false,
                highPriority = false,
                itemSttOverride = itemSttOverride,
                engineByLanguage = engineByLanguage,
            )

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                forceRefresh: Boolean,
                highPriority: Boolean,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult {
                transcribedFiles += audioFile
                val isFirst = startMs == 0L
                return OnDeviceChunkTranscriptionResult(
                    text = if (forceRefresh) "再認識($startMs..$endMs)" else "先読み認識($startMs..$endMs)",
                    language = if (isFirst) LanguageTag("en") else LanguageTag("ja"),
                    confidence = 0.92f,
                    engineId = EngineId("on-device-speech"),
                )
            }

            override suspend fun transcribeChunkTrial(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                engineOverride: com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine?,
                audioSpeed: Float,
            ): OnDeviceChunkTranscriptionResult {
                transcribedFiles += audioFile
                return OnDeviceChunkTranscriptionResult(
                    text = "詳細試行認識($startMs..$endMs)",
                    language = languagePair?.target ?: LanguageTag("en"),
                    confidence = 0.96f,
                    engineId = EngineId("mlkit-genai-speech"),
                )
            }
        }

        val repository = createRepository(
            testScheduler = testScheduler,
            onDeviceChunkTranscriber = fakeTranscriber,
        )

        // filesDir/media 配下にはファイルが作られていないことを確認
        assertNull(mediaFileLocator.resolveAudioFile(mediaId))

        // 1. tryTranscribeChunkCustom（詳細文字起こし）
        val trialResult = repository.tryTranscribeChunkCustom(
            id = mediaId,
            chunkId = ChunkId(101L),
            engine = com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ML_KIT,
            languageOverride = LanguageTag("en"),
            audioSpeed = 1.0f,
        )
        assertNotNull(trialResult)
        assertEquals("詳細試行認識(0..2000)", trialResult.text)
        assertEquals(LanguageTag("en"), trialResult.language)

        // 2. triggerLookaheadAt（先読み文字起こし・言語判定）
        repository.triggerLookaheadAt(mediaId, currentChunkIndex = 0)
        testScheduler.advanceUntilIdle()
        val afterLookahead = repository.observeTranscripts(mediaId).first()
        assertEquals("先読み認識(0..2000)", afterLookahead[ChunkId(101L)]?.text)
        assertEquals(LanguageTag("en"), afterLookahead[ChunkId(101L)]?.language)
        assertEquals("先読み認識(2000..4000)", afterLookahead[ChunkId(102L)]?.text)
        assertEquals(LanguageTag("ja"), afterLookahead[ChunkId(102L)]?.language)

        // 3. retranscribeChunk（再文字起こし）
        repository.retranscribeChunk(mediaId, ChunkId(101L))
        testScheduler.advanceUntilIdle()
        val afterRetranscribe = repository.observeTranscripts(mediaId).first()
        assertEquals("再認識(0..2000)", afterRetranscribe[ChunkId(101L)]?.text)

        // filesDir/media 配下には作られず、filesDir/analysis/<mediaId>/source_audio.m4a が再利用されていることを確認
        assertNull(mediaFileLocator.resolveAudioFile(mediaId))
        val cachedSourceAudio = File(File(File(context.filesDir, "analysis"), mediaId.value), "source_audio.m4a")
        assertTrue(cachedSourceAudio.isFile)
        assertTrue(transcribedFiles.isNotEmpty())
        assertTrue(transcribedFiles.all { it.absolutePath == cachedSourceAudio.absolutePath })
    }

    @Test
    fun `setBackgroundTranscriptionEnabled_有効にしてファイルを閉じた場合でも残りのチャンクの文字起こしが完了する`() = runTest {
        val transcribedChunks = mutableListOf<Long>()
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? {
                transcribedChunks += startMs
                return OnDeviceChunkTranscriptionResult(
                    text = "BG($startMs..$endMs)",
                    language = LanguageTag("en"),
                    confidence = 0.91f,
                )
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_bg_complete_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Background Complete Test",
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
        mediaFileLocator.audioFileFor(mediaId, "mp3").writeBytes(byteArrayOf(1, 2, 3))
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 2001L, mediaId = mediaId.value, startMs = 0L, endMs = 800L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 2002L, mediaId = mediaId.value, startMs = 1_000L, endMs = 1_800L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 2003L, mediaId = mediaId.value, startMs = 2_000L, endMs = 2_800L, origin = ChunkOrigin.AUTO.name),
            ),
        )

        repository.setMediaTranscriptionSessionOpen(mediaId, true)
        repository.setLookaheadEnabled(mediaId, true)
        repository.setBackgroundTranscriptionEnabled(mediaId, true)
        assertTrue(repository.observeLookaheadStatus(mediaId).first().backgroundEnabled)

        // ファイルを閉じてもバックグラウンドループが起動して全チャンクの文字起こしが完了する
        repository.setMediaTranscriptionSessionOpen(mediaId, false)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(0L, 1_000L, 2_000L), transcribedChunks)
        val transcripts = repository.observeTranscripts(mediaId).first()
        assertEquals("BG(0..800)", transcripts[ChunkId(2001L)]?.text)
        assertEquals("BG(1000..1800)", transcripts[ChunkId(2002L)]?.text)
        assertEquals("BG(2000..2800)", transcripts[ChunkId(2003L)]?.text)
    }

    @Test
    fun `setBackgroundTranscriptionEnabled_ファイルAをバックグラウンド有効で閉じファイルBを開くとファイルBが先に完了しその後にファイルAが完了する`() = runTest {
        val transcribedOrder = mutableListOf<Pair<String, Long>>()
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? {
                val idName = audioFile.nameWithoutExtension
                delay(10L)
                transcribedOrder += idName to startMs
                return OnDeviceChunkTranscriptionResult(
                    text = "Text($idName:$startMs)",
                    language = LanguageTag("en"),
                    confidence = 0.9f,
                )
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val fileA = MediaId("media_bg_file_a")
        val fileB = MediaId("media_fg_file_b")
        for (id in listOf(fileA, fileB)) {
            mediaItemDao.upsert(
                MediaItemEntity(
                    id = id.value,
                    title = id.value,
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
            mediaFileLocator.audioFileFor(id, "mp3").writeBytes(byteArrayOf(1, 2, 3))
        }
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 2101L, mediaId = fileA.value, startMs = 0L, endMs = 800L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 2102L, mediaId = fileA.value, startMs = 1_000L, endMs = 1_800L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 2201L, mediaId = fileB.value, startMs = 0L, endMs = 800L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 2202L, mediaId = fileB.value, startMs = 1_000L, endMs = 1_800L, origin = ChunkOrigin.AUTO.name),
            ),
        )

        // ファイル A を開いてバックグラウンド文字起こしを有効にしてから閉じ、直後にファイル B を開いて文字起こしを実行する
        repository.setMediaTranscriptionSessionOpen(fileA, true)
        repository.setLookaheadEnabled(fileA, true)
        repository.setBackgroundTranscriptionEnabled(fileA, true)
        repository.setMediaTranscriptionSessionOpen(fileA, false)

        repository.setMediaTranscriptionSessionOpen(fileB, true)
        repository.setLookaheadEnabled(fileB, true)
        repository.triggerLookaheadAt(fileB, currentChunkId = ChunkId(2201L))
        testScheduler.advanceUntilIdle()

        // ファイル B の全チャンクが先に完了し、その後にファイル A のチャンクがバックグラウンドで完了する
        assertEquals(
            listOf(
                fileB.value to 0L,
                fileB.value to 1_000L,
                fileA.value to 0L,
                fileA.value to 1_000L,
            ),
            transcribedOrder,
        )
        assertEquals("Text(${fileB.value}:0)", transcriptDao.getByChunkId(2201L)?.text)
        assertEquals("Text(${fileB.value}:1000)", transcriptDao.getByChunkId(2202L)?.text)
        assertEquals("Text(${fileA.value}:0)", transcriptDao.getByChunkId(2101L)?.text)
        assertEquals("Text(${fileA.value}:1000)", transcriptDao.getByChunkId(2102L)?.text)
    }

    @Test
    fun `setLookaheadEnabledFalse_中断するとbackgroundEnabledもfalseになりバックグラウンド処理が止まる`() = runTest {
        val firstChunkStarted = CompletableDeferred<Unit>()
        val releaseFirstChunk = CompletableDeferred<Unit>()
        val transcribedChunks = mutableListOf<Long>()

        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override fun cancelActiveTranscription(onlyLowPriority: Boolean) {
                if (!onlyLowPriority) {
                    releaseFirstChunk.complete(Unit)
                }
            }

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? {
                transcribedChunks += startMs
                if (startMs == 0L) {
                    firstChunkStarted.complete(Unit)
                    releaseFirstChunk.await()
                    return null
                }
                return OnDeviceChunkTranscriptionResult(
                    text = "BG($startMs)",
                    language = LanguageTag("en"),
                    confidence = 0.9f,
                )
            }
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_bg_stop_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Background Stop Test",
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
        mediaFileLocator.audioFileFor(mediaId, "mp3").writeBytes(byteArrayOf(1, 2, 3))
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 2301L, mediaId = mediaId.value, startMs = 0L, endMs = 800L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 2302L, mediaId = mediaId.value, startMs = 1_000L, endMs = 1_800L, origin = ChunkOrigin.AUTO.name),
            ),
        )

        repository.setMediaTranscriptionSessionOpen(mediaId, true)
        repository.setLookaheadEnabled(mediaId, true)
        repository.setBackgroundTranscriptionEnabled(mediaId, true)
        repository.setMediaTranscriptionSessionOpen(mediaId, false)

        testScheduler.advanceTimeBy(25L)
        testScheduler.runCurrent()
        firstChunkStarted.await()

        // setLookaheadEnabled(false) で中断すると backgroundEnabled も false になり、後続チャンクは実行されない
        repository.setLookaheadEnabled(mediaId, false)
        testScheduler.advanceUntilIdle()

        val status = repository.observeLookaheadStatus(mediaId).first()
        assertFalse(status.enabled)
        assertFalse(status.backgroundEnabled)
        assertNull(status.inFlightChunkId)
        assertTrue(status.queuedChunkIds.isEmpty())
        assertEquals(listOf(0L), transcribedChunks)
        assertNull(transcriptDao.getByChunkId(2302L))
    }

    @Test
    fun `startLanguageDetectionOnly_判定済みのチャンクを文字起こしする際にprecomputedLanguageとconfidenceを引き渡す`() = runTest {
        val capturedPrecomputed = mutableListOf<Pair<LanguageTag?, Float?>>()
        var detectCallCount = 0

        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun detectChunkLanguage(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair,
            ): OnDeviceChunkTranscriptionResult {
                detectCallCount += 1
                return OnDeviceChunkTranscriptionResult(
                    text = "",
                    language = LanguageTag("ja"),
                    confidence = 0.88f,
                    engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
                )
            }

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                forceRefresh: Boolean,
                highPriority: Boolean,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
                precomputedLanguage: LanguageTag?,
                precomputedLanguageConfidence: Float?,
            ): OnDeviceChunkTranscriptionResult {
                capturedPrecomputed += precomputedLanguage to precomputedLanguageConfidence
                return OnDeviceChunkTranscriptionResult(
                    text = "判定済み言語で認識",
                    language = precomputedLanguage ?: LanguageTag("en"),
                    confidence = 0.93f,
                )
            }

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = null
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_precomputed_lid_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Precomputed LID Test",
                durationMs = 2_000L,
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
        mediaFileLocator.audioFileFor(mediaId, "mp3").writeBytes(byteArrayOf(1, 2, 3))
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 3101L, mediaId = mediaId.value, startMs = 0L, endMs = 1_500L, origin = ChunkOrigin.AUTO.name),
            ),
        )

        repository.startLanguageDetectionOnly(mediaId)
        testScheduler.advanceUntilIdle()
        assertEquals(1, detectCallCount)
        assertEquals("ja", transcriptDao.getByChunkId(3101L)?.language)

        repository.setMediaTranscriptionSessionOpen(mediaId, true)
        repository.setLookaheadEnabled(mediaId, true)
        repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(3101L))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf<Pair<LanguageTag?, Float?>>(LanguageTag("ja") to 0.88f), capturedPrecomputed)
        assertEquals("判定済み言語で認識", transcriptDao.getByChunkId(3101L)?.text)
    }

    @Test
    fun `setTranscribeTargetLanguageOnly_第1段階の一括言語判定後に学習対象言語のチャンクのみを文字起こしし解説言語はスキップする`() = runTest {
        val transcribedChunkRanges = mutableListOf<Pair<Long, Long>>()
        val detectedChunkRanges = mutableListOf<Pair<Long, Long>>()

        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun detectChunkLanguage(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair,
            ): OnDeviceChunkTranscriptionResult {
                detectedChunkRanges += startMs to endMs
                // 1チャンク目 (0..1500ms) は解説言語 (ja)、2チャンク目 (1500..3000ms) は学習対象言語 (en)
                val lang = if (startMs == 0L) LanguageTag("ja") else LanguageTag("en")
                return OnDeviceChunkTranscriptionResult(
                    text = "",
                    language = lang,
                    confidence = 0.92f,
                    engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
                )
            }

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                forceRefresh: Boolean,
                highPriority: Boolean,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
                precomputedLanguage: LanguageTag?,
                precomputedLanguageConfidence: Float?,
            ): OnDeviceChunkTranscriptionResult {
                transcribedChunkRanges += startMs to endMs
                return OnDeviceChunkTranscriptionResult(
                    text = "Hello world",
                    language = precomputedLanguage ?: LanguageTag("en"),
                    confidence = 0.95f,
                )
            }

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = null
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_two_stage_target_only_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Two Stage Target Only Test",
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
        mediaFileLocator.audioFileFor(mediaId, "mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 4101L, mediaId = mediaId.value, startMs = 0L, endMs = 1_500L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 4102L, mediaId = mediaId.value, startMs = 1_500L, endMs = 3_000L, origin = ChunkOrigin.AUTO.name),
            ),
        )

        // 学習対象言語（en）のみ文字起こしするモードを有効化
        repository.setTranscribeTargetLanguageOnly(mediaId, true)

        // 第1段階: 高速一括言語判定で全チャンクに [ja] / [en] バッジを付与
        repository.startLanguageDetectionOnly(mediaId)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(0L to 1_500L, 1_500L to 3_000L), detectedChunkRanges)
        assertEquals("ja", transcriptDao.getByChunkId(4101L)?.language)
        assertEquals("", transcriptDao.getByChunkId(4101L)?.text)
        assertEquals("en", transcriptDao.getByChunkId(4102L)?.language)
        assertEquals("", transcriptDao.getByChunkId(4102L)?.text)

        // 第2段階: 先読み文字起こしを開始すると、解説言語 (ja) の 4101L はスキップされ、学習対象言語 (en) の 4102L のみ文字起こしされる
        repository.setMediaTranscriptionSessionOpen(mediaId, true)
        repository.setLookaheadEnabled(mediaId, true)
        repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(4101L))
        testScheduler.advanceUntilIdle()

        assertEquals(listOf(1_500L to 3_000L), transcribedChunkRanges)
        assertEquals("ja", transcriptDao.getByChunkId(4101L)?.language)
        assertEquals("", transcriptDao.getByChunkId(4101L)?.text)
        assertEquals("en", transcriptDao.getByChunkId(4102L)?.language)
        assertEquals("Hello world", transcriptDao.getByChunkId(4102L)?.text)
    }

    @Test
    fun `startLanguageDetectionOnly_一括判定の結果をチャンク順に保存し判定中に言語が入ったチャンクは上書きしない`() = runTest {
        val requestedChunks = mutableListOf<List<ChunkTimeRange>>()
        val requestedParallelism = mutableListOf<Int>()
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun detectChunkLanguages(
                audioFile: File,
                chunks: List<ChunkTimeRange>,
                languagePair: LanguagePair,
                parallelism: Int,
                computeDispatcher: CoroutineDispatcher,
                onResult: suspend (index: Int, result: OnDeviceChunkTranscriptionResult?) -> Unit,
            ) {
                requestedChunks += chunks
                requestedParallelism += parallelism
                for (index in chunks.indices) {
                    if (index == 1) {
                        // 2 件目の判定中に、別の処理（利用者による言語の設定など）が 2 件目に言語を入れた
                        transcriptDao.upsert(
                            TranscriptEntity(
                                chunkId = 5102L,
                                text = "",
                                language = "en",
                                languageConfidence = null,
                                engine = "manual",
                                createdAt = currentEpochMs,
                            ),
                        )
                    }
                    val result = if (index == 2) {
                        null
                    } else {
                        OnDeviceChunkTranscriptionResult(
                            text = "",
                            language = LanguageTag("ja"),
                            confidence = 0.9f,
                            engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
                        )
                    }
                    onResult(index, result)
                }
            }

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = null
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_batch_lid_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Batch LID Test",
                durationMs = 3_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 2048L,
                sha256 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )
        mediaFileLocator.audioFileFor(mediaId, "mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 5101L, mediaId = mediaId.value, startMs = 0L, endMs = 1_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 5102L, mediaId = mediaId.value, startMs = 1_000L, endMs = 2_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 5103L, mediaId = mediaId.value, startMs = 2_000L, endMs = 3_000L, origin = ChunkOrigin.AUTO.name),
            ),
        )

        repository.startLanguageDetectionOnly(mediaId)
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                listOf(
                    ChunkTimeRange(startMs = 0L, endMs = 1_000L),
                    ChunkTimeRange(startMs = 1_000L, endMs = 2_000L),
                    ChunkTimeRange(startMs = 2_000L, endMs = 3_000L),
                ),
            ),
            requestedChunks,
            "対象のチャンクをまとめて 1 回で依頼する",
        )
        assertTrue(requestedParallelism.single() in 1..OnDeviceChunkTranscriber.MAX_LANGUAGE_DETECTION_PARALLELISM)
        assertEquals("ja", transcriptDao.getByChunkId(5101L)?.language)
        assertEquals("en", transcriptDao.getByChunkId(5102L)?.language, "判定中に入った言語は上書きしない")
        assertNull(transcriptDao.getByChunkId(5103L), "判定できなかったチャンクには何も保存しない")
        val status = repository.observeLookaheadStatus(mediaId).first()
        assertNull(status.inFlightChunkId)
        assertEquals(0, status.completedCount)
        assertEquals(0, status.totalTargetCount)
    }

    @Test
    fun `startLanguageDetectionOnly_既存の仮判定レコードは再判定で更新またはnull時に削除し手動設定や本文ありは保護する`() = runTest {
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun detectChunkLanguages(
                audioFile: File,
                chunks: List<ChunkTimeRange>,
                languagePair: LanguagePair,
                parallelism: Int,
                computeDispatcher: CoroutineDispatcher,
                onResult: suspend (index: Int, result: OnDeviceChunkTranscriptionResult?) -> Unit,
            ) {
                for ((index, chunk) in chunks.withIndex()) {
                    val result = when (chunk.startMs) {
                        0L -> OnDeviceChunkTranscriptionResult(
                            text = "",
                            language = LanguageTag("hi"),
                            confidence = 0.85f,
                            engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
                        )

                        1_000L -> null

                        else -> null
                    }
                    onResult(index, result)
                }
            }

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = null
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_relid_overwrite_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Re-LID Overwrite Test",
                durationMs = 4_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 2048L,
                sha256 = "1122334455667788112233445566778811223344556677881122334455667788",
                course = null,
                targetLang = "hi",
                baseLang = "en",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )
        mediaFileLocator.audioFileFor(mediaId, "mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 6101L, mediaId = mediaId.value, startMs = 0L, endMs = 1_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 6102L, mediaId = mediaId.value, startMs = 1_000L, endMs = 2_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 6103L, mediaId = mediaId.value, startMs = 2_000L, endMs = 3_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 6104L, mediaId = mediaId.value, startMs = 3_000L, endMs = 4_000L, origin = ChunkOrigin.AUTO.name),
            ),
        )
        // 6101L: 古い仮判定 (en) -> 再判定で hi に更新されるべき
        transcriptDao.upsert(
            TranscriptEntity(
                chunkId = 6101L,
                text = "",
                language = "en",
                languageConfidence = 0.6f,
                engine = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID.value,
                userEdited = false,
                createdAt = currentEpochMs,
            ),
        )
        // 6102L: 古い仮判定 (en) -> 再判定で null が返り削除されて "-" に戻るべき
        transcriptDao.upsert(
            TranscriptEntity(
                chunkId = 6102L,
                text = "",
                language = "en",
                languageConfidence = 0.52f,
                engine = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID.value,
                userEdited = false,
                createdAt = currentEpochMs,
            ),
        )
        // 6103L: 手動設定言語 (en) -> 保護されて触られないべき
        transcriptDao.upsert(
            TranscriptEntity(
                chunkId = 6103L,
                text = "",
                language = "en",
                languageConfidence = 1.0f,
                engine = "manual-lang",
                userEdited = true,
                createdAt = currentEpochMs,
            ),
        )
        // 6104L: 本文あり (en) -> 保護されて触られないべき
        transcriptDao.upsert(
            TranscriptEntity(
                chunkId = 6104L,
                text = "Hello",
                language = "en",
                languageConfidence = 0.9f,
                engine = "android-speech-recognizer",
                userEdited = false,
                createdAt = currentEpochMs,
            ),
        )

        repository.startLanguageDetectionOnly(mediaId)
        testScheduler.advanceUntilIdle()

        assertEquals("hi", transcriptDao.getByChunkId(6101L)?.language, "古い仮判定が新しい判定結果 hi で上書きされる")
        assertNull(transcriptDao.getByChunkId(6102L), "再判定で確定しなかった古い仮判定は削除されて null (UI上の -) に戻る")
        assertEquals("en", transcriptDao.getByChunkId(6103L)?.language, "手動設定された言語は保護される")
        assertEquals("Hello", transcriptDao.getByChunkId(6104L)?.text, "本文があるレコードは保護される")
    }

    @Test
    fun `先読みと強制再文字起こし_仮判定の言語と逆の言語がSTTから返った場合にチャンクの言語を訂正する`() = runTest {
        val capturedPairs = mutableListOf<LanguagePair?>()
        val capturedPrecomputed = mutableListOf<LanguageTag?>()

        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                forceRefresh: Boolean,
                highPriority: Boolean,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
                precomputedLanguage: LanguageTag?,
                precomputedLanguageConfidence: Float?,
            ): OnDeviceChunkTranscriptionResult {
                capturedPairs += languagePair
                capturedPrecomputed += precomputedLanguage
                // 仮判定は en だったが、STT の結果として hi ("सोनिए") が返ったケース
                return OnDeviceChunkTranscriptionResult(
                    text = "सोनिए",
                    language = LanguageTag("hi"),
                    confidence = 0.91f,
                )
            }

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = null
        }

        val repository = createRepository(testScheduler, onDeviceChunkTranscriber = fakeTranscriber)
        val mediaId = MediaId("media_stt_lang_correction_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "STT Lang Correction Test",
                durationMs = 2_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 2048L,
                sha256 = "9988776655443322998877665544332299887766554433229988776655443322",
                course = null,
                targetLang = "hi",
                baseLang = "en",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )
        mediaFileLocator.audioFileFor(mediaId, "mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 7101L, mediaId = mediaId.value, startMs = 0L, endMs = 1_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 7102L, mediaId = mediaId.value, startMs = 1_000L, endMs = 2_000L, origin = ChunkOrigin.AUTO.name),
            ),
        )
        // 両チャンクとも LID のみで誤って "en" と仮判定されている
        for (cid in listOf(7101L, 7102L)) {
            transcriptDao.upsert(
                TranscriptEntity(
                    chunkId = cid,
                    text = "",
                    language = "en",
                    languageConfidence = 0.72f,
                    engine = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID.value,
                    userEdited = false,
                    createdAt = currentEpochMs,
                ),
            )
        }

        // 1. 先読み文字起こしで 7101L の言語が en -> hi に訂正されることを確認
        repository.setMediaTranscriptionSessionOpen(mediaId, true)
        repository.setLookaheadEnabled(mediaId, true)
        repository.triggerLookaheadAt(mediaId, currentChunkId = ChunkId(7101L))
        testScheduler.advanceUntilIdle()

        val afterLookahead = transcriptDao.getByChunkId(7101L)
        assertNotNull(afterLookahead)
        assertEquals("सोनिए", afterLookahead.text)
        assertEquals("hi", afterLookahead.language, "先読み文字起こし後に仮判定の en から STT 結果の hi に訂正される")

        // 2. 7102L を仮判定 (en) に戻し、retranscribeChunk でも 2 言語ペア維持 & hi に訂正されることを確認
        transcriptDao.upsert(
            TranscriptEntity(
                chunkId = 7102L,
                text = "",
                language = "en",
                languageConfidence = 0.70f,
                engine = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID.value,
                userEdited = false,
                createdAt = currentEpochMs,
            ),
        )
        capturedPairs.clear()
        capturedPrecomputed.clear()

        repository.retranscribeChunk(mediaId, ChunkId(7102L))
        testScheduler.advanceUntilIdle()

        val afterRetranscribe = transcriptDao.getByChunkId(7102L)
        assertNotNull(afterRetranscribe)
        assertEquals("सोनिए", afterRetranscribe.text)
        assertEquals("hi", afterRetranscribe.language, "retranscribeChunk でも仮判定の en から STT 結果の hi に訂正される")
        assertEquals(
            LanguagePair(target = LanguageTag("hi"), base = LanguageTag("en")),
            capturedPairs.last(),
            "仮判定レコードの再文字起こしでは 2 言語ペアを維持して渡す",
        )
        assertEquals(LanguageTag("en"), capturedPrecomputed.last())
    }

    @Test
    fun `requestAnalysis_初回デコード中に30秒区間ごとに確定したAUTOチャンクを段階的に公開する`() = runTest {
        val mediaId = MediaId("progressive_chunks_01")
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        File(mediaDir, "${mediaId.value}.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Progressive Chunks Test",
                durationMs = 60_000L,
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

        var chunksAt30s: List<com.miuraken.chunkflow.core.model.Chunk> = emptyList()
        var segStatusAt30s: StageStatus? = null
        lateinit var repository: DefaultAnalysisRepository

        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                // 前半 30.1 秒: 3 つの発話区間 (1..5s, 7..12s, 15..25s) と無音区間を作る
                val samples30s = FloatArray(outputSampleRate * 30 + (outputSampleRate / 10)) { idx ->
                    val sec = idx.toDouble() / outputSampleRate
                    if (sec in 1.0..5.0 || sec in 7.0..12.0 || sec in 15.0..25.0) {
                        (0.5 * sin(2.0 * PI * 440.0 * sec)).toFloat()
                    } else {
                        0f
                    }
                }
                sink.onSamples(samples30s, samples30s.size)
                onProgress(
                    DecodeProgress(
                        decodedDurationMs = 30_100L,
                        totalDurationMs = 60_000L,
                        fraction = 0.5f,
                    ),
                )

                // デコード完了前の 30.1 秒時点で、末尾の未確定 1 チャンク (15..25s) を除いた 2 チャンクが公開されていること
                chunksAt30s = repository.observeChunks(mediaId).first()
                segStatusAt30s = repository.observeStatus(mediaId).first()[AnalysisStage.SEGMENTATION]

                // 後半 29.9 秒: さらに 1 つの発話区間 (35..45s) を流してデコード完了
                val totalSamples = outputSampleRate * 60
                val remainingSamples = FloatArray(totalSamples - samples30s.size) { idx ->
                    val sec = (samples30s.size + idx).toDouble() / outputSampleRate
                    if (sec in 35.0..45.0) {
                        (0.5 * sin(2.0 * PI * 440.0 * sec)).toFloat()
                    } else {
                        0f
                    }
                }
                sink.onSamples(remainingSamples, remainingSamples.size)
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = totalSamples.toLong(),
                    resampledSamples = totalSamples.toLong(),
                    durationMs = 60_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/mpeg",
                )
            }
        }

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repository = DefaultAnalysisRepository(
            context = context,
            analysisStateDao = analysisStateDao,
            chunkDao = chunkDao,
            transcriptDao = transcriptDao,
            itemStateDao = itemStateDao,
            mediaItemDao = mediaItemDao,
            mediaFileResolver = mediaFileLocator,
            ioDispatcher = dispatcher,
            bookmarkDao = bookmarkDao,
            clock = { currentEpochMs },
            audioStreamDecoder = fakeDecoder,
        )

        repository.requestAnalysis(mediaId, setOf(AnalysisStage.FEATURES, AnalysisStage.SEGMENTATION))

        assertEquals(2, chunksAt30s.size, "30秒チェックポイント時点で確定済み2チャンクが先行公開される")
        assertTrue(segStatusAt30s is StageStatus.Running, "30秒チェックポイント時点でSEGMENTATIONがRunningになる")

        val finalChunks = repository.observeChunks(mediaId).first()
        assertEquals(4, finalChunks.size, "デコード完了時には全4チャンクが公開される")
        assertEquals(
            StageStatus.Done,
            repository.observeStatus(mediaId).first()[AnalysisStage.SEGMENTATION],
        )
    }

    @Test
    fun `requestAnalysis_2言語教材の初回デコード中にPCMストリームから並列で言語判定を行い音声の再デコードなしで言語バッジを保存する`() = runTest {
        val mediaId = MediaId("pipelined_lid_01")
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        File(mediaDir, "${mediaId.value}.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Pipelined LID Test",
                durationMs = 60_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 4L,
                sha256 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
                course = null,
                targetLang = "en",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )

        val pcmLidLengths = mutableListOf<Int>()
        var fileBasedDetectCalls = 0
        var callIndex = 0

        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun detectChunkLanguageFromPcm(
                audioScopeKey: String,
                pcm16kMono: ShortArray,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair,
                audioFile: File?,
            ): OnDeviceChunkTranscriptionResult {
                pcmLidLengths += pcm16kMono.size
                val lang = if (callIndex % 2 == 0) languagePair.base ?: LanguageTag("ja") else languagePair.target
                callIndex += 1
                return OnDeviceChunkTranscriptionResult(
                    text = "",
                    language = lang,
                    confidence = 0.91f,
                    engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
                )
            }

            override suspend fun detectChunkLanguage(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair,
            ): OnDeviceChunkTranscriptionResult? {
                fileBasedDetectCalls += 1
                return null
            }

            override suspend fun detectChunkLanguages(
                audioFile: File,
                chunks: List<ChunkTimeRange>,
                languagePair: LanguagePair,
                parallelism: Int,
                computeDispatcher: CoroutineDispatcher,
                onResult: suspend (index: Int, result: OnDeviceChunkTranscriptionResult?) -> Unit,
            ) {
                fileBasedDetectCalls += 1
            }

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = null
        }

        var transcriptsAt30s: Map<ChunkId, Transcript> = emptyMap()
        lateinit var repository: DefaultAnalysisRepository

        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                val samples30s = FloatArray(outputSampleRate * 30 + (outputSampleRate / 10)) { idx ->
                    val sec = idx.toDouble() / outputSampleRate
                    if (sec in 1.0..5.0 || sec in 7.0..12.0 || sec in 15.0..25.0) {
                        (0.5 * sin(2.0 * PI * 440.0 * sec)).toFloat()
                    } else {
                        0f
                    }
                }
                sink.onSamples(samples30s, samples30s.size)
                onProgress(
                    DecodeProgress(
                        decodedDurationMs = 30_100L,
                        totalDurationMs = 60_000L,
                        fraction = 0.5f,
                    ),
                )
                testScheduler.runCurrent()
                transcriptsAt30s = repository.observeTranscripts(mediaId).first()

                val totalSamples = outputSampleRate * 60
                val remainingSamples = FloatArray(totalSamples - samples30s.size) { idx ->
                    val sec = (samples30s.size + idx).toDouble() / outputSampleRate
                    if (sec in 35.0..45.0) {
                        (0.5 * sin(2.0 * PI * 440.0 * sec)).toFloat()
                    } else {
                        0f
                    }
                }
                sink.onSamples(remainingSamples, remainingSamples.size)
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = totalSamples.toLong(),
                    resampledSamples = totalSamples.toLong(),
                    durationMs = 60_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/mpeg",
                )
            }
        }

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repository = DefaultAnalysisRepository(
            context = context,
            analysisStateDao = analysisStateDao,
            chunkDao = chunkDao,
            transcriptDao = transcriptDao,
            itemStateDao = itemStateDao,
            mediaItemDao = mediaItemDao,
            mediaFileResolver = mediaFileLocator,
            ioDispatcher = dispatcher,
            onDeviceChunkTranscriber = fakeTranscriber,
            bookmarkDao = bookmarkDao,
            clock = { currentEpochMs },
            audioStreamDecoder = fakeDecoder,
            computeDispatcher = dispatcher,
        )

        repository.requestAnalysis(mediaId, setOf(AnalysisStage.FEATURES, AnalysisStage.SEGMENTATION))
        testScheduler.advanceUntilIdle()

        // 30秒チェックポイント時点で確定した2チャンクの言語判定がすでに完了していること
        assertEquals(2, transcriptsAt30s.size, "デコード途中の30秒時点で確定済み2チャンクの言語判定が保存される")
        assertEquals(
            listOf(LanguageTag("ja"), LanguageTag("en")),
            transcriptsAt30s.values.map { it.language },
        )

        // デコード完了時には全4チャンクの言語判定が完了しており、ファイルからの再デコードは0回であること
        val finalChunks = repository.observeChunks(mediaId).first()
        val finalTranscripts = repository.observeTranscripts(mediaId).first()
        assertEquals(4, finalChunks.size)
        assertEquals(4, pcmLidLengths.size)
        assertTrue(pcmLidLengths.all { it > 0 })
        assertEquals(
            listOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ja"), LanguageTag("en")),
            finalChunks.map { finalTranscripts[it.id]?.language },
        )
        assertEquals(0, fileBasedDetectCalls, "PCMストリームから直接言語判定するため音声ファイルの再デコードは発生しない")

        // 後続で startLanguageDetectionOnly が呼ばれても、パイプライン処理済みチャンクは再デコード・再判定しないこと
        repository.startLanguageDetectionOnly(mediaId)
        testScheduler.advanceUntilIdle()
        assertEquals(0, fileBasedDetectCalls, "startLanguageDetectionOnly を呼んでも重複してファイル再デコードしない")
    }

    @Test
    fun `requestAnalysis_初回5秒でプログレッシブ波形を公開し高速デコードでもまだら状の未判定チャンクを出さない`() = runTest {
        val mediaId = MediaId("fast_decode_200s")
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        File(mediaDir, "${mediaId.value}.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Fast Decode 200s Lesson",
                durationMs = 200_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 4L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "ko",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )

        val pcmLidLengths = mutableListOf<Int>()
        var callIndex = 0
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun detectChunkLanguageFromPcm(
                audioScopeKey: String,
                pcm16kMono: ShortArray,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair,
                audioFile: File?,
            ): OnDeviceChunkTranscriptionResult {
                pcmLidLengths += pcm16kMono.size
                val lang = if (callIndex % 2 == 0) LanguageTag("ja") else LanguageTag("ko")
                callIndex += 1
                return OnDeviceChunkTranscriptionResult(
                    text = "",
                    language = lang,
                    confidence = 0.9f,
                    engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
                )
            }

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = null
        }

        var waveformAt5s: Waveform? = null
        lateinit var repository: DefaultAnalysisRepository

        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                // 実時間クロック (currentEpochMs) を一切進めずに 200 秒間を 5 秒ステップで高速デコードする
                for (step in 1..40) {
                    val stepStartSec = (step - 1) * 5
                    val samples5s = FloatArray(outputSampleRate * 5) { idx ->
                        val sec = stepStartSec + idx.toDouble() / outputSampleRate
                        val inSpeech = (sec % 10.0) in 1.0..4.0 || (sec % 10.0) in 6.0..9.0
                        if (inSpeech) (0.5 * sin(2.0 * PI * 440.0 * sec)).toFloat() else 0f
                    }
                    sink.onSamples(samples5s, samples5s.size)
                    onProgress(
                        DecodeProgress(
                            decodedDurationMs = step * 5_000L,
                            totalDurationMs = 200_000L,
                            fraction = step / 40f,
                        ),
                    )
                    if (step == 1) {
                        waveformAt5s = repository.observeWaveform(mediaId).first()
                    }
                }
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = (outputSampleRate * 200).toLong(),
                    resampledSamples = (outputSampleRate * 200).toLong(),
                    durationMs = 200_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/mpeg",
                )
            }
        }

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        repository = DefaultAnalysisRepository(
            context = context,
            analysisStateDao = analysisStateDao,
            chunkDao = chunkDao,
            transcriptDao = transcriptDao,
            itemStateDao = itemStateDao,
            mediaItemDao = mediaItemDao,
            mediaFileResolver = mediaFileLocator,
            ioDispatcher = dispatcher,
            onDeviceChunkTranscriber = fakeTranscriber,
            bookmarkDao = bookmarkDao,
            clock = { currentEpochMs },
            audioStreamDecoder = fakeDecoder,
            computeDispatcher = dispatcher,
        )

        repository.requestAnalysis(mediaId, setOf(AnalysisStage.FEATURES, AnalysisStage.SEGMENTATION))
        testScheduler.advanceUntilIdle()

        // 1. 最初の 5 秒 (~500 bins) デコード完了時点でプログレッシブ波形がすでに保存・公開されていること
        assertNotNull(waveformAt5s, "5秒デコード時点で初回のプログレッシブ波形が保存・公開される")
        assertTrue((waveformAt5s?.peak?.size ?: 0) >= 499)

        // 2. 200 秒分すべてのチャンクが PCM 欠落なく言語判定され、まだら状の未判定領域がないこと
        val finalChunks = repository.observeChunks(mediaId).first()
        val finalTranscripts = repository.observeTranscripts(mediaId).first()
        assertTrue(finalChunks.size >= 30, "200秒の音声から30個以上のチャンクが生成される (actual=${finalChunks.size})")
        assertTrue(pcmLidLengths.all { it > 0 }, "全チャンクに非空のPCMスライスが渡される")
        assertTrue(
            finalChunks.all { chunk -> finalTranscripts[chunk.id]?.language != null },
            "全チャンクに欠落なく言語判定結果が保存される",
        )
    }

    @Test
    fun `prioritizeOpenedMediaAnalysis_解析済みファイルに未判定チャンクがまだらに残っている場合は未判定チャンクのみ自動補完する`() = runTest {
        val mediaId = MediaId("patchy_existing_01")
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        File(mediaDir, "${mediaId.value}.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Patchy Existing Lesson",
                durationMs = 20_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 4L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "ko",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )

        // 解析ファイルと AnalysisState (DONE) を用意
        val analysisDir = File(File(context.filesDir, "analysis"), mediaId.value).apply { mkdirs() }
        AnalysisBinaryFormat.writeWaveformAtomic(
            File(analysisDir, "waveform.bin"),
            Waveform(
                binMs = 10,
                peak = ByteArray(2000),
                rms = ByteArray(2000),
            ),
        )
        AnalysisBinaryFormat.writeFeaturesAtomic(
            File(analysisDir, "features.bin"),
            StoredFeatures(binMs = 10, logEnergyDb = FloatArray(2000) { -20f }),
        )
        analysisStateDao.upsert(
            StageStatus.Done.toEntity(mediaId, AnalysisStage.FEATURES, CURRENT_ALGORITHM_VERSION, currentEpochMs),
        )
        analysisStateDao.upsert(
            StageStatus.Done.toEntity(mediaId, AnalysisStage.SEGMENTATION, CURRENT_ALGORITHM_VERSION, currentEpochMs),
        )

        // 3 チャンクのうち、1つ目と3つ目はすでに whisper-tiny-lid 判定済み、2つ目だけ未判定（まだら状態）
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 1L, mediaId = mediaId.value, startMs = 0L, endMs = 4_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 2L, mediaId = mediaId.value, startMs = 5_000L, endMs = 9_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 3L, mediaId = mediaId.value, startMs = 10_000L, endMs = 14_000L, origin = ChunkOrigin.AUTO.name),
            ),
        )
        transcriptDao.upsertAll(
            listOf(
                TranscriptEntity(
                    chunkId = 1L,
                    text = "",
                    language = "ja",
                    languageConfidence = 0.9f,
                    engine = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID.value,
                    createdAt = currentEpochMs,
                ),
                TranscriptEntity(
                    chunkId = 3L,
                    text = "",
                    language = "ko",
                    languageConfidence = 0.9f,
                    engine = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID.value,
                    createdAt = currentEpochMs,
                ),
            ),
        )

        val evaluatedRanges = mutableListOf<ChunkTimeRange>()
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult? = null

            override suspend fun detectChunkLanguages(
                audioFile: File,
                chunks: List<ChunkTimeRange>,
                languagePair: LanguagePair,
                parallelism: Int,
                computeDispatcher: CoroutineDispatcher,
                onResult: suspend (index: Int, result: OnDeviceChunkTranscriptionResult?) -> Unit,
            ) {
                chunks.forEachIndexed { index, range ->
                    evaluatedRanges += range
                    onResult(
                        index,
                        OnDeviceChunkTranscriptionResult(
                            text = "",
                            language = LanguageTag("ko"),
                            confidence = 0.88f,
                            engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
                        ),
                    )
                }
            }
        }

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = DefaultAnalysisRepository(
            context = context,
            analysisStateDao = analysisStateDao,
            chunkDao = chunkDao,
            transcriptDao = transcriptDao,
            itemStateDao = itemStateDao,
            mediaItemDao = mediaItemDao,
            mediaFileResolver = mediaFileLocator,
            ioDispatcher = dispatcher,
            onDeviceChunkTranscriber = fakeTranscriber,
            bookmarkDao = bookmarkDao,
            clock = { currentEpochMs },
            computeDispatcher = dispatcher,
        )

        repository.prioritizeOpenedMediaAnalysis(mediaId)
        testScheduler.advanceUntilIdle()

        // すでに判定済みの chunk 1, 3 は再推論せず、未判定の chunk 2 (5000..9000) のみ補完推論されること
        assertEquals(listOf(ChunkTimeRange(5_000L, 9_000L)), evaluatedRanges)
        val finalTranscripts = repository.observeTranscripts(mediaId).first()
        assertEquals(LanguageTag("ja"), finalTranscripts[ChunkId(1L)]?.language)
        assertEquals(LanguageTag("ko"), finalTranscripts[ChunkId(2L)]?.language)
        assertEquals(LanguageTag("ko"), finalTranscripts[ChunkId(3L)]?.language)
    }

    @Test
    fun `全体設定のbackgroundTranscriptionEnabledがオフのときは画面を閉じるとバックグラウンド文字起こしを行わない`() = runTest {
        val mediaId = MediaId("bg_disabled_01")
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        File(mediaDir, "${mediaId.value}.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Background Disabled Lesson",
                durationMs = 10_000L,
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
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 1L, mediaId = mediaId.value, startMs = 0L, endMs = 4_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 2L, mediaId = mediaId.value, startMs = 5_000L, endMs = 9_000L, origin = ChunkOrigin.AUTO.name),
            ),
        )

        var transcribeCallCount = 0
        val fakeTranscriber = object : OnDeviceChunkTranscriber {
            override fun isAvailable(): Boolean = true

            override suspend fun transcribeChunk(
                audioFile: File,
                startMs: Long,
                endMs: Long,
                languagePair: LanguagePair?,
                itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
                engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
            ): OnDeviceChunkTranscriptionResult {
                transcribeCallCount += 1
                return OnDeviceChunkTranscriptionResult(
                    text = "hello",
                    language = LanguageTag("en"),
                    confidence = 0.9f,
                    engineId = com.miuraken.chunkflow.core.model.EngineId("test"),
                )
            }
        }
        val settingsRepo = FakeUserSettingsRepository(
            com.miuraken.chunkflow.core.model.UserSettings(backgroundTranscriptionEnabled = false),
        )
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val repository = DefaultAnalysisRepository(
            context = context,
            analysisStateDao = analysisStateDao,
            chunkDao = chunkDao,
            transcriptDao = transcriptDao,
            itemStateDao = itemStateDao,
            mediaItemDao = mediaItemDao,
            mediaFileResolver = mediaFileLocator,
            ioDispatcher = dispatcher,
            onDeviceChunkTranscriber = fakeTranscriber,
            userSettingsRepository = settingsRepo,
            bookmarkDao = bookmarkDao,
            clock = { currentEpochMs },
            computeDispatcher = dispatcher,
        )

        repository.setMediaTranscriptionSessionOpen(mediaId, true)
        repository.setLookaheadEnabled(mediaId, true)
        assertFalse(repository.observeLookaheadStatus(mediaId).first().backgroundEnabled)

        // 画面を閉じた状態で triggerLookaheadAt を呼んでもバックグラウンド文字起こしは実行されない
        repository.setMediaTranscriptionSessionOpen(mediaId, false)
        repository.triggerLookaheadAt(mediaId, ChunkId(1L))
        testScheduler.advanceUntilIdle()
        assertEquals(0, transcribeCallCount)
    }

    @Test
    fun `addChunkとdeleteChunks_無音トリミング付きで時系列順にチャンクを追加し複数チャンクを一括解除できる`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_add_delete_chunks_01")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Add and Delete Chunks Test",
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
        chunkDao.insertAll(
            listOf(
                ChunkEntity(id = 10L, mediaId = mediaId.value, startMs = 0L, endMs = 1_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 20L, mediaId = mediaId.value, startMs = 4_000L, endMs = 5_000L, origin = ChunkOrigin.AUTO.name),
                ChunkEntity(id = 30L, mediaId = mediaId.value, startMs = 6_000L, endMs = 7_000L, origin = ChunkOrigin.AUTO.name),
            ),
        )
        transcriptDao.upsertAll(
            listOf(
                Transcript(
                    chunkId = ChunkId(10L),
                    text = "first",
                    language = LanguageTag("ja"),
                    languageConfidence = 0.9f,
                    engine = EngineId("test"),
                    userEdited = true,
                ).toEntity(),
                Transcript(
                    chunkId = ChunkId(20L),
                    text = "second",
                    language = LanguageTag("ko"),
                    languageConfidence = 0.9f,
                    engine = EngineId("test"),
                    userEdited = true,
                ).toEntity(),
                Transcript(
                    chunkId = ChunkId(30L),
                    text = "third",
                    language = LanguageTag("ko"),
                    languageConfidence = 0.9f,
                    engine = EngineId("test"),
                    userEdited = true,
                ).toEntity(),
            ),
        )

        // 特徴量ファイル: 1800..2600ms に有音 (-15dB)、それ以外は無音 (-65dB)
        val analysisDir = File(File(context.filesDir, "analysis"), mediaId.value).apply { mkdirs() }
        val logEnergy = FloatArray(1_000) { frame ->
            val ms = frame * 10
            if (ms in 1_800 until 2_600) -15f else -65f
        }
        AnalysisBinaryFormat.writeFeaturesAtomic(
            File(analysisDir, "features.bin"),
            StoredFeatures(
                binMs = 10,
                logEnergyDb = logEnergy,
            ),
        )

        // 1. チャンク間 [1500..3000ms] にチャンクを追加すると、左右の無音がトリミングされて 1 番目と 2 番目の間に挿入される
        assertTrue(repository.addChunk(mediaId, startMs = 1_500L, endMs = 3_000L))
        val afterAdd = repository.observeChunks(mediaId).first()
        assertEquals(4, afterAdd.size)
        assertEquals(0L to 1_000L, afterAdd[0].startMs to afterAdd[0].endMs)
        assertTrue(afterAdd[1].startMs in 1_750L..1_820L)
        assertTrue(afterAdd[1].endMs in 2_580L..2_650L)
        assertEquals(4_000L to 5_000L, afterAdd[2].startMs to afterAdd[2].endMs)
        assertEquals(6_000L to 7_000L, afterAdd[3].startMs to afterAdd[3].endMs)

        // 無音しかない [7500..8500ms] にチャンクを追加すると、選択された全範囲がそのまま新チャンクになり、無音のみのため skippedEmptyChunkIds に入る
        assertTrue(repository.addChunk(mediaId, startMs = 7_500L, endMs = 8_500L))
        val afterAddSilent = repository.observeChunks(mediaId).first()
        assertEquals(5, afterAddSilent.size)
        assertEquals(7_500L to 8_500L, afterAddSilent[4].startMs to afterAddSilent[4].endMs)
        val lookaheadAfterAddSilent = repository.observeLookaheadStatus(mediaId).first()
        assertTrue(afterAddSilent[4].id in lookaheadAfterAddSilent.skippedEmptyChunkIds)
        assertFalse(afterAddSilent[1].id in lookaheadAfterAddSilent.skippedEmptyChunkIds)

        // 2. deleteChunks で中間の 2 チャンク（afterAddSilent[1], afterAddSilent[2]）を一括解除できる
        val idsToRemove = setOf(afterAddSilent[1].id, afterAddSilent[2].id)
        assertTrue(repository.deleteChunks(mediaId, idsToRemove))
        val afterDelete = repository.observeChunks(mediaId).first()
        assertEquals(
            listOf(0L to 1_000L, 6_000L to 7_000L, 7_500L to 8_500L),
            afterDelete.map { it.startMs to it.endMs },
        )
        val remainingTranscripts = repository.observeTranscripts(mediaId).first()
        assertEquals("first", remainingTranscripts[afterDelete[0].id]?.text)
        assertEquals("third", remainingTranscripts[afterDelete[1].id]?.text)
        // チャンク削除による再採番後も、無音チャンクの skippedEmptyChunkIds は新しい ChunkId に引き継がれる
        val lookaheadAfterDelete = repository.observeLookaheadStatus(mediaId).first()
        assertTrue(afterDelete[2].id in lookaheadAfterDelete.skippedEmptyChunkIds)
    }

    @Test
    fun `addChunkとsplitChunkとresegment_featuresBinV2のspeechProbabilityを用いてBGM下でも発話区間に合わせてトリミングと分割を行う`() = runTest {
        val repository = createRepository(testScheduler)
        val mediaId = MediaId("media_silero_vad_bgm")
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "BGM Lesson",
                durationMs = 5_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 100L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = null,
                targetLang = "zh-CN",
                baseLang = "ja-JP",
                transcriptOffsetMs = 0,
                importedAt = currentEpochMs,
            ),
        )

        // 全区間で -20dBFS の大音量 BGM が鳴っているが、speechProbability では [1000..1600ms] と [2400..3200ms] のみ発話
        val energy = FloatArray(500) { frame ->
            val ms = frame * 10
            if (ms in 1_000 until 1_600 || ms in 2_400 until 3_200) -14f else -20f
        }
        val speechProb = FloatArray(500) { frame ->
            val ms = frame * 10
            if (ms in 1_000 until 1_600 || ms in 2_400 until 3_200) 0.94f else 0.02f
        }
        val featuresFile = File(File(File(context.filesDir, "analysis"), mediaId.value), "features.bin")
        assertTrue(
            AnalysisBinaryFormat.writeFeaturesAtomic(
                featuresFile,
                StoredFeatures(binMs = 10, logEnergyDb = energy, speechProbability = speechProb),
            ),
        )

        // 1. resegment で 2 つの発話チャンクに分割される
        repository.resegment(mediaId, SegmentationParams())
        val autoChunks = repository.observeChunks(mediaId).first()
        assertEquals(2, autoChunks.size)

        // 2. 拡大波形の「チャンク生成」（addChunk）: [3400..4800ms] に新しい発話 [3800..4400ms] があるとき、前後の BGM (-20dBFS) がトリミングされる
        for (f in 380 until 440) {
            energy[f] = -14f
            speechProb[f] = 0.93f
        }
        assertTrue(
            AnalysisBinaryFormat.writeFeaturesAtomic(
                featuresFile,
                StoredFeatures(binMs = 10, logEnergyDb = energy, speechProbability = speechProb),
            ),
        )
        assertTrue(repository.addChunk(mediaId, startMs = 3_400L, endMs = 4_800L))
        val manualChunks = repository.observeChunks(mediaId).first()
        assertEquals(3, manualChunks.size)
        val added = manualChunks.last()
        assertTrue(added.startMs in 3_770L..3_800L, "added.startMs=${added.startMs}")
        assertTrue(added.endMs in 4_400L..4_430L, "added.endMs=${added.endMs}")
    }
}
