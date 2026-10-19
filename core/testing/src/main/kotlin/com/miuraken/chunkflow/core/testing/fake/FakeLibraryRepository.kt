package com.miuraken.chunkflow.core.testing.fake

import android.net.Uri
import com.miuraken.chunkflow.core.data.library.ImportProgress
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.data.library.SyncProgress
import com.miuraken.chunkflow.core.data.library.TranscriptImportResult
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.AudioStorageState
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.LibraryFolderKind
import com.miuraken.chunkflow.core.model.LibraryPlaylist
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PlaylistId
import com.miuraken.chunkflow.core.model.TranscriptExportFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * テストとプレビュー用の [LibraryRepository]。
 *
 * 教材の一覧と教材ごとの状態はメモリ上に持つ。取り込みと同期の結果は [importResult] / [importTranscriptResult] / [syncResult] で決める。
 */
class FakeLibraryRepository(
    initialItems: List<AudioItem> = emptyList(),
    initialItemStates: Map<MediaId, ItemState> = emptyMap(),
    initialCourseFolders: List<LibraryCourseFolder> = emptyList(),
    initialPlaylists: List<LibraryPlaylist> = emptyList(),
) : LibraryRepository {
    /** 教材の一覧。テストから直接書き換えられる。 */
    val items: MutableStateFlow<List<AudioItem>> = MutableStateFlow(initialItems)

    /** 登録済みコースフォルダの一覧。テストから直接書き換えられる。 */
    val courseFolders: MutableStateFlow<List<LibraryCourseFolder>> = MutableStateFlow(initialCourseFolders)

    /** 教材ごとの再生・学習状態。テストから直接書き換えられる。 */
    val itemStates: MutableStateFlow<Map<MediaId, ItemState>> = MutableStateFlow(initialItemStates)

    /** プレイリストの一覧。テストから直接書き換えられる。 */
    val playlists: MutableStateFlow<List<LibraryPlaylist>> = MutableStateFlow(initialPlaylists)

    /** [import] に渡された URI（呼ばれた順）。 */
    val importedUris: MutableList<List<Uri>> = mutableListOf()

    /** [import]（コース名・言語ペア指定版）に渡された引数（呼ばれた順）。 */
    val importedConfigs: MutableList<Triple<List<Uri>, String?, LanguagePair?>> = mutableListOf()

    /** [linkLocalDirectory] に渡された引数（呼ばれた順）。 */
    val linkedLocalDirectories: MutableList<Triple<Uri, String?, LanguagePair?>> = mutableListOf()

    /** [rescanLocalDirectories] の呼び出し履歴（呼ばれた順）。 */
    val rescanRequests: MutableList<String?> = mutableListOf()

    /** [rescanLocalDirectories] が返す更新件数。 */
    var rescanResultCount: Int = 0

    /** [syncFromLinkedFolder] で登録された同期対象フォルダの Tree URI。 */
    var linkedSyncFolderUri: Uri? = null

    /** [syncFromLinkedFolder] で登録されたコース名。 */
    var linkedSyncCourse: String? = null

    /** [syncFromLinkedFolder] で登録された言語ペア。 */
    var linkedSyncLanguages: LanguagePair? = null

    /** [import] が流す進捗を決める。既定はすぐに「0 件で完了」。 */
    var importResult: (List<Uri>) -> Flow<ImportProgress> = {
        flowOf(ImportProgress.Copying(0f), ImportProgress.Done(emptyList(), duplicates = 0, transcriptsAttached = 0))
    }

    /** [importTranscript] の結果を決める。既定は「対応する音声がない」。 */
    var importTranscriptResult: (Uri) -> TranscriptImportResult = { TranscriptImportResult.NoMatchingAudio(null) }

    /** [importTranscriptForMedia] の呼び出し履歴（呼ばれた順）。 */
    val importedTranscriptsForMedia: MutableList<Pair<MediaId, Uri>> = mutableListOf()

    /** [resetTranscriptsAndDeleteFiles] が返す結果。 */
    var resetTranscriptsResult: Boolean = true

    /** [resetTranscriptsAndDeleteFiles] の呼び出し履歴（呼ばれた順）。 */
    val resetTranscriptMediaIds: MutableList<MediaId> = mutableListOf()

    /** [syncFromDrive] が流す進捗を決める。既定はすぐに「0 件で完了」。 */
    var syncResult: () -> Flow<SyncProgress> = {
        flowOf(
            SyncProgress.Listing,
            SyncProgress.Done(newAudio = 0, newTranscripts = 0, failed = 0, uploadedTranscripts = 0),
        )
    }

    /** [exportLocalTranscriptJson] が返す結果。 */
    var exportTranscriptResult: Boolean = true

    /** [exportLocalTranscriptJson] で書き出しが要求された教材 ID の一覧。 */
    val exportedTranscriptIds: MutableList<MediaId> = mutableListOf()

    /** 音声ファイルが欠損している教材 ID の集合（F2.4 バックアップ復元後の状態再現用）。 */
    val missingAudioIds: MutableStateFlow<Set<MediaId>> = MutableStateFlow(emptySet())

    /** [exportTranscriptText] が返す形式別の文字列マップ（F7.5）。 */
    val exportedTranscriptTexts: MutableMap<Pair<MediaId, TranscriptExportFormat>, String> = mutableMapOf()

    /** [writeExportedTranscriptToUri] が返す結果（F7.5）。 */
    var writeExportedTranscriptResult: Boolean = true

    /** [writeExportedTranscriptToUri] の呼び出し履歴（F7.5）。 */
    val writtenTranscriptExports: MutableList<Triple<MediaId, TranscriptExportFormat, Uri>> = mutableListOf()

    override fun observeItems(): Flow<List<AudioItem>> = items

    override fun observeCourseFolders(): Flow<List<LibraryCourseFolder>> = courseFolders

    override fun observePlaylists(): Flow<List<LibraryPlaylist>> = playlists

    override fun observeItem(id: MediaId): Flow<AudioItem?> = items.map { list -> list.firstOrNull { it.id == id } }

    override fun observeItemStates(): Flow<Map<MediaId, ItemState>> = itemStates

    override fun observeItemState(id: MediaId): Flow<ItemState?> = itemStates.map { map -> map[id] }

    override suspend fun upsertItemState(state: ItemState) {
        itemStates.update { current ->
            val existing = current[state.mediaId]
            val nextMaxPos = maxOf(
                existing?.maxPositionMs ?: 0L,
                existing?.positionMs ?: 0L,
                state.maxPositionMs,
                state.positionMs,
            )
            current + (state.mediaId to state.copy(maxPositionMs = nextMaxPos))
        }
    }

    override suspend fun updatePlaybackPosition(
        id: MediaId,
        positionMs: Long,
        speed: Float,
        updatedAtEpochMs: Long,
    ) {
        itemStates.update { current ->
            val existing = current[id]
            val nextMaxPos = maxOf(
                existing?.maxPositionMs ?: 0L,
                existing?.positionMs ?: 0L,
                positionMs,
            )
            val updated = existing?.copy(
                positionMs = positionMs,
                maxPositionMs = nextMaxPos,
                speed = speed,
                updatedAtEpochMs = updatedAtEpochMs,
            ) ?: ItemState(
                mediaId = id,
                positionMs = positionMs,
                maxPositionMs = nextMaxPos,
                speed = speed,
                updatedAtEpochMs = updatedAtEpochMs,
            )
            current + (id to updated)
        }
    }

    override fun import(uris: List<Uri>): Flow<ImportProgress> {
        importedUris += uris
        return importResult(uris)
    }

    override fun import(
        uris: List<Uri>,
        course: String?,
        languages: LanguagePair?,
    ): Flow<ImportProgress> {
        importedConfigs += Triple(uris, course, languages)
        if (course != null || languages != null) {
            items.update { list ->
                list.map { item ->
                    item.copy(
                        course = course ?: item.course,
                        languagePair = languages ?: item.languagePair,
                    )
                }
            }
        }
        return import(uris)
    }

    override fun linkLocalDirectory(
        treeUri: Uri,
        course: String?,
        languages: LanguagePair?,
    ): Flow<ImportProgress> {
        linkedLocalDirectories += Triple(treeUri, course, languages)
        val resolvedCourse = course?.trim()?.takeIf { it.isNotEmpty() } ?: "local-folder"
        val resolvedLanguages = languages ?: LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja"))
        courseFolders.update { list ->
            list.filterNot { it.coursePath == resolvedCourse } +
                LibraryCourseFolder(
                    coursePath = resolvedCourse,
                    kind = LibraryFolderKind.LOCAL_DIRECTORY,
                    languagePair = resolvedLanguages,
                    treeUri = treeUri.toString(),
                    lastScannedAtEpochMs = System.currentTimeMillis(),
                )
        }
        return import(listOf(treeUri), resolvedCourse, resolvedLanguages)
    }

    override suspend fun rescanLocalDirectories(coursePath: String?): Int {
        rescanRequests += coursePath
        return rescanResultCount
    }

    override suspend fun createVirtualFolder(
        coursePath: String,
        languages: LanguagePair,
    ): Boolean {
        val trimmed = coursePath.trim().trim('/')
        if (trimmed.isEmpty()) return false
        courseFolders.update { list ->
            val existing = list.firstOrNull { it.coursePath == trimmed }
            list.filterNot { it.coursePath == trimmed } +
                LibraryCourseFolder(
                    coursePath = trimmed,
                    kind = existing?.kind ?: LibraryFolderKind.VIRTUAL_DIRECTORY,
                    languagePair = languages,
                    treeUri = existing?.treeUri,
                    driveFolderId = existing?.driveFolderId,
                    lastScannedAtEpochMs = existing?.lastScannedAtEpochMs ?: 0L,
                )
        }
        return true
    }

    override suspend fun moveItemsToCourse(
        mediaIds: Set<MediaId>,
        targetCourse: String,
    ) {
        val normalized = targetCourse.trim().trim('/').ifEmpty { LibraryCourseFolder.DEFAULT_IMPORTED_COURSE }
        val folderLang = courseFolders.value.firstOrNull { it.coursePath == normalized }?.languagePair
        items.update { list ->
            list.map { item ->
                if (item.id in mediaIds) {
                    item.copy(
                        course = normalized,
                        languagePair = folderLang ?: item.languagePair,
                    )
                } else {
                    item
                }
            }
        }
    }

    override suspend fun importTranscript(uri: Uri): TranscriptImportResult = importTranscriptResult(uri)

    override suspend fun importTranscriptForMedia(id: MediaId, uri: Uri): TranscriptImportResult {
        importedTranscriptsForMedia += id to uri
        return importTranscriptResult(uri)
    }

    override suspend fun resetTranscriptsAndDeleteFiles(id: MediaId): Boolean {
        resetTranscriptMediaIds += id
        return resetTranscriptsResult
    }

    override fun syncFromDrive(): Flow<SyncProgress> = syncResult()

    override suspend fun downloadDriveAudio(id: MediaId): Boolean {
        var found = false
        items.update { list ->
            list.map { item ->
                if (item.id == id) {
                    found = true
                    item.copy(storageState = AudioStorageState.DRIVE_DOWNLOADED)
                } else {
                    item
                }
            }
        }
        missingAudioIds.update { it - id }
        return found
    }

    override suspend fun deleteLocalAudioCopy(id: MediaId): Boolean {
        var found = false
        items.update { list ->
            list.map { item ->
                if (item.id == id) {
                    found = true
                    item.copy(storageState = AudioStorageState.DRIVE_REMOTE_ONLY)
                } else {
                    item
                }
            }
        }
        return found
    }

    override suspend fun hasLinkedSyncFolder(): Boolean = linkedSyncFolderUri != null

    override fun syncFromLinkedFolder(
        treeUri: Uri?,
        course: String?,
        languages: LanguagePair?,
    ): Flow<SyncProgress> {
        if (treeUri != null) {
            linkedSyncFolderUri = treeUri
        }
        if (course != null) {
            linkedSyncCourse = course
        }
        if (languages != null) {
            linkedSyncLanguages = languages
        }
        return syncFromDrive()
    }

    override suspend fun updateItemMetadata(
        id: MediaId,
        course: String?,
        languages: LanguagePair,
    ) {
        items.update { list ->
            list.map { item ->
                if (item.id == id) {
                    item.copy(course = course, languagePair = languages)
                } else {
                    item
                }
            }
        }
    }

    override suspend fun updateDirectoryMetadata(
        currentCourse: String?,
        newCourse: String?,
        languages: LanguagePair,
    ) {
        items.update { list ->
            list.map { item ->
                when {
                    item.course == currentCourse -> {
                        item.copy(course = newCourse, languagePair = languages)
                    }

                    currentCourse != null && item.course?.startsWith("$currentCourse/") == true -> {
                        val suffix = item.course.orEmpty().removePrefix("$currentCourse/")
                        val updatedChildCourse = if (!newCourse.isNullOrBlank()) "$newCourse/$suffix" else suffix
                        item.copy(course = updatedChildCourse, languagePair = languages)
                    }

                    else -> {
                        item
                    }
                }
            }
        }
        if (!currentCourse.isNullOrBlank()) {
            val targetPath = newCourse?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: currentCourse
            courseFolders.update { folders ->
                folders.map { folder ->
                    if (folder.coursePath == currentCourse) {
                        folder.copy(coursePath = targetPath, languagePair = languages)
                    } else {
                        folder
                    }
                }
            }
        }
    }

    private var nextQueueSequence: Long = 1L
    private var nextPlaylistSequence: Long = 1L

    override suspend fun markItemListened(
        id: MediaId,
        isListened: Boolean,
        updatedAtEpochMs: Long,
    ) {
        itemStates.update { current ->
            val existing = current[id]
            val updated = existing?.copy(
                isListened = isListened,
                updatedAtEpochMs = updatedAtEpochMs,
            ) ?: ItemState(
                mediaId = id,
                isListened = isListened,
                updatedAtEpochMs = updatedAtEpochMs,
            )
            current + (id to updated)
        }
    }

    override suspend fun toggleFavorite(id: MediaId) {
        itemStates.update { current ->
            val existing = current[id]
            val nextFavorite = !(existing?.isFavorite ?: false)
            val updated = existing?.copy(
                isFavorite = nextFavorite,
            ) ?: ItemState(
                mediaId = id,
                isFavorite = nextFavorite,
            )
            current + (id to updated)
        }
    }

    override suspend fun toggleQueueItem(
        id: MediaId,
        queuedAtEpochMs: Long,
    ) {
        itemStates.update { current ->
            val existing = current[id]
            val maxExistingQueue = current.values.maxOfOrNull { it.queuedAtEpochMs } ?: 0L
            val nextSeq = maxOf(queuedAtEpochMs, maxExistingQueue + 1L, nextQueueSequence++)
            val nextQueuedAt = if ((existing?.queuedAtEpochMs ?: 0L) > 0L) 0L else nextSeq
            val updated = existing?.copy(
                queuedAtEpochMs = nextQueuedAt,
            ) ?: ItemState(
                mediaId = id,
                queuedAtEpochMs = nextQueuedAt,
            )
            current + (id to updated)
        }
    }

    override suspend fun recordItemAccess(
        id: MediaId,
        accessedAtEpochMs: Long,
    ) {
        itemStates.update { current ->
            val existing = current[id]
            val nextCount = (existing?.accessCount ?: 0) + 1
            val nextUpdatedAt = maxOf(existing?.updatedAtEpochMs ?: 0L, accessedAtEpochMs)
            val updated = existing?.copy(
                accessCount = nextCount,
                updatedAtEpochMs = nextUpdatedAt,
            ) ?: ItemState(
                mediaId = id,
                accessCount = nextCount,
                updatedAtEpochMs = nextUpdatedAt,
            )
            current + (id to updated)
        }
    }

    override suspend fun createPlaylist(
        name: String,
        initialMediaIds: List<MediaId>,
    ): PlaylistId? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null
        val seq = nextPlaylistSequence++
        val id = PlaylistId("user:$seq")
        playlists.update { current ->
            val maxSortOrder = maxOf(
                current.maxOfOrNull { it.sortOrder } ?: -1,
                PlaylistId.BUILT_IN_IDS.size - 1,
            )
            val newPlaylist = LibraryPlaylist(
                id = id,
                name = trimmed,
                isBuiltIn = false,
                sortOrder = maxSortOrder + 1,
                createdAtEpochMs = seq,
                itemIds = initialMediaIds.distinct(),
            )
            (current + newPlaylist).sortedWith(compareBy({ it.sortOrder }, { it.createdAtEpochMs }, { it.id.value }))
        }
        return id
    }

    override suspend fun deletePlaylist(id: PlaylistId) {
        if (id in PlaylistId.BUILT_IN_IDS) return
        playlists.update { current -> current.filterNot { it.id == id } }
    }

    override suspend fun addItemsToPlaylist(
        playlistId: PlaylistId,
        mediaIds: List<MediaId>,
    ) {
        if (mediaIds.isEmpty()) return
        playlists.update { current ->
            current.map { playlist ->
                if (playlist.id == playlistId) {
                    val existingSet = playlist.itemIds.toSet()
                    val added = mediaIds.distinct().filter { it !in existingSet }
                    playlist.copy(itemIds = playlist.itemIds + added)
                } else {
                    playlist
                }
            }
        }
    }

    override suspend fun removeItemFromPlaylist(
        playlistId: PlaylistId,
        mediaId: MediaId,
    ) {
        playlists.update { current ->
            current.map { playlist ->
                if (playlist.id == playlistId) {
                    playlist.copy(itemIds = playlist.itemIds.filterNot { it == mediaId })
                } else {
                    playlist
                }
            }
        }
    }

    override suspend fun reorderPlaylists(orderedIds: List<PlaylistId>) {
        if (orderedIds.isEmpty()) return
        playlists.update { current ->
            val byId = current.associateBy { it.id }
            val updated = orderedIds.distinct().mapIndexedNotNull { index, playlistId ->
                val existing = byId[playlistId]
                when {
                    existing != null -> existing.copy(sortOrder = index)

                    playlistId in PlaylistId.BUILT_IN_IDS -> LibraryPlaylist(
                        id = playlistId,
                        name = "",
                        isBuiltIn = true,
                        sortOrder = index,
                    )

                    else -> null
                }
            }
            val updatedIds = updated.map { it.id }.toSet()
            val remaining = current.filterNot { it.id in updatedIds }
            (updated + remaining).sortedWith(compareBy({ it.sortOrder }, { it.createdAtEpochMs }, { it.id.value }))
        }
    }

    override suspend fun reorderPlaylistItems(
        playlistId: PlaylistId,
        orderedMediaIds: List<MediaId>,
    ) {
        playlists.update { current ->
            val existing = current.firstOrNull { it.id == playlistId }
            val distinctIds = orderedMediaIds.distinct()
            if (existing != null) {
                current.map { playlist ->
                    if (playlist.id == playlistId) playlist.copy(itemIds = distinctIds) else playlist
                }
            } else if (playlistId in PlaylistId.BUILT_IN_IDS) {
                val created = LibraryPlaylist(
                    id = playlistId,
                    name = "",
                    isBuiltIn = true,
                    sortOrder = PlaylistId.BUILT_IN_IDS.indexOf(playlistId).coerceAtLeast(0),
                    itemIds = distinctIds,
                )
                (current + created).sortedWith(compareBy({ it.sortOrder }, { it.createdAtEpochMs }, { it.id.value }))
            } else {
                current
            }
        }
    }

    override suspend fun reorderAllFiles(orderedMediaIds: List<MediaId>) {
        itemStates.update { current ->
            val next = current.toMutableMap()
            orderedMediaIds.distinct().forEachIndexed { index, mediaId ->
                val existing = next[mediaId]
                next[mediaId] = existing?.copy(fileSortOrder = index) ?: ItemState(
                    mediaId = mediaId,
                    fileSortOrder = index,
                )
            }
            next
        }
    }

    override suspend fun exportLocalTranscriptJson(id: MediaId): Boolean {
        exportedTranscriptIds += id
        return exportTranscriptResult
    }

    override fun observeMissingAudioIds(): Flow<Set<MediaId>> = missingAudioIds

    override suspend fun exportTranscriptText(id: MediaId, format: TranscriptExportFormat): String? = exportedTranscriptTexts[id to format]

    override suspend fun writeExportedTranscriptToUri(
        id: MediaId,
        format: TranscriptExportFormat,
        targetUri: Uri,
    ): Boolean {
        writtenTranscriptExports += Triple(id, format, targetUri)
        return writeExportedTranscriptResult
    }

    override suspend fun delete(id: MediaId) {
        items.update { list -> list.filterNot { it.id == id } }
        itemStates.update { current -> current - id }
        missingAudioIds.update { current -> current - id }
        playlists.update { current ->
            current.map { playlist ->
                playlist.copy(itemIds = playlist.itemIds.filterNot { it == id })
            }
        }
    }
}
