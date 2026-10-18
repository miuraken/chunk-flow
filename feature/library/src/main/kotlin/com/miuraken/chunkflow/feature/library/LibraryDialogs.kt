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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.component.ALPHABETICAL_SELECTABLE_LANGUAGE_OPTIONS
import com.miuraken.chunkflow.core.designsystem.component.LanguageSettingsModalDialog
import com.miuraken.chunkflow.core.designsystem.component.MissingLanguageHintWithModalLink
import com.miuraken.chunkflow.core.designsystem.component.SelectableLanguageOption
import com.miuraken.chunkflow.core.designsystem.component.buildSelectableLanguageOptions
import com.miuraken.chunkflow.core.designsystem.component.normalizeSelectableLanguageCode
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag

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
    enabledLanguages: List<LanguageTag> = emptyList(),
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
) {
    var showLanguageSettingsModal by rememberSaveable { mutableStateOf(false) }
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

    Surface(
        color = langPalette.targetRowBackground,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
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

    MissingLanguageHintWithModalLink(
        onOpenLanguageSettingsModal = { showLanguageSettingsModal = true },
    )

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
internal fun ImportConfigDialog(
    config: PendingImportConfig,
    enabledLanguages: List<LanguageTag>,
    onConfirm: (String?, LanguagePair) -> Unit,
    onDismiss: () -> Unit,
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
) {
    var courseText by remember(config) { mutableStateOf(config.initialCourse.orEmpty()) }
    var targetLangText by remember(config) {
        mutableStateOf(normalizeDialogLanguageCode(config.initialLanguages.target.value))
    }
    var baseLangText by remember(config) {
        mutableStateOf(normalizeDialogLanguageCode(config.initialLanguages.base?.value.orEmpty()))
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
                    enabledLanguages = enabledLanguages,
                    onToggleEnabledLanguage = onToggleEnabledLanguage,
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
}

@Composable
internal fun CreateVirtualFolderDialog(
    enabledLanguages: List<LanguageTag>,
    onConfirm: (String, LanguagePair) -> Unit,
    onDismiss: () -> Unit,
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
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
                    enabledLanguages = enabledLanguages,
                    onToggleEnabledLanguage = onToggleEnabledLanguage,
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
    onConfirm: (String?, LanguagePair) -> Unit,
    onDismiss: () -> Unit,
    onDeleteLocalAudioCopy: (() -> Unit)? = null,
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
) {
    var courseText by remember(item) { mutableStateOf(item.course.orEmpty()) }
    var targetLangText by remember(item) {
        mutableStateOf(normalizeDialogLanguageCode(item.languagePair.target.value))
    }
    var baseLangText by remember(item) {
        mutableStateOf(normalizeDialogLanguageCode(item.languagePair.base?.value.orEmpty()))
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
                    enabledLanguages = enabledLanguages,
                    onToggleEnabledLanguage = onToggleEnabledLanguage,
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
    onConfirm: (String?, LanguagePair) -> Unit,
    onDismiss: () -> Unit,
    onToggleEnabledLanguage: (LanguageTag) -> Unit = {},
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
                    enabledLanguages = enabledLanguages,
                    onToggleEnabledLanguage = onToggleEnabledLanguage,
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
