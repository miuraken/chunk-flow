package com.miuraken.chunkflow.feature.settings

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.SegmentationParams
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
                onEvent = onEvent,
            )

            SegmentationDefaultsSection(
                params = state.defaultSegmentationParams,
                onEvent = onEvent,
            )

            LanguagePairSection(
                targetLanguage = state.defaultLanguagePair.target,
                baseLanguage = state.defaultLanguagePair.base,
                onEvent = onEvent,
            )

            ScriptPreferenceSection(
                chineseScript = state.chineseScript,
                cantoneseScript = state.cantoneseScript,
                onEvent = onEvent,
            )

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
    onEvent: (SettingsEvent) -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_section_language)) {
        Text(
            text = stringResource(R.string.settings_target_language_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsUiState.SUPPORTED_LANGUAGES.forEach { language ->
                FilterChip(
                    selected = targetLanguage == language,
                    onClick = { onEvent(SettingsEvent.SetTargetLanguage(language)) },
                    label = { Text(text = languageDisplayName(language)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.settings_base_language_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
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
            SettingsUiState.SUPPORTED_LANGUAGES.forEach { language ->
                FilterChip(
                    selected = baseLanguage == language,
                    onClick = { onEvent(SettingsEvent.SetBaseLanguage(language)) },
                    label = { Text(text = languageDisplayName(language)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScriptPreferenceSection(
    chineseScript: ChineseScript,
    cantoneseScript: ChineseScript,
    onEvent: (SettingsEvent) -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_section_script)) {
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

        Spacer(modifier = Modifier.height(8.dp))

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
    else -> language.value.uppercase()
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
