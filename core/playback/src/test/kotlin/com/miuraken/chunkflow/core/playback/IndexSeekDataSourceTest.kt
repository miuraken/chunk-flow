package com.miuraken.chunkflow.core.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class IndexSeekDataSourceTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = File(RuntimeEnvironment.getApplication().cacheDir, "index-seek-test").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `openとread_ID3v2直後のXing識別子を0でマスクしLENGTH_UNSETを返し元ファイルは変更しない`() {
        val original = ByteArray(128) { it.toByte() }
        // ID3v2 header (10 bytes + 10 bytes payload = 20 bytes)
        original[0] = 'I'.code.toByte()
        original[1] = 'D'.code.toByte()
        original[2] = '3'.code.toByte()
        original[3] = 4
        original[4] = 0
        original[5] = 0
        original[6] = 0
        original[7] = 0
        original[8] = 0
        original[9] = 10
        val tagOffset = 20 + 36
        "Xing".encodeToByteArray().copyInto(original, tagOffset)

        val file = File(tempDir, "xing.mp3").apply { writeBytes(original) }
        val dataSource = IndexSeekDataSource.Factory(FileDataSource.Factory()).createDataSource()

        val length = dataSource.open(DataSpec(Uri.fromFile(file)))
        assertEquals(C.LENGTH_UNSET.toLong(), length)

        val readBuffer = ByteArray(original.size)
        var totalRead = 0
        while (totalRead < readBuffer.size) {
            val r = dataSource.read(readBuffer, totalRead, 7.coerceAtMost(readBuffer.size - totalRead))
            if (r == C.RESULT_END_OF_INPUT) break
            totalRead += r
        }
        dataSource.close()

        assertEquals(original.size, totalRead)
        for (i in original.indices) {
            val expected = if (i in tagOffset until (tagOffset + 4)) 0.toByte() else original[i]
            assertEquals(expected, readBuffer[i], "Byte mismatch at index $i")
        }
        // 元ファイル自体は一切書き換わっていないこと（ADR 0006）
        assertContentEquals(original, file.readBytes())
    }

    @Test
    fun `openとread_InfoおよびVBRI識別子も0でマスクする`() {
        for (tag in listOf("Info", "VBRI")) {
            val original = ByteArray(100) { 0x55 }
            val tagOffset = 36
            tag.encodeToByteArray().copyInto(original, tagOffset)
            val file = File(tempDir, "${tag.lowercase()}.mp3").apply { writeBytes(original) }

            val dataSource = IndexSeekDataSource(FileDataSource())
            val length = dataSource.open(DataSpec(Uri.fromFile(file)))
            assertEquals(C.LENGTH_UNSET.toLong(), length)

            val out = ByteArray(original.size)
            val read = dataSource.read(out, 0, out.size)
            dataSource.close()

            assertEquals(original.size, read)
            assertTrue((tagOffset until tagOffset + 4).all { out[it] == 0.toByte() })
            assertEquals(0x55.toByte(), out[tagOffset - 1])
            assertEquals(0x55.toByte(), out[tagOffset + 4])
        }
    }

    @Test
    fun `openとread_先頭フレーム範囲外の文字列はマスクしない`() {
        val original = ByteArray(4_096) { 0x11 }
        val farOffset = 3_000
        "Xing".encodeToByteArray().copyInto(original, farOffset)
        val file = File(tempDir, "far.mp3").apply { writeBytes(original) }

        val dataSource = IndexSeekDataSource(FileDataSource())
        dataSource.open(DataSpec(Uri.fromFile(file)))
        val out = ByteArray(original.size)
        var pos = 0
        while (pos < out.size) {
            val r = dataSource.read(out, pos, out.size - pos)
            if (r == C.RESULT_END_OF_INPUT) break
            pos += r
        }
        dataSource.close()

        assertContentEquals(original, out)
    }
}
