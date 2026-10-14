"""漢字の字体の変換（OpenCC）のテスト。"""

import unicodedata

import pytest

from chunkflow_transcriber.chinese import needs_conversion, normalize_main, script_forms


@pytest.mark.parametrize(
    "text", ["你們好，歡迎來到北京。", "你们好，欢迎来到北京。", "你們好，欢迎來到北京。"]
)
def test_mandarin_main_is_simplified_and_variant_is_traditional(text: str) -> None:
    f = script_forms(text, "zh")
    assert f.main == "你们好，欢迎来到北京。"
    assert f.variants == {"Hant": "你們好，歡迎來到北京。"}


@pytest.mark.parametrize("text", ["佢喺邊度做嘢？", "佢喺边度做嘢？"])
def test_cantonese_main_is_hk_traditional_and_variant_is_simplified(text: str) -> None:
    f = script_forms(text, "yue")
    assert f.main == "佢喺邊度做嘢？"
    assert f.variants == {"Hans": "佢喺边度做嘢？"}


def test_cantonese_colloquial_characters_are_kept() -> None:
    f = script_forms("我哋聽日一齊去飲茶啦。", "yue")
    assert f.main == "我哋聽日一齊去飲茶啦。"
    assert f.variants == {"Hans": "我哋听日一齐去饮茶啦。"}


def test_cantonese_hk_variant_characters() -> None:
    # s2hk は香港式の字形にする（里 → 裏）
    assert script_forms("里面", "yue").main == "裏面"


def test_no_variant_without_convertible_characters() -> None:
    assert script_forms("Hello", "zh").variants == {}
    assert script_forms("好", "zh").variants == {}


@pytest.mark.parametrize("lang", ["ja", "ko", "en", None])
def test_other_languages_are_untouched(lang: str | None) -> None:
    f = script_forms("國語の勉強", lang)
    assert f.main == "國語の勉強"
    assert f.variants == {}
    assert normalize_main("國語", lang) == "國語"
    assert not needs_conversion(lang)


def test_words_use_same_conversion_as_text() -> None:
    assert normalize_main("歡迎", "zh") == "欢迎"
    assert normalize_main("边度", "yue") == "邊度"


def test_output_is_nfc() -> None:
    f = script_forms("北京", "zh")
    assert f.main == unicodedata.normalize("NFC", f.main)
