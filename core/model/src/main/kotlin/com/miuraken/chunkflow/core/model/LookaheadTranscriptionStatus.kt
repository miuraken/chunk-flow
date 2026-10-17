package com.miuraken.chunkflow.core.model

/**
 * 1 チャンクの文字起こし処理における内部ステップと目安進捗率（0.0..1.0）。
 *
 * @property progressFraction チャンク単体の処理における目安の進捗率。
 */
enum class TranscriptionStep(val progressFraction: Float) {
    /** 認識スロットまたは排他ロックの解放待ち。 */
    WAITING_LOCK(0.08f),

    /** 音声ファイルからチャンク区間の 16kHz モノラル PCM を切り出し中（MediaCodec）。 */
    EXTRACTING_PCM(0.22f),

    /** Whisper Tiny による言語判定（学習言語 / 母語）を実行中。 */
    DETECTING_LANGUAGE(0.45f),

    /** 第 1 候補の言語で音声認識エンジンを実行中。 */
    RECOGNIZING_PRIMARY(0.75f),

    /** フォールバック言語または別ロケールタグで音声認識を再試行中。 */
    RECOGNIZING_FALLBACK(0.90f),

    /** 認識結果をデータベースおよびローカル JSON に保存中。 */
    SAVING(0.98f),
}

/**
 * 文字起こし処理の診断・デバッグ用イベントログの 1 エントリ（F6.2）。
 *
 * @property timestampEpochMs イベント発生時刻（エポックミリ秒）。
 * @property stage 処理段階の短い分類ラベル（`PCM` / `LID` / `STT` / `CONTROL` / `ERROR` など）。
 * @property message 診断メッセージ（所要時間・使用エンジン・言語・エラーコード等を含む）。
 * @property isWarningOrError タイムアウト・エラー・中断など注意が必要なイベントの場合 `true`。
 */
data class TranscriptionDiagnosticEntry(
    val timestampEpochMs: Long,
    val stage: String,
    val message: String,
    val isWarningOrError: Boolean = false,
)

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
 * @property inFlightStep 現在実行中のチャンクの処理ステップ（待機中は `null`）。
 * @property inFlightDetail 現在実行中のエンジンや言語タグの補足情報（例: `"SpeechRecognizer (ko-KR)"`）。
 * @property inFlightStartedAtEpochMs 現在のチャンクの文字起こしを開始した時刻（エポックミリ秒）。
 * @property isForcedChunkInFlight 現在の処理が手動の「強制文字起こし」等による優先実行かどうか。
 * @property skippedEmptyChunkIds 今セッションで一度認識を試みたものの無音・認識不能等で本文が得られなかったチャンク ID の集合。
 * @property diagnostics 直近の文字起こし診断・デバッグログ一覧（新しい順または時系列順）。
 */
data class LookaheadTranscriptionStatus(
    val enabled: Boolean = false,
    val engineAvailable: Boolean = false,
    val inFlightChunkId: ChunkId? = null,
    val queuedChunkIds: Set<ChunkId> = emptySet(),
    val completedCount: Int = 0,
    val totalTargetCount: Int = 0,
    val recognitionModelIssues: Map<LanguageTag, OnDeviceRecognitionModelState> = emptyMap(),
    val inFlightStep: TranscriptionStep? = null,
    val inFlightDetail: String? = null,
    val inFlightStartedAtEpochMs: Long? = null,
    val isForcedChunkInFlight: Boolean = false,
    val skippedEmptyChunkIds: Set<ChunkId> = emptySet(),
    val diagnostics: List<TranscriptionDiagnosticEntry> = emptyList(),
)
