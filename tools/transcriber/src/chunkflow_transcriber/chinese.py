"""漢字の字体の変換（§7.4.4 の手順 6、OpenCC）。

- 中国語（`zh`）: `t2s` で簡体字に揃えて `text` にし、`s2t` で繁体字を作って `textVariants.Hant` に入れる。
- 広東語（`yue`）: `s2hk` で香港式の繁体字に揃えて `text` にし、`hk2s` で簡体字を作って
  `textVariants.Hans` に入れる。
- 単語のテキストも `text` と同じ変換で揃える（単語には `textVariants` を持たせない。§7.4.3）。
"""

from __future__ import annotations

import unicodedata
from dataclasses import dataclass
from functools import cache
from typing import Protocol


class _Converter(Protocol):
    def convert(self, text: str) -> str: ...


@cache
def _converter(config: str) -> _Converter:
    import opencc  # 遅延 import（変換が要らない言語ペアでは読み込まない）

    return opencc.OpenCC(config)


def convert(text: str, config: str) -> str:
    """OpenCC の設定名（`t2s`、`s2t`、`s2hk`、`hk2s` など）でテキストを変換し、NFC に揃える。"""
    if not text:
        return text
    return unicodedata.normalize("NFC", _converter(config).convert(text))


@dataclass(frozen=True)
class ScriptForms:
    """字体を揃えた結果。`main` を `text` に、`variants` を `textVariants` に入れる。"""

    main: str
    variants: dict[str, str]


# 言語ごとの（`text` 用の変換、もう一方の字体のキー、もう一方の字体を作る変換）
_RULES: dict[str, tuple[str, str, str]] = {
    "zh": ("t2s", "Hant", "s2t"),
    "yue": ("s2hk", "Hans", "hk2s"),
}


def needs_conversion(lang: str | None) -> bool:
    """字体の変換の対象の言語（`zh` / `yue`）かを返す。"""
    return lang in _RULES


def normalize_main(text: str, lang: str | None) -> str:
    """`text` に入れる字体に揃える（単語のテキストにも使う）。対象外の言語はそのまま返す。"""
    if lang not in _RULES:
        return text
    return convert(text, _RULES[lang][0])


def script_forms(text: str, lang: str | None) -> ScriptForms:
    """`text` と `textVariants` を作る。

    もう一方の字体が `text` と同じ（漢字を含まないなど）なら `variants` は空にする。
    """
    if lang not in _RULES:
        return ScriptForms(main=text, variants={})
    main_cfg, key, other_cfg = _RULES[lang]
    main = convert(text, main_cfg)
    other = convert(main, other_cfg)
    variants = {key: other} if other != main else {}
    return ScriptForms(main=main, variants=variants)
