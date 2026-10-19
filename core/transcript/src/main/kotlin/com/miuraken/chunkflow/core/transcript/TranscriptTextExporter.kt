package com.miuraken.chunkflow.core.transcript

import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.TranscriptExportFormat

/**
 * 教材のチャンクと文字起こしを SRT / LRC / TSV / JSON 形式の文字列へ書き出すエクスポータ（F7.5）。
 */
object TranscriptTextExporter {

    private const val DEFAULT_EXPORT_SHA256: String =
        "0000000000000000000000000000000000000000000000000000000000000000"

    /**
     * 指定した [format] でチャンクと文字起こしを文字列に書き出す。
     *
     * @param chunks 対象教材のチャンク一覧。
     * @param transcripts チャンク ID をキーとする文字起こしマップ。
     * @param format 書き出し形式（[TranscriptExportFormat]）。
     * @return 書き出された文字列。有効な文字起こしが存在しない場合は空文字列。
     */
    fun export(
        chunks: List<Chunk>,
        transcripts: Map<ChunkId, Transcript>,
        format: TranscriptExportFormat,
    ): String = when (format) {
        TranscriptExportFormat.SRT -> exportSrt(chunks, transcripts)
        TranscriptExportFormat.LRC -> exportLrc(chunks, transcripts)
        TranscriptExportFormat.TSV -> exportTsv(chunks, transcripts)
        TranscriptExportFormat.JSON -> exportJson(chunks, transcripts)
    }

    /**
     * チャンクを `startMs` 昇順で並べ、文字起こしテキストが存在するチャンクを
     * 1 始まりの連番、`HH:MM:SS,mmm --> HH:MM:SS,mmm`、テキスト本文、空行の標準 SubRip（`.srt`）形式で出力する。
     */
    fun exportSrt(
        chunks: List<Chunk>,
        transcripts: Map<ChunkId, Transcript>,
    ): String {
        val entries = collectValidEntries(chunks, transcripts)
        if (entries.isEmpty()) return ""

        return buildString {
            for ((index, entry) in entries.withIndex()) {
                val startMs = maxOf(0L, entry.chunk.startMs)
                val endMs = maxOf(startMs + 1L, entry.chunk.endMs)
                append(index + 1).append('\n')
                append(formatSrtTimestamp(startMs))
                    .append(" --> ")
                    .append(formatSrtTimestamp(endMs))
                    .append('\n')
                append(entry.multilineText).append("\n\n")
            }
        }
    }

    /**
     * チャンクを `startMs` 昇順で並べ、文字起こしテキストが存在するチャンクを
     * `[MM:SS.xx]テキスト`（複数行テキストは空白連結）形式で 1 行ずつ出力する。
     */
    fun exportLrc(
        chunks: List<Chunk>,
        transcripts: Map<ChunkId, Transcript>,
    ): String {
        val entries = collectValidEntries(chunks, transcripts)
        if (entries.isEmpty()) return ""

        var lastCentisecondIndex = -1L
        return entries.joinToString("\n") { entry ->
            val rawCentisecondIndex = maxOf(0L, entry.chunk.startMs) / 10L
            val centisecondIndex = if (rawCentisecondIndex <= lastCentisecondIndex) {
                lastCentisecondIndex + 1L
            } else {
                rawCentisecondIndex
            }
            lastCentisecondIndex = centisecondIndex
            "${formatLrcTimestampFromCentiseconds(centisecondIndex)}${entry.singleLineText}"
        }
    }

    /**
     * チャンクを `startMs` 昇順で並べ、文字起こしテキストが存在するチャンクを
     * 先頭ヘッダー行 `startMs\tendMs\tlang\ttext` 付きのタブ区切り（`.tsv`）形式で 1 行ずつ出力する。
     */
    fun exportTsv(
        chunks: List<Chunk>,
        transcripts: Map<ChunkId, Transcript>,
    ): String {
        val entries = collectValidEntries(chunks, transcripts)
        if (entries.isEmpty()) return ""

        return buildString {
            append("startMs\tendMs\tlang\ttext")
            for (entry in entries) {
                val startMs = maxOf(0L, entry.chunk.startMs)
                val endMs = maxOf(startMs + 1L, entry.chunk.endMs)
                val lang = entry.transcript.language?.value
                    ?.trim()
                    ?.replace('\t', ' ')
                    .orEmpty()
                val text = entry.singleLineText.replace('\t', ' ')
                append('\n')
                append(startMs).append('\t')
                append(endMs).append('\t')
                append(lang).append('\t')
                append(text)
            }
        }
    }

    /**
     * チャンクを `startMs` 昇順で並べ、文字起こしテキストが存在するチャンクを
     * `chunkflow-transcript` v1 形式の整形済み JSON（`.json`）として出力する。
     */
    fun exportJson(
        chunks: List<Chunk>,
        transcripts: Map<ChunkId, Transcript>,
        sha256: String? = null,
        fileName: String? = null,
        durationMs: Long? = null,
        languages: LanguagePair? = null,
    ): String {
        val entries = collectValidEntries(chunks, transcripts)
        if (entries.isEmpty()) return ""

        val resolvedSha256 = sha256?.trim()?.lowercase()
            ?.takeIf(ChunkFlowTranscriptParser::isValidSha256)
            ?: DEFAULT_EXPORT_SHA256

        val distinctValidLangs = entries
            .mapNotNull { it.transcript.language?.value?.trim()?.takeIf(ChunkFlowTranscriptParser::isValidLanguageTag) }
            .distinct()
        val resolvedLanguages = languages ?: LanguagePair(
            target = LanguageTag(distinctValidLangs.firstOrNull() ?: ChunkFlowTranscriptSerializer.DEFAULT_TARGET_LANG),
            base = distinctValidLangs.getOrNull(1)?.let(::LanguageTag),
        )

        val segments = entries.map { entry ->
            val startMs = maxOf(0L, entry.chunk.startMs)
            val endMs = maxOf(startMs + 1L, entry.chunk.endMs)
            TranscriptSegment(
                startMs = startMs,
                endMs = endMs,
                language = entry.transcript.language,
                languageConfidence = entry.transcript.languageConfidence,
                text = entry.multilineText,
                textVariants = entry.transcript.textVariants,
                words = entry.transcript.words,
            )
        }

        val maxEndMs = segments.maxOfOrNull { it.endMs } ?: 1L
        val resolvedDurationMs = maxOf(durationMs ?: maxEndMs, maxEndMs, 1L)
        val generatorEngine = entries.first().transcript.engine.value
            .ifBlank { ChunkFlowTranscriptSerializer.DEFAULT_ENGINE }

        val document = TranscriptDocument(
            audioSha256 = resolvedSha256,
            audioFileName = fileName,
            durationMs = resolvedDurationMs,
            languages = resolvedLanguages,
            generator = EngineId(generatorEngine),
            segments = segments,
            createdAt = null,
        )

        return ChunkFlowTranscriptSerializer.serialize(
            document = document,
            sha256 = resolvedSha256,
            fileName = fileName,
            durationMs = resolvedDurationMs,
            languages = resolvedLanguages,
        )
    }

    /**
     * [format] に対応する推奨ファイル拡張子（ドットなし）を返す。
     */
    fun suggestedExtension(format: TranscriptExportFormat): String = when (format) {
        TranscriptExportFormat.SRT -> "srt"
        TranscriptExportFormat.LRC -> "lrc"
        TranscriptExportFormat.TSV -> "tsv"
        TranscriptExportFormat.JSON -> "json"
    }

    /**
     * [format] に対応する推奨 MIME タイプを返す。
     */
    fun suggestedMimeType(format: TranscriptExportFormat): String = when (format) {
        TranscriptExportFormat.SRT -> "application/x-subrip"
        TranscriptExportFormat.LRC -> "text/plain"
        TranscriptExportFormat.TSV -> "text/tab-separated-values"
        TranscriptExportFormat.JSON -> "application/json"
    }

    /**
     * 音声ファイル名またはタイトルから拡張子を取り除き、[format] の拡張子を付与したデフォルト出力ファイル名を返す。
     */
    fun defaultFileName(
        baseTitleOrFileName: String,
        format: TranscriptExportFormat,
    ): String {
        val lastSegment = baseTitleOrFileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val stem = lastSegment.replace(
            Regex("\\.(mp3|m4a|mp4|ogg|opus|flac|wav|aac)$", RegexOption.IGNORE_CASE),
            "",
        ).trim().ifBlank { "transcript" }
        return "$stem.${suggestedExtension(format)}"
    }

    private fun collectValidEntries(
        chunks: List<Chunk>,
        transcripts: Map<ChunkId, Transcript>,
    ): List<ExportEntry> {
        if (chunks.isEmpty() || transcripts.isEmpty()) return emptyList()

        val sortedChunks = chunks.sortedWith(
            compareBy<Chunk> { it.startMs }
                .thenBy { it.endMs }
                .thenBy { it.id.value },
        )

        val result = mutableListOf<ExportEntry>()
        for (chunk in sortedChunks) {
            val transcript = transcripts[chunk.id] ?: continue
            val normalized = ChunkFlowTranscriptParser.normalizeNfc(transcript.text)
            val lines = normalized
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            result += ExportEntry(
                chunk = chunk,
                transcript = transcript,
                multilineText = lines.joinToString("\n"),
                singleLineText = lines.joinToString(" "),
            )
        }
        return result
    }

    private fun formatSrtTimestamp(ms: Long): String {
        val totalSeconds = ms / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        val millis = ms % 1_000L
        return buildString(12) {
            append(hours.toString().padStart(2, '0')).append(':')
            append(minutes.toString().padStart(2, '0')).append(':')
            append(seconds.toString().padStart(2, '0')).append(',')
            append(millis.toString().padStart(3, '0'))
        }
    }

    private fun formatLrcTimestampFromCentiseconds(totalCentiseconds: Long): String {
        val totalSeconds = totalCentiseconds / 100L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val centiseconds = totalCentiseconds % 100L
        return buildString(10) {
            append('[')
            append(minutes.toString().padStart(2, '0')).append(':')
            append(seconds.toString().padStart(2, '0')).append('.')
            append(centiseconds.toString().padStart(2, '0'))
            append(']')
        }
    }

    private data class ExportEntry(
        val chunk: Chunk,
        val transcript: Transcript,
        val multilineText: String,
        val singleLineText: String,
    )
}
