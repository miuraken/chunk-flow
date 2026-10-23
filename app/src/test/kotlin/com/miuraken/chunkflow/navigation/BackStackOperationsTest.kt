package com.miuraken.chunkflow.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 画面遷移のバックスタックの操作（[popUnlessRoot]、[popIfTop]、[openPlayer]）のテスト。
 *
 * 「戻る」が再構成の前に 2 回届いたとき（戻る矢印のダブルタップ、ポップ中に消えていく画面のタップ、
 * 同じフレームでのシステムの「戻る」2 回）に、ルートの [LibraryKey] まで消えて NavDisplay が
 * IllegalArgumentException（NavDisplay backstack cannot be empty）で落ちないことを確かめる。
 */
class BackStackOperationsTest {

    private val settings = SettingsKey()
    private val playerA = PlayerKey(mediaId = "media-a")
    private val playerB = PlayerKey(mediaId = "media-b")

    @Test
    fun `popUnlessRoot_ルートの上に画面があるとき_一番上を1件だけ取り除く`() {
        val backStack = mutableListOf<NavKey>(LibraryKey, playerA, settings)

        assertTrue(backStack.popUnlessRoot())
        assertEquals(listOf<NavKey>(LibraryKey, playerA), backStack)
    }

    @Test
    fun `popUnlessRoot_ルートの1件だけのとき_何もしない`() {
        val backStack = mutableListOf<NavKey>(LibraryKey)

        assertFalse(backStack.popUnlessRoot())
        assertEquals(listOf<NavKey>(LibraryKey), backStack)
    }

    @Test
    fun `popUnlessRoot_空のとき_例外を投げずに何もしない`() {
        val backStack = mutableListOf<NavKey>()

        assertFalse(backStack.popUnlessRoot())
        assertTrue(backStack.isEmpty())
    }

    @Test
    fun `popUnlessRoot_同じフレームでシステムの戻るが2回届いたとき_ルートを残す`() {
        // 設定 → システムの「戻る」×2。2 回目は、戻るハンドラーの古い状態のまま onBack が呼ばれる
        val backStack = mutableListOf<NavKey>(LibraryKey, settings)

        assertTrue(backStack.popUnlessRoot())
        assertFalse(backStack.popUnlessRoot())
        assertEquals(listOf<NavKey>(LibraryKey), backStack)
    }

    @Test
    fun `popUnlessRoot_NavDisplayが続けて何度も呼んだとき_呼ばれた回数だけ戻りルートで止まる`() {
        // NavDisplay は 1 回の「戻る」で onBack を複数回呼ぶことがあり、古い状態のまま呼ぶこともある
        val backStack = mutableListOf<NavKey>(LibraryKey, playerA, settings)

        assertTrue(backStack.popUnlessRoot())
        assertEquals(listOf<NavKey>(LibraryKey, playerA), backStack)
        repeat(5) { backStack.popUnlessRoot() }
        assertEquals(listOf<NavKey>(LibraryKey), backStack)
    }

    @Test
    fun `popIfTop_自分のエントリーが一番上のとき_取り除く`() {
        val backStack = mutableListOf<NavKey>(LibraryKey, playerA)

        assertTrue(backStack.popIfTop(playerA))
        assertEquals(listOf<NavKey>(LibraryKey), backStack)
    }

    @Test
    fun `popIfTop_戻る矢印をダブルタップしたとき_2回目は何もせずルートを残す`() {
        val backStack = mutableListOf<NavKey>(LibraryKey, settings)

        assertTrue(backStack.popIfTop(settings))
        assertFalse(backStack.popIfTop(settings))
        assertEquals(listOf<NavKey>(LibraryKey), backStack)
    }

    @Test
    fun `popIfTop_プレイヤーの上の設定で戻る矢印をダブルタップしたとき_プレイヤーを消さない`() {
        // 2 回目のタップは、ポップのアニメーション中に消えていく設定画面に届く
        val backStack = mutableListOf<NavKey>(LibraryKey, playerA, settings)

        assertTrue(backStack.popIfTop(settings))
        assertFalse(backStack.popIfTop(settings))
        assertEquals(listOf<NavKey>(LibraryKey, playerA), backStack)
    }

    @Test
    fun `popIfTop_システムの戻るのあとに消えていく画面の戻る矢印をタップしたとき_何もしない`() {
        val backStack = mutableListOf<NavKey>(LibraryKey, settings)

        assertTrue(backStack.popUnlessRoot())
        assertFalse(backStack.popIfTop(settings))
        assertEquals(listOf<NavKey>(LibraryKey), backStack)
    }

    @Test
    fun `popIfTop_自分のエントリーが一番上でないとき_何もしない`() {
        val backStack = mutableListOf<NavKey>(LibraryKey, playerA, settings)

        assertFalse(backStack.popIfTop(playerA))
        assertEquals(listOf<NavKey>(LibraryKey, playerA, settings), backStack)
    }

    @Test
    fun `popIfTop_差し替えられる前のプレイヤーの戻るのとき_新しいプレイヤーを消さない`() {
        // 2 ペイン表示中に別の教材を選ぶと、一番上の PlayerKey が差し替えられる
        val backStack = mutableListOf<NavKey>(LibraryKey, playerA)
        backStack.openPlayer(playerB)

        assertFalse(backStack.popIfTop(playerA))
        assertEquals(listOf<NavKey>(LibraryKey, playerB), backStack)
    }

    @Test
    fun `popIfTop_キーがルートの1件のとき_消さない`() {
        val backStack = mutableListOf<NavKey>(LibraryKey)

        assertFalse(backStack.popIfTop(LibraryKey))
        assertEquals(listOf<NavKey>(LibraryKey), backStack)
    }

    @Test
    fun `popIfTop_内容が同じ別のインスタンスのキーのとき_同じエントリーとして取り除く`() {
        // キーは data class の等しさで比べる（同じ内容のキーが積み直されても同じ画面として扱う）
        val backStack = mutableListOf<NavKey>(LibraryKey, SettingsKey(initialTab = 3))

        assertTrue(backStack.popIfTop(SettingsKey(initialTab = 3)))
        assertEquals(listOf<NavKey>(LibraryKey), backStack)
    }

    @Test
    fun `openPlayer_一番上がプレイヤーでないとき_積む`() {
        val backStack = mutableListOf<NavKey>(LibraryKey)

        backStack.openPlayer(playerA)

        assertEquals(listOf<NavKey>(LibraryKey, playerA), backStack)
    }

    @Test
    fun `openPlayer_一番上がプレイヤーのとき_差し替えて件数を変えない`() {
        val backStack = mutableListOf<NavKey>(LibraryKey, playerA)

        backStack.openPlayer(playerB)

        assertEquals(listOf<NavKey>(LibraryKey, playerB), backStack)
    }

    @Test
    fun `openPlayer_同じフレームで2回開いたとき_プレイヤーは1件だけ積まれる`() {
        val backStack = mutableListOf<NavKey>(LibraryKey)

        backStack.openPlayer(playerA)
        backStack.openPlayer(playerA)

        assertEquals(listOf<NavKey>(LibraryKey, playerA), backStack)
    }

    @Test
    fun `openPlayer_一番上が設定のとき_その上にプレイヤーを積む`() {
        val backStack = mutableListOf<NavKey>(LibraryKey, playerA, settings)

        backStack.openPlayer(playerB)

        assertEquals(listOf<NavKey>(LibraryKey, playerA, settings, playerB), backStack)
    }

    @Test
    fun `NavBackStack_画面の戻るとシステムの戻るが続けて届いたとき_ルートのLibraryKeyが残る`() {
        // 実際に使う NavBackStack（SnapshotStateList）でも同じように動くことを確かめる
        val backStack = NavBackStack<NavKey>(LibraryKey, playerA)

        assertTrue(backStack.popIfTop(playerA))
        assertFalse(backStack.popUnlessRoot())
        assertFalse(backStack.popIfTop(playerA))
        assertEquals(listOf<NavKey>(LibraryKey), backStack.toList())
    }
}
