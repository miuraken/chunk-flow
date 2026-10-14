"""文字体系の確認のテスト。"""

import unicodedata

import pytest

from chunkflow_transcriber.script_check import Script, check_script, script_of


@pytest.mark.parametrize(
    ("text", "lang"),
    [
        ("안녕하세요. 저는 김민수입니다.", "ko"),
        ("それでは、聞いてみましょう。", "ja"),
        ("第一課。", "ja"),  # 漢字だけの日本語も合格
        ("カタカナー", "ja"),
        ("请问，地铁站怎么走？", "zh"),
        ("請問，地鐵站怎麼走？", "zh"),
        ("佢喺邊度做嘢㗎？", "yue"),
        ("नमस्ते, आप कैसे हैं?", "hi"),
        ("Сегодня холодно и идёт снег.", "ru"),
        ("Καλημέρα σας.", "el"),
        ("Cái áo này bao nhiêu tiền?", "vi"),
        ("Hello, how are you?", "en"),
        ("123 !?", "ja"),  # 文字がなければ判断できないので合格
        ("", "ko"),
    ],
)
def test_matching_script(text: str, lang: str) -> None:
    assert check_script(text, lang).ok


@pytest.mark.parametrize(
    ("text", "lang"),
    [
        ("こんにちは、私はキンミンスです。", "ko"),  # 韓国語の音声を日本語に訳した
        ("안녕하세요.", "ja"),
        ("それは何ですか", "zh"),  # かなが混ざった中国語
        ("آپ کیسے ہیں؟", "hi"),  # ウルドゥー文字で書かれたヒンディー語
        ("aap kaise hain", "hi"),  # ローマ字で書かれたヒンディー語
        ("你好", "en"),
        ("Hello", "ru"),
        ("OK", "ja"),
    ],
)
def test_mismatching_script(text: str, lang: str) -> None:
    assert not check_script(text, lang).ok


def test_latin_letters_are_ignored_in_cjk_text_with_native_letters() -> None:
    # 略語・外来語のラテン文字は、その言語の文字があれば数えない（「Tシャツ」「CD」）
    assert check_script("Tシャツ", "ja").ok
    assert check_script("これはCDプレーヤーとラジオです", "ja").ratio == 1.0
    assert check_script("저는 DVD를 봐요", "ko").ok
    assert check_script("我有一台iPhone", "zh").ok
    # ラテン文字だけなら不合格のまま
    assert not check_script("T", "ko").ok
    assert not check_script("Thank you", "zh").ok


def test_ratio_threshold_allows_some_foreign_letters() -> None:
    # ハングルが少し混ざる程度は合格（割合 0.8 以上）
    r = check_script("これは日本語の文章で、한が少しだけ混ざっています", "ja")
    assert r.ok
    assert 0.8 <= r.ratio < 1.0


def test_vietnamese_decomposed_is_normalized() -> None:
    nfd = unicodedata.normalize("NFD", "Tiếng Việt")
    r = check_script(nfd, "vi")
    assert r.ok
    assert r.ratio == 1.0


def test_script_of() -> None:
    assert script_of("한") == Script.HANGUL
    assert script_of("あ") == Script.KANA
    assert script_of("ー") == Script.KANA
    assert script_of("漢") == Script.HAN
    assert script_of("々") == Script.HAN
    assert script_of("ж") == Script.CYRILLIC
    assert script_of("क") == Script.DEVANAGARI
    assert script_of("ệ") == Script.LATIN
    assert script_of("1") is None
    assert script_of("。") is None
    assert script_of(" ") is None
