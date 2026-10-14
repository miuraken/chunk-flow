package com.miuraken.chunkflow.core.testing.fake

import androidx.media3.common.Player
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.playback.PlaybackController
import com.miuraken.chunkflow.core.playback.PlaybackStatus
import com.miuraken.chunkflow.core.playback.PositionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * テストとプレビュー用の [PositionProvider]。[positionMs] を書き換えて位置を進める。
 */
class FakePositionProvider(var positionMs: Long = 0L) : PositionProvider {
    override fun currentPositionMs(): Long = positionMs
}

/**
 * テストとプレビュー用の [PlaybackController]。
 *
 * 実際の再生はしない。呼ばれた操作を記録し、状態（[status]、[practice]）を素直に更新する。
 * [player] は常に `null`（Media3 の Compose 部品は接続前の表示になる）。
 * 状態を直接変えたいときは [statusState] / [practiceState] を書き換える。
 */
class FakePlaybackController(
    initialStatus: PlaybackStatus = PlaybackStatus(),
    initialPractice: PracticeSettings = PracticeSettings(),
    override val position: FakePositionProvider = FakePositionProvider(),
) : PlaybackController {
    /** [player] の中身。テストから差し替えられる。 */
    val playerState: MutableStateFlow<Player?> = MutableStateFlow(null)

    /** [status] の中身。テストから差し替えられる。 */
    val statusState: MutableStateFlow<PlaybackStatus> = MutableStateFlow(initialStatus)

    /** [practice] の中身。テストから差し替えられる。 */
    val practiceState: MutableStateFlow<PracticeSettings> = MutableStateFlow(initialPractice)

    /** [open] に渡された教材（呼ばれた順）。 */
    val openedMediaIds: MutableList<MediaId> = mutableListOf()

    /** [seekToChunk] に渡されたチャンク（呼ばれた順）。 */
    val seekedChunkIds: MutableList<ChunkId> = mutableListOf()

    override val player: StateFlow<Player?> = playerState
    override val status: StateFlow<PlaybackStatus> = statusState
    override val practice: StateFlow<PracticeSettings> = practiceState

    override suspend fun open(mediaId: MediaId) {
        openedMediaIds += mediaId
        statusState.update { it.copy(mediaId = mediaId, isPlaying = false, currentChunkId = null, error = null) }
    }

    override fun seekToChunk(chunkId: ChunkId) {
        seekedChunkIds += chunkId
        statusState.update { it.copy(currentChunkId = chunkId) }
    }

    override fun setSpeed(speed: Float) {
        statusState.update { it.copy(speed = speed) }
    }

    override fun updatePractice(transform: (PracticeSettings) -> PracticeSettings) {
        practiceState.update(transform)
    }
}
