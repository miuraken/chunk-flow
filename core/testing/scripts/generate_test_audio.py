#!/usr/bin/env python3
"""Chunk Flow のエミュレータ動作確認・自動テスト用のサンプル音声および文字起こし JSON 生成スクリプト。

Python 3 標準ライブラリのみで動作し、以下の両方に対応する:
1. ディレクトリ指定（または引数省略時 `/tmp/korean-course`）:
   - `lesson01.wav`（16kHz モノラル 16-bit PCM、16.0 秒）
   - `transcription/lesson01.chunkflow.json`（`lesson01.wav` の実 SHA-256・durationMs・単語時刻付き）
   - `lesson02_untranscribed.wav`（文字起こし JSON なし。端末内の無音自動分割・先読み書き起こし確認用）
2. `.wav` ファイルパス指定（`--out /tmp/korean-course/lesson01.wav` または位置引数）:
   - 指定された `.wav` ファイルを生成し、同じ親ディレクトリの `transcription/<stem>.chunkflow.json` と
     `lesson02_untranscribed.wav` もあわせて生成する。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import struct
import subprocess
import tempfile
import unicodedata
import wave
from pathlib import Path
from typing import Any

SAMPLE_RATE = 16000
TOTAL_DURATION_MS = 16000
TOTAL_SAMPLES = (SAMPLE_RATE * TOTAL_DURATION_MS) // 1000


def _nfc(text: str) -> str:
    """文字列を Unicode NFC に正規化する。"""
    return unicodedata.normalize("NFC", text)


LESSON01_SEGMENTS: list[dict[str, Any]] = [
    {
        "startMs": 500,
        "endMs": 3200,
        "lang": "ja",
        "langProb": 0.99,
        "text": _nfc("第1課、挨拶の表現を練習しましょう。"),
        "words": [
            {"startMs": 500, "endMs": 1200, "text": _nfc("第1課、"), "prob": 0.99},
            {"startMs": 1250, "endMs": 2150, "text": _nfc("挨拶の表現を"), "prob": 0.98},
            {"startMs": 2200, "endMs": 3200, "text": _nfc("練習しましょう。"), "prob": 0.99},
        ],
    },
    {
        "startMs": 4200,
        "endMs": 6900,
        "lang": "ko",
        "langProb": 0.98,
        "text": _nfc("안녕하세요. 만나서 반갑습니다."),
        "translation": _nfc("こんにちは。お会いできて嬉しいです。"),
        "words": [
            {"startMs": 4200, "endMs": 5300, "text": _nfc("안녕하세요."), "prob": 0.98},
            {"startMs": 5400, "endMs": 6050, "text": _nfc("만나서"), "prob": 0.96},
            {"startMs": 6100, "endMs": 6900, "text": _nfc("반갑습니다."), "prob": 0.97},
        ],
    },
    {
        "startMs": 8000,
        "endMs": 10800,
        "lang": "ja",
        "langProb": 0.99,
        "text": _nfc("もう一度ゆっくり聞いて、繰り返してください。"),
        "words": [
            {"startMs": 8000, "endMs": 9300, "text": _nfc("もう一度ゆっくり聞いて、"), "prob": 0.99},
            {"startMs": 9350, "endMs": 10800, "text": _nfc("繰り返してください。"), "prob": 0.99},
        ],
    },
    {
        "startMs": 12000,
        "endMs": 15000,
        "lang": "ko",
        "langProb": 0.97,
        "text": _nfc("오늘 날씨가 정말 좋네요."),
        "translation": _nfc("今日は天気が本当にいいですね。"),
        "words": [
            {"startMs": 12000, "endMs": 12650, "text": _nfc("오늘"), "prob": 0.97},
            {"startMs": 12700, "endMs": 13450, "text": _nfc("날씨가"), "prob": 0.96},
            {"startMs": 13500, "endMs": 14100, "text": _nfc("정말"), "prob": 0.98},
            {"startMs": 14150, "endMs": 15000, "text": _nfc("좋네요."), "prob": 0.97},
        ],
    },
]

LESSON02_SEGMENTS: list[dict[str, Any]] = [
    {
        "startMs": 600,
        "endMs": 3100,
        "lang": "ja",
        "text": _nfc("第2課、買い物の表現を聞いてみましょう。"),
    },
    {
        "startMs": 4300,
        "endMs": 6800,
        "lang": "ko",
        "text": _nfc("이것은 얼마입니까?"),
    },
    {
        "startMs": 8100,
        "endMs": 10600,
        "lang": "ja",
        "text": _nfc("これはいくらですか。"),
    },
    {
        "startMs": 11900,
        "endMs": 14800,
        "lang": "ko",
        "text": _nfc("삼천 원입니다. 감사합니다."),
    },
]


def _synthesize_segment_with_macos_say(text: str, lang: str, target_samples: int) -> list[int] | None:
    """macOS の `say` と `afconvert` が利用可能な場合、16kHz モノラル PCM サンプル列を生成する。"""
    say_bin = shutil.which("say")
    afconvert_bin = shutil.which("afconvert")
    if not say_bin or not afconvert_bin:
        return None

    voice = "Kyoko" if lang == "ja" else "Yuna"
    try:
        with tempfile.TemporaryDirectory() as tmp_dir:
            aiff_path = Path(tmp_dir) / "utterance.aiff"
            wav_path = Path(tmp_dir) / "utterance.wav"
            subprocess.run(
                [say_bin, "-v", voice, "-o", str(aiff_path), text],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=15,
            )
            subprocess.run(
                [
                    afconvert_bin,
                    "-f",
                    "WAVE",
                    "-d",
                    f"LEI16@{SAMPLE_RATE}",
                    "-c",
                    "1",
                    str(aiff_path),
                    str(wav_path),
                ],
                check=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=15,
            )
            with wave.open(str(wav_path), "rb") as wf:
                raw = wf.readframes(wf.getnframes())
                samples = list(struct.unpack(f"<{len(raw) // 2}h", raw))
    except (OSError, subprocess.SubprocessError, wave.Error):
        return None

    if not samples:
        return None

    # 先頭・末尾の無音を軽くトリムしてから target_samples に収める
    threshold = 200
    first = next((i for i, s in enumerate(samples) if abs(s) > threshold), 0)
    last = next((len(samples) - i for i, s in enumerate(reversed(samples)) if abs(s) > threshold), len(samples))
    trimmed = samples[first:max(first + 1, last)]

    if len(trimmed) >= target_samples:
        trimmed = trimmed[:target_samples]
    else:
        # 短い場合は無音で埋めず、軽い振幅エンベロープの余韻またはゼロパディングで長さを揃える
        trimmed = trimmed + [0] * (target_samples - len(trimmed))

    # フェードイン・フェードアウト（クリックノイズ防止）
    fade_len = min(320, len(trimmed) // 10)
    for i in range(fade_len):
        scale = i / max(1, fade_len)
        trimmed[i] = int(trimmed[i] * scale)
        trimmed[-1 - i] = int(trimmed[-1 - i] * scale)
    return trimmed


def _generate_tone_burst(lang: str, target_samples: int) -> list[int]:
    """`say` が使えない環境向けに、言語ごとに異なる周波数のトーンバーストを生成する。"""
    base_freq = 440.0 if lang == "ja" else 660.0
    samples: list[int] = []
    fade_len = min(480, target_samples // 10)
    for i in range(target_samples):
        t = i / SAMPLE_RATE
        # 語学音声らしい抑揚を持たせるため 4Hz の軽い振幅変調を掛ける
        envelope = 0.65 + 0.35 * math.sin(2.0 * math.pi * 4.0 * t)
        if i < fade_len:
            envelope *= i / max(1, fade_len)
        elif i >= target_samples - fade_len:
            envelope *= (target_samples - 1 - i) / max(1, fade_len)
        val = math.sin(2.0 * math.pi * base_freq * t) + 0.3 * math.sin(2.0 * math.pi * (base_freq * 1.5) * t)
        sample = int(max(-32767, min(32767, val * envelope * 12000)))
        samples.append(sample)
    return samples


def generate_wav_file(wav_path: Path, segments: list[dict[str, Any]], use_tts: bool = True) -> tuple[str, int]:
    """指定されたセグメント定義から 16kHz モノラル WAV を生成し、(sha256, durationMs) を返す。"""
    wav_path.parent.mkdir(parents=True, exist_ok=True)
    pcm = [0] * TOTAL_SAMPLES

    for seg in segments:
        start_sample = max(0, min(TOTAL_SAMPLES, ( int(seg["startMs"]) * SAMPLE_RATE ) // 1000))
        end_sample = max(start_sample, min(TOTAL_SAMPLES, ( int(seg["endMs"]) * SAMPLE_RATE ) // 1000))
        target_len = end_sample - start_sample
        if target_len <= 0:
            continue

        seg_samples: list[int] | None = None
        if use_tts:
            seg_samples = _synthesize_segment_with_macos_say(str(seg["text"]), str(seg["lang"]), target_len)
        if seg_samples is None:
            seg_samples = _generate_tone_burst(str(seg["lang"]), target_len)

        pcm[start_sample:end_sample] = seg_samples[:target_len]

    raw_bytes = struct.pack(f"<{len(pcm)}h", *pcm)
    with wave.open(str(wav_path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(SAMPLE_RATE)
        wf.writeframes(raw_bytes)

    sha256_hex = hashlib.sha256(wav_path.read_bytes()).hexdigest()
    return sha256_hex, TOTAL_DURATION_MS


def write_transcript_json(
    json_path: Path,
    audio_file_name: str,
    sha256_hex: str,
    duration_ms: int,
    segments: list[dict[str, Any]],
) -> None:
    """設計書 §7.4.3 および JSON Schema に準拠した `.chunkflow.json` を書き出す。"""
    json_path.parent.mkdir(parents=True, exist_ok=True)
    payload: dict[str, Any] = {
        "format": "chunkflow-transcript",
        "version": 1,
        "audio": {
            "fileName": _nfc(audio_file_name),
            "sha256": sha256_hex,
            "durationMs": duration_ms,
        },
        "languages": {
            "target": "ko",
            "base": "ja",
        },
        "generator": {
            "tool": "chunkflow-transcribe 0.1.0",
            "engine": "generate_test_audio",
            "model": "large-v3-turbo",
            "createdAt": "2026-10-15T00:00:00Z",
        },
        "segments": segments,
    }
    json_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main() -> None:
    """コマンドライン引数を解析し、テスト用の音声と文字起こし JSON を生成する。"""
    parser = argparse.ArgumentParser(
        description="Chunk Flow のテスト用音声（WAV）および文字起こし JSON（.chunkflow.json）を生成します。"
    )
    parser.add_argument(
        "target",
        nargs="?",
        default=None,
        help="出力先のコースディレクトリまたは .wav ファイルパス（省略時: /tmp/korean-course）",
    )
    parser.add_argument(
        "--preset",
        default="speech-like",
        choices=["speech-like", "tone"],
        help="音声生成プリセット（既定: speech-like）",
    )
    parser.add_argument(
        "--out",
        default=None,
        help="出力先のコースディレクトリまたは .wav ファイルパス",
    )
    parser.add_argument(
        "--with-transcript",
        action="store_true",
        default=True,
        help="対応する transcription/<stem>.chunkflow.json も同時に生成する（既定で有効）",
    )
    args = parser.parse_args()

    raw_target = args.out or args.target or "/tmp/korean-course"
    target_path = Path(raw_target)
    use_tts = args.preset == "speech-like"

    if target_path.suffix.lower() == ".wav":
        course_dir = target_path.parent
        primary_wav = target_path
    else:
        course_dir = target_path
        primary_wav = course_dir / "lesson01.wav"

    sha256_hex, duration_ms = generate_wav_file(primary_wav, LESSON01_SEGMENTS, use_tts=use_tts)
    transcript_path = course_dir / "transcription" / f"{primary_wav.stem}.chunkflow.json"
    write_transcript_json(
        json_path=transcript_path,
        audio_file_name=primary_wav.name,
        sha256_hex=sha256_hex,
        duration_ms=duration_ms,
        segments=LESSON01_SEGMENTS,
    )

    untranscribed_wav = course_dir / "lesson02_untranscribed.wav"
    if untranscribed_wav != primary_wav:
        generate_wav_file(untranscribed_wav, LESSON02_SEGMENTS, use_tts=use_tts)

    print(f"Generated WAV:        {primary_wav} (sha256={sha256_hex}, durationMs={duration_ms})")
    print(f"Generated Transcript: {transcript_path}")
    if untranscribed_wav != primary_wav:
        print(f"Generated Untranscribed WAV: {untranscribed_wav}")


if __name__ == "__main__":
    main()
