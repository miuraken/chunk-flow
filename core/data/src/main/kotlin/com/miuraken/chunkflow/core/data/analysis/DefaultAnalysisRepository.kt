package com.miuraken.chunkflow.core.data.analysis

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.miuraken.chunkflow.core.audio.AnalysisBinaryFormat
import com.miuraken.chunkflow.core.audio.CURRENT_ALGORITHM_VERSION
import com.miuraken.chunkflow.core.audio.ChunkSegmenter
import com.miuraken.chunkflow.core.audio.FeatureAccumulator
import com.miuraken.chunkflow.core.audio.FrameFeatureExtractor
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.audio.StoredFeatures
import com.miuraken.chunkflow.core.audio.TranscriptOffsetAligner
import com.miuraken.chunkflow.core.data.di.ChunkFlowDispatchers
import com.miuraken.chunkflow.core.data.di.Dispatcher
import com.miuraken.chunkflow.core.data.library.DefaultLibraryRepository
import com.miuraken.chunkflow.core.data.library.LibraryMediaFileResolver
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.database.dao.AnalysisStateDao
import com.miuraken.chunkflow.core.database.dao.BookmarkDao
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.dao.TranscriptDao
import com.miuraken.chunkflow.core.database.entity.BookmarkEntity
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import com.miuraken.chunkflow.core.database.entity.toEntity
import com.miuraken.chunkflow.core.database.entity.toExternalModel
import com.miuraken.chunkflow.core.database.entity.toStageAndStatus
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.media.AudioDecodeResult
import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriber
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriptionResult
import com.miuraken.chunkflow.core.model.AnalysisError
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.Bookmark
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.model.WordTiming
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [AnalysisRepository] の本番実装（§6.3、§6.4、§7.3、§7.4.5、§7.4.6、§10.6、F5.3、F6.2、F7.4、F8.1）。
 *
 * - `filesDir/analysis/<MediaId>/waveform.bin` と `features.bin` を [AnalysisBinaryFormat] で読み書きする。
 * - `item_state.chunk_source` に応じて表示するチャンクの出どころを切り替え、未存在時は優先順（`IMPORTED` → `MANUAL` → `AUTO`）にフォールバックする。
 * - `AUTO`（または `MANUAL`）チャンク表示時に `IMPORTED` チャンクの文字起こしが Room に存在する場合は、
 *   `media_item.transcript_offset_ms` を反映したうえで時間重なりが最大のチャンクへ対応付ける（§7.3、§10.6）。
 * - チャンク単位の言語手動変更・不明言語の一括設定・文字起こし時刻の自動合わせ・端末内先読み音声認識（F6.2）を提供する。
 * - チャンクの手動編集（分割・結合・境界移動、F5.3）、文字起こしテキストの手動修正（F7.4）、チャンクのブックマーク（F8.1）を提供する。
 */
@Singleton
class DefaultAnalysisRepository(
    @ApplicationContext private val context: Context,
    private val analysisStateDao: AnalysisStateDao,
    private val chunkDao: ChunkDao,
    private val transcriptDao: TranscriptDao,
    private val itemStateDao: ItemStateDao,
    private val mediaItemDao: MediaItemDao,
    private val mediaFileResolver: LibraryMediaFileResolver,
    @Dispatcher(ChunkFlowDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    private val onDeviceChunkTranscriber: OnDeviceChunkTranscriber = NoOpOnDeviceChunkTranscriber,
    private val bookmarkDao: BookmarkDao = NoOpBookmarkDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val audioStreamDecoder: AudioStreamDecoder? = null,
    private val userSettingsRepository: UserSettingsRepository? = null,
) : AnalysisRepository {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        analysisStateDao: AnalysisStateDao,
        chunkDao: ChunkDao,
        transcriptDao: TranscriptDao,
        itemStateDao: ItemStateDao,
        mediaItemDao: MediaItemDao,
        mediaFileResolver: LibraryMediaFileResolver,
        @Dispatcher(ChunkFlowDispatchers.IO) ioDispatcher: CoroutineDispatcher,
        onDeviceChunkTranscriber: OnDeviceChunkTranscriber,
        bookmarkDao: BookmarkDao,
        userSettingsRepository: UserSettingsRepository,
    ) : this(
        context = context,
        analysisStateDao = analysisStateDao,
        chunkDao = chunkDao,
        transcriptDao = transcriptDao,
        itemStateDao = itemStateDao,
        mediaItemDao = mediaItemDao,
        mediaFileResolver = mediaFileResolver,
        ioDispatcher = ioDispatcher,
        onDeviceChunkTranscriber = onDeviceChunkTranscriber,
        bookmarkDao = bookmarkDao,
        clock = System::currentTimeMillis,
        userSettingsRepository = userSettingsRepository,
    )

    private data class ChunkLangCacheKey(
        val mediaId: String,
        val startMs: Long,
        val endMs: Long,
        val language: String,
    )

    private data class CachedChunkTranscript(
        val text: String,
        val confidence: Float?,
        val engine: String,
    )

    private val chunkSegmenter: ChunkSegmenter = ChunkSegmenter()
    private val pipelineGate: PriorityAnalysisPipelineGate = AnalysisWorker.pipelineGate
    private val lookaheadMutex: Mutex = Mutex()
    private val pendingHighPriorityCount = AtomicInteger(0)
    private val recognitionSlotGate = PriorityRecognitionSlotGate(pendingHighPriorityCount)
    private val waveformRefreshEvents = MutableSharedFlow<MediaId>(extraBufferCapacity = 16)
    private val lookaheadStates = ConcurrentHashMap<MediaId, MutableStateFlow<LookaheadTranscriptionStatus>>()
    private val explicitlyConfiguredLookaheadMediaIds: MutableSet<MediaId> = ConcurrentHashMap.newKeySet()
    private val attemptedEmptyChunkIds = ConcurrentHashMap<MediaId, MutableSet<Long>>()
    private val chunkTranscriptByLangCache = ConcurrentHashMap<ChunkLangCacheKey, CachedChunkTranscript>()
    private val pendingHighPriorityChunkIds = ConcurrentHashMap<MediaId, MutableSet<Long>>()
    private val latestLookaheadChunkIndex = ConcurrentHashMap<MediaId, Int>()
    private val activeOpenMediaIds: MutableSet<MediaId> = ConcurrentHashMap.newKeySet()
    private val hasExplicitSessionTracking = AtomicBoolean(false)

    private fun isMediaOpenForTranscription(id: MediaId): Boolean = !hasExplicitSessionTracking.get() || activeOpenMediaIds.contains(id)

    override fun observeStatus(id: MediaId): Flow<Map<AnalysisStage, StageStatus>> = analysisStateDao.observeByMediaId(id.value)
        .map { entities ->
            entities.mapNotNull { it.toStageAndStatus() }.toMap()
        }
        .distinctUntilChanged()

    override fun observeWaveform(id: MediaId): Flow<Waveform?> {
        val statusTrigger = analysisStateDao.observeByMediaId(id.value)
        val manualTrigger = waveformRefreshEvents.onStart { emit(id) }
        return combine(statusTrigger, manualTrigger) { _, _ ->
            readWaveformFromDisk(id)
        }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)
    }

    override fun observeChunks(id: MediaId): Flow<List<Chunk>> = combine(
        itemStateDao.observeByMediaId(id.value),
        chunkDao.observeByMediaId(id.value),
    ) { itemState, allChunks ->
        selectActiveChunkEntities(itemState, allChunks).map { it.toExternalModel() }
    }.distinctUntilChanged()

    override fun observeAvailableChunkOrigins(id: MediaId): Flow<Set<ChunkOrigin>> = chunkDao.observeOriginsByMediaId(id.value)
        .map { rawOrigins ->
            rawOrigins.mapNotNull { raw ->
                ChunkOrigin.entries.firstOrNull { it.name == raw }
            }.toSet()
        }
        .distinctUntilChanged()

    override fun observeTranscripts(id: MediaId): Flow<Map<ChunkId, Transcript>> {
        return combine(
            mediaItemDao.observeById(id.value),
            itemStateDao.observeByMediaId(id.value),
            chunkDao.observeByMediaId(id.value),
            transcriptDao.observeByMediaId(id.value),
        ) { mediaItem, itemState, allChunks, allTranscripts ->
            val offsetMs = (mediaItem?.transcriptOffsetMs ?: 0).toLong()
            val activeChunks = selectActiveChunkEntities(itemState, allChunks)
            if (activeChunks.isEmpty() || allTranscripts.isEmpty()) {
                return@combine emptyMap()
            }

            val transcriptsByChunkId = allTranscripts.associateBy { it.chunkId }
            val result = LinkedHashMap<ChunkId, Transcript>()

            // AUTO / MANUAL チャンク表示時に IMPORTED チャンクの文字起こしが存在する場合、
            // 時間重なりが最大のチャンクへ対応付ける（§7.3、§10.6）。
            val isManualWithOwnTranscripts = activeChunks.firstOrNull()?.origin == ChunkOrigin.MANUAL.name &&
                activeChunks.any { transcriptsByChunkId.containsKey(it.id) }
            val importedChunks = allChunks.filter { it.origin == ChunkOrigin.IMPORTED.name }
            if (!isManualWithOwnTranscripts &&
                importedChunks.isNotEmpty() &&
                activeChunks.firstOrNull()?.origin != ChunkOrigin.IMPORTED.name
            ) {
                result.putAll(
                    mapOverlappingTranscripts(
                        activeChunks = activeChunks,
                        sourceChunks = importedChunks,
                        transcriptsByChunkId = transcriptsByChunkId,
                        offsetMs = offsetMs,
                    ),
                )
            }

            // アクティブチャンクに直接紐付く文字起こし（IMPORTED 表示時、または先読み認識・手動編集時）を優先して重ねる。
            for (chunk in activeChunks) {
                val entity = transcriptsByChunkId[chunk.id] ?: continue
                val chunkId = ChunkId(chunk.id)
                result[chunkId] = applyTranscriptOffset(entity.toExternalModel(), offsetMs)
            }

            result
        }.distinctUntilChanged()
    }

    override suspend fun requestAnalysis(id: MediaId, stages: Set<AnalysisStage>) {
        requestAnalysisInternal(
            id = id,
            stages = stages,
            explicitHighPriority = false,
        )
    }

    private suspend fun requestAnalysisInternal(
        id: MediaId,
        stages: Set<AnalysisStage>,
        explicitHighPriority: Boolean,
    ) {
        if (stages.isEmpty()) return
        withContext(ioDispatcher) {
            val now = clock()
            val existingByStage = analysisStateDao.getByMediaId(id.value)
                .mapNotNull { it.toStageAndStatus() }
                .toMap()

            val initialEntities = stages.map { stage ->
                val existing = existingByStage[stage]
                val status = if (existing is StageStatus.Running || existing is StageStatus.Done) {
                    existing
                } else {
                    StageStatus.NotStarted
                }
                status.toEntity(
                    mediaId = id,
                    stage = stage,
                    algorithmVersion = CURRENT_ALGORITHM_VERSION,
                    updatedAtEpochMs = now,
                )
            }
            analysisStateDao.upsertAll(initialEntities)

            val highPriority = explicitHighPriority || pipelineGate.isMediaOpen(id)
            runCatching {
                val inputData = Data.Builder()
                    .putString(AnalysisWorker.KEY_MEDIA_ID, id.value)
                    .putStringArray(
                        AnalysisWorker.KEY_STAGES,
                        stages.map { it.name }.toTypedArray(),
                    )
                    .putBoolean(AnalysisWorker.KEY_HIGH_PRIORITY, highPriority)
                    .build()
                val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
                    .setInputData(inputData)
                    .addTag(AnalysisWorker.tagForMedia(id))
                    .addTag(AnalysisWorker.uniqueWorkName(id))
                    .build()
                val workManager = WorkManager.getInstance(context)
                if (highPriority) {
                    workManager.enqueueUniqueWork(
                        AnalysisWorker.priorityWorkName(id),
                        ExistingWorkPolicy.REPLACE,
                        request,
                    )
                } else {
                    workManager.enqueueUniqueWork(
                        AnalysisWorker.SEQUENTIAL_WORK_NAME,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        request,
                    )
                }
            }

            val decoder = audioStreamDecoder
            if (decoder != null) {
                var hadToWaitOrPreempt = pipelineGate.isCurrentlyLocked()
                while (true) {
                    val effectiveHigh = explicitHighPriority || pipelineGate.isMediaOpen(id)
                    val completed = pipelineGate.withLock(highPriority = effectiveHigh) {
                        try {
                            runAnalysisPipeline(
                                mediaId = id,
                                requestedStages = stages,
                                decoder = decoder,
                                skipIfAlreadyDone = hadToWaitOrPreempt && !explicitHighPriority,
                            )
                            true
                        } catch (_: AnalysisPreemptedException) {
                            resetStagesToNotStarted(id, stages)
                            false
                        }
                    }
                    if (completed) break
                    hadToWaitOrPreempt = true
                }
            }
        }
    }

    private suspend fun runAnalysisPipeline(
        mediaId: MediaId,
        requestedStages: Set<AnalysisStage>,
        decoder: AudioStreamDecoder,
        skipIfAlreadyDone: Boolean = false,
    ) {
        val audioFile = mediaFileResolver.resolveAudioFile(mediaId)
        val analysisDir = resolveAnalysisDir(mediaId)?.apply { mkdirs() }
        if (audioFile == null || !audioFile.isFile || analysisDir == null || !analysisDir.exists()) {
            markStagesFailed(mediaId, requestedStages, AnalysisError.IO)
            return
        }

        val waveformFile = File(analysisDir, WAVEFORM_FILE_NAME)
        val featuresFile = File(analysisDir, FEATURES_FILE_NAME)
        if (skipIfAlreadyDone && waveformFile.isFile && featuresFile.isFile) {
            val existingByStage = analysisStateDao.getByMediaId(mediaId.value)
                .mapNotNull { it.toStageAndStatus() }
                .toMap()
            if (requestedStages.all { existingByStage[it] is StageStatus.Done }) {
                return
            }
        }

        val needDecode = AnalysisStage.FEATURES in requestedStages || !featuresFile.isFile

        try {
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
                    sampleRate = AnalysisWorker.TARGET_SAMPLE_RATE_HZ,
                    sink = accumulator,
                )

                var lastCheckpointMs = now
                var lastProgressUpdateMs = now

                val decodeResult = decoder.decode(
                    file = audioFile,
                    sink = SampleSink { samples, count ->
                        if (!pipelineGate.isMediaOpen(mediaId) && pipelineGate.hasHighPriorityWaiters()) {
                            throw AnalysisPreemptedException()
                        }
                        extractor.process(samples, count)
                    },
                    outputSampleRate = AnalysisWorker.TARGET_SAMPLE_RATE_HZ,
                    expectedDurationMs = expectedDurationMs,
                    onProgress = { progress ->
                        if (!pipelineGate.isMediaOpen(mediaId) && pipelineGate.hasHighPriorityWaiters()) {
                            throw AnalysisPreemptedException()
                        }
                        val currentMs = clock()
                        val shouldSaveWaveform =
                            currentMs - lastCheckpointMs >= AnalysisWorker.PROGRESS_SAVE_INTERVAL_MS
                        val shouldUpdateProgress =
                            currentMs - lastProgressUpdateMs >= AnalysisWorker.PROGRESS_DB_INTERVAL_MS

                        if (shouldSaveWaveform && accumulator.size > 0) {
                            AnalysisBinaryFormat.writeWaveformAtomic(
                                file = waveformFile,
                                waveform = accumulator.toWaveform(),
                            )
                            lastCheckpointMs = currentMs
                            notifyWaveformUpdated(mediaId)
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
                        return
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
                            return
                        }
                        notifyWaveformUpdated(mediaId)

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
                    return
                }

                val params = runCatching {
                    userSettingsRepository?.settings?.first()?.defaultSegmentationParams
                }.getOrNull() ?: SegmentationParams()

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
        } catch (ce: CancellationException) {
            throw ce
        } catch (pe: AnalysisPreemptedException) {
            throw pe
        } catch (_: Exception) {
            markStagesFailed(mediaId, requestedStages, AnalysisError.DECODE_FAILED)
        }
    }

    private suspend fun resetStagesToNotStarted(
        mediaId: MediaId,
        stages: Set<AnalysisStage>,
    ) {
        val now = clock()
        val existingByStage = analysisStateDao.getByMediaId(mediaId.value)
            .mapNotNull { it.toStageAndStatus() }
            .toMap()
        val entities = stages.mapNotNull { stage ->
            if (existingByStage[stage] is StageStatus.Done) {
                null
            } else {
                StageStatus.NotStarted.toEntity(
                    mediaId = mediaId,
                    stage = stage,
                    algorithmVersion = CURRENT_ALGORITHM_VERSION,
                    updatedAtEpochMs = now,
                )
            }
        }
        if (entities.isNotEmpty()) {
            analysisStateDao.upsertAll(entities)
        }
    }

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

    override suspend fun resegment(id: MediaId, params: SegmentationParams) {
        withContext(ioDispatcher) {
            val now = clock()
            val featuresFile = resolveAnalysisFile(id, FEATURES_FILE_NAME)
            val storedFeatures = featuresFile?.let { AnalysisBinaryFormat.readFeatures(it) }

            if (storedFeatures == null) {
                analysisStateDao.upsert(
                    StageStatus.Failed(AnalysisError.IO).toEntity(
                        mediaId = id,
                        stage = AnalysisStage.SEGMENTATION,
                        algorithmVersion = CURRENT_ALGORITHM_VERSION,
                        updatedAtEpochMs = now,
                    ),
                )
                return@withContext
            }

            analysisStateDao.upsert(
                StageStatus.Running(progress = 0f).toEntity(
                    mediaId = id,
                    stage = AnalysisStage.SEGMENTATION,
                    algorithmVersion = CURRENT_ALGORITHM_VERSION,
                    updatedAtEpochMs = now,
                ),
            )

            val mediaItem = mediaItemDao.getById(id.value)
            val totalDurationMs = mediaItem?.durationMs?.takeIf { it > 0L }
                ?: storedFeatures.durationMs

            val segments = chunkSegmenter.segment(
                logEnergyDb = storedFeatures.logEnergyDb,
                params = params,
                binMs = storedFeatures.binMs,
                durationMs = totalDurationMs,
            )
            val chunkEntities = segments.map {
                it.toEntity(
                    mediaId = id,
                    algorithmVersion = CURRENT_ALGORITHM_VERSION,
                )
            }
            chunkDao.replaceChunksTransaction(
                mediaId = id.value,
                origin = ChunkOrigin.AUTO.name,
                newChunks = chunkEntities,
                newTranscriptsByIndex = emptyMap(),
                preserveAllOldTranscripts = true,
            )

            attemptedEmptyChunkIds.remove(id)
            val completedAt = clock()
            analysisStateDao.upsert(
                StageStatus.Done.toEntity(
                    mediaId = id,
                    stage = AnalysisStage.SEGMENTATION,
                    algorithmVersion = CURRENT_ALGORITHM_VERSION,
                    updatedAtEpochMs = completedAt,
                ),
            )
        }
    }

    override suspend fun setChunkSource(id: MediaId, source: ChunkOrigin) {
        withContext(ioDispatcher) {
            val now = clock()
            val updatedRows = itemStateDao.updateChunkSource(
                mediaId = id.value,
                chunkSource = source.name,
                updatedAt = now,
            )
            if (updatedRows <= 0) {
                val existing = itemStateDao.getByMediaId(id.value)
                val entity = existing?.copy(
                    chunkSource = source.name,
                    updatedAt = now,
                ) ?: ItemStateEntity(
                    mediaId = id.value,
                    chunkSource = source.name,
                    updatedAt = now,
                )
                itemStateDao.upsert(entity)
            }
        }
    }

    override suspend fun setTranscriptOffset(id: MediaId, offsetMs: Int) {
        withContext(ioDispatcher) {
            mediaItemDao.updateTranscriptOffset(id.value, offsetMs)
        }
    }

    override suspend fun updateChunkLanguage(id: MediaId, chunkId: ChunkId, language: LanguageTag?) {
        withContext(ioDispatcher) {
            attemptedEmptyChunkIds[id]?.remove(chunkId.value)

            val allChunks = chunkDao.observeByMediaId(id.value).first()
            val targetChunk = allChunks.firstOrNull { it.id == chunkId.value }
            if (targetChunk != null) {
                evictChunkTranscriptCache(
                    mediaId = id.value,
                    startMs = targetChunk.startMs,
                    endMs = targetChunk.endMs,
                )
            }

            val existing = transcriptDao.getByChunkId(chunkId.value)
            val mediaItem = mediaItemDao.getById(id.value)
            val offsetMs = (mediaItem?.transcriptOffsetMs ?: 0).toLong()
            val overlappingImported = if (existing == null && targetChunk != null) {
                val importedChunks = allChunks.filter { it.origin == ChunkOrigin.IMPORTED.name }
                val importedTranscripts = transcriptDao.getByChunkIds(importedChunks.map { it.id })
                    .associateBy { it.chunkId }
                findBestOverlappingTranscript(
                    target = targetChunk,
                    sourceChunks = importedChunks,
                    transcriptsByChunkId = importedTranscripts,
                    offsetMs = offsetMs,
                )
            } else {
                null
            }

            val now = clock()
            val normalizedNewLang = language?.value?.trim()?.takeIf {
                it.isNotEmpty() && !it.equals("und", ignoreCase = true)
            }
            val clearedEntity = TranscriptEntity(
                chunkId = chunkId.value,
                text = "",
                textVariants = "{}",
                language = normalizedNewLang,
                languageConfidence = if (normalizedNewLang != null) 1.0f else null,
                words = "[]",
                engine = MANUAL_LANGUAGE_ENGINE_ID,
                userEdited = true,
                createdAt = now,
            )

            // 言語を切り替えた際は文字起こしデータを即座にクリアし（UI表示もいったん消す）、新しい言語バッジを反映する
            if (overlappingImported != null && overlappingImported.chunkId != chunkId.value) {
                transcriptDao.upsert(
                    overlappingImported.copy(
                        text = "",
                        textVariants = "{}",
                        language = normalizedNewLang,
                        languageConfidence = if (normalizedNewLang != null) 1.0f else null,
                        words = "[]",
                        userEdited = true,
                        createdAt = now,
                    ),
                )
            }
            transcriptDao.upsert(clearedEntity)

            val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
            val audioFile = mediaFileResolver.resolveAudioFile(id)
            if (available && audioFile != null && audioFile.isFile && targetChunk != null && targetChunk.endMs > targetChunk.startMs) {
                val stateFlow = lookaheadStateFor(id)
                val highPrioritySet = pendingHighPriorityChunkIds.computeIfAbsent(id) {
                    ConcurrentHashMap.newKeySet()
                }
                highPrioritySet.add(chunkId.value)
                updateQueuedChunkIdsState(id)

                val mediaLanguagePair = mediaItem?.let { item ->
                    val target = item.targetLang.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                    val base = item.baseLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                    target?.let { LanguagePair(target = it, base = base) }
                }
                val effectiveLanguagePair = if (normalizedNewLang != null) {
                    LanguagePair(target = LanguageTag(normalizedNewLang), base = null)
                } else {
                    mediaLanguagePair
                }

                try {
                    recognitionSlotGate.withHighPrioritySlot {
                        withContext(NonCancellable) {
                            highPrioritySet.remove(chunkId.value)
                            stateFlow.update { current ->
                                current.copy(
                                    engineAvailable = true,
                                    inFlightChunkId = chunkId,
                                    queuedChunkIds = current.queuedChunkIds - chunkId,
                                )
                            }
                            val recognized = try {
                                onDeviceChunkTranscriber.transcribeChunk(
                                    audioFile = audioFile,
                                    startMs = targetChunk.startMs,
                                    endMs = targetChunk.endMs,
                                    languagePair = effectiveLanguagePair,
                                    forceRefresh = true,
                                    highPriority = true,
                                )
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                                null
                            } finally {
                                stateFlow.update { current ->
                                    current.copy(
                                        inFlightChunkId = if (current.inFlightChunkId == chunkId) null else current.inFlightChunkId,
                                    )
                                }
                                updateQueuedChunkIdsState(id)
                            }

                            if (recognized != null && recognized.text.isNotBlank()) {
                                val resolvedLanguage = normalizedNewLang
                                    ?: recognized.language?.value?.takeIf {
                                        it.isNotBlank() && !it.equals("und", ignoreCase = true)
                                    }
                                    ?: effectiveLanguagePair?.target?.value
                                val resolvedConfidence = if (normalizedNewLang != null) {
                                    1.0f
                                } else {
                                    recognized.confidence
                                }
                                if (!resolvedLanguage.isNullOrBlank() && !resolvedLanguage.equals("und", ignoreCase = true)) {
                                    chunkTranscriptByLangCache[
                                        ChunkLangCacheKey(
                                            mediaId = id.value,
                                            startMs = targetChunk.startMs,
                                            endMs = targetChunk.endMs,
                                            language = resolvedLanguage.trim().lowercase(Locale.ROOT),
                                        ),
                                    ] = CachedChunkTranscript(
                                        text = recognized.text,
                                        confidence = resolvedConfidence,
                                        engine = recognized.engineId.value,
                                    )
                                }
                                transcriptDao.upsert(
                                    TranscriptEntity(
                                        chunkId = chunkId.value,
                                        text = recognized.text,
                                        textVariants = "{}",
                                        language = resolvedLanguage,
                                        languageConfidence = resolvedConfidence,
                                        words = "[]",
                                        engine = recognized.engineId.value,
                                        userEdited = normalizedNewLang != null,
                                        createdAt = clock(),
                                    ),
                                )
                            }
                            persistTranscriptBackupFile(id)
                        }
                    }
                } finally {
                    highPrioritySet.remove(chunkId.value)
                    withContext(NonCancellable) {
                        updateQueuedChunkIdsState(id)
                    }
                }
            } else {
                withContext(NonCancellable) {
                    persistTranscriptBackupFile(id)
                }
            }
        }
    }

    override suspend fun bulkSetUnknownTranscriptLanguage(id: MediaId, language: LanguageTag) {
        withContext(ioDispatcher) {
            transcriptDao.bulkSetUnknownLanguageForMedia(
                mediaId = id.value,
                language = language.value,
            )
            persistTranscriptBackupFile(id)
        }
    }

    override suspend fun autoAlignTranscriptOffset(id: MediaId): Int? = withContext(ioDispatcher) {
        val featuresFile = resolveAnalysisFile(id, FEATURES_FILE_NAME) ?: return@withContext null
        val storedFeatures = AnalysisBinaryFormat.readFeatures(featuresFile) ?: return@withContext null
        if (storedFeatures.logEnergyDb.isEmpty()) return@withContext null

        val allChunks = chunkDao.observeByMediaId(id.value).first()
        val importedChunks = allChunks
            .filter { it.origin == ChunkOrigin.IMPORTED.name }
            .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
        val targetChunks = if (importedChunks.isNotEmpty()) {
            importedChunks
        } else {
            val itemState = itemStateDao.getByMediaId(id.value)
            selectActiveChunkEntities(itemState, allChunks)
        }
        if (targetChunks.isEmpty()) return@withContext null

        val mediaItem = mediaItemDao.getById(id.value)
        val initialOffsetMs = mediaItem?.transcriptOffsetMs ?: 0
        val bestOffsetMs = TranscriptOffsetAligner.align(
            segmentStartMsList = targetChunks.map { it.startMs },
            energyDb = storedFeatures.logEnergyDb,
            hopMs = storedFeatures.binMs,
            initialOffsetMs = initialOffsetMs,
            searchRangeMs = TranscriptOffsetAligner.DEFAULT_SEARCH_RANGE_MS,
            stepMs = TranscriptOffsetAligner.DEFAULT_STEP_MS,
        )
        mediaItemDao.updateTranscriptOffset(id.value, bestOffsetMs)
        bestOffsetMs
    }

    override fun observeLookaheadStatus(id: MediaId): Flow<LookaheadTranscriptionStatus> = combine(
        lookaheadStateFor(id),
        onDeviceChunkTranscriber.recognitionModelIssues,
    ) { status, modelIssues ->
        if (status.recognitionModelIssues == modelIssues) status else status.copy(recognitionModelIssues = modelIssues)
    }.distinctUntilChanged()

    override suspend fun requestRecognitionModelDownload(language: LanguageTag): Boolean = runCatching {
        onDeviceChunkTranscriber.requestRecognitionModelDownload(language)
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        false
    }

    override suspend fun refreshRecognitionModelIssues() {
        try {
            onDeviceChunkTranscriber.refreshRecognitionModelIssues()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // 状態の問い合わせに失敗しても再生や文字起こしは続ける
        }
    }

    /**
     * 本文がなく、言語判定（Whisper Tiny）だけで言語を入れた文字起こしかどうか。
     *
     * 利用者が設定した言語ではないため、認識し直すときは両言語で判定し直す。
     */
    private fun TranscriptEntity.isLanguageIdOnly(): Boolean = text.isBlank() &&
        !userEdited &&
        engine == OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID.value

    /**
     * 言語判定のみの認識結果 [result]（音声認識モデルが使えず本文がない）をチャンク [chunkId] に保存する。
     *
     * 既存の本文や、利用者が設定した言語は上書きしない。
     */
    private suspend fun persistLanguageOnlyTranscript(
        chunkId: Long,
        result: OnDeviceChunkTranscriptionResult,
    ) {
        val language = result.language?.value?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
            ?: return
        val existing = transcriptDao.getByChunkId(chunkId)
        if (existing != null && (existing.text.isNotBlank() || existing.userEdited)) return
        if (existing != null && existing.language == language && existing.engine == result.engineId.value) return
        transcriptDao.upsert(
            TranscriptEntity(
                chunkId = chunkId,
                text = "",
                textVariants = existing?.textVariants ?: "{}",
                language = language,
                languageConfidence = result.confidence,
                words = existing?.words ?: "[]",
                engine = result.engineId.value,
                userEdited = false,
                createdAt = clock(),
            ),
        )
    }

    override suspend fun setLookaheadEnabled(id: MediaId, enabled: Boolean) {
        explicitlyConfiguredLookaheadMediaIds.add(id)
        attemptedEmptyChunkIds.remove(id)
        val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
        lookaheadStateFor(id).update { current ->
            current.copy(
                enabled = enabled,
                engineAvailable = available,
                inFlightChunkId = if (enabled) current.inFlightChunkId else null,
                queuedChunkIds = if (enabled && isMediaOpenForTranscription(id)) current.queuedChunkIds else emptySet(),
            )
        }
    }

    override fun setMediaTranscriptionSessionOpen(id: MediaId, open: Boolean) {
        hasExplicitSessionTracking.set(true)
        if (open) {
            val previouslyOpen = activeOpenMediaIds.toList()
            activeOpenMediaIds.clear()
            activeOpenMediaIds.add(id)
            pipelineGate.setMediaOpen(id, true)
            for (otherId in previouslyOpen) {
                if (otherId != id) {
                    pipelineGate.setMediaOpen(otherId, false)
                    pendingHighPriorityChunkIds[otherId]?.clear()
                    lookaheadStateFor(otherId).update { current ->
                        current.copy(queuedChunkIds = emptySet())
                    }
                }
            }
        } else {
            activeOpenMediaIds.remove(id)
            pipelineGate.setMediaOpen(id, false)
            pendingHighPriorityChunkIds[id]?.clear()
            lookaheadStateFor(id).update { current ->
                current.copy(queuedChunkIds = emptySet())
            }
        }
    }

    override suspend fun prioritizeOpenedMediaAnalysis(id: MediaId) {
        withContext(ioDispatcher) {
            val mediaItem = mediaItemDao.getById(id.value) ?: return@withContext
            val existingState = itemStateDao.getByMediaId(id.value)
            val accessedAt = maxOf(clock(), mediaItem.importedAt + 1L)
            if (existingState == null) {
                itemStateDao.upsert(
                    ItemStateEntity(
                        mediaId = id.value,
                        updatedAt = accessedAt,
                    ),
                )
            } else if (isItemUnaccessed(mediaItem, existingState, emptySet())) {
                itemStateDao.upsert(
                    existingState.copy(updatedAt = accessedAt),
                )
            }

            val audioFile = mediaFileResolver.resolveAudioFile(id)
            if (audioFile != null && audioFile.isFile) {
                val existingByStage = analysisStateDao.getByMediaId(id.value)
                    .mapNotNull { it.toStageAndStatus() }
                    .toMap()
                val waveformFile = resolveAnalysisFile(id, WAVEFORM_FILE_NAME)
                val featuresFile = resolveAnalysisFile(id, FEATURES_FILE_NAME)
                val needFeatures = existingByStage[AnalysisStage.FEATURES] !is StageStatus.Done ||
                    waveformFile?.isFile != true ||
                    featuresFile?.isFile != true
                val needSegmentation = existingByStage[AnalysisStage.SEGMENTATION] !is StageStatus.Done
                if (needFeatures || needSegmentation) {
                    requestAnalysisInternal(
                        id = id,
                        stages = setOf(AnalysisStage.FEATURES, AnalysisStage.SEGMENTATION),
                        explicitHighPriority = true,
                    )
                }
            }

            scheduleCourseAnalysisUpToLimit(mediaItem.course)
        }
    }

    internal suspend fun scheduleCourseAnalysisUpToLimit(course: String?) {
        val allItems = mediaItemDao.observeAll().first()
            .filter { it.course == course }
        if (allItems.isEmpty()) return

        val statesByMediaId = itemStateDao.observeAll().first()
            .associateBy { it.mediaId }

        val unaccessedItems = allItems
            .filter { item ->
                val itemId = MediaId(item.id)
                isItemUnaccessed(item, statesByMediaId[item.id], activeOpenMediaIds) &&
                    mediaFileResolver.resolveAudioFile(itemId)?.isFile == true
            }
            .sortedWith(
                compareBy<MediaItemEntity, String>(DefaultLibraryRepository.naturalFileNameComparator) { it.title }
                    .thenBy { it.id },
            )

        if (unaccessedItems.isEmpty()) return

        var activeOrDoneCount = 0
        val pendingCandidates = mutableListOf<MediaId>()
        for (item in unaccessedItems) {
            val itemId = MediaId(item.id)
            if (hasAnalysisScheduledOrDone(itemId)) {
                activeOrDoneCount += 1
            } else {
                pendingCandidates += itemId
            }
        }

        val slotsRemaining = (MAX_UNACCESSED_ANALYZED_PER_FOLDER - activeOrDoneCount).coerceAtLeast(0)
        for (candidateId in pendingCandidates.take(slotsRemaining)) {
            requestAnalysisInternal(
                id = candidateId,
                stages = setOf(AnalysisStage.FEATURES, AnalysisStage.SEGMENTATION),
                explicitHighPriority = false,
            )
        }
    }

    private suspend fun hasAnalysisScheduledOrDone(id: MediaId): Boolean {
        val byStage = analysisStateDao.getByMediaId(id.value)
            .mapNotNull { it.toStageAndStatus() }
            .toMap()
        val featuresStatus = byStage[AnalysisStage.FEATURES]
        val segStatus = byStage[AnalysisStage.SEGMENTATION]
        val featuresActiveOrDone = featuresStatus is StageStatus.NotStarted ||
            featuresStatus is StageStatus.Running ||
            featuresStatus is StageStatus.Done
        val segActiveOrDone = segStatus is StageStatus.NotStarted ||
            segStatus is StageStatus.Running ||
            segStatus is StageStatus.Done
        return featuresActiveOrDone && segActiveOrDone
    }

    override suspend fun triggerLookaheadAt(id: MediaId, currentChunkId: ChunkId?) {
        if (!isMediaOpenForTranscription(id)) {
            lookaheadStateFor(id).update { it.copy(queuedChunkIds = emptySet()) }
            return
        }
        withContext(ioDispatcher) {
            val allChunks = chunkDao.observeByMediaId(id.value).first()
            val itemState = itemStateDao.getByMediaId(id.value)
            val activeChunks = selectActiveChunkEntities(itemState, allChunks)
            if (activeChunks.isEmpty()) return@withContext
            val targetIndex = if (currentChunkId == null) {
                0
            } else {
                activeChunks.indexOfFirst { it.id == currentChunkId.value }
            }
            if (targetIndex < 0) return@withContext
            triggerLookaheadAt(id, targetIndex)
        }
    }

    override suspend fun prioritizeChunkTranscription(id: MediaId, chunkId: ChunkId) {
        if (!isMediaOpenForTranscription(id)) {
            lookaheadStateFor(id).update { it.copy(queuedChunkIds = emptySet()) }
            return
        }
        withContext(ioDispatcher) {
            attemptedEmptyChunkIds[id]?.remove(chunkId.value)

            val allChunks = chunkDao.observeByMediaId(id.value).first()
            val itemState = itemStateDao.getByMediaId(id.value)
            val activeChunks = selectActiveChunkEntities(itemState, allChunks)
            val targetIndex = activeChunks.indexOfFirst { it.id == chunkId.value }
            if (targetIndex >= 0) {
                latestLookaheadChunkIndex[id] = targetIndex
                updateQueuedChunkIdsState(id, activeChunks)
            }

            val targetChunk = activeChunks.getOrNull(targetIndex)
                ?: allChunks.firstOrNull { it.id == chunkId.value }
                ?: return@withContext
            if (targetChunk.endMs <= targetChunk.startMs) return@withContext

            val existing = transcriptDao.getByChunkId(chunkId.value)
            val mediaItem = mediaItemDao.getById(id.value)
            val offsetMs = (mediaItem?.transcriptOffsetMs ?: 0).toLong()
            val overlappingImported = if (existing == null) {
                val importedChunks = allChunks.filter { it.origin == ChunkOrigin.IMPORTED.name }
                val importedTranscripts = transcriptDao.getByChunkIds(importedChunks.map { it.id })
                    .associateBy { it.chunkId }
                findBestOverlappingTranscript(
                    target = targetChunk,
                    sourceChunks = importedChunks,
                    transcriptsByChunkId = importedTranscripts,
                    offsetMs = offsetMs,
                )
            } else {
                null
            }
            val baseExisting = existing ?: overlappingImported
            if (needsLookaheadTranscription(baseExisting) && isMediaOpenForTranscription(id)) {
                val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
                val audioFile = mediaFileResolver.resolveAudioFile(id)
                if (available && audioFile != null && audioFile.isFile) {
                    val stateFlow = lookaheadStateFor(id)
                    val highPrioritySet = pendingHighPriorityChunkIds.computeIfAbsent(id) {
                        ConcurrentHashMap.newKeySet()
                    }
                    highPrioritySet.add(chunkId.value)
                    updateQueuedChunkIdsState(id, activeChunks)

                    try {
                        recognitionSlotGate.withHighPrioritySlot {
                            highPrioritySet.remove(chunkId.value)
                            if (!isMediaOpenForTranscription(id)) {
                                updateQueuedChunkIdsState(id)
                                return@withHighPrioritySlot
                            }
                            val latestExisting = transcriptDao.getByChunkId(chunkId.value) ?: overlappingImported
                            if (!needsLookaheadTranscription(latestExisting)) {
                                updateQueuedChunkIdsState(id)
                                return@withHighPrioritySlot
                            }

                            withContext(NonCancellable) {
                                stateFlow.update { current ->
                                    current.copy(
                                        engineAvailable = true,
                                        inFlightChunkId = chunkId,
                                        queuedChunkIds = current.queuedChunkIds - chunkId,
                                    )
                                }

                                val assignedLang = latestExisting
                                    ?.takeUnless { it.isLanguageIdOnly() }
                                    ?.language?.trim()?.takeIf {
                                        it.isNotEmpty() && !it.equals("und", ignoreCase = true)
                                    }
                                val mediaLanguagePair = mediaItem?.let { item ->
                                    val target = item.targetLang.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                                    val base = item.baseLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                                    target?.let { LanguagePair(target = it, base = base) }
                                }
                                val effectiveLanguagePair = if (assignedLang != null) {
                                    LanguagePair(target = LanguageTag(assignedLang), base = null)
                                } else {
                                    mediaLanguagePair
                                }

                                val result = try {
                                    onDeviceChunkTranscriber.transcribeChunk(
                                        audioFile = audioFile,
                                        startMs = targetChunk.startMs,
                                        endMs = targetChunk.endMs,
                                        languagePair = effectiveLanguagePair,
                                        forceRefresh = false,
                                        highPriority = true,
                                    )
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (_: Exception) {
                                    null
                                } finally {
                                    stateFlow.update { current ->
                                        current.copy(
                                            inFlightChunkId = if (current.inFlightChunkId == chunkId) null else current.inFlightChunkId,
                                        )
                                    }
                                    updateQueuedChunkIdsState(id)
                                }

                                if (result != null && result.text.isNotBlank()) {
                                    val currentEntity = transcriptDao.getByChunkId(chunkId.value) ?: latestExisting
                                    val resolvedLanguage = assignedLang
                                        ?: result.language?.value?.takeIf {
                                            it.isNotBlank() && !it.equals("und", ignoreCase = true)
                                        }
                                        ?: effectiveLanguagePair?.target?.value
                                    val resolvedConfidence = if (currentEntity?.userEdited == true) {
                                        currentEntity.languageConfidence ?: result.confidence ?: 1.0f
                                    } else {
                                        result.confidence ?: currentEntity?.languageConfidence
                                    }
                                    if (!resolvedLanguage.isNullOrBlank() && !resolvedLanguage.equals("und", ignoreCase = true)) {
                                        chunkTranscriptByLangCache[
                                            ChunkLangCacheKey(
                                                mediaId = id.value,
                                                startMs = targetChunk.startMs,
                                                endMs = targetChunk.endMs,
                                                language = resolvedLanguage.trim().lowercase(Locale.ROOT),
                                            ),
                                        ] = CachedChunkTranscript(
                                            text = result.text,
                                            confidence = resolvedConfidence,
                                            engine = result.engineId.value,
                                        )
                                    }
                                    transcriptDao.upsert(
                                        TranscriptEntity(
                                            chunkId = chunkId.value,
                                            text = result.text,
                                            textVariants = currentEntity?.textVariants ?: "{}",
                                            language = resolvedLanguage,
                                            languageConfidence = resolvedConfidence,
                                            words = currentEntity?.words ?: "[]",
                                            engine = result.engineId.value,
                                            userEdited = currentEntity?.userEdited ?: false,
                                            createdAt = clock(),
                                        ),
                                    )
                                    persistTranscriptBackupFile(id)
                                } else if (result != null && result.isLanguageOnly) {
                                    persistLanguageOnlyTranscript(chunkId.value, result)
                                }
                            }
                        }
                    } finally {
                        highPrioritySet.remove(chunkId.value)
                        withContext(NonCancellable) {
                            updateQueuedChunkIdsState(id)
                        }
                    }
                }
            }

            if (targetIndex >= 0 && isMediaOpenForTranscription(id)) {
                triggerLookaheadAt(id, targetIndex)
            }
        }
    }

    override suspend fun retranscribeChunk(id: MediaId, chunkId: ChunkId) {
        withContext(ioDispatcher) {
            attemptedEmptyChunkIds[id]?.remove(chunkId.value)

            val allChunks = chunkDao.observeByMediaId(id.value).first()
            val targetChunk = allChunks.firstOrNull { it.id == chunkId.value } ?: return@withContext
            if (targetChunk.endMs <= targetChunk.startMs) return@withContext

            evictChunkTranscriptCache(
                mediaId = id.value,
                startMs = targetChunk.startMs,
                endMs = targetChunk.endMs,
            )

            val existing = transcriptDao.getByChunkId(chunkId.value)
            val mediaItem = mediaItemDao.getById(id.value)
            val offsetMs = (mediaItem?.transcriptOffsetMs ?: 0).toLong()
            val overlappingImported = if (existing == null) {
                val importedChunks = allChunks.filter { it.origin == ChunkOrigin.IMPORTED.name }
                val importedTranscripts = transcriptDao.getByChunkIds(importedChunks.map { it.id })
                    .associateBy { it.chunkId }
                findBestOverlappingTranscript(
                    target = targetChunk,
                    sourceChunks = importedChunks,
                    transcriptsByChunkId = importedTranscripts,
                    offsetMs = offsetMs,
                )
            } else {
                null
            }
            val baseExisting = existing ?: overlappingImported
            val assignedLang = baseExisting?.language?.trim()?.takeIf {
                it.isNotEmpty() && !it.equals("und", ignoreCase = true)
            }
            val wasUserEdited = baseExisting?.userEdited ?: false
            val now = clock()

            // 再文字起こし要求時は文字起こしデータを即座にクリアする（UI表示もいったん消す）
            if (overlappingImported != null && overlappingImported.chunkId != chunkId.value) {
                transcriptDao.upsert(
                    overlappingImported.copy(
                        text = "",
                        textVariants = "{}",
                        words = "[]",
                        createdAt = now,
                    ),
                )
            }
            transcriptDao.upsert(
                TranscriptEntity(
                    chunkId = chunkId.value,
                    text = "",
                    textVariants = "{}",
                    language = assignedLang,
                    languageConfidence = baseExisting?.languageConfidence,
                    words = "[]",
                    engine = baseExisting?.engine ?: MANUAL_LANGUAGE_ENGINE_ID,
                    userEdited = wasUserEdited,
                    createdAt = now,
                ),
            )

            val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
            val audioFile = mediaFileResolver.resolveAudioFile(id)
            if (!available || audioFile == null || !audioFile.isFile) {
                withContext(NonCancellable) {
                    persistTranscriptBackupFile(id)
                }
                return@withContext
            }

            val stateFlow = lookaheadStateFor(id)
            val highPrioritySet = pendingHighPriorityChunkIds.computeIfAbsent(id) {
                ConcurrentHashMap.newKeySet()
            }
            highPrioritySet.add(chunkId.value)
            updateQueuedChunkIdsState(id)

            try {
                recognitionSlotGate.withHighPrioritySlot {
                    withContext(NonCancellable) {
                        highPrioritySet.remove(chunkId.value)
                        evictChunkTranscriptCache(
                            mediaId = id.value,
                            startMs = targetChunk.startMs,
                            endMs = targetChunk.endMs,
                        )

                        val currentBeforeRecognize = transcriptDao.getByChunkId(chunkId.value) ?: baseExisting
                        val effectiveAssignedLang = currentBeforeRecognize?.language?.trim()?.takeIf {
                            it.isNotEmpty() && !it.equals("und", ignoreCase = true)
                        } ?: assignedLang
                        val mediaLanguagePair = mediaItem?.let { item ->
                            val target = item.targetLang.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                            val base = item.baseLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                            target?.let { LanguagePair(target = it, base = base) }
                        }
                        val effectiveLanguagePair = if (effectiveAssignedLang != null) {
                            LanguagePair(target = LanguageTag(effectiveAssignedLang), base = null)
                        } else {
                            mediaLanguagePair
                        }

                        stateFlow.update { current ->
                            current.copy(
                                engineAvailable = true,
                                inFlightChunkId = chunkId,
                                queuedChunkIds = current.queuedChunkIds - chunkId,
                            )
                        }
                        val recognized = try {
                            onDeviceChunkTranscriber.transcribeChunk(
                                audioFile = audioFile,
                                startMs = targetChunk.startMs,
                                endMs = targetChunk.endMs,
                                languagePair = effectiveLanguagePair,
                                forceRefresh = true,
                                highPriority = true,
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            null
                        } finally {
                            stateFlow.update { current ->
                                current.copy(
                                    inFlightChunkId = if (current.inFlightChunkId == chunkId) null else current.inFlightChunkId,
                                )
                            }
                            updateQueuedChunkIdsState(id)
                        }

                        if (recognized != null && recognized.text.isNotBlank()) {
                            val currentEntity = transcriptDao.getByChunkId(chunkId.value) ?: baseExisting
                            val resolvedLanguage = effectiveAssignedLang
                                ?: recognized.language?.value?.takeIf {
                                    it.isNotBlank() && !it.equals("und", ignoreCase = true)
                                }
                                ?: effectiveLanguagePair?.target?.value
                            val resolvedConfidence = if (currentEntity?.userEdited == true) {
                                currentEntity.languageConfidence ?: recognized.confidence ?: 1.0f
                            } else {
                                recognized.confidence ?: currentEntity?.languageConfidence
                            }
                            if (!resolvedLanguage.isNullOrBlank() && !resolvedLanguage.equals("und", ignoreCase = true)) {
                                chunkTranscriptByLangCache[
                                    ChunkLangCacheKey(
                                        mediaId = id.value,
                                        startMs = targetChunk.startMs,
                                        endMs = targetChunk.endMs,
                                        language = resolvedLanguage.trim().lowercase(Locale.ROOT),
                                    ),
                                ] = CachedChunkTranscript(
                                    text = recognized.text,
                                    confidence = resolvedConfidence,
                                    engine = recognized.engineId.value,
                                )
                            }
                            transcriptDao.upsert(
                                TranscriptEntity(
                                    chunkId = chunkId.value,
                                    text = recognized.text,
                                    textVariants = currentEntity?.textVariants ?: "{}",
                                    language = resolvedLanguage,
                                    languageConfidence = resolvedConfidence,
                                    words = currentEntity?.words ?: "[]",
                                    engine = recognized.engineId.value,
                                    userEdited = currentEntity?.userEdited ?: wasUserEdited,
                                    createdAt = clock(),
                                ),
                            )
                        }
                        persistTranscriptBackupFile(id)
                    }
                }
            } finally {
                highPrioritySet.remove(chunkId.value)
                withContext(NonCancellable) {
                    updateQueuedChunkIdsState(id)
                }
            }
        }
    }

    override suspend fun clearTranscriptsForMedia(id: MediaId) {
        withContext(ioDispatcher) {
            attemptedEmptyChunkIds.remove(id)
            pendingHighPriorityChunkIds.remove(id)
            latestLookaheadChunkIndex.remove(id)
            chunkTranscriptByLangCache.keys.removeIf { it.mediaId == id.value }

            transcriptDao.deleteByMediaId(id.value)
            chunkDao.deleteByMediaIdAndOrigin(id.value, ChunkOrigin.IMPORTED.name)

            val existingState = itemStateDao.getByMediaId(id.value)
            if (existingState != null && existingState.chunkSource == ChunkOrigin.IMPORTED.name) {
                itemStateDao.updateChunkSource(
                    mediaId = id.value,
                    chunkSource = ChunkOrigin.AUTO.name,
                    updatedAt = clock(),
                )
            }

            lookaheadStateFor(id).update {
                it.copy(
                    inFlightChunkId = null,
                    queuedChunkIds = emptySet(),
                    completedCount = 0,
                    totalTargetCount = 0,
                )
            }
            deleteTranscriptBackupFiles(id)
        }
    }

    private fun deleteTranscriptBackupFiles(mediaId: MediaId) {
        if (!MediaFileLocator.isSafeIdentifier(mediaId.value)) return
        val mediaDir = File(context.filesDir, "media")
        if (!mediaDir.exists() || !mediaDir.isDirectory) return
        val prefix = "${mediaId.value}.transcript."
        mediaDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(prefix)) {
                runCatching { file.delete() }
            }
        }
    }

    private fun evictChunkTranscriptCache(mediaId: String, startMs: Long, endMs: Long) {
        chunkTranscriptByLangCache.keys.removeIf { key ->
            key.mediaId == mediaId && key.startMs == startMs && key.endMs == endMs
        }
    }

    private fun needsLookaheadTranscription(existing: TranscriptEntity?): Boolean {
        val hasUndetectedLanguage = existing != null &&
            (existing.language.isNullOrBlank() || existing.language == "und") &&
            !(existing.userEdited && existing.text.isNotBlank())
        return existing == null ||
            (existing.text.isBlank() && !(existing.userEdited && existing.language == null)) ||
            hasUndetectedLanguage
    }

    private suspend fun computeLookaheadTargetChunks(
        id: MediaId,
        activeChunks: List<ChunkEntity>,
        startIndex: Int,
    ): List<ChunkEntity> {
        if (activeChunks.isEmpty() || startIndex !in activeChunks.indices) return emptyList()
        val skippedEmptyIds = attemptedEmptyChunkIds.computeIfAbsent(id) {
            ConcurrentHashMap.newKeySet()
        }
        val orderedCandidates = activeChunks.subList(startIndex, activeChunks.size) +
            activeChunks.subList(0, startIndex)
        val existingTranscripts = transcriptDao.getByChunkIds(orderedCandidates.map { it.id })
            .associateBy { it.chunkId }
        // プレイヤー画面で開いている間は、現在位置から後ろ、続けて先頭からの順で、残りのすべてのチャンクを対象にする
        return orderedCandidates.filter { chunk ->
            needsLookaheadTranscription(existingTranscripts[chunk.id]) &&
                !skippedEmptyIds.contains(chunk.id)
        }
    }

    private suspend fun updateQueuedChunkIdsState(
        id: MediaId,
        activeChunksOverride: List<ChunkEntity>? = null,
    ) {
        val stateFlow = lookaheadStateFor(id)
        if (!isMediaOpenForTranscription(id)) {
            stateFlow.update { current ->
                current.copy(queuedChunkIds = emptySet())
            }
            return
        }
        val highPriorityIds = pendingHighPriorityChunkIds[id]
            ?.map { ChunkId(it) }
            ?.toSet()
            .orEmpty()
        val lookaheadIds = if (stateFlow.value.enabled) {
            val startIndex = latestLookaheadChunkIndex[id]
            if (startIndex != null && startIndex >= 0) {
                val activeChunks = activeChunksOverride ?: run {
                    val allChunks = chunkDao.observeByMediaId(id.value).first()
                    val itemState = itemStateDao.getByMediaId(id.value)
                    selectActiveChunkEntities(itemState, allChunks)
                }
                computeLookaheadTargetChunks(id, activeChunks, startIndex)
                    .map { ChunkId(it.id) }
                    .toSet()
            } else {
                emptySet()
            }
        } else {
            emptySet()
        }
        stateFlow.update { current ->
            val combined = (highPriorityIds + lookaheadIds) - setOfNotNull(current.inFlightChunkId)
            current.copy(queuedChunkIds = combined)
        }
    }

    /**
     * アクティブチャンクのインデックス [currentChunkIndex] 以降から、未文字起こし・または言語未確定（かつ空結果として試行済みでない）チャンクを
     * プレイヤー画面で開いている間、残りのすべてのチャンクを順番に先読み認識する（F6.2、§7.4.5）。
     *
     * 各チャンクの認識前に高優先度の手動リクエスト（[retranscribeChunk] / [updateChunkLanguage] / [prioritizeChunkTranscription]）が
     * 待機している場合は認識スロットを譲り、また先読み中に起点インデックスが更新された場合は次のチャンクから即座に新しい起点へ切り替える。
     */
    suspend fun triggerLookaheadAt(id: MediaId, currentChunkIndex: Int) {
        if (currentChunkIndex < 0) return
        if (!isMediaOpenForTranscription(id)) {
            lookaheadStateFor(id).update { it.copy(queuedChunkIds = emptySet()) }
            return
        }
        withContext(ioDispatcher) {
            latestLookaheadChunkIndex[id] = currentChunkIndex
            updateQueuedChunkIdsState(id)

            lookaheadMutex.withLock {
                val stateFlow = lookaheadStateFor(id)
                if (id !in explicitlyConfiguredLookaheadMediaIds && userSettingsRepository != null) {
                    val defaultEnabled = runCatching {
                        userSettingsRepository.settings.first().lookaheadTranscriptionEnabled
                    }.getOrDefault(true)
                    if (stateFlow.value.enabled != defaultEnabled) {
                        stateFlow.update { it.copy(enabled = defaultEnabled) }
                    }
                }
                val currentStatus = stateFlow.value
                val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
                if (!currentStatus.enabled || !isMediaOpenForTranscription(id)) {
                    stateFlow.update { it.copy(queuedChunkIds = emptySet()) }
                    return@withLock
                }
                if (!available) {
                    stateFlow.update {
                        it.copy(
                            engineAvailable = false,
                            inFlightChunkId = null,
                            queuedChunkIds = emptySet(),
                        )
                    }
                    return@withLock
                }

                val allChunks = chunkDao.observeByMediaId(id.value).first()
                val itemState = itemStateDao.getByMediaId(id.value)
                var activeChunks = selectActiveChunkEntities(itemState, allChunks)
                val initialIndex = latestLookaheadChunkIndex[id] ?: currentChunkIndex
                if (initialIndex !in activeChunks.indices) return@withLock

                val audioFile = mediaFileResolver.resolveAudioFile(id)
                if (audioFile == null || !audioFile.isFile) return@withLock

                val skippedEmptyIds = attemptedEmptyChunkIds.computeIfAbsent(id) {
                    ConcurrentHashMap.newKeySet()
                }

                val initialTargets = computeLookaheadTargetChunks(id, activeChunks, initialIndex)
                if (initialTargets.isEmpty()) {
                    stateFlow.update { current ->
                        val highIds = pendingHighPriorityChunkIds[id]?.map { ChunkId(it) }?.toSet().orEmpty()
                        current.copy(
                            engineAvailable = true,
                            inFlightChunkId = if (pendingHighPriorityCount.get() > 0) current.inFlightChunkId else null,
                            queuedChunkIds = if (isMediaOpenForTranscription(id)) {
                                highIds - setOfNotNull(current.inFlightChunkId)
                            } else {
                                emptySet()
                            },
                            completedCount = activeChunks.size,
                            totalTargetCount = activeChunks.size,
                        )
                    }
                    return@withLock
                }

                val mediaItem = mediaItemDao.getById(id.value)
                val languagePair = mediaItem?.let { item ->
                    val target = item.targetLang.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                    val base = item.baseLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                    target?.let { LanguagePair(target = it, base = base) }
                }

                var completed = 0
                val totalTargets = initialTargets.size
                if (pendingHighPriorityCount.get() == 0 && isMediaOpenForTranscription(id)) {
                    val firstId = ChunkId(initialTargets.first().id)
                    val queuedIds = initialTargets.drop(1).map { ChunkId(it.id) }.toSet()
                    stateFlow.update {
                        it.copy(
                            engineAvailable = true,
                            inFlightChunkId = firstId,
                            queuedChunkIds = queuedIds,
                            completedCount = 0,
                            totalTargetCount = totalTargets,
                        )
                    }
                } else {
                    updateQueuedChunkIdsState(id, activeChunks)
                }

                // プレイヤー画面が開いている間は、残りのチャンクがなくなるまで順に処理する。
                // 同じバッチ内で同じチャンクを繰り返さないよう、試したチャンクを覚えておく
                val attemptedInBatch = HashSet<Long>()
                while (stateFlow.value.enabled && isMediaOpenForTranscription(id)) {
                    // 分割・連結でチャンクが作り直されることがあるため、毎回最新のチャンク一覧を読む
                    activeChunks = selectActiveChunkEntities(
                        itemStateDao.getByMediaId(id.value),
                        chunkDao.observeByMediaId(id.value).first(),
                    )
                    if (activeChunks.isEmpty()) break
                    val activeStartIndex = (latestLookaheadChunkIndex[id] ?: currentChunkIndex)
                        .coerceIn(0, activeChunks.lastIndex)
                    val remainingTargets = computeLookaheadTargetChunks(id, activeChunks, activeStartIndex)
                        .filterNot { it.id in attemptedInBatch }
                    val chunk = remainingTargets.firstOrNull() ?: break

                    if (chunk.endMs <= chunk.startMs) {
                        attemptedInBatch.add(chunk.id)
                        skippedEmptyIds.add(chunk.id)
                        completed += 1
                        continue
                    }

                    recognitionSlotGate.withNormalSlot {
                        if (!stateFlow.value.enabled || !isMediaOpenForTranscription(id)) {
                            stateFlow.update { current ->
                                current.copy(
                                    inFlightChunkId = null,
                                    queuedChunkIds = emptySet(),
                                )
                            }
                            return@withNormalSlot
                        }

                        // 高優先度スロットの実行中に起点インデックスや文字起こし状態が変わった場合は最新の先頭未認識チャンクを取り直す
                        val refreshedStartIndex = (latestLookaheadChunkIndex[id] ?: activeStartIndex)
                            .coerceIn(0, activeChunks.lastIndex)
                        val refreshedTargets = computeLookaheadTargetChunks(id, activeChunks, refreshedStartIndex)
                            .filterNot { it.id in attemptedInBatch }
                        val targetChunk = refreshedTargets.firstOrNull()
                        targetChunk?.let { attemptedInBatch.add(it.id) }
                        if (targetChunk == null) {
                            completed += 1
                            stateFlow.update { current ->
                                val highIds = pendingHighPriorityChunkIds[id]?.map { ChunkId(it) }?.toSet().orEmpty()
                                current.copy(
                                    inFlightChunkId = null,
                                    queuedChunkIds = if (isMediaOpenForTranscription(id)) highIds else emptySet(),
                                    completedCount = completed.coerceAtMost(totalTargets),
                                    totalTargetCount = totalTargets,
                                )
                            }
                            return@withNormalSlot
                        }

                        // 既に処理中（in-flight）に入った 1 チャンク分の処理と出力は、ウィンドウが閉じられても中断せず完了まで実行する
                        withContext(NonCancellable) {
                            val targetChunkId = ChunkId(targetChunk.id)
                            val queuedRest = if (isMediaOpenForTranscription(id)) {
                                refreshedTargets.drop(1).map { ChunkId(it.id) }.toSet() +
                                    pendingHighPriorityChunkIds[id]?.map { ChunkId(it) }?.toSet().orEmpty() -
                                    targetChunkId
                            } else {
                                emptySet()
                            }
                            stateFlow.update {
                                it.copy(
                                    engineAvailable = true,
                                    inFlightChunkId = targetChunkId,
                                    queuedChunkIds = queuedRest,
                                    completedCount = completed,
                                    totalTargetCount = totalTargets,
                                )
                            }

                            val existingBefore = transcriptDao.getByChunkId(targetChunk.id)
                            val existingBeforeLang = existingBefore?.language
                            // 利用者が設定した言語（本文なし）はその言語だけで認識する。言語判定のみの結果は仮の言語のため両言語で判定し直す
                            val effectiveLanguagePair = if (
                                existingBefore != null &&
                                existingBefore.text.isBlank() &&
                                !existingBefore.isLanguageIdOnly() &&
                                !existingBeforeLang.isNullOrBlank() &&
                                existingBeforeLang != "und"
                            ) {
                                LanguagePair(target = LanguageTag(existingBeforeLang), base = null)
                            } else {
                                languagePair
                            }

                            try {
                                // チャンク区間 [startMs, endMs] のみを切り出して認識し、チャンク間の無音はスキップする（F6.2）
                                val result: OnDeviceChunkTranscriptionResult? = try {
                                    onDeviceChunkTranscriber.transcribeChunk(
                                        audioFile = audioFile,
                                        startMs = targetChunk.startMs,
                                        endMs = targetChunk.endMs,
                                        languagePair = effectiveLanguagePair,
                                        forceRefresh = false,
                                        highPriority = false,
                                    )
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (_: Exception) {
                                    null
                                }

                                if (result != null && result.text.isNotBlank()) {
                                    skippedEmptyIds.remove(targetChunk.id)
                                    val existing = transcriptDao.getByChunkId(targetChunk.id)
                                    val resolvedLanguage = existing?.language?.takeIf { it.isNotBlank() && it != "und" }
                                        ?: result.language?.value
                                        ?: effectiveLanguagePair?.target?.value
                                    val resolvedConfidence = if (existing?.userEdited == true) {
                                        existing.languageConfidence ?: 1.0f
                                    } else {
                                        result.confidence
                                    }
                                    if (!resolvedLanguage.isNullOrBlank() && resolvedLanguage != "und") {
                                        chunkTranscriptByLangCache[
                                            ChunkLangCacheKey(
                                                mediaId = id.value,
                                                startMs = targetChunk.startMs,
                                                endMs = targetChunk.endMs,
                                                language = resolvedLanguage.trim().lowercase(Locale.ROOT),
                                            ),
                                        ] = CachedChunkTranscript(
                                            text = result.text,
                                            confidence = resolvedConfidence,
                                            engine = result.engineId.value,
                                        )
                                    }
                                    transcriptDao.upsert(
                                        TranscriptEntity(
                                            chunkId = targetChunk.id,
                                            text = result.text,
                                            textVariants = existing?.textVariants ?: "{}",
                                            language = resolvedLanguage,
                                            languageConfidence = resolvedConfidence,
                                            words = existing?.words ?: "[]",
                                            engine = result.engineId.value,
                                            userEdited = existing?.userEdited ?: false,
                                            createdAt = clock(),
                                        ),
                                    )
                                    persistTranscriptBackupFile(id)
                                } else if (result != null && result.isLanguageOnly) {
                                    // 音声認識モデルが使えず本文はないが、言語は判定できた。言語だけ保存し、このセッションでは再試行しない
                                    persistLanguageOnlyTranscript(targetChunk.id, result)
                                    if (stateFlow.value.enabled) {
                                        skippedEmptyIds.add(targetChunk.id)
                                    }
                                } else if (stateFlow.value.enabled) {
                                    skippedEmptyIds.add(targetChunk.id)
                                }
                            } finally {
                                completed += 1
                                val nextQueued = if (stateFlow.value.enabled && isMediaOpenForTranscription(id)) {
                                    computeLookaheadTargetChunks(
                                        id = id,
                                        activeChunks = activeChunks,
                                        startIndex = (latestLookaheadChunkIndex[id] ?: refreshedStartIndex)
                                            .coerceIn(0, activeChunks.lastIndex),
                                    ).map { ChunkId(it.id) }.toSet() +
                                        pendingHighPriorityChunkIds[id]?.map { ChunkId(it) }?.toSet().orEmpty()
                                } else {
                                    emptySet()
                                }
                                stateFlow.update {
                                    it.copy(
                                        inFlightChunkId = null,
                                        queuedChunkIds = nextQueued,
                                        completedCount = completed.coerceAtMost(totalTargets),
                                        totalTargetCount = totalTargets,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun observeBookmarks(id: MediaId): Flow<List<Bookmark>> = bookmarkDao.observeByMediaId(id.value)
        .map { list -> list.map { it.toExternalModel() } }
        .distinctUntilChanged()

    override suspend fun toggleChunkBookmark(id: MediaId, chunkId: ChunkId, note: String): Boolean = withContext(ioDispatcher) {
        val allChunks = chunkDao.observeByMediaId(id.value).first()
        val chunk = allChunks.firstOrNull { it.id == chunkId.value } ?: return@withContext false

        val existingBookmarks = bookmarkDao.getByMediaId(id.value)
        val matching = existingBookmarks.filter { bm ->
            bm.positionMs in chunk.startMs until chunk.endMs || bm.positionMs == chunk.startMs
        }
        if (matching.isNotEmpty()) {
            for (bm in matching) {
                bookmarkDao.deleteById(bm.id)
            }
            return@withContext false
        }

        val normalizedNote = truncateCodePoints(
            Normalizer.normalize(note, Normalizer.Form.NFC).trim(),
            MAX_BOOKMARK_NOTE_CODE_POINTS,
        )
        val midpointMs = (chunk.startMs + chunk.endMs) / 2L
        bookmarkDao.insert(
            BookmarkEntity(
                mediaId = id.value,
                positionMs = midpointMs.coerceAtLeast(0L),
                note = normalizedNote,
                createdAt = clock(),
            ),
        )
        true
    }

    override suspend fun updateChunkText(id: MediaId, chunkId: ChunkId, text: String) {
        withContext(ioDispatcher) {
            val normalizedText = truncateCodePoints(
                Normalizer.normalize(text, Normalizer.Form.NFC).trim(),
                MAX_TRANSCRIPT_TEXT_CODE_POINTS,
            )
            val now = clock()
            val existing = transcriptDao.getByChunkId(chunkId.value)
            if (existing != null) {
                transcriptDao.upsert(
                    existing.copy(
                        text = normalizedText,
                        userEdited = true,
                    ),
                )
            } else {
                val allChunks = chunkDao.observeByMediaId(id.value).first()
                val targetChunk = allChunks.firstOrNull { it.id == chunkId.value }
                val mediaItem = mediaItemDao.getById(id.value)
                val offsetMs = (mediaItem?.transcriptOffsetMs ?: 0).toLong()
                val overlappingImported = if (targetChunk != null) {
                    val importedChunks = allChunks.filter { it.origin == ChunkOrigin.IMPORTED.name }
                    val importedTranscripts = transcriptDao.getByChunkIds(importedChunks.map { it.id })
                        .associateBy { it.chunkId }
                    findBestOverlappingTranscript(
                        target = targetChunk,
                        sourceChunks = importedChunks,
                        transcriptsByChunkId = importedTranscripts,
                        offsetMs = offsetMs,
                    )
                } else {
                    null
                }

                if (overlappingImported != null) {
                    transcriptDao.upsert(
                        overlappingImported.copy(
                            chunkId = chunkId.value,
                            text = normalizedText,
                            userEdited = true,
                            createdAt = now,
                        ),
                    )
                } else {
                    transcriptDao.upsert(
                        TranscriptEntity(
                            chunkId = chunkId.value,
                            text = normalizedText,
                            textVariants = "{}",
                            language = mediaItem?.targetLang?.takeIf { it.isNotBlank() },
                            languageConfidence = null,
                            words = "[]",
                            engine = USER_EDIT_ENGINE_ID,
                            userEdited = true,
                            createdAt = now,
                        ),
                    )
                }
            }
            persistTranscriptBackupFile(id)
        }
    }

    override suspend fun splitChunk(id: MediaId, chunkId: ChunkId, splitPositionMs: Long): Boolean = withContext(ioDispatcher) {
        val allChunks = chunkDao.observeByMediaId(id.value).first()
        val itemState = itemStateDao.getByMediaId(id.value)
        val activeChunks = selectActiveChunkEntities(itemState, allChunks)
        val targetIndex = activeChunks.indexOfFirst { it.id == chunkId.value }
        if (targetIndex < 0) return@withContext false

        val target = activeChunks[targetIndex]
        if (splitPositionMs <= target.startMs || splitPositionMs >= target.endMs) {
            return@withContext false
        }

        val now = clock()
        val effectiveTranscripts = resolveEffectiveTranscriptsForActiveChunks(id, activeChunks, allChunks)
        val newChunks = ArrayList<ChunkEntity>(activeChunks.size + 1)
        val newTranscriptsByIndex = LinkedHashMap<Int, TranscriptEntity>()

        for ((index, chunk) in activeChunks.withIndex()) {
            if (index < targetIndex) {
                newChunks.add(chunk.copy(id = 0L, origin = ChunkOrigin.MANUAL.name))
                effectiveTranscripts[chunk.id]?.let { newTranscriptsByIndex[index] = it }
            } else if (index == targetIndex) {
                val firstHalfIndex = newChunks.size
                val secondHalfIndex = firstHalfIndex + 1
                newChunks.add(
                    chunk.copy(
                        id = 0L,
                        startMs = chunk.startMs,
                        endMs = splitPositionMs,
                        origin = ChunkOrigin.MANUAL.name,
                    ),
                )
                newChunks.add(
                    chunk.copy(
                        id = 0L,
                        startMs = splitPositionMs,
                        endMs = chunk.endMs,
                        origin = ChunkOrigin.MANUAL.name,
                    ),
                )

                val targetTranscript = effectiveTranscripts[chunk.id]
                if (targetTranscript != null) {
                    val domain = targetTranscript.toExternalModel()
                    if (domain.words.isNotEmpty()) {
                        val (firstWordsRaw, secondWordsRaw) = domain.words.partition { word ->
                            (word.startMs + word.endMs) / 2L < splitPositionMs
                        }
                        val firstWords = clampWordTimings(firstWordsRaw, chunk.startMs, splitPositionMs)
                        val secondWords = clampWordTimings(secondWordsRaw, splitPositionMs, chunk.endMs)
                        val separator = if (domain.text.contains(' ')) " " else ""
                        val firstText = truncateCodePoints(
                            firstWords.joinToString(separator) { it.text }.trim(),
                            MAX_TRANSCRIPT_TEXT_CODE_POINTS,
                        )
                        val secondText = truncateCodePoints(
                            secondWords.joinToString(separator) { it.text }.trim(),
                            MAX_TRANSCRIPT_TEXT_CODE_POINTS,
                        )

                        newTranscriptsByIndex[firstHalfIndex] = domain.copy(
                            text = firstText,
                            words = firstWords,
                            userEdited = true,
                        ).toEntity(createdAtEpochMs = now)

                        newTranscriptsByIndex[secondHalfIndex] = domain.copy(
                            text = secondText,
                            words = secondWords,
                            userEdited = true,
                        ).toEntity(createdAtEpochMs = now)
                    } else {
                        // words が空の場合は前半チャンクに元のテキスト（userEdited = true）を引き継ぐ
                        newTranscriptsByIndex[firstHalfIndex] = targetTranscript.copy(
                            userEdited = true,
                            createdAt = now,
                        )
                    }
                }
            } else {
                val newIdx = newChunks.size
                newChunks.add(chunk.copy(id = 0L, origin = ChunkOrigin.MANUAL.name))
                effectiveTranscripts[chunk.id]?.let { newTranscriptsByIndex[newIdx] = it }
            }
        }

        val insertedChunks = chunkDao.replaceChunksTransaction(
            mediaId = id.value,
            origin = ChunkOrigin.MANUAL.name,
            newChunks = newChunks,
            newTranscriptsByIndex = newTranscriptsByIndex,
            preserveAllOldTranscripts = true,
        )
        syncManualTranscriptsExact(insertedChunks, newTranscriptsByIndex)
        setChunkSource(id, ChunkOrigin.MANUAL)
        persistTranscriptBackupFile(id)
        true
    }

    override suspend fun mergeWithNextChunk(id: MediaId, chunkId: ChunkId): Boolean = withContext(ioDispatcher) {
        val allChunks = chunkDao.observeByMediaId(id.value).first()
        val itemState = itemStateDao.getByMediaId(id.value)
        val activeChunks = selectActiveChunkEntities(itemState, allChunks)
        val targetIndex = activeChunks.indexOfFirst { it.id == chunkId.value }
        if (targetIndex < 0 || targetIndex + 1 >= activeChunks.size) {
            return@withContext false
        }

        val target = activeChunks[targetIndex]
        val next = activeChunks[targetIndex + 1]
        val now = clock()
        val effectiveTranscripts = resolveEffectiveTranscriptsForActiveChunks(id, activeChunks, allChunks)

        val newChunks = ArrayList<ChunkEntity>(activeChunks.size - 1)
        val newTranscriptsByIndex = LinkedHashMap<Int, TranscriptEntity>()

        var index = 0
        while (index < activeChunks.size) {
            if (index == targetIndex) {
                val mergedIndex = newChunks.size
                val mergedStartMs = target.startMs
                val mergedEndMs = maxOf(next.endMs, mergedStartMs + 1L)
                newChunks.add(
                    target.copy(
                        id = 0L,
                        startMs = mergedStartMs,
                        endMs = mergedEndMs,
                        origin = ChunkOrigin.MANUAL.name,
                    ),
                )

                val mergedTranscript = mergeTranscriptEntities(
                    first = effectiveTranscripts[target.id],
                    second = effectiveTranscripts[next.id],
                    mergedStartMs = mergedStartMs,
                    mergedEndMs = mergedEndMs,
                    createdAtEpochMs = now,
                )
                if (mergedTranscript != null) {
                    newTranscriptsByIndex[mergedIndex] = mergedTranscript
                }
                index += 2
            } else {
                val chunk = activeChunks[index]
                val destIndex = newChunks.size
                newChunks.add(chunk.copy(id = 0L, origin = ChunkOrigin.MANUAL.name))
                effectiveTranscripts[chunk.id]?.let { newTranscriptsByIndex[destIndex] = it }
                index += 1
            }
        }

        val insertedChunks = chunkDao.replaceChunksTransaction(
            mediaId = id.value,
            origin = ChunkOrigin.MANUAL.name,
            newChunks = newChunks,
            newTranscriptsByIndex = newTranscriptsByIndex,
            preserveAllOldTranscripts = true,
        )
        syncManualTranscriptsExact(insertedChunks, newTranscriptsByIndex)
        setChunkSource(id, ChunkOrigin.MANUAL)
        persistTranscriptBackupFile(id)
        true
    }

    override suspend fun mergeChunks(id: MediaId, chunkIds: List<ChunkId>): Boolean = withContext(ioDispatcher) {
        if (chunkIds.size < 2) return@withContext false
        val allChunks = chunkDao.observeByMediaId(id.value).first()
        val itemState = itemStateDao.getByMediaId(id.value)
        val activeChunks = selectActiveChunkEntities(itemState, allChunks)
        val indices = chunkIds.map { chunkId -> activeChunks.indexOfFirst { it.id == chunkId.value } }.sorted()
        if (indices.any { it < 0 } || indices.distinct().size != indices.size) return@withContext false
        val firstIndex = indices.first()
        val lastIndex = indices.last()
        // 途中に選ばれていないチャンクを挟む（連続していない）場合は連結しない
        if (lastIndex - firstIndex + 1 != indices.size) return@withContext false

        val now = clock()
        val effectiveTranscripts = resolveEffectiveTranscriptsForActiveChunks(id, activeChunks, allChunks)
        val first = activeChunks[firstIndex]
        val mergedStartMs = first.startMs
        val mergedEndMs = maxOf(activeChunks[lastIndex].endMs, mergedStartMs + 1L)

        // 文字起こしは先頭から順に 2 件ずつ連結する（半角スペース 1 文字を挟み、言語は先頭のチャンクに合わせる）
        var mergedTranscript: TranscriptEntity? = effectiveTranscripts[first.id]
        for (index in (firstIndex + 1)..lastIndex) {
            mergedTranscript = mergeTranscriptEntities(
                first = mergedTranscript,
                second = effectiveTranscripts[activeChunks[index].id],
                mergedStartMs = mergedStartMs,
                mergedEndMs = mergedEndMs,
                createdAtEpochMs = now,
            )
        }

        val newChunks = ArrayList<ChunkEntity>(activeChunks.size - (lastIndex - firstIndex))
        val newTranscriptsByIndex = LinkedHashMap<Int, TranscriptEntity>()
        for ((index, chunk) in activeChunks.withIndex()) {
            when {
                index == firstIndex -> {
                    val mergedIndex = newChunks.size
                    newChunks.add(
                        chunk.copy(
                            id = 0L,
                            startMs = mergedStartMs,
                            endMs = mergedEndMs,
                            origin = ChunkOrigin.MANUAL.name,
                        ),
                    )
                    mergedTranscript?.let { newTranscriptsByIndex[mergedIndex] = it.copy(userEdited = true, createdAt = now) }
                }

                index in (firstIndex + 1)..lastIndex -> Unit

                else -> {
                    val destIndex = newChunks.size
                    newChunks.add(chunk.copy(id = 0L, origin = ChunkOrigin.MANUAL.name))
                    effectiveTranscripts[chunk.id]?.let { newTranscriptsByIndex[destIndex] = it }
                }
            }
        }

        val insertedChunks = chunkDao.replaceChunksTransaction(
            mediaId = id.value,
            origin = ChunkOrigin.MANUAL.name,
            newChunks = newChunks,
            newTranscriptsByIndex = newTranscriptsByIndex,
            preserveAllOldTranscripts = true,
        )
        syncManualTranscriptsExact(insertedChunks, newTranscriptsByIndex)
        setChunkSource(id, ChunkOrigin.MANUAL)
        persistTranscriptBackupFile(id)
        true
    }

    override suspend fun moveChunkBoundary(
        id: MediaId,
        chunkId: ChunkId,
        newStartMs: Long,
        newEndMs: Long,
    ): Boolean = withContext(ioDispatcher) {
        if (newStartMs < 0L || newStartMs >= newEndMs) {
            return@withContext false
        }

        val allChunks = chunkDao.observeByMediaId(id.value).first()
        val itemState = itemStateDao.getByMediaId(id.value)
        val activeChunks = selectActiveChunkEntities(itemState, allChunks)
        val targetIndex = activeChunks.indexOfFirst { it.id == chunkId.value }
        if (targetIndex < 0) return@withContext false

        val target = activeChunks[targetIndex]
        val prevChunk = activeChunks.getOrNull(targetIndex - 1)
        val nextChunk = activeChunks.getOrNull(targetIndex + 1)

        var adjustedPrevEndMs: Long? = null
        val clampedStartMs = if (prevChunk != null) {
            if (newStartMs < prevChunk.endMs) {
                if (prevChunk.endMs == target.startMs && newStartMs > prevChunk.startMs) {
                    adjustedPrevEndMs = newStartMs
                    newStartMs
                } else {
                    prevChunk.endMs
                }
            } else {
                newStartMs
            }
        } else {
            newStartMs.coerceAtLeast(0L)
        }

        var adjustedNextStartMs: Long? = null
        val clampedEndMs = if (nextChunk != null) {
            if (newEndMs > nextChunk.startMs) {
                if (nextChunk.startMs == target.endMs && newEndMs < nextChunk.endMs) {
                    adjustedNextStartMs = newEndMs
                    newEndMs
                } else {
                    nextChunk.startMs
                }
            } else {
                newEndMs
            }
        } else {
            val mediaDurationMs = mediaItemDao.getById(id.value)?.durationMs?.takeIf { it > 0L }
            if (mediaDurationMs != null && mediaDurationMs >= target.endMs) {
                newEndMs.coerceAtMost(mediaDurationMs)
            } else {
                newEndMs
            }
        }

        if (clampedStartMs >= clampedEndMs) {
            return@withContext false
        }

        val now = clock()
        val effectiveTranscripts = resolveEffectiveTranscriptsForActiveChunks(id, activeChunks, allChunks)
        val newChunks = ArrayList<ChunkEntity>(activeChunks.size)
        val newTranscriptsByIndex = LinkedHashMap<Int, TranscriptEntity>()

        for ((index, chunk) in activeChunks.withIndex()) {
            val updatedChunk = when (index) {
                targetIndex - 1 -> {
                    val endMs = adjustedPrevEndMs ?: chunk.endMs
                    chunk.copy(id = 0L, endMs = endMs, origin = ChunkOrigin.MANUAL.name)
                }

                targetIndex -> chunk.copy(
                    id = 0L,
                    startMs = clampedStartMs,
                    endMs = clampedEndMs,
                    origin = ChunkOrigin.MANUAL.name,
                )

                targetIndex + 1 -> {
                    val startMs = adjustedNextStartMs ?: chunk.startMs
                    chunk.copy(id = 0L, startMs = startMs, origin = ChunkOrigin.MANUAL.name)
                }

                else -> chunk.copy(id = 0L, origin = ChunkOrigin.MANUAL.name)
            }
            newChunks.add(updatedChunk)

            val existingTr = effectiveTranscripts[chunk.id]
            if (existingTr != null) {
                val domain = existingTr.toExternalModel()
                val clampedWords = if (domain.words.isNotEmpty()) {
                    clampWordTimings(domain.words, updatedChunk.startMs, updatedChunk.endMs)
                } else {
                    emptyList()
                }
                newTranscriptsByIndex[index] = domain.copy(
                    words = clampedWords,
                    userEdited = if (index == targetIndex) true else domain.userEdited,
                ).toEntity(createdAtEpochMs = if (index == targetIndex) now else existingTr.createdAt)
            }
        }

        val insertedChunks = chunkDao.replaceChunksTransaction(
            mediaId = id.value,
            origin = ChunkOrigin.MANUAL.name,
            newChunks = newChunks,
            newTranscriptsByIndex = newTranscriptsByIndex,
            preserveAllOldTranscripts = true,
        )
        syncManualTranscriptsExact(insertedChunks, newTranscriptsByIndex)
        setChunkSource(id, ChunkOrigin.MANUAL)
        persistTranscriptBackupFile(id)
        true
    }

    /**
     * `waveform.bin` 更新後に Flow 購読者へ再読込を通知する。
     */
    internal fun notifyWaveformUpdated(id: MediaId) {
        waveformRefreshEvents.tryEmit(id)
    }

    internal fun resolveAnalysisDir(id: MediaId): File? {
        if (!MediaFileLocator.isSafeIdentifier(id.value)) return null
        val audioFile = mediaFileResolver.resolveAudioFile(id)
        val baseFilesDir = audioFile?.parentFile?.parentFile ?: context.filesDir
        return File(File(baseFilesDir, ANALYSIS_DIR_NAME), id.value)
    }

    internal fun resolveAnalysisFile(id: MediaId, fileName: String): File? {
        val dir = resolveAnalysisDir(id) ?: return null
        return File(dir, fileName)
    }

    internal fun resolveTranscriptBackupFile(id: MediaId): File? {
        if (!MediaFileLocator.isSafeIdentifier(id.value)) return null
        val audioFile = mediaFileResolver.resolveAudioFile(id)
        val baseFilesDir = audioFile?.parentFile?.parentFile ?: context.filesDir
        val mediaDir = File(baseFilesDir, MEDIA_DIR_NAME).apply { mkdirs() }
        return File(mediaDir, "${id.value}.transcript.chunkflow.json")
    }

    private fun lookaheadStateFor(id: MediaId): MutableStateFlow<LookaheadTranscriptionStatus> = lookaheadStates.computeIfAbsent(id) {
        val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
        MutableStateFlow(
            LookaheadTranscriptionStatus(
                enabled = true,
                engineAvailable = available,
            ),
        )
    }

    private fun readWaveformFromDisk(id: MediaId): Waveform? {
        val file = resolveAnalysisFile(id, WAVEFORM_FILE_NAME) ?: return null
        return AnalysisBinaryFormat.readWaveform(file)
    }

    private fun selectActiveChunkEntities(
        itemState: ItemStateEntity?,
        allChunks: List<ChunkEntity>,
    ): List<ChunkEntity> {
        if (allChunks.isEmpty()) return emptyList()
        val preferredOrigin = itemState?.chunkSource
            ?.let { raw -> ChunkOrigin.entries.firstOrNull { it.name == raw } }

        if (preferredOrigin != null) {
            val preferred = allChunks.filter { it.origin == preferredOrigin.name }
            if (preferred.isNotEmpty()) {
                return preferred.sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
            }
        }

        for (fallback in FALLBACK_ORIGIN_PRIORITY) {
            val matching = allChunks.filter { it.origin == fallback.name }
            if (matching.isNotEmpty()) {
                return matching.sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
            }
        }
        return allChunks.sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
    }

    private fun applyTranscriptOffset(transcript: Transcript, offsetMs: Long): Transcript {
        if (offsetMs == 0L || transcript.words.isEmpty()) return transcript
        val shiftedWords = transcript.words.map { word ->
            val shiftedStart = (word.startMs + offsetMs).coerceAtLeast(0L)
            val shiftedEnd = (word.endMs + offsetMs).coerceAtLeast(shiftedStart)
            WordTiming(
                startMs = shiftedStart,
                endMs = shiftedEnd,
                text = word.text,
                confidence = word.confidence,
            )
        }
        return transcript.copy(words = shiftedWords)
    }

    private fun findBestOverlappingTranscript(
        target: ChunkEntity,
        sourceChunks: List<ChunkEntity>,
        transcriptsByChunkId: Map<Long, TranscriptEntity>,
        offsetMs: Long,
    ): TranscriptEntity? {
        var bestTranscript: TranscriptEntity? = null
        var bestOverlap = 0L
        for (source in sourceChunks) {
            val transcriptEntity = transcriptsByChunkId[source.id] ?: continue
            val shiftedStart = source.startMs + offsetMs
            val shiftedEnd = source.endMs + offsetMs
            val overlapStart = maxOf(target.startMs, shiftedStart)
            val overlapEnd = minOf(target.endMs, shiftedEnd)
            val overlap = (overlapEnd - overlapStart).coerceAtLeast(0L)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                bestTranscript = transcriptEntity
            }
        }
        return bestTranscript
    }

    private fun mapOverlappingTranscripts(
        activeChunks: List<ChunkEntity>,
        sourceChunks: List<ChunkEntity>,
        transcriptsByChunkId: Map<Long, TranscriptEntity>,
        offsetMs: Long,
    ): Map<ChunkId, Transcript> {
        val result = LinkedHashMap<ChunkId, Transcript>()
        for (target in activeChunks) {
            val bestTranscript = findBestOverlappingTranscript(
                target = target,
                sourceChunks = sourceChunks,
                transcriptsByChunkId = transcriptsByChunkId,
                offsetMs = offsetMs,
            )
            if (bestTranscript != null) {
                val mappedChunkId = ChunkId(target.id)
                val domain = bestTranscript.toExternalModel().copy(chunkId = mappedChunkId)
                result[mappedChunkId] = applyTranscriptOffset(domain, offsetMs)
            }
        }
        return result
    }

    private suspend fun resolveEffectiveTranscriptsForActiveChunks(
        id: MediaId,
        activeChunks: List<ChunkEntity>,
        allChunks: List<ChunkEntity>,
    ): Map<Long, TranscriptEntity> {
        val allTranscripts = transcriptDao.observeByMediaId(id.value).first()
        if (allTranscripts.isEmpty()) return emptyMap()
        val transcriptsByChunkId = allTranscripts.associateBy { it.chunkId }
        val importedChunks = allChunks.filter { it.origin == ChunkOrigin.IMPORTED.name }
        val offsetMs = (mediaItemDao.getById(id.value)?.transcriptOffsetMs ?: 0).toLong()

        val result = LinkedHashMap<Long, TranscriptEntity>()
        for (chunk in activeChunks) {
            val direct = transcriptsByChunkId[chunk.id]
            if (direct != null) {
                result[chunk.id] = direct
            } else if (importedChunks.isNotEmpty() && chunk.origin != ChunkOrigin.IMPORTED.name) {
                val mapped = findBestOverlappingTranscript(
                    target = chunk,
                    sourceChunks = importedChunks,
                    transcriptsByChunkId = transcriptsByChunkId,
                    offsetMs = offsetMs,
                )
                if (mapped != null) {
                    result[chunk.id] = mapped.copy(chunkId = chunk.id)
                }
            }
        }
        return result
    }

    private suspend fun syncManualTranscriptsExact(
        insertedChunks: List<ChunkEntity>,
        newTranscriptsByIndex: Map<Int, TranscriptEntity>,
    ) {
        val toUpsert = ArrayList<TranscriptEntity>(newTranscriptsByIndex.size)
        for ((index, inserted) in insertedChunks.withIndex()) {
            val desired = newTranscriptsByIndex[index]
            if (desired != null) {
                toUpsert.add(desired.copy(chunkId = inserted.id))
            } else {
                transcriptDao.deleteByChunkId(inserted.id)
            }
        }
        if (toUpsert.isNotEmpty()) {
            chunkDao.upsertTranscripts(toUpsert)
        }
    }

    private fun clampWordTimings(
        words: List<WordTiming>,
        chunkStartMs: Long,
        chunkEndMs: Long,
    ): List<WordTiming> {
        val clamped = ArrayList<WordTiming>(words.size)
        var prevEnd = chunkStartMs
        for (word in words.sortedBy { it.startMs }) {
            val ws = maxOf(word.startMs, prevEnd)
            val we = minOf(word.endMs, chunkEndMs)
            val text = word.text.trim()
            if (ws < we && text.isNotEmpty()) {
                prevEnd = we
                clamped.add(word.copy(startMs = ws, endMs = we, text = text))
            }
        }
        return clamped
    }

    private fun mergeTranscriptEntities(
        first: TranscriptEntity?,
        second: TranscriptEntity?,
        mergedStartMs: Long,
        mergedEndMs: Long,
        createdAtEpochMs: Long,
    ): TranscriptEntity? {
        if (first == null && second == null) return null
        if (first != null && second == null) {
            return first.copy(userEdited = true, createdAt = createdAtEpochMs)
        }
        if (first == null) {
            return second?.copy(userEdited = true, createdAt = createdAtEpochMs)
        }
        val secondEntity = second ?: return first.copy(userEdited = true, createdAt = createdAtEpochMs)
        val d1 = first.toExternalModel()
        val d2 = secondEntity.toExternalModel()

        val mergedText = when {
            d1.text.isNotBlank() && d2.text.isNotBlank() ->
                truncateCodePoints("${d1.text.trim()} ${d2.text.trim()}", MAX_TRANSCRIPT_TEXT_CODE_POINTS)

            d1.text.isNotBlank() -> d1.text.trim()

            else -> d2.text.trim()
        }

        val mergedVariants = LinkedHashMap<String, String>()
        for (key in (d1.textVariants.keys + d2.textVariants.keys)) {
            val v1 = d1.textVariants[key]?.trim().orEmpty()
            val v2 = d2.textVariants[key]?.trim().orEmpty()
            val combined = when {
                v1.isNotEmpty() && v2.isNotEmpty() -> "$v1 $v2"
                v1.isNotEmpty() -> v1
                else -> v2
            }
            if (combined.isNotEmpty()) {
                mergedVariants[key] = truncateCodePoints(combined, MAX_TRANSCRIPT_TEXT_CODE_POINTS)
            }
        }

        val mergedWords = clampWordTimings(
            words = d1.words + d2.words,
            chunkStartMs = mergedStartMs,
            chunkEndMs = mergedEndMs,
        )
        val mergedLang = d1.language ?: d2.language
        val mergedConfidence = if (mergedLang != null) {
            d1.languageConfidence ?: d2.languageConfidence ?: 1.0f
        } else {
            null
        }
        val mergedEngine = d1.engine.value.ifBlank { d2.engine.value }.ifBlank { USER_EDIT_ENGINE_ID }

        return Transcript(
            chunkId = ChunkId(0L),
            text = mergedText,
            textVariants = mergedVariants,
            language = mergedLang,
            languageConfidence = mergedConfidence,
            words = mergedWords,
            engine = EngineId(mergedEngine),
            userEdited = true,
        ).toEntity(createdAtEpochMs = createdAtEpochMs)
    }

    private fun truncateCodePoints(text: String, maxCodePoints: Int): String {
        val cpCount = text.codePointCount(0, text.length)
        if (cpCount <= maxCodePoints) return text
        val endOffset = text.offsetByCodePoints(0, maxCodePoints)
        return text.substring(0, endOffset)
    }

    /**
     * 言語の手動変更・テキストの手動修正・チャンクの手動編集・先読み認識の結果を
     * `filesDir/media/<MediaId>.transcript.chunkflow.json` に書き戻す（§7.4.5、§7.4.6）。
     */
    private suspend fun persistTranscriptBackupFile(id: MediaId) {
        val targetFile = resolveTranscriptBackupFile(id) ?: return
        val allChunks = chunkDao.observeByMediaId(id.value).first()
        if (allChunks.isEmpty()) return
        val allTranscripts = transcriptDao.observeByMediaId(id.value).first()
        if (allTranscripts.isEmpty()) return

        val transcriptsByChunkId = allTranscripts.associateBy { it.chunkId }
        val importedChunks = allChunks
            .filter { it.origin == ChunkOrigin.IMPORTED.name }
            .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
        val itemState = itemStateDao.getByMediaId(id.value)
        val activeChunks = selectActiveChunkEntities(itemState, allChunks)
        val exportChunks = if (activeChunks.any { transcriptsByChunkId.containsKey(it.id) } ||
            activeChunks.firstOrNull()?.origin == ChunkOrigin.MANUAL.name
        ) {
            activeChunks
        } else if (importedChunks.any { transcriptsByChunkId.containsKey(it.id) }) {
            importedChunks
        } else {
            activeChunks
        }
        if (exportChunks.isEmpty()) return

        val mediaItem = mediaItemDao.getById(id.value)
        val offsetMs = (mediaItem?.transcriptOffsetMs ?: 0).toLong()
        val audioFile = mediaFileResolver.resolveAudioFile(id)
        val audioFileName = audioFile?.name ?: "${id.value}.mp3"
        val sha256 = mediaItem?.sha256?.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
            ?: "0".repeat(64)
        val targetLang = mediaItem?.targetLang?.takeIf { it.isNotBlank() } ?: "en"

        // S5（各セグメントの lang は target か base か null）を満たすため、base 未設定時に別言語があれば base に補完する
        val distinctLangs = exportChunks.mapNotNull { chunk ->
            val tr = transcriptsByChunkId[chunk.id]
                ?: findBestOverlappingTranscript(chunk, importedChunks, transcriptsByChunkId, offsetMs)
            tr?.language?.takeIf { it.isNotBlank() && it != "und" }
        }.distinct()
        val configuredBase = mediaItem?.baseLang?.takeIf { it.isNotBlank() && it != targetLang }
        val effectiveBase = configuredBase ?: distinctLangs.firstOrNull { it != targetLang }

        val maxEndMs = exportChunks.maxOfOrNull { it.endMs } ?: 1_000L
        val durationMs = maxOf(mediaItem?.durationMs ?: 0L, maxEndMs, 1_000L)
        val createdAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(clock()).atOffset(ZoneOffset.UTC))

        var previousEndMs = 0L
        val segmentsJson = JSONArray()
        for (chunk in exportChunks) {
            val trEntity = transcriptsByChunkId[chunk.id]
                ?: findBestOverlappingTranscript(chunk, importedChunks, transcriptsByChunkId, offsetMs)
                ?: continue
            val startMs = maxOf(chunk.startMs, previousEndMs)
            val endMs = maxOf(chunk.endMs, startMs + 1L).coerceAtMost(durationMs)
            if (startMs >= endMs) continue
            previousEndMs = endMs

            val domain = trEntity.toExternalModel()
            val rawLang = trEntity.language?.takeIf { it.isNotBlank() && it != "und" }
            val validLang = rawLang?.takeIf { it == targetLang || it == effectiveBase }
            val safeText = domain.text.ifEmpty { " " }

            val segObj = JSONObject().apply {
                put("startMs", startMs)
                put("endMs", endMs)
                put("lang", validLang ?: JSONObject.NULL)
                val confidence = domain.languageConfidence
                if (validLang != null && confidence != null) {
                    put("langProb", confidence.toDouble().coerceIn(0.0, 1.0))
                } else {
                    put("langProb", JSONObject.NULL)
                }
                put("text", safeText)
                if (domain.textVariants.isNotEmpty()) {
                    val variantsObj = JSONObject()
                    for ((scriptKey, v) in domain.textVariants) {
                        variantsObj.put(scriptKey, v.ifEmpty { " " })
                    }
                    put("textVariants", variantsObj)
                }
                if (domain.words.isNotEmpty()) {
                    var prevWordEnd = startMs
                    val validWords = JSONArray()
                    for (w in domain.words) {
                        val ws = maxOf(w.startMs, prevWordEnd)
                        val we = minOf(w.endMs, endMs)
                        val wt = w.text.trim()
                        if (ws < we && wt.isNotEmpty()) {
                            prevWordEnd = we
                            val wordObj = JSONObject().apply {
                                put("startMs", ws)
                                put("endMs", we)
                                put("text", wt)
                                val wConf = w.confidence
                                put(
                                    "prob",
                                    if (wConf != null) wConf.toDouble().coerceIn(0.0, 1.0) else JSONObject.NULL,
                                )
                            }
                            validWords.put(wordObj)
                        }
                    }
                    if (validWords.length() > 0) {
                        put("words", validWords)
                    }
                }
            }
            segmentsJson.put(segObj)
        }

        if (segmentsJson.length() == 0) return

        val rootJson = JSONObject().apply {
            put("format", "chunkflow-transcript")
            put("version", 1)
            put(
                "audio",
                JSONObject().apply {
                    put("fileName", audioFileName)
                    put("sha256", sha256)
                    put("durationMs", durationMs)
                },
            )
            put(
                "languages",
                JSONObject().apply {
                    put("target", targetLang)
                    put("base", effectiveBase ?: JSONObject.NULL)
                },
            )
            put(
                "generator",
                JSONObject().apply {
                    put("tool", "chunkflow-android")
                    put("engine", "chunkflow-repository")
                    put("model", "user-edited")
                    put("createdAt", createdAtIso)
                },
            )
            put("segments", segmentsJson)
        }

        val bytes = rootJson.toString().toByteArray(Charsets.UTF_8)
        writeFileAtomically(targetFile, bytes)
    }

    private fun writeFileAtomically(targetFile: File, bytes: ByteArray) {
        val parent = targetFile.parentFile ?: return
        if (!parent.exists() && !parent.mkdirs()) return
        val tempFile = File(parent, "${targetFile.name}.tmp")
        try {
            FileOutputStream(tempFile).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
        } catch (_: Exception) {
            tempFile.delete()
        }
    }

    companion object {
        internal const val MEDIA_DIR_NAME: String = "media"
        internal const val ANALYSIS_DIR_NAME: String = "analysis"
        internal const val WAVEFORM_FILE_NAME: String = "waveform.bin"
        internal const val FEATURES_FILE_NAME: String = "features.bin"
        internal const val LOOKAHEAD_WINDOW_AHEAD: Int = 5

        /** 同一フォルダ内で事前に波形・チャンク解析を行っておく未アクセス教材の上限数。 */
        const val MAX_UNACCESSED_ANALYZED_PER_FOLDER: Int = 2

        private const val MANUAL_LANGUAGE_ENGINE_ID: String = "manual-language"
        private const val USER_EDIT_ENGINE_ID: String = "user"
        private const val MAX_TRANSCRIPT_TEXT_CODE_POINTS: Int = 2_000
        private const val MAX_BOOKMARK_NOTE_CODE_POINTS: Int = 500

        private val FALLBACK_ORIGIN_PRIORITY: List<ChunkOrigin> = listOf(
            ChunkOrigin.IMPORTED,
            ChunkOrigin.MANUAL,
            ChunkOrigin.AUTO,
        )

        /**
         * 指定された教材がまだ一度もユーザーによってアクセス（プレイヤーで開く・再生位置が進む・聴取済みにする等）されていないかを判定する。
         */
        internal fun isItemUnaccessed(
            item: MediaItemEntity,
            state: ItemStateEntity?,
            openMediaIds: Set<MediaId> = emptySet(),
        ): Boolean {
            if (MediaId(item.id) in openMediaIds) return false
            if (state == null) return true
            if (state.isListened || state.positionMs > 0L) return false
            return state.updatedAt <= 0L || state.updatedAt <= item.importedAt
        }
    }
}

private object NoOpOnDeviceChunkTranscriber : OnDeviceChunkTranscriber {
    override fun isAvailable(): Boolean = false

    override suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
    ): OnDeviceChunkTranscriptionResult? = null
}

private object NoOpBookmarkDao : BookmarkDao {
    override fun observeByMediaId(mediaId: String): Flow<List<BookmarkEntity>> = flowOf(emptyList())

    override suspend fun getByMediaId(mediaId: String): List<BookmarkEntity> = emptyList()

    override suspend fun insert(bookmark: BookmarkEntity): Long = 0L

    override suspend fun upsert(bookmark: BookmarkEntity): Long = 0L

    override suspend fun deleteById(id: Long) = Unit
}

/**
 * 手動の強制再文字起こし（`retranscribeChunk`）や言語トグル（`updateChunkLanguage`）が
 * バックグラウンド先読み（`triggerLookaheadAt`）のチャンク間に割り込んで優先実行されるようにするゲート。
 */
private class PriorityRecognitionSlotGate(
    private val pendingHighPriorityCount: AtomicInteger,
) {
    private val stateLock = Any()
    private var isLocked = false
    private val highPriorityQueue = ArrayDeque<CompletableDeferred<Unit>>()
    private val normalPriorityQueue = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun <T> withHighPrioritySlot(block: suspend () -> T): T {
        pendingHighPriorityCount.incrementAndGet()
        return try {
            acquire(highPriority = true)
            try {
                block()
            } finally {
                release()
            }
        } finally {
            if (pendingHighPriorityCount.decrementAndGet() == 0) {
                wakeNormalIfIdle()
            }
        }
    }

    suspend fun <T> withNormalSlot(block: suspend () -> T): T {
        acquire(highPriority = false)
        return try {
            block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(highPriority: Boolean) {
        val waiter: CompletableDeferred<Unit>? = synchronized(stateLock) {
            val canAcquireImmediately = if (highPriority) {
                !isLocked && highPriorityQueue.isEmpty()
            } else {
                !isLocked &&
                    highPriorityQueue.isEmpty() &&
                    normalPriorityQueue.isEmpty() &&
                    pendingHighPriorityCount.get() == 0
            }
            if (canAcquireImmediately) {
                isLocked = true
                null
            } else {
                CompletableDeferred<Unit>().also { deferred ->
                    if (highPriority) {
                        // 最優先の要求はスタックとして扱い、最後に来た要求（直近の操作）から処理する
                        highPriorityQueue.addFirst(deferred)
                    } else {
                        normalPriorityQueue.addLast(deferred)
                    }
                }
            }
        }
        if (waiter == null) return
        try {
            waiter.await()
        } catch (ce: CancellationException) {
            var acquiredBeforeCancel = false
            synchronized(stateLock) {
                val removed = if (highPriority) {
                    highPriorityQueue.remove(waiter)
                } else {
                    normalPriorityQueue.remove(waiter)
                }
                if (!removed && waiter.isCompleted) {
                    acquiredBeforeCancel = true
                }
            }
            if (acquiredBeforeCancel) {
                release()
            }
            throw ce
        }
    }

    private fun release() {
        while (true) {
            val next: CompletableDeferred<Unit>? = synchronized(stateLock) {
                val highWaiter = highPriorityQueue.removeFirstOrNull()
                if (highWaiter != null) {
                    highWaiter
                } else if (pendingHighPriorityCount.get() > 0) {
                    isLocked = false
                    null
                } else {
                    val normalWaiter = normalPriorityQueue.removeFirstOrNull()
                    if (normalWaiter != null) {
                        normalWaiter
                    } else {
                        isLocked = false
                        null
                    }
                }
            }
            if (next == null) return
            if (next.complete(Unit)) return
        }
    }

    private fun wakeNormalIfIdle() {
        while (true) {
            val next: CompletableDeferred<Unit>? = synchronized(stateLock) {
                if (isLocked) return
                val waiter = highPriorityQueue.removeFirstOrNull()
                    ?: if (pendingHighPriorityCount.get() == 0) {
                        normalPriorityQueue.removeFirstOrNull()
                    } else {
                        null
                    }
                if (waiter != null) {
                    isLocked = true
                    waiter
                } else {
                    null
                }
            }
            if (next == null) return
            if (next.complete(Unit)) return
            synchronized(stateLock) {
                isLocked = false
            }
        }
    }
}
