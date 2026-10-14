package com.miuraken.chunkflow.core.testing.fixture

import com.miuraken.chunkflow.core.model.AnalysisError
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.WordTiming
import com.miuraken.chunkflow.core.testing.fixture.FixtureBuilder.audioItem
import com.miuraken.chunkflow.core.testing.fixture.FixtureBuilder.material

/**
 * 設計書 §10.5.1 の「確認する状態の一覧」に対応する教材のフィクスチャ。
 *
 * 文はすべてこのリポジトリのために作った短い例文で、実際の教材からは取っていない（著作権のため）。
 * 中国語（`zh`）の本文は簡体字で `Hant` の変種を、広東語（`yue`）の本文は香港式の繁体字の口語で `Hans` の変種を持つ（§7.4.3）。
 */
object SampleMaterials {
    /** 日本語で学ぶ韓国語（講座型: 解説とダイアログが交互）。 */
    val jaKo: SampleMaterial = material(
        key = "ja-ko",
        item = audioItem(1, "韓国語講座 第1課", target = "ko", base = "ja", durationMs = 30_000, course = "korean-course"),
        lines = listOf(
            SampleLine(1_200, 3_400, "ja", "では、聞いてみましょう。"),
            SampleLine(4_100, 6_900, "ko", "안녕하세요. 만나서 반갑습니다."),
            SampleLine(7_800, 9_600, "ja", "もう一度。"),
            SampleLine(10_300, 13_100, "ko", "이건 얼마예요?"),
            SampleLine(14_000, 17_500, "ko", "저는 한국어를 공부하고 있어요."),
        ),
    )

    /** 日本語で学ぶ中国語（簡体字の本文 + 繁体字の変種、単語の時刻あり）。 */
    val jaZh: SampleMaterial = material(
        key = "ja-zh",
        item = audioItem(2, "中国語 Lesson 1", target = "zh", base = "ja", durationMs = 30_000, course = "chinese-course"),
        lines = listOf(
            SampleLine(1_000, 2_700, "ja", "では、聞いてみましょう。"),
            SampleLine(
                3_200,
                5_470,
                "zh",
                "你们好，欢迎来到北京。",
                variants = mapOf("Hant" to "你們好，歡迎來到北京。"),
                words = listOf(
                    WordTiming(3_200, 3_980, "你们好，", 0.95f),
                    WordTiming(4_110, 4_620, "欢迎", 0.93f),
                    WordTiming(4_690, 5_470, "来到北京。", 0.96f),
                ),
            ),
            SampleLine(6_300, 8_100, "zh", "这个多少钱？", variants = mapOf("Hant" to "這個多少錢？")),
            SampleLine(9_000, 11_200, "ja", "「これはいくらですか」という意味です。"),
        ),
    )

    /** 英語で学ぶ広東語（香港式の繁体字の口語 + 簡体字の変種）。 */
    val enYue: SampleMaterial = material(
        key = "en-yue",
        item = audioItem(3, "Cantonese Unit 1", target = "yue", base = "en", durationMs = 30_000, course = "cantonese-course"),
        lines = listOf(
            SampleLine(900, 2_800, "en", "Let's listen to the dialogue."),
            SampleLine(3_500, 5_600, "yue", "你食咗飯未呀？", variants = mapOf("Hans" to "你食咗饭未呀？")),
            SampleLine(6_200, 8_400, "yue", "佢哋喺度傾緊偈。", variants = mapOf("Hans" to "佢哋喺度倾紧偈。")),
            SampleLine(9_100, 10_900, "yue", "呢度係邊度？", variants = mapOf("Hans" to "呢度系边度？")),
            SampleLine(11_600, 13_800, "en", "Where is this place?"),
        ),
    )

    /** 英語で学ぶヒンディー語（デーヴァナーガリー）。 */
    val enHi: SampleMaterial = material(
        key = "en-hi",
        item = audioItem(4, "Hindi Basics 1", target = "hi", base = "en", durationMs = 20_000, course = "hindi-course"),
        lines = listOf(
            SampleLine(800, 2_600, "en", "Repeat after me."),
            SampleLine(3_300, 5_400, "hi", "नमस्ते, आप कैसे हैं?"),
            SampleLine(6_100, 8_700, "hi", "मुझे हिंदी सीखना पसंद है।"),
        ),
    )

    /** 英語で学ぶロシア語（キリル文字）。 */
    val enRu: SampleMaterial = material(
        key = "en-ru",
        item = audioItem(5, "Russian Step 1", target = "ru", base = "en", durationMs = 20_000, course = "russian-course"),
        lines = listOf(
            SampleLine(700, 2_400, "en", "Listen and repeat."),
            SampleLine(3_000, 4_900, "ru", "Здравствуйте! Как дела?"),
            SampleLine(5_600, 7_800, "ru", "Я изучаю русский язык."),
        ),
    )

    /** 英語で学ぶベトナム語（声調記号。NFC に正規化した表記）。 */
    val enVi: SampleMaterial = material(
        key = "en-vi",
        item = audioItem(6, "Vietnamese 101", target = "vi", base = "en", durationMs = 20_000, course = "vietnamese-course"),
        lines = listOf(
            SampleLine(600, 2_300, "en", "How do you greet someone?"),
            SampleLine(3_000, 4_800, "vi", "Xin chào, bạn khỏe không?"),
            SampleLine(5_500, 7_600, "vi", "Tôi đang học tiếng Việt."),
        ),
    )

    /** 英語で学ぶギリシャ語（ギリシャ文字）。 */
    val enEl: SampleMaterial = material(
        key = "en-el",
        item = audioItem(7, "Greek Lesson 1", target = "el", base = "en", durationMs = 20_000, course = "greek-course"),
        lines = listOf(
            SampleLine(700, 2_200, "en", "Good morning in Greek."),
            SampleLine(2_900, 4_600, "el", "Καλημέρα! Τι κάνεις;"),
        ),
    )

    /** 単一言語（スペイン語だけ。言語ペアの base が `null`）。 */
    val singleLanguage: SampleMaterial = material(
        key = "es-only",
        item = audioItem(8, "Podcast en español", target = "es", base = null, durationMs = 20_000),
        lines = listOf(
            SampleLine(500, 2_600, "es", "¿Dónde está la estación?"),
            SampleLine(3_200, 5_800, "es", "Está a la derecha, al lado del banco."),
        ),
    )

    /** 長い文（折り返しの確認用）。 */
    val longSentence: SampleMaterial = material(
        key = "long-sentence",
        item = audioItem(9, "とても長いタイトルの教材：韓国語で日常会話を学ぶための総合的なリスニング練習 第12課", target = "ko", base = "ja", durationMs = 40_000, course = "korean-course"),
        lines = listOf(
            SampleLine(
                1_000,
                14_000,
                "ko",
                "저는 지난 주말에 친구들과 함께 서울 근처의 작은 마을에 가서 맛있는 음식을 먹고 사진도 많이 찍으면서 아주 즐거운 시간을 보냈는데, 다음에는 가족들과 함께 다시 가 보고 싶어요.",
            ),
            SampleLine(
                15_000,
                27_000,
                "ja",
                "先週末、友達と一緒にソウルの近くの小さな村に行って、おいしいものを食べたり写真をたくさん撮ったりしてとても楽しい時間を過ごしましたが、今度は家族と一緒にまた行ってみたいです、という意味です。",
            ),
        ),
    )

    /** 言語を判定できなかったチャンク（`language == null`）を含む教材。 */
    val unknownLanguage: SampleMaterial = material(
        key = "unknown-language",
        item = audioItem(10, "韓国語講座 第2課", target = "ko", base = "ja", durationMs = 20_000, course = "korean-course"),
        lines = listOf(
            SampleLine(800, 2_600, "ja", "次の会話を聞いてください。"),
            SampleLine(3_200, 3_700, null, "OK"),
            SampleLine(4_300, 6_500, "ko", "네, 알겠습니다."),
        ),
    )

    /** 文字起こしなし（端末内の自動分割のチャンクだけ）。 */
    val noTranscript: SampleMaterial = material(
        key = "no-transcript",
        item = audioItem(11, "Unit 3 (no transcript)", target = "ko", base = "ja", durationMs = 20_000),
        lines = listOf(
            SampleLine(700, 2_900, null, ""),
            SampleLine(3_600, 6_100, null, ""),
            SampleLine(6_900, 9_400, null, ""),
        ),
        withTranscripts = false,
    )

    /** 解析中（波形の計算が 40%、チャンクはまだない。取り込み直後で再生はできる）。 */
    val analyzing: SampleMaterial = material(
        key = "analyzing",
        item = audioItem(12, "Unit 4 (analyzing)", target = "ko", base = "ja", durationMs = 20_000),
        lines = emptyList(),
        withTranscripts = false,
        withWaveform = false,
        analysis = mapOf(
            AnalysisStage.FEATURES to StageStatus.Running(0.4f),
            AnalysisStage.SEGMENTATION to StageStatus.NotStarted,
        ),
    )

    /** 解析中（進捗が不明）。 */
    val analyzingIndeterminate: SampleMaterial = analyzing.copy(
        key = "analyzing-indeterminate",
        analysis = mapOf(
            AnalysisStage.FEATURES to StageStatus.Running(null),
            AnalysisStage.SEGMENTATION to StageStatus.NotStarted,
        ),
    )

    /** 解析のエラー（デコードに失敗）。 */
    val analysisFailed: SampleMaterial = material(
        key = "analysis-failed",
        item = audioItem(13, "broken-file", target = "ko", base = "ja", durationMs = 20_000),
        lines = emptyList(),
        withTranscripts = false,
        withWaveform = false,
        analysis = mapOf(
            AnalysisStage.FEATURES to StageStatus.Failed(AnalysisError.DECODE_FAILED),
            AnalysisStage.SEGMENTATION to StageStatus.NotStarted,
        ),
    )

    /** 言語ペアごとの文字起こしのある教材。 */
    val languagePairs: List<SampleMaterial> = listOf(jaKo, jaZh, enYue, enHi, enRu, enVi, enEl, singleLanguage)

    /** すべての教材のフィクスチャ。 */
    val all: List<SampleMaterial> = languagePairs + listOf(
        longSentence,
        unknownLanguage,
        noTranscript,
        analyzing,
        analyzingIndeterminate,
        analysisFailed,
    )
}
