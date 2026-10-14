package com.miuraken.chunkflow.core.testing.fake

import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
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
     */
    data class Entry(
        val status: Map<AnalysisStage, StageStatus> = emptyMap(),
        val waveform: Waveform? = null,
        val chunks: List<Chunk> = emptyList(),
        val transcripts: Map<ChunkId, Transcript> = emptyMap(),
    )

    private val entries = MutableStateFlow<Map<MediaId, Entry>>(emptyMap())

    /** [requestAnalysis] の呼び出し（呼ばれた順）。 */
    val requestedAnalyses: MutableList<Pair<MediaId, Set<AnalysisStage>>> = mutableListOf()

    /** [resegment] の呼び出し（呼ばれた順）。 */
    val resegmentRequests: MutableList<Pair<MediaId, SegmentationParams>> = mutableListOf()

    /** [setChunkSource] で設定された出どころ。 */
    val chunkSources: MutableMap<MediaId, ChunkOrigin> = mutableMapOf()

    /** [setTranscriptOffset] で設定された補正値。 */
    val transcriptOffsets: MutableMap<MediaId, Int> = mutableMapOf()

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

    override suspend fun requestAnalysis(id: MediaId, stages: Set<AnalysisStage>) {
        requestedAnalyses += id to stages
    }

    override suspend fun resegment(id: MediaId, params: SegmentationParams) {
        resegmentRequests += id to params
    }

    override suspend fun setChunkSource(id: MediaId, source: ChunkOrigin) {
        chunkSources[id] = source
    }

    override suspend fun setTranscriptOffset(id: MediaId, offsetMs: Int) {
        transcriptOffsets[id] = offsetMs
    }
}
