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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Silero VAD (`silero_vad.onnx`, 16kHz) を ONNX Runtime で実行し、
 * ストリーミングデコード中の 16kHz モノラル PCM から発話確率列を算出するエンジン。
 *
 * - セッション（[OrtSession]）はプロセス内で 1 回だけ遅延初期化して共有する。
 * - [parallelism] が 2 以上の場合は、デコードスレッドから受け取った PCM を一定長のブロック（既定 5.12 秒）に分割し、
 *   直前ブロック末尾のウォームアップ区間（既定 1.024 秒）を先頭に添えてバックグラウンドのワーカースレッドプールで並列推論する。
 * - [parallelism] が 1 の場合は、隠れ状態 `state` (`[2, 1, 128]`) と `context` (`64` サンプル) を連続引き継ぎしながら直列推論する。
 */
@Singleton
class SileroVadEngine internal constructor(
    private val context: Context?,
    private val modelBytesSource: (() -> ByteArray?)?,
    internal val parallelism: Int = DEFAULT_PARALLELISM,
    internal val blockWindows: Int = DEFAULT_BLOCK_WINDOWS,
    internal val warmupWindows: Int = DEFAULT_WARMUP_WINDOWS,
) : SpeechProbabilityStreamFactory {

    @Inject
    constructor(@ApplicationContext context: Context?) : this(
        context = context,
        modelBytesSource = null,
        parallelism = DEFAULT_PARALLELISM,
        blockWindows = DEFAULT_BLOCK_WINDOWS,
        warmupWindows = DEFAULT_WARMUP_WINDOWS,
    )

    internal constructor() : this(
        context = null,
        modelBytesSource = null,
        parallelism = DEFAULT_PARALLELISM,
        blockWindows = DEFAULT_BLOCK_WINDOWS,
        warmupWindows = DEFAULT_WARMUP_WINDOWS,
    )

    override fun createStream(): SpeechProbabilityStream? {
        val loaded = getOrLoadSession() ?: return null
        val effectiveParallelism = parallelism.coerceAtLeast(1)
        return if (effectiveParallelism <= 1) {
            SerialSileroVadProbabilityStream(
                env = loaded.env,
                session = loaded.session,
            )
        } else {
            ParallelSileroVadProbabilityStream(
                env = loaded.env,
                session = loaded.session,
                executor = executorForParallelism(effectiveParallelism),
                blockWindows = blockWindows.coerceAtLeast(16),
                warmupWindows = warmupWindows.coerceAtLeast(0),
            )
        }
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

        /** 既定の並列ワーカー数（CPU コア数 - 1 を 2..4 にクランプ）。 */
        val DEFAULT_PARALLELISM: Int =
            Runtime.getRuntime().availableProcessors().minus(1).coerceIn(2, 4)

        /** 並列推論時の 1 ブロックあたりのウィンドウ数（160 × 32ms = 5.12 秒 = 81,920 サンプル）。 */
        const val DEFAULT_BLOCK_WINDOWS: Int = 160

        /** 並列推論時のウォームアップウィンドウ数（32 × 32ms = 1.024 秒 = 16,384 サンプル）。 */
        const val DEFAULT_WARMUP_WINDOWS: Int = 32

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
        private val workerThreadIndex = AtomicInteger(1)
        private val executorsByParallelism = HashMap<Int, ExecutorService>()

        internal fun executorForParallelism(parallelism: Int): ExecutorService {
            val poolSize = parallelism.coerceIn(1, 8)
            synchronized(executorsByParallelism) {
                return executorsByParallelism.getOrPut(poolSize) {
                    Executors.newFixedThreadPool(poolSize) { runnable ->
                        Thread(
                            runnable,
                            "SileroVadWorker-${poolSize}p-${workerThreadIndex.getAndIncrement()}",
                        ).apply {
                            isDaemon = true
                        }
                    }
                }
            }
        }

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

        internal fun runStepWithSrTensor(
            env: OrtEnvironment,
            session: OrtSession,
            srTensor: OnnxTensor,
            inputBuffer: FloatArray,
            stateBuffer: FloatArray,
        ): Float = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputBuffer),
            INPUT_SHAPE,
        ).use { inputTensor ->
            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(stateBuffer),
                STATE_SHAPE,
            ).use { stateTensor ->
                val inputs = mapOf(
                    INPUT_AUDIO to inputTensor,
                    INPUT_STATE to stateTensor,
                    INPUT_SR to srTensor,
                )
                session.run(inputs).use { result ->
                    val probTensor = (result.get(OUTPUT_PROB).orElse(null) as? OnnxTensor)
                        ?: (result.get(0) as? OnnxTensor)
                        ?: throw IllegalStateException("Silero VAD 出力 $OUTPUT_PROB がありません")
                    val stateNTensor = (result.get(OUTPUT_STATE_N).orElse(null) as? OnnxTensor)
                        ?: (result.get(1) as? OnnxTensor)
                        ?: throw IllegalStateException("Silero VAD 出力 $OUTPUT_STATE_N がありません")

                    val prob = probTensor.floatBuffer.get(0).coerceIn(0f, 1f)
                    val nextStateBuf = stateNTensor.floatBuffer
                    if (nextStateBuf.remaining() == STATE_ELEMENTS) {
                        nextStateBuf.get(stateBuffer)
                    }
                    prob
                }
            }
        }
    }
}

/**
 * 1 ステップずつ隠れ状態を連続引き継ぎして推論する直列ストリーム実装（`parallelism == 1` 用）。
 */
internal class SerialSileroVadProbabilityStream(
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
        OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(srArray),
            SileroVadEngine.SR_SHAPE,
        ).use { srTensor ->
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
                    processWindow(pendingSamples, srTensor)
                    pendingCount = 0
                }
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
                OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(srArray),
                    SileroVadEngine.SR_SHAPE,
                ).use { srTensor ->
                    processWindow(pendingSamples, srTensor)
                }
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

    private fun processWindow(window512: FloatArray, srTensor: OnnxTensor) {
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
        window512.copyInto(
            destination = contextBuffer,
            destinationOffset = 0,
            startIndex = SileroVadEngine.WINDOW_SAMPLES - SileroVadEngine.CONTEXT_SAMPLES,
            endIndex = SileroVadEngine.WINDOW_SAMPLES,
        )

        val prob = SileroVadEngine.runStepWithSrTensor(
            env = env,
            session = session,
            srTensor = srTensor,
            inputBuffer = inputBuffer,
            stateBuffer = stateBuffer,
        )
        if (probsCount == probs32ms.size) {
            probs32ms = probs32ms.copyOf(probs32ms.size * 2)
        }
        probs32ms[probsCount++] = prob
    }
}

/**
 * デコードスレッドから受け取った PCM を一定長のブロックに分割し、
 * 直前ブロック末尾のウォームアップ区間を付与してバックグラウンドで並列推論するストリーム実装。
 */
internal class ParallelSileroVadProbabilityStream(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val executor: ExecutorService,
    private val blockWindows: Int,
    private val warmupWindows: Int,
) : SpeechProbabilityStream {

    private val blockSamplesCap: Int = blockWindows * SileroVadEngine.WINDOW_SAMPLES
    private val warmupSamplesCap: Int = warmupWindows * SileroVadEngine.WINDOW_SAMPLES

    private val blockBuffer = FloatArray(blockSamplesCap)
    private var blockSampleCount: Int = 0

    private val warmupTail = FloatArray(warmupSamplesCap)
    private var warmupTailCount: Int = 0

    private val submittedBlocks = ArrayList<Future<FloatArray>>()
    private var collectedProbs32ms = FloatArray(2_048)
    private var collectedProbsCount: Int = 0
    private var collectedBlockIndex: Int = 0
    private var finished: Boolean = false

    override fun onSamples(samples: FloatArray, count: Int) {
        if (finished || count <= 0) return
        val limit = min(count, samples.size)
        var offset = 0
        while (offset < limit) {
            val toCopy = min(blockSamplesCap - blockSampleCount, limit - offset)
            samples.copyInto(
                destination = blockBuffer,
                destinationOffset = blockSampleCount,
                startIndex = offset,
                endIndex = offset + toCopy,
            )
            blockSampleCount += toCopy
            offset += toCopy
            if (blockSampleCount == blockSamplesCap) {
                submitAlignedBlock(blockWindows)
            }
        }
    }

    override fun currentProbabilities(targetFrameCount: Int, binMs: Int): FloatArray {
        if (!finished) {
            val fullWindows = blockSampleCount / SileroVadEngine.WINDOW_SAMPLES
            if (fullWindows > 0) {
                submitAlignedBlock(fullWindows)
            }
        }
        collectSubmittedBlocks()
        return SpeechProbabilityInterpolator.interpolate32msToBins(
            probs32ms = collectedProbs32ms,
            probsCount = collectedProbsCount,
            targetFrameCount = targetFrameCount,
            binMs = binMs,
        )
    }

    override fun finish(targetFrameCount: Int, binMs: Int): FloatArray {
        if (!finished) {
            finished = true
            if (blockSampleCount > 0) {
                val remainder = blockSampleCount % SileroVadEngine.WINDOW_SAMPLES
                if (remainder > 0) {
                    val padTo = blockSampleCount + (SileroVadEngine.WINDOW_SAMPLES - remainder)
                    blockBuffer.fill(0f, fromIndex = blockSampleCount, toIndex = padTo)
                    blockSampleCount = padTo
                }
                val finalWindows = blockSampleCount / SileroVadEngine.WINDOW_SAMPLES
                if (finalWindows > 0) {
                    submitAlignedBlock(finalWindows)
                }
            }
        }
        collectSubmittedBlocks()
        return SpeechProbabilityInterpolator.interpolate32msToBins(
            probs32ms = collectedProbs32ms,
            probsCount = collectedProbsCount,
            targetFrameCount = targetFrameCount,
            binMs = binMs,
        )
    }

    private fun submitAlignedBlock(windowCount: Int) {
        val alignedSamples = windowCount * SileroVadEngine.WINDOW_SAMPLES
        val blockSlice = blockBuffer.copyOfRange(0, alignedSamples)
        val warmupSlice = if (warmupTailCount > 0) {
            warmupTail.copyOfRange(0, warmupTailCount)
        } else {
            EMPTY_FLOAT_ARRAY
        }

        // 次ブロック用のウォームアップ末尾（最大 warmupSamplesCap サンプル）を更新する
        if (warmupSamplesCap > 0) {
            if (alignedSamples >= warmupSamplesCap) {
                blockBuffer.copyInto(
                    destination = warmupTail,
                    destinationOffset = 0,
                    startIndex = alignedSamples - warmupSamplesCap,
                    endIndex = alignedSamples,
                )
                warmupTailCount = warmupSamplesCap
            } else {
                val totalAvailable = warmupTailCount + alignedSamples
                if (totalAvailable <= warmupSamplesCap) {
                    blockBuffer.copyInto(
                        destination = warmupTail,
                        destinationOffset = warmupTailCount,
                        startIndex = 0,
                        endIndex = alignedSamples,
                    )
                    warmupTailCount = totalAvailable
                } else {
                    val keepFromOld = warmupSamplesCap - alignedSamples
                    warmupTail.copyInto(
                        destination = warmupTail,
                        destinationOffset = 0,
                        startIndex = warmupTailCount - keepFromOld,
                        endIndex = warmupTailCount,
                    )
                    blockBuffer.copyInto(
                        destination = warmupTail,
                        destinationOffset = keepFromOld,
                        startIndex = 0,
                        endIndex = alignedSamples,
                    )
                    warmupTailCount = warmupSamplesCap
                }
            }
        }

        // 未処理の端数サンプルがあれば先頭へ詰める
        val leftover = blockSampleCount - alignedSamples
        if (leftover > 0) {
            blockBuffer.copyInto(
                destination = blockBuffer,
                destinationOffset = 0,
                startIndex = alignedSamples,
                endIndex = blockSampleCount,
            )
        }
        blockSampleCount = leftover

        val task = Callable {
            inferBlockWithWarmup(
                env = env,
                session = session,
                warmupSamples = warmupSlice,
                blockSamples = blockSlice,
                blockWindowCount = windowCount,
            )
        }
        submittedBlocks.add(executor.submit(task))
    }

    private fun collectSubmittedBlocks() {
        while (collectedBlockIndex < submittedBlocks.size) {
            val blockProbs = submittedBlocks[collectedBlockIndex].get()
            val neededCapacity = collectedProbsCount + blockProbs.size
            if (neededCapacity > collectedProbs32ms.size) {
                var newCap = collectedProbs32ms.size
                while (newCap < neededCapacity) {
                    newCap *= 2
                }
                collectedProbs32ms = collectedProbs32ms.copyOf(newCap)
            }
            blockProbs.copyInto(
                destination = collectedProbs32ms,
                destinationOffset = collectedProbsCount,
                startIndex = 0,
                endIndex = blockProbs.size,
            )
            collectedProbsCount += blockProbs.size
            collectedBlockIndex++
        }
    }

    companion object {
        private val EMPTY_FLOAT_ARRAY = FloatArray(0)

        internal fun inferBlockWithWarmup(
            env: OrtEnvironment,
            session: OrtSession,
            warmupSamples: FloatArray,
            blockSamples: FloatArray,
            blockWindowCount: Int,
        ): FloatArray {
            val contextBuffer = FloatArray(SileroVadEngine.CONTEXT_SAMPLES)
            val inputBuffer = FloatArray(SileroVadEngine.INPUT_SAMPLES)
            val stateBuffer = FloatArray(SileroVadEngine.STATE_ELEMENTS)
            val srArray = longArrayOf(SileroVadEngine.SAMPLE_RATE_HZ)
            val outProbs = FloatArray(blockWindowCount)

            OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(srArray),
                SileroVadEngine.SR_SHAPE,
            ).use { srTensor ->
                val warmupWindows = warmupSamples.size / SileroVadEngine.WINDOW_SAMPLES
                for (w in 0 until warmupWindows) {
                    val winStart = w * SileroVadEngine.WINDOW_SAMPLES
                    stepWindow(
                        env = env,
                        session = session,
                        srTensor = srTensor,
                        source = warmupSamples,
                        winStart = winStart,
                        contextBuffer = contextBuffer,
                        inputBuffer = inputBuffer,
                        stateBuffer = stateBuffer,
                    )
                }

                for (w in 0 until blockWindowCount) {
                    val winStart = w * SileroVadEngine.WINDOW_SAMPLES
                    outProbs[w] = stepWindow(
                        env = env,
                        session = session,
                        srTensor = srTensor,
                        source = blockSamples,
                        winStart = winStart,
                        contextBuffer = contextBuffer,
                        inputBuffer = inputBuffer,
                        stateBuffer = stateBuffer,
                    )
                }
            }
            return outProbs
        }

        private fun stepWindow(
            env: OrtEnvironment,
            session: OrtSession,
            srTensor: OnnxTensor,
            source: FloatArray,
            winStart: Int,
            contextBuffer: FloatArray,
            inputBuffer: FloatArray,
            stateBuffer: FloatArray,
        ): Float {
            contextBuffer.copyInto(
                destination = inputBuffer,
                destinationOffset = 0,
                startIndex = 0,
                endIndex = SileroVadEngine.CONTEXT_SAMPLES,
            )
            source.copyInto(
                destination = inputBuffer,
                destinationOffset = SileroVadEngine.CONTEXT_SAMPLES,
                startIndex = winStart,
                endIndex = winStart + SileroVadEngine.WINDOW_SAMPLES,
            )
            source.copyInto(
                destination = contextBuffer,
                destinationOffset = 0,
                startIndex = winStart + SileroVadEngine.WINDOW_SAMPLES - SileroVadEngine.CONTEXT_SAMPLES,
                endIndex = winStart + SileroVadEngine.WINDOW_SAMPLES,
            )
            return SileroVadEngine.runStepWithSrTensor(
                env = env,
                session = session,
                srTensor = srTensor,
                inputBuffer = inputBuffer,
                stateBuffer = stateBuffer,
            )
        }
    }
}
