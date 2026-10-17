package com.miuraken.chunkflow.core.model

/**
 * 言語設定（全体の設定画面の「言語設定」タブ）の決まりごと（ADR-0011 §2.1）。
 *
 * どの言語を選べるか、どの順に並べるか、既定でどれを有効にするかを、Android に依存しない純粋な関数で表す。
 */
object LanguageSettings {
    /**
     * 一覧の上に固定して表示する 13 言語のコード（言語コード順）。
     *
     * アラビア語・ドイツ語・英語・スペイン語・フランス語・ヒンディー語・イタリア語・日本語・韓国語・
     * ポルトガル語・ロシア語・簡体中国語・繁体中国語。
     */
    val PINNED_LANGUAGE_CODES: List<String> = listOf(
        "ar",
        "de",
        "en",
        "es",
        "fr",
        "hi",
        "it",
        "ja",
        "ko",
        "pt",
        "ru",
        "zh-cn",
        "zh-tw",
    )

    /** 端末の言語に関係なく、既定で有効にする言語（英語）。 */
    val ALWAYS_ENABLED_BY_DEFAULT: LanguageTag = LanguageTag("en")

    /**
     * ML Kit GenAI Speech Recognition が対応している言語のコード（公式の対応表。基本モードと高度モードの和）。
     *
     * 端末で実際に使えるかは、実行時に確かめる（`core:media`）。ここでは一覧に出すかどうかの判定にだけ使う。
     */
    val ML_KIT_SPEECH_LANGUAGE_CODES: Set<String> = setOf(
        "ar",
        "da",
        "de",
        "en",
        "es",
        "fr",
        "hi",
        "id",
        "it",
        "ja",
        "ko",
        "nl",
        "pl",
        "pt",
        "ru",
        "sv",
        "th",
        "tr",
        "vi",
        "zh-cn",
        "zh-tw",
    )

    /**
     * 言語タグを、設定で使うカタログの言語コードにそろえる。
     *
     * [SupportedLanguageCatalog.normalizeToCatalogCode] で正規化したうえで、区別のない中国語（`zh`）は
     * 簡体中国語（`zh-cn`）として扱う。
     */
    fun normalize(language: LanguageTag): LanguageTag = LanguageTag(normalizeCode(language.value))

    /** 言語コード（BCP-47 タグでもよい）を、設定で使うカタログの言語コードにそろえる。空のときは空文字列。 */
    fun normalizeCode(rawCode: String): String {
        val catalogCode = SupportedLanguageCatalog.normalizeToCatalogCode(rawCode)
        return if (catalogCode == "zh") "zh-cn" else catalogCode
    }

    /**
     * どれかのエンジン（ML Kit・SpeechRecognizer・sherpa-onnx の Whisper）で、カタログ上は文字起こしできる言語かどうか。
     */
    fun isSttCapable(entry: SupportedLanguageEntry): Boolean = entry.speechRecognizerSupported ||
        entry.whisperCode != null ||
        entry.code in ML_KIT_SPEECH_LANGUAGE_CODES

    /**
     * 言語設定タブに並べる言語の一覧。
     *
     * 固定の 13 言語（[PINNED_LANGUAGE_CODES]、言語コード順）を先に、そのあとに文字起こしできる残りの言語を
     * 言語コード順に並べる。区別のない中国語（`zh`）は出さない（`zh-cn` / `zh-tw` を使う）。
     */
    val SELECTABLE_LANGUAGES: List<SupportedLanguageEntry> by lazy {
        val candidates = SupportedLanguageCatalog.ALL_ENTRIES.filter { it.code != "zh" }
        val pinned = PINNED_LANGUAGE_CODES.mapNotNull { code -> candidates.firstOrNull { it.code == code } }
        val pinnedCodes = pinned.map { it.code }.toSet()
        val others = candidates
            .filter { it.code !in pinnedCodes && isSttCapable(it) }
            .sortedBy { it.code }
        pinned + others
    }

    /** 言語コード [code] が、固定表示の 13 言語に入っているかどうか。 */
    fun isPinned(code: String): Boolean = normalizeCode(code) in PINNED_LANGUAGE_CODES

    /**
     * 既定で有効にする言語。端末の設定言語（[deviceLanguageTags]、複数あればすべて）と英語。
     *
     * 端末の言語は [normalizeCode] でそろえ、言語設定タブで選べる言語（[SELECTABLE_LANGUAGES]）にあるものだけを使う。
     * 戻り値は空にならない（少なくとも英語を含む）。
     */
    fun defaultEnabledLanguages(deviceLanguageTags: List<String>): Set<LanguageTag> {
        val selectableCodes = SELECTABLE_LANGUAGES.map { it.code }.toSet()
        val fromDevice = deviceLanguageTags
            .map(::normalizeCode)
            .filter { it in selectableCodes }
        return (fromDevice + ALWAYS_ENABLED_BY_DEFAULT.value)
            .map(::LanguageTag)
            .toCollection(LinkedHashSet())
    }
}
