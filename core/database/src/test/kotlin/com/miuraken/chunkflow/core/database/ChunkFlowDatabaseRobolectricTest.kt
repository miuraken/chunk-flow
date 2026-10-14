package com.miuraken.chunkflow.core.database

import androidx.room.Room
import com.miuraken.chunkflow.core.database.entity.AnalysisStateEntity
import com.miuraken.chunkflow.core.database.entity.BookmarkEntity
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.DriveFileEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ChunkFlowDatabaseRobolectricTest {
    private lateinit var db: ChunkFlowDatabase
    private val mediaId = "00000000-0000-4000-8000-000000000020"

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            ChunkFlowDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `全テーブルの保存とON_DELETE_CASCADEとreplaceChunksTransactionがSQLite上で正しく動く`() = runTest {
        val item = MediaItemEntity(
            id = mediaId,
            title = "Lesson 01",
            durationMs = 60_000L,
            mimeType = "audio/mpeg",
            sizeBytes = 1_000_000L,
            sha256 = "c".repeat(64),
            course = "korean-course",
            targetLang = "ko",
            baseLang = "ja",
            transcriptOffsetMs = 39,
            importedAt = 1_000L,
        )
        db.mediaItemDao().upsert(item)
        assertEquals(item, db.mediaItemDao().getBySha256("c".repeat(64)))

        // item_state: 行がない状態から updatePlaybackPosition で作成・更新できる
        db.itemStateDao().updatePlaybackPosition(
            mediaId = mediaId,
            positionMs = 12_340L,
            speed = 1.25f,
            updatedAt = 2_000L,
        )
        assertEquals(12_340L, db.itemStateDao().observeByMediaId(mediaId).first()?.positionMs)

        // analysis_state
        db.analysisStateDao().upsert(
            AnalysisStateEntity(
                mediaId = mediaId,
                stage = "FEATURES",
                status = AnalysisStateEntity.STATUS_DONE,
                algorithmVersion = 1,
                updatedAt = 3_000L,
            ),
        )
        assertEquals(1, db.analysisStateDao().getByMediaId(mediaId).size)

        // chunk + transcript: 登録と replaceChunksTransaction での作り直し（userEdited 引き継ぎ）
        val initialChunks = db.chunkDao().replaceChunksTransaction(
            mediaId = mediaId,
            origin = "IMPORTED",
            newChunks = listOf(
                ChunkEntity(mediaId = mediaId, startMs = 1_000L, endMs = 3_000L, origin = "IMPORTED"),
                ChunkEntity(mediaId = mediaId, startMs = 4_000L, endMs = 6_000L, origin = "IMPORTED"),
            ),
            newTranscriptsByIndex = mapOf(
                0 to TranscriptEntity(chunkId = 0L, text = "手修正", language = "ko", engine = "manual", userEdited = true),
                1 to TranscriptEntity(chunkId = 0L, text = "旧自動", language = "ja", engine = "whisper", userEdited = false),
            ),
        )
        assertEquals(2, initialChunks.size)

        val rebuiltChunks = db.chunkDao().replaceChunksTransaction(
            mediaId = mediaId,
            origin = "IMPORTED",
            newChunks = listOf(
                ChunkEntity(mediaId = mediaId, startMs = 1_020L, endMs = 2_990L, origin = "IMPORTED"),
                ChunkEntity(mediaId = mediaId, startMs = 4_200L, endMs = 6_500L, origin = "IMPORTED"),
            ),
            newTranscriptsByIndex = mapOf(
                0 to TranscriptEntity(chunkId = 0L, text = "新自動1", language = "ko", engine = "whisper", userEdited = false),
                1 to TranscriptEntity(chunkId = 0L, text = "新自動2", language = "ja", engine = "whisper", userEdited = false),
            ),
        )
        val savedTranscripts = db.transcriptDao().observeByMediaId(mediaId).first()
        assertEquals(listOf("手修正", "新自動2"), savedTranscripts.map { it.text })
        assertEquals(rebuiltChunks.map { it.id }, savedTranscripts.map { it.chunkId })

        // bookmark + drive_file
        db.bookmarkDao().insert(BookmarkEntity(mediaId = mediaId, positionMs = 1_500L, note = "重要"))
        db.driveFileDao().upsert(
            DriveFileEntity(
                driveFileId = "drive-1",
                kind = "AUDIO",
                course = "korean-course",
                modifiedTime = 5_000L,
                sha256 = "c".repeat(64),
                mediaId = mediaId,
            ),
        )

        // media_item を削除すると ON DELETE CASCADE で全テーブルの関連行が消える
        db.mediaItemDao().deleteById(mediaId)
        assertNull(db.itemStateDao().getByMediaId(mediaId))
        assertEquals(emptyList(), db.analysisStateDao().getByMediaId(mediaId))
        assertEquals(emptyList(), db.chunkDao().getByMediaIdAndOrigin(mediaId, "IMPORTED"))
        assertEquals(emptyList(), db.transcriptDao().observeByMediaId(mediaId).first())
        assertEquals(emptyList(), db.bookmarkDao().getByMediaId(mediaId))
        assertEquals(emptyList(), db.driveFileDao().getAll())
    }
}
