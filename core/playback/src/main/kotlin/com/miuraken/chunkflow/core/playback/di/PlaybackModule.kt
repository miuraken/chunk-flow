package com.miuraken.chunkflow.core.playback.di

import com.miuraken.chunkflow.core.playback.MediaControllerPlaybackController
import com.miuraken.chunkflow.core.playback.PlaybackController
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Media3 の `Player` 操作に用いるメインスレッド [CoroutineDispatcher] の修飾子。 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class PlaybackMainDispatcher

/**
 * [PlaybackController] の実装を Hilt に束縛するモジュール（§6.3、§7.2.5）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    /** [MediaControllerPlaybackController] を [PlaybackController] として束縛する。 */
    @Binds
    @Singleton
    abstract fun bindsPlaybackController(
        impl: MediaControllerPlaybackController,
    ): PlaybackController

    companion object {
        /** Media3 の `Player` を扱うメインスレッド [CoroutineDispatcher] を提供する。 */
        @Provides
        @PlaybackMainDispatcher
        fun providesPlaybackMainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate
    }
}
