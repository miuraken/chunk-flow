package com.miuraken.chunkflow.core.testing.fake

import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.LibraryFolderKind
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
            initial.copy(positionMs = 5_000L, maxPositionMs = 5_000L, speed = 1.25f, updatedAtEpochMs = 200L),
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

    @Test
    fun `setItemTagsとupdateItemTags_本番と同じく表記をそろえ既存のタグにまとめ使われないタグは消える`() = runTest {
        val first = SampleMaterials.jaKo.item
        val second = SampleMaterials.all.first { it.item.id != first.id }.item
        val repo = FakeLibraryRepository(initialItems = listOf(first, second))

        repo.setItemTags(first.id, listOf(" English ", "english", "シャドーイング"))
        repo.updateItemTags(setOf(first.id, second.id), addTags = setOf("ENGLISH", "TOEIC"), removeTags = setOf("シャドーイング"))
        assertEquals(
            mapOf(first.id to listOf("English", "TOEIC"), second.id to listOf("English", "TOEIC")),
            repo.observeItemTags().first(),
        )
        assertEquals(listOf("English", "TOEIC"), repo.observeTags().first())

        repo.delete(second.id)
        repo.setItemTags(first.id, listOf("TOEIC"))
        assertEquals(mapOf(first.id to listOf("TOEIC")), repo.observeItemTags().first())
        assertEquals(listOf("TOEIC"), repo.observeTags().first())
    }

    @Test
    fun `setCourseFolderColor_色を設定し取り込み直しても保ち対象言語の順番はフォルダが作られた順`() = runTest {
        val repo = FakeLibraryRepository(
            initialCourseFolders = listOf(
                LibraryCourseFolder(
                    coursePath = "韓国語",
                    kind = LibraryFolderKind.VIRTUAL_DIRECTORY,
                    languagePair = LanguagePair(LanguageTag("ko"), LanguageTag("ja")),
                ),
            ),
        )
        repo.createRealFolder("中国語", LanguagePair(LanguageTag("zh"), LanguageTag("ja")))
        assertEquals(
            mapOf(LanguageTag("ko") to 0, LanguageTag("zh-cn") to 1),
            repo.observeFolderLanguageColorOrdinals().first(),
        )

        repo.setCourseFolderColor("韓国語", 6)
        repo.createVirtualFolder("韓国語", LanguagePair(LanguageTag("ko"), LanguageTag("en")))
        assertEquals(6, repo.observeCourseFolders().first().first { it.coursePath == "韓国語" }.colorIndex)

        repo.setCourseFolderColor("韓国語", null)
        assertNull(repo.observeCourseFolders().first().first { it.coursePath == "韓国語" }.colorIndex)
    }
}
