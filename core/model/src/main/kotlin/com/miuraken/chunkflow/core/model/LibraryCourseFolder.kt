package com.miuraken.chunkflow.core.model

/**
 * ライブラリ内のフォルダ（コース）の種別。
 */
enum class LibraryFolderKind {
    /** Android ローカルファイルシステムのディレクトリ参照（音声コピーなし、`.chunkflow` 管理）。 */
    LOCAL_DIRECTORY,

    /** 個別選択ファイルをグループ化して入れる仮想ディレクトリ（既定: `imported`）。 */
    VIRTUAL_DIRECTORY,

    /** Google Drive 上のフォルダ連携（オンデマンドダウンロード、`.chunkflow` 同期）。 */
    GOOGLE_DRIVE_FOLDER,
}

/**
 * ライブラリに登録されたフォルダ（コースまたは仮想ディレクトリ）の設定情報。
 *
 * @property coursePath コース（ディレクトリ）の一意なパス名（例: `"NHKラジオ"`, `"imported"`）。
 * @property kind フォルダの種別。
 * @property languagePair コースに設定された学習言語・母語ペア。
 * @property treeUri ローカルフォルダの場合の SAF Tree URI 文字列。
 * @property driveFolderId Google Drive フォルダの場合のフォルダ ID。
 * @property lastScannedAtEpochMs 最終スキャン・同期時刻（ミリ秒）。
 */
data class LibraryCourseFolder(
    val coursePath: String,
    val kind: LibraryFolderKind,
    val languagePair: LanguagePair,
    val treeUri: String? = null,
    val driveFolderId: String? = null,
    val lastScannedAtEpochMs: Long = 0L,
) {
    companion object {
        /** 単一・複数ファイル選択時に既定で格納される仮想ディレクトリ名。 */
        const val DEFAULT_IMPORTED_COURSE: String = "imported"
    }
}
