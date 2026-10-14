package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.miuraken.chunkflow.core.database.entity.AnalysisStateEntity
import kotlinx.coroutines.flow.Flow

/** `analysis_state` テーブルの DAO（§6.4、§7.3.4）。 */
@Dao
interface AnalysisStateDao {
    /** 指定した教材のすべての解析段階の状態を購読する。 */
    @Query("SELECT * FROM analysis_state WHERE media_id = :mediaId")
    fun observeByMediaId(mediaId: String): Flow<List<AnalysisStateEntity>>

    /** 指定した教材のすべての解析段階の状態を取得する。 */
    @Query("SELECT * FROM analysis_state WHERE media_id = :mediaId")
    suspend fun getByMediaId(mediaId: String): List<AnalysisStateEntity>

    /** 解析段階の状態を挿入または更新する。 */
    @Upsert
    suspend fun upsert(state: AnalysisStateEntity)

    /** 複数の解析段階の状態をまとめて挿入または更新する。 */
    @Upsert
    suspend fun upsertAll(states: List<AnalysisStateEntity>)

    /** 指定した教材の解析状態を削除する。 */
    @Query("DELETE FROM analysis_state WHERE media_id = :mediaId")
    suspend fun deleteByMediaId(mediaId: String)
}
