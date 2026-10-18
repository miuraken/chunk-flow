package com.miuraken.chunkflow.core.media.bench

import com.miuraken.chunkflow.core.media.ChunkTimeRange
import java.util.Locale

/** 1 回の計測の結果の種類。 */
internal enum class LidBenchStatus {
    /** 指定したエンジンだけで推論した。 */
    OK,

    /** ONNX Runtime を指定したが、純 Kotlin に切り替わった推論がある（ONNX Runtime の結果として使わない）。 */
    FALLBACK,

    /** 推論が 1 回もなかった（すべて無音、切り出しの失敗など）。 */
    NO_INFERENCE,

    /** ウォームアップでそのエンジンを使えないと分かった（ONNX Runtime を読み込めない、例外が起きた）ので、計測しなかった。 */
    SKIPPED,

    /** 計測中に例外が起きた。 */
    ERROR,
}

/**
 * 1 回の計測（1 つのエンジン × 1 つの進め方）の結果。
 *
 * @property engine 計測したエンジン。
 * @property mode 進め方（逐次 / 3 並列）。
 * @property status 結果の種類。[LidBenchStatus.OK] 以外の回は比べるのに使わない。
 * @property languages チャンクごとの最終判定（ファイル内の補正の後の言語タグ。判定できなければ `null`）。
 * @property totalMs `detectChunkLanguages` の全体時間（音声の切り出し・log-mel・推論・補正を含む壁時計の時間）。
 * @property cpuMs この間にプロセスが使った CPU 時間（全スレッドの合計。参考値）。
 * @property inferMs チャンクごとの `analyzeLanguagePair` の時間（log-mel・推論・平均プーリング。判定できたチャンクだけ）。
 * @property nullAnalyses 判定器が `null` を返した回数（無音など。[inferMs] に入れない）。
 * @property engineMeanMs 推論（エンコーダ + デコーダ）だけの平均時間（判定器の累計から）。
 * @property melMeanMs log-mel の平均時間（判定器の累計から）。
 * @property onnxRuns ONNX Runtime で推論した回数。
 * @property kotlinRuns 純 Kotlin で推論した回数。
 * @property workers 実際に同時に推論した数（端末が熱い・省電力モードで 1 件ずつになった場合は 1）。
 * @property degraded 3 並列を指定したが、端末の状態のために 1 件ずつになった。
 * @property thermalStart 開始時の `PowerManager.currentThermalStatus`。
 * @property thermalEnd 終了時の `PowerManager.currentThermalStatus`。
 * @property headroomStart 開始時の `PowerManager.getThermalHeadroom`（取れなければ NaN）。
 * @property headroomEnd 終了時の `PowerManager.getThermalHeadroom`（取れなければ NaN）。
 * @property powerSave 開始時に省電力モードだった。
 * @property backgrounded 計測中に画面が前面でなくなった。
 * @property note 補足（切り替わった理由、例外など）。
 */
internal data class LidBenchRunResult(
    val engine: LidBenchEngine,
    val mode: LidBenchMode,
    val status: LidBenchStatus,
    val languages: List<String?>,
    val totalMs: Double,
    val cpuMs: Long,
    val inferMs: List<Double>,
    val nullAnalyses: Int,
    val engineMeanMs: Double?,
    val melMeanMs: Double?,
    val onnxRuns: Long,
    val kotlinRuns: Long,
    val workers: Int,
    val degraded: Boolean,
    val thermalStart: Int,
    val thermalEnd: Int,
    val headroomStart: Float,
    val headroomEnd: Float,
    val powerSave: Boolean,
    val backgrounded: Boolean,
    val note: String? = null,
) {
    /** 計測したチャンク数。 */
    val chunkCount: Int
        get() = languages.size

    /** 言語を判定できたチャンク数。 */
    val detectedCount: Int
        get() = languages.count { it != null }

    /** 1 チャンクあたりの全体時間。 */
    val perChunkMs: Double?
        get() = if (chunkCount > 0) totalMs / chunkCount else null

    /** `engine/mode`（例: `kotlin/seq`）。 */
    val key: String
        get() = "${engine.label}/${mode.label}"
}

/**
 * 最終判定の一致。
 *
 * @property reference 比べた相手の回（`engine/mode`）。
 * @property matches 判定が同じだったチャンク数（どちらも判定できなかったチャンクを含む）。
 * @property total チャンク数。
 */
internal data class LidBenchAgreement(
    val reference: String,
    val matches: Int,
    val total: Int,
) {
    /** 一致率。 */
    val rate: Double
        get() = if (total > 0) matches.toDouble() / total else 0.0
}

/** 計測の結果の集計と、ログに出す行の組み立て。 */
internal object LidBenchReport {
    /** [values] の中央値。空なら `null`。 */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** [values] の平均。空なら `null`。 */
    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.sum() / values.size

    /**
     * [run] の最終判定を比べる相手を返す。
     *
     * ONNX Runtime の回は、同じ進め方の純 Kotlin の回（なければ最初の純 Kotlin の回）と比べる。
     * 純 Kotlin の回は、純 Kotlin の逐次の回と比べる（逐次と並列で判定が同じことの確認。自分自身とは比べない）。
     */
    fun referenceFor(run: LidBenchRunResult, runs: List<LidBenchRunResult>): LidBenchRunResult? {
        val usable = runs.filter { it !== run && it.status == LidBenchStatus.OK && it.engine == LidBenchEngine.KOTLIN }
        return when (run.engine) {
            LidBenchEngine.ONNX -> usable.firstOrNull { it.mode == run.mode } ?: usable.firstOrNull()
            LidBenchEngine.KOTLIN -> usable.firstOrNull { it.mode == LidBenchMode.SEQUENTIAL }
        }
    }

    /** [run] と [reference] の最終判定の一致。比べられない（どちらかがない、件数が違う、[run] が OK でない）なら `null`。 */
    fun agreement(run: LidBenchRunResult, reference: LidBenchRunResult?): LidBenchAgreement? {
        if (reference == null || run.status != LidBenchStatus.OK || run.chunkCount != reference.chunkCount) return null
        val matches = run.languages.indices.count { run.languages[it] == reference.languages[it] }
        return LidBenchAgreement(reference = reference.key, matches = matches, total = run.chunkCount)
    }

    /**
     * 1 回の計測の結果を、機械で読める 1 行にする（`key=value` を空白で区切る。`note=` は最後で、空白を含むことがある）。
     *
     * @param prefix 行の先頭（`RUN_DONE` または `RESULT`）。
     * @param agreement [prefix] が `RESULT` のときに出す一致（`null` なら `agree=NA`）。
     */
    fun runLine(prefix: String, name: String, run: LidBenchRunResult, agreement: LidBenchAgreement?, withAgreement: Boolean): String = buildString {
        append(prefix)
        append(" name=").append(name)
        append(" engine=").append(run.engine.label)
        append(" mode=").append(run.mode.label)
        append(" status=").append(run.status.name)
        append(" chunks=").append(run.chunkCount)
        append(" detected=").append(run.detectedCount)
        append(" totalMs=").append(fmt1(run.totalMs))
        append(" perChunkMs=").append(fmt1(run.perChunkMs))
        append(" inferN=").append(run.inferMs.size)
        append(" inferMedianMs=").append(fmt1(median(run.inferMs)))
        append(" inferMeanMs=").append(fmt1(mean(run.inferMs)))
        append(" engineMeanMs=").append(fmt1(run.engineMeanMs))
        append(" melMeanMs=").append(fmt2(run.melMeanMs))
        append(" nullAnalyses=").append(run.nullAnalyses)
        append(" onnxRuns=").append(run.onnxRuns)
        append(" kotlinRuns=").append(run.kotlinRuns)
        append(" workers=").append(run.workers)
        append(" degraded=").append(run.degraded)
        append(" cpuMs=").append(run.cpuMs)
        append(" thermal=").append(run.thermalStart).append("->").append(run.thermalEnd)
        append(" headroom=").append(fmtHeadroom(run.headroomStart)).append("->").append(fmtHeadroom(run.headroomEnd))
        append(" powerSave=").append(run.powerSave)
        append(" bg=").append(run.backgrounded)
        if (withAgreement) {
            if (agreement != null) {
                append(" agree=").append(String.format(Locale.ROOT, "%.3f", agreement.rate))
                append(" agreeN=").append(agreement.matches).append('/').append(agreement.total)
                append(" ref=").append(agreement.reference)
            } else {
                append(" agree=NA agreeN=- ref=-")
            }
        }
        run.note?.let { append(" note=").append(it) }
    }

    /**
     * [run] の判定が [reference] と違うチャンクを 1 行にする（最初の [MAX_DIFF_ITEMS] 件。`番号@開始ms:判定/相手の判定`）。
     * 違いがなければ `null`。
     */
    fun diffLine(run: LidBenchRunResult, reference: LidBenchRunResult, chunks: List<ChunkTimeRange>): String? {
        if (run.status != LidBenchStatus.OK || run.chunkCount != reference.chunkCount) return null
        val diffs = run.languages.indices.filter { run.languages[it] != reference.languages[it] }
        if (diffs.isEmpty()) return null
        val items = diffs.take(MAX_DIFF_ITEMS).joinToString(",") { index ->
            val startMs = chunks.getOrNull(index)?.startMs ?: -1L
            "$index@${startMs}ms:${run.languages[index] ?: "-"}/${reference.languages[index] ?: "-"}"
        }
        return "DIFF engine=${run.engine.label} mode=${run.mode.label} ref=${reference.key} count=${diffs.size} chunks=$items"
    }

    /** 結果の表（見出しと各回の行。等幅で読める形）。 */
    fun tableLines(runs: List<LidBenchRunResult>, agreements: List<LidBenchAgreement?>): List<String> {
        val header = String.format(
            Locale.ROOT,
            TABLE_FORMAT,
            "engine", "mode", "status", "chunks", "totalMs", "ms/chunk", "inferMed", "inferMean",
            "engMean", "melMean", "workers", "thermal", "agree",
        )
        val rows = runs.mapIndexed { index, run ->
            val agreement = agreements.getOrNull(index)
            String.format(
                Locale.ROOT,
                TABLE_FORMAT,
                run.engine.label,
                run.mode.label,
                run.status.name,
                run.chunkCount.toString(),
                fmt1(run.totalMs),
                fmt1(run.perChunkMs),
                fmt1(median(run.inferMs)),
                fmt1(mean(run.inferMs)),
                fmt1(run.engineMeanMs),
                fmt2(run.melMeanMs),
                if (run.degraded) "${run.workers}!" else run.workers.toString(),
                "${run.thermalStart}->${run.thermalEnd}",
                agreement?.let { String.format(Locale.ROOT, "%.3f", it.rate) } ?: "-",
            )
        }
        return listOf("TABLE $header") + rows.map { "TABLE $it" }
    }

    /** 進め方ごとの、純 Kotlin と ONNX Runtime の比（どちらも OK の回があるときだけ）。 */
    fun summaryLines(runs: List<LidBenchRunResult>): List<String> = LidBenchMode.entries.mapNotNull { mode ->
        val kotlin = runs.firstOrNull { it.engine == LidBenchEngine.KOTLIN && it.mode == mode && it.status == LidBenchStatus.OK }
        val onnx = runs.firstOrNull { it.engine == LidBenchEngine.ONNX && it.mode == mode && it.status == LidBenchStatus.OK }
        if (kotlin == null || onnx == null) return@mapNotNull null
        val kotlinInfer = median(kotlin.inferMs)
        val onnxInfer = median(onnx.inferMs)
        buildString {
            append("SUMMARY mode=").append(mode.label)
            append(" kotlinTotalMs=").append(fmt1(kotlin.totalMs))
            append(" onnxTotalMs=").append(fmt1(onnx.totalMs))
            append(" totalSpeedup=").append(fmtRatio(kotlin.totalMs, onnx.totalMs))
            append(" kotlinInferMedianMs=").append(fmt1(kotlinInfer))
            append(" onnxInferMedianMs=").append(fmt1(onnxInfer))
            append(" inferSpeedup=").append(fmtRatio(kotlinInfer, onnxInfer))
        }
    }

    /** 小数 1 桁（`null` は `NA`）。 */
    fun fmt1(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "NA"

    /** 小数 2 桁（`null` は `NA`）。 */
    fun fmt2(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "NA"

    private fun fmtHeadroom(value: Float): String = if (value.isNaN()) "NaN" else String.format(Locale.ROOT, "%.2f", value)

    private fun fmtRatio(numerator: Double?, denominator: Double?): String = if (numerator == null || denominator == null || denominator <= 0.0) "NA" else String.format(Locale.ROOT, "%.1fx", numerator / denominator)

    /** [diffLine] に出す件数の上限。 */
    private const val MAX_DIFF_ITEMS: Int = 20

    private const val TABLE_FORMAT: String = "%-6s %-4s %-12s %6s %9s %8s %8s %9s %8s %7s %7s %-7s %s"
}
