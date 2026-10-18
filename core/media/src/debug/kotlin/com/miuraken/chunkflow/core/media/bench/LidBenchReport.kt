package com.miuraken.chunkflow.core.media.bench

import com.miuraken.chunkflow.core.media.ChunkTimeRange
import com.miuraken.chunkflow.core.media.LidExtractionMethod
import com.miuraken.chunkflow.core.media.LidExtractionTimingsSnapshot
import java.util.Locale
import kotlin.math.abs

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
 * 切り出した PCM の指紋（PCM を持たずに、切り出しの方式ごとの PCM が同じかを確かめるため）。
 *
 * @property samples サンプル数。
 * @property hash サンプル列（16-bit リトルエンディアンのバイト列）の 64 ビット FNV-1a。
 * @property sumAbs サンプルの絶対値の合計（違ったときに、違いの大きさの目安にする）。
 */
internal data class LidBenchPcmPrint(
    val samples: Int,
    val hash: Long,
    val sumAbs: Long,
) {
    companion object {
        /** [pcm] の指紋を作る（1 チャンク 2.8 秒・44,800 サンプルで 0.1 ms ほど）。 */
        fun of(pcm: ShortArray): LidBenchPcmPrint {
            var hash = FNV_OFFSET_BASIS
            var sumAbs = 0L
            for (sample in pcm) {
                val value = sample.toInt()
                hash = (hash xor (value and 0xFF).toLong()) * FNV_PRIME
                hash = (hash xor ((value shr 8) and 0xFF).toLong()) * FNV_PRIME
                sumAbs += abs(value)
            }
            return LidBenchPcmPrint(samples = pcm.size, hash = hash, sumAbs = sumAbs)
        }

        /** 64 ビット FNV-1a の初期値（0xcbf29ce484222325 を符号付きで書いたもの）。 */
        private const val FNV_OFFSET_BASIS: Long = -0x340d631b7bdddcdbL

        /** 64 ビット FNV-1a の素数。 */
        private const val FNV_PRIME: Long = 0x100000001b3L
    }
}

/**
 * 1 回の計測（1 つのエンジン × 1 つの進め方 × 1 つの切り出しの方式）の結果。
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
 * @property extraction 指定した音声の切り出しの方式。
 * @property extractionUsed 実際に使った切り出しの方式（順に流す切り出しを指定しても、チャンクがまばらなら並列の切り出しになる。
 *   デコーダが MediaCodec でない、2 言語でないなどの場合は今の方式になる）。計測しなかった回は `null`。
 * @property extract この回の切り出しの各段階の時間と回数（回の前後の差。並列の方式では複数のスレッドの時間を足す）。
 * @property pcm チャンクごとの、切り出した PCM の指紋（切り出せなかったチャンクは `null`。計測しなかった回は空）。
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
    val extraction: LidExtractionMethod = LidExtractionMethod.CURRENT,
    val extractionUsed: LidExtractionMethod? = null,
    val extract: LidExtractionTimingsSnapshot = LidExtractionTimingsSnapshot(),
    val pcm: List<LidBenchPcmPrint?> = emptyList(),
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

    /** 切り出しの各段階の時間を割る件数（今の方式の経路で切り出した数と、順に流す切り出しで切り出した窓の数の合計）。 */
    val extractCount: Long
        get() = extract.extractions + extract.streamWindows

    /** 1 件あたりの切り出しの時間（待ち行列の待ちを除く）。切り出さなかった回は `null`。 */
    val extractMsPerChunk: Double?
        get() = if (extractCount > 0L) extract.busyNanos / NANOS_PER_MS / extractCount else null

    /** `engine/mode/ext`（例: `onnx/par3/cur`）。 */
    val key: String
        get() = "${engine.label}/${mode.label}/${extraction.label}"

    private companion object {
        const val NANOS_PER_MS: Double = 1_000_000.0
    }
}

/**
 * 最終判定の一致。
 *
 * @property reference 比べた相手の回（`engine/mode/ext`）。
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

/**
 * 切り出した PCM の一致（指紋で比べる）。
 *
 * @property reference 比べた相手の回（`engine/mode/ext`。今の方式の回）。
 * @property matches PCM が同じだったチャンク数（どちらも切り出せなかったチャンクを含む）。
 * @property total チャンク数。
 */
internal data class LidBenchPcmAgreement(
    val reference: String,
    val matches: Int,
    val total: Int,
)

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
     * [run] の最終判定を比べる相手を返す（OK の回だけ。自分自身とは比べない）。
     *
     * - 今の方式でない回は、同じエンジン・同じ進め方の今の方式の回と比べる（なければ下と同じ規則で、同じ切り出しの方式の回と比べる）。
     * - ONNX Runtime の回は、同じ進め方の純 Kotlin の回（なければ最初の純 Kotlin の回）と比べる。
     * - 純 Kotlin の回は、純 Kotlin の逐次の回と比べる（逐次と並列で判定が同じことの確認）。
     */
    fun referenceFor(run: LidBenchRunResult, runs: List<LidBenchRunResult>): LidBenchRunResult? {
        val usable = runs.filter { it !== run && it.status == LidBenchStatus.OK }
        if (run.extraction != LidExtractionMethod.CURRENT) {
            usable.firstOrNull { it.engine == run.engine && it.mode == run.mode && it.extraction == LidExtractionMethod.CURRENT }
                ?.let { return it }
        }
        val kotlin = usable.filter { it.engine == LidBenchEngine.KOTLIN && it.extraction == run.extraction }
        return when (run.engine) {
            LidBenchEngine.ONNX -> kotlin.firstOrNull { it.mode == run.mode } ?: kotlin.firstOrNull()
            LidBenchEngine.KOTLIN -> kotlin.firstOrNull { it.mode == LidBenchMode.SEQUENTIAL }
        }
    }

    /** [run] と [reference] の最終判定の一致。比べられない（どちらかがない、件数が違う、[run] が OK でない）なら `null`。 */
    fun agreement(run: LidBenchRunResult, reference: LidBenchRunResult?): LidBenchAgreement? {
        if (reference == null || run.status != LidBenchStatus.OK || run.chunkCount != reference.chunkCount) return null
        val matches = run.languages.indices.count { run.languages[it] == reference.languages[it] }
        return LidBenchAgreement(reference = reference.key, matches = matches, total = run.chunkCount)
    }

    /**
     * [run]（今の方式でない回）の切り出した PCM を比べる相手（今の方式の OK の回。同じエンジン・同じ進め方の回を優先する）を返す。
     * PCM はエンジンと進め方によらないので、なければほかの今の方式の回と比べる。今の方式の回は `null`。
     */
    fun pcmReferenceFor(run: LidBenchRunResult, runs: List<LidBenchRunResult>): LidBenchRunResult? {
        if (run.extraction == LidExtractionMethod.CURRENT) return null
        val candidates = runs.filter {
            it !== run && it.status == LidBenchStatus.OK && it.extraction == LidExtractionMethod.CURRENT && it.pcm.isNotEmpty()
        }
        return candidates.firstOrNull { it.engine == run.engine && it.mode == run.mode } ?: candidates.firstOrNull()
    }

    /** [run] と [reference] の切り出した PCM の一致。比べられない（どちらかがない、件数が違う、[run] が OK でない）なら `null`。 */
    fun pcmAgreement(run: LidBenchRunResult, reference: LidBenchRunResult?): LidBenchPcmAgreement? {
        if (reference == null || run.status != LidBenchStatus.OK || run.pcm.isEmpty() || run.pcm.size != reference.pcm.size) return null
        val matches = run.pcm.indices.count { run.pcm[it] == reference.pcm[it] }
        return LidBenchPcmAgreement(reference = reference.key, matches = matches, total = run.pcm.size)
    }

    /**
     * 1 回の計測の結果を、機械で読める 1 行にする（`key=value` を空白で区切る。`note=` は最後で、空白を含むことがある）。
     *
     * `bg=` の後に、切り出しの 1 件あたりの各段階の時間（ミリ秒）と回数を出す（[appendExtraction]）。
     *
     * @param prefix 行の先頭（`RUN_DONE` または `RESULT`）。
     * @param agreement [prefix] が `RESULT` のときに出す一致（`null` なら `agree=NA`）。
     * @param pcmAgreement [prefix] が `RESULT` のときに出す PCM の一致（`null` なら `pcmSame=NA`）。
     */
    fun runLine(
        prefix: String,
        name: String,
        run: LidBenchRunResult,
        agreement: LidBenchAgreement?,
        withAgreement: Boolean,
        pcmAgreement: LidBenchPcmAgreement? = null,
    ): String = buildString {
        append(prefix)
        append(" name=").append(name)
        append(" engine=").append(run.engine.label)
        append(" mode=").append(run.mode.label)
        append(" ext=").append(run.extraction.label)
        append(" extUsed=").append(run.extractionUsed?.label ?: "-")
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
        appendExtraction(run)
        if (withAgreement) {
            if (agreement != null) {
                append(" agree=").append(fmt3(agreement.rate))
                append(" agreeN=").append(agreement.matches).append('/').append(agreement.total)
                append(" ref=").append(agreement.reference)
            } else {
                append(" agree=NA agreeN=- ref=-")
            }
            if (pcmAgreement != null) {
                append(" pcmSame=").append(pcmAgreement.matches).append('/').append(pcmAgreement.total)
                append(" pcmRef=").append(pcmAgreement.reference)
            } else {
                append(" pcmSame=NA pcmRef=-")
            }
        }
        run.note?.let { append(" note=").append(it) }
    }

    /**
     * 切り出しの内訳を足す。時間は 1 件あたり（[LidBenchRunResult.extractCount] で割る。`adaptMs`・`awaitMs` はチャンク数で割る）。
     *
     * - `exMs`: 切り出し全体（待ち行列の待ちを除く）。`exOpenMs`（抽出器を開く）+ `exPosMs`（位置合わせ）+ `codecNewMs`（MediaCodec の作成）
     *   + `codecWaitMs`（入力と出力のやり取り）+ `mixMs`（モノラル化）+ `rsMs`（リサンプル）+ `pcmMs`（16-bit への変換）
     *   + `codecRelMs`（MediaCodec の解放）+ `handoffMs`（受け渡し）+ `exOtherMs`（その他）。
     * - `adaptMs`: ファイル内の補正と結果の受け渡し。`awaitMs`: チャンクの順に結果を待った時間。
     * - 回数: `codecs`（MediaCodec を作った数）、`exOpens`（抽出器を開いた数）、`seeks`、`skipSamples`（圧縮のまま読み飛ばしたサンプル）、
     *   `decodedSamples`（デコードしたサンプル。ネイティブのレート）、`runs`・`windows`（順に流す切り出し）、`qWaitMs`（待ち行列の待ちの合計）、
     *   `fallbacks`（今の方式で切り出し直した数）。
     */
    private fun StringBuilder.appendExtraction(run: LidBenchRunResult) {
        val t = run.extract
        val count = run.extractCount
        fun perExtraction(nanos: Long): String = if (count > 0L) fmt2(nanos / NANOS_PER_MS / count) else "NA"
        fun perChunk(nanos: Long): String = if (run.chunkCount > 0) fmt2(nanos / NANOS_PER_MS / run.chunkCount) else "NA"
        append(" exN=").append(count)
        append(" exMs=").append(perExtraction(t.busyNanos))
        append(" exOpenMs=").append(perExtraction(t.extractorOpenNanos))
        append(" exPosMs=").append(perExtraction(t.positionNanos))
        append(" codecNewMs=").append(perExtraction(t.codecCreateNanos))
        append(" codecWaitMs=").append(perExtraction(t.codecWaitNanos))
        append(" mixMs=").append(perExtraction(t.mixNanos))
        append(" rsMs=").append(perExtraction(t.resampleOnlyNanos))
        append(" pcmMs=").append(perExtraction(t.pcmNanos))
        append(" codecRelMs=").append(perExtraction(t.codecReleaseNanos))
        append(" handoffMs=").append(perExtraction(t.handoffNanos))
        append(" exOtherMs=").append(perExtraction(t.otherNanos))
        append(" adaptMs=").append(perChunk(t.adaptNanos))
        append(" awaitMs=").append(perChunk(t.awaitNanos))
        append(" codecs=").append(t.codecCreates)
        append(" exOpens=").append(t.extractorOpens)
        append(" seeks=").append(t.seeks)
        append(" skipSamples=").append(t.skippedSamples)
        append(" decodedSamples=").append(t.decodedNativeSamples)
        append(" runs=").append(t.streamRuns)
        append(" windows=").append(t.streamWindows)
        append(" qWaitMs=").append(fmt1(t.queueWaitNanos / NANOS_PER_MS))
        append(" fallbacks=").append(t.fallbacks)
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
        return "DIFF engine=${run.engine.label} mode=${run.mode.label} ext=${run.extraction.label} ref=${reference.key} " +
            "count=${diffs.size} chunks=$items"
    }

    /**
     * [run] の切り出した PCM が [reference] と違うチャンクを 1 行にする（最初の [MAX_DIFF_ITEMS] 件。
     * `番号@開始ms:サンプル数/相手のサンプル数`。どちらも切り出せた場合は、絶対値の合計の差の割合 `:abs+0.012%` を付ける）。
     * 違いがなければ `null`。
     */
    fun pcmDiffLine(run: LidBenchRunResult, reference: LidBenchRunResult, chunks: List<ChunkTimeRange>): String? {
        if (run.status != LidBenchStatus.OK || run.pcm.isEmpty() || run.pcm.size != reference.pcm.size) return null
        val diffs = run.pcm.indices.filter { run.pcm[it] != reference.pcm[it] }
        if (diffs.isEmpty()) return null
        val items = diffs.take(MAX_DIFF_ITEMS).joinToString(",") { index ->
            val startMs = chunks.getOrNull(index)?.startMs ?: -1L
            val print = run.pcm[index]
            val ref = reference.pcm[index]
            val level = if (print != null && ref != null && ref.sumAbs > 0L) {
                ":abs" + String.format(Locale.ROOT, "%+.3f%%", (print.sumAbs - ref.sumAbs) * 100.0 / ref.sumAbs)
            } else {
                ""
            }
            "$index@${startMs}ms:${print?.samples ?: "-"}/${ref?.samples ?: "-"}$level"
        }
        return "PCMDIFF engine=${run.engine.label} mode=${run.mode.label} ext=${run.extraction.label} ref=${reference.key} " +
            "count=${diffs.size} chunks=$items"
    }

    /** 結果の表（見出しと各回の行。等幅で読める形）。指定と違う切り出しの方式を使った回は `str>par` のように出す。 */
    fun tableLines(
        runs: List<LidBenchRunResult>,
        agreements: List<LidBenchAgreement?>,
        pcmAgreements: List<LidBenchPcmAgreement?> = emptyList(),
    ): List<String> {
        val header = String.format(
            Locale.ROOT,
            TABLE_FORMAT,
            "engine", "mode", "ext", "status", "chunks", "totalMs", "ms/chunk", "inferMed", "inferMean",
            "engMean", "melMean", "exMs", "workers", "thermal", "pcm", "agree",
        )
        val rows = runs.mapIndexed { index, run ->
            val agreement = agreements.getOrNull(index)
            val pcmAgreement = pcmAgreements.getOrNull(index)
            String.format(
                Locale.ROOT,
                TABLE_FORMAT,
                run.engine.label,
                run.mode.label,
                extractionLabel(run),
                run.status.name,
                run.chunkCount.toString(),
                fmt1(run.totalMs),
                fmt1(run.perChunkMs),
                fmt1(median(run.inferMs)),
                fmt1(mean(run.inferMs)),
                fmt1(run.engineMeanMs),
                fmt2(run.melMeanMs),
                fmt1(run.extractMsPerChunk),
                if (run.degraded) "${run.workers}!" else run.workers.toString(),
                "${run.thermalStart}->${run.thermalEnd}",
                pcmAgreement?.let { "${it.matches}/${it.total}" } ?: "-",
                agreement?.let { fmt3(it.rate) } ?: "-",
            )
        }
        return listOf("TABLE $header") + rows.map { "TABLE $it" }
    }

    /** 切り出しの方式と進め方ごとの、純 Kotlin と ONNX Runtime の比（同じ切り出しの方式・進め方で、どちらも OK の回があるときだけ）。 */
    fun summaryLines(runs: List<LidBenchRunResult>): List<String> = runs.map { it.extraction }.distinct().flatMap { extraction ->
        LidBenchMode.entries.mapNotNull { mode ->
            fun find(engine: LidBenchEngine) = runs.firstOrNull {
                it.engine == engine && it.mode == mode && it.extraction == extraction && it.status == LidBenchStatus.OK
            }
            val kotlin = find(LidBenchEngine.KOTLIN)
            val onnx = find(LidBenchEngine.ONNX)
            if (kotlin == null || onnx == null) return@mapNotNull null
            val kotlinInfer = median(kotlin.inferMs)
            val onnxInfer = median(onnx.inferMs)
            buildString {
                append("SUMMARY mode=").append(mode.label)
                append(" ext=").append(extraction.label)
                append(" kotlinTotalMs=").append(fmt1(kotlin.totalMs))
                append(" onnxTotalMs=").append(fmt1(onnx.totalMs))
                append(" totalSpeedup=").append(fmtRatio(kotlin.totalMs, onnx.totalMs))
                append(" kotlinInferMedianMs=").append(fmt1(kotlinInfer))
                append(" onnxInferMedianMs=").append(fmt1(onnxInfer))
                append(" inferSpeedup=").append(fmtRatio(kotlinInfer, onnxInfer))
            }
        }
    }

    /**
     * 速い切り出しの方式の回ごとの、今の方式の回（同じエンジン・同じ進め方）との比と一致（どちらも OK のときだけ）。
     *
     * `totalSpeedup` は全体時間の比（今の方式 ÷ この方式）。`exMs` は 1 件あたりの切り出しの時間、`agree` は最終判定の一致、
     * `pcmSame` は切り出した PCM が同じだったチャンク数。
     */
    fun extractionSummaryLines(runs: List<LidBenchRunResult>): List<String> = runs.mapNotNull { run ->
        if (run.extraction == LidExtractionMethod.CURRENT || run.status != LidBenchStatus.OK) return@mapNotNull null
        val current = runs.firstOrNull {
            it.engine == run.engine && it.mode == run.mode && it.extraction == LidExtractionMethod.CURRENT && it.status == LidBenchStatus.OK
        } ?: return@mapNotNull null
        val agreement = agreement(run, current)
        val pcmAgreement = pcmAgreement(run, current)
        buildString {
            append("SUMMARY_EXT engine=").append(run.engine.label)
            append(" mode=").append(run.mode.label)
            append(" ext=").append(run.extraction.label)
            append(" extUsed=").append(run.extractionUsed?.label ?: "-")
            append(" curTotalMs=").append(fmt1(current.totalMs))
            append(" totalMs=").append(fmt1(run.totalMs))
            append(" totalSpeedup=").append(fmtRatio(current.totalMs, run.totalMs))
            append(" curPerChunkMs=").append(fmt1(current.perChunkMs))
            append(" perChunkMs=").append(fmt1(run.perChunkMs))
            append(" curExMs=").append(fmt1(current.extractMsPerChunk))
            append(" exMs=").append(fmt1(run.extractMsPerChunk))
            append(" agree=").append(agreement?.let { fmt3(it.rate) } ?: "NA")
            append(" agreeN=").append(agreement?.let { "${it.matches}/${it.total}" } ?: "-")
            append(" pcmSame=").append(pcmAgreement?.let { "${it.matches}/${it.total}" } ?: "NA")
        }
    }

    /** 小数 1 桁（`null` は `NA`）。 */
    fun fmt1(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "NA"

    /** 小数 2 桁（`null` は `NA`）。 */
    fun fmt2(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "NA"

    private fun fmt3(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private fun fmtHeadroom(value: Float): String = if (value.isNaN()) "NaN" else String.format(Locale.ROOT, "%.2f", value)

    private fun fmtRatio(numerator: Double?, denominator: Double?): String = if (numerator == null || denominator == null || denominator <= 0.0) "NA" else String.format(Locale.ROOT, "%.1fx", numerator / denominator)

    /** 表の切り出しの方式の欄（指定と違う方式を使った回は `指定>実際`）。 */
    private fun extractionLabel(run: LidBenchRunResult): String {
        val used = run.extractionUsed
        return if (used != null && used != run.extraction) "${run.extraction.label}>${used.label}" else run.extraction.label
    }

    private const val NANOS_PER_MS: Double = 1_000_000.0

    /** [diffLine]・[pcmDiffLine] に出す件数の上限。 */
    private const val MAX_DIFF_ITEMS: Int = 20

    private const val TABLE_FORMAT: String = "%-6s %-4s %-7s %-12s %6s %9s %8s %8s %9s %8s %7s %7s %7s %-7s %-9s %s"
}
