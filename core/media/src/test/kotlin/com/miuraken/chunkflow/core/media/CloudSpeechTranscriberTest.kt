package com.miuraken.chunkflow.core.media

import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.UserSettings
import java.io.ByteArrayInputStream
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * [CloudSpeechTranscriber] と、クラウド API のエラーの解析（CloudApiErrors.kt）のテスト。
 *
 * org.json と android.util.Base64 を使うので Robolectric で動かす。HTTP は [FakeCloudHttpClient] で差し替え、ネットワークに出ない。
 */
@RunWith(RobolectricTestRunner::class)
class CloudSpeechTranscriberTest {

    // ---- 呼び出し先とモデルの候補 ----

    @Test
    fun `resolveGeminiCandidateEndpoints_AIzaキーはAIStudioのみを返す`() {
        assertEquals(
            listOf(GeminiEndpoint.AI_STUDIO),
            CloudSpeechTranscriber.resolveGeminiCandidateEndpoints(AIZA_KEY),
        )
    }

    @Test
    fun `resolveGeminiCandidateEndpoints_AQキーはAIStudioとVertexAIExpressの両方を返し成功した呼び出し先を先にする`() {
        assertEquals(
            listOf(GeminiEndpoint.AI_STUDIO, GeminiEndpoint.VERTEX_EXPRESS),
            CloudSpeechTranscriber.resolveGeminiCandidateEndpoints(AQ_KEY),
        )
        assertEquals(
            listOf(GeminiEndpoint.AI_STUDIO, GeminiEndpoint.VERTEX_EXPRESS),
            CloudSpeechTranscriber.resolveGeminiCandidateEndpoints(AQ_KEY, cachedEndpoint = GeminiEndpoint.AI_STUDIO),
        )
        assertEquals(
            listOf(GeminiEndpoint.VERTEX_EXPRESS, GeminiEndpoint.AI_STUDIO),
            CloudSpeechTranscriber.resolveGeminiCandidateEndpoints(AQ_KEY, cachedEndpoint = GeminiEndpoint.VERTEX_EXPRESS),
        )
    }

    @Test
    fun `resolveGeminiCandidateEndpoints_形式の違うキーはAIStudioのみを返す`() {
        assertEquals(
            listOf(GeminiEndpoint.AI_STUDIO),
            CloudSpeechTranscriber.resolveGeminiCandidateEndpoints("sk-wrong-key", cachedEndpoint = GeminiEndpoint.VERTEX_EXPRESS),
        )
    }

    @Test
    fun `resolveGeminiCandidateModels_覚えたモデルが無ければ主のモデル_予備の別名の順`() {
        assertEquals(listOf("gemini-3.8-flash", "gemini-flash-latest"), CloudSpeechTranscriber.GEMINI_MODEL_CANDIDATES)
        assertEquals(CloudSpeechTranscriber.GEMINI_MODEL_CANDIDATES, CloudSpeechTranscriber.resolveGeminiCandidateModels())
    }

    @Test
    fun `resolveGeminiCandidateModels_覚えたモデルを先に試し残りの候補を続ける`() {
        assertEquals(
            listOf("gemini-flash-latest", "gemini-3.8-flash"),
            CloudSpeechTranscriber.resolveGeminiCandidateModels(cachedModel = "gemini-flash-latest"),
        )
        assertEquals(
            listOf("gemini-3.8-flash", "gemini-flash-latest"),
            CloudSpeechTranscriber.resolveGeminiCandidateModels(cachedModel = "gemini-3.8-flash"),
        )
    }

    @Test
    fun `resolveGeminiCandidateModels_候補にないモデルを覚えていても既定の順に戻す`() {
        assertEquals(
            CloudSpeechTranscriber.GEMINI_MODEL_CANDIDATES,
            CloudSpeechTranscriber.resolveGeminiCandidateModels(cachedModel = "gemini-2.5-flash"),
        )
    }

    // ---- URL・エンジン ID・要求と応答の本文 ----

    @Test
    fun `buildGeminiGenerateContentUrl_呼び出し先とモデル名からURLを組み立てる`() {
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent",
            CloudSpeechTranscriber.buildGeminiGenerateContentUrl(GeminiEndpoint.AI_STUDIO, "gemini-3.8-flash"),
        )
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-flash-latest:generateContent",
            CloudSpeechTranscriber.buildGeminiGenerateContentUrl(GeminiEndpoint.AI_STUDIO, "gemini-flash-latest"),
        )
        assertEquals(
            "https://aiplatform.googleapis.com/v1beta1/publishers/google/models/gemini-3.8-flash:generateContent",
            CloudSpeechTranscriber.buildGeminiGenerateContentUrl(GeminiEndpoint.VERTEX_EXPRESS, "gemini-3.8-flash"),
        )
    }

    @Test
    fun `buildGeminiGenerateContentUrl_モデル名の形でなければ例外`() {
        for (invalid in listOf("", "../gemini", "gemini/3", "Gemini-3.8-flash", "gemini 3", "gemini-3.8-flash:generateContent?key=x")) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                CloudSpeechTranscriber.buildGeminiGenerateContentUrl(GeminiEndpoint.AI_STUDIO, invalid)
            }
        }
    }

    @Test
    fun `geminiEngineIdFor_cloudとモデル名をつないだIDで以前と同じcloud-geminiで始まる`() {
        assertEquals("cloud-gemini-3.8-flash", CloudSpeechTranscriber.geminiEngineIdFor("gemini-3.8-flash").value)
        assertEquals("cloud-gemini-flash-latest", CloudSpeechTranscriber.geminiEngineIdFor("gemini-flash-latest").value)
        assertEquals("cloud-gemini-3.8-flash", CloudSpeechTranscriber.GEMINI_ENGINE_ID.value)
        for (model in CloudSpeechTranscriber.GEMINI_MODEL_CANDIDATES) {
            assertTrue(CloudSpeechTranscriber.geminiEngineIdFor(model).value.startsWith("cloud-gemini-"))
        }
    }

    @Test
    fun `buildGeminiGenerateContentRequest_thinkingConfigを付けるとthinkingLevelがlowになり外すと含まない`() {
        val withThinking = JSONObject(
            CloudSpeechTranscriber.buildGeminiGenerateContentRequest("QUJD", "prompt", includeThinkingConfig = true),
        )
        val content = withThinking.getJSONArray("contents").getJSONObject(0)
        assertEquals("user", content.getString("role"))
        val parts = content.getJSONArray("parts")
        assertEquals("audio/wav", parts.getJSONObject(0).getJSONObject("inlineData").getString("mimeType"))
        assertEquals("QUJD", parts.getJSONObject(0).getJSONObject("inlineData").getString("data"))
        assertEquals("prompt", parts.getJSONObject(1).getString("text"))
        val generationConfig = withThinking.getJSONObject("generationConfig")
        assertEquals(0.0, generationConfig.getDouble("temperature"), 0.0)
        assertEquals("low", generationConfig.getJSONObject("thinkingConfig").getString("thinkingLevel"))

        val withoutThinking = JSONObject(
            CloudSpeechTranscriber.buildGeminiGenerateContentRequest("QUJD", "prompt", includeThinkingConfig = false),
        )
        assertFalse(withoutThinking.getJSONObject("generationConfig").has("thinkingConfig"))
        assertEquals(0.0, withoutThinking.getJSONObject("generationConfig").getDouble("temperature"), 0.0)
    }

    @Test
    fun `extractGeminiResponseText_本文の部分をつなぎ思考の部分を除く`() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"考え中","thought":true},{"text":" Hello "},{"text":"world"}]}}]}"""
        assertEquals("Hello world", CloudSpeechTranscriber.extractGeminiResponseText(body))
    }

    @Test
    fun `extractGeminiResponseText_候補が無ければ空文字`() {
        assertEquals("", CloudSpeechTranscriber.extractGeminiResponseText("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertEquals("", CloudSpeechTranscriber.extractGeminiResponseText("""{"candidates":[]}"""))
    }

    // ---- エラーの本文の解析 ----

    @Test
    fun `parseGoogleApiError_オブジェクトの本文からcode_status_message_reasonを読む`() {
        val error = parseGoogleApiError(SERVICE_BLOCKED_403)
        assertNotNull(error)
        assertEquals(403, error?.code)
        assertEquals("PERMISSION_DENIED", error?.status)
        assertEquals(listOf("API_KEY_SERVICE_BLOCKED"), error?.reasons)
        assertEquals("API_KEY_SERVICE_BLOCKED", error?.primaryReason)
        assertTrue(error?.message.orEmpty().startsWith("Requests to this API aiplatform.googleapis.com"))
    }

    @Test
    fun `parseGoogleApiError_要素1つの配列の本文も読む`() {
        val error = parseGoogleApiError("[$SERVICE_BLOCKED_403]")
        assertEquals(403, error?.code)
        assertEquals("PERMISSION_DENIED", error?.status)
        assertEquals(listOf("API_KEY_SERVICE_BLOCKED"), error?.reasons)
    }

    @Test
    fun `parseGoogleApiError_402と404の本文を読む`() {
        val prepay = parseGoogleApiError(PREPAY_402)
        assertEquals(402, prepay?.code)
        assertEquals("RESOURCE_EXHAUSTED", prepay?.status)
        assertTrue(prepay?.message.orEmpty().startsWith("Your prepayment credits are depleted."))
        assertEquals(emptyList<String>(), prepay?.reasons)

        val notFound = parseGoogleApiError(NOT_FOUND_404)
        assertEquals(404, notFound?.code)
        assertEquals("NOT_FOUND", notFound?.status)
        assertNull(notFound?.primaryReason)
    }

    @Test
    fun `parseGoogleApiError_壊れたJSONや形の違う本文はnull`() {
        val invalidBodies = listOf(
            null,
            "",
            "   ",
            "<html><body>Not Found</body></html>",
            """{"error":""",
            """{"error":{"code":403,""",
            """{"message":"no error object"}""",
            """{"error":"string"}""",
            "[]",
            """["x"]""",
            "402",
        )
        for (body in invalidBodies) {
            assertNull("body=$body", parseGoogleApiError(body))
        }
    }

    @Test
    fun `parseGoogleApiError_大きすぎる本文と入れ子が深すぎる本文は解析しない`() {
        val huge = """{"error":{"code":400,"message":"${"a".repeat(CloudHttpClient.MAX_ERROR_BODY_BYTES)}","status":"INVALID_ARGUMENT"}}"""
        assertNull(parseGoogleApiError(huge))

        val deep = "[".repeat(5_000) + "]".repeat(5_000)
        assertTrue(exceedsJsonNestingDepth(deep, MAX_EXTERNAL_JSON_DEPTH))
        assertNull(parseGoogleApiError(deep))

        // 文字列の中の括弧は数えない
        val bracketsInString = """{"error":{"code":400,"message":"${"[".repeat(100)}","status":"INVALID_ARGUMENT"}}"""
        assertFalse(exceedsJsonNestingDepth(bracketsInString, MAX_EXTERNAL_JSON_DEPTH))
        assertEquals(400, parseGoogleApiError(bracketsInString)?.code)
    }

    @Test
    fun `parseGoogleApiError_英大文字と数字と下線以外を含むstatusやreasonは捨てる`() {
        val body = """
            {"error":{"code":400,"status":"bad status\n","message":"m","details":[
              {"reason":"API_KEY_INVALID"},{"reason":"x y"},{"reason":"API_KEY_INVALID"},{"reason":123},"not an object"
            ]}}
        """.trimIndent()
        val error = parseGoogleApiError(body)
        assertNotNull(error)
        assertNull(error?.status)
        assertEquals(listOf("API_KEY_INVALID"), error?.reasons)
    }

    @Test
    fun `GoogleApiError_toStringは外部の説明文を含まない`() {
        val error = GoogleApiError(code = 402, status = "RESOURCE_EXHAUSTED", message = "secret explanation")
        assertFalse(error.toString().contains("secret explanation"))
        assertTrue(error.toString().contains("RESOURCE_EXHAUSTED"))
    }

    @Test
    fun `readUtf8Bounded_上限を超える分は読まない`() {
        val input = ByteArrayInputStream(ByteArray(40_000) { 'a'.code.toByte() })
        val read = readUtf8Bounded(input, CloudHttpClient.MAX_ERROR_BODY_BYTES)
        assertEquals(CloudHttpClient.MAX_ERROR_BODY_BYTES, read.length)
        assertEquals(40_000 - CloudHttpClient.MAX_ERROR_BODY_BYTES, input.available())

        assertEquals("abc", readUtf8Bounded(ByteArrayInputStream("abc".toByteArray()), 16))
        assertEquals("", readUtf8Bounded(ByteArrayInputStream(ByteArray(0)), 16))
        assertEquals("日本語", readUtf8Bounded(ByteArrayInputStream("日本語".toByteArray(Charsets.UTF_8)), 1024))
    }

    // ---- 文言 ----

    @Test
    fun `formatCloudHttpErrorMessage_402は前払いクレジットの案内とGoogleの説明を出す`() {
        val message = CloudSpeechTranscriber.formatCloudHttpErrorMessage(
            providerLabel = "Gemini API",
            httpCode = 402,
            apiKey = AQ_KEY,
            isGemini = true,
            apiError = parseGoogleApiError(PREPAY_402),
        )
        assertTrue(
            message,
            message.startsWith(
                "Gemini API の前払いクレジットが残っていません（HTTP 402）。" +
                    "Google AI Studio（https://ai.studio/projects）でプロジェクトの課金設定とクレジットを確認してください。",
            ),
        )
        assertTrue(message, message.contains("（Google からの説明: Your prepayment credits are depleted."))
        assertTrue(message, message.endsWith("）"))
    }

    @Test
    fun `formatCloudHttpErrorMessage_RESOURCE_EXHAUSTEDでprepaymentを含めば402でなくても前払いの案内`() {
        val prepay = GoogleApiError(code = 429, status = "RESOURCE_EXHAUSTED", message = "Your prepayment credits are depleted.")
        val prepayMessage = CloudSpeechTranscriber.formatCloudHttpErrorMessage("Gemini API", 429, AIZA_KEY, true, prepay)
        assertTrue(prepayMessage, prepayMessage.startsWith("Gemini API の前払いクレジットが残っていません（HTTP 429）。"))

        val quota = GoogleApiError(code = 429, status = "RESOURCE_EXHAUSTED", message = "You exceeded your current quota.")
        val quotaMessage = CloudSpeechTranscriber.formatCloudHttpErrorMessage("Gemini API", 429, AIZA_KEY, true, quota)
        assertTrue(quotaMessage, quotaMessage.startsWith("Gemini API の利用上限（レート制限 / クォータ上限: HTTP 429）に達しました。"))
    }

    @Test
    fun `formatCloudHttpErrorMessage_404はモデルが見つからずアプリの更新を案内する`() {
        val message = CloudSpeechTranscriber.formatCloudHttpErrorMessage(
            providerLabel = "Gemini API",
            httpCode = 404,
            apiKey = AIZA_KEY,
            isGemini = true,
            apiError = parseGoogleApiError(NOT_FOUND_404),
        )
        assertTrue(
            message,
            message.startsWith(
                "Gemini API で文字起こしに使うモデルが見つかりません（HTTP 404）。モデルが提供終了になった可能性があります。アプリを更新してください。",
            ),
        )
        assertTrue(message, message.contains("（Google からの説明: This model models/gemini-3.8-flash is no longer available"))
    }

    @Test
    fun `formatCloudHttpErrorMessage_403のAPI_KEY_SERVICE_BLOCKEDはキーのAPIの制限を案内する`() {
        val message = CloudSpeechTranscriber.formatCloudHttpErrorMessage(
            providerLabel = "Gemini API",
            httpCode = 403,
            apiKey = AQ_KEY,
            isGemini = true,
            apiError = parseGoogleApiError("[$SERVICE_BLOCKED_403]"),
        )
        assertTrue(message, message.contains("HTTP 403、理由: API_KEY_SERVICE_BLOCKED"))
        assertTrue(
            message,
            message.contains("Cloud Console の認証情報でキーの『API の制限』に Generative Language API（Gemini API）が含まれているか確認してください。"),
        )
        assertTrue(message, message.contains("（Google からの説明: Requests to this API aiplatform.googleapis.com"))
    }

    @Test
    fun `formatCloudHttpErrorMessage_401は今の案内に理由を添える`() {
        val message = CloudSpeechTranscriber.formatCloudHttpErrorMessage(
            providerLabel = "Gemini API",
            httpCode = 401,
            apiKey = AQ_KEY,
            isGemini = true,
            apiError = parseGoogleApiError(UNAUTHENTICATED_401),
        )
        assertTrue(
            message,
            message.startsWith(
                "Gemini API の認証またはリクエストに失敗しました（HTTP 401、理由: ACCESS_TOKEN_TYPE_UNSUPPORTED）。" +
                    "設定画面の Gemini API キー（AIza… または AQ.…）に誤りがないか、",
            ),
        )
        assertTrue(message, message.endsWith("（Google からの説明: API key not valid. Please pass a valid API key.）"))
    }

    @Test
    fun `formatCloudHttpErrorMessage_AIzaとAQの両方を正常なキー形式として扱う`() {
        val aizaMsg = CloudSpeechTranscriber.formatCloudHttpErrorMessage(
            providerLabel = "Gemini API",
            httpCode = 401,
            apiKey = AIZA_KEY,
            isGemini = true,
        )
        val aqMsg = CloudSpeechTranscriber.formatCloudHttpErrorMessage(
            providerLabel = "Gemini API",
            httpCode = 401,
            apiKey = AQ_KEY,
            isGemini = true,
        )
        assertEquals(aizaMsg, aqMsg)
        assertTrue(aqMsg.contains("AIza… または AQ.…"))

        val invalidPrefixMsg = CloudSpeechTranscriber.formatCloudHttpErrorMessage(
            providerLabel = "Gemini API",
            httpCode = 401,
            apiKey = "sk-wrong-key",
            isGemini = true,
        )
        assertTrue(invalidPrefixMsg.contains("Gemini API キーの形式（AIza… または AQ.… で始まる文字列）ではありません"))
    }

    @Test
    fun `formatCloudHttpErrorMessage_OpenAIとGroqの402は残高と課金設定を案内する`() {
        for (provider in listOf("OpenAI API", "Groq API")) {
            val message = CloudSpeechTranscriber.formatCloudHttpErrorMessage(
                providerLabel = provider,
                httpCode = 402,
                apiKey = "sk-test",
                isGemini = false,
            )
            assertEquals(
                "$provider の残高（クレジット）が不足しているか、支払いの設定に問題があります（HTTP 402）。" +
                    "$provider の管理画面で課金設定と残高を確認してください。",
                message,
            )
        }
    }

    @Test
    fun `formatCloudHttpErrorMessage_Googleの説明は200文字までに縮めAPIキーを伏せる`() {
        val longMessage = "Bad key $AQ_LONG_KEY. " + "x".repeat(500)
        val message = CloudSpeechTranscriber.formatCloudHttpErrorMessage(
            providerLabel = "Gemini API",
            httpCode = 400,
            apiKey = AQ_LONG_KEY,
            isGemini = true,
            apiError = GoogleApiError(code = 400, status = "INVALID_ARGUMENT", message = longMessage, reasons = listOf("API_KEY_INVALID")),
        )
        assertFalse(message, message.contains(AQ_LONG_KEY))
        assertTrue(message, message.contains("理由: API_KEY_INVALID"))
        val detail = message.substringAfter("（Google からの説明: ").removeSuffix("）")
        assertTrue(detail, detail.startsWith("Bad key $REDACTED_API_KEY."))
        assertTrue(detail, detail.endsWith("…"))
        assertTrue(detail, detail.length <= MAX_PROVIDER_MESSAGE_CHARS + 1)
    }

    @Test
    fun `redactApiKeys_利用者のキーとキーらしい文字列を伏せる`() {
        val otherGoogleKey = "AIzaSy" + "X".repeat(33)
        val openAiKey = "sk-proj-" + "a".repeat(24)
        val groqKey = "gsk_" + "b".repeat(24)
        val text = "key=$AQ_KEY other=$otherGoogleKey openai=$openAiKey groq=$groqKey task-0123 risk-free"
        val redacted = redactApiKeys(text, AQ_KEY)
        for (secret in listOf(AQ_KEY, otherGoogleKey, openAiKey, groqKey)) {
            assertFalse(redacted, redacted.contains(secret))
        }
        assertTrue(redacted, redacted.startsWith("key=*** other=*** openai=*** groq=***"))
        assertTrue(redacted, redacted.endsWith("task-0123 risk-free"))
    }

    @Test
    fun `sanitizeProviderMessage_改行と制御文字をまとめキーを伏せてから縮める`() {
        assertEquals("line1 line2 tab", sanitizeProviderMessage("\n line1\n\nline2\t tab\u0000 ", AIZA_KEY))
        assertEquals("abcdef", sanitizeProviderMessage("abc\u202Edef", AIZA_KEY))

        // 縮める境界をまたぐ位置にキーがあっても、先に伏せるのでキーの一部が残らない
        val message = "a".repeat(190) + " " + AQ_KEY + " tail " + "b".repeat(50)
        val sanitized = sanitizeProviderMessage(message, AQ_KEY)
        assertFalse(sanitized, sanitized.contains("AQ.Ab8"))
        assertTrue(sanitized, sanitized.contains(REDACTED_API_KEY))
        assertTrue(sanitized, sanitized.endsWith("…"))
        assertTrue(sanitized, sanitized.length <= MAX_PROVIDER_MESSAGE_CHARS + 1)
    }

    // ---- 判定 ----

    @Test
    fun `isGeminiThinkingConfigRejected_400のINVALID_ARGUMENTで説明文にthinkingを含むときだけtrue`() {
        val reject: (Int, GoogleApiError?) -> Boolean = { code, error ->
            CloudSpeechTranscriber.isGeminiThinkingConfigRejected(code, error)
        }
        assertTrue(reject(400, parseGoogleApiError(THINKING_REJECTED_400)))
        assertTrue(
            reject(
                400,
                GoogleApiError(
                    400,
                    "INVALID_ARGUMENT",
                    "Thinking level MINIMAL is not supported for this model. Please retry with other thinking level.",
                ),
            ),
        )
        assertTrue(
            reject(
                400,
                GoogleApiError(400, "INVALID_ARGUMENT", "Invalid value at 'generation_config.thinking_config.thinking_level'"),
            ),
        )
        assertFalse(reject(400, GoogleApiError(400, "INVALID_ARGUMENT", "API key not valid.", listOf("API_KEY_INVALID"))))
        assertFalse(reject(400, null))
        assertFalse(reject(400, GoogleApiError(400, "FAILED_PRECONDITION", "thinking is not available")))
        assertFalse(reject(402, GoogleApiError(402, "RESOURCE_EXHAUSTED", "thinking")))
    }

    @Test
    fun `selectGeminiFailureToReport_VertexがAPI_KEY_SERVICE_BLOCKEDならAIStudioの失敗を選ぶ`() {
        val aiStudio = GeminiAttemptFailure(GeminiEndpoint.AI_STUDIO, "gemini-3.8-flash", 401, parseGoogleApiError(UNAUTHENTICATED_401))
        val vertex = GeminiAttemptFailure(GeminiEndpoint.VERTEX_EXPRESS, "gemini-3.8-flash", 403, parseGoogleApiError(SERVICE_BLOCKED_403))
        assertEquals(aiStudio, CloudSpeechTranscriber.selectGeminiFailureToReport(listOf(aiStudio, vertex)))
        assertEquals(aiStudio, CloudSpeechTranscriber.selectGeminiFailureToReport(listOf(vertex, aiStudio)))
    }

    @Test
    fun `selectGeminiFailureToReport_キーが通った失敗を優先し同じ優先度なら先に試したほう`() {
        val blocked = GeminiAttemptFailure(GeminiEndpoint.AI_STUDIO, "gemini-3.8-flash", 403, parseGoogleApiError(SERVICE_BLOCKED_403))
        val notFound = GeminiAttemptFailure(GeminiEndpoint.VERTEX_EXPRESS, "gemini-flash-latest", 404, parseGoogleApiError(NOT_FOUND_404))
        assertEquals(notFound, CloudSpeechTranscriber.selectGeminiFailureToReport(listOf(blocked, notFound)))

        val first401 = GeminiAttemptFailure(GeminiEndpoint.AI_STUDIO, "gemini-3.8-flash", 401, null)
        val second401 = GeminiAttemptFailure(GeminiEndpoint.VERTEX_EXPRESS, "gemini-3.8-flash", 401, null)
        assertEquals(first401, CloudSpeechTranscriber.selectGeminiFailureToReport(listOf(first401, second401)))
        assertNull(CloudSpeechTranscriber.selectGeminiFailureToReport(emptyList()))
    }

    // ---- 呼び出しの流れ（HTTP を差し替える） ----

    @Test
    fun `Gemini_キーをヘッダで送りthinkingLevelをlowにして主のモデルを呼ぶ`() = runTest {
        val http = FakeCloudHttpClient { CloudHttpResponse(200, geminiSuccess("hello")) }
        val result = transcriber(http, geminiApiKey = AIZA_KEY).transcribeGemini()

        assertEquals("hello", result?.text)
        assertNull(result?.errorMessage)
        assertEquals("cloud-gemini-3.8-flash", result?.engineId?.value)
        val request = http.requests.single()
        assertEquals(geminiUrl(GeminiEndpoint.AI_STUDIO, "gemini-3.8-flash"), request.url)
        assertEquals(AIZA_KEY, request.headers["x-goog-api-key"])
        assertFalse(request.url.contains(AIZA_KEY))
        val generationConfig = JSONObject(request.body).getJSONObject("generationConfig")
        assertEquals("low", generationConfig.getJSONObject("thinkingConfig").getString("thinkingLevel"))
    }

    @Test
    fun `Gemini_主のモデルが404なら予備のモデルで試し直し次のチャンクは覚えたモデルから始める`() = runTest {
        val http = FakeCloudHttpClient { request ->
            if (request.url.contains("/gemini-3.8-flash:")) {
                CloudHttpResponse(404, NOT_FOUND_404)
            } else {
                CloudHttpResponse(200, geminiSuccess("hello"))
            }
        }
        val transcriber = transcriber(http, geminiApiKey = AIZA_KEY)

        val first = transcriber.transcribeGemini()
        assertEquals("hello", first?.text)
        assertEquals("cloud-gemini-flash-latest", first?.engineId?.value)
        assertEquals(
            listOf(
                geminiUrl(GeminiEndpoint.AI_STUDIO, "gemini-3.8-flash"),
                geminiUrl(GeminiEndpoint.AI_STUDIO, "gemini-flash-latest"),
            ),
            http.requests.map { it.url },
        )

        http.requests.clear()
        val second = transcriber.transcribeGemini()
        assertEquals("hello", second?.text)
        assertEquals(listOf(geminiUrl(GeminiEndpoint.AI_STUDIO, "gemini-flash-latest")), http.requests.map { it.url })
    }

    @Test
    fun `Gemini_キーが変わったら覚えたモデルを使わない`() = runTest {
        val http = FakeCloudHttpClient { request ->
            if (request.url.contains("/gemini-3.8-flash:") && request.headers["x-goog-api-key"] == AIZA_KEY) {
                CloudHttpResponse(404, NOT_FOUND_404)
            } else {
                CloudHttpResponse(200, geminiSuccess("hello"))
            }
        }
        val settings = MutableSettingsRepository(UserSettings(geminiApiKey = AIZA_KEY))
        val transcriber = CloudSpeechTranscriber(settings, http)
        transcriber.transcribeGemini()

        settings.current = UserSettings(geminiApiKey = OTHER_AIZA_KEY)
        http.requests.clear()
        val result = transcriber.transcribeGemini()
        assertEquals("cloud-gemini-3.8-flash", result?.engineId?.value)
        assertEquals(listOf(geminiUrl(GeminiEndpoint.AI_STUDIO, "gemini-3.8-flash")), http.requests.map { it.url })
    }

    @Test
    fun `Gemini_thinkingの設定を400で拒否されたら外して1回だけ試し直し次からは付けない`() = runTest {
        val http = FakeCloudHttpClient { request ->
            if (request.body.contains("thinkingConfig")) {
                CloudHttpResponse(400, THINKING_REJECTED_400)
            } else {
                CloudHttpResponse(200, geminiSuccess("hi"))
            }
        }
        val transcriber = transcriber(http, geminiApiKey = AIZA_KEY)

        val first = transcriber.transcribeGemini()
        assertEquals("hi", first?.text)
        assertEquals(2, http.requests.size)
        assertEquals(http.requests[0].url, http.requests[1].url)
        assertTrue(http.requests[0].body.contains("thinkingConfig"))
        assertFalse(http.requests[1].body.contains("thinkingConfig"))

        http.requests.clear()
        val second = transcriber.transcribeGemini()
        assertEquals("hi", second?.text)
        assertFalse(http.requests.single().body.contains("thinkingConfig"))
    }

    @Test
    fun `Gemini_thinkingを外しても失敗したらもう一度は試さずその失敗を返す`() = runTest {
        val http = FakeCloudHttpClient { request ->
            if (request.body.contains("thinkingConfig")) {
                CloudHttpResponse(400, THINKING_REJECTED_400)
            } else {
                CloudHttpResponse(402, PREPAY_402)
            }
        }
        val result = transcriber(http, geminiApiKey = AIZA_KEY).transcribeGemini()

        assertEquals(2, http.requests.size)
        assertEquals("", result?.text)
        assertTrue(result?.errorMessage.orEmpty().startsWith("Gemini API の前払いクレジットが残っていません（HTTP 402）。"))
    }

    @Test
    fun `Gemini_402は前払いクレジットの文言を返しモデルも呼び出し先も切り替えない`() = runTest {
        val http = FakeCloudHttpClient { CloudHttpResponse(402, PREPAY_402) }
        val result = transcriber(http, geminiApiKey = AQ_KEY).transcribeGemini()

        assertEquals(listOf(geminiUrl(GeminiEndpoint.AI_STUDIO, "gemini-3.8-flash")), http.requests.map { it.url })
        assertEquals("", result?.text)
        assertEquals("cloud-gemini-3.8-flash", result?.engineId?.value)
        val message = result?.errorMessage.orEmpty()
        assertTrue(message, message.startsWith("Gemini API の前払いクレジットが残っていません（HTTP 402）。"))
        assertTrue(message, message.contains("（Google からの説明: Your prepayment credits are depleted."))
        assertFalse(message, message.contains(AQ_KEY))
        assertFalse(message, message.contains("試した呼び出し先"))
    }

    @Test
    fun `Gemini_すべての候補モデルで404ならモデルが見つからない文言を返す`() = runTest {
        val http = FakeCloudHttpClient { CloudHttpResponse(404, NOT_FOUND_404) }
        val result = transcriber(http, geminiApiKey = AIZA_KEY).transcribeGemini()

        assertEquals(
            listOf(
                geminiUrl(GeminiEndpoint.AI_STUDIO, "gemini-3.8-flash"),
                geminiUrl(GeminiEndpoint.AI_STUDIO, "gemini-flash-latest"),
            ),
            http.requests.map { it.url },
        )
        assertTrue(
            result?.errorMessage.orEmpty(),
            result?.errorMessage.orEmpty().startsWith("Gemini API で文字起こしに使うモデルが見つかりません（HTTP 404）。"),
        )
    }

    @Test
    fun `Gemini_AQキーで両方の呼び出し先が失敗しVertexがAPI_KEY_SERVICE_BLOCKEDならAIStudio側のエラーを出す`() = runTest {
        val http = FakeCloudHttpClient { request ->
            if (request.url.startsWith("https://generativelanguage.googleapis.com/")) {
                CloudHttpResponse(401, UNAUTHENTICATED_401)
            } else {
                CloudHttpResponse(403, "[$SERVICE_BLOCKED_403]")
            }
        }
        val result = transcriber(http, geminiApiKey = AQ_KEY).transcribeGemini()

        assertEquals(
            listOf(
                geminiUrl(GeminiEndpoint.AI_STUDIO, "gemini-3.8-flash"),
                geminiUrl(GeminiEndpoint.VERTEX_EXPRESS, "gemini-3.8-flash"),
            ),
            http.requests.map { it.url },
        )
        val message = result?.errorMessage.orEmpty()
        assertTrue(message, message.startsWith("Gemini API の認証またはリクエストに失敗しました（HTTP 401、理由: ACCESS_TOKEN_TYPE_UNSUPPORTED）。"))
        assertTrue(message, message.endsWith("（試した呼び出し先: AI Studio HTTP 401 / Vertex AI Express HTTP 403）"))
    }

    @Test
    fun `Gemini_AQキーでVertexが成功したら次のチャンクはVertexから試す`() = runTest {
        val http = FakeCloudHttpClient { request ->
            if (request.url.startsWith("https://generativelanguage.googleapis.com/")) {
                CloudHttpResponse(403, SERVICE_BLOCKED_403)
            } else {
                CloudHttpResponse(200, geminiSuccess("vertex"))
            }
        }
        val transcriber = transcriber(http, geminiApiKey = AQ_KEY)

        assertEquals("vertex", transcriber.transcribeGemini()?.text)
        http.requests.clear()
        assertEquals("vertex", transcriber.transcribeGemini()?.text)
        assertEquals(listOf(geminiUrl(GeminiEndpoint.VERTEX_EXPRESS, "gemini-3.8-flash")), http.requests.map { it.url })
    }

    @Test
    fun `Gemini_ログにはHTTPコードとstatusとreasonと試行の番号とモデル名だけを出す`() = runTest {
        val http = FakeCloudHttpClient { CloudHttpResponse(402, PREPAY_402) }
        transcriber(http, geminiApiKey = AQ_KEY).transcribeGemini()

        val logs = ShadowLog.getLogsForTag("CloudSpeechTranscriber").map { it.msg }
        val line = logs.single { it.contains("HTTP 402") }
        assertTrue(line, line.contains("status=RESOURCE_EXHAUSTED"))
        assertTrue(line, line.contains("attempt=1"))
        assertTrue(line, line.contains("model=gemini-3.8-flash"))
        for (log in logs) {
            assertFalse(log, log.contains(AQ_KEY))
            assertFalse(log, log.contains("prepayment", ignoreCase = true))
        }
    }

    @Test
    fun `OpenAI_402は残高と課金設定の文言を返す`() = runTest {
        val http = FakeCloudHttpClient {
            CloudHttpResponse(402, """{"error":{"message":"Insufficient balance","type":"insufficient_quota"}}""")
        }
        val transcriber = CloudSpeechTranscriber(MutableSettingsRepository(UserSettings(openAiApiKey = "sk-test-key-0001")), http)
        val result = transcriber.transcribePcm16Le(
            engine = SttEngine.OPENAI_API,
            pcm16LeBytes = PCM,
            sampleRateHz = 16_000,
            channelCount = 1,
            language = EN,
        )

        assertEquals("https://api.openai.com/v1/audio/transcriptions", http.requests.single().url)
        assertEquals("Bearer sk-test-key-0001", http.requests.single().headers["Authorization"])
        assertTrue(result?.errorMessage.orEmpty().startsWith("OpenAI API の残高（クレジット）が不足しているか"))
    }

    // ---- テスト用の部品 ----

    private fun transcriber(http: CloudHttpClient, geminiApiKey: String): CloudSpeechTranscriber = CloudSpeechTranscriber(MutableSettingsRepository(UserSettings(geminiApiKey = geminiApiKey)), http)

    private suspend fun CloudSpeechTranscriber.transcribeGemini(): OnDeviceChunkTranscriptionResult? = transcribePcm16Le(
        engine = SttEngine.GEMINI_API,
        pcm16LeBytes = PCM,
        sampleRateHz = 16_000,
        channelCount = 1,
        language = EN,
    )

    private fun geminiUrl(endpoint: GeminiEndpoint, model: String): String = CloudSpeechTranscriber.buildGeminiGenerateContentUrl(endpoint, model)

    private fun geminiSuccess(text: String): String = """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]},"finishReason":"STOP"}],"modelVersion":"test"}"""

    /** 送られた要求を記録し、[responder] の応答を返す [CloudHttpClient]。 */
    private class FakeCloudHttpClient(
        private val responder: (Request) -> CloudHttpResponse,
    ) : CloudHttpClient {
        data class Request(val url: String, val headers: Map<String, String>, val body: String)

        val requests = mutableListOf<Request>()

        override fun post(url: String, headers: Map<String, String>, body: ByteArray): CloudHttpResponse {
            val request = Request(url = url, headers = headers, body = String(body, Charsets.UTF_8))
            requests += request
            return responder(request)
        }
    }

    /** [current] の設定を返す [UserSettingsRepository]（書き込みは使わない）。 */
    private class MutableSettingsRepository(var current: UserSettings) : UserSettingsRepository {
        override val settings: Flow<UserSettings>
            get() = flowOf(current)

        override suspend fun setDefaultLanguagePair(pair: LanguagePair) = Unit

        override suspend fun setChineseScript(script: ChineseScript) = Unit

        override suspend fun setCantoneseScript(script: ChineseScript) = Unit

        override suspend fun setDefaultSpeed(speed: Float) = Unit

        override suspend fun setSeekBackMs(seekBackMs: Long) = Unit

        override suspend fun setSeekForwardMs(seekForwardMs: Long) = Unit
    }

    private companion object {
        /** テスト用の偽のキー（実在しない）。 */
        const val AIZA_KEY = "AIzaSyTestKey1234567890"
        const val OTHER_AIZA_KEY = "AIzaSyOtherTestKey0987654321"
        const val AQ_KEY = "AQ.Ab8RN6TestKey1234567890"
        const val AQ_LONG_KEY = "AQ.Ab8RN6TestKeyForRedaction_abcdefghijklmnopqrstuvwxyz0123456789"

        val EN = LanguageTag("en")
        val PCM = ByteArray(3_200)

        const val PREPAY_402 = """{"error":{"code":402,"message":"Your prepayment credits are depleted. Please go to AI Studio at https://ai.studio/projects to manage your project and billing. Learn more at https://ai.google.dev/gemini-api/docs/billing#prepay.","status":"RESOURCE_EXHAUSTED"}}"""

        const val NOT_FOUND_404 = """{"error":{"code":404,"message":"This model models/gemini-3.8-flash is no longer available to new users. Please update your code to use a newer model for the latest features and improvements.","status":"NOT_FOUND"}}"""

        const val UNAUTHENTICATED_401 = """{"error":{"code":401,"message":"API key not valid. Please pass a valid API key.","status":"UNAUTHENTICATED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"ACCESS_TOKEN_TYPE_UNSUPPORTED","domain":"googleapis.com","metadata":{"service":"generativelanguage.googleapis.com"}}]}}"""

        const val SERVICE_BLOCKED_403 = """{"error":{"code":403,"message":"Requests to this API aiplatform.googleapis.com method google.cloud.aiplatform.v1beta1.PredictionService.GenerateContent are blocked.","status":"PERMISSION_DENIED","details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_SERVICE_BLOCKED","domain":"googleapis.com","metadata":{"service":"aiplatform.googleapis.com"}}]}}"""

        const val THINKING_REJECTED_400 = """{"error":{"code":400,"message":"Thinking level is not supported for this model.","status":"INVALID_ARGUMENT"}}"""
    }
}
