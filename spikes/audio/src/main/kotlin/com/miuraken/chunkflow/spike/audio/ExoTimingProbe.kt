package com.miuraken.chunkflow.spike.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * S3 の計測 3: ExoPlayer で再生したとき、クリックが「再生位置」の何秒に来るかを測る。
 *
 * 仕組み:
 * - DefaultAudioSink の最終段（[AudioOutput]）を差し替え、AudioTrack に書く直前の PCM（ギャップレスの
 *   トリミングの後）でクリックを検出する。位置は「フラッシュ後に書いたフレーム数」k。
 * - DefaultAudioSink は、再生位置 = オフセット + 出力の再生位置（[AudioOutput.getPositionUs]）として求める。
 *   [ForwardingAudioSink.handleBuffer] のたびに `getCurrentPositionUs() - output.positionUs` をオフセットとして記録し、
 *   クリックの再生位置 = オフセット + k / サンプルレート とする（ExoPlayer 自身の対応付けをそのまま使う）。
 * - [realtime] が false なら、出力は AudioTrack を使わずに書いた分だけすぐ「再生済み」にする（等速より速く進む）。
 *   true なら本物の AudioTrack に書く（等速。端末の出力遅延の補正を含む位置になる）。
 */
@OptIn(UnstableApi::class)
class ExoTimingProbe(
    private val context: Context,
    private val file: File,
    private val realtime: Boolean,
    private val expectedClicksS: List<Double>,
) {
    private val lock = Any()
    private var output: ProbeOutput? = null
    private var detector: ClickDetector? = null
    private var sampleRate = 0
    private var latestOffsetUs = Long.MIN_VALUE
    private var minOffsetUs = Long.MAX_VALUE
    private var maxOffsetUs = Long.MIN_VALUE
    private var offsetSamples = 0
    private val clickTimesS = ArrayList<Double>()
    private val firstPtsAfterFlushUs = ArrayList<Long>()
    private var awaitingFirstPts = true
    private var outputFlushes = 0
    private var outputConfig = ""
    private var rendererOffsetUs = 0L

    /** 再生して結果を JSON で返す（メインスレッド以外から呼ぶ）。 */
    fun run(timeoutMs: Long): JSONObject {
        val out = JSONObject()
        out.put("test", if (realtime) "exoplayer-realtime" else "exoplayer-fast")
        out.put("file", file.parentFile?.name + "/" + file.name)
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        var player: ExoPlayer? = null
        val start = SystemClock.elapsedRealtime()
        main.post {
            val p = ExoPlayer.Builder(context, renderersFactory()).build()
            player = p
            p.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    out.put("decoder", decoderName)
                }

                override fun onAudioInputFormatChanged(
                    eventTime: AnalyticsListener.EventTime,
                    format: Format,
                    decoderReuseEvaluation: DecoderReuseEvaluation?,
                ) {
                    out.put("mime", format.sampleMimeType)
                    out.put("encoderDelay", format.encoderDelay)
                    out.put("encoderPadding", format.encoderPadding)
                    out.put("inputSampleRate", format.sampleRate)
                }
            })
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) {
                        out.put("durationMs", p.duration)
                        done.countDown()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    out.put("error", error.errorCodeName + ": " + error.message)
                    done.countDown()
                }
            })
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            p.prepare()
            p.play()
        }
        val finished = done.await(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) out.put("error", "timeout")
        main.post { player?.release() }
        out.put("wallMs", SystemClock.elapsedRealtime() - start)
        synchronized(lock) {
            out.put("outputConfig", outputConfig)
            out.put("outputFlushes", outputFlushes)
            out.put("firstPtsAfterFlushUs", JSONArray(firstPtsAfterFlushUs))
            out.put("offsetUsMin", minOffsetUs)
            out.put("offsetUsMax", maxOffsetUs)
            out.put("offsetSamples", offsetSamples)
            out.put("rendererOffsetUs", rendererOffsetUs)
            out.put("clicks", ClickMatch.summarize(clickTimesS.toList(), expectedClicksS))
        }
        return out
    }

    private fun renderersFactory() = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink {
            val real = AudioTrackAudioOutputProvider.Builder(context).build()
            val provider = object : ForwardingAudioOutputProvider(real) {
                override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
                    val o: ProbeOutput = if (realtime) {
                        RealtimeOutput(super.getAudioOutput(config), config)
                    } else {
                        FastOutput(config)
                    }
                    synchronized(lock) {
                        output = o
                        sampleRate = config.sampleRate
                        detector = ClickDetector(quietSamples = config.sampleRate * 2 / 5)
                        outputConfig = "rate=${config.sampleRate} encoding=${config.encoding} " +
                            "channelMask=${config.channelMask}"
                    }
                    return o
                }
            }
            val sink = DefaultAudioSink.Builder(context).setAudioOutputProvider(provider).build()
            return ProbeSink(sink)
        }
    }

    private inner class ProbeSink(sink: AudioSink) : ForwardingAudioSink(sink) {
        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            synchronized(lock) {
                if (awaitingFirstPts) {
                    if (firstPtsAfterFlushUs.isEmpty()) {
                        rendererOffsetUs = (presentationTimeUs / RENDERER_OFFSET_UNIT_US) * RENDERER_OFFSET_UNIT_US
                    }
                    firstPtsAfterFlushUs.add(presentationTimeUs)
                    awaitingFirstPts = false
                }
            }
            val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            val media = getCurrentPositionUs(false)
            synchronized(lock) {
                val o = output
                if (media != AudioSink.CURRENT_POSITION_NOT_SET && o != null) {
                    // シンクの位置はレンダラーの時刻（メディアの時刻 + レンダラーのオフセット）。
                    // 最初の再生区間のオフセットは Media3 の既定で 1e12 µs なので、それを引いてメディアの時刻にする
                    val offset = media - o.getPositionUs() - rendererOffsetUs
                    latestOffsetUs = offset
                    offsetSamples++
                    if (offset < minOffsetUs) minOffsetUs = offset
                    if (offset > maxOffsetUs) maxOffsetUs = offset
                }
            }
            return handled
        }

        override fun flush() {
            super.flush()
            synchronized(lock) { awaitingFirstPts = true }
        }
    }

    /** 書き込まれた PCM でクリックを検出する（[frameIndexStart] はフラッシュ後のフレームの累計）。 */
    private fun analyze(buffer: ByteBuffer, from: Int, bytes: Int, frameIndexStart: Long, encoding: Int, channels: Int) {
        synchronized(lock) {
            val d = detector ?: return
            val before = d.onsets.size
            val b = buffer.duplicate().order(ByteOrder.nativeOrder())
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val frameBytes = 4 * channels
                var p = from
                while (p + frameBytes <= from + bytes) {
                    var s = 0f
                    for (c in 0 until channels) s += b.getFloat(p + 4 * c)
                    d.processOne(s / channels)
                    p += frameBytes
                }
            } else {
                val frameBytes = 2 * channels
                var p = from
                while (p + frameBytes <= from + bytes) {
                    var s = 0
                    for (c in 0 until channels) s += b.getShort(p + 2 * c)
                    d.processOne(s / 32768f / channels)
                    p += frameBytes
                }
            }
            if (d.onsets.size > before && latestOffsetUs != Long.MIN_VALUE) {
                for (i in before until d.onsets.size) {
                    // 検出器の位置はフラッシュごとに 0 に戻している
                    clickTimesS.add(latestOffsetUs / 1e6 + d.onsets[i].toDouble() / sampleRate)
                }
            }
            check(frameIndexStart >= 0)
        }
    }

    private fun onOutputFlush() {
        synchronized(lock) {
            outputFlushes++
            detector?.resetPosition()
        }
    }

    /** 計測用の出力の共通部分 */
    private interface ProbeOutput : AudioOutput

    /** 書いた分だけすぐ再生済みにする出力（AudioTrack を使わない）。 */
    private inner class FastOutput(private val config: AudioOutputProvider.OutputConfig) : ProbeOutput {
        private val channels = Integer.bitCount(config.channelMask).coerceAtLeast(1)
        private val frameBytes = (if (config.encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2) * channels
        private var written = 0L
        private var playing = false
        private var frozenUs = 0L
        private var params = PlaybackParameters.DEFAULT
        private val listeners = ArrayList<AudioOutput.Listener>()
        private var notified = false

        override fun getPositionUs(): Long = if (playing) written * 1_000_000L / config.sampleRate else frozenUs

        override fun play() {
            playing = true
            if (!notified) {
                notified = true
                listeners.forEach { it.onPositionAdvancing(System.currentTimeMillis()) }
            }
        }

        override fun pause() {
            frozenUs = getPositionUs()
            playing = false
        }

        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            val bytes = buffer.remaining()
            analyze(buffer, buffer.position(), bytes, written, config.encoding, channels)
            written += bytes / frameBytes
            buffer.position(buffer.limit())
            return true
        }

        override fun flush() {
            written = 0
            frozenUs = 0
            onOutputFlush()
        }

        override fun stop() = Unit
        override fun release() = Unit
        override fun setVolume(volume: Float) = Unit
        override fun isOffloadedPlayback(): Boolean = false
        override fun getAudioSessionId(): Int = config.audioSessionId
        override fun getSampleRate(): Int = config.sampleRate
        override fun getBufferSizeInFrames(): Long = (config.bufferSize / frameBytes).toLong()
        override fun getPlaybackParameters(): PlaybackParameters = params
        override fun isStalled(): Boolean = false
        override fun addListener(listener: AudioOutput.Listener) {
            listeners.add(listener)
        }

        override fun removeListener(listener: AudioOutput.Listener) {
            listeners.remove(listener)
        }

        override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
            params = playbackParameters
        }

        override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit
        override fun setOffloadEndOfStream() = Unit
        override fun attachAuxEffect(effectId: Int) = Unit
        override fun setAuxEffectSendLevel(level: Float) = Unit
        override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
    }

    /** 本物の AudioTrack に書き、書けた分だけ検出する出力。 */
    private inner class RealtimeOutput(
        delegate: AudioOutput,
        private val config: AudioOutputProvider.OutputConfig,
    ) : ForwardingAudioOutput(delegate),
        ProbeOutput {
        private val channels = Integer.bitCount(config.channelMask).coerceAtLeast(1)
        private val frameBytes = (if (config.encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2) * channels
        private var written = 0L

        override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
            val start = buffer.position()
            val result = super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
            val consumed = buffer.position() - start
            if (consumed > 0) {
                analyze(buffer, start, consumed, written, config.encoding, channels)
                written += consumed / frameBytes
            }
            return result
        }

        override fun flush() {
            super.flush()
            written = 0
            onOutputFlush()
        }
    }

    private companion object {
        /** Media3 の既定のレンダラーのオフセット（最初の区間は 1e12 µs から始まる） */
        const val RENDERER_OFFSET_UNIT_US = 1_000_000_000_000L
    }
}
