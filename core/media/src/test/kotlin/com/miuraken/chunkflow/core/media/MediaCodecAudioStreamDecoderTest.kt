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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
    fun `decodeCoarseOverviewWaveform_音声の秒数と横幅から算出した間隔でseekToサンプリングし全体長の波形を即座に生成する`() = runTest {
        val tempDir = Files.createTempDirectory("cf_coarse_test").toFile()
        try {
            val audioFile = File(tempDir, "overview.m4a").apply { writeBytes(ByteArray(64) { 1 }) }
            val inputRate = 16_000
            val channels = 1
            // 10秒 (10,000ms) の音声を 4 カラム (2,500ms 間隔) でシークサンプリングするケース
            val trackFormat = MediaFormat.createAudioFormat("audio/mp4a-latm", inputRate, channels).apply {
                setLong(MediaFormat.KEY_DURATION, 10_000_000L)
            }

            // 500ms ごとに 20 パケット (0ms..9,500ms) を配置し、4 カラム (0ms, 2,500ms, 5,000ms, 7,500ms) でシークさせる
            val packets = (0 until 20).map { pktIdx ->
                val colIdx = (pktIdx / 5).coerceIn(0, 3)
                val amp = listOf(0.2f, 0.4f, 0.6f, 0.8f)[colIdx]
                val shortVal = (amp * Short.MAX_VALUE).toInt().toShort()
                val frames = 320 // 20ms @ 16kHz
                val pcmBytes = ByteBuffer.allocate(frames * 2).order(ByteOrder.nativeOrder()).apply {
                    repeat(frames) { putShort(shortVal) }
                }.array()
                FakePacket(ptsUs = pktIdx * 500_000L, pcmBytes = pcmBytes)
            }

            val extractorSession = FakeExtractorSession(trackFormat, packets)
            val codecSession = FakeCodecSession(
                outputFormat = MediaFormat.createAudioFormat("audio/raw", inputRate, channels).apply {
                    setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                },
            )
            val decoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { extractorSession },
                codecCreator = { _, _ -> codecSession },
            )

            val waveform = decoder.decodeCoarseOverviewWaveform(
                file = audioFile,
                expectedDurationMs = 10_000L,
                targetColumns = 4,
            )
            kotlin.test.assertNotNull(waveform)
            assertEquals(10, waveform.binMs)
            assertEquals(1_000, waveform.peak.size)
            assertEquals(10_000L, waveform.durationMs)
            assertEquals(3, extractorSession.seekHistoryUs.size)
            assertTrue(codecSession.flushCount >= 3)

            // 4 カラム分の振幅が段階的に大きく展開されていること
            val p0 = waveform.peak[0].toInt() and 0xFF
            val p1 = waveform.peak[250].toInt() and 0xFF
            val p2 = waveform.peak[500].toInt() and 0xFF
            val p3 = waveform.peak[750].toInt() and 0xFF
            assertTrue(p0 > 0, "p0=$p0")
            assertTrue(p1 > p0 && p2 > p1 && p3 > p2, "p0=$p0, p1=$p1, p2=$p2, p3=$p3")
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

    @Test
    fun `decode_標準のMP3デコーダではフレームをまとめて渡し出力は1フレームずつのときと同じになる`() = runTest {
        val tempDir = Files.createTempDirectory("cf_mp3_batch").toFile()
        try {
            val file = File(tempDir, "batch.mp3").apply { writeBytes(ByteArray(64) { 1 }) }
            val packets = mp3FramePackets(count = 300)

            suspend fun decodeWith(codecName: String): Triple<AudioDecodeResult, FakeMp3Codec, FloatArray> {
                val codec = FakeMp3Codec(codecName)
                val decoder = MediaCodecAudioStreamDecoder(
                    extractorOpener = { FakeExtractorSession(mp3Format(), packets) },
                    codecCreator = { _, _ -> codec },
                )
                val pcm = ArrayList<Float>()
                val result = decoder.decode(
                    file = file,
                    sink = SampleSink { samples, count -> for (i in 0 until count) pcm += samples[i] },
                    outputSampleRate = 16_000,
                )
                return Triple(result, codec, pcm.toFloatArray())
            }

            val (batched, batchedCodec, batchedPcm) = decodeWith("c2.android.mp3.decoder")
            val (single, singleCodec, singlePcm) = decodeWith("c2.vendor.mp3.decoder")

            val batchedSuccess = assertIs<AudioDecodeResult.Success>(batched)
            val singleSuccess = assertIs<AudioDecodeResult.Success>(single)
            assertEquals(300L * 1_152L, batchedSuccess.decodedFrames)
            assertEquals(singleSuccess.decodedFrames, batchedSuccess.decodedFrames)
            // 標準のデコーダには 128 個ずつまとめ、それ以外のデコーダには 1 個ずつ渡す
            assertEquals(listOf(128, 128, 44), batchedCodec.framesPerInput)
            assertEquals(List(300) { 1 }, singleCodec.framesPerInput)
            // 入力の時刻は、その入力の先頭のフレームの時刻
            assertEquals(listOf(packets[0].ptsUs, packets[128].ptsUs, packets[256].ptsUs), batchedCodec.inputPtsUs)
            // 解析に渡る PCM（16kHz）は 1 サンプルも違わない
            assertContentEquals(singlePcm, batchedPcm)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `decode_ヘッダの読めないフレームはまとめずに単独で渡し前後のフレームの順番を保つ`() = runTest {
        val tempDir = Files.createTempDirectory("cf_mp3_badframe").toFile()
        try {
            val file = File(tempDir, "bad.mp3").apply { writeBytes(ByteArray(64) { 1 }) }
            val packets = mp3FramePackets(count = 20).mapIndexed { idx, packet ->
                if (idx == 7) packet.copy(pcmBytes = packet.pcmBytes.copyOf().also { it[0] = 0 }) else packet
            }
            val codec = FakeMp3Codec("c2.android.mp3.decoder")
            val decoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { FakeExtractorSession(mp3Format(), packets) },
                codecCreator = { _, _ -> codec },
            )

            val result = decoder.decode(file = file, sink = SampleSink { _, _ -> }, outputSampleRate = 16_000)

            val success = assertIs<AudioDecodeResult.Success>(result)
            // 0〜6 をまとめ、7 は単独（偽物は読めない入力を無音 1 フレームとして出す）、8〜19 をまとめる
            assertEquals(listOf(7, 0, 12), codec.framesPerInput)
            assertEquals(listOf(packets[0].ptsUs, packets[7].ptsUs, packets[8].ptsUs), codec.inputPtsUs)
            assertEquals(20L * 1_152L, success.decodedFrames)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `decode_まとめて渡したのに出力が足りなければ失敗にして以後は1フレームずつ渡す`() = runTest {
        val tempDir = Files.createTempDirectory("cf_mp3_drop").toFile()
        try {
            val file = File(tempDir, "drop.mp3").apply { writeBytes(ByteArray(64) { 1 }) }
            val packets = mp3FramePackets(count = 300)
            val codecs = mutableListOf<FakeMp3Codec>()
            val decoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { FakeExtractorSession(mp3Format(), packets) },
                // 標準のデコーダの名前だが、まとめて渡すと先頭のフレームしか出さない
                codecCreator = { _, _ -> FakeMp3Codec("c2.android.mp3.decoder", dropAllButFirstFrame = true).also { codecs += it } },
            )

            // 1 回目: まとめて渡したが出力が足りない（時間軸がずれる）ので、結果を使わず失敗にする
            val first = decoder.decode(file = file, sink = SampleSink { _, _ -> }, outputSampleRate = 16_000)
            assertEquals(AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED), first)
            assertEquals(listOf(128, 128, 44), codecs[0].framesPerInput)

            // 2 回目: 1 フレームずつ渡すので、正しい長さで読める
            val second = decoder.decode(file = file, sink = SampleSink { _, _ -> }, outputSampleRate = 16_000)
            val success = assertIs<AudioDecodeResult.Success>(second)
            assertEquals(300L * 1_152L, success.decodedFrames)
            assertEquals(List(300) { 1 }, codecs[1].framesPerInput)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `decode_ギャップレス情報のあるMP3でデコーダが先頭と末尾を削っても失敗にせず以後もまとめて渡す`() = runTest {
        val tempDir = Files.createTempDirectory("cf_mp3_gapless").toFile()
        try {
            val file = File(tempDir, "gapless.mp3").apply { writeBytes(ByteArray(64) { 1 }) }
            val packets = mp3FramePackets(count = 300)
            // iTunes で作った MP3（ID3 の iTunSMPB）と同じ値。MediaCodec は出力の先頭と末尾からこの分を削る
            val format = mp3Format().apply {
                setInteger(MediaFormat.KEY_ENCODER_DELAY, 528)
                setInteger(MediaFormat.KEY_ENCODER_PADDING, 2_110)
            }
            val codecs = mutableListOf<FakeMp3Codec>()
            val decoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { FakeExtractorSession(format, packets) },
                codecCreator = { _, _ ->
                    FakeMp3Codec("c2.android.mp3.decoder", trimStartSamples = 528, trimEndSamples = 2_110).also { codecs += it }
                },
            )

            repeat(2) { attempt ->
                val result = decoder.decode(file = file, sink = SampleSink { _, _ -> }, outputSampleRate = 16_000)

                val success = assertIs<AudioDecodeResult.Success>(result)
                assertEquals(300L * 1_152L - 528L - 2_110L, success.decodedFrames)
                // 削られた分を「ずれ」とみなさないので、2 回目も 1 フレームずつに落とさずまとめて渡す
                assertEquals(listOf(128, 128, 44), codecs[attempt].framesPerInput)
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `isMp3BatchOutputLengthMismatched_ギャップレスで削られる分は許し欠落とはみ出しは見逃さない`() {
        fun mismatched(decoded: Long, delay: Int, padding: Int): Boolean = MediaCodecAudioStreamDecoder.isMp3BatchOutputLengthMismatched(
            decodedFrames = decoded,
            expectedFrames = EXPECTED_300_FRAMES,
            encoderDelay = delay,
            encoderPadding = padding,
        )

        // ずれがない・削られる分ちょうど・削られる分 + 許容（2 フレーム）まで
        assertFalse(mismatched(EXPECTED_300_FRAMES, delay = 0, padding = 0))
        assertFalse(mismatched(EXPECTED_300_FRAMES - 2_638L, delay = 528, padding = 2_110))
        assertFalse(mismatched(EXPECTED_300_FRAMES - 2_638L - 2_304L, delay = 528, padding = 2_110))
        // それより短い・許容より長い
        assertTrue(mismatched(EXPECTED_300_FRAMES - 2_638L - 2_305L, delay = 528, padding = 2_110))
        assertTrue(mismatched(EXPECTED_300_FRAMES + 2_305L, delay = 528, padding = 2_110))
        // ギャップレス情報がなければ、2 フレームを超える欠落は失敗
        assertTrue(mismatched(EXPECTED_300_FRAMES - 3L * 1_152L, delay = 0, padding = 0))
        // 申告された遅延とパディングが大きすぎても、まとめた入力 1 つ分（MPEG-2 の 16 フレーム）の欠落は見逃さない
        assertTrue(mismatched(EXPECTED_300_FRAMES - 16L * 576L, delay = 0, padding = 100_000))
    }

    @Test
    fun `decodeFromPosition_チャンクの切り出しではMP3のフレームを16個までにまとめる`() = runTest {
        val tempDir = Files.createTempDirectory("cf_mp3_range").toFile()
        try {
            val file = File(tempDir, "range.mp3").apply { writeBytes(ByteArray(64) { 1 }) }
            val packets = mp3FramePackets(count = 300)
            val codec = FakeMp3Codec("c2.android.mp3.decoder")
            val decoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { FakeExtractorSession(mp3Format(), packets) },
                codecCreator = { _, _ -> codec },
            )

            var landedUs = -1L
            val result = decoder.decodeFromPosition(
                file = file,
                startPositionMs = 2_000L,
                sink = SampleSink { _, _ -> },
                outputSampleRate = 16_000,
                onSeekLandingUs = { landedUs = it },
            )

            val success = assertIs<AudioDecodeResult.Success>(result)
            // 1.5 秒（開始位置の 500ms 手前）以降の最初のフレーム 58 から読む
            assertEquals(packets[58].ptsUs, landedUs)
            assertEquals(List(15) { 16 } + 2, codec.framesPerInput)
            assertEquals(242L * 1_152L, success.decodedFrames)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `decodeCoarseOverviewWaveform_シークが目標より手前に着地する形式では誤った全体波形を出さずnullを返す`() = runTest {
        val tempDir = Files.createTempDirectory("cf_coarse_inaccurate").toFile()
        try {
            val file = File(tempDir, "overview.mp3").apply { writeBytes(ByteArray(64) { 1 }) }
            // 100ms ずつ 100 パケット（10 秒）。開いた直後の IndexSeeker の MP3 と同じく、seekTo は先頭に着地する
            val extractorSession = FakeExtractorSession(monoFormat(), monoPackets(count = 100), seekLandsAtStart = true)
            val decoder = MediaCodecAudioStreamDecoder(
                extractorOpener = { extractorSession },
                codecCreator = { _, _ -> FakeCodecSession(rawFormat()) },
            )

            val waveform = decoder.decodeCoarseOverviewWaveform(file = file, expectedDurationMs = 10_000L, targetColumns = 4)

            assertNull(waveform)
            // 2 列目（2.5 秒）へのシークが先頭に着地した時点で諦める
            assertEquals(listOf(2_500_000L), extractorSession.seekHistoryUs)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** MPEG-1 Layer III・44.1kHz・モノラルの MP3 トラックの形式。 */
    private fun mp3Format(): MediaFormat = MediaFormat.createAudioFormat("audio/mpeg", 44_100, 1)

    /**
     * MPEG-1 Layer III・128kbps・44.1kHz・パディングありの 418 バイトのフレームを [count] 個作る（中身は 5 バイト目の番号以外 0）。
     * 時刻は 1 フレーム 1,152 サンプル（約 26.1ms）ずつ進める。
     */
    private fun mp3FramePackets(count: Int): List<FakePacket> = List(count) { idx ->
        val frame = ByteArray(418)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte()
        frame[2] = 0x92.toByte()
        frame[3] = 0xC0.toByte()
        frame[4] = (idx % 200 + 1).toByte()
        FakePacket(ptsUs = (idx * MP3_SAMPLES_PER_FRAME * 1_000_000.0 / MP3_SAMPLE_RATE).roundToLong(), pcmBytes = frame)
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
        var flushCount = 0

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

        override fun flush() {
            flushCount += 1
            queuedOutputs.clear()
            currentOutputBytes = null
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * Android 標準の MP3 デコーダ（`C2SoftMp3Dec`）の振る舞いをまねた偽物。
     * 入力に入ったフレームをヘッダの長さで区切り、1 フレームにつき 1,152 サンプル（値はフレームの 5 バイト目 × 100）を出す。
     * ヘッダの読めない入力は、無音 1 フレームぶんを出す（テスト用の簡略化）。
     *
     * @param dropAllButFirstFrame true なら、まとめて渡されても先頭のフレームしか出さない（まとめ入れに対応しないデコーダ）。
     * @param trimStartSamples 出力の先頭から削るサンプル数（MediaCodec がエンコーダの遅延に従って削るのをまねる）。
     * @param trimEndSamples 出力の末尾から削るサンプル数（同じくパディング。EOS まで出さずに持っておき、EOS で捨てる）。
     */
    private class FakeMp3Codec(
        override val codecName: String,
        private val dropAllButFirstFrame: Boolean = false,
        private val trimStartSamples: Int = 0,
        private val trimEndSamples: Int = 0,
    ) : CodecSession {
        private val inputBuf = ByteBuffer.allocate(256 * 1024)
        private val pendingOutputs = ArrayDeque<Pair<ByteArray, Int>>()
        private var currentOutput: ByteArray? = null
        private var emittedFormatChange = false

        /** まだ出していない PCM（値とサンプル数の並び）。 */
        private val heldRuns = ArrayDeque<Pair<Short, Int>>()
        private var heldSampleCount = 0
        private var remainingStartTrim = trimStartSamples

        /** 入力ごとに入っていた、ヘッダの読めるフレームの数（EOS を除く、渡された順）。 */
        val framesPerInput = mutableListOf<Int>()

        /** 入力ごとの PTS（EOS を除く、渡された順）。 */
        val inputPtsUs = mutableListOf<Long>()

        override val outputFormat: MediaFormat = MediaFormat.createAudioFormat("audio/raw", 44_100, 1).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

        override fun dequeueInputBuffer(timeoutUs: Long): Int = if (pendingOutputs.size < 4) 0 else -1

        override fun getInputBuffer(index: Int): ByteBuffer = inputBuf

        override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
            if ((flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                // 末尾に持っておいた分（パディング）は出さずに捨てる
                heldRuns.clear()
                heldSampleCount = 0
                pendingOutputs.addLast(ByteArray(0) to flags)
                return
            }
            inputPtsUs += presentationTimeUs
            val view = inputBuf.duplicate().apply {
                position(0)
                limit(offset + size)
            }
            val frameValues = mutableListOf<Short>()
            var position = offset
            var frames = 0
            while (position + Mp3FrameHeader.HEADER_SIZE_BYTES <= offset + size) {
                val frame = Mp3FrameHeader.parse(view, position) ?: break
                if (!dropAllButFirstFrame || frames == 0) {
                    frameValues += ((view.get(position + 4).toInt() and 0xFF) * 100).toShort()
                }
                frames++
                position += frame.frameSizeBytes
            }
            if (frames == 0) frameValues += 0
            framesPerInput += frames
            for (value in frameValues) {
                heldRuns.addLast(value to MP3_FRAME_SAMPLES)
                heldSampleCount += MP3_FRAME_SAMPLES
            }
            // 先頭（遅延）を削る
            while (remainingStartTrim > 0 && heldRuns.isNotEmpty()) {
                val (value, count) = heldRuns.removeFirst()
                val dropped = minOf(count, remainingStartTrim)
                if (count > dropped) heldRuns.addFirst(value to count - dropped)
                remainingStartTrim -= dropped
                heldSampleCount -= dropped
            }
            // 末尾（パディング）になり得る分を残して出す
            var emitCount = (heldSampleCount - trimEndSamples).coerceAtLeast(0)
            val pcm = ByteBuffer.allocate(emitCount * 2).order(ByteOrder.nativeOrder())
            heldSampleCount -= emitCount
            while (emitCount > 0) {
                val (value, count) = heldRuns.removeFirst()
                val taken = minOf(count, emitCount)
                repeat(taken) { pcm.putShort(value) }
                if (count > taken) heldRuns.addFirst(value to count - taken)
                emitCount -= taken
            }
            pendingOutputs.addLast(pcm.array() to 0)
        }

        override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
            if (!emittedFormatChange) {
                emittedFormatChange = true
                return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
            }
            val (bytes, flags) = pendingOutputs.removeFirstOrNull() ?: return MediaCodec.INFO_TRY_AGAIN_LATER
            currentOutput = bytes
            info.set(0, bytes.size, 0L, flags)
            return 0
        }

        override fun getOutputBuffer(index: Int): ByteBuffer? = currentOutput?.let { ByteBuffer.wrap(it).order(ByteOrder.nativeOrder()) }

        override fun releaseOutputBuffer(index: Int, render: Boolean) {
            currentOutput = null
        }

        override fun close() = Unit

        private companion object {
            const val MP3_FRAME_SAMPLES = 1_152
        }
    }

    private companion object {
        /** MPEG-1 Layer III の 1 フレームのサンプル数。 */
        const val MP3_SAMPLES_PER_FRAME = 1_152.0

        /** テスト用 MP3 のサンプルレート（Hz）。 */
        const val MP3_SAMPLE_RATE = 44_100.0

        /** MPEG-1 Layer III の 300 フレームから見込まれる出力の長さ（1 チャンネルあたりのサンプル数）。 */
        const val EXPECTED_300_FRAMES = 300L * 1_152L
    }
}
