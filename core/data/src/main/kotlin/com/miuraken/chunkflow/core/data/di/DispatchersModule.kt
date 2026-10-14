package com.miuraken.chunkflow.core.data.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** 注入する Dispatcher の種類（§5.4: Dispatcher は DI で注入し、テストで差し替える）。 */
enum class ChunkFlowDispatchers {
    /** CPU を使う処理（デコード後の特徴量の計算など）。 */
    Default,

    /** DB とファイルの I/O。 */
    IO,
}

/** 注入する [CoroutineDispatcher] を選ぶ修飾子。例: `@Dispatcher(ChunkFlowDispatchers.IO) io: CoroutineDispatcher`。 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val dispatcher: ChunkFlowDispatchers)

/** [Dispatcher] で修飾した [CoroutineDispatcher] を提供する。 */
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    /** CPU を使う処理の Dispatcher。 */
    @Provides
    @Dispatcher(ChunkFlowDispatchers.Default)
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /** I/O の Dispatcher。 */
    @Provides
    @Dispatcher(ChunkFlowDispatchers.IO)
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
