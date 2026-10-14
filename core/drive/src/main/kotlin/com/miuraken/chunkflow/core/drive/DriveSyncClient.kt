package com.miuraken.chunkflow.core.drive

import java.io.File

/**
 * Google Drive クライアントの失敗種別（§7.1、§8.1、§9.2）。
 */
enum class DriveError {
    /** 未認証、トークン失効、または権限不足（HTTP 401 / 403）。 */
    AUTH,

    /** 通信失敗、タイムアウト、ストリーム切断、またはサーバエラー。 */
    NETWORK,

    /** Google Drive 上に `ChunkFlow/` フォルダまたは対象ファイルが見つからない（HTTP 404）。 */
    FOLDER_NOT_FOUND,

    /** ファイルサイズが上限（音声 2GB / 文字起こし 20MB）を超えている（§8.1）。 */
    TOO_LARGE,

    /** API の応答 JSON が不正またはスキーマ外（§8.1、§9.2）。 */
    INVALID_RESPONSE,
}

/**
 * Google Drive 上の 1 ファイルのメタデータ（§6.4、§7.1）。
 *
 * @property id Drive のファイル ID。
 * @property name ファイル名。
 * @property mimeType MIME タイプ。
 * @property sizeBytes ファイルサイズ（バイト、不明な場合は `0L`）。
 * @property modifiedTimeEpochMs 更新時刻（UNIX エポックミリ秒）。
 * @property sha256Checksum Drive が返す SHA-256（小文字の 16 進数、取得できない場合は `null`）。
 */
data class DriveRemoteFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val modifiedTimeEpochMs: Long,
    val sha256Checksum: String? = null,
)

/**
 * `ChunkFlow/<コース名>/` 配下の音声ファイル群と `transcription/ 内の .chunkflow.json` ファイル群。
 */
data class DriveCourseFiles(
    val courseName: String,
    val audioFiles: List<DriveRemoteFile>,
    val transcriptFiles: List<DriveRemoteFile>,
)

/** [DriveSyncClient.listChunkFlowCourses] の結果。 */
sealed interface DriveListResult {
    /** コース一覧と各ファイルのメタデータ取得に成功した。 */
    data class Success(val courses: List<DriveCourseFiles>) : DriveListResult

    /** 取得に失敗した。 */
    data class Failure(val error: DriveError) : DriveListResult
}

/** [DriveSyncClient.downloadToFile] の結果。 */
sealed interface DriveDownloadResult {
    /** ダウンロードと SHA-256 の計算に成功した。 */
    data class Success(
        val bytesWritten: Long,
        val sha256Hex: String,
    ) : DriveDownloadResult

    /** ダウンロードに失敗した。 */
    data class Failure(val error: DriveError) : DriveDownloadResult
}

/** [DriveSyncClient.downloadBytes] の結果。 */
sealed interface DriveBytesDownloadResult {
    /** バイト列のダウンロードに成功した。 */
    data class Success(val bytes: ByteArray) : DriveBytesDownloadResult

    /** ダウンロードに失敗した。 */
    data class Failure(val error: DriveError) : DriveBytesDownloadResult
}

/** [DriveSyncClient.uploadTranscriptJson] の結果。 */
sealed interface DriveUploadResult {
    /** アップロード（新規作成または更新）に成功した。 */
    data class Success(val file: DriveRemoteFile) : DriveUploadResult

    /** アップロードに失敗した。 */
    data class Failure(val error: DriveError) : DriveUploadResult
}

/**
 * Google Drive REST API v3 の最小クライアント（§6.3、§7.1、§8.1、§8.3、§9.2）。
 *
 * - `ChunkFlow/<コース名>/` 直下の音声ファイルと `ChunkFlow/<コース名>/transcription/ 内の .chunkflow.json` を列挙する。
 * - すべての応答とストリームは信頼できない入力として扱い、サイズ上限（音声 2GB / 文字起こし 20MB）を超える場合は中断する。
 * - ローカルで生成・編集された `.chunkflow.json` を `ChunkFlow/<コース名>/transcription/` へアップロードする。
 */
interface DriveSyncClient {
    /** `ChunkFlow/` 配下のすべてのコースフォルダと音声・文字起こしファイル一覧を取得する。 */
    suspend fun listChunkFlowCourses(): DriveListResult

    /**
     * 指定した [fileId] のファイルを [destFile] にストリーミングでダウンロードし、SHA-256 を計算する。
     */
    suspend fun downloadToFile(
        fileId: String,
        destFile: File,
        maxSizeBytes: Long = Companion.MAX_AUDIO_SIZE_BYTES,
        onProgress: ((Long) -> Unit)? = null,
    ): DriveDownloadResult

    /**
     * 指定した [fileId] のファイルを上限 [maxSizeBytes] 以内でメモリにダウンロードする（文字起こしファイル用）。
     */
    suspend fun downloadBytes(
        fileId: String,
        maxSizeBytes: Long = Companion.MAX_TRANSCRIPT_SIZE_BYTES,
    ): DriveBytesDownloadResult

    /**
     * `ChunkFlow/<courseName>/transcription/<fileName>` に `.chunkflow.json` をアップロード（作成または更新）する。
     */
    suspend fun uploadTranscriptJson(
        courseName: String,
        fileName: String,
        jsonBytes: ByteArray,
    ): DriveUploadResult

    companion object {
        /** 音声 1 ファイルあたりの最大サイズ: 2 GB（§8.1）。 */
        const val MAX_AUDIO_SIZE_BYTES: Long = 2L * 1024L * 1024L * 1024L

        /** 文字起こし 1 ファイルあたりの最大サイズ: 20 MB（§8.1）。 */
        const val MAX_TRANSCRIPT_SIZE_BYTES: Long = 20L * 1024L * 1024L
    }
}
