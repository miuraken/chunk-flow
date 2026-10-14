"""docs/format/examples/ のサンプルで、出力の検証（形式の規則 + Schema）を確かめる。

PC ツールは不正な JSON を書かないことが目的なので、不正なサンプルは「Schema か形式の規則のどちらかで
拒否される」ことを確かめる。読み込みの上限（深い入れ子など）はアプリ側（core:transcript）の検証の対象。
"""

import json
from pathlib import Path

import pytest

from chunkflow_transcriber.cli import find_default_schema
from chunkflow_transcriber.output import check_document, load_schema, schema_errors

SCHEMA_PATH = find_default_schema()
EXAMPLES = SCHEMA_PATH.parent / "examples" if SCHEMA_PATH else None
pytestmark = pytest.mark.skipif(SCHEMA_PATH is None, reason="docs/format/ がありません")

# PC ツールの出力の検証の対象外（アプリの読み込みの上限で拒否するもの）
APP_ONLY = {"deep-nesting.chunkflow.json"}


def _valid() -> list[Path]:
    return sorted(EXAMPLES.glob("*.chunkflow.json")) if EXAMPLES else []


def _invalid() -> list[Path]:
    return sorted(EXAMPLES.glob("invalid/*.chunkflow.json")) if EXAMPLES else []


@pytest.mark.parametrize("path", _valid(), ids=lambda p: p.name)
def test_valid_examples_pass(path: Path) -> None:
    doc = json.loads(path.read_text(encoding="utf-8"))
    assert schema_errors(doc, load_schema(SCHEMA_PATH)) == []
    assert check_document(doc) == []


@pytest.mark.parametrize("path", _invalid(), ids=lambda p: p.name)
def test_invalid_examples_are_rejected(path: Path) -> None:
    if path.name in APP_ONLY:
        pytest.skip("アプリの読み込みの上限で拒否するサンプル")
    try:
        doc = json.loads(path.read_bytes().decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return  # 読めない JSON（PC ツールが書くことはない）
    errors = schema_errors(doc, load_schema(SCHEMA_PATH)) + check_document(doc)
    assert errors, path.name
