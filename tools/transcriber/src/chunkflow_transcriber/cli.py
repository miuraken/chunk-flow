"""コマンド `chunkflow-transcribe`（§7.4.4）。

    chunkflow-transcribe <コースフォルダ（複数可） | 音声ファイル> [--target ko] [--base ja]
        [--model large-v3-turbo] [--engine auto|mlx|faster-whisper] [--force]

JSON がすでにある音声は飛ばす（`--force` で作り直す）。古い版のツールで作った JSON は作り直す。
最後に、作成した JSON の一覧とアップロード先（`<コース>/transcription/`）を表示する。
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from dataclasses import replace
from pathlib import Path

from . import __version__, tool_id
from .audio import AUDIO_EXTENSIONS, AudioError
from .config import ConfigError, CourseConfig, load_course_config, load_pc_config
from .engine import create_engine, resolve_engine_name
from .langs import UnknownLanguageError, to_whisper_code
from .output import OutputError, existing_tool, load_schema, output_path
from .pipeline import process_file
from .vad import SpeechDetector, silero_detector

log = logging.getLogger("chunkflow_transcribe")

SCHEMA_RELATIVE = Path("docs/format/chunkflow-transcript-v1.schema.json")


def find_default_schema() -> Path | None:
    """リポジトリの `docs/format/` の JSON Schema を探す（パッケージの場所から上にたどる）。"""
    here = Path(__file__).resolve()
    for parent in here.parents:
        candidate = parent / SCHEMA_RELATIVE
        if candidate.is_file():
            return candidate
    return None


def list_audio_files(course_dir: Path) -> list[Path]:
    """コースフォルダの直下の音声ファイル（サブフォルダは見ない。隠しファイルは除く）。"""
    return sorted(
        p
        for p in course_dir.iterdir()
        if p.is_file() and not p.name.startswith(".") and p.suffix.lower() in AUDIO_EXTENSIONS
    )


def collect_jobs(paths: list[Path]) -> list[tuple[Path, list[Path]]]:
    """引数をコースフォルダごとの音声ファイルの一覧にする。"""
    jobs: dict[Path, list[Path]] = {}
    for p in paths:
        p = p.expanduser()
        if p.is_dir():
            jobs.setdefault(p, []).extend(list_audio_files(p))
        elif p.is_file() and p.suffix.lower() in AUDIO_EXTENSIONS:
            jobs.setdefault(p.parent, []).append(p)
        else:
            raise SystemExit(f"コースフォルダでも音声ファイルでもありません: {p}")
    return [(course, sorted(set(files))) for course, files in jobs.items()]


def build_parser() -> argparse.ArgumentParser:
    """引数の定義。"""
    ap = argparse.ArgumentParser(
        prog="chunkflow-transcribe",
        description="音声を文字起こしして <コース>/transcription/*.chunkflow.json を作る",
    )
    ap.add_argument(
        "paths", nargs="+", type=Path, help="コースフォルダ（複数可）または音声ファイル"
    )
    ap.add_argument("--target", help="学習する言語（例: ko、zh、yue）")
    ap.add_argument("--base", help="解説の言語（例: ja、en）。省略すると単一言語の教材")
    ap.add_argument(
        "--model", help="文字起こしのモデル（既定: large-v3-turbo。広東語を含むコースは large-v3）"
    )
    ap.add_argument("--detect-model", help="言語判定のモデル（既定: --model と同じ）")
    ap.add_argument("--engine", choices=["auto", "mlx", "faster-whisper"], help="エンジン")
    ap.add_argument("--force", action="store_true", help="JSON があっても作り直す")
    ap.add_argument(
        "--schema", type=Path, help="JSON Schema のパス（既定: リポジトリの docs/format/）"
    )
    ap.add_argument("--config", type=Path, help="PC ごとの設定のパス")
    ap.add_argument(
        "--model-dir", type=Path, help="モデルの保存先（既定: Hugging Face のキャッシュ）"
    )
    ap.add_argument("-v", "--verbose", action="store_true", help="詳しいログ")
    ap.add_argument("--version", action="version", version=tool_id())
    return ap


def apply_args(cfg: CourseConfig, args: argparse.Namespace) -> CourseConfig:
    """コマンドの引数をコースの設定に上書きする（引数が優先）。"""
    try:
        target = to_whisper_code(args.target) if args.target else cfg.target
        base = to_whisper_code(args.base) if args.base else cfg.base
    except UnknownLanguageError as e:
        raise SystemExit(str(e)) from None
    if target and base and target == base:
        raise SystemExit("--target と --base が同じ言語です")
    return replace(
        cfg,
        target=target,
        base=base,
        model=args.model or cfg.model,
        detect_model=args.detect_model or cfg.detect_model,
    )


def main(argv: list[str] | None = None) -> int:
    """エントリポイント。"""
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(asctime)s %(levelname)s %(message)s",
        stream=sys.stderr,
    )
    try:
        pc = load_pc_config(args.config)
    except (ConfigError, OSError, ValueError) as e:
        log.error("PC ごとの設定を読めません: %s", e)
        return 2
    engine_name = resolve_engine_name(args.engine or pc.engine)

    schema_path = args.schema or find_default_schema()
    schema = load_schema(schema_path)
    if schema is None:
        log.warning(
            "JSON Schema が見つからないため、Schema による検証を飛ばします（形式の規則の確認は行います）: %s",
            schema_path or SCHEMA_RELATIVE,
        )

    engines: dict[str, object] = {}
    vad: list[SpeechDetector] = []

    def get_vad() -> SpeechDetector:
        if not vad:
            vad.append(silero_detector(args.model_dir or pc.model_dir))
        return vad[0]

    def get_engine(model: str) -> object:
        if model not in engines:
            log.info("エンジン %s でモデル %s を準備します", engine_name, model)
            engines[model] = create_engine(engine_name, model, pc, args.model_dir)
        return engines[model]

    written: list[Path] = []
    failed = 0
    for course_dir, files in collect_jobs(args.paths):
        try:
            cfg = apply_args(load_course_config(course_dir), args)
        except (ConfigError, OSError, ValueError) as e:
            log.error("%s の chunkflow.toml を読めません: %s", course_dir, e)
            failed += 1
            continue
        for audio in files:
            out = output_path(course_dir, audio.name)
            if out.exists() and not args.force:
                prev = existing_tool(out)
                if prev == tool_id():
                    log.info("飛ばします（JSON があります。作り直すには --force）: %s", out.name)
                    continue
                log.info("古い版（%s）で作った JSON を作り直します: %s", prev, out.name)
            try:
                transcriber = get_engine(cfg.effective_model)
                detector = get_engine(cfg.effective_detect_model)
                outcome = process_file(
                    audio,
                    course_dir,
                    cfg,
                    detector,  # type: ignore[arg-type]
                    transcriber,  # type: ignore[arg-type]
                    transcriber.info,  # type: ignore[attr-defined]
                    schema,
                    out,
                    get_vad(),
                )
            except (AudioError, OutputError, ConfigError, RuntimeError) as e:
                log.error("失敗しました: %s: %s", audio.name, e)
                failed += 1
                continue
            log.info("%s: %s", audio.name, json.dumps(outcome.summary, ensure_ascii=False))
            written.append(out)

    if written:
        print(
            "\n作成した JSON（Google Drive の同じコースの transcription/ にアップロードしてください）:"
        )
        for p in written:
            print(f"  {p}")
        for d in sorted({p.parent for p in written}):
            print(f"アップロード先: {d.parent.name}/transcription/")
    else:
        print("作成した JSON はありません。")
    print(f"(chunkflow-transcribe {__version__})")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
