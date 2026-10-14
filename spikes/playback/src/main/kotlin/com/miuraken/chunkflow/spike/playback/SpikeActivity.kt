package com.miuraken.chunkflow.spike.playback

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.miuraken.chunkflow.spike.playback.measure.LoopAccuracyTest
import com.miuraken.chunkflow.spike.playback.measure.Mp4Repackager
import com.miuraken.chunkflow.spike.playback.measure.SeekAccuracyTest
import com.miuraken.chunkflow.spike.playback.measure.TestManifest
import com.miuraken.chunkflow.spike.playback.picker.PickerInspector
import com.miuraken.chunkflow.spike.playback.service.SpikePlaybackService
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * スパイク S2 の計測画面。
 *
 * テスト音源は `adb push` で `files/audio/` に置く（spikes/playback/testdata/generate_testdata.py を参照）。
 * adb から自動で始めるには `--es run <seek|loop|repackage|all|service|autopause_wl|autopause_nowl>` を渡す
 * （例: `adb shell am start -n com.miuraken.chunkflow.spike.playback/.SpikeActivity --es run loop`）。
 */
class SpikeActivity : ComponentActivity() {
    private val busy = mutableStateOf(false)
    private var job: Job? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val audioDir: File get() = File(getExternalFilesDir(null), "audio")

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // adb shell で作ったフォルダは所有者が shell になりアプリから読めないため、アプリが先に作る
        audioDir.mkdirs()
        SpikeLog.init(this)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen()
                }
            }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        controllerFuture?.let(MediaController::releaseFuture)
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent) {
        val run = intent.getStringExtra("run") ?: return
        val pauseMs = intent.getLongExtra("pauseMs", DEFAULT_PAUSE_MS)
        val cycles = intent.getIntExtra("cycles", DEFAULT_CYCLES)
        SpikeLog.i("intent run=$run")
        when (run) {
            "seek" -> launchTask { runSeek() }

            "loop" -> launchTask { runLoop() }

            "repackage" -> launchTask { runRepackage() }

            "seekfix" -> launchTask { runSeekFix() }

            "all" -> launchTask {
                runLoop()
                runSeek()
                runRepackage()
            }

            "service" -> connectService()

            "autopause_wl" -> startAutoPause(pauseMs, cycles, wakeLock = true)

            "autopause_nowl" -> startAutoPause(pauseMs, cycles, wakeLock = false)
        }
    }

    private fun manifestOrNull(): TestManifest? = TestManifest.load(audioDir).also {
        if (it == null) SpikeLog.i("manifest.json がない: ${audioDir.path} に adb push してください")
    }

    private fun launchTask(block: suspend () -> Unit) {
        if (busy.value) {
            SpikeLog.i("実行中です")
            return
        }
        busy.value = true
        MeasureService.start(this)
        job = lifecycleScope.launch {
            try {
                block()
                SpikeLog.i("完了")
            } finally {
                busy.value = false
                MeasureService.stop(this@SpikeActivity)
            }
        }
    }

    private suspend fun runSeek() {
        val manifest = manifestOrNull() ?: return
        val extra = DEVICE_REPACK.takeIf { File(audioDir, it).isFile }
            ?.let { listOf(TestManifest.AudioFile(it, "VBR MP3 を MediaMuxer で MP4 に詰め替え（端末）", isMp3 = false)) }
            .orEmpty()
        SeekAccuracyTest(this, manifest).run(extra)
    }

    /** IndexSeekDataSource（ヘッダを隠して IndexSeeker を使わせる）を通した MP3 のシーク精度。 */
    private suspend fun runSeekFix() {
        val manifest = manifestOrNull() ?: return
        val files = listOf("vbr_xing.mp3", "vbr_noheader.mp3", "cbr.mp3").map {
            TestManifest.AudioFile(it, "$it + IndexSeekDataSource", isMp3 = true, indexSeekDataSource = true)
        }
        SeekAccuracyTest(this, manifest.copy(files = emptyList())).run(files)
    }

    private suspend fun runLoop() {
        val manifest = manifestOrNull() ?: return
        LoopAccuracyTest(this, manifest).run()
    }

    private suspend fun runRepackage() {
        val manifest = manifestOrNull() ?: return
        val src = File(audioDir, "vbr_xing.mp3")
        val dst = File(audioDir, DEVICE_REPACK)
        when (val r = Mp4Repackager.repackage(src, dst)) {
            is Mp4Repackager.Result.Ok -> {
                SpikeLog.i("[repackage] 成功 ${r.samples} フレーム 最後の時刻 ${r.lastPtsUs / 1000}ms ${r.elapsedMs}ms かかった")
                SeekAccuracyTest(this, manifest.copy(files = emptyList()))
                    .run(listOf(TestManifest.AudioFile(DEVICE_REPACK, "端末で詰め替え", isMp3 = false)))
            }

            is Mp4Repackager.Result.Failed -> SpikeLog.i("[repackage] 失敗: ${r.reason}")
        }
    }

    private fun connectService() {
        if (controllerFuture != null) return
        val token = SessionToken(this, ComponentName(this, SpikePlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                SpikeLog.i("[service] MediaController 接続")
                val manifest = manifestOrNull() ?: return@addListener
                SpikePlaybackService.instance.value?.load(manifest)
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun controller(): MediaController? = controllerFuture?.takeIf { it.isDone }?.get()

    private fun startAutoPause(pauseMs: Long, cycles: Int, wakeLock: Boolean) {
        connectService()
        lifecycleScope.launch {
            var service = SpikePlaybackService.instance.value
            var waited = 0
            while (service == null && waited < 50) {
                delay(100)
                waited++
                service = SpikePlaybackService.instance.value
            }
            if (service == null) {
                SpikeLog.i("[service] サービスに接続できない")
                return@launch
            }
            manifestOrNull()?.let(service::load)
            service.startAutoPauseTest(pauseMs, cycles, wakeLock)
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun SpikeScreen() {
        val lines by SpikeLog.lines.collectAsState()
        val isBusy by busy
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
        }
        val openDocs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val text = PickerInspector.describeDocuments(this, uris)
            text.lines().forEach(SpikeLog::i)
            SpikeLog.writeResult(this, "picker-docs", "txt", text)
        }
        val openTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) {
                SpikeLog.i("[picker] フォルダが選ばれなかった（キャンセル）")
                return@rememberLauncherForActivityResult
            }
            val text = PickerInspector.describeTree(this, uri)
            text.lines().forEach(SpikeLog::i)
            SpikeLog.writeResult(this, "picker-tree", "txt", text)
        }
        Column(modifier = Modifier.safeDrawingPadding().padding(8.dp)) {
            Text("S2 playback spike", style = MaterialTheme.typography.titleMedium)
            Text("音源: files/audio/（${if (File(audioDir, "manifest.json").isFile) "あり" else "なし"}）", fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(enabled = !isBusy, onClick = { launchTask { runLoop() } }) { Text("ループ") }
                Button(enabled = !isBusy, onClick = { launchTask { runSeek() } }) { Text("シーク") }
                Button(enabled = !isBusy, onClick = { launchTask { runRepackage() } }) { Text("MP4 詰め替え") }
                OutlinedButton(onClick = { job?.cancel() }) { Text("中止") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { connectService() }) { Text("サービス接続") }
                Button(onClick = { startAutoPause(DEFAULT_PAUSE_MS, DEFAULT_CYCLES, wakeLock = true) }) { Text("自動ポーズ WL あり") }
                Button(onClick = { startAutoPause(DEFAULT_PAUSE_MS, DEFAULT_CYCLES, wakeLock = false) }) { Text("WL なし") }
                OutlinedButton(onClick = { SpikePlaybackService.instance.value?.stopAutoPauseTest() }) { Text("停止") }
                OutlinedButton(onClick = { controller()?.seekToPrevious() }) { Text("前") }
                OutlinedButton(onClick = { controller()?.seekToNext() }) { Text("次") }
                OutlinedButton(onClick = { controller()?.let { if (it.isPlaying) it.pause() else it.play() } }) { Text("再生/停止") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { openDocs.launch(arrayOf("*/*")) }) { Text("複数選択") }
                Button(onClick = { openTree.launch(null) }) { Text("フォルダ選択") }
                OutlinedButton(onClick = { SpikeLog.clear() }) { Text("ログ消去") }
            }
            HorizontalDivider()
            LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                items(lines) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                }
            }
        }
    }

    private companion object {
        const val DEVICE_REPACK = "vbr_repack_device.mp4"
        const val DEFAULT_PAUSE_MS = 5_000L
        const val DEFAULT_CYCLES = 10
    }
}
