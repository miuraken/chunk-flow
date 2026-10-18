package com.miuraken.chunkflow.core.media

import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import java.io.File
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * ONNX Runtime による言語判定の推論（[OnnxWhisperLidEngine]）と、純 Kotlin の推論への切り替えのテスト。
 *
 * 音声は合成する（倍音を持つ声のような音・雑音・無音）。
 */
@RunWith(RobolectricTestRunner::class)
class WhisperLidOnnxRuntimeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `computeRawOutputs_ONNXRuntimeの推論は純Kotlinの推論と許容誤差内で一致する`() {
        val onnx = WhisperTinyLanguageDetector(context, LidEngineMode.AUTO)
        val kotlin = WhisperTinyLanguageDetector(context, LidEngineMode.KOTLIN_ONLY)
        for ((i, pcm) in synthSignals().withIndex()) {
            val a = assertNotNull(onnx.computeRawOutputs(pcm))
            val b = assertNotNull(kotlin.computeRawOutputs(pcm))
            assertEquals(OnnxWhisperLidEngine.ENGINE_NAME, a.engineName, "signal$i")
            assertEquals(WhisperTinyLanguageDetector.KOTLIN_ENGINE_NAME, b.engineName, "signal$i")
            // log-mel はどちらも同じ Kotlin のコードで計算する
            assertContentEquals(b.mel300x80, a.mel300x80, "signal$i")
            val logitsError = maxAbsDiff(a.logits, b.logits)
            val featuresError = maxAbsDiff(a.audioFeatures, b.audioFeatures)
            assertTrue(logitsError < LOGITS_TOLERANCE, "signal$i: ロジットの最大誤差 $logitsError")
            assertTrue(featuresError < FEATURES_TOLERANCE, "signal$i: エンコーダ出力の最大誤差 $featuresError")
            assertEquals(argmax(b.logits), argmax(a.logits), "signal$i: 最も確率の高い言語")
        }
    }

    @Test
    fun `analyzeLanguagePair_ONNXRuntimeとKotlinで2言語の確率と平均ベクトルが一致する`() {
        val onnx = WhisperTinyLanguageDetector(context, LidEngineMode.AUTO)
        val kotlin = WhisperTinyLanguageDetector(context, LidEngineMode.KOTLIN_ONLY)
        for ((i, pcm) in synthSignals().withIndex()) {
            for ((target, base) in listOf("ko" to "ja", "hi" to "en")) {
                val a = assertNotNull(onnx.analyzeLanguagePair(pcm, target, base))
                val b = assertNotNull(kotlin.analyzeLanguagePair(pcm, target, base))
                val label = "signal$i $target/$base"
                assertTrue(abs(a.prediction.targetProbability - b.prediction.targetProbability) < 1e-3f, label)
                assertTrue(abs(a.prediction.pairMass - b.prediction.pairMass) < 1e-3f, label)
                if (abs(b.prediction.targetProbability - 0.5f) > 0.01f) {
                    assertEquals(b.prediction.selectedTag, a.prediction.selectedTag, label)
                }
                assertTrue(maxAbsDiff(a.encoderEmbedding, b.encoderEmbedding) < 1e-3f, label)
            }
        }
    }

    @Test
    fun `analyzeLanguagePair_ONNXがない場合は純Kotlinで推論しONNXRuntimeを使えない理由を残す`() {
        val detector = WhisperTinyLanguageDetector(
            context = context,
            engineMode = LidEngineMode.AUTO,
            onnxModelSource = { null },
            neuralEngineFactory = null,
        )
        val reference = WhisperTinyLanguageDetector(context, LidEngineMode.KOTLIN_ONLY)
        val pcm = synthSignals()[2]

        val result = assertNotNull(detector.analyzeLanguagePair(pcm, "ko", "ja"))
        val expected = assertNotNull(reference.analyzeLanguagePair(pcm, "ko", "ja"))

        assertEquals(expected.prediction.targetProbability, result.prediction.targetProbability)
        assertContentEquals(expected.encoderEmbedding, result.encoderEmbedding)
        val stats = detector.inferenceStats()
        assertEquals(0L, stats.onnxRuns)
        assertEquals(1L, stats.kotlinRuns)
        assertEquals("ONNX のファイルがない", stats.onnxUnavailableReason)
        assertNull(stats.onnxLoadTimeMs)
    }

    @Test
    fun `analyzeLanguagePair_壊れたONNXの場合は純Kotlinで推論し読み込みの失敗を残す`() {
        val broken = ByteArray(4_096) { (it * 31 + 7).toByte() }
        val detector = WhisperTinyLanguageDetector(
            context = context,
            engineMode = LidEngineMode.AUTO,
            onnxModelSource = { broken },
            neuralEngineFactory = null,
        )
        val reference = WhisperTinyLanguageDetector(context, LidEngineMode.KOTLIN_ONLY)
        val pcm = synthSignals()[1]

        val result = assertNotNull(detector.analyzeLanguagePair(pcm, "hi", "en"))
        val expected = assertNotNull(reference.analyzeLanguagePair(pcm, "hi", "en"))

        assertEquals(expected.prediction.targetProbability, result.prediction.targetProbability)
        val stats = detector.inferenceStats()
        assertEquals(0L, stats.onnxRuns)
        assertEquals(1L, stats.kotlinRuns)
        val reason = assertNotNull(stats.onnxUnavailableReason)
        assertTrue(reason.startsWith("読み込みに失敗"), reason)
    }

    @Test
    fun `analyzeLanguagePair_推論に失敗したらそのチャンクを純Kotlinで推論し以後はONNXRuntimeを使わない`() {
        val runCalls = AtomicInteger(0)
        val failingEngine = object : WhisperLidNeuralEngine {
            override val engineName: String = "failing"

            override fun run(mel300x80: FloatArray, logitsOut: FloatArray, audioFeaturesOut: FloatArray) {
                runCalls.incrementAndGet()
                throw IllegalStateException("テスト用の失敗")
            }
        }
        val detector = WhisperTinyLanguageDetector(
            context = context,
            engineMode = LidEngineMode.AUTO,
            onnxModelSource = { ByteArray(1) },
            neuralEngineFactory = { failingEngine },
        )
        val pcm = synthSignals()[3]

        assertNotNull(detector.analyzeLanguagePair(pcm, "ko", "ja"))
        assertNotNull(detector.analyzeLanguagePair(pcm, "ko", "ja"))

        assertEquals(1, runCalls.get(), "失敗した後は ONNX Runtime を呼ばない")
        val stats = detector.inferenceStats()
        assertEquals(0L, stats.onnxRuns)
        assertEquals(2L, stats.kotlinRuns)
        assertEquals("推論に失敗 (IllegalStateException)", stats.onnxUnavailableReason)
    }

    @Test
    fun `analyzeLanguagePair_ONNXの読み込みは1回だけで複数のスレッドから同時に推論しても1件ずつと同じ結果になる`() {
        val loads = AtomicInteger(0)
        val onnxBytes = File("src/main/assets/${OnnxWhisperLidEngine.ASSET_FILE_NAME}").readBytes()
        val detector = WhisperTinyLanguageDetector(
            context = context,
            engineMode = LidEngineMode.AUTO,
            onnxModelSource = {
                loads.incrementAndGet()
                onnxBytes
            },
            neuralEngineFactory = null,
        )
        val signals = synthSignals()
        val sequential = signals.map { assertNotNull(detector.analyzeLanguagePair(it, "ko", "ja")) }

        val pool = Executors.newFixedThreadPool(3)
        try {
            val parallel = signals.map { pcm ->
                pool.submit(Callable { detector.analyzeLanguagePair(pcm, "ko", "ja") })
            }.map { assertNotNull(it.get()) }
            for (i in signals.indices) {
                assertEquals(sequential[i].prediction.targetProbability, parallel[i].prediction.targetProbability, "signal$i")
                assertContentEquals(sequential[i].encoderEmbedding, parallel[i].encoderEmbedding, "signal$i")
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, loads.get())
        val stats = detector.inferenceStats()
        assertEquals(signals.size * 2L, stats.onnxRuns)
        assertEquals(0L, stats.kotlinRuns)
        assertNotNull(stats.onnxLoadTimeMs)
        assertNull(stats.onnxUnavailableReason)
    }

    @Test
    fun `analyzeLanguagePair_最初の推論を複数のスレッドが同時に始めても読み込みを待って全件ONNXRuntimeで推論する`() {
        val loads = AtomicInteger(0)
        val onnxBytes = File("src/main/assets/${OnnxWhisperLidEngine.ASSET_FILE_NAME}").readBytes()
        val detector = WhisperTinyLanguageDetector(
            context = context,
            engineMode = LidEngineMode.AUTO,
            onnxModelSource = {
                loads.incrementAndGet()
                // 読み込みに時間がかかる状況を作り、ほかのスレッドが読み込みの途中に推論を始めるようにする
                Thread.sleep(300L)
                onnxBytes
            },
            neuralEngineFactory = null,
        )
        val signals = synthSignals()
        val pool = Executors.newFixedThreadPool(signals.size)
        try {
            signals.map { pcm -> pool.submit(Callable { detector.analyzeLanguagePair(pcm, "ko", "ja") }) }
                .forEach { assertNotNull(it.get()) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(1, loads.get())
        val stats = detector.inferenceStats()
        assertEquals(signals.size.toLong(), stats.onnxRuns)
        assertEquals(0L, stats.kotlinRuns, "読み込みの途中に純 Kotlin へ切り替えない")
    }

    @Test
    fun `describeLanguageDetectionEngine_onnxruntimeだけで推論した場合は件数と平均時間と読み込み時間を出す`() {
        val text = AndroidSpeechRecognizerChunkTranscriber.describeLanguageDetectionEngine(
            LidInferenceStats(
                onnxRuns = 4,
                onnxNanos = 4 * 31_200_000L,
                melRuns = 4,
                melNanos = 4 * 4_800_000L,
                onnxLoadTimeMs = 142,
            ),
        )
        assertEquals("推論エンジン: onnxruntime（4 件、推論 平均 31.2 ms/チャンク、log-mel 平均 4.8 ms、ONNX の読み込み 142 ms）", text)
    }

    @Test
    fun `describeLanguageDetectionEngine_純Kotlinに切り替えた場合はONNXRuntimeを使えない理由を出す`() {
        val kotlinOnly = AndroidSpeechRecognizerChunkTranscriber.describeLanguageDetectionEngine(
            LidInferenceStats(
                kotlinRuns = 2,
                kotlinNanos = 2 * 826_000_000L,
                melRuns = 2,
                melNanos = 2 * 4_800_000L,
                onnxUnavailableReason = "ONNX のファイルがない",
            ),
        )
        assertEquals(
            "推論エンジン: kotlin（2 件、推論 平均 826.0 ms/チャンク、log-mel 平均 4.8 ms）。onnxruntime を使えない: ONNX のファイルがない",
            kotlinOnly,
        )
        val mixed = AndroidSpeechRecognizerChunkTranscriber.describeLanguageDetectionEngine(
            LidInferenceStats(
                onnxRuns = 3,
                onnxNanos = 3 * 30_000_000L,
                kotlinRuns = 1,
                kotlinNanos = 800_000_000L,
                melRuns = 4,
                melNanos = 4 * 5_000_000L,
                onnxLoadTimeMs = 150,
                onnxUnavailableReason = "推論に失敗 (OrtException)",
            ),
        )
        assertEquals(
            "推論エンジン: onnxruntime 3 件とkotlin 1 件（推論 平均 30.0 ms / 800.0 ms/チャンク、log-mel 平均 5.0 ms、" +
                "ONNX の読み込み 150 ms）。onnxruntime を使えない: 推論に失敗 (OrtException)",
            mixed,
        )
    }

    @Test
    fun `detectChunkLanguages_一括言語判定の終わりに推論エンジンと推論時間を診断ログに残す`() = runTest {
        val detector = WhisperTinyLanguageDetector(context, LidEngineMode.AUTO)
        val signal = synthSignals()[5]
        val decoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                val samples = FloatArray(outputSampleRate * 4) { idx -> signal[idx % signal.size] / 32768f }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = samples.size.toLong(),
                    resampledSamples = samples.size.toLong(),
                    durationMs = 4_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/wav",
                )
            }
        }
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = decoder,
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            languageAnalyzer = { pcm, target, base -> detector.analyzeLanguagePair(pcm, target, base) },
            languageDetectionStats = { detector.inferenceStats() },
        )
        val audioFile = tempFolder.newFile("engine_diag.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val chunks = listOf(ChunkTimeRange(0L, 1_500L), ChunkTimeRange(1_500L, 3_000L))
        val executor = Executors.newFixedThreadPool(2)
        val results = mutableListOf<OnDeviceChunkTranscriptionResult?>()
        try {
            transcriber.detectChunkLanguages(
                audioFile = audioFile,
                chunks = chunks,
                languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
                parallelism = 2,
                computeDispatcher = executor.asCoroutineDispatcher(),
            ) { _, result -> results += result }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(2, results.size)
        val engineLine = transcriber.recentDiagnostics.value.lastOrNull { it.message.startsWith("推論エンジン:") }
        assertNotNull(engineLine)
        assertTrue(engineLine.message.startsWith("推論エンジン: onnxruntime（2 件、"), engineLine.message)
        assertTrue(!engineLine.isWarningOrError)
    }

    private fun maxAbsDiff(a: FloatArray, b: FloatArray): Float {
        assertEquals(a.size, b.size)
        var maxDiff = 0f
        for (i in a.indices) {
            val d = abs(a[i] - b[i])
            if (d > maxDiff) maxDiff = d
        }
        return maxDiff
    }

    private fun argmax(values: FloatArray): Int = values.indices.maxBy { values[it] }

    /** 倍音を持つ声のような音（基本周波数・ビブラート・振幅の揺れを変える）に雑音を足し、一部は先頭を無音にする。 */
    private fun synthSignals(): List<ShortArray> {
        val random = Random(3)
        val sampleRate = 16_000
        return (0 until 6).map { i ->
            val length = (sampleRate * (0.6 + 0.5 * i)).toInt()
            val f0 = 110.0 + 40.0 * i
            ShortArray(length) { n ->
                val t = n.toDouble() / sampleRate
                var s = 0.0
                for (h in 1 until 12) {
                    s += sin(2.0 * PI * f0 * h * t * (1.0 + 0.02 * sin(2.0 * PI * 4.0 * t))) / h
                }
                s *= 0.5 * (1.0 + sin(2.0 * PI * (2.0 + i) * t)) * 0.2
                s += 0.01 * random.nextGaussian()
                if (i % 2 == 1 && n < length / 5) s = 0.0
                (s * 32_767.0).toInt().coerceIn(-32_768, 32_767).toShort()
            }
        }
    }

    private companion object {
        /** ロジットの許容誤差（Mac の実測の最大は約 1e-4。演算の順序の違いによる丸め誤差だけを許す）。 */
        const val LOGITS_TOLERANCE: Float = 2e-3f

        /** エンコーダ出力（LayerNorm 後、最大値 20 程度）の許容誤差（Mac の実測の最大は約 1e-3）。 */
        const val FEATURES_TOLERANCE: Float = 1e-2f
    }
}
