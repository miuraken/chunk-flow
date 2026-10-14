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
enum class TranscriptExportFormat {
    /** SubRip 字幕形式（`.srt`）。 */
    SRT,

    /** LRC 歌詞・字幕形式（`.lrc`）。 */
    LRC,

    /** プレーンテキスト形式（`.txt`）。 */
    TXT,
}
