"""コマンドのテスト（エンジン・VAD・デコードは偽物）。"""

import json
from pathlib import Path

import pytest

from chunkflow_transcriber import cli
from chunkflow_transcriber.audio import DecodedAudio

from .fakes import FakeEngine, Utt, fake_vad, timeline_audio

UTTS = [
    Utt(500, 2500, "ja", {"ja": "第一課です。"}, {"ja": 0.99, "ko": 0.01}),
    Utt(3000, 5000, "ko", {"ko": "안녕하세요."}, {"ko": 0.99, "ja": 0.01}),
]


@pytest.fixture
def course(tmp_path: Path) -> Path:
    c = tmp_path / "korean-course"
    (c / "sub").mkdir(parents=True)
    (c / "lesson01.mp3").write_bytes(b"a")
    (c / "lesson02.m4a").write_bytes(b"b")
    (c / "notes.txt").write_text("x")
    (c / ".hidden.mp3").write_bytes(b"c")
    (c / "sub" / "lesson03.mp3").write_bytes(b"d")
    (c / "chunkflow.toml").write_text('[languages]\ntarget = "ko"\nbase = "ja"\n', encoding="utf-8")
    return c


@pytest.fixture
def fakes(monkeypatch: pytest.MonkeyPatch) -> list[str]:
    created: list[str] = []

    def create_engine(engine, model, pc, model_dir=None):
        created.append(model)
        return FakeEngine(UTTS)

    monkeypatch.setattr(cli, "create_engine", create_engine)
    monkeypatch.setattr(cli, "silero_detector", lambda cache_dir=None: fake_vad(UTTS))
    monkeypatch.setattr(
        "chunkflow_transcriber.pipeline.decode",
        lambda path: DecodedAudio(samples=timeline_audio(6), sha256="c" * 64),
    )
    return created


def test_list_audio_files_only_root(course: Path) -> None:
    assert [p.name for p in cli.list_audio_files(course)] == ["lesson01.mp3", "lesson02.m4a"]


def test_cli_creates_json_and_skips_existing(course: Path, fakes: list[str], capsys) -> None:
    assert cli.main([str(course), "--schema", str(course / "missing.json")]) == 0
    tdir = course / "transcription"
    assert sorted(p.name for p in tdir.iterdir()) == [
        "lesson01.chunkflow.json",
        "lesson02.chunkflow.json",
    ]
    out = capsys.readouterr().out
    assert "korean-course/transcription/" in out
    doc = json.loads((tdir / "lesson01.chunkflow.json").read_text(encoding="utf-8"))
    assert [s["lang"] for s in doc["segments"]] == ["ja", "ko"]
    # 2 回目は飛ばす（エンジンも作らない）
    fakes.clear()
    assert cli.main([str(course), "--schema", str(course / "missing.json")]) == 0
    assert fakes == []
    assert "作成した JSON はありません" in capsys.readouterr().out


def test_cli_rebuilds_json_from_old_tool(course: Path, fakes: list[str]) -> None:
    tdir = course / "transcription"
    tdir.mkdir()
    (tdir / "lesson01.chunkflow.json").write_text(
        json.dumps({"generator": {"tool": "chunkflow-transcribe 0.0.1"}}), encoding="utf-8"
    )
    assert cli.main([str(course / "lesson01.mp3"), "--schema", str(course / "x.json")]) == 0
    doc = json.loads((tdir / "lesson01.chunkflow.json").read_text(encoding="utf-8"))
    assert doc["generator"]["tool"] != "chunkflow-transcribe 0.0.1"
    assert not (tdir / "lesson02.chunkflow.json").exists()


def test_cli_arguments_override_course_config(course: Path, fakes: list[str]) -> None:
    assert (
        cli.main(
            [
                str(course),
                "--target",
                "ko-KR",
                "--base",
                "ja",
                "--model",
                "large-v3",
                "--schema",
                "x",
            ]
        )
        == 0
    )
    assert fakes == ["large-v3"]


def test_cli_rejects_same_target_and_base(course: Path, fakes: list[str]) -> None:
    with pytest.raises(SystemExit):
        cli.main([str(course), "--target", "ko", "--base", "ko"])


def test_cli_rejects_non_audio(course: Path, fakes: list[str]) -> None:
    with pytest.raises(SystemExit):
        cli.main([str(course / "notes.txt")])


def test_schema_missing_warns(course: Path, fakes: list[str], caplog) -> None:
    cli.main([str(course), "--schema", str(course / "missing.json")])
    assert any("Schema" in r.message for r in caplog.records)
