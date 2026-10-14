package com.miuraken.chunkflow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 設定画面の UI 状態（F9.1、F3.2、F3.3、F5.2、F6.3）。
 *
 * @property settings 現在のユーザー設定。
 */
data class SettingsUiState(
    val settings: UserSettings = UserSettings(),
) {
    /** 既定の言語ペア。 */
    val defaultLanguagePair: LanguagePair
        get() = settings.defaultLanguagePair

    /** 中国語（`zh`）の表示字体。 */
    val chineseScript: ChineseScript
        get() = settings.chineseScript

    /** 広東語（`yue`）の表示字体。 */
    val cantoneseScript: ChineseScript
        get() = settings.cantoneseScript

    /** 既定の再生速度（0.5..2.0）。 */
    val defaultSpeed: Float
        get() = settings.defaultSpeed

    /** 巻き戻しミリ秒数。 */
    val seekBackMs: Long
        get() = settings.seekBackMs

    /** 早送りミリ秒数。 */
    val seekForwardMs: Long
        get() = settings.seekForwardMs

    /** 既定の自動ポーズ設定。 */
    val defaultAutoPause: AutoPause
        get() = settings.defaultAutoPause

    /** 端末内の自動チャンク分割の既定パラメータ。 */
    val defaultSegmentationParams: SegmentationParams
        get() = settings.defaultSegmentationParams

    companion object {
        /** 巻き戻し秒数の選択肢（ミリ秒）。 */
        val SEEK_BACK_OPTIONS_MS: List<Long> = listOf(1_000L, 2_000L, 3_000L, 5_000L, 10_000L)

        /** 早送り秒数の選択肢（ミリ秒）。 */
        val SEEK_FORWARD_OPTIONS_MS: List<Long> = listOf(3_000L, 5_000L, 10_000L, 15_000L, 30_000L)

        /** 既定の再生速度のプリセット一覧。 */
        val SPEED_PRESETS: List<Float> = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

        /** 既定の自動ポーズの選択肢。 */
        val AUTO_PAUSE_OPTIONS: List<AutoPause> = listOf(
            AutoPause.Off,
            AutoPause.Fixed(1_500L),
            AutoPause.Fixed(3_000L),
            AutoPause.Proportional(factor = 1.0f),
            AutoPause.Proportional(factor = 1.5f),
        )

        /** 主な対象言語の一覧（§1.2、F9.1）。 */
        val SUPPORTED_LANGUAGES: List<LanguageTag> = listOf(
            LanguageTag("en"),
            LanguageTag("ja"),
            LanguageTag("ko"),
            LanguageTag("zh"),
            LanguageTag("yue"),
            LanguageTag("hi"),
            LanguageTag("vi"),
            LanguageTag("ru"),
            LanguageTag("es"),
            LanguageTag("de"),
            LanguageTag("el"),
            LanguageTag("id"),
        )
    }
}

/** 設定画面のイベント。 */
sealed interface SettingsEvent {
    /** 前の画面に戻る。 */
    data object Back : SettingsEvent

    /** 巻き戻しのミリ秒数を変更する。 */
    data class SetSeekBackMs(val seekBackMs: Long) : SettingsEvent

    /** 早送りのミリ秒数を変更する。 */
    data class SetSeekForwardMs(val seekForwardMs: Long) : SettingsEvent

    /** 既定の再生速度を変更する。 */
    data class SetDefaultSpeed(val speed: Float) : SettingsEvent

    /** 既定の自動ポーズを変更する。 */
    data class SetDefaultAutoPause(val autoPause: AutoPause) : SettingsEvent

    /** 自動チャンク分割の既定パラメータを変更する。 */
    data class SetDefaultSegmentationParams(val params: SegmentationParams) : SettingsEvent

    /** 自動チャンク分割の既定パラメータを初期値に戻す。 */
    data object ResetDefaultSegmentationParams : SettingsEvent

    /** 既定の学習言語（target）を変更する。 */
    data class SetTargetLanguage(val language: LanguageTag) : SettingsEvent

    /** 既定の母語・解説の言語（base、単一言語は `null`）を変更する。 */
    data class SetBaseLanguage(val language: LanguageTag?) : SettingsEvent

    /** 中国語（`zh`）の表示字体を変更する。 */
    data class SetChineseScript(val script: ChineseScript) : SettingsEvent

    /** 広東語（`yue`）の表示字体を変更する。 */
    data class SetCantoneseScript(val script: ChineseScript) : SettingsEvent
}

/**
 * 設定画面の ViewModel（F9.1、F3.2、F3.3、F5.2、F6.3）。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userSettingsRepository: UserSettingsRepository,
) : ViewModel() {

    /** 設定画面の UI 状態。 */
    val uiState: StateFlow<SettingsUiState> = userSettingsRepository.settings
        .map { userSettings -> SettingsUiState(settings = userSettings) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SettingsUiState(),
        )

    /** 画面からのイベントを処理する。 */
    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.SetSeekBackMs -> {
                if (event.seekBackMs <= 0L) return
                viewModelScope.launch {
                    userSettingsRepository.setSeekBackMs(event.seekBackMs)
                }
            }

            is SettingsEvent.SetSeekForwardMs -> {
                if (event.seekForwardMs <= 0L) return
                viewModelScope.launch {
                    userSettingsRepository.setSeekForwardMs(event.seekForwardMs)
                }
            }

            is SettingsEvent.SetDefaultSpeed -> {
                val clamped = event.speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
                val rounded = ((clamped * 20f).roundToInt() / 20f)
                    .coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
                viewModelScope.launch {
                    userSettingsRepository.setDefaultSpeed(rounded)
                }
            }

            is SettingsEvent.SetDefaultAutoPause -> {
                viewModelScope.launch {
                    userSettingsRepository.setDefaultAutoPause(event.autoPause)
                }
            }

            is SettingsEvent.SetDefaultSegmentationParams -> {
                val minSilence = event.params.minSilenceMs.coerceIn(100, 1_500)
                val sanitized = event.params.copy(
                    minSilenceMs = minSilence,
                    mergeGapMs = event.params.mergeGapMs.coerceAtLeast(minSilence),
                    prePaddingMs = event.params.prePaddingMs.coerceIn(0, 500),
                    postPaddingMs = event.params.postPaddingMs.coerceIn(0, 500),
                )
                viewModelScope.launch {
                    userSettingsRepository.setDefaultSegmentationParams(sanitized)
                }
            }

            SettingsEvent.ResetDefaultSegmentationParams -> {
                viewModelScope.launch {
                    userSettingsRepository.setDefaultSegmentationParams(SegmentationParams())
                }
            }

            is SettingsEvent.SetTargetLanguage -> {
                if (event.language.value.isBlank()) return
                val currentPair = uiState.value.defaultLanguagePair
                viewModelScope.launch {
                    userSettingsRepository.setDefaultLanguagePair(
                        currentPair.copy(target = event.language),
                    )
                }
            }

            is SettingsEvent.SetBaseLanguage -> {
                if (event.language != null && event.language.value.isBlank()) return
                val currentPair = uiState.value.defaultLanguagePair
                viewModelScope.launch {
                    userSettingsRepository.setDefaultLanguagePair(
                        currentPair.copy(base = event.language),
                    )
                }
            }

            is SettingsEvent.SetChineseScript -> {
                viewModelScope.launch {
                    userSettingsRepository.setChineseScript(event.script)
                }
            }

            is SettingsEvent.SetCantoneseScript -> {
                viewModelScope.launch {
                    userSettingsRepository.setCantoneseScript(event.script)
                }
            }

            SettingsEvent.Back -> Unit
        }
    }
}
