"""テスト用の偽物（エンジン・VAD・音声）。モデルや GPU なしでパイプラインを動かす。"""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

from chunkflow_transcriber.audio import SAMPLE_RATE
from chunkflow_transcriber.config import VadParams
from chunkflow_transcriber.engine.base import EngineInfo, EngineSegment, EngineWord


def timeline_audio(seconds: float) -> np.ndarray:
    """各サンプルの値がその時刻（秒）になっている音声。偽のエンジンが位置を知るために使う。"""
    n = int(seconds * SAMPLE_RATE)
    return (np.arange(n, dtype=np.float64) / SAMPLE_RATE).astype(np.float32)


@dataclass
class Utt:
    """偽の音声の中の発話。"""

    start_ms: int
    end_ms: int
    lang: str
    # 言語ごとの書き起こし（指定した言語のテキスト。なければ `default_text`）
    texts: dict[str, str]
    probs: dict[str, float]


@dataclass
class FakeEngine:
    """発話の表を引いて答える偽のエンジン（`LanguageDetector` と `Transcriber`）。"""

    utts: list[Utt]
    calls: list[tuple[str, int, int, str]] = field(default_factory=list)
    info: EngineInfo = field(
        default_factory=lambda: EngineInfo("fake-engine 1.0", "fake-model", "fake/repo", "0" * 40)
    )

    def _window(self, audio: np.ndarray) -> tuple[int, int]:
        return round(float(audio[0]) * 1000), round(float(audio[-1]) * 1000)

    def _find(self, audio: np.ndarray) -> Utt:
        a, b = self._window(audio)
        best = max(self.utts, key=lambda u: min(b, u.end_ms) - max(a, u.start_ms))
        return best

    def language_probs(self, audio: np.ndarray) -> dict[str, float]:
        u = self._find(audio)
        a, b = self._window(audio)
        self.calls.append(("detect", a, b, ""))
        return dict(u.probs)

    def transcribe(
        self, audio: np.ndarray, language: str, prompt: str | None = None
    ) -> list[EngineSegment]:
        u = self._find(audio)
        a, b = self._window(audio)
        self.calls.append(("transcribe", a, b, language))
        text = u.texts.get(language, u.texts.get("*", ""))
        if not text:
            return []
        # 単語に分ける（空白区切り。空白がなければ 1 語）
        parts = text.split(" ") if " " in text else [text]
        start = (u.start_ms - a) / 1000
        end = (u.end_ms - a) / 1000
        step = (end - start) / len(parts)
        words = tuple(
            EngineWord(
                start=start + i * step,
                end=start + (i + 1) * step,
                text=(" " if " " in text else "") + p,
                prob=0.9,
            )
            for i, p in enumerate(parts)
        )
        return [
            EngineSegment(
                start=start,
                end=end,
                text=text,
                words=words,
                avg_logprob=-0.2,
                no_speech_prob=0.01,
                compression_ratio=1.2,
            )
        ]


def fake_vad(utts: list[Utt]):
    """発話の表をそのまま発話区間として返す偽の VAD。"""

    def detect(audio: np.ndarray, params: VadParams) -> list[tuple[int, int]]:
        return [(u.start_ms, u.end_ms) for u in utts]

    return detect
