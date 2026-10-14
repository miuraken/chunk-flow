package com.miuraken.chunkflow.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModelInvariantsTest {
    @Test
    fun `Chunk_開始が終了以上なら作れない`() {
        assertFailsWith<IllegalArgumentException> { Chunk(ChunkId(1), 1_000, 1_000, ChunkOrigin.AUTO) }
        assertFailsWith<IllegalArgumentException> { Chunk(ChunkId(1), 2_000, 1_000, ChunkOrigin.AUTO) }
    }

    @Test
    fun `Chunk_長さは終了から開始を引いた値`() {
        assertEquals(750L, Chunk(ChunkId(1), 250, 1_000, ChunkOrigin.IMPORTED).durationMs)
    }

    @Test
    fun `AbRange_開始が終了以上なら作れない`() {
        assertFailsWith<IllegalArgumentException> { LoopMode.AbRange(500, 500) }
    }

    @Test
    fun `Waveform_peakとrmsの長さが違うと作れない`() {
        assertFailsWith<IllegalArgumentException> { Waveform(10, ByteArray(3), ByteArray(2)) }
    }

    @Test
    fun `Waveform_長さはビン数とビン幅の積`() {
        assertEquals(30L, Waveform(10, ByteArray(3), ByteArray(3)).durationMs)
    }

    @Test
    fun `PracticeSettings_既定値はすべてオフ`() {
        val settings = PracticeSettings()
        assertEquals(LoopMode.Off, settings.loop)
        assertEquals(AutoPause.Off, settings.autoPause)
        assertEquals(AutoPauseScope.ALL_CHUNKS, settings.autoPauseScope)
        assertEquals(LanguageFilter.ALL, settings.languageFilter)
    }

    @Test
    fun `ItemState_既定値と境界値で作れる`() {
        val id = MediaId("00000000-0000-4000-8000-000000000001")
        val state = ItemState(mediaId = id)
        assertEquals(0L, state.positionMs)
        assertEquals(1.0f, state.speed)
        assertEquals(ChunkOrigin.AUTO, state.chunkSource)
        assertEquals(AutoPause.Off, state.autoPause)
        assertEquals(AutoPauseScope.ALL_CHUNKS, state.autoPauseScope)
        assertEquals(LanguageFilter.ALL, state.languageFilter)
        assertEquals(0.5f, ItemState(mediaId = id, speed = 0.5f).speed)
        assertEquals(2.0f, ItemState(mediaId = id, speed = 2.0f).speed)
    }

    @Test
    fun `ItemState_位置が負または速度が範囲外なら作れない`() {
        val id = MediaId("00000000-0000-4000-8000-000000000001")
        assertFailsWith<IllegalArgumentException> { ItemState(mediaId = id, positionMs = -1L) }
        assertFailsWith<IllegalArgumentException> { ItemState(mediaId = id, speed = 0.49f) }
        assertFailsWith<IllegalArgumentException> { ItemState(mediaId = id, speed = 2.01f) }
    }

    @Test
    fun `UserSettings_既定値は英語と日本語で中国語は簡体字かつ広東語は繁体字`() {
        val settings = UserSettings()
        assertEquals(LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja")), settings.defaultLanguagePair)
        assertEquals(ChineseScript.HANS, settings.chineseScript)
        assertEquals("Hans", settings.chineseScript.iso15924)
        assertEquals(ChineseScript.HANT, settings.cantoneseScript)
        assertEquals("Hant", settings.cantoneseScript.iso15924)
        assertEquals(1.0f, settings.defaultSpeed)
        assertEquals(3_000L, settings.seekBackMs)
        assertEquals(5_000L, settings.seekForwardMs)
        assertEquals(AutoPause.Off, settings.defaultAutoPause)
        assertEquals(SegmentationParams(), settings.defaultSegmentationParams)
    }

    @Test
    fun `UserSettings_速度が範囲外またはスキップ秒数が0以下なら作れない`() {
        assertFailsWith<IllegalArgumentException> { UserSettings(defaultSpeed = 0.4f) }
        assertFailsWith<IllegalArgumentException> { UserSettings(defaultSpeed = 2.5f) }
        assertFailsWith<IllegalArgumentException> { UserSettings(seekBackMs = 0L) }
        assertFailsWith<IllegalArgumentException> { UserSettings(seekBackMs = -100L) }
        assertFailsWith<IllegalArgumentException> { UserSettings(seekForwardMs = 0L) }
        assertFailsWith<IllegalArgumentException> { UserSettings(seekForwardMs = -100L) }
    }

    @Test
    fun `Bookmark_位置が負なら作れない`() {
        val id = MediaId("00000000-0000-4000-8000-000000000001")
        assertFailsWith<IllegalArgumentException> { Bookmark(mediaId = id, positionMs = -1L) }
        assertEquals(0L, Bookmark(mediaId = id, positionMs = 0L).positionMs)
    }
}
