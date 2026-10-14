"""docs/format/ の JSON Schema とサンプルが期待どおりかを確かめる（設計書 §7.4.3、§9 の「契約」）。

- 正しいサンプル（docs/format/examples/*.chunkflow.json）: 読み込み・JSON Schema・意味の検証のすべてに合格すること。
- 不正なサンプル（docs/format/examples/invalid/*.chunkflow.json）: expectations.json に書いた段階
  （parse / schema / semantic）で初めて不合格になること（それより前の段階には合格すること）。

意味の検証（validate_semantics）は docs/format/README.md の「意味の検証」の参照実装。

実行: uv run --no-project --with jsonschema python .github/scripts/validate_transcript_examples.py
"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from typing import Any

from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parents[2]
FORMAT_DIR = ROOT / "docs" / "format"
SCHEMA_PATH = FORMAT_DIR / "chunkflow-transcript-v1.schema.json"
EXAMPLES_DIR = FORMAT_DIR / "examples"
INVALID_DIR = EXAMPLES_DIR / "invalid"

MAX_FILE_BYTES = 20 * 1024 * 1024  # §8.1
MAX_DEPTH = 64  # README の「読み込みの上限」


class Rejected(Exception):
    """検証で拒否されたことを表す。layer は parse / schema / semantic のいずれか。"""

    def __init__(self, layer: str, message: str) -> None:
        super().__init__(f"[{layer}] {message}")
        self.layer = layer


def max_nesting_depth(text: str) -> int:
    """JSON の文字列リテラルの外にある括弧の入れ子の深さを、再帰を使わずに数える。"""
    depth = 0
    deepest = 0
    in_string = False
    escaped = False
    for ch in text:
        if in_string:
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                in_string = False
        elif ch == '"':
            in_string = True
        elif ch in "[{":
            depth += 1
            deepest = max(deepest, depth)
        elif ch in "]}":
            depth -= 1
    return deepest


def parse(data: bytes) -> Any:
    """読み込みの段階: サイズ、UTF-8、入れ子の深さ、JSON の構文。"""
    if len(data) > MAX_FILE_BYTES:
        raise Rejected("parse", "ファイルが 20MB を超える")
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError as e:
        raise Rejected("parse", f"UTF-8 として不正: {e}") from e
    if max_nesting_depth(text) > MAX_DEPTH:
        raise Rejected("parse", f"入れ子の深さが {MAX_DEPTH} を超える")
    try:
        return json.loads(text)
    except json.JSONDecodeError as e:
        raise Rejected("parse", f"JSON として不正: {e}") from e


def validate_schema(validator: Draft202012Validator, doc: Any) -> None:
    """JSON Schema の段階。"""
    errors = sorted(validator.iter_errors(doc), key=lambda e: list(e.absolute_path))
    if errors:
        first = errors[0]
        path = "/".join(str(p) for p in first.absolute_path)
        raise Rejected("schema", f"{path}: {first.message[:120]}")


def validate_semantics(doc: dict[str, Any]) -> None:
    """意味の検証の段階（JSON Schema に合格した文書が前提）。README.md の S1〜S6 に対応する。"""
    languages = doc["languages"]
    target = languages["target"]
    base = languages.get("base")
    # S1: target と base は異なる
    if base is not None and base == target:
        raise Rejected("semantic", "S1: languages.target と languages.base が同じ")
    allowed = {target} if base is None else {target, base}
    duration = doc["audio"]["durationMs"]
    prev_end: int | None = None
    for i, seg in enumerate(doc["segments"]):
        start, end = seg["startMs"], seg["endMs"]
        # S2: startMs < endMs
        if start >= end:
            raise Rejected("semantic", f"S2: segments/{i}: startMs >= endMs")
        # S3: 開始時刻の順で重ならない
        if prev_end is not None and start < prev_end:
            raise Rejected("semantic", f"S3: segments/{i}: 前のセグメントと重なる、または順序が逆")
        prev_end = end
        # S4: 音声の長さを超えない
        if end > duration:
            raise Rejected("semantic", f"S4: segments/{i}: endMs が audio.durationMs を超える")
        # S5: lang は言語ペアのどちらか（null は可）
        lang = seg.get("lang")
        if lang is not None and lang not in allowed:
            raise Rejected("semantic", f"S5: segments/{i}: lang が言語ペアにない")
        # S6: 単語はセグメントの範囲内で、開始時刻の順で重ならず、startMs < endMs
        prev_word_end = start
        for j, word in enumerate(seg.get("words", [])):
            ws, we = word["startMs"], word["endMs"]
            if ws >= we or ws < prev_word_end or we > end:
                raise Rejected("semantic", f"S6: segments/{i}/words/{j}: 単語の時刻が不正")
            prev_word_end = we


def check(validator: Draft202012Validator, path: Path) -> None:
    """1 ファイルをすべての段階で検証する。拒否されたら Rejected を投げる。"""
    doc = parse(path.read_bytes())
    validate_schema(validator, doc)
    validate_semantics(doc)


def main() -> int:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=Draft202012Validator.FORMAT_CHECKER)
    failures: list[str] = []

    valid_files = sorted(EXAMPLES_DIR.glob("*.chunkflow.json"))
    for path in valid_files:
        try:
            check(validator, path)
            print(f"OK    valid    {path.name}")
        except Rejected as e:
            failures.append(f"正しいサンプルが拒否された: {path.name}: {e}")

    expectations = json.loads((INVALID_DIR / "expectations.json").read_text(encoding="utf-8"))["files"]
    expected = {e["file"]: e for e in expectations}
    invalid_files = sorted(INVALID_DIR.glob("*.chunkflow.json"))
    for path in invalid_files:
        exp = expected.pop(path.name, None)
        if exp is None:
            failures.append(f"expectations.json にない不正なサンプル: {path.name}")
            continue
        try:
            check(validator, path)
            failures.append(f"不正なサンプルが合格した: {path.name}")
        except Rejected as e:
            if e.layer != exp["layer"]:
                failures.append(f"拒否された段階が違う: {path.name}: 期待 {exp['layer']}、実際 {e}")
            else:
                print(f"OK    invalid  {path.name}  {e}")
    for name in expected:
        failures.append(f"expectations.json にあるがファイルがない: {name}")

    print(f"\n正しいサンプル {len(valid_files)} 件、不正なサンプル {len(invalid_files)} 件")
    for f in failures:
        print(f"NG    {f}", file=sys.stderr)
    return 1 if failures or not valid_files or not invalid_files else 0


if __name__ == "__main__":
    sys.exit(main())
