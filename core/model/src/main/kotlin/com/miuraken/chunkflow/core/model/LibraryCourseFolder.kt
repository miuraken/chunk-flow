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
    /**
     * このフォルダの音声認識エンジンの上書きのうち、言語ペア [itemLanguages] のファイルに効く部分を返す。
     *
     * フォルダの上書きは、フォルダの設定のシートで選んだ言語ペア（[languagePair]）に対するエンジンの指定なので、
     * 役割（学習対象 / ベース）ごとに、ファイルの言語がフォルダの言語と同じときだけ効く
     * （[LanguageSettings.normalize] でそろえて比べる）。[itemLanguages] が `null` なら上書きをそのまま返す。
     */
    fun sttOverrideFor(itemLanguages: LanguagePair?): ItemSttOverride {
        if (itemLanguages == null || sttOverride.isEmpty) return sttOverride
        val targetMatches = LanguageSettings.normalize(languagePair.target) ==
            LanguageSettings.normalize(itemLanguages.target)
        val folderBase = languagePair.base
        val itemBase = itemLanguages.base
        val baseMatches = folderBase != null &&
            itemBase != null &&
            LanguageSettings.normalize(folderBase) == LanguageSettings.normalize(itemBase)
        return ItemSttOverride(
            targetEngine = sttOverride.targetEngine?.takeIf { targetMatches },
            baseEngine = sttOverride.baseEngine?.takeIf { baseMatches },
        )
    }

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
         * フォルダ [course] に入っている、言語ペア [itemLanguages] のファイルに効く、フォルダ単位の音声認識エンジンの
         * 上書きを返す。
         *
         * - 近いフォルダの設定を優先する。言語の役割（学習対象 / ベース）ごとに、そのフォルダが上書きしていなければ
         *   （またはフォルダの言語がファイルの言語と違えば。[sttOverrideFor]）親フォルダの設定を使う。
         * - どのフォルダも効かなければ [ItemSttOverride.NONE]（全体設定に従う）。
         * - ファイル個別の設定は含まない。呼び出し側で `itemOverride.withFolderFallback(結果)` のように合成する
         *   （ファイル個別設定 ＞ フォルダ設定 ＞ 全体設定）。
         *
         * @param course ファイルのフォルダのパス（`AudioItem.course`）。
         * @param itemLanguages ファイルの言語ペア。`null` ならフォルダの言語と比べない。
         * @param folderOf フォルダのパス（[selfAndAncestorPaths] の形）から、そのフォルダの設定を返す。
         *   設定の行がなければ `null`。
         */
        inline fun resolveSttOverride(
            course: String?,
            itemLanguages: LanguagePair?,
            folderOf: (path: String) -> LibraryCourseFolder?,
        ): ItemSttOverride {
            var resolved = ItemSttOverride.NONE
            for (path in selfAndAncestorPaths(course)) {
                val folder = folderOf(path) ?: continue
                resolved = resolved.withFolderFallback(folder.sttOverrideFor(itemLanguages))
                if (resolved.targetEngine != null && resolved.baseEngine != null) break
            }
            return resolved
        }

        /**
         * フォルダの設定の一覧 [folders] から、フォルダ [course] に入っている言語ペア [itemLanguages] のファイルに効く
         * 音声認識エンジンの上書きを返す。
         *
         * 決め方は `resolveSttOverride(course, itemLanguages, folderOf)` と同じ。
         */
        fun resolveSttOverride(
            course: String?,
            itemLanguages: LanguagePair?,
            folders: Collection<LibraryCourseFolder>,
        ): ItemSttOverride {
            if (folders.isEmpty()) return ItemSttOverride.NONE
            val folderByPath = folders.associateBy { folder ->
                selfAndAncestorPaths(folder.coursePath).firstOrNull().orEmpty()
            }
            return resolveSttOverride(course, itemLanguages) { path -> folderByPath[path] }
        }
    }
}
