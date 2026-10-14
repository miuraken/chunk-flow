package com.miuraken.chunkflow.core.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PracticeSettings
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [PlaybackController] の本番実装（§6.3、§7.2.1〜§7.2.5）。
 *
 * - [LearningPlayer]（内側は [PlaybackPlayerFactory] で構成した ExoPlayer）を管理し、UI と [PlaybackService] で共有する。
 * - `open(mediaId)` で [LibraryRepository] から [AudioItem] と [ItemState] を取得し、ローカル音声ファイルの有無を検証した上で
 *   前回の位置・速度で再生準備を行う（ファイルが欠損している場合は [PlaybackError.SOURCE_MISSING] を設定する）。
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
    private val rawPlayerFactory: () -> Player = { PlaybackPlayerFactory.createExoPlayer(context) },
    private val clock: () -> Long = System::currentTimeMillis,
) : PlaybackController {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        libraryRepository: LibraryRepository,
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
        rawPlayerFactory = { PlaybackPlayerFactory.createExoPlayer(context) },
        clock = System::currentTimeMillis,
    )

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var periodicSaveJob: Job? = null

    private var currentChunks: List<Chunk> = emptyList()
    private var activeChunkSource: ChunkOrigin = ChunkOrigin.AUTO
    private var learningPlayerInstance: LearningPlayer? = null
    private var rawPlayerInstance: Player? = null

    private val _player = MutableStateFlow<Player?>(null)
    override val player: StateFlow<Player?> = _player.asStateFlow()

    private val _status = MutableStateFlow(PlaybackStatus())
    override val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    override val practice: StateFlow<PracticeSettings> = practiceController.settings

    override val position: PositionProvider = PositionProvider {
        learningPlayerInstance?.currentPosition?.coerceAtLeast(0L) ?: 0L
    }

    init {
        // ユーザ設定のシーク秒数を監視し、LearningPlayer に反映する
        scope.launch {
            userSettingsRepository.settings.collect { settings ->
                learningPlayerInstance?.apply {
                    seekBackIncrementMs = settings.seekBackMs.coerceAtLeast(1_000L)
                    seekForwardIncrementMs = settings.seekForwardMs.coerceAtLeast(1_000L)
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
        val learning = LearningPlayer(inner) {
            currentChunks.map { it.startMs }
        }
        learning.addListener(ControllerPlayerListener())
        rawPlayerInstance = inner
        learningPlayerInstance = learning
        _player.value = learning
        return learning
    }

    override suspend fun open(mediaId: MediaId) {
        withContext(mainDispatcher) {
            openInternal(mediaId)
        }
    }

    private suspend fun openInternal(mediaId: MediaId) {
        // 別の教材を開く前に、現在再生中の教材があればレジューム位置を保存する
        val previousMediaId = _status.value.mediaId
        if (previousMediaId != null && previousMediaId != mediaId) {
            persistCurrentPositionNow(previousMediaId)
        }

        // 別教材への切り替え時は A-B ループ・チャンクループを解除する（§7.2.5）
        practiceController.resetLoop()

        val item = libraryRepository.observeItem(mediaId).first()
        if (item == null) {
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

        val audioFile = mediaFileLocator.resolveAudioFile(mediaId, item.mimeType)
        if (audioFile == null || !audioFile.exists()) {
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

        val savedState = libraryRepository.observeItemState(mediaId).first()
        val userSettings = userSettingsRepository.settings.first()
        val initialSpeed = (savedState?.speed ?: userSettings.defaultSpeed)
            .coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
        val initialPositionMs = (savedState?.positionMs ?: 0L)
            .coerceIn(0L, item.durationMs.coerceAtLeast(0L))

        // 保存済みの学習モード（オートポーズ・言語フィルタ・チャンク種別）を復元する（ループは常に Off）
        if (savedState != null) {
            activeChunkSource = savedState.chunkSource
            practiceController.setSettings(
                PracticeSettings(
                    loop = LoopMode.Off,
                    autoPause = savedState.autoPause,
                    autoPauseScope = savedState.autoPauseScope,
                    languageFilter = savedState.languageFilter,
                ),
            )
        } else {
            activeChunkSource = ChunkOrigin.AUTO
            practiceController.setSettings(PracticeSettings())
        }

        val lp = getOrCreateLearningPlayer()
        lp.knownDurationMs = item.durationMs
        lp.seekBackIncrementMs = userSettings.seekBackMs.coerceAtLeast(1_000L)
        lp.seekForwardIncrementMs = userSettings.seekForwardMs.coerceAtLeast(1_000L)

        val mediaItem = buildMediaItem(item, audioFile)
        lp.setMediaItem(mediaItem, initialPositionMs)
        lp.setPlaybackSpeed(initialSpeed)
        lp.prepare()

        _status.value = PlaybackStatus(
            mediaId = mediaId,
            isPlaying = lp.isPlaying,
            durationMs = item.durationMs,
            speed = initialSpeed,
            currentChunkId = findChunkAt(initialPositionMs)?.id,
            autoPauseEndsAtMs = null,
            error = null,
        )
    }

    override fun seekToChunk(chunkId: ChunkId) {
        scope.launch {
            val targetChunk = currentChunks.firstOrNull { it.id == chunkId } ?: return@launch
            learningPlayerInstance?.seekTo(targetChunk.startMs)
            _status.update {
                it.copy(
                    currentChunkId = chunkId,
                    autoPauseEndsAtMs = null,
                )
            }
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
        practiceController.update(transform)
        val updated = practiceController.settings.value
        val currentMediaId = _status.value.mediaId ?: return
        scope.launch {
            val currentPos = learningPlayerInstance?.currentPosition?.coerceAtLeast(0L) ?: 0L
            val currentSpeed = _status.value.speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
            libraryRepository.upsertItemState(
                ItemState(
                    mediaId = currentMediaId,
                    positionMs = currentPos,
                    speed = currentSpeed,
                    chunkSource = activeChunkSource,
                    autoPause = updated.autoPause,
                    autoPauseScope = updated.autoPauseScope,
                    languageFilter = updated.languageFilter,
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

        val audioFile = mediaFileLocator.resolveAudioFile(resumeItem.id, resumeItem.mimeType)
        if (audioFile == null || !audioFile.exists()) {
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

        val lp = getOrCreateLearningPlayer()
        lp.knownDurationMs = resumeItem.durationMs
        lp.setPlaybackSpeed(resumeSpeed)

        _status.value = PlaybackStatus(
            mediaId = resumeItem.id,
            isPlaying = false,
            durationMs = resumeItem.durationMs,
            speed = resumeSpeed,
            currentChunkId = findChunkAt(resumePositionMs)?.id,
            autoPauseEndsAtMs = null,
            error = null,
        )

        val mediaItem = buildMediaItem(resumeItem, audioFile)
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
        learningPlayerInstance?.release()
        learningPlayerInstance = null
        rawPlayerInstance = null
        _player.value = null
        scope.cancel()
    }

    private fun startPeriodicPositionSave() {
        if (periodicSaveJob?.isActive == true) return
        periodicSaveJob = scope.launch {
            while (isActive) {
                delay(POSITION_SAVE_INTERVAL_MS)
                val mediaId = _status.value.mediaId ?: continue
                if (_status.value.isPlaying) {
                    persistCurrentPositionNow(mediaId)
                }
            }
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

    private fun buildMediaItem(item: AudioItem, audioFile: File): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .apply {
                item.course?.let { setAlbumTitle(it) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(item.id.value)
            .setUri(Uri.fromFile(audioFile))
            .setMimeType(item.mimeType)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun findChunkAt(positionMs: Long): Chunk? = currentChunks.lastOrNull { it.startMs <= positionMs }

    private inner class ControllerPlayerListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val pos = learningPlayerInstance?.currentPosition?.coerceAtLeast(0L) ?: 0L
            _status.update {
                it.copy(
                    isPlaying = isPlaying,
                    currentChunkId = findChunkAt(pos)?.id,
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
            _status.update {
                it.copy(
                    currentChunkId = findChunkAt(pos)?.id,
                )
            }
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

    companion object {
        /** 再生中にレジューム位置を DB に書き込む周期（§7.2.5: 5 秒ごと）。 */
        internal const val POSITION_SAVE_INTERVAL_MS: Long = 5_000L
    }
}
