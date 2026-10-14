"""エンジンの選択（`--engine auto|mlx|faster-whisper`。§7.4.4）。"""

from __future__ import annotations

import platform
import sys
from pathlib import Path

from ..config import PcConfig
from .base import EngineInfo, EngineSegment, EngineWord, LanguageDetector, Transcriber

__all__ = [
    "EngineInfo",
    "EngineSegment",
    "EngineWord",
    "LanguageDetector",
    "Transcriber",
    "create_engine",
    "resolve_engine_name",
]


def _is_apple_silicon() -> bool:
    return sys.platform == "darwin" and platform.machine() == "arm64"


def resolve_engine_name(requested: str) -> str:
    """`auto` を実際のエンジン名に解決する。

    macOS（Apple Silicon）なら mlx、それ以外は faster-whisper（CUDA が使えれば GPU、なければ CPU）。
    """
    if requested != "auto":
        return requested
    return "mlx" if _is_apple_silicon() else "faster-whisper"


def create_engine(
    engine: str, model_name: str, pc: PcConfig, model_dir: Path | None = None
) -> LanguageDetector | Transcriber:
    """エンジンを作る。戻り値は `LanguageDetector` と `Transcriber` の両方を満たす。"""
    name = resolve_engine_name(engine)
    source = pc.model_source(name, model_name)
    cache = model_dir or pc.model_dir
    if name == "mlx":
        from .mlx_engine import MlxWhisperEngine

        return MlxWhisperEngine(model_name, source, cache_dir=cache)
    if name == "faster-whisper":
        from .faster_whisper_engine import FasterWhisperEngine

        return FasterWhisperEngine(
            model_name, source, cache_dir=cache, device=pc.device, compute_type=pc.compute_type
        )
    raise ValueError(f"未知のエンジンです: {engine}")
