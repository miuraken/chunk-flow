package com.miuraken.chunkflow.core.media.bench

import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.media.AudioDecodeResult
import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import com.miuraken.chunkflow.core.media.ChunkTimeRange
import com.miuraken.chunkflow.core.media.DecodeProgress
import com.miuraken.chunkflow.core.media.LidEngineMode
import com.miuraken.chunkflow.core.media.WhisperLidNeuralEngine
import com.miuraken.chunkflow.core.media.WhisperTinyLanguageDetector
import java.io.File
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 実機計測ツールの流れ（ウォームアップ → 計測 → 結果の行）のテスト。
 *
 * 本物の判定器（純 Kotlin と ONNX Runtime）を使い、音声は合成して、本番のデコーダの代わりにメモリの PCM を流す。
 */
@RunWith(RobolectricTestRunner::class)
class LidBenchRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()

    /** 端末の状態（温度の状態は NONE、省電力モードでない）。 */
    private class FakeDeviceState : LidBenchDeviceState {
        override fun thermalStatus(): Int = 0

        override fun thermalHeadroom(): Float = Float.NaN

        override fun isPowerSaveMode(): Boolean = false
    }

    /** 先頭から PCM を流すデコーダ（チャンクの切り出しは ChunkPcmExtractor が必要な区間で打ち切る）。 */
    private class PcmDecoder(private val samples: FloatArray) : AudioStreamDecoder {
        override suspend fun decode(
            file: File,
            sink: SampleSink,
            outputSampleRate: Int,
            expectedDurationMs: Long?,
            onProgress: suspend (DecodeProgress) -> Unit,
        ): AudioDecodeResult {
            val block = FloatArray(BLOCK_SAMPLES)
            var position = 0
            while (position < samples.size) {
                val count = minOf(block.size, samples.size - position)
                System.arraycopy(samples, position, block, 0, count)
                sink.onSamples(block, count)
                position += count
            }
            return AudioDecodeResult.Success(
                sampleRate = outputSampleRate,
                channels = 1,
                decodedFrames = samples.size.toLong(),
                resampledSamples = samples.size.toLong(),
                durationMs = samples.size * 1_000L / SAMPLE_RATE,
                encoderDelay = 0,
                encoderPadding = 0,
                mimeType = "audio/raw",
            )
        }
    }

    /** 必ず失敗する推論エンジン（ONNX Runtime の推論の失敗の代わり）。 */
    private class FailingEngine : WhisperLidNeuralEngine {
        override val engineName: String = "failing"

        override fun run(mel300x80: FloatArray, logitsOut: FloatArray, audioFeaturesOut: FloatArray): Unit = throw IllegalStateException("推論に失敗した（テスト）")
    }

    private class Fixture(val input: LidBenchInput, val samples: FloatArray)

    /** 3 つのチャンク（声のような倍音を持つ音。チャンクごとに高さを変える）の音声を作る。 */
    private fun fixture(): Fixture {
        val random = Random(7)
        val chunks = (0 until CHUNK_COUNT).map { i -> ChunkTimeRange(startMs = i * 3_000L + 200L, endMs = i * 3_000L + 2_700L) }
        val samples = FloatArray(SAMPLE_RATE * 3 * CHUNK_COUNT)
        for ((i, chunk) in chunks.withIndex()) {
            val f0 = 120.0 + 60.0 * i
            val from = (chunk.startMs * SAMPLE_RATE / 1_000L).toInt()
            val to = (chunk.endMs * SAMPLE_RATE / 1_000L).toInt()
            for (n in from until to) {
                val t = (n - from).toDouble() / SAMPLE_RATE
                var s = 0.0
                for (h in 1 until 10) s += sin(2.0 * PI * f0 * h * t) / h
                s *= 0.5 * (1.0 + sin(2.0 * PI * 3.0 * t)) * 0.2
                samples[n] = (s + 0.005 * random.nextGaussian()).toFloat().coerceIn(-1f, 1f)
            }
        }
        val audio = tempFolder.newFile("bench.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        return Fixture(LidBenchInput(audioFile = audio, chunks = chunks, totalChunks = chunks.size), samples)
    }

    private fun config(engines: List<LidBenchEngine>, modes: List<LidBenchMode>, warmupChunks: Int): LidBenchConfig = LidBenchConfig(
        name = "bench",
        targetTag = "ko",
        baseTag = "ja",
        audioFileName = "bench.mp3",
        warmupChunks = warmupChunks,
        cooldownSec = 0,
        thermalWaitSec = 0,
        engines = engines,
        modes = modes,
    )

    private suspend fun runBench(
        config: LidBenchConfig,
        fixture: Fixture,
        lines: MutableList<String>,
        detectorFactory: ((LidBenchEngine) -> WhisperTinyLanguageDetector)? = null,
    ): List<LidBenchRunResult> {
        val executor = Executors.newFixedThreadPool(3)
        try {
            return LidBenchRunner(
                context = context,
                config = config,
                input = fixture.input,
                inferenceDispatcher = executor.asCoroutineDispatcher(),
                output = { _, line -> synchronized(lines) { lines += line } },
                deviceState = FakeDeviceState(),
                detectorFactory = detectorFactory ?: { engine -> LidBenchRunner.createDetector(context, engine) },
                decoderFactory = { PcmDecoder(fixture.samples) },
                cpuTimeMs = { 0L },
                sleep = {},
            ).run()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `run_両エンジンを逐次と3並列で測り判定の一致と表を出す`() = runTest {
        val lines = mutableListOf<String>()
        val results = runBench(config(LidBenchEngine.entries, LidBenchMode.entries, warmupChunks = 1), fixture(), lines)

        assertEquals(listOf("kotlin/seq", "kotlin/par3", "onnx/seq", "onnx/par3"), results.map { it.key })
        for (run in results) {
            assertEquals(LidBenchStatus.OK, run.status, run.key)
            assertEquals(CHUNK_COUNT, run.chunkCount, run.key)
            assertEquals(CHUNK_COUNT, run.detectedCount, run.key)
            assertEquals(CHUNK_COUNT, run.inferMs.size, run.key)
            assertEquals(run.mode.parallelism, run.workers, run.key)
            val engineRuns = if (run.engine == LidBenchEngine.ONNX) run.onnxRuns else run.kotlinRuns
            val otherRuns = if (run.engine == LidBenchEngine.ONNX) run.kotlinRuns else run.onnxRuns
            assertEquals(CHUNK_COUNT.toLong(), engineRuns, run.key)
            assertEquals(0L, otherRuns, run.key)
        }
        // 純 Kotlin と ONNX Runtime、逐次と並列で、最終判定は同じ
        for (run in results.drop(1)) assertEquals(results[0].languages, run.languages, run.key)

        assertTrue(lines.first().startsWith("START name=bench target=ko base=ja chunks=3/3 "), lines.first())
        assertTrue(lines.any { it.startsWith("WARMUP engine=kotlin chunks=1 ") }, lines.joinToString("\n"))
        assertTrue(lines.any { it.startsWith("ONNX_LOAD ok loadMs=") }, lines.joinToString("\n"))
        val onnxPar = lines.single { it.startsWith("RESULT name=bench engine=onnx mode=par3 ") }
        assertTrue(onnxPar.contains(" status=OK "), onnxPar)
        assertTrue(onnxPar.endsWith(" agree=1.000 agreeN=3/3 ref=kotlin/par3"), onnxPar)
        assertTrue(lines.any { it.startsWith("TABLE engine ") }, lines.joinToString("\n"))
        assertTrue(lines.any { it.startsWith("SUMMARY mode=par3 ") }, lines.joinToString("\n"))
        assertTrue(lines.none { it.startsWith("DIFF ") }, lines.joinToString("\n"))
        assertTrue(lines.last().startsWith("END name=bench runs=4 ok=4 "), lines.last())
    }

    @Test
    fun `run_ONNXRuntimeを読み込めない場合はONNXRuntimeの回を計測せず理由を出す`() = runTest {
        val lines = mutableListOf<String>()
        val results = runBench(
            config(listOf(LidBenchEngine.ONNX), LidBenchMode.entries, warmupChunks = 1),
            fixture(),
            lines,
            detectorFactory = {
                WhisperTinyLanguageDetector(context, LidEngineMode.AUTO, onnxModelSource = { null }, neuralEngineFactory = null)
            },
        )

        assertEquals(listOf(LidBenchStatus.SKIPPED, LidBenchStatus.SKIPPED), results.map { it.status })
        assertTrue(lines.any { it.startsWith("ERROR ONNX_UNAVAILABLE reason=ONNX のファイルがない") }, lines.joinToString("\n"))
        val result = lines.single { it.startsWith("RESULT name=bench engine=onnx mode=seq ") }
        assertTrue(result.contains(" status=SKIPPED "), result)
        assertTrue(lines.last().startsWith("END name=bench runs=2 ok=0 "), lines.last())
    }

    @Test
    fun `run_計測中に純Kotlinへ切り替わった回はONNXRuntimeの結果として扱わない`() = runTest {
        val lines = mutableListOf<String>()
        val results = runBench(
            config(listOf(LidBenchEngine.ONNX), listOf(LidBenchMode.SEQUENTIAL), warmupChunks = 0),
            fixture(),
            lines,
            detectorFactory = {
                WhisperTinyLanguageDetector(
                    context,
                    LidEngineMode.AUTO,
                    onnxModelSource = { ByteArray(1) },
                    neuralEngineFactory = { FailingEngine() },
                )
            },
        )

        val run = results.single()
        assertEquals(LidBenchStatus.FALLBACK, run.status)
        assertEquals(CHUNK_COUNT.toLong(), run.kotlinRuns)
        val result = lines.single { it.startsWith("RESULT name=bench engine=onnx mode=seq ") }
        assertTrue(result.contains(" status=FALLBACK "), result)
        assertTrue(result.contains("推論に失敗 (IllegalStateException)"), result)
        assertTrue(lines.none { it.startsWith("SUMMARY ") }, lines.joinToString("\n"))
    }

    private companion object {
        const val SAMPLE_RATE: Int = 16_000
        const val BLOCK_SAMPLES: Int = 16_000
        const val CHUNK_COUNT: Int = 3
    }
}
