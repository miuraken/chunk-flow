package com.miuraken.chunkflow.core.model

/**
 * ライブラリに取り込んだ教材（音声ファイル）1 件。
 *
 * @property id 教材の識別子（UUID）。
 * @property title 表示専用のタイトル。ファイルパスには使わない（§8.1）。
 * @property durationMs 音声の長さ（ミリ秒）。
 * @property mimeType 検証済みの MIME タイプ。
 * @property sizeBytes ファイルサイズ（バイト）。
 * @property contentSha256 音声ファイルのバイト列の SHA-256（小文字の 16 進数）。重複の検出と、文字起こしファイルとの対応付けに使うキー。
 * @property course コース名（Drive のコースフォルダ名）。手動で取り込んだ場合は任意。
 * @property languagePair 教材の言語ペア。
 * @property transcriptOffsetMs 文字起こしの時刻に足す補正値（§7.4.6）。
 * @property importedAtEpochMs 取り込んだ時刻（UNIX エポックからのミリ秒）。
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
)
