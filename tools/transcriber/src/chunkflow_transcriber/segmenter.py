"""文単位のチャンクへの整形（§7.4.4 の手順 7）と、時刻の整合。

単語の時刻・句読点・無音を使って、学習に使いやすい文単位のチャンクに整える。長すぎるものは分け、
短すぎるものは（同じ言語で間隔が短ければ）つなぐ。言語の違うチャンクはつながない。
"""

from __future__ import annotations

import re
import unicodedata
from collections.abc import Sequence
from dataclasses import dataclass, replace

from .model import Segment, Word

# 文の終わりの記号（単語の末尾にあれば文の区切りにする）
_SENTENCE_END = tuple("。．.!?！？…।॥؟")
# 長すぎる文を分けるときに優先する区切り
_CLAUSE_END = tuple("、，,;；:：")
# 単語の間に空白を入れない言語（Whisper のトークナイザと同じ集合）
NO_SPACE_LANGS = frozenset({"zh", "ja", "th", "lo", "my", "yue"})
_WS = re.compile(r"\s+")


@dataclass(frozen=True)
class ShapingParams:
    """文単位の整形のパラメータ。

    - `max_ms`: チャンクの長さの上限（超えたら分ける）
    - `min_ms`: これより短いチャンクは、前後の同じ言語のチャンクとつなぐ
    - `split_gap_ms`: 単語の間の無音がこれ以上なら（句読点がなくても）分ける
    - `merge_gap_ms`: つなぐときの、チャンクの間の無音の上限
    """

    max_ms: int = 15_000
    min_ms: int = 1_000
    split_gap_ms: int = 700
    merge_gap_ms: int = 400


def join_texts(parts: Sequence[str], lang: str | None) -> str:
    """テキストをつなぐ。空白を入れない言語では詰め、それ以外は空白 1 つでつなぐ。"""
    cleaned = [p.strip() for p in parts if p and p.strip()]
    if not cleaned:
        return ""
    if lang in NO_SPACE_LANGS:
        return "".join(cleaned)
    return _WS.sub(" ", " ".join(cleaned)).strip()


def words_text(words: Sequence[Word], lang: str | None) -> str:
    """単語のテキストからセグメントのテキストを作る。

    Whisper の単語は、空白で区切る言語では先頭に空白を含むので、そのままつないで空白を整える。
    """
    if lang in NO_SPACE_LANGS:
        return "".join(w.text.strip() for w in words)
    return _WS.sub(" ", "".join(w.text for w in words)).strip()


def _ends_sentence(text: str) -> bool:
    t = text.rstrip().rstrip("\"'”」』)）]")
    return t.endswith(_SENTENCE_END)


def _ends_clause(text: str) -> bool:
    return text.rstrip().endswith(_CLAUSE_END)


def _make_segment(src: Segment, words: list[Word]) -> Segment:
    return replace(
        src,
        start_ms=words[0].start_ms,
        end_ms=max(w.end_ms for w in words),
        text=words_text(words, src.rec_lang or src.lang),
        words=words,
        variants={},
    )


def _split_long(seg: Segment, max_ms: int) -> list[Segment]:
    """長すぎるセグメントを、単語の間の無音（節の区切りを優先）で再帰的に分ける。"""
    if seg.duration_ms <= max_ms or len(seg.words) < 2:
        return [seg]
    best_i, best_score = 1, float("-inf")
    for i in range(1, len(seg.words)):
        gap = seg.words[i].start_ms - seg.words[i - 1].end_ms
        score = float(gap)
        if _ends_clause(seg.words[i - 1].text):
            score += 300.0
        # 真ん中に近いほど良い（片方だけが極端に短くならないように）
        left = seg.words[i - 1].end_ms - seg.start_ms
        right = seg.end_ms - seg.words[i].start_ms
        score -= abs(left - right) / 50.0
        if score > best_score:
            best_i, best_score = i, score
    left_seg = _make_segment(seg, seg.words[:best_i])
    right_seg = _make_segment(seg, seg.words[best_i:])
    return _split_long(left_seg, max_ms) + _split_long(right_seg, max_ms)


def split_sentences(seg: Segment, params: ShapingParams) -> list[Segment]:
    """1 つのセグメントを、文の終わりの記号と長い無音で分ける。単語がなければそのまま返す。"""
    if not seg.words:
        return [seg]
    pieces: list[list[Word]] = []
    cur: list[Word] = []
    for i, w in enumerate(seg.words):
        cur.append(w)
        nxt = seg.words[i + 1] if i + 1 < len(seg.words) else None
        if nxt is None:
            break
        gap = nxt.start_ms - w.end_ms
        if _ends_sentence(w.text) or gap >= params.split_gap_ms:
            pieces.append(cur)
            cur = []
    if cur:
        pieces.append(cur)
    out: list[Segment] = []
    for ws in pieces:
        out.extend(_split_long(_make_segment(seg, ws), params.max_ms))
    return out


def _can_merge(a: Segment, b: Segment, params: ShapingParams) -> bool:
    if a.lang != b.lang:
        return False
    if a.lang is None and a.rec_lang != b.rec_lang:
        return False
    gap = b.start_ms - a.end_ms
    if gap > params.merge_gap_ms:
        return False
    if b.end_ms - a.start_ms > params.max_ms:
        return False
    return a.duration_ms < params.min_ms or b.duration_ms < params.min_ms


def _merge(a: Segment, b: Segment) -> Segment:
    lang_key = a.rec_lang or a.lang
    probs = [p for p in (a.lang_prob, b.lang_prob) if p is not None]
    return replace(
        a,
        end_ms=max(a.end_ms, b.end_ms),
        text=join_texts([a.text, b.text], lang_key),
        words=[*a.words, *b.words],
        lang_prob=min(probs) if probs else None,
        variants={},
    )


def merge_short(segments: Sequence[Segment], params: ShapingParams) -> list[Segment]:
    """短すぎるセグメントを、間隔の短い同じ言語の隣のセグメントとつなぐ。"""
    out: list[Segment] = []
    for seg in segments:
        if out and _can_merge(out[-1], seg, params):
            out[-1] = _merge(out[-1], seg)
        else:
            out.append(seg)
    return out


def shape_sentences(segments: Sequence[Segment], params: ShapingParams) -> list[Segment]:
    """文単位のチャンクに整える（分けてからつなぎ、最後に時刻を整合させる）。"""
    split: list[Segment] = []
    for seg in sorted(segments, key=lambda s: s.start_ms):
        split.extend(split_sentences(seg, params))
    return resolve_overlaps(merge_short(split, params))


def fix_word_times(seg: Segment) -> Segment:
    """単語の時刻を整える。

    単語の開始は単調に増え、終了は開始より後、すべてセグメントの範囲の中に収める。
    """
    words: list[Word] = []
    prev_start = seg.start_ms
    for w in seg.words:
        start = min(max(w.start_ms, prev_start, seg.start_ms), seg.end_ms - 1)
        end = min(max(w.end_ms, start + 1), seg.end_ms)
        words.append(Word(start_ms=start, end_ms=end, text=w.text, prob=w.prob))
        prev_start = start
    return replace(seg, words=words)


def resolve_overlaps(segments: Sequence[Segment]) -> list[Segment]:
    """開始時刻の順に並べ、重なりを取り除く（§7.4.3: 開始時刻の順で重ならない）。

    重なったら後ろのセグメントの開始を前の終了まで遅らせる。それで長さが 0 以下になるなら
    前のセグメントの終了を後ろの開始まで早める。テキストが空のセグメントは捨てる。
    """
    ordered = sorted((s for s in segments if s.text.strip()), key=lambda s: (s.start_ms, s.end_ms))
    out: list[Segment] = []
    for seg in ordered:
        cur = seg
        if cur.end_ms <= cur.start_ms:
            cur = replace(cur, end_ms=cur.start_ms + 1)
        if out and cur.start_ms < out[-1].end_ms:
            prev = out[-1]
            if prev.end_ms < cur.end_ms:
                cur = replace(cur, start_ms=prev.end_ms)
            elif cur.start_ms > prev.start_ms:
                out[-1] = fix_word_times(replace(prev, end_ms=cur.start_ms))
            else:
                # 完全に含まれる（同じ開始）: 前のセグメントにつなぐ
                out[-1] = _merge(prev, cur)
                continue
        out.append(fix_word_times(cur))
    return out


def normalize_text(text: str) -> str:
    """テキストを NFC に正規化し、制御文字を除き、空白を整える（§7.4.3）。"""
    t = unicodedata.normalize("NFC", text)
    t = "".join(" " if unicodedata.category(ch) == "Cc" else ch for ch in t)
    return _WS.sub(" ", t).strip()
