"""実際の教材でパイプラインを動かし、計測値と人間のレビュー用の表を作る（スパイク S1 後半）。

    uv run python scripts/eval_real.py \
        --course chinese=/path/chinese_training.mp3:zh:ja \
        --course hindi=/path/Hindi_1_Lesson_16.mp3:hi:en \
        --models large-v3-turbo large-v3 \
        --work ~/chunkflow-eval --model-dir .work/models

**教材は著作物なので、音声も文字起こしもリポジトリに置かない。** `--work` にはリポジトリの外のフォルダを
指定する。コースごと・モデルごとに `<work>/<モデル>/<コース>/` を作り、音声へのシンボリックリンクと
`chunkflow.toml` を置いて、そこの `transcription/` に JSON を書かせる。

出力（すべて `<work>/review/` の下）:
- `<コース>.review.csv`: VAD の候補チャンクごとの表（モデルごとの判定・確率・テキストの先頭）。人間が
  `truth` の列に正しい言語を書くと、`scripts/score_review.py` で正解率を計算できる。VAD はモデルに依存
  しないので、候補チャンクはモデル間で共通（1 回の確認で両方のモデルを採点できる）。
- `<モデル>/<コース>.records.json`: チャンクごとの詳しい記録（テキスト全文を含む。リポジトリの外に置く）
- `summary.json`: 処理時間・チャンク数・言語の割合などの集計（テキストを含まない）
"""

from __future__ import annotations

import argparse
import csv
import itertools
import json
import re
import time
from dataclasses import replace
from pathlib import Path
from typing import Any

from chunkflow_transcriber.audio import decode
from chunkflow_transcriber.cli import find_default_schema
from chunkflow_transcriber.config import PcConfig, load_course_config
from chunkflow_transcriber.engine import create_engine
from chunkflow_transcriber.output import load_schema, output_path
from chunkflow_transcriber.pipeline import LOW_PROB, process_file
from chunkflow_transcriber.vad import silero_detector

# Whisper のよく知られた幻覚の定型文（の一部）。見つけたら印を付ける（目安）
KNOWN_HALLUCINATIONS = [
    "ご視聴ありがとうございました",
    "ご視聴ありがとうございます",
    "チャンネル登録",
    "字幕",
    "thank you for watching",
    "thanks for watching",
    "please subscribe",
    "subtitles by",
    "시청해 주셔서 감사합니다",
    "구독",
    "请不吝点赞",
    "订阅",
    "字幕由",
    "amara.org",
]
REPEAT_RE = re.compile(r"(.{2,}?)\1{3,}")


def parse_course(spec: str) -> tuple[str, Path, str, str | None]:
    """`名前=音声のパス:target[:base]` を分解する。"""
    name, rest = spec.split("=", 1)
    parts = rest.rsplit(":", 2)
    if len(parts) == 3:
        audio, target, base = parts
    else:
        audio, target = rest.rsplit(":", 1)
        base = None
    return name, Path(audio).expanduser().resolve(), target, base or None


def prepare_course(
    work: Path, model: str, name: str, audio: Path, target: str, base: str | None
) -> Path:
    """`<work>/<モデル>/<コース>/` に音声へのシンボリックリンクと chunkflow.toml を置く。"""
    course = work / model / name
    course.mkdir(parents=True, exist_ok=True)
    link = course / audio.name
    if not link.exists():
        link.symlink_to(audio)
    toml = f'[languages]\ntarget = "{target}"\n'
    if base:
        toml += f'base = "{base}"\n'
    toml += f'[model]\nname = "{model}"\n'
    (course / "chunkflow.toml").write_text(toml, encoding="utf-8")
    return course


def suspicious(text: str, duration_ms: int) -> list[str]:
    """幻覚の疑いの印（目安）。"""
    flags = []
    low = text.lower()
    if any(h in low for h in KNOWN_HALLUCINATIONS):
        flags.append("known-hallucination")
    if REPEAT_RE.search(text):
        flags.append("repeat")
    if duration_ms > 0 and len(text) / (duration_ms / 1000) > 30:
        flags.append("too-dense")
    return flags


def record_rows(outcome: Any) -> list[dict[str, Any]]:
    """チャンクごとの記録を辞書にする。"""
    rows = []
    for r in outcome.result.records:
        rows.append(
            {
                "startMs": r.start_ms,
                "endMs": r.end_ms,
                "pairProbs": {k: round(v, 4) for k, v in r.pair_probs.items()},
                "raw": r.raw_lang,
                "rawProb": round(r.raw_prob, 4),
                "smoothed": r.decision.lang if r.decision else None,
                "smoothedProb": round(r.decision.prob, 4) if r.decision else None,
                "smoothedChanged": bool(r.decision and r.decision.smoothed),
                "recLang": r.rec_lang,
                "final": r.final_lang,
                "finalProb": None if r.final_prob is None else round(r.final_prob, 4),
                "initialScriptOk": r.initial_script_ok,
                "retried": r.retried,
                "adoptedOther": r.adopted_other,
                "scriptRatio": round(r.script.ratio, 3) if r.script else None,
                "droppedHallucinations": r.dropped_hallucinations,
                "flags": suspicious(r.text, r.duration_ms),
                "text": r.text,
            }
        )
    return rows


def write_review_csv(
    path: Path, models: list[str], per_model: dict[str, list[dict[str, Any]]]
) -> None:
    """人間のレビュー用の表を書く（truth の列は空）。"""
    base_rows = per_model[models[0]]
    header = ["idx", "start", "startMs", "endMs", "durSec"]
    for m in models:
        header += [f"{m}:lang", f"{m}:prob", f"{m}:raw", f"{m}:flags", f"{m}:text"]
    header += ["models_disagree", "truth", "note"]
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        for i, b in enumerate(base_rows):
            row: list[Any] = [
                i,
                f"{b['startMs'] // 60000:02d}:{b['startMs'] // 1000 % 60:02d}.{b['startMs'] % 1000 // 100}",
                b["startMs"],
                b["endMs"],
                round((b["endMs"] - b["startMs"]) / 1000, 2),
            ]
            finals = []
            for m in models:
                r = per_model[m][i]
                assert r["startMs"] == b["startMs"], "候補チャンクがモデル間で一致しません"
                flags = list(r["flags"])
                if not r["initialScriptOk"]:
                    flags.append("script-mismatch")
                if r["adoptedOther"]:
                    flags.append("adopted-other")
                if r["smoothedChanged"]:
                    flags.append("smoothed")
                lang = r["final"] or "?"
                finals.append(lang)
                prob = r["finalProb"] if r["final"] else r["smoothedProb"]
                row += [lang, prob, f"{r['raw']}:{r['rawProb']}", " ".join(flags), r["text"][:20]]
            row += ["yes" if len(set(finals)) > 1 else "", "", ""]
            w.writerow(row)


def summarize_model(outcome: Any, rows: list[dict[str, Any]], decode_sec: float) -> dict[str, Any]:
    """テキストを含まない集計。"""
    res = outcome.result
    dur = outcome.document["audio"]["durationMs"] / 1000
    chunk_ms = [r["endMs"] - r["startMs"] for r in rows]
    segs = outcome.document["segments"]
    by_lang: dict[str, int] = {}
    for s in segs:
        by_lang[s["lang"] or "?"] = by_lang.get(s["lang"] or "?", 0) + s["endMs"] - s["startMs"]
    speech = sum(by_lang.values()) or 1
    seg_ms = sorted(s["endMs"] - s["startMs"] for s in segs)
    gaps = sorted(b["startMs"] - a["endMs"] for a, b in itertools.pairwise(rows))
    return {
        "durationSec": round(dur, 1),
        "timings": {k: round(v, 1) for k, v in res.timings.items()},
        "decodeSec": round(decode_sec, 1),
        "rtf": round(res.timings["total"] / dur, 3),
        "candidateChunks": len(rows),
        "chunksUnder1s": sum(1 for d in chunk_ms if d < 1000),
        "segments": len(segs),
        "segmentMsMedian": seg_ms[len(seg_ms) // 2] if seg_ms else None,
        "segmentMsMax": seg_ms[-1] if seg_ms else None,
        "segmentsOver15s": sum(1 for d in seg_ms if d > 15000),
        "gapMsPercentiles": {
            p: gaps[min(len(gaps) - 1, int(len(gaps) * p / 100))] for p in (10, 25, 50, 75, 90)
        }
        if gaps
        else {},
        "langRatio": {k: round(v / speech, 3) for k, v in sorted(by_lang.items())},
        "lowProbChunks": sum(1 for r in rows if (r["finalProb"] or 0) < LOW_PROB),
        "nullLangChunks": sum(1 for r in rows if r["final"] is None),
        "scriptMismatchChunks": sum(1 for r in rows if not r["initialScriptOk"]),
        "retriedChunks": sum(1 for r in rows if r["retried"]),
        "adoptedOtherLang": sum(1 for r in rows if r["adoptedOther"]),
        "smoothedChanged": sum(1 for r in rows if r["smoothedChanged"]),
        "droppedHallucinations": sum(r["droppedHallucinations"] for r in rows),
        "flagged": {
            f: sum(1 for r in rows if f in r["flags"])
            for f in ("known-hallucination", "repeat", "too-dense")
        },
        "emptyTextChunks": sum(1 for r in rows if not r["text"].strip()),
    }


def main() -> int:
    """エントリポイント。"""
    ap = argparse.ArgumentParser()
    ap.add_argument("--course", action="append", required=True, help="名前=音声:target[:base]")
    ap.add_argument("--models", nargs="+", default=["large-v3-turbo", "large-v3"])
    ap.add_argument("--work", type=Path, required=True, help="リポジトリの外の作業フォルダ")
    ap.add_argument("--model-dir", type=Path, default=Path(".work/models"))
    ap.add_argument("--engine", default="mlx")
    args = ap.parse_args()
    work = args.work.expanduser().resolve()
    repo = Path(__file__).resolve().parents[3]
    if work == repo or repo in work.parents:
        raise SystemExit(
            "--work はリポジトリの外を指定してください（教材の文字起こしをリポジトリに置かない）"
        )
    review = work / "review"
    review.mkdir(parents=True, exist_ok=True)
    schema = load_schema(find_default_schema())
    vad = silero_detector(args.model_dir)
    pc = PcConfig()
    courses = [parse_course(c) for c in args.course]
    summary_path = review / "summary.json"
    summary: dict[str, Any] = (
        json.loads(summary_path.read_text(encoding="utf-8")) if summary_path.exists() else {}
    )
    per_course: dict[str, dict[str, list[dict[str, Any]]]] = {}
    for model in args.models:
        t0 = time.perf_counter()
        engine = create_engine(args.engine, model, pc, args.model_dir)
        load_sec = time.perf_counter() - t0
        (review / model).mkdir(exist_ok=True)
        for name, audio, target, base in courses:
            course = prepare_course(work, model, name, audio, target, base)
            cfg = load_course_config(course)
            cfg = replace(cfg, model=model)
            t1 = time.perf_counter()
            decoded = decode(course / audio.name)
            decode_sec = time.perf_counter() - t1
            outcome = process_file(
                course / audio.name,
                course,
                cfg,
                engine,  # type: ignore[arg-type]
                engine,  # type: ignore[arg-type]
                engine.info,  # type: ignore[attr-defined]
                schema,
                output_path(course, audio.name),
                vad,
                decoded=decoded,
            )
            rows = record_rows(outcome)
            (review / model / f"{name}.records.json").write_text(
                json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8"
            )
            s = summarize_model(outcome, rows, decode_sec)
            s["modelLoadSec"] = round(load_sec, 1)
            summary.setdefault(name, {})[model] = s
            summary_path.write_text(
                json.dumps(summary, ensure_ascii=False, indent=1), encoding="utf-8"
            )
            per_course.setdefault(name, {})[model] = rows
            print(f"{model} {name}: rtf={s['rtf']} chunks={s['candidateChunks']}", flush=True)
    for name, per_model in per_course.items():
        models = [m for m in args.models if m in per_model]
        write_review_csv(review / f"{name}.review.csv", models, per_model)
        print(f"レビュー用の表: {review / f'{name}.review.csv'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
