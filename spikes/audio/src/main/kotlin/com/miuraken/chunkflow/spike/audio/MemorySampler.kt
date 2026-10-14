package com.miuraken.chunkflow.spike.audio

import android.os.Debug

/**
 * 計測中のメモリの最大値を、別スレッドで定期的に調べる。
 *
 * - Java ヒープ: `Runtime.totalMemory() - freeMemory()`（20ms ごと）
 * - ネイティブヒープ: `Debug.getNativeHeapAllocatedSize()`（20ms ごと。MediaCodec のバッファの一部を含む）
 * - PSS: `Debug.getPss()`（500ms ごと。プロセス全体）
 *
 * [start] の前に GC をかけて基準値を取る。[stop] で最大値を返す。
 */
class MemorySampler {
    /** 計測結果（バイト。PSS は KB）。 */
    data class Result(
        val javaBaseline: Long,
        val javaPeak: Long,
        val nativeBaseline: Long,
        val nativePeak: Long,
        val pssBaselineKb: Long,
        val pssPeakKb: Long,
    ) {
        /** 追加の Java ヒープの最大値（MB） */
        val javaExtraMb: Double get() = (javaPeak - javaBaseline) / MB

        /** 追加のネイティブヒープの最大値（MB） */
        val nativeExtraMb: Double get() = (nativePeak - nativeBaseline) / MB

        /** 追加の PSS の最大値（MB） */
        val pssExtraMb: Double get() = (pssPeakKb - pssBaselineKb) / 1024.0
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var javaBaseline = 0L
    private var nativeBaseline = 0L
    private var pssBaseline = 0L

    @Volatile private var javaPeak = 0L

    @Volatile private var nativePeak = 0L

    @Volatile private var pssPeak = 0L

    /** 基準値を取り、計測を始める。 */
    fun start() {
        val rt = Runtime.getRuntime()
        repeat(2) {
            System.gc()
        }
        javaBaseline = rt.totalMemory() - rt.freeMemory()
        nativeBaseline = Debug.getNativeHeapAllocatedSize()
        pssBaseline = Debug.getPss()
        javaPeak = javaBaseline
        nativePeak = nativeBaseline
        pssPeak = pssBaseline
        running = true
        thread = Thread({
            var tick = 0
            while (running) {
                javaPeak = maxOf(javaPeak, rt.totalMemory() - rt.freeMemory())
                nativePeak = maxOf(nativePeak, Debug.getNativeHeapAllocatedSize())
                if (tick % 25 == 0) pssPeak = maxOf(pssPeak, Debug.getPss())
                tick++
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "s3-mem").apply { start() }
    }

    /** 計測を止めて結果を返す。 */
    fun stop(): Result {
        running = false
        thread?.join()
        pssPeak = maxOf(pssPeak, Debug.getPss())
        return Result(javaBaseline, javaPeak, nativeBaseline, nativePeak, pssBaseline, pssPeak)
    }

    private companion object {
        const val MB = 1024.0 * 1024.0
    }
}
