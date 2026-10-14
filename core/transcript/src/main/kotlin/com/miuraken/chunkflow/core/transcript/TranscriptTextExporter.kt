package com.miuraken.chunkflow.core.transcript

import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.TranscriptExportFormat

/**
 * 教材のチャンクと文字起こしを SRT / LRC / TXT 形式の文字列へ書き出すエクスポータ（F7.5）。
 */
object TranscriptTextExporter {

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
        TranscriptExportFormat.TXT -> exportTxt(chunks, transcripts)
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
     * `[MM:SS] [LANG] テキスト`（`language` が `null` の場合は `[MM:SS] テキスト`）形式で 1 行ずつ出力する。
     */
    fun exportTxt(
        chunks: List<Chunk>,
        transcripts: Map<ChunkId, Transcript>,
    ): String {
        val entries = collectValidEntries(chunks, transcripts)
        if (entries.isEmpty()) return ""

        return entries.joinToString("\n") { entry ->
            val timeTag = formatTxtTimestamp(maxOf(0L, entry.chunk.startMs))
            val langTag = entry.transcript.language?.value
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.uppercase()
            if (langTag != null) {
                "$timeTag [$langTag] ${entry.singleLineText}"
            } else {
                "$timeTag ${entry.singleLineText}"
            }
        }
    }

    /**
     * [format] に対応する推奨ファイル拡張子（ドットなし）を返す。
     */
    fun suggestedExtension(format: TranscriptExportFormat): String = when (format) {
        TranscriptExportFormat.SRT -> "srt"
        TranscriptExportFormat.LRC -> "lrc"
        TranscriptExportFormat.TXT -> "txt"
    }

    /**
     * [format] に対応する推奨 MIME タイプを返す。
     */
    fun suggestedMimeType(format: TranscriptExportFormat): String = when (format) {
        TranscriptExportFormat.SRT -> "application/x-subrip"
        TranscriptExportFormat.LRC -> "text/plain"
        TranscriptExportFormat.TXT -> "text/plain"
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

    private fun formatTxtTimestamp(ms: Long): String {
        val totalSeconds = ms / 1_000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        return buildString(7) {
            append('[')
            append(minutes.toString().padStart(2, '0')).append(':')
            append(seconds.toString().padStart(2, '0'))
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
