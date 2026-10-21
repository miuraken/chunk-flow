#!/usr/bin/env python3
"""macOS の音声合成（say）で、正解がわかっている音声認識ベンチマーク用のクリップを作る（Chunk Flow / tools/stt-bench）。

- 文は tts_corpus.tsv（10 言語 × 12 文）。言語ごとに 1〜2 種類の声で読み上げる。
- 16kHz モノラル 16-bit の WAV に書き出す（前後に 0.2 秒の無音を付ける）。
- 出力: <out>/clips/*.wav、<out>/tts.tsv（クリップ一覧。SttBenchActivity に渡す）、<out>/refs.tsv（正解。score.py に渡す）。

合成した音声はコミットしない（*.wav は gitignore）。必要になったらこのスクリプトで作り直す。
標準ライブラリと macOS の say・ffmpeg だけを使う。
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

# 言語ごとの声（say -v の名前）。2 種類あれば両方で読む。
VOICES: dict[str, list[str]] = {
    "en": ["Samantha", "Daniel"],
    "ja": ["Kyoko", "Eddy (Japanese (Japan))"],
    "zh": ["Tingting", "Eddy (Chinese (China mainland))"],
    "yue": ["Sinji"],
    "ko": ["Yuna", "Eddy (Korean (South Korea))"],
    "hi": ["Lekha"],
    "es": ["Mónica", "Paulina"],
    "ar": ["Majed"],
    "uk": ["Lesya"],
    "vi": ["Linh"],
}

PAD_SEC = 0.2


def read_corpus(path: Path) -> list[tuple[str, str, str]]:
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        sentence_id, lang, text = line.split("\t", 2)
        rows.append((sentence_id.strip(), lang.strip(), text.strip()))
    return rows


def synthesize(voice: str, text: str, out: Path) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        raw = Path(tmp) / "raw.aiff"
        subprocess.run(["say", "-v", voice, "-o", str(raw), text], check=True, stdin=subprocess.DEVNULL)
        subprocess.run(
            [
                "ffmpeg",
            "-nostdin",
                "-v",
                "error",
                "-y",
                "-i",
                str(raw),
                "-af",
                f"adelay={int(PAD_SEC * 1000)}:all=1,apad=pad_dur={PAD_SEC}",
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
    parser.add_argument("--corpus", type=Path, default=Path(__file__).with_name("tts_corpus.tsv"))
    parser.add_argument("--out", type=Path, default=Path("sample_files/stt-bench/tts"))
    args = parser.parse_args()

    clips_dir = args.out / "clips"
    clips_dir.mkdir(parents=True, exist_ok=True)
    manifest = ["id\tlang\tfile\tvoice"]
    refs = ["id\tlang\ttext"]
    for sentence_id, lang, text in read_corpus(args.corpus):
        for voice_index, voice in enumerate(VOICES[lang]):
            clip_id = f"tts_{sentence_id}_v{voice_index + 1}"
            synthesize(voice, text, clips_dir / f"{clip_id}.wav")
            manifest.append(f"{clip_id}\t{lang}\tclips/{clip_id}.wav\t{voice}")
            refs.append(f"{clip_id}\t{lang}\t{text}")
    (args.out / "tts.tsv").write_text("\n".join(manifest) + "\n", encoding="utf-8")
    (args.out / "refs.tsv").write_text("\n".join(refs) + "\n", encoding="utf-8")
    print(f"wrote {len(manifest) - 1} clips -> {args.out}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
