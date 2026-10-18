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
    /** デコーダの名前（`MediaCodec.getName()`。MP3 のフレームをまとめて渡せるかの判定に使う）。不明なら空文字。 */
    val codecName: String get() = ""
    val outputFormat: MediaFormat
    fun dequeueInputBuffer(timeoutUs: Long): Int
    fun getInputBuffer(index: Int): ByteBuffer?
    fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int)
    fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int
    fun getOutputBuffer(index: Int): ByteBuffer?
    fun releaseOutputBuffer(index: Int, render: Boolean)
    fun flush() {}
}

/** チャンク切り出し用に開いたままにする抽出器を特定するキー（パス・更新時刻・長さが同じなら同じファイルとみなす）。 */
internal data class RangeSessionKey(
    val absolutePath: String,
    val lastModified: Long,
    val length: Long,
) {
    companion object {
        /** [file] の今のキー。 */
        fun of(file: File): RangeSessionKey = RangeSessionKey(
            absolutePath = file.absolutePath,
            lastModified = file.lastModified(),
            length = file.length(),
        )
    }
}

/**
 * チャンク切り出し用の抽出器を 1 つ開いたままにする入れ物（[MediaCodecAudioStreamDecoder] の共有の 1 つと、[RangeDecodeLane]）。
 *
 * 抽出器はスレッドセーフではないので、[key] と [extractor] は [mutex] の中でだけ読み書きする。
 */
internal class RangeExtractorSlot {
    /** 切り出しを直列にするロック。 */
    val mutex = Mutex()

    /** 開いている抽出器のファイル。 */
    var key: RangeSessionKey? = null

    /** 開いている抽出器。 */
    var extractor: ExtractorSession? = null

    /** 切り出しの途中で解放を求められたとき、その切り出しの終わりに解放するための印。 */
    val releaseRequested = AtomicBoolean(false)
}

/**
 * 並列の切り出し（[LidExtractionMethod.PARALLEL]）で 1 つのワーカーが使うレーン（[MediaCodecAudioStreamDecoder.openRangeDecodeLane]）。
 *
 * 共有の抽出器とは別の抽出器を 1 つ持つ（最初の切り出しで開く）。MediaCodec は切り出しごとに作る（今の方式と同じ）。
 * 使い終わったら必ず [close] する（切り出しの途中なら、その切り出しの終わりに閉じる）。
 */
internal class RangeDecodeLane internal constructor(
    private val release: (RangeExtractorSlot) -> Unit,
) : AutoCloseable {
    /** このレーンの抽出器の入れ物。 */
    internal val slot = RangeExtractorSlot()

    override fun close() {
        release(slot)
    }
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
 * - MP3 を [MP3_BATCH_CAPABLE_DECODERS] のデコーダで読むときは、ヘッダと長さを確かめたフレームを 1 つの入力にまとめて渡す
 *   （1 フレームずつだとデコーダのプロセスとのやり取りで頭打ちになる）。入力から見込まれる出力の長さと実際の長さがずれたら、
 *   その結果は失敗とし、以後は 1 フレームずつ渡す（時間軸を守る。ADR 0005）。
 */
@Singleton
@OptIn(UnstableApi::class)
class MediaCodecAudioStreamDecoder internal constructor(
    private val extractorOpener: (File) -> ExtractorSession,
    private val codecCreator: (String, MediaFormat) -> CodecSession,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS,
    private val uriExtractorOpener: ((Uri) -> ExtractorSession)? = null,
) : AudioStreamDecoder {

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(
        extractorOpener = { file -> openCompatExtractorSession(context, file) },
        codecCreator = { mime, format -> openMediaCodecSession(mime, format) },
        uriExtractorOpener = { uri -> openCompatExtractorSession(context, uri) },
    )

    /**
     * チャンク切り出し用に開いたままにする共有の抽出器（第 2 段階の文字起こしと、一括言語判定の今の方式で使う）。
     * 抽出器はスレッドセーフではないので、この入れ物の `mutex` で切り出しを直列にする。
     */
    private val sharedRangeSlot = RangeExtractorSlot()

    /** まとめて渡した MP3 の出力の長さがずれたことがあれば true（以後は 1 フレームずつ渡す）。 */
    private val mp3BatchingDisabled = AtomicBoolean(false)

    /** [codecName] のデコーダに MP3 のフレームをまとめて渡してよいか（[MP3_BATCH_CAPABLE_DECODERS] にあり、ずれたことがない）。 */
    internal fun isMp3BatchingSupported(codecName: String): Boolean = !mp3BatchingDisabled.get() && codecName in MP3_BATCH_CAPABLE_DECODERS

    /** まとめて渡した MP3 の出力の長さがずれたときに呼ぶ（以後このデコーダでは 1 フレームずつ渡す）。 */
    internal fun disableMp3Batching() {
        mp3BatchingDisabled.set(true)
    }

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

    override suspend fun decode(
        uri: Uri,
        sink: SampleSink,
        outputSampleRate: Int,
        expectedDurationMs: Long?,
        onProgress: suspend (DecodeProgress) -> Unit,
    ): AudioDecodeResult {
        require(outputSampleRate > 0) { "outputSampleRate must be > 0: $outputSampleRate" }
        val scheme = uri.scheme
        if (scheme == null || scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.takeIf { it.isNotBlank() }?.let(::File)
                ?: return AudioDecodeResult.Failure(AnalysisError.IO)
            return decode(
                file = file,
                sink = sink,
                outputSampleRate = outputSampleRate,
                expectedDurationMs = expectedDurationMs,
                onProgress = onProgress,
            )
        }
        val opener = uriExtractorOpener ?: return AudioDecodeResult.Failure(AnalysisError.IO)
        val extractor = try {
            opener(uri)
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
     * @param lane 並列の切り出し（[openRangeDecodeLane]）のレーン。`null` なら共有の抽出器を使う（第 2 段階と今の方式）。
     *   レーンを使っても、MediaCodec を作り直すこと・着地するフレーム・デコードする範囲は同じなので、切り出す PCM は同じ。
     * @param timings 各段階の時間を足す先（言語判定の切り出しだけで渡す）。
     */
    internal suspend fun decodeFromPosition(
        file: File,
        startPositionMs: Long,
        sink: SampleSink,
        outputSampleRate: Int = StreamingResampler.DEFAULT_OUTPUT_RATE,
        lane: RangeDecodeLane? = null,
        timings: LidExtractionTimings? = null,
        onSeekLandingUs: (Long) -> Unit = {},
    ): AudioDecodeResult {
        require(outputSampleRate > 0) { "outputSampleRate must be > 0: $outputSampleRate" }
        if (!file.exists() || !file.isFile || !file.canRead()) {
            return AudioDecodeResult.Failure(AnalysisError.IO)
        }
        if (file.length() <= 0L) {
            return AudioDecodeResult.Failure(AnalysisError.UNSUPPORTED_FORMAT)
        }

        val slot = lane?.slot ?: sharedRangeSlot
        return slot.mutex.withLock {
            val extractor = try {
                obtainRangeExtractorLocked(slot, file, timings)
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
                    timings = timings,
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
                if (!keepSession || slot.releaseRequested.getAndSet(false)) {
                    closeSlotLocked(slot)
                }
            }
        }
    }

    /**
     * チャンク切り出し用に開いたままにしている抽出器を解放する（文字起こしをオフにしたとき・リセットしたときなど）。
     * 切り出しの途中なら、その切り出しが終わったときに解放する。次の切り出しでは開き直す。
     */
    internal fun releaseRangeSession() {
        releaseSlot(sharedRangeSlot)
    }

    /**
     * 並列の切り出し（[LidExtractionMethod.PARALLEL]）で 1 つのワーカーが使うレーンを作る。
     *
     * 抽出器は最初の切り出しで開く（共有の抽出器とは別）。使い終わったら必ず [RangeDecodeLane.close] する。
     */
    internal fun openRangeDecodeLane(): RangeDecodeLane = RangeDecodeLane(release = ::releaseSlot)

    /**
     * 順に流す切り出し（[LidExtractionMethod.STREAMING]、言語判定だけで使う）。
     *
     * [windows]（開始位置の順）の窓を、run ごとに 1 つの MediaCodec で続けてデコードして切り出し、窓ごとにちょうど 1 回 [emit] に渡す
     * （[LidStreamingExtractor]）。共有の抽出器とは別の抽出器を開き、終わったら（キャンセル・エラーでも）閉じる。
     * 第 2 段階の切り出しと解析（波形・チャンク分割）の時間軸は変えない。
     */
    internal suspend fun streamLanguageWindows(
        file: File,
        windows: List<StreamWindowRequest>,
        outputSampleRate: Int,
        options: StreamingExtractionOptions,
        timings: LidExtractionTimings?,
        emit: suspend (StreamWindowResult) -> Unit,
    ) {
        require(outputSampleRate > 0) { "outputSampleRate must be > 0: $outputSampleRate" }
        LidStreamingExtractor(
            extractorOpener = extractorOpener,
            codecCreator = codecCreator,
            elapsedRealtimeMs = elapsedRealtimeMs,
            isMp3BatchingSupported = ::isMp3BatchingSupported,
            onMp3BatchMismatch = ::disableMp3Batching,
            options = options,
            timings = timings,
        ).extract(file, windows, outputSampleRate, emit)
    }

    /** [slot] の抽出器を解放する。切り出しの途中なら、その切り出しが終わったときに解放する。 */
    private fun releaseSlot(slot: RangeExtractorSlot) {
        slot.releaseRequested.set(true)
        if (slot.mutex.tryLock()) {
            try {
                slot.releaseRequested.set(false)
                closeSlotLocked(slot)
            } finally {
                slot.mutex.unlock()
            }
        }
    }

    /** [slot] の `mutex` の中で呼ぶ。同じファイルなら開いている抽出器を返し、違えば閉じて開き直す。 */
    private fun obtainRangeExtractorLocked(slot: RangeExtractorSlot, file: File, timings: LidExtractionTimings?): ExtractorSession {
        if (slot.releaseRequested.getAndSet(false)) {
            closeSlotLocked(slot)
        }
        val key = RangeSessionKey.of(file)
        val current = slot.extractor
        if (current != null && slot.key == key) return current
        closeSlotLocked(slot)
        val startNanos = timings.startNanos()
        val extractor = extractorOpener(file)
        timings.addSince(startNanos) { extractorOpenNanos }
        timings?.extractorOpens?.incrementAndGet()
        slot.key = key
        slot.extractor = extractor
        return extractor
    }

    /** [slot] の `mutex` の中で呼ぶ。開いたままの抽出器があれば閉じる。 */
    private fun closeSlotLocked(slot: RangeExtractorSlot) {
        val current = slot.extractor ?: return
        slot.extractor = null
        slot.key = null
        runCatching { current.close() }
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

    override suspend fun decodeCoarseOverviewWaveform(
        uri: Uri,
        expectedDurationMs: Long?,
        targetColumns: Int,
        fineBinMs: Int,
    ): Waveform? {
        if (targetColumns <= 0 || fineBinMs <= 0) return null
        val scheme = uri.scheme
        if (scheme == null || scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
            return decodeCoarseOverviewWaveform(
                file = file,
                expectedDurationMs = expectedDurationMs,
                targetColumns = targetColumns,
                fineBinMs = fineBinMs,
            )
        }
        val opener = uriExtractorOpener ?: return null
        val extractor = try {
            opener(uri)
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
                    // シークが目標より半列ぶん以上手前に着地する形式（MP3 の IndexSeeker は読んだ範囲にしか着地しない。ADR 0006）では、
                    // 列ごとに同じ先頭付近を読むことになり、全体の波形にならない。誤った波形を出さないよう、ここで諦める
                    val landedUs = extractor.sampleTime
                    if (landedUs >= 0L && targetTimeUs - landedUs > stepUs / 2L) return null
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
        timings: LidExtractionTimings? = null,
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
            val positionStartNanos = timings.startNanos()
            val landedUs = positionExtractorForRange(extractor, startPositionUs, timings)
            timings.addSince(positionStartNanos) { positionNanos }
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

        val codecStartNanos = timings.startNanos()
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
        } finally {
            timings.addSince(codecStartNanos) { codecCreateNanos }
        }
        timings?.codecCreates?.incrementAndGet()

        codec.useTimed(timings) { decoder ->
            val info = MediaCodec.BufferInfo()
            var inputEos = false
            var extractorDone = false
            var lastInputPts = Long.MIN_VALUE
            var repeatedPtsCount = 0
            var stuckAtEndWorkaround = false

            // MP3 を標準のデコーダで読むときは、1 つの入力に複数のフレームをまとめて渡す
            // （1 フレームずつだと、デコーダのプロセスとのやり取りの往復で頭打ちになる。Pixel 実機で 30 分の解析が十数倍速くなる）
            val isMp3 = mime == MIME_TYPE_MP3
            val mp3BatchLimit = when {
                !isMp3 || !isMp3BatchingSupported(decoder.codecName) -> 1
                startPositionUs == null -> MP3_BATCH_FRAMES_FULL_DECODE
                else -> MP3_BATCH_FRAMES_RANGE_DECODE
            }
            var batchedInputQueued = false
            // 入力したフレームのヘッダから見込まれる出力のサンプル数（1 チャンネルあたり）。読めないヘッダがあれば確かめない
            var expectedMp3Samples = 0L
            var expectedMp3SamplesKnown = isMp3

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
                    var queuedBytes = 0
                    var queuedSamples = 0
                    var firstPts = 0L
                    while (!extractorDone && queuedSamples < mp3BatchLimit) {
                        // 2 つ目以降は、最大長のフレームが入る空きがあるときだけ続けて詰める
                        if (queuedSamples > 0 && inBuf.capacity() - queuedBytes < MAX_MP3_FRAME_BYTES) break
                        val sampleSize = extractor.readSampleData(inBuf, queuedBytes)
                        if (sampleSize < 0) {
                            extractorDone = true
                            break
                        }
                        val mp3Frame = if (isMp3) Mp3FrameHeader.parse(inBuf, queuedBytes) else null
                        val batchable = mp3BatchLimit > 1 && mp3Frame != null && mp3Frame.frameSizeBytes == sampleSize
                        if (queuedSamples > 0 && !batchable) {
                            // ヘッダの読めないフレームはまとめない。advance していないので、次の入力で同じフレームを単独で読み直す
                            break
                        }
                        val pts = extractor.sampleTime
                        if (queuedSamples == 0) firstPts = pts
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
                        if (!batchable) break
                    }
                    if (queuedSamples == 0) {
                        decoder.queueInputBuffer(
                            inIdx,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                        inputEos = true
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, queuedBytes, firstPts, 0)
                        if (queuedSamples > 1) batchedInputQueued = true
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
                                    val mixStartNanos = timings.startNanos()
                                    val neededFloats = allowedFrames * channels
                                    if (floatBuf.size < neededFloats) floatBuf = FloatArray(neededFloats)
                                    outBuf.asFloatBuffer().get(floatBuf, 0, neededFloats)
                                    if (monoBuf.size < allowedFrames) monoBuf = FloatArray(allowedFrames)
                                    PcmDownmix.downmixFloat(floatBuf, allowedFrames, channels, monoBuf)
                                    timings.addSince(mixStartNanos) { mixNanos }
                                    decodedFrames += allowedFrames
                                    timings?.decodedNativeSamples?.addAndGet(allowedFrames.toLong())
                                    activeResampler.processTimed(monoBuf, allowedFrames, sink, timings)
                                }
                            } else {
                                val sampleCount = info.size / 2
                                val rawFrames = sampleCount / channels
                                val allowedFrames = min(
                                    rawFrames.toLong(),
                                    (maxTotalFrames - decodedFrames).coerceAtLeast(0L),
                                ).toInt()
                                if (allowedFrames > 0) {
                                    val mixStartNanos = timings.startNanos()
                                    val neededShorts = allowedFrames * channels
                                    if (shortBuf.size < neededShorts) shortBuf = ShortArray(neededShorts)
                                    outBuf.asShortBuffer().get(shortBuf, 0, neededShorts)
                                    if (monoBuf.size < allowedFrames) monoBuf = FloatArray(allowedFrames)
                                    PcmDownmix.downmixS16(shortBuf, allowedFrames, channels, monoBuf)
                                    timings.addSince(mixStartNanos) { mixNanos }
                                    decodedFrames += allowedFrames
                                    timings?.decodedNativeSamples?.addAndGet(allowedFrames.toLong())
                                    activeResampler.processTimed(monoBuf, allowedFrames, sink, timings)
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

            if (batchedInputQueued && expectedMp3SamplesKnown && !stuckAtEndWorkaround &&
                isMp3BatchOutputLengthMismatched(
                    decodedFrames = decodedFrames,
                    expectedFrames = expectedMp3Samples,
                    encoderDelay = encoderDelay,
                    encoderPadding = encoderPadding,
                )
            ) {
                // まとめて渡したフレームの一部がデコードされなかった（時間軸がずれる）。
                // 結果は使わず失敗にし、以後このデコーダでは 1 フレームずつ渡す（次の解析では正しく読める）
                mp3BatchingDisabled.set(true)
                return AudioDecodeResult.Failure(AnalysisError.DECODE_FAILED)
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
        internal const val OUTPUT_TIMEOUT_US: Long = 5_000L
        private const val COARSE_TIMEOUT_US: Long = 2_500L
        internal const val STALL_TIMEOUT_MS: Long = 5_000L
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

        /** MP3 の MIME タイプ。 */
        internal const val MIME_TYPE_MP3: String = "audio/mpeg"

        /**
         * 1 つの入力に複数のフレームをまとめて渡せると確かめた MP3 デコーダ（AOSP の `C2SoftMp3Dec`。ソースと Pixel 実機で確認）。
         * ほかのデコーダは 1 フレームずつ渡す。
         */
        internal val MP3_BATCH_CAPABLE_DECODERS: Set<String> = setOf("c2.android.mp3.decoder")

        /** 全体をデコードするとき、1 つの入力にまとめる MP3 のフレーム数の上限（44.1kHz で約 3.3 秒ぶん）。 */
        internal const val MP3_BATCH_FRAMES_FULL_DECODE: Int = 128

        /** チャンクを切り出すとき、1 つの入力にまとめる MP3 のフレーム数の上限（末尾で打ち切ったときの無駄を小さくする）。 */
        internal const val MP3_BATCH_FRAMES_RANGE_DECODE: Int = 16

        /** まとめて渡すための入力バッファの大きさ（最大長のフレーム 1,441 バイト × 128 個が入る）。 */
        private const val MP3_BATCH_INPUT_BUFFER_BYTES: Int = 256 * 1024

        /** MP3 の 1 フレームの最大バイト数の見込み（Media3 の Mp3Extractor の上限と同じ）。次のフレームを詰める前の空きの確認に使う。 */
        internal const val MAX_MP3_FRAME_BYTES: Int = 4_096

        /** まとめて渡したとき、入力したフレームから見込まれる出力の長さとのずれの許容（1 チャンネルあたり 2 フレームぶん）。 */
        private const val MP3_BATCH_TOLERANCE_SAMPLES: Long = 2_304L

        /**
         * MediaCodec がギャップレス情報に従って削る長さとして認める上限（1 チャンネルあたり 4 フレームぶん。
         * iTunes で作った MP3 では 528 + 2,110 だった）。
         * 上限 + [MP3_BATCH_TOLERANCE_SAMPLES]（6,912）が、まとめた入力 1 つ分の欠落の最小
         * （チャンクの切り出しで MPEG-2 の 16 フレーム = 9,216）より小さいので、欠落は許容の中に隠れない。
         */
        private const val MAX_GAPLESS_TRIM_SAMPLES: Long = 4_608L

        /**
         * まとめて渡した MP3 の出力の長さ [decodedFrames] が、入力したフレームから見込まれる長さ [expectedFrames] とずれているか
         * （どちらも 1 チャンネルあたりのサンプル数）。
         *
         * MediaCodec は、トラックの形式にエンコーダの遅延とパディング（ID3 の iTunSMPB や LAME タグから読んだ
         * [MediaFormat.KEY_ENCODER_DELAY]・[MediaFormat.KEY_ENCODER_PADDING]）があると、出力の先頭と末尾からその分を削る。
         * 1 フレームずつ渡しても同じだけ削られる（Pixel 実機で、iTunes で作った 30 分の MP3 は 528 + 2,110 サンプル短くなった）ので、
         * 見込みより最大でその分（上限 [MAX_GAPLESS_TRIM_SAMPLES]）だけ短いのは正常とする。
         */
        internal fun isMp3BatchOutputLengthMismatched(
            decodedFrames: Long,
            expectedFrames: Long,
            encoderDelay: Int,
            encoderPadding: Int,
        ): Boolean {
            val gaplessTrim = (encoderDelay.coerceAtLeast(0).toLong() + encoderPadding.coerceAtLeast(0).toLong())
                .coerceAtMost(MAX_GAPLESS_TRIM_SAMPLES)
            val shortfall = expectedFrames - decodedFrames
            return shortfall < -MP3_BATCH_TOLERANCE_SAMPLES || shortfall > gaplessTrim + MP3_BATCH_TOLERANCE_SAMPLES
        }

        /**
         * 範囲デコードの開始位置へ [extractor] を進め、最初にデコーダへ渡すサンプルの時刻（µs）を返す。
         * 入力の終わりに達したら [END_OF_INPUT_US] を返す。
         *
         * - 目標は [startPositionUs] の [SEEK_PRE_ROLL_MS] 手前。今の読み出し位置が目標の手前で近ければそのまま、
         *   そうでなければ `seekTo` してから、目標に達するまで**圧縮データのまま** `advance()` で読み飛ばす（デコーダには渡さない）。
         * - 返す時刻は抽出器が返すフレームの時刻なので、全体をデコードしたときの時間軸と同じ（ADR 0005、ADR 0006）。
         * - シークの着地が [startPositionUs] より後ろ（シーク表の誤差）か入力の終わりなら、先頭から読み飛ばし直す（遅いが正確）。
         * - 時刻が進まない読み出しが [REPEATED_PTS_LIMIT] 回続いたら入力の終わりとみなす（ADR 0005 決定 4 の FLAC 対策）。
         *
         * @param timings シークの回数と読み飛ばしたサンプルの数を足す先（言語判定の切り出しだけで渡す）。
         */
        internal suspend fun positionExtractorForRange(
            extractor: ExtractorSession,
            startPositionUs: Long,
            timings: LidExtractionTimings? = null,
        ): Long {
            val targetUs = (startPositionUs - SEEK_PRE_ROLL_MS * 1_000L).coerceAtLeast(0L)
            var currentUs = extractor.sampleTime
            val canSkipFromCurrent = currentUs != END_OF_INPUT_US &&
                currentUs <= targetUs &&
                targetUs - currentUs <= MAX_FORWARD_SKIP_WITHOUT_SEEK_US
            if (!canSkipFromCurrent) {
                runCatching { extractor.seekTo(targetUs) }
                timings?.seeks?.incrementAndGet()
                currentUs = extractor.sampleTime
                if (currentUs == END_OF_INPUT_US || currentUs > startPositionUs) {
                    runCatching { extractor.seekTo(0L) }
                    timings?.seeks?.incrementAndGet()
                    currentUs = extractor.sampleTime
                }
            }

            var skippedSamples = 0
            var repeatedTimeCount = 0
            var lastTimeUs = currentUs
            try {
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
            } finally {
                timings?.skippedSamples?.addAndGet(skippedSamples.toLong())
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
        internal fun openCompatExtractorSession(context: Context, file: File): ExtractorSession = openCompatExtractorSession(context, Uri.fromFile(file))

        /** 本番の抽出器（[createExtractorCompat]）で [uri] を開き、[ExtractorSession] として返す。 */
        @OptIn(UnstableApi::class)
        internal fun openCompatExtractorSession(context: Context, uri: Uri): ExtractorSession {
            val extractor = createExtractorCompat(context)
            try {
                extractor.setDataSource(uri, 0L)
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
                if (mime == MIME_TYPE_MP3 && format.intOrZero(MediaFormat.KEY_MAX_INPUT_SIZE) < MP3_BATCH_INPUT_BUFFER_BYTES) {
                    // 複数のフレームをまとめて渡せるように、入力バッファを広げる（まとめるかどうかは decodeWithExtractor で決める）
                    format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MP3_BATCH_INPUT_BUFFER_BYTES)
                }
                codec.configure(format, null, null, 0)
                codec.start()
                started = true
            } catch (e: Exception) {
                if (started) runCatching { codec.stop() }
                runCatching { codec.release() }
                throw e
            }
            val name = runCatching { codec.name }.getOrDefault("")
            return object : CodecSession {
                override val codecName: String = name
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

/**
 * [AutoCloseable.use] と同じく [block] のあとで必ず閉じる。[timings] があれば、[block] の時間を
 * [LidExtractionTimings.decodeLoopNanos] に、閉じる（MediaCodec の stop・release）時間を [LidExtractionTimings.codecReleaseNanos] に足す。
 */
internal inline fun <T> CodecSession.useTimed(timings: LidExtractionTimings?, block: (CodecSession) -> T): T {
    val loopStartNanos = timings.startNanos()
    var failure: Throwable? = null
    try {
        return block(this)
    } catch (e: Throwable) {
        failure = e
        throw e
    } finally {
        timings.addSince(loopStartNanos) { decodeLoopNanos }
        val releaseStartNanos = timings.startNanos()
        if (failure == null) {
            close()
        } else {
            try {
                close()
            } catch (closeError: Throwable) {
                failure.addSuppressed(closeError)
            }
        }
        timings.addSince(releaseStartNanos) { codecReleaseNanos }
    }
}

/** [StreamingResampler.process] を呼び、[timings] があればその時間（シンクの中の PCM への変換を含む）を足す。 */
internal fun StreamingResampler.processTimed(input: FloatArray, length: Int, sink: SampleSink, timings: LidExtractionTimings?) {
    if (timings == null) {
        process(input, length, sink)
        return
    }
    val startNanos = System.nanoTime()
    try {
        process(input, length, sink)
    } finally {
        timings.resampleNanos.addAndGet(System.nanoTime() - startNanos)
    }
}
