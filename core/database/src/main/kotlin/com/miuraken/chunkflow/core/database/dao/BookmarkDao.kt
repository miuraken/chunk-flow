package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.miuraken.chunkflow.core.database.entity.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/** `bookmark` テーブルの DAO（§6.4、F8.1）。 */
@Dao
interface BookmarkDao {
    /** 指定した教材のブックマークを時刻順で購読する。 */
    @Query("SELECT * FROM bookmark WHERE media_id = :mediaId ORDER BY position_ms ASC, id ASC")
    fun observeByMediaId(mediaId: String): Flow<List<BookmarkEntity>>

    /** 指定した教材のブックマークを時刻順で取得する。 */
    @Query("SELECT * FROM bookmark WHERE media_id = :mediaId ORDER BY position_ms ASC, id ASC")
    suspend fun getByMediaId(mediaId: String): List<BookmarkEntity>

    /** ブックマークを挿入し、採番された ID を返す。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmark: BookmarkEntity): Long

    /** ブックマークを挿入または更新する。 */
    @Upsert
    suspend fun upsert(bookmark: BookmarkEntity): Long

    /** 指定した ID のブックマークを削除する。 */
    @Query("DELETE FROM bookmark WHERE id = :id")
    suspend fun deleteById(id: Long)
}
