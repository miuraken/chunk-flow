package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.miuraken.chunkflow.core.database.entity.DriveFileEntity

/** `drive_file` テーブルの DAO（§6.4）。 */
@Dao
interface DriveFileDao {
    /** すべての Drive 同期ファイル情報を取得する。 */
    @Query("SELECT * FROM drive_file")
    suspend fun getAll(): List<DriveFileEntity>

    /** 指定した Drive ファイル ID の情報を取得する。 */
    @Query("SELECT * FROM drive_file WHERE drive_file_id = :driveFileId")
    suspend fun getById(driveFileId: String): DriveFileEntity?

    /** 指定した教材 ID に紐づく Drive 同期ファイル情報を取得する（§7.2）。 */
    @Query("SELECT * FROM drive_file WHERE media_id = :mediaId")
    suspend fun getByMediaId(mediaId: String): List<DriveFileEntity>

    /** Drive ファイル情報を挿入または更新する。 */
    @Upsert
    suspend fun upsert(file: DriveFileEntity)

    /** 複数の Drive ファイル情報をまとめて挿入または更新する。 */
    @Upsert
    suspend fun upsertAll(files: List<DriveFileEntity>)

    /** 指定した Drive ファイル ID の情報を削除する。 */
    @Query("DELETE FROM drive_file WHERE drive_file_id = :driveFileId")
    suspend fun deleteById(driveFileId: String)
}
