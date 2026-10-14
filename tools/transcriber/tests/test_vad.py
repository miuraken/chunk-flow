"""VAD（発話区間の検出と候補チャンク）のテスト。モデルは使わない。"""

import itertools

from chunkflow_transcriber.config import VadParams
from chunkflow_transcriber.model import CandidateChunk
from chunkflow_transcriber.vad import (
    SILERO_ONNX_SHA256,
    SILERO_WHEEL_SHA256,
    WINDOW,
    merge_regions,
    recognition_window,
    timestamps_from_probs,
)


def test_merge_regions_joins_short_gaps() -> None:
    out = merge_regions(
        [(0, 1000), (1200, 2000), (2600, 3000)], merge_gap_ms=300, max_chunk_ms=10_000
    )
    assert out == [CandidateChunk(0, 2000), CandidateChunk(2600, 3000)]


def test_merge_regions_respects_max_length() -> None:
    out = merge_regions([(0, 6000), (6100, 12000)], merge_gap_ms=300, max_chunk_ms=10_000)
    assert out == [CandidateChunk(0, 6000), CandidateChunk(6100, 12000)]


def test_merge_regions_splits_overlong_region() -> None:
    out = merge_regions([(0, 25_000)], merge_gap_ms=300, max_chunk_ms=10_000)
    assert len(out) == 3
    assert out[0].start_ms == 0 and out[-1].end_ms == 25_000
    assert all(c.duration_ms <= 10_000 for c in out)
    assert all(a.end_ms == b.start_ms for a, b in itertools.pairwise(out))


def test_merge_regions_sorts_and_drops_empty() -> None:
    out = merge_regions(
        [(5000, 6000), (100, 100), (0, 1000)], merge_gap_ms=300, max_chunk_ms=10_000
    )
    assert out == [CandidateChunk(0, 1000), CandidateChunk(5000, 6000)]


def test_recognition_window_does_not_cross_neighbors() -> None:
    chunks = [CandidateChunk(1000, 2000), CandidateChunk(2100, 3000), CandidateChunk(5000, 6000)]
    assert recognition_window(chunks, 0, 200, 10_000) == (800, 2100)
    assert recognition_window(chunks, 1, 200, 10_000) == (2000, 3200)
    assert recognition_window(chunks, 2, 200, 6100) == (4800, 6100)


def _probs(pattern: list[tuple[float, int]]) -> list[float]:
    """(確率, 窓の数) の列から確率の列を作る。"""
    out: list[float] = []
    for p, n in pattern:
        out.extend([p] * n)
    return out


def test_timestamps_from_probs_basic() -> None:
    # 窓は 32ms。無音 10 窓 → 発話 20 窓 → 無音 20 窓 → 発話 10 窓 → 無音 10 窓
    probs = _probs([(0.0, 10), (0.9, 20), (0.0, 20), (0.9, 10), (0.0, 10)])
    params = VadParams(min_speech_ms=100, min_silence_ms=150, speech_pad_ms=30)
    out = timestamps_from_probs(probs, params, len(probs) * WINDOW)
    assert len(out) == 2
    (s1, e1), (s2, e2) = out
    assert s1 == 320 - 30 and e1 == 960 + 30
    assert s2 == 1600 - 30 and e2 == 1920 + 30


def test_timestamps_from_probs_bridges_short_silence() -> None:
    probs = _probs([(0.9, 20), (0.1, 3), (0.9, 20), (0.0, 10)])
    params = VadParams(min_speech_ms=100, min_silence_ms=150, speech_pad_ms=0)
    out = timestamps_from_probs(probs, params, len(probs) * WINDOW)
    assert len(out) == 1


def test_timestamps_from_probs_drops_too_short() -> None:
    probs = _probs([(0.0, 10), (0.9, 2), (0.0, 20)])
    params = VadParams(min_speech_ms=100, min_silence_ms=150, speech_pad_ms=0)
    assert timestamps_from_probs(probs, params, len(probs) * WINDOW) == []


def test_timestamps_from_probs_hysteresis() -> None:
    # 発話中の 0.4（閾値 0.5 未満だが 0.35 以上）は無音とみなさない
    probs = _probs([(0.9, 10), (0.4, 20), (0.9, 10), (0.0, 10)])
    params = VadParams(min_speech_ms=100, min_silence_ms=150, speech_pad_ms=0)
    assert len(timestamps_from_probs(probs, params, len(probs) * WINDOW)) == 1


def test_speech_until_end() -> None:
    probs = _probs([(0.0, 5), (0.9, 20)])
    params = VadParams(min_speech_ms=100, min_silence_ms=150, speech_pad_ms=0)
    total = len(probs) * WINDOW - 100
    out = timestamps_from_probs(probs, params, total)
    assert out[-1][1] == total * 1000 // 16000


def test_model_hashes_are_pinned() -> None:
    assert len(SILERO_WHEEL_SHA256) == 64
    assert len(SILERO_ONNX_SHA256) == 64
