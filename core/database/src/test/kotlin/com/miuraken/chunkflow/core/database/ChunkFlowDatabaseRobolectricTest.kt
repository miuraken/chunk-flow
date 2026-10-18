package com.miuraken.chunkflow.core.database

import android.database.sqlite.SQLiteException
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.miuraken.chunkflow.core.database.entity.AnalysisStateEntity
import com.miuraken.chunkflow.core.database.entity.BookmarkEntity
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.CourseFolderEntity
import com.miuraken.chunkflow.core.database.entity.DriveFileEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.ItemSttOverrideEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import com.miuraken.chunkflow.core.model.SttEngine
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
        assertEquals(false, db.itemStateDao().getByMediaId(mediaId)?.isListened)

        // updateListenedStatus で is_listened を true にし、その後の updatePlaybackPosition でも維持される
        db.itemStateDao().updateListenedStatus(mediaId = mediaId, isListened = true, updatedAt = 2_500L)
        assertEquals(true, db.itemStateDao().getByMediaId(mediaId)?.isListened)
        db.itemStateDao().updatePlaybackPosition(
            mediaId = mediaId,
            positionMs = 20_000L,
            speed = 1.0f,
            updatedAt = 2_800L,
        )
        assertEquals(true, db.itemStateDao().getByMediaId(mediaId)?.isListened)

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

        // chunk + transcript: 登録と replaceChunksTransaction での作り直し（userEdited 引き継ぎ・isSkipped 引き継ぎ）
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
        db.chunkDao().updateSkippedByIds(mediaId, listOf(initialChunks[0].id), skipped = true)
        assertEquals(listOf(true, false), db.chunkDao().getByMediaIdAndOrigin(mediaId, "IMPORTED").map { it.isSkipped })

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
        assertEquals(listOf(true, false), rebuiltChunks.map { it.isSkipped })
        val savedTranscripts = db.transcriptDao().observeByMediaId(mediaId).first()
        assertEquals(listOf("手修正", "新自動2"), savedTranscripts.map { it.text })
        assertEquals(rebuiltChunks.map { it.id }, savedTranscripts.map { it.chunkId })

        // M3 追加クエリ: updateCourse / updateCourseAndLanguagePair / bulkSetUnknownLanguageForMedia
        db.mediaItemDao().updateCourse(mediaId, "updated-course")
        assertEquals("updated-course", db.mediaItemDao().getById(mediaId)?.course)
        db.mediaItemDao().updateCourseAndLanguagePair(mediaId, "final-course", "zh", "en")
        val updatedMedia = db.mediaItemDao().getById(mediaId)
        assertEquals("final-course", updatedMedia?.course)
        assertEquals("zh", updatedMedia?.targetLang)
        assertEquals("en", updatedMedia?.baseLang)

        db.mediaItemDao().updateCourseMetadata("final-course", null, "ko", "ja")
        assertEquals(null, db.mediaItemDao().getById(mediaId)?.course)
        assertEquals("ko", db.mediaItemDao().getById(mediaId)?.targetLang)
        db.mediaItemDao().updateUncategorizedCourseMetadata("categorized-course", "zh", "ja")
        assertEquals("categorized-course", db.mediaItemDao().getById(mediaId)?.course)
        assertEquals("zh", db.mediaItemDao().getById(mediaId)?.targetLang)

        db.transcriptDao().upsert(
            TranscriptEntity(
                chunkId = rebuiltChunks[1].id,
                text = "未設定言語",
                language = "und",
                engine = "whisper",
                userEdited = false,
            ),
        )
        db.transcriptDao().bulkSetUnknownLanguageForMedia(mediaId, "zh")
        val afterBulk = db.transcriptDao().getByChunkId(rebuiltChunks[1].id)
        assertEquals("zh", afterBulk?.language)
        assertEquals(true, afterBulk?.userEdited)

        // bookmark + drive_file + item_stt_override
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
        assertEquals(1, db.driveFileDao().getByMediaId(mediaId).size)
        assertTrue(
            db.itemSttOverrideDao().setIfMediaExists(
                ItemSttOverrideEntity(
                    mediaId = mediaId,
                    targetEngine = SttEngine.ML_KIT.name,
                    baseEngine = SttEngine.SHERPA_ONNX.name,
                ),
            ),
        )
        assertEquals(
            ItemSttOverrideEntity(
                mediaId = mediaId,
                targetEngine = SttEngine.ML_KIT.name,
                baseEngine = SttEngine.SHERPA_ONNX.name,
            ),
            db.itemSttOverrideDao().observeByMediaId(mediaId).first(),
        )

        // media_item を削除すると ON DELETE CASCADE で全テーブルの関連行が消える
        db.mediaItemDao().deleteById(mediaId)
        assertNull(db.itemStateDao().getByMediaId(mediaId))
        assertEquals(emptyList(), db.analysisStateDao().getByMediaId(mediaId))
        assertEquals(emptyList(), db.chunkDao().getByMediaIdAndOrigin(mediaId, "IMPORTED"))
        assertEquals(emptyList(), db.transcriptDao().observeByMediaId(mediaId).first())
        assertEquals(emptyList(), db.bookmarkDao().getByMediaId(mediaId))
        assertEquals(emptyList(), db.driveFileDao().getAll())
        assertNull(db.itemSttOverrideDao().getByMediaId(mediaId))
    }

    @Test
    fun `削除済みの教材の状態は書かずにfalseを返し外部キー違反も起こさない`() = runTest {
        db.mediaItemDao().upsert(
            MediaItemEntity(
                id = mediaId,
                title = "Lesson 02",
                durationMs = 60_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1_000_000L,
                sha256 = "d".repeat(64),
                course = "korean-course",
                targetLang = "ko",
                baseLang = "ja",
                transcriptOffsetMs = 0,
                importedAt = 1_000L,
            ),
        )
        assertTrue(db.itemStateDao().updatePlaybackPosition(mediaId, positionMs = 1_000L, speed = 1.0f, updatedAt = 2_000L))
        assertTrue(
            db.itemSttOverrideDao().setIfMediaExists(
                ItemSttOverrideEntity(mediaId = mediaId, targetEngine = SttEngine.ML_KIT.name, baseEngine = null),
            ),
        )
        // 両方 null を渡すと行が消える
        assertTrue(
            db.itemSttOverrideDao().setIfMediaExists(
                ItemSttOverrideEntity(mediaId = mediaId, targetEngine = null, baseEngine = null),
            ),
        )
        assertNull(db.itemSttOverrideDao().getByMediaId(mediaId))

        db.mediaItemDao().deleteById(mediaId)
        assertFalse(db.itemStateDao().mediaItemExists(mediaId))
        assertFalse(db.itemSttOverrideDao().mediaItemExists(mediaId))

        // この環境でも外部キーが効いている（削除済みの教材の行をそのまま書くと失敗する）
        assertFailsWith<SQLiteException> {
            db.itemStateDao().upsert(ItemStateEntity(mediaId = mediaId, updatedAt = 3_000L))
        }
        assertFailsWith<SQLiteException> {
            db.itemSttOverrideDao().upsert(ItemSttOverrideEntity(mediaId = mediaId, targetEngine = SttEngine.ML_KIT.name))
        }

        // 再生中に削除された教材について、プレイヤーが位置・聴取済み・学習モード・STT上書きを保存しようとしても、書かずに false を返す
        assertFalse(db.itemStateDao().updatePlaybackPosition(mediaId, positionMs = 2_000L, speed = 1.0f, updatedAt = 4_000L))
        assertFalse(db.itemStateDao().updateListenedStatus(mediaId = mediaId, isListened = true, updatedAt = 5_000L))
        assertFalse(db.itemStateDao().upsertIfMediaExists(ItemStateEntity(mediaId = mediaId, updatedAt = 6_000L)))
        assertFalse(
            db.itemSttOverrideDao().setIfMediaExists(
                ItemSttOverrideEntity(mediaId = mediaId, targetEngine = SttEngine.ML_KIT.name, baseEngine = null),
            ),
        )
        assertNull(db.itemStateDao().getByMediaId(mediaId))
        assertNull(db.itemSttOverrideDao().getByMediaId(mediaId))
    }

    @Test
    fun `MIGRATION_1_2_から_4_5_まで適用したDBをRoom_v5で開いて読み書きできる`() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val dbName = "migration_test_${System.nanoTime()}.db"
        val v1Helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `media_item` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `duration_ms` INTEGER NOT NULL, `mime_type` TEXT NOT NULL, `size_bytes` INTEGER NOT NULL, `sha256` TEXT NOT NULL, `course` TEXT, `target_lang` TEXT NOT NULL, `base_lang` TEXT, `transcript_offset_ms` INTEGER NOT NULL, `imported_at` INTEGER NOT NULL, `mp3_has_header` INTEGER NOT NULL DEFAULT 0, `mp3_samples_per_frame` INTEGER NOT NULL DEFAULT 1152, `mp3_encoder_delay` INTEGER NOT NULL DEFAULT 576, PRIMARY KEY(`id`))",
                            )
                            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_media_item_sha256` ON `media_item` (`sha256`)")
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `item_state` (`media_id` TEXT NOT NULL, `position_ms` INTEGER NOT NULL, `speed` REAL NOT NULL, `chunk_source` TEXT NOT NULL, `auto_pause` TEXT NOT NULL, `auto_pause_scope` TEXT NOT NULL, `language_filter` TEXT NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`media_id`), FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                            )
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `analysis_state` (`media_id` TEXT NOT NULL, `stage` TEXT NOT NULL, `status` TEXT NOT NULL, `progress` REAL, `error` TEXT, `algorithm_version` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, PRIMARY KEY(`media_id`, `stage`), FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                            )
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `chunk` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `media_id` TEXT NOT NULL, `start_ms` INTEGER NOT NULL, `end_ms` INTEGER NOT NULL, `origin` TEXT NOT NULL, `algorithm_version` INTEGER NOT NULL, FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                            )
                            db.execSQL("CREATE INDEX IF NOT EXISTS `index_chunk_media_id_start_ms` ON `chunk` (`media_id`, `start_ms`)")
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `transcript` (`chunk_id` INTEGER NOT NULL, `text` TEXT NOT NULL, `text_variants` TEXT NOT NULL, `language` TEXT, `language_confidence` REAL, `words` TEXT NOT NULL, `engine` TEXT NOT NULL, `user_edited` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, PRIMARY KEY(`chunk_id`), FOREIGN KEY(`media_id`) REFERENCES `chunk`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )".replace("FOREIGN KEY(`media_id`)", "FOREIGN KEY(`chunk_id`)"),
                            )
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `bookmark` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `media_id` TEXT NOT NULL, `position_ms` INTEGER NOT NULL, `note` TEXT NOT NULL, `created_at` INTEGER NOT NULL, FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                            )
                            db.execSQL("CREATE INDEX IF NOT EXISTS `index_bookmark_media_id_position_ms` ON `bookmark` (`media_id`, `position_ms`)")
                            db.execSQL(
                                "CREATE TABLE IF NOT EXISTS `drive_file` (`drive_file_id` TEXT NOT NULL, `kind` TEXT NOT NULL, `course` TEXT NOT NULL, `modified_time` INTEGER NOT NULL, `sha256` TEXT, `media_id` TEXT, PRIMARY KEY(`drive_file_id`), FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                            )
                            db.execSQL("CREATE INDEX IF NOT EXISTS `index_drive_file_media_id` ON `drive_file` (`media_id`)")
                            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
                            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ed9f4d5d606130efa2c10e6a66a9411f')")
                            db.execSQL(
                                "INSERT INTO `media_item` (`id`, `title`, `duration_ms`, `mime_type`, `size_bytes`, `sha256`, `course`, `target_lang`, `base_lang`, `transcript_offset_ms`, `imported_at`) VALUES ('$mediaId', 'Migrated', 10000, 'audio/mpeg', 1000, '${"e".repeat(64)}', 'c', 'ko', 'ja', 0, 100)",
                            )
                            db.execSQL(
                                "INSERT INTO `item_state` (`media_id`, `position_ms`, `speed`, `chunk_source`, `auto_pause`, `auto_pause_scope`, `language_filter`, `updated_at`) VALUES ('$mediaId', 4500, 1.0, 'AUTO', 'OFF', 'ALL_CHUNKS', 'ALL', 200)",
                            )
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        v1Helper.writableDatabase.close()
        v1Helper.close()

        val migratedDb = Room.databaseBuilder(context, ChunkFlowDatabase::class.java, dbName)
            .addMigrations(*ChunkFlowDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val state = migratedDb.itemStateDao().getByMediaId(mediaId)
            assertEquals(4_500L, state?.positionMs)
            assertEquals(4_500L, state?.maxPositionMs)
            assertEquals(false, state?.isListened)
            assertEquals("APP_MANAGED", migratedDb.mediaItemDao().getById(mediaId)?.storageState)

            migratedDb.courseFolderDao().upsert(
                CourseFolderEntity(
                    coursePath = "imported",
                    kind = "VIRTUAL_DIRECTORY",
                    targetLang = "ko",
                    baseLang = "ja",
                ),
            )
            assertEquals("VIRTUAL_DIRECTORY", migratedDb.courseFolderDao().getByPath("imported")?.kind)

            assertTrue(
                migratedDb.itemSttOverrideDao().setIfMediaExists(
                    ItemSttOverrideEntity(mediaId = mediaId, targetEngine = SttEngine.ML_KIT.name, baseEngine = SttEngine.SPEECH_RECOGNIZER.name),
                ),
            )
            assertEquals(
                SttEngine.ML_KIT.name,
                migratedDb.itemSttOverrideDao().getByMediaId(mediaId)?.targetEngine,
            )

            migratedDb.playlistDao().addItemsToPlaylist(
                playlist = com.miuraken.chunkflow.core.database.entity.PlaylistEntity(
                    id = "user:1",
                    name = "朝リスト",
                    isBuiltIn = false,
                    sortOrder = 3,
                    createdAt = 500L,
                ),
                mediaIds = listOf(mediaId),
                addedAt = 600L,
            )
            val playlists = migratedDb.playlistDao().getPlaylists()
            assertEquals(1, playlists.size)
            assertEquals("朝リスト", playlists.single().name)
            val playlistItems = migratedDb.playlistDao().getItemsByPlaylistId("user:1")
            assertEquals(listOf(mediaId), playlistItems.map { it.mediaId })

            migratedDb.itemStateDao().updateFileSortOrders(listOf(mediaId))
            assertEquals(0, migratedDb.itemStateDao().getByMediaId(mediaId)?.fileSortOrder)
        } finally {
            migratedDb.close()
            context.deleteDatabase(dbName)
        }
    }
}
