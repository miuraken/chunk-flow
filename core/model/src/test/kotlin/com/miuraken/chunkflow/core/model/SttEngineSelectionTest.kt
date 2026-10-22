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

    @Test
    fun `enrichedWithCatalog_カタログで非対応のエンジンはUNSUPPORTEDになる`() {
        // ba は SupportedLanguageCatalog で speechRecognizerSupported = false, mlKitSupported = false
        val baInfo = OnDeviceLanguageModelInfo(
            language = LanguageTag("ba"),
            status = OnDeviceLanguageModelStatus.CHECKING,
            mlKitStatus = OnDeviceLanguageModelStatus.CHECKING,
            sherpaModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
        ).enrichedWithCatalog()
        assertEquals(OnDeviceLanguageModelStatus.UNSUPPORTED, baInfo.mlKitStatus)
        assertEquals(OnDeviceLanguageModelStatus.UNSUPPORTED, baInfo.status)
        assertEquals(OnDeviceLanguageModelStatus.NOT_INSTALLED, baInfo.sherpaModelStatus)

        // en はすべて対応なのでそのまま維持される
        val enInfo = OnDeviceLanguageModelInfo(
            language = LanguageTag("en"),
            status = OnDeviceLanguageModelStatus.INSTALLED,
            mlKitStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
            sherpaModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
        ).enrichedWithCatalog()
        assertEquals(OnDeviceLanguageModelStatus.NOT_INSTALLED, enInfo.mlKitStatus)
        assertEquals(OnDeviceLanguageModelStatus.INSTALLED, enInfo.status)
    }

    @Test
    fun `resolveAutoEngine_MLKitが未確認や非対応の環境ではMLKitを自動選択しない`() {
        // 1. デフォルト（CHECKING）状態では ML Kit が確認されていないので SpeechRecognizer にフォールバックする
        assertEquals(
            SttEngine.SPEECH_RECOGNIZER,
            SttEngineSelection.resolveAutoEngine(OnDeviceLanguageModelInfo(language = LanguageTag("en"))),
        )

        // 2. ML Kit が UNSUPPORTED、SpeechRecognizer が NOT_INSTALLED の場合も SpeechRecognizer を返す
        assertEquals(
            SttEngine.SPEECH_RECOGNIZER,
            SttEngineSelection.resolveAutoEngine(
                OnDeviceLanguageModelInfo(
                    language = LanguageTag("en"),
                    status = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                    mlKitStatus = OnDeviceLanguageModelStatus.UNSUPPORTED,
                ),
            ),
        )

        // 3. ML Kit が端末・言語で対応確認済み（NOT_INSTALLED または INSTALLED）のときだけ ML Kit を選ぶ
        assertEquals(
            SttEngine.ML_KIT,
            SttEngineSelection.resolveAutoEngine(
                OnDeviceLanguageModelInfo(
                    language = LanguageTag("en"),
                    status = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                    mlKitStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                ),
            ),
        )
    }

    @Test
    fun `DownloadableSttModel_Moonshineは言語別モデルとして8言語をサポートし言語ごとのサイズを返す`() {
        val tiny = DownloadableSttModel.MOONSHINE_TINY
        val base = DownloadableSttModel.MOONSHINE_BASE

        assertTrue(tiny.isPerLanguageModel)
        assertTrue(base.isPerLanguageModel)
        assertFalse(DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL.isPerLanguageModel)

        assertEquals(
            listOf("en", "ja", "zh", "ko", "es", "ar", "uk", "vi").map(::LanguageTag),
            tiny.supportedLanguages,
        )
        assertTrue(tiny.isLanguageSupported(LanguageTag("ja")))
        assertTrue(tiny.isLanguageSupported(LanguageTag("zh-Hans")))
        assertFalse(tiny.isLanguageSupported(LanguageTag("fr")))

        assertEquals(118, tiny.approxSizeMbForLanguage(LanguageTag("en")))
        assertEquals(69, tiny.approxSizeMbForLanguage(LanguageTag("ja")))
        assertEquals(274, base.approxSizeMbForLanguage(LanguageTag("en")))
        assertEquals(135, base.approxSizeMbForLanguage(LanguageTag("ja")))
    }

    @Test
    fun `recommendedDownloadEngineForLanguage_MLKitより明確に速く高精度な言語とMLKit非対応推奨言語のみ返す`() {
        assertEquals(SttEngine.SHERPA_ONNX_SENSE_VOICE, SttEngineSelection.recommendedDownloadEngineForLanguage(LanguageTag("zh")))
        assertEquals(SttEngine.SHERPA_ONNX_SENSE_VOICE, SttEngineSelection.recommendedDownloadEngineForLanguage(LanguageTag("zh-CN")))
        assertEquals(SttEngine.SHERPA_ONNX_SENSE_VOICE, SttEngineSelection.recommendedDownloadEngineForLanguage(LanguageTag("yue")))
        assertEquals(SttEngine.MOONSHINE_BASE, SttEngineSelection.recommendedDownloadEngineForLanguage(LanguageTag("vi")))
        assertEquals(SttEngine.MOONSHINE_BASE, SttEngineSelection.recommendedDownloadEngineForLanguage(LanguageTag("ar")))
        assertEquals(SttEngine.MOONSHINE_BASE, SttEngineSelection.recommendedDownloadEngineForLanguage(LanguageTag("uk")))

        assertNull(SttEngineSelection.recommendedDownloadEngineForLanguage(LanguageTag("en")))
        assertNull(SttEngineSelection.recommendedDownloadEngineForLanguage(LanguageTag("ja")))
        assertNull(SttEngineSelection.recommendedDownloadEngineForLanguage(LanguageTag("ko")))
        assertNull(SttEngineSelection.recommendedDownloadEngineForLanguage(LanguageTag("es")))
        assertNull(SttEngineSelection.recommendedDownloadEngineForLanguage(LanguageTag("hi")))
    }

    @Test
    fun `selectableEnginesForLanguage_SpeechRecognizerとWhisperBaseとWhisperSmallを除外し言語別に厳選する`() {
        // 英語: ML Kit, Moonshine Base, Whisper Tiny
        val enEngines = SttEngineSelection.selectableEnginesForLanguage(OnDeviceLanguageModelInfo(LanguageTag("en")))
        assertEquals(listOf(SttEngine.ML_KIT, SttEngine.MOONSHINE_BASE, SttEngine.SHERPA_ONNX), enEngines)
        assertFalse(SttEngine.SPEECH_RECOGNIZER in enEngines)
        assertFalse(SttEngine.SHERPA_ONNX_WHISPER_SMALL in enEngines)
        assertFalse(SttEngine.SHERPA_ONNX_WHISPER_BASE in enEngines)

        // 日本語: Moonshine を除外し ML Kit, SenseVoice, Whisper Tiny
        val jaEngines = SttEngineSelection.selectableEnginesForLanguage(OnDeviceLanguageModelInfo(LanguageTag("ja")))
        assertEquals(listOf(SttEngine.ML_KIT, SttEngine.SHERPA_ONNX_SENSE_VOICE, SttEngine.SHERPA_ONNX), jaEngines)

        // 韓国語: Moonshine Base を除外し Moonshine Tiny と SenseVoice を提示（両方未インストール時）
        val koEngines = SttEngineSelection.selectableEnginesForLanguage(OnDeviceLanguageModelInfo(LanguageTag("ko")))
        assertEquals(
            listOf(SttEngine.ML_KIT, SttEngine.MOONSHINE_TINY, SttEngine.SHERPA_ONNX_SENSE_VOICE, SttEngine.SHERPA_ONNX),
            koEngines,
        )

        // 韓国語: SenseVoice がインストール済みでも未インストールの Moonshine Tiny は一覧に残す（設定画面で disabled + ダウンロードボタン表示）
        val koWithSenseVoice = OnDeviceLanguageModelInfo(
            language = LanguageTag("ko"),
            extraEngineStatuses = mapOf(SttEngine.SHERPA_ONNX_SENSE_VOICE to OnDeviceLanguageModelStatus.INSTALLED),
        )
        assertEquals(
            listOf(SttEngine.ML_KIT, SttEngine.MOONSHINE_TINY, SttEngine.SHERPA_ONNX_SENSE_VOICE, SttEngine.SHERPA_ONNX),
            SttEngineSelection.selectableEnginesForLanguage(koWithSenseVoice),
        )

        // 中国語: SenseVoice がインストール済みでも未インストールの Moonshine Base は一覧に残す（設定画面で disabled + ダウンロードボタン表示）
        val zhWithSenseVoice = OnDeviceLanguageModelInfo(
            language = LanguageTag("zh-cn"),
            extraEngineStatuses = mapOf(SttEngine.SHERPA_ONNX_SENSE_VOICE to OnDeviceLanguageModelStatus.INSTALLED),
        )
        assertEquals(
            listOf(SttEngine.SHERPA_ONNX_SENSE_VOICE, SttEngine.ML_KIT, SttEngine.MOONSHINE_BASE, SttEngine.SHERPA_ONNX),
            SttEngineSelection.selectableEnginesForLanguage(zhWithSenseVoice),
        )
    }

    @Test
    fun `detailedTrialEnginesForLanguage_より詳しく文字起こしではWhisperSmallが追加される`() {
        val enDetailed = SttEngineSelection.detailedTrialEnginesForLanguage(OnDeviceLanguageModelInfo(LanguageTag("en")))
        assertEquals(
            listOf(
                SttEngine.ML_KIT,
                SttEngine.MOONSHINE_BASE,
                SttEngine.SHERPA_ONNX_WHISPER_SMALL,
                SttEngine.SHERPA_ONNX,
            ),
            enDetailed,
        )
    }

    @Test
    fun `labelsFor_言語とエンジンに応じた特徴ラベルを返す`() {
        assertEquals(
            listOf(SttEngineLabel.RECOMMENDED, SttEngineLabel.HIGH_ACCURACY, SttEngineLabel.FAST),
            SttEngineSelection.labelsFor(LanguageTag("zh-cn"), SttEngine.SHERPA_ONNX_SENSE_VOICE),
        )
        assertEquals(
            listOf(SttEngineLabel.HIGH_ACCURACY),
            SttEngineSelection.labelsFor(LanguageTag("ja"), SttEngine.ML_KIT),
        )
        assertEquals(
            listOf(SttEngineLabel.FAST),
            SttEngineSelection.labelsFor(LanguageTag("ja"), SttEngine.SHERPA_ONNX_SENSE_VOICE),
        )
        assertEquals(
            listOf(SttEngineLabel.HIGH_ACCURACY, SttEngineLabel.SLOW),
            SttEngineSelection.labelsFor(LanguageTag("en"), SttEngine.SHERPA_ONNX_WHISPER_SMALL),
        )
    }
}
