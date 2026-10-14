package com.miuraken.chunkflow.core.testing.fixture

import java.text.Normalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** フィクスチャ自体が設計書の規則（§7.4.3）を満たしていることを確かめる。 */
class SampleMaterialsTest {
    @Test
    fun `チャンクは開始時刻の順で重ならず音声の長さに収まる`() {
        SampleMaterials.all.forEach { m ->
            m.chunks.zipWithNext().forEach { (a, b) -> assertTrue(a.endMs <= b.startMs, m.key) }
            m.chunks.forEach { assertTrue(it.endMs <= m.item.durationMs, m.key) }
        }
    }

    @Test
    fun `文字起こしの言語は言語ペアのどちらかかnull`() {
        SampleMaterials.all.forEach { m ->
            val pair = setOfNotNull(m.item.languagePair.target, m.item.languagePair.base)
            m.transcripts.values.forEach { t -> assertTrue(t.language == null || t.language in pair, m.key) }
        }
    }

    @Test
    fun `本文と変種はNFCに正規化されている`() {
        SampleMaterials.all.flatMap { it.transcripts.values }.forEach { t ->
            (listOf(t.text) + t.textVariants.values).forEach { s ->
                assertEquals(Normalizer.normalize(s, Normalizer.Form.NFC), s)
            }
        }
    }

    @Test
    fun `MediaIdとチャンクIDは教材をまたいで重複しない`() {
        val ids = SampleMaterials.all.map { it.item.id }.toSet()
        assertEquals(SampleMaterials.all.map { it.key }.toSet().size, SampleMaterials.all.size)
        assertTrue(ids.size >= SampleMaterials.all.size - 1) // analyzingIndeterminate は analyzing と同じ教材
        val chunkIds = SampleMaterials.all.distinctBy { it.item.id }.flatMap { m -> m.chunks.map { it.id } }
        assertEquals(chunkIds.size, chunkIds.toSet().size)
    }

    @Test
    fun `波形の長さは音声の長さと一致する`() {
        SampleMaterials.all.forEach { m ->
            m.waveform?.let { assertEquals(m.item.durationMs, it.durationMs, m.key) }
        }
    }
}
