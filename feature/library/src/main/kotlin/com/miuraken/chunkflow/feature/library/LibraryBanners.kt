package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.data.library.ImportError
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowWavyLinearProgress
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.model.MediaId

/**
 * 取り込みの進捗バナー。コピー中（[LibraryImportUiState.Copying]）だけを持続的なバナーとして描く。
 * 完了・失敗の一時的な通知は [ImportStatusSnackbarEffect] が Snackbar で出す（調査 C8、計画書 Phase B）。
 *
 * @param onCancel 右端の中断ボタンが押されたときに呼ぶ。`null` ならボタンを出さない。
 */
@Composable
internal fun ImportStatusBanner(
    importState: LibraryImportUiState,
    modifier: Modifier = Modifier,
    onCancel: (() -> Unit)? = null,
) {
    if (importState !is LibraryImportUiState.Copying) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.library_importing),
                    style = MaterialTheme.typography.bodyMedium,
                )
                val currentFileName = importState.currentFileName?.takeIf { it.isNotBlank() }
                if (currentFileName != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = currentFileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                val fraction = importState.fraction
                if (fraction != null) {
                    ChunkFlowWavyLinearProgress(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    // TODO(designsystem): 不確定の波形バーのラッパーが入ったら置き換える。
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (onCancel != null) {
                IconButton(onClick = onCancel) {
                    Icon(
                        imageVector = ChunkFlowIcons.Close,
                        contentDescription = stringResource(R.string.library_import_cancel_cd),
                    )
                }
            }
        }
    }
}

/**
 * Snackbar に出す取り込み結果の内容。
 *
 * @property message 本文。
 * @property duration 表示時間。失敗は [SnackbarDuration.Long]。
 * @property actionLabel アクションのラベル（「開く」）。無ければ `null`。
 * @property onAction アクションが押されたときの処理。
 */
@Immutable
private data class ImportStatusSnackbarContent(
    val message: String,
    val duration: SnackbarDuration,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

/**
 * 取り込みの完了・失敗を Snackbar で通知する副作用（調査 C8 の B4）。
 *
 * - 1 件をフォルダに取り込んだ: 「"ファイル名" を "フォルダ" に追加しました」+「開く」（そのファイルをプレイヤーで開く）
 * - 複数件をフォルダに取り込んだ: 「N 件を "フォルダ" に追加しました」+「開く」（そのフォルダへ移動）
 * - それ以外の完了・失敗: 本文のみ
 *
 * Snackbar が閉じたら（アクション・× ・時間切れのいずれでも）[onDismiss] を呼び、ViewModel 側の状態を `Idle` に戻す。
 * 取り込み先フォルダへの自動移動は ViewModel が行う（この副作用では変えない）。
 *
 * @param importState 取り込みの状態。[LibraryImportUiState.Done] と [LibraryImportUiState.Error] だけに反応する。
 * @param snackbarHostState `Scaffold` の `snackbarHost` に渡した [SnackbarHostState]。
 * @param onOpenItem 「開く」で教材を開く。
 * @param onOpenFolder 「開く」でフォルダへ移動する。
 * @param onDismiss Snackbar が閉じたときに呼ぶ（`DismissImportStatus`）。
 */
@Composable
internal fun ImportStatusSnackbarEffect(
    importState: LibraryImportUiState,
    snackbarHostState: SnackbarHostState,
    onOpenItem: (MediaId) -> Unit,
    onOpenFolder: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val content = importStatusSnackbarContent(
        importState = importState,
        onOpenItem = onOpenItem,
        onOpenFolder = onOpenFolder,
    )
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(importState) {
        if (content == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = content.message,
            actionLabel = content.actionLabel,
            withDismissAction = true,
            duration = content.duration,
        )
        if (result == SnackbarResult.ActionPerformed) {
            content.onAction?.invoke()
        }
        currentOnDismiss()
    }
}

@Composable
private fun importStatusSnackbarContent(
    importState: LibraryImportUiState,
    onOpenItem: (MediaId) -> Unit,
    onOpenFolder: (String) -> Unit,
): ImportStatusSnackbarContent? = when (importState) {
    LibraryImportUiState.Idle,
    is LibraryImportUiState.Copying,
    -> null

    is LibraryImportUiState.Done -> {
        val folderName = importState.targetFolderName?.takeIf { it.isNotBlank() }
        val importedFiles = importState.importedFiles
        val openLabel = stringResource(R.string.library_import_snackbar_open)
        when {
            folderName != null && importedFiles.size == 1 -> {
                val file = importedFiles.first()
                ImportStatusSnackbarContent(
                    message = stringResource(
                        R.string.library_import_done_single_into_folder,
                        file.fileName,
                        folderName,
                    ),
                    duration = SnackbarDuration.Short,
                    actionLabel = openLabel,
                    onAction = { onOpenItem(file.mediaId) },
                )
            }

            folderName != null && importedFiles.size > 1 -> {
                ImportStatusSnackbarContent(
                    message = stringResource(
                        R.string.library_import_done_multiple_into_folder,
                        importedFiles.size,
                        folderName,
                    ),
                    duration = SnackbarDuration.Short,
                    actionLabel = openLabel,
                    onAction = { onOpenFolder(folderName) },
                )
            }

            importState.transcriptsAttached > 0 -> ImportStatusSnackbarContent(
                message = stringResource(
                    R.string.library_import_done_with_transcripts,
                    importState.addedCount,
                    importState.duplicates,
                    importState.transcriptsAttached,
                ),
                duration = SnackbarDuration.Short,
            )

            else -> ImportStatusSnackbarContent(
                message = stringResource(
                    R.string.library_import_done,
                    importState.addedCount,
                    importState.duplicates,
                ),
                duration = SnackbarDuration.Short,
            )
        }
    }

    is LibraryImportUiState.Error -> ImportStatusSnackbarContent(
        message = when (importState.error) {
            ImportError.UNSUPPORTED_FORMAT -> stringResource(R.string.library_import_error_unsupported)
            ImportError.TOO_LARGE -> stringResource(R.string.library_import_error_too_large)
            ImportError.NO_SPACE -> stringResource(R.string.library_import_error_no_space)
            ImportError.IO -> stringResource(R.string.library_import_error_io)
            ImportError.CANCELLED -> stringResource(R.string.library_import_error_cancelled)
        },
        // 利用者が中断したとき（CANCELLED）は失敗ではないので短く出す
        duration = if (importState.error == ImportError.CANCELLED) SnackbarDuration.Short else SnackbarDuration.Long,
    )
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
