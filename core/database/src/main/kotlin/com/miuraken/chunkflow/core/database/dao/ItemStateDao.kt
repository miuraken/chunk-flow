package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import kotlinx.coroutines.flow.Flow

/** `item_state` テーブルの DAO（§6.4、F2.1、F2.2）。 */
@Dao
interface ItemStateDao {
    /** すべての教材の状態を購読する。 */
    @Query("SELECT * FROM item_state")
    fun observeAll(): Flow<List<ItemStateEntity>>

    /** 指定した教材の状態を購読する。 */
    @Query("SELECT * FROM item_state WHERE media_id = :mediaId")
    fun observeByMediaId(mediaId: String): Flow<ItemStateEntity?>

    /** 指定した教材の状態を取得する。 */
    @Query("SELECT * FROM item_state WHERE media_id = :mediaId")
    suspend fun getByMediaId(mediaId: String): ItemStateEntity?

    /** 教材の状態を挿入または更新する。 */
    @Upsert
    suspend fun upsert(state: ItemStateEntity)

    /** チャンクの出どころ（`IMPORTED` / `AUTO`）を更新する（F5.4）。 */
    @Query("UPDATE item_state SET chunk_source = :chunkSource, updated_at = :updatedAt WHERE media_id = :mediaId")
    suspend fun updateChunkSource(mediaId: String, chunkSource: String, updatedAt: Long): Int

    /**
     * 教材の再生位置と速度を更新する（§7.2.5）。
     *
     * まだ `item_state` の行がない場合は既定値で行を作成する。
     */
    @Transaction
    suspend fun updatePlaybackPosition(
        mediaId: String,
        positionMs: Long,
        speed: Float,
        updatedAt: Long,
    ) {
        val existing = getByMediaId(mediaId)
        val nextMaxPositionMs = maxOf(
            existing?.maxPositionMs ?: 0L,
            existing?.positionMs ?: 0L,
            positionMs,
        )
        val updated = existing?.copy(
            positionMs = positionMs,
            maxPositionMs = nextMaxPositionMs,
            speed = speed,
            updatedAt = updatedAt,
        ) ?: ItemStateEntity(
            mediaId = mediaId,
            positionMs = positionMs,
            maxPositionMs = nextMaxPositionMs,
            speed = speed,
            updatedAt = updatedAt,
        )
        upsert(updated)
    }

    /**
     * 教材の聴取完了フラグ（`is_listened`）と最終アクセス日時を更新する。
     *
     * まだ `item_state` の行がない場合は既定値で行を作成する。
     */
    @Transaction
    suspend fun updateListenedStatus(
        mediaId: String,
        isListened: Boolean,
        updatedAt: Long,
    ) {
        val existing = getByMediaId(mediaId)
        val updated = existing?.copy(
            isListened = isListened,
            updatedAt = updatedAt,
        ) ?: ItemStateEntity(
            mediaId = mediaId,
            isListened = isListened,
            updatedAt = updatedAt,
        )
        upsert(updated)
    }
}
