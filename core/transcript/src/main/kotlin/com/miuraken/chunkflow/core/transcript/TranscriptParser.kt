package com.miuraken.chunkflow.core.transcript

import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.WordTiming

/**
 * 文字起こしファイルを読み、検証して共通の形（[TranscriptDocument]）にする（§6.3、§7.4.6）。
 *
 * 対応する形式は chunkflow-transcript JSON（`docs/format/`）、SRT、WebVTT、LRC。
 * 入力は信頼できないものとして扱い、上限（ファイル 20MB、セグメント 50,000 件、1 セグメントのテキスト 2,000 文字。§8.1）を
 * 超えたら失敗にする。
 */
interface TranscriptParser {
    /**
     * [bytes] を解析する。
     *
     * @param bytes ファイルの内容。
     * @param fileName ファイル名（形式の判定にだけ使う。パスには使わない）。
     */
    fun parse(bytes: ByteArray, fileName: String): TranscriptParseResult
}

/** [TranscriptParser.parse] の結果。 */
sealed interface TranscriptParseResult {
    /** 解析と検証に成功した。 */
    data class Ok(val document: TranscriptDocument) : TranscriptParseResult

    /**
     * 失敗した。
     *
     * @property error 失敗の種別。
     * @property detail 調査用の説明（ログ用。リリースビルドで本文を含めないこと）。
     */
    data class Error(val error: TranscriptError, val detail: String) : TranscriptParseResult
}

/** 文字起こしファイルの失敗の種別。対応関係は `docs/format/README.md` の「検証の順序」を参照。 */
enum class TranscriptError {
    /** 形式を判定できない（`format` が違う、JSON として読めないなど）。 */
    UNKNOWN_FORMAT,

    /** 対応していない版（`version`）。 */
    UNSUPPORTED_VERSION,

    /** JSON Schema または意味の検証に違反している。 */
    SCHEMA_VIOLATION,

    /** サイズ・件数・文字数の上限を超えている。 */
    TOO_LARGE,

    /** 時刻が範囲外（負の値、音声の長さを超えるなど）。 */
    TIME_OUT_OF_RANGE,
}

/**
 * 形式によらない文字起こしの内容。
 *
 * @property audioSha256 音声ファイルの SHA-256（小文字の 16 進数）。Chunk Flow JSON のみ。SRT などでは `null`。
 * @property audioFileName 音声のファイル名（対応付けの手がかりと表示にだけ使う）。
 * @property durationMs 音声の長さ（ミリ秒）。不明なら `null`。
 * @property languages 言語ペア。SRT などでは `null`。
 * @property generator 文字起こしの出どころ。
 * @property segments 開始時刻の順に並び、重ならないセグメント。
 * @property createdAt 生成日時（RFC 3339 文字列）。同じ音声の JSON が複数あるときに新しい方を選ぶのに使う（§7.4.6）。
 */
data class TranscriptDocument(
    val audioSha256: String?,
    val audioFileName: String?,
    val durationMs: Long?,
    val languages: LanguagePair?,
    val generator: EngineId,
    val segments: List<TranscriptSegment>,
    val createdAt: String? = null,
)

/**
 * 文字起こしのセグメント 1 つ。
 *
 * @property startMs 発話そのものの範囲の開始（パディングなし。ミリ秒）。
 * @property endMs 発話そのものの範囲の終了（ミリ秒）。
 * @property language 言語。判定できなければ `null`。
 * @property languageConfidence 言語ペアの 2 言語の中での確率（0..1）。提供されない場合は `null`。
 * @property text 本文。
 * @property textVariants 別の字体のテキスト。キーは ISO 15924（`"Hans"` / `"Hant"`）。
 * @property words 単語単位の時刻。
 */
data class TranscriptSegment(
    val startMs: Long,
    val endMs: Long,
    val language: LanguageTag?,
    val languageConfidence: Float?,
    val text: String,
    val textVariants: Map<String, String>,
    val words: List<WordTiming>,
)
