package com.miuraken.chunkflow.core.database.di

import android.content.Context
import androidx.room.Room
import com.miuraken.chunkflow.core.database.ChunkFlowDatabase
import com.miuraken.chunkflow.core.database.dao.AnalysisStateDao
import com.miuraken.chunkflow.core.database.dao.BookmarkDao
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.DriveFileDao
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.ItemSttOverrideDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.dao.TranscriptDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** [ChunkFlowDatabase] と各 DAO を提供する Hilt モジュール（§6.4）。 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    /** アプリ全体で共有する [ChunkFlowDatabase] を提供する。 */
    @Provides
    @Singleton
    fun providesChunkFlowDatabase(
        @ApplicationContext context: Context,
    ): ChunkFlowDatabase = Room.databaseBuilder(
        context,
        ChunkFlowDatabase::class.java,
        ChunkFlowDatabase.DATABASE_NAME,
    )
        .addMigrations(*ChunkFlowDatabase.ALL_MIGRATIONS)
        .build()

    /** [MediaItemDao] を提供する。 */
    @Provides
    fun providesMediaItemDao(database: ChunkFlowDatabase): MediaItemDao = database.mediaItemDao()

    /** [ItemStateDao] を提供する。 */
    @Provides
    fun providesItemStateDao(database: ChunkFlowDatabase): ItemStateDao = database.itemStateDao()

    /** [AnalysisStateDao] を提供する。 */
    @Provides
    fun providesAnalysisStateDao(database: ChunkFlowDatabase): AnalysisStateDao = database.analysisStateDao()

    /** [ChunkDao] を提供する。 */
    @Provides
    fun providesChunkDao(database: ChunkFlowDatabase): ChunkDao = database.chunkDao()

    /** [TranscriptDao] を提供する。 */
    @Provides
    fun providesTranscriptDao(database: ChunkFlowDatabase): TranscriptDao = database.transcriptDao()

    /** [BookmarkDao] を提供する。 */
    @Provides
    fun providesBookmarkDao(database: ChunkFlowDatabase): BookmarkDao = database.bookmarkDao()

    /** [DriveFileDao] を提供する。 */
    @Provides
    fun providesDriveFileDao(database: ChunkFlowDatabase): DriveFileDao = database.driveFileDao()

    /** [ItemSttOverrideDao] を提供する（ADR-0011 §2.3）。 */
    @Provides
    fun providesItemSttOverrideDao(database: ChunkFlowDatabase): ItemSttOverrideDao = database.itemSttOverrideDao()
}
