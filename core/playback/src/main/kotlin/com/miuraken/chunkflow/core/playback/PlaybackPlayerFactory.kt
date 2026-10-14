package com.miuraken.chunkflow.core.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import com.miuraken.chunkflow.core.model.UserSettings

/**
 * 設計書 §7.2.2・ADR 0006・ADR 0007 の設定に従う [ExoPlayer] を組み立てるファクトリ。
 */
object PlaybackPlayerFactory {

    /**
     * 本番用の [ExoPlayer] を生成する。
     *
     * - `AudioAttributes`: `USAGE_MEDIA` + `AUDIO_CONTENT_TYPE_SPEECH`、`handleAudioFocus = true`
     * - `setHandleAudioBecomingNoisy(true)`
     * - `setWakeMode(C.WAKE_MODE_LOCAL)`
     * - `experimentalSetDynamicSchedulingEnabled(false)`（ADR 0007）
     * - [IndexSeekDataSource] + `Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING`（ADR 0006）
     */
    @OptIn(UnstableApi::class, ExperimentalApi::class)
    fun createExoPlayer(context: Context): ExoPlayer {
        val extractorsFactory = DefaultExtractorsFactory()
            .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
        val dataSourceFactory = IndexSeekDataSource.Factory(DefaultDataSource.Factory(context))
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .setSeekBackIncrementMs(UserSettings.DEFAULT_SEEK_BACK_MS)
            .setSeekForwardIncrementMs(UserSettings.DEFAULT_SEEK_FORWARD_MS)
            .experimentalSetDynamicSchedulingEnabled(false)
            .build()
    }
}
