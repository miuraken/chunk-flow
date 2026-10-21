#!/usr/bin/env python3
"""音声認識ベンチマークの結果（SttBenchActivity の JSON Lines）を正解と比べて、言語ごと・エンジンごとの精度と速度を集計する
（Chunk Flow / tools/stt-bench）。

- 精度: 日本語・中国語・広東語・韓国語は CER（文字誤り率。空白を除く）、ほかの言語は WER（単語誤り率）。
  コーパス全体の (置換 + 削除 + 挿入) / 正解の長さ。本文が空の結果は全部削除として数える。
- 正規化: NFKC、小文字化、句読点・記号の除去。中国語・広東語は簡体字にそろえる（hant2hans.swift）。
  ヒンディー語はヌクタと半月点の揺れ、アラビア語は母音記号とアリフなどの字形の揺れを無視する。
- 速度: ウォームアップ後の 1 クリップあたりの時間（中央値・90 パーセンタイル）、RTF（処理時間の合計 / 音声の長さの合計）、
  ウォームアップ 1 回目の時間（モデルの読み込みを含む）。

出力: 標準出力と --out に Markdown の表（summary.md）、クリップごとの詳細（details.tsv）。
標準ライブラリだけを使う（pip で何も入れない）。
"""

from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import sys
import unicodedata
from collections import defaultdict
from dataclasses import dataclass, field
from pathlib import Path

CER_LANGS = {"ja", "zh", "yue", "ko"}
HANT2HANS = Path(__file__).with_name("hant2hans.swift")

ENGINE_ORDER = ["mlkit", "sr", "wtiny", "wbase", "wsmall", "sensevoice", "mtiny", "mbase"]
ENGINE_NAMES = {
    "mlkit": "ML Kit GenAI",
    "sr": "SpeechRecognizer",
    "wtiny": "Whisper Tiny",
    "wbase": "Whisper Base",
    "wsmall": "Whisper Small",
    "sensevoice": "SenseVoice Small",
    "mtiny": "Moonshine Tiny",
    "mbase": "Moonshine Base",
}
LANG_NAMES = {
    "en": "英語",
    "ja": "日本語",
    "zh": "中国語（普通話）",
    "yue": "広東語",
    "ko": "韓国語",
    "hi": "ヒンディー語",
    "es": "スペイン語",
    "ar": "アラビア語",
    "uk": "ウクライナ語",
    "vi": "ベトナム語",
}

ARABIC_DIACRITICS = {chr(c) for c in range(0x064B, 0x0653)} | {"\u0670", "\u0640"}
ARABIC_MAP = str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ة": "ه", "ى": "ي"})
HINDI_MAP = str.maketrans({"\u093c": None, "\u0901": "\u0902"})


# ---- 正規化と誤り率 ----


def to_simplified(texts: list[str]) -> list[str]:
    """繁体字を簡体字にそろえる（macOS の ICU 変換。使えなければそのまま返す）。"""
    if not texts:
        return texts
    payload = "\n".join(t.replace("\n", " ") for t in texts) + "\n"
    try:
        proc = subprocess.run(
            ["swift", str(HANT2HANS)], input=payload, capture_output=True, text=True, timeout=300, check=True
        )
    except (OSError, subprocess.SubprocessError) as e:
        print(f"warn: hant2hans failed ({e}); traditional/simplified differences will count as errors", file=sys.stderr)
        return texts
    lines = proc.stdout.split("\n")
    return lines[: len(texts)] if len(lines) >= len(texts) else texts


def normalize(text: str, lang: str) -> str:
    t = unicodedata.normalize("NFKC", text or "").lower()
    if lang == "hi":
        t = t.translate(HINDI_MAP)
    if lang == "ar":
        t = "".join(ch for ch in t if ch not in ARABIC_DIACRITICS).translate(ARABIC_MAP)
    out = []
    for ch in t:
        cat = unicodedata.category(ch)
        # 句読点（P*）と記号（S*）は空白に。結合文字（M*）はインド系の文字で必要なので残す
        out.append(" " if cat.startswith("P") or cat.startswith("S") else ch)
    return " ".join("".join(out).split())


def tokens(normalized: str, lang: str) -> list[str]:
    if lang in CER_LANGS:
        return list(normalized.replace(" ", ""))
    return normalized.split()


def chars(normalized: str) -> list[str]:
    return list(normalized.replace(" ", ""))


def edit_distance(ref: list[str], hyp: list[str]) -> int:
    if not ref:
        return len(hyp)
    if not hyp:
        return len(ref)
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i] + [0] * len(hyp)
        for j, h in enumerate(hyp, 1):
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h))
        prev = cur
    return prev[-1]


# ---- 読み込み ----


def read_refs(path: Path) -> dict[str, tuple[str, str]]:
    refs: dict[str, tuple[str, str]] = {}
    for i, line in enumerate(path.read_text(encoding="utf-8").splitlines()):
        if i == 0 or not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        clip_id, lang, text = parts[0].strip(), parts[1].strip(), parts[2].strip()
        if text.upper() == "EXCLUDE":
            continue
        refs[clip_id] = (lang, text)
    return refs


@dataclass
class Results:
    results: dict[tuple[str, str], dict] = field(default_factory=dict)
    warmups: dict[tuple[str, str], dict] = field(default_factory=dict)
    groups: dict[tuple[str, str], dict] = field(default_factory=dict)
    skips: dict[tuple[str, str], str] = field(default_factory=dict)
    header: dict = field(default_factory=dict)


def read_results(paths: list[Path]) -> Results:
    """結果を読む。同じエンジン・クリップの結果が複数あれば、後のファイル・後の行を使う。"""
    out = Results()
    for path in paths:
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            rec = json.loads(line)
            kind = rec.get("type")
            if kind == "header" and not out.header:
                out.header = rec
            elif kind == "result":
                out.results[(rec["engine"], rec["clip"])] = rec
            elif kind == "warmup":
                out.warmups[(rec["engine"], rec["lang"])] = rec
            elif kind == "group":
                out.groups[(rec["engine"], rec["lang"])] = rec
            elif kind == "skip":
                out.skips[(rec["engine"], rec["lang"])] = rec["status"]
    return out


# ---- 集計 ----


@dataclass
class Row:
    engine: str
    lang: str
    clips: int = 0
    scored: int = 0
    empty: int = 0
    edits: int = 0
    ref_len: int = 0
    char_edits: int = 0
    char_len: int = 0
    ms: list[float] = field(default_factory=list)
    audio_ms: int = 0
    warmup_ms: float | None = None
    pss_mb: float | None = None
    background: int = 0

    @property
    def error_rate(self) -> float:
        return self.edits / self.ref_len if self.ref_len else float("nan")

    @property
    def char_error_rate(self) -> float:
        return self.char_edits / self.char_len if self.char_len else float("nan")

    @property
    def rtf(self) -> float:
        return sum(self.ms) / self.audio_ms if self.audio_ms else float("nan")


def percentile(values: list[float], q: float) -> float:
    if not values:
        return float("nan")
    s = sorted(values)
    k = (len(s) - 1) * q
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def score(results: Results, refs: dict[str, tuple[str, str]]):
    # 中国語・広東語の正解と結果を、まとめて簡体字にそろえる
    zh_keys: list[tuple[str, str]] = []
    zh_texts: list[str] = []
    for clip_id, (lang, text) in refs.items():
        if lang in ("zh", "yue"):
            zh_keys.append(("ref", clip_id))
            zh_texts.append(text)
    for (engine, clip_id), rec in results.results.items():
        if clip_id in refs and refs[clip_id][0] in ("zh", "yue"):
            zh_keys.append((engine, clip_id))
            zh_texts.append(rec.get("text") or "")
    simplified = dict(zip(zh_keys, to_simplified(zh_texts)))

    rows: dict[tuple[str, str], Row] = {}
    details: list[list] = []
    for (engine, clip_id), rec in sorted(results.results.items()):
        if clip_id not in refs:
            continue
        lang, ref_text = refs[clip_id]
        hyp_text = rec.get("text") or ""
        if lang in ("zh", "yue"):
            ref_text = simplified.get(("ref", clip_id), ref_text)
            hyp_text = simplified.get((engine, clip_id), hyp_text)
        ref_n = normalize(ref_text, lang)
        hyp_n = normalize(hyp_text, lang)
        ref_t, hyp_t = tokens(ref_n, lang), tokens(hyp_n, lang)
        edits = edit_distance(ref_t, hyp_t)
        c_edits = edit_distance(chars(ref_n), chars(hyp_n))
        row = rows.setdefault((engine, lang), Row(engine=engine, lang=lang))
        row.clips += 1
        row.scored += 1
        row.edits += edits
        row.ref_len += len(ref_t)
        row.char_edits += c_edits
        row.char_len += len(chars(ref_n))
        if rec.get("status") != "ok":
            row.empty += 1
        if rec.get("bg"):
            row.background += 1
        row.ms.append(float(rec.get("ms") or 0.0))
        row.audio_ms += int(rec.get("audioMs") or 0)
        er = edits / len(ref_t) if ref_t else float("nan")
        details.append([lang, clip_id, engine, rec.get("audioMs"), f"{float(rec.get('ms') or 0):.0f}", f"{er:.3f}", ref_text, hyp_text])
    for (engine, lang), row in rows.items():
        warm = results.warmups.get((engine, lang))
        if warm:
            row.warmup_ms = float(warm.get("ms") or 0.0)
        group = results.groups.get((engine, lang))
        if group and group.get("pssMb") is not None:
            row.pss_mb = float(group["pssMb"])
    return rows, details


def fmt_pct(v: float) -> str:
    return "—" if v != v else f"{v * 100:.1f}%"


def fmt_ms(v: float | None) -> str:
    if v is None or v != v:
        return "—"
    return f"{v / 1000:.2f} s" if v >= 1000 else f"{v:.0f} ms"


def render(rows: dict[tuple[str, str], Row], results: Results, title: str) -> str:
    lines = [f"## {title}", ""]
    header = results.header
    if header:
        lines.append(
            f"端末: {header.get('manufacturer', '')} {header.get('model', '')}（{header.get('soc', '')}、Android {header.get('release', '')}、"
            f"RAM {header.get('totalMemMb', 0):.0f} MB）、アプリ {header.get('appVersion', '')}"
        )
        lines.append("")
    langs = sorted({lang for _, lang in rows}, key=lambda l: list(LANG_NAMES).index(l) if l in LANG_NAMES else 99)
    for lang in langs:
        metric = "CER" if lang in CER_LANGS else "WER"
        lines.append(f"### {LANG_NAMES.get(lang, lang)}（{lang}）")
        lines.append("")
        lines.append(f"| エンジン | {metric} | 空の結果 | 中央値 | p90 | RTF | 初回（読み込み込み） | クリップ |")
        lines.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
        lang_rows = [r for (e, l), r in rows.items() if l == lang]
        lang_rows.sort(key=lambda r: (r.error_rate if r.error_rate == r.error_rate else 9e9))
        for r in lang_rows:
            flag = "（前面でない計測あり）" if r.background else ""
            lines.append(
                f"| {ENGINE_NAMES.get(r.engine, r.engine)}{flag} | {fmt_pct(r.error_rate)} | {r.empty}/{r.clips} | "
                f"{fmt_ms(statistics.median(r.ms) if r.ms else None)} | {fmt_ms(percentile(r.ms, 0.9))} | "
                f"{r.rtf:.2f} | {fmt_ms(r.warmup_ms)} | {r.clips} |"
            )
        skipped = [ENGINE_NAMES.get(e, e) + f"（{s}）" for (e, l), s in sorted(results.skips.items()) if l == lang]
        if skipped:
            lines.append("")
            lines.append("計測しなかったエンジン: " + "、".join(skipped))
        lines.append("")
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--results", type=Path, nargs="+", required=True, help="results-*.jsonl（後のファイルを優先）")
    parser.add_argument("--refs", type=Path, required=True, help="正解（id, lang, text の TSV。text が EXCLUDE の行は除く）")
    parser.add_argument("--title", default="結果")
    parser.add_argument("--out", type=Path, help="summary.md と details.tsv を書くディレクトリ")
    parser.add_argument("--json", type=Path, help="集計を JSON でも書く")
    args = parser.parse_args()

    results = read_results(args.results)
    refs = read_refs(args.refs)
    rows, details = score(results, refs)
    text = render(rows, results, args.title)
    print(text)
    if args.out:
        args.out.mkdir(parents=True, exist_ok=True)
        (args.out / "summary.md").write_text(text + "\n", encoding="utf-8")
        with (args.out / "details.tsv").open("w", encoding="utf-8") as f:
            f.write("lang\tclip\tengine\taudioMs\tms\ter\tref\thyp\n")
            for d in details:
                f.write("\t".join(str(x).replace("\t", " ").replace("\n", " ") for x in d) + "\n")
    if args.json:
        payload = [
            {
                "engine": r.engine,
                "lang": r.lang,
                "metric": "CER" if r.lang in CER_LANGS else "WER",
                "errorRate": r.error_rate,
                "charErrorRate": r.char_error_rate,
                "clips": r.clips,
                "empty": r.empty,
                "medianMs": statistics.median(r.ms) if r.ms else None,
                "p90Ms": percentile(r.ms, 0.9),
                "rtf": r.rtf,
                "warmupMs": r.warmup_ms,
                "pssMb": r.pss_mb,
            }
            for r in rows.values()
        ]
        args.json.write_text(json.dumps(payload, ensure_ascii=False, indent=1), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
