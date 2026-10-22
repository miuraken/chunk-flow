package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * ファイル（教材）とタグの対応（`media_item_tag` テーブル）。
 *
 * `media_item` または `tag` が削除されたときは `ON DELETE CASCADE` で行も削除される。
 */
@Entity(
    tableName = "media_item_tag",
    primaryKeys = ["media_id", "tag_id"],
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tag_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["tag_id"]),
    ],
)
data class MediaItemTagEntity(
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    @ColumnInfo(name = "tag_id")
    val tagId: Long,
)

/**
 * ファイルとタグ名の対応（[com.miuraken.chunkflow.core.database.dao.TagDao.observeItemTagNames] の 1 行）。
 *
 * @property mediaId ファイル（教材）の ID。
 * @property tagName タグの表示名。
 */
data class MediaItemTagNameRow(
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    @ColumnInfo(name = "name")
    val tagName: String,
)
