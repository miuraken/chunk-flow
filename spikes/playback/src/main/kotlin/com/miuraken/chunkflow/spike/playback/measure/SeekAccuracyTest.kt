package com.miuraken.chunkflow.spike.playback.measure

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.mp3.Mp3Extractor
import com.miuraken.chunkflow.spike.playback.SpikeLog
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

/**
 * シーク精度の計測（S2 の 2）。
 *
 * 手順（ファイル × MP3 のフラグごと）:
 * 1. 計測用の AudioSink を差し込んだ ExoPlayer で先頭から再生し、最初のクリックの時刻のずれを測る（基準: シークなし）。
 * 2. 選んだクリックごとに「真の時刻 − 500ms」へシークし、シーク後の最初の区間でクリックを探す。
 *    クリックはバーストの間隔で番号がわかるので、シークが大きく外れても、どのクリックかを取り違えない。
 * 3. 誤差 = プレイヤーが信じているクリックのメディア時刻 − 真の時刻。
 *    正なら「プレイヤーの時間軸が遅れている」（例: 10.000s へシークすると、実際には 10.000s − 誤差 の音が鳴る）。
 */
@OptIn(UnstableApi::class)
class SeekAccuracyTest(private val context: Context, private val manifest: TestManifest) {
    /** 計測の 1 行。 */
    data class Row(
        val file: String,
        val flags: String,
        val kind: String,
        val targetIndex: Int,
        val seekToMs: Long,
        val detectedIndex: Int?,
        val believedMs: Double?,
        val truthMs: Double?,
        val errorMs: Double?,
        val latencyMs: Double?,
        val note: String,
    )

    private val detector = ClickDetector(manifest)

    /** すべてのファイルを測る。[extraFiles] は端末で作ったファイルなど、manifest にないもの。 */
    suspend fun run(extraFiles: List<TestManifest.AudioFile> = emptyList()): List<Row> {
        val rows = ArrayList<Row>()
        for (file in manifest.files + extraFiles) {
            val flagsList = when {
                file.indexSeekDataSource -> listOf(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
                file.isMp3 -> listOf(0, Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
                else -> listOf(0)
            }
            for (flags in flagsList) {
                rows += measureFile(file, flags)
            }
        }
        SpikeLog.writeResult(context, "seek", "csv", toCsv(rows))
        summarize(rows).forEach(SpikeLog::i)
        return rows
    }

    private suspend fun measureFile(file: TestManifest.AudioFile, flags: Int): List<Row> {
        val flagsLabel = (if (flags == 0) "default" else "INDEX_SEEKING") +
            if (file.indexSeekDataSource) "+IndexSeekDataSource" else ""
        SpikeLog.i("[seek] ${file.name} ($flagsLabel)")
        val probe = AudioProbe()
        val player = SpikePlayers.build(
            context,
            probe,
            flags,
            handleAudioFocus = false,
            indexSeekDataSource = file.indexSeekDataSource,
        )
        val rows = ArrayList<Row>()
        try {
            player.volume = 0f
            probe.armOnNextFlush()
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(manifest.dir, file.name))))
            player.prepare()
            player.play()
            val start = SystemClock.elapsedRealtimeNanos()
            if (!SpikePlayers.awaitReady(player)) {
                SpikeLog.i("  準備できない: ${player.playerError?.errorCodeName}")
                return listOf(Row(file.name, flagsLabel, "error", -1, 0, null, null, null, null, null, "準備できない"))
            }
            val durationMs = player.duration
            SpikeLog.i(
                "  duration=${if (durationMs == C.TIME_UNSET) "unset" else "$durationMs"}ms " +
                    "(真値 ${manifest.durationMs}ms) seekable=${player.isCurrentMediaItemSeekable}",
            )
            rows += capture(file.name, flagsLabel, "baseline", 0, 0, probe, start)
            for (idx in TARGETS.filter { it < manifest.clicks.size }) {
                val seekTo = (manifest.clicks[idx].truthMs - PRE_ROLL_MS).toLong()
                probe.armOnNextFlush()
                val t0 = SystemClock.elapsedRealtimeNanos()
                player.seekTo(seekTo)
                rows += capture(file.name, flagsLabel, "seek", idx, seekTo, probe, t0)
            }
        } finally {
            player.release()
        }
        return rows
    }

    private suspend fun capture(
        file: String,
        flags: String,
        kind: String,
        targetIndex: Int,
        seekToMs: Long,
        probe: AudioProbe,
        startNs: Long,
    ): Row {
        val deadline = SystemClock.elapsedRealtime() + CAPTURE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_MS)
            val seg = probe.currentSegment() ?: continue
            if (seg.firstPtsUs == C.TIME_UNSET || probe.sampleRate == 0) continue
            val pcm = probe.snapshot(seg)
            val found = detector.find(pcm, seg.firstPtsUs, probe.sampleRate).firstOrNull()
            if (found != null) {
                val truth = manifest.clicks.getOrNull(found.index)?.truthMs
                val error = truth?.let { found.believedMs - it }
                val latency = (seg.firstBufferAtNs - startNs) / 1e6
                val note = when {
                    truth == null -> "番号 ${found.index} は範囲外（gap=${fmt(found.gapMs)}ms）"
                    found.index != targetIndex -> "狙いと別のクリック"
                    else -> ""
                }
                SpikeLog.i(
                    "  $kind #$targetIndex → #${found.index} believed=${fmt(found.believedMs)} " +
                        "truth=${truth?.let(::fmt)} err=${error?.let(::fmt)}ms 初回バッファまで ${fmt(latency)}ms " +
                        "firstPts=${seg.firstPtsUs / 1000}ms",
                )
                return Row(file, flags, kind, targetIndex, seekToMs, found.index, found.believedMs, truth, error, latency, note)
            }
            if (pcm.size > probe.sampleRate * GIVE_UP_AFTER_S) break
        }
        SpikeLog.i("  $kind #$targetIndex: クリックが見つからない")
        return Row(file, flags, kind, targetIndex, seekToMs, null, null, null, null, null, "クリックが見つからない")
    }

    private fun toCsv(rows: List<Row>): String = buildString {
        appendLine("file,flags,kind,targetIndex,seekToMs,detectedIndex,believedMs,truthMs,errorMs,latencyMs,note")
        rows.forEach { r ->
            appendLine(
                listOf(
                    r.file, r.flags, r.kind, r.targetIndex, r.seekToMs, r.detectedIndex ?: "",
                    r.believedMs?.let(::fmt) ?: "", r.truthMs?.let(::fmt) ?: "", r.errorMs?.let(::fmt) ?: "",
                    r.latencyMs?.let(::fmt) ?: "", r.note,
                ).joinToString(","),
            )
        }
    }

    private fun summarize(rows: List<Row>): List<String> = buildList {
        add("[seek] まとめ（基準 = シークなしの誤差、シーク = シーク後の誤差 ms）")
        rows.groupBy { it.file to it.flags }.forEach { (key, group) ->
            val base = group.firstOrNull { it.kind == "baseline" }?.errorMs
            val seeks = group.filter { it.kind == "seek" }.mapNotNull { it.errorMs }
            val missing = group.count { it.kind == "seek" && it.errorMs == null }
            val maxAbs = seeks.maxOfOrNull { abs(it) }
            val mean = if (seeks.isEmpty()) null else seeks.average()
            add(
                "  ${key.first} ${key.second}: 基準=${base?.let(::fmt)} シーク: 平均=${mean?.let(::fmt)} " +
                    "最大|誤差|=${maxAbs?.let(::fmt)} 範囲=[${seeks.minOrNull()?.let(::fmt)}, " +
                    "${seeks.maxOrNull()?.let(::fmt)}] 未検出=$missing",
            )
        }
    }

    private companion object {
        /**
         * シークで狙うクリックの番号。最初（8 秒）以外は、前のシーク先から 60 秒以上離れるように前後に飛ぶ
         * （ExoPlayer は読み込み済みの範囲の中へのシークを正確に行うため、その影響を避ける）。
         */
        val TARGETS = listOf(1, 95, 8, 80, 20, 65, 35, 50, 12, 45)
        const val PRE_ROLL_MS = 500.0
        const val POLL_MS = 100L
        const val CAPTURE_TIMEOUT_MS = 20_000L
        const val GIVE_UP_AFTER_S = 9

        fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    }
}
