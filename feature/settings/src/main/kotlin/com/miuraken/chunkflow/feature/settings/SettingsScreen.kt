package com.miuraken.chunkflow.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.designsystem.component.LanguageSettingsListContent
import com.miuraken.chunkflow.core.designsystem.component.languageSettingDisplayName
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageSettings
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.SttEngineSelection
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 設定画面のタブ種別。
 */
enum class SettingsTab {
    PLAYBACK,
    SEGMENTATION,
    TRANSCRIPTION,
    LANGUAGE,
}

/**
 * 設定画面の Route（F9.1、F3.2、F3.3、F5.2、F6.3）。
 */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: Int = SettingsTab.PLAYBACK.ordinal,
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
        initialTab = initialTab,
    )
}

@Composable
private fun sttEngineDisplayName(engine: SttEngine): String = when (engine) {
    SttEngine.ML_KIT -> stringResource(R.string.settings_engine_ml_kit)
    SttEngine.SPEECH_RECOGNIZER -> stringResource(R.string.settings_stt_engine_speech_recognizer)
    SttEngine.SHERPA_ONNX -> stringResource(R.string.settings_stt_engine_sherpa_onnx)
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
    initialTab: Int = SettingsTab.PLAYBACK.ordinal,
    expandFirstAccordion: Boolean = false,
    initialExpandedLanguages: Set<LanguageTag> = emptySet(),
) {
    var selectedTabOrdinal by rememberSaveable { mutableIntStateOf(initialTab) }
    val selectedTab = SettingsTab.entries.getOrElse(selectedTabOrdinal) { SettingsTab.PLAYBACK }
    val isInspectionMode = LocalInspectionMode.current

    LaunchedEffect(selectedTab, isInspectionMode) {
        if (!isInspectionMode && selectedTab == SettingsTab.TRANSCRIPTION) {
            onEvent(SettingsEvent.RefreshRecognitionModels)
        }
    }

    state.sherpaDownloadDialog?.let { dialogState ->
        SherpaDownloadModalDialog(
            dialogState = dialogState,
            onConfirm = { onEvent(SettingsEvent.ConfirmSherpaModelDownload) },
            onDismiss = { onEvent(SettingsEvent.DismissSherpaModelDownloadDialog) },
        )
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
            PrimaryScrollableTabRow(selectedTabIndex = selectedTab.ordinal, edgePadding = 0.dp) {
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
                Tab(
                    selected = selectedTab == SettingsTab.LANGUAGE,
                    onClick = { selectedTabOrdinal = SettingsTab.LANGUAGE.ordinal },
                    text = { Text(text = stringResource(R.string.settings_tab_language)) },
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
                            skipOriginalSilence = state.skipOriginalSilence,
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
                            autoStartTranscriptionOnOpen = state.autoStartTranscriptionOnOpen,
                            backgroundTranscriptionEnabled = state.backgroundTranscriptionEnabled,
                            languageModels = state.languageModels,
                            enabledLanguages = state.enabledLanguages,
                            sttEngineByLanguage = state.sttEngineByLanguage,
                            onEvent = onEvent,
                            expandFirstAccordion = expandFirstAccordion,
                            initialExpandedLanguages = initialExpandedLanguages,
                        )
                    }

                    SettingsTab.LANGUAGE -> {
                        LanguageSettingsSection(
                            enabledLanguages = state.enabledLanguages,
                            onEvent = onEvent,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackSettingsSection(
    seekBackMs: Long,
    seekForwardMs: Long,
    defaultSpeed: Float,
    defaultAutoPause: AutoPause,
    defaultAutoPauseScope: AutoPauseScope,
    skipOriginalSilence: Boolean,
    onEvent: (SettingsEvent) -> Unit,
) {
    var fixedSeconds by rememberSaveable {
        mutableFloatStateOf(
            (defaultAutoPause as? AutoPause.Fixed)?.let {
                (it.pauseMs / 1_000f).coerceIn(0f, 4.0f)
            } ?: (SettingsUiState.DEFAULT_AUTO_PAUSE_FIXED_MS / 1_000f),
        )
    }
    var proportionalFactor by rememberSaveable {
        mutableFloatStateOf(
            (defaultAutoPause as? AutoPause.Proportional)?.factor?.coerceIn(
                SettingsUiState.MIN_AUTO_PAUSE_PROPORTIONAL_FACTOR,
                SettingsUiState.MAX_AUTO_PAUSE_PROPORTIONAL_FACTOR,
            ) ?: SettingsUiState.DEFAULT_AUTO_PAUSE_PROPORTIONAL_FACTOR,
        )
    }

    LaunchedEffect(defaultAutoPause) {
        when (defaultAutoPause) {
            is AutoPause.Fixed -> {
                fixedSeconds = (defaultAutoPause.pauseMs / 1_000f).coerceIn(0f, 4.0f)
            }

            is AutoPause.Proportional -> {
                proportionalFactor = defaultAutoPause.factor.coerceIn(
                    SettingsUiState.MIN_AUTO_PAUSE_PROPORTIONAL_FACTOR,
                    SettingsUiState.MAX_AUTO_PAUSE_PROPORTIONAL_FACTOR,
                )
            }

            AutoPause.Off -> Unit
        }
    }

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

        Spacer(modifier = Modifier.height(4.dp))

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

        Spacer(modifier = Modifier.height(4.dp))

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
                shape = MaterialTheme.shapes.small,
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

        SettingsSlider(
            value = defaultSpeed,
            onValueChange = { onEvent(SettingsEvent.SetDefaultSpeed(it)) },
            valueRange = 0.5f..2.0f,
            steps = 29,
            modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.settings_section_skip_silence),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = skipOriginalSilence,
                    onValueChange = { onEvent(SettingsEvent.SetSkipOriginalSilence(it)) },
                    role = Role.Checkbox,
                )
                .padding(vertical = 2.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = skipOriginalSilence,
                onCheckedChange = null,
            )
            Text(
                text = stringResource(R.string.settings_skip_original_silence_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.settings_default_auto_pause_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            SettingsUiState.AUTO_PAUSE_SCOPE_OPTIONS.forEachIndexed { index, scopeOption ->
                val isSelected = defaultAutoPauseScope == scopeOption
                SegmentedButton(
                    selected = isSelected,
                    onClick = { onEvent(SettingsEvent.SetDefaultAutoPauseScope(scopeOption)) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = SettingsUiState.AUTO_PAUSE_SCOPE_OPTIONS.size,
                    ),
                    icon = {},
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                    label = {
                        Text(
                            text = autoPauseScopeLabel(scopeOption),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                        )
                    },
                )
            }
        }

        val isOffSelected = defaultAutoPause is AutoPause.Off
        val isFixedSelected = defaultAutoPause is AutoPause.Fixed
        val isProportionalSelected = defaultAutoPause is AutoPause.Proportional
        val displayFixedSeconds = if (defaultAutoPause is AutoPause.Fixed) {
            (defaultAutoPause.pauseMs / 1_000f).coerceIn(0f, 4.0f)
        } else {
            fixedSeconds
        }
        val displayProportionalFactor = if (defaultAutoPause is AutoPause.Proportional) {
            defaultAutoPause.factor.coerceIn(
                SettingsUiState.MIN_AUTO_PAUSE_PROPORTIONAL_FACTOR,
                SettingsUiState.MAX_AUTO_PAUSE_PROPORTIONAL_FACTOR,
            )
        } else {
            proportionalFactor
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = isOffSelected,
                        onClick = { onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.Off)) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 2.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = isOffSelected,
                    onClick = null,
                )
                Text(
                    text = stringResource(R.string.settings_auto_pause_off),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = isFixedSelected,
                        onClick = {
                            val pauseMs = ((displayFixedSeconds * 2f).roundToInt() * 500L)
                                .coerceIn(
                                    SettingsUiState.MIN_AUTO_PAUSE_FIXED_MS,
                                    SettingsUiState.MAX_AUTO_PAUSE_FIXED_MS,
                                )
                            onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.Fixed(pauseMs)))
                        },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 2.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = isFixedSelected,
                    onClick = null,
                )
                Text(
                    text = stringResource(R.string.settings_auto_pause_fixed, displayFixedSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            SettingsSlider(
                value = displayFixedSeconds,
                onValueChange = { rawValue ->
                    val snappedSteps = (rawValue * 2f).roundToInt().coerceIn(0, 8)
                    val snappedSeconds = snappedSteps * 0.5f
                    fixedSeconds = snappedSeconds
                    onEvent(
                        SettingsEvent.SetDefaultAutoPause(
                            AutoPause.Fixed(pauseMs = snappedSteps * 500L),
                        ),
                    )
                },
                valueRange = 0f..4.0f,
                steps = 7,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(start = 32.dp, end = 8.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = isProportionalSelected,
                        onClick = {
                            val snappedFactor = ((displayProportionalFactor * 2f).roundToInt() * 0.5f)
                                .coerceIn(
                                    SettingsUiState.MIN_AUTO_PAUSE_PROPORTIONAL_FACTOR,
                                    SettingsUiState.MAX_AUTO_PAUSE_PROPORTIONAL_FACTOR,
                                )
                            onEvent(
                                SettingsEvent.SetDefaultAutoPause(
                                    AutoPause.Proportional(factor = snappedFactor),
                                ),
                            )
                        },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 2.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = isProportionalSelected,
                    onClick = null,
                )
                Text(
                    text = stringResource(
                        R.string.settings_auto_pause_proportional,
                        displayProportionalFactor,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            SettingsSlider(
                value = displayProportionalFactor,
                onValueChange = { rawValue ->
                    val snappedSteps = (rawValue * 2f).roundToInt().coerceIn(1, 6)
                    val snappedFactor = snappedSteps * 0.5f
                    proportionalFactor = snappedFactor
                    onEvent(
                        SettingsEvent.SetDefaultAutoPause(
                            AutoPause.Proportional(factor = snappedFactor),
                        ),
                    )
                },
                valueRange = 0.5f..3.0f,
                steps = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(start = 32.dp, end = 8.dp),
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
        SettingsSlider(
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
        SettingsSlider(
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
        SettingsSlider(
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
    autoStartTranscriptionOnOpen: Boolean,
    backgroundTranscriptionEnabled: Boolean,
    languageModels: List<OnDeviceLanguageModelInfo>,
    enabledLanguages: Set<LanguageTag>,
    sttEngineByLanguage: Map<LanguageTag, SttEngine>,
    onEvent: (SettingsEvent) -> Unit,
    expandFirstAccordion: Boolean = false,
    initialExpandedLanguages: Set<LanguageTag> = emptySet(),
) {
    SettingsCard(title = stringResource(R.string.settings_section_transcription)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = autoStartTranscriptionOnOpen,
                    onValueChange = { enabled ->
                        onEvent(SettingsEvent.SetAutoStartTranscriptionOnOpen(enabled))
                    },
                    role = Role.Checkbox,
                )
                .padding(vertical = 2.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = autoStartTranscriptionOnOpen,
                onCheckedChange = null,
            )
            Text(
                text = stringResource(R.string.settings_auto_start_transcription_on_open_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = backgroundTranscriptionEnabled,
                    onValueChange = { enabled ->
                        onEvent(SettingsEvent.SetBackgroundTranscriptionEnabled(enabled))
                    },
                    role = Role.Checkbox,
                )
                .padding(vertical = 2.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = backgroundTranscriptionEnabled,
                onCheckedChange = null,
            )
            Text(
                text = stringResource(R.string.settings_background_transcription_enabled_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    SettingsCard(title = stringResource(R.string.settings_section_speech_models)) {
        val displayEntries = LanguageSettings.SELECTABLE_LANGUAGES.filter {
            enabledLanguages.contains(LanguageTag(it.code))
        }

        displayEntries.forEachIndexed { index, entry ->
            val langTag = LanguageTag(entry.code)
            val mgmtCode = com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(entry.code)
            val rawInfo = languageModels.firstOrNull {
                it.language.value == entry.code ||
                    com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(it.language.value) == mgmtCode
            } ?: OnDeviceLanguageModelInfo(language = langTag)
            val info = rawInfo.copy(language = langTag).enrichedWithCatalog(langTag)
            val preferredEngine = sttEngineByLanguage[LanguageSettings.normalize(langTag)]

            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
            SttEngineAccordion(
                info = info,
                preferredEngine = preferredEngine,
                onPreferredEngineChange = { engine ->
                    onEvent(SettingsEvent.SetSttEngineForLanguage(langTag, engine))
                },
                onDeleteSherpaModel = {
                    onEvent(SettingsEvent.DeleteSherpaModel(rawInfo.language))
                },
                initialExpanded = (expandFirstAccordion && index == 0) ||
                    initialExpandedLanguages.contains(langTag),
            )
        }
    }
}

@Composable
private fun speechModelLanguageDisplayName(language: LanguageTag): String {
    val code = language.value.lowercase(Locale.ROOT)
    return when (code) {
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
            val currentLocale = androidx.compose.ui.text.intl.Locale.current.platformLocale
            languageSettingDisplayName(language, currentLocale)
        }
    }
}

/**
 * 設定画面用のスライダー。
 *
 * material3 1.5 で `Slider(value, onValueChange, …)` が非推奨になったため、
 * [rememberSliderState] + `Slider(state = …)` の新しいオーバーロードで描く。
 * 値はこの Composable の外（UiState）が持つので、外から [value] が変わったとき
 * （プリセットのチップ、リセットなど）に `state.value` を追従させる。
 *
 * @param value 現在の値。
 * @param onValueChange ドラッグ・タップで値が変わるたびに呼ぶ。
 * @param valueRange 値の範囲。
 * @param steps 範囲の内側に置く目盛りの数（`Slider` と同じ意味）。
 */
@Composable
private fun SettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
) {
    val state: SliderState = rememberSliderState(
        value = value,
        steps = steps,
        trackRange = valueRange,
    )
    LaunchedEffect(state, value) {
        if (state.value != value) {
            state.value = value
        }
    }
    Slider(
        state = state,
        onValueChange = { newValue ->
            state.value = newValue
            onValueChange(newValue)
        },
        modifier = modifier,
    )
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
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

@Composable
private fun LanguageSettingsSection(
    enabledLanguages: Set<LanguageTag>,
    onEvent: (SettingsEvent) -> Unit,
) {
    SettingsCard(title = stringResource(R.string.settings_tab_language)) {
        LanguageSettingsListContent(
            enabledLanguages = enabledLanguages,
            onToggleLanguage = { language ->
                onEvent(SettingsEvent.ToggleEnabledLanguage(language))
            },
        )
    }
}

@Composable
private fun SttEngineAccordion(
    info: OnDeviceLanguageModelInfo,
    preferredEngine: SttEngine?,
    onPreferredEngineChange: (SttEngine?) -> Unit,
    onDeleteSherpaModel: () -> Unit,
    initialExpanded: Boolean = false,
) {
    val expanded = rememberSaveable { mutableStateOf(initialExpanded) }
    val enrichedInfo = info.enrichedWithCatalog()

    val autoResolvedEngine = SttEngineSelection.resolveAutoEngine(enrichedInfo)
    val autoLabel = stringResource(
        R.string.settings_stt_engine_auto_with_resolved,
        sttEngineDisplayName(autoResolvedEngine),
    )
    val effectivePreferred = preferredEngine?.takeIf { isSttEngineSelectableInSettings(enrichedInfo, it) }
    val currentEngineLabel = when (effectivePreferred) {
        null -> autoLabel
        else -> sttEngineDisplayName(effectivePreferred)
    }
    val sizeMb = SherpaDownloadDialogState.resolveSizeMb(enrichedInfo.sherpaModelSizeBytes)
    val isSherpaInstalled = enrichedInfo.sherpaModelStatus == OnDeviceLanguageModelStatus.INSTALLED
    val sherpaLabel = if (isSherpaInstalled) {
        stringResource(R.string.settings_stt_engine_sherpa_downloaded, sizeMb)
    } else {
        stringResource(R.string.settings_stt_engine_sherpa_not_downloaded, sizeMb)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
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
                    text = speechModelLanguageDisplayName(enrichedInfo.language),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.settings_stt_engine_current, currentEngineLabel),
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
                    text = stringResource(R.string.settings_stt_engine_priority),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                val options: List<Pair<SttEngine?, String>> = listOf(
                    null to autoLabel,
                    SttEngine.ML_KIT to stringResource(R.string.settings_stt_engine_mlkit_radio),
                    SttEngine.SPEECH_RECOGNIZER to stringResource(R.string.settings_stt_engine_speech_recognizer),
                    SttEngine.SHERPA_ONNX to sherpaLabel,
                )

                options.forEach { (engine, label) ->
                    val isOptionEnabled = isSttEngineSelectableInSettings(enrichedInfo, engine)
                    val isSelected = effectivePreferred == engine
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
                            modifier = Modifier.padding(start = 8.dp),
                        )
                        if (engine == SttEngine.SHERPA_ONNX && isSherpaInstalled) {
                            IconButton(
                                onClick = onDeleteSherpaModel,
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(32.dp),
                            ) {
                                Icon(
                                    imageVector = ChunkFlowIcons.Delete,
                                    contentDescription = stringResource(
                                        R.string.settings_speech_model_action_sherpa_delete,
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

@Composable
private fun SherpaDownloadModalDialog(
    dialogState: SherpaDownloadDialogState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {
            if (!dialogState.isDownloading) {
                onDismiss()
            }
        },
        title = {
            Text(text = stringResource(R.string.settings_sherpa_download_dialog_title))
        },
        text = {
            if (dialogState.isDownloading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(vertical = 8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    Text(
                        text = stringResource(
                            R.string.settings_sherpa_downloading_message,
                            dialogState.sizeMb,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                val messageRes = if (dialogState.isFailed) {
                    R.string.settings_sherpa_download_failed_message
                } else {
                    R.string.settings_sherpa_download_confirm_message
                }
                Text(
                    text = stringResource(messageRes, dialogState.sizeMb),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            if (!dialogState.isDownloading) {
                TextButton(onClick = onConfirm) {
                    Text(text = stringResource(R.string.settings_sherpa_download_confirm_button))
                }
            }
        },
        dismissButton = {
            if (!dialogState.isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.settings_sherpa_download_cancel_button))
                }
            }
        },
    )
}

@ChunkFlowPreviews
@Composable
internal fun SettingsScreenTranscriptionTabPreview() {
    ChunkFlowTheme {
        SettingsScreen(
            state = SettingsUiState(
                settings = com.miuraken.chunkflow.core.model.UserSettings(
                    enabledLanguages = setOf(
                        LanguageTag("en"),
                        LanguageTag("ja"),
                        LanguageTag("ko"),
                    ),
                    sttEngineByLanguage = mapOf(
                        LanguageTag("en") to SttEngine.ML_KIT,
                        LanguageTag("ko") to SttEngine.SHERPA_ONNX,
                    ),
                ),
                languageModels = listOf(
                    OnDeviceLanguageModelInfo(
                        language = LanguageTag("en"),
                        status = OnDeviceLanguageModelStatus.INSTALLED,
                        sherpaModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                        mlKitStatus = OnDeviceLanguageModelStatus.INSTALLED,
                        sherpaModelSizeBytes = 98L * 1024L * 1024L,
                    ),
                    OnDeviceLanguageModelInfo(
                        language = LanguageTag("ja"),
                        status = OnDeviceLanguageModelStatus.INSTALLED,
                        sherpaModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                        mlKitStatus = OnDeviceLanguageModelStatus.INSTALLED,
                        mlKitMode = com.miuraken.chunkflow.core.model.MlKitRecognitionMode.ADVANCED,
                        sherpaModelSizeBytes = 98L * 1024L * 1024L,
                    ),
                    OnDeviceLanguageModelInfo(
                        language = LanguageTag("ko"),
                        status = OnDeviceLanguageModelStatus.INSTALLED,
                        sherpaModelStatus = OnDeviceLanguageModelStatus.INSTALLED,
                        mlKitStatus = OnDeviceLanguageModelStatus.INSTALLED,
                        sherpaModelSizeBytes = 98L * 1024L * 1024L,
                        canDeleteSherpaModel = true,
                    ),
                ),
            ),
            onEvent = {},
            initialTab = SettingsTab.TRANSCRIPTION.ordinal,
            initialExpandedLanguages = setOf(LanguageTag("ja"), LanguageTag("ko")),
        )
    }
}

@ChunkFlowPreviews
@Composable
internal fun SettingsScreenLanguageTabPreview() {
    ChunkFlowTheme {
        SettingsScreen(
            state = SettingsUiState(),
            onEvent = {},
            initialTab = SettingsTab.LANGUAGE.ordinal,
        )
    }
}
