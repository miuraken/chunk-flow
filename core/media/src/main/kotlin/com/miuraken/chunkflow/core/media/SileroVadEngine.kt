package com.miuraken.chunkflow.core.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.miuraken.chunkflow.core.audio.SpeechProbabilityInterpolator
import com.miuraken.chunkflow.core.audio.SpeechProbabilityStream
import com.miuraken.chunkflow.core.audio.SpeechProbabilityStreamFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Silero VAD v6 (`silero_vad.onnx`, 16kHz) を ONNX Runtime で実行し、
 * ストリーミングデコード中の 16kHz モノラル PCM から発話確率列を算出するエンジン。
 *
 * - セッション（[OrtSession]）はプロセス内で 1 回だけ遅延初期化して共有する。
 * - ストリームごとに独立した隠れ状態 `state` (`[2, 1, 128]`) とコンテキスト `context` (`64` サンプル) を保持する。
 */
@Singleton
class SileroVadEngine internal constructor(
    private val context: Context?,
    private val modelBytesSource: (() -> ByteArray?)?,
) : SpeechProbabilityStreamFactory {

    @Inject
    constructor(@ApplicationContext context: Context?) : this(
        context = context,
        modelBytesSource = null,
    )

    internal constructor() : this(context = null, modelBytesSource = null)

    override fun createStream(): SpeechProbabilityStream? {
        val loaded = getOrLoadSession() ?: return null
        return SileroVadProbabilityStream(
            env = loaded.env,
            session = loaded.session,
        )
    }

    private fun getOrLoadSession(): LoadedOrtSession? {
        val holder = if (modelBytesSource == null) sharedSessionHolder else localSessionHolder
        return holder.getOrLoad {
            val bytes = (modelBytesSource ?: ::readDefaultModelBytes).invoke()
                ?: throw IllegalStateException("silero_vad.onnx が見つかりません")
            createOrtSession(bytes)
        }
    }

    private fun readDefaultModelBytes(): ByteArray? {
        for (provider in candidateStreamProviders(ASSET_FILE_NAME)) {
            val bytes = runCatching {
                provider()?.use { stream -> stream.readNBytes(MAX_MODEL_BYTES + 1) }
            }.getOrNull() ?: continue
            if (bytes.isNotEmpty() && bytes.size <= MAX_MODEL_BYTES) {
                return bytes
            }
        }
        return null
    }

    private fun candidateStreamProviders(fileName: String): List<() -> InputStream?> = listOf(
        {
            context?.let { ctx ->
                runCatching { ctx.assets.open(fileName) }.getOrNull()
            }
        },
        {
            val candidateFiles = listOf(
                File("src/main/assets/$fileName"),
                File("core/media/src/main/assets/$fileName"),
                File("../core/media/src/main/assets/$fileName"),
                File("../../core/media/src/main/assets/$fileName"),
            )
            candidateFiles.firstOrNull { it.isFile }?.inputStream()
        },
        {
            SileroVadEngine::class.java.classLoader?.getResourceAsStream(fileName)
        },
    )

    private val localSessionHolder = SessionHolder()

    internal class LoadedOrtSession(
        val env: OrtEnvironment,
        val session: OrtSession,
    )

    internal class SessionHolder {
        private val lock = Any()

        @Volatile
        private var loaded: LoadedOrtSession? = null

        @Volatile
        private var loadFinished: Boolean = false

        fun getOrLoad(loader: () -> LoadedOrtSession): LoadedOrtSession? {
            loaded?.let { return it }
            if (loadFinished) return null
            synchronized(lock) {
                loaded?.let { return it }
                if (loadFinished) return null
                return try {
                    val created = loader()
                    loaded = created
                    created
                } catch (_: Throwable) {
                    null
                } finally {
                    loadFinished = true
                }
            }
        }
    }

    companion object {
        const val ASSET_FILE_NAME: String = "silero_vad.onnx"
        const val MAX_MODEL_BYTES: Int = 8 * 1024 * 1024

        internal const val INPUT_AUDIO: String = "input"
        internal const val INPUT_STATE: String = "state"
        internal const val INPUT_SR: String = "sr"
        internal const val OUTPUT_PROB: String = "output"
        internal const val OUTPUT_STATE_N: String = "stateN"

        internal const val SAMPLE_RATE_HZ: Long = 16_000L
        internal const val WINDOW_SAMPLES: Int = SpeechProbabilityInterpolator.SILERO_WINDOW_SAMPLES_16K // 512
        internal const val CONTEXT_SAMPLES: Int = SpeechProbabilityInterpolator.SILERO_CONTEXT_SAMPLES_16K // 64
        internal const val INPUT_SAMPLES: Int = CONTEXT_SAMPLES + WINDOW_SAMPLES // 576
        internal const val STATE_ELEMENTS: Int = 2 * 1 * 128 // 256

        internal val INPUT_SHAPE: LongArray = longArrayOf(1L, INPUT_SAMPLES.toLong())
        internal val STATE_SHAPE: LongArray = longArrayOf(2L, 1L, 128L)
        internal val SR_SHAPE: LongArray = longArrayOf()

        private val sharedSessionHolder = SessionHolder()

        internal fun createOrtSession(modelBytes: ByteArray): LoadedOrtSession {
            require(modelBytes.size in 1..MAX_MODEL_BYTES) {
                "Silero VAD ONNX のサイズが範囲外です: ${modelBytes.size}"
            }
            val env = OrtEnvironment.getEnvironment()
            val session = OrtSession.SessionOptions().use { options ->
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
                options.setIntraOpNumThreads(1)
                options.setInterOpNumThreads(1)
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                env.createSession(modelBytes, options)
            }
            try {
                check(INPUT_AUDIO in session.inputNames) { "入力 $INPUT_AUDIO がありません" }
                check(INPUT_STATE in session.inputNames) { "入力 $INPUT_STATE がありません" }
                check(INPUT_SR in session.inputNames) { "入力 $INPUT_SR がありません" }
                val stateInfo = session.inputInfo[INPUT_STATE]?.info as? TensorInfo
                check(
                    stateInfo != null &&
                        stateInfo.shape.size == 3 &&
                        stateInfo.shape[0] == 2L &&
                        stateInfo.shape[2] == 128L,
                ) {
                    "入力 $INPUT_STATE の形状が想定と異なります: ${stateInfo?.shape?.contentToString()}"
                }
            } catch (e: Exception) {
                runCatching { session.close() }
                throw e
            }
            return LoadedOrtSession(env = env, session = session)
        }
    }
}

internal class SileroVadProbabilityStream(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : SpeechProbabilityStream {

    private val contextBuffer = FloatArray(SileroVadEngine.CONTEXT_SAMPLES)
    private val pendingSamples = FloatArray(SileroVadEngine.WINDOW_SAMPLES)
    private var pendingCount: Int = 0
    private val inputBuffer = FloatArray(SileroVadEngine.INPUT_SAMPLES)
    private val stateBuffer = FloatArray(SileroVadEngine.STATE_ELEMENTS)
    private val srArray = longArrayOf(SileroVadEngine.SAMPLE_RATE_HZ)

    private var probs32ms = FloatArray(2_048)
    private var probsCount: Int = 0
    private var finished: Boolean = false

    override fun onSamples(samples: FloatArray, count: Int) {
        if (finished || count <= 0) return
        val limit = min(count, samples.size)
        var offset = 0
        while (offset < limit) {
            val toCopy = min(SileroVadEngine.WINDOW_SAMPLES - pendingCount, limit - offset)
            samples.copyInto(
                destination = pendingSamples,
                destinationOffset = pendingCount,
                startIndex = offset,
                endIndex = offset + toCopy,
            )
            pendingCount += toCopy
            offset += toCopy
            if (pendingCount == SileroVadEngine.WINDOW_SAMPLES) {
                processWindow(pendingSamples)
                pendingCount = 0
            }
        }
    }

    override fun currentProbabilities(targetFrameCount: Int, binMs: Int): FloatArray = SpeechProbabilityInterpolator.interpolate32msToBins(
        probs32ms = probs32ms,
        probsCount = probsCount,
        targetFrameCount = targetFrameCount,
        binMs = binMs,
    )

    override fun finish(targetFrameCount: Int, binMs: Int): FloatArray {
        if (!finished) {
            finished = true
            if (pendingCount > 0) {
                pendingSamples.fill(0f, fromIndex = pendingCount, toIndex = SileroVadEngine.WINDOW_SAMPLES)
                processWindow(pendingSamples)
                pendingCount = 0
            }
        }
        return SpeechProbabilityInterpolator.interpolate32msToBins(
            probs32ms = probs32ms,
            probsCount = probsCount,
            targetFrameCount = targetFrameCount,
            binMs = binMs,
        )
    }

    private fun processWindow(window512: FloatArray) {
        contextBuffer.copyInto(
            destination = inputBuffer,
            destinationOffset = 0,
            startIndex = 0,
            endIndex = SileroVadEngine.CONTEXT_SAMPLES,
        )
        window512.copyInto(
            destination = inputBuffer,
            destinationOffset = SileroVadEngine.CONTEXT_SAMPLES,
            startIndex = 0,
            endIndex = SileroVadEngine.WINDOW_SAMPLES,
        )
        // 次ステップのコンテキストとして今回ウィンドウの末尾 64 サンプルを保存する
        window512.copyInto(
            destination = contextBuffer,
            destinationOffset = 0,
            startIndex = SileroVadEngine.WINDOW_SAMPLES - SileroVadEngine.CONTEXT_SAMPLES,
            endIndex = SileroVadEngine.WINDOW_SAMPLES,
        )

        val prob = runSingleStep()
        if (probsCount == probs32ms.size) {
            probs32ms = probs32ms.copyOf(probs32ms.size * 2)
        }
        probs32ms[probsCount++] = prob
    }

    private fun runSingleStep(): Float = OnnxTensor.createTensor(
        env,
        FloatBuffer.wrap(inputBuffer),
        SileroVadEngine.INPUT_SHAPE,
    ).use { inputTensor ->
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(stateBuffer),
            SileroVadEngine.STATE_SHAPE,
        ).use { stateTensor ->
            OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(srArray),
                SileroVadEngine.SR_SHAPE,
            ).use { srTensor ->
                val inputs = mapOf(
                    SileroVadEngine.INPUT_AUDIO to inputTensor,
                    SileroVadEngine.INPUT_STATE to stateTensor,
                    SileroVadEngine.INPUT_SR to srTensor,
                )
                session.run(inputs).use { result ->
                    val probTensor = (result.get(SileroVadEngine.OUTPUT_PROB).orElse(null) as? OnnxTensor)
                        ?: (result.get(0) as? OnnxTensor)
                        ?: throw IllegalStateException("Silero VAD 出力 ${SileroVadEngine.OUTPUT_PROB} がありません")
                    val stateNTensor = (result.get(SileroVadEngine.OUTPUT_STATE_N).orElse(null) as? OnnxTensor)
                        ?: (result.get(1) as? OnnxTensor)
                        ?: throw IllegalStateException("Silero VAD 出力 ${SileroVadEngine.OUTPUT_STATE_N} がありません")

                    val prob = probTensor.floatBuffer.get(0).coerceIn(0f, 1f)
                    val nextStateBuf = stateNTensor.floatBuffer
                    if (nextStateBuf.remaining() == SileroVadEngine.STATE_ELEMENTS) {
                        nextStateBuf.get(stateBuffer)
                    }
                    prob
                }
            }
        }
    }
}
