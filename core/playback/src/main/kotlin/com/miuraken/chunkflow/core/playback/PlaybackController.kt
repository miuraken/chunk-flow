package com.miuraken.chunkflow.core.playback

import androidx.media3.common.Player
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PracticeSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * アプリの UI から再生を操作するための窓口（§6.3、§7.2.5）。
 *
 * ViewModel はこのインターフェースだけに依存する（テストでは core:testing の Fake を注入する）。
 * 再生 / 一時停止 / シーク / 前後のチャンク / スキップは [Player] の標準 API を使う
 * （`seekToPrevious` / `seekToNext` は LearningPlayer がチャンク移動に再割り当てする。§7.2.3）。
 */
interface PlaybackController {
    /** Media3 の Compose 部品（PlayPauseButton など）に渡す [Player]。実体は MediaController。接続前は `null`。 */
    val player: StateFlow<Player?>

    /** 低頻度で変わる再生の状態。 */
    val status: StateFlow<PlaybackStatus>

    /** 学習モードの設定。 */
    val practice: StateFlow<PracticeSettings>

    /** 高頻度で変わる再生位置（MediaController の推定値を読むだけで IPC は発生しない）。描画フェーズでのみ読む。 */
    val position: PositionProvider

    /** 教材を開き、前回の位置・速度で再生の準備をする。 */
    suspend fun open(mediaId: MediaId)

    /** 指定したチャンクの頭へ移動する。 */
    fun seekToChunk(chunkId: ChunkId)

    /** 再生速度を変える（ピッチは保つ。0.5〜2.0 倍）。 */
    fun setSpeed(speed: Float)

    /** 学習モードの設定を [transform] で更新する。 */
    fun updatePractice(transform: (PracticeSettings) -> PracticeSettings)
}

/** 高頻度で変わる再生位置。描画フェーズでのみ読む（UiState には入れない。§6.2）。 */
fun interface PositionProvider {
    /** 現在の再生位置（ミリ秒）。 */
    fun currentPositionMs(): Long
}

/**
 * 低頻度で変わる再生の状態。
 *
 * `android.media.session.PlaybackState` や `SimpleBasePlayer.State` と紛れないよう、Status と呼ぶ。
 *
 * @property mediaId 開いている教材。開いていなければ `null`。
 * @property isPlaying 再生中かどうか。
 * @property durationMs 教材の長さ（ミリ秒）。
 * @property speed 再生速度。
 * @property currentChunkId 現在のチャンク。ChunkScheduler がチャンク境界で更新する。
 * @property autoPauseEndsAtMs 自動ポーズが終わる時刻（`elapsedRealtime` 基準）。自動ポーズ中のみ。
 * @property error 再生のエラー。
 */
data class PlaybackStatus(
    val mediaId: MediaId? = null,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val currentChunkId: ChunkId? = null,
    val autoPauseEndsAtMs: Long? = null,
    val error: PlaybackError? = null,
)

/** 再生のエラーの種別。 */
enum class PlaybackError {
    /** 音声ファイルがない（バックアップからの復元後など）。 */
    SOURCE_MISSING,

    /** デコーダのエラー。 */
    DECODER,

    /** その他のエラー。 */
    UNKNOWN,
}
