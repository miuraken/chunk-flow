package com.miuraken.chunkflow.core.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * ストリーミングのリサンプラ（窓付き sinc、ポリフェーズ）。§7.3.1 の「16kHz にリサンプル」に使う。
 *
 * - **時間軸**: 出力の k 番目のサンプルは、入力の時刻 `k / outputRate` 秒の位置を表す（位相のずれのないフィルタ。
 *   先頭と末尾はゼロで補う）。したがって、出力したサンプル数の累計から時刻を求められる（§7.3.1 の時間軸）。
 * - **出力数**: 入力が N サンプルなら、[finish] までの出力は `ceil(N * outputRate / inputRate)` サンプル。
 * - 入力と出力の比は有理数として正確に扱う（長時間でも時刻がずれない）。位相の数が [maxPhases] を超える比では、
 *   位相を [maxPhases] 段階に丸める（時刻の誤差は最大で入力の 1/[maxPhases] サンプル）。
 * - 入力と出力のサンプルレートが同じなら、そのまま通す。
 *
 * スレッドセーフではない。1 つのストリームに 1 つ使う。
 *
 * @param inputRate 入力のサンプルレート（Hz）
 * @param outputRate 出力のサンプルレート（Hz）。既定は 16kHz
 * @param zeroCrossings sinc の片側のゼロ交差の数（大きいほど急峻で重い）
 * @param rolloff 遮断周波数を、出力（ダウンサンプルの場合）のナイキスト周波数の何倍に置くか
 * @param maxPhases 位相の表の最大の段数
 */
class StreamingResampler(
    val inputRate: Int,
    val outputRate: Int = DEFAULT_OUTPUT_RATE,
    zeroCrossings: Int = 8,
    rolloff: Double = 0.9,
    maxPhases: Int = 1024,
) {
    private val up: Long
    private val down: Long
    private val passthrough = inputRate == outputRate

    /** カーネルの片側の幅（入力のサンプル数） */
    private val halfWidth: Int
    private val taps: Int
    private val phases: Int
    private val table: FloatArray

    /** 入力の履歴。buf[0] は入力の絶対位置 [bufStart] のサンプル（負の位置はゼロの補い） */
    private var buf = FloatArray(4096)
    private var bufStart = 0L
    private var bufLen = 0

    private val out = FloatArray(OUT_CHUNK)
    private var outLen = 0

    /** 受け取った入力のサンプル数 */
    var inputCount: Long = 0
        private set

    /** 出力したサンプル数（時刻 = outputCount / outputRate） */
    var outputCount: Long = 0
        private set

    private var finished = false

    init {
        require(inputRate > 0 && outputRate > 0) { "inputRate=$inputRate, outputRate=$outputRate" }
        require(zeroCrossings >= 1 && rolloff > 0 && rolloff <= 1 && maxPhases >= 1)
        val g = gcd(inputRate, outputRate)
        up = (outputRate / g).toLong()
        down = (inputRate / g).toLong()
        // 遮断周波数（入力の 1 サンプルあたりのサイクル数）
        val fc = 0.5 * min(1.0, outputRate.toDouble() / inputRate) * rolloff
        halfWidth = if (passthrough) 0 else ceil(zeroCrossings / (2 * fc)).toInt()
        taps = 2 * halfWidth
        phases = if (passthrough) 1 else min(up, maxPhases.toLong()).toInt()
        table = FloatArray(phases * taps)
        if (!passthrough) buildTable(fc)
        // 先頭のゼロの補い（出力 0 番が入力の -halfWidth+1 番以降を参照するため）
        bufStart = -halfWidth.toLong()
        bufLen = halfWidth
    }

    private fun buildTable(fc: Double) {
        for (p in 0 until phases) {
            val frac = p.toDouble() / phases
            var sum = 0.0
            val row = p * taps
            for (j in 0 until taps) {
                // 出力位置から入力サンプルまでの距離（入力のサンプル数）
                val d = frac + halfWidth - 1 - j
                val v = 2 * fc * sinc(2 * fc * d) * blackman(d / halfWidth)
                table[row + j] = v.toFloat()
                sum += v
            }
            // 位相ごとに直流のゲインを 1 にそろえる
            for (j in 0 until taps) table[row + j] = (table[row + j] / sum).toFloat()
        }
    }

    /**
     * 入力 [input] の先頭 [length] サンプルを処理し、得られた出力を [sink] に渡す。
     * 出力は内部のバッファがいっぱいになるか、この呼び出しの終わりに渡す。
     */
    fun process(input: FloatArray, length: Int, sink: SampleSink) {
        check(!finished) { "finish() の後には呼べない" }
        require(length in 0..input.size)
        if (length == 0) return
        inputCount += length
        if (passthrough) {
            sink.onSamples(input, length)
            outputCount += length
            return
        }
        append(input, length)
        produce(sink, limit = Long.MAX_VALUE)
        flushOut(sink)
    }

    /** 入力の終わり。末尾をゼロで補って、残りの出力をすべて [sink] に渡す。 */
    fun finish(sink: SampleSink) {
        if (finished) return
        finished = true
        if (passthrough) return
        val total = expectedOutputCount(inputCount)
        // 末尾のゼロの補い
        val zeros = FloatArray(halfWidth + 1)
        while (outputCount < total) {
            append(zeros, zeros.size)
            produce(sink, limit = total)
        }
        flushOut(sink)
    }

    /** 入力が [inputSamples] サンプルのときの出力の総数。 */
    fun expectedOutputCount(inputSamples: Long): Long = if (passthrough) {
        inputSamples
    } else {
        // ceil(N * up / down)
        (inputSamples * up + down - 1) / down
    }

    private fun append(input: FloatArray, length: Int) {
        if (bufLen + length > buf.size) {
            // 次の出力で使わない古い入力を捨てる
            val firstNeeded = (outputCount * down) / up - halfWidth + 1
            val drop = (firstNeeded - bufStart).coerceIn(0L, bufLen.toLong()).toInt()
            if (drop > 0) {
                System.arraycopy(buf, drop, buf, 0, bufLen - drop)
                bufLen -= drop
                bufStart += drop
            }
            if (bufLen + length > buf.size) {
                buf = buf.copyOf(maxOf(buf.size * 2, bufLen + length))
            }
        }
        System.arraycopy(input, 0, buf, bufLen, length)
        bufLen += length
    }

    private fun produce(sink: SampleSink, limit: Long) {
        val available = bufStart + bufLen // 最後の入力の次の絶対位置
        while (outputCount < limit) {
            val num = outputCount * down
            val ip = num / up
            if (ip + halfWidth >= available) break
            val rem = num - ip * up
            val phase = if (phases.toLong() == up) rem.toInt() else ((rem * phases) / up).toInt()
            val row = phase * taps
            var base = (ip - halfWidth + 1 - bufStart).toInt()
            var acc = 0f
            for (j in 0 until taps) {
                acc += buf[base] * table[row + j]
                base++
            }
            out[outLen++] = acc
            outputCount++
            if (outLen == out.size) flushOut(sink)
        }
    }

    private fun flushOut(sink: SampleSink) {
        if (outLen > 0) {
            sink.onSamples(out, outLen)
            outLen = 0
        }
    }

    /** 定数 */
    companion object {
        /** 解析に使う既定のサンプルレート（§7.3.1） */
        const val DEFAULT_OUTPUT_RATE: Int = 16_000
        private const val OUT_CHUNK = 4096

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

        private fun sinc(x: Double): Double = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

        /** -1..1 の範囲の Blackman 窓（範囲外は 0） */
        private fun blackman(x: Double): Double {
            if (x <= -1.0 || x >= 1.0) return 0.0
            val t = (x + 1) / 2 // 0..1
            return 0.42 - 0.5 * cos(2 * PI * t) + 0.08 * cos(4 * PI * t)
        }
    }
}
