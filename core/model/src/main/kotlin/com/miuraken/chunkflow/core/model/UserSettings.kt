package com.miuraken.chunkflow.core.model

/**
 * 中国語（`zh`）・広東語（`yue`）の表示に使う漢字の字体（§7.4.3、§7.5.5、F9.1）。
 *
 * @property iso15924 [Transcript.textVariants] のキーに対応する ISO 15924 コード。
 */
enum class ChineseScript(val iso15924: String) {
    /** 簡体字（中国語 `zh` の既定）。 */
    HANS("Hans"),

    /** 繁体字（広東語 `yue` の既定）。 */
    HANT("Hant"),
}

/**
 * 非ラテン文字を使う対象言語に対するアルファベット読み方（発音表記）の種別。
 */
enum class PhoneticGuideKind {
    /** 日本語: ローマ字 (Romaji) */
    ROMAJI,

    /** ヒンディー語・インド系言語: IAST (International Alphabet of Sanskrit Transliteration) */
    IAST,

    /** 中国語・広東語系: ピンイン (Pinyin) */
    PINYIN,

    /** 韓国語・キリル文字・アラビア文字・タイ文字・ギリシャ文字などその他の非ラテン文字: ラテン文字（アルファベット）転写 */
    LATIN_TRANSLITERATION,
    ;

    companion object {
        /**
         * 指定した [language] が非ラテン文字を使用する言語であれば対応する [PhoneticGuideKind] を返し、
         * 英語・スペイン語・フランス語・ドイツ語・インドネシア語・ベトナム語などのラテン文字（アルファベット）言語であれば `null` を返す。
         */
        fun forLanguage(language: LanguageTag?): PhoneticGuideKind? {
            val code = language?.value?.trim()?.lowercase()?.substringBefore("-") ?: return null
            return when (code) {
                "ja" -> ROMAJI

                "hi", "sa", "mr", "ne", "bn", "pa", "gu", "or", "ta", "te", "kn", "ml", "si" -> IAST

                "zh", "cmn", "yue", "wuu", "nan", "hak" -> PINYIN

                "ko", "ru", "uk", "bg", "sr", "be", "mk", "kk", "ky", "mn", "tg",
                "el", "ar", "fa", "ur", "ps", "he", "iw", "yi", "th", "lo", "km",
                "my", "ka", "hy", "am", "ti", "bo", "dz", "dv",
                -> LATIN_TRANSLITERATION

                else -> null
            }
        }

        /** 中国語（普通話、`zh`）の簡体字・繁体字切替の対象かどうか。 */
        fun isMandarinChinese(language: LanguageTag?): Boolean {
            val raw = language?.value?.trim()?.lowercase() ?: return false
            if (raw == "zh-yue" || raw == "zh-hk") return false
            val base = raw.substringBefore("-")
            return base == "zh" || base == "cmn"
        }

        /** 広東語（`yue`）の簡体字・繁体字切替の対象かどうか。 */
        fun isCantonese(language: LanguageTag?): Boolean {
            val raw = language?.value?.trim()?.lowercase() ?: return false
            if (raw == "zh-yue" || raw == "zh-hk") return true
            val base = raw.substringBefore("-")
            return base == "yue"
        }
    }
}

/**
 * アプリ全体の設定（§2 F9.1、§6.4 DataStore）。
 *
 * @property defaultLanguagePair 新しく取り込む教材の既定の言語ペア。
 * @property chineseScript 中国語（普通話、`zh`）の表示字体（既定は簡体字 [ChineseScript.HANS]）。
 * @property cantoneseScript 広東語（`yue`）の表示字体（既定は繁体字 [ChineseScript.HANT]）。
 * @property defaultSpeed 既定の再生速度（0.5..3.0）。
 * @property seekBackMs 巻き戻しのミリ秒数（正の値、既定 3,000ms）。
 * @property seekForwardMs 早送りのミリ秒数（正の値、既定 5,000ms）。
 * @property defaultAutoPause 新しく開く教材の既定の自動ポーズ設定（F6.3、F9.1）。
 * @property defaultAutoPauseScope 既定の自動ポーズ対象言語範囲（両言語・学習言語のみ・解説言語のみ）。
 * @property skipOriginalSilence チャンク間の無音（元音声の空白区間）をスキップするかどうか（既定は `false`）。
 * @property baseLanguageSpeedEnabled ベース言語（言語ペアの `base`）のチャンクだけを [baseLanguageSpeed] で再生するかどうか（既定は `false`）。
 * @property baseLanguageSpeed ベース言語のチャンクの再生速度（0.5..3.0、既定 1.0）。[baseLanguageSpeedEnabled] が `true` のときだけ使い、
 *   そのときベース言語のチャンクは、利用者が選んだ速度（1x・もうひとつの再生速度）によらず必ずこの速度で再生する。
 * @property defaultSegmentationParams 端末内の自動チャンク分割の既定パラメータ（§7.3.3、F5.2、F9.1）。
 * @property enabledLanguages 設定画面やダイアログの言語選択肢として表示する言語の集合（言語設定タブで選ぶ。ADR-0011 §2.1）。
 *   保存されていないときの既定は、端末の設定言語と英語（[LanguageSettings.defaultEnabledLanguages]）。
 * @property showTargetRomanization 対象言語が非ラテン文字（日本語・中国語・ヒンディー語・韓国語など）のときに、アルファベットによる読み方（ローマ字・Pinyin・IAST など）をチャンクに付加して表示するかどうか。
 * @property lookaheadTranscriptionEnabled 再生中に未検出のチャンクの先読み文字起こしを自動的に行うかどうか（常にオン `true`）。
 * @property autoStartTranscriptionOnOpen ファイルを開いてチャンク分割・言語判定が終わったら文字起こしを自動で始めるかどうか（既定はオン `true`）。
 * @property backgroundTranscriptionEnabled 再生画面を閉じた後もバックグラウンドでの文字起こしを継続するかどうか（既定はオン `true`）。
 * @property transcriptionEngine 端末内文字起こしで使用する音声認識ライブラリ（エンジン）の選択（既定は [OnDeviceTranscriptionEngine.AUTO]）。
 *   ADR-0011 の移行の途中の設定で、言語ごとの設定（[sttEngineByLanguage]）に切り替わったら消す。
 * @property sttEngineByLanguage 言語ごとに優先する文字起こしエンジン（ADR-0011 §2.2）。キーは [LanguageSettings.normalize] で
 *   そろえた言語。キーがない言語は自動（[SttEngine.AUTO_ORDER]）。
 * @property acknowledgedSttRecommendationRevisions 言語ごとの文字起こしエンジン推奨カタログの変更通知を確認済みとしたリビジョン番号。
 *   キーは [LanguageSettings.normalize] でそろえた言語。
 * @property hasCompletedLanguageOnboarding 初回起動時の言語選択ダイアログを表示・完了したかどうか（既定は `false`）。
 * @property openAiApiKey OpenAI Audio Transcriptions API 用のユーザー設定 API キー（未設定時は空文字）。
 * @property groqApiKey Groq Audio Transcriptions API（OpenAI 互換）用のユーザー設定 API キー（未設定時は空文字）。
 * @property geminiApiKey Google Gemini API 用のユーザー設定 API キー（未設定時は空文字）。
 */
data class UserSettings(
    val defaultLanguagePair: LanguagePair = LanguagePair(
        target = LanguageTag("en"),
        base = LanguageTag("ja"),
    ),
    val chineseScript: ChineseScript = ChineseScript.HANS,
    val cantoneseScript: ChineseScript = ChineseScript.HANT,
    val defaultSpeed: Float = 1.0f,
    val seekBackMs: Long = DEFAULT_SEEK_BACK_MS,
    val seekForwardMs: Long = DEFAULT_SEEK_FORWARD_MS,
    val defaultAutoPause: AutoPause = AutoPause.Off,
    val defaultAutoPauseScope: AutoPauseScope = AutoPauseScope.ALL_CHUNKS,
    val skipOriginalSilence: Boolean = false,
    val baseLanguageSpeedEnabled: Boolean = false,
    val baseLanguageSpeed: Float = 1.0f,
    val defaultSegmentationParams: SegmentationParams = SegmentationParams(),
    val enabledLanguages: Set<LanguageTag> = DEFAULT_ENABLED_LANGUAGES,
    val showTargetRomanization: Boolean = false,
    val lookaheadTranscriptionEnabled: Boolean = true,
    val autoStartTranscriptionOnOpen: Boolean = lookaheadTranscriptionEnabled,
    val backgroundTranscriptionEnabled: Boolean = true,
    val transcriptionEngine: OnDeviceTranscriptionEngine = OnDeviceTranscriptionEngine.AUTO,
    val sttEngineByLanguage: Map<LanguageTag, SttEngine> = emptyMap(),
    val acknowledgedSttRecommendationRevisions: Map<LanguageTag, Int> = emptyMap(),
    val hasCompletedLanguageOnboarding: Boolean = false,
    val openAiApiKey: String = "",
    val groqApiKey: String = "",
    val geminiApiKey: String = "",
) {
    init {
        require(defaultSpeed in ItemState.MIN_SPEED..ItemState.MAX_SPEED) {
            "defaultSpeed must be in ${ItemState.MIN_SPEED}..${ItemState.MAX_SPEED}: $defaultSpeed"
        }
        require(baseLanguageSpeed in ItemState.MIN_SPEED..ItemState.MAX_SPEED) {
            "baseLanguageSpeed must be in ${ItemState.MIN_SPEED}..${ItemState.MAX_SPEED}: $baseLanguageSpeed"
        }
        require(seekBackMs > 0L) { "seekBackMs must be > 0: $seekBackMs" }
        require(seekForwardMs > 0L) { "seekForwardMs must be > 0: $seekForwardMs" }
        require(enabledLanguages.isNotEmpty()) { "enabledLanguages must not be empty" }
    }

    companion object {
        /** 巻き戻しの既定値（3 秒、F3.3）。 */
        const val DEFAULT_SEEK_BACK_MS: Long = 3_000L

        /** 早送りの既定値（5 秒、F3.3）。 */
        const val DEFAULT_SEEK_FORWARD_MS: Long = 5_000L

        /**
         * 端末の言語がわからないとき（プレビュー・テストなど）に使う、選択肢として有効な言語の既定セット（日本語・英語）。
         *
         * アプリの実行時の既定は、端末の設定言語と英語（[LanguageSettings.defaultEnabledLanguages]）。
         */
        val DEFAULT_ENABLED_LANGUAGES: Set<LanguageTag> = setOf(
            LanguageTag("ja"),
            LanguageTag("en"),
        )
    }
}
