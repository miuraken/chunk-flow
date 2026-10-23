package com.miuraken.chunkflow.navigation

import androidx.navigation3.runtime.NavKey

// 画面遷移のバックスタック（rememberNavBackStack の NavBackStack）を変える操作。
//
// 「戻る」は、画面の再構成より前に 2 回届くことがある。
// - 上部の戻る矢印のダブルタップ。
// - ポップのアニメーション（NavDisplay の既定は 700ms のクロスフェード）の間に、消えていく画面の
//   戻る矢印をタップする（ポップ中は消えていく画面が z 順で上にあり、タップを受け取る）。
// - 同じフレームでシステムの「戻る」が 2 回届く。NavDisplay の戻るハンドラーの有効・無効と、
//   onBack を呼ぶ処理（何件戻すかの計算）は SideEffect で更新されるため、2 回目は古い状態のまま
//   onBack を呼ぶ。
// 無条件に末尾を消すと、ルートの LibraryKey まで消えて、次の再構成で NavDisplay が
// IllegalArgumentException（NavDisplay backstack cannot be empty）で落ちる。
// そのため、ポップは次の 2 つを守る。
// - ルート（一番下）の 1 件は消さない。
// - 画面の「戻る」ボタンは、その画面のエントリーが一番上にあるときだけ消す。

/**
 * システムの「戻る」（NavDisplay の onBack）で、一番上のエントリーを 1 件取り除く。
 *
 * ルートの 1 件だけのときは何もしない。ルートでは NavDisplay の戻るハンドラーが無効になり、
 * 「戻る」はシステムに任されてアプリが閉じる（バックグラウンドに回る）ので、ここで止まるのは
 * ハンドラーの状態が古いまま 2 回目の「戻る」が届いたときだけ。
 *
 * NavDisplay は 1 回の「戻る」で onBack を複数回呼ぶことがある（Scene の previousEntries が
 * 2 件以上前を指すとき）。そのため、一番上が何かは確かめず、呼ばれるたびに 1 件ずつ取り除く。
 *
 * @return 取り除いたら true、ルートの 1 件だけで何もしなかったら false。
 */
internal fun <T : Any> MutableList<T>.popUnlessRoot(): Boolean {
    if (size <= 1) return false
    removeAt(lastIndex)
    return true
}

/**
 * 画面の「戻る」ボタンで、[key] のエントリーが一番上にあるときだけ取り除く。
 *
 * ポップ済みで消えていく途中の画面や、同じフレームで届いた 2 回目のタップでは、一番上が別の
 * エントリーになっているので何もしない。[key] がルートの 1 件のときも消さない。
 *
 * @param key 「戻る」ボタンを押された画面のエントリーのキー。
 * @return 取り除いたら true。
 */
internal fun <T : Any> MutableList<T>.popIfTop(key: T): Boolean {
    if (lastOrNull() != key) return false
    return popUnlessRoot()
}

/**
 * プレイヤーを開く。
 *
 * 一番上がすでに [PlayerKey] のとき（2 ペイン表示中に別の教材を選んだときや、同じフレームで
 * 教材が 2 回タップされたとき）は、詳細ペインの PlayerKey が重複して積まれないよう差し替える。
 * どちらの場合もバックスタックの件数は減らない。
 *
 * @param key 開くプレイヤーのキー。
 */
internal fun MutableList<NavKey>.openPlayer(key: PlayerKey) {
    if (lastOrNull() is PlayerKey) {
        set(lastIndex, key)
    } else {
        add(key)
    }
}
