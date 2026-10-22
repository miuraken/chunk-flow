package com.miuraken.chunkflow.core.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSpeechTranscriberTest {

    @Test
    fun `resolveGeminiCandidateUrls_AIzaキーはAIStudioエンドポイントのみを返す`() {
        val urls = CloudSpeechTranscriber.resolveGeminiCandidateUrls("AIzaSyTestKey1234567890")
        assertEquals(
            listOf(CloudSpeechTranscriber.GEMINI_GENERATE_CONTENT_URL),
            urls,
        )
    }

    @Test
    fun `resolveGeminiCandidateUrls_AQキーはAIStudioとVertexAIExpressの両エンドポイントを返しキャッシュ順を優先する`() {
        val urlsDefault = CloudSpeechTranscriber.resolveGeminiCandidateUrls("AQ.Ab8RN6TestKey1234567890")
        assertEquals(
            listOf(
                CloudSpeechTranscriber.GEMINI_GENERATE_CONTENT_URL,
                CloudSpeechTranscriber.GEMINI_VERTEX_EXPRESS_GENERATE_CONTENT_URL,
            ),
            urlsDefault,
        )

        val urlsVertexCached = CloudSpeechTranscriber.resolveGeminiCandidateUrls(
            apiKey = "AQ.Ab8RN6TestKey1234567890",
            cachedUrl = CloudSpeechTranscriber.GEMINI_VERTEX_EXPRESS_GENERATE_CONTENT_URL,
        )
        assertEquals(
            listOf(
                CloudSpeechTranscriber.GEMINI_VERTEX_EXPRESS_GENERATE_CONTENT_URL,
                CloudSpeechTranscriber.GEMINI_GENERATE_CONTENT_URL,
            ),
            urlsVertexCached,
        )
    }

    @Test
    fun `formatCloudHttpErrorMessage_AIzaとAQの両方を正常なキー形式として扱う`() {
        val aizaMsg = CloudSpeechTranscriber.formatCloudHttpErrorMessage(
            providerLabel = "Gemini API",
            httpCode = 401,
            apiKey = "AIzaSyTestKey1234567890",
            isGemini = true,
        )
        val aqMsg = CloudSpeechTranscriber.formatCloudHttpErrorMessage(
            providerLabel = "Gemini API",
            httpCode = 401,
            apiKey = "AQ.Ab8RN6TestKey1234567890",
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
}
