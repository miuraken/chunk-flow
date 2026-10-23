package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** プレイヤーの「次のファイル」の順番（ライブラリと同じファイル名の昇順）のテスト。 */
class PlayerFileOrderTest {

    private fun item(
        id: String,
        title: String,
        course: String? = "Course",
        localFilePath: String? = null,
        sourceUri: String? = null,
    ): AudioItem = SampleMaterials.jaKo.item.copy(
        id = MediaId(id),
        title = title,
        course = course,
        localFilePath = localFilePath,
        sourceUri = sourceUri,
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

        assertEquals(t2.id, nextMediaIdInSameFolder(t1, all))
        assertEquals(t10.id, nextMediaIdInSameFolder(t2, all))
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
    fun `sortedInLibraryFileOrder_ファイル名がわからないファイルはタイトルで比べ同じ名前はタイトルそのあとIDで決める`() {
        val local4 = item("local4", title = "x", localFilePath = "/m/Lesson 04.mp3")
        val drive5 = item(
            "drive5",
            title = "Lesson 05",
            sourceUri = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3Dxyz",
        )
        val local6b = item("local6b", title = "Same", localFilePath = "/m/Lesson 06.m4a")
        val local6a = item("local6a", title = "Same", localFilePath = "/m/Lesson 06.mp3")
        assertEquals(
            listOf("local4", "drive5", "local6a", "local6b"),
            sortedInLibraryFileOrder(listOf(local6b, drive5, local6a, local4)).map { it.id.value },
        )
    }

    @Test
    fun `fileNameFromSourceUri_ローカルのストレージとrawとfileは名前を返しそれ以外はnull`() {
        assertEquals(
            "第01課.mp3",
            fileNameFromSourceUri(
                "content://com.android.externalstorage.documents/document/" +
                    "primary%3AMusic%2F%E9%9F%93%E5%9B%BD%E8%AA%9E%2F%E7%AC%AC01%E8%AA%B2.mp3",
            ),
        )
        assertEquals(
            "podcast 10.mp3",
            fileNameFromSourceUri(
                "content://com.android.providers.downloads.documents/document/" +
                    "raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fpodcast%2010.mp3",
            ),
        )
        assertEquals("a b.mp3", fileNameFromSourceUri("file:///storage/emulated/0/Music/a%20b.mp3"))
        assertNull(fileNameFromSourceUri("content://com.android.providers.media.documents/document/audio%3A1234"))
        assertNull(
            fileNameFromSourceUri(
                "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3DAbC%2FdEf%3D",
            ),
        )
        assertEquals("Lesson 1.5", removeFileExtension("Lesson 1.5"))
        assertEquals("Lesson 1", removeFileExtension("Lesson 1.mp3"))
    }
}
