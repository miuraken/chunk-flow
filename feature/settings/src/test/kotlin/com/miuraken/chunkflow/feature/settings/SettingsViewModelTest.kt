package com.miuraken.chunkflow.feature.settings

import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.PhoneticGuideKind
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.UserSettings
import com.miuraken.chunkflow.core.testing.fake.FakeUserSettingsRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `スキップ秒数と既定再生速度の変更がRepositoryとUiStateに反映される`() = runTest {
        val repo = FakeUserSettingsRepository()
        val viewModel = SettingsViewModel(repo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(SettingsEvent.SetSeekBackMs(2_000L))
        assertEquals(2_000L, viewModel.uiState.value.seekBackMs)

        viewModel.onEvent(SettingsEvent.SetSeekForwardMs(15_000L))
        assertEquals(15_000L, viewModel.uiState.value.seekForwardMs)

        assertEquals(listOf(0.5f, 0.75f, 1.25f, 1.5f, 2.0f, 2.5f, 3.0f), SettingsUiState.SPEED_PRESETS)
        // settings.defaultSpeed が 1.0f の場合は 1.5f を返す
        repo.state.value = repo.state.value.copy(defaultSpeed = 1.0f)
        assertEquals(1.5f, viewModel.uiState.value.defaultSpeed)

        viewModel.onEvent(SettingsEvent.SetDefaultSpeed(1.25f))
        assertEquals(1.25f, viewModel.uiState.value.defaultSpeed)

        // 上下限のクランプ
        viewModel.onEvent(SettingsEvent.SetDefaultSpeed(0.2f))
        assertEquals(ItemState.MIN_SPEED, viewModel.uiState.value.defaultSpeed)
        viewModel.onEvent(SettingsEvent.SetDefaultSpeed(2.5f))
        assertEquals(2.5f, viewModel.uiState.value.defaultSpeed)
        viewModel.onEvent(SettingsEvent.SetDefaultSpeed(3.5f))
        assertEquals(ItemState.MAX_SPEED, viewModel.uiState.value.defaultSpeed)
    }

    @Test
    fun `既定の自動ポーズと自動チャンク分割パラメータの変更およびリセットが機能する`() = runTest {
        val repo = FakeUserSettingsRepository()
        val viewModel = SettingsViewModel(repo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(1_000L, SettingsUiState.DEFAULT_AUTO_PAUSE_FIXED_MS)
        assertEquals(1.0f, SettingsUiState.DEFAULT_AUTO_PAUSE_PROPORTIONAL_FACTOR)
        assertEquals(1.0f, SettingsUiState.DEFAULT_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR)
        assertTrue(
            SettingsUiState.AUTO_PAUSE_OPTIONS.contains(
                AutoPause.OriginalSilence(factor = SettingsUiState.DEFAULT_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR),
            ),
        )

        viewModel.onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.Fixed(0)))
        assertEquals(AutoPause.Fixed(0), viewModel.uiState.value.defaultAutoPause)

        viewModel.onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.Fixed(1_500)))
        assertEquals(AutoPause.Fixed(1_500), viewModel.uiState.value.defaultAutoPause)

        viewModel.onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.Fixed(5_000)))
        assertEquals(AutoPause.Fixed(4_000), viewModel.uiState.value.defaultAutoPause)

        viewModel.onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.Proportional(factor = 0.5f)))
        assertEquals(AutoPause.Proportional(factor = 0.5f), viewModel.uiState.value.defaultAutoPause)

        viewModel.onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.Proportional(factor = 3.5f)))
        assertEquals(AutoPause.Proportional(factor = 3.0f), viewModel.uiState.value.defaultAutoPause)

        // OriginalSilence の設定・0.25 刻みの丸め・上下限クランプ (0.25〜2.0)
        viewModel.onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.OriginalSilence(factor = 0.25f)))
        assertEquals(AutoPause.OriginalSilence(factor = 0.25f), viewModel.uiState.value.defaultAutoPause)

        viewModel.onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.OriginalSilence(factor = 1.25f)))
        assertEquals(AutoPause.OriginalSilence(factor = 1.25f), viewModel.uiState.value.defaultAutoPause)

        viewModel.onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.OriginalSilence(factor = 1.36f)))
        assertEquals(AutoPause.OriginalSilence(factor = 1.25f), viewModel.uiState.value.defaultAutoPause)

        viewModel.onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.OriginalSilence(factor = 1.38f)))
        assertEquals(AutoPause.OriginalSilence(factor = 1.5f), viewModel.uiState.value.defaultAutoPause)

        viewModel.onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.OriginalSilence(factor = 0.1f)))
        assertEquals(AutoPause.OriginalSilence(factor = 0.25f), viewModel.uiState.value.defaultAutoPause)

        viewModel.onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.OriginalSilence(factor = 2.5f)))
        assertEquals(AutoPause.OriginalSilence(factor = 2.0f), viewModel.uiState.value.defaultAutoPause)

        viewModel.onEvent(
            SettingsEvent.SetDefaultAutoPauseScope(
                com.miuraken.chunkflow.core.model.AutoPauseScope.AFTER_TARGET_LANGUAGE,
            ),
        )
        assertEquals(
            com.miuraken.chunkflow.core.model.AutoPauseScope.AFTER_TARGET_LANGUAGE,
            viewModel.uiState.value.defaultAutoPauseScope,
        )
        viewModel.onEvent(
            SettingsEvent.SetDefaultAutoPauseScope(
                com.miuraken.chunkflow.core.model.AutoPauseScope.AFTER_BASE_LANGUAGE,
            ),
        )
        assertEquals(
            com.miuraken.chunkflow.core.model.AutoPauseScope.AFTER_BASE_LANGUAGE,
            viewModel.uiState.value.defaultAutoPauseScope,
        )

        val customParams = SegmentationParams(
            minSilenceMs = 500,
            prePaddingMs = 200,
            postPaddingMs = 250,
        )
        viewModel.onEvent(SettingsEvent.SetDefaultSegmentationParams(customParams))
        assertEquals(customParams, viewModel.uiState.value.defaultSegmentationParams)

        viewModel.onEvent(SettingsEvent.ResetDefaultSegmentationParams)
        assertEquals(SegmentationParams(), viewModel.uiState.value.defaultSegmentationParams)

        assertFalse(viewModel.uiState.value.skipOriginalSilence)
        viewModel.onEvent(SettingsEvent.SetSkipOriginalSilence(true))
        assertTrue(viewModel.uiState.value.skipOriginalSilence)
        assertTrue(repo.state.value.skipOriginalSilence)
        viewModel.onEvent(SettingsEvent.SetSkipOriginalSilence(false))
        assertFalse(viewModel.uiState.value.skipOriginalSilence)
        assertFalse(repo.state.value.skipOriginalSilence)
    }

    @Test
    fun `既定の言語ペア_学習言語と母語の変更および単一言語への切り替えが機能する`() = runTest {
        val repo = FakeUserSettingsRepository()
        val viewModel = SettingsViewModel(repo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(SettingsEvent.SetTargetLanguage(LanguageTag("yue")))
        assertEquals(
            LanguagePair(target = LanguageTag("yue"), base = LanguageTag("ja")),
            viewModel.uiState.value.defaultLanguagePair,
        )

        viewModel.onEvent(SettingsEvent.SetBaseLanguage(LanguageTag("en")))
        assertEquals(
            LanguagePair(target = LanguageTag("yue"), base = LanguageTag("en")),
            viewModel.uiState.value.defaultLanguagePair,
        )

        // 単一言語（母語なし）
        viewModel.onEvent(SettingsEvent.SetBaseLanguage(null))
        assertEquals(
            LanguagePair(target = LanguageTag("yue"), base = null),
            viewModel.uiState.value.defaultLanguagePair,
        )
    }

    @Test
    fun `選択肢の言語_既定で日本語と英語が有効であり追加と解除と一括更新が機能する`() = runTest {
        val repo = FakeUserSettingsRepository()
        val viewModel = SettingsViewModel(repo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // 初期状態は ja と en のみ
        assertEquals(UserSettings.DEFAULT_ENABLED_LANGUAGES, viewModel.uiState.value.enabledLanguages)
        assertEquals(
            listOf(LanguageTag("ja"), LanguageTag("en")),
            viewModel.uiState.value.selectableLanguages,
        )

        // 端末認識可能言語リストは ja, en を先頭に含む
        val deviceLanguages = SettingsUiState.availableDeviceLanguages()
        assertTrue(deviceLanguages.size >= 2)
        assertEquals(LanguageTag("ja"), deviceLanguages[0].tag)
        assertEquals(LanguageTag("en"), deviceLanguages[1].tag)

        // 韓国語を追加
        viewModel.onEvent(SettingsEvent.ToggleEnabledLanguage(LanguageTag("ko")))
        assertEquals(
            setOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ko")),
            viewModel.uiState.value.enabledLanguages,
        )

        // 一括更新
        viewModel.onEvent(
            SettingsEvent.SetEnabledLanguages(
                setOf(LanguageTag("ja"), LanguageTag("zh"), LanguageTag("yue")),
            ),
        )
        assertEquals(
            setOf(LanguageTag("ja"), LanguageTag("zh"), LanguageTag("yue")),
            viewModel.uiState.value.enabledLanguages,
        )

        // 最後の 1 件を解除しようとしても空にならない
        viewModel.onEvent(SettingsEvent.SetEnabledLanguages(setOf(LanguageTag("ja"))))
        viewModel.onEvent(SettingsEvent.ToggleEnabledLanguage(LanguageTag("ja")))
        assertEquals(setOf(LanguageTag("ja")), viewModel.uiState.value.enabledLanguages)
    }

    @Test
    fun `漢字の字体_中国語と広東語の字体切り替えが機能する`() = runTest {
        val repo = FakeUserSettingsRepository()
        val viewModel = SettingsViewModel(repo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(ChineseScript.HANS, viewModel.uiState.value.chineseScript)
        assertEquals(ChineseScript.HANT, viewModel.uiState.value.cantoneseScript)

        viewModel.onEvent(SettingsEvent.SetChineseScript(ChineseScript.HANT))
        assertEquals(ChineseScript.HANT, viewModel.uiState.value.chineseScript)

        viewModel.onEvent(SettingsEvent.SetCantoneseScript(ChineseScript.HANS))
        assertEquals(ChineseScript.HANS, viewModel.uiState.value.cantoneseScript)
    }

    @Test
    fun `表記と読み方ガイド_選択言語に応じた字体表示条件と読み方ガイド種別およびON_OFF切り替えが機能する`() = runTest {
        val repo = FakeUserSettingsRepository()
        val viewModel = SettingsViewModel(repo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // 初期状態 (target=en, base=ja): 中国語・広東語ともに未選択、学習言語(en)はラテン文字のためガイドなし
        assertFalse(viewModel.uiState.value.showChineseScriptOption)
        assertFalse(viewModel.uiState.value.showCantoneseScriptOption)
        assertNull(viewModel.uiState.value.targetPhoneticGuideKind)
        assertFalse(viewModel.uiState.value.showTargetRomanization)

        // 学習言語を中国語 (zh) に変更 -> 中国語字体オプションと Pinyin ガイドが有効
        viewModel.onEvent(SettingsEvent.SetTargetLanguage(LanguageTag("zh")))
        assertTrue(viewModel.uiState.value.showChineseScriptOption)
        assertFalse(viewModel.uiState.value.showCantoneseScriptOption)
        assertEquals(PhoneticGuideKind.PINYIN, viewModel.uiState.value.targetPhoneticGuideKind)

        // 母語を広東語 (yue)、学習言語を日本語 (ja) に変更 -> 広東語字体オプションとローマ字ガイドが有効
        viewModel.onEvent(SettingsEvent.SetBaseLanguage(LanguageTag("yue")))
        viewModel.onEvent(SettingsEvent.SetTargetLanguage(LanguageTag("ja")))
        assertFalse(viewModel.uiState.value.showChineseScriptOption)
        assertTrue(viewModel.uiState.value.showCantoneseScriptOption)
        assertEquals(PhoneticGuideKind.ROMAJI, viewModel.uiState.value.targetPhoneticGuideKind)

        // 学習言語をヒンディー語 (hi) に変更 -> IAST ガイド
        viewModel.onEvent(SettingsEvent.SetTargetLanguage(LanguageTag("hi")))
        assertEquals(PhoneticGuideKind.IAST, viewModel.uiState.value.targetPhoneticGuideKind)

        // 学習言語を韓国語 (ko) に変更 -> アルファベット読み方ガイド
        viewModel.onEvent(SettingsEvent.SetTargetLanguage(LanguageTag("ko")))
        assertEquals(PhoneticGuideKind.LATIN_TRANSLITERATION, viewModel.uiState.value.targetPhoneticGuideKind)

        // 読み方ガイド表示の ON / OFF 切り替え
        viewModel.onEvent(SettingsEvent.SetShowTargetRomanization(true))
        assertTrue(viewModel.uiState.value.showTargetRomanization)
        viewModel.onEvent(SettingsEvent.SetShowTargetRomanization(false))
        assertFalse(viewModel.uiState.value.showTargetRomanization)
    }

    @Test
    fun `先読み文字起こし設定_既定で有効であり自動開始とバックグラウンド文字起こしのON_OFF切り替えがRepositoryとUiStateに反映される`() = runTest {
        val repo = FakeUserSettingsRepository()
        val viewModel = SettingsViewModel(repo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // 既定値はすべて ON (true)
        assertTrue(viewModel.uiState.value.lookaheadTranscriptionEnabled)
        assertTrue(repo.state.value.lookaheadTranscriptionEnabled)
        assertTrue(viewModel.uiState.value.autoStartTranscriptionOnOpen)
        assertTrue(repo.state.value.autoStartTranscriptionOnOpen)
        assertTrue(viewModel.uiState.value.backgroundTranscriptionEnabled)
        assertTrue(repo.state.value.backgroundTranscriptionEnabled)

        // ファイルを開いたら自動開始を OFF / ON に切り替え
        viewModel.onEvent(SettingsEvent.SetAutoStartTranscriptionOnOpen(false))
        assertFalse(viewModel.uiState.value.autoStartTranscriptionOnOpen)
        assertFalse(repo.state.value.autoStartTranscriptionOnOpen)
        viewModel.onEvent(SettingsEvent.SetAutoStartTranscriptionOnOpen(true))
        assertTrue(viewModel.uiState.value.autoStartTranscriptionOnOpen)
        assertTrue(repo.state.value.autoStartTranscriptionOnOpen)

        // バックグラウンド文字起こしを OFF / ON に切り替え
        viewModel.onEvent(SettingsEvent.SetBackgroundTranscriptionEnabled(false))
        assertFalse(viewModel.uiState.value.backgroundTranscriptionEnabled)
        assertFalse(repo.state.value.backgroundTranscriptionEnabled)
        viewModel.onEvent(SettingsEvent.SetBackgroundTranscriptionEnabled(true))
        assertTrue(viewModel.uiState.value.backgroundTranscriptionEnabled)
        assertTrue(repo.state.value.backgroundTranscriptionEnabled)
    }

    @Test
    fun `音声認識モデルの管理_状態取得とダウンロード要求がUiStateに反映される`() = runTest {
        val repo = FakeUserSettingsRepository()
        val analysisRepo = com.miuraken.chunkflow.core.testing.fake.FakeAnalysisRepository().apply {
            languageModelInfos[LanguageTag("zh")] =
                com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                    language = LanguageTag("zh"),
                    status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
                    resolvedBcp47Tag = "cmn-Hans-CN",
                )
        }
        val viewModel = SettingsViewModel(repo, analysisRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val zhBefore = viewModel.uiState.value.languageModels.first { it.language == LanguageTag("zh") }
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
            zhBefore.status,
        )
        assertEquals("cmn-Hans-CN", zhBefore.resolvedBcp47Tag)

        // ダウンロード要求で DOWNLOADING に遷移する
        viewModel.onEvent(SettingsEvent.RequestSttModelDownload(LanguageTag("zh"), com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER))
        assertEquals(
            listOf(LanguageTag("zh") to com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER),
            analysisRepo.requestedSttModelDownloads,
        )
        val zhDownloading = viewModel.uiState.value.languageModels.first { it.language == LanguageTag("zh") }
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.DOWNLOADING,
            zhDownloading.status,
        )

        // インストール完了＋端末で新規言語 (例: en-AU) が検出された後に「状態を更新」すると、対応言語リストも最新化される
        analysisRepo.languageModelInfos[LanguageTag("zh")] =
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                language = LanguageTag("zh"),
                status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                resolvedBcp47Tag = "cmn-Hans-CN",
            )
        analysisRepo.languageModelInfos[LanguageTag("en-AU")] =
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                language = LanguageTag("en-AU"),
                status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                resolvedBcp47Tag = "en-AU",
            )
        viewModel.onEvent(SettingsEvent.RefreshRecognitionModels)
        val zhInstalled = viewModel.uiState.value.languageModels.first { it.language == LanguageTag("zh") }
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
            zhInstalled.status,
        )
        assertTrue(
            viewModel.uiState.value.languageModels.any { it.language == LanguageTag("en-AU") },
        )
        val refreshedDeviceLanguages = SettingsUiState.availableDeviceLanguages(
            viewModel.uiState.value.languageModels,
        )
        assertTrue(refreshedDeviceLanguages.any { it.tag == LanguageTag("en-AU") })
    }

    @Test
    fun `文字起こしライブラリの選択とsherpa_onnxモデルの入手および削除がUiStateとRepositoryに反映される`() = runTest {
        val repo = FakeUserSettingsRepository()
        val analysisRepo = com.miuraken.chunkflow.core.testing.fake.FakeAnalysisRepository().apply {
            languageModelInfos[LanguageTag("zh")] =
                com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                    language = LanguageTag("zh"),
                    status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.UNSUPPORTED,
                    sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
                    sherpaModelId = "sherpa-onnx-whisper-tiny",
                    sherpaModelSizeBytes = 103_609_903L,
                )
        }
        val viewModel = SettingsViewModel(repo, analysisRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.AUTO,
            viewModel.uiState.value.transcriptionEngine,
        )

        viewModel.onEvent(
            SettingsEvent.SetTranscriptionEngine(
                com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.SHERPA_ONNX,
            ),
        )
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.SHERPA_ONNX,
            viewModel.uiState.value.transcriptionEngine,
        )
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.SHERPA_ONNX,
            repo.state.value.transcriptionEngine,
        )

        // sherpa-onnx モデルの入手要求で INSTALLED + canDeleteSherpaModel = true に遷移する
        viewModel.onEvent(SettingsEvent.RequestSttModelDownload(LanguageTag("zh"), com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX))
        assertEquals(listOf(LanguageTag("zh")), analysisRepo.requestedSherpaDownloads)
        val zhAfterDownload = viewModel.uiState.value.languageModels.first { it.language == LanguageTag("zh") }
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
            zhAfterDownload.sherpaModelStatus,
        )
        assertTrue(zhAfterDownload.canDeleteSherpaModel)

        // 削除要求で NOT_INSTALLED + canDeleteSherpaModel = false に戻る
        viewModel.onEvent(SettingsEvent.DeleteSherpaModel(LanguageTag("zh")))
        assertEquals(listOf(LanguageTag("zh")), analysisRepo.requestedSherpaDeletions)
        val zhAfterDelete = viewModel.uiState.value.languageModels.first { it.language == LanguageTag("zh") }
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
            zhAfterDelete.sherpaModelStatus,
        )
        assertFalse(zhAfterDelete.canDeleteSherpaModel)
    }

    @Test
    fun `未ダウンロードのsherpa_onnx選択で確認ダイアログが表示されダウンロード完了後にラジオボタンが切り替わる`() = runTest {
        val repo = FakeUserSettingsRepository()
        val analysisRepo = com.miuraken.chunkflow.core.testing.fake.FakeAnalysisRepository().apply {
            languageModelInfos[LanguageTag("ja")] =
                com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                    language = LanguageTag("ja"),
                    status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                    sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
                    sherpaModelSizeBytes = 103_609_903L, // 98 MB
                )
        }
        val viewModel = SettingsViewModel(repo, analysisRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // 未ダウンロードの sherpa-onnx を選択すると、まだエンジン設定は切り替わらず確認ダイアログが開く
        viewModel.onEvent(
            SettingsEvent.SetSttEngineForLanguage(
                LanguageTag("ja"),
                com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX,
            ),
        )
        assertNull(viewModel.uiState.value.sttEngineByLanguage[LanguageTag("ja")])
        val dialog = viewModel.uiState.value.sherpaDownloadDialog
        assertEquals(LanguageTag("ja"), dialog?.language)
        assertEquals(98, dialog?.sizeMb)
        assertEquals(false, dialog?.isDownloading)

        // キャンセルするとダイアログが閉じ、エンジン設定も変わらない
        viewModel.onEvent(SettingsEvent.DismissSherpaModelDownloadDialog)
        assertNull(viewModel.uiState.value.sherpaDownloadDialog)
        assertNull(viewModel.uiState.value.sttEngineByLanguage[LanguageTag("ja")])

        // 再度選択してダウンロードを確定すると、ダウンロード完了後にダイアログが閉じ sherpa-onnx に切り替わる
        viewModel.onEvent(
            SettingsEvent.SetSttEngineForLanguage(
                LanguageTag("ja"),
                com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX,
            ),
        )
        viewModel.onEvent(SettingsEvent.ConfirmSherpaModelDownload)
        assertNull(viewModel.uiState.value.sherpaDownloadDialog)
        assertEquals(
            com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX,
            viewModel.uiState.value.sttEngineByLanguage[LanguageTag("ja")],
        )

        // モデルを削除すると自動 (null) に戻る
        viewModel.onEvent(SettingsEvent.DeleteSherpaModel(LanguageTag("ja")))
        assertNull(viewModel.uiState.value.sttEngineByLanguage[LanguageTag("ja")])
        assertNull(repo.state.value.sttEngineByLanguage[LanguageTag("ja")])

        // 別のエンジン（SpeechRecognizer など）を選択している状態で sherpa-onnx を削除しても自動 (null) に戻る
        viewModel.onEvent(
            SettingsEvent.SetSttEngineForLanguage(
                LanguageTag("ja"),
                com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER,
            ),
        )
        assertEquals(
            com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER,
            viewModel.uiState.value.sttEngineByLanguage[LanguageTag("ja")],
        )
        viewModel.onEvent(SettingsEvent.DeleteSherpaModel(LanguageTag("ja")))
        assertNull(viewModel.uiState.value.sttEngineByLanguage[LanguageTag("ja")])
        assertNull(repo.state.value.sttEngineByLanguage[LanguageTag("ja")])
    }

    @Test
    fun `MLKit非対応または未確認の言語ではMLKitを選択できず設定も変更されない`() = runTest {
        val repo = FakeUserSettingsRepository()
        val analysisRepo = com.miuraken.chunkflow.core.testing.fake.FakeAnalysisRepository().apply {
            languageModelInfos[LanguageTag("ko")] =
                com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                    language = LanguageTag("ko"),
                    status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                    mlKitStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.UNSUPPORTED,
                    sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
                )
            languageModelInfos[LanguageTag("en")] =
                com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                    language = LanguageTag("en"),
                    status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                    mlKitStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.CHECKING,
                    sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
                )
        }
        val viewModel = SettingsViewModel(repo, analysisRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val koInfo = viewModel.uiState.value.languageModels.first { it.language == LanguageTag("ko") }
        val enInfo = viewModel.uiState.value.languageModels.first { it.language == LanguageTag("en") }

        assertFalse(isSttEngineSelectableInSettings(koInfo, com.miuraken.chunkflow.core.model.SttEngine.ML_KIT))
        assertFalse(isSttEngineSelectableInSettings(enInfo, com.miuraken.chunkflow.core.model.SttEngine.ML_KIT))
        assertTrue(isSttEngineSelectableInSettings(koInfo, com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER))
        assertTrue(isSttEngineSelectableInSettings(koInfo, com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX))

        // 非対応の ML Kit を選んでも sttEngineByLanguage には保存されない
        viewModel.onEvent(
            SettingsEvent.SetSttEngineForLanguage(
                LanguageTag("ko"),
                com.miuraken.chunkflow.core.model.SttEngine.ML_KIT,
            ),
        )
        assertNull(viewModel.uiState.value.sttEngineByLanguage[LanguageTag("ko")])

        // ラテン語 (la): ML Kit / SpeechRecognizer はカタログで非対応、sherpa-onnx のみ対応
        val laInfo = viewModel.uiState.value.languageModels.first { it.language == LanguageTag("la") }
        assertFalse(isSttEngineSelectableInSettings(laInfo, com.miuraken.chunkflow.core.model.SttEngine.ML_KIT))
        assertFalse(isSttEngineSelectableInSettings(laInfo, com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER))
        assertTrue(isSttEngineSelectableInSettings(laInfo, com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX))
        assertTrue(isSttEngineSelectableInSettings(laInfo, null))
        assertEquals(
            com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX,
            com.miuraken.chunkflow.core.model.SttEngineSelection.resolveAutoEngine(laInfo),
        )

        // エスペラント語 (eo): 全エンジン非対応のため「自動」も含めて選択不可
        val eoInfo = viewModel.uiState.value.languageModels.first { it.language == LanguageTag("eo") }
        assertFalse(isSttEngineSelectableInSettings(eoInfo, com.miuraken.chunkflow.core.model.SttEngine.ML_KIT))
        assertFalse(isSttEngineSelectableInSettings(eoInfo, com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER))
        assertFalse(isSttEngineSelectableInSettings(eoInfo, com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX))
        assertFalse(isSttEngineSelectableInSettings(eoInfo, null))
    }

    @Test
    fun `モデルのダウンロードとパージおよびクラウドAPIキー設定が反映される`() = runTest {
        val repo = FakeUserSettingsRepository()
        val analysisRepo = com.miuraken.chunkflow.core.testing.fake.FakeAnalysisRepository()
        val viewModel = SettingsViewModel(repo, analysisRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // 初期状態では全 6 個のオンデバイスモデルが NOT_INSTALLED で並んでいる
        assertEquals(
            com.miuraken.chunkflow.core.model.DownloadableSttModel.entries.size,
            viewModel.uiState.value.downloadableModels.size,
        )
        val senseVoiceBefore = viewModel.uiState.value.downloadableModels.first {
            it.model == com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL
        }
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
            senseVoiceBefore.status,
        )

        // SenseVoice Small をダウンロードすると INSTALLED になり、言語別エンジンの選択肢としても有効になる
        viewModel.onEvent(
            SettingsEvent.DownloadSttModel(
                com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL,
            ),
        )
        assertEquals(
            listOf(com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL),
            analysisRepo.downloadedSttModels,
        )
        val senseVoiceAfterDownload = viewModel.uiState.value.downloadableModels.first {
            it.model == com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL
        }
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
            senseVoiceAfterDownload.status,
        )

        viewModel.onEvent(
            SettingsEvent.SetSttEngineForLanguage(
                LanguageTag("ja"),
                com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_SENSE_VOICE,
            ),
        )
        assertEquals(
            com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX_SENSE_VOICE,
            viewModel.uiState.value.sttEngineByLanguage[LanguageTag("ja")],
        )

        // パージすると NOT_INSTALLED に戻り、選択していた言語設定も自動 (null) にリセットされる
        viewModel.onEvent(
            SettingsEvent.PurgeSttModel(
                com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL,
            ),
        )
        assertEquals(
            listOf(com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL),
            analysisRepo.purgedSttModels,
        )
        val senseVoiceAfterPurge = viewModel.uiState.value.downloadableModels.first {
            it.model == com.miuraken.chunkflow.core.model.DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL
        }
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
            senseVoiceAfterPurge.status,
        )
        assertNull(viewModel.uiState.value.sttEngineByLanguage[LanguageTag("ja")])

        // OpenAI / Groq / Gemini API キーを設定できる
        viewModel.onEvent(SettingsEvent.SetOpenAiApiKey("sk-openai-test"))
        viewModel.onEvent(SettingsEvent.SetGroqApiKey("gsk-groq-test"))
        viewModel.onEvent(SettingsEvent.SetGeminiApiKey("AIza-gemini-test"))

        assertEquals("sk-openai-test", viewModel.uiState.value.openAiApiKey)
        assertEquals("gsk-groq-test", viewModel.uiState.value.groqApiKey)
        assertEquals("AIza-gemini-test", viewModel.uiState.value.geminiApiKey)
        assertEquals("sk-openai-test", repo.state.value.openAiApiKey)
        assertEquals("gsk-groq-test", repo.state.value.groqApiKey)
        assertEquals("AIza-gemini-test", repo.state.value.geminiApiKey)
    }

    @Test
    fun `Moonshineモデルの言語別ダウンロードと個別削除および一括削除が反映される`() = runTest {
        val repo = FakeUserSettingsRepository()
        val analysisRepo = com.miuraken.chunkflow.core.testing.fake.FakeAnalysisRepository()
        val viewModel = SettingsViewModel(repo, analysisRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // 初期状態では Moonshine Tiny のダウンロード済み言語は空
        val initialTiny = viewModel.uiState.value.downloadableModels.first {
            it.model == com.miuraken.chunkflow.core.model.DownloadableSttModel.MOONSHINE_TINY
        }
        assertTrue(initialTiny.downloadedLanguages.isEmpty())
        assertFalse(initialTiny.canPurge)

        // 日本語と英語の Moonshine Tiny を言語別にダウンロード
        viewModel.onEvent(
            SettingsEvent.RequestSttModelDownload(
                LanguageTag("ja"),
                com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY,
            ),
        )
        viewModel.onEvent(
            SettingsEvent.RequestSttModelDownload(
                LanguageTag("en"),
                com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY,
            ),
        )

        val tinyAfterDownload = viewModel.uiState.value.downloadableModels.first {
            it.model == com.miuraken.chunkflow.core.model.DownloadableSttModel.MOONSHINE_TINY
        }
        assertEquals(listOf(LanguageTag("ja"), LanguageTag("en")), tinyAfterDownload.downloadedLanguages)
        assertTrue(tinyAfterDownload.canPurge)

        // 日本語・英語ともに Moonshine Tiny を優先エンジンに設定できる
        viewModel.onEvent(
            SettingsEvent.SetSttEngineForLanguage(
                LanguageTag("ja"),
                com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY,
            ),
        )
        viewModel.onEvent(
            SettingsEvent.SetSttEngineForLanguage(
                LanguageTag("en"),
                com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY,
            ),
        )
        assertEquals(
            com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY,
            viewModel.uiState.value.sttEngineByLanguage[LanguageTag("ja")],
        )
        assertEquals(
            com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY,
            viewModel.uiState.value.sttEngineByLanguage[LanguageTag("en")],
        )

        // 日本語の Moonshine Tiny だけを削除すると、日本語のみ自動 (null) に戻り、英語は残る
        viewModel.onEvent(
            SettingsEvent.DeleteLanguageSttModel(
                LanguageTag("ja"),
                com.miuraken.chunkflow.core.model.DownloadableSttModel.MOONSHINE_TINY,
            ),
        )
        val tinyAfterJaDelete = viewModel.uiState.value.downloadableModels.first {
            it.model == com.miuraken.chunkflow.core.model.DownloadableSttModel.MOONSHINE_TINY
        }
        assertEquals(listOf(LanguageTag("en")), tinyAfterJaDelete.downloadedLanguages)
        assertTrue(tinyAfterJaDelete.canPurge)
        assertNull(viewModel.uiState.value.sttEngineByLanguage[LanguageTag("ja")])
        assertEquals(
            com.miuraken.chunkflow.core.model.SttEngine.MOONSHINE_TINY,
            viewModel.uiState.value.sttEngineByLanguage[LanguageTag("en")],
        )

        // 一括削除（PurgeSttModel）すると、残っていた英語も削除され downloadedLanguages が空になる
        viewModel.onEvent(
            SettingsEvent.PurgeSttModel(
                com.miuraken.chunkflow.core.model.DownloadableSttModel.MOONSHINE_TINY,
            ),
        )
        val tinyAfterPurge = viewModel.uiState.value.downloadableModels.first {
            it.model == com.miuraken.chunkflow.core.model.DownloadableSttModel.MOONSHINE_TINY
        }
        assertTrue(tinyAfterPurge.downloadedLanguages.isEmpty())
        assertFalse(tinyAfterPurge.canPurge)
        assertNull(viewModel.uiState.value.sttEngineByLanguage[LanguageTag("en")])
    }
}
