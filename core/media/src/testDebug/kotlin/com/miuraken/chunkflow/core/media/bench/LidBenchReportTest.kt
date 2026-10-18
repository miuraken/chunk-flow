package com.miuraken.chunkflow.core.media.bench

import com.miuraken.chunkflow.core.media.ChunkTimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
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
    )

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
    fun `agreement_判定が同じチャンクの割合を出しOKでない回は比べない`() {
        val reference = result(LidBenchEngine.KOTLIN, LidBenchMode.SEQUENTIAL, listOf("hi", "en", null, "hi"))
        val onnx = result(LidBenchEngine.ONNX, LidBenchMode.SEQUENTIAL, listOf("hi", "hi", null, "hi"))

        val agreement = assertNotNull(LidBenchReport.agreement(onnx, reference))
        assertEquals(LidBenchAgreement(reference = "kotlin/seq", matches = 3, total = 4), agreement)
        assertEquals(0.75, agreement.rate)

        val fallback = onnx.copy(status = LidBenchStatus.FALLBACK)
        assertNull(LidBenchReport.agreement(fallback, reference))
        assertNull(LidBenchReport.agreement(onnx, null))
    }

    @Test
    fun `runLine_RESULTは機械で読める1行にする`() {
        val run = result(
            engine = LidBenchEngine.ONNX,
            mode = LidBenchMode.PARALLEL3,
            languages = listOf("hi", "en", null, "hi"),
            totalMs = 612.34,
            inferMs = listOf(28.0, 30.0, 26.0),
        ).copy(
            cpuMs = 1_834L,
            nullAnalyses = 1,
            engineMeanMs = 25.94,
            melMeanMs = 2.054,
            onnxRuns = 3L,
            thermalEnd = 1,
            headroomStart = 0.42f,
        )
        val agreement = LidBenchAgreement(reference = "kotlin/par3", matches = 4, total = 4)

        assertEquals(
            "RESULT name=bench engine=onnx mode=par3 status=OK chunks=4 detected=3 totalMs=612.3 perChunkMs=153.1 " +
                "inferN=3 inferMedianMs=28.0 inferMeanMs=28.0 engineMeanMs=25.9 melMeanMs=2.05 nullAnalyses=1 " +
                "onnxRuns=3 kotlinRuns=0 workers=3 degraded=false cpuMs=1834 thermal=0->1 headroom=0.42->NaN " +
                "powerSave=false bg=false agree=1.000 agreeN=4/4 ref=kotlin/par3",
            LidBenchReport.runLine("RESULT", "bench", run, agreement, withAgreement = true),
        )
        val skipped = run.copy(status = LidBenchStatus.SKIPPED, note = "ONNX Runtime を使えない: ONNX のファイルがない")
        val line = LidBenchReport.runLine("RESULT", "bench", skipped, null, withAgreement = true)
        assertTrue(line.contains(" status=SKIPPED "), line)
        assertTrue(line.endsWith(" agree=NA agreeN=- ref=- note=ONNX Runtime を使えない: ONNX のファイルがない"), line)
        val runDone = LidBenchReport.runLine("RUN_DONE", "bench", run, null, withAgreement = false)
        assertTrue(runDone.startsWith("RUN_DONE name=bench engine=onnx mode=par3 status=OK "), runDone)
        assertTrue(!runDone.contains("agree="), runDone)
    }

    @Test
    fun `diffLine_判定が違うチャンクを番号と開始時刻と両方の判定で出す`() {
        val reference = result(LidBenchEngine.KOTLIN, LidBenchMode.SEQUENTIAL, listOf("hi", "en", null))
        val onnx = result(LidBenchEngine.ONNX, LidBenchMode.SEQUENTIAL, listOf("hi", "hi", "en"))
        val chunks = listOf(ChunkTimeRange(0L, 1_000L), ChunkTimeRange(1_500L, 2_500L), ChunkTimeRange(3_000L, 4_000L))

        assertEquals(
            "DIFF engine=onnx mode=seq ref=kotlin/seq count=2 chunks=1@1500ms:hi/en,2@3000ms:en/-",
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
        assertTrue(table[3].startsWith("TABLE onnx   seq  OK "), table[3])

        assertEquals(
            listOf(
                "SUMMARY mode=seq kotlinTotalMs=50000.0 onnxTotalMs=2000.0 totalSpeedup=25.0x " +
                    "kotlinInferMedianMs=800.0 onnxInferMedianMs=32.0 inferSpeedup=25.0x",
            ),
            LidBenchReport.summaryLines(runs),
        )
    }
}
