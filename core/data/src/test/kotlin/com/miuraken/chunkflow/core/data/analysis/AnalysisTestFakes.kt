package com.miuraken.chunkflow.core.data.analysis

import com.miuraken.chunkflow.core.database.dao.AnalysisStateDao
import com.miuraken.chunkflow.core.database.dao.BookmarkDao
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.dao.TranscriptDao
import com.miuraken.chunkflow.core.database.entity.AnalysisStateEntity
import com.miuraken.chunkflow.core.database.entity.BookmarkEntity
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.UserSettings
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal class FakeAnalysisStateDao : AnalysisStateDao {
    private val store = MutableStateFlow<Map<Pair<String, String>, AnalysisStateEntity>>(emptyMap())

    override fun observeByMediaId(mediaId: String): Flow<List<AnalysisStateEntity>> = store.map { map -> map.values.filter { it.mediaId == mediaId } }

    override suspend fun getByMediaId(mediaId: String): List<AnalysisStateEntity> = store.value.values.filter { it.mediaId == mediaId }

    override suspend fun upsert(state: AnalysisStateEntity) {
        store.update { current -> current + ((state.mediaId to state.stage) to state) }
    }

    override suspend fun upsertAll(states: List<AnalysisStateEntity>) {
        store.update { current ->
            val next = current.toMutableMap()
            for (state in states) {
                next[state.mediaId to state.stage] = state
            }
            next
        }
    }

    override suspend fun deleteByMediaId(mediaId: String) {
        store.update { current -> current.filterKeys { it.first != mediaId } }
    }
}

internal class FakeTranscriptDao : TranscriptDao {
    private val store = MutableStateFlow<Map<Long, TranscriptEntity>>(emptyMap())
    private var chunksProvider: () -> List<ChunkEntity> = { emptyList() }

    fun bindChunksProvider(provider: () -> List<ChunkEntity>) {
        chunksProvider = provider
    }

    override fun observeByMediaId(mediaId: String): Flow<List<TranscriptEntity>> = store.map { map ->
        val orderedChunkIds = chunksProvider()
            .filter { it.mediaId == mediaId }
            .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
            .map { it.id }
        orderedChunkIds.mapNotNull { map[it] }
    }

    override fun observeByMediaIdAndOrigin(mediaId: String, origin: String): Flow<List<TranscriptEntity>> = store.map { map ->
        val orderedChunkIds = chunksProvider()
            .filter { it.mediaId == mediaId && it.origin == origin }
            .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
            .map { it.id }
        orderedChunkIds.mapNotNull { map[it] }
    }

    override suspend fun getByChunkId(chunkId: Long): TranscriptEntity? = store.value[chunkId]

    override suspend fun getByChunkIds(chunkIds: List<Long>): List<TranscriptEntity> = chunkIds.mapNotNull { store.value[it] }

    override suspend fun upsert(transcript: TranscriptEntity) {
        store.update { it + (transcript.chunkId to transcript) }
    }

    override suspend fun upsertAll(transcripts: List<TranscriptEntity>) {
        store.update { current ->
            val next = current.toMutableMap()
            for (transcript in transcripts) {
                next[transcript.chunkId] = transcript
            }
            next
        }
    }

    override suspend fun updateLanguage(chunkId: Long, language: String?) {
        store.update { current ->
            val existing = current[chunkId] ?: return@update current
            current + (chunkId to existing.copy(language = language, languageConfidence = 1.0f, userEdited = true))
        }
    }

    override suspend fun bulkSetUnknownLanguageForMedia(mediaId: String, language: String) {
        val mediaChunkIds = chunksProvider().filter { it.mediaId == mediaId }.map { it.id }.toSet()
        store.update { current ->
            current.mapValues { (chunkId, existing) ->
                if (chunkId in mediaChunkIds && (existing.language.isNullOrEmpty() || existing.language == "und")) {
                    existing.copy(language = language, languageConfidence = 1.0f, userEdited = true)
                } else {
                    existing
                }
            }
        }
    }

    override suspend fun deleteByChunkId(chunkId: Long) {
        store.update { current -> current - chunkId }
    }

    fun deleteByChunkIds(chunkIds: List<Long>) {
        store.update { current -> current - chunkIds.toSet() }
    }
}

internal class FakeChunkDao(
    private val transcriptDao: FakeTranscriptDao,
) : ChunkDao {
    private val nextId = AtomicLong(1L)
    private val store = MutableStateFlow<Map<Long, ChunkEntity>>(emptyMap())

    init {
        transcriptDao.bindChunksProvider { store.value.values.toList() }
    }

    override fun observeByMediaIdAndOrigin(mediaId: String, origin: String): Flow<List<ChunkEntity>> = store.map { map ->
        map.values
            .filter { it.mediaId == mediaId && it.origin == origin }
            .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
    }

    override fun observeByMediaId(mediaId: String): Flow<List<ChunkEntity>> = store.map { map ->
        map.values
            .filter { it.mediaId == mediaId }
            .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
    }

    override fun observeOriginsByMediaId(mediaId: String): Flow<List<String>> = store.map { map ->
        map.values
            .filter { it.mediaId == mediaId }
            .map { it.origin }
            .distinct()
    }

    override suspend fun getByMediaIdAndOrigin(mediaId: String, origin: String): List<ChunkEntity> = store.value.values
        .filter { it.mediaId == mediaId && it.origin == origin }
        .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })

    override suspend fun insertAll(chunks: List<ChunkEntity>): List<Long> {
        val assignedIds = ArrayList<Long>(chunks.size)
        store.update { current ->
            val next = current.toMutableMap()
            for (chunk in chunks) {
                val id = if (chunk.id > 0L) chunk.id else nextId.getAndIncrement()
                assignedIds.add(id)
                next[id] = chunk.copy(id = id)
            }
            next
        }
        return assignedIds
    }

    override suspend fun getTranscriptsByChunkIds(chunkIds: List<Long>): List<TranscriptEntity> = transcriptDao.getByChunkIds(chunkIds)

    override suspend fun upsertTranscripts(transcripts: List<TranscriptEntity>) {
        transcriptDao.upsertAll(transcripts)
    }

    override suspend fun deleteByIds(chunkIds: List<Long>) {
        store.update { current -> current - chunkIds.toSet() }
        transcriptDao.deleteByChunkIds(chunkIds)
    }
}

internal class FakeItemStateDao : ItemStateDao {
    private val store = MutableStateFlow<Map<String, ItemStateEntity>>(emptyMap())

    override fun observeAll(): Flow<List<ItemStateEntity>> = store.map { it.values.toList() }

    override fun observeByMediaId(mediaId: String): Flow<ItemStateEntity?> = store.map { it[mediaId] }

    override suspend fun getByMediaId(mediaId: String): ItemStateEntity? = store.value[mediaId]

    override suspend fun upsert(state: ItemStateEntity) {
        store.update { it + (state.mediaId to state) }
    }

    override suspend fun updateChunkSource(mediaId: String, chunkSource: String, updatedAt: Long): Int {
        var updated = 0
        store.update { current ->
            val existing = current[mediaId] ?: return@update current
            updated = 1
            current + (mediaId to existing.copy(chunkSource = chunkSource, updatedAt = updatedAt))
        }
        return updated
    }
}

internal class FakeMediaItemDao : MediaItemDao {
    private val store = MutableStateFlow<Map<String, MediaItemEntity>>(emptyMap())

    override fun observeAll(): Flow<List<MediaItemEntity>> = store.map { it.values.toList() }

    override fun observeById(id: String): Flow<MediaItemEntity?> = store.map { it[id] }

    override suspend fun getById(id: String): MediaItemEntity? = store.value[id]

    override suspend fun getBySha256(sha256: String): MediaItemEntity? = store.value.values.firstOrNull { it.sha256 == sha256 }

    override suspend fun upsert(item: MediaItemEntity) {
        store.update { it + (item.id to item) }
    }

    override suspend fun insertOrIgnore(item: MediaItemEntity): Long {
        if (store.value.containsKey(item.id)) return -1L
        store.update { it + (item.id to item) }
        return 1L
    }

    override suspend fun update(item: MediaItemEntity) {
        store.update { it + (item.id to item) }
    }

    override suspend fun updateTranscriptOffset(id: String, offsetMs: Int) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to existing.copy(transcriptOffsetMs = offsetMs))
        }
    }

    override suspend fun updateLanguagePair(id: String, targetLang: String, baseLang: String?) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to existing.copy(targetLang = targetLang, baseLang = baseLang))
        }
    }

    override suspend fun updateCourse(id: String, course: String?) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to existing.copy(course = course))
        }
    }

    override suspend fun updateCourseAndLanguagePair(
        id: String,
        course: String?,
        targetLang: String,
        baseLang: String?,
    ) {
        store.update { current ->
            val existing = current[id] ?: return@update current
            current + (id to existing.copy(course = course, targetLang = targetLang, baseLang = baseLang))
        }
    }

    override suspend fun updateCourseMetadata(
        oldCourse: String,
        newCourse: String?,
        targetLang: String,
        baseLang: String?,
    ) {
        store.update { current ->
            current.mapValues { (_, item) ->
                if (item.course == oldCourse) {
                    item.copy(course = newCourse, targetLang = targetLang, baseLang = baseLang)
                } else {
                    item
                }
            }
        }
    }

    override suspend fun updateUncategorizedCourseMetadata(
        newCourse: String?,
        targetLang: String,
        baseLang: String?,
    ) {
        store.update { current ->
            current.mapValues { (_, item) ->
                if (item.course == null) {
                    item.copy(course = newCourse, targetLang = targetLang, baseLang = baseLang)
                } else {
                    item
                }
            }
        }
    }

    override suspend fun deleteById(id: String) {
        store.update { it - id }
    }
}

internal class FakeUserSettingsRepository(
    initialSettings: UserSettings = UserSettings(),
) : UserSettingsRepository {
    private val state = MutableStateFlow(initialSettings)

    override val settings: Flow<UserSettings> = state

    override suspend fun setDefaultLanguagePair(pair: LanguagePair) {
        state.update { it.copy(defaultLanguagePair = pair) }
    }

    override suspend fun setChineseScript(script: ChineseScript) {
        state.update { it.copy(chineseScript = script) }
    }

    override suspend fun setCantoneseScript(script: ChineseScript) {
        state.update { it.copy(cantoneseScript = script) }
    }

    override suspend fun setDefaultSpeed(speed: Float) {
        state.update { it.copy(defaultSpeed = speed) }
    }

    override suspend fun setSeekBackMs(seekBackMs: Long) {
        state.update { it.copy(seekBackMs = seekBackMs) }
    }

    override suspend fun setSeekForwardMs(seekForwardMs: Long) {
        state.update { it.copy(seekForwardMs = seekForwardMs) }
    }

    override suspend fun setDefaultAutoPause(autoPause: AutoPause) {
        state.update { it.copy(defaultAutoPause = autoPause) }
    }

    override suspend fun setDefaultSegmentationParams(params: SegmentationParams) {
        state.update { it.copy(defaultSegmentationParams = params) }
    }
}

internal class FakeBookmarkDao : BookmarkDao {
    private val nextId = AtomicLong(1L)
    private val store = MutableStateFlow<Map<Long, BookmarkEntity>>(emptyMap())

    override fun observeByMediaId(mediaId: String): Flow<List<BookmarkEntity>> = store.map { map ->
        map.values
            .filter { it.mediaId == mediaId }
            .sortedWith(compareBy<BookmarkEntity> { it.positionMs }.thenBy { it.id })
    }

    override suspend fun getByMediaId(mediaId: String): List<BookmarkEntity> = store.value.values
        .filter { it.mediaId == mediaId }
        .sortedWith(compareBy<BookmarkEntity> { it.positionMs }.thenBy { it.id })

    override suspend fun insert(bookmark: BookmarkEntity): Long {
        val assignedId = if (bookmark.id > 0L) bookmark.id else nextId.getAndIncrement()
        store.update { current -> current + (assignedId to bookmark.copy(id = assignedId)) }
        return assignedId
    }

    override suspend fun upsert(bookmark: BookmarkEntity): Long = insert(bookmark)

    override suspend fun deleteById(id: Long) {
        store.update { current -> current - id }
    }
}
