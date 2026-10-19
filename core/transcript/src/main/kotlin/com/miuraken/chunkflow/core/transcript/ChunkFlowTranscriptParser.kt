package com.miuraken.chunkflow.core.transcript

import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.WordTiming
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `chunkflow-transcript` v1 形式の JSON を解析・検証する [TranscriptParser] 実装（§7.4.3、§7.4.6、§8.1）。
 *
 * `docs/format/README.md` の「検証の順序」に従い、次の 4 段階で検証する:
 * 1. 読み込み（ファイルサイズ 20MB 以下、UTF-8 妥当性、入れ子の深さ 64 以下、JSON 構文）
 * 2. 形式名（`chunkflow-transcript`）と版（`1`）
 * 3. JSON Schema（必須項目、型、文字数・件数の上限、パターン、時刻の非負整数性）
 * 4. 意味の検証（S1〜S6: 言語ペアの相違、セグメント時刻の順序・非重複・音声長以内、言語の一致、単語時刻の整合性）
 *
 * テキスト（`text`、`textVariants` の値、`words.text`）はすべて Unicode NFC に正規化して返す。
 */
class ChunkFlowTranscriptParser : TranscriptParser {

    // TODO(security): 文字起こしファイルの署名による改ざんの検知は行わない。自分の PC と自分の端末の間でだけ受け渡すため。内容が不正な場合の影響は、検証と上限で抑える。

    override fun parse(bytes: ByteArray, fileName: String): TranscriptParseResult {
        if (fileName.isNotBlank() && !isSupportedJsonFileName(fileName)) {
            return TranscriptParseResult.Error(
                TranscriptError.UNKNOWN_FORMAT,
                "Unsupported transcript file extension",
            )
        }

        // 段階 1: 読み込み（サイズ上限、UTF-8、入れ子の深さ、JSON 構文）
        if (bytes.size > MAX_FILE_SIZE_BYTES) {
            return TranscriptParseResult.Error(
                TranscriptError.TOO_LARGE,
                "Transcript file exceeds maximum size of $MAX_FILE_SIZE_BYTES bytes",
            )
        }
        val text = decodeUtf8Strict(bytes)
            ?: return TranscriptParseResult.Error(
                TranscriptError.SCHEMA_VIOLATION,
                "Invalid UTF-8 byte sequence",
            )

        if (maxNestingDepth(text) > MAX_NESTING_DEPTH) {
            return TranscriptParseResult.Error(
                TranscriptError.TOO_LARGE,
                "JSON nesting depth exceeds limit of $MAX_NESTING_DEPTH",
            )
        }

        val rootElement = try {
            json.parseToJsonElement(text)
        } catch (_: SerializationException) {
            return TranscriptParseResult.Error(
                TranscriptError.SCHEMA_VIOLATION,
                "Malformed JSON syntax",
            )
        } catch (_: IllegalArgumentException) {
            return TranscriptParseResult.Error(
                TranscriptError.SCHEMA_VIOLATION,
                "Malformed JSON syntax",
            )
        }

        val root = rootElement as? JsonObject
            ?: return TranscriptParseResult.Error(
                TranscriptError.SCHEMA_VIOLATION,
                "Root JSON value must be an object",
            )

        // 段階 2: 形式と版
        val formatName = root.getStrictString("format")
            ?: return TranscriptParseResult.Error(
                TranscriptError.UNKNOWN_FORMAT,
                "Missing or non-string 'format' field",
            )
        if (formatName != FORMAT_NAME) {
            return TranscriptParseResult.Error(
                TranscriptError.UNKNOWN_FORMAT,
                "Unsupported format '$formatName'",
            )
        }

        val versionElement = root["version"]
            ?: return TranscriptParseResult.Error(
                TranscriptError.SCHEMA_VIOLATION,
                "Missing 'version' field",
            )
        val version = versionElement.asStrictLong()
            ?: return TranscriptParseResult.Error(
                TranscriptError.SCHEMA_VIOLATION,
                "'version' must be an integer",
            )
        if (version != SUPPORTED_VERSION.toLong()) {
            return TranscriptParseResult.Error(
                TranscriptError.UNSUPPORTED_VERSION,
                "Unsupported transcript version: $version",
            )
        }

        // 段階 3: JSON Schema の検証
        val schemaError = validateSchema(root)
        if (schemaError != null) {
            return schemaError
        }

        val rawDoc = try {
            json.decodeFromJsonElement(RawTranscriptDocument.serializer(), root)
        } catch (_: SerializationException) {
            return TranscriptParseResult.Error(
                TranscriptError.SCHEMA_VIOLATION,
                "Failed to decode transcript document",
            )
        } catch (_: IllegalArgumentException) {
            return TranscriptParseResult.Error(
                TranscriptError.SCHEMA_VIOLATION,
                "Failed to decode transcript document",
            )
        }

        // 段階 4: 意味の検証（S1〜S6）と NFC 正規化
        return validateSemanticsAndBuildDocument(rawDoc)
    }

    private fun validateSchema(root: JsonObject): TranscriptParseResult.Error? {
        val audioObj = root["audio"] as? JsonObject
            ?: return schemaViolation("Missing or invalid 'audio' object")
        val languagesObj = root["languages"] as? JsonObject
            ?: return schemaViolation("Missing or invalid 'languages' object")
        val generatorObj = root["generator"] as? JsonObject
            ?: return schemaViolation("Missing or invalid 'generator' object")
        val segmentsArray = root["segments"] as? JsonArray
            ?: return schemaViolation("Missing or invalid 'segments' array")

        // audio
        val fileName = audioObj.getStrictString("fileName")
            ?: return schemaViolation("Missing or invalid 'audio.fileName'")
        if (!isValidAudioFileName(fileName)) {
            return schemaViolation("Invalid 'audio.fileName'")
        }
        val sha256 = audioObj.getStrictString("sha256")
            ?: return schemaViolation("Missing or invalid 'audio.sha256'")
        if (!isValidSha256(sha256)) {
            return schemaViolation("Invalid 'audio.sha256'")
        }
        val durationMs = when (val durationResult = parseTimeMs(audioObj["durationMs"], "audio.durationMs")) {
            is TimeParseOutcome.Ok -> durationResult.value
            is TimeParseOutcome.Fail -> return durationResult.error
        }
        if (durationMs <= 0L) {
            return schemaViolation("'audio.durationMs' must be positive")
        }

        // languages
        val targetLang = languagesObj.getStrictString("target")
            ?: return schemaViolation("Missing or invalid 'languages.target'")
        if (!isValidLanguageTag(targetLang)) {
            return schemaViolation("Invalid 'languages.target' BCP-47 tag")
        }
        val baseEl = languagesObj["base"]
        if (baseEl != null && baseEl !is JsonNull) {
            val baseLang = baseEl.asStrictString()
                ?: return schemaViolation("'languages.base' must be a string or null")
            if (!isValidLanguageTag(baseLang)) {
                return schemaViolation("Invalid 'languages.base' BCP-47 tag")
            }
        }

        // generator
        val tool = generatorObj.getStrictString("tool")
            ?: return schemaViolation("Missing or invalid 'generator.tool'")
        if (!isValidShortText(tool)) {
            return schemaViolation("Invalid 'generator.tool' length")
        }
        val engine = generatorObj.getStrictString("engine")
            ?: return schemaViolation("Missing or invalid 'generator.engine'")
        if (!isValidShortText(engine)) {
            return schemaViolation("Invalid 'generator.engine' length")
        }
        val model = generatorObj.getStrictString("model")
            ?: return schemaViolation("Missing or invalid 'generator.model'")
        if (!isValidShortText(model)) {
            return schemaViolation("Invalid 'generator.model' length")
        }
        val createdAt = generatorObj.getStrictString("createdAt")
            ?: return schemaViolation("Missing or invalid 'generator.createdAt'")
        if (!isValidRfc3339(createdAt)) {
            return schemaViolation("Invalid 'generator.createdAt' timestamp")
        }
        val modelRepoEl = generatorObj["modelRepo"]
        if (modelRepoEl != null) {
            val modelRepo = modelRepoEl.asStrictString()
                ?: return schemaViolation("'generator.modelRepo' must be a string")
            if (!isValidShortText(modelRepo)) {
                return schemaViolation("Invalid 'generator.modelRepo' length")
            }
        }
        val modelRevisionEl = generatorObj["modelRevision"]
        if (modelRevisionEl != null) {
            val modelRevision = modelRevisionEl.asStrictString()
                ?: return schemaViolation("'generator.modelRevision' must be a string")
            if (!isValidShortText(modelRevision)) {
                return schemaViolation("Invalid 'generator.modelRevision' length")
            }
        }
        val vadEl = generatorObj["vad"]
        if (vadEl != null && vadEl !is JsonObject) {
            return schemaViolation("'generator.vad' must be an object")
        }

        // segments
        if (segmentsArray.size > MAX_SEGMENTS) {
            return TranscriptParseResult.Error(
                TranscriptError.TOO_LARGE,
                "Segments count (${segmentsArray.size}) exceeds maximum of $MAX_SEGMENTS",
            )
        }

        for ((index, segEl) in segmentsArray.withIndex()) {
            val segObj = segEl as? JsonObject
                ?: return schemaViolation("segments[$index] must be an object")

            when (val startRes = parseTimeMs(segObj["startMs"], "segments[$index].startMs")) {
                is TimeParseOutcome.Ok -> Unit
                is TimeParseOutcome.Fail -> return startRes.error
            }
            when (val endRes = parseTimeMs(segObj["endMs"], "segments[$index].endMs")) {
                is TimeParseOutcome.Ok -> Unit
                is TimeParseOutcome.Fail -> return endRes.error
            }

            val rawText = segObj.getStrictString("text")
                ?: return schemaViolation("Missing or invalid 'segments[$index].text'")
            val textError = validateSegmentTextLength(rawText, "segments[$index].text")
            if (textError != null) return textError

            val langEl = segObj["lang"]
            if (langEl != null && langEl !is JsonNull) {
                val langStr = langEl.asStrictString()
                    ?: return schemaViolation("'segments[$index].lang' must be a string or null")
                if (!isValidLanguageTag(langStr)) {
                    return schemaViolation("Invalid 'segments[$index].lang' BCP-47 tag")
                }
            }

            val langProbEl = segObj["langProb"]
            if (langProbEl != null && langProbEl !is JsonNull) {
                val prob = langProbEl.asStrictDouble()
                    ?: return schemaViolation("'segments[$index].langProb' must be a number or null")
                if (!prob.isFinite() || prob < 0.0 || prob > 1.0) {
                    return schemaViolation("'segments[$index].langProb' out of 0..1 range")
                }
            }

            val variantsEl = segObj["textVariants"]
            if (variantsEl != null) {
                val variantsObj = variantsEl as? JsonObject
                    ?: return schemaViolation("'segments[$index].textVariants' must be an object")
                if (variantsObj.size > MAX_TEXT_VARIANTS) {
                    return schemaViolation("'segments[$index].textVariants' exceeds max properties")
                }
                for ((variantKey, variantValEl) in variantsObj) {
                    if (variantKey != "Hans" && variantKey != "Hant") {
                        return schemaViolation("Unsupported textVariants key '$variantKey'")
                    }
                    val variantText = variantValEl.asStrictString()
                        ?: return schemaViolation("'segments[$index].textVariants.$variantKey' must be a string")
                    val variantError = validateSegmentTextLength(
                        variantText,
                        "segments[$index].textVariants.$variantKey",
                    )
                    if (variantError != null) return variantError
                }
            }

            val wordsEl = segObj["words"]
            if (wordsEl != null) {
                val wordsArray = wordsEl as? JsonArray
                    ?: return schemaViolation("'segments[$index].words' must be an array")
                if (wordsArray.size > MAX_WORDS_PER_SEGMENT) {
                    return TranscriptParseResult.Error(
                        TranscriptError.TOO_LARGE,
                        "segments[$index].words exceeds maximum of $MAX_WORDS_PER_SEGMENT items",
                    )
                }
                for ((wordIdx, wordEl) in wordsArray.withIndex()) {
                    val wordObj = wordEl as? JsonObject
                        ?: return schemaViolation("segments[$index].words[$wordIdx] must be an object")
                    when (val wsRes = parseTimeMs(wordObj["startMs"], "segments[$index].words[$wordIdx].startMs")) {
                        is TimeParseOutcome.Ok -> Unit
                        is TimeParseOutcome.Fail -> return wsRes.error
                    }
                    when (val weRes = parseTimeMs(wordObj["endMs"], "segments[$index].words[$wordIdx].endMs")) {
                        is TimeParseOutcome.Ok -> Unit
                        is TimeParseOutcome.Fail -> return weRes.error
                    }
                    val wordText = wordObj.getStrictString("text")
                        ?: return schemaViolation("Missing 'segments[$index].words[$wordIdx].text'")
                    val wordCp = wordText.codePointCount(0, wordText.length)
                    if (wordCp == 0 || wordCp > MAX_WORD_TEXT_CODE_POINTS) {
                        return schemaViolation("Invalid 'segments[$index].words[$wordIdx].text' length")
                    }
                    val trimmedWord = normalizeNfc(wordText).trim()
                    val trimmedWordCp = trimmedWord.codePointCount(0, trimmedWord.length)
                    if (trimmedWordCp == 0 || trimmedWordCp > MAX_WORD_TEXT_CODE_POINTS) {
                        return schemaViolation("Invalid normalized 'segments[$index].words[$wordIdx].text' length")
                    }
                    val probEl = wordObj["prob"]
                    if (probEl != null && probEl !is JsonNull) {
                        val wProb = probEl.asStrictDouble()
                            ?: return schemaViolation("'segments[$index].words[$wordIdx].prob' must be a number or null")
                        if (!wProb.isFinite() || wProb < 0.0 || wProb > 1.0) {
                            return schemaViolation("'segments[$index].words[$wordIdx].prob' out of 0..1 range")
                        }
                    }
                }
            }
        }

        return null
    }

    private fun validateSemanticsAndBuildDocument(raw: RawTranscriptDocument): TranscriptParseResult {
        val targetLang = raw.languages.target
        val baseLang = raw.languages.base

        // S1: languages.base があるとき、languages.target と異なる
        if (baseLang != null && baseLang == targetLang) {
            return schemaViolation("S1: languages.target and languages.base must be different")
        }

        val durationMs = raw.audio.durationMs
        var prevEndMs: Long? = null
        val convertedSegments = ArrayList<TranscriptSegment>(raw.segments.size)

        for ((index, seg) in raw.segments.withIndex()) {
            // S2: startMs < endMs
            if (seg.startMs >= seg.endMs) {
                return schemaViolation("S2: segments[$index] startMs (${seg.startMs}) >= endMs (${seg.endMs})")
            }
            // S3: 開始時刻の順で重ならない
            val previousEnd = prevEndMs
            if (previousEnd != null && seg.startMs < previousEnd) {
                return schemaViolation("S3: segments[$index] overlaps or is unsorted")
            }
            prevEndMs = seg.endMs

            // S4: 各セグメントの endMs <= audio.durationMs
            if (seg.endMs > durationMs) {
                return TranscriptParseResult.Error(
                    TranscriptError.TIME_OUT_OF_RANGE,
                    "S4: segments[$index].endMs (${seg.endMs}) exceeds audio.durationMs ($durationMs)",
                )
            }

            // S5: lang は languages.target か languages.base と完全に一致するか null
            val segLang = seg.lang
            if (segLang != null && segLang != targetLang && segLang != baseLang) {
                return schemaViolation("S5: segments[$index].lang '$segLang' is not in language pair")
            }

            // S6: words の各単語で startMs < endMs、セグメントの範囲内、startMs の順で重ならない
            var prevWordEnd = seg.startMs
            val convertedWords = if (seg.words.isEmpty()) {
                emptyList()
            } else {
                ArrayList<WordTiming>(seg.words.size)
            }
            for ((wordIdx, word) in seg.words.withIndex()) {
                if (word.startMs >= word.endMs || word.startMs < prevWordEnd || word.endMs > seg.endMs) {
                    return schemaViolation("S6: segments[$index].words[$wordIdx] timing is invalid")
                }
                prevWordEnd = word.endMs
                if (convertedWords is ArrayList) {
                    convertedWords.add(
                        WordTiming(
                            startMs = word.startMs,
                            endMs = word.endMs,
                            text = normalizeNfc(word.text).trim(),
                            confidence = word.prob?.toFloat(),
                        ),
                    )
                }
            }

            val normalizedText = normalizeNfc(seg.text)
            val normalizedVariants = if (seg.textVariants.isEmpty()) {
                emptyMap()
            } else {
                seg.textVariants.mapValues { (_, v) -> normalizeNfc(v) }
            }

            // docs/format/README.md: lang が null のときは langProb を無視する
            val confidence = if (segLang == null) null else seg.langProb?.toFloat()

            convertedSegments.add(
                TranscriptSegment(
                    startMs = seg.startMs,
                    endMs = seg.endMs,
                    language = segLang?.let(::LanguageTag),
                    languageConfidence = confidence,
                    text = normalizedText,
                    textVariants = normalizedVariants,
                    words = convertedWords,
                ),
            )
        }

        val document = TranscriptDocument(
            audioSha256 = raw.audio.sha256,
            audioFileName = normalizeNfc(raw.audio.fileName),
            durationMs = durationMs,
            languages = LanguagePair(
                target = LanguageTag(targetLang),
                base = baseLang?.let(::LanguageTag),
            ),
            generator = EngineId(raw.generator.model),
            segments = convertedSegments,
            createdAt = raw.generator.createdAt,
        )
        return TranscriptParseResult.Ok(document)
    }

    private fun validateSegmentTextLength(rawText: String, fieldPath: String): TranscriptParseResult.Error? {
        val rawCp = rawText.codePointCount(0, rawText.length)
        if (rawCp == 0) {
            return schemaViolation("'$fieldPath' must not be empty")
        }
        if (rawCp > MAX_SEGMENT_TEXT_CODE_POINTS) {
            return TranscriptParseResult.Error(
                TranscriptError.TOO_LARGE,
                "'$fieldPath' length ($rawCp) exceeds maximum of $MAX_SEGMENT_TEXT_CODE_POINTS code points",
            )
        }
        val normalized = normalizeNfc(rawText)
        val normCp = normalized.codePointCount(0, normalized.length)
        if (normCp == 0) {
            return schemaViolation("'$fieldPath' must not be empty after NFC normalization")
        }
        if (normCp > MAX_SEGMENT_TEXT_CODE_POINTS) {
            return TranscriptParseResult.Error(
                TranscriptError.TOO_LARGE,
                "'$fieldPath' normalized length ($normCp) exceeds maximum of $MAX_SEGMENT_TEXT_CODE_POINTS code points",
            )
        }
        return null
    }

    private fun parseTimeMs(element: JsonElement?, fieldPath: String): TimeParseOutcome {
        if (element == null) {
            return TimeParseOutcome.Fail(schemaViolation("Missing '$fieldPath'"))
        }
        val value = element.asStrictLong()
            ?: return TimeParseOutcome.Fail(schemaViolation("'$fieldPath' must be an integer"))
        if (value < 0L) {
            return TimeParseOutcome.Fail(
                TranscriptParseResult.Error(
                    TranscriptError.TIME_OUT_OF_RANGE,
                    "'$fieldPath' must not be negative ($value)",
                ),
            )
        }
        if (value > MAX_SAFE_INTEGER_MS) {
            return TimeParseOutcome.Fail(schemaViolation("'$fieldPath' exceeds max safe integer"))
        }
        return TimeParseOutcome.Ok(value)
    }

    private sealed interface TimeParseOutcome {
        data class Ok(val value: Long) : TimeParseOutcome
        data class Fail(val error: TranscriptParseResult.Error) : TimeParseOutcome
    }

    private fun schemaViolation(detail: String): TranscriptParseResult.Error = TranscriptParseResult.Error(TranscriptError.SCHEMA_VIOLATION, detail)

    @Serializable
    private data class RawTranscriptDocument(
        val format: String,
        val version: Int,
        val audio: RawAudio,
        val languages: RawLanguages,
        val generator: RawGenerator,
        val segments: List<RawSegment>,
    )

    @Serializable
    private data class RawAudio(
        val fileName: String,
        val sha256: String,
        val durationMs: Long,
    )

    @Serializable
    private data class RawLanguages(
        val target: String,
        val base: String? = null,
    )

    @Serializable
    private data class RawGenerator(
        val tool: String,
        val engine: String,
        val model: String,
        val createdAt: String,
    )

    @Serializable
    private data class RawSegment(
        val startMs: Long,
        val endMs: Long,
        val lang: String? = null,
        val langProb: Double? = null,
        val text: String,
        val textVariants: Map<String, String> = emptyMap(),
        val words: List<RawWord> = emptyList(),
    )

    @Serializable
    private data class RawWord(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val prob: Double? = null,
    )

    companion object {
        /** 1 ファイルあたりの最大バイト数: 20 MB（§8.1）。 */
        const val MAX_FILE_SIZE_BYTES: Int = 20 * 1024 * 1024

        /** セグメント数の上限: 50,000 件（§8.1）。 */
        const val MAX_SEGMENTS: Int = 50_000

        /** 1 セグメントあたりの最大文字数（Unicode コードポイント数）: 2,000 文字（§8.1）。 */
        const val MAX_SEGMENT_TEXT_CODE_POINTS: Int = 2_000

        /** 1 セグメントあたりの最大単語数: 2,000 件。 */
        const val MAX_WORDS_PER_SEGMENT: Int = 2_000

        /** 1 単語あたりの最大文字数（Unicode コードポイント数）: 200 文字。 */
        const val MAX_WORD_TEXT_CODE_POINTS: Int = 200

        /** 短い文字列項目（`generator.*` など）の最大文字数: 200 文字。 */
        const val MAX_SHORT_TEXT_CODE_POINTS: Int = 200

        /** `audio.fileName` の最大文字数: 255 文字。 */
        const val MAX_FILE_NAME_CODE_POINTS: Int = 255

        /** JSON の括弧の最大入れ子深さ: 64（`docs/format/README.md`）。 */
        const val MAX_NESTING_DEPTH: Int = 64

        /** `textVariants` の最大プロパティ数: 4。 */
        private const val MAX_TEXT_VARIANTS: Int = 4

        /** `createdAt` の最大文字数: 64。 */
        private const val MAX_CREATED_AT_LENGTH: Int = 64

        /** `timeMs` の最大値（IEEE 754 倍精度の最大安全整数）。 */
        private const val MAX_SAFE_INTEGER_MS: Long = 9_007_199_254_740_991L

        /** 形式名。 */
        const val FORMAT_NAME: String = "chunkflow-transcript"

        /** 対応する形式の版。 */
        const val SUPPORTED_VERSION: Int = 1

        private val json = Json {
            ignoreUnknownKeys = true
        }

        /**
         * `chunkflow-transcript` JSON のバイト列から `generator.createdAt`（RFC 3339 文字列）を取り出す（§7.4.6）。
         *
         * 取り出せない場合や不正な値の場合は `null` を返す。
         */
        fun extractCreatedAt(bytes: ByteArray): String? {
            if (bytes.isEmpty() || bytes.size > MAX_FILE_SIZE_BYTES) return null
            val text = decodeUtf8Strict(bytes) ?: return null
            if (maxNestingDepth(text) > MAX_NESTING_DEPTH) return null
            val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
            val generatorObj = root["generator"] as? JsonObject ?: return null
            val createdAt = generatorObj.getStrictString("createdAt") ?: return null
            return createdAt.takeIf(::isValidRfc3339)
        }

        /**
         * RFC 3339 形式の `createdAt` 文字列をエポックミリ秒に変換する（§7.4.6）。
         *
         * 解析できない場合は `null` を返す。
         */
        fun parseCreatedAtEpochMs(createdAt: String?): Long? {
            if (createdAt == null || !isValidRfc3339(createdAt)) return null
            return try {
                OffsetDateTime.parse(createdAt).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            } catch (_: ArithmeticException) {
                null
            }
        }

        /**
         * `chunkflow-transcript` JSON のバイト列から `generator.createdAt` をエポックミリ秒として取り出す（§7.4.6）。
         */
        fun extractCreatedAtEpochMs(bytes: ByteArray): Long? = parseCreatedAtEpochMs(extractCreatedAt(bytes))

        internal fun normalizeNfc(value: String): String = if (Normalizer.isNormalized(value, Normalizer.Form.NFC)) {
            value
        } else {
            Normalizer.normalize(value, Normalizer.Form.NFC)
        }

        private fun isSupportedJsonFileName(fileName: String): Boolean {
            val name = fileName.substringAfterLast('/').substringAfterLast('\\')
            if (!name.contains('.')) return true
            return name.endsWith(".json", ignoreCase = true)
        }

        private fun decodeUtf8Strict(bytes: ByteArray): String? {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                null
            }
        }

        /**
         * JSON の文字列リテラルの外にある括弧の入れ子の深さを、再帰を使わず線形時間で数える。
         */
        internal fun maxNestingDepth(text: String): Int {
            var depth = 0
            var deepest = 0
            var inString = false
            var escaped = false
            for (i in 0 until text.length) {
                val ch = text[i]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        inString = false
                    }
                } else {
                    when (ch) {
                        '"' -> inString = true

                        '[', '{' -> {
                            depth += 1
                            if (depth > deepest) {
                                deepest = depth
                            }
                            if (deepest > MAX_NESTING_DEPTH) {
                                return deepest
                            }
                        }

                        ']', '}' -> {
                            depth -= 1
                        }
                    }
                }
            }
            return deepest
        }

        internal fun isValidAudioFileName(fileName: String): Boolean {
            if (fileName.isEmpty()) return false
            val cpCount = fileName.codePointCount(0, fileName.length)
            if (cpCount !in 1..MAX_FILE_NAME_CODE_POINTS) return false
            for (i in 0 until fileName.length) {
                val ch = fileName[i]
                if (ch == '/' || ch == '\\' || ch.code in 0x00..0x1F) {
                    return false
                }
            }
            return true
        }

        internal fun isValidSha256(sha256: String): Boolean {
            if (sha256.length != 64) return false
            for (i in 0 until 64) {
                val ch = sha256[i]
                val isHexLower = (ch in '0'..'9') || (ch in 'a'..'f')
                if (!isHexLower) return false
            }
            return true
        }

        /**
         * BCP-47 言語タグの形式（`^[a-z]{2,3}(-[A-Za-z0-9]{1,8})*$`、最大 35 文字）を線形時間で検証する。
         */
        internal fun isValidLanguageTag(tag: String): Boolean {
            if (tag.isEmpty() || tag.length > 35) return false
            val parts = tag.split('-')
            val primary = parts[0]
            if (primary.length !in 2..3 || !primary.all { it in 'a'..'z' }) return false
            for (i in 1 until parts.size) {
                val subtag = parts[i]
                if (subtag.length !in 1..8 || !subtag.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) {
                    return false
                }
            }
            return true
        }

        private fun isValidShortText(value: String): Boolean {
            if (value.isEmpty()) return false
            val cpCount = value.codePointCount(0, value.length)
            return cpCount in 1..MAX_SHORT_TEXT_CODE_POINTS
        }

        /**
         * RFC 3339 日時文字列（`YYYY-MM-DDTHH:MM:SS(.sss...)(Z|[+-]HH:MM)`、最大 64 文字）を検証する。
         */
        internal fun isValidRfc3339(value: String): Boolean {
            if (value.length !in 20..MAX_CREATED_AT_LENGTH) return false
            // YYYY-MM-DDTHH:MM:SS の基本構造を線形検査する
            if (value[4] != '-' || value[7] != '-' || value[10] != 'T' || value[13] != ':' || value[16] != ':') {
                return false
            }
            val dateTimeDigitsIndices = intArrayOf(0, 1, 2, 3, 5, 6, 8, 9, 11, 12, 14, 15, 17, 18)
            for (idx in dateTimeDigitsIndices) {
                if (value[idx] !in '0'..'9') return false
            }
            var pos = 19
            if (pos < value.length && value[pos] == '.') {
                pos += 1
                val fracStart = pos
                while (pos < value.length && value[pos] in '0'..'9') {
                    pos += 1
                }
                if (pos == fracStart) return false
            }
            if (pos >= value.length) return false
            when (value[pos]) {
                'Z' -> if (pos + 1 != value.length) return false

                '+', '-' -> {
                    if (pos + 6 != value.length) return false
                    if (value[pos + 3] != ':') return false
                    if (value[pos + 1] !in '0'..'9' ||
                        value[pos + 2] !in '0'..'9' ||
                        value[pos + 4] !in '0'..'9' ||
                        value[pos + 5] !in '0'..'9'
                    ) {
                        return false
                    }
                }

                else -> return false
            }
            return try {
                OffsetDateTime.parse(value)
                true
            } catch (_: DateTimeParseException) {
                false
            }
        }

        private fun JsonObject.getStrictString(key: String): String? = this[key]?.asStrictString()

        private fun JsonElement.asStrictString(): String? {
            val prim = this as? JsonPrimitive ?: return null
            return if (prim.isString) prim.content else null
        }

        private fun JsonElement.asStrictLong(): Long? {
            val prim = this as? JsonPrimitive ?: return null
            if (prim.isString) return null
            val content = prim.content
            if (content.isEmpty()) return null
            val startIdx = if (content[0] == '-') 1 else 0
            if (startIdx >= content.length) return null
            for (i in startIdx until content.length) {
                if (content[i] !in '0'..'9') return null
            }
            return content.toLongOrNull()
        }

        private fun JsonElement.asStrictDouble(): Double? {
            val prim = this as? JsonPrimitive ?: return null
            if (prim.isString) return null
            return prim.content.toDoubleOrNull()
        }
    }
}
