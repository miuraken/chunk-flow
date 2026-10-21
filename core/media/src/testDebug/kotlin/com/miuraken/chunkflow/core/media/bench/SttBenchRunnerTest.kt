package com.miuraken.chunkflow.core.media.bench

import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriber
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriptionResult
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.SttEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** 音声認識の実機計測ツールの進め方（スキップ・ウォームアップ・記録）のテスト。 */
class SttBenchRunnerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val deviceState = object : LidBenchDeviceState {
        override fun thermalStatus(): Int = 0
        override fun thermalHeadroom(): Float = Float.NaN
        override fun isPowerSaveMode(): Boolean = false
    }

    /** 試した呼び出しを覚え、言語ごとのモデルの状態を返す偽物。 */
    private class FakeTranscriber(
        private val installed: MutableSet<Pair<String, SttEngine>>,
    ) : OnDeviceChunkTranscriber {
        val trials = ArrayList<Triple<String, LanguagePair?, OnDeviceTranscriptionEngine?>>()
        val downloadRequests = ArrayList<Pair<String, SttEngine>>()

        override fun isAvailable(): Boolean = true

        override suspend fun transcribeChunk(
            audioFile: File,
            startMs: Long,
            endMs: Long,
            languagePair: LanguagePair?,
            itemSttOverride: ItemSttOverride,
            engineByLanguage: Map<LanguageTag, SttEngine>,
        ): OnDeviceChunkTranscriptionResult? = null

        override suspend fun queryLanguageModelInfos(languages: List<LanguageTag>): List<OnDeviceLanguageModelInfo> = languages.map { language ->
            val extras = listOf(SttEngine.MOONSHINE_TINY, SttEngine.SHERPA_ONNX_WHISPER_SMALL).associateWith { engine ->
                if ((language.value to engine) in installed) OnDeviceLanguageModelStatus.INSTALLED else OnDeviceLanguageModelStatus.NOT_INSTALLED
            }
            OnDeviceLanguageModelInfo(language = language, extraEngineStatuses = extras)
        }

        override suspend fun requestSttModelDownload(language: LanguageTag, engine: SttEngine): Boolean {
            downloadRequests += language.value to engine
            installed += language.value to engine
            return true
        }

        override suspend fun transcribeChunkTrial(
            audioFile: File,
            startMs: Long,
            endMs: Long,
            languagePair: LanguagePair?,
            engineOverride: OnDeviceTranscriptionEngine?,
            audioSpeed: Float,
        ): OnDeviceChunkTranscriptionResult? {
            trials += Triple(audioFile.name, languagePair, engineOverride)
            return if (audioFile.name.startsWith("empty")) {
                OnDeviceChunkTranscriptionResult(text = "", language = languagePair?.target)
            } else {
                OnDeviceChunkTranscriptionResult(text = " こんにちは ", language = languagePair?.target, engineId = EngineId("fake"))
            }
        }

        override suspend fun detectChunkLanguage(
            audioFile: File,
            startMs: Long,
            endMs: Long,
            languagePair: LanguagePair,
        ): OnDeviceChunkTranscriptionResult = OnDeviceChunkTranscriptionResult(text = "", language = languagePair.base, confidence = 0.9f)
    }

    private fun clipFile(name: String): File {
        val dir = File(tempFolder.root, "clips").apply { mkdirs() }
        return SttBenchIoTest.writeWav(File(dir, name), sampleRate = 16_000, channels = 1, samples = 16_000)
    }

    private fun runner(
        transcriber: OnDeviceChunkTranscriber,
        config: SttBenchConfig,
        clips: List<SttBenchClip>,
        records: MutableList<String>,
    ): SttBenchRunner = SttBenchRunner(
        transcriber = transcriber,
        config = config,
        inputDir = tempFolder.root,
        clips = clips,
        output = { _, _ -> },
        records = { records += it },
        deviceState = deviceState,
        sleep = {},
    )

    @Test
    fun `run_使えないエンジンと言語の組はskipにし使える組はウォームアップしてから全クリップを測る`() = runTest {
        clipFile("ja1.wav")
        clipFile("empty_ja2.wav")
        clipFile("en1.wav")
        val transcriber = FakeTranscriber(installed = mutableSetOf("ja" to SttEngine.MOONSHINE_TINY))
        val records = ArrayList<String>()
        val clips = listOf(
            SttBenchClip("ja1", "ja", "clips/ja1.wav"),
            SttBenchClip("ja2", "ja", "clips/empty_ja2.wav"),
            SttBenchClip("en1", "en", "clips/en1.wav"),
        )

        runner(transcriber, SttBenchConfig(mode = SttBenchMode.RUN, manifestFileName = "m.tsv", engines = listOf(SttBenchEngine.MOONSHINE_TINY)), clips, records).run()

        // ウォームアップ 1 回 + 2 クリップ。英語はモデルがないので試さない
        assertEquals(listOf("ja1.wav", "ja1.wav", "empty_ja2.wav"), transcriber.trials.map { it.first })
        assertTrue(transcriber.trials.all { it.second == LanguagePair(LanguageTag("ja"), null) })
        assertTrue(transcriber.trials.all { it.third == OnDeviceTranscriptionEngine.MOONSHINE_TINY })
        assertEquals(1, records.count { it.startsWith("{\"type\":\"header\"") })
        assertEquals(1, records.count { it.startsWith("{\"type\":\"warmup\"") })
        assertTrue(records.any { it.startsWith("{\"type\":\"skip\",\"engine\":\"mtiny\",\"lang\":\"en\",\"status\":\"NOT_INSTALLED\"") })
        val results = records.filter { it.startsWith("{\"type\":\"result\"") }
        assertEquals(2, results.size)
        assertTrue(results[0].contains("\"clip\":\"ja1\"") && results[0].contains("\"status\":\"ok\"") && results[0].contains("\"text\":\"こんにちは\""))
        assertTrue(results[1].contains("\"clip\":\"ja2\"") && results[1].contains("\"status\":\"empty\""))
        assertTrue(records.any { it.startsWith("{\"type\":\"group\",\"engine\":\"mtiny\",\"lang\":\"ja\",\"clips\":2,\"ok\":1") })
        assertTrue(records.last().startsWith("{\"type\":\"footer\""))
    }

    @Test
    fun `run_読めないクリップはclipErrorにして測らない`() = runTest {
        clipFile("ja1.wav")
        File(tempFolder.root, "clips/broken.wav").writeText("not a wav")
        val transcriber = FakeTranscriber(installed = mutableSetOf("ja" to SttEngine.MOONSHINE_TINY))
        val records = ArrayList<String>()
        val clips = listOf(
            SttBenchClip("broken", "ja", "clips/broken.wav"),
            SttBenchClip("missing", "ja", "clips/missing.wav"),
            SttBenchClip("ja1", "ja", "clips/ja1.wav"),
        )
        val config = SttBenchConfig(mode = SttBenchMode.RUN, manifestFileName = "m.tsv", engines = listOf(SttBenchEngine.MOONSHINE_TINY), warmup = false)

        runner(transcriber, config, clips, records).run()

        assertEquals(listOf("ja1.wav"), transcriber.trials.map { it.first })
        assertEquals(2, records.count { it.startsWith("{\"type\":\"clipError\"") })
    }

    @Test
    fun `run_言語の絞り込みと言語ごとの上限を守る`() = runTest {
        listOf("ja1", "ja2", "ja3", "en1").forEach { clipFile("$it.wav") }
        val transcriber = FakeTranscriber(
            installed = mutableSetOf("ja" to SttEngine.MOONSHINE_TINY, "en" to SttEngine.MOONSHINE_TINY),
        )
        val records = ArrayList<String>()
        val clips = listOf("ja1", "ja2", "ja3").map { SttBenchClip(it, "ja", "clips/$it.wav") } + SttBenchClip("en1", "en", "clips/en1.wav")
        val config = SttBenchConfig(
            mode = SttBenchMode.RUN,
            manifestFileName = "m.tsv",
            languages = listOf("ja"),
            engines = listOf(SttBenchEngine.MOONSHINE_TINY),
            maxClipsPerLanguage = 2,
            warmup = false,
        )

        runner(transcriber, config, clips, records).run()

        assertEquals(listOf("ja1.wav", "ja2.wav"), transcriber.trials.map { it.first })
    }

    @Test
    fun `lid_2言語の判定結果を記録する`() = runTest {
        clipFile("c1.wav")
        val transcriber = FakeTranscriber(installed = mutableSetOf())
        val records = ArrayList<String>()
        val clips = listOf(SttBenchClip("c1", "ko", "clips/c1.wav", pair = "ko" to "ja"), SttBenchClip("c2", "ko", "clips/c1.wav"))

        runner(transcriber, SttBenchConfig(mode = SttBenchMode.LID, manifestFileName = "m.tsv"), clips, records).run()

        val lid = records.filter { it.startsWith("{\"type\":\"lid\"") }
        assertEquals(1, lid.size)
        assertTrue(lid[0].contains("\"clip\":\"c1\"") && lid[0].contains("\"pair\":\"ko,ja\"") && lid[0].contains("\"detected\":\"ja\""))
    }

    @Test
    fun `download_対応していて未取得の組だけを要求し使えるようになるまで待つ`() = runTest {
        val transcriber = FakeTranscriber(installed = mutableSetOf("ja" to SttEngine.MOONSHINE_TINY))
        val records = ArrayList<String>()
        val config = SttBenchConfig(
            mode = SttBenchMode.DOWNLOAD,
            languages = listOf("ja", "hi"),
            engines = listOf(SttBenchEngine.MOONSHINE_TINY, SttBenchEngine.WHISPER_SMALL),
        )

        runner(transcriber, config, emptyList(), records).run()

        // Moonshine はヒンディー語に対応していないので要求しない。日本語の Moonshine は取得済み
        assertEquals(
            listOf("ja" to SttEngine.SHERPA_ONNX_WHISPER_SMALL, "hi" to SttEngine.SHERPA_ONNX_WHISPER_SMALL),
            transcriber.downloadRequests,
        )
        assertEquals(2, records.count { it.startsWith("{\"type\":\"download\"") })
    }
}
