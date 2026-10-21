package com.miuraken.chunkflow.core.media.bench

import com.miuraken.chunkflow.core.media.AudioStreamDecoder
import com.miuraken.chunkflow.core.media.ChunkPcmExtractor
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriber
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/** 計測結果（JSON Lines）の書き出し先。 */
internal fun interface SttBenchRecordSink {
    /** JSON の 1 行を書く。 */
    fun write(line: String)
}

/**
 * 端末内の音声認識エンジンを、言語ごと・エンジンごとに実機で測る（debug ビルドの計測ツール）。
 *
 * 本番の「より詳しく文字起こし」と同じ経路（[OnDeviceChunkTranscriber.transcribeChunkTrial]、学習言語だけを指定、
 * エンジンを 1 つに固定して切り替えない）でクリップを 1 件ずつ文字起こしし、かかった時間と本文を
 * [records]（JSON Lines）に書く。精度（CER / WER）は、書き出した本文を PC 側のスクリプト（`tools/stt-bench/`）で正解と比べて求める。
 *
 * - [SttBenchMode.STATUS]: 言語ごと・エンジンごとのモデルの状態を出す。
 * - [SttBenchMode.DOWNLOAD]: 足りないモデルの取得を要求し、使えるようになるまで待つ。
 * - [SttBenchMode.LID]: クリップごとに 2 言語のどちらかを言語判定だけで判定する（正解の言語の付与に使う）。
 * - [SttBenchMode.RUN]: エンジン × 言語の順に、クールダウン → ウォームアップ（モデルの読み込みを計測から外す）→ 計測。
 *
 * @param inputDir クリップ一覧とクリップを置いたディレクトリ。
 * @param clips クリップ一覧（[SttBenchMode.LID] と [SttBenchMode.RUN] で使う）。
 * @param deviceInfo 結果の先頭に書く端末の情報。
 * @param decoder クリップの PCM の切り出しだけにかかる時間を測るためのデコーダ（`null` なら測らない）。
 * @param memoryMb プロセスのメモリ（PSS、MB）。エンジン × 言語の回の終わりに記録する。
 * @param isForeground 画面が前面にあるか（前面でなかった計測に印を付ける）。
 * @param sleep クールダウンで待つ（テストで差し替える）。
 */
internal class SttBenchRunner(
    private val transcriber: OnDeviceChunkTranscriber,
    private val config: SttBenchConfig,
    private val inputDir: File,
    private val clips: List<SttBenchClip>,
    private val output: LidBenchOutput,
    private val records: SttBenchRecordSink,
    private val deviceState: LidBenchDeviceState,
    private val deviceInfo: Map<String, Any?> = emptyMap(),
    private val decoder: AudioStreamDecoder? = null,
    private val memoryMb: () -> Double = { Double.NaN },
    private val isForeground: () -> Boolean = { true },
    private val nanoTime: () -> Long = { System.nanoTime() },
    private val now: () -> Instant = { Instant.now() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** 計測に使えると確かめたクリップ。 */
    private class PreparedClip(
        val clip: SttBenchClip,
        val file: File,
        val durationMs: Long,
    )

    /** 1 回の文字起こしの結果。 */
    private class TrialOutcome(
        val status: String,
        val elapsedMs: Double,
        val text: String = "",
        val engineId: String? = null,
        val language: String? = null,
        val confidence: Float? = null,
        val background: Boolean = false,
        val error: String? = null,
    )

    /** 設定の動作を最後まで行う。 */
    suspend fun run() {
        val startNanos = nanoTime()
        output.emit(LidBenchLevel.INFO, "START mode=${config.mode.label} tag=${config.tag} engines=${config.engines.joinToString(",") { it.label }}")
        when (config.mode) {
            SttBenchMode.STATUS -> runStatus()
            SttBenchMode.DOWNLOAD -> runDownload()
            SttBenchMode.LID -> runLid()
            SttBenchMode.RUN -> runTranscription()
        }
        val elapsedSec = (nanoTime() - startNanos) / 1e9
        records.write(SttBenchJson.line("type" to "footer", "finishedAt" to now().toString(), "elapsedSec" to elapsedSec))
        output.emit(LidBenchLevel.INFO, "END mode=${config.mode.label} elapsedSec=${fmt1(elapsedSec)}")
    }

    private fun writeHeader(extra: List<Pair<String, Any?>>) {
        val fields = ArrayList<Pair<String, Any?>>()
        fields += "type" to "header"
        fields += "mode" to config.mode.label
        fields += "tag" to config.tag
        fields += "startedAt" to now().toString()
        fields += "engines" to config.engines.map { it.label }
        fields += "warmup" to config.warmup
        fields += "cooldownSec" to config.cooldownSec
        fields += "thermal" to deviceState.thermalStatus()
        fields += "powerSave" to deviceState.isPowerSaveMode()
        deviceInfo.forEach { (key, value) -> fields += key to value }
        fields += extra
        records.write(SttBenchJson.line(*fields.toTypedArray()))
    }

    private suspend fun queryInfos(languages: List<String>): Map<String, OnDeviceLanguageModelInfo> {
        val infos = try {
            transcriber.queryLanguageModelInfos(languages.map(::LanguageTag))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            output.emit(LidBenchLevel.WARN, "WARN モデルの状態を問い合わせられない: ${e.javaClass.simpleName}")
            emptyList()
        }
        val byLanguage = infos.associateBy { it.language.value }
        return languages.associateWith { language ->
            (byLanguage[language] ?: OnDeviceLanguageModelInfo(language = LanguageTag(language))).enrichedWithCatalog()
        }
    }

    // ---- STATUS ----

    private suspend fun runStatus() {
        writeHeader(listOf("langs" to config.languages))
        val infos = queryInfos(config.languages)
        for (language in config.languages) {
            val info = infos.getValue(language)
            for (engine in config.engines) {
                val status = info.statusOf(engine.sttEngine)
                output.emit(
                    LidBenchLevel.INFO,
                    "STATUS lang=$language engine=${engine.label} status=$status mlKitMode=${info.mlKitMode} bcp47=${info.resolvedBcp47Tag}",
                )
                records.write(
                    SttBenchJson.line(
                        "type" to "status",
                        "lang" to language,
                        "engine" to engine.label,
                        "status" to status.name,
                        "mlKitMode" to info.mlKitMode?.name,
                        "bcp47" to info.resolvedBcp47Tag,
                    ),
                )
            }
        }
        val modelStates = try {
            transcriber.queryDownloadableModelStates()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            output.emit(LidBenchLevel.WARN, "WARN ダウンロード可能なモデルの状態を問い合わせられない: ${e.javaClass.simpleName}")
            emptyList()
        }
        for (state in modelStates) {
            val downloaded = state.downloadedLanguages.joinToString(",") { it.value }
            output.emit(LidBenchLevel.INFO, "MODEL model=${state.model.name} status=${state.status} downloaded=$downloaded")
            records.write(
                SttBenchJson.line(
                    "type" to "model",
                    "model" to state.model.name,
                    "status" to state.status.name,
                    "sizeBytes" to state.sizeBytes,
                    "downloaded" to state.downloadedLanguages.map { it.value },
                ),
            )
        }
    }

    // ---- DOWNLOAD ----

    private suspend fun runDownload() {
        writeHeader(listOf("langs" to config.languages, "downloadWaitSec" to config.downloadWaitSec))
        val infos = queryInfos(config.languages)
        val pending = LinkedHashSet<Pair<String, SttBenchEngine>>()
        for (language in config.languages) {
            val info = infos.getValue(language)
            for (engine in config.engines) {
                when (val status = info.statusOf(engine.sttEngine)) {
                    OnDeviceLanguageModelStatus.UNSUPPORTED -> {
                        output.emit(LidBenchLevel.INFO, "DOWNLOAD_SKIP lang=$language engine=${engine.label} status=$status")
                    }

                    OnDeviceLanguageModelStatus.INSTALLED -> {
                        output.emit(LidBenchLevel.INFO, "DOWNLOAD_READY lang=$language engine=${engine.label}")
                    }

                    else -> {
                        val startNanos = nanoTime()
                        output.emit(LidBenchLevel.INFO, "DOWNLOAD_REQUEST lang=$language engine=${engine.label} status=$status")
                        val requested = try {
                            transcriber.requestSttModelDownload(LanguageTag(language), engine.sttEngine)
                        } catch (ce: CancellationException) {
                            throw ce
                        } catch (e: Exception) {
                            output.emit(LidBenchLevel.WARN, "WARN 取得の要求で例外: ${e.javaClass.simpleName}")
                            false
                        }
                        val elapsedMs = (nanoTime() - startNanos) / 1e6
                        output.emit(
                            LidBenchLevel.INFO,
                            "DOWNLOAD_RESULT lang=$language engine=${engine.label} requested=$requested ms=${fmt1(elapsedMs)}",
                        )
                        records.write(
                            SttBenchJson.line(
                                "type" to "download",
                                "lang" to language,
                                "engine" to engine.label,
                                "requested" to requested,
                                "ms" to elapsedMs,
                            ),
                        )
                        pending += language to engine
                    }
                }
            }
        }
        val deadline = nanoTime() + config.downloadWaitSec * NANOS_PER_SECOND
        while (pending.isNotEmpty() && nanoTime() < deadline) {
            sleep(DOWNLOAD_POLL_MS)
            val refreshed = queryInfos(pending.map { it.first }.distinct())
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val (language, engine) = iterator.next()
                if (refreshed.getValue(language).statusOf(engine.sttEngine) == OnDeviceLanguageModelStatus.INSTALLED) {
                    output.emit(LidBenchLevel.INFO, "DOWNLOAD_READY lang=$language engine=${engine.label}")
                    iterator.remove()
                }
            }
        }
        if (pending.isNotEmpty()) {
            val refreshed = queryInfos(pending.map { it.first }.distinct())
            for ((language, engine) in pending) {
                val status = refreshed.getValue(language).statusOf(engine.sttEngine)
                output.emit(LidBenchLevel.WARN, "DOWNLOAD_NOT_READY lang=$language engine=${engine.label} status=$status")
            }
        }
    }

    // ---- LID ----

    private suspend fun runLid() {
        writeHeader(listOf("clips" to clips.size))
        var done = 0
        for (clip in clips) {
            val pair = clip.pair
            if (pair == null) {
                output.emit(LidBenchLevel.WARN, "LID_SKIP clip=${clip.id} pair がない")
                continue
            }
            val prepared = prepare(clip, measureExtraction = false) ?: continue
            val languagePair = LanguagePair(target = LanguageTag(pair.first), base = LanguageTag(pair.second))
            val startNanos = nanoTime()
            val result = try {
                transcriber.detectChunkLanguage(prepared.file, 0L, prepared.durationMs, languagePair)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                output.emit(LidBenchLevel.WARN, "WARN 言語判定で例外 clip=${clip.id}: ${e.javaClass.simpleName}")
                null
            }
            val elapsedMs = (nanoTime() - startNanos) / 1e6
            records.write(
                SttBenchJson.line(
                    "type" to "lid",
                    "clip" to clip.id,
                    "pair" to "${pair.first},${pair.second}",
                    "audioMs" to prepared.durationMs,
                    "detected" to result?.language?.value,
                    "confidence" to result?.confidence,
                    "ms" to elapsedMs,
                ),
            )
            done++
            if (done % PROGRESS_EVERY == 0) output.emit(LidBenchLevel.INFO, "LID_PROGRESS $done/${clips.size}")
        }
        output.emit(LidBenchLevel.INFO, "LID_DONE clips=$done")
    }

    // ---- RUN ----

    private suspend fun runTranscription() {
        val selected = clips.filter { config.languages.isEmpty() || it.language in config.languages }
        val grouped = LinkedHashMap<String, MutableList<SttBenchClip>>()
        for (clip in selected) grouped.getOrPut(clip.language) { ArrayList() } += clip
        val limited = grouped.mapValues { (_, list) ->
            if (config.maxClipsPerLanguage > 0) list.take(config.maxClipsPerLanguage) else list
        }
        writeHeader(
            listOf(
                "langs" to limited.keys.toList(),
                "clips" to limited.values.sumOf { it.size },
                "maxClipsPerLanguage" to config.maxClipsPerLanguage,
            ),
        )
        val prepared = LinkedHashMap<String, List<PreparedClip>>()
        for ((language, list) in limited) {
            prepared[language] = list.mapNotNull { prepare(it, measureExtraction = true) }
        }
        val infos = queryInfos(prepared.keys.toList())
        for (engine in config.engines) {
            for ((language, list) in prepared) {
                if (list.isEmpty()) continue
                val status = infos.getValue(language).statusOf(engine.sttEngine)
                if (status != OnDeviceLanguageModelStatus.INSTALLED) {
                    output.emit(LidBenchLevel.INFO, "SKIP engine=${engine.label} lang=$language status=$status")
                    records.write(
                        SttBenchJson.line("type" to "skip", "engine" to engine.label, "lang" to language, "status" to status.name),
                    )
                    continue
                }
                measureGroup(engine, language, list)
            }
        }
    }

    private suspend fun measureGroup(engine: SttBenchEngine, language: String, list: List<PreparedClip>) {
        coolDown()
        val thermalBefore = deviceState.thermalStatus()
        // モデルを読み込む前のメモリ（ウォームアップ後との差が、そのエンジンで新しく読み込んだモデルの大きさの目安になる）
        val pssBefore = memoryMb()
        output.emit(LidBenchLevel.INFO, "GROUP_START engine=${engine.label} lang=$language clips=${list.size} thermal=$thermalBefore")
        if (config.warmup) {
            val first = list.first()
            val warm = trial(engine, language, first)
            output.emit(LidBenchLevel.INFO, "WARMUP engine=${engine.label} lang=$language ms=${fmt1(warm.elapsedMs)} status=${warm.status}")
            records.write(
                SttBenchJson.line(
                    "type" to "warmup",
                    "engine" to engine.label,
                    "lang" to language,
                    "clip" to first.clip.id,
                    "audioMs" to first.durationMs,
                    "ms" to warm.elapsedMs,
                    "status" to warm.status,
                    "error" to warm.error,
                ),
            )
        }
        val latencies = ArrayList<Double>()
        var okCount = 0
        var audioMs = 0L
        for ((index, item) in list.withIndex()) {
            val outcome = trial(engine, language, item)
            if (outcome.status == STATUS_OK) okCount++
            latencies += outcome.elapsedMs
            audioMs += item.durationMs
            records.write(
                SttBenchJson.line(
                    "type" to "result",
                    "engine" to engine.label,
                    "lang" to language,
                    "clip" to item.clip.id,
                    "audioMs" to item.durationMs,
                    "ms" to outcome.elapsedMs,
                    "status" to outcome.status,
                    "text" to outcome.text,
                    "engineId" to outcome.engineId,
                    "langOut" to outcome.language,
                    "confidence" to outcome.confidence,
                    "thermal" to deviceState.thermalStatus(),
                    "bg" to outcome.background,
                    "error" to outcome.error,
                ),
            )
            output.emit(
                LidBenchLevel.INFO,
                "RESULT engine=${engine.label} lang=$language ${index + 1}/${list.size} clip=${item.clip.id} " +
                    "ms=${fmt1(outcome.elapsedMs)} audioMs=${item.durationMs} status=${outcome.status} chars=${outcome.text.length}",
            )
        }
        val median = median(latencies)
        val totalMs = latencies.sum()
        val rtf = if (audioMs > 0L) totalMs / audioMs else Double.NaN
        val thermalAfter = deviceState.thermalStatus()
        val pss = memoryMb()
        records.write(
            SttBenchJson.line(
                "type" to "group",
                "engine" to engine.label,
                "lang" to language,
                "clips" to list.size,
                "ok" to okCount,
                "medianMs" to median,
                "totalMs" to totalMs,
                "audioMs" to audioMs,
                "rtf" to rtf,
                "thermalBefore" to thermalBefore,
                "thermalAfter" to thermalAfter,
                "pssBeforeMb" to pssBefore,
                "pssMb" to pss,
            ),
        )
        output.emit(
            LidBenchLevel.INFO,
            "GROUP_DONE engine=${engine.label} lang=$language ok=$okCount/${list.size} medianMs=${fmt1(median)} " +
                "rtf=${fmt3(rtf)} thermal=$thermalBefore->$thermalAfter pssMb=${fmt1(pssBefore)}->${fmt1(pss)}",
        )
    }

    private suspend fun trial(engine: SttBenchEngine, language: String, item: PreparedClip): TrialOutcome {
        val foregroundBefore = isForeground()
        val startNanos = nanoTime()
        val result = try {
            transcriber.transcribeChunkTrial(
                audioFile = item.file,
                startMs = 0L,
                endMs = item.durationMs,
                languagePair = LanguagePair(target = LanguageTag(language), base = null),
                engineOverride = engine.trialEngine,
                audioSpeed = 1.0f,
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return TrialOutcome(
                status = STATUS_ERROR,
                elapsedMs = (nanoTime() - startNanos) / 1e6,
                background = !foregroundBefore || !isForeground(),
                error = e.javaClass.simpleName,
            )
        }
        val elapsedMs = (nanoTime() - startNanos) / 1e6
        val status = when {
            result == null -> STATUS_NULL
            result.text.isBlank() -> STATUS_EMPTY
            else -> STATUS_OK
        }
        return TrialOutcome(
            status = status,
            elapsedMs = elapsedMs,
            text = result?.text?.trim().orEmpty(),
            engineId = result?.engineId?.value,
            language = result?.language?.value,
            confidence = result?.confidence,
            background = !foregroundBefore || !isForeground(),
        )
    }

    /** [clip] のファイルを確かめ、長さを読む（使えなければ記録して `null`）。 */
    private suspend fun prepare(clip: SttBenchClip, measureExtraction: Boolean): PreparedClip? {
        val file = File(inputDir, clip.relativePath)
        val canonicalDir = inputDir.canonicalFile
        val canonicalFile = file.canonicalFile
        if (!canonicalFile.path.startsWith(canonicalDir.path + File.separator)) {
            clipError(clip, "入力のディレクトリの外を指している")
            return null
        }
        if (!canonicalFile.isFile || canonicalFile.length() > MAX_CLIP_BYTES) {
            clipError(clip, "ファイルがない・大きすぎる")
            return null
        }
        val wav = SttBenchWav.read(canonicalFile)
        if (wav == null || wav.durationMs < MIN_CLIP_MS || wav.durationMs > MAX_CLIP_MS) {
            clipError(clip, "WAV として読めない・長さが範囲外 (${wav?.durationMs})")
            return null
        }
        var extractMs: Double? = null
        val activeDecoder = decoder
        if (measureExtraction && activeDecoder != null) {
            val startNanos = nanoTime()
            val pcm = try {
                ChunkPcmExtractor.extractChunkPcm16LeBytes(activeDecoder, canonicalFile, 0L, wav.durationMs)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            extractMs = (nanoTime() - startNanos) / 1e6
            if (pcm == null) {
                clipError(clip, "PCM を切り出せない")
                return null
            }
        }
        records.write(
            SttBenchJson.line(
                "type" to "clip",
                "clip" to clip.id,
                "lang" to clip.language,
                "file" to clip.relativePath,
                "audioMs" to wav.durationMs,
                "sampleRate" to wav.sampleRateHz,
                "channels" to wav.channels,
                "extractMs" to extractMs,
            ),
        )
        return PreparedClip(clip = clip, file = canonicalFile, durationMs = wav.durationMs)
    }

    private fun clipError(clip: SttBenchClip, reason: String) {
        output.emit(LidBenchLevel.WARN, "CLIP_ERROR clip=${clip.id} $reason")
        records.write(SttBenchJson.line("type" to "clipError", "clip" to clip.id, "reason" to reason))
    }

    /** クールダウンで待ち、端末の温度が MODERATE 以上なら下がるまで（上限まで）待つ。 */
    private suspend fun coolDown() {
        if (config.cooldownSec > 0) sleep(config.cooldownSec * MILLIS_PER_SECOND)
        if (config.thermalWaitSec <= 0) return
        val deadline = nanoTime() + config.thermalWaitSec * NANOS_PER_SECOND
        var waited = false
        while (deviceState.thermalStatus() >= THERMAL_STATUS_MODERATE && nanoTime() < deadline) {
            if (!waited) output.emit(LidBenchLevel.INFO, "THERMAL_WAIT status=${deviceState.thermalStatus()}")
            waited = true
            sleep(THERMAL_POLL_MS)
        }
    }

    companion object {
        /** 文字起こしできた。 */
        const val STATUS_OK: String = "ok"

        /** 結果はあったが本文が空。 */
        const val STATUS_EMPTY: String = "empty"

        /** 結果がなかった（エンジンが使えない・認識できないなど）。 */
        const val STATUS_NULL: String = "null"

        /** 例外で止まった。 */
        const val STATUS_ERROR: String = "error"

        /** `PowerManager.THERMAL_STATUS_MODERATE`。 */
        private const val THERMAL_STATUS_MODERATE: Int = 2

        private const val MILLIS_PER_SECOND: Long = 1_000L
        private const val NANOS_PER_SECOND: Long = 1_000_000_000L
        private const val THERMAL_POLL_MS: Long = 5_000L
        private const val DOWNLOAD_POLL_MS: Long = 5_000L
        private const val PROGRESS_EVERY: Int = 20

        /** クリップの長さの下限・上限（ミリ秒）。 */
        private const val MIN_CLIP_MS: Long = 200L
        private const val MAX_CLIP_MS: Long = 60_000L

        /** クリップのファイルの大きさの上限（16kHz モノラル 16-bit で約 60 秒分に余裕を持たせる）。 */
        private const val MAX_CLIP_BYTES: Long = 8L * 1024L * 1024L

        internal fun median(values: List<Double>): Double {
            if (values.isEmpty()) return Double.NaN
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
        }

        private fun fmt1(value: Double): String = if (value.isNaN()) "NaN" else String.format(Locale.ROOT, "%.1f", value)

        private fun fmt3(value: Double): String = if (value.isNaN()) "NaN" else String.format(Locale.ROOT, "%.3f", value)
    }
}
