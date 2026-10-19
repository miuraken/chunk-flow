package com.miuraken.chunkflow.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

// 画面遷移のキー。feature 同士は依存しないため、キーと遷移の組み立ては :app に置く（§5.2）。
// プロセスの終了後に復元できるよう、キーは @Serializable にする。

/** ライブラリ画面。 */
@Serializable
data object LibraryKey : NavKey

/**
 * プレイヤー画面。
 *
 * @property mediaId 開く教材の [com.miuraken.chunkflow.core.model.MediaId] の値。
 * @property initialChunkId 開いた直後に移動するチャンクの [com.miuraken.chunkflow.core.model.ChunkId] の値（指定時は一時停止状態でそのチャンクへ移動する）。
 */
@Serializable
data class PlayerKey(
    val mediaId: String,
    val initialChunkId: Long? = null,
) : NavKey

/**
 * 設定画面。
 *
 * @property initialTab 初期表示するタブのインデックス（0 = 再生, 3 = 言語）。
 */
@Serializable
data class SettingsKey(val initialTab: Int = 0) : NavKey
