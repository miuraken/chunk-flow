package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.LibraryFolderKind

/**
 * ライブラリに登録されたフォルダ（コース・仮想ディレクトリ・Drive フォルダ）の設定情報（`course_folder` テーブル）。
 */
@Entity(tableName = "course_folder")
data class CourseFolderEntity(
    @PrimaryKey
    @ColumnInfo(name = "course_path")
    val coursePath: String,
    @ColumnInfo(name = "kind")
    val kind: String,
    @ColumnInfo(name = "target_lang")
    val targetLang: String,
    @ColumnInfo(name = "base_lang")
    val baseLang: String? = null,
    @ColumnInfo(name = "tree_uri")
    val treeUri: String? = null,
    @ColumnInfo(name = "drive_folder_id")
    val driveFolderId: String? = null,
    @ColumnInfo(name = "last_scanned_at", defaultValue = "0")
    val lastScannedAt: Long = 0L,
)

/** [CourseFolderEntity] をドメインモデルの [LibraryCourseFolder] に変換する。 */
fun CourseFolderEntity.toExternalModel(): LibraryCourseFolder = LibraryCourseFolder(
    coursePath = coursePath,
    kind = LibraryFolderKind.entries.firstOrNull { it.name == kind } ?: LibraryFolderKind.VIRTUAL_DIRECTORY,
    languagePair = LanguagePair(
        target = LanguageTag(targetLang),
        base = baseLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag),
    ),
    treeUri = treeUri,
    driveFolderId = driveFolderId,
    lastScannedAtEpochMs = lastScannedAt,
)

/** ドメインモデルの [LibraryCourseFolder] を [CourseFolderEntity] に変換する。 */
fun LibraryCourseFolder.toEntity(): CourseFolderEntity = CourseFolderEntity(
    coursePath = coursePath,
    kind = kind.name,
    targetLang = languagePair.target.value,
    baseLang = languagePair.base?.value,
    treeUri = treeUri,
    driveFolderId = driveFolderId,
    lastScannedAt = lastScannedAtEpochMs,
)
