package com.miuraken.chunkflow.core.media

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 端末内音声認識による 1 チャンク分の認識結果（F6.2）。
 *
 * @property text 認識されたテキスト（前後空白除去済み）。
 * @property language 検出または指定された言語タグ（不明な場合は `null`）。
 * @property confidence 認識信頼度（0.0..1.0、取得できない場合は `null`）。
 * @property engineId 使用した端末内認識エンジンの識別子。
 */
data class OnDeviceChunkTranscriptionResult(
    val text: String,
    val language: LanguageTag? = null,
    val confidence: Float? = null,
    val engineId: EngineId = DEFAULT_ON_DEVICE_ENGINE_ID,
) {
    companion object {
        val DEFAULT_ON_DEVICE_ENGINE_ID: EngineId = EngineId("android-on-device-speech")
    }
}

/**
 * チャンク単位（16kHz モノラル 16-bit PCM または音声ファイルの `[startMs, endMs]` 区間）で
 * 端末内音声認識を行うインターフェース（F6.2）。
 */
interface OnDeviceChunkTranscriber {
    /** 端末内音声認識が利用可能かどうかを返す。 */
    fun isAvailable(): Boolean

    /**
     * 音声ファイル [audioFile] の `[startMs, endMs]` 区間を 16kHz モノラル 16-bit PCM に切り出して端末内音声認識を行う。
     *
     * 認識不能・非対応端末・エミュレータ・無音の場合はクラッシュせず `null` を返す。
     */
    suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair? = null,
    ): OnDeviceChunkTranscriptionResult?

    /**
     * 切り出し済みの 16kHz モノラル 16-bit リトルエンディアン PCM（[pcm16LeBytes]）に対して端末内音声認識を行う。
     */
    suspend fun transcribePcm16Le(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        languagePair: LanguagePair? = null,
    ): OnDeviceChunkTranscriptionResult? = null

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000
    }
}

/**
 * 音声ファイルから指定したチャンク区間 `[startMs, endMs]` の 16kHz モノラル 16-bit PCM を取り出すヘルパー（F6.2）。
 */
object ChunkPcmExtractor {

    /**
     * 正規化 Float サンプル配列（`-1.0..1.0`）を 16-bit 符号付き整数（[ShortArray]）へ変換する。
     */
    fun floatToPcm16Shorts(samples: FloatArray, count: Int = samples.size): ShortArray {
        val safeCount = count.coerceIn(0, samples.size)
        val out = ShortArray(safeCount)
        for (i in 0 until safeCount) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            val scaled = (clamped * Short.MAX_VALUE.toFloat()).roundToInt()
            out[i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /**
     * 16-bit 符号付き整数配列（[ShortArray]）をリトルエンディアン [ByteArray] へ変換する。
     */
    fun shortsToPcm16LeBytes(shorts: ShortArray, count: Int = shorts.size): ByteArray {
        val safeCount = count.coerceIn(0, shorts.size)
        val buffer = ByteBuffer.allocate(safeCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until safeCount) {
            buffer.putShort(shorts[i])
        }
        return buffer.array()
    }

    /**
     * [decoder] を用いて [audioFile] から `[startMs, endMs]` 区間のモノラル 16-bit PCM（[ShortArray]）を抽出する。
     */
    suspend fun extractChunkPcm16Shorts(
        decoder: AudioStreamDecoder,
        audioFile: File,
        startMs: Long,
        endMs: Long,
        sampleRateHz: Int = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
    ): ShortArray? {
        val bytes = extractChunkPcm16LeBytes(
            decoder = decoder,
            audioFile = audioFile,
            startMs = startMs,
            endMs = endMs,
            sampleRateHz = sampleRateHz,
        ) ?: return null
        val shortCount = bytes.size / 2
        val shorts = ShortArray(shortCount)
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortCount) {
            shorts[i] = byteBuffer.short
        }
        return shorts
    }

    /**
     * [decoder] を用いて [audioFile] から `[startMs, endMs]` 区間のモノラル 16-bit リトルエンディアン PCM（[ByteArray]）を抽出する。
     */
    suspend fun extractChunkPcm16LeBytes(
        decoder: AudioStreamDecoder,
        audioFile: File,
        startMs: Long,
        endMs: Long,
        sampleRateHz: Int = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
    ): ByteArray? {
        if (startMs < 0L || endMs <= startMs || sampleRateHz <= 0 || !audioFile.isFile) {
            return null
        }
        val startSample = (startMs * sampleRateHz) / 1_000L
        val endSample = (endMs * sampleRateHz) / 1_000L
        if (endSample <= startSample) return null

        val expectedBytes = ((endSample - startSample) * 2L).coerceAtMost(MAX_CHUNK_PCM_BYTES.toLong()).toInt()
        val out = ByteArrayOutputStream(max(expectedBytes, 1024))
        var currentSampleIndex = 0L

        val result = decoder.decode(
            file = audioFile,
            outputSampleRate = sampleRateHz,
            sink = SampleSink { samples, count ->
                val blockStart = currentSampleIndex
                val blockEnd = currentSampleIndex + count
                currentSampleIndex = blockEnd

                val sliceStart = max(startSample, blockStart)
                val sliceEnd = min(endSample, blockEnd)
                if (sliceStart < sliceEnd && out.size() < MAX_CHUNK_PCM_BYTES) {
                    val localFrom = (sliceStart - blockStart).toInt()
                    val localTo = (sliceEnd - blockStart).toInt()
                    for (idx in localFrom until localTo) {
                        if (out.size() + 2 > MAX_CHUNK_PCM_BYTES) break
                        val clamped = samples[idx].coerceIn(-1.0f, 1.0f)
                        val sampleInt = (clamped * Short.MAX_VALUE.toFloat())
                            .roundToInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        out.write(sampleInt and 0xFF)
                        out.write((sampleInt ushr 8) and 0xFF)
                    }
                }
            },
        )

        return when (result) {
            is AudioDecodeResult.Failure -> null
            is AudioDecodeResult.Success -> out.toByteArray().takeIf { it.isNotEmpty() }
        }
    }

    private const val MAX_CHUNK_PCM_BYTES: Int = 4 * 1024 * 1024
}

/**
 * Android 標準の [SpeechRecognizer.createOnDeviceSpeechRecognizer]（API 33+ の `EXTRA_AUDIO_SOURCE` +
 * `ParcelFileDescriptor.createPipe()`、API 34+ の言語自動検出）を用いた [OnDeviceChunkTranscriber] 実装（F6.2）。
 *
 * エミュレータや `isOnDeviceRecognitionAvailable(context) == false` の端末でもクラッシュせず安全に `null` を返す。
 */
@Singleton
class AndroidSpeechRecognizerChunkTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioStreamDecoder: AudioStreamDecoder,
) : OnDeviceChunkTranscriber {

    override fun isAvailable(): Boolean = runCatching {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context) ||
            SpeechRecognizer.isRecognitionAvailable(context)
    }.getOrDefault(false)

    override suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
    ): OnDeviceChunkTranscriptionResult? {
        if (!isAvailable() || startMs < 0L || endMs <= startMs || !audioFile.isFile) {
            return null
        }
        val pcmBytes = try {
            ChunkPcmExtractor.extractChunkPcm16LeBytes(
                decoder = audioStreamDecoder,
                audioFile = audioFile,
                startMs = startMs,
                endMs = endMs,
                sampleRateHz = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        } ?: return null

        return transcribePcm16Le(
            pcm16LeBytes = pcmBytes,
            sampleRateHz = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
            languagePair = languagePair,
        )
    }

    override suspend fun transcribePcm16Le(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        languagePair: LanguagePair?,
    ): OnDeviceChunkTranscriptionResult? {
        if (!isAvailable() || pcm16LeBytes.isEmpty()) {
            return null
        }

        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: Exception) {
            return null
        }
        val readSide = pipe[0]
        val writeSide = pipe[1]

        return try {
            coroutineScope {
                val writerJob = launch {
                    runCatching {
                        ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                            var offset = 0
                            while (offset < pcm16LeBytes.size) {
                                val len = min(PIPE_WRITE_CHUNK_BYTES, pcm16LeBytes.size - offset)
                                output.write(pcm16LeBytes, offset, len)
                                offset += len
                            }
                            output.flush()
                        }
                    }
                }

                val recognitionResult = withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
                    recognizeFromPipeOnMainLooper(
                        readSide = readSide,
                        sampleRateHz = sampleRateHz,
                        languagePair = languagePair,
                    )
                }
                writerJob.cancel()
                recognitionResult
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        } finally {
            runCatching { readSide.close() }
            runCatching { writeSide.close() }
        }
    }

    private suspend fun recognizeFromPipeOnMainLooper(
        readSide: ParcelFileDescriptor,
        sampleRateHz: Int,
        languagePair: LanguagePair?,
    ): OnDeviceChunkTranscriptionResult? = suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null
        var detectedLanguageTag: String? = null

        val cleanup = Runnable {
            runCatching {
                recognizer?.cancel()
                recognizer?.destroy()
            }
            recognizer = null
        }

        continuation.invokeOnCancellation {
            mainHandler.post(cleanup)
        }

        mainHandler.post {
            if (!continuation.isActive) return@post
            try {
                val useOnDevice = runCatching {
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                }.getOrDefault(false)
                val created = if (useOnDevice) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                recognizer = created

                val listener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onLanguageDetection(results: Bundle) {
                        val topLang = results.getString(SpeechRecognizer.TOP_LOCALE_ALTERNATIVES)
                            ?: results.getStringArrayList(SpeechRecognizer.TOP_LOCALE_ALTERNATIVES)?.firstOrNull()
                        if (!topLang.isNullOrBlank()) {
                            detectedLanguageTag = normalizeBcp47ToPrimaryLanguage(topLang, languagePair)
                        }
                    }

                    override fun onError(error: Int) {
                        cleanup.run()
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val bestText = matches?.firstOrNull()?.trim().orEmpty()
                        val bestConfidence = scores?.firstOrNull()?.takeIf { it in 0.0f..1.0f }
                        val resolvedLang = (detectedLanguageTag ?: languagePair?.target?.value)
                            ?.takeIf { it.isNotBlank() }
                            ?.let(::LanguageTag)

                        cleanup.run()
                        if (continuation.isActive) {
                            if (bestText.isNotEmpty()) {
                                continuation.resume(
                                    OnDeviceChunkTranscriptionResult(
                                        text = bestText,
                                        language = resolvedLang,
                                        confidence = bestConfidence,
                                    ),
                                )
                            } else {
                                continuation.resume(null)
                            }
                        }
                    }
                }

                created.setRecognitionListener(listener)
                val intent = buildRecognizerIntent(
                    readSide = readSide,
                    sampleRateHz = sampleRateHz,
                    languagePair = languagePair,
                    useOnDevice = useOnDevice,
                )
                created.startListening(intent)
            } catch (_: Exception) {
                cleanup.run()
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    private fun buildRecognizerIntent(
        readSide: ParcelFileDescriptor,
        sampleRateHz: Int,
        languagePair: LanguagePair?,
        useOnDevice: Boolean,
    ): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, useOnDevice)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRateHz)
            if (languagePair != null) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languagePair.target.value)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                val allowed = ArrayList<String>(2).apply {
                    add(languagePair.target.value)
                    languagePair.base?.value?.let { base ->
                        if (base != languagePair.target.value) add(base)
                    }
                }
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowed)
            }
        }
        return intent
    }

    private fun normalizeBcp47ToPrimaryLanguage(rawTag: String, languagePair: LanguagePair?): String {
        val normalized = rawTag.trim().replace('_', '-')
        val primary = normalized.substringBefore('-').lowercase()
        if (languagePair != null) {
            if (languagePair.target.value.equals(normalized, ignoreCase = true) ||
                languagePair.target.value.equals(primary, ignoreCase = true)
            ) {
                return languagePair.target.value
            }
            val baseVal = languagePair.base?.value
            if (baseVal != null &&
                (baseVal.equals(normalized, ignoreCase = true) || baseVal.equals(primary, ignoreCase = true))
            ) {
                return baseVal
            }
        }
        return primary.ifEmpty { normalized }
    }

    companion object {
        private const val PIPE_WRITE_CHUNK_BYTES: Int = 4_096
        private const val RECOGNITION_TIMEOUT_MS: Long = 10_000L
    }
}
