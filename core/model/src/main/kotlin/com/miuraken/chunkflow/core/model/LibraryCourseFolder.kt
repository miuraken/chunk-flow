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
 * @property sttOverride フォルダ単位で指定した音声認識エンジンの上書き設定。
 */
data class LibraryCourseFolder(
    val coursePath: String,
    val kind: LibraryFolderKind,
    val languagePair: LanguagePair,
    val treeUri: String? = null,
    val lastScannedAtEpochMs: Long = 0L,
    val sortOrder: Int? = null,
    val colorIndex: Int? = null,
    val sttOverride: ItemSttOverride = ItemSttOverride.NONE,
) {
    companion object {
        /** 単一・複数ファイル選択時に既定で格納される仮想ディレクトリ名。 */
        const val DEFAULT_IMPORTED_COURSE: String = "default"

        /** Google Drive のファイルを取り込むときに既定で格納される仮想ディレクトリ名。 */
        const val DEFAULT_GOOGLE_DRIVE_COURSE: String = "Google Drive から"

        /**
         * フォルダのパス [course] と、その親フォルダのパスを近い順に返す（`"A/B/C"` → `["A/B/C", "A/B", "A"]`）。
         *
         * 前後の `/`・空の区切り（`"A//B"`）・区切りの前後の空白は無視する。[course] が空なら空のリスト。
         */
        fun selfAndAncestorPaths(course: String?): List<String> {
            val segments = course.orEmpty().split('/').map(String::trim).filter(String::isNotEmpty)
            return (segments.size downTo 1).map { count -> segments.take(count).joinToString("/") }
        }

        /**
         * フォルダ [course] に入っているファイルに効く、フォルダ単位の音声認識エンジンの上書きを返す。
         *
         * - 近いフォルダの設定を優先する。言語（学習対象 / ベース）ごとに、そのフォルダが上書きしていなければ
         *   親フォルダの設定を使う。
         * - どのフォルダも上書きしていなければ [ItemSttOverride.NONE]（全体設定に従う）。
         * - ファイル個別の設定は含まない。呼び出し側で `itemOverride.withFolderFallback(結果)` のように合成する
         *   （ファイル個別設定 ＞ フォルダ設定 ＞ 全体設定）。
         *
         * @param course ファイルのフォルダのパス（`AudioItem.course`）。
         * @param overrideOf フォルダのパス（[selfAndAncestorPaths] の形）から、そのフォルダの上書き設定を返す。
         *   設定の行がなければ `null`。
         */
        inline fun resolveSttOverride(
            course: String?,
            overrideOf: (path: String) -> ItemSttOverride?,
        ): ItemSttOverride {
            var resolved = ItemSttOverride.NONE
            for (path in selfAndAncestorPaths(course)) {
                val folderOverride = overrideOf(path) ?: continue
                resolved = resolved.withFolderFallback(folderOverride)
                if (resolved.targetEngine != null && resolved.baseEngine != null) break
            }
            return resolved
        }

        /**
         * フォルダの設定の一覧 [folders] から、フォルダ [course] のファイルに効く音声認識エンジンの上書きを返す。
         *
         * 決め方は `resolveSttOverride(course, overrideOf)` と同じ（近いフォルダ優先、言語ごとに親フォルダへ）。
         */
        fun resolveSttOverride(
            course: String?,
            folders: Collection<LibraryCourseFolder>,
        ): ItemSttOverride {
            if (folders.isEmpty()) return ItemSttOverride.NONE
            val overrideByPath = folders.associate { folder ->
                selfAndAncestorPaths(folder.coursePath).firstOrNull().orEmpty() to folder.sttOverride
            }
            return resolveSttOverride(course) { path -> overrideByPath[path] }
        }
    }
}
