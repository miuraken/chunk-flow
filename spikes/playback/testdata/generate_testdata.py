#!/usr/bin/env python3
"""スパイク S2 のテスト音源を合成する（標準ライブラリと ffmpeg だけを使う）。

作るもの（すべて ``testdata/out/`` に出力。コミットしない）:

- ``clicks.wav``: 10 分のモノラル 44.1kHz。無音・小さな正弦波・ノイズの区間をランダムな長さで並べ
  （VBR のビットレートが大きく揺れるようにする）、約 6 秒ごとに「クリック」を置く。
  クリック k は 3ms・3kHz・振幅 0.9 のバースト 2 つで、間隔 ``30 + 7k`` ms でクリックの番号を表す。
  クリックの前後は無音にし、ノイズの振幅は 0.4 未満に抑える（振幅 0.5 の閾値でクリックだけを検出できる）。
- 上の音源を符号化したもの: CBR / CBR（ヘッダなし）/ VBR + Xing（TOC あり）/ VBR + Xing（TOC なしに書き換え）/
  ヘッダのない VBR の MP3、AAC の M4A、VBR の MP3 を再エンコードせずに MP4 に詰め替えたもの。
- ``loop.wav`` / ``loop.m4a``: ループの行き過ぎを測る音源。A=10.000s〜B=12.000s が 440Hz、
  B から 500ms は 10ms ごとの目盛り（1ms のバースト）。行き過ぎた分だけ目盛りが聞こえる（実機での録音用）。
- ``manifest.json``: 真のクリックの時刻（生成した PCM に端末と同じ検出を当てた値）とファイルの一覧。

使い方:
    python3 spikes/playback/testdata/generate_testdata.py
    adb push spikes/playback/testdata/out/. /sdcard/Android/data/com.miuraken.chunkflow.spike.playback/files/audio/
"""

from __future__ import annotations

import array
import json
import math
import random
import shutil
import struct
import subprocess
import sys
import wave
from pathlib import Path

SAMPLE_RATE = 44_100
DURATION_S = 600
SEED = 20261014
CLICK_THRESHOLD = 16_384  # 振幅 0.5。端末の ClickDetector と同じ値
CLICK_HOLDOFF_MS = 10.0
BURST_MS = 3.0
BURST_HZ = 3_000.0
BURST_AMP = 0.9
GAP_BASE_MS = 30
GAP_STEP_MS = 7
GUARD_S = 0.25

OUT = Path(__file__).resolve().parent / "out"
FFMPEG = shutil.which("ffmpeg") or "/opt/homebrew/bin/ffmpeg"


def to_i16(x: float) -> int:
    return max(-32768, min(32767, int(round(x * 32767))))


def burst() -> array.array:
    n = int(SAMPLE_RATE * BURST_MS / 1000)
    return array.array("h", (to_i16(BURST_AMP * math.sin(2 * math.pi * BURST_HZ * i / SAMPLE_RATE)) for i in range(n)))


def detect_onsets(pcm: array.array, start: int, end: int) -> list[int]:
    """振幅が閾値を超えた最初のサンプルを、ホールドオフを挟んで最大 2 つ返す（端末の ClickDetector と同じ規則）。"""
    holdoff = int(SAMPLE_RATE * CLICK_HOLDOFF_MS / 1000)
    onsets: list[int] = []
    i = start
    while i < end and len(onsets) < 2:
        if abs(pcm[i]) >= CLICK_THRESHOLD:
            onsets.append(i)
            i += holdoff
        else:
            i += 1
    return onsets


def make_clicks_wav(rng: random.Random) -> tuple[array.array, list[dict]]:
    total = SAMPLE_RATE * DURATION_S
    pcm = array.array("h", bytes(2 * total))

    noise = array.array("h", (to_i16(max(-0.39, min(0.39, rng.gauss(0.0, 0.12)))) for _ in range(SAMPLE_RATE * 3)))
    tone = array.array("h", (to_i16(0.15 * math.sin(2 * math.pi * 300 * i / SAMPLE_RATE)) for i in range(SAMPLE_RATE)))

    pos = 0
    while pos < total:
        kind = rng.choice(("silence", "tone", "noise", "noise"))
        length = min(int(rng.uniform(0.3, 4.0) * SAMPLE_RATE), total - pos)
        if kind == "tone":
            for off in range(0, length, len(tone)):
                n = min(len(tone), length - off)
                pcm[pos + off : pos + off + n] = tone[:n]
        elif kind == "noise":
            for off in range(0, length, len(noise)):
                n = min(len(noise), length - off)
                s = rng.randrange(0, len(noise) - n + 1)
                pcm[pos + off : pos + off + n] = noise[s : s + n]
        pos += length

    b = burst()
    clicks: list[dict] = []
    t = 3.0
    k = 0
    while t < DURATION_S - 3.0:
        gap_ms = GAP_BASE_MS + GAP_STEP_MS * k
        s0 = int(round(t * SAMPLE_RATE))
        g0 = int((t - GUARD_S) * SAMPLE_RATE)
        g1 = int((t + gap_ms / 1000 + GUARD_S) * SAMPLE_RATE)
        pcm[g0:g1] = array.array("h", bytes(2 * (g1 - g0)))
        s1 = s0 + int(round(gap_ms / 1000 * SAMPLE_RATE))
        pcm[s0 : s0 + len(b)] = b
        pcm[s1 : s1 + len(b)] = b
        onsets = detect_onsets(pcm, g0, g1)
        assert len(onsets) == 2, f"click {k}: onsets={onsets}"
        clicks.append(
            {
                "index": k,
                "truthMs": onsets[0] * 1000 / SAMPLE_RATE,
                "gapMs": (onsets[1] - onsets[0]) * 1000 / SAMPLE_RATE,
            }
        )
        k += 1
        t += rng.uniform(5.0, 7.0)
    return pcm, clicks


def make_loop_wav() -> array.array:
    total = SAMPLE_RATE * 30
    pcm = array.array("h", bytes(2 * total))
    for i in range(int(9.0 * SAMPLE_RATE), int(10.0 * SAMPLE_RATE)):
        pcm[i] = to_i16(0.2 * math.sin(2 * math.pi * 220 * i / SAMPLE_RATE))
    for i in range(int(10.0 * SAMPLE_RATE), int(12.0 * SAMPLE_RATE)):
        pcm[i] = to_i16(0.3 * math.sin(2 * math.pi * 440 * i / SAMPLE_RATE))
    tick = array.array("h", (to_i16(0.9 * math.sin(2 * math.pi * 3000 * i / SAMPLE_RATE)) for i in range(44)))
    for n in range(50):
        s = int(round((12.0 + n * 0.010) * SAMPLE_RATE))
        pcm[s : s + len(tick)] = tick
    return pcm


def write_wav(path: Path, pcm: array.array) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        data = array.array("h", pcm)
        if sys.byteorder != "little":
            data.byteswap()
        w.writeframes(data.tobytes())


def ffmpeg(*args: str) -> None:
    subprocess.run(
        [FFMPEG, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", *args],
        check=True,
        stdin=subprocess.DEVNULL,
    )


def strip_xing_toc(src: Path, dst: Path) -> bool:
    """Xing ヘッダの TOC（100 バイト）を取り除き、フラグを下ろす（フレーム長は末尾を 0 で埋めて保つ）。"""
    data = bytearray(src.read_bytes())
    idx = data.find(b"Xing")
    if idx < 0 or idx > 4096:
        return False
    flags = struct.unpack(">I", data[idx + 4 : idx + 8])[0]
    if not flags & 0x4:
        return False
    toc = idx + 8 + (4 if flags & 0x1 else 0) + (4 if flags & 0x2 else 0)
    # 1 フレーム目（Xing フレーム）の終わり = 次の同期ワードの位置
    frame_end = toc + 100
    while frame_end + 1 < len(data) and not (data[frame_end] == 0xFF and (data[frame_end + 1] & 0xE0) == 0xE0):
        frame_end += 1
    struct.pack_into(">I", data, idx + 4, flags & ~0x4)
    rest = data[toc + 100 : frame_end]
    data[toc:frame_end] = rest + bytes(100)
    dst.write_bytes(bytes(data))
    return True


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    rng = random.Random(SEED)
    pcm, clicks = make_clicks_wav(rng)
    write_wav(OUT / "clicks.wav", pcm)
    write_wav(OUT / "loop.wav", make_loop_wav())

    src = str(OUT / "clicks.wav")
    ffmpeg("-i", src, "-c:a", "libmp3lame", "-b:a", "128k", str(OUT / "cbr.mp3"))
    ffmpeg("-i", src, "-c:a", "libmp3lame", "-b:a", "128k", "-write_xing", "0", str(OUT / "cbr_noheader.mp3"))
    ffmpeg("-i", src, "-c:a", "libmp3lame", "-q:a", "4", str(OUT / "vbr_xing.mp3"))
    ffmpeg("-i", src, "-c:a", "libmp3lame", "-q:a", "4", "-write_xing", "0", str(OUT / "vbr_noheader.mp3"))
    ffmpeg("-i", src, "-c:a", "aac", "-b:a", "96k", str(OUT / "aac.m4a"))
    ffmpeg("-i", str(OUT / "vbr_xing.mp3"), "-c:a", "copy", "-f", "mp4", str(OUT / "vbr_in_mp4.mp4"))
    has_notoc = strip_xing_toc(OUT / "vbr_xing.mp3", OUT / "vbr_xing_notoc.mp3")
    ffmpeg("-i", str(OUT / "loop.wav"), "-c:a", "aac", "-b:a", "96k", str(OUT / "loop.m4a"))

    files = [
        {"name": "cbr.mp3", "label": "MP3 CBR（Info/LAME ヘッダあり）", "mp3": True},
        {"name": "cbr_noheader.mp3", "label": "MP3 CBR（ヘッダなし）", "mp3": True},
        {"name": "vbr_xing.mp3", "label": "MP3 VBR + Xing（TOC あり）", "mp3": True},
        {"name": "vbr_noheader.mp3", "label": "MP3 VBR（ヘッダなし）", "mp3": True},
        {"name": "aac.m4a", "label": "M4A（AAC）", "mp3": False},
        {"name": "vbr_in_mp4.mp4", "label": "VBR MP3 を MP4 に詰め替え（ffmpeg）", "mp3": False},
    ]
    if has_notoc:
        files.insert(3, {"name": "vbr_xing_notoc.mp3", "label": "MP3 VBR + Xing（TOC を除去）", "mp3": True})
    manifest = {
        "sampleRate": SAMPLE_RATE,
        "durationMs": DURATION_S * 1000,
        "clickThreshold": CLICK_THRESHOLD,
        "clickHoldoffMs": CLICK_HOLDOFF_MS,
        "gapBaseMs": GAP_BASE_MS,
        "gapStepMs": GAP_STEP_MS,
        "clicks": clicks,
        "files": files,
        "loop": {"file": "loop.m4a", "aMs": 10_000, "bMs": 12_000},
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"clicks={len(clicks)} files={[f['name'] for f in files]} -> {OUT}")


if __name__ == "__main__":
    main()
