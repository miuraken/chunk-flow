package com.miuraken.chunkflow.core.data.library

import android.net.Uri
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.LibraryPlaylist
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PlaylistId
import com.miuraken.chunkflow.core.model.TranscriptExportFormat
import com.miuraken.chunkflow.core.transcript.TranscriptError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * ライブラリ（取り込んだ教材）と教材ごとの再生・学習状態（[ItemState]）の単一の情報源（§6.3、§6.4、§7.1）。
 *
 * 実装は playback が書く（`core/data` の `library` パッケージ）。宣言は lead が管理する契約（ADR 0003）。
 */
interface LibraryRepository {
    /** すべての教材を購読する。 */
    fun observeItems(): Flow<List<AudioItem>>

    /** 登録されているすべてのコースフォルダ（ローカルフォルダ・仮想フォルダ）を購読する。 */
    fun observeCourseFolders(): Flow<List<LibraryCourseFolder>> = flowOf(emptyList())

    /** 教材 1 件を購読する。削除されたら `null` を流す。 */
    fun observeItem(id: MediaId): Flow<AudioItem?>

    /** すべての教材の再生・学習状態（F2.1 進捗表示、F2.2 レジューム）を購読する。 */
    fun observeItemStates(): Flow<Map<MediaId, ItemState>>

    /** 教材 1 件の再生・学習状態を購読する。まだ状態がなければ `null` を流す。 */
    fun observeItemState(id: MediaId): Flow<ItemState?>

    /** 教材の再生・学習状態を保存（追加または更新）する。教材が削除済みなら何もしない。 */
    suspend fun upsertItemState(state: ItemState)

    /**
     * 教材の再生位置と速度を更新する（§7.2.5: 再生中は 5 秒ごと、および一時停止・停止・切り替え時）。
     *
     * まだ [ItemState] の行がない教材の場合は既定値で作って位置・速度を設定する。
     * 教材が削除済み（再生中に削除されたときなど）なら何もしない。
     */
    suspend fun updatePlaybackPosition(
        id: MediaId,
        positionMs: Long,
        speed: Float,
        updatedAtEpochMs: Long,
    )

    /**
     * 音声と文字起こしファイルをまとめて取り込む（名前と SHA-256 で対応付ける）。
     *
     * @param uris SAF の `content://` URI。それ以外のスキームは拒否する（§8.1）。
     */
    fun import(uris: List<Uri>): Flow<ImportProgress>

    /**
     * コース名（フォルダ名）と言語ペアを共通設定して、音声と文字起こしファイルをまとめて取り込む。
     *
     * @param uris SAF の `content://` URI（または Tree URI）。
     * @param course 取り込む教材に設定するコース名（`null` の場合は URI から推定または既定の `"default"`）。
     * @param languages 取り込む教材に共通で設定する言語ペア（`null` の場合は既定の言語ペア）。
     */
    fun import(
        uris: List<Uri>,
        course: String?,
        languages: LanguagePair?,
    ): Flow<ImportProgress> = import(uris)

    /**
     * ローカルフォルダ（SAF Tree URI）をリンクし、再帰的に音声ファイルを走査して登録する。
     * 音声ファイルのコピーは作らず、元の URI / ファイルパスへの参照を保持する。
     */
    fun linkLocalDirectory(
        treeUri: Uri,
        course: String? = null,
        languages: LanguagePair? = null,
    ): Flow<ImportProgress> = import(listOf(treeUri), course, languages)

    /**
     * 登録済みのローカルフォルダを再スキャンし、新規ファイルの追加や消失したファイルの DB 削除を行う。
     *
     * @param coursePath 対象のコースパス（`null` の場合はすべてのローカルフォルダを再スキャン）。
     */
    suspend fun rescanLocalDirectories(coursePath: String? = null): Int = 0

    /**
     * 新しい仮想フォルダ（ファイル単位取り込みの整理用ディレクトリ）を作成する。
     */
    suspend fun createVirtualFolder(
        coursePath: String,
        languages: LanguagePair,
    ): Boolean = false

    /**
     * 実フォルダ（ファイルシステム上の親ディレクトリに対応するコースフォルダ）を登録・更新する。
     */
    suspend fun createRealFolder(
        coursePath: String,
        languages: LanguagePair,
        treeUri: String? = null,
    ): Boolean = false

    /**
     * 複数の教材を指定したコース（フォルダ）へ移動する。
     */
    suspend fun moveItemsToCourse(
        mediaIds: Set<MediaId>,
        targetCourse: String,
    ) = Unit

    /** 文字起こしファイルだけを取り込み、SHA-256 が一致する既存の教材に付ける。 */
    suspend fun importTranscript(uri: Uri): TranscriptImportResult

    /**
     * 指定した教材 [id] に対して、任意の場所にある文字起こし・字幕ファイル（JSON / SRT / VTT / LRC）を直接取り込んで紐付ける。
     *
     * ファイル名や SHA-256 が一致しない場合でも、対象教材 [id] の文字起こしとして取り込む。
     */
    suspend fun importTranscriptForMedia(id: MediaId, uri: Uri): TranscriptImportResult = importTranscript(uri)

    /**
     * 指定した教材 [id] の文字起こしをすべてリセットし、保存済みの文字起こしファイルも削除する。
     */
    suspend fun resetTranscriptsAndDeleteFiles(id: MediaId): Boolean = false

    /** 教材のコース名と言語ペアを更新する（F1.3、F1.4）。 */
    suspend fun updateItemMetadata(
        id: MediaId,
        course: String?,
        languages: LanguagePair,
    ) = Unit

    /** ディレクトリ（コース）単位でコース名と言語ペアを一括再設定する。 */
    suspend fun updateDirectoryMetadata(
        currentCourse: String?,
        newCourse: String? = currentCourse,
        languages: LanguagePair,
    ) = Unit

    /** 教材の聴取完了フラグ（Listened / 済）と最終アクセス日時を更新する。教材が削除済みなら何もしない。 */
    suspend fun markItemListened(
        id: MediaId,
        isListened: Boolean = true,
        updatedAtEpochMs: Long = System.currentTimeMillis(),
    ) = Unit

    /**
     * 教材のお気に入り登録フラグ（`isFavorite`）を切り替えて永続化する。教材が削除済みなら何もしない。
     */
    suspend fun toggleFavorite(id: MediaId) = Unit

    /**
     * 教材の「次に再生するキュー」への登録状態（`queuedAtEpochMs`）を切り替えて永続化する。教材が削除済みなら何もしない。
     */
    suspend fun toggleQueueItem(
        id: MediaId,
        queuedAtEpochMs: Long = System.currentTimeMillis(),
    ) = Unit

    /**
     * 教材を開いたときの累計アクセス回数（`accessCount`）と最終アクセス日時（`updatedAtEpochMs`）を更新して永続化する。
     * 教材が削除済みなら何もしない。
     */
    suspend fun recordItemAccess(
        id: MediaId,
        accessedAtEpochMs: Long = System.currentTimeMillis(),
    ) = Unit

    /**
     * プレイリスト一覧（ユーザー作成プレイリストおよび並び順・カスタムアイテム順が記録された組み込みプレイリスト）を購読する。
     */
    fun observePlaylists(): Flow<List<LibraryPlaylist>> = flowOf(emptyList())

    /**
     * 新しいユーザープレイリストを末尾に作成し、必要であれば初期アイテム [initialMediaIds] を登録する。
     *
     * @return 作成されたプレイリストの [PlaylistId]（名前が空白の場合は `null`）。
     */
    suspend fun createPlaylist(
        name: String,
        initialMediaIds: List<MediaId> = emptyList(),
    ): PlaylistId? = null

    /**
     * ユーザープレイリストを削除する（組み込みプレイリストは削除しない）。
     */
    suspend fun deletePlaylist(id: PlaylistId) = Unit

    /**
     * 指定したプレイリストに複数の教材を追加する。
     */
    suspend fun addItemsToPlaylist(
        playlistId: PlaylistId,
        mediaIds: List<MediaId>,
    ) = Unit

    /**
     * 指定したプレイリストから特定の教材を除外する。
     */
    suspend fun removeItemFromPlaylist(
        playlistId: PlaylistId,
        mediaId: MediaId,
    ) = Unit

    /**
     * 「再生リスト」タブ内のプレイリスト一覧（組み込み＋ユーザー作成）の並び順を永続化する。
     */
    suspend fun reorderPlaylists(orderedIds: List<PlaylistId>) = Unit

    /**
     * 指定したプレイリスト内の教材アイテムの並び順を永続化する。
     */
    suspend fun reorderPlaylistItems(
        playlistId: PlaylistId,
        orderedMediaIds: List<MediaId>,
    ) = Unit

    /**
     * 「ファイル」タブにおける教材のカスタム並び順を永続化する。
     */
    suspend fun reorderAllFiles(orderedMediaIds: List<MediaId>) = Unit

    /**
     * 「フォルダ」タブにおけるルートフォルダのカスタム並び順を永続化する。
     */
    suspend fun reorderRootFolders(orderedCoursePaths: List<String>) = Unit

    /**
     * 教材の文字起こし（手動修正された言語ラベルを含む）を `.chunkflow.json` 形式でアプリ内部ファイルへ書き出す。
     *
     * @return 書き出しに成功した場合は `true`、書き出す文字起こしがない場合は `false`。
     */
    suspend fun exportLocalTranscriptJson(id: MediaId): Boolean = false

    /**
     * バックアップからの復元後など、DB に項目があるが音声ファイルにアクセスできない教材 ID の集合を購読する（F2.4）。
     */
    fun observeMissingAudioIds(): Flow<Set<MediaId>> = flowOf(emptySet())

    /**
     * 現在のチャンクと文字起こしを指定した形式（SRT / LRC / TSV / JSON）の文字列に変換して返す（F7.5）。
     *
     * @return 書き出し文字列。対象の教材やチャンクが存在しない場合は `null`。
     */
    suspend fun exportTranscriptText(id: MediaId, format: TranscriptExportFormat): String? = null

    /**
     * 現在のチャンクと文字起こしを指定した形式（SRT / LRC / TSV / JSON）で SAF の `content://` [targetUri] に書き出す（F7.5）。
     *
     * @return 書き出しに成功した場合は `true`。
     */
    suspend fun writeExportedTranscriptToUri(
        id: MediaId,
        format: TranscriptExportFormat,
        targetUri: Uri,
    ): Boolean = false

    /**
     * 複数の教材 [ids] の文字起こしを指定した形式（SRT / LRC / TSV / JSON）で SAF のフォルダ [treeUri] に書き出す。
     *
     * 各ファイル名は音声ファイル名の拡張子を対象形式の拡張子（`.srt`, `.lrc`, `.tsv`, `.json`）に置き換えたものになる。
     *
     * @return 書き出しに成功したファイル数。
     */
    suspend fun writeExportedTranscriptsToDirectory(
        ids: List<MediaId>,
        format: TranscriptExportFormat,
        treeUri: Uri,
    ): Int = 0

    /** 教材を削除する（音声・解析結果・文字起こし・状態を含む）。 */
    suspend fun delete(id: MediaId)
}

/** 取り込みの進捗。 */
sealed interface ImportProgress {
    /**
     * コピー中。
     *
     * @property fraction 進捗（0..1）。不明なら `null`。
     */
    data class Copying(val fraction: Float?) : ImportProgress

    /**
     * 完了した。
     *
     * @property mediaIds 取り込んだ（または既存の）教材。
     * @property duplicates 既に取り込まれていた音声の数。
     * @property transcriptsAttached 教材に付けた文字起こしの数。
     */
    data class Done(val mediaIds: List<MediaId>, val duplicates: Int, val transcriptsAttached: Int) : ImportProgress

    /** 失敗した。 */
    data class Failed(val error: ImportError) : ImportProgress
}

/** 取り込みの失敗の種別。 */
enum class ImportError {
    /** 対応していない形式。 */
    UNSUPPORTED_FORMAT,

    /** サイズの上限を超えた。 */
    TOO_LARGE,

    /** 空き容量が足りない。 */
    NO_SPACE,

    /** 読み書きに失敗した。 */
    IO,

    /** キャンセルされた。 */
    CANCELLED,
}

/** 文字起こしファイルだけを取り込んだ結果。 */
sealed interface TranscriptImportResult {
    /**
     * 教材に付けた。
     *
     * @property mediaId 文字起こしを付けた教材。
     * @property chunkCount 作ったチャンクの数。
     * @property durationMismatch 教材の長さと JSON の `durationMs` が 0.5 秒以上違う（別の版の音声の可能性がある）。
     */
    data class Attached(val mediaId: MediaId, val chunkCount: Int, val durationMismatch: Boolean) : TranscriptImportResult

    /**
     * 対応する音声がまだ取り込まれていない。
     *
     * @property audioFileName JSON に書かれた音声のファイル名（案内の表示に使う）。
     */
    data class NoMatchingAudio(val audioFileName: String?) : TranscriptImportResult

    /** 文字起こしファイルが不正。 */
    data class Invalid(val error: TranscriptError) : TranscriptImportResult
}
