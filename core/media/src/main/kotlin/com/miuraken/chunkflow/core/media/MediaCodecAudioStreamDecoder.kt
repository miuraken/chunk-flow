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
import com.miuraken.chunkflow.core.audio.PcmDownmix
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.audio.StreamingResampler
import com.miuraken.chunkflow.core.model.AnalysisError
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [MediaCodecAudioStreamDecoder] が内部で扱う抽出器のセッション抽象（テスト差し替え用）。
 */
internal interface ExtractorSession : AutoCloseable {
    val trackCount: Int
    fun getTrackFormat(index: Int): MediaFormat
    fun selectTrack(index: Int)
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

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun decodeWithExtractor(
        extractor: ExtractorSession,
        sink: SampleSink,
        outputSampleRate: Int,
        expectedDurationMs: Long?,
        onProgress: suspend (DecodeProgress) -> Unit,
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
        private const val STALL_TIMEOUT_MS: Long = 5_000L
        internal const val REPEATED_PTS_LIMIT: Int = 8
        private const val DEFAULT_PROGRESS_INTERVAL_MS: Long = 250L

        @OptIn(UnstableApi::class)
        internal fun createExtractorCompat(context: Context): MediaExtractorCompat {
            val extractorsFactory = DefaultExtractorsFactory()
                .setMp3ExtractorFlags(Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING)
            val dataSourceFactory = IndexSeekDataSource.Factory(DefaultDataSource.Factory(context))
            return MediaExtractorCompat(extractorsFactory, dataSourceFactory)
        }

        @OptIn(UnstableApi::class)
        private fun openCompatExtractorSession(context: Context, file: File): ExtractorSession {
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
