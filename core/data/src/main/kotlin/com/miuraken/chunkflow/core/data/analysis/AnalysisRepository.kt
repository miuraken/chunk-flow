package com.miuraken.chunkflow.core.data.analysis

import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.Bookmark
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
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

    /**
     * チャンクの言語ラベルを手動で更新する（F7.3、設計書 §7.4.6）。
     *
     * 更新した文字起こしは `user_edited = true`、`language_confidence = 1.0f` として保存される。
     */
    suspend fun updateChunkLanguage(id: MediaId, chunkId: ChunkId, language: LanguageTag?) = Unit

    /**
     * 指定した教材で言語ラベルが未設定または `und` の文字起こしを一括で [language] に設定する（F7.3）。
     */
    suspend fun bulkSetUnknownTranscriptLanguage(id: MediaId, language: LanguageTag) = Unit

    /**
     * 特徴量の有声開始と取り込みチャンクの開始時刻の差の中央値から文字起こしオフセット（ミリ秒）を自動計算して保存し、
     * 計算された補正値を返す（F7.6、設計書 §7.4.6）。対応する特徴量やチャンクがない場合は `null` を返す。
     */
    suspend fun autoAlignTranscriptOffset(id: MediaId): Int? = null

    /** 端末上の先読み簡易文字起こし（F6.2、設計書 §7.4.5）の状態を購読する。 */
    fun observeLookaheadStatus(id: MediaId): Flow<LookaheadTranscriptionStatus> = flowOf(LookaheadTranscriptionStatus())

    /** 端末上の先読み簡易文字起こし（F6.2）の有効・無効を切り替える。 */
    suspend fun setLookaheadEnabled(id: MediaId, enabled: Boolean) = Unit

    /**
     * 現在再生中のチャンク位置 [currentChunkId] を通知し、その先 3 チャンク分の先読み簡易文字起こし（§7.4.5）を要求する。
     */
    suspend fun triggerLookaheadAt(id: MediaId, currentChunkId: ChunkId?) = Unit

    /**
     * チャンク 1 件の文字起こしキャッシュを破棄し、最優先で再度オンデバイス文字起こしを実行する。
     */
    suspend fun retranscribeChunk(id: MediaId, chunkId: ChunkId) = Unit

    /** 指定した教材のブックマーク一覧を時刻の昇順で購読する（F8.1）。 */
    fun observeBookmarks(id: MediaId): Flow<List<Bookmark>> = flowOf(emptyList())

    /**
     * 指定したチャンク `[startMs, endMs)` 内のブックマークをトグルする（F8.1）。
     *
     * @return ブックマークが追加された場合は `true`、既存のブックマークが削除された場合は `false`。
     */
    suspend fun toggleChunkBookmark(id: MediaId, chunkId: ChunkId, note: String = ""): Boolean = false

    /**
     * チャンクの文字起こしテキストを手動で修正し、`user_edited = true` として保存する（F7.4）。
     */
    suspend fun updateChunkText(id: MediaId, chunkId: ChunkId, text: String) = Unit

    /**
     * 指定した時刻 [splitPositionMs] でチャンクを 2 つに分割し、[ChunkOrigin.MANUAL] として 1 トランザクションで保存する（F5.3）。
     *
     * @return 分割が成立した場合は `true`、範囲外や最小チャンク長未満などで分割できない場合は `false`。
     */
    suspend fun splitChunk(id: MediaId, chunkId: ChunkId, splitPositionMs: Long): Boolean = false

    /**
     * 指定したチャンクと直後のチャンクを結合し、[ChunkOrigin.MANUAL] として 1 トランザクションで保存する（F5.3）。
     *
     * @return 結合が成立した場合は `true`、直後のチャンクが存在しない等で結合できない場合は `false`。
     */
    suspend fun mergeWithNextChunk(id: MediaId, chunkId: ChunkId): Boolean = false

    /**
     * チャンクの開始・終了境界を [newStartMs] / [newEndMs] に移動し、[ChunkOrigin.MANUAL] として 1 トランザクションで保存する（F5.3）。
     *
     * @return 境界移動が成立した場合は `true`、隣接チャンクとの整合性や最小チャンク長を満たさない場合は `false`。
     */
    suspend fun moveChunkBoundary(
        id: MediaId,
        chunkId: ChunkId,
        newStartMs: Long,
        newEndMs: Long,
    ): Boolean = false
}
