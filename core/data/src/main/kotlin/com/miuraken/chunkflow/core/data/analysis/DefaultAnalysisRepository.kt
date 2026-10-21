package com.miuraken.chunkflow.core.data.analysis

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.miuraken.chunkflow.core.audio.AnalysisBinaryFormat
import com.miuraken.chunkflow.core.audio.CURRENT_ALGORITHM_VERSION
import com.miuraken.chunkflow.core.audio.ChunkSegmenter
import com.miuraken.chunkflow.core.audio.DbfsQuantizer
import com.miuraken.chunkflow.core.audio.FeatureAccumulator
import com.miuraken.chunkflow.core.audio.FrameFeatureExtractor
import com.miuraken.chunkflow.core.audio.PcmSlidingWindowBuffer
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.audio.SpeechProbabilityStreamFactory
import com.miuraken.chunkflow.core.audio.StoredFeatures
import com.miuraken.chunkflow.core.audio.TranscriptOffsetAligner
import com.miuraken.chunkflow.core.data.di.ChunkFlowDispatchers
import com.miuraken.chunkflow.core.data.di.Dispatcher
import com.miuraken.chunkflow.core.data.library.DefaultLibraryRepository
import com.miuraken.chunkflow.core.data.library.LibraryMediaFileResolver
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.data.stt.ItemSttOverrideRepository
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
import com.miuraken.chunkflow.core.media.ChunkTimeRange
import com.miuraken.chunkflow.core.media.MlKitOnDeviceTextTranslator
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriber
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriptionResult
import com.miuraken.chunkflow.core.media.OnDeviceTextTranslator
import com.miuraken.chunkflow.core.media.PcmChunkLanguageRequest
import com.miuraken.chunkflow.core.model.AnalysisError
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.Bookmark
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ChunkTranscriptionTrialResult
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.TranscriptionStep
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.model.WordTiming
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
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
    private val itemSttOverrideRepository: ItemSttOverrideRepository? = null,
    private val textTranslator: OnDeviceTextTranslator = MlKitOnDeviceTextTranslator(),
    /** CPU を使う処理（一括言語判定の Whisper 推論など）の Dispatcher。 */
    @Dispatcher(ChunkFlowDispatchers.Default) private val computeDispatcher: CoroutineDispatcher = ioDispatcher,
    private val speechProbabilityStreamFactory: SpeechProbabilityStreamFactory? = null,
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
        audioStreamDecoder: AudioStreamDecoder,
        userSettingsRepository: UserSettingsRepository,
        itemSttOverrideRepository: ItemSttOverrideRepository,
        textTranslator: OnDeviceTextTranslator,
        @Dispatcher(ChunkFlowDispatchers.Default) computeDispatcher: CoroutineDispatcher,
        speechProbabilityStreamFactory: SpeechProbabilityStreamFactory,
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
        audioStreamDecoder = audioStreamDecoder,
        userSettingsRepository = userSettingsRepository,
        itemSttOverrideRepository = itemSttOverrideRepository,
        textTranslator = textTranslator,
        computeDispatcher = computeDispatcher,
        speechProbabilityStreamFactory = speechProbabilityStreamFactory,
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
    private val backgroundScope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val backgroundJobs = ConcurrentHashMap<MediaId, Job>()
    private val backgroundTranscriptionMediaIds: MutableSet<MediaId> = ConcurrentHashMap.newKeySet()
    private val backgroundDeferredEmptyChunkIds = ConcurrentHashMap<MediaId, MutableSet<Long>>()
    private val lookaheadMediaMutexes = ConcurrentHashMap<MediaId, Mutex>()
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
    private val activeTranscriptionJobs = ConcurrentHashMap<MediaId, MutableSet<Job>>()
    private val targetLanguageOnlyMediaIds: MutableSet<MediaId> = ConcurrentHashMap.newKeySet()
    private val activePipelinedLidJobs = ConcurrentHashMap<MediaId, CompletableDeferred<Unit>>()
    private val activeLanguageDetectionJobs = ConcurrentHashMap<MediaId, Job>()
    private val pipelinedLidProcessedChunkIds = ConcurrentHashMap<MediaId, MutableSet<Long>>()
    private val globalBackgroundTranscriptionEnabled = AtomicBoolean(true)
    private val defaultAutoStartLookaheadEnabled = AtomicBoolean(true)

    init {
        val settingsFlow = userSettingsRepository?.settings
        if (settingsFlow != null) {
            if (settingsFlow is kotlinx.coroutines.flow.StateFlow<*>) {
                val initialSettings = settingsFlow.value as? com.miuraken.chunkflow.core.model.UserSettings
                if (initialSettings != null) {
                    globalBackgroundTranscriptionEnabled.set(initialSettings.backgroundTranscriptionEnabled)
                    defaultAutoStartLookaheadEnabled.set(
                        initialSettings.lookaheadTranscriptionEnabled && initialSettings.autoStartTranscriptionOnOpen,
                    )
                }
            }
            backgroundScope.launch(ioDispatcher) {
                settingsFlow
                    .map { it.lookaheadTranscriptionEnabled && it.autoStartTranscriptionOnOpen }
                    .distinctUntilChanged()
                    .collect { defaultEnabled ->
                        defaultAutoStartLookaheadEnabled.set(defaultEnabled)
                        for ((mediaId, stateFlow) in lookaheadStates) {
                            if (mediaId !in explicitlyConfiguredLookaheadMediaIds) {
                                stateFlow.update { current ->
                                    if (current.enabled == defaultEnabled) current else current.copy(enabled = defaultEnabled)
                                }
                            }
                        }
                    }
            }
            backgroundScope.launch {
                settingsFlow
                    .map { it.backgroundTranscriptionEnabled }
                    .distinctUntilChanged()
                    .collect { enabled ->
                        globalBackgroundTranscriptionEnabled.set(enabled)
                        if (!enabled) {
                            for (mediaId in backgroundTranscriptionMediaIds.toList()) {
                                if (!isMediaOpenForTranscription(mediaId)) {
                                    backgroundJobs.remove(mediaId)?.cancel()
                                    cancelActiveTranscriptionJobs(
                                        mediaId,
                                        CancellationException("Global background transcription disabled"),
                                    )
                                    lookaheadStateFor(mediaId).update {
                                        it.copy(
                                            backgroundEnabled = false,
                                            inFlightChunkId = null,
                                            inFlightStep = null,
                                            inFlightDetail = null,
                                            inFlightStartedAtEpochMs = null,
                                            isForcedChunkInFlight = false,
                                            queuedChunkIds = emptySet(),
                                        )
                                    }
                                } else {
                                    lookaheadStateFor(mediaId).update {
                                        it.copy(backgroundEnabled = false)
                                    }
                                }
                            }
                            if (activeOpenMediaIds.isEmpty()) {
                                runCatching { onDeviceChunkTranscriber.releaseIdleResources() }
                            }
                        } else {
                            for (mediaId in backgroundTranscriptionMediaIds.toList()) {
                                if (lookaheadStateFor(mediaId).value.enabled) {
                                    lookaheadStateFor(mediaId).update {
                                        it.copy(backgroundEnabled = true)
                                    }
                                    if (!isMediaOpenForTranscription(mediaId)) {
                                        startBackgroundTranscriptionLoop(mediaId)
                                    }
                                }
                            }
                        }
                    }
            }
        }
    }

    private suspend fun ensureDefaultLookaheadEnabledConfigured(id: MediaId) {
        if (id in explicitlyConfiguredLookaheadMediaIds) return
        val repo = userSettingsRepository ?: return
        val defaultEnabled = runCatching {
            val s = repo.settings.first()
            s.lookaheadTranscriptionEnabled && s.autoStartTranscriptionOnOpen
        }.getOrElse { defaultAutoStartLookaheadEnabled.get() }
        defaultAutoStartLookaheadEnabled.set(defaultEnabled)
        val stateFlow = lookaheadStateFor(id)
        if (id !in explicitlyConfiguredLookaheadMediaIds && stateFlow.value.enabled != defaultEnabled) {
            stateFlow.update { it.copy(enabled = defaultEnabled) }
        }
    }

    private fun isGlobalBackgroundTranscriptionEnabled(): Boolean {
        val settingsFlow = userSettingsRepository?.settings
        if (settingsFlow is kotlinx.coroutines.flow.StateFlow<*>) {
            val current = (settingsFlow.value as? com.miuraken.chunkflow.core.model.UserSettings)?.backgroundTranscriptionEnabled
            if (current != null) {
                globalBackgroundTranscriptionEnabled.set(current)
                return current
            }
        }
        return globalBackgroundTranscriptionEnabled.get()
    }

    private suspend fun refreshGlobalBackgroundTranscriptionEnabled(): Boolean {
        val repo = userSettingsRepository ?: return globalBackgroundTranscriptionEnabled.get()
        val enabled = runCatching { repo.settings.first().backgroundTranscriptionEnabled }
            .getOrDefault(globalBackgroundTranscriptionEnabled.get())
        globalBackgroundTranscriptionEnabled.set(enabled)
        return enabled
    }

    private fun isBackgroundTranscriptionAllowedFor(id: MediaId): Boolean = isGlobalBackgroundTranscriptionEnabled() && backgroundTranscriptionMediaIds.contains(id)

    private fun isMediaOpenForTranscription(id: MediaId): Boolean = !hasExplicitSessionTracking.get() || activeOpenMediaIds.contains(id)

    private fun canContinueTranscriptionForMedia(id: MediaId): Boolean = isMediaOpenForTranscription(id) || isBackgroundTranscriptionAllowedFor(id)

    private fun lookaheadMutexFor(id: MediaId): Mutex = lookaheadMediaMutexes.computeIfAbsent(id) { Mutex() }

    private fun currentSkippedChunkIds(id: MediaId): Set<ChunkId> = attemptedEmptyChunkIds[id]
        ?.map { ChunkId(it) }
        ?.toSet()
        .orEmpty()

    private suspend fun <T> withTrackedTranscriptionJob(id: MediaId, block: suspend () -> T): T {
        val job = currentCoroutineContext()[Job]
        val jobSet = activeTranscriptionJobs.computeIfAbsent(id) {
            ConcurrentHashMap.newKeySet()
        }
        if (job != null) {
            jobSet.add(job)
        }
        return try {
            block()
        } finally {
            if (job != null) {
                jobSet.remove(job)
            }
        }
    }

    private fun cancelActiveTranscriptionJobs(id: MediaId, cause: CancellationException) {
        val jobs = activeTranscriptionJobs[id]?.toList().orEmpty()
        activeTranscriptionJobs[id]?.clear()
        for (job in jobs) {
            job.cancel(cause)
        }
    }

    override fun observeStatus(id: MediaId): Flow<Map<AnalysisStage, StageStatus>> = analysisStateDao.observeByMediaId(id.value)
        .map { entities ->
            entities.mapNotNull { it.toStageAndStatus() }.toMap()
        }
        .distinctUntilChanged()

    override fun observeWaveform(id: MediaId): Flow<Waveform?> {
        val statusTrigger = analysisStateDao.observeByMediaId(id.value)
            .map { list ->
                list.firstOrNull { it.stage == AnalysisStage.FEATURES.name }?.let {
                    Triple(it.status, it.progress, it.updatedAt)
                }
            }
            .distinctUntilChanged()
        val manualTrigger = waveformRefreshEvents
            .filter { it == id }
            .onStart { emit(id) }
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
            val decoder = audioStreamDecoder
            if (decoder != null) {
                val lidBarrier = if (AnalysisStage.SEGMENTATION in stages) {
                    CompletableDeferred<Unit>().also { deferred ->
                        activePipelinedLidJobs[id] = deferred
                    }
                } else {
                    null
                }
                try {
                    while (true) {
                        val effectiveHigh = explicitHighPriority || pipelineGate.isMediaOpen(id)
                        val completed = pipelineGate.withLock(highPriority = effectiveHigh) {
                            try {
                                runAnalysisPipeline(
                                    mediaId = id,
                                    requestedStages = stages,
                                    decoder = decoder,
                                    skipIfAlreadyDone = true,
                                )
                                true
                            } catch (_: AnalysisPreemptedException) {
                                resetStagesToNotStarted(id, stages)
                                false
                            }
                        }
                        if (completed) break
                    }
                } finally {
                    if (lidBarrier != null) {
                        activePipelinedLidJobs.remove(id, lidBarrier)
                        lidBarrier.complete(Unit)
                    }
                }
            } else {
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
            }
        }
    }

    private suspend fun runAnalysisPipeline(
        mediaId: MediaId,
        requestedStages: Set<AnalysisStage>,
        decoder: AudioStreamDecoder,
        skipIfAlreadyDone: Boolean = false,
    ) {
        val mediaItemForSource = mediaItemDao.getById(mediaId.value)
        val audioSource = resolveAudioSource(
            mediaId = mediaId,
            mediaFileResolver = mediaFileResolver,
            mediaItemDao = mediaItemDao,
            cachedMediaItem = mediaItemForSource,
        )
        val analysisDir = resolveAnalysisDir(mediaId)?.apply { mkdirs() }
        if (audioSource == null || analysisDir == null || !analysisDir.exists()) {
            markStagesFailed(mediaId, requestedStages, AnalysisError.IO)
            return
        }

        val waveformFile = File(analysisDir, WAVEFORM_FILE_NAME)
        val featuresFile = File(analysisDir, FEATURES_FILE_NAME)
        val existingStoredFeatures = if (featuresFile.isFile) {
            AnalysisBinaryFormat.readFeatures(featuresFile)
        } else {
            null
        }
        val needsVadUpgrade = speechProbabilityStreamFactory != null &&
            existingStoredFeatures?.speechProbability == null

        if (skipIfAlreadyDone && waveformFile.isFile && existingStoredFeatures != null && !needsVadUpgrade) {
            val existingByStage = analysisStateDao.getByMediaId(mediaId.value)
                .mapNotNull { it.toStageAndStatus() }
                .toMap()
            if (requestedStages.all { existingByStage[it] is StageStatus.Done }) {
                activePipelinedLidJobs.remove(mediaId)?.complete(Unit)
                notifyWaveformUpdated(mediaId)
                val item = mediaItemForSource ?: mediaItemDao.getById(mediaId.value)
                var launchedLid = false
                if (
                    AnalysisStage.SEGMENTATION in requestedStages &&
                    item != null &&
                    item.targetLang.isNotBlank() &&
                    !item.baseLang.isNullOrBlank() &&
                    !item.targetLang.equals(item.baseLang, ignoreCase = true) &&
                    onDeviceChunkTranscriber !== NoOpOnDeviceChunkTranscriber
                ) {
                    val allChunks = chunkDao.getByMediaIdAndOrigin(mediaId.value, ChunkOrigin.AUTO.name)
                    if (allChunks.isNotEmpty()) {
                        val transcriptsByChunkId = transcriptDao.getByChunkIds(allChunks.map { it.id }).associateBy { it.chunkId }
                        val hasMissingLid = allChunks.any { chunk ->
                            val existing = transcriptsByChunkId[chunk.id]
                            existing == null ||
                                (
                                    existing.text.isBlank() &&
                                        (existing.language.isNullOrBlank() || existing.language.equals("und", ignoreCase = true)) &&
                                        !(existing.userEdited && existing.language == null)
                                    )
                        }
                        if (hasMissingLid) {
                            launchedLid = true
                            startLanguageDetectionInternal(mediaId, onlyMissingLanguage = true)
                        }
                    }
                }
                ensureDefaultLookaheadEnabledConfigured(mediaId)
                val resumeStartIndex = resumeLookaheadStartIndexOrNull(mediaId)
                if (
                    !launchedLid &&
                    resumeStartIndex != null &&
                    lookaheadStateFor(mediaId).value.enabled &&
                    canContinueTranscriptionForMedia(mediaId)
                ) {
                    triggerLookaheadAt(mediaId, resumeStartIndex)
                }
                return
            }
        }

        val needDecode = AnalysisStage.FEATURES in requestedStages || existingStoredFeatures == null || needsVadUpgrade

        try {
            coroutineScope {
                var decodedDurationMs: Long? = null
                val mediaItem = mediaItemForSource ?: mediaItemDao.getById(mediaId.value)
                val segmentationParams = if (AnalysisStage.SEGMENTATION in requestedStages) {
                    val customParams = runCatching {
                        itemStateDao.getByMediaId(mediaId.value)?.let {
                            ItemStateEntity.decodeSegmentationParams(it.customSegmentation)
                        }
                    }.getOrNull()
                    customParams ?: runCatching {
                        userSettingsRepository?.settings?.first()?.defaultSegmentationParams
                    }.getOrNull() ?: SegmentationParams()
                } else {
                    null
                }

                val lidLanguagePair = if (
                    needDecode &&
                    AnalysisStage.SEGMENTATION in requestedStages &&
                    onDeviceChunkTranscriber !== NoOpOnDeviceChunkTranscriber
                ) {
                    val target = mediaItem?.targetLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                    val base = mediaItem?.baseLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                    if (target != null && base != null && !target.value.equals(base.value, ignoreCase = true)) {
                        LanguagePair(target = target, base = base)
                    } else {
                        null
                    }
                } else {
                    null
                }

                val pcmWindowBuffer = if (lidLanguagePair != null) {
                    PcmSlidingWindowBuffer(sampleRateHz = AnalysisWorker.TARGET_SAMPLE_RATE_HZ)
                } else {
                    null
                }
                val lidRequestChannel = if (lidLanguagePair != null) {
                    Channel<PcmChunkLanguageRequest>(capacity = Channel.UNLIMITED)
                } else {
                    null
                }
                val chunkPersistenceMutex = Mutex()
                val enqueuedLidRanges = ArrayList<ChunkTimeRange>()
                val processedLidRanges = java.util.concurrent.CopyOnWriteArrayList<ChunkTimeRange>()
                val lidCompletedCount = AtomicInteger(0)
                val lidTotalEnqueuedCount = AtomicInteger(0)
                val stateFlow = lookaheadStateFor(mediaId)

                fun enqueueConfirmedChunksForLid(
                    insertedChunks: List<ChunkEntity>,
                    buffer: PcmSlidingWindowBuffer,
                    channel: Channel<PcmChunkLanguageRequest>,
                ) {
                    for (chunk in insertedChunks) {
                        val chunkDuration = (chunk.endMs - chunk.startMs).coerceAtLeast(1L)
                        val alreadyEnqueued = enqueuedLidRanges.any { existing ->
                            val overlap = minOf(chunk.endMs, existing.endMs) - maxOf(chunk.startMs, existing.startMs)
                            overlap > chunkDuration / 2L
                        }
                        if (alreadyEnqueued) continue

                        val pcmSlice = buffer.sliceLeadingPcm(
                            startMs = chunk.startMs,
                            endMs = chunk.endMs,
                        )
                        if (pcmSlice == null || pcmSlice.isEmpty()) {
                            continue
                        }
                        enqueuedLidRanges.add(ChunkTimeRange(startMs = chunk.startMs, endMs = chunk.endMs))
                        lidTotalEnqueuedCount.incrementAndGet()
                        channel.trySend(
                            PcmChunkLanguageRequest(
                                chunkId = chunk.id,
                                startMs = chunk.startMs,
                                endMs = chunk.endMs,
                                pcm16kMono = pcmSlice,
                            ),
                        )
                    }
                }

                val localAudioFile = if (lidLanguagePair != null) {
                    (audioSource as? ResolvedAudioSource.LocalFile)?.file
                } else {
                    null
                }
                val lidAudioScopeKey = localAudioFile?.absolutePath ?: resolveExpectedAudioScopeKey(mediaId, mediaItem, audioSource, context)
                val lidParallelism = OnDeviceChunkTranscriber.recommendedLanguageDetectionParallelism()

                val lidConsumerJob = if (lidLanguagePair != null && lidRequestChannel != null) {
                    stateFlow.update { it.copy(isLanguageDetectionRunning = true) }
                    launch(computeDispatcher) {
                        val lidStartedAt = clock()
                        val pendingStreamLidResults =
                            ArrayList<Pair<PcmChunkLanguageRequest, OnDeviceChunkTranscriptionResult?>>()
                        var lastStreamLidFlushMs = lidStartedAt

                        suspend fun flushPendingStreamLidResultsLocked() {
                            if (pendingStreamLidResults.isEmpty()) return
                            val batch = ArrayList(pendingStreamLidResults)
                            pendingStreamLidResults.clear()

                            val currentAutoChunks = chunkDao.getByMediaIdAndOrigin(
                                mediaId.value,
                                ChunkOrigin.AUTO.name,
                            )
                            if (currentAutoChunks.isEmpty()) return

                            val pendingUpserts = LinkedHashMap<Long, OnDeviceChunkTranscriptionResult>()
                            val pendingDeletes = LinkedHashSet<Long>()
                            for ((req, detected) in batch) {
                                val matchedChunk = currentAutoChunks.firstOrNull { it.id == req.chunkId }
                                    ?: currentAutoChunks.firstOrNull {
                                        kotlin.math.abs(it.startMs - req.startMs) <= 50L &&
                                            kotlin.math.abs(it.endMs - req.endMs) <= 50L
                                    }
                                    ?: currentAutoChunks
                                        .map { c ->
                                            c to (minOf(req.endMs, c.endMs) - maxOf(req.startMs, c.startMs))
                                        }
                                        .filter { it.second > 0L }
                                        .maxByOrNull { it.second }
                                        ?.first
                                if (matchedChunk != null) {
                                    if (detected?.language != null) {
                                        pendingUpserts[matchedChunk.id] = detected
                                        pendingDeletes.remove(matchedChunk.id)
                                    } else {
                                        pendingUpserts.remove(matchedChunk.id)
                                        pendingDeletes.add(matchedChunk.id)
                                    }
                                }
                            }
                            withContext(NonCancellable) {
                                flushPendingLidTranscriptWrites(
                                    pendingUpserts = pendingUpserts,
                                    pendingDeletes = pendingDeletes,
                                )
                            }
                        }

                        try {
                            onDeviceChunkTranscriber.detectChunkLanguagesFromPcmStream(
                                audioScopeKey = lidAudioScopeKey,
                                requests = lidRequestChannel,
                                languagePair = lidLanguagePair,
                                parallelism = lidParallelism,
                                computeDispatcher = computeDispatcher,
                                audioFile = localAudioFile,
                            ) { req, detected ->
                                chunkPersistenceMutex.withLock {
                                    pendingStreamLidResults.add(req to detected)
                                    processedLidRanges.add(ChunkTimeRange(startMs = req.startMs, endMs = req.endMs))
                                    val nowMs = clock()
                                    val isClockAdvancing = nowMs > lidStartedAt
                                    val shouldFlush = !isClockAdvancing ||
                                        nowMs - lastStreamLidFlushMs >= LID_DB_BATCH_FLUSH_INTERVAL_MS ||
                                        pendingStreamLidResults.size >= LID_DB_BATCH_MAX_SIZE
                                    if (shouldFlush) {
                                        flushPendingStreamLidResultsLocked()
                                        lastStreamLidFlushMs = nowMs
                                    }
                                }
                                lidCompletedCount.incrementAndGet()
                            }
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            runCatching {
                                onDeviceChunkTranscriber.recordExternalDiagnostic(
                                    stage = "LID_ONLY",
                                    message = "デコード並列の言語判定を中断 (${e.javaClass.simpleName})",
                                    isWarningOrError = true,
                                )
                            }
                        } finally {
                            withContext(NonCancellable) {
                                chunkPersistenceMutex.withLock {
                                    flushPendingStreamLidResultsLocked()
                                }
                                val done = lidCompletedCount.get()
                                val total = lidTotalEnqueuedCount.get()
                                if (total > 0) {
                                    updateQueuedChunkIdsState(mediaId)
                                    persistTranscriptBackupFile(mediaId)
                                    val elapsedMs = (clock() - lidStartedAt).coerceAtLeast(0L)
                                    val elapsedTenths = (elapsedMs + 50L) / 100L
                                    runCatching {
                                        onDeviceChunkTranscriber.recordExternalDiagnostic(
                                            stage = "LID_ONLY",
                                            message = "デコード並列の言語判定を終了 ($done / $total チャンク、" +
                                                "${elapsedTenths / 10}.${elapsedTenths % 10} 秒、" +
                                                "平均 ${elapsedMs / done.coerceAtLeast(1)} ms/チャンク)",
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else {
                    null
                }

                try {
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

                        val expectedDurationMs = mediaItem?.durationMs?.takeIf { it > 0L }

                        val coarseWaveform = when (audioSource) {
                            is ResolvedAudioSource.LocalFile -> decoder.decodeCoarseOverviewWaveform(
                                file = audioSource.file,
                                expectedDurationMs = expectedDurationMs,
                            )

                            is ResolvedAudioSource.SourceUri -> decoder.decodeCoarseOverviewWaveform(
                                uri = audioSource.uri,
                                expectedDurationMs = expectedDurationMs,
                            )
                        }?.takeIf { it.peak.isNotEmpty() }
                        if (coarseWaveform != null) {
                            val saved = AnalysisBinaryFormat.writeWaveformAtomic(
                                file = waveformFile,
                                waveform = coarseWaveform,
                            )
                            if (saved) {
                                notifyWaveformUpdated(mediaId)
                            }
                        }

                        val accumulator = FeatureAccumulator(binMs = ChunkSegmenter.DEFAULT_BIN_MS)
                        val extractor = FrameFeatureExtractor(
                            sampleRate = AnalysisWorker.TARGET_SAMPLE_RATE_HZ,
                            sink = accumulator,
                        )
                        val vadStream = runCatching { speechProbabilityStreamFactory?.createStream() }.getOrNull()

                        var lastCheckpointMs = now
                        var lastProgressUpdateMs = now
                        var lastSavedDecodedBins = 0
                        var lastSegmentedBins = 0
                        var progressiveConfirmedCount = 0
                        var progressiveSegmentationRunning = false
                        var firstProgressiveSaved = false

                        val sampleSink = SampleSink { samples, count ->
                            if (!pipelineGate.isMediaOpen(mediaId) && pipelineGate.hasHighPriorityWaiters()) {
                                throw AnalysisPreemptedException()
                            }
                            extractor.process(samples, count)
                            vadStream?.onSamples(samples, count)
                            pcmWindowBuffer?.appendFloats(samples, count)
                        }
                        val onProgressCallback: suspend (com.miuraken.chunkflow.core.media.DecodeProgress) -> Unit = { progress ->
                            if (!pipelineGate.isMediaOpen(mediaId) && pipelineGate.hasHighPriorityWaiters()) {
                                throw AnalysisPreemptedException()
                            }
                            val currentMs = clock()
                            val isClockAdvancing = currentMs > now
                            // 初回プレビュー後は、十分な新規デコード区間が溜まり、かつ実時間でも一定間隔が経過したときのみ波形ファイルを書き直す
                            // （実機では 15 秒区間が約 30ms ごとにデコードされるため、OR 条件だと秒間 30 回以上ファイル書き直し・UI 再描画が走ってしまう）
                            val hasNew15sSegment =
                                accumulator.size - lastSavedDecodedBins >= FeatureAccumulator.BINS_PER_15_SECONDS - 2
                            val shouldSaveWaveform = if (!firstProgressiveSaved) {
                                accumulator.size >= FeatureAccumulator.BINS_PER_INITIAL_PREVIEW - 2
                            } else if (isClockAdvancing) {
                                currentMs - lastCheckpointMs >= 600L && hasNew15sSegment
                            } else {
                                hasNew15sSegment
                            }
                            val shouldUpdateProgress =
                                currentMs - lastProgressUpdateMs >= AnalysisWorker.PROGRESS_DB_INTERVAL_MS

                            if (shouldSaveWaveform && accumulator.size > 0) {
                                AnalysisBinaryFormat.writeWaveformAtomic(
                                    file = waveformFile,
                                    waveform = accumulator.toProgressiveWaveform(coarseWaveform),
                                )
                                lastCheckpointMs = currentMs
                                lastSavedDecodedBins = accumulator.size
                                firstProgressiveSaved = true
                                notifyWaveformUpdated(mediaId)
                            }

                            if (
                                segmentationParams != null &&
                                accumulator.size - lastSegmentedBins >= FeatureAccumulator.BINS_PER_15_SECONDS - 2
                            ) {
                                lastSegmentedBins = accumulator.size
                                if (!progressiveSegmentationRunning) {
                                    progressiveSegmentationRunning = true
                                    analysisStateDao.upsert(
                                        StageStatus.Running(progress = progress.fraction).toEntity(
                                            mediaId = mediaId,
                                            stage = AnalysisStage.SEGMENTATION,
                                            algorithmVersion = CURRENT_ALGORITHM_VERSION,
                                            updatedAtEpochMs = currentMs,
                                        ),
                                    )
                                }
                                val currentEnergy = accumulator.logEnergyDb()
                                val currentSpeechProb = vadStream?.currentProbabilities(
                                    targetFrameCount = currentEnergy.size,
                                    binMs = ChunkSegmenter.DEFAULT_BIN_MS,
                                )
                                val provisionalChunks = chunkSegmenter.segment(
                                    logEnergyDb = currentEnergy,
                                    params = segmentationParams,
                                    binMs = ChunkSegmenter.DEFAULT_BIN_MS,
                                    durationMs = currentEnergy.size.toLong() * ChunkSegmenter.DEFAULT_BIN_MS,
                                    speechProbability = currentSpeechProb,
                                )
                                val confirmedChunks = if (provisionalChunks.size > 1) {
                                    provisionalChunks.dropLast(1)
                                } else {
                                    emptyList()
                                }
                                if (confirmedChunks.size > progressiveConfirmedCount) {
                                    val confirmedEntities = confirmedChunks.map {
                                        it.toEntity(
                                            mediaId = mediaId,
                                            algorithmVersion = CURRENT_ALGORITHM_VERSION,
                                        )
                                    }
                                    chunkPersistenceMutex.withLock {
                                        val inserted = chunkDao.replaceChunksTransaction(
                                            mediaId = mediaId.value,
                                            origin = ChunkOrigin.AUTO.name,
                                            newChunks = confirmedEntities,
                                            newTranscriptsByIndex = emptyMap(),
                                            preserveAllOldTranscripts = true,
                                        )
                                        if (pcmWindowBuffer != null && lidRequestChannel != null) {
                                            enqueueConfirmedChunksForLid(
                                                insertedChunks = inserted,
                                                buffer = pcmWindowBuffer,
                                                channel = lidRequestChannel,
                                            )
                                            val safeDiscardMs = (confirmedChunks.last().endMs - 500L).coerceAtLeast(0L)
                                            pcmWindowBuffer.discardBeforeMs(safeDiscardMs)
                                        }
                                    }
                                    progressiveConfirmedCount = confirmedChunks.size
                                    if (shouldUpdateProgress) {
                                        analysisStateDao.upsert(
                                            StageStatus.Running(progress = progress.fraction).toEntity(
                                                mediaId = mediaId,
                                                stage = AnalysisStage.SEGMENTATION,
                                                algorithmVersion = CURRENT_ALGORITHM_VERSION,
                                                updatedAtEpochMs = currentMs,
                                            ),
                                        )
                                    }
                                }
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
                        }
                        val decodeResult = when (audioSource) {
                            is ResolvedAudioSource.LocalFile -> decoder.decode(
                                file = audioSource.file,
                                sink = sampleSink,
                                outputSampleRate = AnalysisWorker.TARGET_SAMPLE_RATE_HZ,
                                expectedDurationMs = expectedDurationMs,
                                onProgress = onProgressCallback,
                            )

                            is ResolvedAudioSource.SourceUri -> decoder.decode(
                                uri = audioSource.uri,
                                sink = sampleSink,
                                outputSampleRate = AnalysisWorker.TARGET_SAMPLE_RATE_HZ,
                                expectedDurationMs = expectedDurationMs,
                                onProgress = onProgressCallback,
                            )
                        }

                        when (decodeResult) {
                            is AudioDecodeResult.Failure -> {
                                pcmWindowBuffer?.clear()
                                lidRequestChannel?.close()
                                lidConsumerJob?.cancel()
                                markStagesFailed(mediaId, requestedStages, decodeResult.error)
                                return@coroutineScope
                            }

                            is AudioDecodeResult.Success -> {
                                extractor.finish()
                                decodedDurationMs = decodeResult.durationMs
                                val finalEnergy = accumulator.logEnergyDb()
                                val finalSpeechProb = vadStream?.finish(
                                    targetFrameCount = finalEnergy.size,
                                    binMs = ChunkSegmenter.DEFAULT_BIN_MS,
                                )

                                val waveformSaved = AnalysisBinaryFormat.writeWaveformAtomic(
                                    file = waveformFile,
                                    waveform = accumulator.toWaveform(),
                                )
                                val featuresSaved = AnalysisBinaryFormat.writeFeaturesAtomic(
                                    file = featuresFile,
                                    features = StoredFeatures(
                                        binMs = ChunkSegmenter.DEFAULT_BIN_MS,
                                        logEnergyDb = finalEnergy,
                                        speechProbability = finalSpeechProb,
                                    ),
                                )
                                if (!waveformSaved || !featuresSaved) {
                                    pcmWindowBuffer?.clear()
                                    lidRequestChannel?.close()
                                    lidConsumerJob?.cancel()
                                    markStagesFailed(mediaId, requestedStages, AnalysisError.IO)
                                    return@coroutineScope
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
                            pcmWindowBuffer?.clear()
                            lidRequestChannel?.close()
                            lidConsumerJob?.cancel()
                            analysisStateDao.upsert(
                                StageStatus.Failed(AnalysisError.IO).toEntity(
                                    mediaId = mediaId,
                                    stage = AnalysisStage.SEGMENTATION,
                                    algorithmVersion = CURRENT_ALGORITHM_VERSION,
                                    updatedAtEpochMs = clock(),
                                ),
                            )
                            return@coroutineScope
                        }

                        val params = segmentationParams ?: SegmentationParams()

                        val durationMs = decodedDurationMs?.takeIf { it > 0L }
                            ?: mediaItemDao.getById(mediaId.value)?.durationMs?.takeIf { it > 0L }
                            ?: storedFeatures.durationMs

                        val autoChunks = chunkSegmenter.segment(
                            logEnergyDb = storedFeatures.logEnergyDb,
                            params = params,
                            binMs = storedFeatures.binMs,
                            durationMs = durationMs,
                            speechProbability = storedFeatures.speechProbability,
                        )
                        val chunkEntities = autoChunks.map {
                            it.toEntity(
                                mediaId = mediaId,
                                algorithmVersion = CURRENT_ALGORITHM_VERSION,
                            )
                        }

                        chunkPersistenceMutex.withLock {
                            val finalInserted = chunkDao.replaceChunksTransaction(
                                mediaId = mediaId.value,
                                origin = ChunkOrigin.AUTO.name,
                                newChunks = chunkEntities,
                                newTranscriptsByIndex = emptyMap(),
                                preserveAllOldTranscripts = true,
                            )
                            if (pcmWindowBuffer != null && lidRequestChannel != null) {
                                enqueueConfirmedChunksForLid(
                                    insertedChunks = finalInserted,
                                    buffer = pcmWindowBuffer,
                                    channel = lidRequestChannel,
                                )
                                pcmWindowBuffer.clear()
                                lidRequestChannel.close()
                            }
                        }

                        analysisStateDao.upsert(
                            StageStatus.Done.toEntity(
                                mediaId = mediaId,
                                stage = AnalysisStage.SEGMENTATION,
                                algorithmVersion = CURRENT_ALGORITHM_VERSION,
                                updatedAtEpochMs = clock(),
                            ),
                        )

                        if (lidConsumerJob != null) {
                            while (lidConsumerJob.isActive) {
                                if (!pipelineGate.isMediaOpen(mediaId) && pipelineGate.hasHighPriorityWaiters()) {
                                    lidConsumerJob.cancel()
                                    throw AnalysisPreemptedException()
                                }
                                delay(100L)
                            }
                            if (!pipelineGate.isMediaOpen(mediaId) && pipelineGate.hasHighPriorityWaiters()) {
                                throw AnalysisPreemptedException()
                            }
                            lidConsumerJob.join()
                            activePipelinedLidJobs.remove(mediaId)?.complete(Unit)

                            val finalAutoChunks = chunkDao.getByMediaIdAndOrigin(
                                mediaId.value,
                                ChunkOrigin.AUTO.name,
                            )
                            val finalAutoIds = finalAutoChunks.map { it.id }.toSet()
                            val evaluatedAutoIds = finalAutoChunks.filter { c ->
                                processedLidRanges.any { r ->
                                    val overlap = minOf(c.endMs, r.endMs) - maxOf(c.startMs, r.startMs)
                                    overlap > (c.endMs - c.startMs).coerceAtLeast(1L) / 2L
                                }
                            }.map { it.id }.toSet()

                            val missingLidChunkIds = finalAutoIds - evaluatedAutoIds

                            pipelinedLidProcessedChunkIds.computeIfAbsent(mediaId) {
                                ConcurrentHashMap.newKeySet()
                            }.addAll(evaluatedAutoIds)

                            ensureDefaultLookaheadEnabledConfigured(mediaId)
                            val resumeStartIndex = resumeLookaheadStartIndexOrNull(mediaId)
                            if (missingLidChunkIds.isNotEmpty()) {
                                startLanguageDetectionInternal(mediaId, onlyMissingLanguage = true)
                            } else {
                                stateFlow.update { it.copy(isLanguageDetectionRunning = false) }
                                if (
                                    resumeStartIndex != null &&
                                    stateFlow.value.enabled &&
                                    canContinueTranscriptionForMedia(mediaId)
                                ) {
                                    triggerLookaheadAt(mediaId, resumeStartIndex)
                                }
                            }
                        } else {
                            activePipelinedLidJobs.remove(mediaId)?.complete(Unit)
                            ensureDefaultLookaheadEnabledConfigured(mediaId)
                            val resumeStartIndex = resumeLookaheadStartIndexOrNull(mediaId)
                            if (
                                mediaItem != null &&
                                mediaItem.targetLang.isNotBlank() &&
                                !mediaItem.baseLang.isNullOrBlank() &&
                                !mediaItem.targetLang.equals(mediaItem.baseLang, ignoreCase = true) &&
                                onDeviceChunkTranscriber !== NoOpOnDeviceChunkTranscriber
                            ) {
                                startLanguageDetectionInternal(mediaId, onlyMissingLanguage = false)
                            } else if (
                                resumeStartIndex != null &&
                                stateFlow.value.enabled &&
                                canContinueTranscriptionForMedia(mediaId)
                            ) {
                                triggerLookaheadAt(mediaId, resumeStartIndex)
                            }
                        }
                    } else {
                        pcmWindowBuffer?.clear()
                        lidRequestChannel?.close()
                        lidConsumerJob?.cancel()
                        if (activeLanguageDetectionJobs[mediaId]?.isActive != true) {
                            stateFlow.update { it.copy(isLanguageDetectionRunning = false) }
                        }
                    }
                } catch (t: Throwable) {
                    pcmWindowBuffer?.clear()
                    lidRequestChannel?.close()
                    lidConsumerJob?.cancel()
                    if (activeLanguageDetectionJobs[mediaId]?.isActive != true) {
                        stateFlow.update { it.copy(isLanguageDetectionRunning = false) }
                    }
                    throw t
                }
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
            var storedFeatures = featuresFile?.let { AnalysisBinaryFormat.readFeatures(it) }

            if (
                storedFeatures != null &&
                storedFeatures.speechProbability == null &&
                speechProbabilityStreamFactory != null &&
                audioStreamDecoder != null &&
                featuresFile != null
            ) {
                val upgraded = upgradeStoredFeaturesWithVadIfPossible(id, featuresFile)
                if (upgraded != null) {
                    storedFeatures = upgraded
                }
            }

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
                speechProbability = storedFeatures.speechProbability,
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
            pipelinedLidProcessedChunkIds.remove(id)
            val completedAt = clock()
            analysisStateDao.upsert(
                StageStatus.Done.toEntity(
                    mediaId = id,
                    stage = AnalysisStage.SEGMENTATION,
                    algorithmVersion = CURRENT_ALGORITHM_VERSION,
                    updatedAtEpochMs = completedAt,
                ),
            )
            if (onDeviceChunkTranscriber !== NoOpOnDeviceChunkTranscriber) {
                startLanguageDetectionInternal(id, onlyMissingLanguage = false)
            }
        }
    }

    private suspend fun upgradeStoredFeaturesWithVadIfPossible(
        mediaId: MediaId,
        featuresFile: File,
    ): StoredFeatures? {
        val decoder = audioStreamDecoder ?: return null
        val factory = speechProbabilityStreamFactory ?: return null
        val vadStream = runCatching { factory.createStream() }.getOrNull() ?: return null
        val mediaItem = mediaItemDao.getById(mediaId.value)
        val audioSource = resolveAudioSource(mediaId, mediaFileResolver, mediaItemDao, mediaItem) ?: return null
        val expectedDurationMs = mediaItem?.durationMs?.takeIf { it > 0L }

        val accumulator = FeatureAccumulator(binMs = ChunkSegmenter.DEFAULT_BIN_MS)
        val extractor = FrameFeatureExtractor(
            sampleRate = AnalysisWorker.TARGET_SAMPLE_RATE_HZ,
            sink = accumulator,
        )
        val sink = SampleSink { samples, count ->
            extractor.process(samples, count)
            vadStream.onSamples(samples, count)
        }
        val decodeResult = runCatching {
            when (audioSource) {
                is ResolvedAudioSource.LocalFile -> decoder.decode(
                    file = audioSource.file,
                    sink = sink,
                    outputSampleRate = AnalysisWorker.TARGET_SAMPLE_RATE_HZ,
                    expectedDurationMs = expectedDurationMs,
                )

                is ResolvedAudioSource.SourceUri -> decoder.decode(
                    uri = audioSource.uri,
                    sink = sink,
                    outputSampleRate = AnalysisWorker.TARGET_SAMPLE_RATE_HZ,
                    expectedDurationMs = expectedDurationMs,
                )
            }
        }.getOrNull()
        if (decodeResult !is AudioDecodeResult.Success) return null
        extractor.finish()
        val energy = accumulator.logEnergyDb()
        val probs = vadStream.finish(
            targetFrameCount = energy.size,
            binMs = ChunkSegmenter.DEFAULT_BIN_MS,
        )
        val upgraded = StoredFeatures(
            binMs = ChunkSegmenter.DEFAULT_BIN_MS,
            logEnergyDb = energy,
            speechProbability = probs,
        )
        AnalysisBinaryFormat.writeFeaturesAtomic(featuresFile, upgraded)
        return upgraded
    }

    override suspend fun setCustomSegmentationParams(id: MediaId, params: SegmentationParams?) {
        withContext(ioDispatcher) {
            val encoded = ItemStateEntity.encodeSegmentationParams(params)
            itemStateDao.updateCustomSegmentation(
                mediaId = id.value,
                customSegmentation = encoded,
                updatedAt = clock(),
            )
        }
    }

    override suspend fun startLanguageDetectionOnly(id: MediaId) {
        startLanguageDetectionInternal(id, onlyMissingLanguage = false)
    }

    private fun startLanguageDetectionInternal(id: MediaId, onlyMissingLanguage: Boolean) {
        activeLanguageDetectionJobs.remove(id)?.cancel()
        val stateFlow = lookaheadStateFor(id)
        stateFlow.update { it.copy(isLanguageDetectionRunning = true) }
        val job = backgroundScope.launch(ioDispatcher) {
            try {
                val mediaItem = mediaItemDao.getById(id.value) ?: return@launch
                val target = mediaItem.targetLang.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                    ?: return@launch
                val base = mediaItem.baseLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                val languagePair = LanguagePair(target = target, base = base)
                val audioFile = resolveReadableAudioFile(id, mediaFileResolver, mediaItemDao, mediaItem, context)
                if (audioFile == null || !audioFile.isFile) return@launch

                // 初回デコード中の並列言語判定パイプラインが実行中の場合は、その完了を待ってから未処理チャンクのみを対象にする
                activePipelinedLidJobs[id]?.join()

                val chunkSource = itemStateDao.getByMediaId(id.value)?.chunkSource ?: ChunkOrigin.AUTO.name
                val allChunks = chunkDao.observeByMediaId(id.value).first()
                val activeChunks = allChunks.filter { it.origin == chunkSource }.ifEmpty { allChunks }
                if (activeChunks.isEmpty()) return@launch

                val alreadyProcessedByPipelinedLid = pipelinedLidProcessedChunkIds[id].orEmpty()
                val skippedEmptyIds = attemptedEmptyChunkIds[id].orEmpty()
                val transcriptsByChunkId = transcriptDao.observeByMediaId(id.value).first().associateBy { it.chunkId }
                val targetChunks = activeChunks.filter { chunk ->
                    if (chunk.id in alreadyProcessedByPipelinedLid || chunk.id in skippedEmptyIds) {
                        return@filter false
                    }
                    val existing = transcriptsByChunkId[chunk.id]
                    if (onlyMissingLanguage) {
                        existing == null || (
                            existing.text.isBlank() &&
                                (existing.language.isNullOrBlank() || existing.language.equals("und", ignoreCase = true)) &&
                                !(existing.userEdited && existing.language == null)
                            )
                    } else {
                        existing == null ||
                            (existing.isLanguageIdOnly() && !existing.userEdited) ||
                            (
                                existing.text.isBlank() &&
                                    (existing.language.isNullOrBlank() || existing.language.equals("und", ignoreCase = true)) &&
                                    !(existing.userEdited && existing.language == null)
                                )
                    }
                }
                if (targetChunks.isEmpty()) {
                    return@launch
                }

                // 第1段階（高速一括言語判定）を即座に走らせるため、低優先度の先読み音声認識があれば中断する
                runCatching {
                    onDeviceChunkTranscriber.cancelActiveTranscription(onlyLowPriority = true)
                }

                val totalTargets = targetChunks.size
                var completed = 0
                val parallelism = OnDeviceChunkTranscriber.recommendedLanguageDetectionParallelism()
                val languageDetectionStartedAt = clock()
                runCatching {
                    onDeviceChunkTranscriber.recordExternalDiagnostic(
                        stage = "LID_ONLY",
                        message = "言語判定のみを開始 ($totalTargets チャンク、最大 $parallelism 並列)",
                    )
                }

                val lidFlushMutex = Mutex()
                val pendingLidUpserts = LinkedHashMap<Long, OnDeviceChunkTranscriptionResult>()
                val pendingLidDeletes = LinkedHashSet<Long>()
                var lastLidFlushMs = languageDetectionStartedAt

                suspend fun flushPendingBatchLocked() {
                    if (pendingLidUpserts.isEmpty() && pendingLidDeletes.isEmpty()) return
                    val upsertsSnapshot = LinkedHashMap(pendingLidUpserts)
                    val deletesSnapshot = LinkedHashSet(pendingLidDeletes)
                    pendingLidUpserts.clear()
                    pendingLidDeletes.clear()
                    withContext(NonCancellable) {
                        flushPendingLidTranscriptWrites(
                            pendingUpserts = upsertsSnapshot,
                            pendingDeletes = deletesSnapshot,
                        )
                    }
                }

                try {
                    onDeviceChunkTranscriber.detectChunkLanguages(
                        audioFile = audioFile,
                        chunks = targetChunks.map { ChunkTimeRange(startMs = it.startMs, endMs = it.endMs) },
                        languagePair = languagePair,
                        parallelism = parallelism,
                        computeDispatcher = computeDispatcher,
                    ) { index, detected ->
                        val chunk = targetChunks[index]
                        lidFlushMutex.withLock {
                            if (detected?.language != null) {
                                pendingLidUpserts[chunk.id] = detected
                                pendingLidDeletes.remove(chunk.id)
                            } else {
                                pendingLidUpserts.remove(chunk.id)
                                pendingLidDeletes.add(chunk.id)
                            }
                            completed += 1
                            val nowMs = clock()
                            val isClockAdvancing = nowMs > languageDetectionStartedAt
                            val pendingCount = pendingLidUpserts.size + pendingLidDeletes.size
                            val shouldFlush = !isClockAdvancing ||
                                nowMs - lastLidFlushMs >= LID_DB_BATCH_FLUSH_INTERVAL_MS ||
                                pendingCount >= LID_DB_BATCH_MAX_SIZE
                            if (shouldFlush) {
                                flushPendingBatchLocked()
                                lastLidFlushMs = nowMs
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    runCatching {
                        onDeviceChunkTranscriber.recordExternalDiagnostic(
                            stage = "LID_ONLY",
                            message = "言語判定のみを中断 (${e.javaClass.simpleName})",
                            isWarningOrError = true,
                        )
                    }
                } finally {
                    withContext(NonCancellable) {
                        lidFlushMutex.withLock {
                            flushPendingBatchLocked()
                        }
                        updateQueuedChunkIdsState(id)
                        persistTranscriptBackupFile(id)
                        val elapsedMs = (clock() - languageDetectionStartedAt).coerceAtLeast(0L)
                        val elapsedTenths = (elapsedMs + 50L) / 100L
                        runCatching {
                            onDeviceChunkTranscriber.recordExternalDiagnostic(
                                stage = "LID_ONLY",
                                message = "言語判定のみを終了 ($completed / $totalTargets チャンク、" +
                                    "${elapsedTenths / 10}.${elapsedTenths % 10} 秒、" +
                                    "平均 ${elapsedMs / completed.coerceAtLeast(1)} ms/チャンク)",
                            )
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    activeLanguageDetectionJobs.remove(id)
                    stateFlow.update { it.copy(isLanguageDetectionRunning = false) }
                }
            }

            // 第1段階（一括言語判定）の完了後、先読み文字起こしセッションが進行中であれば第2段階（文字起こし）へ自動移行する
            ensureDefaultLookaheadEnabledConfigured(id)
            val resumeStartIndex = resumeLookaheadStartIndexOrNull(id)
            if (resumeStartIndex != null && stateFlow.value.enabled && canContinueTranscriptionForMedia(id)) {
                triggerLookaheadAt(id, resumeStartIndex)
            }
        }
        activeLanguageDetectionJobs[id] = job
        job.invokeOnCompletion {
            activeLanguageDetectionJobs.remove(id, job)
        }
    }

    override suspend fun setTranscribeTargetLanguageOnly(id: MediaId, targetLanguageOnly: Boolean) {
        withContext(ioDispatcher) {
            if (targetLanguageOnly) {
                targetLanguageOnlyMediaIds.add(id)
            } else {
                targetLanguageOnlyMediaIds.remove(id)
            }
            updateQueuedChunkIdsState(id)
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
                // 削除済みの教材には行を作らない（外部キー違反で落ちないように）
                itemStateDao.upsertIfMediaExists(entity)
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
            withTrackedTranscriptionJob(id) {
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
                val audioFile = resolveReadableAudioFile(id, mediaFileResolver, mediaItemDao, mediaItem, context)
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
                        runCatching {
                            onDeviceChunkTranscriber.cancelActiveTranscription(onlyLowPriority = true)
                        }
                        recognitionSlotGate.withHighPrioritySlot {
                            highPrioritySet.remove(chunkId.value)
                            val startedAt = clock()
                            stateFlow.update { current ->
                                current.copy(
                                    engineAvailable = true,
                                    inFlightChunkId = chunkId,
                                    inFlightStep = TranscriptionStep.EXTRACTING_PCM,
                                    inFlightDetail = "言語変更による再文字起こし",
                                    inFlightStartedAtEpochMs = startedAt,
                                    isForcedChunkInFlight = true,
                                    queuedChunkIds = current.queuedChunkIds - chunkId,
                                    skippedEmptyChunkIds = currentSkippedChunkIds(id),
                                )
                            }
                            val itemSttOverride = itemSttOverrideRepository?.get(id) ?: com.miuraken.chunkflow.core.model.ItemSttOverride.NONE
                            val engineByLanguage = userSettingsRepository?.settings?.first()?.sttEngineByLanguage ?: emptyMap()
                            val recognized = try {
                                onDeviceChunkTranscriber.transcribeChunk(
                                    audioFile = audioFile,
                                    startMs = targetChunk.startMs,
                                    endMs = targetChunk.endMs,
                                    languagePair = effectiveLanguagePair,
                                    forceRefresh = true,
                                    highPriority = true,
                                    itemSttOverride = itemSttOverride,
                                    engineByLanguage = engineByLanguage,
                                    precomputedLanguage = null,
                                    precomputedLanguageConfidence = null,
                                    compareBothLanguages = normalizedNewLang == null,
                                )
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                                null
                            } finally {
                                withContext(NonCancellable) {
                                    stateFlow.update { current ->
                                        val stillThis = current.inFlightChunkId == chunkId
                                        current.copy(
                                            inFlightChunkId = if (stillThis) null else current.inFlightChunkId,
                                            inFlightStep = if (stillThis) null else current.inFlightStep,
                                            inFlightDetail = if (stillThis) null else current.inFlightDetail,
                                            inFlightStartedAtEpochMs = if (stillThis) null else current.inFlightStartedAtEpochMs,
                                            isForcedChunkInFlight = if (stillThis) false else current.isForcedChunkInFlight,
                                        )
                                    }
                                    updateQueuedChunkIdsState(id)
                                }
                            }

                            withContext(NonCancellable) {
                                val skippedSet = attemptedEmptyChunkIds.computeIfAbsent(id) {
                                    ConcurrentHashMap.newKeySet()
                                }
                                if (recognized != null && recognized.text.isNotBlank()) {
                                    skippedSet.remove(chunkId.value)
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
                                } else {
                                    skippedSet.add(chunkId.value)
                                }
                                updateQueuedChunkIdsState(id)
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
        onDeviceChunkTranscriber.activeStepState,
        onDeviceChunkTranscriber.recentDiagnostics,
    ) { status, modelIssues, stepState, diagnostics ->
        val effectiveStep = when {
            status.inFlightChunkId == null -> null
            stepState != null -> stepState.step
            else -> status.inFlightStep ?: TranscriptionStep.EXTRACTING_PCM
        }
        val effectiveDetail = if (status.inFlightChunkId != null && !stepState?.detail.isNullOrBlank()) {
            stepState.detail
        } else {
            status.inFlightDetail
        }
        val effectiveStartedAt = when {
            status.inFlightChunkId == null -> null
            (status.inFlightStartedAtEpochMs ?: 0L) > 0L -> status.inFlightStartedAtEpochMs
            (stepState?.startedAtEpochMs ?: 0L) > 0L -> stepState?.startedAtEpochMs
            else -> null
        }
        status.copy(
            recognitionModelIssues = modelIssues,
            inFlightStep = effectiveStep,
            inFlightDetail = effectiveDetail,
            inFlightStartedAtEpochMs = effectiveStartedAt,
            diagnostics = diagnostics,
        )
    }.distinctUntilChanged()

    override suspend fun requestRecognitionModelDownload(language: LanguageTag): Boolean = runCatching {
        val ok = onDeviceChunkTranscriber.requestRecognitionModelDownload(language)
        if (ok) {
            attemptedEmptyChunkIds.clear()
        }
        ok
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        false
    }

    override suspend fun downloadSherpaModel(language: LanguageTag): Boolean = withContext(ioDispatcher) {
        try {
            val ok = onDeviceChunkTranscriber.downloadSherpaModel(language)
            if (ok) {
                attemptedEmptyChunkIds.clear()
            }
            ok
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun requestSttModelDownload(language: LanguageTag, engine: com.miuraken.chunkflow.core.model.SttEngine): Boolean = withContext(ioDispatcher) {
        try {
            val ok = onDeviceChunkTranscriber.requestSttModelDownload(language, engine)
            if (ok) {
                attemptedEmptyChunkIds.clear()
            }
            ok
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun deleteSherpaModel(language: LanguageTag): Boolean = withContext(ioDispatcher) {
        try {
            onDeviceChunkTranscriber.deleteSherpaModel(language)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
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

    override suspend fun queryDownloadableModelStates(): List<com.miuraken.chunkflow.core.model.DownloadableSttModelState> = try {
        onDeviceChunkTranscriber.queryDownloadableModelStates()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        com.miuraken.chunkflow.core.model.DownloadableSttModel.entries.map {
            com.miuraken.chunkflow.core.model.DownloadableSttModelState(model = it)
        }
    }

    override suspend fun downloadSttModel(model: com.miuraken.chunkflow.core.model.DownloadableSttModel): Boolean = withContext(ioDispatcher) {
        try {
            onDeviceChunkTranscriber.downloadSttModel(model)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun purgeSttModel(model: com.miuraken.chunkflow.core.model.DownloadableSttModel): Boolean = withContext(ioDispatcher) {
        try {
            onDeviceChunkTranscriber.purgeSttModel(model)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun deleteSttModelForLanguage(
        language: LanguageTag,
        model: com.miuraken.chunkflow.core.model.DownloadableSttModel,
    ): Boolean = withContext(ioDispatcher) {
        try {
            onDeviceChunkTranscriber.deleteSttModelForLanguage(language, model)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun queryLanguageModelInfos(languages: List<LanguageTag>): List<OnDeviceLanguageModelInfo> = try {
        onDeviceChunkTranscriber.queryLanguageModelInfos(languages)
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Exception) {
        languages.map { OnDeviceLanguageModelInfo(language = it) }
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

    /**
     * 並列言語判定の結果をまとめて DB へ反映し、Room の変更通知（および波形・チャンクの再描画）頻度を抑制する。
     */
    private suspend fun flushPendingLidTranscriptWrites(
        pendingUpserts: Map<Long, OnDeviceChunkTranscriptionResult>,
        pendingDeletes: Set<Long>,
    ) {
        if (pendingUpserts.isEmpty() && pendingDeletes.isEmpty()) return
        val targetChunkIds = (pendingUpserts.keys + pendingDeletes).toList()
        val existingByChunkId = transcriptDao.getByChunkIds(targetChunkIds).associateBy { it.chunkId }
        val now = clock()
        val entitiesToUpsert = ArrayList<TranscriptEntity>(pendingUpserts.size)

        for ((chunkId, result) in pendingUpserts) {
            val language = result.language?.value?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("und", ignoreCase = true) }
                ?: continue
            val existing = existingByChunkId[chunkId]
            val isOverwritableLidOnly = existing != null &&
                existing.isLanguageIdOnly() &&
                !existing.userEdited
            val alreadyHasProtectedLanguageOrText = existing != null &&
                !isOverwritableLidOnly && (
                    existing.text.isNotBlank() ||
                        existing.userEdited ||
                        (!existing.language.isNullOrBlank() && !existing.language.equals("und", ignoreCase = true))
                    )
            if (alreadyHasProtectedLanguageOrText) continue
            if (existing != null && existing.language == language && existing.engine == result.engineId.value) continue
            entitiesToUpsert += TranscriptEntity(
                chunkId = chunkId,
                text = "",
                textVariants = existing?.textVariants ?: "{}",
                language = language,
                languageConfidence = result.confidence,
                words = existing?.words ?: "[]",
                engine = result.engineId.value,
                userEdited = false,
                createdAt = now,
            )
        }
        if (entitiesToUpsert.isNotEmpty()) {
            transcriptDao.upsertAll(entitiesToUpsert)
        }
        for (chunkId in pendingDeletes) {
            val existing = existingByChunkId[chunkId] ?: continue
            if (existing.isLanguageIdOnly() && !existing.userEdited) {
                transcriptDao.deleteByChunkId(chunkId)
            }
        }
    }

    override suspend fun setLookaheadEnabled(id: MediaId, enabled: Boolean) {
        explicitlyConfiguredLookaheadMediaIds.add(id)
        val bgAllowed = refreshGlobalBackgroundTranscriptionEnabled()
        if (enabled) {
            backgroundTranscriptionMediaIds.add(id)
            attemptedEmptyChunkIds.remove(id)
            backgroundDeferredEmptyChunkIds.remove(id)
        } else {
            backgroundTranscriptionMediaIds.remove(id)
            backgroundDeferredEmptyChunkIds.remove(id)
            backgroundJobs.remove(id)?.cancel()
            pendingHighPriorityChunkIds[id]?.clear()
            cancelActiveTranscriptionJobs(
                id = id,
                cause = CancellationException("Lookahead transcription disabled by user"),
            )
            recognitionSlotGate.cancelAllWaiters()
            runCatching {
                onDeviceChunkTranscriber.cancelActiveTranscription(onlyLowPriority = false)
            }
            runCatching {
                onDeviceChunkTranscriber.recordExternalDiagnostic(
                    stage = "CONTROL",
                    message = "文字起こしオフにより処理を中断",
                )
            }
        }
        val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
        lookaheadStateFor(id).update { current ->
            current.copy(
                enabled = enabled,
                backgroundEnabled = enabled && bgAllowed,
                engineAvailable = available,
                inFlightChunkId = if (enabled) current.inFlightChunkId else null,
                inFlightStep = if (enabled) current.inFlightStep else null,
                inFlightDetail = if (enabled) current.inFlightDetail else null,
                inFlightStartedAtEpochMs = if (enabled) current.inFlightStartedAtEpochMs else null,
                isForcedChunkInFlight = if (enabled) current.isForcedChunkInFlight else false,
                queuedChunkIds = if (enabled && canContinueTranscriptionForMedia(id)) current.queuedChunkIds else emptySet(),
                skippedEmptyChunkIds = currentSkippedChunkIds(id),
            )
        }
        if (enabled && isBackgroundTranscriptionAllowedFor(id) && !isMediaOpenForTranscription(id)) {
            startBackgroundTranscriptionLoop(id)
        }
    }

    override suspend fun setBackgroundTranscriptionEnabled(id: MediaId, enabled: Boolean) {
        if (enabled) {
            backgroundTranscriptionMediaIds.add(id)
        } else {
            backgroundTranscriptionMediaIds.remove(id)
            backgroundDeferredEmptyChunkIds.remove(id)
        }
        val effectiveBg = enabled && refreshGlobalBackgroundTranscriptionEnabled()
        lookaheadStateFor(id).update { it.copy(backgroundEnabled = effectiveBg) }
        if (!effectiveBg && !isMediaOpenForTranscription(id)) {
            backgroundJobs.remove(id)?.cancel()
            cancelActiveTranscriptionJobs(id, CancellationException("Background transcription disabled"))
            lookaheadStateFor(id).update {
                it.copy(
                    inFlightChunkId = null,
                    inFlightStep = null,
                    inFlightDetail = null,
                    inFlightStartedAtEpochMs = null,
                    isForcedChunkInFlight = false,
                    queuedChunkIds = emptySet(),
                )
            }
            if (activeOpenMediaIds.isEmpty() && backgroundTranscriptionMediaIds.none { isBackgroundTranscriptionAllowedFor(it) }) {
                runCatching { onDeviceChunkTranscriber.releaseIdleResources() }
            }
        } else if (effectiveBg && lookaheadStateFor(id).value.enabled && !isMediaOpenForTranscription(id)) {
            startBackgroundTranscriptionLoop(id)
        }
    }

    private fun startBackgroundTranscriptionLoop(id: MediaId) {
        if (!isBackgroundTranscriptionAllowedFor(id) || !lookaheadStateFor(id).value.enabled) return
        val existingJob = backgroundJobs[id]
        if (existingJob != null && existingJob.isActive) return
        val job = backgroundScope.launch {
            delay(BACKGROUND_YIELD_POLL_MS)
            if (!canContinueTranscriptionForMedia(id) || !lookaheadStateFor(id).value.enabled) {
                return@launch
            }
            val startIndex = (latestLookaheadChunkIndex[id] ?: 0).coerceAtLeast(0)
            triggerLookaheadAt(id, startIndex)
        }
        backgroundJobs[id] = job
        job.invokeOnCompletion {
            backgroundJobs.remove(id, job)
        }
    }

    override suspend fun resetTranscription(id: MediaId) {
        withContext(ioDispatcher) {
            backgroundTranscriptionMediaIds.remove(id)
            backgroundDeferredEmptyChunkIds.remove(id)
            backgroundJobs.remove(id)?.cancel()
            pendingHighPriorityChunkIds[id]?.clear()
            attemptedEmptyChunkIds.remove(id)
            pipelinedLidProcessedChunkIds.remove(id)
            activeLanguageDetectionJobs.remove(id)?.cancel()
            cancelActiveTranscriptionJobs(
                id = id,
                cause = CancellationException("Transcription forcibly reset by user"),
            )
            recognitionSlotGate.forceReset()
            runCatching {
                onDeviceChunkTranscriber.resetTranscriberState()
            }
            val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
            lookaheadStateFor(id).update { current ->
                current.copy(
                    enabled = false,
                    backgroundEnabled = false,
                    isLanguageDetectionRunning = false,
                    engineAvailable = available,
                    inFlightChunkId = null,
                    inFlightStep = null,
                    inFlightDetail = null,
                    inFlightStartedAtEpochMs = null,
                    isForcedChunkInFlight = false,
                    queuedChunkIds = emptySet(),
                    skippedEmptyChunkIds = emptySet(),
                )
            }
        }
    }

    override fun setMediaTranscriptionSessionOpen(id: MediaId, open: Boolean) {
        hasExplicitSessionTracking.set(true)
        if (open) {
            if (id !in explicitlyConfiguredLookaheadMediaIds) {
                backgroundScope.launch(ioDispatcher) {
                    ensureDefaultLookaheadEnabledConfigured(id)
                }
            }
            backgroundJobs.remove(id)?.cancel()
            backgroundDeferredEmptyChunkIds.remove(id)
            val previouslyOpen = activeOpenMediaIds.toList()
            val hadOtherBackgroundJobs = backgroundJobs.keys.any { it != id }
            activeOpenMediaIds.clear()
            activeOpenMediaIds.add(id)
            pipelineGate.setMediaOpen(id, true)
            notifyWaveformUpdated(id)
            if (hadOtherBackgroundJobs || previouslyOpen.any { it != id }) {
                runCatching {
                    onDeviceChunkTranscriber.cancelActiveTranscription(onlyLowPriority = true)
                }
            }
            for (otherId in previouslyOpen) {
                if (otherId != id) {
                    pipelineGate.setMediaOpen(otherId, false)
                    pendingHighPriorityChunkIds[otherId]?.clear()
                    if (isBackgroundTranscriptionAllowedFor(otherId) && lookaheadStateFor(otherId).value.enabled) {
                        startBackgroundTranscriptionLoop(otherId)
                    } else {
                        backgroundJobs.remove(otherId)?.cancel()
                        lookaheadStateFor(otherId).update { current ->
                            current.copy(queuedChunkIds = emptySet())
                        }
                    }
                }
            }
        } else {
            activeOpenMediaIds.remove(id)
            pipelineGate.setMediaOpen(id, false)
            pendingHighPriorityChunkIds[id]?.clear()
            if (isBackgroundTranscriptionAllowedFor(id) && lookaheadStateFor(id).value.enabled) {
                startBackgroundTranscriptionLoop(id)
            } else {
                backgroundJobs.remove(id)?.cancel()
                lookaheadStateFor(id).update { current ->
                    current.copy(queuedChunkIds = emptySet())
                }
            }
            if (activeOpenMediaIds.isEmpty() && backgroundTranscriptionMediaIds.none { isBackgroundTranscriptionAllowedFor(it) }) {
                // プレイヤー画面を閉じたら、チャンク切り出し用に開いたままの音声ファイルを閉じる（削除された教材の容量も解放される）
                runCatching { onDeviceChunkTranscriber.releaseIdleResources() }
            }
        }
    }

    override suspend fun prioritizeOpenedMediaAnalysis(id: MediaId) {
        withContext(ioDispatcher) {
            notifyWaveformUpdated(id)
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

            val audioSource = resolveAudioSource(id, mediaFileResolver, mediaItemDao, mediaItem)
            if (audioSource != null) {
                val existingByStage = analysisStateDao.getByMediaId(id.value)
                    .mapNotNull { it.toStageAndStatus() }
                    .toMap()
                val waveformFile = resolveAnalysisFile(id, WAVEFORM_FILE_NAME)
                val featuresFile = resolveAnalysisFile(id, FEATURES_FILE_NAME)
                val storedFeatures = featuresFile?.takeIf { it.isFile }?.let { AnalysisBinaryFormat.readFeatures(it) }
                val needsVadUpgrade = speechProbabilityStreamFactory != null &&
                    audioStreamDecoder != null &&
                    storedFeatures?.speechProbability == null
                val needFeatures = existingByStage[AnalysisStage.FEATURES] !is StageStatus.Done ||
                    waveformFile?.isFile != true ||
                    storedFeatures == null ||
                    needsVadUpgrade
                val needSegmentation = existingByStage[AnalysisStage.SEGMENTATION] !is StageStatus.Done ||
                    needsVadUpgrade
                if (needFeatures || needSegmentation) {
                    requestAnalysisInternal(
                        id = id,
                        stages = setOf(AnalysisStage.FEATURES, AnalysisStage.SEGMENTATION),
                        explicitHighPriority = true,
                    )
                } else if (
                    mediaItem.targetLang.isNotBlank() &&
                    !mediaItem.baseLang.isNullOrBlank() &&
                    !mediaItem.targetLang.equals(mediaItem.baseLang, ignoreCase = true) &&
                    onDeviceChunkTranscriber !== NoOpOnDeviceChunkTranscriber
                ) {
                    val allChunks = chunkDao.getByMediaIdAndOrigin(id.value, ChunkOrigin.AUTO.name)
                    if (allChunks.isNotEmpty()) {
                        val transcriptsByChunkId = transcriptDao.getByChunkIds(allChunks.map { it.id }).associateBy { it.chunkId }
                        val hasMissingLid = allChunks.any { chunk ->
                            val existing = transcriptsByChunkId[chunk.id]
                            existing == null ||
                                (
                                    existing.text.isBlank() &&
                                        (existing.language.isNullOrBlank() || existing.language.equals("und", ignoreCase = true)) &&
                                        !(existing.userEdited && existing.language == null)
                                    )
                        }
                        if (hasMissingLid) {
                            startLanguageDetectionInternal(id, onlyMissingLanguage = true)
                        }
                    }
                }
            }
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
                    resolveAudioSource(itemId, mediaFileResolver, mediaItemDao, item) != null
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

    private fun resumeLookaheadStartIndexOrNull(id: MediaId): Int? = latestLookaheadChunkIndex[id]?.coerceAtLeast(0)
        ?: if (id in activeOpenMediaIds || id in backgroundTranscriptionMediaIds) 0 else null

    override suspend fun triggerLookaheadAt(id: MediaId, currentChunkId: ChunkId?) {
        if (!canContinueTranscriptionForMedia(id)) {
            lookaheadStateFor(id).update { it.copy(queuedChunkIds = emptySet()) }
            return
        }
        withContext(ioDispatcher) {
            val allChunks = chunkDao.observeByMediaId(id.value).first()
            val itemState = itemStateDao.getByMediaId(id.value)
            val activeChunks = selectActiveChunkEntities(itemState, allChunks)
            if (activeChunks.isEmpty()) {
                latestLookaheadChunkIndex.putIfAbsent(id, 0)
                return@withContext
            }
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
            withTrackedTranscriptionJob(id) {
                val stateFlow = lookaheadStateFor(id)
                val allChunks = chunkDao.observeByMediaId(id.value).first()
                val itemState = itemStateDao.getByMediaId(id.value)
                val activeChunks = selectActiveChunkEntities(itemState, allChunks)
                val targetIndex = activeChunks.indexOfFirst { it.id == chunkId.value }
                if (!stateFlow.value.enabled) {
                    if (targetIndex >= 0) {
                        latestLookaheadChunkIndex[id] = targetIndex
                    }
                    updateQueuedChunkIdsState(id, activeChunks)
                    return@withTrackedTranscriptionJob
                }

                attemptedEmptyChunkIds[id]?.remove(chunkId.value)
                if (targetIndex >= 0) {
                    latestLookaheadChunkIndex[id] = targetIndex
                    updateQueuedChunkIdsState(id, activeChunks)
                }

                val targetChunk = activeChunks.getOrNull(targetIndex)
                    ?: allChunks.firstOrNull { it.id == chunkId.value }
                    ?: return@withTrackedTranscriptionJob
                if (targetChunk.endMs <= targetChunk.startMs) return@withTrackedTranscriptionJob

                val existing = transcriptDao.getByChunkId(chunkId.value)
                val mediaItem = mediaItemDao.getById(id.value)
                val itemSttOverride = itemSttOverrideRepository?.get(id) ?: com.miuraken.chunkflow.core.model.ItemSttOverride.NONE
                val engineByLanguage = userSettingsRepository?.settings?.first()?.sttEngineByLanguage ?: emptyMap()

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
                if (needsLookaheadTranscription(baseExisting) && isMediaOpenForTranscription(id) && stateFlow.value.enabled) {
                    val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
                    val audioFile = resolveReadableAudioFile(id, mediaFileResolver, mediaItemDao, mediaItem, context)
                    if (available && audioFile != null && audioFile.isFile) {
                        val highPrioritySet = pendingHighPriorityChunkIds.computeIfAbsent(id) {
                            ConcurrentHashMap.newKeySet()
                        }
                        highPrioritySet.add(chunkId.value)
                        updateQueuedChunkIdsState(id, activeChunks)

                        try {
                            recognitionSlotGate.withHighPrioritySlot {
                                highPrioritySet.remove(chunkId.value)
                                if (!isMediaOpenForTranscription(id) || !stateFlow.value.enabled) {
                                    updateQueuedChunkIdsState(id)
                                    return@withHighPrioritySlot
                                }
                                val latestExisting = transcriptDao.getByChunkId(chunkId.value) ?: overlappingImported
                                if (!needsLookaheadTranscription(latestExisting)) {
                                    updateQueuedChunkIdsState(id)
                                    return@withHighPrioritySlot
                                }

                                val startedAt = clock()
                                stateFlow.update { current ->
                                    current.copy(
                                        engineAvailable = true,
                                        inFlightChunkId = chunkId,
                                        inFlightStep = TranscriptionStep.EXTRACTING_PCM,
                                        inFlightDetail = "選択チャンクを優先文字起こし中",
                                        inFlightStartedAtEpochMs = startedAt,
                                        isForcedChunkInFlight = false,
                                        queuedChunkIds = current.queuedChunkIds - chunkId,
                                        skippedEmptyChunkIds = currentSkippedChunkIds(id),
                                    )
                                }

                                val assignedLang = latestExisting
                                    ?.takeUnless { it.isLanguageIdOnly() }
                                    ?.language?.trim()?.takeIf {
                                        it.isNotEmpty() && !it.equals("und", ignoreCase = true)
                                    }
                                val precomputedLidLang = latestExisting
                                    ?.takeIf { it.isLanguageIdOnly() }
                                    ?.language?.trim()?.takeIf {
                                        it.isNotEmpty() && !it.equals("und", ignoreCase = true)
                                    }
                                    ?.let(::LanguageTag)
                                val precomputedLidConfidence = latestExisting
                                    ?.takeIf { it.isLanguageIdOnly() }
                                    ?.languageConfidence
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
                                        itemSttOverride = itemSttOverride,
                                        engineByLanguage = engineByLanguage,
                                        highPriority = true,
                                        precomputedLanguage = precomputedLidLang,
                                        precomputedLanguageConfidence = precomputedLidConfidence,
                                    )
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (_: Exception) {
                                    null
                                } finally {
                                    withContext(NonCancellable) {
                                        stateFlow.update { current ->
                                            val stillThis = current.inFlightChunkId == chunkId
                                            current.copy(
                                                inFlightChunkId = if (stillThis) null else current.inFlightChunkId,
                                                inFlightStep = if (stillThis) null else current.inFlightStep,
                                                inFlightDetail = if (stillThis) null else current.inFlightDetail,
                                                inFlightStartedAtEpochMs = if (stillThis) null else current.inFlightStartedAtEpochMs,
                                                isForcedChunkInFlight = if (stillThis) false else current.isForcedChunkInFlight,
                                            )
                                        }
                                        updateQueuedChunkIdsState(id)
                                    }
                                }

                                withContext(NonCancellable) {
                                    val skippedSet = attemptedEmptyChunkIds.computeIfAbsent(id) {
                                        ConcurrentHashMap.newKeySet()
                                    }
                                    if (result != null && result.text.isNotBlank()) {
                                        skippedSet.remove(chunkId.value)
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
                                        skippedSet.add(chunkId.value)
                                    } else {
                                        skippedSet.add(chunkId.value)
                                    }
                                    updateQueuedChunkIdsState(id)
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

                if (targetIndex >= 0 && isMediaOpenForTranscription(id) && stateFlow.value.enabled) {
                    triggerLookaheadAt(id, targetIndex)
                }
            }
        }
    }

    override suspend fun retranscribeChunk(id: MediaId, chunkId: ChunkId) {
        withContext(ioDispatcher) {
            withTrackedTranscriptionJob(id) {
                val itemSttOverride = itemSttOverrideRepository?.get(id) ?: com.miuraken.chunkflow.core.model.ItemSttOverride.NONE
                val engineByLanguage = userSettingsRepository?.settings?.first()?.sttEngineByLanguage ?: emptyMap()

                attemptedEmptyChunkIds[id]?.remove(chunkId.value)

                val allChunks = chunkDao.observeByMediaId(id.value).first()
                val targetChunk = allChunks.firstOrNull { it.id == chunkId.value } ?: return@withTrackedTranscriptionJob
                if (targetChunk.endMs <= targetChunk.startMs) return@withTrackedTranscriptionJob

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
                val isBaseLidOnly = baseExisting != null && baseExisting.isLanguageIdOnly() && !baseExisting.userEdited
                val assignedLang = baseExisting
                    ?.takeUnless { isBaseLidOnly }
                    ?.language?.trim()?.takeIf {
                        it.isNotEmpty() && !it.equals("und", ignoreCase = true)
                    }
                val initialDisplayLang = baseExisting?.language?.trim()?.takeIf {
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
                        language = initialDisplayLang,
                        languageConfidence = baseExisting?.languageConfidence,
                        words = "[]",
                        engine = baseExisting?.engine ?: MANUAL_LANGUAGE_ENGINE_ID,
                        userEdited = wasUserEdited,
                        createdAt = now,
                    ),
                )

                val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
                val audioFile = resolveReadableAudioFile(id, mediaFileResolver, mediaItemDao, mediaItem, context)
                if (!available || audioFile == null || !audioFile.isFile) {
                    withContext(NonCancellable) {
                        persistTranscriptBackupFile(id)
                    }
                    return@withTrackedTranscriptionJob
                }

                val stateFlow = lookaheadStateFor(id)
                val highPrioritySet = pendingHighPriorityChunkIds.computeIfAbsent(id) {
                    ConcurrentHashMap.newKeySet()
                }
                highPrioritySet.add(chunkId.value)
                updateQueuedChunkIdsState(id)

                // バックグラウンド先読みが実行中であれば即座に中断して強制文字起こしへ切り替える
                runCatching {
                    onDeviceChunkTranscriber.cancelActiveTranscription(onlyLowPriority = true)
                }
                runCatching {
                    onDeviceChunkTranscriber.recordExternalDiagnostic(
                        stage = "CONTROL",
                        message = "強制文字起こし要求 (${targetChunk.startMs}..${targetChunk.endMs}ms)",
                    )
                }

                try {
                    recognitionSlotGate.withHighPrioritySlot {
                        highPrioritySet.remove(chunkId.value)
                        evictChunkTranscriptCache(
                            mediaId = id.value,
                            startMs = targetChunk.startMs,
                            endMs = targetChunk.endMs,
                        )

                        val currentBeforeRecognize = transcriptDao.getByChunkId(chunkId.value) ?: baseExisting
                        val isCurrentLidOnly = currentBeforeRecognize != null &&
                            currentBeforeRecognize.isLanguageIdOnly() &&
                            !currentBeforeRecognize.userEdited
                        val effectiveAssignedLang = currentBeforeRecognize
                            ?.takeUnless { isCurrentLidOnly }
                            ?.language?.trim()?.takeIf {
                                it.isNotEmpty() && !it.equals("und", ignoreCase = true)
                            } ?: assignedLang
                        val precomputedLidLang = currentBeforeRecognize
                            ?.takeIf { isCurrentLidOnly }
                            ?.language?.trim()?.takeIf {
                                it.isNotEmpty() && !it.equals("und", ignoreCase = true)
                            }
                            ?.let(::LanguageTag)
                        val precomputedLidConfidence = currentBeforeRecognize
                            ?.takeIf { isCurrentLidOnly }
                            ?.languageConfidence
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

                        val startedAt = clock()
                        stateFlow.update { current ->
                            current.copy(
                                engineAvailable = true,
                                inFlightChunkId = chunkId,
                                inFlightStep = TranscriptionStep.EXTRACTING_PCM,
                                inFlightDetail = "強制文字起こしを実行中",
                                inFlightStartedAtEpochMs = startedAt,
                                isForcedChunkInFlight = true,
                                queuedChunkIds = current.queuedChunkIds - chunkId,
                                skippedEmptyChunkIds = currentSkippedChunkIds(id),
                            )
                        }
                        val itemSttOverride = itemSttOverrideRepository?.get(id) ?: com.miuraken.chunkflow.core.model.ItemSttOverride.NONE
                        val engineByLanguage = userSettingsRepository?.settings?.first()?.sttEngineByLanguage ?: emptyMap()
                        val recognized = try {
                            onDeviceChunkTranscriber.transcribeChunk(
                                audioFile = audioFile,
                                startMs = targetChunk.startMs,
                                endMs = targetChunk.endMs,
                                languagePair = effectiveLanguagePair,
                                forceRefresh = true,
                                itemSttOverride = itemSttOverride,
                                engineByLanguage = engineByLanguage,
                                highPriority = true,
                                precomputedLanguage = precomputedLidLang,
                                precomputedLanguageConfidence = precomputedLidConfidence,
                            )
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            null
                        } finally {
                            withContext(NonCancellable) {
                                stateFlow.update { current ->
                                    val stillThis = current.inFlightChunkId == chunkId
                                    current.copy(
                                        inFlightChunkId = if (stillThis) null else current.inFlightChunkId,
                                        inFlightStep = if (stillThis) null else current.inFlightStep,
                                        inFlightDetail = if (stillThis) null else current.inFlightDetail,
                                        inFlightStartedAtEpochMs = if (stillThis) null else current.inFlightStartedAtEpochMs,
                                        isForcedChunkInFlight = if (stillThis) false else current.isForcedChunkInFlight,
                                    )
                                }
                                updateQueuedChunkIdsState(id)
                            }
                        }

                        withContext(NonCancellable) {
                            val skippedSet = attemptedEmptyChunkIds.computeIfAbsent(id) {
                                ConcurrentHashMap.newKeySet()
                            }
                            if (recognized != null && recognized.text.isNotBlank()) {
                                skippedSet.remove(chunkId.value)
                                val currentEntity = transcriptDao.getByChunkId(chunkId.value) ?: baseExisting
                                val resolvedLanguage = effectiveAssignedLang
                                    ?: recognized.language?.value?.takeIf {
                                        it.isNotBlank() && !it.equals("und", ignoreCase = true)
                                    }
                                    ?: currentEntity?.language?.takeIf {
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
                            } else if (recognized != null && recognized.isLanguageOnly) {
                                persistLanguageOnlyTranscript(chunkId.value, recognized)
                                skippedSet.add(chunkId.value)
                            } else {
                                skippedSet.add(chunkId.value)
                            }
                            updateQueuedChunkIdsState(id)
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
    }

    override suspend fun clearTranscriptsForMedia(id: MediaId) {
        withContext(ioDispatcher) {
            activeLanguageDetectionJobs.remove(id)?.cancel()
            activePipelinedLidJobs.remove(id)?.cancel()
            backgroundJobs.remove(id)?.cancel()
            cancelActiveTranscriptionJobs(id, CancellationException("Transcripts cleared"))
            attemptedEmptyChunkIds.remove(id)
            backgroundDeferredEmptyChunkIds.remove(id)
            pipelinedLidProcessedChunkIds.remove(id)
            pendingHighPriorityChunkIds.remove(id)
            latestLookaheadChunkIndex.remove(id)
            chunkTranscriptByLangCache.keys.removeIf { it.mediaId == id.value }

            transcriptDao.deleteByMediaId(id.value)
            chunkDao.deleteByMediaIdAndOrigin(id.value, ChunkOrigin.IMPORTED.name)
            chunkDao.deleteByMediaIdAndOrigin(id.value, ChunkOrigin.MANUAL.name)

            val existingState = itemStateDao.getByMediaId(id.value)
            if (existingState != null && existingState.chunkSource != ChunkOrigin.AUTO.name) {
                itemStateDao.updateChunkSource(
                    mediaId = id.value,
                    chunkSource = ChunkOrigin.AUTO.name,
                    updatedAt = clock(),
                )
            }

            lookaheadStateFor(id).update {
                it.copy(
                    inFlightChunkId = null,
                    inFlightStep = null,
                    inFlightDetail = null,
                    inFlightStartedAtEpochMs = null,
                    isForcedChunkInFlight = false,
                    queuedChunkIds = emptySet(),
                    skippedEmptyChunkIds = emptySet(),
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
        val deferredBgEmptyIds = if (!isMediaOpenForTranscription(id)) {
            backgroundDeferredEmptyChunkIds[id].orEmpty()
        } else {
            emptySet()
        }
        val targetLangFilter = if (id in targetLanguageOnlyMediaIds) {
            mediaItemDao.getById(id.value)?.targetLang?.trim()?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val orderedCandidates = activeChunks.subList(startIndex, activeChunks.size) +
            activeChunks.subList(0, startIndex)
        val existingTranscripts = transcriptDao.getByChunkIds(orderedCandidates.map { it.id })
            .associateBy { it.chunkId }
        // プレイヤー画面で開いている間（またはバックグラウンド文字起こしが有効な間）は、現在位置から後ろ、続けて先頭からの順で、残りのすべてのチャンクを対象にする
        return orderedCandidates.filter { chunk ->
            val existing = existingTranscripts[chunk.id]
            val existingLang = existing?.language?.trim()?.takeIf {
                it.isNotEmpty() && !it.equals("und", ignoreCase = true)
            }
            val skippedByTargetFilter = targetLangFilter != null &&
                existingLang != null &&
                !existingLang.equals(targetLangFilter, ignoreCase = true)
            needsLookaheadTranscription(existing) &&
                !skippedByTargetFilter &&
                !skippedEmptyIds.contains(chunk.id) &&
                !deferredBgEmptyIds.contains(chunk.id)
        }
    }

    private suspend fun isOpenMediaAnalysisRunning(openId: MediaId): Boolean {
        if (
            lookaheadStateFor(openId).value.isLanguageDetectionRunning ||
            activeLanguageDetectionJobs[openId]?.isActive == true ||
            activePipelinedLidJobs[openId]?.isActive == true
        ) {
            return true
        }
        val stages = analysisStateDao.getByMediaId(openId.value)
            .mapNotNull { it.toStageAndStatus() }
            .toMap()
        return stages[AnalysisStage.FEATURES] is StageStatus.Running ||
            stages[AnalysisStage.SEGMENTATION] is StageStatus.Running
    }

    private suspend fun isOpenMediaTranscriptionBusy(openId: MediaId): Boolean {
        val status = lookaheadStateFor(openId).value
        if (!status.enabled) return false
        if (status.inFlightChunkId != null || status.queuedChunkIds.isNotEmpty()) return true
        if (!pendingHighPriorityChunkIds[openId].isNullOrEmpty()) return true
        val allChunks = chunkDao.observeByMediaId(openId.value).first()
        if (allChunks.isEmpty()) return false
        val itemState = itemStateDao.getByMediaId(openId.value)
        val activeChunks = selectActiveChunkEntities(itemState, allChunks)
        if (activeChunks.isEmpty()) return false
        val startIndex = (latestLookaheadChunkIndex[openId] ?: 0).coerceIn(0, activeChunks.lastIndex)
        return computeLookaheadTargetChunks(openId, activeChunks, startIndex).isNotEmpty()
    }

    private suspend fun shouldYieldBackgroundTranscription(id: MediaId): Boolean {
        if (isMediaOpenForTranscription(id)) return false
        if (pendingHighPriorityCount.get() > 0) return true
        if (pipelineGate.hasHighPriorityWaiters()) return true
        val openMediaSnapshot = activeOpenMediaIds.toList()
        for (openId in openMediaSnapshot) {
            if (openId == id) continue
            if (isOpenMediaAnalysisRunning(openId)) return true
            if (isOpenMediaTranscriptionBusy(openId)) return true
        }
        return false
    }

    private suspend fun updateQueuedChunkIdsState(
        id: MediaId,
        activeChunksOverride: List<ChunkEntity>? = null,
    ) {
        val stateFlow = lookaheadStateFor(id)
        val skippedIds = currentSkippedChunkIds(id)
        if (!canContinueTranscriptionForMedia(id)) {
            stateFlow.update { current ->
                current.copy(
                    queuedChunkIds = emptySet(),
                    skippedEmptyChunkIds = skippedIds,
                )
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
            current.copy(
                queuedChunkIds = combined,
                skippedEmptyChunkIds = skippedIds,
            )
        }
    }

    /**
     * アクティブチャンクのインデックス [currentChunkIndex] 以降から、未文字起こし・または言語未確定（かつ空結果として試行済みでない）チャンクを
     * プレイヤー画面で開いている間、またはバックグラウンド文字起こしが有効な間、残りのすべてのチャンクを順番に認識する（F6.2、§7.4.5）。
     *
     * 各チャンクの認識前に高優先度の手動リクエスト（[retranscribeChunk] / [updateChunkLanguage] / [prioritizeChunkTranscription]）が
     * 待機している場合は認識スロットを譲り、また先読み中に起点インデックスが更新された場合は次のチャンクから即座に新しい起点へ切り替える。
     */
    suspend fun triggerLookaheadAt(id: MediaId, currentChunkIndex: Int) {
        if (currentChunkIndex < 0) return
        if (!canContinueTranscriptionForMedia(id)) {
            lookaheadStateFor(id).update { it.copy(queuedChunkIds = emptySet()) }
            return
        }
        withContext(ioDispatcher) {
            withTrackedTranscriptionJob(id) {
                latestLookaheadChunkIndex[id] = currentChunkIndex
                updateQueuedChunkIdsState(id)
                if (
                    activePipelinedLidJobs[id]?.isActive == true ||
                    activeLanguageDetectionJobs[id]?.isActive == true ||
                    lookaheadStateFor(id).value.isLanguageDetectionRunning
                ) {
                    return@withTrackedTranscriptionJob
                }

                lookaheadMutexFor(id).withLock {
                    ensureDefaultLookaheadEnabledConfigured(id)
                    val stateFlow = lookaheadStateFor(id)
                    val currentStatus = stateFlow.value
                    val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
                    if (!currentStatus.enabled || !canContinueTranscriptionForMedia(id)) {
                        stateFlow.update {
                            it.copy(
                                queuedChunkIds = emptySet(),
                                skippedEmptyChunkIds = currentSkippedChunkIds(id),
                            )
                        }
                        return@withLock
                    }
                    if (!available) {
                        stateFlow.update {
                            it.copy(
                                engineAvailable = false,
                                inFlightChunkId = null,
                                inFlightStep = null,
                                inFlightDetail = null,
                                inFlightStartedAtEpochMs = null,
                                isForcedChunkInFlight = false,
                                queuedChunkIds = emptySet(),
                                skippedEmptyChunkIds = currentSkippedChunkIds(id),
                            )
                        }
                        return@withLock
                    }

                    val allChunks = chunkDao.observeByMediaId(id.value).first()
                    val itemState = itemStateDao.getByMediaId(id.value)
                    var activeChunks = selectActiveChunkEntities(itemState, allChunks)
                    val initialIndex = latestLookaheadChunkIndex[id] ?: currentChunkIndex
                    if (initialIndex !in activeChunks.indices) return@withLock

                    val audioFile = resolveReadableAudioFile(id, mediaFileResolver, mediaItemDao, context = context)
                    if (audioFile == null || !audioFile.isFile) return@withLock

                    val skippedEmptyIds = attemptedEmptyChunkIds.computeIfAbsent(id) {
                        ConcurrentHashMap.newKeySet()
                    }

                    val initialTargets = computeLookaheadTargetChunks(id, activeChunks, initialIndex)
                    if (initialTargets.isEmpty()) {
                        stateFlow.update { current ->
                            val highIds = pendingHighPriorityChunkIds[id]?.map { ChunkId(it) }?.toSet().orEmpty()
                            val keepInFlight = pendingHighPriorityCount.get() > 0
                            current.copy(
                                engineAvailable = true,
                                inFlightChunkId = if (keepInFlight) current.inFlightChunkId else null,
                                inFlightStep = if (keepInFlight) current.inFlightStep else null,
                                inFlightDetail = if (keepInFlight) current.inFlightDetail else null,
                                inFlightStartedAtEpochMs = if (keepInFlight) current.inFlightStartedAtEpochMs else null,
                                isForcedChunkInFlight = if (keepInFlight) current.isForcedChunkInFlight else false,
                                queuedChunkIds = if (canContinueTranscriptionForMedia(id)) {
                                    highIds - setOfNotNull(current.inFlightChunkId)
                                } else {
                                    emptySet()
                                },
                                skippedEmptyChunkIds = currentSkippedChunkIds(id),
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
                    if (pendingHighPriorityCount.get() == 0 &&
                        canContinueTranscriptionForMedia(id) &&
                        !shouldYieldBackgroundTranscription(id)
                    ) {
                        val firstId = ChunkId(initialTargets.first().id)
                        val queuedIds = initialTargets.drop(1).map { ChunkId(it.id) }.toSet()
                        val isBackground = !isMediaOpenForTranscription(id)
                        stateFlow.update {
                            it.copy(
                                engineAvailable = true,
                                inFlightChunkId = firstId,
                                inFlightStep = TranscriptionStep.EXTRACTING_PCM,
                                inFlightDetail = if (isBackground) "バックグラウンド文字起こし中" else "先読み文字起こし中",
                                inFlightStartedAtEpochMs = clock(),
                                isForcedChunkInFlight = false,
                                queuedChunkIds = queuedIds,
                                skippedEmptyChunkIds = currentSkippedChunkIds(id),
                                completedCount = 0,
                                totalTargetCount = totalTargets,
                            )
                        }
                    } else {
                        updateQueuedChunkIdsState(id, activeChunks)
                    }

                    // プレイヤー画面が開いている間（またはバックグラウンド文字起こしが有効な間）は、残りのチャンクがなくなるまで順に処理する。
                    // 同じバッチ内で同じチャンクを繰り返さないよう、試したチャンクを覚えておく
                    val attemptedInBatch = HashSet<Long>()
                    while (stateFlow.value.enabled && canContinueTranscriptionForMedia(id)) {
                        if (!isMediaOpenForTranscription(id)) {
                            while (stateFlow.value.enabled &&
                                canContinueTranscriptionForMedia(id) &&
                                !isMediaOpenForTranscription(id) &&
                                shouldYieldBackgroundTranscription(id)
                            ) {
                                stateFlow.update { current ->
                                    current.copy(
                                        inFlightChunkId = null,
                                        inFlightStep = null,
                                        inFlightDetail = null,
                                        inFlightStartedAtEpochMs = null,
                                        isForcedChunkInFlight = false,
                                    )
                                }
                                delay(BACKGROUND_YIELD_POLL_MS)
                            }
                            if (!stateFlow.value.enabled || !canContinueTranscriptionForMedia(id)) {
                                break
                            }
                        }

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

                        var yieldedInSlot = false
                        recognitionSlotGate.withNormalSlot {
                            if (!stateFlow.value.enabled || !canContinueTranscriptionForMedia(id)) {
                                stateFlow.update { current ->
                                    current.copy(
                                        inFlightChunkId = null,
                                        inFlightStep = null,
                                        inFlightDetail = null,
                                        inFlightStartedAtEpochMs = null,
                                        isForcedChunkInFlight = false,
                                        queuedChunkIds = emptySet(),
                                        skippedEmptyChunkIds = currentSkippedChunkIds(id),
                                    )
                                }
                                return@withNormalSlot
                            }

                            if (!isMediaOpenForTranscription(id) && shouldYieldBackgroundTranscription(id)) {
                                yieldedInSlot = true
                                stateFlow.update { current ->
                                    current.copy(
                                        inFlightChunkId = null,
                                        inFlightStep = null,
                                        inFlightDetail = null,
                                        inFlightStartedAtEpochMs = null,
                                        isForcedChunkInFlight = false,
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
                                        inFlightStep = null,
                                        inFlightDetail = null,
                                        inFlightStartedAtEpochMs = null,
                                        isForcedChunkInFlight = false,
                                        queuedChunkIds = if (canContinueTranscriptionForMedia(id)) highIds else emptySet(),
                                        skippedEmptyChunkIds = currentSkippedChunkIds(id),
                                        completedCount = completed.coerceAtMost(totalTargets),
                                        totalTargetCount = totalTargets,
                                    )
                                }
                                return@withNormalSlot
                            }

                            val targetChunkId = ChunkId(targetChunk.id)
                            val queuedRest = if (canContinueTranscriptionForMedia(id)) {
                                refreshedTargets.drop(1).map { ChunkId(it.id) }.toSet() +
                                    pendingHighPriorityChunkIds[id]?.map { ChunkId(it) }?.toSet().orEmpty() -
                                    targetChunkId
                            } else {
                                emptySet()
                            }
                            val isBackground = !isMediaOpenForTranscription(id)
                            stateFlow.update {
                                it.copy(
                                    engineAvailable = true,
                                    inFlightChunkId = targetChunkId,
                                    inFlightStep = TranscriptionStep.EXTRACTING_PCM,
                                    inFlightDetail = if (isBackground) "バックグラウンド文字起こし中" else "先読み文字起こし中",
                                    inFlightStartedAtEpochMs = clock(),
                                    isForcedChunkInFlight = false,
                                    queuedChunkIds = queuedRest,
                                    skippedEmptyChunkIds = currentSkippedChunkIds(id),
                                    completedCount = completed,
                                    totalTargetCount = totalTargets,
                                )
                            }

                            val existingBefore = transcriptDao.getByChunkId(targetChunk.id)
                            val existingBeforeLang = existingBefore?.language
                            // 利用者が設定した言語（本文なし）はその言語だけで認識する。言語判定のみの結果は事前判定結果として引き継ぎ、二重の音声LID推論を省く
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
                            var precomputedLidLang = existingBefore
                                ?.takeIf { it.isLanguageIdOnly() }
                                ?.language?.trim()?.takeIf {
                                    it.isNotEmpty() && !it.equals("und", ignoreCase = true)
                                }
                                ?.let(::LanguageTag)
                            var precomputedLidConfidence = existingBefore
                                ?.takeIf { it.isLanguageIdOnly() }
                                ?.languageConfidence

                            try {
                                if (!isMediaOpenForTranscription(id)) {
                                    try {
                                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                                    } catch (_: Throwable) {
                                    }
                                }
                                val isTargetOnlyMode = id in targetLanguageOnlyMediaIds
                                val targetLangTag = languagePair?.target
                                if (isTargetOnlyMode &&
                                    precomputedLidLang == null &&
                                    effectiveLanguagePair?.base != null &&
                                    targetLangTag != null
                                ) {
                                    val detectedLid = try {
                                        onDeviceChunkTranscriber.detectChunkLanguage(
                                            audioFile = audioFile,
                                            startMs = targetChunk.startMs,
                                            endMs = targetChunk.endMs,
                                            languagePair = effectiveLanguagePair,
                                        )
                                    } catch (ce: CancellationException) {
                                        throw ce
                                    } catch (_: Exception) {
                                        null
                                    }
                                    val detectedLang = detectedLid?.language
                                    if (detectedLid != null && detectedLang != null) {
                                        if (!detectedLang.value.equals(targetLangTag.value, ignoreCase = true)) {
                                            withContext(NonCancellable) {
                                                persistLanguageOnlyTranscript(targetChunk.id, detectedLid)
                                            }
                                            return@withNormalSlot
                                        }
                                        precomputedLidLang = detectedLang
                                        precomputedLidConfidence = detectedLid.confidence
                                    }
                                }
                                // チャンク区間 [startMs, endMs] のみを切り出して認識し、チャンク間の無音はスキップする（F6.2）
                                val itemSttOverride = itemSttOverrideRepository?.get(id) ?: com.miuraken.chunkflow.core.model.ItemSttOverride.NONE
                                val engineByLanguage = userSettingsRepository?.settings?.first()?.sttEngineByLanguage ?: emptyMap()
                                val result: OnDeviceChunkTranscriptionResult? = try {
                                    onDeviceChunkTranscriber.transcribeChunk(
                                        audioFile = audioFile,
                                        startMs = targetChunk.startMs,
                                        endMs = targetChunk.endMs,
                                        languagePair = effectiveLanguagePair,
                                        forceRefresh = false,
                                        itemSttOverride = itemSttOverride,
                                        engineByLanguage = engineByLanguage,
                                        highPriority = false,
                                        precomputedLanguage = precomputedLidLang,
                                        precomputedLanguageConfidence = precomputedLidConfidence,
                                    )
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (_: Exception) {
                                    null
                                }

                                withContext(NonCancellable) {
                                    if (result != null && result.text.isNotBlank()) {
                                        skippedEmptyIds.remove(targetChunk.id)
                                        backgroundDeferredEmptyChunkIds[id]?.remove(targetChunk.id)
                                        val existing = transcriptDao.getByChunkId(targetChunk.id)
                                        val manualExistingLanguage = existing
                                            ?.takeIf { !it.isLanguageIdOnly() && (it.userEdited || it.engine == MANUAL_LANGUAGE_ENGINE_ID) }
                                            ?.language?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
                                        val resolvedLanguage = manualExistingLanguage
                                            ?: result.language?.value?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
                                            ?: existing?.language?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
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
                                        if (isMediaOpenForTranscription(id)) {
                                            skippedEmptyIds.add(targetChunk.id)
                                        } else {
                                            backgroundDeferredEmptyChunkIds.computeIfAbsent(id) {
                                                ConcurrentHashMap.newKeySet()
                                            }.add(targetChunk.id)
                                        }
                                    }
                                }
                            } finally {
                                withContext(NonCancellable) {
                                    completed += 1
                                    val nextQueued = if (stateFlow.value.enabled && canContinueTranscriptionForMedia(id)) {
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
                                        val stillThis = it.inFlightChunkId == targetChunkId
                                        it.copy(
                                            inFlightChunkId = if (stillThis) null else it.inFlightChunkId,
                                            inFlightStep = if (stillThis) null else it.inFlightStep,
                                            inFlightDetail = if (stillThis) null else it.inFlightDetail,
                                            inFlightStartedAtEpochMs = if (stillThis) null else it.inFlightStartedAtEpochMs,
                                            isForcedChunkInFlight = if (stillThis) false else it.isForcedChunkInFlight,
                                            queuedChunkIds = nextQueued,
                                            skippedEmptyChunkIds = currentSkippedChunkIds(id),
                                            completedCount = completed.coerceAtMost(totalTargets),
                                            totalTargetCount = totalTargets,
                                        )
                                    }
                                }
                            }
                        }

                        if (yieldedInSlot) {
                            continue
                        }
                        if (!isMediaOpenForTranscription(id)) {
                            yield()
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
                        translatedText = null,
                        translatedLang = null,
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
                            translatedText = null,
                            translatedLang = null,
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

    override suspend fun tryTranscribeChunkCustom(
        id: MediaId,
        chunkId: ChunkId,
        engine: OnDeviceTranscriptionEngine,
        languageOverride: LanguageTag?,
        audioSpeed: Float,
    ): ChunkTranscriptionTrialResult? = withContext(ioDispatcher) {
        val available = runCatching { onDeviceChunkTranscriber.isAvailable() }.getOrDefault(false)
        val audioFile = resolveReadableAudioFile(id, mediaFileResolver, mediaItemDao, context = context)
        if (!available || audioFile == null || !audioFile.isFile) {
            return@withContext null
        }

        val allChunks = chunkDao.observeByMediaId(id.value).first()
        val targetChunk = allChunks.firstOrNull { it.id == chunkId.value } ?: return@withContext null
        if (targetChunk.endMs <= targetChunk.startMs) return@withContext null

        val mediaItem = mediaItemDao.getById(id.value)
        val effectiveLanguagePair = if (languageOverride != null) {
            LanguagePair(target = languageOverride, base = null)
        } else {
            mediaItem?.let { item ->
                val target = item.targetLang.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                val base = item.baseLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                target?.let { LanguagePair(target = it, base = base) }
            }
        }

        recognitionSlotGate.withHighPrioritySlot {
            val recognized = try {
                onDeviceChunkTranscriber.transcribeChunkTrial(
                    audioFile = audioFile,
                    startMs = targetChunk.startMs,
                    endMs = targetChunk.endMs,
                    languagePair = effectiveLanguagePair,
                    engineOverride = engine,
                    audioSpeed = audioSpeed,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            } ?: return@withHighPrioritySlot null

            ChunkTranscriptionTrialResult(
                text = recognized.text,
                language = recognized.language ?: languageOverride ?: effectiveLanguagePair?.target,
                confidence = recognized.confidence,
                engineId = recognized.engineId,
            )
        }
    }

    override suspend fun applyChunkTranscriptionTrial(
        id: MediaId,
        chunkId: ChunkId,
        text: String,
        language: LanguageTag?,
    ) {
        withContext(ioDispatcher) {
            val normalizedText = truncateCodePoints(
                Normalizer.normalize(text, Normalizer.Form.NFC).trim(),
                MAX_TRANSCRIPT_TEXT_CODE_POINTS,
            )
            val resolvedLang = language?.value?.trim()?.takeIf {
                it.isNotEmpty() && !it.equals("und", ignoreCase = true)
            }
            val now = clock()
            val existing = transcriptDao.getByChunkId(chunkId.value)
            if (existing != null) {
                transcriptDao.upsert(
                    existing.copy(
                        text = normalizedText,
                        language = resolvedLang ?: existing.language,
                        userEdited = true,
                        translatedText = null,
                        translatedLang = null,
                    ),
                )
            } else {
                val mediaItem = mediaItemDao.getById(id.value)
                transcriptDao.upsert(
                    TranscriptEntity(
                        chunkId = chunkId.value,
                        text = normalizedText,
                        textVariants = "{}",
                        language = resolvedLang ?: mediaItem?.targetLang?.takeIf { it.isNotBlank() },
                        languageConfidence = null,
                        words = "[]",
                        engine = USER_EDIT_ENGINE_ID,
                        userEdited = true,
                        createdAt = now,
                    ),
                )
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

        val storedFeatures = resolveAnalysisFile(id, FEATURES_FILE_NAME)?.let {
            AnalysisBinaryFormat.readFeatures(it)
        }
        val energyAndBinMs: Pair<FloatArray, Int>? = if (storedFeatures != null && storedFeatures.logEnergyDb.isNotEmpty()) {
            storedFeatures.logEnergyDb to storedFeatures.binMs
        } else {
            val waveform = readWaveformFromDisk(id)
            if (waveform != null && waveform.rms.isNotEmpty() && waveform.binMs > 0) {
                FloatArray(waveform.rms.size) { i -> DbfsQuantizer.toDb(waveform.rms[i]) } to waveform.binMs
            } else {
                null
            }
        }
        val pieces = if (energyAndBinMs != null) {
            val segmentationParams = userSettingsRepository?.settings?.first()?.defaultSegmentationParams
                ?: SegmentationParams()
            chunkSegmenter.splitAndTrimSilencePieces(
                logEnergyDb = energyAndBinMs.first,
                chunkStartMs = target.startMs,
                chunkEndMs = target.endMs,
                splitPositionMs = splitPositionMs,
                params = segmentationParams,
                binMs = energyAndBinMs.second,
                speechProbability = storedFeatures?.speechProbability,
            )
        } else {
            listOf(target.startMs to splitPositionMs, splitPositionMs to target.endMs)
        }

        val now = clock()
        val effectiveTranscripts = resolveEffectiveTranscriptsForActiveChunks(id, activeChunks, allChunks)
        val newChunks = ArrayList<ChunkEntity>(activeChunks.size + 1)
        val newTranscriptsByIndex = LinkedHashMap<Int, TranscriptEntity>()

        for ((index, chunk) in activeChunks.withIndex()) {
            if (index < targetIndex) {
                val destIdx = newChunks.size
                newChunks.add(chunk.copy(id = 0L, origin = ChunkOrigin.MANUAL.name))
                effectiveTranscripts[chunk.id]?.let { newTranscriptsByIndex[destIdx] = it }
            } else if (index == targetIndex) {
                if (pieces.size == 1) {
                    val (singleStartMs, singleEndMs) = pieces[0]
                    val singleIndex = newChunks.size
                    newChunks.add(
                        chunk.copy(
                            id = 0L,
                            startMs = singleStartMs,
                            endMs = singleEndMs,
                            origin = ChunkOrigin.MANUAL.name,
                        ),
                    )
                    val targetTranscript = effectiveTranscripts[chunk.id]
                    if (targetTranscript != null) {
                        val domain = targetTranscript.toExternalModel()
                        val clampedWords = if (domain.words.isNotEmpty()) {
                            clampWordTimings(domain.words, singleStartMs, singleEndMs)
                        } else {
                            emptyList()
                        }
                        newTranscriptsByIndex[singleIndex] = domain.copy(
                            words = clampedWords,
                            userEdited = true,
                        ).toEntity(createdAtEpochMs = now)
                    }
                } else {
                    val (firstStartMs, firstEndMs) = pieces.getOrElse(0) { target.startMs to splitPositionMs }
                    val (secondStartMs, secondEndMs) = pieces.getOrElse(1) { splitPositionMs to target.endMs }
                    val firstHalfIndex = newChunks.size
                    val secondHalfIndex = firstHalfIndex + 1
                    newChunks.add(
                        chunk.copy(
                            id = 0L,
                            startMs = firstStartMs,
                            endMs = firstEndMs,
                            origin = ChunkOrigin.MANUAL.name,
                        ),
                    )
                    newChunks.add(
                        chunk.copy(
                            id = 0L,
                            startMs = secondStartMs,
                            endMs = secondEndMs,
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
                            val firstWords = clampWordTimings(firstWordsRaw, firstStartMs, firstEndMs)
                            val secondWords = clampWordTimings(secondWordsRaw, secondStartMs, secondEndMs)
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
        syncManualChunksSkippedExact(id.value, insertedChunks, newChunks)
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
                        isSkipped = target.isSkipped && next.isSkipped,
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
        syncManualChunksSkippedExact(id.value, insertedChunks, newChunks)
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

        val now = clock()
        val effectiveTranscripts = resolveEffectiveTranscriptsForActiveChunks(id, activeChunks, allChunks)
        val first = activeChunks[firstIndex]
        val firstLanguage = effectiveTranscripts[first.id]?.language?.takeIf { it.isNotBlank() }
        val firstLanguageConfidence = if (firstLanguage != null) {
            effectiveTranscripts[first.id]?.languageConfidence ?: 1.0f
        } else {
            null
        }
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
        mergedTranscript = mergedTranscript?.copy(
            language = firstLanguage,
            languageConfidence = firstLanguageConfidence,
            userEdited = true,
            createdAt = now,
        )

        val newChunks = ArrayList<ChunkEntity>(activeChunks.size - (lastIndex - firstIndex))
        val newTranscriptsByIndex = LinkedHashMap<Int, TranscriptEntity>()
        for ((index, chunk) in activeChunks.withIndex()) {
            when {
                index == firstIndex -> {
                    val mergedIndex = newChunks.size
                    val mergedSkipped = (firstIndex..lastIndex).all { activeChunks[it].isSkipped }
                    newChunks.add(
                        chunk.copy(
                            id = 0L,
                            startMs = mergedStartMs,
                            endMs = mergedEndMs,
                            origin = ChunkOrigin.MANUAL.name,
                            isSkipped = mergedSkipped,
                        ),
                    )
                    mergedTranscript?.let { newTranscriptsByIndex[mergedIndex] = it }
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
        syncManualChunksSkippedExact(id.value, insertedChunks, newChunks)
        syncManualTranscriptsExact(insertedChunks, newTranscriptsByIndex)
        setChunkSource(id, ChunkOrigin.MANUAL)
        persistTranscriptBackupFile(id)
        true
    }

    override suspend fun addChunk(id: MediaId, startMs: Long, endMs: Long): Boolean = withContext(ioDispatcher) {
        if (startMs < 0L || startMs >= endMs) {
            return@withContext false
        }

        val allChunks = chunkDao.observeByMediaId(id.value).first()
        val itemState = itemStateDao.getByMediaId(id.value)
        val activeChunks = selectActiveChunkEntities(itemState, allChunks)
        if (activeChunks.any { startMs < it.endMs && endMs > it.startMs }) {
            return@withContext false
        }

        val storedFeatures = resolveAnalysisFile(id, FEATURES_FILE_NAME)?.let {
            AnalysisBinaryFormat.readFeatures(it)
        }
        val energyAndBinMs: Pair<FloatArray, Int>? = if (storedFeatures != null && storedFeatures.logEnergyDb.isNotEmpty()) {
            storedFeatures.logEnergyDb to storedFeatures.binMs
        } else {
            val waveform = readWaveformFromDisk(id)
            if (waveform != null && waveform.rms.isNotEmpty() && waveform.binMs > 0) {
                FloatArray(waveform.rms.size) { i -> DbfsQuantizer.toDb(waveform.rms[i]) } to waveform.binMs
            } else {
                null
            }
        }
        val segmentationParams = itemState?.let { ItemStateEntity.decodeSegmentationParams(it.customSegmentation) }
            ?: userSettingsRepository?.settings?.first()?.defaultSegmentationParams
            ?: SegmentationParams()
        val isSilenceOnly = energyAndBinMs != null && chunkSegmenter.isSilenceOnlyInRange(
            logEnergyDb = energyAndBinMs.first,
            rangeStartMs = startMs,
            rangeEndMs = endMs,
            params = segmentationParams,
            binMs = energyAndBinMs.second,
            speechProbability = storedFeatures?.speechProbability,
        )
        val (trimmedStartMs, trimmedEndMs) = if (energyAndBinMs != null && !isSilenceOnly) {
            chunkSegmenter.trimSilenceInRange(
                logEnergyDb = energyAndBinMs.first,
                rangeStartMs = startMs,
                rangeEndMs = endMs,
                params = segmentationParams,
                binMs = energyAndBinMs.second,
                speechProbability = storedFeatures?.speechProbability,
            )
        } else {
            startMs to endMs
        }
        if (trimmedStartMs >= trimmedEndMs) {
            return@withContext false
        }

        val previousEmptyChunkIds = attemptedEmptyChunkIds[id].orEmpty()
        val previousEmptyRanges = if (previousEmptyChunkIds.isNotEmpty()) {
            activeChunks
                .filter { it.id in previousEmptyChunkIds }
                .map { it.startMs to it.endMs }
                .toSet()
        } else {
            emptySet()
        }

        val effectiveTranscripts = resolveEffectiveTranscriptsForActiveChunks(id, activeChunks, allChunks)
        val newChunks = ArrayList<ChunkEntity>(activeChunks.size + 1)
        val newTranscriptsByIndex = LinkedHashMap<Int, TranscriptEntity>()
        val insertedEntity = ChunkEntity(
            id = 0L,
            mediaId = id.value,
            startMs = trimmedStartMs,
            endMs = trimmedEndMs,
            origin = ChunkOrigin.MANUAL.name,
            algorithmVersion = ChunkSegmenter.CURRENT_ALGORITHM_VERSION,
            isSkipped = false,
        )

        var insertedChunkIndex = -1
        for (chunk in activeChunks) {
            if (insertedChunkIndex < 0 && chunk.startMs >= trimmedEndMs) {
                insertedChunkIndex = newChunks.size
                newChunks.add(insertedEntity)
            }
            val destIndex = newChunks.size
            newChunks.add(chunk.copy(id = 0L, origin = ChunkOrigin.MANUAL.name))
            effectiveTranscripts[chunk.id]?.let { newTranscriptsByIndex[destIndex] = it }
        }
        if (insertedChunkIndex < 0) {
            insertedChunkIndex = newChunks.size
            newChunks.add(insertedEntity)
        }

        val insertedChunks = chunkDao.replaceChunksTransaction(
            mediaId = id.value,
            origin = ChunkOrigin.MANUAL.name,
            newChunks = newChunks,
            newTranscriptsByIndex = newTranscriptsByIndex,
            preserveAllOldTranscripts = true,
        )
        syncManualChunksSkippedExact(id.value, insertedChunks, newChunks)
        syncManualTranscriptsExact(insertedChunks, newTranscriptsByIndex)
        setChunkSource(id, ChunkOrigin.MANUAL)

        if (previousEmptyRanges.isNotEmpty() || isSilenceOnly) {
            val skippedSet = attemptedEmptyChunkIds.computeIfAbsent(id) {
                ConcurrentHashMap.newKeySet()
            }
            skippedSet.clear()
            for ((index, chunk) in insertedChunks.withIndex()) {
                if ((chunk.startMs to chunk.endMs) in previousEmptyRanges) {
                    skippedSet.add(chunk.id)
                }
                if (isSilenceOnly && index == insertedChunkIndex) {
                    skippedSet.add(chunk.id)
                }
            }
            lookaheadStateFor(id).update { current ->
                current.copy(skippedEmptyChunkIds = currentSkippedChunkIds(id))
            }
            updateQueuedChunkIdsState(id)
        }

        persistTranscriptBackupFile(id)
        true
    }

    override suspend fun deleteChunk(id: MediaId, chunkId: ChunkId): Boolean = deleteChunks(id, setOf(chunkId))

    override suspend fun deleteChunks(id: MediaId, chunkIds: Set<ChunkId>): Boolean = withContext(ioDispatcher) {
        if (chunkIds.isEmpty()) return@withContext false
        val allChunks = chunkDao.observeByMediaId(id.value).first()
        val itemState = itemStateDao.getByMediaId(id.value)
        val activeChunks = selectActiveChunkEntities(itemState, allChunks)
        val targetIdValues = chunkIds.mapTo(HashSet(chunkIds.size)) { it.value }
        if (activeChunks.none { it.id in targetIdValues }) return@withContext false

        val effectiveTranscripts = resolveEffectiveTranscriptsForActiveChunks(id, activeChunks, allChunks)
        val newChunks = ArrayList<ChunkEntity>((activeChunks.size - targetIdValues.size).coerceAtLeast(0))
        val newTranscriptsByIndex = LinkedHashMap<Int, TranscriptEntity>()

        for (chunk in activeChunks) {
            if (chunk.id in targetIdValues) continue
            val destIndex = newChunks.size
            newChunks.add(chunk.copy(id = 0L, origin = ChunkOrigin.MANUAL.name))
            effectiveTranscripts[chunk.id]?.let { newTranscriptsByIndex[destIndex] = it }
        }

        val insertedChunks = chunkDao.replaceChunksTransaction(
            mediaId = id.value,
            origin = ChunkOrigin.MANUAL.name,
            newChunks = newChunks,
            newTranscriptsByIndex = newTranscriptsByIndex,
            preserveAllOldTranscripts = true,
        )
        if (newChunks.isEmpty()) {
            val remainingIds = allChunks.map { it.id }
            if (remainingIds.isNotEmpty()) {
                chunkDao.deleteByIds(remainingIds)
            }
        }
        syncManualChunksSkippedExact(id.value, insertedChunks, newChunks)
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
        syncManualChunksSkippedExact(id.value, insertedChunks, newChunks)
        syncManualTranscriptsExact(insertedChunks, newTranscriptsByIndex)
        setChunkSource(id, ChunkOrigin.MANUAL)
        persistTranscriptBackupFile(id)
        true
    }

    override suspend fun setChunksSkipped(
        id: MediaId,
        chunkIds: Set<ChunkId>,
        skipped: Boolean,
    ) {
        if (chunkIds.isEmpty()) return
        withContext(ioDispatcher) {
            chunkDao.updateSkippedByIds(
                mediaId = id.value,
                chunkIds = chunkIds.map { it.value },
                skipped = skipped,
            )
        }
    }

    override fun isTranslationSupported(
        sourceLanguage: LanguageTag?,
        targetLanguage: LanguageTag?,
    ): Boolean = textTranslator.isSupported(sourceLanguage, targetLanguage)

    override suspend fun translateChunk(
        id: MediaId,
        chunkId: ChunkId,
        sourceLanguage: LanguageTag,
        targetLanguage: LanguageTag,
    ): String? = withContext(ioDispatcher) {
        val entity = transcriptDao.getByChunkId(chunkId.value) ?: return@withContext null
        if (entity.text.isBlank()) return@withContext null
        if (!entity.translatedText.isNullOrBlank() &&
            entity.translatedLang?.equals(targetLanguage.value, ignoreCase = true) == true
        ) {
            return@withContext entity.translatedText
        }
        val translated = textTranslator.translate(
            text = entity.text,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
        )?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext null
        transcriptDao.updateTranslation(
            chunkId = chunkId.value,
            translatedText = translated,
            translatedLang = targetLanguage.value,
        )
        translated
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
                enabled = defaultAutoStartLookaheadEnabled.get(),
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

    private suspend fun syncManualChunksSkippedExact(
        mediaId: String,
        insertedChunks: List<ChunkEntity>,
        desiredChunks: List<ChunkEntity>,
    ) {
        val toSkip = ArrayList<Long>()
        val toUnskip = ArrayList<Long>()
        for (i in insertedChunks.indices) {
            val inserted = insertedChunks[i]
            val desired = desiredChunks.getOrNull(i) ?: continue
            if (inserted.isSkipped != desired.isSkipped) {
                if (desired.isSkipped) {
                    toSkip.add(inserted.id)
                } else {
                    toUnskip.add(inserted.id)
                }
            }
        }
        if (toSkip.isNotEmpty()) {
            chunkDao.updateSkippedByIds(mediaId, toSkip, true)
        }
        if (toUnskip.isNotEmpty()) {
            chunkDao.updateSkippedByIds(mediaId, toUnskip, false)
        }
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
        val t1 = d1.translatedText?.trim().orEmpty()
        val t2 = d2.translatedText?.trim().orEmpty()
        val mergedTranslatedText = when {
            t1.isNotEmpty() && t2.isNotEmpty() ->
                truncateCodePoints("$t1 $t2", MAX_TRANSCRIPT_TEXT_CODE_POINTS)

            t1.isNotEmpty() -> t1

            t2.isNotEmpty() -> t2

            else -> null
        }
        val mergedTranslatedLang = if (mergedTranslatedText != null) {
            d1.translatedLanguage ?: d2.translatedLanguage
        } else {
            null
        }

        return Transcript(
            chunkId = ChunkId(0L),
            text = mergedText,
            textVariants = mergedVariants,
            language = mergedLang,
            languageConfidence = mergedConfidence,
            words = mergedWords,
            engine = EngineId(mergedEngine),
            userEdited = true,
            translatedText = mergedTranslatedText,
            translatedLanguage = mergedTranslatedLang,
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
        val audioFile = resolveReadableAudioFile(id, mediaFileResolver, mediaItemDao, mediaItem, context)
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

        private const val BACKGROUND_YIELD_POLL_MS: Long = 20L
        private const val LID_DB_BATCH_FLUSH_INTERVAL_MS: Long = 400L
        private const val LID_DB_BATCH_MAX_SIZE: Int = 16
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
            if (state.isListened || state.positionMs > 0L || state.maxPositionMs > 0L) return false
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
        itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
        engineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
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
            val remaining = pendingHighPriorityCount.decrementAndGet()
            if (remaining < 0) {
                pendingHighPriorityCount.set(0)
            }
            if (remaining <= 0) {
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

    fun cancelAllWaiters() {
        val toCancel = synchronized(stateLock) {
            val list = highPriorityQueue.toList() + normalPriorityQueue.toList()
            highPriorityQueue.clear()
            normalPriorityQueue.clear()
            list
        }
        val cause = CancellationException("Recognition slot waiters cancelled")
        for (waiter in toCancel) {
            waiter.cancel(cause)
        }
    }

    fun forceReset() {
        val toCancel = synchronized(stateLock) {
            val list = highPriorityQueue.toList() + normalPriorityQueue.toList()
            highPriorityQueue.clear()
            normalPriorityQueue.clear()
            isLocked = false
            pendingHighPriorityCount.set(0)
            list
        }
        val cause = CancellationException("Recognition slot gate forcibly reset")
        for (waiter in toCancel) {
            waiter.cancel(cause)
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

internal sealed interface ResolvedAudioSource {
    data class LocalFile(val file: File) : ResolvedAudioSource
    data class SourceUri(val uri: Uri) : ResolvedAudioSource
}

@SuppressLint("UseKtx")
internal suspend fun resolveAudioSource(
    mediaId: MediaId,
    mediaFileResolver: LibraryMediaFileResolver,
    mediaItemDao: MediaItemDao,
    cachedMediaItem: MediaItemEntity? = null,
): ResolvedAudioSource? {
    val managedFile = mediaFileResolver.resolveAudioFile(mediaId)
    if (managedFile != null && managedFile.isFile && managedFile.canRead()) {
        return ResolvedAudioSource.LocalFile(managedFile)
    }
    val item = cachedMediaItem ?: mediaItemDao.getById(mediaId.value) ?: return null
    val localFile = item.localFilePath
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let(::File)
        ?.takeIf { it.isFile && it.canRead() }
    if (localFile != null) {
        return ResolvedAudioSource.LocalFile(localFile)
    }
    val rawUri = item.sourceUri?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parsedUri = runCatching { Uri.parse(rawUri) }.getOrNull() ?: return null
    val scheme = parsedUri.scheme?.lowercase(Locale.ROOT)
    if (scheme == null || scheme == "file") {
        val uriFile = parsedUri.path
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.takeIf { it.isFile && it.canRead() }
            ?: File(rawUri).takeIf { it.isFile && it.canRead() }
        return uriFile?.let(ResolvedAudioSource::LocalFile)
    }
    return ResolvedAudioSource.SourceUri(parsedUri)
}

internal fun resolveExpectedAudioScopeKey(
    mediaId: MediaId,
    mediaItem: MediaItemEntity?,
    audioSource: ResolvedAudioSource?,
    context: Context?,
): String {
    if (audioSource is ResolvedAudioSource.LocalFile) {
        return audioSource.file.absolutePath
    }
    if (audioSource is ResolvedAudioSource.SourceUri && context != null) {
        val ext = inferAudioExtension(mediaItem?.mimeType, audioSource.uri)
        val analysisDir = File(
            File(context.filesDir, DefaultAnalysisRepository.ANALYSIS_DIR_NAME),
            mediaId.value,
        )
        return File(analysisDir, "source_audio.$ext").absolutePath
    }
    return mediaId.value
}

private val sourceAudioCacheMutexes = ConcurrentHashMap<MediaId, Mutex>()

internal suspend fun resolveReadableAudioFile(
    mediaId: MediaId,
    mediaFileResolver: LibraryMediaFileResolver,
    mediaItemDao: MediaItemDao,
    cachedMediaItem: MediaItemEntity? = null,
    context: Context? = null,
): File? {
    val source = resolveAudioSource(mediaId, mediaFileResolver, mediaItemDao, cachedMediaItem) ?: return null
    return when (source) {
        is ResolvedAudioSource.LocalFile -> source.file

        is ResolvedAudioSource.SourceUri -> {
            if (!MediaFileLocator.isSafeIdentifier(mediaId.value)) return null
            val uri = source.uri
            if (uri.scheme?.lowercase(Locale.ROOT) == "file") {
                val directFile = uri.path
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::File)
                    ?.takeIf { it.isFile && it.canRead() }
                if (directFile != null) return directFile
            }
            val ctx = context ?: return null
            val item = cachedMediaItem ?: mediaItemDao.getById(mediaId.value)
            val ext = inferAudioExtension(item?.mimeType, uri)
            val analysisDir = File(
                File(ctx.filesDir, DefaultAnalysisRepository.ANALYSIS_DIR_NAME),
                mediaId.value,
            ).apply { mkdirs() }
            if (!analysisDir.exists() || !analysisDir.isDirectory) return null
            val cachedFile = File(analysisDir, "source_audio.$ext")
            if (cachedFile.isFile && cachedFile.length() > 0L) {
                return cachedFile
            }

            val mutex = sourceAudioCacheMutexes.computeIfAbsent(mediaId) { Mutex() }
            mutex.withLock {
                if (cachedFile.isFile && cachedFile.length() > 0L) {
                    return@withLock cachedFile
                }
                val tempFile = File(analysisDir, "source_audio.$ext.tmp")
                try {
                    val inputStream = runCatching {
                        ctx.contentResolver.openInputStream(uri)
                    }.getOrNull() ?: uri.path
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::File)
                        ?.takeIf { it.isFile && it.canRead() }
                        ?.let { runCatching { FileInputStream(it) }.getOrNull() }
                        ?: return@withLock null

                    inputStream.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                    if (!tempFile.isFile || tempFile.length() <= 0L) {
                        tempFile.delete()
                        return@withLock null
                    }
                    if (!tempFile.renameTo(cachedFile)) {
                        tempFile.copyTo(cachedFile, overwrite = true)
                        tempFile.delete()
                    }
                    cachedFile.takeIf { it.isFile && it.length() > 0L }
                } catch (ce: CancellationException) {
                    tempFile.delete()
                    throw ce
                } catch (_: Exception) {
                    tempFile.delete()
                    null
                }
            }
        }
    }
}

private fun inferAudioExtension(mimeType: String?, uri: Uri): String {
    val fromMime = when (mimeType?.trim()?.lowercase(Locale.ROOT)) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/mp4a-latm", "audio/x-m4a" -> "m4a"
        "audio/wav", "audio/x-wav", "audio/vnd.wave", "audio/raw" -> "wav"
        "audio/ogg", "audio/vorbis", "application/ogg" -> "ogg"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/aac" -> "aac"
        "audio/opus" -> "opus"
        else -> null
    }
    if (fromMime != null) return fromMime

    val candidatePath = uri.lastPathSegment ?: uri.path ?: uri.toString()
    val rawExt = candidatePath
        .substringAfterLast('.', "")
        .substringBefore('?')
        .substringBefore('#')
        .lowercase(Locale.ROOT)
    return when (rawExt) {
        "mp3", "m4a", "wav", "ogg", "flac", "aac", "opus" -> rawExt
        else -> "mp3"
    }
}
