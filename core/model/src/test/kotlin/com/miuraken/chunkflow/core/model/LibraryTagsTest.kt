package com.miuraken.chunkflow.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibraryTagsTest {
    @Test
    fun `normalizeName_前後の空白を除き連続する空白を1つにまとめる`() {
        assertEquals("TOEIC Part 2", LibraryTags.normalizeName("  TOEIC \t\n Part   2 "))
    }

    @Test
    fun `normalizeName_空白だけや長すぎる名前や制御文字を含む名前はnull`() {
        assertNull(LibraryTags.normalizeName("   "))
        assertNull(LibraryTags.normalizeName("あ".repeat(LibraryTags.MAX_NAME_LENGTH + 1)))
        assertEquals("あ".repeat(LibraryTags.MAX_NAME_LENGTH), LibraryTags.normalizeName("あ".repeat(LibraryTags.MAX_NAME_LENGTH)))
        assertNull(LibraryTags.normalizeName("bad\u0000tag"))
        assertNull(LibraryTags.normalizeName("abc\u202Edef"))
    }

    @Test
    fun `normalizeName_NFCに正規化する`() {
        // 「が」を濁点の結合文字で書いた入力は、合成済みの 1 文字にそろう
        assertEquals("\u304C", LibraryTags.normalizeName("\u304B\u3099"))
    }

    @Test
    fun `key_大文字小文字と全角半角の違いだけのタグは同じキーになる`() {
        assertEquals(LibraryTags.key("TOEIC"), LibraryTags.key("toeic"))
        assertEquals(LibraryTags.key("TOEIC"), LibraryTags.key("\uFF34\uFF2F\uFF25\uFF29\uFF23"))
        assertEquals(LibraryTags.key("Русский"), LibraryTags.key("русский"))
    }

    @Test
    fun `normalizeNames_不正な名前を捨て同じキーの重複は最初の表記を残す`() {
        assertEquals(
            listOf("English", "シャドーイング"),
            LibraryTags.normalizeNames(listOf(" English ", "", "english", "シャドーイング", "ENGLISH")),
        )
    }
}
