#!/usr/bin/env python3
"""実教材と合成音声の集計（score.py --json の出力）を 1 つの表にまとめる（Chunk Flow / tools/stt-bench）。

言語ごとに、エンジンの誤り率（実教材・合成音声）と速度（中央値・RTF・初回）を並べ、
エンジンごとに、メモリ（計測プロセスの PSS の最大値）と速度の範囲をまとめる。

使い方:
    python3 tools/stt-bench/combine.py --real sample_files/stt-bench/real/score/summary.json \\
        --tts sample_files/stt-bench/tts/score/summary.json --out sample_files/stt-bench/combined.md

標準ライブラリだけを使う（pip で何も入れない）。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from score import ENGINE_NAMES, ENGINE_ORDER, LANG_NAMES  # noqa: E402

LANG_ORDER = ["en", "ja", "zh", "yue", "ko", "hi", "es", "ar", "uk", "vi"]


def load(path: Path | None) -> dict[tuple[str, str], dict]:
    if path is None or not path.is_file():
        return {}
    return {(r["engine"], r["lang"]): r for r in json.loads(path.read_text(encoding="utf-8"))}


def pct(r: dict | None) -> str:
    return "—" if r is None else f"{r['errorRate'] * 100:.1f}%"


def ms(v: float | None) -> str:
    if v is None:
        return "—"
    return f"{v / 1000:.2f} s" if v >= 1000 else f"{v:.0f} ms"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--real", type=Path, help="実教材の summary.json")
    parser.add_argument("--tts", type=Path, help="合成音声の summary.json")
    parser.add_argument("--out", type=Path, help="書き出す Markdown（省略時は標準出力だけ）")
    args = parser.parse_args()

    real = load(args.real)
    tts = load(args.tts)
    lines: list[str] = []

    for lang in LANG_ORDER:
        engines = [e for e in ENGINE_ORDER if (e, lang) in real or (e, lang) in tts]
        if not engines:
            continue
        metric = next(r["metric"] for r in (real.get((e, lang)) or tts.get((e, lang)) for e in engines) if r)

        def sort_key(e: str) -> tuple[float, float]:
            r = real.get((e, lang))
            t = tts.get((e, lang))
            return (r["errorRate"] if r else 9.0, t["errorRate"] if t else 9.0)

        lines.append(f"### {LANG_NAMES.get(lang, lang)}（{lang}）")
        lines.append("")
        lines.append(
            f"| エンジン | 実教材 {metric} | 合成音声 {metric} | 中央値（実教材） | 中央値（合成音声） | RTF | 初回（読み込み込み） |"
        )
        lines.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: |")
        for e in sorted(engines, key=sort_key):
            r = real.get((e, lang))
            t = tts.get((e, lang))
            base = r or t
            lines.append(
                f"| {ENGINE_NAMES.get(e, e)} | {pct(r)} | {pct(t)} | {ms(r['medianMs'] if r else None)} | "
                f"{ms(t['medianMs'] if t else None)} | {base['rtf']:.2f} | {ms(base.get('warmupMs'))} |"
            )
        lines.append("")

    lines.append("### エンジンごとのメモリと速度")
    lines.append("")
    lines.append("| エンジン | PSS の最大値 | RTF（言語ごとの範囲） | 初回（言語ごとの範囲） |")
    lines.append("| --- | ---: | ---: | ---: |")
    for e in ENGINE_ORDER:
        rows = [r for (eng, _), r in list(real.items()) + list(tts.items()) if eng == e]
        if not rows:
            continue
        pss = max((r["pssMb"] for r in rows if r.get("pssMb")), default=None)
        rtfs = [r["rtf"] for r in rows if r.get("rtf") is not None]
        warm = [r["warmupMs"] for r in rows if r.get("warmupMs") is not None]
        lines.append(
            f"| {ENGINE_NAMES.get(e, e)} | {'—' if pss is None else f'{pss:.0f} MB'} | "
            f"{min(rtfs):.2f}〜{max(rtfs):.2f} | {ms(min(warm))}〜{ms(max(warm))} |"
        )
    lines.append("")

    text = "\n".join(lines)
    print(text)
    if args.out:
        args.out.write_text(text + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
