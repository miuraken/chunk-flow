package com.miuraken.chunkflow.core.transcript

import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.WordTiming
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [TranscriptDocument] を `chunkflow-transcript` v1 スキーマ（`docs/format/chunkflow-transcript-v1.schema.json`）に
 * 準拠した UTF-8（Unicode NFC 正規化済み）JSON 文字列またはバイト列へ直列化するクラス（§7.4.3、§7.4.6、§7.5）。
 *
 * 端末内の先読み文字起こし（F6.2）や手動修正（F7.5）、字幕ファイルからの変換で生成・更新された文字起こしを
 * ローカルの `.chunkflow.json` や Google Drive の `<course>/transcription/<stem>.chunkflow.json` に保存する際に使用する。
 */
@OptIn(ExperimentalSerializationApi::class)
class ChunkFlowTranscriptSerializer(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * [document] を `chunkflow-transcript` v1 形式の JSON 文字列に直列化する。
     *
     * @param document 直列化する文字起こしドキュメント。
     * @param sha256 対象音声の SHA-256（64 桁の小文字 16 進数）。省略時は [TranscriptDocument.audioSha256] を使用する。
     * @param fileName 対象音声のファイル名。省略時は [TranscriptDocument.audioFileName] を使用する。
     * @param durationMs 対象音声の長さ（ミリ秒）。省略時は [TranscriptDocument.durationMs] を使用する。
     * @param languages 教材の言語ペア。省略時は [TranscriptDocument.languages] を使用する。
     * @param tool `generator.tool` に記録するツール名。
     * @param engine `generator.engine` に記録するエンジン名。
     * @param model `generator.model` に記録するモデル名。
     * @param createdAt `generator.createdAt`（RFC 3339 文字列）。省略時は [TranscriptDocument.createdAt] または現在時刻（UTC）を使用する。
     */
    fun serialize(
        document: TranscriptDocument,
        sha256: String? = document.audioSha256,
        fileName: String? = document.audioFileName,
        durationMs: Long? = document.durationMs,
        languages: LanguagePair? = document.languages,
        tool: String = DEFAULT_TOOL,
        engine: String = document.generator.value.ifBlank { DEFAULT_ENGINE },
        model: String = document.generator.value.ifBlank { DEFAULT_MODEL },
        createdAt: String? = document.createdAt,
    ): String {
        val resolvedSha256 = (sha256 ?: document.audioSha256)?.trim()?.lowercase()
            ?: throw IllegalArgumentException("audio sha256 is required for chunkflow-transcript v1 serialization")
        require(ChunkFlowTranscriptParser.isValidSha256(resolvedSha256)) {
            "Invalid SHA-256 format: must be 64 lowercase hex characters"
        }

        val resolvedFileName = sanitizeAudioFileName(fileName ?: document.audioFileName)
        val resolvedLanguages = languages ?: document.languages ?: LanguagePair(
            target = LanguageTag(DEFAULT_TARGET_LANG),
            base = null,
        )
        val targetLang = sanitizeLanguageTag(resolvedLanguages.target.value, DEFAULT_TARGET_LANG)
        val baseLang = resolvedLanguages.base?.value
            ?.let { sanitizeLanguageTag(it, "") }
            ?.takeIf { it.isNotEmpty() && it != targetLang }

        val serializedSegments = buildNormalizedSegments(
            segments = document.segments,
            targetLang = targetLang,
            baseLang = baseLang,
        )

        val maxSegmentEndMs = serializedSegments.maxOfOrNull { it.endMs } ?: 0L
        val requestedDurationMs = durationMs ?: document.durationMs ?: 1L
        val resolvedDurationMs = maxOf(requestedDurationMs, maxSegmentEndMs, 1L)

        val resolvedCreatedAt = if (createdAt != null && ChunkFlowTranscriptParser.isValidRfc3339(createdAt)) {
            createdAt
        } else {
            formatEpochMsAsRfc3339Utc(clock())
        }

        val payload = SerializableTranscriptDocument(
            format = ChunkFlowTranscriptParser.FORMAT_NAME,
            version = ChunkFlowTranscriptParser.SUPPORTED_VERSION,
            audio = SerializableAudio(
                fileName = resolvedFileName,
                sha256 = resolvedSha256,
                durationMs = resolvedDurationMs,
            ),
            languages = SerializableLanguages(
                target = targetLang,
                base = baseLang,
            ),
            generator = SerializableGenerator(
                tool = sanitizeShortText(tool, DEFAULT_TOOL),
                engine = sanitizeShortText(engine, DEFAULT_ENGINE),
                model = sanitizeShortText(model, DEFAULT_MODEL),
                createdAt = resolvedCreatedAt,
            ),
            segments = serializedSegments,
        )

        return json.encodeToString(SerializableTranscriptDocument.serializer(), payload)
    }

    /**
     * [document] を `chunkflow-transcript` v1 形式の UTF-8 バイト列に直列化する。
     */
    fun serializeToBytes(
        document: TranscriptDocument,
        sha256: String? = document.audioSha256,
        fileName: String? = document.audioFileName,
        durationMs: Long? = document.durationMs,
        languages: LanguagePair? = document.languages,
        tool: String = DEFAULT_TOOL,
        engine: String = document.generator.value.ifBlank { DEFAULT_ENGINE },
        model: String = document.generator.value.ifBlank { DEFAULT_MODEL },
        createdAt: String? = document.createdAt,
    ): ByteArray = serialize(
        document = document,
        sha256 = sha256,
        fileName = fileName,
        durationMs = durationMs,
        languages = languages,
        tool = tool,
        engine = engine,
        model = model,
        createdAt = createdAt,
    ).encodeToByteArray()

    private fun buildNormalizedSegments(
        segments: List<TranscriptSegment>,
        targetLang: String,
        baseLang: String?,
    ): List<SerializableSegment> {
        if (segments.isEmpty()) return emptyList()

        val sorted = segments
            .filter { it.startMs >= 0L && it.endMs > it.startMs }
            .sortedWith(compareBy<TranscriptSegment> { it.startMs }.thenBy { it.endMs })

        val result = ArrayList<SerializableSegment>(minOf(sorted.size, ChunkFlowTranscriptParser.MAX_SEGMENTS))
        for (idx in sorted.indices) {
            if (result.size >= ChunkFlowTranscriptParser.MAX_SEGMENTS) break
            val seg = sorted[idx]
            val nextStartMs = if (idx + 1 < sorted.size) sorted[idx + 1].startMs else null
            val clampedEndMs = if (nextStartMs != null && seg.endMs > nextStartMs) {
                nextStartMs
            } else {
                seg.endMs
            }
            if (seg.startMs >= clampedEndMs) continue

            val normalizedText = truncateCodePoints(
                ChunkFlowTranscriptParser.normalizeNfc(seg.text).trim(),
                ChunkFlowTranscriptParser.MAX_SEGMENT_TEXT_CODE_POINTS,
            )
            if (normalizedText.isEmpty()) continue

            val segLang = seg.language?.value?.takeIf { it == targetLang || it == baseLang }
            val segLangProb = if (segLang != null) {
                seg.languageConfidence
                    ?.toDouble()
                    ?.takeIf { it.isFinite() && it in 0.0..1.0 }
                    ?.let(::roundProbability)
            } else {
                null
            }

            val normalizedVariants = seg.textVariants
                .filterKeys { it == "Hans" || it == "Hant" }
                .mapValues { (_, v) ->
                    truncateCodePoints(
                        ChunkFlowTranscriptParser.normalizeNfc(v).trim(),
                        ChunkFlowTranscriptParser.MAX_SEGMENT_TEXT_CODE_POINTS,
                    )
                }
                .filterValues { it.isNotEmpty() }
                .ifEmpty { null }

            val normalizedWords = normalizeWords(
                words = seg.words,
                segStartMs = seg.startMs,
                segEndMs = clampedEndMs,
            ).ifEmpty { null }

            result.add(
                SerializableSegment(
                    startMs = seg.startMs,
                    endMs = clampedEndMs,
                    lang = segLang,
                    langProb = segLangProb,
                    text = normalizedText,
                    textVariants = normalizedVariants,
                    words = normalizedWords,
                ),
            )
        }

        return result
    }

    private fun normalizeWords(
        words: List<WordTiming>,
        segStartMs: Long,
        segEndMs: Long,
    ): List<SerializableWord> {
        if (words.isEmpty()) return emptyList()
        val result = ArrayList<SerializableWord>(minOf(words.size, ChunkFlowTranscriptParser.MAX_WORDS_PER_SEGMENT))
        var prevEndMs = segStartMs
        for (word in words) {
            if (result.size >= ChunkFlowTranscriptParser.MAX_WORDS_PER_SEGMENT) break
            val startMs = maxOf(word.startMs, prevEndMs)
            val endMs = minOf(word.endMs, segEndMs)
            if (startMs >= endMs) continue

            val text = truncateCodePoints(
                ChunkFlowTranscriptParser.normalizeNfc(word.text).trim(),
                ChunkFlowTranscriptParser.MAX_WORD_TEXT_CODE_POINTS,
            )
            if (text.isEmpty()) continue

            val prob = word.confidence
                ?.toDouble()
                ?.takeIf { it.isFinite() && it in 0.0..1.0 }
                ?.let(::roundProbability)

            result.add(
                SerializableWord(
                    startMs = startMs,
                    endMs = endMs,
                    text = text,
                    prob = prob,
                ),
            )
            prevEndMs = endMs
        }
        return result
    }

    @Serializable
    private data class SerializableTranscriptDocument(
        @EncodeDefault val format: String,
        @EncodeDefault val version: Int,
        val audio: SerializableAudio,
        val languages: SerializableLanguages,
        val generator: SerializableGenerator,
        @EncodeDefault val segments: List<SerializableSegment>,
    )

    @Serializable
    private data class SerializableAudio(
        val fileName: String,
        val sha256: String,
        val durationMs: Long,
    )

    @Serializable
    private data class SerializableLanguages(
        val target: String,
        @EncodeDefault val base: String? = null,
    )

    @Serializable
    private data class SerializableGenerator(
        val tool: String,
        val engine: String,
        val model: String,
        val createdAt: String,
    )

    @Serializable
    private data class SerializableSegment(
        val startMs: Long,
        val endMs: Long,
        val lang: String? = null,
        val langProb: Double? = null,
        val text: String,
        val textVariants: Map<String, String>? = null,
        val words: List<SerializableWord>? = null,
    )

    @Serializable
    private data class SerializableWord(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val prob: Double? = null,
    )

    companion object {
        const val DEFAULT_TOOL: String = "chunkflow-android 1.0"
        const val DEFAULT_ENGINE: String = "whisper.cpp"
        const val DEFAULT_MODEL: String = "unknown"
        const val DEFAULT_AUDIO_FILE_NAME: String = "audio"
        const val DEFAULT_TARGET_LANG: String = "en"

        private val json = Json {
            prettyPrint = true
            explicitNulls = false
        }

        private val defaultInstance = ChunkFlowTranscriptSerializer()

        /**
         * [TranscriptDocument] を `chunkflow-transcript` v1 形式の JSON 文字列に直列化するユーティリティ関数。
         */
        fun serialize(
            document: TranscriptDocument,
            sha256: String? = document.audioSha256,
            fileName: String? = document.audioFileName,
            durationMs: Long? = document.durationMs,
            languages: LanguagePair? = document.languages,
            tool: String = DEFAULT_TOOL,
            engine: String = document.generator.value.ifBlank { DEFAULT_ENGINE },
            model: String = document.generator.value.ifBlank { DEFAULT_MODEL },
            createdAt: String? = document.createdAt,
        ): String = defaultInstance.serialize(
            document = document,
            sha256 = sha256,
            fileName = fileName,
            durationMs = durationMs,
            languages = languages,
            tool = tool,
            engine = engine,
            model = model,
            createdAt = createdAt,
        )

        /**
         * [TranscriptDocument] を `chunkflow-transcript` v1 形式の UTF-8 バイト列に直列化するユーティリティ関数。
         */
        fun serializeToBytes(
            document: TranscriptDocument,
            sha256: String? = document.audioSha256,
            fileName: String? = document.audioFileName,
            durationMs: Long? = document.durationMs,
            languages: LanguagePair? = document.languages,
            tool: String = DEFAULT_TOOL,
            engine: String = document.generator.value.ifBlank { DEFAULT_ENGINE },
            model: String = document.generator.value.ifBlank { DEFAULT_MODEL },
            createdAt: String? = document.createdAt,
        ): ByteArray = defaultInstance.serializeToBytes(
            document = document,
            sha256 = sha256,
            fileName = fileName,
            durationMs = durationMs,
            languages = languages,
            tool = tool,
            engine = engine,
            model = model,
            createdAt = createdAt,
        )

        internal fun formatEpochMsAsRfc3339Utc(epochMs: Long): String {
            val instant = Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS)
            return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }

        private fun sanitizeAudioFileName(rawName: String?): String {
            val base = rawName.orEmpty()
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .filter { it.code > 0x1F && it != '/' && it != '\\' }
                .trim()
            val normalized = ChunkFlowTranscriptParser.normalizeNfc(base).ifEmpty { DEFAULT_AUDIO_FILE_NAME }
            return truncateCodePoints(normalized, ChunkFlowTranscriptParser.MAX_FILE_NAME_CODE_POINTS)
        }

        private fun sanitizeShortText(raw: String, fallback: String): String {
            val normalized = ChunkFlowTranscriptParser.normalizeNfc(raw.trim()).ifEmpty { fallback }
            return truncateCodePoints(normalized, ChunkFlowTranscriptParser.MAX_SHORT_TEXT_CODE_POINTS)
        }

        private fun sanitizeLanguageTag(raw: String, fallback: String): String {
            val trimmed = raw.trim()
            return if (ChunkFlowTranscriptParser.isValidLanguageTag(trimmed)) {
                trimmed
            } else {
                fallback
            }
        }

        private fun truncateCodePoints(text: String, maxCodePoints: Int): String {
            val cpCount = text.codePointCount(0, text.length)
            if (cpCount <= maxCodePoints) return text
            val endOffset = text.offsetByCodePoints(0, maxCodePoints)
            return text.substring(0, endOffset)
        }

        private fun roundProbability(value: Double): Double = (value * 10_000.0).roundToLong() / 10_000.0
    }
}
