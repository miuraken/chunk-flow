package com.miuraken.chunkflow.core.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer

/**
 * 言語判定の推論（Whisper Tiny のエンコーダと融合デコーダ）を行うエンジン（[WhisperTinyLanguageDetector] が使う）。
 *
 * 入力は [WhisperTinyLanguageDetector] が計算した正規化 log-mel（300 フレーム × 80、時間が先の並び）、
 * 出力は 99 言語のロジットとエンコーダ出力（LayerNorm 後、150 × 384）。複数のスレッドから同時に呼んでよい。
 */
internal interface WhisperLidNeuralEngine {
    /** 推論エンジンの名前（診断ログに出す）。 */
    val engineName: String

    /**
     * [mel300x80] から推論し、[logitsOut]（99 言語）と [audioFeaturesOut]（150 × 384）に書き込む。
     *
     * @throws Exception 推論に失敗した場合（呼び出し側で純 Kotlin の推論に切り替える）。
     */
    fun run(mel300x80: FloatArray, logitsOut: FloatArray, audioFeaturesOut: FloatArray)
}

/**
 * ONNX Runtime（CPU）で `whisper_tiny_lid.onnx`（`spikes/audio/whisper-lid-onnx/` で .bin から作ったもの）を実行するエンジン。
 *
 * - 重みは `whisper_tiny_lid_q8q4.bin` と同じ量子化の値（Q8 は DequantizeLinear + Gemm、Q4 は MatMulNBits）。
 * - セッションは 1 つを共有し、[OrtSession.run] を複数のスレッドから同時に呼ぶ（ONNX Runtime の推論はスレッドセーフ）。
 *   一括言語判定はチャンク単位で並列に推論するため、1 回の推論は 1 スレッドで行う（[DEFAULT_INTRA_OP_THREADS]）。
 */
internal class OnnxWhisperLidEngine private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    /** セッションを作るのにかかった時間（ミリ秒、ONNX の解析と最適化を含む）。 */
    val loadTimeMs: Long,
    /** ONNX のメタデータに書かれた言語コードの並び（ロジットの並び）。書かれていなければ空。 */
    val languageCodes: List<String>,
) : WhisperLidNeuralEngine {

    override val engineName: String = ENGINE_NAME

    override fun run(mel300x80: FloatArray, logitsOut: FloatArray, audioFeaturesOut: FloatArray) {
        require(mel300x80.size == MEL_ELEMENTS) { "mel の要素数が違う: ${mel300x80.size}" }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mel300x80), MEL_SHAPE).use { mel ->
            session.run(mapOf(INPUT_MEL to mel)).use { result ->
                val logits = result.get(OUTPUT_LOGITS).orElse(null) as? OnnxTensor
                    ?: throw IllegalStateException("出力 $OUTPUT_LOGITS がない")
                val features = result.get(OUTPUT_AUDIO_FEATURES).orElse(null) as? OnnxTensor
                    ?: throw IllegalStateException("出力 $OUTPUT_AUDIO_FEATURES がない")
                copyExactly(logits, logitsOut, OUTPUT_LOGITS)
                copyExactly(features, audioFeaturesOut, OUTPUT_AUDIO_FEATURES)
            }
        }
    }

    private fun copyExactly(tensor: OnnxTensor, out: FloatArray, name: String) {
        val buffer = tensor.floatBuffer
        check(buffer.remaining() == out.size) { "出力 $name の要素数が違う: ${buffer.remaining()}（期待 ${out.size}）" }
        buffer.get(out)
    }

    companion object {
        /** 診断ログに出すエンジンの名前。 */
        const val ENGINE_NAME: String = "onnxruntime"

        /** アセットの ONNX のファイル名。 */
        const val ASSET_FILE_NAME: String = "whisper_tiny_lid.onnx"

        /** 1 回の推論に使うスレッド数（チャンク単位で並列にするため 1）。 */
        const val DEFAULT_INTRA_OP_THREADS: Int = 1

        /** 読み込む ONNX の大きさの上限（想定外のファイルや壊れたファイルで大きなメモリを確保しない）。 */
        const val MAX_MODEL_BYTES: Int = 32 * 1024 * 1024

        internal const val INPUT_MEL: String = "mel"
        internal const val OUTPUT_LOGITS: String = "logits"
        internal const val OUTPUT_AUDIO_FEATURES: String = "audio_features"

        /** ONNX のメタデータで、言語コードの並び（カンマ区切り）を書くキー（`build_onnx.py` が書く）。 */
        internal const val METADATA_LANGUAGE_CODES: String = "chunkflow.language_codes"
        private const val N_FRAMES: Long = 300L
        private const val N_MELS: Long = 80L
        private val MEL_SHAPE: LongArray = longArrayOf(1L, N_FRAMES, N_MELS)
        private const val MEL_ELEMENTS: Int = (N_FRAMES * N_MELS).toInt()

        /**
         * ONNX のバイト列 [modelBytes] からセッションを作る。
         *
         * Q8 の重みの DequantizeLinear は読み込み時に float の重みへ畳む（`session.disable_quant_qdq=1`）。
         * Mac の計測では、畳まない場合より推論が約 5% 速く、推論中のメモリも少なかった（`docs/spikes/S3-onnx-whisper-lid.md`）。
         *
         * @throws Exception ONNX が壊れている、入出力が想定と違う、ネイティブライブラリを読めない（[LinkageError]）場合など。
         */
        fun create(
            modelBytes: ByteArray,
            intraOpThreads: Int = DEFAULT_INTRA_OP_THREADS,
        ): OnnxWhisperLidEngine {
            require(modelBytes.size in 1..MAX_MODEL_BYTES) { "ONNX の大きさが範囲外: ${modelBytes.size} バイト" }
            val env = OrtEnvironment.getEnvironment()
            val startNanos = System.nanoTime()
            val session = OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setIntraOpNumThreads(intraOpThreads.coerceAtLeast(1))
                options.setInterOpNumThreads(1)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.addConfigEntry("session.disable_quant_qdq", "1")
                env.createSession(modelBytes, options)
            }
            val languageCodes = try {
                validateSignature(session)
                session.metadata.customMetadata[METADATA_LANGUAGE_CODES]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
            return OnnxWhisperLidEngine(
                env = env,
                session = session,
                loadTimeMs = (System.nanoTime() - startNanos) / 1_000_000L,
                languageCodes = languageCodes,
            )
        }

        /** 入出力の名前と形が、このクラスの想定（mel [1, 300, 80] → logits [1, 99]、audio_features [1, 150, 384]）と同じか確かめる。 */
        private fun validateSignature(session: OrtSession) {
            val melShape = (session.inputInfo[INPUT_MEL]?.info as? TensorInfo)?.shape
            check(melShape != null && melShape.contentEquals(MEL_SHAPE)) { "入力 $INPUT_MEL の形が違う: ${melShape?.contentToString()}" }
            val logitsShape = (session.outputInfo[OUTPUT_LOGITS]?.info as? TensorInfo)?.shape
            check(logitsShape != null && logitsShape.size == 2 && logitsShape[0] == 1L) {
                "出力 $OUTPUT_LOGITS の形が違う: ${logitsShape?.contentToString()}"
            }
            val featuresShape = (session.outputInfo[OUTPUT_AUDIO_FEATURES]?.info as? TensorInfo)?.shape
            check(featuresShape != null && featuresShape.contentEquals(longArrayOf(1L, 150L, 384L))) {
                "出力 $OUTPUT_AUDIO_FEATURES の形が違う: ${featuresShape?.contentToString()}"
            }
        }
    }
}
