package com.miuraken.chunkflow.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miuraken.chunkflow.core.data.library.ImportError
import com.miuraken.chunkflow.core.data.library.ImportProgress
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.playback.PlaybackController
import com.miuraken.chunkflow.core.playback.PlaybackStatus
import com.miuraken.chunkflow.core.testing.fake.FakePlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ライブラリ一覧の各教材の UI 表示モデル（F2.1、F2.2）。
 *
 * @property item 教材のメタデータ。
 * @property positionMs 前回のレジューム再生位置（ミリ秒）。
 * @property speed 前回の再生速度（0.5..2.0）。
 */
data class LibraryItemUiModel(
    val item: AudioItem,
    val positionMs: Long = 0L,
    val speed: Float = 1.0f,
) {
    /** 再生進捗の割合（0.0..1.0）。 */
    val progressFraction: Float
        get() = if (item.durationMs > 0L) {
            (positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
}

/**
 * 音声取り込みの UI 状態（F1.1）。
 */
sealed interface LibraryImportUiState {
    /** 取り込みを行っていない。 */
    data object Idle : LibraryImportUiState

    /**
     * ファイルをコピー・検証中。
     *
     * @property fraction 進捗（0..1）。不明なら `null`。
     */
    data class Copying(val fraction: Float?) : LibraryImportUiState

    /**
     * 取り込みが完了した。
     *
     * @property addedCount 取り込んだ教材数。
     * @property duplicates 重複していた音声の数。
     */
    data class Done(val addedCount: Int, val duplicates: Int) : LibraryImportUiState

    /**
     * 取り込みに失敗した。
     *
     * @property error 失敗の種別。
     */
    data class Error(val error: ImportError) : LibraryImportUiState
}

/**
 * ライブラリ画面下部のミニプレイヤーの状態（§7.5.1）。
 *
 * @property mediaId 現在開いている教材の ID。
 * @property title 現在開いている教材のタイトル。
 * @property isPlaying 再生中かどうか。
 * @property durationMs 教材の長さ（ミリ秒）。
 */
data class MiniPlayerUiState(
    val mediaId: MediaId,
    val title: String,
    val isPlaying: Boolean,
    val durationMs: Long,
)

/**
 * ライブラリ画面の状態（F1.1、F2.1、F2.2、§7.5.1）。
 *
 * @property items 教材の一覧（レジューム位置・速度を含む）。
 * @property importState 音声取り込みの進捗・エラー状態。
 * @property miniPlayer 現在開いている教材のミニプレイヤー表示（開いていなければ `null`）。
 * @property pendingDeleteItem 削除確認ダイアログで対象になっている教材（表示中でなければ `null`）。
 */
data class LibraryUiState(
    val items: List<LibraryItemUiModel> = emptyList(),
    val importState: LibraryImportUiState = LibraryImportUiState.Idle,
    val miniPlayer: MiniPlayerUiState? = null,
    val pendingDeleteItem: AudioItem? = null,
)

/** ライブラリ画面のイベント。 */
sealed interface LibraryEvent {
    /** SAF ピッカーを開いて音声ファイルを取り込む。 */
    data object RequestImport : LibraryEvent

    /** SAF で選択された URI の一覧を取り込む。 */
    data class ImportUris(val uris: List<Uri>) : LibraryEvent

    /** 取り込み完了・エラーのバナーを閉じる。 */
    data object DismissImportStatus : LibraryEvent

    /** 教材を開いてプレイヤー画面へ遷移する。 */
    data class OpenItem(val id: MediaId) : LibraryEvent

    /** 設定画面を開く。 */
    data object OpenSettings : LibraryEvent

    /** 教材の削除確認ダイアログを開く。 */
    data class RequestDelete(val item: AudioItem) : LibraryEvent

    /** 削除確認ダイアログで削除を実行する。 */
    data object ConfirmDelete : LibraryEvent

    /** 削除確認ダイアログをキャンセルする。 */
    data object CancelDelete : LibraryEvent

    /** ミニプレイヤーの再生 / 一時停止を切り替える。 */
    data object ToggleMiniPlayerPlayPause : LibraryEvent
}

/**
 * ライブラリ画面の ViewModel（F1.1、F2.1、F2.2、§7.5.1）。
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
) : ViewModel() {

    private val importState = MutableStateFlow<LibraryImportUiState>(LibraryImportUiState.Idle)
    private val pendingDeleteItem = MutableStateFlow<AudioItem?>(null)
    private var importJob: Job? = null

    /** ライブラリ画面の UI 状態。 */
    val uiState: StateFlow<LibraryUiState> = combine(
        libraryRepository.observeItems(),
        libraryRepository.observeItemStates(),
        playbackController.status,
        importState,
        pendingDeleteItem,
    ) { items, itemStates, playbackStatus, currentImportState, deleteTarget ->
        buildUiState(
            items = items,
            itemStates = itemStates,
            playbackStatus = playbackStatus,
            importState = currentImportState,
            pendingDeleteItem = deleteTarget,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    /** 画面からのイベントを処理する。 */
    fun onEvent(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.ImportUris -> importUris(event.uris)

            LibraryEvent.DismissImportStatus -> {
                importState.value = LibraryImportUiState.Idle
            }

            is LibraryEvent.RequestDelete -> {
                pendingDeleteItem.value = event.item
            }

            LibraryEvent.CancelDelete -> {
                pendingDeleteItem.value = null
            }

            LibraryEvent.ConfirmDelete -> confirmDelete()

            LibraryEvent.ToggleMiniPlayerPlayPause -> toggleMiniPlayerPlayPause()

            LibraryEvent.RequestImport,
            is LibraryEvent.OpenItem,
            LibraryEvent.OpenSettings,
            -> Unit
        }
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            libraryRepository.import(uris).collect { progress ->
                importState.value = when (progress) {
                    is ImportProgress.Copying -> LibraryImportUiState.Copying(progress.fraction)

                    is ImportProgress.Done -> LibraryImportUiState.Done(
                        addedCount = progress.mediaIds.size,
                        duplicates = progress.duplicates,
                    )

                    is ImportProgress.Failed -> LibraryImportUiState.Error(progress.error)
                }
            }
        }
    }

    private fun confirmDelete() {
        val target = pendingDeleteItem.value ?: return
        pendingDeleteItem.value = null
        viewModelScope.launch {
            libraryRepository.delete(target.id)
            if (playbackController is FakePlaybackController &&
                playbackController.status.value.mediaId == target.id
            ) {
                playbackController.statusState.value = PlaybackStatus()
            }
        }
    }

    private fun toggleMiniPlayerPlayPause() {
        val player = playbackController.player.value
        if (player != null) {
            if (playbackController.status.value.isPlaying || player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        } else if (playbackController is FakePlaybackController) {
            playbackController.statusState.update { current ->
                if (current.mediaId != null) {
                    current.copy(isPlaying = !current.isPlaying)
                } else {
                    current
                }
            }
        }
    }

    private fun buildUiState(
        items: List<AudioItem>,
        itemStates: Map<MediaId, ItemState>,
        playbackStatus: PlaybackStatus,
        importState: LibraryImportUiState,
        pendingDeleteItem: AudioItem?,
    ): LibraryUiState {
        val uiItems = items.map { audioItem ->
            val state = itemStates[audioItem.id]
            LibraryItemUiModel(
                item = audioItem,
                positionMs = state?.positionMs ?: 0L,
                speed = state?.speed ?: 1.0f,
            )
        }
        val activeMediaId = playbackStatus.mediaId
        val activeAudioItem = if (activeMediaId != null) {
            items.firstOrNull { it.id == activeMediaId }
        } else {
            null
        }
        val miniPlayer = if (activeMediaId != null && activeAudioItem != null) {
            MiniPlayerUiState(
                mediaId = activeMediaId,
                title = activeAudioItem.title,
                isPlaying = playbackStatus.isPlaying,
                durationMs = if (playbackStatus.durationMs > 0L) {
                    playbackStatus.durationMs
                } else {
                    activeAudioItem.durationMs
                },
            )
        } else {
            null
        }
        return LibraryUiState(
            items = uiItems,
            importState = importState,
            miniPlayer = miniPlayer,
            pendingDeleteItem = pendingDeleteItem,
        )
    }
}
