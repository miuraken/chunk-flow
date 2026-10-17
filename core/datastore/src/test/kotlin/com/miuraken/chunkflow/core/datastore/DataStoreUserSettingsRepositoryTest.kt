package com.miuraken.chunkflow.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.SttEngine
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

    private fun createRepository(
        scope: TestScope,
        deviceLanguageTags: List<String> = listOf("ja-JP"),
    ): Pair<DataStoreUserSettingsRepository, androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>> {
        val file = File(tempFolder.newFolder(), "test_user_settings.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope.backgroundScope,
            produceFile = { file },
        )
        return DataStoreUserSettingsRepository(dataStore) { deviceLanguageTags } to dataStore
    }

    @Test
    fun `初期状態_UserSettingsの既定値を返す`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, _) = createRepository(this)
        assertEquals(UserSettings(), repository.settings.first())
    }

    @Test
    fun `各設定項目を更新するとFlowに反映される`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, _) = createRepository(this)
        val customSeg = SegmentationParams(
            minSilenceMs = 600,
            minSpeechMs = 300,
            mergeGapMs = 1_200,
            maxChunkMs = 12_000,
            prePaddingMs = 100,
            postPaddingMs = 250,
            thresholdRatio = 0.4f,
        )

        repository.setDefaultLanguagePair(LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")))
        repository.setChineseScript(ChineseScript.HANT)
        repository.setCantoneseScript(ChineseScript.HANS)
        repository.setDefaultSpeed(1.25f)
        repository.setSeekBackMs(2_000L)
        repository.setSeekForwardMs(10_000L)
        repository.setDefaultAutoPause(AutoPause.Fixed(pauseMs = 1_500L))
        repository.setDefaultSegmentationParams(customSeg)

        val current = repository.settings.first()
        assertEquals(LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")), current.defaultLanguagePair)
        assertEquals(ChineseScript.HANT, current.chineseScript)
        assertEquals(ChineseScript.HANS, current.cantoneseScript)
        assertEquals(1.25f, current.defaultSpeed)
        assertEquals(2_000L, current.seekBackMs)
        assertEquals(10_000L, current.seekForwardMs)
        assertEquals(AutoPause.Fixed(pauseMs = 1_500L), current.defaultAutoPause)
        assertEquals(customSeg, current.defaultSegmentationParams)

        repository.setDefaultAutoPause(AutoPause.Proportional(factor = 1.5f, minMs = 800L))
        assertEquals(AutoPause.Proportional(factor = 1.5f, minMs = 800L), repository.settings.first().defaultAutoPause)

        repository.setDefaultAutoPause(AutoPause.Off)
        assertEquals(AutoPause.Off, repository.settings.first().defaultAutoPause)

        val customLangs = setOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ko"))
        repository.setEnabledLanguages(customLangs)
        assertEquals(customLangs, repository.settings.first().enabledLanguages)

        repository.setShowTargetRomanization(true)
        assertEquals(true, repository.settings.first().showTargetRomanization)
        repository.setShowTargetRomanization(false)
        assertEquals(false, repository.settings.first().showTargetRomanization)

        assertEquals(true, repository.settings.first().lookaheadTranscriptionEnabled)
        repository.setLookaheadTranscriptionEnabled(false)
        assertEquals(false, repository.settings.first().lookaheadTranscriptionEnabled)
        repository.setLookaheadTranscriptionEnabled(true)
        assertEquals(true, repository.settings.first().lookaheadTranscriptionEnabled)

        assertEquals(OnDeviceTranscriptionEngine.AUTO, repository.settings.first().transcriptionEngine)
        repository.setTranscriptionEngine(OnDeviceTranscriptionEngine.SHERPA_ONNX)
        assertEquals(OnDeviceTranscriptionEngine.SHERPA_ONNX, repository.settings.first().transcriptionEngine)
        repository.setTranscriptionEngine(OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER)
        assertEquals(OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER, repository.settings.first().transcriptionEngine)
        repository.setTranscriptionEngine(OnDeviceTranscriptionEngine.ML_KIT)
        assertEquals(OnDeviceTranscriptionEngine.ML_KIT, repository.settings.first().transcriptionEngine)
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
        assertFailsWith<IllegalArgumentException> {
            repository.setDefaultAutoPause(AutoPause.Fixed(pauseMs = 0L))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.setDefaultAutoPause(AutoPause.Proportional(factor = 0f, minMs = 1_000L))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.setDefaultSegmentationParams(SegmentationParams(minSilenceMs = 800, mergeGapMs = 500))
        }
        assertFailsWith<IllegalArgumentException> {
            repository.setDefaultSegmentationParams(SegmentationParams(thresholdRatio = 1.5f))
        }

        // DataStore に直接不正な値が入った場合でもクラッシュせず既定値にフォールバックする
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("chinese_script")] = "UNKNOWN"
            prefs[floatPreferencesKey("default_speed")] = 9.9f
            prefs[longPreferencesKey("seek_back_ms")] = -1L
            prefs[stringPreferencesKey("default_auto_pause")] = "FIXED:-100"
            prefs[intPreferencesKey("seg_min_silence_ms")] = -10
            prefs[floatPreferencesKey("seg_threshold_ratio")] = 2.0f
            prefs[stringPreferencesKey("transcription_engine")] = "INVALID_ENGINE"
        }
        val current = repository.settings.first()
        assertEquals(ChineseScript.HANS, current.chineseScript)
        assertEquals(1.0f, current.defaultSpeed)
        assertEquals(UserSettings.DEFAULT_SEEK_BACK_MS, current.seekBackMs)
        assertEquals(AutoPause.Off, current.defaultAutoPause)
        assertEquals(SegmentationParams(), current.defaultSegmentationParams)
        assertEquals(OnDeviceTranscriptionEngine.AUTO, current.transcriptionEngine)
    }

    @Test
    fun `言語設定が未保存_端末の言語と英語が既定で有効になる`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, _) = createRepository(this, deviceLanguageTags = listOf("ko-KR", "zh-Hant-TW", "xx-YY"))

        // 一覧にない言語（xx）は使わず、繁体中国語は zh-tw にそろえる
        assertEquals(
            listOf(LanguageTag("ko"), LanguageTag("zh-tw"), LanguageTag("en")),
            repository.settings.first().enabledLanguages.toList(),
        )
    }

    @Test
    fun `以前のキーenabled_languagesは読まずに新しい既定から始める`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, dataStore) = createRepository(this, deviceLanguageTags = listOf("ja-JP"))
        dataStore.edit { prefs -> prefs[stringPreferencesKey("enabled_languages")] = "ko,hi" }

        assertEquals(setOf(LanguageTag("ja"), LanguageTag("en")), repository.settings.first().enabledLanguages)

        // 新しいキーに書くと、以前のキーは消える
        repository.setEnabledLanguages(setOf(LanguageTag("fr")))
        assertEquals(null, dataStore.data.first()[stringPreferencesKey("enabled_languages")])
        assertEquals(setOf(LanguageTag("fr")), repository.settings.first().enabledLanguages)
    }

    @Test
    fun `選んだ言語は設定用の言語コードにそろえて保存する`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, _) = createRepository(this)

        repository.setEnabledLanguages(setOf(LanguageTag("zh"), LanguageTag("zh-Hant"), LanguageTag("EN")))

        assertEquals(
            setOf(LanguageTag("zh-cn"), LanguageTag("zh-tw"), LanguageTag("en")),
            repository.settings.first().enabledLanguages,
        )
    }

    @Test
    fun `言語ごとのエンジンを設定と解除でき_言語コードはそろえて保存する`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, _) = createRepository(this)
        assertEquals(emptyMap(), repository.settings.first().sttEngineByLanguage)

        repository.setSttEngineForLanguage(LanguageTag("ko"), SttEngine.SHERPA_ONNX)
        repository.setSttEngineForLanguage(LanguageTag("zh-Hans"), SttEngine.SPEECH_RECOGNIZER)
        assertEquals(
            mapOf(LanguageTag("ko") to SttEngine.SHERPA_ONNX, LanguageTag("zh-cn") to SttEngine.SPEECH_RECOGNIZER),
            repository.settings.first().sttEngineByLanguage,
        )

        repository.setSttEngineForLanguage(LanguageTag("ko"), null)
        assertEquals(
            mapOf(LanguageTag("zh-cn") to SttEngine.SPEECH_RECOGNIZER),
            repository.settings.first().sttEngineByLanguage,
        )

        assertFailsWith<IllegalArgumentException> { repository.setSttEngineForLanguage(LanguageTag(" "), SttEngine.ML_KIT) }
    }

    @Test
    fun `保存済みの言語ごとのエンジンが壊れていても読める項目だけを使う`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, dataStore) = createRepository(this)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey("stt_engine_by_language")] = "ja=ML_KIT,ko=UNKNOWN,=SHERPA_ONNX,broken,hi=SHERPA_ONNX"
        }

        assertEquals(
            mapOf(LanguageTag("ja") to SttEngine.ML_KIT, LanguageTag("hi") to SttEngine.SHERPA_ONNX),
            repository.settings.first().sttEngineByLanguage,
        )
    }

    @Test
    fun `チャンク間の無音スキップ設定を更新できる`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, _) = createRepository(this)
        assertEquals(false, repository.settings.first().skipOriginalSilence)

        repository.setSkipOriginalSilence(true)
        assertEquals(true, repository.settings.first().skipOriginalSilence)

        repository.setSkipOriginalSilence(false)
        assertEquals(false, repository.settings.first().skipOriginalSilence)
    }

    @Test
    fun `初回起動の言語選択完了フラグはcompleteLanguageOnboardingでのみ完了になり言語変更だけでは閉じない`() = runTest(UnconfinedTestDispatcher()) {
        val (repository, _) = createRepository(this)
        assertEquals(false, repository.settings.first().hasCompletedLanguageOnboarding)

        repository.setEnabledLanguages(setOf(LanguageTag("en"), LanguageTag("ja"), LanguageTag("ko")))
        assertEquals(false, repository.settings.first().hasCompletedLanguageOnboarding)

        repository.completeLanguageOnboarding()
        assertEquals(true, repository.settings.first().hasCompletedLanguageOnboarding)
    }
}
