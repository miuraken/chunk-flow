package com.miuraken.chunkflow.core.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.math.roundToLong

/** テスト用の圧縮データの 1 サンプル（パケット）。 */
internal class FakeMediaPacket(
    val ptsUs: Long,
    val bytes: ByteArray,
)

/**
 * パケットの並びを返す偽の抽出器（[ExtractorSession]）。
 *
 * `seekTo` は、その時刻以下の最後のパケットに着地する（`SEEK_TO_PREVIOUS_SYNC` と同じ）。
 */
internal class FakeMediaExtractor(
    private val format: MediaFormat,
    private val packets: List<FakeMediaPacket>,
) : ExtractorSession {
    private var index = 0

    /** 閉じたか。 */
    @Volatile
    var closed = false

    /** シークした時刻（シークした順）。 */
    val seekHistoryUs: MutableList<Long> = Collections.synchronizedList(ArrayList())

    override val trackCount: Int get() = 1

    override fun getTrackFormat(index: Int): MediaFormat = format

    override fun selectTrack(index: Int) = Unit

    override fun seekTo(timeUs: Long) {
        seekHistoryUs += timeUs
        val found = packets.indexOfLast { it.ptsUs <= timeUs }
        index = if (found >= 0) found else 0
    }

    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        check(!closed) { "閉じた抽出器を使った" }
        if (index >= packets.size) return -1
        val bytes = packets[index].bytes
        buffer.position(offset)
        buffer.put(bytes)
        return bytes.size
    }

    override val sampleTime: Long
        get() = if (index >= packets.size) MediaCodecAudioStreamDecoder.END_OF_INPUT_US else packets[index].ptsUs

    override fun advance(): Boolean {
        index++
        return index < packets.size
    }

    override fun close() {
        closed = true
    }
}

/**
 * 16-bit PCM（`audio/raw`）をそのまま出す偽のデコーダ（[CodecSession]）。
 *
 * 本物の Codec2 のデコーダと同じく、セッションの先頭（作った直後）の [trimStartSamples] サンプル（1 チャンネルあたり）を削る
 * （デコーダの遅延・エンコーダの遅延を削るのをまねる）。入力 1 つにつき出力 1 つ（削った残り）を出す。
 */
internal class FakeRawCodec(
    private val sampleRate: Int,
    private val channels: Int = 1,
    private val trimStartSamples: Int = 0,
) : CodecSession {
    private val inputBuf = ByteBuffer.allocate(512 * 1024).order(ByteOrder.nativeOrder())
    private val pending = ArrayDeque<Pair<ByteArray, Int>>()
    private var current: ByteArray? = null
    private var emittedFormatChange = false
    private var remainingTrimBytes = trimStartSamples * channels * 2

    /** 閉じたか。 */
    @Volatile
    var closed = false

    override val outputFormat: MediaFormat
        get() = MediaFormat.createAudioFormat("audio/raw", sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

    override fun dequeueInputBuffer(timeoutUs: Long): Int {
        check(!closed) { "閉じたデコーダを使った" }
        return if (pending.size < 4) 0 else -1
    }

    override fun getInputBuffer(index: Int): ByteBuffer = inputBuf

    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        val bytes = ByteArray(size)
        inputBuf.position(offset)
        inputBuf.get(bytes, 0, size)
        val trimmed = remainingTrimBytes.coerceAtMost(bytes.size)
        remainingTrimBytes -= trimmed
        pending.addLast(bytes.copyOfRange(trimmed, bytes.size) to flags)
    }

    override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
        if (!emittedFormatChange) {
            emittedFormatChange = true
            return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
        }
        val (bytes, flags) = pending.removeFirstOrNull() ?: return MediaCodec.INFO_TRY_AGAIN_LATER
        current = bytes
        info.set(0, bytes.size, 0L, flags)
        return 0
    }

    override fun getOutputBuffer(index: Int): ByteBuffer? = current?.let { ByteBuffer.wrap(it).order(ByteOrder.nativeOrder()) }

    override fun releaseOutputBuffer(index: Int, render: Boolean) {
        current = null
    }

    override fun close() {
        closed = true
    }
}

/**
 * Android 標準の MP3 デコーダ（`C2SoftMp3Dec`）のまとめ入れをまねた偽物（[MediaCodecAudioStreamDecoderTest] の偽物と同じ考え方）。
 *
 * 入力に入ったフレームをヘッダの長さで区切り、1 フレームにつき 1,152 サンプル（値はフレームの 5 バイト目 × 100）を出す。
 *
 * @param dropAllButFirstFrame true なら、まとめて渡されても先頭のフレームしか出さない（まとめ入れに対応しないデコーダ）。
 */
internal class FakeBatchMp3Codec(
    override val codecName: String,
    private val dropAllButFirstFrame: Boolean = false,
) : CodecSession {
    private val inputBuf = ByteBuffer.allocate(256 * 1024)
    private val pending = ArrayDeque<Pair<ByteArray, Int>>()
    private var current: ByteArray? = null
    private var emittedFormatChange = false

    /** 閉じたか。 */
    @Volatile
    var closed = false

    /** 入力ごとに入っていたフレームの数（EOS を除く）。 */
    val framesPerInput = mutableListOf<Int>()

    override val outputFormat: MediaFormat = MediaFormat.createAudioFormat("audio/raw", MP3_SAMPLE_RATE, 1).apply {
        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
    }

    override fun dequeueInputBuffer(timeoutUs: Long): Int {
        check(!closed) { "閉じたデコーダを使った" }
        return if (pending.size < 4) 0 else -1
    }

    override fun getInputBuffer(index: Int): ByteBuffer = inputBuf

    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        if ((flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            pending.addLast(ByteArray(0) to flags)
            return
        }
        val view = inputBuf.duplicate().apply {
            position(0)
            limit(offset + size)
        }
        val values = mutableListOf<Short>()
        var position = offset
        var frames = 0
        while (position + Mp3FrameHeader.HEADER_SIZE_BYTES <= offset + size) {
            val frame = Mp3FrameHeader.parse(view, position) ?: break
            if (!dropAllButFirstFrame || frames == 0) values += ((view.get(position + 4).toInt() and 0xFF) * 100).toShort()
            frames++
            position += frame.frameSizeBytes
        }
        if (frames == 0) values += 0
        framesPerInput += frames
        val pcm = ByteBuffer.allocate(values.size * MP3_FRAME_SAMPLES * 2).order(ByteOrder.nativeOrder())
        for (value in values) repeat(MP3_FRAME_SAMPLES) { pcm.putShort(value) }
        pending.addLast(pcm.array() to 0)
    }

    override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
        if (!emittedFormatChange) {
            emittedFormatChange = true
            return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
        }
        val (bytes, flags) = pending.removeFirstOrNull() ?: return MediaCodec.INFO_TRY_AGAIN_LATER
        current = bytes
        info.set(0, bytes.size, 0L, flags)
        return 0
    }

    override fun getOutputBuffer(index: Int): ByteBuffer? = current?.let { ByteBuffer.wrap(it).order(ByteOrder.nativeOrder()) }

    override fun releaseOutputBuffer(index: Int, render: Boolean) {
        current = null
    }

    override fun close() {
        closed = true
    }

    companion object {
        const val MP3_SAMPLE_RATE: Int = 44_100
        const val MP3_FRAME_SAMPLES: Int = 1_152
    }
}

/** テスト用の音声の作り方。 */
internal object FakeMedia {
    /**
     * 44.1kHz（[sampleRate]）モノラルの 16-bit PCM を、1 パケット [framesPerPacket] フレームずつに分けた `audio/raw` の音声。
     * 値はサンプルの位置だけで決まる（どう区切っても同じ）。チャンクごとに違う PCM になるよう、音の高さと大きさをゆっくり変える。
     */
    fun rawPackets(durationMs: Long, sampleRate: Int = 44_100, framesPerPacket: Int = 1_152): List<FakeMediaPacket> {
        val totalFrames = durationMs * sampleRate / 1_000L
        val packets = ArrayList<FakeMediaPacket>()
        var start = 0L
        while (start < totalFrames) {
            val frames = minOf(framesPerPacket.toLong(), totalFrames - start).toInt()
            val buffer = ByteBuffer.allocate(frames * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until frames) buffer.putShort(sampleValue(start + i, sampleRate))
            packets += FakeMediaPacket(ptsUs = (start * 1_000_000.0 / sampleRate).roundToLong(), bytes = buffer.array())
            start += frames
        }
        return packets
    }

    /** [rawPackets] の位置 [n] の値。 */
    fun sampleValue(n: Long, sampleRate: Int): Short {
        val t = n.toDouble() / sampleRate
        val f0 = 150.0 + 40.0 * kotlin.math.sin(2.0 * Math.PI * 0.13 * t)
        val amplitude = 0.25 + 0.2 * kotlin.math.sin(2.0 * Math.PI * 0.07 * t)
        var noise = n * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
        noise = noise xor (noise ushr 29)
        val jitter = ((noise ushr 40) and 0xFF).toDouble() / 255.0 - 0.5
        val value = amplitude * kotlin.math.sin(2.0 * Math.PI * f0 * t) + 0.02 * jitter
        return (value * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }

    /** `audio/raw` のトラックの形式。 */
    fun rawFormat(sampleRate: Int = 44_100, durationMs: Long? = null): MediaFormat = MediaFormat.createAudioFormat("audio/raw", sampleRate, 1).apply {
        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        if (durationMs != null) setLong(MediaFormat.KEY_DURATION, durationMs * 1_000L)
    }

    /** MPEG-1 Layer III・44.1kHz・モノラルの MP3 トラックの形式。 */
    fun mp3Format(): MediaFormat = MediaFormat.createAudioFormat(MediaCodecAudioStreamDecoder.MIME_TYPE_MP3, FakeBatchMp3Codec.MP3_SAMPLE_RATE, 1)

    /**
     * MPEG-1 Layer III・128kbps・44.1kHz・パディングありの 418 バイトのフレームを [count] 個作る（5 バイト目はフレームの番号から決めた値）。
     */
    fun mp3Packets(count: Int): List<FakeMediaPacket> = List(count) { idx ->
        val frame = ByteArray(418)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte()
        frame[2] = 0x92.toByte()
        frame[3] = 0xC0.toByte()
        frame[4] = (idx % 200 + 1).toByte()
        FakeMediaPacket(
            ptsUs = (idx * FakeBatchMp3Codec.MP3_FRAME_SAMPLES * 1_000_000.0 / FakeBatchMp3Codec.MP3_SAMPLE_RATE).roundToLong(),
            bytes = frame,
        )
    }
}
