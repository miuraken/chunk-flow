package com.miuraken.chunkflow.core.model

/**
 * 教材ごとの再生・学習モードの状態（§6.4 `item_state`、F2.2 レジューム）。
 *
 * ループ（[LoopMode]）は再開時に解除するため永続化しない（§7.2.5）。
 *
 * @property mediaId 対象の教材。
 * @property positionMs 前回の再生位置（ミリ秒、0 以上）。
 * @property maxPositionMs 過去に到達した最大の再生位置（ミリ秒、0 以上）。
 * @property speed 再生速度（0.5..2.0）。
 * @property chunkSource 使用するチャンクの出どころ（文字起こしがあれば [ChunkOrigin.IMPORTED]、なければ [ChunkOrigin.AUTO]）。
 * @property autoPause 自動ポーズの設定。
 * @property autoPauseScope 自動ポーズを入れるチャンクの範囲。
 * @property languageFilter 言語フィルタ。
 * @property updatedAtEpochMs 更新時刻（UNIX エポックからのミリ秒）。
 * @property isListened ファイルの 95%〜100% の位置を 1 秒以上再生したかどうか（聴取完了フラグ）。
 * @property isFavorite お気に入り（「お気に入り」プレイリスト）に登録されているかどうか。
 * @property queuedAtEpochMs 「次に再生するキュー」に追加された時刻（UNIX エポックからのミリ秒。未登録なら `0L`）。
 * @property accessCount 教材を開いた累計アクセス回数（0 以上）。
 * @property customSegmentationParams 教材ごとの自動チャンク分割パラメータの上書き（`null` のときは「全体設定に従う」）。
 * @property fileSortOrder 「ファイル」タブでのカスタム並び順（`null` のときは既定のファイル名順）。
 */
data class ItemState(
    val mediaId: MediaId,
    val positionMs: Long = 0L,
    val speed: Float = 1.0f,
    val chunkSource: ChunkOrigin = ChunkOrigin.AUTO,
    val autoPause: AutoPause = AutoPause.Off,
    val autoPauseScope: AutoPauseScope = AutoPauseScope.ALL_CHUNKS,
    val languageFilter: LanguageFilter = LanguageFilter.ALL,
    val updatedAtEpochMs: Long = 0L,
    val isListened: Boolean = false,
    val maxPositionMs: Long = positionMs,
    val isFavorite: Boolean = false,
    val queuedAtEpochMs: Long = 0L,
    val accessCount: Int = 0,
    val customSegmentationParams: SegmentationParams? = null,
    val fileSortOrder: Int? = null,
) {
    init {
        require(positionMs >= 0L) { "positionMs must be >= 0: $positionMs" }
        require(maxPositionMs >= 0L) { "maxPositionMs must be >= 0: $maxPositionMs" }
        require(speed in MIN_SPEED..MAX_SPEED) { "speed must be in $MIN_SPEED..$MAX_SPEED: $speed" }
        require(queuedAtEpochMs >= 0L) { "queuedAtEpochMs must be >= 0: $queuedAtEpochMs" }
        require(accessCount >= 0) { "accessCount must be >= 0: $accessCount" }
    }

    companion object {
        /** 設定可能な最小の再生速度（§2 F3.2）。 */
        const val MIN_SPEED: Float = 0.5f

        /** 設定可能な最大の再生速度（§2 F3.2）。 */
        const val MAX_SPEED: Float = 2.0f
    }
}
