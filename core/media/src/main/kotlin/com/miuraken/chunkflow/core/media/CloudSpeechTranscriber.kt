package com.miuraken.chunkflow.core.media

import android.util.Base64
import android.util.Log
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.SupportedLanguageCatalog
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 互換 API (`gpt-4o-mini-transcribe`) / Groq 互換 API (`whisper-large-v3-turbo`) /
 * Gemini API (`gemini-2.5-flash`) を用いてチャンク音声を文字起こしするクラウドクライアント実装。
 *
 * ネットワーク通信は `core:media` 内に閉じる（AGENTS.md §6）。
 * リリースビルドでは文字起こし本文や API キーをログに出力しない（AGENTS.md §10）。
 */
@Singleton
internal class CloudSpeechTranscriber @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository,
) {

    suspend fun isEngineConfigured(engine: SttEngine): Boolean {
        val settings = runCatching { userSettingsRepository.settings.first() }.getOrNull() ?: return false
        return when (engine) {
            SttEngine.OPENAI_API -> settings.openAiApiKey.isNotBlank()
            SttEngine.GROQ_API -> settings.groqApiKey.isNotBlank()
            SttEngine.GEMINI_API -> settings.geminiApiKey.isNotBlank()
            else -> false
        }
    }

    suspend fun transcribeWavFile(
        engine: SttEngine,
        wavBytes: ByteArray,
        language: LanguageTag,
    ): OnDeviceChunkTranscriptionResult? {
        if (wavBytes.size <= 44 || wavBytes.size > MAX_WAV_BYTES) return null
        val settings = runCatching { userSettingsRepository.settings.first() }.getOrNull() ?: return null

        return try {
            when (engine) {
                SttEngine.OPENAI_API -> {
                    val apiKey = settings.openAiApiKey.trim()
                    if (apiKey.isEmpty()) return null
                    callOpenAiCompatibleMultipart(
                        endpointUrl = OPENAI_TRANSCRIPTIONS_URL,
                        apiKey = apiKey,
                        modelName = OPENAI_MODEL,
                        wavBytes = wavBytes,
                        language = language,
                        engineId = OPENAI_ENGINE_ID,
                    )
                }

                SttEngine.GROQ_API -> {
                    val apiKey = settings.groqApiKey.trim()
                    if (apiKey.isEmpty()) return null
                    callOpenAiCompatibleMultipart(
                        endpointUrl = GROQ_TRANSCRIPTIONS_URL,
                        apiKey = apiKey,
                        modelName = GROQ_MODEL,
                        wavBytes = wavBytes,
                        language = language,
                        engineId = GROQ_ENGINE_ID,
                    )
                }

                SttEngine.GEMINI_API -> {
                    val apiKey = settings.geminiApiKey.trim()
                    if (apiKey.isEmpty()) return null
                    callGeminiGenerateContent(
                        apiKey = apiKey,
                        wavBytes = wavBytes,
                        language = language,
                    )
                }

                else -> null
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            logInfo("cloud transcription failed engine=${engine.name} error=${e.javaClass.simpleName}")
            null
        }
    }

    suspend fun transcribePcm16Le(
        engine: SttEngine,
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        channelCount: Int,
        language: LanguageTag,
    ): OnDeviceChunkTranscriptionResult? {
        if (pcm16LeBytes.isEmpty() || sampleRateHz <= 0 || channelCount <= 0) return null
        val wavBytes = wrapPcm16LeAsWav(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
        )
        return transcribeWavFile(engine, wavBytes, language)
    }

    private fun callOpenAiCompatibleMultipart(
        endpointUrl: String,
        apiKey: String,
        modelName: String,
        wavBytes: ByteArray,
        language: LanguageTag,
        engineId: EngineId,
    ): OnDeviceChunkTranscriptionResult? {
        val boundary = "----ChunkFlowBoundary" + UUID.randomUUID().toString().replace("-", "")
        val lineEnd = "\r\n"
        val twoHyphens = "--"

        val primaryLang = SupportedLanguageCatalog.primarySubtag(language.value)
        val bodyBytes = ByteArrayOutputStream(wavBytes.size + 1024).use { bos ->
            DataOutputStream(bos).use { dos ->
                // file field
                dos.writeBytes("$twoHyphens$boundary$lineEnd")
                dos.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"$lineEnd")
                dos.writeBytes("Content-Type: audio/wav$lineEnd$lineEnd")
                dos.write(wavBytes)
                dos.writeBytes(lineEnd)

                // model field
                dos.writeBytes("$twoHyphens$boundary$lineEnd")
                dos.writeBytes("Content-Disposition: form-data; name=\"model\"$lineEnd$lineEnd")
                dos.writeBytes("$modelName$lineEnd")

                // language field (ISO-639-1 2文字コードのみ指定)
                if (primaryLang.length == 2) {
                    dos.writeBytes("$twoHyphens$boundary$lineEnd")
                    dos.writeBytes("Content-Disposition: form-data; name=\"language\"$lineEnd$lineEnd")
                    dos.writeBytes("$primaryLang$lineEnd")
                }

                // response_format=json
                dos.writeBytes("$twoHyphens$boundary$lineEnd")
                dos.writeBytes("Content-Disposition: form-data; name=\"response_format\"$lineEnd$lineEnd")
                dos.writeBytes("json$lineEnd")

                dos.writeBytes("$twoHyphens$boundary$twoHyphens$lineEnd")
                dos.flush()
            }
            bos.toByteArray()
        }

        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setFixedLengthStreamingMode(bodyBytes.size)
        }

        return try {
            connection.outputStream.use { out ->
                out.write(bodyBytes)
                out.flush()
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                logInfo("OpenAI/Groq API returned HTTP $code for engine=${engineId.value}")
                return null
            }
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(responseText)
            val text = json.optString("text", "").trim()
            if (text.isEmpty()) return null
            OnDeviceChunkTranscriptionResult(
                text = text,
                language = language,
                confidence = 0.92f,
                engineId = engineId,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun callGeminiGenerateContent(
        apiKey: String,
        wavBytes: ByteArray,
        language: LanguageTag,
    ): OnDeviceChunkTranscriptionResult? {
        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val prompt = "Transcribe the speech in this audio clip accurately in ${language.value}. " +
            "Return ONLY the verbatim transcription text without any explanations, timestamps, or formatting."

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray()
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject()
                                        .put("mime_type", "audio/wav")
                                        .put("data", base64Audio),
                                ),
                            )
                            .put(JSONObject().put("text", prompt)),
                    ),
                ),
            )
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.0),
            )
        }

        val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(GEMINI_GENERATE_CONTENT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
            setFixedLengthStreamingMode(bodyBytes.size)
        }

        return try {
            connection.outputStream.use { out ->
                out.write(bodyBytes)
                out.flush()
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                logInfo("Gemini API returned HTTP $code")
                return null
            }
            val responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(responseText)
            val candidates = json.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val firstCandidate = candidates.optJSONObject(0) ?: return null
            val content = firstCandidate.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val partText = parts.optJSONObject(i)?.optString("text", "").orEmpty()
                if (partText.isNotEmpty()) {
                    sb.append(partText)
                }
            }
            val text = sb.toString().trim()
            if (text.isEmpty()) return null
            OnDeviceChunkTranscriptionResult(
                text = text,
                language = language,
                confidence = 0.92f,
                engineId = GEMINI_ENGINE_ID,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun wrapPcm16LeAsWav(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        channelCount: Int,
    ): ByteArray {
        val byteRate = sampleRateHz * channelCount * 2
        val blockAlign = (channelCount * 2).toShort()
        val dataSize = pcm16LeBytes.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channelCount.toShort())
            putInt(sampleRateHz)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()
        val out = ByteArray(44 + dataSize)
        System.arraycopy(header, 0, out, 0, 44)
        System.arraycopy(pcm16LeBytes, 0, out, 44, dataSize)
        return out
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    companion object {
        private const val TAG = "CloudSpeechTranscriber"

        val OPENAI_ENGINE_ID = EngineId("cloud-openai-gpt-4o-mini-transcribe")
        val GROQ_ENGINE_ID = EngineId("cloud-groq-whisper-large-v3-turbo")
        val GEMINI_ENGINE_ID = EngineId("cloud-gemini-2.5-flash")

        private const val OPENAI_TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val OPENAI_MODEL = "gpt-4o-mini-transcribe"

        private const val GROQ_TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val GROQ_MODEL = "whisper-large-v3-turbo"

        private const val GEMINI_GENERATE_CONTENT_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_WAV_BYTES = 16 * 1024 * 1024 // 16 MB 上限
    }
}
