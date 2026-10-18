package com.miuraken.chunkflow.core.media

import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.MlKitRecognitionMode
import com.miuraken.chunkflow.core.model.OnDeviceRecognitionModelState
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
        assertEquals("zh-CN", toBcp47LocaleTag("zh-cn"))
        assertEquals("zh-TW", toBcp47LocaleTag("zh-tw"))
        assertEquals("zh-TW", toBcp47LocaleTag("zh_TW"))
        assertEquals("el-GR", toBcp47LocaleTag("el"))
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
    fun `extractLeadingSpeechProbePcm_先頭無音をスキップし約2秒のプローブを切り出し長チャンクと短チャンクを識別する`() {
        val sampleRate = 16_000
        // 4.0秒 (64,000サンプル = 128,000 bytes): 先頭 200ms (3,200サンプル) は無音、残り 3.8秒は振幅 2,000
        val longSamples = ShortArray(sampleRate * 4) { idx ->
            if (idx < 3_200) 0 else 2_000
        }
        val longBytes = ChunkPcmExtractor.shortsToPcm16LeBytes(longSamples)

        val longProbe = ChunkPcmExtractor.extractLeadingSpeechProbePcm(longBytes, sampleRate)
        // 発話開始 200ms (6,400 bytes) の 60ms (1,920 bytes) 手前 = 4,480 bytes
        assertEquals(4_480, longProbe.onsetByteOffset)
        // 2,000ms = 64,000 bytes
        assertEquals(64_000, longProbe.probePcm16LeBytes.size)
        assertFalse(longProbe.isFullChunkCovered)

        // 1.8秒 (28,800サンプル = 57,600 bytes) の短チャンクは isFullChunkCovered == true
        val shortSamples = ShortArray((sampleRate * 1.8).toInt()) { 2_000 }
        val shortBytes = ChunkPcmExtractor.shortsToPcm16LeBytes(shortSamples)
        val shortProbe = ChunkPcmExtractor.extractLeadingSpeechProbePcm(shortBytes, sampleRate)
        assertTrue(shortProbe.isFullChunkCovered)
    }

    @Test
    fun `scoreProbeCandidateForLanguage_日本語発話のハングル音写よりも自然な日本語テキストが高スコアになる`() {
        // 日本語音声「それでは、『日本語』という言い方です」が韓国語認識器でハングル音写されたケース
        val transliteratedKo = SpeechRecognitionAttemptResult(
            text = "소래 대화 니혼고 더유이가 다 됐어",
            confidence = 0.78f,
        )
        val naturalJa = SpeechRecognitionAttemptResult(
            text = "それでは日本語という言い方です",
            confidence = 0.80f,
        )

        val koScoreOnJaAudio = scoreProbeCandidateForLanguage(transliteratedKo, "ko")
        val jaScoreOnJaAudio = scoreProbeCandidateForLanguage(naturalJa, "ja")
        assertTrue(
            jaScoreOnJaAudio > koScoreOnJaAudio,
            "Expected jaScore ($jaScoreOnJaAudio) > koScore ($koScoreOnJaAudio)",
        )

        // 韓国語音声「안녕하세요 만나서 반갑습니다」が日本語認識器でカタカナ音写されたケース
        val naturalKo = SpeechRecognitionAttemptResult(
            text = "안녕하세요 만나서 반갑습니다",
            confidence = 0.82f,
        )
        val katakanaJa = SpeechRecognitionAttemptResult(
            text = "アンニョンハセヨマンナソパンガプスミダ",
            confidence = 0.75f,
        )
        val koScoreOnKoAudio = scoreProbeCandidateForLanguage(naturalKo, "ko")
        val jaScoreOnKoAudio = scoreProbeCandidateForLanguage(katakanaJa, "ja")
        assertTrue(
            koScoreOnKoAudio > jaScoreOnKoAudio,
            "Expected koScore ($koScoreOnKoAudio) > jaScore ($jaScoreOnKoAudio)",
        )
    }

    @Test
    fun `transcribePcm16Le_長チャンクで2秒プローブにより日本語と韓国語を判定し勝った言語で全体STTを実行する`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val calls = mutableListOf<Pair<String, Int>>()
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult = AudioDecodeResult.Success(16_000, 1, 0, 0, 0, 0, 0, "audio/mpeg")
        }

        // 4.0秒 (128,000 bytes @ 16kHz) のチャンク（2秒プローブ = 64,000 bytes）
        val fourSecPcm = ByteBuffer.allocate(128_000).order(ByteOrder.LITTLE_ENDIAN).apply {
            for (i in 0 until 64_000) {
                putShort(1_500)
            }
        }.array()

        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = fakeDecoder,
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { pcmBytes, _, bcp47Tag, _ ->
                calls += bcp47Tag to pcmBytes.size
                when {
                    // 2秒プローブ (64,000 bytes) で ko-KR も音写ハングルを返すが、ja-JP が自然な日本語を返すケース
                    pcmBytes.size == 64_000 && bcp47Tag == "ko-KR" ->
                        SpeechRecognitionAttemptResult(text = "소래 대화 니혼고 더유이가 다 됐어", confidence = 0.75f)

                    pcmBytes.size == 64_000 && bcp47Tag == "ja-JP" ->
                        SpeechRecognitionAttemptResult(text = "それでは日本語という", confidence = 0.80f)

                    // 勝った ja-JP でチャンク全体 (128,000 bytes) の STT を実行
                    pcmBytes.size == 128_000 && bcp47Tag == "ja-JP" ->
                        SpeechRecognitionAttemptResult(
                            text = "それでは日本語という言い方です聞いて繰り返してください",
                            confidence = 0.92f,
                        )

                    else -> null
                }
            },
        )

        val result = transcriber.transcribePcm16Le(
            pcm16LeBytes = fourSecPcm,
            sampleRateHz = 16_000,
            languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
        )
        assertNotNull(result)
        assertEquals("それでは日本語という言い方です聞いて繰り返してください", result.text)
        assertEquals(LanguageTag("ja"), result.language)
        assertEquals(0.92f, result.confidence)
        assertEquals(
            listOf(
                "ko-KR" to 64_000,
                "ja-JP" to 64_000,
                "ja-JP" to 128_000,
            ),
            calls,
        )
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

        // 短チャンク (640 bytes <= 2.4秒) は 2秒プローブがチャンク全体をカバーするため、全体STTを再実行せずプローブ結果を返す
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
    fun `transcribePcm16Le_onDevice利用可能時はpreferOnDeviceのみを使用し物理マイクを開かない`() = runTest {
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
                null
            },
        )

        val result = transcriber.transcribePcm16Le(
            pcm16LeBytes = ByteArray(640) { 1 },
            sampleRateHz = 16_000,
            languagePair = LanguagePair(target = LanguageTag("en"), base = null),
        )
        assertNull(result)
        // preferOnDevice = false (物理マイクを開く createSpeechRecognizer) は呼ばれないこと
        assertEquals(listOf("en-US" to true), calls)
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
            errorCode = SpeechRecognizer.ERROR_CLIENT,
        )
        assertNull(fromClientError)

        // 言語モデルが使えないエラーは、部分結果を採用せず「本文なし + エラーコード」として返す
        val fromLanguageNotSupported = AndroidSpeechRecognizerChunkTranscriber.resolveRecognitionCallbackOutcome(
            resultsText = null,
            confidence = null,
            partialText = "非対応言語",
            errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        )
        assertNotNull(fromLanguageNotSupported)
        assertEquals("", fromLanguageNotSupported.text)
        assertTrue(fromLanguageNotSupported.isLanguageModelUnavailable)

        val fromLanguageUnavailable = AndroidSpeechRecognizerChunkTranscriber.resolveRecognitionCallbackOutcome(
            resultsText = null,
            confidence = null,
            partialText = "",
            errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        )
        assertNotNull(fromLanguageUnavailable)
        assertEquals(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE, fromLanguageUnavailable.errorCode)
        assertTrue(fromLanguageUnavailable.isLanguageModelUnavailable)
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

    @Test
    fun `transcribeChunk_同一チャンクと同一言語の再呼び出し時はattemptRunnerを再実行せずキャッシュ結果を即座に返す`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dummyFile = tempFolder.newFile("cached_single.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        var decodeCallCount = 0
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                decodeCallCount += 1
                val samples = FloatArray(outputSampleRate) { 0.3f }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(outputSampleRate, 1, samples.size.toLong(), samples.size.toLong(), 1_000L, 0, 0, "audio/wav")
            }
        }

        val runnerCalls = mutableListOf<String>()
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = fakeDecoder,
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47Tag, _ ->
                runnerCalls += bcp47Tag
                SpeechRecognitionAttemptResult(text = "안녕하세요", confidence = 0.93f)
            },
        )

        val first = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 1_000L,
            languagePair = LanguagePair(target = LanguageTag("ko"), base = null),
        )
        assertNotNull(first)
        assertEquals("안녕하세요", first.text)
        assertEquals(LanguageTag("ko"), first.language)
        assertEquals(listOf("ko-KR"), runnerCalls)
        assertEquals(1, decodeCallCount)

        // 同じチャンク・同じ言語で再度呼び出すとデコードも SpeechRecognizer も走らずキャッシュが返る
        val second = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 1_000L,
            languagePair = LanguagePair(target = LanguageTag("ko"), base = null),
        )
        assertNotNull(second)
        assertEquals("안녕하세요", second.text)
        assertEquals(LanguageTag("ko"), second.language)
        assertEquals(listOf("ko-KR"), runnerCalls)
        assertEquals(1, decodeCallCount)
    }

    @Test
    fun `transcribeChunk_短チャンクの2言語プローブ後はもう一方の単一言語指定でもattemptRunnerを呼ばずキャッシュから即座に返す`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dummyFile = tempFolder.newFile("short_probe_cache.wav").apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                // 1.5秒 (<= 2.4秒の短チャンク)
                val samples = FloatArray((outputSampleRate * 1.5).toInt()) { 0.25f }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(outputSampleRate, 1, samples.size.toLong(), samples.size.toLong(), 1_500L, 0, 0, "audio/wav")
            }
        }

        val runnerCalls = mutableListOf<String>()
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = fakeDecoder,
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47Tag, _ ->
                runnerCalls += bcp47Tag
                when (bcp47Tag) {
                    "ko-KR" -> SpeechRecognitionAttemptResult(text = "만나서 반갑습니다", confidence = 0.85f)
                    "ja-JP" -> SpeechRecognitionAttemptResult(text = "お会いできて嬉しいです", confidence = 0.90f)
                    else -> null
                }
            },
        )

        // 1. 2言語プローブ実行（ja が勝つ）
        val initial = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 1_500L,
            languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
        )
        assertNotNull(initial)
        assertEquals("お会いできて嬉しいです", initial.text)
        assertEquals(LanguageTag("ja"), initial.language)
        assertEquals(listOf("ko-KR", "ja-JP"), runnerCalls)

        // 2. もう一方の言語 (ko) または元の言語 (ja) を単一言語指定で呼ぶと、プローブ時のキャッシュがヒットし attemptRunner は一切呼ばれない
        val switchedToKo = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 1_500L,
            languagePair = LanguagePair(target = LanguageTag("ko"), base = null),
        )
        assertNotNull(switchedToKo)
        assertEquals("만나서 반갑습니다", switchedToKo.text)
        assertEquals(LanguageTag("ko"), switchedToKo.language)
        assertEquals(listOf("ko-KR", "ja-JP"), runnerCalls)

        val switchedBackToJa = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 1_500L,
            languagePair = LanguagePair(target = LanguageTag("ja"), base = null),
        )
        assertNotNull(switchedBackToJa)
        assertEquals("お会いできて嬉しいです", switchedBackToJa.text)
        assertEquals(LanguageTag("ja"), switchedBackToJa.language)
        assertEquals(listOf("ko-KR", "ja-JP"), runnerCalls)
    }

    @Test
    fun `transcribeChunk_長チャンクで別言語へ切替時は1回だけ全体STTを実行し以降の往復トグルはキャッシュから即座に返す`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dummyFile = tempFolder.newFile("long_toggle_cache.wav").apply { writeBytes(byteArrayOf(9, 10, 11, 12)) }
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                // 4.0秒 (> 2.4秒の長チャンク = 64,000 サンプル = 128,000 bytes)
                val samples = FloatArray(outputSampleRate * 4) { 0.25f }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(outputSampleRate, 1, samples.size.toLong(), samples.size.toLong(), 4_000L, 0, 0, "audio/wav")
            }
        }

        val runnerCalls = mutableListOf<Pair<String, Int>>()
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = fakeDecoder,
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { pcmBytes, _, bcp47Tag, _ ->
                runnerCalls += bcp47Tag to pcmBytes.size
                when {
                    pcmBytes.size == 64_000 && bcp47Tag == "ko-KR" ->
                        SpeechRecognitionAttemptResult(text = "소래 대화 니혼고", confidence = 0.72f)

                    pcmBytes.size == 64_000 && bcp47Tag == "ja-JP" ->
                        SpeechRecognitionAttemptResult(text = "それでは日本語という", confidence = 0.85f)

                    pcmBytes.size == 128_000 && bcp47Tag == "ja-JP" ->
                        SpeechRecognitionAttemptResult(text = "それでは日本語という言い方です", confidence = 0.92f)

                    pcmBytes.size == 128_000 && bcp47Tag == "ko-KR" ->
                        SpeechRecognitionAttemptResult(text = "안녕하세요 만나서 반갑습니다 여러분", confidence = 0.94f)

                    else -> null
                }
            },
        )

        // 1. 初回 2言語プローブ + 勝った ja の全体STT (計3回)
        val initial = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 4_000L,
            languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
        )
        assertNotNull(initial)
        assertEquals("それでは日本語という言い方です", initial.text)
        assertEquals(LanguageTag("ja"), initial.language)
        assertEquals(3, runnerCalls.size)

        // 2. ユーザーが韓国語 (ko) に手動切替 -> ko の全体STT (128,000 bytes) が 1 回だけ走る
        val firstKo = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 4_000L,
            languagePair = LanguagePair(target = LanguageTag("ko"), base = null),
        )
        assertNotNull(firstKo)
        assertEquals("안녕하세요 만나서 반갑습니다 여러분", firstKo.text)
        assertEquals(LanguageTag("ko"), firstKo.language)
        assertEquals(4, runnerCalls.size)
        assertEquals("ko-KR" to 128_000, runnerCalls.last())

        // 3. 以降の ja <-> ko の往復切替はすべてキャッシュヒットし attemptRunner は二度と走らない
        val toggleBackJa = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 4_000L,
            languagePair = LanguagePair(target = LanguageTag("ja"), base = null),
        )
        assertNotNull(toggleBackJa)
        assertEquals("それでは日本語という言い方です", toggleBackJa.text)
        assertEquals(LanguageTag("ja"), toggleBackJa.language)
        assertEquals(4, runnerCalls.size)

        val toggleAgainKo = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 4_000L,
            languagePair = LanguagePair(target = LanguageTag("ko"), base = null),
        )
        assertNotNull(toggleAgainKo)
        assertEquals("안녕하세요 만나서 반갑습니다 여러분", toggleAgainKo.text)
        assertEquals(LanguageTag("ko"), toggleAgainKo.language)
        assertEquals(4, runnerCalls.size)
    }

    @Test
    fun `transcribeChunk_WhisperTinyLanguageDetector判定時はSpeechRecognizerを勝った言語で1回だけ呼び出す`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dummyFile = tempFolder.newFile("whisper_lid_single_stt.wav").apply { writeBytes(byteArrayOf(21, 22, 23, 24)) }
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                // 3.5秒 (56,000 サンプル = 112,000 bytes)
                val samples = FloatArray((outputSampleRate * 3.5).toInt()) { 0.35f }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = samples.size.toLong(),
                    resampledSamples = samples.size.toLong(),
                    durationMs = 3_500L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/wav",
                )
            }
        }

        val runnerCalls = mutableListOf<Pair<String, Int>>()
        var lidCalls = 0
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = fakeDecoder,
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { pcmBytes, _, bcp47Tag, _ ->
                runnerCalls += bcp47Tag to pcmBytes.size
                SpeechRecognitionAttemptResult(
                    text = "それでは日本語という言い方です",
                    confidence = 0.93f,
                )
            },
            languageDetector = { pcmShorts, targetTag, baseTag ->
                lidCalls += 1
                assertEquals(56_000, pcmShorts.size)
                assertEquals("ko", targetTag)
                assertEquals("ja", baseTag)
                LanguagePairPrediction(
                    selectedTag = "ja",
                    fallbackTag = "ko",
                    selectedProbability = 0.99f,
                    targetProbability = 0.01f,
                    baseProbability = 0.99f,
                    inferenceTimeMs = 12L,
                )
            },
        )

        val result = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 3_500L,
            languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
        )
        assertNotNull(result)
        assertEquals("それでは日本語という言い方です", result.text)
        assertEquals(LanguageTag("ja"), result.language)
        assertEquals(0.93f, result.confidence)
        assertEquals(1, lidCalls)
        // 2秒プローブを行わず、LID が選んだ ja-JP でチャンク全体 (112,000 bytes) を 1 回だけ SpeechRecognizer に渡す
        assertEquals(listOf("ja-JP" to 112_000), runnerCalls)
    }

    @Test
    fun `transcribeChunk_forceRefreshがtrueのときキャッシュを破棄して再認識しキャッシュを新しい結果で更新する`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dummyFile = tempFolder.newFile("force_refresh_chunk.wav").apply { writeBytes(byteArrayOf(31, 32, 33, 34)) }
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                val samples = FloatArray(outputSampleRate) { 0.3f }
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

        var attemptCount = 0
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = fakeDecoder,
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, _, _ ->
                attemptCount += 1
                SpeechRecognitionAttemptResult(
                    text = "認識結果_$attemptCount",
                    confidence = 0.90f,
                )
            },
        )

        val langPair = LanguagePair(target = LanguageTag("ja"), base = null)

        // 1回目: 初回認識で "認識結果_1" が返りキャッシュされる
        val first = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 1_000L,
            languagePair = langPair,
            forceRefresh = false,
            highPriority = false,
        )
        assertNotNull(first)
        assertEquals("認識結果_1", first.text)
        assertEquals(1, attemptCount)

        // 2回目: forceRefresh = false ならキャッシュヒットし attemptRunner は呼ばれない
        val cached = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 1_000L,
            languagePair = langPair,
            forceRefresh = false,
            highPriority = false,
        )
        assertNotNull(cached)
        assertEquals("認識結果_1", cached.text)
        assertEquals(1, attemptCount)

        // 3回目: forceRefresh = true ならキャッシュを破棄して再認識し "認識結果_2" が返る
        val refreshed = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 1_000L,
            languagePair = langPair,
            forceRefresh = true,
            highPriority = true,
        )
        assertNotNull(refreshed)
        assertEquals("認識結果_2", refreshed.text)
        assertEquals(2, attemptCount)

        // 4回目: 再び forceRefresh = false で呼ぶと更新後のキャッシュ "認識結果_2" が返る
        val afterRefreshCached = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 1_000L,
            languagePair = langPair,
            forceRefresh = false,
            highPriority = false,
        )
        assertNotNull(afterRefreshCached)
        assertEquals("認識結果_2", afterRefreshCached.text)
        assertEquals(2, attemptCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `transcribeChunk_highPriorityがtrueのリクエストは待機中の通常優先度リクエストを追い越して先に実行される`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dummyFile = tempFolder.newFile("priority_queue.wav").apply { writeBytes(byteArrayOf(41, 42, 43, 44)) }
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                // 4秒分 (0..4000ms)
                val samples = FloatArray(outputSampleRate * 4) { 0.3f }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = samples.size.toLong(),
                    resampledSamples = samples.size.toLong(),
                    durationMs = 4_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/wav",
                )
            }
        }

        val firstAttemptStarted = CompletableDeferred<Unit>()
        val releaseFirstAttempt = CompletableDeferred<Unit>()
        val executionOrder = mutableListOf<String>()

        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = fakeDecoder,
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47Tag, _ ->
                executionOrder += bcp47Tag
                if (bcp47Tag == "en-US") {
                    firstAttemptStarted.complete(Unit)
                    releaseFirstAttempt.await()
                }
                SpeechRecognitionAttemptResult(text = "Result:$bcp47Tag", confidence = 0.9f)
            },
        )

        val dispatcher = UnconfinedTestDispatcher(testScheduler)

        // 1. 通常優先度 (en) が最初にロックを取得して実行中になる
        val jobNormal1 = launch(dispatcher) {
            transcriber.transcribeChunk(
                audioFile = dummyFile,
                startMs = 0L,
                endMs = 1_000L,
                languagePair = LanguagePair(target = LanguageTag("en"), base = null),
                forceRefresh = false,
                highPriority = false,
            )
        }
        firstAttemptStarted.await()

        // 2. 実行中に通常優先度 (ko, fr) が順番に待機キューへ入る
        val jobNormal2 = launch(dispatcher) {
            transcriber.transcribeChunk(
                audioFile = dummyFile,
                startMs = 1_000L,
                endMs = 2_000L,
                languagePair = LanguagePair(target = LanguageTag("ko"), base = null),
                forceRefresh = false,
                highPriority = false,
            )
        }
        val jobNormal3 = launch(dispatcher) {
            transcriber.transcribeChunk(
                audioFile = dummyFile,
                startMs = 2_000L,
                endMs = 3_000L,
                languagePair = LanguagePair(target = LanguageTag("fr"), base = null),
                forceRefresh = false,
                highPriority = false,
            )
        }

        // 3. 後から高優先度 (ja) が到着する
        val jobHigh = launch(dispatcher) {
            transcriber.transcribeChunk(
                audioFile = dummyFile,
                startMs = 3_000L,
                endMs = 4_000L,
                languagePair = LanguagePair(target = LanguageTag("ja"), base = null),
                forceRefresh = true,
                highPriority = true,
            )
        }

        // 4. 1件目を解放すると、待機していた ko/fr より先に高優先度の ja が実行される
        releaseFirstAttempt.complete(Unit)
        jobNormal1.join()
        jobNormal2.join()
        jobNormal3.join()
        jobHigh.join()

        assertEquals(
            listOf("en-US", "ja-JP", "ko-KR", "fr-FR"),
            executionOrder,
        )
    }

    @Test
    fun `scoreProbeCandidateForLanguage_カタカナ外来語化に助詞やですが混ざっても減点されSpeechRecognizerのconfidenceが高い学習言語側が勝つ`() {
        // 1. 韓国語音声が日本語認識器で「カタカナ＋です／助詞」に外来語化されたケース
        val katakanaWithDesuJa = SpeechRecognitionAttemptResult(
            text = "アンニョンハセヨマンナソです",
            confidence = 0.78f,
        )
        val naturalKo = SpeechRecognitionAttemptResult(
            text = "안녕하세요 만나서",
            confidence = 0.80f,
        )
        val jaKatakanaDesuScore = scoreProbeCandidateForLanguage(katakanaWithDesuJa, "ja")
        val koScore = scoreProbeCandidateForLanguage(naturalKo, "ko")
        assertTrue(
            koScore > jaKatakanaDesuScore,
            "Expected koScore ($koScore) > jaKatakanaDesuScore ($jaKatakanaDesuScore)",
        )
        assertTrue(isKatakanaTransliterationSuspected(katakanaWithDesuJa.text, "ja"))

        // 2. 英語音声が日本語認識器で低確信度の漢字かな混じり文に空耳変換された場合でも、confidence差を重視して英語側が勝つケース
        val lowConfidenceHallucinatedJa = SpeechRecognitionAttemptResult(
            text = "腹を空かせてチェックインです",
            confidence = 0.55f,
        )
        val highConfidenceEn = SpeechRecognitionAttemptResult(
            text = "Hello I would like to check in",
            confidence = 0.88f,
        )
        val hallucinatedJaScore = scoreProbeCandidateForLanguage(lowConfidenceHallucinatedJa, "ja")
        val enScore = scoreProbeCandidateForLanguage(highConfidenceEn, "en")
        assertTrue(
            enScore > hallucinatedJaScore,
            "Expected enScore ($enScore) > hallucinatedJaScore ($hallucinatedJaScore)",
        )
    }

    @Test
    fun `transcribeChunk_WhisperTinyが日本語と誤判定してもカタカナ外来語化や低confidence時は学習言語へフォールバック検証し学習言語側を採用する`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dummyFile = tempFolder.newFile("whisper_lid_katakana_fallback.wav").apply {
            writeBytes(byteArrayOf(51, 52, 53, 54))
        }
        val fakeDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                val samples = FloatArray(outputSampleRate * 2) { 0.35f }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = samples.size.toLong(),
                    resampledSamples = samples.size.toLong(),
                    durationMs = 2_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/wav",
                )
            }
        }

        val runnerCalls = mutableListOf<String>()
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = fakeDecoder,
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47Tag, _ ->
                runnerCalls += bcp47Tag
                when (bcp47Tag) {
                    "ja-JP" -> SpeechRecognitionAttemptResult(
                        text = "グッドモーニングマイネームはジョンです",
                        confidence = 0.72f,
                    )

                    "en-US" -> SpeechRecognitionAttemptResult(
                        text = "Good morning my name is John",
                        confidence = 0.91f,
                    )

                    else -> null
                }
            },
            languageDetector = { _, _, _ ->
                // WhisperTiny LID が誤って ja を 0.82 の確率で選んだ状況を模擬
                LanguagePairPrediction(
                    selectedTag = "ja",
                    fallbackTag = "en",
                    selectedProbability = 0.82f,
                    targetProbability = 0.18f,
                    baseProbability = 0.82f,
                    inferenceTimeMs = 10L,
                )
            },
        )

        val result = transcriber.transcribeChunk(
            audioFile = dummyFile,
            startMs = 0L,
            endMs = 2_000L,
            languagePair = LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja")),
        )
        assertNotNull(result)
        // カタカナ外来語化の疑いにより en-US も検証され、confidence が高く自然な英語側が採用されること
        assertEquals(listOf("ja-JP", "en-US"), runnerCalls)
        assertEquals("Good morning my name is John", result.text)
        assertEquals(LanguageTag("en"), result.language)
        assertEquals(0.91f, result.confidence)
    }

    @Test
    fun `transcribeChunk_LIDが韓国語と確信し韓国語モデルが未ダウンロードのとき日本語の音写で代用せず言語判定のみを返しダウンロードを1回だけ要求する`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val gateway = FakeRecognitionModelGateway()
        val runnerCalls = mutableListOf<String>()
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 2),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47Tag, _ ->
                runnerCalls += bcp47Tag
                when (bcp47Tag) {
                    // 韓国語の言語モデルが端末にない（ERROR_LANGUAGE_UNAVAILABLE）
                    "ko-KR" -> SpeechRecognitionAttemptResult(
                        text = "",
                        errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    )

                    // 日本語認識器では韓国語音声がカタカナに音写される
                    "ja-JP" -> SpeechRecognitionAttemptResult(text = "アンニョンハセヨ", confidence = 0.8f)

                    else -> null
                }
            },
            languageDetector = { _, _, _ ->
                LanguagePairPrediction(
                    selectedTag = "ko",
                    fallbackTag = "ja",
                    selectedProbability = 0.97f,
                    targetProbability = 0.97f,
                    baseProbability = 0.03f,
                    inferenceTimeMs = 8L,
                )
            },
            modelGateway = gateway,
        )
        val koJaPair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja"))

        val first = transcriber.transcribeChunk(
            audioFile = tempFolder.newFile("ko_missing_model_1.wav").apply { writeBytes(byteArrayOf(1, 2)) },
            startMs = 0L,
            endMs = 2_000L,
            languagePair = koJaPair,
        )
        assertNotNull(first)
        assertTrue(first.isLanguageOnly)
        assertEquals("", first.text)
        assertEquals(LanguageTag("ko"), first.language)
        assertEquals(0.97f, first.confidence)
        assertEquals(OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID, first.engineId)
        // 日本語認識器（音写）は呼ばれない
        assertEquals(listOf("ko-KR"), runnerCalls)
        // ダウンロードを自動で要求し、状態は「ダウンロード中」
        assertEquals(listOf("ko-KR"), gateway.downloadRequests)
        assertEquals(
            mapOf(LanguageTag("ko") to OnDeviceRecognitionModelState.DOWNLOADING),
            transcriber.recognitionModelIssues.value,
        )

        // 2 チャンク目でも言語判定のみを返し、ダウンロードの要求は繰り返さない
        val second = transcriber.transcribeChunk(
            audioFile = tempFolder.newFile("ko_missing_model_2.wav").apply { writeBytes(byteArrayOf(3, 4)) },
            startMs = 0L,
            endMs = 2_000L,
            languagePair = koJaPair,
        )
        assertNotNull(second)
        assertTrue(second.isLanguageOnly)
        assertEquals(listOf("ko-KR"), gateway.downloadRequests)

        // ダウンロードが終わると、問題の一覧から外れる
        gateway.emit(RecognitionModelDownloadEvent.SUCCESS)
        assertTrue(transcriber.recognitionModelIssues.value.isEmpty())
    }

    @Test
    fun `transcribeChunk_LIDが曖昧で韓国語モデルが未ダウンロードのとき自然な日本語は採用し音写は言語判定のみにする`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        var jaResult = SpeechRecognitionAttemptResult(text = "それでは日本語という言い方です", confidence = 0.9f)
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 2),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47Tag, _ ->
                when (bcp47Tag) {
                    "ko-KR" -> SpeechRecognitionAttemptResult(
                        text = "",
                        errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    )

                    "ja-JP" -> jaResult

                    else -> null
                }
            },
            languageDetector = { _, _, _ ->
                LanguagePairPrediction(
                    selectedTag = "ko",
                    fallbackTag = "ja",
                    selectedProbability = 0.6f,
                    targetProbability = 0.6f,
                    baseProbability = 0.4f,
                    inferenceTimeMs = 8L,
                )
            },
            modelGateway = FakeRecognitionModelGateway(),
        )
        val koJaPair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja"))

        // 自然な日本語として認識できた場合は日本語の結果を採用する
        val natural = transcriber.transcribeChunk(
            audioFile = tempFolder.newFile("ambiguous_natural_ja.wav").apply { writeBytes(byteArrayOf(5, 6)) },
            startMs = 0L,
            endMs = 2_000L,
            languagePair = koJaPair,
        )
        assertNotNull(natural)
        assertEquals("それでは日本語という言い方です", natural.text)
        assertEquals(LanguageTag("ja"), natural.language)

        // カタカナの音写しか得られない場合は、韓国語の言語判定のみを返す
        jaResult = SpeechRecognitionAttemptResult(text = "マンナソパンガプスミダ", confidence = 0.8f)
        val transliterated = transcriber.transcribeChunk(
            audioFile = tempFolder.newFile("ambiguous_katakana_ja.wav").apply { writeBytes(byteArrayOf(7, 8)) },
            startMs = 0L,
            endMs = 2_000L,
            languagePair = koJaPair,
        )
        assertNotNull(transliterated)
        assertTrue(transliterated.isLanguageOnly)
        assertEquals(LanguageTag("ko"), transliterated.language)
    }

    @Test
    fun `refreshRecognitionModelIssues_OSの対応状況でインストール済みになった言語を問題の一覧から外す`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val gateway = FakeRecognitionModelGateway()
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 1),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, _, _ ->
                SpeechRecognitionAttemptResult(
                    text = "",
                    errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                )
            },
            modelGateway = gateway,
        )
        val result = transcriber.transcribeChunk(
            audioFile = tempFolder.newFile("single_ko_missing.wav").apply { writeBytes(byteArrayOf(9, 10)) },
            startMs = 0L,
            endMs = 1_000L,
            languagePair = LanguagePair(target = LanguageTag("ko"), base = null),
        )
        // 単一言語の場合は言語が既知のため、本文がなければ null を返す（状態だけ記録する）
        assertNull(result)
        assertEquals(
            mapOf(LanguageTag("ko") to OnDeviceRecognitionModelState.DOWNLOADING),
            transcriber.recognitionModelIssues.value,
        )

        // 予約（Wi-Fi 待ちなど）のあと、OS の対応状況でインストール済みになったら一覧から外す
        gateway.emit(RecognitionModelDownloadEvent.SCHEDULED)
        assertEquals(
            mapOf(LanguageTag("ko") to OnDeviceRecognitionModelState.DOWNLOADING),
            transcriber.recognitionModelIssues.value,
        )
        gateway.supportResult = mapOf("ko-KR" to RecognitionModelSupport.INSTALLED)
        transcriber.refreshRecognitionModelIssues()
        assertTrue(transcriber.recognitionModelIssues.value.isEmpty())
    }

    @Test
    fun `requestRecognitionModelDownload_ダウンロードに失敗するとDOWNLOAD_FAILEDになる`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val gateway = FakeRecognitionModelGateway()
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 1),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, _, _ -> null },
            modelGateway = gateway,
        )

        assertTrue(transcriber.requestRecognitionModelDownload(LanguageTag("ko")))
        assertEquals(listOf("ko-KR"), gateway.downloadRequests)
        assertEquals(
            mapOf(LanguageTag("ko") to OnDeviceRecognitionModelState.DOWNLOADING),
            transcriber.recognitionModelIssues.value,
        )

        gateway.emit(RecognitionModelDownloadEvent.FAILED)
        assertEquals(
            mapOf(LanguageTag("ko") to OnDeviceRecognitionModelState.DOWNLOAD_FAILED),
            transcriber.recognitionModelIssues.value,
        )
    }

    @Test
    fun `toBcp47CandidateLocaleTags_簡体繁体広東語の同義BCP47タグ候補を優先順で返す`() {
        assertEquals(
            listOf("zh-CN", "cmn-Hans-CN", "zh-Hans-CN", "cmn-CN", "zh"),
            toBcp47CandidateLocaleTags("zh"),
        )
        assertEquals(
            listOf("zh-CN", "cmn-Hans-CN", "zh-Hans-CN", "cmn-CN", "zh"),
            toBcp47CandidateLocaleTags("zh-cn"),
        )
        assertEquals(
            listOf("zh-TW", "cmn-Hant-TW", "zh-Hant-TW", "cmn-TW"),
            toBcp47CandidateLocaleTags("zh-tw"),
        )
        assertEquals(
            listOf("yue-HK", "yue-Hant-HK", "zh-HK", "zh-Hant-HK", "cmn-Hant-HK", "yue"),
            toBcp47CandidateLocaleTags("yue"),
        )
    }

    @Test
    fun `transcribeChunk_zh_CNがERROR_LANGUAGE_NOT_SUPPORTEDでもcmn_Hans_CNへフォールバックして認識し次回以降はcmn_Hans_CNを直接使う`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val gateway = FakeRecognitionModelGateway()
        val runnerCalls = mutableListOf<String>()
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 2),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47Tag, _ ->
                runnerCalls += bcp47Tag
                when (bcp47Tag) {
                    "zh-CN" -> SpeechRecognitionAttemptResult(
                        text = "",
                        errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    )

                    "cmn-Hans-CN" -> SpeechRecognitionAttemptResult(
                        text = "你好世界",
                        confidence = 0.94f,
                    )

                    else -> null
                }
            },
            languageDetector = { _, _, _ ->
                LanguagePairPrediction(
                    selectedTag = "zh",
                    fallbackTag = "ja",
                    selectedProbability = 0.98f,
                    targetProbability = 0.98f,
                    baseProbability = 0.02f,
                    inferenceTimeMs = 8L,
                )
            },
            modelGateway = gateway,
        )
        val zhJaPair = LanguagePair(target = LanguageTag("zh"), base = LanguageTag("ja"))

        val first = transcriber.transcribeChunk(
            audioFile = tempFolder.newFile("zh_fallback_1.wav").apply { writeBytes(byteArrayOf(1, 2)) },
            startMs = 0L,
            endMs = 2_000L,
            languagePair = zhJaPair,
        )
        assertNotNull(first)
        assertEquals("你好世界", first.text)
        assertEquals(LanguageTag("zh"), first.language)
        assertEquals(listOf("zh-CN", "cmn-Hans-CN"), runnerCalls)
        assertTrue(transcriber.recognitionModelIssues.value.isEmpty())

        // 2チャンク目では解決済みの cmn-Hans-CN を最初から使う
        val second = transcriber.transcribeChunk(
            audioFile = tempFolder.newFile("zh_fallback_2.wav").apply { writeBytes(byteArrayOf(3, 4)) },
            startMs = 0L,
            endMs = 2_000L,
            languagePair = zhJaPair,
        )
        assertNotNull(second)
        assertEquals("你好世界", second.text)
        assertEquals(listOf("zh-CN", "cmn-Hans-CN", "cmn-Hans-CN"), runnerCalls)
    }

    @Test
    fun `transcribeChunk_候補タグが全てERROR_LANGUAGE_NOT_SUPPORTEDでもcheckSupportでcmn_Hans_CNがSUPPORTED_NOT_INSTALLEDならダウンロードを開始する`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val gateway = FakeRecognitionModelGateway().apply {
            supportResult = mapOf("cmn-Hans-CN" to RecognitionModelSupport.SUPPORTED_NOT_INSTALLED)
        }
        val runnerCalls = mutableListOf<String>()
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 2),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47Tag, _ ->
                runnerCalls += bcp47Tag
                SpeechRecognitionAttemptResult(
                    text = "",
                    errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                )
            },
            languageDetector = { _, _, _ ->
                LanguagePairPrediction(
                    selectedTag = "zh",
                    fallbackTag = "ja",
                    selectedProbability = 0.96f,
                    targetProbability = 0.96f,
                    baseProbability = 0.04f,
                    inferenceTimeMs = 8L,
                )
            },
            modelGateway = gateway,
        )
        val zhJaPair = LanguagePair(target = LanguageTag("zh"), base = LanguageTag("ja"))

        val result = transcriber.transcribeChunk(
            audioFile = tempFolder.newFile("zh_download_fallback.wav").apply { writeBytes(byteArrayOf(5, 6)) },
            startMs = 0L,
            endMs = 2_000L,
            languagePair = zhJaPair,
        )
        assertNotNull(result)
        assertTrue(result.isLanguageOnly)
        assertEquals(LanguageTag("zh"), result.language)
        assertEquals(
            listOf("zh-CN", "cmn-Hans-CN", "zh-Hans-CN", "cmn-CN", "zh"),
            runnerCalls,
        )
        // UNSUPPORTED ではなく、対応タグ cmn-Hans-CN でダウンロードが自動要求されること
        assertEquals(listOf("cmn-Hans-CN"), gateway.downloadRequests)
        assertEquals(
            mapOf(LanguageTag("zh") to OnDeviceRecognitionModelState.DOWNLOADING),
            transcriber.recognitionModelIssues.value,
        )
    }

    @Test
    fun `SherpaOnnxModelManager_言語ごとのチェックアウトと共有ファイル検証および削除が機能する`() = runTest {
        val encPayload = "dummy-encoder-bytes".toByteArray()
        val decPayload = "dummy-decoder-bytes".toByteArray()
        val tokPayload = "dummy-tokens-bytes".toByteArray()
        val testArtifacts = listOf(
            SherpaOnnxModelManager.ArtifactEntry(
                fileName = SherpaOnnxModelManager.ENCODER_FILE_NAME,
                url = "https://huggingface.co/test/encoder.onnx",
                expectedSizeBytes = encPayload.size.toLong(),
                expectedSha256Hex = null,
            ),
            SherpaOnnxModelManager.ArtifactEntry(
                fileName = SherpaOnnxModelManager.DECODER_FILE_NAME,
                url = "https://huggingface.co/test/decoder.onnx",
                expectedSizeBytes = decPayload.size.toLong(),
                expectedSha256Hex = null,
            ),
            SherpaOnnxModelManager.ArtifactEntry(
                fileName = SherpaOnnxModelManager.TOKENS_FILE_NAME,
                url = "https://huggingface.co/test/tokens.txt",
                expectedSizeBytes = tokPayload.size.toLong(),
                expectedSha256Hex = null,
            ),
        )
        val filesByUrl = mapOf(
            "https://huggingface.co/test/encoder.onnx" to encPayload,
            "https://huggingface.co/test/decoder.onnx" to decPayload,
            "https://huggingface.co/test/tokens.txt" to tokPayload,
        )
        var downloadCallCount = 0
        val fakeDownloader = object : com.miuraken.chunkflow.core.drive.ModelArtifactDownloader {
            override suspend fun downloadVerifiedFile(
                url: String,
                destFile: File,
                expectedSizeBytes: Long?,
                expectedSha256Hex: String?,
                maxSizeBytes: Long,
            ): com.miuraken.chunkflow.core.drive.ModelArtifactDownloadResult {
                downloadCallCount += 1
                val payload = filesByUrl[url]
                    ?: return com.miuraken.chunkflow.core.drive.ModelArtifactDownloadResult.Failure(
                        com.miuraken.chunkflow.core.drive.ModelArtifactDownloadResult.Failure.Reason.HTTP_ERROR,
                    )
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(payload)
                return com.miuraken.chunkflow.core.drive.ModelArtifactDownloadResult.Success(
                    bytesWritten = payload.size.toLong(),
                    sha256Hex = "0".repeat(64),
                )
            }
        }
        val manager = SherpaOnnxModelManager(
            modelsRootDir = tempFolder.newFolder("sherpa_models"),
            downloader = fakeDownloader,
            artifacts = testArtifacts,
        )

        val zh = LanguageTag("zh")
        val ko = LanguageTag("ko")

        // 初期状態は未インストール
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
            manager.getLanguageModelStatus(zh),
        )
        assertFalse(manager.isInstalledForLanguage(zh))

        // 1言語目 (zh) をダウンロード -> 3ファイルがダウンロードされ zh が INSTALLED になる
        assertTrue(manager.downloadModelForLanguage(zh))
        assertEquals(3, downloadCallCount)
        assertTrue(manager.isInstalledForLanguage(zh))
        assertFalse(manager.isInstalledForLanguage(ko))
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
            manager.getLanguageModelStatus(zh),
        )

        // 2言語目 (ko) をチェックアウト -> 共有ファイルが揃っているため再ダウンロードせず即座に INSTALLED になる
        assertTrue(manager.downloadModelForLanguage(ko))
        assertEquals(3, downloadCallCount)
        assertTrue(manager.isInstalledForLanguage(ko))

        // zh だけ削除 -> ko が残っているため共有ファイルは保持され ko は引き続き INSTALLED
        assertTrue(manager.deleteModelForLanguage(zh))
        assertFalse(manager.isInstalledForLanguage(zh))
        assertTrue(manager.isInstalledForLanguage(ko))
        assertTrue(manager.areCoreModelFilesPresent())

        // 最後の言語 ko を削除 -> 共有モデルファイルも削除される
        assertTrue(manager.deleteModelForLanguage(ko))
        assertFalse(manager.isInstalledForLanguage(ko))
        assertFalse(manager.areCoreModelFilesPresent())
    }

    @Test
    fun `SherpaOnnxWhisperTranscriber_base64トークン表からUTF8文字列と言語トークンIDを正しく復元する`() {
        val tokensFile = tempFolder.newFile("tiny-tokens.txt")
        val helloBase64 = java.util.Base64.getEncoder().encodeToString("こんにちは".toByteArray(Charsets.UTF_8))
        val worldBase64 = java.util.Base64.getEncoder().encodeToString("世界".toByteArray(Charsets.UTF_8))
        val sotBase64 = java.util.Base64.getEncoder().encodeToString("<|startoftranscript|>".toByteArray(Charsets.UTF_8))
        val eotBase64 = java.util.Base64.getEncoder().encodeToString("<|endoftext|>".toByteArray(Charsets.UTF_8))

        tokensFile.writeText(
            """
            $helloBase64 100
            $worldBase64 101
            $sotBase64 50258
            $eotBase64 50257
            """.trimIndent(),
        )

        val tokenMap = SherpaOnnxWhisperTranscriber.parseTokensFile(tokensFile)
        assertTrue(tokenMap.isNotEmpty())

        val config = SherpaOnnxWhisperTranscriber.WhisperModelConfig()
        assertEquals(50266L, config.resolveLanguageToken(LanguageTag("ja")))
        assertEquals(50266L, config.resolveLanguageToken(LanguageTag("ja-JP")))
        assertEquals(50260L, config.resolveLanguageToken(LanguageTag("zh")))
        assertEquals(50260L, config.resolveLanguageToken(LanguageTag("yue")))

        val decoded = SherpaOnnxWhisperTranscriber.decodeTokensToString(
            tokenIds = intArrayOf(100, 101, 50258, 50257),
            tokenBytesById = tokenMap,
        )
        assertEquals("こんにちは世界", decoded)
    }

    @Test
    fun `computeMelSpectrogram80x3000WithFilters_30秒3000フレームの正規化特徴量を生成する`() {
        val dummyFilters = FloatArray(WhisperTinyLanguageDetector.N_MELS * WhisperTinyLanguageDetector.N_FREQ_BINS) { 0.01f }
        val pcm = ShortArray(WhisperTinyLanguageDetector.SAMPLE_RATE_HZ * 2) { 1_000 }
        val melPair = WhisperTinyLanguageDetector.computeMelSpectrogram80x3000WithFilters(pcm, dummyFilters)
        assertNotNull(melPair)
        assertEquals(
            WhisperTinyLanguageDetector.N_MELS * WhisperTinyLanguageDetector.MEL_FRAMES_30S,
            melPair.first.size,
        )
        assertEquals(200, melPair.second)
    }

    @Test
    fun `transcribeChunk_OSのSpeechRecognizerがUNSUPPORTEDのときsherpa_onnxが導入済みなら自動フォールバックで文字起こしする`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val gateway = FakeRecognitionModelGateway().apply {
            supportResult = emptyMap() // すべて UNSUPPORTED
        }
        val testArtifacts = listOf(
            SherpaOnnxModelManager.ArtifactEntry(
                fileName = SherpaOnnxModelManager.ENCODER_FILE_NAME,
                url = "https://huggingface.co/test/encoder.onnx",
                expectedSizeBytes = 3L,
                expectedSha256Hex = null,
            ),
            SherpaOnnxModelManager.ArtifactEntry(
                fileName = SherpaOnnxModelManager.DECODER_FILE_NAME,
                url = "https://huggingface.co/test/decoder.onnx",
                expectedSizeBytes = 3L,
                expectedSha256Hex = null,
            ),
            SherpaOnnxModelManager.ArtifactEntry(
                fileName = SherpaOnnxModelManager.TOKENS_FILE_NAME,
                url = "https://huggingface.co/test/tokens.txt",
                expectedSizeBytes = 3L,
                expectedSha256Hex = null,
            ),
        )
        val fakeDownloader = object : com.miuraken.chunkflow.core.drive.ModelArtifactDownloader {
            override suspend fun downloadVerifiedFile(
                url: String,
                destFile: File,
                expectedSizeBytes: Long?,
                expectedSha256Hex: String?,
                maxSizeBytes: Long,
            ): com.miuraken.chunkflow.core.drive.ModelArtifactDownloadResult {
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(byteArrayOf(1, 2, 3))
                return com.miuraken.chunkflow.core.drive.ModelArtifactDownloadResult.Success(3L, "0".repeat(64))
            }
        }
        val sherpaManager = SherpaOnnxModelManager(
            modelsRootDir = tempFolder.newFolder("sherpa_fallback_models"),
            downloader = fakeDownloader,
            artifacts = testArtifacts,
        )
        assertTrue(sherpaManager.downloadModelForLanguage(LanguageTag("zh")))

        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 2),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, _, _ ->
                SpeechRecognitionAttemptResult(
                    text = "",
                    errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                )
            },
            languageDetector = { _, _, _ ->
                LanguagePairPrediction(
                    selectedTag = "zh",
                    fallbackTag = "ja",
                    selectedProbability = 0.98f,
                    targetProbability = 0.98f,
                    baseProbability = 0.02f,
                    inferenceTimeMs = 8L,
                )
            },
            modelGateway = gateway,
            sherpaModelManager = sherpaManager,
            sherpaTranscriber = { _, _, lang ->
                OnDeviceChunkTranscriptionResult(
                    text = "你好，这是本地模型识别结果",
                    language = lang,
                    confidence = 0.85f,
                    engineId = SherpaOnnxWhisperTranscriber.SHERPA_ONNX_ENGINE_ID,
                )
            },
        )

        val result = transcriber.transcribeChunk(
            audioFile = tempFolder.newFile("zh_sherpa_fallback.wav").apply { writeBytes(byteArrayOf(1, 2)) },
            startMs = 0L,
            endMs = 2_000L,
            languagePair = LanguagePair(target = LanguageTag("zh"), base = LanguageTag("ja")),
        )
        assertNotNull(result)
        assertFalse(result.isLanguageOnly)
        assertEquals("你好，这是本地模型识别结果", result.text)
        assertEquals(LanguageTag("zh"), result.language)
        assertEquals(SherpaOnnxWhisperTranscriber.SHERPA_ONNX_ENGINE_ID, result.engineId)
        assertTrue(transcriber.recognitionModelIssues.value.isEmpty())
    }

    @Test
    fun `transcribeChunk_SHERPA_ONNXエンジン優先設定時はOSのSpeechRecognizerを呼ばずにsherpa_onnxで認識する`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val testArtifacts = listOf(
            SherpaOnnxModelManager.ArtifactEntry(
                fileName = SherpaOnnxModelManager.ENCODER_FILE_NAME,
                url = "https://huggingface.co/test/encoder.onnx",
                expectedSizeBytes = 3L,
                expectedSha256Hex = null,
            ),
            SherpaOnnxModelManager.ArtifactEntry(
                fileName = SherpaOnnxModelManager.DECODER_FILE_NAME,
                url = "https://huggingface.co/test/decoder.onnx",
                expectedSizeBytes = 3L,
                expectedSha256Hex = null,
            ),
            SherpaOnnxModelManager.ArtifactEntry(
                fileName = SherpaOnnxModelManager.TOKENS_FILE_NAME,
                url = "https://huggingface.co/test/tokens.txt",
                expectedSizeBytes = 3L,
                expectedSha256Hex = null,
            ),
        )
        val fakeDownloader = object : com.miuraken.chunkflow.core.drive.ModelArtifactDownloader {
            override suspend fun downloadVerifiedFile(
                url: String,
                destFile: File,
                expectedSizeBytes: Long?,
                expectedSha256Hex: String?,
                maxSizeBytes: Long,
            ): com.miuraken.chunkflow.core.drive.ModelArtifactDownloadResult {
                destFile.parentFile?.mkdirs()
                destFile.writeBytes(byteArrayOf(1, 2, 3))
                return com.miuraken.chunkflow.core.drive.ModelArtifactDownloadResult.Success(3L, "0".repeat(64))
            }
        }
        val sherpaManager = SherpaOnnxModelManager(
            modelsRootDir = tempFolder.newFolder("sherpa_preferred_models"),
            downloader = fakeDownloader,
            artifacts = testArtifacts,
        )
        assertTrue(sherpaManager.downloadModelForLanguage(LanguageTag("ko")))

        var osAttemptCount = 0
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 2),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, _, _ ->
                osAttemptCount += 1
                SpeechRecognitionAttemptResult(text = "OS結果", confidence = 0.9f)
            },
            sherpaModelManager = sherpaManager,
            sherpaTranscriber = { _, _, lang ->
                OnDeviceChunkTranscriptionResult(
                    text = "안녕하세요 로컬 모델입니다",
                    language = lang,
                    confidence = 0.88f,
                    engineId = SherpaOnnxWhisperTranscriber.SHERPA_ONNX_ENGINE_ID,
                )
            },
            engineProvider = { com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.SHERPA_ONNX },
        )

        val result = transcriber.transcribeChunk(
            audioFile = tempFolder.newFile("ko_sherpa_preferred.wav").apply { writeBytes(byteArrayOf(7, 8)) },
            startMs = 0L,
            endMs = 2_000L,
            languagePair = LanguagePair(target = LanguageTag("ko"), base = null),
        )
        assertNotNull(result)
        assertEquals("안녕하세요 로컬 모델입니다", result.text)
        assertEquals(LanguageTag("ko"), result.language)
        assertEquals(SherpaOnnxWhisperTranscriber.SHERPA_ONNX_ENGINE_ID, result.engineId)
        assertEquals(0, osAttemptCount)
    }

    @Test
    fun `transcribeChunk_ML_KIT選択時かつ端末対応時はML_Kitが優先使用され非対応言語ではOSへフォールバックする`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val mlKitCalls = mutableListOf<String>()
        var osAttemptCount = 0
        val fakeMlKit = object : MlKitSpeechRecognitionGateway {
            override fun isDevicePotentiallySupported(): Boolean = true

            override suspend fun checkMlKitSupport(
                bcp47LocaleTags: List<String>,
            ): Map<String, Pair<RecognitionModelSupport, MlKitRecognitionMode?>> = bcp47LocaleTags.associateWith { tag ->
                if (tag.startsWith("ja")) {
                    RecognitionModelSupport.INSTALLED to null
                } else {
                    RecognitionModelSupport.UNSUPPORTED to null
                }
            }

            override suspend fun downloadModel(
                bcp47LocaleTag: String,
                onEvent: (RecognitionModelDownloadEvent) -> Unit,
            ): Boolean = true

            override suspend fun recognizePcm16Le(
                pcm16LeBytes: ByteArray,
                sampleRateHz: Int,
                bcp47LocaleTag: String,
                isProbe: Boolean,
            ): SpeechRecognitionAttemptResult {
                mlKitCalls += bcp47LocaleTag
                return if (bcp47LocaleTag.startsWith("ja")) {
                    SpeechRecognitionAttemptResult(
                        text = "ML Kitでの認識結果です",
                        confidence = 0.96f,
                        engineId = MlKitGenAiSpeechTranscriber.ML_KIT_GENAI_ENGINE_ID,
                    )
                } else {
                    SpeechRecognitionAttemptResult(
                        text = "",
                        confidence = null,
                        errorCode = android.speech.SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                        engineId = MlKitGenAiSpeechTranscriber.ML_KIT_GENAI_ENGINE_ID,
                    )
                }
            }
        }

        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 2),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47Tag, _ ->
                osAttemptCount += 1
                SpeechRecognitionAttemptResult(text = "OSフォールバック($bcp47Tag)", confidence = 0.91f)
            },
            mlKitGateway = fakeMlKit,
            engineProvider = { com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ML_KIT },
        )

        // 1. ML Kit が対応している ja では ML Kit が使われ、OS SpeechRecognizer は呼ばれない
        val jaResult = transcriber.transcribeChunk(
            audioFile = tempFolder.newFile("ja_mlkit.wav").apply { writeBytes(byteArrayOf(1, 2)) },
            startMs = 0L,
            endMs = 2_000L,
            languagePair = LanguagePair(target = LanguageTag("ja"), base = null),
        )
        assertNotNull(jaResult)
        assertEquals("ML Kitでの認識結果です", jaResult.text)
        assertEquals(LanguageTag("ja"), jaResult.language)
        assertEquals(MlKitGenAiSpeechTranscriber.ML_KIT_GENAI_ENGINE_ID, jaResult.engineId)
        assertEquals(1, mlKitCalls.size)
        assertEquals(0, osAttemptCount)

        // 2. ML Kit が非対応の el では OS SpeechRecognizer へ自動フォールバックする
        val elResult = transcriber.transcribeChunk(
            audioFile = tempFolder.newFile("el_mlkit_fallback.wav").apply { writeBytes(byteArrayOf(3, 4)) },
            startMs = 0L,
            endMs = 2_000L,
            languagePair = LanguagePair(target = LanguageTag("el"), base = null),
        )
        assertNotNull(elResult)
        assertEquals("OSフォールバック(el-GR)", elResult.text)
        assertEquals(LanguageTag("el"), elResult.language)
        assertTrue(osAttemptCount >= 1)
    }

    @Test
    fun `transcribeChunkTrial_エンジンオーバーライドと速度倍率1_5xでWSOLA時間伸縮したPCMを認識器に渡す`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val capturedPcmLengths = mutableListOf<Int>()
        val fakeMlKit = object : MlKitSpeechRecognitionGateway {
            override fun isDevicePotentiallySupported(): Boolean = true

            override suspend fun checkMlKitSupport(
                bcp47LocaleTags: List<String>,
            ): Map<String, Pair<RecognitionModelSupport, MlKitRecognitionMode?>> = bcp47LocaleTags.associateWith {
                RecognitionModelSupport.INSTALLED to null
            }

            override suspend fun downloadModel(
                bcp47LocaleTag: String,
                onEvent: (RecognitionModelDownloadEvent) -> Unit,
            ): Boolean = true

            override suspend fun recognizePcm16Le(
                pcm16LeBytes: ByteArray,
                sampleRateHz: Int,
                bcp47LocaleTag: String,
                isProbe: Boolean,
            ): SpeechRecognitionAttemptResult {
                capturedPcmLengths += pcm16LeBytes.size
                return SpeechRecognitionAttemptResult(
                    text = "1.5倍速MLKit認識",
                    confidence = 0.92f,
                    engineId = MlKitGenAiSpeechTranscriber.ML_KIT_GENAI_ENGINE_ID,
                )
            }
        }

        // 既定設定は ANDROID_SPEECH_RECOGNIZER だが、transcribeChunkTrial で ML_KIT と 1.5x を指定
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 3),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            mlKitGateway = fakeMlKit,
            engineProvider = { com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER },
        )

        val audioFile = tempFolder.newFile("trial_speed.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val result1x = transcriber.transcribeChunkTrial(
            audioFile = audioFile,
            startMs = 0L,
            endMs = 3_000L,
            languagePair = LanguagePair(target = LanguageTag("ja"), base = null),
            engineOverride = com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ML_KIT,
            audioSpeed = 1.0f,
        )
        val result15x = transcriber.transcribeChunkTrial(
            audioFile = audioFile,
            startMs = 0L,
            endMs = 3_000L,
            languagePair = LanguagePair(target = LanguageTag("ja"), base = null),
            engineOverride = com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ML_KIT,
            audioSpeed = 1.5f,
        )

        assertNotNull(result1x)
        assertNotNull(result15x)
        assertEquals("1.5倍速MLKit認識", result15x.text)
        assertEquals(MlKitGenAiSpeechTranscriber.ML_KIT_GENAI_ENGINE_ID, result15x.engineId)
        assertEquals(2, capturedPcmLengths.size)
        // 1.5倍速時は 1.0倍速時よりも PCM バイト長が短くなっていることを確認
        assertTrue(capturedPcmLengths[1] < capturedPcmLengths[0])
    }

    @Test
    fun `transcribeChunk_itemSttOverrideとengineByLanguageが内部のエンジン選択に反映される`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        var mlKitCallCount = 0
        var sherpaCallCount = 0
        var osCallCount = 0

        val fakeMlKit = object : MlKitSpeechRecognitionGateway {
            override fun isDevicePotentiallySupported(): Boolean = true

            override suspend fun checkMlKitSupport(
                bcp47LocaleTags: List<String>,
            ): Map<String, Pair<RecognitionModelSupport, MlKitRecognitionMode?>> = bcp47LocaleTags.associateWith {
                RecognitionModelSupport.INSTALLED to null
            }

            override suspend fun downloadModel(
                bcp47LocaleTag: String,
                onEvent: (RecognitionModelDownloadEvent) -> Unit,
            ): Boolean = true

            override suspend fun recognizePcm16Le(
                pcm16LeBytes: ByteArray,
                sampleRateHz: Int,
                bcp47LocaleTag: String,
                isProbe: Boolean,
            ): SpeechRecognitionAttemptResult {
                mlKitCallCount += 1
                return SpeechRecognitionAttemptResult(
                    text = "MLKit認識($bcp47LocaleTag)",
                    confidence = 0.90f,
                    engineId = MlKitGenAiSpeechTranscriber.ML_KIT_GENAI_ENGINE_ID,
                )
            }
        }

        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 2),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47Tag, _ ->
                osCallCount += 1
                SpeechRecognitionAttemptResult(text = "OS認識($bcp47Tag)", confidence = 0.85f)
            },
            mlKitGateway = fakeMlKit,
            sherpaTranscriber = { _, _, lang ->
                sherpaCallCount += 1
                OnDeviceChunkTranscriptionResult(
                    text = "Sherpa認識(${lang.value})",
                    language = lang,
                    confidence = 0.95f,
                    engineId = SherpaOnnxWhisperTranscriber.SHERPA_ONNX_ENGINE_ID,
                )
            },
            engineProvider = { com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.AUTO },
        )

        val audioFile1 = tempFolder.newFile("override_engine_by_lang.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        // 1. engineByLanguage で en -> SHERPA_ONNX を指定した場合、既定の ML_KIT ではなく Sherpa が選ばれる
        val byLangResult = transcriber.transcribeChunk(
            audioFile = audioFile1,
            startMs = 0L,
            endMs = 2_000L,
            languagePair = LanguagePair(target = LanguageTag("en"), base = null),
            forceRefresh = true,
            highPriority = true,
            itemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
            engineByLanguage = mapOf(LanguageTag("en") to com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX),
        )
        assertNotNull(byLangResult)
        assertEquals("Sherpa認識(en)", byLangResult.text)
        assertEquals(1, sherpaCallCount)
        assertEquals(0, mlKitCallCount)
        assertEquals(0, osCallCount)

        val audioFile2 = tempFolder.newFile("override_item_stt.wav").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        // 2. itemSttOverride で targetEngine = SPEECH_RECOGNIZER を指定した場合、engineByLanguage より優先して OS 認識器が選ばれる
        val byItemOverrideResult = transcriber.transcribeChunk(
            audioFile = audioFile2,
            startMs = 0L,
            endMs = 2_000L,
            languagePair = LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja")),
            forceRefresh = true,
            highPriority = true,
            itemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride(
                targetEngine = com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER,
                baseEngine = com.miuraken.chunkflow.core.model.SttEngine.ML_KIT,
            ),
            engineByLanguage = mapOf(LanguageTag("en") to com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX),
        )
        assertNotNull(byItemOverrideResult)
        assertTrue(osCallCount >= 1)
    }

    @Test
    fun `transcribeChunk_precomputedLanguageが指定されている場合はWhisperLIDを再実行せず指定言語から認識を開始する`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        var lidCallCount = 0
        val recognizedLocales = mutableListOf<String>()

        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 2),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47Tag, _ ->
                recognizedLocales += bcp47Tag
                SpeechRecognitionAttemptResult(text = "こんにちはテストです", confidence = 0.92f)
            },
            languageDetector = { _, _, _ ->
                lidCallCount += 1
                LanguagePairPrediction(
                    selectedTag = "en",
                    fallbackTag = "ja",
                    selectedProbability = 0.95f,
                    targetProbability = 0.95f,
                    baseProbability = 0.05f,
                    inferenceTimeMs = 50L,
                )
            },
        )

        val audioFile = tempFolder.newFile("precomputed_lid.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val result = transcriber.transcribeChunk(
            audioFile = audioFile,
            startMs = 0L,
            endMs = 2_000L,
            languagePair = LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja")),
            forceRefresh = true,
            highPriority = false,
            itemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
            engineByLanguage = emptyMap(),
            precomputedLanguage = LanguageTag("ja"),
            precomputedLanguageConfidence = 0.91f,
        )

        assertNotNull(result)
        assertEquals("こんにちはテストです", result.text)
        assertEquals(LanguageTag("ja"), result.language)
        assertEquals(0, lidCallCount, "事前判定済みの言語がある場合は Whisper LID を再実行しない")
        assertEquals(listOf("ja-JP"), recognizedLocales)
    }

    @Test
    fun `detectChunkLanguage_ファイル内で学習した声の特徴でペア外に確率が流れた曖昧なWhisper判定を補正する`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        // 偶数秒: 低い声の話者（ja）、奇数秒: 高い声の話者（en）
        val twoSpeakerDecoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                val totalSec = 10
                val samples = FloatArray(outputSampleRate * totalSec) { idx ->
                    val timeSec = idx.toDouble() / outputSampleRate.toDouble()
                    val isSecondSpeaker = (idx / outputSampleRate) % 2 == 1
                    val f0 = if (isSecondSpeaker) 280.0 else 110.0
                    val f1 = if (isSecondSpeaker) 560.0 else 220.0
                    val f2 = if (isSecondSpeaker) 2_800.0 else 770.0
                    (
                        0.32 * kotlin.math.sin(2.0 * Math.PI * f0 * timeSec) +
                            0.10 * kotlin.math.sin(2.0 * Math.PI * f1 * timeSec) +
                            0.14 * kotlin.math.sin(2.0 * Math.PI * f2 * timeSec)
                        ).toFloat()
                }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(
                    sampleRate = outputSampleRate,
                    channels = 1,
                    decodedFrames = samples.size.toLong(),
                    resampledSamples = samples.size.toLong(),
                    durationMs = totalSec * 1_000L,
                    encoderDelay = 0,
                    encoderPadding = 0,
                    mimeType = "audio/wav",
                )
            }
        }

        var analyzerCallCount = 0
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = twoSpeakerDecoder,
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            languageAnalyzer = { shorts, targetTag, baseTag ->
                analyzerCallCount += 1
                // ZCR（零交差率）で高低どちらの話者かを判別し、話者ごとの特徴ベクトルを返す
                var zeroCrossings = 0
                for (i in 1 until shorts.size) {
                    if ((shorts[i] >= 0) != (shorts[i - 1] >= 0)) zeroCrossings += 1
                }
                val isHighPitch = zeroCrossings > 800
                val embedding = FloatArray(16) { d ->
                    val speakerComponent = if ((d % 2 == 0) == isHighPitch) 1.0f else -1.0f
                    3.0f + speakerComponent + 0.05f * ((analyzerCallCount * 7 + d) % 5)
                }
                val warmUp = analyzerCallCount <= 6
                // 最初の 6 チャンクは Whisper が高確信、以降は確率の大半がペア外に流れ、しかも逆の言語に傾いた判定
                val targetProbability = when {
                    warmUp && isHighPitch -> 0.97f
                    warmUp -> 0.03f
                    isHighPitch -> 0.15f
                    else -> 0.80f
                }
                LanguagePairAnalysis(
                    prediction = LanguagePairPrediction(
                        selectedTag = if (targetProbability >= 0.5f) targetTag else baseTag,
                        fallbackTag = if (targetProbability >= 0.5f) baseTag else targetTag,
                        selectedProbability = maxOf(targetProbability, 1f - targetProbability),
                        targetProbability = targetProbability,
                        baseProbability = 1f - targetProbability,
                        inferenceTimeMs = 20L,
                        pairMass = if (warmUp) 0.9f else 0.1f,
                    ),
                    encoderEmbedding = embedding,
                )
            },
        )

        val audioFile = tempFolder.newFile("bilingual_dialogue.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val pair = LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja"))

        // 最初の 6 チャンク（ja, en, ja, en, ja, en）は Whisper 単体の高確信な判定がそのまま使われ、アンカーになる
        for (second in 0 until 6) {
            val expected = if (second % 2 == 1) LanguageTag("en") else LanguageTag("ja")
            val startMs = second * 1_000L
            assertEquals(expected, transcriber.detectChunkLanguage(audioFile, startMs, startMs + 1_000L, pair)?.language)
        }

        // 7 チャンク目（ja の話者）と 8 チャンク目（en の話者）は、Whisper が逆の言語に傾いていても声の特徴で正しく判定する
        val seventh = transcriber.detectChunkLanguage(audioFile, 6_000L, 7_000L, pair)
        val eighth = transcriber.detectChunkLanguage(audioFile, 7_000L, 8_000L, pair)
        assertNotNull(seventh)
        assertNotNull(eighth)
        assertEquals(LanguageTag("ja"), seventh.language)
        assertEquals(LanguageTag("en"), eighth.language)
        // Whisper は毎回実行する（話者の一致だけで判定を省略しない）
        assertEquals(8, analyzerCallCount)
    }

    @Test
    fun `isUnnaturalOrTransliterationSuspected_韓国語やラテン文字言語での他言語音写を検知し自然な発話は除外する`() {
        // 日本語: カタカナ比率が高い音写は疑わしい
        assertTrue(isUnnaturalOrTransliterationSuspected("アンニョンハセヨ", "ja"))
        assertTrue(!isUnnaturalOrTransliterationSuspected("おはようございます。今日もよろしくお願いします。", "ja"))

        // 韓国語: 機能語・助詞を含まない1文字ハングル断片の羅列は音写の疑いあり
        assertTrue(isUnnaturalOrTransliterationSuspected("오 하 요 고 자 이 마 스", "ko"))
        assertTrue(!isUnnaturalOrTransliterationSuspected("안녕하세요 오늘 날씨가 정말 좋네요", "ko"))

        // 英語・スペイン語: 対象言語の機能語が皆無で他言語の機能語や短音節断片が並ぶ場合は不自然
        assertTrue(isUnnaturalOrTransliterationSuspected("el libro está en la mesa", "en"))
        assertTrue(!isUnnaturalOrTransliterationSuspected("the book is on the table", "en"))
        assertTrue(!isUnnaturalOrTransliterationSuspected("el libro está en la mesa", "es"))
    }

    @Test
    fun `scoreProbeCandidateForLanguage_confidenceがnullのMLKit結果でも機能語と発音区別符号で正しいラテン文字言語を高く評価する`() {
        val spanishSentence = SpeechRecognitionAttemptResult(
            text = "buenos días dónde está la estación por favor",
            confidence = null,
        )
        val englishSentence = SpeechRecognitionAttemptResult(
            text = "good morning where is the station please",
            confidence = null,
        )

        val esScoreForSpanish = scoreProbeCandidateForLanguage(spanishSentence, "es")
        val enScoreForSpanish = scoreProbeCandidateForLanguage(spanishSentence, "en")
        assertTrue(
            esScoreForSpanish > enScoreForSpanish,
            "スペイン語文は confidence == null でも es スコア ($esScoreForSpanish) > en スコア ($enScoreForSpanish) になる",
        )

        val enScoreForEnglish = scoreProbeCandidateForLanguage(englishSentence, "en")
        val esScoreForEnglish = scoreProbeCandidateForLanguage(englishSentence, "es")
        assertTrue(
            enScoreForEnglish > esScoreForEnglish,
            "英語文は confidence == null でも en スコア ($enScoreForEnglish) > es スコア ($esScoreForEnglish) になる",
        )
    }

    @Test
    fun `transcribeChunk_compareBothLanguagesがtrueのときLID確信度が高くても両言語を実行して自然度が高い言語を採用する`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val executedLocales = mutableListOf<String>()
        val transcriber = AndroidSpeechRecognizerChunkTranscriber(
            context = context,
            audioStreamDecoder = constantDecoder(durationSec = 2),
            onDeviceAvailabilityChecker = { true },
            standardAvailabilityChecker = { true },
            attemptRunner = { _, _, bcp47LocaleTag, _ ->
                executedLocales += bcp47LocaleTag
                when (bcp47LocaleTag) {
                    // LID が高確信度 (0.95) で en と誤判定したが、英語 STT の結果は自然度が低い断片
                    "en-US" -> SpeechRecognitionAttemptResult(
                        text = "oh ha yo go za",
                        confidence = null,
                    )

                    // 日本語 STT の結果は自然な日本語文
                    "ja-JP" -> SpeechRecognitionAttemptResult(
                        text = "おはようございます。今日もよろしくお願いします。",
                        confidence = null,
                    )

                    else -> null
                }
            },
            languageDetector = { _, _, _ ->
                LanguagePairPrediction(
                    selectedTag = "en",
                    fallbackTag = "ja",
                    selectedProbability = 0.95f,
                    targetProbability = 0.95f,
                    baseProbability = 0.05f,
                    inferenceTimeMs = 10L,
                )
            },
        )

        val audioFile = tempFolder.newFile("compare_both.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val result = transcriber.transcribeChunk(
            audioFile = audioFile,
            startMs = 0L,
            endMs = 2_000L,
            languagePair = LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja")),
            forceRefresh = true,
            highPriority = true,
            itemSttOverride = com.miuraken.chunkflow.core.model.ItemSttOverride.NONE,
            engineByLanguage = emptyMap(),
            precomputedLanguage = null,
            precomputedLanguageConfidence = null,
            compareBothLanguages = true,
        )

        assertNotNull(result)
        assertEquals(listOf("en-US", "ja-JP"), executedLocales)
        assertEquals(LanguageTag("ja"), result.language)
        assertEquals("おはようございます。今日もよろしくお願いします。", result.text)
    }

    /** [durationSec] 秒の一定振幅の音声を返すデコーダ。 */
    private fun constantDecoder(durationSec: Int): AudioStreamDecoder = object : AudioStreamDecoder {
        override suspend fun decode(
            file: File,
            sink: SampleSink,
            outputSampleRate: Int,
            expectedDurationMs: Long?,
            onProgress: suspend (DecodeProgress) -> Unit,
        ): AudioDecodeResult {
            val samples = FloatArray(outputSampleRate * durationSec) { 0.3f }
            sink.onSamples(samples, samples.size)
            return AudioDecodeResult.Success(
                sampleRate = outputSampleRate,
                channels = 1,
                decodedFrames = samples.size.toLong(),
                resampledSamples = samples.size.toLong(),
                durationMs = durationSec * 1_000L,
                encoderDelay = 0,
                encoderPadding = 0,
                mimeType = "audio/wav",
            )
        }
    }

    /** ダウンロード要求を記録し、通知をテストから送れる [RecognitionModelGateway]。 */
    private class FakeRecognitionModelGateway : RecognitionModelGateway {
        val downloadRequests = mutableListOf<String>()
        var supportResult: Map<String, RecognitionModelSupport> = emptyMap()
        private var lastOnEvent: ((RecognitionModelDownloadEvent) -> Unit)? = null

        override fun requestDownload(
            bcp47LocaleTag: String,
            onEvent: (RecognitionModelDownloadEvent) -> Unit,
        ): Boolean {
            downloadRequests += bcp47LocaleTag
            lastOnEvent = onEvent
            return true
        }

        override suspend fun checkSupport(bcp47LocaleTags: List<String>): Map<String, RecognitionModelSupport> = supportResult.filterKeys { it in bcp47LocaleTags }

        fun emit(event: RecognitionModelDownloadEvent) {
            lastOnEvent?.invoke(event)
        }
    }
}
