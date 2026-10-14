package com.miuraken.chunkflow.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
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

    private fun Preferences.toUserSettings(): UserSettings {
        val defaults = UserSettings()
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
        return UserSettings(
            defaultLanguagePair = LanguagePair(target = targetLang, base = baseLang),
            chineseScript = chineseScript,
            cantoneseScript = cantoneseScript,
            defaultSpeed = defaultSpeed,
            seekBackMs = seekBackMs,
            seekForwardMs = seekForwardMs,
        )
    }

    private companion object {
        val KEY_DEFAULT_TARGET_LANG = stringPreferencesKey("default_target_lang")
        val KEY_DEFAULT_BASE_LANG = stringPreferencesKey("default_base_lang")
        val KEY_CHINESE_SCRIPT = stringPreferencesKey("chinese_script")
        val KEY_CANTONESE_SCRIPT = stringPreferencesKey("cantonese_script")
        val KEY_DEFAULT_SPEED = floatPreferencesKey("default_speed")
        val KEY_SEEK_BACK_MS = longPreferencesKey("seek_back_ms")
        val KEY_SEEK_FORWARD_MS = longPreferencesKey("seek_forward_ms")
    }
}
