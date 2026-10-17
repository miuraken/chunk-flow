package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.miuraken.chunkflow.core.database.entity.CourseFolderEntity
import kotlinx.coroutines.flow.Flow

/** `course_folder` テーブルの DAO。 */
@Dao
interface CourseFolderDao {
    /** すべてのコースフォルダ設定をパス順で購読する。 */
    @Query("SELECT * FROM course_folder ORDER BY course_path ASC")
    fun observeAll(): Flow<List<CourseFolderEntity>>

    /** すべてのコースフォルダ設定を取得する。 */
    @Query("SELECT * FROM course_folder ORDER BY course_path ASC")
    suspend fun getAll(): List<CourseFolderEntity>

    /** 指定したパスのコースフォルダ設定を取得する。 */
    @Query("SELECT * FROM course_folder WHERE course_path = :coursePath")
    suspend fun getByPath(coursePath: String): CourseFolderEntity?

    /** 指定した SAF Tree URI のコースフォルダ設定を取得する。 */
    @Query("SELECT * FROM course_folder WHERE tree_uri = :treeUri LIMIT 1")
    suspend fun getByTreeUri(treeUri: String): CourseFolderEntity?

    /** コースフォルダ設定を挿入または更新する。 */
    @Upsert
    suspend fun upsert(folder: CourseFolderEntity)

    /** 指定したパスのコースフォルダ設定を削除する。 */
    @Query("DELETE FROM course_folder WHERE course_path = :coursePath")
    suspend fun deleteByPath(coursePath: String)
}
