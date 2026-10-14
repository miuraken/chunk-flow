"""合成音声のテスト素材を作る（スパイク S1 用）。

macOS の `say` と ffmpeg で、2 言語が交互に現れる教材風の音声と、正解のチャンク（言語と時刻）を作る。
生成物は `tools/transcriber/.work/synthetic/<シナリオ>/`（.gitignore 済み）に置き、コミットしない。

    uv run python scripts/make_synthetic.py [--out .work/synthetic] [--only ja-ko en-yue ...]

各シナリオのフォルダはそのままコースフォルダとして使える（`lesson.mp3` と `chunkflow.toml`）。
正解は `truth.json`（音声ではないので PC ツールの処理対象にならない）。

注意: 合成音声は発音が明瞭で、雑音も BGM もなく、話者も 1 言語 1〜2 人に限られるため、実際の教材より
判定がやさしい。結果は実際の教材での計測の代わりにはならない。
"""

from __future__ import annotations

import argparse
import json
import random
import shutil
import subprocess
import sys
import tempfile
import wave
from dataclasses import dataclass
from pathlib import Path

import numpy as np

SR = 16_000

# 言語 → 使う声（`say -v '?'` で確認したもの。1 つ目が主）
VOICES: dict[str, list[str]] = {
    "ja": ["Kyoko", "Eddy (Japanese (Japan))"],
    "ko": ["Yuna", "Reed (Korean (South Korea))"],
    "zh": ["Tingting", "Flo (Chinese (China mainland))"],
    "zh-TW": ["Meijia"],  # 台湾の声（繁体字で書かれやすいかを見る。正解の言語は zh）
    "yue": ["Sinji"],
    "hi": ["Lekha"],
    "ru": ["Milena"],
    "vi": ["Linh"],
    "en": ["Samantha", "Daniel"],
}


@dataclass(frozen=True)
class Line:
    """1 つの発話。`voice_lang` は声の選択に使う（`zh-TW` など）。正解の言語は `lang`。"""

    lang: str
    text: str
    voice_lang: str | None = None


def L(lang: str, text: str, voice_lang: str | None = None) -> Line:
    """`Line` の短い書き方。"""
    return Line(lang, text, voice_lang)


SCENARIOS: dict[str, tuple[str, str, list[Line]]] = {
    "ja-ko": (
        "ko",
        "ja",
        [
            L("ja", "第三課です。今日はカフェでの注文を練習しましょう。"),
            L("ko", "어서 오세요. 뭐 드릴까요?"),
            L("ja", "いらっしゃいませ、何になさいますか、という意味です。"),
            L("ko", "아메리카노 한 잔 주세요."),
            L("ja", "はい。"),
            L("ko", "네."),
            L("ja", "もう一度、ゆっくり聞いてみましょう。"),
            L("ko", "따뜻한 걸로 드릴까요, 차가운 걸로 드릴까요?"),
            L("ja", "温かいものと冷たいもの、どちらにするか聞いています。"),
            L("ko", "차가운 걸로 주세요."),
            L("ko", "네, 알겠습니다."),
            L("ja", "では、次の文です。"),
            L("ko", "여기서 드세요, 아니면 가져가세요?"),
            L("ja", "お店で飲むか、持ち帰るかを聞いています。"),
            L("ko", "가져갈게요."),
            L("ja", "はい。"),
            L("ko", "감사합니다."),
            L("ja", "よくできました。"),
        ],
    ),
    "ja-zh": (
        "zh",
        "ja",
        [
            L("ja", "第五課では、道の聞き方を学びます。"),
            L("zh", "请问，地铁站怎么走？"),
            L("ja", "すみません、地下鉄の駅はどう行きますか、という意味です。"),
            L("zh", "一直往前走，然后往左拐。"),
            L("ja", "はい。"),
            L("zh", "好。"),
            L("ja", "もう一度聞いてみましょう。"),
            L("zh", "离这儿远吗？", "zh-TW"),
            L("zh", "不太远，走路五分钟就到了。"),
            L("ja", "歩いて五分で着く、と言っています。"),
            L("zh", "谢谢你。", "zh-TW"),
            L("zh", "不客气。"),
            L("ja", "次は、時間の言い方です。"),
            L("zh", "现在几点了？"),
            L("zh", "现在下午三点半。", "zh-TW"),
            L("ja", "午後三時半です。"),
        ],
    ),
    "en-zh": (
        "zh",
        "en",
        [
            L("en", "Lesson seven. Let's learn how to order food at a restaurant."),
            L("zh", "服务员，我们要点菜。"),
            L("en", "This means, waiter, we would like to order."),
            L("zh", "你们想吃什么？"),
            L("en", "OK."),
            L("zh", "我要一碗牛肉面，不要辣的。"),
            L("en", "I'd like a bowl of beef noodles, not spicy."),
            L("zh", "好的，请稍等。", "zh-TW"),
            L("en", "Listen again."),
            L("zh", "买单。"),
            L("en", "The bill, please."),
            L("zh", "一共六十八块。"),
            L("en", "Yes."),
            L("zh", "可以用手机付吗？"),
            L("en", "Can I pay with my phone?"),
        ],
    ),
    "en-yue": (
        "yue",
        "en",
        [
            L("en", "Lesson two. Today we will practice greetings in Cantonese."),
            L("yue", "你好，你食咗飯未呀？"),
            L("en", "This literally means, have you eaten yet?"),
            L("yue", "食咗喇，多謝。"),
            L("en", "OK."),
            L("yue", "係。"),
            L("en", "Let's listen to the next sentence."),
            L("yue", "佢喺邊度做嘢㗎？"),
            L("en", "Where does he work?"),
            L("yue", "佢喺銀行做嘢。"),
            L("yue", "唔該晒。"),
            L("en", "Thank you very much."),
            L("yue", "我哋聽日一齊去飲茶啦。"),
            L("en", "Let's go for dim sum together tomorrow."),
        ],
    ),
    "en-hi": (
        "hi",
        "en",
        [
            L("en", "Lesson four. Let's learn some useful phrases in Hindi."),
            L("hi", "नमस्ते, आप कैसे हैं?"),
            L("en", "Hello, how are you?"),
            L("hi", "मैं ठीक हूँ, धन्यवाद।"),
            L("en", "OK."),
            L("hi", "हाँ।"),
            L("en", "Now listen to the next question."),
            L("hi", "आपका नाम क्या है?"),
            L("en", "What is your name?"),
            L("hi", "मेरा नाम राहुल है।"),
            L("hi", "बाज़ार यहाँ से कितनी दूर है?"),
            L("en", "How far is the market from here?"),
            L("hi", "बस दस मिनट।"),
            L("en", "Just ten minutes."),
        ],
    ),
    "en-ru": (
        "ru",
        "en",
        [
            L("en", "Lesson six. Today we talk about the weather in Russian."),
            L("ru", "Какая сегодня погода?"),
            L("en", "What is the weather like today?"),
            L("ru", "Сегодня холодно и идёт снег."),
            L("en", "OK."),
            L("ru", "Да."),
            L("en", "Listen once more."),
            L("ru", "Завтра будет тепло?"),
            L("en", "Will it be warm tomorrow?"),
            L("ru", "Нет, завтра тоже будет холодно."),
            L("ru", "Спасибо."),
            L("en", "Thank you."),
            L("ru", "Пожалуйста, одевайтесь теплее."),
            L("en", "Please dress more warmly."),
        ],
    ),
    "en-vi": (
        "vi",
        "en",
        [
            L("en", "Lesson three. Let's practice shopping at the market in Vietnamese."),
            L("vi", "Cái áo này bao nhiêu tiền?"),
            L("en", "How much is this shirt?"),
            L("vi", "Một trăm nghìn đồng."),
            L("en", "OK."),
            L("vi", "Vâng."),
            L("en", "Now try to bargain."),
            L("vi", "Đắt quá, bớt cho tôi một chút được không?"),
            L("en", "That's too expensive, can you give me a discount?"),
            L("vi", "Được, tám mươi nghìn thôi."),
            L("vi", "Cảm ơn chị."),
            L("en", "Thank you."),
            L("vi", "Hẹn gặp lại."),
            L("en", "See you again."),
        ],
    ),
}

# 発話の間の無音（秒）。0.3 秒は VAD でつながりやすい厳しい条件として混ぜる
GAPS = [0.3, 0.5, 0.6, 0.8, 1.0, 1.2]


def synth(text: str, voice: str, tmp: Path) -> np.ndarray:
    """`say` で 16kHz・モノラルの音声を作り、前後の無音を切り詰めて返す。"""
    out = tmp / "u.wav"
    subprocess.run(
        [
            "say",
            "-v",
            voice,
            "-o",
            str(out),
            "--file-format=WAVE",
            "--data-format=LEI16@16000",
            text,
        ],
        check=True,
    )
    with wave.open(str(out), "rb") as w:
        assert w.getframerate() == SR and w.getnchannels() == 1
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    thr = max(0.01, 0.02 * float(np.max(np.abs(x))))
    idx = np.flatnonzero(np.abs(x) > thr)
    if idx.size == 0:
        raise RuntimeError(f"無音になりました: {voice}: {text}")
    a = max(0, idx[0] - SR // 100)
    b = min(len(x), idx[-1] + SR // 100)
    return x[a:b]


def build(name: str, out_root: Path, seed: int) -> Path:
    """シナリオを 1 つ作り、コースフォルダのパスを返す。"""
    target, base, lines = SCENARIOS[name]
    rng = random.Random(f"{seed}-{name}")
    course = out_root / name
    if course.exists():
        shutil.rmtree(course)
    course.mkdir(parents=True)
    pieces: list[np.ndarray] = []
    truth = []
    pos = int(0.8 * SR)
    pieces.append(np.zeros(pos, dtype=np.float32))
    voice_turn: dict[str, int] = {}
    with tempfile.TemporaryDirectory() as td:
        for i, line in enumerate(lines):
            vl = line.voice_lang or line.lang
            voices = VOICES[vl]
            k = voice_turn.get(vl, 0)
            voice = voices[k % len(voices)]
            voice_turn[vl] = k + 1
            x = synth(line.text, voice, Path(td))
            truth.append(
                {
                    "startMs": pos * 1000 // SR,
                    "endMs": (pos + len(x)) * 1000 // SR,
                    "lang": line.lang,
                    "text": line.text,
                    "voice": voice,
                }
            )
            pieces.append(x)
            pos += len(x)
            if i + 1 < len(lines):
                gap = int(rng.choice(GAPS) * SR)
                pieces.append(np.zeros(gap, dtype=np.float32))
                pos += gap
        pieces.append(np.zeros(SR, dtype=np.float32))
        audio = np.concatenate(pieces)
        # 弱い雑音（-60 dBFS 程度）を足す。完全な無音より VAD に現実的な条件にする
        audio = audio + np.random.default_rng(seed).normal(0, 0.001, len(audio)).astype(np.float32)
        audio = np.clip(audio, -1, 1)
        wav = Path(td) / "lesson.wav"
        with wave.open(str(wav), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes((audio * 32767).astype(np.int16).tobytes())
        subprocess.run(
            [
                "ffmpeg",
                "-nostdin",
                "-y",
                "-loglevel",
                "error",
                "-i",
                str(wav),
                "-codec:a",
                "libmp3lame",
                "-b:a",
                "128k",
                str(course / "lesson.mp3"),
            ],
            check=True,
        )
    (course / "chunkflow.toml").write_text(
        f'[languages]\ntarget = "{target}"\nbase = "{base}"\n', encoding="utf-8"
    )
    (course / "truth.json").write_text(
        json.dumps(
            {
                "scenario": name,
                "target": target,
                "base": base,
                "durationMs": len(audio) * 1000 // SR,
                "utterances": truth,
            },
            ensure_ascii=False,
            indent=1,
        ),
        encoding="utf-8",
    )
    return course


def main() -> int:
    """エントリポイント。"""
    if sys.platform != "darwin" or not shutil.which("say"):
        print("macOS の say コマンドが必要です", file=sys.stderr)
        return 2
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--out", type=Path, default=Path(".work/synthetic"))
    ap.add_argument("--only", nargs="*", choices=sorted(SCENARIOS))
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()
    for name in args.only or SCENARIOS:
        course = build(name, args.out, args.seed)
        t = json.loads((course / "truth.json").read_text(encoding="utf-8"))
        print(f"{name}: {len(t['utterances'])} 発話, {t['durationMs'] / 1000:.1f} 秒 → {course}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
