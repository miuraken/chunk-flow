package com.miuraken.chunkflow.core.media

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.OnDeviceRecognitionModelState
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 端末内音声認識による 1 チャンク分の認識結果（F6.2）。
 *
 * 選ばれた言語の音声認識モデルが端末にない（未ダウンロードなど）場合は、本文を空にして
 * 言語判定（Whisper Tiny）の結果だけを返すことがある（[isLanguageOnly]）。
 *
 * @property text 認識されたテキスト（前後空白除去済み）。言語判定のみの結果では空文字。
 * @property language 検出または指定された言語タグ（不明な場合は `null`）。
 * @property confidence 認識信頼度（0.0..1.0、取得できない場合は `null`）。言語判定のみの結果では言語判定の確率。
 * @property engineId 使用した端末内認識エンジンの識別子。
 */
data class OnDeviceChunkTranscriptionResult(
    val text: String,
    val language: LanguageTag? = null,
    val confidence: Float? = null,
    val engineId: EngineId = DEFAULT_ON_DEVICE_ENGINE_ID,
) {
    /** 本文がなく、言語の判定だけを行った結果かどうか（音声認識モデルが使えなかった場合）。 */
    val isLanguageOnly: Boolean
        get() = text.isBlank() && language != null

    companion object {
        val DEFAULT_ON_DEVICE_ENGINE_ID: EngineId = EngineId("android-on-device-speech")

        /** 音声認識モデルが使えず、Whisper Tiny の言語判定だけで言語を決めた結果のエンジン ID。 */
        val LANGUAGE_ID_ONLY_ENGINE_ID: EngineId = EngineId("whisper-tiny-lid")
    }
}

/**
 * チャンク単位（16kHz モノラル 16-bit PCM または音声ファイルの `[startMs, endMs]` 区間）で
 * 端末内音声認識を行うインターフェース（F6.2）。
 */
interface OnDeviceChunkTranscriber {
    /** 端末内音声認識が利用可能かどうかを返す。 */
    fun isAvailable(): Boolean

    /**
     * 端末内音声認識の言語モデルが使えない言語と、その理由（未ダウンロード・ダウンロード中・非対応など）。
     *
     * 認識を試みて問題がわかった言語だけを含み、問題のない言語は含まない。
     */
    val recognitionModelIssues: StateFlow<Map<LanguageTag, OnDeviceRecognitionModelState>>
        get() = NO_RECOGNITION_MODEL_ISSUES

    /**
     * チャンクの切り出し用に開いたままにしている音声ファイルなどの資源を解放する（プレイヤー画面を閉じたときなど）。
     *
     * 実行中の文字起こしは止めない（切り出しの途中なら、その切り出しの終わりに解放する）。次の切り出しでは開き直す。
     */
    fun releaseIdleResources() = Unit

    /**
     * [language] の端末内音声認識モデルのダウンロードを要求する。
     *
     * OS の `SpeechRecognizer` が対応している言語は OS にダウンロードを要求し、
     * OS 非対応言語（または `SHERPA_ONNX` 選択時）は `sherpa-onnx` ローカルモデルをダウンロードする。
     */
    suspend fun requestRecognitionModelDownload(language: LanguageTag): Boolean = false

    /**
     * [language] 向けに `sherpa-onnx` ローカル音声認識モデルをダウンロード・検証して有効化する（Stage 2）。
     */
    suspend fun downloadSherpaModel(language: LanguageTag): Boolean = false

    /**
     * [language] の `sherpa-onnx` ローカル音声認識モデルを削除する（Stage 2）。
     */
    suspend fun deleteSherpaModel(language: LanguageTag): Boolean = false

    /**
     * [recognitionModelIssues] に含まれる言語の言語モデルの状態を OS に問い合わせ直す。
     *
     * ダウンロードが終わっていれば、その言語を [recognitionModelIssues] から外す。
     */
    suspend fun refreshRecognitionModelIssues() = Unit

    /**
     * 指定された言語一覧 [languages] について、端末内音声認識モデルの利用可能状態と
     * 解決された BCP-47 ロケールタグ、および `sherpa-onnx` ローカルモデルの状態を問い合わせて返す（設定画面の「文字起こし設定」タブ用）。
     */
    suspend fun queryLanguageModelInfos(languages: List<LanguageTag>): List<OnDeviceLanguageModelInfo> = languages.map { OnDeviceLanguageModelInfo(language = it) }

    /**
     * 音声ファイル [audioFile] の `[startMs, endMs]` 区間を 16kHz モノラル 16-bit PCM に切り出して端末内音声認識を行う。
     *
     * 認識不能・非対応端末・エミュレータ・無音の場合はクラッシュせず `null` を返す。
     */
    suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair? = null,
    ): OnDeviceChunkTranscriptionResult?

    /**
     * 音声ファイル [audioFile] の `[startMs, endMs]` 区間を 16kHz モノラル 16-bit PCM に切り出して端末内音声認識を行う。
     *
     * @param forceRefresh `true` の場合は対象チャンクのキャッシュを破棄して強制的に再認識する。
     * @param highPriority `true` の場合はバックグラウンド先読みより優先して認識ロックを取得する。
     */
    suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        forceRefresh: Boolean,
        highPriority: Boolean,
    ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        languagePair = languagePair,
    )

    /**
     * 切り出し済みの 16kHz モノラル 16-bit リトルエンディアン PCM（[pcm16LeBytes]）に対して端末内音声認識を行う。
     */
    suspend fun transcribePcm16Le(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        languagePair: LanguagePair? = null,
    ): OnDeviceChunkTranscriptionResult? = null

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000

        private val NO_RECOGNITION_MODEL_ISSUES: StateFlow<Map<LanguageTag, OnDeviceRecognitionModelState>> =
            MutableStateFlow<Map<LanguageTag, OnDeviceRecognitionModelState>>(emptyMap()).asStateFlow()
    }
}

/**
 * 短い言語コード（`"ja"`, `"en"`, `"ko"` など）を Android `SpeechRecognizer`（SODA）が要求する
 * 地域コード付き BCP-47 ロケールタグ（`"ja-JP"`, `"en-US"`, `"ko-KR"` など）へ変換する。
 */
internal fun toBcp47LocaleTag(lang: String): String {
    val normalized = lang.trim().replace('_', '-')
    if (normalized.isEmpty()) return "en-US"
    if ('-' in normalized) {
        val localeTag = Locale.forLanguageTag(normalized).toLanguageTag()
        return if (localeTag.isNotBlank() && localeTag != "und") localeTag else normalized
    }
    return when (normalized.lowercase(Locale.ROOT)) {
        "ja" -> "ja-JP"

        "en" -> "en-US"

        "ko" -> "ko-KR"

        "zh" -> "zh-CN"

        "yue" -> "yue-HK"

        "hi" -> "hi-IN"

        "es" -> "es-ES"

        "fr" -> "fr-FR"

        "de" -> "de-DE"

        "it" -> "it-IT"

        "pt" -> "pt-BR"

        "ru" -> "ru-RU"

        "ar" -> "ar-SA"

        "th" -> "th-TH"

        "vi" -> "vi-VN"

        "id" -> "id-ID"

        "el" -> "el-GR"

        else -> {
            val localeTag = Locale.forLanguageTag(normalized).toLanguageTag()
            if (localeTag.isNotBlank() && localeTag != "und") localeTag else normalized
        }
    }
}

/**
 * 言語コード [lang] に対して Android `SpeechRecognizer`（SODA）へ順に試行する BCP-47 ロケールタグ候補一覧を返す。
 *
 * Pixel 等のオンデバイス音声認識（SODA）では、中国語（普通話・簡体字）が `"zh-CN"` ではなく `"cmn-Hans-CN"`、
 * 台湾華語（繁体字）が `"cmn-Hant-TW"`、広東語が `"yue-Hant-HK"` として登録されている端末があるため、
 * 第一候補が `ERROR_LANGUAGE_NOT_SUPPORTED` となった場合にこれらの同義タグへフォールバックできるようにする。
 */
internal fun toBcp47CandidateLocaleTags(lang: String): List<String> {
    val primary = toBcp47LocaleTag(lang)
    val normalized = lang.trim().replace('_', '-').lowercase(Locale.ROOT)
    val aliases = when (normalized) {
        "zh",
        "zh-cn",
        "zh-hans",
        "zh-hans-cn",
        "cmn",
        "cmn-hans",
        "cmn-hans-cn",
        "cmn-cn",
        -> listOf("zh-CN", "cmn-Hans-CN", "zh-Hans-CN", "cmn-CN", "zh")

        "zh-sg",
        "zh-hans-sg",
        "cmn-hans-sg",
        -> listOf("zh-SG", "cmn-Hans-SG", "cmn-Hans-CN", "zh-CN", "zh-Hans-CN")

        "zh-tw",
        "zh-hant",
        "zh-hant-tw",
        "cmn-hant",
        "cmn-hant-tw",
        "cmn-tw",
        -> listOf("zh-TW", "cmn-Hant-TW", "zh-Hant-TW", "cmn-TW")

        "yue",
        "yue-hk",
        "yue-hant",
        "yue-hant-hk",
        "zh-hk",
        "zh-hant-hk",
        -> listOf("yue-HK", "yue-Hant-HK", "zh-HK", "zh-Hant-HK", "cmn-Hant-HK", "yue")

        "ja", "ja-jp" -> listOf("ja-JP", "ja")

        "en", "en-us" -> listOf("en-US", "en-GB", "en")

        "ko", "ko-kr" -> listOf("ko-KR", "ko")

        "es", "es-es" -> listOf("es-ES", "es-US", "es-MX", "es")

        "fr", "fr-fr" -> listOf("fr-FR", "fr-CA", "fr")

        "de", "de-de" -> listOf("de-DE", "de")

        "it", "it-it" -> listOf("it-IT", "it")

        "pt", "pt-br" -> listOf("pt-BR", "pt-PT", "pt")

        "ru", "ru-ru" -> listOf("ru-RU", "ru")

        "hi", "hi-in" -> listOf("hi-IN", "hi")

        "ar", "ar-sa" -> listOf("ar-SA", "ar-EG", "ar-AE", "ar")

        "th", "th-th" -> listOf("th-TH", "th")

        "vi", "vi-vn" -> listOf("vi-VN", "vi")

        "id", "id-id" -> listOf("id-ID", "id")

        "el", "el-gr" -> listOf("el-GR", "el")

        else -> {
            val baseLang = normalized.substringBefore('-')
            val defaultForBase = if (baseLang.isNotEmpty() && baseLang != normalized) {
                toBcp47LocaleTag(baseLang)
            } else {
                null
            }
            listOfNotNull(primary, defaultForBase, baseLang.takeIf { it.isNotEmpty() })
        }
    }
    return (listOf(primary) + aliases).distinct()
}

/**
 * チャンクの先頭発話プローブ切り出し結果（F6.2）。
 *
 * @property probePcm16LeBytes 発話開始位置から最大約 2 秒間を切り出した 16-bit リトルエンディアン PCM。
 * @property onsetByteOffset 元のチャンク PCM 内での発話開始バイトオフセット（偶数）。
 * @property isFullChunkCovered プローブ区間がチャンクの残り全体をカバーしている場合 `true`。
 */
internal data class SpeechProbeSlice(
    val probePcm16LeBytes: ByteArray,
    val onsetByteOffset: Int,
    val isFullChunkCovered: Boolean,
)

/**
 * 音声ファイルから指定したチャンク区間 `[startMs, endMs]` の 16kHz モノラル 16-bit PCM を取り出すヘルパー（F6.2）。
 *
 * - `endSample` に到達した時点で即座にデコードを打ち切り、30 分等の長時間ファイル全体をデコードしない。
 * - [MediaCodecAudioStreamDecoder] 使用時は、`startMs` の 500ms 手前まで圧縮データのまま読み飛ばしてからデコードし、
 *   同じファイルの抽出器を使い回す（[MediaCodecAudioStreamDecoder.decodeFromPosition]）。抽出済みチャンクは LRU キャッシュする。
 */
object ChunkPcmExtractor {

    private class ChunkExtractionCompletedException : CancellationException("Chunk PCM extraction reached target endSample")

    private data class ChunkCacheKey(
        val absolutePath: String,
        val lastModified: Long,
        val fileLength: Long,
        val decoderIdentity: Int,
        val startMs: Long,
        val endMs: Long,
        val sampleRateHz: Int,
    )

    private val cacheLock = Any()
    private val recentChunkCache = object : LinkedHashMap<ChunkCacheKey, ByteArray>(MAX_CACHE_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ChunkCacheKey, ByteArray>?): Boolean = size > MAX_CACHE_ENTRIES
    }

    /**
     * 正規化 Float サンプル配列（`-1.0..1.0`）を 16-bit 符号付き整数（[ShortArray]）へ変換する。
     */
    fun floatToPcm16Shorts(samples: FloatArray, count: Int = samples.size): ShortArray {
        val safeCount = count.coerceIn(0, samples.size)
        val out = ShortArray(safeCount)
        for (i in 0 until safeCount) {
            val clamped = samples[i].coerceIn(-1.0f, 1.0f)
            val scaled = (clamped * Short.MAX_VALUE.toFloat()).roundToInt()
            out[i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    /**
     * 16-bit 符号付き整数配列（[ShortArray]）をリトルエンディアン [ByteArray] へ変換する。
     */
    fun shortsToPcm16LeBytes(shorts: ShortArray, count: Int = shorts.size): ByteArray {
        val safeCount = count.coerceIn(0, shorts.size)
        val buffer = ByteBuffer.allocate(safeCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until safeCount) {
            buffer.putShort(shorts[i])
        }
        return buffer.array()
    }

    /**
     * 16-bit リトルエンディアン PCM（[pcm16LeBytes]）を [ShortArray] へ変換する。
     */
    fun pcm16LeBytesToShorts(pcm16LeBytes: ByteArray): ShortArray {
        val shortCount = pcm16LeBytes.size / 2
        val shorts = ShortArray(shortCount)
        val byteBuffer = ByteBuffer.wrap(pcm16LeBytes, 0, shortCount * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortCount) {
            shorts[i] = byteBuffer.short
        }
        return shorts
    }

    /**
     * 16-bit リトルエンディアン PCM（[pcm16LeBytes]）の先頭と末尾に無音（0 バイト列）を付加する。
     */
    internal fun padPcmWithSilence(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
        leadingSilenceMs: Int = LEADING_SILENCE_MS,
        trailingSilenceMs: Int = TRAILING_SILENCE_MS,
    ): ByteArray {
        if (pcm16LeBytes.isEmpty() || sampleRateHz <= 0) return pcm16LeBytes
        val leadingBytes = ((sampleRateHz * leadingSilenceMs) / 1_000).coerceAtLeast(0) * 2
        val trailingBytes = ((sampleRateHz * trailingSilenceMs) / 1_000).coerceAtLeast(0) * 2
        val padded = ByteArray(leadingBytes + pcm16LeBytes.size + trailingBytes)
        System.arraycopy(pcm16LeBytes, 0, padded, leadingBytes, pcm16LeBytes.size)
        return padded
    }

    /**
     * チャンク PCM（[pcm16LeBytes]）から発話開始位置を検出し、先頭約 [probeDurationMs]（既定 2,000ms）の
     * 言語判定プローブ用 PCM スライスを切り出す。
     */
    internal fun extractLeadingSpeechProbePcm(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
        probeDurationMs: Long = PROBE_DURATION_MS,
        fullCoverageThresholdMs: Long = PROBE_FULL_COVERAGE_THRESHOLD_MS,
    ): SpeechProbeSlice {
        val totalBytes = pcm16LeBytes.size and 1.inv()
        if (totalBytes <= 0 || sampleRateHz <= 0) {
            return SpeechProbeSlice(
                probePcm16LeBytes = pcm16LeBytes,
                onsetByteOffset = 0,
                isFullChunkCovered = true,
            )
        }

        val frameSamples = ((sampleRateHz * PROBE_RMS_FRAME_MS) / 1_000).coerceAtLeast(1)
        val frameBytes = frameSamples * 2
        val preRollBytes = (((sampleRateHz * PROBE_ONSET_PRE_ROLL_MS) / 1_000) * 2).coerceAtLeast(0)

        var onsetByteOffset = 0
        var offset = 0
        while (offset + 1 < totalBytes) {
            val frameEnd = min(offset + frameBytes, totalBytes)
            var sumSq = 0.0
            var count = 0
            var idx = offset
            while (idx + 1 < frameEnd) {
                val lo = pcm16LeBytes[idx].toInt() and 0xFF
                val hi = pcm16LeBytes[idx + 1].toInt()
                val sample = ((hi shl 8) or lo).toShort().toDouble()
                sumSq += sample * sample
                count += 1
                idx += 2
            }
            val rms = if (count > 0) sqrt(sumSq / count) else 0.0
            if (rms >= SPEECH_ONSET_RMS_THRESHOLD) {
                onsetByteOffset = (offset - preRollBytes).coerceAtLeast(0) and 1.inv()
                break
            }
            offset += frameBytes
        }

        val remainingBytes = (totalBytes - onsetByteOffset).coerceAtLeast(0)
        val maxProbeBytes = (((sampleRateHz.toLong() * probeDurationMs) / 1_000L) * 2L)
            .coerceAtLeast(2L)
            .toInt() and 1.inv()
        val fullCoverageBytes = (((sampleRateHz.toLong() * fullCoverageThresholdMs) / 1_000L) * 2L)
            .coerceAtLeast(maxProbeBytes.toLong())
            .toInt() and 1.inv()

        val isFullChunkCovered = remainingBytes <= fullCoverageBytes
        val sliceBytes = min(remainingBytes, maxProbeBytes)
        val probeBytes = if (onsetByteOffset == 0 && sliceBytes == pcm16LeBytes.size) {
            pcm16LeBytes
        } else {
            pcm16LeBytes.copyOfRange(onsetByteOffset, onsetByteOffset + sliceBytes)
        }

        return SpeechProbeSlice(
            probePcm16LeBytes = probeBytes,
            onsetByteOffset = onsetByteOffset,
            isFullChunkCovered = isFullChunkCovered,
        )
    }

    /**
     * [decoder] を用いて [audioFile] から `[startMs, endMs]` 区間のモノラル 16-bit PCM（[ShortArray]）を抽出する。
     */
    suspend fun extractChunkPcm16Shorts(
        decoder: AudioStreamDecoder,
        audioFile: File,
        startMs: Long,
        endMs: Long,
        sampleRateHz: Int = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
    ): ShortArray? {
        val bytes = extractChunkPcm16LeBytes(
            decoder = decoder,
            audioFile = audioFile,
            startMs = startMs,
            endMs = endMs,
            sampleRateHz = sampleRateHz,
        ) ?: return null
        val shortCount = bytes.size / 2
        val shorts = ShortArray(shortCount)
        val byteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until shortCount) {
            shorts[i] = byteBuffer.short
        }
        return shorts
    }

    /**
     * [decoder] を用いて [audioFile] から `[startMs, endMs]` 区間のモノラル 16-bit リトルエンディアン PCM（[ByteArray]）を抽出する。
     *
     * `endMs` に達した時点で即座にデコードを終了し、ファイル全体のデコードを行わない。
     */
    suspend fun extractChunkPcm16LeBytes(
        decoder: AudioStreamDecoder,
        audioFile: File,
        startMs: Long,
        endMs: Long,
        sampleRateHz: Int = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
    ): ByteArray? {
        if (startMs < 0L || endMs <= startMs || sampleRateHz <= 0 || !audioFile.isFile) {
            return null
        }
        val startSample = (startMs * sampleRateHz) / 1_000L
        val endSample = (endMs * sampleRateHz) / 1_000L
        if (endSample <= startSample) return null

        val cacheKey = ChunkCacheKey(
            absolutePath = audioFile.absolutePath,
            lastModified = audioFile.lastModified(),
            fileLength = audioFile.length(),
            decoderIdentity = System.identityHashCode(decoder),
            startMs = startMs,
            endMs = endMs,
            sampleRateHz = sampleRateHz,
        )
        synchronized(cacheLock) {
            recentChunkCache[cacheKey]?.let { cached ->
                return cached.copyOf()
            }
        }

        // 出力の大きさは最初から決まるので、1 バイトずつ書く ByteArrayOutputStream（write は同期化されている）を使わず配列に直接書く
        val expectedBytes = ((endSample - startSample) * 2L).coerceAtMost(MAX_CHUNK_PCM_BYTES.toLong()).toInt()
        val out = ByteArray(expectedBytes)
        var writtenBytes = 0
        var currentSampleIndex = 0L

        val sink = SampleSink { samples, count ->
            val blockStart = currentSampleIndex
            val blockEnd = currentSampleIndex + count
            currentSampleIndex = blockEnd

            val sliceStart = max(startSample, blockStart)
            val sliceEnd = min(endSample, blockEnd)
            if (sliceStart < sliceEnd) {
                var idx = (sliceStart - blockStart).toInt()
                val localTo = (sliceEnd - blockStart).toInt()
                while (idx < localTo && writtenBytes + 2 <= out.size) {
                    val clamped = samples[idx].coerceIn(-1.0f, 1.0f)
                    val sampleInt = (clamped * Short.MAX_VALUE.toFloat())
                        .roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out[writtenBytes] = (sampleInt and 0xFF).toByte()
                    out[writtenBytes + 1] = ((sampleInt ushr 8) and 0xFF).toByte()
                    writtenBytes += 2
                    idx++
                }
            }
            if (currentSampleIndex >= endSample || writtenBytes + 2 > out.size) {
                throw ChunkExtractionCompletedException()
            }
        }

        var stoppedEarly = false
        val result = try {
            if (decoder is MediaCodecAudioStreamDecoder) {
                decoder.decodeFromPosition(
                    file = audioFile,
                    startPositionMs = startMs,
                    sink = sink,
                    outputSampleRate = sampleRateHz,
                    onSeekLandingUs = { landedUs ->
                        // 最初にデコーダへ渡すサンプルの時刻を、出力（sampleRateHz）のサンプル位置に直す
                        currentSampleIndex = Math.floorDiv(landedUs * sampleRateHz, 1_000_000L)
                    },
                )
            } else {
                decoder.decode(
                    file = audioFile,
                    outputSampleRate = sampleRateHz,
                    sink = sink,
                )
            }
        } catch (_: ChunkExtractionCompletedException) {
            stoppedEarly = true
            null
        }

        val extracted = if ((stoppedEarly || result is AudioDecodeResult.Success) && writtenBytes > 0) {
            if (writtenBytes == out.size) out else out.copyOf(writtenBytes)
        } else {
            null
        }

        if (extracted != null && extracted.size <= MAX_CACHED_ENTRY_BYTES) {
            synchronized(cacheLock) {
                recentChunkCache[cacheKey] = extracted.copyOf()
            }
        }
        return extracted
    }

    internal const val LEADING_SILENCE_MS: Int = 150
    internal const val TRAILING_SILENCE_MS: Int = 600
    internal const val PROBE_LEADING_SILENCE_MS: Int = 120
    internal const val PROBE_TRAILING_SILENCE_MS: Int = 400
    internal const val PROBE_DURATION_MS: Long = 2_000L
    internal const val PROBE_FULL_COVERAGE_THRESHOLD_MS: Long = 2_400L
    private const val PROBE_RMS_FRAME_MS: Int = 20
    private const val PROBE_ONSET_PRE_ROLL_MS: Int = 60
    private const val SPEECH_ONSET_RMS_THRESHOLD: Double = 160.0
    private const val MAX_CHUNK_PCM_BYTES: Int = 4 * 1024 * 1024
    private const val MAX_CACHED_ENTRY_BYTES: Int = 512 * 1024
    private const val MAX_CACHE_ENTRIES: Int = 8
}

/**
 * 1 回の `SpeechRecognizer` 呼び出し試行の結果（内部・テスト用）。
 *
 * @property errorCode 認識できなかったときの `SpeechRecognizer` のエラーコード（言語モデルの未ダウンロードなどの判定に使う）。
 * @property engineId 実際に認識を実行したエンジンの識別子。
 */
internal data class SpeechRecognitionAttemptResult(
    val text: String,
    val confidence: Float? = null,
    val errorCode: Int? = null,
    val engineId: EngineId = OnDeviceChunkTranscriptionResult.DEFAULT_ON_DEVICE_ENGINE_ID,
) {
    /** 言語モデルが使えない（未ダウンロード・非対応）ために認識できなかったかどうか。 */
    val isLanguageModelUnavailable: Boolean
        get() = text.isBlank() &&
            (
                errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                    errorCode == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                )
}

/** 端末内音声認識の言語モデルのダウンロード要求の進み具合（内部・テスト用）。 */
internal enum class RecognitionModelDownloadEvent {
    /** ダウンロード中。 */
    PROGRESS,

    /** ダウンロードが予約された（Wi-Fi 接続時などに OS が行う。以後の通知はない）。 */
    SCHEDULED,

    /** ダウンロードが終わり、言語モデルを使えるようになった。 */
    SUCCESS,

    /** ダウンロードに失敗した。 */
    FAILED,
}

/** 端末内音声認識の言語モデルの対応状況（内部・テスト用）。 */
internal enum class RecognitionModelSupport {
    /** 端末にインストール済み。 */
    INSTALLED,

    /** ダウンロード中または予約済み。 */
    PENDING,

    /** 対応しているが未インストール。 */
    SUPPORTED_NOT_INSTALLED,

    /** 端末内音声認識が対応していない。 */
    UNSUPPORTED,
}

/**
 * 端末内音声認識の言語モデルの対応状況の確認とダウンロード要求（内部。テストで差し替える）。
 */
internal interface RecognitionModelGateway {
    /**
     * [bcp47LocaleTag] の言語モデルのダウンロードを要求し、進み具合を [onEvent] に通知する。
     *
     * 要求を送れた場合は `true`。
     */
    fun requestDownload(
        bcp47LocaleTag: String,
        onEvent: (RecognitionModelDownloadEvent) -> Unit,
    ): Boolean

    /**
     * [bcp47LocaleTags] の言語モデルの対応状況を調べる。調べられなかった言語は結果に含めない。
     */
    suspend fun checkSupport(bcp47LocaleTags: List<String>): Map<String, RecognitionModelSupport>
}

/**
 * [SpeechRecognizer.triggerModelDownload] と [SpeechRecognizer.checkRecognitionSupport] を使う
 * [RecognitionModelGateway] の本番実装。`SpeechRecognizer` はメインスレッドで作成・破棄する。
 */
internal class AndroidRecognitionModelGateway(
    private val context: Context,
) : RecognitionModelGateway {

    override fun requestDownload(
        bcp47LocaleTag: String,
        onEvent: (RecognitionModelDownloadEvent) -> Unit,
    ): Boolean {
        val mainHandler = Handler(Looper.getMainLooper())
        return mainHandler.post {
            var recognizer: SpeechRecognizer? = null
            var released = false
            val release = Runnable {
                if (!released) {
                    released = true
                    runCatching { recognizer?.destroy() }
                    recognizer = null
                }
            }
            try {
                val created = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer = created
                created.triggerModelDownload(
                    buildModelIntent(bcp47LocaleTag),
                    context.mainExecutor,
                    object : ModelDownloadListener {
                        override fun onProgress(completedPercent: Int) {
                            onEvent(RecognitionModelDownloadEvent.PROGRESS)
                        }

                        override fun onSuccess() {
                            onEvent(RecognitionModelDownloadEvent.SUCCESS)
                            release.run()
                        }

                        override fun onScheduled() {
                            onEvent(RecognitionModelDownloadEvent.SCHEDULED)
                            release.run()
                        }

                        override fun onError(error: Int) {
                            logModelInfo("modelDownload onError lang=$bcp47LocaleTag error=$error")
                            onEvent(RecognitionModelDownloadEvent.FAILED)
                            release.run()
                        }
                    },
                )
                // 完了通知が来ないまま SpeechRecognizer を持ち続けないよう、一定時間で手放す
                mainHandler.postDelayed(release, MODEL_DOWNLOAD_LISTENER_TIMEOUT_MS)
            } catch (e: Exception) {
                logModelInfo("modelDownload failed lang=$bcp47LocaleTag error=${e.javaClass.simpleName}")
                release.run()
                onEvent(RecognitionModelDownloadEvent.FAILED)
            }
        }
    }

    override suspend fun checkSupport(bcp47LocaleTags: List<String>): Map<String, RecognitionModelSupport> {
        if (bcp47LocaleTags.isEmpty()) return emptyMap()
        val support = withTimeoutOrNull(SUPPORT_CHECK_TIMEOUT_MS) { querySupportOnMainLooper() }
            ?: return emptyMap()
        return bcp47LocaleTags.associateWith { tag ->
            when {
                support.installedOnDeviceLanguages.containsTag(tag) -> RecognitionModelSupport.INSTALLED
                support.pendingOnDeviceLanguages.containsTag(tag) -> RecognitionModelSupport.PENDING
                support.supportedOnDeviceLanguages.containsTag(tag) -> RecognitionModelSupport.SUPPORTED_NOT_INSTALLED
                else -> RecognitionModelSupport.UNSUPPORTED
            }
        }
    }

    private suspend fun querySupportOnMainLooper(): RecognitionSupport? = suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null
        val release = Runnable {
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        continuation.invokeOnCancellation { mainHandler.post(release) }
        mainHandler.post {
            if (!continuation.isActive) return@post
            try {
                val created = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer = created
                created.checkRecognitionSupport(
                    buildModelIntent(bcp47LocaleTag = null),
                    context.mainExecutor,
                    object : RecognitionSupportCallback {
                        override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                            release.run()
                            if (continuation.isActive) continuation.resume(recognitionSupport)
                        }

                        override fun onError(error: Int) {
                            logModelInfo("checkRecognitionSupport onError error=$error")
                            release.run()
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            } catch (e: Exception) {
                logModelInfo("checkRecognitionSupport failed error=${e.javaClass.simpleName}")
                release.run()
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    private fun buildModelIntent(bcp47LocaleTag: String?): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        if (bcp47LocaleTag != null) {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47LocaleTag)
        }
    }

    private fun List<String>.containsTag(tag: String): Boolean = any { it.equals(tag, ignoreCase = true) }

    private fun logModelInfo(message: String) {
        runCatching {
            if (Log.isLoggable(MODEL_LOG_TAG, Log.INFO)) {
                Log.i(MODEL_LOG_TAG, message)
            }
        }
    }

    private companion object {
        const val MODEL_LOG_TAG: String = "ChunkFlowSpeech"
        const val MODEL_DOWNLOAD_LISTENER_TIMEOUT_MS: Long = 30L * 60L * 1_000L
        const val SUPPORT_CHECK_TIMEOUT_MS: Long = 5_000L
    }
}

/**
 * 2 秒プローブ認識結果 [attempt] が指定言語 [langTag] の自然な発話としてどの程度もっともらしいかを採点する。
 *
 * - `SpeechRecognizer` の [SpeechRecognitionAttemptResult.confidence] を最重視（重み [CONFIDENCE_SCORE_WEIGHT] = 3.0）し、
 *   低確信度（`< 0.60`）の無理な当てはめ認識には追加ペナルティを与える。
 * - 外国語音声が日本語認識器にカタカナ外来語化・音写された場合（純カタカナだけでなく、助詞や「です」等の
 *   ひらがなが数文字混入したカタカナ主体の結果も含む）を強く減点し、本来の学習言語の認識結果が高スコアになるよう判定する。
 */
internal fun scoreProbeCandidateForLanguage(
    attempt: SpeechRecognitionAttemptResult,
    langTag: String,
): Float {
    val text = attempt.text.trim()
    if (text.isEmpty()) return Float.NEGATIVE_INFINITY

    val primaryLang = langTag.trim().replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
    val validConfidence = attempt.confidence?.takeIf { it > 0f }
    val baseConfidence = validConfidence ?: DEFAULT_PROBE_CONFIDENCE
    var score = baseConfidence * CONFIDENCE_SCORE_WEIGHT
    if (validConfidence != null && validConfidence < LOW_CONFIDENCE_PENALTY_THRESHOLD) {
        score -= (LOW_CONFIDENCE_PENALTY_THRESHOLD - validConfidence) * LOW_CONFIDENCE_PENALTY_WEIGHT
    }

    var kanjiCount = 0
    var hiraganaCount = 0
    var katakanaCount = 0
    var hangulCount = 0
    var devanagariCount = 0
    var latinCount = 0
    var totalLetterCount = 0

    for (ch in text) {
        when {
            ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> {
                kanjiCount += 1
                totalLetterCount += 1
            }

            ch in '\u3040'..'\u309F' -> {
                hiraganaCount += 1
                totalLetterCount += 1
            }

            ch in '\u30A0'..'\u30FF' -> {
                katakanaCount += 1
                totalLetterCount += 1
            }

            ch in '\uAC00'..'\uD7A3' || ch in '\u3130'..'\u318F' -> {
                hangulCount += 1
                totalLetterCount += 1
            }

            ch in '\u0900'..'\u097F' -> {
                devanagariCount += 1
                totalLetterCount += 1
            }

            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '\u00C0'..'\u024F' -> {
                latinCount += 1
                totalLetterCount += 1
            }

            Character.isLetterOrDigit(ch) -> {
                totalLetterCount += 1
            }
        }
    }

    val safeTotalLetters = totalLetterCount.coerceAtLeast(1).toFloat()

    when (primaryLang) {
        "ja" -> {
            val katakanaRatio = katakanaCount.toFloat() / safeTotalLetters
            val hasJaMultiPattern = JA_FUNCTION_PATTERNS.any { text.contains(it) }
            val hasJaParticle = hiraganaCount >= 1 && JA_PARTICLES.any { text.contains(it) }

            when {
                hiraganaCount == 0 && kanjiCount == 0 && katakanaCount > 0 -> {
                    // ひらがな・漢字を含まない純カタカナ音写は強く減点する
                    score -= 1.6f
                }

                katakanaRatio >= 0.50f -> {
                    // 半分以上がカタカナのテキストは、助詞や「です」が混ざっていても外来語音写の可能性が高いためボーナスを与えず減点する
                    score -= katakanaRatio * 1.5f
                }

                katakanaRatio >= 0.30f -> {
                    // カタカナ比率が 30%〜50% の場合も減点し、日本語ボーナスを半分に抑える
                    score -= katakanaRatio * 1.0f
                    if (kanjiCount >= 1 && hiraganaCount >= 2) {
                        score += 0.35f
                    } else if (hiraganaCount >= 2) {
                        score += 0.15f
                    }
                    if (hasJaMultiPattern || hasJaParticle) {
                        score += 0.2f
                    }
                }

                else -> {
                    if (kanjiCount >= 1 && hiraganaCount >= 2) {
                        score += 0.7f
                    } else if (hiraganaCount >= 2) {
                        score += 0.35f
                    }
                    if (hasJaMultiPattern || hasJaParticle) {
                        score += 0.45f
                    }
                }
            }
        }

        "ko" -> {
            if (hangulCount > 0) {
                val hangulRatio = hangulCount.toFloat() / safeTotalLetters
                score += hangulRatio * 0.55f

                val hasKoGrammar = KO_GRAMMAR_PATTERNS.any { text.contains(it) }
                if (hasKoGrammar) {
                    score += 0.55f
                }
                val tokens = text.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
                val avgTokenLen = if (tokens.isNotEmpty()) {
                    tokens.sumOf { it.length }.toFloat() / tokens.size.toFloat()
                } else {
                    0f
                }
                val hasPoliteEnding = KO_POLITE_ENDINGS.any { text.contains(it) }
                if (tokens.size >= 3 && avgTokenLen <= 2.2f && !hasPoliteEnding) {
                    score -= 0.6f
                }
            } else {
                score -= 1.0f
            }
        }

        "zh", "yue" -> {
            if (totalLetterCount > 0) {
                score += (kanjiCount.toFloat() / safeTotalLetters) * 0.65f
            }
            if (hiraganaCount == 0 && katakanaCount == 0 && ZH_COMMON_CHARS.any { text.contains(it) }) {
                score += 0.5f
            }
            if (hiraganaCount > 0 || katakanaCount > 0) {
                score -= 0.8f
            }
        }

        "hi" -> {
            if (totalLetterCount > 0) {
                score += (devanagariCount.toFloat() / safeTotalLetters) * 1.1f
            }
        }

        else -> {
            if (totalLetterCount > 0) {
                score += (latinCount.toFloat() / safeTotalLetters) * 0.95f
            }
        }
    }

    return score
}

/**
 * 日本語認識結果 [text] が外国語音声のカタカナ外来語化（音写）である疑いが高いかどうかを判定する。
 */
internal fun isKatakanaTransliterationSuspected(
    text: String,
    langTag: String,
): Boolean {
    val primaryLang = langTag.trim().replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
    if (primaryLang != "ja") return false

    var kanjiCount = 0
    var hiraganaCount = 0
    var katakanaCount = 0
    var totalLetterCount = 0
    for (ch in text) {
        when {
            ch in '\u4E00'..'\u9FFF' || ch in '\u3400'..'\u4DBF' -> {
                kanjiCount += 1
                totalLetterCount += 1
            }

            ch in '\u3040'..'\u309F' -> {
                hiraganaCount += 1
                totalLetterCount += 1
            }

            ch in '\u30A0'..'\u30FF' -> {
                katakanaCount += 1
                totalLetterCount += 1
            }

            Character.isLetterOrDigit(ch) -> {
                totalLetterCount += 1
            }
        }
    }
    if (totalLetterCount <= 0 || katakanaCount <= 0) return false
    if (hiraganaCount == 0 && kanjiCount == 0) return true
    val katakanaRatio = katakanaCount.toFloat() / totalLetterCount.toFloat()
    return katakanaRatio >= KATAKANA_SUSPECT_RATIO_THRESHOLD
}

private const val CONFIDENCE_SCORE_WEIGHT: Float = 3.0f
private const val DEFAULT_PROBE_CONFIDENCE: Float = 0.55f
private const val LOW_CONFIDENCE_PENALTY_THRESHOLD: Float = 0.60f
private const val LOW_CONFIDENCE_PENALTY_WEIGHT: Float = 1.5f
private const val KATAKANA_SUSPECT_RATIO_THRESHOLD: Float = 0.35f
private val WHITESPACE_REGEX = Regex("\\s+")

private val JA_FUNCTION_PATTERNS: List<String> = listOf(
    "です",
    "ます",
    "した",
    "して",
    "さい",
    "しょう",
    "という",
    "から",
    "では",
    "には",
    "ません",
    "ください",
    "ましょう",
    "について",
    "ように",
    "ている",
)

private val JA_PARTICLES: List<String> = listOf("の", "を", "が", "は", "に", "で")

private val KO_GRAMMAR_PATTERNS: List<String> = listOf(
    "습니다",
    "습니까",
    "세요",
    "해요",
    "어요",
    "아요",
    "예요",
    "이에요",
    "네요",
    "지만",
    "에서",
    "으로",
    "에게",
    "부터",
    "까지",
    "니다",
    "니까",
    "을",
    "를",
    "은",
    "는",
)

private val KO_POLITE_ENDINGS: List<String> = listOf(
    "습니다",
    "습니까",
    "세요",
    "해요",
    "어요",
    "아요",
    "예요",
    "이에요",
    "네요",
    "니다",
    "니까",
)

private val ZH_COMMON_CHARS: List<String> = listOf(
    "的",
    "了",
    "是",
    "在",
    "我",
    "你",
    "他",
    "们",
    "这",
    "那",
    "不",
    "有",
    "吗",
    "呢",
)

/**
 * Android 標準の [SpeechRecognizer.createOnDeviceSpeechRecognizer]（Stage 1）と
 * `sherpa-onnx` ローカル Whisper モデル（Stage 2）を組み合わせた [OnDeviceChunkTranscriber] 実装（F6.2）。
 *
 * - 利用者が選択した [OnDeviceTranscriptionEngine]（`AUTO` / `ANDROID_SPEECH_RECOGNIZER` / `SHERPA_ONNX` / `ML_KIT`）に従って
 *   文字起こし処理を進める。
 * - `AUTO`（または将来拡張用の `ML_KIT`）では Android 標準 `SpeechRecognizer` を優先し、OS が非対応（`UNSUPPORTED`）の言語については
 *   `sherpa-onnx` ローカルモデルがチェックアウト済みであればそちらで推論し、未チェックアウトであれば `NOT_INSTALLED` として
 *   プレイヤーバナーや設定画面からワンタップで `sherpa-onnx` モデルを入手できるようにする。
 * - `SHERPA_ONNX` が選択されている場合は、チェックアウト済みの `sherpa-onnx` ローカルモデルを優先して使用する。
 */
@Singleton
class AndroidSpeechRecognizerChunkTranscriber internal constructor(
    @ApplicationContext private val context: Context,
    private val audioStreamDecoder: AudioStreamDecoder,
    private val onDeviceAvailabilityChecker: () -> Boolean,
    private val standardAvailabilityChecker: () -> Boolean,
    private val attemptRunner: (
        suspend (
            pcm16LeBytes: ByteArray,
            sampleRateHz: Int,
            bcp47LocaleTag: String,
            preferOnDevice: Boolean,
        ) -> SpeechRecognitionAttemptResult?
    )? = null,
    private val languageDetector: (
        (
            pcm16kMono: ShortArray,
            targetTag: String,
            baseTag: String,
        ) -> LanguagePairPrediction?
    )? = null,
    modelGateway: RecognitionModelGateway? = null,
    private val sherpaModelManager: SherpaOnnxModelManager? = null,
    private val sherpaTranscriber: (
        (
            pcm16LeBytes: ByteArray,
            sampleRateHz: Int,
            language: LanguageTag,
        ) -> OnDeviceChunkTranscriptionResult?
    )? = null,
    private val engineProvider: suspend () -> OnDeviceTranscriptionEngine = { OnDeviceTranscriptionEngine.AUTO },
) : OnDeviceChunkTranscriber {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        audioStreamDecoder: AudioStreamDecoder,
        whisperTinyLanguageDetector: WhisperTinyLanguageDetector,
        sherpaOnnxModelManager: SherpaOnnxModelManager,
        sherpaOnnxWhisperTranscriber: SherpaOnnxWhisperTranscriber,
        userSettingsRepository: UserSettingsRepository,
    ) : this(
        context = context,
        audioStreamDecoder = audioStreamDecoder,
        onDeviceAvailabilityChecker = {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        },
        standardAvailabilityChecker = {
            runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        },
        attemptRunner = null,
        languageDetector = whisperTinyLanguageDetector::detectLanguagePair,
        modelGateway = null,
        sherpaModelManager = sherpaOnnxModelManager,
        sherpaTranscriber = { pcmBytes, sampleRate, lang ->
            sherpaOnnxWhisperTranscriber.transcribePcm16Le(
                pcm16LeBytes = pcmBytes,
                sampleRateHz = sampleRate,
                language = lang,
            )
        },
        engineProvider = {
            runCatching { userSettingsRepository.settings.first().transcriptionEngine }
                .getOrDefault(OnDeviceTranscriptionEngine.AUTO)
        },
    )

    internal constructor(
        context: Context,
        audioStreamDecoder: AudioStreamDecoder,
    ) : this(
        context = context,
        audioStreamDecoder = audioStreamDecoder,
        onDeviceAvailabilityChecker = {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        },
        standardAvailabilityChecker = {
            runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        },
        attemptRunner = null,
        languageDetector = WhisperTinyLanguageDetector(context)::detectLanguagePair,
    )

    private data class ChunkAudioCacheScope(
        val audioPath: String,
        val lastModified: Long,
        val startMs: Long,
        val endMs: Long,
    )

    internal data class ChunkSttCacheKey(
        val audioPath: String,
        val lastModified: Long,
        val startMs: Long,
        val endMs: Long,
        val normalizedLangTag: String,
    )

    private val sttCacheLock = Any()
    private val fullChunkSttCache = object : LinkedHashMap<ChunkSttCacheKey, OnDeviceChunkTranscriptionResult>(
        MAX_STT_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ChunkSttCacheKey, OnDeviceChunkTranscriptionResult>?,
        ): Boolean = size > MAX_STT_CACHE_ENTRIES
    }

    private val recognitionLock = PriorityRecognitionLock()
    private var lastPipeAttemptCompletedRealtimeMs: Long = 0L

    private val recognitionModelGateway: RecognitionModelGateway =
        modelGateway ?: AndroidRecognitionModelGateway(context)
    private val modelIssuesState =
        MutableStateFlow<Map<LanguageTag, OnDeviceRecognitionModelState>>(emptyMap())

    /** 自動でダウンロードを要求済みの言語（BCP-47、小文字）。同じ言語の要求はプロセスごとに 1 回だけ。 */
    private val autoDownloadRequestedTags: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 言語タグ（小文字）ごとに端末で動作または対応が確認された BCP-47 ロケールタグのキャッシュ。 */
    private val resolvedBcp47TagByLangKey: ConcurrentHashMap<String, String> = ConcurrentHashMap()

    @Volatile
    private var lastModelIssueRefreshRealtimeMs: Long = 0L

    override val recognitionModelIssues: StateFlow<Map<LanguageTag, OnDeviceRecognitionModelState>> =
        modelIssuesState.asStateFlow()

    /** チャンク切り出し用に開いたままにしている抽出器を解放する（次の切り出しで開き直す）。 */
    private fun releaseChunkDecodeSession() {
        (audioStreamDecoder as? MediaCodecAudioStreamDecoder)?.releaseRangeSession()
    }

    override fun releaseIdleResources() {
        releaseChunkDecodeSession()
    }

    override fun isAvailable(): Boolean = onDeviceAvailabilityChecker() ||
        standardAvailabilityChecker() ||
        sherpaModelManager?.areCoreModelFilesPresent() == true ||
        sherpaTranscriber != null

    private fun isSherpaInstalledFor(language: LanguageTag?): Boolean {
        if (language == null) return false
        return sherpaModelManager?.isInstalledForLanguage(language) == true
    }

    private fun isSherpaSupportedFor(language: LanguageTag?): Boolean {
        if (language == null) return false
        return sherpaModelManager != null && SherpaOnnxModelManager.isLanguageSupportedByWhisper(language)
    }

    private fun clearAllSttCache() {
        synchronized(sttCacheLock) {
            fullChunkSttCache.clear()
        }
    }

    override suspend fun downloadSherpaModel(language: LanguageTag): Boolean {
        val manager = sherpaModelManager ?: return false
        setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)
        val ok = manager.downloadModelForLanguage(language)
        if (ok) {
            clearModelIssue(language)
            clearAllSttCache()
        } else {
            setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOAD_FAILED)
        }
        return ok
    }

    override suspend fun deleteSherpaModel(language: LanguageTag): Boolean {
        val manager = sherpaModelManager ?: return false
        val ok = manager.deleteModelForLanguage(language)
        if (ok) {
            clearAllSttCache()
        }
        return ok
    }

    override suspend fun requestRecognitionModelDownload(language: LanguageTag): Boolean {
        val selectedEngine = runCatching { engineProvider() }.getOrDefault(OnDeviceTranscriptionEngine.AUTO)
        if (selectedEngine == OnDeviceTranscriptionEngine.SHERPA_ONNX && isSherpaSupportedFor(language)) {
            return downloadSherpaModel(language)
        }
        if (!onDeviceAvailabilityChecker()) {
            return if (selectedEngine != OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER && isSherpaSupportedFor(language)) {
                downloadSherpaModel(language)
            } else {
                false
            }
        }
        val candidates = candidateBcp47TagsFor(language.value)
        val support = recognitionLock.withLock(highPriority = false) {
            try {
                recognitionModelGateway.checkSupport(candidates)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emptyMap()
            }
        }
        val installedTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.INSTALLED }
        if (installedTag != null) {
            rememberResolvedBcp47Tag(language.value, installedTag)
            clearModelIssue(language)
            return true
        }
        val supportedOrPendingTag = candidates.firstOrNull {
            support[it] == RecognitionModelSupport.SUPPORTED_NOT_INSTALLED ||
                support[it] == RecognitionModelSupport.PENDING
        }
        if (supportedOrPendingTag == null &&
            support.isNotEmpty() &&
            selectedEngine != OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER &&
            isSherpaSupportedFor(language)
        ) {
            // OS の SpeechRecognizer が非対応の言語は Stage 2 の sherpa-onnx ローカルモデルをダウンロードする
            return downloadSherpaModel(language)
        }
        val bcp47Tag = supportedOrPendingTag ?: candidates.first()
        rememberResolvedBcp47Tag(language.value, bcp47Tag)
        autoDownloadRequestedTags.add(bcp47Tag.lowercase(Locale.ROOT))
        return startRecognitionModelDownload(language, bcp47Tag)
    }

    override suspend fun refreshRecognitionModelIssues() {
        val issues = modelIssuesState.value
        if (issues.isEmpty()) return
        for (language in issues.keys) {
            if (isSherpaInstalledFor(language)) {
                clearModelIssue(language)
            }
        }
        val remainingIssues = modelIssuesState.value
        if (remainingIssues.isEmpty() || !onDeviceAvailabilityChecker()) return
        val now = SystemClock.elapsedRealtime()
        if (lastModelIssueRefreshRealtimeMs > 0L &&
            now - lastModelIssueRefreshRealtimeMs in 0L until MODEL_ISSUE_REFRESH_MIN_INTERVAL_MS
        ) {
            return
        }
        lastModelIssueRefreshRealtimeMs = now
        val candidatesByLanguage = remainingIssues.keys.associateWith { candidateBcp47TagsFor(it.value) }
        // 認識中の SpeechRecognizer と同時にサービスへ接続しないよう、認識ロックを取ってから問い合わせる
        val support = recognitionLock.withLock(highPriority = false) {
            try {
                recognitionModelGateway.checkSupport(candidatesByLanguage.values.flatten().distinct())
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emptyMap()
            }
        }
        for ((language, candidates) in candidatesByLanguage) {
            val installedTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.INSTALLED }
            val pendingTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.PENDING }
            val supportedTag = candidates.firstOrNull {
                support[it] == RecognitionModelSupport.SUPPORTED_NOT_INSTALLED
            }
            when {
                installedTag != null || isSherpaInstalledFor(language) -> {
                    if (installedTag != null) {
                        logInfo("modelSupport lang=$installedTag installed")
                        rememberResolvedBcp47Tag(language.value, installedTag)
                    }
                    clearModelIssue(language)
                }

                pendingTag != null -> {
                    rememberResolvedBcp47Tag(language.value, pendingTag)
                    setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)
                }

                supportedTag != null -> {
                    rememberResolvedBcp47Tag(language.value, supportedTag)
                    if (remainingIssues[language] == OnDeviceRecognitionModelState.UNSUPPORTED) {
                        setModelIssue(language, OnDeviceRecognitionModelState.NOT_INSTALLED)
                    }
                }
            }
        }
    }

    override suspend fun queryLanguageModelInfos(languages: List<LanguageTag>): List<OnDeviceLanguageModelInfo> {
        if (languages.isEmpty()) return emptyList()
        if (!onDeviceAvailabilityChecker()) {
            return languages.map { lang ->
                withSherpaModelFields(
                    language = lang,
                    osStatus = OnDeviceLanguageModelStatus.UNSUPPORTED,
                    resolvedBcp47Tag = candidateBcp47TagsFor(lang.value).firstOrNull(),
                )
            }
        }
        val candidatesByLanguage = languages.associateWith { candidateBcp47TagsFor(it.value) }
        val support = recognitionLock.withLock(highPriority = false) {
            try {
                recognitionModelGateway.checkSupport(candidatesByLanguage.values.flatten().distinct())
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emptyMap()
            }
        }
        val currentIssues = modelIssuesState.value
        return languages.map { language ->
            val candidates = candidatesByLanguage[language].orEmpty()
            val installedTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.INSTALLED }
            val pendingTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.PENDING }
            val supportedTag = candidates.firstOrNull {
                support[it] == RecognitionModelSupport.SUPPORTED_NOT_INSTALLED
            }
            val issue = currentIssues[language]
            when {
                installedTag != null -> {
                    rememberResolvedBcp47Tag(language.value, installedTag)
                    clearModelIssue(language)
                    withSherpaModelFields(
                        language = language,
                        osStatus = OnDeviceLanguageModelStatus.INSTALLED,
                        resolvedBcp47Tag = installedTag,
                    )
                }

                pendingTag != null ||
                    (
                        issue == OnDeviceRecognitionModelState.DOWNLOADING &&
                            sherpaModelManager?.getLanguageModelStatus(language) != OnDeviceLanguageModelStatus.DOWNLOADING
                        )
                -> {
                    val tag = pendingTag ?: supportedTag ?: candidates.firstOrNull()
                    if (tag != null) rememberResolvedBcp47Tag(language.value, tag)
                    withSherpaModelFields(
                        language = language,
                        osStatus = OnDeviceLanguageModelStatus.DOWNLOADING,
                        resolvedBcp47Tag = tag,
                    )
                }

                supportedTag != null -> {
                    rememberResolvedBcp47Tag(language.value, supportedTag)
                    val status = if (issue == OnDeviceRecognitionModelState.DOWNLOAD_FAILED) {
                        OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                    } else {
                        OnDeviceLanguageModelStatus.NOT_INSTALLED
                    }
                    withSherpaModelFields(
                        language = language,
                        osStatus = status,
                        resolvedBcp47Tag = supportedTag,
                    )
                }

                support.isNotEmpty() -> {
                    if (isSherpaInstalledFor(language)) {
                        clearModelIssue(language)
                    }
                    withSherpaModelFields(
                        language = language,
                        osStatus = OnDeviceLanguageModelStatus.UNSUPPORTED,
                        resolvedBcp47Tag = candidates.firstOrNull(),
                    )
                }

                else -> {
                    val status = when (issue) {
                        OnDeviceRecognitionModelState.NOT_INSTALLED -> OnDeviceLanguageModelStatus.NOT_INSTALLED
                        OnDeviceRecognitionModelState.DOWNLOADING -> OnDeviceLanguageModelStatus.DOWNLOADING
                        OnDeviceRecognitionModelState.DOWNLOAD_FAILED -> OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                        OnDeviceRecognitionModelState.UNSUPPORTED -> OnDeviceLanguageModelStatus.UNSUPPORTED
                        null -> OnDeviceLanguageModelStatus.CHECKING
                    }
                    withSherpaModelFields(
                        language = language,
                        osStatus = status,
                        resolvedBcp47Tag = candidates.firstOrNull(),
                    )
                }
            }
        }
    }

    private fun withSherpaModelFields(
        language: LanguageTag,
        osStatus: OnDeviceLanguageModelStatus,
        resolvedBcp47Tag: String?,
    ): OnDeviceLanguageModelInfo {
        val manager = sherpaModelManager
        if (manager == null) {
            return OnDeviceLanguageModelInfo(
                language = language,
                status = osStatus,
                resolvedBcp47Tag = resolvedBcp47Tag,
            )
        }
        val sherpaStatus = manager.getLanguageModelStatus(language)
        val installed = sherpaStatus == OnDeviceLanguageModelStatus.INSTALLED
        return OnDeviceLanguageModelInfo(
            language = language,
            status = osStatus,
            resolvedBcp47Tag = resolvedBcp47Tag,
            sherpaModelStatus = sherpaStatus,
            sherpaModelId = SherpaOnnxModelManager.MODEL_ID,
            sherpaModelSizeBytes = manager.totalModelSizeBytes,
            canDeleteSherpaModel = installed,
        )
    }

    private fun candidateBcp47TagsFor(
        rawLang: String?,
        fallbackBcp47Tag: String = toBcp47LocaleTag(rawLang ?: ""),
    ): List<String> {
        val source = rawLang?.takeIf { it.isNotBlank() } ?: fallbackBcp47Tag
        val langKey = source.trim().replace('_', '-').lowercase(Locale.ROOT)
        val resolved = resolvedBcp47TagByLangKey[langKey]
        val candidates = toBcp47CandidateLocaleTags(source)
        return if (resolved != null) {
            (listOf(resolved) + candidates).distinct()
        } else {
            candidates
        }
    }

    private fun rememberResolvedBcp47Tag(rawLang: String?, resolvedTag: String) {
        val source = rawLang?.takeIf { it.isNotBlank() } ?: return
        val langKey = source.trim().replace('_', '-').lowercase(Locale.ROOT)
        resolvedBcp47TagByLangKey[langKey] = resolvedTag
    }

    private fun startRecognitionModelDownload(language: LanguageTag, bcp47Tag: String): Boolean {
        logInfo("modelDownload request lang=$bcp47Tag")
        setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)
        val requested = runCatching {
            recognitionModelGateway.requestDownload(bcp47Tag) { event ->
                onRecognitionModelDownloadEvent(language, bcp47Tag, event)
            }
        }.getOrDefault(false)
        if (!requested) {
            setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOAD_FAILED)
        }
        return requested
    }

    private fun onRecognitionModelDownloadEvent(
        language: LanguageTag,
        bcp47Tag: String,
        event: RecognitionModelDownloadEvent,
    ) {
        if (event != RecognitionModelDownloadEvent.PROGRESS) {
            logInfo("modelDownload lang=$bcp47Tag event=$event")
        }
        when (event) {
            RecognitionModelDownloadEvent.PROGRESS,
            RecognitionModelDownloadEvent.SCHEDULED,
            -> setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)

            RecognitionModelDownloadEvent.SUCCESS -> clearModelIssue(language)

            RecognitionModelDownloadEvent.FAILED -> setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOAD_FAILED)
        }
    }

    /**
     * 1 回の認識試行の結果から、[language] の言語モデルの状態を更新する。
     *
     * 言語モデルの未ダウンロード（`ERROR_LANGUAGE_UNAVAILABLE`）を初めて検出した言語は、ダウンロードを自動で要求する。
     */
    private fun noteRecognitionAttemptOutcome(
        language: LanguageTag?,
        bcp47Tag: String,
        attempt: SpeechRecognitionAttemptResult?,
        selectedEngine: OnDeviceTranscriptionEngine = OnDeviceTranscriptionEngine.AUTO,
    ) {
        if (language == null || attempt == null) return
        when {
            attempt.text.isNotBlank() -> clearModelIssue(language)

            attempt.errorCode == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> {
                if (selectedEngine != OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER &&
                    isSherpaSupportedFor(language)
                ) {
                    val sherpaStatus = sherpaModelManager?.getLanguageModelStatus(language)
                    when (sherpaStatus) {
                        OnDeviceLanguageModelStatus.INSTALLED -> clearModelIssue(language)
                        OnDeviceLanguageModelStatus.DOWNLOADING -> setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)
                        OnDeviceLanguageModelStatus.DOWNLOAD_FAILED -> setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOAD_FAILED)
                        else -> setModelIssue(language, OnDeviceRecognitionModelState.NOT_INSTALLED)
                    }
                } else {
                    setModelIssue(language, OnDeviceRecognitionModelState.UNSUPPORTED)
                }
            }

            attempt.errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                val current = modelIssuesState.value[language]
                if (current != OnDeviceRecognitionModelState.DOWNLOADING &&
                    current != OnDeviceRecognitionModelState.DOWNLOAD_FAILED
                ) {
                    setModelIssue(language, OnDeviceRecognitionModelState.NOT_INSTALLED)
                }
                if (selectedEngine != OnDeviceTranscriptionEngine.SHERPA_ONNX &&
                    onDeviceAvailabilityChecker() &&
                    autoDownloadRequestedTags.add(bcp47Tag.lowercase(Locale.ROOT))
                ) {
                    startRecognitionModelDownload(language, bcp47Tag)
                }
            }
        }
    }

    private fun setModelIssue(language: LanguageTag, state: OnDeviceRecognitionModelState) {
        modelIssuesState.update { current ->
            if (current[language] == state) current else current + (language to state)
        }
    }

    private fun clearModelIssue(language: LanguageTag) {
        modelIssuesState.update { current ->
            if (language in current) current - language else current
        }
    }

    override suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
    ): OnDeviceChunkTranscriptionResult? = transcribeChunk(
        audioFile = audioFile,
        startMs = startMs,
        endMs = endMs,
        languagePair = languagePair,
        forceRefresh = false,
        highPriority = false,
    )

    override suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
        forceRefresh: Boolean,
        highPriority: Boolean,
    ): OnDeviceChunkTranscriptionResult? {
        if (!isAvailable() || startMs < 0L || endMs <= startMs || !audioFile.isFile) {
            return null
        }

        val cacheScope = ChunkAudioCacheScope(
            audioPath = audioFile.absolutePath,
            lastModified = audioFile.lastModified(),
            startMs = startMs,
            endMs = endMs,
        )
        val targetLang = languagePair?.target
        val baseLang = languagePair?.base?.takeIf {
            targetLang == null || !it.value.equals(targetLang.value, ignoreCase = true)
        }
        if (forceRefresh) {
            evictCachedSttResults(cacheScope)
        } else if (targetLang == null || baseLang == null) {
            val singleLang = targetLang ?: baseLang
            val cached = getCachedSttResult(cacheScope, singleLang)
            if (cached != null) {
                logInfo("singleLang cacheHit startMs=$startMs endMs=$endMs lang=${singleLang?.value ?: "default"}")
                return cached
            }
        } else {
            getCachedBilingualResultIfBothPresent(cacheScope, targetLang, baseLang)?.let { cachedBoth ->
                logInfo("bilingual bothCacheHit startMs=$startMs endMs=$endMs winner=${cachedBoth.language?.value}")
                return cachedBoth
            }
        }

        val pcmBytes = try {
            ChunkPcmExtractor.extractChunkPcm16LeBytes(
                decoder = audioStreamDecoder,
                audioFile = audioFile,
                startMs = startMs,
                endMs = endMs,
                sampleRateHz = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        } ?: return null

        return transcribePcm16LeInternal(
            pcm16LeBytes = pcmBytes,
            sampleRateHz = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
            languagePair = languagePair,
            cacheScope = cacheScope,
            forceRefresh = forceRefresh,
            highPriority = highPriority,
        )
    }

    override suspend fun transcribePcm16Le(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        languagePair: LanguagePair?,
    ): OnDeviceChunkTranscriptionResult? {
        if (!isAvailable() || pcm16LeBytes.isEmpty() || sampleRateHz <= 0) {
            return null
        }
        val chunkDurationMs = ((pcm16LeBytes.size / 2).toLong() * 1_000L) / sampleRateHz.toLong()
        val syntheticScope = ChunkAudioCacheScope(
            audioPath = "pcm:${pcm16LeBytes.size}:${pcm16LeBytes.contentHashCode()}",
            lastModified = sampleRateHz.toLong(),
            startMs = 0L,
            endMs = chunkDurationMs,
        )
        return transcribePcm16LeInternal(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
            languagePair = languagePair,
            cacheScope = syntheticScope,
            forceRefresh = false,
            highPriority = false,
        )
    }

    private suspend fun transcribePcm16LeInternal(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        languagePair: LanguagePair?,
        cacheScope: ChunkAudioCacheScope,
        forceRefresh: Boolean,
        highPriority: Boolean,
    ): OnDeviceChunkTranscriptionResult? {
        if (!isAvailable() || pcm16LeBytes.isEmpty() || sampleRateHz <= 0) {
            return null
        }

        val onDeviceAvailable = onDeviceAvailabilityChecker()
        val standardAvailable = standardAvailabilityChecker()
        val sherpaAvailable = sherpaModelManager?.areCoreModelFilesPresent() == true || sherpaTranscriber != null
        if (!onDeviceAvailable && !standardAvailable && !sherpaAvailable) return null

        // isOnDeviceRecognitionAvailable == true の端末では物理マイクが開くのを防ぐため preferOnDevice = true のみを使用する
        val preferOnDevice = onDeviceAvailable

        val targetLang = languagePair?.target
        val baseLang = languagePair?.base?.takeIf {
            targetLang == null || !it.value.equals(targetLang.value, ignoreCase = true)
        }
        val chunkDurationMs = ((pcm16LeBytes.size / 2).toLong() * 1_000L) / sampleRateHz.toLong()

        if (!forceRefresh) {
            if (targetLang == null || baseLang == null) {
                val singleLang = targetLang ?: baseLang
                getCachedSttResult(cacheScope, singleLang)?.let { cached ->
                    logInfo(
                        "singleLang cacheHit chunkDurationMs=$chunkDurationMs lang=${singleLang?.value ?: "default"}",
                    )
                    return cached
                }
            } else {
                getCachedBilingualResultIfBothPresent(cacheScope, targetLang, baseLang)?.let { cachedBoth ->
                    return cachedBoth
                }
            }
        }

        return recognitionLock.withLock(highPriority = highPriority) {
            if (forceRefresh) {
                evictCachedSttResults(cacheScope)
            } else if (targetLang == null || baseLang == null) {
                val singleLang = targetLang ?: baseLang
                getCachedSttResult(cacheScope, singleLang)?.let { cached ->
                    return@withLock cached
                }
            } else {
                getCachedBilingualResultIfBothPresent(cacheScope, targetLang, baseLang)?.let { cachedBoth ->
                    return@withLock cachedBoth
                }
            }

            if (targetLang != null && baseLang != null) {
                val lidPrediction = if (languageDetector != null) {
                    val shorts = ChunkPcmExtractor.pcm16LeBytesToShorts(pcm16LeBytes)
                    runCatching {
                        languageDetector?.invoke(shorts, targetLang.value, baseLang.value)
                    }.getOrNull()
                } else {
                    null
                }
                if (lidPrediction != null) {
                    transcribeWithWhisperLidPrediction(
                        pcm16LeBytes = pcm16LeBytes,
                        sampleRateHz = sampleRateHz,
                        chunkDurationMs = chunkDurationMs,
                        targetLang = targetLang,
                        baseLang = baseLang,
                        prediction = lidPrediction,
                        preferOnDevice = preferOnDevice,
                        cacheScope = cacheScope,
                    )
                } else {
                    transcribeWithTwoSecondLanguageProbe(
                        pcm16LeBytes = pcm16LeBytes,
                        sampleRateHz = sampleRateHz,
                        chunkDurationMs = chunkDurationMs,
                        targetLang = targetLang,
                        baseLang = baseLang,
                        preferOnDevice = preferOnDevice,
                        cacheScope = cacheScope,
                    )
                }
            } else {
                val singleLang = targetLang ?: baseLang
                val bcp47Tag = singleLang?.value?.let(::toBcp47LocaleTag)
                    ?: toBcp47LocaleTag(Locale.getDefault().toLanguageTag())
                val attempt = executeAttempt(
                    pcm16LeBytes = pcm16LeBytes,
                    sampleRateHz = sampleRateHz,
                    bcp47LocaleTag = bcp47Tag,
                    preferOnDevice = preferOnDevice,
                    isProbe = false,
                    language = singleLang,
                )
                if (attempt != null && attempt.text.isNotBlank()) {
                    logInfo(
                        "singleLang chunkDurationMs=$chunkDurationMs lang=${singleLang?.value ?: bcp47Tag} chars=${attempt.text.trim().length}",
                    )
                    val result = OnDeviceChunkTranscriptionResult(
                        text = attempt.text.trim(),
                        language = singleLang,
                        confidence = attempt.confidence,
                        engineId = attempt.engineId,
                    )
                    putCachedSttResult(cacheScope, singleLang?.value ?: bcp47Tag, result)
                    result
                } else {
                    logInfo(
                        "singleLang empty chunkDurationMs=$chunkDurationMs lang=${singleLang?.value ?: bcp47Tag} " +
                            "modelUnavailable=${attempt?.isLanguageModelUnavailable == true}",
                    )
                    null
                }
            }
        }
    }

    private suspend fun transcribeWithWhisperLidPrediction(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        chunkDurationMs: Long,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
        prediction: LanguagePairPrediction,
        preferOnDevice: Boolean,
        cacheScope: ChunkAudioCacheScope,
    ): OnDeviceChunkTranscriptionResult? {
        val selectedLang = if (prediction.selectedTag.equals(baseLang.value, ignoreCase = true)) {
            baseLang
        } else {
            targetLang
        }
        val fallbackLang = if (selectedLang == targetLang) baseLang else targetLang

        logDebug(
            "whisperLid selected=${selectedLang.value} prob=${prediction.selectedProbability} " +
                "targetProb=${prediction.targetProbability} baseProb=${prediction.baseProbability} " +
                "lidMs=${prediction.inferenceTimeMs} chunkDurationMs=$chunkDurationMs",
        )

        var primaryModelUnavailable = false
        val primaryResult = getCachedSttResult(cacheScope, selectedLang) ?: run {
            val selectedBcp47 = toBcp47LocaleTag(selectedLang.value)
            val attempt = executeAttempt(
                pcm16LeBytes = pcm16LeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = selectedBcp47,
                preferOnDevice = preferOnDevice,
                isProbe = false,
                language = selectedLang,
            )
            primaryModelUnavailable = attempt?.isLanguageModelUnavailable == true
            attempt?.takeIf { it.text.isNotBlank() }?.let {
                OnDeviceChunkTranscriptionResult(
                    text = it.text.trim(),
                    language = selectedLang,
                    confidence = it.confidence,
                    engineId = it.engineId,
                ).also { res ->
                    putCachedSttResult(cacheScope, selectedLang.value, res)
                }
            }
        }

        val isLidConfident = prediction.selectedProbability >= AMBIGUOUS_LID_PROB_THRESHOLD
        if (primaryResult == null && primaryModelUnavailable && isLidConfident) {
            // 選ばれた言語の音声認識モデルがない。もう一方の言語で認識するとカタカナ・ハングルの音写になり
            // 言語ラベルも誤るため、言語判定の結果だけを返す（モデルのダウンロード後に本文を認識し直す）
            logInfo(
                "whisperLid selected=${selectedLang.value} prob=${prediction.selectedProbability} " +
                    "modelUnavailable=true result=languageOnly chunkDurationMs=$chunkDurationMs",
            )
            return languageOnlyResult(selectedLang, prediction.selectedProbability)
        }

        val shouldVerifyFallback = primaryResult != null && (
            isKatakanaTransliterationSuspected(primaryResult.text, selectedLang.value) ||
                (primaryResult.confidence != null && primaryResult.confidence < LOW_STT_CONFIDENCE_THRESHOLD) ||
                (
                    prediction.selectedProbability < AMBIGUOUS_LID_PROB_THRESHOLD &&
                        (primaryResult.confidence == null || primaryResult.confidence < MODERATE_STT_CONFIDENCE_THRESHOLD)
                    )
            )

        if (primaryResult != null && !shouldVerifyFallback) {
            return primaryResult
        }

        val fallbackResult = getCachedSttResult(cacheScope, fallbackLang) ?: run {
            val fallbackBcp47 = toBcp47LocaleTag(fallbackLang.value)
            val attempt = executeAttempt(
                pcm16LeBytes = pcm16LeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = fallbackBcp47,
                preferOnDevice = preferOnDevice,
                isProbe = false,
                language = fallbackLang,
            )?.takeIf { it.text.isNotBlank() }
            attempt?.let {
                OnDeviceChunkTranscriptionResult(
                    text = it.text.trim(),
                    language = fallbackLang,
                    confidence = it.confidence,
                    engineId = it.engineId,
                ).also { res ->
                    putCachedSttResult(cacheScope, fallbackLang.value, res)
                }
            }
        }

        if (primaryResult == null) {
            // 選ばれた言語で認識できなかった場合、もう一方の言語の結果は、自然な発話に見える（音写の疑いがない）ときか、
            // 言語判定が曖昧で、かつ選ばれた言語のモデル自体は使える（単に聞き取れなかった）ときだけ採用する
            val acceptFallback = fallbackResult != null && (
                isNaturalRecognitionFor(fallbackResult, fallbackLang) ||
                    (!isLidConfident && !primaryModelUnavailable)
                )
            if (acceptFallback) {
                return fallbackResult
            }
            if (primaryModelUnavailable || fallbackResult != null) {
                logInfo(
                    "whisperLid selected=${selectedLang.value} prob=${prediction.selectedProbability} " +
                        "modelUnavailable=$primaryModelUnavailable fallbackRejected=${fallbackResult != null} " +
                        "result=languageOnly chunkDurationMs=$chunkDurationMs",
                )
                return languageOnlyResult(selectedLang, prediction.selectedProbability)
            }
            return null
        }
        if (fallbackResult == null) {
            return primaryResult
        }

        val primaryScore = scoreProbeCandidateForLanguage(
            SpeechRecognitionAttemptResult(text = primaryResult.text, confidence = primaryResult.confidence),
            selectedLang.value,
        )
        val fallbackScore = scoreProbeCandidateForLanguage(
            SpeechRecognitionAttemptResult(text = fallbackResult.text, confidence = fallbackResult.confidence),
            fallbackLang.value,
        )
        return if (fallbackScore > primaryScore) fallbackResult else primaryResult
    }

    /** 認識結果 [result] が言語 [language] の自然な発話に見える（音写の疑いがなく、信頼度が低すぎない）かどうか。 */
    private fun isNaturalRecognitionFor(
        result: OnDeviceChunkTranscriptionResult,
        language: LanguageTag,
    ): Boolean = !isKatakanaTransliterationSuspected(result.text, language.value) &&
        (result.confidence == null || result.confidence >= LOW_STT_CONFIDENCE_THRESHOLD)

    /** 本文なしで、言語判定の結果だけを持つ認識結果を作る。キャッシュには入れない。 */
    private fun languageOnlyResult(
        language: LanguageTag,
        lidProbability: Float,
    ): OnDeviceChunkTranscriptionResult = OnDeviceChunkTranscriptionResult(
        text = "",
        language = language,
        confidence = lidProbability.coerceIn(0f, 1f),
        engineId = OnDeviceChunkTranscriptionResult.LANGUAGE_ID_ONLY_ENGINE_ID,
    )

    private fun getCachedBilingualResultIfBothPresent(
        cacheScope: ChunkAudioCacheScope,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
    ): OnDeviceChunkTranscriptionResult? {
        val cachedTarget = getCachedSttResult(cacheScope, targetLang) ?: return null
        val cachedBase = getCachedSttResult(cacheScope, baseLang) ?: return null
        val targetScore = scoreProbeCandidateForLanguage(
            SpeechRecognitionAttemptResult(text = cachedTarget.text, confidence = cachedTarget.confidence),
            targetLang.value,
        )
        val baseScore = scoreProbeCandidateForLanguage(
            SpeechRecognitionAttemptResult(text = cachedBase.text, confidence = cachedBase.confidence),
            baseLang.value,
        )
        return if (baseScore > targetScore) cachedBase else cachedTarget
    }

    private suspend fun transcribeWithTwoSecondLanguageProbe(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        chunkDurationMs: Long,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
        preferOnDevice: Boolean,
        cacheScope: ChunkAudioCacheScope,
    ): OnDeviceChunkTranscriptionResult? {
        val probeSlice = ChunkPcmExtractor.extractLeadingSpeechProbePcm(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
        )
        val probeBytes = probeSlice.probePcm16LeBytes
        val targetBcp47 = toBcp47LocaleTag(targetLang.value)
        val baseBcp47 = toBcp47LocaleTag(baseLang.value)

        val targetProbe = if (probeSlice.isFullChunkCovered) {
            getCachedSttResult(cacheScope, targetLang)?.let {
                SpeechRecognitionAttemptResult(text = it.text, confidence = it.confidence, engineId = it.engineId)
            } ?: executeAttempt(
                pcm16LeBytes = probeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = targetBcp47,
                preferOnDevice = preferOnDevice,
                isProbe = true,
                language = targetLang,
            )?.takeIf { it.text.isNotBlank() }?.also { probe ->
                putCachedSttResult(
                    cacheScope = cacheScope,
                    rawLangTag = targetLang.value,
                    result = OnDeviceChunkTranscriptionResult(
                        text = probe.text.trim(),
                        language = targetLang,
                        confidence = probe.confidence,
                        engineId = probe.engineId,
                    ),
                )
            }
        } else {
            executeAttempt(
                pcm16LeBytes = probeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = targetBcp47,
                preferOnDevice = preferOnDevice,
                isProbe = true,
                language = targetLang,
            )?.takeIf { it.text.isNotBlank() }
        }

        val baseProbe = if (probeSlice.isFullChunkCovered) {
            getCachedSttResult(cacheScope, baseLang)?.let {
                SpeechRecognitionAttemptResult(text = it.text, confidence = it.confidence, engineId = it.engineId)
            } ?: executeAttempt(
                pcm16LeBytes = probeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = baseBcp47,
                preferOnDevice = preferOnDevice,
                isProbe = true,
                language = baseLang,
            )?.takeIf { it.text.isNotBlank() }?.also { probe ->
                putCachedSttResult(
                    cacheScope = cacheScope,
                    rawLangTag = baseLang.value,
                    result = OnDeviceChunkTranscriptionResult(
                        text = probe.text.trim(),
                        language = baseLang,
                        confidence = probe.confidence,
                        engineId = probe.engineId,
                    ),
                )
            }
        } else {
            executeAttempt(
                pcm16LeBytes = probeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = baseBcp47,
                preferOnDevice = preferOnDevice,
                isProbe = true,
                language = baseLang,
            )?.takeIf { it.text.isNotBlank() }
        }

        if (targetProbe == null && baseProbe == null) {
            logInfo(
                "probe empty chunkDurationMs=$chunkDurationMs targetLang=${targetLang.value} baseLang=${baseLang.value}",
            )
            return null
        }

        val targetScore = targetProbe?.let { scoreProbeCandidateForLanguage(it, targetLang.value) }
            ?: Float.NEGATIVE_INFINITY
        val baseScore = baseProbe?.let { scoreProbeCandidateForLanguage(it, baseLang.value) }
            ?: Float.NEGATIVE_INFINITY

        val winningLang: LanguageTag
        val winningProbe: SpeechRecognitionAttemptResult
        if (baseProbe != null && (targetProbe == null || baseScore > targetScore)) {
            winningLang = baseLang
            winningProbe = baseProbe
        } else if (targetProbe != null) {
            winningLang = targetLang
            winningProbe = targetProbe
        } else {
            return null
        }

        if (probeSlice.isFullChunkCovered) {
            val text = winningProbe.text.trim()
            logInfo(
                "probe winner=${winningLang.value} chunkDurationMs=$chunkDurationMs targetLang=${targetLang.value} targetScore=$targetScore baseLang=${baseLang.value} baseScore=$baseScore fullChunkUsed=false chars=${text.length}",
            )
            val result = OnDeviceChunkTranscriptionResult(
                text = text,
                language = winningLang,
                confidence = winningProbe.confidence,
                engineId = winningProbe.engineId,
            )
            putCachedSttResult(cacheScope, winningLang.value, result)
            return result
        }

        getCachedSttResult(cacheScope, winningLang)?.let { cached ->
            logInfo(
                "probe winner=${winningLang.value} chunkDurationMs=$chunkDurationMs fullChunkCacheHit=true chars=${cached.text.length}",
            )
            return cached
        }

        val winningBcp47 = toBcp47LocaleTag(winningLang.value)
        val fullAttempt = executeAttempt(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
            bcp47LocaleTag = winningBcp47,
            preferOnDevice = preferOnDevice,
            isProbe = false,
            language = winningLang,
        )?.takeIf { it.text.isNotBlank() }

        val finalAttempt = fullAttempt ?: winningProbe
        val finalText = finalAttempt.text.trim()
        logInfo(
            "probe winner=${winningLang.value} chunkDurationMs=$chunkDurationMs targetLang=${targetLang.value} targetScore=$targetScore baseLang=${baseLang.value} baseScore=$baseScore fullChunkUsed=${fullAttempt != null} chars=${finalText.length}",
        )
        val result = OnDeviceChunkTranscriptionResult(
            text = finalText,
            language = winningLang,
            confidence = finalAttempt.confidence,
            engineId = finalAttempt.engineId,
        )
        putCachedSttResult(cacheScope, winningLang.value, result)
        return result
    }

    private fun normalizeLangCacheKey(rawLangTag: String?): String {
        val tag = rawLangTag?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag()
        return toBcp47LocaleTag(tag).lowercase(Locale.ROOT)
    }

    private fun getCachedSttResult(
        cacheScope: ChunkAudioCacheScope,
        requestedLang: LanguageTag?,
    ): OnDeviceChunkTranscriptionResult? {
        val key = ChunkSttCacheKey(
            audioPath = cacheScope.audioPath,
            lastModified = cacheScope.lastModified,
            startMs = cacheScope.startMs,
            endMs = cacheScope.endMs,
            normalizedLangTag = normalizeLangCacheKey(requestedLang?.value),
        )
        val cached = synchronized(sttCacheLock) {
            fullChunkSttCache[key]
        } ?: return null
        return if (requestedLang != null && cached.language != requestedLang) {
            cached.copy(language = requestedLang)
        } else {
            cached
        }
    }

    private fun putCachedSttResult(
        cacheScope: ChunkAudioCacheScope,
        rawLangTag: String?,
        result: OnDeviceChunkTranscriptionResult,
    ) {
        if (result.text.isBlank()) return
        val key = ChunkSttCacheKey(
            audioPath = cacheScope.audioPath,
            lastModified = cacheScope.lastModified,
            startMs = cacheScope.startMs,
            endMs = cacheScope.endMs,
            normalizedLangTag = normalizeLangCacheKey(rawLangTag),
        )
        synchronized(sttCacheLock) {
            fullChunkSttCache[key] = result
        }
    }

    private fun evictCachedSttResults(cacheScope: ChunkAudioCacheScope) {
        synchronized(sttCacheLock) {
            val iterator = fullChunkSttCache.entries.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next().key
                if (key.audioPath == cacheScope.audioPath &&
                    key.lastModified == cacheScope.lastModified &&
                    key.startMs == cacheScope.startMs &&
                    key.endMs == cacheScope.endMs
                ) {
                    iterator.remove()
                }
            }
        }
    }

    private fun trySherpaInference(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        language: LanguageTag?,
    ): SpeechRecognitionAttemptResult? {
        if (language == null) return null
        val runner = sherpaTranscriber ?: return null
        if (sherpaModelManager != null && !sherpaModelManager.isInstalledForLanguage(language)) {
            return null
        }
        val result = runCatching {
            runner(pcm16LeBytes, sampleRateHz, language)
        }.getOrNull() ?: return null
        if (result.text.isBlank()) return null
        clearModelIssue(language)
        return SpeechRecognitionAttemptResult(
            text = result.text.trim(),
            confidence = result.confidence,
            errorCode = null,
            engineId = result.engineId,
        )
    }

    private suspend fun executeAttempt(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        preferOnDevice: Boolean,
        isProbe: Boolean,
        language: LanguageTag? = null,
    ): SpeechRecognitionAttemptResult? {
        val selectedEngine = runCatching { engineProvider() }.getOrDefault(OnDeviceTranscriptionEngine.AUTO)

        // SHERPA_ONNX が明示選択されている場合は sherpa-onnx ローカルモデルを最優先で使用する
        if (selectedEngine == OnDeviceTranscriptionEngine.SHERPA_ONNX && language != null) {
            trySherpaInference(pcm16LeBytes, sampleRateHz, language)?.let { return it }
            if (isSherpaSupportedFor(language) && !isSherpaInstalledFor(language)) {
                val sherpaStatus = sherpaModelManager?.getLanguageModelStatus(language)
                when (sherpaStatus) {
                    OnDeviceLanguageModelStatus.DOWNLOADING -> setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)
                    OnDeviceLanguageModelStatus.DOWNLOAD_FAILED -> setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOAD_FAILED)
                    else -> setModelIssue(language, OnDeviceRecognitionModelState.NOT_INSTALLED)
                }
                return SpeechRecognitionAttemptResult(
                    text = "",
                    errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    engineId = SherpaOnnxWhisperTranscriber.SHERPA_ONNX_ENGINE_ID,
                )
            }
        }

        val candidates = candidateBcp47TagsFor(language?.value, bcp47LocaleTag)
        var lastAttempt: SpeechRecognitionAttemptResult? = null
        var lastTag: String = candidates.firstOrNull() ?: bcp47LocaleTag

        if (onDeviceAvailabilityChecker() || standardAvailabilityChecker() || attemptRunner != null) {
            for ((index, candidateTag) in candidates.withIndex()) {
                lastTag = candidateTag
                val attempt = runAttemptWithTag(
                    pcm16LeBytes = pcm16LeBytes,
                    sampleRateHz = sampleRateHz,
                    bcp47LocaleTag = candidateTag,
                    preferOnDevice = preferOnDevice,
                    isProbe = isProbe,
                )
                lastAttempt = attempt
                if (attempt?.errorCode != SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED) {
                    if (index > 0 ||
                        attempt?.text?.isNotBlank() == true ||
                        attempt?.errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                    ) {
                        rememberResolvedBcp47Tag(language?.value, candidateTag)
                    }
                    // OS モデルが未ダウンロードの間でも、sherpa-onnx がチェックアウト済みならフォールバック推論する
                    if (attempt?.errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE &&
                        selectedEngine != OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER
                    ) {
                        trySherpaInference(pcm16LeBytes, sampleRateHz, language)?.let { return it }
                    }
                    noteRecognitionAttemptOutcome(language, candidateTag, attempt, selectedEngine)
                    return attempt
                }
                if (index + 1 < candidates.size) {
                    logInfo(
                        "bcp47Fallback lang=${language?.value ?: bcp47LocaleTag} " +
                            "unsupportedTag=$candidateTag nextTag=${candidates[index + 1]}",
                    )
                }
            }
        }

        // すべての候補タグで ERROR_LANGUAGE_NOT_SUPPORTED が返った場合でも、
        // checkRecognitionSupport 上で対応・未インストール（例: cmn-Hans-CN）と報告される端末では
        // 未ダウンロード（ERROR_LANGUAGE_UNAVAILABLE）として扱い、モデルの取得を開始する
        if (language != null && onDeviceAvailabilityChecker()) {
            val support = try {
                recognitionModelGateway.checkSupport(candidates)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                emptyMap()
            }
            val installedTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.INSTALLED }
            val pendingTag = candidates.firstOrNull { support[it] == RecognitionModelSupport.PENDING }
            val downloadableTag = candidates.firstOrNull {
                support[it] == RecognitionModelSupport.SUPPORTED_NOT_INSTALLED
            }
            when {
                installedTag != null -> {
                    rememberResolvedBcp47Tag(language.value, installedTag)
                    clearModelIssue(language)
                    return lastAttempt
                }

                pendingTag != null -> {
                    rememberResolvedBcp47Tag(language.value, pendingTag)
                    setModelIssue(language, OnDeviceRecognitionModelState.DOWNLOADING)
                    return SpeechRecognitionAttemptResult(
                        text = "",
                        errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    )
                }

                downloadableTag != null -> {
                    rememberResolvedBcp47Tag(language.value, downloadableTag)
                    val unavailableAttempt = SpeechRecognitionAttemptResult(
                        text = "",
                        errorCode = SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    )
                    noteRecognitionAttemptOutcome(language, downloadableTag, unavailableAttempt, selectedEngine)
                    return unavailableAttempt
                }
            }
        }

        // OS の SpeechRecognizer が非対応（UNSUPPORTED）の場合、Stage 2 の sherpa-onnx ローカルモデルへフォールバックする
        if (language != null && selectedEngine != OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER) {
            trySherpaInference(pcm16LeBytes, sampleRateHz, language)?.let { return it }
            if (isSherpaSupportedFor(language)) {
                val unavailableAttempt = SpeechRecognitionAttemptResult(
                    text = "",
                    errorCode = SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    engineId = SherpaOnnxWhisperTranscriber.SHERPA_ONNX_ENGINE_ID,
                )
                noteRecognitionAttemptOutcome(language, lastTag, unavailableAttempt, selectedEngine)
                return unavailableAttempt
            }
        }

        noteRecognitionAttemptOutcome(language, lastTag, lastAttempt, selectedEngine)
        return lastAttempt
    }

    private suspend fun runAttemptWithTag(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        preferOnDevice: Boolean,
        isProbe: Boolean,
    ): SpeechRecognitionAttemptResult? {
        val runner = attemptRunner
        return if (runner != null) {
            runner(pcm16LeBytes, sampleRateHz, bcp47LocaleTag, preferOnDevice)
        } else {
            runSinglePipeAttempt(
                pcm16LeBytes = pcm16LeBytes,
                sampleRateHz = sampleRateHz,
                bcp47LocaleTag = bcp47LocaleTag,
                preferOnDevice = preferOnDevice,
                isProbe = isProbe,
            )
        }
    }

    private suspend fun runSinglePipeAttempt(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        preferOnDevice: Boolean,
        isProbe: Boolean,
    ): SpeechRecognitionAttemptResult? {
        if (lastPipeAttemptCompletedRealtimeMs > 0L) {
            val elapsed = SystemClock.elapsedRealtime() - lastPipeAttemptCompletedRealtimeMs
            if (elapsed in 0L until INTER_RECOGNITION_SETTLE_DELAY_MS) {
                delay(INTER_RECOGNITION_SETTLE_DELAY_MS - elapsed)
            }
        }

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
                }

                val recognitionResult = withTimeoutOrNull(RECOGNITION_TIMEOUT_MS) {
                    recognizeFromPipeOnMainLooper(
                        readSide = readSide,
                        sampleRateHz = sampleRateHz,
                        bcp47LocaleTag = bcp47LocaleTag,
                        preferOnDevice = preferOnDevice,
                        onListeningStarted = { started ->
                            listeningStarted.complete(started)
                        },
                    )
                }
                if (!listeningStarted.isCompleted) {
                    listeningStarted.complete(false)
                }
                writerJob.cancel()
                recognitionResult
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        } finally {
            runCatching { readSide.close() }
            runCatching { writeSide.close() }
            lastPipeAttemptCompletedRealtimeMs = SystemClock.elapsedRealtime()
        }
    }

    private suspend fun recognizeFromPipeOnMainLooper(
        readSide: ParcelFileDescriptor,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        preferOnDevice: Boolean,
        onListeningStarted: (Boolean) -> Unit,
    ): SpeechRecognitionAttemptResult? = suspendCancellableCoroutine { continuation ->
        val mainHandler = Handler(Looper.getMainLooper())
        var recognizer: SpeechRecognizer? = null
        var latestPartialText: String = ""

        val cleanup = Runnable {
            runCatching {
                recognizer?.cancel()
                recognizer?.destroy()
            }
            recognizer = null
        }

        continuation.invokeOnCancellation {
            mainHandler.post(cleanup)
        }

        mainHandler.post {
            if (!continuation.isActive) {
                onListeningStarted(false)
                return@post
            }
            try {
                val created = if (preferOnDevice) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                recognizer = created

                val listener = object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = extractBestRecognitionText(partialResults)
                        if (partial.isNotEmpty()) {
                            latestPartialText = partial
                        }
                    }

                    override fun onError(error: Int) {
                        val outcome = resolveRecognitionCallbackOutcome(
                            resultsText = null,
                            confidence = null,
                            partialText = latestPartialText,
                            errorCode = error,
                        )
                        logInfo(
                            "onError preferOnDevice=$preferOnDevice lang=$bcp47LocaleTag error=$error fallbackChars=${outcome?.text?.length ?: 0}",
                        )
                        cleanup.run()
                        if (continuation.isActive) {
                            continuation.resume(outcome)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        val bestText = extractBestRecognitionText(results)
                        val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val bestConfidence = scores?.firstOrNull()?.takeIf { it in 0.0f..1.0f }
                        val outcome = resolveRecognitionCallbackOutcome(
                            resultsText = bestText,
                            confidence = bestConfidence,
                            partialText = latestPartialText,
                            errorCode = null,
                        )
                        logInfo(
                            "onResults preferOnDevice=$preferOnDevice lang=$bcp47LocaleTag chars=${outcome?.text?.length ?: 0}",
                        )
                        cleanup.run()
                        if (continuation.isActive) {
                            continuation.resume(outcome)
                        }
                    }
                }

                created.setRecognitionListener(listener)
                val intent = buildRecognizerIntent(
                    readSide = readSide,
                    sampleRateHz = sampleRateHz,
                    bcp47LocaleTag = bcp47LocaleTag,
                    preferOnDevice = preferOnDevice,
                )
                created.startListening(intent)
                onListeningStarted(true)
            } catch (_: Exception) {
                onListeningStarted(false)
                cleanup.run()
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    internal fun buildRecognizerIntent(
        readSide: ParcelFileDescriptor,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        preferOnDevice: Boolean,
    ): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47LocaleTag)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, bcp47LocaleTag)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOnDevice)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRateHz)
    }

    private fun extractBestRecognitionText(bundle: Bundle?): String = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()

    private fun logInfo(message: String) {
        runCatching {
            if (Log.isLoggable(LOG_TAG, Log.INFO)) {
                Log.i(LOG_TAG, message)
            }
        }
    }

    private fun logDebug(message: String) {
        runCatching {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, message)
            }
        }
    }

    companion object {
        private const val LOG_TAG: String = "ChunkFlowSpeech"
        private const val PIPE_FRAME_DURATION_MS: Int = 20
        private const val MIN_PIPE_FRAME_BYTES: Int = 640
        private const val PIPE_AUDIO_PACE_MS: Long = 10L
        private const val PIPE_TRAILING_SILENCE_PACE_MS: Long = 15L
        private const val PROBE_PIPE_AUDIO_PACE_MS: Long = 8L
        private const val PROBE_PIPE_TRAILING_SILENCE_PACE_MS: Long = 10L
        internal const val INTER_RECOGNITION_SETTLE_DELAY_MS: Long = 200L
        private const val RECOGNITION_TIMEOUT_MS: Long = 12_000L
        private const val MAX_STT_CACHE_ENTRIES: Int = 2_048
        private const val AMBIGUOUS_LID_PROB_THRESHOLD: Float = 0.75f
        private const val LOW_STT_CONFIDENCE_THRESHOLD: Float = 0.68f
        private const val MODERATE_STT_CONFIDENCE_THRESHOLD: Float = 0.80f
        private const val MODEL_ISSUE_REFRESH_MIN_INTERVAL_MS: Long = 15_000L

        /**
         * `onResults` または `onError` コールバック時点で最終結果テキストと `onPartialResults` の保持テキストを統合する。
         *
         * 言語モデルが使えないエラー（`ERROR_LANGUAGE_UNAVAILABLE` / `ERROR_LANGUAGE_NOT_SUPPORTED`）は、
         * 呼び出し側で区別できるよう、本文が空でエラーコードを持つ結果として返す。
         */
        internal fun resolveRecognitionCallbackOutcome(
            resultsText: String?,
            confidence: Float?,
            partialText: String,
            errorCode: Int?,
        ): SpeechRecognitionAttemptResult? {
            val trimmedFinal = resultsText?.trim().orEmpty()
            if (trimmedFinal.isNotEmpty()) {
                return SpeechRecognitionAttemptResult(
                    text = trimmedFinal,
                    confidence = confidence,
                )
            }
            if (errorCode == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                errorCode == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
            ) {
                return SpeechRecognitionAttemptResult(text = "", errorCode = errorCode)
            }
            val trimmedPartial = partialText.trim()
            if (trimmedPartial.isEmpty()) return null

            return if (
                errorCode == null ||
                errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            ) {
                SpeechRecognitionAttemptResult(
                    text = trimmedPartial,
                    confidence = null,
                )
            } else {
                null
            }
        }
    }
}

/**
 * `highPriority = true` の待機コルーチンが `highPriority = false`（バックグラウンド先読み）の待機コルーチンより
 * 必ず先にロックを取得できる優先度付き非同期排他ロック。
 */
internal class PriorityRecognitionLock {
    private val stateLock = Any()
    private var isLocked = false
    private val highPriorityQueue = ArrayDeque<CompletableDeferred<Unit>>()
    private val normalPriorityQueue = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun <T> withLock(highPriority: Boolean, block: suspend () -> T): T {
        acquire(highPriority)
        return try {
            block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(highPriority: Boolean) {
        val waiter: CompletableDeferred<Unit>? = synchronized(stateLock) {
            if (!isLocked && highPriorityQueue.isEmpty() && normalPriorityQueue.isEmpty()) {
                isLocked = true
                null
            } else {
                CompletableDeferred<Unit>().also { deferred ->
                    if (highPriority) {
                        highPriorityQueue.addLast(deferred)
                    } else {
                        normalPriorityQueue.addLast(deferred)
                    }
                }
            }
        }
        if (waiter == null) return
        try {
            waiter.await()
        } catch (ce: CancellationException) {
            var acquiredBeforeCancel = false
            synchronized(stateLock) {
                val removed = if (highPriority) {
                    highPriorityQueue.remove(waiter)
                } else {
                    normalPriorityQueue.remove(waiter)
                }
                if (!removed && waiter.isCompleted) {
                    acquiredBeforeCancel = true
                }
            }
            if (acquiredBeforeCancel) {
                release()
            }
            throw ce
        }
    }

    private fun release() {
        while (true) {
            val next: CompletableDeferred<Unit>? = synchronized(stateLock) {
                val waiter = highPriorityQueue.removeFirstOrNull()
                    ?: normalPriorityQueue.removeFirstOrNull()
                if (waiter == null) {
                    isLocked = false
                    null
                } else {
                    waiter
                }
            }
            if (next == null) return
            if (next.complete(Unit)) return
        }
    }
}
