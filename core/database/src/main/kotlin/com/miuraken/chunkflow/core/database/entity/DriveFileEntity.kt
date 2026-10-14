package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.miuraken.chunkflow.core.model.DriveFile
import com.miuraken.chunkflow.core.model.DriveFileKind
import com.miuraken.chunkflow.core.model.MediaId

/**
 * Google Drive の手動同期で記録するファイル情報（§6.4 `drive_file` テーブル）。
 */
@Entity(
    tableName = "drive_file",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["media_id"]),
    ],
)
data class DriveFileEntity(
    @PrimaryKey
    @ColumnInfo(name = "drive_file_id")
    val driveFileId: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "course")
    val course: String,
    @ColumnInfo(name = "modified_time")
    val modifiedTime: Long,
    @ColumnInfo(name = "sha256")
    val sha256: String? = null,
    @ColumnInfo(name = "media_id")
    val mediaId: String? = null,
)

/** [DriveFileEntity] をドメインモデルの [DriveFile] に変換する。 */
fun DriveFileEntity.toExternalModel(): DriveFile = DriveFile(
    driveFileId = driveFileId,
    kind = DriveFileKind.entries.firstOrNull { it.name == kind } ?: DriveFileKind.AUDIO,
    course = course,
    modifiedTimeEpochMs = modifiedTime,
    sha256 = sha256,
    mediaId = mediaId?.let(::MediaId),
)

/** ドメインモデルの [DriveFile] を [DriveFileEntity] に変換する。 */
fun DriveFile.toEntity(): DriveFileEntity = DriveFileEntity(
    driveFileId = driveFileId,
    kind = kind.name,
    course = course,
    modifiedTime = modifiedTimeEpochMs,
    sha256 = sha256,
    mediaId = mediaId?.value,
)
