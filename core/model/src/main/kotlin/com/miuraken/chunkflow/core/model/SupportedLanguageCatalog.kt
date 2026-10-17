package com.miuraken.chunkflow.core.model

import java.util.Locale

/**
 * アプリ内の音声認識・文字起こし・言語識別エンジン（Android `SpeechRecognizer` / `sherpa-onnx` Whisper /
 * `ML Kit Language Identification`）で利用可能な言語の統合カタログ。
 *
 * @property code アプリ内で標準使用する言語コード（例: `"en"`, `"ja"`, `"zh"`, `"zh-cn"`, `"zh-tw"`, `"yue"`）。
 * @property japaneseName 日本語での言語表示名。
 * @property englishName 英語での言語表示名。
 * @property defaultBcp47Tag Android `SpeechRecognizer` 等に渡す代表 BCP-47 ロケールタグ。
 * @property bcp47Candidates `SpeechRecognizer` のモデル照会・フォールバックに使う BCP-47 ロケール候補一覧。
 * @property whisperCode `sherpa-onnx` (Whisper) に渡す言語コード。非対応の場合は `null`。
 * @property speechRecognizerSupported Android `SpeechRecognizer` (SODA / Google 音声認識) の対象言語かどうか。
 * @property mlKitSupported `ML Kit Language Identification` の対象言語かどうか。
 */
data class SupportedLanguageEntry(
    val code: String,
    val japaneseName: String,
    val englishName: String,
    val defaultBcp47Tag: String,
    val bcp47Candidates: List<String> = listOf(defaultBcp47Tag),
    val whisperCode: String? = code,
    val speechRecognizerSupported: Boolean = true,
    val mlKitSupported: Boolean = true,
) {
    /** [LanguageTag] 形式の言語タグ。 */
    val tag: LanguageTag get() = LanguageTag(code)

    /** `"日本語名 (code)"` 形式のラベルを返す。 */
    val labelWithCode: String get() = "$japaneseName ($code)"
}

/**
 * `SpeechRecognizer`、`sherpa-onnx` (Whisper 99 言語 + 広東語・中国語字体別)、および `ML Kit` で
 * 利用可能な全言語コードの定義と変換ヘルパー。
 */
object SupportedLanguageCatalog {

    /**
     * 全対応言語のエントリ一覧。
     *
     * 主要な学習・母語言語を先頭に置き、続けてコードのアルファベット順に並べている。
     */
    val ALL_ENTRIES: List<SupportedLanguageEntry> = listOf(
        // 主要言語
        SupportedLanguageEntry(
            code = "ja",
            japaneseName = "日本語",
            englishName = "Japanese",
            defaultBcp47Tag = "ja-JP",
            bcp47Candidates = listOf("ja-JP", "ja"),
            whisperCode = "ja",
        ),
        SupportedLanguageEntry(
            code = "en",
            japaneseName = "英語",
            englishName = "English",
            defaultBcp47Tag = "en-US",
            bcp47Candidates = listOf("en-US", "en-GB", "en-AU", "en-CA", "en-IN", "en"),
            whisperCode = "en",
        ),
        SupportedLanguageEntry(
            code = "ko",
            japaneseName = "韓国語",
            englishName = "Korean",
            defaultBcp47Tag = "ko-KR",
            bcp47Candidates = listOf("ko-KR", "ko"),
            whisperCode = "ko",
        ),
        SupportedLanguageEntry(
            code = "zh",
            japaneseName = "中国語",
            englishName = "Chinese",
            defaultBcp47Tag = "zh-CN",
            bcp47Candidates = listOf("zh-CN", "cmn-Hans-CN", "zh-Hans-CN", "cmn-CN", "zh"),
            whisperCode = "zh",
        ),
        SupportedLanguageEntry(
            code = "zh-cn",
            japaneseName = "簡体中国語",
            englishName = "Chinese (Simplified)",
            defaultBcp47Tag = "zh-CN",
            bcp47Candidates = listOf("zh-CN", "cmn-Hans-CN", "zh-Hans-CN", "cmn-CN", "zh"),
            whisperCode = "zh",
        ),
        SupportedLanguageEntry(
            code = "zh-tw",
            japaneseName = "繁体中国語",
            englishName = "Chinese (Traditional)",
            defaultBcp47Tag = "zh-TW",
            bcp47Candidates = listOf("zh-TW", "cmn-Hant-TW", "zh-Hant-TW", "cmn-TW"),
            whisperCode = "zh",
        ),
        SupportedLanguageEntry(
            code = "yue",
            japaneseName = "広東語",
            englishName = "Cantonese",
            defaultBcp47Tag = "yue-HK",
            bcp47Candidates = listOf("yue-HK", "yue-Hant-HK", "zh-HK", "zh-Hant-HK", "cmn-Hant-HK", "yue"),
            whisperCode = "yue",
        ),
        SupportedLanguageEntry(
            code = "es",
            japaneseName = "スペイン語",
            englishName = "Spanish",
            defaultBcp47Tag = "es-ES",
            bcp47Candidates = listOf("es-ES", "es-MX", "es-US", "es-AR", "es"),
            whisperCode = "es",
        ),
        SupportedLanguageEntry(
            code = "fr",
            japaneseName = "フランス語",
            englishName = "French",
            defaultBcp47Tag = "fr-FR",
            bcp47Candidates = listOf("fr-FR", "fr-CA", "fr-BE", "fr-CH", "fr"),
            whisperCode = "fr",
        ),
        SupportedLanguageEntry(
            code = "de",
            japaneseName = "ドイツ語",
            englishName = "German",
            defaultBcp47Tag = "de-DE",
            bcp47Candidates = listOf("de-DE", "de-AT", "de-CH", "de"),
            whisperCode = "de",
        ),
        SupportedLanguageEntry(
            code = "it",
            japaneseName = "イタリア語",
            englishName = "Italian",
            defaultBcp47Tag = "it-IT",
            bcp47Candidates = listOf("it-IT", "it-CH", "it"),
            whisperCode = "it",
        ),
        SupportedLanguageEntry(
            code = "pt",
            japaneseName = "ポルトガル語",
            englishName = "Portuguese",
            defaultBcp47Tag = "pt-BR",
            bcp47Candidates = listOf("pt-BR", "pt-PT", "pt"),
            whisperCode = "pt",
        ),
        SupportedLanguageEntry(
            code = "ru",
            japaneseName = "ロシア語",
            englishName = "Russian",
            defaultBcp47Tag = "ru-RU",
            bcp47Candidates = listOf("ru-RU", "ru"),
            whisperCode = "ru",
        ),
        SupportedLanguageEntry(
            code = "hi",
            japaneseName = "ヒンディー語",
            englishName = "Hindi",
            defaultBcp47Tag = "hi-IN",
            bcp47Candidates = listOf("hi-IN", "hi"),
            whisperCode = "hi",
        ),
        SupportedLanguageEntry(
            code = "vi",
            japaneseName = "ベトナム語",
            englishName = "Vietnamese",
            defaultBcp47Tag = "vi-VN",
            bcp47Candidates = listOf("vi-VN", "vi"),
            whisperCode = "vi",
        ),
        SupportedLanguageEntry(
            code = "id",
            japaneseName = "インドネシア語",
            englishName = "Indonesian",
            defaultBcp47Tag = "id-ID",
            bcp47Candidates = listOf("id-ID", "in-ID", "id"),
            whisperCode = "id",
        ),
        SupportedLanguageEntry(
            code = "th",
            japaneseName = "タイ語",
            englishName = "Thai",
            defaultBcp47Tag = "th-TH",
            bcp47Candidates = listOf("th-TH", "th"),
            whisperCode = "th",
        ),
        SupportedLanguageEntry(
            code = "ar",
            japaneseName = "アラビア語",
            englishName = "Arabic",
            defaultBcp47Tag = "ar-SA",
            bcp47Candidates = listOf("ar-SA", "ar-EG", "ar-AE", "ar-001", "ar"),
            whisperCode = "ar",
        ),
        SupportedLanguageEntry(
            code = "tr",
            japaneseName = "トルコ語",
            englishName = "Turkish",
            defaultBcp47Tag = "tr-TR",
            bcp47Candidates = listOf("tr-TR", "tr"),
            whisperCode = "tr",
        ),
        SupportedLanguageEntry(
            code = "nl",
            japaneseName = "オランダ語",
            englishName = "Dutch",
            defaultBcp47Tag = "nl-NL",
            bcp47Candidates = listOf("nl-NL", "nl-BE", "nl"),
            whisperCode = "nl",
        ),
        SupportedLanguageEntry(
            code = "pl",
            japaneseName = "ポーランド語",
            englishName = "Polish",
            defaultBcp47Tag = "pl-PL",
            bcp47Candidates = listOf("pl-PL", "pl"),
            whisperCode = "pl",
        ),
        SupportedLanguageEntry(
            code = "sv",
            japaneseName = "スウェーデン語",
            englishName = "Swedish",
            defaultBcp47Tag = "sv-SE",
            bcp47Candidates = listOf("sv-SE", "sv"),
            whisperCode = "sv",
        ),
        SupportedLanguageEntry(
            code = "el",
            japaneseName = "ギリシャ語",
            englishName = "Greek",
            defaultBcp47Tag = "el-GR",
            bcp47Candidates = listOf("el-GR", "el"),
            whisperCode = "el",
        ),
        SupportedLanguageEntry(
            code = "uk",
            japaneseName = "ウクライナ語",
            englishName = "Ukrainian",
            defaultBcp47Tag = "uk-UA",
            bcp47Candidates = listOf("uk-UA", "uk"),
            whisperCode = "uk",
        ),
        // その他の SpeechRecognizer / sherpa-onnx (Whisper) / ML Kit 対応言語（アルファベット順）
        SupportedLanguageEntry(
            code = "af",
            japaneseName = "アフリカーンス語",
            englishName = "Afrikaans",
            defaultBcp47Tag = "af-ZA",
            bcp47Candidates = listOf("af-ZA", "af"),
            whisperCode = "af",
        ),
        SupportedLanguageEntry(
            code = "am",
            japaneseName = "アムハラ語",
            englishName = "Amharic",
            defaultBcp47Tag = "am-ET",
            bcp47Candidates = listOf("am-ET", "am"),
            whisperCode = "am",
        ),
        SupportedLanguageEntry(
            code = "as",
            japaneseName = "アッサム語",
            englishName = "Assamese",
            defaultBcp47Tag = "as-IN",
            bcp47Candidates = listOf("as-IN", "as"),
            whisperCode = "as",
            mlKitSupported = false,
        ),
        SupportedLanguageEntry(
            code = "az",
            japaneseName = "アゼルバイジャン語",
            englishName = "Azerbaijani",
            defaultBcp47Tag = "az-AZ",
            bcp47Candidates = listOf("az-AZ", "az"),
            whisperCode = "az",
        ),
        SupportedLanguageEntry(
            code = "ba",
            japaneseName = "バシキール語",
            englishName = "Bashkir",
            defaultBcp47Tag = "ba-RU",
            bcp47Candidates = listOf("ba-RU", "ba"),
            whisperCode = "ba",
            speechRecognizerSupported = false,
            mlKitSupported = false,
        ),
        SupportedLanguageEntry(
            code = "be",
            japaneseName = "ベラルーシ語",
            englishName = "Belarusian",
            defaultBcp47Tag = "be-BY",
            bcp47Candidates = listOf("be-BY", "be"),
            whisperCode = "be",
        ),
        SupportedLanguageEntry(
            code = "bg",
            japaneseName = "ブルガリア語",
            englishName = "Bulgarian",
            defaultBcp47Tag = "bg-BG",
            bcp47Candidates = listOf("bg-BG", "bg"),
            whisperCode = "bg",
        ),
        SupportedLanguageEntry(
            code = "bn",
            japaneseName = "ベンガル語",
            englishName = "Bengali",
            defaultBcp47Tag = "bn-BD",
            bcp47Candidates = listOf("bn-BD", "bn-IN", "bn"),
            whisperCode = "bn",
        ),
        SupportedLanguageEntry(
            code = "bo",
            japaneseName = "チベット語",
            englishName = "Tibetan",
            defaultBcp47Tag = "bo-CN",
            bcp47Candidates = listOf("bo-CN", "bo"),
            whisperCode = "bo",
            speechRecognizerSupported = false,
            mlKitSupported = false,
        ),
        SupportedLanguageEntry(
            code = "br",
            japaneseName = "ブルトン語",
            englishName = "Breton",
            defaultBcp47Tag = "br-FR",
            bcp47Candidates = listOf("br-FR", "br"),
            whisperCode = "br",
            speechRecognizerSupported = false,
            mlKitSupported = false,
        ),
        SupportedLanguageEntry(
            code = "bs",
            japaneseName = "ボスニア語",
            englishName = "Bosnian",
            defaultBcp47Tag = "bs-BA",
            bcp47Candidates = listOf("bs-BA", "bs"),
            whisperCode = "bs",
        ),
        SupportedLanguageEntry(
            code = "ca",
            japaneseName = "カタルーニャ語",
            englishName = "Catalan",
            defaultBcp47Tag = "ca-ES",
            bcp47Candidates = listOf("ca-ES", "ca"),
            whisperCode = "ca",
        ),
        SupportedLanguageEntry(
            code = "ceb",
            japaneseName = "セブアノ語",
            englishName = "Cebuano",
            defaultBcp47Tag = "ceb-PH",
            bcp47Candidates = listOf("ceb-PH", "ceb"),
            whisperCode = null,
            speechRecognizerSupported = false,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "co",
            japaneseName = "コルシカ語",
            englishName = "Corsican",
            defaultBcp47Tag = "co-FR",
            bcp47Candidates = listOf("co-FR", "co"),
            whisperCode = null,
            speechRecognizerSupported = false,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "cs",
            japaneseName = "チェコ語",
            englishName = "Czech",
            defaultBcp47Tag = "cs-CZ",
            bcp47Candidates = listOf("cs-CZ", "cs"),
            whisperCode = "cs",
        ),
        SupportedLanguageEntry(
            code = "cy",
            japaneseName = "ウェールズ語",
            englishName = "Welsh",
            defaultBcp47Tag = "cy-GB",
            bcp47Candidates = listOf("cy-GB", "cy"),
            whisperCode = "cy",
        ),
        SupportedLanguageEntry(
            code = "da",
            japaneseName = "デンマーク語",
            englishName = "Danish",
            defaultBcp47Tag = "da-DK",
            bcp47Candidates = listOf("da-DK", "da"),
            whisperCode = "da",
        ),
        SupportedLanguageEntry(
            code = "eo",
            japaneseName = "エスペラント語",
            englishName = "Esperanto",
            defaultBcp47Tag = "eo",
            bcp47Candidates = listOf("eo"),
            whisperCode = null,
            speechRecognizerSupported = false,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "et",
            japaneseName = "エストニア語",
            englishName = "Estonian",
            defaultBcp47Tag = "et-EE",
            bcp47Candidates = listOf("et-EE", "et"),
            whisperCode = "et",
        ),
        SupportedLanguageEntry(
            code = "eu",
            japaneseName = "バスク語",
            englishName = "Basque",
            defaultBcp47Tag = "eu-ES",
            bcp47Candidates = listOf("eu-ES", "eu"),
            whisperCode = "eu",
        ),
        SupportedLanguageEntry(
            code = "fa",
            japaneseName = "ペルシア語",
            englishName = "Persian",
            defaultBcp47Tag = "fa-IR",
            bcp47Candidates = listOf("fa-IR", "fa"),
            whisperCode = "fa",
        ),
        SupportedLanguageEntry(
            code = "fi",
            japaneseName = "フィンランド語",
            englishName = "Finnish",
            defaultBcp47Tag = "fi-FI",
            bcp47Candidates = listOf("fi-FI", "fi"),
            whisperCode = "fi",
        ),
        SupportedLanguageEntry(
            code = "fo",
            japaneseName = "フェロー語",
            englishName = "Faroese",
            defaultBcp47Tag = "fo-FO",
            bcp47Candidates = listOf("fo-FO", "fo"),
            whisperCode = "fo",
            speechRecognizerSupported = false,
            mlKitSupported = false,
        ),
        SupportedLanguageEntry(
            code = "fy",
            japaneseName = "フリジア語",
            englishName = "Western Frisian",
            defaultBcp47Tag = "fy-NL",
            bcp47Candidates = listOf("fy-NL", "fy"),
            whisperCode = null,
            speechRecognizerSupported = false,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "ga",
            japaneseName = "アイルランド語",
            englishName = "Irish",
            defaultBcp47Tag = "ga-IE",
            bcp47Candidates = listOf("ga-IE", "ga"),
            whisperCode = null,
            speechRecognizerSupported = false,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "gd",
            japaneseName = "スコットランド・ゲール語",
            englishName = "Scottish Gaelic",
            defaultBcp47Tag = "gd-GB",
            bcp47Candidates = listOf("gd-GB", "gd"),
            whisperCode = null,
            speechRecognizerSupported = false,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "gl",
            japaneseName = "ガリシア語",
            englishName = "Galician",
            defaultBcp47Tag = "gl-ES",
            bcp47Candidates = listOf("gl-ES", "gl"),
            whisperCode = "gl",
        ),
        SupportedLanguageEntry(
            code = "gu",
            japaneseName = "グジャラート語",
            englishName = "Gujarati",
            defaultBcp47Tag = "gu-IN",
            bcp47Candidates = listOf("gu-IN", "gu"),
            whisperCode = "gu",
        ),
        SupportedLanguageEntry(
            code = "ha",
            japaneseName = "ハウサ語",
            englishName = "Hausa",
            defaultBcp47Tag = "ha-NG",
            bcp47Candidates = listOf("ha-NG", "ha"),
            whisperCode = "ha",
        ),
        SupportedLanguageEntry(
            code = "haw",
            japaneseName = "ハワイ語",
            englishName = "Hawaiian",
            defaultBcp47Tag = "haw-US",
            bcp47Candidates = listOf("haw-US", "haw"),
            whisperCode = "haw",
        ),
        SupportedLanguageEntry(
            code = "he",
            japaneseName = "ヘブライ語",
            englishName = "Hebrew",
            defaultBcp47Tag = "he-IL",
            bcp47Candidates = listOf("he-IL", "iw-IL", "he", "iw"),
            whisperCode = "he",
        ),
        SupportedLanguageEntry(
            code = "hmn",
            japaneseName = "モン語",
            englishName = "Hmong",
            defaultBcp47Tag = "hmn",
            bcp47Candidates = listOf("hmn"),
            whisperCode = null,
            speechRecognizerSupported = false,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "hr",
            japaneseName = "クロアチア語",
            englishName = "Croatian",
            defaultBcp47Tag = "hr-HR",
            bcp47Candidates = listOf("hr-HR", "hr"),
            whisperCode = "hr",
        ),
        SupportedLanguageEntry(
            code = "ht",
            japaneseName = "ハイチ語",
            englishName = "Haitian Creole",
            defaultBcp47Tag = "ht-HT",
            bcp47Candidates = listOf("ht-HT", "ht"),
            whisperCode = "ht",
        ),
        SupportedLanguageEntry(
            code = "hu",
            japaneseName = "ハンガリー語",
            englishName = "Hungarian",
            defaultBcp47Tag = "hu-HU",
            bcp47Candidates = listOf("hu-HU", "hu"),
            whisperCode = "hu",
        ),
        SupportedLanguageEntry(
            code = "hy",
            japaneseName = "アルメニア語",
            englishName = "Armenian",
            defaultBcp47Tag = "hy-AM",
            bcp47Candidates = listOf("hy-AM", "hy"),
            whisperCode = "hy",
        ),
        SupportedLanguageEntry(
            code = "ig",
            japaneseName = "イボ語",
            englishName = "Igbo",
            defaultBcp47Tag = "ig-NG",
            bcp47Candidates = listOf("ig-NG", "ig"),
            whisperCode = null,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "is",
            japaneseName = "アイスランド語",
            englishName = "Icelandic",
            defaultBcp47Tag = "is-IS",
            bcp47Candidates = listOf("is-IS", "is"),
            whisperCode = "is",
        ),
        SupportedLanguageEntry(
            code = "jw",
            japaneseName = "ジャワ語",
            englishName = "Javanese",
            defaultBcp47Tag = "jv-ID",
            bcp47Candidates = listOf("jv-ID", "jw-ID", "jv", "jw"),
            whisperCode = "jw",
        ),
        SupportedLanguageEntry(
            code = "ka",
            japaneseName = "ジョージア語",
            englishName = "Georgian",
            defaultBcp47Tag = "ka-GE",
            bcp47Candidates = listOf("ka-GE", "ka"),
            whisperCode = "ka",
        ),
        SupportedLanguageEntry(
            code = "kk",
            japaneseName = "カザフ語",
            englishName = "Kazakh",
            defaultBcp47Tag = "kk-KZ",
            bcp47Candidates = listOf("kk-KZ", "kk"),
            whisperCode = "kk",
        ),
        SupportedLanguageEntry(
            code = "km",
            japaneseName = "クメール語",
            englishName = "Khmer",
            defaultBcp47Tag = "km-KH",
            bcp47Candidates = listOf("km-KH", "km"),
            whisperCode = "km",
        ),
        SupportedLanguageEntry(
            code = "kn",
            japaneseName = "カンナダ語",
            englishName = "Kannada",
            defaultBcp47Tag = "kn-IN",
            bcp47Candidates = listOf("kn-IN", "kn"),
            whisperCode = "kn",
        ),
        SupportedLanguageEntry(
            code = "ku",
            japaneseName = "クルド語",
            englishName = " Kurdish",
            defaultBcp47Tag = "ku-TR",
            bcp47Candidates = listOf("ku-TR", "ku"),
            whisperCode = null,
            speechRecognizerSupported = false,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "ky",
            japaneseName = "キルギス語",
            englishName = "Kyrgyz",
            defaultBcp47Tag = "ky-KG",
            bcp47Candidates = listOf("ky-KG", "ky"),
            whisperCode = null,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "la",
            japaneseName = "ラテン語",
            englishName = "Latin",
            defaultBcp47Tag = "la",
            bcp47Candidates = listOf("la", "la-VA"),
            whisperCode = "la",
            speechRecognizerSupported = false,
        ),
        SupportedLanguageEntry(
            code = "lb",
            japaneseName = "ルクセンブルク語",
            englishName = "Luxembourgish",
            defaultBcp47Tag = "lb-LU",
            bcp47Candidates = listOf("lb-LU", "lb"),
            whisperCode = "lb",
        ),
        SupportedLanguageEntry(
            code = "ln",
            japaneseName = "リンガラ語",
            englishName = "Lingala",
            defaultBcp47Tag = "ln-CD",
            bcp47Candidates = listOf("ln-CD", "ln"),
            whisperCode = "ln",
            speechRecognizerSupported = false,
            mlKitSupported = false,
        ),
        SupportedLanguageEntry(
            code = "lo",
            japaneseName = "ラオス語",
            englishName = "Lao",
            defaultBcp47Tag = "lo-LA",
            bcp47Candidates = listOf("lo-LA", "lo"),
            whisperCode = "lo",
        ),
        SupportedLanguageEntry(
            code = "lt",
            japaneseName = "リトアニア語",
            englishName = "Lithuanian",
            defaultBcp47Tag = "lt-LT",
            bcp47Candidates = listOf("lt-LT", "lt"),
            whisperCode = "lt",
        ),
        SupportedLanguageEntry(
            code = "lv",
            japaneseName = "ラトビア語",
            englishName = "Latvian",
            defaultBcp47Tag = "lv-LV",
            bcp47Candidates = listOf("lv-LV", "lv"),
            whisperCode = "lv",
        ),
        SupportedLanguageEntry(
            code = "mg",
            japaneseName = "マダガスカル語",
            englishName = "Malagasy",
            defaultBcp47Tag = "mg-MG",
            bcp47Candidates = listOf("mg-MG", "mg"),
            whisperCode = "mg",
        ),
        SupportedLanguageEntry(
            code = "mi",
            japaneseName = "マオリ語",
            englishName = "Maori",
            defaultBcp47Tag = "mi-NZ",
            bcp47Candidates = listOf("mi-NZ", "mi"),
            whisperCode = "mi",
        ),
        SupportedLanguageEntry(
            code = "mk",
            japaneseName = "マケドニア語",
            englishName = "Macedonian",
            defaultBcp47Tag = "mk-MK",
            bcp47Candidates = listOf("mk-MK", "mk"),
            whisperCode = "mk",
        ),
        SupportedLanguageEntry(
            code = "ml",
            japaneseName = "マラヤーラム語",
            englishName = "Malayalam",
            defaultBcp47Tag = "ml-IN",
            bcp47Candidates = listOf("ml-IN", "ml"),
            whisperCode = "ml",
        ),
        SupportedLanguageEntry(
            code = "mn",
            japaneseName = "モンゴル語",
            englishName = "Mongolian",
            defaultBcp47Tag = "mn-MN",
            bcp47Candidates = listOf("mn-MN", "mn"),
            whisperCode = "mn",
        ),
        SupportedLanguageEntry(
            code = "mr",
            japaneseName = "マラーティー語",
            englishName = "Marathi",
            defaultBcp47Tag = "mr-IN",
            bcp47Candidates = listOf("mr-IN", "mr"),
            whisperCode = "mr",
        ),
        SupportedLanguageEntry(
            code = "ms",
            japaneseName = "マレー語",
            englishName = "Malay",
            defaultBcp47Tag = "ms-MY",
            bcp47Candidates = listOf("ms-MY", "ms"),
            whisperCode = "ms",
        ),
        SupportedLanguageEntry(
            code = "mt",
            japaneseName = "マルタ語",
            englishName = "Maltese",
            defaultBcp47Tag = "mt-MT",
            bcp47Candidates = listOf("mt-MT", "mt"),
            whisperCode = "mt",
        ),
        SupportedLanguageEntry(
            code = "my",
            japaneseName = "ビルマ語",
            englishName = "Myanmar (Burmese)",
            defaultBcp47Tag = "my-MM",
            bcp47Candidates = listOf("my-MM", "my"),
            whisperCode = "my",
        ),
        SupportedLanguageEntry(
            code = "ne",
            japaneseName = "ネパール語",
            englishName = "Nepali",
            defaultBcp47Tag = "ne-NP",
            bcp47Candidates = listOf("ne-NP", "ne"),
            whisperCode = "ne",
        ),
        SupportedLanguageEntry(
            code = "nn",
            japaneseName = "ノルウェー語（ニーノシュク）",
            englishName = "Norwegian Nynorsk",
            defaultBcp47Tag = "nn-NO",
            bcp47Candidates = listOf("nn-NO", "nn", "no-NO"),
            whisperCode = "nn",
            mlKitSupported = false,
        ),
        SupportedLanguageEntry(
            code = "no",
            japaneseName = "ノルウェー語",
            englishName = "Norwegian",
            defaultBcp47Tag = "nb-NO",
            bcp47Candidates = listOf("nb-NO", "no-NO", "nb", "no"),
            whisperCode = "no",
        ),
        SupportedLanguageEntry(
            code = "ny",
            japaneseName = "チェワ語",
            englishName = "Chichewa",
            defaultBcp47Tag = "ny-MW",
            bcp47Candidates = listOf("ny-MW", "ny"),
            whisperCode = null,
            speechRecognizerSupported = false,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "oc",
            japaneseName = "オック語",
            englishName = "Occitan",
            defaultBcp47Tag = "oc-FR",
            bcp47Candidates = listOf("oc-FR", "oc"),
            whisperCode = "oc",
            speechRecognizerSupported = false,
            mlKitSupported = false,
        ),
        SupportedLanguageEntry(
            code = "or",
            japaneseName = "オリヤー語",
            englishName = "Odia",
            defaultBcp47Tag = "or-IN",
            bcp47Candidates = listOf("or-IN", "or"),
            whisperCode = null,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "pa",
            japaneseName = "パンジャーブ語",
            englishName = "Punjabi",
            defaultBcp47Tag = "pa-Guru-IN",
            bcp47Candidates = listOf("pa-Guru-IN", "pa-IN", "pa"),
            whisperCode = "pa",
        ),
        SupportedLanguageEntry(
            code = "ps",
            japaneseName = "パシュトー語",
            englishName = "Pashto",
            defaultBcp47Tag = "ps-AF",
            bcp47Candidates = listOf("ps-AF", "ps"),
            whisperCode = "ps",
        ),
        SupportedLanguageEntry(
            code = "ro",
            japaneseName = "ルーマニア語",
            englishName = "Romanian",
            defaultBcp47Tag = "ro-RO",
            bcp47Candidates = listOf("ro-RO", "ro"),
            whisperCode = "ro",
        ),
        SupportedLanguageEntry(
            code = "rw",
            japaneseName = "ルワンダ語",
            englishName = "Kinyarwanda",
            defaultBcp47Tag = "rw-RW",
            bcp47Candidates = listOf("rw-RW", "rw"),
            whisperCode = null,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "sa",
            japaneseName = "サンスクリット語",
            englishName = "Sanskrit",
            defaultBcp47Tag = "sa-IN",
            bcp47Candidates = listOf("sa-IN", "sa"),
            whisperCode = "sa",
            speechRecognizerSupported = false,
            mlKitSupported = false,
        ),
        SupportedLanguageEntry(
            code = "sd",
            japaneseName = "シンド語",
            englishName = "Sindhi",
            defaultBcp47Tag = "sd-PK",
            bcp47Candidates = listOf("sd-PK", "sd-IN", "sd"),
            whisperCode = "sd",
        ),
        SupportedLanguageEntry(
            code = "si",
            japaneseName = "シンハラ語",
            englishName = "Sinhala",
            defaultBcp47Tag = "si-LK",
            bcp47Candidates = listOf("si-LK", "si"),
            whisperCode = "si",
        ),
        SupportedLanguageEntry(
            code = "sk",
            japaneseName = "スロバキア語",
            englishName = "Slovak",
            defaultBcp47Tag = "sk-SK",
            bcp47Candidates = listOf("sk-SK", "sk"),
            whisperCode = "sk",
        ),
        SupportedLanguageEntry(
            code = "sl",
            japaneseName = "スロベニア語",
            englishName = "Slovenian",
            defaultBcp47Tag = "sl-SI",
            bcp47Candidates = listOf("sl-SI", "sl"),
            whisperCode = "sl",
        ),
        SupportedLanguageEntry(
            code = "sm",
            japaneseName = "サモア語",
            englishName = "Samoan",
            defaultBcp47Tag = "sm-WS",
            bcp47Candidates = listOf("sm-WS", "sm"),
            whisperCode = null,
            speechRecognizerSupported = false,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "sn",
            japaneseName = "ショナ語",
            englishName = "Shona",
            defaultBcp47Tag = "sn-ZW",
            bcp47Candidates = listOf("sn-ZW", "sn"),
            whisperCode = "sn",
        ),
        SupportedLanguageEntry(
            code = "so",
            japaneseName = "ソマリ語",
            englishName = "Somali",
            defaultBcp47Tag = "so-SO",
            bcp47Candidates = listOf("so-SO", "so"),
            whisperCode = "so",
        ),
        SupportedLanguageEntry(
            code = "sq",
            japaneseName = "アルバニア語",
            englishName = "Albanian",
            defaultBcp47Tag = "sq-AL",
            bcp47Candidates = listOf("sq-AL", "sq"),
            whisperCode = "sq",
        ),
        SupportedLanguageEntry(
            code = "sr",
            japaneseName = "セルビア語",
            englishName = "Serbian",
            defaultBcp47Tag = "sr-RS",
            bcp47Candidates = listOf("sr-RS", "sr"),
            whisperCode = "sr",
        ),
        SupportedLanguageEntry(
            code = "st",
            japaneseName = "ソト語",
            englishName = "Sesotho",
            defaultBcp47Tag = "st-ZA",
            bcp47Candidates = listOf("st-ZA", "st"),
            whisperCode = null,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "su",
            japaneseName = "スンダ語",
            englishName = "Sundanese",
            defaultBcp47Tag = "su-ID",
            bcp47Candidates = listOf("su-ID", "su"),
            whisperCode = "su",
        ),
        SupportedLanguageEntry(
            code = "sw",
            japaneseName = "スワヒリ語",
            englishName = "Swahili",
            defaultBcp47Tag = "sw-TZ",
            bcp47Candidates = listOf("sw-TZ", "sw-KE", "sw"),
            whisperCode = "sw",
        ),
        SupportedLanguageEntry(
            code = "ta",
            japaneseName = "タミル語",
            englishName = "Tamil",
            defaultBcp47Tag = "ta-IN",
            bcp47Candidates = listOf("ta-IN", "ta-SG", "ta-LK", "ta-MY", "ta"),
            whisperCode = "ta",
        ),
        SupportedLanguageEntry(
            code = "te",
            japaneseName = "テルグ語",
            englishName = "Telugu",
            defaultBcp47Tag = "te-IN",
            bcp47Candidates = listOf("te-IN", "te"),
            whisperCode = "te",
        ),
        SupportedLanguageEntry(
            code = "tg",
            japaneseName = "タジク語",
            englishName = "Tajik",
            defaultBcp47Tag = "tg-TJ",
            bcp47Candidates = listOf("tg-TJ", "tg"),
            whisperCode = "tg",
        ),
        SupportedLanguageEntry(
            code = "tk",
            japaneseName = "トルクメン語",
            englishName = "Turkmen",
            defaultBcp47Tag = "tk-TM",
            bcp47Candidates = listOf("tk-TM", "tk"),
            whisperCode = "tk",
        ),
        SupportedLanguageEntry(
            code = "tl",
            japaneseName = "タガログ語（フィリピン語）",
            englishName = "Tagalog (Filipino)",
            defaultBcp47Tag = "fil-PH",
            bcp47Candidates = listOf("fil-PH", "tl-PH", "fil", "tl"),
            whisperCode = "tl",
        ),
        SupportedLanguageEntry(
            code = "tt",
            japaneseName = "タタール語",
            englishName = "Tatar",
            defaultBcp47Tag = "tt-RU",
            bcp47Candidates = listOf("tt-RU", "tt"),
            whisperCode = "tt",
            speechRecognizerSupported = false,
            mlKitSupported = false,
        ),
        SupportedLanguageEntry(
            code = "ug",
            japaneseName = "ウイグル語",
            englishName = "Uyghur",
            defaultBcp47Tag = "ug-CN",
            bcp47Candidates = listOf("ug-CN", "ug"),
            whisperCode = null,
            speechRecognizerSupported = false,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "ur",
            japaneseName = "ウルドゥー語",
            englishName = "Urdu",
            defaultBcp47Tag = "ur-PK",
            bcp47Candidates = listOf("ur-PK", "ur-IN", "ur"),
            whisperCode = "ur",
        ),
        SupportedLanguageEntry(
            code = "uz",
            japaneseName = "ウズベク語",
            englishName = "Uzbek",
            defaultBcp47Tag = "uz-UZ",
            bcp47Candidates = listOf("uz-UZ", "uz"),
            whisperCode = "uz",
        ),
        SupportedLanguageEntry(
            code = "xh",
            japaneseName = "コサ語",
            englishName = "Xhosa",
            defaultBcp47Tag = "xh-ZA",
            bcp47Candidates = listOf("xh-ZA", "xh"),
            whisperCode = null,
            mlKitSupported = true,
        ),
        SupportedLanguageEntry(
            code = "yi",
            japaneseName = "イディッシュ語",
            englishName = "Yiddish",
            defaultBcp47Tag = "yi",
            bcp47Candidates = listOf("yi", "ji"),
            whisperCode = "yi",
            speechRecognizerSupported = false,
        ),
        SupportedLanguageEntry(
            code = "yo",
            japaneseName = "ヨルバ語",
            englishName = "Yoruba",
            defaultBcp47Tag = "yo-NG",
            bcp47Candidates = listOf("yo-NG", "yo"),
            whisperCode = "yo",
        ),
        SupportedLanguageEntry(
            code = "zu",
            japaneseName = "ズールー語",
            englishName = "Zulu",
            defaultBcp47Tag = "zu-ZA",
            bcp47Candidates = listOf("zu-ZA", "zu"),
            whisperCode = null,
            mlKitSupported = true,
        ),
    )

    private val entriesByCode: Map<String, SupportedLanguageEntry> =
        ALL_ENTRIES.associateBy { it.code.lowercase(Locale.ROOT) }

    /**
     * ファイル別設定・ライブラリ取り込み用の言語エントリ一覧（`zh` は `zh-cn` / `zh-tw` に分けてアルファベット順）。
     */
    val ITEM_LANGUAGE_ENTRIES: List<SupportedLanguageEntry> =
        ALL_ENTRIES
            .filter { it.code != "zh" }
            .sortedBy { it.code }

    /**
     * 全体設定および音声認識モデル管理画面で扱う対応言語タグの一覧。
     */
    val MODEL_MANAGEMENT_LANGUAGE_TAGS: List<LanguageTag> =
        ALL_ENTRIES
            .filter { it.code != "zh-cn" && it.code != "zh-tw" }
            .map { it.tag }

    /**
     * 言語コードから対応する [SupportedLanguageEntry] を返す。
     */
    fun findByCode(rawCode: String): SupportedLanguageEntry? {
        val cleaned = rawCode.trim().lowercase(Locale.ROOT).replace('_', '-')
        entriesByCode[cleaned]?.let { return it }
        val normalized = normalizeToCatalogCode(cleaned)
        return entriesByCode[normalized]
    }

    /**
     * 任意の BCP-47 タグや別名コードをカタログ内の代表言語コードへ正規化する。
     */
    fun normalizeToCatalogCode(rawTag: String): String {
        val cleaned = rawTag.trim().lowercase(Locale.ROOT).replace('_', '-')
        if (cleaned.isEmpty()) return ""
        return when {
            cleaned == "zh-cn" || cleaned == "zh-sg" || cleaned.startsWith("zh-hans") || cleaned.startsWith("cmn-hans") -> "zh-cn"

            cleaned == "zh-tw" || cleaned == "zh-hk" || cleaned == "zh-mo" || cleaned.startsWith("zh-hant") || cleaned.startsWith("cmn-hant") -> "zh-tw"

            cleaned == "zh" || cleaned.startsWith("cmn") -> "zh"

            cleaned.startsWith("yue") -> "yue"

            cleaned == "in" || cleaned.startsWith("in-") -> "id"

            cleaned == "iw" || cleaned.startsWith("iw-") -> "he"

            cleaned == "ji" || cleaned.startsWith("ji-") -> "yi"

            cleaned == "jv" || cleaned.startsWith("jv-") -> "jw"

            cleaned == "fil" || cleaned.startsWith("fil-") -> "tl"

            cleaned == "nb" || cleaned.startsWith("nb-") -> "no"

            entriesByCode.containsKey(cleaned) -> cleaned

            else -> {
                val primary = cleaned.substringBefore('-')
                when (primary) {
                    "in" -> "id"
                    "iw" -> "he"
                    "ji" -> "yi"
                    "jv" -> "jw"
                    "fil" -> "tl"
                    "nb" -> "no"
                    "cmn" -> "zh"
                    else -> if (entriesByCode.containsKey(primary)) primary else cleaned
                }
            }
        }
    }

    /**
     * 音声認識モデル管理画面の [LanguageTag] 単位（`zh-cn`/`zh-tw` を `zh` に統合）へ正規化する。
     */
    fun normalizeToModelManagementCode(rawTag: String): String {
        val code = normalizeToCatalogCode(rawTag)
        return when (code) {
            "zh-cn", "zh-tw" -> "zh"
            else -> code
        }
    }

    /**
     * 指定した言語コードの日本語表示名を返す（見つからない場合は `null`）。
     */
    fun japaneseDisplayName(rawCode: String): String? = findByCode(rawCode)?.japaneseName

    /**
     * 指定した言語コードの英語表示名を返す（見つからない場合は `null`）。
     */
    fun englishDisplayName(rawCode: String): String? = findByCode(rawCode)?.englishName

    /**
     * 指定した言語コードに対する代表 BCP-47 ロケールタグを返す。
     */
    fun toBcp47Tag(rawCode: String): String {
        val cleaned = rawCode.trim()
        if (cleaned.isEmpty()) return "en-US"
        findByCode(cleaned)?.let { return it.defaultBcp47Tag }
        if ('-' in cleaned || '_' in cleaned) {
            return cleaned.replace('_', '-')
        }
        return cleaned.lowercase(Locale.ROOT)
    }

    /**
     * 指定した言語コードに対する BCP-47 ロケールタグ候補リストを返す。
     */
    fun toBcp47CandidateTags(rawCode: String): List<String> {
        val cleaned = rawCode.trim()
        if (cleaned.isEmpty()) return listOf("en-US", "en")
        findByCode(cleaned)?.let { return it.bcp47Candidates }
        val primary = toBcp47Tag(cleaned)
        val normalized = cleaned.replace('_', '-')
        return listOf(primary, normalized).distinct()
    }
}
