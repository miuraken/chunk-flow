package com.miuraken.chunkflow.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibraryFolderColorsTest {
    private val ordinals = mapOf(
        LanguageTag("ko") to 0,
        LanguageTag("zh-cn") to 1,
        LanguageTag("en") to 9,
    )

    @Test
    fun `languageKey_カタログの言語コードにそろえ区別のない中国語はzh-cnにする`() {
        assertEquals("ko", LibraryFolderColors.languageKey(" KO "))
        assertEquals("zh-cn", LibraryFolderColors.languageKey("zh"))
        assertEquals("zh-tw", LibraryFolderColors.languageKey("zh-Hant"))
        assertNull(LibraryFolderColors.languageKey("  "))
    }

    @Test
    fun `isValidIndex_パレットの範囲だけを正しい番号とする`() {
        assertTrue(LibraryFolderColors.isValidIndex(0))
        assertTrue(LibraryFolderColors.isValidIndex(LibraryFolderColors.PALETTE_SIZE - 1))
        assertFalse(LibraryFolderColors.isValidIndex(-1))
        assertFalse(LibraryFolderColors.isValidIndex(LibraryFolderColors.PALETTE_SIZE))
    }

    @Test
    fun `resolveIndex_指定した色があればそれを使い不正な番号は無視する`() {
        assertEquals(5, LibraryFolderColors.resolveIndex(5, LanguageTag("ko"), ordinals))
        assertEquals(0, LibraryFolderColors.resolveIndex(LibraryFolderColors.PALETTE_SIZE, LanguageTag("ko"), ordinals))
    }

    @Test
    fun `resolveIndex_指定がなければ対象言語の順番の色を使い色数を超えたら繰り返す`() {
        assertEquals(0, LibraryFolderColors.resolveIndex(null, LanguageTag("ko"), ordinals))
        assertEquals(1, LibraryFolderColors.resolveIndex(null, LanguageTag("zh"), ordinals))
        assertEquals(9 % LibraryFolderColors.PALETTE_SIZE, LibraryFolderColors.resolveIndex(null, LanguageTag("en"), ordinals))
    }

    @Test
    fun `resolveIndex_順番のない言語は次に割り当てられる色にし言語が不明ならnull`() {
        assertEquals(10 % LibraryFolderColors.PALETTE_SIZE, LibraryFolderColors.resolveIndex(null, LanguageTag("fr"), ordinals))
        assertEquals(0, LibraryFolderColors.resolveIndex(null, LanguageTag("fr"), emptyMap()))
        assertNull(LibraryFolderColors.resolveIndex(null, null, ordinals))
        assertNull(LibraryFolderColors.resolveIndex(null, LanguageTag(""), ordinals))
    }

    @Test
    fun `orderedPaletteIndices_折りたたみ範囲外の色を選んだら先頭に移動し既定の色も1行目に残す`() {
        val normal = LibraryFolderColors.orderedPaletteIndices(selectedIndex = 2, defaultIndex = 0)
        assertEquals((0 until LibraryFolderColors.PALETTE_SIZE).toList(), normal)

        val hiddenSelected = LibraryFolderColors.orderedPaletteIndices(selectedIndex = 12, defaultIndex = 0)
        assertEquals(12, hiddenSelected.first())
        assertTrue(0 in hiddenSelected.take(LibraryFolderColors.COLLAPSED_SWATCH_COUNT))
        assertEquals(LibraryFolderColors.PALETTE_SIZE, hiddenSelected.distinct().size)

        val hiddenBoth = LibraryFolderColors.orderedPaletteIndices(selectedIndex = 14, defaultIndex = 11)
        assertEquals(14, hiddenBoth.first())
        assertTrue(11 in hiddenBoth.take(LibraryFolderColors.COLLAPSED_SWATCH_COUNT))
    }
}
