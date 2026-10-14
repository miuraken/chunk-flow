package com.miuraken.chunkflow.core.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** [FrameFeatureExtractor] の出力を受け取る側。 */
fun interface FrameFeatureSink {
    /**
     * フレーム [index]（時刻 `index * binMs` からの 1 ビン）の特徴量。
     *
     * @param peak ビン内の最大の絶対値（線形、0..1 程度）
     * @param rms ビン内の RMS（線形）
     * @param logEnergyDb ビンの中心を中心とする窓（既定 25ms）の平均パワー（dBFS。下限 [FrameFeatureExtractor.MIN_DB]）
     */
    fun onFrame(index: Int, peak: Float, rms: Float, logEnergyDb: Float)
}

/**
 * モノラルの PCM から、10ms ごとのビン（ピークと RMS。§7.3.2 の波形）と、
 * フレーム特徴量（10ms ホップ・25ms 窓の対数エネルギー。§7.3.1）を 1 パスで求める。
 *
 * - ビン n は入力の `[n * hop, (n + 1) * hop)`（hop = binMs 分のサンプル数）。
 * - 対数エネルギーの窓はビン n の中心を中心にする: `[n * hop - (window - hop) / 2, ... + window)`。
 *   範囲外（先頭より前・末尾より後）はゼロとして扱う。
 * - 出力するフレーム数は `ceil(入力サンプル数 / hop)`。最後のビンが途中で終わる場合も出力する
 *   （足りない分は無音として扱うため、RMS はやや小さくなる）。
 * - 内部では `gcd(hop, (window - hop) / 2)` サンプルのブロックごとに二乗和とピークを集計する（追加の確保なし）。
 *
 * スレッドセーフではない。
 *
 * @param sampleRate 入力のサンプルレート（Hz）
 * @param binMs ビンとホップの長さ（ミリ秒）
 * @param windowMs 対数エネルギーの窓の長さ（ミリ秒）。binMs 以上で、(window - hop) が偶数のサンプル数になること
 */
class FrameFeatureExtractor(
    val sampleRate: Int = StreamingResampler.DEFAULT_OUTPUT_RATE,
    val binMs: Int = 10,
    val windowMs: Int = 25,
    private val sink: FrameFeatureSink,
) {
    private val hop = sampleRate * binMs / 1000
    private val window = sampleRate * windowMs / 1000
    private val pre = (window - hop) / 2
    private val block: Int
    private val blocksPerHop: Int
    private val preBlocks: Int
    private val blocksPerWindow: Int

    private val ringSumSq: DoubleArray
    private val ringPeak: FloatArray

    /** 次に完成するブロックの絶対番号（先頭の補いのブロックは負） */
    private var blockIndex: Long
    private var curSumSq = 0.0
    private var curPeak = 0f
    private var curCount = 0

    private var nextFrame = 0
    private var inputCount = 0L
    private var finished = false

    init {
        require(sampleRate > 0 && binMs > 0 && windowMs >= binMs)
        require(sampleRate * binMs % 1000 == 0 && sampleRate * windowMs % 1000 == 0) { "ビンと窓は整数サンプルにする" }
        require((window - hop) % 2 == 0) { "(window - hop) は偶数サンプルにする" }
        block = if (pre == 0) hop else gcd(hop, pre)
        blocksPerHop = hop / block
        preBlocks = pre / block
        blocksPerWindow = window / block
        ringSumSq = DoubleArray(blocksPerWindow)
        ringPeak = FloatArray(blocksPerWindow)
        blockIndex = -preBlocks.toLong()
        // 先頭の補い（ゼロ）のブロック
        repeat(preBlocks) { completeBlock() }
    }

    /** これまでに出力したフレーム数 */
    val frameCount: Int get() = nextFrame

    /** [samples] の先頭 [count] 個を処理する。 */
    fun process(samples: FloatArray, count: Int) {
        check(!finished) { "finish() の後には呼べない" }
        inputCount += count
        var i = 0
        while (i < count) {
            val n = minOf(block - curCount, count - i)
            var s = curSumSq
            var p = curPeak
            for (k in i until i + n) {
                val v = samples[k]
                s += (v * v).toDouble()
                val a = if (v < 0) -v else v
                if (a > p) p = a
            }
            curSumSq = s
            curPeak = p
            curCount += n
            i += n
            if (curCount == block) completeBlock()
        }
    }

    /** 入力の終わり。残りのフレームをすべて出力する。 */
    fun finish() {
        if (finished) return
        finished = true
        val totalFrames = ((inputCount + hop - 1) / hop).toInt()
        if (curCount > 0) completeBlock()
        while (nextFrame < totalFrames) completeBlock()
    }

    private fun completeBlock() {
        val slot = Math.floorMod(blockIndex, blocksPerWindow.toLong()).toInt()
        ringSumSq[slot] = curSumSq
        ringPeak[slot] = curPeak
        curSumSq = 0.0
        curPeak = 0f
        curCount = 0
        // フレーム n の窓の最後のブロック = n * blocksPerHop - preBlocks + blocksPerWindow - 1
        val lastOfNext = nextFrame.toLong() * blocksPerHop - preBlocks + blocksPerWindow - 1
        if (blockIndex == lastOfNext) emitFrame()
        blockIndex++
    }

    private fun emitFrame() {
        val n = nextFrame
        val first = n.toLong() * blocksPerHop
        var binSumSq = 0.0
        var binPeak = 0f
        for (b in first until first + blocksPerHop) {
            val slot = Math.floorMod(b, blocksPerWindow.toLong()).toInt()
            binSumSq += ringSumSq[slot]
            binPeak = max(binPeak, ringPeak[slot])
        }
        var winSumSq = 0.0
        for (s in ringSumSq) winSumSq += s
        val rms = sqrt(binSumSq / hop).toFloat()
        val energy = winSumSq / window
        val db = if (energy <= MIN_POWER) MIN_DB else (10 * log10(energy)).toFloat()
        sink.onFrame(n, binPeak, rms, max(db, MIN_DB))
        nextFrame++
    }

    /** 定数 */
    companion object {
        /** 対数エネルギーの下限（dBFS） */
        const val MIN_DB: Float = -100f
        private const val MIN_POWER = 1e-10

        private fun gcd(a: Int, b: Int): Int {
            var x = a
            var y = b
            while (y != 0) {
                val t = x % y
                x = y
                y = t
            }
            return x
        }
    }
}
