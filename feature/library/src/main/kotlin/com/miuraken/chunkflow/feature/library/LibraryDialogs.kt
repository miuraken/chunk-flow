package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.miuraken.chunkflow.core.designsystem.component.MissingLanguageHintText
import com.miuraken.chunkflow.core.designsystem.component.SelectableLanguageOption
import com.miuraken.chunkflow.core.designsystem.component.buildSelectableLanguageOptions
import com.miuraken.chunkflow.core.designsystem.component.normalizeSelectableLanguageCode
import com.miuraken.chunkflow.core.designsystem.component.resolveConfiguredDefaultEngineForLanguage
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SttEngine

internal typealias LanguageDropdownOption = SelectableLanguageOption

internal val ALPHABETICAL_LANGUAGE_OPTIONS: List<LanguageDropdownOption> =
    ALPHABETICAL_SELECTABLE_LANGUAGE_OPTIONS

internal fun normalizeDialogLanguageCode(rawCode: String): String = normalizeSelectableLanguageCode(rawCode)

internal fun buildDialogLanguageOptions(
    currentCode: String,
    noneLabel: String? = null,
    enabledLanguages: List<LanguageTag> = emptyList(),
): List<LanguageDropdownOption> = buildSelectableLanguageOptions(
    currentCode = currentCode,
    noneLabel = noneLabel,
    enabledLanguages = enabledLanguages,
)

@Composable
private fun librarySttEngineDisplayName(engine: SttEngine): String = when (engine) {
    SttEngine.ML_KIT -> stringResource(R.string.library_edit_stt_engine_mlkit)
    SttEngine.SPEECH_RECOGNIZER -> stringResource(R.string.library_edit_stt_engine_speech_recognizer)
    SttEngine.SHERPA_ONNX -> stringResource(R.string.library_edit_stt_engine_sherpa_onnx)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySttEngineDropdown(
    label: String,
    selectedEngine: SttEngine?,
    defaultEngine: SttEngine,
    accentColor: Color,
    onSelect: (SttEngine?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val defaultEngineLabel = stringResource(
        R.string.library_edit_stt_engine_default_with_name,
        librarySttEngineDisplayName(defaultEngine),
    )

    val options = listOf(
        null to defaultEngineLabel,
        SttEngine.ML_KIT to stringResource(R.string.library_edit_stt_engine_mlkit),
        SttEngine.SPEECH_RECOGNIZER to stringResource(R.string.library_edit_stt_engine_speech_recognizer),
        SttEngine.SHERPA_ONNX to stringResource(R.string.library_edit_stt_engine_sherpa_onnx),
    )

    val selectedLabel = options.firstOrNull { it.first == selectedEngine }?.second ?: options.first().second

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LanguagePairDropdownSection(
    targetLangCode: String,
    baseLangCode: String,
    onTargetLangChange: (String) -> Unit,
    onBaseLangChange: (String) -> Unit,
    targetSttEngine: SttEngine? = null,
    baseSttEngine: SttEngine? = null,
    onTargetSttEngineChange: (SttEngine?) -> Unit = {},
    onBaseSttEngineChange: (SttEngine?) -> Unit = {},
    sttEngineByLanguage: Map<LanguageTag, SttEngine> = emptyMap(),
    enabledLanguages: List<LanguageTag> = emptyList(),
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
    onOpenLanguageSettings: () -> Unit = {},
) {
    val langPalette = chunkFlowLanguagePalette()
    val noneLabel = stringResource(R.string.library_edit_base_lang_none)

    val normalizedTarget = normalizeDialogLanguageCode(targetLangCode)
    val normalizedBase = normalizeDialogLanguageCode(baseLangCode)

    val targetOptions = remember(normalizedTarget, enabledLanguages) {
        buildDialogLanguageOptions(
            currentCode = normalizedTarget,
            noneLabel = null,
            enabledLanguages = enabledLanguages,
        )
    }
    val baseOptions = remember(normalizedBase, noneLabel, enabledLanguages) {
        buildDialogLanguageOptions(
            currentCode = normalizedBase,
            noneLabel = noneLabel,
            enabledLanguages = enabledLanguages,
        )
    }

    val targetDefaultEngine = remember(normalizedTarget, sttEngineByLanguage) {
        resolveConfiguredDefaultEngineForLanguage(
            rawCode = normalizedTarget,
            sttEngineByLanguage = sttEngineByLanguage,
        )
    }
    val baseDefaultEngine = remember(normalizedBase, sttEngineByLanguage) {
        resolveConfiguredDefaultEngineForLanguage(
            rawCode = normalizedBase,
            sttEngineByLanguage = sttEngineByLanguage,
        )
    }

    val selectedTargetLabel = targetOptions.firstOrNull { it.code == normalizedTarget }?.label
        ?: normalizedTarget.ifBlank { "en" }
    val selectedBaseLabel = baseOptions.firstOrNull { it.code == normalizedBase }?.label
        ?: noneLabel

    var targetExpanded by remember { mutableStateOf(false) }
    var baseExpanded by remember { mutableStateOf(false) }

    Surface(
        color = langPalette.targetRowBackground,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExposedDropdownMenuBox(
                expanded = targetExpanded,
                onExpandedChange = { targetExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = selectedTargetLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.library_edit_target_lang_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = targetExpanded)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = langPalette.onTargetBadgeContainer,
                        focusedLabelColor = langPalette.onTargetBadgeContainer,
                    ),
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = targetExpanded,
                    onDismissRequest = { targetExpanded = false },
                ) {
                    targetOptions.forEach { option ->
                        val isSelected = option.code == normalizedTarget
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.label,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                targetExpanded = false
                                onTargetLangChange(option.code)
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }

            LibrarySttEngineDropdown(
                label = stringResource(R.string.library_edit_target_stt_engine_label),
                selectedEngine = targetSttEngine,
                defaultEngine = targetDefaultEngine,
                accentColor = langPalette.onTargetBadgeContainer,
                onSelect = onTargetSttEngineChange,
            )
        }
    }

    Surface(
        color = langPalette.baseRowBackground,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ExposedDropdownMenuBox(
                expanded = baseExpanded,
                onExpandedChange = { baseExpanded = it },
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = selectedBaseLabel,
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(text = stringResource(R.string.library_edit_base_lang_label)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = baseExpanded)
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = langPalette.onBaseBadgeContainer,
                        focusedLabelColor = langPalette.onBaseBadgeContainer,
                    ),
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = baseExpanded,
                    onDismissRequest = { baseExpanded = false },
                ) {
                    baseOptions.forEach { option ->
                        val isSelected = option.code == normalizedBase
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = option.label,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                baseExpanded = false
                                onBaseLangChange(option.code)
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }

            LibrarySttEngineDropdown(
                label = stringResource(R.string.library_edit_base_stt_engine_label),
                selectedEngine = baseSttEngine,
                defaultEngine = baseDefaultEngine,
                accentColor = langPalette.onBaseBadgeContainer,
                onSelect = onBaseSttEngineChange,
            )
        }
    }

    MissingLanguageHintText(
        text = stringResource(R.string.library_missing_language_hint),
    )

    HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

    OutlinedButton(
        onClick = onOpenLanguageSettings,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = ChunkFlowIcons.Settings,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = stringResource(R.string.library_open_global_language_settings))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImportConfigDialog(
    config: PendingImportConfig,
    enabledLanguages: List<LanguageTag>,
    onConfirm: (String?, LanguagePair, ItemSttOverride) -> Unit,
    onDismiss: () -> Unit,
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
    onOpenLanguageSettings: () -> Unit = {},
    sttEngineByLanguage: Map<LanguageTag, SttEngine> = emptyMap(),
    availableFolders: List<String> = listOf(LibraryCourseFolder.DEFAULT_IMPORTED_COURSE),
    onCreateVirtualFolder: (String, LanguagePair, ItemSttOverride) -> Unit = { _, _, _ -> },
) {
    val isFileImport = !config.isDriveSyncFolder && config.treeUri == null
    var courseText by remember(config) {
        mutableStateOf(
            if (isFileImport) {
                config.initialCourse?.takeIf { it.isNotBlank() }
                    ?: LibraryCourseFolder.DEFAULT_IMPORTED_COURSE
            } else {
                config.initialCourse.orEmpty()
            },
        )
    }
    var targetLangText by remember(config) {
        mutableStateOf(normalizeDialogLanguageCode(config.initialLanguages.target.value))
    }
    var baseLangText by remember(config) {
        mutableStateOf(normalizeDialogLanguageCode(config.initialLanguages.base?.value.orEmpty()))
    }
    var targetSttEngine by remember(config) {
        mutableStateOf(config.initialSttOverride.targetEngine)
    }
    var baseSttEngine by remember(config) {
        mutableStateOf(config.initialSttOverride.baseEngine)
    }
    var folderDropdownExpanded by remember { mutableStateOf(false) }
    var showNewVirtualFolderDialog by rememberSaveable { mutableStateOf(false) }
    var newVirtualFolderName by rememberSaveable { mutableStateOf("") }

    val folderOptions = remember(availableFolders, courseText) {
        buildList {
            add(LibraryCourseFolder.DEFAULT_IMPORTED_COURSE)
            availableFolders.forEach { folder ->
                val trimmed = folder.trim()
                if (trimmed.isNotBlank() && trimmed !in this) {
                    add(trimmed)
                }
            }
            val currentTrimmed = courseText.trim()
            if (currentTrimmed.isNotBlank() && currentTrimmed !in this) {
                add(currentTrimmed)
            }
        }
    }

    val dialogTitle = if (config.isDriveSyncFolder) {
        stringResource(R.string.library_drive_sync_config_title)
    } else {
        stringResource(R.string.library_import_config_title)
    }
    val dialogHint = if (config.isDriveSyncFolder) {
        stringResource(R.string.library_drive_sync_config_hint)
    } else {
        stringResource(R.string.library_import_config_hint, config.uris.size)
    }
    val confirmLabel = if (config.isDriveSyncFolder) {
        stringResource(R.string.library_drive_sync_config_confirm)
    } else {
        stringResource(R.string.library_import_config_confirm)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = dialogTitle) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = dialogHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (isFileImport) {
                    ExposedDropdownMenuBox(
                        expanded = folderDropdownExpanded,
                        onExpandedChange = { folderDropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = courseText,
                            onValueChange = {},
                            readOnly = true,
                            singleLine = true,
                            label = {
                                Text(text = stringResource(R.string.library_import_config_folder_label))
                            },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = folderDropdownExpanded)
                            },
                            modifier = Modifier
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                                .fillMaxWidth(),
                        )
                        ExposedDropdownMenu(
                            expanded = folderDropdownExpanded,
                            onDismissRequest = { folderDropdownExpanded = false },
                        ) {
                            folderOptions.forEach { folder ->
                                val isSelected = folder == courseText.trim()
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = folder,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        folderDropdownExpanded = false
                                        courseText = folder
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = stringResource(R.string.library_import_config_new_virtual_folder_option),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = ChunkFlowIcons.Add,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                },
                                onClick = {
                                    folderDropdownExpanded = false
                                    newVirtualFolderName = ""
                                    showNewVirtualFolderDialog = true
                                },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = courseText,
                        onValueChange = { courseText = it },
                        label = { Text(text = stringResource(R.string.library_edit_course_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                LanguagePairDropdownSection(
                    targetLangCode = targetLangText,
                    baseLangCode = baseLangText,
                    onTargetLangChange = { targetLangText = it },
                    onBaseLangChange = { baseLangText = it },
                    targetSttEngine = targetSttEngine,
                    baseSttEngine = baseSttEngine,
                    onTargetSttEngineChange = { targetSttEngine = it },
                    onBaseSttEngineChange = { baseSttEngine = it },
                    sttEngineByLanguage = sttEngineByLanguage,
                    enabledLanguages = enabledLanguages,
                    onToggleEnabledLanguage = onToggleEnabledLanguage,
                    onOpenLanguageSettings = onOpenLanguageSettings,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanTarget = normalizeDialogLanguageCode(targetLangText).ifBlank { "en" }
                    val cleanBase = normalizeDialogLanguageCode(baseLangText).ifBlank { null }
                    onConfirm(
                        courseText.trim().ifBlank { null },
                        LanguagePair(
                            target = LanguageTag(cleanTarget),
                            base = cleanBase?.let { LanguageTag(it) },
                        ),
                        ItemSttOverride(
                            targetEngine = targetSttEngine,
                            baseEngine = baseSttEngine,
                        ),
                    )
                },
            ) {
                Text(text = confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.library_edit_cancel))
            }
        },
    )

    if (showNewVirtualFolderDialog) {
        val trimmedNewFolder = newVirtualFolderName.trim()
        AlertDialog(
            onDismissRequest = { showNewVirtualFolderDialog = false },
            title = {
                Text(text = stringResource(R.string.library_import_config_new_virtual_folder_dialog_title))
            },
            text = {
                OutlinedTextField(
                    value = newVirtualFolderName,
                    onValueChange = { newVirtualFolderName = it },
                    label = {
                        Text(text = stringResource(R.string.library_import_config_new_virtual_folder_name_label))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (trimmedNewFolder.isNotBlank()) {
                            val cleanTarget = normalizeDialogLanguageCode(targetLangText).ifBlank { "en" }
                            val cleanBase = normalizeDialogLanguageCode(baseLangText).ifBlank { null }
                            courseText = trimmedNewFolder
                            onCreateVirtualFolder(
                                trimmedNewFolder,
                                LanguagePair(
                                    target = LanguageTag(cleanTarget),
                                    base = cleanBase?.let { LanguageTag(it) },
                                ),
                                ItemSttOverride(
                                    targetEngine = targetSttEngine,
                                    baseEngine = baseSttEngine,
                                ),
                            )
                            showNewVirtualFolderDialog = false
                        }
                    },
                    enabled = trimmedNewFolder.isNotBlank(),
                ) {
                    Text(text = stringResource(R.string.library_create_virtual_folder_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewVirtualFolderDialog = false }) {
                    Text(text = stringResource(R.string.library_edit_cancel))
                }
            },
        )
    }
}

@Composable
internal fun CreateVirtualFolderDialog(
    enabledLanguages: List<LanguageTag>,
    onConfirm: (String, LanguagePair, ItemSttOverride) -> Unit,
    onDismiss: () -> Unit,
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
    onOpenLanguageSettings: () -> Unit = {},
    sttEngineByLanguage: Map<LanguageTag, SttEngine> = emptyMap(),
) {
    val defaultTarget = remember(enabledLanguages) {
        enabledLanguages.firstOrNull { normalizeDialogLanguageCode(it.value) != "ja" }
            ?.let { normalizeDialogLanguageCode(it.value) }
            ?: enabledLanguages.firstOrNull()?.let { normalizeDialogLanguageCode(it.value) }
            ?: "en"
    }
    val defaultBase = remember(enabledLanguages, defaultTarget) {
        enabledLanguages.firstOrNull {
            val norm = normalizeDialogLanguageCode(it.value)
            norm == "ja" && norm != defaultTarget
        }?.let { normalizeDialogLanguageCode(it.value) }
            ?: enabledLanguages.firstOrNull { normalizeDialogLanguageCode(it.value) != defaultTarget }
                ?.let { normalizeDialogLanguageCode(it.value) }
            ?: "ja"
    }
    var folderNameText by remember { mutableStateOf("") }
    var targetLangText by remember(defaultTarget) { mutableStateOf(defaultTarget) }
    var baseLangText by remember(defaultBase) { mutableStateOf(defaultBase) }
    var targetSttEngine by remember { mutableStateOf<SttEngine?>(null) }
    var baseSttEngine by remember { mutableStateOf<SttEngine?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.library_create_virtual_folder_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = folderNameText,
                    onValueChange = { folderNameText = it },
                    label = { Text(text = stringResource(R.string.library_create_virtual_folder_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                LanguagePairDropdownSection(
                    targetLangCode = targetLangText,
                    baseLangCode = baseLangText,
                    onTargetLangChange = { targetLangText = it },
                    onBaseLangChange = { baseLangText = it },
                    targetSttEngine = targetSttEngine,
                    baseSttEngine = baseSttEngine,
                    onTargetSttEngineChange = { targetSttEngine = it },
                    onBaseSttEngineChange = { baseSttEngine = it },
                    sttEngineByLanguage = sttEngineByLanguage,
                    enabledLanguages = enabledLanguages,
                    onToggleEnabledLanguage = onToggleEnabledLanguage,
                    onOpenLanguageSettings = onOpenLanguageSettings,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = folderNameText.trim().trim('/')
                    if (cleanName.isNotEmpty()) {
                        val cleanTarget = normalizeDialogLanguageCode(targetLangText).ifBlank { "en" }
                        val cleanBase = normalizeDialogLanguageCode(baseLangText).ifBlank { null }
                        onConfirm(
                            cleanName,
                            LanguagePair(
                                target = LanguageTag(cleanTarget),
                                base = cleanBase?.let { LanguageTag(it) },
                            ),
                            ItemSttOverride(
                                targetEngine = targetSttEngine,
                                baseEngine = baseSttEngine,
                            ),
                        )
                    }
                },
                enabled = folderNameText.trim().trim('/').isNotEmpty(),
            ) {
                Text(text = stringResource(R.string.library_create_virtual_folder_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.library_edit_cancel))
            }
        },
    )
}

@Composable
internal fun EditItemMetadataDialog(
    item: AudioItem,
    enabledLanguages: List<LanguageTag>,
    onImportTranscript: () -> Unit,
    onConfirm: (String?, LanguagePair, ItemSttOverride) -> Unit,
    onDismiss: () -> Unit,
    initialSttOverride: ItemSttOverride = ItemSttOverride.NONE,
    sttEngineByLanguage: Map<LanguageTag, SttEngine> = emptyMap(),
    onDeleteLocalAudioCopy: (() -> Unit)? = null,
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
    onOpenLanguageSettings: () -> Unit = {},
) {
    var courseText by remember(item) { mutableStateOf(item.course.orEmpty()) }
    var targetLangText by remember(item) {
        mutableStateOf(normalizeDialogLanguageCode(item.languagePair.target.value))
    }
    var baseLangText by remember(item) {
        mutableStateOf(normalizeDialogLanguageCode(item.languagePair.base?.value.orEmpty()))
    }
    var targetSttEngine by remember(item, initialSttOverride) {
        mutableStateOf(initialSttOverride.targetEngine)
    }
    var baseSttEngine by remember(item, initialSttOverride) {
        mutableStateOf(initialSttOverride.baseEngine)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = courseText,
                    onValueChange = { courseText = it },
                    label = { Text(text = stringResource(R.string.library_edit_course_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                LanguagePairDropdownSection(
                    targetLangCode = targetLangText,
                    baseLangCode = baseLangText,
                    onTargetLangChange = { targetLangText = it },
                    onBaseLangChange = { baseLangText = it },
                    targetSttEngine = targetSttEngine,
                    baseSttEngine = baseSttEngine,
                    onTargetSttEngineChange = { targetSttEngine = it },
                    onBaseSttEngineChange = { baseSttEngine = it },
                    sttEngineByLanguage = sttEngineByLanguage,
                    enabledLanguages = enabledLanguages,
                    onToggleEnabledLanguage = onToggleEnabledLanguage,
                    onOpenLanguageSettings = onOpenLanguageSettings,
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

                Text(
                    text = stringResource(R.string.library_edit_transcript_section_title),
                    style = MaterialTheme.typography.titleSmall,
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
                    Text(text = stringResource(R.string.library_edit_import_transcript_button))
                }

                if (onDeleteLocalAudioCopy != null) {
                    OutlinedButton(
                        onClick = onDeleteLocalAudioCopy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            imageVector = ChunkFlowIcons.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = stringResource(R.string.library_edit_delete_local_audio_copy_button))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cleanTarget = normalizeDialogLanguageCode(targetLangText).ifBlank { "en" }
                    val cleanBase = normalizeDialogLanguageCode(baseLangText).ifBlank { null }
                    onConfirm(
                        courseText.trim().ifBlank { null },
                        LanguagePair(
                            target = LanguageTag(cleanTarget),
                            base = cleanBase?.let { LanguageTag(it) },
                        ),
                        ItemSttOverride(
                            targetEngine = targetSttEngine,
                            baseEngine = baseSttEngine,
                        ),
                    )
                },
            ) {
                Text(text = stringResource(R.string.library_edit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.library_edit_cancel))
            }
        },
    )
}

@Composable
internal fun EditDirectoryMetadataDialog(
    directoryEdit: PendingDirectoryEdit,
    enabledLanguages: List<LanguageTag>,
    onConfirm: (String?, LanguagePair, ItemSttOverride) -> Unit,
    onDismiss: () -> Unit,
    sttEngineByLanguage: Map<LanguageTag, SttEngine> = emptyMap(),
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
    onOpenLanguageSettings: () -> Unit = {},
) {
    var courseText by remember(directoryEdit) {
        mutableStateOf(directoryEdit.currentCourse?.substringAfterLast('/').orEmpty())
    }
    var targetLangText by remember(directoryEdit) {
        mutableStateOf(normalizeDialogLanguageCode(directoryEdit.initialLanguages.target.value))
    }
    var baseLangText by remember(directoryEdit) {
        mutableStateOf(normalizeDialogLanguageCode(directoryEdit.initialLanguages.base?.value.orEmpty()))
    }
    var targetSttEngine by remember(directoryEdit) {
        mutableStateOf(directoryEdit.initialSttOverride.targetEngine)
    }
    var baseSttEngine by remember(directoryEdit) {
        mutableStateOf(directoryEdit.initialSttOverride.baseEngine)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = courseText,
                    onValueChange = { courseText = it },
                    label = { Text(text = stringResource(R.string.library_edit_course_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                LanguagePairDropdownSection(
                    targetLangCode = targetLangText,
                    baseLangCode = baseLangText,
                    onTargetLangChange = { targetLangText = it },
                    onBaseLangChange = { baseLangText = it },
                    targetSttEngine = targetSttEngine,
                    baseSttEngine = baseSttEngine,
                    onTargetSttEngineChange = { targetSttEngine = it },
                    onBaseSttEngineChange = { baseSttEngine = it },
                    sttEngineByLanguage = sttEngineByLanguage,
                    enabledLanguages = enabledLanguages,
                    onToggleEnabledLanguage = onToggleEnabledLanguage,
                    onOpenLanguageSettings = onOpenLanguageSettings,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanTarget = normalizeDialogLanguageCode(targetLangText).ifBlank { "en" }
                    val cleanBase = normalizeDialogLanguageCode(baseLangText).ifBlank { null }
                    onConfirm(
                        courseText.trim().ifBlank { null },
                        LanguagePair(
                            target = LanguageTag(cleanTarget),
                            base = cleanBase?.let { LanguageTag(it) },
                        ),
                        ItemSttOverride(
                            targetEngine = targetSttEngine,
                            baseEngine = baseSttEngine,
                        ),
                    )
                },
            ) {
                Text(text = stringResource(R.string.library_edit_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.library_edit_cancel))
            }
        },
    )
}

@ChunkFlowPreviews
@Composable
private fun EditDirectoryMetadataDialogPreview() {
    ChunkFlowTheme {
        Surface {
            EditDirectoryMetadataDialog(
                directoryEdit = PendingDirectoryEdit(
                    currentCourse = "ピンズラー韓国語 I",
                    initialLanguages = LanguagePair(
                        target = LanguageTag("ko"),
                        base = LanguageTag("ja"),
                    ),
                ),
                enabledLanguages = listOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ko")),
                onConfirm = { _, _, _ -> },
                onDismiss = {},
            )
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun EditItemMetadataDialogPreview() {
    ChunkFlowTheme {
        Surface {
            EditItemMetadataDialog(
                item = AudioItem(
                    id = MediaId("sample-1"),
                    title = "ピンズラー 韓国語 01",
                    course = "ピンズラー韓国語 I",
                    durationMs = 180_000L,
                    mimeType = "audio/mpeg",
                    sizeBytes = 1_000L,
                    contentSha256 = "0".repeat(64),
                    languagePair = LanguagePair(
                        target = LanguageTag("ko"),
                        base = LanguageTag("ja"),
                    ),
                    importedAtEpochMs = 0L,
                ),
                enabledLanguages = listOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ko")),
                onImportTranscript = {},
                onConfirm = { _, _, _ -> },
                onDismiss = {},
            )
        }
    }
}
