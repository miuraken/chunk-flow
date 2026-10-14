package com.miuraken.chunkflow.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.miuraken.chunkflow.core.database.dao.AnalysisStateDao
import com.miuraken.chunkflow.core.database.dao.BookmarkDao
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.DriveFileDao
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.dao.TranscriptDao
import com.miuraken.chunkflow.core.database.entity.AnalysisStateEntity
import com.miuraken.chunkflow.core.database.entity.BookmarkEntity
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.DriveFileEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
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
    ],
    version = 1,
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

    companion object {
        /** データベースファイル名（`data_extraction_rules.xml` の `database` ドメイン対象）。 */
        const val DATABASE_NAME: String = "chunkflow.db"
    }
}
