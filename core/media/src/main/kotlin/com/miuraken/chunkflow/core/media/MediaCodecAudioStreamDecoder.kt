package com.miuraken.chunkflow.core.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.inspector.MediaExtractorCompat
import com.miuraken.chunkflow.core.audio.DbfsQuantizer
import com.miuraken.chunkflow.core.audio.FeatureAccumulator
import com.miuraken.chunkflow.core.audio.PcmDownmix
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.audio.StreamingResampler
import com.miuraken.chunkflow.core.model.AnalysisError
import com.miuraken.chunkflow.core.model.Waveform
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [MediaCodecAudioStreamDecoder] が内部で扱う抽出器のセッション抽象（テスト差し替え用）。
 */
internal interface ExtractorSession : AutoCloseable {
    val trackCount: Int
    fun getTrackFormat(index: Int): MediaFormat
    fun selectTrack(index: Int)
    fun seekTo(timeUs: Long) {}
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int
    val sampleTime: Long
    fun advance(): Boolean
}

/**
 * [MediaCodecAudioStreamDecoder] が内部で扱うデコーダのセッション抽象（テスト差し替え用）。
 */
internal interface CodecSession : AutoCloseable {
    val outputFormat: MediaFormat
    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun getInputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)
    fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int
    fun getOutputBuffer(index: Int): ByteBuffer?
    fun releaseOutputBuffer(index: Int, render: Boolean)
    fun flush() {}
}

/**
 * `media3-inspector` の [MediaExtractorCompat] と [MediaCodec]（同期モード）を使った
 * [AudioStreamDecoder] の本番実装（§7.3.1、ADR 0005、ADR 0006）。
 *
 * - MP3 は [IndexSeekDataSource]（先頭フレームの `Xing`/`Info`/`VBRI` を 0 でマスクし、ファイル長を
 *   `C.LENGTH_UNSET` とする）および `Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING` を通して読み出し、
 *   再生側の ExoPlayer と時間軸を一致させる（ADR 0005、ADR 0006）。
 * - ギャップレス情報（`encoder-delay` / `encoder-padding`）を含む [MediaFormat] をそのまま [MediaCodec] に渡し、
 *   時刻は出力サンプル数の累計から求める（`presentationTimeUs` は時間軸に使わない。ADR 0005）。
 * - FLAC で終端の同じ `sampleTime` が繰り返されて EOS にならない問題に備え、時刻が進まない読み出しが
 *   [REPEATED_PTS_LIMIT] 回続いたら入力終端とみなし、抽出器が示す長さで出力を打ち切る（ADR 0005 決定 4）。
 * - チャンクの切り出し（[decodeFromPosition]）では、開始位置の手前まで圧縮データのまま読み飛ばし、
 *   同じファイルの抽出器を使い回す（F6.2。MP3 の `IndexSeeker` では、開いた直後のシークが先頭に着地するため）。
 */
@Singleton
@OptIn(UnstableApi::class)
class MediaCodecAudioStreamDecoder internal constructor(
    private val extractorOpener: (File) -> ExtractorSession,
    private val codecCreator: (String, MediaFormat) -> CodecSession,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS,
) : AudioStreamDecoder {

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        extractorOpener = { file -> openCompatExtractorSession(context, file) },
        codecCreator = { mime, format -> openMediaCodecSession(mime, format) },
    )

    /** チャンク切り出し用に開いたままにする抽出器を特定するキー（パス・更新時刻・長さが同じなら同じファイルとみなす）。 */
    private data class RangeSessionKey(
        val absolutePath: String,
        val lastModified: Long,
        val length: Long,
    )

    /** チャンク切り出し用に開いたままにしている抽出器。 */
    private class RangeExtractorSession(
        val key: RangeSessionKey,
        val extractor: ExtractorSession,
    )

    /** [rangeSession] を触るときに持つロック（抽出器はスレッドセーフではないため、切り出しを直列にする）。 */
    private val rangeSessionMutex = Mutex()

    /** [rangeSessionMutex] の中でだけ読み書きする。 */
    private var rangeSession: RangeExtractorSession? = null

    /** 切り出しの途中で解放を求められたとき、その切り出しの終わりに解放するための印。 */
    private val rangeSessionReleaseRequested = AtomicBoolean(false)

    override suspend fun decode(
        file: File,
        sink: SampleSink,
        outputSampleRate: Int,
        expectedDurationMs: Long?,
        onProgress: suspend (DecodeProgress) -> Unit,
    ): AudioDecodeResult {
        require(outputSampleRate > 0) { "outputSampleRate must be > 0: $outputSampleRate" }
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return AudioDecodeResult.Failure(AnalysisError.IO)
        }
        if (file.length() <= 0L) {
            return AudioDecodeResult.Failure(AnalysisError.UNSUPPORTED_FORMAT)
        }

        val extractor = try {
            extractorOpener(file)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: FileNotFoundException) {
            return AudioDecodeResult.Failure(AnalysisError.IO)
        } catch (_: SecurityException) {
            return AudioDecodeResult.Failure(AnalysisError.IO)
        } catch (_: IOException) {
            return AudioDecodeResult.Failure(AnalysisError.UNSUPPORTED_FORMAT)
        } catch (_: Exception) {
            return AudioDecodeResult.Failure(AnalysisError.UNSUPPORTED_FORMAT)
        }

        return try {
            extractor.use { ex ->
                decodeWithExtractor(
                    extractor = ex,
                    sink = sink,
                    outputSampleRate = outputSampleRate,
                    expectedDurationMs = expectedDurationMs,
                    onProgress = onProgress,
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: MediaCodec.CodecException) {
            AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED)
        } catch (_: IOException) {
            AudioDecodeResult.Failure(AnalysisError.IO)
        } catch (_: Exception) {
            AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED)
        }
    }

    /**
     * チャンク切り出し（F6.2）用に、[startPositionMs] の [SEEK_PRE_ROLL_MS] 手前からデコードして [sink] に渡す。
     * 打ち切りは [sink] 側で行う（チャンクの末尾に達したら例外を投げて止める）。
     *
     * - 開始位置の手前までは**圧縮データのまま読み飛ばし**、デコーダには渡さない（[positionExtractorForRange]）。
     *   MP3 は ADR 0006 により `IndexSeeker` を使うため、開いた直後の抽出器の `seekTo` はファイルの先頭に着地する。
     *   読み飛ばさないと、チャンクごとに先頭からデコードすることになる。
     * - 同じファイルが続く間は抽出器を開いたまま使い回す（読んだ範囲の索引が残るので、2 回目以降のシークは速い）。
     *   別のファイル・内容が変わったファイル・失敗・[releaseRangeSession] のときに閉じる。
     * - [MediaCodec] は呼び出しごとに作り直す（Codec2 がセッションの先頭で行うエンコーダ遅延の削り方を、
     *   全体をデコードするときと同じに保つため。ADR 0005 決定 2）。
     *
     * @param onSeekLandingUs 最初にデコーダへ渡すサンプルの時刻（µs）を受け取る。
     *   出力サンプル数の累計にこの時刻を足したものが、全体をデコードしたときと同じ時間軸になる（ADR 0005）。
     */
    internal suspend fun decodeFromPosition(
        file: File,
        startPositionMs: Long,
        sink: SampleSink,
        outputSampleRate: Int = StreamingResampler.DEFAULT_OUTPUT_RATE,
        onSeekLandingUs: (Long) -> Unit = {},
    ): AudioDecodeResult {
        require(outputSampleRate > 0) { "outputSampleRate must be > 0: $outputSampleRate" }
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return AudioDecodeResult.Failure(AnalysisError.IO)
        }
        if (file.length() <= 0L) {
            return AudioDecodeResult.Failure(AnalysisError.UNSUPPORTED_FORMAT)
        }

        return rangeSessionMutex.withLock {
            val extractor = try {
                obtainRangeExtractorLocked(file)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: FileNotFoundException) {
                return@withLock AudioDecodeResult.Failure(AnalysisError.IO)
            } catch (_: SecurityException) {
                return@withLock AudioDecodeResult.Failure(AnalysisError.IO)
            } catch (_: Exception) {
                return@withLock AudioDecodeResult.Failure(AnalysisError.UNSUPPORTED_FORMAT)
            }

            var keepSession = false
            try {
                val result = decodeWithExtractor(
                    extractor = extractor,
                    sink = sink,
                    outputSampleRate = outputSampleRate,
                    expectedDurationMs = null,
                    onProgress = {},
                    startPositionUs = startPositionMs.coerceAtLeast(0L) * 1_000L,
                    onSeekLandingUs = onSeekLandingUs,
                )
                keepSession = result is AudioDecodeResult.Success
                result
            } catch (ce: CancellationException) {
                // sink による打ち切り（チャンクの末尾に到達）とジョブのキャンセル。
                // どちらも抽出器の呼び出しの合間に起きるので、抽出器はそのまま使い回せる
                keepSession = true
                throw ce
            } catch (_: MediaCodec.CodecException) {
                AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED)
            } catch (_: IOException) {
                AudioDecodeResult.Failure(AnalysisError.IO)
            } catch (_: Exception) {
                AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED)
            } finally {
                if (!keepSession || rangeSessionReleaseRequested.getAndSet(false)) {
                    closeRangeSessionLocked()
                }
            }
        }
    }

    /**
     * チャンク切り出し用に開いたままにしている抽出器を解放する（文字起こしをオフにしたとき・リセットしたときなど）。
     * 切り出しの途中なら、その切り出しが終わったときに解放する。次の切り出しでは開き直す。
     */
    internal fun releaseRangeSession() {
        rangeSessionReleaseRequested.set(true)
        if (rangeSessionMutex.tryLock()) {
            try {
                rangeSessionReleaseRequested.set(false)
                closeRangeSessionLocked()
            } finally {
                rangeSessionMutex.unlock()
            }
        }
    }

    /** [rangeSessionMutex] の中で呼ぶ。同じファイルなら開いている抽出器を返し、違えば閉じて開き直す。 */
    private fun obtainRangeExtractorLocked(file: File): ExtractorSession {
        if (rangeSessionReleaseRequested.getAndSet(false)) {
            closeRangeSessionLocked()
        }
        val key = RangeSessionKey(
            absolutePath = file.absolutePath,
            lastModified = file.lastModified(),
            length = file.length(),
        )
        rangeSession?.let { current ->
            if (current.key == key) return current.extractor
        }
        closeRangeSessionLocked()
        val extractor = extractorOpener(file)
        rangeSession = RangeExtractorSession(key = key, extractor = extractor)
        return extractor
    }

    /** [rangeSessionMutex] の中で呼ぶ。開いたままの抽出器があれば閉じる。 */
    private fun closeRangeSessionLocked() {
        val current = rangeSession ?: return
        rangeSession = null
        runCatching { current.extractor.close() }
    }

    override suspend fun decodeCoarseOverviewWaveform(
        file: File,
        expectedDurationMs: Long?,
        targetColumns: Int,
        fineBinMs: Int,
    ): Waveform? {
        if (targetColumns <= 0 || fineBinMs <= 0) return null
        if (!file.exists() || !file.isFile || !file.canRead() || file.length() <= 0L) return null
        val extractor = try {
            extractorOpener(file)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            return null
        }

        return try {
            extractor.use { ex ->
                decodeCoarseOverviewWithExtractor(
                    extractor = ex,
                    expectedDurationMs = expectedDurationMs,
                    targetColumns = targetColumns,
                    fineBinMs = fineBinMs,
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "NestedBlockDepth")
    private suspend fun decodeCoarseOverviewWithExtractor(
        extractor: ExtractorSession,
        expectedDurationMs: Long?,
        targetColumns: Int,
        fineBinMs: Int,
    ): Waveform? {
        val trackIndex = (0 until extractor.trackCount).firstOrNull { idx ->
            val mime = extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)
            mime != null && (mime.startsWith("audio/") || mime == "application/ogg")
        } ?: return null

        val trackFormat = extractor.getTrackFormat(trackIndex)
        val mime = trackFormat.getString(MediaFormat.KEY_MIME)?.takeIf { it.isNotBlank() } ?: return null
        extractor.selectTrack(trackIndex)

        val formatDurationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
            trackFormat.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0L }
        } else {
            null
        }
        val totalDurationMs = expectedDurationMs?.takeIf { it > 0L }
            ?: formatDurationUs?.let { it / 1_000L }
            ?: return null

        val columns = targetColumns.coerceIn(1, MAX_OVERVIEW_COLUMNS)
        // 音声ファイルの秒数(ミリ秒) / 波形の横幅(カラム数) から、何ミリ秒ごとのデータが必要かを割り出す
        val stepMs = (totalDurationMs / columns).coerceAtLeast(fineBinMs.toLong())
        val stepUs = stepMs * 1_000L

        val codec = try {
            codecCreator(mime, trackFormat)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            return null
        }

        codec.use { decoder ->
            val info = MediaCodec.BufferInfo()
            var channels = trackFormat.intOrZero(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            var floatPcm = trackFormat.intOr(
                MediaFormat.KEY_PCM_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT,
            ) == AudioFormat.ENCODING_PCM_FLOAT

            val coarsePeak = ByteArray(columns)
            val coarseRms = ByteArray(columns)
            var shortBuf = ShortArray(0)
            var floatBuf = FloatArray(0)
            var monoBuf = FloatArray(4096)
            var decodedAnySample = false

            for (col in 0 until columns) {
                currentCoroutineContext().ensureActive()
                val targetTimeUs = col.toLong() * stepUs
                val currentPtsUs = extractor.sampleTime
                if (col > 0 && (currentPtsUs < 0L || targetTimeUs - currentPtsUs >= stepUs / 2L)) {
                    runCatching { extractor.seekTo(targetTimeUs) }
                    runCatching { decoder.flush() }
                }

                var colPeak = 0f
                var colRms = 0f
                var gotSample = false
                var attempts = 0
                var extractorEos = false

                while (!gotSample && attempts < MAX_COARSE_SAMPLE_ATTEMPTS) {
                    attempts++
                    if (!extractorEos) {
                        val inIdx = decoder.dequeueInputBuffer(COARSE_TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = decoder.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                inBuf.clear()
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(
                                        inIdx,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                    )
                                    extractorEos = true
                                } else {
                                    val pts = extractor.sampleTime.coerceAtLeast(0L)
                                    decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                    if (!extractor.advance()) {
                                        extractorEos = true
                                    }
                                }
                            }
                        }
                    }

                    val outIdx = decoder.dequeueOutputBuffer(info, COARSE_TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = decoder.outputFormat
                            channels = outFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels).coerceAtLeast(1)
                            val encoding = outFormat.intOr(
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT,
                            )
                            floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT
                        }

                        outIdx >= 0 -> {
                            if (info.size > 0) {
                                val outBuf = decoder.getOutputBuffer(outIdx)
                                if (outBuf != null) {
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + info.size)
                                    outBuf.order(ByteOrder.nativeOrder())
                                    val frameCount = if (floatPcm) {
                                        val sampleCount = info.size / 4
                                        val frames = sampleCount / channels
                                        if (frames > 0) {
                                            val needed = frames * channels
                                            if (floatBuf.size < needed) floatBuf = FloatArray(needed)
                                            outBuf.asFloatBuffer().get(floatBuf, 0, needed)
                                            if (monoBuf.size < frames) monoBuf = FloatArray(frames)
                                            PcmDownmix.downmixFloat(floatBuf, frames, channels, monoBuf)
                                        }
                                        frames
                                    } else {
                                        val sampleCount = info.size / 2
                                        val frames = sampleCount / channels
                                        if (frames > 0) {
                                            val needed = frames * channels
                                            if (shortBuf.size < needed) shortBuf = ShortArray(needed)
                                            outBuf.asShortBuffer().get(shortBuf, 0, needed)
                                            if (monoBuf.size < frames) monoBuf = FloatArray(frames)
                                            PcmDownmix.downmixS16(shortBuf, frames, channels, monoBuf)
                                        }
                                        frames
                                    }
                                    if (frameCount > 0) {
                                        var peak = 0f
                                        var sumSq = 0.0
                                        for (i in 0 until frameCount) {
                                            val v = abs(monoBuf[i])
                                            if (v > peak) peak = v
                                            sumSq += v.toDouble() * v.toDouble()
                                        }
                                        colPeak = peak.coerceIn(0f, 1f)
                                        colRms = sqrt(sumSq / frameCount).toFloat().coerceIn(0f, 1f)
                                        gotSample = true
                                        decodedAnySample = true
                                    }
                                }
                            }
                            decoder.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                break
                            }
                        }
                    }
                }
                coarsePeak[col] = DbfsQuantizer.quantize(colPeak)
                coarseRms[col] = DbfsQuantizer.quantize(colRms)
            }

            if (!decodedAnySample) return null
            return FeatureAccumulator.expandCoarseToFineWaveform(
                coarsePeak = coarsePeak,
                coarseRms = coarseRms,
                totalDurationMs = totalDurationMs,
                fineBinMs = fineBinMs,
            )
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun decodeWithExtractor(
        extractor: ExtractorSession,
        sink: SampleSink,
        outputSampleRate: Int,
        expectedDurationMs: Long?,
        onProgress: suspend (DecodeProgress) -> Unit,
        startPositionUs: Long? = null,
        onSeekLandingUs: (Long) -> Unit = {},
    ): AudioDecodeResult {
        val trackIndex = (0 until extractor.trackCount).firstOrNull { idx ->
            val mime = extractor.getTrackFormat(idx).getString(MediaFormat.KEY_MIME)
            mime != null && (mime.startsWith("audio/") || mime == "application/ogg")
        } ?: return AudioDecodeResult.Failure(AnalysisError.UNSUPPORTED_FORMAT)

        val trackFormat = extractor.getTrackFormat(trackIndex)
        val mime = trackFormat.getString(MediaFormat.KEY_MIME)
            ?.takeIf { it.isNotBlank() }
            ?: return AudioDecodeResult.Failure(AnalysisError.UNSUPPORTED_FORMAT)

        extractor.selectTrack(trackIndex)
        if (startPositionUs != null) {
            val landedUs = positionExtractorForRange(extractor, startPositionUs)
            onSeekLandingUs(if (landedUs == END_OF_INPUT_US) startPositionUs else landedUs)
        }

        val encoderDelay = trackFormat.intOrZero(MediaFormat.KEY_ENCODER_DELAY)
        val encoderPadding = trackFormat.intOrZero(MediaFormat.KEY_ENCODER_PADDING)
        val formatDurationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
            trackFormat.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0L }
        } else {
            null
        }
        val totalDurationMs = expectedDurationMs?.takeIf { it > 0L }
            ?: formatDurationUs?.let { it / 1_000L }

        val codec = try {
            codecCreator(mime, trackFormat)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IllegalArgumentException) {
            return AudioDecodeResult.Failure(AnalysisError.UNSUPPORTED_FORMAT)
        } catch (_: IOException) {
            return AudioDecodeResult.Failure(AnalysisError.UNSUPPORTED_FORMAT)
        } catch (_: Exception) {
            return AudioDecodeResult.Failure(AnalysisError.UNSUPPORTED_FORMAT)
        }

        codec.use { decoder ->
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var extractorDone = false
            var lastInputPts = Long.MIN_VALUE
            var repeatedPtsCount = 0
            var stuckAtEndWorkaround = false

            var sampleRate = trackFormat.intOrZero(MediaFormat.KEY_SAMPLE_RATE)
            var channels = trackFormat.intOrZero(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = trackFormat.intOr(
                MediaFormat.KEY_PCM_ENCODING,
                AudioFormat.ENCODING_PCM_16BIT,
            ) == AudioFormat.ENCODING_PCM_FLOAT

            var resampler: StreamingResampler? = if (sampleRate > 0 && channels > 0) {
                StreamingResampler(inputRate = sampleRate, outputRate = outputSampleRate)
            } else {
                null
            }

            var shortBuf = ShortArray(0)
            var floatBuf = FloatArray(0)
            var monoBuf = FloatArray(4096)
            var decodedFrames = 0L

            var lastActivityMs = elapsedRealtimeMs()
            var lastProgressEmitMs = lastActivityMs

            while (true) {
                currentCoroutineContext().ensureActive()

                val nowMs = elapsedRealtimeMs()
                if (nowMs - lastActivityMs > STALL_TIMEOUT_MS) {
                    return AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED)
                }

                // 入力バッファが空いている限りノンブロッキングで供給する（ADR 0005）
                var fedInput = false
                while (!inputEos) {
                    val inIdx = decoder.dequeueInputBuffer(0L)
                    if (inIdx < 0) break
                    fedInput = true
                    lastActivityMs = elapsedRealtimeMs()

                    val inBuf = decoder.getInputBuffer(inIdx)
                        ?: return AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED)
                    inBuf.clear()
                    val sampleSize = if (extractorDone) -1 else extractor.readSampleData(inBuf, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(
                            inIdx,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEos = true
                    } else {
                        val pts = extractor.sampleTime
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                        if (pts != Long.MIN_VALUE && pts <= lastInputPts) {
                            repeatedPtsCount++
                        } else {
                            repeatedPtsCount = 0
                        }
                        if (pts > lastInputPts) {
                            lastInputPts = pts
                        }
                        if (!extractor.advance()) {
                            extractorDone = true
                        } else if (repeatedPtsCount >= REPEATED_PTS_LIMIT) {
                            // ADR 0005 決定 4: FLAC で最後のサンプルの時刻が進まず EOS にならない現象への対策
                            extractorDone = true
                            stuckAtEndWorkaround = true
                        }
                    }
                }

                val outIdx = decoder.dequeueOutputBuffer(info, if (fedInput) 0L else OUTPUT_TIMEOUT_US)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        lastActivityMs = elapsedRealtimeMs()
                        val outFormat = decoder.outputFormat
                        val newRate = outFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        val newChannels = outFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        val encoding = outFormat.intOr(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        if (newRate <= 0 || newChannels <= 0) {
                            return AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED)
                        }
                        if (resampler == null || newRate != sampleRate) {
                            resampler?.finish(sink)
                            resampler = StreamingResampler(inputRate = newRate, outputRate = outputSampleRate)
                        }
                        sampleRate = newRate
                        channels = newChannels
                        floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT
                    }

                    outIdx >= 0 -> {
                        lastActivityMs = elapsedRealtimeMs()
                        if (info.size > 0) {
                            if (sampleRate <= 0 || channels <= 0) {
                                decoder.releaseOutputBuffer(outIdx, false)
                                return AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED)
                            }
                            val activeResampler = resampler ?: StreamingResampler(
                                inputRate = sampleRate,
                                outputRate = outputSampleRate,
                            ).also { resampler = it }

                            val outBuf = decoder.getOutputBuffer(outIdx)
                                ?: return AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED)
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            outBuf.order(ByteOrder.nativeOrder())

                            // ADR 0005 決定 4: FLAC 等で末尾ワークアラウンドが発動した場合、または同一 PTS 反復中は抽出器の durationUs で打ち切る
                            val maxTotalFrames = if ((stuckAtEndWorkaround || repeatedPtsCount > 0) && formatDurationUs != null) {
                                (formatDurationUs * sampleRate) / 1_000_000L
                            } else {
                                Long.MAX_VALUE
                            }

                            if (floatPcm) {
                                val sampleCount = info.size / 4
                                val rawFrames = sampleCount / channels
                                val allowedFrames = min(
                                    rawFrames.toLong(),
                                    (maxTotalFrames - decodedFrames).coerceAtLeast(0L),
                                ).toInt()
                                if (allowedFrames > 0) {
                                    val neededFloats = allowedFrames * channels
                                    if (floatBuf.size < neededFloats) floatBuf = FloatArray(neededFloats)
                                    outBuf.asFloatBuffer().get(floatBuf, 0, neededFloats)
                                    if (monoBuf.size < allowedFrames) monoBuf = FloatArray(allowedFrames)
                                    PcmDownmix.downmixFloat(floatBuf, allowedFrames, channels, monoBuf)
                                    activeResampler.process(monoBuf, allowedFrames, sink)
                                    decodedFrames += allowedFrames
                                }
                            } else {
                                val sampleCount = info.size / 2
                                val rawFrames = sampleCount / channels
                                val allowedFrames = min(
                                    rawFrames.toLong(),
                                    (maxTotalFrames - decodedFrames).coerceAtLeast(0L),
                                ).toInt()
                                if (allowedFrames > 0) {
                                    val neededShorts = allowedFrames * channels
                                    if (shortBuf.size < neededShorts) shortBuf = ShortArray(neededShorts)
                                    outBuf.asShortBuffer().get(shortBuf, 0, neededShorts)
                                    if (monoBuf.size < allowedFrames) monoBuf = FloatArray(allowedFrames)
                                    PcmDownmix.downmixS16(shortBuf, allowedFrames, channels, monoBuf)
                                    activeResampler.process(monoBuf, allowedFrames, sink)
                                    decodedFrames += allowedFrames
                                }
                            }

                            val progressNowMs = elapsedRealtimeMs()
                            if (progressNowMs - lastProgressEmitMs >= progressIntervalMs) {
                                lastProgressEmitMs = progressNowMs
                                val decodedMs = (decodedFrames * 1_000L) / sampleRate
                                val fraction = totalDurationMs?.takeIf { it > 0L }?.let { total ->
                                    (decodedMs.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                }
                                onProgress(
                                    DecodeProgress(
                                        decodedDurationMs = decodedMs,
                                        totalDurationMs = totalDurationMs,
                                        fraction = fraction,
                                    ),
                                )
                            }
                        }

                        decoder.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            break
                        }
                    }
                }
            }

            if (sampleRate <= 0 || channels <= 0 || decodedFrames <= 0L) {
                return AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED)
            }

            val finalResampler = resampler
            finalResampler?.finish(sink)
            val resampledSamples = finalResampler?.outputCount ?: 0L
            val durationMs = (decodedFrames * 1_000L) / sampleRate

            onProgress(
                DecodeProgress(
                    decodedDurationMs = durationMs,
                    totalDurationMs = totalDurationMs ?: durationMs,
                    fraction = 1.0f,
                ),
            )

            return AudioDecodeResult.Success(
                sampleRate = sampleRate,
                channels = channels,
                decodedFrames = decodedFrames,
                resampledSamples = resampledSamples,
                durationMs = durationMs,
                encoderDelay = encoderDelay,
                encoderPadding = encoderPadding,
                mimeType = mime,
            )
        }
    }

    companion object {
        private const val OUTPUT_TIMEOUT_US: Long = 5_000L
        private const val COARSE_TIMEOUT_US: Long = 2_500L
        private const val STALL_TIMEOUT_MS: Long = 5_000L
        internal const val REPEATED_PTS_LIMIT: Int = 8
        private const val DEFAULT_PROGRESS_INTERVAL_MS: Long = 250L

        /** 範囲デコードで、開始位置より前からデコードして捨てる長さ（デコーダとリサンプラの立ち上がり分）。 */
        internal const val SEEK_PRE_ROLL_MS: Long = 500L

        /** 今の読み出し位置からこの長さまでの前方なら、シークせずに読み飛ばす。 */
        private const val MAX_FORWARD_SKIP_WITHOUT_SEEK_US: Long = 30_000_000L

        /** 読み飛ばし中にキャンセルを確かめる間隔（サンプル数）。 */
        private const val SKIP_CANCELLATION_CHECK_INTERVAL: Int = 512

        /** 抽出器の `sampleTime` が入力の終わりを示す値。 */
        internal const val END_OF_INPUT_US: Long = -1L
        private const val MAX_OVERVIEW_COLUMNS: Int = 600
        private const val MAX_COARSE_SAMPLE_ATTEMPTS: Int = 6

        /**
         * 範囲デコードの開始位置へ [extractor] を進め、最初にデコーダへ渡すサンプルの時刻（µs）を返す。
         * 入力の終わりに達したら [END_OF_INPUT_US] を返す。
         *
         * - 目標は [startPositionUs] の [SEEK_PRE_ROLL_MS] 手前。今の読み出し位置が目標の手前で近ければそのまま、
         *   そうでなければ `seekTo` してから、目標に達するまで**圧縮データのまま** `advance()` で読み飛ばす（デコーダには渡さない）。
         * - 返す時刻は抽出器が返すフレームの時刻なので、全体をデコードしたときの時間軸と同じ（ADR 0005、ADR 0006）。
         * - シークの着地が [startPositionUs] より後ろ（シーク表の誤差）か入力の終わりなら、先頭から読み飛ばし直す（遅いが正確）。
         * - 時刻が進まない読み出しが [REPEATED_PTS_LIMIT] 回続いたら入力の終わりとみなす（ADR 0005 決定 4 の FLAC 対策）。
         */
        internal suspend fun positionExtractorForRange(extractor: ExtractorSession, startPositionUs: Long): Long {
            val targetUs = (startPositionUs - SEEK_PRE_ROLL_MS * 1_000L).coerceAtLeast(0L)
            var currentUs = extractor.sampleTime
            val canSkipFromCurrent = currentUs != END_OF_INPUT_US &&
                currentUs <= targetUs &&
                targetUs - currentUs <= MAX_FORWARD_SKIP_WITHOUT_SEEK_US
            if (!canSkipFromCurrent) {
                runCatching { extractor.seekTo(targetUs) }
                currentUs = extractor.sampleTime
                if (currentUs == END_OF_INPUT_US || currentUs > startPositionUs) {
                    runCatching { extractor.seekTo(0L) }
                    currentUs = extractor.sampleTime
                }
            }

            var skippedSamples = 0
            var repeatedTimeCount = 0
            var lastTimeUs = currentUs
            while (currentUs != END_OF_INPUT_US && currentUs < targetUs) {
                if (!extractor.advance()) return END_OF_INPUT_US
                currentUs = extractor.sampleTime
                if (currentUs <= lastTimeUs) {
                    repeatedTimeCount++
                    if (repeatedTimeCount >= REPEATED_PTS_LIMIT) return END_OF_INPUT_US
                } else {
                    repeatedTimeCount = 0
                    lastTimeUs = currentUs
                }
                skippedSamples++
                if (skippedSamples % SKIP_CANCELLATION_CHECK_INTERVAL == 0) {
                    currentCoroutineContext().ensureActive()
                }
            }
            return currentUs
        }

        @OptIn(UnstableApi::class)
        internal fun createExtractorCompat(context: Context): MediaExtractorCompat {
            val extractorsFactory = DefaultExtractorsFactory()
                .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
            val dataSourceFactory = IndexSeekDataSource.Factory(DefaultDataSource.Factory(context))
            return MediaExtractorCompat(extractorsFactory, dataSourceFactory)
        }

        /** 本番の抽出器（[createExtractorCompat]）で [file] を開き、[ExtractorSession] として返す。 */
        @OptIn(UnstableApi::class)
        internal fun openCompatExtractorSession(context: Context, file: File): ExtractorSession {
            val extractor = createExtractorCompat(context)
            try {
                extractor.setDataSource(Uri.fromFile(file), 0L)
            } catch (e: Exception) {
                runCatching { extractor.release() }
                throw e
            }
            return object : ExtractorSession {
                override val trackCount: Int get() = extractor.trackCount
                override fun getTrackFormat(index: Int): MediaFormat = extractor.getTrackFormat(index)
                override fun selectTrack(index: Int) = extractor.selectTrack(index)
                override fun seekTo(timeUs: Long) {
                    extractor.seekTo(timeUs, MediaExtractorCompat.SEEK_TO_PREVIOUS_SYNC)
                }
                override fun readSampleData(buffer: ByteBuffer, offset: Int): Int = extractor.readSampleData(buffer, offset)
                override val sampleTime: Long get() = extractor.sampleTime
                override fun advance(): Boolean = extractor.advance()
                override fun close() {
                    runCatching { extractor.release() }
                }
            }
        }

        private fun openMediaCodecSession(mime: String, format: MediaFormat): CodecSession {
            val codec = MediaCodec.createDecoderByType(mime)
            var started = false
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                started = true
            } catch (e: Exception) {
                if (started) runCatching { codec.stop() }
                runCatching { codec.release() }
                throw e
            }
            return object : CodecSession {
                override val outputFormat: MediaFormat get() = codec.outputFormat
                override fun dequeueInputBuffer(timeoutUs: Long): Int = codec.dequeueInputBuffer(timeoutUs)
                override fun getInputBuffer(index: Int): ByteBuffer? = codec.getInputBuffer(index)
                override fun queueInputBuffer(
                    index: Int,
                    offset: Int,
                    size: Int,
                    presentationTimeUs: Long,
                    flags: Int,
                ) = codec.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
                override fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int = codec.dequeueOutputBuffer(info, timeoutUs)
                override fun getOutputBuffer(index: Int): ByteBuffer? = codec.getOutputBuffer(index)
                override fun releaseOutputBuffer(index: Int, render: Boolean) = codec.releaseOutputBuffer(index, render)
                override fun flush() {
                    runCatching { codec.flush() }
                }
                override fun close() {
                    runCatching { codec.stop() }
                    runCatching { codec.release() }
                }
            }
        }
    }
}

private fun MediaFormat.intOrZero(key: String): Int = intOr(key, 0)

private fun MediaFormat.intOr(key: String, default: Int): Int = if (containsKey(key)) getInteger(key) else default
