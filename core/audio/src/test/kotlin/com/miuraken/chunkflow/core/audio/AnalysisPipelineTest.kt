package com.miuraken.chunkflow.core.audio

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisPipelineTest {
    @Test
    fun `ステレオ44_1kHzのクリック_16kHzの出力でも同じ時刻に検出される`() {
        val rate = 44_100
        val seconds = 5
        val clickAt = 3.217 // 秒
        val n0 = (clickAt * rate).toInt()
        val frames = rate * seconds
        val pcm = ShortArray(frames * 2)
        // 30ms のバースト（振幅 0.6）
        for (i in n0 until n0 + rate * 30 / 1000) {
            val v = if ((i - n0) % 44 < 22) 19_660 else -19_660
            pcm[2 * i] = v.toShort()
            pcm[2 * i + 1] = v.toShort()
        }
        var resampledIndex = 0L
        var firstHit = -1L
        val tap = SampleSink { s, n ->
            for (i in 0 until n) {
                if (firstHit < 0 && abs(s[i]) > 0.3f) firstHit = resampledIndex + i
            }
            resampledIndex += n
        }
        val acc = FeatureAccumulator()
        val p = AnalysisPipeline(rate, 2, acc, resampledTap = tap)
        // デコーダのように 1152 フレームずつ渡す
        val chunk = ShortArray(1152 * 2)
        var f = 0
        while (f < frames) {
            val n = minOf(1152, frames - f)
            System.arraycopy(pcm, f * 2, chunk, 0, n * 2)
            p.pushS16(chunk, n)
            f += n
        }
        p.finish()

        assertEquals(frames.toLong(), p.inputFrames)
        assertEquals(ceil(frames * 16_000.0 / rate).toLong(), p.outputSamples)
        assertEquals(ceil(p.outputSamples / 160.0).toInt(), acc.size)
        val hitMs = firstHit * 1000.0 / 16_000
        // 立ち上がりのリンギングで 1 サンプル（0.0625ms）程度早まることがある
        assertTrue(abs(hitMs - clickAt * 1000) < 0.2, "hitMs=$hitMs")
        // 波形: クリックが始まるビン（321）から 30ms 分が大きく（0.6 ≒ -4.4dB ≒ 236）、直前のビンは小さい
        val wf = acc.toWaveform()
        fun level(bin: Int) = wf.peak[bin].toInt() and 0xFF
        val first = (clickAt * 100).toInt()
        for (b in first..first + 2) assertTrue(level(b) > 220, "bin=$b level=${level(b)}")
        assertTrue(level(first - 1) < 100, "level=${level(first - 1)}")
    }

    @Test
    fun `モノラル化_チャンネルの平均になる`() {
        val dst = FloatArray(2)
        PcmDownmix.downmixS16(shortArrayOf(16_384, 0, -32_768, -32_768), 2, 2, dst)
        assertEquals(0.25f, dst[0])
        assertEquals(-1f, dst[1])
        PcmDownmix.downmixFloat(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f), 2, 3, dst)
        assertEquals(0.2f, dst[0], 1e-6f)
        assertEquals(0.5f, dst[1], 1e-6f)
    }

    @Test
    fun `量子化_-60から0dBを0から255に対応させる`() {
        assertEquals(0, DbfsQuantizer.quantize(0f).toInt() and 0xFF)
        assertEquals(0, DbfsQuantizer.quantize(0.0005f).toInt() and 0xFF)
        assertEquals(255, DbfsQuantizer.quantize(1f).toInt() and 0xFF)
        assertEquals(255, DbfsQuantizer.quantize(2f).toInt() and 0xFF)
        // -30dB ≒ 0.0316 → 127.5 → 128
        assertEquals(128, DbfsQuantizer.quantize(0.031623f).toInt() and 0xFF)
        assertEquals(-30f, DbfsQuantizer.toDb(DbfsQuantizer.quantize(0.031623f)), 0.2f)
    }
}
