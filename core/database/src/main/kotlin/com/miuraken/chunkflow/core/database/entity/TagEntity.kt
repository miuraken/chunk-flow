package com.miuraken.chunkflow.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.miuraken.chunkflow.core.model.LibraryTags

/**
 * ファイル（教材）に付けるタグ（`tag` テーブル）。
 *
 * [nameKey]（[LibraryTags.key]）に一意インデックスを張り、大文字・小文字や全角・半角の違いだけのタグを
 * 作らないようにする。どのファイルにも付いていないタグは、タグを書き換えるときに消す。
 *
 * @property id タグの ID（自動採番）。
 * @property name 表示名（[LibraryTags.normalizeName] でそろえた、最初に作られたときの表記）。
 * @property nameKey 同じタグかどうかを比べるキー。
 * @property createdAt 作成時刻（UNIX エポックからのミリ秒）。タグの一覧はこの順に並べる。
 */
@Entity(
    tableName = "tag",
    indices = [
        Index(value = ["name_key"], unique = true),
    ],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "name_key")
    val nameKey: String,
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0L,
)
