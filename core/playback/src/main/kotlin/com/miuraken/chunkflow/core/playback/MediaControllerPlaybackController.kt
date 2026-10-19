package com.miuraken.chunkflow.core.playback

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.AudioStorageState
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.playback.di.PlaybackMainDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [PlaybackController] の本番実装（§6.3、§7.2.1〜§7.2.5）。
 *
 * - [LearningPlayer]（内側は [PlaybackPlayerFactory] で構成した ExoPlayer）を管理し、UI と [PlaybackService] で共有する。
 * - 内側の Player が [ExoPlayer] の場合は [ChunkScheduler] を接続し、`PlayerMessage` によるチャンク境界制御・
 *   リピート・自動ポーズ・言語フィルタおよび [PlaybackStatus.currentChunkId] / [PlaybackStatus.autoPauseEndsAtMs] の更新を行う（§7.2.4、ADR 0007）。
 * - `open(mediaId)` で [LibraryRepository] から [AudioItem] と [ItemState] を取得し、ローカル音声ファイルの有無を検証した上で
 *   前回の位置・速度・学習モードで再生準備を行う（ファイルが欠損している場合は [PlaybackError.SOURCE_MISSING] を設定する）。
 * - 再生中は 5 秒ごと、および一時停止・停止・教材切り替え・速度変更時に [LibraryRepository.updatePlaybackPosition] で
 *   レジューム位置と速度を Room に保存する（§7.2.5）。
 */
@OptIn(UnstableApi::class)
@Singleton
class MediaControllerPlaybackController(
    @ApplicationContext private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val practiceController: PracticeController,
    private val mediaFileLocator: MediaFileLocator,
    @PlaybackMainDispatcher private val mainDispatcher: CoroutineDispatcher,
    private val analysisRepository: AnalysisRepository = NoOpAnalysisRepository,
    private val rawPlayerFactory: () -> Player = { PlaybackPlayerFactory.createExoPlayer(context) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val bindPlaybackService: Boolean = false,
) : PlaybackController {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        libraryRepository: LibraryRepository,
        analysisRepository: AnalysisRepository,
        userSettingsRepository: UserSettingsRepository,
        practiceController: PracticeController,
        mediaFileLocator: MediaFileLocator,
        @PlaybackMainDispatcher mainDispatcher: CoroutineDispatcher,
    ) : this(
        context = context,
        libraryRepository = libraryRepository,
        userSettingsRepository = userSettingsRepository,
        practiceController = practiceController,
        mediaFileLocator = mediaFileLocator,
        mainDispatcher = mainDispatcher,
        analysisRepository = analysisRepository,
        rawPlayerFactory = { PlaybackPlayerFactory.createExoPlayer(context) },
        clock = System::currentTimeMillis,
        bindPlaybackService = true,
    )

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var periodicSaveJob: Job? = null
    private var chunkObservationJob: Job? = null
    private var sessionControllerFuture: ListenableFuture<MediaController>? = null

    private var currentChunks: List<Chunk> = emptyList()
    private var lastLookaheadTarget: Pair<MediaId, ChunkId?>? = null
    private var activeChunkSource: ChunkOrigin = ChunkOrigin.AUTO
    private var tailPlayedMs: Long = 0L
    private var isCurrentItemMarkedListened: Boolean = false
    private var learningPlayerInstance: LearningPlayer? = null
    private var rawPlayerInstance: Player? = null
    internal var chunkScheduler: ChunkScheduler? = null
        private set

    private val _player = MutableStateFlow<Player?>(null)
    override val player: StateFlow<Player?> = _player.asStateFlow()

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    override val practice: StateFlow<PracticeSettings> = practiceController.settings

    override val position: PositionProvider = PositionProvider {
        learningPlayerInstance?.currentPosition?.coerceAtLeast(0L) ?: 0L
    }

    init {
        // ユーザ設定のシーク秒数と自動ポーズ設定を監視し、LearningPlayer と PracticeController に反映する
        scope.launch {
            userSettingsRepository.settings.collect { settings ->
                learningPlayerInstance?.apply {
                    seekBackIncrementMs = settings.seekBackMs.coerceAtLeast(1_000L)
                    seekForwardIncrementMs = settings.seekForwardMs.coerceAtLeast(1_000L)
                }
                practiceController.update {
                    it.copy(
                        autoPause = settings.defaultAutoPause,
                        autoPauseScope = settings.defaultAutoPauseScope,
                        skipOriginalSilence = settings.skipOriginalSilence,
                    )
                }
            }
        }
    }

    /**
     * [PlaybackService] および内部操作向けに [LearningPlayer] を取得または生成する。
     * メインスレッド（またはテストの Main dispatcher）から呼び出すこと。
     */
    internal fun getOrCreateLearningPlayer(): LearningPlayer {
        learningPlayerInstance?.let { return it }
        val inner = rawPlayerFactory()
        val learning = LearningPlayer(
            player = inner,
            selectedChunkIdsProvider = { practiceController.settings.value.repeatChunkIds },
            chunkStartsMsProvider = {
                currentChunks.filterNot { it.isSkipped }.ifEmpty { currentChunks }.map { it.startMs }
            },
        )
        learning.chunks = currentChunks
        learning.addListener(ControllerPlayerListener())
        rawPlayerInstance = inner
        learningPlayerInstance = learning

        if (inner is ExoPlayer) {
            val scheduler = ChunkScheduler(
                exoPlayer = inner,
                practiceController = practiceController,
                analysisRepository = analysisRepository,
                libraryRepository = libraryRepository,
                powerManager = context.getSystemService(PowerManager::class.java),
                mainLooper = inner.applicationLooper,
                mainDispatcher = mainDispatcher,
                onStateChanged = { chunkId, autoPauseEndsAt ->
                    _status.update {
                        it.copy(
                            currentChunkId = chunkId,
                            autoPauseEndsAtMs = autoPauseEndsAt,
                        )
                    }
                    notifyLookaheadIfChanged(_status.value.mediaId, chunkId)
                },
            )
            chunkScheduler = scheduler
            learning.onUserPlayWhenReadyCommand = {
                scheduler.onUserPlayPauseCommand()
            }
        }

        _player.value = learning
        ensurePlaybackServiceBound()
        return learning
    }

    /**
     * [PlaybackService]（[MediaSessionService]）を起動・接続し、システム UI（クイック設定パネル・ロック画面のメディアコントロール）と
     * バックグラウンド再生を有効化する（§7.2.1、§7.2.6）。
     */
    private fun ensurePlaybackServiceBound() {
        if (!bindPlaybackService) return
        val existing = sessionControllerFuture
        if (existing != null && !existing.isCancelled) return
        runCatching {
            val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            sessionControllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        }
    }

    override suspend fun open(mediaId: MediaId) {
        withContext(mainDispatcher) {
            openInternal(mediaId)
        }
    }

    private suspend fun openInternal(mediaId: MediaId) {
        // 別の教材を開く前に、現在再生中の教材があればレジューム位置を保存する
        val previousMediaId = _status.value.mediaId
        val isSameMediaAlreadyLoaded =
            previousMediaId == mediaId &&
                learningPlayerInstance != null &&
                _status.value.error == null
        if (previousMediaId != null && previousMediaId != mediaId) {
            persistCurrentPositionNow(previousMediaId)
        }

        // 別教材への切り替え時（または初回ロード時）は A-B ループ・チャンクループを解除する（§7.2.5）
        if (!isSameMediaAlreadyLoaded) {
            practiceController.resetLoop()
        }

        val item = libraryRepository.observeItem(mediaId).first()
        if (item == null) {
            stopObservingChunks()
            _status.update {
                it.copy(
                    mediaId = mediaId,
                    isPlaying = false,
                    durationMs = 0L,
                    currentChunkId = null,
                    autoPauseEndsAtMs = null,
                    error = PlaybackError.SOURCE_MISSING,
                )
            }
            return
        }

        val playableUri = resolvePlayableAudioUri(item)
        if (playableUri == null) {
            stopObservingChunks()
            learningPlayerInstance?.let { lp ->
                lp.stop()
                lp.clearMediaItems()
            }
            _status.update {
                it.copy(
                    mediaId = mediaId,
                    isPlaying = false,
                    durationMs = item.durationMs,
                    currentChunkId = null,
                    autoPauseEndsAtMs = null,
                    error = PlaybackError.SOURCE_MISSING,
                )
            }
            return
        }

        if (isSameMediaAlreadyLoaded) {
            val userSettings = userSettingsRepository.settings.first()
            learningPlayerInstance?.apply {
                seekBackIncrementMs = userSettings.seekBackMs.coerceAtLeast(1_000L)
                seekForwardIncrementMs = userSettings.seekForwardMs.coerceAtLeast(1_000L)
            }
            practiceController.update {
                it.copy(
                    autoPause = userSettings.defaultAutoPause,
                    autoPauseScope = userSettings.defaultAutoPauseScope,
                    skipOriginalSilence = userSettings.skipOriginalSilence,
                )
            }
            notifyLookaheadIfChanged(mediaId, _status.value.currentChunkId, force = true)
            return
        }

        val savedState = libraryRepository.observeItemState(mediaId).first()
        val userSettings = userSettingsRepository.settings.first()
        val initialSpeed = (savedState?.speed ?: userSettings.defaultSpeed)
            .coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
        val rawSavedPositionMs = (savedState?.positionMs ?: 0L)
            .coerceIn(0L, item.durationMs.coerceAtLeast(0L))
        val initialPositionMs = if (item.durationMs > 0L && rawSavedPositionMs >= item.durationMs - 500L) {
            0L
        } else {
            rawSavedPositionMs
        }
        tailPlayedMs = 0L
        isCurrentItemMarkedListened = savedState?.isListened ?: false

        // ファイルを開いた時点で最終アクセス日時・累計アクセス回数・再開位置を記録する
        val openedAtMs = clock()
        libraryRepository.updatePlaybackPosition(
            id = mediaId,
            positionMs = initialPositionMs,
            speed = initialSpeed,
            updatedAtEpochMs = openedAtMs,
        )
        libraryRepository.recordItemAccess(
            id = mediaId,
            accessedAtEpochMs = openedAtMs,
        )

        // 保存済みの学習モード（言語フィルタ・チャンク種別）を復元する。
        // 自動ポーズは常に全体設定の再生設定（UserSettings.defaultAutoPause / defaultAutoPauseScope）に従う（ループは常に Off、§7.2.5）。
        if (savedState != null) {
            activeChunkSource = savedState.chunkSource
            practiceController.setSettings(
                PracticeSettings(
                    loop = LoopMode.Off,
                    autoPause = userSettings.defaultAutoPause,
                    autoPauseScope = userSettings.defaultAutoPauseScope,
                    skipOriginalSilence = userSettings.skipOriginalSilence,
                    languageFilter = savedState.languageFilter,
                ),
            )
        } else {
            activeChunkSource = ChunkOrigin.AUTO
            practiceController.setSettings(
                PracticeSettings(
                    loop = LoopMode.Off,
                    autoPause = userSettings.defaultAutoPause,
                    autoPauseScope = userSettings.defaultAutoPauseScope,
                    skipOriginalSilence = userSettings.skipOriginalSilence,
                ),
            )
        }

        val lp = getOrCreateLearningPlayer()
        lp.knownDurationMs = item.durationMs
        lp.seekBackIncrementMs = userSettings.seekBackMs.coerceAtLeast(1_000L)
        lp.seekForwardIncrementMs = userSettings.seekForwardMs.coerceAtLeast(1_000L)

        // チャンク一覧を取得して LearningPlayer と ChunkScheduler に反映する
        val initialChunks = analysisRepository.observeChunks(mediaId).first().sortedBy { it.startMs }
        currentChunks = initialChunks
        lp.chunks = initialChunks
        observeActiveMediaChunks(mediaId, item.languagePair)

        val mediaItem = buildMediaItem(item, playableUri)
        lp.setMediaItem(mediaItem, initialPositionMs)
        lp.setPlaybackSpeed(initialSpeed)
        lp.prepare()
        chunkScheduler?.setChunks(initialChunks)

        val initialChunkId = chunkScheduler?.currentChunkId?.value ?: findChunkAt(initialPositionMs)?.id
        _status.value = PlaybackStatus(
            mediaId = mediaId,
            isPlaying = lp.isPlaying,
            durationMs = item.durationMs,
            speed = initialSpeed,
            currentChunkId = initialChunkId,
            autoPauseEndsAtMs = chunkScheduler?.autoPauseEndsAtMs?.value,
            error = null,
        )
        notifyLookaheadIfChanged(mediaId, initialChunkId, force = true)
    }

    override fun seekToChunk(chunkId: ChunkId) {
        scope.launch {
            val targetChunk = currentChunks.firstOrNull { it.id == chunkId } ?: return@launch
            val lp = learningPlayerInstance
            val scheduler = chunkScheduler
            val practice = practiceController.settings.value
            val repeatChunkIds = practice.repeatChunkIds
            val isSelectedRepeatTarget = chunkId in repeatChunkIds &&
                (repeatChunkIds.size >= 2 || (practice.repeatCheckedOnly && practice.temporaryRepeatChunkId == chunkId))

            if (isSelectedRepeatTarget) {
                if (scheduler != null) {
                    scheduler.playFromSelectedChunk(chunkId)
                } else {
                    lp?.seekTo(targetChunk.startMs)
                    lp?.play()
                }
            } else {
                val shouldStartSingleChunkPlayback =
                    lp?.isPlaying != true || scheduler?.isSingleChunkPlaybackActive == true

                if (shouldStartSingleChunkPlayback) {
                    if (scheduler != null) {
                        scheduler.startSingleChunkPlayback(chunkId)
                    } else {
                        lp?.seekTo(targetChunk.startMs)
                        lp?.play()
                    }
                } else {
                    scheduler?.cancelAutoPause()
                    lp.seekTo(targetChunk.startMs)
                }
            }

            _status.update {
                it.copy(
                    isPlaying = lp?.isPlaying == true,
                    currentChunkId = chunkId,
                    autoPauseEndsAtMs = null,
                )
            }
            notifyLookaheadIfChanged(_status.value.mediaId, chunkId)
        }
    }

    override fun setSpeed(speed: Float) {
        val coerced = speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
        scope.launch {
            learningPlayerInstance?.setPlaybackSpeed(coerced)
            _status.update { it.copy(speed = coerced) }
            val currentMediaId = _status.value.mediaId ?: return@launch
            val currentPos = learningPlayerInstance?.currentPosition?.coerceAtLeast(0L) ?: 0L
            libraryRepository.updatePlaybackPosition(
                id = currentMediaId,
                positionMs = currentPos,
                speed = coerced,
                updatedAtEpochMs = clock(),
            )
        }
    }

    override fun updatePractice(transform: (PracticeSettings) -> PracticeSettings) {
        // 自動ポーズは常に全体設定の再生設定（UserSettings.defaultAutoPause / defaultAutoPauseScope）に従う設計
        practiceController.update(transform)
        val updated = practiceController.settings.value
        val currentMediaId = _status.value.mediaId ?: return
        scope.launch {
            val currentPos = learningPlayerInstance?.currentPosition?.coerceAtLeast(0L) ?: 0L
            val currentSpeed = _status.value.speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
            val existingState = libraryRepository.observeItemState(currentMediaId).first()
            val resolvedChunkSource = existingState?.chunkSource ?: activeChunkSource
            val maxPos = maxOf(
                existingState?.maxPositionMs ?: 0L,
                existingState?.positionMs ?: 0L,
                currentPos,
            )
            activeChunkSource = resolvedChunkSource
            libraryRepository.upsertItemState(
                ItemState(
                    mediaId = currentMediaId,
                    positionMs = currentPos,
                    maxPositionMs = maxPos,
                    speed = currentSpeed,
                    chunkSource = resolvedChunkSource,
                    autoPause = updated.autoPause,
                    autoPauseScope = updated.autoPauseScope,
                    languageFilter = updated.languageFilter,
                    isListened = isCurrentItemMarkedListened || (existingState?.isListened == true),
                    isFavorite = existingState?.isFavorite ?: false,
                    queuedAtEpochMs = existingState?.queuedAtEpochMs ?: 0L,
                    accessCount = existingState?.accessCount ?: 0,
                    updatedAtEpochMs = clock(),
                ),
            )
        }
    }

    /**
     * OS からの再生再開（Bluetooth イヤホンの再生ボタン等、`onPlaybackResumption`）に備え、
     * 最後に再生していた教材と位置を復元する（§7.2.5）。
     */
    internal suspend fun prepareForResumption(): MediaSession.MediaItemsWithStartPosition? = withContext(mainDispatcher) {
        val states = libraryRepository.observeItemStates().first()
        val items = libraryRepository.observeItems().first()
        if (items.isEmpty()) return@withContext null

        // 最も新しく更新された ItemState を持つ教材、なければ最後に取り込まれた教材を選ぶ
        val resumeItem = states.values
            .maxByOrNull { it.updatedAtEpochMs }
            ?.let { latestState -> items.firstOrNull { it.id == latestState.mediaId } }
            ?: items.maxByOrNull { it.importedAtEpochMs }
            ?: return@withContext null

        val playableUri = resolvePlayableAudioUri(resumeItem)
        if (playableUri == null) {
            stopObservingChunks()
            _status.update {
                it.copy(
                    mediaId = resumeItem.id,
                    isPlaying = false,
                    durationMs = resumeItem.durationMs,
                    error = PlaybackError.SOURCE_MISSING,
                )
            }
            return@withContext null
        }

        val savedState = states[resumeItem.id]
        val userSettings = userSettingsRepository.settings.first()
        val resumeSpeed = (savedState?.speed ?: userSettings.defaultSpeed)
            .coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
        val resumePositionMs = (savedState?.positionMs ?: 0L)
            .coerceIn(0L, resumeItem.durationMs.coerceAtLeast(0L))

        if (savedState != null) {
            activeChunkSource = savedState.chunkSource
            practiceController.setSettings(
                PracticeSettings(
                    loop = LoopMode.Off,
                    autoPause = userSettings.defaultAutoPause,
                    autoPauseScope = userSettings.defaultAutoPauseScope,
                    skipOriginalSilence = userSettings.skipOriginalSilence,
                    languageFilter = savedState.languageFilter,
                ),
            )
        } else {
            activeChunkSource = ChunkOrigin.AUTO
            practiceController.setSettings(
                PracticeSettings(
                    loop = LoopMode.Off,
                    autoPause = userSettings.defaultAutoPause,
                    autoPauseScope = userSettings.defaultAutoPauseScope,
                    skipOriginalSilence = userSettings.skipOriginalSilence,
                ),
            )
        }

        val lp = getOrCreateLearningPlayer()
        lp.knownDurationMs = resumeItem.durationMs
        lp.setPlaybackSpeed(resumeSpeed)

        val initialChunks = analysisRepository.observeChunks(resumeItem.id).first().sortedBy { it.startMs }
        currentChunks = initialChunks
        lp.chunks = initialChunks
        observeActiveMediaChunks(resumeItem.id, resumeItem.languagePair)

        val resumeChunkId = findChunkAt(resumePositionMs)?.id
        _status.value = PlaybackStatus(
            mediaId = resumeItem.id,
            isPlaying = false,
            durationMs = resumeItem.durationMs,
            speed = resumeSpeed,
            currentChunkId = resumeChunkId,
            autoPauseEndsAtMs = null,
            error = null,
        )
        notifyLookaheadIfChanged(resumeItem.id, resumeChunkId, force = true)

        val mediaItem = buildMediaItem(resumeItem, playableUri)
        MediaSession.MediaItemsWithStartPosition(
            listOf(mediaItem),
            0,
            resumePositionMs,
        )
    }

    /**
     * サービス終了時や画面破棄時に、現在の再生位置を即座に保存する（§7.2.5）。
     */
    internal suspend fun flushPlaybackPosition() {
        val mediaId = _status.value.mediaId ?: return
        persistCurrentPositionNow(mediaId)
    }

    /**
     * テストやサービス終了時にリソースを解放する。
     */
    internal fun release() {
        periodicSaveJob?.cancel()
        periodicSaveJob = null
        chunkObservationJob?.cancel()
        chunkObservationJob = null
        sessionControllerFuture?.let { MediaController.releaseFuture(it) }
        sessionControllerFuture = null
        chunkScheduler?.release()
        chunkScheduler = null
        learningPlayerInstance?.release()
        learningPlayerInstance = null
        rawPlayerInstance = null
        _player.value = null
        scope.cancel()
    }

    private fun observeActiveMediaChunks(mediaId: MediaId, initialLanguagePair: LanguagePair?) {
        chunkObservationJob?.cancel()
        chunkScheduler?.bindMedia(mediaId, initialLanguagePair)
        chunkObservationJob = scope.launch {
            analysisRepository.observeChunks(mediaId).collect { chunks ->
                val sorted = chunks.sortedBy { it.startMs }
                sorted.firstOrNull()?.origin?.let { activeChunkSource = it }
                currentChunks = sorted
                learningPlayerInstance?.chunks = sorted
                val pos = learningPlayerInstance?.currentPosition?.coerceAtLeast(0L) ?: 0L
                val updatedChunkId = chunkScheduler?.currentChunkId?.value ?: findChunkAt(pos)?.id
                _status.update {
                    it.copy(currentChunkId = updatedChunkId)
                }
                notifyLookaheadIfChanged(mediaId, updatedChunkId)
            }
        }
    }

    private fun stopObservingChunks() {
        chunkObservationJob?.cancel()
        chunkObservationJob = null
        currentChunks = emptyList()
        lastLookaheadTarget = null
        learningPlayerInstance?.chunks = emptyList()
        chunkScheduler?.bindMedia(null)
    }

    private fun notifyLookaheadIfChanged(
        mediaId: MediaId?,
        chunkId: ChunkId?,
        force: Boolean = false,
    ) {
        val targetMediaId = mediaId ?: return
        val key = targetMediaId to chunkId
        if (!force && lastLookaheadTarget == key) return
        lastLookaheadTarget = key
        scope.launch {
            analysisRepository.triggerLookaheadAt(targetMediaId, chunkId)
        }
    }

    private fun startPeriodicPositionSave() {
        if (periodicSaveJob?.isActive == true) return
        periodicSaveJob = scope.launch {
            var elapsedSinceSaveMs = 0L
            while (isActive) {
                delay(LISTENED_CHECK_INTERVAL_MS)
                val mediaId = _status.value.mediaId ?: continue
                if (_status.value.isPlaying) {
                    checkAndMarkListenedOnTick(mediaId, LISTENED_CHECK_INTERVAL_MS)
                    elapsedSinceSaveMs += LISTENED_CHECK_INTERVAL_MS
                    if (elapsedSinceSaveMs >= POSITION_SAVE_INTERVAL_MS) {
                        elapsedSinceSaveMs = 0L
                        persistCurrentPositionNow(mediaId)
                    }
                }
            }
        }
    }

    private suspend fun checkAndMarkListenedOnTick(mediaId: MediaId, deltaMs: Long) {
        val lp = learningPlayerInstance ?: return
        val durationMs = _status.value.durationMs.takeIf { it > 0L }
            ?: lp.duration.takeIf { it > 0L }
            ?: 0L
        if (durationMs <= 0L) return
        val posMs = lp.currentPosition.coerceAtLeast(0L)
        val tailStartMs = (durationMs * LISTENED_TAIL_START_PERCENT) / 100L
        if (posMs >= tailStartMs) {
            tailPlayedMs += deltaMs
            val requiredTailMs = minOf(
                LISTENED_REQUIRED_PLAYED_MS,
                (durationMs * (100L - LISTENED_TAIL_START_PERCENT) / 100L).coerceAtLeast(LISTENED_CHECK_INTERVAL_MS),
            )
            if (!isCurrentItemMarkedListened && tailPlayedMs >= requiredTailMs) {
                isCurrentItemMarkedListened = true
                libraryRepository.markItemListened(
                    id = mediaId,
                    isListened = true,
                    updatedAtEpochMs = clock(),
                )
            }
        }
    }

    private suspend fun checkAndMarkListenedOnEnded(mediaId: MediaId) {
        val lp = learningPlayerInstance ?: return
        val durationMs = _status.value.durationMs.takeIf { it > 0L }
            ?: lp.duration.takeIf { it > 0L }
            ?: 0L
        if (durationMs <= 0L) return
        val posMs = lp.currentPosition.coerceAtLeast(0L)
        val tailStartMs = (durationMs * LISTENED_TAIL_START_PERCENT) / 100L
        if (posMs >= tailStartMs) {
            tailPlayedMs += LISTENED_CHECK_INTERVAL_MS
        }
        val requiredTailMs = minOf(
            LISTENED_REQUIRED_PLAYED_MS,
            (durationMs * (100L - LISTENED_TAIL_START_PERCENT) / 100L).coerceAtLeast(LISTENED_CHECK_INTERVAL_MS),
        )
        if (!isCurrentItemMarkedListened && tailPlayedMs >= requiredTailMs) {
            isCurrentItemMarkedListened = true
            libraryRepository.markItemListened(
                id = mediaId,
                isListened = true,
                updatedAtEpochMs = clock(),
            )
        }
    }

    private fun stopPeriodicPositionSaveAndFlush() {
        periodicSaveJob?.cancel()
        periodicSaveJob = null
        val mediaId = _status.value.mediaId ?: return
        scope.launch {
            persistCurrentPositionNow(mediaId)
        }
    }

    private suspend fun persistCurrentPositionNow(mediaId: MediaId) {
        val lp = learningPlayerInstance ?: return
        val currentPos = lp.currentPosition.coerceAtLeast(0L)
        val currentSpeed = _status.value.speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
        libraryRepository.updatePlaybackPosition(
            id = mediaId,
            positionMs = currentPos,
            speed = currentSpeed,
            updatedAtEpochMs = clock(),
        )
    }

    @SuppressLint("UseKtx")
    internal fun resolvePlayableAudioUri(item: AudioItem): Uri? {
        val managedFile = mediaFileLocator.resolveAudioFile(item.id, item.mimeType)
            ?: File(context.filesDir, "media/${item.id.value}").takeIf { it.isFile }
        if (managedFile != null && managedFile.exists()) {
            return Uri.fromFile(managedFile)
        }
        if (item.storageState == AudioStorageState.DRIVE_REMOTE_ONLY) {
            return null
        }
        val localPath = item.localFilePath?.takeIf { it.isNotBlank() }
        if (localPath != null) {
            val localFile = File(localPath)
            if (localFile.exists() && localFile.canRead()) {
                return Uri.fromFile(localFile)
            }
        }
        val sourceUri = item.sourceUri?.takeIf { it.isNotBlank() }
        if (sourceUri != null) {
            return Uri.parse(sourceUri)
        }
        return null
    }

    internal fun buildMediaItem(item: AudioItem, playableUri: Uri): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .apply {
                item.course?.takeIf { it.isNotBlank() }?.let { course ->
                    setArtist(course)
                    setAlbumTitle(course)
                    setSubtitle(course)
                }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(item.id.value)
            .setUri(playableUri)
            .setMimeType(item.mimeType)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun findChunkAt(positionMs: Long): Chunk? = currentChunks.firstOrNull { positionMs in it.startMs until it.endMs }
        ?: currentChunks.lastOrNull { it.startMs <= positionMs }

    private inner class ControllerPlayerListener : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                val mediaId = _status.value.mediaId ?: return
                scope.launch {
                    checkAndMarkListenedOnEnded(mediaId)
                    persistCurrentPositionNow(mediaId)
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val pos = learningPlayerInstance?.currentPosition?.coerceAtLeast(0L) ?: 0L
            _status.update {
                it.copy(
                    isPlaying = isPlaying,
                    currentChunkId = chunkScheduler?.currentChunkId?.value ?: findChunkAt(pos)?.id,
                    autoPauseEndsAtMs = chunkScheduler?.autoPauseEndsAtMs?.value,
                )
            }
            if (isPlaying) {
                startPeriodicPositionSave()
            } else {
                stopPeriodicPositionSaveAndFlush()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val pos = newPosition.positionMs.coerceAtLeast(0L)
            val updatedChunkId = chunkScheduler?.currentChunkId?.value ?: findChunkAt(pos)?.id
            _status.update {
                it.copy(
                    currentChunkId = updatedChunkId,
                    autoPauseEndsAtMs = chunkScheduler?.autoPauseEndsAtMs?.value,
                )
            }
            notifyLookaheadIfChanged(_status.value.mediaId, updatedChunkId)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            _status.update { it.copy(speed = playbackParameters.speed) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val mappedError = when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                -> PlaybackError.SOURCE_MISSING

                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                -> PlaybackError.DECODER

                else -> PlaybackError.UNKNOWN
            }
            _status.update {
                it.copy(
                    isPlaying = false,
                    error = mappedError,
                )
            }
        }
    }

    private object NoOpAnalysisRepository : AnalysisRepository {
        override fun observeStatus(id: MediaId): Flow<Map<AnalysisStage, StageStatus>> = flowOf(emptyMap())
        override fun observeWaveform(id: MediaId): Flow<Waveform?> = flowOf(null)
        override fun observeChunks(id: MediaId): Flow<List<Chunk>> = flowOf(emptyList())
        override fun observeTranscripts(id: MediaId): Flow<Map<ChunkId, Transcript>> = flowOf(emptyMap())
        override suspend fun requestAnalysis(id: MediaId, stages: Set<AnalysisStage>) = Unit
        override suspend fun resegment(id: MediaId, params: SegmentationParams) = Unit
        override suspend fun setChunkSource(id: MediaId, source: ChunkOrigin) = Unit
        override suspend fun setTranscriptOffset(id: MediaId, offsetMs: Int) = Unit
    }

    companion object {
        /** 再生中にレジューム位置を DB に書き込む周期（§7.2.5: 5 秒ごと）。 */
        internal const val POSITION_SAVE_INTERVAL_MS: Long = 5_000L

        /** 95%〜100% 領域の再生時間チェック周期（200ms）。 */
        internal const val LISTENED_CHECK_INTERVAL_MS: Long = 200L

        /** 聴取完了（Listened）と判定する末尾領域の開始割合（95%）。 */
        internal const val LISTENED_TAIL_START_PERCENT: Long = 95L

        /** 95%〜100% 領域で聴取完了（Listened）と判定するのに必要な累計再生時間（1,000ms）。 */
        internal const val LISTENED_REQUIRED_PLAYED_MS: Long = 1_000L
    }
}
