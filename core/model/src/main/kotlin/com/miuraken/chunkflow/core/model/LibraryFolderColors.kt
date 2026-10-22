package com.miuraken.chunkflow.core.model

/**
 * ライブラリのフォルダの色分け（決め打ちのパレットの番号）の決まりごと。
 *
 * - 既定の色は対象言語ごとに決まる。対象言語が初めてフォルダに使われた順（0 始まりの順番）に、パレットの色を
 *   先頭から割り当てる。順番はデータベースに保存し、フォルダを消しても変わらない。
 * - フォルダごとに色を指定できる（[LibraryCourseFolder.colorIndex]）。指定を外すと既定の色に戻る。
 * - パレットの実際の色（ライト / ダーク）は `core:designsystem` が [PALETTE_SIZE] 色で持つ。
 */
object LibraryFolderColors {
    /** パレットの色数。 */
    const val PALETTE_SIZE: Int = 8

    /** [index] がパレットの番号として正しいかどうか。 */
    fun isValidIndex(index: Int): Boolean = index in 0 until PALETTE_SIZE

    /**
     * 色の順番を数えるときの対象言語のキー（設定のカタログの言語コード。区別のない中国語 `zh` は `zh-cn`）。
     *
     * 空のときは `null`。
     */
    fun languageKey(rawLanguageCode: String): String? = LanguageSettings.normalizeCode(rawLanguageCode).takeIf { it.isNotBlank() }

    /** 対象言語の順番（0 始まり）から、パレットの番号を求める。色数を超えたら先頭から繰り返す。 */
    fun indexForOrdinal(ordinal: Int): Int = Math.floorMod(ordinal, PALETTE_SIZE)

    /**
     * フォルダの色（パレットの番号）を決める。
     *
     * @param colorIndex フォルダに指定された色。正しい番号ならこれを使う。
     * @param targetLanguage フォルダの対象言語（不明なら `null`）。
     * @param languageOrdinals 対象言語のキー（[languageKey]）ごとの順番。
     * @return パレットの番号。色を指定しておらず対象言語も不明なら `null`。まだ順番のない対象言語は、
     *   次に割り当てられる順番の色にする。
     */
    fun resolveIndex(
        colorIndex: Int?,
        targetLanguage: LanguageTag?,
        languageOrdinals: Map<LanguageTag, Int>,
    ): Int? {
        if (colorIndex != null && isValidIndex(colorIndex)) return colorIndex
        val key = targetLanguage?.let { languageKey(it.value) } ?: return null
        val ordinal = languageOrdinals[LanguageTag(key)]
            ?: ((languageOrdinals.values.maxOrNull() ?: -1) + 1)
        return indexForOrdinal(ordinal)
    }
}
