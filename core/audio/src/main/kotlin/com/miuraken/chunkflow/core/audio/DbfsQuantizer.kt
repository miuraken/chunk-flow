package com.miuraken.chunkflow.core.audio

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 波形の値の量子化（§7.3.2）。dBFS（-60〜0dB）を 0..255 に対応させる。
 *
 * 0 は -60dB 以下（無音を含む）、255 は 0dBFS。
 */
object DbfsQuantizer {
    /** 量子化の下限（dBFS） */
    const val FLOOR_DB: Float = -60f

    /** 線形の振幅（0..1）を 0..255 の値にする（Byte として返す。符号なしとして読む）。 */
    fun quantize(linear: Float): Byte {
        if (linear <= FLOOR_LINEAR) return 0
        val db = (20 * log10(linear.toDouble())).toFloat().coerceIn(FLOOR_DB, 0f)
        return ((db - FLOOR_DB) / -FLOOR_DB * 255f).roundToInt().toByte()
    }

    /** 0..255 の値（Byte）を dBFS に戻す。 */
    fun toDb(value: Byte): Float = (value.toInt() and 0xFF) / 255f * -FLOOR_DB + FLOOR_DB

    private val FLOOR_LINEAR = 10.0.pow(FLOOR_DB / 20.0).toFloat()
}
