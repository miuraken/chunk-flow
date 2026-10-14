package com.miuraken.chunkflow.spike.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.miuraken.chunkflow.core.audio.AnalysisPipeline
import com.miuraken.chunkflow.core.audio.FeatureAccumulator
import com.miuraken.chunkflow.core.audio.SampleSink
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * [DecodeBenchmark] の非同期モード版（比較用）。MediaCodec.setCallback で、入力の供給・出力の処理を
 * 1 本の HandlerThread で行う（ExoPlayer も API 31 以上では非同期モードで MediaCodec を使う）。
 *
 * 設計書 §7.3.1 は同期モードを想定しているが、エミュレータで同期モードが ExoPlayer より遅かったため、
 * 差がモードによるものかを確かめる。クリックの時刻は出力サンプル数の累計（ネイティブ / 16kHz）だけを求める。
 */
class AsyncDecodeBenchmark(
    private val context: Context,
    private val file: File,
    private val kind: ExtractorKind,
    private val expectedClicksS: List<Double>,
) {
    /** 計測して結果を JSON で返す（メインスレッド以外から呼ぶ）。 */
    fun run(timeoutMs: Long): JSONObject {
        val out = JSONObject()
        out.put("test", "decode+analyze-async")
        out.put("file", file.parentFile?.name + "/" + file.name)
        out.put("extractor", kind.name)
        val mem = MemorySampler()
        mem.start()
        val wallStart = SystemClock.elapsedRealtimeNanos()
        val thread = HandlerThread("s3-codec").apply { start() }
        try {
            decode(out, Handler(thread.looper), timeoutMs)
        } catch (e: Exception) {
            out.put("error", e.javaClass.simpleName + ": " + e.message)
        } finally {
            thread.quitSafely()
        }
        val wallMs = (SystemClock.elapsedRealtimeNanos() - wallStart) / 1e6
        val m = mem.stop()
        out.put("wallMs", wallMs.round(1))
        out.put("javaHeapExtraMb", m.javaExtraMb.round(2))
        out.put("nativeHeapExtraMb", m.nativeExtraMb.round(2))
        out.put("pssExtraMb", m.pssExtraMb.round(2))
        val durS = out.optDouble("decodedDurationS", Double.NaN)
        if (!durS.isNaN() && wallMs > 0) out.put("speedX", (durS * 1000 / wallMs).round(1))
        return out
    }

    @Suppress("LongMethod")
    private fun decode(out: JSONObject, handler: Handler, timeoutMs: Long) {
        SampleExtractor.open(kind, context, file.absolutePath).use { ex ->
            val track = (0 until ex.trackCount).firstOrNull {
                ex.trackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("音声のトラックがない")
            val format = ex.trackFormat(track)
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("MIME がない")
            out.put("mime", mime)
            val encoderDelay = if (format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) {
                format.getInteger(MediaFormat.KEY_ENCODER_DELAY)
            } else {
                0
            }
            out.put("encoderDelay", encoderDelay)

            val done = CountDownLatch(1)
            val acc = FeatureAccumulator()
            var pipeline: AnalysisPipeline? = null
            var native: ClickDetector? = null
            var resampled: ClickDetector? = null
            var rate = 0
            var channels = 0
            var floatPcm = false
            var shorts = ShortArray(0)
            var floats = FloatArray(0)
            var framesOut = 0L
            var pipelineNs = 0L
            var extractorDone = false
            var lastPts = Long.MIN_VALUE
            var repeated = 0
            var cpuNs = 0L
            var error: String? = null

            val codec = MediaCodec.createDecoderByType(mime)
            out.put("codec", codec.name)
            codec.setCallback(
                object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        val buf = codec.getInputBuffer(index) ?: return
                        val size = if (extractorDone) -1 else ex.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                            return
                        }
                        val pts = ex.sampleTime
                        codec.queueInputBuffer(index, 0, size, pts, 0)
                        if (pts <= lastPts) repeated++ else repeated = 0
                        lastPts = maxOf(lastPts, pts)
                        if (!ex.advance() || repeated >= REPEATED_PTS_LIMIT) extractorDone = true
                    }

                    override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                        val p = pipeline
                        if (info.size > 0 && p != null) {
                            val buf = codec.getOutputBuffer(index)
                            if (buf != null) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                buf.order(ByteOrder.nativeOrder())
                                val t = System.nanoTime()
                                if (floatPcm) {
                                    val n = info.size / 4
                                    if (floats.size < n) floats = FloatArray(n)
                                    buf.asFloatBuffer().get(floats, 0, n)
                                    p.pushFloat(floats, n / channels)
                                    framesOut += n / channels
                                } else {
                                    val n = info.size / 2
                                    if (shorts.size < n) shorts = ShortArray(n)
                                    buf.asShortBuffer().get(shorts, 0, n)
                                    p.pushS16(shorts, n / channels)
                                    framesOut += n / channels
                                }
                                pipelineNs += System.nanoTime() - t
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            pipeline?.finish()
                            cpuNs = Debug.threadCpuTimeNanos()
                            done.countDown()
                        }
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        error = "CodecException: ${e.diagnosticInfo}"
                        done.countDown()
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (pipeline == null) {
                            val nd = ClickDetector(quietSamples = rate * 2 / 5).also { native = it }
                            val rd = ClickDetector(quietSamples = 16_000 * 2 / 5).also { resampled = it }
                            pipeline = AnalysisPipeline(
                                inputRate = rate,
                                channels = channels,
                                sink = acc,
                                nativeTap = SampleSink { s, n -> nd.process(s, n) },
                                resampledTap = SampleSink { s, n -> rd.process(s, n) },
                            )
                        }
                    }
                },
                handler,
            )
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) error = "timeout"
            } finally {
                codec.stop()
                codec.release()
            }
            error?.let { out.put("error", it) }
            out.put("sampleRate", rate)
            out.put("channels", channels)
            out.put("outputFrames", framesOut)
            out.put("codecThreadCpuMs", (cpuNs / 1e6).round(1))
            if (rate > 0) out.put("decodedDurationS", (framesOut.toDouble() / rate).round(6))
            out.put("pipelineMs", (pipelineNs / 1e6).round(1))
            out.put("bins", acc.size)
            val nd = native
            val rd = resampled
            if (nd != null && rd != null) {
                val clicks = JSONObject()
                clicks.put("countRaw", ClickMatch.summarize(nd.onsets.map { it.toDouble() / rate }, expectedClicksS))
                clicks.put(
                    "analysis16kRaw",
                    ClickMatch.summarize(rd.onsets.map { it.toDouble() / 16_000 }, expectedClicksS),
                )
                out.put("clicks", clicks)
            }
        }
    }

    private companion object {
        const val REPEATED_PTS_LIMIT = 8
    }
}
