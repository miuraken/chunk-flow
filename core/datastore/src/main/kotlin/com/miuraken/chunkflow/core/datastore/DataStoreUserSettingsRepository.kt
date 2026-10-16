package com.miuraken.chunkflow.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.UserSettings
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Preferences [DataStore] を使った [UserSettingsRepository] の実装（§6.4）。
 */
@Singleton
class DataStoreUserSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : UserSettingsRepository {

    override val settings: Flow<UserSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs -> prefs.toUserSettings() }

    override suspend fun setDefaultLanguagePair(pair: LanguagePair) {
        require(pair.target.value.isNotBlank()) { "target language tag must not be blank" }
        require(pair.base == null || pair.base?.value?.isNotBlank() == true) {
            "base language tag must not be blank"
        }
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_TARGET_LANG] = pair.target.value
            // 未設定（null）と単一言語の明示（空文字列）を区別する
            prefs[KEY_DEFAULT_BASE_LANG] = pair.base?.value.orEmpty()
        }
    }

    override suspend fun setChineseScript(script: ChineseScript) {
        dataStore.edit { prefs ->
            prefs[KEY_CHINESE_SCRIPT] = script.name
        }
    }

    override suspend fun setCantoneseScript(script: ChineseScript) {
        dataStore.edit { prefs ->
            prefs[KEY_CANTONESE_SCRIPT] = script.name
        }
    }

    override suspend fun setDefaultSpeed(speed: Float) {
        require(speed in ItemState.MIN_SPEED..ItemState.MAX_SPEED) {
            "speed must be in ${ItemState.MIN_SPEED}..${ItemState.MAX_SPEED}: $speed"
        }
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_SPEED] = speed
        }
    }

    override suspend fun setSeekBackMs(seekBackMs: Long) {
        require(seekBackMs > 0L) { "seekBackMs must be > 0: $seekBackMs" }
        dataStore.edit { prefs ->
            prefs[KEY_SEEK_BACK_MS] = seekBackMs
        }
    }

    override suspend fun setSeekForwardMs(seekForwardMs: Long) {
        require(seekForwardMs > 0L) { "seekForwardMs must be > 0: $seekForwardMs" }
        dataStore.edit { prefs ->
            prefs[KEY_SEEK_FORWARD_MS] = seekForwardMs
        }
    }

    override suspend fun setDefaultAutoPause(autoPause: AutoPause) {
        when (autoPause) {
            AutoPause.Off -> Unit

            is AutoPause.Fixed -> require(autoPause.pauseMs > 0L) {
                "pauseMs must be > 0: ${autoPause.pauseMs}"
            }

            is AutoPause.Proportional -> {
                require(autoPause.factor > 0f && autoPause.factor.isFinite()) {
                    "factor must be > 0: ${autoPause.factor}"
                }
                require(autoPause.minMs >= 0L) {
                    "minMs must be >= 0: ${autoPause.minMs}"
                }
            }
        }
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_AUTO_PAUSE] = encodeAutoPause(autoPause)
        }
    }

    override suspend fun setDefaultAutoPauseScope(scope: AutoPauseScope) {
        dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_AUTO_PAUSE_SCOPE] = scope.name
        }
    }

    override suspend fun setDefaultSegmentationParams(params: SegmentationParams) {
        require(params.minSilenceMs > 0) { "minSilenceMs must be > 0: ${params.minSilenceMs}" }
        require(params.minSpeechMs > 0) { "minSpeechMs must be > 0: ${params.minSpeechMs}" }
        require(params.mergeGapMs >= params.minSilenceMs) {
            "mergeGapMs (${params.mergeGapMs}) must be >= minSilenceMs (${params.minSilenceMs})"
        }
        require(params.maxChunkMs > params.minSpeechMs) {
            "maxChunkMs (${params.maxChunkMs}) must be > minSpeechMs (${params.minSpeechMs})"
        }
        require(params.prePaddingMs >= 0) { "prePaddingMs must be >= 0: ${params.prePaddingMs}" }
        require(params.postPaddingMs >= 0) { "postPaddingMs must be >= 0: ${params.postPaddingMs}" }
        require(params.thresholdRatio in 0.0f..1.0f) {
            "thresholdRatio must be in 0.0..1.0: ${params.thresholdRatio}"
        }
        dataStore.edit { prefs ->
            prefs[KEY_SEG_MIN_SILENCE_MS] = params.minSilenceMs
            prefs[KEY_SEG_MIN_SPEECH_MS] = params.minSpeechMs
            prefs[KEY_SEG_MERGE_GAP_MS] = params.mergeGapMs
            prefs[KEY_SEG_MAX_CHUNK_MS] = params.maxChunkMs
            prefs[KEY_SEG_PRE_PADDING_MS] = params.prePaddingMs
            prefs[KEY_SEG_POST_PADDING_MS] = params.postPaddingMs
            prefs[KEY_SEG_THRESHOLD_RATIO] = params.thresholdRatio
        }
    }

    override suspend fun setEnabledLanguages(languages: Set<LanguageTag>) {
        require(languages.isNotEmpty()) { "enabledLanguages must not be empty" }
        require(languages.all { it.value.isNotBlank() }) { "language tag must not be blank" }
        dataStore.edit { prefs ->
            prefs[KEY_ENABLED_LANGUAGES] = languages.joinToString(",") { it.value }
        }
    }

    override suspend fun setShowTargetRomanization(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_SHOW_TARGET_ROMANIZATION] = enabled
        }
    }

    override suspend fun setLookaheadTranscriptionEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_LOOKAHEAD_TRANSCRIPTION_ENABLED] = enabled
        }
    }

    private fun Preferences.toUserSettings(): UserSettings {
        val defaults = UserSettings()
        val defaultSeg = defaults.defaultSegmentationParams
        val targetLang = this[KEY_DEFAULT_TARGET_LANG]
            ?.takeIf { it.isNotBlank() }
            ?.let(::LanguageTag)
            ?: defaults.defaultLanguagePair.target
        val rawBaseLang = this[KEY_DEFAULT_BASE_LANG]
        val baseLang = when {
            rawBaseLang == null -> defaults.defaultLanguagePair.base
            rawBaseLang.isBlank() -> null
            else -> LanguageTag(rawBaseLang)
        }
        val chineseScript = this[KEY_CHINESE_SCRIPT]
            ?.let { name -> ChineseScript.entries.firstOrNull { it.name == name } }
            ?: defaults.chineseScript
        val cantoneseScript = this[KEY_CANTONESE_SCRIPT]
            ?.let { name -> ChineseScript.entries.firstOrNull { it.name == name } }
            ?: defaults.cantoneseScript
        val defaultSpeed = this[KEY_DEFAULT_SPEED]
            ?.takeIf { it in ItemState.MIN_SPEED..ItemState.MAX_SPEED }
            ?: defaults.defaultSpeed
        val seekBackMs = this[KEY_SEEK_BACK_MS]
            ?.takeIf { it > 0L }
            ?: defaults.seekBackMs
        val seekForwardMs = this[KEY_SEEK_FORWARD_MS]
            ?.takeIf { it > 0L }
            ?: defaults.seekForwardMs
        val defaultAutoPause = this[KEY_DEFAULT_AUTO_PAUSE]
            ?.let(::decodeAutoPause)
            ?: defaults.defaultAutoPause
        val defaultAutoPauseScope = this[KEY_DEFAULT_AUTO_PAUSE_SCOPE]
            ?.let { name -> AutoPauseScope.entries.firstOrNull { it.name == name } }
            ?: defaults.defaultAutoPauseScope

        val minSilenceMs = this[KEY_SEG_MIN_SILENCE_MS]
            ?.takeIf { it > 0 }
            ?: defaultSeg.minSilenceMs
        val minSpeechMs = this[KEY_SEG_MIN_SPEECH_MS]
            ?.takeIf { it > 0 }
            ?: defaultSeg.minSpeechMs
        val mergeGapMs = this[KEY_SEG_MERGE_GAP_MS]
            ?.takeIf { it >= minSilenceMs }
            ?: defaultSeg.mergeGapMs.coerceAtLeast(minSilenceMs)
        val maxChunkMs = this[KEY_SEG_MAX_CHUNK_MS]
            ?.takeIf { it > minSpeechMs }
            ?: defaultSeg.maxChunkMs.coerceAtLeast(minSpeechMs + 1)
        val prePaddingMs = this[KEY_SEG_PRE_PADDING_MS]
            ?.takeIf { it >= 0 }
            ?: defaultSeg.prePaddingMs
        val postPaddingMs = this[KEY_SEG_POST_PADDING_MS]
            ?.takeIf { it >= 0 }
            ?: defaultSeg.postPaddingMs
        val thresholdRatio = this[KEY_SEG_THRESHOLD_RATIO]
            ?.takeIf { it in 0.0f..1.0f }
            ?: defaultSeg.thresholdRatio
        val enabledLanguages = this[KEY_ENABLED_LANGUAGES]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.map(::LanguageTag)
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
            ?: UserSettings.DEFAULT_ENABLED_LANGUAGES

        return UserSettings(
            defaultLanguagePair = LanguagePair(target = targetLang, base = baseLang),
            chineseScript = chineseScript,
            cantoneseScript = cantoneseScript,
            defaultSpeed = defaultSpeed,
            seekBackMs = seekBackMs,
            seekForwardMs = seekForwardMs,
            defaultAutoPause = defaultAutoPause,
            defaultAutoPauseScope = defaultAutoPauseScope,
            defaultSegmentationParams = SegmentationParams(
                minSilenceMs = minSilenceMs,
                minSpeechMs = minSpeechMs,
                mergeGapMs = mergeGapMs,
                maxChunkMs = maxChunkMs,
                prePaddingMs = prePaddingMs,
                postPaddingMs = postPaddingMs,
                thresholdRatio = thresholdRatio,
            ),
            enabledLanguages = enabledLanguages,
            showTargetRomanization = this[KEY_SHOW_TARGET_ROMANIZATION] ?: defaults.showTargetRomanization,
            lookaheadTranscriptionEnabled = this[KEY_LOOKAHEAD_TRANSCRIPTION_ENABLED]
                ?: defaults.lookaheadTranscriptionEnabled,
        )
    }

    private companion object {
        const val AUTO_PAUSE_OFF = "OFF"
        const val PREFIX_FIXED = "FIXED:"
        const val PREFIX_PROPORTIONAL = "PROPORTIONAL:"

        val KEY_DEFAULT_TARGET_LANG = stringPreferencesKey("default_target_lang")
        val KEY_DEFAULT_BASE_LANG = stringPreferencesKey("default_base_lang")
        val KEY_CHINESE_SCRIPT = stringPreferencesKey("chinese_script")
        val KEY_CANTONESE_SCRIPT = stringPreferencesKey("cantonese_script")
        val KEY_DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val KEY_SEEK_BACK_MS = longPreferencesKey("seek_back_ms")
        val KEY_SEEK_FORWARD_MS = longPreferencesKey("seek_forward_ms")
        val KEY_DEFAULT_AUTO_PAUSE = stringPreferencesKey("default_auto_pause")
        val KEY_DEFAULT_AUTO_PAUSE_SCOPE = stringPreferencesKey("default_auto_pause_scope")
        val KEY_SEG_MIN_SILENCE_MS = intPreferencesKey("seg_min_silence_ms")
        val KEY_SEG_MIN_SPEECH_MS = intPreferencesKey("seg_min_speech_ms")
        val KEY_SEG_MERGE_GAP_MS = intPreferencesKey("seg_merge_gap_ms")
        val KEY_SEG_MAX_CHUNK_MS = intPreferencesKey("seg_max_chunk_ms")
        val KEY_SEG_PRE_PADDING_MS = intPreferencesKey("seg_pre_padding_ms")
        val KEY_SEG_POST_PADDING_MS = intPreferencesKey("seg_post_padding_ms")
        val KEY_SEG_THRESHOLD_RATIO = floatPreferencesKey("seg_threshold_ratio")
        val KEY_ENABLED_LANGUAGES = stringPreferencesKey("enabled_languages")
        val KEY_SHOW_TARGET_ROMANIZATION = booleanPreferencesKey("show_target_romanization")
        val KEY_LOOKAHEAD_TRANSCRIPTION_ENABLED = booleanPreferencesKey("lookahead_transcription_enabled")

        fun encodeAutoPause(autoPause: AutoPause): String = when (autoPause) {
            AutoPause.Off -> AUTO_PAUSE_OFF
            is AutoPause.Fixed -> "$PREFIX_FIXED${autoPause.pauseMs}"
            is AutoPause.Proportional -> "$PREFIX_PROPORTIONAL${autoPause.factor}:${autoPause.minMs}"
        }

        fun decodeAutoPause(raw: String): AutoPause = when {
            raw == AUTO_PAUSE_OFF -> AutoPause.Off

            raw.startsWith(PREFIX_FIXED) -> {
                val pauseMs = raw.removePrefix(PREFIX_FIXED).toLongOrNull()
                if (pauseMs != null && pauseMs > 0L) AutoPause.Fixed(pauseMs) else AutoPause.Off
            }

            raw.startsWith(PREFIX_PROPORTIONAL) -> {
                val parts = raw.removePrefix(PREFIX_PROPORTIONAL).split(":")
                val factor = parts.getOrNull(0)?.toFloatOrNull()
                val minMs = parts.getOrNull(1)?.toLongOrNull()
                if (factor != null && factor > 0f && factor.isFinite() && minMs != null && minMs >= 0L) {
                    AutoPause.Proportional(factor = factor, minMs = minMs)
                } else {
                    AutoPause.Off
                }
            }

            else -> AutoPause.Off
        }
    }
}
