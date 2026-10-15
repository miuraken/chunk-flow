package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.Flow

/**
 * `chunk` テーブルの DAO（§6.4、§7.4.6）。
 *
 * チャンクの作り直し（再分割・文字起こしの再取り込み）は必ず [replaceChunksTransaction] で
 * 「新しいチャンクの挿入 → 文字起こしの付け替え → 古いチャンクの削除」の順に 1 トランザクションで行う。
 */
@Dao
interface ChunkDao {
    /** 指定した教材・出どころのチャンクを開始時刻順で購読する。 */
    @Query("SELECT * FROM chunk WHERE media_id = :mediaId AND origin = :origin ORDER BY start_ms ASC, id ASC")
    fun observeByMediaIdAndOrigin(mediaId: String, origin: String): Flow<List<ChunkEntity>>

    /** 指定した教材のすべてのチャンクを開始時刻順で購読する。 */
    @Query("SELECT * FROM chunk WHERE media_id = :mediaId ORDER BY start_ms ASC, id ASC")
    fun observeByMediaId(mediaId: String): Flow<List<ChunkEntity>>

    /** 指定した教材に保存されているチャンクの出どころ（`origin`）の一覧を購読する（F5.4）。 */
    @Query("SELECT DISTINCT origin FROM chunk WHERE media_id = :mediaId")
    fun observeOriginsByMediaId(mediaId: String): Flow<List<String>>

    /** 指定した教材・出どころのチャンクを開始時刻順で取得する。 */
    @Query("SELECT * FROM chunk WHERE media_id = :mediaId AND origin = :origin ORDER BY start_ms ASC, id ASC")
    suspend fun getByMediaIdAndOrigin(mediaId: String, origin: String): List<ChunkEntity>

    /** チャンクを挿入し、自動採番された ID の一覧を返す。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(chunks: List<ChunkEntity>): List<Long>

    /** 指定したチャンク ID 群に紐づく文字起こしを取得する（作り直しトランザクション用）。 */
    @Query("SELECT * FROM transcript WHERE chunk_id IN (:chunkIds)")
    suspend fun getTranscriptsByChunkIds(chunkIds: List<Long>): List<TranscriptEntity>

    /** 文字起こしを挿入または更新する（作り直しトランザクション用）。 */
    @Upsert
    suspend fun upsertTranscripts(transcripts: List<TranscriptEntity>)

    /** 指定した ID 群のチャンクを削除する。 */
    @Query("DELETE FROM chunk WHERE id IN (:chunkIds)")
    suspend fun deleteByIds(chunkIds: List<Long>)

    /** 指定した教材・出どころのチャンクをすべて削除する。 */
    @Query("DELETE FROM chunk WHERE media_id = :mediaId AND origin = :origin")
    suspend fun deleteByMediaIdAndOrigin(mediaId: String, origin: String)

    /**
     * チャンクの作り直しトランザクション（§6.4、§7.4.6）。
     *
     * 1. 新しいチャンク（`id = 0`）を挿入して新しい `chunk_id` を確定する。
     * 2. 古いチャンクに紐づいていた文字起こし（特に `user_edited = true` のもの、または [preserveAllOldTranscripts] が
     *    `true` の場合はすべての文字起こし）を、境界一致（±50ms 以内）または重なりが最大の新しいチャンクへ引き継ぐ。
     *    [newTranscriptsByIndex] で渡された新しい文字起こしがある場合は、引き継がれなかった新しいチャンクにそれを設定する。
     * 3. 古いチャンクを削除する（`transcript` の `ON DELETE CASCADE` で古い `chunk_id` の行だけが消え、付け替えた行は残る）。
     *
     * @param mediaId 対象の教材 ID。
     * @param origin 作り直すチャンクの出どころ（`AUTO` または `IMPORTED`）。
     * @param newChunks 新しく挿入するチャンク一覧（開始時刻順）。
     * @param newTranscriptsByIndex [newChunks] のインデックスに対応する新しい文字起こし（`chunkId` は仮の値でよい）。
     * @param preserveAllOldTranscripts `true` なら `user_edited` に限らず古い文字起こしをすべて付け替える（再分割時など）。
     * @return 採番済みの新しい [ChunkEntity] の一覧。
     */
    @Transaction
    suspend fun replaceChunksTransaction(
        mediaId: String,
        origin: String,
        newChunks: List<ChunkEntity>,
        newTranscriptsByIndex: Map<Int, TranscriptEntity> = emptyMap(),
        preserveAllOldTranscripts: Boolean = false,
    ): List<ChunkEntity> {
        val oldChunks = getByMediaIdAndOrigin(mediaId, origin)
        val oldChunkIds = oldChunks.map { it.id }
        val oldTranscriptsByChunkId = if (oldChunkIds.isEmpty()) {
            emptyMap()
        } else {
            getTranscriptsByChunkIds(oldChunkIds).associateBy { it.chunkId }
        }

        val normalizedToInsert = newChunks.map { it.copy(id = 0L, mediaId = mediaId, origin = origin) }
        val insertedIds = if (normalizedToInsert.isEmpty()) {
            emptyList()
        } else {
            insertAll(normalizedToInsert)
        }
        val insertedChunks = normalizedToInsert.mapIndexed { index, chunk ->
            chunk.copy(id = insertedIds[index])
        }

        if (insertedChunks.isNotEmpty()) {
            val transcriptsToSave = LinkedHashMap<Long, TranscriptEntity>()
            // 1. 新しい文字起こし（再取り込み時など）をまず配置する
            for ((index, template) in newTranscriptsByIndex) {
                val targetChunk = insertedChunks.getOrNull(index) ?: continue
                transcriptsToSave[targetChunk.id] = template.copy(chunkId = targetChunk.id)
            }

            // 2. 古い文字起こしの引き継ぎ（§7.4.6: userEdited = true、または preserveAllOldTranscripts）
            for (oldChunk in oldChunks) {
                val oldTranscript = oldTranscriptsByChunkId[oldChunk.id] ?: continue
                if (!preserveAllOldTranscripts && !oldTranscript.userEdited) continue
                val matchedNewChunk = findBestMatchingChunk(oldChunk, insertedChunks) ?: continue
                // userEdited な文字起こしは新しい文字起こしより優先して上書きする
                val existing = transcriptsToSave[matchedNewChunk.id]
                if (existing == null || oldTranscript.userEdited || !existing.userEdited) {
                    transcriptsToSave[matchedNewChunk.id] = oldTranscript.copy(chunkId = matchedNewChunk.id)
                }
            }

            if (transcriptsToSave.isNotEmpty()) {
                upsertTranscripts(transcriptsToSave.values.toList())
            }
        }

        if (oldChunkIds.isNotEmpty()) {
            deleteByIds(oldChunkIds)
        }
        return insertedChunks
    }

    companion object {
        private const val BOUNDARY_TOLERANCE_MS = 50L

        /**
         * §7.4.6 の規則に従い、[oldChunk] の文字起こしを引き継ぐ先の新しいチャンクを選ぶ。
         * 1. 開始・終了とも ±50ms 以内で一致するチャンクがあればそれを選ぶ。
         * 2. なければ重なり時間 `min(old.end, new.end) - max(old.start, new.start)` が最大のチャンクを選ぶ。
         * 3. 1 ミリ秒も重なるチャンクがなければ、中心時刻が最も近いチャンクを選ぶ（手動修正を捨てないため）。
         */
        internal fun findBestMatchingChunk(
            oldChunk: ChunkEntity,
            candidates: List<ChunkEntity>,
        ): ChunkEntity? {
            if (candidates.isEmpty()) return null

            val boundaryMatch = candidates.firstOrNull { candidate ->
                abs(candidate.startMs - oldChunk.startMs) <= BOUNDARY_TOLERANCE_MS &&
                    abs(candidate.endMs - oldChunk.endMs) <= BOUNDARY_TOLERANCE_MS
            }
            if (boundaryMatch != null) return boundaryMatch

            val bestOverlap = candidates
                .map { candidate ->
                    val overlap = min(oldChunk.endMs, candidate.endMs) - max(oldChunk.startMs, candidate.startMs)
                    candidate to overlap
                }
                .filter { (_, overlap) -> overlap > 0L }
                .maxByOrNull { (_, overlap) -> overlap }
                ?.first
            if (bestOverlap != null) return bestOverlap

            val oldCenter = (oldChunk.startMs + oldChunk.endMs) / 2L
            return candidates.minByOrNull { candidate ->
                val center = (candidate.startMs + candidate.endMs) / 2L
                abs(center - oldCenter)
            }
        }
    }
}
