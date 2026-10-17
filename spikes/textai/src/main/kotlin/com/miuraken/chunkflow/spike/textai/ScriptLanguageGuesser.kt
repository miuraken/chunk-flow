package com.miuraken.chunkflow.spike.textai

import java.text.Normalizer

/** BCP-47 の言語タグの主タグ（小文字）。例: `"zh-Hant"` → `"zh"`、`"yue"` → `"yue"`。 */
internal fun primaryLanguage(tag: String): String = tag.trim().substringBefore('-').lowercase()

/** 前後の比較用に、NFC に正規化して前後の空白を除き、連続する空白を 1 つにする。 */
internal fun normalizeForCompare(text: String): String {
    val nfc = Normalizer.normalize(text, Normalizer.Form.NFC).trim()
    val builder = StringBuilder(nfc.length)
    var previousWasSpace = false
    for (ch in nfc) {
        val isSpace = ch.isWhitespace()
        if (isSpace && previousWasSpace) continue
        builder.append(if (isSpace) ' ' else ch)
        previousWasSpace = isSpace
    }
    return builder.toString()
}

/** 文字数（コードポイント）。CSV には本文の代わりにこれを書く。 */
internal fun codePointLength(text: String): Int = text.codePointCount(0, text.length)

/**
 * SRT / LRC / WebVTT（セグメントごとの言語がない）で、文字の種類から学習言語かベース言語かを推定する。
 *
 * 2 つの言語の主な文字の種類が同じ組（英語とフランス語、中国語と広東語など）は区別できないので `null` を返す。
 * 日本語と中国語は、仮名があれば日本語、漢字だけなら中国語とみなす（漢字だけの短い日本語は中国語と判定される）。
 */
internal object ScriptLanguageGuesser {

    /** 推定に使う文字の種類。 */
    enum class Script { LATIN, HANGUL, KANA, HAN, DEVANAGARI, CYRILLIC, GREEK, THAI, ARABIC, OTHER }

    /** [text] が [target] と [base] のどちらの言語か。判定できなければ `null`。 */
    fun guess(text: String, target: String, base: String?): String? {
        if (base == null || !canDistinguish(target, base)) return null
        val counts = countScripts(text)
        val targetScore = score(target, counts)
        val baseScore = score(base, counts)
        return when {
            targetScore > baseScore -> target
            baseScore > targetScore -> base
            else -> null
        }
    }

    /** 2 つの言語を文字の種類で区別できるか。 */
    fun canDistinguish(target: String, base: String): Boolean = primaryScript(target) != primaryScript(base)

    /** 言語の主な文字の種類。 */
    fun primaryScript(language: String): Script = when (primaryLanguage(language)) {
        "ko" -> Script.HANGUL
        "ja" -> Script.KANA
        "zh", "yue", "cmn" -> Script.HAN
        "hi", "mr", "ne", "sa" -> Script.DEVANAGARI
        "ru", "uk", "bg", "be", "mk", "sr", "kk" -> Script.CYRILLIC
        "el" -> Script.GREEK
        "th" -> Script.THAI
        "ar", "fa", "ur" -> Script.ARABIC
        else -> Script.LATIN
    }

    private fun score(language: String, counts: Map<Script, Int>): Int {
        val kana = counts[Script.KANA] ?: 0
        val han = counts[Script.HAN] ?: 0
        val hangul = counts[Script.HANGUL] ?: 0
        return when (val script = primaryScript(language)) {
            // 日本語は仮名があるときだけ、漢字も日本語の文字として数える
            Script.KANA -> if (kana > 0) kana + han else 0

            // 中国語・広東語は、仮名もハングルもないときだけ漢字を数える
            Script.HAN -> if (kana == 0 && hangul == 0) han else 0

            else -> counts[script] ?: 0
        }
    }

    private fun countScripts(text: String): Map<Script, Int> {
        val counts = mutableMapOf<Script, Int>()
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            index += Character.charCount(codePoint)
            if (!Character.isLetter(codePoint)) continue
            val script = when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.LATIN -> Script.LATIN
                Character.UnicodeScript.HANGUL -> Script.HANGUL
                Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Script.KANA
                Character.UnicodeScript.HAN -> Script.HAN
                Character.UnicodeScript.DEVANAGARI -> Script.DEVANAGARI
                Character.UnicodeScript.CYRILLIC -> Script.CYRILLIC
                Character.UnicodeScript.GREEK -> Script.GREEK
                Character.UnicodeScript.THAI -> Script.THAI
                Character.UnicodeScript.ARABIC -> Script.ARABIC
                else -> Script.OTHER
            }
            counts[script] = (counts[script] ?: 0) + 1
        }
        return counts
    }
}
