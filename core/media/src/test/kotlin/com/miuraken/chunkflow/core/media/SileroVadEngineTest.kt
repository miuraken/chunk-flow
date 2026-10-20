package com.miuraken.chunkflow.core.media

import com.miuraken.chunkflow.core.audio.ChunkSegmenter
import com.miuraken.chunkflow.core.audio.FeatureAccumulator
import com.miuraken.chunkflow.core.audio.FrameFeatureExtractor
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.SegmentationParams
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SileroVadEngineTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `SileroVadEngine_無音波形では発話確率が低くストリーミング入力から10ms単位の確率配列を生成できる`() {
        val engine = SileroVadEngine(context)
        val stream = assertNotNull(engine.createStream())

        val sampleRate = 16_000
        val silentSamples = FloatArray(sampleRate * 2) // 2.0s 無音
        stream.onSamples(silentSamples, silentSamples.size)

        val current = stream.currentProbabilities(targetFrameCount = 200, binMs = 10)
        assertEquals(200, current.size)
        assertTrue(current.all { it in 0.0f..0.15f }, "無音の最大確率が高すぎます: max=${current.maxOrNull()}")

        val finalProbs = stream.finish(targetFrameCount = 200, binMs = 10)
        assertEquals(200, finalProbs.size)
        assertTrue(finalProbs.all { it in 0.0f..0.15f })
    }

    @Test
    fun `SileroVadEngine_壊れたモデルバイト列の場合は例外を投げずにnullを返す`() {
        val brokenEngine = SileroVadEngine(
            context = context,
            modelBytesSource = { ByteArray(128) { 0x42 } },
        )
        assertNull(brokenEngine.createStream())
    }

    @Test
    fun `SileroVadEngine_合成60秒音声で直列推論とウォームアップ付きブロック並列推論の精度と速度を比較検証する`() {
        val sampleRate = 16_000
        val durationSec = 60
        val totalSamples = sampleRate * durationSec
        val syntheticSamples = FloatArray(totalSamples)
        // 2.0秒発話区間（基本波 + 倍音 + 振幅変調）+ 1.0秒無音 を 20 周期（計 60 秒）生成
        for (cycle in 0 until 20) {
            val startSample = cycle * 3 * sampleRate
            val speechSamples = 2 * sampleRate
            for (i in 0 until speechSamples) {
                val t = i.toDouble() / sampleRate
                val env = 0.5 + 0.4 * sin(2.0 * Math.PI * 3.5 * t)
                val carrier =
                    0.55 * sin(2.0 * Math.PI * 180.0 * t) +
                        0.30 * sin(2.0 * Math.PI * 540.0 * t) +
                        0.15 * sin(2.0 * Math.PI * 1080.0 * t)
                syntheticSamples[startSample + i] = (env * carrier).toFloat()
            }
        }

        val targetFrames = durationSec * 100
        val durationMs = durationSec * 1_000L
        val segmenter = ChunkSegmenter()

        data class BenchResult(
            val label: String,
            val warmupWindows: Int,
            val vadOnlyMs: Long,
            val pipelineMs: Long,
            val probs: FloatArray,
            val energy: FloatArray,
            val chunks: List<Chunk>,
            val hasBgm: Boolean,
        )

        fun runBench(label: String, engine: SileroVadEngine): BenchResult {
            // JIT & ONNX Session ウォームアップ
            engine.createStream()?.let { s ->
                s.onSamples(syntheticSamples, 16_000)
                s.finish(100, 10)
            }

            // 1) VAD 単体計測
            val startVadNs = System.nanoTime()
            val vadStreamOnly = assertNotNull(engine.createStream())
            var offset = 0
            val step = 4_096
            while (offset < syntheticSamples.size) {
                val len = minOf(step, syntheticSamples.size - offset)
                vadStreamOnly.onSamples(syntheticSamples.copyOfRange(offset, offset + len), len)
                offset += len
            }
            vadStreamOnly.finish(targetFrameCount = targetFrames, binMs = 10)
            val vadOnlyMs = (System.nanoTime() - startVadNs) / 1_000_000L

            // 2) FeatureExtractor + VAD パイプライン計測
            val startPipeNs = System.nanoTime()
            val acc = FeatureAccumulator(binMs = 10)
            val extractor = FrameFeatureExtractor(sampleRate = sampleRate, binMs = 10, sink = acc)
            val vadStream = assertNotNull(engine.createStream())
            offset = 0
            while (offset < syntheticSamples.size) {
                val len = minOf(step, syntheticSamples.size - offset)
                val slice = syntheticSamples.copyOfRange(offset, offset + len)
                extractor.process(slice, len)
                vadStream.onSamples(slice, len)
                offset += len
            }
            extractor.finish()
            val energy = acc.logEnergyDb()
            val probs = vadStream.finish(targetFrameCount = energy.size, binMs = 10)
            val pipelineMs = (System.nanoTime() - startPipeNs) / 1_000_000L

            val hasBgm = ChunkSegmenter.detectBgmPresence(energy, probs, binMs = 10)
            val chunks = segmenter.segment(
                logEnergyDb = energy,
                params = SegmentationParams(),
                binMs = 10,
                durationMs = durationMs,
                speechProbability = probs,
            )
            return BenchResult(
                label = label,
                warmupWindows = engine.warmupWindows,
                vadOnlyMs = vadOnlyMs,
                pipelineMs = pipelineMs,
                probs = probs,
                energy = energy,
                chunks = chunks,
                hasBgm = hasBgm,
            )
        }

        val serial = runBench(
            "serial(p=1)",
            SileroVadEngine(context = context, modelBytesSource = null, parallelism = 1),
        )
        val par2w16 = runBench(
            "par2(w=16/0.51s)",
            SileroVadEngine(context = context, modelBytesSource = null, parallelism = 2, blockWindows = 160, warmupWindows = 16),
        )
        val par2w32 = runBench(
            "par2(w=32/1.02s)",
            SileroVadEngine(context = context, modelBytesSource = null, parallelism = 2, blockWindows = 160, warmupWindows = 32),
        )
        val par4w16 = runBench(
            "par4(w=16/0.51s)",
            SileroVadEngine(context = context, modelBytesSource = null, parallelism = 4, blockWindows = 160, warmupWindows = 16),
        )
        val par4w32 = runBench(
            "par4(w=32/1.02s)",
            SileroVadEngine(context = context, modelBytesSource = null, parallelism = 4, blockWindows = 160, warmupWindows = 32),
        )

        println("=== [Benchmark: Synthetic 60s Audio] ===")
        for (res in listOf(serial, par2w16, par2w32, par4w16, par4w32)) {
            val mae = calcMae(serial.probs, res.probs)
            val p99 = calcP99AbsDiff(serial.probs, res.probs)
            val vadSpeedup = serial.vadOnlyMs.toDouble() / res.vadOnlyMs.coerceAtLeast(1)
            val pipeSpeedup = serial.pipelineMs.toDouble() / res.pipelineMs.coerceAtLeast(1)
            val boundaryMatch = countMatchingChunks(serial.chunks, res.chunks, toleranceMs = 30L)
            println(
                "  %-18s | VAD=%4dms (%4.2fx) | Pipe=%4dms (%4.2fx) | MAE=%.5f | P99=%.5f | chunks=%2d (match=%2d/%2d) | bgm=%s".format(
                    res.label,
                    res.vadOnlyMs,
                    vadSpeedup,
                    res.pipelineMs,
                    pipeSpeedup,
                    mae,
                    p99,
                    res.chunks.size,
                    boundaryMatch,
                    serial.chunks.size,
                    res.hasBgm,
                ),
            )
        }

        assertTrue(calcMae(serial.probs, par2w16.probs) < 0.02f)
        assertTrue(calcMae(serial.probs, par2w32.probs) < 0.015f)
        assertTrue(calcMae(serial.probs, par4w16.probs) < 0.02f)
        assertTrue(calcMae(serial.probs, par4w32.probs) < 0.015f)
        assertEquals(serial.chunks.size, par4w32.chunks.size)
        assertEquals(serial.hasBgm, par4w32.hasBgm)
    }

    @Test
    fun `SileroVadEngine_実BGM教材が存在する場合にBGMありと判定され直列と並列でチャンク分割が一致し高速化される`() {
        val candidateDirs = listOf(
            File("sample_files"),
            File("../sample_files"),
            File("/Users/miuraken/src/gogaku-player/sample_files"),
        )
        val sampleDir = candidateDirs.firstOrNull { File(it, "withmusic-2.mp3").isFile } ?: return
        val file1 = File(sampleDir, "withmusic-1.mp3")
        val file2 = File(sampleDir, "withmusic-2.mp3")
        if (!file1.isFile || !file2.isFile) return

        val segmenter = ChunkSegmenter()

        fun decode16kMonoWithFfmpeg(file: File): FloatArray? = runCatching {
            val process = ProcessBuilder(
                "ffmpeg",
                "-v",
                "error",
                "-i",
                file.absolutePath,
                "-f",
                "f32le",
                "-ac",
                "1",
                "-ar",
                "16000",
                "-",
            ).start()
            val bytes = process.inputStream.use { it.readBytes() }
            val exitCode = process.waitFor()
            if (exitCode != 0 || bytes.size < 4) return@runCatching null
            val floats = FloatArray(bytes.size / 4)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(floats)
            floats
        }.getOrNull()

        data class FileRunResult(
            val label: String,
            val warmupWindows: Int,
            val vadOnlyMs: Long,
            val pipelineMs: Long,
            val hasBgm: Boolean,
            val chunks: List<Chunk>,
            val energy: FloatArray,
            val probs: FloatArray,
        )

        fun analyzeSamplesWithEngine(
            label: String,
            samples: FloatArray,
            engine: SileroVadEngine,
        ): FileRunResult {
            // ウォームアップ
            engine.createStream()?.let { s ->
                s.onSamples(samples, minOf(16_000, samples.size))
                s.finish(100, 10)
            }

            val targetFrames = (samples.size * 100) / 16_000
            val startVadNs = System.nanoTime()
            val vadOnlyStream = assertNotNull(engine.createStream())
            var offset = 0
            val step = 4_096
            while (offset < samples.size) {
                val len = minOf(step, samples.size - offset)
                vadOnlyStream.onSamples(samples.copyOfRange(offset, offset + len), len)
                offset += len
            }
            vadOnlyStream.finish(targetFrameCount = targetFrames, binMs = 10)
            val vadOnlyMs = (System.nanoTime() - startVadNs) / 1_000_000L

            val startPipeNs = System.nanoTime()
            val acc = FeatureAccumulator(binMs = 10)
            val extractor = FrameFeatureExtractor(sampleRate = 16_000, binMs = 10, sink = acc)
            val vadStream = assertNotNull(engine.createStream())

            offset = 0
            while (offset < samples.size) {
                val len = minOf(step, samples.size - offset)
                val slice = samples.copyOfRange(offset, offset + len)
                extractor.process(slice, len)
                vadStream.onSamples(slice, len)
                offset += len
            }
            extractor.finish()
            val energy = acc.logEnergyDb()
            val probs = vadStream.finish(targetFrameCount = energy.size, binMs = 10)
            val pipelineMs = (System.nanoTime() - startPipeNs) / 1_000_000L

            val hasBgm = ChunkSegmenter.detectBgmPresence(energy, probs, binMs = 10)
            val durationMs = (samples.size * 1_000L) / 16_000L
            val chunks = segmenter.segment(
                logEnergyDb = energy,
                params = SegmentationParams(),
                binMs = 10,
                durationMs = durationMs,
                speechProbability = probs,
            )
            return FileRunResult(
                label = label,
                warmupWindows = engine.warmupWindows,
                vadOnlyMs = vadOnlyMs,
                pipelineMs = pipelineMs,
                hasBgm = hasBgm,
                chunks = chunks,
                energy = energy,
                probs = probs,
            )
        }

        val samples1 = decode16kMonoWithFfmpeg(file1) ?: return
        val samples2 = decode16kMonoWithFfmpeg(file2) ?: return

        val engines = listOf(
            "serial(p=1)" to SileroVadEngine(context = context, modelBytesSource = null, parallelism = 1),
            "par2(w=16/0.51s)" to SileroVadEngine(context = context, modelBytesSource = null, parallelism = 2, blockWindows = 160, warmupWindows = 16),
            "par2(w=32/1.02s)" to SileroVadEngine(context = context, modelBytesSource = null, parallelism = 2, blockWindows = 160, warmupWindows = 32),
            "par4(w=16/0.51s)" to SileroVadEngine(context = context, modelBytesSource = null, parallelism = 4, blockWindows = 160, warmupWindows = 16),
            "par4(w=32/1.02s)" to SileroVadEngine(context = context, modelBytesSource = null, parallelism = 4, blockWindows = 160, warmupWindows = 32),
        )

        for ((fileName, samples) in listOf("withmusic-1.mp3" to samples1, "withmusic-2.mp3" to samples2)) {
            val audioDurationSec = samples.size / 16_000.0
            println("=== [Benchmark: $fileName (${"%.1f".format(audioDurationSec)}s)] ===")
            val results = engines.map { (label, eng) -> analyzeSamplesWithEngine(label, samples, eng) }
            val base = results.first()
            for (res in results) {
                val mae = calcMae(base.probs, res.probs)
                val p99 = calcP99AbsDiff(base.probs, res.probs)
                val vadSpeedup = base.vadOnlyMs.toDouble() / res.vadOnlyMs.coerceAtLeast(1)
                val pipeSpeedup = base.pipelineMs.toDouble() / res.pipelineMs.coerceAtLeast(1)
                val match30ms = countMatchingChunks(base.chunks, res.chunks, toleranceMs = 30L)
                val match50ms = countMatchingChunks(base.chunks, res.chunks, toleranceMs = 50L)
                println(
                    "  %-18s | VAD=%4dms (%4.2fx) | Pipe=%4dms (%4.2fx) | MAE=%.5f | P99=%.5f | chunks=%3d (match±30ms=%3d/%3d, ±50ms=%3d/%3d) | bgm=%s".format(
                        res.label,
                        res.vadOnlyMs,
                        vadSpeedup,
                        res.pipelineMs,
                        pipeSpeedup,
                        mae,
                        p99,
                        res.chunks.size,
                        match30ms,
                        base.chunks.size,
                        match50ms,
                        base.chunks.size,
                        res.hasBgm,
                    ),
                )
                assertTrue(res.hasBgm, "$fileName (${res.label}) は BGM ありと判定されるべき")
                val maxAllowedMae = if (res.warmupWindows >= 32) 0.035f else 0.045f
                assertTrue(mae < maxAllowedMae, "$fileName (${res.label}) の MAE が大きすぎます: $mae")
            }
        }

        val defaultEngine = SileroVadEngine(context)
        val res1 = analyzeSamplesWithEngine("default", samples1, defaultEngine)
        val res2 = analyzeSamplesWithEngine("default", samples2, defaultEngine)
        val durationMs2 = (samples2.size * 1_000L) / 16_000L
        val energyOnlyChunks2 = segmenter.segment(
            logEnergyDb = res2.energy,
            params = SegmentationParams(),
            binMs = 10,
            durationMs = durationMs2,
            speechProbability = null,
        )

        assertTrue(res1.hasBgm, "withmusic-1.mp3 は開始30秒以降のサンプリングで BGM ありと判定されるべき")
        assertTrue(res2.hasBgm, "withmusic-2.mp3 は開始30秒以降のサンプリングで BGM ありと判定されるべき")
        assertTrue(res1.chunks.size >= 40, "withmusic-1.mp3 は巨大チャンク化せず多数の単語チャンクに分割されるべき: size=${res1.chunks.size}")
        assertTrue(res2.chunks.size >= 35, "withmusic-2.mp3 は巨大チャンク化せず多数の単語チャンクに分割されるべき: size=${res2.chunks.size}")
        assertTrue(
            res2.chunks.size > energyOnlyChunks2.size * 2,
            "Silero VAD + BGM適応 (${res2.chunks.size}) はエネルギーのみ (${energyOnlyChunks2.size}) より細かく分割されるべき",
        )

        // 拡大波形の「チャンク生成」（trimSilenceInRange）でも BGM 端がトリミングされることを確認
        val targetChunk = res2.chunks.firstOrNull { (it.endMs - it.startMs) in 400L..2_500L && it.startMs > 5_000L }
        if (targetChunk != null) {
            val wideStart = (targetChunk.startMs - 200L).coerceAtLeast(0L)
            val wideEnd = targetChunk.endMs + 200L
            val (trimmedStart, trimmedEnd) = segmenter.trimSilenceInRange(
                logEnergyDb = res2.energy,
                rangeStartMs = wideStart,
                rangeEndMs = wideEnd,
                binMs = 10,
                speechProbability = res2.probs,
            )
            assertTrue(trimmedStart >= wideStart && trimmedEnd <= wideEnd)
            assertTrue((trimmedEnd - trimmedStart) < (wideEnd - wideStart))
        }
    }

    private fun calcMae(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            sum += abs(a[i] - b[i])
        }
        return (sum / n).toFloat()
    }

    private fun calcP99AbsDiff(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0f
        val diffs = FloatArray(n) { i -> abs(a[i] - b[i]) }
        diffs.sort()
        val idx = ((n - 1) * 0.99).toInt().coerceIn(0, n - 1)
        return diffs[idx]
    }

    private fun countMatchingChunks(
        expected: List<Chunk>,
        actual: List<Chunk>,
        toleranceMs: Long,
    ): Int = expected.count { exp ->
        actual.any { act ->
            abs(exp.startMs - act.startMs) <= toleranceMs &&
                abs(exp.endMs - act.endMs) <= toleranceMs
        }
    }
}
