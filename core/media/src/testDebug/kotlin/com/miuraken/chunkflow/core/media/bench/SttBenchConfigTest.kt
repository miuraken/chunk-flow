package com.miuraken.chunkflow.core.media.bench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 音声認識の実機計測ツールの引数とクリップ一覧の読み込みのテスト。 */
class SttBenchConfigTest {

    @Test
    fun `parse_runとmanifestだけなら既定値で作る`() {
        val parsed = SttBenchConfig.parse(mapOf("mode" to "run", "manifest" to "real.tsv"))

        val config = assertIs<SttBenchParsed.Ok<SttBenchConfig>>(parsed).value
        assertEquals(SttBenchMode.RUN, config.mode)
        assertEquals("real.tsv", config.manifestFileName)
        assertEquals(SttBenchEngine.DEFAULT, config.engines)
        assertEquals(emptyList(), config.languages)
        assertEquals(0, config.maxClipsPerLanguage)
        assertTrue(config.warmup)
        assertEquals(SttBenchConfig.DEFAULT_COOLDOWN_SEC, config.cooldownSec)
        assertEquals(SttBenchConfig.DEFAULT_TAG, config.tag)
    }

    @Test
    fun `parse_エンジンは指定の順にし数値と真偽値は文字列でも読む`() {
        val parsed = SttBenchConfig.parse(
            mapOf(
                "mode" to "run",
                "manifest" to "tts.tsv",
                "engines" to "wsmall, mtiny,sr",
                "langs" to "ja,en",
                "maxClips" to "12",
                "warmup" to "false",
                "cooldownSec" to 0,
                "tag" to "tts_1",
            ),
        )

        val config = assertIs<SttBenchParsed.Ok<SttBenchConfig>>(parsed).value
        assertEquals(listOf(SttBenchEngine.WHISPER_SMALL, SttBenchEngine.MOONSHINE_TINY, SttBenchEngine.SPEECH_RECOGNIZER), config.engines)
        assertEquals(listOf("ja", "en"), config.languages)
        assertEquals(12, config.maxClipsPerLanguage)
        assertFalse(config.warmup)
        assertEquals(0, config.cooldownSec)
        assertEquals("tts_1", config.tag)
    }

    @Test
    fun `parse_正しくない引数は理由を返す`() {
        assertIs<SttBenchParsed.Invalid>(SttBenchConfig.parse(emptyMap()))
        assertIs<SttBenchParsed.Invalid>(SttBenchConfig.parse(mapOf("mode" to "fly")))
        assertIs<SttBenchParsed.Invalid>(SttBenchConfig.parse(mapOf("mode" to "run")))
        assertIs<SttBenchParsed.Invalid>(SttBenchConfig.parse(mapOf("mode" to "status")))
        assertIs<SttBenchParsed.Invalid>(SttBenchConfig.parse(mapOf("mode" to "run", "manifest" to "../x.tsv")))
        assertIs<SttBenchParsed.Invalid>(SttBenchConfig.parse(mapOf("mode" to "run", "manifest" to "a.tsv", "engines" to "mtiny,mtiny")))
        assertIs<SttBenchParsed.Invalid>(SttBenchConfig.parse(mapOf("mode" to "run", "manifest" to "a.tsv", "engines" to "gpt")))
        assertIs<SttBenchParsed.Invalid>(SttBenchConfig.parse(mapOf("mode" to "status", "langs" to "ja,j@")))
        assertIs<SttBenchParsed.Invalid>(SttBenchConfig.parse(mapOf("mode" to "run", "manifest" to "a.tsv", "warmup" to "maybe")))
        assertIs<SttBenchParsed.Invalid>(SttBenchConfig.parse(mapOf("mode" to "run", "manifest" to "a.tsv", "tag" to "a/b")))
        assertIs<SttBenchParsed.Invalid>(SttBenchConfig.parse(mapOf("mode" to "run", "manifest" to "a.tsv", "cooldownSec" to -1)))
    }

    @Test
    fun `manifest_列名の順は自由でコメントと空行は読み飛ばしcontext列も読める`() {
        val text = "\uFEFF# 実教材のクリップ\nfile\tid\tlang\tpair\tsource\tcontext\n\nclips/a.wav\tko_001\tko\tko,ja\tKorean_mainichi\tBOTH::안녕||안녕\r\n" +
            "# 途中のコメント\nclips/b.wav\tja_001\tja\t\tKorean_mainichi\t\n"

        val clips = assertIs<SttBenchParsed.Ok<List<SttBenchClip>>>(SttBenchManifest.parse(text)).value

        assertEquals(2, clips.size)
        assertEquals(
            SttBenchClip(
                id = "ko_001",
                language = "ko",
                relativePath = "clips/a.wav",
                pair = "ko" to "ja",
                context = "BOTH::안녕||안녕",
            ),
            clips[0],
        )
        assertEquals("ja_001", clips[1].id)
        assertNull(clips[1].pair)
        assertNull(clips[1].context)
    }

    @Test
    fun `manifest_正しくない行は理由を返す`() {
        val header = "id\tlang\tfile\tpair\n"
        assertIs<SttBenchParsed.Invalid>(SttBenchManifest.parse(""))
        assertIs<SttBenchParsed.Invalid>(SttBenchManifest.parse("id\tfile\n"))
        assertIs<SttBenchParsed.Invalid>(SttBenchManifest.parse(header))
        assertIs<SttBenchParsed.Invalid>(SttBenchManifest.parse(header + "a\tja\t../a.wav\t\n"))
        assertIs<SttBenchParsed.Invalid>(SttBenchManifest.parse(header + "a\tja\t/sdcard/a.wav\t\n"))
        assertIs<SttBenchParsed.Invalid>(SttBenchManifest.parse(header + "a\tja\ta.wav\t\na\tja\tb.wav\t\n"))
        assertIs<SttBenchParsed.Invalid>(SttBenchManifest.parse(header + "a\tjapanese!\ta.wav\t\n"))
        assertIs<SttBenchParsed.Invalid>(SttBenchManifest.parse(header + "a\tja\ta.wav\tja\n"))
        assertIs<SttBenchParsed.Invalid>(SttBenchManifest.parse(header + "a\tja\ta.wav\tja,ja\n"))
    }

    @Test
    fun `isSafeRelativePath_親ディレクトリや深すぎるパスを拒む`() {
        assertTrue(SttBenchManifest.isSafeRelativePath("clips/ja_001.wav"))
        assertTrue(SttBenchManifest.isSafeRelativePath("a.wav"))
        assertFalse(SttBenchManifest.isSafeRelativePath("clips/../a.wav"))
        assertFalse(SttBenchManifest.isSafeRelativePath("./a.wav"))
        assertFalse(SttBenchManifest.isSafeRelativePath("a/b/c/d.wav"))
        assertFalse(SttBenchManifest.isSafeRelativePath("clips//a.wav"))
        assertFalse(SttBenchManifest.isSafeRelativePath(""))
    }
}
