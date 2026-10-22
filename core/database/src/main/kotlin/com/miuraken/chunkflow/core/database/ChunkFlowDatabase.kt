package com.miuraken.chunkflow.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.miuraken.chunkflow.core.database.dao.AnalysisStateDao
import com.miuraken.chunkflow.core.database.dao.BookmarkDao
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.CourseFolderDao
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.ItemSttOverrideDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.dao.PlaylistDao
import com.miuraken.chunkflow.core.database.dao.TagDao
import com.miuraken.chunkflow.core.database.dao.TranscriptDao
import com.miuraken.chunkflow.core.database.entity.AnalysisStateEntity
import com.miuraken.chunkflow.core.database.entity.BookmarkEntity
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.CourseFolderEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.ItemSttOverrideEntity
import com.miuraken.chunkflow.core.database.entity.LanguageColorEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemTagEntity
import com.miuraken.chunkflow.core.database.entity.PlaylistEntity
import com.miuraken.chunkflow.core.database.entity.PlaylistItemEntity
import com.miuraken.chunkflow.core.database.entity.TagEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import com.miuraken.chunkflow.core.model.LibraryFolderColors

/**
 * Chunk Flow の Room データベース（§6.4）。
 *
 * スキーマは `core/database/schemas/` に出力してバージョン管理する。
 */
@Database(
    entities = [
        MediaItemEntity::class,
        ItemStateEntity::class,
        AnalysisStateEntity::class,
        ChunkEntity::class,
        TranscriptEntity::class,
        BookmarkEntity::class,
        ItemSttOverrideEntity::class,
        CourseFolderEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        LanguageColorEntity::class,
        TagEntity::class,
        MediaItemTagEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class ChunkFlowDatabase : RoomDatabase() {
    /** `media_item` テーブルの DAO。 */
    abstract fun mediaItemDao(): MediaItemDao

    /** `item_state` テーブルの DAO。 */
    abstract fun itemStateDao(): ItemStateDao

    /** `analysis_state` テーブルの DAO。 */
    abstract fun analysisStateDao(): AnalysisStateDao

    /** `chunk` テーブルの DAO。 */
    abstract fun chunkDao(): ChunkDao

    /** `transcript` テーブルの DAO。 */
    abstract fun transcriptDao(): TranscriptDao

    /** `bookmark` テーブルの DAO。 */
    abstract fun bookmarkDao(): BookmarkDao

    /** `item_stt_override` テーブルの DAO（ADR-0011 §2.3）。 */
    abstract fun itemSttOverrideDao(): ItemSttOverrideDao

    /** `course_folder` / `language_color` テーブルの DAO。 */
    abstract fun courseFolderDao(): CourseFolderDao

    /** `library_playlist` / `library_playlist_item` テーブルの DAO。 */
    abstract fun playlistDao(): PlaylistDao

    /** `tag` / `media_item_tag` テーブルの DAO。 */
    abstract fun tagDao(): TagDao

    companion object {
        /** データベースファイル名（`data_extraction_rules.xml` の `database` ドメイン対象）。 */
        const val DATABASE_NAME: String = "chunkflow.db"

        /** バージョン 1 から 2 へのマイグレーション（`item_state` に `is_listened` 列を追加）。 */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE item_state ADD COLUMN is_listened INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** バージョン 2 から 3 へのマイグレーション（`item_state` に `max_position_ms` 列を追加し、既存の `position_ms` を引き継ぐ）。 */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE item_state ADD COLUMN max_position_ms INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE item_state SET max_position_ms = position_ms WHERE position_ms > max_position_ms")
            }
        }

        /** バージョン 3 から 4 へのマイグレーション（教材ごとの文字起こしエンジンの上書き `item_stt_override` を追加。ADR-0011 §2.3）。 */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `item_stt_override` (" +
                        "`media_id` TEXT NOT NULL, `target_engine` TEXT, `base_engine` TEXT, " +
                        "PRIMARY KEY(`media_id`), " +
                        "FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        /** バージョン 4 から 5 へのマイグレーション（`media_item` にソース参照・保持状態列、`chunk` に `is_skipped` 列、`transcript` に翻訳列、`course_folder` テーブルを追加）。 */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `media_item` ADD COLUMN `source_uri` TEXT")
                db.execSQL("ALTER TABLE `media_item` ADD COLUMN `local_file_path` TEXT")
                db.execSQL("ALTER TABLE `media_item` ADD COLUMN `storage_state` TEXT NOT NULL DEFAULT 'APP_MANAGED'")
                db.execSQL("ALTER TABLE `chunk` ADD COLUMN `is_skipped` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `transcript` ADD COLUMN `translated_text` TEXT")
                db.execSQL("ALTER TABLE `transcript` ADD COLUMN `translated_lang` TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `course_folder` (" +
                        "`course_path` TEXT NOT NULL, `kind` TEXT NOT NULL, `target_lang` TEXT NOT NULL, " +
                        "`base_lang` TEXT, `tree_uri` TEXT, `drive_folder_id` TEXT, " +
                        "`last_scanned_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`course_path`))",
                )
            }
        }

        /** バージョン 5 から 6 へのマイグレーション（`item_state` にプレイリスト・個別分割・並び順列、`library_playlist` / `library_playlist_item` テーブルを追加）。 */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `item_state` ADD COLUMN `is_favorite` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `item_state` ADD COLUMN `queued_at` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `item_state` ADD COLUMN `access_count` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `item_state` ADD COLUMN `custom_segmentation` TEXT")
                db.execSQL("ALTER TABLE `item_state` ADD COLUMN `file_sort_order` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_playlist` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`is_builtin` INTEGER NOT NULL DEFAULT 0, " +
                        "`sort_order` INTEGER NOT NULL DEFAULT 0, " +
                        "`created_at` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `library_playlist_item` (" +
                        "`playlist_id` TEXT NOT NULL, `media_id` TEXT NOT NULL, " +
                        "`sort_order` INTEGER NOT NULL DEFAULT 0, " +
                        "`added_at` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`playlist_id`, `media_id`), " +
                        "FOREIGN KEY(`playlist_id`) REFERENCES `library_playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_library_playlist_item_playlist_id_sort_order` " +
                        "ON `library_playlist_item` (`playlist_id`, `sort_order`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_library_playlist_item_media_id` " +
                        "ON `library_playlist_item` (`media_id`)",
                )
            }
        }

        /** バージョン 6 から 7 へのマイグレーション（`drive_file` テーブルと `course_folder.drive_folder_id` 列を削除）。 */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `drive_file`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `course_folder_new` (" +
                        "`course_path` TEXT NOT NULL, `kind` TEXT NOT NULL, `target_lang` TEXT NOT NULL, " +
                        "`base_lang` TEXT, `tree_uri` TEXT, " +
                        "`last_scanned_at` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`course_path`))",
                )
                db.execSQL(
                    "INSERT INTO `course_folder_new` (`course_path`, `kind`, `target_lang`, `base_lang`, `tree_uri`, `last_scanned_at`) " +
                        "SELECT `course_path`, `kind`, `target_lang`, `base_lang`, `tree_uri`, `last_scanned_at` " +
                        "FROM `course_folder` WHERE `kind` != 'GOOGLE_DRIVE_FOLDER'",
                )
                db.execSQL("DROP TABLE `course_folder`")
                db.execSQL("ALTER TABLE `course_folder_new` RENAME TO `course_folder`")
            }
        }

        /**
         * バージョン 7 から 8 へのマイグレーション（ファイルのタグ `tag` / `media_item_tag`、フォルダの色の指定
         * `course_folder.color_index`、対象言語ごとの色の順番 `language_color` を追加）。
         *
         * 既存のフォルダとファイルの対象言語には、フォルダが作られた順 → ファイルが取り込まれた順に色の順番を割り当てる。
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `course_folder` ADD COLUMN `color_index` INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `language_color` (" +
                        "`language` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, PRIMARY KEY(`language`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tag` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`name_key` TEXT NOT NULL, `created_at` INTEGER NOT NULL DEFAULT 0)",
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tag_name_key` ON `tag` (`name_key`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `media_item_tag` (" +
                        "`media_id` TEXT NOT NULL, `tag_id` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`media_id`, `tag_id`), " +
                        "FOREIGN KEY(`media_id`) REFERENCES `media_item`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`tag_id`) REFERENCES `tag`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_item_tag_tag_id` ON `media_item_tag` (`tag_id`)")
                seedLanguageColors(db)
            }
        }

        /** バージョン 8 から 9 へのマイグレーション（フォルダ単位の音声認識エンジン上書き `course_folder.target_stt_engine` / `base_stt_engine` を追加）。 */
        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `course_folder` ADD COLUMN `target_stt_engine` TEXT")
                db.execSQL("ALTER TABLE `course_folder` ADD COLUMN `base_stt_engine` TEXT")
            }
        }

        /** アプリで使うすべてのマイグレーション（古い順）。データベースを開くときに渡す。 */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
        )

        /**
         * 既存のフォルダとファイルの対象言語に、色の順番（0 始まり）を割り当てる。
         *
         * フォルダが作られた順（`course_folder` の rowid 順）→ ファイルが取り込まれた順（`imported_at` 順）に、
         * 初めて出てきた対象言語から順番を付ける。キーは [LibraryFolderColors.languageKey] でそろえる。
         */
        private fun seedLanguageColors(db: SupportSQLiteDatabase) {
            val languageKeys = LinkedHashSet<String>()
            val queries = listOf(
                "SELECT `target_lang` FROM `course_folder` ORDER BY rowid ASC",
                "SELECT `target_lang` FROM `media_item` ORDER BY `imported_at` ASC, rowid ASC",
            )
            for (query in queries) {
                db.query(query).use { cursor ->
                    while (cursor.moveToNext()) {
                        val rawLanguage = if (cursor.isNull(0)) null else cursor.getString(0)
                        rawLanguage?.let(LibraryFolderColors::languageKey)?.let(languageKeys::add)
                    }
                }
            }
            languageKeys.forEachIndexed { ordinal, key ->
                db.execSQL(
                    "INSERT OR IGNORE INTO `language_color` (`language`, `ordinal`) VALUES (?, ?)",
                    arrayOf<Any?>(key, ordinal),
                )
            }
        }
    }
}
