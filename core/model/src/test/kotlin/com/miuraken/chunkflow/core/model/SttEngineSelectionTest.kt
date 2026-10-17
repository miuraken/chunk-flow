package com.miuraken.chunkflow.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SttEngineSelectionTest {
    private val koJa = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja"))

    @Test
    fun `tryOrder_指定なしなら自動の順番`() {
        assertEquals(listOf(SttEngine.ML_KIT, SttEngine.SPEECH_RECOGNIZER, SttEngine.SHERPA_ONNX), SttEngine.tryOrder(null))
    }

    @Test
    fun `tryOrder_指定したエンジンのあとに自動の順番の残りを続ける`() {
        assertEquals(
            listOf(SttEngine.SHERPA_ONNX, SttEngine.ML_KIT, SttEngine.SPEECH_RECOGNIZER),
            SttEngine.tryOrder(SttEngine.SHERPA_ONNX),
        )
        assertEquals(
            listOf(SttEngine.SPEECH_RECOGNIZER, SttEngine.ML_KIT, SttEngine.SHERPA_ONNX),
            SttEngine.tryOrder(SttEngine.SPEECH_RECOGNIZER),
        )
    }

    @Test
    fun `fromNameOrNull_知らない名前とnullはnull`() {
        assertEquals(SttEngine.ML_KIT, SttEngine.fromNameOrNull("ML_KIT"))
        assertNull(SttEngine.fromNameOrNull("ml_kit"))
        assertNull(SttEngine.fromNameOrNull("AUTO"))
        assertNull(SttEngine.fromNameOrNull(null))
    }

    @Test
    fun `preferredEngine_ファイルの上書きが言語ごとの設定より優先される`() {
        val override = ItemSttOverride(targetEngine = SttEngine.SHERPA_ONNX, baseEngine = null)
        val byLanguage = mapOf(LanguageTag("ko") to SttEngine.SPEECH_RECOGNIZER, LanguageTag("ja") to SttEngine.ML_KIT)

        assertEquals(SttEngine.SHERPA_ONNX, SttEngineSelection.preferredEngine(LanguageTag("ko"), koJa, override, byLanguage))
        // ベース言語は上書きがないので、言語ごとの設定に従う
        assertEquals(SttEngine.ML_KIT, SttEngineSelection.preferredEngine(LanguageTag("ja"), koJa, override, byLanguage))
    }

    @Test
    fun `preferredEngine_上書きも言語ごとの設定もなければ自動（null）`() {
        assertNull(SttEngineSelection.preferredEngine(LanguageTag("ko"), koJa, ItemSttOverride.NONE, emptyMap()))
        // 教材の言語ペアにない言語は、ファイルの上書きを使わない
        val override = ItemSttOverride(targetEngine = SttEngine.SHERPA_ONNX, baseEngine = SttEngine.SHERPA_ONNX)
        assertNull(SttEngineSelection.preferredEngine(LanguageTag("en"), koJa, override, emptyMap()))
    }

    @Test
    fun `preferredEngine_言語は設定用のコードにそろえて比べる`() {
        val zhJa = LanguagePair(target = LanguageTag("zh"), base = LanguageTag("ja"))
        val override = ItemSttOverride(targetEngine = SttEngine.SPEECH_RECOGNIZER)

        // 区別のない zh の教材で、チャンクの言語が zh-Hans（簡体）でも学習言語として扱う
        assertEquals(
            SttEngine.SPEECH_RECOGNIZER,
            SttEngineSelection.preferredEngine(LanguageTag("zh-Hans"), zhJa, override, emptyMap()),
        )
        // 言語ごとの設定のキーは zh-cn にそろっている
        assertEquals(
            SttEngine.SHERPA_ONNX,
            SttEngineSelection.preferredEngine(
                LanguageTag("zh-CN"),
                koJa,
                ItemSttOverride.NONE,
                mapOf(LanguageTag("zh-cn") to SttEngine.SHERPA_ONNX),
            ),
        )
    }

    @Test
    fun `preferredEngine_単一言語の教材はベース言語の上書きを使わない`() {
        val koOnly = LanguagePair(target = LanguageTag("ko"), base = null)
        val override = ItemSttOverride(targetEngine = null, baseEngine = SttEngine.SHERPA_ONNX)

        assertNull(SttEngineSelection.preferredEngine(LanguageTag("ja"), koOnly, override, emptyMap()))
    }

    @Test
    fun `firstUsableEngine_試す順番のうち使える最初のエンジンを返す`() {
        val info = OnDeviceLanguageModelInfo(
            language = LanguageTag("ko"),
            status = OnDeviceLanguageModelStatus.INSTALLED,
            sherpaModelStatus = OnDeviceLanguageModelStatus.INSTALLED,
            mlKitStatus = OnDeviceLanguageModelStatus.UNSUPPORTED,
        )

        assertEquals(SttEngine.SPEECH_RECOGNIZER, SttEngineSelection.firstUsableEngine(SttEngine.tryOrder(null), info))
        assertEquals(
            SttEngine.SHERPA_ONNX,
            SttEngineSelection.firstUsableEngine(SttEngine.tryOrder(SttEngine.SHERPA_ONNX), info),
        )
        assertNull(SttEngineSelection.firstUsableEngine(SttEngine.tryOrder(null), OnDeviceLanguageModelInfo(LanguageTag("ko"))))
    }

    @Test
    fun `ItemSttOverride_isEmptyはどちらも上書きしていないときだけtrue`() {
        assertTrue(ItemSttOverride.NONE.isEmpty)
        assertFalse(ItemSttOverride(baseEngine = SttEngine.ML_KIT).isEmpty)
    }
}
