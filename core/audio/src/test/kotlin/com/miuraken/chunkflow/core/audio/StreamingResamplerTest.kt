package com.miuraken.chunkflow.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingResamplerTest {
    private fun runAll(r: StreamingResampler, input: FloatArray, chunk: Int): FloatArray {
        val out = ArrayList<Float>()
        val sink = SampleSink { s, n -> for (i in 0 until n) out.add(s[i]) }
        var i = 0
        val buf = FloatArray(chunk)
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            System.arraycopy(input, i, buf, 0, n)
            r.process(buf, n, sink)
            i += n
        }
        r.finish(sink)
        return out.toFloatArray()
    }

    @Test
    fun `出力数_入力数×比の切り上げになる`() {
        for (rate in listOf(8_000, 11_025, 22_050, 44_100, 48_000, 44_056)) {
            for (n in listOf(0, 1, 7, 1_000, 44_101)) {
                val r = StreamingResampler(rate)
                val out = runAll(r, FloatArray(n) { 0.1f }, 333)
                val expected = (n.toLong() * 16_000 + rate - 1) / rate
                assertEquals(expected, out.size.toLong(), "rate=$rate n=$n")
                assertEquals(expected, r.outputCount)
            }
        }
    }

    @Test
    fun `正弦波_位相がずれずに再現される`() {
        for (rate in listOf(44_100, 48_000, 22_050, 8_000)) {
            val f = 1_000.0
            val n = rate // 1 秒
            val input = FloatArray(n) { (0.5 * sin(2 * PI * f * it / rate)).toFloat() }
            val out = runAll(StreamingResampler(rate), input, 1_024)
            var maxErr = 0.0
            // 両端（ゼロで補う区間）を除く
            for (k in 800 until out.size - 800) {
                val expected = 0.5 * sin(2 * PI * f * k / 16_000)
                maxErr = maxOf(maxErr, abs(out[k] - expected))
            }
            assertTrue(maxErr < 5e-3, "rate=$rate maxErr=$maxErr")
        }
    }

    @Test
    fun `インパルス_出力のピークが同じ時刻に来る`() {
        val rate = 44_100
        val n0 = 44_100 * 3 + 12_345 // 約 3.28 秒
        val input = FloatArray(rate * 4)
        input[n0] = 1f
        val out = runAll(StreamingResampler(rate), input, 4_096)
        val peak = out.indices.maxBy { out[it] }
        val expected = n0.toDouble() * 16_000 / rate
        assertTrue(abs(peak - expected) <= 0.5 + 1e-9, "peak=$peak expected=$expected")
    }

    @Test
    fun `細切れの入力_一度に渡した場合と同じ結果になる`() {
        val rnd = Random(1)
        val input = FloatArray(50_000) { rnd.nextFloat() * 2 - 1 }
        val whole = runAll(StreamingResampler(44_100), input, input.size)
        val pieces = runAll(StreamingResampler(44_100), input, 37)
        assertContentEquals(whole, pieces)
    }

    @Test
    fun `同じサンプルレート_そのまま通す`() {
        val input = FloatArray(1_000) { it / 1_000f }
        val out = runAll(StreamingResampler(16_000), input, 100)
        assertContentEquals(input, out)
    }

    @Test
    fun `位相の数が上限を超える比_時刻の誤差が小さい`() {
        val rate = 16_001 // up = 16000, 位相を 1024 段階に丸める
        val n0 = 30_000
        val input = FloatArray(40_000)
        input[n0] = 1f
        val out = runAll(StreamingResampler(rate), input, 999)
        val peak = out.indices.maxBy { out[it] }
        val expected = n0.toDouble() * 16_000 / rate
        assertTrue(abs(peak - expected) <= 0.5 + 1e-9, "peak=$peak expected=$expected")
    }

    @Test
    fun `reset_新しく作った場合と同じ出力になる`() {
        val rnd = Random(2)
        val first = FloatArray(20_000) { rnd.nextFloat() * 2 - 1 }
        val second = FloatArray(13_333) { rnd.nextFloat() * 2 - 1 }
        for (rate in listOf(44_100, 48_000, 22_050, 8_000, 16_000)) {
            val reused = StreamingResampler(rate)
            runAll(reused, first, 4_096)
            reused.reset()
            assertEquals(0L, reused.inputCount)
            assertEquals(0L, reused.outputCount)
            assertContentEquals(runAll(StreamingResampler(rate), second, 777), runAll(reused, second, 1_000), "rate=$rate")
        }
    }

    @Test
    fun `周期の倍数だけ進めた位置から始める_先頭の補いの影響を除いて元の出力と完全に同じになる`() {
        val rnd = Random(3)
        val input = FloatArray(60_000) { rnd.nextFloat() * 2 - 1 }
        for (rate in listOf(44_100, 48_000, 22_050, 11_025, 8_000, 32_000, 16_000)) {
            val whole = runAll(StreamingResampler(rate), input, 4_096)
            val probe = StreamingResampler(rate)
            val m = 7
            val offset = m * probe.inputPeriod
            val shifted = runAll(StreamingResampler(rate), input.copyOfRange(offset, input.size), 1_234)
            val skip = probe.startupOutputs
            // 進めたリサンプラの j 番目の出力は、元の (outputPeriod × m + j) 番目の出力（先頭の補いが届く範囲と末尾を除く）
            for (j in skip until shifted.size - 64) {
                assertEquals(whole[probe.outputPeriod * m + j], shifted[j], "rate=$rate j=$j")
            }
        }
    }
}
