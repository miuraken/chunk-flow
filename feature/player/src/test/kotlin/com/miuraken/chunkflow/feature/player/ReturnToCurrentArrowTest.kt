package com.miuraken.chunkflow.feature.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 「現在位置へ戻る」の矢印の向き（[isReturnToCurrentArrowDownward]）のテスト。 */
class ReturnToCurrentArrowTest {

    @Test
    fun `現在位置が表示より上にあるとき_上向き`() {
        // 現在のチャンクは 10 番目。自動追従なら先頭は 8 番目だが、20 番目まで下にスクロールしている
        assertFalse(isReturnToCurrentArrowDownward(firstVisibleItemIndex = 20, firstVisibleItemScrollOffset = 0, activeDisplayIndex = 10))
    }

    @Test
    fun `現在位置が表示より下にあるとき_下向き`() {
        // 現在のチャンクは 30 番目。先頭が 5 番目まで上にスクロールしている
        assertTrue(isReturnToCurrentArrowDownward(firstVisibleItemIndex = 5, firstVisibleItemScrollOffset = 0, activeDisplayIndex = 30))
    }

    @Test
    fun `自動追従の位置から少しだけ下にずれているとき_上向き`() {
        assertFalse(isReturnToCurrentArrowDownward(firstVisibleItemIndex = 8, firstVisibleItemScrollOffset = 40, activeDisplayIndex = 10))
        assertFalse(isReturnToCurrentArrowDownward(firstVisibleItemIndex = 8, firstVisibleItemScrollOffset = 0, activeDisplayIndex = 10))
    }

    @Test
    fun `先頭付近のチャンクでも負の位置にならない`() {
        // 現在のチャンクが 1 番目のとき、自動追従の先頭は 0 番目
        assertFalse(isReturnToCurrentArrowDownward(firstVisibleItemIndex = 3, firstVisibleItemScrollOffset = 0, activeDisplayIndex = 1))
        assertFalse(isReturnToCurrentArrowDownward(firstVisibleItemIndex = 0, firstVisibleItemScrollOffset = 0, activeDisplayIndex = 1))
    }
}
