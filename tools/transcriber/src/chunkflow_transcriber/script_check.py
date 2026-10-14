"""文字体系の確認（§7.4.4 の手順 5）。

テキストの文字が、その言語で使う文字体系と合っているかを確かめる。合わない場合は、言語の誤判定や
もう一方の言語への翻訳が起きた疑いがある。判定は文字（Unicode の L* と、かなの長音記号など）だけを
数え、数字・記号・空白は数えない。
"""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass
from enum import StrEnum


class Script(StrEnum):
    """判定に使う文字体系。"""

    HANGUL = "Hang"
    KANA = "Kana"  # ひらがな・カタカナ
    HAN = "Hani"
    LATIN = "Latn"
    CYRILLIC = "Cyrl"
    GREEK = "Grek"
    DEVANAGARI = "Deva"
    ARABIC = "Arab"
    THAI = "Thai"
    HEBREW = "Hebr"
    OTHER = "Zyyy"


# (開始, 終了, 文字体系)。開始の昇順。
_RANGES: tuple[tuple[int, int, Script], ...] = (
    (0x0041, 0x005A, Script.LATIN),
    (0x0061, 0x007A, Script.LATIN),
    (0x00C0, 0x024F, Script.LATIN),
    (0x0370, 0x03FF, Script.GREEK),
    (0x0400, 0x052F, Script.CYRILLIC),
    (0x0590, 0x05FF, Script.HEBREW),
    (0x0600, 0x06FF, Script.ARABIC),
    (0x0750, 0x077F, Script.ARABIC),
    (0x0900, 0x097F, Script.DEVANAGARI),
    (0x0E00, 0x0E7F, Script.THAI),
    (0x1100, 0x11FF, Script.HANGUL),
    (0x1E00, 0x1EFF, Script.LATIN),  # ベトナム語の合成済みの文字を含む
    (0x1F00, 0x1FFF, Script.GREEK),
    (0x3005, 0x3007, Script.HAN),  # 々 〆 〇
    (0x3040, 0x309F, Script.KANA),
    (0x30A0, 0x30FF, Script.KANA),
    (0x3130, 0x318F, Script.HANGUL),
    (0x31F0, 0x31FF, Script.KANA),
    (0x3400, 0x4DBF, Script.HAN),
    (0x4E00, 0x9FFF, Script.HAN),
    (0xA8E0, 0xA8FF, Script.DEVANAGARI),
    (0xA960, 0xA97F, Script.HANGUL),
    (0xAC00, 0xD7AF, Script.HANGUL),
    (0xD7B0, 0xD7FF, Script.HANGUL),
    (0xF900, 0xFAFF, Script.HAN),
    (0xFB50, 0xFDFF, Script.ARABIC),
    (0xFE70, 0xFEFF, Script.ARABIC),
    (0xFF21, 0xFF3A, Script.LATIN),
    (0xFF41, 0xFF5A, Script.LATIN),
    (0xFF66, 0xFF9F, Script.KANA),
    (0x20000, 0x3134F, Script.HAN),
)

# 言語ごとに使う文字体系。ここにない言語はラテン文字とみなす。
_EXPECTED: dict[str, frozenset[Script]] = {
    "ja": frozenset({Script.KANA, Script.HAN}),
    "ko": frozenset({Script.HANGUL}),
    "zh": frozenset({Script.HAN}),
    "yue": frozenset({Script.HAN}),
    "ru": frozenset({Script.CYRILLIC}),
    "uk": frozenset({Script.CYRILLIC}),
    "be": frozenset({Script.CYRILLIC}),
    "bg": frozenset({Script.CYRILLIC}),
    "sr": frozenset({Script.CYRILLIC, Script.LATIN}),
    "mk": frozenset({Script.CYRILLIC}),
    "kk": frozenset({Script.CYRILLIC}),
    "mn": frozenset({Script.CYRILLIC}),
    "el": frozenset({Script.GREEK}),
    "hi": frozenset({Script.DEVANAGARI}),
    "mr": frozenset({Script.DEVANAGARI}),
    "ne": frozenset({Script.DEVANAGARI}),
    "sa": frozenset({Script.DEVANAGARI}),
    "ar": frozenset({Script.ARABIC}),
    "fa": frozenset({Script.ARABIC}),
    "ur": frozenset({Script.ARABIC}),
    "ps": frozenset({Script.ARABIC}),
    "he": frozenset({Script.HEBREW}),
    "yi": frozenset({Script.HEBREW}),
    "th": frozenset({Script.THAI}),
}

# 「合っている」とみなす、期待する文字体系の文字の割合の下限。
DEFAULT_MIN_RATIO = 0.8


def script_of(ch: str) -> Script | None:
    """1 文字の文字体系を返す。文字（L*）やかなの長音記号でなければ None（数えない）。"""
    cp = ord(ch)
    cat = unicodedata.category(ch)
    if not (cat.startswith("L") or cat == "Mn" or ch in "ー々〆〇"):
        return None
    if cat == "Mn":
        # 結合文字（デーヴァナーガリーの母音記号など）は直前の文字に属するので数えない
        return None
    lo, hi = 0, len(_RANGES) - 1
    while lo <= hi:
        mid = (lo + hi) // 2
        start, end, script = _RANGES[mid]
        if cp < start:
            hi = mid - 1
        elif cp > end:
            lo = mid + 1
        else:
            return script
    return Script.OTHER


def expected_scripts(lang: str) -> frozenset[Script]:
    """言語で使う文字体系の集合を返す。"""
    return _EXPECTED.get(lang, frozenset({Script.LATIN}))


@dataclass(frozen=True)
class ScriptCheck:
    """文字体系の確認の結果。

    - `letters`: 数えた文字の数
    - `ratio`: そのうち期待する文字体系の文字の割合（文字がなければ 1.0）
    - `ok`: 割合が下限以上か（文字がなければ True。判断できないので再認識しない）
    """

    lang: str
    letters: int
    ratio: float
    ok: bool
    counts: dict[str, int]


def check_script(text: str, lang: str, min_ratio: float = DEFAULT_MIN_RATIO) -> ScriptCheck:
    """テキストが言語の文字体系と合っているかを確かめる。

    日本語は漢字だけでも合格にする（漢字だけの短い文がある）。中国語・広東語の文にかなが
    混ざれば不合格になる。

    ラテン文字を使わない言語では、期待する文字体系の文字が 1 つでもあれば、ラテン文字は数えない
    （S1 の実際の教材: 「Tシャツ」のような略語・外来語のラテン文字で不一致になり、誤って再認識した）。
    ラテン文字だけのテキスト（訳して英語で書いたなど）は、これまでどおり不合格になる。
    """
    text = unicodedata.normalize("NFC", text)
    counts: dict[str, int] = {}
    for ch in text:
        s = script_of(ch)
        if s is None:
            continue
        counts[s.value] = counts.get(s.value, 0) + 1
    letters = sum(counts.values())
    if letters == 0:
        return ScriptCheck(lang=lang, letters=0, ratio=1.0, ok=True, counts=counts)
    expected = expected_scripts(lang)
    good = sum(n for s, n in counts.items() if Script(s) in expected)
    if Script.LATIN not in expected and good > 0:
        letters -= counts.get(Script.LATIN.value, 0)
    ratio = good / letters
    return ScriptCheck(
        lang=lang, letters=letters, ratio=ratio, ok=ratio >= min_ratio, counts=counts
    )
