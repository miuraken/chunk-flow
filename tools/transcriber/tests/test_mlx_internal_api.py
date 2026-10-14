"""mlx-whisper の内部 API（`mlx_whisper.decoding.detect_language`）に依存していることの確認。

mlx-whisper は macOS（Apple Silicon）だけの依存なので、ほかの環境では飛ばす。
`-m model` を付けたテストは実際のモデルを使う（手元でのみ。CI では実行しない）。
"""

import inspect
import os
from pathlib import Path

import pytest

mlx_whisper = pytest.importorskip("mlx_whisper", reason="mlx-whisper は macOS（Apple Silicon）限定")


def test_pinned_version() -> None:
    from importlib.metadata import version

    from chunkflow_transcriber.engine.mlx_engine import SUPPORTED_MLX_WHISPER

    assert version("mlx-whisper") == SUPPORTED_MLX_WHISPER


def test_detect_language_exists_with_expected_signature() -> None:
    from mlx_whisper import decoding
    from mlx_whisper.whisper import Whisper

    from chunkflow_transcriber.engine.mlx_engine import check_internal_api

    assert callable(decoding.detect_language)
    assert list(inspect.signature(decoding.detect_language).parameters)[:3] == [
        "model",
        "mel",
        "tokenizer",
    ]
    assert Whisper.detect_language is decoding.detect_language
    check_internal_api()


def test_helpers_used_by_engine_exist() -> None:
    from mlx_whisper.audio import N_FRAMES, N_SAMPLES, log_mel_spectrogram, pad_or_trim
    from mlx_whisper.load_models import load_model
    from mlx_whisper.tokenizer import LANGUAGES, get_tokenizer
    from mlx_whisper.transcribe import ModelHolder

    assert N_FRAMES == 3000 and N_SAMPLES == 480000
    assert callable(log_mel_spectrogram) and callable(pad_or_trim) and callable(load_model)
    assert callable(get_tokenizer)
    assert hasattr(ModelHolder, "get_model")
    assert "yue" in LANGUAGES
    # 言語タグの変換が持つ Whisper の言語の一覧が、mlx-whisper の一覧と一致する
    from chunkflow_transcriber.langs import WHISPER_LANGUAGES

    assert set(LANGUAGES) == WHISPER_LANGUAGES
    params = inspect.signature(mlx_whisper.transcribe).parameters
    for name in (
        "initial_prompt",
        "word_timestamps",
        "condition_on_previous_text",
        "decode_options",
    ):
        assert name in params
    # language / task / fp16 は **decode_options 経由で DecodingOptions に渡る
    from dataclasses import fields

    from mlx_whisper.decoding import DecodingOptions

    names = {f.name for f in fields(DecodingOptions)}
    assert {"language", "task", "fp16"} <= names


@pytest.mark.model
def test_language_probs_with_real_model() -> None:
    """実際の turbo モデルで言語の確率を得る（`uv run pytest -m model`）。"""
    import numpy as np

    from chunkflow_transcriber.config import PcConfig
    from chunkflow_transcriber.engine import create_engine

    model_dir = Path(os.environ.get("CHUNKFLOW_MODEL_DIR", ".work/models"))
    engine = create_engine("mlx", "large-v3-turbo", PcConfig(), model_dir)
    probs = engine.language_probs(np.zeros(16000, dtype=np.float32))  # type: ignore[union-attr]
    assert len(probs) == 100
    assert {"ja", "ko", "zh", "yue", "hi", "en"} <= set(probs)
    assert sum(probs.values()) == pytest.approx(1.0, abs=1e-2)
