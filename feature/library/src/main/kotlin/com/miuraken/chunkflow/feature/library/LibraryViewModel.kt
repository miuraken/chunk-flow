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
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.AudioStorageState
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageSettings
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.LibraryFolderKind
import com.miuraken.chunkflow.core.model.LibraryPlaylist
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PlaylistId
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.UserSettings
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.playback.PlaybackController
import com.miuraken.chunkflow.core.playback.PlaybackStatus
import com.miuraken.chunkflow.core.testing.fake.FakePlaybackController
import com.miuraken.chunkflow.core.testing.fake.FakeUserSettingsRepository
import com.miuraken.chunkflow.core.transcript.TranscriptError
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigInteger
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ファイル名（タイトル）の自然順（数値部分を数値として比較し、大文字小文字を区別しない）の昇順比較関数。
 */
internal fun compareFileNamesAscending(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val startA = i
            while (i < a.length && a[i].isDigit()) i++
            val startB = j
            while (j < b.length && b[j].isDigit()) j++
            val numA = BigInteger(a.substring(startA, i))
            val numB = BigInteger(b.substring(startB, j))
            val numCmp = numA.compareTo(numB)
            if (numCmp != 0) return numCmp
            val lenCmp = (i - startA).compareTo(j - startB)
            if (lenCmp != 0) return lenCmp
        } else {
            val lowerA = ca.lowercaseChar()
            val lowerB = cb.lowercaseChar()
            if (lowerA != lowerB) return lowerA.compareTo(lowerB)
            i++
            j++
        }
    }
    val lenDiff = a.length.compareTo(b.length)
    return if (lenDiff != 0) lenDiff else a.compareTo(b)
}

/**
 * 教材の文字起こし付与状態。
 */
enum class LibraryTranscriptStatus {
    /** 文字起こしなし。 */
    NONE,

    /** 一部のチャンクのみ文字起こしあり。 */
    PARTIAL,

    /** 全チャンクの文字起こし済み。 */
    COMPLETED,
}

/**
 * ライブラリ一覧の各教材の UI 表示モデル（F2.1〜F2.4、F7.1）。
 *
 * @property item 教材のメタデータ。
 * @property positionMs 前回のレジューム再生位置（ミリ秒）。
 * @property maxPositionMs 過去に到達した最大の再生位置（ミリ秒）。
 * @property speed 前回の再生速度（0.5..2.0）。
 * @property chunkCount チャンク数（解析前・未取得の場合は 0）。
 * @property hasTranscript 文字起こしが紐付いているかどうか。
 * @property transcriptStatus 文字起こしの付与状態（なし・一部・済み）。
 * @property transcriptProgressPercent 文字起こししたチャンクの割合（0..100）。
 * @property isAnalyzing 端末内解析を実行中かどうか。
 * @property isTranscribing バックグラウンドまたは先読みで文字起こしを実行中かどうか。
 * @property isAudioMissing バックアップ復元後などで音声ファイルが未取り込みかどうか（F2.4）。
 * @property isListened ファイルの 95%〜100% の位置を 1 秒以上聴取した完了フラグ。
 * @property lastAccessedAtEpochMs 最終アクセス日時（ミリ秒、未アクセスの場合は `null`）。
 * @property isFavorite お気に入り（「お気に入り」プレイリスト）に登録されているかどうか。
 * @property queuedAtEpochMs 「次に再生するキュー」に追加された時刻（ミリ秒、未登録なら `0L`）。
 * @property accessCount 教材を開いた累計アクセス回数（0 以上）。
 * @property fileSortOrder 「ファイル」タブでのカスタム並び順（未設定なら `null`）。
 */
data class LibraryItemUiModel(
    val item: AudioItem,
    val positionMs: Long = 0L,
    val maxPositionMs: Long = positionMs,
    val speed: Float = 1.0f,
    val chunkCount: Int = 0,
    val hasTranscript: Boolean = false,
    val transcriptStatus: LibraryTranscriptStatus =
        if (hasTranscript) LibraryTranscriptStatus.COMPLETED else LibraryTranscriptStatus.NONE,
    val transcriptProgressPercent: Int = when (transcriptStatus) {
        LibraryTranscriptStatus.COMPLETED -> 100
        LibraryTranscriptStatus.NONE -> 0
        LibraryTranscriptStatus.PARTIAL -> 50
    },
    val isAnalyzing: Boolean = false,
    val isTranscribing: Boolean = false,
    val isAudioMissing: Boolean = false,
    val isListened: Boolean = false,
    val lastAccessedAtEpochMs: Long? = null,
    val isFavorite: Boolean = false,
    val queuedAtEpochMs: Long = 0L,
    val accessCount: Int = 0,
    val fileSortOrder: Int? = null,
) {
    /** 前回の再生位置（ミリ秒）。 */
    val lastPositionMs: Long
        get() = positionMs

    /** 現在の再生進捗の割合（0.0..1.0）。 */
    val progressFraction: Float
        get() = if (item.durationMs > 0L) {
            (positionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    /** 過去に到達した最大の再生進捗の割合（0.0..1.0）。 */
    val maxProgressFraction: Float
        get() {
            val effectiveMaxMs = maxOf(maxPositionMs, positionMs)
            return when {
                item.durationMs > 0L && effectiveMaxMs > 0L ->
                    (effectiveMaxMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)

                isListened -> 1f

                else -> 0f
            }
        }

    /** 過去に到達した最大の再生進捗のパーセンテージ（0..100）。 */
    val maxProgressPercent: Int
        get() = (maxProgressFraction * 100f).let { kotlin.math.round(it).toInt().coerceIn(0, 100) }

    /** ディレクトリ構成における対応文字起こし JSON の相対パス案内。 */
    val transcriptRelativePath: String
        get() = "transcription/${item.title}.chunkflow.json"
}

/** [LibraryItemUiModel] の別名。 */
typealias LibraryItemUi = LibraryItemUiModel

/**
 * ディレクトリ（フォルダ）単位の教材グループ表示モデル。
 *
 * @property name ディレクトリのフル相対パス（未分類の場合は `null`）。
 * @property items このディレクトリ（サブディレクトリを含む）内の教材一覧（ファイル名昇順）。
 * @property isExpanded 展開中かどうか。
 * @property displayName 現在の階層で表示するディレクトリ名（末尾セグメント）。
 */
data class LibraryDirectoryUiModel(
    val name: String?,
    val items: List<LibraryItemUiModel>,
    val isExpanded: Boolean = true,
    val displayName: String? = name?.substringAfterLast('/'),
    val isRealFolder: Boolean = false,
) {
    /** ディレクトリ配下の `transcription/` サブフォルダの対応パス案内。 */
    val transcriptionPathHint: String
        get() = if (name != null) {
            "$name/transcription/*.chunkflow.json"
        } else {
            "transcription/*.chunkflow.json"
        }

    /** このディレクトリ内で `Listened（済）` になっているファイル数。 */
    val listenedCount: Int
        get() = items.count { it.isListened }

    /**
     * このディレクトリ内で最後にアクセスされた `Listened（済）` ファイルの [items] 内インデックス。
     *
     * 該当するファイルがない場合は `null`。
     */
    val lastAccessedListenedIndex: Int?
        get() = items
            .withIndex()
            .filter { it.value.isListened }
            .maxWithOrNull(
                compareBy<IndexedValue<LibraryItemUiModel>> { it.value.lastAccessedAtEpochMs ?: 0L }
                    .thenBy { it.index },
            )
            ?.index

    /**
     * ディレクトリ表示時に画面上部に表示を開始する [items] 内インデックス。
     *
     * 最後にアクセスされた `Listened（済）` ファイルが 1〜2 件だけ上部に残って見える位置（`max(0, lastAccessedListenedIndex - 1)`）を返す。
     */
    val initialVisibleItemIndexInDirectory: Int?
        get() = lastAccessedListenedIndex?.let { idx -> (idx - 1).coerceAtLeast(0) }
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
 * @property mediaId 現在開いている（または前回最後に開いていた）教材の ID。
 * @property title 現在開いている（または前回最後に開いていた）教材のタイトル。
 * @property isPlaying 再生中かどうか。
 * @property durationMs 教材の長さ（ミリ秒）。
 * @property positionMs レジューム再生位置（ミリ秒）。
 */
data class MiniPlayerUiState(
    val mediaId: MediaId,
    val title: String,
    val isPlaying: Boolean,
    val durationMs: Long,
    val positionMs: Long = 0L,
    val sourceUri: String? = null,
    val localFilePath: String? = null,
)

/**
 * ファイルまたはディレクトリ選択後、取り込み開始前に表示する共通設定ダイアログの状態。
 *
 * @property uris 取り込み対象の URI 一覧。
 * @property initialCourse 初期コース名（ディレクトリ名など）。
 * @property initialLanguages 初期言語ペア（対象言語・ベース言語）。
 * @property isDriveSyncFolder Drive 同期フォルダの連携設定かどうか。
 */
data class PendingImportConfig(
    val uris: List<Uri>,
    val initialCourse: String? = null,
    val initialLanguages: LanguagePair = LanguagePair(
        target = LanguageTag("ko"),
        base = LanguageTag("ja"),
    ),
    val isDriveSyncFolder: Boolean = false,
    val treeUri: Uri? = null,
)

/**
 * ディレクトリ（コース）単位のコース名・言語ペア一括再設定ダイアログの状態。
 *
 * @property currentCourse 現在のディレクトリ（コース）名（未分類の場合は `null`）。
 * @property initialLanguages 初期表示する言語ペア。
 */
data class PendingDirectoryEdit(
    val currentCourse: String?,
    val initialLanguages: LanguagePair,
)

/**
 * ライブラリ画面の上部タブ。
 */
enum class LibraryViewTab {
    /** フォルダから階層的に辿るフォルダビュー。 */
    FOLDERS,

    /** 登録されているすべてのファイルを一覧・フィルタするファイルビュー。 */
    FILES,

    /** 最近開いたファイル・よくアクセスするファイル・お気に入り・ユーザー作成プレイリストの再生リストビュー。 */
    PLAYLISTS,
}

/**
 * ファイルビュータブのソート種別。
 */
enum class LibraryFileSortOption {
    /** 名称順（ファイル名自然昇順 / 手動並び替え順）。 */
    NAME,

    /** アクセス日時順（最終アクセスが新しい順）。 */
    ACCESS_TIME,

    /** 完了度順（完了済み・再生進捗率の高い順）。 */
    COMPLETION,
}

/**
 * 再生リストビュータブの組み込みスマートリスト種別。
 */
enum class LibraryPlaylistCategory(val playlistId: PlaylistId) {
    /** 最近開いたファイル。 */
    RECENTLY_OPENED(PlaylistId.RECENTLY_OPENED),

    /** よくアクセスするファイル。 */
    FREQUENTLY_ACCESSED(PlaylistId.FREQUENTLY_ACCESSED),

    /** お気に入り。 */
    FAVORITES(PlaylistId.FAVORITES),

    /** 次に再生するキュー（互換用）。 */
    UP_NEXT_QUEUE(PlaylistId("builtin:up_next_queue")),
    ;

    companion object {
        /** 既定で表示する組み込みスマートリスト（上から順）。 */
        val BUILT_IN_CATEGORIES: List<LibraryPlaylistCategory> = listOf(
            RECENTLY_OPENED,
            FREQUENTLY_ACCESSED,
            FAVORITES,
        )

        /** [PlaylistId] に対応する組み込みカテゴリがあれば返す。 */
        fun fromPlaylistId(id: PlaylistId): LibraryPlaylistCategory? = entries.firstOrNull { it.playlistId == id }
    }
}

/**
 * 「再生リスト」タブに表示するプレイリスト 1 件分の UI モデル。
 *
 * @property id プレイリストの一意な識別子。
 * @property name ユーザー作成プレイリストの表示名（組み込みスマートリストの場合は空文字列でもよく、UI で [builtInCategory] から表示名を解決する）。
 * @property builtInCategory 組み込みスマートリストの場合はその種別、ユーザー作成プレイリストなら `null`。
 * @property sortOrder プレイリスト一覧での並び順（小さいほど上）。
 * @property items このプレイリストに含まれる教材一覧（並び順反映済み）。
 */
data class LibraryPlaylistEntryUiModel(
    val id: PlaylistId,
    val name: String,
    val builtInCategory: LibraryPlaylistCategory? = null,
    val sortOrder: Int = 0,
    val items: List<LibraryItemUiModel> = emptyList(),
) {
    /** 組み込みスマートプレイリストかどうか。 */
    val isBuiltIn: Boolean
        get() = builtInCategory != null
}

/**
 * ライブラリ画面の状態（F1.1〜F2.4、F7.1、§7.5.1）。
 *
 * @property items 教材の一覧（レジューム位置・速度・チャンク数・音声有無・聴取完了・最終アクセス日時を含む）。
 * @property importState 音声・フォルダ取り込みの進捗・エラー状態。
 * @property transcriptImportState 文字起こし単体取り込みの結果状態。
 * @property syncState Google Drive 同期の進捗・エラー状態。
 * @property miniPlayer 現在開いている教材のミニプレイヤー表示（開いていなければ `null`）。
 * @property pendingDeleteItem 削除確認ダイアログで対象になっている単一教材（表示中でなければ `null`）。
 * @property pendingDeleteDirectoryPath 削除確認ダイアログで対象になっているディレクトリパス（表示中でなければ `null`）。
 * @property isPendingBatchDelete 複数選択項目の削除確認ダイアログを表示中かどうか。
 * @property pendingEditItem コース・言語ペア編集ダイアログで対象になっている教材（表示中でなければ `null`）。
 * @property pendingEditDirectory ディレクトリ一括のコース・言語ペア編集ダイアログ状態（表示中でなければ `null`）。
 * @property pendingImportConfig 取り込み・同期フォルダ選択後の共通設定ダイアログ状態（表示中でなければ `null`）。
 * @property selectedDirectoryName フォルダ指定で選択されたディレクトリ名。
 * @property collapsedDirectories 折りたたまれているディレクトリ名の集合。
 * @property enabledLanguages 設定画面で有効化された選択肢の言語一覧（既定は日本語・英語）。
 * @property currentDirectoryPath 現在開いているディレクトリの相対パス（ルート階層の場合は `null`）。
 * @property selectedDirectoryPaths 長押し複数選択モードで選択されているディレクトリパスの集合。
 * @property selectedItemIds 長押し複数選択モードで選択されている教材 ID の集合。
 * @property selectedTab 現在選択されているビュータブ（フォルダ / ファイル / 再生リスト）。
 * @property fileSearchQuery ファイルビュータブのファイル名検索キーワード。
 * @property fileTargetLanguageFilter ファイルビュータブの対象言語フィルタ（`null` なら全言語）。
 * @property fileSortOption ファイルビュータブのソート種別（名称順 / アクセス日時 / 完了度）。
 * @property selectedPlaylistId 再生リストビュータブで展開中のプレイリスト ID（一覧表示時は `null`）。
 * @property selectedPlaylistCategory 再生リストビュータブで展開中のスマートリスト種別（互換用・一覧表示時は `null`）。
 * @property playlists DB に保存されているプレイリスト一覧（ユーザー作成プレイリスト＋並び順・アイテム順が上書きされた組み込みプレイリスト）。
 * @property favoriteItemIds お気に入りに登録されている教材 ID の集合。
 * @property queuedItemIds 次に再生するキューに登録されている教材 ID の順序付き一覧。
 */
data class LibraryUiState(
    val items: List<LibraryItemUiModel> = emptyList(),
    val courseFolders: List<LibraryCourseFolder> = emptyList(),
    val importState: LibraryImportUiState = LibraryImportUiState.Idle,
    val transcriptImportState: TranscriptImportUiState = TranscriptImportUiState.Idle,
    val syncState: DriveSyncUiState = DriveSyncUiState.Idle,
    val miniPlayer: MiniPlayerUiState? = null,
    val pendingDeleteItem: AudioItem? = null,
    val pendingDeleteDirectoryPath: String? = null,
    val isPendingBatchDelete: Boolean = false,
    val pendingEditItem: AudioItem? = null,
    val pendingEditDirectory: PendingDirectoryEdit? = null,
    val pendingImportConfig: PendingImportConfig? = null,
    val selectedDirectoryName: String? = null,
    val collapsedDirectories: Set<String?> = emptySet(),
    val enabledLanguages: List<LanguageTag> = listOf(LanguageTag("ja"), LanguageTag("en")),
    val showLanguageOnboardingDialog: Boolean = false,
    val currentDirectoryPath: String? = null,
    val selectedDirectoryPaths: Set<String> = emptySet(),
    val selectedItemIds: Set<MediaId> = emptySet(),
    val selectedTab: LibraryViewTab = LibraryViewTab.FOLDERS,
    val fileSearchQuery: String = "",
    val fileTargetLanguageFilter: LanguageTag? = null,
    val fileSortOption: LibraryFileSortOption = LibraryFileSortOption.NAME,
    val selectedPlaylistId: PlaylistId? = null,
    val selectedPlaylistCategory: LibraryPlaylistCategory? = selectedPlaylistId?.let(LibraryPlaylistCategory::fromPlaylistId),
    val playlists: List<LibraryPlaylist> = emptyList(),
    val favoriteItemIds: Set<MediaId> = items
        .filter { it.isFavorite }
        .mapTo(linkedSetOf()) { it.item.id },
    val queuedItemIds: List<MediaId> = items
        .filter { it.queuedAtEpochMs > 0L }
        .sortedWith(
            compareBy<LibraryItemUiModel> { it.queuedAtEpochMs }
                .thenBy { it.item.id.value },
        )
        .map { it.item.id },
) {
    companion object {
        /** 最近開いたファイル欄およびよくアクセスするファイル欄に表示する最大件数。 */
        const val MAX_RECENT_ITEMS = 10
    }

    /**
     * 直近に開いた（アクセス日時が記録されている）最大 10 件の教材一覧（新しい順）。
     */
    val recentItems: List<LibraryItemUiModel>
        get() = items
            .filter { it.lastAccessedAtEpochMs != null }
            .sortedWith(
                compareByDescending<LibraryItemUiModel> { it.lastAccessedAtEpochMs ?: 0L }
                    .thenBy { it.item.id.value },
            )
            .take(MAX_RECENT_ITEMS)

    /**
     * ファイルビュータブでフィルタ可能な対象言語の一覧（登録済み教材に含まれる対象言語の昇順）。
     */
    val availableTargetLanguages: List<LanguageTag>
        get() = items
            .map { LanguageTag(it.item.languagePair.target.value.lowercase()) }
            .distinct()
            .sortedBy { it.value }

    /**
     * ファイルビュータブでファイル名キーワードと対象言語でフィルタし、[fileSortOption] に応じて並べた全ファイル一覧。
     */
    val filteredAllFiles: List<LibraryItemUiModel>
        get() {
            val normalizedQuery = fileSearchQuery.trim()
            val targetFilter = fileTargetLanguageFilter?.value?.lowercase()
            val filtered = items.filter { model ->
                val matchesQuery = normalizedQuery.isEmpty() ||
                    model.item.title.contains(normalizedQuery, ignoreCase = true) ||
                    (model.item.course?.contains(normalizedQuery, ignoreCase = true) == true)
                val matchesLang = targetFilter == null ||
                    model.item.languagePair.target.value.lowercase() == targetFilter
                matchesQuery && matchesLang
            }
            return when (fileSortOption) {
                LibraryFileSortOption.NAME -> filtered.sortedWith { a, b ->
                    val orderA = a.fileSortOrder
                    val orderB = b.fileSortOrder
                    if (orderA != null && orderB != null && orderA != orderB) {
                        return@sortedWith orderA.compareTo(orderB)
                    }
                    if (orderA != null && orderB == null) return@sortedWith -1
                    if (orderA == null && orderB != null) return@sortedWith 1
                    val titleCmp = compareFileNamesAscending(a.item.title, b.item.title)
                    if (titleCmp != 0) titleCmp else a.item.id.value.compareTo(b.item.id.value)
                }

                LibraryFileSortOption.ACCESS_TIME -> filtered.sortedWith { a, b ->
                    val timeA = a.lastAccessedAtEpochMs ?: 0L
                    val timeB = b.lastAccessedAtEpochMs ?: 0L
                    val timeCmp = timeB.compareTo(timeA)
                    if (timeCmp != 0) {
                        timeCmp
                    } else {
                        val titleCmp = compareFileNamesAscending(a.item.title, b.item.title)
                        if (titleCmp != 0) titleCmp else a.item.id.value.compareTo(b.item.id.value)
                    }
                }

                LibraryFileSortOption.COMPLETION -> filtered.sortedWith { a, b ->
                    val compA = if (a.isListened) 1.0f else a.maxProgressFraction
                    val compB = if (b.isListened) 1.0f else b.maxProgressFraction
                    val compCmp = compB.compareTo(compA)
                    if (compCmp != 0) {
                        compCmp
                    } else {
                        val listenedCmp = b.isListened.compareTo(a.isListened)
                        if (listenedCmp != 0) {
                            listenedCmp
                        } else {
                            val titleCmp = compareFileNamesAscending(a.item.title, b.item.title)
                            if (titleCmp != 0) titleCmp else a.item.id.value.compareTo(b.item.id.value)
                        }
                    }
                }
            }
        }

    /**
     * カスタム順序 [customOrderIds] が指定されていればそれを優先し、未指定の残りのアイテムは元の順序で後ろに並べる。
     */
    private fun applyCustomOrder(
        defaultOrdered: List<LibraryItemUiModel>,
        customOrderIds: List<MediaId>,
    ): List<LibraryItemUiModel> {
        if (customOrderIds.isEmpty()) return defaultOrdered
        val indexMap = customOrderIds.withIndex().associate { (idx, id) -> id to idx }
        val customPart = defaultOrdered
            .filter { it.item.id in indexMap }
            .sortedBy { indexMap.getValue(it.item.id) }
        val remainingPart = defaultOrdered.filterNot { it.item.id in indexMap }
        return customPart + remainingPart
    }

    /**
     * 指定された再生リストカテゴリ [category] に該当する教材一覧を返す（カスタム並び替えがあれば反映する）。
     *
     * 「最近開いたファイル」と「よくアクセスするファイル」は最大 [MAX_RECENT_ITEMS]（10）件までに制限する。
     */
    fun playlistItemsFor(category: LibraryPlaylistCategory): List<LibraryItemUiModel> {
        val rawList = when (category) {
            LibraryPlaylistCategory.RECENTLY_OPENED ->
                items
                    .filter { it.lastAccessedAtEpochMs != null }
                    .sortedWith(
                        compareByDescending<LibraryItemUiModel> { it.lastAccessedAtEpochMs ?: 0L }
                            .thenBy { it.item.id.value },
                    )
                    .take(MAX_RECENT_ITEMS)

            LibraryPlaylistCategory.FREQUENTLY_ACCESSED ->
                items
                    .filter {
                        it.accessCount > 0 ||
                            it.isListened ||
                            it.maxPositionMs > 0L ||
                            it.positionMs > 0L ||
                            it.lastAccessedAtEpochMs != null
                    }
                    .sortedWith(
                        compareByDescending<LibraryItemUiModel> { it.accessCount }
                            .thenByDescending { it.isListened }
                            .thenByDescending { it.maxProgressFraction }
                            .thenByDescending { it.lastAccessedAtEpochMs ?: 0L }
                            .thenBy { it.item.id.value },
                    )
                    .take(MAX_RECENT_ITEMS)

            LibraryPlaylistCategory.FAVORITES ->
                items
                    .filter { it.isFavorite || it.item.id in favoriteItemIds }
                    .sortedWith { a, b ->
                        val titleCmp = compareFileNamesAscending(a.item.title, b.item.title)
                        if (titleCmp != 0) titleCmp else a.item.id.value.compareTo(b.item.id.value)
                    }

            LibraryPlaylistCategory.UP_NEXT_QUEUE -> {
                val byId = items.associateBy { it.item.id }
                queuedItemIds.mapNotNull { byId[it] }
            }
        }
        val persisted = playlists.firstOrNull { it.id == category.playlistId }
        val ordered = if (persisted != null && persisted.itemIds.isNotEmpty()) {
            applyCustomOrder(rawList, persisted.itemIds)
        } else {
            rawList
        }
        return when (category) {
            LibraryPlaylistCategory.RECENTLY_OPENED,
            LibraryPlaylistCategory.FREQUENTLY_ACCESSED,
            -> ordered.take(MAX_RECENT_ITEMS)

            else -> ordered
        }
    }

    /**
     * 指定された [playlistId] に属する教材一覧を返す。
     */
    fun playlistItemsFor(playlistId: PlaylistId): List<LibraryItemUiModel> {
        val builtIn = LibraryPlaylistCategory.fromPlaylistId(playlistId)
        if (builtIn != null) {
            return playlistItemsFor(builtIn)
        }
        val playlist = playlists.firstOrNull { it.id == playlistId } ?: return emptyList()
        val byId = items.associateBy { it.item.id }
        return playlist.itemIds.mapNotNull { byId[it] }
    }

    /**
     * 「再生リスト」タブに表示するすべてのプレイリスト（組み込みスマートリスト＋ユーザー作成プレイリスト、並び順反映済み）。
     */
    val playlistEntries: List<LibraryPlaylistEntryUiModel>
        get() {
            val persistedById = playlists.associateBy { it.id }
            val builtInEntries = LibraryPlaylistCategory.BUILT_IN_CATEGORIES.mapIndexed { defaultIdx, category ->
                val persisted = persistedById[category.playlistId]
                LibraryPlaylistEntryUiModel(
                    id = category.playlistId,
                    name = persisted?.name.orEmpty(),
                    builtInCategory = category,
                    sortOrder = persisted?.sortOrder ?: defaultIdx,
                    items = playlistItemsFor(category),
                )
            }
            val byId = items.associateBy { it.item.id }
            val userEntries = playlists
                .filterNot { it.isBuiltIn || LibraryPlaylistCategory.fromPlaylistId(it.id) != null }
                .mapIndexed { idx, playlist ->
                    LibraryPlaylistEntryUiModel(
                        id = playlist.id,
                        name = playlist.name,
                        builtInCategory = null,
                        sortOrder = playlist.sortOrder.takeIf { it >= 0 }
                            ?: (LibraryPlaylistCategory.BUILT_IN_CATEGORIES.size + idx),
                        items = playlist.itemIds.mapNotNull { byId[it] },
                    )
                }
            return (builtInEntries + userEntries).sortedWith(
                compareBy<LibraryPlaylistEntryUiModel> { it.sortOrder }
                    .thenBy { !it.isBuiltIn }
                    .thenBy { it.id.value },
            )
        }

    /**
     * ユーザーが作成したプレイリストのみの一覧（プレイリスト追加ダイアログ用）。
     */
    val userPlaylists: List<LibraryPlaylistEntryUiModel>
        get() = playlistEntries.filterNot { it.isBuiltIn }

    /**
     * 現在「再生リスト」タブで開いているプレイリスト（一覧表示時は `null`）。
     */
    val activePlaylist: LibraryPlaylistEntryUiModel?
        get() {
            val targetId = selectedPlaylistId ?: selectedPlaylistCategory?.playlistId ?: return null
            return playlistEntries.firstOrNull { it.id == targetId }
        }

    /** 複数選択モード中かどうか。 */
    val isSelectionMode: Boolean
        get() = selectedDirectoryPaths.isNotEmpty() || selectedItemIds.isNotEmpty()

    /** 複数選択モードで選択されている項目（ディレクトリ数＋ファイル数）の合計。 */
    val selectedCount: Int
        get() = selectedDirectoryPaths.size + selectedItemIds.size

    /** 削除確認ダイアログを表示すべきかどうか。 */
    val hasPendingDeleteConfirmation: Boolean
        get() = pendingDeleteItem != null || pendingDeleteDirectoryPath != null || isPendingBatchDelete

    /**
     * 各教材の実効ディレクトリパス（先頭・末尾の `/` を除いた相対パス、未分類なら `null`）を返す。
     */
    internal fun effectivePathOf(itemModel: LibraryItemUiModel): String? {
        val raw = itemModel.item.course ?: selectedDirectoryName
        return raw?.trim('/')?.takeIf { it.isNotBlank() }
    }

    /**
     * 指定されたディレクトリパス [dirPath] 配下（サブディレクトリを含む）の教材一覧を返す。
     */
    fun itemsInDirectoryTree(dirPath: String): List<LibraryItemUiModel> {
        val normalized = dirPath.trim('/')
        if (normalized.isEmpty()) return emptyList()
        val prefix = "$normalized/"
        return items.filter { model ->
            val path = effectivePathOf(model)
            path == normalized || (path != null && path.startsWith(prefix))
        }
    }

    /**
     * 指定されたディレクトリパス [dirPath] が「実フォルダ」（ファイルシステム上のディレクトリを参照するもの）かどうかを返す。
     *
     * `false` の場合はアプリ内でファイルをグループ分けする「仮想フォルダ」を表す。
     */
    fun isRealFolder(dirPath: String?): Boolean {
        val normalized = dirPath?.trim('/')?.takeIf { it.isNotBlank() } ?: return false
        if (normalized == LibraryCourseFolder.DEFAULT_IMPORTED_COURSE || normalized == "imported") {
            return false
        }
        val matchingFolders = courseFolders.filter { folder ->
            val path = folder.coursePath.trim('/')
            path == normalized || path.startsWith("$normalized/") || normalized.startsWith("$path/")
        }
        if (matchingFolders.isNotEmpty()) {
            return matchingFolders.any { folder ->
                folder.kind == LibraryFolderKind.LOCAL_DIRECTORY || !folder.treeUri.isNullOrBlank()
            }
        }
        val matchingItems = itemsInDirectoryTree(normalized)
        return matchingItems.any { model ->
            model.item.storageState == AudioStorageState.LOCAL_REFERENCE ||
                !model.item.localFilePath.isNullOrBlank()
        }
    }

    /**
     * ファイル追加時の格納先フォルダ選択プルダウンに表示する既存フォルダ名の一覧。
     *
     * 先頭には必ず既定の仮想フォルダ `"default"`（[LibraryCourseFolder.DEFAULT_IMPORTED_COURSE]）を置き、
     * 続いて既存のフォルダ名を自然昇順で並べる。
     */
    val availableImportFolders: List<String>
        get() {
            val defaultFolder = LibraryCourseFolder.DEFAULT_IMPORTED_COURSE
            val folderNames = linkedSetOf<String>()
            for (folder in courseFolders) {
                val path = folder.coursePath.trim('/').takeIf { it.isNotBlank() } ?: continue
                folderNames += path
            }
            for (model in items) {
                val path = effectivePathOf(model) ?: continue
                folderNames += path
            }
            val sortedOthers = folderNames
                .filterNot { it == defaultFolder }
                .sortedWith(::compareFileNamesAscending)
            return listOf(defaultFolder) + sortedOthers
        }

    /**
     * 複数選択モードで対象となる全教材 ID の一覧（選択中のディレクトリ配下の教材と選択中の教材、重複なし）。
     */
    val selectedTargetItemIds: List<MediaId>
        get() {
            val fromDirs = selectedDirectoryPaths.flatMap { path ->
                itemsInDirectoryTree(path).map { it.item.id }
            }
            return (fromDirs + selectedItemIds).distinct()
        }

    /**
     * 複数選択されている対象教材が 1 件以上あり、そのすべてがお気に入りに登録済みかどうか。
     */
    val areAllSelectedFavorite: Boolean
        get() {
            val targetIds = selectedTargetItemIds
            return targetIds.isNotEmpty() && targetIds.all { it in favoriteItemIds }
        }

    /**
     * 現在の削除確認ダイアログで削除対象となる全 [AudioItem] の一覧（重複なし）。
     */
    val pendingDeleteTargetItems: List<AudioItem>
        get() = when {
            pendingDeleteItem != null -> listOf(pendingDeleteItem)

            pendingDeleteDirectoryPath != null -> {
                itemsInDirectoryTree(pendingDeleteDirectoryPath).map { it.item }
            }

            isPendingBatchDelete -> {
                val fromDirs = selectedDirectoryPaths.flatMap { path ->
                    itemsInDirectoryTree(path).map { it.item }
                }
                val fromItems = items.filter { it.item.id in selectedItemIds }.map { it.item }
                (fromDirs + fromItems).distinctBy { it.id }
            }

            else -> emptyList()
        }

    /**
     * 現在開いている階層（[currentDirectoryPath]）の直下にあるサブディレクトリ一覧（ディレクトリ名の自然昇順）。
     */
    val currentDirectories: List<LibraryDirectoryUiModel>
        get() {
            val currentPath = currentDirectoryPath?.trim('/')?.takeIf { it.isNotBlank() }
            val grouped = linkedMapOf<String, MutableList<LibraryItemUiModel>>()
            for (folder in courseFolders) {
                val path = folder.coursePath.trim('/').takeIf { it.isNotBlank() } ?: continue
                if (currentPath == null) {
                    val topSegment = path.substringBefore('/')
                    grouped.getOrPut(topSegment) { mutableListOf() }
                } else if (path.startsWith("$currentPath/")) {
                    val remainder = path.removePrefix("$currentPath/")
                    val childSegment = remainder.substringBefore('/')
                    if (childSegment.isNotBlank()) {
                        val childFullPath = "$currentPath/$childSegment"
                        grouped.getOrPut(childFullPath) { mutableListOf() }
                    }
                }
            }
            for (itemModel in items) {
                val path = effectivePathOf(itemModel) ?: continue
                if (currentPath == null) {
                    val topSegment = path.substringBefore('/')
                    grouped.getOrPut(topSegment) { mutableListOf() }.add(itemModel)
                } else if (path.startsWith("$currentPath/")) {
                    val remainder = path.removePrefix("$currentPath/")
                    val childSegment = remainder.substringBefore('/')
                    if (childSegment.isNotBlank()) {
                        val childFullPath = "$currentPath/$childSegment"
                        grouped.getOrPut(childFullPath) { mutableListOf() }.add(itemModel)
                    }
                }
            }
            return grouped.entries
                .sortedWith { a, b ->
                    val nameA = a.key.substringAfterLast('/')
                    val nameB = b.key.substringAfterLast('/')
                    compareFileNamesAscending(nameA, nameB)
                }
                .map { (dirFullPath, dirItems) ->
                    val sortedItems = dirItems.sortedWith { a, b ->
                        val titleCmp = compareFileNamesAscending(a.item.title, b.item.title)
                        if (titleCmp != 0) titleCmp else a.item.id.value.compareTo(b.item.id.value)
                    }
                    LibraryDirectoryUiModel(
                        name = dirFullPath,
                        items = sortedItems,
                        isExpanded = dirFullPath !in collapsedDirectories,
                        displayName = dirFullPath.substringAfterLast('/'),
                        isRealFolder = isRealFolder(dirFullPath),
                    )
                }
        }

    /**
     * 現在開いている階層（[currentDirectoryPath]）の直下にある音声ファイル一覧（ファイル名の自然昇順）。
     */
    val currentFiles: List<LibraryItemUiModel>
        get() {
            val currentPath = currentDirectoryPath?.trim('/')?.takeIf { it.isNotBlank() }
            return items
                .filter { effectivePathOf(it) == currentPath }
                .sortedWith { a, b ->
                    val titleCmp = compareFileNamesAscending(a.item.title, b.item.title)
                    if (titleCmp != 0) titleCmp else a.item.id.value.compareTo(b.item.id.value)
                }
        }

    /**
     * 現在の階層で最後にアクセスされた `Listened` ファイルが 1〜2 件だけ上部に残る `LazyColumn` インデックス。
     */
    val initialVisibleLazyIndexInCurrentDirectory: Int?
        get() {
            val lastListenedIdx = currentFiles
                .withIndex()
                .filter { it.value.isListened }
                .maxWithOrNull(
                    compareBy<IndexedValue<LibraryItemUiModel>> { it.value.lastAccessedAtEpochMs ?: 0L }
                        .thenBy { it.index },
                )
                ?.index ?: return null
            val targetFileIdx = (lastListenedIdx - 1).coerceAtLeast(0)
            return currentDirectories.size + targetFileIdx
        }

    /**
     * ディレクトリ（フォルダ）単位にグループ化し、各ディレクトリ内をファイル名の自然昇順でソートした教材一覧。
     */
    val directories: List<LibraryDirectoryUiModel>
        get() {
            val grouped = linkedMapOf<String?, MutableList<LibraryItemUiModel>>()
            for (itemModel in items) {
                val dirName = itemModel.item.course ?: selectedDirectoryName
                grouped.getOrPut(dirName) { mutableListOf() }.add(itemModel)
            }
            for (folder in courseFolders) {
                val folderPath = folder.coursePath.trim('/').takeIf { it.isNotBlank() }
                if (folderPath != null) {
                    grouped.getOrPut(folderPath) { mutableListOf() }
                }
            }
            return grouped.map { (dirName, dirItems) ->
                val sortedItems = dirItems.sortedWith { a, b ->
                    val titleCmp = compareFileNamesAscending(a.item.title, b.item.title)
                    if (titleCmp != 0) titleCmp else a.item.id.value.compareTo(b.item.id.value)
                }
                LibraryDirectoryUiModel(
                    name = dirName,
                    items = sortedItems,
                    isExpanded = dirName !in collapsedDirectories,
                    isRealFolder = isRealFolder(dirName),
                )
            }
        }

    /**
     * 指定したディレクトリ（省略時は展開中で `Listened` ファイルを持つ最初のディレクトリ）について、
     * 最後にアクセスされた `Listened` ファイルが 1〜2 件だけ上部に表示されるフラット `LazyColumn` インデックスを返す。
     */
    fun targetScrollLazyIndexForDirectory(
        directoryName: String? = directories.firstOrNull {
            it.isExpanded && it.initialVisibleItemIndexInDirectory != null
        }?.name,
    ): Int? {
        var flatIndex = 0
        for (dir in directories) {
            if (dir.name == directoryName) {
                if (!dir.isExpanded) return null
                val offsetInDir = dir.initialVisibleItemIndexInDirectory ?: return null
                // ディレクトリヘッダの分 (+1) を進めて対象ファイル行を先頭に表示する
                return flatIndex + 1 + offsetInDir
            }
            flatIndex += 1 + if (dir.isExpanded) dir.items.size else 0
        }
        return null
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

    /** 指定された教材 [id] に対して SAF ファイルピッカーを開き、文字起こし・字幕ファイルを取り込む。 */
    data class RequestImportTranscriptForItem(val id: MediaId) : LibraryEvent

    /** 指定されたフォルダ（ディレクトリ）名と配下の URI 一覧（および SAF Tree URI）を取り込む。 */
    data class ImportDirectory(
        val directoryName: String?,
        val uris: List<Uri>,
        val treeUri: Uri? = null,
    ) : LibraryEvent

    /** SAF で選択された URI の一覧を取り込む。 */
    data class ImportUris(val uris: List<Uri>) : LibraryEvent

    /** SAF で選択された文字起こし・字幕 URI を単体で取り込む。 */
    data class ImportTranscriptUri(val uri: Uri) : LibraryEvent

    /** 指定された教材 [id] に対して選択された文字起こし・字幕 URI を直接取り込む。 */
    data class ImportTranscriptForItem(val id: MediaId, val uri: Uri) : LibraryEvent

    /** Google Drive の `ChunkFlow/` フォルダと同期する（F1.5）。 */
    data object SyncFromDrive : LibraryEvent

    /** SAF フォルダピッカーを開いて Drive 同期フォルダを選択する。 */
    data object RequestSelectDriveSyncFolder : LibraryEvent

    /** Drive 同期用のフォルダ（Tree URI）が選択されたときに設定ダイアログを開く。 */
    data class SelectDriveSyncFolder(val directoryName: String?, val treeUri: Uri) : LibraryEvent

    /** 取り込み・同期フォルダの設定ダイアログ（コース名・対象言語・ベース言語）を確定して実行する。 */
    data class ConfirmImportConfig(
        val course: String?,
        val languages: LanguagePair,
    ) : LibraryEvent

    /** 取り込み・同期フォルダの設定ダイアログをキャンセルする。 */
    data object CancelImportConfig : LibraryEvent

    /** 取り込み・同期の完了・エラーバナーを閉じる。 */
    data object DismissImportStatus : LibraryEvent

    /** Google Drive 同期バナーを閉じる。 */
    data object DismissSyncStatus : LibraryEvent

    /** ディレクトリ（フォルダ）セクションの展開・折りたたみを切り替える。 */
    data class ToggleDirectory(val directoryName: String?) : LibraryEvent

    /** 指定されたディレクトリ階層 [directoryPath] を開いて掘り下げる。 */
    data class OpenDirectory(val directoryPath: String?) : LibraryEvent

    /** 1 つ上の親ディレクトリ階層へ戻る。 */
    data object NavigateUp : LibraryEvent

    /** ディレクトリ [directoryPath] の複数選択状態を切り替える。 */
    data class ToggleDirectorySelection(val directoryPath: String) : LibraryEvent

    /** 教材 [id] の複数選択状態を切り替える。 */
    data class ToggleItemSelection(val id: MediaId) : LibraryEvent

    /** 複数選択状態をすべて解除する。 */
    data object ClearSelection : LibraryEvent

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

    /** ディレクトリ（コース）単位のコース名・言語ペア一括編集ダイアログを開く。 */
    data class RequestEditDirectoryMetadata(val directoryName: String?) : LibraryEvent

    /** ディレクトリ（コース）単位のコース名・言語ペア一括編集を確定して保存する。 */
    data class ConfirmEditDirectoryMetadata(
        val currentCourse: String?,
        val newCourse: String?,
        val languages: LanguagePair,
    ) : LibraryEvent

    /** ディレクトリ（コース）単位の編集ダイアログをキャンセルする。 */
    data object CancelEditDirectoryMetadata : LibraryEvent

    /** 教材の削除確認ダイアログを開く。 */
    data class RequestDelete(val item: AudioItem) : LibraryEvent

    /** ディレクトリ単位の削除確認ダイアログを開く。 */
    data class RequestDeleteDirectory(val directoryPath: String) : LibraryEvent

    /** 複数選択されているディレクトリ・ファイルの削除確認ダイアログを開く。 */
    data object RequestDeleteSelected : LibraryEvent

    /** 削除確認ダイアログで削除を実行する。 */
    data object ConfirmDelete : LibraryEvent

    /** 削除確認ダイアログをキャンセルする。 */
    data object CancelDelete : LibraryEvent

    /** ミニプレイヤーの再生 / 一時停止を切り替える。 */
    data object ToggleMiniPlayerPlayPause : LibraryEvent

    /** 登録済みローカルフォルダを再スキャンする。 */
    data object RescanLocalDirectories : LibraryEvent

    /** 新しい仮想フォルダ（ファイル単位取り込みの整理用ディレクトリ）を作成する。 */
    data class CreateVirtualFolder(
        val folderName: String,
        val languages: LanguagePair,
    ) : LibraryEvent

    /** 複数の教材を指定したコース（フォルダ）へ移動する。 */
    data class MoveItemsToCourse(
        val ids: Set<MediaId>,
        val targetCourse: String?,
    ) : LibraryEvent

    /** Google Drive 上の未ダウンロード教材（`DRIVE_REMOTE_ONLY`）の音声ファイルをオンデマンドでダウンロードする。 */
    data class DownloadDriveAudio(val id: MediaId) : LibraryEvent

    /** Google Drive 由来教材のローカル音声コピーを削除し、`DRIVE_REMOTE_ONLY` に戻す。 */
    data class DeleteLocalAudioCopy(val id: MediaId) : LibraryEvent

    /** 全体設定で選択可能な有効言語の ON / OFF を切り替える（最低 1 言語は維持）。 */
    data class ToggleEnabledLanguage(val language: LanguageTag) : LibraryEvent

    /** 初回起動時の言語選択オンボーディングを完了済みにする。 */
    data object CompleteLanguageOnboarding : LibraryEvent

    /** ライブラリの表示タブ（フォルダ / ファイル / 再生リスト）を切り替える。 */
    data class SelectTab(val tab: LibraryViewTab) : LibraryEvent

    /** ファイルビューのファイル名検索キーワードを更新する。 */
    data class UpdateFileSearchQuery(val query: String) : LibraryEvent

    /** ファイルビューの対象言語フィルタを切り替える（`null` ですべて表示）。 */
    data class SelectFileTargetLanguageFilter(val language: LanguageTag?) : LibraryEvent

    /** ファイルビューのソート順を切り替える。 */
    data class SelectFileSortOption(val sortOption: LibraryFileSortOption) : LibraryEvent

    /** 再生リストビューで開くスマートプレイリストカテゴリを選択する（`null` で一覧へ戻る）。 */
    data class SelectPlaylistCategory(val category: LibraryPlaylistCategory?) : LibraryEvent

    /** 再生リストビューで開くプレイリストを選択する（`null` で一覧へ戻る）。 */
    data class SelectPlaylist(val playlistId: PlaylistId?) : LibraryEvent

    /** 新しいユーザー作成プレイリストを作成する。 */
    data class CreatePlaylist(val name: String) : LibraryEvent

    /** 新しいユーザー作成プレイリストを作成し、現在選択されている教材を追加する。 */
    data class CreatePlaylistAndAddSelected(val name: String) : LibraryEvent

    /** ユーザー作成プレイリストを削除する。 */
    data class DeletePlaylist(val playlistId: PlaylistId) : LibraryEvent

    /** 現在選択されている教材を指定したプレイリストに追加し、選択モードを終了する。 */
    data class AddSelectedItemsToPlaylist(val playlistId: PlaylistId) : LibraryEvent

    /** 指定したプレイリストから教材を除外する。 */
    data class RemoveItemFromPlaylist(val playlistId: PlaylistId, val mediaId: MediaId) : LibraryEvent

    /** 再生リストタブ内のプレイリスト一覧の並び順を変更する。 */
    data class MovePlaylist(val fromIndex: Int, val toIndex: Int) : LibraryEvent

    /** 指定したプレイリスト内の教材アイテムの並び順を変更する。 */
    data class MovePlaylistItem(val playlistId: PlaylistId, val fromIndex: Int, val toIndex: Int) : LibraryEvent

    /** ファイルタブ内の教材アイテムの並び順を変更する。 */
    data class MoveFileTabItem(val fromIndex: Int, val toIndex: Int) : LibraryEvent

    /** 教材 [id] のお気に入り登録状態を切り替える。 */
    data class ToggleFavorite(val id: MediaId) : LibraryEvent

    /** 複数選択されている教材のお気に入り状態を一括で切り替える（すべてお気に入りなら解除、それ以外はすべて追加）。 */
    data object ToggleFavoriteSelected : LibraryEvent

    /** 教材 [id] の「次に再生するキュー」への登録状態を切り替える。 */
    data class ToggleQueueItem(val id: MediaId) : LibraryEvent
}

private data class ItemAnalysisSummary(
    val chunkCount: Int = 0,
    val hasTranscript: Boolean = false,
    val transcriptStatus: LibraryTranscriptStatus = LibraryTranscriptStatus.NONE,
    val transcriptProgressPercent: Int = 0,
    val isAnalyzing: Boolean = false,
    val isTranscribing: Boolean = false,
)

private data class LibraryRepositorySnapshot(
    val items: List<AudioItem> = emptyList(),
    val courseFolders: List<LibraryCourseFolder> = emptyList(),
    val playlists: List<LibraryPlaylist> = emptyList(),
    val itemStates: Map<MediaId, ItemState> = emptyMap(),
    val summaries: Map<MediaId, ItemAnalysisSummary> = emptyMap(),
    val missingAudioIds: Set<MediaId> = emptySet(),
    val enabledLanguages: List<LanguageTag> = listOf(LanguageTag("ja"), LanguageTag("en")),
    val showLanguageOnboardingDialog: Boolean = false,
)

private data class DialogAndImportSnapshot(
    val importState: LibraryImportUiState = LibraryImportUiState.Idle,
    val transcriptImportState: TranscriptImportUiState = TranscriptImportUiState.Idle,
    val syncState: DriveSyncUiState = DriveSyncUiState.Idle,
    val pendingDeleteItem: AudioItem? = null,
    val pendingDeleteDirectoryPath: String? = null,
    val isPendingBatchDelete: Boolean = false,
    val pendingEditItem: AudioItem? = null,
    val pendingEditDirectory: PendingDirectoryEdit? = null,
    val pendingImportConfig: PendingImportConfig? = null,
)

private data class NavigationAndSelectionSnapshot(
    val selectedDirectoryName: String? = null,
    val collapsedDirectories: Set<String?> = emptySet(),
    val currentDirectoryPath: String? = null,
    val selectedDirectoryPaths: Set<String> = emptySet(),
    val selectedItemIds: Set<MediaId> = emptySet(),
    val selectedTab: LibraryViewTab = LibraryViewTab.FOLDERS,
    val fileSearchQuery: String = "",
    val fileTargetLanguageFilter: LanguageTag? = null,
    val fileSortOption: LibraryFileSortOption = LibraryFileSortOption.NAME,
    val selectedPlaylistId: PlaylistId? = null,
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

private fun orderEnabledLanguages(enabledSet: Set<LanguageTag>): List<LanguageTag> {
    val nonEmpty = enabledSet.ifEmpty { UserSettings.DEFAULT_ENABLED_LANGUAGES }
    val priority = listOf(LanguageTag("ja"), LanguageTag("en"))
    val head = priority.filter { it in nonEmpty }
    val tail = nonEmpty.filterNot { it in priority }.sortedBy { it.value }
    return head + tail
}

/**
 * ライブラリ画面の ViewModel（F1.1〜F2.4、F7.1、§7.5.1）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val playbackController: PlaybackController,
    private val analysisRepository: AnalysisRepository,
    private val userSettingsRepository: UserSettingsRepository,
) : ViewModel() {

    constructor(
        libraryRepository: LibraryRepository,
        playbackController: PlaybackController,
        analysisRepository: AnalysisRepository = EmptyAnalysisRepository,
    ) : this(
        libraryRepository = libraryRepository,
        playbackController = playbackController,
        analysisRepository = analysisRepository,
        userSettingsRepository = FakeUserSettingsRepository(),
    )

    private val importState = MutableStateFlow<LibraryImportUiState>(LibraryImportUiState.Idle)
    private val transcriptImportState =
        MutableStateFlow<TranscriptImportUiState>(TranscriptImportUiState.Idle)
    private val syncState = MutableStateFlow<DriveSyncUiState>(DriveSyncUiState.Idle)
    private val pendingDeleteItem = MutableStateFlow<AudioItem?>(null)
    private val pendingDeleteDirectoryPath = MutableStateFlow<String?>(null)
    private val isPendingBatchDelete = MutableStateFlow(false)
    private val pendingEditItem = MutableStateFlow<AudioItem?>(null)
    private val pendingEditDirectory = MutableStateFlow<PendingDirectoryEdit?>(null)
    private val pendingImportConfig = MutableStateFlow<PendingImportConfig?>(null)
    private val selectedDirectoryName = MutableStateFlow<String?>(null)
    private val collapsedDirectories = MutableStateFlow<Set<String?>>(emptySet())
    private val currentDirectoryPath = MutableStateFlow<String?>(null)
    private val selectedDirectoryPaths = MutableStateFlow<Set<String>>(emptySet())
    private val selectedItemIds = MutableStateFlow<Set<MediaId>>(emptySet())
    private val selectedTab = MutableStateFlow(LibraryViewTab.FOLDERS)
    private val fileSearchQuery = MutableStateFlow("")
    private val fileTargetLanguageFilter = MutableStateFlow<LanguageTag?>(null)
    private val fileSortOption = MutableStateFlow(LibraryFileSortOption.NAME)
    private val selectedPlaylistId = MutableStateFlow<PlaylistId?>(null)
    private var defaultLanguagePair: LanguagePair = LanguagePair(
        target = LanguageTag("en"),
        base = LanguageTag("ja"),
    )
    private var importJob: Job? = null
    private var syncJob: Job? = null

    init {
        viewModelScope.launch {
            userSettingsRepository.settings.collect { settings ->
                defaultLanguagePair = settings.defaultLanguagePair
            }
        }
    }

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
                        analysisRepository.observeLookaheadStatus(item.id),
                    ) { chunks, transcripts, statusMap, lookaheadStatus ->
                        val transcribedCount = chunks.count { chunk ->
                            transcripts[chunk.id]?.text?.isNotBlank() == true
                        }
                        val transcriptStatus = when {
                            chunks.isNotEmpty() && transcribedCount >= chunks.size -> {
                                LibraryTranscriptStatus.COMPLETED
                            }

                            transcribedCount > 0 -> {
                                LibraryTranscriptStatus.PARTIAL
                            }

                            transcripts.values.any { it.text.isNotBlank() } -> {
                                LibraryTranscriptStatus.PARTIAL
                            }

                            chunks.any { it.origin == ChunkOrigin.IMPORTED } -> {
                                LibraryTranscriptStatus.COMPLETED
                            }

                            else -> {
                                LibraryTranscriptStatus.NONE
                            }
                        }
                        val transcriptProgressPercent = when (transcriptStatus) {
                            LibraryTranscriptStatus.COMPLETED -> 100

                            LibraryTranscriptStatus.NONE -> 0

                            LibraryTranscriptStatus.PARTIAL -> {
                                if (chunks.isNotEmpty()) {
                                    kotlin.math.round((transcribedCount * 100f) / chunks.size.toFloat())
                                        .toInt()
                                        .coerceIn(1, 99)
                                } else {
                                    0
                                }
                            }
                        }
                        val isTranscribing = transcriptStatus != LibraryTranscriptStatus.COMPLETED &&
                            (
                                lookaheadStatus.inFlightChunkId != null ||
                                    lookaheadStatus.queuedChunkIds.isNotEmpty() ||
                                    (lookaheadStatus.backgroundEnabled && lookaheadStatus.enabled && chunks.isNotEmpty())
                                )
                        item.id to ItemAnalysisSummary(
                            chunkCount = chunks.size,
                            hasTranscript = transcriptStatus != LibraryTranscriptStatus.NONE,
                            transcriptStatus = transcriptStatus,
                            transcriptProgressPercent = transcriptProgressPercent,
                            isAnalyzing = statusMap.values.any { it is StageStatus.Running },
                            isTranscribing = isTranscribing,
                        )
                    }
                }
                combine(perItemFlows) { pairs -> pairs.toMap() }
            }
        }

    private val dialogAndImportFlow: Flow<DialogAndImportSnapshot> = combine(
        combine(
            importState,
            transcriptImportState,
            syncState,
        ) { currentImport, currentTranscript, currentSync ->
            Triple(currentImport, currentTranscript, currentSync)
        },
        combine(
            pendingDeleteItem,
            pendingDeleteDirectoryPath,
            isPendingBatchDelete,
        ) { deleteItem, deleteDir, batchDelete ->
            Triple(deleteItem, deleteDir, batchDelete)
        },
        combine(
            pendingEditItem,
            pendingEditDirectory,
        ) { editItem, editDir ->
            editItem to editDir
        },
        pendingImportConfig,
    ) { (currentImport, currentTranscript, currentSync), (deleteTarget, deleteDirTarget, batchDelete), (editTarget, editDirTarget), importConfig ->
        DialogAndImportSnapshot(
            importState = currentImport,
            transcriptImportState = currentTranscript,
            syncState = currentSync,
            pendingDeleteItem = deleteTarget,
            pendingDeleteDirectoryPath = deleteDirTarget,
            isPendingBatchDelete = batchDelete,
            pendingEditItem = editTarget,
            pendingEditDirectory = editDirTarget,
            pendingImportConfig = importConfig,
        )
    }

    private val navigationAndSelectionFlow: Flow<NavigationAndSelectionSnapshot> = combine(
        combine(
            selectedDirectoryName,
            collapsedDirectories,
            currentDirectoryPath,
            selectedDirectoryPaths,
            selectedItemIds,
        ) { dirName, collapsed, currentPath, selectedDirs, selectedItems ->
            NavigationAndSelectionSnapshot(
                selectedDirectoryName = dirName,
                collapsedDirectories = collapsed,
                currentDirectoryPath = currentPath,
                selectedDirectoryPaths = selectedDirs,
                selectedItemIds = selectedItems,
            )
        },
        combine(
            selectedTab,
            fileSearchQuery,
            fileTargetLanguageFilter,
            fileSortOption,
            selectedPlaylistId,
        ) { tab, query, langFilter, sortOption, playlistId ->
            NavigationTabAndFilterSnapshot(
                selectedTab = tab,
                fileSearchQuery = query,
                fileTargetLanguageFilter = langFilter,
                fileSortOption = sortOption,
                selectedPlaylistId = playlistId,
            )
        },
    ) { baseNav, tabAndFilter ->
        baseNav.copy(
            selectedTab = tabAndFilter.selectedTab,
            fileSearchQuery = tabAndFilter.fileSearchQuery,
            fileTargetLanguageFilter = tabAndFilter.fileTargetLanguageFilter,
            fileSortOption = tabAndFilter.fileSortOption,
            selectedPlaylistId = tabAndFilter.selectedPlaylistId,
        )
    }

    /** ライブラリ画面の UI 状態。 */
    val uiState: StateFlow<LibraryUiState> = combine(
        combine(
            combine(
                libraryRepository.observeItems(),
                libraryRepository.observeCourseFolders(),
                libraryRepository.observePlaylists(),
            ) { items, courseFolders, playlists -> Triple(items, courseFolders, playlists) },
            libraryRepository.observeItemStates(),
            analysisSummaries,
            libraryRepository.observeMissingAudioIds(),
            userSettingsRepository.settings,
        ) { (items, courseFolders, playlists), itemStates, summaries, missingAudioIds, userSettings ->
            LibraryRepositorySnapshot(
                items = items,
                courseFolders = courseFolders,
                playlists = playlists,
                itemStates = itemStates,
                summaries = summaries,
                missingAudioIds = missingAudioIds,
                enabledLanguages = orderEnabledLanguages(userSettings.enabledLanguages),
                showLanguageOnboardingDialog = !userSettings.hasCompletedLanguageOnboarding,
            )
        },
        playbackController.status,
        dialogAndImportFlow,
        navigationAndSelectionFlow,
    ) { repoSnapshot, playbackStatus, dialogAndImport, navAndSelection ->
        buildUiState(
            items = repoSnapshot.items,
            courseFolders = repoSnapshot.courseFolders,
            playlists = repoSnapshot.playlists,
            itemStates = repoSnapshot.itemStates,
            summaries = repoSnapshot.summaries,
            missingAudioIds = repoSnapshot.missingAudioIds,
            enabledLanguages = repoSnapshot.enabledLanguages,
            showLanguageOnboardingDialog = repoSnapshot.showLanguageOnboardingDialog,
            playbackStatus = playbackStatus,
            dialogAndImport = dialogAndImport,
            navAndSelection = navAndSelection,
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
                val trimmedDir = event.directoryName?.trim()?.ifBlank { null }
                if (trimmedDir != null) {
                    selectedDirectoryName.value = trimmedDir
                }
                val effectiveUris = if (event.uris.isNotEmpty()) {
                    event.uris
                } else if (event.treeUri != null) {
                    listOf(event.treeUri)
                } else {
                    emptyList()
                }
                if (effectiveUris.isNotEmpty()) {
                    pendingImportConfig.value = PendingImportConfig(
                        uris = effectiveUris,
                        initialCourse = trimmedDir ?: currentDirectoryPath.value ?: selectedDirectoryName.value,
                        initialLanguages = defaultLanguagePair,
                        isDriveSyncFolder = false,
                        treeUri = event.treeUri,
                    )
                }
            }

            is LibraryEvent.ImportUris -> {
                if (event.uris.isNotEmpty()) {
                    pendingImportConfig.value = PendingImportConfig(
                        uris = event.uris,
                        initialCourse = LibraryCourseFolder.DEFAULT_IMPORTED_COURSE,
                        initialLanguages = defaultLanguagePair,
                        isDriveSyncFolder = false,
                        treeUri = null,
                    )
                }
            }

            is LibraryEvent.SelectDriveSyncFolder -> {
                val trimmedDir = event.directoryName?.trim()?.ifBlank { null }
                if (trimmedDir != null) {
                    selectedDirectoryName.value = trimmedDir
                }
                pendingImportConfig.value = PendingImportConfig(
                    uris = listOf(event.treeUri),
                    initialCourse = trimmedDir ?: currentDirectoryPath.value ?: selectedDirectoryName.value ?: "ChunkFlow",
                    initialLanguages = defaultLanguagePair,
                    isDriveSyncFolder = true,
                )
            }

            is LibraryEvent.ConfirmImportConfig -> {
                val config = pendingImportConfig.value ?: return
                pendingImportConfig.value = null
                val normalizedCourse = event.course?.trim()?.ifBlank { null }
                val normalizedTarget = event.languages.target.value.trim().lowercase().ifBlank { "en" }
                val normalizedBase = event.languages.base?.value?.trim()?.lowercase()?.ifBlank { null }
                val normalizedLanguages = LanguagePair(
                    target = LanguageTag(normalizedTarget),
                    base = normalizedBase?.let { LanguageTag(it) },
                )
                if (normalizedCourse != null) {
                    selectedDirectoryName.value = normalizedCourse
                }
                defaultLanguagePair = normalizedLanguages
                viewModelScope.launch {
                    userSettingsRepository.setDefaultLanguagePair(normalizedLanguages)
                }
                if (config.isDriveSyncFolder) {
                    syncFromLinkedFolder(
                        treeUri = config.treeUri ?: config.uris.firstOrNull(),
                        course = normalizedCourse,
                        languages = normalizedLanguages,
                    )
                } else if (config.treeUri != null) {
                    linkLocalDirectory(
                        treeUri = config.treeUri,
                        course = normalizedCourse,
                        languages = normalizedLanguages,
                    )
                } else {
                    val targetCourse = normalizedCourse ?: LibraryCourseFolder.DEFAULT_IMPORTED_COURSE
                    importUris(
                        uris = config.uris,
                        course = targetCourse,
                        languages = normalizedLanguages,
                    )
                }
            }

            LibraryEvent.CancelImportConfig -> {
                pendingImportConfig.value = null
            }

            is LibraryEvent.ImportTranscriptUri -> importTranscriptUri(event.uri)

            is LibraryEvent.ImportTranscriptForItem -> importTranscriptForItem(event.id, event.uri)

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

            is LibraryEvent.OpenDirectory -> {
                clearSelectionInternal()
                currentDirectoryPath.value = event.directoryPath?.trim('/')?.takeIf { it.isNotBlank() }
            }

            LibraryEvent.NavigateUp -> {
                if (selectedDirectoryPaths.value.isNotEmpty() || selectedItemIds.value.isNotEmpty()) {
                    clearSelectionInternal()
                } else if (selectedTab.value == LibraryViewTab.PLAYLISTS && selectedPlaylistId.value != null) {
                    selectedPlaylistId.value = null
                } else {
                    val current = currentDirectoryPath.value?.trim('/')?.takeIf { it.isNotBlank() }
                    currentDirectoryPath.value = if (current != null && '/' in current) {
                        current.substringBeforeLast('/')
                    } else {
                        null
                    }
                }
            }

            is LibraryEvent.SelectTab -> {
                clearSelectionInternal()
                selectedTab.value = event.tab
            }

            is LibraryEvent.UpdateFileSearchQuery -> {
                fileSearchQuery.value = event.query
            }

            is LibraryEvent.SelectFileTargetLanguageFilter -> {
                fileTargetLanguageFilter.value = event.language?.let {
                    LanguageTag(it.value.trim().lowercase())
                }
            }

            is LibraryEvent.SelectFileSortOption -> {
                fileSortOption.value = event.sortOption
            }

            is LibraryEvent.SelectPlaylistCategory -> {
                clearSelectionInternal()
                selectedPlaylistId.value = event.category?.playlistId
            }

            is LibraryEvent.SelectPlaylist -> {
                clearSelectionInternal()
                selectedPlaylistId.value = event.playlistId
            }

            is LibraryEvent.CreatePlaylist -> {
                val trimmed = event.name.trim()
                if (trimmed.isNotEmpty()) {
                    viewModelScope.launch {
                        libraryRepository.createPlaylist(name = trimmed)
                    }
                }
            }

            is LibraryEvent.CreatePlaylistAndAddSelected -> {
                val trimmed = event.name.trim()
                if (trimmed.isNotEmpty()) {
                    val stateSnapshot = uiState.value
                    val dirSnapshot = selectedDirectoryPaths.value
                    val itemSnapshot = selectedItemIds.value
                    clearSelectionInternal()
                    viewModelScope.launch {
                        val targetIds = resolveTargetMediaIds(stateSnapshot, dirSnapshot, itemSnapshot)
                        libraryRepository.createPlaylist(
                            name = trimmed,
                            initialMediaIds = targetIds,
                        )
                    }
                }
            }

            is LibraryEvent.DeletePlaylist -> {
                if (selectedPlaylistId.value == event.playlistId) {
                    selectedPlaylistId.value = null
                }
                viewModelScope.launch {
                    libraryRepository.deletePlaylist(event.playlistId)
                }
            }

            is LibraryEvent.AddSelectedItemsToPlaylist -> {
                val stateSnapshot = uiState.value
                val dirSnapshot = selectedDirectoryPaths.value
                val itemSnapshot = selectedItemIds.value
                clearSelectionInternal()
                viewModelScope.launch {
                    val targetIds = resolveTargetMediaIds(stateSnapshot, dirSnapshot, itemSnapshot)
                    if (targetIds.isEmpty()) return@launch
                    if (event.playlistId == PlaylistId.FAVORITES) {
                        val latestStates = libraryRepository.observeItemStates().firstOrNull().orEmpty()
                        for (id in targetIds) {
                            if (latestStates[id]?.isFavorite != true) {
                                libraryRepository.toggleFavorite(id)
                            }
                        }
                    } else {
                        libraryRepository.addItemsToPlaylist(event.playlistId, targetIds)
                    }
                }
            }

            is LibraryEvent.RemoveItemFromPlaylist -> {
                viewModelScope.launch {
                    if (event.playlistId == PlaylistId.FAVORITES) {
                        val latestState = libraryRepository.observeItemState(event.mediaId).firstOrNull()
                        if (latestState?.isFavorite == true) {
                            libraryRepository.toggleFavorite(event.mediaId)
                        }
                    } else {
                        libraryRepository.removeItemFromPlaylist(event.playlistId, event.mediaId)
                    }
                }
            }

            is LibraryEvent.MovePlaylist -> {
                val entries = uiState.value.playlistEntries.map { it.id }.toMutableList()
                if (event.fromIndex in entries.indices &&
                    event.toIndex in entries.indices &&
                    event.fromIndex != event.toIndex
                ) {
                    val moved = entries.removeAt(event.fromIndex)
                    entries.add(event.toIndex, moved)
                    viewModelScope.launch {
                        libraryRepository.reorderPlaylists(entries)
                    }
                }
            }

            is LibraryEvent.MovePlaylistItem -> {
                val currentItems = uiState.value.playlistItemsFor(event.playlistId).map { it.item.id }.toMutableList()
                if (event.fromIndex in currentItems.indices &&
                    event.toIndex in currentItems.indices &&
                    event.fromIndex != event.toIndex
                ) {
                    val moved = currentItems.removeAt(event.fromIndex)
                    currentItems.add(event.toIndex, moved)
                    viewModelScope.launch {
                        libraryRepository.reorderPlaylistItems(event.playlistId, currentItems)
                    }
                }
            }

            is LibraryEvent.MoveFileTabItem -> {
                fileSortOption.value = LibraryFileSortOption.NAME
                val currentFiltered = uiState.value.filteredAllFiles.map { it.item.id }.toMutableList()
                if (event.fromIndex in currentFiltered.indices &&
                    event.toIndex in currentFiltered.indices &&
                    event.fromIndex != event.toIndex
                ) {
                    val moved = currentFiltered.removeAt(event.fromIndex)
                    currentFiltered.add(event.toIndex, moved)
                    // フィルタ外のファイルがあれば、現在の全体順序を保ったまま対象の位置を入れ替える
                    val allOrdered = buildUpdatedAllFileOrder(uiState.value, currentFiltered)
                    viewModelScope.launch {
                        libraryRepository.reorderAllFiles(allOrdered)
                    }
                }
            }

            is LibraryEvent.ToggleFavorite -> {
                viewModelScope.launch {
                    libraryRepository.toggleFavorite(event.id)
                }
            }

            LibraryEvent.ToggleFavoriteSelected -> {
                val stateSnapshot = uiState.value
                val dirSnapshot = selectedDirectoryPaths.value
                val itemSnapshot = selectedItemIds.value
                if (dirSnapshot.isNotEmpty() || itemSnapshot.isNotEmpty()) {
                    viewModelScope.launch {
                        val targetIds = resolveTargetMediaIds(stateSnapshot, dirSnapshot, itemSnapshot)
                        if (targetIds.isEmpty()) return@launch
                        val latestStates = libraryRepository.observeItemStates().firstOrNull().orEmpty()
                        val currentFavoriteIds = latestStates
                            .filterValues { it.isFavorite }
                            .keys
                        val allFavorite = targetIds.all { it in currentFavoriteIds }
                        val idsToToggle = if (allFavorite) {
                            targetIds.filter { it in currentFavoriteIds }
                        } else {
                            targetIds.filterNot { it in currentFavoriteIds }
                        }
                        for (id in idsToToggle) {
                            libraryRepository.toggleFavorite(id)
                        }
                    }
                }
            }

            is LibraryEvent.ToggleQueueItem -> {
                viewModelScope.launch {
                    libraryRepository.toggleQueueItem(event.id)
                }
            }

            is LibraryEvent.OpenItem -> {
                viewModelScope.launch {
                    libraryRepository.recordItemAccess(event.id)
                }
            }

            is LibraryEvent.ToggleDirectorySelection -> {
                val normalized = event.directoryPath.trim('/')
                if (normalized.isNotEmpty()) {
                    selectedDirectoryPaths.update { current ->
                        if (normalized in current) current - normalized else current + normalized
                    }
                }
            }

            is LibraryEvent.ToggleItemSelection -> {
                selectedItemIds.update { current ->
                    if (event.id in current) current - event.id else current + event.id
                }
            }

            LibraryEvent.ClearSelection -> {
                clearSelectionInternal()
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

            is LibraryEvent.RequestEditDirectoryMetadata -> {
                val dirPath = event.directoryName?.trim('/')?.takeIf { it.isNotBlank() }
                val matchingItems = if (dirPath != null) {
                    uiState.value.itemsInDirectoryTree(dirPath)
                } else {
                    uiState.value.items.filter { uiState.value.effectivePathOf(it) == null }
                }
                val folderPair = uiState.value.courseFolders.firstOrNull {
                    it.coursePath.trim('/') == dirPath
                }?.languagePair
                val initialPair = matchingItems.firstOrNull()?.item?.languagePair
                    ?: folderPair
                    ?: defaultLanguagePair
                pendingEditDirectory.value = PendingDirectoryEdit(
                    currentCourse = dirPath,
                    initialLanguages = initialPair,
                )
            }

            is LibraryEvent.ConfirmEditDirectoryMetadata -> {
                pendingEditDirectory.value = null
                val currentPath = event.currentCourse?.trim('/')?.takeIf { it.isNotBlank() }
                val rawNew = event.newCourse?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
                val resolvedNewCourse = if (
                    currentPath != null &&
                    '/' in currentPath &&
                    rawNew != null &&
                    '/' !in rawNew
                ) {
                    "${currentPath.substringBeforeLast('/')}/$rawNew"
                } else {
                    rawNew
                }
                val normalizedTarget = event.languages.target.value.trim().lowercase().ifBlank { "en" }
                val normalizedBase = event.languages.base?.value?.trim()?.lowercase()?.ifBlank { null }
                val normalizedLanguages = LanguagePair(
                    target = LanguageTag(normalizedTarget),
                    base = normalizedBase?.let { LanguageTag(it) },
                )
                if (selectedDirectoryName.value == currentPath) {
                    selectedDirectoryName.value = resolvedNewCourse
                }
                val activeDir = currentDirectoryPath.value
                if (currentPath != null && activeDir != null) {
                    if (activeDir == currentPath) {
                        currentDirectoryPath.value = resolvedNewCourse
                    } else if (activeDir.startsWith("$currentPath/")) {
                        val suffix = activeDir.removePrefix("$currentPath/")
                        currentDirectoryPath.value = if (resolvedNewCourse != null) {
                            "$resolvedNewCourse/$suffix"
                        } else {
                            suffix
                        }
                    }
                }
                viewModelScope.launch {
                    libraryRepository.updateDirectoryMetadata(
                        currentCourse = currentPath,
                        newCourse = resolvedNewCourse,
                        languages = normalizedLanguages,
                    )
                }
            }

            LibraryEvent.CancelEditDirectoryMetadata -> {
                pendingEditDirectory.value = null
            }

            is LibraryEvent.RequestDelete -> {
                pendingDeleteDirectoryPath.value = null
                isPendingBatchDelete.value = false
                pendingDeleteItem.value = event.item
            }

            is LibraryEvent.RequestDeleteDirectory -> {
                val normalized = event.directoryPath.trim('/').takeIf { it.isNotBlank() } ?: return
                pendingDeleteItem.value = null
                isPendingBatchDelete.value = false
                pendingDeleteDirectoryPath.value = normalized
            }

            LibraryEvent.RequestDeleteSelected -> {
                if (selectedDirectoryPaths.value.isNotEmpty() || selectedItemIds.value.isNotEmpty()) {
                    pendingDeleteItem.value = null
                    pendingDeleteDirectoryPath.value = null
                    isPendingBatchDelete.value = true
                }
            }

            LibraryEvent.CancelDelete -> {
                pendingDeleteItem.value = null
                pendingDeleteDirectoryPath.value = null
                isPendingBatchDelete.value = false
            }

            LibraryEvent.ConfirmDelete -> confirmDelete()

            LibraryEvent.ToggleMiniPlayerPlayPause -> toggleMiniPlayerPlayPause()

            LibraryEvent.RescanLocalDirectories -> {
                val targetCourse = currentDirectoryPath.value?.trim('/')?.takeIf { it.isNotBlank() }
                viewModelScope.launch {
                    val addedCount = libraryRepository.rescanLocalDirectories(targetCourse)
                    importState.value = LibraryImportUiState.Done(
                        addedCount = addedCount,
                        duplicates = 0,
                        transcriptsAttached = 0,
                    )
                }
            }

            is LibraryEvent.CreateVirtualFolder -> {
                val cleanFolder = event.folderName.trim().trim('/')
                if (cleanFolder.isNotEmpty()) {
                    val normalizedTarget = event.languages.target.value.trim().lowercase().ifBlank { "en" }
                    val normalizedBase = event.languages.base?.value?.trim()?.lowercase()?.ifBlank { null }
                    val normalizedPair = LanguagePair(
                        target = LanguageTag(normalizedTarget),
                        base = normalizedBase?.let { LanguageTag(it) },
                    )
                    viewModelScope.launch {
                        libraryRepository.createVirtualFolder(
                            coursePath = cleanFolder,
                            languages = normalizedPair,
                        )
                    }
                }
            }

            is LibraryEvent.MoveItemsToCourse -> {
                if (event.ids.isNotEmpty()) {
                    val targetCourse = event.targetCourse?.trim()?.trim('/')?.ifBlank { null }
                        ?: LibraryCourseFolder.DEFAULT_IMPORTED_COURSE
                    clearSelectionInternal()
                    viewModelScope.launch {
                        libraryRepository.moveItemsToCourse(
                            mediaIds = event.ids,
                            targetCourse = targetCourse,
                        )
                    }
                }
            }

            is LibraryEvent.DownloadDriveAudio -> {
                viewModelScope.launch {
                    libraryRepository.downloadDriveAudio(event.id)
                }
            }

            is LibraryEvent.DeleteLocalAudioCopy -> {
                pendingEditItem.value = null
                viewModelScope.launch {
                    libraryRepository.deleteLocalAudioCopy(event.id)
                }
            }

            is LibraryEvent.ToggleEnabledLanguage -> {
                val normalized = LanguageSettings.normalize(event.language)
                if (normalized.value.isBlank()) return
                val current = uiState.value.enabledLanguages
                    .map(LanguageSettings::normalize)
                    .toSet()
                val next = if (normalized in current) {
                    if (current.size <= 1) return
                    current - normalized
                } else {
                    current + normalized
                }
                viewModelScope.launch {
                    userSettingsRepository.setEnabledLanguages(next)
                }
            }

            LibraryEvent.CompleteLanguageOnboarding -> {
                viewModelScope.launch {
                    userSettingsRepository.completeLanguageOnboarding()
                }
            }

            LibraryEvent.RequestImportDirectory,
            LibraryEvent.RequestImport,
            LibraryEvent.RequestImportTranscript,
            is LibraryEvent.RequestImportTranscriptForItem,
            LibraryEvent.RequestSelectDriveSyncFolder,
            LibraryEvent.OpenSettings,
            -> Unit
        }
    }

    private suspend fun resolveTargetMediaIds(
        stateSnapshot: LibraryUiState,
        dirSnapshot: Set<String>,
        itemSnapshot: Set<MediaId>,
    ): List<MediaId> = stateSnapshot.selectedTargetItemIds.ifEmpty {
        val dirItemIds = if (dirSnapshot.isNotEmpty()) {
            val allItems = libraryRepository.observeItems().firstOrNull().orEmpty()
            allItems.filter { item ->
                val c = item.course?.trim('/')?.takeIf { it.isNotBlank() }
                    ?: LibraryCourseFolder.DEFAULT_IMPORTED_COURSE
                dirSnapshot.any { dir -> c == dir || c.startsWith("$dir/") }
            }.map { it.id }
        } else {
            emptyList()
        }
        (dirItemIds + itemSnapshot).distinct()
    }

    private fun buildUpdatedAllFileOrder(
        state: LibraryUiState,
        reorderedFilteredIds: List<MediaId>,
    ): List<MediaId> {
        val fullSorted = state.copy(fileSearchQuery = "", fileTargetLanguageFilter = null)
            .filteredAllFiles
            .map { it.item.id }
        val filteredSet = reorderedFilteredIds.toSet()
        val iterator = reorderedFilteredIds.iterator()
        return fullSorted.map { id ->
            if (id in filteredSet && iterator.hasNext()) iterator.next() else id
        }
    }

    private fun clearSelectionInternal() {
        selectedDirectoryPaths.value = emptySet()
        selectedItemIds.value = emptySet()
        isPendingBatchDelete.value = false
    }

    private fun linkLocalDirectory(
        treeUri: Uri,
        course: String?,
        languages: LanguagePair,
    ) {
        transcriptImportState.value = TranscriptImportUiState.Idle
        importJob?.cancel()
        importJob = viewModelScope.launch {
            libraryRepository.linkLocalDirectory(
                treeUri = treeUri,
                course = course,
                languages = languages,
            ).collect { progress ->
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

    private fun importUris(
        uris: List<Uri>,
        course: String?,
        languages: LanguagePair,
    ) {
        if (uris.isEmpty()) return
        val effectiveCourse = course?.trim()?.takeIf { it.isNotEmpty() }
            ?: LibraryCourseFolder.DEFAULT_IMPORTED_COURSE
        transcriptImportState.value = TranscriptImportUiState.Idle
        importJob?.cancel()
        importJob = viewModelScope.launch {
            libraryRepository.createVirtualFolder(
                coursePath = effectiveCourse,
                languages = languages,
            )
            libraryRepository.import(
                uris = uris,
                course = effectiveCourse,
                languages = languages,
            ).collect { progress ->
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
            transcriptImportState.value = mapTranscriptImportResult(result)
        }
    }

    private fun importTranscriptForItem(id: MediaId, uri: Uri) {
        importState.value = LibraryImportUiState.Idle
        pendingEditItem.value = null
        viewModelScope.launch {
            val result = libraryRepository.importTranscriptForMedia(id, uri)
            transcriptImportState.value = mapTranscriptImportResult(result)
        }
    }

    private fun mapTranscriptImportResult(result: TranscriptImportResult): TranscriptImportUiState = when (result) {
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

    private fun syncFromDrive() {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            libraryRepository.syncFromDrive().collect { progress ->
                syncState.value = mapSyncProgress(progress)
            }
        }
    }

    private fun syncFromLinkedFolder(
        treeUri: Uri?,
        course: String?,
        languages: LanguagePair,
    ) {
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            libraryRepository.syncFromLinkedFolder(
                treeUri = treeUri,
                course = course,
                languages = languages,
            ).collect { progress ->
                syncState.value = mapSyncProgress(progress)
            }
        }
    }

    private fun mapSyncProgress(progress: SyncProgress): DriveSyncUiState = when (progress) {
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

    private fun confirmDelete() {
        val currentState = uiState.value
        val targets = currentState.pendingDeleteTargetItems
        pendingDeleteItem.value = null
        pendingDeleteDirectoryPath.value = null
        isPendingBatchDelete.value = false
        clearSelectionInternal()
        if (targets.isEmpty()) return

        viewModelScope.launch {
            for (target in targets) {
                libraryRepository.delete(target.id)
                if (playbackController is FakePlaybackController &&
                    playbackController.status.value.mediaId == target.id
                ) {
                    playbackController.statusState.value = PlaybackStatus()
                }
            }
        }
    }

    private fun toggleMiniPlayerPlayPause() {
        val targetMiniPlayer = uiState.value.miniPlayer ?: return
        val currentActiveId = playbackController.status.value.mediaId
        if (currentActiveId != targetMiniPlayer.mediaId) {
            viewModelScope.launch {
                val savedState = libraryRepository.observeItemState(targetMiniPlayer.mediaId).firstOrNull()
                playbackController.open(targetMiniPlayer.mediaId)
                val player = playbackController.player.value
                if (player != null) {
                    player.play()
                } else if (playbackController is FakePlaybackController) {
                    if (savedState != null) {
                        playbackController.position.positionMs = savedState.positionMs
                        playbackController.setSpeed(savedState.speed)
                    }
                    playbackController.statusState.update { current ->
                        current.copy(
                            mediaId = targetMiniPlayer.mediaId,
                            isPlaying = true,
                            durationMs = targetMiniPlayer.durationMs,
                        )
                    }
                }
            }
            return
        }
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
        courseFolders: List<LibraryCourseFolder>,
        playlists: List<LibraryPlaylist>,
        itemStates: Map<MediaId, ItemState>,
        summaries: Map<MediaId, ItemAnalysisSummary>,
        missingAudioIds: Set<MediaId>,
        enabledLanguages: List<LanguageTag>,
        showLanguageOnboardingDialog: Boolean,
        playbackStatus: PlaybackStatus,
        dialogAndImport: DialogAndImportSnapshot,
        navAndSelection: NavigationAndSelectionSnapshot,
    ): LibraryUiState {
        val uiItems = items.map { audioItem ->
            val state = itemStates[audioItem.id]
            val summary = summaries[audioItem.id]
            val transcriptStatus = summary?.transcriptStatus ?: LibraryTranscriptStatus.NONE
            val posMs = state?.positionMs ?: 0L
            val maxPosMs = maxOf(state?.maxPositionMs ?: 0L, posMs)
            LibraryItemUiModel(
                item = audioItem,
                positionMs = posMs,
                maxPositionMs = maxPosMs,
                speed = state?.speed ?: 1.0f,
                chunkCount = summary?.chunkCount ?: 0,
                hasTranscript = summary?.hasTranscript ?: (transcriptStatus != LibraryTranscriptStatus.NONE),
                transcriptStatus = transcriptStatus,
                transcriptProgressPercent = summary?.transcriptProgressPercent ?: when (transcriptStatus) {
                    LibraryTranscriptStatus.COMPLETED -> 100
                    LibraryTranscriptStatus.NONE -> 0
                    LibraryTranscriptStatus.PARTIAL -> 50
                },
                isAnalyzing = summary?.isAnalyzing ?: false,
                isTranscribing = summary?.isTranscribing ?: false,
                isAudioMissing = audioItem.id in missingAudioIds,
                isListened = state?.isListened ?: false,
                lastAccessedAtEpochMs = state?.updatedAtEpochMs?.takeIf { it > 0L },
                isFavorite = state?.isFavorite ?: false,
                queuedAtEpochMs = state?.queuedAtEpochMs ?: 0L,
                accessCount = state?.accessCount ?: 0,
                fileSortOrder = state?.fileSortOrder,
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
                positionMs = itemStates[activeMediaId]?.positionMs ?: 0L,
                sourceUri = activeAudioItem.sourceUri,
                localFilePath = activeAudioItem.localFilePath,
            )
        } else {
            val lastAccessedItem = uiItems
                .filter { it.lastAccessedAtEpochMs != null && !it.isAudioMissing && !it.item.isRemoteOnly }
                .maxWithOrNull(
                    compareBy<LibraryItemUiModel> { it.lastAccessedAtEpochMs ?: 0L }
                        .thenBy { it.item.id.value },
                )
            if (lastAccessedItem != null) {
                MiniPlayerUiState(
                    mediaId = lastAccessedItem.item.id,
                    title = lastAccessedItem.item.title,
                    isPlaying = false,
                    durationMs = lastAccessedItem.item.durationMs,
                    positionMs = lastAccessedItem.positionMs,
                    sourceUri = lastAccessedItem.item.sourceUri,
                    localFilePath = lastAccessedItem.item.localFilePath,
                )
            } else {
                null
            }
        }
        val validItemIds = items.mapTo(mutableSetOf()) { it.id }
        val filteredSelectedItemIds = navAndSelection.selectedItemIds.intersect(validItemIds)
        val favoriteIds = uiItems
            .filter { it.isFavorite }
            .mapTo(linkedSetOf()) { it.item.id }
        val queuedIds = uiItems
            .filter { it.queuedAtEpochMs > 0L }
            .sortedWith(
                compareBy<LibraryItemUiModel> { it.queuedAtEpochMs }
                    .thenBy { it.item.id.value },
            )
            .map { it.item.id }

        return LibraryUiState(
            items = uiItems,
            courseFolders = courseFolders,
            importState = dialogAndImport.importState,
            transcriptImportState = dialogAndImport.transcriptImportState,
            syncState = dialogAndImport.syncState,
            miniPlayer = miniPlayer,
            pendingDeleteItem = dialogAndImport.pendingDeleteItem,
            pendingDeleteDirectoryPath = dialogAndImport.pendingDeleteDirectoryPath,
            isPendingBatchDelete = dialogAndImport.isPendingBatchDelete,
            pendingEditItem = dialogAndImport.pendingEditItem,
            pendingEditDirectory = dialogAndImport.pendingEditDirectory,
            pendingImportConfig = dialogAndImport.pendingImportConfig,
            selectedDirectoryName = navAndSelection.selectedDirectoryName,
            collapsedDirectories = navAndSelection.collapsedDirectories,
            enabledLanguages = enabledLanguages,
            showLanguageOnboardingDialog = showLanguageOnboardingDialog,
            currentDirectoryPath = navAndSelection.currentDirectoryPath,
            selectedDirectoryPaths = navAndSelection.selectedDirectoryPaths,
            selectedItemIds = filteredSelectedItemIds,
            selectedTab = navAndSelection.selectedTab,
            fileSearchQuery = navAndSelection.fileSearchQuery,
            fileTargetLanguageFilter = navAndSelection.fileTargetLanguageFilter,
            fileSortOption = navAndSelection.fileSortOption,
            selectedPlaylistId = navAndSelection.selectedPlaylistId,
            selectedPlaylistCategory = navAndSelection.selectedPlaylistId?.let(LibraryPlaylistCategory::fromPlaylistId),
            playlists = playlists,
            favoriteItemIds = favoriteIds,
            queuedItemIds = queuedIds,
        )
    }
}

private data class NavigationTabAndFilterSnapshot(
    val selectedTab: LibraryViewTab = LibraryViewTab.FOLDERS,
    val fileSearchQuery: String = "",
    val fileTargetLanguageFilter: LanguageTag? = null,
    val fileSortOption: LibraryFileSortOption = LibraryFileSortOption.NAME,
    val selectedPlaylistId: PlaylistId? = null,
)
