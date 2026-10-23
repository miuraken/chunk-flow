package com.miuraken.chunkflow.core.model

/**
 * アプリ内の各機能（再生制御・詳細文字起こし・先読み文字起こし・機械翻訳・チャンク編集など）が
 * どの程度利用されているかを統計的に計測するためのトラッカーインターフェース。
 *
 * セキュリティ方針（設計書 §8 / AGENTS.md §10）に従い、ファイル名・URI・フォルダ名・
 * 文字起こしや翻訳の本文など、ユーザーの教材コンテンツや個人情報は一切送信しない。
 */
interface UsageAnalyticsTracker {
    /** 画面遷移を記録する（`library`, `player`, `settings`）。 */
    fun logScreenView(screenName: String)

    /** 音声教材の取り込みを記録する（`single_file`, `directory`, `shared_intent`）。 */
    fun logMediaImport(
        sourceType: String,
        itemCount: Int,
    )

    /** 再生開始を記録する。 */
    fun logPlaybackStart(
        targetLanguage: String,
        baseLanguage: String,
    )

    /** 再生速度の変更を記録する。 */
    fun logPlaybackSpeedChange(
        speed: Float,
        isBaseLanguageSpeed: Boolean,
    )

    /** チャンクのリピート回数の変更を記録する。 */
    fun logRepeatCountChange(repeatCount: Int)

    /** A-B リピート区間の設定を記録する。 */
    fun logAbRepeatSet(chunkSpan: Int)

    /** 自動ポーズモードの変更を記録する（`off`, `fixed`, `proportional`, `original_silence`）。 */
    fun logAutoPauseChange(
        mode: String,
        scope: String,
    )

    /** 再生言語フィルタの変更を記録する（`all`, `target_only`, `base_only`）。 */
    fun logLanguageFilterChange(filter: String)

    /** ファイル終了時の動作変更を記録する（`stop`, `loop`, `next_file`, `shuffle`）。 */
    fun logFileEndActionChange(action: String)

    /** ブラインドモード（テキスト非表示）の切り替えを記録する。 */
    fun logBlindModeToggle(enabled: Boolean)

    /** ファイル全体の一括文字起こし開始を記録する。 */
    fun logFullTranscriptionStart(
        targetLanguage: String,
        baseLanguage: String,
        targetEngine: String,
        baseEngine: String,
    )

    /** 個別チャンクの詳細書き起こし（再文字起こし）実行を記録する。 */
    fun logChunkDetailTranscription(
        language: String,
        engine: String,
    )

    /** 先読み簡易文字起こしの実行を記録する。 */
    fun logLookaheadTranscription(
        language: String,
        engine: String,
    )

    /** 機械翻訳表示（ON/OFF）の切り替えを記録する。 */
    fun logTranslationToggle(
        enabled: Boolean,
        sourceLanguage: String,
        targetLanguage: String,
    )

    /** チャンクの機械翻訳の実行を記録する。 */
    fun logTranslationRun(
        sourceLanguage: String,
        targetLanguage: String,
        chunkCount: Int,
    )

    /** 端末内音声認識モデルのダウンロード開始を記録する。 */
    fun logSttModelDownload(
        modelId: String,
        language: String,
    )

    /** チャンク境界の編集（`split`, `merge`, `adjust`, `resegment`）を記録する。 */
    fun logChunkBoundaryEdit(editType: String)

    /** 文字起こしのエクスポート（`chunkflow_json`, `srt`, `vtt`, `lrc`, `tsv`）を記録する。 */
    fun logTranscriptExport(format: String)
}

/**
 * テストおよびプレビュー用の何もしない [UsageAnalyticsTracker] 実装。
 */
object NoOpUsageAnalyticsTracker : UsageAnalyticsTracker {
    override fun logScreenView(screenName: String) = Unit
    override fun logMediaImport(sourceType: String, itemCount: Int) = Unit
    override fun logPlaybackStart(targetLanguage: String, baseLanguage: String) = Unit
    override fun logPlaybackSpeedChange(speed: Float, isBaseLanguageSpeed: Boolean) = Unit
    override fun logRepeatCountChange(repeatCount: Int) = Unit
    override fun logAbRepeatSet(chunkSpan: Int) = Unit
    override fun logAutoPauseChange(mode: String, scope: String) = Unit
    override fun logLanguageFilterChange(filter: String) = Unit
    override fun logFileEndActionChange(action: String) = Unit
    override fun logBlindModeToggle(enabled: Boolean) = Unit
    override fun logFullTranscriptionStart(
        targetLanguage: String,
        baseLanguage: String,
        targetEngine: String,
        baseEngine: String,
    ) = Unit
    override fun logChunkDetailTranscription(language: String, engine: String) = Unit
    override fun logLookaheadTranscription(language: String, engine: String) = Unit
    override fun logTranslationToggle(
        enabled: Boolean,
        sourceLanguage: String,
        targetLanguage: String,
    ) = Unit
    override fun logTranslationRun(
        sourceLanguage: String,
        targetLanguage: String,
        chunkCount: Int,
    ) = Unit
    override fun logSttModelDownload(modelId: String, language: String) = Unit
    override fun logChunkBoundaryEdit(editType: String) = Unit
    override fun logTranscriptExport(format: String) = Unit
}
