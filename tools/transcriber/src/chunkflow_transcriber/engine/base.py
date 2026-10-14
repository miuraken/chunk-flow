"""エンジンのインターフェース（§7.4.4「エンジンの共通化」）。

エンジンは次の 2 つの操作だけを持つ。手順 2〜8 は共通のコード（`pipeline`）で行う。

1. `language_probs`: 音声（16kHz・モノラルの float32）の、全言語の確率を返す
2. `transcribe`: 言語を指定して文字起こしし、単語の時刻付きのセグメントを返す

時刻は、渡した音声の先頭からの秒。前のチャンクの文脈は引き継がない（呼び出しごとに独立）。
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol, runtime_checkable

import numpy as np


@dataclass(frozen=True)
class EngineWord:
    """エンジンが返す単語。時刻は秒（渡した音声の先頭から）。"""

    start: float
    end: float
    text: str
    prob: float | None = None


@dataclass(frozen=True)
class EngineSegment:
    """エンジンが返すセグメント。幻覚の判定に使う値も持つ。"""

    start: float
    end: float
    text: str
    words: tuple[EngineWord, ...] = field(default_factory=tuple)
    avg_logprob: float | None = None
    no_speech_prob: float | None = None
    compression_ratio: float | None = None


@runtime_checkable
class LanguageDetector(Protocol):
    """言語の確率を返す操作。"""

    def language_probs(self, audio: np.ndarray) -> dict[str, float]:
        """音声の、全言語の確率（Whisper の言語コード → 確率）を返す。"""
        ...


@runtime_checkable
class Transcriber(Protocol):
    """言語を指定して文字起こしする操作。"""

    def transcribe(
        self, audio: np.ndarray, language: str, prompt: str | None = None
    ) -> list[EngineSegment]:
        """言語を指定して文字起こしし、単語の時刻付きのセグメントを返す。"""
        ...


@dataclass(frozen=True)
class EngineInfo:
    """`generator` に記録するエンジンとモデルの情報。"""

    engine: str  # 例: "mlx-whisper 0.4.3"
    model: str  # 例: "large-v3-turbo"
    model_repo: str
    model_revision: str
