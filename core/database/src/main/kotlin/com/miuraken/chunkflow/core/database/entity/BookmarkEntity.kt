package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.miuraken.chunkflow.core.model.Bookmark
import com.miuraken.chunkflow.core.model.MediaId

/**
 * 教材内のブックマーク（§6.4 `bookmark` テーブル）。
 *
 * チャンク ID ではなく時刻（`position_ms`）で持ち、`media_id` と `position_ms` の複合インデックスを張る。
 */
@Entity(
    tableName = "bookmark",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["media_id", "position_ms"]),
    ],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
    @ColumnInfo(name = "note")
    val note: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Long = 0L,
)

/** [BookmarkEntity] をドメインモデルの [Bookmark] に変換する。 */
fun BookmarkEntity.toExternalModel(): Bookmark = Bookmark(
    id = id,
    mediaId = MediaId(mediaId),
    positionMs = positionMs.coerceAtLeast(0L),
    note = note,
    createdAtEpochMs = createdAt,
)

/** ドメインモデルの [Bookmark] を [BookmarkEntity] に変換する。 */
fun Bookmark.toEntity(): BookmarkEntity = BookmarkEntity(
    id = id,
    mediaId = mediaId.value,
    positionMs = positionMs,
    note = note,
    createdAt = createdAtEpochMs,
)
