package com.miuraken.chunkflow.spike.textai

/**
 * Prompt API（Gemini Nano）に渡す指示文と、返ってきた文の読み取り（方式 B と D）。
 *
 * 小さいモデルが従いやすいよう、指示は英語で書く。校正の出力の見出し（元の文 / 案 / 理由）は日本語にする（計画の 2）。
 */
internal object PromptTemplates {

    /** 文脈として渡す直前のチャンクの最大文字数（プロンプトを短く保つ）。 */
    const val MAX_CONTEXT_CHARS: Int = 300

    /** 方式 B の最大出力トークン数。 */
    const val TRANSLATION_MAX_OUTPUT_TOKENS: Int = 256

    /** 方式 D の最大出力トークン数。 */
    const val PROOFREADING_MAX_OUTPUT_TOKENS: Int = 384

    /**
     * 方式 B: 学習言語の文をベース言語に訳す指示。
     *
     * @param sourceName 学習言語の英語名（例: `"Korean"`）。
     * @param targetName ベース言語の英語名（例: `"Japanese"`）。
     * @param previous 直前のチャンク（言語は問わない）。文脈として渡すだけで、訳させない。
     */
    fun translation(sourceName: String, targetName: String, text: String, previous: PreviousContext?): String = buildString {
        append("Translate the following ").append(sourceName).append(" sentence into ").append(targetName).append(".\n")
        append("It is a line from a language-learning audio course, transcribed by speech recognition.\n")
        append("Output only the ").append(targetName).append(" translation. ")
        append("Do not add explanations, notes, quotation marks, or romanization.\n")
        if (previous != null) {
            append("\nFor context, the line spoken just before it was")
            previous.languageName?.let { append(" (").append(it).append(")") }
            append(": ").append(previous.text.take(MAX_CONTEXT_CHARS)).append('\n')
            append("Do not translate the context line.\n")
        }
        append('\n').append(sourceName).append(" sentence: ").append(text).append('\n')
        append(targetName).append(" translation:")
    }

    /**
     * 方式 D: 音声認識の誤りを直す案を「元の文 / 案 / 理由」の 3 行で出させる指示。
     *
     * @param languageName 学習言語の英語名。
     */
    fun proofreading(languageName: String, text: String): String = buildString {
        append("The following ").append(languageName)
        append(" sentence is a speech-recognition transcript of a line from a language-learning audio course.\n")
        append("It may contain recognition errors (wrong words, missing words, wrong spelling or spacing).\n")
        append("If there is an error, propose a corrected sentence. If the sentence is already correct, repeat it unchanged.\n")
        append("Do not rephrase correct sentences, and keep the spoken style.\n")
        append("Answer in exactly three lines, in this format:\n")
        append(LABEL_ORIGINAL).append(": <the original sentence>\n")
        append(LABEL_SUGGESTION).append(": <the corrected sentence>\n")
        append(LABEL_REASON).append(": <a short reason in Japanese, or ").append(NO_CHANGE_REASON).append(">\n")
        append('\n').append("Sentence: ").append(text)
    }

    /** 方式 B の出力から訳文だけを取り出す（前後の空白・引用符・見出しを除く）。空なら `null`。 */
    fun cleanTranslation(output: String?, targetName: String): String? {
        if (output == null) return null
        var text = output.trim()
        val label = "$targetName translation:"
        if (text.startsWith(label, ignoreCase = true)) text = text.substring(label.length).trim()
        text = stripWrapping(text)
        return text.ifEmpty { null }
    }

    /**
     * 方式 D の出力を読む。「案」の行がなければ `null`（形式の誤り）。
     */
    fun parseProofreading(output: String?): ProofreadingSuggestion? {
        if (output == null) return null
        var original: String? = null
        var suggestion: String? = null
        var reason: String? = null
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim().trimStart('*', '-', '#', ' ', '\t')
            labeledValue(line, LABEL_ORIGINAL)?.let { if (original == null) original = it }
            labeledValue(line, LABEL_SUGGESTION)?.let { if (suggestion == null) suggestion = it }
            labeledValue(line, LABEL_REASON)?.let { if (reason == null) reason = it }
        }
        val parsedSuggestion = suggestion?.takeIf { it.isNotEmpty() } ?: return null
        return ProofreadingSuggestion(original = original, suggestion = parsedSuggestion, reason = reason)
    }

    /** `見出し: 値` の行なら値を返す（全角のコロンと Markdown の太字も受け付ける）。 */
    private fun labeledValue(line: String, label: String): String? {
        if (!line.startsWith(label)) return null
        val rest = line.substring(label.length).trimStart('*', ' ', '\t')
        if (rest.isEmpty() || (rest[0] != ':' && rest[0] != '：')) return null
        return stripWrapping(rest.substring(1).trim())
    }

    private fun stripWrapping(text: String): String {
        var result = text.trim().trim('*').trim()
        val pairs = listOf("\"" to "\"", "「" to "」", "“" to "”", "'" to "'", "`" to "`")
        for ((open, close) in pairs) {
            if (result.length >= open.length + close.length && result.startsWith(open) && result.endsWith(close)) {
                result = result.substring(open.length, result.length - close.length).trim()
            }
        }
        return result
    }

    private const val LABEL_ORIGINAL = "元の文"
    private const val LABEL_SUGGESTION = "案"
    private const val LABEL_REASON = "理由"
    private const val NO_CHANGE_REASON = "修正不要"
}

/** 方式 B に渡す直前のチャンク。 */
internal data class PreviousContext(val text: String, val languageName: String?)

/** 方式 D の出力を読んだ結果。 */
internal data class ProofreadingSuggestion(val original: String?, val suggestion: String, val reason: String?)
