"""設定（§7.4.4）。

- コースの設定（`<コース>/chunkflow.toml`）: 言語ペア、モデル、VAD とチャンクのパラメータ、言語ごとの
  書き起こしのヒント。コマンドの引数が優先する。
- PC ごとの設定（`~/.config/chunkflow-transcribe/config.toml`）: エンジン、デバイス、モデルの取得元と
  リビジョン、モデルの保存先。

モデルは取得元（Hugging Face のリポジトリ）とリビジョン（コミットハッシュ）を固定する（§8.2）。
"""

from __future__ import annotations

import re
import tomllib
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any

from .langs import to_whisper_code
from .segmenter import ShapingParams
from .smoothing import SmoothingParams

DEFAULT_MODEL = "large-v3-turbo"
COURSE_CONFIG_NAME = "chunkflow.toml"
PC_CONFIG_PATH = Path("~/.config/chunkflow-transcribe/config.toml")

_COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")


@dataclass(frozen=True)
class ModelSource:
    """モデルの取得元とリビジョン。"""

    repo: str
    revision: str


# エンジン → モデル名 → 取得元（2026-10-14 に Hugging Face で確認したコミット）
DEFAULT_MODEL_SOURCES: dict[str, dict[str, ModelSource]] = {
    "mlx": {
        "large-v3-turbo": ModelSource(
            "mlx-community/whisper-large-v3-turbo", "a4aaeec0636e6fef84abdcbe3544cb2bf7e9f6fb"
        ),
        "large-v3": ModelSource(
            "mlx-community/whisper-large-v3-mlx", "49e6aa286ad60c14352c404340ded53710378a11"
        ),
    },
    "faster-whisper": {
        # 旧 mobiuslabsgmbh/faster-whisper-large-v3-turbo（Hugging Face で改名された）
        "large-v3-turbo": ModelSource(
            "dropbox-dash/faster-whisper-large-v3-turbo",
            "0a363e9161cbc7ed1431c9597a8ceaf0c4f78fcf",
        ),
        "large-v3": ModelSource(
            "Systran/faster-whisper-large-v3", "edaa852ec7e145841d8ffdb056a99866b5f0a478"
        ),
    },
}


class ConfigError(ValueError):
    """設定の誤り。"""


@dataclass(frozen=True)
class VadParams:
    """VAD と候補チャンクのパラメータ（§7.4.4 の手順 2）。

    - `threshold`: Silero VAD の発話の閾値
    - `min_speech_ms`: これより短い発話は捨てる（「はい」「네」を残すため短めにする）
    - `min_silence_ms`: これより短い無音は発話の区切りにしない
    - `speech_pad_ms`: 発話の前後に付ける余白（VAD の出力）
    - `merge_gap_ms`: これより短い無音でつながる発話区間を 1 つの候補チャンクにまとめる
    - `max_chunk_ms`: 候補チャンクの長さの上限（長すぎる区間は分ける。Whisper の窓は 30 秒）
    - `recognition_pad_ms`: 認識のためにチャンクの前後に足す音声（隣の発話には食い込まない）
    """

    threshold: float = 0.5
    min_speech_ms: int = 150
    min_silence_ms: int = 150
    speech_pad_ms: int = 30
    merge_gap_ms: int = 300
    max_chunk_ms: int = 25_000
    recognition_pad_ms: int = 200


@dataclass(frozen=True)
class CourseConfig:
    """コースの設定（コマンドの引数を反映した後の値）。"""

    target: str | None = None
    base: str | None = None
    model: str = DEFAULT_MODEL
    detect_model: str | None = None  # None なら `model` と同じ
    prompts: dict[str, str] = field(default_factory=dict)
    vad: VadParams = field(default_factory=VadParams)
    shaping: ShapingParams = field(default_factory=ShapingParams)
    smoothing: SmoothingParams = field(default_factory=SmoothingParams)

    @property
    def pair(self) -> tuple[str, str] | None:
        """言語ペア（target, base）。base がなければ None。"""
        if self.target and self.base:
            return (self.target, self.base)
        return None


@dataclass(frozen=True)
class PcConfig:
    """PC ごとの設定。"""

    engine: str = "auto"  # auto | mlx | faster-whisper
    device: str = "auto"  # faster-whisper: auto | cuda | cpu
    compute_type: str | None = None  # faster-whisper: None なら cuda は float16、cpu は int8
    model_dir: Path | None = None  # None なら Hugging Face の既定のキャッシュ
    models: dict[str, dict[str, ModelSource]] = field(
        default_factory=lambda: {k: dict(v) for k, v in DEFAULT_MODEL_SOURCES.items()}
    )

    def model_source(self, engine: str, name: str) -> ModelSource:
        """エンジンとモデル名から取得元を返す。登録がなければ `ConfigError`。"""
        try:
            return self.models[engine][name]
        except KeyError:
            known = ", ".join(sorted(self.models.get(engine, {})))
            raise ConfigError(
                f"エンジン {engine} のモデル {name!r} の取得元が設定にありません（既知: {known}）。"
                f'PC ごとの設定の [models.{engine}."{name}"] に repo と revision を書いてください'
            ) from None


def _dataclass_from(cls: Any, base: Any, table: dict[str, Any], where: str) -> Any:
    names = set(cls.__dataclass_fields__)
    unknown = set(table) - names
    if unknown:
        raise ConfigError(f"{where} に未知の項目があります: {', '.join(sorted(unknown))}")
    return replace(base, **table)


def _normalize_lang(value: Any, where: str) -> str | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ConfigError(f"{where} は文字列で指定してください")
    try:
        return to_whisper_code(value)
    except ValueError as e:
        raise ConfigError(f"{where}: {e}") from None


def parse_course_config(data: dict[str, Any]) -> CourseConfig:
    """`chunkflow.toml` を読んだ辞書から `CourseConfig` を作る（純粋な関数）。"""
    cfg = CourseConfig()
    langs = data.get("languages", {})
    model = data.get("model", {})
    hints = data.get("hints", {})
    prompts: dict[str, str] = {}
    for lang, table in hints.items():
        code = _normalize_lang(lang, f"[hints.{lang}]")
        prompt = table.get("prompt") if isinstance(table, dict) else None
        if code and isinstance(prompt, str) and prompt.strip():
            prompts[code] = prompt.strip()
    return replace(
        cfg,
        target=_normalize_lang(langs.get("target"), "[languages] target"),
        base=_normalize_lang(langs.get("base"), "[languages] base"),
        model=str(model.get("name", cfg.model)),
        detect_model=model.get("detect"),
        prompts=prompts,
        vad=_dataclass_from(VadParams, cfg.vad, data.get("vad", {}), "[vad]"),
        shaping=_dataclass_from(ShapingParams, cfg.shaping, data.get("chunking", {}), "[chunking]"),
        smoothing=_dataclass_from(
            SmoothingParams, cfg.smoothing, data.get("smoothing", {}), "[smoothing]"
        ),
    )


def load_course_config(course_dir: Path) -> CourseConfig:
    """コースフォルダの `chunkflow.toml` を読む。なければ既定値。"""
    path = course_dir / COURSE_CONFIG_NAME
    if not path.is_file():
        return CourseConfig()
    with path.open("rb") as f:
        return parse_course_config(tomllib.load(f))


def parse_pc_config(data: dict[str, Any]) -> PcConfig:
    """PC ごとの設定の辞書から `PcConfig` を作る（純粋な関数）。

    モデルのリビジョンは 40 桁のコミットハッシュに限る（ブランチ名では固定にならないため）。
    """
    cfg = PcConfig()
    engine = data.get("engine", cfg.engine)
    if engine not in ("auto", "mlx", "faster-whisper"):
        raise ConfigError(f"engine は auto / mlx / faster-whisper のいずれかです: {engine!r}")
    models = {k: dict(v) for k, v in cfg.models.items()}
    for eng, table in data.get("models", {}).items():
        for name, src in table.items():
            repo, rev = src.get("repo"), src.get("revision")
            if not isinstance(repo, str) or not isinstance(rev, str) or not _COMMIT_RE.match(rev):
                raise ConfigError(
                    f'[models.{eng}."{name}"] には repo と、40 桁のコミットハッシュの revision が必要です'
                )
            models.setdefault(eng, {})[name] = ModelSource(repo, rev)
    model_dir = data.get("model_dir")
    return PcConfig(
        engine=engine,
        device=str(data.get("device", cfg.device)),
        compute_type=data.get("compute_type"),
        model_dir=Path(model_dir).expanduser() if model_dir else None,
        models=models,
    )


def load_pc_config(path: Path | None = None) -> PcConfig:
    """PC ごとの設定を読む。なければ既定値。"""
    p = (path or PC_CONFIG_PATH).expanduser()
    if not p.is_file():
        return PcConfig()
    with p.open("rb") as f:
        return parse_pc_config(tomllib.load(f))
