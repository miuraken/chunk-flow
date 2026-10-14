"""言語タグの扱い（Whisper の言語コード ⇔ BCP-47、言語ペアへの絞り込み）。

形式（§7.4.3）では Whisper の言語コードをそのまま BCP-47 として使う。利用者が設定やコマンドで
`zh-CN` や `ja-JP` のような地域付きのタグを書いても受け付けられるよう、ここで正規化する。
"""

from __future__ import annotations

import math
from collections.abc import Mapping

# Whisper（large-v3 系、100 言語）の言語コード。`yue` は large-v3 で追加された。
WHISPER_LANGUAGES: frozenset[str] = frozenset(
    """
    en zh de es ru ko fr ja pt tr pl ca nl ar sv it id hi fi vi he uk el ms cs ro da hu ta no
    th ur hr bg lt la mi ml cy sk te fa lv bn sr az sl kn et mk br eu is hy ne mn bs kk sq sw
    gl mr pa si km sn yo so af oc ka be tg sd gu am yi lo uz fo ht ps tk nn mt sa lb my bo tl
    mg as tt haw ln ha ba jw su yue
    """.split()  # noqa: SIM905
)

# 地域や文字体系の付いたタグ・別名から Whisper のコードへの対応。
_ALIASES: dict[str, str] = {
    "cmn": "zh",
    "zh-cn": "zh",
    "zh-hans": "zh",
    "zh-sg": "zh",
    "zh-tw": "zh",
    "zh-hant": "zh",
    "zh-hk": "yue",
    "zh-yue": "yue",
    "yue-hk": "yue",
    "yue-hant": "yue",
    "iw": "he",
    "jv": "jw",
    "nb": "no",
}


class UnknownLanguageError(ValueError):
    """Whisper が扱えない言語タグを指定したときの例外。"""


def to_whisper_code(tag: str) -> str:
    """BCP-47 のタグ（`ko`、`ja-JP`、`zh-Hans`、`zh-HK` など）を Whisper の言語コードに変換する。

    `zh-HK` は広東語（`yue`）として扱う（香港の教材は広東語が多いため）。普通話の香港の教材は
    `zh` を直接指定する。扱えないタグは `UnknownLanguageError` を送出する。
    """
    key = tag.strip().replace("_", "-").lower()
    if not key:
        raise UnknownLanguageError("言語タグが空です")
    if key in _ALIASES:
        return _ALIASES[key]
    if key in WHISPER_LANGUAGES:
        return key
    primary = key.split("-", 1)[0]
    if primary in _ALIASES:
        return _ALIASES[primary]
    if primary in WHISPER_LANGUAGES:
        return primary
    raise UnknownLanguageError(f"Whisper が扱えない言語タグです: {tag!r}")


def to_bcp47(code: str) -> str:
    """Whisper の言語コードを、形式に書く BCP-47 のタグに変換する。

    §7.4.3 のとおり Whisper のコードはそのまま BCP-47 として有効なので、検証して小文字で返す。
    ただし Whisper の `jw`（ジャワ語）は BCP-47 では `jv` なので置き換える。
    """
    c = code.strip().lower()
    if c not in WHISPER_LANGUAGES:
        raise UnknownLanguageError(f"Whisper の言語コードではありません: {code!r}")
    return "jv" if c == "jw" else c


def restrict_to_pair(
    probs: Mapping[str, float], pair: tuple[str, str]
) -> tuple[str | None, float, dict[str, float]]:
    """全言語の確率を言語ペアの 2 言語に絞り、正規化して比べる（§7.4.5）。

    戻り値は（確率の高い方の言語、その確率、2 言語の正規化した確率）。どちらの確率も 0 または
    得られない場合は（None, 0.0, {a: 0.5, b: 0.5}）を返す。
    """
    a, b = pair
    pa = max(0.0, float(probs.get(a, 0.0)))
    pb = max(0.0, float(probs.get(b, 0.0)))
    total = pa + pb
    if total <= 0.0 or math.isnan(total):
        return None, 0.0, {a: 0.5, b: 0.5}
    na, nb = pa / total, pb / total
    if na >= nb:
        return a, na, {a: na, b: nb}
    return b, nb, {a: na, b: nb}


def top_languages(probs_list: list[tuple[Mapping[str, float], float]], n: int = 2) -> list[str]:
    """チャンクごとの全言語の確率（と重み＝長さ）から、上位 n 言語を推定する。

    言語ペアが設定されていないときに、ファイル全体の推定に使う（§7.4.4。誤りやすいのでログに出す）。
    """
    score: dict[str, float] = {}
    for probs, weight in probs_list:
        for lang, p in probs.items():
            score[lang] = score.get(lang, 0.0) + float(p) * float(weight)
    return [lang for lang, _ in sorted(score.items(), key=lambda kv: (-kv[1], kv[0]))[:n]]
