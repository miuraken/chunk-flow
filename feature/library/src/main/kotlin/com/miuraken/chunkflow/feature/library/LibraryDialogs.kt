package com.miuraken.chunkflow.feature.library

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.component.ALPHABETICAL_SELECTABLE_LANGUAGE_OPTIONS
import com.miuraken.chunkflow.core.designsystem.component.MISSING_LANGUAGE_HINT_LINK_SLOT
import com.miuraken.chunkflow.core.designsystem.component.MissingLanguageHintWithSettingsLink
import com.miuraken.chunkflow.core.designsystem.component.SelectableLanguageOption
import com.miuraken.chunkflow.core.designsystem.component.buildSelectableLanguageOptions
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowLoadingIndicator
import com.miuraken.chunkflow.core.designsystem.component.normalizeSelectableLanguageCode
import com.miuraken.chunkflow.core.designsystem.component.resolveConfiguredDefaultEngineForLanguage
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.DownloadableSttModel
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageSettings
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.LibraryFolderColors
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.SttEngineLabel
import com.miuraken.chunkflow.core.model.SttEngineSelection
import com.miuraken.chunkflow.core.model.SupportedLanguageCatalog

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
    confirmTargetLangChange: Boolean = false,
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

    val selectedTargetLabel = targetOptions.firstOrNull { it.code == normalizedTarget }?.label
        ?: normalizedTarget.ifBlank { "en" }
    val selectedBaseLabel = baseOptions.firstOrNull { it.code == normalizedBase }?.label
        ?: noneLabel

    var targetExpanded by remember { mutableStateOf(false) }
    var baseExpanded by remember { mutableStateOf(false) }
    var pendingTargetCode by remember { mutableStateOf<String?>(null) }

    pendingTargetCode?.let { pendingCode ->
        AlertDialog(
            onDismissRequest = { pendingTargetCode = null },
            title = { Text(text = stringResource(R.string.library_target_lang_change_confirm_title)) },
            text = { Text(text = stringResource(R.string.library_target_lang_change_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingTargetCode = null
                        onTargetLangChange(pendingCode)
                    },
                ) {
                    Text(text = stringResource(R.string.library_target_lang_change_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingTargetCode = null }) {
                    Text(text = stringResource(R.string.library_target_lang_change_confirm_cancel))
                }
            },
        )
    }

    Surface(
        color = langPalette.targetRowBackground,
        shape = MaterialTheme.shapes.medium,
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
                                if (confirmTargetLangChange && option.code != normalizedTarget) {
                                    pendingTargetCode = option.code
                                } else {
                                    onTargetLangChange(option.code)
                                }
                            },
                            contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                        )
                    }
                }
            }
        }
    }

    Surface(
        color = langPalette.baseRowBackground,
        shape = MaterialTheme.shapes.medium,
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
        }
    }

    MissingLanguageHintWithSettingsLink(
        sentence = stringResource(R.string.library_missing_language_hint_with_link, MISSING_LANGUAGE_HINT_LINK_SLOT),
        linkLabel = stringResource(R.string.library_open_global_language_settings),
        onOpenLanguageSettings = onOpenLanguageSettings,
    )
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
    val isFileImport = config.treeUri == null
    var courseText by remember(config) {
        mutableStateOf(
            if (isFileImport) {
                config.initialCourse?.takeIf { it.isNotBlank() }
                    ?: config.parentFolderName?.takeIf { it.isNotBlank() }
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
    var folderDropdownExpanded by remember { mutableStateOf(false) }
    var showNewVirtualFolderDialog by rememberSaveable { mutableStateOf(false) }
    var newVirtualFolderName by rememberSaveable { mutableStateOf("") }

    val folderOptions = remember(config, availableFolders, courseText) {
        buildList {
            val parentFolder = config.parentFolderName?.trim()?.takeIf { it.isNotBlank() }
            if (parentFolder != null) {
                add(parentFolder)
            }
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

    val dialogTitle = stringResource(R.string.library_import_config_title)
    val dialogHint = stringResource(R.string.library_import_config_hint, config.uris.size)
    val confirmLabel = stringResource(R.string.library_import_config_confirm)

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
                        ItemSttOverride.NONE,
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
                                ItemSttOverride.NONE,
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
                            ItemSttOverride.NONE,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditItemMetadataDialog(
    item: AudioItem,
    enabledLanguages: List<LanguageTag>,
    onConfirm: (course: String?, languages: LanguagePair, sttOverride: ItemSttOverride, tags: List<String>) -> Unit,
    onDismiss: () -> Unit,
    initialSttOverride: ItemSttOverride = ItemSttOverride.NONE,
    sttEngineByLanguage: Map<LanguageTag, SttEngine> = emptyMap(),
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
    onOpenLanguageSettings: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
    initialTags: List<String> = emptyList(),
    availableTags: List<String> = emptyList(),
) {
    var courseText by remember(item) { mutableStateOf(item.course.orEmpty()) }
    var tags by remember(item) { mutableStateOf(initialTags) }
    var tagInput by remember(item) { mutableStateOf("") }
    var tagInputError by remember(item) { mutableStateOf<TagInputResult?>(null) }
    var targetLangText by remember(item) {
        mutableStateOf(normalizeDialogLanguageCode(item.languagePair.target.value))
    }
    var baseLangText by remember(item) {
        mutableStateOf(normalizeDialogLanguageCode(item.languagePair.base?.value.orEmpty()))
    }

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
                sttEngineByLanguage = sttEngineByLanguage,
                enabledLanguages = enabledLanguages,
                onToggleEnabledLanguage = onToggleEnabledLanguage,
                onOpenLanguageSettings = onOpenLanguageSettings,
                confirmTargetLangChange = true,
            )

            ItemTagEditor(
                tags = tags,
                availableTags = availableTags,
                input = tagInput,
                inputError = tagInputError,
                onInputChange = {
                    tagInput = it
                    tagInputError = null
                },
                onSubmitInput = {
                    if (tagInput.isNotBlank()) {
                        when (val result = addTagFromInput(tags, tagInput, availableTags)) {
                            is TagInputResult.Added -> {
                                tags = result.tags
                                tagInput = ""
                                tagInputError = null
                            }

                            else -> tagInputError = result
                        }
                    }
                },
                onTagsChange = {
                    tags = it
                    tagInputError = null
                },
            )

            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.library_edit_cancel))
                }
                Button(
                    onClick = {
                        val cleanTarget = normalizeDialogLanguageCode(targetLangText).ifBlank { "en" }
                        val cleanBase = normalizeDialogLanguageCode(baseLangText).ifBlank { null }
                        // 入力途中のタグも保存する。正しくなければ保存せず、入力欄に理由を出す
                        when (val tagResult = resolveTagsForSave(tags, tagInput, availableTags)) {
                            is TagInputResult.Added -> onConfirm(
                                courseText.trim().ifBlank { null },
                                LanguagePair(
                                    target = LanguageTag(cleanTarget),
                                    base = cleanBase?.let { LanguageTag(it) },
                                ),
                                ItemSttOverride.NONE,
                                tagResult.tags,
                            )

                            else -> tagInputError = tagResult
                        }
                    },
                ) {
                    Text(text = stringResource(R.string.library_edit_save))
                }
            }

            if (onDelete != null) {
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                DeleteFromLibraryButton(
                    label = stringResource(R.string.library_delete_file_button),
                    onClick = onDelete,
                )
            }
        }
    }

    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            content()
        }
    } else {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
            ),
        ) {
            content()
        }
    }
}

/**
 * 設定のシートの下部に置く「ライブラリから削除」のボタン（ファイル・フォルダ共通）。押すと確認ダイアログを出す。
 */
@Composable
private fun DeleteFromLibraryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
        ),
    ) {
        Icon(
            imageVector = ChunkFlowIcons.Delete,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label)
    }
}

/**
 * フォルダの設定のシート（フォルダ名・色・言語ペア・音声認識モデルをフォルダ単位で設定する）。
 *
 * 上から、見出し（Tune のアイコンとフォルダ名の入力欄。仮想フォルダには「仮想」のバッジ）→ 色 → 言語（学習対象・ベース）と
 * 案内文 → 音声認識モデルの管理 → キャンセル / 保存 → フォルダを削除 の順に並べる。
 *
 * @param initialColorsExpanded 色の欄をはじめから全色に広げておくかどうか（プレビュー用）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditDirectoryMetadataDialog(
    directoryEdit: PendingDirectoryEdit,
    enabledLanguages: List<LanguageTag>,
    onConfirm: (
        newCourse: String?,
        languages: LanguagePair,
        sttOverride: ItemSttOverride,
        colorChoice: LibraryFolderColorChoice,
    ) -> Unit,
    onDismiss: () -> Unit,
    sttEngineByLanguage: Map<LanguageTag, SttEngine> = emptyMap(),
    languageModelInfos: Map<LanguageTag, OnDeviceLanguageModelInfo> = emptyMap(),
    onDownloadSttModel: (LanguageTag, SttEngine) -> Unit = { _, _ -> },
    onDeleteSherpaModel: (LanguageTag) -> Unit = {},
    onDeleteLanguageSttModel: (LanguageTag, DownloadableSttModel) -> Unit = { _, _ -> },
    onPurgeDownloadableModel: (DownloadableSttModel) -> Unit = {},
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
    onOpenLanguageSettings: () -> Unit = {},
    onDelete: (() -> Unit)? = null,
    languageColorOrdinals: Map<LanguageTag, Int> = emptyMap(),
    initialColorsExpanded: Boolean = false,
) {
    var colorIndex by remember(directoryEdit) { mutableStateOf(directoryEdit.colorIndex) }
    var courseText by remember(directoryEdit) {
        mutableStateOf(directoryEdit.currentCourse?.substringAfterLast('/').orEmpty())
    }
    var targetLangText by remember(directoryEdit) {
        mutableStateOf(normalizeDialogLanguageCode(directoryEdit.initialLanguages.target.value))
    }
    var baseLangText by remember(directoryEdit) {
        mutableStateOf(normalizeDialogLanguageCode(directoryEdit.initialLanguages.base?.value.orEmpty()))
    }
    var useGlobalSttModels by remember(directoryEdit) {
        mutableStateOf(directoryEdit.initialSttOverride.isEmpty)
    }
    var targetSttEngine by remember(directoryEdit) {
        mutableStateOf(directoryEdit.initialSttOverride.targetEngine)
    }
    var baseSttEngine by remember(directoryEdit) {
        mutableStateOf(directoryEdit.initialSttOverride.baseEngine)
    }

    val isVirtualFolder = directoryEdit.currentCourse != null && !directoryEdit.isRealFolder

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 色の欄は左右の余白へ少しはみ出して描くので、余白はスクロールの内側に取る（切り抜かれないように）
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FolderSettingsHeader(
                folderName = courseText,
                onFolderNameChange = { courseText = it },
                isVirtualFolder = isVirtualFolder,
            )

            if (directoryEdit.currentCourse != null) {
                // 「デフォルト」の色は、シートで選んでいる対象言語の色（ADR 0013）
                val defaultColorIndex = LibraryFolderColors.resolveIndex(
                    colorIndex = null,
                    targetLanguage = normalizeDialogLanguageCode(targetLangText)
                        .takeIf { it.isNotBlank() }
                        ?.let { LanguageTag(it) },
                    languageOrdinals = languageColorOrdinals,
                )
                FolderColorPicker(
                    selectedIndex = colorIndex,
                    defaultIndex = defaultColorIndex,
                    onSelect = { colorIndex = it },
                    initialExpanded = initialColorsExpanded,
                )
            }

            LanguagePairDropdownSection(
                targetLangCode = targetLangText,
                baseLangCode = baseLangText,
                onTargetLangChange = { targetLangText = it },
                onBaseLangChange = { baseLangText = it },
                sttEngineByLanguage = sttEngineByLanguage,
                enabledLanguages = enabledLanguages,
                onToggleEnabledLanguage = onToggleEnabledLanguage,
                onOpenLanguageSettings = onOpenLanguageSettings,
                confirmTargetLangChange = true,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))

            FolderSttModelManagementSection(
                targetLangCode = targetLangText,
                baseLangCode = baseLangText,
                useGlobalSttModels = useGlobalSttModels,
                onToggleUseGlobalSttModels = { checked ->
                    useGlobalSttModels = checked
                    if (checked) {
                        targetSttEngine = null
                        baseSttEngine = null
                    }
                },
                targetEngine = targetSttEngine,
                baseEngine = baseSttEngine,
                onTargetEngineChange = { targetSttEngine = it },
                onBaseEngineChange = { baseSttEngine = it },
                sttEngineByLanguage = sttEngineByLanguage,
                languageModelInfos = languageModelInfos,
                onDownloadSttModel = onDownloadSttModel,
                onDeleteSherpaModel = onDeleteSherpaModel,
                onDeleteLanguageSttModel = onDeleteLanguageSttModel,
                onPurgeDownloadableModel = onPurgeDownloadableModel,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.library_edit_cancel))
                }
                Button(
                    onClick = {
                        val cleanTarget = normalizeDialogLanguageCode(targetLangText).ifBlank { "en" }
                        val cleanBase = normalizeDialogLanguageCode(baseLangText).ifBlank { null }
                        val nextCourse = courseText.trim().ifBlank {
                            if (directoryEdit.isRealFolder) directoryEdit.currentCourse else null
                        }
                        val sttOverride = if (useGlobalSttModels) {
                            ItemSttOverride.NONE
                        } else {
                            ItemSttOverride(
                                targetEngine = targetSttEngine,
                                baseEngine = if (cleanBase != null) baseSttEngine else null,
                            )
                        }
                        onConfirm(
                            nextCourse,
                            LanguagePair(
                                target = LanguageTag(cleanTarget),
                                base = cleanBase?.let { LanguageTag(it) },
                            ),
                            sttOverride,
                            LibraryFolderColorChoice.of(initial = directoryEdit.colorIndex, selected = colorIndex),
                        )
                    },
                ) {
                    Text(text = stringResource(R.string.library_edit_save))
                }
            }

            if (onDelete != null && directoryEdit.currentCourse != null) {
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                DeleteFromLibraryButton(
                    label = stringResource(R.string.library_delete_folder_button),
                    onClick = onDelete,
                )
            }
        }
    }

    if (androidx.compose.ui.platform.LocalInspectionMode.current) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            content()
        }
    } else {
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
            ),
        ) {
            content()
        }
    }
}

/**
 * フォルダの設定のシートの見出し。Tune のアイコン（飾り）と、フォルダ名を編集する入力欄（ラベルなし・1 行）を並べ、
 * 仮想フォルダ（[isVirtualFolder]）なら入力欄の右に「仮想」のバッジを出す。
 *
 * 入力欄には見えるラベルがないので、読み上げ用の名前（「フォルダ名」）を付ける。空のときだけ同じ文言を placeholder に出す。
 */
@Composable
private fun FolderSettingsHeader(
    folderName: String,
    onFolderNameChange: (String) -> Unit,
    isVirtualFolder: Boolean,
    modifier: Modifier = Modifier,
) {
    val folderNameLabel = stringResource(R.string.library_folder_settings_name_placeholder)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = ChunkFlowIcons.Tune,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        OutlinedTextField(
            value = folderName,
            onValueChange = onFolderNameChange,
            singleLine = true,
            placeholder = { Text(text = folderNameLabel) },
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = folderNameLabel },
        )
        FolderKindBadge(isRealFolder = !isVirtualFolder)
    }
}

private const val DEFAULT_FOLDER_SHERPA_TINY_SIZE_MB = 113

private fun resolveFolderSherpaTinySizeMb(sizeBytes: Long): Int {
    if (sizeBytes <= 0L) return DEFAULT_FOLDER_SHERPA_TINY_SIZE_MB
    return ((sizeBytes + 512L * 1024L) / (1024L * 1024L)).toInt().coerceAtLeast(1)
}

internal fun resolveLanguageModelInfoForFolder(
    language: LanguageTag,
    languageModelInfos: Map<LanguageTag, OnDeviceLanguageModelInfo>,
): OnDeviceLanguageModelInfo {
    val normLang = LanguageSettings.normalize(language)
    val mgmtLang = LanguageTag(SupportedLanguageCatalog.normalizeToModelManagementCode(language.value))
    val raw = languageModelInfos[language]
        ?: languageModelInfos[normLang]
        ?: languageModelInfos[mgmtLang]
        ?: OnDeviceLanguageModelInfo(language = language)
    return raw.enrichedWithCatalog(language)
}

internal fun isFolderSttEngineSelectable(
    info: OnDeviceLanguageModelInfo,
    engine: SttEngine?,
): Boolean {
    if (!SttEngineSelection.isAnyEngineSupported(info)) return false
    if (engine == null) return true
    return info.statusOf(engine) != OnDeviceLanguageModelStatus.UNSUPPORTED
}

/**
 * 音声認識モデルのアコーディオンの見出しに出す言語名（例: `ヒンディー語 (hi)`）。
 *
 * ファイルの設定（プレイヤーのファイル個別設定）の見出しと同じ書き方にする。カタログにない言語はコードだけを返す。
 */
private fun formatFolderLanguageDisplayName(tag: LanguageTag): String {
    val entry = SupportedLanguageCatalog.findByCode(tag.value) ?: return tag.value
    return "${entry.japaneseName} (${tag.value})"
}

@Composable
internal fun FolderSttModelManagementSection(
    targetLangCode: String,
    baseLangCode: String,
    useGlobalSttModels: Boolean,
    onToggleUseGlobalSttModels: (Boolean) -> Unit,
    targetEngine: SttEngine?,
    baseEngine: SttEngine?,
    onTargetEngineChange: (SttEngine?) -> Unit,
    onBaseEngineChange: (SttEngine?) -> Unit,
    sttEngineByLanguage: Map<LanguageTag, SttEngine>,
    languageModelInfos: Map<LanguageTag, OnDeviceLanguageModelInfo>,
    onDownloadSttModel: (LanguageTag, SttEngine) -> Unit = { _, _ -> },
    onDeleteSherpaModel: (LanguageTag) -> Unit = {},
    onDeleteLanguageSttModel: (LanguageTag, DownloadableSttModel) -> Unit = { _, _ -> },
    onPurgeDownloadableModel: (DownloadableSttModel) -> Unit = {},
) {
    Text(
        text = stringResource(R.string.library_folder_settings_section_speech_models),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleUseGlobalSttModels(!useGlobalSttModels) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = useGlobalSttModels,
            onCheckedChange = { checked -> onToggleUseGlobalSttModels(checked) },
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.library_folder_settings_use_global),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }

    val sttAccordionEnabled = !useGlobalSttModels
    val cleanTarget = normalizeDialogLanguageCode(targetLangCode).ifBlank { "en" }
    val targetLang = LanguageTag(cleanTarget)
    val targetInfo = resolveLanguageModelInfoForFolder(targetLang, languageModelInfos)
    val targetGlobalDefault = resolveConfiguredDefaultEngineForLanguage(
        rawCode = targetLang.value,
        sttEngineByLanguage = sttEngineByLanguage,
        languageModelInfos = languageModelInfos,
    )
    FolderSttEngineAccordion(
        headerTitle = stringResource(
            R.string.library_folder_settings_stt_target_lang_header,
            formatFolderLanguageDisplayName(targetLang),
        ),
        info = targetInfo,
        globalDefaultEngine = targetGlobalDefault,
        preferredEngine = if (useGlobalSttModels) null else targetEngine,
        enabled = sttAccordionEnabled,
        onPreferredEngineChange = onTargetEngineChange,
        onDownloadLanguageModel = { engine -> onDownloadSttModel(targetLang, engine) },
        onDeleteSherpaModel = { onDeleteSherpaModel(targetLang) },
        onDeleteLanguageSttModel = { model -> onDeleteLanguageSttModel(targetLang, model) },
        onPurgeDownloadableModel = onPurgeDownloadableModel,
    )

    val cleanBase = normalizeDialogLanguageCode(baseLangCode).ifBlank { null }
    if (cleanBase != null) {
        val baseLang = LanguageTag(cleanBase)
        val baseInfo = resolveLanguageModelInfoForFolder(baseLang, languageModelInfos)
        val baseGlobalDefault = resolveConfiguredDefaultEngineForLanguage(
            rawCode = baseLang.value,
            sttEngineByLanguage = sttEngineByLanguage,
            languageModelInfos = languageModelInfos,
        )
        FolderSttEngineAccordion(
            headerTitle = stringResource(
                R.string.library_folder_settings_stt_base_lang_header,
                formatFolderLanguageDisplayName(baseLang),
            ),
            info = baseInfo,
            globalDefaultEngine = baseGlobalDefault,
            preferredEngine = if (useGlobalSttModels) null else baseEngine,
            enabled = sttAccordionEnabled,
            onPreferredEngineChange = onBaseEngineChange,
            onDownloadLanguageModel = { engine -> onDownloadSttModel(baseLang, engine) },
            onDeleteSherpaModel = { onDeleteSherpaModel(baseLang) },
            onDeleteLanguageSttModel = { model -> onDeleteLanguageSttModel(baseLang, model) },
            onPurgeDownloadableModel = onPurgeDownloadableModel,
        )
    }
}

@Composable
private fun FolderSttEngineAccordion(
    headerTitle: String,
    info: OnDeviceLanguageModelInfo,
    globalDefaultEngine: SttEngine,
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
    val enrichedInfo = info.enrichedWithCatalog(info.language)
    val anyEngineSupported = SttEngineSelection.isAnyEngineSupported(enrichedInfo)
    val followGlobalLabel = if (anyEngineSupported) {
        stringResource(
            R.string.library_folder_settings_stt_follow_global_with_resolved,
            folderSttEngineDisplayName(globalDefaultEngine),
        )
    } else {
        "${stringResource(R.string.library_folder_settings_use_global)} (${stringResource(R.string.library_folder_settings_stt_status_unsupported)})"
    }
    val effectivePreferred = preferredEngine?.takeIf { isFolderSttEngineSelectable(enrichedInfo, it) }
    val currentEngineLabel = when {
        !anyEngineSupported -> stringResource(R.string.library_folder_settings_stt_status_unsupported)
        effectivePreferred == null -> followGlobalLabel
        else -> folderSttEngineDisplayName(effectivePreferred)
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
                    text = stringResource(R.string.library_folder_settings_stt_current_engine, currentEngineLabel),
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
                    text = stringResource(R.string.library_folder_settings_stt_priority_engine),
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
                        add(engine to formatFolderSttEngineOptionLabel(enrichedInfo, engine))
                    }
                }

                options.forEach { (engine, label) ->
                    val isOptionEnabled = enabled && isFolderSttEngineSelectable(enrichedInfo, engine)
                    val isSelected = anyEngineSupported && effectivePreferred == engine
                    val engineStatus = engine?.let { enrichedInfo.statusOf(it) }
                    val downloadableModel = engine?.let { DownloadableSttModel.fromEngineOrNull(it) }
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
                                    resolveFolderSherpaTinySizeMb(enrichedInfo.sherpaModelSizeBytes)
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
                                            R.string.library_folder_settings_stt_action_download_with_size,
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
                                        text = stringResource(R.string.library_folder_settings_stt_action_download),
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
                                            R.string.library_folder_settings_stt_action_delete,
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
private fun folderSttEngineDisplayName(engine: SttEngine): String = when (engine) {
    SttEngine.ML_KIT -> stringResource(R.string.library_stt_engine_mlkit)
    SttEngine.SPEECH_RECOGNIZER -> stringResource(R.string.library_folder_settings_stt_engine_speech_recognizer)
    SttEngine.SHERPA_ONNX -> stringResource(R.string.library_folder_settings_stt_engine_sherpa_onnx)
    SttEngine.SHERPA_ONNX_SENSE_VOICE -> stringResource(R.string.library_folder_settings_stt_engine_sherpa_sense_voice)
    SttEngine.SHERPA_ONNX_WHISPER_BASE -> stringResource(R.string.library_folder_settings_stt_engine_sherpa_whisper_base)
    SttEngine.SHERPA_ONNX_WHISPER_SMALL -> stringResource(R.string.library_folder_settings_stt_engine_sherpa_whisper_small)
    SttEngine.MOONSHINE_TINY -> stringResource(R.string.library_folder_settings_stt_engine_moonshine_tiny)
    SttEngine.MOONSHINE_BASE -> stringResource(R.string.library_folder_settings_stt_engine_moonshine_base)
    SttEngine.OPENAI_API -> stringResource(R.string.library_folder_settings_stt_engine_openai_api)
    SttEngine.GROQ_API -> stringResource(R.string.library_folder_settings_stt_engine_groq_api)
    SttEngine.GEMINI_API -> stringResource(R.string.library_folder_settings_stt_engine_gemini_api)
}

@Composable
private fun folderSttEngineLabelText(label: SttEngineLabel): String = when (label) {
    SttEngineLabel.RECOMMENDED -> stringResource(R.string.library_stt_label_recommended)
    SttEngineLabel.HIGH_ACCURACY -> stringResource(R.string.library_stt_label_high_accuracy)
    SttEngineLabel.FAST -> stringResource(R.string.library_stt_label_fast)
    SttEngineLabel.SLOW -> stringResource(R.string.library_stt_label_slow)
}

@Composable
private fun formatFolderSttEngineOptionLabel(
    enrichedInfo: OnDeviceLanguageModelInfo,
    engine: SttEngine,
): String {
    val status = enrichedInfo.statusOf(engine)
    val rawLabel = when (engine) {
        SttEngine.ML_KIT -> formatFolderEngineOptionWithStatus(
            baseName = stringResource(R.string.library_folder_settings_stt_engine_mlkit_radio),
            status = status,
        )

        SttEngine.SPEECH_RECOGNIZER -> formatFolderEngineOptionWithStatus(
            baseName = stringResource(R.string.library_folder_settings_stt_engine_speech_recognizer),
            status = status,
        )

        SttEngine.SHERPA_ONNX -> {
            val baseName = stringResource(R.string.library_folder_settings_stt_engine_sherpa_onnx)
            val sizeMb = resolveFolderSherpaTinySizeMb(enrichedInfo.sherpaModelSizeBytes)
            when (status) {
                OnDeviceLanguageModelStatus.INSTALLED ->
                    stringResource(R.string.library_folder_settings_stt_engine_sherpa_downloaded, sizeMb)

                OnDeviceLanguageModelStatus.NOT_INSTALLED -> baseName

                else -> formatFolderEngineOptionWithStatus(
                    baseName = baseName,
                    status = status,
                )
            }
        }

        SttEngine.MOONSHINE_TINY,
        SttEngine.MOONSHINE_BASE,
        -> {
            val baseName = folderSttEngineDisplayName(engine)
            val sizeMb = DownloadableSttModel.fromEngineOrNull(engine)?.approxSizeMbForLanguage(enrichedInfo.language) ?: 100
            when (status) {
                OnDeviceLanguageModelStatus.INSTALLED ->
                    stringResource(R.string.library_folder_settings_stt_engine_model_downloaded_format, baseName, sizeMb)

                OnDeviceLanguageModelStatus.NOT_INSTALLED -> baseName

                else -> formatFolderEngineOptionWithStatus(
                    baseName = baseName,
                    status = status,
                )
            }
        }

        SttEngine.SHERPA_ONNX_SENSE_VOICE,
        SttEngine.SHERPA_ONNX_WHISPER_BASE,
        SttEngine.SHERPA_ONNX_WHISPER_SMALL,
        -> {
            val baseName = folderSttEngineDisplayName(engine)
            val sizeMb = DownloadableSttModel.fromEngineOrNull(engine)?.estimatedSizeMb ?: 100
            when (status) {
                OnDeviceLanguageModelStatus.INSTALLED ->
                    stringResource(R.string.library_folder_settings_stt_engine_model_downloaded_format, baseName, sizeMb)

                OnDeviceLanguageModelStatus.NOT_INSTALLED -> baseName

                else -> formatFolderEngineOptionWithStatus(
                    baseName = baseName,
                    status = status,
                )
            }
        }

        SttEngine.OPENAI_API,
        SttEngine.GROQ_API,
        SttEngine.GEMINI_API,
        -> {
            val baseName = folderSttEngineDisplayName(engine)
            when (status) {
                OnDeviceLanguageModelStatus.INSTALLED -> baseName

                OnDeviceLanguageModelStatus.NOT_INSTALLED ->
                    "$baseName (${stringResource(R.string.library_folder_settings_stt_status_api_key_not_configured)})"

                else -> formatFolderEngineOptionWithStatus(baseName = baseName, status = status)
            }
        }
    }
    val labels = SttEngineSelection.labelsFor(enrichedInfo.language, engine)
    if (labels.isEmpty()) {
        return rawLabel
    }
    val badgeText = labels.map { folderSttEngineLabelText(it) }.joinToString("・")
    return "$rawLabel [$badgeText]"
}

@Composable
private fun formatFolderEngineOptionWithStatus(
    baseName: String,
    status: OnDeviceLanguageModelStatus,
): String = when (status) {
    OnDeviceLanguageModelStatus.INSTALLED -> baseName

    OnDeviceLanguageModelStatus.NOT_INSTALLED ->
        "$baseName (${stringResource(R.string.library_folder_settings_stt_status_not_installed)})"

    OnDeviceLanguageModelStatus.DOWNLOADING ->
        "$baseName (${stringResource(R.string.library_folder_settings_stt_status_downloading)})"

    OnDeviceLanguageModelStatus.DOWNLOAD_FAILED ->
        "$baseName (${stringResource(R.string.library_folder_settings_stt_status_download_failed)})"

    OnDeviceLanguageModelStatus.CHECKING ->
        "$baseName (${stringResource(R.string.library_folder_settings_stt_status_checking)})"

    OnDeviceLanguageModelStatus.UNSUPPORTED ->
        "$baseName (${stringResource(R.string.library_folder_settings_stt_status_unsupported)})"
}

@Composable
internal fun SttRecommendationUpdateDialog(
    changes: List<com.miuraken.chunkflow.core.model.SttEngineRecommendationChange>,
    onApplyRecommendedAuto: () -> Unit,
    onKeepCurrent: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeepCurrent,
        title = {
            Text(
                text = stringResource(R.string.library_stt_recommendation_update_title),
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.library_stt_recommendation_update_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (change in changes) {
                    val normCode = normalizeDialogLanguageCode(change.language.value)
                    val langName = ALPHABETICAL_LANGUAGE_OPTIONS.firstOrNull { it.code == normCode }?.label
                        ?: change.language.value
                    Text(
                        text = stringResource(
                            R.string.library_stt_recommendation_update_item_format,
                            langName,
                            sttEngineShortNameForLibrary(change.currentSelectedEngine),
                            sttEngineShortNameForLibrary(change.recommendedEngine),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onApplyRecommendedAuto) {
                Text(text = stringResource(R.string.library_stt_recommendation_update_switch_to_auto))
            }
        },
        dismissButton = {
            TextButton(onClick = onKeepCurrent) {
                Text(text = stringResource(R.string.library_stt_recommendation_update_keep_current))
            }
        },
    )
}

@Composable
private fun sttEngineShortNameForLibrary(engine: SttEngine): String = when (engine) {
    SttEngine.ML_KIT -> stringResource(R.string.library_stt_engine_mlkit)
    SttEngine.SPEECH_RECOGNIZER -> stringResource(R.string.library_stt_engine_speech_recognizer)
    SttEngine.SHERPA_ONNX -> stringResource(R.string.library_stt_engine_sherpa_onnx)
    SttEngine.SHERPA_ONNX_WHISPER_BASE -> stringResource(R.string.library_stt_engine_sherpa_whisper_base)
    SttEngine.SHERPA_ONNX_WHISPER_SMALL -> stringResource(R.string.library_stt_engine_sherpa_whisper_small)
    SttEngine.SHERPA_ONNX_SENSE_VOICE -> stringResource(R.string.library_stt_engine_sherpa_sense_voice)
    SttEngine.MOONSHINE_TINY -> stringResource(R.string.library_stt_engine_moonshine_tiny)
    SttEngine.MOONSHINE_BASE -> stringResource(R.string.library_stt_engine_moonshine_base)
    SttEngine.OPENAI_API -> stringResource(R.string.library_stt_engine_openai_api)
    SttEngine.GROQ_API -> stringResource(R.string.library_stt_engine_groq_api)
    SttEngine.GEMINI_API -> stringResource(R.string.library_stt_engine_gemini_api)
}

/** 実フォルダ（色はデフォルトのまま）のフォルダの設定のシート。 */
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
                    isRealFolder = true,
                ),
                enabledLanguages = listOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ko")),
                onConfirm = { _, _, _, _ -> },
                languageColorOrdinals = mapOf(LanguageTag("ko") to 0, LanguageTag("en") to 1),
                onDismiss = {},
                onDelete = {},
            )
        }
    }
}

/**
 * 仮想フォルダ（「仮想」のバッジ）で、ふだん隠れている行の色（パレットの番号 9）を選んでいるフォルダの設定のシート。
 * 選んだ色が左上に来て、デフォルトの色（番号 0）にグレーのチェックが付く。
 */
@ChunkFlowPreviews
@Composable
private fun EditDirectoryMetadataDialogVirtualFolderPreview() {
    ChunkFlowTheme {
        Surface {
            EditDirectoryMetadataDialog(
                directoryEdit = PendingDirectoryEdit(
                    currentCourse = "Hindi_1",
                    initialLanguages = LanguagePair(
                        target = LanguageTag("hi"),
                        base = LanguageTag("en"),
                    ),
                    isRealFolder = false,
                    colorIndex = 9,
                ),
                enabledLanguages = listOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("hi")),
                onConfirm = { _, _, _, _ -> },
                languageColorOrdinals = mapOf(LanguageTag("hi") to 0, LanguageTag("ko") to 1),
                onDismiss = {},
                onDelete = {},
            )
        }
    }
}

/** 色の欄を ▼ で 18 色（6 色 × 3 行）に広げたフォルダの設定のシート。 */
@ChunkFlowPreviews
@Composable
private fun EditDirectoryMetadataDialogColorsExpandedPreview() {
    ChunkFlowTheme {
        Surface {
            EditDirectoryMetadataDialog(
                directoryEdit = PendingDirectoryEdit(
                    currentCourse = "Hindi_1",
                    initialLanguages = LanguagePair(
                        target = LanguageTag("hi"),
                        base = LanguageTag("en"),
                    ),
                    isRealFolder = false,
                    colorIndex = 9,
                ),
                enabledLanguages = listOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("hi")),
                onConfirm = { _, _, _, _ -> },
                languageColorOrdinals = mapOf(LanguageTag("hi") to 0, LanguageTag("ko") to 1),
                onDismiss = {},
                onDelete = {},
                initialColorsExpanded = true,
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
                onConfirm = { _, _, _, _ -> },
                onDismiss = {},
                onDelete = {},
                initialTags = listOf("N5", "シャドーイング"),
                availableTags = listOf("N5", "シャドーイング", "旅行", "復習"),
            )
        }
    }
}
