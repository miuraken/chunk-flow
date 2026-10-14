package com.miuraken.chunkflow.core.data.library

import com.miuraken.chunkflow.core.model.MediaId
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * M1 品質ゲート（qa 担当）: [MediaFileLocator] および [DefaultLibraryRepository.sanitizeDisplayName] の
 * パストラバーサル防止・UUID 検証・境界値のセキュリティ検証テスト（§7.1、§8.1、§8.2、§9）。
 */
@RunWith(RobolectricTestRunner::class)
class MediaFileLocatorSecurityQaTest {

    private lateinit var filesDir: File
    private lateinit var locator: MediaFileLocator

    @BeforeTest
    fun setUp() {
        filesDir = File(RuntimeEnvironment.getApplication().cacheDir, "qa-locator-test").apply {
            deleteRecursively()
            mkdirs()
        }
        locator = MediaFileLocator(filesDir)
    }

    @AfterTest
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `isSafeIdentifier_UUIDは許可しパストラバーサルや空文字列や長すぎるIDは拒否する`() {
        assertTrue(MediaFileLocator.isSafeIdentifier("00000000-0000-4000-8000-000000000001"))
        assertTrue(MediaFileLocator.isSafeIdentifier("valid_id-123"))

        assertFalse(MediaFileLocator.isSafeIdentifier(""))
        assertFalse(MediaFileLocator.isSafeIdentifier("../escape"))
        assertFalse(MediaFileLocator.isSafeIdentifier("..\\escape"))
        assertFalse(MediaFileLocator.isSafeIdentifier("dir/file"))
        assertFalse(MediaFileLocator.isSafeIdentifier("file.mp3"))
        assertFalse(MediaFileLocator.isSafeIdentifier("id with space"))
        assertFalse(MediaFileLocator.isSafeIdentifier("id\u0000null"))
        assertFalse(MediaFileLocator.isSafeIdentifier("a".repeat(129)))
    }

    @Test
    fun `audioFileForとtempFileFor_不正なIDや非対応拡張子はIllegalArgumentExceptionで拒否する`() {
        val validId = MediaId("00000000-0000-4000-8000-000000000010")
        val traversalId = MediaId("../outside")

        assertFailsWith<IllegalArgumentException> {
            locator.audioFileFor(traversalId, "mp3")
        }
        assertFailsWith<IllegalArgumentException> {
            locator.audioFileFor(validId, "exe")
        }
        assertFailsWith<IllegalArgumentException> {
            locator.audioFileFor(validId, "../mp3")
        }
        assertFailsWith<IllegalArgumentException> {
            locator.tempFileFor("../temp")
        }
    }

    @Test
    fun `resolveAudioFileとdeleteMediaFiles_パストラバーサルIDでは管理ディレクトリ外のファイルに触れない`() {
        val outsideFile = File(filesDir, "secret.mp3").apply { writeText("do-not-touch") }
        val siblingMediaFile = locator.audioFileFor(
            MediaId("00000000-0000-4000-8000-000000000099"),
            "mp3",
        ).apply { writeText("keep-me") }

        val traversalIds = listOf(
            MediaId("../secret"),
            MediaId("..\\secret"),
            MediaId(".."),
            MediaId("."),
            MediaId("00000000-0000-4000-8000-000000000099/../../secret"),
        )
        for (badId in traversalIds) {
            assertNull(locator.resolveAudioFile(badId))
            assertNull(locator.resolveAudioFile(badId, "audio/mpeg"))
            locator.deleteMediaFiles(badId)
        }

        assertTrue(outsideFile.exists())
        assertTrue(siblingMediaFile.exists())
        assertNotNull(locator.resolveAudioFile(MediaId("00000000-0000-4000-8000-000000000099")))
    }

    @Test
    fun `sanitizeDisplayName_パス区切りと制御文字を除去し200コードポイント上限を守る`() {
        assertEquals(
            "lesson01",
            DefaultLibraryRepository.sanitizeDisplayName("../../etc/passwd/lesson01.mp3"),
        )
        assertEquals(
            "lesson02",
            DefaultLibraryRepository.sanitizeDisplayName("C:\\Users\\evil\\..\\lesson02.m4a"),
        )
        assertEquals(
            "clean_title",
            DefaultLibraryRepository.sanitizeDisplayName("clean\u0000_\u0007title\u001F.opus"),
        )
        assertEquals("", DefaultLibraryRepository.sanitizeDisplayName("   \n\t  "))
        assertEquals("", DefaultLibraryRepository.sanitizeDisplayName(null))

        val longName = "あ".repeat(250) + ".flac"
        val sanitizedLong = DefaultLibraryRepository.sanitizeDisplayName(longName)
        assertEquals(200, sanitizedLong.codePoints().count().toInt())
    }
}
