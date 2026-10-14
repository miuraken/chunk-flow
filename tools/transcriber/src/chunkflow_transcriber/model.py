"""パイプラインの中で受け渡すデータ型。時刻はすべてミリ秒の整数（音声の先頭から）。"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class Word:
    """単語（Whisper の単語単位の時刻）。"""

    start_ms: int
    end_ms: int
    text: str
    prob: float | None = None


@dataclass(frozen=True)
class CandidateChunk:
    """VAD で求めた候補チャンク（§7.4.4 の手順 2）。"""

    start_ms: int
    end_ms: int

    @property
    def duration_ms(self) -> int:
        """長さ（ミリ秒）。"""
        return self.end_ms - self.start_ms


@dataclass(frozen=True)
class LangObservation:
    """チャンクの言語判定の観測値（言語ペアに絞って正規化した確率）。

    `p_first` は言語ペアの 1 つ目（target）の確率。2 つ目はその補数。
    """

    duration_ms: int
    p_first: float
    start_ms: int = 0
    end_ms: int = 0


@dataclass(frozen=True)
class LangDecision:
    """平滑化した後の言語の判定。`lang` が None なら判定できなかった（アプリで「?」と表示）。

    `prob` は、確率の高い方の言語の確率（言語ペアの中での値）。`smoothed` は平滑化で値が
    変わったか。
    """

    lang: str | None
    prob: float
    best: str
    smoothed: bool = False


@dataclass
class Segment:
    """出力するセグメント（学習用のチャンク）。"""

    start_ms: int
    end_ms: int
    lang: str | None
    lang_prob: float | None
    text: str
    words: list[Word] = field(default_factory=list)
    variants: dict[str, str] = field(default_factory=dict)
    # 整形の参考（出力しない）: 言語の判定に使った言語（lang が None のときも入る）
    rec_lang: str | None = None

    @property
    def duration_ms(self) -> int:
        """長さ（ミリ秒）。"""
        return self.end_ms - self.start_ms
