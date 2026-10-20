package com.miuraken.chunkflow.core.media

import com.miuraken.chunkflow.core.audio.ChunkSegmenter
import com.miuraken.chunkflow.core.audio.FeatureAccumulator
import com.miuraken.chunkflow.core.audio.FrameFeatureExtractor
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.SegmentationParams
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    fun `SileroVadEngine_実BGM教材が存在する場合にBGMありと判定され巨大チャンク化せず単語単位に分割される`() {
        val candidateDirs = listOf(
            File("sample_files"),
            File("../sample_files"),
            File("/Users/miuraken/src/gogaku-player/sample_files"),
        )
        val sampleDir = candidateDirs.firstOrNull { File(it, "withmusic-2.mp3").isFile } ?: return
        val file1 = File(sampleDir, "withmusic-1.mp3")
        val file2 = File(sampleDir, "withmusic-2.mp3")
        if (!file1.isFile || !file2.isFile) return

        val vadEngine = SileroVadEngine(context)
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

        fun analyzeSamples(samples: FloatArray): Triple<Boolean, List<Chunk>, Pair<FloatArray, FloatArray>> {
            val acc = FeatureAccumulator(binMs = 10)
            val extractor = FrameFeatureExtractor(sampleRate = 16_000, binMs = 10, sink = acc)
            val vadStream = assertNotNull(vadEngine.createStream())

            var offset = 0
            val step = 4_096
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
            val hasBgm = ChunkSegmenter.detectBgmPresence(energy, probs, binMs = 10)
            val durationMs = (samples.size * 1_000L) / 16_000L
            val chunks = segmenter.segment(
                logEnergyDb = energy,
                params = SegmentationParams(),
                binMs = 10,
                durationMs = durationMs,
                speechProbability = probs,
            )
            return Triple(hasBgm, chunks, energy to probs)
        }

        val samples1 = decode16kMonoWithFfmpeg(file1) ?: return
        val samples2 = decode16kMonoWithFfmpeg(file2) ?: return

        val (hasBgm1, chunks1, _) = analyzeSamples(samples1)
        val (hasBgm2, chunks2, features2) = analyzeSamples(samples2)
        val durationMs2 = (samples2.size * 1_000L) / 16_000L
        val energyOnlyChunks2 = segmenter.segment(
            logEnergyDb = features2.first,
            params = SegmentationParams(),
            binMs = 10,
            durationMs = durationMs2,
            speechProbability = null,
        )

        assertTrue(hasBgm1, "withmusic-1.mp3 は開始30秒以降のサンプリングで BGM ありと判定されるべき")
        assertTrue(hasBgm2, "withmusic-2.mp3 は開始30秒以降のサンプリングで BGM ありと判定されるべき")
        assertTrue(chunks1.size >= 40, "withmusic-1.mp3 は巨大チャンク化せず多数の単語チャンクに分割されるべき: size=${chunks1.size}")
        assertTrue(chunks2.size >= 35, "withmusic-2.mp3 は巨大チャンク化せず多数の単語チャンクに分割されるべき: size=${chunks2.size}")
        assertTrue(
            chunks2.size > energyOnlyChunks2.size * 2,
            "Silero VAD + BGM適応 (${chunks2.size}) はエネルギーのみ (${energyOnlyChunks2.size}) より細かく分割されるべき",
        )

        // 拡大波形の「チャンク生成」（trimSilenceInRange）でも BGM 端がトリミングされることを確認
        val targetChunk = chunks2.firstOrNull { (it.endMs - it.startMs) in 400L..2_500L && it.startMs > 5_000L }
        if (targetChunk != null) {
            val wideStart = (targetChunk.startMs - 200L).coerceAtLeast(0L)
            val wideEnd = targetChunk.endMs + 200L
            val (trimmedStart, trimmedEnd) = segmenter.trimSilenceInRange(
                logEnergyDb = features2.first,
                rangeStartMs = wideStart,
                rangeEndMs = wideEnd,
                binMs = 10,
                speechProbability = features2.second,
            )
            assertTrue(trimmedStart >= wideStart && trimmedEnd <= wideEnd)
            assertTrue((trimmedEnd - trimmedStart) < (wideEnd - wideStart))
        }
    }
}
