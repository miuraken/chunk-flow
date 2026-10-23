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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini API の呼び出し先。
 *
 * @property label 利用者に見せるエラーの文言で使う名前。
 */
internal enum class GeminiEndpoint(val label: String) {
    /** Google AI Studio の Gemini API（`generativelanguage.googleapis.com`）。 */
    AI_STUDIO("AI Studio"),

    /** Vertex AI Express モード（`aiplatform.googleapis.com`）。`AQ.` で始まるキーのときだけ試す。 */
    VERTEX_EXPRESS("Vertex AI Express"),
}

/**
 * 文字起こしに成功した Gemini の呼び出し先 [endpoint] とモデル [model]（API キーのハッシュ [keyHash] ごとに覚える）。
 */
internal data class GeminiResolvedRoute(
    val keyHash: Int,
    val endpoint: GeminiEndpoint,
    val model: String,
)

/**
 * Gemini の呼び出しの失敗（呼び出し先ごとの最後の失敗）。利用者に見せるエラーの文言を選ぶのに使う。
 *
 * @property apiError エラーの本文を解析した結果（本文が無い・解析できないときは `null`）。
 */
internal data class GeminiAttemptFailure(
    val endpoint: GeminiEndpoint,
    val model: String,
    val httpCode: Int,
    val apiError: GoogleApiError?,
)

/**
 * OpenAI 互換 API (`gpt-4o-mini-transcribe`) / Groq 互換 API (`whisper-large-v3-turbo`) /
 * Gemini API（主のモデルは `gemini-3.8-flash`）を用いてチャンク音声を文字起こしするクラウドクライアント実装。
 *
 * Gemini API の呼び出し方:
 * - 主のモデル [GEMINI_PRIMARY_MODEL] が 404 NOT_FOUND（提供終了・利用不可）を返したら、[GEMINI_FALLBACK_MODEL] で 1 回だけ試し直す。
 * - 文字起こしに思考（thinking）は要らないので、`generationConfig.thinkingConfig.thinkingLevel` に [GEMINI_THINKING_LEVEL] を付ける。
 *   400 INVALID_ARGUMENT で thinking の設定を拒否されたら、付けずに 1 回だけ試し直し、以後その呼び出し先とモデルには付けない。
 * - `AQ.` で始まるキーは、AI Studio と Vertex AI Express の両方を試す（[resolveGeminiCandidateEndpoints]）。
 * - 成功した呼び出し先とモデルは、キーのハッシュごとにメモリに覚え、次のチャンクから最初に使う。
 * - 失敗したときは、エラーの本文（Google のエラーの JSON）を読んで、理由に合わせた日本語の案内を返す（[formatGeminiFailureMessage]）。
 *
 * ネットワーク通信は `core:media` 内に閉じる（AGENTS.md §6）。
 * API キー・文字起こしの本文・API が返す説明文はログに出さない（AGENTS.md §10）。ログに出すのは、
 * HTTP コード・status・reason・試行の番号・呼び出し先・モデル名だけ。
 */
@Singleton
internal class CloudSpeechTranscriber internal constructor(
    private val userSettingsRepository: UserSettingsRepository,
    private val httpClient: CloudHttpClient,
) {

    @Inject
    internal constructor(userSettingsRepository: UserSettingsRepository) : this(
        userSettingsRepository = userSettingsRepository,
        httpClient = HttpUrlConnectionCloudHttpClient,
    )

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
                    if (apiKey.isEmpty()) {
                        return OnDeviceChunkTranscriptionResult(
                            text = "",
                            language = language,
                            engineId = OPENAI_ENGINE_ID,
                            errorMessage = "OpenAI API キーが設定されていません。設定画面の「クラウド文字起こし API キー」で入力し、「保存」を押してください。",
                        )
                    }
                    callOpenAiCompatibleMultipart(
                        endpointUrl = OPENAI_TRANSCRIPTIONS_URL,
                        apiKey = apiKey,
                        modelName = OPENAI_MODEL,
                        wavBytes = wavBytes,
                        language = language,
                        engineId = OPENAI_ENGINE_ID,
                        providerLabel = "OpenAI API",
                    )
                }

                SttEngine.GROQ_API -> {
                    val apiKey = settings.groqApiKey.trim()
                    if (apiKey.isEmpty()) {
                        return OnDeviceChunkTranscriptionResult(
                            text = "",
                            language = language,
                            engineId = GROQ_ENGINE_ID,
                            errorMessage = "Groq API キーが設定されていません。設定画面の「クラウド文字起こし API キー」で入力し、「保存」を押してください。",
                        )
                    }
                    callOpenAiCompatibleMultipart(
                        endpointUrl = GROQ_TRANSCRIPTIONS_URL,
                        apiKey = apiKey,
                        modelName = GROQ_MODEL,
                        wavBytes = wavBytes,
                        language = language,
                        engineId = GROQ_ENGINE_ID,
                        providerLabel = "Groq API",
                    )
                }

                SttEngine.GEMINI_API -> {
                    val apiKey = settings.geminiApiKey.trim()
                    if (apiKey.isEmpty()) {
                        return OnDeviceChunkTranscriptionResult(
                            text = "",
                            language = language,
                            engineId = GEMINI_ENGINE_ID,
                            errorMessage = "Gemini API キーが設定されていません。設定画面の「クラウド文字起こし API キー」で入力し、「保存」を押してください。",
                        )
                    }
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
            val engineId = when (engine) {
                SttEngine.OPENAI_API -> OPENAI_ENGINE_ID
                SttEngine.GROQ_API -> GROQ_ENGINE_ID
                else -> GEMINI_ENGINE_ID
            }
            val providerLabel = when (engine) {
                SttEngine.OPENAI_API -> "OpenAI API"
                SttEngine.GROQ_API -> "Groq API"
                else -> GEMINI_PROVIDER_LABEL
            }
            OnDeviceChunkTranscriptionResult(
                text = "",
                language = language,
                engineId = engineId,
                errorMessage = "$providerLabel への通信に失敗しました（ネットワーク接続を確認してください: ${e.javaClass.simpleName}）。",
            )
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
        providerLabel: String,
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

        val response = httpClient.post(
            url = endpointUrl,
            headers = mapOf(
                "Authorization" to "Bearer $apiKey",
                "Content-Type" to "multipart/form-data; boundary=$boundary",
            ),
            body = bodyBytes,
        )
        if (response.code !in 200..299) {
            logInfo("OpenAI/Groq API returned HTTP ${response.code} for engine=${engineId.value}")
            return OnDeviceChunkTranscriptionResult(
                text = "",
                language = language,
                engineId = engineId,
                errorMessage = formatCloudHttpErrorMessage(
                    providerLabel = providerLabel,
                    httpCode = response.code,
                    apiKey = apiKey,
                    isGemini = false,
                ),
            )
        }
        val text = (parseExternalJsonObject(response.body).opt("text") as? String).orEmpty().trim()
        if (text.isEmpty()) return null
        return OnDeviceChunkTranscriptionResult(
            text = text,
            language = language,
            confidence = 0.92f,
            engineId = engineId,
        )
    }

    /**
     * 文字起こしに成功した Gemini の呼び出し先とモデル（キーのハッシュごとに最新の 1 件）。
     *
     * `AQ.` から始まるキー（Google AI Studio の Auth Key または Vertex AI Express Mode のキー）は、成功した呼び出し先を
     * 次から先に試す。モデルも、主のモデルが 404 で予備のモデルに切り替えたときに、次のチャンクで 404 を繰り返さないよう覚える。
     */
    @Volatile
    private var resolvedGeminiRoute: GeminiResolvedRoute? = null

    /** `thinkingConfig` を 400 INVALID_ARGUMENT で拒否した呼び出し先とモデルの組（[geminiRouteKey]）。次からは付けない。 */
    private val geminiThinkingConfigRejectedRoutes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun callGeminiGenerateContent(
        apiKey: String,
        wavBytes: ByteArray,
        language: LanguageTag,
    ): OnDeviceChunkTranscriptionResult? {
        val base64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val prompt = "Transcribe the speech in this audio clip accurately in ${language.value}. " +
            "Return ONLY the verbatim transcription text without any explanations, timestamps, or formatting."
        // thinkingConfig の有無で本文が 2 通りあるので、使うときに 1 回だけ組み立てる
        val bodyWithThinkingConfig by lazy(LazyThreadSafetyMode.NONE) {
            buildGeminiGenerateContentRequest(base64Audio, prompt, includeThinkingConfig = true).toByteArray(Charsets.UTF_8)
        }
        val bodyWithoutThinkingConfig by lazy(LazyThreadSafetyMode.NONE) {
            buildGeminiGenerateContentRequest(base64Audio, prompt, includeThinkingConfig = false).toByteArray(Charsets.UTF_8)
        }
        val headers = mapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "x-goog-api-key" to apiKey,
        )

        val keyHash = apiKey.hashCode()
        val cachedRoute = resolvedGeminiRoute?.takeIf { it.keyHash == keyHash }
        val endpoints = resolveGeminiCandidateEndpoints(apiKey, cachedRoute?.endpoint)
        val models = resolveGeminiCandidateModels(cachedRoute?.model)

        val endpointFailures = ArrayList<GeminiAttemptFailure>(endpoints.size)
        var attempt = 0
        for (endpoint in endpoints) {
            var endpointFailure: GeminiAttemptFailure? = null
            for (model in models) {
                var includeThinkingConfig = geminiRouteKey(endpoint, model) !in geminiThinkingConfigRejectedRoutes
                while (true) {
                    attempt += 1
                    val response = httpClient.post(
                        url = buildGeminiGenerateContentUrl(endpoint, model),
                        headers = headers,
                        body = if (includeThinkingConfig) bodyWithThinkingConfig else bodyWithoutThinkingConfig,
                    )
                    if (response.code in 200..299) {
                        resolvedGeminiRoute = GeminiResolvedRoute(keyHash = keyHash, endpoint = endpoint, model = model)
                        val text = extractGeminiResponseText(response.body)
                        if (text.isEmpty()) return null
                        return OnDeviceChunkTranscriptionResult(
                            text = text,
                            language = language,
                            confidence = 0.92f,
                            engineId = geminiEngineIdFor(model),
                        )
                    }
                    val apiError = parseGoogleApiError(response.body)
                    logInfo(
                        "Gemini API returned HTTP ${response.code} status=${apiError?.status} " +
                            "reason=${apiError?.primaryReason} (attempt=$attempt endpoint=${endpoint.name} " +
                            "model=$model thinkingConfig=$includeThinkingConfig)",
                    )
                    if (includeThinkingConfig && isGeminiThinkingConfigRejected(response.code, apiError)) {
                        // モデルが thinking の設定を受け付けない: 外して 1 回だけ試し直し、次からは付けない
                        geminiThinkingConfigRejectedRoutes += geminiRouteKey(endpoint, model)
                        includeThinkingConfig = false
                        continue
                    }
                    endpointFailure = GeminiAttemptFailure(
                        endpoint = endpoint,
                        model = model,
                        httpCode = response.code,
                        apiError = apiError,
                    )
                    break
                }
                // 404 NOT_FOUND（モデルが提供終了・利用不可）のときだけ、次のモデルを試す
                if (endpointFailure.httpCode != 404) break
            }
            val failure = endpointFailure ?: continue
            endpointFailures += failure
            // キーがこの呼び出し先で使えない（400 / 401 / 403）か、モデルが無い（404）ときだけ、もう一方の呼び出し先を試す
            if (failure.httpCode !in GEMINI_ENDPOINT_FALLBACK_HTTP_CODES) break
        }

        val reported = selectGeminiFailureToReport(endpointFailures)
        return OnDeviceChunkTranscriptionResult(
            text = "",
            language = language,
            engineId = reported?.let { geminiEngineIdFor(it.model) } ?: GEMINI_ENGINE_ID,
            errorMessage = formatGeminiFailureMessage(apiKey = apiKey, failures = endpointFailures),
        )
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

        /**
         * 文字起こしに使う主の Gemini のモデル。
         *
         * 以前の `gemini-2.5-flash` は新しい利用者には提供されなくなった（2026-10 に HTTP 404 で確認）ため、
         * Google が後継として勧めるこのモデルにした。
         */
        internal const val GEMINI_PRIMARY_MODEL = "gemini-3.8-flash"

        /** 主のモデルが 404 NOT_FOUND（提供終了・利用不可）を返したときに 1 回だけ試す、最新の Flash を指す別名。 */
        internal const val GEMINI_FALLBACK_MODEL = "gemini-flash-latest"

        /** 試す順の Gemini のモデル（主 → 予備）。 */
        internal val GEMINI_MODEL_CANDIDATES: List<String> = listOf(GEMINI_PRIMARY_MODEL, GEMINI_FALLBACK_MODEL)

        /**
         * 文字起こしで思考を抑えるための `generationConfig.thinkingConfig.thinkingLevel`。
         *
         * `"minimal"` は `gemini-3.8-flash`（と、それを指す `gemini-flash-latest`）が 400 INVALID_ARGUMENT
         * 「Thinking level MINIMAL is not supported for this model.」で拒否する。`"low"` なら思考のトークンが 0 で、
         * thinkingConfig を付けないとき（既定の思考あり）より速かった（2026-10-23 に合成音声で確認）。
         */
        internal const val GEMINI_THINKING_LEVEL = "low"

        /**
         * Gemini のモデル [model] で文字起こししたときのエンジン ID（`cloud-` + モデル名。例: `cloud-gemini-3.8-flash`）。
         *
         * 以前の `cloud-gemini-2.5-flash` と同じ形で、どのモデルで文字起こししたかを残す。
         * Gemini のモデル名はどれも `gemini-` で始まるので、Gemini のエンジン ID はどれも `cloud-gemini-` で始まる。
         */
        internal fun geminiEngineIdFor(model: String): EngineId = EngineId("cloud-$model")

        /**
         * まだ使うモデルが決まっていないとき（キーが未設定・通信に失敗など）の Gemini のエンジン ID（主のモデルの ID）。
         * 文字起こしできたときは、実際に使ったモデルの ID（[geminiEngineIdFor]）を返す。
         */
        val GEMINI_ENGINE_ID: EngineId = geminiEngineIdFor(GEMINI_PRIMARY_MODEL)

        private const val OPENAI_TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val OPENAI_MODEL = "gpt-4o-mini-transcribe"

        private const val GROQ_TRANSCRIPTIONS_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        private const val GROQ_MODEL = "whisper-large-v3-turbo"

        private const val GEMINI_PROVIDER_LABEL = "Gemini API"
        private const val GEMINI_AI_STUDIO_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        private const val GEMINI_VERTEX_EXPRESS_MODELS_URL = "https://aiplatform.googleapis.com/v1beta1/publishers/google/models/"
        private const val GEMINI_GENERATE_CONTENT_METHOD = ":generateContent"

        private const val MAX_WAV_BYTES = 16 * 1024 * 1024 // 16 MB 上限

        /** もう一方の呼び出し先を試す HTTP コード（キーがその呼び出し先で使えない・モデルが無い）。 */
        private val GEMINI_ENDPOINT_FALLBACK_HTTP_CODES = setOf(400, 401, 403, 404)

        /** モデル名として受け付ける形（URL のパスに入れるので、英小文字・数字・`.`・`-` だけ）。 */
        private val GEMINI_MODEL_NAME_PATTERN = Regex("[a-z0-9][a-z0-9.-]{0,63}")

        private fun geminiRouteKey(endpoint: GeminiEndpoint, model: String): String = "${endpoint.name}/$model"

        /**
         * Gemini API キーのプレフィックスに応じて、試す呼び出し先の順番を返す。
         *
         * - `AIza...`: Google AI Studio 標準キー → AI Studio（`generativelanguage.googleapis.com`）のみ。形式の違うキーも同じ。
         * - `AQ....`: Google AI Studio の新形式キー（Auth Key）と Vertex AI Express Mode キーの両方が存在するため、
         *   両方（AI Studio → Vertex AI Express）を順に試行する。前に成功した呼び出し先 [cachedEndpoint] があれば先に試す。
         */
        internal fun resolveGeminiCandidateEndpoints(
            apiKey: String,
            cachedEndpoint: GeminiEndpoint? = null,
        ): List<GeminiEndpoint> {
            if (!apiKey.startsWith("AQ.")) {
                return listOf(GeminiEndpoint.AI_STUDIO)
            }
            return when (cachedEndpoint) {
                GeminiEndpoint.VERTEX_EXPRESS -> listOf(
                    GeminiEndpoint.VERTEX_EXPRESS,
                    GeminiEndpoint.AI_STUDIO,
                )

                else -> listOf(
                    GeminiEndpoint.AI_STUDIO,
                    GeminiEndpoint.VERTEX_EXPRESS,
                )
            }
        }

        /**
         * 試す Gemini のモデルの順番を返す。
         *
         * 前に成功したモデル [cachedModel] があればそれを先にし（主のモデルの 404 を繰り返さない）、残りの候補を続ける。
         * 次のモデルに進むのは、前のモデルが 404 NOT_FOUND を返したときだけ。
         */
        internal fun resolveGeminiCandidateModels(cachedModel: String? = null): List<String> {
            if (cachedModel == null || cachedModel !in GEMINI_MODEL_CANDIDATES) return GEMINI_MODEL_CANDIDATES
            return listOf(cachedModel) + GEMINI_MODEL_CANDIDATES.filter { it != cachedModel }
        }

        /**
         * 呼び出し先 [endpoint] とモデル [model] から、generateContent の URL を組み立てる。
         *
         * @throws IllegalArgumentException [model] がモデル名の形（英小文字・数字・`.`・`-`）でないとき。
         */
        internal fun buildGeminiGenerateContentUrl(endpoint: GeminiEndpoint, model: String): String {
            require(GEMINI_MODEL_NAME_PATTERN.matches(model)) { "invalid Gemini model name" }
            val modelsUrl = when (endpoint) {
                GeminiEndpoint.AI_STUDIO -> GEMINI_AI_STUDIO_MODELS_URL
                GeminiEndpoint.VERTEX_EXPRESS -> GEMINI_VERTEX_EXPRESS_MODELS_URL
            }
            return modelsUrl + model + GEMINI_GENERATE_CONTENT_METHOD
        }

        /**
         * Gemini の generateContent の要求の本文（JSON）を組み立てる。
         *
         * AI Studio (generativelanguage.googleapis.com) と Vertex AI Express (aiplatform.googleapis.com) の
         * 両方で共通して受け付けられる ProtoJSON 形式（role = "user"、inlineData / mimeType）。
         * [includeThinkingConfig] が `true` なら、思考を抑える `generationConfig.thinkingConfig.thinkingLevel`
         * （[GEMINI_THINKING_LEVEL]）を付ける。
         */
        internal fun buildGeminiGenerateContentRequest(
            base64Audio: String,
            prompt: String,
            includeThinkingConfig: Boolean,
        ): String {
            val generationConfig = JSONObject().put("temperature", 0.0)
            if (includeThinkingConfig) {
                generationConfig.put(
                    "thinkingConfig",
                    JSONObject().put("thinkingLevel", GEMINI_THINKING_LEVEL),
                )
            }
            val parts = JSONArray()
                .put(
                    JSONObject().put(
                        "inlineData",
                        JSONObject()
                            .put("mimeType", "audio/wav")
                            .put("data", base64Audio),
                    ),
                )
                .put(JSONObject().put("text", prompt))
            return JSONObject()
                .put(
                    "contents",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", parts),
                    ),
                )
                .put("generationConfig", generationConfig)
                .toString()
        }

        /**
         * generateContent の応答の本文から、最初の候補の文字起こしの本文を取り出す（思考の部分 `thought: true` は除く）。
         * 候補や本文が無ければ空文字。
         *
         * @throws org.json.JSONException 本文が JSON のオブジェクトでないとき、入れ子が深すぎるとき。
         */
        internal fun extractGeminiResponseText(responseBody: String): String {
            val parts = parseExternalJsonObject(responseBody)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                val partText = part.opt("text") as? String ?: continue
                sb.append(partText)
            }
            return sb.toString().trim()
        }

        /**
         * モデルが `thinkingConfig` を受け付けないエラー（400 INVALID_ARGUMENT で、説明文に thinking を含む）かどうか。
         */
        internal fun isGeminiThinkingConfigRejected(httpCode: Int, apiError: GoogleApiError?): Boolean = httpCode == 400 &&
            apiError != null &&
            apiError.status == GOOGLE_STATUS_INVALID_ARGUMENT &&
            apiError.message?.contains("thinking", ignoreCase = true) == true

        /**
         * 前払いクレジットが残っていないエラーかどうか
         * （HTTP 402、または status が RESOURCE_EXHAUSTED で、説明文に prepayment / credits を含む）。
         */
        internal fun isGeminiPrepaymentDepleted(httpCode: Int, apiError: GoogleApiError?): Boolean {
            if (httpCode == 402) return true
            if (apiError == null || apiError.status != GOOGLE_STATUS_RESOURCE_EXHAUSTED) return false
            val message = apiError.message ?: return false
            return message.contains("prepayment", ignoreCase = true) || message.contains("credits", ignoreCase = true)
        }

        /**
         * 呼び出し先ごとの最後の失敗 [failures]（試した順）から、利用者に見せる失敗を選ぶ。
         *
         * キーの「API の制限」で呼び出し先ごと拒否された失敗（API_KEY_SERVICE_BLOCKED）は情報が少ないので最後にし、
         * 次に認証の失敗（400 / 401 / 403）、いちばん優先するのはキーが通ったうえでの失敗（402 / 404 / 429 / 5xx など）。
         * 同じ優先度なら先に試したほうを選ぶ。
         */
        internal fun selectGeminiFailureToReport(failures: List<GeminiAttemptFailure>): GeminiAttemptFailure? {
            var selected: GeminiAttemptFailure? = null
            for (failure in failures) {
                if (selected == null || geminiFailurePriority(failure) > geminiFailurePriority(selected)) {
                    selected = failure
                }
            }
            return selected
        }

        private fun geminiFailurePriority(failure: GeminiAttemptFailure): Int = when {
            failure.apiError?.reasons?.contains(GOOGLE_REASON_API_KEY_SERVICE_BLOCKED) == true -> 0
            failure.httpCode == 400 || failure.httpCode == 401 || failure.httpCode == 403 -> 1
            else -> 2
        }

        /**
         * Gemini の呼び出しがすべて失敗したときに、利用者に見せる文言を組み立てる。
         *
         * [selectGeminiFailureToReport] で選んだ失敗の文言（[formatCloudHttpErrorMessage]）に、
         * 2 つの呼び出し先を試したときは、それぞれの HTTP コードを短く添える。
         */
        internal fun formatGeminiFailureMessage(
            apiKey: String,
            failures: List<GeminiAttemptFailure>,
        ): String {
            val reported = selectGeminiFailureToReport(failures)
                ?: return "Gemini API の呼び出しに失敗しました。設定画面の API キーや通信環境を確認してください。"
            val message = formatCloudHttpErrorMessage(
                providerLabel = GEMINI_PROVIDER_LABEL,
                httpCode = reported.httpCode,
                apiKey = apiKey,
                isGemini = true,
                apiError = reported.apiError,
            )
            if (failures.size < 2) return message
            val tried = failures.joinToString(separator = " / ") { "${it.endpoint.label} HTTP ${it.httpCode}" }
            return "$message（試した呼び出し先: $tried）"
        }

        /**
         * クラウド API の HTTP エラー [httpCode] を、利用者向けの日本語の文言にする。
         *
         * Gemini（[isGemini] が `true`）では、エラーの本文を解析した [apiError] があれば、理由（reason）に合わせて案内し、
         * 末尾に Google の説明文（API キーを伏せて [MAX_PROVIDER_MESSAGE_CHARS] 文字まで）を「（Google からの説明: …）」として添える。
         */
        internal fun formatCloudHttpErrorMessage(
            providerLabel: String,
            httpCode: Int,
            apiKey: String,
            isGemini: Boolean,
            apiError: GoogleApiError? = null,
        ): String {
            if (!isGemini) return formatOpenAiCompatibleHttpErrorMessage(providerLabel, httpCode)
            val base = formatGeminiHttpErrorMessage(providerLabel, httpCode, apiKey, apiError)
            val detail = apiError?.message?.let { sanitizeProviderMessage(it, apiKey) }.orEmpty()
            return if (detail.isEmpty()) base else "$base（Google からの説明: $detail）"
        }

        private fun formatGeminiHttpErrorMessage(
            providerLabel: String,
            httpCode: Int,
            apiKey: String,
            apiError: GoogleApiError?,
        ): String {
            val reason = apiError?.primaryReason
            return when {
                isGeminiPrepaymentDepleted(httpCode, apiError) ->
                    "Gemini API の前払いクレジットが残っていません（HTTP $httpCode）。Google AI Studio（https://ai.studio/projects）でプロジェクトの課金設定とクレジットを確認してください。"

                httpCode == 404 ->
                    "Gemini API で文字起こしに使うモデルが見つかりません（HTTP 404）。モデルが提供終了になった可能性があります。アプリを更新してください。"

                httpCode == 400 || httpCode == 401 || httpCode == 403 -> when {
                    !apiKey.startsWith("AIza") && !apiKey.startsWith("AQ.") ->
                        "Gemini API の認証に失敗しました（HTTP $httpCode）。現在入力されているキーは Gemini API キーの形式（AIza… または AQ.… で始まる文字列）ではありません。設定画面で有効な Gemini API キーを入力し、「保存」を押してください。"

                    reason == GOOGLE_REASON_API_KEY_SERVICE_BLOCKED ->
                        "Gemini API の呼び出しが API キーの制限で拒否されました（HTTP $httpCode、理由: $reason）。Cloud Console の認証情報でキーの『API の制限』に Generative Language API（Gemini API）が含まれているか確認してください。"

                    else -> {
                        val reasonPart = reason?.let { "、理由: $it" }.orEmpty()
                        "Gemini API の認証またはリクエストに失敗しました（HTTP $httpCode$reasonPart）。設定画面の Gemini API キー（AIza… または AQ.…）に誤りがないか、キーが無効化・期限切れ・API 制限されていないか確認してください。" +
                            geminiReasonHint(reason)
                    }
                }

                httpCode == 429 ->
                    "$providerLabel の利用上限（レート制限 / クォータ上限: HTTP 429）に達しました。しばらく待ってから再試行するか、利用プラン・残高を確認してください。"

                httpCode in 500..599 ->
                    "$providerLabel サーバー側で一時的なエラーが発生しました（HTTP $httpCode）。しばらく待ってから再試行してください。"

                else ->
                    "$providerLabel の呼び出しに失敗しました（HTTP $httpCode）。設定画面の API キーや通信環境を確認してください。"
            }
        }

        /** 認証・権限の失敗の理由 [reason] に合わせた追加の案内（無ければ空文字）。 */
        private fun geminiReasonHint(reason: String?): String = when (reason) {
            "SERVICE_DISABLED" ->
                "Cloud Console でプロジェクトの Generative Language API（Gemini API）が有効になっているか確認してください。"

            "API_KEY_ANDROID_APP_BLOCKED",
            "API_KEY_HTTP_REFERRER_BLOCKED",
            "API_KEY_IP_ADDRESS_BLOCKED",
            "API_KEY_IOS_APP_BLOCKED",
            -> "Cloud Console の認証情報でキーの『アプリケーションの制限』を確認してください。"

            else -> ""
        }

        private fun formatOpenAiCompatibleHttpErrorMessage(providerLabel: String, httpCode: Int): String = when (httpCode) {
            400, 401, 403 ->
                "$providerLabel の認証またはリクエストに失敗しました（HTTP $httpCode）。有効な API キーが設定画面で入力・保存されているか確認してください。"

            402 ->
                "$providerLabel の残高（クレジット）が不足しているか、支払いの設定に問題があります（HTTP 402）。$providerLabel の管理画面で課金設定と残高を確認してください。"

            429 ->
                "$providerLabel の利用上限（レート制限 / クォータ上限: HTTP 429）に達しました。しばらく待ってから再試行するか、利用プラン・残高を確認してください。"

            in 500..599 ->
                "$providerLabel サーバー側で一時的なエラーが発生しました（HTTP $httpCode）。しばらく待ってから再試行してください。"

            else ->
                "$providerLabel の呼び出しに失敗しました（HTTP $httpCode）。設定画面の API キーや通信環境を確認してください。"
        }
    }
}
