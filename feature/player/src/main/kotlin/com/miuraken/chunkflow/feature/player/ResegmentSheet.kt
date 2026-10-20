package com.miuraken.chunkflow.feature.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.SupportedLanguageCatalog
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun ResegmentSheetContent(
    initialParams: SegmentationParams,
    useGlobalSegmentation: Boolean,
    languagePair: LanguagePair?,
    itemSttOverride: com.miuraken.chunkflow.core.model.ItemSttOverride,
    sttEngineByLanguage: Map<LanguageTag, com.miuraken.chunkflow.core.model.SttEngine>,
    languageModelInfos: Map<LanguageTag, com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo>,
    enabledLanguages: List<com.miuraken.chunkflow.core.model.LanguageTag>,
    onChangeLanguages: (LanguagePair) -> Unit,
    onChangeTargetEngine: (com.miuraken.chunkflow.core.model.SttEngine?) -> Unit,
    onChangeBaseEngine: (com.miuraken.chunkflow.core.model.SttEngine?) -> Unit,
    onToggleEnabledLanguage: (com.miuraken.chunkflow.core.model.LanguageTag) -> Unit = {},
    transcriptOffsetMs: Int,
    onAdjustTranscriptOffset: (Int) -> Unit,
    onAutoAlignTranscriptOffset: () -> Unit,
    onImportTranscript: () -> Unit,
    onExportTranscript: () -> Unit,
    onResetTranscripts: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onToggleUseGlobalSegmentation: (Boolean) -> Unit,
    onApply: (SegmentationParams) -> Unit,
    onOpenGlobalSettings: () -> Unit,
    onClose: () -> Unit,
    onResetSegmentation: (SegmentationParams?) -> Unit = {},
    initialTabIndex: Int = 0,
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(initialTabIndex) }
    var minSilenceMs by remember(initialParams) { mutableIntStateOf(initialParams.minSilenceMs) }
    var prePaddingMs by remember(initialParams) { mutableIntStateOf(initialParams.prePaddingMs) }
    var postPaddingMs by remember(initialParams) { mutableIntStateOf(initialParams.postPaddingMs) }
    var thresholdRatio by remember(initialParams) { mutableFloatStateOf(initialParams.thresholdRatio) }
    var showResetConfirmDialog by remember { mutableStateOf(false) }

    val formattedOffset = if (transcriptOffsetMs >= 0) {
        "+$transcriptOffsetMs"
    } else {
        transcriptOffsetMs.toString()
    }

    val tabTitles = listOf(
        stringResource(R.string.player_file_settings_tab_language),
        stringResource(R.string.player_file_settings_tab_transcript_file),
        stringResource(R.string.player_file_settings_tab_segmentation),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.player_file_settings_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onClose) {
                Text(text = stringResource(R.string.player_sheet_close))
            }
        }

        PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTabIndex == index,
                    onClick = { selectedTabIndex = index },
                    text = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }

        when (selectedTabIndex) {
            0 -> {
                // 「言語」タブ: 学習言語、ベース言語とそれぞれの文字起こしエンジン
                ItemLanguageSettingsSection(
                    languagePair = languagePair,
                    itemSttOverride = itemSttOverride,
                    onChangeLanguages = onChangeLanguages,
                    onChangeTargetEngine = onChangeTargetEngine,
                    onChangeBaseEngine = onChangeBaseEngine,
                    enabledLanguages = enabledLanguages,
                    onToggleEnabledLanguage = onToggleEnabledLanguage,
                    sttEngineByLanguage = sttEngineByLanguage,
                    languageModelInfos = languageModelInfos,
                    showSectionTitle = false,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedButton(
                    onClick = onOpenGlobalSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.player_open_global_settings))
                }
            }

            1 -> {
                // 「文字起こし」タブ: 最上位にリセットボタン / エクスポート / インポート / ログ
                OutlinedButton(
                    onClick = { showResetConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.player_transcript_reset_button))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 1. エクスポート
                Text(
                    text = stringResource(R.string.player_file_settings_section_export),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = onExportTranscript,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.player_file_settings_section_export))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 2. インポート（文字起こし・字幕ファイルのインポート + 時刻補正）
                Text(
                    text = stringResource(R.string.player_file_settings_section_import),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FilledTonalButton(
                    onClick = onImportTranscript,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Description,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.player_transcript_file_import))
                }
                Text(
                    text = stringResource(R.string.player_offset_section_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.player_offset_current_value, formattedOffset),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(-50, -10, 10, 50).forEach { delta ->
                        val label = if (delta > 0) "+${delta}ms" else "${delta}ms"
                        OutlinedButton(
                            onClick = { onAdjustTranscriptOffset(delta) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                        ) {
                            Text(text = label, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                FilledTonalButton(
                    onClick = onAutoAlignTranscriptOffset,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.player_offset_auto_align))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 3. ログ
                Text(
                    text = stringResource(R.string.player_file_settings_section_log),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Description,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = stringResource(R.string.player_transcription_diagnostics_button))
                }
            }

            2 -> {
                // 「チャンク分割」タブ: 最上部にリセットボタン + 全体設定に従うチェックボックス + 個別調整スライダー
                OutlinedButton(
                    onClick = {
                        if (useGlobalSegmentation) {
                            onResetSegmentation(null)
                        } else {
                            val updatedMergeGap = max(initialParams.mergeGapMs, minSilenceMs)
                            onResetSegmentation(
                                initialParams.copy(
                                    minSilenceMs = minSilenceMs,
                                    mergeGapMs = updatedMergeGap,
                                    prePaddingMs = prePaddingMs,
                                    postPaddingMs = postPaddingMs,
                                    thresholdRatio = thresholdRatio,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.player_resegment_reset_button))
                }
                Text(
                    text = stringResource(R.string.player_resegment_reset_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggleUseGlobalSegmentation(!useGlobalSegmentation) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = useGlobalSegmentation,
                        onCheckedChange = { checked -> onToggleUseGlobalSegmentation(checked) },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.player_segmentation_use_global),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }

                val slidersEnabled = !useGlobalSegmentation

                Text(
                    text = stringResource(R.string.player_resegment_min_silence, minSilenceMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (slidersEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
                Slider(
                    value = minSilenceMs.toFloat(),
                    onValueChange = { minSilenceMs = ((it / 50f).roundToInt() * 50).coerceIn(200, 1_500) },
                    valueRange = 200f..1_500f,
                    enabled = slidersEnabled,
                )

                Text(
                    text = stringResource(R.string.player_resegment_pre_padding, prePaddingMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (slidersEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
                Slider(
                    value = prePaddingMs.toFloat(),
                    onValueChange = { prePaddingMs = ((it / 25f).roundToInt() * 25).coerceIn(0, 500) },
                    valueRange = 0f..500f,
                    enabled = slidersEnabled,
                )

                Text(
                    text = stringResource(R.string.player_resegment_post_padding, postPaddingMs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (slidersEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
                Slider(
                    value = postPaddingMs.toFloat(),
                    onValueChange = { postPaddingMs = ((it / 25f).roundToInt() * 25).coerceIn(0, 500) },
                    valueRange = 0f..500f,
                    enabled = slidersEnabled,
                )

                Text(
                    text = stringResource(
                        R.string.player_resegment_threshold,
                        String.format(Locale.ROOT, "%.2f", thresholdRatio),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (slidersEnabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                )
                Slider(
                    value = thresholdRatio,
                    onValueChange = { thresholdRatio = ((it * 20f).roundToInt() / 20f).coerceIn(0.05f, 0.80f) },
                    valueRange = 0.05f..0.80f,
                    enabled = slidersEnabled,
                )

                Button(
                    onClick = {
                        val updatedMergeGap = max(initialParams.mergeGapMs, minSilenceMs)
                        onApply(
                            initialParams.copy(
                                minSilenceMs = minSilenceMs,
                                mergeGapMs = updatedMergeGap,
                                prePaddingMs = prePaddingMs,
                                postPaddingMs = postPaddingMs,
                                thresholdRatio = thresholdRatio,
                            ),
                        )
                    },
                    enabled = slidersEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.player_resegment_apply))
                }
            }
        }
    }

    if (showResetConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showResetConfirmDialog = false },
            title = {
                Text(text = stringResource(R.string.player_transcript_reset_confirm_title))
            },
            text = {
                Text(text = stringResource(R.string.player_transcript_reset_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirmDialog = false
                        onResetTranscripts()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text(text = stringResource(R.string.player_transcript_reset_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirmDialog = false }) {
                    Text(text = stringResource(R.string.player_chunk_edit_cancel))
                }
            },
        )
    }
}

/**
 * 言語タグから表示用の言語名（例: `韓国語 (ko)`）を返す。
 */
internal fun formatLanguageDisplayName(language: LanguageTag): String {
    val entry = SupportedLanguageCatalog.findByCode(language.value)
    return if (entry != null) {
        "${entry.japaneseName} (${language.value})"
    } else {
        language.value
    }
}

/**
 * バックグラウンド文字起こし有効化時の `SpeechRecognizer` 制限警告ダイアログの本文を組み立てる。
 */
internal fun formatBackgroundSpeechRecognizerWarningMessage(
    warning: BackgroundSpeechRecognizerWarningState,
): String {
    val langLabel = warning.speechRecognizerLanguages.joinToString("・") {
        formatLanguageDisplayName(it)
    }
    return buildString {
        append("${langLabel}では標準の音声認識（SpeechRecognizer）が使用されるため、バックグラウンドでは処理が進まない可能性があります。")
        if (warning.sherpaSupportedLanguages.isNotEmpty()) {
            val sherpaLangLabel = warning.sherpaSupportedLanguages.joinToString("・") {
                formatLanguageDisplayName(it)
            }
            append("\n\n${sherpaLangLabel}ではオフラインモデルの sherpa-onnx が利用可能です。sherpa-onnx の導入を検討してください。")
        }
    }
}

@Composable
internal fun BackgroundSpeechRecognizerWarningDialog(
    warning: BackgroundSpeechRecognizerWarningState,
    onDismiss: () -> Unit,
) {
    val message = remember(warning) { formatBackgroundSpeechRecognizerWarningMessage(warning) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.player_background_speech_recognizer_warning_title))
        },
        text = {
            Text(text = message)
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.player_background_speech_recognizer_warning_close))
            }
        },
    )
}
