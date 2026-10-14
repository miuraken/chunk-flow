package com.miuraken.chunkflow.core.model

/** Google Drive 上のファイルの種類（§6.4 `drive_file`、§7.1）。 */
enum class DriveFileKind {
    /** 教材の音声ファイル。 */
    AUDIO,

    /** 文字起こしファイル（`*.chunkflow.json`）。 */
    TRANSCRIPT,
}

/**
 * Google Drive の手動同期で記録するファイルのメタデータ（§6.4 `drive_file`、§7.1）。
 *
 * @property driveFileId Drive のファイル ID。
 * @property kind ファイルの種類。
 * @property course コース名（`ChunkFlow/` 直下のフォルダ名）。
 * @property modifiedTimeEpochMs Drive 上の更新時刻（UNIX エポックからのミリ秒）。
 * @property sha256 Drive が返す SHA-256（小文字の 16 進数、取得できない場合は `null`）。
 * @property mediaId 対応するローカルの教材（削除されると `null`、または CASCADE で連動）。
 */
data class DriveFile(
    val driveFileId: String,
    val kind: DriveFileKind,
    val course: String,
    val modifiedTimeEpochMs: Long,
    val sha256: String? = null,
    val mediaId: MediaId? = null,
)
