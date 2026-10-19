package com.miuraken.chunkflow.core.transcript

import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguageTag
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * SRT（`.srt`）、WebVTT（`.vtt`）、LRC（`.lrc`）、TSV（`.tsv`）形式の字幕ファイルを解析・検証する [TranscriptParser] 実装（F1.4、§7.4.6、§8.1）。
 *
 * - 入力サイズ上限 20MB、セグメント数上限 50,000 件、1 セグメントあたり 2,000 コードポイントの上限を強制する（§8.1）。
 * - 厳格な UTF-8 デコードを行い、先頭の UTF-8 BOM（`\uFEFF`）を除去する。
 * - HTML / WebVTT / LRC のインラインタグ（`<b>`, `<i>`, `<c.color>`, `<00:01.00>` など）を線形時間で除去し、
 *   Unicode NFC 正規化を適用する。タグ除去後に空となったキューはスキップし、有効なキューが 1 件も残らない場合は
 *   [TranscriptError.SCHEMA_VIOLATION] を返す。
 * - 重なりがあるセグメントは開始時刻順に整列し、前のセグメントの `endMs` を次の `startMs` にクランプする。
 */
class SubtitleTranscriptParser : TranscriptParser {

    override fun parse(bytes: ByteArray, fileName: String): TranscriptParseResult {
        if (bytes.size > MAX_FILE_SIZE_BYTES) {
            return TranscriptParseResult.Error(
                TranscriptError.TOO_LARGE,
                "Subtitle file exceeds maximum size of $MAX_FILE_SIZE_BYTES bytes",
            )
        }

        val decoded = decodeUtf8Strict(bytes)
            ?: return TranscriptParseResult.Error(
                TranscriptError.UNKNOWN_FORMAT,
                "Invalid UTF-8 byte sequence",
            )

        val text = decoded.removePrefix(UTF8_BOM)
        val format = detectFormat(fileName, text)
            ?: return TranscriptParseResult.Error(
                TranscriptError.UNKNOWN_FORMAT,
                "Unsupported or unrecognized subtitle format",
            )

        val rawCuesResult = when (format) {
            SubtitleFormat.SRT -> parseSrt(text)
            SubtitleFormat.VTT -> parseVtt(text)
            SubtitleFormat.LRC -> parseLrc(text)
            SubtitleFormat.TSV -> parseTsv(text)
        }

        val rawCues = when (rawCuesResult) {
            is RawCueParseOutcome.Error -> return rawCuesResult.error
            is RawCueParseOutcome.Ok -> rawCuesResult.cues
        }

        if (rawCues.isEmpty()) {
            return TranscriptParseResult.Error(
                TranscriptError.SCHEMA_VIOLATION,
                "No non-empty subtitle cues found",
            )
        }

        val segments = sortAndClampSegments(rawCues)
        if (segments.isEmpty()) {
            return TranscriptParseResult.Error(
                TranscriptError.SCHEMA_VIOLATION,
                "No valid subtitle segments remained after clamping",
            )
        }

        val stem = extractAudioStem(fileName)
        return TranscriptParseResult.Ok(
            TranscriptDocument(
                audioSha256 = null,
                audioFileName = stem,
                durationMs = null,
                languages = null,
                generator = EngineId("subtitle-${format.id}"),
                segments = segments,
                createdAt = null,
            ),
        )
    }

    private fun parseSrt(text: String): RawCueParseOutcome {
        val lines = normalizeLineEndings(text).split('\n')
        val cues = mutableListOf<RawCue>()
        var i = 0
        var sawAnyBlock = false

        while (i < lines.size) {
            while (i < lines.size && lines[i].isBlank()) {
                i++
            }
            if (i >= lines.size) break
            sawAnyBlock = true

            var timingLine = lines[i].trim()
            if (!timingLine.contains("-->")) {
                // 1 行目がキュー番号の場合は次の行をタイムスタンプ行として扱う
                if (!isSrtCueNumber(timingLine) || i + 1 >= lines.size) {
                    return RawCueParseOutcome.Error(
                        TranscriptParseResult.Error(
                            TranscriptError.SCHEMA_VIOLATION,
                            "Invalid SRT cue header",
                        ),
                    )
                }
                i++
                timingLine = lines[i].trim()
            }

            val timing = parseArrowTimingLine(timingLine, requireHours = true)
                ?: return RawCueParseOutcome.Error(
                    TranscriptParseResult.Error(
                        TranscriptError.SCHEMA_VIOLATION,
                        "Invalid SRT timestamp line",
                    ),
                )

            if (timing.startMs < 0L || timing.startMs >= timing.endMs) {
                return RawCueParseOutcome.Error(
                    TranscriptParseResult.Error(
                        TranscriptError.TIME_OUT_OF_RANGE,
                        "SRT cue timestamp out of range: ${timing.startMs} --> ${timing.endMs}",
                    ),
                )
            }

            i++
            val payloadLines = mutableListOf<String>()
            var rawCharCount = 0
            while (i < lines.size && lines[i].isNotBlank()) {
                val line = lines[i]
                rawCharCount += line.length
                if (rawCharCount > MAX_RAW_CUE_CHARS) {
                    return RawCueParseOutcome.Error(
                        TranscriptParseResult.Error(
                            TranscriptError.SCHEMA_VIOLATION,
                            "SRT cue text exceeds maximum allowed length",
                        ),
                    )
                }
                payloadLines.add(line.trim())
                i++
            }

            val cleaned = cleanAndNormalizeCueText(payloadLines.joinToString("\n"))
            when (cleaned) {
                is CleanedTextOutcome.TooLong -> return RawCueParseOutcome.Error(cleaned.error)

                is CleanedTextOutcome.Empty -> continue

                is CleanedTextOutcome.Ok -> {
                    if (cues.size >= MAX_SEGMENTS) {
                        return RawCueParseOutcome.Error(
                            TranscriptParseResult.Error(
                                TranscriptError.TOO_LARGE,
                                "Subtitle segments exceed maximum of $MAX_SEGMENTS",
                            ),
                        )
                    }
                    cues.add(RawCue(startMs = timing.startMs, endMs = timing.endMs, text = cleaned.text))
                }
            }
        }

        if (!sawAnyBlock) {
            return RawCueParseOutcome.Error(
                TranscriptParseResult.Error(
                    TranscriptError.SCHEMA_VIOLATION,
                    "Empty SRT file",
                ),
            )
        }
        return RawCueParseOutcome.Ok(cues)
    }

    private fun parseVtt(text: String): RawCueParseOutcome {
        val normalized = normalizeLineEndings(text)
        val lines = normalized.split('\n')
        if (lines.isEmpty() || !isValidWebVttHeader(lines[0])) {
            return RawCueParseOutcome.Error(
                TranscriptParseResult.Error(
                    TranscriptError.SCHEMA_VIOLATION,
                    "Missing or invalid WEBVTT header",
                ),
            )
        }

        var i = 1
        // ヘッダメタデータ領域（最初の空行まで）をスキップする（ただし `-->` を含む場合はキュー開始とみなす）
        while (i < lines.size && lines[i].isNotBlank() && !lines[i].contains("-->")) {
            i++
        }

        val cues = mutableListOf<RawCue>()
        while (i < lines.size) {
            while (i < lines.size && lines[i].isBlank()) {
                i++
            }
            if (i >= lines.size) break

            val firstLine = lines[i].trim()
            if (isWebVttIgnoredBlockHeader(firstLine)) {
                i++
                while (i < lines.size && lines[i].isNotBlank()) {
                    i++
                }
                continue
            }

            var timingLine = firstLine
            if (!timingLine.contains("-->")) {
                if (i + 1 >= lines.size) {
                    return RawCueParseOutcome.Error(
                        TranscriptParseResult.Error(
                            TranscriptError.SCHEMA_VIOLATION,
                            "Unexpected line in WebVTT without timing arrow",
                        ),
                    )
                }
                i++
                timingLine = lines[i].trim()
            }

            val timing = parseArrowTimingLine(timingLine, requireHours = false)
                ?: return RawCueParseOutcome.Error(
                    TranscriptParseResult.Error(
                        TranscriptError.SCHEMA_VIOLATION,
                        "Invalid WebVTT timestamp line",
                    ),
                )

            if (timing.startMs < 0L || timing.startMs >= timing.endMs) {
                return RawCueParseOutcome.Error(
                    TranscriptParseResult.Error(
                        TranscriptError.TIME_OUT_OF_RANGE,
                        "WebVTT cue timestamp out of range: ${timing.startMs} --> ${timing.endMs}",
                    ),
                )
            }

            i++
            val payloadLines = mutableListOf<String>()
            var rawCharCount = 0
            while (i < lines.size && lines[i].isNotBlank()) {
                val line = lines[i]
                rawCharCount += line.length
                if (rawCharCount > MAX_RAW_CUE_CHARS) {
                    return RawCueParseOutcome.Error(
                        TranscriptParseResult.Error(
                            TranscriptError.SCHEMA_VIOLATION,
                            "WebVTT cue text exceeds maximum allowed length",
                        ),
                    )
                }
                payloadLines.add(line.trim())
                i++
            }

            val cleaned = cleanAndNormalizeCueText(payloadLines.joinToString("\n"))
            when (cleaned) {
                is CleanedTextOutcome.TooLong -> return RawCueParseOutcome.Error(cleaned.error)

                is CleanedTextOutcome.Empty -> continue

                is CleanedTextOutcome.Ok -> {
                    if (cues.size >= MAX_SEGMENTS) {
                        return RawCueParseOutcome.Error(
                            TranscriptParseResult.Error(
                                TranscriptError.TOO_LARGE,
                                "Subtitle segments exceed maximum of $MAX_SEGMENTS",
                            ),
                        )
                    }
                    cues.add(RawCue(startMs = timing.startMs, endMs = timing.endMs, text = cleaned.text))
                }
            }
        }

        return RawCueParseOutcome.Ok(cues)
    }

    private fun parseLrc(text: String): RawCueParseOutcome {
        val lines = normalizeLineEndings(text).split('\n')
        val timedEntries = mutableListOf<LrcTimedEntry>()
        var lastTimestampMs: Long? = null

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.length > MAX_RAW_CUE_CHARS) {
                return RawCueParseOutcome.Error(
                    TranscriptParseResult.Error(
                        TranscriptError.SCHEMA_VIOLATION,
                        "LRC line exceeds maximum allowed length",
                    ),
                )
            }

            val parsedLine = parseLrcLine(line)
                ?: return RawCueParseOutcome.Error(
                    TranscriptParseResult.Error(
                        TranscriptError.SCHEMA_VIOLATION,
                        "Invalid LRC line syntax",
                    ),
                )

            when (parsedLine) {
                is LrcLineOutcome.Metadata -> continue

                is LrcLineOutcome.Lyric -> {
                    val cleaned = cleanAndNormalizeCueText(parsedLine.rawText)
                    val normalizedText = when (cleaned) {
                        is CleanedTextOutcome.TooLong -> return RawCueParseOutcome.Error(cleaned.error)
                        is CleanedTextOutcome.Empty -> null
                        is CleanedTextOutcome.Ok -> cleaned.text
                    }

                    for (ts in parsedLine.timestampsMs) {
                        if (ts < 0L) {
                            return RawCueParseOutcome.Error(
                                TranscriptParseResult.Error(
                                    TranscriptError.TIME_OUT_OF_RANGE,
                                    "Negative LRC timestamp: $ts",
                                ),
                            )
                        }
                        val prevTs = lastTimestampMs
                        if (prevTs != null && ts <= prevTs) {
                            return RawCueParseOutcome.Error(
                                TranscriptParseResult.Error(
                                    TranscriptError.TIME_OUT_OF_RANGE,
                                    "Non-increasing LRC timestamp: $ts after $prevTs",
                                ),
                            )
                        }
                        lastTimestampMs = ts

                        if (normalizedText != null) {
                            if (timedEntries.size >= MAX_SEGMENTS) {
                                return RawCueParseOutcome.Error(
                                    TranscriptParseResult.Error(
                                        TranscriptError.TOO_LARGE,
                                        "LRC segments exceed maximum of $MAX_SEGMENTS",
                                    ),
                                )
                            }
                            timedEntries.add(LrcTimedEntry(startMs = ts, text = normalizedText))
                        }
                    }
                }
            }
        }

        if (timedEntries.isEmpty()) {
            return RawCueParseOutcome.Error(
                TranscriptParseResult.Error(
                    TranscriptError.SCHEMA_VIOLATION,
                    "No valid LRC lyric entries found",
                ),
            )
        }

        val cues = ArrayList<RawCue>(timedEntries.size)
        for (idx in timedEntries.indices) {
            val current = timedEntries[idx]
            val endMs = if (idx + 1 < timedEntries.size) {
                timedEntries[idx + 1].startMs
            } else {
                current.startMs + LRC_LAST_LINE_DURATION_MS
            }
            if (current.startMs >= endMs) {
                return RawCueParseOutcome.Error(
                    TranscriptParseResult.Error(
                        TranscriptError.TIME_OUT_OF_RANGE,
                        "Invalid LRC segment range: ${current.startMs}..$endMs",
                    ),
                )
            }
            cues.add(RawCue(startMs = current.startMs, endMs = endMs, text = current.text))
        }

        return RawCueParseOutcome.Ok(cues)
    }

    private fun parseTsv(text: String): RawCueParseOutcome {
        val lines = normalizeLineEndings(text).split('\n')
        val cues = mutableListOf<RawCue>()
        var sawDataRow = false
        var isFirstNonBlankLine = true

        for (rawLine in lines) {
            if (rawLine.isBlank()) continue
            if (rawLine.length > MAX_RAW_CUE_CHARS) {
                return RawCueParseOutcome.Error(
                    TranscriptParseResult.Error(
                        TranscriptError.SCHEMA_VIOLATION,
                        "TSV line exceeds maximum allowed length",
                    ),
                )
            }

            val cols = rawLine.split('\t')
            if (isFirstNonBlankLine) {
                isFirstNonBlankLine = false
                val firstCol = cols.firstOrNull()?.trim().orEmpty()
                if (firstCol.equals("startMs", ignoreCase = true) || firstCol.equals("start", ignoreCase = true)) {
                    continue
                }
            }

            if (cols.size < 3) {
                return RawCueParseOutcome.Error(
                    TranscriptParseResult.Error(
                        TranscriptError.SCHEMA_VIOLATION,
                        "Invalid TSV row: expected at least 3 tab-separated columns",
                    ),
                )
            }

            val startMs = parseFixedDigits(cols[0].trim(), minDigits = 1, maxDigits = 12)
                ?: return RawCueParseOutcome.Error(
                    TranscriptParseResult.Error(
                        TranscriptError.SCHEMA_VIOLATION,
                        "Invalid TSV startMs value",
                    ),
                )
            val endMs = parseFixedDigits(cols[1].trim(), minDigits = 1, maxDigits = 12)
                ?: return RawCueParseOutcome.Error(
                    TranscriptParseResult.Error(
                        TranscriptError.SCHEMA_VIOLATION,
                        "Invalid TSV endMs value",
                    ),
                )

            if (startMs < 0L || startMs >= endMs) {
                return RawCueParseOutcome.Error(
                    TranscriptParseResult.Error(
                        TranscriptError.TIME_OUT_OF_RANGE,
                        "TSV cue timestamp out of range: $startMs .. $endMs",
                    ),
                )
            }

            sawDataRow = true
            val language: LanguageTag?
            val rawTextPayload: String
            if (cols.size >= 4) {
                val rawLang = cols[2].trim()
                language = rawLang
                    .takeIf { it.isNotEmpty() && ChunkFlowTranscriptParser.isValidLanguageTag(it) }
                    ?.let(::LanguageTag)
                rawTextPayload = cols.subList(3, cols.size).joinToString(" ")
            } else {
                language = null
                rawTextPayload = cols[2]
            }

            val cleaned = cleanAndNormalizeCueText(rawTextPayload)
            when (cleaned) {
                is CleanedTextOutcome.TooLong -> return RawCueParseOutcome.Error(cleaned.error)

                is CleanedTextOutcome.Empty -> continue

                is CleanedTextOutcome.Ok -> {
                    if (cues.size >= MAX_SEGMENTS) {
                        return RawCueParseOutcome.Error(
                            TranscriptParseResult.Error(
                                TranscriptError.TOO_LARGE,
                                "TSV segments exceed maximum of $MAX_SEGMENTS",
                            ),
                        )
                    }
                    cues.add(
                        RawCue(
                            startMs = startMs,
                            endMs = endMs,
                            text = cleaned.text,
                            language = language,
                        ),
                    )
                }
            }
        }

        if (!sawDataRow) {
            return RawCueParseOutcome.Error(
                TranscriptParseResult.Error(
                    TranscriptError.SCHEMA_VIOLATION,
                    "Empty TSV file",
                ),
            )
        }
        return RawCueParseOutcome.Ok(cues)
    }

    private fun sortAndClampSegments(rawCues: List<RawCue>): List<TranscriptSegment> {
        val sorted = rawCues.sortedWith(compareBy<RawCue> { it.startMs }.thenBy { it.endMs })
        val result = ArrayList<TranscriptSegment>(sorted.size)

        for (idx in sorted.indices) {
            val current = sorted[idx]
            val nextStartMs = if (idx + 1 < sorted.size) sorted[idx + 1].startMs else null
            val clampedEndMs = if (nextStartMs != null && current.endMs > nextStartMs) {
                nextStartMs
            } else {
                current.endMs
            }
            if (current.startMs >= clampedEndMs) {
                // 同一開始時刻のキューが重なった場合はゼロ長になるためスキップする
                continue
            }
            result.add(
                TranscriptSegment(
                    startMs = current.startMs,
                    endMs = clampedEndMs,
                    language = current.language,
                    languageConfidence = null,
                    text = current.text,
                    textVariants = emptyMap(),
                    words = emptyList(),
                ),
            )
        }
        return result
    }

    private fun cleanAndNormalizeCueText(rawText: String): CleanedTextOutcome {
        val withoutTags = stripSubtitleTags(rawText)
        val decodedEntities = decodeBasicHtmlEntities(withoutTags)
        val normalizedLines = decodedEntities
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

        if (normalizedLines.isEmpty()) {
            return CleanedTextOutcome.Empty
        }

        val nfc = ChunkFlowTranscriptParser.normalizeNfc(normalizedLines)
        if (nfc.isEmpty()) {
            return CleanedTextOutcome.Empty
        }

        val codePoints = nfc.codePointCount(0, nfc.length)
        if (codePoints > MAX_SEGMENT_TEXT_CODE_POINTS) {
            return CleanedTextOutcome.TooLong(
                TranscriptParseResult.Error(
                    TranscriptError.SCHEMA_VIOLATION,
                    "Subtitle cue text length ($codePoints) exceeds maximum of $MAX_SEGMENT_TEXT_CODE_POINTS code points",
                ),
            )
        }
        return CleanedTextOutcome.Ok(nfc)
    }

    private data class RawCue(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val language: LanguageTag? = null,
    )

    private data class LrcTimedEntry(
        val startMs: Long,
        val text: String,
    )

    private sealed interface RawCueParseOutcome {
        data class Ok(val cues: List<RawCue>) : RawCueParseOutcome
        data class Error(val error: TranscriptParseResult.Error) : RawCueParseOutcome
    }

    private sealed interface CleanedTextOutcome {
        data class Ok(val text: String) : CleanedTextOutcome
        data object Empty : CleanedTextOutcome
        data class TooLong(val error: TranscriptParseResult.Error) : CleanedTextOutcome
    }

    private sealed interface LrcLineOutcome {
        data object Metadata : LrcLineOutcome
        data class Lyric(val timestampsMs: List<Long>, val rawText: String) : LrcLineOutcome
    }

    private data class TimingRange(
        val startMs: Long,
        val endMs: Long,
    )

    internal enum class SubtitleFormat(val id: String) {
        SRT("srt"),
        VTT("vtt"),
        LRC("lrc"),
        TSV("tsv"),
    }

    companion object {
        /** 1 ファイルあたりの最大バイト数: 20 MB（§8.1）。 */
        const val MAX_FILE_SIZE_BYTES: Int = ChunkFlowTranscriptParser.MAX_FILE_SIZE_BYTES

        /** セグメント数の上限: 50,000 件（§8.1）。 */
        const val MAX_SEGMENTS: Int = ChunkFlowTranscriptParser.MAX_SEGMENTS

        /** 1 セグメントあたりの最大文字数（Unicode コードポイント数）: 2,000 文字（§8.1）。 */
        const val MAX_SEGMENT_TEXT_CODE_POINTS: Int = ChunkFlowTranscriptParser.MAX_SEGMENT_TEXT_CODE_POINTS

        /** LRC の最終行に割り当てる既定の長さ: 3,000 ミリ秒（§7.4.6）。 */
        const val LRC_LAST_LINE_DURATION_MS: Long = 3_000L

        /** タグ除去前の 1 キューあたりの最大文字数（異常に長い入力に対する防御上限）。 */
        private const val MAX_RAW_CUE_CHARS: Int = 16_384

        private const val UTF8_BOM: String = "\uFEFF"

        internal fun isSubtitleFileName(fileName: String): Boolean {
            val name = fileName.substringAfterLast('/').substringAfterLast('\\').lowercase()
            return name.endsWith(".srt") ||
                name.endsWith(".vtt") ||
                name.endsWith(".webvtt") ||
                name.endsWith(".lrc") ||
                name.endsWith(".tsv")
        }

        internal fun detectFormat(fileName: String, content: String): SubtitleFormat? {
            val baseName = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
            if (baseName.contains('.')) {
                val lower = baseName.lowercase()
                return when {
                    lower.endsWith(".srt") -> SubtitleFormat.SRT
                    lower.endsWith(".vtt") || lower.endsWith(".webvtt") -> SubtitleFormat.VTT
                    lower.endsWith(".lrc") -> SubtitleFormat.LRC
                    lower.endsWith(".tsv") -> SubtitleFormat.TSV
                    else -> null
                }
            }

            val trimmed = content.trimStart()
            if (trimmed.isEmpty()) return null
            val firstLine = trimmed.lineSequence().firstOrNull().orEmpty()
            if (isValidWebVttHeader(firstLine)) {
                return SubtitleFormat.VTT
            }
            if (isTsvHeaderOrDataLine(firstLine)) {
                return SubtitleFormat.TSV
            }
            val firstNonEmptyLines = trimmed.lineSequence().filter { it.isNotBlank() }.take(2).toList()
            if (firstNonEmptyLines.any { it.contains("-->") }) {
                return SubtitleFormat.SRT
            }
            if (trimmed.startsWith("[")) {
                return SubtitleFormat.LRC
            }
            return null
        }

        private fun isTsvHeaderOrDataLine(line: String): Boolean {
            if (!line.contains('\t')) return false
            val cols = line.split('\t')
            if (cols.size < 3) return false
            val first = cols[0].trim()
            if (first.equals("startMs", ignoreCase = true) || first.equals("start", ignoreCase = true)) {
                return true
            }
            return parseFixedDigits(first, 1, 12) != null && parseFixedDigits(cols[1].trim(), 1, 12) != null
        }

        internal fun extractAudioStem(fileName: String): String? {
            val baseName = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
            if (baseName.isEmpty()) return null
            val stem = if (baseName.contains('.')) {
                baseName.substringBeforeLast('.')
            } else {
                baseName
            }.trim()
            if (stem.isEmpty()) return null
            return ChunkFlowTranscriptParser.normalizeNfc(stem)
        }

        /**
         * HTML / WebVTT / カラオケタイムスタンプ等の `<...>` タグを正規表現を使わず線形時間で除去する。
         */
        internal fun stripSubtitleTags(input: String): String {
            if (!input.contains('<')) return input
            val sb = StringBuilder(input.length)
            var i = 0
            while (i < input.length) {
                val ch = input[i]
                if (ch == '<') {
                    val closeIdx = input.indexOf('>', startIndex = i + 1)
                    if (closeIdx != -1) {
                        i = closeIdx + 1
                        continue
                    }
                }
                sb.append(ch)
                i++
            }
            return sb.toString()
        }

        private fun decodeBasicHtmlEntities(input: String): String {
            if (!input.contains('&')) return input
            return input
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }

        private fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

        private fun isSrtCueNumber(line: String): Boolean = line.isNotEmpty() && line.all { it in '0'..'9' }

        private fun isValidWebVttHeader(firstLine: String): Boolean {
            val line = firstLine.trimEnd()
            if (line == "WEBVTT") return true
            return line.startsWith("WEBVTT ") || line.startsWith("WEBVTT\t")
        }

        private fun isWebVttIgnoredBlockHeader(line: String): Boolean = line == "NOTE" ||
            line.startsWith("NOTE ") ||
            line.startsWith("NOTE\t") ||
            line == "STYLE" ||
            line.startsWith("STYLE ") ||
            line.startsWith("STYLE\t") ||
            line == "REGION" ||
            line.startsWith("REGION ") ||
            line.startsWith("REGION\t")

        private fun parseArrowTimingLine(line: String, requireHours: Boolean): TimingRange? {
            val arrowIdx = line.indexOf("-->")
            if (arrowIdx <= 0) return null
            val startPart = line.substring(0, arrowIdx).trim()
            val rightSide = line.substring(arrowIdx + 3).trim()
            if (startPart.isEmpty() || rightSide.isEmpty()) return null

            val endToken = rightSide.takeWhile { !it.isWhitespace() }
            val startMs = parseSubtitleTimestampMs(startPart, requireHours = requireHours) ?: return null
            val endMs = parseSubtitleTimestampMs(endToken, requireHours = requireHours) ?: return null
            return TimingRange(startMs = startMs, endMs = endMs)
        }

        /**
         * `HH:MM:SS,mmm` / `HH:MM:SS.mmm` / `MM:SS.mmm` をミリ秒に変換する。
         */
        internal fun parseSubtitleTimestampMs(token: String, requireHours: Boolean): Long? {
            val parts = token.split(':')
            val hours: Long
            val minutes: Long
            val secAndMillis: String

            when (parts.size) {
                3 -> {
                    hours = parseFixedDigits(parts[0], minDigits = 1, maxDigits = 4) ?: return null
                    minutes = parseFixedDigits(parts[1], minDigits = 2, maxDigits = 2) ?: return null
                    secAndMillis = parts[2]
                }

                2 -> {
                    if (requireHours) return null
                    hours = 0L
                    minutes = parseFixedDigits(parts[0], minDigits = 2, maxDigits = 2) ?: return null
                    secAndMillis = parts[1]
                }

                else -> return null
            }

            if (minutes !in 0L..59L) return null

            val sepIdx = secAndMillis.indexOfFirst { it == '.' || it == ',' }
            if (sepIdx <= 0) return null
            val secStr = secAndMillis.substring(0, sepIdx)
            val fracStr = secAndMillis.substring(sepIdx + 1)

            val seconds = parseFixedDigits(secStr, minDigits = 2, maxDigits = 2) ?: return null
            if (seconds !in 0L..59L) return null

            val rawMillis = parseFixedDigits(fracStr, minDigits = 1, maxDigits = 3) ?: return null
            val millis = when (fracStr.length) {
                1 -> rawMillis * 100L
                2 -> rawMillis * 10L
                3 -> rawMillis
                else -> return null
            }

            return ((hours * 3600L + minutes * 60L + seconds) * 1000L) + millis
        }

        private fun parseLrcLine(line: String): LrcLineOutcome? {
            if (!line.startsWith('[')) return null

            val timestamps = mutableListOf<Long>()
            var pos = 0
            while (pos < line.length && line[pos] == '[') {
                val closeIdx = line.indexOf(']', startIndex = pos + 1)
                if (closeIdx == -1) return null
                val tagContent = line.substring(pos + 1, closeIdx).trim()
                val timestampMs = parseLrcTimestampTag(tagContent)
                if (timestampMs != null) {
                    timestamps.add(timestampMs)
                    pos = closeIdx + 1
                } else if (timestamps.isEmpty() && isLrcMetadataTag(tagContent) && closeIdx == line.length - 1) {
                    return LrcLineOutcome.Metadata
                } else {
                    return null
                }
            }

            if (timestamps.isEmpty()) return null
            val rawText = line.substring(pos)
            return LrcLineOutcome.Lyric(timestampsMs = timestamps, rawText = rawText)
        }

        /**
         * LRC のタイムスタンプタグ（`mm:ss.xx`、`mm:ss.xxx`、`mm:ss:xx`、`mm:ss`）をミリ秒に変換する。
         */
        private fun parseLrcTimestampTag(tag: String): Long? {
            if (tag.isEmpty() || tag[0] !in '0'..'9') return null
            val colonIdx = tag.indexOf(':')
            if (colonIdx <= 0) return null

            val minStr = tag.substring(0, colonIdx)
            val rest = tag.substring(colonIdx + 1)
            val minutes = parseFixedDigits(minStr, minDigits = 1, maxDigits = 4) ?: return null

            val sepIdx = rest.indexOfFirst { it == '.' || it == ':' }
            if (sepIdx == -1) {
                val seconds = parseFixedDigits(rest, minDigits = 2, maxDigits = 2) ?: return null
                if (seconds !in 0L..59L) return null
                return (minutes * 60L + seconds) * 1000L
            }

            val secStr = rest.substring(0, sepIdx)
            val fracStr = rest.substring(sepIdx + 1)
            val seconds = parseFixedDigits(secStr, minDigits = 2, maxDigits = 2) ?: return null
            if (seconds !in 0L..59L) return null

            val rawFrac = parseFixedDigits(fracStr, minDigits = 1, maxDigits = 3) ?: return null
            val millis = when (fracStr.length) {
                1 -> rawFrac * 100L
                2 -> rawFrac * 10L
                3 -> rawFrac
                else -> return null
            }
            return ((minutes * 60L + seconds) * 1000L) + millis
        }

        private fun isLrcMetadataTag(tag: String): Boolean {
            val colonIdx = tag.indexOf(':')
            if (colonIdx <= 0) return false
            val key = tag.substring(0, colonIdx).trim()
            return key.isNotEmpty() && key.all { it in 'a'..'z' || it in 'A'..'Z' || it == '_' || it == '-' }
        }

        private fun parseFixedDigits(value: String, minDigits: Int, maxDigits: Int): Long? {
            if (value.length !in minDigits..maxDigits) return null
            for (i in 0 until value.length) {
                if (value[i] !in '0'..'9') return null
            }
            return value.toLongOrNull()
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
    }
}
