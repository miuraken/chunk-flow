package com.miuraken.chunkflow.feature.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.component.ALPHABETICAL_SELECTABLE_LANGUAGE_OPTIONS
import com.miuraken.chunkflow.core.designsystem.component.LanguageSettingsModalDialog
import com.miuraken.chunkflow.core.designsystem.component.MissingLanguageHintWithModalLink
import com.miuraken.chunkflow.core.designsystem.component.SelectableLanguageOption
import com.miuraken.chunkflow.core.designsystem.component.buildSelectableLanguageOptions
import com.miuraken.chunkflow.core.designsystem.component.normalizeSelectableLanguageCode
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageSettings
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.SttEngine
import java.util.Locale

internal typealias ItemLanguageOption = SelectableLanguageOption

internal val ITEM_LANGUAGE_OPTIONS: List<ItemLanguageOption> = ALPHABETICAL_SELECTABLE_LANGUAGE_OPTIONS

internal fun normalizeItemLanguageCode(rawCode: String): String = normalizeSelectableLanguageCode(rawCode)

internal fun buildItemLanguageOptions(
    currentCode: String,
    noneLabel: String? = null,
    enabledLanguages: List<LanguageTag> = emptyList(),
): List<ItemLanguageOption> = buildSelectableLanguageOptions(
    currentCode = currentCode,
    noneLabel = noneLabel,
    enabledLanguages = enabledLanguages,
)

/**
 * 言語設定（[sttEngineByLanguage] および [languageModelInfos]）で規定されている当該言語の既定文字起こしエンジンを返す。
 */
internal fun resolveConfiguredDefaultEngineForLanguage(
    rawCode: String,
    sttEngineByLanguage: Map<LanguageTag, SttEngine> = emptyMap(),
    languageModelInfos: Map<LanguageTag, OnDeviceLanguageModelInfo> = emptyMap(),
): SttEngine {
    val normalized = normalizeItemLanguageCode(rawCode).ifBlank { return SttEngine.ML_KIT }
    val langTag = LanguageTag(normalized)
    val settingsNormalizedTag = LanguageSettings.normalize(langTag)
    val modelLangTag = LanguageTag(
        com.miuraken.chunkflow.core.model.SupportedLanguageCatalog.normalizeToModelManagementCode(langTag.value),
    )

    val explicitPreferred = sttEngineByLanguage[langTag]
        ?: sttEngineByLanguage[settingsNormalizedTag]
        ?: sttEngineByLanguage[modelLangTag]
    if (explicitPreferred != null) {
        return explicitPreferred
    }

    val rawInfo = languageModelInfos[langTag]
        ?: languageModelInfos[settingsNormalizedTag]
        ?: languageModelInfos[modelLangTag]
        ?: OnDeviceLanguageModelInfo(language = langTag)
    val enriched = DetailedTranscriptionDialogState.enrichWithCatalog(rawInfo, langTag)
    return enriched.effectiveEngine(override = null, userPreferred = null)
        ?: SttEngine.AUTO_ORDER.firstOrNull { enriched.statusOf(it) != OnDeviceLanguageModelStatus.UNSUPPORTED }
        ?: SttEngine.ML_KIT
}

/**
 * 学習言語を [targetCode] に変えたあとの言語ペアを返す。変更がない、または無効な場合は `null`。
 *
 * いまのベース言語と同じ言語を学習言語に選んだときは、学習言語とベース言語を入れ替える。
 */
internal fun languagePairWithTarget(
    current: LanguagePair?,
    targetCode: String,
): LanguagePair? {
    val newTarget = normalizeItemLanguageCode(targetCode).ifBlank { return null }
    val currentTarget = current?.target?.value?.let(::normalizeItemLanguageCode).orEmpty()
    val currentBase = current?.base?.value?.let(::normalizeItemLanguageCode)?.takeIf { it.isNotBlank() }
    if (newTarget == currentTarget) return null
    val newBase = if (newTarget == currentBase) currentTarget.takeIf { it.isNotBlank() } else currentBase
    return LanguagePair(target = LanguageTag(newTarget), base = newBase?.let(::LanguageTag))
}

/**
 * ベース言語を [baseCode]（空文字は「なし」）に変えたあとの言語ペアを返す。変更がない、または無効な場合は `null`。
 *
 * いまの学習言語と同じ言語をベース言語に選んだときは、学習言語とベース言語を入れ替える
 * （ベース言語が「なし」で入れ替えられない場合は `null`）。
 */
internal fun languagePairWithBase(
    current: LanguagePair?,
    baseCode: String,
): LanguagePair? {
    val currentTarget = current?.target?.value?.let(::normalizeItemLanguageCode)?.takeIf { it.isNotBlank() }
        ?: return null
    val currentBase = current.base?.value?.let(::normalizeItemLanguageCode)?.takeIf { it.isNotBlank() }
    val newBase = normalizeItemLanguageCode(baseCode).takeIf { it.isNotBlank() }
    if (newBase == currentBase) return null
    if (newBase == currentTarget) {
        val swappedTarget = currentBase ?: return null
        return LanguagePair(target = LanguageTag(swappedTarget), base = LanguageTag(currentTarget))
    }
    return LanguagePair(target = LanguageTag(currentTarget), base = newBase?.let(::LanguageTag))
}

/**
 * プレイヤーの設定シートの「言語」セクション。開いている教材の学習言語とベース言語をプルダウンで選び直す。
 *
 * 選んだ時点で [onChangeLanguages] を呼ぶ（いまの言語と同じものを選んだときは呼ばない）。
 */
@Composable
internal fun ItemLanguageSettingsSection(
    languagePair: LanguagePair?,
    itemSttOverride: ItemSttOverride,
    onChangeLanguages: (LanguagePair) -> Unit,
    onChangeTargetEngine: (SttEngine?) -> Unit,
    onChangeBaseEngine: (SttEngine?) -> Unit,
    modifier: Modifier = Modifier,
    enabledLanguages: List<LanguageTag> = emptyList(),
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
    sttEngineByLanguage: Map<LanguageTag, SttEngine> = emptyMap(),
    languageModelInfos: Map<LanguageTag, OnDeviceLanguageModelInfo> = emptyMap(),
    showSectionTitle: Boolean = true,
) {
    var showLanguageSettingsModal by rememberSaveable { mutableStateOf(false) }
    val langPalette = chunkFlowLanguagePalette()
    val noneLabel = stringResource(R.string.player_settings_base_lang_none)
    val targetCode = normalizeItemLanguageCode(languagePair?.target?.value.orEmpty())
    val baseCode = normalizeItemLanguageCode(languagePair?.base?.value.orEmpty())
    val targetOptions = remember(targetCode, enabledLanguages) {
        buildItemLanguageOptions(currentCode = targetCode, enabledLanguages = enabledLanguages)
    }
    val baseOptions = remember(baseCode, noneLabel, enabledLanguages) {
        buildItemLanguageOptions(
            currentCode = baseCode,
            noneLabel = noneLabel,
            enabledLanguages = enabledLanguages,
        )
    }
    val targetDefaultEngine = remember(targetCode, sttEngineByLanguage, languageModelInfos) {
        resolveConfiguredDefaultEngineForLanguage(
            rawCode = targetCode,
            sttEngineByLanguage = sttEngineByLanguage,
            languageModelInfos = languageModelInfos,
        )
    }
    val baseDefaultEngine = remember(baseCode, sttEngineByLanguage, languageModelInfos) {
        resolveConfiguredDefaultEngineForLanguage(
            rawCode = baseCode,
            sttEngineByLanguage = sttEngineByLanguage,
            languageModelInfos = languageModelInfos,
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (showSectionTitle) {
            Text(
                text = stringResource(R.string.player_settings_language_section_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        ItemLanguageDropdown(
            label = stringResource(R.string.player_settings_target_lang_label),
            selectedCode = targetCode,
            options = targetOptions,
            fallbackLabel = targetCode,
            containerColor = langPalette.targetRowBackground,
            accentColor = langPalette.onTargetBadgeContainer,
            onSelect = { code -> languagePairWithTarget(languagePair, code)?.let(onChangeLanguages) },
        )
        SttEngineDropdown(
            label = stringResource(R.string.player_settings_target_stt_engine_label),
            selectedEngine = itemSttOverride.targetEngine,
            defaultEngine = targetDefaultEngine,
            containerColor = langPalette.targetRowBackground,
            accentColor = langPalette.onTargetBadgeContainer,
            onSelect = onChangeTargetEngine,
        )
        ItemLanguageDropdown(
            label = stringResource(R.string.player_settings_base_lang_label),
            selectedCode = baseCode,
            options = baseOptions,
            fallbackLabel = noneLabel,
            containerColor = langPalette.baseRowBackground,
            accentColor = langPalette.onBaseBadgeContainer,
            onSelect = { code -> languagePairWithBase(languagePair, code)?.let(onChangeLanguages) },
        )
        SttEngineDropdown(
            label = stringResource(R.string.player_settings_base_stt_engine_label),
            selectedEngine = itemSttOverride.baseEngine,
            defaultEngine = baseDefaultEngine,
            containerColor = langPalette.baseRowBackground,
            accentColor = langPalette.onBaseBadgeContainer,
            onSelect = onChangeBaseEngine,
        )
        MissingLanguageHintWithModalLink(
            onOpenLanguageSettingsModal = { showLanguageSettingsModal = true },
        )
    }

    if (showLanguageSettingsModal) {
        LanguageSettingsModalDialog(
            enabledLanguages = enabledLanguages.toSet(),
            onToggleLanguage = onToggleEnabledLanguage,
            onDismiss = { showLanguageSettingsModal = false },
            isFirstLaunchOnboarding = false,
        )
    }
}

@Composable
private fun sttEngineDisplayName(engine: SttEngine): String = when (engine) {
    SttEngine.ML_KIT -> stringResource(R.string.player_settings_stt_engine_mlkit)
    SttEngine.SPEECH_RECOGNIZER -> stringResource(R.string.player_settings_stt_engine_speech_recognizer)
    SttEngine.SHERPA_ONNX -> stringResource(R.string.player_settings_stt_engine_sherpa_onnx)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SttEngineDropdown(
    label: String,
    selectedEngine: SttEngine?,
    defaultEngine: SttEngine,
    containerColor: Color,
    accentColor: Color,
    onSelect: (SttEngine?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val defaultEngineLabel = stringResource(
        R.string.player_settings_stt_engine_default_with_name,
        sttEngineDisplayName(defaultEngine),
    )

    val options = listOf(
        null to defaultEngineLabel,
        SttEngine.ML_KIT to stringResource(R.string.player_settings_stt_engine_mlkit),
        SttEngine.SPEECH_RECOGNIZER to stringResource(R.string.player_settings_stt_engine_speech_recognizer),
        SttEngine.SHERPA_ONNX to stringResource(R.string.player_settings_stt_engine_sherpa_onnx),
    )

    val selectedLabel = options.firstOrNull { it.first == selectedEngine }?.second ?: options.first().second

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.fillMaxWidth().padding(10.dp),
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(text = label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    focusedLabelColor = accentColor,
                ),
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { (engine, optionLabel) ->
                    val isSelected = engine == selectedEngine
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = optionLabel,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(engine)
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemLanguageDropdown(
    label: String,
    selectedCode: String,
    options: List<ItemLanguageOption>,
    fallbackLabel: String,
    containerColor: Color,
    accentColor: Color,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.code == selectedCode }?.label ?: fallbackLabel

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
        ) {
            OutlinedTextField(
                value = selectedLabel,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(text = label) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentColor,
                    focusedLabelColor = accentColor,
                ),
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                options.forEach { option ->
                    val isSelected = option.code == selectedCode
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = option.label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(option.code)
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    )
                }
            }
        }
    }
}
