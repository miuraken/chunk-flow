package com.miuraken.chunkflow.spike.textai

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** 試作の純粋な処理（差分・言語の推定・プロンプトの読み取り・CSV・チャンクの選び方）のテスト。例文は作ったもの。 */
class TextAiLogicTest {

    @Test
    fun `差分_一部だけ違う_消した部分と加えた部分に分かれる`() {
        val spans = TextDiff.diff("저는 학생이예요", "저는 학생이에요")
        assertEquals(
            listOf(DiffSpan.Same("저는 학생이"), DiffSpan.Removed("예"), DiffSpan.Added("에"), DiffSpan.Same("요")),
            spans,
        )
    }

    @Test
    fun `差分_同じ文_同じ区間だけになる`() {
        assertEquals(listOf(DiffSpan.Same("abc")), TextDiff.diff("abc", "abc"))
    }

    @Test
    fun `差分_長すぎる文_nullを返す`() {
        val long = "a".repeat(TextDiff.MAX_CODE_POINTS + 1)
        assertNull(TextDiff.diff(long, "a"))
    }

    @Test
    fun `差分_サロゲートペア_文字の途中で切らない`() {
        val spans = TextDiff.diff("a\uD83D\uDE00b", "ab")
        assertEquals(listOf(DiffSpan.Same("a"), DiffSpan.Removed("\uD83D\uDE00"), DiffSpan.Same("b")), spans)
    }

    @Test
    fun `言語の推定_ハングルと仮名_それぞれの言語になる`() {
        assertEquals("ko", ScriptLanguageGuesser.guess("저는 학생이에요", "ko", "ja"))
        assertEquals("ja", ScriptLanguageGuesser.guess("私は学生です", "ko", "ja"))
    }

    @Test
    fun `言語の推定_中国語と日本語_仮名がなければ中国語`() {
        assertEquals("zh", ScriptLanguageGuesser.guess("我是学生", "zh", "ja"))
        assertEquals("ja", ScriptLanguageGuesser.guess("学生です", "zh", "ja"))
    }

    @Test
    fun `言語の推定_デーヴァナーガリーとラテン文字_それぞれの言語になる`() {
        assertEquals("hi", ScriptLanguageGuesser.guess("मैं छात्र हूँ", "hi", "en"))
        assertEquals("en", ScriptLanguageGuesser.guess("I am a student", "hi", "en"))
    }

    @Test
    fun `言語の推定_同じ文字の言語の組_判定しない`() {
        assertNull(ScriptLanguageGuesser.guess("Je suis étudiant", "fr", "en"))
        assertNull(ScriptLanguageGuesser.guess("我係學生", "yue", "zh"))
        assertNull(ScriptLanguageGuesser.guess("저는 학생이에요", "ko", null))
    }

    @Test
    fun `校正の出力_3行の形_案と理由を読む`() {
        val parsed = PromptTemplates.parseProofreading("元の文: 저는 학생이예요\n案: 저는 학생이에요\n理由: 綴りの誤り")
        assertEquals(ProofreadingSuggestion("저는 학생이예요", "저는 학생이에요", "綴りの誤り"), parsed)
    }

    @Test
    fun `校正の出力_全角コロンと太字_読める`() {
        val parsed = PromptTemplates.parseProofreading("**元の文**： a\n**案**： 「b」\n**理由**： 修正不要")
        assertEquals("b", parsed?.suggestion)
        assertEquals("修正不要", parsed?.reason)
    }

    @Test
    fun `校正の出力_案の行がない_nullを返す`() {
        assertNull(PromptTemplates.parseProofreading("I cannot help with that."))
        assertNull(PromptTemplates.parseProofreading(null))
    }

    @Test
    fun `翻訳の出力_見出しと引用符_取り除く`() {
        assertEquals("私は学生です", PromptTemplates.cleanTranslation("Japanese translation: \"私は学生です\"", "Japanese"))
        assertNull(PromptTemplates.cleanTranslation("  ", "Japanese"))
    }

    @Test
    fun `翻訳のプロンプト_直前のチャンクを文脈として入れる`() {
        val prompt = PromptTemplates.translation("Korean", "Japanese", "저는 학생이에요", PreviousContext("前の文", "Japanese"))
        assertTrue(prompt.contains("(Japanese): 前の文"))
        assertTrue(prompt.endsWith("Japanese translation:"))
    }

    @Test
    fun `CSV_カンマや引用符を含む値_引用符で囲む`() {
        assertEquals("plain", csvField("plain"))
        assertEquals("\"a,b\"", csvField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", csvField("say \"hi\""))
    }

    @Test
    fun `CSV_1行の列の数_見出しと同じ`() {
        val line = csvLine("t", "s", CsvRow(event = CsvEvent.RUN, method = Method.PROOFREAD_API, chunk = 3))
        assertEquals(CSV_HEADER.split(',').size, line.split(',').size)
        assertTrue(line.startsWith("t,s,RUN,"))
    }

    @Test
    fun `チャンクの選び方_JSONの言語を優先し直前のチャンクを付ける`() {
        val transcript = LoadedTranscript(
            kind = FileKind.CHUNKFLOW_JSON,
            fileId = "00000000",
            segments = listOf(
                Segment(1, 0, "「学生です」と言ってみましょう", "ja"),
                Segment(2, 1_000, "저는 학생이에요", "ko"),
                Segment(3, 2_000, "???", null),
            ),
            fileTarget = "ko",
            fileBase = "ja",
        )
        val built = buildRows(transcript, "ko", "ja", includeUnknown = false) { it.uppercase() }
        assertEquals(listOf(2), built.rows.map { it.number })
        assertEquals(PreviousContext("「学生です」と言ってみましょう", "JA"), built.rows.single().previous)
        assertEquals(1, built.unknownCount)

        val withUnknown = buildRows(transcript, "ko", "ja", includeUnknown = true) { it }
        assertEquals(listOf(2, 3), withUnknown.rows.map { it.number })
        assertEquals(LanguageSource.NONE, withUnknown.rows.last().languageSource)
    }

    @Test
    fun `チャンクの選び方_字幕形式_文字の種類から学習言語を選ぶ`() {
        val transcript = LoadedTranscript(
            kind = FileKind.SRT,
            fileId = "00000000",
            segments = listOf(Segment(1, 0, "I am a student", null), Segment(2, 1_000, "मैं छात्र हूँ", null)),
            fileTarget = null,
            fileBase = null,
        )
        val built = buildRows(transcript, "hi", "en", includeUnknown = false) { it }
        assertEquals(listOf(2), built.rows.map { it.number })
        assertEquals(LanguageSource.SCRIPT, built.rows.single().languageSource)
        assertEquals(0, built.unknownCount)
    }

    @Test
    fun `比較用の正規化_空白の違い_同じになる`() {
        assertEquals(normalizeForCompare("a  b "), normalizeForCompare(" a b"))
        assertEquals(3, codePointLength("a\uD83D\uDE00b"))
    }
}
