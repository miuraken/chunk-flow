"""`chunkflow-transcript` v1 の JSON の組み立て・検証・書き込み（§7.4.3、§7.4.4 の手順 8）。

書き込むのは、コースフォルダの `transcription/` の `*.chunkflow.json` と、その一時ファイルだけ（§8.2）。
一時ファイルに書いて検証してから名前を変えるので、途中で止まっても壊れた JSON は残らない。
"""

from __future__ import annotations

import json
import logging
import os
import re
import tempfile
import unicodedata
from collections.abc import Sequence
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from .engine.base import EngineInfo
from .langs import to_bcp47
from .model import Segment

log = logging.getLogger(__name__)

FORMAT_NAME = "chunkflow-transcript"
FORMAT_VERSION = 1
OUTPUT_SUFFIX = ".chunkflow.json"
TRANSCRIPTION_DIR = "transcription"
# アプリ側の上限（§8.1）。超えるファイルはアプリが拒否するので、書く前に確かめる
MAX_FILE_BYTES = 20 * 1024 * 1024
MAX_SEGMENTS = 50_000
MAX_SEGMENT_CHARS = 2_000

_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class OutputError(RuntimeError):
    """出力の検証や書き込みに失敗したときの例外。"""


def output_path(course_dir: Path, audio_name: str) -> Path:
    """音声のファイル名から出力先のパスを作る（`lesson01.mp3` → `transcription/lesson01.chunkflow.json`）。"""
    stem = Path(audio_name).stem
    if not stem or "/" in stem or "\\" in stem or stem in (".", ".."):
        raise OutputError(f"出力のファイル名を作れません: {audio_name!r}")
    return course_dir / TRANSCRIPTION_DIR / f"{stem}{OUTPUT_SUFFIX}"


def _round(p: float | None) -> float | None:
    if p is None:
        return None
    return round(min(max(float(p), 0.0), 1.0), 3)


def _nfc(text: str) -> str:
    return unicodedata.normalize("NFC", text)


def segment_to_json(seg: Segment) -> dict[str, Any]:
    """セグメントを JSON の辞書にする。"""
    d: dict[str, Any] = {
        "startMs": int(seg.start_ms),
        "endMs": int(seg.end_ms),
        "lang": to_bcp47(seg.lang) if seg.lang else None,
    }
    lp = _round(seg.lang_prob)
    if lp is not None:
        d["langProb"] = lp
    d["text"] = _nfc(seg.text)
    if seg.variants:
        d["textVariants"] = {k: _nfc(v) for k, v in sorted(seg.variants.items())}
    if seg.words:
        words = []
        for w in seg.words:
            wd: dict[str, Any] = {
                "startMs": int(w.start_ms),
                "endMs": int(w.end_ms),
                "text": _nfc(w.text),
            }
            wp = _round(w.prob)
            if wp is not None:
                wd["prob"] = wp
            words.append(wd)
        d["words"] = words
    return d


def build_document(
    *,
    audio_name: str,
    sha256: str,
    duration_ms: int,
    target: str,
    base: str | None,
    tool: str,
    engine: EngineInfo,
    segments: Sequence[Segment],
    created_at: datetime | None = None,
) -> dict[str, Any]:
    """§7.4.3 の形式の辞書を作る（純粋な関数。`created_at` を渡せば決定的）。"""
    ts = (created_at or datetime.now(UTC)).astimezone(UTC).replace(microsecond=0)
    languages: dict[str, str] = {"target": to_bcp47(target)}
    if base:
        languages["base"] = to_bcp47(base)
    return {
        "format": FORMAT_NAME,
        "version": FORMAT_VERSION,
        "audio": {"fileName": _nfc(audio_name), "sha256": sha256, "durationMs": int(duration_ms)},
        "languages": languages,
        "generator": {
            "tool": tool,
            "engine": engine.engine,
            "model": engine.model,
            "createdAt": ts.strftime("%Y-%m-%dT%H:%M:%SZ"),
        },
        "segments": [segment_to_json(s) for s in segments],
    }


def check_document(doc: dict[str, Any]) -> list[str]:
    """Schema とは別に、形式の規則（§7.4.3 と §7.4.6 の検証）を確かめる。誤りの一覧を返す。

    アプリが拒否する内容を書かないための確認。Schema がまだない間も必ず実行する。
    """
    errors: list[str] = []
    if doc.get("format") != FORMAT_NAME or doc.get("version") != FORMAT_VERSION:
        errors.append("format / version が違います")
    audio = doc.get("audio", {})
    if not _SHA256_RE.match(str(audio.get("sha256", ""))):
        errors.append("audio.sha256 が 64 桁の小文字の 16 進数ではありません")
    duration = audio.get("durationMs")
    if not isinstance(duration, int) or duration <= 0:
        errors.append("audio.durationMs が正の整数ではありません")
        duration = None
    langs = doc.get("languages", {})
    allowed = {langs.get("target"), langs.get("base")} - {None}
    segs = doc.get("segments", [])
    if len(segs) > MAX_SEGMENTS:
        errors.append(f"セグメントが多すぎます（{len(segs)} 件）")
    prev_end = -1
    for i, s in enumerate(segs):
        where = f"segments[{i}]"
        st, en = s.get("startMs"), s.get("endMs")
        if not (isinstance(st, int) and isinstance(en, int)) or st < 0 or st >= en:
            errors.append(f"{where}: 時刻が不正です（{st}, {en}）")
            continue
        if st < prev_end:
            errors.append(f"{where}: 前のセグメントと重なっているか、順序が違います")
        if duration is not None and en > duration:
            errors.append(f"{where}: 音声の長さを超えています")
        prev_end = en
        if s.get("lang") is not None and s.get("lang") not in allowed:
            errors.append(f"{where}: lang が言語ペアにありません: {s.get('lang')}")
        text = s.get("text", "")
        if not text or text != _nfc(text):
            errors.append(f"{where}: text が空か、NFC ではありません")
        if len(text) > MAX_SEGMENT_CHARS:
            errors.append(f"{where}: text が長すぎます")
        lp = s.get("langProb")
        if lp is not None and not (0.0 <= lp <= 1.0):
            errors.append(f"{where}: langProb が 0〜1 ではありません")
        wprev = st
        for j, w in enumerate(s.get("words", [])):
            ws, we = w.get("startMs"), w.get("endMs")
            if not (isinstance(ws, int) and isinstance(we, int)) or ws >= we:
                errors.append(f"{where}.words[{j}]: 時刻が不正です")
            elif ws < wprev or ws < st or we > en:
                errors.append(f"{where}.words[{j}]: セグメントの範囲外か、順序が違います")
            else:
                wprev = ws
    return errors


def load_schema(path: Path | None) -> dict[str, Any] | None:
    """JSON Schema を読む。パスがない・ファイルがなければ None（呼び出し側で警告する）。"""
    if path is None or not path.is_file():
        return None
    with path.open("rb") as f:
        return json.load(f)


def schema_errors(doc: dict[str, Any], schema: dict[str, Any]) -> list[str]:
    """JSON Schema で検証し、誤りの一覧を返す。"""
    from jsonschema.validators import validator_for

    cls = validator_for(schema)
    cls.check_schema(schema)
    validator = cls(schema)
    return [
        f"{'/'.join(str(p) for p in e.absolute_path) or '(root)'}: {e.message}"
        for e in sorted(validator.iter_errors(doc), key=lambda e: list(e.absolute_path))
    ]


def write_atomic(
    doc: dict[str, Any], out_path: Path, course_dir: Path, schema: dict[str, Any] | None
) -> None:
    """一時ファイルに書き、読み戻して検証してから、名前を変えて置く。

    `out_path` がコースフォルダの `transcription/` の直下の `*.chunkflow.json` でなければ書かない。
    """
    tdir = (course_dir / TRANSCRIPTION_DIR).resolve()
    if tdir.parent != course_dir.resolve():
        raise OutputError(
            "transcription/ がコースフォルダの外を指しています（シンボリックリンク？）"
        )
    if out_path.resolve().parent != tdir or not out_path.name.endswith(OUTPUT_SUFFIX):
        raise OutputError(f"書き込み先が transcription/ の外です: {out_path}")
    tdir.mkdir(exist_ok=True)
    data = json.dumps(doc, ensure_ascii=False, indent=1).encode("utf-8") + b"\n"
    if len(data) > MAX_FILE_BYTES:
        raise OutputError(f"JSON が大きすぎます（{len(data)} バイト。上限 {MAX_FILE_BYTES}）")
    fd, tmp_name = tempfile.mkstemp(dir=tdir, prefix=f".{out_path.name}.", suffix=".tmp")
    tmp = Path(tmp_name)
    try:
        # mkstemp は 0600 で作るので、通常のファイルと同じ権限（umask に従う）にする
        umask = os.umask(0)
        os.umask(umask)
        os.chmod(tmp, 0o666 & ~umask)
        with os.fdopen(fd, "wb") as f:
            f.write(data)
            f.flush()
            os.fsync(f.fileno())
        with tmp.open("rb") as f:
            reread = json.load(f)
        errors = check_document(reread)
        if schema is not None:
            errors += schema_errors(reread, schema)
        if errors:
            raise OutputError("出力の検証に失敗しました:\n  " + "\n  ".join(errors[:20]))
        os.replace(tmp, out_path)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise


def existing_tool(out_path: Path) -> str | None:
    """既存の JSON の `generator.tool` を返す（読めなければ None）。"""
    try:
        with out_path.open("rb") as f:
            doc = json.load(f)
        tool = doc.get("generator", {}).get("tool")
        return tool if isinstance(tool, str) else None
    except (OSError, ValueError, AttributeError):
        return None
