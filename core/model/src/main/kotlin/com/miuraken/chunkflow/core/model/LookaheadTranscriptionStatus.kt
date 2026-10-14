package com.miuraken.chunkflow.core.model

/**
 * 端末上の先読み簡易文字起こし（F6.2、設計書 §7.4.5）の状態。
 *
 * @property enabled 教材で簡易文字起こしが有効になっているか。
 * @property engineAvailable 端末上で簡易文字起こしエンジンが利用可能か（スタブ時は `false`）。
 * @property inFlightChunkId 現在先読み処理中のチャンク ID。待機中は `null`。
 * @property completedCount 先読みが完了したチャンク数。
 * @property totalTargetCount 先読み対象のチャンク総数。
 */
data class LookaheadTranscriptionStatus(
    val enabled: Boolean = false,
    val engineAvailable: Boolean = false,
    val inFlightChunkId: ChunkId? = null,
    val completedCount: Int = 0,
    val totalTargetCount: Int = 0,
)
