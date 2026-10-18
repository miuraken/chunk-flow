package com.miuraken.chunkflow.core.media.bench

import com.miuraken.chunkflow.core.media.ChunkTimeRange
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** 実機計測ツールの引数と入力の読み込みのテスト。 */
class LidBenchConfigTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `parse_必須の引数だけなら既定値で作る`() {
        val parsed = LidBenchConfig.parse(mapOf("name" to "Hindi_1_Lesson_16", "target" to "hi", "base" to "en"))

        val config = assertIs<LidBenchParsed.Ok<LidBenchConfig>>(parsed).value
        assertEquals("Hindi_1_Lesson_16.mp3", config.audioFileName)
        assertEquals("Hindi_1_Lesson_16.chunks.json", config.chunksFileName)
        assertEquals(60, config.maxChunks)
        assertEquals(6, config.warmupChunks)
        assertEquals(30, config.cooldownSec)
        assertEquals(300, config.thermalWaitSec)
        assertEquals(listOf(LidBenchEngine.KOTLIN, LidBenchEngine.ONNX), config.engines)
        assertEquals(listOf(LidBenchMode.SEQUENTIAL, LidBenchMode.PARALLEL3), config.modes)
    }

    @Test
    fun `parse_数値は文字列でも受け付けエンジンと進め方は指定の順にする`() {
        val parsed = LidBenchConfig.parse(
            mapOf(
                "name" to "Korean_lesson_ja",
                "target" to "ko",
                "base" to "ja",
                "maxChunks" to "0",
                "cooldownSec" to 5,
                "engines" to "onnx, kotlin",
                "modes" to "par3",
                "audio" to "Korean_lesson_ja.m4a",
            ),
        )

        val config = assertIs<LidBenchParsed.Ok<LidBenchConfig>>(parsed).value
        assertEquals(0, config.maxChunks)
        assertEquals(5, config.cooldownSec)
        assertEquals(listOf(LidBenchEngine.ONNX, LidBenchEngine.KOTLIN), config.engines)
        assertEquals(listOf(LidBenchMode.PARALLEL3), config.modes)
        assertEquals("Korean_lesson_ja.m4a", config.audioFileName)
    }

    @Test
    fun `parse_正しくない引数は理由を返す`() {
        val base = mapOf("name" to "x", "target" to "hi", "base" to "en")
        val invalidArgs = listOf(
            base - "name",
            base + ("name" to "../x"),
            base + ("base" to "HI"),
            base + ("target" to "hindi!"),
            base + ("maxChunks" to -1),
            base + ("maxChunks" to "abc"),
            base + ("engines" to "kotlin,tflite"),
            base + ("modes" to "seq,seq"),
            base + ("audio" to "a/b.mp3"),
        )
        for (args in invalidArgs) {
            assertIs<LidBenchParsed.Invalid>(LidBenchConfig.parse(args), args.toString())
        }
    }

    @Test
    fun `parseChunks_整数の組の配列を読む`() {
        val parsed = LidBenchChunkList.parse("\uFEFF[[1950,5230], [5390, 13300]\n]")

        val chunks = assertIs<LidBenchParsed.Ok<List<ChunkTimeRange>>>(parsed).value
        assertEquals(listOf(ChunkTimeRange(1_950L, 5_230L), ChunkTimeRange(5_390L, 13_300L)), chunks)
    }

    @Test
    fun `parseChunks_形が違う・区間が逆・余分な文字がある場合はエラーにする`() {
        val invalidTexts = listOf(
            "",
            "{}",
            "[]",
            "[[1,2],[3]]",
            "[[5,1]]",
            "[[1,2]] x",
            "[[1.5,2]]",
            "[[-1,2]]",
            "[[1,2],]",
            "[[1,99999999999999]]",
        )
        for (text in invalidTexts) {
            assertIs<LidBenchParsed.Invalid>(LidBenchChunkList.parse(text), text)
        }
    }

    @Test
    fun `load_チャンクのJSONがあるディレクトリから読み先頭からmaxChunks件にする`() {
        val empty = tempFolder.newFolder("external")
        val dir = tempFolder.newFolder("internal")
        File(dir, "lesson.chunks.json").writeText("[[0,1000],[1500,2500],[3000,4000]]")
        File(dir, "lesson.mp3").writeBytes(byteArrayOf(1, 2, 3))
        val config = LidBenchConfig(name = "lesson", targetTag = "hi", baseTag = "en", audioFileName = "lesson.mp3", maxChunks = 2)

        val input = assertIs<LidBenchParsed.Ok<LidBenchInput>>(LidBenchInputLoader.load(listOf(empty, dir), config)).value

        assertEquals(File(dir, "lesson.mp3"), input.audioFile)
        assertEquals(listOf(ChunkTimeRange(0L, 1_000L), ChunkTimeRange(1_500L, 2_500L)), input.chunks)
        assertEquals(3, input.totalChunks)
    }

    @Test
    fun `load_音声かチャンクのJSONがなければ理由を返す`() {
        val dir = tempFolder.newFolder("bench")
        val config = LidBenchConfig(name = "lesson", targetTag = "hi", baseTag = "en", audioFileName = "lesson.mp3")

        val noChunks = assertIs<LidBenchParsed.Invalid>(LidBenchInputLoader.load(listOf(dir), config))
        assertTrue(noChunks.reason.contains("lesson.chunks.json"), noChunks.reason)

        File(dir, "lesson.chunks.json").writeText("[[0,1000]]")
        val noAudio = assertIs<LidBenchParsed.Invalid>(LidBenchInputLoader.load(listOf(dir), config))
        assertTrue(noAudio.reason.contains("lesson.mp3"), noAudio.reason)
    }
}
