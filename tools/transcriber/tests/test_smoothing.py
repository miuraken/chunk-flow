"""言語の平滑化のテスト。"""

import pytest

from chunkflow_transcriber.model import LangObservation
from chunkflow_transcriber.smoothing import SmoothingParams, smooth_languages

PAIR = ("ko", "ja")


def obs(start: int, end: int, p_ko: float) -> LangObservation:
    return LangObservation(duration_ms=end - start, p_first=p_ko, start_ms=start, end_ms=end)


def test_long_confident_chunks_are_unchanged() -> None:
    seq = [obs(0, 3000, 0.99), obs(3500, 6000, 0.02), obs(6500, 9000, 0.95)]
    out = smooth_languages(seq, PAIR)
    assert [d.lang for d in out] == ["ko", "ja", "ko"]
    assert not any(d.smoothed for d in out)


def test_unsure_short_chunk_follows_neighbors() -> None:
    # 「네」のような短いチャンクの判定が揺れている（ja 寄り 0.4）が、前後は ko
    seq = [obs(0, 3000, 0.99), obs(3500, 3900, 0.4), obs(4400, 7000, 0.98)]
    out = smooth_languages(seq, PAIR)
    assert out[1].lang == "ko"
    assert out[1].smoothed


def test_confident_short_chunk_keeps_own_language() -> None:
    # 前後が ja でも、短いチャンク自身が ko を強く示していれば ko のまま（ja-ko の「네」）
    seq = [obs(0, 3000, 0.01), obs(3500, 3900, 0.995), obs(4400, 7000, 0.01)]
    out = smooth_languages(seq, PAIR)
    assert out[1].lang == "ko"
    assert not out[1].smoothed


def test_ambiguous_short_chunk_between_disagreeing_neighbors_becomes_null() -> None:
    seq = [obs(0, 3000, 0.99), obs(3500, 3900, 0.5), obs(4400, 7000, 0.01)]
    out = smooth_languages(seq, PAIR)
    assert out[1].lang is None
    assert out[1].prob < 0.6


def test_low_confidence_long_chunk_is_smoothed() -> None:
    seq = [obs(0, 3000, 0.99), obs(3500, 6000, 0.45), obs(6500, 9000, 0.99)]
    out = smooth_languages(seq, PAIR)
    assert out[1].lang == "ko"


def test_far_neighbors_have_less_weight() -> None:
    near = [obs(0, 3000, 0.99), obs(3100, 3500, 0.3)]
    far = [obs(0, 3000, 0.99), obs(9000, 9400, 0.3)]
    p_near = smooth_languages(near, PAIR)[1]
    p_far = smooth_languages(far, PAIR)[1]
    # 近い方が ko に強く引かれる
    ko_near = p_near.prob if p_near.best == "ko" else 1 - p_near.prob
    ko_far = p_far.prob if p_far.best == "ko" else 1 - p_far.prob
    assert ko_near > ko_far


def test_null_threshold_is_configurable() -> None:
    seq = [obs(0, 3000, 0.65)]
    assert smooth_languages(seq, PAIR, SmoothingParams(null_below=0.6))[0].lang == "ko"
    assert smooth_languages(seq, PAIR, SmoothingParams(null_below=0.7))[0].lang is None


def test_probability_one_does_not_overflow() -> None:
    out = smooth_languages([obs(0, 500, 1.0), obs(600, 900, 0.0)], PAIR)
    assert out[0].lang == "ko"
    assert out[1].lang in ("ja", None)
    assert all(0.5 <= d.prob <= 1.0 for d in out)


def test_empty() -> None:
    assert smooth_languages([], PAIR) == []


@pytest.mark.parametrize("p", [0.0, 0.3, 0.5, 0.7, 1.0])
def test_single_chunk_probability_is_symmetric(p: float) -> None:
    a = smooth_languages([obs(0, 2000, p)], ("ko", "ja"))[0]
    b = smooth_languages([obs(0, 2000, 1 - p)], ("ja", "ko"))[0]
    assert a.prob == pytest.approx(b.prob)
    assert a.lang == b.lang
