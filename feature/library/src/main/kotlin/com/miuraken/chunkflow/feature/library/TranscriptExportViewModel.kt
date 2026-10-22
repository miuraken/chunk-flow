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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.receiveAsFlow
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

    /** 文字数が多すぎて、クリップボードにコピーできなかった（[TranscriptExportViewModel.MAX_CLIPBOARD_CHARS]）。 */
    data object ClipboardTooLarge : TranscriptExportResultState

    /** エクスポートに失敗した。 */
    data object Failed : TranscriptExportResultState
}

/**
 * 文字起こしエクスポート画面の UI 状態。
 *
 * @property mediaIds エクスポート対象の教材 ID 一覧。
 * @property firstItemTitle 先頭教材のファイル名（タイトル）。
 * @property selectedFormat 選択中のエクスポートフォーマット（既定は TSV。並び順は [FORMAT_ORDER]）。
 * @property previewByFormat 各フォーマットにおける先頭教材のプレビュー文字列（先頭数行）。
 * @property exportResult エクスポート実行後の結果通知状態。
 * @property isCopyingToClipboard クリップボードへのコピーの準備中かどうか（二重に押せないようにする）。
 */
data class TranscriptExportUiState(
    val mediaIds: List<MediaId> = emptyList(),
    val firstItemTitle: String = "",
    val selectedFormat: TranscriptExportFormat = TranscriptExportFormat.TSV,
    val previewByFormat: Map<TranscriptExportFormat, String> = emptyMap(),
    val exportResult: TranscriptExportResultState = TranscriptExportResultState.Idle,
    val isCopyingToClipboard: Boolean = false,
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

    /** 「クリップボードにエクスポート」を押下可能かどうか。 */
    val canCopyToClipboard: Boolean
        get() = canExport && !isCopyingToClipboard

    companion object {
        /** 画面に並べるフォーマットの順（TSV・SRT・LRC・JSON）。 */
        val FORMAT_ORDER: List<TranscriptExportFormat> = listOf(
            TranscriptExportFormat.TSV,
            TranscriptExportFormat.SRT,
            TranscriptExportFormat.LRC,
            TranscriptExportFormat.JSON,
        )
    }
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

    /**
     * 選択中のフォーマットの文字起こしをクリップボードにコピーする。複数ファイルのときは、ファイルごとに
     * 「=== ファイル名 ===」の見出しを付けてつなげる。
     */
    data object CopyToClipboard : TranscriptExportEvent

    /**
     * 画面がクリップボードへの書き込み（[TranscriptExportEffect.WriteToClipboard]）を終えた。
     * コピーしたことはシステム（Android 13 以上）が画面に表示するので、成功したときアプリからは何も表示しない。
     *
     * @property success 書き込めたかどうか。
     */
    data class ClipboardWriteFinished(val success: Boolean) : TranscriptExportEvent

    /** エクスポート結果通知バナーを閉じる。 */
    data object DismissExportResult : TranscriptExportEvent

    /** 前の画面に戻る。 */
    data object Back : TranscriptExportEvent
}

/**
 * 文字起こしエクスポート画面への一度きりの指示（画面が受け取って実行する）。
 */
sealed interface TranscriptExportEffect {
    /**
     * [text] をクリップボードに書き込む。終わったら [TranscriptExportEvent.ClipboardWriteFinished] を送ること。
     *
     * @property label `ClipData` のラベル（利用者には基本的に見えない）。
     * @property text 書き込む文字列。
     */
    data class WriteToClipboard(
        val label: String,
        val text: String,
    ) : TranscriptExportEffect
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

    private val _effects = Channel<TranscriptExportEffect>(Channel.BUFFERED)

    /** 画面への一度きりの指示（クリップボードへの書き込みなど）。 */
    val effects: Flow<TranscriptExportEffect> = _effects.receiveAsFlow()

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

            TranscriptExportEvent.CopyToClipboard -> copyToClipboard()

            is TranscriptExportEvent.ClipboardWriteFinished -> {
                _uiState.update { current ->
                    current.copy(
                        isCopyingToClipboard = false,
                        exportResult = if (event.success) {
                            TranscriptExportResultState.Idle
                        } else {
                            TranscriptExportResultState.Failed
                        },
                    )
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

    /**
     * 選択中のフォーマットの文字起こしを全文読み込み、画面にクリップボードへの書き込みを頼む。
     * 文字起こしがない・長すぎるときは、書き込みを頼まずに結果を表示する。
     */
    private fun copyToClipboard() {
        val state = _uiState.value
        if (state.mediaIds.isEmpty() || state.isCopyingToClipboard) return
        val format = state.selectedFormat
        _uiState.update { current ->
            current.copy(
                isCopyingToClipboard = true,
                exportResult = TranscriptExportResultState.Idle,
            )
        }
        viewModelScope.launch {
            when (val built = buildClipboardText(state.mediaIds, format)) {
                ClipboardText.Empty -> finishClipboardCopyWithoutWriting(TranscriptExportResultState.Failed)

                ClipboardText.TooLarge -> finishClipboardCopyWithoutWriting(TranscriptExportResultState.ClipboardTooLarge)

                is ClipboardText.Ready -> {
                    _effects.send(
                        TranscriptExportEffect.WriteToClipboard(
                            label = CLIPBOARD_LABEL,
                            text = built.text,
                        ),
                    )
                }
            }
        }
    }

    private fun finishClipboardCopyWithoutWriting(result: TranscriptExportResultState) {
        _uiState.update { current ->
            current.copy(
                isCopyingToClipboard = false,
                exportResult = result,
            )
        }
    }

    /**
     * クリップボードに書き込む文字列を作る。1 ファイルならそのまま、複数ファイルなら「=== ファイル名 ===」の見出しを付けて
     * 空行でつなげる（文字起こしのないファイルは飛ばす）。[MAX_CLIPBOARD_CHARS] を超えたら、その時点でやめる。
     */
    private suspend fun buildClipboardText(
        ids: List<MediaId>,
        format: TranscriptExportFormat,
    ): ClipboardText {
        if (ids.size == 1) {
            val text = libraryRepository.exportTranscriptText(ids.first(), format)?.trimEnd().orEmpty()
            return when {
                text.isBlank() -> ClipboardText.Empty
                text.length > MAX_CLIPBOARD_CHARS -> ClipboardText.TooLarge
                else -> ClipboardText.Ready(text = text)
            }
        }
        val titles = libraryRepository.observeItems().firstOrNull().orEmpty().associate { it.id to it.title }
        val builder = StringBuilder()
        var fileCount = 0
        for (id in ids) {
            val text = libraryRepository.exportTranscriptText(id, format)?.trimEnd()
            if (text.isNullOrBlank()) continue
            if (builder.isNotEmpty()) builder.append("\n\n")
            val title = titles[id]?.takeIf { it.isNotBlank() } ?: id.value
            builder.append("=== ").append(title).append(" ===\n").append(text)
            fileCount += 1
            if (builder.length > MAX_CLIPBOARD_CHARS) return ClipboardText.TooLarge
        }
        return if (fileCount == 0) ClipboardText.Empty else ClipboardText.Ready(builder.toString())
    }

    /** クリップボードに書き込む文字列を作った結果。 */
    private sealed interface ClipboardText {
        /** 文字起こしがない。 */
        data object Empty : ClipboardText

        /** 長すぎる。 */
        data object TooLarge : ClipboardText

        /** 書き込める。 */
        data class Ready(val text: String) : ClipboardText
    }

    companion object {
        /** プレビューに表示する最大行数（標準フォーマット）。 */
        internal const val MAX_PREVIEW_LINES = 10

        /** JSON プレビューに表示する最大行数（標準フォーマットの 2 倍）。 */
        internal const val MAX_JSON_PREVIEW_LINES = MAX_PREVIEW_LINES * 2

        /**
         * クリップボードにコピーできる最大の文字数。クリップボードの内容はプロセス間通信（Binder、1 プロセスあたり約 1MB の
         * バッファ）で渡すため、UTF-16 で約 400KB に収まるよう抑える。
         */
        internal const val MAX_CLIPBOARD_CHARS: Int = 200_000

        /** クリップボードに書き込むときの `ClipData` のラベル。 */
        internal const val CLIPBOARD_LABEL: String = "Chunk Flow transcript"

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
