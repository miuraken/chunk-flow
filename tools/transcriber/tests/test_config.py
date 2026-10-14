"""設定の読み込みのテスト。"""

import pytest

from chunkflow_transcriber.config import (
    DEFAULT_MODEL,
    ConfigError,
    load_course_config,
    parse_course_config,
    parse_pc_config,
)


def test_course_config_example_from_design_doc() -> None:
    cfg = parse_course_config(
        {
            "languages": {"target": "yue", "base": "en"},
            "model": {"name": "large-v3"},
            "hints": {"yue": {"prompt": "佢哋喺度傾緊偈，我哋一齊聽下啦。"}},
        }
    )
    assert cfg.pair == ("yue", "en")
    assert cfg.model == "large-v3"
    assert cfg.prompts == {"yue": "佢哋喺度傾緊偈，我哋一齊聽下啦。"}


def test_course_config_normalizes_tags() -> None:
    cfg = parse_course_config({"languages": {"target": "zh-CN", "base": "ja-JP"}})
    assert cfg.pair == ("zh", "ja")


def test_course_config_defaults() -> None:
    cfg = parse_course_config({})
    assert cfg.model == DEFAULT_MODEL
    assert cfg.pair is None
    assert cfg.detect_model is None


def test_single_language_course_has_no_pair() -> None:
    cfg = parse_course_config({"languages": {"target": "ko"}})
    assert cfg.target == "ko"
    assert cfg.pair is None


def test_course_config_parameters() -> None:
    cfg = parse_course_config(
        {
            "vad": {"merge_gap_ms": 200},
            "chunking": {"max_ms": 10000},
            "smoothing": {"null_below": 0.7},
        }
    )
    assert cfg.vad.merge_gap_ms == 200
    assert cfg.shaping.max_ms == 10000
    assert cfg.smoothing.null_below == 0.7


def test_course_config_rejects_unknown_keys() -> None:
    with pytest.raises(ConfigError):
        parse_course_config({"vad": {"merge_gap": 200}})


def test_course_config_rejects_unknown_language() -> None:
    with pytest.raises(ConfigError):
        parse_course_config({"languages": {"target": "xx"}})


def test_load_course_config_reads_toml(tmp_path) -> None:
    (tmp_path / "chunkflow.toml").write_text(
        '[languages]\ntarget = "ko"\nbase = "ja"\n', encoding="utf-8"
    )
    assert load_course_config(tmp_path).pair == ("ko", "ja")
    assert load_course_config(tmp_path / "missing").pair is None


def test_pc_config_requires_commit_hash() -> None:
    with pytest.raises(ConfigError):
        parse_pc_config({"models": {"mlx": {"large-v3": {"repo": "a/b", "revision": "main"}}}})
    cfg = parse_pc_config(
        {"models": {"mlx": {"my-model": {"repo": "a/b", "revision": "a" * 40}}}, "engine": "mlx"}
    )
    assert cfg.model_source("mlx", "my-model").revision == "a" * 40
    # 既定の登録は残る
    assert cfg.model_source("mlx", "large-v3-turbo").repo == "mlx-community/whisper-large-v3-turbo"


def test_pc_config_rejects_unknown_engine() -> None:
    with pytest.raises(ConfigError):
        parse_pc_config({"engine": "whisper.cpp"})


def test_default_model_sources_are_pinned() -> None:
    cfg = parse_pc_config({})
    for engine in ("mlx", "faster-whisper"):
        for name in ("large-v3-turbo", "large-v3"):
            src = cfg.model_source(engine, name)
            assert len(src.revision) == 40
    with pytest.raises(ConfigError):
        cfg.model_source("mlx", "tiny")
