package com.miuraken.chunkflow.core.transcript

/**
 * `chunkflow-transcript` JSON（[ChunkFlowTranscriptParser]）と字幕形式 SRT / WebVTT / LRC（[SubtitleTranscriptParser]）を
 * ファイル拡張子または先頭の内容に応じて振り分ける統合 [TranscriptParser]（F1.4、§6.3、§7.4.6）。
 */
class CompositeTranscriptParser(
    private val jsonParser: TranscriptParser = ChunkFlowTranscriptParser(),
    private val subtitleParser: TranscriptParser = SubtitleTranscriptParser(),
) : TranscriptParser {

    override fun parse(bytes: ByteArray, fileName: String): TranscriptParseResult {
        if (bytes.size > ChunkFlowTranscriptParser.MAX_FILE_SIZE_BYTES) {
            return TranscriptParseResult.Error(
                TranscriptError.TOO_LARGE,
                "Transcript file exceeds maximum size of ${ChunkFlowTranscriptParser.MAX_FILE_SIZE_BYTES} bytes",
            )
        }

        val baseName = fileName.substringAfterLast('/').substringAfterLast('\\').trim()
        if (baseName.contains('.')) {
            val lower = baseName.lowercase()
            return when {
                lower.endsWith(".json") -> jsonParser.parse(bytes, baseName)

                SubtitleTranscriptParser.isSubtitleFileName(lower) -> subtitleParser.parse(bytes, baseName)

                else -> TranscriptParseResult.Error(
                    TranscriptError.UNKNOWN_FORMAT,
                    "Unsupported transcript file extension",
                )
            }
        }

        return when (sniffContentKind(bytes)) {
            ContentKind.JSON -> jsonParser.parse(bytes, "")

            ContentKind.SUBTITLE -> subtitleParser.parse(bytes, "")

            ContentKind.UNKNOWN -> TranscriptParseResult.Error(
                TranscriptError.UNKNOWN_FORMAT,
                "Unable to determine transcript format from content",
            )
        }
    }

    private enum class ContentKind {
        JSON,
        SUBTITLE,
        UNKNOWN,
    }

    companion object {
        private fun sniffContentKind(bytes: ByteArray): ContentKind {
            if (bytes.isEmpty()) return ContentKind.UNKNOWN
            var offset = 0
            // 先頭の UTF-8 BOM (EF BB BF) をスキップする
            if (bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte()
            ) {
                offset = 3
            }
            while (offset < bytes.size) {
                val b = bytes[offset].toInt() and 0xFF
                if (b == ' '.code || b == '\t'.code || b == '\r'.code || b == '\n'.code) {
                    offset++
                } else {
                    break
                }
            }
            if (offset >= bytes.size) return ContentKind.UNKNOWN
            val firstChar = (bytes[offset].toInt() and 0xFF).toChar()
            return when {
                firstChar == '{' -> ContentKind.JSON
                firstChar == 'W' || firstChar == '[' || firstChar == 's' || firstChar == 'S' || firstChar in '0'..'9' -> ContentKind.SUBTITLE
                else -> ContentKind.UNKNOWN
            }
        }
    }
}
