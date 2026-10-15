package com.miuraken.chunkflow.core.model

/**
 * 端末上の先読み簡易文字起こし（F6.2、設計書 §7.4.5）の状態。
 *
 * @property enabled 教材で簡易文字起こしが有効になっているか。
 * @property engineAvailable 端末上で簡易文字起こしエンジンが利用可能か（スタブ時は `false`）。
 * @property inFlightChunkId 現在先読みまたは優先文字起こし処理中のチャンク ID。待機中は `null`。
 * @property queuedChunkIds 文字起こしの順番待ち（待機中）になっているチャンク ID の集合。
 * @property completedCount 先読みが完了したチャンク数。
 * @property totalTargetCount 先読み対象のチャンク総数。
 * @property recognitionModelIssues 端末内音声認識の言語モデルが使えない言語と、その理由。
 *   問題のない言語は含まない。ここに含まれる言語のチャンクは、言語の判定だけ行い本文は空になる。
 */
data class LookaheadTranscriptionStatus(
    val enabled: Boolean = false,
    val engineAvailable: Boolean = false,
    val inFlightChunkId: ChunkId? = null,
    val queuedChunkIds: Set<ChunkId> = emptySet(),
    val completedCount: Int = 0,
    val totalTargetCount: Int = 0,
    val recognitionModelIssues: Map<LanguageTag, OnDeviceRecognitionModelState> = emptyMap(),
)
