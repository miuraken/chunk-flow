package com.miuraken.chunkflow.core.media

import com.miuraken.chunkflow.core.audio.SampleSink
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OnDeviceSpeechTranscriberTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `extractChunkPcm16Shorts_指定したstartMsからendMsの区間のみを16kHz_16bitPCMで切り出す`() = runTest {
        val dummyFile = tempFolder.newFile("sample.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                // 1.0秒分 (16,000 サンプル): 前半 0..500ms は 0.25f、後半 500..1000ms は 0.75f
                val samples = FloatArray(outputSampleRate) { idx ->
                    if (idx < outputSampleRate / 2) 0.25f else 0.75f
                }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = samples.size.toLong(),
                    resampledSamples = samples.size.toLong(),
                    durationMs = 1_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/wav",
                )
            }
        }

        // 500ms..750ms (250ms = 4,000 サンプル @ 16kHz) を抽出
        val shorts = ChunkPcmExtractor.extractChunkPcm16Shorts(
            decoder = fakeDecoder,
            audioFile = dummyFile,
            startMs = 500L,
            endMs = 750L,
            sampleRateHz = 16_000,
        )
        assertNotNull(shorts)
        assertEquals(4_000, shorts.size)
        val expectedShort = (0.75f * Short.MAX_VALUE).toInt().toShort()
        assertTrue(shorts.all { it == expectedShort })
    }

    @Test
    fun `AndroidSpeechRecognizerChunkTranscriber_オンデバイス認識非対応環境ではクラッシュせずnullを返す`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dummyFile = tempFolder.newFile("chunk.mp3").apply { writeBytes(byteArrayOf(0, 1)) }
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                val samples = FloatArray(1_600) { 0.1f }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = samples.size.toLong(),
                    resampledSamples = samples.size.toLong(),
                    durationMs = 100L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/mpeg",
                )
            }
        }

        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = fakeDecoder,
        )
        val result = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 100L,
        )
        assertNull(result)
    }
}
