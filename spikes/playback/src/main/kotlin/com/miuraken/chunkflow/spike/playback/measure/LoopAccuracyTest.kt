package com.miuraken.chunkflow.spike.playback.measure

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.PlayerMessage
import com.miuraken.chunkflow.spike.playback.SpikeLog
import com.miuraken.chunkflow.spike.playback.scheduler.SpikeChunkScheduler
import java.io.File
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * ループの行き過ぎの計測（S2 の 1）。
 *
 * loop.m4a の A〜B を、速度 0.5 / 1.0 / 2.0 倍と 3 つの方式（予約し直す A-B、使い回す A-B、B で一時停止）で
 * 繰り返し、境界ごとに次を記録する。
 * - 配信: PlayerMessage がアプリのスレッドに届いたときの `currentPosition` − B（メディア時刻）
 * - 停止: その後の AudioSink の flush（シーク）/ pause の時点で、ExoPlayer が推定する「鳴っている位置」− B。
 *   これが行き過ぎ（メディア時刻）で、速度で割ると実時間になる。
 * - 再開まで: flush から、次の区間の最初のバッファが AudioSink に届くまでの実時間（音が途切れる時間の下限）。
 *
 * 「鳴っている位置」は AudioTrack の再生位置とレイテンシの推定に基づく。スピーカーから実際に出た音の
 * 行き過ぎは、実機で loop.m4a の目盛り（B の後の 10ms ごとのバースト）を録音して数える（実機待ち）。
 */
@OptIn(UnstableApi::class)
class LoopAccuracyTest(private val context: Context, private val manifest: TestManifest) {
    /** 1 回の境界の記録。 */
    data class Row(
        val mode: String,
        val speed: Float,
        val iteration: Int,
        val deliveryOvershootMs: Long,
        val playbackThreadOvershootMs: Double?,
        val sinkOvershootMediaMs: Double?,
        val sinkOvershootWallMs: Double?,
        val deliveryToStopMs: Double?,
        val restartGapMs: Double?,
    )

    private enum class LoopMode(val label: String) {
        REARM("A-B 予約し直し"),
        PERSISTENT("A-B deleteAfterDelivery=false"),
        PAUSE("B で一時停止"),
    }

    /** すべての速度と方式を測る。 */
    suspend fun run(): List<Row> {
        val rows = ArrayList<Row>()
        for (dynamic in listOf(true, false)) {
            for (speed in SPEEDS) {
                for (mode in LoopMode.entries) {
                    rows += measure(mode, speed, dynamic)
                }
            }
        }
        rows += measureUserSeekDistinction()
        for (speed in SPEEDS) measureClipping(speed)
        SpikeLog.writeResult(context, "loop", "csv", toCsv(rows))
        summarize(rows).forEach(SpikeLog::i)
        return rows
    }

    private suspend fun measure(mode: LoopMode, speed: Float, dynamic: Boolean): List<Row> {
        val label = mode.label + if (dynamic) "" else "（dynamicScheduling=false）"
        SpikeLog.i("[loop] $label x$speed")
        val a = manifest.loopAMs
        val b = manifest.loopBMs
        val probe = AudioProbe()
        val player = SpikePlayers.build(context, probe, 0, handleAudioFocus = false, dynamicScheduling = dynamic)
        val deliveries = ArrayList<SpikeChunkScheduler.Delivery>()
        val playbackThreadHits = Collections.synchronizedList(ArrayList<Pair<Long, Long>>())
        var diag: PlayerMessage? = null
        val scheduler = SpikeChunkScheduler(
            player,
            object : SpikeChunkScheduler.Callback {
                override fun onDelivered(delivery: SpikeChunkScheduler.Delivery) {
                    deliveries += delivery
                }
            },
        )
        try {
            probe.armOnNextFlush()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(manifest.dir, manifest.loopFile))))
            player.setPlaybackSpeed(speed)
            player.seekTo(a)
            player.prepare()
            if (!SpikePlayers.awaitReady(player)) {
                SpikeLog.i("  準備できない")
                return emptyList()
            }
            scheduler.setMode(
                when (mode) {
                    LoopMode.REARM -> SpikeChunkScheduler.Mode.AbLoop(a, b, persistent = false)
                    LoopMode.PERSISTENT -> SpikeChunkScheduler.Mode.AbLoop(a, b, persistent = true)
                    LoopMode.PAUSE -> SpikeChunkScheduler.Mode.PauseAt(b)
                },
            )
            // 診断用: 同じ位置に、再生スレッドで配信されるメッセージを置き、ExoPlayer が境界を検出した時点の
            // AudioSink の位置を記録する（アプリのスレッドへの受け渡しの遅れと切り分けるため）
            diag = player.createMessage { _, _ ->
                playbackThreadHits += SystemClock.elapsedRealtimeNanos() to probe.sinkPositionUs()
            }
                .setPosition(b)
                .setLooper(player.playbackLooper)
                .setDeleteAfterDelivery(false)
                .send()
            probe.drainEvents()
            player.play()
            val deadline = SystemClock.elapsedRealtime() + ((b - a) / speed * (ITERATIONS + 2)).toLong() + 5_000
            var handled = 0
            while (deliveries.size < ITERATIONS && SystemClock.elapsedRealtime() < deadline) {
                delay(20)
                if (mode == LoopMode.PAUSE && deliveries.size > handled) {
                    handled = deliveries.size
                    delay(PAUSE_BEFORE_RESTART_MS)
                    scheduler.seekInternal(a)
                    scheduler.setMode(SpikeChunkScheduler.Mode.PauseAt(b))
                    player.play()
                }
            }
            delay(300)
            SpikeLog.i(
                "  配信 ${deliveries.size} 回、自分のシーク ${scheduler.internalSeekCount} 回、" +
                    "ユーザーのシーク ${scheduler.userSeekCount} 回、seekTo の中で届いた discontinuity ${scheduler.discontinuityInsideSeekCall} 回",
            )
            return toRows(mode, label, speed, deliveries, probe.drainEvents(), synchronized(playbackThreadHits) { playbackThreadHits.toList() }, b)
        } finally {
            diag?.cancel()
            scheduler.release()
            player.release()
        }
    }

    private fun toRows(
        mode: LoopMode,
        label: String,
        speed: Float,
        deliveries: List<SpikeChunkScheduler.Delivery>,
        events: List<AudioProbe.Event>,
        hits: List<Pair<Long, Long>>,
        b: Long,
    ): List<Row> = deliveries.mapIndexed { i, d ->
        val hit = hits.lastOrNull { it.first <= d.deliveredAtNs && d.deliveredAtNs - it.first < 1_000_000_000L }
        val stopType = if (mode == LoopMode.PAUSE) AudioProbe.Event.Type.PAUSE else AudioProbe.Event.Type.FLUSH
        val stop = events.firstOrNull { it.type == stopType && it.atNs >= d.deliveredAtNs }
        val restart = stop?.let { s ->
            events.firstOrNull { it.type == AudioProbe.Event.Type.FIRST_BUFFER && it.atNs > s.atNs }
        }
        val sinkMedia = stop?.takeIf { it.positionUs > 0 }?.let { it.positionUs / 1000.0 - b }
        val row = Row(
            mode = label,
            speed = speed,
            iteration = i,
            deliveryOvershootMs = d.positionAtDeliveryMs - b,
            playbackThreadOvershootMs = hit?.second?.takeIf { it > 0 }?.let { it / 1000.0 - b },
            sinkOvershootMediaMs = sinkMedia,
            sinkOvershootWallMs = sinkMedia?.let { it / speed },
            deliveryToStopMs = stop?.let { (it.atNs - d.deliveredAtNs) / 1e6 },
            restartGapMs = if (mode == LoopMode.PAUSE) null else restart?.let { r -> stop.let { (r.atNs - it.atNs) / 1e6 } },
        )
        SpikeLog.i(
            "  #$i 再生スレッドで検出 ${row.playbackThreadOvershootMs?.let(::fmt)}ms / 配信時 ${row.deliveryOvershootMs}ms / 停止時 ${row.sinkOvershootMediaMs?.let(::fmt)}ms" +
                "（実時間 ${row.sinkOvershootWallMs?.let(::fmt)}ms）/ 配信→停止 ${row.deliveryToStopMs?.let(::fmt)}ms" +
                " / 再開まで ${row.restartGapMs?.let(::fmt)}ms",
        )
        row
    }

    /** ユーザーのシーク（ChunkScheduler を通さない seekTo）を区別でき、使い回す予約が生き残るかを確かめる。 */
    private suspend fun measureUserSeekDistinction(): List<Row> {
        SpikeLog.i("[loop] ユーザーのシークの区別（x1.0、使い回す A-B）")
        val a = manifest.loopAMs
        val b = manifest.loopBMs
        val player = SpikePlayers.build(context, null, 0, handleAudioFocus = false)
        var deliveries = 0
        var userSeeks = 0
        val scheduler = SpikeChunkScheduler(
            player,
            object : SpikeChunkScheduler.Callback {
                override fun onDelivered(delivery: SpikeChunkScheduler.Delivery) {
                    deliveries++
                }

                override fun onUserSeek(positionMs: Long) {
                    userSeeks++
                }
            },
        )
        try {
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(manifest.dir, manifest.loopFile))))
            player.seekTo(a)
            player.prepare()
            SpikePlayers.awaitReady(player)
            scheduler.setMode(SpikeChunkScheduler.Mode.AbLoop(a, b, persistent = true))
            player.play()
            delay(1_000)
            player.seekTo(b - 500) // ユーザーのシーク
            delay(1_000) // B を通過する
            player.seekTo(b + 2_000) // B の後ろへのユーザーのシーク（ループの外に出る）
            delay(1_000)
            player.seekTo(a + 1_200) // 戻ると再び B で戻されるはず
            delay(1_500)
            SpikeLog.i(
                "  配信 $deliveries 回（期待 2）、ユーザーのシーク $userSeeks 回（期待 4: 準備前の seekTo(A) を含む）、" +
                    "自分のシーク ${scheduler.internalSeekCount} 回（期待 2）",
            )
        } finally {
            scheduler.release()
            player.release()
        }
        return emptyList()
    }

    /**
     * 比較: A〜B を `ClippingConfiguration` で切り出し、`REPEAT_MODE_ONE` で繰り返す方式。
     * B の後ろの目盛り（振幅 0.9）が AudioSink に渡った数を数える（1 つ = 10ms の行き過ぎ）。
     * この方式ではシークも flush も起きないので、AudioSink に渡った PCM はすべて再生される。
     */
    private suspend fun measureClipping(speed: Float) {
        SpikeLog.i("[loop] 比較: ClippingConfiguration + REPEAT_MODE_ONE x$speed")
        val a = manifest.loopAMs
        val b = manifest.loopBMs
        val probe = AudioProbe()
        val player = SpikePlayers.build(context, probe, 0, handleAudioFocus = false)
        try {
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(File(manifest.dir, manifest.loopFile)))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setStartPositionMs(a).setEndPositionMs(b).build(),
                )
                .build()
            probe.keepSingleSegment = true
            probe.armOnNextFlush()
            player.setMediaItem(item)
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.setPlaybackSpeed(speed)
            player.prepare()
            SpikePlayers.awaitReady(player)
            var transitions = 0
            player.addListener(
                object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) transitions++
                    }
                },
            )
            player.play()
            delay(((b - a) / speed * CLIP_LOOPS).toLong() + 500)
            player.pause()
            val seg = probe.currentSegment()
            val pcm = seg?.let(probe::snapshot) ?: ShortArray(0)
            val sr = probe.sampleRate.takeIf { it > 0 } ?: manifest.sampleRate
            var ticks = 0
            var i = 0
            while (i < pcm.size) {
                if (kotlin.math.abs(pcm[i].toInt()) >= manifest.clickThreshold) {
                    ticks++
                    i += sr * 5 / 1000
                } else {
                    i++
                }
            }
            SpikeLog.i(
                "  繰り返し $transitions 回、AudioSink に渡った PCM ${fmt(pcm.size * 1000.0 / sr)}ms、" +
                    "B の後ろの目盛り $ticks 個（0 なら行き過ぎなし）",
            )
        } finally {
            player.release()
        }
    }

    private fun toCsv(rows: List<Row>): String = buildString {
        appendLine("mode,speed,iteration,playbackThreadOvershootMs,deliveryOvershootMs,sinkOvershootMediaMs,sinkOvershootWallMs,deliveryToStopMs,restartGapMs")
        rows.forEach { r ->
            appendLine(
                listOf(
                    r.mode, r.speed, r.iteration, r.playbackThreadOvershootMs?.let(::fmt) ?: "", r.deliveryOvershootMs, r.sinkOvershootMediaMs?.let(::fmt) ?: "",
                    r.sinkOvershootWallMs?.let(::fmt) ?: "", r.deliveryToStopMs?.let(::fmt) ?: "",
                    r.restartGapMs?.let(::fmt) ?: "",
                ).joinToString(","),
            )
        }
    }

    private fun summarize(rows: List<Row>): List<String> = buildList {
        add("[loop] まとめ（1 回目を除く。行き過ぎ = 停止時の推定位置 − B）")
        rows.groupBy { it.mode to it.speed }.forEach { (key, group) ->
            val g = group.drop(1)
            val media = g.mapNotNull { it.sinkOvershootMediaMs }
            val wall = g.mapNotNull { it.sinkOvershootWallMs }
            val gap = g.mapNotNull { it.restartGapMs }
            add(
                "  ${key.first} x${key.second}: n=${g.size} 行き過ぎ(メディア) 平均=${avg(media)} 最大=${media.maxOrNull()?.let(::fmt)}" +
                    " / 実時間 平均=${avg(wall)} 最大=${wall.maxOrNull()?.let(::fmt)}" +
                    " / 再生スレッドで検出 平均=${avg(g.mapNotNull { it.playbackThreadOvershootMs })}" +
                    " / 配信時 平均=${avg(g.map { it.deliveryOvershootMs.toDouble() })} / 再開まで 平均=${avg(gap)}",
            )
        }
    }

    private companion object {
        val SPEEDS = listOf(0.5f, 1.0f, 2.0f)
        const val ITERATIONS = 8
        const val PAUSE_BEFORE_RESTART_MS = 300L
        const val CLIP_LOOPS = 5

        fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)

        fun avg(v: List<Double>) = if (v.isEmpty()) "-" else fmt(v.average())
    }
}
