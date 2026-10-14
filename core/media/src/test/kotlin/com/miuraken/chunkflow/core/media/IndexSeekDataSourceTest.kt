package com.miuraken.chunkflow.core.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IndexSeekDataSourceTest {

    @Test
    fun `ID3v2直後のXing識別子をマスクしopenはLENGTH_UNSETを返し元ファイルは書き換えない`() {
        val tempDir = Files.createTempDirectory("cf_media_ds").toFile()
        try {
            val bytes = ByteArray(256) { 0x11 }
            // ID3v2 header (10 bytes + 20 bytes payload = 30 bytes; flags at byte 5 must be 0)
            bytes[0] = 'I'.code.toByte()
            bytes[1] = 'D'.code.toByte()
            bytes[2] = '3'.code.toByte()
            bytes[3] = 4
            bytes[4] = 0
            bytes[5] = 0
            bytes[6] = 0
            bytes[7] = 0
            bytes[8] = 0
            bytes[9] = 20
            val xingOffset = 66
            "Xing".encodeToByteArray().copyInto(bytes, xingOffset)

            val file = File(tempDir, "sample.mp3").apply { writeBytes(bytes) }
            val ds = IndexSeekDataSource.Factory(FileDataSource.Factory()).createDataSource()
            val length = ds.open(DataSpec(Uri.fromFile(file)))
            assertEquals(C.LENGTH_UNSET.toLong(), length)

            val readBuf = ByteArray(256)
            var totalRead = 0
            while (totalRead < readBuf.size) {
                val n = ds.read(readBuf, totalRead, 37.coerceAtMost(readBuf.size - totalRead))
                if (n < 0) break
                totalRead += n
            }
            ds.close()

            assertEquals(256, totalRead)
            for (i in xingOffset until xingOffset + 4) {
                assertEquals(0, readBuf[i].toInt())
            }
            assertEquals(0x11, readBuf[xingOffset - 1].toInt())
            assertEquals(0x11, readBuf[xingOffset + 4].toInt())
            // 元ファイルが変更されていないことを確認
            assertContentEquals(bytes, file.readBytes())
            assertTrue(!IndexSeekDataSource.findHeaderTagRangeInBytes(bytes).isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
