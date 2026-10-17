package com.miuraken.chunkflow.core.media

import java.nio.ByteBuffer

/**
 * MPEG オーディオ Layer III（MP3）のフレームヘッダから読み取った値。
 *
 * @property frameSizeBytes ヘッダを含むフレーム全体のバイト数。
 * @property samplesPerFrame 1 フレームをデコードしたときの 1 チャンネルあたりのサンプル数（MPEG-1 は 1,152、MPEG-2 / 2.5 は 576）。
 */
internal data class Mp3FrameInfo(
    val frameSizeBytes: Int,
    val samplesPerFrame: Int,
)

/**
 * MP3 のフレームヘッダ（4 バイト）を読む（MediaCodec の 1 つの入力に複数のフレームをまとめて渡す前の確認用）。
 *
 * Android 標準の MP3 デコーダ（AOSP の `C2SoftMp3Dec`）は、入力に入ったフレームをヘッダの長さで区切ってデコードし、
 * 読めないヘッダが 1 つでも混ざると入力全体を捨てる（時間軸がずれる）。そのため、まとめる前にここでヘッダと長さを確かめる。
 * 長さの式は Layer III のもので、デコーダ（`parseMp3Header`）と Media3（`MpegAudioUtil`）と同じ。
 */
internal object Mp3FrameHeader {
    /** ヘッダのバイト数。 */
    const val HEADER_SIZE_BYTES: Int = 4

    /**
     * [buffer] の [offset] から始まるヘッダを読む（位置と範囲は変えない）。
     * Layer III の正しいヘッダでなければ `null` を返す（Layer I / II、フリーフォーマット、予約値を含む）。
     */
    fun parse(buffer: ByteBuffer, offset: Int): Mp3FrameInfo? {
        if (offset < 0 || offset + HEADER_SIZE_BYTES > buffer.limit()) return null
        return parse(
            b0 = buffer.get(offset).toInt() and 0xFF,
            b1 = buffer.get(offset + 1).toInt() and 0xFF,
            b2 = buffer.get(offset + 2).toInt() and 0xFF,
        )
    }

    /** ヘッダの先頭 3 バイトから読む（4 バイト目はフレームの長さに関係しない）。正しくなければ `null`。 */
    fun parse(b0: Int, b1: Int, b2: Int): Mp3FrameInfo? {
        if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
        // 0: MPEG-2.5、1: 予約、2: MPEG-2、3: MPEG-1
        val version = (b1 ushr 3) and 0x03
        // 1: Layer III
        val layer = (b1 ushr 1) and 0x03
        if (version == VERSION_RESERVED || layer != LAYER_III) return null
        val bitrateIndex = (b2 ushr 4) and 0x0F
        val sampleRateIndex = (b2 ushr 2) and 0x03
        if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null
        val padding = (b2 ushr 1) and 0x01

        val isMpeg1 = version == VERSION_MPEG1
        val bitrateKbps = if (isMpeg1) MPEG1_BITRATES_KBPS[bitrateIndex] else MPEG2_BITRATES_KBPS[bitrateIndex]
        val sampleRateHz = when (version) {
            VERSION_MPEG1 -> MPEG1_SAMPLE_RATES_HZ[sampleRateIndex]
            VERSION_MPEG2 -> MPEG1_SAMPLE_RATES_HZ[sampleRateIndex] / 2
            else -> MPEG1_SAMPLE_RATES_HZ[sampleRateIndex] / 4
        }
        val coefficient = if (isMpeg1) MPEG1_FRAME_COEFFICIENT else MPEG2_FRAME_COEFFICIENT
        return Mp3FrameInfo(
            frameSizeBytes = coefficient * bitrateKbps / sampleRateHz + padding,
            samplesPerFrame = if (isMpeg1) MPEG1_SAMPLES_PER_FRAME else MPEG2_SAMPLES_PER_FRAME,
        )
    }

    private const val VERSION_RESERVED = 1
    private const val VERSION_MPEG2 = 2
    private const val VERSION_MPEG1 = 3
    private const val LAYER_III = 1
    private const val MPEG1_SAMPLES_PER_FRAME = 1_152
    private const val MPEG2_SAMPLES_PER_FRAME = 576

    /** フレーム長の係数（144 × ビットレート[bps] ÷ サンプルレート を kbps で計算するための値）。 */
    private const val MPEG1_FRAME_COEFFICIENT = 144_000
    private const val MPEG2_FRAME_COEFFICIENT = 72_000

    private val MPEG1_BITRATES_KBPS = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MPEG2_BITRATES_KBPS = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val MPEG1_SAMPLE_RATES_HZ = intArrayOf(44_100, 48_000, 32_000)
}
