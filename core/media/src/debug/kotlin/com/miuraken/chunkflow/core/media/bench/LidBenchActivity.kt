package com.miuraken.chunkflow.core.media.bench

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 一括言語判定の速度を、純 Kotlin と ONNX Runtime の両方で実機で測る画面（debug ビルドだけ。release には入らない）。
 *
 * adb の shell から起動する（`android.permission.DUMP` を持つ shell からだけ起動できる）:
 * ```
 * adb shell am start -n com.miuraken.chunkflow/com.miuraken.chunkflow.core.media.bench.LidBenchActivity \
 *     --es name <教材名> --es target hi --es base en --ei maxChunks 60
 * ```
 * - 入力: `getExternalFilesDir("lid-bench")`（なければ `filesDir/lid-bench`）の `<name>.mp3` と `<name>.chunks.json`。
 * - 出力: logcat（タグ [LOG_TAG]）、この画面、入力と同じディレクトリの `<name>-<日時>.log`。
 * - 計測中は画面を点けたままにする（前面でなくなるとプロセスが止まることがあるため）。
 * - 音声の切り出しの方式を比べるときは `--es extraction cur,par,str` を足す（教材全体なら `--ei maxChunks 0`）。
 *
 * 計測の中身は [LidBenchRunner]、手順は `docs/spikes/S3-onnx-whisper-lid.md` §9（切り出しの方式の比較は §10）。
 */
class LidBenchActivity : Activity() {
    private lateinit var scrollView: ScrollView
    private lateinit var textView: TextView
    private val screenText = StringBuilder()

    private val logLock = Any()
    private var logWriter: BufferedWriter? = null

    private var benchScope: CoroutineScope? = null
    private var benchExecutor: ExecutorService? = null
    private var inferenceExecutor: ExecutorService? = null

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
            emit(LidBenchLevel.WARN, "WARN 画面が前面でなくなった（計測が遅くなる・止まることがある。bg=true の回は比べるのに使わない）")
        }
        super.onStop()
    }

    override fun onDestroy() {
        benchScope?.cancel()
        benchScope = null
        inferenceExecutor?.shutdownNow()
        benchExecutor?.shutdownNow()
        closeLogFile()
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
        val bench = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "$THREAD_NAME-main") }
        val threadIndex = AtomicInteger()
        val inference = Executors.newFixedThreadPool(INFERENCE_THREADS) { runnable ->
            Thread(runnable, "$THREAD_NAME-infer-${threadIndex.incrementAndGet()}")
        }
        benchExecutor = bench
        inferenceExecutor = inference
        val scope = CoroutineScope(SupervisorJob() + bench.asCoroutineDispatcher())
        benchScope = scope
        scope.launch {
            try {
                runBench(args, inference.asCoroutineDispatcher())
            } catch (ce: CancellationException) {
                emit(LidBenchLevel.WARN, "END 中断した")
                throw ce
            } catch (e: Exception) {
                emit(LidBenchLevel.ERROR, "ERROR 計測が例外で止まった: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                closeLogFile()
                inference.shutdown()
                bench.shutdown()
                if (ownsRun) {
                    ownsRun = false
                    RUNNING.set(false)
                }
                runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    private suspend fun runBench(args: Map<String, Any?>, inferenceDispatcher: CoroutineDispatcher) {
        val config = when (val parsed = LidBenchConfig.parse(args)) {
            is LidBenchParsed.Ok -> parsed.value

            is LidBenchParsed.Invalid -> {
                emit(LidBenchLevel.ERROR, "ERROR 引数が正しくない: ${parsed.reason}")
                emit(LidBenchLevel.INFO, LidBenchConfig.USAGE)
                return
            }
        }
        val dirs = listOfNotNull(getExternalFilesDir(INPUT_DIR_NAME), File(filesDir, INPUT_DIR_NAME))
        val input = when (val loaded = LidBenchInputLoader.load(dirs, config)) {
            is LidBenchParsed.Ok -> loaded.value

            is LidBenchParsed.Invalid -> {
                emit(LidBenchLevel.ERROR, "ERROR 入力を読めない: ${loaded.reason}")
                return
            }
        }
        input.audioFile.parentFile?.let { dir -> openLogFile(dir, config.name) }
        LidBenchRunner(
            context = applicationContext,
            config = config,
            input = input,
            inferenceDispatcher = inferenceDispatcher,
            output = { level, line -> emit(level, line) },
            isForeground = { foreground },
        ).run()
    }

    /** Intent の extras を、キーと値（文字列・数値）の表にする。 */
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
        writeLogLine(line)
        runOnUiThread { appendToScreen(line) }
    }

    private fun appendToScreen(line: String) {
        screenText.append(line).append('\n')
        if (screenText.length > MAX_SCREEN_CHARS) {
            screenText.delete(0, screenText.length - MAX_SCREEN_CHARS)
        }
        textView.text = screenText
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun openLogFile(dir: File, name: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT))
        val file = File(dir, "$name-$timestamp.log")
        synchronized(logLock) {
            logWriter = try {
                file.bufferedWriter(Charsets.UTF_8)
            } catch (e: IOException) {
                Log.w(LOG_TAG, "結果のファイルを作れない: ${e.javaClass.simpleName}")
                null
            }
        }
        if (logWriter != null) emit(LidBenchLevel.INFO, "LOG_FILE ${file.absolutePath}")
    }

    private fun writeLogLine(line: String) {
        synchronized(logLock) {
            val writer = logWriter ?: return
            try {
                writer.write(line)
                writer.newLine()
                writer.flush()
            } catch (e: IOException) {
                Log.w(LOG_TAG, "結果のファイルに書けない: ${e.javaClass.simpleName}")
                runCatching { writer.close() }
                logWriter = null
            }
        }
    }

    private fun closeLogFile() {
        synchronized(logLock) {
            runCatching { logWriter?.close() }
            logWriter = null
        }
    }

    companion object {
        /** logcat のタグ。 */
        const val LOG_TAG: String = "ChunkFlowLidBench"

        /** 入力を置くディレクトリの名前（`getExternalFilesDir` の下）。 */
        const val INPUT_DIR_NAME: String = "lid-bench"

        /** 推論に使うスレッド数（3 並列の回の並列数と同じ）。 */
        private const val INFERENCE_THREADS: Int = 3

        private const val THREAD_NAME: String = "ChunkFlowLidBench"
        private const val SCREEN_TEXT_SIZE_SP: Float = 10f
        private const val SCREEN_PADDING_DP: Int = 8
        private const val MAX_SCREEN_CHARS: Int = 200_000

        /** 計測を実行中か（同時に 2 つ実行しない）。 */
        private val RUNNING = AtomicBoolean(false)
    }
}
