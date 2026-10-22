#!/usr/bin/env python3
"""切り出した候補（candidates.tsv）に、端末の言語判定（SttBenchActivity の mode=lid）の結果で言語を付け、言語ごとに数をそろえて
計測用のクリップ一覧（real.tsv）を作る（Chunk Flow / tools/stt-bench）。

- 判定の確率がしきい値（既定 0.85）未満のクリップは、2 言語が混ざっている・判定が難しいとみなして使わない。
- 言語ごとに、教材（source）をまたいで均等に、時間の順に選ぶ。
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--candidates", type=Path, default=Path("sample_files/stt-bench/real/candidates.tsv"))
    parser.add_argument("--lid", type=Path, nargs="+", required=True, help="mode=lid の results-*.jsonl")
    parser.add_argument("--out", type=Path, default=Path("sample_files/stt-bench/real/real.tsv"))
    parser.add_argument("--per-lang", type=int, default=36)
    parser.add_argument("--min-confidence", type=float, default=0.85)
    parser.add_argument("--exclude-langs", type=str, default="", help="除外する言語コード（カンマ区切り）")
    args = parser.parse_args()

    exclude_langs = {x.strip() for x in args.exclude_langs.split(",") if x.strip()}
    lid: dict[str, tuple[str, float]] = {}
    for path in args.lid:
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            rec = json.loads(line)
            if rec.get("type") == "lid" and rec.get("detected"):
                lid[rec["clip"]] = (rec["detected"], float(rec.get("confidence") or 0.0))

    rows = [line.split("\t") for line in args.candidates.read_text(encoding="utf-8").splitlines()]
    header, body = rows[0], rows[1:]
    col = {name: i for i, name in enumerate(header)}
    by_lang_source: dict[str, dict[str, list[list[str]]]] = defaultdict(lambda: defaultdict(list))
    by_lang_source_fallback: dict[str, dict[str, list[list[str]]]] = defaultdict(lambda: defaultdict(list))
    by_lang_source_null: dict[str, dict[str, list[list[str]]]] = defaultdict(lambda: defaultdict(list))
    dropped = 0
    for row in body:
        clip_id = row[col["id"]]
        detected = lid.get(clip_id)
        if detected is None:
            target_lang = row[col["lang"]]
            if target_lang not in exclude_langs:
                by_lang_source_null[target_lang][row[col["source"]]].append(row)
            else:
                dropped += 1
            continue
        if detected[1] < 0.50:
            dropped += 1
            continue
        if detected[0] in exclude_langs:
            continue
        if detected[1] >= args.min_confidence:
            by_lang_source[detected[0]][row[col["source"]]].append(row)
        else:
            by_lang_source_fallback[detected[0]][row[col["source"]]].append(row)

    out_lines = ["id\tlang\tfile\tsource\tlidConfidence"]
    all_langs = sorted(set(by_lang_source.keys()) | set(by_lang_source_fallback.keys()) | set(by_lang_source_null.keys()))
    for lang in all_langs:
        all_sources = sorted(
            set(by_lang_source[lang].keys())
            | set(by_lang_source_fallback[lang].keys())
            | set(by_lang_source_null[lang].keys())
        )
        sources: dict[str, list[list[str]]] = {}
        for s in all_sources:
            primary = sorted(by_lang_source[lang][s], key=lambda r: int(r[col["startMs"]]))
            if len(primary) < args.per_lang and by_lang_source_fallback[lang][s]:
                need = args.per_lang - len(primary)
                extra = sorted(
                    by_lang_source_fallback[lang][s],
                    key=lambda r: lid[r[col["id"]]][1],
                    reverse=True,
                )[:need]
                primary = sorted(primary + extra, key=lambda r: int(r[col["startMs"]]))
            if len(primary) < args.per_lang and by_lang_source_null[lang][s]:
                need = args.per_lang - len(primary)
                extra_null = pick_evenly(
                    sorted(by_lang_source_null[lang][s], key=lambda r: int(r[col["startMs"]])),
                    need,
                )
                primary = sorted(primary + extra_null, key=lambda r: int(r[col["startMs"]]))
            sources[s] = primary
        quotas = allocate(args.per_lang, {s: len(rows) for s, rows in sources.items()})
        picked: list[list[str]] = []
        for source, rows in sources.items():
            picked.extend(pick_evenly(rows, quotas[source]))
        for row in sorted(picked, key=lambda r: r[col["id"]]):
            clip_id = row[col["id"]]
            conf = lid.get(clip_id, (lang, 0.50))[1]
            out_lines.append(f"{clip_id}\t{lang}\t{row[col['file']]}\t{row[col['source']]}\t{conf:.3f}")
        print(f"{lang}: {len(picked)} clips {quotas}", file=sys.stderr)
    args.out.write_text("\n".join(out_lines) + "\n", encoding="utf-8")
    print(f"dropped (low confidence / no result): {dropped}; wrote {len(out_lines) - 1} -> {args.out}", file=sys.stderr)
    return 0


def allocate(total: int, available: dict[str, int]) -> dict[str, int]:
    """total 件を教材ごとに均等に割り当てる（足りない教材の残りは、ほかの教材に回す）。"""
    quotas = {s: 0 for s in available}
    remaining = total
    open_sources = [s for s in sorted(available) if available[s] > 0]
    while remaining > 0 and open_sources:
        share = max(1, remaining // len(open_sources))
        for source in list(open_sources):
            if remaining <= 0:
                break
            take = min(share, available[source] - quotas[source], remaining)
            quotas[source] += take
            remaining -= take
            if quotas[source] >= available[source]:
                open_sources.remove(source)
    return quotas


def pick_evenly(items: list, count: int) -> list:
    """items から count 件を、最初から最後まで均等に選ぶ。"""
    if count <= 0:
        return []
    if len(items) <= count:
        return list(items)
    if count == 1:
        return [items[len(items) // 2]]
    step = (len(items) - 1) / (count - 1)
    return [items[round(i * step)] for i in range(count)]


if __name__ == "__main__":
    sys.exit(main())
