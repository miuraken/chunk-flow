package com.miuraken.chunkflow.core.data.analysis

import android.content.Context
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.miuraken.chunkflow.core.audio.AnalysisBinaryFormat
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.toStageAndStatus
import com.miuraken.chunkflow.core.media.AudioDecodeResult
import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import com.miuraken.chunkflow.core.media.DecodeProgress
import com.miuraken.chunkflow.core.model.AnalysisError
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.StageStatus
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
class AnalysisWorkerTest {

    private lateinit var context: Context
    private lateinit var analysisStateDao: FakeAnalysisStateDao
    private lateinit var transcriptDao: FakeTranscriptDao
    private lateinit var chunkDao: FakeChunkDao
    private lateinit var mediaItemDao: FakeMediaItemDao
    private lateinit var userSettingsRepository: FakeUserSettingsRepository
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
        mediaItemDao = FakeMediaItemDao()
        userSettingsRepository = FakeUserSettingsRepository()
        mediaFileLocator = MediaFileLocator(context.filesDir)
    }

    private fun createWorkerParameters(inputData: Data): WorkerParameters {
        val executor = Executors.newSingleThreadExecutor()
        val constructors = WorkerParameters::class.java.declaredConstructors
        val ctor = constructors.first()
        ctor.isAccessible = true
        val args = ctor.parameterTypes.map { paramType ->
            when {
                paramType == UUID::class.java -> UUID.randomUUID()
                paramType == Data::class.java -> inputData
                Collection::class.java.isAssignableFrom(paramType) -> emptySet<String>()
                paramType == Int::class.javaPrimitiveType -> 1
                paramType == java.util.concurrent.Executor::class.java -> executor
                paramType.name.contains("CoroutineContext") -> kotlinx.coroutines.Dispatchers.Unconfined
                paramType == WorkerParameters.RuntimeExtras::class.java -> WorkerParameters.RuntimeExtras()
                paramType.isInterface -> ReflectionHelpers.createNullProxy(paramType)
                else -> null
            }
        }.toTypedArray()
        return ctor.newInstance(*args) as WorkerParameters
    }

    @Test
    fun `doWork_正常デコード時にwaveformBinとfeaturesBinとAUTOチャンクを保存しdurationMsを補正する`() = runTest {
        val mediaId = MediaId("media_worker_01")
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        File(mediaDir, "${mediaId.value}.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))

        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "Worker Test",
                durationMs = 1_000L, // 初期のメタデータ尺 (デコード後に 2,000ms に補正される想定)
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

        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                // 2.0 秒間 (32,000 サンプル @ 16kHz): 0..0.8s 発話、0.8..1.3s 無音、1.3..2.0s 発話
                val totalSamples = outputSampleRate * 2
                val samples = FloatArray(totalSamples) { i ->
                    val timeSec = i.toDouble() / outputSampleRate
                    if (timeSec in 0.8..1.3) {
                        0f
                    } else {
                        (0.5 * sin(2.0 * PI * 440.0 * timeSec)).toFloat()
                    }
                }
                sink.onSamples(samples, samples.size)
                currentEpochMs += 2_500L
                onProgress(
                    DecodeProgress(
                        decodedDurationMs = 1_000L,
                        totalDurationMs = expectedDurationMs,
                        fraction = 0.5f,
                    ),
                )
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = totalSamples.toLong(),
                    resampledSamples = totalSamples.toLong(),
                    durationMs = 2_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/mpeg",
                )
            }
        }

        val inputData = Data.Builder()
            .putString(AnalysisWorker.KEY_MEDIA_ID, mediaId.value)
            .putStringArray(
                AnalysisWorker.KEY_STAGES,
                arrayOf(AnalysisStage.FEATURES.name, AnalysisStage.SEGMENTATION.name),
            )
            .build()

        val factory = AnalysisWorkerFactory(
            audioStreamDecoder = fakeDecoder,
            analysisStateDao = analysisStateDao,
            chunkDao = chunkDao,
            mediaItemDao = mediaItemDao,
            mediaFileResolver = mediaFileLocator,
            userSettingsRepository = userSettingsRepository,
            clock = { currentEpochMs },
        )
        val worker = factory.createWorker(
            appContext = context,
            workerClassName = AnalysisWorker::class.java.name,
            workerParameters = createWorkerParameters(inputData),
        ) as AnalysisWorker

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val analysisDir = File(File(context.filesDir, "analysis"), mediaId.value)
        val waveformFile = File(analysisDir, "waveform.bin")
        val featuresFile = File(analysisDir, "features.bin")
        assertTrue(waveformFile.isFile)
        assertTrue(featuresFile.isFile)

        val waveform = AnalysisBinaryFormat.readWaveform(waveformFile)
        val features = AnalysisBinaryFormat.readFeatures(featuresFile)
        assertNotNull(waveform)
        assertNotNull(features)
        assertTrue(waveform.peak.isNotEmpty())
        assertEquals(200, features.frameCount)

        val autoChunks = chunkDao.getByMediaIdAndOrigin(mediaId.value, ChunkOrigin.AUTO.name)
        assertTrue(autoChunks.isNotEmpty())

        val updatedMediaItem = mediaItemDao.getById(mediaId.value)
        assertNotNull(updatedMediaItem)
        assertEquals(2_000L, updatedMediaItem.durationMs)

        val statuses = analysisStateDao.getByMediaId(mediaId.value)
            .mapNotNull { it.toStageAndStatus() }
            .toMap()
        assertEquals(StageStatus.Done, statuses[AnalysisStage.FEATURES])
        assertEquals(StageStatus.Done, statuses[AnalysisStage.SEGMENTATION])
    }

    @Test
    fun `doWork_デコードエラー時にFailed状態を保存してResult_failureを返す`() = runTest {
        val mediaId = MediaId("media_worker_err")
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        File(mediaDir, "${mediaId.value}.mp3").writeBytes(byteArrayOf(0, 0, 0))

        val failingDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult = AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED)
        }

        val inputData = Data.Builder()
            .putString(AnalysisWorker.KEY_MEDIA_ID, mediaId.value)
            .build()

        val worker = AnalysisWorker(
            appContext = context,
            workerParams = createWorkerParameters(inputData),
            audioStreamDecoder = failingDecoder,
            analysisStateDao = analysisStateDao,
            chunkDao = chunkDao,
            mediaItemDao = mediaItemDao,
            mediaFileResolver = mediaFileLocator,
            userSettingsRepository = userSettingsRepository,
            clock = { currentEpochMs },
        )

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.failure(), result)

        val statuses = analysisStateDao.getByMediaId(mediaId.value)
            .mapNotNull { it.toStageAndStatus() }
            .toMap()
        assertEquals(StageStatus.Failed(AnalysisError.DECODE_FAILED), statuses[AnalysisStage.FEATURES])
        assertEquals(StageStatus.Failed(AnalysisError.DECODE_FAILED), statuses[AnalysisStage.SEGMENTATION])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `doWork_複数のAnalysisWorkerが同時に起動されてもpipelineMutexにより1件ずつ順番に直列実行される`() = runTest {
        assertEquals("chunkflow_analysis_sequential_queue", AnalysisWorker.SEQUENTIAL_WORK_NAME)

        val mediaIds = listOf(
            MediaId("media_worker_seq_1"),
            MediaId("media_worker_seq_2"),
            MediaId("media_worker_seq_3"),
        )
        val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
        for (id in mediaIds) {
            File(mediaDir, "${id.value}.mp3").writeBytes(byteArrayOf(1, 2, 3, 4))
            mediaItemDao.upsert(
                MediaItemEntity(
                    id = id.value,
                    title = "Worker Seq ${id.value}",
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
                delay(40L)

                val totalSamples = outputSampleRate
                val samples = FloatArray(totalSamples) { i ->
                    val timeSec = i.toDouble() / outputSampleRate
                    (0.5 * sin(2.0 * PI * 440.0 * timeSec)).toFloat()
                }
                sink.onSamples(samples, samples.size)

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

        val deferreds = mediaIds.map { id ->
            val inputData = Data.Builder()
                .putString(AnalysisWorker.KEY_MEDIA_ID, id.value)
                .putStringArray(
                    AnalysisWorker.KEY_STAGES,
                    arrayOf(AnalysisStage.FEATURES.name, AnalysisStage.SEGMENTATION.name),
                )
                .build()
            val worker = AnalysisWorker(
                appContext = context,
                workerParams = createWorkerParameters(inputData),
                audioStreamDecoder = fakeDecoder,
                analysisStateDao = analysisStateDao,
                chunkDao = chunkDao,
                mediaItemDao = mediaItemDao,
                mediaFileResolver = mediaFileLocator,
                userSettingsRepository = userSettingsRepository,
                clock = { currentEpochMs },
            )
            async(UnconfinedTestDispatcher(testScheduler)) {
                worker.doWork()
            }
        }

        val results = deferreds.map { it.await() }
        assertTrue(results.all { it == ListenableWorker.Result.success() })
        assertEquals(1, maxActiveDecodes)
        assertEquals(
            listOf(
                "start:media_worker_seq_1",
                "end:media_worker_seq_1",
                "start:media_worker_seq_2",
                "end:media_worker_seq_2",
                "start:media_worker_seq_3",
                "end:media_worker_seq_3",
            ),
            executionLog,
        )
    }
}
