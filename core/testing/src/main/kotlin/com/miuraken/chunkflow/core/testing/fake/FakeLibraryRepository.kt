package com.miuraken.chunkflow.core.testing.fake

import android.net.Uri
import com.miuraken.chunkflow.core.data.library.ImportProgress
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.data.library.SyncProgress
import com.miuraken.chunkflow.core.data.library.TranscriptImportResult
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.MediaId
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
) : LibraryRepository {
    /** 教材の一覧。テストから直接書き換えられる。 */
    val items: MutableStateFlow<List<AudioItem>> = MutableStateFlow(initialItems)

    /** 教材ごとの再生・学習状態。テストから直接書き換えられる。 */
    val itemStates: MutableStateFlow<Map<MediaId, ItemState>> = MutableStateFlow(initialItemStates)

    /** [import] に渡された URI（呼ばれた順）。 */
    val importedUris: MutableList<List<Uri>> = mutableListOf()

    /** [import] が流す進捗を決める。既定はすぐに「0 件で完了」。 */
    var importResult: (List<Uri>) -> Flow<ImportProgress> = {
        flowOf(ImportProgress.Copying(0f), ImportProgress.Done(emptyList(), duplicates = 0, transcriptsAttached = 0))
    }

    /** [importTranscript] の結果を決める。既定は「対応する音声がない」。 */
    var importTranscriptResult: (Uri) -> TranscriptImportResult = { TranscriptImportResult.NoMatchingAudio(null) }

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

    override fun observeItems(): Flow<List<AudioItem>> = items

    override fun observeItem(id: MediaId): Flow<AudioItem?> = items.map { list -> list.firstOrNull { it.id == id } }

    override fun observeItemStates(): Flow<Map<MediaId, ItemState>> = itemStates

    override fun observeItemState(id: MediaId): Flow<ItemState?> = itemStates.map { map -> map[id] }

    override suspend fun upsertItemState(state: ItemState) {
        itemStates.update { current -> current + (state.mediaId to state) }
    }

    override suspend fun updatePlaybackPosition(
        id: MediaId,
        positionMs: Long,
        speed: Float,
        updatedAtEpochMs: Long,
    ) {
        itemStates.update { current ->
            val existing = current[id]
            val updated = existing?.copy(
                positionMs = positionMs,
                speed = speed,
                updatedAtEpochMs = updatedAtEpochMs,
            ) ?: ItemState(
                mediaId = id,
                positionMs = positionMs,
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

    override suspend fun importTranscript(uri: Uri): TranscriptImportResult = importTranscriptResult(uri)

    override fun syncFromDrive(): Flow<SyncProgress> = syncResult()

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

    override suspend fun exportLocalTranscriptJson(id: MediaId): Boolean {
        exportedTranscriptIds += id
        return exportTranscriptResult
    }

    override suspend fun delete(id: MediaId) {
        items.update { list -> list.filterNot { it.id == id } }
        itemStates.update { current -> current - id }
    }
}
