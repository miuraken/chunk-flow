"""音声の読み込み（§7.4.4 の手順 1）。

ffmpeg で 16kHz・モノラルの float32 にデコードし、音声ファイルの SHA-256 を計算する。
時刻の基準はデコードした音声の先頭。エンコーダ遅延は ffmpeg の既定の扱い（ギャップレス情報に
従って取り除く）に任せる（§7.4.3）。
"""

from __future__ import annotations

import hashlib
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

import numpy as np

SAMPLE_RATE = 16_000

# コースフォルダの直下で処理する音声の拡張子
AUDIO_EXTENSIONS = frozenset(
    {".mp3", ".m4a", ".m4b", ".aac", ".mp4", ".wav", ".flac", ".ogg", ".oga", ".opus", ".webm"}
)


class AudioError(RuntimeError):
    """音声を読めなかったときの例外。"""


@dataclass(frozen=True)
class DecodedAudio:
    """デコードした音声。"""

    samples: np.ndarray  # float32、16kHz、モノラル
    sha256: str

    @property
    def duration_ms(self) -> int:
        """長さ（ミリ秒）。"""
        return len(self.samples) * 1000 // SAMPLE_RATE


def sha256_file(path: Path, chunk_size: int = 1 << 20) -> str:
    """ファイルのバイト列そのものの SHA-256（小文字の 16 進数）を返す。"""
    h = hashlib.sha256()
    with path.open("rb") as f:
        while block := f.read(chunk_size):
            h.update(block)
    return h.hexdigest()


def decode(path: Path, ffmpeg: str | None = None) -> DecodedAudio:
    """音声ファイルを 16kHz・モノラルの float32 にデコードする。"""
    exe = ffmpeg or shutil.which("ffmpeg")
    if not exe:
        raise AudioError("ffmpeg が見つかりません（README の手順で入れてください）")
    cmd = [
        exe,
        "-nostdin",
        "-hide_banner",
        "-loglevel",
        "error",
        "-i",
        str(path),
        "-map",
        "0:a:0",
        "-vn",
        "-ac",
        "1",
        "-ar",
        str(SAMPLE_RATE),
        "-f",
        "f32le",
        "-acodec",
        "pcm_f32le",
        "pipe:1",
    ]
    proc = subprocess.run(cmd, capture_output=True, check=False)
    if proc.returncode != 0:
        msg = proc.stderr.decode("utf-8", "replace").strip().splitlines()[-1:] or ["不明なエラー"]
        raise AudioError(f"デコードに失敗しました: {msg[0]}")
    samples = np.frombuffer(proc.stdout, dtype=np.float32).copy()
    if samples.size == 0:
        raise AudioError("音声が空です")
    return DecodedAudio(samples=samples, sha256=sha256_file(path))


def slice_ms(samples: np.ndarray, start_ms: int, end_ms: int) -> np.ndarray:
    """音声の一部（ミリ秒で指定）を返す。"""
    a = max(0, start_ms * SAMPLE_RATE // 1000)
    b = min(len(samples), end_ms * SAMPLE_RATE // 1000)
    return samples[a:b]
