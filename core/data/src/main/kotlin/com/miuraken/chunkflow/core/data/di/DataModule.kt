package com.miuraken.chunkflow.core.data.di

import androidx.work.WorkerFactory
import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.data.analysis.AnalysisWorkerFactory
import com.miuraken.chunkflow.core.data.analysis.DefaultAnalysisRepository
import com.miuraken.chunkflow.core.data.library.AudioFormatInspector
import com.miuraken.chunkflow.core.data.library.DefaultAudioFormatInspector
import com.miuraken.chunkflow.core.data.library.DefaultLibraryRepository
import com.miuraken.chunkflow.core.data.library.LibraryMediaFileResolver
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.data.transcript.DefaultTranscriptImporter
import com.miuraken.chunkflow.core.data.transcript.TranscriptImporter
import com.miuraken.chunkflow.core.transcript.CompositeTranscriptParser
import com.miuraken.chunkflow.core.transcript.TranscriptParser
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import javax.inject.Singleton

/**
 * データ層（`core:data`）のリポジトリ・ヘルパー・WorkManager [WorkerFactory] を Hilt に束縛するモジュール（§6.3、§7.3.4、§7.4.6）。
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

    /** [DefaultAnalysisRepository] を [AnalysisRepository] として束縛する。 */
    @Binds
    @Singleton
    abstract fun bindsAnalysisRepository(
        impl: DefaultAnalysisRepository,
    ): AnalysisRepository

    /** [DefaultTranscriptImporter] を [TranscriptImporter] として束縛する。 */
    @Binds
    @Singleton
    abstract fun bindsTranscriptImporter(
        impl: DefaultTranscriptImporter,
    ): TranscriptImporter

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

    /**
     * WorkManager 用のカスタム [WorkerFactory] のマルチバインディング（§7.3.4）。
     */
    @Multibinds
    abstract fun bindsWorkerFactories(): Set<WorkerFactory>

    /** [AnalysisWorkerFactory] を WorkManager の [WorkerFactory] セットに登録する（§7.3.4）。 */
    @Binds
    @IntoSet
    abstract fun bindsAnalysisWorkerFactory(
        impl: AnalysisWorkerFactory,
    ): WorkerFactory

    companion object {
        /**
         * `.chunkflow.json` と字幕形式（`.srt` / `.vtt` / `.lrc`）の両方を解釈する
         * [CompositeTranscriptParser] を [TranscriptParser] として提供する（F1.4、§6.3、§7.4.6）。
         */
        @Provides
        @Singleton
        fun provideTranscriptParser(): TranscriptParser = CompositeTranscriptParser()
    }
}
