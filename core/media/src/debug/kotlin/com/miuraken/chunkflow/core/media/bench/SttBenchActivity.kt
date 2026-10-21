package com.miuraken.chunkflow.core.media.bench

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriber
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 音声認識の実機計測ツールが、本番と同じ（Hilt のシングルトンの）文字起こし器を受け取るための入口（debug ビルドだけ）。
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SttBenchEntryPoint {
    /** 本番の端末内文字起こし器。 */
    fun onDeviceChunkTranscriber(): OnDeviceChunkTranscriber

    /** 本番の音声デコーダ（クリップの切り出しだけにかかる時間を測る）。 */
    fun audioStreamDecoder(): AudioStreamDecoder
}

/**
 * 端末内の音声認識エンジンの速度と本文を、言語ごと・エンジンごとに実機で測る画面（debug ビルドだけ。release には入らない）。
 *
 * adb の shell から起動する（`android.permission.DUMP` を持つ shell からだけ起動できる）:
 * ```
 * adb shell am start -n com.miuraken.chunkflow/com.miuraken.chunkflow.core.media.bench.SttBenchActivity \
 *     --es mode run --es manifest real.tsv --es engines mtiny,mbase,sensevoice --es tag real
 * ```
 * - 入力: `getExternalFilesDir("stt-bench")`（なければ `filesDir/stt-bench`）のクリップ一覧（TSV）とクリップ（16kHz モノラル WAV）。
 * - 出力: 入力と同じディレクトリの `results-<tag>-<mode>-<日時>.jsonl`（1 行 1 件）と `<tag>-<mode>-<日時>.log`、logcat（タグ [LOG_TAG]）、この画面。
 * - 計測中は画面を点けたままにする（前面でなくなるとプロセスが止まることがあるため）。
 *
 * 計測の中身は [SttBenchRunner]、引数は [SttBenchConfig]、PC 側の準備と集計は `tools/stt-bench/`。
 */
class SttBenchActivity : Activity() {
    private lateinit var scrollView: ScrollView
    private lateinit var textView: TextView
    private val screenText = StringBuilder()

    private val fileLock = Any()
    private var logWriter: BufferedWriter? = null
    private var recordWriter: BufferedWriter? = null

    private var benchScope: CoroutineScope? = null
    private var benchExecutor: ExecutorService? = null

    @Volatile
    private var foreground: Boolean = false

    /** この画面が計測を実行中か（[RUNNING] を持っているか）。 */
    @Volatile
    private var ownsRun: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 画面がロックされていても前に出し、計測中は画面を点けたままにする
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContentView(buildContentView())
        startBench(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startBench(intent)
    }

    override fun onStart() {
        super.onStart()
        foreground = true
    }

    override fun onStop() {
        foreground = false
        if (ownsRun) {
            emit(LidBenchLevel.WARN, "WARN 画面が前面でなくなった（計測が遅くなる・止まることがある。bg=true の結果は比べるのに使わない）")
        }
        super.onStop()
    }

    override fun onDestroy() {
        benchScope?.cancel()
        benchScope = null
        benchExecutor?.shutdownNow()
        closeFiles()
        if (ownsRun) {
            ownsRun = false
            RUNNING.set(false)
        }
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val density = resources.displayMetrics.density
        textView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = SCREEN_TEXT_SIZE_SP
            setTextIsSelectable(true)
            val padding = (SCREEN_PADDING_DP * density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        scrollView = ScrollView(this).apply {
            addView(textView)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        return scrollView
    }

    /** [intent] の引数で計測を始める。ほかの計測が実行中なら何もしない。 */
    private fun startBench(intent: Intent) {
        if (!RUNNING.compareAndSet(false, true)) {
            emit(LidBenchLevel.WARN, "BUSY 計測を実行中のため、新しい起動は無視した")
            return
        }
        ownsRun = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val args = readArgs(intent)
        val bench = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, THREAD_NAME) }
        benchExecutor = bench
        val scope = CoroutineScope(SupervisorJob() + bench.asCoroutineDispatcher())
        benchScope = scope
        scope.launch {
            try {
                runBench(args)
            } catch (ce: CancellationException) {
                emit(LidBenchLevel.WARN, "END 中断した")
                throw ce
            } catch (e: Exception) {
                emit(LidBenchLevel.ERROR, "ERROR 計測が例外で止まった: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                closeFiles()
                bench.shutdown()
                if (ownsRun) {
                    ownsRun = false
                    RUNNING.set(false)
                }
                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    private suspend fun runBench(args: Map<String, Any?>) {
        val config = when (val parsed = SttBenchConfig.parse(args)) {
            is SttBenchParsed.Ok -> parsed.value

            is SttBenchParsed.Invalid -> {
                emit(LidBenchLevel.ERROR, "ERROR 引数が正しくない: ${parsed.reason}")
                emit(LidBenchLevel.INFO, SttBenchConfig.USAGE)
                return
            }
        }
        val dir = listOfNotNull(getExternalFilesDir(INPUT_DIR_NAME), File(filesDir, INPUT_DIR_NAME))
            .firstOrNull { it.isDirectory || it.mkdirs() }
        if (dir == null) {
            emit(LidBenchLevel.ERROR, "ERROR 入力のディレクトリを作れない")
            return
        }
        val clips = when (val loaded = loadClips(dir, config)) {
            is SttBenchParsed.Ok -> loaded.value

            is SttBenchParsed.Invalid -> {
                emit(LidBenchLevel.ERROR, "ERROR クリップ一覧を読めない: ${loaded.reason}")
                return
            }
        }
        openFiles(dir, config)
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SttBenchEntryPoint::class.java)
        SttBenchRunner(
            transcriber = entryPoint.onDeviceChunkTranscriber(),
            config = config,
            inputDir = dir,
            clips = clips,
            output = { level, line -> emit(level, line) },
            records = { line -> writeRecord(line) },
            deviceState = AndroidLidBenchDeviceState(applicationContext),
            deviceInfo = deviceInfo(),
            decoder = entryPoint.audioStreamDecoder(),
            memoryMb = { Debug.getPss() / KIB_PER_MIB },
            isForeground = { foreground },
        ).run()
    }

    private fun loadClips(dir: File, config: SttBenchConfig): SttBenchParsed<List<SttBenchClip>> {
        val name = config.manifestFileName ?: return SttBenchParsed.Ok(emptyList())
        val file = File(dir, name)
        if (!file.isFile) return SttBenchParsed.Invalid("$name が見つからない（${dir.absolutePath}）")
        if (file.length() > SttBenchManifest.MAX_FILE_BYTES) return SttBenchParsed.Invalid("$name が大きすぎる: ${file.length()} バイト")
        return SttBenchManifest.parse(file.readText(Charsets.UTF_8))
    }

    private fun deviceInfo(): Map<String, Any?> {
        val memoryInfo = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java)?.getMemoryInfo(memoryInfo)
        val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        return linkedMapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "soc" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE,
            "sdk" to Build.VERSION.SDK_INT,
            "release" to Build.VERSION.RELEASE,
            "cpus" to Runtime.getRuntime().availableProcessors(),
            "totalMemMb" to memoryInfo.totalMem / (KIB_PER_MIB * KIB_PER_MIB),
            "appVersion" to versionName,
        )
    }

    /** Intent の extras を、キーと値（文字列・数値・真偽値）の表にする。 */
    private fun readArgs(intent: Intent): Map<String, Any?> {
        val extras = intent.extras ?: return emptyMap()
        return extras.keySet().associateWith { key ->
            @Suppress("DEPRECATION")
            extras.get(key)
        }
    }

    /** 1 行を logcat・ファイル・画面に出す（どのスレッドから呼んでもよい）。 */
    private fun emit(level: LidBenchLevel, line: String) {
        val priority = when (level) {
            LidBenchLevel.INFO -> Log.INFO
            LidBenchLevel.WARN -> Log.WARN
            LidBenchLevel.ERROR -> Log.ERROR
        }
        Log.println(priority, LOG_TAG, line)
        synchronized(fileLock) { writeLine(logWriter, line)?.let { logWriter = null } }
        runOnUiThread { appendToScreen(line) }
    }

    private fun writeRecord(line: String) {
        synchronized(fileLock) { writeLine(recordWriter, line)?.let { recordWriter = null } }
    }

    /** [writer] に 1 行書く。書けなければ閉じて例外の名前を返す。 */
    private fun writeLine(writer: BufferedWriter?, line: String): String? {
        if (writer == null) return null
        return try {
            writer.write(line)
            writer.newLine()
            writer.flush()
            null
        } catch (e: IOException) {
            Log.w(LOG_TAG, "結果のファイルに書けない: ${e.javaClass.simpleName}")
            runCatching { writer.close() }
            e.javaClass.simpleName
        }
    }

    private fun appendToScreen(line: String) {
        screenText.append(line).append('\n')
        if (screenText.length > MAX_SCREEN_CHARS) {
            screenText.delete(0, screenText.length - MAX_SCREEN_CHARS)
        }
        textView.text = screenText
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openFiles(dir: File, config: SttBenchConfig) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))
        val base = "${config.tag}-${config.mode.label}-$timestamp"
        val logFile = File(dir, "$base.log")
        val recordFile = File(dir, "results-$base.jsonl")
        synchronized(fileLock) {
            logWriter = runCatching { logFile.bufferedWriter(Charsets.UTF_8) }.getOrNull()
            recordWriter = runCatching { recordFile.bufferedWriter(Charsets.UTF_8) }.getOrNull()
        }
        emit(LidBenchLevel.INFO, "RESULT_FILE ${recordFile.absolutePath}")
    }

    private fun closeFiles() {
        synchronized(fileLock) {
            runCatching { logWriter?.close() }
            runCatching { recordWriter?.close() }
            logWriter = null
            recordWriter = null
        }
    }

    companion object {
        /** logcat のタグ。 */
        const val LOG_TAG: String = "ChunkFlowSttBench"

        /** 入力を置くディレクトリの名前（`getExternalFilesDir` の下）。 */
        const val INPUT_DIR_NAME: String = "stt-bench"

        private const val THREAD_NAME: String = "ChunkFlowSttBench"
        private const val SCREEN_TEXT_SIZE_SP: Float = 10f
        private const val SCREEN_PADDING_DP: Int = 8
        private const val MAX_SCREEN_CHARS: Int = 200_000
        private const val KIB_PER_MIB: Double = 1_024.0

        /** 計測を実行中か（同時に 2 つ実行しない）。 */
        private val RUNNING = AtomicBoolean(false)
    }
}
