"""パイプライン全体のテスト（エンジンと VAD は偽物。モデルも GPU も使わない）。"""

import json
from pathlib import Path
from typing import ClassVar

from chunkflow_transcriber.audio import DecodedAudio
from chunkflow_transcriber.config import CourseConfig
from chunkflow_transcriber.output import check_document, output_path
from chunkflow_transcriber.pipeline import process_file, run_pipeline, summarize

from .fakes import FakeEngine, Utt, fake_vad, timeline_audio

JA = {"ja": 0.98, "ko": 0.01, "en": 0.01}
KO = {"ko": 0.97, "ja": 0.02, "en": 0.01}


def ja_ko_utts() -> list[Utt]:
    return [
        Utt(1000, 3500, "ja", {"ja": "第三課です。今日は注文を練習しましょう。"}, JA),
        Utt(4300, 6200, "ko", {"ko": "어서 오세요. 뭐 드릴까요?"}, KO),
        # 短い「네」: 判定が揺れて ja 寄り（0.45）。前後が ko なので平滑化で ko になる
        Utt(7000, 7400, "ko", {"ko": "네.", "ja": "ね。"}, {"ja": 0.5, "ko": 0.4, "en": 0.1}),
        Utt(8000, 10000, "ko", {"ko": "아메리카노 한 잔 주세요."}, KO),
        Utt(11000, 11400, "ja", {"ja": "はい。"}, JA),
        Utt(12000, 14500, "ja", {"ja": "もう一度聞いてみましょう。"}, JA),
    ]


def test_ja_ko_pipeline() -> None:
    utts = ja_ko_utts()
    engine = FakeEngine(utts)
    cfg = CourseConfig(target="ko", base="ja")
    res = run_pipeline(timeline_audio(16), cfg, engine, engine, fake_vad(utts))
    langs = [(s.lang, s.text) for s in res.segments]
    assert ("ko", "네. 아메리카노 한 잔 주세요.") in langs or ("ko", "네.") in langs
    assert all(s.lang in ("ja", "ko") for s in res.segments)
    # 言語を指定して認識している（前の文脈を引き継がないよう、チャンクごとに 1 回）
    tr = [c for c in engine.calls if c[0] == "transcribe"]
    assert len(tr) == len(utts)
    assert [c[3] for c in tr] == ["ja", "ko", "ko", "ko", "ja", "ja"]
    # 平滑化が働いたことが記録されている
    assert res.records[2].decision.smoothed
    assert res.records[2].raw_lang == "ja"
    # 時刻の順で重ならない
    for a, b in zip(res.segments, res.segments[1:], strict=False):
        assert a.end_ms <= b.start_ms


def test_script_mismatch_triggers_retry_with_other_language() -> None:
    # en-zh: 中国語の発話を en と誤判定 → en で認識すると漢字が出る → zh で認識し直して採る
    utts = [
        Utt(500, 3000, "en", {"en": " Lesson seven."}, {"en": 0.99, "zh": 0.01}),
        Utt(
            3500,
            6000,
            "zh",
            {"en": "服務員，我們要點菜。", "zh": "服務員，我們要點菜。"},
            {"en": 0.7, "zh": 0.3},
        ),
        Utt(6500, 9000, "en", {"en": " This means, waiter."}, {"en": 0.99, "zh": 0.01}),
    ]
    engine = FakeEngine(utts)
    cfg = CourseConfig(target="zh", base="en")
    res = run_pipeline(timeline_audio(10), cfg, engine, engine, fake_vad(utts))
    r = res.records[1]
    assert not r.initial_script_ok
    assert r.retried and r.adopted_other
    assert r.final_lang == "zh"
    zh = [s for s in res.segments if s.lang == "zh"]
    assert len(zh) == 1
    # 中国語は簡体字を text、繁体字を textVariants.Hant（§7.4.4 の手順 6）
    assert zh[0].text == "服务员，我们要点菜。"
    assert zh[0].variants == {"Hant": "服務員，我們要點菜。"}
    assert all(w.text in zh[0].text for w in zh[0].words)
    s = summarize(res, 10_000)
    assert s["scriptMismatchChunks"] == 1
    assert s["adoptedOtherLang"] == 1


def test_script_mismatch_both_languages_gives_null() -> None:
    utts = [
        Utt(500, 3000, "ko", {"ko": "Hello", "ja": "Hello"}, {"ko": 0.9, "ja": 0.1}),
    ]
    engine = FakeEngine(utts)
    res = run_pipeline(
        timeline_audio(4), CourseConfig(target="ko", base="ja"), engine, engine, fake_vad(utts)
    )
    assert res.records[0].retried and not res.records[0].adopted_other
    assert [s.lang for s in res.segments] == [None]


def test_cantonese_uses_hk_traditional_and_hans_variant() -> None:
    utts = [
        Utt(500, 3000, "en", {"en": " Lesson two."}, {"en": 0.99, "yue": 0.01}),
        Utt(3500, 6000, "yue", {"yue": "佢喺边度做嘢？"}, {"yue": 0.9, "en": 0.05, "zh": 0.05}),
    ]
    engine = FakeEngine(utts)
    res = run_pipeline(
        timeline_audio(7), CourseConfig(target="yue", base="en"), engine, engine, fake_vad(utts)
    )
    yue = next(s for s in res.segments if s.lang == "yue")
    assert yue.text == "佢喺邊度做嘢？"
    assert yue.variants == {"Hans": "佢喺边度做嘢？"}


def test_prompt_hint_is_passed_for_language() -> None:
    utts = [Utt(500, 3000, "yue", {"yue": "係。"}, {"yue": 0.9, "en": 0.1})]

    class Spy(FakeEngine):
        prompts: ClassVar[list] = []

        def transcribe(self, audio, language, prompt=None):  # type: ignore[override]
            self.prompts.append((language, prompt))
            return super().transcribe(audio, language, prompt)

    engine = Spy(utts)
    cfg = CourseConfig(target="yue", base="en", prompts={"yue": "佢哋喺度傾緊偈。"})
    run_pipeline(timeline_audio(4), cfg, engine, engine, fake_vad(utts))
    assert engine.prompts == [("yue", "佢哋喺度傾緊偈。")]


def test_single_language_skips_detection() -> None:
    utts = [Utt(500, 3000, "ko", {"ko": "안녕하세요."}, KO)]
    engine = FakeEngine(utts)
    res = run_pipeline(timeline_audio(4), CourseConfig(target="ko"), engine, engine, fake_vad(utts))
    assert not [c for c in engine.calls if c[0] == "detect"]
    assert [(s.lang, s.lang_prob) for s in res.segments] == [("ko", 1.0)]
    assert res.pair is None


def test_pair_is_estimated_when_not_configured() -> None:
    utts = ja_ko_utts()
    engine = FakeEngine(utts)
    res = run_pipeline(timeline_audio(16), CourseConfig(), engine, engine, fake_vad(utts))
    assert set(res.pair or ()) == {"ja", "ko"}


def test_no_speech_gives_empty_segments() -> None:
    engine = FakeEngine([Utt(0, 1, "en", {}, {"en": 1.0})])
    res = run_pipeline(
        timeline_audio(2), CourseConfig(target="en", base="ja"), engine, engine, lambda a, p: []
    )
    assert res.segments == []


def test_process_file_writes_valid_json(tmp_path: Path) -> None:
    course = tmp_path / "korean-course"
    course.mkdir()
    audio_path = course / "lesson01.mp3"
    audio_path.write_bytes(b"not really audio")
    utts = ja_ko_utts()
    engine = FakeEngine(utts)
    decoded = DecodedAudio(samples=timeline_audio(16), sha256="b" * 64)
    out = output_path(course, audio_path.name)
    outcome = process_file(
        audio_path,
        course,
        CourseConfig(target="ko", base="ja"),
        engine,
        engine,
        engine.info,
        None,
        out,
        fake_vad(utts),
        decoded=decoded,
    )
    data = json.loads(out.read_text(encoding="utf-8"))
    assert check_document(data) == []
    assert data["languages"] == {"target": "ko", "base": "ja"}
    assert data["audio"]["durationMs"] == 16000
    assert data["generator"]["engine"] == "fake-engine 1.0"
    assert data["generator"]["tool"].startswith("chunkflow-transcribe ")
    assert outcome.summary["segments"] == len(data["segments"])
    # 音声は変更しない。書き込むのは transcription/ だけ
    assert audio_path.read_bytes() == b"not really audio"
    assert sorted(p.name for p in course.iterdir()) == ["lesson01.mp3", "transcription"]


def test_to_segments_clamps_to_chunk_bounds() -> None:
    from chunkflow_transcriber.engine.base import EngineSegment, EngineWord
    from chunkflow_transcriber.pipeline import to_segments

    # 認識の窓は 800ms から（余白 200ms）。Whisper が単語を窓の先頭（0 秒）に置いても、
    # VAD のチャンクの開始（1000ms）より前にはしない
    es = EngineSegment(
        0.0, 1.5, " Hello there", (EngineWord(0.0, 0.6, " Hello"), EngineWord(0.7, 1.5, " there"))
    )
    out = to_segments([es], 800, (1000, 2200), "en", 0.9, "en")
    assert out[0].start_ms == 1000
    assert out[0].words[0].start_ms == 1000
    assert out[0].end_ms == 2200


def test_hallucination_is_dropped() -> None:
    from chunkflow_transcriber.engine.base import EngineSegment
    from chunkflow_transcriber.pipeline import is_hallucination

    assert is_hallucination(EngineSegment(0, 1, "a a a a", compression_ratio=3.0))
    assert is_hallucination(EngineSegment(0, 1, "x", avg_logprob=-1.5, no_speech_prob=0.9))
    assert not is_hallucination(EngineSegment(0, 1, "x", avg_logprob=-0.3, no_speech_prob=0.9))
