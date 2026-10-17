package com.miuraken.chunkflow.core.database

import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.entity.AnalysisStateEntity
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.ItemSttOverrideEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import com.miuraken.chunkflow.core.database.entity.toEntity
import com.miuraken.chunkflow.core.database.entity.toExternalModel
import com.miuraken.chunkflow.core.database.entity.toStageAndStatus
import com.miuraken.chunkflow.core.model.AnalysisError
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.Bookmark
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.DriveFile
import com.miuraken.chunkflow.core.model.DriveFileKind
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.WordTiming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest

class DatabaseEntityAndTransactionTest {
    private val mediaId = MediaId("00000000-0000-4000-8000-000000000010")

    @Test
    fun `MediaItemEntityとAudioItemの相互変換が正しく往復する`() {
        val item = com.miuraken.chunkflow.core.model.AudioItem(
            id = mediaId,
            title = "Lesson 01",
            durationMs = 120_000L,
            mimeType = "audio/mpeg",
            sizeBytes = 2_048_000L,
            contentSha256 = "a".repeat(64),
            course = "korean-course",
            languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
            transcriptOffsetMs = 39,
            importedAtEpochMs = 1_700_000_000_000L,
        )
        val entity = item.toEntity(mp3HasHeader = true, mp3SamplesPerFrame = 1152, mp3EncoderDelay = 576)
        assertEquals(true, entity.mp3HasHeader)
        assertEquals(item, entity.toExternalModel())
    }

    @Test
    fun `ItemStateEntityとItemStateの相互変換がAutoPauseの各種類を含めて往復する`() {
        val autoPauses = listOf(
            AutoPause.Off,
            AutoPause.Fixed(pauseMs = 1_500L),
            AutoPause.Proportional(factor = 1.25f, minMs = 800L),
        )
        for (autoPause in autoPauses) {
            val state = ItemState(
                mediaId = mediaId,
                positionMs = 45_000L,
                speed = 1.25f,
                chunkSource = ChunkOrigin.IMPORTED,
                autoPause = autoPause,
                autoPauseScope = AutoPauseScope.AFTER_BASE_LANGUAGE,
                languageFilter = LanguageFilter.TARGET_ONLY,
                updatedAtEpochMs = 999L,
            )
            assertEquals(state, state.toEntity().toExternalModel())
        }
    }

    @Test
    fun `AnalysisStateEntityとStageStatusの相互変換がすべての状態で往復する`() {
        val statuses = listOf(
            StageStatus.NotStarted,
            StageStatus.Running(progress = 0.5f),
            StageStatus.Running(progress = null),
            StageStatus.Done,
            StageStatus.Failed(AnalysisError.DECODE_FAILED),
        )
        for (status in statuses) {
            val entity = status.toEntity(mediaId, AnalysisStage.FEATURES, algorithmVersion = 2, updatedAtEpochMs = 10L)
            assertEquals(AnalysisStage.FEATURES to status, entity.toStageAndStatus())
        }
        val unknownStage = AnalysisStateEntity(
            mediaId = mediaId.value,
            stage = "UNKNOWN",
            status = AnalysisStateEntity.STATUS_DONE,
        )
        assertNull(unknownStage.toStageAndStatus())
    }

    @Test
    fun `ChunkEntityとTranscriptEntityとBookmarkEntityとDriveFileEntityの相互変換が往復する`() {
        val chunk = Chunk(id = ChunkId(42L), startMs = 1_000L, endMs = 3_500L, origin = ChunkOrigin.IMPORTED)
        assertEquals(chunk, chunk.toEntity(mediaId).toExternalModel())

        val transcript = Transcript(
            chunkId = ChunkId(42L),
            text = "你们好",
            textVariants = mapOf("Hant" to "你們好"),
            language = LanguageTag("zh"),
            languageConfidence = 0.98f,
            words = listOf(WordTiming(startMs = 1_000L, endMs = 2_000L, text = "你们好", confidence = 0.95f)),
            engine = EngineId("large-v3-turbo"),
            userEdited = true,
        )
        assertEquals(transcript, transcript.toEntity(createdAtEpochMs = 123L).toExternalModel())

        val bookmark = Bookmark(id = 7L, mediaId = mediaId, positionMs = 2_500L, note = "復習", createdAtEpochMs = 500L)
        assertEquals(bookmark, bookmark.toEntity().toExternalModel())

        val driveFile = DriveFile(
            driveFileId = "drive-123",
            kind = DriveFileKind.TRANSCRIPT,
            course = "cantonese-course",
            modifiedTimeEpochMs = 777L,
            sha256 = "b".repeat(64),
            mediaId = mediaId,
        )
        assertEquals(driveFile, driveFile.toEntity().toExternalModel())

        val sttOverride = ItemSttOverride(
            targetEngine = SttEngine.ML_KIT,
            baseEngine = SttEngine.SHERPA_ONNX,
        )
        assertEquals(sttOverride, sttOverride.toEntity(mediaId).toExternalModel())
        assertEquals(
            ItemSttOverride(targetEngine = null, baseEngine = SttEngine.SPEECH_RECOGNIZER),
            ItemSttOverrideEntity(
                mediaId = mediaId.value,
                targetEngine = "UNKNOWN_ENGINE",
                baseEngine = "SPEECH_RECOGNIZER",
            ).toExternalModel(),
        )
    }

    @Test
    fun `replaceChunksTransaction_新しいチャンクの挿入後に文字起こしを付け替えてから古いチャンクを削除する`() = runTest {
        val dao = InMemoryChunkDao()
        // 古いチャンク 2 件と文字起こし（1 件目は userEdited = true、2 件目は userEdited = false）を登録
        val oldIds = dao.insertAll(
            listOf(
                ChunkEntity(mediaId = mediaId.value, startMs = 1_000L, endMs = 3_000L, origin = "IMPORTED"),
                ChunkEntity(mediaId = mediaId.value, startMs = 4_000L, endMs = 6_000L, origin = "IMPORTED"),
            ),
        )
        dao.upsertTranscripts(
            listOf(
                TranscriptEntity(
                    chunkId = oldIds[0],
                    text = "手動修正済みテキスト",
                    language = "ko",
                    engine = "manual",
                    userEdited = true,
                ),
                TranscriptEntity(
                    chunkId = oldIds[1],
                    text = "古い自動認識テキスト",
                    language = "ja",
                    engine = "whisper",
                    userEdited = false,
                ),
            ),
        )

        // 再取り込み: 1 件目は ±30ms ずれた位置（±50ms 以内で一致）、2 件目は新しいテキスト
        val newChunks = listOf(
            ChunkEntity(mediaId = mediaId.value, startMs = 1_030L, endMs = 2_980L, origin = "IMPORTED"),
            ChunkEntity(mediaId = mediaId.value, startMs = 4_100L, endMs = 6_200L, origin = "IMPORTED"),
        )
        val newTranscripts = mapOf(
            0 to TranscriptEntity(chunkId = 0L, text = "新しい認識1", language = "ko", engine = "whisper", userEdited = false),
            1 to TranscriptEntity(chunkId = 0L, text = "新しい認識2", language = "ja", engine = "whisper", userEdited = false),
        )

        val inserted = dao.replaceChunksTransaction(
            mediaId = mediaId.value,
            origin = "IMPORTED",
            newChunks = newChunks,
            newTranscriptsByIndex = newTranscripts,
            preserveAllOldTranscripts = false,
        )

        // 古いチャンクが消え、新しいチャンク 2 件だけが残る
        assertEquals(2, dao.chunks.size)
        val transcripts = dao.getTranscriptsByChunkIds(inserted.map { it.id }).associateBy { it.chunkId }
        // 1 件目は userEdited = true の手動修正テキストが引き継がれている
        assertEquals("手動修正済みテキスト", transcripts[inserted[0].id]?.text)
        assertEquals(true, transcripts[inserted[0].id]?.userEdited)
        // 2 件目は新しい文字起こしになっている
        assertEquals("新しい認識2", transcripts[inserted[1].id]?.text)
    }

    private class InMemoryChunkDao : ChunkDao {
        private var nextId = 1L
        val chunks = mutableListOf<ChunkEntity>()
        val transcripts = linkedMapOf<Long, TranscriptEntity>()

        override fun observeByMediaIdAndOrigin(mediaId: String, origin: String): Flow<List<ChunkEntity>> = emptyFlow()

        override fun observeByMediaId(mediaId: String): Flow<List<ChunkEntity>> = emptyFlow()

        override fun observeOriginsByMediaId(mediaId: String): Flow<List<String>> = emptyFlow()

        override suspend fun getByMediaIdAndOrigin(mediaId: String, origin: String): List<ChunkEntity> = chunks.filter { it.mediaId == mediaId && it.origin == origin }.sortedBy { it.startMs }

        override suspend fun insertAll(chunks: List<ChunkEntity>): List<Long> = chunks.map { chunk ->
            val assignedId = if (chunk.id == 0L) nextId++ else chunk.id
            this.chunks.add(chunk.copy(id = assignedId))
            assignedId
        }

        override suspend fun getTranscriptsByChunkIds(chunkIds: List<Long>): List<TranscriptEntity> = chunkIds.mapNotNull { transcripts[it] }

        override suspend fun upsertTranscripts(transcripts: List<TranscriptEntity>) {
            for (t in transcripts) {
                this.transcripts[t.chunkId] = t
            }
        }

        override suspend fun deleteByIds(chunkIds: List<Long>) {
            val idSet = chunkIds.toSet()
            chunks.removeAll { it.id in idSet }
            // ON DELETE CASCADE と同じく紐づく文字起こしも消す
            for (id in idSet) {
                transcripts.remove(id)
            }
        }

        override suspend fun deleteByMediaIdAndOrigin(mediaId: String, origin: String) {
            val idsToRemove = chunks.filter { it.mediaId == mediaId && it.origin == origin }.map { it.id }.toSet()
            chunks.removeAll { it.id in idsToRemove }
            for (id in idsToRemove) {
                transcripts.remove(id)
            }
        }
    }
}
