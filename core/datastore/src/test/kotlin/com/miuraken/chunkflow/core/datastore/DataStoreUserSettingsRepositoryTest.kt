package com.miuraken.chunkflow.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.UserSettings
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DataStoreUserSettingsRepositoryTest {
    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private fun createRepository(scope: TestScope): Pair<DataStoreUserSettingsRepository, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>> {
        val file = File(tempFolder.newFolder(), "test_user_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { file },
        )
        return DataStoreUserSettingsRepository(dataStore) to dataStore
    }

    @Test
    fun `初期状態_UserSettingsの既定値を返す`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, _) = createRepository(this)
        assertEquals(UserSettings(), repository.settings.first())
    }

    @Test
    fun `各設定項目を更新するとFlowに反映される`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, _) = createRepository(this)

        repository.setDefaultLanguagePair(LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")))
        repository.setChineseScript(ChineseScript.HANT)
        repository.setCantoneseScript(ChineseScript.HANS)
        repository.setDefaultSpeed(1.25f)
        repository.setSeekBackMs(2_000L)
        repository.setSeekForwardMs(10_000L)

        val current = repository.settings.first()
        assertEquals(LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")), current.defaultLanguagePair)
        assertEquals(ChineseScript.HANT, current.chineseScript)
        assertEquals(ChineseScript.HANS, current.cantoneseScript)
        assertEquals(1.25f, current.defaultSpeed)
        assertEquals(2_000L, current.seekBackMs)
        assertEquals(10_000L, current.seekForwardMs)
    }

    @Test
    fun `単一言語ペア_baseをnullに設定しても既定値に戻らずnullとして保持される`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, _) = createRepository(this)

        repository.setDefaultLanguagePair(LanguagePair(target = LanguageTag("yue"), base = null))
        assertEquals(LanguagePair(target = LanguageTag("yue"), base = null), repository.settings.first().defaultLanguagePair)
    }

    @Test
    fun `不正な設定値の書き込みは例外になり保存済みの異常値は既定値へフォールバックする`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, dataStore) = createRepository(this)

        assertFailsWith<IllegalArgumentException> { repository.setDefaultSpeed(0.1f) }
        assertFailsWith<IllegalArgumentException> { repository.setSeekBackMs(0L) }
        assertFailsWith<IllegalArgumentException> { repository.setSeekForwardMs(-500L) }
        assertFailsWith<IllegalArgumentException> {
            repository.setDefaultLanguagePair(LanguagePair(target = LanguageTag(""), base = LanguageTag("ja")))
        }

        // DataStore に直接不正な値が入った場合でもクラッシュせず既定値にフォールバックする
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("chinese_script")] = "UNKNOWN"
            prefs[floatPreferencesKey("default_speed")] = 9.9f
            prefs[longPreferencesKey("seek_back_ms")] = -1L
        }
        val current = repository.settings.first()
        assertEquals(ChineseScript.HANS, current.chineseScript)
        assertEquals(1.0f, current.defaultSpeed)
        assertEquals(UserSettings.DEFAULT_SEEK_BACK_MS, current.seekBackMs)
    }
}
