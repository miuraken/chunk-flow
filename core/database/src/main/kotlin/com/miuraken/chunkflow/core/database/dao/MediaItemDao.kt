package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import kotlinx.coroutines.flow.Flow

/** `media_item` テーブルの DAO（§6.4）。 */
@Dao
interface MediaItemDao {
    /** すべての教材を取り込み日時の新しい順で購読する。 */
    @Query("SELECT * FROM media_item ORDER BY imported_at DESC, id ASC")
    fun observeAll(): Flow<List<MediaItemEntity>>

    /** 指定した ID の教材を購読する。 */
    @Query("SELECT * FROM media_item WHERE id = :id")
    fun observeById(id: String): Flow<MediaItemEntity?>

    /** 指定した ID の教材を取得する。 */
    @Query("SELECT * FROM media_item WHERE id = :id")
    suspend fun getById(id: String): MediaItemEntity?

    /** 指定した SHA-256 の教材を取得する（重複検出・文字起こし対応付け用）。 */
    @Query("SELECT * FROM media_item WHERE sha256 = :sha256")
    suspend fun getBySha256(sha256: String): MediaItemEntity?

    /** 教材を挿入または更新する（`ON DELETE CASCADE` を発火させない `@Upsert` を使う）。 */
    @Upsert
    suspend fun upsert(item: MediaItemEntity)

    /** 教材を新規挿入する（重複時は無視して `-1L` を返す）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(item: MediaItemEntity): Long

    /** 既存の教材を更新する。 */
    @Update
    suspend fun update(item: MediaItemEntity)

    /** 文字起こしの時刻オフセットを更新する（§7.4.6、F7.6）。 */
    @Query("UPDATE media_item SET transcript_offset_ms = :offsetMs WHERE id = :id")
    suspend fun updateTranscriptOffset(id: String, offsetMs: Int)

    /** 教材の言語ペアを更新する（§7.4.6）。 */
    @Query("UPDATE media_item SET target_lang = :targetLang, base_lang = :baseLang WHERE id = :id")
    suspend fun updateLanguagePair(id: String, targetLang: String, baseLang: String?)

    /** 教材のコース名を更新する（F1.3）。 */
    @Query("UPDATE media_item SET course = :course WHERE id = :id")
    suspend fun updateCourse(id: String, course: String?)

    /** 教材のコース名と言語ペアをまとめて更新する（F1.3、F1.4）。 */
    @Query("UPDATE media_item SET course = :course, target_lang = :targetLang, base_lang = :baseLang WHERE id = :id")
    suspend fun updateCourseAndLanguagePair(
        id: String,
        course: String?,
        targetLang: String,
        baseLang: String?,
    )

    /** 指定したコース（ディレクトリ）に属する教材のコース名と言語ペアを一括更新する。 */
    @Query("UPDATE media_item SET course = :newCourse, target_lang = :targetLang, base_lang = :baseLang WHERE course = :oldCourse")
    suspend fun updateCourseMetadata(
        oldCourse: String,
        newCourse: String?,
        targetLang: String,
        baseLang: String?,
    )

    /** コース未設定（`course IS NULL`）の教材のコース名と言語ペアを一括更新する。 */
    @Query("UPDATE media_item SET course = :newCourse, target_lang = :targetLang, base_lang = :baseLang WHERE course IS NULL")
    suspend fun updateUncategorizedCourseMetadata(
        newCourse: String?,
        targetLang: String,
        baseLang: String?,
    )

    /** 教材を削除する（外部キーの `ON DELETE CASCADE` により関連テーブルも削除される）。 */
    @Query("DELETE FROM media_item WHERE id = :id")
    suspend fun deleteById(id: String)
}
