"""scripts/score_review.py（レビュー用の表の採点）のテスト。"""

import csv
import importlib.util
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "score_review.py"
_spec = importlib.util.spec_from_file_location("score_review", SCRIPT)
assert _spec and _spec.loader
score_review = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(score_review)


def _write(path: Path, rows: list[dict[str, str]]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=list(rows[0]))
        w.writeheader()
        w.writerows(rows)


def row(dur: float, lang: str, raw: str, truth: str) -> dict[str, str]:
    return {
        "idx": "0",
        "durSec": str(dur),
        "m:lang": lang,
        "m:prob": "0.9",
        "m:raw": f"{raw}:0.9",
        "m:flags": "",
        "m:text": "x",
        "truth": truth,
    }


def test_score(tmp_path: Path) -> None:
    p = tmp_path / "a.review.csv"
    _write(
        p,
        [
            row(2.0, "ko", "ko", "ko"),
            row(2.0, "ja", "ko", "ko"),
            row(0.5, "?", "ja", "ko"),
            row(0.5, "ja", "ja", "ja"),
            row(3.0, "ja", "ja", "mix"),
            row(3.0, "ja", "ja", "none"),
            row(3.0, "ja", "ja", ""),
        ],
    )
    rows = score_review.load_rows([p])
    assert score_review.models_in(rows) == ["m"]
    s = score_review.score(rows, "m")
    assert s["final >=1s"] == (1, 2, 0.5)
    assert s["raw >=1s"] == (2, 2, 1.0)
    assert s["final <1s"] == (1, 2, 0.5)
    assert s["null"] == {">=1s": 0, "<1s": 1}
    assert s["excluded"] == {"mix": 1, "none": 1, "unlabeled": 1}
    assert s["confusion"]["ko->ja"] == 1
