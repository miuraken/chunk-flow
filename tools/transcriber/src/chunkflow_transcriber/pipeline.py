"""文字起こしのパイプライン（§7.4.4 の手順 1〜8）。

エンジン（`LanguageDetector` / `Transcriber`）と VAD は引数で受け取り、ここでは共通の処理だけを行う。
テストではエンジンと VAD をモックにして、モデルや GPU なしで全体を動かせる。
"""

from __future__ import annotations

import logging
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

import numpy as np

from . import tool_id
from .audio import SAMPLE_RATE, DecodedAudio, decode, slice_ms
from .chinese import normalize_main, script_forms
from .config import CourseConfig
from .engine.base import EngineInfo, EngineSegment, LanguageDetector, Transcriber
from .langs import restrict_to_pair, top_languages
from .model import CandidateChunk, LangDecision, LangObservation, Segment, Word
from .output import build_document, write_atomic
from .script_check import ScriptCheck, check_script
from .segmenter import normalize_text, shape_sentences, words_text
from .smoothing import smooth_languages
from .vad import SpeechDetector, find_candidate_chunks, recognition_window

log = logging.getLogger(__name__)

# 幻覚とみなすエンジンの出力（§12: 無音の確率や圧縮率が閾値を超える出力は捨てる）
HALLUCINATION_COMPRESSION_RATIO = 2.4
HALLUCINATION_NO_SPEECH = 0.6
HALLUCINATION_LOGPROB = -1.0
LOW_PROB = 0.8


@dataclass
class ChunkRecord:
    """候補チャンクごとの処理の記録（ログと計測に使う。JSON には出さない）。"""

    start_ms: int
    end_ms: int
    pair_probs: dict[str, float] = field(default_factory=dict)
    raw_lang: str | None = None
    raw_prob: float = 0.0
    decision: LangDecision | None = None
    rec_lang: str | None = None
    script: ScriptCheck | None = None
    initial_script_ok: bool = True
    retried: bool = False
    retry_script: ScriptCheck | None = None
    adopted_other: bool = False
    final_lang: str | None = None
    final_prob: float | None = None
    dropped_hallucinations: int = 0
    text: str = ""

    @property
    def duration_ms(self) -> int:
        """長さ（ミリ秒）。"""
        return self.end_ms - self.start_ms


@dataclass
class PipelineResult:
    """パイプラインの結果。"""

    segments: list[Segment]
    records: list[ChunkRecord]
    pair: tuple[str, str] | None
    target: str
    base: str | None
    timings: dict[str, float] = field(default_factory=dict)


def is_hallucination(seg: EngineSegment) -> bool:
    """幻覚の疑いのあるエンジンの出力かを返す。"""
    if (
        seg.compression_ratio is not None
        and seg.compression_ratio > HALLUCINATION_COMPRESSION_RATIO
    ):
        return True
    return (
        seg.no_speech_prob is not None
        and seg.avg_logprob is not None
        and seg.no_speech_prob > HALLUCINATION_NO_SPEECH
        and seg.avg_logprob < HALLUCINATION_LOGPROB
    )


def to_segments(
    engine_segments: Sequence[EngineSegment],
    offset_ms: int,
    window_end_ms: int,
    lang: str | None,
    lang_prob: float | None,
    rec_lang: str,
) -> list[Segment]:
    """エンジンの出力（チャンクの先頭からの秒）を、音声の先頭からのミリ秒のセグメントにする。"""

    def ms(t: float) -> int:
        return min(max(offset_ms + round(t * 1000), offset_ms), window_end_ms)

    out: list[Segment] = []
    for es in engine_segments:
        words = [
            Word(start_ms=ms(w.start), end_ms=ms(w.end), text=w.text, prob=w.prob)
            for w in es.words
            if w.text.strip()
        ]
        if words:
            text = words_text(words, rec_lang)
            start, end = words[0].start_ms, max(w.end_ms for w in words)
        else:
            text = es.text.strip()
            start, end = ms(es.start), ms(es.end)
        if not text:
            continue
        out.append(
            Segment(
                start_ms=start,
                end_ms=max(end, start + 1),
                lang=lang,
                lang_prob=lang_prob,
                text=text,
                words=words,
                rec_lang=rec_lang,
            )
        )
    return out


def _transcribe(
    transcriber: Transcriber,
    audio: np.ndarray,
    lang: str,
    prompts: dict[str, str],
    record: ChunkRecord,
) -> list[EngineSegment]:
    segs = transcriber.transcribe(audio, lang, prompts.get(lang))
    kept = [s for s in segs if not is_hallucination(s)]
    record.dropped_hallucinations += len(segs) - len(kept)
    return kept


def _joined_text(segs: Sequence[EngineSegment]) -> str:
    return " ".join(s.text.strip() for s in segs if s.text.strip())


def estimate_pair(
    detector: LanguageDetector, samples: np.ndarray, chunks: Sequence[CandidateChunk], pad_ms: int
) -> list[str]:
    """言語ペアの指定がないとき、チャンクごとの判定から上位 2 言語を推定する（§7.4.4）。"""
    total = len(samples) * 1000 // SAMPLE_RATE
    probs = []
    for i, c in enumerate(chunks):
        a, b = recognition_window(chunks, i, pad_ms, total)
        probs.append((detector.language_probs(slice_ms(samples, a, b)), c.duration_ms))
    return top_languages(probs, 2)


def run_pipeline(
    samples: np.ndarray,
    cfg: CourseConfig,
    detector: LanguageDetector,
    transcriber: Transcriber,
    speech_detector: SpeechDetector,
    clock: Callable[[], float] = time.perf_counter,
) -> PipelineResult:
    """音声（16kHz・モノラルの float32）から、出力するセグメントを作る（手順 2〜7）。"""
    timings: dict[str, float] = {}
    t0 = clock()
    total_ms = len(samples) * 1000 // SAMPLE_RATE
    chunks = find_candidate_chunks(samples, cfg.vad, speech_detector)
    timings["vad"] = clock() - t0
    pad = cfg.vad.recognition_pad_ms

    # 言語ペアを決める
    target, base = cfg.target, cfg.base
    if target is None and chunks:
        est = estimate_pair(detector, samples, chunks, pad)
        log.warning(
            "言語ペアの指定がないため、推定した上位 2 言語（%s）を使います。誤りやすいので "
            "chunkflow.toml の [languages] か --target / --base で指定してください",
            ", ".join(est),
        )
        target = est[0]
        base = est[1] if len(est) > 1 else None
    if target is None:
        target = "en"  # 発話が 1 つもない音声。セグメントは空になる
    pair = (target, base) if base else None

    # 手順 3: 言語の判定（候補を言語ペアに絞る）と平滑化
    t1 = clock()
    records = [ChunkRecord(start_ms=c.start_ms, end_ms=c.end_ms) for c in chunks]
    windows = [recognition_window(chunks, i, pad, total_ms) for i in range(len(chunks))]
    if pair:
        obs = []
        for i, c in enumerate(chunks):
            a, b = windows[i]
            probs = detector.language_probs(slice_ms(samples, a, b))
            lang, p, norm = restrict_to_pair(probs, pair)
            records[i].pair_probs = norm
            records[i].raw_lang, records[i].raw_prob = lang, p
            obs.append(LangObservation(c.duration_ms, norm[pair[0]], c.start_ms, c.end_ms))
        decisions = smooth_languages(obs, pair, cfg.smoothing)
    else:
        decisions = [LangDecision(lang=target, prob=1.0, best=target) for _ in chunks]
    for r, d in zip(records, decisions, strict=True):
        r.decision = d
    timings["detect"] = clock() - t1

    # 手順 4〜5: 言語を指定した文字起こしと、文字体系の確認
    t2 = clock()
    segments: list[Segment] = []
    for i, _c in enumerate(chunks):
        r, d = records[i], decisions[i]
        a, b = windows[i]
        audio = slice_ms(samples, a, b)
        rec_lang = d.best
        segs = _transcribe(transcriber, audio, rec_lang, cfg.prompts, r)
        check = check_script(_joined_text(segs), rec_lang)
        lang, prob = d.lang, d.prob
        r.initial_script_ok = check.ok
        if not check.ok and pair:
            other = pair[1] if rec_lang == pair[0] else pair[0]
            r.retried = True
            segs2 = _transcribe(transcriber, audio, other, cfg.prompts, r)
            check2 = check_script(_joined_text(segs2), other)
            r.retry_script = check2
            if check2.ok:
                r.adopted_other = True
                segs, rec_lang, check = segs2, other, check2
                lang, prob = other, 1.0 - d.prob
            else:
                if check2.ratio > check.ratio:
                    segs, rec_lang, check = segs2, other, check2
                lang = None
        elif not check.ok:
            log.debug(
                "文字体系が合わないチャンク（単一言語のため言語はそのまま）: %d ms", r.start_ms
            )
        r.rec_lang, r.script = rec_lang, check
        r.final_lang, r.final_prob = lang, prob
        r.text = _joined_text(segs)
        segments.extend(to_segments(segs, a, b, lang, prob, rec_lang))
    timings["transcribe"] = clock() - t2

    # 手順 7: 文単位の整形 → 手順 6: 字体の変換（つないだ後のテキストで作る）と NFC
    shaped = shape_sentences(segments, cfg.shaping)
    final: list[Segment] = []
    for s in shaped:
        words = [
            Word(w.start_ms, w.end_ms, normalize_text(normalize_main(w.text, s.lang)), w.prob)
            for w in s.words
        ]
        words = [w for w in words if w.text]
        forms = script_forms(normalize_text(s.text), s.lang)
        if not forms.main:
            continue
        s.text, s.variants, s.words = forms.main, forms.variants, words
        final.append(s)
    timings["total"] = clock() - t0
    return PipelineResult(
        segments=final, records=records, pair=pair, target=target, base=base, timings=timings
    )


def summarize(result: PipelineResult, duration_ms: int) -> dict[str, Any]:
    """ログに出す品質の目安（§7.4.4「ログ」）。本文は含めない。"""
    by_lang: dict[str, int] = {}
    for s in result.segments:
        key = s.lang or "?"
        by_lang[key] = by_lang.get(key, 0) + s.duration_ms
    speech = sum(by_lang.values()) or 1
    return {
        "durationSec": round(duration_ms / 1000, 1),
        "processSec": round(result.timings.get("total", 0.0), 1),
        "realtimeFactor": round(
            result.timings.get("total", 0.0) / max(duration_ms / 1000, 1e-9), 3
        ),
        "candidateChunks": len(result.records),
        "segments": len(result.segments),
        "langRatio": {k: round(v / speech, 3) for k, v in sorted(by_lang.items())},
        "lowProbSegments": sum(
            1 for s in result.segments if s.lang_prob is not None and s.lang_prob < LOW_PROB
        ),
        "nullLangSegments": sum(1 for s in result.segments if s.lang is None),
        "scriptMismatchChunks": sum(1 for r in result.records if not r.initial_script_ok),
        "retriedChunks": sum(1 for r in result.records if r.retried),
        "adoptedOtherLang": sum(1 for r in result.records if r.adopted_other),
        "droppedHallucinations": sum(r.dropped_hallucinations for r in result.records),
    }


@dataclass
class FileOutcome:
    """1 つの音声ファイルの処理の結果。"""

    audio_path: Path
    out_path: Path
    result: PipelineResult
    summary: dict[str, Any]
    document: dict[str, Any]


def process_file(
    audio_path: Path,
    course_dir: Path,
    cfg: CourseConfig,
    detector: LanguageDetector,
    transcriber: Transcriber,
    engine_info: EngineInfo,
    schema: dict[str, Any] | None,
    out_path: Path,
    speech_detector: SpeechDetector,
    decoded: DecodedAudio | None = None,
    created_at: datetime | None = None,
) -> FileOutcome:
    """音声ファイルを 1 つ処理して、`transcription/` に JSON を置く（手順 1〜8）。"""
    t0 = time.perf_counter()
    audio = decoded or decode(audio_path)
    t_decode = time.perf_counter() - t0
    result = run_pipeline(audio.samples, cfg, detector, transcriber, speech_detector)
    result.timings["decode"] = t_decode
    result.timings["total"] += t_decode
    doc = build_document(
        audio_name=audio_path.name,
        sha256=audio.sha256,
        duration_ms=audio.duration_ms,
        target=result.target,
        base=result.base,
        tool=tool_id(),
        engine=engine_info,
        segments=result.segments,
        created_at=created_at,
    )
    write_atomic(doc, out_path, course_dir, schema)
    return FileOutcome(
        audio_path=audio_path,
        out_path=out_path,
        result=result,
        summary=summarize(result, audio.duration_ms),
        document=doc,
    )
