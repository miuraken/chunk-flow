package com.miuraken.chunkflow.core.data.analysis

import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.SttEngineSelection
import kotlin.test.Test
import kotlin.test.assertEquals

class RecognitionSttOverrideTest {
    private val enJa = LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja"))
    private val override = ItemSttOverride(
        targetEngine = SttEngine.SHERPA_ONNX_SENSE_VOICE,
        baseEngine = SttEngine.ML_KIT,
    )

    private fun single(language: String) = LanguagePair(target = LanguageTag(language), base = null)

    @Test
    fun `forRecognitionLanguages_教材の言語ペアで認識するときは写し替えない`() {
        assertEquals(override, override.forRecognitionLanguages(enJa, enJa))
        assertEquals(override, override.forRecognitionLanguages(null, enJa))
    }

    @Test
    fun `forRecognitionLanguages_単一言語では割り当てた言語の教材での役割のエンジンを学習対象言語のエンジンとして渡す`() {
        assertEquals(
            ItemSttOverride(targetEngine = SttEngine.SHERPA_ONNX_SENSE_VOICE),
            override.forRecognitionLanguages(single("en"), enJa),
        )
        assertEquals(
            ItemSttOverride(targetEngine = SttEngine.ML_KIT),
            override.forRecognitionLanguages(single("ja"), enJa),
        )
        // ベース言語を指定していなければ、ベース言語のチャンクは全体設定に従う
        assertEquals(
            ItemSttOverride.NONE,
            ItemSttOverride(targetEngine = SttEngine.MOONSHINE_BASE).forRecognitionLanguages(single("ja"), enJa),
        )
    }

    @Test
    fun `forRecognitionLanguages_教材の言語ペアにない言語や教材の言語がわからないときは全体設定に従う`() {
        assertEquals(ItemSttOverride.NONE, override.forRecognitionLanguages(single("ko"), enJa))
        assertEquals(ItemSttOverride.NONE, override.forRecognitionLanguages(single("ja"), null))
        // 単一言語の教材（ベース言語なし）では、学習対象言語以外は全体設定に従う
        val englishOnly = LanguagePair(target = LanguageTag("en"), base = null)
        assertEquals(ItemSttOverride.NONE, override.forRecognitionLanguages(single("ja"), englishOnly))
        assertEquals(
            ItemSttOverride(targetEngine = SttEngine.SHERPA_ONNX_SENSE_VOICE),
            override.forRecognitionLanguages(englishOnly, englishOnly),
        )
    }

    @Test
    fun `forRecognitionLanguages_言語コードの書き方の違いはそろえて比べる`() {
        assertEquals(
            ItemSttOverride(targetEngine = SttEngine.ML_KIT),
            override.forRecognitionLanguages(single("ja-JP"), LanguagePair(target = LanguageTag("KO"), base = LanguageTag("ja"))),
        )
        assertEquals(
            ItemSttOverride(targetEngine = SttEngine.SHERPA_ONNX_SENSE_VOICE),
            override.forRecognitionLanguages(single("ko"), LanguagePair(target = LanguageTag("KO"), base = LanguageTag("ja"))),
        )
        // 区別のない中国語（zh）は簡体中国語（zh-CN）としてそろえる
        assertEquals(
            ItemSttOverride(targetEngine = SttEngine.ML_KIT),
            override.forRecognitionLanguages(single("zh"), LanguagePair(target = LanguageTag("en"), base = LanguageTag("zh-CN"))),
        )
    }

    @Test
    fun `forRecognitionLanguages_写し替えた上書きで文字起こしは割り当てた言語の役割のエンジンを選ぶ`() {
        // core:media と同じく SttEngineSelection.preferredEngine で、単一言語のペアの言語に使うエンジンを決める
        fun engineFor(language: String): SttEngine? = SttEngineSelection.preferredEngine(
            language = LanguageTag(language),
            languagePair = single(language),
            itemOverride = override.forRecognitionLanguages(single(language), enJa),
            engineByLanguage = emptyMap(),
        )

        assertEquals(SttEngine.SHERPA_ONNX_SENSE_VOICE, engineFor("en"))
        assertEquals(SttEngine.ML_KIT, engineFor("ja"))
        assertEquals(null, engineFor("ko"))
    }
}
