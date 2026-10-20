package com.miuraken.chunkflow.core.data.analysis

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.miuraken.chunkflow.core.audio.SpeechProbabilityStreamFactory
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
    private val speechProbabilityStreamFactory: SpeechProbabilityStreamFactory? = null,
) : WorkerFactory() {

    @Inject
    constructor(
        audioStreamDecoder: AudioStreamDecoder,
        analysisStateDao: AnalysisStateDao,
        chunkDao: ChunkDao,
        mediaItemDao: MediaItemDao,
        mediaFileResolver: LibraryMediaFileResolver,
        userSettingsRepository: UserSettingsRepository,
        speechProbabilityStreamFactory: SpeechProbabilityStreamFactory,
    ) : this(
        audioStreamDecoder = audioStreamDecoder,
        analysisStateDao = analysisStateDao,
        chunkDao = chunkDao,
        mediaItemDao = mediaItemDao,
        mediaFileResolver = mediaFileResolver,
        userSettingsRepository = userSettingsRepository,
        clock = System::currentTimeMillis,
        speechProbabilityStreamFactory = speechProbabilityStreamFactory,
    )

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
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
            speechProbabilityStreamFactory = speechProbabilityStreamFactory,
        )

        else -> null
    }
}
