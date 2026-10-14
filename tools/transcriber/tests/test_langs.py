"""言語タグの変換と、言語ペアへの絞り込みのテスト。"""

import pytest

from chunkflow_transcriber.langs import (
    UnknownLanguageError,
    restrict_to_pair,
    to_bcp47,
    to_whisper_code,
    top_languages,
)


@pytest.mark.parametrize(
    ("tag", "code"),
    [
        ("ko", "ko"),
        ("ja-JP", "ja"),
        ("JA_jp", "ja"),
        ("zh", "zh"),
        ("zh-CN", "zh"),
        ("zh-Hans", "zh"),
        ("zh-TW", "zh"),
        ("zh-Hant", "zh"),
        ("cmn", "zh"),
        ("zh-HK", "yue"),
        ("yue", "yue"),
        ("yue-Hant-HK", "yue"),
        ("hi-IN", "hi"),
        ("en-US", "en"),
        ("vi", "vi"),
        ("iw", "he"),
        ("jv", "jw"),
    ],
)
def test_to_whisper_code(tag: str, code: str) -> None:
    assert to_whisper_code(tag) == code


@pytest.mark.parametrize("tag", ["", "  ", "xx", "klingon", "tlh"])
def test_to_whisper_code_rejects_unknown(tag: str) -> None:
    with pytest.raises(UnknownLanguageError):
        to_whisper_code(tag)


def test_to_bcp47() -> None:
    assert to_bcp47("yue") == "yue"
    assert to_bcp47("zh") == "zh"
    assert to_bcp47("jw") == "jv"
    with pytest.raises(UnknownLanguageError):
        to_bcp47("zz")


def test_restrict_to_pair_normalizes_two_languages() -> None:
    probs = {"ko": 0.6, "ja": 0.2, "en": 0.15, "zh": 0.05}
    lang, p, norm = restrict_to_pair(probs, ("ko", "ja"))
    assert lang == "ko"
    assert p == pytest.approx(0.75)
    assert norm == pytest.approx({"ko": 0.75, "ja": 0.25})


def test_restrict_to_pair_ignores_languages_outside_pair() -> None:
    # 全言語では en が最大でも、候補は言語ペアに限る（§7.4.5）
    probs = {"en": 0.9, "zh": 0.02, "yue": 0.08}
    lang, p, _ = restrict_to_pair(probs, ("yue", "zh"))
    assert lang == "yue"
    assert p == pytest.approx(0.8)


def test_restrict_to_pair_without_probability() -> None:
    lang, p, norm = restrict_to_pair({"en": 1.0}, ("ko", "ja"))
    assert lang is None
    assert p == 0.0
    assert norm == {"ko": 0.5, "ja": 0.5}


def test_top_languages_weights_by_duration() -> None:
    probs = [
        ({"ja": 0.9, "ko": 0.1}, 3000),
        ({"ko": 0.8, "ja": 0.2}, 5000),
        ({"en": 1.0}, 100),
    ]
    assert top_languages(probs, 2) == ["ko", "ja"]
