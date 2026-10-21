#!/usr/bin/env python3
"""手元の教材（sample_files/）から、音声認識ベンチマーク用の短い発話クリップを切り出す（Chunk Flow / tools/stt-bench）。

- ffmpeg の silencedetect で無音を探し、無音にはさまれた発話の区間を候補にする（アプリのチャンクに近い長さ）。
- 長さが範囲内（既定 1.2〜12 秒）の区間を、教材の最初から最後まで均等に選ぶ。
- 16kHz モノラル 16-bit の WAV に書き出す（前後に少し余白を付ける）。
- 出力: <out>/clips/*.wav と <out>/candidates.tsv（id, lang, file, pair, source, startMs, endMs）。
  lang はまだ仮（学習言語）。端末の言語判定（SttBenchActivity の mode=lid）で決めてから select_real_clips.py で絞る。

教材の音声と切り出したクリップは著作物なので、コミットしない（AGENTS.md §10）。出力先は gitignore された sample_files/ の下にする。
標準ライブラリと ffmpeg だけを使う（pip で何も入れない）。
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Source:
    """切り出し元の教材。"""

    file: str
    key: str
    target: str
    base: str
    count: int
    noise_db: float = -35.0
    min_silence_sec: float = 0.35


# 教材ごとの学習言語・解説の言語と、切り出す候補の数（言語判定で 2 言語に分かれる）。
SOURCES: list[Source] = [
    Source("Cantonese_1_Lesson_15_en.mp3", "canto15", "yue", "en", 90),
    Source("Hindi_1_Lesson_16_en.mp3", "hindi16", "hi", "en", 90),
    Source("Korean_lesson_ja.mp3", "kolesson", "ko", "ja", 70),
    Source("Korean_mainichi_ja.mp3", "komainichi", "ko", "ja", 50),
    # BGM 付きの教材は、音楽が無音の区間を埋めるので、しきい値を音楽の大きさに合わせる（--probe と掃引で決めた値）
    Source("Mandarin_BGM_ja.mp3", "zhbgm1", "zh", "ja", 24, noise_db=-27.0, min_silence_sec=0.4),
    Source("Mandarin_BGM2_ja.mp3", "zhbgm2", "zh", "ja", 12, noise_db=-21.0, min_silence_sec=0.4),
    Source("Mandarin_Chinese_4_Lesson_01_en.mp3", "zhpims4", "zh", "en", 70),
    Source("Mandarin_stepup_18_ja.mp3", "zhstepup18", "zh", "ja", 50),
    Source("Mandarin.mp3", "zhshort", "zh", "ja", 10, min_silence_sec=0.3),
]

SILENCE_START = re.compile(r"silence_start:\s*(-?[0-9.]+)")
SILENCE_END = re.compile(r"silence_end:\s*(-?[0-9.]+)")


def probe_duration(path: Path) -> float:
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", str(path)],
        capture_output=True,
        text=True,
        check=True,
        stdin=subprocess.DEVNULL,
    ).stdout.strip()
    return float(out)


def detect_silences(path: Path, noise_db: float, min_silence_sec: float) -> list[tuple[float, float]]:
    """ffmpeg の silencedetect で無音の区間（開始, 終了）を返す。"""
    proc = subprocess.run(
        [
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-nostats",
            "-i",
            str(path),
            "-af",
            f"silencedetect=noise={noise_db}dB:d={min_silence_sec}",
            "-f",
            "null",
            "-",
        ],
        capture_output=True,
        text=True,
        stdin=subprocess.DEVNULL,
    )
    silences: list[tuple[float, float]] = []
    start: float | None = None
    for line in proc.stderr.splitlines():
        m = SILENCE_START.search(line)
        if m:
            start = max(0.0, float(m.group(1)))
            continue
        m = SILENCE_END.search(line)
        if m and start is not None:
            silences.append((start, float(m.group(1))))
            start = None
    if start is not None:
        silences.append((start, float("inf")))
    return silences


def speech_segments(silences: list[tuple[float, float]], total: float) -> list[tuple[float, float]]:
    """無音の区間のあいだ（発話の区間）を返す。"""
    segments: list[tuple[float, float]] = []
    prev_end = 0.0
    for start, end in silences:
        if start > prev_end:
            segments.append((prev_end, min(start, total)))
        prev_end = end
    if prev_end < total:
        segments.append((prev_end, total))
    return segments


def pick_evenly(items: list, count: int) -> list:
    """items から count 件を、最初から最後まで均等に選ぶ。"""
    if count <= 0 or not items:
        return []
    if len(items) <= count:
        return list(items)
    if count == 1:
        return [items[len(items) // 2]]
    step = (len(items) - 1) / (count - 1)
    picked = []
    seen = set()
    for i in range(count):
        index = round(i * step)
        if index not in seen:
            seen.add(index)
            picked.append(items[index])
    return picked


def extract(src: Path, start: float, end: float, out: Path) -> None:
    subprocess.run(
        [
            "ffmpeg",
            "-nostdin",
            "-v",
            "error",
            "-y",
            "-ss",
            f"{start:.3f}",
            "-t",
            f"{end - start:.3f}",
            "-i",
            str(src),
            "-ac",
            "1",
            "-ar",
            "16000",
            "-c:a",
            "pcm_s16le",
            "-map_metadata",
            "-1",
            "-fflags",
            "+bitexact",
            "-flags:a",
            "+bitexact",
            str(out),
        ],
        check=True,
        stdin=subprocess.DEVNULL,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--src", type=Path, default=Path("sample_files"), help="教材のディレクトリ")
    parser.add_argument("--out", type=Path, default=Path("sample_files/stt-bench/real"), help="出力先（gitignore された場所）")
    parser.add_argument("--min-sec", type=float, default=1.2, help="クリップの長さの下限（秒）")
    parser.add_argument("--max-sec", type=float, default=12.0, help="クリップの長さの上限（秒）")
    parser.add_argument("--pad-sec", type=float, default=0.15, help="前後に付ける余白（秒）")
    parser.add_argument("--probe", action="store_true", help="切り出さずに、教材ごとの区間の数と長さの分布だけを出す")
    args = parser.parse_args()

    clips_dir = args.out / "clips"
    if not args.probe:
        clips_dir.mkdir(parents=True, exist_ok=True)
    rows: list[str] = ["id\tlang\tfile\tpair\tsource\tstartMs\tendMs"]
    for source in SOURCES:
        path = args.src / source.file
        if not path.is_file():
            print(f"skip (not found): {path}", file=sys.stderr)
            continue
        total = probe_duration(path)
        segments = speech_segments(detect_silences(path, source.noise_db, source.min_silence_sec), total)
        usable = [(s, e) for s, e in segments if args.min_sec <= e - s <= args.max_sec]
        durations = sorted(e - s for s, e in segments)
        median = durations[len(durations) // 2] if durations else 0.0
        print(
            f"{source.key:12s} total={total:7.1f}s segments={len(segments):4d} usable={len(usable):4d} "
            f"median={median:5.2f}s longer={sum(1 for d in durations if d > args.max_sec):3d}",
            file=sys.stderr,
        )
        if args.probe:
            continue
        for index, (start, end) in enumerate(pick_evenly(usable, source.count)):
            padded_start = max(0.0, start - args.pad_sec)
            padded_end = min(total, end + args.pad_sec)
            clip_id = f"{source.key}_{index:03d}"
            out = clips_dir / f"{clip_id}.wav"
            extract(path, padded_start, padded_end, out)
            rows.append(
                "\t".join(
                    [
                        clip_id,
                        source.target,
                        f"clips/{clip_id}.wav",
                        f"{source.target},{source.base}",
                        source.key,
                        str(round(padded_start * 1000)),
                        str(round(padded_end * 1000)),
                    ]
                )
            )
    if not args.probe:
        manifest = args.out / "candidates.tsv"
        manifest.write_text("\n".join(rows) + "\n", encoding="utf-8")
        print(f"wrote {len(rows) - 1} clips -> {manifest}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
