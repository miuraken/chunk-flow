package com.miuraken.chunkflow.core.testing.fake

import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.SegmentationParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class FakeUserSettingsRepositoryTest {
    @Test
    fun `各設定項目を更新するとsettingsに反映され不変条件も検査される`() = runTest {
        val repo = FakeUserSettingsRepository()
        val segParams = SegmentationParams(minSilenceMs = 400, mergeGapMs = 900)
        repo.setDefaultLanguagePair(LanguagePair(target = LanguageTag("zh"), base = LanguageTag("en")))
        repo.setChineseScript(ChineseScript.HANT)
        repo.setCantoneseScript(ChineseScript.HANS)
        repo.setDefaultSpeed(1.5f)
        repo.setSeekBackMs(4_000L)
        repo.setSeekForwardMs(8_000L)
        repo.setDefaultAutoPause(AutoPause.Fixed(pauseMs = 2_000L))
        repo.setDefaultSegmentationParams(segParams)

        val current = repo.settings.first()
        assertEquals(LanguagePair(target = LanguageTag("zh"), base = LanguageTag("en")), current.defaultLanguagePair)
        assertEquals(ChineseScript.HANT, current.chineseScript)
        assertEquals(ChineseScript.HANS, current.cantoneseScript)
        assertEquals(1.5f, current.defaultSpeed)
        assertEquals(4_000L, current.seekBackMs)
        assertEquals(8_000L, current.seekForwardMs)
        assertEquals(AutoPause.Fixed(pauseMs = 2_000L), current.defaultAutoPause)
        assertEquals(segParams, current.defaultSegmentationParams)

        assertFailsWith<IllegalArgumentException> { repo.setDefaultSpeed(3.0f) }
        assertFailsWith<IllegalArgumentException> { repo.setSeekBackMs(0L) }
    }
}
