package com.miuraken.chunkflow.core.model

/**
 * 教材ごとの再生・学習モードの状態（§6.4 `item_state`、F2.2 レジューム）。
 *
 * ループ（[LoopMode]）は再開時に解除するため永続化しない（§7.2.5）。
 *
 * @property mediaId 対象の教材。
 * @property positionMs 前回の再生位置（ミリ秒、0 以上）。
 * @property speed 再生速度（0.5..2.0）。
 * @property chunkSource 使用するチャンクの出どころ（文字起こしがあれば [ChunkOrigin.IMPORTED]、なければ [ChunkOrigin.AUTO]）。
 * @property autoPause 自動ポーズの設定。
 * @property autoPauseScope 自動ポーズを入れるチャンクの範囲。
 * @property languageFilter 言語フィルタ。
 * @property updatedAtEpochMs 更新時刻（UNIX エポックからのミリ秒）。
 * @property isListened ファイルの 95%〜100% の位置を 1 秒以上再生したかどうか（聴取完了フラグ）。
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
) {
    init {
        require(positionMs >= 0L) { "positionMs must be >= 0: $positionMs" }
        require(speed in MIN_SPEED..MAX_SPEED) { "speed must be in $MIN_SPEED..$MAX_SPEED: $speed" }
    }

    companion object {
        /** 設定可能な最小の再生速度（§2 F3.2）。 */
        const val MIN_SPEED: Float = 0.5f

        /** 設定可能な最大の再生速度（§2 F3.2）。 */
        const val MAX_SPEED: Float = 2.0f
    }
}
