package com.miuraken.chunkflow.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * バージョン 7 から 8 へのマイグレーション（[ChunkFlowDatabase.MIGRATION_7_8]）のテスト。
 *
 * `room-testing`（`MigrationTestHelper`）は依存に入っていないため、同じ考え方で、書き出し済みのスキーマ
 * `schemas/.../7.json` の `createSql` からバージョン 7 のデータベースを作り、Room で開いて移行させる。
 * Room は移行後にスキーマをバージョン 8 のエンティティと照合し、食い違えば開けない（`IllegalStateException`）。
 */
@RunWith(RobolectricTestRunner::class)
class Migration7To8Test {
    private lateinit var context: Context
    private val dbName = "migration_7_8_${System.nanoTime()}.db"

    @BeforeTest
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @AfterTest
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `MIGRATION_7_8_スキーマ7のDBを移行するとスキーマ8になり既存の対象言語に追加された順で色の順番が付く`() = runTest {
        createVersion7Database { db ->
            insertFolder(db, path = "韓国語", targetLang = "ko")
            insertFolder(db, path = "中国語", targetLang = "zh")
            insertFolder(db, path = "韓国語/上級", targetLang = "KO")
            insertFolder(db, path = "英語", targetLang = "en")
            insertMediaItem(db, id = MEDIA_ID, sha = "a", targetLang = "ko", importedAt = 100L)
            insertMediaItem(db, id = "00000000-0000-4000-8000-000000000081", sha = "b", targetLang = "fr", importedAt = 300L)
            insertMediaItem(db, id = "00000000-0000-4000-8000-000000000082", sha = "c", targetLang = "de", importedAt = 200L)
        }

        val migrated = Room.databaseBuilder(context, ChunkFlowDatabase::class.java, dbName)
            .addMigrations(*ChunkFlowDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val folderDao = migrated.courseFolderDao()
            // フォルダが作られた順 → ファイルが取り込まれた順（キーはカタログの言語コードにそろえる）
            assertEquals(
                listOf("ko" to 0, "zh-cn" to 1, "en" to 2, "de" to 3, "fr" to 4),
                folderDao.observeLanguageColors().first().map { it.language to it.ordinal },
            )
            // 既存のフォルダは色の指定なし（既定の色）
            assertNull(folderDao.getByPath("韓国語")?.colorIndex)
            assertEquals("ko", folderDao.getByPath("韓国語")?.targetLang)

            // 新しい表（タグ）が使え、既存のファイルに付けられる
            assertTrue(migrated.tagDao().replaceTagsForItem(MEDIA_ID, listOf("English"), createdAt = 10L))
            assertEquals(listOf("English"), migrated.tagDao().observeUsedTags().first().map { it.name })

            // 移行後に新しい対象言語のフォルダを作ると、続きの順番が付く
            folderDao.upsert(
                com.miuraken.chunkflow.core.database.entity.CourseFolderEntity(
                    coursePath = "スペイン語",
                    kind = "VIRTUAL_DIRECTORY",
                    targetLang = "es",
                ),
            )
            assertEquals(5, folderDao.getLanguageColor("es")?.ordinal)
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `MIGRATION_7_8_フォルダもファイルもないDBでも移行でき色の順番は空`() = runTest {
        createVersion7Database { }

        val migrated = Room.databaseBuilder(context, ChunkFlowDatabase::class.java, dbName)
            .addMigrations(*ChunkFlowDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals(emptyList(), migrated.courseFolderDao().observeLanguageColors().first())
            assertEquals(emptyList(), migrated.tagDao().observeUsedTags().first())
        } finally {
            migrated.close()
        }
    }

    /** 書き出し済みの `7.json` の `createSql` でバージョン 7 のデータベースを作り、[seed] で行を入れる。 */
    private fun createVersion7Database(seed: (SupportSQLiteDatabase) -> Unit) {
        val statements = version7SchemaStatements()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            statements.forEach(db::execSQL)
                            seed(db)
                        }

                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    },
                )
                .build(),
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun version7SchemaStatements(): List<String> {
        val relativePath = "schemas/com.miuraken.chunkflow.core.database.ChunkFlowDatabase/7.json"
        val schemaFile = listOf(File(relativePath), File("core/database/$relativePath")).first { it.isFile }
        val database = Json.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        val statements = mutableListOf<String>()
        for (entity in database.getValue("entities").jsonArray) {
            val entityObject = entity.jsonObject
            val tableName = entityObject.getValue("tableName").jsonPrimitive.content
            statements += entityObject.getValue("createSql").jsonPrimitive.content.replace(TABLE_NAME_PLACEHOLDER, tableName)
            entityObject["indices"]?.jsonArray?.forEach { index ->
                statements += index.jsonObject.getValue("createSql").jsonPrimitive.content.replace(TABLE_NAME_PLACEHOLDER, tableName)
            }
        }
        database["setupQueries"]?.jsonArray?.forEach { query -> statements += query.jsonPrimitive.content }
        return statements
    }

    private fun insertFolder(db: SupportSQLiteDatabase, path: String, targetLang: String) {
        db.execSQL(
            "INSERT INTO `course_folder` (`course_path`, `kind`, `target_lang`, `base_lang`, `tree_uri`, `last_scanned_at`) " +
                "VALUES (?, 'VIRTUAL_DIRECTORY', ?, 'ja', NULL, 0)",
            arrayOf<Any?>(path, targetLang),
        )
    }

    private fun insertMediaItem(db: SupportSQLiteDatabase, id: String, sha: String, targetLang: String, importedAt: Long) {
        db.execSQL(
            "INSERT INTO `media_item` (`id`, `title`, `duration_ms`, `mime_type`, `size_bytes`, `sha256`, `course`, " +
                "`target_lang`, `base_lang`, `transcript_offset_ms`, `imported_at`) " +
                "VALUES (?, 'Lesson', 10000, 'audio/mpeg', 1000, ?, NULL, ?, 'ja', 0, ?)",
            arrayOf<Any?>(id, sha.repeat(64), targetLang, importedAt),
        )
    }

    private companion object {
        const val MEDIA_ID = "00000000-0000-4000-8000-000000000080"
        const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"
    }
}
