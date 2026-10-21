package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.miuraken.chunkflow.core.model.LibraryFolderColors

/**
 * フォルダの既定の色を決めるための、対象言語ごとの順番（`language_color` テーブル）。
 *
 * 対象言語が初めてフォルダに使われたときに、次の順番（0 始まり）を割り当てる。フォルダを消しても行は残し、
 * 同じ言語のフォルダを作り直しても色が変わらないようにする。
 *
 * @property language 対象言語のキー（[LibraryFolderColors.languageKey]）。
 * @property ordinal 割り当てた順番（0 始まり）。パレットの番号は [LibraryFolderColors.indexForOrdinal] で求める。
 */
@Entity(tableName = "language_color")
data class LanguageColorEntity(
    @PrimaryKey
    @ColumnInfo(name = "language")
    val language: String,
    @ColumnInfo(name = "ordinal")
    val ordinal: Int,
)
