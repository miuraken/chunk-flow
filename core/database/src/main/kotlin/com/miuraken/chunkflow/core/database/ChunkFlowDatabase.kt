package com.miuraken.chunkflow.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.miuraken.chunkflow.core.database.dao.AnalysisStateDao
import com.miuraken.chunkflow.core.database.dao.BookmarkDao
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.DriveFileDao
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.ItemSttOverrideDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.dao.TranscriptDao
import com.miuraken.chunkflow.core.database.entity.AnalysisStateEntity
import com.miuraken.chunkflow.core.database.entity.BookmarkEntity
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.DriveFileEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.ItemSttOverrideEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity

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
        DriveFileEntity::class,
        ItemSttOverrideEntity::class,
    ],
    version = 4,
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

    /** `drive_file` テーブルの DAO。 */
    abstract fun driveFileDao(): DriveFileDao

    /** `item_stt_override` テーブルの DAO（ADR-0011 §2.3）。 */
    abstract fun itemSttOverrideDao(): ItemSttOverrideDao

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

        /** アプリで使うすべてのマイグレーション（古い順）。データベースを開くときに渡す。 */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
