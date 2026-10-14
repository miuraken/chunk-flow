package com.miuraken.chunkflow.core.audio

import com.miuraken.chunkflow.core.model.Waveform
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WaveformReducerTest {

    @Test
    fun `全体範囲の縮約_バケットごとに符号なし最大値でmaxプーリングされる`() {
        // 10 ビン（100ms）、2 ビンずつ 5 バケットに縮約する
        val peak = byteArrayOf(
            10,
            20,
            50,
            40,
            200.toByte(),
            100,
            255.toByte(),
            0,
            5,
            15,
        )
        val rms = byteArrayOf(
            5,
            15,
            25,
            30,
            150.toByte(),
            80,
            220.toByte(),
            10,
            2,
            12,
        )
        val wf = Waveform(binMs = 10, peak = peak, rms = rms)
        val reducer = WaveformReducer()

        val reduced = reducer.reduce(waveform = wf, startMs = 0L, endMs = 100L, targetBins = 5)
        assertEquals(5, reduced.size)
        assertContentEquals(
            byteArrayOf(20, 50, 200.toByte(), 255.toByte(), 15),
            reduced.peak,
        )
        assertContentEquals(
            byteArrayOf(15, 30, 150.toByte(), 220.toByte(), 12),
            reduced.rms,
        )
        assertEquals(1.0f, reduced.peakFraction(3), 1e-5f)
    }

    @Test
    fun `部分範囲と範囲外を含む縮約_範囲外バケットは0になり範囲内は正しく縮約される`() {
        val peak = ByteArray(10) { ((it + 1) * 20).toByte() }
        val rms = ByteArray(10) { ((it + 1) * 10).toByte() }
        val wf = Waveform(binMs = 10, peak = peak, rms = rms)
        val reducer = WaveformReducer()

        // [-50ms, 50ms] を 2 バケットに縮約: バケット 0 は [-50, 0) で無音、バケット 1 は [0, 50) で bin 0..4 の最大
        val reduced = reducer.reduce(waveform = wf, startMs = -50L, endMs = 50L, targetBins = 2)
        assertEquals(0, reduced.peak[0].toInt() and 0xFF)
        assertEquals(0, reduced.rms[0].toInt() and 0xFF)
        assertEquals(100, reduced.peak[1].toInt() and 0xFF)
        assertEquals(50, reduced.rms[1].toInt() and 0xFF)
    }

    @Test
    fun `LRUキャッシュ_同一キーはキャッシュから返り上限を超えると古いエントリが破棄される`() {
        val wf = Waveform(binMs = 10, peak = ByteArray(100) { 100 }, rms = ByteArray(100) { 50 })
        val reducer = WaveformReducer(maxCacheEntries = 2)

        val first = reducer.reduce(wf, startMs = 0L, endMs = 1_000L, targetBins = 10)
        val firstAgain = reducer.reduce(wf, startMs = 0L, endMs = 1_000L, targetBins = 10)
        assertSame(first, firstAgain)
        assertEquals(1, reducer.cacheSize)

        reducer.reduce(wf, startMs = 0L, endMs = 500L, targetBins = 10)
        assertEquals(2, reducer.cacheSize)

        // first にアクセスして最近使用にする
        assertSame(first, reducer.reduce(wf, startMs = 0L, endMs = 1_000L, targetBins = 10))

        // 3 件目を入れると [0, 500L] が追い出され、[0, 1000L] は残る
        reducer.reduce(wf, startMs = 500L, endMs = 1_000L, targetBins = 10)
        assertEquals(2, reducer.cacheSize)
        assertSame(first, reducer.reduce(wf, startMs = 0L, endMs = 1_000L, targetBins = 10))

        reducer.clearCache()
        assertEquals(0, reducer.cacheSize)
        val recomputed = reducer.reduce(wf, startMs = 0L, endMs = 1_000L, targetBins = 10)
        assertNotSame(first, recomputed)
        assertTrue(recomputed.peak.contentEquals(first.peak))
    }
}
