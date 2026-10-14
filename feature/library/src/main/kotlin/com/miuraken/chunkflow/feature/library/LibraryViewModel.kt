package com.miuraken.chunkflow.feature.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.data.library.ImportError
import com.miuraken.chunkflow.core.data.library.ImportProgress
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.data.library.SyncError
import com.miuraken.chunkflow.core.data.library.SyncProgress
import com.miuraken.chunkflow.core.data.library.TranscriptImportResult
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.playback.PlaybackController
import com.miuraken.chunkflow.core.playback.PlaybackStatus
import com.miuraken.chunkflow.core.testing.fake.FakePlaybackController
import com.miuraken.chunkflow.core.transcript.TranscriptError
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ライブラリ一覧の各教材の UI 表示モデル（F2.1、F2.2、F7.1）。
 *
 * @property item 教材のメタデータ。
 * @property positionMs 前回のレジューム再生位置（ミリ秒）。
 * @property speed 前回の再生速度（0.5..2.0）。
 * @property chunkCount チャンク数（解析前・未取得の場合は 0）。
 * @property hasTranscript 文字起こしが紐付いているかどうか。
 * @property isAnalyzing 端末内解析を実行中かどうか。
 */
data class LibraryItemUiModel(
    val item: AudioItem,
    val positionMs: Long = 0L,
    val speed: Float = 1.0f,
    val chunkCount: Int = 0,
    val hasTranscript: Boolean = false,
    val isAnalyzing: Boolean = false,
) {
    /** 再生進捗の割合（0.0..1.0）。 */
    val progressFraction: Float
        get() = if (item.durationMs > 0L) {
            (positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    /** ディレクトリ構成における対応文字起こし JSON の相対パス案内。 */
    val transcriptRelativePath: String
        get() = "transcription/${item.title}.chunkflow.json"
}

/**
 * ディレクトリ（フォルダ）単位の教材グループ表示モデル。
 *
 * @property name ディレクトリ名（未分類の場合は `null`）。
 * @property items このディレクトリ内の教材一覧。
 * @property isExpanded 展開中かどうか。
 */
data class LibraryDirectoryUiModel(
    val name: String?,
    val items: List<LibraryItemUiModel>,
    val isExpanded: Boolean = true,
) {
    /** ディレクトリ配下の `transcription/` サブフォルダの対応パス案内。 */
    val transcriptionPathHint: String
        get() = if (name != null) {
            "$name/transcription/*.chunkflow.json"
        } else {
            "transcription/*.chunkflow.json"
        }
}

/**
 * 音声・文字起こし一括取り込みの UI 状態（F1.1、F7.1）。
 */
sealed interface LibraryImportUiState {
    /** 取り込みを行っていない。 */
    data object Idle : LibraryImportUiState

    /**
     * ファイルをコピー・検証中。
     *
     * @property fraction 進捗（0..1）。不明なら `null`。
     */
    data class Copying(val fraction: Float?) : LibraryImportUiState

    /**
     * 取り込みが完了した。
     *
     * @property addedCount 取り込んだ教材数。
     * @property duplicates 重複していた音声の数。
     * @property transcriptsAttached 紐付けた文字起こしの数。
     */
    data class Done(
        val addedCount: Int,
        val duplicates: Int,
        val transcriptsAttached: Int = 0,
    ) : LibraryImportUiState

    /**
     * 取り込みに失敗した。
     *
     * @property error 失敗の種別。
     */
    data class Error(val error: ImportError) : LibraryImportUiState
}

/**
 * 文字起こし JSON / 字幕単体取り込みの UI 状態（F7.1、F1.4、§7.1）。
 */
sealed interface TranscriptImportUiState {
    /** 取り込みを行っていない。 */
    data object Idle : TranscriptImportUiState

    /**
     * 既存の教材に文字起こしを紐付けた。
     *
     * @property mediaId 紐付けた教材の ID。
     * @property chunkCount 作成したチャンクの数。
     * @property durationMismatch 教材の長さと JSON の `durationMs` に 0.5 秒以上の差があるか。
     */
    data class Attached(
        val mediaId: MediaId,
        val chunkCount: Int,
        val durationMismatch: Boolean,
    ) : TranscriptImportUiState

    /**
     * 対応する音声がまだ取り込まれていない。
     *
     * @property audioFileName JSON に書かれた音声ファイル名。
     */
    data class NoMatchingAudio(val audioFileName: String?) : TranscriptImportUiState

    /**
     * 文字起こしファイルが不正。
     *
     * @property error 失敗の種別。
     */
    data class Invalid(val error: TranscriptError) : TranscriptImportUiState
}

/**
 * Google Drive 同期の UI 状態（F1.5、§7.1）。
 */
sealed interface DriveSyncUiState {
    /** 同期を行っていない。 */
    data object Idle : DriveSyncUiState

    /** Drive のファイル一覧を取得中。 */
    data object Listing : DriveSyncUiState

    /**
     * ダウンロード中。
     *
     * @property done 完了したファイル数。
     * @property total 対象の総ファイル数。
     */
    data class Downloading(val done: Int, val total: Int) : DriveSyncUiState

    /**
     * 同期が完了した。
     *
     * @property newAudio 追加した音声数。
     * @property newTranscripts 追加・更新した文字起こし数。
     * @property failed 失敗したファイル数。
     * @property uploadedTranscripts Drive へアップロード（書き戻し）したローカル文字起こし数。
     */
    data class Done(
        val newAudio: Int,
        val newTranscripts: Int,
        val failed: Int,
        val uploadedTranscripts: Int = 0,
    ) : DriveSyncUiState

    /**
     * 同期に失敗した。
     *
     * @property error 失敗の種別。
     */
    data class Error(val error: SyncError) : DriveSyncUiState
}

/**
 * ライブラリ画面下部のミニプレイヤーの状態（§7.5.1）。
 *
 * @property mediaId 現在開いている教材の ID。
 * @property title 現在開いている教材のタイトル。
 * @property isPlaying 再生中かどうか。
 * @property durationMs 教材の長さ（ミリ秒）。
 */
data class MiniPlayerUiState(
    val mediaId: MediaId,
    val title: String,
    val isPlaying: Boolean,
    val durationMs: Long,
)

/**
 * ライブラリ画面の状態（F1.1〜F2.3、F7.1、§7.5.1）。
 *
 * @property items 教材の一覧（レジューム位置・速度・チャンク数を含む）。
 * @property importState 音声・フォルダ取り込みの進捗・エラー状態。
 * @property transcriptImportState 文字起こし単体取り込みの結果状態。
 * @property syncState Google Drive 同期の進捗・エラー状態。
 * @property miniPlayer 現在開いている教材のミニプレイヤー表示（開いていなければ `null`）。
 * @property pendingDeleteItem 削除確認ダイアログで対象になっている教材（表示中でなければ `null`）。
 * @property pendingEditItem コース・言語ペア編集ダイアログで対象になっている教材（表示中でなければ `null`）。
 * @property selectedDirectoryName フォルダ指定で選択されたディレクトリ名。
 * @property collapsedDirectories 折りたたまれているディレクトリ名の集合。
 */
data class LibraryUiState(
    val items: List<LibraryItemUiModel> = emptyList(),
    val importState: LibraryImportUiState = LibraryImportUiState.Idle,
    val transcriptImportState: TranscriptImportUiState = TranscriptImportUiState.Idle,
    val syncState: DriveSyncUiState = DriveSyncUiState.Idle,
    val miniPlayer: MiniPlayerUiState? = null,
    val pendingDeleteItem: AudioItem? = null,
    val pendingEditItem: AudioItem? = null,
    val selectedDirectoryName: String? = null,
    val collapsedDirectories: Set<String?> = emptySet(),
) {
    /** ディレクトリ（フォルダ）単位にグループ化した教材一覧。 */
    val directories: List<LibraryDirectoryUiModel>
        get() = items
            .groupBy { it.item.course ?: selectedDirectoryName }
            .map { (dirName, dirItems) ->
                LibraryDirectoryUiModel(
                    name = dirName,
                    items = dirItems,
                    isExpanded = dirName !in collapsedDirectories,
                )
            }
}

/** ライブラリ画面のイベント。 */
sealed interface LibraryEvent {
    /** SAF フォルダピッカー（OpenDocumentTree）を開いてディレクトリ単位で取り込む。 */
    data object RequestImportDirectory : LibraryEvent

    /** SAF ファイルピッカーを開いて音声・文字起こしファイルを取り込む。 */
    data object RequestImport : LibraryEvent

    /** SAF ファイルピッカーを開いて文字起こし・字幕ファイルを単体で取り込む。 */
    data object RequestImportTranscript : LibraryEvent

    /** 指定されたフォルダ（ディレクトリ）名と配下の URI 一覧を取り込む。 */
    data class ImportDirectory(val directoryName: String?, val uris: List<Uri>) : LibraryEvent

    /** SAF で選択された URI の一覧を取り込む。 */
    data class ImportUris(val uris: List<Uri>) : LibraryEvent

    /** SAF で選択された文字起こし・字幕 URI を単体で取り込む。 */
    data class ImportTranscriptUri(val uri: Uri) : LibraryEvent

    /** Google Drive の `ChunkFlow/` フォルダと同期する（F1.5）。 */
    data object SyncFromDrive : LibraryEvent

    /** 取り込み・同期の完了・エラーバナーを閉じる。 */
    data object DismissImportStatus : LibraryEvent

    /** Google Drive 同期バナーを閉じる。 */
    data object DismissSyncStatus : LibraryEvent

    /** ディレクトリ（フォルダ）セクションの展開・折りたたみを切り替える。 */
    data class ToggleDirectory(val directoryName: String?) : LibraryEvent

    /** 教材を開いてプレイヤー画面へ遷移する。 */
    data class OpenItem(val id: MediaId) : LibraryEvent

    /** 設定画面を開く。 */
    data object OpenSettings : LibraryEvent

    /** 教材のコース名・言語ペア編集ダイアログを開く（F2.3）。 */
    data class RequestEditMetadata(val item: AudioItem) : LibraryEvent

    /** 教材のコース名・言語ペアの編集を確定して保存する（F2.3）。 */
    data class ConfirmEditMetadata(
        val id: MediaId,
        val course: String?,
        val languages: LanguagePair,
    ) : LibraryEvent

    /** 教材のコース名・言語ペア編集ダイアログをキャンセルする。 */
    data object CancelEditMetadata : LibraryEvent

    /** 教材の削除確認ダイアログを開く。 */
    data class RequestDelete(val item: AudioItem) : LibraryEvent

    /** 削除確認ダイアログで削除を実行する。 */
    data object ConfirmDelete : LibraryEvent

    /** 削除確認ダイアログをキャンセルする。 */
    data object CancelDelete : LibraryEvent

    /** ミニプレイヤーの再生 / 一時停止を切り替える。 */
    data object ToggleMiniPlayerPlayPause : LibraryEvent
}

private data class ItemAnalysisSummary(
    val chunkCount: Int = 0,
    val hasTranscript: Boolean = false,
    val isAnalyzing: Boolean = false,
)

private data class DialogAndImportSnapshot(
    val importState: LibraryImportUiState = LibraryImportUiState.Idle,
    val transcriptImportState: TranscriptImportUiState = TranscriptImportUiState.Idle,
    val syncState: DriveSyncUiState = DriveSyncUiState.Idle,
    val pendingDeleteItem: AudioItem? = null,
    val pendingEditItem: AudioItem? = null,
)

private object EmptyAnalysisRepository : AnalysisRepository {
    override fun observeStatus(id: MediaId): Flow<Map<AnalysisStage, StageStatus>> = flowOf(emptyMap())
    override fun observeWaveform(id: MediaId): Flow<Waveform?> = flowOf(null)
    override fun observeChunks(id: MediaId): Flow<List<Chunk>> = flowOf(emptyList())
    override fun observeTranscripts(id: MediaId): Flow<Map<ChunkId, Transcript>> = flowOf(emptyMap())
    override fun observeAvailableChunkOrigins(id: MediaId): Flow<Set<ChunkOrigin>> = flowOf(emptySet())
    override suspend fun requestAnalysis(id: MediaId, stages: Set<AnalysisStage>) = Unit
    override suspend fun resegment(id: MediaId, params: SegmentationParams) = Unit
    override suspend fun setChunkSource(id: MediaId, source: ChunkOrigin) = Unit
    override suspend fun setTranscriptOffset(id: MediaId, offsetMs: Int) = Unit
}

/**
 * ライブラリ画面の ViewModel（F1.1〜F2.3、F7.1、§7.5.1）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val analysisRepository: AnalysisRepository,
) : ViewModel() {

    constructor(
        libraryRepository: LibraryRepository,
        playbackController: PlaybackController,
    ) : this(
        libraryRepository = libraryRepository,
        playbackController = playbackController,
        analysisRepository = EmptyAnalysisRepository,
    )

    private val importState = MutableStateFlow<LibraryImportUiState>(LibraryImportUiState.Idle)
    private val transcriptImportState =
        MutableStateFlow<TranscriptImportUiState>(TranscriptImportUiState.Idle)
    private val syncState = MutableStateFlow<DriveSyncUiState>(DriveSyncUiState.Idle)
    private val pendingDeleteItem = MutableStateFlow<AudioItem?>(null)
    private val pendingEditItem = MutableStateFlow<AudioItem?>(null)
    private val selectedDirectoryName = MutableStateFlow<String?>(null)
    private val collapsedDirectories = MutableStateFlow<Set<String?>>(emptySet())
    private var importJob: Job? = null
    private var syncJob: Job? = null

    private val analysisSummaries: Flow<Map<MediaId, ItemAnalysisSummary>> =
        libraryRepository.observeItems().flatMapLatest { items ->
            if (items.isEmpty()) {
                flowOf(emptyMap())
            } else {
                val perItemFlows = items.map { item ->
                    combine(
                        analysisRepository.observeChunks(item.id),
                        analysisRepository.observeTranscripts(item.id),
                        analysisRepository.observeStatus(item.id),
                    ) { chunks, transcripts, statusMap ->
                        item.id to ItemAnalysisSummary(
                            chunkCount = chunks.size,
                            hasTranscript = transcripts.isNotEmpty() ||
                                chunks.any { it.origin == ChunkOrigin.IMPORTED },
                            isAnalyzing = statusMap.values.any { it is StageStatus.Running },
                        )
                    }
                }
                combine(perItemFlows) { pairs -> pairs.toMap() }
            }
        }

    private val dialogAndImportFlow: Flow<DialogAndImportSnapshot> = combine(
        importState,
        transcriptImportState,
        syncState,
        pendingDeleteItem,
        pendingEditItem,
    ) { currentImport, currentTranscript, currentSync, deleteTarget, editTarget ->
        DialogAndImportSnapshot(
            importState = currentImport,
            transcriptImportState = currentTranscript,
            syncState = currentSync,
            pendingDeleteItem = deleteTarget,
            pendingEditItem = editTarget,
        )
    }

    /** ライブラリ画面の UI 状態。 */
    val uiState: StateFlow<LibraryUiState> = combine(
        combine(
            libraryRepository.observeItems(),
            libraryRepository.observeItemStates(),
            analysisSummaries,
        ) { items, itemStates, summaries ->
            Triple(items, itemStates, summaries)
        },
        playbackController.status,
        dialogAndImportFlow,
        selectedDirectoryName,
        collapsedDirectories,
    ) { (items, itemStates, summaries), playbackStatus, dialogAndImport, dirName, collapsed ->
        buildUiState(
            items = items,
            itemStates = itemStates,
            summaries = summaries,
            playbackStatus = playbackStatus,
            dialogAndImport = dialogAndImport,
            selectedDirectoryName = dirName,
            collapsedDirectories = collapsed,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LibraryUiState(),
    )

    /** 画面からのイベントを処理する。 */
    fun onEvent(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.ImportDirectory -> {
                if (!event.directoryName.isNullOrBlank()) {
                    selectedDirectoryName.value = event.directoryName
                }
                importUris(event.uris)
            }

            is LibraryEvent.ImportUris -> importUris(event.uris)

            is LibraryEvent.ImportTranscriptUri -> importTranscriptUri(event.uri)

            LibraryEvent.SyncFromDrive -> syncFromDrive()

            LibraryEvent.DismissImportStatus -> {
                importState.value = LibraryImportUiState.Idle
                transcriptImportState.value = TranscriptImportUiState.Idle
                syncState.value = DriveSyncUiState.Idle
            }

            LibraryEvent.DismissSyncStatus -> {
                syncState.value = DriveSyncUiState.Idle
            }

            is LibraryEvent.ToggleDirectory -> {
                collapsedDirectories.update { current ->
                    if (event.directoryName in current) {
                        current - event.directoryName
                    } else {
                        current + event.directoryName
                    }
                }
            }

            is LibraryEvent.RequestEditMetadata -> {
                pendingEditItem.value = event.item
            }

            is LibraryEvent.ConfirmEditMetadata -> {
                pendingEditItem.value = null
                val normalizedCourse = event.course?.trim()?.ifBlank { null }
                val normalizedTarget = event.languages.target.value.trim().lowercase().ifBlank { "en" }
                val normalizedBase = event.languages.base?.value?.trim()?.lowercase()?.ifBlank { null }
                viewModelScope.launch {
                    libraryRepository.updateItemMetadata(
                        id = event.id,
                        course = normalizedCourse,
                        languages = LanguagePair(
                            target = LanguageTag(normalizedTarget),
                            base = normalizedBase?.let { LanguageTag(it) },
                        ),
                    )
                }
            }

            LibraryEvent.CancelEditMetadata -> {
                pendingEditItem.value = null
            }

            is LibraryEvent.RequestDelete -> {
                pendingDeleteItem.value = event.item
            }

            LibraryEvent.CancelDelete -> {
                pendingDeleteItem.value = null
            }

            LibraryEvent.ConfirmDelete -> confirmDelete()

            LibraryEvent.ToggleMiniPlayerPlayPause -> toggleMiniPlayerPlayPause()

            LibraryEvent.RequestImportDirectory,
            LibraryEvent.RequestImport,
            LibraryEvent.RequestImportTranscript,
            is LibraryEvent.OpenItem,
            LibraryEvent.OpenSettings,
            -> Unit
        }
    }

    private fun importUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        transcriptImportState.value = TranscriptImportUiState.Idle
        importJob?.cancel()
        importJob = viewModelScope.launch {
            libraryRepository.import(uris).collect { progress ->
                importState.value = when (progress) {
                    is ImportProgress.Copying -> LibraryImportUiState.Copying(progress.fraction)

                    is ImportProgress.Done -> LibraryImportUiState.Done(
                        addedCount = progress.mediaIds.size,
                        duplicates = progress.duplicates,
                        transcriptsAttached = progress.transcriptsAttached,
                    )

                    is ImportProgress.Failed -> LibraryImportUiState.Error(progress.error)
                }
            }
        }
    }

    private fun importTranscriptUri(uri: Uri) {
        importState.value = LibraryImportUiState.Idle
        viewModelScope.launch {
            val result = libraryRepository.importTranscript(uri)
            transcriptImportState.value = when (result) {
                is TranscriptImportResult.Attached -> TranscriptImportUiState.Attached(
                    mediaId = result.mediaId,
                    chunkCount = result.chunkCount,
                    durationMismatch = result.durationMismatch,
                )

                is TranscriptImportResult.NoMatchingAudio -> TranscriptImportUiState.NoMatchingAudio(
                    audioFileName = result.audioFileName,
                )

                is TranscriptImportResult.Invalid -> TranscriptImportUiState.Invalid(
                    error = result.error,
                )
            }
        }
    }

    private fun syncFromDrive() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            libraryRepository.syncFromDrive().collect { progress ->
                syncState.value = when (progress) {
                    SyncProgress.Listing -> DriveSyncUiState.Listing

                    is SyncProgress.Downloading -> DriveSyncUiState.Downloading(
                        done = progress.done,
                        total = progress.total,
                    )

                    is SyncProgress.Done -> DriveSyncUiState.Done(
                        newAudio = progress.newAudio,
                        newTranscripts = progress.newTranscripts,
                        failed = progress.failed,
                        uploadedTranscripts = progress.uploadedTranscripts,
                    )

                    is SyncProgress.Failed -> DriveSyncUiState.Error(progress.error)
                }
            }
        }
    }

    private fun confirmDelete() {
        val target = pendingDeleteItem.value ?: return
        pendingDeleteItem.value = null
        viewModelScope.launch {
            libraryRepository.delete(target.id)
            if (playbackController is FakePlaybackController &&
                playbackController.status.value.mediaId == target.id
            ) {
                playbackController.statusState.value = PlaybackStatus()
            }
        }
    }

    private fun toggleMiniPlayerPlayPause() {
        val player = playbackController.player.value
        if (player != null) {
            if (playbackController.status.value.isPlaying || player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
        } else if (playbackController is FakePlaybackController) {
            playbackController.statusState.update { current ->
                if (current.mediaId != null) {
                    current.copy(isPlaying = !current.isPlaying)
                } else {
                    current
                }
            }
        }
    }

    private fun buildUiState(
        items: List<AudioItem>,
        itemStates: Map<MediaId, ItemState>,
        summaries: Map<MediaId, ItemAnalysisSummary>,
        playbackStatus: PlaybackStatus,
        dialogAndImport: DialogAndImportSnapshot,
        selectedDirectoryName: String?,
        collapsedDirectories: Set<String?>,
    ): LibraryUiState {
        val uiItems = items.map { audioItem ->
            val state = itemStates[audioItem.id]
            val summary = summaries[audioItem.id]
            LibraryItemUiModel(
                item = audioItem,
                positionMs = state?.positionMs ?: 0L,
                speed = state?.speed ?: 1.0f,
                chunkCount = summary?.chunkCount ?: 0,
                hasTranscript = summary?.hasTranscript ?: false,
                isAnalyzing = summary?.isAnalyzing ?: false,
            )
        }
        val activeMediaId = playbackStatus.mediaId
        val activeAudioItem = if (activeMediaId != null) {
            items.firstOrNull { it.id == activeMediaId }
        } else {
            null
        }
        val miniPlayer = if (activeMediaId != null && activeAudioItem != null) {
            MiniPlayerUiState(
                mediaId = activeMediaId,
                title = activeAudioItem.title,
                isPlaying = playbackStatus.isPlaying,
                durationMs = if (playbackStatus.durationMs > 0L) {
                    playbackStatus.durationMs
                } else {
                    activeAudioItem.durationMs
                },
            )
        } else {
            null
        }
        return LibraryUiState(
            items = uiItems,
            importState = dialogAndImport.importState,
            transcriptImportState = dialogAndImport.transcriptImportState,
            syncState = dialogAndImport.syncState,
            miniPlayer = miniPlayer,
            pendingDeleteItem = dialogAndImport.pendingDeleteItem,
            pendingEditItem = dialogAndImport.pendingEditItem,
            selectedDirectoryName = selectedDirectoryName,
            collapsedDirectories = collapsedDirectories,
        )
    }
}
