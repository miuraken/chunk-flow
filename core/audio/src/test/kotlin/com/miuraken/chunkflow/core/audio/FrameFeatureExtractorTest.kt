package com.miuraken.chunkflow.core.audio

import kotlin.math.log10
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameFeatureExtractorTest {
    private data class Frame(val index: Int, val peak: Float, val rms: Float, val db: Float)

    private fun run(input: FloatArray, chunk: Int): List<Frame> {
        val frames = ArrayList<Frame>()
        val fx = FrameFeatureExtractor(sink = { i, p, r, e -> frames.add(Frame(i, p, r, e)) })
        var i = 0
        while (i < input.size) {
            val n = minOf(chunk, input.size - i)
            fx.process(input.copyOfRange(i, i + n), n)
            i += n
        }
        fx.finish()
        return frames
    }

    @Test
    fun `フレーム数_入力サンプル数をホップで割った切り上げになる`() {
        for (n in listOf(0, 1, 159, 160, 161, 16_000, 16_123)) {
            val frames = run(FloatArray(n) { 0.1f }, 500)
            assertEquals((n + 159) / 160, frames.size, "n=$n")
            assertEquals((0 until frames.size).toList(), frames.map { it.index })
        }
    }

    @Test
    fun `一定の振幅_ピークとRMSとエネルギーが一致する`() {
        // ±0.5 の矩形波: ピーク 0.5、RMS 0.5、パワー 0.25（-6.02dB）
        val input = FloatArray(16_000) { if (it % 2 == 0) 0.5f else -0.5f }
        val frames = run(input, 1_000)
        for (f in frames.subList(3, frames.size - 3)) {
            assertEquals(0.5f, f.peak, 1e-6f)
            assertEquals(0.5f, f.rms, 1e-5f)
            assertEquals((10 * log10(0.25)).toFloat(), f.db, 1e-3f)
        }
    }

    @Test
    fun `インパルス_ピークは該当するビンにだけ出る`() {
        val input = FloatArray(16_000)
        input[160 * 42 + 17] = 0.8f
        val frames = run(input, 333)
        assertEquals(0.8f, frames[42].peak)
        assertTrue(frames.filter { it.index != 42 }.all { it.peak == 0f })
    }

    @Test
    fun `エネルギーの窓_ビンの中心を中心とする25ms`() {
        // ビン 50 の中心は 50*160+80。窓は [50*160-120, 50*160+280)
        val start = 50 * 160 - 120
        val input = FloatArray(16_000)
        input[start] = 1f // 窓の最初のサンプル: フレーム 50 に入り、フレーム 51 には入らない
        input[start + 399 + 2_000] = 1f // 別の場所
        val frames = run(input, 160)
        val silent = FrameFeatureExtractor.MIN_DB
        assertTrue(frames[50].db > silent)
        // 1 サンプル（振幅 1）/ 400 サンプル → 10*log10(1/400)
        assertEquals((10 * log10(1.0 / 400)).toFloat(), frames[50].db, 1e-3f)
        // フレーム 51 の窓は [51*160-120, ...) で、start を含まない
        assertEquals(silent, frames[51].db)
        // フレーム 47 の窓 [47*160-120, 47*160+280) = [7400, 7800) は start=7880 を含まない
        assertEquals(silent, frames[47].db)
        // フレーム 48 の窓 [7560, 7960) は含む
        assertTrue(frames[48].db > silent)
    }

    @Test
    fun `細切れの入力_一度に渡した場合と同じ結果になる`() {
        val rnd = Random(7)
        val input = FloatArray(20_000) { rnd.nextFloat() - 0.5f }
        assertContentEquals(run(input, input.size).toTypedArray(), run(input, 13).toTypedArray())
    }
}
