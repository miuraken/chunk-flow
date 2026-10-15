package com.miuraken.chunkflow.core.data.analysis

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.miuraken.chunkflow.core.audio.AnalysisBinaryFormat
import com.miuraken.chunkflow.core.audio.CURRENT_ALGORITHM_VERSION
import com.miuraken.chunkflow.core.audio.ChunkSegmenter
import com.miuraken.chunkflow.core.audio.FeatureAccumulator
import com.miuraken.chunkflow.core.audio.FrameFeatureExtractor
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.audio.StoredFeatures
import com.miuraken.chunkflow.core.data.library.LibraryMediaFileResolver
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.database.dao.AnalysisStateDao
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.entity.toEntity
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.media.AudioDecodeResult
import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import com.miuraken.chunkflow.core.model.AnalysisError
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 音声ファイルをストリーミングデコードし、波形・特徴量（`waveform.bin`, `features.bin`）と
 * 端末内自動分割チャンク（`AUTO`）を算出・永続化する [CoroutineWorker]（§7.3.1〜§7.3.4、ADR 0005、ADR 0006）。
 *
 * - デコード中は [PROGRESS_SAVE_INTERVAL_MS]（2,000ms）ごとに `waveform.bin` をアトミック更新し、
 *   [AnalysisStage.FEATURES] の進捗率を更新することで UI で波形を逐次描画できるようにする（F5.1）。
 * - デコード完了時に実デコード長 `durationMs` で `media_item.duration_ms` を補正する（ADR 0005 決定 5）。
 */
class AnalysisWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val audioStreamDecoder: AudioStreamDecoder,
    private val analysisStateDao: AnalysisStateDao,
    private val chunkDao: ChunkDao,
    private val mediaItemDao: MediaItemDao,
    private val mediaFileResolver: LibraryMediaFileResolver,
    private val userSettingsRepository: UserSettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : CoroutineWorker(appContext, workerParams) {

    private val chunkSegmenter: ChunkSegmenter = ChunkSegmenter()

    override suspend fun doWork(): Result {
        val rawMediaId = inputData.getString(KEY_MEDIA_ID)
        if (rawMediaId.isNullOrBlank() || !MediaFileLocator.isSafeIdentifier(rawMediaId)) {
            return Result.failure()
        }
        val mediaId = MediaId(rawMediaId)
        val requestedStages = parseRequestedStages(inputData.getStringArray(KEY_STAGES))

        return pipelineMutex.withLock {
            runPipeline(mediaId, requestedStages)
        }
    }

    private suspend fun runPipeline(
        mediaId: MediaId,
        requestedStages: Set<AnalysisStage>,
    ): Result {
        val audioFile = mediaFileResolver.resolveAudioFile(mediaId)
        val analysisDir = resolveAnalysisDir(mediaId, audioFile)
        if (audioFile == null || !audioFile.isFile || analysisDir == null) {
            markStagesFailed(mediaId, requestedStages, AnalysisError.IO)
            return Result.failure()
        }

        val waveformFile = File(analysisDir, DefaultAnalysisRepository.WAVEFORM_FILE_NAME)
        val featuresFile = File(analysisDir, DefaultAnalysisRepository.FEATURES_FILE_NAME)

        val needDecode = AnalysisStage.FEATURES in requestedStages || !featuresFile.isFile

        return try {
            var decodedDurationMs: Long? = null

            if (needDecode) {
                val now = clock()
                analysisStateDao.upsert(
                    StageStatus.Running(progress = 0f).toEntity(
                        mediaId = mediaId,
                        stage = AnalysisStage.FEATURES,
                        algorithmVersion = CURRENT_ALGORITHM_VERSION,
                        updatedAtEpochMs = now,
                    ),
                )

                val mediaItem = mediaItemDao.getById(mediaId.value)
                val expectedDurationMs = mediaItem?.durationMs?.takeIf { it > 0L }

                val accumulator = FeatureAccumulator(binMs = ChunkSegmenter.DEFAULT_BIN_MS)
                val extractor = FrameFeatureExtractor(
                    sampleRate = TARGET_SAMPLE_RATE_HZ,
                    sink = accumulator,
                )

                var lastCheckpointMs = now
                var lastProgressUpdateMs = now

                val decodeResult = audioStreamDecoder.decode(
                    file = audioFile,
                    sink = SampleSink { samples, count ->
                        extractor.process(samples, count)
                    },
                    outputSampleRate = TARGET_SAMPLE_RATE_HZ,
                    expectedDurationMs = expectedDurationMs,
                    onProgress = { progress ->
                        val currentMs = clock()
                        val shouldSaveWaveform = currentMs - lastCheckpointMs >= PROGRESS_SAVE_INTERVAL_MS
                        val shouldUpdateProgress = currentMs - lastProgressUpdateMs >= PROGRESS_DB_INTERVAL_MS

                        if (shouldSaveWaveform && accumulator.size > 0) {
                            AnalysisBinaryFormat.writeWaveformAtomic(
                                file = waveformFile,
                                waveform = accumulator.toWaveform(),
                            )
                            lastCheckpointMs = currentMs
                        }

                        if (shouldUpdateProgress || shouldSaveWaveform) {
                            lastProgressUpdateMs = currentMs
                            analysisStateDao.upsert(
                                StageStatus.Running(progress = progress.fraction).toEntity(
                                    mediaId = mediaId,
                                    stage = AnalysisStage.FEATURES,
                                    algorithmVersion = CURRENT_ALGORITHM_VERSION,
                                    updatedAtEpochMs = currentMs,
                                ),
                            )
                        }
                    },
                )

                when (decodeResult) {
                    is AudioDecodeResult.Failure -> {
                        markStagesFailed(mediaId, requestedStages, decodeResult.error)
                        return Result.failure()
                    }

                    is AudioDecodeResult.Success -> {
                        extractor.finish()
                        decodedDurationMs = decodeResult.durationMs

                        val waveformSaved = AnalysisBinaryFormat.writeWaveformAtomic(
                            file = waveformFile,
                            waveform = accumulator.toWaveform(),
                        )
                        val featuresSaved = AnalysisBinaryFormat.writeFeaturesAtomic(
                            file = featuresFile,
                            features = StoredFeatures(
                                binMs = ChunkSegmenter.DEFAULT_BIN_MS,
                                logEnergyDb = accumulator.logEnergyDb(),
                            ),
                        )
                        if (!waveformSaved || !featuresSaved) {
                            markStagesFailed(mediaId, requestedStages, AnalysisError.IO)
                            return Result.failure()
                        }

                        // ADR 0005 決定 5: デコード完了後に media_item.duration_ms を実デコード長で更新する
                        if (decodeResult.durationMs > 0L) {
                            val currentItem = mediaItemDao.getById(mediaId.value)
                            if (currentItem != null && currentItem.durationMs != decodeResult.durationMs) {
                                mediaItemDao.update(
                                    currentItem.copy(durationMs = decodeResult.durationMs),
                                )
                            }
                        }

                        analysisStateDao.upsert(
                            StageStatus.Done.toEntity(
                                mediaId = mediaId,
                                stage = AnalysisStage.FEATURES,
                                algorithmVersion = CURRENT_ALGORITHM_VERSION,
                                updatedAtEpochMs = clock(),
                            ),
                        )
                    }
                }
            }

            if (AnalysisStage.SEGMENTATION in requestedStages) {
                val segStartMs = clock()
                analysisStateDao.upsert(
                    StageStatus.Running(progress = 0f).toEntity(
                        mediaId = mediaId,
                        stage = AnalysisStage.SEGMENTATION,
                        algorithmVersion = CURRENT_ALGORITHM_VERSION,
                        updatedAtEpochMs = segStartMs,
                    ),
                )

                val storedFeatures = AnalysisBinaryFormat.readFeatures(featuresFile)
                if (storedFeatures == null) {
                    analysisStateDao.upsert(
                        StageStatus.Failed(AnalysisError.IO).toEntity(
                            mediaId = mediaId,
                            stage = AnalysisStage.SEGMENTATION,
                            algorithmVersion = CURRENT_ALGORITHM_VERSION,
                            updatedAtEpochMs = clock(),
                        ),
                    )
                    return Result.failure()
                }

                val params = readSegmentationParams()
                val durationMs = decodedDurationMs?.takeIf { it > 0L }
                    ?: mediaItemDao.getById(mediaId.value)?.durationMs?.takeIf { it > 0L }
                    ?: storedFeatures.durationMs

                val autoChunks = chunkSegmenter.segment(
                    logEnergyDb = storedFeatures.logEnergyDb,
                    params = params,
                    binMs = storedFeatures.binMs,
                    durationMs = durationMs,
                )
                val chunkEntities = autoChunks.map {
                    it.toEntity(
                        mediaId = mediaId,
                        algorithmVersion = CURRENT_ALGORITHM_VERSION,
                    )
                }

                chunkDao.replaceChunksTransaction(
                    mediaId = mediaId.value,
                    origin = ChunkOrigin.AUTO.name,
                    newChunks = chunkEntities,
                    newTranscriptsByIndex = emptyMap(),
                    preserveAllOldTranscripts = true,
                )

                analysisStateDao.upsert(
                    StageStatus.Done.toEntity(
                        mediaId = mediaId,
                        stage = AnalysisStage.SEGMENTATION,
                        algorithmVersion = CURRENT_ALGORITHM_VERSION,
                        updatedAtEpochMs = clock(),
                    ),
                )
            }

            Result.success()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            markStagesFailed(mediaId, requestedStages, AnalysisError.DECODE_FAILED)
            Result.failure()
        }
    }

    private suspend fun readSegmentationParams(): SegmentationParams = runCatching {
        userSettingsRepository.settings.first().defaultSegmentationParams
    }.getOrDefault(SegmentationParams())

    private suspend fun markStagesFailed(
        mediaId: MediaId,
        stages: Set<AnalysisStage>,
        error: AnalysisError,
    ) {
        val now = clock()
        val entities = stages.map { stage ->
            StageStatus.Failed(error).toEntity(
                mediaId = mediaId,
                stage = stage,
                algorithmVersion = CURRENT_ALGORITHM_VERSION,
                updatedAtEpochMs = now,
            )
        }
        analysisStateDao.upsertAll(entities)
    }

    private fun resolveAnalysisDir(mediaId: MediaId, audioFile: File?): File? {
        if (!MediaFileLocator.isSafeIdentifier(mediaId.value)) return null
        val baseDir = audioFile?.parentFile?.parentFile ?: applicationContext.filesDir
        val dir = File(File(baseDir, DefaultAnalysisRepository.ANALYSIS_DIR_NAME), mediaId.value)
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir
    }

    private fun parseRequestedStages(rawStages: Array<String>?): Set<AnalysisStage> {
        if (rawStages.isNullOrEmpty()) {
            return AnalysisStage.entries.toSet()
        }
        val parsed = rawStages.mapNotNull { name ->
            AnalysisStage.entries.firstOrNull { it.name == name }
        }.toSet()
        return parsed.ifEmpty { AnalysisStage.entries.toSet() }
    }

    companion object {
        const val KEY_MEDIA_ID: String = "media_id"
        const val KEY_STAGES: String = "stages"

        /** 複数ファイルの解析を 1 件ずつ順番に直列実行するための WorkManager ユニークワーク名。 */
        const val SEQUENTIAL_WORK_NAME: String = "chunkflow_analysis_sequential_queue"

        internal const val TARGET_SAMPLE_RATE_HZ: Int = 16_000
        internal const val PROGRESS_SAVE_INTERVAL_MS: Long = 2_000L
        internal const val PROGRESS_DB_INTERVAL_MS: Long = 500L

        /** 複数ファイルの解析パイプラインが同時に走らないよう直列化する Mutex（FIFO 順）。 */
        internal val pipelineMutex: Mutex = Mutex()

        /** WorkManager のユニークワーク名を返す。 */
        fun uniqueWorkName(mediaId: MediaId): String = "analysis_${mediaId.value}"

        /** WorkManager のタグを返す。 */
        fun tagForMedia(mediaId: MediaId): String = "analysis_tag_${mediaId.value}"
    }
}
