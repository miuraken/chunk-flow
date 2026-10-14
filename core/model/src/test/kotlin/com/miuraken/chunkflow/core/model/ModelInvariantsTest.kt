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
}
