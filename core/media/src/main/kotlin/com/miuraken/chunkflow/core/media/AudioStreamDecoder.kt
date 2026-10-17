package com.miuraken.chunkflow.core.media

import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.audio.StreamingResampler
import com.miuraken.chunkflow.core.model.AnalysisError
import com.miuraken.chunkflow.core.model.Waveform
import java.io.File

/**
 * ストリーミングデコード中の進捗情報（§7.3.1、§7.3.4）。
 *
 * @property decodedDurationMs これまでにデコードした音声の長さ（ミリ秒）。
 * @property totalDurationMs 音声全体の推定長（ミリ秒）。不明な場合は `null`。
 * @property fraction 0.0..1.0 の進捗率。全体の長さが不明な場合は `null`。
 */
data class DecodeProgress(
    val decodedDurationMs: Long,
    val totalDurationMs: Long?,
    val fraction: Float?,
)

/**
 * [AudioStreamDecoder.decode] の結果型（§6.1、§7.3.1）。
 */
sealed interface AudioDecodeResult {
    /**
     * デコードが正常に完了した。
     *
     * @property sampleRate デコーダ出力のネイティブサンプルレート（Hz）。
     * @property channels デコーダ出力のチャンネル数。
     * @property decodedFrames デコーダが出力した累計フレーム数（ネイティブレート）。
     * @property resampledSamples [SampleSink] へ出力した累計サンプル数（リサンプル後）。
     * @property durationMs 出力サンプル数の累計から求めた音声の長さ（ミリ秒、ADR 0005）。
     * @property encoderDelay トラックの `encoder-delay`（サンプル数）。
     * @property encoderPadding トラックの `encoder-padding`（サンプル数）。
     * @property mimeType トラックの MIME タイプ。
     */
    data class Success(
        val sampleRate: Int,
        val channels: Int,
        val decodedFrames: Long,
        val resampledSamples: Long,
        val durationMs: Long,
        val encoderDelay: Int,
        val encoderPadding: Int,
        val mimeType: String,
    ) : AudioDecodeResult

    /**
     * デコードに失敗した。
     *
     * @property error 失敗の種別（[AnalysisError]）。
     */
    data class Failure(
        val error: AnalysisError,
    ) : AudioDecodeResult
}

/**
 * 音声ファイルを 1 パスのストリーミングでデコードし、モノラル化・リサンプルして [SampleSink] に流すインターフェース（§7.3.1、ADR 0005）。
 *
 * PCM 全体をメモリに保持しない。
 */
interface AudioStreamDecoder {
    /**
     * [file] をデコードし、[outputSampleRate]（既定 16kHz）のモノラル Float サンプルを [sink] に順次渡す。
     *
     * @param file デコード対象の音声ファイル。
     * @param sink リサンプル後のモノラル PCM サンプル（-1.0..1.0）を受け取るシンク。
     * @param outputSampleRate リサンプル後のサンプルレート（Hz、既定 16,000Hz）。
     * @param expectedDurationMs 進捗率の算出に使う既知の音声長（ミリ秒）。MP3 でヘッダを隠した場合などに利用する。
     * @param onProgress 進捗通知コールバック。
     */
    suspend fun decode(
        file: File,
        sink: SampleSink,
        outputSampleRate: Int = StreamingResampler.DEFAULT_OUTPUT_RATE,
        expectedDurationMs: Long? = null,
        onProgress: suspend (DecodeProgress) -> Unit = {},
    ): AudioDecodeResult

    /**
     * 音声ファイルの秒数（全体長）と全体波形の横幅（カラム数 [targetColumns]）から必要なミリ秒間隔を割り出し、
     * シークによる間引きサンプリングで低解像度の全体波形（[Waveform]）を即座に生成する。
     *
     * その後、30 秒幅のフル解像度（10ms ビン）解析データが揃うたびに順次置き換えていくために用いる。
     * 非対応または音声長が不明な場合は `null` を返す。
     */
    suspend fun decodeCoarseOverviewWaveform(
        file: File,
        expectedDurationMs: Long? = null,
        targetColumns: Int = DEFAULT_OVERVIEW_COLUMNS,
        fineBinMs: Int = DEFAULT_FINE_BIN_MS,
    ): Waveform? = null

    companion object {
        /** 全体波形の既定の横幅カラム数（画面幅約 360〜720dp ÷ 3dp/本 ≒ 240 本）。 */
        const val DEFAULT_OVERVIEW_COLUMNS: Int = 240

        /** 詳細波形の既定ビン長（10ms）。 */
        const val DEFAULT_FINE_BIN_MS: Int = 10
    }
}
