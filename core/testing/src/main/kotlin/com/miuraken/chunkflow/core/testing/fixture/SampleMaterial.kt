package com.miuraken.chunkflow.core.testing.fixture

import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.Waveform

/**
 * プレビューとテスト用の教材 1 件分のデータ（ドメインの型だけで作る）。
 *
 * UiState は feature モジュールの型なので、ここでは作らない。各画面がこの値から自分の UiState を組み立てる。
 *
 * @property key プレビュー名やテスト名に使う短い名前（例: `"ja-ko"`）。
 * @property item 教材。
 * @property chunks チャンク（開始時刻の順）。
 * @property transcripts チャンクごとの文字起こし。文字起こしのない教材では空。
 * @property waveform 波形。まだない場合は `null`。
 * @property analysis 解析の段階ごとの状態。
 */
data class SampleMaterial(
    val key: String,
    val item: AudioItem,
    val chunks: List<Chunk>,
    val transcripts: Map<ChunkId, Transcript>,
    val waveform: Waveform?,
    val analysis: Map<AnalysisStage, StageStatus>,
) {
    override fun toString(): String = "SampleMaterial($key)"
}
