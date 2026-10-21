package com.miuraken.chunkflow.core.testing.fake

import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageSettings
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.UserSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * テストとプレビュー用の [UserSettingsRepository]。
 *
 * 設定はメモリ上の [state] に保持し、テストから直接読み書きできる。
 */
class FakeUserSettingsRepository(
    initialSettings: UserSettings = UserSettings(),
) : UserSettingsRepository {
    /** 現在のアプリ設定。テストから直接書き換えられる。 */
    val state: MutableStateFlow<UserSettings> = MutableStateFlow(initialSettings)

    override val settings: Flow<UserSettings> = state

    override suspend fun setDefaultLanguagePair(pair: LanguagePair) {
        require(pair.target.value.isNotBlank()) { "target language tag must not be blank" }
        require(pair.base == null || pair.base?.value?.isNotBlank() == true) {
            "base language tag must not be blank"
        }
        state.update { it.copy(defaultLanguagePair = pair) }
    }

    override suspend fun setChineseScript(script: ChineseScript) {
        state.update { it.copy(chineseScript = script) }
    }

    override suspend fun setCantoneseScript(script: ChineseScript) {
        state.update { it.copy(cantoneseScript = script) }
    }

    override suspend fun setDefaultSpeed(speed: Float) {
        state.update { it.copy(defaultSpeed = speed) }
    }

    override suspend fun setSeekBackMs(seekBackMs: Long) {
        state.update { it.copy(seekBackMs = seekBackMs) }
    }

    override suspend fun setSeekForwardMs(seekForwardMs: Long) {
        state.update { it.copy(seekForwardMs = seekForwardMs) }
    }

    override suspend fun setDefaultAutoPause(autoPause: AutoPause) {
        state.update { it.copy(defaultAutoPause = autoPause) }
    }

    override suspend fun setDefaultAutoPauseScope(scope: AutoPauseScope) {
        state.update { it.copy(defaultAutoPauseScope = scope) }
    }

    override suspend fun setSkipOriginalSilence(enabled: Boolean) {
        state.update { it.copy(skipOriginalSilence = enabled) }
    }

    override suspend fun setBaseLanguageSpeedEnabled(enabled: Boolean) {
        state.update { it.copy(baseLanguageSpeedEnabled = enabled) }
    }

    override suspend fun setBaseLanguageSpeed(speed: Float) {
        state.update { it.copy(baseLanguageSpeed = speed) }
    }

    override suspend fun setDefaultSegmentationParams(params: SegmentationParams) {
        state.update { it.copy(defaultSegmentationParams = params) }
    }

    override suspend fun setEnabledLanguages(languages: Set<LanguageTag>) {
        require(languages.isNotEmpty()) { "enabledLanguages must not be empty" }
        state.update { it.copy(enabledLanguages = languages) }
    }

    override suspend fun completeLanguageOnboarding() {
        state.update { it.copy(hasCompletedLanguageOnboarding = true) }
    }

    override suspend fun setShowTargetRomanization(enabled: Boolean) {
        state.update { it.copy(showTargetRomanization = enabled) }
    }

    override suspend fun setLookaheadTranscriptionEnabled(enabled: Boolean) {
        state.update {
            it.copy(
                lookaheadTranscriptionEnabled = enabled,
                autoStartTranscriptionOnOpen = enabled,
            )
        }
    }

    override suspend fun setAutoStartTranscriptionOnOpen(enabled: Boolean) {
        state.update { it.copy(autoStartTranscriptionOnOpen = enabled) }
    }

    override suspend fun setBackgroundTranscriptionEnabled(enabled: Boolean) {
        state.update { it.copy(backgroundTranscriptionEnabled = enabled) }
    }

    override suspend fun setTranscriptionEngine(engine: OnDeviceTranscriptionEngine) {
        state.update { it.copy(transcriptionEngine = engine) }
    }

    override suspend fun setSttEngineForLanguage(language: LanguageTag, engine: SttEngine?) {
        val normalized = LanguageSettings.normalize(language)
        require(normalized.value.isNotEmpty()) { "language tag must not be blank" }
        state.update { current ->
            val next = if (engine == null) {
                current.sttEngineByLanguage - normalized
            } else {
                current.sttEngineByLanguage + (normalized to engine)
            }
            current.copy(sttEngineByLanguage = next)
        }
    }

    override suspend fun setOpenAiApiKey(apiKey: String) {
        state.update { it.copy(openAiApiKey = apiKey.trim()) }
    }

    override suspend fun setGroqApiKey(apiKey: String) {
        state.update { it.copy(groqApiKey = apiKey.trim()) }
    }

    override suspend fun setGeminiApiKey(apiKey: String) {
        state.update { it.copy(geminiApiKey = apiKey.trim()) }
    }
}
