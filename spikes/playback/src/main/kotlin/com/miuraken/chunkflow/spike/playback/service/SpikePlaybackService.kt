package com.miuraken.chunkflow.spike.playback.service

import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.miuraken.chunkflow.spike.playback.SpikeLog
import com.miuraken.chunkflow.spike.playback.measure.SpikePlayers
import com.miuraken.chunkflow.spike.playback.measure.TestManifest
import com.miuraken.chunkflow.spike.playback.scheduler.SpikeChunkScheduler
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 自動ポーズからの再開と、前 / 次ボタンの再割り当てを試す MediaSessionService（S2 の 3）。
 *
 * 構成は §7.2.1 の縮小版: MediaSession → [LearningPlayer] → ExoPlayer、境界は [SpikeChunkScheduler]。
 * チャンクは aac.m4a のクリックの区間（クリック k の 250ms 前から、クリック k+1 の 250ms 前まで）。
 *
 * 自動ポーズの計測: チャンクの終端で一時停止し、`Handler.postDelayed` で N 秒後に再開する。
 * 再開の遅れ（実時間）、ポーズ中に CPU が眠っていた時間（elapsedRealtime と uptimeMillis の進み方の差）、
 * 画面の状態、フォアグラウンドの状態を記録する。`PARTIAL_WAKE_LOCK` を持つ / 持たないを切り替えられる。
 */
@OptIn(UnstableApi::class)
class SpikePlaybackService : MediaSessionService() {

    /** 1 回の自動ポーズの記録。 */
    data class PauseRow(
        val cycle: Int,
        val wakeLock: Boolean,
        val pauseMs: Long,
        val lateMs: Long,
        val sleptMs: Long,
        val interactiveAtPause: Boolean,
        val interactiveAtResume: Boolean,
        val idleAtResume: Boolean,
        val foregroundTypeAtResume: Int,
    )

    private lateinit var exo: ExoPlayer
    private lateinit var session: MediaSession
    private lateinit var scheduler: SpikeChunkScheduler
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var power: PowerManager
    private val handler = Handler(Looper.getMainLooper())

    private var chunkStarts: List<Long> = emptyList()
    private var chunkEnds: List<Long> = emptyList()

    private var test: AutoPauseTest? = null

    private class AutoPauseTest(val pauseMs: Long, val cycles: Int, val useWakeLock: Boolean) {
        val rows = ArrayList<PauseRow>()
        var pausedAtElapsed = 0L
        var pausedAtUptime = 0L
        var interactiveAtPause = true
        var resumeTask: Runnable? = null
        var resumingByTimer = false
    }

    override fun onCreate() {
        super.onCreate()
        power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ChunkFlowSpike:autoPause").apply {
            setReferenceCounted(false)
        }
        // ループ計測で、dynamicScheduling=true（既定）では境界の検出が数十〜数百 ms 遅れたため false にする
        exo = SpikePlayers.build(this, null, 0, handleAudioFocus = true, dynamicScheduling = false).apply {
            setWakeMode(C.WAKE_MODE_LOCAL)
            setHandleAudioBecomingNoisy(true)
        }
        scheduler = SpikeChunkScheduler(exo, SchedulerCallback())
        exo.addListener(PlayerListener())
        session = MediaSession.Builder(this, LearningPlayer(exo) { chunkStarts }).build()
        _instance.value = this
        SpikeLog.i("[service] onCreate")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession {
        // 本体では、カスタムコマンドを自アプリと信頼できるシステムのコントローラからだけ受け付ける（§8.1）。
        SpikeLog.i("[service] onGetSession package=${controllerInfo.packageName}")
        return session
    }

    override fun onDestroy() {
        SpikeLog.i("[service] onDestroy")
        _instance.value = null
        cancelAutoPauseTimer()
        scheduler.release()
        session.release()
        exo.release()
        super.onDestroy()
    }

    /** aac.m4a を読み込み、クリックの区間をチャンクにする（2 回目以降は何もしない）。 */
    fun load(manifest: TestManifest) {
        // 読み込み直すと（setMediaItem）予約済みの PlayerMessage が無効になるため、一度だけにする
        if (chunkStarts.isNotEmpty()) return
        val file = manifest.files.firstOrNull { it.name == "aac.m4a" } ?: manifest.files.first()
        chunkStarts = listOf(0L) + manifest.clicks.drop(1).map { (it.truthMs - 250).toLong() }
        chunkEnds = chunkStarts.drop(1)
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(File(manifest.dir, file.name)))
            .setMediaMetadata(MediaMetadata.Builder().setTitle("S2 spike: ${file.name}").build())
            .build()
        exo.setMediaItem(item)
        exo.prepare()
        SpikeLog.i("[service] 読み込み ${file.name}、チャンク ${chunkStarts.size} 個")
    }

    /** 自動ポーズの計測を始める（[cycles] 回のポーズ。各 [pauseMs] ms）。 */
    fun startAutoPauseTest(pauseMs: Long, cycles: Int, useWakeLock: Boolean) {
        cancelAutoPauseTimer()
        test = AutoPauseTest(pauseMs, cycles, useWakeLock)
        scheduler.setMode(SpikeChunkScheduler.Mode.PauseAtChunkEnds(chunkEnds))
        SpikeLog.i("[autopause] 開始 pause=${pauseMs}ms cycles=$cycles wakeLock=$useWakeLock")
        exo.play()
    }

    /** 自動ポーズの計測をやめる（結果を書き出す）。 */
    fun stopAutoPauseTest() {
        val t = test ?: return
        cancelAutoPauseTimer()
        test = null
        scheduler.setMode(SpikeChunkScheduler.Mode.Off)
        writeResults(t)
    }

    private fun onAutoPause() {
        val t = test ?: return
        t.pausedAtElapsed = SystemClock.elapsedRealtime()
        t.pausedAtUptime = SystemClock.uptimeMillis()
        t.interactiveAtPause = power.isInteractive
        if (t.useWakeLock) wakeLock.acquire(t.pauseMs + WAKE_LOCK_MARGIN_MS)
        val task = Runnable { resumeFromAutoPause() }
        t.resumeTask = task
        handler.postDelayed(task, t.pauseMs)
    }

    private fun resumeFromAutoPause() {
        val t = test ?: return
        t.resumeTask = null
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowUptime = SystemClock.uptimeMillis()
        val row = PauseRow(
            cycle = t.rows.size + 1,
            wakeLock = t.useWakeLock,
            pauseMs = t.pauseMs,
            lateMs = nowElapsed - t.pausedAtElapsed - t.pauseMs,
            sleptMs = (nowElapsed - t.pausedAtElapsed) - (nowUptime - t.pausedAtUptime),
            interactiveAtPause = t.interactiveAtPause,
            interactiveAtResume = power.isInteractive,
            idleAtResume = power.isDeviceIdleMode,
            foregroundTypeAtResume = foregroundServiceType,
        )
        t.rows += row
        SpikeLog.i(
            "[autopause] #${row.cycle} 遅れ=${row.lateMs}ms 睡眠=${row.sleptMs}ms 画面(ポーズ時/再開時)=" +
                "${row.interactiveAtPause}/${row.interactiveAtResume} idle=${row.idleAtResume} " +
                "FGS=${row.foregroundTypeAtResume == ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK}",
        )
        if (wakeLock.isHeld) wakeLock.release()
        t.resumingByTimer = true
        exo.play()
        t.resumingByTimer = false
        if (t.rows.size >= t.cycles) stopAutoPauseTest()
    }

    private fun cancelAutoPauseTimer() {
        test?.resumeTask?.let(handler::removeCallbacks)
        test?.resumeTask = null
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun writeResults(t: AutoPauseTest) {
        val csv = buildString {
            appendLine("cycle,wakeLock,pauseMs,lateMs,sleptMs,interactiveAtPause,interactiveAtResume,idleAtResume,foregroundType")
            t.rows.forEach {
                appendLine(
                    "${it.cycle},${it.wakeLock},${it.pauseMs},${it.lateMs},${it.sleptMs},${it.interactiveAtPause}," +
                        "${it.interactiveAtResume},${it.idleAtResume},${it.foregroundTypeAtResume}",
                )
            }
        }
        SpikeLog.writeResult(this, if (t.useWakeLock) "autopause-wl" else "autopause-nowl", "csv", csv)
        val late = t.rows.map { it.lateMs }
        SpikeLog.i(
            "[autopause] まとめ wakeLock=${t.useWakeLock} n=${late.size} 遅れ 平均=${late.average().toLong()}ms " +
                "最大=${late.maxOrNull()}ms 睡眠の合計=${t.rows.sumOf { it.sleptMs }}ms",
        )
    }

    private inner class SchedulerCallback : SpikeChunkScheduler.Callback {
        override fun onDelivered(delivery: SpikeChunkScheduler.Delivery) {
            SpikeLog.i(
                "[service] 境界 ${delivery.boundaryMs}ms 配信時 ${delivery.positionAtDeliveryMs}ms → ${delivery.action}",
            )
            if (delivery.action == "pause") onAutoPause()
        }

        override fun onUserSeek(positionMs: Long) {
            SpikeLog.i("[service] ユーザーのシーク → ${positionMs}ms（予約し直した）")
        }
    }

    private inner class PlayerListener : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val t = test ?: return
            // 自動ポーズの間にユーザーが再生を始めたら、タイマーを取り消す（§7.2.4）
            if (playWhenReady && t.resumeTask != null && !t.resumingByTimer) {
                SpikeLog.i("[autopause] ユーザーの操作でタイマーを取り消し（reason=$reason）")
                cancelAutoPauseTimer()
            }
        }
    }

    companion object {
        private const val WAKE_LOCK_MARGIN_MS = 5_000L
        private val _instance = MutableStateFlow<SpikePlaybackService?>(null)

        /** 同じプロセスの Activity から計測を操作するための参照（スパイク専用。本体では使わない）。 */
        val instance: StateFlow<SpikePlaybackService?> = _instance
    }
}
