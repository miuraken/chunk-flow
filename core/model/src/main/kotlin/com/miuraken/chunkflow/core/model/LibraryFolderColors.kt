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
    /** アコーディオンを閉じたときに 1 行に表示する色数。 */
    const val COLLAPSED_SWATCH_COUNT: Int = 6

    /** アコーディオンを開いたときの行数（1 行目の 3 倍の色数）。 */
    const val EXPANDED_ROW_COUNT: Int = 3

    /** パレットの色数（6 色 × 3 行 = 18 色）。 */
    const val PALETTE_SIZE: Int = COLLAPSED_SWATCH_COUNT * EXPANDED_ROW_COUNT

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

    /**
     * フォルダ設定の色見本で並べるパレット番号の順番を返す。
     *
     * - ふだんアコーディオンで隠れている色（[COLLAPSED_SWATCH_COUNT] 番目以降）が選ばれた場合、
     *   アコーディオンを閉じても選んだ色とデフォルトの色（[defaultIndex]）が常に見えるように、
     *   選択した色を一番左上（先頭）に持ってくる。
     * - [defaultIndex] が 1 行目（先頭 [COLLAPSED_SWATCH_COUNT] 個）に入らない場合も、1 行目に見える位置へ移動する。
     */
    fun orderedPaletteIndices(
        selectedIndex: Int?,
        defaultIndex: Int?,
    ): List<Int> {
        val ordered = (0 until PALETTE_SIZE).toMutableList()
        val validSelected = selectedIndex?.takeIf(::isValidIndex)
        val validDefault = defaultIndex?.takeIf(::isValidIndex)
        if (validSelected != null && validSelected >= COLLAPSED_SWATCH_COUNT) {
            ordered.remove(validSelected)
            ordered.add(0, validSelected)
        }
        if (validDefault != null && validDefault !in ordered.take(COLLAPSED_SWATCH_COUNT)) {
            ordered.remove(validDefault)
            val targetPos = if (validSelected == null) {
                0
            } else {
                (COLLAPSED_SWATCH_COUNT - 1).coerceAtLeast(1)
            }
            ordered.add(targetPos.coerceAtMost(ordered.size), validDefault)
        }
        return ordered
    }
}
