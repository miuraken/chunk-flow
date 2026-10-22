package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.LibraryFolderColors
import com.miuraken.chunkflow.core.model.LibraryFolderKind
import com.miuraken.chunkflow.core.model.SttEngine

/**
 * ライブラリに登録されたフォルダ（コース・仮想ディレクトリ）の設定情報（`course_folder` テーブル）。
 *
 * [colorIndex] はユーザーが指定したフォルダの色（[LibraryFolderColors] のパレットの番号）。`null` なら対象言語の
 * 既定の色を使う。行を書き直しても色を失わないように、通常は [com.miuraken.chunkflow.core.database.dao.CourseFolderDao.upsert]
 * で書き込む（`null` を渡すと既存の色を保つ）。
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
    @ColumnInfo(name = "last_scanned_at", defaultValue = "0")
    val lastScannedAt: Long = 0L,
    @ColumnInfo(name = "color_index")
    val colorIndex: Int? = null,
    @ColumnInfo(name = "target_stt_engine")
    val targetSttEngine: String? = null,
    @ColumnInfo(name = "base_stt_engine")
    val baseSttEngine: String? = null,
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
    lastScannedAtEpochMs = lastScannedAt,
    colorIndex = colorIndex?.takeIf(LibraryFolderColors::isValidIndex),
    sttOverride = ItemSttOverride(
        targetEngine = SttEngine.fromNameOrNull(targetSttEngine),
        baseEngine = SttEngine.fromNameOrNull(baseSttEngine),
    ),
)

/** ドメインモデルの [LibraryCourseFolder] を [CourseFolderEntity] に変換する。 */
fun LibraryCourseFolder.toEntity(): CourseFolderEntity = CourseFolderEntity(
    coursePath = coursePath,
    kind = kind.name,
    targetLang = languagePair.target.value,
    baseLang = languagePair.base?.value,
    treeUri = treeUri,
    lastScannedAt = lastScannedAtEpochMs,
    colorIndex = colorIndex,
    targetSttEngine = sttOverride.targetEngine?.name,
    baseSttEngine = sttOverride.baseEngine?.name,
)
