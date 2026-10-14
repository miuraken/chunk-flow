package com.miuraken.chunkflow.spike.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Debug
import android.os.SystemClock
import com.miuraken.chunkflow.core.audio.AnalysisPipeline
import com.miuraken.chunkflow.core.audio.FeatureAccumulator
import com.miuraken.chunkflow.core.audio.SampleSink
import java.io.File
import java.nio.ByteOrder
import org.json.JSONObject

/**
 * S3 の計測 1・2: 抽出器（[ExtractorKind]）+ MediaCodec（同期モード）でストリーミングでデコードし、
 * core:audio の [AnalysisPipeline]（モノラル化 → 16kHz → 10ms のビンと特徴量）に流す。
 *
 * 計測するもの: 所要時間（壁時計・スレッドの CPU 時間・パイプラインの時間）、追加のメモリ、
 * クリックの時刻（出力サンプル数の累計から求めたもの、エンコーダ遅延を引いたもの、PTS から求めたもの）。
 *
 * @param analyze false ならデコードだけ（PCM の取り出しまで）を測る
 */
class DecodeBenchmark(
    private val context: Context,
    private val file: File,
    private val kind: ExtractorKind,
    private val analyze: Boolean,
    private val expectedClicksS: List<Double>,
) {
    /** 計測して結果を JSON で返す。 */
    fun run(): JSONObject {
        val out = JSONObject()
        out.put("test", if (analyze) "decode+analyze" else "decode-only")
        out.put("file", file.parentFile?.name + "/" + file.name)
        out.put("extractor", kind.name)
        val mem = MemorySampler()
        mem.start()
        val wallStart = SystemClock.elapsedRealtimeNanos()
        val cpuStart = Debug.threadCpuTimeNanos()
        try {
            decode(out)
        } catch (e: Exception) {
            out.put("error", e.javaClass.simpleName + ": " + e.message)
        }
        val wallMs = (SystemClock.elapsedRealtimeNanos() - wallStart) / 1e6
        val cpuMs = (Debug.threadCpuTimeNanos() - cpuStart) / 1e6
        val m = mem.stop()
        out.put("wallMs", wallMs.round(1))
        out.put("threadCpuMs", cpuMs.round(1))
        out.put("javaHeapExtraMb", m.javaExtraMb.round(2))
        out.put("nativeHeapExtraMb", m.nativeExtraMb.round(2))
        out.put("pssExtraMb", m.pssExtraMb.round(2))
        val durS = out.optDouble("decodedDurationS", Double.NaN)
        if (!durS.isNaN() && wallMs > 0) out.put("speedX", (durS * 1000 / wallMs).round(1))
        return out
    }

    private fun decode(out: JSONObject) {
        SampleExtractor.open(kind, context, file.absolutePath).use { ex ->
            val track = (0 until ex.trackCount).firstOrNull {
                ex.trackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("音声のトラックがない")
            val format = ex.trackFormat(track)
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("MIME がない")
            out.put("mime", mime)
            out.put("trackFormat", format.toString())
            val encoderDelay = format.intOrZero(MediaFormat.KEY_ENCODER_DELAY)
            val encoderPadding = format.intOrZero(MediaFormat.KEY_ENCODER_PADDING)
            out.put("encoderDelay", encoderDelay)
            out.put("encoderPadding", encoderPadding)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                out.put("formatDurationUs", format.getLong(MediaFormat.KEY_DURATION))
            }

            val codec = MediaCodec.createDecoderByType(mime)
            out.put("codec", codec.name)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                loop(ex, codec, out, encoderDelay)
            } finally {
                codec.stop()
                codec.release()
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun loop(ex: SampleExtractor, codec: MediaCodec, out: JSONObject, encoderDelay: Int) {
        val info = MediaCodec.BufferInfo()
        var inputEos = false
        var firstInputPts = Long.MIN_VALUE
        var inputSamples = 0
        var firstOutputPts = Long.MIN_VALUE
        var rate = 0
        var channels = 0
        var floatPcm = false
        var shorts = ShortArray(0)
        var floats = FloatArray(0)
        var framesOut = 0L
        var pipeline: AnalysisPipeline? = null
        val acc = FeatureAccumulator()
        var nativeClicks: ClickDetector? = null
        var resampledClicks: ClickDetector? = null
        val ptsClicksS = ArrayList<Double>()
        var pipelineNs = 0L
        var formatChanges = 0

        var extractorDone = false
        var lastInputPts = Long.MIN_VALUE
        var repeatedPts = 0
        var lastProgress = SystemClock.elapsedRealtime()
        var lastLog = lastProgress
        while (true) {
            if (SystemClock.elapsedRealtime() - lastLog > PROGRESS_LOG_MS) {
                lastLog = SystemClock.elapsedRealtime()
                android.util.Log.i(
                    "S3P",
                    "in=$inputSamples eos=$inputEos out=$framesOut t=${ex.sampleTime} info.size=${info.size}",
                )
            }
            // 進まなくなったら打ち切る（抽出器やデコーダの不具合の検出）
            if (SystemClock.elapsedRealtime() - lastProgress > STALL_MS) {
                error(
                    "停止: inputSamples=$inputSamples inputEos=$inputEos framesOut=$framesOut " +
                        "lastSampleTime=${ex.sampleTime}",
                )
            }
            // 入力は待たずに入るだけ入れる。入れられなかったときだけ出力を待つ
            // （両方を毎回待つと、Codec2 の非同期の処理を待つ時間で遅くなる）
            var fed = false
            while (!inputEos) {
                val inIdx = codec.dequeueInputBuffer(0)
                if (inIdx < 0) break
                fed = true
                lastProgress = SystemClock.elapsedRealtime()
                run {
                    val buf = codec.getInputBuffer(inIdx) ?: error("入力バッファがない")
                    val size = if (extractorDone) -1 else ex.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        val pts = ex.sampleTime
                        if (firstInputPts == Long.MIN_VALUE) firstInputPts = pts
                        codec.queueInputBuffer(inIdx, 0, size, pts, 0)
                        inputSamples++
                        // 時刻が進まないサンプルが続いたら終わりとみなす
                        // （MediaExtractorCompat の FLAC で、最後のサンプルが繰り返し返され、EOS にならない現象への対策）
                        if (pts <= lastInputPts) repeatedPts++ else repeatedPts = 0
                        lastInputPts = maxOf(lastInputPts, pts)
                        if (!ex.advance()) {
                            extractorDone = true
                        } else if (repeatedPts >= REPEATED_PTS_LIMIT) {
                            extractorDone = true
                            out.put("stuckAtEndWorkaround", true)
                        }
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, if (fed) 0 else TIMEOUT_US)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    val newCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    val enc = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    formatChanges++
                    out.put("outputFormat", f.toString())
                    if (pipeline != null && (newRate != rate || newCh != channels)) {
                        error("ストリームの途中でサンプルレートかチャンネル数が変わった（スパイクでは非対応）")
                    }
                    rate = newRate
                    channels = newCh
                    floatPcm = enc == AudioFormat.ENCODING_PCM_FLOAT
                    if (analyze && pipeline == null) {
                        val nd = ClickDetector(quietSamples = rate * 2 / 5).also { nativeClicks = it }
                        val rd = ClickDetector(quietSamples = 16_000 * 2 / 5).also { resampledClicks = it }
                        pipeline = AnalysisPipeline(
                            inputRate = rate,
                            channels = channels,
                            sink = acc,
                            nativeTap = SampleSink { s, n -> nd.process(s, n) },
                            resampledTap = SampleSink { s, n -> rd.process(s, n) },
                        )
                    }
                }

                outIdx >= 0 -> {
                    lastProgress = SystemClock.elapsedRealtime()
                    if (info.size > 0) {
                        if (firstOutputPts == Long.MIN_VALUE) firstOutputPts = info.presentationTimeUs
                        val buf = codec.getOutputBuffer(outIdx) ?: error("出力バッファがない")
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.nativeOrder())
                        val bufStart = framesOut
                        if (floatPcm) {
                            val n = info.size / 4
                            if (floats.size < n) floats = FloatArray(n)
                            buf.asFloatBuffer().get(floats, 0, n)
                            val frames = n / channels
                            framesOut += frames
                            pipeline?.let { p ->
                                val t = System.nanoTime()
                                p.pushFloat(floats, frames)
                                pipelineNs += System.nanoTime() - t
                            }
                        } else {
                            val n = info.size / 2
                            if (shorts.size < n) shorts = ShortArray(n)
                            buf.asShortBuffer().get(shorts, 0, n)
                            val frames = n / channels
                            framesOut += frames
                            pipeline?.let { p ->
                                val t = System.nanoTime()
                                p.pushS16(shorts, frames)
                                pipelineNs += System.nanoTime() - t
                            }
                        }
                        // PTS から求めたクリックの時刻（このバッファの中で見つかったもの）
                        nativeClicks?.onsets?.let { onsets ->
                            for (i in onsets.size - 1 downTo 0) {
                                val o = onsets[i]
                                if (o < bufStart) break
                                ptsClicksS.add(0, info.presentationTimeUs / 1e6 + (o - bufStart).toDouble() / rate)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        // ptsClicksS は後ろから挿入したので、バッファごとに順序を保っている。念のため並べ替える
        ptsClicksS.sort()
        val p = pipeline
        if (p != null) {
            val t = System.nanoTime()
            p.finish()
            pipelineNs += System.nanoTime() - t
        }

        out.put("sampleRate", rate)
        out.put("channels", channels)
        out.put("floatPcm", floatPcm)
        out.put("formatChanges", formatChanges)
        out.put("firstInputPtsUs", firstInputPts)
        out.put("firstOutputPtsUs", firstOutputPts)
        out.put("inputSamples", inputSamples)
        out.put("outputFrames", framesOut)
        if (rate > 0) out.put("decodedDurationS", (framesOut.toDouble() / rate).round(6))
        if (p != null) {
            out.put("pipelineMs", (pipelineNs / 1e6).round(1))
            out.put("resampledSamples", p.outputSamples)
            out.put("bins", acc.size)
            val nd = nativeClicks
            val rd = resampledClicks
            if (nd != null && rd != null) {
                val clicks = JSONObject()
                clicks.put("countRaw", ClickMatch.summarize(nd.onsets.map { it.toDouble() / rate }, expectedClicksS))
                clicks.put(
                    "countTrimDelay",
                    ClickMatch.summarize(nd.onsets.map { (it - encoderDelay).toDouble() / rate }, expectedClicksS),
                )
                clicks.put("pts", ClickMatch.summarize(ptsClicksS, expectedClicksS))
                clicks.put(
                    "analysis16kRaw",
                    ClickMatch.summarize(rd.onsets.map { it.toDouble() / 16_000 }, expectedClicksS),
                )
                out.put("clicks", clicks)
            }
        }
    }

    private companion object {
        const val TIMEOUT_US = 5_000L
        const val STALL_MS = 5_000L
        const val REPEATED_PTS_LIMIT = 8
        const val PROGRESS_LOG_MS = 2_000L
    }
}

private fun MediaFormat.intOrZero(key: String): Int = intOr(key, 0)

private fun MediaFormat.intOr(key: String, default: Int): Int = if (containsKey(key)) getInteger(key) else default

/** 小数点以下 [digits] 桁に丸める（JSON の見やすさのため）。 */
fun Double.round(digits: Int): Double {
    var m = 1.0
    repeat(digits) { m *= 10 }
    return Math.round(this * m) / m
}
