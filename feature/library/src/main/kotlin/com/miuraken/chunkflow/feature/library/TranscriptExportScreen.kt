package com.miuraken.chunkflow.feature.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.TranscriptExportFormat

private data class CreateTranscriptDocumentRequest(
    val mimeType: String,
    val suggestedFileName: String,
)

private object CreateTranscriptDocumentContract :
    ActivityResultContract<CreateTranscriptDocumentRequest, Uri?>() {
    override fun createIntent(
        context: Context,
        input: CreateTranscriptDocumentRequest,
    ): Intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = input.mimeType
        putExtra(Intent.EXTRA_TITLE, input.suggestedFileName)
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

/**
 * 文字起こしエクスポート画面の Route。
 *
 * 単一ファイル選択時はファイル作成ダイアログ（CreateDocument）、
 * 複数ファイル選択時はフォルダ選択ダイアログ（OpenDocumentTree）を起動する。
 */
@Composable
fun TranscriptExportRoute(
    mediaIds: List<MediaId>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TranscriptExportViewModel = hiltViewModel(),
) {
    LaunchedEffect(mediaIds) {
        viewModel.open(mediaIds)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val singleFileLauncher = rememberLauncherForActivityResult(
        contract = CreateTranscriptDocumentContract,
    ) { uri ->
        if (uri != null) {
            viewModel.onEvent(TranscriptExportEvent.ExportSingleFileToUri(uri))
        }
    }

    val directoryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            viewModel.onEvent(TranscriptExportEvent.ExportMultipleFilesToDirectory(treeUri))
        }
    }

    TranscriptExportScreen(
        state = state,
        onEvent = { event ->
            when (event) {
                TranscriptExportEvent.Back -> onBack()
                else -> viewModel.onEvent(event)
            }
        },
        onRequestExport = {
            if (state.isMultipleFiles) {
                directoryLauncher.launch(null)
            } else {
                singleFileLauncher.launch(
                    CreateTranscriptDocumentRequest(
                        mimeType = state.selectedFormat.mimeType,
                        suggestedFileName = state.suggestedFileName,
                    ),
                )
            }
        },
        modifier = modifier,
    )
}

/**
 * 文字起こしエクスポート画面（フォーマットのラジオボタン選択・先頭数行のプレビュー・エクスポート実行ボタン。§10.5.1）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptExportScreen(
    state: TranscriptExportUiState,
    onEvent: (TranscriptExportEvent) -> Unit,
    onRequestExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onEvent(TranscriptExportEvent.Back) }) {
                        Icon(
                            imageVector = ChunkFlowIcons.ArrowBack,
                            contentDescription = stringResource(R.string.library_navigate_up),
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.transcript_export_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(
                    onClick = onRequestExport,
                    enabled = state.canExport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Download,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.transcript_export_button))
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TranscriptExportResultBanner(
                result = state.exportResult,
                onDismiss = { onEvent(TranscriptExportEvent.DismissExportResult) },
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val targetSummary = if (state.isMultipleFiles) {
                        stringResource(
                            R.string.transcript_export_target_multiple,
                            state.mediaIds.size,
                            state.firstItemTitle.ifBlank { "-" },
                        )
                    } else {
                        stringResource(
                            R.string.transcript_export_target_single,
                            state.firstItemTitle.ifBlank { "-" },
                        )
                    }
                    Text(
                        text = targetSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(
                            R.string.transcript_export_preview_file_name,
                            state.suggestedFileName,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.transcript_export_format_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TranscriptExportFormat.entries.forEach { format ->
                        val selected = state.selectedFormat == format
                        val label = formatLabel(format)
                        val description = formatDescription(format)
                        OutlinedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .selectable(
                                    selected = selected,
                                    onClick = { onEvent(TranscriptExportEvent.SelectFormat(format)) },
                                    role = Role.RadioButton,
                                ),
                            border = BorderStroke(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                },
                            ),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = null,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.transcript_export_preview_section_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    val previewText = state.previewText
                    if (previewText.isBlank()) {
                        Text(
                            text = stringResource(R.string.transcript_export_preview_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    } else {
                        Text(
                            text = previewText.replace("\t", "    "),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun formatLabel(format: TranscriptExportFormat): String = when (format) {
    TranscriptExportFormat.SRT -> stringResource(R.string.transcript_export_format_srt)
    TranscriptExportFormat.LRC -> stringResource(R.string.transcript_export_format_lrc)
    TranscriptExportFormat.TSV -> stringResource(R.string.transcript_export_format_tsv)
    TranscriptExportFormat.JSON -> stringResource(R.string.transcript_export_format_json)
}

@Composable
private fun formatDescription(format: TranscriptExportFormat): String = when (format) {
    TranscriptExportFormat.SRT -> stringResource(R.string.transcript_export_format_srt_desc)
    TranscriptExportFormat.LRC -> stringResource(R.string.transcript_export_format_lrc_desc)
    TranscriptExportFormat.TSV -> stringResource(R.string.transcript_export_format_tsv_desc)
    TranscriptExportFormat.JSON -> stringResource(R.string.transcript_export_format_json_desc)
}

@Composable
private fun TranscriptExportResultBanner(
    result: TranscriptExportResultState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (message, isError) = when (result) {
        TranscriptExportResultState.Idle -> return

        is TranscriptExportResultState.SingleDone -> {
            stringResource(R.string.transcript_export_done_single, result.format.name) to false
        }

        is TranscriptExportResultState.MultipleDone -> {
            stringResource(
                R.string.transcript_export_done_multiple,
                result.count,
                result.format.name,
            ) to false
        }

        TranscriptExportResultState.Failed -> {
            stringResource(R.string.transcript_export_failed) to true
        }
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            contentColor = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
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

@ChunkFlowPreviews
@Composable
private fun TranscriptExportScreenSinglePreview() {
    ChunkFlowTheme {
        TranscriptExportScreen(
            state = TranscriptExportUiState(
                mediaIds = listOf(MediaId("sample-1")),
                firstItemTitle = "lesson01.mp3",
                selectedFormat = TranscriptExportFormat.SRT,
                previewByFormat = mapOf(
                    TranscriptExportFormat.SRT to """
                        1
                        00:00:00,000 --> 00:00:02,400
                        안녕하세요.

                        2
                        00:00:02,400 --> 00:00:05,100
                        こんにちは。
                    """.trimIndent(),
                ),
            ),
            onEvent = {},
            onRequestExport = {},
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun TranscriptExportScreenMultipleTsvPreview() {
    ChunkFlowTheme {
        TranscriptExportScreen(
            state = TranscriptExportUiState(
                mediaIds = listOf(MediaId("sample-1"), MediaId("sample-2"), MediaId("sample-3")),
                firstItemTitle = "lesson01.mp3",
                selectedFormat = TranscriptExportFormat.TSV,
                previewByFormat = mapOf(
                    TranscriptExportFormat.TSV to """
                        startMs	endMs	lang	text
                        0	2400	ko	안녕하세요.
                        2400	5100	ja	こんにちは。
                    """.trimIndent(),
                ),
                exportResult = TranscriptExportResultState.MultipleDone(
                    count = 3,
                    format = TranscriptExportFormat.TSV,
                ),
            ),
            onEvent = {},
            onRequestExport = {},
        )
    }
}
