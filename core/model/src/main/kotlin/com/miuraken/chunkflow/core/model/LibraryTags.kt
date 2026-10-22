package com.miuraken.chunkflow.core.model

import java.text.Normalizer
import java.util.Locale

/**
 * ライブラリのファイル（教材）に付けるタグの決まりごと（名前の正規化・同じタグかどうかの判定・上限）。
 *
 * タグは自由入力の文字列。大文字・小文字や全角・半角の違いだけのタグは同じタグとして扱い（[key]）、
 * 最初に作られたときの表記で表示する。Android に依存しない純粋な関数で表す。
 */
object LibraryTags {
    /** タグ名の最大の長さ（コードポイント数）。 */
    const val MAX_NAME_LENGTH: Int = 32

    /** 1 つのファイルに付けられるタグの最大数。 */
    const val MAX_TAGS_PER_ITEM: Int = 30

    private val WHITESPACE_RUN = Regex("\\s+")

    /** 表示を乱す双方向テキストの制御文字（LRE/RLE/PDF/LRO/RLO と LRI/RLI/FSI/PDI）。 */
    private val BIDI_CONTROL_CODE_POINTS: Set<Int> = (0x202A..0x202E).toSet() + (0x2066..0x2069).toSet()

    /**
     * 入力されたタグ名を、保存・表示に使う形にそろえる。
     *
     * 前後の空白を除き、連続する空白（改行・タブを含む）を半角スペース 1 つにまとめ、NFC に正規化する。
     * 空のとき、[MAX_NAME_LENGTH] を超えるとき、制御文字や双方向テキストの制御文字を含むときは `null` を返す。
     */
    fun normalizeName(raw: String): String? {
        val collapsed = raw.replace(WHITESPACE_RUN, " ").trim()
        if (collapsed.isEmpty()) return null
        val normalized = Normalizer.normalize(collapsed, Normalizer.Form.NFC)
        if (normalized.codePointCount(0, normalized.length) > MAX_NAME_LENGTH) return null
        val hasForbiddenChar = normalized.codePoints().anyMatch { codePoint ->
            Character.isISOControl(codePoint) || codePoint in BIDI_CONTROL_CODE_POINTS
        }
        return if (hasForbiddenChar) null else normalized
    }

    /**
     * 同じタグかどうかを比べるためのキー（NFKC で全角・半角をそろえ、小文字にしたもの）。
     *
     * [name] は [normalizeName] でそろえた名前を渡す。
     */
    fun key(name: String): String = Normalizer.normalize(name, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    /**
     * タグ名の一覧を [normalizeName] でそろえ、同じキー（[key]）の重複を除く（最初に出てきた表記を残す）。
     *
     * 正しくない名前は捨てる。並び順は入力の順を保つ。
     */
    fun normalizeNames(rawNames: Iterable<String>): List<String> {
        val seenKeys = HashSet<String>()
        val result = ArrayList<String>()
        for (raw in rawNames) {
            val name = normalizeName(raw) ?: continue
            if (seenKeys.add(key(name))) {
                result += name
            }
        }
        return result
    }
}
