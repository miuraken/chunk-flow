package com.miuraken.chunkflow.feature.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowLoadingIndicator
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.model.DownloadableSttModel
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageSettings
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.SttEngineLabel
import com.miuraken.chunkflow.core.model.SttEngineSelection
import com.miuraken.chunkflow.core.model.SupportedLanguageCatalog
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
internal fun ResegmentSheetContent(
    initialParams: SegmentationParams,
    useGlobalSegmentation: Boolean,
    languagePair: LanguagePair?,
    itemSttOverride: ItemSttOverride,
    folderSttOverride: ItemSttOverride = ItemSttOverride.NONE,
    sttEngineByLanguage: Map<LanguageTag, SttEngine>,
    languageModelInfos: Map<LanguageTag, OnDeviceLanguageModelInfo>,
    enabledLanguages: List<LanguageTag>,
    onChangeLanguages: (LanguagePair) -> Unit,
    onChangeTargetEngine: (SttEngine?) -> Unit,
    onChangeBaseEngine: (SttEngine?) -> Unit,
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
    onClearSttOverride: () -> Unit = {
        onChangeTargetEngine(null)
        onChangeBaseEngine(null)
    },
    onDownloadSttModel: (LanguageTag, SttEngine) -> Unit = { _, _ -> },
    onDeleteSherpaModel: (LanguageTag) -> Unit = {},
    onDeleteLanguageSttModel: (LanguageTag, DownloadableSttModel) -> Unit = { _, _ -> },
    onPurgeDownloadableModel: (DownloadableSttModel) -> Unit = {},
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
    var useGlobalSttModels by rememberSaveable {
        mutableStateOf(itemSttOverride == ItemSttOverride.NONE)
    }
    LaunchedEffect(itemSttOverride) {
        if (itemSttOverride != ItemSttOverride.NONE) {
            useGlobalSttModels = false
        }
    }

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
                // 「言語」タブ: 学習言語とベース言語。言語が選択肢にないときは、案内文の「⚙ 全体設定（言語）」のリンクから開く
                ItemLanguageSettingsSection(
                    languagePair = languagePair,
                    itemSttOverride = itemSttOverride,
                    onChangeLanguages = onChangeLanguages,
                    onOpenLanguageSettings = onOpenGlobalSettings,
                    onChangeTargetEngine = onChangeTargetEngine,
                    onChangeBaseEngine = onChangeBaseEngine,
                    enabledLanguages = enabledLanguages,
                    onToggleEnabledLanguage = onToggleEnabledLanguage,
                    sttEngineByLanguage = sttEngineByLanguage,
                    languageModelInfos = languageModelInfos,
                    showSectionTitle = false,
                )
            }

            1 -> {
                // 「文字起こし」タブ: 最上位にすべてクリアボタン / 音声認識モデルの管理 / エクスポート / インポート / ログ
                OutlinedButton(
                    onClick = { showResetConfirmDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(R.string.player_transcript_reset_button))
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                // 0. 音声認識モデルの管理（学習対象言語・ベース言語のアコーディオン）
                Text(
                    text = stringResource(R.string.player_file_settings_section_speech_models),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val nextUseGlobal = !useGlobalSttModels
                            useGlobalSttModels = nextUseGlobal
                            if (nextUseGlobal) {
                                onClearSttOverride()
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = useGlobalSttModels,
                        onCheckedChange = { checked ->
                            useGlobalSttModels = checked
                            if (checked) {
                                onClearSttOverride()
                            }
                        },
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        // 内包するフォルダの設定が全体設定から変わっていれば、フォルダの設定にフォールバックする
                        text = stringResource(
                            if (folderSttOverride.isEmpty) {
                                R.string.player_segmentation_use_global
                            } else {
                                R.string.player_file_settings_stt_follow_folder
                            },
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }

                val sttAccordionEnabled = !useGlobalSttModels
                val targetLang = languagePair?.target?.takeIf { it.value.isNotBlank() }
                if (targetLang != null) {
                    val targetInfo = resolveLanguageModelInfoForItem(targetLang, languageModelInfos)
                    val targetFallbackEngine = folderSttOverride.targetEngine
                        ?: resolveConfiguredDefaultEngineForLanguage(
                            rawCode = targetLang.value,
                            sttEngineByLanguage = sttEngineByLanguage,
                            languageModelInfos = languageModelInfos,
                        )
                    ItemSttEngineAccordion(
                        headerTitle = stringResource(
                            R.string.player_file_settings_stt_target_lang_header,
                            formatLanguageDisplayName(targetLang),
                        ),
                        info = targetInfo,
                        fallbackEngine = targetFallbackEngine,
                        followsFolderSetting = folderSttOverride.targetEngine != null,
                        preferredEngine = if (useGlobalSttModels) null else itemSttOverride.targetEngine,
                        enabled = sttAccordionEnabled,
                        onPreferredEngineChange = onChangeTargetEngine,
                        onDownloadLanguageModel = { engine -> onDownloadSttModel(targetLang, engine) },
                        onDeleteSherpaModel = { onDeleteSherpaModel(targetLang) },
                        onDeleteLanguageSttModel = { model -> onDeleteLanguageSttModel(targetLang, model) },
                        onPurgeDownloadableModel = onPurgeDownloadableModel,
                    )
                }

                val baseLang = languagePair?.base?.takeIf { it.value.isNotBlank() }
                if (baseLang != null) {
                    val baseInfo = resolveLanguageModelInfoForItem(baseLang, languageModelInfos)
                    val baseFallbackEngine = folderSttOverride.baseEngine
                        ?: resolveConfiguredDefaultEngineForLanguage(
                            rawCode = baseLang.value,
                            sttEngineByLanguage = sttEngineByLanguage,
                            languageModelInfos = languageModelInfos,
                        )
                    ItemSttEngineAccordion(
                        headerTitle = stringResource(
                            R.string.player_file_settings_stt_base_lang_header,
                            formatLanguageDisplayName(baseLang),
                        ),
                        info = baseInfo,
                        fallbackEngine = baseFallbackEngine,
                        followsFolderSetting = folderSttOverride.baseEngine != null,
                        preferredEngine = if (useGlobalSttModels) null else itemSttOverride.baseEngine,
                        enabled = sttAccordionEnabled,
                        onPreferredEngineChange = onChangeBaseEngine,
                        onDownloadLanguageModel = { engine -> onDownloadSttModel(baseLang, engine) },
                        onDeleteSherpaModel = { onDeleteSherpaModel(baseLang) },
                        onDeleteLanguageSttModel = { model -> onDeleteLanguageSttModel(baseLang, model) },
                        onPurgeDownloadableModel = onPurgeDownloadableModel,
                    )
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
                SegmentationSlider(
                    value = minSilenceMs.toFloat(),
                    valueRange = 200f..1_500f,
                    enabled = slidersEnabled,
                    onValueChange = { minSilenceMs = ((it / 50f).roundToInt() * 50).coerceIn(200, 1_500) },
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
                SegmentationSlider(
                    value = prePaddingMs.toFloat(),
                    valueRange = 0f..500f,
                    enabled = slidersEnabled,
                    onValueChange = { prePaddingMs = ((it / 25f).roundToInt() * 25).coerceIn(0, 500) },
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
                SegmentationSlider(
                    value = postPaddingMs.toFloat(),
                    valueRange = 0f..500f,
                    enabled = slidersEnabled,
                    onValueChange = { postPaddingMs = ((it / 25f).roundToInt() * 25).coerceIn(0, 500) },
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
                SegmentationSlider(
                    value = thresholdRatio,
                    valueRange = 0.05f..0.80f,
                    enabled = slidersEnabled,
                    onValueChange = { thresholdRatio = ((it * 20f).roundToInt() / 20f).coerceIn(0.05f, 0.80f) },
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

/**
 * 分割パラメータのスライダー。非推奨の `Slider(value, onValueChange)` ではなく `SliderState` を使う（material3 1.5）。
 * 呼び出し側が値を丸めるため、外から来た [value] と state がずれたときは `LaunchedEffect` で state に同期する
 * （`core:designsystem` の `SpeedSheetContent` と同じ手法）。
 */
@Composable
private fun SegmentationSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sliderState = rememberSliderState(
        value = value,
        trackRange = valueRange,
    )
    LaunchedEffect(sliderState, value) {
        if (sliderState.value != value) {
            sliderState.value = value
        }
    }
    Slider(
        state = sliderState,
        modifier = modifier,
        enabled = enabled,
        onValueChange = { newValue ->
            sliderState.value = newValue
            onValueChange(newValue)
        },
    )
}

internal fun resolveLanguageModelInfoForItem(
    language: LanguageTag,
    languageModelInfos: Map<LanguageTag, OnDeviceLanguageModelInfo>,
): OnDeviceLanguageModelInfo {
    val normLang = LanguageSettings.normalize(language)
    val mgmtLang = LanguageTag(SupportedLanguageCatalog.normalizeToModelManagementCode(language.value))
    val raw = languageModelInfos[language]
        ?: languageModelInfos[normLang]
        ?: languageModelInfos[mgmtLang]
        ?: OnDeviceLanguageModelInfo(language = language)
    return DetailedTranscriptionDialogState.enrichWithCatalog(raw, language)
}

internal fun isItemSttEngineSelectable(
    info: OnDeviceLanguageModelInfo,
    engine: SttEngine?,
): Boolean {
    if (!SttEngineSelection.isAnyEngineSupported(info)) return false
    if (engine == null) return true
    return info.statusOf(engine) != OnDeviceLanguageModelStatus.UNSUPPORTED
}

private const val DEFAULT_SHERPA_TINY_SIZE_MB = 113

private fun resolveSherpaTinySizeMb(sizeBytes: Long): Int {
    if (sizeBytes <= 0L) return DEFAULT_SHERPA_TINY_SIZE_MB
    return ((sizeBytes + 512L * 1024L) / (1024L * 1024L)).toInt().coerceAtLeast(1)
}

/**
 * ファイルの設定の「音声認識モデルの管理」の、言語 1 つ分のアコーディオン。
 *
 * 先頭の選択肢（ファイルでは上書きしない）は、内包するフォルダの設定がこの言語のエンジンを上書きしていれば
 * （[followsFolderSetting]）「フォルダー設定に従う (エンジン)」、していなければ「全体設定に従う (エンジン)」と表示する
 * （ファイル個別設定 ＞ フォルダ設定 ＞ 全体設定）。
 *
 * @param fallbackEngine ファイルで上書きしないときに使われるエンジン（フォルダの設定、なければ全体設定のエンジン）。
 * @param followsFolderSetting この言語で、内包するフォルダの設定がエンジンを上書きしているかどうか。
 */
@Composable
private fun ItemSttEngineAccordion(
    headerTitle: String,
    info: OnDeviceLanguageModelInfo,
    fallbackEngine: SttEngine,
    followsFolderSetting: Boolean,
    preferredEngine: SttEngine?,
    enabled: Boolean,
    onPreferredEngineChange: (SttEngine?) -> Unit,
    onDownloadLanguageModel: (SttEngine) -> Unit = {},
    onDeleteSherpaModel: () -> Unit = {},
    onDeleteLanguageSttModel: (DownloadableSttModel) -> Unit = {},
    onPurgeDownloadableModel: (DownloadableSttModel) -> Unit = {},
    initialExpanded: Boolean = false,
) {
    val expanded = rememberSaveable { mutableStateOf(initialExpanded) }
    val enrichedInfo = DetailedTranscriptionDialogState.enrichWithCatalog(info, info.language)
    val anyEngineSupported = SttEngineSelection.isAnyEngineSupported(enrichedInfo)
    val followGlobalLabel = when {
        anyEngineSupported && followsFolderSetting -> stringResource(
            R.string.player_file_settings_stt_follow_folder_with_resolved,
            itemSttEngineDisplayName(fallbackEngine),
        )

        anyEngineSupported -> stringResource(
            R.string.player_file_settings_stt_follow_global_with_resolved,
            itemSttEngineDisplayName(fallbackEngine),
        )

        else -> {
            val followLabel = stringResource(
                if (followsFolderSetting) {
                    R.string.player_file_settings_stt_follow_folder
                } else {
                    R.string.player_segmentation_use_global
                },
            )
            "$followLabel (${stringResource(R.string.player_file_settings_stt_status_unsupported)})"
        }
    }
    val effectivePreferred = preferredEngine?.takeIf { isItemSttEngineSelectable(enrichedInfo, it) }
    val currentEngineLabel = when {
        !anyEngineSupported -> stringResource(R.string.player_file_settings_stt_status_unsupported)
        effectivePreferred == null -> followGlobalLabel
        else -> itemSttEngineDisplayName(effectivePreferred)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.6f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(
                    selected = expanded.value,
                    onClick = { expanded.value = !expanded.value },
                    role = Role.Button,
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headerTitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.player_file_settings_stt_current_engine, currentEngineLabel),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = if (expanded.value) ChunkFlowIcons.ExpandLess else ChunkFlowIcons.ExpandMore,
                contentDescription = null,
            )
        }

        AnimatedVisibility(visible = expanded.value) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, bottom = 8.dp)
                    .selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_file_settings_stt_priority_engine),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                val selectableEngines = SttEngineSelection.selectableEnginesForLanguage(
                    info = enrichedInfo,
                    currentPreferred = effectivePreferred,
                )
                val options: List<Pair<SttEngine?, String>> = buildList {
                    add(null to followGlobalLabel)
                    for (engine in selectableEngines) {
                        add(engine to formatItemSttEngineOptionLabel(enrichedInfo, engine))
                    }
                }

                options.forEach { (engine, label) ->
                    val isOptionEnabled = enabled && isItemSttEngineSelectable(enrichedInfo, engine)
                    val isSelected = anyEngineSupported && effectivePreferred == engine
                    val engineStatus = engine?.let { enrichedInfo.statusOf(it) }
                    val downloadableModel = engine?.downloadableModel
                    val isDownloadableModelInstalled =
                        downloadableModel != null && engineStatus == OnDeviceLanguageModelStatus.INSTALLED
                    val isDownloadableModelSupported =
                        downloadableModel != null &&
                            downloadableModel.isLanguageSupported(enrichedInfo.language)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                enabled = isOptionEnabled,
                                onClick = {
                                    if (isOptionEnabled) {
                                        onPreferredEngineChange(engine)
                                    }
                                },
                                role = Role.RadioButton,
                            )
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            enabled = isOptionEnabled,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isOptionEnabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(start = 8.dp),
                        )
                        when {
                            isDownloadableModelSupported &&
                                (
                                    engineStatus == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
                                        engineStatus == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                                    ) -> {
                                val langSizeMb = if (engine == SttEngine.SHERPA_ONNX && enrichedInfo.sherpaModelSizeBytes > 0L) {
                                    resolveSherpaTinySizeMb(enrichedInfo.sherpaModelSizeBytes)
                                } else {
                                    downloadableModel.approxSizeMbForLanguage(enrichedInfo.language)
                                }
                                OutlinedButton(
                                    onClick = { onDownloadLanguageModel(engine) },
                                    enabled = enabled,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .height(28.dp),
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.player_file_settings_stt_action_download_with_size,
                                            langSizeMb,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }

                            isDownloadableModelSupported &&
                                engineStatus == OnDeviceLanguageModelStatus.DOWNLOADING -> {
                                ChunkFlowLoadingIndicator(
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .size(20.dp),
                                )
                            }

                            (engine == SttEngine.ML_KIT || engine == SttEngine.SPEECH_RECOGNIZER) &&
                                (
                                    engineStatus == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
                                        engineStatus == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                                    ) -> {
                                OutlinedButton(
                                    onClick = { onDownloadLanguageModel(engine) },
                                    enabled = enabled,
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .height(28.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.player_file_settings_stt_action_download),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }

                            (engine == SttEngine.ML_KIT || engine == SttEngine.SPEECH_RECOGNIZER) &&
                                engineStatus == OnDeviceLanguageModelStatus.DOWNLOADING -> {
                                ChunkFlowLoadingIndicator(
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .size(20.dp),
                                )
                            }

                            downloadableModel != null && isDownloadableModelInstalled -> {
                                IconButton(
                                    onClick = {
                                        when {
                                            engine == SttEngine.SHERPA_ONNX -> onDeleteSherpaModel()
                                            downloadableModel.isPerLanguageModel -> onDeleteLanguageSttModel(downloadableModel)
                                            else -> onPurgeDownloadableModel(downloadableModel)
                                        }
                                    },
                                    enabled = enabled,
                                    modifier = Modifier
                                        .padding(start = 4.dp)
                                        .size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = ChunkFlowIcons.Delete,
                                        contentDescription = stringResource(
                                            R.string.player_file_settings_stt_action_delete,
                                        ),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun itemSttEngineDisplayName(engine: SttEngine): String = when (engine) {
    SttEngine.ML_KIT -> stringResource(R.string.player_settings_stt_engine_mlkit)
    SttEngine.SPEECH_RECOGNIZER -> stringResource(R.string.player_file_settings_stt_engine_speech_recognizer)
    SttEngine.SHERPA_ONNX -> stringResource(R.string.player_file_settings_stt_engine_sherpa_onnx)
    SttEngine.SHERPA_ONNX_SENSE_VOICE -> stringResource(R.string.player_file_settings_stt_engine_sherpa_sense_voice)
    SttEngine.SHERPA_ONNX_WHISPER_BASE -> stringResource(R.string.player_file_settings_stt_engine_sherpa_whisper_base)
    SttEngine.SHERPA_ONNX_WHISPER_SMALL -> stringResource(R.string.player_file_settings_stt_engine_sherpa_whisper_small)
    SttEngine.MOONSHINE_TINY -> stringResource(R.string.player_file_settings_stt_engine_moonshine_tiny)
    SttEngine.MOONSHINE_BASE -> stringResource(R.string.player_file_settings_stt_engine_moonshine_base)
    SttEngine.OPENAI_API -> stringResource(R.string.player_file_settings_stt_engine_openai_api)
    SttEngine.GROQ_API -> stringResource(R.string.player_file_settings_stt_engine_groq_api)
    SttEngine.GEMINI_API -> stringResource(R.string.player_file_settings_stt_engine_gemini_api)
}

@Composable
private fun itemSttEngineLabelText(label: SttEngineLabel): String = when (label) {
    SttEngineLabel.RECOMMENDED -> stringResource(R.string.player_stt_label_recommended)
    SttEngineLabel.HIGH_ACCURACY -> stringResource(R.string.player_stt_label_high_accuracy)
    SttEngineLabel.FAST -> stringResource(R.string.player_stt_label_fast)
    SttEngineLabel.SLOW -> stringResource(R.string.player_stt_label_slow)
}

@Composable
private fun formatItemSttEngineOptionLabel(
    enrichedInfo: OnDeviceLanguageModelInfo,
    engine: SttEngine,
): String {
    val status = enrichedInfo.statusOf(engine)
    val rawLabel = when (engine) {
        SttEngine.ML_KIT -> formatItemEngineOptionWithStatus(
            baseName = stringResource(R.string.player_file_settings_stt_engine_mlkit_radio),
            status = status,
        )

        SttEngine.SPEECH_RECOGNIZER -> formatItemEngineOptionWithStatus(
            baseName = stringResource(R.string.player_file_settings_stt_engine_speech_recognizer),
            status = status,
        )

        SttEngine.SHERPA_ONNX -> {
            val baseName = stringResource(R.string.player_file_settings_stt_engine_sherpa_onnx)
            val sizeMb = resolveSherpaTinySizeMb(enrichedInfo.sherpaModelSizeBytes)
            when (status) {
                OnDeviceLanguageModelStatus.INSTALLED ->
                    stringResource(R.string.player_file_settings_stt_engine_sherpa_downloaded, sizeMb)

                OnDeviceLanguageModelStatus.NOT_INSTALLED -> baseName

                else -> formatItemEngineOptionWithStatus(
                    baseName = baseName,
                    status = status,
                )
            }
        }

        SttEngine.MOONSHINE_TINY,
        SttEngine.MOONSHINE_BASE,
        -> {
            val baseName = itemSttEngineDisplayName(engine)
            val sizeMb = engine.downloadableModel?.approxSizeMbForLanguage(enrichedInfo.language) ?: 100
            when (status) {
                OnDeviceLanguageModelStatus.INSTALLED ->
                    stringResource(R.string.player_file_settings_stt_engine_model_downloaded_format, baseName, sizeMb)

                OnDeviceLanguageModelStatus.NOT_INSTALLED -> baseName

                else -> formatItemEngineOptionWithStatus(
                    baseName = baseName,
                    status = status,
                )
            }
        }

        SttEngine.SHERPA_ONNX_SENSE_VOICE,
        SttEngine.SHERPA_ONNX_WHISPER_BASE,
        SttEngine.SHERPA_ONNX_WHISPER_SMALL,
        -> {
            val baseName = itemSttEngineDisplayName(engine)
            val sizeMb = engine.downloadableModel?.estimatedSizeMb ?: 100
            when (status) {
                OnDeviceLanguageModelStatus.INSTALLED ->
                    stringResource(R.string.player_file_settings_stt_engine_model_downloaded_format, baseName, sizeMb)

                OnDeviceLanguageModelStatus.NOT_INSTALLED -> baseName

                else -> formatItemEngineOptionWithStatus(
                    baseName = baseName,
                    status = status,
                )
            }
        }

        SttEngine.OPENAI_API,
        SttEngine.GROQ_API,
        SttEngine.GEMINI_API,
        -> {
            val baseName = itemSttEngineDisplayName(engine)
            when (status) {
                OnDeviceLanguageModelStatus.INSTALLED -> baseName

                OnDeviceLanguageModelStatus.NOT_INSTALLED ->
                    "$baseName (${stringResource(R.string.player_file_settings_stt_status_api_key_not_configured)})"

                else -> formatItemEngineOptionWithStatus(baseName = baseName, status = status)
            }
        }
    }
    val labels = SttEngineSelection.labelsFor(enrichedInfo.language, engine)
    if (labels.isEmpty()) {
        return rawLabel
    }
    val badgeText = labels.map { itemSttEngineLabelText(it) }.joinToString("・")
    return "$rawLabel [$badgeText]"
}

@Composable
private fun formatItemEngineOptionWithStatus(
    baseName: String,
    status: OnDeviceLanguageModelStatus,
): String = when (status) {
    OnDeviceLanguageModelStatus.INSTALLED -> baseName

    OnDeviceLanguageModelStatus.NOT_INSTALLED ->
        "$baseName (${stringResource(R.string.player_file_settings_stt_status_not_installed)})"

    OnDeviceLanguageModelStatus.DOWNLOADING ->
        "$baseName (${stringResource(R.string.player_file_settings_stt_status_downloading)})"

    OnDeviceLanguageModelStatus.DOWNLOAD_FAILED ->
        "$baseName (${stringResource(R.string.player_file_settings_stt_status_download_failed)})"

    OnDeviceLanguageModelStatus.CHECKING ->
        "$baseName (${stringResource(R.string.player_file_settings_stt_status_checking)})"

    OnDeviceLanguageModelStatus.UNSUPPORTED ->
        "$baseName (${stringResource(R.string.player_file_settings_stt_status_unsupported)})"
}
