"""レビュー用の表（`scripts/eval_real.py` が作る CSV）から、言語判定の正解率を計算する。

    uv run python scripts/score_review.py ~/chunkflow-eval/review/hindi.review.csv [...]

人間は CSV の `truth` の列に、候補チャンクの正しい言語を書く。

- 言語コード（`ja`、`ko`、`zh`、`hi`、`en` など）: そのチャンクの言語
- `mix`: 1 つのチャンクに 2 言語が混ざっている（採点の対象外にし、件数を数える）
- `none`: 発話ではない（音楽・効果音・雑音）
- 空欄または `?`: 未確認（対象外）

モデルごとに、最終の判定（`<モデル>:lang`）と、言語ペアに絞っただけの判定（`<モデル>:raw`）の正解率を、
チャンクの長さ 1 秒以上 / 未満に分けて出す。`?`（判定できず null にした）は不正解として数え、件数も出す。
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections import Counter
from pathlib import Path

SKIP = {"", "?"}


def load_rows(paths: list[Path]) -> list[dict[str, str]]:
    """CSV を読む（Excel で保存した BOM 付きの UTF-8 も読める）。"""
    rows: list[dict[str, str]] = []
    for p in paths:
        with p.open(encoding="utf-8-sig", newline="") as f:
            for r in csv.DictReader(f):
                r["_file"] = p.name
                rows.append(r)
    return rows


def models_in(rows: list[dict[str, str]]) -> list[str]:
    """CSV の列名からモデルの名前を取り出す。"""
    if not rows:
        return []
    return [k[: -len(":lang")] for k in rows[0] if k.endswith(":lang")]


def score(rows: list[dict[str, str]], model: str) -> dict[str, object]:
    """1 つのモデルの正解率を計算する（純粋な関数）。"""
    result: dict[str, object] = {}
    counts = Counter()
    confusion: Counter[tuple[str, str]] = Counter()
    for r in rows:
        truth = r.get("truth", "").strip().lower()
        if truth in SKIP:
            counts["unlabeled"] += 1
            continue
        if truth in ("mix", "none"):
            counts[truth] += 1
            continue
        bucket = ">=1s" if float(r["durSec"]) >= 1.0 else "<1s"
        final = r[f"{model}:lang"].strip()
        raw = r[f"{model}:raw"].split(":", 1)[0].strip()
        for kind, pred in (("final", final), ("raw", raw)):
            key = f"{kind} {bucket}"
            counts[f"{key} n"] += 1
            counts[f"{key} ok"] += int(pred == truth)
        if final == "?":
            counts[f"null {bucket}"] += 1
        confusion[(truth, final)] += 1
    for kind in ("final", "raw"):
        for bucket in (">=1s", "<1s"):
            n = counts[f"{kind} {bucket} n"]
            ok = counts[f"{kind} {bucket} ok"]
            result[f"{kind} {bucket}"] = (ok, n, round(ok / n, 4) if n else None)
    result["null"] = {b: counts[f"null {b}"] for b in (">=1s", "<1s")}
    result["excluded"] = {k: counts[k] for k in ("mix", "none", "unlabeled")}
    result["confusion"] = {f"{t}->{p}": n for (t, p), n in sorted(confusion.items())}
    return result


def main() -> int:
    """エントリポイント。"""
    ap = argparse.ArgumentParser(description="レビュー用の表から言語判定の正解率を計算する")
    ap.add_argument("csv", nargs="+", type=Path)
    args = ap.parse_args()
    rows = load_rows(args.csv)
    labeled = sum(1 for r in rows if r.get("truth", "").strip().lower() not in SKIP)
    print(f"チャンク {len(rows)} 件のうち、truth を記入済み {labeled} 件")
    if not labeled:
        print("truth の列が空です。正しい言語を記入してから実行してください。", file=sys.stderr)
        return 1
    for model in models_in(rows):
        s = score(rows, model)
        print(f"\n## {model}")
        for key in ("final >=1s", "final <1s", "raw >=1s", "raw <1s"):
            ok, n, rate = s[key]  # type: ignore[misc]
            print(f"  {key:11s} {ok}/{n}" + (f" ({rate * 100:.1f}%)" if rate is not None else ""))
        print(f"  null（判定できず）: {s['null']}")
        print(f"  対象外: {s['excluded']}")
        print(f"  混同（正解->判定）: {s['confusion']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
