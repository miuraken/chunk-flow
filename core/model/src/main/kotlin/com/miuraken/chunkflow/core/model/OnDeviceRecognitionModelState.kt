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
 * 端末内の簡易文字起こし（チャンク単位の音声認識）で使用する音声認識ライブラリ（エンジン）の選択（F6.2、F9.1）。
 */
enum class OnDeviceTranscriptionEngine {
    /**
     * 自動（推奨）: Android 標準の `SpeechRecognizer` を優先し、端末が非対応の言語は
     * チェックアウト済みのローカル `sherpa-onnx` モデルへ自動フォールバックする。
     */
    AUTO,

    /** Android 標準の `SpeechRecognizer`（オンデバイス音声認識 SODA）のみを使用する。 */
    ANDROID_SPEECH_RECOGNIZER,

    /** ローカルの `sherpa-onnx`（ONNX Runtime 音声認識モデル）を優先して使用する。 */
    SHERPA_ONNX,

    /**
     * ML Kit GenAI Speech Recognition（AICore 対応機種向け）。
     *
     * 端末・言語が ML Kit GenAI Speech Recognition に対応している場合は優先使用し、
     * 非対応の場合は [AUTO] と同様に OS 標準または `sherpa-onnx` へフォールバックする。
     */
    ML_KIT,

    /** `sherpa-onnx` SenseVoice Small（日・英・中・広東・韓の 5 言語対応）。 */
    SHERPA_ONNX_SENSE_VOICE,

    /** `sherpa-onnx` Whisper Base（99 言語対応）。 */
    SHERPA_ONNX_WHISPER_BASE,

    /** `sherpa-onnx` Whisper Small（99 言語対応）。 */
    SHERPA_ONNX_WHISPER_SMALL,

    /** Moonshine ONNX Tiny（英語 `en` 専用）。 */
    MOONSHINE_TINY,

    /** Moonshine ONNX Base（英語 `en` 専用）。 */
    MOONSHINE_BASE,

    /** OpenAI Audio Transcriptions API（要 API キー設定）。 */
    OPENAI_API,

    /** Groq Audio Transcriptions API（OpenAI 互換・要 API キー設定）。 */
    GROQ_API,

    /** Google Gemini API 音声文字起こし（要 API キー設定）。 */
    GEMINI_API,
}

/**
 * ML Kit GenAI Speech Recognition の認識モード（ADR-0011 §2.2）。
 *
 * 端末と言語に合わせて自動で選ぶ（高度 → 基本の順に試す）。
 */
enum class MlKitRecognitionMode {
    /** 基本モード（API 31 以上の多くの端末。対応言語は少なめ）。 */
    BASIC,

    /** 高度モード（Gemini Nano を使う。対応端末は Pixel 10・11 など。対応言語が多い）。 */
    ADVANCED,
}

/**
 * 言語ごとの端末内音声認識モデルの情報（F6.2、F9.1）。
 *
 * @property language 言語タグ（`ja`、`en`、`zh` など）。
 * @property status Android 標準音声認識（`SpeechRecognizer`）モデルの利用可能状態。
 * @property resolvedBcp47Tag 端末で解決・選択された BCP-47 ロケールタグ（例: `cmn-Hans-CN`、`ja-JP`）。
 * @property sherpaModelStatus ローカル `sherpa-onnx`（Whisper Tiny）モデルの利用可能状態。
 * @property sherpaModelId この言語で使用する `sherpa-onnx` のモデル識別子（例: `sherpa-onnx-whisper-tiny`）。
 * @property sherpaModelSizeBytes `sherpa-onnx` モデルの合計バイトサイズ（目安）。
 * @property canDeleteSherpaModel ローカルにダウンロード済みの `sherpa-onnx` モデルを削除可能かどうか。
 * @property mlKitStatus ML Kit GenAI Speech Recognition の利用可能状態（ADR-0011）。モデルは OS が管理するので、
 *   アプリからは取得の要求だけができる（削除はできない）。
 * @property mlKitMode ML Kit で使う認識モード。使えないとき・未確認のときは `null`。
 * @property extraEngineStatuses 追加エンジン（SenseVoice / Whisper Base / Whisper Small / Moonshine / OpenAI / Groq / Gemini）の
 *   利用可能状態マップ。
 */
data class OnDeviceLanguageModelInfo(
    val language: LanguageTag,
    val status: OnDeviceLanguageModelStatus = OnDeviceLanguageModelStatus.CHECKING,
    val resolvedBcp47Tag: String? = null,
    val sherpaModelStatus: OnDeviceLanguageModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
    val sherpaModelId: String? = null,
    val sherpaModelSizeBytes: Long = 0L,
    val canDeleteSherpaModel: Boolean = false,
    val mlKitStatus: OnDeviceLanguageModelStatus = OnDeviceLanguageModelStatus.CHECKING,
    val mlKitMode: MlKitRecognitionMode? = null,
    val extraEngineStatuses: Map<SttEngine, OnDeviceLanguageModelStatus> = emptyMap(),
) {
    /** エンジン [engine] のモデルの利用可能状態を返す。 */
    fun statusOf(engine: SttEngine): OnDeviceLanguageModelStatus = when (engine) {
        SttEngine.ML_KIT -> mlKitStatus

        SttEngine.SPEECH_RECOGNIZER -> status

        SttEngine.SHERPA_ONNX -> sherpaModelStatus

        SttEngine.SHERPA_ONNX_SENSE_VOICE,
        SttEngine.SHERPA_ONNX_WHISPER_BASE,
        SttEngine.SHERPA_ONNX_WHISPER_SMALL,
        SttEngine.MOONSHINE_TINY,
        SttEngine.MOONSHINE_BASE,
        SttEngine.OPENAI_API,
        SttEngine.GROQ_API,
        SttEngine.GEMINI_API,
        -> extraEngineStatuses[engine] ?: defaultExtraEngineStatus(engine, language)
    }

    /**
     * [SupportedLanguageCatalog] のエンジン対応情報（`mlKitSupported`・`speechRecognizerSupported`・`whisperCode`）を反映した
     * [OnDeviceLanguageModelInfo] を返す。カタログ上非対応のエンジンは [OnDeviceLanguageModelStatus.UNSUPPORTED] になる。
     */
    fun enrichedWithCatalog(languageOverride: LanguageTag = language): OnDeviceLanguageModelInfo {
        val entry = SupportedLanguageCatalog.findByCode(languageOverride.value) ?: return this
        val updatedExtras = extraEngineStatuses.toMutableMap()
        val extraEngines = listOf(
            SttEngine.SHERPA_ONNX_SENSE_VOICE,
            SttEngine.SHERPA_ONNX_WHISPER_BASE,
            SttEngine.SHERPA_ONNX_WHISPER_SMALL,
            SttEngine.MOONSHINE_TINY,
            SttEngine.MOONSHINE_BASE,
            SttEngine.OPENAI_API,
            SttEngine.GROQ_API,
            SttEngine.GEMINI_API,
        )
        for (engine in extraEngines) {
            val defaultStatus = defaultExtraEngineStatus(engine, languageOverride)
            if (defaultStatus == OnDeviceLanguageModelStatus.UNSUPPORTED) {
                updatedExtras[engine] = OnDeviceLanguageModelStatus.UNSUPPORTED
            } else if (!updatedExtras.containsKey(engine)) {
                updatedExtras[engine] = defaultStatus
            }
        }
        return copy(
            mlKitStatus = if (!entry.mlKitSupported) {
                OnDeviceLanguageModelStatus.UNSUPPORTED
            } else {
                mlKitStatus
            },
            status = if (!entry.speechRecognizerSupported) {
                OnDeviceLanguageModelStatus.UNSUPPORTED
            } else {
                status
            },
            sherpaModelStatus = if (entry.whisperCode == null) {
                OnDeviceLanguageModelStatus.UNSUPPORTED
            } else {
                sherpaModelStatus
            },
            extraEngineStatuses = updatedExtras,
        )
    }

    companion object {
        /** 追加エンジン [engine] の言語 [language] におけるカタログ対応・既定状態を返す。 */
        fun defaultExtraEngineStatus(
            engine: SttEngine,
            language: LanguageTag,
        ): OnDeviceLanguageModelStatus {
            val downloadable = DownloadableSttModel.fromEngineOrNull(engine)
            if (downloadable != null) {
                return if (downloadable.isLanguageSupported(language)) {
                    OnDeviceLanguageModelStatus.NOT_INSTALLED
                } else {
                    OnDeviceLanguageModelStatus.UNSUPPORTED
                }
            }
            val entry = SupportedLanguageCatalog.findByCode(language.value)
            return when (engine) {
                SttEngine.OPENAI_API,
                SttEngine.GROQ_API,
                -> if (entry == null || entry.whisperCode != null) {
                    OnDeviceLanguageModelStatus.NOT_INSTALLED
                } else {
                    OnDeviceLanguageModelStatus.UNSUPPORTED
                }

                SttEngine.GEMINI_API -> OnDeviceLanguageModelStatus.NOT_INSTALLED

                else -> OnDeviceLanguageModelStatus.NOT_INSTALLED
            }
        }
    }
}

/**
 * 「より詳しく文字起こし」ダイアログでの試行結果（F6.2）。
 *
 * @property text 認識されたテキスト（認識できなかった場合は空文字）。
 * @property language 判定または使用された言語タグ（不明時は `null`）。
 * @property confidence 認識信頼度（0.0..1.0、不明時は `null`）。
 * @property engineId 実際に認識を行ったエンジン識別子。
 */
data class ChunkTranscriptionTrialResult(
    val text: String,
    val language: LanguageTag? = null,
    val confidence: Float? = null,
    val engineId: EngineId = EngineId("unknown"),
)
