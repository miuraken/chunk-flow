#!/usr/bin/env python3
"""実教材のクリップの「正解の下書き」を、端末内の全エンジンの結果から作る（Chunk Flow / tools/stt-bench）。

sample_files の教材には台本がないため、正解は「各エンジンの結果を突き合わせて、人（AI）が確かめて直したもの」にする。
このスクリプトはその下書きと、確かめるための一覧を作る:

- クリップごとに、各エンジンの結果のうち、ほかの結果との正規化後の誤り率の合計が最も小さいもの（メドイド）を下書きにする。
- 結果どうしの一致の度合い（メドイドとほかの結果の誤り率の平均）を付ける。値が大きいクリップは、正解の信頼性が低い。
- 出力: refs_draft.tsv（id, lang, text, from, agree）と review.txt（クリップごとに全エンジンの結果を並べた一覧）。

下書きを確かめて直したものを refs.tsv として score.py に渡す（text を EXCLUDE にした行は採点から外す）。
教材の文字起こしは著作物なので、出力はコミットしない（sample_files/ の下に置く）。
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

import score  # noqa: E402


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--results", type=Path, nargs="+", required=True)
    parser.add_argument("--manifest", type=Path, required=True, help="計測に使ったクリップ一覧（id, lang, file, ...）")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--exclude-engines", default="", help="下書きの候補にしないエンジン（カンマ区切り）")
    args = parser.parse_args()

    excluded = {e.strip() for e in args.exclude_engines.split(",") if e.strip()}
    results = score.read_results(args.results)
    clips: list[tuple[str, str]] = []
    for i, line in enumerate(args.manifest.read_text(encoding="utf-8").splitlines()):
        if i == 0 or not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        clips.append((parts[0], parts[1]))

    by_clip: dict[str, dict[str, dict]] = {}
    for (engine, clip_id), rec in results.results.items():
        by_clip.setdefault(clip_id, {})[engine] = rec

    # 中国語・広東語は簡体字にそろえてから比べる
    zh_keys, zh_texts = [], []
    for clip_id, lang in clips:
        if lang in ("zh", "yue"):
            for engine, rec in by_clip.get(clip_id, {}).items():
                zh_keys.append((engine, clip_id))
                zh_texts.append(rec.get("text") or "")
    simplified = dict(zip(zh_keys, score.to_simplified(zh_texts)))

    draft_lines = ["id\tlang\ttext\tfrom\tagree"]
    review_lines: list[str] = []
    for clip_id, lang in clips:
        recs = by_clip.get(clip_id, {})
        if not recs:
            continue
        hyps: dict[str, tuple[str, list[str]]] = {}
        for engine, rec in recs.items():
            raw = rec.get("text") or ""
            text = simplified.get((engine, clip_id), raw) if lang in ("zh", "yue") else raw
            hyps[engine] = (raw, score.tokens(score.normalize(text, lang), lang))
        candidates = [e for e in hyps if e not in excluded and hyps[e][1]]
        best_engine, best_cost = None, float("inf")
        for e in candidates:
            cost = 0.0
            for o in hyps:
                if o == e:
                    continue
                ref = hyps[o][1]
                cost += score.edit_distance(hyps[e][1], ref) / max(1, len(hyps[e][1]))
            if cost < best_cost:
                best_engine, best_cost = e, cost
        others = max(1, len(hyps) - 1)
        agree = best_cost / others if best_engine else float("nan")
        draft_text = hyps[best_engine][0] if best_engine else ""
        audio_ms = next(iter(recs.values())).get("audioMs")
        draft_lines.append(f"{clip_id}\t{lang}\t{draft_text}\t{best_engine or '-'}\t{agree:.2f}")
        review_lines.append(f"[{clip_id}] {lang} {audio_ms / 1000 if audio_ms else 0:.1f}s agree={agree:.2f} medoid={best_engine}")
        for engine in score.ENGINE_ORDER:
            if engine in hyps:
                review_lines.append(f"  {engine:10s}: {hyps[engine][0]}")
        review_lines.append("")

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "refs_draft.tsv").write_text("\n".join(draft_lines) + "\n", encoding="utf-8")
    (args.out / "review.txt").write_text("\n".join(review_lines) + "\n", encoding="utf-8")
    print(f"wrote {len(draft_lines) - 1} drafts -> {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
