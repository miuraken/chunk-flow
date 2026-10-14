package com.miuraken.chunkflow.feature.settings

import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.testing.fake.FakeUserSettingsRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
