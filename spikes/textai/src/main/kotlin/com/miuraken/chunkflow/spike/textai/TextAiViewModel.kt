package com.miuraken.chunkflow.spike.textai

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.miuraken.chunkflow.core.model.SupportedLanguageCatalog
import java.io.File
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * スパイク S4 の試作の状態と処理。
 *
 * - ML Kit の呼び出しは 1 つずつ順に行う（GenAI は同時に呼ぶと `BUSY` になるため）。ほかの処理の実行中は、新しい処理を始めない。
 * - 結果の文（訳・案・理由）は画面にだけ出し、CSV とログには文字数・時間・結果の種類だけを書く（AGENTS.md §10）。
 * - 「全部を順に実行」は、アプリが裏に回っても（画面を消しても）続け、各回の結果に前面かどうか・画面がついているかを記録する。
 *
 * @param ioDispatcher ファイルの読み書きに使う（呼び出し側から注入する）。
 */
internal class TextAiViewModel(
    application: Application,
    private val ioDispatcher: CoroutineDispatcher,
) : AndroidViewModel(application) {

    private val appContext: Context = application.applicationContext
    private val clock: () -> Long = SystemClock::elapsedRealtime
    private val reader = TranscriptFileReader(appContext.contentResolver, ioDispatcher)
    private val recorder = SpikeRecorder(csvDirectory(appContext), ioDispatcher, viewModelScope)
    private val translation = TranslationEngine(appContext, ioDispatcher, clock)
    private val proofread = ProofreadEngine(appContext)
    private val prompt = PromptEngine()
    private val powerManager: PowerManager? = appContext.getSystemService(PowerManager::class.java)

    private val mutableState = MutableStateFlow(TextAiUiState(csvDirectory = recorder.directoryPath))

    /** 画面の状態。 */
    val state: StateFlow<TextAiUiState> = mutableState.asStateFlow()

    private var activeJob: Job? = null
    private var appForeground = true
    private var batchCounter = 0

    init {
        record(CsvRow(event = CsvEvent.SESSION_START, foreground = true, screenOn = isScreenOn()))
    }

    fun onEvent(event: SpikeEvent) {
        when (event) {
            // ファイル選択は Activity が開く
            SpikeEvent.OpenFile -> Unit

            SpikeEvent.LoadSample -> launchExclusive(BusyKind.LOADING_FILE) { loadSampleFile() }

            is SpikeEvent.SelectTarget -> selectLanguages(target = event.language, base = state.value.base)

            is SpikeEvent.SelectBase -> selectLanguages(target = state.value.target, base = event.language)

            is SpikeEvent.SetIncludeUnknown -> {
                mutableState.update { it.copy(includeUnknown = event.include) }
                rebuildRows()
            }

            is SpikeEvent.SetMethodEnabled -> mutableState.update {
                it.copy(enabledMethods = if (event.enabled) it.enabledMethods + event.method else it.enabledMethods - event.method)
            }

            SpikeEvent.RefreshStatus -> launchExclusive(BusyKind.CHECKING_STATUS) { refreshStatuses() }

            is SpikeEvent.Download -> launchExclusive(BusyKind.DOWNLOADING) { download(event.method) }

            is SpikeEvent.RunChunk -> launchExclusive(BusyKind.RUNNING_CHUNK) { runChunk(event.chunkNumber) }

            SpikeEvent.RunAll -> launchExclusive(BusyKind.RUNNING_ALL) { runAll() }

            SpikeEvent.Stop -> activeJob?.cancel()

            is SpikeEvent.Rate -> rate(event.key, event.rating)

            SpikeEvent.ClearResults -> clearResults()

            SpikeEvent.SaveSummary -> saveSummary()

            SpikeEvent.DismissMessage -> mutableState.update { it.copy(message = null) }
        }
    }

    /** ファイル選択で選ばれたファイルを読む。 */
    private suspend fun loadSampleFile() {
        val bytes = SampleTranscript.bytes()
        when (val parsed = com.miuraken.chunkflow.core.transcript.CompositeTranscriptParser().parse(bytes, SampleTranscript.FILE_NAME)) {
            is com.miuraken.chunkflow.core.transcript.TranscriptParseResult.Error -> {
                mutableState.update { it.copy(loadError = LoadError.INVALID) }
            }

            is com.miuraken.chunkflow.core.transcript.TranscriptParseResult.Ok -> {
                handleLoadedTranscript(parsed.document, "sample-id")
            }
        }
    }

    private fun handleLoadedTranscript(document: com.miuraken.chunkflow.core.transcript.TranscriptDocument, fileId: String) {
        if (document.segments.isEmpty()) {
            mutableState.update { it.copy(loadError = LoadError.NO_SEGMENTS) }
            return
        }
        val languages = document.languages
        val kind = if (languages != null) FileKind.CHUNKFLOW_JSON else FileKind.SRT
        val segments = document.segments.mapIndexed { index, segment ->
            Segment(
                number = index + 1,
                startMs = segment.startMs,
                text = segment.text,
                fileLanguage = segment.language?.value?.let(::primaryLanguage),
            )
        }
        val transcript = LoadedTranscript(
            kind = kind,
            fileId = fileId,
            segments = segments,
            fileTarget = languages?.target?.value?.let(::primaryLanguage),
            fileBase = languages?.base?.value?.let(::primaryLanguage),
        )

        mutableState.update { current ->
            val target = transcript.fileTarget ?: current.target
            val base = (transcript.fileBase ?: current.base)?.takeIf { it != target }
            current.copy(
                transcript = transcript,
                loadError = null,
                target = target,
                base = base,
                results = emptyMap(),
                ratings = emptyMap(),
                lastBatch = null,
            )
        }
        rebuildRows()
        val current = state.value
        record(
            CsvRow(
                event = CsvEvent.FILE_LOADED,
                fileKind = transcript.kind,
                fileId = transcript.fileId,
                target = current.target,
                base = current.base,
                count = transcript.segments.size,
                successCount = current.rows.size,
            ),
        )
        viewModelScope.launch { refreshStatuses() }
    }

    fun loadFile(uri: Uri) = launchExclusive(BusyKind.LOADING_FILE) {
        when (val outcome = reader.read(uri)) {
            is LoadOutcome.Failed -> mutableState.update { it.copy(loadError = outcome.error) }

            is LoadOutcome.Loaded -> {
                val transcript = outcome.transcript
                mutableState.update { current ->
                    val target = transcript.fileTarget ?: current.target
                    val base = (transcript.fileBase ?: current.base)?.takeIf { it != target }
                    current.copy(
                        transcript = transcript,
                        loadError = null,
                        target = target,
                        base = base,
                        results = emptyMap(),
                        ratings = emptyMap(),
                        lastBatch = null,
                    )
                }
                rebuildRows()
                val current = state.value
                record(
                    CsvRow(
                        event = CsvEvent.FILE_LOADED,
                        fileKind = transcript.kind,
                        fileId = transcript.fileId,
                        target = current.target,
                        base = current.base,
                        count = transcript.segments.size,
                        successCount = current.rows.size,
                    ),
                )
                refreshStatuses()
            }
        }
    }

    /** アプリが前面に来た・裏に回った（画面を消したときを含む）。 */
    fun onAppForegroundChanged(foreground: Boolean) {
        if (appForeground == foreground) return
        appForeground = foreground
        record(
            CsvRow(
                event = if (foreground) CsvEvent.APP_FOREGROUND else CsvEvent.APP_BACKGROUND,
                batch = if (state.value.busy == BusyKind.RUNNING_ALL) currentBatchId() else null,
                foreground = foreground,
                screenOn = isScreenOn(),
            ),
        )
    }

    override fun onCleared() {
        translation.close()
        proofread.close()
        prompt.close()
    }

    private fun selectLanguages(target: String?, base: String?) {
        if (state.value.busy != null) return
        val fixedTarget = state.value.transcript?.fileTarget
        val newTarget = fixedTarget ?: target
        val newBase = base?.takeIf { it != newTarget }
        mutableState.update { it.copy(target = newTarget, base = newBase, results = emptyMap(), ratings = emptyMap(), lastBatch = null) }
        rebuildRows()
        val current = state.value
        record(
            CsvRow(
                event = CsvEvent.LANGUAGES,
                fileKind = current.transcript?.kind,
                fileId = current.transcript?.fileId,
                target = current.target,
                base = current.base,
                count = current.rows.size,
                successCount = current.unknownCount,
            ),
        )
        launchExclusive(BusyKind.CHECKING_STATUS) { refreshStatuses() }
    }

    private fun rebuildRows() {
        mutableState.update { current ->
            val transcript = current.transcript
            val target = current.target
            if (transcript == null || target == null) {
                current.copy(rows = emptyList(), unknownCount = 0)
            } else {
                val built = buildRows(transcript, target, current.base, current.includeUnknown, ::englishName)
                current.copy(rows = built.rows, unknownCount = built.unknownCount)
            }
        }
    }

    // ---- 状態の確認とダウンロード ----

    private suspend fun refreshStatuses() {
        val target = state.value.target
        val base = state.value.base
        Method.entries.forEach { setStatus(it, EngineStatus(EngineState.CHECKING)) }

        val translateStatus = when {
            target == null || base == null -> EngineStatus(EngineState.NEEDS_LANGUAGE)

            !translation.isSupported(target, base) -> EngineStatus(EngineState.UNSUPPORTED)

            else -> statusOrError {
                val downloaded = translation.areModelsDownloaded(target, base)
                EngineStatus(if (downloaded) EngineState.AVAILABLE else EngineState.DOWNLOADABLE)
            }
        }
        setStatus(Method.TRANSLATE_API, translateStatus)

        val promptStatus = statusOrError {
            val feature = prompt.status()
            val name = if (feature == FeatureStatus.AVAILABLE) nameOrNull { prompt.modelName() } else null
            EngineStatus(featureState(feature), modelName = name)
        }
        setStatus(Method.PROMPT_TRANSLATE, if (target == null || base == null) promptStatus.copy(state = EngineState.NEEDS_LANGUAGE) else promptStatus)
        setStatus(Method.PROMPT_PROOFREAD, if (target == null) promptStatus.copy(state = EngineState.NEEDS_LANGUAGE) else promptStatus)

        val proofreadLanguage = target?.let(proofread::languageOf)
        val proofreadStatus = when {
            target == null -> EngineStatus(EngineState.NEEDS_LANGUAGE)

            proofreadLanguage == null -> EngineStatus(EngineState.UNSUPPORTED)

            else -> statusOrError {
                val feature = proofread.status(proofreadLanguage)
                val name = if (feature == FeatureStatus.AVAILABLE) nameOrNull { proofread.modelName(proofreadLanguage) } else null
                EngineStatus(featureState(feature), modelName = name)
            }
        }
        setStatus(Method.PROOFREAD_API, proofreadStatus)

        val current = state.value
        for (method in Method.entries) {
            val status = current.statuses[method] ?: continue
            record(
                CsvRow(
                    event = CsvEvent.STATUS,
                    fileId = current.transcript?.fileId,
                    target = current.target,
                    base = current.base,
                    method = method,
                    featureStatus = status.state.name,
                    model = status.modelName,
                    errorCode = status.errorCode,
                    foreground = appForeground,
                    screenOn = isScreenOn(),
                ),
            )
        }
    }

    private suspend fun download(method: Method) {
        val current = state.value
        val target = current.target ?: return
        val base = current.base
        val started = clock()
        record(downloadRow(CsvEvent.DOWNLOAD_START, method))
        try {
            when (method) {
                Method.TRANSLATE_API -> {
                    if (base == null) return
                    setStatus(method, EngineStatus(EngineState.DOWNLOADING))
                    val result = translation.download(target, base)
                    setStatus(method, EngineStatus(EngineState.AVAILABLE, lastDownloadMs = result.elapsedMs, lastDownloadBytes = result.bytesDelta))
                    record(
                        downloadRow(CsvEvent.DOWNLOAD_END, method).copy(
                            elapsedMs = result.elapsedMs,
                            bytes = result.bytesDelta,
                            result = ResultKind.SUCCESS,
                            featureStatus = if (result.alreadyDownloaded) "ALREADY_DOWNLOADED" else "DOWNLOADED",
                        ),
                    )
                }

                Method.PROMPT_TRANSLATE, Method.PROMPT_PROOFREAD -> {
                    var total: Long? = null
                    var downloaded: Long? = null
                    setPromptStatus(EngineStatus(EngineState.DOWNLOADING))
                    prompt.download().collect { status ->
                        when (status) {
                            is DownloadStatus.DownloadStarted -> total = status.bytesToDownload
                            is DownloadStatus.DownloadProgress -> downloaded = status.totalBytesDownloaded
                            is DownloadStatus.DownloadFailed -> throw status.e
                            else -> Unit
                        }
                        setPromptStatus(EngineStatus(EngineState.DOWNLOADING, downloadedBytes = downloaded, totalBytes = total))
                    }
                    val elapsed = clock() - started
                    setPromptStatus(EngineStatus(EngineState.CHECKING, lastDownloadMs = elapsed, lastDownloadBytes = total ?: downloaded))
                    record(
                        downloadRow(CsvEvent.DOWNLOAD_END, method).copy(
                            elapsedMs = elapsed,
                            bytes = total ?: downloaded,
                            result = ResultKind.SUCCESS,
                        ),
                    )
                    refreshStatuses()
                }

                Method.PROOFREAD_API -> {
                    val language = proofread.languageOf(target) ?: return
                    var total: Long? = null
                    var downloaded: Long? = null
                    setStatus(method, EngineStatus(EngineState.DOWNLOADING))
                    proofread.download(language) { event ->
                        when (event) {
                            is DownloadEvent.Started -> total = event.bytesToDownload
                            is DownloadEvent.Progress -> downloaded = event.bytesDownloaded
                        }
                        setStatus(method, EngineStatus(EngineState.DOWNLOADING, downloadedBytes = downloaded, totalBytes = total))
                    }
                    val elapsed = clock() - started
                    setStatus(method, EngineStatus(EngineState.CHECKING, lastDownloadMs = elapsed, lastDownloadBytes = total ?: downloaded))
                    record(
                        downloadRow(CsvEvent.DOWNLOAD_END, method).copy(
                            elapsedMs = elapsed,
                            bytes = total ?: downloaded,
                            result = ResultKind.SUCCESS,
                        ),
                    )
                    refreshStatuses()
                }
            }
        } catch (e: CancellationException) {
            // 記録は待たずに積むだけなので、取り消し中でも書ける
            record(downloadRow(CsvEvent.DOWNLOAD_END, method).copy(elapsedMs = clock() - started, result = ResultKind.CANCELLED))
            throw e
        } catch (e: Exception) {
            val failure = classifyFailure(e)
            val failed = EngineStatus(EngineState.ERROR, errorCode = failure.code)
            if (method == Method.PROMPT_TRANSLATE || method == Method.PROMPT_PROOFREAD) setPromptStatus(failed) else setStatus(method, failed)
            record(
                downloadRow(CsvEvent.DOWNLOAD_END, method).copy(
                    elapsedMs = clock() - started,
                    result = failure.kind,
                    errorCode = failure.code,
                ),
            )
        }
    }

    private fun downloadRow(event: CsvEvent, method: Method): CsvRow {
        val current = state.value
        return CsvRow(
            event = event,
            fileId = current.transcript?.fileId,
            target = current.target,
            base = current.base,
            method = method,
            foreground = appForeground,
            screenOn = isScreenOn(),
        )
    }

    // ---- 実行 ----

    /** いま実行できる方式（有効にしていて、状態が「使える」もの）。 */
    private fun runnableMethods(): List<Method> {
        val current = state.value
        return Method.entries.filter { it in current.enabledMethods && current.statuses[it]?.state == EngineState.AVAILABLE }
    }

    private suspend fun runChunk(chunkNumber: Int) {
        val row = state.value.rows.firstOrNull { it.number == chunkNumber } ?: return
        val methods = runnableMethods()
        if (methods.isEmpty()) {
            mutableState.update { it.copy(message = SpikeMessage.NothingToRun) }
            return
        }
        for (method in methods) runOne(row, method, batchId = null)
    }

    private suspend fun runAll() {
        val methods = runnableMethods()
        val current = state.value
        // 未実行のものと、失敗したもの（BUSY・画面を消したときの失敗など）だけを実行する
        val plan = current.rows.flatMap { row ->
            methods.filter { method -> current.results[ResultKey(row.number, method)]?.kind?.isDone != true }.map { row to it }
        }
        if (plan.isEmpty()) {
            mutableState.update { it.copy(message = SpikeMessage.NothingToRun) }
            return
        }
        batchCounter++
        val batchId = currentBatchId()
        record(
            CsvRow(
                event = CsvEvent.BATCH_START,
                batch = batchId,
                fileKind = current.transcript?.kind,
                fileId = current.transcript?.fileId,
                target = current.target,
                base = current.base,
                methods = methods.joinToString("+") { it.code },
                count = plan.size,
                foreground = appForeground,
                screenOn = isScreenOn(),
            ),
        )
        val started = clock()
        var done = 0
        var successes = 0
        var completed = false
        mutableState.update { it.copy(batch = BatchProgress(0, plan.size)) }
        try {
            for ((row, method) in plan) {
                val result = runOne(row, method, batchId)
                done++
                if (result.kind == ResultKind.SUCCESS) successes++
                mutableState.update { it.copy(batch = BatchProgress(done, plan.size)) }
            }
            completed = true
        } finally {
            val elapsed = clock() - started
            mutableState.update { it.copy(batch = null, lastBatch = BatchSummary(elapsed, done, successes, completed)) }
            record(
                CsvRow(
                    event = CsvEvent.BATCH_END,
                    batch = batchId,
                    fileKind = current.transcript?.kind,
                    fileId = current.transcript?.fileId,
                    target = current.target,
                    base = current.base,
                    methods = methods.joinToString("+") { it.code },
                    elapsedMs = elapsed,
                    result = if (completed) ResultKind.SUCCESS else ResultKind.CANCELLED,
                    count = done,
                    successCount = successes,
                    foreground = appForeground,
                    screenOn = isScreenOn(),
                ),
            )
        }
    }

    private suspend fun runOne(row: ChunkRow, method: Method, batchId: String?): MethodResult {
        val current = state.value
        val target = current.target ?: return MethodResult(ResultKind.UNSUPPORTED, 0, firstCall = false, foreground = appForeground)
        val base = current.base
        val key = ResultKey(row.number, method)
        mutableState.update { it.copy(running = key) }
        val firstCall = !isWarm(method)
        val foreground = appForeground
        val started = clock()
        val result = try {
            val outcome = withTimeoutOrNull(CALL_TIMEOUT_MS) { callMethod(method, row, target, base) }
            val elapsed = clock() - started
            outcome?.toResult(row, elapsed, firstCall, foreground)
                ?: MethodResult(ResultKind.TIMEOUT, elapsed, firstCall, foreground)
        } catch (e: CancellationException) {
            val cancelled = MethodResult(ResultKind.CANCELLED, clock() - started, firstCall, foreground)
            storeResult(key, cancelled)
            record(runRow(row, method, batchId, cancelled))
            throw e
        } catch (e: Exception) {
            val failure = classifyFailure(e)
            MethodResult(failure.kind, clock() - started, firstCall, foreground, errorCode = failure.code)
        }
        storeResult(key, result)
        record(runRow(row, method, batchId, result))
        return result
    }

    private suspend fun callMethod(method: Method, row: ChunkRow, target: String, base: String?): Outcome = when (method) {
        Method.TRANSLATE_API -> {
            if (base == null) {
                Outcome.Skipped(ResultKind.UNSUPPORTED)
            } else {
                Outcome.Translated(translation.translate(target, base, row.text), finishReason = null)
            }
        }

        Method.PROMPT_TRANSLATE -> {
            if (base == null) {
                Outcome.Skipped(ResultKind.UNSUPPORTED)
            } else {
                val targetName = englishName(base)
                val output = prompt.generate(
                    PromptTemplates.translation(englishName(target), targetName, row.text, row.previous),
                    PromptTemplates.TRANSLATION_MAX_OUTPUT_TOKENS,
                )
                Outcome.Translated(PromptTemplates.cleanTranslation(output.text, targetName), output.finishReason)
            }
        }

        Method.PROOFREAD_API -> {
            val language = proofread.languageOf(target)
            if (language == null) {
                Outcome.Skipped(ResultKind.UNSUPPORTED)
            } else {
                val suggestions = proofread.proofread(language, row.text)
                Outcome.Proofread(suggestion = suggestions.firstOrNull(), suggestionCount = suggestions.size)
            }
        }

        Method.PROMPT_PROOFREAD -> {
            val output = prompt.generate(
                PromptTemplates.proofreading(englishName(target), row.text),
                PromptTemplates.PROOFREADING_MAX_OUTPUT_TOKENS,
            )
            val parsed = PromptTemplates.parseProofreading(output.text)
            if (parsed == null) {
                Outcome.FormatError(rawOutput = output.text, finishReason = output.finishReason)
            } else {
                Outcome.Proofread(
                    suggestion = parsed.suggestion,
                    suggestionCount = 1,
                    reason = parsed.reason,
                    finishReason = output.finishReason,
                )
            }
        }
    }

    private fun Outcome.toResult(row: ChunkRow, elapsedMs: Long, firstCall: Boolean, foreground: Boolean): MethodResult = when (this) {
        is Outcome.Skipped -> MethodResult(kind, elapsedMs, firstCall, foreground)

        is Outcome.Translated -> MethodResult(
            kind = if (text.isNullOrBlank()) ResultKind.EMPTY else ResultKind.SUCCESS,
            elapsedMs = elapsedMs,
            firstCall = firstCall,
            foreground = foreground,
            output = text,
            finishReason = finishReason,
        )

        is Outcome.Proofread -> MethodResult(
            kind = ResultKind.SUCCESS,
            elapsedMs = elapsedMs,
            firstCall = firstCall,
            foreground = foreground,
            output = suggestion,
            reason = reason,
            changed = suggestion != null && normalizeForCompare(suggestion) != normalizeForCompare(row.text),
            suggestionCount = suggestionCount,
            finishReason = finishReason,
        )

        is Outcome.FormatError -> MethodResult(
            kind = ResultKind.FORMAT_ERROR,
            elapsedMs = elapsedMs,
            firstCall = firstCall,
            foreground = foreground,
            output = rawOutput,
            finishReason = finishReason,
        )
    }

    private fun isWarm(method: Method): Boolean = when (method) {
        Method.TRANSLATE_API -> translation.warm
        Method.PROMPT_TRANSLATE, Method.PROMPT_PROOFREAD -> prompt.warm
        Method.PROOFREAD_API -> proofread.warm
    }

    private fun runRow(row: ChunkRow, method: Method, batchId: String?, result: MethodResult): CsvRow {
        val current = state.value
        return CsvRow(
            event = CsvEvent.RUN,
            batch = batchId,
            fileKind = current.transcript?.kind,
            fileId = current.transcript?.fileId,
            target = current.target,
            base = current.base,
            chunk = row.number,
            chunkLang = row.language,
            langSource = row.languageSource,
            method = method,
            first = result.firstCall,
            elapsedMs = result.elapsedMs,
            result = result.kind,
            errorCode = result.errorCode,
            inputChars = codePointLength(row.text),
            outputChars = result.output?.let(::codePointLength),
            changed = result.changed,
            suggestions = result.suggestionCount,
            finish = result.finishReason,
            foreground = result.foreground,
            screenOn = isScreenOn(),
            model = current.statuses[method]?.modelName,
        )
    }

    private fun storeResult(key: ResultKey, result: MethodResult) {
        mutableState.update { it.copy(results = it.results + (key to result), running = null) }
    }

    // ---- 評価・集計 ----

    private fun rate(key: ResultKey, rating: Rating) {
        val current = state.value
        val newRating = if (current.ratings[key] == rating) null else rating
        mutableState.update {
            it.copy(ratings = if (newRating == null) it.ratings - key else it.ratings + (key to newRating))
        }
        val row = current.rows.firstOrNull { it.number == key.chunkNumber }
        record(
            CsvRow(
                event = CsvEvent.RATING,
                fileId = current.transcript?.fileId,
                target = current.target,
                base = current.base,
                chunk = key.chunkNumber,
                chunkLang = row?.language,
                langSource = row?.languageSource,
                method = key.method,
                result = current.results[key]?.kind,
                changed = current.results[key]?.changed,
                rating = newRating?.name ?: "CLEARED",
            ),
        )
    }

    private fun clearResults() {
        if (state.value.busy != null) return
        mutableState.update { it.copy(results = emptyMap(), ratings = emptyMap(), lastBatch = null) }
        record(CsvRow(event = CsvEvent.RESULTS_CLEARED, fileId = state.value.transcript?.fileId))
    }

    private fun saveSummary() {
        val current = state.value
        val rows = current.rows.flatMap { row ->
            Method.entries.mapNotNull { method ->
                val key = ResultKey(row.number, method)
                val result = current.results[key]
                val rating = current.ratings[key]
                if (result == null && rating == null) return@mapNotNull null
                CsvRow(
                    event = CsvEvent.SUMMARY,
                    fileKind = current.transcript?.kind,
                    fileId = current.transcript?.fileId,
                    target = current.target,
                    base = current.base,
                    chunk = row.number,
                    chunkLang = row.language,
                    langSource = row.languageSource,
                    method = method,
                    first = result?.firstCall,
                    elapsedMs = result?.elapsedMs,
                    result = result?.kind,
                    errorCode = result?.errorCode,
                    inputChars = codePointLength(row.text),
                    outputChars = result?.output?.let(::codePointLength),
                    changed = result?.changed,
                    suggestions = result?.suggestionCount,
                    finish = result?.finishReason,
                    rating = rating?.name,
                    foreground = result?.foreground,
                    model = current.statuses[method]?.modelName,
                )
            }
        }
        viewModelScope.launch {
            val fileName = recorder.writeSummary(rows)
            mutableState.update {
                it.copy(message = if (fileName != null) SpikeMessage.SummarySaved(fileName) else SpikeMessage.SummaryFailed)
            }
        }
    }

    // ---- 補助 ----

    private fun launchExclusive(kind: BusyKind, block: suspend () -> Unit) {
        if (activeJob?.isActive == true) return
        mutableState.update { it.copy(busy = kind, message = null) }
        activeJob = viewModelScope.launch {
            try {
                block()
            } finally {
                mutableState.update { it.copy(busy = null, running = null) }
            }
        }
    }

    private fun setStatus(method: Method, status: EngineStatus) {
        mutableState.update { current ->
            val previous = current.statuses[method]
            val merged = status.copy(
                lastDownloadMs = status.lastDownloadMs ?: previous?.lastDownloadMs,
                lastDownloadBytes = status.lastDownloadBytes ?: previous?.lastDownloadBytes,
            )
            current.copy(statuses = current.statuses + (method to merged))
        }
    }

    /** B と D は同じモデル（Prompt API）なので、状態をそろえる。 */
    private fun setPromptStatus(status: EngineStatus) {
        setStatus(Method.PROMPT_TRANSLATE, status)
        setStatus(Method.PROMPT_PROOFREAD, status)
    }

    private suspend fun statusOrError(block: suspend () -> EngineStatus): EngineStatus = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        EngineStatus(EngineState.ERROR, errorCode = classifyFailure(e).code)
    }

    private suspend fun nameOrNull(block: suspend () -> String?): String? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun featureState(feature: Int): EngineState = when (feature) {
        FeatureStatus.AVAILABLE -> EngineState.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> EngineState.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> EngineState.DOWNLOADING
        FeatureStatus.UNAVAILABLE -> EngineState.UNAVAILABLE
        else -> EngineState.ERROR
    }

    private fun record(row: CsvRow) {
        recorder.record(row)
    }

    private fun currentBatchId(): String = "${recorder.sessionId}-$batchCounter"

    private fun isScreenOn(): Boolean = powerManager?.isInteractive ?: true

    private fun englishName(language: String): String = when (language) {
        "zh" -> "Chinese (Mandarin)"
        else -> SupportedLanguageCatalog.englishDisplayName(language) ?: language
    }

    /** 1 回の呼び出しの結果（文を含むので、画面に出すだけ）。 */
    private sealed interface Outcome {
        data class Skipped(val kind: ResultKind) : Outcome
        data class Translated(val text: String?, val finishReason: String?) : Outcome
        data class Proofread(
            val suggestion: String?,
            val suggestionCount: Int,
            val reason: String? = null,
            val finishReason: String? = null,
        ) : Outcome

        data class FormatError(val rawOutput: String?, val finishReason: String?) : Outcome
    }

    private companion object {
        /** 1 回の呼び出しの上限（止まったままにしない）。 */
        const val CALL_TIMEOUT_MS = 120_000L

        fun csvDirectory(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "s4")
    }
}
