package com.miuraken.chunkflow.core.media

import android.content.Context
import android.os.ParcelFileDescriptor
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.common.internal.GenAiUtils
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.miuraken.chunkflow.core.model.EngineId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ML Kit GenAI Speech Recognition (`com.google.mlkit:genai-speech-recognition`) を抽象化するゲートウェイ（F6.2）。
 *
 * AICore 対応端末（Pixel 上位機種など）でオンデバイス GenAI 音声認識を実行し、
 * 非対応端末や単体テスト環境では安全にフォールバックまたは差し替えできるようにする。
 */
internal interface MlKitSpeechRecognitionGateway {
    /** 端末が AICore / ML Kit GenAI に対応している可能性があるかどうかを返す。 */
    fun isDevicePotentiallySupported(): Boolean

    /** 指定した BCP-47 ロケールタグ群のモデル利用可能状態とモードを問い合わせる。 */
    suspend fun checkMlKitSupport(bcp47LocaleTags: List<String>): Map<String, Pair<RecognitionModelSupport, com.miuraken.chunkflow.core.model.MlKitRecognitionMode?>>

    /** 指定した BCP-47 ロケールタグのモデルダウンロードを開始し、進捗イベントを通知する。 */
    suspend fun downloadModel(
        bcp47LocaleTag: String,
        onEvent: (RecognitionModelDownloadEvent) -> Unit = {},
    ): Boolean

    /**
     * 16kHz モノラル 16-bit リトルエンディアン PCM を `AudioSource.fromPfd` 経由で ML Kit GenAI に供給して音声認識を行う。
     */
    suspend fun recognizePcm16Le(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        isProbe: Boolean,
    ): SpeechRecognitionAttemptResult?
}

/**
 * Google ML Kit GenAI Speech Recognition SDK を用いた [MlKitSpeechRecognitionGateway] 実装（F6.2）。
 *
 * - `SpeechRecognizerOptions.Mode.MODE_ADVANCED` を優先して確認し、非対応（`FeatureStatus.UNAVAILABLE`）のロケール・端末では
 *   `MODE_BASIC` へ自動的にフォールバックして利用可能状態を確認・実行する。
 * - 一度 `FeatureStatus.AVAILABLE` が確認されたロケールタグの採用モード（`MODE_ADVANCED` / `MODE_BASIC`）はメモリ上にキャッシュし、
 *   チャンク認識ごとの AICore IPC 往復（`checkStatus()`）を省略する。
 * - `ParcelFileDescriptor.createPipe()` と `AudioSource.fromPfd(readSide)` を用いてチャンク PCM を直接供給する。
 */
@Singleton
internal class MlKitGenAiSpeechTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
) : MlKitSpeechRecognitionGateway {

    /**
     * 正規化済み BCP-47 タグ（小文字、`'-'` 区切り）ごとに、`FeatureStatus.AVAILABLE` が確認済みの
     * `@SpeechRecognizerOptions.Mode` を保持するキャッシュ。
     */
    private val cachedAvailableModeByTag = ConcurrentHashMap<String, Int>()

    /**
     * 端末単位で `MODE_ADVANCED`（AICore feature 0）が `FeatureStatus.AVAILABLE` であることが確認済みの場合はキャッシュする。
     * `MODE_ADVANCED` の `checkStatus()` はロケールを見ずに AICore feature 0 の状態のみを返すため、
     * ロケールごとの対応可否・言語パックのダウンロード状態は必ず `MODE_BASIC` 側で確認する。
     */
    @Volatile
    private var cachedAdvancedFeatureAvailable: Boolean = false

    private fun normalizeTagKey(bcp47LocaleTag: String): String = bcp47LocaleTag.trim().replace('_', '-').lowercase(Locale.ROOT)

    override fun isDevicePotentiallySupported(): Boolean = runCatching { GenAiUtils.isAiCoreCompatible(context) }.getOrDefault(false)

    override suspend fun checkMlKitSupport(
        bcp47LocaleTags: List<String>,
    ): Map<String, Pair<RecognitionModelSupport, com.miuraken.chunkflow.core.model.MlKitRecognitionMode?>> {
        if (bcp47LocaleTags.isEmpty() || !isDevicePotentiallySupported()) {
            return emptyMap()
        }
        val result = LinkedHashMap<String, Pair<RecognitionModelSupport, com.miuraken.chunkflow.core.model.MlKitRecognitionMode?>>(bcp47LocaleTags.size)
        for (tag in bcp47LocaleTags) {
            val modeAndStatus = checkFeatureStatusForTag(tag, useAvailableCache = false)
            if (modeAndStatus == null) {
                result[tag] = RecognitionModelSupport.UNSUPPORTED to null
            } else {
                val mode = if (modeAndStatus.first == SpeechRecognizerOptions.Mode.MODE_ADVANCED) {
                    com.miuraken.chunkflow.core.model.MlKitRecognitionMode.ADVANCED
                } else {
                    com.miuraken.chunkflow.core.model.MlKitRecognitionMode.BASIC
                }
                result[tag] = featureStatusToModelSupport(modeAndStatus.second) to mode
            }
        }
        return result
    }

    override suspend fun downloadModel(
        bcp47LocaleTag: String,
        onEvent: (RecognitionModelDownloadEvent) -> Unit,
    ): Boolean {
        if (!isDevicePotentiallySupported()) {
            onEvent(RecognitionModelDownloadEvent.FAILED)
            return false
        }
        val basicStatus = checkSingleModeStatus(bcp47LocaleTag, SpeechRecognizerOptions.Mode.MODE_BASIC)
        if (basicStatus == null || basicStatus == FeatureStatus.UNAVAILABLE) {
            cachedAvailableModeByTag.remove(normalizeTagKey(bcp47LocaleTag))
            onEvent(RecognitionModelDownloadEvent.FAILED)
            return false
        }
        val advancedStatus = checkDeviceAdvancedStatus(bcp47LocaleTag, useAvailableCache = false)
        val targetMode = if (advancedStatus != null && advancedStatus != FeatureStatus.UNAVAILABLE) {
            SpeechRecognizerOptions.Mode.MODE_ADVANCED
        } else {
            SpeechRecognizerOptions.Mode.MODE_BASIC
        }
        if (basicStatus == FeatureStatus.AVAILABLE &&
            (targetMode == SpeechRecognizerOptions.Mode.MODE_BASIC || advancedStatus == FeatureStatus.AVAILABLE)
        ) {
            cachedAvailableModeByTag[normalizeTagKey(bcp47LocaleTag)] = targetMode
            onEvent(RecognitionModelDownloadEvent.SUCCESS)
            return true
        }

        // GoogleAsrService 側の言語別モデル取得は MODE_BASIC の download() でのみ locale タグが送信されるため、
        // basicStatus が未インストールの場合は必ず MODE_BASIC でダウンロードを実行する。
        val basicDownloaded = if (basicStatus != FeatureStatus.AVAILABLE) {
            downloadSingleModeModel(bcp47LocaleTag, SpeechRecognizerOptions.Mode.MODE_BASIC, onEvent)
        } else {
            true
        }
        if (!basicDownloaded) {
            return false
        }
        val advancedDownloaded = if (
            targetMode == SpeechRecognizerOptions.Mode.MODE_ADVANCED &&
            advancedStatus != FeatureStatus.AVAILABLE
        ) {
            downloadSingleModeModel(bcp47LocaleTag, SpeechRecognizerOptions.Mode.MODE_ADVANCED, onEvent)
        } else {
            true
        }
        val resolvedMode = if (targetMode == SpeechRecognizerOptions.Mode.MODE_ADVANCED && advancedDownloaded) {
            cachedAdvancedFeatureAvailable = true
            SpeechRecognizerOptions.Mode.MODE_ADVANCED
        } else {
            SpeechRecognizerOptions.Mode.MODE_BASIC
        }
        cachedAvailableModeByTag[normalizeTagKey(bcp47LocaleTag)] = resolvedMode
        onEvent(RecognitionModelDownloadEvent.SUCCESS)
        return true
    }

    private suspend fun downloadSingleModeModel(
        bcp47LocaleTag: String,
        mode: Int,
        onEvent: (RecognitionModelDownloadEvent) -> Unit,
    ): Boolean {
        val recognizer = createRecognizer(bcp47LocaleTag, mode) ?: run {
            onEvent(RecognitionModelDownloadEvent.FAILED)
            return false
        }
        return try {
            var completed = false
            var failed = false
            withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                recognizer.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            onEvent(RecognitionModelDownloadEvent.SCHEDULED)
                        }

                        is DownloadStatus.DownloadProgress -> {
                            onEvent(RecognitionModelDownloadEvent.PROGRESS)
                        }

                        is DownloadStatus.DownloadCompleted -> {
                            completed = true
                        }

                        is DownloadStatus.DownloadFailed -> {
                            failed = true
                            onEvent(RecognitionModelDownloadEvent.FAILED)
                        }
                    }
                }
            }
            if (!completed && !failed) {
                onEvent(RecognitionModelDownloadEvent.FAILED)
            }
            completed
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            onEvent(RecognitionModelDownloadEvent.FAILED)
            false
        } finally {
            runCatching { recognizer.close() }
        }
    }

    override suspend fun recognizePcm16Le(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        isProbe: Boolean,
    ): SpeechRecognitionAttemptResult? {
        if (pcm16LeBytes.isEmpty() || sampleRateHz <= 0 || !isDevicePotentiallySupported()) {
            return null
        }

        val modeAndStatus = checkFeatureStatusForTag(bcp47LocaleTag, useAvailableCache = true)
            ?: return SpeechRecognitionAttemptResult(
                text = "",
                errorCode = AndroidSpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                engineId = ML_KIT_GENAI_ENGINE_ID,
            )

        val (selectedMode, featureStatus) = modeAndStatus
        when (featureStatus) {
            FeatureStatus.UNAVAILABLE -> {
                return SpeechRecognitionAttemptResult(
                    text = "",
                    errorCode = AndroidSpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    engineId = ML_KIT_GENAI_ENGINE_ID,
                )
            }

            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING,
            -> {
                return SpeechRecognitionAttemptResult(
                    text = "",
                    errorCode = AndroidSpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    engineId = ML_KIT_GENAI_ENGINE_ID,
                )
            }
        }

        val primaryAttempt = recognizePcm16LeWithMode(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
            bcp47LocaleTag = bcp47LocaleTag,
            selectedMode = selectedMode,
            isProbe = isProbe,
        )
        if (
            selectedMode == SpeechRecognizerOptions.Mode.MODE_ADVANCED &&
            (primaryAttempt == null || (primaryAttempt.text.isBlank() && primaryAttempt.errorCode != null && primaryAttempt.errorCode != AndroidSpeechRecognizer.ERROR_NO_MATCH))
        ) {
            val basicAttempt = recognizePcm16LeWithMode(
                pcm16LeBytes = pcm16LeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = bcp47LocaleTag,
                selectedMode = SpeechRecognizerOptions.Mode.MODE_BASIC,
                isProbe = isProbe,
            )
            if (basicAttempt != null && (basicAttempt.text.isNotBlank() || basicAttempt.errorCode == AndroidSpeechRecognizer.ERROR_NO_MATCH)) {
                cachedAvailableModeByTag[normalizeTagKey(bcp47LocaleTag)] = SpeechRecognizerOptions.Mode.MODE_BASIC
                return basicAttempt
            }
        }
        return primaryAttempt
    }

    private suspend fun recognizePcm16LeWithMode(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        selectedMode: Int,
        isProbe: Boolean,
    ): SpeechRecognitionAttemptResult? {
        val leadingSilenceMs = if (isProbe) {
            ChunkPcmExtractor.PROBE_LEADING_SILENCE_MS
        } else {
            ChunkPcmExtractor.LEADING_SILENCE_MS
        }
        val trailingSilenceMs = if (isProbe) {
            ChunkPcmExtractor.PROBE_TRAILING_SILENCE_MS
        } else {
            ChunkPcmExtractor.TRAILING_SILENCE_MS
        }
        val audioPaceMs = if (isProbe) PROBE_PIPE_AUDIO_PACE_MS else PIPE_AUDIO_PACE_MS
        val trailingPaceMs = if (isProbe) PROBE_PIPE_TRAILING_SILENCE_PACE_MS else PIPE_TRAILING_SILENCE_PACE_MS

        val paddedBytes = ChunkPcmExtractor.padPcmWithSilence(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
            leadingSilenceMs = leadingSilenceMs,
            trailingSilenceMs = trailingSilenceMs,
        )

        val pipe = try {
            ParcelFileDescriptor.createPipe()
        } catch (_: Exception) {
            return null
        }
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val recognizer = createRecognizer(bcp47LocaleTag, selectedMode) ?: run {
            runCatching { readSide.close() }
            runCatching { writeSide.close() }
            return null
        }

        val listeningStarted = CompletableDeferred<Boolean>()
        return try {
            coroutineScope {
                val writerJob = launch {
                    if (!listeningStarted.await()) return@launch
                    runCatching {
                        val frameBytes = ((sampleRateHz * 2 * PIPE_FRAME_DURATION_MS) / 1_000)
                            .coerceAtLeast(MIN_PIPE_FRAME_BYTES)
                        val trailingSilenceBytes = ((sampleRateHz * trailingSilenceMs) / 1_000) * 2
                        val trailingStartOffset = (paddedBytes.size - trailingSilenceBytes).coerceAtLeast(0)

                        ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                            var offset = 0
                            while (offset < paddedBytes.size) {
                                val len = min(frameBytes, paddedBytes.size - offset)
                                output.write(paddedBytes, offset, len)
                                offset += len
                                if (offset <= trailingStartOffset) {
                                    delay(audioPaceMs)
                                } else {
                                    delay(trailingPaceMs)
                                }
                            }
                            output.flush()
                        }
                    }
                    runCatching { recognizer.stopRecognition() }
                }

                val finalSegments = mutableListOf<String>()
                var latestPartialText = ""
                var mappedErrorCode: Int? = null

                withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
                    val request: SpeechRecognizerRequest = speechRecognizerRequest {
                        audioSource = AudioSource.fromPfd(readSide)
                    }
                    listeningStarted.complete(true)
                    try {
                        recognizer.startRecognition(request).collect { response ->
                            when (response) {
                                is SpeechRecognizerResponse.PartialTextResponse -> {
                                    val trimmed = response.text.trim()
                                    if (trimmed.isNotEmpty()) {
                                        latestPartialText = trimmed
                                    }
                                }

                                is SpeechRecognizerResponse.FinalTextResponse -> {
                                    val trimmed = response.text.trim()
                                    if (trimmed.isNotEmpty()) {
                                        finalSegments.add(trimmed)
                                        latestPartialText = ""
                                    }
                                }

                                is SpeechRecognizerResponse.ErrorResponse -> {
                                    mappedErrorCode = mapGenAiErrorCode(response.e.errorCode)
                                }

                                is SpeechRecognizerResponse.CompletedResponse -> Unit
                            }
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (ge: GenAiException) {
                        mappedErrorCode = mapGenAiErrorCode(ge.errorCode)
                    } catch (_: Throwable) {
                        if (mappedErrorCode == null) {
                            mappedErrorCode = AndroidSpeechRecognizer.ERROR_CLIENT
                        }
                    }
                }

                if (!listeningStarted.isCompleted) {
                    listeningStarted.complete(false)
                }
                writerJob.cancel()

                if (
                    mappedErrorCode == AndroidSpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    mappedErrorCode == AndroidSpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                ) {
                    cachedAvailableModeByTag.remove(normalizeTagKey(bcp47LocaleTag))
                }

                val combinedFinal = finalSegments.joinToString(separator = " ").trim()
                val resolvedText = combinedFinal.ifEmpty { latestPartialText.trim() }
                when {
                    resolvedText.isNotEmpty() -> SpeechRecognitionAttemptResult(
                        text = resolvedText,
                        confidence = null,
                        errorCode = null,
                        engineId = ML_KIT_GENAI_ENGINE_ID,
                    )

                    mappedErrorCode != null -> SpeechRecognitionAttemptResult(
                        text = "",
                        confidence = null,
                        errorCode = mappedErrorCode,
                        engineId = ML_KIT_GENAI_ENGINE_ID,
                    )

                    else -> SpeechRecognitionAttemptResult(
                        text = "",
                        confidence = null,
                        errorCode = AndroidSpeechRecognizer.ERROR_NO_MATCH,
                        engineId = ML_KIT_GENAI_ENGINE_ID,
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { recognizer.stopRecognition() }
            runCatching { recognizer.close() }
            runCatching { readSide.close() }
            runCatching { writeSide.close() }
        }
    }

    /**
     * 指定された [bcp47LocaleTag] について、まず `MODE_BASIC` で `GoogleAsrService` のロケール対応可否と
     * 言語モデルのダウンロード状態を確認し、対応ロケールであれば `MODE_ADVANCED` の可否と組み合わせて
     * 採用するモードと `@FeatureStatus` のペアを返す。
     *
     * （ML Kit GenAI Speech Recognition 1.0.0-alpha1 の `MODE_ADVANCED.checkStatus()` はロケール引数を見ずに
     * AICore feature 0 の有無のみを返すため、`MODE_BASIC` を先に確認しないと非対応言語や未ダウンロード言語も
     * すべて `AVAILABLE` と誤判定されてしまう。）
     *
     * @param useAvailableCache `true` の場合、すでに `FeatureStatus.AVAILABLE` が確認済みのロケールは
     *   AICore IPC (`checkStatus()`) を省略してキャッシュから即座に返す。
     */
    private suspend fun checkFeatureStatusForTag(
        bcp47LocaleTag: String,
        useAvailableCache: Boolean = true,
    ): Pair<Int, Int>? {
        val tagKey = normalizeTagKey(bcp47LocaleTag)
        if (useAvailableCache) {
            val cachedMode = cachedAvailableModeByTag[tagKey]
            if (cachedMode != null) {
                return cachedMode to FeatureStatus.AVAILABLE
            }
        }
        val basicStatus = checkSingleModeStatus(bcp47LocaleTag, SpeechRecognizerOptions.Mode.MODE_BASIC)
        if (basicStatus == null || basicStatus == FeatureStatus.UNAVAILABLE) {
            cachedAvailableModeByTag.remove(tagKey)
            return null
        }

        val advancedStatus = checkDeviceAdvancedStatus(bcp47LocaleTag, useAvailableCache = useAvailableCache)
        if (advancedStatus == null || advancedStatus == FeatureStatus.UNAVAILABLE) {
            if (basicStatus == FeatureStatus.AVAILABLE) {
                cachedAvailableModeByTag[tagKey] = SpeechRecognizerOptions.Mode.MODE_BASIC
            } else {
                cachedAvailableModeByTag.remove(tagKey)
            }
            return SpeechRecognizerOptions.Mode.MODE_BASIC to basicStatus
        }

        val combinedStatus = combineFeatureStatuses(basicStatus, advancedStatus)
        if (combinedStatus == FeatureStatus.AVAILABLE) {
            cachedAvailableModeByTag[tagKey] = SpeechRecognizerOptions.Mode.MODE_ADVANCED
        } else {
            cachedAvailableModeByTag.remove(tagKey)
        }
        return SpeechRecognizerOptions.Mode.MODE_ADVANCED to combinedStatus
    }

    private suspend fun checkDeviceAdvancedStatus(
        bcp47LocaleTag: String,
        useAvailableCache: Boolean,
    ): Int? {
        if (useAvailableCache && cachedAdvancedFeatureAvailable) {
            return FeatureStatus.AVAILABLE
        }
        val status = checkSingleModeStatus(bcp47LocaleTag, SpeechRecognizerOptions.Mode.MODE_ADVANCED)
        if (status == FeatureStatus.AVAILABLE) {
            cachedAdvancedFeatureAvailable = true
        }
        return status
    }

    private fun combineFeatureStatuses(basicStatus: Int, advancedStatus: Int): Int = when {
        basicStatus == FeatureStatus.UNAVAILABLE || advancedStatus == FeatureStatus.UNAVAILABLE ->
            FeatureStatus.UNAVAILABLE

        basicStatus == FeatureStatus.AVAILABLE && advancedStatus == FeatureStatus.AVAILABLE ->
            FeatureStatus.AVAILABLE

        basicStatus == FeatureStatus.DOWNLOADING || advancedStatus == FeatureStatus.DOWNLOADING ->
            FeatureStatus.DOWNLOADING

        else ->
            FeatureStatus.DOWNLOADABLE
    }

    private suspend fun checkSingleModeStatus(bcp47LocaleTag: String, mode: Int): Int? {
        val recognizer = createRecognizer(bcp47LocaleTag, mode) ?: return null
        return try {
            withTimeoutOrNull(STATUS_CHECK_TIMEOUT_MS) {
                recognizer.checkStatus()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private fun createRecognizer(bcp47LocaleTag: String, mode: Int): SpeechRecognizer? = runCatching {
        val locale = Locale.forLanguageTag(bcp47LocaleTag.replace('_', '-'))
        val options = speechRecognizerOptions {
            this.locale = locale
            this.preferredMode = mode
        }
        SpeechRecognition.getClient(options)
    }.getOrNull()

    companion object {
        val ML_KIT_GENAI_ENGINE_ID: EngineId = EngineId("mlkit-genai-speech")

        private const val STATUS_CHECK_TIMEOUT_MS: Long = 4_000L
        private const val RECOGNITION_TIMEOUT_MS: Long = 10_000L
        private const val DOWNLOAD_TIMEOUT_MS: Long = 180_000L
        private const val PIPE_FRAME_DURATION_MS: Int = 20
        private const val MIN_PIPE_FRAME_BYTES: Int = 640
        private const val PIPE_AUDIO_PACE_MS: Long = 4L
        private const val PIPE_TRAILING_SILENCE_PACE_MS: Long = 15L
        private const val PROBE_PIPE_AUDIO_PACE_MS: Long = 2L
        private const val PROBE_PIPE_TRAILING_SILENCE_PACE_MS: Long = 8L

        internal fun featureStatusToModelSupport(status: Int): RecognitionModelSupport = when (status) {
            FeatureStatus.AVAILABLE -> RecognitionModelSupport.INSTALLED
            FeatureStatus.DOWNLOADABLE -> RecognitionModelSupport.SUPPORTED_NOT_INSTALLED
            FeatureStatus.DOWNLOADING -> RecognitionModelSupport.PENDING
            else -> RecognitionModelSupport.UNSUPPORTED
        }

        internal fun mapGenAiErrorCode(errorCode: Int): Int = when (errorCode) {
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
            -> AndroidSpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED

            GenAiException.ErrorCode.BUSY -> AndroidSpeechRecognizer.ERROR_RECOGNIZER_BUSY

            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED,
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED,
            -> AndroidSpeechRecognizer.ERROR_CLIENT

            else -> AndroidSpeechRecognizer.ERROR_NO_MATCH
        }
    }
}
