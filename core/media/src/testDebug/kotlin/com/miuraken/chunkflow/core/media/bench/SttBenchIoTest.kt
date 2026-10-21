package com.miuraken.chunkflow.core.media.bench

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** 計測結果の JSON の組み立てと WAV のヘッダの読み込みのテスト。 */
class SttBenchIoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `line_文字列を逃がし数値と真偽値とnullと配列をそのまま書く`() {
        val line = SttBenchJson.line(
            "text" to "引用\"と\\と\n改行\t",
            "ms" to 12.3456,
            "count" to 3,
            "ok" to true,
            "none" to null,
            "nan" to Double.NaN,
            "list" to listOf("a", 1),
            "ctrl" to "\u0001",
        )

        assertEquals(
            "{\"text\":\"引用\\\"と\\\\と\\n改行\\t\",\"ms\":12.346,\"count\":3,\"ok\":true,\"none\":null,\"nan\":null," +
                "\"list\":[\"a\",1],\"ctrl\":\"\\u0001\"}",
            line,
        )
    }

    @Test
    fun `read_16kHzモノラルのWAVの長さを読み途中のチャンクを読み飛ばす`() {
        val file = writeWav(tempFolder.newFile("a.wav"), sampleRate = 16_000, channels = 1, samples = 24_000, extraChunk = true)

        val info = assertNotNull(SttBenchWav.read(file))

        assertEquals(16_000, info.sampleRateHz)
        assertEquals(1, info.channels)
        assertEquals(16, info.bitsPerSample)
        assertEquals(1_500L, info.durationMs)
    }

    @Test
    fun `read_WAVでない・壊れたファイルはnull`() {
        val notWav = tempFolder.newFile("b.wav").apply { writeText("hello world, not a wav file") }
        val truncated = tempFolder.newFile("c.wav").apply { writeBytes("RIFF".toByteArray()) }

        assertNull(SttBenchWav.read(notWav))
        assertNull(SttBenchWav.read(truncated))
        assertNull(SttBenchWav.read(File(tempFolder.root, "missing.wav")))
    }

    companion object {
        /** 無音の 16-bit PCM の WAV を書く（[extraChunk] なら fmt と data の間に LIST チャンクを入れる）。 */
        fun writeWav(file: File, sampleRate: Int, channels: Int, samples: Int, extraChunk: Boolean = false): File {
            val dataBytes = samples * channels * 2
            val list = if (extraChunk) "LIST".toByteArray() + le32(5) + "INFOx".toByteArray() + byteArrayOf(0) else ByteArray(0)
            val fmt = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(sampleRate * channels * 2)
                putShort((channels * 2).toShort())
                putShort(16)
            }.array()
            val body = "WAVE".toByteArray() + fmt + list + "data".toByteArray() + le32(dataBytes) + ByteArray(dataBytes)
            file.writeBytes("RIFF".toByteArray() + le32(body.size) + body)
            return file
        }

        private fun le32(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
}
