#!/usr/bin/env python3
"""スパイク S3 の計測アプリの結果（s3_results.jsonl）を Markdown の表にまとめる。

使い方:
  adb pull /sdcard/Android/data/com.miuraken.chunkflow.spike.audio/files/s3_results.jsonl /tmp/
  python3 spikes/audio/scripts/s3_summarize.py /tmp/s3_results.jsonl
"""

from __future__ import annotations

import json
import sys


def _clk(c: dict | None) -> str:
    if not c:
        return "-"
    if "deltaMsMin" not in c:
        return f"検出なし（missed {c.get('missed')}）"
    lo, hi = c["deltaMsMin"], c["deltaMsMax"]
    s = f"{lo:+.2f}" if abs(hi - lo) < 0.01 else f"{lo:+.2f}〜{hi:+.2f}"
    if c.get("missed"):
        s += f"（missed {c['missed']}）"
    return s


def main() -> None:
    rows = [json.loads(line) for line in open(sys.argv[1], encoding="utf-8") if line.strip()]
    print("## デコード + 解析\n")
    print("| ファイル | 抽出器 | 種類 | 壁時計 ms | CPU ms | 解析 ms | 倍速 | Java ヒープ +MB | ネイティブ +MB | PSS +MB | "
          "遅延 | 累計 | 累計−遅延 | PTS | 16kHz 累計 | エラー |")
    print("| --- " * 16 + "|")
    for r in rows:
        if not r["test"].startswith("decode"):
            continue
        c = r.get("clicks", {})
        print(f"| {r['file']} | {r['extractor']} | {r['test']} | {r.get('wallMs')} | {r.get('threadCpuMs')} | "
              f"{r.get('pipelineMs', '-')} | {r.get('speedX', '-')} | {r.get('javaHeapExtraMb')} | "
              f"{r.get('nativeHeapExtraMb')} | {r.get('pssExtraMb')} | {r.get('encoderDelay', '-')} | "
              f"{_clk(c.get('countRaw'))} | {_clk(c.get('countTrimDelay'))} | {_clk(c.get('pts'))} | "
              f"{_clk(c.get('analysis16kRaw'))} | {r.get('error', '')} |")
    print("\n## ExoPlayer\n")
    print("| ファイル | 種類 | デコーダ | 遅延 / パディング | クリック（ms） | オフセット µs | 壁時計 ms | エラー |")
    print("| --- " * 8 + "|")
    for r in rows:
        if not r["test"].startswith("exoplayer"):
            continue
        off = f"{r.get('offsetUsMin')}〜{r.get('offsetUsMax')}"
        print(f"| {r['file']} | {r['test']} | {r.get('decoder', '-')} | "
              f"{r.get('encoderDelay', '-')} / {r.get('encoderPadding', '-')} | {_clk(r.get('clicks'))} | {off} | "
              f"{r.get('wallMs')} | {r.get('error', '')} |")


if __name__ == "__main__":
    main()
