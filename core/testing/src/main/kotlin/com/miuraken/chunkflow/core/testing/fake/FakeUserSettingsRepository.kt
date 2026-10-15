package com.miuraken.chunkflow.core.testing.fake

import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.SegmentationParams
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

    override suspend fun setDefaultSegmentationParams(params: SegmentationParams) {
        state.update { it.copy(defaultSegmentationParams = params) }
    }

    override suspend fun setEnabledLanguages(languages: Set<LanguageTag>) {
        require(languages.isNotEmpty()) { "enabledLanguages must not be empty" }
        state.update { it.copy(enabledLanguages = languages) }
    }

    override suspend fun setShowTargetRomanization(enabled: Boolean) {
        state.update { it.copy(showTargetRomanization = enabled) }
    }

    override suspend fun setLookaheadTranscriptionEnabled(enabled: Boolean) {
        state.update { it.copy(lookaheadTranscriptionEnabled = enabled) }
    }
}
