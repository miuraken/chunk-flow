package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.data.library.ImportError
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.model.MediaId

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ImportStatusBanner(
    importState: LibraryImportUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenItem: (MediaId) -> Unit = {},
) {
    when (importState) {
        LibraryImportUiState.Idle -> Unit

        is LibraryImportUiState.Copying -> {
            Surface(
                modifier = modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.library_importing),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    val fraction = importState.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        is LibraryImportUiState.Done -> {
            val folderName = importState.targetFolderName?.takeIf { it.isNotBlank() }
            if (importState.importedFiles.isNotEmpty() && folderName != null) {
                Card(
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FlowRow(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            importState.importedFiles.forEachIndexed { index, importedFile ->
                                Text(
                                    text = importedFile.fileName,
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                    modifier = Modifier
                                        .clip(MaterialTheme.shapes.extraSmall)
                                        .clickable { onOpenItem(importedFile.mediaId) }
                                        .padding(vertical = 2.dp),
                                )
                                if (index < importState.importedFiles.lastIndex) {
                                    Text(
                                        text = "、",
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(vertical = 2.dp),
                                    )
                                }
                            }
                            Text(
                                text = stringResource(
                                    R.string.library_import_done_into_folder_suffix,
                                    folderName,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = ChunkFlowIcons.Close,
                                contentDescription = stringResource(R.string.library_dismiss_banner),
                            )
                        }
                    }
                }
            } else {
                val doneMessage = if (importState.transcriptsAttached > 0) {
                    stringResource(
                        R.string.library_import_done_with_transcripts,
                        importState.addedCount,
                        importState.duplicates,
                        importState.transcriptsAttached,
                    )
                } else {
                    stringResource(
                        R.string.library_import_done,
                        importState.addedCount,
                        importState.duplicates,
                    )
                }
                StatusBannerCard(
                    message = doneMessage,
                    isError = false,
                    onDismiss = onDismiss,
                    modifier = modifier,
                )
            }
        }

        is LibraryImportUiState.Error -> {
            val errorText = when (importState.error) {
                ImportError.UNSUPPORTED_FORMAT -> stringResource(R.string.library_import_error_unsupported)
                ImportError.TOO_LARGE -> stringResource(R.string.library_import_error_too_large)
                ImportError.NO_SPACE -> stringResource(R.string.library_import_error_no_space)
                ImportError.IO -> stringResource(R.string.library_import_error_io)
                ImportError.CANCELLED -> stringResource(R.string.library_import_error_cancelled)
            }
            StatusBannerCard(
                message = errorText,
                isError = true,
                onDismiss = onDismiss,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun StatusBannerCard(
    message: String,
    isError: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = ChunkFlowIcons.Close,
                    contentDescription = stringResource(R.string.library_dismiss_banner),
                )
            }
        }
    }
}

@Composable
internal fun LibraryEmptyContent(
    onImportDirectoryClick: () -> Unit,
    onImportFilesClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = ChunkFlowIcons.Folder,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.library_empty),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.library_empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onImportDirectoryClick) {
            Icon(
                imageVector = ChunkFlowIcons.Folder,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.library_empty_import_directory_action))
        }
        OutlinedButton(onClick = onImportFilesClick) {
            Icon(
                imageVector = ChunkFlowIcons.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = stringResource(R.string.library_empty_import_action))
        }
    }
}
