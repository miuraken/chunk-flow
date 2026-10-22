package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.miuraken.chunkflow.core.database.entity.CourseFolderEntity
import com.miuraken.chunkflow.core.database.entity.LanguageColorEntity
import com.miuraken.chunkflow.core.model.LibraryFolderColors
import kotlinx.coroutines.flow.Flow

/** `course_folder` テーブル（と、フォルダの既定の色を決める `language_color` テーブル）の DAO。 */
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

    /**
     * コースフォルダ設定を挿入または更新する。
     *
     * - [CourseFolderEntity.colorIndex] が `null` のときは、既存の行の色の指定を保つ（再スキャンや取り込みで
     *   行を作り直しても、ユーザーが選んだ色を失わないようにする）。色を変える・既定に戻すときは [updateColorIndex] を使う。
     * - 対象言語にまだ色の順番がなければ割り当てる（[assignLanguageColorIfAbsent]）。
     */
    @Transaction
    suspend fun upsert(folder: CourseFolderEntity) {
        val existing = getByPath(folder.coursePath)
        val preservedColor = if (folder.colorIndex == null) existing?.colorIndex else folder.colorIndex
        val preservedTargetStt = if (folder.targetSttEngine == null && folder.baseSttEngine == null) {
            existing?.targetSttEngine
        } else {
            folder.targetSttEngine
        }
        val preservedBaseStt = if (folder.targetSttEngine == null && folder.baseSttEngine == null) {
            existing?.baseSttEngine
        } else {
            folder.baseSttEngine
        }
        upsertRow(
            folder.copy(
                colorIndex = preservedColor,
                targetSttEngine = preservedTargetStt,
                baseSttEngine = preservedBaseStt,
            ),
        )
        assignLanguageColorIfAbsent(folder.targetLang)
    }

    /** 行をそのまま挿入または更新する（色の指定を保たない）。通常は [upsert] を使う。 */
    @Upsert
    suspend fun upsertRow(folder: CourseFolderEntity)

    /** 指定したパスのコースフォルダ設定を削除する。 */
    @Query("DELETE FROM course_folder WHERE course_path = :coursePath")
    suspend fun deleteByPath(coursePath: String)

    /**
     * フォルダの色の指定（パレットの番号）を書き換える。`null` で対象言語の既定の色に戻す。
     *
     * @return 書き換えた行の数（フォルダの行がなければ 0）。
     */
    @Query("UPDATE course_folder SET color_index = :colorIndex WHERE course_path = :coursePath")
    suspend fun updateColorIndex(coursePath: String, colorIndex: Int?): Int

    /**
     * フォルダの音声認識エンジンの上書き設定を書き換える。両方 `null` で全体設定に従う状態に戻す。
     *
     * @return 書き換えた行の数（フォルダの行がなければ 0）。
     */
    @Query("UPDATE course_folder SET target_stt_engine = :targetEngine, base_stt_engine = :baseEngine WHERE course_path = :coursePath")
    suspend fun updateSttOverride(coursePath: String, targetEngine: String?, baseEngine: String?): Int

    /** 対象言語ごとの色の順番を、順番の昇順で購読する。 */
    @Query("SELECT * FROM language_color ORDER BY ordinal ASC")
    fun observeLanguageColors(): Flow<List<LanguageColorEntity>>

    /** 指定した対象言語のキーの色の順番を取得する。 */
    @Query("SELECT * FROM language_color WHERE language = :language")
    suspend fun getLanguageColor(language: String): LanguageColorEntity?

    /** 割り当て済みの色の順番の最大値を取得する（まだなければ `null`）。 */
    @Query("SELECT MAX(ordinal) FROM language_color")
    suspend fun getMaxLanguageColorOrdinal(): Int?

    /** 対象言語の色の順番を挿入する（すでにあれば何もしない）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLanguageColor(entity: LanguageColorEntity): Long

    /**
     * 対象言語（言語コード [rawLanguageCode]）にまだ色の順番がなければ、次の順番を割り当てる。
     *
     * キーは [LibraryFolderColors.languageKey] でそろえる。空の言語コードは何もしない。
     */
    @Transaction
    suspend fun assignLanguageColorIfAbsent(rawLanguageCode: String) {
        val key = LibraryFolderColors.languageKey(rawLanguageCode) ?: return
        if (getLanguageColor(key) != null) return
        val nextOrdinal = (getMaxLanguageColorOrdinal() ?: -1) + 1
        insertLanguageColor(LanguageColorEntity(language = key, ordinal = nextOrdinal))
    }
}
