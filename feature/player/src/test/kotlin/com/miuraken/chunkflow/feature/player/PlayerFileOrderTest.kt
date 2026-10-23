package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * プレイヤーの「次のファイル」の順番（ライブラリと同じファイル名の昇順）のテスト。
 *
 * 名前の比べ方やファイル名の取り出し方そのものは core:model の `AudioItemFileOrderTest` で確かめている。
 */
class PlayerFileOrderTest {

    private fun item(
        id: String,
        title: String,
        course: String? = "Course",
        localFilePath: String? = null,
        sourceUri: String? = null,
        displayName: String? = null,
    ): AudioItem = SampleMaterials.jaKo.item.copy(
        id = MediaId(id),
        title = title,
        course = course,
        localFilePath = localFilePath,
        sourceUri = sourceUri,
        displayName = displayName,
    )

    @Test
    fun `nextMediaIdInSameFolder_タイトルの順ではなくファイル名の順で次のファイルを決める`() {
        // 曲名のタグ（タイトル）はファイル名の順の逆
        val t1 = item("t1", title = "Zeta", localFilePath = "/storage/emulated/0/Music/Course/Track 1.mp3")
        val t2 = item(
            "t2",
            title = "Epsilon",
            sourceUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FCourse" +
                "/document/primary%3AMusic%2FCourse%2FTrack%202.mp3",
        )
        val t10 = item("t10", title = "Alpha", localFilePath = "/storage/emulated/0/Music/Course/Track 10.mp3")
        val other = item("other", title = "Beta", course = "Other", localFilePath = "/storage/emulated/0/Music/Other/Track 3.mp3")
        val all = listOf(t10, other, t2, t1)

        assertEquals(t2, nextMediaItemInSameFolder(t1, all))
        assertEquals(t2.id, nextMediaIdInSameFolder(t1, all))
        assertEquals(t10.id, nextMediaIdInSameFolder(t2, all))
        assertNull(nextMediaItemInSameFolder(t10, all))
        assertNull(nextMediaIdInSameFolder(t10, all))
        // ほかのフォルダのファイルには進まない
        assertNull(nextMediaIdInSameFolder(other, all))
    }

    @Test
    fun `nextMediaIdInSameFolder_フォルダ名の前後のスラッシュの違いは同じフォルダとみなす`() {
        val a = item("a", title = "x", course = "Course", localFilePath = "/m/Course/01.mp3")
        val b = item("b", title = "x", course = "/Course/", localFilePath = "/m/Course/02.mp3")
        assertEquals(b.id, nextMediaIdInSameFolder(a, listOf(b, a)))
    }

    @Test
    fun `sortedInLibraryFileOrder_元のファイル名を最優先しファイル名がわからないファイルはタイトルで比べる`() {
        // Google ドライブのファイル: 元のファイル名（displayName）が保存されていればそれで並べる
        val drive2 = item(
            "drive2",
            title = "Zeta",
            sourceUri = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3Dabc",
            displayName = "Lesson 02.mp3",
        )
        // 元のファイル名がまだ補われていないドライブのファイルはタイトルで比べる
        val drive5 = item(
            "drive5",
            title = "Lesson 05",
            sourceUri = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3Dxyz",
        )
        val local4 = item("local4", title = "Alpha", localFilePath = "/m/Lesson 04.mp3")
        // displayName はローカルのパスより優先する
        val renamed = item("renamed", title = "Beta", localFilePath = "/m/Lesson 01.mp3", displayName = "Lesson 10.mp3")
        assertEquals(
            listOf("drive2", "local4", "drive5", "renamed"),
            sortedInLibraryFileOrder(listOf(renamed, drive5, local4, drive2)).map { it.id.value },
        )
    }
}
