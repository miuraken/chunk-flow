package com.miuraken.chunkflow.spike.textai

/** 比べる方式（計画の 2）。[code] は画面と CSV に出す記号。 */
internal enum class Method(val code: String, val purpose: Purpose) {
    /** A. Translation API（学習言語 → ベース言語）。 */
    TRANSLATE_API("A", Purpose.TRANSLATION),

    /** B. Prompt API による翻訳（直前のチャンクを文脈として渡す）。 */
    PROMPT_TRANSLATE("B", Purpose.TRANSLATION),

    /** C. Proofreading API（入力の種類 = VOICE）。 */
    PROOFREAD_API("C", Purpose.PROOFREADING),

    /** D. Prompt API による校正の提案（元の文 / 案 / 理由）。 */
    PROMPT_PROOFREAD("D", Purpose.PROOFREADING),
}

/** 方式の用途。評価の選択肢が変わる。 */
internal enum class Purpose { TRANSLATION, PROOFREADING }

/** 1 回の実行の結果の種類（CSV の `result` 列）。 */
internal enum class ResultKind {
    SUCCESS,

    /** 呼び出しは成功したが、決めた形（元の文 / 案 / 理由）で返らなかった（方式 D）。 */
    FORMAT_ERROR,

    /** 呼び出しは成功したが、出力が空だった。 */
    EMPTY,

    /** この言語には対応していない。 */
    UNSUPPORTED,

    /** モデルがまだない（ダウンロードしていない）。 */
    NOT_READY,
    BUSY,

    /** 回数の上限（`PER_APP_BATTERY_USE_QUOTA_EXCEEDED`）。 */
    QUOTA_EXCEEDED,
    BACKGROUND_USE_BLOCKED,
    NOT_AVAILABLE,
    REQUEST_TOO_LARGE,
    NETWORK_ERROR,
    TIMEOUT,
    CANCELLED,
    ERROR,
}

/** 「全部を順に実行」で、もう一度実行しなくてよい結果か。 */
internal val ResultKind.isDone: Boolean
    get() = this == ResultKind.SUCCESS || this == ResultKind.FORMAT_ERROR || this == ResultKind.EMPTY

/** 人間の評価（計画の 4 の 1）。 */
internal enum class Rating(val purpose: Purpose) {
    /** 翻訳: 合っている。 */
    CORRECT(Purpose.TRANSLATION),

    /** 翻訳: ほぼ合っている。 */
    MOSTLY_CORRECT(Purpose.TRANSLATION),

    /** 翻訳: 間違い。 */
    WRONG(Purpose.TRANSLATION),

    /** 校正: 正しい修正。 */
    GOOD_FIX(Purpose.PROOFREADING),

    /** 校正: 不要な書き換え。 */
    UNNECESSARY_FIX(Purpose.PROOFREADING),

    /** 校正: 誤った修正。 */
    WRONG_FIX(Purpose.PROOFREADING),
}

/** チャンクの言語をどこから知ったか。 */
internal enum class LanguageSource {
    /** Chunk Flow JSON のセグメントの `lang`。 */
    FILE,

    /** 文字の種類からの推定（字幕形式、または JSON で `lang` がないとき）。 */
    SCRIPT,

    /** わからない。 */
    NONE,
}

/** 読み込んだファイルの形式。 */
internal enum class FileKind { CHUNKFLOW_JSON, SRT, VTT, LRC }

/** 読み込んだセグメント 1 つ（ファイルの順）。 */
internal data class Segment(
    val number: Int,
    val startMs: Long,
    val text: String,
    val fileLanguage: String?,
)

/**
 * 読み込んだ文字起こし。
 *
 * @property fileId ファイルの内容の SHA-256 の先頭 8 桁（CSV でファイルを区別するため。ファイル名は記録しない）。
 */
internal data class LoadedTranscript(
    val kind: FileKind,
    val fileId: String,
    val segments: List<Segment>,
    val fileTarget: String?,
    val fileBase: String?,
)

/** 画面に並べる学習言語のチャンク。 */
internal data class ChunkRow(
    val number: Int,
    val startMs: Long,
    val text: String,
    val language: String?,
    val languageSource: LanguageSource,
    val previous: PreviousContext?,
)

/** 結果と評価の鍵（チャンクの番号と方式）。 */
internal data class ResultKey(val chunkNumber: Int, val method: Method)

/**
 * 1 回の実行の結果。[output] と [reason] は画面に出すだけで、CSV にもログにも書かない（AGENTS.md §10）。
 */
internal data class MethodResult(
    val kind: ResultKind,
    val elapsedMs: Long,
    val firstCall: Boolean,
    val foreground: Boolean,
    val output: String? = null,
    val reason: String? = null,
    val changed: Boolean? = null,
    val suggestionCount: Int? = null,
    val finishReason: String? = null,
    val errorCode: Int? = null,
)

/** 方式ごとの使える状態。 */
internal enum class EngineState { CHECKING, UNSUPPORTED, NEEDS_LANGUAGE, UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE, ERROR }

/** 方式ごとの状態（`checkFeatureStatus` やモデルのダウンロードの有無）と、直近のダウンロードの記録。 */
internal data class EngineStatus(
    val state: EngineState,
    val modelName: String? = null,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val lastDownloadMs: Long? = null,
    val lastDownloadBytes: Long? = null,
    val errorCode: Int? = null,
)

/** いま行っている、ほかの操作と同時にはできない処理。 */
internal enum class BusyKind { LOADING_FILE, CHECKING_STATUS, DOWNLOADING, RUNNING_CHUNK, RUNNING_ALL }

/** 「全部を順に実行」の進み具合。 */
internal data class BatchProgress(val done: Int, val total: Int)

/** 直近の「全部を順に実行」の結果。 */
internal data class BatchSummary(
    val elapsedMs: Long,
    val runs: Int,
    val successes: Int,
    val completed: Boolean,
)

/** 読み込みの失敗の種類（画面に出す）。 */
internal enum class LoadError { TOO_LARGE, UNREADABLE, UNKNOWN_FORMAT, UNSUPPORTED_VERSION, INVALID, TIME_OUT_OF_RANGE, NO_SEGMENTS }

/** 画面に一度だけ出す知らせ。 */
internal sealed interface SpikeMessage {
    data class SummarySaved(val fileName: String) : SpikeMessage
    data object SummaryFailed : SpikeMessage
    data object NothingToRun : SpikeMessage
}

/** 試作の画面の状態。 */
internal data class TextAiUiState(
    val transcript: LoadedTranscript? = null,
    val loadError: LoadError? = null,
    val target: String? = null,
    val base: String? = null,
    val includeUnknown: Boolean = false,
    val rows: List<ChunkRow> = emptyList(),
    val unknownCount: Int = 0,
    val results: Map<ResultKey, MethodResult> = emptyMap(),
    val ratings: Map<ResultKey, Rating> = emptyMap(),
    val enabledMethods: Set<Method> = Method.entries.toSet(),
    val statuses: Map<Method, EngineStatus> = emptyMap(),
    val busy: BusyKind? = null,
    val running: ResultKey? = null,
    val batch: BatchProgress? = null,
    val lastBatch: BatchSummary? = null,
    val csvDirectory: String? = null,
    val message: SpikeMessage? = null,
)

/** 画面からの操作。 */
internal sealed interface SpikeEvent {
    data object OpenFile : SpikeEvent
    data object LoadSample : SpikeEvent
    data class SelectTarget(val language: String) : SpikeEvent
    data class SelectBase(val language: String) : SpikeEvent
    data class SetIncludeUnknown(val include: Boolean) : SpikeEvent
    data class SetMethodEnabled(val method: Method, val enabled: Boolean) : SpikeEvent
    data object RefreshStatus : SpikeEvent
    data class Download(val method: Method) : SpikeEvent
    data class RunChunk(val chunkNumber: Int) : SpikeEvent
    data object RunAll : SpikeEvent
    data object Stop : SpikeEvent
    data class Rate(val key: ResultKey, val rating: Rating) : SpikeEvent
    data object ClearResults : SpikeEvent
    data object SaveSummary : SpikeEvent
    data object DismissMessage : SpikeEvent
}
