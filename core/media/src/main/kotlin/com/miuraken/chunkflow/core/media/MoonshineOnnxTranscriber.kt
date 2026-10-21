package com.miuraken.chunkflow.core.media

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.util.Log
import com.miuraken.chunkflow.core.model.DownloadableSttModel
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguageTag
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.LongBuffer
import java.util.Base64
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Moonshine ONNX（Tiny / Base、8 言語: `en`, `ja`, `zh`, `ko`, `es`, `ar`, `uk`, `vi`）を
 * ONNX Runtime で直接推論し、チャンク音声を文字起こしするエンジン実装。
 */
@Singleton
internal class MoonshineOnnxTranscriber @Inject constructor(
    private val modelManager: SherpaOnnxModelManager,
) {
    private val sessionLock = Any()
    private var loadedBundle: LoadedMoonshineBundle? = null
    private var loadedMergedBundle: LoadedMoonshineMergedBundle? = null

    fun isReadyForLanguage(
        languageTag: String,
        model: DownloadableSttModel,
    ): Boolean {
        val lang = LanguageTag(languageTag)
        if (!model.isLanguageSupported(lang)) return false
        return modelManager.isInstalledForLanguage(model, lang)
    }

    fun transcribePcm16Le(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        channelCount: Int,
        language: LanguageTag,
        model: DownloadableSttModel,
    ): OnDeviceChunkTranscriptionResult? {
        if (!model.isLanguageSupported(language)) return null
        if (pcm16LeBytes.size < 2 || sampleRateHz <= 0 || channelCount <= 0) return null

        val mono16k = resamplePcm16LeTo16kMonoNormalized(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
        )
        if (mono16k.size < MIN_AUDIO_SAMPLES) return null
        return transcribeNormalized16kMono(mono16k, language, model)
    }

    internal fun transcribeNormalized16kMono(
        pcm16kMono: FloatArray,
        language: LanguageTag,
        model: DownloadableSttModel,
    ): OnDeviceChunkTranscriptionResult? {
        if (!model.isLanguageSupported(language)) return null
        val samples = if (pcm16kMono.size > MAX_AUDIO_SAMPLES) {
            pcm16kMono.copyOf(MAX_AUDIO_SAMPLES)
        } else {
            pcm16kMono
        }
        if (samples.all { abs(it) <= SILENCE_SAMPLE_ABS_MAX }) return null

        val engineId = when (model) {
            DownloadableSttModel.MOONSHINE_BASE -> MOONSHINE_BASE_ENGINE_ID
            else -> MOONSHINE_TINY_ENGINE_ID
        }
        val langKey = SherpaOnnxModelManager.normalizeLanguageKey(language)

        synchronized(sessionLock) {
            if (langKey == "en") {
                val bundle = ensureLoadedBundleLocked(model) ?: return null
                return try {
                    runGreedyDecodeLocked(
                        bundle = bundle,
                        samples = samples,
                        language = language,
                        engineId = engineId,
                    )
                } catch (e: Exception) {
                    logInfo("Moonshine v1 inference failed model=${model.modelId} error=${e.javaClass.simpleName}")
                    null
                }
            } else {
                val mergedBundle = ensureLoadedMergedBundleLocked(model, language) ?: return null
                return try {
                    runMergedGreedyDecodeLocked(
                        bundle = mergedBundle,
                        samples = samples,
                        language = language,
                        engineId = engineId,
                    )
                } catch (e: Exception) {
                    logInfo("Moonshine v2 inference failed model=${model.modelId} lang=$langKey error=${e.javaClass.simpleName}")
                    null
                }
            }
        }
    }

    private fun runGreedyDecodeLocked(
        bundle: LoadedMoonshineBundle,
        samples: FloatArray,
        language: LanguageTag,
        engineId: EngineId,
    ): OnDeviceChunkTranscriptionResult? {
        val env = bundle.env
        val audioShape = longArrayOf(1L, samples.size.toLong())
        val preInputName = bundle.preprocessSession.inputNames.firstOrNull() ?: return null

        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), audioShape).use { audioTensor ->
            bundle.preprocessSession.run(mapOf(preInputName to audioTensor)).use { preResult ->
                val featuresTensor = preResult.get(0) as? OnnxTensor ?: return null
                val featShape = featuresTensor.info.shape
                if (featShape.size < 3) return null
                val featFrames = featShape[1].toInt()
                if (featFrames <= 0) return null

                val encInputNames = bundle.encodeSession.inputNames.toList()
                if (encInputNames.size < 2) return null

                OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(featFrames)), longArrayOf(1L)).use { featLenTensor ->
                    val encInputs = mapOf(
                        encInputNames[0] to featuresTensor,
                        encInputNames[1] to featLenTensor,
                    )
                    bundle.encodeSession.run(encInputs).use { encResult ->
                        val contextTensor = encResult.get(0) as? OnnxTensor ?: return null
                        return decodeWithKvCache(
                            bundle = bundle,
                            contextTensor = contextTensor,
                            audioDurationSec = samples.size.toFloat() / SAMPLE_RATE.toFloat(),
                            language = language,
                            engineId = engineId,
                        )
                    }
                }
            }
        }
    }

    private fun decodeWithKvCache(
        bundle: LoadedMoonshineBundle,
        contextTensor: OnnxTensor,
        audioDurationSec: Float,
        language: LanguageTag,
        engineId: EngineId,
    ): OnDeviceChunkTranscriptionResult? {
        val env = bundle.env
        val uncachedInputNames = bundle.uncachedDecodeSession.inputNames.toList()
        val cachedInputNames = bundle.cachedDecodeSession.inputNames.toList()
        if (uncachedInputNames.size < 3 || cachedInputNames.size < 4) return null

        val maxLen = (audioDurationSec * 6.5f).toInt().coerceIn(8, MAX_DECODE_TOKENS)
        val tokenShape = longArrayOf(1L, 1L)
        val lenShape = longArrayOf(1L)

        var activeDecodeResult: OrtSession.Result? = null
        try {
            var seqLen = 1
            var nextToken = BOS_TOKEN_ID
            var sumLogProb = 0.0
            val generatedIds = IntArray(maxLen)
            var generatedCount = 0

            // Step 0: uncached_decode
            var currentStates: List<OnnxTensor>
            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(BOS_TOKEN_ID)), tokenShape).use { bosTensor ->
                OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(seqLen)), lenShape).use { seqLenTensor ->
                    val inputs = mapOf(
                        uncachedInputNames[0] to bosTensor,
                        uncachedInputNames[1] to contextTensor,
                        uncachedInputNames[2] to seqLenTensor,
                    )
                    val stepResult = bundle.uncachedDecodeSession.run(inputs)
                    val logitsTensor = stepResult.get(0) as? OnnxTensor
                    if (logitsTensor == null) {
                        stepResult.close()
                        return null
                    }
                    val picked = pickArgmaxToken(logitsTensor.floatBuffer, logitsTensor.info.shape.last().toInt())
                    nextToken = picked.tokenId
                    if (nextToken != EOS_TOKEN_ID) {
                        sumLogProb += picked.logProb
                    }
                    val states = ArrayList<OnnxTensor>(stepResult.size() - 1)
                    for (i in 1 until stepResult.size()) {
                        val st = stepResult.get(i) as? OnnxTensor
                        if (st != null) states.add(st)
                    }
                    activeDecodeResult = stepResult
                    currentStates = states
                }
            }

            while (generatedCount < maxLen && nextToken != EOS_TOKEN_ID) {
                if (isRepeatedTail(generatedIds, generatedCount, nextToken)) {
                    break
                }
                generatedIds[generatedCount] = nextToken
                generatedCount += 1
                seqLen += 1

                OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(nextToken)), tokenShape).use { tokTensor ->
                    OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(seqLen)), lenShape).use { seqLenTensor ->
                        val inputs = HashMap<String, OnnxTensor>(cachedInputNames.size)
                        inputs[cachedInputNames[0]] = tokTensor
                        inputs[cachedInputNames[1]] = contextTensor
                        inputs[cachedInputNames[2]] = seqLenTensor
                        val stateCount = minOf(currentStates.size, cachedInputNames.size - 3)
                        for (i in 0 until stateCount) {
                            inputs[cachedInputNames[3 + i]] = currentStates[i]
                        }

                        val stepResult = bundle.cachedDecodeSession.run(inputs)
                        val logitsTensor = stepResult.get(0) as? OnnxTensor
                        if (logitsTensor == null) {
                            stepResult.close()
                            break
                        }
                        val picked = pickArgmaxToken(logitsTensor.floatBuffer, logitsTensor.info.shape.last().toInt())
                        nextToken = picked.tokenId
                        if (nextToken != EOS_TOKEN_ID) {
                            sumLogProb += picked.logProb
                        }
                        val nextStates = ArrayList<OnnxTensor>(stepResult.size() - 1)
                        for (i in 1 until stepResult.size()) {
                            val st = stepResult.get(i) as? OnnxTensor
                            if (st != null) nextStates.add(st)
                        }
                        activeDecodeResult?.close()
                        activeDecodeResult = stepResult
                        currentStates = nextStates
                    }
                }
            }

            if (generatedCount == 0) return null
            val text = decodeSentencePieceTokens(
                tokenIds = generatedIds,
                count = generatedCount,
                tokensById = bundle.tokensById,
            ).trim()
            if (text.isEmpty()) return null

            val avgLogProb = sumLogProb / generatedCount.toDouble()
            val confidence = exp(avgLogProb).toFloat().coerceIn(0.05f, 1.0f)

            return OnDeviceChunkTranscriptionResult(
                text = text,
                language = language,
                confidence = confidence,
                engineId = engineId,
            )
        } finally {
            runCatching { activeDecodeResult?.close() }
        }
    }

    private fun runMergedGreedyDecodeLocked(
        bundle: LoadedMoonshineMergedBundle,
        samples: FloatArray,
        language: LanguageTag,
        engineId: EngineId,
    ): OnDeviceChunkTranscriptionResult? {
        val env = bundle.env
        val audioShape = longArrayOf(1L, samples.size.toLong())
        val encInputNames = bundle.encoderSession.inputNames.toList()
        if (encInputNames.isEmpty()) return null

        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), audioShape).use { audioTensor ->
            val maskTensor = if (encInputNames.size > 1) {
                val mask = LongArray(samples.size) { 1L }
                OnnxTensor.createTensor(env, LongBuffer.wrap(mask), audioShape)
            } else {
                null
            }
            try {
                val encInputs = LinkedHashMap<String, OnnxTensor>(2)
                encInputs[encInputNames[0]] = audioTensor
                if (maskTensor != null) {
                    encInputs[encInputNames[1]] = maskTensor
                }
                bundle.encoderSession.run(encInputs).use { encResult ->
                    val encoderOut = encResult.get(0) as? OnnxTensor ?: return null
                    return decodeMergedWithKvCache(
                        bundle = bundle,
                        encoderOut = encoderOut,
                        language = language,
                        engineId = engineId,
                    )
                }
            } finally {
                runCatching { maskTensor?.close() }
            }
        }
    }

    private fun decodeMergedWithKvCache(
        bundle: LoadedMoonshineMergedBundle,
        encoderOut: OnnxTensor,
        language: LanguageTag,
        engineId: EngineId,
    ): OnDeviceChunkTranscriptionResult? {
        val env = bundle.env
        val encShape = encoderOut.info.shape
        if (encShape.size < 2) return null
        val encoderFrames = encShape[1].coerceAtLeast(1L)
        val maxLen = ((encoderFrames * 384.0 / SAMPLE_RATE.toDouble()) * 6.5).toInt().coerceIn(8, MAX_DECODE_TOKENS)

        val decInputNames = bundle.decoderSession.inputNames.toList()
        val needsMask = decInputNames.any { it.contains("encoder_attention_mask") }
        val baseInputsOffset = if (needsMask) 3 else 2
        val numLayers = ((decInputNames.size - (baseInputsOffset + 1)) / 4).coerceAtLeast(1)

        var numHead = 8L
        var headDim = 36L
        for ((name, nodeInfo) in bundle.decoderSession.inputInfo) {
            if (name.contains("key_values")) {
                val tInfo = nodeInfo.info as? TensorInfo
                val shape = tInfo?.shape
                if (shape != null && shape.size == 4) {
                    if (shape[1] > 0) numHead = shape[1]
                    if (shape[3] > 0) headDim = shape[3]
                    break
                }
            }
        }
        val idsInputName = decInputNames[if (needsMask) 1 else 0]
        val idsType = (bundle.decoderSession.inputInfo[idsInputName]?.info as? TensorInfo)?.type

        val emptyKvShape = longArrayOf(1L, numHead, 0L, headDim)
        val initialEmptyTensors = ArrayList<OnnxTensor>(4 * numLayers)
        var step0Result: OrtSession.Result? = null
        var latestDecoderStepResult: OrtSession.Result? = null
        var maskTensor: OnnxTensor? = null

        try {
            if (needsMask) {
                val rawLen = (encoderFrames * 384L).toInt().coerceAtLeast(1)
                val mask = LongArray(rawLen) { 1L }
                maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(mask), longArrayOf(1L, rawLen.toLong()))
            }

            for (i in 0 until 4 * numLayers) {
                initialEmptyTensors.add(OnnxTensor.createTensor(env, FloatBuffer.allocate(0), emptyKvShape))
            }

            val states = ArrayList<OnnxTensor>(initialEmptyTensors)
            var sumLogProb = 0.0
            val generatedIds = IntArray(maxLen)
            var generatedCount = 0
            var nextToken = BOS_TOKEN_ID

            for (step in 0 until maxLen) {
                val useCache = step > 0
                val tokenTensor = createSingleTokenTensor(env, nextToken, idsType)
                val useCacheTensor = OnnxTensor.createTensor(env, booleanArrayOf(useCache))
                try {
                    val inputs = LinkedHashMap<String, OnnxTensor>(decInputNames.size)
                    var idx = 0
                    if (needsMask && maskTensor != null) {
                        inputs[decInputNames[idx++]] = maskTensor
                    }
                    inputs[decInputNames[idx++]] = tokenTensor
                    inputs[decInputNames[idx++]] = encoderOut
                    for (i in 0 until 4 * numLayers) {
                        inputs[decInputNames[idx++]] = states[i]
                    }
                    inputs[decInputNames[idx]] = useCacheTensor

                    val stepResult = bundle.decoderSession.run(inputs)
                    val logitsTensor = stepResult.get(0) as? OnnxTensor
                    if (logitsTensor == null) {
                        stepResult.close()
                        break
                    }
                    val picked = pickArgmaxToken(logitsTensor.floatBuffer, logitsTensor.info.shape.last().toInt())
                    nextToken = picked.tokenId

                    if (!useCache) {
                        for (i in 0 until 4 * numLayers) {
                            val outTensor = stepResult.get(1 + i) as? OnnxTensor
                            if (outTensor != null) states[i] = outTensor
                        }
                        step0Result = stepResult
                    } else {
                        for (layer in 0 until numLayers) {
                            val decKey = stepResult.get(1 + 4 * layer + 0) as? OnnxTensor
                            val decVal = stepResult.get(1 + 4 * layer + 1) as? OnnxTensor
                            if (decKey != null) states[4 * layer + 0] = decKey
                            if (decVal != null) states[4 * layer + 1] = decVal
                        }
                        latestDecoderStepResult?.close()
                        latestDecoderStepResult = stepResult
                    }

                    if (nextToken == EOS_TOKEN_ID) {
                        break
                    }
                    sumLogProb += picked.logProb
                    if (isRepeatedTail(generatedIds, generatedCount, nextToken)) {
                        break
                    }
                    generatedIds[generatedCount] = nextToken
                    generatedCount += 1
                } finally {
                    runCatching { tokenTensor.close() }
                    runCatching { useCacheTensor.close() }
                }
            }

            if (generatedCount == 0) return null
            val text = decodeSentencePieceTokens(
                tokenIds = generatedIds,
                count = generatedCount,
                tokensById = bundle.tokensById,
            ).trim()
            if (text.isEmpty()) return null

            val avgLogProb = sumLogProb / generatedCount.toDouble()
            val confidence = exp(avgLogProb).toFloat().coerceIn(0.05f, 1.0f)

            return OnDeviceChunkTranscriptionResult(
                text = text,
                language = language,
                confidence = confidence,
                engineId = engineId,
            )
        } finally {
            runCatching { latestDecoderStepResult?.close() }
            runCatching { step0Result?.close() }
            initialEmptyTensors.forEach { runCatching { it.close() } }
            runCatching { maskTensor?.close() }
        }
    }

    private fun createSingleTokenTensor(
        env: OrtEnvironment,
        tokenId: Int,
        expectedType: OnnxJavaType?,
    ): OnnxTensor {
        val shape = longArrayOf(1L, 1L)
        return if (expectedType == OnnxJavaType.INT32) {
            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(tokenId)), shape)
        } else {
            OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(tokenId.toLong())), shape)
        }
    }

    private data class TokenPick(
        val tokenId: Int,
        val logProb: Double,
    )

    private fun pickArgmaxToken(
        logitsBuffer: FloatBuffer,
        vocabSize: Int,
    ): TokenPick {
        val basePos = (logitsBuffer.remaining() - vocabSize).coerceAtLeast(0)
        var bestId = EOS_TOKEN_ID
        var bestLogit = Float.NEGATIVE_INFINITY
        for (id in 0 until vocabSize) {
            val v = logitsBuffer.get(basePos + id)
            if (v > bestLogit) {
                bestLogit = v
                bestId = id
            }
        }
        if (!bestLogit.isFinite()) return TokenPick(EOS_TOKEN_ID, -10.0)
        var sumExp = 0.0
        for (id in 0 until vocabSize) {
            val v = logitsBuffer.get(basePos + id)
            sumExp += exp((v - bestLogit).toDouble())
        }
        val logProb = if (sumExp > 0.0) -ln(sumExp) else -5.0
        return TokenPick(bestId, logProb)
    }

    private fun isRepeatedTail(
        ids: IntArray,
        count: Int,
        candidate: Int,
    ): Boolean {
        if (count < 6) return false
        for (i in 1..6) {
            if (ids[count - i] != candidate) return false
        }
        return true
    }

    private fun ensureLoadedBundleLocked(model: DownloadableSttModel): LoadedMoonshineBundle? {
        val paths = modelManager.resolveMoonshineModelPaths(model, LanguageTag("en")) ?: run {
            loadedBundle?.close()
            loadedBundle = null
            return null
        }

        val preMod = paths.preprocessFile.lastModified()
        val encMod = paths.encodeFile.lastModified()
        val uncMod = paths.uncachedDecodeFile.lastModified()
        val cacMod = paths.cachedDecodeFile.lastModified()
        val tokMod = paths.tokensFile.lastModified()

        val existing = loadedBundle
        if (existing != null &&
            existing.model == model &&
            existing.preprocessLastModified == preMod &&
            existing.encodeLastModified == encMod &&
            existing.uncachedLastModified == uncMod &&
            existing.cachedLastModified == cacMod &&
            existing.tokensLastModified == tokMod
        ) {
            return existing
        }

        existing?.close()
        loadedBundle = null

        return try {
            val tokensById = parseMoonshineTokensFile(paths.tokensFile)
            if (tokensById.isEmpty()) return null

            val env = OrtEnvironment.getEnvironment()
            val preSession = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }.use {
                env.createSession(paths.preprocessFile.absolutePath, it)
            }
            val encSession = try {
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }.use {
                    env.createSession(paths.encodeFile.absolutePath, it)
                }
            } catch (e: Exception) {
                runCatching { preSession.close() }
                throw e
            }
            val uncSession = try {
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }.use {
                    env.createSession(paths.uncachedDecodeFile.absolutePath, it)
                }
            } catch (e: Exception) {
                runCatching { preSession.close() }
                runCatching { encSession.close() }
                throw e
            }
            val cacSession = try {
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }.use {
                    env.createSession(paths.cachedDecodeFile.absolutePath, it)
                }
            } catch (e: Exception) {
                runCatching { preSession.close() }
                runCatching { encSession.close() }
                runCatching { uncSession.close() }
                throw e
            }

            LoadedMoonshineBundle(
                model = model,
                preprocessLastModified = preMod,
                encodeLastModified = encMod,
                uncachedLastModified = uncMod,
                cachedLastModified = cacMod,
                tokensLastModified = tokMod,
                env = env,
                preprocessSession = preSession,
                encodeSession = encSession,
                uncachedDecodeSession = uncSession,
                cachedDecodeSession = cacSession,
                tokensById = tokensById,
            ).also {
                loadedBundle = it
            }
        } catch (e: Exception) {
            logInfo("failed to load Moonshine sessions model=${model.modelId} error=${e.javaClass.simpleName}")
            null
        }
    }

    private fun ensureLoadedMergedBundleLocked(
        model: DownloadableSttModel,
        language: LanguageTag,
    ): LoadedMoonshineMergedBundle? {
        val langKey = SherpaOnnxModelManager.normalizeLanguageKey(language)
        val paths = modelManager.resolveMoonshineMergedModelPaths(model, language) ?: run {
            loadedMergedBundle?.close()
            loadedMergedBundle = null
            return null
        }

        val encMod = paths.encoderFile.lastModified()
        val decMod = paths.decoderMergedFile.lastModified()
        val tokMod = paths.tokensFile.lastModified()

        val existing = loadedMergedBundle
        if (existing != null &&
            existing.model == model &&
            existing.langKey == langKey &&
            existing.encoderLastModified == encMod &&
            existing.decoderLastModified == decMod &&
            existing.tokensLastModified == tokMod
        ) {
            return existing
        }

        existing?.close()
        loadedMergedBundle = null

        return try {
            val tokensById = parseMoonshineTokensFile(paths.tokensFile)
            if (tokensById.isEmpty()) return null

            val env = OrtEnvironment.getEnvironment()
            val encSession = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }.use {
                env.createSession(paths.encoderFile.absolutePath, it)
            }
            val decSession = try {
                OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }.use {
                    env.createSession(paths.decoderMergedFile.absolutePath, it)
                }
            } catch (e: Exception) {
                runCatching { encSession.close() }
                throw e
            }

            LoadedMoonshineMergedBundle(
                model = model,
                langKey = langKey,
                encoderLastModified = encMod,
                decoderLastModified = decMod,
                tokensLastModified = tokMod,
                env = env,
                encoderSession = encSession,
                decoderSession = decSession,
                tokensById = tokensById,
            ).also {
                loadedMergedBundle = it
            }
        } catch (e: Exception) {
            logInfo("failed to load Moonshine v2 merged sessions model=${model.modelId} lang=$langKey error=${e.javaClass.simpleName}")
            null
        }
    }

    private class LoadedMoonshineBundle(
        val model: DownloadableSttModel,
        val preprocessLastModified: Long,
        val encodeLastModified: Long,
        val uncachedLastModified: Long,
        val cachedLastModified: Long,
        val tokensLastModified: Long,
        val env: OrtEnvironment,
        val preprocessSession: OrtSession,
        val encodeSession: OrtSession,
        val uncachedDecodeSession: OrtSession,
        val cachedDecodeSession: OrtSession,
        val tokensById: Map<Int, String>,
    ) {
        fun close() {
            runCatching { preprocessSession.close() }
            runCatching { encodeSession.close() }
            runCatching { uncachedDecodeSession.close() }
            runCatching { cachedDecodeSession.close() }
        }
    }

    private class LoadedMoonshineMergedBundle(
        val model: DownloadableSttModel,
        val langKey: String,
        val encoderLastModified: Long,
        val decoderLastModified: Long,
        val tokensLastModified: Long,
        val env: OrtEnvironment,
        val encoderSession: OrtSession,
        val decoderSession: OrtSession,
        val tokensById: Map<Int, String>,
    ) {
        fun close() {
            runCatching { encoderSession.close() }
            runCatching { decoderSession.close() }
        }
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    companion object {
        private const val TAG = "MoonshineTranscriber"
        val MOONSHINE_TINY_ENGINE_ID = EngineId("moonshine-onnx-tiny-en-int8")
        val MOONSHINE_BASE_ENGINE_ID = EngineId("moonshine-onnx-base-en-int8")

        private const val SAMPLE_RATE = 16_000
        private const val MIN_AUDIO_SAMPLES = 1_600 // 0.1s
        private const val MAX_AUDIO_SAMPLES = SAMPLE_RATE * 30 // 30s
        private const val MAX_DECODE_TOKENS = 200
        private const val BOS_TOKEN_ID = 1
        private const val EOS_TOKEN_ID = 2
        private const val SILENCE_SAMPLE_ABS_MAX = 1e-6f

        internal fun parseMoonshineTokensFile(file: File): Map<Int, String> {
            if (!file.isFile) return emptyMap()
            val rawMap = HashMap<Int, String>(33_000)
            file.useLines(Charsets.UTF_8) { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trimEnd('\r', '\n')
                    if (line.isEmpty()) return@forEach
                    val tabIdx = line.lastIndexOf('\t')
                    val splitIdx = if (tabIdx > 0) tabIdx else line.lastIndexOf(' ')
                    if (splitIdx <= 0) return@forEach
                    val piece = line.substring(0, splitIdx)
                    val id = line.substring(splitIdx + 1).trim().toIntOrNull() ?: return@forEach
                    rawMap[id] = piece
                }
            }
            val isBase64Encoded = rawMap[0] == "PHVuaz4=" || rawMap[1] == "PHM+"
            if (!isBase64Encoded) return rawMap

            val decodedMap = HashMap<Int, String>(rawMap.size)
            val decoder = Base64.getDecoder()
            for ((id, b64Piece) in rawMap) {
                val bytes = runCatching { decoder.decode(b64Piece) }.getOrNull() ?: continue
                if (bytes.size == 1 && id in 3..258) {
                    decodedMap[id] = String.format(Locale.ROOT, "<0x%02X>", bytes[0].toInt() and 0xFF)
                } else {
                    decodedMap[id] = String(bytes, Charsets.UTF_8)
                }
            }
            return decodedMap
        }

        internal fun decodeSentencePieceTokens(
            tokenIds: IntArray,
            count: Int,
            tokensById: Map<Int, String>,
        ): String {
            val out = ByteArrayOutputStream(count * 4)
            for (i in 0 until count) {
                val id = tokenIds[i]
                if (id <= EOS_TOKEN_ID) continue
                val piece = tokensById[id] ?: continue
                if (piece.length == 6 && piece.startsWith("<0x") && piece.endsWith(">")) {
                    val hexByte = piece.substring(3, 5).toIntOrNull(16)
                    if (hexByte != null) {
                        out.write(hexByte)
                        continue
                    }
                }
                if (piece.startsWith("<") && piece.endsWith(">")) continue
                val normalized = piece.replace("▁", " ")
                val bytes = normalized.toByteArray(Charsets.UTF_8)
                out.write(bytes, 0, bytes.size)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        private fun resamplePcm16LeTo16kMonoNormalized(
            pcm16LeBytes: ByteArray,
            sampleRateHz: Int,
            channelCount: Int,
        ): FloatArray {
            val totalShorts = pcm16LeBytes.size / 2
            val frameCount = totalShorts / channelCount
            if (frameCount <= 0) return FloatArray(0)

            val shortBuf = ByteBuffer.wrap(pcm16LeBytes, 0, frameCount * channelCount * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .asShortBuffer()

            val scale = 1.0f / 32768.0f
            val monoSrc = FloatArray(frameCount)
            if (channelCount == 1) {
                for (i in 0 until frameCount) {
                    monoSrc[i] = (shortBuf.get(i).toFloat() * scale).coerceIn(-1.0f, 1.0f)
                }
            } else {
                val invCh = scale / channelCount.toFloat()
                for (i in 0 until frameCount) {
                    var sum = 0f
                    val base = i * channelCount
                    for (ch in 0 until channelCount) {
                        sum += shortBuf.get(base + ch).toFloat()
                    }
                    monoSrc[i] = (sum * invCh).coerceIn(-1.0f, 1.0f)
                }
            }

            if (sampleRateHz == SAMPLE_RATE) return monoSrc
            val outLen = ((frameCount.toLong() * SAMPLE_RATE) / sampleRateHz).toInt()
            if (outLen <= 0) return FloatArray(0)
            val ratio = sampleRateHz.toDouble() / SAMPLE_RATE.toDouble()
            return FloatArray(outLen) { outIdx ->
                val srcPos = outIdx * ratio
                val idx0 = srcPos.toInt().coerceIn(0, frameCount - 1)
                val idx1 = (idx0 + 1).coerceAtMost(frameCount - 1)
                val frac = (srcPos - idx0).toFloat()
                monoSrc[idx0] * (1f - frac) + monoSrc[idx1] * frac
            }
        }
    }
}
