"""mlx-whisper のエンジン（macOS、Apple Silicon）。

言語の確率は、mlx-whisper の公開されていない内部 API `mlx_whisper.decoding.detect_language`
（`Whisper.detect_language` として束縛されている）で得る。このため mlx-whisper の版を
`pyproject.toml` で完全に固定し、関数の存在と戻り値の形をテストで確かめる
（`tests/test_mlx_internal_api.py`）。
"""

from __future__ import annotations

from importlib.metadata import version
from pathlib import Path
from typing import Any

import numpy as np

from ..config import ModelSource
from .base import EngineInfo, EngineSegment, EngineWord

SUPPORTED_MLX_WHISPER = "0.4.3"


def check_internal_api() -> None:
    """依存している mlx-whisper の内部 API があることを確かめる。なければ `RuntimeError`。"""
    import inspect

    from mlx_whisper import decoding
    from mlx_whisper.whisper import Whisper

    fn = getattr(decoding, "detect_language", None)
    if fn is None or getattr(Whisper, "detect_language", None) is not fn:
        raise RuntimeError("mlx_whisper.decoding.detect_language が見つかりません")
    params = list(inspect.signature(fn).parameters)
    if params[:3] != ["model", "mel", "tokenizer"]:
        raise RuntimeError(f"detect_language の引数が想定と違います: {params}")


def download(source: ModelSource, cache_dir: Path | None) -> Path:
    """モデルを取得元とリビジョンを固定して取得し、ローカルのパスを返す。"""
    from huggingface_hub import snapshot_download

    path = snapshot_download(
        repo_id=source.repo,
        revision=source.revision,
        cache_dir=str(cache_dir) if cache_dir else None,
    )
    return Path(path)


class MlxWhisperEngine:
    """mlx-whisper による `LanguageDetector` と `Transcriber` の実装。"""

    def __init__(
        self,
        model_name: str,
        source: ModelSource,
        cache_dir: Path | None = None,
        fp16: bool = True,
    ) -> None:
        installed = version("mlx-whisper")
        if installed != SUPPORTED_MLX_WHISPER:
            raise RuntimeError(
                f"mlx-whisper {installed} は未検証です（{SUPPORTED_MLX_WHISPER} を前提にしています）"
            )
        check_internal_api()
        self._model_path = download(source, cache_dir)
        self._fp16 = fp16
        self._model: Any = None
        self.info = EngineInfo(
            engine=f"mlx-whisper {installed}",
            model=model_name,
            model_repo=source.repo,
            model_revision=source.revision,
        )

    def _dtype(self) -> Any:
        import mlx.core as mx

        return mx.float16 if self._fp16 else mx.float32

    def _get_model(self) -> Any:
        """言語判定に使うモデル。

        transcribe() が使う `ModelHolder` に同じモデルが載っていればそれを共有する。別のモデルが
        載っている（言語判定と文字起こしで別のモデルを使う）場合は、自分用に 1 度だけ読み込む。
        """
        from mlx_whisper.load_models import load_model
        from mlx_whisper.transcribe import ModelHolder

        path = str(self._model_path)
        if ModelHolder.model is None:
            return ModelHolder.get_model(path, self._dtype())
        if ModelHolder.model_path == path:
            return ModelHolder.model
        if self._model is None:
            self._model = load_model(path, dtype=self._dtype())
        return self._model

    def language_probs(self, audio: np.ndarray) -> dict[str, float]:
        """音声（先頭 30 秒まで）の全言語の確率を返す。"""
        import mlx.core as mx
        from mlx_whisper.audio import N_FRAMES, N_SAMPLES, log_mel_spectrogram, pad_or_trim
        from mlx_whisper.decoding import detect_language
        from mlx_whisper.tokenizer import get_tokenizer

        model = self._get_model()
        mel = log_mel_spectrogram(audio, n_mels=model.dims.n_mels, padding=N_SAMPLES)
        segment = pad_or_trim(mel, N_FRAMES, axis=-2).astype(
            mx.float16 if self._fp16 else mx.float32
        )
        tokenizer = get_tokenizer(
            model.is_multilingual, num_languages=model.num_languages, task="transcribe"
        )
        _, probs = detect_language(model, segment, tokenizer)
        return {str(k): float(v) for k, v in probs.items()}

    def transcribe(
        self, audio: np.ndarray, language: str, prompt: str | None = None
    ) -> list[EngineSegment]:
        """言語を指定して文字起こしする（前の文脈は引き継がない）。"""
        import mlx_whisper

        result = mlx_whisper.transcribe(
            audio,
            path_or_hf_repo=str(self._model_path),
            language=language,
            task="transcribe",
            initial_prompt=prompt,
            word_timestamps=True,
            condition_on_previous_text=False,
            fp16=self._fp16,
            verbose=None,
        )
        out: list[EngineSegment] = []
        for s in result.get("segments", []):
            words = tuple(
                EngineWord(
                    start=float(w["start"]),
                    end=float(w["end"]),
                    text=str(w["word"]),
                    prob=float(w["probability"]) if "probability" in w else None,
                )
                for w in s.get("words", [])
            )
            out.append(
                EngineSegment(
                    start=float(s["start"]),
                    end=float(s["end"]),
                    text=str(s["text"]),
                    words=words,
                    avg_logprob=s.get("avg_logprob"),
                    no_speech_prob=s.get("no_speech_prob"),
                    compression_ratio=s.get("compression_ratio"),
                )
            )
        return out
