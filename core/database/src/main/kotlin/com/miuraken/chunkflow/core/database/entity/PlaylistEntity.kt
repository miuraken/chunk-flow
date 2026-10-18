package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.miuraken.chunkflow.core.model.LibraryPlaylist
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PlaylistId

/**
 * プレイリストのメタデータ（`library_playlist` テーブル）。
 *
 * ユーザーが作成したプレイリストのほか、組み込みのスマートプレイリスト（「最近開いたファイル」等）の
 * 並び順もこのテーブルに記録する。
 */
@Entity(tableName = "library_playlist")
data class PlaylistEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "is_builtin", defaultValue = "0")
    val isBuiltIn: Boolean = false,
    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0L,
)

/** [PlaylistEntity] と属する教材 ID リストからドメインモデルの [LibraryPlaylist] に変換する。 */
fun PlaylistEntity.toExternalModel(itemIds: List<MediaId> = emptyList()): LibraryPlaylist = LibraryPlaylist(
    id = PlaylistId(id),
    name = name,
    isBuiltIn = isBuiltIn,
    sortOrder = sortOrder,
    createdAtEpochMs = createdAt,
    itemIds = itemIds,
)

/** ドメインモデルの [LibraryPlaylist] を [PlaylistEntity] に変換する。 */
fun LibraryPlaylist.toEntity(): PlaylistEntity = PlaylistEntity(
    id = id.value,
    name = name,
    isBuiltIn = isBuiltIn,
    sortOrder = sortOrder,
    createdAt = createdAtEpochMs,
)
