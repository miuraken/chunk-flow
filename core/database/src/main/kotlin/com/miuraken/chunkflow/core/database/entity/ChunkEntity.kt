package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.MediaId

/**
 * 発話単位のチャンク（§6.4 `chunk` テーブル）。
 *
 * `media_id` と `start_ms` の複合インデックスを張り、教材ごとの時刻順の取得を高速にする。
 */
@Entity(
    tableName = "chunk",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["media_id", "start_ms"]),
    ],
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "media_id")
    val mediaId: String,
    @ColumnInfo(name = "start_ms")
    val startMs: Long,
    @ColumnInfo(name = "end_ms")
    val endMs: Long,
    @ColumnInfo(name = "origin")
    val origin: String,
    @ColumnInfo(name = "algorithm_version")
    val algorithmVersion: Int = 1,
    @ColumnInfo(name = "is_skipped", defaultValue = "0")
    val isSkipped: Boolean = false,
)

/** [ChunkEntity] をドメインモデルの [Chunk] に変換する。 */
fun ChunkEntity.toExternalModel(): Chunk = Chunk(
    id = ChunkId(id),
    startMs = startMs,
    endMs = endMs,
    origin = ChunkOrigin.entries.firstOrNull { it.name == origin } ?: ChunkOrigin.AUTO,
    isSkipped = isSkipped,
)

/** ドメインモデルの [Chunk] を [ChunkEntity] に変換する。 */
fun Chunk.toEntity(
    mediaId: MediaId,
    algorithmVersion: Int = 1,
): ChunkEntity = ChunkEntity(
    id = id.value,
    mediaId = mediaId.value,
    startMs = startMs,
    endMs = endMs,
    origin = origin.name,
    algorithmVersion = algorithmVersion,
    isSkipped = isSkipped,
)
