package com.miuraken.chunkflow.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 画面遷移のバックスタックの操作（[popUnlessRoot]、[popIfTop]、[pushIfNotTop]、[openPlayer]）のテスト。
 *
 * 「戻る」が再構成の前に 2 回届いたとき（戻る矢印のダブルタップ、ポップ中に消えていく画面のタップ、
 * 同じフレームでのシステムの「戻る」2 回）に、ルートの [LibraryKey] まで消えて NavDisplay が
 * IllegalArgumentException（NavDisplay backstack cannot be empty）で落ちないことを確かめる。
 * あわせて、画面を開くボタンのダブルタップで同じ画面が 2 枚積まれないことを確かめる。
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
    fun `pushIfNotTop_一番上が別のキーのとき_積む`() {
        val backStack = mutableListOf<NavKey>(LibraryKey)

        assertTrue(backStack.pushIfNotTop(settings))
        assertEquals(listOf<NavKey>(LibraryKey, settings), backStack)
    }

    @Test
    fun `pushIfNotTop_歯車をダブルタップしたとき_設定は1件だけ積まれる`() {
        val backStack = mutableListOf<NavKey>(LibraryKey)

        assertTrue(backStack.pushIfNotTop(SettingsKey()))
        assertFalse(backStack.pushIfNotTop(SettingsKey()))
        assertEquals(listOf<NavKey>(LibraryKey, SettingsKey()), backStack)
    }

    @Test
    fun `pushIfNotTop_ダブルタップで開いたあと戻る1回で_元の画面に戻る`() {
        // プレイヤーの再生設定ボタンをダブルタップ → 設定の戻る矢印 1 回でプレイヤーに戻る
        val backStack = mutableListOf<NavKey>(LibraryKey, playerA)
        backStack.pushIfNotTop(SettingsKey())
        backStack.pushIfNotTop(SettingsKey())

        assertTrue(backStack.popIfTop(SettingsKey()))
        assertEquals(listOf<NavKey>(LibraryKey, playerA), backStack)
    }

    @Test
    fun `pushIfNotTop_内容が違うキーのとき_積む`() {
        // 再生のタブの設定の上に、言語のタブの設定を開く
        val backStack = mutableListOf<NavKey>(LibraryKey, SettingsKey(initialTab = 0))

        assertTrue(backStack.pushIfNotTop(SettingsKey(initialTab = 3)))
        assertEquals(
            listOf<NavKey>(LibraryKey, SettingsKey(initialTab = 0), SettingsKey(initialTab = 3)),
            backStack,
        )
    }

    @Test
    fun `pushIfNotTop_文字起こしエクスポートを2回開いたとき_1件だけ積まれる`() {
        // TranscriptExportKey は教材 ID のリストの等しさで比べる（別のインスタンスでも同じキー）
        val backStack = mutableListOf<NavKey>(LibraryKey)
        val first = TranscriptExportKey(mediaIds = listOf("media-a", "media-b"))
        val second = TranscriptExportKey(mediaIds = listOf("media-a", "media-b"))

        assertTrue(backStack.pushIfNotTop(first))
        assertFalse(backStack.pushIfNotTop(second))
        assertEquals(listOf<NavKey>(LibraryKey, first), backStack)
    }

    @Test
    fun `pushIfNotTop_同じキーが一番上ではなく下にあるとき_積む`() {
        val backStack = mutableListOf<NavKey>(LibraryKey, settings, playerA)

        assertTrue(backStack.pushIfNotTop(settings))
        assertEquals(listOf<NavKey>(LibraryKey, settings, playerA, settings), backStack)
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

    @Test
    fun `NavBackStack_歯車をダブルタップしたとき_設定は1件だけ積まれる`() {
        val backStack = NavBackStack<NavKey>(LibraryKey)

        assertTrue(backStack.pushIfNotTop(SettingsKey()))
        assertFalse(backStack.pushIfNotTop(SettingsKey()))
        assertEquals(listOf<NavKey>(LibraryKey, SettingsKey()), backStack.toList())
    }
}
