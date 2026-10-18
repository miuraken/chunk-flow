package com.miuraken.chunkflow.core.media.bench

import com.miuraken.chunkflow.core.media.ChunkTimeRange
import com.miuraken.chunkflow.core.media.LidExtractionMethod
import com.miuraken.chunkflow.core.media.LidExtractionTimingsSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** 実機計測ツールの集計と、ログに出す行のテスト。 */
class LidBenchReportTest {

    private fun result(
        engine: LidBenchEngine,
        mode: LidBenchMode,
        languages: List<String?>,
        status: LidBenchStatus = LidBenchStatus.OK,
        totalMs: Double = 1_000.0,
        inferMs: List<Double> = listOf(10.0, 20.0, 30.0),
        extraction: LidExtractionMethod = LidExtractionMethod.CURRENT,
        pcm: List<LidBenchPcmPrint?> = emptyList(),
    ): LidBenchRunResult = LidBenchRunResult(
        engine = engine,
        mode = mode,
        status = status,
        languages = languages,
        totalMs = totalMs,
        cpuMs = 0L,
        inferMs = inferMs,
        nullAnalyses = 0,
        engineMeanMs = null,
        melMeanMs = null,
        onnxRuns = 0L,
        kotlinRuns = 0L,
        workers = mode.parallelism,
        degraded = false,
        thermalStart = 0,
        thermalEnd = 0,
        headroomStart = Float.NaN,
        headroomEnd = Float.NaN,
        powerSave = false,
        backgrounded = false,
        extraction = extraction,
        extractionUsed = extraction,
        pcm = pcm,
    )

    private fun pcmPrint(vararg samples: Short): LidBenchPcmPrint = LidBenchPcmPrint.of(shortArrayOf(*samples))

    @Test
    fun `median_奇数個は真ん中で偶数個は真ん中の2つの平均`() {
        assertEquals(20.0, LidBenchReport.median(listOf(30.0, 10.0, 20.0)))
        assertEquals(25.0, LidBenchReport.median(listOf(40.0, 10.0, 20.0, 30.0)))
        assertNull(LidBenchReport.median(emptyList()))
        assertEquals(20.0, LidBenchReport.mean(listOf(10.0, 20.0, 30.0)))
    }

    @Test
    fun `referenceFor_ONNXRuntimeは同じ進め方の純Kotlinと比べ純Kotlinの並列は逐次と比べる`() {
        val kotlinSeq = result(LidBenchEngine.KOTLIN, LidBenchMode.SEQUENTIAL, listOf("hi"))
        val kotlinPar = result(LidBenchEngine.KOTLIN, LidBenchMode.PARALLEL3, listOf("hi"))
        val onnxSeq = result(LidBenchEngine.ONNX, LidBenchMode.SEQUENTIAL, listOf("hi"))
        val onnxPar = result(LidBenchEngine.ONNX, LidBenchMode.PARALLEL3, listOf("hi"))
        val runs = listOf(kotlinSeq, kotlinPar, onnxSeq, onnxPar)

        assertNull(LidBenchReport.referenceFor(kotlinSeq, runs))
        assertSame(kotlinSeq, LidBenchReport.referenceFor(kotlinPar, runs))
        assertSame(kotlinSeq, LidBenchReport.referenceFor(onnxSeq, runs))
        assertSame(kotlinPar, LidBenchReport.referenceFor(onnxPar, runs))
        // 純 Kotlin の並列の回がなければ、最初の純 Kotlin の回と比べる
        assertSame(kotlinSeq, LidBenchReport.referenceFor(onnxPar, listOf(kotlinSeq, onnxPar)))
        // OK でない回は比べる相手にしない
        val failedKotlin = result(LidBenchEngine.KOTLIN, LidBenchMode.PARALLEL3, listOf("hi"), status = LidBenchStatus.ERROR)
        assertSame(kotlinSeq, LidBenchReport.referenceFor(onnxPar, listOf(kotlinSeq, failedKotlin, onnxPar)))
    }

    @Test
    fun `referenceFor_速い切り出しの回は同じエンジンと進め方の今の方式の回と比べる`() {
        val kotlinSeq = result(LidBenchEngine.KOTLIN, LidBenchMode.SEQUENTIAL, listOf("hi"))
        val onnxCur = result(LidBenchEngine.ONNX, LidBenchMode.PARALLEL3, listOf("hi"))
        val onnxPar = result(LidBenchEngine.ONNX, LidBenchMode.PARALLEL3, listOf("hi"), extraction = LidExtractionMethod.PARALLEL)
        val onnxStr = result(LidBenchEngine.ONNX, LidBenchMode.PARALLEL3, listOf("hi"), extraction = LidExtractionMethod.STREAMING)
        val runs = listOf(kotlinSeq, onnxCur, onnxPar, onnxStr)

        assertSame(onnxCur, LidBenchReport.referenceFor(onnxPar, runs))
        assertSame(onnxCur, LidBenchReport.referenceFor(onnxStr, runs))
        // 今の方式の回は、今までどおり純 Kotlin の今の方式の回と比べる
        assertSame(kotlinSeq, LidBenchReport.referenceFor(onnxCur, runs))
        // 今の方式の回がなければ、同じ切り出しの方式の純 Kotlin の回と比べる（なければ比べない）
        val kotlinStr = result(LidBenchEngine.KOTLIN, LidBenchMode.SEQUENTIAL, listOf("hi"), extraction = LidExtractionMethod.STREAMING)
        assertSame(kotlinStr, LidBenchReport.referenceFor(onnxStr, listOf(kotlinStr, onnxStr)))
        assertNull(LidBenchReport.referenceFor(onnxStr, listOf(kotlinSeq, onnxStr)))
        assertEquals("onnx/par3/str", onnxStr.key)
    }

    @Test
    fun `agreement_判定が同じチャンクの割合を出しOKでない回は比べない`() {
        val reference = result(LidBenchEngine.KOTLIN, LidBenchMode.SEQUENTIAL, listOf("hi", "en", null, "hi"))
        val onnx = result(LidBenchEngine.ONNX, LidBenchMode.SEQUENTIAL, listOf("hi", "hi", null, "hi"))

        val agreement = assertNotNull(LidBenchReport.agreement(onnx, reference))
        assertEquals(LidBenchAgreement(reference = "kotlin/seq/cur", matches = 3, total = 4), agreement)
        assertEquals(0.75, agreement.rate)

        val fallback = onnx.copy(status = LidBenchStatus.FALLBACK)
        assertNull(LidBenchReport.agreement(fallback, reference))
        assertNull(LidBenchReport.agreement(onnx, null))
    }

    @Test
    fun `LidBenchPcmPrint_同じPCMは同じ指紋で1サンプルでも違えば違う指紋になる`() {
        val a = pcmPrint(0, 1, -2, 300, Short.MIN_VALUE, Short.MAX_VALUE)
        assertEquals(a, pcmPrint(0, 1, -2, 300, Short.MIN_VALUE, Short.MAX_VALUE))
        assertEquals(6, a.samples)
        assertEquals(0L + 1 + 2 + 300 + 32_768 + 32_767, a.sumAbs)
        assertNotEquals(a.hash, pcmPrint(0, 1, -2, 301, Short.MIN_VALUE, Short.MAX_VALUE).hash)
        // 上位のバイトだけが違う・並びが違う場合も見分ける
        assertNotEquals(a.hash, pcmPrint(0, 1, -2, 556, Short.MIN_VALUE, Short.MAX_VALUE).hash)
        assertNotEquals(a.hash, pcmPrint(1, 0, -2, 300, Short.MIN_VALUE, Short.MAX_VALUE).hash)
        assertNotEquals(pcmPrint().hash, pcmPrint(0).hash)
    }

    @Test
    fun `pcmAgreementとpcmDiffLine_今の方式の回とPCMの指紋を比べ違うチャンクを出す`() {
        val same = pcmPrint(10, 20, 30)
        val cur = result(
            LidBenchEngine.ONNX,
            LidBenchMode.PARALLEL3,
            listOf("hi", "hi", "en", null),
            pcm = listOf(same, pcmPrint(1, 2, 3), pcmPrint(100, 100), null),
        )
        val kotlinCur = cur.copy(engine = LidBenchEngine.KOTLIN)
        val str = cur.copy(
            extraction = LidExtractionMethod.STREAMING,
            extractionUsed = LidExtractionMethod.STREAMING,
            pcm = listOf(same, pcmPrint(1, 2, 4), pcmPrint(100, 100, 1), null),
        )
        val chunks = listOf(ChunkTimeRange(0L, 1_000L), ChunkTimeRange(1_500L, 2_500L), ChunkTimeRange(3_000L, 4_000L), ChunkTimeRange(5_000L, 6_000L))
        val runs = listOf(kotlinCur, cur, str)

        assertNull(LidBenchReport.pcmReferenceFor(cur, runs))
        assertSame(cur, LidBenchReport.pcmReferenceFor(str, runs))
        // 同じエンジン・進め方の今の方式の回がなければ、ほかの今の方式の回と比べる（PCM はエンジンによらない）
        assertSame(kotlinCur, LidBenchReport.pcmReferenceFor(str, listOf(kotlinCur, str)))
        assertEquals(LidBenchPcmAgreement(reference = "onnx/par3/cur", matches = 2, total = 4), LidBenchReport.pcmAgreement(str, cur))
        assertNull(LidBenchReport.pcmAgreement(str.copy(status = LidBenchStatus.ERROR), cur))
        assertNull(LidBenchReport.pcmAgreement(str, cur.copy(pcm = emptyList())))

        assertEquals(
            "PCMDIFF engine=onnx mode=par3 ext=str ref=onnx/par3/cur count=2 chunks=1@1500ms:3/3:abs+16.667%,2@3000ms:3/2:abs+0.500%",
            LidBenchReport.pcmDiffLine(str, cur, chunks),
        )
        assertNull(LidBenchReport.pcmDiffLine(cur.copy(extraction = LidExtractionMethod.PARALLEL), cur, chunks))
    }

    @Test
    fun `runLine_RESULTは切り出しの方式と内訳とPCMの一致を含む機械で読める1行にする`() {
        val run = result(
            engine = LidBenchEngine.ONNX,
            mode = LidBenchMode.PARALLEL3,
            languages = listOf("hi", "en", null, "hi"),
            totalMs = 612.34,
            inferMs = listOf(28.0, 30.0, 26.0),
            extraction = LidExtractionMethod.PARALLEL,
        ).copy(
            cpuMs = 1_834L,
            nullAnalyses = 1,
            engineMeanMs = 25.94,
            melMeanMs = 2.054,
            onnxRuns = 3L,
            thermalEnd = 1,
            headroomStart = 0.42f,
            extract = LidExtractionTimingsSnapshot(
                extractions = 4L,
                extractNanos = 240_000_000L,
                extractorOpens = 1L,
                extractorOpenNanos = 4_000_000L,
                positionNanos = 8_000_000L,
                seeks = 2L,
                skippedSamples = 1_234L,
                codecCreates = 4L,
                codecCreateNanos = 100_000_000L,
                decodeLoopNanos = 100_000_000L,
                mixNanos = 8_000_000L,
                resampleNanos = 20_000_000L,
                pcmNanos = 4_000_000L,
                codecReleaseNanos = 20_000_000L,
                handoffNanos = 2_000_000L,
                decodedNativeSamples = 500_000L,
                adaptNanos = 400_000L,
                awaitNanos = 40_000_000L,
            ),
        )
        val agreement = LidBenchAgreement(reference = "onnx/par3/cur", matches = 4, total = 4)
        val pcmAgreement = LidBenchPcmAgreement(reference = "onnx/par3/cur", matches = 4, total = 4)

        assertEquals(
            "RESULT name=bench engine=onnx mode=par3 ext=par extUsed=par status=OK chunks=4 detected=3 totalMs=612.3 perChunkMs=153.1 " +
                "inferN=3 inferMedianMs=28.0 inferMeanMs=28.0 engineMeanMs=25.9 melMeanMs=2.05 nullAnalyses=1 " +
                "onnxRuns=3 kotlinRuns=0 workers=3 degraded=false cpuMs=1834 thermal=0->1 headroom=0.42->NaN " +
                "powerSave=false bg=false exN=4 exMs=60.00 exOpenMs=1.00 exPosMs=2.00 codecNewMs=25.00 codecWaitMs=18.00 " +
                "mixMs=2.00 rsMs=4.00 pcmMs=1.00 codecRelMs=5.00 handoffMs=0.50 exOtherMs=1.50 adaptMs=0.10 awaitMs=10.00 " +
                "codecs=4 exOpens=1 seeks=2 skipSamples=1234 decodedSamples=500000 runs=0 windows=0 qWaitMs=0.0 fallbacks=0 " +
                "agree=1.000 agreeN=4/4 ref=onnx/par3/cur pcmSame=4/4 pcmRef=onnx/par3/cur",
            LidBenchReport.runLine("RESULT", "bench", run, agreement, withAgreement = true, pcmAgreement = pcmAgreement),
        )
        // 順に流す切り出しでは、待ち行列の待ちを除いた時間を、切り出した窓と切り出し直した数の合計で割る
        val streaming = run.copy(
            extraction = LidExtractionMethod.STREAMING,
            extractionUsed = LidExtractionMethod.STREAMING,
            extract = LidExtractionTimingsSnapshot(
                extractions = 1L,
                extractNanos = 130_000_000L,
                streamRuns = 1L,
                streamWindows = 3L,
                queueWaitNanos = 50_000_000L,
                fallbacks = 1L,
            ),
        )
        val streamingLine = LidBenchReport.runLine("RESULT", "bench", streaming, null, withAgreement = true)
        assertTrue(streamingLine.contains(" ext=str extUsed=str "), streamingLine)
        assertTrue(streamingLine.contains(" exN=4 exMs=20.00 "), streamingLine)
        assertTrue(streamingLine.contains(" exOtherMs=20.00 "), streamingLine)
        assertTrue(streamingLine.contains(" runs=1 windows=3 qWaitMs=50.0 fallbacks=1 "), streamingLine)

        val skipped = run.copy(
            status = LidBenchStatus.SKIPPED,
            extractionUsed = null,
            extract = LidExtractionTimingsSnapshot(),
            note = "ONNX Runtime を使えない: ONNX のファイルがない",
        )
        val line = LidBenchReport.runLine("RESULT", "bench", skipped, null, withAgreement = true)
        assertTrue(line.contains(" ext=par extUsed=- status=SKIPPED "), line)
        assertTrue(line.contains(" exN=0 exMs=NA "), line)
        assertTrue(line.endsWith(" agree=NA agreeN=- ref=- pcmSame=NA pcmRef=- note=ONNX Runtime を使えない: ONNX のファイルがない"), line)
        val runDone = LidBenchReport.runLine("RUN_DONE", "bench", run, null, withAgreement = false)
        assertTrue(runDone.startsWith("RUN_DONE name=bench engine=onnx mode=par3 ext=par extUsed=par status=OK "), runDone)
        assertTrue(runDone.contains(" exMs=60.00 "), runDone)
        assertTrue(!runDone.contains("agree=") && !runDone.contains("pcmSame="), runDone)
    }

    @Test
    fun `diffLine_判定が違うチャンクを番号と開始時刻と両方の判定で出す`() {
        val reference = result(LidBenchEngine.KOTLIN, LidBenchMode.SEQUENTIAL, listOf("hi", "en", null))
        val onnx = result(LidBenchEngine.ONNX, LidBenchMode.SEQUENTIAL, listOf("hi", "hi", "en"))
        val chunks = listOf(ChunkTimeRange(0L, 1_000L), ChunkTimeRange(1_500L, 2_500L), ChunkTimeRange(3_000L, 4_000L))

        assertEquals(
            "DIFF engine=onnx mode=seq ext=cur ref=kotlin/seq/cur count=2 chunks=1@1500ms:hi/en,2@3000ms:en/-",
            LidBenchReport.diffLine(onnx, reference, chunks),
        )
        assertNull(LidBenchReport.diffLine(reference, reference.copy(), chunks))
    }

    @Test
    fun `tableLinesとsummaryLines_表と両エンジンがOKの進め方だけの比を出す`() {
        val runs = listOf(
            result(LidBenchEngine.KOTLIN, LidBenchMode.SEQUENTIAL, listOf("hi"), totalMs = 50_000.0, inferMs = listOf(800.0)),
            result(LidBenchEngine.KOTLIN, LidBenchMode.PARALLEL3, listOf("hi"), totalMs = 17_000.0),
            result(LidBenchEngine.ONNX, LidBenchMode.SEQUENTIAL, listOf("hi"), totalMs = 2_000.0, inferMs = listOf(32.0)),
            result(LidBenchEngine.ONNX, LidBenchMode.PARALLEL3, listOf("hi"), status = LidBenchStatus.FALLBACK),
        )

        val table = LidBenchReport.tableLines(runs, listOf(null, null, null, null))
        assertEquals(5, table.size)
        assertTrue(table.all { it.startsWith("TABLE ") })
        assertTrue(table[0].startsWith("TABLE engine mode ext     status "), table[0])
        assertTrue(table[3].startsWith("TABLE onnx   seq  cur     OK "), table[3])

        assertEquals(
            listOf(
                "SUMMARY mode=seq ext=cur kotlinTotalMs=50000.0 onnxTotalMs=2000.0 totalSpeedup=25.0x " +
                    "kotlinInferMedianMs=800.0 onnxInferMedianMs=32.0 inferSpeedup=25.0x",
            ),
            LidBenchReport.summaryLines(runs),
        )
        assertEquals(emptyList(), LidBenchReport.extractionSummaryLines(runs))
    }

    @Test
    fun `extractionSummaryLines_速い切り出しの回ごとに今の方式との比と一致を出し表に実際の方式を出す`() {
        val prints = listOf(pcmPrint(1, 2), pcmPrint(3, 4))
        val cur = result(LidBenchEngine.ONNX, LidBenchMode.PARALLEL3, listOf("hi", "en"), totalMs = 3_600.0, pcm = prints).copy(
            extract = LidExtractionTimingsSnapshot(extractions = 2L, extractNanos = 122_400_000L),
        )
        val str = result(
            LidBenchEngine.ONNX,
            LidBenchMode.PARALLEL3,
            listOf("hi", "hi"),
            totalMs = 1_500.0,
            extraction = LidExtractionMethod.STREAMING,
            pcm = listOf(prints[0], pcmPrint(3, 5)),
        ).copy(extract = LidExtractionTimingsSnapshot(streamWindows = 2L, extractNanos = 40_200_000L))
        val strAsPar = result(
            LidBenchEngine.ONNX,
            LidBenchMode.SEQUENTIAL,
            listOf("hi", "en"),
            extraction = LidExtractionMethod.STREAMING,
        ).copy(extractionUsed = LidExtractionMethod.PARALLEL)
        val runs = listOf(cur, str, strAsPar)

        assertEquals(
            listOf(
                "SUMMARY_EXT engine=onnx mode=par3 ext=str extUsed=str curTotalMs=3600.0 totalMs=1500.0 totalSpeedup=2.4x " +
                    "curPerChunkMs=1800.0 perChunkMs=750.0 curExMs=61.2 exMs=20.1 agree=0.500 agreeN=1/2 pcmSame=1/2",
            ),
            LidBenchReport.extractionSummaryLines(runs),
        )
        val table = LidBenchReport.tableLines(runs, listOf(null, null, null), listOf(null, LidBenchPcmAgreement("onnx/par3/cur", 1, 2), null))
        assertTrue(table[2].startsWith("TABLE onnx   par3 str     OK "), table[2])
        assertTrue(table[2].contains(" 1/2 "), table[2])
        assertTrue(table[3].startsWith("TABLE onnx   seq  str>par OK "), table[3])
    }
}
