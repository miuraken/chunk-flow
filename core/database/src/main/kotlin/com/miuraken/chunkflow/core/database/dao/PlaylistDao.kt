package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.miuraken.chunkflow.core.database.entity.PlaylistEntity
import com.miuraken.chunkflow.core.database.entity.PlaylistItemEntity
import kotlinx.coroutines.flow.Flow

/** `library_playlist` および `library_playlist_item` テーブルの DAO。 */
@Dao
interface PlaylistDao {
    /** すべてのプレイリストを `sort_order` 昇順・作成日時昇順で購読する。 */
    @Query("SELECT * FROM library_playlist ORDER BY sort_order ASC, created_at ASC, id ASC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    /** すべてのプレイリストアイテムを `playlist_id`・`sort_order` 昇順で購読する。 */
    @Query("SELECT * FROM library_playlist_item ORDER BY playlist_id ASC, sort_order ASC, added_at ASC")
    fun observeAllPlaylistItems(): Flow<List<PlaylistItemEntity>>

    /** すべてのプレイリストを `sort_order` 昇順で取得する。 */
    @Query("SELECT * FROM library_playlist ORDER BY sort_order ASC, created_at ASC, id ASC")
    suspend fun getPlaylists(): List<PlaylistEntity>

    /** 指定した ID のプレイリストを取得する。 */
    @Query("SELECT * FROM library_playlist WHERE id = :playlistId")
    suspend fun getPlaylistById(playlistId: String): PlaylistEntity?

    /** 指定したプレイリストのアイテムを `sort_order` 昇順で取得する。 */
    @Query("SELECT * FROM library_playlist_item WHERE playlist_id = :playlistId ORDER BY sort_order ASC, added_at ASC")
    suspend fun getItemsByPlaylistId(playlistId: String): List<PlaylistItemEntity>

    /** プレイリストを挿入または更新する。 */
    @Upsert
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    /** 複数のプレイリストを挿入または更新する。 */
    @Upsert
    suspend fun upsertPlaylists(playlists: List<PlaylistEntity>)

    /** プレイリストアイテムを挿入または更新する。 */
    @Upsert
    suspend fun upsertPlaylistItems(items: List<PlaylistItemEntity>)

    /** 指定したプレイリストを削除する（組み込みプレイリストは削除しない）。 */
    @Query("DELETE FROM library_playlist WHERE id = :playlistId AND is_builtin = 0")
    suspend fun deleteUserPlaylist(playlistId: String): Int

    /** 指定したプレイリストから特定の教材を除外する。 */
    @Query("DELETE FROM library_playlist_item WHERE playlist_id = :playlistId AND media_id = :mediaId")
    suspend fun deletePlaylistItem(playlistId: String, mediaId: String): Int

    /** 指定したプレイリストのアイテムをすべて削除する。 */
    @Query("DELETE FROM library_playlist_item WHERE playlist_id = :playlistId")
    suspend fun deleteAllItemsInPlaylist(playlistId: String)

    /** 指定した教材が `media_item` にあるか。 */
    @Query("SELECT EXISTS(SELECT 1 FROM media_item WHERE id = :mediaId)")
    suspend fun mediaItemExists(mediaId: String): Boolean

    /**
     * プレイリストに教材を追加する（すでに含まれている教材は順序を維持し、新規分だけを末尾に追加する）。
     */
    @Transaction
    suspend fun addItemsToPlaylist(
        playlist: PlaylistEntity,
        mediaIds: List<String>,
        addedAt: Long,
    ) {
        if (getPlaylistById(playlist.id) == null) {
            upsertPlaylist(playlist)
        }
        val existing = getItemsByPlaylistId(playlist.id)
        val existingIds = existing.map { it.mediaId }.toSet()
        var nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val newItems = mediaIds
            .distinct()
            .filter { it !in existingIds && mediaItemExists(it) }
            .map { mediaId ->
                PlaylistItemEntity(
                    playlistId = playlist.id,
                    mediaId = mediaId,
                    sortOrder = nextOrder++,
                    addedAt = addedAt,
                )
            }
        if (newItems.isNotEmpty()) {
            upsertPlaylistItems(newItems)
        }
    }

    /**
     * プレイリスト内の教材の並び順を更新する。
     *
     * 組み込みプレイリストのアイテム並び替えにも対応できるよう、プレイリスト行が未作成なら [playlistIfMissing] を先に挿入する。
     */
    @Transaction
    suspend fun replacePlaylistItemsOrder(
        playlistId: String,
        orderedMediaIds: List<String>,
        updatedAt: Long,
        playlistIfMissing: PlaylistEntity? = null,
    ) {
        if (getPlaylistById(playlistId) == null) {
            if (playlistIfMissing != null) {
                upsertPlaylist(playlistIfMissing)
            } else {
                return
            }
        }
        val existingByMediaId = getItemsByPlaylistId(playlistId).associateBy { it.mediaId }
        deleteAllItemsInPlaylist(playlistId)
        val validItems = orderedMediaIds
            .distinct()
            .filter { mediaItemExists(it) }
            .mapIndexed { index, mediaId ->
                PlaylistItemEntity(
                    playlistId = playlistId,
                    mediaId = mediaId,
                    sortOrder = index,
                    addedAt = existingByMediaId[mediaId]?.addedAt ?: updatedAt,
                )
            }
        if (validItems.isNotEmpty()) {
            upsertPlaylistItems(validItems)
        }
    }
}
