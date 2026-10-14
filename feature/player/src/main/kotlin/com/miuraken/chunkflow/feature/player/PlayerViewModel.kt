package com.miuraken.chunkflow.feature.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.UserSettings
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.playback.PlaybackController
import com.miuraken.chunkflow.core.playback.PlaybackError
import com.miuraken.chunkflow.core.playback.PlaybackStatus
import com.miuraken.chunkflow.core.playback.PositionProvider
import com.miuraken.chunkflow.core.testing.fake.FakePlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * チャンク 1 件の UI 表示モデル（§6.2）。
 *
 * @property id チャンク ID。
 * @property startMs 開始時刻（ミリ秒）。
 * @property endMs 終了時刻（ミリ秒）。
 * @property text 文字起こしテキスト（なければ `null`）。
 * @property language 言語タグ（未判定または信頼度が低い場合は `null`）。
 * @property isBookmarked ブックマーク済みかどうか。
 */
data class ChunkUi(
    val id: ChunkId,
    val startMs: Long,
    val endMs: Long,
    val text: String?,
    val language: LanguageTag?,
    val isBookmarked: Boolean = false,
)

/**
 * プレイヤー画面の UI 状態（§6.2、F3.1、F3.2、F3.3）。
 *
 * 高頻度で変わる再生位置はここには含めず、[PositionProvider] から描画・子 Composable で読む。
 */
data class PlayerUiState(
    val mediaId: MediaId? = null,
    val title: String = "",
    val course: String? = null,
    val languagePair: LanguagePair? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val seekBackMs: Long = UserSettings.DEFAULT_SEEK_BACK_MS,
    val seekForwardMs: Long = UserSettings.DEFAULT_SEEK_FORWARD_MS,
    val practice: PracticeSettings = PracticeSettings(),
    val autoPauseEndsAtMs: Long? = null,
    val waveform: Waveform? = null,
    val chunks: List<ChunkUi> = emptyList(),
    val activeChunkIndex: Int? = null,
    val analysis: Map<AnalysisStage, StageStatus> = emptyMap(),
    val error: PlaybackError? = null,
) {
    companion object {
        /** 再生速度の微調整の刻み幅（0.05 倍、F3.2）。 */
        const val SPEED_STEP: Float = 0.05f

        /** 再生速度のプリセット一覧（F3.2）。 */
        val SPEED_PRESETS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    }
}

/** プレイヤー画面のイベント。 */
sealed interface PlayerEvent {
    /** 前の画面に戻る。 */
    data object Back : PlayerEvent

    /** 再生と一時停止を切り替える。 */
    data object TogglePlayPause : PlayerEvent

    /** 指定した位置（ミリ秒）にシークする。 */
    data class SeekTo(val positionMs: Long) : PlayerEvent

    /** 設定された秒数だけ巻き戻す。 */
    data object SeekBack : PlayerEvent

    /** 設定された秒数だけ早送りする。 */
    data object SeekForward : PlayerEvent

    /** 再生速度を指定した倍率（0.5〜2.0 倍）に変更する。 */
    data class SetSpeed(val speed: Float) : PlayerEvent

    /** 現在の再生速度を [delta]（例: `-0.05f` / `+0.05f`）だけ増減する。 */
    data class AdjustSpeed(val delta: Float) : PlayerEvent
}

/**
 * プレイヤー画面の ViewModel（§6.2、F3.1、F3.2、F3.3、F2.2）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val userSettingsRepository: UserSettingsRepository,
) : ViewModel() {

    private val currentMediaId = MutableStateFlow<MediaId?>(null)
    private var lastOpenedMediaId: MediaId? = null

    /** 高頻度で変わる再生位置のプロバイダ（§6.2）。 */
    val positionProvider: PositionProvider
        get() = playbackController.position

    private val currentAudioItem = currentMediaId.flatMapLatest { id ->
        if (id == null) flowOf(null) else libraryRepository.observeItem(id)
    }

    /** プレイヤー画面の UI 状態。 */
    val uiState: StateFlow<PlayerUiState> = combine(
        currentMediaId,
        currentAudioItem,
        playbackController.status,
        playbackController.practice,
        userSettingsRepository.settings,
    ) { mediaId, item, status, practice, settings ->
        buildUiState(
            mediaId = mediaId,
            item = item,
            status = status,
            practice = practice,
            settings = settings,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PlayerUiState(),
    )

    /** 指定した教材を開いて再生準備を行う。 */
    fun open(mediaId: MediaId) {
        currentMediaId.value = mediaId
        if (lastOpenedMediaId == mediaId) return
        lastOpenedMediaId = mediaId
        viewModelScope.launch {
            val savedState = libraryRepository.observeItemState(mediaId).firstOrNull()
            playbackController.open(mediaId)
            if (playbackController is FakePlaybackController && savedState != null) {
                playbackController.position.positionMs = savedState.positionMs
                playbackController.setSpeed(savedState.speed)
            }
        }
    }

    /** 画面からのイベントを処理する。 */
    fun onEvent(event: PlayerEvent) {
        when (event) {
            PlayerEvent.TogglePlayPause -> togglePlayPause()

            is PlayerEvent.SeekTo -> seekTo(event.positionMs)

            PlayerEvent.SeekBack -> {
                val seekBackMs = uiState.value.seekBackMs
                val currentPos = playbackController.position.currentPositionMs()
                seekTo(currentPos - seekBackMs)
            }

            PlayerEvent.SeekForward -> {
                val seekForwardMs = uiState.value.seekForwardMs
                val currentPos = playbackController.position.currentPositionMs()
                seekTo(currentPos + seekForwardMs)
            }

            is PlayerEvent.SetSpeed -> setSpeed(event.speed)

            is PlayerEvent.AdjustSpeed -> {
                val currentSpeed = playbackController.status.value.speed
                setSpeed(currentSpeed + event.delta)
            }

            PlayerEvent.Back -> Unit
        }
    }

    private fun togglePlayPause() {
        val player = playbackController.player.value
        if (player != null) {
            if (playbackController.status.value.isPlaying || player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        } else if (playbackController is FakePlaybackController) {
            playbackController.statusState.update { it.copy(isPlaying = !it.isPlaying) }
        }
    }

    private fun seekTo(targetPositionMs: Long) {
        val durationMs = uiState.value.durationMs
        val clampedMs = if (durationMs > 0L) {
            targetPositionMs.coerceIn(0L, durationMs)
        } else {
            targetPositionMs.coerceAtLeast(0L)
        }
        val player = playbackController.player.value
        if (player != null) {
            player.seekTo(clampedMs)
        }
        if (playbackController is FakePlaybackController) {
            playbackController.position.positionMs = clampedMs
        }
    }

    private fun setSpeed(rawSpeed: Float) {
        val clamped = rawSpeed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
        val rounded = (clamped * 20f).roundToInt() / 20f
        playbackController.setSpeed(rounded.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED))
    }

    private fun buildUiState(
        mediaId: MediaId?,
        item: AudioItem?,
        status: PlaybackStatus,
        practice: PracticeSettings,
        settings: UserSettings,
    ): PlayerUiState {
        val resolvedDurationMs = when {
            status.durationMs > 0L -> status.durationMs
            item != null -> item.durationMs
            else -> 0L
        }
        return PlayerUiState(
            mediaId = mediaId ?: status.mediaId ?: item?.id,
            title = item?.title.orEmpty(),
            course = item?.course,
            languagePair = item?.languagePair,
            isPlaying = status.isPlaying,
            durationMs = resolvedDurationMs,
            speed = status.speed,
            seekBackMs = settings.seekBackMs,
            seekForwardMs = settings.seekForwardMs,
            practice = practice,
            autoPauseEndsAtMs = status.autoPauseEndsAtMs,
            error = status.error,
        )
    }
}
