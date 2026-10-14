package com.miuraken.chunkflow.core.model

/**
 * 教材内のブックマーク（§6.4 `bookmark`、F8.1）。
 *
 * チャンク ID ではなく時刻（[positionMs]）で持ち、表示時にその時刻を含むチャンクに対応付ける（再分割で消えないようにするため）。
 *
 * @property id ブックマークの識別子（Room の主キー。新規作成前は 0）。
 * @property mediaId 対象の教材。
 * @property positionMs ブックマークした位置（ミリ秒、0 以上）。
 * @property note 任意のメモ。
 * @property createdAtEpochMs 作成時刻（UNIX エポックからのミリ秒）。
 */
data class Bookmark(
    val id: Long = 0L,
    val mediaId: MediaId,
    val positionMs: Long,
    val note: String = "",
    val createdAtEpochMs: Long = 0L,
) {
    init {
        require(positionMs >= 0L) { "positionMs must be >= 0: $positionMs" }
    }
}
