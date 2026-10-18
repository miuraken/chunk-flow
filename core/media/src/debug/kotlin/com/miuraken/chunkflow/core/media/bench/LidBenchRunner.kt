package com.miuraken.chunkflow.core.media.bench

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import com.miuraken.chunkflow.core.media.AndroidSpeechRecognizerChunkTranscriber
import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import com.miuraken.chunkflow.core.media.LidEngineMode
import com.miuraken.chunkflow.core.media.LidExtractionMethod
import com.miuraken.chunkflow.core.media.MediaCodecAudioStreamDecoder
import com.miuraken.chunkflow.core.media.WhisperTinyLanguageDetector
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay

/** 計測ツールの出力の重さ（logcat の優先度に対応する）。 */
internal enum class LidBenchLevel {
    INFO,
    WARN,
    ERROR,
}

/** 計測ツールの出力先（logcat・画面・ファイル）。 */
internal fun interface LidBenchOutput {
    /** 1 行を出す。 */
    fun emit(level: LidBenchLevel, line: String)
}

/** 計測の前後に記録する端末の状態。 */
internal interface LidBenchDeviceState {
    /** `PowerManager.currentThermalStatus`（0 = NONE 〜 6 = SHUTDOWN）。取れなければ -1。 */
    fun thermalStatus(): Int

    /** `PowerManager.getThermalHeadroom(0)`（1.0 で重い制限が始まる目安）。取れなければ NaN。 */
    fun thermalHeadroom(): Float

    /** 省電力モードか。 */
    fun isPowerSaveMode(): Boolean
}

/** [PowerManager] から端末の状態を読む。 */
internal class AndroidLidBenchDeviceState(context: Context) : LidBenchDeviceState {
    private val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)

    override fun thermalStatus(): Int = powerManager?.currentThermalStatus ?: -1

    override fun thermalHeadroom(): Float = powerManager?.getThermalHeadroom(0) ?: Float.NaN

    override fun isPowerSaveMode(): Boolean = powerManager?.isPowerSaveMode == true
}

/**
 * 一括言語判定の速度を、純 Kotlin と ONNX Runtime の両方で測る（debug ビルドの計測ツール。手順は
 * `docs/spikes/S3-onnx-whisper-lid.md` §9・§10）。
 *
 * 1. エンジンごとにウォームアップ（[LidBenchConfig.warmupChunks] 件。今の方式で切り出す）。ONNX Runtime を読み込めなければ、その計測は行わない。
 * 2. エンジン × 進め方 × 音声の切り出しの方式（[LidBenchConfig.extractions]）の順に、クールダウンしてから
 *    [AndroidSpeechRecognizerChunkTranscriber.detectChunkLanguages] を通して測る。回ごとに、新しいデコーダ（本番と同じ
 *    [MediaCodecAudioStreamDecoder]）と新しい文字起こし器（ファイル内の補正の状態を持つ）を作る。
 *    切り出しの各段階の時間と、切り出した PCM の指紋（[LidBenchPcmPrint]）も記録する。
 * 3. 最後に、各回の結果（`RESULT` 行）、判定が違うチャンク（`DIFF`）、PCM が今の方式と違うチャンク（`PCMDIFF`）、表、
 *    純 Kotlin と ONNX Runtime の比（`SUMMARY`）、切り出しの方式ごとの今の方式との比（`SUMMARY_EXT`）を出す。
 *
 * エンジンは判定器の [LidEngineMode] で選ぶ（本番の動作は変えない）。ONNX Runtime の回で純 Kotlin に切り替わった推論があれば、
 * その回は [LidBenchStatus.FALLBACK] にし、ONNX Runtime の結果として扱わない。
 *
 * @param inferenceDispatcher 推論に使う Dispatcher（呼び出し側が作ったスレッドプール）。
 * @param isForeground 画面が前面にあるか（前面でなくなった回に印を付ける）。
 * @param detectorFactory エンジンの判定器を作る（テストで差し替える）。
 * @param decoderFactory 回ごとに音声のデコーダを作る（テストで差し替える）。
 * @param cpuTimeMs プロセスの CPU 時間（ミリ秒）。
 * @param sleep クールダウンで待つ（テストで差し替える）。
 */
internal class LidBenchRunner(
    private val context: Context,
    private val config: LidBenchConfig,
    private val input: LidBenchInput,
    private val inferenceDispatcher: CoroutineDispatcher,
    private val output: LidBenchOutput,
    private val deviceState: LidBenchDeviceState = AndroidLidBenchDeviceState(context),
    private val isForeground: () -> Boolean = { true },
    private val detectorFactory: (LidBenchEngine) -> WhisperTinyLanguageDetector = { engine -> createDetector(context, engine) },
    private val decoderFactory: () -> AudioStreamDecoder = { MediaCodecAudioStreamDecoder(context) },
    private val cpuTimeMs: () -> Long = { Process.getElapsedCpuTime() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val languagePair = LanguagePair(target = LanguageTag(config.targetTag), base = LanguageTag(config.baseTag))

    /** 判定器が結果を返した推論の時間（ナノ秒）と、`null` を返した回数。 */
    private class InferenceRecorder {
        val nanos = ConcurrentLinkedQueue<Long>()
        val nullCount = AtomicInteger()
    }

    /** 計測を最後まで行い、各回の結果を返す。 */
    suspend fun run(): List<LidBenchRunResult> {
        val startNanos = System.nanoTime()
        output.emit(LidBenchLevel.INFO, startLine())
        val detectors = config.engines.associateWith(detectorFactory)
        val unavailable = HashMap<LidBenchEngine, String>()
        if (config.warmupChunks > 0) {
            for (engine in config.engines) {
                warmUp(engine, detectors.getValue(engine))?.let { unavailable[engine] = it }
            }
        }
        val results = ArrayList<LidBenchRunResult>()
        for (engine in config.engines) {
            for (mode in config.modes) {
                for (extraction in config.extractions) {
                    val reason = unavailable[engine]
                    val result = if (reason != null) {
                        skippedResult(engine, mode, extraction, reason)
                    } else {
                        coolDown()
                        measure(engine, mode, extraction, detectors.getValue(engine))
                    }
                    results += result
                    output.emit(levelOf(result), LidBenchReport.runLine("RUN_DONE", config.name, result, null, withAgreement = false))
                }
            }
        }
        report(results)
        val elapsedSec = (System.nanoTime() - startNanos) / 1e9
        output.emit(
            LidBenchLevel.INFO,
            "END name=${config.name} runs=${results.size} ok=${results.count { it.status == LidBenchStatus.OK }} " +
                "elapsedSec=${LidBenchReport.fmt1(elapsedSec)}",
        )
        return results
    }

    private fun startLine(): String = buildString {
        append("START name=").append(config.name)
        append(" target=").append(config.targetTag)
        append(" base=").append(config.baseTag)
        append(" chunks=").append(input.chunks.size).append('/').append(input.totalChunks)
        append(" warmup=").append(config.warmupChunks)
        append(" cooldownSec=").append(config.cooldownSec)
        append(" thermalWaitSec=").append(config.thermalWaitSec)
        append(" engines=").append(config.engines.joinToString(",") { it.label })
        append(" modes=").append(config.modes.joinToString(",") { it.label })
        append(" extractions=").append(config.extractions.joinToString(",") { it.label })
        append(" streamGapMs=").append(config.streaming.maxGapMs)
        append(" streamRunWindows=").append(config.streaming.maxRunWindows)
        append(" streamRunMs=").append(config.streaming.maxRunDurationMs)
        append(" streamQueue=").append(config.streaming.queueCapacity)
        append(" audio=").append(input.audioFile.name)
        append(" audioBytes=").append(input.audioFile.length())
        append(" device=").append(token(Build.MANUFACTURER)).append('/').append(token(Build.MODEL))
        append(" hardware=").append(token(Build.HARDWARE))
        append(" sdk=").append(Build.VERSION.SDK_INT)
        append(" cpus=").append(Runtime.getRuntime().availableProcessors())
        append(" abi=").append(Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        append(" thermal=").append(deviceState.thermalStatus())
        append(" powerSave=").append(deviceState.isPowerSaveMode())
    }

    /**
     * [engine] をウォームアップする（モデルの読み込みと JIT）。使えないエンジンなら理由を返す。
     *
     * ONNX Runtime の判定器で、純 Kotlin に切り替わった推論があるか、使えない理由が残っていれば使えないとする。
     */
    private suspend fun warmUp(engine: LidBenchEngine, detector: WhisperTinyLanguageDetector): String? {
        val chunks = input.chunks.take(config.warmupChunks)
        val before = detector.inferenceStats()
        val startNanos = System.nanoTime()
        val transcriber = newTranscriber(detector, decoderFactory(), recorder = null)
        try {
            for (chunk in chunks) {
                transcriber.detectChunkLanguage(input.audioFile, chunk.startMs, chunk.endMs, languagePair)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            val reason = "ウォームアップで例外 (${e.javaClass.simpleName})"
            output.emit(LidBenchLevel.ERROR, "ERROR engine=${engine.label} $reason: ${e.message}")
            return reason
        } finally {
            transcriber.releaseIdleResources()
        }
        val after = detector.inferenceStats()
        val delta = after.since(before)
        val elapsedMs = (System.nanoTime() - startNanos) / 1e6
        output.emit(
            LidBenchLevel.INFO,
            "WARMUP engine=${engine.label} chunks=${chunks.size} ms=${LidBenchReport.fmt1(elapsedMs)} " +
                "onnxRuns=${delta.onnxRuns} kotlinRuns=${delta.kotlinRuns}",
        )
        if (engine != LidBenchEngine.ONNX) return null
        val unavailableReason = after.onnxUnavailableReason
        if (delta.kotlinRuns > 0L || unavailableReason != null) {
            val reason = unavailableReason ?: "純 Kotlin に切り替わった"
            output.emit(LidBenchLevel.ERROR, "ERROR ONNX_UNAVAILABLE reason=$reason（ONNX Runtime の計測は行わない）")
            return reason
        }
        if (delta.onnxRuns > 0L) {
            output.emit(LidBenchLevel.INFO, "ONNX_LOAD ok loadMs=${after.onnxLoadTimeMs ?: "NA"}")
        } else {
            output.emit(LidBenchLevel.WARN, "WARN ONNX_LOAD unknown（ウォームアップのチャンクを 1 つも推論しなかった）")
        }
        return null
    }

    /** 各回の前に待つ。クールダウンのあと、端末の温度の状態が MODERATE 以上なら、下がるまで上限まで待つ。 */
    private suspend fun coolDown() {
        if (config.cooldownSec > 0) {
            output.emit(LidBenchLevel.INFO, "COOLDOWN sec=${config.cooldownSec} thermal=${deviceState.thermalStatus()}")
            sleep(config.cooldownSec * 1_000L)
        }
        if (config.thermalWaitSec <= 0 || deviceState.isPowerSaveMode()) return
        val limitMs = config.thermalWaitSec * 1_000L
        var waitedMs = 0L
        while (deviceState.thermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE && waitedMs < limitMs) {
            sleep(THERMAL_POLL_INTERVAL_MS)
            waitedMs += THERMAL_POLL_INTERVAL_MS
        }
        if (waitedMs > 0L) {
            val thermal = deviceState.thermalStatus()
            val level = if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) LidBenchLevel.WARN else LidBenchLevel.INFO
            output.emit(level, "THERMAL_WAIT waitedSec=${waitedMs / 1_000L} thermal=$thermal")
        }
    }

    /** [engine] × [mode] × [extraction] を 1 回測る。 */
    private suspend fun measure(
        engine: LidBenchEngine,
        mode: LidBenchMode,
        extraction: LidExtractionMethod,
        detector: WhisperTinyLanguageDetector,
    ): LidBenchRunResult {
        val recorder = InferenceRecorder()
        val chunkCount = input.chunks.size
        val prints = AtomicReferenceArray<LidBenchPcmPrint?>(chunkCount)
        val transcriber = newTranscriber(detector, decoderFactory(), recorder, extraction) { index, pcm ->
            if (index in 0 until chunkCount) prints.set(index, LidBenchPcmPrint.of(pcm))
        }
        val languages = arrayOfNulls<String>(chunkCount)
        val powerSave = deviceState.isPowerSaveMode()
        val thermalStart = deviceState.thermalStatus()
        val headroomStart = deviceState.thermalHeadroom()
        var backgrounded = !isForeground()
        output.emit(
            LidBenchLevel.INFO,
            "RUN_START engine=${engine.label} mode=${mode.label} ext=${extraction.label} chunks=$chunkCount " +
                "thermal=$thermalStart powerSave=$powerSave",
        )
        val statsBefore = detector.inferenceStats()
        val extractBefore = transcriber.languageOnlyExtractionTimings()
        val cpuStartMs = cpuTimeMs()
        val startNanos = System.nanoTime()
        var error: Exception? = null
        try {
            transcriber.detectChunkLanguages(
                audioFile = input.audioFile,
                chunks = input.chunks,
                languagePair = languagePair,
                parallelism = mode.parallelism,
                computeDispatcher = inferenceDispatcher,
            ) { index, result ->
                languages[index] = result?.language?.value
                if (!isForeground()) backgrounded = true
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            error = e
        }
        val totalMs = (System.nanoTime() - startNanos) / 1e6
        val cpuMs = cpuTimeMs() - cpuStartMs
        val statsAfter = detector.inferenceStats()
        val delta = statsAfter.since(statsBefore)
        val extractDelta = transcriber.languageOnlyExtractionTimings().since(extractBefore)
        val extractionUsed = transcriber.lastLanguageOnlyExtractionMethod
        val thermalEnd = deviceState.thermalStatus()
        val headroomEnd = deviceState.thermalHeadroom()
        if (!isForeground()) backgrounded = true
        val diagnostics = transcriber.recentDiagnostics.value
        transcriber.releaseIdleResources()
        for (entry in diagnostics) {
            output.emit(
                if (entry.isWarningOrError) LidBenchLevel.WARN else LidBenchLevel.INFO,
                "DIAG engine=${engine.label} mode=${mode.label} ext=${extraction.label} ${entry.stage}: ${entry.message}",
            )
        }

        val degraded = mode.parallelism > 1 &&
            diagnostics.any { it.message == AndroidSpeechRecognizerChunkTranscriber.PARALLEL_LID_CONSTRAINED_MESSAGE }
        val status = when {
            error != null -> LidBenchStatus.ERROR
            engine == LidBenchEngine.ONNX && delta.kotlinRuns > 0L -> LidBenchStatus.FALLBACK
            delta.onnxRuns + delta.kotlinRuns == 0L -> LidBenchStatus.NO_INFERENCE
            else -> LidBenchStatus.OK
        }
        val note = when (status) {
            LidBenchStatus.ERROR -> "例外 ${error?.javaClass?.simpleName}: ${error?.message}"

            LidBenchStatus.FALLBACK ->
                "純 Kotlin に切り替わった推論が ${delta.kotlinRuns} 件ある: ${statsAfter.onnxUnavailableReason ?: "理由は不明"}"

            LidBenchStatus.NO_INFERENCE -> "推論が 1 回もなかった"

            else -> null
        }
        val engineRuns = if (engine == LidBenchEngine.ONNX) delta.onnxRuns else delta.kotlinRuns
        val engineNanos = if (engine == LidBenchEngine.ONNX) delta.onnxNanos else delta.kotlinNanos
        return LidBenchRunResult(
            engine = engine,
            mode = mode,
            status = status,
            languages = languages.toList(),
            totalMs = totalMs,
            cpuMs = cpuMs,
            inferMs = recorder.nanos.map { it / 1e6 },
            nullAnalyses = recorder.nullCount.get(),
            engineMeanMs = if (engineRuns > 0L) engineNanos / 1e6 / engineRuns else null,
            melMeanMs = if (delta.melRuns > 0L) delta.melNanos / 1e6 / delta.melRuns else null,
            onnxRuns = delta.onnxRuns,
            kotlinRuns = delta.kotlinRuns,
            workers = if (mode.parallelism > 1 && !degraded) mode.parallelism else 1,
            degraded = degraded,
            thermalStart = thermalStart,
            thermalEnd = thermalEnd,
            headroomStart = headroomStart,
            headroomEnd = headroomEnd,
            powerSave = powerSave,
            backgrounded = backgrounded,
            extraction = extraction,
            extractionUsed = extractionUsed,
            extract = extractDelta,
            pcm = List(chunkCount) { prints.get(it) },
            note = note,
        )
    }

    /**
     * 本番と同じ一括言語判定を、[detector] で行う文字起こし器（音声認識は使わない）。
     *
     * @param extraction 一括言語判定の音声の切り出しの方式。
     * @param pcmObserver 切り出した PCM をチャンクの番号と一緒に受け取る（複数のスレッドから呼ばれる）。
     */
    private fun newTranscriber(
        detector: WhisperTinyLanguageDetector,
        decoder: AudioStreamDecoder,
        recorder: InferenceRecorder?,
        extraction: LidExtractionMethod = LidExtractionMethod.CURRENT,
        pcmObserver: ((index: Int, pcm: ShortArray) -> Unit)? = null,
    ): AndroidSpeechRecognizerChunkTranscriber = AndroidSpeechRecognizerChunkTranscriber(
        context = context,
        audioStreamDecoder = decoder,
        onDeviceAvailabilityChecker = { false },
        standardAvailabilityChecker = { false },
        languageAnalyzer = { pcm, target, base ->
            val startNanos = System.nanoTime()
            val analysis = detector.analyzeLanguagePair(pcm, target, base)
            if (recorder != null) {
                if (analysis != null) {
                    recorder.nanos.add(System.nanoTime() - startNanos)
                } else {
                    recorder.nullCount.incrementAndGet()
                }
            }
            analysis
        },
        languageDetectionStats = { detector.inferenceStats() },
        languageOnlyExtractionMethod = extraction,
        streamingExtractionOptions = config.streaming,
        languageOnlyPcmObserver = pcmObserver,
    )

    private fun skippedResult(
        engine: LidBenchEngine,
        mode: LidBenchMode,
        extraction: LidExtractionMethod,
        reason: String,
    ): LidBenchRunResult = LidBenchRunResult(
        engine = engine,
        mode = mode,
        status = LidBenchStatus.SKIPPED,
        languages = List(input.chunks.size) { null },
        totalMs = 0.0,
        cpuMs = 0L,
        inferMs = emptyList(),
        nullAnalyses = 0,
        engineMeanMs = null,
        melMeanMs = null,
        onnxRuns = 0L,
        kotlinRuns = 0L,
        workers = 0,
        degraded = false,
        thermalStart = deviceState.thermalStatus(),
        thermalEnd = deviceState.thermalStatus(),
        headroomStart = Float.NaN,
        headroomEnd = Float.NaN,
        powerSave = deviceState.isPowerSaveMode(),
        backgrounded = false,
        extraction = extraction,
        note = "${engine.label} を使えないため計測しなかった: $reason",
    )

    /** 最後に、各回の結果（判定と PCM の一致を含む）、判定や PCM が違うチャンク、表、比を出す。 */
    private fun report(results: List<LidBenchRunResult>) {
        val references = results.map { LidBenchReport.referenceFor(it, results) }
        val agreements = results.mapIndexed { index, run -> LidBenchReport.agreement(run, references[index]) }
        val pcmReferences = results.map { LidBenchReport.pcmReferenceFor(it, results) }
        val pcmAgreements = results.mapIndexed { index, run -> LidBenchReport.pcmAgreement(run, pcmReferences[index]) }
        results.forEachIndexed { index, run ->
            output.emit(
                levelOf(run),
                LidBenchReport.runLine("RESULT", config.name, run, agreements[index], withAgreement = true, pcmAgreement = pcmAgreements[index]),
            )
        }
        results.forEachIndexed { index, run ->
            val reference = references[index] ?: return@forEachIndexed
            LidBenchReport.diffLine(run, reference, input.chunks)?.let { output.emit(LidBenchLevel.WARN, it) }
        }
        results.forEachIndexed { index, run ->
            val reference = pcmReferences[index] ?: return@forEachIndexed
            LidBenchReport.pcmDiffLine(run, reference, input.chunks)?.let { output.emit(LidBenchLevel.WARN, it) }
        }
        LidBenchReport.tableLines(results, agreements, pcmAgreements).forEach { output.emit(LidBenchLevel.INFO, it) }
        LidBenchReport.summaryLines(results).forEach { output.emit(LidBenchLevel.INFO, it) }
        LidBenchReport.extractionSummaryLines(results).forEach { output.emit(LidBenchLevel.INFO, it) }
    }

    private fun levelOf(result: LidBenchRunResult): LidBenchLevel = when (result.status) {
        LidBenchStatus.OK -> if (result.degraded || result.backgrounded) LidBenchLevel.WARN else LidBenchLevel.INFO
        LidBenchStatus.SKIPPED, LidBenchStatus.NO_INFERENCE -> LidBenchLevel.WARN
        LidBenchStatus.FALLBACK, LidBenchStatus.ERROR -> LidBenchLevel.ERROR
    }

    companion object {
        /** 端末の温度の状態を確かめる間隔。 */
        private const val THERMAL_POLL_INTERVAL_MS: Long = 5_000L

        /** 空白を含まない 1 語にする（`key=value` の値に使う）。 */
        private fun token(value: String?): String = value?.trim()?.replace(Regex("\\s+"), "_")?.takeIf { it.isNotEmpty() } ?: "unknown"

        /** [engine] の判定器を作る（純 Kotlin は [LidEngineMode.KOTLIN_ONLY]、ONNX Runtime は本番と同じ [LidEngineMode.AUTO]）。 */
        fun createDetector(context: Context, engine: LidBenchEngine): WhisperTinyLanguageDetector = when (engine) {
            LidBenchEngine.KOTLIN -> WhisperTinyLanguageDetector(context, LidEngineMode.KOTLIN_ONLY)
            LidBenchEngine.ONNX -> WhisperTinyLanguageDetector(context, LidEngineMode.AUTO)
        }
    }
}
