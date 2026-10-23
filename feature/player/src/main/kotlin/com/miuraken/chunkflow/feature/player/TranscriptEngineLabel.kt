package com.miuraken.chunkflow.feature.player

import androidx.annotation.StringRes

/** クラウドの OpenAI 互換の音声認識エンジンの ID の前半（例: `cloud-openai-gpt-4o-mini-transcribe`）。 */
private const val CLOUD_OPENAI_ENGINE_ID_PREFIX = "cloud-openai-"

/** クラウドの Groq の音声認識エンジンの ID の前半（例: `cloud-groq-whisper-large-v3-turbo`）。 */
private const val CLOUD_GROQ_ENGINE_ID_PREFIX = "cloud-groq-"

/** クラウドの Gemini の音声認識エンジンの ID の前半（例: `cloud-gemini-2.5-flash`、`cloud-gemini-flash-latest`）。 */
private const val CLOUD_GEMINI_ENGINE_ID_PREFIX = "cloud-gemini-"

/**
 * 文字起こしのエンジンの ID（[engineId]）から、画面に出すエンジンの名前の文字列リソースを決める。
 * 知らない ID なら `null`（呼び出し側で ID をそのまま出す）。
 *
 * クラウドのエンジンは ID にモデル名が入り、モデルを変えると ID も変わる（Gemini は `cloud-gemini-2.5-flash` →
 * `cloud-gemini-3.8-flash` / `cloud-gemini-flash-latest` など）ので、前方一致で「OpenAI API」「Groq API」
 * 「Gemini API」にまとめる。
 */
@StringRes
internal fun transcriptEngineLabelRes(engineId: String): Int? = when {
    engineId == "mlkit-genai-speech" -> R.string.player_detailed_transcribe_engine_mlkit

    engineId == "android-on-device-speech" || engineId == "android-speech-recognizer" ->
        R.string.player_detailed_transcribe_engine_os

    engineId == "sherpa-onnx-whisper-tiny" -> R.string.player_detailed_transcribe_engine_sherpa

    engineId == "sherpa-onnx-sense-voice-small" -> R.string.player_detailed_transcribe_engine_sherpa_sense_voice

    engineId == "sherpa-onnx-whisper-base" -> R.string.player_detailed_transcribe_engine_sherpa_whisper_base

    engineId == "sherpa-onnx-whisper-small" -> R.string.player_detailed_transcribe_engine_sherpa_whisper_small

    engineId == "moonshine-onnx-tiny" -> R.string.player_detailed_transcribe_engine_moonshine_tiny

    engineId == "moonshine-onnx-base" -> R.string.player_detailed_transcribe_engine_moonshine_base

    engineId.startsWith(CLOUD_OPENAI_ENGINE_ID_PREFIX) -> R.string.player_detailed_transcribe_engine_openai_api

    engineId.startsWith(CLOUD_GROQ_ENGINE_ID_PREFIX) -> R.string.player_detailed_transcribe_engine_groq_api

    engineId.startsWith(CLOUD_GEMINI_ENGINE_ID_PREFIX) -> R.string.player_detailed_transcribe_engine_gemini_api

    else -> null
}
