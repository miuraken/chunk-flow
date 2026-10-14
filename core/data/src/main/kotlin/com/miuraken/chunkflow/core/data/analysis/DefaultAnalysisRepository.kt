package com.miuraken.chunkflow.core.data.analysis

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.miuraken.chunkflow.core.audio.AnalysisBinaryFormat
import com.miuraken.chunkflow.core.audio.CURRENT_ALGORITHM_VERSION
import com.miuraken.chunkflow.core.audio.ChunkSegmenter
import com.miuraken.chunkflow.core.audio.TranscriptOffsetAligner
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
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriber
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriptionResult
import com.miuraken.chunkflow.core.model.AnalysisError
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [AnalysisRepository] の本番実装（§6.3、§7.3、§7.4.5、§7.4.6、§10.6、F6.2）。
 *
 * - `filesDir/analysis/<MediaId>/waveform.bin` と `features.bin` を [AnalysisBinaryFormat] で読み書きする。
 * - `item_state.chunk_source` に応じて表示するチャンクの出どころを切り替え、未存在時は優先順（`IMPORTED` → `MANUAL` → `AUTO`）にフォールバックする。
 * - `AUTO`（または `MANUAL`）チャンク表示時に `IMPORTED` チャンクの文字起こしが Room に存在する場合は、
 *   `media_item.transcript_offset_ms` を反映したうえで時間重なりが最大のチャンクへ対応付ける（§7.3、§10.6）。
 * - チャンク単位の言語手動変更・不明言語の一括設定・文字起こし時刻の自動合わせ・端末内先読み音声認識（F6.2）を提供する。
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
        onDeviceChunkTranscriber: OnDeviceChunkTranscriber,
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
        clock = System::currentTimeMillis,
    )

    private val chunkSegmenter: ChunkSegmenter = ChunkSegmenter()
    private val waveformRefreshEvents = MutableSharedFlow<MediaId>(extraBufferCapacity = 16)
    private val lookaheadStates = ConcurrentHashMap<MediaId, MutableStateFlow<LookaheadTranscriptionStatus>>()

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
            val importedChunks = allChunks.filter { it.origin == ChunkOrigin.IMPORTED.name }
            if (importedChunks.isNotEmpty() && activeChunks.firstOrNull()?.origin != ChunkOrigin.IMPORTED.name) {
                result.putAll(
                    mapOverlappingTranscripts(
                        activeChunks = activeChunks,
                        sourceChunks = importedChunks,
                        transcriptsByChunkId = transcriptsByChunkId,
                        offsetMs = offsetMs,
                    ),
                )
            }

            // アクティブチャンクに直接紐付く文字起こし（IMPORTED 表示時、または先読み認識・手動言語編集時）を優先して重ねる。
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

    override suspend fun updateChunkLanguage(id: MediaId, chunkId: ChunkId, language: LanguageTag?) {
        withContext(ioDispatcher) {
            val existing = transcriptDao.getByChunkId(chunkId.value)
            if (existing != null) {
                transcriptDao.updateLanguage(chunkId.value, language?.value)
            } else {
                // AUTO チャンク等で直接の TranscriptEntity が未作成の場合、
                // 重なる IMPORTED チャンクがあればその文字起こしを引き継ぎつつ言語を更新し、
                // なければ空テキストの TranscriptEntity（userEdited = true）を作成する（§7.4.5、§7.4.6）。
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
                    transcriptDao.updateLanguage(overlappingImported.chunkId, language?.value)
                    transcriptDao.upsert(
                        overlappingImported.copy(
                            chunkId = chunkId.value,
                            language = language?.value,
                            languageConfidence = if (language != null) 1.0f else null,
                            userEdited = true,
                        ),
                    )
                } else {
                    transcriptDao.upsert(
                        TranscriptEntity(
                            chunkId = chunkId.value,
                            text = "",
                            textVariants = "{}",
                            language = language?.value,
                            languageConfidence = if (language != null) 1.0f else null,
                            words = "[]",
                            engine = MANUAL_LANGUAGE_ENGINE_ID,
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

    /**
     * アクティブチャンクのインデックス [currentChunkIndex] から最大 [LOOKAHEAD_WINDOW_AHEAD] チャンク先までを先読み認識する（F6.2、§7.4.5）。
     */
    suspend fun triggerLookaheadAt(id: MediaId, currentChunkIndex: Int) {
        if (currentChunkIndex < 0) return
        withContext(ioDispatcher) {
            val stateFlow = lookaheadStateFor(id)
            val currentStatus = stateFlow.value
            val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
            if (!currentStatus.enabled) return@withContext
            if (!available) {
                stateFlow.update { it.copy(engineAvailable = false, inFlightChunkId = null) }
                return@withContext
            }

            val allChunks = chunkDao.observeByMediaId(id.value).first()
            val itemState = itemStateDao.getByMediaId(id.value)
            val activeChunks = selectActiveChunkEntities(itemState, allChunks)
            if (currentChunkIndex >= activeChunks.size) return@withContext

            val audioFile = mediaFileResolver.resolveAudioFile(id)
            if (audioFile == null || !audioFile.isFile) return@withContext

            val endIndex = min(activeChunks.lastIndex, currentChunkIndex + LOOKAHEAD_WINDOW_AHEAD)
            val windowChunks = activeChunks.subList(currentChunkIndex, endIndex + 1)
            val existingTranscripts = transcriptDao.getByChunkIds(windowChunks.map { it.id })
                .associateBy { it.chunkId }

            // 既に認識済み（テキストが空でない、またはユーザー編集済み）のチャンクはスキップする
            val targetChunks = windowChunks.filter { chunk ->
                val existing = existingTranscripts[chunk.id]
                existing == null || (existing.text.isBlank() && !existing.userEdited)
            }

            if (targetChunks.isEmpty()) {
                stateFlow.update {
                    it.copy(
                        engineAvailable = true,
                        inFlightChunkId = null,
                        completedCount = windowChunks.size,
                        totalTargetCount = windowChunks.size,
                    )
                }
                return@withContext
            }

            val mediaItem = mediaItemDao.getById(id.value)
            val languagePair = mediaItem?.let { item ->
                val target = item.targetLang.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                val base = item.baseLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                target?.let { LanguagePair(target = it, base = base) }
            }

            var completed = 0
            val totalTargets = targetChunks.size
            stateFlow.update {
                it.copy(
                    engineAvailable = true,
                    inFlightChunkId = ChunkId(targetChunks.first().id),
                    completedCount = 0,
                    totalTargetCount = totalTargets,
                )
            }

            var anyTranscribed = false
            for (chunk in targetChunks) {
                if (!stateFlow.value.enabled) break
                if (chunk.endMs <= chunk.startMs) {
                    completed += 1
                    continue
                }

                stateFlow.update {
                    it.copy(
                        inFlightChunkId = ChunkId(chunk.id),
                        completedCount = completed,
                        totalTargetCount = totalTargets,
                    )
                }

                // チャンク区間 [startMs, endMs] のみを切り出して認識し、チャンク間の無音はスキップする（F6.2）
                val result: OnDeviceChunkTranscriptionResult? = try {
                    onDeviceChunkTranscriber.transcribeChunk(
                        audioFile = audioFile,
                        startMs = chunk.startMs,
                        endMs = chunk.endMs,
                        languagePair = languagePair,
                    )
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    null
                }

                if (result != null && result.text.isNotBlank()) {
                    val existing = transcriptDao.getByChunkId(chunk.id)
                    val resolvedLanguage = existing?.language?.takeIf { it.isNotBlank() && it != "und" }
                        ?: result.language?.value
                        ?: languagePair?.target?.value
                    val resolvedConfidence = if (existing?.userEdited == true) {
                        existing.languageConfidence ?: 1.0f
                    } else {
                        result.confidence
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

            if (anyTranscribed) {
                persistTranscriptBackupFile(id)
            }
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

    internal fun resolveTranscriptBackupFile(id: MediaId): File? {
        if (!MediaFileLocator.isSafeIdentifier(id.value)) return null
        val audioFile = mediaFileResolver.resolveAudioFile(id)
        val baseFilesDir = audioFile?.parentFile?.parentFile ?: context.filesDir
        val mediaDir = File(baseFilesDir, MEDIA_DIR_NAME).apply { mkdirs() }
        return File(mediaDir, "${id.value}.transcript.chunkflow.json")
    }

    private fun lookaheadStateFor(id: MediaId): MutableStateFlow<LookaheadTranscriptionStatus> = lookaheadStates.computeIfAbsent(id) {
        val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
        MutableStateFlow(LookaheadTranscriptionStatus(engineAvailable = available))
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

    /**
     * 言語の手動変更・不明言語の一括設定・先読み認識の結果を
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
        val exportChunks = if (importedChunks.any { transcriptsByChunkId.containsKey(it.id) }) {
            importedChunks
        } else {
            val itemState = itemStateDao.getByMediaId(id.value)
            selectActiveChunkEntities(itemState, allChunks)
        }
        if (exportChunks.isEmpty()) return

        val mediaItem = mediaItemDao.getById(id.value)
        val audioFile = mediaFileResolver.resolveAudioFile(id)
        val audioFileName = audioFile?.name ?: "${id.value}.mp3"
        val sha256 = mediaItem?.sha256?.takeIf { it.length == 64 && it.all { ch -> ch in '0'..'9' || ch in 'a'..'f' } }
            ?: "0".repeat(64)
        val targetLang = mediaItem?.targetLang?.takeIf { it.isNotBlank() } ?: "en"

        // S5（各セグメントの lang は target か base か null）を満たすため、base 未設定時に別言語があれば base に補完する
        val distinctLangs = exportChunks.mapNotNull { chunk ->
            transcriptsByChunkId[chunk.id]?.language?.takeIf { it.isNotBlank() && it != "und" }
        }.distinct()
        val configuredBase = mediaItem?.baseLang?.takeIf { it.isNotBlank() && it != targetLang }
        val effectiveBase = configuredBase ?: distinctLangs.firstOrNull { it != targetLang }

        val maxEndMs = exportChunks.maxOfOrNull { it.endMs } ?: 1_000L
        val durationMs = maxOf(mediaItem?.durationMs ?: 0L, maxEndMs, 1_000L)
        val createdAtIso = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(clock()).atOffset(ZoneOffset.UTC))

        var previousEndMs = 0L
        val segmentsJson = JSONArray()
        for (chunk in exportChunks) {
            val trEntity = transcriptsByChunkId[chunk.id] ?: continue
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
