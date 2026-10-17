package com.miuraken.chunkflow.core.model

/**
 * 端末内の文字起こし（チャンク単位の音声認識）に使うエンジン（ADR-0011）。
 */
enum class SttEngine {
    /** ML Kit GenAI Speech Recognition。基本モードと高度モードは、端末と言語に合わせて自動で選ぶ。 */
    ML_KIT,

    /** Android 標準の `SpeechRecognizer`（端末内の音声認識）。 */
    SPEECH_RECOGNIZER,

    /** `sherpa-onnx` の Whisper モデル（ONNX Runtime で動かす。言語ごとにモデルのダウンロードが必要）。 */
    SHERPA_ONNX,
    ;

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
}
