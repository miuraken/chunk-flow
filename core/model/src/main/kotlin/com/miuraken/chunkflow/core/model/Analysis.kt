package com.miuraken.chunkflow.core.model

/** 端末内の解析の段階。文字起こしは PC で行うため含まない。 */
enum class AnalysisStage {
    /** 波形とフレーム特徴量の計算。 */
    FEATURES,

    /** 端末内の無音検出によるチャンク分割。 */
    SEGMENTATION,
}

/** 解析の段階ごとの状態。 */
sealed interface StageStatus {
    /** まだ始まっていない。 */
    data object NotStarted : StageStatus

    /**
     * 実行中。
     *
     * @property progress 進捗（0..1）。`null` は進捗が不明であることを表す。
     */
    data class Running(val progress: Float?) : StageStatus

    /** 完了した。 */
    data object Done : StageStatus

    /**
     * 失敗した。
     *
     * @property error 失敗の種別。表示する文言は UI 側の文字列リソースで決める。
     */
    data class Failed(val error: AnalysisError) : StageStatus
}

/** 解析の失敗の種別。 */
enum class AnalysisError {
    /** 対応していない形式。 */
    UNSUPPORTED_FORMAT,

    /** デコードに失敗した（破損したファイルなど）。 */
    DECODE_FAILED,

    /** ファイルの読み書きに失敗した。 */
    IO,
}
