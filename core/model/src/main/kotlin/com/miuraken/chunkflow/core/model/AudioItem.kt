package com.miuraken.chunkflow.core.model

/**
 * 教材の音声ファイルの実体の保持方式・状態。
 */
enum class AudioStorageState {
    /** アプリ内部ストレージ（`filesDir/media/` 等）にコピーまたはキャッシュ保持している状態。 */
    APP_MANAGED,

    /** ローカルファイルシステム（SAF フォルダ・ファイル）をコピーせず直接参照している状態。 */
    LOCAL_REFERENCE,
}

/**
 * ライブラリに取り込んだ教材（音声ファイル）1 件。
 *
 * @property id 教材の識別子（UUID）。
 * @property title 表示専用のタイトル。ファイルパスには使わない（§8.1）。
 * @property durationMs 音声の長さ（ミリ秒）。
 * @property mimeType 検証済みの MIME タイプ。
 * @property sizeBytes ファイルサイズ（バイト）。
 * @property contentSha256 音声ファイルのバイト列の SHA-256（小文字の 16 進数）。重複の検出と、文字起こしファイルとの対応付けに使うキー。
 * @property course コース名（フォルダ名または仮想ディレクトリ名）。手動で取り込んだ場合は任意。
 * @property languagePair 教材の言語ペア。
 * @property transcriptOffsetMs 文字起こしの時刻に足す補正値（§7.4.6）。
 * @property importedAtEpochMs 取り込んだ時刻（UNIX エポックからのミリ秒）。
 * @property sourceUri ローカルの元ファイルを参照する SAF / ファイル URI 文字列（コピーを作らない場合）。
 * @property localFilePath ローカルファイルシステム上の実ファイルパス（解決できた場合）。
 * @property storageState 音声実体の保持状態（ローカル参照 / アプリ管理）。
 */
data class AudioItem(
    val id: MediaId,
    val title: String,
    val durationMs: Long,
    val mimeType: String,
    val sizeBytes: Long,
    val contentSha256: String,
    val course: String?,
    val languagePair: LanguagePair,
    val transcriptOffsetMs: Int = 0,
    val importedAtEpochMs: Long,
    val sourceUri: String? = null,
    val localFilePath: String? = null,
    val storageState: AudioStorageState = AudioStorageState.APP_MANAGED,
)
