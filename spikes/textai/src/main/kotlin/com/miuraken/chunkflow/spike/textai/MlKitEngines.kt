package com.miuraken.chunkflow.spike.textai

import android.content.Context
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.genai.common.DownloadCallback
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.proofreading.Proofreader
import com.google.mlkit.genai.proofreading.ProofreaderOptions
import com.google.mlkit.genai.proofreading.Proofreading
import com.google.mlkit.genai.proofreading.ProofreadingRequest
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** 失敗の分類（CSV の `result` 列と `errorCode` 列）。 */
internal data class Failure(val kind: ResultKind, val code: Int?)

/**
 * ML Kit の例外を結果の種類に分ける。GenAI の `BUSY`・回数の上限・`BACKGROUND_USE_BLOCKED` を区別する（計画の 4 の 3）。
 *
 * 例外のメッセージは入力の文を含むかもしれないので、記録しない（コードだけを残す）。
 */
internal fun classifyFailure(error: Throwable): Failure {
    var current: Throwable? = error
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        when (current) {
            is GenAiException -> return Failure(genAiKind(current.errorCode), current.errorCode)
            is MlKitException -> return Failure(mlKitKind(current.errorCode), current.errorCode)
        }
        current = current.cause
        depth++
    }
    return Failure(ResultKind.ERROR, null)
}

private const val MAX_CAUSE_DEPTH = 8

private fun genAiKind(code: Int): ResultKind = when (code) {
    GenAiException.ErrorCode.BUSY -> ResultKind.BUSY
    GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> ResultKind.QUOTA_EXCEEDED
    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> ResultKind.BACKGROUND_USE_BLOCKED
    GenAiException.ErrorCode.NOT_AVAILABLE -> ResultKind.NOT_AVAILABLE
    GenAiException.ErrorCode.REQUEST_TOO_LARGE -> ResultKind.REQUEST_TOO_LARGE
    GenAiException.ErrorCode.NOT_SUPPORTED -> ResultKind.UNSUPPORTED
    GenAiException.ErrorCode.CANCELLED -> ResultKind.CANCELLED
    else -> ResultKind.ERROR
}

private fun mlKitKind(code: Int): ResultKind = when (code) {
    MlKitException.NETWORK_ISSUE -> ResultKind.NETWORK_ERROR
    MlKitException.NOT_FOUND, MlKitException.FAILED_PRECONDITION -> ResultKind.NOT_READY
    MlKitException.UNAVAILABLE -> ResultKind.NOT_AVAILABLE
    MlKitException.CANCELLED -> ResultKind.CANCELLED
    MlKitException.UNSUPPORTED -> ResultKind.UNSUPPORTED
    else -> ResultKind.ERROR
}

/** GenAI のモデルのダウンロードの進み具合。 */
internal sealed interface DownloadEvent {
    data class Started(val bytesToDownload: Long) : DownloadEvent
    data class Progress(val bytesDownloaded: Long) : DownloadEvent
}

/** 方式 A の翻訳モデルのダウンロードの記録。 */
internal data class TranslateDownload(val alreadyDownloaded: Boolean, val elapsedMs: Long, val bytesDelta: Long)

/**
 * 方式 A: ML Kit Translation API。言語の組ごとに 1 つの [Translator] を持つ。
 *
 * モデル（言語ごとに約 30MB）はアプリ自身がダウンロードする。大きさは API から取れないので、
 * ダウンロードの前後のアプリのデータ領域の大きさの差で見積もる。
 */
internal class TranslationEngine(
    private val appContext: Context,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) : Closeable {
    private var client: Translator? = null
    private var clientPair: Pair<String, String>? = null

    /** この言語の組で、まだ一度も翻訳に成功していなければ `false`（初回の時間はモデルの読み込みを含む）。 */
    var warm: Boolean = false
        private set

    /** 2 つの言語に対応していて、異なる言語か。広東語（yue）などは対応していない。 */
    fun isSupported(source: String, target: String): Boolean = source != target &&
        TranslateLanguage.fromLanguageTag(source) != null &&
        TranslateLanguage.fromLanguageTag(target) != null

    suspend fun areModelsDownloaded(source: String, target: String): Boolean {
        val manager = RemoteModelManager.getInstance()
        for (language in listOf(source, target).distinct()) {
            val code = TranslateLanguage.fromLanguageTag(language) ?: return false
            val downloaded: Boolean = manager.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).awaitResult()
            if (!downloaded) return false
        }
        return true
    }

    suspend fun download(source: String, target: String): TranslateDownload {
        val alreadyDownloaded = areModelsDownloaded(source, target)
        val sizeBefore = withContext(ioDispatcher) { directorySize(appContext.dataDir) }
        val started = clock()
        clientFor(source, target).downloadModelIfNeeded().awaitResult()
        val elapsed = clock() - started
        val sizeAfter = withContext(ioDispatcher) { directorySize(appContext.dataDir) }
        return TranslateDownload(alreadyDownloaded, elapsed, sizeAfter - sizeBefore)
    }

    suspend fun translate(source: String, target: String, text: String): String? {
        val translated: String? = clientFor(source, target).translate(text).awaitResult()
        warm = true
        return translated
    }

    private fun clientFor(source: String, target: String): Translator {
        val pair = source to target
        val existing = client
        if (existing != null && clientPair == pair) return existing
        existing?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.fromLanguageTag(source) ?: source)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(target) ?: target)
            .build()
        val created = Translation.getClient(options)
        client = created
        clientPair = pair
        warm = false
        return created
    }

    override fun close() {
        client?.close()
        client = null
        clientPair = null
    }

    private fun directorySize(directory: File): Long = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/**
 * 方式 C: ML Kit GenAI Proofreading API（入力の種類 = VOICE）。言語ごとに 1 つの [Proofreader] を持つ。
 *
 * 対応する言語は英語・日本語・韓国語・ドイツ語・フランス語・イタリア語・スペイン語だけ。
 */
internal class ProofreadEngine(private val appContext: Context) : Closeable {
    private var client: Proofreader? = null
    private var clientLanguage: Int? = null

    /** この言語で、まだ一度も校正に成功していなければ `false`。 */
    var warm: Boolean = false
        private set

    /** 学習言語に対応する Proofreading API の言語。対応していなければ `null`。 */
    fun languageOf(language: String): Int? = when (primaryLanguage(language)) {
        "en" -> ProofreaderOptions.Language.ENGLISH
        "ja" -> ProofreaderOptions.Language.JAPANESE
        "ko" -> ProofreaderOptions.Language.KOREAN
        "de" -> ProofreaderOptions.Language.GERMAN
        "fr" -> ProofreaderOptions.Language.FRENCH
        "it" -> ProofreaderOptions.Language.ITALIAN
        "es" -> ProofreaderOptions.Language.SPANISH
        else -> null
    }

    suspend fun status(language: Int): Int = clientFor(language).checkFeatureStatus().awaitResult()

    suspend fun modelName(language: Int): String? = clientFor(language).baseModelName.awaitResult()

    suspend fun download(language: Int, onEvent: (DownloadEvent) -> Unit) {
        val callback = object : DownloadCallback {
            override fun onDownloadStarted(bytesToDownload: Long) = onEvent(DownloadEvent.Started(bytesToDownload))

            override fun onDownloadProgress(totalBytesDownloaded: Long) = onEvent(DownloadEvent.Progress(totalBytesDownloaded))
        }
        clientFor(language).downloadFeature(callback).awaitResult()
    }

    /** 案の一覧（多くは 1 つ）。案がなければ空。 */
    suspend fun proofread(language: Int, text: String): List<String> {
        val result = clientFor(language).runInference(ProofreadingRequest.builder(text).build()).awaitResult()
        warm = true
        return result.results.map { it.text }
    }

    private fun clientFor(language: Int): Proofreader {
        val existing = client
        if (existing != null && clientLanguage == language) return existing
        existing?.close()
        val options = ProofreaderOptions.builder(appContext)
            .setInputType(ProofreaderOptions.InputType.VOICE)
            .setLanguage(language)
            .build()
        val created = Proofreading.getClient(options)
        client = created
        clientLanguage = language
        warm = false
        return created
    }

    override fun close() {
        client?.close()
        client = null
        clientLanguage = null
    }
}

/** 方式 B・D の出力。 */
internal data class PromptOutput(val text: String?, val finishReason: String?)

/**
 * 方式 B・D: ML Kit GenAI Prompt API（Gemini Nano）。B と D は同じモデルを使う。
 *
 * 出力のばらつきを抑えるため、温度を低くし、シードを固定する。
 */
internal class PromptEngine : Closeable {
    private val model: GenerativeModel by lazy { Generation.getClient() }
    private var created = false

    /** まだ一度も生成に成功していなければ `false`（B と D で共通）。 */
    var warm: Boolean = false
        private set

    suspend fun status(): Int {
        created = true
        return model.checkStatus()
    }

    suspend fun modelName(): String {
        created = true
        return model.getBaseModelName()
    }

    fun download(): Flow<DownloadStatus> {
        created = true
        return model.download()
    }

    suspend fun generate(prompt: String, maxOutputTokens: Int): PromptOutput {
        created = true
        val request = generateContentRequest(TextPart(prompt)) {
            temperature = TEMPERATURE
            topK = TOP_K
            seed = SEED
            candidateCount = 1
            this.maxOutputTokens = maxOutputTokens
        }
        val response = model.generateContent(request)
        warm = true
        val candidate = response.candidates.firstOrNull()
        return PromptOutput(text = candidate?.text, finishReason = finishReasonName(candidate?.finishReason))
    }

    override fun close() {
        if (created) model.close()
    }

    private fun finishReasonName(reason: Int?): String? = when (reason) {
        null -> null
        Candidate.FinishReason.STOP -> "STOP"
        Candidate.FinishReason.MAX_TOKENS -> "MAX_TOKENS"
        Candidate.FinishReason.OTHER -> "OTHER"
        else -> reason.toString()
    }

    private companion object {
        const val TEMPERATURE = 0.2f
        const val TOP_K = 16
        const val SEED = 0
    }
}
