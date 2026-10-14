package com.miuraken.chunkflow.spike.playback.measure

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 再生中の音声を、ExoPlayer が「その音声のメディア時刻」と信じている時刻つきで取り出す仕組み。
 *
 * - [ProbeAudioSink]（`DefaultAudioSink` を包む）は、flush（シーク）・pause・play の時刻と、
 *   その時点の `getCurrentPositionUs()`（ExoPlayer が推定する「いま鳴っている位置」）を記録し、
 *   flush の直後に届いた最初のバッファの PTS を、区間（[Segment]）の開始時刻として記録する。
 * - [TapAudioProcessor] は `DefaultAudioSink` の中の、ギャップレスのトリミングの後・速度変更（Sonic）の前に
 *   置かれる。ここを通る PCM の n 番目のサンプルは、AudioSink の位置の計算と同じく
 *   「区間の開始 PTS + n / サンプルレート」のメディア時刻として再生される。
 *
 * つまり、区間の中でクリックを見つけた位置から求めた時刻が「プレイヤーが信じているクリックの時刻」で、
 * これと真の時刻（manifest.json）との差が、時間軸のずれ（シークの誤差）になる。
 */
@OptIn(UnstableApi::class)
class AudioProbe {
    /** flush（または最初の configure）から次の flush までの、取り出した PCM（第 1 チャンネルのみ）。 */
    class Segment(val createdAtNs: Long) {
        /** 区間の最初のバッファの PTS（メディア時刻、µs）。まだ届いていなければ [C.TIME_UNSET]。 */
        @Volatile var firstPtsUs: Long = C.TIME_UNSET

        @Volatile var firstBufferAtNs: Long = 0L
        internal val samples = ShortArrayBuilder()
    }

    /** 再生スレッドで起きた出来事。位置はメディア時刻（µs）。 */
    data class Event(val type: Type, val atNs: Long, val positionUs: Long) {
        enum class Type { FLUSH, PAUSE, PLAY, FIRST_BUFFER }
    }

    private val lock = Any()
    private var armed = false
    private var current: Segment? = null
    private val events = ArrayList<Event>()

    @Volatile var sampleRate: Int = 0
        internal set

    @Volatile var channelCount: Int = 0
        internal set

    @Volatile var outputStreamOffsetUs: Long = 0L
        internal set

    @Volatile internal var positionProvider: (() -> Long)? = null

    /** AudioSink が推定する「いま鳴っている位置」（メディア時刻、µs）。再生スレッドから呼ぶ。 */
    fun sinkPositionUs(): Long = positionProvider?.invoke() ?: C.TIME_UNSET

    /** 次の flush / configure から PCM の取り出しを始める（それまでの区間は捨てる）。 */
    fun armOnNextFlush() {
        synchronized(lock) {
            armed = true
            current = null
        }
    }

    /** 取り出しをやめる。 */
    fun disarm() {
        synchronized(lock) {
            armed = false
            current = null
        }
    }

    /** いま取り出している区間（なければ null）。 */
    fun currentSegment(): Segment? = synchronized(lock) { current }

    /** 区間の PCM の複製。 */
    fun snapshot(segment: Segment): ShortArray = synchronized(lock) { segment.samples.toArray() }

    /** 記録した出来事を取り出して消す。 */
    fun drainEvents(): List<Event> = synchronized(lock) { events.toList().also { events.clear() } }

    /** true なら、configure / flush のたびに区間を分けず、1 つの区間に続けて記録する（繰り返し再生の計測用）。 */
    @Volatile var keepSingleSegment: Boolean = false

    internal fun onNewStream() {
        synchronized(lock) {
            if (armed && !(keepSingleSegment && current != null)) current = Segment(SystemClock.elapsedRealtimeNanos())
        }
    }

    internal fun onEvent(type: Event.Type, positionUs: Long) {
        synchronized(lock) { events += Event(type, SystemClock.elapsedRealtimeNanos(), positionUs) }
    }

    internal fun onBuffer(ptsUs: Long) {
        val seg = synchronized(lock) { current } ?: return
        if (seg.firstPtsUs == C.TIME_UNSET) {
            seg.firstPtsUs = ptsUs - outputStreamOffsetUs
            seg.firstBufferAtNs = SystemClock.elapsedRealtimeNanos()
            onEvent(Event.Type.FIRST_BUFFER, seg.firstPtsUs)
        }
    }

    internal fun onPcm(buffer: ByteBuffer, channels: Int) {
        synchronized(lock) {
            val seg = current ?: return
            val b = buffer.duplicate().order(ByteOrder.nativeOrder())
            val frames = b.remaining() / (2 * channels)
            for (i in 0 until frames) {
                seg.samples.add(b.getShort(b.position() + i * 2 * channels))
            }
        }
    }
}

/** ShortArray の可変長バッファ。 */
class ShortArrayBuilder {
    private var data = ShortArray(1 shl 16)
    var size = 0
        private set

    fun add(v: Short) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): ShortArray = data.copyOf(size)
}

/** PCM をそのまま通しながら [AudioProbe] に写す。16 ビット PCM のときだけ有効。 */
@OptIn(UnstableApi::class)
class TapAudioProcessor(private val probe: AudioProbe) : BaseAudioProcessor() {
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        probe.sampleRate = inputAudioFormat.sampleRate
        probe.channelCount = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        probe.onPcm(inputBuffer, inputAudioFormat.channelCount)
        val out = replaceOutputBuffer(remaining)
        out.put(inputBuffer)
        out.flip()
    }
}

/** flush / pause / play の時点の再生位置と、区間の最初の PTS を記録する AudioSink。 */
@OptIn(UnstableApi::class)
class ProbeAudioSink(sink: AudioSink, private val probe: AudioProbe) : ForwardingAudioSink(sink) {
    private var pending: ByteBuffer? = null

    init {
        probe.positionProvider = ::positionUs
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        super.configure(audioSinkConfig)
        pending = null
        probe.onNewStream()
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        probe.outputStreamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (buffer !== pending) probe.onBuffer(presentationTimeUs)
        val consumed = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        pending = if (consumed) null else buffer
        return consumed
    }

    override fun flush() {
        probe.onEvent(AudioProbe.Event.Type.FLUSH, positionUs())
        pending = null
        super.flush()
        probe.onNewStream()
    }

    override fun pause() {
        probe.onEvent(AudioProbe.Event.Type.PAUSE, positionUs())
        super.pause()
    }

    override fun play() {
        super.play()
        probe.onEvent(AudioProbe.Event.Type.PLAY, positionUs())
    }

    private fun positionUs(): Long {
        val p = getCurrentPositionUs(false)
        return if (p == AudioSink.CURRENT_POSITION_NOT_SET) C.TIME_UNSET else p - probe.outputStreamOffsetUs
    }
}

/** [ProbeAudioSink] と [TapAudioProcessor] を差し込んだ RenderersFactory。 */
@OptIn(UnstableApi::class)
class ProbeRenderersFactory(context: Context, private val probe: AudioProbe) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink = ProbeAudioSink(
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(TapAudioProcessor(probe)))
            .build(),
        probe,
    )
}
