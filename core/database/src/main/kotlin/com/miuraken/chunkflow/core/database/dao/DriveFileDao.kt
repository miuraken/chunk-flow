package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.miuraken.chunkflow.core.database.entity.DriveFileEntity
import kotlinx.coroutines.flow.Flow

/** `drive_file` テーブルの DAO（§6.4、§7.1）。 */
@Dao
interface DriveFileDao {
    /** すべての Drive ファイル記録を購読する。 */
    @Query("SELECT * FROM drive_file ORDER BY course ASC, drive_file_id ASC")
    fun observeAll(): Flow<List<DriveFileEntity>>

    /** すべての Drive ファイル記録を取得する。 */
    @Query("SELECT * FROM drive_file ORDER BY course ASC, drive_file_id ASC")
    suspend fun getAll(): List<DriveFileEntity>

    /** 指定した Drive ファイル ID の記録を取得する。 */
    @Query("SELECT * FROM drive_file WHERE drive_file_id = :driveFileId")
    suspend fun getById(driveFileId: String): DriveFileEntity?

    /** 指定したコースの Drive ファイル記録を取得する。 */
    @Query("SELECT * FROM drive_file WHERE course = :course ORDER BY drive_file_id ASC")
    suspend fun getByCourse(course: String): List<DriveFileEntity>

    /** Drive ファイル記録を挿入または更新する。 */
    @Upsert
    suspend fun upsert(driveFile: DriveFileEntity)

    /** 複数の Drive ファイル記録をまとめて挿入または更新する。 */
    @Upsert
    suspend fun upsertAll(driveFiles: List<DriveFileEntity>)

    /** 指定した Drive ファイル ID の記録を削除する。 */
    @Query("DELETE FROM drive_file WHERE drive_file_id = :driveFileId")
    suspend fun deleteById(driveFileId: String)
}
