"""合成音声でパイプラインを計測する（スパイク S1 用）。

    uv run python scripts/evaluate.py [--models large-v3-turbo large-v3] [--only ja-ko ...]
        [--data .work/synthetic] [--model-dir .work/models] [--out .work/eval]

`scripts/make_synthetic.py` で作ったシナリオ（コースフォルダ）ごとに PC ツールのパイプラインを動かし、
正解（`truth.json`）と比べる。JSON は各シナリオの `transcription/` に書かれる。計測の結果は
`--out` のフォルダ（.gitignore 済み）に JSON で保存し、Markdown の表を標準出力に出す。

計測する項目:
- 候補チャンク（VAD）ごとの言語判定の正解率（平滑化の前 / 後 / 文字体系の確認の後）。1 秒以上 / 未満
- 正解の発話ごとの、出力セグメントの言語の正解率（1 秒以上 / 未満）と、欠落
- 文字体系の確認が働いた件数（再認識した件数、もう一方の言語を採った件数）
- 処理時間（音声の長さに対する比）
- 文字誤り率（CER。句読点と空白を除いて比べる。目安）
"""

from __future__ import annotations

import argparse
import json
import platform
import re
import time
import unicodedata
from pathlib import Path
from typing import Any

from chunkflow_transcriber.audio import decode
from chunkflow_transcriber.chinese import normalize_main
from chunkflow_transcriber.cli import find_default_schema
from chunkflow_transcriber.config import PcConfig, load_course_config
from chunkflow_transcriber.engine import create_engine
from chunkflow_transcriber.output import load_schema, output_path
from chunkflow_transcriber.pipeline import process_file
from chunkflow_transcriber.vad import silero_detector

_PUNCT = re.compile(r"[\W_]+", re.UNICODE)
YUE_COLLOQUIAL = set("佢喺嘅咗唔冇哋嘢㗎啦呀嗰啲乜嚟睇噉咁囉喎嘞")


def norm_for_cer(text: str, lang: str) -> str:
    """CER 用に正規化する（NFC、小文字、句読点と空白を除く。中国語・広東語は字体を揃える）。"""
    t = unicodedata.normalize("NFC", text).lower()
    if lang in ("zh", "yue"):
        t = normalize_main(t, lang)
    return _PUNCT.sub("", t)


def edit_distance(a: str, b: str) -> int:
    """レーベンシュタイン距離。"""
    if len(a) < len(b):
        a, b = b, a
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (ca != cb)))
        prev = cur
    return prev[-1]


def overlap(a0: int, a1: int, b0: int, b1: int) -> int:
    """区間の重なり（ミリ秒）。"""
    return max(0, min(a1, b1) - max(a0, b0))


def bucket(ms: int) -> str:
    """1 秒以上 / 未満の区分。"""
    return ">=1s" if ms >= 1000 else "<1s"


def acc_add(table: dict[str, list[int]], key: str, ok: bool) -> None:
    """正解数と件数を数える。"""
    t = table.setdefault(key, [0, 0])
    t[0] += int(ok)
    t[1] += 1


def evaluate_scenario(course: Path, outcome: Any) -> dict[str, Any]:
    """1 つのシナリオの結果を正解と比べる。"""
    truth = json.loads((course / "truth.json").read_text(encoding="utf-8"))
    utts = truth["utterances"]
    res = outcome.result
    chunk_acc: dict[str, dict[str, list[int]]] = {"raw": {}, "smoothed": {}, "final": {}}
    mixed_chunks = 0
    chunk_rows = []
    for r in res.records:
        ov = [(overlap(r.start_ms, r.end_ms, u["startMs"], u["endMs"]), u) for u in utts]
        ov = [(o, u) for o, u in ov if o > 0]
        if not ov:
            chunk_rows.append({"start": r.start_ms, "end": r.end_ms, "truth": None})
            continue
        langs = {u["lang"] for _, u in ov}
        if len(langs) > 1:
            mixed_chunks += 1
        truth_lang = max(ov, key=lambda t: t[0])[1]["lang"]
        b = bucket(r.duration_ms)
        acc_add(chunk_acc["raw"], b, r.raw_lang == truth_lang)
        acc_add(chunk_acc["smoothed"], b, r.decision is not None and r.decision.lang == truth_lang)
        acc_add(chunk_acc["final"], b, r.final_lang == truth_lang)
        chunk_rows.append(
            {
                "start": r.start_ms,
                "end": r.end_ms,
                "truth": truth_lang,
                "mixed": len(langs) > 1,
                "raw": r.raw_lang,
                "rawProb": round(r.raw_prob, 3),
                "smoothed": r.decision.lang if r.decision else None,
                "smoothedProb": round(r.decision.prob, 3) if r.decision else None,
                "final": r.final_lang,
                "retried": r.retried,
                "adoptedOther": r.adopted_other,
                "scriptRatio": round(r.script.ratio, 2) if r.script else None,
                "text": r.text,
            }
        )
    seg_acc: dict[str, list[int]] = {}
    missing = 0
    cer_by_lang: dict[str, list[int]] = {}
    utt_rows = []
    segs = outcome.document["segments"]
    for u in utts:
        ov = [(overlap(u["startMs"], u["endMs"], s["startMs"], s["endMs"]), s) for s in segs]
        ov = [(o, s) for o, s in ov if o > 0]
        b = bucket(u["endMs"] - u["startMs"])
        if not ov:
            missing += 1
            acc_add(seg_acc, b, False)
            utt_rows.append({"truth": u["lang"], "text": u["text"], "out": None})
            continue
        best = max(ov, key=lambda t: t[0])[1]
        acc_add(seg_acc, b, best["lang"] == u["lang"])
        hyp_text = "".join(s["text"] for _, s in sorted(ov, key=lambda t: t[1]["startMs"]))
        ref = norm_for_cer(u["text"], u["lang"])
        hyp = norm_for_cer(hyp_text, u["lang"])
        d = edit_distance(ref, hyp)
        c = cer_by_lang.setdefault(u["lang"], [0, 0])
        c[0] += d
        c[1] += len(ref)
        utt_rows.append(
            {
                "truth": u["lang"],
                "durMs": u["endMs"] - u["startMs"],
                "text": u["text"],
                "out": best["lang"],
                "outText": hyp_text,
                "startErrMs": best["startMs"] - u["startMs"],
            }
        )
    # 広東語の口語の字の出現（正解と出力）
    yue = None
    if truth["target"] == "yue":
        ref_n = sum(
            1 for u in utts if u["lang"] == "yue" for ch in u["text"] if ch in YUE_COLLOQUIAL
        )
        hyp_n = sum(
            1 for s in segs if s["lang"] == "yue" for ch in s["text"] if ch in YUE_COLLOQUIAL
        )
        yue = {"colloquialCharsTruth": ref_n, "colloquialCharsOutput": hyp_n}
    # 字体の変換が働いた件数（エンジンの生の出力と、text の字体が違ったチャンク）
    conv = sum(
        1
        for r in res.records
        if r.final_lang in ("zh", "yue") and normalize_main(r.text, r.final_lang) != r.text
    )
    start_errs = [abs(r["startErrMs"]) for r in utt_rows if r.get("startErrMs") is not None]
    return {
        "scenario": truth["scenario"],
        "durationSec": truth["durationMs"] / 1000,
        "utterances": len(utts),
        "candidateChunks": len(res.records),
        "mixedChunks": mixed_chunks,
        "segments": len(segs),
        "chunkAccuracy": chunk_acc,
        "utteranceAccuracy": seg_acc,
        "missingUtterances": missing,
        "retried": sum(1 for r in res.records if r.retried),
        "adoptedOther": sum(1 for r in res.records if r.adopted_other),
        "scriptMismatch": sum(1 for r in res.records if not r.initial_script_ok),
        "nullLang": sum(1 for s in segs if s["lang"] is None),
        "smoothedChanged": sum(1 for r in res.records if r.decision and r.decision.smoothed),
        "hanziConverted": conv,
        "cer": {k: round(v[0] / max(v[1], 1), 3) for k, v in cer_by_lang.items()},
        "medianStartErrMs": sorted(start_errs)[len(start_errs) // 2] if start_errs else None,
        "timings": {k: round(v, 2) for k, v in res.timings.items()},
        "rtf": round(res.timings["total"] / (truth["durationMs"] / 1000), 3),
        "yue": yue,
        "chunks": chunk_rows,
        "utteranceRows": utt_rows,
    }


def fmt_acc(t: dict[str, list[int]], key: str) -> str:
    """正解率の表示。"""
    if key not in t:
        return "-"
    ok, n = t[key]
    return f"{ok}/{n} ({100 * ok / n:.0f}%)"


def main() -> int:
    """エントリポイント。"""
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", nargs="+", default=["large-v3-turbo"])
    ap.add_argument("--only", nargs="*")
    ap.add_argument("--data", type=Path, default=Path(".work/synthetic"))
    ap.add_argument("--model-dir", type=Path, default=Path(".work/models"))
    ap.add_argument("--out", type=Path, default=Path(".work/eval"))
    ap.add_argument("--engine", default="mlx")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    pc = PcConfig()
    schema = load_schema(find_default_schema())
    print(f"Schema: {'あり' if schema else 'なし（形式の規則の確認だけ）'}")
    vad = silero_detector(args.model_dir)
    courses = sorted(p for p in args.data.iterdir() if (p / "truth.json").is_file())
    if args.only:
        courses = [c for c in courses if c.name in args.only]
    for model in args.models:
        t_load = time.perf_counter()
        engine = create_engine(args.engine, model, pc, args.model_dir)
        load_sec = time.perf_counter() - t_load
        results = []
        for course in courses:
            cfg = load_course_config(course)
            audio_path = course / "lesson.mp3"
            decoded = decode(audio_path)
            outcome = process_file(
                audio_path,
                course,
                cfg,
                engine,
                engine,
                engine.info,
                schema,  # type: ignore[arg-type]
                output_path(course, audio_path.name),
                vad,
                decoded=decoded,
            )
            ev = evaluate_scenario(course, outcome)
            results.append(ev)
            print(f"  {model} {course.name}: rtf={ev['rtf']} retried={ev['retried']}", flush=True)
        report = {
            "model": model,
            "engine": engine.info.engine,
            "modelRepo": engine.info.model_repo,
            "modelRevision": engine.info.model_revision,
            "machine": f"{platform.platform()} {platform.machine()}",
            "loadSec": round(load_sec, 1),
            "results": results,
        }
        (args.out / f"{model}.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8"
        )
        print(f"\n### {model}（{engine.info.engine}、モデルの読み込み {load_sec:.1f} 秒）\n")
        print(
            "| シナリオ | 長さ(秒) | 発話 | 候補チャンク(混在) | 判定 生 ≥1s | 生 <1s | 平滑化後 ≥1s | 平滑化後 <1s"
            " | 最終 ≥1s | 最終 <1s | 発話単位 ≥1s | 発話単位 <1s | 文字体系 不一致/再認識/採用 | null | RTF | CER |"
        )
        print("|" + "---|" * 16)
        for ev in results:
            ca, ua = ev["chunkAccuracy"], ev["utteranceAccuracy"]
            print(
                f"| {ev['scenario']} | {ev['durationSec']:.0f} | {ev['utterances']} | "
                f"{ev['candidateChunks']}({ev['mixedChunks']}) | "
                f"{fmt_acc(ca['raw'], '>=1s')} | {fmt_acc(ca['raw'], '<1s')} | "
                f"{fmt_acc(ca['smoothed'], '>=1s')} | {fmt_acc(ca['smoothed'], '<1s')} | "
                f"{fmt_acc(ca['final'], '>=1s')} | {fmt_acc(ca['final'], '<1s')} | "
                f"{fmt_acc(ua, '>=1s')} | {fmt_acc(ua, '<1s')} | "
                f"{ev['scriptMismatch']}/{ev['retried']}/{ev['adoptedOther']} | {ev['nullLang']} | "
                f"{ev['rtf']} | {ev['cer']} |"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
