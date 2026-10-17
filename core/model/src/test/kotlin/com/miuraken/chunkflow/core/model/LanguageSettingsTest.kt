package com.miuraken.chunkflow.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LanguageSettingsTest {
    @Test
    fun `normalizeCode_区別のない中国語は簡体中国語として扱う`() {
        assertEquals("zh-cn", LanguageSettings.normalizeCode("zh"))
        assertEquals("zh-cn", LanguageSettings.normalizeCode("zh-Hans-CN"))
        assertEquals("zh-tw", LanguageSettings.normalizeCode("zh-Hant-TW"))
        assertEquals("ja", LanguageSettings.normalizeCode("ja-JP"))
        assertEquals("", LanguageSettings.normalizeCode("  "))
    }

    @Test
    fun `SELECTABLE_LANGUAGES_固定の13言語が言語コード順で先頭に並ぶ`() {
        val codes = LanguageSettings.SELECTABLE_LANGUAGES.map { it.code }

        assertEquals(LanguageSettings.PINNED_LANGUAGE_CODES, codes.take(13))
        assertEquals(LanguageSettings.PINNED_LANGUAGE_CODES.sorted(), LanguageSettings.PINNED_LANGUAGE_CODES)
    }

    @Test
    fun `SELECTABLE_LANGUAGES_残りは文字起こしできる言語だけを言語コード順に並べる`() {
        val others = LanguageSettings.SELECTABLE_LANGUAGES.drop(13)

        assertEquals(others.map { it.code }.sorted(), others.map { it.code })
        assertTrue(others.all { LanguageSettings.isSttCapable(it) })
        assertTrue(others.none { it.code in LanguageSettings.PINNED_LANGUAGE_CODES })
    }

    @Test
    fun `SELECTABLE_LANGUAGES_区別のない中国語zhは出さず重複もない`() {
        val codes = LanguageSettings.SELECTABLE_LANGUAGES.map { it.code }

        assertFalse("zh" in codes)
        assertTrue("zh-cn" in codes)
        assertTrue("zh-tw" in codes)
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test
    fun `isPinned_固定の言語かどうかをコードをそろえてから判定する`() {
        assertTrue(LanguageSettings.isPinned("ja-JP"))
        assertTrue(LanguageSettings.isPinned("zh"))
        assertFalse(LanguageSettings.isPinned("th"))
    }

    @Test
    fun `defaultEnabledLanguages_端末の言語と英語を端末の順で返す`() {
        assertEquals(
            listOf(LanguageTag("ja"), LanguageTag("ko"), LanguageTag("en")),
            LanguageSettings.defaultEnabledLanguages(listOf("ja-JP", "ko-KR", "en-US")).toList(),
        )
    }

    @Test
    fun `defaultEnabledLanguages_端末の言語がわからなくても英語は有効`() {
        assertEquals(setOf(LanguageTag("en")), LanguageSettings.defaultEnabledLanguages(emptyList()))
        assertEquals(setOf(LanguageTag("en")), LanguageSettings.defaultEnabledLanguages(listOf("und", "")))
    }
}
