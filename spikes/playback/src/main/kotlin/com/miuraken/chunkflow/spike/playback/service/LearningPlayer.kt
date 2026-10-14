package com.miuraken.chunkflow.spike.playback.service

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.miuraken.chunkflow.spike.playback.SpikeLog

/**
 * 前 / 次ボタンをチャンク移動に割り当てる Player の試作（§7.2.3）。
 *
 * - `getState()` で `COMMAND_SEEK_TO_PREVIOUS` / `COMMAND_SEEK_TO_NEXT` を利用可能にする（単一の音声でも通知・イヤホンで使える）。
 * - `handleSeek()` の引数（`C.INDEX_UNSET` / `C.TIME_UNSET` で届くことがある）は使わず、現在の位置から移動先を求める。
 * - 「前」: チャンクの開始から 1 秒以上経っていれば現在のチャンクの頭へ、1 秒未満なら前のチャンクへ。
 */
@OptIn(UnstableApi::class)
class LearningPlayer(
    private val exo: ExoPlayer,
    private val chunkStartsMs: () -> List<Long>,
) : ForwardingSimpleBasePlayer(exo) {

    override fun getState(): State {
        val state = super.getState()
        val commands = state.availableCommands.buildUpon()
            .addAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_NEXT)
            .build()
        return state.buildUpon().setAvailableCommands(commands).build()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val starts = chunkStartsMs()
        val now = exo.currentPosition
        val target = when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> previousTarget(starts, now)
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> nextTarget(starts, now)
            else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        SpikeLog.i(
            "[learning] command=$seekCommand 引数=($mediaItemIndex, $positionMs) 現在=${now}ms → ${target}ms",
        )
        // 本体では、チャンク移動は ChunkScheduler のシークとして扱うか、ユーザーのシークとして扱うかを決める必要がある。
        // ここでは「ユーザーの操作」なので exo.seekTo を直接呼び、ChunkScheduler にはユーザーのシークとして見せる。
        exo.seekTo(target)
        return Futures.immediateVoidFuture()
    }

    private fun previousTarget(starts: List<Long>, now: Long): Long {
        if (starts.isEmpty()) return (now - SKIP_MS).coerceAtLeast(0)
        val current = starts.indexOfLast { it <= now }.coerceAtLeast(0)
        return if (now - starts[current] >= RESTART_THRESHOLD_MS || current == 0) starts[current] else starts[current - 1]
    }

    private fun nextTarget(starts: List<Long>, now: Long): Long {
        if (starts.isEmpty()) return now + SKIP_MS
        return starts.firstOrNull { it > now } ?: now
    }

    private companion object {
        const val RESTART_THRESHOLD_MS = 1_000L
        const val SKIP_MS = 10_000L
    }
}
