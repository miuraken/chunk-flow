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
import java.util.Locale
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 端末で認識可能な言語選択肢のモデル。
 *
 * @property tag BCP 47 言語タグ（小文字 ISO コード）。
 * @property displayName 現在のロケールでの表示名（例: `日本語 (JA)`）。
 */
data class DeviceLanguageOption(
    val tag: LanguageTag,
    val displayName: String,
)

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

    /** 選択肢として有効な言語のセット（既定は日本語・英語）。 */
    val enabledLanguages: Set<LanguageTag>
        get() = settings.enabledLanguages

    /**
     * 設定画面の言語チップに表示する言語一覧。
     *
     * [enabledLanguages] に加え、現在選択中の [defaultLanguagePair] の学習言語・ベース言語も含め、
     * 日本語（`ja`）・英語（`en`）を先頭に並べる。
     */
    val selectableLanguages: List<LanguageTag>
        get() {
            val combined = buildSet {
                addAll(enabledLanguages)
                add(defaultLanguagePair.target)
                defaultLanguagePair.base?.let { add(it) }
            }
            val priority = listOf(LanguageTag("ja"), LanguageTag("en"))
            val head = priority.filter { it in combined }
            val tail = combined.filterNot { it in priority }.sortedBy { it.value }
            return head + tail
        }

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

        /**
         * 端末（[Locale.getAvailableLocales]）で認識可能な言語一覧を返す。
         *
         * 日本語（`ja`）と英語（`en`）を先頭に配置し、残りは表示名・コード順でソートする。
         */
        fun availableDeviceLanguages(displayLocale: Locale = Locale.getDefault()): List<DeviceLanguageOption> {
            val rawCodes = buildSet {
                addAll(SUPPORTED_LANGUAGES.map { it.value })
                for (locale in Locale.getAvailableLocales()) {
                    val normalized = when (val lang = locale.language.lowercase(Locale.ROOT)) {
                        "in" -> "id"
                        "iw" -> "he"
                        "ji" -> "yi"
                        else -> lang
                    }
                    if (normalized.length in 2..3 &&
                        normalized.all { it in 'a'..'z' } &&
                        normalized != "und"
                    ) {
                        add(normalized)
                    }
                }
            }
            val optionsByCode = rawCodes.associateWith { code ->
                val tagLocale = Locale.forLanguageTag(code)
                val name = tagLocale.getDisplayLanguage(displayLocale)
                    .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
                    ?: code.uppercase(Locale.ROOT)
                val upper = code.uppercase(Locale.ROOT)
                DeviceLanguageOption(
                    tag = LanguageTag(code),
                    displayName = "$name ($upper)",
                )
            }
            val priorityCodes = listOf("ja", "en")
            val head = priorityCodes.mapNotNull { optionsByCode[it] }
            val tail = optionsByCode
                .filterKeys { it !in priorityCodes }
                .values
                .sortedWith(compareBy<DeviceLanguageOption> { it.displayName }.thenBy { it.tag.value })
            return head + tail
        }
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

    /** 選択肢として有効な言語の ON / OFF を切り替える。 */
    data class ToggleEnabledLanguage(val language: LanguageTag) : SettingsEvent

    /** 選択肢として有効な言語のセットを一括更新する。 */
    data class SetEnabledLanguages(val languages: Set<LanguageTag>) : SettingsEvent

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

            is SettingsEvent.ToggleEnabledLanguage -> {
                toggleEnabledLanguage(event.language)
            }

            is SettingsEvent.SetEnabledLanguages -> {
                updateEnabledLanguages(event.languages)
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

    /** 選択肢として有効な言語の ON / OFF を切り替える（最低 1 言語は残す）。 */
    fun toggleEnabledLanguage(tag: LanguageTag) {
        if (tag.value.isBlank()) return
        val current = uiState.value.enabledLanguages
        val next = if (tag in current) {
            if (current.size <= 1) return
            current - tag
        } else {
            current + tag
        }
        updateEnabledLanguages(next)
    }

    /** 選択肢として有効な言語のセットを更新する（空の場合は既定の日本語・英語にフォールバックする）。 */
    fun updateEnabledLanguages(languages: Set<LanguageTag>) {
        val cleaned = languages
            .mapNotNull { tag ->
                val code = tag.value.trim().lowercase(Locale.ROOT)
                if (code.isNotEmpty()) LanguageTag(code) else null
            }
            .toSet()
            .ifEmpty { UserSettings.DEFAULT_ENABLED_LANGUAGES }
        viewModelScope.launch {
            userSettingsRepository.setEnabledLanguages(cleaned)
        }
    }
}
