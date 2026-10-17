package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.miuraken.chunkflow.core.database.entity.ItemSttOverrideEntity
import kotlinx.coroutines.flow.Flow

/** `item_stt_override` テーブルの DAO（教材ごとの文字起こしエンジンの上書き。ADR-0011 §2.3）。 */
@Dao
interface ItemSttOverrideDao {
    /** 指定した教材の上書きを購読する。行がなければ `null`。 */
    @Query("SELECT * FROM item_stt_override WHERE media_id = :mediaId")
    fun observeByMediaId(mediaId: String): Flow<ItemSttOverrideEntity?>

    /** 指定した教材の上書きを取得する。行がなければ `null`。 */
    @Query("SELECT * FROM item_stt_override WHERE media_id = :mediaId")
    suspend fun getByMediaId(mediaId: String): ItemSttOverrideEntity?

    /** 上書きを挿入または更新する。教材がないと外部キー違反になるので、通常は [setIfMediaExists] を使う。 */
    @Upsert
    suspend fun upsert(entity: ItemSttOverrideEntity)

    /** 指定した教材の上書きを消す。消した行の数を返す。 */
    @Query("DELETE FROM item_stt_override WHERE media_id = :mediaId")
    suspend fun deleteByMediaId(mediaId: String): Int

    /** 指定した教材が `media_item` にあるか。 */
    @Query("SELECT EXISTS(SELECT 1 FROM media_item WHERE id = :mediaId)")
    suspend fun mediaItemExists(mediaId: String): Boolean

    /**
     * 教材が `media_item` にあるときだけ、上書きを保存する（教材の確認と書き込みを 1 つのトランザクションで行う）。
     *
     * 両方のエンジンが `NULL`（上書きなし）なら、行を消す。削除済みの教材に書き込むと外部キー違反になるので、
     * 教材がなければ何もしない。
     *
     * @return 保存した（または行を消した）ら true、教材がなければ false。
     */
    @Transaction
    suspend fun setIfMediaExists(entity: ItemSttOverrideEntity): Boolean {
        if (!mediaItemExists(entity.mediaId)) return false
        if (entity.targetEngine == null && entity.baseEngine == null) {
            deleteByMediaId(entity.mediaId)
        } else {
            upsert(entity)
        }
        return true
    }
}
