package com.miuraken.chunkflow.core.media

import com.google.mlkit.nl.translate.TranslateLanguage
import com.miuraken.chunkflow.core.model.LanguageTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnDeviceTextTranslatorTest {

    @Test
    fun `normalizeMlKitTranslateLanguage_主要言語と中国語系と広東語を正規化する`() {
        assertEquals(TranslateLanguage.ENGLISH, normalizeMlKitTranslateLanguage(LanguageTag("en")))
        assertEquals(TranslateLanguage.ENGLISH, normalizeMlKitTranslateLanguage(LanguageTag("en-US")))
        assertEquals(TranslateLanguage.JAPANESE, normalizeMlKitTranslateLanguage(LanguageTag("ja-JP")))
        assertEquals(TranslateLanguage.CHINESE, normalizeMlKitTranslateLanguage(LanguageTag("zh")))
        assertEquals(TranslateLanguage.CHINESE, normalizeMlKitTranslateLanguage(LanguageTag("cmn")))
        assertEquals(TranslateLanguage.CHINESE, normalizeMlKitTranslateLanguage(LanguageTag("zh-Hans")))
        assertEquals(TranslateLanguage.CHINESE, normalizeMlKitTranslateLanguage(LanguageTag("zh-Hant")))
        assertEquals(TranslateLanguage.CHINESE, normalizeMlKitTranslateLanguage(LanguageTag("zh-CN")))
        assertEquals(TranslateLanguage.CHINESE, normalizeMlKitTranslateLanguage(LanguageTag("zh-TW")))
        assertNull(normalizeMlKitTranslateLanguage(LanguageTag("yue")))
        assertNull(normalizeMlKitTranslateLanguage(LanguageTag("zh-yue")))
        assertNull(normalizeMlKitTranslateLanguage(LanguageTag("zh-HK")))
        assertNull(normalizeMlKitTranslateLanguage(LanguageTag("und")))
        assertNull(normalizeMlKitTranslateLanguage(LanguageTag("")))
        assertNull(normalizeMlKitTranslateLanguage(null))
    }

    @Test
    fun `isSupported_対応言語の異なるペアのみtrueを返し同一言語や広東語はfalseを返す`() {
        val translator = MlKitOnDeviceTextTranslator()
        assertTrue(translator.isSupported(LanguageTag("en"), LanguageTag("ja")))
        assertTrue(translator.isSupported(LanguageTag("cmn"), LanguageTag("ja")))
        assertTrue(translator.isSupported(LanguageTag("zh-Hant"), LanguageTag("en-US")))
        assertFalse(translator.isSupported(LanguageTag("en"), LanguageTag("en-US")))
        assertFalse(translator.isSupported(LanguageTag("cmn"), LanguageTag("zh-CN")))
        assertFalse(translator.isSupported(LanguageTag("yue"), LanguageTag("ja")))
        assertFalse(translator.isSupported(LanguageTag("zh-HK"), LanguageTag("ja")))
        assertFalse(translator.isSupported(LanguageTag("und"), LanguageTag("ja")))
        assertFalse(translator.isSupported(null, LanguageTag("ja")))
    }
}
