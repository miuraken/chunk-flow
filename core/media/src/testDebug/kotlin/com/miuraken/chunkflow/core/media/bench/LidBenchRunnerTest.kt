package com.miuraken.chunkflow.core.media.bench

import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.media.AudioDecodeResult
import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import com.miuraken.chunkflow.core.media.ChunkTimeRange
import com.miuraken.chunkflow.core.media.DecodeProgress
import com.miuraken.chunkflow.core.media.FakeMedia
import com.miuraken.chunkflow.core.media.FakeMediaExtractor
import com.miuraken.chunkflow.core.media.FakeRawCodec
import com.miuraken.chunkflow.core.media.LidEngineMode
import com.miuraken.chunkflow.core.media.LidExtractionMethod
import com.miuraken.chunkflow.core.media.MediaCodecAudioStreamDecoder
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
 * 切り出しの方式の比較では、偽の抽出器と MediaCodec（`FakeMediaSessions.kt`）で本番の [MediaCodecAudioStreamDecoder] を動かす。
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

    private fun config(
        engines: List<LidBenchEngine>,
        modes: List<LidBenchMode>,
        warmupChunks: Int,
        extractions: List<LidExtractionMethod> = listOf(LidExtractionMethod.CURRENT),
    ): LidBenchConfig = LidBenchConfig(
        name = "bench",
        targetTag = "ko",
        baseTag = "ja",
        audioFileName = "bench.mp3",
        warmupChunks = warmupChunks,
        cooldownSec = 0,
        thermalWaitSec = 0,
        engines = engines,
        modes = modes,
        extractions = extractions,
    )

    /** 偽の抽出器と MediaCodec（44.1kHz の `audio/raw`。セッションの先頭で 529 サンプル削る）で動く本番のデコーダ。 */
    private fun mediaCodecDecoder(): MediaCodecAudioStreamDecoder {
        val packets = FakeMedia.rawPackets(AUDIO_DURATION_MS)
        return MediaCodecAudioStreamDecoder(
            extractorOpener = { FakeMediaExtractor(FakeMedia.rawFormat(durationMs = AUDIO_DURATION_MS), packets) },
            codecCreator = { _, _ -> FakeRawCodec(sampleRate = 44_100, trimStartSamples = 529) },
        )
    }

    private suspend fun runBench(
        config: LidBenchConfig,
        fixture: Fixture,
        lines: MutableList<String>,
        detectorFactory: ((LidBenchEngine) -> WhisperTinyLanguageDetector)? = null,
        decoderFactory: (() -> AudioStreamDecoder)? = null,
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
                decoderFactory = decoderFactory ?: { PcmDecoder(fixture.samples) },
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

        assertEquals(listOf("kotlin/seq/cur", "kotlin/par3/cur", "onnx/seq/cur", "onnx/par3/cur"), results.map { it.key })
        for (run in results) {
            assertEquals(LidBenchStatus.OK, run.status, run.key)
            assertEquals(LidExtractionMethod.CURRENT, run.extractionUsed, run.key)
            assertEquals(CHUNK_COUNT, run.pcm.count { it != null }, run.key)
            assertEquals(CHUNK_COUNT.toLong(), run.extract.extractions, run.key)
            assertEquals(CHUNK_COUNT, run.chunkCount, run.key)
            assertTrue(run.detectedCount in 0..CHUNK_COUNT, run.key)
            assertEquals(CHUNK_COUNT, run.inferMs.size, run.key)
            assertEquals(run.mode.parallelism, run.workers, run.key)
            val engineRuns = if (run.engine == LidBenchEngine.ONNX) run.onnxRuns else run.kotlinRuns
            val otherRuns = if (run.engine == LidBenchEngine.ONNX) run.kotlinRuns else run.onnxRuns
            assertEquals(CHUNK_COUNT.toLong(), engineRuns, run.key)
            assertEquals(0L, otherRuns, run.key)
        }
        // 純 Kotlin と ONNX Runtime、逐次と並列で、最終判定と切り出した PCM は同じ
        for (run in results.drop(1)) {
            assertEquals(results[0].languages, run.languages, run.key)
            assertEquals(results[0].pcm, run.pcm, run.key)
        }

        assertTrue(lines.first().startsWith("START name=bench target=ko base=ja chunks=3/3 "), lines.first())
        assertTrue(lines.any { it.startsWith("WARMUP engine=kotlin chunks=1 ") }, lines.joinToString("\n"))
        assertTrue(lines.any { it.startsWith("ONNX_LOAD ok loadMs=") }, lines.joinToString("\n"))
        val onnxPar = lines.single { it.startsWith("RESULT name=bench engine=onnx mode=par3 ") }
        assertTrue(onnxPar.contains(" status=OK "), onnxPar)
        assertTrue(onnxPar.contains(" ext=cur extUsed=cur status=OK "), onnxPar)
        assertTrue(onnxPar.contains(" exN=3 "), onnxPar)
        assertTrue(onnxPar.endsWith(" agree=1.000 agreeN=3/3 ref=kotlin/par3/cur pcmSame=NA pcmRef=-"), onnxPar)
        assertTrue(lines.any { it.startsWith("TABLE engine ") }, lines.joinToString("\n"))
        assertTrue(lines.any { it.startsWith("SUMMARY mode=par3 ext=cur ") }, lines.joinToString("\n"))
        assertTrue(lines.none { it.startsWith("SUMMARY_EXT ") }, lines.joinToString("\n"))
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

    @Test
    fun `run_切り出しの方式ごとに測り今の方式とPCMと判定が同じことと内訳を出す`() = runTest {
        val lines = mutableListOf<String>()
        val results = runBench(
            config(listOf(LidBenchEngine.ONNX), listOf(LidBenchMode.PARALLEL3), warmupChunks = 0, extractions = LidExtractionMethod.entries),
            fixture(),
            lines,
            decoderFactory = ::mediaCodecDecoder,
        )

        assertEquals(listOf("onnx/par3/cur", "onnx/par3/par", "onnx/par3/str"), results.map { it.key })
        assertEquals(LidExtractionMethod.entries, results.map { it.extractionUsed })
        for (run in results) {
            assertEquals(LidBenchStatus.OK, run.status, run.key)
            assertEquals(CHUNK_COUNT, run.pcm.count { it != null }, run.key)
            assertEquals(results[0].pcm, run.pcm, run.key)
            assertEquals(results[0].languages, run.languages, run.key)
            assertEquals(CHUNK_COUNT.toLong(), run.extractCount, run.key)
        }
        // 今の方式はチャンクごとに MediaCodec を作り、順に流す切り出しは 1 つの MediaCodec で 3 つの窓を切り出す
        assertEquals(CHUNK_COUNT.toLong(), results[0].extract.codecCreates)
        assertEquals(1L, results[2].extract.codecCreates)
        assertEquals(1L, results[2].extract.streamRuns)
        for (ext in listOf("par", "str")) {
            val line = lines.single { it.startsWith("RESULT name=bench engine=onnx mode=par3 ext=$ext ") }
            assertTrue(line.contains(" extUsed=$ext status=OK "), line)
            assertTrue(line.endsWith(" agree=1.000 agreeN=3/3 ref=onnx/par3/cur pcmSame=3/3 pcmRef=onnx/par3/cur"), line)
            val summary = lines.single { it.startsWith("SUMMARY_EXT engine=onnx mode=par3 ext=$ext ") }
            assertTrue(summary.endsWith(" agree=1.000 agreeN=3/3 pcmSame=3/3"), summary)
        }
        val streaming = lines.single { it.startsWith("RESULT name=bench engine=onnx mode=par3 ext=str ") }
        assertTrue(streaming.contains(" codecs=1 ") && streaming.contains(" runs=1 windows=3 "), streaming)
        assertTrue(lines.first().contains(" extractions=cur,par,str streamGapMs=10000 streamRunWindows=16 "), lines.first())
        assertTrue(lines.any { it.startsWith("RUN_START engine=onnx mode=par3 ext=str ") }, lines.joinToString("\n"))
        assertTrue(lines.any { it.startsWith("DIAG engine=onnx mode=par3 ext=str LID_ONLY: 切り出し: 方式 str（") }, lines.joinToString("\n"))
        assertTrue(lines.none { it.startsWith("PCMDIFF ") || it.startsWith("DIFF ") }, lines.joinToString("\n"))
        assertTrue(lines.last().startsWith("END name=bench runs=3 ok=3 "), lines.last())
    }

    @Test
    fun `run_速い切り出しを使えないデコーダなら今の方式で測り実際の方式を出す`() = runTest {
        val lines = mutableListOf<String>()
        val results = runBench(
            config(listOf(LidBenchEngine.ONNX), listOf(LidBenchMode.SEQUENTIAL), warmupChunks = 0, extractions = listOf(LidExtractionMethod.PARALLEL)),
            fixture(),
            lines,
        )

        val run = results.single()
        assertEquals(LidBenchStatus.OK, run.status)
        assertEquals(LidExtractionMethod.PARALLEL, run.extraction)
        assertEquals(LidExtractionMethod.CURRENT, run.extractionUsed)
        val result = lines.single { it.startsWith("RESULT name=bench engine=onnx mode=seq ext=par ") }
        assertTrue(result.contains(" extUsed=cur status=OK "), result)
        assertTrue(lines.any { it.startsWith("TABLE onnx   seq  par>cur OK ") }, lines.joinToString("\n"))
    }

    private companion object {
        const val SAMPLE_RATE: Int = 16_000
        const val AUDIO_DURATION_MS: Long = 10_000L
        const val BLOCK_SAMPLES: Int = 16_000
        const val CHUNK_COUNT: Int = 3
    }
}
