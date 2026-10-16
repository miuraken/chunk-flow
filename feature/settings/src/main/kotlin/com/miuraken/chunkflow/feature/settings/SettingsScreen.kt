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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.SegmentationParams
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 設定画面のタブ種別。
 */
private enum class SettingsTab {
    PLAYBACK,
    SEGMENTATION,
    TRANSCRIPTION,
}

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
    var selectedTabOrdinal by rememberSaveable { mutableIntStateOf(SettingsTab.PLAYBACK.ordinal) }
    val selectedTab = SettingsTab.entries.getOrElse(selectedTabOrdinal) { SettingsTab.PLAYBACK }
    val isInspectionMode = LocalInspectionMode.current

    LaunchedEffect(selectedTab, isInspectionMode) {
        if (!isInspectionMode && selectedTab == SettingsTab.TRANSCRIPTION) {
            onEvent(SettingsEvent.RefreshRecognitionModels)
        }
    }

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
                .padding(innerPadding),
        ) {
            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                Tab(
                    selected = selectedTab == SettingsTab.PLAYBACK,
                    onClick = { selectedTabOrdinal = SettingsTab.PLAYBACK.ordinal },
                    text = { Text(text = stringResource(R.string.settings_tab_playback)) },
                )
                Tab(
                    selected = selectedTab == SettingsTab.SEGMENTATION,
                    onClick = { selectedTabOrdinal = SettingsTab.SEGMENTATION.ordinal },
                    text = { Text(text = stringResource(R.string.settings_tab_segmentation)) },
                )
                Tab(
                    selected = selectedTab == SettingsTab.TRANSCRIPTION,
                    onClick = { selectedTabOrdinal = SettingsTab.TRANSCRIPTION.ordinal },
                    text = { Text(text = stringResource(R.string.settings_tab_transcription)) },
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (selectedTab) {
                    SettingsTab.PLAYBACK -> {
                        PlaybackSettingsSection(
                            seekBackMs = state.seekBackMs,
                            seekForwardMs = state.seekForwardMs,
                            defaultSpeed = state.defaultSpeed,
                            defaultAutoPause = state.defaultAutoPause,
                            defaultAutoPauseScope = state.defaultAutoPauseScope,
                            onEvent = onEvent,
                        )
                    }

                    SettingsTab.SEGMENTATION -> {
                        SegmentationDefaultsSection(
                            params = state.defaultSegmentationParams,
                            onEvent = onEvent,
                        )
                    }

                    SettingsTab.TRANSCRIPTION -> {
                        TranscriptionSettingsSection(
                            lookaheadEnabled = state.lookaheadTranscriptionEnabled,
                            transcriptionEngine = state.transcriptionEngine,
                            languageModels = state.languageModels,
                            onEvent = onEvent,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
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
    defaultAutoPauseScope: AutoPauseScope,
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

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsUiState.AUTO_PAUSE_SCOPE_OPTIONS.forEach { scopeOption ->
                val isSelected = defaultAutoPauseScope == scopeOption
                Row(
                    modifier = Modifier
                        .selectable(
                            selected = isSelected,
                            onClick = { onEvent(SettingsEvent.SetDefaultAutoPauseScope(scopeOption)) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                    )
                    Text(
                        text = autoPauseScopeLabel(scopeOption),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
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

@Composable
private fun TranscriptionSettingsSection(
    lookaheadEnabled: Boolean,
    transcriptionEngine: OnDeviceTranscriptionEngine,
    languageModels: List<OnDeviceLanguageModelInfo>,
    onEvent: (SettingsEvent) -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_section_transcription)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
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
                    text = stringResource(R.string.settings_lookahead_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.settings_lookahead_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = lookaheadEnabled,
                onCheckedChange = { enabled ->
                    onEvent(SettingsEvent.SetLookaheadTranscriptionEnabled(enabled))
                },
            )
        }
    }

    SettingsCard(title = stringResource(R.string.settings_section_transcription_engine)) {
        Text(
            text = stringResource(R.string.settings_transcription_engine_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SettingsUiState.TRANSCRIPTION_ENGINE_OPTIONS.forEach { option ->
                val isSelected = transcriptionEngine == option
                val (titleText, descText) = transcriptionEngineLabelAndDescription(option)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = isSelected,
                            onClick = { onEvent(SettingsEvent.SetTranscriptionEngine(option)) },
                            role = Role.RadioButton,
                        )
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Column(
                        modifier = Modifier.padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = descText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    SettingsCard(title = stringResource(R.string.settings_section_speech_models)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_speech_models_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
            )
            TextButton(onClick = { onEvent(SettingsEvent.RefreshRecognitionModels) }) {
                Text(text = stringResource(R.string.settings_speech_models_refresh))
            }
        }

        languageModels.forEachIndexed { index, info ->
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            SpeechModelRow(
                info = info,
                onRequestOsDownload = {
                    onEvent(SettingsEvent.RequestRecognitionModelDownload(info.language))
                },
                onRequestSherpaDownload = {
                    onEvent(SettingsEvent.RequestSherpaModelDownload(info.language))
                },
                onDeleteSherpaModel = {
                    onEvent(SettingsEvent.DeleteSherpaModel(info.language))
                },
            )
        }
    }
}

@Composable
private fun transcriptionEngineLabelAndDescription(
    engine: OnDeviceTranscriptionEngine,
): Pair<String, String> = when (engine) {
    OnDeviceTranscriptionEngine.AUTO -> Pair(
        stringResource(R.string.settings_engine_auto_title),
        stringResource(R.string.settings_engine_auto_desc),
    )

    OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER -> Pair(
        stringResource(R.string.settings_engine_android_title),
        stringResource(R.string.settings_engine_android_desc),
    )

    OnDeviceTranscriptionEngine.SHERPA_ONNX -> Pair(
        stringResource(R.string.settings_engine_sherpa_title),
        stringResource(R.string.settings_engine_sherpa_desc),
    )

    OnDeviceTranscriptionEngine.ML_KIT -> Pair(
        stringResource(R.string.settings_engine_mlkit_title),
        stringResource(R.string.settings_engine_mlkit_desc),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeechModelRow(
    info: OnDeviceLanguageModelInfo,
    onRequestOsDownload: () -> Unit,
    onRequestSherpaDownload: () -> Unit,
    onDeleteSherpaModel: () -> Unit,
) {
    val (osStatusLabel, osContainerColor, osContentColor) = modelStatusStyle(info.status)
    val (sherpaStatusLabel, sherpaContainerColor, sherpaContentColor) = modelStatusStyle(info.sherpaModelStatus)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = speechModelLanguageDisplayName(info.language),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                info.resolvedBcp47Tag?.let { bcp47Tag ->
                    Text(
                        text = stringResource(R.string.settings_speech_model_locale_tag, bcp47Tag),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (info.status == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
                    info.status == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                ) {
                    val actionLabel = if (info.status == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED) {
                        stringResource(R.string.settings_speech_model_action_retry)
                    } else {
                        stringResource(R.string.settings_speech_model_action_download)
                    }
                    OutlinedButton(onClick = onRequestOsDownload) {
                        Text(
                            text = actionLabel,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }

                if (info.sherpaModelStatus == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
                    info.sherpaModelStatus == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                ) {
                    val sherpaActionLabel = if (info.sherpaModelStatus == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED) {
                        stringResource(R.string.settings_speech_model_action_sherpa_retry)
                    } else {
                        stringResource(R.string.settings_speech_model_action_sherpa_download)
                    }
                    OutlinedButton(onClick = onRequestSherpaDownload) {
                        Text(
                            text = sherpaActionLabel,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else if (info.canDeleteSherpaModel &&
                    info.sherpaModelStatus == OnDeviceLanguageModelStatus.INSTALLED
                ) {
                    TextButton(onClick = onDeleteSherpaModel) {
                        Text(
                            text = stringResource(R.string.settings_speech_model_action_sherpa_delete),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Surface(
                color = osContainerColor,
                contentColor = osContentColor,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_speech_model_os_prefix, osStatusLabel),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            if (info.sherpaModelStatus != OnDeviceLanguageModelStatus.UNSUPPORTED) {
                val sizeMb = (info.sherpaModelSizeBytes / (1024L * 1024L)).toInt()
                val sherpaBadgeText = if (sizeMb > 0) {
                    stringResource(R.string.settings_speech_model_sherpa_size_mb, sizeMb, sherpaStatusLabel)
                } else {
                    stringResource(R.string.settings_speech_model_sherpa_prefix, sherpaStatusLabel)
                }
                Surface(
                    color = sherpaContainerColor,
                    contentColor = sherpaContentColor,
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(
                        text = sherpaBadgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun modelStatusStyle(
    status: OnDeviceLanguageModelStatus,
): Triple<String, androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> = when (status) {
    OnDeviceLanguageModelStatus.CHECKING -> Triple(
        stringResource(R.string.settings_speech_model_status_checking),
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OnDeviceLanguageModelStatus.INSTALLED -> Triple(
        stringResource(R.string.settings_speech_model_status_installed),
        MaterialTheme.colorScheme.primaryContainer,
        MaterialTheme.colorScheme.onPrimaryContainer,
    )

    OnDeviceLanguageModelStatus.NOT_INSTALLED -> Triple(
        stringResource(R.string.settings_speech_model_status_not_installed),
        MaterialTheme.colorScheme.secondaryContainer,
        MaterialTheme.colorScheme.onSecondaryContainer,
    )

    OnDeviceLanguageModelStatus.DOWNLOADING -> Triple(
        stringResource(R.string.settings_speech_model_status_downloading),
        MaterialTheme.colorScheme.tertiaryContainer,
        MaterialTheme.colorScheme.onTertiaryContainer,
    )

    OnDeviceLanguageModelStatus.DOWNLOAD_FAILED -> Triple(
        stringResource(R.string.settings_speech_model_status_download_failed),
        MaterialTheme.colorScheme.errorContainer,
        MaterialTheme.colorScheme.onErrorContainer,
    )

    OnDeviceLanguageModelStatus.UNSUPPORTED -> Triple(
        stringResource(R.string.settings_speech_model_status_unsupported),
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun speechModelLanguageDisplayName(language: LanguageTag): String = when (language.value.lowercase(Locale.ROOT)) {
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
    else -> language.value.uppercase(Locale.ROOT)
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
private fun autoPauseScopeLabel(scope: AutoPauseScope): String = when (scope) {
    AutoPauseScope.ALL_CHUNKS -> stringResource(R.string.settings_auto_pause_scope_all)
    AutoPauseScope.AFTER_TARGET_LANGUAGE -> stringResource(R.string.settings_auto_pause_scope_target)
    AutoPauseScope.AFTER_BASE_LANGUAGE -> stringResource(R.string.settings_auto_pause_scope_base)
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
