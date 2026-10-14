"""文単位の整形と時刻の整合のテスト。"""

from chunkflow_transcriber.model import Segment, Word
from chunkflow_transcriber.segmenter import (
    ShapingParams,
    fix_word_times,
    join_texts,
    merge_short,
    normalize_text,
    resolve_overlaps,
    shape_sentences,
    split_sentences,
    words_text,
)

P = ShapingParams(max_ms=15_000, min_ms=1_000, split_gap_ms=700, merge_gap_ms=400)


def seg(start: int, end: int, lang: str | None, words: list[Word], prob: float = 0.9) -> Segment:
    return Segment(
        start_ms=start,
        end_ms=end,
        lang=lang,
        lang_prob=prob,
        text=words_text(words, lang),
        words=words,
        rec_lang=lang,
    )


def w(s: int, e: int, t: str) -> Word:
    return Word(start_ms=s, end_ms=e, text=t, prob=0.9)


def test_split_at_sentence_end() -> None:
    words = [
        w(0, 400, " Hello."),
        w(450, 900, " How"),
        w(900, 1200, " are"),
        w(1200, 1600, " you?"),
    ]
    out = split_sentences(seg(0, 1600, "en", words), P)
    assert [s.text for s in out] == ["Hello.", "How are you?"]
    assert (out[1].start_ms, out[1].end_ms) == (450, 1600)


def test_split_at_long_gap_without_punctuation() -> None:
    words = [w(0, 500, "それでは"), w(1500, 2000, "はじめましょう")]
    out = split_sentences(seg(0, 2000, "ja", words), P)
    assert [s.text for s in out] == ["それでは", "はじめましょう"]


def test_split_japanese_and_chinese_punctuation() -> None:
    words = [w(0, 500, "你好，"), w(500, 900, "欢迎。"), w(950, 1400, "请坐！")]
    out = split_sentences(seg(0, 1400, "zh", words), P)
    assert [s.text for s in out] == ["你好，欢迎。", "请坐！"]


def test_long_segment_is_split_preferring_clause_boundary() -> None:
    params = ShapingParams(max_ms=4000, min_ms=500, split_gap_ms=5000, merge_gap_ms=100)
    words = [w(i * 500, i * 500 + 450, f" w{i}" + ("," if i == 5 else "")) for i in range(12)]
    out = split_sentences(seg(0, 5950, "en", words), params)
    assert len(out) == 2
    assert out[0].text.endswith("w5,")
    assert all(s.duration_ms <= 4000 for s in out)


def test_merge_short_same_language() -> None:
    a = seg(0, 500, "ja", [w(0, 500, "はい。")])
    b = seg(700, 2500, "ja", [w(700, 2500, "そうです。")])
    out = merge_short([a, b], P)
    assert len(out) == 1
    assert out[0].text == "はい。そうです。"
    assert (out[0].start_ms, out[0].end_ms) == (0, 2500)


def test_do_not_merge_across_languages() -> None:
    a = seg(0, 500, "ja", [w(0, 500, "はい。")])
    b = seg(700, 2500, "ko", [w(700, 2500, "네, 알겠습니다.")])
    assert len(merge_short([a, b], P)) == 2


def test_do_not_merge_across_long_gap() -> None:
    a = seg(0, 500, "en", [w(0, 500, " OK.")])
    b = seg(1500, 3000, "en", [w(1500, 3000, " Next.")])
    assert len(merge_short([a, b], P)) == 2


def test_null_language_merges_only_with_same_recognition_language() -> None:
    a = seg(0, 500, None, [w(0, 500, "a")])
    a.rec_lang = "ko"
    b = seg(600, 900, None, [w(600, 900, "b")])
    b.rec_lang = "ja"
    assert len(merge_short([a, b], P)) == 2


def test_merged_probability_is_minimum() -> None:
    a = seg(0, 500, "en", [w(0, 500, " OK.")], prob=0.7)
    b = seg(600, 2000, "en", [w(600, 2000, " Next.")], prob=0.95)
    assert merge_short([a, b], P)[0].lang_prob == 0.7


def test_resolve_overlaps_orders_and_removes_overlap() -> None:
    a = seg(1000, 3000, "en", [w(1000, 3000, " B")])
    b = seg(0, 1500, "en", [w(0, 1500, " A")])
    out = resolve_overlaps([a, b])
    assert [s.text for s in out] == ["A", "B"]
    assert out[0].end_ms <= out[1].start_ms
    for s in out:
        assert s.start_ms < s.end_ms
        for x in s.words:
            assert s.start_ms <= x.start_ms < x.end_ms <= s.end_ms


def test_resolve_overlaps_drops_empty_text() -> None:
    a = seg(0, 1000, "en", [])
    a.text = "  "
    assert resolve_overlaps([a]) == []


def test_fix_word_times() -> None:
    s = Segment(
        start_ms=100,
        end_ms=1000,
        lang="en",
        lang_prob=0.9,
        text="a b c",
        words=[w(50, 80, "a"), w(500, 500, "b"), w(400, 1200, "c")],
    )
    out = fix_word_times(s)
    starts = [x.start_ms for x in out.words]
    assert starts == sorted(starts)
    for x in out.words:
        assert 100 <= x.start_ms < x.end_ms <= 1000


def test_shape_sentences_end_to_end() -> None:
    a = seg(0, 2100, "ja", [w(0, 1000, "第一課です。"), w(1100, 2100, "聞きましょう。")])
    b = seg(2600, 3000, "ko", [w(2600, 3000, "네.")])
    c = seg(3200, 5000, "ko", [w(3200, 5000, "안녕하세요.")])
    out = shape_sentences([c, a, b], P)
    assert [(s.lang, s.text) for s in out] == [
        ("ja", "第一課です。"),
        ("ja", "聞きましょう。"),
        ("ko", "네. 안녕하세요."),
    ]


def test_join_texts() -> None:
    assert join_texts(["你好，", "欢迎。"], "zh") == "你好，欢迎。"
    assert join_texts([" Hello. ", "How are you?"], "en") == "Hello. How are you?"
    assert join_texts(["", "  "], "en") == ""


def test_normalize_text_nfc_and_controls() -> None:
    import unicodedata

    raw = unicodedata.normalize("NFD", "Tiếng\tViệt\x00 ")
    out = normalize_text(raw)
    assert out == "Tiếng Việt"
    assert out == unicodedata.normalize("NFC", out)
