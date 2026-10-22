package com.miuraken.chunkflow.core.model

/**
 * ライブラリ内のフォルダ（コース）の種別。
 */
enum class LibraryFolderKind {
    /** Android ローカルファイルシステムのディレクトリ参照（音声コピーなし）。 */
    LOCAL_DIRECTORY,

    /** 個別選択ファイルをグループ化して入れる仮想ディレクトリ（既定: `default`）。 */
    VIRTUAL_DIRECTORY,
}

/**
 * ライブラリに登録されたフォルダ（コースまたは仮想ディレクトリ）の設定情報。
 *
 * @property coursePath コース（ディレクトリ）の一意なパス名（例: `"NHKラジオ"`, `"default"`）。
 * @property kind フォルダの種別。
 * @property languagePair コースに設定された学習言語・母語ペア。
 * @property treeUri ローカルフォルダの場合の SAF Tree URI 文字列。
 * @property lastScannedAtEpochMs 最終スキャン時刻（ミリ秒）。
 * @property sortOrder ルートフォルダの表示順序（小さい値ほど上に表示。`null` の場合は名前順）。
 * @property colorIndex フォルダに指定した色（[LibraryFolderColors] のパレットの番号）。`null` なら対象言語の既定の色。
 */
data class LibraryCourseFolder(
    val coursePath: String,
    val kind: LibraryFolderKind,
    val languagePair: LanguagePair,
    val treeUri: String? = null,
    val lastScannedAtEpochMs: Long = 0L,
    val sortOrder: Int? = null,
    val colorIndex: Int? = null,
) {
    companion object {
        /** 単一・複数ファイル選択時に既定で格納される仮想ディレクトリ名。 */
        const val DEFAULT_IMPORTED_COURSE: String = "default"
    }
}
