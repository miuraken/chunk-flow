package com.miuraken.chunkflow.core.testing.fake

import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class FakeLibraryRepositoryTest {
    @Test
    fun `upsertItemStateとupdatePlaybackPosition_状態が保存され学習設定を維持して位置を更新できる`() = runTest {
        val item = SampleMaterials.jaKo.item
        val repo = FakeLibraryRepository(initialItems = listOf(item))

        assertNull(repo.observeItemState(item.id).first())

        val initial = ItemState(
            mediaId = item.id,
            positionMs = 1_000L,
            speed = 1.0f,
            chunkSource = ChunkOrigin.IMPORTED,
            autoPause = AutoPause.Fixed(1_500L),
            autoPauseScope = AutoPauseScope.AFTER_BASE_LANGUAGE,
            languageFilter = LanguageFilter.TARGET_ONLY,
            updatedAtEpochMs = 100L,
        )
        repo.upsertItemState(initial)
        assertEquals(initial, repo.observeItemState(item.id).first())
        assertEquals(mapOf(item.id to initial), repo.observeItemStates().first())

        repo.updatePlaybackPosition(
            id = item.id,
            positionMs = 5_000L,
            speed = 1.25f,
            updatedAtEpochMs = 200L,
        )
        val updated = repo.observeItemState(item.id).first()
        assertEquals(
            initial.copy(positionMs = 5_000L, speed = 1.25f, updatedAtEpochMs = 200L),
            updated,
        )
    }

    @Test
    fun `updatePlaybackPosition_状態がまだない教材でも新規作成される`() = runTest {
        val id = MediaId("00000000-0000-4000-8000-000000000099")
        val repo = FakeLibraryRepository()

        repo.updatePlaybackPosition(id = id, positionMs = 3_200L, speed = 1.5f, updatedAtEpochMs = 500L)
        assertEquals(
            ItemState(mediaId = id, positionMs = 3_200L, speed = 1.5f, updatedAtEpochMs = 500L),
            repo.observeItemState(id).first(),
        )
    }

    @Test
    fun `delete_教材を削除すると状態も合わせて消える`() = runTest {
        val item = SampleMaterials.jaKo.item
        val state = ItemState(mediaId = item.id, positionMs = 2_000L)
        val repo = FakeLibraryRepository(
            initialItems = listOf(item),
            initialItemStates = mapOf(item.id to state),
        )

        repo.delete(item.id)
        assertNull(repo.observeItem(item.id).first())
        assertNull(repo.observeItemState(item.id).first())
    }
}
