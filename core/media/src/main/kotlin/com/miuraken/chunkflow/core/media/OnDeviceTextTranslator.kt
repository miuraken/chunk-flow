package com.miuraken.chunkflow.core.media

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.miuraken.chunkflow.core.model.LanguageTag
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 端末内（オンデバイス）でテキストを翻訳するインターフェース。
 */
public interface OnDeviceTextTranslator {
    /**
     * 指定した翻訳元言語と翻訳先言語のペアがオンデバイス翻訳に対応しているかを返す。
     */
    public fun isSupported(sourceLanguage: LanguageTag?, targetLanguage: LanguageTag?): Boolean

    /**
     * [text] を [sourceLanguage] から [targetLanguage] へ翻訳して返す。
     * 未対応の言語ペア・空文字列・エラー時は `null` を返す。
     */
    public suspend fun translate(
        text: String,
        sourceLanguage: LanguageTag,
        targetLanguage: LanguageTag,
    ): String?
}

/**
 * ML Kit On-device Translation を用いた [OnDeviceTextTranslator] の実装。
 */
public class MlKitOnDeviceTextTranslator @Inject constructor() : OnDeviceTextTranslator {
    private val mutex = Mutex()
    private var cachedPair: Pair<String, String>? = null
    private var cachedTranslator: Translator? = null

    override fun isSupported(sourceLanguage: LanguageTag?, targetLanguage: LanguageTag?): Boolean {
        val srcCode = normalizeMlKitTranslateLanguage(sourceLanguage) ?: return false
        val dstCode = normalizeMlKitTranslateLanguage(targetLanguage) ?: return false
        return srcCode != dstCode
    }

    override suspend fun translate(
        text: String,
        sourceLanguage: LanguageTag,
        targetLanguage: LanguageTag,
    ): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val srcCode = normalizeMlKitTranslateLanguage(sourceLanguage) ?: return null
        val dstCode = normalizeMlKitTranslateLanguage(targetLanguage) ?: return null
        if (srcCode == dstCode) return null

        return mutex.withLock {
            try {
                val translator = getOrCreateTranslator(srcCode, dstCode)
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).awaitTask()
                val translated = translator.translate(trimmed).awaitTask().trim()
                translated.takeIf { it.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun getOrCreateTranslator(srcCode: String, dstCode: String): Translator {
        val pair = srcCode to dstCode
        val existing = cachedTranslator
        if (cachedPair == pair && existing != null) {
            return existing
        }
        existing?.close()
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(srcCode)
            .setTargetLanguage(dstCode)
            .build()
        return Translation.getClient(options).also { client ->
            cachedPair = pair
            cachedTranslator = client
        }
    }
}

/**
 * [LanguageTag] を ML Kit の [TranslateLanguage] コードに正規化する。
 * 未対応の言語や空・`"und"` の場合は `null` を返す。
 */
internal fun normalizeMlKitTranslateLanguage(tag: LanguageTag?): String? {
    val raw = tag?.value?.trim()?.lowercase()?.replace('_', '-').orEmpty()
    if (raw.isEmpty() || raw == "und") return null
    val normalized = when {
        raw == "yue" || raw.startsWith("yue-") || raw == "zh-yue" || raw == "zh-hk" || raw.startsWith("zh-hk-") -> "yue"
        raw == "cmn" || raw.startsWith("cmn-") || raw == "zh" || raw.startsWith("zh-") -> "zh"
        else -> raw.substringBefore('-')
    }
    return TranslateLanguage.fromLanguageTag(normalized)
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { error ->
        if (cont.isActive) cont.resumeWithException(error)
    }
    addOnCanceledListener {
        if (cont.isActive) cont.cancel()
    }
}
