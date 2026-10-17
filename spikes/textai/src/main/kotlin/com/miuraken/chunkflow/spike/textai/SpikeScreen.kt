package com.miuraken.chunkflow.spike.textai

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.model.SupportedLanguageCatalog

/** 学習言語・ベース言語として選べる言語（今の教材と、Proofreading API の対応言語）。 */
private val LANGUAGE_CHOICES = listOf("ja", "en", "ko", "zh", "yue", "hi", "fr", "es", "de", "it", "ru", "vi")

/** 状態を集めて [SpikeScreen] に渡すだけの Route。 */
@Composable
internal fun SpikeRoute(viewModel: TextAiViewModel, onOpenFile: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SpikeScreen(
        state = state,
        onEvent = { event -> if (event is SpikeEvent.OpenFile) onOpenFile() else viewModel.onEvent(event) },
        modifier = modifier,
    )
}

/**
 * スパイク S4 の画面（状態を受け取って描くだけ）。
 *
 * 上から、ファイル・言語・方式の状態・実行と記録、続けて学習言語のチャンクごとに元の文と A〜D の結果・時間・評価を並べる。
 */
@Composable
internal fun SpikeScreen(state: TextAiUiState, onEvent: (SpikeEvent) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "file") { FileSection(state, onEvent) }
        if (state.transcript != null) {
            item(key = "languages") { LanguageSection(state, onEvent) }
            item(key = "methods") { MethodSection(state, onEvent) }
            item(key = "run") { RunSection(state, onEvent) }
        }
        items(state.rows, key = { it.number }) { row -> ChunkCard(row, state, onEvent) }
    }
}

@Composable
private fun FileSection(state: TextAiUiState, onEvent: (SpikeEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = stringResource(R.string.spike_title), style = MaterialTheme.typography.titleLarge)
        Text(text = stringResource(R.string.spike_description), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onEvent(SpikeEvent.OpenFile) }, enabled = state.busy == null) {
                Text(text = stringResource(R.string.action_open_file))
            }
            OutlinedButton(onClick = { onEvent(SpikeEvent.LoadSample) }, enabled = state.busy == null) {
                Text(text = "Load Sample") // No translation needed for spike
            }
        }
        if (state.busy == BusyKind.LOADING_FILE) {
            Text(text = stringResource(R.string.loading_file))
        }
        state.loadError?.let { error ->
            Text(text = stringResource(error.messageRes()), color = MaterialTheme.colorScheme.error)
        }
        state.transcript?.let { transcript ->
            Text(
                text = stringResource(
                    R.string.file_summary,
                    stringResource(transcript.kind.labelRes()),
                    transcript.segments.size,
                    state.rows.size,
                    transcript.fileId,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LanguageSection(state: TextAiUiState, onEvent: (SpikeEvent) -> Unit) {
    val transcript = state.transcript ?: return
    val editable = state.busy == null
    SectionCard(title = stringResource(R.string.section_languages)) {
        val fixedTarget = transcript.fileTarget
        if (fixedTarget != null) {
            Text(text = stringResource(R.string.label_target_from_file, languageLabel(fixedTarget)))
        } else {
            Text(text = stringResource(R.string.label_target), style = MaterialTheme.typography.labelLarge)
            LanguageChips(selected = state.target, excluded = null, enabled = editable) { onEvent(SpikeEvent.SelectTarget(it)) }
            Text(text = stringResource(R.string.subtitle_language_note), style = MaterialTheme.typography.bodySmall)
        }
        Text(text = stringResource(R.string.label_base), style = MaterialTheme.typography.labelLarge)
        LanguageChips(selected = state.base, excluded = state.target, enabled = editable) { onEvent(SpikeEvent.SelectBase(it)) }
        val target = state.target
        val base = state.base
        if (target == null) {
            Text(text = stringResource(R.string.choose_target_prompt), color = MaterialTheme.colorScheme.error)
        } else if (fixedTarget == null && base != null && !ScriptLanguageGuesser.canDistinguish(target, base)) {
            Text(text = stringResource(R.string.cannot_distinguish_note), color = MaterialTheme.colorScheme.error)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.includeUnknown,
                    role = Role.Switch,
                    onValueChange = { onEvent(SpikeEvent.SetIncludeUnknown(it)) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.include_unknown, state.unknownCount),
                modifier = Modifier.weight(1f),
            )
            Switch(checked = state.includeUnknown, onCheckedChange = null)
        }
    }
}

@Composable
private fun LanguageChips(selected: String?, excluded: String?, enabled: Boolean, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (language in LANGUAGE_CHOICES) {
            if (language == excluded) continue
            FilterChip(
                selected = language == selected,
                onClick = { onSelect(language) },
                enabled = enabled,
                label = { Text(text = languageLabel(language)) },
            )
        }
    }
}

@Composable
private fun MethodSection(state: TextAiUiState, onEvent: (SpikeEvent) -> Unit) {
    val context = LocalContext.current
    SectionCard(title = stringResource(R.string.section_methods)) {
        for (method in Method.entries) {
            val status = state.statuses[method]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = method in state.enabledMethods,
                        role = Role.Checkbox,
                        onValueChange = { onEvent(SpikeEvent.SetMethodEnabled(method, it)) },
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = method in state.enabledMethods, onCheckedChange = null)
                Spacer(modifier = Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(method.labelRes()), style = MaterialTheme.typography.titleSmall)
                    Text(text = statusText(status, context), style = MaterialTheme.typography.bodySmall)
                    status?.modelName?.let {
                        Text(text = stringResource(R.string.status_model, it), style = MaterialTheme.typography.bodySmall)
                    }
                    val lastMs = status?.lastDownloadMs
                    if (lastMs != null) {
                        Text(
                            text = stringResource(
                                R.string.status_last_download,
                                durationText(lastMs),
                                status.lastDownloadBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "—",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (status?.state == EngineState.DOWNLOADABLE) {
                    OutlinedButton(onClick = { onEvent(SpikeEvent.Download(method)) }, enabled = state.busy == null) {
                        Text(text = stringResource(R.string.action_download))
                    }
                }
            }
            val downloaded = status?.downloadedBytes
            val total = status?.totalBytes
            if (status?.state == EngineState.DOWNLOADING) {
                if (downloaded != null && total != null && total > 0) {
                    LinearProgressIndicator(
                        progress = { (downloaded.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Text(text = stringResource(R.string.shared_model_note), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onEvent(SpikeEvent.RefreshStatus) }, enabled = state.busy == null) {
            Text(text = stringResource(R.string.action_refresh_status))
        }
    }
}

@Composable
private fun RunSection(state: TextAiUiState, onEvent: (SpikeEvent) -> Unit) {
    SectionCard(title = stringResource(R.string.section_run)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onEvent(SpikeEvent.RunAll) }, enabled = state.busy == null && state.rows.isNotEmpty()) {
                Text(text = stringResource(R.string.action_run_all))
            }
            OutlinedButton(onClick = { onEvent(SpikeEvent.Stop) }, enabled = state.busy != null) {
                Text(text = stringResource(R.string.action_stop))
            }
        }
        state.batch?.let { batch ->
            Text(text = stringResource(R.string.batch_progress, batch.done, batch.total))
            LinearProgressIndicator(
                progress = { if (batch.total > 0) batch.done.toFloat() / batch.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.lastBatch?.let { summary ->
            Text(
                text = stringResource(
                    if (summary.completed) R.string.last_batch_completed else R.string.last_batch_stopped,
                    summary.runs,
                    summary.successes,
                    durationText(summary.elapsedMs),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(text = stringResource(R.string.background_note), style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onEvent(SpikeEvent.SaveSummary) }) {
                Text(text = stringResource(R.string.action_save_summary))
            }
            TextButton(onClick = { onEvent(SpikeEvent.ClearResults) }, enabled = state.busy == null) {
                Text(text = stringResource(R.string.action_clear_results))
            }
        }
        state.csvDirectory?.let {
            Text(text = stringResource(R.string.csv_location, it), style = MaterialTheme.typography.bodySmall)
        }
        state.message?.let { message ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (message) {
                        is SpikeMessage.SummarySaved -> stringResource(R.string.message_summary_saved, message.fileName)
                        SpikeMessage.SummaryFailed -> stringResource(R.string.message_summary_failed)
                        SpikeMessage.NothingToRun -> stringResource(R.string.message_nothing_to_run)
                    },
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.primary,
                )
                TextButton(onClick = { onEvent(SpikeEvent.DismissMessage) }) {
                    Text(text = stringResource(R.string.action_dismiss))
                }
            }
        }
    }
}

@Composable
private fun ChunkCard(row: ChunkRow, state: TextAiUiState, onEvent: (SpikeEvent) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(
                    R.string.chunk_header,
                    row.number,
                    clockText(row.startMs),
                    row.language ?: stringResource(R.string.language_unknown),
                    stringResource(row.languageSource.labelRes()),
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(text = row.text, style = localizedStyle(MaterialTheme.typography.bodyLarge, row.language ?: state.target))
            OutlinedButton(onClick = { onEvent(SpikeEvent.RunChunk(row.number)) }, enabled = state.busy == null) {
                Text(text = stringResource(R.string.action_run_chunk))
            }
            for (method in Method.entries) {
                HorizontalDivider()
                val key = ResultKey(row.number, method)
                MethodResultBlock(
                    method = method,
                    original = row.text,
                    result = state.results[key],
                    rating = state.ratings[key],
                    status = state.statuses[method],
                    running = state.running == key,
                    outputLanguage = if (method.purpose == Purpose.TRANSLATION) state.base else state.target,
                    onRate = { onEvent(SpikeEvent.Rate(key, it)) },
                )
            }
        }
    }
}

@Composable
private fun MethodResultBlock(
    method: Method,
    original: String,
    result: MethodResult?,
    rating: Rating?,
    status: EngineStatus?,
    running: Boolean,
    outputLanguage: String?,
    onRate: (Rating) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(method.labelRes()),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            if (result != null) {
                Text(
                    text = buildString {
                        append(
                            if (result.firstCall) {
                                stringResource(R.string.result_time_first, result.elapsedMs)
                            } else {
                                stringResource(R.string.result_time, result.elapsedMs)
                            },
                        )
                        if (!result.foreground) append(stringResource(R.string.result_background))
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        when {
            running -> Text(text = stringResource(R.string.result_running))
            result == null -> Text(text = notRunText(status), style = MaterialTheme.typography.bodySmall)
            else -> ResultContent(method, original, result, outputLanguage)
        }
        val canRate = result != null &&
            result.kind == ResultKind.SUCCESS &&
            (method.purpose == Purpose.TRANSLATION || result.changed == true)
        if (canRate) {
            RatingChips(purpose = method.purpose, selected = rating, onRate = onRate)
        }
    }
}

@Composable
private fun ResultContent(method: Method, original: String, result: MethodResult, outputLanguage: String?) {
    val output = result.output
    when (result.kind) {
        ResultKind.SUCCESS -> if (method.purpose == Purpose.TRANSLATION) {
            Text(text = output.orEmpty(), style = localizedStyle(MaterialTheme.typography.bodyLarge, outputLanguage))
        } else {
            if (result.changed == true && output != null) {
                Text(
                    text = stringResource(R.string.result_suggestion, output),
                    style = localizedStyle(MaterialTheme.typography.bodyLarge, outputLanguage),
                )
                Text(text = stringResource(R.string.result_diff_legend), style = MaterialTheme.typography.labelSmall)
                DiffText(original = original, suggestion = output, language = outputLanguage)
            } else {
                Text(text = stringResource(R.string.result_no_change))
            }
            result.reason?.let { Text(text = stringResource(R.string.result_reason, it), style = MaterialTheme.typography.bodySmall) }
            result.suggestionCount?.takeIf { method == Method.PROOFREAD_API && it > 1 }?.let {
                Text(text = stringResource(R.string.result_suggestion_count, it), style = MaterialTheme.typography.bodySmall)
            }
        }

        ResultKind.FORMAT_ERROR -> {
            Text(text = stringResource(R.string.result_format_error), color = MaterialTheme.colorScheme.error)
            Text(text = output.orEmpty(), style = MaterialTheme.typography.bodySmall)
        }

        ResultKind.EMPTY -> Text(text = stringResource(R.string.result_empty), color = MaterialTheme.colorScheme.error)

        else -> {
            val code = result.errorCode
            Text(
                text = if (code != null) {
                    stringResource(R.string.result_failed_code, result.kind.name, code)
                } else {
                    stringResource(R.string.result_failed, result.kind.name)
                },
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    if (result.finishReason == "MAX_TOKENS") {
        Text(text = stringResource(R.string.result_truncated), style = MaterialTheme.typography.bodySmall)
    }
}

/** 校正の差分。消す部分は取り消し線、加える部分は下線と太字で示す（色だけに頼らない）。 */
@Composable
private fun DiffText(original: String, suggestion: String, language: String?) {
    val colors = MaterialTheme.colorScheme
    val spans = remember(original, suggestion) { TextDiff.diff(original, suggestion) }
    if (spans == null) {
        Text(text = stringResource(R.string.result_diff_too_long), style = MaterialTheme.typography.bodySmall)
        return
    }
    val removedStyle = SpanStyle(
        background = colors.errorContainer,
        color = colors.onErrorContainer,
        textDecoration = TextDecoration.LineThrough,
    )
    val addedStyle = SpanStyle(
        background = colors.tertiaryContainer,
        color = colors.onTertiaryContainer,
        textDecoration = TextDecoration.Underline,
        fontWeight = FontWeight.Bold,
    )
    val annotated: AnnotatedString = buildAnnotatedString {
        for (span in spans) {
            when (span) {
                is DiffSpan.Same -> append(span.text)
                is DiffSpan.Removed -> withStyle(removedStyle) { append(span.text) }
                is DiffSpan.Added -> withStyle(addedStyle) { append(span.text) }
            }
        }
    }
    Text(text = annotated, style = localizedStyle(MaterialTheme.typography.bodyLarge, language))
}

@Composable
private fun RatingChips(purpose: Purpose, selected: Rating?, onRate: (Rating) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (rating in Rating.entries) {
            if (rating.purpose != purpose) continue
            FilterChip(
                selected = rating == selected,
                onClick = { onRate(rating) },
                label = { Text(text = stringResource(rating.labelRes())) },
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

// ---- 表示の補助 ----

@Composable
private fun statusText(status: EngineStatus?, context: android.content.Context): String {
    if (status == null) return stringResource(R.string.state_checking)
    return when (status.state) {
        EngineState.CHECKING -> stringResource(R.string.state_checking)

        EngineState.UNSUPPORTED -> stringResource(R.string.state_unsupported)

        EngineState.NEEDS_LANGUAGE -> stringResource(R.string.state_needs_language)

        EngineState.UNAVAILABLE -> stringResource(R.string.state_unavailable)

        EngineState.DOWNLOADABLE -> stringResource(R.string.state_downloadable)

        EngineState.DOWNLOADING -> {
            val downloaded = status.downloadedBytes
            val total = status.totalBytes
            if (downloaded != null && total != null) {
                stringResource(
                    R.string.state_downloading_progress,
                    Formatter.formatShortFileSize(context, downloaded),
                    Formatter.formatShortFileSize(context, total),
                )
            } else {
                stringResource(R.string.state_downloading)
            }
        }

        EngineState.AVAILABLE -> stringResource(R.string.state_available)

        EngineState.ERROR -> stringResource(R.string.state_error, status.errorCode?.toString() ?: "—")
    }
}

@Composable
private fun notRunText(status: EngineStatus?): String = when (status?.state) {
    EngineState.UNSUPPORTED -> stringResource(R.string.state_unsupported)
    EngineState.NEEDS_LANGUAGE -> stringResource(R.string.state_needs_language)
    EngineState.DOWNLOADABLE -> stringResource(R.string.state_downloadable)
    EngineState.UNAVAILABLE -> stringResource(R.string.state_unavailable)
    else -> stringResource(R.string.result_not_run)
}

@Composable
private fun durationText(ms: Long): String {
    val totalSeconds = ms / MILLIS_PER_SECOND
    return if (totalSeconds < SECONDS_PER_MINUTE) {
        stringResource(R.string.duration_seconds, ms / MILLIS_PER_SECOND.toFloat())
    } else {
        stringResource(R.string.duration_minutes, totalSeconds / SECONDS_PER_MINUTE, totalSeconds % SECONDS_PER_MINUTE)
    }
}

/** 再生位置の表示（分:秒）。 */
private fun clockText(ms: Long): String {
    val totalSeconds = ms / MILLIS_PER_SECOND
    val minutes = (totalSeconds / SECONDS_PER_MINUTE).toString().padStart(2, '0')
    val seconds = (totalSeconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')
    return "$minutes:$seconds"
}

private fun languageLabel(language: String): String {
    val name = SupportedLanguageCatalog.japaneseDisplayName(language)
    return if (name != null) "$name $language" else language
}

/** 言語に合った字形で表示する（中国語と日本語の漢字など）。 */
private fun localizedStyle(base: TextStyle, language: String?): TextStyle = if (language == null) base else base.copy(localeList = LocaleList(language))

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

private fun Method.labelRes(): Int = when (this) {
    Method.TRANSLATE_API -> R.string.method_a
    Method.PROMPT_TRANSLATE -> R.string.method_b
    Method.PROOFREAD_API -> R.string.method_c
    Method.PROMPT_PROOFREAD -> R.string.method_d
}

private fun Rating.labelRes(): Int = when (this) {
    Rating.CORRECT -> R.string.rating_correct
    Rating.MOSTLY_CORRECT -> R.string.rating_mostly_correct
    Rating.WRONG -> R.string.rating_wrong
    Rating.GOOD_FIX -> R.string.rating_good_fix
    Rating.UNNECESSARY_FIX -> R.string.rating_unnecessary_fix
    Rating.WRONG_FIX -> R.string.rating_wrong_fix
}

private fun LanguageSource.labelRes(): Int = when (this) {
    LanguageSource.FILE -> R.string.lang_source_file
    LanguageSource.SCRIPT -> R.string.lang_source_script
    LanguageSource.NONE -> R.string.lang_source_none
}

private fun FileKind.labelRes(): Int = when (this) {
    FileKind.CHUNKFLOW_JSON -> R.string.file_kind_json
    FileKind.SRT -> R.string.file_kind_srt
    FileKind.VTT -> R.string.file_kind_vtt
    FileKind.LRC -> R.string.file_kind_lrc
}

private fun LoadError.messageRes(): Int = when (this) {
    LoadError.TOO_LARGE -> R.string.load_error_too_large
    LoadError.UNREADABLE -> R.string.load_error_unreadable
    LoadError.UNKNOWN_FORMAT -> R.string.load_error_unknown_format
    LoadError.UNSUPPORTED_VERSION -> R.string.load_error_unsupported_version
    LoadError.INVALID -> R.string.load_error_invalid
    LoadError.TIME_OUT_OF_RANGE -> R.string.load_error_time
    LoadError.NO_SEGMENTS -> R.string.load_error_no_segments
}

/** 試作のテーマ（端末のダークテーマに合わせる）。 */
@Composable
internal fun SpikeTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) darkColorScheme() else lightColorScheme(), content = content)
}

@Preview(showBackground = true, heightDp = 1400)
@Composable
private fun SpikeScreenPreview() {
    // プレビュー用の作った例文（実際の教材ではない）
    val row = ChunkRow(
        number = 12,
        startMs = 41_200,
        text = "저는 학생이에요",
        language = "ko",
        languageSource = LanguageSource.FILE,
        previous = PreviousContext(text = "「私は学生です」と言ってみましょう。", languageName = "Japanese"),
    )
    val state = TextAiUiState(
        transcript = LoadedTranscript(
            kind = FileKind.CHUNKFLOW_JSON,
            fileId = "1a2b3c4d",
            segments = listOf(Segment(12, 41_200, row.text, "ko")),
            fileTarget = "ko",
            fileBase = "ja",
        ),
        target = "ko",
        base = "ja",
        rows = listOf(row),
        statuses = mapOf(
            Method.TRANSLATE_API to EngineStatus(EngineState.AVAILABLE, lastDownloadMs = 12_300, lastDownloadBytes = 31_000_000),
            Method.PROMPT_TRANSLATE to EngineStatus(EngineState.AVAILABLE, modelName = "nano"),
            Method.PROOFREAD_API to EngineStatus(EngineState.DOWNLOADING, downloadedBytes = 10_000_000, totalBytes = 40_000_000),
            Method.PROMPT_PROOFREAD to EngineStatus(EngineState.AVAILABLE, modelName = "nano"),
        ),
        results = mapOf(
            ResultKey(12, Method.TRANSLATE_API) to MethodResult(ResultKind.SUCCESS, 420, firstCall = true, foreground = true, output = "私は学生です"),
            ResultKey(12, Method.PROMPT_TRANSLATE) to MethodResult(ResultKind.BACKGROUND_USE_BLOCKED, 35, firstCall = false, foreground = false, errorCode = 30),
            ResultKey(12, Method.PROMPT_PROOFREAD) to MethodResult(
                ResultKind.SUCCESS,
                1_800,
                firstCall = false,
                foreground = true,
                output = "저는 학생이에요.",
                reason = "文末に句点を補った",
                changed = true,
            ),
        ),
        ratings = mapOf(ResultKey(12, Method.TRANSLATE_API) to Rating.CORRECT),
        csvDirectory = "/storage/emulated/0/Android/data/com.miuraken.chunkflow.spike.textai/files/s4",
    )
    SpikeTheme(darkTheme = false) {
        Surface {
            SpikeScreen(state = state, onEvent = {})
        }
    }
}
