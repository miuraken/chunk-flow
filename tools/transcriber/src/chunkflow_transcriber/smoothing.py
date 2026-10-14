"""言語の平滑化（§7.4.5）。

短いチャンク（1 秒未満。「OK」「はい」「네」など）や確率の低いチャンクは判定が不安定なので、
前後のチャンクの判定で補う。教材では同じ言語が続きやすい性質を使う。自分の判定に、前後の
チャンクの判定（対数オッズ）を重みを付けて足し合わせる。確信度の高い短いチャンクは自分の判定が
勝つように、前後のチャンクの寄与には上限を設ける。平滑化した後も確率が低ければ `lang` を None に
する。
"""

from __future__ import annotations

import math
from collections.abc import Sequence
from dataclasses import dataclass

from .model import LangDecision, LangObservation

_EPS = 1e-6


@dataclass(frozen=True)
class SmoothingParams:
    """平滑化のパラメータ。

    - `short_ms`: これより短いチャンクを平滑化する
    - `low_conf`: 確率がこれ未満のチャンクも（長くても）平滑化する
    - `neighbor_weight`: 前後のチャンク 1 つあたりの重み
    - `max_neighbor_logit`: 前後のチャンクの対数オッズの上限（絶対値）
    - `max_own_logit`: 自分の対数オッズの上限（確率 1.0 で無限大にならないように）
    - `far_gap_ms`: 前後のチャンクとの間隔がこれより長ければ重みを半分にする
    - `null_below`: 平滑化の後の確率がこれ未満なら `lang` を None にする
    """

    short_ms: int = 1000
    low_conf: float = 0.7
    neighbor_weight: float = 0.5
    max_neighbor_logit: float = 3.0
    max_own_logit: float = 6.0
    far_gap_ms: int = 4000
    null_below: float = 0.6


def _logit(p: float) -> float:
    p = min(max(p, _EPS), 1.0 - _EPS)
    return math.log(p / (1.0 - p))


def _sigmoid(x: float) -> float:
    if x >= 0:
        return 1.0 / (1.0 + math.exp(-x))
    e = math.exp(x)
    return e / (1.0 + e)


def _clip(x: float, limit: float) -> float:
    return max(-limit, min(limit, x))


def needs_smoothing(obs: LangObservation, params: SmoothingParams) -> bool:
    """平滑化の対象（短い、または確率が低い）かを返す。"""
    conf = max(obs.p_first, 1.0 - obs.p_first)
    return obs.duration_ms < params.short_ms or conf < params.low_conf


def smooth_languages(
    observations: Sequence[LangObservation],
    pair: tuple[str, str],
    params: SmoothingParams | None = None,
) -> list[LangDecision]:
    """チャンクの並びの言語判定を平滑化する。

    `observations` は開始時刻の順。前後のチャンクの判定は平滑化する前の値を使う（順序に依存しない）。
    前後のチャンクも平滑化の対象（短い・確率が低い）なら、その寄与は半分にする。
    """
    p = params or SmoothingParams()
    first, second = pair
    logits = [_logit(o.p_first) for o in observations]
    flags = [needs_smoothing(o, p) for o in observations]
    out: list[LangDecision] = []
    for i, obs in enumerate(observations):
        own = _clip(logits[i], p.max_own_logit)
        total = own
        if flags[i]:
            for j in (i - 1, i + 1):
                if j < 0 or j >= len(observations):
                    continue
                w = p.neighbor_weight
                if flags[j]:
                    w *= 0.5
                gap = (
                    obs.start_ms - observations[j].end_ms
                    if j < i
                    else observations[j].start_ms - obs.end_ms
                )
                if gap > p.far_gap_ms:
                    w *= 0.5
                total += w * _clip(logits[j], p.max_neighbor_logit)
        prob_first = _sigmoid(total)
        best = first if prob_first >= 0.5 else second
        prob = max(prob_first, 1.0 - prob_first)
        own_best = first if obs.p_first >= 0.5 else second
        lang: str | None = best if prob >= p.null_below else None
        out.append(
            LangDecision(lang=lang, prob=prob, best=best, smoothed=flags[i] and best != own_best)
        )
    return out
