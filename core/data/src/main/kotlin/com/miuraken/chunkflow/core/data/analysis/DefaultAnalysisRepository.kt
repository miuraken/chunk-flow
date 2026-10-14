package com.miuraken.chunkflow.core.data.analysis

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.miuraken.chunkflow.core.audio.AnalysisBinaryFormat
import com.miuraken.chunkflow.core.audio.CURRENT_ALGORITHM_VERSION
import com.miuraken.chunkflow.core.audio.ChunkSegmenter
import com.miuraken.chunkflow.core.data.di.ChunkFlowDispatchers
import com.miuraken.chunkflow.core.data.di.Dispatcher
import com.miuraken.chunkflow.core.data.library.LibraryMediaFileResolver
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.database.dao.AnalysisStateDao
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.dao.TranscriptDao
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import com.miuraken.chunkflow.core.database.entity.toEntity
import com.miuraken.chunkflow.core.database.entity.toExternalModel
import com.miuraken.chunkflow.core.database.entity.toStageAndStatus
import com.miuraken.chunkflow.core.model.AnalysisError
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.model.WordTiming
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext

/**
 * [AnalysisRepository] の本番実装（§6.3、§7.3、§10.6）。
 *
 * - `filesDir/analysis/<MediaId>/waveform.bin` と `features.bin` を [AnalysisBinaryFormat] で読み書きする。
 * - `item_state.chunk_source` に応じて表示するチャンクの出どころを切り替え、未存在時は優先順（`IMPORTED` → `MANUAL` → `AUTO`）にフォールバックする。
 * - `AUTO`（または `MANUAL`）チャンク表示時に `IMPORTED` チャンクの文字起こしが Room に存在する場合は、
 *   `media_item.transcript_offset_ms` を反映したうえで時間重なりが最大のチャンクへ対応付ける（§7.3、§10.6）。
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
    private val clock: () -> Long = System::currentTimeMillis,
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
    ) : this(
        context = context,
        analysisStateDao = analysisStateDao,
        chunkDao = chunkDao,
        transcriptDao = transcriptDao,
        itemStateDao = itemStateDao,
        mediaItemDao = mediaItemDao,
        mediaFileResolver = mediaFileResolver,
        ioDispatcher = ioDispatcher,
        clock = System::currentTimeMillis,
    )

    private val chunkSegmenter: ChunkSegmenter = ChunkSegmenter()
    private val waveformRefreshEvents = MutableSharedFlow<MediaId>(extraBufferCapacity = 16)

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
            val directMatches = LinkedHashMap<ChunkId, Transcript>()
            for (chunk in activeChunks) {
                val entity = transcriptsByChunkId[chunk.id] ?: continue
                val chunkId = ChunkId(chunk.id)
                directMatches[chunkId] = applyTranscriptOffset(entity.toExternalModel(), offsetMs)
            }
            if (directMatches.isNotEmpty()) {
                return@combine directMatches
            }

            // AUTO / MANUAL チャンク表示時に IMPORTED チャンクの文字起こしが存在する場合、
            // 時間重なりが最大のチャンクへ対応付ける（§7.3、§10.6）。
            val importedChunks = allChunks.filter { it.origin == ChunkOrigin.IMPORTED.name }
            if (importedChunks.isEmpty()) {
                return@combine emptyMap()
            }
            mapOverlappingTranscripts(
                activeChunks = activeChunks,
                sourceChunks = importedChunks,
                transcriptsByChunkId = transcriptsByChunkId,
                offsetMs = offsetMs,
            )
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
                val status = if (existing is StageStatus.Running) {
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
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    AnalysisWorker.uniqueWorkName(id),
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }
        }
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

    private fun mapOverlappingTranscripts(
        activeChunks: List<ChunkEntity>,
        sourceChunks: List<ChunkEntity>,
        transcriptsByChunkId: Map<Long, TranscriptEntity>,
        offsetMs: Long,
    ): Map<ChunkId, Transcript> {
        val result = LinkedHashMap<ChunkId, Transcript>()
        for (target in activeChunks) {
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
            if (bestTranscript != null) {
                val mappedChunkId = ChunkId(target.id)
                val domain = bestTranscript.toExternalModel().copy(chunkId = mappedChunkId)
                result[mappedChunkId] = applyTranscriptOffset(domain, offsetMs)
            }
        }
        return result
    }

    companion object {
        internal const val ANALYSIS_DIR_NAME: String = "analysis"
        internal const val WAVEFORM_FILE_NAME: String = "waveform.bin"
        internal const val FEATURES_FILE_NAME: String = "features.bin"

        private val FALLBACK_ORIGIN_PRIORITY: List<ChunkOrigin> = listOf(
            ChunkOrigin.IMPORTED,
            ChunkOrigin.MANUAL,
            ChunkOrigin.AUTO,
        )
    }
}
