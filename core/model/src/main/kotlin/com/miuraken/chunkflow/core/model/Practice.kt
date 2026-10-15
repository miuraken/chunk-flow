package com.miuraken.chunkflow.core.model

/** リピート（ループ）のモード（§7.2.4）。 */
sealed interface LoopMode {
    /** リピートしない。 */
    data object Off : LoopMode

    /**
     * 1 つのチャンクをリピートする。
     *
     * @property chunkId リピートするチャンク。
     * @property times リピートの回数。`null` は無限。
     */
    data class SingleChunk(val chunkId: ChunkId, val times: Int?) : LoopMode

    /**
     * 連続する複数のチャンク（[firstChunkId] から [lastChunkId] まで）をまとめてリピートする。
     *
     * @property firstChunkId 範囲の最初のチャンク。
     * @property lastChunkId 範囲の最後のチャンク。
     * @property times リピートの回数。`null` は無限。
     */
    data class ChunkRange(val firstChunkId: ChunkId, val lastChunkId: ChunkId, val times: Int?) : LoopMode

    /**
     * A-B リピート。`startMs < endMs` でなければならない。
     *
     * @property startMs A の位置（ミリ秒）。
     * @property endMs B の位置（ミリ秒）。
     */
    data class AbRange(val startMs: Long, val endMs: Long) : LoopMode {
        init {
            require(startMs < endMs)
        }
    }
}

/** 自動ポーズの設定（§7.2.4）。 */
sealed interface AutoPause {
    /** 自動ポーズしない。 */
    data object Off : AutoPause

    /**
     * 固定の長さだけポーズする。
     *
     * @property pauseMs ポーズの長さ（ミリ秒）。
     */
    data class Fixed(val pauseMs: Long) : AutoPause

    /**
     * ポーズ長 = max([minMs], チャンクの実再生時間 × [factor])。実再生時間は再生速度を考慮する。
     *
     * @property factor チャンクの実再生時間に掛ける係数。
     * @property minMs ポーズ長の下限（ミリ秒）。
     */
    data class Proportional(val factor: Float = 1.0f, val minMs: Long = 1_000) : AutoPause
}

/** 自動ポーズを入れるチャンクの範囲。 */
enum class AutoPauseScope {
    /** すべてのチャンクの後。 */
    ALL_CHUNKS,

    /** 母語（base）のチャンクの後だけ（瞬間作文モード）。 */
    AFTER_BASE_LANGUAGE,
}

/** 言語フィルタ再生（F6.4）。 */
enum class LanguageFilter {
    /** すべてのチャンクを再生する。 */
    ALL,

    /** 学習言語のチャンクだけを再生する。 */
    TARGET_ONLY,

    /** 母語のチャンクだけを再生する。 */
    BASE_ONLY,
}

/**
 * 学習モードの設定一式。
 *
 * @property loop リピートのモード。
 * @property autoPause 自動ポーズの設定。
 * @property autoPauseScope 自動ポーズを入れるチャンクの範囲。
 * @property languageFilter 言語フィルタ。
 * @property bookmarksOnly ブックマークしたチャンクだけを再生するフィルタ（F8.1）。
 * @property repeatChunkIds 選択したチャンク（チャンクリストのチェックボックス、拡大波形の長押し）の ID の集合。空でない場合、選択したチャンクだけを時系列の順に再生する。
 * @property repeatCheckedOnly 下部再生コントロールの「リピート」トグルが ON かどうか（デフォルトは `false`）。`true` かつ [repeatChunkIds] が空でない場合は選択したチャンクを繰り返し、`false` の場合は最後の選択チャンクのあとで一時停止する。
 */
data class PracticeSettings(
    val loop: LoopMode = LoopMode.Off,
    val autoPause: AutoPause = AutoPause.Off,
    val autoPauseScope: AutoPauseScope = AutoPauseScope.ALL_CHUNKS,
    val languageFilter: LanguageFilter = LanguageFilter.ALL,
    val bookmarksOnly: Boolean = false,
    val repeatChunkIds: Set<ChunkId> = emptySet(),
    val repeatCheckedOnly: Boolean = false,
)
