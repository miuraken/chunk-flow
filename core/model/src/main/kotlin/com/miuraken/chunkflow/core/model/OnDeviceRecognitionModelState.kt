package com.miuraken.chunkflow.core.model

/**
 * 端末内音声認識（Android の `SpeechRecognizer`）で、ある言語の言語モデルが使えない理由（F6.2、ADR 0010）。
 *
 * 端末内音声認識は言語ごとに言語モデルのダウンロードが必要で、未ダウンロードの言語は認識できない。
 * 認識できない間も、言語の判定（Whisper Tiny）だけは行える。
 */
enum class OnDeviceRecognitionModelState {
    /** 端末は対応しているが、言語モデルがまだダウンロードされていない。 */
    NOT_INSTALLED,

    /** 言語モデルのダウンロードを OS に要求済み（ダウンロード中、または Wi-Fi 接続時などに予約済み）。 */
    DOWNLOADING,

    /** 言語モデルのダウンロードに失敗した、または要求できなかった。 */
    DOWNLOAD_FAILED,

    /** この端末の端末内音声認識は、この言語に対応していない。 */
    UNSUPPORTED,
}

/**
 * 設定画面の「文字起こし設定」タブで表示する、言語ごとの端末内音声認識モデルの管理状態（F6.2、F9.1）。
 */
enum class OnDeviceLanguageModelStatus {
    /** 状態を確認中、または未確認。 */
    CHECKING,

    /** 端末にインストール済みで利用可能。 */
    INSTALLED,

    /** 端末は対応しているが、モデルがまだダウンロードされていない。 */
    NOT_INSTALLED,

    /** OS にダウンロードを要求済み（ダウンロード中または Wi-Fi 待機中）。 */
    DOWNLOADING,

    /** ダウンロードに失敗した。 */
    DOWNLOAD_FAILED,

    /** この端末の標準音声認識（SpeechRecognizer）はこの言語に対応していない（Whisper Tiny による言語判定のみ動作）。 */
    UNSUPPORTED,
}

/**
 * 言語ごとの端末内音声認識モデルの情報（F6.2、F9.1）。
 *
 * @property language 言語タグ（`ja`、`en`、`zh` など）。
 * @property status 現在のモデルの利用可能状態。
 * @property resolvedBcp47Tag 端末で解決・選択された BCP-47 ロケールタグ（例: `cmn-Hans-CN`、`ja-JP`）。
 */
data class OnDeviceLanguageModelInfo(
    val language: LanguageTag,
    val status: OnDeviceLanguageModelStatus = OnDeviceLanguageModelStatus.CHECKING,
    val resolvedBcp47Tag: String? = null,
)
