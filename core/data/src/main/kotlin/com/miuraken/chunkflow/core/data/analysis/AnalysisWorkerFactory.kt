package com.miuraken.chunkflow.core.data.analysis

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.miuraken.chunkflow.core.data.library.LibraryMediaFileResolver
import com.miuraken.chunkflow.core.database.dao.AnalysisStateDao
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AnalysisWorker] に依存性を注入して生成する [WorkerFactory]（§7.3.4）。
 */
@Singleton
class AnalysisWorkerFactory(
    private val audioStreamDecoder: AudioStreamDecoder,
    private val analysisStateDao: AnalysisStateDao,
    private val chunkDao: ChunkDao,
    private val mediaItemDao: MediaItemDao,
    private val mediaFileResolver: LibraryMediaFileResolver,
    private val userSettingsRepository: UserSettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : WorkerFactory() {

    @Inject
    constructor(
        audioStreamDecoder: AudioStreamDecoder,
        analysisStateDao: AnalysisStateDao,
        chunkDao: ChunkDao,
        mediaItemDao: MediaItemDao,
        mediaFileResolver: LibraryMediaFileResolver,
        userSettingsRepository: UserSettingsRepository,
    ) : this(
        audioStreamDecoder = audioStreamDecoder,
        analysisStateDao = analysisStateDao,
        chunkDao = chunkDao,
        mediaItemDao = mediaItemDao,
        mediaFileResolver = mediaFileResolver,
        userSettingsRepository = userSettingsRepository,
        clock = System::currentTimeMillis,
    )

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        return when (workerClassName) {
            AnalysisWorker::class.java.name -> AnalysisWorker(
                appContext = appContext,
                workerParams = workerParameters,
                audioStreamDecoder = audioStreamDecoder,
                analysisStateDao = analysisStateDao,
                chunkDao = chunkDao,
                mediaItemDao = mediaItemDao,
                mediaFileResolver = mediaFileResolver,
                userSettingsRepository = userSettingsRepository,
                clock = clock,
            )
            else -> null
        }
    }
}
