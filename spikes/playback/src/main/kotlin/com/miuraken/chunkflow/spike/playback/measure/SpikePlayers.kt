package com.miuraken.chunkflow.spike.playback.measure

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import kotlinx.coroutines.delay

/** スパイクで使う ExoPlayer の組み立て。 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
object SpikePlayers {
    /**
     * ExoPlayer を作る。[probe] を渡すと計測用の AudioSink を差し込む。
     * [mp3Flags] は `Mp3Extractor` のフラグ（例: `FLAG_ENABLE_INDEX_SEEKING`）。
     * [dynamicScheduling] は `experimentalSetDynamicSchedulingEnabled`（false なら再生スレッドは約 10ms ごとに動く）。
     */
    fun build(
        context: Context,
        probe: AudioProbe?,
        mp3Flags: Int,
        handleAudioFocus: Boolean,
        dynamicScheduling: Boolean = true,
        indexSeekDataSource: Boolean = false,
    ): ExoPlayer {
        val extractors = DefaultExtractorsFactory().setMp3ExtractorFlags(mp3Flags)
        val mediaSourceFactory = if (indexSeekDataSource) {
            DefaultMediaSourceFactory(IndexSeekDataSource.Factory(DefaultDataSource.Factory(context)), extractors)
        } else {
            DefaultMediaSourceFactory(context, extractors)
        }
        val renderers = if (probe != null) ProbeRenderersFactory(context, probe) else DefaultRenderersFactory(context)
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        return ExoPlayer.Builder(context, renderers, mediaSourceFactory)
            .setAudioAttributes(attributes, handleAudioFocus)
            // Media3 1.11.1 では既定で true（再生スレッドの起床間隔をレンダラが決める）。PlayerMessage の配信の遅れに効く
            .experimentalSetDynamicSchedulingEnabled(dynamicScheduling)
            .build()
    }

    /** STATE_READY になるまで待つ。時間内にならなければ false。 */
    suspend fun awaitReady(player: Player, timeoutMs: Long = 15_000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (player.playbackState == Player.STATE_READY) return true
            if (player.playerError != null) return false
            delay(20)
        }
        return false
    }
}
