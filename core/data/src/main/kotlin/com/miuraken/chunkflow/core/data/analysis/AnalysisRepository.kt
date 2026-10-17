package com.miuraken.chunkflow.core.data.analysis

import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.Bookmark
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ChunkTranscriptionTrialResult
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo
import com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.SttEngine
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
     * 教材 [id] を閉じた後や別アプリへ移動した後も、優先度を下げてバックグラウンドで文字起こしを継続するかどうかを設定する。
     */
    suspend fun setBackgroundTranscriptionEnabled(id: MediaId, enabled: Boolean) = Unit

    /**
     * [language] の端末内音声認識の言語モデルのダウンロードを OS に要求する（F6.2）。
     *
     * 状態は [observeLookaheadStatus] の [LookaheadTranscriptionStatus.recognitionModelIssues] で知らせる。
     * 要求を送れた場合は `true`。
     */
    suspend fun requestRecognitionModelDownload(language: LanguageTag): Boolean = false

    /**
     * [language] 向けに `sherpa-onnx` ローカル音声認識モデルをダウンロード・検証して有効化する（Stage 2）。
     */
    suspend fun downloadSherpaModel(language: LanguageTag): Boolean = false

    /**
     * [language] の `sherpa-onnx` ローカル音声認識モデルを削除する（Stage 2）。
     */
    suspend fun deleteSherpaModel(language: LanguageTag): Boolean = false

    /**
     * [language] で、エンジン [engine] のモデルの取得を要求する（ADR-0011 §2.4）。
     *
     * - [SttEngine.ML_KIT]・[SttEngine.SPEECH_RECOGNIZER]: モデルは OS が管理するので、OS に取得を要求するだけ（削除はできない）。
     * - [SttEngine.SHERPA_ONNX]: アプリがダウンロードして検証する（[downloadSherpaModel] と同じ）。
     *
     * 要求を送れた（または取得できた）場合は `true`。状態は [queryLanguageModelInfos] で確かめる。
     */
    suspend fun requestSttModelDownload(language: LanguageTag, engine: SttEngine): Boolean = false

    /**
     * 端末内音声認識の言語モデルの状態を OS に問い合わせ直す（ダウンロードの完了を検出する）。
     */
    suspend fun refreshRecognitionModelIssues() = Unit

    /**
     * 指定された言語一覧 [languages] について、端末内音声認識モデルの利用可能状態と
     * 解決された BCP-47 ロケールタグ、および `sherpa-onnx` ローカルモデルの状態を問い合わせて返す（設定画面の「文字起こし設定」タブ用）。
     */
    suspend fun queryLanguageModelInfos(languages: List<LanguageTag>): List<OnDeviceLanguageModelInfo> = languages.map { OnDeviceLanguageModelInfo(language = it) }

    /**
     * プレイヤー画面で教材 [id] のウィンドウが開いているか閉じたかを通知する。
     *
     * 通常は現在開いている教材のみ文字起こし作業を行い、ウィンドウを閉じた教材の待機中キューは停止する。
     * ただし、ウィンドウを閉じた時点で既に処理中（in-flight）のチャンク文字起こしは中断せず最後まで実行して出力する。
     */
    fun setMediaTranscriptionSessionOpen(id: MediaId, open: Boolean) = Unit

    /**
     * プレイヤー画面で開かれた教材 [id] をアクセス済みとして記録し、未解析であれば他のファイルのバックグラウンド解析よりも
     * 優先して波形・チャンク解析を実行する。あわせて同じフォルダ内で解析済み・未アクセスのファイルが 2 件未満であれば補充解析を予約する。
     */
    suspend fun prioritizeOpenedMediaAnalysis(id: MediaId) = Unit

    /**
     * 現在再生中のチャンク位置 [currentChunkId] を通知し、その先 3 チャンク分の先読み簡易文字起こし（§7.4.5）を要求する。
     */
    suspend fun triggerLookaheadAt(id: MediaId, currentChunkId: ChunkId?) = Unit

    /**
     * ユーザーが拡大波形やチャンクリストで選択したチャンク [chunkId] の文字起こしを優先予約し、
     * 以降の先読み文字起こしの起点もこのチャンクへ移動する。
     * 現在実行中のチャンク文字起こしがあれば中断せず完了を待ち、その直後に [chunkId] を処理する。
     */
    suspend fun prioritizeChunkTranscription(id: MediaId, chunkId: ChunkId) {
        triggerLookaheadAt(id, chunkId)
    }

    /**
     * チャンク 1 件の文字起こしキャッシュを破棄し、最優先で再度オンデバイス文字起こしを実行する。
     */
    suspend fun retranscribeChunk(id: MediaId, chunkId: ChunkId) = Unit

    /**
     * 文字起こし処理が停止・スタックした場合などに、実行中の文字起こしジョブ・待機キュー・認識ロックをすべて強制中断・リセットする。
     */
    suspend fun resetTranscription(id: MediaId) = Unit

    /**
     * 指定した教材のすべての文字起こしとメモリ上の文字起こしキャッシュを消去し、取り込みチャンク（[ChunkOrigin.IMPORTED]）があれば削除して自動分割チャンクへ戻す。
     */
    suspend fun clearTranscriptsForMedia(id: MediaId) = Unit

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
     * 「より詳しく文字起こし」ダイアログから、エンジン・言語・音声速度倍率を指定して 1 チャンクの試験的な文字起こしを実行する。
     *
     * DB の既存の文字起こしは書き換えず、認識結果のみを返す。
     */
    suspend fun tryTranscribeChunkCustom(
        id: MediaId,
        chunkId: ChunkId,
        engine: OnDeviceTranscriptionEngine,
        languageOverride: LanguageTag? = null,
        audioSpeed: Float = 1.0f,
    ): ChunkTranscriptionTrialResult? = null

    /**
     * 「より詳しく文字起こし」ダイアログの結果テキストと言語ラベルをチャンクへ反映して保存する。
     */
    suspend fun applyChunkTranscriptionTrial(
        id: MediaId,
        chunkId: ChunkId,
        text: String,
        language: LanguageTag? = null,
    ) {
        updateChunkText(id, chunkId, text)
    }

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
     * 連続する複数のチャンク [chunkIds]（開始時刻の順）を 1 つに連結する（F5.3）。
     *
     * 文字起こしは半角スペース 1 文字を挟んで連結し、言語は先頭のチャンクに合わせる。
     * チャンクが 2 件未満、または連続していない場合は何もせず `false` を返す。
     */
    suspend fun mergeChunks(id: MediaId, chunkIds: List<ChunkId>): Boolean = false

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

    /**
     * 指定した複数のチャンク [chunkIds] のスキップ状態（[Chunk.isSkipped]）を一括で更新する。
     */
    suspend fun setChunksSkipped(
        id: MediaId,
        chunkIds: Set<ChunkId>,
        skipped: Boolean,
    ) = Unit

    /**
     * 対象言語 [sourceLanguage] からベース言語 [targetLanguage] への端末内翻訳（ML Kit）に対応しているかどうかを返す。
     */
    fun isTranslationSupported(
        sourceLanguage: LanguageTag?,
        targetLanguage: LanguageTag?,
    ): Boolean = false

    /**
     * チャンク 1 件のテキストを [sourceLanguage] から [targetLanguage] へ端末内翻訳（ML Kit）し、
     * 翻訳結果を保存して返す。失敗または未対応の場合は `null` を返す。
     */
    suspend fun translateChunk(
        id: MediaId,
        chunkId: ChunkId,
        sourceLanguage: LanguageTag,
        targetLanguage: LanguageTag,
    ): String? = null
}
