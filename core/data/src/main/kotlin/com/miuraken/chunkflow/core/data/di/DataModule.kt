package com.miuraken.chunkflow.core.data.di

import androidx.work.WorkerFactory
import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.data.library.AudioFormatInspector
import com.miuraken.chunkflow.core.data.library.DefaultAudioFormatInspector
import com.miuraken.chunkflow.core.data.library.DefaultLibraryRepository
import com.miuraken.chunkflow.core.data.library.LibraryMediaFileResolver
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.Waveform
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * データ層（`core:data`）のリポジトリ・ヘルパー・WorkManager [WorkerFactory] を Hilt に束縛するモジュール（§6.3、§7.3.4）。
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

    /**
     * WorkManager 用のカスタム [WorkerFactory] のマルチバインディング（§7.3.4）。
     *
     * `audio-analysis` などの担当モジュールは `@Binds @IntoSet` で自身の `WorkerFactory` を登録する。
     */
    @Multibinds
    abstract fun bindsWorkerFactories(): Set<WorkerFactory>

    companion object {
        /**
         * Stage 3 の DI 統合で `DefaultAnalysisRepository` に置き換えるまでのフォールバック実装。
         * `:app` や `PlaybackService`（`ChunkScheduler`）が [AnalysisRepository] を直接注入できるようにする。
         */
        @Provides
        @Singleton
        fun provideAnalysisRepository(): AnalysisRepository = NoOpAnalysisRepository
    }
}

private object NoOpAnalysisRepository : AnalysisRepository {
    override fun observeStatus(id: MediaId): Flow<Map<AnalysisStage, StageStatus>> = flowOf(emptyMap())
    override fun observeWaveform(id: MediaId): Flow<Waveform?> = flowOf(null)
    override fun observeChunks(id: MediaId): Flow<List<Chunk>> = flowOf(emptyList())
    override fun observeTranscripts(id: MediaId): Flow<Map<ChunkId, Transcript>> = flowOf(emptyMap())
    override suspend fun requestAnalysis(id: MediaId, stages: Set<AnalysisStage>) = Unit
    override suspend fun resegment(id: MediaId, params: SegmentationParams) = Unit
    override suspend fun setChunkSource(id: MediaId, source: ChunkOrigin) = Unit
    override suspend fun setTranscriptOffset(id: MediaId, offsetMs: Int) = Unit
}
