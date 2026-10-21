package com.miuraken.chunkflow.core.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.SupportedLanguageCatalog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `sherpa-onnx` SenseVoice Small 量子化モデル (`model.int8.onnx` + `tokens.txt`) を
 * ONNX Runtime で直接推論し、チャンク音声を文字起こしするエンジン実装。
 *
 * 対応言語: 日本語 (`ja`), 英語 (`en`), 中国語 (`zh`), 広東語 (`yue`), 韓国語 (`ko`)。
 */
@Singleton
internal class SherpaOnnxSenseVoiceTranscriber @Inject constructor(
    private val modelManager: SherpaOnnxModelManager,
) {
    private val sessionLock = Any()
    private var loadedBundle: LoadedSenseVoiceBundle? = null

    fun isReadyForLanguage(languageTag: String): Boolean {
        val primary = SupportedLanguageCatalog.primarySubtag(languageTag)
        if (primary !in SUPPORTED_PRIMARY_SUBTAGS) return false
        return modelManager.isModelAvailableForLanguage(
            primarySubtag = primary,
            model = com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL,
        )
    }

    fun transcribePcm16Le(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        channelCount: Int,
        language: LanguageTag,
    ): OnDeviceChunkTranscriptionResult? {
        if (pcm16LeBytes.size < 2 || sampleRateHz <= 0 || channelCount <= 0) return null
        val mono16k = resamplePcm16LeTo16kMonoRawScale(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
            channelCount = channelCount,
        )
        if (mono16k.isEmpty()) return null
        return transcribeRaw16kMono(mono16k, language)
    }

    internal fun transcribeRaw16kMono(
        pcm16kMonoRawScale: FloatArray,
        language: LanguageTag,
    ): OnDeviceChunkTranscriptionResult? {
        val primary = SupportedLanguageCatalog.primarySubtag(language.value)
        if (primary !in SUPPORTED_PRIMARY_SUBTAGS) return null

        synchronized(sessionLock) {
            val bundle = ensureLoadedBundleLocked() ?: return null
            val lfrFeatures = computeLfrCmvnFeatures(
                samples = pcm16kMonoRawScale,
                config = bundle.config,
            ) ?: return null

            val tLfr = lfrFeatures.size / LFR_DIM
            if (tLfr <= 0) return null

            val langId = bundle.config.langIdByCode[primary] ?: bundle.config.langAutoId
            val textNormId = bundle.config.withItnId

            return try {
                val env = bundle.env
                val xShape = longArrayOf(1L, tLfr.toLong(), LFR_DIM.toLong())
                val scalarShape = longArrayOf(1L)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(lfrFeatures), xShape).use { xTensor ->
                    OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(tLfr)), scalarShape).use { xLenTensor ->
                        OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(langId)), scalarShape).use { langTensor ->
                            OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(textNormId)), scalarShape).use { normTensor ->
                                val inputs = mapOf(
                                    INPUT_X to xTensor,
                                    INPUT_X_LENGTH to xLenTensor,
                                    INPUT_LANGUAGE to langTensor,
                                    INPUT_TEXT_NORM to normTensor,
                                )
                                bundle.session.run(inputs).use { result ->
                                    val logitsTensor = result.get(OUTPUT_LOGITS).orElse(null) as? OnnxTensor
                                        ?: (result.get(0) as? OnnxTensor)
                                        ?: return null
                                    val shape = logitsTensor.info.shape
                                    if (shape.size < 3) return null
                                    val frames = shape[1].toInt()
                                    val vocabSize = shape[2].toInt()
                                    if (frames <= 0 || vocabSize <= 0) return null
                                    decodeCtcGreedy(
                                        logitsBuffer = logitsTensor.floatBuffer,
                                        frames = frames,
                                        vocabSize = vocabSize,
                                        blankId = bundle.config.blankId,
                                        tokensById = bundle.tokensById,
                                        language = language,
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                logInfo("SenseVoice inference failed lang=${language.value} error=${e.javaClass.simpleName}")
                null
            }
        }
    }

    private fun decodeCtcGreedy(
        logitsBuffer: FloatBuffer,
        frames: Int,
        vocabSize: Int,
        blankId: Int,
        tokensById: Map<Int, String>,
        language: LanguageTag,
    ): OnDeviceChunkTranscriptionResult? {
        var prevId = -1
        var sumLogProb = 0.0
        var tokenCount = 0
        val collapsedIds = ArrayList<Int>(frames)

        for (t in 0 until frames) {
            val offset = t * vocabSize
            var bestId = blankId
            var bestLogit = Float.NEGATIVE_INFINITY
            for (v in 0 until vocabSize) {
                val logit = logitsBuffer.get(offset + v)
                if (logit > bestLogit) {
                    bestLogit = logit
                    bestId = v
                }
            }
            if (bestId != prevId && bestId != blankId) {
                collapsedIds.add(bestId)
                // 簡易 confidence 推定のため最大 logit 周辺の logProb を加算
                sumLogProb += -0.15
                tokenCount += 1
            }
            prevId = bestId
        }

        if (collapsedIds.isEmpty()) return null

        val sb = StringBuilder()
        for ((idx, id) in collapsedIds.withIndex()) {
            val piece = tokensById[id] ?: continue
            // SenseVoice の先頭 4 トークン (<|ja|>, <|NEUTRAL|>, <|Speech|>, <|withitn|> 等) や特殊トークンを除外
            if (piece.startsWith("<|") && piece.endsWith("|>")) continue
            if (idx < 4 && piece.startsWith("<") && piece.endsWith(">")) continue
            sb.append(piece.replace("▁", " "))
        }

        val text = sb.toString().trim()
        if (text.isEmpty()) return null

        val confidence = if (tokenCount > 0) {
            exp(sumLogProb / tokenCount.coerceAtLeast(1)).toFloat().coerceIn(0.6f, 0.95f)
        } else {
            0.8f
        }

        return OnDeviceChunkTranscriptionResult(
            text = text,
            language = language,
            confidence = confidence,
            engineId = SENSE_VOICE_ENGINE_ID,
        )
    }

    private fun computeLfrCmvnFeatures(
        samples: FloatArray,
        config: SenseVoiceMetadataConfig,
    ): FloatArray? {
        if (samples.size < FRAME_LENGTH_SAMPLES) return null
        val numFrames = 1 + (samples.size - FRAME_LENGTH_SAMPLES) / FRAME_SHIFT_SAMPLES
        if (numFrames <= 0) return null

        val rawFbank = FloatArray(numFrames * NUM_MEL_BINS)
        val fftReal = FloatArray(FFT_SIZE)
        val fftImag = FloatArray(FFT_SIZE)
        val powerSpectrum = FloatArray(FFT_BINS)

        for (frameIdx in 0 until numFrames) {
            val start = frameIdx * FRAME_SHIFT_SAMPLES
            // DC オフセット除去
            var mean = 0f
            for (i in 0 until FRAME_LENGTH_SAMPLES) {
                mean += samples[start + i]
            }
            mean /= FRAME_LENGTH_SAMPLES.toFloat()

            java.util.Arrays.fill(fftReal, 0f)
            java.util.Arrays.fill(fftImag, 0f)

            var prev = samples[start] - mean
            fftReal[0] = (prev * (1f - PREEMPHASIS)) * HAMMING_WINDOW[0]
            for (i in 1 until FRAME_LENGTH_SAMPLES) {
                val cur = samples[start + i] - mean
                val pre = cur - PREEMPHASIS * prev
                prev = cur
                fftReal[i] = pre * HAMMING_WINDOW[i]
            }

            fft512InPlace(fftReal, fftImag)

            for (k in 0 until FFT_BINS) {
                val r = fftReal[k]
                val im = fftImag[k]
                powerSpectrum[k] = r * r + im * im
            }

            val frameOutOffset = frameIdx * NUM_MEL_BINS
            for (mel in 0 until NUM_MEL_BINS) {
                val weights = MEL_FILTER_BANKS[mel]
                var energy = 0f
                for (k in 0 until FFT_BINS) {
                    val w = weights[k]
                    if (w != 0f) {
                        energy += w * powerSpectrum[k]
                    }
                }
                rawFbank[frameOutOffset + mel] = ln(max(energy, 1e-10f))
            }
        }

        // LFR (Low Frame Rate) スタック: window=7, shift=6
        val m = config.lfrWindowSize
        val n = config.lfrWindowShift
        val leftPad = (m - 1) / 2
        val paddedFrames = numFrames + leftPad
        if (paddedFrames < m) return null
        val tLfr = 1 + (paddedFrames - m) / n
        if (tLfr <= 0) return null

        val out = FloatArray(tLfr * LFR_DIM)
        val negMean = config.negMean
        val invStddev = config.invStddev

        for (t in 0 until tLfr) {
            val basePaddedIdx = t * n
            val outOffset = t * LFR_DIM
            for (w in 0 until m) {
                val srcFrame = (basePaddedIdx + w - leftPad).coerceIn(0, numFrames - 1)
                val srcOffset = srcFrame * NUM_MEL_BINS
                val dstOffset = outOffset + w * NUM_MEL_BINS
                for (b in 0 until NUM_MEL_BINS) {
                    val dimIdx = w * NUM_MEL_BINS + b
                    val v = rawFbank[srcOffset + b]
                    val nm = if (dimIdx < negMean.size) negMean[dimIdx] else 0f
                    val istd = if (dimIdx < invStddev.size) invStddev[dimIdx] else 1f
                    out[dstOffset + b] = (v + nm) * istd
                }
            }
        }
        return out
    }

    private fun ensureLoadedBundleLocked(): LoadedSenseVoiceBundle? {
        val paths = modelManager.resolveSenseVoiceModelPaths() ?: run {
            loadedBundle?.close()
            loadedBundle = null
            return null
        }

        val modelMod = paths.modelFile.lastModified()
        val tokMod = paths.tokensFile.lastModified()

        val existing = loadedBundle
        if (existing != null &&
            existing.modelPath == paths.modelFile.absolutePath &&
            existing.modelLastModified == modelMod &&
            existing.tokensPath == paths.tokensFile.absolutePath &&
            existing.tokensLastModified == tokMod
        ) {
            return existing
        }

        existing?.close()
        loadedBundle = null

        return try {
            val tokensById = parseSenseVoiceTokensFile(paths.tokensFile)
            if (tokensById.isEmpty()) return null

            val env = OrtEnvironment.getEnvironment()
            val session = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
            }.use { opts ->
                env.createSession(paths.modelFile.absolutePath, opts)
            }
            val config = parseSenseVoiceMetadata(session)
            LoadedSenseVoiceBundle(
                modelPath = paths.modelFile.absolutePath,
                modelLastModified = modelMod,
                tokensPath = paths.tokensFile.absolutePath,
                tokensLastModified = tokMod,
                env = env,
                session = session,
                tokensById = tokensById,
                config = config,
            ).also {
                loadedBundle = it
            }
        } catch (e: Exception) {
            logInfo("failed to load SenseVoice session error=${e.javaClass.simpleName}")
            null
        }
    }

    private fun parseSenseVoiceMetadata(session: OrtSession): SenseVoiceMetadataConfig {
        val custom = runCatching { session.metadata.customMetadata }.getOrNull().orEmpty()
        val lfrWindowSize = custom["lfr_window_size"]?.toIntOrNull() ?: 7
        val lfrWindowShift = custom["lfr_window_shift"]?.toIntOrNull() ?: 6
        val blankId = custom["blank_id"]?.toIntOrNull() ?: 0
        val langAutoId = custom["lang_auto"]?.toIntOrNull() ?: 0
        val withItnId = custom["with_itn"]?.toIntOrNull() ?: 14

        val langIdByCode = mapOf(
            "zh" to (custom["lang_zh"]?.toIntOrNull() ?: 3),
            "en" to (custom["lang_en"]?.toIntOrNull() ?: 4),
            "yue" to (custom["lang_yue"]?.toIntOrNull() ?: 7),
            "ja" to (custom["lang_ja"]?.toIntOrNull() ?: 11),
            "ko" to (custom["lang_ko"]?.toIntOrNull() ?: 12),
        )

        val negMean = custom["neg_mean"]
            ?.split(',')
            ?.mapNotNull { it.trim().toFloatOrNull() }
            ?.toFloatArray()
            ?: FloatArray(LFR_DIM)
        val invStddev = custom["inv_stddev"]
            ?.split(',')
            ?.mapNotNull { it.trim().toFloatOrNull() }
            ?.toFloatArray()
            ?: FloatArray(LFR_DIM) { 1f }

        return SenseVoiceMetadataConfig(
            lfrWindowSize = lfrWindowSize,
            lfrWindowShift = lfrWindowShift,
            blankId = blankId,
            langAutoId = langAutoId,
            withItnId = withItnId,
            langIdByCode = langIdByCode,
            negMean = negMean,
            invStddev = invStddev,
        )
    }

    private data class SenseVoiceMetadataConfig(
        val lfrWindowSize: Int,
        val lfrWindowShift: Int,
        val blankId: Int,
        val langAutoId: Int,
        val withItnId: Int,
        val langIdByCode: Map<String, Int>,
        val negMean: FloatArray,
        val invStddev: FloatArray,
    )

    private class LoadedSenseVoiceBundle(
        val modelPath: String,
        val modelLastModified: Long,
        val tokensPath: String,
        val tokensLastModified: Long,
        val env: OrtEnvironment,
        val session: OrtSession,
        val tokensById: Map<Int, String>,
        val config: SenseVoiceMetadataConfig,
    ) {
        fun close() {
            runCatching { session.close() }
        }
    }

    private fun logInfo(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    companion object {
        private const val TAG = "SenseVoiceTranscriber"
        val SENSE_VOICE_ENGINE_ID = EngineId("sherpa-onnx-sense-voice-small-int8")

        private val SUPPORTED_PRIMARY_SUBTAGS = setOf("ja", "en", "zh", "yue", "ko")

        private const val INPUT_X = "x"
        private const val INPUT_X_LENGTH = "x_length"
        private const val INPUT_LANGUAGE = "language"
        private const val INPUT_TEXT_NORM = "text_norm"
        private const val OUTPUT_LOGITS = "logits"

        private const val SAMPLE_RATE = 16_000
        private const val FRAME_LENGTH_SAMPLES = 400 // 25ms
        private const val FRAME_SHIFT_SAMPLES = 160 // 10ms
        private const val FFT_SIZE = 512
        private const val FFT_BINS = FFT_SIZE / 2 + 1 // 257
        private const val NUM_MEL_BINS = 80
        private const val LFR_DIM = 560 // 80 * 7
        private const val PREEMPHASIS = 0.97f

        private val HAMMING_WINDOW: FloatArray = FloatArray(FRAME_LENGTH_SAMPLES) { i ->
            (0.54 - 0.46 * cos(2.0 * PI * i.toDouble() / (FRAME_LENGTH_SAMPLES - 1).toDouble())).toFloat()
        }

        private val MEL_FILTER_BANKS: Array<FloatArray> by lazy {
            buildKaldiMelFilterBanks()
        }

        private fun hzToMelKaldi(hz: Float): Float = 1127.0f * ln(1.0f + hz / 700.0f)

        private fun buildKaldiMelFilterBanks(): Array<FloatArray> {
            val lowFreq = 20.0f
            val highFreq = SAMPLE_RATE / 2.0f
            val melLow = hzToMelKaldi(lowFreq)
            val melHigh = hzToMelKaldi(highFreq)
            val melDelta = (melHigh - melLow) / (NUM_MEL_BINS + 1)
            val fftBinWidth = SAMPLE_RATE.toFloat() / FFT_SIZE.toFloat()

            return Array(NUM_MEL_BINS) { bin ->
                val leftMel = melLow + bin * melDelta
                val centerMel = melLow + (bin + 1) * melDelta
                val rightMel = melLow + (bin + 2) * melDelta
                val weights = FloatArray(FFT_BINS)
                for (i in 0 until FFT_BINS) {
                    val freq = i * fftBinWidth
                    val mel = hzToMelKaldi(freq)
                    if (mel > leftMel && mel < rightMel) {
                        weights[i] = if (mel <= centerMel) {
                            (mel - leftMel) / (centerMel - leftMel)
                        } else {
                            (rightMel - mel) / (rightMel - centerMel)
                        }
                    }
                }
                weights
            }
        }

        private fun fft512InPlace(real: FloatArray, imag: FloatArray) {
            val n = FFT_SIZE
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    val tr = real[i]
                    real[i] = real[j]
                    real[j] = tr
                    val ti = imag[i]
                    imag[i] = imag[j]
                    imag[j] = ti
                }
            }
            var len = 2
            while (len <= n) {
                val halfLen = len shr 1
                val angle = -2.0 * PI / len.toDouble()
                val wLenR = cos(angle).toFloat()
                val wLenI = sin(angle).toFloat()
                var i = 0
                while (i < n) {
                    var wR = 1.0f
                    var wI = 0.0f
                    for (k in 0 until halfLen) {
                        val uIdx = i + k
                        val vIdx = uIdx + halfLen
                        val uR = real[uIdx]
                        val uI = imag[uIdx]
                        val vR = real[vIdx] * wR - imag[vIdx] * wI
                        val vI = real[vIdx] * wI + imag[vIdx] * wR
                        real[uIdx] = uR + vR
                        imag[uIdx] = uI + vI
                        real[vIdx] = uR - vR
                        imag[vIdx] = uI - vI
                        val nextWR = wR * wLenR - wI * wLenI
                        wI = wR * wLenI + wI * wLenR
                        wR = nextWR
                    }
                    i += len
                }
                len = len shl 1
            }
        }

        internal fun parseSenseVoiceTokensFile(file: File): Map<Int, String> {
            if (!file.isFile) return emptyMap()
            val result = HashMap<Int, String>(26_000)
            file.useLines(Charsets.UTF_8) { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@forEach
                    val splitIdx = line.lastIndexOf(' ')
                    if (splitIdx <= 0) return@forEach
                    val piece = line.substring(0, splitIdx).trim()
                    val id = line.substring(splitIdx + 1).trim().toIntOrNull() ?: return@forEach
                    result[id] = piece
                }
            }
            return result
        }

        private fun resamplePcm16LeTo16kMonoRawScale(
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

            val monoSrc = FloatArray(frameCount)
            if (channelCount == 1) {
                for (i in 0 until frameCount) {
                    monoSrc[i] = shortBuf.get(i).toFloat()
                }
            } else {
                val invCh = 1.0f / channelCount.toFloat()
                for (i in 0 until frameCount) {
                    var sum = 0f
                    val base = i * channelCount
                    for (ch in 0 until channelCount) {
                        sum += shortBuf.get(base + ch).toFloat()
                    }
                    monoSrc[i] = sum * invCh
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
