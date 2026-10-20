package com.miuraken.chunkflow.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.TranscriptExportFormat
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 文字起こしエクスポート実行結果の表示状態。
 */
sealed interface TranscriptExportResultState {
    /** 未実行またはバナー非表示。 */
    data object Idle : TranscriptExportResultState

    /**
     * 単一ファイルのエクスポートに成功した。
     *
     * @property format エクスポートしたフォーマット。
     */
    data class SingleDone(val format: TranscriptExportFormat) : TranscriptExportResultState

    /**
     * 複数ファイルのフォルダエクスポートに成功した。
     *
     * @property count 書き出したファイル数。
     * @property format エクスポートしたフォーマット。
     */
    data class MultipleDone(
        val count: Int,
        val format: TranscriptExportFormat,
    ) : TranscriptExportResultState

    /** エクスポートに失敗した。 */
    data object Failed : TranscriptExportResultState
}

/**
 * 文字起こしエクスポート画面の UI 状態。
 *
 * @property mediaIds エクスポート対象の教材 ID 一覧。
 * @property firstItemTitle 先頭教材のファイル名（タイトル）。
 * @property selectedFormat 選択中のエクスポートフォーマット（SRT / LRC / TSV / JSON）。
 * @property previewByFormat 各フォーマットにおける先頭教材のプレビュー文字列（先頭数行）。
 * @property exportResult エクスポート実行後の結果通知状態。
 */
data class TranscriptExportUiState(
    val mediaIds: List<MediaId> = emptyList(),
    val firstItemTitle: String = "",
    val selectedFormat: TranscriptExportFormat = TranscriptExportFormat.SRT,
    val previewByFormat: Map<TranscriptExportFormat, String> = emptyMap(),
    val exportResult: TranscriptExportResultState = TranscriptExportResultState.Idle,
) {
    /** 複数ファイル選択モードかどうか。 */
    val isMultipleFiles: Boolean
        get() = mediaIds.size > 1

    /** 現在選択中のフォーマットに対応するプレビュー文字列。 */
    val previewText: String
        get() = previewByFormat[selectedFormat].orEmpty()

    /** 単一ファイル出力時の推奨ファイル名（元の拡張子をフォーマットの拡張子に置換したもの）。 */
    val suggestedFileName: String
        get() = selectedFormat.defaultFileName(firstItemTitle.ifBlank { "transcript" })

    /** エクスポートボタンを押下可能かどうか。 */
    val canExport: Boolean
        get() = mediaIds.isNotEmpty() && (isMultipleFiles || previewText.isNotBlank())
}

/**
 * 文字起こしエクスポート画面のイベント。
 */
sealed interface TranscriptExportEvent {
    /** 出力フォーマットを選択する。 */
    data class SelectFormat(val format: TranscriptExportFormat) : TranscriptExportEvent

    /** 単一ファイルを指定された [uri] に書き出す。 */
    data class ExportSingleFileToUri(val uri: Uri) : TranscriptExportEvent

    /** 複数ファイルを指定されたフォルダ [treeUri] に書き出す。 */
    data class ExportMultipleFilesToDirectory(val treeUri: Uri) : TranscriptExportEvent

    /** エクスポート結果通知バナーを閉じる。 */
    data object DismissExportResult : TranscriptExportEvent

    /** 前の画面に戻る。 */
    data object Back : TranscriptExportEvent
}

/**
 * 文字起こしエクスポート画面の ViewModel。
 */
@HiltViewModel
class TranscriptExportViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TranscriptExportUiState())

    /** 画面の UI 状態。 */
    val uiState: StateFlow<TranscriptExportUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    /**
     * エクスポート対象の [mediaIds] を設定し、先頭ファイルの各フォーマットのプレビューを読み込む。
     */
    fun open(mediaIds: List<MediaId>) {
        val distinctIds = mediaIds.distinct()
        if (distinctIds.isEmpty()) return
        if (_uiState.value.mediaIds == distinctIds && _uiState.value.previewByFormat.isNotEmpty()) {
            return
        }
        loadJob?.cancel()
        _uiState.update { current ->
            current.copy(
                mediaIds = distinctIds,
                exportResult = TranscriptExportResultState.Idle,
            )
        }
        loadJob = viewModelScope.launch {
            val firstId = distinctIds.first()
            val allItems = libraryRepository.observeItems().firstOrNull().orEmpty()
            val firstTitle = allItems.firstOrNull { it.id == firstId }?.title?.takeIf { it.isNotBlank() }
                ?: firstId.value
            val previews = linkedMapOf<TranscriptExportFormat, String>()
            for (format in TranscriptExportFormat.entries) {
                val exported = libraryRepository.exportTranscriptText(firstId, format).orEmpty()
                previews[format] = formatPreviewLines(exported, maxLines = maxPreviewLinesFor(format))
            }
            _uiState.update { current ->
                current.copy(
                    firstItemTitle = firstTitle,
                    previewByFormat = previews,
                )
            }
        }
    }

    /**
     * イベントを処理する。
     */
    fun onEvent(event: TranscriptExportEvent) {
        when (event) {
            is TranscriptExportEvent.SelectFormat -> {
                _uiState.update { current ->
                    current.copy(
                        selectedFormat = event.format,
                        exportResult = TranscriptExportResultState.Idle,
                    )
                }
            }

            is TranscriptExportEvent.ExportSingleFileToUri -> {
                val state = _uiState.value
                val firstId = state.mediaIds.firstOrNull() ?: return
                val format = state.selectedFormat
                viewModelScope.launch {
                    val ok = libraryRepository.writeExportedTranscriptToUri(
                        id = firstId,
                        format = format,
                        targetUri = event.uri,
                    )
                    _uiState.update { current ->
                        current.copy(
                            exportResult = if (ok) {
                                TranscriptExportResultState.SingleDone(format)
                            } else {
                                TranscriptExportResultState.Failed
                            },
                        )
                    }
                }
            }

            is TranscriptExportEvent.ExportMultipleFilesToDirectory -> {
                val state = _uiState.value
                val targetIds = state.mediaIds
                if (targetIds.isEmpty()) return
                val format = state.selectedFormat
                viewModelScope.launch {
                    val count = libraryRepository.writeExportedTranscriptsToDirectory(
                        ids = targetIds,
                        format = format,
                        treeUri = event.treeUri,
                    )
                    _uiState.update { current ->
                        current.copy(
                            exportResult = if (count > 0) {
                                TranscriptExportResultState.MultipleDone(
                                    count = count,
                                    format = format,
                                )
                            } else {
                                TranscriptExportResultState.Failed
                            },
                        )
                    }
                }
            }

            TranscriptExportEvent.DismissExportResult -> {
                _uiState.update { current ->
                    current.copy(exportResult = TranscriptExportResultState.Idle)
                }
            }

            TranscriptExportEvent.Back -> Unit
        }
    }

    companion object {
        /** プレビューに表示する最大行数（標準フォーマット）。 */
        internal const val MAX_PREVIEW_LINES = 10

        /** JSON プレビューに表示する最大行数（標準フォーマットの 2 倍）。 */
        internal const val MAX_JSON_PREVIEW_LINES = MAX_PREVIEW_LINES * 2

        /** フォーマット [format] に対応するプレビュー最大行数を返す。 */
        internal fun maxPreviewLinesFor(format: TranscriptExportFormat): Int = if (format == TranscriptExportFormat.JSON) MAX_JSON_PREVIEW_LINES else MAX_PREVIEW_LINES

        /**
         * エクスポート文字列 [fullText] の先頭 [maxLines] 行を取り出してプレビュー文字列に整形する。
         */
        internal fun formatPreviewLines(
            fullText: String,
            maxLines: Int = MAX_PREVIEW_LINES,
        ): String {
            val trimmed = fullText.trimEnd()
            if (trimmed.isBlank()) return ""
            val lines = trimmed.lines()
            return if (lines.size <= maxLines) {
                trimmed
            } else {
                lines.take(maxLines).joinToString("\n") + "\n…"
            }
        }
    }
}
