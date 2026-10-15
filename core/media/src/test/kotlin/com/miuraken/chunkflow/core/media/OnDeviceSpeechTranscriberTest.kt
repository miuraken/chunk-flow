package com.miuraken.chunkflow.core.media

import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
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
}
