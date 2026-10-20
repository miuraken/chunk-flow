package com.miuraken.chunkflow.core.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.UserSettings

/**
 * 前 / 次ボタン（通知・ロック画面・イヤホン・アプリ内 UI）をチャンク移動またはスキップに再割り当てし、
 * ADR 0006 で `C.TIME_UNSET` になる MP3 のメディア長を既知の `durationMs` で補う [Player]（§7.2.3、ADR 0006、ADR 0007）。
 *
 * - 単一の音声でも `COMMAND_SEEK_TO_PREVIOUS` / `COMMAND_SEEK_TO_NEXT` を利用可能コマンドとして公開する。
 * - チャンクがまだない状態（M1）では、前 / 次コマンドを `seekBack()` / `seekForward()` として扱う。
 * - チャンクがある状態（M2 以降）では、現在位置から前後のチャンク境界へシークする（開始から 1 秒以上経過していれば現在チャンクの頭、1 秒未満なら前のチャンクへ戻る）。
 */
@OptIn(UnstableApi::class)
class LearningPlayer(
    player: Player,
    private val selectedChunkIdsProvider: () -> Set<ChunkId> = { emptySet() },
    private val chunkStartsMsProvider: () -> List<Long> = { emptyList() },
) : ForwardingSimpleBasePlayer(player) {

    /**
     * 現在有効なチャンクの一覧（M2 以降）。
     * 空でない場合は [chunkStartsMsProvider] より優先して前 / 次チャンク移動に使用される。
     */
    var chunks: List<Chunk> = emptyList()
        set(value) {
            field = value.sortedBy { it.startMs }
        }

    /**
     * ユーザ（UI または MediaSession）から再生・一時停止コマンドが発行されたときに呼ばれるコールバック。
     * 自動ポーズ中の手動操作によるタイマー解除（§7.2.4）および非選択チャンク位置からの開始時シーク先行実行に使用する。
     */
    var onUserPlayWhenReadyCommand: ((playWhenReady: Boolean) -> Unit)? = null

    /**
     * DB（`AudioItem.durationMs`）から判明している教材の長さ（ミリ秒）。
     * ADR 0006 の [IndexSeekDataSource] により内側の Player の duration が [C.TIME_UNSET] になる場合に反映する。
     */
    var knownDurationMs: Long? = null
        set(value) {
            if (field != value) {
                field = value
                invalidateState()
            }
        }

    /** 巻き戻しのミリ秒数（[UserSettings.seekBackMs] を反映）。 */
    var seekBackIncrementMs: Long = UserSettings.DEFAULT_SEEK_BACK_MS
        set(value) {
            val coerced = value.coerceAtLeast(1L)
            if (field != coerced) {
                field = coerced
                invalidateState()
            }
        }

    /** 早送りのミリ秒数（[UserSettings.seekForwardMs] を反映）。 */
    var seekForwardIncrementMs: Long = UserSettings.DEFAULT_SEEK_FORWARD_MS
        set(value) {
            val coerced = value.coerceAtLeast(1L)
            if (field != coerced) {
                field = coerced
                invalidateState()
            }
        }

    override fun getState(): State {
        val baseState = super.getState()
        val commands = baseState.availableCommands.buildUpon()
            .addAll(
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_BACK,
                Player.COMMAND_SEEK_FORWARD,
            )
            .build()

        val stateBuilder = baseState.buildUpon()
            .setAvailableCommands(commands)
            .setSeekBackIncrementMs(seekBackIncrementMs)
            .setSeekForwardIncrementMs(seekForwardIncrementMs)

        val overrideDurationMs = knownDurationMs
        if (overrideDurationMs != null && overrideDurationMs > 0L && baseState.playlist.isNotEmpty()) {
            val durationUs = Util.msToUs(overrideDurationMs)
            val updatedPlaylist = baseState.playlist.map { itemData ->
                if (itemData.durationUs == C.TIME_UNSET || itemData.durationUs <= 0L) {
                    val builder = itemData.buildUpon().setDurationUs(durationUs)
                    if (itemData.periods.size == 1) {
                        builder.setPeriods(
                            listOf(
                                itemData.periods[0].buildUpon().setDurationUs(durationUs).build(),
                            ),
                        )
                    }
                    builder.build()
                } else {
                    itemData
                }
            }
            stateBuilder.setPlaylist(updatedPlaylist)
        }

        return stateBuilder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        onUserPlayWhenReadyCommand?.invoke(playWhenReady)
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val starts = resolveChunkStartsMs()
        val now = player.currentPosition.coerceAtLeast(0L)

        when (seekCommand) {
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            -> {
                if (starts.isEmpty()) {
                    seekByOffset(-seekBackIncrementMs)
                } else {
                    val target = computePreviousChunkTarget(starts, now)
                    player.seekTo(target)
                }
                return Futures.immediateVoidFuture()
            }

            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            -> {
                if (starts.isEmpty()) {
                    seekByOffset(seekForwardIncrementMs)
                } else {
                    val target = starts.firstOrNull { it > now }
                    if (target != null) {
                        player.seekTo(target)
                    }
                }
                return Futures.immediateVoidFuture()
            }

            Player.COMMAND_SEEK_BACK -> {
                seekByOffset(-seekBackIncrementMs)
                return Futures.immediateVoidFuture()
            }

            Player.COMMAND_SEEK_FORWARD -> {
                seekByOffset(seekForwardIncrementMs)
                return Futures.immediateVoidFuture()
            }

            else -> return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
    }

    private fun resolveChunkStartsMs(): List<Long> = if (chunks.isNotEmpty()) {
        val selectedIds = selectedChunkIdsProvider()
        val selectedChunks = if (selectedIds.isNotEmpty()) {
            chunks.filter { it.id in selectedIds && !it.isSkipped }
        } else {
            emptyList()
        }
        val activeChunks = selectedChunks.ifEmpty {
            chunks.filterNot { it.isSkipped }.ifEmpty { chunks }
        }
        activeChunks.map { it.startMs }
    } else {
        chunkStartsMsProvider()
    }

    private fun seekByOffset(deltaMs: Long) {
        val now = player.currentPosition.coerceAtLeast(0L)
        val rawTarget = (now + deltaMs).coerceAtLeast(0L)
        val maxDuration = knownDurationMs?.takeIf { it > 0L }
            ?: player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
        val target = if (maxDuration != null) rawTarget.coerceAtMost(maxDuration) else rawTarget
        player.seekTo(target)
    }

    companion object {
        /** チャンク開始からこの時間以上経過していれば、現在のチャンクの頭に戻る（§7.2.3）。 */
        const val RESTART_THRESHOLD_MS: Long = 1_000L

        internal fun computePreviousChunkTarget(starts: List<Long>, nowMs: Long): Long {
            val currentIndex = starts.indexOfLast { it <= nowMs }.coerceAtLeast(0)
            val currentStart = starts[currentIndex]
            return if (nowMs - currentStart >= RESTART_THRESHOLD_MS || currentIndex == 0) {
                currentStart
            } else {
                starts[currentIndex - 1]
            }
        }
    }
}
