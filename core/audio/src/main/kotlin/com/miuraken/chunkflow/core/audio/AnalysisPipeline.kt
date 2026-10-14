package com.miuraken.chunkflow.core.audio

/**
 * §7.3.1 の 1 パスのストリーミング処理: モノラル化 → 16kHz にリサンプル → ビンと特徴量（[FrameFeatureExtractor]）。
 *
 * デコーダの出力（インターリーブされた PCM）を [pushS16] / [pushFloat] で順に渡し、最後に [finish] を呼ぶ。
 * PCM 全体は保持しない。時刻は出力したサンプル数の累計（[outputSamples] / [outputRate]）から求める。
 *
 * スレッドセーフではない。1 つのストリームに 1 つ使う。サンプルレートとチャンネル数はストリームの途中で変えられない。
 *
 * @param inputRate 入力のサンプルレート（Hz）
 * @param channels 入力のチャンネル数
 * @param sink ビンと特徴量の出力先
 * @param outputRate リサンプル後のサンプルレート（既定 16kHz）
 * @param binMs ビンの長さ（ミリ秒）
 * @param nativeTap モノラル化した直後（リサンプル前）のサンプルを受け取る（検証用。不要なら null）
 * @param resampledTap リサンプル後のサンプルを受け取る（検証用。不要なら null）
 */
class AnalysisPipeline(
    val inputRate: Int,
    val channels: Int,
    sink: FrameFeatureSink,
    val outputRate: Int = StreamingResampler.DEFAULT_OUTPUT_RATE,
    binMs: Int = 10,
    private val nativeTap: SampleSink? = null,
    private val resampledTap: SampleSink? = null,
) {
    private val resampler = StreamingResampler(inputRate, outputRate)
    private val features = FrameFeatureExtractor(outputRate, binMs, sink = sink)
    private var mono = FloatArray(4096)
    private val toFeatures = SampleSink { samples, count ->
        features.process(samples, count)
        resampledTap?.onSamples(samples, count)
    }

    init {
        require(channels >= 1) { "channels=$channels" }
    }

    /** 受け取った入力のフレーム数（モノラル化の前の 1 フレーム = 全チャンネルの 1 サンプル） */
    val inputFrames: Long get() = resampler.inputCount

    /** リサンプル後に出力したサンプル数 */
    val outputSamples: Long get() = resampler.outputCount

    /** 出力したビン（フレーム）の数 */
    val frameCount: Int get() = features.frameCount

    /** 16bit 整数のインターリーブされた PCM を [frames] フレーム処理する。 */
    fun pushS16(interleaved: ShortArray, frames: Int) {
        ensureMono(frames)
        PcmDownmix.downmixS16(interleaved, frames, channels, mono)
        pushMono(frames)
    }

    /** Float のインターリーブされた PCM を [frames] フレーム処理する。 */
    fun pushFloat(interleaved: FloatArray, frames: Int) {
        ensureMono(frames)
        PcmDownmix.downmixFloat(interleaved, frames, channels, mono)
        pushMono(frames)
    }

    /** 入力の終わり。残りの出力をすべて流す。 */
    fun finish() {
        resampler.finish(toFeatures)
        features.finish()
    }

    private fun ensureMono(frames: Int) {
        if (mono.size < frames) mono = FloatArray(frames)
    }

    private fun pushMono(frames: Int) {
        nativeTap?.onSamples(mono, frames)
        resampler.process(mono, frames, toFeatures)
    }
}
