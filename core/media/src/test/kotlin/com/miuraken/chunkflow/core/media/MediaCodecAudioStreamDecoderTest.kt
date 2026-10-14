package com.miuraken.chunkflow.core.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.model.AnalysisError
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MediaCodecAudioStreamDecoderTest {

    @Test
    fun `16bitステレオPCMデコード_モノラル化と16kHzリサンプルと進捗通知とギャップレス情報取得が動く`() = runTest {
        val tempDir = Files.createTempDirectory("cf_dec_test").toFile()
        try {
            val audioFile = File(tempDir, "test.m4a").apply { writeBytes(ByteArray(64) { 1 }) }
            val inputRate = 32_000
            val channels = 2
            val framesPerPacket = 1_600 // 50ms @ 32kHz
            val packetCount = 4 // 合計 200ms = 6400 フレーム -> 16kHz で 3200 サンプル

            val trackFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", inputRate, channels).apply {
                setInteger(MediaFormat.KEY_ENCODER_DELAY, 1_024)
                setInteger(MediaFormat.KEY_ENCODER_PADDING, 512)
                setLong(MediaFormat.KEY_DURATION, 200_000L)
            }

            val packets = List(packetCount) { idx ->
                val pcmBytes = ByteBuffer.allocate(framesPerPacket * channels * 2).order(ByteOrder.nativeOrder())
                for (f in 0 until framesPerPacket) {
                    pcmBytes.putShort(16_384) // L = +0.5
                    pcmBytes.putShort(16_384) // R = +0.5
                }
                FakePacket(ptsUs = idx * 50_000L, pcmBytes = pcmBytes.array())
            }

            var nowMs = 1_000L
            val extractorSession = FakeExtractorSession(trackFormat, packets)
            val codecSession = FakeCodecSession(
                outputFormat = MediaFormat.createAudioFormat("audio/raw", inputRate, channels).apply {
                    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                },
                onOutputDequeued = { nowMs += 300L },
            )

            val decoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { extractorSession },
                codecCreator = { _, _ -> codecSession },
                elapsedRealtimeMs = { nowMs },
                progressIntervalMs = 250L,
            )

            var totalResampledCount = 0L
            val progresses = mutableListOf<DecodeProgress>()
            val result = decoder.decode(
                file = audioFile,
                sink = SampleSink { _, count -> totalResampledCount += count },
                outputSampleRate = 16_000,
                onProgress = { progresses.add(it) },
            )

            val success = assertIs<AudioDecodeResult.Success>(result)
            assertEquals(32_000, success.sampleRate)
            assertEquals(2, success.channels)
            assertEquals(6_400L, success.decodedFrames)
            assertEquals(3_200L, success.resampledSamples)
            assertEquals(3_200L, totalResampledCount)
            assertEquals(200L, success.durationMs)
            assertEquals(1_024, success.encoderDelay)
            assertEquals(512, success.encoderPadding)
            assertTrue(progresses.isNotEmpty())
            assertEquals(1.0f, progresses.last().fraction)
            assertTrue(extractorSession.closed)
            assertTrue(codecSession.closed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `FLAC終端の同一PTS反復_8回連続でEOSとみなしformatDurationUsで出力フレームを打ち切る`() = runTest {
        val tempDir = Files.createTempDirectory("cf_flac_test").toFile()
        try {
            val audioFile = File(tempDir, "stuck.flac").apply { writeBytes(ByteArray(32) { 2 }) }
            val inputRate = 16_000
            val channels = 1
            // 本来の長さは 100ms (1600 フレーム) だが、最後の PTS が無限に返り続けるケースを再現（ADR 0005 決定 4）
            val trackFormat = MediaFormat.createAudioFormat("audio/flac", inputRate, channels).apply {
                setLong(MediaFormat.KEY_DURATION, 100_000L) // 100ms
            }

            val packetFrames = 400 // 25ms
            val packetBytes = ByteBuffer.allocate(packetFrames * 2).order(ByteOrder.nativeOrder()).apply {
                repeat(packetFrames) { putShort(8_192) }
            }.array()
            val packets = (0 until 20).map { idx ->
                val pts = if (idx < 4) idx * 25_000L else 75_000L // 4 パケット目以降ずっと同じ PTS
                FakePacket(ptsUs = pts, pcmBytes = packetBytes)
            }

            val extractorSession = FakeExtractorSession(trackFormat, packets, neverEos = true)
            val codecSession = FakeCodecSession(
                outputFormat = MediaFormat.createAudioFormat("audio/raw", inputRate, channels),
            )
            val decoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { extractorSession },
                codecCreator = { _, _ -> codecSession },
            )

            var outSamples = 0L
            val result = decoder.decode(
                file = audioFile,
                sink = SampleSink { _, count -> outSamples += count },
                outputSampleRate = 16_000,
            )

            val success = assertIs<AudioDecodeResult.Success>(result)
            // 100ms = 1600 フレームで打ち切られること
            assertEquals(1_600L, success.decodedFrames)
            assertEquals(1_600L, outSamples)
            assertEquals(100L, success.durationMs)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `エラー種別マッピングとキャンセル_IOとUNSUPPORTED_FORMATとDECODE_FAILEDとキャンセル伝播`() = runTest {
        val tempDir = Files.createTempDirectory("cf_err_test").toFile()
        try {
            // 1. 存在しないファイル -> IO
            val missingDecoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { error("not reached") },
                codecCreator = { _, _ -> error("not reached") },
            )
            val ioRes = missingDecoder.decode(File(tempDir, "missing.mp3"), SampleSink { _, _ -> })
            assertEquals(AudioDecodeResult.Failure(AnalysisError.IO), ioRes)

            val validFile = File(tempDir, "file.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

            // 2. 音声トラックなし -> UNSUPPORTED_FORMAT
            val videoFormat = MediaFormat.createVideoFormat("video/avc", 640, 480)
            val noAudioDecoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { FakeExtractorSession(videoFormat, emptyList()) },
                codecCreator = { _, _ -> error("not reached") },
            )
            assertEquals(
                AudioDecodeResult.Failure(AnalysisError.UNSUPPORTED_FORMAT),
                noAudioDecoder.decode(validFile, SampleSink { _, _ -> }),
            )

            // 3. デコード中の例外 -> DECODE_FAILED
            val audioFormat = MediaFormat.createAudioFormat("audio/mpeg", 16_000, 1)
            val failingDecoder = MediaCodecAudioStreamDecoder(
                extractorOpener = {
                    FakeExtractorSession(audioFormat, listOf(FakePacket(0L, ByteArray(32))))
                },
                codecCreator = { _, _ ->
                    object : CodecSession {
                        override val outputFormat: MediaFormat get() = audioFormat
                        override fun dequeueInputBuffer(timeoutUs: Long): Int = error("codec crashed")
                        override fun getInputBuffer(index: Int): ByteBuffer? = null
                        override fun queueInputBuffer(
                            index: Int,
                            offset: Int,
                            size: Int,
                            presentationTimeUs: Long,
                            flags: Int,
                        ) = Unit
                        override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int = -1
                        override fun getOutputBuffer(index: Int): ByteBuffer? = null
                        override fun releaseOutputBuffer(index: Int, render: Boolean) = Unit
                        override fun close() = Unit
                    }
                },
            )
            assertEquals(
                AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED),
                failingDecoder.decode(validFile, SampleSink { _, _ -> }),
            )

            // 4. コルーチンキャンセル時は CancellationException を握りつぶさず再スローしリソースを閉じる
            val pkt = FakePacket(0L, ByteArray(320))
            val exSession = FakeExtractorSession(audioFormat, listOf(pkt, pkt))
            val cdSession = FakeCodecSession(
                outputFormat = MediaFormat.createAudioFormat("audio/raw", 16_000, 1),
            )
            val cancelDecoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { exSession },
                codecCreator = { _, _ -> cdSession },
                elapsedRealtimeMs = { 1_000L },
                progressIntervalMs = 0L,
            )
            assertFailsWith<CancellationException> {
                cancelDecoder.decode(
                    file = validFile,
                    sink = SampleSink { _, _ -> },
                    onProgress = { throw CancellationException("User cancelled") },
                )
            }
            assertTrue(exSession.closed)
            assertTrue(cdSession.closed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private data class FakePacket(
        val ptsUs: Long,
        val pcmBytes: ByteArray,
    )

    private class FakeExtractorSession(
        private val format: MediaFormat,
        private val packets: List<FakePacket>,
        private val neverEos: Boolean = false,
    ) : ExtractorSession {
        private var index = 0
        var closed = false

        override val trackCount: Int get() = 1
        override fun getTrackFormat(index: Int): MediaFormat = format
        override fun selectTrack(index: Int) = Unit

        override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
            if (packets.isEmpty()) return -1
            if (index >= packets.size) {
                if (!neverEos) return -1
                index = packets.lastIndex
            }
            val bytes = packets[index].pcmBytes
            buffer.position(offset)
            buffer.put(bytes)
            return bytes.size
        }

        override val sampleTime: Long
            get() = if (packets.isEmpty()) -1L else packets[index.coerceAtMost(packets.lastIndex)].ptsUs

        override fun advance(): Boolean {
            index++
            return neverEos || index < packets.size
        }

        override fun close() {
            closed = true
        }
    }

    private class FakeCodecSession(
        override val outputFormat: MediaFormat,
        private val onOutputDequeued: () -> Unit = {},
    ) : CodecSession {
        private val inputBuf = ByteBuffer.allocate(64 * 1024).order(ByteOrder.nativeOrder())
        private val queuedOutputs = ArrayDeque<QueuedOutput>()
        private var currentOutputBytes: ByteArray? = null
        private var emittedFormatChange = false
        var closed = false

        private data class QueuedOutput(
            val bytes: ByteArray,
            val ptsUs: Long,
            val flags: Int,
        )

        override fun dequeueInputBuffer(timeoutUs: Long): Int = if (queuedOutputs.size < 4) 0 else -1

        override fun getInputBuffer(index: Int): ByteBuffer = inputBuf

        override fun queueInputBuffer(
            index: Int,
            offset: Int,
            size: Int,
            presentationTimeUs: Long,
            flags: Int,
        ) {
            val copy = ByteArray(size)
            inputBuf.position(offset)
            inputBuf.get(copy, 0, size)
            queuedOutputs.addLast(QueuedOutput(copy, presentationTimeUs, flags))
        }

        override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
            if (!emittedFormatChange) {
                emittedFormatChange = true
                return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            }
            val next = queuedOutputs.removeFirstOrNull() ?: return MediaCodec.INFO_TRY_AGAIN_LATER
            onOutputDequeued()
            currentOutputBytes = next.bytes
            info.set(0, next.bytes.size, next.ptsUs, next.flags)
            return 0
        }

        override fun getOutputBuffer(index: Int): ByteBuffer? =
            currentOutputBytes?.let { ByteBuffer.wrap(it).order(ByteOrder.nativeOrder()) }

        override fun releaseOutputBuffer(index: Int, render: Boolean) {
            currentOutputBytes = null
        }

        override fun close() {
            closed = true
        }
    }
}
