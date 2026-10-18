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

    /** 指定した教材が `media_item` にあるか。 */
    @Query("SELECT EXISTS(SELECT 1 FROM media_item WHERE id = :mediaId)")
    suspend fun mediaItemExists(mediaId: String): Boolean

    /**
     * 教材が `media_item` にあるときだけ、状態を挿入または更新する。
     *
     * `item_state` は `media_item` に外部キー（ON DELETE CASCADE）で結び付いているので、削除済みの教材の行を書くと
     * 外部キー違反になる。プレイヤーは再生中の教材が削除されたあとも位置や設定を保存しようとするため、削除済みなら何もしない。
     *
     * @return 書き込んだら true、教材がなければ false。
     */
    @Transaction
    suspend fun upsertIfMediaExists(state: ItemStateEntity): Boolean {
        if (!mediaItemExists(state.mediaId)) return false
        upsert(state)
        return true
    }

    /** チャンクの出どころ（`IMPORTED` / `AUTO`）を更新する（F5.4）。 */
    @Query("UPDATE item_state SET chunk_source = :chunkSource, updated_at = :updatedAt WHERE media_id = :mediaId")
    suspend fun updateChunkSource(mediaId: String, chunkSource: String, updatedAt: Long): Int

    /**
     * 教材の再生位置と速度を更新する（§7.2.5）。
     *
     * まだ `item_state` の行がない場合は既定値で行を作成する。教材が削除済みなら何もしない（[upsertIfMediaExists]）。
     *
     * @return 書き込んだら true、教材がなければ false。
     */
    @Transaction
    suspend fun updatePlaybackPosition(
        mediaId: String,
        positionMs: Long,
        speed: Float,
        updatedAt: Long,
    ): Boolean {
        val existing = getByMediaId(mediaId)
        // 行があれば教材もある（削除すると行も消える）。行がないときだけ、教材が削除済みでないかを確かめる
        if (existing == null && !mediaItemExists(mediaId)) return false
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
        return true
    }

    /**
     * 教材の聴取完了フラグ（`is_listened`）と最終アクセス日時を更新する。
     *
     * まだ `item_state` の行がない場合は既定値で行を作成する。教材が削除済みなら何もしない（[upsertIfMediaExists]）。
     *
     * @return 書き込んだら true、教材がなければ false。
     */
    @Transaction
    suspend fun updateListenedStatus(
        mediaId: String,
        isListened: Boolean,
        updatedAt: Long,
    ): Boolean {
        val existing = getByMediaId(mediaId)
        if (existing == null && !mediaItemExists(mediaId)) return false
        val updated = existing?.copy(
            isListened = isListened,
            updatedAt = updatedAt,
        ) ?: ItemStateEntity(
            mediaId = mediaId,
            isListened = isListened,
            updatedAt = updatedAt,
        )
        upsert(updated)
        return true
    }

    /**
     * 教材のお気に入り登録フラグ（`is_favorite`）を切り替える。
     *
     * まだ `item_state` の行がない場合は既定値で行を作成し `isFavorite = true` にする。教材が削除済みなら何もしない。
     *
     * @return 書き込んだら true、教材がなければ false。
     */
    @Transaction
    suspend fun toggleFavorite(mediaId: String): Boolean {
        val existing = getByMediaId(mediaId)
        if (existing == null && !mediaItemExists(mediaId)) return false
        val nextFavorite = !(existing?.isFavorite ?: false)
        val updated = existing?.copy(
            isFavorite = nextFavorite,
        ) ?: ItemStateEntity(
            mediaId = mediaId,
            isFavorite = nextFavorite,
        )
        upsert(updated)
        return true
    }

    /** 現在 `item_state` に登録されている最大の `queued_at` を返す。 */
    @Query("SELECT COALESCE(MAX(queued_at), 0) FROM item_state")
    suspend fun getMaxQueuedAt(): Long

    /**
     * 教材の「次に再生するキュー」への登録状態（`queued_at`）を切り替える。
     *
     * 未登録（`queued_at == 0`）なら [queuedAt]（既存の最大値 + 1 以上）を設定し、登録済みなら `0L` に戻す。
     * まだ `item_state` の行がない場合は既定値で行を作成する。教材が削除済みなら何もしない。
     *
     * @return 書き込んだら true、教材がなければ false。
     */
    @Transaction
    suspend fun toggleQueue(mediaId: String, queuedAt: Long): Boolean {
        val existing = getByMediaId(mediaId)
        if (existing == null && !mediaItemExists(mediaId)) return false
        val nextQueuedAt = if ((existing?.queuedAt ?: 0L) > 0L) {
            0L
        } else {
            maxOf(queuedAt.coerceAtLeast(1L), getMaxQueuedAt() + 1L)
        }
        val updated = existing?.copy(
            queuedAt = nextQueuedAt,
        ) ?: ItemStateEntity(
            mediaId = mediaId,
            queuedAt = nextQueuedAt,
        )
        upsert(updated)
        return true
    }

    /**
     * 教材を開いたときのアクセス回数（`access_count`）を 1 増やし、最終アクセス日時（`updated_at`）を更新する。
     *
     * まだ `item_state` の行がない場合は既定値で行を作成する。教材が削除済みなら何もしない。
     *
     * @return 書き込んだら true、教材がなければ false。
     */
    @Transaction
    suspend fun recordAccess(mediaId: String, accessedAt: Long): Boolean {
        val existing = getByMediaId(mediaId)
        if (existing == null && !mediaItemExists(mediaId)) return false
        val nextCount = (existing?.accessCount ?: 0).coerceAtLeast(0) + 1
        val nextUpdatedAt = maxOf(existing?.updatedAt ?: 0L, accessedAt)
        val updated = existing?.copy(
            accessCount = nextCount,
            updatedAt = nextUpdatedAt,
        ) ?: ItemStateEntity(
            mediaId = mediaId,
            accessCount = nextCount,
            updatedAt = nextUpdatedAt,
        )
        upsert(updated)
        return true
    }

    /**
     * 教材ごとの自動チャンク分割パラメータの上書き（`custom_segmentation`）を更新する。
     *
     * `null` を指定すると「全体設定に従う」に戻る。
     * まだ `item_state` の行がない場合は既定値で行を作成する。教材が削除済みなら何もしない。
     *
     * @return 書き込んだら true、教材がなければ false。
     */
    @Transaction
    suspend fun updateCustomSegmentation(
        mediaId: String,
        customSegmentation: String?,
        updatedAt: Long,
    ): Boolean {
        val existing = getByMediaId(mediaId)
        if (existing == null && !mediaItemExists(mediaId)) return false
        val nextUpdatedAt = maxOf(existing?.updatedAt ?: 0L, updatedAt)
        val updated = existing?.copy(
            customSegmentation = customSegmentation,
            updatedAt = nextUpdatedAt,
        ) ?: ItemStateEntity(
            mediaId = mediaId,
            customSegmentation = customSegmentation,
            updatedAt = nextUpdatedAt,
        )
        upsert(updated)
        return true
    }
}
