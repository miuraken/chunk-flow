package com.miuraken.chunkflow

import android.app.Application
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import androidx.work.WorkerFactory
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Chunk Flow のアプリケーション。Hilt の DI の起点であり、WorkManager の [Configuration.Provider] を実装する（§7.3.4）。
 */
@HiltAndroidApp
class ChunkFlowApplication :
    Application(),
    Configuration.Provider {

    @Inject
    lateinit var workerFactories: Set<@JvmSuppressWildcards WorkerFactory>

    override val workManagerConfiguration: Configuration
        get() {
            val delegatingFactory = DelegatingWorkerFactory().apply {
                workerFactories.forEach(::addFactory)
            }
            return Configuration.Builder()
                .setWorkerFactory(delegatingFactory)
                .build()
        }
}
