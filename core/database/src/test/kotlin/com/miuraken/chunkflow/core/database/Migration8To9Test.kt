package com.miuraken.chunkflow.core.database

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.miuraken.chunkflow.core.database.entity.CourseFolderEntity
import com.miuraken.chunkflow.core.database.entity.toExternalModel
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.SttEngine
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
 * バージョン 8 から 9 へのマイグレーション（[ChunkFlowDatabase.MIGRATION_8_9]）のテスト。
 *
 * [Migration7To8Test] と同じく、書き出し済みのスキーマ `schemas/.../8.json` の `createSql` からバージョン 8 の
 * データベースを作り、Room で開いて移行させる。Room は移行後にスキーマをバージョン 9 のエンティティと照合し、
 * 食い違えば開けない（`IllegalStateException`）。
 */
@RunWith(RobolectricTestRunner::class)
class Migration8To9Test {
    private lateinit var context: Context
    private val dbName = "migration_8_9_${System.nanoTime()}.db"

    @BeforeTest
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @AfterTest
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `MIGRATION_8_9_既存のフォルダの設定を残したまま音声認識エンジンの上書きは空で移行する`() = runTest {
        createVersion8Database { db ->
            insertFolder(db, path = "韓国語", targetLang = "ko", colorIndex = 3)
            insertFolder(db, path = "韓国語/上級", targetLang = "ko", colorIndex = null)
        }

        val migrated = openMigratedDatabase()
        try {
            val folderDao = migrated.courseFolderDao()
            val korean = assertNotNull(folderDao.getByPath("韓国語"))
            assertEquals("ko", korean.targetLang)
            assertEquals("ja", korean.baseLang)
            assertEquals(3, korean.colorIndex)
            assertNull(korean.targetSttEngine)
            assertNull(korean.baseSttEngine)
            assertEquals(ItemSttOverride.NONE, korean.toExternalModel().sttOverride)

            val advanced = assertNotNull(folderDao.getByPath("韓国語/上級"))
            assertNull(advanced.colorIndex)
            assertEquals(ItemSttOverride.NONE, advanced.toExternalModel().sttOverride)
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `MIGRATION_8_9_移行後にフォルダの上書きを保存でき再スキャンの書き込みでは消えない`() = runTest {
        createVersion8Database { db ->
            insertFolder(db, path = "英語", targetLang = "en", colorIndex = null)
        }

        val migrated = openMigratedDatabase()
        try {
            val folderDao = migrated.courseFolderDao()
            assertEquals(
                1,
                folderDao.updateSttOverride(
                    coursePath = "英語",
                    targetEngine = SttEngine.SHERPA_ONNX.name,
                    baseEngine = SttEngine.ML_KIT.name,
                ),
            )
            val expected = ItemSttOverride(targetEngine = SttEngine.SHERPA_ONNX, baseEngine = SttEngine.ML_KIT)
            assertEquals(expected, folderDao.getByPath("英語")?.toExternalModel()?.sttOverride)

            // 再スキャンなどで上書きの列が空の行を書き込んでも、保存済みの上書きは残る
            folderDao.upsert(
                CourseFolderEntity(
                    coursePath = "英語",
                    kind = "VIRTUAL_DIRECTORY",
                    targetLang = "en",
                    baseLang = "ja",
                    lastScannedAt = 100L,
                ),
            )
            assertEquals(expected, folderDao.getByPath("英語")?.toExternalModel()?.sttOverride)
            assertEquals(100L, folderDao.getByPath("英語")?.lastScannedAt)

            // 両方を空にすると全体設定に従う状態に戻る
            folderDao.updateSttOverride(coursePath = "英語", targetEngine = null, baseEngine = null)
            assertEquals(ItemSttOverride.NONE, folderDao.getByPath("英語")?.toExternalModel()?.sttOverride)

            // 行のないフォルダは書き換えない
            assertEquals(0, folderDao.updateSttOverride("ない", SttEngine.ML_KIT.name, null))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun `MIGRATION_8_9_知らないエンジン名が入っていても上書きなしとして読む`() = runTest {
        createVersion8Database { db ->
            insertFolder(db, path = "中国語", targetLang = "zh", colorIndex = null)
        }

        val migrated = openMigratedDatabase()
        try {
            val folderDao = migrated.courseFolderDao()
            folderDao.updateSttOverride(coursePath = "中国語", targetEngine = "UNKNOWN_ENGINE", baseEngine = null)
            assertEquals(ItemSttOverride.NONE, folderDao.getByPath("中国語")?.toExternalModel()?.sttOverride)
        } finally {
            migrated.close()
        }
    }

    private fun openMigratedDatabase(): ChunkFlowDatabase = Room.databaseBuilder(context, ChunkFlowDatabase::class.java, dbName)
        .addMigrations(*ChunkFlowDatabase.ALL_MIGRATIONS)
        .allowMainThreadQueries()
        .build()

    /** 書き出し済みの `8.json` の `createSql` でバージョン 8 のデータベースを作り、[seed] で行を入れる。 */
    private fun createVersion8Database(seed: (SupportSQLiteDatabase) -> Unit) {
        val statements = version8SchemaStatements()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(8) {
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

    private fun version8SchemaStatements(): List<String> {
        val relativePath = "schemas/com.miuraken.chunkflow.core.database.ChunkFlowDatabase/8.json"
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

    private fun insertFolder(db: SupportSQLiteDatabase, path: String, targetLang: String, colorIndex: Int?) {
        db.execSQL(
            "INSERT INTO `course_folder` (`course_path`, `kind`, `target_lang`, `base_lang`, `tree_uri`, `last_scanned_at`, `color_index`) " +
                "VALUES (?, 'VIRTUAL_DIRECTORY', ?, 'ja', NULL, 0, ?)",
            arrayOf<Any?>(path, targetLang, colorIndex),
        )
    }

    private companion object {
        const val TABLE_NAME_PLACEHOLDER = "\${TABLE_NAME}"
    }
}
