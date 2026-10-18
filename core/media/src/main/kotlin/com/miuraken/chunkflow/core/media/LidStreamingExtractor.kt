package com.miuraken.chunkflow.core.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import com.miuraken.chunkflow.core.audio.PcmDownmix
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.audio.StreamingResampler
import java.io.File
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * 順に流す切り出し（[LidExtractionMethod.STREAMING]）で切り出す 1 つの窓。
 *
 * @property index チャンクの番号（結果に付けて返す）。
 * @property startMs チャンクの開始位置（ミリ秒）。
 * @property endMs 窓の終わり（ミリ秒。言語判定のためにチャンクの先頭から最大 2.8 秒にした値）。[startMs] より大きい。
 */
internal data class StreamWindowRequest(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
)

/** 順に流す切り出しの 1 つの窓の結果（[StreamWindowRequest] ごとにちょうど 1 回返す）。 */
internal sealed interface StreamWindowResult {
    /** チャンクの番号。 */
    val index: Int

    /**
     * 切り出した PCM（16kHz モノラル 16-bit。今の方式の [ChunkPcmExtractor] と同じ値になるようにしている）。
     *
     * @property shorts 切り出した PCM。音声の終わりより後ろなどで切り出せなかった場合は `null`（今の方式で `null` になるのと同じ）。
     */
    class Pcm(
        override val index: Int,
        val shorts: ShortArray?,
    ) : StreamWindowResult

    /** この方式では切り出せなかった（MediaCodec を作れない・出力の長さがずれた・ファイルが変わったなど）。今の方式で切り出し直す。 */
    data class Fallback(
        override val index: Int,
    ) : StreamWindowResult
}

/**
 * 順に流す切り出しの設定。
 *
 * @property maxGapMs 窓と窓の間（前の窓の終わりから、次の窓のデコードを始める位置まで）がこれより長ければ、
 *   その間はデコードせずに圧縮のまま読み飛ばす（MediaCodec を作り直す）。
 * @property maxRunWindows 1 つの MediaCodec で続けて切り出す窓の数の上限。
 * @property maxRunDurationMs 1 つの MediaCodec で続けてデコードする長さ（ミリ秒）の上限。
 * @property queueCapacity 切り出した窓を推論のワーカーに渡す待ち行列の長さの上限。
 */
internal data class StreamingExtractionOptions(
    val maxGapMs: Long = DEFAULT_MAX_GAP_MS,
    val maxRunWindows: Int = DEFAULT_MAX_RUN_WINDOWS,
    val maxRunDurationMs: Long = DEFAULT_MAX_RUN_DURATION_MS,
    val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
) {
    init {
        require(maxGapMs >= 0L) { "maxGapMs=$maxGapMs" }
        require(maxRunWindows >= 1) { "maxRunWindows=$maxRunWindows" }
        require(maxRunDurationMs > 0L) { "maxRunDurationMs=$maxRunDurationMs" }
        require(queueCapacity >= 1) { "queueCapacity=$queueCapacity" }
    }

    companion object {
        /** 既定の [maxGapMs]（MP3 を 10 秒デコードする時間は、MediaCodec を作り直す時間と同じくらいと見込む。実機で調整する）。 */
        const val DEFAULT_MAX_GAP_MS: Long = 10_000L

        /** 既定の [maxRunWindows]。 */
        const val DEFAULT_MAX_RUN_WINDOWS: Int = 16

        /** 既定の [maxRunDurationMs]。 */
        const val DEFAULT_MAX_RUN_DURATION_MS: Long = 120_000L

        /**
         * 既定の [queueCapacity]（[DEFAULT_MAX_RUN_WINDOWS] と同じ）。MP3 をまとめて渡す run は、出力の長さを確かめてから run の窓を
         * まとめて渡すので、推論のワーカーが追いついている間は、まとめて渡すときにデコードを止めずに済む長さにする
         * （1 窓は 16kHz で最大 2.8 秒・約 88 KB。16 窓で約 1.4 MB）。
         */
        const val DEFAULT_QUEUE_CAPACITY: Int = DEFAULT_MAX_RUN_WINDOWS
    }
}

/**
 * 窓（開始位置の順に並んでいること）を、1 つの MediaCodec で続けてデコードする区間（run）に分ける。返す範囲は [windows] の中の位置。
 *
 * 次の窓のデコードを始める位置（開始位置の [preRollMs] 手前）が今の run の終わりから [StreamingExtractionOptions.maxGapMs] より
 * 後ろなら分ける（間は圧縮のまま読み飛ばす）。窓の数や長さが上限を超える場合も分ける。
 */
internal fun planStreamRuns(
    windows: List<StreamWindowRequest>,
    preRollMs: Long,
    options: StreamingExtractionOptions,
): List<IntRange> {
    if (windows.isEmpty()) return emptyList()
    val runs = ArrayList<IntRange>()
    var runStart = 0
    var runDecodeStartMs = (windows[0].startMs - preRollMs).coerceAtLeast(0L)
    var runEndMs = windows[0].endMs
    for (pos in 1 until windows.size) {
        val window = windows[pos]
        val decodeStartMs = (window.startMs - preRollMs).coerceAtLeast(0L)
        val newEndMs = max(runEndMs, window.endMs)
        val split = decodeStartMs - runEndMs > options.maxGapMs ||
            pos - runStart >= options.maxRunWindows ||
            newEndMs - runDecodeStartMs > options.maxRunDurationMs
        if (split) {
            runs += runStart until pos
            runStart = pos
            runDecodeStartMs = decodeStartMs
            runEndMs = window.endMs
        } else {
            runEndMs = newEndMs
        }
    }
    runs += runStart until windows.size
    return runs
}

/**
 * 1 つの run の中で、デコーダの出力（ネイティブのレートのモノラル）から各窓の 16kHz の PCM を切り出す。
 *
 * 今の方式（[ChunkPcmExtractor.extractChunkPcm16LeBytes]）は、窓ごとに着地フレーム（開始位置の 500ms 手前以降の最初のフレーム）から
 * 新しい MediaCodec でデコードし、そのフレームの時刻をラベル（16kHz のサンプル番号）の起点にして新しいリサンプラを通す。
 * ここでも同じ値になるように、窓ごとに次のようにする。
 * - 着地フレームの時刻 `landedUs` から、run の出力の中での位置 `n = round((landedUs − runLandedUs) × rate / 10^6)` を求める
 *   （デコーダの出力はフレームの時刻とそろっている。セッションの先頭で削られる遅延は、run の先頭と窓ごとのデコードで同じなので打ち消し合う）。
 * - ラベルの起点 `c = floorDiv(landedUs × 16000, 10^6)` は今の方式と同じにする。
 * - リサンプラは、出力の格子が繰り返す周期（[StreamingResampler.inputPeriod] / [StreamingResampler.outputPeriod]）の m 倍だけ進めた
 *   位置 `n + m × inputPeriod` から始め、ラベル `c + m × outputPeriod` から数える。m は、先頭のゼロの補いの影響
 *   （[StreamingResampler.startupOutputs]）が窓の先頭より前に収まる最大の値。こうすると窓の中の出力は、今の方式と同じ入力・同じ位相から計算される。
 *
 * デコーダの出力（ネイティブの PCM）が窓の付近で今の方式と同じなら、切り出す PCM も同じになる（MP3 は 500ms の余白で
 * デコーダの状態がそろう見込み。実機での一致は計測ツールの `pcmSame` で確かめる）。
 *
 * スレッドセーフではない（1 つの run の中で 1 つのスレッドから使う）。
 *
 * @param runLandedUs run の最初に MediaCodec へ渡したフレームの時刻（µs）。
 * @param outputRate 切り出す PCM のサンプルレート（Hz）。
 */
internal class StreamWindowCutter(
    private val runLandedUs: Long,
    private val outputRate: Int,
    requests: List<StreamWindowRequest>,
    private val timings: LidExtractionTimings?,
) {
    private enum class State { WAITING, ACTIVE, DONE, FAILED }

    private inner class Window(val request: StreamWindowRequest) {
        val targetUs: Long = (request.startMs * 1_000L - MediaCodecAudioStreamDecoder.SEEK_PRE_ROLL_MS * 1_000L).coerceAtLeast(0L)
        val startSample: Long = request.startMs * outputRate / 1_000L
        val endSample: Long = request.endMs * outputRate / 1_000L
        var landedUs: Long = UNKNOWN_US
        var state: State = State.WAITING
        var nativeStart: Long = 0L
        var labelNext: Long = 0L
        var resampler: StreamingResampler? = null
        var out: ShortArray = EMPTY_SHORTS
        var written: Int = 0
        var completedResult: StreamWindowResult? = null

        val sink = SampleSink { samples, count ->
            if (state != State.ACTIVE) return@SampleSink
            val pcmStartNanos = timings.startNanos()
            val blockStart = labelNext
            val blockEnd = labelNext + count
            labelNext = blockEnd
            val sliceStart = max(startSample, blockStart)
            val sliceEnd = min(endSample, blockEnd)
            if (sliceStart < sliceEnd) {
                var idx = (sliceStart - blockStart).toInt()
                val localTo = (sliceEnd - blockStart).toInt()
                while (idx < localTo && written < out.size) {
                    out[written] = ChunkPcmExtractor.floatToPcm16(samples[idx]).toShort()
                    written++
                    idx++
                }
            }
            timings.addSince(pcmStartNanos) { pcmNanos }
            if (labelNext >= endSample || written >= out.size) {
                complete()
            }
        }

        /** 窓を終える（今の方式と同じく、1 サンプルも書けなければ `null`）。 */
        fun complete() {
            if (state == State.DONE) return
            state = State.DONE
            val shorts = when {
                written <= 0 -> null
                written == out.size -> out
                else -> out.copyOf(written)
            }
            completedResult = StreamWindowResult.Pcm(request.index, shorts)
            out = EMPTY_SHORTS
        }
    }

    private val windows: List<Window> = requests.map { Window(it) }

    /** 着地フレームがまだ決まっていない最初の窓（窓は開始位置の順なので、着地はこの順に決まる）。 */
    private var nextLandingPos = 0

    /** run の出力のうち、これまでに受け取ったサンプル数（ネイティブのレート）。 */
    private var nativePos = 0L

    /** デコーダの出力のサンプルレート（最初の [accept] で決める。0 はまだ）。 */
    private var nativeRate = 0

    private val resamplerPool = ArrayDeque<StreamingResampler>()
    private val scratch = FloatArray(PIECE_SAMPLES)

    /** MediaCodec に渡したフレームの時刻 [ptsUs]（渡した順）を受け取り、着地フレームが決まる窓に記録する。 */
    fun onFrameFed(ptsUs: Long) {
        while (nextLandingPos < windows.size && ptsUs >= windows[nextLandingPos].targetUs) {
            windows[nextLandingPos].landedUs = ptsUs
            nextLandingPos++
        }
    }

    /**
     * デコーダの出力（モノラル、サンプルレート [rate]）[mono] の先頭 [count] サンプルを受け取り、各窓に流す。
     * run の中でレートは変えない（呼び出し側は、出力を受け取り始めたあとでレートが変わったらこの run をやめる）。
     */
    fun accept(mono: FloatArray, count: Int, rate: Int) {
        if (count <= 0) return
        if (nativeRate == 0) nativeRate = rate
        require(rate == nativeRate) { "rate changed: $nativeRate -> $rate" }
        val bufferStart = nativePos
        val bufferEnd = nativePos + count
        for (window in windows) {
            if (window.state == State.WAITING && window.landedUs != UNKNOWN_US) activate(window, bufferStart)
            if (window.state != State.ACTIVE) continue
            val from = max(window.nativeStart, bufferStart)
            if (from >= bufferEnd) continue
            var offset = (from - bufferStart).toInt()
            val resampler = window.resampler ?: continue
            while (offset < count && window.state == State.ACTIVE) {
                val length = min(PIECE_SAMPLES, count - offset)
                System.arraycopy(mono, offset, scratch, 0, length)
                resampler.processTimed(scratch, length, window.sink, timings)
                offset += length
            }
            if (window.state == State.DONE) release(window)
        }
        nativePos = bufferEnd
    }

    /** 音声の終わりに達した（run の終わりがファイルの終わり）。今の方式と同じく、リサンプラの末尾を出して窓を終える。 */
    fun finishAtEndOfInput() {
        for (window in windows) {
            if (window.state != State.ACTIVE) continue
            window.resampler?.finish(window.sink)
            window.complete()
            release(window)
        }
    }

    /** 終わった窓の結果を取り出す（窓の順）。 */
    fun drainCompleted(): List<StreamWindowResult> {
        var drained: ArrayList<StreamWindowResult>? = null
        for (window in windows) {
            val result = window.completedResult ?: continue
            window.completedResult = null
            if (drained == null) drained = ArrayList()
            drained += result
        }
        return drained ?: emptyList()
    }

    /**
     * run を終えたあとに、まだ結果を返していない窓の結果を返す。
     *
     * @param reachedEndOfInput run の終わりがファイルの終わりだった。窓が終わる前に音声が終わった場合は、今の方式と同じく
     *   書けた分（なければ `null`）を返す。そうでない（run の終わりまでデコードしたのに窓が終わっていない）場合は、念のため今の方式に戻す。
     */
    fun finishRemaining(reachedEndOfInput: Boolean): List<StreamWindowResult> {
        val results = ArrayList<StreamWindowResult>()
        for (window in windows) {
            when (window.state) {
                State.DONE -> window.completedResult?.let { results += it }

                State.FAILED -> results += StreamWindowResult.Fallback(window.request.index)

                State.WAITING, State.ACTIVE -> {
                    if (reachedEndOfInput) {
                        window.complete()
                        window.completedResult?.let { results += it }
                    } else {
                        window.state = State.FAILED
                        results += StreamWindowResult.Fallback(window.request.index)
                    }
                    release(window)
                }
            }
            window.completedResult = null
        }
        return results
    }

    private fun activate(window: Window, bufferStart: Long) {
        val resampler = resamplerPool.removeLastOrNull()?.also { it.reset() }
            ?: StreamingResampler(inputRate = nativeRate, outputRate = outputRate)
        val deltaUs = (window.landedUs - runLandedUs).coerceAtLeast(0L)
        val landingNative = (deltaUs * nativeRate + MICROS_PER_SECOND / 2) / MICROS_PER_SECOND
        val labelOrigin = Math.floorDiv(window.landedUs * outputRate, MICROS_PER_SECOND)
        val guard = resampler.startupOutputs + GUARD_OUTPUTS
        val periods = Math.floorDiv(window.startSample - labelOrigin - guard, resampler.outputPeriod.toLong()).coerceAtLeast(0L)
        window.nativeStart = landingNative + periods * resampler.inputPeriod
        window.labelNext = labelOrigin + periods * resampler.outputPeriod
        if (window.nativeStart < bufferStart) {
            // 窓の始まりをすでに過ぎていた（起きないはず）。今の方式に戻す
            resamplerPool.addLast(resampler)
            window.state = State.FAILED
            return
        }
        val expected = (window.endSample - window.startSample).coerceIn(0L, MAX_WINDOW_SAMPLES.toLong()).toInt()
        window.out = ShortArray(expected)
        window.resampler = resampler
        window.state = State.ACTIVE
        if (expected == 0) {
            window.complete()
            release(window)
        }
    }

    private fun release(window: Window) {
        val resampler = window.resampler ?: return
        window.resampler = null
        if (resamplerPool.size < MAX_POOLED_RESAMPLERS) resamplerPool.addLast(resampler)
    }

    private companion object {
        const val UNKNOWN_US: Long = Long.MIN_VALUE
        const val MICROS_PER_SECOND: Long = 1_000_000L

        /** 先頭のゼロの補いの影響に加えて、窓の先頭より前に取る出力の数（念のための余裕）。 */
        const val GUARD_OUTPUTS: Int = 16

        /** リサンプラに一度に渡す入力の長さ（窓が終わったあとの無駄な計算をこの長さまでにする）。 */
        const val PIECE_SAMPLES: Int = 4_096

        /** 窓の長さの上限（今の方式の上限 4MiB と同じ）。 */
        const val MAX_WINDOW_SAMPLES: Int = 2 * 1024 * 1024

        const val MAX_POOLED_RESAMPLERS: Int = 4
        val EMPTY_SHORTS = ShortArray(0)
    }
}

/**
 * 順に流す切り出し（[LidExtractionMethod.STREAMING]）の本体（[MediaCodecAudioStreamDecoder.streamLanguageWindows] から使う）。
 *
 * - 窓を run に分け（[planStreamRuns]）、run ごとに最初の窓の位置へ抽出器を合わせて（今の方式と同じ [MediaCodecAudioStreamDecoder.positionExtractorForRange]）、
 *   新しい MediaCodec で run の終わり（最後の窓の終わり + 余白）までデコードして EOS を渡す。run の間は圧縮のまま読み飛ばす。
 * - 各窓は [StreamWindowCutter] で今の方式と同じ値になるように切り出す。
 * - MP3 のフレームをまとめて渡した run は、EOS のあとで出力の長さを確かめてから窓を返す（ずれていたら、その run の窓は今の方式に戻し、
 *   以後はまとめない。ADR 0005 追記の安全策と同じ）。まとめない run は、窓が終わりしだい返す。
 * - 共有の抽出器とは別の抽出器を開き、終わったら（キャンセル・エラーでも）閉じる。MediaCodec も run ごとに必ず閉じる。
 * - MediaCodec を作れない・ファイルが変わった・予期しないエラーのときは、まだ返していない窓をすべて [StreamWindowResult.Fallback] にする。
 */
internal class LidStreamingExtractor(
    private val extractorOpener: (File) -> ExtractorSession,
    private val codecCreator: (String, MediaFormat) -> CodecSession,
    private val elapsedRealtimeMs: () -> Long,
    private val isMp3BatchingSupported: (String) -> Boolean,
    private val onMp3BatchMismatch: () -> Unit,
    private val options: StreamingExtractionOptions,
    private val timings: LidExtractionTimings?,
) {
    /** run の中で使う、トラックの情報。 */
    private class TrackInfo(
        val format: MediaFormat,
        val mime: String,
    )

    /**
     * [windows]（開始位置の順）を切り出して、窓ごとにちょうど 1 回 [emit] に渡す。
     *
     * キャンセル以外の例外は投げない（まだ返していない窓を [StreamWindowResult.Fallback] にして返す）。
     */
    suspend fun extract(
        file: File,
        windows: List<StreamWindowRequest>,
        outputSampleRate: Int,
        emit: suspend (StreamWindowResult) -> Unit,
    ) {
        if (windows.isEmpty()) return
        val startNanos = timings.startNanos()
        val emitted = HashSet<Int>()
        suspend fun emitOnce(result: StreamWindowResult) {
            if (!emitted.add(result.index)) return
            if (result is StreamWindowResult.Pcm) timings?.streamWindows?.incrementAndGet()
            val waitStartNanos = timings.startNanos()
            emit(result)
            timings.addSince(waitStartNanos) { queueWaitNanos }
        }
        suspend fun fallbackRemaining() {
            for (window in windows) {
                if (window.index !in emitted) {
                    timings?.fallbacks?.incrementAndGet()
                    emitOnce(StreamWindowResult.Fallback(window.index))
                }
            }
        }

        var extractor: ExtractorSession? = null
        try {
            val openStartNanos = timings.startNanos()
            val opened = try {
                extractorOpener(file)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            } finally {
                timings.addSince(openStartNanos) { extractorOpenNanos }
            }
            if (opened == null) {
                fallbackRemaining()
                return
            }
            extractor = opened
            timings?.extractorOpens?.incrementAndGet()
            val key = RangeSessionKey.of(file)
            val track = selectAudioTrack(opened)
            if (track == null) {
                // 音声のトラックがない（今の方式でも切り出せない）
                for (window in windows) emitOnce(StreamWindowResult.Pcm(window.index, null))
                return
            }
            val runs = planStreamRuns(windows, MediaCodecAudioStreamDecoder.SEEK_PRE_ROLL_MS, options)
            for (run in runs) {
                currentCoroutineContext().ensureActive()
                if (RangeSessionKey.of(file) != key) {
                    // 途中でファイルが変わった。残りは今の方式（開き直して読む）に任せる
                    fallbackRemaining()
                    return
                }
                val ok = decodeRun(opened, track, windows.subList(run.first, run.last + 1), outputSampleRate) { emitOnce(it) }
                if (!ok) {
                    fallbackRemaining()
                    return
                }
            }
            fallbackRemaining()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            fallbackRemaining()
        } finally {
            extractor?.let { runCatching { it.close() } }
            timings.addSince(startNanos) { extractNanos }
        }
    }

    private fun selectAudioTrack(extractor: ExtractorSession): TrackInfo? {
        val trackIndex = (0 until extractor.trackCount).firstOrNull { idx ->
            val mime = extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)
            mime != null && (mime.startsWith("audio/") || mime == "application/ogg")
        } ?: return null
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)?.takeIf { it.isNotBlank() } ?: return null
        extractor.selectTrack(trackIndex)
        return TrackInfo(format = format, mime = mime)
    }

    /**
     * 1 つの run（[runWindows]）をデコードして窓を切り出し、[emit] に渡す。
     *
     * @return 続けてよければ `true`。MediaCodec を作れない・止まったなど、この方式を続けられない場合は `false`
     *   （呼び出し側が、まだ返していない窓を今の方式に戻す）。
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun decodeRun(
        extractor: ExtractorSession,
        track: TrackInfo,
        runWindows: List<StreamWindowRequest>,
        outputSampleRate: Int,
        emit: suspend (StreamWindowResult) -> Unit,
    ): Boolean {
        timings?.streamRuns?.incrementAndGet()
        val positionStartNanos = timings.startNanos()
        val runLandedUs = MediaCodecAudioStreamDecoder.positionExtractorForRange(extractor, runWindows.first().startMs * 1_000L, timings)
        timings.addSince(positionStartNanos) { positionNanos }
        if (runLandedUs == MediaCodecAudioStreamDecoder.END_OF_INPUT_US) {
            // 音声の終わりより後ろ（今の方式でも切り出せない）
            for (window in runWindows) emit(StreamWindowResult.Pcm(window.index, null))
            return true
        }

        val trackFormat = track.format
        val encoderDelay = trackFormat.intOrZero(MediaFormat.KEY_ENCODER_DELAY)
        val encoderPadding = trackFormat.intOrZero(MediaFormat.KEY_ENCODER_PADDING)
        val formatDurationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
            trackFormat.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0L }
        } else {
            null
        }
        var sampleRate = trackFormat.intOrZero(MediaFormat.KEY_SAMPLE_RATE)
        var channels = trackFormat.intOrZero(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = trackFormat.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
        // 窓の終わりのあと、run の EOS（末尾のパディングの削り）の影響が届かないだけデコードする
        val paddingUs = if (sampleRate > 0) encoderPadding.coerceAtLeast(0).toLong() * 1_000_000L / sampleRate else 0L
        val runEndUs = runWindows.maxOf { it.endMs } * 1_000L + RUN_TAIL_MARGIN_US + paddingUs

        val codecStartNanos = timings.startNanos()
        val codec = try {
            codecCreator(track.mime, trackFormat)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // 資源不足などで MediaCodec を作れない。今の方式に戻す
            return false
        } finally {
            timings.addSince(codecStartNanos) { codecCreateNanos }
        }
        timings?.codecCreates?.incrementAndGet()

        return codec.useTimed(timings) { decoder ->
            val isMp3 = track.mime == MediaCodecAudioStreamDecoder.MIME_TYPE_MP3
            val batchLimit = if (isMp3 && isMp3BatchingSupported(decoder.codecName)) {
                MediaCodecAudioStreamDecoder.MP3_BATCH_FRAMES_FULL_DECODE
            } else {
                1
            }
            // まとめて渡す run は、出力の長さを確かめるまで窓を返さない
            val holdUntilVerified = batchLimit > 1
            val held = ArrayList<StreamWindowResult>()
            var batchedInputQueued = false
            var expectedMp3Samples = 0L
            var expectedMp3SamplesKnown = isMp3

            val cutter = StreamWindowCutter(
                runLandedUs = runLandedUs,
                outputRate = outputSampleRate,
                requests = runWindows,
                timings = timings,
            )
            var outputStarted = false
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var extractorDone = false
            var reachedEndOfInput = false
            var lastInputPts = Long.MIN_VALUE
            var repeatedPtsCount = 0
            var stuckAtEndWorkaround = false
            var shortBuf = ShortArray(0)
            var floatBuf = FloatArray(0)
            var monoBuf = FloatArray(4096)
            var nativeFrames = 0L
            var lastActivityMs = elapsedRealtimeMs()

            suspend fun deliver(results: List<StreamWindowResult>) {
                if (holdUntilVerified) {
                    held += results
                } else {
                    for (result in results) emit(result)
                }
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                if (elapsedRealtimeMs() - lastActivityMs > MediaCodecAudioStreamDecoder.STALL_TIMEOUT_MS) return@useTimed false

                var fedInput = false
                while (!inputEos) {
                    val inIdx = decoder.dequeueInputBuffer(0L)
                    if (inIdx < 0) break
                    fedInput = true
                    lastActivityMs = elapsedRealtimeMs()
                    val inBuf = decoder.getInputBuffer(inIdx) ?: return@useTimed false
                    inBuf.clear()
                    var queuedBytes = 0
                    var queuedSamples = 0
                    var firstPts = 0L
                    while (!extractorDone && queuedSamples < batchLimit) {
                        if (queuedSamples > 0 && inBuf.capacity() - queuedBytes < MediaCodecAudioStreamDecoder.MAX_MP3_FRAME_BYTES) break
                        val pts = extractor.sampleTime
                        if (pts != MediaCodecAudioStreamDecoder.END_OF_INPUT_US && pts >= runEndUs) {
                            // run の終わりまで渡した（次の run はこのフレームから読み飛ばす）
                            extractorDone = true
                            break
                        }
                        val sampleSize = extractor.readSampleData(inBuf, queuedBytes)
                        if (sampleSize < 0) {
                            extractorDone = true
                            reachedEndOfInput = true
                            break
                        }
                        val mp3Frame = if (isMp3) Mp3FrameHeader.parse(inBuf, queuedBytes) else null
                        val batchable = batchLimit > 1 && mp3Frame != null && mp3Frame.frameSizeBytes == sampleSize
                        if (queuedSamples > 0 && !batchable) break
                        if (queuedSamples == 0) firstPts = pts
                        cutter.onFrameFed(pts)
                        queuedBytes += sampleSize
                        queuedSamples++
                        if (mp3Frame != null) {
                            expectedMp3Samples += mp3Frame.samplesPerFrame
                        } else {
                            expectedMp3SamplesKnown = false
                        }
                        if (pts != Long.MIN_VALUE && pts <= lastInputPts) {
                            repeatedPtsCount++
                        } else {
                            repeatedPtsCount = 0
                        }
                        if (pts > lastInputPts) lastInputPts = pts
                        if (!extractor.advance()) {
                            extractorDone = true
                            reachedEndOfInput = true
                        } else if (repeatedPtsCount >= MediaCodecAudioStreamDecoder.REPEATED_PTS_LIMIT) {
                            // ADR 0005 決定 4: FLAC で最後のサンプルの時刻が進まず EOS にならない現象への対策
                            extractorDone = true
                            reachedEndOfInput = true
                            stuckAtEndWorkaround = true
                        }
                        if (!batchable) break
                    }
                    if (queuedSamples == 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputEos = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, queuedBytes, firstPts, 0)
                        if (queuedSamples > 1) batchedInputQueued = true
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, if (fedInput) 0L else MediaCodecAudioStreamDecoder.OUTPUT_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        lastActivityMs = elapsedRealtimeMs()
                        val outFormat = decoder.outputFormat
                        val newRate = outFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        val newChannels = outFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        if (newRate <= 0 || newChannels <= 0) return@useTimed false
                        // 出力を受け取り始めたあとでレートが変わったら、窓の時刻を合わせられないので今の方式に戻す
                        if (outputStarted && newRate != sampleRate) return@useTimed false
                        sampleRate = newRate
                        channels = newChannels
                        floatPcm = outFormat.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                    }

                    outIdx >= 0 -> {
                        lastActivityMs = elapsedRealtimeMs()
                        if (info.size > 0) {
                            if (sampleRate <= 0 || channels <= 0) {
                                decoder.releaseOutputBuffer(outIdx, false)
                                return@useTimed false
                            }
                            val outBuf = decoder.getOutputBuffer(outIdx) ?: return@useTimed false
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.order(ByteOrder.nativeOrder())
                            // ADR 0005 決定 4: FLAC で末尾の対策が働いた場合は、抽出器が示す長さで打ち切る
                            val maxNativeFrames = if ((stuckAtEndWorkaround || repeatedPtsCount > 0) && formatDurationUs != null) {
                                ((formatDurationUs - runLandedUs).coerceAtLeast(0L) * sampleRate) / 1_000_000L
                            } else {
                                Long.MAX_VALUE
                            }
                            val bytesPerSample = if (floatPcm) 4 else 2
                            val rawFrames = info.size / bytesPerSample / channels
                            val allowedFrames = min(rawFrames.toLong(), (maxNativeFrames - nativeFrames).coerceAtLeast(0L)).toInt()
                            if (allowedFrames > 0) {
                                val mixStartNanos = timings.startNanos()
                                if (monoBuf.size < allowedFrames) monoBuf = FloatArray(allowedFrames)
                                if (floatPcm) {
                                    val needed = allowedFrames * channels
                                    if (floatBuf.size < needed) floatBuf = FloatArray(needed)
                                    outBuf.asFloatBuffer().get(floatBuf, 0, needed)
                                    PcmDownmix.downmixFloat(floatBuf, allowedFrames, channels, monoBuf)
                                } else {
                                    val needed = allowedFrames * channels
                                    if (shortBuf.size < needed) shortBuf = ShortArray(needed)
                                    outBuf.asShortBuffer().get(shortBuf, 0, needed)
                                    PcmDownmix.downmixS16(shortBuf, allowedFrames, channels, monoBuf)
                                }
                                timings.addSince(mixStartNanos) { mixNanos }
                                nativeFrames += allowedFrames
                                timings?.decodedNativeSamples?.addAndGet(allowedFrames.toLong())
                                outputStarted = true
                                cutter.accept(monoBuf, allowedFrames, sampleRate)
                                decoder.releaseOutputBuffer(outIdx, false)
                                deliver(cutter.drainCompleted())
                            } else {
                                decoder.releaseOutputBuffer(outIdx, false)
                            }
                        } else {
                            decoder.releaseOutputBuffer(outIdx, false)
                        }
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
                    }
                }
            }

            if (batchedInputQueued && expectedMp3SamplesKnown && !stuckAtEndWorkaround &&
                MediaCodecAudioStreamDecoder.isMp3BatchOutputLengthMismatched(
                    decodedFrames = nativeFrames,
                    expectedFrames = expectedMp3Samples,
                    encoderDelay = encoderDelay,
                    encoderPadding = encoderPadding,
                )
            ) {
                // まとめて渡したフレームの一部がデコードされなかった（窓の時刻がずれる）。まとめて渡す run は窓を返していないので、
                // この run の窓をすべて今の方式に戻し、以後このデコーダではまとめない
                onMp3BatchMismatch()
                held.clear()
                timings?.fallbacks?.addAndGet(runWindows.size.toLong())
                for (window in runWindows) emit(StreamWindowResult.Fallback(window.index))
                return@useTimed true
            }
            if (reachedEndOfInput) cutter.finishAtEndOfInput()
            val remaining = cutter.drainCompleted() + cutter.finishRemaining(reachedEndOfInput)
            timings?.fallbacks?.addAndGet(remaining.count { it is StreamWindowResult.Fallback }.toLong())
            for (result in held) emit(result)
            for (result in remaining) emit(result)
            true
        }
    }

    private companion object {
        /** run の最後の窓の終わりのあとにデコードする余白（µs。フレームの長さとデコーダの末尾の処理より長くする）。 */
        const val RUN_TAIL_MARGIN_US: Long = 300_000L
    }
}

private fun MediaFormat.intOrZero(key: String): Int = intOr(key, 0)

private fun MediaFormat.intOr(key: String, default: Int): Int = if (containsKey(key)) getInteger(key) else default
