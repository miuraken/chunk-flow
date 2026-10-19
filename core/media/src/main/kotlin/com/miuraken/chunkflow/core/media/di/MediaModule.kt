package com.miuraken.chunkflow.core.media.di

import com.miuraken.chunkflow.core.media.AndroidSpeechRecognizerChunkTranscriber
import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import com.miuraken.chunkflow.core.media.HttpUrlConnectionModelArtifactDownloader
import com.miuraken.chunkflow.core.media.MediaCodecAudioStreamDecoder
import com.miuraken.chunkflow.core.media.MlKitOnDeviceTextTranslator
import com.miuraken.chunkflow.core.media.ModelArtifactDownloader
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriber
import com.miuraken.chunkflow.core.media.OnDeviceTextTranslator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * `core:media` の [AudioStreamDecoder]、[OnDeviceChunkTranscriber]、[OnDeviceTextTranslator]、
 * および [ModelArtifactDownloader] を Hilt に束縛するモジュール（§7.3.1、F6.2）。
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

    /** [AndroidSpeechRecognizerChunkTranscriber] を [OnDeviceChunkTranscriber] として束縛する。 */
    @Binds
    @Singleton
    abstract fun bindsOnDeviceChunkTranscriber(
        impl: AndroidSpeechRecognizerChunkTranscriber,
    ): OnDeviceChunkTranscriber

    /** [MlKitOnDeviceTextTranslator] を [OnDeviceTextTranslator] として束縛する。 */
    @Binds
    @Singleton
    abstract fun bindsOnDeviceTextTranslator(
        impl: MlKitOnDeviceTextTranslator,
    ): OnDeviceTextTranslator

    /** [HttpUrlConnectionModelArtifactDownloader] を [ModelArtifactDownloader] として束縛する。 */
    @Binds
    @Singleton
    abstract fun bindsModelArtifactDownloader(
        impl: HttpUrlConnectionModelArtifactDownloader,
    ): ModelArtifactDownloader
}
