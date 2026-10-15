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
