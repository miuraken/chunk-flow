package com.miuraken.chunkflow.core.media

import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `extractChunkPcm16LeBytes_endMs到達時点でデコードを即座に打ち切り後続サンプルをデコードしない`() = runTest {
        val dummyFile = tempFolder.newFile("long_audio.mp3").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        var blocksEmitted = 0
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                // 1ブロック = 100ms (1,600サンプル) を最大 600 ブロック (60秒分) 流そうとするデコーダ
                val block = FloatArray(1_600) { 0.5f }
                for (i in 0 until 600) {
                    blocksEmitted += 1
                    sink.onSamples(block, block.size)
                }
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = 600L * 1_600L,
                    resampledSamples = 600L * 1_600L,
                    durationMs = 60_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/mpeg",
                )
            }
        }

        // 0ms..300ms (3ブロック = 4,800サンプル = 9,600 bytes) のみ要求
        val pcmBytes = ChunkPcmExtractor.extractChunkPcm16LeBytes(
            decoder = fakeDecoder,
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 300L,
            sampleRateHz = 16_000,
        )
        assertNotNull(pcmBytes)
        assertEquals(9_600, pcmBytes.size)
        // 300ms (3ブロック) 到達時点で即座に停止し、残りの 597 ブロックはデコードされない
        assertEquals(3, blocksEmitted)
    }

    @Test
    fun `toBcp47LocaleTag_短い言語コードを地域付きBCP47タグに変換し既存タグは維持する`() {
        assertEquals("ja-JP", toBcp47LocaleTag("ja"))
        assertEquals("en-US", toBcp47LocaleTag("en"))
        assertEquals("ko-KR", toBcp47LocaleTag("ko"))
        assertEquals("zh-CN", toBcp47LocaleTag("zh"))
        assertEquals("yue-HK", toBcp47LocaleTag("yue"))
        assertEquals("hi-IN", toBcp47LocaleTag("hi"))
        assertEquals("es-ES", toBcp47LocaleTag("es"))
        assertEquals("fr-FR", toBcp47LocaleTag("fr"))
        assertEquals("de-DE", toBcp47LocaleTag("de"))
        assertEquals("pt-BR", toBcp47LocaleTag("pt"))
        assertEquals("en-GB", toBcp47LocaleTag("en-GB"))
        assertEquals("zh-TW", toBcp47LocaleTag("zh_TW"))
    }

    @Test
    fun `padPcmWithSilence_先頭150msと末尾600msの無音バイト列を付加する`() {
        val raw = ByteArray(1_000) { 0x12 }
        val padded = ChunkPcmExtractor.padPcmWithSilence(
            pcm16LeBytes = raw,
            sampleRateHz = 16_000,
        )
        // 16000Hz * 2bytes * 150ms / 1000 = 4,800 bytes
        // 16000Hz * 2bytes * 600ms / 1000 = 19,200 bytes
        assertEquals(4_800 + 1_000 + 19_200, padded.size)
        assertTrue(padded.sliceArray(0 until 4_800).all { it == 0.toByte() })
        assertTrue(padded.sliceArray(4_800 until 5_800).all { it == 0x12.toByte() })
        assertTrue(padded.sliceArray(5_800 until padded.size).all { it == 0.toByte() })
    }

    @Test
    fun `transcribePcm16Le_target言語で認識失敗時にbase言語へフォールバックし元の言語タグを返す`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val calls = mutableListOf<Pair<String, Boolean>>()
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult = AudioDecodeResult.Success(16_000, 1, 0, 0, 0, 0, 0, "audio/mpeg")
        }

        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = fakeDecoder,
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { false },
            attemptRunner = { _, _, bcp47Tag, preferOnDevice ->
                calls += bcp47Tag to preferOnDevice
                if (bcp47Tag == "ja-JP") {
                    SpeechRecognitionAttemptResult(text = "こんにちは", confidence = 0.91f)
                } else {
                    null
                }
            },
        )

        val result = transcriber.transcribePcm16Le(
            pcm16LeBytes = ByteArray(640) { 1 },
            sampleRateHz = 16_000,
            languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
        )
        assertNotNull(result)
        assertEquals("こんにちは", result.text)
        assertEquals(LanguageTag("ja"), result.language)
        assertEquals(0.91f, result.confidence)
        assertEquals(listOf("ko-KR" to true, "ja-JP" to true), calls)
    }

    @Test
    fun `transcribePcm16Le_preferOnDevice失敗時に標準SpeechRecognizerへフォールバックする`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val calls = mutableListOf<Pair<String, Boolean>>()
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult = AudioDecodeResult.Success(16_000, 1, 0, 0, 0, 0, 0, "audio/mpeg")
        }

        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = fakeDecoder,
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47Tag, preferOnDevice ->
                calls += bcp47Tag to preferOnDevice
                if (!preferOnDevice) {
                    SpeechRecognitionAttemptResult(text = "Fallback success", confidence = 0.85f)
                } else {
                    null
                }
            },
        )

        val result = transcriber.transcribePcm16Le(
            pcm16LeBytes = ByteArray(640) { 1 },
            sampleRateHz = 16_000,
            languagePair = LanguagePair(target = LanguageTag("en"), base = null),
        )
        assertNotNull(result)
        assertEquals("Fallback success", result.text)
        assertEquals(LanguageTag("en"), result.language)
        assertEquals(listOf("en-US" to true, "en-US" to false), calls)
    }

    @Test
    fun `resolveRecognitionCallbackOutcome_onResultsが空またはNO_MATCHやTIMEOUT時にpartialTextを採用する`() {
        val fromEmptyResults = AndroidSpeechRecognizerChunkTranscriber.resolveRecognitionCallbackOutcome(
            resultsText = "",
            confidence = null,
            partialText = "途中までの認識テキスト",
            errorCode = null,
        )
        assertNotNull(fromEmptyResults)
        assertEquals("途中までの認識テキスト", fromEmptyResults.text)

        val fromNoMatch = AndroidSpeechRecognizerChunkTranscriber.resolveRecognitionCallbackOutcome(
            resultsText = null,
            confidence = null,
            partialText = "部分認識結果",
            errorCode = SpeechRecognizer.ERROR_NO_MATCH,
        )
        assertNotNull(fromNoMatch)
        assertEquals("部分認識結果", fromNoMatch.text)

        val fromTimeout = AndroidSpeechRecognizerChunkTranscriber.resolveRecognitionCallbackOutcome(
            resultsText = null,
            confidence = null,
            partialText = "タイムアウト前の結果",
            errorCode = SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        )
        assertNotNull(fromTimeout)
        assertEquals("タイムアウト前の結果", fromTimeout.text)

        val fromClientError = AndroidSpeechRecognizerChunkTranscriber.resolveRecognitionCallbackOutcome(
            resultsText = null,
            confidence = null,
            partialText = "クライアントエラー時",
            errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        )
        assertNull(fromClientError)
    }

    @Test
    fun `buildRecognizerIntent_EXTRA_ENABLE_LANGUAGE_DETECTIONを設定せずBCP47言語タグとPARTIAL_RESULTSを設定する`() {
        val context = RuntimeEnvironment.getApplication()
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult = AudioDecodeResult.Success(16_000, 1, 0, 0, 0, 0, 0, "audio/mpeg")
        }
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(context, fakeDecoder)
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val intent = transcriber.buildRecognizerIntent(
                readSide = pipe[0],
                sampleRateHz = 16_000,
                bcp47LocaleTag = "ja-JP",
                preferOnDevice = true,
            )
            assertEquals("ja-JP", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
            assertEquals("ja-JP", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE))
            assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
            assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false))
            assertFalse(intent.hasExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION))
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
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
