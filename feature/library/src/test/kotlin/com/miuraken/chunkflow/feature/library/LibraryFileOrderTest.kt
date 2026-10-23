package com.miuraken.chunkflow.feature.library

import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ライブラリのファイルの並び（[LibraryFileOrder]）と、ファイル名での検索のテスト。
 *
 * 名前の比べ方やファイル名の取り出し方そのものは core:model の `AudioItemFileOrderTest` で確かめている。
 */
class LibraryFileOrderTest {

    private val driveUriPrefix = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3D"

    private fun item(
        id: String,
        title: String,
        localFilePath: String? = null,
        sourceUri: String? = null,
        displayName: String? = null,
    ): AudioItem = SampleMaterials.jaKo.item.copy(
        id = MediaId(id),
        title = title,
        localFilePath = localFilePath,
        sourceUri = sourceUri,
        displayName = displayName,
    )

    private fun List<AudioItem>.sortedForLibrary(): List<String> = map { LibraryItemUiModel(item = it) }
        .sortedWith(LibraryFileOrder)
        .map { it.item.id.value }

    @Test
    fun `LibraryFileOrder_タイトルの順ではなくファイル名の順に並べる`() {
        // 曲名のタグ（タイトル）はファイル名の順の逆
        val items = listOf(
            item("t10", title = "Alpha", localFilePath = "/m/Track 10.mp3"),
            item("t3", title = "Beta", localFilePath = "/m/track 3.mp3"),
            item("t2", title = "Epsilon", localFilePath = "/m/Track 2.mp3"),
            item("t1", title = "Zeta", localFilePath = "/m/Track 1.mp3"),
        )
        assertEquals(listOf("t1", "t2", "t3", "t10"), items.sortedForLibrary())
    }

    @Test
    fun `LibraryFileOrder_元のファイル名を最優先しファイル名がわからないファイルはタイトルで比べる`() {
        val items = listOf(
            // ローカルのパスより元のファイル名（displayName）を優先する
            item("renamed", title = "Beta", localFilePath = "/m/Lesson 01.mp3", displayName = "Lesson 10.mp3"),
            // 元のファイル名がまだ補われていない Google ドライブのファイルはタイトルで比べる
            item("drive5", title = "Lesson 05", sourceUri = driveUriPrefix + "xyz"),
            item("local4", title = "Alpha", localFilePath = "/m/Lesson 04.mp3"),
            item("drive2", title = "Zeta", sourceUri = driveUriPrefix + "abc", displayName = "Lesson 02.mp3"),
        )
        assertEquals(listOf("drive2", "local4", "drive5", "renamed"), items.sortedForLibrary())
    }

    @Test
    fun `ファイル名で検索_タイトルが違っても元のファイル名で見つかる`() {
        val state = LibraryUiState(
            items = listOf(
                item("drive2", title = "Unit Two", sourceUri = driveUriPrefix + "abc", displayName = "Lesson 02.mp3"),
                item("drive3", title = "Unit Three", sourceUri = driveUriPrefix + "def", displayName = "Track 03.mp3"),
            ).map { LibraryItemUiModel(item = it) },
            fileSearchQuery = "lesson 02",
        )
        assertEquals(listOf("drive2"), state.filteredAllFiles.map { it.item.id.value })
    }
}
