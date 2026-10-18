package com.miuraken.chunkflow.core.media

import android.media.MediaFormat
import com.miuraken.chunkflow.core.audio.SampleSink
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 一括言語判定の切り出しの方式（今 / 並列 / 順に流す）のテスト。
 *
 * 偽の抽出器とデコーダ（[FakeMediaSessions.kt]）で本番の [MediaCodecAudioStreamDecoder] を動かし、切り出した PCM と判定が
 * 今の方式と同じこと、資源を必ず閉じること、順に流す切り出しの窓の扱い（間の読み飛ばし・待ち行列の上限・ファイルの終わり）を確かめる。
 */
@RunWith(RobolectricTestRunner::class)
class LidExtractionMethodsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val context get() = RuntimeEnvironment.getApplication()

    private val pair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja"))

    /** 偽の音声と、開いた抽出器・作った MediaCodec の記録。 */
    private class Media(
        private val format: MediaFormat,
        private val packets: List<FakeMediaPacket>,
        private val codecFactory: (index: Int) -> CodecSession,
    ) {
        val extractors: MutableList<FakeMediaExtractor> = Collections.synchronizedList(ArrayList())
        val codecs: MutableList<CodecSession> = Collections.synchronizedList(ArrayList())
        private val codecCount = AtomicInteger()

        val decoder = MediaCodecAudioStreamDecoder(
            extractorOpener = { FakeMediaExtractor(format, packets).also { extractors += it } },
            codecCreator = { _, _ -> codecFactory(codecCount.getAndIncrement()).also { codecs += it } },
        )

        fun allCodecsClosed(): Boolean = codecs.all { codec ->
            when (codec) {
                is FakeRawCodec -> codec.closed
                is FakeBatchMp3Codec -> codec.closed
                else -> true
            }
        }
    }

    private class Outcome(
        val results: List<OnDeviceChunkTranscriptionResult?>,
        val pcm: Map<Int, ShortArray>,
        val transcriber: AndroidSpeechRecognizerChunkTranscriber,
    ) {
        val timings: LidExtractionTimingsSnapshot get() = transcriber.languageOnlyExtractionTimings()
    }

    private fun audioFile(): File = tempFolder.newFile().apply { writeBytes(ByteArray(64) { 1 }) }

    private fun rawMedia(durationMs: Long = 40_000L, trimStartSamples: Int = 529): Media = Media(
        format = FakeMedia.rawFormat(durationMs = durationMs),
        packets = FakeMedia.rawPackets(durationMs),
        codecFactory = { FakeRawCodec(sampleRate = 44_100, trimStartSamples = trimStartSamples) },
    )

    private fun transcriber(
        decoder: AudioStreamDecoder,
        method: LidExtractionMethod,
        pcm: MutableMap<Int, ShortArray>,
        options: StreamingExtractionOptions = StreamingExtractionOptions(),
        analyzer: (ShortArray, String, String) -> LanguagePairAnalysis? = ::analyze,
    ) = AndroidSpeechRecognizerChunkTranscriber(
        context = context,
        audioStreamDecoder = decoder,
        onDeviceAvailabilityChecker = { true },
        standardAvailabilityChecker = { true },
        languageAnalyzer = analyzer,
        languageOnlyExtractionMethod = method,
        streamingExtractionOptions = options,
        languageOnlyPcmObserver = { index, shorts -> pcm[index] = shorts.copyOf() },
    )

    private suspend fun detect(
        decoder: AudioStreamDecoder,
        method: LidExtractionMethod,
        file: File,
        chunks: List<ChunkTimeRange>,
        parallelism: Int = 3,
        options: StreamingExtractionOptions = StreamingExtractionOptions(),
        onEachResult: (Int) -> Unit = {},
    ): Outcome {
        val pcm = ConcurrentHashMap<Int, ShortArray>()
        val transcriber = transcriber(decoder, method, pcm, options)
        val results = arrayOfNulls<OnDeviceChunkTranscriptionResult>(chunks.size)
        val order = mutableListOf<Int>()
        val executor = Executors.newFixedThreadPool(3)
        try {
            transcriber.detectChunkLanguages(file, chunks, pair, parallelism, executor.asCoroutineDispatcher()) { index, result ->
                order += index
                results[index] = result
                onEachResult(index)
            }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(chunks.indices.toList(), order, "結果はチャンクの順に 1 回ずつ届く")
        return Outcome(results.toList(), pcm, transcriber)
    }

    private fun assertSamePcmAndResults(expected: Outcome, actual: Outcome, label: String) {
        assertEquals(expected.pcm.keys, actual.pcm.keys, "$label: PCM を切り出せたチャンク")
        for ((index, shorts) in expected.pcm) {
            assertContentEquals(shorts, actual.pcm[index], "$label: チャンク $index の PCM")
        }
        assertEquals(expected.results, actual.results, "$label: 判定")
    }

    @Test
    fun `並列の切り出し_PCMと判定が今の方式と同じで追加の抽出器とMediaCodecをすべて閉じる`() = runTest {
        val file = audioFile()
        val current = detect(rawMedia().decoder, LidExtractionMethod.CURRENT, file, CHUNKS)
        val media = rawMedia()
        val parallel = detect(media.decoder, LidExtractionMethod.PARALLEL, file, CHUNKS)

        assertEquals(CHUNKS.size, current.pcm.size)
        assertSamePcmAndResults(current, parallel, "並列")
        assertEquals(LidExtractionMethod.PARALLEL, parallel.transcriber.lastLanguageOnlyExtractionMethod)
        // ワーカーごとのレーンが独立した抽出器を開き、終わったら閉じる（共有の抽出器は使わない）
        assertTrue(media.extractors.size in 1..3, "抽出器 ${media.extractors.size} 個")
        assertTrue(media.extractors.all { it.closed })
        assertTrue(media.allCodecsClosed())
        val timings = parallel.timings
        assertEquals(CHUNKS.size.toLong(), timings.extractions)
        assertEquals(CHUNKS.size.toLong(), timings.codecCreates)
        assertEquals(0L, timings.fallbacks)
        assertTrue(timings.extractNanos > 0L && timings.decodeLoopNanos > 0L && timings.resampleNanos > 0L)
    }

    @Test
    fun `並列の切り出し_レーンでMediaCodecを作れなければ今の方式で切り出し直し判定は同じ`() = runTest {
        val file = audioFile()
        val current = detect(rawMedia().decoder, LidExtractionMethod.CURRENT, file, CHUNKS)
        // 最初の 1 回は資源不足で作れない（どれか 1 つのレーンの最初の切り出しが失敗する）。
        // 2 回以上にすると、失敗したレーンの切り出し直しが 2 回目に当たるかどうかがスレッドの順番で変わるので 1 回にする
        val media = Media(FakeMedia.rawFormat(durationMs = 40_000L), FakeMedia.rawPackets(40_000L)) { index ->
            if (index == 0) throw IllegalStateException("資源不足（テスト）") else FakeRawCodec(sampleRate = 44_100, trimStartSamples = 529)
        }
        val parallel = detect(media.decoder, LidExtractionMethod.PARALLEL, file, CHUNKS)

        assertSamePcmAndResults(current, parallel, "並列（作れない MediaCodec あり）")
        assertTrue(parallel.timings.fallbacks >= 1L, "今の方式で切り出した ${parallel.timings.fallbacks} 件")
        assertTrue(parallel.transcriber.recentDiagnostics.value.any { it.message.startsWith("並列の切り出しに失敗したため") })
        // 今の方式で使った共有の抽出器のほかは閉じている。解放を求めれば共有の抽出器も閉じる
        assertTrue(media.extractors.count { !it.closed } <= 1)
        parallel.transcriber.releaseIdleResources()
        assertTrue(media.extractors.all { it.closed })
        assertTrue(media.allCodecsClosed())
    }

    @Test
    fun `並列の切り出し_途中でキャンセルしても抽出器とMediaCodecを閉じる`() = runTest {
        val media = rawMedia()
        assertFailsWith<CancellationException> {
            detect(media.decoder, LidExtractionMethod.PARALLEL, audioFile(), CHUNKS) { index ->
                if (index == 2) throw CancellationException("利用者が止めた（テスト）")
            }
        }
        assertTrue(media.extractors.isNotEmpty())
        assertTrue(media.extractors.all { it.closed })
        assertTrue(media.allCodecsClosed())
    }

    @Test
    fun `順に流す切り出し_セッションの先頭で削られる遅延があってもPCMと判定が今の方式と同じ`() = runTest {
        val file = audioFile()
        val current = detect(rawMedia().decoder, LidExtractionMethod.CURRENT, file, CHUNKS)
        for (parallelism in listOf(1, 3)) {
            val media = rawMedia()
            val streaming = detect(media.decoder, LidExtractionMethod.STREAMING, file, CHUNKS, parallelism = parallelism)

            assertSamePcmAndResults(current, streaming, "順に流す（$parallelism 並列）")
            assertEquals(LidExtractionMethod.STREAMING, streaming.transcriber.lastLanguageOnlyExtractionMethod)
            val timings = streaming.timings
            // 12 個の窓を 1 つの MediaCodec で続けて切り出す
            assertEquals(1L, timings.codecCreates)
            assertEquals(1L, timings.streamRuns)
            assertEquals(CHUNKS.size.toLong(), timings.streamWindows)
            assertEquals(0L, timings.fallbacks)
            assertEquals(1, media.extractors.size)
            assertTrue(media.extractors.all { it.closed })
            assertTrue(media.allCodecsClosed())
        }
    }

    @Test
    fun `順に流す切り出し_MP3をまとめて渡してもPCMは今の方式と同じでrunの終わりに長さを確かめる`() = runTest {
        val file = audioFile()
        val packets = FakeMedia.mp3Packets(count = 1_400)
        val chunks = CHUNKS.filter { it.endMs <= 35_000L }
        val currentMedia = Media(FakeMedia.mp3Format(), packets) { FakeBatchMp3Codec("c2.android.mp3.decoder") }
        val current = detect(currentMedia.decoder, LidExtractionMethod.CURRENT, file, chunks)
        val media = Media(FakeMedia.mp3Format(), packets) { FakeBatchMp3Codec("c2.android.mp3.decoder") }
        val options = StreamingExtractionOptions(maxRunWindows = 4)
        val streaming = detect(media.decoder, LidExtractionMethod.STREAMING, file, chunks, options = options)

        assertSamePcmAndResults(current, streaming, "順に流す（MP3）")
        // 全体をデコードするときと同じく 128 フレームまでまとめて渡す（今の方式のチャンクの切り出しは 16 まで）
        val framesPerInput = media.codecs.filterIsInstance<FakeBatchMp3Codec>().flatMap { it.framesPerInput }
        assertTrue(framesPerInput.max() > MediaCodecAudioStreamDecoder.MP3_BATCH_FRAMES_RANGE_DECODE, "framesPerInput=$framesPerInput")
        assertEquals((chunks.size + 3) / 4L, streaming.timings.streamRuns)
        assertEquals(0L, streaming.timings.fallbacks)
        assertTrue(media.decoder.isMp3BatchingSupported("c2.android.mp3.decoder"))
        assertTrue(media.extractors.all { it.closed })
        assertTrue(media.allCodecsClosed())
    }

    @Test
    fun `順に流す切り出し_まとめて渡した出力が足りなければそのrunを今の方式で切り出し直し以後はまとめない`() = runTest {
        val file = audioFile()
        val packets = FakeMedia.mp3Packets(count = 1_400)
        val chunks = CHUNKS.filter { it.endMs <= 35_000L }
        // 1 フレームずつ渡すデコーダ（名前が標準と違う）での今の方式を正解にする
        val reference = detect(
            Media(FakeMedia.mp3Format(), packets) { FakeBatchMp3Codec("c2.vendor.mp3.decoder") }.decoder,
            LidExtractionMethod.CURRENT,
            file,
            chunks,
        )
        // 標準の名前だが、まとめて渡すと先頭のフレームしか出さない
        val media = Media(FakeMedia.mp3Format(), packets) { FakeBatchMp3Codec("c2.android.mp3.decoder", dropAllButFirstFrame = true) }
        val streaming = detect(media.decoder, LidExtractionMethod.STREAMING, file, chunks, options = StreamingExtractionOptions(maxRunWindows = 4))

        assertSamePcmAndResults(reference, streaming, "順に流す（まとめ入れに失敗）")
        // 最初の run（4 窓）の窓だけを今の方式で切り出し直し、以後はまとめずに順に流す
        assertEquals(4L, streaming.timings.fallbacks)
        assertFalse(media.decoder.isMp3BatchingSupported("c2.android.mp3.decoder"))
        streaming.transcriber.releaseIdleResources()
        assertTrue(media.extractors.all { it.closed })
        assertTrue(media.allCodecsClosed())
    }

    @Test
    fun `順に流す切り出し_大きく空いた間はデコードせずに読み飛ばしてrunを分ける`() = runTest {
        val file = audioFile()
        val chunks = listOf(
            ChunkTimeRange(200L, 2_600L),
            ChunkTimeRange(2_900L, 4_100L),
            ChunkTimeRange(4_300L, 6_000L),
            ChunkTimeRange(27_000L, 29_000L),
            ChunkTimeRange(29_300L, 31_000L),
            ChunkTimeRange(31_500L, 33_000L),
        )
        val current = detect(rawMedia().decoder, LidExtractionMethod.CURRENT, file, chunks)
        val media = rawMedia()
        val streaming = detect(media.decoder, LidExtractionMethod.STREAMING, file, chunks)

        assertSamePcmAndResults(current, streaming, "順に流す（間あり）")
        val timings = streaming.timings
        assertEquals(2L, timings.streamRuns)
        assertEquals(2L, timings.codecCreates)
        // 6〜26.5 秒はデコードしていない（デコードしたのは 2 つの run の分だけ）
        val decodedSec = timings.decodedNativeSamples / 44_100.0
        assertTrue(decodedSec < 15.0, "decodedSec=$decodedSec")
        assertTrue(media.extractors.all { it.closed })
    }

    @Test
    fun `順に流す切り出し_まばらなときと時刻の順でないときは並列の切り出しにする`() = runTest {
        val file = audioFile()
        val sparse = listOf(ChunkTimeRange(1_000L, 2_000L), ChunkTimeRange(15_000L, 16_000L), ChunkTimeRange(30_000L, 31_000L))
        val sparseOutcome = detect(rawMedia().decoder, LidExtractionMethod.STREAMING, file, sparse)
        assertEquals(LidExtractionMethod.PARALLEL, sparseOutcome.transcriber.lastLanguageOnlyExtractionMethod)
        assertTrue(sparseOutcome.transcriber.recentDiagnostics.value.any { it.message == AndroidSpeechRecognizerChunkTranscriber.STREAMING_SPARSE_MESSAGE })

        val unsorted = listOf(ChunkTimeRange(5_000L, 6_000L), ChunkTimeRange(1_000L, 2_000L), ChunkTimeRange(6_500L, 7_000L))
        val current = detect(rawMedia().decoder, LidExtractionMethod.CURRENT, file, unsorted)
        val unsortedOutcome = detect(rawMedia().decoder, LidExtractionMethod.STREAMING, file, unsorted)
        assertEquals(LidExtractionMethod.PARALLEL, unsortedOutcome.transcriber.lastLanguageOnlyExtractionMethod)
        assertTrue(unsortedOutcome.transcriber.recentDiagnostics.value.any { it.message == AndroidSpeechRecognizerChunkTranscriber.STREAMING_UNSORTED_MESSAGE })
        assertSamePcmAndResults(current, unsortedOutcome, "時刻の順でない")
    }

    @Test
    fun `順に流す切り出し_ファイルの終わりにかかる窓は今の方式と同じく書けた分を返す`() = runTest {
        val file = audioFile()
        val chunks = listOf(ChunkTimeRange(15_000L, 17_500L), ChunkTimeRange(17_800L, 19_000L), ChunkTimeRange(19_300L, 22_000L))
        val current = detect(rawMedia(durationMs = 20_000L).decoder, LidExtractionMethod.CURRENT, file, chunks)
        val media = rawMedia(durationMs = 20_000L)
        val streaming = detect(media.decoder, LidExtractionMethod.STREAMING, file, chunks)

        assertSamePcmAndResults(current, streaming, "順に流す（ファイルの終わり）")
        // 最後の窓は 19.3〜20.0 秒（とリサンプラの末尾）だけ
        val last = assertNotNull(streaming.pcm[2])
        assertTrue(last.size in 10_800..11_400, "size=${last.size}")
        assertTrue(media.extractors.all { it.closed })
        assertTrue(media.allCodecsClosed())
    }

    @Test
    fun `順に流す切り出し_推論が止まっている間は待ち行列の上限より先に切り出さない`() = runTest {
        val media = rawMedia()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pcm = ConcurrentHashMap<Int, ShortArray>()
        val transcriber = transcriber(
            decoder = media.decoder,
            method = LidExtractionMethod.STREAMING,
            pcm = pcm,
            options = StreamingExtractionOptions(queueCapacity = 2),
            analyzer = { shorts, target, base ->
                started.countDown()
                release.await(10, TimeUnit.SECONDS)
                analyze(shorts, target, base)
            },
        )
        val caller = Executors.newSingleThreadExecutor()
        val compute = Executors.newFixedThreadPool(1)
        val delivered = AtomicInteger()
        try {
            val job = launch(caller.asCoroutineDispatcher()) {
                transcriber.detectChunkLanguages(audioFile(), CHUNKS, pair, 1, compute.asCoroutineDispatcher()) { _, _ ->
                    delivered.incrementAndGet()
                }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            Thread.sleep(300)
            // 推論中の 1 件 + 待ち行列の 2 件 + 渡そうとして待っている 1 件まで
            val emitted = transcriber.languageOnlyExtractionTimings().streamWindows
            assertTrue(emitted in 2L..4L, "emitted=$emitted")
            assertEquals(0, delivered.get())
            release.countDown()
            job.join()
            assertEquals(CHUNKS.size, delivered.get())
            assertEquals(CHUNKS.size.toLong(), transcriber.languageOnlyExtractionTimings().streamWindows)
            assertTrue(transcriber.languageOnlyExtractionTimings().queueWaitNanos > 0L)
        } finally {
            release.countDown()
            caller.shutdownNow()
            compute.shutdownNow()
        }
        assertTrue(media.extractors.all { it.closed })
        assertTrue(media.allCodecsClosed())
    }

    @Test
    fun `順に流す切り出し_途中でキャンセルしても抽出器とMediaCodecを閉じる`() = runTest {
        val media = rawMedia()
        assertFailsWith<CancellationException> {
            detect(media.decoder, LidExtractionMethod.STREAMING, audioFile(), CHUNKS, options = StreamingExtractionOptions(maxRunWindows = 3)) { index ->
                if (index == 4) throw CancellationException("利用者が止めた（テスト）")
            }
        }
        assertTrue(media.extractors.isNotEmpty())
        assertTrue(media.extractors.all { it.closed })
        assertTrue(media.allCodecsClosed())
    }

    @Test
    fun `順に流す切り出し_MediaCodecを作れない・デコードが止まるrunは今の方式で切り出し直す`() = runTest {
        val file = audioFile()
        val current = detect(rawMedia().decoder, LidExtractionMethod.CURRENT, file, CHUNKS)
        // 順に流す切り出しの 2 つ目の run の MediaCodec だけ作れない（それより後は今の方式で切り出し直す）
        val media = Media(FakeMedia.rawFormat(durationMs = 40_000L), FakeMedia.rawPackets(40_000L)) { index ->
            if (index == 1) throw IllegalStateException("資源不足（テスト）") else FakeRawCodec(sampleRate = 44_100, trimStartSamples = 529)
        }
        val streaming = detect(media.decoder, LidExtractionMethod.STREAMING, file, CHUNKS, options = StreamingExtractionOptions(maxRunWindows = 4))

        assertSamePcmAndResults(current, streaming, "順に流す（作れない MediaCodec あり）")
        assertEquals(CHUNKS.size - 4L, streaming.timings.fallbacks)
        streaming.transcriber.releaseIdleResources()
        assertTrue(media.extractors.all { it.closed })
        assertTrue(media.allCodecsClosed())
    }

    @Test
    fun `速い方式_デコーダがMediaCodecでなければ今の方式で切り出す`() = runTest {
        val decoder = object : AudioStreamDecoder {
            override suspend fun decode(
                file: File,
                sink: SampleSink,
                outputSampleRate: Int,
                expectedDurationMs: Long?,
                onProgress: suspend (DecodeProgress) -> Unit,
            ): AudioDecodeResult {
                val samples = FloatArray(outputSampleRate * 10) { n -> FakeMedia.sampleValue(n.toLong(), outputSampleRate) / 32_768f }
                sink.onSamples(samples, samples.size)
                return AudioDecodeResult.Success(outputSampleRate, 1, samples.size.toLong(), samples.size.toLong(), 10_000L, 0, 0, "audio/raw")
            }
        }
        val chunks = listOf(ChunkTimeRange(0L, 2_000L), ChunkTimeRange(2_500L, 4_000L))
        for (method in listOf(LidExtractionMethod.PARALLEL, LidExtractionMethod.STREAMING)) {
            val outcome = detect(decoder, method, audioFile(), chunks)
            assertEquals(LidExtractionMethod.CURRENT, outcome.transcriber.lastLanguageOnlyExtractionMethod)
            assertEquals(2, outcome.pcm.size)
        }
    }

    @Test
    fun `planStreamRuns_大きな間と窓の数と長さの上限でrunを分ける`() {
        fun window(index: Int, startMs: Long, endMs: Long) = StreamWindowRequest(index, startMs, endMs)
        val windows = listOf(
            window(0, 1_000L, 3_800L),
            window(1, 4_000L, 6_000L),
            // 前の窓の終わり（6.0 秒）から、次のデコードの開始（17.5 秒）まで 11.5 秒空く
            window(2, 18_000L, 20_000L),
            window(3, 20_500L, 23_000L),
            window(4, 23_500L, 25_000L),
        )
        val options = StreamingExtractionOptions(maxGapMs = 10_000L, maxRunWindows = 2, maxRunDurationMs = 120_000L)
        assertEquals(listOf(0..1, 2..3, 4..4), planStreamRuns(windows, preRollMs = 500L, options = options))
        assertEquals(listOf(0..1, 2..4), planStreamRuns(windows, 500L, options.copy(maxRunWindows = 16)))
        // 間を 12 秒まで許せば 1 つ（長さの上限が短ければ分ける）
        assertEquals(listOf(0..4), planStreamRuns(windows, 500L, options.copy(maxGapMs = 12_000L, maxRunWindows = 16)))
        assertEquals(listOf(0..2, 3..4), planStreamRuns(windows, 500L, StreamingExtractionOptions(maxGapMs = 12_000L, maxRunDurationMs = 20_000L)))
        assertEquals(emptyList(), planStreamRuns(emptyList(), 500L, options))
    }

    @Test
    fun `describeLanguageOnlyExtraction_方式と1件あたりの内訳を1行にする`() {
        val delta = LidExtractionTimingsSnapshot(
            extractions = 2L,
            extractNanos = 120_000_000L,
            positionNanos = 4_000_000L,
            codecCreates = 2L,
            codecCreateNanos = 50_000_000L,
            decodeLoopNanos = 50_000_000L,
            mixNanos = 2_000_000L,
            resampleNanos = 10_000_000L,
            pcmNanos = 2_000_000L,
            codecReleaseNanos = 12_000_000L,
        )
        val line = AndroidSpeechRecognizerChunkTranscriber.describeLanguageOnlyExtraction(LidExtractionMethod.CURRENT, delta, 3_600_000_000L)
        assertEquals(
            "切り出し: 方式 cur（2 件、全体 3.6 秒。1 件あたり 60.0 ms = 抽出器 0.0 + 位置合わせ 2.0 + MediaCodec 作成 25.0 + " +
                "デコード待ち 19.0 + モノラル化 1.0 + リサンプル 4.0 + PCM 1.0 + 解放 6.0 + 受け渡し 0.0 + その他 2.0。MediaCodec 2 回）",
            line,
        )
    }

    private companion object {
        /** 40 秒の音声の中の 12 個のチャンク（短いもの・2.8 秒より長いものを含む）。 */
        val CHUNKS = listOf(
            ChunkTimeRange(200L, 2_600L),
            ChunkTimeRange(2_900L, 4_100L),
            ChunkTimeRange(4_300L, 9_000L),
            ChunkTimeRange(9_300L, 11_000L),
            ChunkTimeRange(11_200L, 11_700L),
            ChunkTimeRange(12_000L, 15_500L),
            ChunkTimeRange(15_800L, 18_000L),
            ChunkTimeRange(18_300L, 21_000L),
            ChunkTimeRange(21_500L, 22_300L),
            ChunkTimeRange(22_600L, 26_000L),
            ChunkTimeRange(26_400L, 29_000L),
            ChunkTimeRange(29_500L, 33_000L),
        )

        /** PCM の中身で決まる判定（チャンクごとに違う結果になり、PCM が少しでも違えば変わりやすい）。 */
        fun analyze(pcm: ShortArray, target: String, base: String): LanguagePairAnalysis {
            var sum = 0L
            var crossings = 0
            for (i in pcm.indices) {
                sum += abs(pcm[i].toInt())
                if (i > 0 && (pcm[i] >= 0) != (pcm[i - 1] >= 0)) crossings++
            }
            val targetProbability = ((sum % 97L) / 96f).coerceIn(0.02f, 0.98f)
            val embedding = FloatArray(8) { d -> ((sum % (d + 5L)) + crossings % (d + 3)).toFloat() + 1f }
            return LanguagePairAnalysis(
                prediction = LanguagePairPrediction(
                    selectedTag = if (targetProbability >= 0.5f) target else base,
                    fallbackTag = if (targetProbability >= 0.5f) base else target,
                    selectedProbability = maxOf(targetProbability, 1f - targetProbability),
                    targetProbability = targetProbability,
                    baseProbability = 1f - targetProbability,
                    inferenceTimeMs = 1L,
                    pairMass = 0.9f,
                ),
                encoderEmbedding = embedding,
            )
        }
    }
}
