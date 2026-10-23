package com.miuraken.chunkflow.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.miuraken.chunkflow.core.database.entity.toEntity
import com.miuraken.chunkflow.core.database.entity.toExternalModel
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * バージョン 9 から 10 へのマイグレーション（[ChunkFlowDatabase.MIGRATION_9_10]）のテスト。
 *
 * [Migration8To9Test] と同じく、書き出し済みのスキーマ `schemas/.../9.json` の `createSql` からバージョン 9 の
 * データベースを作り、Room で開いて移行させる。Room は移行後にスキーマをバージョン 10 のエンティティと照合し、
 * 食い違えば開けない（`IllegalStateException`）。
 */
@RunWith(RobolectricTestRunner::class)
class Migration9To10Test {
    private lateinit var context: Context
    private val dbName = "migration_9_10_${System.nanoTime()}.db"

    @BeforeTest
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @AfterTest
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `MIGRATION_9_10_既存の教材を残したまま元のファイル名は空で移行する`() = runTest {
        createVersion9Database { db ->
            insertMediaItem(
                db,
                id = "local",
                sha256 = "sha-local",
                sourceUri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2F01.mp3",
                localFilePath = "/storage/emulated/0/Music/01.mp3",
            )
            insertMediaItem(db, id = "copied", sha256 = "sha-copied", sourceUri = null, localFilePath = null)
        }

        val migrated = openMigratedDatabase()
        try {
            val dao = migrated.mediaItemDao()
            val local = assertNotNull(dao.getById("local"))
            assertEquals("タイトル local", local.title)
            assertEquals("/storage/emulated/0/Music/01.mp3", local.localFilePath)
            assertNull(local.displayName)
            assertNull(local.toExternalModel().displayName)

            // 元のファイルを参照している教材だけが補完の対象になる
            assertEquals(listOf("local"), dao.getItemsMissingDisplayName().map { it.id })
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `MIGRATION_9_10_移行後に元のファイル名を保存でき教材の書き込みで受け渡せる`() = runTest {
        createVersion9Database { db ->
            insertMediaItem(
                db,
                id = "drive",
                sha256 = "sha-drive",
                sourceUri = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3Dxyz",
                localFilePath = null,
            )
        }

        val migrated = openMigratedDatabase()
        try {
            val dao = migrated.mediaItemDao()
            assertEquals(1, dao.updateDisplayName("drive", "ピンズラー韓国語01.mp3"))
            assertEquals("ピンズラー韓国語01.mp3", dao.getById("drive")?.displayName)
            assertEquals(emptyList(), dao.getItemsMissingDisplayName())

            // ドメインモデルを経由した書き込み（再スキャンなど）でも元のファイル名を受け渡す
            val item = assertNotNull(dao.getById("drive")).toExternalModel()
            assertEquals("ピンズラー韓国語01.mp3", item.displayName)
            dao.upsert(item.copy(displayName = "ピンズラー韓国語01 (1).mp3").toEntity())
            assertEquals("ピンズラー韓国語01 (1).mp3", dao.getById("drive")?.displayName)

            // 行のない教材は書き換えない
            assertEquals(0, dao.updateDisplayName("missing", "x.mp3"))
        } finally {
            migrated.close()
        }
    }

    private fun openMigratedDatabase(): ChunkFlowDatabase = Room.databaseBuilder(context, ChunkFlowDatabase::class.java, dbName)
        .addMigrations(*ChunkFlowDatabase.ALL_MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    /** 書き出し済みの `9.json` の `createSql` でバージョン 9 のデータベースを作り、[seed] で行を入れる。 */
    private fun createVersion9Database(seed: (SupportSQLiteDatabase) -> Unit) {
        val statements = version9SchemaStatements()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(9) {
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

    private fun version9SchemaStatements(): List<String> {
        val relativePath = "schemas/com.miuraken.chunkflow.core.database.ChunkFlowDatabase/9.json"
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

    private fun insertMediaItem(
        db: SupportSQLiteDatabase,
        id: String,
        sha256: String,
        sourceUri: String?,
        localFilePath: String?,
    ) {
        db.execSQL(
            "INSERT INTO `media_item` (`id`, `title`, `duration_ms`, `mime_type`, `size_bytes`, `sha256`, `course`, " +
                "`target_lang`, `base_lang`, `transcript_offset_ms`, `imported_at`, `source_uri`, `local_file_path`, " +
                "`storage_state`) VALUES (?, ?, 1000, 'audio/mpeg', 1, ?, NULL, 'ko', 'ja', 0, 0, ?, ?, 'LOCAL_REFERENCE')",
            arrayOf<Any?>(id, "タイトル $id", sha256, sourceUri, localFilePath),
        )
    }

    private companion object {
        const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"
    }
}
