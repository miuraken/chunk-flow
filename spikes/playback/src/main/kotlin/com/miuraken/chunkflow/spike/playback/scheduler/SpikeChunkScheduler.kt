package com.miuraken.chunkflow.spike.playback.scheduler

import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage

/**
 * ChunkScheduler の最小の試作（§7.2.4）。本体の実装の下書きで、テストや例外処理は省いている。
 *
 * - 次の境界を `PlayerMessage` で常に 1 件だけ予約する（予約し直す前に必ず `cancel()`）。
 * - 自分の `seekTo()` とユーザーのシークを、`onPositionDiscontinuity` で区別する
 *   （自分のシークの前に件数を数えておき、届いた SEEK の分だけ減らす）。
 * - A-B は「到達のたびに予約し直す」方式と、`setDeleteAfterDelivery(false)` の 1 件を使い回す方式の両方を試せる。
 *
 * ExoPlayer の API（`createMessage`）を使うので、LearningPlayer ではなく内側の ExoPlayer を直接持つ。
 * すべてアプリのスレッド（メインスレッド）から呼ぶ。
 */
@OptIn(UnstableApi::class)
class SpikeChunkScheduler(private val exo: ExoPlayer, private val callback: Callback) : Player.Listener {

    /** 境界の処理のモード。 */
    sealed interface Mode {
        /** 何もしない。 */
        data object Off : Mode

        /** A-B リピート。[persistent] なら `setDeleteAfterDelivery(false)` の 1 件を使い回す。 */
        data class AbLoop(val aMs: Long, val bMs: Long, val persistent: Boolean) : Mode

        /** [bMs] で一時停止する（再開は呼び出し側）。ループの計測用。 */
        data class PauseAt(val bMs: Long) : Mode

        /** 各チャンクの終端（[endsMs] は昇順）で一時停止する（再開は呼び出し側）。自動ポーズの試作。 */
        data class PauseAtChunkEnds(val endsMs: List<Long>) : Mode
    }

    /** 境界に達したときの記録。[positionAtDeliveryMs] は配信時にアプリのスレッドで読んだ再生位置。 */
    data class Delivery(val boundaryMs: Long, val deliveredAtNs: Long, val positionAtDeliveryMs: Long, val action: String)

    /** 境界の通知を受ける。 */
    interface Callback {
        /** 境界に達して、モードの動作（シーク・一時停止）を済ませた直後に呼ばれる。 */
        fun onDelivered(delivery: Delivery)

        /** ユーザー（ChunkScheduler 以外）のシークを検出したときに呼ばれる。 */
        fun onUserSeek(positionMs: Long) {}
    }

    private var mode: Mode = Mode.Off
    private var pending: PlayerMessage? = null
    private var pendingInternalSeeks = 0

    /** 自分のシークの件数（診断用）。 */
    var internalSeekCount = 0
        private set

    /** ユーザーのシークの件数（診断用）。 */
    var userSeekCount = 0
        private set

    /** `seekTo()` の呼び出しの中で `onPositionDiscontinuity` が届いた回数（同期的に届くかの確認用）。 */
    var discontinuityInsideSeekCall = 0
        private set

    private var insideSeekCall = false

    init {
        exo.addListener(this)
    }

    /** モードを変えて、次の境界を予約し直す。 */
    fun setMode(newMode: Mode) {
        mode = newMode
        reschedule()
    }

    /** 予約を取り消し、リスナーを外す。 */
    fun release() {
        cancelPending()
        exo.removeListener(this)
    }

    /** ChunkScheduler 自身のシーク。ユーザーのシークと区別するため、必ずここを通す。 */
    fun seekInternal(positionMs: Long) {
        pendingInternalSeeks++
        insideSeekCall = true
        try {
            exo.seekTo(positionMs)
        } finally {
            insideSeekCall = false
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        if (insideSeekCall) discontinuityInsideSeekCall++
        if (pendingInternalSeeks > 0) {
            pendingInternalSeeks--
            internalSeekCount++
        } else {
            userSeekCount++
            // 本体ではここでリピート回数をリセットする。ユーザーのシークは常に優先し、次の境界から適用する。
            reschedule()
            callback.onUserSeek(newPosition.positionMs)
        }
    }

    private fun reschedule() {
        when (val m = mode) {
            Mode.Off -> cancelPending()

            is Mode.AbLoop -> schedule(m.bMs, persistent = m.persistent)

            is Mode.PauseAt -> schedule(m.bMs, persistent = false)

            is Mode.PauseAtChunkEnds -> {
                val now = exo.currentPosition
                val next = m.endsMs.firstOrNull { it > now }
                if (next == null) cancelPending() else schedule(next, persistent = false)
            }
        }
    }

    private fun schedule(positionMs: Long, persistent: Boolean) {
        // 使い回す 1 件がすでに同じ位置に予約されていれば、そのままにする
        val current = pending
        if (persistent && current != null && !current.isCanceled && current.positionMs == positionMs) return
        cancelPending()
        pending = exo.createMessage { _, payload -> onBoundary(payload as Long) }
            .setPayload(positionMs)
            .setPosition(positionMs)
            .setLooper(Looper.getMainLooper())
            .setDeleteAfterDelivery(!persistent)
            .send()
    }

    private fun cancelPending() {
        pending?.cancel()
        pending = null
    }

    private fun onBoundary(boundaryMs: Long) {
        val atNs = SystemClock.elapsedRealtimeNanos()
        val position = exo.currentPosition
        val action = when (val m = mode) {
            Mode.Off -> "none"

            is Mode.AbLoop -> {
                seekInternal(m.aMs)
                if (!m.persistent) schedule(m.bMs, persistent = false)
                "seek→A"
            }

            is Mode.PauseAt -> {
                exo.pause()
                "pause"
            }

            is Mode.PauseAtChunkEnds -> {
                exo.pause()
                val next = m.endsMs.firstOrNull { it > boundaryMs }
                if (next == null) cancelPending() else schedule(next, persistent = false)
                "pause"
            }
        }
        callback.onDelivered(Delivery(boundaryMs, atNs, position, action))
    }
}
