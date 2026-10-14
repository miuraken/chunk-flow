"""faster-whisper のエンジン（Linux / WSL2、NVIDIA の GPU または CPU）。

**未検証**: 開発用の Mac には faster-whisper を入れていない（Linux 限定の依存）ため、このモジュールは
faster-whisper 1.2.1 のソース（`WhisperModel.detect_language` / `WhisperModel.transcribe`）を読んで
インターフェースに合わせて書いたもので、実際には動かしていない。NVIDIA の PC で確かめる。
"""

from __future__ import annotations

from importlib.metadata import version
from pathlib import Path
from typing import Any

import numpy as np

from ..config import ModelSource
from .base import EngineInfo, EngineSegment, EngineWord


def cuda_available() -> bool:
    """CUDA の GPU が使えるかを返す（ctranslate2 で確かめる）。"""
    try:
        import ctranslate2

        return ctranslate2.get_cuda_device_count() > 0
    except Exception:
        return False


class FasterWhisperEngine:
    """faster-whisper による `LanguageDetector` と `Transcriber` の実装（未検証）。"""

    def __init__(
        self,
        model_name: str,
        source: ModelSource,
        cache_dir: Path | None = None,
        device: str = "auto",
        compute_type: str | None = None,
    ) -> None:
        from faster_whisper import WhisperModel
        from huggingface_hub import snapshot_download

        if device == "auto":
            device = "cuda" if cuda_available() else "cpu"
        if compute_type is None:
            compute_type = "float16" if device == "cuda" else "int8"
        path = snapshot_download(
            repo_id=source.repo,
            revision=source.revision,
            cache_dir=str(cache_dir) if cache_dir else None,
        )
        self._model: Any = WhisperModel(path, device=device, compute_type=compute_type)
        self.device = device
        self.info = EngineInfo(
            engine=f"faster-whisper {version('faster-whisper')} ({device}, {compute_type})",
            model=model_name,
            model_repo=source.repo,
            model_revision=source.revision,
        )

    def language_probs(self, audio: np.ndarray) -> dict[str, float]:
        """音声（先頭 30 秒まで）の全言語の確率を返す。"""
        _, _, all_probs = self._model.detect_language(
            audio=audio, vad_filter=False, language_detection_segments=1
        )
        return {str(lang): float(p) for lang, p in (all_probs or [])}

    def transcribe(
        self, audio: np.ndarray, language: str, prompt: str | None = None
    ) -> list[EngineSegment]:
        """言語を指定して文字起こしする（前の文脈は引き継がない）。"""
        segments, _info = self._model.transcribe(
            audio,
            language=language,
            task="transcribe",
            initial_prompt=prompt,
            word_timestamps=True,
            condition_on_previous_text=False,
            vad_filter=False,
            beam_size=5,
        )
        out: list[EngineSegment] = []
        for s in segments:  # ジェネレータ: ここで実際に認識が走る
            words = tuple(
                EngineWord(
                    start=float(w.start),
                    end=float(w.end),
                    text=str(w.word),
                    prob=float(w.probability),
                )
                for w in (s.words or [])
            )
            out.append(
                EngineSegment(
                    start=float(s.start),
                    end=float(s.end),
                    text=str(s.text),
                    words=words,
                    avg_logprob=float(s.avg_logprob),
                    no_speech_prob=float(s.no_speech_prob),
                    compression_ratio=float(s.compression_ratio),
                )
            )
        return out
