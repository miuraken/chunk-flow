package com.miuraken.chunkflow.feature.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.designsystem.component.LanguageSettingsListContent
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowLoadingIndicator
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowToggleButtonGroup
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowToggleButtonItem
import com.miuraken.chunkflow.core.designsystem.component.languageSettingDisplayName
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.formatSpeed
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.DownloadableSttModel
import com.miuraken.chunkflow.core.model.DownloadableSttModelState
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageSettings
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.SttEngineLabel
import com.miuraken.chunkflow.core.model.SttEngineSelection
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 設定画面のタブ種別。
 */
enum class SettingsTab(
    /** タブのラベル（固定幅タブに収まる短い名前）。 */
    @StringRes val labelRes: Int,
) {
    PLAYBACK(R.string.settings_tab_playback),
    SEGMENTATION(R.string.settings_tab_segmentation),
    TRANSCRIPTION(R.string.settings_tab_transcription),
    LANGUAGE(R.string.settings_tab_language),
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
    SttEngine.SHERPA_ONNX_SENSE_VOICE -> stringResource(R.string.settings_stt_engine_sherpa_sense_voice)
    SttEngine.SHERPA_ONNX_WHISPER_BASE -> stringResource(R.string.settings_stt_engine_sherpa_whisper_base)
    SttEngine.SHERPA_ONNX_WHISPER_SMALL -> stringResource(R.string.settings_stt_engine_sherpa_whisper_small)
    SttEngine.MOONSHINE_TINY -> stringResource(R.string.settings_stt_engine_moonshine_tiny)
    SttEngine.MOONSHINE_BASE -> stringResource(R.string.settings_stt_engine_moonshine_base)
    SttEngine.OPENAI_API -> stringResource(R.string.settings_stt_engine_openai_api)
    SttEngine.GROQ_API -> stringResource(R.string.settings_stt_engine_groq_api)
    SttEngine.GEMINI_API -> stringResource(R.string.settings_stt_engine_gemini_api)
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

    // 本文がスクロールしても TopAppBar は固定し、スクロール中は色だけを変える（計画書 §4 Phase B）。
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

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
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 4 タブを固定幅で並べ、360dp 幅でも全タブが見えるようにする（調査 §3.2 C11）。
            PrimaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                SettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTabOrdinal = tab.ordinal },
                        text = {
                            Text(
                                text = stringResource(tab.labelRes),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
            ) {
                when (selectedTab) {
                    SettingsTab.PLAYBACK -> {
                        PlaybackSettingsSection(
                            seekBackMs = state.seekBackMs,
                            seekForwardMs = state.seekForwardMs,
                            defaultSpeed = state.defaultSpeed,
                            baseLanguageSpeedEnabled = state.baseLanguageSpeedEnabled,
                            baseLanguageSpeed = state.baseLanguageSpeed,
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
                            downloadableModels = state.downloadableModels,
                            openAiApiKey = state.openAiApiKey,
                            groqApiKey = state.groqApiKey,
                            geminiApiKey = state.geminiApiKey,
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

/** 速度のスライダーの段数（0.05 刻み、[ItemState.MIN_SPEED]〜[ItemState.MAX_SPEED]）。 */
private fun speedSliderSteps(): Int = ((ItemState.MAX_SPEED - ItemState.MIN_SPEED) / SPEED_SLIDER_STEP).roundToInt() - 1

/** 速度のスライダーの刻み。 */
private const val SPEED_SLIDER_STEP: Float = 0.05f

/** 速度のプリセットを 1 段に並べる数（スマートフォンの幅で ⋯ に畳まれない数）。 */
private const val SPEED_PRESETS_PER_ROW: Int = 4

@Composable
private fun PlaybackSettingsSection(
    seekBackMs: Long,
    seekForwardMs: Long,
    defaultSpeed: Float,
    baseLanguageSpeedEnabled: Boolean,
    baseLanguageSpeed: Float,
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
    var originalSilenceFactor by rememberSaveable {
        mutableFloatStateOf(
            (defaultAutoPause as? AutoPause.OriginalSilence)?.factor?.coerceIn(
                SettingsUiState.MIN_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
                SettingsUiState.MAX_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
            ) ?: SettingsUiState.DEFAULT_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
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

            is AutoPause.OriginalSilence -> {
                originalSilenceFactor = defaultAutoPause.factor.coerceIn(
                    SettingsUiState.MIN_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
                    SettingsUiState.MAX_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
                )
            }

            AutoPause.Off -> Unit
        }
    }

    SettingsSection(title = stringResource(R.string.settings_section_playback)) {
        SettingsItemHeader(
            label = stringResource(R.string.settings_seek_back_label),
            value = stringResource(R.string.settings_seconds_format, (seekBackMs / 1_000L).toInt()),
        )
        ChunkFlowToggleButtonGroup(
            items = SettingsUiState.SEEK_BACK_OPTIONS_MS.map { optionMs ->
                val sec = (optionMs / 1_000L).toInt()
                ChunkFlowToggleButtonItem(
                    label = stringResource(R.string.settings_seconds_format, sec),
                    checked = seekBackMs == optionMs,
                    onCheckedChange = { checked ->
                        if (checked) onEvent(SettingsEvent.SetSeekBackMs(optionMs))
                    },
                )
            },
            compact = true,
        )

        Spacer(modifier = Modifier.height(4.dp))

        SettingsItemHeader(
            label = stringResource(R.string.settings_seek_forward_label),
            value = stringResource(R.string.settings_seconds_format, (seekForwardMs / 1_000L).toInt()),
        )
        ChunkFlowToggleButtonGroup(
            items = SettingsUiState.SEEK_FORWARD_OPTIONS_MS.map { optionMs ->
                val sec = (optionMs / 1_000L).toInt()
                ChunkFlowToggleButtonItem(
                    label = stringResource(R.string.settings_seconds_format, sec),
                    checked = seekForwardMs == optionMs,
                    onCheckedChange = { checked ->
                        if (checked) onEvent(SettingsEvent.SetSeekForwardMs(optionMs))
                    },
                )
            },
            compact = true,
        )

        Spacer(modifier = Modifier.height(4.dp))

        val baseLanguageSpeedLabel = stringResource(R.string.settings_base_language_speed_label)
        SettingsCheckboxHeader(
            label = baseLanguageSpeedLabel,
            checked = baseLanguageSpeedEnabled,
            onCheckedChange = { onEvent(SettingsEvent.SetBaseLanguageSpeedEnabled(it)) },
            value = formatSpeed(baseLanguageSpeed),
        )

        SettingsSlider(
            value = baseLanguageSpeed,
            onValueChange = { onEvent(SettingsEvent.SetBaseLanguageSpeed(it)) },
            valueRange = ItemState.MIN_SPEED..ItemState.MAX_SPEED,
            // 0.05 刻み（0.5〜3.0 で 49 段）。チェックボックスがオフのあいだは操作できない
            steps = speedSliderSteps(),
            enabled = baseLanguageSpeedEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = baseLanguageSpeedLabel
                    stateDescription = formatSpeed(baseLanguageSpeed)
                },
        )

        Spacer(modifier = Modifier.height(4.dp))

        val defaultSpeedLabel = stringResource(R.string.settings_default_speed_label)
        SettingsItemHeader(
            label = defaultSpeedLabel,
            value = formatSpeed(defaultSpeed),
        )

        SettingsSlider(
            value = defaultSpeed,
            onValueChange = { onEvent(SettingsEvent.SetDefaultSpeed(it)) },
            valueRange = ItemState.MIN_SPEED..ItemState.MAX_SPEED,
            // 0.05 刻み（0.5〜3.0 で 49 段）
            steps = speedSliderSteps(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = defaultSpeedLabel
                    stateDescription = formatSpeed(defaultSpeed)
                },
        )

        // 7 つを 1 段に並べるとスマートフォンの幅では後ろ（x2.5・x3）が ⋯ に畳まれるため、4 つずつの段に分ける
        SettingsUiState.SPEED_PRESETS.chunked(SPEED_PRESETS_PER_ROW).forEach { rowPresets ->
            ChunkFlowToggleButtonGroup(
                items = rowPresets.map { preset ->
                    ChunkFlowToggleButtonItem(
                        label = formatSpeed(preset),
                        checked = abs(defaultSpeed - preset) < 0.01f,
                        onCheckedChange = { checked ->
                            if (checked) onEvent(SettingsEvent.SetDefaultSpeed(preset))
                        },
                    )
                },
                compact = true,
            )
        }
    }

    SettingsSectionDivider()

    SettingsSwitchItem(
        headline = stringResource(R.string.settings_section_skip_silence),
        checked = skipOriginalSilence,
        onCheckedChange = { onEvent(SettingsEvent.SetSkipOriginalSilence(it)) },
        supporting = stringResource(R.string.settings_skip_original_silence_label),
    )

    SettingsSectionDivider()

    val isOffSelected = defaultAutoPause is AutoPause.Off
    val isFixedSelected = defaultAutoPause is AutoPause.Fixed
    val isProportionalSelected = defaultAutoPause is AutoPause.Proportional
    val isOriginalSilenceSelected = defaultAutoPause is AutoPause.OriginalSilence
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
    val displayOriginalSilenceFactor = if (defaultAutoPause is AutoPause.OriginalSilence) {
        defaultAutoPause.factor.coerceIn(
            SettingsUiState.MIN_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
            SettingsUiState.MAX_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
        )
    } else {
        originalSilenceFactor
    }

    SettingsSection(
        title = stringResource(R.string.settings_default_auto_pause_label),
        contentPadding = PaddingValues(0.dp),
    ) {
        SettingsItemHeader(
            label = stringResource(R.string.settings_auto_pause_scope_label),
            value = autoPauseScopeLabel(defaultAutoPauseScope),
            modifier = Modifier.padding(horizontal = SettingsSectionPadding),
        )
        ChunkFlowToggleButtonGroup(
            items = SettingsUiState.AUTO_PAUSE_SCOPE_OPTIONS.map { scopeOption ->
                ChunkFlowToggleButtonItem(
                    label = autoPauseScopeLabel(scopeOption),
                    checked = defaultAutoPauseScope == scopeOption,
                    onCheckedChange = { checked ->
                        if (checked) onEvent(SettingsEvent.SetDefaultAutoPauseScope(scopeOption))
                    },
                )
            },
            modifier = Modifier.padding(horizontal = SettingsSectionPadding),
            compact = true,
        )

        Spacer(modifier = Modifier.height(4.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
        ) {
            SettingsRadioItem(
                headline = stringResource(R.string.settings_auto_pause_off),
                selected = isOffSelected,
                onClick = { onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.Off)) },
            )

            SettingsRadioItem(
                headline = stringResource(R.string.settings_auto_pause_fixed, displayFixedSeconds),
                selected = isFixedSelected,
                onClick = {
                    val pauseMs = ((displayFixedSeconds * 2f).roundToInt() * 500L)
                        .coerceIn(
                            SettingsUiState.MIN_AUTO_PAUSE_FIXED_MS,
                            SettingsUiState.MAX_AUTO_PAUSE_FIXED_MS,
                        )
                    onEvent(SettingsEvent.SetDefaultAutoPause(AutoPause.Fixed(pauseMs)))
                },
            )

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
                    .padding(start = SettingsRadioContentStart, end = SettingsSectionPadding),
            )

            SettingsRadioItem(
                headline = stringResource(
                    R.string.settings_auto_pause_proportional,
                    displayProportionalFactor,
                ),
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
            )

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
                    .padding(start = SettingsRadioContentStart, end = SettingsSectionPadding),
            )

            SettingsRadioItem(
                headline = stringResource(
                    R.string.settings_auto_pause_original_silence,
                    formatOriginalSilenceFactor(displayOriginalSilenceFactor),
                ),
                selected = isOriginalSilenceSelected,
                onClick = {
                    val snappedFactor = ((displayOriginalSilenceFactor * 4f).roundToInt().coerceIn(1, 8) * 0.25f)
                        .coerceIn(
                            SettingsUiState.MIN_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
                            SettingsUiState.MAX_AUTO_PAUSE_ORIGINAL_SILENCE_FACTOR,
                        )
                    onEvent(
                        SettingsEvent.SetDefaultAutoPause(
                            AutoPause.OriginalSilence(factor = snappedFactor),
                        ),
                    )
                },
            )

            SettingsSlider(
                value = displayOriginalSilenceFactor,
                onValueChange = { rawValue ->
                    val snappedFactor = (rawValue * 4f).roundToInt().coerceIn(1, 8) * 0.25f
                    originalSilenceFactor = snappedFactor
                    onEvent(
                        SettingsEvent.SetDefaultAutoPause(
                            AutoPause.OriginalSilence(factor = snappedFactor),
                        ),
                    )
                },
                valueRange = 0.25f..2.0f,
                steps = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .padding(start = SettingsRadioContentStart, end = SettingsSectionPadding),
            )
        }
    }
}

private fun formatOriginalSilenceFactor(factor: Float): String {
    val hundredths = (factor * 100f).roundToInt()
    return if (hundredths % 10 == 0) {
        String.format(Locale.ROOT, "%.1f", factor)
    } else {
        String.format(Locale.ROOT, "%.2f", factor)
    }
}

@Composable
private fun SegmentationDefaultsSection(
    params: SegmentationParams,
    onEvent: (SettingsEvent) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_segmentation)) {
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
    downloadableModels: List<DownloadableSttModelState>,
    openAiApiKey: String,
    groqApiKey: String,
    geminiApiKey: String,
    enabledLanguages: Set<LanguageTag>,
    sttEngineByLanguage: Map<LanguageTag, SttEngine>,
    onEvent: (SettingsEvent) -> Unit,
    expandFirstAccordion: Boolean = false,
    initialExpandedLanguages: Set<LanguageTag> = emptySet(),
) {
    SettingsSection(
        title = stringResource(R.string.settings_section_transcription),
        contentPadding = PaddingValues(0.dp),
    ) {
        SettingsSwitchItem(
            headline = stringResource(R.string.settings_auto_start_transcription_on_open_label),
            checked = autoStartTranscriptionOnOpen,
            onCheckedChange = { enabled ->
                onEvent(SettingsEvent.SetAutoStartTranscriptionOnOpen(enabled))
            },
        )
        SettingsSwitchItem(
            headline = stringResource(R.string.settings_background_transcription_enabled_label),
            checked = backgroundTranscriptionEnabled,
            onCheckedChange = { enabled ->
                onEvent(SettingsEvent.SetBackgroundTranscriptionEnabled(enabled))
            },
        )
    }

    SettingsSectionDivider()

    ModelDownloadsAndApiKeysSection(
        downloadableModels = downloadableModels,
        openAiApiKey = openAiApiKey,
        groqApiKey = groqApiKey,
        geminiApiKey = geminiApiKey,
        onEvent = onEvent,
    )

    SettingsSectionDivider()

    SettingsSection(title = stringResource(R.string.settings_section_speech_models)) {
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
                onDownloadLanguageModel = { engine ->
                    onEvent(SettingsEvent.RequestSttModelDownload(langTag, engine))
                },
                onDeleteSherpaModel = {
                    onEvent(SettingsEvent.DeleteSherpaModel(rawInfo.language))
                },
                onDeleteLanguageSttModel = { model ->
                    onEvent(SettingsEvent.DeleteLanguageSttModel(langTag, model))
                },
                onPurgeDownloadableModel = { model ->
                    onEvent(SettingsEvent.PurgeSttModel(model))
                },
                initialExpanded = (expandFirstAccordion && index == 0) ||
                    initialExpandedLanguages.contains(langTag),
            )
        }
    }
}

@Composable
private fun ModelDownloadsAndApiKeysSection(
    downloadableModels: List<DownloadableSttModelState>,
    openAiApiKey: String,
    groqApiKey: String,
    geminiApiKey: String,
    onEvent: (SettingsEvent) -> Unit,
) {
    SettingsSection(title = stringResource(R.string.settings_section_model_downloads)) {
        Text(
            text = stringResource(R.string.settings_model_downloads_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val visibleModels = downloadableModels.filter { modelState ->
            modelState.model != DownloadableSttModel.SHERPA_WHISPER_BASE ||
                modelState.status == OnDeviceLanguageModelStatus.INSTALLED ||
                modelState.canPurge
        }
        visibleModels.forEachIndexed { index, modelState ->
            if (index > 0) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
            }
            DownloadableSttModelRow(
                modelState = modelState,
                onDownload = { onEvent(SettingsEvent.DownloadSttModel(modelState.model)) },
                onPurge = { onEvent(SettingsEvent.PurgeSttModel(modelState.model)) },
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(vertical = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Text(
            text = stringResource(R.string.settings_cloud_api_keys_subtitle),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )

        ApiKeyInputField(
            label = stringResource(R.string.settings_openai_api_key_label),
            placeholder = stringResource(R.string.settings_openai_api_key_placeholder),
            currentKey = openAiApiKey,
            onSave = { onEvent(SettingsEvent.SetOpenAiApiKey(it)) },
        )

        ApiKeyInputField(
            label = stringResource(R.string.settings_groq_api_key_label),
            placeholder = stringResource(R.string.settings_groq_api_key_placeholder),
            currentKey = groqApiKey,
            onSave = { onEvent(SettingsEvent.SetGroqApiKey(it)) },
        )

        ApiKeyInputField(
            label = stringResource(R.string.settings_gemini_api_key_label),
            placeholder = stringResource(R.string.settings_gemini_api_key_placeholder),
            currentKey = geminiApiKey,
            onSave = { onEvent(SettingsEvent.SetGeminiApiKey(it)) },
        )
    }
}

@Composable
private fun downloadableSttModelDisplayName(model: DownloadableSttModel): String = when (model) {
    DownloadableSttModel.SHERPA_WHISPER_TINY -> stringResource(R.string.settings_model_sherpa_whisper_tiny)
    DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL -> stringResource(R.string.settings_model_sherpa_sense_voice_small)
    DownloadableSttModel.SHERPA_WHISPER_BASE -> stringResource(R.string.settings_model_sherpa_whisper_base)
    DownloadableSttModel.SHERPA_WHISPER_SMALL -> stringResource(R.string.settings_model_sherpa_whisper_small)
    DownloadableSttModel.MOONSHINE_TINY -> stringResource(R.string.settings_model_moonshine_tiny)
    DownloadableSttModel.MOONSHINE_BASE -> stringResource(R.string.settings_model_moonshine_base)
}

@Composable
private fun downloadableSttModelDescription(model: DownloadableSttModel, sizeMb: Int): String = when (model) {
    DownloadableSttModel.SHERPA_WHISPER_TINY -> stringResource(R.string.settings_model_sherpa_whisper_tiny_desc, sizeMb)
    DownloadableSttModel.SHERPA_SENSE_VOICE_SMALL -> stringResource(R.string.settings_model_sherpa_sense_voice_small_desc, sizeMb)
    DownloadableSttModel.SHERPA_WHISPER_BASE -> stringResource(R.string.settings_model_sherpa_whisper_base_desc, sizeMb)
    DownloadableSttModel.SHERPA_WHISPER_SMALL -> stringResource(R.string.settings_model_sherpa_whisper_small_desc, sizeMb)
    DownloadableSttModel.MOONSHINE_TINY -> stringResource(R.string.settings_model_moonshine_tiny_desc)
    DownloadableSttModel.MOONSHINE_BASE -> stringResource(R.string.settings_model_moonshine_base_desc)
}

@Composable
private fun DownloadableSttModelRow(
    modelState: DownloadableSttModelState,
    onDownload: () -> Unit,
    onPurge: () -> Unit,
) {
    val sizeMb = ((modelState.sizeBytes / (1024L * 1024L)).toInt()).takeIf { it > 0 }
        ?: modelState.model.estimatedSizeMb
    val isPerLanguage = modelState.model.isPerLanguageModel
    val hasDownloadedLanguages = modelState.downloadedLanguages.isNotEmpty()
    val isInstalled = if (isPerLanguage) {
        hasDownloadedLanguages || modelState.status == OnDeviceLanguageModelStatus.INSTALLED
    } else {
        modelState.status == OnDeviceLanguageModelStatus.INSTALLED
    }
    val isDownloading = modelState.status == OnDeviceLanguageModelStatus.DOWNLOADING
    val statusText = when (modelState.status) {
        OnDeviceLanguageModelStatus.INSTALLED -> stringResource(R.string.settings_speech_model_status_installed)
        OnDeviceLanguageModelStatus.DOWNLOADING -> stringResource(R.string.settings_speech_model_status_downloading)
        OnDeviceLanguageModelStatus.DOWNLOAD_FAILED -> stringResource(R.string.settings_speech_model_status_download_failed)
        OnDeviceLanguageModelStatus.CHECKING -> stringResource(R.string.settings_speech_model_status_checking)
        else -> stringResource(R.string.settings_speech_model_status_not_installed)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = downloadableSttModelDisplayName(modelState.model),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = downloadableSttModelDescription(modelState.model, sizeMb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (isPerLanguage) {
                val availableLangsText = modelState.availableLanguages
                    .map { speechModelLanguageDisplayName(it) }
                    .joinToString("、")
                Text(
                    text = stringResource(R.string.settings_model_available_languages, availableLangsText),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (hasDownloadedLanguages) {
                    val downloadedLangsText = modelState.downloadedLanguages
                        .map { speechModelLanguageDisplayName(it) }
                        .joinToString("、")
                    Text(
                        text = stringResource(R.string.settings_model_downloaded_languages, downloadedLangsText),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isInstalled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }

        when {
            isDownloading -> {
                ChunkFlowLoadingIndicator(modifier = Modifier.size(32.dp))
            }

            isInstalled || modelState.canPurge -> {
                OutlinedButton(
                    onClick = onPurge,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.settings_model_action_purge),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            !isPerLanguage -> {
                Button(
                    onClick = onDownload,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.settings_model_action_download),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ApiKeyInputField(
    label: String,
    placeholder: String,
    currentKey: String,
    onSave: (String) -> Unit,
) {
    var draftKey by rememberSaveable(currentKey) { mutableStateOf(currentKey) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val isConfigured = currentKey.isNotBlank()
    val hasChanges = draftKey.trim() != currentKey.trim()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (isConfigured) {
                    stringResource(R.string.settings_api_key_status_configured)
                } else {
                    stringResource(R.string.settings_api_key_status_not_configured)
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isConfigured) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draftKey,
                onValueChange = { draftKey = it },
                placeholder = { Text(text = placeholder) },
                singleLine = true,
                visualTransformation = if (visible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) ChunkFlowIcons.VisibilityOff else ChunkFlowIcons.Visibility,
                            contentDescription = stringResource(
                                if (visible) R.string.settings_api_key_hide else R.string.settings_api_key_show,
                            ),
                        )
                    }
                },
                modifier = Modifier.weight(1f),
            )

            Button(
                onClick = { onSave(draftKey.trim()) },
                enabled = hasChanges,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_api_key_save),
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            if (isConfigured || draftKey.isNotBlank()) {
                TextButton(
                    onClick = {
                        draftKey = ""
                        onSave("")
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_api_key_clear),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
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
 * @param enabled `false` のときは操作できない（見た目も無効の色になる）。
 */
@Composable
private fun SettingsSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
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
        enabled = enabled,
    )
}

/**
 * 設定の 1 節。M3 の設定画面に合わせ、`Card` で囲まずに見出し（`titleSmall` / `primary`）だけで節を示す
 * （計画書 §3.5、調査 §3.1 S7）。節と節の間は呼び出し側が [SettingsSectionDivider] で区切る。
 *
 * @param title 節の見出し。
 * @param modifier 一番外側の `Column` に適用する Modifier。
 * @param contentPadding 中身の余白。`ListItem`（自前で 16dp の余白を持つ）を並べる節では `PaddingValues(0.dp)` にする。
 * @param content 節の中身。
 */
@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = SettingsSectionPadding),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = SettingsSectionPadding, vertical = 8.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/**
 * 二値設定の行（`ListItem` + 末尾の `Switch`。M3 の設定画面の標準形。調査 §3.1 S7）。
 *
 * 行全体が `Role.Switch` としてタップでき、`Switch` 自体はクリックを受け取らない（読み上げが二重にならない）。
 *
 * @param headline 設定名。
 * @param checked 現在の値。
 * @param onCheckedChange 新しい値を受け取る。
 * @param modifier 一番外側の `ListItem` に適用する Modifier。
 * @param supporting 任意の補足説明。
 */
@Composable
private fun SettingsSwitchItem(
    headline: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    ListItem(
        modifier = modifier.toggleable(
            value = checked,
            onValueChange = onCheckedChange,
            role = Role.Switch,
        ),
        supportingContent = supporting?.let { text -> { Text(text = text) } },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = null)
        },
    ) {
        Text(text = headline)
    }
}

/**
 * 排他選択の 1 行（`ListItem` + 先頭の `RadioButton`）。スライダーを伴う選択肢（自動ポーズのモード）に使う。
 * 親の `Column` に `selectableGroup()` を付ける。
 *
 * @param headline 選択肢のラベル。
 * @param selected 選択されているかどうか。
 * @param onClick 選ばれたときに呼ぶ。
 * @param modifier 一番外側の `ListItem` に適用する Modifier。
 */
@Composable
private fun SettingsRadioItem(
    headline: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.RadioButton,
        ),
        leadingContent = {
            RadioButton(selected = selected, onClick = null)
        },
    ) {
        Text(text = headline)
    }
}

/** [SettingsRadioItem] の本文の開始位置（16dp 余白 + 48dp の RadioButton + 16dp 間隔）。スライダーの左端を揃えるのに使う。 */
private val SettingsRadioContentStart = 80.dp

/**
 * 設定項目の見出し行（左に項目名、右に現在の値）。
 *
 * 選択肢が [ChunkFlowToggleButtonGroup] の `⋯` メニューに畳まれても、現在の値が常に見えるようにする。
 *
 * @param label 項目名。
 * @param modifier 一番外側の `Row` に適用する Modifier。
 * @param value 現在の値の表示（省略可）。
 */
@Composable
private fun SettingsItemHeader(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        if (value != null) {
            SettingsValueBadge(value = value)
        }
    }
}

/**
 * チェックボックス付きの見出し行（左にチェックボックスと項目名、右に現在の値）。
 *
 * 行全体が `Role.Checkbox` としてタップでき（48dp 以上の高さ）、チェックボックス自体はクリックを受け取らない
 * （読み上げが二重にならない）。オフのあいだ、値のバッジは無効の色で表示する。
 *
 * @param label 項目名。
 * @param checked 現在の値。
 * @param onCheckedChange 新しい値を受け取る。
 * @param modifier 一番外側の `Row` に適用する Modifier。
 * @param value 現在の値の表示（省略可）。
 */
@Composable
private fun SettingsCheckboxHeader(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Checkbox,
            ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (value != null) {
            SettingsValueBadge(value = value, enabled = checked)
        }
    }
}

/**
 * 設定項目の現在の値を示す小さなバッジ（[SettingsItemHeader]・[SettingsCheckboxHeader] の右端）。
 *
 * @param value 表示する値。
 * @param enabled `false` のときは無効の色（M3 の無効状態の不透明度）で表示する。
 */
@Composable
private fun SettingsValueBadge(
    value: String,
    enabled: Boolean = true,
) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 節と節の間の区切り線。 */
@Composable
private fun SettingsSectionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** 節の中身の横余白（`ListItem` の既定の左右余白と同じ 16dp）。 */
private val SettingsSectionPadding = 16.dp

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
    SettingsSection(title = stringResource(R.string.settings_tab_language)) {
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
    onDownloadLanguageModel: (SttEngine) -> Unit = {},
    onDeleteLanguageSttModel: (DownloadableSttModel) -> Unit = {},
    onPurgeDownloadableModel: (DownloadableSttModel) -> Unit = {},
    initialExpanded: Boolean = false,
) {
    val expanded = rememberSaveable { mutableStateOf(initialExpanded) }
    val enrichedInfo = info.enrichedWithCatalog()

    val anyEngineSupported = SttEngineSelection.isAnyEngineSupported(enrichedInfo)
    val autoResolvedEngine = SttEngineSelection.resolveAutoEngine(enrichedInfo)
    val autoLabel = if (anyEngineSupported) {
        stringResource(
            R.string.settings_stt_engine_auto_with_resolved,
            sttEngineDisplayName(autoResolvedEngine),
        )
    } else {
        "${stringResource(R.string.settings_stt_engine_auto)} (${stringResource(R.string.settings_stt_engine_unsupported)})"
    }
    val effectivePreferred = preferredEngine?.takeIf { isSttEngineSelectableInSettings(enrichedInfo, it) }
    val currentEngineLabel = when {
        !anyEngineSupported -> stringResource(R.string.settings_stt_engine_unsupported)
        effectivePreferred == null -> autoLabel
        else -> sttEngineDisplayName(effectivePreferred)
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

                val selectableEngines = SttEngineSelection.selectableEnginesForLanguage(
                    info = enrichedInfo,
                    currentPreferred = effectivePreferred,
                )
                val options: List<Pair<SttEngine?, String>> = buildList {
                    add(null to autoLabel)
                    for (engine in selectableEngines) {
                        add(engine to formatSttEngineOptionLabel(enrichedInfo, engine))
                    }
                }

                options.forEach { (engine, label) ->
                    val isOptionEnabled = isSttEngineSelectableInSettings(enrichedInfo, engine)
                    val isSelected = anyEngineSupported && effectivePreferred == engine
                    val engineStatus = engine?.let { enrichedInfo.statusOf(it) }
                    val downloadableModel = engine?.downloadableModel
                    val isDownloadableModelInstalled =
                        downloadableModel != null && engineStatus == OnDeviceLanguageModelStatus.INSTALLED
                    val isPerLanguageModelSupported =
                        downloadableModel != null &&
                            downloadableModel.isPerLanguageModel &&
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
                            isPerLanguageModelSupported &&
                                (
                                    engineStatus == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
                                        engineStatus == OnDeviceLanguageModelStatus.DOWNLOAD_FAILED
                                    ) -> {
                                val langSizeMb = downloadableModel.approxSizeMbForLanguage(enrichedInfo.language)
                                OutlinedButton(
                                    onClick = { onDownloadLanguageModel(engine) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .height(28.dp),
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.settings_speech_model_action_download_with_size,
                                            langSizeMb,
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }

                            isPerLanguageModelSupported &&
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
}

@Composable
private fun sttEngineLabelText(label: SttEngineLabel): String = when (label) {
    SttEngineLabel.RECOMMENDED -> stringResource(R.string.settings_stt_label_recommended)
    SttEngineLabel.HIGH_ACCURACY -> stringResource(R.string.settings_stt_label_high_accuracy)
    SttEngineLabel.FAST -> stringResource(R.string.settings_stt_label_fast)
    SttEngineLabel.SLOW -> stringResource(R.string.settings_stt_label_slow)
}

@Composable
private fun formatSttEngineOptionLabel(
    enrichedInfo: OnDeviceLanguageModelInfo,
    engine: SttEngine,
): String {
    val status = enrichedInfo.statusOf(engine)
    val rawLabel = when (engine) {
        SttEngine.ML_KIT -> formatEngineOptionWithStatus(
            baseName = stringResource(R.string.settings_stt_engine_mlkit_radio),
            status = status,
        )

        SttEngine.SPEECH_RECOGNIZER -> formatEngineOptionWithStatus(
            baseName = stringResource(R.string.settings_stt_engine_speech_recognizer),
            status = status,
        )

        SttEngine.SHERPA_ONNX -> {
            val sizeMb = SherpaDownloadDialogState.resolveSizeMb(enrichedInfo.sherpaModelSizeBytes)
            when (status) {
                OnDeviceLanguageModelStatus.INSTALLED ->
                    stringResource(R.string.settings_stt_engine_sherpa_downloaded, sizeMb)

                OnDeviceLanguageModelStatus.NOT_INSTALLED ->
                    stringResource(R.string.settings_stt_engine_sherpa_not_downloaded, sizeMb)

                else -> formatEngineOptionWithStatus(
                    baseName = stringResource(R.string.settings_stt_engine_sherpa_onnx),
                    status = status,
                )
            }
        }

        SttEngine.MOONSHINE_TINY,
        SttEngine.MOONSHINE_BASE,
        -> {
            val baseName = sttEngineDisplayName(engine)
            val sizeMb = engine.downloadableModel?.approxSizeMbForLanguage(enrichedInfo.language) ?: 100
            when (status) {
                OnDeviceLanguageModelStatus.INSTALLED ->
                    stringResource(R.string.settings_stt_engine_model_downloaded_format, baseName, sizeMb)

                else -> formatEngineOptionWithStatus(
                    baseName = baseName,
                    status = status,
                )
            }
        }

        SttEngine.SHERPA_ONNX_SENSE_VOICE,
        SttEngine.SHERPA_ONNX_WHISPER_BASE,
        SttEngine.SHERPA_ONNX_WHISPER_SMALL,
        -> {
            val baseName = sttEngineDisplayName(engine)
            val sizeMb = engine.downloadableModel?.estimatedSizeMb ?: 100
            when (status) {
                OnDeviceLanguageModelStatus.INSTALLED ->
                    stringResource(R.string.settings_stt_engine_model_downloaded_format, baseName, sizeMb)

                OnDeviceLanguageModelStatus.NOT_INSTALLED ->
                    stringResource(R.string.settings_stt_engine_model_not_downloaded_format, baseName, sizeMb)

                else -> formatEngineOptionWithStatus(
                    baseName = baseName,
                    status = status,
                )
            }
        }

        SttEngine.OPENAI_API,
        SttEngine.GROQ_API,
        SttEngine.GEMINI_API,
        -> {
            val baseName = sttEngineDisplayName(engine)
            when (status) {
                OnDeviceLanguageModelStatus.INSTALLED -> baseName

                OnDeviceLanguageModelStatus.NOT_INSTALLED ->
                    "$baseName (${stringResource(R.string.settings_api_key_status_not_configured)})"

                else -> formatEngineOptionWithStatus(baseName = baseName, status = status)
            }
        }
    }
    val labels = SttEngineSelection.labelsFor(enrichedInfo.language, engine)
    if (labels.isEmpty()) {
        return rawLabel
    }
    val badgeText = labels.map { sttEngineLabelText(it) }.joinToString("・")
    return "$rawLabel [$badgeText]"
}

@Composable
private fun formatEngineOptionWithStatus(
    baseName: String,
    status: OnDeviceLanguageModelStatus,
): String = when (status) {
    OnDeviceLanguageModelStatus.INSTALLED -> baseName

    OnDeviceLanguageModelStatus.NOT_INSTALLED ->
        "$baseName (${stringResource(R.string.settings_speech_model_status_not_installed)})"

    OnDeviceLanguageModelStatus.DOWNLOADING ->
        "$baseName (${stringResource(R.string.settings_speech_model_status_downloading)})"

    OnDeviceLanguageModelStatus.DOWNLOAD_FAILED ->
        "$baseName (${stringResource(R.string.settings_speech_model_status_download_failed)})"

    OnDeviceLanguageModelStatus.CHECKING ->
        "$baseName (${stringResource(R.string.settings_speech_model_status_checking)})"

    OnDeviceLanguageModelStatus.UNSUPPORTED ->
        "$baseName (${stringResource(R.string.settings_stt_engine_unsupported)})"
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
                    ChunkFlowLoadingIndicator(modifier = Modifier.size(40.dp))
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
                // ダイアログの最重要操作は Filled の Button にする（調査 §3.2 C10）。
                Button(onClick = onConfirm) {
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
                downloadableModels = DownloadableSttModel.entries.map { model ->
                    when (model) {
                        DownloadableSttModel.SHERPA_WHISPER_TINY -> DownloadableSttModelState(
                            model = model,
                            status = OnDeviceLanguageModelStatus.INSTALLED,
                            canPurge = true,
                        )

                        DownloadableSttModel.MOONSHINE_TINY -> DownloadableSttModelState(
                            model = model,
                            status = OnDeviceLanguageModelStatus.INSTALLED,
                            canPurge = true,
                            downloadedLanguages = listOf(LanguageTag("en"), LanguageTag("ja")),
                        )

                        else -> DownloadableSttModelState(model = model)
                    }
                },
                languageModels = listOf(
                    OnDeviceLanguageModelInfo(
                        language = LanguageTag("en"),
                        status = OnDeviceLanguageModelStatus.INSTALLED,
                        sherpaModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                        mlKitStatus = OnDeviceLanguageModelStatus.INSTALLED,
                        sherpaModelSizeBytes = 98L * 1024L * 1024L,
                        extraEngineStatuses = mapOf(
                            SttEngine.MOONSHINE_TINY to OnDeviceLanguageModelStatus.INSTALLED,
                            SttEngine.MOONSHINE_BASE to OnDeviceLanguageModelStatus.NOT_INSTALLED,
                        ),
                    ),
                    OnDeviceLanguageModelInfo(
                        language = LanguageTag("ja"),
                        status = OnDeviceLanguageModelStatus.INSTALLED,
                        sherpaModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
                        mlKitStatus = OnDeviceLanguageModelStatus.INSTALLED,
                        mlKitMode = com.miuraken.chunkflow.core.model.MlKitRecognitionMode.ADVANCED,
                        sherpaModelSizeBytes = 98L * 1024L * 1024L,
                        extraEngineStatuses = mapOf(
                            SttEngine.MOONSHINE_TINY to OnDeviceLanguageModelStatus.INSTALLED,
                            SttEngine.MOONSHINE_BASE to OnDeviceLanguageModelStatus.NOT_INSTALLED,
                        ),
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
