package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import kotlinx.coroutines.flow.Flow

/** `transcript` テーブルの DAO（§6.4）。 */
@Dao
interface TranscriptDao {
    /** 指定した教材のすべての文字起こしを購読する。 */
    @Query(
        """
        SELECT t.* FROM transcript t
        INNER JOIN chunk c ON c.id = t.chunk_id
        WHERE c.media_id = :mediaId
        ORDER BY c.start_ms ASC, c.id ASC
        """,
    )
    fun observeByMediaId(mediaId: String): Flow<List<TranscriptEntity>>

    /** 指定した教材・チャンク出どころの文字起こしを購読する。 */
    @Query(
        """
        SELECT t.* FROM transcript t
        INNER JOIN chunk c ON c.id = t.chunk_id
        WHERE c.media_id = :mediaId AND c.origin = :origin
        ORDER BY c.start_ms ASC, c.id ASC
        """,
    )
    fun observeByMediaIdAndOrigin(mediaId: String, origin: String): Flow<List<TranscriptEntity>>

    /** 指定したチャンク ID の文字起こしを取得する。 */
    @Query("SELECT * FROM transcript WHERE chunk_id = :chunkId")
    suspend fun getByChunkId(chunkId: Long): TranscriptEntity?

    /** 指定したチャンク ID 群の文字起こしを取得する。 */
    @Query("SELECT * FROM transcript WHERE chunk_id IN (:chunkIds)")
    suspend fun getByChunkIds(chunkIds: List<Long>): List<TranscriptEntity>

    /** 文字起こしを挿入または更新する。 */
    @Upsert
    suspend fun upsert(transcript: TranscriptEntity)

    /** 複数の文字起こしをまとめて挿入または更新する。 */
    @Upsert
    suspend fun upsertAll(transcripts: List<TranscriptEntity>)

    /** チャンクの言語ラベルを手動修正する（F7.3）。 */
    @Query(
        """
        UPDATE transcript
        SET language = :language, language_confidence = 1.0, user_edited = 1
        WHERE chunk_id = :chunkId
        """,
    )
    suspend fun updateLanguage(chunkId: Long, language: String?)

    /**
     * 指定した教材で言語ラベルが未設定または `'und'` の文字起こしを一括で設定し、`user_edited = 1` にする（F7.3）。
     */
    @Query(
        """
        UPDATE transcript
        SET language = :language, language_confidence = 1.0, user_edited = 1
        WHERE chunk_id IN (SELECT id FROM chunk WHERE media_id = :mediaId)
          AND (language IS NULL OR language = '' OR language = 'und')
        """,
    )
    suspend fun bulkSetUnknownLanguageForMedia(mediaId: String, language: String)

    /** 指定したチャンク ID の文字起こしを削除する。 */
    @Query("DELETE FROM transcript WHERE chunk_id = :chunkId")
    suspend fun deleteByChunkId(chunkId: Long)
}
