package com.miuraken.chunkflow.core.data.analysis

import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.Waveform
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * 解析結果（波形・チャンク・文字起こし）と解析の状態の単一の情報源（§6.3、§7.3）。
 *
 * 実装は audio-analysis が書く（`core/data` の `analysis` パッケージ）。宣言は Phase 0 で lead が置いた契約（ADR 0003）。
 */
interface AnalysisRepository {
    /** 解析の段階ごとの状態を購読する。 */
    fun observeStatus(id: MediaId): Flow<Map<AnalysisStage, StageStatus>>

    /** 波形を購読する。まだなければ `null`（途中保存のたびに更新される）。 */
    fun observeWaveform(id: MediaId): Flow<Waveform?>

    /** 使うチャンク（`item_state.chunk_source` で選んだ出どころ）を開始時刻の順で購読する。 */
    fun observeChunks(id: MediaId): Flow<List<Chunk>>

    /** チャンクごとの文字起こしを購読する。 */
    fun observeTranscripts(id: MediaId): Flow<Map<ChunkId, Transcript>>

    /**
     * 指定した教材で利用可能なチャンクの出どころ（[ChunkOrigin.IMPORTED] / [ChunkOrigin.AUTO] など）の集合を購読する（F5.4）。
     */
    fun observeAvailableChunkOrigins(id: MediaId): Flow<Set<ChunkOrigin>> = flowOf(emptySet())

    /** 解析ジョブを投入する（WorkManager のユニークワーク）。 */
    suspend fun requestAnalysis(id: MediaId, stages: Set<AnalysisStage>)

    /** 保存済みの特徴量からチャンクを再計算する（再デコード不要）。 */
    suspend fun resegment(id: MediaId, params: SegmentationParams)

    /** チャンクの出どころ（[ChunkOrigin.IMPORTED] / [ChunkOrigin.AUTO]）を切り替える（F5.4）。 */
    suspend fun setChunkSource(id: MediaId, source: ChunkOrigin)

    /** 文字起こしの時刻の補正値を設定する（F7.6）。 */
    suspend fun setTranscriptOffset(id: MediaId, offsetMs: Int)
}
