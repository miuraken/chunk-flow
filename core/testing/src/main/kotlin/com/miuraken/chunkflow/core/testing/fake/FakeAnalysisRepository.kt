package com.miuraken.chunkflow.core.testing.fake

import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.Bookmark
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterial
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * テストとプレビュー用の [AnalysisRepository]。
 *
 * 教材ごとの解析結果をメモリ上に持つ。[put] でフィクスチャ（[SampleMaterial]）を入れるか、[update] で個別に書き換える。
 * 解析の要求は実行せず、[requestedAnalyses] などに記録するだけ。
 */
class FakeAnalysisRepository : AnalysisRepository {
    /**
     * 教材 1 件分の解析結果。
     *
     * @property status 段階ごとの状態。
     * @property waveform 波形。
     * @property chunks チャンク。
     * @property transcripts チャンクごとの文字起こし。
     * @property availableOrigins 利用可能なチャンク出どころの明示指定（`null` の場合は [chunks] および [chunksByOrigin] から算出）。
     * @property chunksByOrigin 出どころ別のチャンク一覧（[setChunkSource] で切り替えを再現したい場合に設定する）。
     * @property lookaheadStatus 端末上の先読み簡易文字起こし状態（F6.2）。
     * @property bookmarks ブックマーク一覧（F8.1）。
     */
    data class Entry(
        val status: Map<AnalysisStage, StageStatus> = emptyMap(),
        val waveform: Waveform? = null,
        val chunks: List<Chunk> = emptyList(),
        val transcripts: Map<ChunkId, Transcript> = emptyMap(),
        val availableOrigins: Set<ChunkOrigin>? = null,
        val chunksByOrigin: Map<ChunkOrigin, List<Chunk>> = emptyMap(),
        val lookaheadStatus: LookaheadTranscriptionStatus = LookaheadTranscriptionStatus(),
        val bookmarks: List<Bookmark> = emptyList(),
    )

    private val entries = MutableStateFlow<Map<MediaId, Entry>>(emptyMap())
    private var nextBookmarkId = 1L
    private var nextManualChunkId = 10_000L

    /** [requestAnalysis] の呼び出し（呼ばれた順）。 */
    val requestedAnalyses: MutableList<Pair<MediaId, Set<AnalysisStage>>> = mutableListOf()

    /** [resegment] の呼び出し（呼ばれた順）。 */
    val resegmentRequests: MutableList<Pair<MediaId, SegmentationParams>> = mutableListOf()

    /** [setChunkSource] で設定された出どころ。 */
    val chunkSources: MutableMap<MediaId, ChunkOrigin> = mutableMapOf()

    /** [setTranscriptOffset] で設定された補正値。 */
    val transcriptOffsets: MutableMap<MediaId, Int> = mutableMapOf()

    /** [autoAlignTranscriptOffset] が返す自動補正値のスタブ設定。 */
    val autoAlignedOffsets: MutableMap<MediaId, Int?> = mutableMapOf()

    /** [triggerLookaheadAt] の呼び出し履歴（呼ばれた順）。 */
    val lookaheadTriggers: MutableList<Pair<MediaId, ChunkId?>> = mutableListOf()

    /** フィクスチャの解析結果を入れる。 */
    fun put(material: SampleMaterial) {
        update(material.item.id) {
            Entry(material.analysis, material.waveform, material.chunks, material.transcripts)
        }
    }

    /** 教材 [id] の解析結果を [transform] で書き換える。 */
    fun update(id: MediaId, transform: (Entry) -> Entry) {
        entries.update { map -> map + (id to transform(map[id] ?: Entry())) }
    }

    override fun observeStatus(id: MediaId): Flow<Map<AnalysisStage, StageStatus>> = entries.map { it[id]?.status.orEmpty() }

    override fun observeWaveform(id: MediaId): Flow<Waveform?> = entries.map { it[id]?.waveform }

    override fun observeChunks(id: MediaId): Flow<List<Chunk>> = entries.map { it[id]?.chunks.orEmpty() }

    override fun observeTranscripts(id: MediaId): Flow<Map<ChunkId, Transcript>> = entries.map { it[id]?.transcripts.orEmpty() }

    override fun observeAvailableChunkOrigins(id: MediaId): Flow<Set<ChunkOrigin>> = entries.map { map ->
        val entry = map[id] ?: return@map emptySet()
        entry.availableOrigins
            ?: (entry.chunksByOrigin.keys + entry.chunks.map { it.origin })
    }

    override fun observeLookaheadStatus(id: MediaId): Flow<LookaheadTranscriptionStatus> = entries.map { it[id]?.lookaheadStatus ?: LookaheadTranscriptionStatus() }

    override fun observeBookmarks(id: MediaId): Flow<List<Bookmark>> = entries.map { map ->
        map[id]?.bookmarks.orEmpty().sortedWith(compareBy<Bookmark> { it.positionMs }.thenBy { it.id })
    }

    override suspend fun requestAnalysis(id: MediaId, stages: Set<AnalysisStage>) {
        requestedAnalyses += id to stages
    }

    override suspend fun resegment(id: MediaId, params: SegmentationParams) {
        resegmentRequests += id to params
    }

    override suspend fun setChunkSource(id: MediaId, source: ChunkOrigin) {
        chunkSources[id] = source
        entries.update { map ->
            val current = map[id] ?: return@update map
            val switched = current.chunksByOrigin[source] ?: return@update map
            map + (id to current.copy(chunks = switched))
        }
    }

    override suspend fun setTranscriptOffset(id: MediaId, offsetMs: Int) {
        transcriptOffsets[id] = offsetMs
    }

    override suspend fun updateChunkLanguage(id: MediaId, chunkId: ChunkId, language: LanguageTag?) {
        update(id) { entry ->
            val existing = entry.transcripts[chunkId] ?: return@update entry
            val updated = existing.copy(
                language = language,
                languageConfidence = 1.0f,
                userEdited = true,
            )
            entry.copy(transcripts = entry.transcripts + (chunkId to updated))
        }
    }

    override suspend fun bulkSetUnknownTranscriptLanguage(id: MediaId, language: LanguageTag) {
        update(id) { entry ->
            val updatedTranscripts = entry.transcripts.mapValues { (_, transcript) ->
                val tag = transcript.language?.value
                if (tag.isNullOrBlank() || tag == "und") {
                    transcript.copy(
                        language = language,
                        languageConfidence = 1.0f,
                        userEdited = true,
                    )
                } else {
                    transcript
                }
            }
            entry.copy(transcripts = updatedTranscripts)
        }
    }

    override suspend fun autoAlignTranscriptOffset(id: MediaId): Int? {
        val aligned = autoAlignedOffsets[id]
        if (aligned != null) {
            transcriptOffsets[id] = aligned
        }
        return aligned
    }

    override suspend fun setLookaheadEnabled(id: MediaId, enabled: Boolean) {
        update(id) { entry ->
            entry.copy(lookaheadStatus = entry.lookaheadStatus.copy(enabled = enabled))
        }
    }

    override suspend fun triggerLookaheadAt(id: MediaId, currentChunkId: ChunkId?) {
        lookaheadTriggers += id to currentChunkId
    }

    override suspend fun toggleChunkBookmark(id: MediaId, chunkId: ChunkId, note: String): Boolean {
        var added = false
        update(id) { entry ->
            val targetChunk = entry.chunks.firstOrNull { it.id == chunkId } ?: return@update entry
            val matching = entry.bookmarks.filter { it.positionMs in targetChunk.startMs until targetChunk.endMs }
            val nextBookmarks = if (matching.isNotEmpty()) {
                added = false
                entry.bookmarks.filterNot { it.positionMs in targetChunk.startMs until targetChunk.endMs }
            } else {
                added = true
                entry.bookmarks + Bookmark(
                    id = nextBookmarkId++,
                    mediaId = id,
                    positionMs = targetChunk.startMs,
                    note = note,
                )
            }
            entry.copy(bookmarks = nextBookmarks)
        }
        return added
    }

    override suspend fun updateChunkText(id: MediaId, chunkId: ChunkId, text: String) {
        update(id) { entry ->
            val existing = entry.transcripts[chunkId]
            val updated = if (existing != null) {
                existing.copy(
                    text = text,
                    textVariants = emptyMap(),
                    words = emptyList(),
                    userEdited = true,
                )
            } else {
                Transcript(
                    chunkId = chunkId,
                    text = text,
                    language = null,
                    languageConfidence = null,
                    engine = EngineId("manual"),
                    userEdited = true,
                )
            }
            entry.copy(transcripts = entry.transcripts + (chunkId to updated))
        }
    }

    override suspend fun splitChunk(id: MediaId, chunkId: ChunkId, splitPositionMs: Long): Boolean {
        var success = false
        update(id) { entry ->
            val idx = entry.chunks.indexOfFirst { it.id == chunkId }
            if (idx < 0) return@update entry
            val target = entry.chunks[idx]
            if (splitPositionMs <= target.startMs || splitPositionMs >= target.endMs) {
                return@update entry
            }
            success = true
            val first = target.copy(endMs = splitPositionMs, origin = ChunkOrigin.MANUAL)
            val second = Chunk(
                id = ChunkId(nextManualChunkId++),
                startMs = splitPositionMs,
                endMs = target.endMs,
                origin = ChunkOrigin.MANUAL,
            )
            val manualChunks = entry.chunks.flatMapIndexed { index, chunk ->
                if (index == idx) {
                    listOf(first, second)
                } else {
                    listOf(chunk.copy(origin = ChunkOrigin.MANUAL))
                }
            }
            chunkSources[id] = ChunkOrigin.MANUAL
            entry.copy(
                chunks = manualChunks,
                chunksByOrigin = entry.chunksByOrigin + (ChunkOrigin.MANUAL to manualChunks),
            )
        }
        return success
    }

    override suspend fun mergeWithNextChunk(id: MediaId, chunkId: ChunkId): Boolean {
        var success = false
        update(id) { entry ->
            val idx = entry.chunks.indexOfFirst { it.id == chunkId }
            if (idx < 0 || idx + 1 >= entry.chunks.size) return@update entry
            val current = entry.chunks[idx]
            val next = entry.chunks[idx + 1]
            success = true

            val merged = current.copy(endMs = next.endMs, origin = ChunkOrigin.MANUAL)
            val manualChunks = buildList {
                for (i in entry.chunks.indices) {
                    when (i) {
                        idx -> add(merged)
                        idx + 1 -> Unit
                        else -> add(entry.chunks[i].copy(origin = ChunkOrigin.MANUAL))
                    }
                }
            }
            val currentTranscript = entry.transcripts[current.id]
            val nextTranscript = entry.transcripts[next.id]
            val updatedTranscripts = entry.transcripts.toMutableMap().apply {
                remove(next.id)
                if (currentTranscript != null && nextTranscript != null) {
                    val combinedText = listOf(currentTranscript.text, nextTranscript.text)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    put(
                        current.id,
                        currentTranscript.copy(
                            text = combinedText,
                            textVariants = emptyMap(),
                            words = emptyList(),
                            userEdited = true,
                        ),
                    )
                } else if (currentTranscript == null && nextTranscript != null) {
                    put(current.id, nextTranscript.copy(chunkId = current.id, userEdited = true))
                }
            }
            chunkSources[id] = ChunkOrigin.MANUAL
            entry.copy(
                chunks = manualChunks,
                transcripts = updatedTranscripts,
                chunksByOrigin = entry.chunksByOrigin + (ChunkOrigin.MANUAL to manualChunks),
            )
        }
        return success
    }

    override suspend fun moveChunkBoundary(
        id: MediaId,
        chunkId: ChunkId,
        newStartMs: Long,
        newEndMs: Long,
    ): Boolean {
        var success = false
        update(id) { entry ->
            val idx = entry.chunks.indexOfFirst { it.id == chunkId }
            if (idx < 0 || newStartMs < 0L || newStartMs >= newEndMs) return@update entry
            val current = entry.chunks[idx]
            val prev = entry.chunks.getOrNull(idx - 1)
            val next = entry.chunks.getOrNull(idx + 1)

            if (prev != null && newStartMs <= prev.startMs) return@update entry
            if (next != null && newEndMs >= next.endMs) return@update entry
            success = true

            val manualChunks = entry.chunks.mapIndexed { i, chunk ->
                when (i) {
                    idx - 1 -> if (chunk.endMs == current.startMs || newStartMs < chunk.endMs) {
                        chunk.copy(endMs = newStartMs, origin = ChunkOrigin.MANUAL)
                    } else {
                        chunk.copy(origin = ChunkOrigin.MANUAL)
                    }

                    idx -> chunk.copy(startMs = newStartMs, endMs = newEndMs, origin = ChunkOrigin.MANUAL)

                    idx + 1 -> if (chunk.startMs == current.endMs || newEndMs > chunk.startMs) {
                        chunk.copy(startMs = newEndMs, origin = ChunkOrigin.MANUAL)
                    } else {
                        chunk.copy(origin = ChunkOrigin.MANUAL)
                    }

                    else -> chunk.copy(origin = ChunkOrigin.MANUAL)
                }
            }
            chunkSources[id] = ChunkOrigin.MANUAL
            entry.copy(
                chunks = manualChunks,
                chunksByOrigin = entry.chunksByOrigin + (ChunkOrigin.MANUAL to manualChunks),
            )
        }
        return success
    }
}
