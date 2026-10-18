package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * プレイリスト内の教材アイテムと並び順（`library_playlist_item` テーブル）。
 *
 * `media_item` が削除されたときは `ON DELETE CASCADE` で自動的にプレイリストからも除外される。
 * `library_playlist` が削除されたときも `ON DELETE CASCADE` でアイテム行が削除される。
 */
@Entity(
    tableName = "library_playlist_item",
    primaryKeys = ["playlist_id", "media_id"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlist_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["playlist_id", "sort_order"]),
        Index(value = ["media_id"]),
    ],
)
data class PlaylistItemEntity(
    @ColumnInfo(name = "playlist_id")
    val playlistId: String,
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "added_at", defaultValue = "0")
    val addedAt: Long = 0L,
)
