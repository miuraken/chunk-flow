package com.miuraken.chunkflow.core.model

/**
 * BCP-47 言語タグ（例: `"en"`、`"id"`、`"ja"`、`"ko"`、`"zh"`、`"yue"`）。
 *
 * 文字起こしの形式（§7.4.3）では Whisper の言語コードをそのまま使う。中国語（普通話）は `zh`、広東語は `yue`。
 */
@JvmInline
value class LanguageTag(val value: String)

/**
 * 学習言語（[target]）と、母語・解説の言語（[base]）の組。教材ごとに持つ。
 *
 * [base] が `null` なら単一言語の教材（言語フィルタと瞬間作文モードは使えない）。
 */
data class LanguagePair(val target: LanguageTag, val base: LanguageTag?)
