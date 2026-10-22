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

    /** Moonshine ONNX Tiny モデル（ONNX Runtime で動かす。英・日・中・韓・西・アラビア・ウクライナ・ベトナムの 8 言語対応、言語別モデル）。 */
    MOONSHINE_TINY,

    /** Moonshine ONNX Base モデル（ONNX Runtime で動かす。英・日・中・韓・西・アラビア・ウクライナ・ベトナムの 8 言語対応、言語別モデル）。 */
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
 * @property isPerLanguageModel 言語ごとに別々のモデルファイルをダウンロードする形式かどうか（Moonshine ONNX Tiny/Base は `true`）。
 */
enum class DownloadableSttModel(
    val sttEngine: SttEngine,
    val modelId: String,
    val displayName: String,
    val approxSizeMb: Int,
    val isPerLanguageModel: Boolean = false,
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

    /** Moonshine ONNX Tiny int8（8 言語対応・言語別モデル・約 27〜120 MB）。 */
    MOONSHINE_TINY(
        sttEngine = SttEngine.MOONSHINE_TINY,
        modelId = "sherpa-onnx-moonshine-tiny-en-int8",
        displayName = "Moonshine ONNX Tiny",
        approxSizeMb = 118,
        isPerLanguageModel = true,
    ),

    /** Moonshine ONNX Base int8（8 言語対応・言語別モデル・約 61〜274 MB）。 */
    MOONSHINE_BASE(
        sttEngine = SttEngine.MOONSHINE_BASE,
        modelId = "sherpa-onnx-moonshine-base-en-int8",
        displayName = "Moonshine ONNX Base",
        approxSizeMb = 274,
        isPerLanguageModel = true,
    ),
    ;

    /** 目安サイズ（MB）。 */
    val estimatedSizeMb: Int
        get() = approxSizeMb

    /** 目安サイズ（バイト）。 */
    val estimatedSizeBytes: Long
        get() = approxSizeMb * 1_024L * 1_024L

    /** 言語別モデルの対応言語タグ一覧（全言語共通モデルの場合は空リスト）。 */
    val supportedLanguages: List<LanguageTag>
        get() = when (this) {
            MOONSHINE_TINY,
            MOONSHINE_BASE,
            -> MOONSHINE_SUPPORTED_LANGUAGE_TAGS

            else -> emptyList()
        }

    /** 指定した言語 [language] に対する目安サイズ（MB）を返す。 */
    fun approxSizeMbForLanguage(language: LanguageTag): Int {
        val entry = SupportedLanguageCatalog.findByCode(language.value)
        val whisperCode = entry?.whisperCode ?: language.value.trim().lowercase().substringBefore('-')
        return when (this) {
            MOONSHINE_TINY -> when (whisperCode) {
                "en" -> 118
                "ja" -> 69
                "zh" -> 27
                "ko" -> 69
                "es" -> 62
                "ar" -> 28
                "uk" -> 120
                "vi" -> 120
                else -> approxSizeMb
            }

            MOONSHINE_BASE -> when (whisperCode) {
                "en" -> 274
                "ja" -> 135
                "zh" -> 135
                "ko" -> 61
                "es" -> 62
                "ar" -> 135
                "uk" -> 135
                "vi" -> 135
                else -> approxSizeMb
            }

            else -> approxSizeMb
        }
    }

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
            -> whisperCode in MOONSHINE_SUPPORTED_CODES
        }
    }

    /** 指定した言語 [language] にこのモデルが対応しているかどうか。 */
    fun supportsLanguage(language: LanguageTag): Boolean = isLanguageSupported(language)

    /** 指定した言語コード [languageCode] にこのモデルが対応しているかどうか。 */
    fun supportsLanguage(languageCode: String): Boolean = isLanguageSupported(LanguageTag(languageCode))

    companion object {
        /** SenseVoice Small がサポートする言語コード（日本語・英語・中国語・広東語・韓国語）。 */
        val SENSE_VOICE_SUPPORTED_CODES: Set<String> = setOf("ja", "en", "zh", "yue", "ko")

        /** Moonshine ONNX がサポートする 8 言語コード（英語・日本語・中国語・韓国語・スペイン語・アラビア語・ウクライナ語・ベトナム語）。 */
        val MOONSHINE_SUPPORTED_CODES: Set<String> = setOf("en", "ja", "zh", "ko", "es", "ar", "uk", "vi")

        /** Moonshine ONNX がサポートする 8 言語の [LanguageTag] 一覧（表示順）。 */
        val MOONSHINE_SUPPORTED_LANGUAGE_TAGS: List<LanguageTag> =
            listOf("en", "ja", "zh", "ko", "es", "ar", "uk", "vi").map(::LanguageTag)

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
 * @property canPurge 削除（パージ）ボタンを有効にするかどうか。
 * @property availableLanguages 言語別モデル（Moonshine 等）で利用可能な対応言語一覧。
 * @property downloadedLanguages 言語別モデル（Moonshine 等）で現在ダウンロード済みの言語一覧。
 */
data class DownloadableSttModelState(
    val model: DownloadableSttModel,
    val status: OnDeviceLanguageModelStatus = OnDeviceLanguageModelStatus.NOT_INSTALLED,
    val sizeBytes: Long = model.approxSizeMb * 1_024L * 1_024L,
    val canPurge: Boolean = status == OnDeviceLanguageModelStatus.INSTALLED,
    val availableLanguages: List<LanguageTag> = model.supportedLanguages,
    val downloadedLanguages: List<LanguageTag> = emptyList(),
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
    ): List<SttEngine> {
        val preferred = preferredEngine(language, languagePair, itemOverride, engineByLanguage)
            ?: recommendedDownloadEngineForLanguage(language)
        return SttEngine.tryOrder(preferred)
    }

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
     * 1. その言語の推奨ダウンロードモデル（[recommendedDownloadEngineForLanguage]）がすでにインストール済みなら、それを優先する。
     * 2. [SttEngine.AUTO_ORDER] の順で、すでにインストール済み（[OnDeviceLanguageModelStatus.INSTALLED]）の最初のエンジン。
     * 3. いずれも未インストールの場合は、ML Kit が端末・言語で対応確認済み（[OnDeviceLanguageModelStatus.NOT_INSTALLED] または
     *    [OnDeviceLanguageModelStatus.DOWNLOADING]）のときのみ [SttEngine.ML_KIT] を返す。未確認（`CHECKING`）や非対応（`UNSUPPORTED`）の
     *    環境では ML Kit を選ばない。
     * 4. `SpeechRecognizer` が非対応でなければ [SttEngine.SPEECH_RECOGNIZER] を返す。
     * 5. 推奨ダウンロードモデルがある言語で OS 標準が非対応なら、その推奨モデルを返す。
     * 6. `sherpa-onnx` が非対応でなければ [SttEngine.SHERPA_ONNX] を返す。
     * 7. それ以外は [SttEngine.SPEECH_RECOGNIZER] を返す。
     */
    fun resolveAutoEngine(info: OnDeviceLanguageModelInfo): SttEngine {
        val enriched = info.enrichedWithCatalog()
        val recommended = recommendedDownloadEngineForLanguage(enriched.language)
        if (recommended != null && enriched.statusOf(recommended) == OnDeviceLanguageModelStatus.INSTALLED) {
            return recommended
        }
        firstUsableEngine(SttEngine.AUTO_ORDER, enriched)?.let { return it }
        if (enriched.mlKitStatus == OnDeviceLanguageModelStatus.NOT_INSTALLED ||
            enriched.mlKitStatus == OnDeviceLanguageModelStatus.DOWNLOADING
        ) {
            return SttEngine.ML_KIT
        }
        if (enriched.status != OnDeviceLanguageModelStatus.UNSUPPORTED) {
            return SttEngine.SPEECH_RECOGNIZER
        }
        if (recommended != null && enriched.statusOf(recommended) != OnDeviceLanguageModelStatus.UNSUPPORTED) {
            return recommended
        }
        if (enriched.sherpaModelStatus != OnDeviceLanguageModelStatus.UNSUPPORTED) {
            return SttEngine.SHERPA_ONNX
        }
        return SttEngine.SPEECH_RECOGNIZER
    }

    /**
     * 言語 [language] のファイルを開いて音声認識を開始するとき、ダウンロードを推奨する単一のオンデバイスモデルを返す。
     *
     * 基本のデフォルトである `ML Kit`（およびその裏の `SpeechRecognizer`）よりも**明確に速く、かつ精度も高い（または同等の最高精度）**
     * 言語、あるいは `ML Kit` が非対応で明確な推奨モデルがある言語に対して、その単一の推奨エンジンを返す。
     * `ML Kit` が最良の選択肢である言語では `null` を返す。
     */
    fun recommendedDownloadEngineForLanguage(language: LanguageTag): SttEngine? {
        val code = SupportedLanguageCatalog.normalizeToModelManagementCode(language.value)
        return when (code) {
            "zh", "zh-cn", "zh-tw" -> SttEngine.SHERPA_ONNX_SENSE_VOICE
            "yue" -> SttEngine.SHERPA_ONNX_SENSE_VOICE
            "vi" -> SttEngine.MOONSHINE_BASE
            "ar" -> SttEngine.MOONSHINE_BASE
            "uk" -> SttEngine.MOONSHINE_BASE
            "th", "ro", "fa", "pa", "sw" -> SttEngine.SHERPA_ONNX_WHISPER_SMALL
            else -> null
        }
    }

    /**
     * 言語ごとの通常文字起こしエンジン選択リストに表示するエンジン一覧を返す。
     *
     * - `SpeechRecognizer` は `ML Kit` の裏の自動フォールバック専用とし、選択肢には出さない。
     * - `Whisper Base` は全言語で他のエンジンに精度・速度とも劣るため除外する。
     * - `Whisper Small` は原則として「より詳しく文字起こし」専用とするが、OS 音声認識と Moonshine/SenseVoice がともに非対応で
     *   `Whisper Tiny` の精度が著しく不足する言語（`th`, `ro`, `fa`, `pa`, `sw`）では第一推奨として選択肢に含める。
     * - 言語ごとに不具合があるモデル（韓国語の `Moonshine Base`、日本語の `Moonshine`、中国語の `Moonshine Tiny` など）は除外し、
     *   その言語でおすすめできる端末内モデルは未ダウンロードでも一覧に含める（設定画面では未ダウンロード時は無効化しダウンロードボタンを表示する）。
     */
    fun selectableEnginesForLanguage(
        info: OnDeviceLanguageModelInfo,
        currentPreferred: SttEngine? = null,
    ): List<SttEngine> {
        val enriched = info.enrichedWithCatalog()
        val code = SupportedLanguageCatalog.normalizeToModelManagementCode(enriched.language.value)
        val baseCandidates = when (code) {
            "en" -> listOf(
                SttEngine.ML_KIT,
                SttEngine.MOONSHINE_BASE,
                SttEngine.SHERPA_ONNX,
            )

            "ja" -> listOf(
                SttEngine.ML_KIT,
                SttEngine.SHERPA_ONNX_SENSE_VOICE,
                SttEngine.SHERPA_ONNX,
            )

            "zh", "zh-cn", "zh-tw" -> listOf(
                SttEngine.SHERPA_ONNX_SENSE_VOICE,
                SttEngine.ML_KIT,
                SttEngine.MOONSHINE_BASE,
                SttEngine.SHERPA_ONNX,
            )

            "yue" -> listOf(
                SttEngine.SHERPA_ONNX_SENSE_VOICE,
                SttEngine.SHERPA_ONNX,
            )

            "ko" -> listOf(
                SttEngine.ML_KIT,
                SttEngine.MOONSHINE_TINY,
                SttEngine.SHERPA_ONNX_SENSE_VOICE,
                SttEngine.SHERPA_ONNX,
            )

            "hi" -> listOf(
                SttEngine.ML_KIT,
                SttEngine.SHERPA_ONNX,
            )

            "es" -> listOf(
                SttEngine.ML_KIT,
                SttEngine.MOONSHINE_TINY,
                SttEngine.MOONSHINE_BASE,
                SttEngine.SHERPA_ONNX,
            )

            "vi" -> listOf(
                SttEngine.MOONSHINE_BASE,
                SttEngine.ML_KIT,
                SttEngine.SHERPA_ONNX,
            )

            "ar" -> listOf(
                SttEngine.MOONSHINE_BASE,
                SttEngine.SHERPA_ONNX,
            )

            "uk" -> listOf(
                SttEngine.MOONSHINE_BASE,
                SttEngine.SHERPA_ONNX,
            )

            "th", "ro", "fa", "pa", "sw" -> listOf(
                SttEngine.SHERPA_ONNX_WHISPER_SMALL,
                SttEngine.SHERPA_ONNX,
            )

            else -> buildList {
                // ML Kit または裏の SpeechRecognizer が対応している場合は ML_KIT を選択肢に含める
                if (enriched.mlKitStatus != OnDeviceLanguageModelStatus.UNSUPPORTED ||
                    enriched.status != OnDeviceLanguageModelStatus.UNSUPPORTED
                ) {
                    add(SttEngine.ML_KIT)
                }
                if (enriched.sherpaModelStatus != OnDeviceLanguageModelStatus.UNSUPPORTED) {
                    add(SttEngine.SHERPA_ONNX)
                }
            }
        }

        val cloudEngines = listOf(SttEngine.OPENAI_API, SttEngine.GROQ_API, SttEngine.GEMINI_API).filter { cloud ->
            enriched.statusOf(cloud) == OnDeviceLanguageModelStatus.INSTALLED || currentPreferred == cloud
        }

        val combined = buildList {
            addAll(baseCandidates)
            addAll(cloudEngines)
            if (currentPreferred != null &&
                currentPreferred != SttEngine.SPEECH_RECOGNIZER &&
                currentPreferred !in this
            ) {
                add(currentPreferred)
            }
        }
        return combined.distinct()
    }

    /**
     * 単一チャンクの「より詳しく文字起こし」ダイアログで選択できるエンジン一覧を返す。
     *
     * 言語別の通常選択リストに加え、2〜4 倍の実時間とメモリを使う代わりに高精度な `Whisper Small`
     * （[SttEngine.SHERPA_ONNX_WHISPER_SMALL]）を対応言語で選択できるようにする。
     */
    fun detailedTrialEnginesForLanguage(info: OnDeviceLanguageModelInfo): List<SttEngine> {
        val enriched = info.enrichedWithCatalog()
        val base = selectableEnginesForLanguage(enriched)
        val whisperSmallSupported =
            enriched.statusOf(SttEngine.SHERPA_ONNX_WHISPER_SMALL) != OnDeviceLanguageModelStatus.UNSUPPORTED
        if (!whisperSmallSupported || SttEngine.SHERPA_ONNX_WHISPER_SMALL in base) {
            return base
        }
        // Whisper Tiny (SHERPA_ONNX) の直前、またはクラウド API の前に Whisper Small を挿入する
        val result = mutableListOf<SttEngine>()
        var inserted = false
        for (engine in base) {
            if (!inserted && (engine == SttEngine.SHERPA_ONNX || engine.isCloudApi)) {
                result.add(SttEngine.SHERPA_ONNX_WHISPER_SMALL)
                inserted = true
            }
            result.add(engine)
        }
        if (!inserted) {
            result.add(SttEngine.SHERPA_ONNX_WHISPER_SMALL)
        }
        return result
    }

    /**
     * 言語 [language] におけるエンジン [engine] の特徴ラベル一覧（「おすすめ」「高精度」「高速」「低速」）を返す。
     */
    fun labelsFor(
        language: LanguageTag,
        engine: SttEngine,
    ): List<SttEngineLabel> {
        val code = SupportedLanguageCatalog.normalizeToModelManagementCode(language.value)
        if (engine == SttEngine.SHERPA_ONNX_WHISPER_SMALL) {
            return when (code) {
                "th", "ro", "fa", "pa", "sw" -> listOf(
                    SttEngineLabel.RECOMMENDED,
                    SttEngineLabel.HIGH_ACCURACY,
                    SttEngineLabel.SLOW,
                )

                "yue", "hi", "vi", "uk" -> listOf(SttEngineLabel.SLOW)

                else -> listOf(SttEngineLabel.HIGH_ACCURACY, SttEngineLabel.SLOW)
            }
        }
        return when (code) {
            "en" -> when (engine) {
                SttEngine.ML_KIT -> listOf(SttEngineLabel.HIGH_ACCURACY)
                SttEngine.MOONSHINE_BASE -> listOf(SttEngineLabel.FAST)
                else -> emptyList()
            }

            "ja" -> when (engine) {
                SttEngine.ML_KIT -> listOf(SttEngineLabel.HIGH_ACCURACY)
                SttEngine.SHERPA_ONNX_SENSE_VOICE -> listOf(SttEngineLabel.FAST)
                else -> emptyList()
            }

            "zh", "zh-cn", "zh-tw" -> when (engine) {
                SttEngine.SHERPA_ONNX_SENSE_VOICE -> listOf(
                    SttEngineLabel.RECOMMENDED,
                    SttEngineLabel.HIGH_ACCURACY,
                    SttEngineLabel.FAST,
                )

                SttEngine.MOONSHINE_BASE -> listOf(SttEngineLabel.FAST)

                else -> emptyList()
            }

            "yue" -> when (engine) {
                SttEngine.SHERPA_ONNX_SENSE_VOICE -> listOf(
                    SttEngineLabel.RECOMMENDED,
                    SttEngineLabel.HIGH_ACCURACY,
                    SttEngineLabel.FAST,
                )

                else -> emptyList()
            }

            "ko" -> when (engine) {
                SttEngine.ML_KIT -> listOf(SttEngineLabel.HIGH_ACCURACY)
                SttEngine.MOONSHINE_TINY -> listOf(SttEngineLabel.FAST)
                SttEngine.SHERPA_ONNX_SENSE_VOICE -> listOf(SttEngineLabel.FAST)
                else -> emptyList()
            }

            "hi" -> when (engine) {
                SttEngine.ML_KIT -> listOf(SttEngineLabel.HIGH_ACCURACY)
                else -> emptyList()
            }

            "es" -> when (engine) {
                SttEngine.ML_KIT -> listOf(SttEngineLabel.HIGH_ACCURACY, SttEngineLabel.FAST)
                SttEngine.MOONSHINE_TINY -> listOf(SttEngineLabel.HIGH_ACCURACY, SttEngineLabel.FAST)
                SttEngine.MOONSHINE_BASE -> listOf(SttEngineLabel.HIGH_ACCURACY, SttEngineLabel.FAST)
                else -> emptyList()
            }

            "de", "it", "pt", "nl", "ru", "pl", "tr", "id" -> when (engine) {
                SttEngine.ML_KIT -> listOf(SttEngineLabel.HIGH_ACCURACY)
                else -> emptyList()
            }

            "vi" -> when (engine) {
                SttEngine.MOONSHINE_BASE -> listOf(
                    SttEngineLabel.RECOMMENDED,
                    SttEngineLabel.HIGH_ACCURACY,
                    SttEngineLabel.FAST,
                )

                SttEngine.ML_KIT -> listOf(SttEngineLabel.HIGH_ACCURACY)

                else -> emptyList()
            }

            "ar" -> when (engine) {
                SttEngine.MOONSHINE_BASE -> listOf(
                    SttEngineLabel.RECOMMENDED,
                    SttEngineLabel.HIGH_ACCURACY,
                    SttEngineLabel.FAST,
                )

                else -> emptyList()
            }

            "uk" -> when (engine) {
                SttEngine.MOONSHINE_BASE -> listOf(
                    SttEngineLabel.RECOMMENDED,
                    SttEngineLabel.HIGH_ACCURACY,
                    SttEngineLabel.FAST,
                )

                else -> emptyList()
            }

            else -> emptyList()
        }
    }
}

/**
 * 言語ごとの文字起こしエンジン選択肢に付与する特徴ラベル。
 */
enum class SttEngineLabel {
    /** デフォルトの `ML Kit` より明確に速く高精度（または `ML Kit` 非対応言語での第一選択）な推奨モデル。 */
    RECOMMENDED,

    /** その言語で特に文字起こし精度が高いエンジン。 */
    HIGH_ACCURACY,

    /** リアルタイム比（RTF）が小さく高速に動作するエンジン。 */
    FAST,

    /** 処理時間が実時間の 2〜4 倍程度かかる低速なエンジン（`Whisper Small` など）。 */
    SLOW,
}
