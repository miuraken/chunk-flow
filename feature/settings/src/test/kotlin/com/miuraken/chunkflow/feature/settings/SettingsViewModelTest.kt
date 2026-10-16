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

        viewModel.onEvent(SettingsEvent.SetDefaultSpeed(1.25f))
        assertEquals(1.25f, viewModel.uiState.value.defaultSpeed)

        // 上下限のクランプ
        viewModel.onEvent(SettingsEvent.SetDefaultSpeed(0.2f))
        assertEquals(ItemState.MIN_SPEED, viewModel.uiState.value.defaultSpeed)
        viewModel.onEvent(SettingsEvent.SetDefaultSpeed(2.5f))
        assertEquals(ItemState.MAX_SPEED, viewModel.uiState.value.defaultSpeed)
    }

    @Test
    fun `既定の自動ポーズと自動チャンク分割パラメータの変更およびリセットが機能する`() = runTest {
        val repo = FakeUserSettingsRepository()
        val viewModel = SettingsViewModel(repo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.Fixed(1_500)))
        assertEquals(AutoPause.Fixed(1_500), viewModel.uiState.value.defaultAutoPause)

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
    fun `先読み文字起こし設定_既定で有効でありON_OFF切り替えがRepositoryとUiStateに反映される`() = runTest {
        val repo = FakeUserSettingsRepository()
        val viewModel = SettingsViewModel(repo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // 既定値は ON (true)
        assertTrue(viewModel.uiState.value.lookaheadTranscriptionEnabled)
        assertTrue(repo.state.value.lookaheadTranscriptionEnabled)

        // OFF に切り替え
        viewModel.onEvent(SettingsEvent.SetLookaheadTranscriptionEnabled(false))
        assertFalse(viewModel.uiState.value.lookaheadTranscriptionEnabled)
        assertFalse(repo.state.value.lookaheadTranscriptionEnabled)

        // ON に戻す
        viewModel.onEvent(SettingsEvent.SetLookaheadTranscriptionEnabled(true))
        assertTrue(viewModel.uiState.value.lookaheadTranscriptionEnabled)
        assertTrue(repo.state.value.lookaheadTranscriptionEnabled)
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
        viewModel.onEvent(SettingsEvent.RequestRecognitionModelDownload(LanguageTag("zh")))
        assertEquals(listOf(LanguageTag("zh")), analysisRepo.requestedModelDownloads)
        val zhDownloading = viewModel.uiState.value.languageModels.first { it.language == LanguageTag("zh") }
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.DOWNLOADING,
            zhDownloading.status,
        )

        // インストール完了後に状態更新すると INSTALLED になる
        analysisRepo.languageModelInfos[LanguageTag("zh")] =
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                language = LanguageTag("zh"),
                status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                resolvedBcp47Tag = "cmn-Hans-CN",
            )
        viewModel.onEvent(SettingsEvent.RefreshRecognitionModels)
        val zhInstalled = viewModel.uiState.value.languageModels.first { it.language == LanguageTag("zh") }
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
            zhInstalled.status,
        )
    }
}
