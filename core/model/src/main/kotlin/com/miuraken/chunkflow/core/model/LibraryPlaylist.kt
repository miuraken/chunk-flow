package com.miuraken.chunkflow.core.model

/**
 * プレイリストの識別子。
 *
 * 組み込みのスマートプレイリスト（「最近開いたファイル」「よくアクセスするファイル」「お気に入り」）と、
 * ユーザーが自由に作成したプレイリストの両方を一意に表す。
 */
@JvmInline
value class PlaylistId(val value: String) {
    init {
        require(value.isNotBlank()) { "PlaylistId must not be blank" }
    }

    companion object {
        /** 組み込みプレイリスト: 最近開いたファイル。 */
        val RECENTLY_OPENED: PlaylistId = PlaylistId("builtin:recently_opened")

        /** 組み込みプレイリスト: よくアクセスするファイル。 */
        val FREQUENTLY_ACCESSED: PlaylistId = PlaylistId("builtin:frequently_accessed")

        /** 組み込みプレイリスト: お気に入り。 */
        val FAVORITES: PlaylistId = PlaylistId("builtin:favorites")

        /** すべての組み込みプレイリスト ID（既定の表示順）。 */
        val BUILT_IN_IDS: List<PlaylistId> = listOf(
            RECENTLY_OPENED,
            FREQUENTLY_ACCESSED,
            FAVORITES,
        )
    }
}

/**
 * ライブラリの「再生リスト」タブに表示されるプレイリスト。
 *
 * ユーザーが作成したプレイリストのほか、組み込みのスマートプレイリストの並び順やファイル並び順の上書きも保持する。
 *
 * @property id プレイリストの一意な識別子。
 * @property name プレイリストの表示名（組み込みプレイリストの場合は空文字列でもよく、UI 側でローカライズ名を使う）。
 * @property isBuiltIn 組み込みスマートプレイリスト（「最近開いたファイル」「よくアクセスするファイル」「お気に入り」）なら true。
 * @property sortOrder 「再生リスト」タブ内での並び順（0 以上の整数、小さいほど上）。
 * @property createdAtEpochMs 作成日時（UNIX エポックからのミリ秒）。
 * @property itemIds プレイリスト内の教材 ID の並び順リスト。
 */
data class LibraryPlaylist(
    val id: PlaylistId,
    val name: String,
    val isBuiltIn: Boolean = false,
    val sortOrder: Int = 0,
    val createdAtEpochMs: Long = 0L,
    val itemIds: List<MediaId> = emptyList(),
)
