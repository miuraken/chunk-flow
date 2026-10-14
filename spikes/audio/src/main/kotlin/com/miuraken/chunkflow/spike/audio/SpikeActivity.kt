package com.miuraken.chunkflow.spike.audio

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * スパイク S3 の計測画面。
 *
 * 音声は `adb push` で `/sdcard/Android/data/com.miuraken.chunkflow.spike.audio/files/s3/<フォルダ>/` に置く
 * （同じフォルダに clicks.json）。結果は `files/s3_results.jsonl` に 1 行 1 件の JSON で追記し、logcat（タグ S3）にも出す。
 *
 * adb から実行する例:
 * `adb shell am start -n com.miuraken.chunkflow.spike.audio/.SpikeActivity --es cmd suite --es path 30min --ei repeat 2`
 *
 * cmd: `decode`（抽出 + デコード + 解析）、`decodeonly`（デコードだけ）、`exo`（ExoPlayer・高速）、
 * `exort`（ExoPlayer・等速）、`decodeasync`（非同期モードのデコード + 解析）、
 * `speed`（同期・非同期・ExoPlayer の速度の比較）、`suite`（フォルダ内の全ファイルで decode×2 種の抽出器・decodeonly・exo）。
 * extractor: `platform` / `compat`。
 */
class SpikeActivity : ComponentActivity() {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "s3-worker") }
    private val main = Handler(Looper.getMainLooper())
    private val log = mutableStateListOf<String>()
    private val status = mutableStateOf("待機中")
    private val pending = AtomicInteger(0)

    private val root: File by lazy { File(getExternalFilesDir(null), "s3").apply { mkdirs() } }
    private val resultsFile: File by lazy { File(getExternalFilesDir(null), "s3_results.jsonl") }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen()
                }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun handle(intent: Intent?) {
        val cmd = intent?.getStringExtra("cmd") ?: return
        val path = intent.getStringExtra("path") ?: return
        val kind = if (intent.getStringExtra("extractor") == "compat") ExtractorKind.COMPAT else ExtractorKind.PLATFORM
        val repeat = intent.getIntExtra("repeat", 1).coerceIn(1, 10)
        val target = resolve(path) ?: run {
            append("パスが不正: $path")
            return
        }
        enqueue(cmd, target, kind, repeat)
    }

    /** `files/s3` の中のパスだけを受け付ける（それ以外は null）。 */
    private fun resolve(path: String): File? {
        val f = File(root, path).canonicalFile
        return if (f.path.startsWith(root.canonicalPath) && f.exists()) f else null
    }

    private fun enqueue(cmd: String, target: File, kind: ExtractorKind, repeat: Int) {
        // 計測中は凍結されないようにフォアグラウンドサービスを動かす（MeasureService の説明を参照）
        if (pending.getAndIncrement() == 0) MeasureService.start(this)
        worker.execute {
            repeat(repeat) {
                when (cmd) {
                    "decode" -> record(DecodeBenchmark(this, target, kind, true, ClickMatch.loadExpected(target)).run())

                    "decodeonly" -> record(
                        DecodeBenchmark(this, target, kind, false, ClickMatch.loadExpected(target)).run(),
                    )

                    "exo" -> record(ExoTimingProbe(this, target, false, ClickMatch.loadExpected(target)).run(TIMEOUT_MS))

                    "exort" -> record(ExoTimingProbe(this, target, true, ClickMatch.loadExpected(target)).run(TIMEOUT_MS))

                    "decodeasync" -> record(
                        AsyncDecodeBenchmark(this, target, kind, ClickMatch.loadExpected(target)).run(TIMEOUT_MS),
                    )

                    "speed" -> speed(target)

                    "suite" -> suite(target)

                    else -> append("不明なコマンド: $cmd")
                }
            }
            setStatus("完了: $cmd ${target.name}")
            if (pending.decrementAndGet() == 0) MeasureService.stop(this)
        }
    }

    private fun suite(dir: File) {
        val files = audioFiles(dir)
        for (f in files) {
            val expected = ClickMatch.loadExpected(f)
            record(DecodeBenchmark(this, f, ExtractorKind.PLATFORM, true, expected).run())
            record(DecodeBenchmark(this, f, ExtractorKind.COMPAT, true, expected).run())
            record(DecodeBenchmark(this, f, ExtractorKind.PLATFORM, false, expected).run())
            record(ExoTimingProbe(this, f, false, expected).run(TIMEOUT_MS))
        }
    }

    /** 速度の比較: 同期モード・非同期モード（2 種の抽出器）・ExoPlayer（高速）。[target] はファイルかフォルダ。 */
    private fun speed(target: File) {
        val files = if (target.isDirectory) audioFiles(target) else listOf(target)
        for (f in files) {
            val expected = ClickMatch.loadExpected(f)
            record(DecodeBenchmark(this, f, ExtractorKind.PLATFORM, true, expected).run())
            record(AsyncDecodeBenchmark(this, f, ExtractorKind.PLATFORM, expected).run(TIMEOUT_MS))
            record(AsyncDecodeBenchmark(this, f, ExtractorKind.COMPAT, expected).run(TIMEOUT_MS))
            record(ExoTimingProbe(this, f, false, expected).run(TIMEOUT_MS))
        }
    }

    private fun audioFiles(dir: File): List<File> = dir.listFiles()
        ?.filter { it.isFile && it.extension in AUDIO_EXTENSIONS }
        ?.sortedBy { it.name }
        .orEmpty()

    private fun record(result: JSONObject) {
        result.put("device", "${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}, ${Build.SUPPORTED_ABIS.firstOrNull()})")
        result.put("isEmulator", Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu"))
        val line = result.toString()
        Log.i(TAG, line)
        resultsFile.appendText(line + "\n")
        append(summaryOf(result))
    }

    private fun summaryOf(r: JSONObject): String {
        val sb = StringBuilder()
        sb.append(r.optString("test")).append(' ').append(r.optString("file"))
        if (r.has("extractor")) sb.append(' ').append(r.optString("extractor"))
        if (r.has("error")) sb.append(" ERROR ").append(r.optString("error"))
        if (r.has("wallMs")) sb.append(" wall=").append(r.optDouble("wallMs")).append("ms")
        if (r.has("speedX")) sb.append(" x").append(r.optDouble("speedX"))
        if (r.has("javaHeapExtraMb")) sb.append(" heap+").append(r.optDouble("javaHeapExtraMb")).append("MB")
        val clicks = r.optJSONObject("clicks")
        if (clicks != null) {
            val c = clicks.optJSONObject("analysis16kRaw") ?: clicks
            sb.append(" click=").append(c.optString("deltaMsMin")).append("..").append(c.optString("deltaMsMax")).append("ms")
        }
        return sb.toString()
    }

    private fun append(line: String) {
        main.post { log.add(0, line) }
    }

    private fun setStatus(s: String) {
        main.post { status.value = s }
    }

    @Composable
    private fun SpikeScreen() {
        val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }.orEmpty()
        Column(
            modifier = Modifier.safeDrawingPadding().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(R.string.spike_title), style = MaterialTheme.typography.titleMedium)
            Text("${Build.MODEL} / SDK ${Build.VERSION.SDK_INT} / ${status.value}")
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (dir in dirs) {
                    item(key = dir.path) {
                        OutlinedButton(onClick = { enqueue("suite", dir, ExtractorKind.PLATFORM, 1) }) {
                            Text(stringResource(R.string.run_suite, dir.name))
                        }
                    }
                    items(audioFiles(dir), key = { it.path }) { f ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${dir.name}/${f.name}", modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { enqueue("decode", f, ExtractorKind.PLATFORM, 1) }) {
                                Text("P")
                            }
                            OutlinedButton(onClick = { enqueue("decode", f, ExtractorKind.COMPAT, 1) }) {
                                Text("C")
                            }
                            OutlinedButton(onClick = { enqueue("exo", f, ExtractorKind.PLATFORM, 1) }) {
                                Text("Exo")
                            }
                        }
                    }
                }
                items(log) { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    private companion object {
        const val TAG = "S3"
        const val TIMEOUT_MS = 30 * 60 * 1000L
        val AUDIO_EXTENSIONS = setOf("wav", "mp3", "m4a", "aac", "opus", "ogg", "flac")
    }
}
