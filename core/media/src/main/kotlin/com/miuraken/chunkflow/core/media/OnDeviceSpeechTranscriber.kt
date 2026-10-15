package com.miuraken.chunkflow.core.media

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 端末内音声認識による 1 チャンク分の認識結果（F6.2）。
 *
 * @property text 認識されたテキスト（前後空白除去済み）。
 * @property language 検出または指定された言語タグ（不明な場合は `null`）。
 * @property confidence 認識信頼度（0.0..1.0、取得できない場合は `null`）。
 * @property engineId 使用した端末内認識エンジンの識別子。
 */
data class OnDeviceChunkTranscriptionResult(
    val text: String,
    val language: LanguageTag? = null,
    val confidence: Float? = null,
    val engineId: EngineId = DEFAULT_ON_DEVICE_ENGINE_ID,
) {
    companion object {
        val DEFAULT_ON_DEVICE_ENGINE_ID: EngineId = EngineId("android-on-device-speech")
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
     * 切り出し済みの 16kHz モノラル 16-bit リトルエンディアン PCM（[pcm16LeBytes]）に対して端末内音声認識を行う。
     */
    suspend fun transcribePcm16Le(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
        languagePair: LanguagePair? = null,
    ): OnDeviceChunkTranscriptionResult? = null

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000
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

        else -> {
            val localeTag = Locale.forLanguageTag(normalized).toLanguageTag()
            if (localeTag.isNotBlank() && localeTag != "und") localeTag else normalized
        }
    }
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
 * - [MediaCodecAudioStreamDecoder] 使用時は `startMs` 直前へシークしてデコードを開始し、抽出済みチャンクを LRU キャッシュする。
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

        val expectedBytes = ((endSample - startSample) * 2L).coerceAtMost(MAX_CHUNK_PCM_BYTES.toLong()).toInt()
        val out = ByteArrayOutputStream(max(expectedBytes, 1024))
        var currentSampleIndex = 0L

        val sink = SampleSink { samples, count ->
            val blockStart = currentSampleIndex
            val blockEnd = currentSampleIndex + count
            currentSampleIndex = blockEnd

            val sliceStart = max(startSample, blockStart)
            val sliceEnd = min(endSample, blockEnd)
            if (sliceStart < sliceEnd && out.size() < MAX_CHUNK_PCM_BYTES) {
                val localFrom = (sliceStart - blockStart).toInt()
                val localTo = (sliceEnd - blockStart).toInt()
                for (idx in localFrom until localTo) {
                    if (out.size() + 2 > MAX_CHUNK_PCM_BYTES) break
                    val clamped = samples[idx].coerceIn(-1.0f, 1.0f)
                    val sampleInt = (clamped * Short.MAX_VALUE.toFloat())
                        .roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.write(sampleInt and 0xFF)
                    out.write((sampleInt ushr 8) and 0xFF)
                }
            }
            if (currentSampleIndex >= endSample || out.size() >= MAX_CHUNK_PCM_BYTES) {
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
                    onSeekLandingMs = { landedMs ->
                        currentSampleIndex = (landedMs * sampleRateHz) / 1_000L
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

        val extracted = when {
            stoppedEarly -> out.toByteArray().takeIf { it.isNotEmpty() }
            result is AudioDecodeResult.Success -> out.toByteArray().takeIf { it.isNotEmpty() }
            else -> null
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
 */
internal data class SpeechRecognitionAttemptResult(
    val text: String,
    val confidence: Float? = null,
)

/**
 * 2 秒プローブ認識結果 [attempt] が指定言語 [langTag] の自然な発話としてどの程度もっともらしいかを採点する。
 *
 * 外国語音声が別言語の認識器に音写された場合（例: 日本語音声が韓国語認識器でハングル音写された場合や、
 * 韓国語音声が日本語認識器でカタカナ音写された場合）よりも、本来の言語の認識結果が高スコアになるよう判定する。
 */
internal fun scoreProbeCandidateForLanguage(
    attempt: SpeechRecognitionAttemptResult,
    langTag: String,
): Float {
    val text = attempt.text.trim()
    if (text.isEmpty()) return Float.NEGATIVE_INFINITY

    val primaryLang = langTag.trim().replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
    var score = (attempt.confidence?.takeIf { it > 0f } ?: DEFAULT_PROBE_CONFIDENCE) * 1.0f

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

    when (primaryLang) {
        "ja" -> {
            if (kanjiCount >= 1 && hiraganaCount >= 2) {
                score += 1.2f
            } else if (hiraganaCount >= 2) {
                score += 0.5f
            }

            val hasJaMultiPattern = JA_FUNCTION_PATTERNS.any { text.contains(it) }
            val hasJaParticle = hiraganaCount >= 1 && JA_PARTICLES.any { text.contains(it) }
            if (hasJaMultiPattern || hasJaParticle) {
                score += 0.8f
            }

            if (hiraganaCount == 0 && katakanaCount > 0 && katakanaCount * 2 >= totalLetterCount.coerceAtLeast(1)) {
                score -= 0.8f
            }
        }

        "ko" -> {
            if (hangulCount > 0) {
                val hasKoGrammar = KO_GRAMMAR_PATTERNS.any { text.contains(it) }
                if (hasKoGrammar) {
                    score += 0.7f
                }
                val tokens = text.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
                val avgTokenLen = if (tokens.isNotEmpty()) {
                    tokens.sumOf { it.length }.toFloat() / tokens.size.toFloat()
                } else {
                    0f
                }
                val hasPoliteEnding = KO_POLITE_ENDINGS.any { text.contains(it) }
                if (tokens.size >= 3 && avgTokenLen <= 2.2f && !hasPoliteEnding) {
                    score -= 0.5f
                }
            } else {
                score -= 0.8f
            }
        }

        "zh", "yue" -> {
            if (totalLetterCount > 0) {
                score += (kanjiCount.toFloat() / totalLetterCount.toFloat()) * 0.8f
            }
            if (hiraganaCount == 0 && ZH_COMMON_CHARS.any { text.contains(it) }) {
                score += 0.6f
            }
        }

        "hi" -> {
            if (totalLetterCount > 0) {
                score += (devanagariCount.toFloat() / totalLetterCount.toFloat()) * 1.0f
            }
        }

        else -> {
            if (totalLetterCount > 0) {
                score += (latinCount.toFloat() / totalLetterCount.toFloat()) * 0.7f
            }
        }
    }

    return score
}

private const val DEFAULT_PROBE_CONFIDENCE: Float = 0.65f
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
 * Android 標準の [SpeechRecognizer.createOnDeviceSpeechRecognizer]（API 33+ の `EXTRA_AUDIO_SOURCE` +
 * `ParcelFileDescriptor.createPipe()`）を用いた [OnDeviceChunkTranscriber] 実装（F6.2）。
 *
 * - 学習言語（`target`）と母語（`base`）のペアが設定されている場合、まずチャンク先頭約 2 秒のプローブ音声を
 *   両言語で認識し、[scoreProbeCandidateForLanguage] でどちらの言語かを判定してから、勝った言語でチャンク全体の STT を実行する。
 * - `isOnDeviceRecognitionAvailable(context) == true` の端末では `createOnDeviceSpeechRecognizer` のみを使用し、
 *   物理マイクを掴んでハングする `createSpeechRecognizer`（`preferOnDevice = false`）へはフォールバックしない。
 * - 連続して `SpeechRecognizer` を呼び出す際はシステムサービスのアンバインド競合を防ぐため 200ms の間隔を空ける。
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
) : OnDeviceChunkTranscriber {

    @Inject
    constructor(
        @ApplicationContext context: Context,
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
    )

    private val recognitionMutex = Mutex()
    private var lastPipeAttemptCompletedRealtimeMs: Long = 0L

    override fun isAvailable(): Boolean = onDeviceAvailabilityChecker() || standardAvailabilityChecker()

    override suspend fun transcribeChunk(
        audioFile: File,
        startMs: Long,
        endMs: Long,
        languagePair: LanguagePair?,
    ): OnDeviceChunkTranscriptionResult? {
        if (!isAvailable() || startMs < 0L || endMs <= startMs || !audioFile.isFile) {
            return null
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

        return transcribePcm16Le(
            pcm16LeBytes = pcmBytes,
            sampleRateHz = OnDeviceChunkTranscriber.DEFAULT_SAMPLE_RATE_HZ,
            languagePair = languagePair,
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

        val onDeviceAvailable = onDeviceAvailabilityChecker()
        val standardAvailable = standardAvailabilityChecker()
        if (!onDeviceAvailable && !standardAvailable) return null

        // isOnDeviceRecognitionAvailable == true の端末では物理マイクが開くのを防ぐため preferOnDevice = true のみを使用する
        val preferOnDevice = onDeviceAvailable

        val targetLang = languagePair?.target
        val baseLang = languagePair?.base?.takeIf {
            targetLang == null || !it.value.equals(targetLang.value, ignoreCase = true)
        }
        val chunkDurationMs = ((pcm16LeBytes.size / 2).toLong() * 1_000L) / sampleRateHz.toLong()

        return recognitionMutex.withLock {
            if (targetLang != null && baseLang != null) {
                transcribeWithTwoSecondLanguageProbe(
                    pcm16LeBytes = pcm16LeBytes,
                    sampleRateHz = sampleRateHz,
                    chunkDurationMs = chunkDurationMs,
                    targetLang = targetLang,
                    baseLang = baseLang,
                    preferOnDevice = preferOnDevice,
                )
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
                )
                if (attempt != null && attempt.text.isNotBlank()) {
                    logInfo(
                        "singleLang chunkDurationMs=$chunkDurationMs lang=${singleLang?.value ?: bcp47Tag} chars=${attempt.text.trim().length}",
                    )
                    OnDeviceChunkTranscriptionResult(
                        text = attempt.text.trim(),
                        language = singleLang,
                        confidence = attempt.confidence,
                    )
                } else {
                    logInfo(
                        "singleLang empty chunkDurationMs=$chunkDurationMs lang=${singleLang?.value ?: bcp47Tag}",
                    )
                    null
                }
            }
        }
    }

    private suspend fun transcribeWithTwoSecondLanguageProbe(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        chunkDurationMs: Long,
        targetLang: LanguageTag,
        baseLang: LanguageTag,
        preferOnDevice: Boolean,
    ): OnDeviceChunkTranscriptionResult? {
        val probeSlice = ChunkPcmExtractor.extractLeadingSpeechProbePcm(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
        )
        val probeBytes = probeSlice.probePcm16LeBytes
        val targetBcp47 = toBcp47LocaleTag(targetLang.value)
        val baseBcp47 = toBcp47LocaleTag(baseLang.value)

        val targetProbe = executeAttempt(
            pcm16LeBytes = probeBytes,
            sampleRateHz = sampleRateHz,
            bcp47LocaleTag = targetBcp47,
            preferOnDevice = preferOnDevice,
            isProbe = true,
        )?.takeIf { it.text.isNotBlank() }

        val baseProbe = executeAttempt(
            pcm16LeBytes = probeBytes,
            sampleRateHz = sampleRateHz,
            bcp47LocaleTag = baseBcp47,
            preferOnDevice = preferOnDevice,
            isProbe = true,
        )?.takeIf { it.text.isNotBlank() }

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
            return OnDeviceChunkTranscriptionResult(
                text = text,
                language = winningLang,
                confidence = winningProbe.confidence,
            )
        }

        val winningBcp47 = toBcp47LocaleTag(winningLang.value)
        val fullAttempt = executeAttempt(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
            bcp47LocaleTag = winningBcp47,
            preferOnDevice = preferOnDevice,
            isProbe = false,
        )?.takeIf { it.text.isNotBlank() }

        val finalAttempt = fullAttempt ?: winningProbe
        val finalText = finalAttempt.text.trim()
        logInfo(
            "probe winner=${winningLang.value} chunkDurationMs=$chunkDurationMs targetLang=${targetLang.value} targetScore=$targetScore baseLang=${baseLang.value} baseScore=$baseScore fullChunkUsed=${fullAttempt != null} chars=${finalText.length}",
        )
        return OnDeviceChunkTranscriptionResult(
            text = finalText,
            language = winningLang,
            confidence = finalAttempt.confidence,
        )
    }

    private suspend fun executeAttempt(
        pcm16LeBytes: ByteArray,
        sampleRateHz: Int,
        bcp47LocaleTag: String,
        preferOnDevice: Boolean,
        isProbe: Boolean,
    ): SpeechRecognitionAttemptResult? {
        val runner = attemptRunner
        if (runner != null) {
            return runner(pcm16LeBytes, sampleRateHz, bcp47LocaleTag, preferOnDevice)
        }
        return runSinglePipeAttempt(
            pcm16LeBytes = pcm16LeBytes,
            sampleRateHz = sampleRateHz,
            bcp47LocaleTag = bcp47LocaleTag,
            preferOnDevice = preferOnDevice,
            isProbe = isProbe,
        )
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
            Log.i(LOG_TAG, message)
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

        /**
         * `onResults` または `onError` コールバック時点で最終結果テキストと `onPartialResults` の保持テキストを統合する。
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
