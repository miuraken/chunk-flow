package com.miuraken.chunkflow.core.playback

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LearningPlayerTest {

    @Test
    fun `getState_単一アイテムでも前後のシークコマンドが有効になりknownDurationMsが反映される`() {
        val innerPlayer = ControllableStubPlayer(
            initialPositionMs = 0L,
            reportedDurationUs = C.TIME_UNSET,
        )
        val learningPlayer = LearningPlayer(innerPlayer)
        learningPlayer.knownDurationMs = 180_000L

        assertTrue(learningPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_PREVIOUS))
        assertTrue(learningPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT))
        assertEquals(180_000L, learningPlayer.duration)
    }

    @Test
    fun `handleSeek_チャンクがないM1状態では前後コマンドがseekBackとseekForwardとして動作する`() {
        val innerPlayer = ControllableStubPlayer(
            initialPositionMs = 10_000L,
            reportedDurationUs = Util.msToUs(60_000L),
        )
        val learningPlayer = LearningPlayer(innerPlayer) { emptyList() }
        learningPlayer.seekBackIncrementMs = 3_000L
        learningPlayer.seekForwardIncrementMs = 5_000L

        learningPlayer.seekToNext()
        assertEquals(15_000L, innerPlayer.currentPosition)

        learningPlayer.seekToPrevious()
        assertEquals(12_000L, innerPlayer.currentPosition)
    }

    @Test
    fun `handleSeek_チャンクがある状態では前後のチャンク境界へ移動し1秒閾値に従う`() {
        val innerPlayer = ControllableStubPlayer(
            initialPositionMs = 5_500L,
            reportedDurationUs = Util.msToUs(60_000L),
        )
        val learningPlayer = LearningPlayer(innerPlayer)
        learningPlayer.chunks = listOf(
            Chunk(ChunkId(1L), 0L, 5_000L, ChunkOrigin.AUTO),
            Chunk(ChunkId(2L), 5_000L, 12_000L, ChunkOrigin.AUTO),
            Chunk(ChunkId(3L), 12_000L, 20_000L, ChunkOrigin.AUTO),
            Chunk(ChunkId(4L), 20_000L, 30_000L, ChunkOrigin.AUTO),
        )

        // チャンク開始 (5_000ms) から 500ms (< 1000ms) なので前のチャンク (0ms) へ移動
        learningPlayer.seekToPrevious()
        assertEquals(0L, innerPlayer.currentPosition)

        // チャンク開始 (5_000ms) から 1_200ms (>= 1000ms) なので現在チャンクの頭 (5_000ms) へ移動
        innerPlayer.seekTo(6_200L)
        learningPlayer.seekToPrevious()
        assertEquals(5_000L, innerPlayer.currentPosition)

        // 次のチャンク (12_000ms) へ移動
        learningPlayer.seekToNext()
        assertEquals(12_000L, innerPlayer.currentPosition)
    }

    private class ControllableStubPlayer(
        initialPositionMs: Long,
        private val reportedDurationUs: Long,
    ) : SimpleBasePlayer(Looper.getMainLooper()) {

        private var posMs: Long = initialPositionMs

        override fun getState(): State {
            val commands = Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                )
                .build()
            val itemData = MediaItemData.Builder("item-1")
                .setDurationUs(reportedDurationUs)
                .build()
            return State.Builder()
                .setAvailableCommands(commands)
                .setPlaylist(listOf(itemData))
                .setCurrentMediaItemIndex(0)
                .setContentPositionMs { posMs }
                .setPlaybackState(Player.STATE_READY)
                .build()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> {
            if (positionMs != C.TIME_UNSET) {
                posMs = positionMs.coerceAtLeast(0L)
                invalidateState()
            }
            return Futures.immediateVoidFuture()
        }
    }
}
