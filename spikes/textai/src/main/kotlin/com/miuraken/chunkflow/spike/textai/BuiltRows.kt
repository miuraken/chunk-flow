package com.miuraken.chunkflow.spike.textai

/** [buildRows] の結果。 */
internal data class BuiltRows(val rows: List<ChunkRow>, val unknownCount: Int)

/**
 * 読み込んだセグメントから、学習言語のチャンクだけを選ぶ。
 *
 * - 言語は、Chunk Flow JSON のセグメントの `lang` を優先し、ないとき（字幕形式を含む）は文字の種類から推定する。
 * - 言語がわからないチャンクは、[includeUnknown] のときだけ含める。
 * - 方式 B に渡す「直前のチャンク」は、ファイルで 1 つ前のセグメント（言語は問わない）。
 *
 * @param englishName 言語コードから、プロンプトに書く英語の言語名を返す。
 */
internal fun buildRows(
    transcript: LoadedTranscript,
    target: String,
    base: String?,
    includeUnknown: Boolean,
    englishName: (String) -> String,
): BuiltRows {
    val resolved = transcript.segments.map { segment -> resolveLanguage(segment, target, base) }
    val rows = mutableListOf<ChunkRow>()
    var unknownCount = 0
    for ((index, segment) in transcript.segments.withIndex()) {
        val (language, source) = resolved[index]
        if (language == null) unknownCount++
        val isTarget = language == target || (language == null && includeUnknown)
        if (!isTarget) continue
        val previous = transcript.segments.getOrNull(index - 1)?.let { previousSegment ->
            PreviousContext(
                text = previousSegment.text,
                languageName = resolved[index - 1].first?.let(englishName),
            )
        }
        rows += ChunkRow(
            number = segment.number,
            startMs = segment.startMs,
            text = segment.text,
            language = language,
            languageSource = source,
            previous = previous,
        )
    }
    return BuiltRows(rows, unknownCount)
}

private fun resolveLanguage(segment: Segment, target: String, base: String?): Pair<String?, LanguageSource> {
    val fileLanguage = segment.fileLanguage
    if (fileLanguage != null) return fileLanguage to LanguageSource.FILE
    val guessed = ScriptLanguageGuesser.guess(segment.text, target, base) ?: return null to LanguageSource.NONE
    return guessed to LanguageSource.SCRIPT
}
