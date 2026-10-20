package com.miuraken.chunkflow.feature.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.OnDeviceRecognitionModelState
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.TranscriptionDiagnosticEntry
import com.miuraken.chunkflow.core.model.TranscriptionStep
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.playback.PositionProvider
import java.util.Locale as JavaLocale
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** 1 チャンクの文字起こしが停滞しているとみなして警告を出す経過時間（8 秒）。 */
private const val TRANSCRIPTION_STALL_WARNING_MS: Long = 8_000L

@Composable
internal fun EditChunkTextDialog(
    chunkNumber: Int,
    initialText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var textValue by remember(chunkNumber, initialText) { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.player_chunk_edit_text_dialog_title, chunkNumber))
        },
        text = {
            OutlinedTextField(
                value = textValue,
                onValueChange = { textValue = it },
                label = { Text(text = stringResource(R.string.player_chunk_edit_text_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(textValue) }) {
                Text(text = stringResource(R.string.player_chunk_edit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.player_chunk_edit_cancel))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailedChunkTranscriptionDialog(
    dialogState: DetailedTranscriptionDialogState,
    targetChunk: ChunkUi?,
    waveform: Waveform?,
    languagePair: LanguagePair?,
    positionProvider: PositionProvider,
    hasRecordAudioPermission: Boolean,
    onRequestRecordAudioPermission: () -> Unit,
    onSelectLanguage: (LanguageTag) -> Unit,
    onSelectEngine: (OnDeviceTranscriptionEngine) -> Unit,
    onRequestModelDownload: (LanguageTag, OnDeviceTranscriptionEngine) -> Unit,
    onPlayPreview: (Float) -> Unit,
    onStopPreview: () -> Unit,
    onRunTrial: (OnDeviceTranscriptionEngine, LanguageTag?, Float) -> Unit,
    onApply: (String, LanguageTag?) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultLang = dialogState.languageOverride
        ?: resolveInitialDetailedLanguage(targetChunk?.language, languagePair)
    var selectedLanguage by remember(dialogState.chunkId, dialogState.languageOverride) {
        mutableStateOf(defaultLang)
    }
    var selectedEngine by remember(dialogState.chunkId, dialogState.engine) {
        mutableStateOf(dialogState.engine)
    }
    var selectedSpeed by remember(dialogState.chunkId) {
        mutableFloatStateOf(dialogState.audioSpeed)
    }
    var editableText by remember(dialogState.chunkId, dialogState.resultText, dialogState.hasAttempted) {
        mutableStateOf(dialogState.resultText)
    }

    val selectedEngineStatus = dialogState.engineStatus(selectedLanguage, selectedEngine)
    val precheckWarning = resolveDetailedPrecheckWarning(
        dialogState = dialogState,
        selectedLanguage = selectedLanguage,
        selectedEngine = selectedEngine,
        hasRecordAudioPermission = hasRecordAudioPermission,
    )
    val isAnyEngineDownloading = DetailedTranscriptionDialogState.ENGINE_OPTIONS.any { engine ->
        dialogState.engineStatus(selectedLanguage, engine) == OnDeviceLanguageModelStatus.DOWNLOADING
    }
    val canRunTrial = !dialogState.isRunning &&
        selectedEngineStatus != OnDeviceLanguageModelStatus.DOWNLOADING &&
        selectedEngineStatus != OnDeviceLanguageModelStatus.UNSUPPORTED

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.player_detailed_transcribe_dialog_title),
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 1. 認識言語（自動判定なし、チャンク言語または学習対象言語が初期選択）
                Text(
                    text = stringResource(R.string.player_detailed_transcribe_lang_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (languagePair != null) {
                        val targetCode = languagePair.target.value.lowercase(JavaLocale.ROOT)
                        FilterChip(
                            selected = matchesLanguageRole(selectedLanguage, languagePair.target),
                            onClick = {
                                val nextLang = languagePair.target
                                selectedLanguage = nextLang
                                selectedEngine = dialogState.selectDefaultEngineForLanguage(nextLang)
                                onSelectLanguage(nextLang)
                            },
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.player_detailed_transcribe_lang_target,
                                        targetCode,
                                    ),
                                )
                            },
                            enabled = !dialogState.isRunning,
                        )
                        languagePair.base?.takeIf { !matchesLanguageRole(it, languagePair.target) }?.let { baseLang ->
                            val baseCode = baseLang.value.lowercase(JavaLocale.ROOT)
                            FilterChip(
                                selected = matchesLanguageRole(selectedLanguage, baseLang),
                                onClick = {
                                    selectedLanguage = baseLang
                                    selectedEngine = dialogState.selectDefaultEngineForLanguage(baseLang)
                                    onSelectLanguage(baseLang)
                                },
                                label = {
                                    Text(
                                        text = stringResource(
                                            R.string.player_detailed_transcribe_lang_base,
                                            baseCode,
                                        ),
                                    )
                                },
                                enabled = !dialogState.isRunning,
                            )
                        }
                    } else {
                        val langCode = selectedLanguage.value.lowercase(JavaLocale.ROOT)
                        FilterChip(
                            selected = true,
                            onClick = {},
                            label = {
                                Text(
                                    text = stringResource(
                                        R.string.player_detailed_transcribe_lang_target,
                                        langCode,
                                    ),
                                )
                            },
                            enabled = !dialogState.isRunning,
                        )
                    }
                }

                // 2. 文字起こしエンジン（ML Kit, SpeechRecognizer, sherpa-onnx の順、自動なし）
                Text(
                    text = stringResource(R.string.player_detailed_transcribe_engine_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (engine in DetailedTranscriptionDialogState.ENGINE_OPTIONS) {
                        val labelRes = when (engine) {
                            OnDeviceTranscriptionEngine.ML_KIT,
                            OnDeviceTranscriptionEngine.AUTO,
                            -> R.string.player_detailed_transcribe_engine_mlkit

                            OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER ->
                                R.string.player_detailed_transcribe_engine_os

                            OnDeviceTranscriptionEngine.SHERPA_ONNX ->
                                R.string.player_detailed_transcribe_engine_sherpa
                        }
                        val status = dialogState.engineStatus(selectedLanguage, engine)
                        val isDownloading = status == OnDeviceLanguageModelStatus.DOWNLOADING
                        val isEngineEnabled = dialogState.isEngineEnabled(selectedLanguage, engine)
                        FilterChip(
                            selected = selectedEngine == engine,
                            onClick = {
                                if (isEngineEnabled) {
                                    selectedEngine = engine
                                    onSelectEngine(engine)
                                }
                            },
                            leadingIcon = if (isDownloading) {
                                {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            } else {
                                null
                            },
                            label = { Text(text = stringResource(labelRes)) },
                            enabled = !dialogState.isRunning && isEngineEnabled,
                        )
                    }
                }
                if (isAnyEngineDownloading && precheckWarning?.issue != DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOADING) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(R.string.player_detailed_transcribe_model_downloading),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // 3. 音声の入力速度（0.5x, 0.75x, 1.0x, 1.5x、デフォルト 1.0x）
                Text(
                    text = stringResource(R.string.player_detailed_transcribe_speed_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (speedOption in DetailedTranscriptionDialogState.AUDIO_SPEED_OPTIONS) {
                        val speedLabel = when {
                            abs(speedOption - 0.5f) < 0.01f -> "0.5x"
                            abs(speedOption - 0.75f) < 0.01f -> "0.75x"
                            abs(speedOption - 1.0f) < 0.01f -> "1.0x"
                            abs(speedOption - 1.5f) < 0.01f -> "1.5x"
                            else -> String.format(JavaLocale.ROOT, "%.2fx", speedOption)
                        }
                        FilterChip(
                            selected = abs(selectedSpeed - speedOption) < 0.01f,
                            onClick = {
                                selectedSpeed = speedOption
                                if (dialogState.isPreviewPlaying) {
                                    onPlayPreview(speedOption)
                                }
                            },
                            label = { Text(text = speedLabel) },
                            enabled = !dialogState.isRunning,
                        )
                    }
                }

                // 4. チャンクの波形と再生/停止ボタン（3の再生速度で再生）
                Text(
                    text = stringResource(R.string.player_detailed_transcribe_preview_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                DetailedChunkWaveformPreviewRow(
                    chunk = targetChunk,
                    waveform = waveform,
                    isPreviewPlaying = dialogState.isPreviewPlaying,
                    positionProvider = positionProvider,
                    enabled = !dialogState.isRunning,
                    onTogglePlayStop = {
                        if (dialogState.isPreviewPlaying) {
                            onStopPreview()
                        } else {
                            onPlayPreview(selectedSpeed)
                        }
                    },
                )

                // 4.5. 実行前チェックの警告カード（マイク権限不足 / モデル未DL / モデルDL中 / 非対応エンジン）
                precheckWarning?.let { warning ->
                    DetailedPrecheckWarningCard(
                        warning = warning,
                        selectedLanguage = selectedLanguage,
                        onAction = {
                            when (warning.issue) {
                                DetailedTranscriptionPrecheckIssue.MIC_PERMISSION_REQUIRED ->
                                    onRequestRecordAudioPermission()

                                DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOAD_REQUIRED ->
                                    onRequestModelDownload(selectedLanguage, selectedEngine)

                                DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOADING,
                                DetailedTranscriptionPrecheckIssue.ENGINE_UNSUPPORTED,
                                -> Unit
                            }
                        },
                    )
                }

                // 5. 「この条件で文字起こしを試す」ボタン
                FilledTonalButton(
                    onClick = {
                        onRunTrial(selectedEngine, selectedLanguage, selectedSpeed)
                    },
                    enabled = canRunTrial,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (dialogState.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stringResource(R.string.player_detailed_transcribe_running))
                    } else {
                        Text(text = stringResource(R.string.player_detailed_transcribe_run_button))
                    }
                }

                // 6. 認識結果テキストボックス（編集可）
                if (dialogState.recognizedEngineId != null) {
                    val langText = (dialogState.recognizedLanguage ?: selectedLanguage).value
                        .lowercase(JavaLocale.ROOT)
                    Text(
                        text = stringResource(
                            R.string.player_detailed_transcribe_result_meta,
                            dialogState.recognizedEngineId,
                            langText,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (dialogState.hasAttempted && dialogState.resultText.isBlank() && !dialogState.isRunning) {
                    val emptyReasonText = dialogState.trialEmptyMessage
                        ?: stringResource(
                            R.string.player_detailed_transcribe_empty_general,
                            selectedLanguage.value,
                        )
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = emptyReasonText,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        )
                    }
                }

                OutlinedTextField(
                    value = editableText,
                    onValueChange = { editableText = it },
                    label = { Text(text = stringResource(R.string.player_detailed_transcribe_result_label)) },
                    enabled = !dialogState.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(editableText, selectedLanguage)
                },
                enabled = !dialogState.isRunning,
            ) {
                Text(text = stringResource(R.string.player_detailed_transcribe_apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.player_chunk_edit_cancel))
            }
        },
    )
}

@Composable
private fun DetailedPrecheckWarningCard(
    warning: DetailedPrecheckWarningUiModel,
    selectedLanguage: LanguageTag,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (warning.isErrorStyle) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (warning.isErrorStyle) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val localizedMessage = when (warning.issue) {
        DetailedTranscriptionPrecheckIssue.MIC_PERMISSION_REQUIRED ->
            stringResource(R.string.player_detailed_transcribe_warning_mic_permission)

        DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOAD_REQUIRED ->
            stringResource(
                R.string.player_detailed_transcribe_warning_model_download_required,
                selectedLanguage.value,
            )

        DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOADING ->
            stringResource(R.string.player_detailed_transcribe_warning_model_downloading)

        DetailedTranscriptionPrecheckIssue.ENGINE_UNSUPPORTED ->
            stringResource(R.string.player_detailed_transcribe_warning_engine_unsupported)
    }
    val localizedActionLabel = when (warning.issue) {
        DetailedTranscriptionPrecheckIssue.MIC_PERMISSION_REQUIRED ->
            stringResource(R.string.player_detailed_transcribe_action_grant_mic_permission)

        DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOAD_REQUIRED ->
            stringResource(R.string.player_detailed_transcribe_action_download_model)

        DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOADING,
        DetailedTranscriptionPrecheckIssue.ENGINE_UNSUPPORTED,
        -> null
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (warning.issue == DetailedTranscriptionPrecheckIssue.MODEL_DOWNLOADING) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = contentColor,
                    )
                }
                Text(
                    text = localizedMessage,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
            if (localizedActionLabel != null) {
                FilledTonalButton(
                    onClick = onAction,
                    modifier = Modifier.align(Alignment.End),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = localizedActionLabel,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailedChunkWaveformPreviewRow(
    chunk: ChunkUi?,
    waveform: Waveform?,
    isPreviewPlaying: Boolean,
    positionProvider: PositionProvider,
    enabled: Boolean,
    onTogglePlayStop: () -> Unit,
) {
    val downsampler = remember { WaveformDownsampler(maxCacheEntries = 4) }
    val barColor = MaterialTheme.colorScheme.primary
    val playheadColor = MaterialTheme.colorScheme.tertiary
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val isInspectionMode = LocalInspectionMode.current
    var currentPosMs by remember(chunk?.id, isPreviewPlaying) {
        mutableLongStateOf(positionProvider.currentPositionMs())
    }
    if (isPreviewPlaying && !isInspectionMode) {
        LaunchedEffect(chunk?.id, isPreviewPlaying) {
            while (isActive) {
                withFrameNanos {
                    currentPosMs = positionProvider.currentPositionMs()
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .weight(1f)
                .height(48.dp),
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            ) {
                val centerY = size.height / 2f
                drawLine(
                    color = baselineColor,
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = 1.dp.toPx(),
                )
                if (waveform != null && chunk != null && chunk.endMs > chunk.startMs && size.width > 0f) {
                    val colWidthPx = 3.dp.toPx().coerceAtLeast(1f)
                    val columnCount = (size.width / colWidthPx).toInt().coerceIn(8, 120)
                    val sampled = downsampler.downsample(
                        waveform = waveform,
                        startMs = chunk.startMs,
                        endMs = chunk.endMs,
                        columnCount = columnCount,
                    )
                    val stepX = size.width / columnCount
                    val strokeW = (stepX * 0.65f).coerceAtLeast(1.5f)
                    for (i in 0 until sampled.size) {
                        val amp = sampled.peak[i].coerceIn(0.05f, 1f)
                        val halfH = (size.height * 0.46f) * amp
                        val x = (i + 0.5f) * stepX
                        drawLine(
                            color = barColor,
                            start = Offset(x, centerY - halfH),
                            end = Offset(x, centerY + halfH),
                            strokeWidth = strokeW,
                            cap = StrokeCap.Round,
                        )
                    }
                    if (isPreviewPlaying && currentPosMs in chunk.startMs..chunk.endMs) {
                        val fraction = ((currentPosMs - chunk.startMs).toFloat() / (chunk.endMs - chunk.startMs).toFloat())
                            .coerceIn(0f, 1f)
                        val playheadX = size.width * fraction
                        drawLine(
                            color = playheadColor,
                            start = Offset(playheadX, 0f),
                            end = Offset(playheadX, size.height),
                            strokeWidth = 2.dp.toPx(),
                        )
                    }
                }
            }
        }

        FilledTonalButton(
            onClick = onTogglePlayStop,
            enabled = enabled && chunk != null,
        ) {
            Icon(
                imageVector = if (isPreviewPlaying) ChunkFlowIcons.Pause else ChunkFlowIcons.Play,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = stringResource(
                    if (isPreviewPlaying) {
                        R.string.player_detailed_transcribe_preview_stop
                    } else {
                        R.string.player_detailed_transcribe_preview_play
                    },
                ),
            )
        }
    }
}

@Composable
internal fun transcriptionStepLabel(step: TranscriptionStep?): String = when (step) {
    TranscriptionStep.WAITING_LOCK -> stringResource(R.string.player_transcription_step_waiting_lock)
    TranscriptionStep.EXTRACTING_PCM -> stringResource(R.string.player_transcription_step_extracting_pcm)
    TranscriptionStep.DETECTING_LANGUAGE -> stringResource(R.string.player_transcription_step_detecting_language)
    TranscriptionStep.RECOGNIZING_PRIMARY -> stringResource(R.string.player_transcription_step_recognizing_primary)
    TranscriptionStep.RECOGNIZING_FALLBACK -> stringResource(R.string.player_transcription_step_recognizing_fallback)
    TranscriptionStep.SAVING -> stringResource(R.string.player_transcription_step_saving)
    null -> stringResource(R.string.player_chunk_transcription_active)
}

internal data class TranscriptionProgressSummary(
    val completedCount: Int,
    val totalCount: Int,
    val progressPercent: Int,
    val overallProgress: Float,
)

internal fun computeTranscriptionProgressSummary(
    lookaheadStatus: LookaheadTranscriptionStatus,
    transcribedChunkCount: Int = lookaheadStatus.completedCount,
    totalChunkCount: Int = max(lookaheadStatus.totalTargetCount, lookaheadStatus.completedCount),
): TranscriptionProgressSummary {
    val totalCount = max(totalChunkCount, transcribedChunkCount)
    val completedCount = transcribedChunkCount.coerceIn(0, totalCount)
    val stepFraction = lookaheadStatus.inFlightStep?.progressFraction ?: 0.35f
    val overallProgress = if (totalCount > 0) {
        val activeBonus = if (lookaheadStatus.inFlightChunkId != null) stepFraction else 0f
        ((completedCount.toFloat() + activeBonus) / totalCount.toFloat()).coerceIn(0f, 1f)
    } else {
        stepFraction
    }
    val progressPercent = if (totalCount > 0) {
        ((completedCount * 100) / totalCount).coerceIn(0, 100)
    } else {
        0
    }
    return TranscriptionProgressSummary(
        completedCount = completedCount,
        totalCount = totalCount,
        progressPercent = progressPercent,
        overallProgress = overallProgress,
    )
}

/**
 * 文字起こしの進捗を、チャンクリストの上部に重ねて表示するカード。
 *
 * - 1 行目: 左上の「閉じる（×）」ボタン、スピナー、全体の進捗（完了数 / 対象数。強制文字起こし中はその要約）
 * - 2 行目: 進捗バー
 * - 3 行目: 処理中のチャンクの番号・ステップ・補足（エンジンや言語）・経過時間
 * - 最下行: バックグラウンド文字起こし設定チェックボックスと、右下の「中断」ボタン
 *
 * 同じチャンクで [TRANSCRIPTION_STALL_WARNING_MS] 以上止まっているときは、カードを errorContainer の色にし、
 * 3 行目の末尾に短い注意を付ける（行は増やさない）。診断ログはファイル設定シートから開く。
 */
@Composable
internal fun TranscriptionProgressOverlay(
    lookaheadStatus: LookaheadTranscriptionStatus,
    inFlightChunkNumber: Int?,
    onAbortTranscription: () -> Unit,
    modifier: Modifier = Modifier,
    transcribedChunkCount: Int = lookaheadStatus.completedCount,
    totalChunkCount: Int = max(lookaheadStatus.totalTargetCount, lookaheadStatus.completedCount),
    onDismiss: () -> Unit = {},
    onToggleBackgroundTranscription: (Boolean) -> Unit = {},
    onRequestRecognitionModelDownload: (LanguageTag) -> Unit = {},
    onOpenFileSettings: () -> Unit = {},
) {
    val isInspectionMode = LocalInspectionMode.current
    val startedAtMs = lookaheadStatus.inFlightStartedAtEpochMs
    var elapsedMs by remember(startedAtMs, lookaheadStatus.inFlightChunkId) {
        val initial = if (isInspectionMode || startedAtMs == null) {
            0L
        } else {
            (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
        }
        mutableLongStateOf(initial)
    }
    if (!isInspectionMode && startedAtMs != null && lookaheadStatus.inFlightChunkId != null) {
        LaunchedEffect(startedAtMs, lookaheadStatus.inFlightChunkId) {
            while (isActive) {
                elapsedMs = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(0L)
                delay(200L)
            }
        }
    }

    val isStalled = elapsedMs >= TRANSCRIPTION_STALL_WARNING_MS
    val containerColor = when {
        isStalled -> MaterialTheme.colorScheme.errorContainer
        lookaheadStatus.isForcedChunkInFlight -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        isStalled -> MaterialTheme.colorScheme.onErrorContainer
        lookaheadStatus.isForcedChunkInFlight -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    val progressSummary = computeTranscriptionProgressSummary(
        lookaheadStatus = lookaheadStatus,
        transcribedChunkCount = transcribedChunkCount,
        totalChunkCount = totalChunkCount,
    )
    val completedCount = progressSummary.completedCount
    val totalCount = progressSummary.totalCount
    val overallProgress = progressSummary.overallProgress
    val progressPercent = progressSummary.progressPercent
    val summaryText = if (lookaheadStatus.isForcedChunkInFlight) {
        stringResource(
            R.string.player_transcription_progress_forced_summary,
            completedCount,
            totalCount,
        )
    } else {
        stringResource(
            R.string.player_transcription_progress_summary,
            progressPercent,
            completedCount,
            totalCount,
        )
    }
    val stepLabel = transcriptionStepLabel(lookaheadStatus.inFlightStep)
    val chunkPrefix = if (inFlightChunkNumber != null) {
        "#$inFlightChunkNumber: "
    } else {
        ""
    }
    val detailSuffix = lookaheadStatus.inFlightDetail
        ?.takeIf { it.isNotBlank() }
        ?.let { " [$it]" }
        .orEmpty()
    val elapsedSuffix = if (elapsedMs > 0L) {
        String.format(JavaLocale.ROOT, " (%.1fs)", elapsedMs / 1_000f)
    } else {
        ""
    }
    val stallSuffix = if (isStalled) {
        " " + stringResource(R.string.player_transcription_stall_short)
    } else {
        ""
    }

    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, top = 4.dp, end = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Close,
                        contentDescription = stringResource(R.string.player_transcription_progress_close_cd),
                        modifier = Modifier.size(18.dp),
                    )
                }
                ChunkTranscriptionSpinner(stage = ChunkTranscriptionStage.TRANSCRIBING)
                Text(
                    text = summaryText,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 6.dp),
                )
            }

            LinearProgressIndicator(
                progress = { overallProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .height(5.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
                // 既定のトラックの色はカードの色（secondaryContainer）と同じで見えないため、文字色を薄くして使う
                trackColor = contentColor.copy(alpha = 0.18f),
            )

            Row(
                modifier = Modifier.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isStalled) {
                    // 遅延の警告は絵文字ではなくアイコンで示す（文言は stall_short のまま）
                    Icon(
                        imageVector = ChunkFlowIcons.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = "$chunkPrefix$stepLabel$detailSuffix$elapsedSuffix$stallSuffix",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isStalled) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val unpreparedLanguages = remember(lookaheadStatus.recognitionModelIssues) {
                extractUnpreparedModelIssueLanguages(lookaheadStatus.recognitionModelIssues)
            }
            for (issueLang in unpreparedLanguages) {
                val issueState = lookaheadStatus.recognitionModelIssues[issueLang]
                val canDownload = issueState == OnDeviceRecognitionModelState.NOT_INSTALLED ||
                    issueState == OnDeviceRecognitionModelState.DOWNLOAD_FAILED
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, top = 2.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(
                                R.string.player_transcription_progress_model_issue,
                                issueLang.value,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        TextButton(
                            onClick = {
                                if (canDownload) {
                                    onRequestRecognitionModelDownload(issueLang)
                                } else {
                                    onOpenFileSettings()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.align(Alignment.End),
                        ) {
                            Text(
                                text = stringResource(R.string.player_transcription_progress_model_issue_action),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MaterialTheme.shapes.small)
                        .toggleable(
                            value = lookaheadStatus.backgroundEnabled,
                            role = Role.Checkbox,
                            onValueChange = onToggleBackgroundTranscription,
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Checkbox(
                        checked = lookaheadStatus.backgroundEnabled,
                        onCheckedChange = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(
                        text = stringResource(R.string.player_transcription_progress_background_checkbox),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                TextButton(
                    onClick = onAbortTranscription,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.player_transcription_abort_button),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 文字起こしの現在の状態・進行中ステップ・直近の診断履歴を確認し、強制リセットできるダイアログ。
 *
 * プレイヤー画面のファイル設定シート（右上の設定アイコン）の「診断ログ」ボタンから開く。
 */
@Composable
internal fun TranscriptionDiagnosticsDialog(
    lookaheadStatus: LookaheadTranscriptionStatus,
    isAudioAnalysisRunning: Boolean,
    onResetTranscription: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val stepText = lookaheadStatus.inFlightStep?.name ?: "IDLE"
    val statusSummary = buildString {
        appendLine("enabled=${lookaheadStatus.enabled}, engineAvailable=${lookaheadStatus.engineAvailable}")
        appendLine(
            "inFlightChunkId=${lookaheadStatus.inFlightChunkId?.value}, " +
                "isForced=${lookaheadStatus.isForcedChunkInFlight}, step=$stepText",
        )
        appendLine("detail=${lookaheadStatus.inFlightDetail.orEmpty()}")
        appendLine(
            "progress=${lookaheadStatus.completedCount}/${lookaheadStatus.totalTargetCount}, " +
                "queued=${lookaheadStatus.queuedChunkIds.size}, " +
                "skippedEmpty=${lookaheadStatus.skippedEmptyChunkIds.size}",
        )
        appendLine("concurrentAudioAnalysis=$isAudioAnalysisRunning")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.player_transcription_diagnostics_title))
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = statusSummary.trimEnd(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }

                FilledTonalButton(
                    onClick = onResetTranscription,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.player_transcription_diagnostics_reset),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()

                if (lookaheadStatus.diagnostics.isEmpty()) {
                    Text(
                        text = stringResource(R.string.player_transcription_diagnostics_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    for (entry in lookaheadStatus.diagnostics.asReversed()) {
                        DiagnosticEntryCard(entry = entry)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val fullLog = buildString {
                        appendLine("=== Transcription Status ===")
                        appendLine(statusSummary)
                        appendLine("=== Recent Diagnostics ===")
                        for (entry in lookaheadStatus.diagnostics) {
                            appendLine(
                                "[${entry.timestampEpochMs}] [${entry.stage}] ${entry.message}" +
                                    if (entry.isWarningOrError) " (WARN/ERR)" else "",
                            )
                        }
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Chunk Flow STT Diagnostics", fullLog))
                },
            ) {
                Text(text = stringResource(R.string.player_transcription_diagnostics_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.player_sheet_close))
            }
        },
    )
}

@Composable
private fun DiagnosticEntryCard(
    entry: TranscriptionDiagnosticEntry,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (entry.isWarningOrError) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = MaterialTheme.shapes.small,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "[${entry.stage}]",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
