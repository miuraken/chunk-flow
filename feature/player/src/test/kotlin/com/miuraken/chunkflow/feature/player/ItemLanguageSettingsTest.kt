package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ItemLanguageSettingsTest {
    private val koJa = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja"))

    @Test
    fun `normalizeItemLanguageCode_小文字にそろえ中国語の表記ゆれをまとめる`() {
        assertEquals("ko", normalizeItemLanguageCode(" KO "))
        assertEquals("zh-cn", normalizeItemLanguageCode("zh_Hans"))
        assertEquals("zh-cn", normalizeItemLanguageCode("zh"))
        assertEquals("zh-tw", normalizeItemLanguageCode("zh-HK"))
        assertEquals("pt-br", normalizeItemLanguageCode("pt_BR"))
    }

    @Test
    fun `buildItemLanguageOptions_一覧にない言語を追加しなしを先頭に置ける`() {
        val options = buildItemLanguageOptions(currentCode = "pt-BR", noneLabel = "なし")
        assertEquals("" to "なし", options.first().code to options.first().label)
        assertEquals(1, options.count { it.code == "pt-br" })
        assertEquals(ITEM_LANGUAGE_OPTIONS.size + 2, options.size)
        assertEquals(ITEM_LANGUAGE_OPTIONS, buildItemLanguageOptions(currentCode = "ko"))
    }

    @Test
    fun `languagePairWithTarget_学習言語を変えベース言語と同じなら入れ替える`() {
        assertEquals(LanguagePair(LanguageTag("en"), LanguageTag("ja")), languagePairWithTarget(koJa, "en"))
        // ベース言語の ja を学習言語に選ぶと入れ替える
        assertEquals(LanguagePair(LanguageTag("ja"), LanguageTag("ko")), languagePairWithTarget(koJa, "ja"))
        // 変わらない・空は null
        assertNull(languagePairWithTarget(koJa, "KO"))
        assertNull(languagePairWithTarget(koJa, ""))
        // 言語ペアがまだないときも作れる
        assertEquals(LanguagePair(LanguageTag("ko"), null), languagePairWithTarget(null, "ko"))
    }

    @Test
    fun `languagePairWithBase_ベース言語を変え学習言語と同じなら入れ替えなしにもできる`() {
        assertEquals(LanguagePair(LanguageTag("ko"), LanguageTag("en")), languagePairWithBase(koJa, "en"))
        assertEquals(LanguagePair(LanguageTag("ko"), null), languagePairWithBase(koJa, ""))
        // 学習言語の ko をベース言語に選ぶと入れ替える
        assertEquals(LanguagePair(LanguageTag("ja"), LanguageTag("ko")), languagePairWithBase(koJa, "ko"))
        // ベース言語が「なし」のときは入れ替えられないので null
        assertNull(languagePairWithBase(LanguagePair(LanguageTag("ko"), null), "ko"))
        assertNull(languagePairWithBase(koJa, "ja"))
        assertNull(languagePairWithBase(null, "ja"))
    }
}
