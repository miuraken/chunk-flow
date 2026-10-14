package com.miuraken.chunkflow.core.media.di

import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import com.miuraken.chunkflow.core.media.MediaCodecAudioStreamDecoder
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `core:media` の [AudioStreamDecoder] を Hilt に束縛するモジュール（§7.3.1）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {

    /** [MediaCodecAudioStreamDecoder] を [AudioStreamDecoder] として束縛する。 */
    @Binds
    @Singleton
    abstract fun bindsAudioStreamDecoder(
        impl: MediaCodecAudioStreamDecoder,
    ): AudioStreamDecoder
}
