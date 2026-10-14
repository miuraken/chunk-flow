#!/usr/bin/env python3
"""スパイク S3 のテスト音源の生成と、ffmpeg でデコードしたときのクリックの時刻の測定。

標準ライブラリと ffmpeg だけを使う（依存を追加しない）。生成した音声はコミットしない
（.gitignore で除外済み。AGENTS.md §10）。

音源の内容:
  - 44.1kHz・ステレオの合成音（ゲートをかけたノイズとトーンで「発話らしい」区間を作る）。
  - 周期 ``period`` 秒ごとに、各周期の 1.000 秒の位置に「クリック」（1kHz・振幅 0.6・30ms の
    トーンバースト。cos で始めるので先頭のサンプルがピーク）を置く。クリックの前後 0.5〜2.0 秒は
    発話の区間を入れない（検出の誤りを防ぐ）。
  - 正解の時刻は ``clicks.json`` に書く（形式によらず同じ。秒）。

使い方:
  python3 spikes/audio/scripts/s3_audio.py generate --minutes 30 --out spikes/audio/build/testaudio/30min
  python3 spikes/audio/scripts/s3_audio.py ffmpeg-clicks --dir spikes/audio/build/testaudio/30min
"""

from __future__ import annotations

import argparse
import array
import json
import re
import subprocess
import sys
from pathlib import Path

FFMPEG = "/opt/homebrew/bin/ffmpeg"

SAMPLE_RATE = 44100
CLICK_OFFSET_S = 1.0
CLICK_LEN_S = 0.03
CLICK_AMP = 0.6
# クリックの検出の閾値（振幅の半分）。発話の区間の振幅は最大 0.12 程度。
THRESHOLD = CLICK_AMP / 2

# (ファイル名, ffmpeg のエンコードの引数)。master.wav から作る。
ENCODINGS: list[tuple[str, list[str]]] = [
    ("wav_pcm16.wav", ["-c:a", "pcm_s16le"]),
    ("mp3_cbr128.mp3", ["-c:a", "libmp3lame", "-b:a", "128k"]),
    ("mp3_vbr_q4.mp3", ["-c:a", "libmp3lame", "-q:a", "4"]),
    # LAME / Xing ヘッダを書かない MP3（ギャップレス情報がない）
    ("mp3_cbr128_noxing.mp3", ["-c:a", "libmp3lame", "-b:a", "128k", "-write_xing", "0"]),
    ("mp3_cbr64_22k_mono.mp3", ["-c:a", "libmp3lame", "-b:a", "64k", "-ac", "1", "-ar", "22050"]),
    ("mp3_cbr64_22k_mono_noxing.mp3",
     ["-c:a", "libmp3lame", "-b:a", "64k", "-ac", "1", "-ar", "22050", "-write_xing", "0"]),
    ("aac_lc128.m4a", ["-c:a", "aac", "-b:a", "128k"]),
    # ADTS（コンテナなし。ギャップレス情報がない）
    ("aac_lc128_adts.aac", ["-c:a", "aac", "-b:a", "128k"]),
    ("opus64.opus", ["-c:a", "libopus", "-b:a", "64k"]),
    ("vorbis_q4.ogg", ["-c:a", "vorbis", "-strict", "experimental", "-q:a", "4"]),
    ("flac.flac", ["-c:a", "flac"]),
]

# 60 分の参考用に作る形式
ENCODINGS_LONG = {"mp3_cbr128.mp3", "mp3_vbr_q4.mp3", "aac_lc128.m4a", "opus64.opus"}


def _expr(period: float, channel: int) -> str:
    """aevalsrc の式（1 チャンネル分）。"""
    m = f"mod(t,{period})"
    click = (
        f"{CLICK_AMP}*cos(2*PI*1000*({m}-{CLICK_OFFSET_S}))"
        f"*gte({m},{CLICK_OFFSET_S})*lt({m},{CLICK_OFFSET_S + CLICK_LEN_S})"
    )
    gate = f"gt(sin(2*PI*0.37*t)+0.5*sin(2*PI*1.71*t),0.3)*(1-gte({m},0.5)*lt({m},2.0))"
    tone_hz = 180 if channel == 0 else 240
    speech = f"{gate}*(0.07*(2*random({channel})-1)+0.05*sin(2*PI*{tone_hz}*t)*sin(2*PI*3*t))"
    return f"{click}+{speech}"


def generate(minutes: float, period: float, out: Path, long_only: bool) -> None:
    out.mkdir(parents=True, exist_ok=True)
    duration = minutes * 60
    master = out / "master.wav"
    exprs = f"{_expr(period, 0)}|{_expr(period, 1)}"
    subprocess.run(
        [FFMPEG, "-nostdin", "-v", "error", "-y", "-f", "lavfi", "-i",
         f"aevalsrc=exprs='{exprs}':s={SAMPLE_RATE}:d={duration}",
         "-c:a", "pcm_s16le", str(master)],
        check=True,
    )
    clicks = []
    k = 0
    while k * period + CLICK_OFFSET_S + CLICK_LEN_S < duration:
        clicks.append(round(k * period + CLICK_OFFSET_S, 6))
        k += 1
    (out / "clicks.json").write_text(
        json.dumps({"durationS": duration, "periodS": period, "clicksS": clicks}, indent=2) + "\n"
    )
    for name, args in ENCODINGS:
        if long_only and name not in ENCODINGS_LONG:
            continue
        print(f"encode {name}", file=sys.stderr)
        subprocess.run([FFMPEG, "-nostdin", "-v", "error", "-y", "-i", str(master), *args, str(out / name)], check=True)
    master.unlink()


def _probe_rate(path: Path) -> int:
    """入力のサンプルレート。ffprobe はこの Mac では実行が許可されていないため、ffmpeg の表示から読む。"""
    r = subprocess.run([FFMPEG, "-nostdin", "-hide_banner", "-i", str(path)], capture_output=True, text=True)
    m = re.search(r"Audio: .*?(\d+) Hz", r.stderr)
    if not m:
        raise RuntimeError(f"サンプルレートを読めない: {path}")
    return int(m.group(1))


def _decode_mono(path: Path, rate: int | None) -> tuple[array.array, int]:
    src_rate = _probe_rate(path)
    out_rate = rate or src_rate
    cmd = [FFMPEG, "-nostdin", "-v", "error", "-i", str(path), "-ac", "1"]
    if rate:
        cmd += ["-ar", str(rate)]
    cmd += ["-f", "s16le", "-acodec", "pcm_s16le", "-"]
    raw = subprocess.run(cmd, check=True, capture_output=True).stdout
    pcm = array.array("h")
    pcm.frombytes(raw)
    if sys.byteorder != "little":
        pcm.byteswap()
    return pcm, out_rate


def _detect(pcm: array.array, rate: int, expected: list[float]) -> list[float | None]:
    """各正解の時刻の ±0.3 秒の範囲で、最初に閾値を超えたサンプルの時刻（秒）。"""
    thr = int(THRESHOLD * 32768)
    found: list[float | None] = []
    for te in expected:
        lo = max(0, int((te - 0.3) * rate))
        hi = min(len(pcm), int((te + 0.3) * rate))
        hit = None
        for i in range(lo, hi):
            if abs(pcm[i]) > thr:
                hit = i / rate
                break
        found.append(hit)
    return found


def ffmpeg_clicks(directory: Path, rates: list[int | None]) -> dict:
    meta = json.loads((directory / "clicks.json").read_text())
    expected = meta["clicksS"]
    results = {}
    for path in sorted(directory.iterdir()):
        if path.suffix not in {".wav", ".mp3", ".m4a", ".aac", ".opus", ".ogg", ".flac"}:
            continue
        per_rate = {}
        for rate in rates:
            pcm, out_rate = _decode_mono(path, rate)
            found = _detect(pcm, out_rate, expected)
            deltas = [None if f is None else round((f - e) * 1000, 3) for f, e in zip(found, expected)]
            ok = [d for d in deltas if d is not None]
            per_rate[f"{out_rate}Hz" + ("" if rate else "(native)")] = {
                "decodedDurationS": round(len(pcm) / out_rate, 6),
                "missed": sum(1 for d in deltas if d is None),
                "deltaMsMin": min(ok) if ok else None,
                "deltaMsMax": max(ok) if ok else None,
                "deltaMsFirst": deltas[0] if deltas else None,
                "deltaMsLast": deltas[-1] if deltas else None,
            }
        results[path.name] = per_rate
        print(path.name, json.dumps(per_rate), file=sys.stderr)
    return {"expectedClicks": len(expected), "ffmpeg": _ffmpeg_version(), "results": results}


def _ffmpeg_version() -> str:
    r = subprocess.run([FFMPEG, "-version"], check=True, capture_output=True, text=True)
    return r.stdout.splitlines()[0]


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    g = sub.add_parser("generate", help="テスト音源を生成する")
    g.add_argument("--minutes", type=float, default=30)
    g.add_argument("--period", type=float, default=60, help="クリックの周期（秒）")
    g.add_argument("--out", type=Path, required=True)
    g.add_argument("--long-only", action="store_true", help="60 分の参考用の形式だけ作る")
    c = sub.add_parser("ffmpeg-clicks", help="ffmpeg でデコードしてクリックの時刻を測る")
    c.add_argument("--dir", type=Path, required=True)
    c.add_argument("--json", type=Path, help="結果を書き出すファイル")
    a = p.parse_args()
    if a.cmd == "generate":
        generate(a.minutes, a.period, a.out, a.long_only)
    else:
        res = ffmpeg_clicks(a.dir, [None, 16000])
        text = json.dumps(res, indent=2, ensure_ascii=False) + "\n"
        if a.json:
            a.json.write_text(text)
        print(text)


if __name__ == "__main__":
    main()
