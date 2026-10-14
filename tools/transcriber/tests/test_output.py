"""JSON の組み立て・検証・書き込みのテスト。"""

import json
import os
from datetime import UTC, datetime
from pathlib import Path

import pytest

from chunkflow_transcriber.engine.base import EngineInfo
from chunkflow_transcriber.model import Segment, Word
from chunkflow_transcriber.output import (
    OutputError,
    build_document,
    check_document,
    existing_tool,
    output_path,
    schema_errors,
    write_atomic,
)

INFO = EngineInfo("mlx-whisper 0.4.3", "large-v3-turbo", "repo", "0" * 40)
SHA = "a" * 64

# Schema（lead が作成中）がない間に、出力の形を確かめるための最小限の Schema
MINI_SCHEMA = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "required": ["format", "version", "audio", "languages", "generator", "segments"],
    "properties": {
        "format": {"const": "chunkflow-transcript"},
        "version": {"const": 1},
        "segments": {
            "type": "array",
            "items": {
                "type": "object",
                "required": ["startMs", "endMs", "lang", "text"],
                "properties": {
                    "startMs": {"type": "integer", "minimum": 0},
                    "endMs": {"type": "integer", "minimum": 1},
                    "lang": {"type": ["string", "null"]},
                    "langProb": {"type": ["number", "null"], "minimum": 0, "maximum": 1},
                    "text": {"type": "string", "minLength": 1},
                    "textVariants": {
                        "type": "object",
                        "propertyNames": {"enum": ["Hans", "Hant"]},
                    },
                },
            },
        },
    },
}


def sample_segments() -> list[Segment]:
    return [
        Segment(1000, 2000, "ja", 0.99, "では、聞いてみましょう。"),
        Segment(
            2500,
            4000,
            "zh",
            0.97,
            "你们好，欢迎来到北京。",
            words=[Word(2500, 3000, "你们好，", 0.95), Word(3100, 4000, "欢迎来到北京。", 0.9)],
            variants={"Hant": "你們好，歡迎來到北京。"},
        ),
        Segment(4500, 4800, None, 0.52, "好"),
    ]


def doc() -> dict:
    return build_document(
        audio_name="lesson01.mp3",
        sha256=SHA,
        duration_ms=5000,
        target="zh",
        base="ja",
        tool="chunkflow-transcribe 0.1.0",
        engine=INFO,
        segments=sample_segments(),
        created_at=datetime(2026, 10, 14, 12, 0, 0, tzinfo=UTC),
        vad={"name": "silero-vad", "version": "6.2.3", "mergeGapMs": 100},
    )


def test_build_document_shape() -> None:
    d = doc()
    assert d["format"] == "chunkflow-transcript"
    assert d["version"] == 1
    assert d["audio"] == {"fileName": "lesson01.mp3", "sha256": SHA, "durationMs": 5000}
    assert d["languages"] == {"target": "zh", "base": "ja"}
    assert d["generator"] == {
        "tool": "chunkflow-transcribe 0.1.0",
        "engine": "mlx-whisper 0.4.3",
        "model": "large-v3-turbo",
        "createdAt": "2026-10-14T12:00:00Z",
        "modelRepo": "repo",
        "modelRevision": "0" * 40,
        "vad": {"name": "silero-vad", "version": "6.2.3", "mergeGapMs": 100},
    }
    s = d["segments"]
    assert s[0] == {
        "startMs": 1000,
        "endMs": 2000,
        "lang": "ja",
        "langProb": 0.99,
        "text": "では、聞いてみましょう。",
    }
    assert s[1]["textVariants"] == {"Hant": "你們好，歡迎來到北京。"}
    assert s[1]["words"][0] == {"startMs": 2500, "endMs": 3000, "text": "你们好，", "prob": 0.95}
    assert s[2]["lang"] is None
    # lang が null なら langProb も null（docs/format/README.md「langProb の意味と値」）
    assert s[2]["langProb"] is None
    assert check_document(d) == []
    assert schema_errors(d, MINI_SCHEMA) == []


def test_single_language_omits_base() -> None:
    d = build_document(
        audio_name="a.mp3",
        sha256=SHA,
        duration_ms=1000,
        target="ko",
        base=None,
        tool="t",
        engine=INFO,
        segments=[],
    )
    assert d["languages"] == {"target": "ko"}


@pytest.mark.parametrize(
    ("mutate", "message"),
    [
        (lambda d: d["segments"][1].update(startMs=1500), "重なって"),
        (lambda d: d["segments"][0].update(endMs=900), "時刻が不正"),
        (lambda d: d["segments"][2].update(endMs=6000), "長さを超えて"),
        (lambda d: d["segments"][0].update(lang="ko"), "言語ペアにありません"),
        (lambda d: d["segments"][0].update(text="Tie\u0302\u0301ng"), "NFC"),
        (lambda d: d["segments"][0].update(langProb=1.5), "langProb"),
        (lambda d: d["audio"].update(sha256="XYZ"), "sha256"),
        (lambda d: d["segments"][1]["words"][0].update(startMs=2000), "範囲外"),
    ],
)
def test_check_document_detects_errors(mutate, message: str) -> None:
    d = doc()
    mutate(d)
    errors = check_document(d)
    assert any(message in e for e in errors), errors


def test_output_path() -> None:
    course = Path("/x/korean-course")
    assert (
        output_path(course, "lesson01.mp3") == course / "transcription" / "lesson01.chunkflow.json"
    )
    with pytest.raises(OutputError):
        output_path(course, "")


def test_write_atomic_writes_only_into_transcription(tmp_path: Path) -> None:
    course = tmp_path / "course"
    course.mkdir()
    out = output_path(course, "lesson01.mp3")
    write_atomic(doc(), out, course, MINI_SCHEMA)
    assert json.loads(out.read_text(encoding="utf-8"))["segments"][1]["lang"] == "zh"
    # 一時ファイルが残っていない、transcription/ の外に何も書いていない
    assert sorted(p.name for p in (course / "transcription").iterdir()) == [
        "lesson01.chunkflow.json"
    ]
    assert sorted(p.name for p in course.iterdir()) == ["transcription"]
    assert existing_tool(out) == "chunkflow-transcribe 0.1.0"
    # 一時ファイルの 0600 のままにしない（umask に従う）
    umask = os.umask(0)
    os.umask(umask)
    assert out.stat().st_mode & 0o777 == 0o666 & ~umask


def test_write_atomic_rejects_path_outside(tmp_path: Path) -> None:
    course = tmp_path / "course"
    course.mkdir()
    with pytest.raises(OutputError):
        write_atomic(doc(), course / "lesson01.chunkflow.json", course, None)
    with pytest.raises(OutputError):
        write_atomic(doc(), course / "transcription" / "lesson01.json", course, None)


def test_write_atomic_rejects_symlinked_transcription_dir(tmp_path: Path) -> None:
    course = tmp_path / "course"
    course.mkdir()
    elsewhere = tmp_path / "elsewhere"
    elsewhere.mkdir()
    os.symlink(elsewhere, course / "transcription")
    with pytest.raises(OutputError):
        write_atomic(doc(), output_path(course, "lesson01.mp3"), course, None)
    assert list(elsewhere.iterdir()) == []


def test_write_atomic_keeps_old_file_when_validation_fails(tmp_path: Path) -> None:
    course = tmp_path / "course"
    (course / "transcription").mkdir(parents=True)
    out = output_path(course, "lesson01.mp3")
    out.write_text('{"old": true}', encoding="utf-8")
    bad = doc()
    bad["segments"][1]["startMs"] = 0  # 重なり
    with pytest.raises(OutputError):
        write_atomic(bad, out, course, None)
    assert json.loads(out.read_text(encoding="utf-8")) == {"old": True}
    assert sorted(p.name for p in (course / "transcription").iterdir()) == [
        "lesson01.chunkflow.json"
    ]


def test_write_atomic_schema_failure(tmp_path: Path) -> None:
    course = tmp_path / "course"
    course.mkdir()
    schema = {**MINI_SCHEMA, "properties": {**MINI_SCHEMA["properties"], "version": {"const": 2}}}
    with pytest.raises(OutputError, match="version"):
        write_atomic(doc(), output_path(course, "a.mp3"), course, schema)
    assert list((course / "transcription").iterdir()) == []


def test_repository_schema_if_present() -> None:
    """lead の Schema（docs/format/）があれば、出力がそれに合格することを確かめる。"""
    from chunkflow_transcriber.cli import find_default_schema
    from chunkflow_transcriber.output import load_schema

    path = find_default_schema()
    if path is None:
        pytest.skip("docs/format/chunkflow-transcript-v1.schema.json がまだありません")
    assert schema_errors(doc(), load_schema(path)) == []
