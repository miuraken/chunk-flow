package com.miuraken.chunkflow.core.model

/**
 * チャンク 1 つ分の文字起こし。
 *
 * @property chunkId 対応するチャンク。
 * @property text 本文（NFC に正規化済みのプレーンテキスト）。中国語（`zh`）は簡体字、広東語（`yue`）は香港式の繁体字（§7.4.3）。
 * @property textVariants 別の字体のテキスト。キーは ISO 15924 の文字コード（`"Hans"` / `"Hant"`）。
 * @property language 言語。`null` は判定できなかったことを表す（UI では「?」と表示する）。
 * @property languageConfidence 言語の確率（0..1）。提供されない場合は `null`（0 と区別する）。
 * @property words 単語単位の時刻（JSON にある場合）。
 * @property engine 文字起こしの出どころ。
 * @property userEdited `true` なら再取り込みで上書きしない（§7.4.6）。
 * @property translatedText 対象言語からベース言語へ翻訳されたテキスト（未翻訳なら `null`）。
 * @property translatedLanguage [translatedText] の翻訳先言語タグ（未翻訳なら `null`）。
 */
data class Transcript(
    val chunkId: ChunkId,
    val text: String,
    val textVariants: Map<String, String> = emptyMap(),
    val language: LanguageTag?,
    val languageConfidence: Float?,
    val words: List<WordTiming> = emptyList(),
    val engine: EngineId,
    val userEdited: Boolean = false,
    val translatedText: String? = null,
    val translatedLanguage: LanguageTag? = null,
)

/**
 * 単語単位の時刻。
 *
 * @property startMs 単語の開始（ミリ秒）。
 * @property endMs 単語の終了（ミリ秒）。
 * @property text 単語のテキスト（[Transcript.text] と同じ字体）。
 * @property confidence 単語の確率（0..1）。提供されない場合は `null`。
 */
data class WordTiming(val startMs: Long, val endMs: Long, val text: String, val confidence: Float?)

/** 文字起こしの書き出し形式（F7.5）。 */
enum class TranscriptExportFormat(
    /** ドットなしの拡張子。 */
    val extension: String,
    /** 推奨 MIME タイプ。 */
    val mimeType: String,
) {
    /** SubRip 字幕形式（`.srt`）。 */
    SRT(extension = "srt", mimeType = "application/x-subrip"),

    /** LRC 歌詞・字幕形式（`.lrc`）。 */
    LRC(extension = "lrc", mimeType = "text/plain"),

    /** タブ区切りテキスト形式（`.tsv`: `startMs\tendMs\tlang\ttext`）。 */
    TSV(extension = "tsv", mimeType = "text/tab-separated-values"),

    /** Chunk Flow 標準 JSON 形式（`.json`: `chunkflow-transcript` v1）。 */
    JSON(extension = "json", mimeType = "application/json"),
    ;

    /**
     * 音声ファイル名またはタイトルから拡張子を取り除き、この形式の拡張子を付与したデフォルト出力ファイル名を返す。
     */
    fun defaultFileName(baseTitleOrFileName: String): String {
        val lastSegment = baseTitleOrFileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val stem = lastSegment.replace(
            Regex("\\.(mp3|m4a|mp4|ogg|opus|flac|wav|aac)$", RegexOption.IGNORE_CASE),
            "",
        ).trim().ifBlank { "transcript" }
        return "$stem.$extension"
    }
}
