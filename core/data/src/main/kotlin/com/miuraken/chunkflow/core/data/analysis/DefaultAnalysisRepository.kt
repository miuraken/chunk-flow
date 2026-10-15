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
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
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
    private val pipelineMutex: Mutex = AnalysisWorker.pipelineMutex
    private val lookaheadMutex: Mutex = Mutex()
    private val pendingHighPriorityCount = AtomicInteger(0)
    private val recognitionSlotGate = PriorityRecognitionSlotGate(pendingHighPriorityCount)
    private val waveformRefreshEvents = MutableSharedFlow<MediaId>(extraBufferCapacity = 16)
    private val lookaheadStates = ConcurrentHashMap<MediaId, MutableStateFlow<LookaheadTranscriptionStatus>>()
    private val explicitlyConfiguredLookaheadMediaIds: MutableSet<MediaId> = ConcurrentHashMap.newKeySet()
    private val attemptedEmptyChunkIds = ConcurrentHashMap<MediaId, MutableSet<Long>>()
    private val chunkTranscriptByLangCache = ConcurrentHashMap<ChunkLangCacheKey, CachedChunkTranscript>()

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

            runCatching {
                val inputData = Data.Builder()
                    .putString(AnalysisWorker.KEY_MEDIA_ID, id.value)
                    .putStringArray(
                        AnalysisWorker.KEY_STAGES,
                        stages.map { it.name }.toTypedArray(),
                    )
                    .build()
                val request = OneTimeWorkRequestBuilder<AnalysisWorker>()
                    .setInputData(inputData)
                    .addTag(AnalysisWorker.tagForMedia(id))
                    .addTag(AnalysisWorker.uniqueWorkName(id))
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    AnalysisWorker.SEQUENTIAL_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request,
                )
            }

            val decoder = audioStreamDecoder
            if (decoder != null) {
                pipelineMutex.withLock {
                    runAnalysisPipeline(id, stages, decoder)
                }
            }
        }
    }

    private suspend fun runAnalysisPipeline(
        mediaId: MediaId,
        requestedStages: Set<AnalysisStage>,
        decoder: AudioStreamDecoder,
    ) {
        val audioFile = mediaFileResolver.resolveAudioFile(mediaId)
        val analysisDir = resolveAnalysisDir(mediaId)?.apply { mkdirs() }
        if (audioFile == null || !audioFile.isFile || analysisDir == null || !analysisDir.exists()) {
            markStagesFailed(mediaId, requestedStages, AnalysisError.IO)
            return
        }

        val waveformFile = File(analysisDir, WAVEFORM_FILE_NAME)
        val featuresFile = File(analysisDir, FEATURES_FILE_NAME)
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
                        extractor.process(samples, count)
                    },
                    outputSampleRate = AnalysisWorker.TARGET_SAMPLE_RATE_HZ,
                    expectedDurationMs = expectedDurationMs,
                    onProgress = { progress ->
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
        } catch (_: Exception) {
            markStagesFailed(mediaId, requestedStages, AnalysisError.DECODE_FAILED)
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

            val baseExisting = existing ?: overlappingImported
            if (targetChunk != null && baseExisting != null) {
                val prevLang = baseExisting.language?.trim()?.lowercase(Locale.ROOT)
                if (baseExisting.text.isNotBlank() && !prevLang.isNullOrBlank() && prevLang != "und") {
                    chunkTranscriptByLangCache[
                        ChunkLangCacheKey(
                            mediaId = id.value,
                            startMs = targetChunk.startMs,
                            endMs = targetChunk.endMs,
                            language = prevLang,
                        ),
                    ] = CachedChunkTranscript(
                        text = baseExisting.text,
                        confidence = baseExisting.languageConfidence,
                        engine = baseExisting.engine,
                    )
                }
            }

            if (language == null) {
                if (existing != null) {
                    transcriptDao.updateLanguage(chunkId.value, null)
                } else if (overlappingImported != null) {
                    transcriptDao.updateLanguage(overlappingImported.chunkId, null)
                    transcriptDao.upsert(
                        overlappingImported.copy(
                            chunkId = chunkId.value,
                            language = null,
                            languageConfidence = null,
                            userEdited = true,
                        ),
                    )
                } else {
                    transcriptDao.upsert(
                        TranscriptEntity(
                            chunkId = chunkId.value,
                            text = "",
                            textVariants = "{}",
                            language = null,
                            languageConfidence = null,
                            words = "[]",
                            engine = MANUAL_LANGUAGE_ENGINE_ID,
                            userEdited = true,
                            createdAt = clock(),
                        ),
                    )
                }
                persistTranscriptBackupFile(id)
                return@withContext
            }

            val normalizedNewLang = language.value.trim().lowercase(Locale.ROOT)
            val cached = targetChunk?.let { chunk ->
                chunkTranscriptByLangCache[
                    ChunkLangCacheKey(
                        mediaId = id.value,
                        startMs = chunk.startMs,
                        endMs = chunk.endMs,
                        language = normalizedNewLang,
                    ),
                ]
            }

            if (cached != null) {
                if (existing == null && overlappingImported != null) {
                    transcriptDao.updateLanguage(overlappingImported.chunkId, language.value)
                }
                transcriptDao.upsert(
                    TranscriptEntity(
                        chunkId = chunkId.value,
                        text = cached.text,
                        textVariants = baseExisting?.textVariants ?: "{}",
                        language = language.value,
                        languageConfidence = 1.0f,
                        words = baseExisting?.words ?: "[]",
                        engine = cached.engine,
                        userEdited = true,
                        createdAt = clock(),
                    ),
                )
                persistTranscriptBackupFile(id)
                return@withContext
            }

            // キャッシュミス時: まず即座に言語バッジを反映する
            if (existing != null) {
                transcriptDao.updateLanguage(chunkId.value, language.value)
            } else if (overlappingImported != null) {
                transcriptDao.updateLanguage(overlappingImported.chunkId, language.value)
                transcriptDao.upsert(
                    overlappingImported.copy(
                        chunkId = chunkId.value,
                        language = language.value,
                        languageConfidence = 1.0f,
                        userEdited = true,
                    ),
                )
            } else {
                transcriptDao.upsert(
                    TranscriptEntity(
                        chunkId = chunkId.value,
                        text = "",
                        textVariants = "{}",
                        language = language.value,
                        languageConfidence = 1.0f,
                        words = "[]",
                        engine = MANUAL_LANGUAGE_ENGINE_ID,
                        userEdited = true,
                        createdAt = clock(),
                    ),
                )
            }

            // オンデバイス音声認識が利用可能かつ音声ファイルが存在する場合は指定言語単体で優先再認識する
            val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
            val audioFile = mediaFileResolver.resolveAudioFile(id)
            if (available && audioFile != null && audioFile.isFile && targetChunk != null && targetChunk.endMs > targetChunk.startMs) {
                val stateFlow = lookaheadStateFor(id)
                val recognized = recognitionSlotGate.withHighPrioritySlot {
                    stateFlow.update {
                        it.copy(
                            engineAvailable = true,
                            inFlightChunkId = chunkId,
                        )
                    }
                    try {
                        onDeviceChunkTranscriber.transcribeChunk(
                            audioFile = audioFile,
                            startMs = targetChunk.startMs,
                            endMs = targetChunk.endMs,
                            languagePair = LanguagePair(target = language, base = null),
                            forceRefresh = false,
                            highPriority = true,
                        )
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (_: Exception) {
                        null
                    } finally {
                        stateFlow.update { it.copy(inFlightChunkId = null) }
                    }
                }

                if (recognized != null && recognized.text.isNotBlank()) {
                    chunkTranscriptByLangCache[
                        ChunkLangCacheKey(
                            mediaId = id.value,
                            startMs = targetChunk.startMs,
                            endMs = targetChunk.endMs,
                            language = normalizedNewLang,
                        ),
                    ] = CachedChunkTranscript(
                        text = recognized.text,
                        confidence = recognized.confidence,
                        engine = recognized.engineId.value,
                    )
                    val currentEntity = transcriptDao.getByChunkId(chunkId.value) ?: baseExisting
                    transcriptDao.upsert(
                        TranscriptEntity(
                            chunkId = chunkId.value,
                            text = recognized.text,
                            textVariants = currentEntity?.textVariants ?: "{}",
                            language = language.value,
                            languageConfidence = 1.0f,
                            words = currentEntity?.words ?: "[]",
                            engine = recognized.engineId.value,
                            userEdited = true,
                            createdAt = clock(),
                        ),
                    )
                }
            }

            persistTranscriptBackupFile(id)
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

    override fun observeLookaheadStatus(id: MediaId): Flow<LookaheadTranscriptionStatus> = lookaheadStateFor(id).asStateFlow()

    override suspend fun setLookaheadEnabled(id: MediaId, enabled: Boolean) {
        explicitlyConfiguredLookaheadMediaIds.add(id)
        attemptedEmptyChunkIds.remove(id)
        val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
        lookaheadStateFor(id).update { current ->
            current.copy(
                enabled = enabled,
                engineAvailable = available,
                inFlightChunkId = if (enabled) current.inFlightChunkId else null,
            )
        }
    }

    override suspend fun triggerLookaheadAt(id: MediaId, currentChunkId: ChunkId?) {
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

            val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
            val audioFile = mediaFileResolver.resolveAudioFile(id)
            if (!available || audioFile == null || !audioFile.isFile) return@withContext

            val stateFlow = lookaheadStateFor(id)
            var updated = false
            recognitionSlotGate.withHighPrioritySlot {
                evictChunkTranscriptCache(
                    mediaId = id.value,
                    startMs = targetChunk.startMs,
                    endMs = targetChunk.endMs,
                )

                val existing = transcriptDao.getByChunkId(chunkId.value)
                val mediaItem = mediaItemDao.getById(id.value)
                val mediaLanguagePair = mediaItem?.let { item ->
                    val target = item.targetLang.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                    val base = item.baseLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                    target?.let { LanguagePair(target = it, base = base) }
                }
                val manualLang = existing?.language?.trim()?.takeIf {
                    existing.userEdited && it.isNotEmpty() && it != "und"
                }
                val effectiveLanguagePair = if (manualLang != null) {
                    LanguagePair(target = LanguageTag(manualLang), base = null)
                } else {
                    mediaLanguagePair
                }

                stateFlow.update {
                    it.copy(
                        engineAvailable = true,
                        inFlightChunkId = chunkId,
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
                    stateFlow.update { it.copy(inFlightChunkId = null) }
                }

                if (recognized != null && recognized.text.isNotBlank()) {
                    val currentEntity = transcriptDao.getByChunkId(chunkId.value) ?: existing
                    val resolvedLanguage = manualLang
                        ?: recognized.language?.value?.takeIf { it.isNotBlank() && it != "und" }
                        ?: effectiveLanguagePair?.target?.value
                    val resolvedConfidence = if (manualLang != null) {
                        currentEntity?.languageConfidence ?: 1.0f
                    } else {
                        recognized.confidence
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
                            userEdited = currentEntity?.userEdited ?: false,
                            createdAt = clock(),
                        ),
                    )
                    updated = true
                }
            }

            if (updated) {
                persistTranscriptBackupFile(id)
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

    /**
     * アクティブチャンクのインデックス [currentChunkIndex] 以降から、未文字起こし・または言語未確定（かつ空結果として試行済みでない）チャンクを
     * 最大 `LOOKAHEAD_WINDOW_AHEAD + 1`（6）件ずつ順番に先読み認識する（F6.2、§7.4.5）。
     *
     * 各チャンクの認識前に高優先度の手動リクエスト（[retranscribeChunk] / [updateChunkLanguage]）が待機している場合は
     * 認識スロットを譲り、手動リクエスト完了後に残りの先読みチャンクを再開する。
     */
    suspend fun triggerLookaheadAt(id: MediaId, currentChunkIndex: Int) {
        if (currentChunkIndex < 0) return
        withContext(ioDispatcher) {
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
                if (!currentStatus.enabled) return@withLock
                if (!available) {
                    stateFlow.update { it.copy(engineAvailable = false, inFlightChunkId = null) }
                    return@withLock
                }

                val allChunks = chunkDao.observeByMediaId(id.value).first()
                val itemState = itemStateDao.getByMediaId(id.value)
                val activeChunks = selectActiveChunkEntities(itemState, allChunks)
                if (currentChunkIndex >= activeChunks.size) return@withLock

                val audioFile = mediaFileResolver.resolveAudioFile(id)
                if (audioFile == null || !audioFile.isFile) return@withLock

                val skippedEmptyIds = attemptedEmptyChunkIds.computeIfAbsent(id) {
                    ConcurrentHashMap.newKeySet()
                }
                val endIndex = min(activeChunks.lastIndex, currentChunkIndex + LOOKAHEAD_WINDOW_AHEAD)
                val windowChunks = activeChunks.subList(currentChunkIndex, endIndex + 1)

                val candidateSlice = activeChunks.subList(currentChunkIndex, activeChunks.size)
                val existingTranscripts = transcriptDao.getByChunkIds(candidateSlice.map { it.id })
                    .associateBy { it.chunkId }

                // まだ文字起こしテキストがないか言語未確定（null / "und"）であり、かつ空結果として試行済みでないチャンクを先頭から最大 6 件取得する
                val targetChunks = candidateSlice.filter { chunk ->
                    needsLookaheadTranscription(existingTranscripts[chunk.id]) &&
                        !skippedEmptyIds.contains(chunk.id)
                }.take(LOOKAHEAD_WINDOW_AHEAD + 1)

                if (targetChunks.isEmpty()) {
                    stateFlow.update {
                        it.copy(
                            engineAvailable = true,
                            inFlightChunkId = if (pendingHighPriorityCount.get() > 0) it.inFlightChunkId else null,
                            completedCount = windowChunks.size,
                            totalTargetCount = windowChunks.size,
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
                val totalTargets = targetChunks.size
                if (pendingHighPriorityCount.get() == 0) {
                    stateFlow.update {
                        it.copy(
                            engineAvailable = true,
                            inFlightChunkId = ChunkId(targetChunks.first().id),
                            completedCount = 0,
                            totalTargetCount = totalTargets,
                        )
                    }
                }

                var anyTranscribed = false
                for (chunk in targetChunks) {
                    if (!stateFlow.value.enabled) break
                    if (chunk.endMs <= chunk.startMs) {
                        skippedEmptyIds.add(chunk.id)
                        completed += 1
                        continue
                    }

                    recognitionSlotGate.withNormalSlot {
                        if (!stateFlow.value.enabled) return@withNormalSlot

                        val existingBefore = transcriptDao.getByChunkId(chunk.id)
                        if (!needsLookaheadTranscription(existingBefore) || skippedEmptyIds.contains(chunk.id)) {
                            completed += 1
                            stateFlow.update {
                                it.copy(
                                    inFlightChunkId = null,
                                    completedCount = completed,
                                    totalTargetCount = totalTargets,
                                )
                            }
                            return@withNormalSlot
                        }

                        stateFlow.update {
                            it.copy(
                                engineAvailable = true,
                                inFlightChunkId = ChunkId(chunk.id),
                                completedCount = completed,
                                totalTargetCount = totalTargets,
                            )
                        }

                        val existingBeforeLang = existingBefore?.language
                        val effectiveLanguagePair = if (
                            existingBefore != null &&
                            existingBefore.text.isBlank() &&
                            !existingBeforeLang.isNullOrBlank() &&
                            existingBeforeLang != "und"
                        ) {
                            LanguagePair(target = LanguageTag(existingBeforeLang), base = null)
                        } else {
                            languagePair
                        }

                        // チャンク区間 [startMs, endMs] のみを切り出して認識し、チャンク間の無音はスキップする（F6.2）
                        val result: OnDeviceChunkTranscriptionResult? = try {
                            onDeviceChunkTranscriber.transcribeChunk(
                                audioFile = audioFile,
                                startMs = chunk.startMs,
                                endMs = chunk.endMs,
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
                            skippedEmptyIds.remove(chunk.id)
                            val existing = transcriptDao.getByChunkId(chunk.id)
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
                                        startMs = chunk.startMs,
                                        endMs = chunk.endMs,
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
                                    chunkId = chunk.id,
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
                            anyTranscribed = true
                        } else if (stateFlow.value.enabled) {
                            skippedEmptyIds.add(chunk.id)
                        }

                        completed += 1
                        stateFlow.update {
                            it.copy(
                                inFlightChunkId = null,
                                completedCount = completed,
                                totalTargetCount = totalTargets,
                            )
                        }
                    }
                }

                if (anyTranscribed) {
                    persistTranscriptBackupFile(id)
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
        private const val MANUAL_LANGUAGE_ENGINE_ID: String = "manual-language"
        private const val USER_EDIT_ENGINE_ID: String = "user"
        private const val MAX_TRANSCRIPT_TEXT_CODE_POINTS: Int = 2_000
        private const val MAX_BOOKMARK_NOTE_CODE_POINTS: Int = 500

        private val FALLBACK_ORIGIN_PRIORITY: List<ChunkOrigin> = listOf(
            ChunkOrigin.IMPORTED,
            ChunkOrigin.MANUAL,
            ChunkOrigin.AUTO,
        )
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
                        highPriorityQueue.addLast(deferred)
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
