package com.miuraken.chunkflow.feature.settings

import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.UserSettings
import com.miuraken.chunkflow.core.testing.fake.FakeUserSettingsRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
