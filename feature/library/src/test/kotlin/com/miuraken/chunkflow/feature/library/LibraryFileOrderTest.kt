package com.miuraken.chunkflow.feature.library

import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** ライブラリのファイルの並び（[LibraryFileOrder]）と、ファイル名の取り出し（[sortableFileNameOf]）のテスト。 */
class LibraryFileOrderTest {

    private fun item(
        id: String,
        title: String,
        localFilePath: String? = null,
        sourceUri: String? = null,
    ): AudioItem = SampleMaterials.jaKo.item.copy(
        id = MediaId(id),
        title = title,
        localFilePath = localFilePath,
        sourceUri = sourceUri,
    )

    private fun List<AudioItem>.sortedForLibrary(): List<String> = map { LibraryItemUiModel(item = it) }
        .sortedWith(LibraryFileOrder)
        .map { it.item.id.value }

    // ---- fileNameFromSourceUri ----

    @Test
    fun `fileNameFromSourceUri_ローカルのストレージのツリーのドキュメントURIならドキュメントIDの最後の名前を返す`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FCourse%20A" +
            "/document/primary%3AMusic%2FCourse%20A%2FTrack%2001.mp3"
        assertEquals("Track 01.mp3", fileNameFromSourceUri(uri))
    }

    @Test
    fun `fileNameFromSourceUri_ボリュームの直下やSDカードのファイルでも名前を返し+は空白にしない`() {
        assertEquals(
            "Track 02.mp3",
            fileNameFromSourceUri("content://com.android.externalstorage.documents/document/primary%3ATrack%2002.mp3"),
        )
        assertEquals(
            "lesson+1.m4a",
            fileNameFromSourceUri(
                "content://com.android.externalstorage.documents/document/1A2B-3C4D%3AAudio%2Flesson%2B1.m4a",
            ),
        )
    }

    @Test
    fun `fileNameFromSourceUri_日本語のファイル名もURLデコードして返す`() {
        val uri = "content://com.android.externalstorage.documents/tree/primary%3AMusic" +
            "/document/primary%3AMusic%2F%E9%9F%93%E5%9B%BD%E8%AA%9E%2F%E7%AC%AC01%E8%AA%B2.mp3"
        assertEquals("第01課.mp3", fileNameFromSourceUri(uri))
    }

    @Test
    fun `fileNameFromSourceUri_rawのドキュメントIDとfileのURIはパスの最後の名前を返す`() {
        assertEquals(
            "podcast 10.mp3",
            fileNameFromSourceUri(
                "content://com.android.providers.downloads.documents/document/" +
                    "raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2Fpodcast%2010.mp3",
            ),
        )
        assertEquals("a b.mp3", fileNameFromSourceUri("file:///storage/emulated/0/Music/a%20b.mp3"))
        assertEquals("c.mp3", fileNameFromSourceUri("/storage/emulated/0/Music/c.mp3"))
    }

    @Test
    fun `fileNameFromSourceUri_ファイル名を含まないURIはnullを返す`() {
        // Google ドライブ（デコードすると / を含むこともある）
        assertNull(
            fileNameFromSourceUri(
                "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3DAbC%2FdEf%3D",
            ),
        )
        // メディアのプロバイダ・ダウンロードの msf・メディアストア
        assertNull(fileNameFromSourceUri("content://com.android.providers.media.documents/document/audio%3A1234"))
        assertNull(fileNameFromSourceUri("content://com.android.providers.downloads.documents/document/msf%3A1000"))
        assertNull(fileNameFromSourceUri("content://media/external/audio/media/42"))
        assertNull(fileNameFromSourceUri(null))
        assertNull(fileNameFromSourceUri("  "))
    }

    @Test
    fun `fileNameFromSourceUri_壊れたエスケープでも例外を投げない`() {
        assertNull(
            fileNameFromSourceUri("content://com.android.externalstorage.documents/document/primary%3AMusic%2F100%zz.mp3"),
        )
    }

    // ---- fileNameFromLocalPath・removeFileExtension ----

    @Test
    fun `fileNameFromLocalPath_絶対パスなら最後の名前を返しプレビュー用の値や空はnull`() {
        assertEquals("Track 03.mp3", fileNameFromLocalPath("/storage/emulated/0/Music/Course/Track 03.mp3"))
        assertNull(fileNameFromLocalPath("preview:cover_book"))
        assertNull(fileNameFromLocalPath(""))
        assertNull(fileNameFromLocalPath(null))
    }

    @Test
    fun `removeFileExtension_英字を含む短い拡張子だけを外す`() {
        assertEquals("01", removeFileExtension("01.mp3"))
        assertEquals("Track 1", removeFileExtension("Track 1.MP3"))
        assertEquals("a.b", removeFileExtension("a.b.m4a"))
        assertEquals("voice", removeFileExtension("voice.3gp"))
        assertEquals("Lesson 1.5", removeFileExtension("Lesson 1.5"))
        assertEquals("archive.backup2025", removeFileExtension("archive.backup2025"))
        assertEquals(".mp3", removeFileExtension(".mp3"))
        assertEquals("noext", removeFileExtension("noext"))
    }

    // ---- sortableFileNameOf ----

    @Test
    fun `sortableFileNameOf_ローカルのパスを優先し次に元のURIでどちらもなければタイトル`() {
        val withBoth = item(
            id = "a",
            title = "Tag Title",
            localFilePath = "/storage/emulated/0/Music/C/From Path.mp3",
            sourceUri = "content://com.android.externalstorage.documents/document/primary%3AMusic%2FC%2FFrom%20Uri.mp3",
        )
        assertEquals("From Path", sortableFileNameOf(withBoth))

        val uriOnly = withBoth.copy(localFilePath = null)
        assertEquals("From Uri", sortableFileNameOf(uriOnly))

        val drive = withBoth.copy(
            localFilePath = null,
            sourceUri = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dencoded%3Dxyz",
        )
        assertEquals("Tag Title", sortableFileNameOf(drive))

        val copied = withBoth.copy(localFilePath = null, sourceUri = null)
        assertEquals("Tag Title", sortableFileNameOf(copied))
    }

    // ---- LibraryFileOrder ----

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
    fun `LibraryFileOrder_数字は桁が違っても数値として比べ大文字小文字は区別しない`() {
        val items = listOf(
            item("10", title = "x", localFilePath = "/m/Lesson 10.mp3"),
            item("2", title = "x", localFilePath = "/m/lesson 2.mp3"),
            item("1", title = "x", localFilePath = "/m/LESSON 1.mp3"),
            item("b", title = "x", localFilePath = "/m/b01.mp3"),
            item("a", title = "x", localFilePath = "/m/A02.mp3"),
        )
        assertEquals(listOf("a", "b", "1", "2", "10"), items.sortedForLibrary())
    }

    @Test
    fun `LibraryFileOrder_拡張子は並びに使わずファイル名が同じならタイトルそのあとIDで決める`() {
        val items = listOf(
            item("z", title = "Same", localFilePath = "/m/Part 1.mp3"),
            item("y", title = "Same", localFilePath = "/m/Part 1.m4a"),
            item("x", title = "Another", localFilePath = "/m/Part 1.ogg"),
            item("w", title = "x", localFilePath = "/m/Part 1 review.mp3"),
        )
        // 「Part 1」の 3 つはタイトル（Another < Same）→ ID（y < z）の順。「Part 1 review」はその後
        assertEquals(listOf("x", "y", "z", "w"), items.sortedForLibrary())
    }

    @Test
    fun `LibraryFileOrder_ファイル名がわからないファイルはタイトルでファイル名と並べて比べる`() {
        val items = listOf(
            item("local6", title = "x", localFilePath = "/m/Lesson 06.mp3"),
            item("drive5", title = "Lesson 05"),
            item("local4", title = "x", localFilePath = "/m/Lesson 04.mp3"),
        )
        assertEquals(listOf("local4", "drive5", "local6"), items.sortedForLibrary())
    }
}
