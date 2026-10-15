package com.miuraken.chunkflow.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.PhoneticGuideKind
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.UserSettings
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 設定画面の Route（F9.1、F3.2、F3.3、F5.2、F6.3）。
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsScreen(
        state = uiState,
        onEvent = { event ->
            when (event) {
                SettingsEvent.Back -> onBack()
                else -> viewModel.onEvent(event)
            }
        },
        modifier = modifier,
    )
}

/**
 * 設定画面のステートレス Composable。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onEvent: (SettingsEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = { onEvent(SettingsEvent.Back) }) {
                        Icon(
                            imageVector = ChunkFlowIcons.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
            )
        },
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PlaybackSettingsSection(
                seekBackMs = state.seekBackMs,
                seekForwardMs = state.seekForwardMs,
                defaultSpeed = state.defaultSpeed,
                defaultAutoPause = state.defaultAutoPause,
                lookaheadTranscriptionEnabled = state.lookaheadTranscriptionEnabled,
                onEvent = onEvent,
            )

            SegmentationDefaultsSection(
                params = state.defaultSegmentationParams,
                onEvent = onEvent,
            )

            LanguagePairSection(
                targetLanguage = state.defaultLanguagePair.target,
                baseLanguage = state.defaultLanguagePair.base,
                enabledLanguages = state.enabledLanguages,
                selectableLanguages = state.selectableLanguages,
                onEvent = onEvent,
            )

            if (state.showChineseScriptOption ||
                state.showCantoneseScriptOption ||
                state.targetPhoneticGuideKind != null
            ) {
                ScriptPreferenceSection(
                    showChineseScriptOption = state.showChineseScriptOption,
                    showCantoneseScriptOption = state.showCantoneseScriptOption,
                    chineseScript = state.chineseScript,
                    cantoneseScript = state.cantoneseScript,
                    targetPhoneticGuideKind = state.targetPhoneticGuideKind,
                    showTargetRomanization = state.showTargetRomanization,
                    onEvent = onEvent,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackSettingsSection(
    seekBackMs: Long,
    seekForwardMs: Long,
    defaultSpeed: Float,
    defaultAutoPause: AutoPause,
    lookaheadTranscriptionEnabled: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_section_playback)) {
        Text(
            text = stringResource(R.string.settings_seek_back_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsUiState.SEEK_BACK_OPTIONS_MS.forEach { optionMs ->
                val sec = (optionMs / 1_000L).toInt()
                FilterChip(
                    selected = seekBackMs == optionMs,
                    onClick = { onEvent(SettingsEvent.SetSeekBackMs(optionMs)) },
                    label = { Text(text = stringResource(R.string.settings_seconds_format, sec)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.settings_seek_forward_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsUiState.SEEK_FORWARD_OPTIONS_MS.forEach { optionMs ->
                val sec = (optionMs / 1_000L).toInt()
                FilterChip(
                    selected = seekForwardMs == optionMs,
                    onClick = { onEvent(SettingsEvent.SetSeekForwardMs(optionMs)) },
                    label = { Text(text = stringResource(R.string.settings_seconds_format, sec)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_default_speed_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = formatSpeed(defaultSpeed),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }

        Slider(
            value = defaultSpeed,
            onValueChange = { onEvent(SettingsEvent.SetDefaultSpeed(it)) },
            valueRange = 0.5f..2.0f,
            steps = 29,
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsUiState.SPEED_PRESETS.forEach { preset ->
                val isSelected = abs(defaultSpeed - preset) < 0.01f
                FilterChip(
                    selected = isSelected,
                    onClick = { onEvent(SettingsEvent.SetDefaultSpeed(preset)) },
                    label = { Text(text = formatSpeed(preset)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.settings_default_auto_pause_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsUiState.AUTO_PAUSE_OPTIONS.forEach { option ->
                FilterChip(
                    selected = defaultAutoPause == option,
                    onClick = { onEvent(SettingsEvent.SetDefaultAutoPause(option)) },
                    label = { Text(text = autoPauseLabel(option)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    onEvent(SettingsEvent.SetLookaheadTranscriptionEnabled(!lookaheadTranscriptionEnabled))
                }
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_lookahead_transcription_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.settings_lookahead_transcription_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = lookaheadTranscriptionEnabled,
                onCheckedChange = { enabled ->
                    onEvent(SettingsEvent.SetLookaheadTranscriptionEnabled(enabled))
                },
            )
        }
    }
}

@Composable
private fun SegmentationDefaultsSection(
    params: SegmentationParams,
    onEvent: (SettingsEvent) -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_section_segmentation)) {
        Text(
            text = stringResource(R.string.settings_segmentation_min_silence, params.minSilenceMs),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = params.minSilenceMs.toFloat().coerceIn(150f, 1_000f),
            onValueChange = { value ->
                val rounded = ((value / 50f).roundToInt() * 50).coerceIn(150, 1_000)
                onEvent(
                    SettingsEvent.SetDefaultSegmentationParams(
                        params.copy(minSilenceMs = rounded),
                    ),
                )
            },
            valueRange = 150f..1_000f,
            steps = 16,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = stringResource(R.string.settings_segmentation_pre_padding, params.prePaddingMs),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = params.prePaddingMs.toFloat().coerceIn(0f, 400f),
            onValueChange = { value ->
                val rounded = ((value / 25f).roundToInt() * 25).coerceIn(0, 400)
                onEvent(
                    SettingsEvent.SetDefaultSegmentationParams(
                        params.copy(prePaddingMs = rounded),
                    ),
                )
            },
            valueRange = 0f..400f,
            steps = 15,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = stringResource(R.string.settings_segmentation_post_padding, params.postPaddingMs),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = params.postPaddingMs.toFloat().coerceIn(0f, 400f),
            onValueChange = { value ->
                val rounded = ((value / 25f).roundToInt() * 25).coerceIn(0, 400)
                onEvent(
                    SettingsEvent.SetDefaultSegmentationParams(
                        params.copy(postPaddingMs = rounded),
                    ),
                )
            },
            valueRange = 0f..400f,
            steps = 15,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = { onEvent(SettingsEvent.ResetDefaultSegmentationParams) }) {
                Text(text = stringResource(R.string.settings_segmentation_reset))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguagePairSection(
    targetLanguage: LanguageTag,
    baseLanguage: LanguageTag?,
    enabledLanguages: Set<LanguageTag>,
    selectableLanguages: List<LanguageTag>,
    onEvent: (SettingsEvent) -> Unit,
) {
    var showEnabledLanguagesDialog by remember { mutableStateOf(false) }
    val langPalette = chunkFlowLanguagePalette()

    SettingsCard(title = stringResource(R.string.settings_section_language)) {
        OutlinedButton(
            onClick = { showEnabledLanguagesDialog = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = ChunkFlowIcons.Translate,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(
                    R.string.settings_enabled_languages_button,
                    enabledLanguages.size,
                ),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Surface(
            color = langPalette.targetRowBackground,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_target_language_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = langPalette.onTargetBadgeContainer,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    selectableLanguages.forEach { language ->
                        FilterChip(
                            selected = targetLanguage == language,
                            onClick = { onEvent(SettingsEvent.SetTargetLanguage(language)) },
                            label = { Text(text = languageDisplayName(language)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = langPalette.targetBadgeContainer,
                                selectedLabelColor = langPalette.onTargetBadgeContainer,
                            ),
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Surface(
            color = langPalette.baseRowBackground,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_base_language_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    color = langPalette.onBaseBadgeContainer,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = baseLanguage == null,
                        onClick = { onEvent(SettingsEvent.SetBaseLanguage(null)) },
                        label = { Text(text = stringResource(R.string.settings_base_language_none)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                    selectableLanguages.forEach { language ->
                        FilterChip(
                            selected = baseLanguage == language,
                            onClick = { onEvent(SettingsEvent.SetBaseLanguage(language)) },
                            label = { Text(text = languageDisplayName(language)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = langPalette.baseBadgeContainer,
                                selectedLabelColor = langPalette.onBaseBadgeContainer,
                            ),
                        )
                    }
                }
            }
        }
    }

    if (showEnabledLanguagesDialog) {
        EnabledLanguagesDialog(
            initialEnabled = enabledLanguages,
            onConfirm = { updatedSet ->
                showEnabledLanguagesDialog = false
                onEvent(SettingsEvent.SetEnabledLanguages(updatedSet))
            },
            onDismiss = { showEnabledLanguagesDialog = false },
        )
    }
}

@Composable
private fun EnabledLanguagesDialog(
    initialEnabled: Set<LanguageTag>,
    onConfirm: (Set<LanguageTag>) -> Unit,
    onDismiss: () -> Unit,
) {
    val allOptions = remember { SettingsUiState.availableDeviceLanguages() }
    var searchQuery by remember { mutableStateOf("") }
    var selectedTags by remember(initialEnabled) {
        mutableStateOf(initialEnabled.ifEmpty { UserSettings.DEFAULT_ENABLED_LANGUAGES })
    }

    val filteredOptions = remember(allOptions, searchQuery) {
        val query = searchQuery.trim()
        if (query.isEmpty()) {
            allOptions
        } else {
            allOptions.filter { option ->
                option.displayName.contains(query, ignoreCase = true) ||
                    option.tag.value.contains(query, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.settings_enabled_languages_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_enabled_languages_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text(text = stringResource(R.string.settings_enabled_languages_search_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(
                    onClick = { selectedTags = UserSettings.DEFAULT_ENABLED_LANGUAGES },
                ) {
                    Text(text = stringResource(R.string.settings_enabled_languages_reset_default))
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                ) {
                    items(
                        items = filteredOptions,
                        key = { it.tag.value },
                    ) { option ->
                        val isChecked = option.tag in selectedTags
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedTags = if (isChecked) {
                                        if (selectedTags.size > 1) selectedTags - option.tag else selectedTags
                                    } else {
                                        selectedTags + option.tag
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    selectedTags = if (checked) {
                                        selectedTags + option.tag
                                    } else {
                                        if (selectedTags.size > 1) selectedTags - option.tag else selectedTags
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = option.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedTags) },
            ) {
                Text(text = stringResource(R.string.settings_enabled_languages_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.settings_enabled_languages_cancel))
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptPreferenceSection(
    showChineseScriptOption: Boolean,
    showCantoneseScriptOption: Boolean,
    chineseScript: ChineseScript,
    cantoneseScript: ChineseScript,
    targetPhoneticGuideKind: PhoneticGuideKind?,
    showTargetRomanization: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_section_script)) {
        if (showChineseScriptOption) {
            Text(
                text = stringResource(R.string.settings_chinese_script_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ChineseScript.entries.forEach { script ->
                    FilterChip(
                        selected = chineseScript == script,
                        onClick = { onEvent(SettingsEvent.SetChineseScript(script)) },
                        label = { Text(text = scriptDisplayName(script)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }
        }

        if (showChineseScriptOption && (showCantoneseScriptOption || targetPhoneticGuideKind != null)) {
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (showCantoneseScriptOption) {
            Text(
                text = stringResource(R.string.settings_cantonese_script_label),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ChineseScript.entries.forEach { script ->
                    FilterChip(
                        selected = cantoneseScript == script,
                        onClick = { onEvent(SettingsEvent.SetCantoneseScript(script)) },
                        label = { Text(text = scriptDisplayName(script)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }
        }

        if (showCantoneseScriptOption && targetPhoneticGuideKind != null) {
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (targetPhoneticGuideKind != null) {
            val guideLabelRes = when (targetPhoneticGuideKind) {
                PhoneticGuideKind.ROMAJI -> R.string.settings_phonetic_guide_romaji
                PhoneticGuideKind.IAST -> R.string.settings_phonetic_guide_iast
                PhoneticGuideKind.PINYIN -> R.string.settings_phonetic_guide_pinyin
                PhoneticGuideKind.LATIN_TRANSLITERATION -> R.string.settings_phonetic_guide_latin
            }
            Text(
                text = stringResource(guideLabelRes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.settings_phonetic_guide_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilterChip(
                    selected = !showTargetRomanization,
                    onClick = { onEvent(SettingsEvent.SetShowTargetRomanization(false)) },
                    label = { Text(text = stringResource(R.string.settings_phonetic_guide_off)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
                FilterChip(
                    selected = showTargetRomanization,
                    onClick = { onEvent(SettingsEvent.SetShowTargetRomanization(true)) },
                    label = { Text(text = stringResource(R.string.settings_phonetic_guide_on)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun autoPauseLabel(autoPause: AutoPause): String = when (autoPause) {
    AutoPause.Off -> stringResource(R.string.settings_auto_pause_off)
    is AutoPause.Fixed -> stringResource(R.string.settings_auto_pause_fixed, autoPause.pauseMs / 1_000f)
    is AutoPause.Proportional -> stringResource(R.string.settings_auto_pause_proportional, autoPause.factor)
}

@Composable
private fun languageDisplayName(language: LanguageTag): String = when (language.value) {
    "en" -> stringResource(R.string.settings_lang_en)

    "ja" -> stringResource(R.string.settings_lang_ja)

    "ko" -> stringResource(R.string.settings_lang_ko)

    "zh" -> stringResource(R.string.settings_lang_zh)

    "yue" -> stringResource(R.string.settings_lang_yue)

    "hi" -> stringResource(R.string.settings_lang_hi)

    "vi" -> stringResource(R.string.settings_lang_vi)

    "ru" -> stringResource(R.string.settings_lang_ru)

    "es" -> stringResource(R.string.settings_lang_es)

    "de" -> stringResource(R.string.settings_lang_de)

    "el" -> stringResource(R.string.settings_lang_el)

    "id" -> stringResource(R.string.settings_lang_id)

    else -> {
        val code = language.value
        val display = Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
            .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }
        val upper = code.uppercase(Locale.ROOT)
        if (display != null) "$display ($upper)" else upper
    }
}

@Composable
private fun scriptDisplayName(script: ChineseScript): String = when (script) {
    ChineseScript.HANS -> stringResource(R.string.settings_script_hans)
    ChineseScript.HANT -> stringResource(R.string.settings_script_hant)
}

@ChunkFlowPreviews
@Composable
internal fun SettingsScreenDefaultPreview() {
    ChunkFlowTheme {
        SettingsScreen(
            state = SettingsUiState(),
            onEvent = {},
        )
    }
}
