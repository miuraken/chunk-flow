package com.miuraken.chunkflow.core.media

import com.miuraken.chunkflow.core.model.LanguageTag
import kotlin.test.Test
import kotlin.test.assertEquals

/** Moonshine のデコードのトークン数の上限（[MoonshineOnnxTranscriber.maxDecodeTokens]）のテスト。 */
class MoonshineDecodeLimitTest {

    @Test
    fun `maxDecodeTokens_英語は1秒あたり6点5トークン`() {
        assertEquals(13, MoonshineOnnxTranscriber.maxDecodeTokens(2.0, LanguageTag("en")))
        assertEquals(13, MoonshineOnnxTranscriber.maxDecodeTokens(2.0, LanguageTag("en-US")))
    }

    @Test
    fun `maxDecodeTokens_英語以外は1秒あたり13トークンで文の途中で切れない`() {
        // 実機のベンチマークで「같이 가고 싶어요.」（1.7 秒）が 11 トークンで切れていた
        assertEquals(22, MoonshineOnnxTranscriber.maxDecodeTokens(1.7, LanguageTag("ko")))
        assertEquals(26, MoonshineOnnxTranscriber.maxDecodeTokens(2.0, LanguageTag("ja")))
        assertEquals(26, MoonshineOnnxTranscriber.maxDecodeTokens(2.0, LanguageTag("zh-CN")))
    }

    @Test
    fun `maxDecodeTokens_短すぎ長すぎは下限と上限に収める`() {
        assertEquals(8, MoonshineOnnxTranscriber.maxDecodeTokens(0.2, LanguageTag("ja")))
        assertEquals(200, MoonshineOnnxTranscriber.maxDecodeTokens(30.0, LanguageTag("ja")))
    }
}
