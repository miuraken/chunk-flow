package com.miuraken.chunkflow.core.data.di

import com.miuraken.chunkflow.core.data.library.AudioFormatInspector
import com.miuraken.chunkflow.core.data.library.DefaultAudioFormatInspector
import com.miuraken.chunkflow.core.data.library.DefaultLibraryRepository
import com.miuraken.chunkflow.core.data.library.LibraryMediaFileResolver
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * データ層（`core:data`）のリポジトリおよびヘルパー実装を Hilt に束縛するモジュール（§6.3）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    /** [DefaultLibraryRepository] を [LibraryRepository] として束縛する。 */
    @Binds
    @Singleton
    abstract fun bindsLibraryRepository(
        impl: DefaultLibraryRepository,
    ): LibraryRepository

    /** [DefaultAudioFormatInspector] を [AudioFormatInspector] として束縛する。 */
    @Binds
    @Singleton
    abstract fun bindsAudioFormatInspector(
        impl: DefaultAudioFormatInspector,
    ): AudioFormatInspector

    /** [MediaFileLocator] を [LibraryMediaFileResolver] として束縛する。 */
    @Binds
    @Singleton
    abstract fun bindsLibraryMediaFileResolver(
        impl: MediaFileLocator,
    ): LibraryMediaFileResolver
}
