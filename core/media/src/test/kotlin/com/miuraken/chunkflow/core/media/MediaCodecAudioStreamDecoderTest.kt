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
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

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

    @Test
    fun `decodeFromPosition_seekToが先頭に着地しても圧縮のまま読み飛ばし開始位置の500ms手前からだけデコーダに渡す`() = runTest {
        val tempDir = Files.createTempDirectory("cf_range_skip").toFile()
        try {
            val audioFile = File(tempDir, "range.mp3").apply { writeBytes(ByteArray(64) { 1 }) }
            // 100ms ずつ 400 パケット（40 秒）。開いた直後の IndexSeeker の MP3 と同じく、seekTo は先頭に着地する
            val extractorSession = FakeExtractorSession(monoFormat(), monoPackets(count = 400), seekLandsAtStart = true)
            val codecSessions = mutableListOf<FakeCodecSession>()
            val decoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { extractorSession },
                codecCreator = { _, _ -> FakeCodecSession(rawFormat()).also { codecSessions += it } },
            )

            var landedUs = -1L
            val result = decoder.decodeFromPosition(
                file = audioFile,
                startPositionMs = 35_000L,
                sink = SampleSink { _, _ -> },
                outputSampleRate = 16_000,
                onSeekLandingUs = { landedUs = it },
            )

            assertIs<AudioDecodeResult.Success>(result)
            // 遠い位置なのでシークするが先頭に着地し、そこから 34.5 秒まで読み飛ばす
            assertEquals(listOf(34_500_000L), extractorSession.seekHistoryUs)
            assertEquals(34_500_000L, landedUs)
            // 34.5 秒より前のパケットはデコーダに渡さない（圧縮のまま読み飛ばした）
            val fedPtsUs = codecSessions.single().queuedInputPtsUs
            assertEquals(34_500_000L, fedPtsUs.first())
            assertEquals(55, fedPtsUs.size)
            // 入力の終わりまで正常に読めたので、抽出器は閉じずに次の切り出しで使い回す（デコーダは毎回閉じる）
            assertFalse(extractorSession.closed)
            assertTrue(codecSessions.single().closed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `decodeFromPosition_同じファイルでは抽出器を使い回し別のファイルと解放要求では開き直す`() = runTest {
        val tempDir = Files.createTempDirectory("cf_range_reuse").toFile()
        try {
            val fileA = File(tempDir, "a.mp3").apply { writeBytes(ByteArray(64) { 1 }) }
            val fileB = File(tempDir, "b.mp3").apply { writeBytes(ByteArray(64) { 2 }) }
            val opened = mutableListOf<FakeExtractorSession>()
            val decoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { FakeExtractorSession(monoFormat(), monoPackets(count = 60)).also { opened += it } },
                codecCreator = { _, _ -> FakeCodecSession(rawFormat()) },
            )
            suspend fun landingFor(file: File, startMs: Long): Long {
                var landedUs = -1L
                decoder.decodeFromPosition(file, startMs, SampleSink { _, _ -> }, 16_000) { landedUs = it }
                return landedUs
            }

            assertEquals(2_500_000L, landingFor(fileA, 3_000L))
            // 同じファイルの手前の位置: 開き直さず、同じ抽出器でシークし直す
            assertEquals(500_000L, landingFor(fileA, 1_000L))
            assertEquals(1, opened.size)
            assertFalse(opened[0].closed)

            // 別のファイル: 前の抽出器を閉じて開き直す（近い前方は読み飛ばしだけで着地する）
            assertEquals(1_500_000L, landingFor(fileB, 2_000L))
            assertEquals(2, opened.size)
            assertTrue(opened[0].closed)
            assertTrue(opened[1].seekHistoryUs.isEmpty())

            // 解放を求めると閉じ、次の切り出しでは開き直す
            decoder.releaseRangeSession()
            assertTrue(opened[1].closed)
            assertEquals(1_500_000L, landingFor(fileB, 2_000L))
            assertEquals(3, opened.size)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `decodeFromPosition_sinkの打ち切りでは抽出器を使い回しデコード失敗では閉じる`() = runTest {
        val tempDir = Files.createTempDirectory("cf_range_fail").toFile()
        try {
            val audioFile = File(tempDir, "range.mp3").apply { writeBytes(ByteArray(64) { 1 }) }
            val opened = mutableListOf<FakeExtractorSession>()
            var codecCrashes = false
            val decoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { FakeExtractorSession(monoFormat(), monoPackets(count = 60)).also { opened += it } },
                codecCreator = { _, _ ->
                    if (codecCrashes) {
                        object : CodecSession {
                            override val outputFormat: MediaFormat get() = rawFormat()
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
                    } else {
                        FakeCodecSession(rawFormat())
                    }
                },
            )

            // チャンクの末尾に達した sink が例外で止める（ChunkPcmExtractor と同じ止め方）。抽出器は使い回す
            assertFailsWith<CancellationException> {
                decoder.decodeFromPosition(
                    file = audioFile,
                    startPositionMs = 1_000L,
                    sink = SampleSink { _, _ -> throw CancellationException("reached chunk end") },
                    outputSampleRate = 16_000,
                )
            }
            assertFalse(opened.single().closed)

            // デコードに失敗したら、状態がわからない抽出器は閉じる
            codecCrashes = true
            val failed = decoder.decodeFromPosition(audioFile, 2_000L, SampleSink { _, _ -> }, 16_000)
            assertEquals(AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED), failed)
            assertTrue(opened.single().closed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `ChunkPcmExtractor_MediaCodecデコーダでは開始位置の手前まで読み飛ばし時刻どおりの区間を切り出す`() = runTest {
        val tempDir = Files.createTempDirectory("cf_range_chunk").toFile()
        try {
            val audioFile = File(tempDir, "chunk.mp3").apply { writeBytes(ByteArray(64) { 1 }) }
            // i 番目のパケット（i * 100ms から 100ms）は値 (i + 1) * 100 で埋める
            val packets = (0 until 60).map { idx ->
                FakePacket(ptsUs = idx * 100_000L, pcmBytes = monoPcmBytes(frames = 1_600, value = ((idx + 1) * 100).toShort()))
            }
            val extractorSession = FakeExtractorSession(monoFormat(), packets, seekLandsAtStart = true)
            val codecSessions = mutableListOf<FakeCodecSession>()
            val decoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { extractorSession },
                codecCreator = { _, _ -> FakeCodecSession(rawFormat()).also { codecSessions += it } },
            )

            val shorts = ChunkPcmExtractor.extractChunkPcm16Shorts(
                decoder = decoder,
                audioFile = audioFile,
                startMs = 3_000L,
                endMs = 3_200L,
                sampleRateHz = 16_000,
            )

            assertNotNull(shorts)
            assertEquals(3_200, shorts.size)
            // 3,000〜3,100ms はパケット 30（値 3,100）、3,100〜3,200ms はパケット 31（値 3,200）
            assertTrue(shorts.copyOfRange(0, 1_600).all { it == 3_100.toShort() })
            assertTrue(shorts.copyOfRange(1_600, 3_200).all { it == 3_200.toShort() })
            assertTrue(codecSessions.single().queuedInputPtsUs.all { it >= 2_500_000L })
            assertFalse(extractorSession.closed)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `positionExtractorForRange_IndexSeekerのMP3で開いた直後のseekToが先頭に着地しても読み飛ばしで開始位置の手前のフレームに正確に着地する`() = runTest {
        val tempDir = Files.createTempDirectory("cf_range_mp3").toFile()
        try {
            // 無音の MP3 フレーム 2,000 個（約 52 秒）。実際の教材は使わない
            val file = File(tempDir, "silent.mp3").apply { writeBytes(silentMp3Bytes(frameCount = 2_000)) }
            val frameUs = MP3_SAMPLES_PER_FRAME * 1_000_000.0 / MP3_SAMPLE_RATE
            MediaCodecAudioStreamDecoder.openCompatExtractorSession(RuntimeEnvironment.getApplication(), file).use { session ->
                session.selectTrack(0)

                // 1. ADR 0006 の IndexSeeker では、開いた直後（索引が先頭だけ）の seekTo は先頭に着地する。このため読み飛ばしが要る
                session.seekTo(30_000_000L)
                assertEquals(0L, session.sampleTime)

                // 2. 読み飛ばしで、29.5 秒（開始位置の 500ms 手前）以降の最初のフレームに着地する。時刻はフレーム数どおり
                val landedUs = MediaCodecAudioStreamDecoder.positionExtractorForRange(session, 30_000_000L)
                assertTrue(landedUs >= 29_500_000L && landedUs < 29_500_000L + frameUs, "landedUs=$landedUs")
                val frameIndex = (landedUs / frameUs).roundToLong()
                assertTrue(abs(landedUs - (frameIndex * frameUs).toLong()) <= 1L, "landedUs=$landedUs frameIndex=$frameIndex")

                // 3. 同じ抽出器で手前へ戻る: 読んだ範囲の索引でシークし、10 秒の 500ms 手前のフレームに着地する
                val backUs = MediaCodecAudioStreamDecoder.positionExtractorForRange(session, 10_000_000L)
                assertTrue(backUs >= 9_500_000L && backUs < 9_500_000L + frameUs + 2, "backUs=$backUs")
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun monoFormat(): MediaFormat = MediaFormat.createAudioFormat("audio/mpeg", 16_000, 1)

    private fun rawFormat(): MediaFormat = MediaFormat.createAudioFormat("audio/raw", 16_000, 1).apply {
        setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
    }

    /** 16kHz モノラルで 100ms（1,600 フレーム）ずつのパケットを [count] 個作る。 */
    private fun monoPackets(count: Int): List<FakePacket> = List(count) { idx ->
        FakePacket(ptsUs = idx * 100_000L, pcmBytes = monoPcmBytes(frames = 1_600, value = 8_192))
    }

    private fun monoPcmBytes(frames: Int, value: Short): ByteArray = ByteBuffer.allocate(frames * 2)
        .order(ByteOrder.nativeOrder())
        .apply { repeat(frames) { putShort(value) } }
        .array()

    /**
     * MPEG-1 Layer III・44.1kHz・128kbps・モノラルの無音フレーム（417 バイト、パディングなし）を [frameCount] 個並べた MP3。
     * LAME の CBR と同じく、先頭フレームのサイド情報の直後に `Info` 識別子を置く（[IndexSeekDataSource] が隠す）。
     */
    private fun silentMp3Bytes(frameCount: Int): ByteArray {
        val frameSize = 417
        val bytes = ByteArray(frameSize * frameCount)
        for (i in 0 until frameCount) {
            val offset = i * frameSize
            bytes[offset] = 0xFF.toByte()
            bytes[offset + 1] = 0xFB.toByte()
            bytes[offset + 2] = 0x90.toByte()
            bytes[offset + 3] = 0xC0.toByte()
        }
        "Info".encodeToByteArray().copyInto(bytes, destinationOffset = 4 + 17)
        return bytes
    }

    private data class FakePacket(
        val ptsUs: Long,
        val pcmBytes: ByteArray,
    )

    private class FakeExtractorSession(
        private val format: MediaFormat,
        private val packets: List<FakePacket>,
        private val neverEos: Boolean = false,
        /** true なら `seekTo` は常に先頭に着地する（開いた直後の IndexSeeker の MP3 と同じ）。 */
        private val seekLandsAtStart: Boolean = false,
    ) : ExtractorSession {
        private var index = 0
        var closed = false
        val seekHistoryUs = mutableListOf<Long>()

        override val trackCount: Int get() = 1
        override fun getTrackFormat(index: Int): MediaFormat = format
        override fun selectTrack(index: Int) = Unit

        override fun seekTo(timeUs: Long) {
            seekHistoryUs += timeUs
            if (seekLandsAtStart) {
                index = 0
                return
            }
            val found = packets.indexOfFirst { it.ptsUs >= timeUs }
            index = if (found >= 0) found else packets.lastIndex.coerceAtLeast(0)
        }

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

        /** デコーダに渡された入力（EOS を除く）の PTS（渡された順）。 */
        val queuedInputPtsUs = mutableListOf<Long>()

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
            if ((flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0) {
                queuedInputPtsUs += presentationTimeUs
            }
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

        override fun getOutputBuffer(index: Int): ByteBuffer? = currentOutputBytes?.let { ByteBuffer.wrap(it).order(ByteOrder.nativeOrder()) }

        override fun releaseOutputBuffer(index: Int, render: Boolean) {
            currentOutputBytes = null
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        /** MPEG-1 Layer III の 1 フレームのサンプル数。 */
        const val MP3_SAMPLES_PER_FRAME = 1_152.0

        /** テスト用 MP3 のサンプルレート（Hz）。 */
        const val MP3_SAMPLE_RATE = 44_100.0
    }
}
