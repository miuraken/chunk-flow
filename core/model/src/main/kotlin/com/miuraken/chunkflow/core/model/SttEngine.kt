package com.miuraken.chunkflow.core.model

/**
 * 端末内の文字起こし（チャンク単位の音声認識）に使うエンジン（ADR-0011）。
 */
enum class SttEngine {
    /** ML Kit GenAI Speech Recognition。基本モードと高度モードは、端末と言語に合わせて自動で選ぶ。 */
    ML_KIT,

    /** Android 標準の `SpeechRecognizer`（端末内の音声認識）。 */
    SPEECH_RECOGNIZER,

    /** `sherpa-onnx` の Whisper Tiny モデル（ONNX Runtime で動かす。99 言語対応、約 99 MB）。 */
    SHERPA_ONNX,

    /** `sherpa-onnx` の SenseVoice Small モデル（ONNX Runtime で動かす。日・英・中・広東・韓の 5 言語対応、約 228 MB）。 */
    SHERPA_ONNX_SENSE_VOICE,

    /** `sherpa-onnx` の Whisper Base モデル（ONNX Runtime で動かす。99 言語対応、約 153 MB）。 */
    SHERPA_ONNX_WHISPER_BASE,

    /** `sherpa-onnx` の Whisper Small モデル（ONNX Runtime で動かす。99 言語対応、約 358 MB）。 */
    SHERPA_ONNX_WHISPER_SMALL,

    /** Moonshine ONNX Tiny モデル（ONNX Runtime で動かす。英語 `en` 専用、約 118 MB）。 */
    MOONSHINE_TINY,

    /** Moonshine ONNX Base モデル（ONNX Runtime で動かす。英語 `en` 専用、約 274 MB）。 */
    MOONSHINE_BASE,

    /** OpenAI Audio Transcriptions API（`gpt-4o-mini-transcribe` / `whisper-1`、要 API キー設定）。 */
    OPENAI_API,

    /** Groq Audio Transcriptions API（OpenAI 互換・`whisper-large-v3-turbo`、要 API キー設定）。 */
    GROQ_API,

    /** Google Gemini API 音声文字起こし（`gemini-2.5-flash`、要 API キー設定）。 */
    GEMINI_API,
    ;

    /** クラウド API キーを要するエンジンかどうか。 */
    val isCloudApi: Boolean
        get() = this == OPENAI_API || this == GROQ_API || this == GEMINI_API

    /** 対応するダウンロード可能なローカル ONNX モデル（ない場合は `null`）。 */
    val downloadableModel: DownloadableSttModel?
        get() = DownloadableSttModel.fromEngineOrNull(this)

    companion object {
        /** 自動のときに試す順番（ADR-0011 §2.2）。sherpa-onnx はモデルがあるときだけ使う。 */
        val AUTO_ORDER: List<SttEngine> = listOf(ML_KIT, SPEECH_RECOGNIZER, SHERPA_ONNX)

        /**
         * 優先するエンジン [preferred]（`null` は自動）から、試す順番を返す。
         *
         * 指定したエンジンが使えないときに止めないよう、指定したエンジンのあとに自動の順番の残りを続ける。
         */
        fun tryOrder(preferred: SttEngine?): List<SttEngine> = if (preferred == null) {
            AUTO_ORDER
        } else {
            listOf(preferred) + AUTO_ORDER.filter { it != preferred }
        }

        /** 保存用の名前（[name]）から [SttEngine] を返す。知らない名前は `null`。 */
        fun fromNameOrNull(name: String?): SttEngine? = name?.let { raw -> entries.firstOrNull { it.name == raw } }
    }
}

/**
 * 全体設定の「文字起こし」タブでダウンロード・削除（パージ）を管理するオンデバイス ONNX 音声認識モデル。
 *
 * @property sttEngine 対応する [SttEngine]。
 * @property modelId ディレクトリ名および [EngineId] に使うモデル識別子。
 * @property displayName UI 表示名。
 * @property approxSizeMb 目安サイズ（MB）。
 */
enum class DownloadableSttModel(
    val sttEngine: SttEngine,
    val modelId: String,
    val displayName: String,
    val approxSizeMb: Int,
) {
    /** `sherpa-onnx` Whisper Tiny int8（99 言語対応・約 99 MB）。 */
    SHERPA_WHISPER_TINY(
        sttEngine = SttEngine.SHERPA_ONNX,
        modelId = "sherpa-onnx-whisper-tiny",
        displayName = "sherpa-onnx Whisper Tiny",
        approxSizeMb = 99,
    ),

    /** `sherpa-onnx` SenseVoice Small int8（日・英・中・広東・韓の 5 言語対応・約 228 MB）。 */
    SHERPA_SENSE_VOICE_SMALL(
        sttEngine = SttEngine.SHERPA_ONNX_SENSE_VOICE,
        modelId = "sherpa-onnx-sense-voice-small",
        displayName = "sherpa-onnx SenseVoice Small",
        approxSizeMb = 228,
    ),

    /** `sherpa-onnx` Whisper Base int8（99 言語対応・約 153 MB）。 */
    SHERPA_WHISPER_BASE(
        sttEngine = SttEngine.SHERPA_ONNX_WHISPER_BASE,
        modelId = "sherpa-onnx-whisper-base",
        displayName = "sherpa-onnx Whisper Base",
        approxSizeMb = 153,
    ),

    /** `sherpa-onnx` Whisper Small int8（99 言語対応・約 358 MB）。 */
    SHERPA_WHISPER_SMALL(
        sttEngine = SttEngine.SHERPA_ONNX_WHISPER_SMALL,
        modelId = "sherpa-onnx-whisper-small",
        displayName = "sherpa-onnx Whisper Small",
        approxSizeMb = 358,
    ),

    /** Moonshine ONNX Tiny int8（英語 `en` 専用・約 118 MB）。 */
    MOONSHINE_TINY(
        sttEngine = SttEngine.MOONSHINE_TINY,
        modelId = "sherpa-onnx-moonshine-tiny-en-int8",
        displayName = "Moonshine ONNX Tiny",
        approxSizeMb = 118,
    ),

    /** Moonshine ONNX Base int8（英語 `en` 専用・約 274 MB）。 */
    MOONSHINE_BASE(
        sttEngine = SttEngine.MOONSHINE_BASE,
        modelId = "sherpa-onnx-moonshine-base-en-int8",
        displayName = "Moonshine ONNX Base",
        approxSizeMb = 274,
    ),
    ;

    /** 目安サイズ（MB）。 */
    val estimatedSizeMb: Int
        get() = approxSizeMb

    /** 目安サイズ（バイト）。 */
    val estimatedSizeBytes: Long
        get() = approxSizeMb * 1_024L * 1_024L

    /** 指定した言語 [language] にこのモデルが対応しているかどうか。 */
    fun isLanguageSupported(language: LanguageTag): Boolean {
        val entry = SupportedLanguageCatalog.findByCode(language.value)
        val whisperCode = entry?.whisperCode ?: language.value.trim().lowercase().substringBefore('-')
        return when (this) {
            SHERPA_WHISPER_TINY,
            SHERPA_WHISPER_BASE,
            SHERPA_WHISPER_SMALL,
            -> entry?.whisperCode != null

            SHERPA_SENSE_VOICE_SMALL -> whisperCode in SENSE_VOICE_SUPPORTED_CODES

            MOONSHINE_TINY,
            MOONSHINE_BASE,
            -> whisperCode == "en"
        }
    }

    /** 指定した言語 [language] にこのモデルが対応しているかどうか。 */
    fun supportsLanguage(language: LanguageTag): Boolean = isLanguageSupported(language)

    /** 指定した言語コード [languageCode] にこのモデルが対応しているかどうか。 */
    fun supportsLanguage(languageCode: String): Boolean = isLanguageSupported(LanguageTag(languageCode))

    companion object {
        /** SenseVoice Small がサポートする言語コード（日本語・英語・中国語・広東語・韓国語）。 */
        val SENSE_VOICE_SUPPORTED_CODES: Set<String> = setOf("ja", "en", "zh", "yue", "ko")

        /** [SttEngine] に対応する [DownloadableSttModel] を返す。対応するローカル ONNX モデルがなければ `null`。 */
        fun fromEngineOrNull(engine: SttEngine): DownloadableSttModel? = entries.firstOrNull { it.sttEngine == engine }
    }
}

/**
 * 全体設定の「文字起こし」タブで表示するダウンロード可能な ONNX モデル 1 件の状態。
 *
 * @property model 対象のモデル種別。
 * @property status モデルのダウンロード・インストール状態（`INSTALLED` / `NOT_INSTALLED` / `DOWNLOADING` / `DOWNLOAD_FAILED`）。
 * @property sizeBytes ディスク上の合計サイズ（バイト、目安）。
 * @property canPurge パージ（削除）ボタンを有効にするかどうか。
 */
data class DownloadableSttModelState(
    val model: DownloadableSttModel,
    val status: OnDeviceLanguageModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
    val sizeBytes: Long = model.approxSizeMb * 1_024L * 1_024L,
    val canPurge: Boolean = status == OnDeviceLanguageModelStatus.INSTALLED,
)

/**
 * ファイル（教材）ごとの文字起こしエンジンの上書き（ADR-0011 §2.3）。
 *
 * @property targetEngine 学習言語のチャンクに使うエンジン。`null` は言語ごとの設定に従う。
 * @property baseEngine ベース言語のチャンクに使うエンジン。`null` は言語ごとの設定に従う。
 */
data class ItemSttOverride(
    val targetEngine: SttEngine? = null,
    val baseEngine: SttEngine? = null,
) {
    /** どちらの言語も上書きしていないかどうか。 */
    val isEmpty: Boolean
        get() = targetEngine == null && baseEngine == null

    companion object {
        /** 上書きなし。 */
        val NONE: ItemSttOverride = ItemSttOverride()
    }
}

/**
 * チャンクの言語に使うエンジンの優先を決める（ADR-0011 §2.2）。
 */
object SttEngineSelection {
    /**
     * 言語 [language] のチャンクに優先して使うエンジンを返す。`null` は自動。
     *
     * ファイルの上書き（[language] が教材の学習言語なら [ItemSttOverride.targetEngine]、ベース言語なら
     * [ItemSttOverride.baseEngine]）→ 言語ごとの設定 [engineByLanguage] → 自動、の順に決める。
     * 言語は [LanguageSettings.normalize] で揃えてから比べる。
     */
    fun preferredEngine(
        language: LanguageTag,
        languagePair: LanguagePair,
        itemOverride: ItemSttOverride,
        engineByLanguage: Map<LanguageTag, SttEngine>,
    ): SttEngine? {
        val normalized = LanguageSettings.normalize(language)
        val overrideEngine = when (normalized) {
            LanguageSettings.normalize(languagePair.target) -> itemOverride.targetEngine
            languagePair.base?.let(LanguageSettings::normalize) -> itemOverride.baseEngine
            else -> null
        }
        return overrideEngine ?: engineByLanguage[normalized]
    }

    /** 言語 [language] のチャンクで試すエンジンの順番を返す（[preferredEngine] と [SttEngine.tryOrder] の組み合わせ）。 */
    fun tryOrder(
        language: LanguageTag,
        languagePair: LanguagePair,
        itemOverride: ItemSttOverride,
        engineByLanguage: Map<LanguageTag, SttEngine>,
    ): List<SttEngine> = SttEngine.tryOrder(
        preferredEngine(language, languagePair, itemOverride, engineByLanguage),
    )

    /**
     * 試す順番 [tryOrder] のうち、モデルの状態 [info] で今すぐ使える（[OnDeviceLanguageModelStatus.INSTALLED]）最初のエンジンを返す。
     *
     * どれも使えないときは `null`。設定画面の「使われるエンジン」の表示に使う。
     */
    fun firstUsableEngine(
        tryOrder: List<SttEngine>,
        info: OnDeviceLanguageModelInfo,
    ): SttEngine? = tryOrder.firstOrNull { engine ->
        info.statusOf(engine) == OnDeviceLanguageModelStatus.INSTALLED
    }

    /**
     * モデルの状態 [info] において、少なくとも 1 つのエンジンが対応（[OnDeviceLanguageModelStatus.UNSUPPORTED] 以外）しているかどうかを返す。
     */
    fun isAnyEngineSupported(info: OnDeviceLanguageModelInfo): Boolean {
        val enriched = info.enrichedWithCatalog()
        return SttEngine.AUTO_ORDER.any { engine ->
            enriched.statusOf(engine) != OnDeviceLanguageModelStatus.UNSUPPORTED
        }
    }

    /**
     * モデルの状態 [info] から、「自動」が選択されたときに解決される既定のエンジンを返す。
     *
     * 1. [SttEngine.AUTO_ORDER] の順で、すでにインストール済み（[OnDeviceLanguageModelStatus.INSTALLED]）の最初のエンジン。
     * 2. いずれも未インストールの場合は、ML Kit が端末・言語で対応確認済み（[OnDeviceLanguageModelStatus.NOT_INSTALLED] または
     *    [OnDeviceLanguageModelStatus.DOWNLOADING]）のときのみ [SttEngine.ML_KIT] を返す。未確認（`CHECKING`）や非対応（`UNSUPPORTED`）の
     *    環境では ML Kit を選ばない。
     * 3. `SpeechRecognizer` が非対応でなければ [SttEngine.SPEECH_RECOGNIZER] を返す。
     * 4. `sherpa-onnx` が非対応でなければ [SttEngine.SHERPA_ONNX] を返す。
     * 5. それ以外は [SttEngine.SPEECH_RECOGNIZER] を返す。
     */
    fun resolveAutoEngine(info: OnDeviceLanguageModelInfo): SttEngine {
        val enriched = info.enrichedWithCatalog()
        firstUsableEngine(SttEngine.AUTO_ORDER, enriched)?.let { return it }
        if (enriched.mlKitStatus == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
            enriched.mlKitStatus == OnDeviceLanguageModelStatus.DOWNLOADING
        ) {
            return SttEngine.ML_KIT
        }
        if (enriched.status != OnDeviceLanguageModelStatus.UNSUPPORTED) {
            return SttEngine.SPEECH_RECOGNIZER
        }
        if (enriched.sherpaModelStatus != OnDeviceLanguageModelStatus.UNSUPPORTED) {
            return SttEngine.SHERPA_ONNX
        }
        return SttEngine.SPEECH_RECOGNIZER
    }
}
