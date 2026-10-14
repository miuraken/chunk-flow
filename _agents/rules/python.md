---
trigger: glob
globs: "*.py,pyproject.toml"
---

# Python の規約（Chunk Flow の PC ツール `tools/transcriber`）

- Python 3.12 以上。依存の管理と実行は uv（`uv sync`、`uv run --directory tools/transcriber ...`）。`pip install` を直接使わない。依存の追加は lead の承認を得る。
- 整形と静的解析は Ruff（`ruff format`、`ruff check`）。型ヒントを付ける。
- 処理は純粋な関数に分け（チャンクの整形、言語の平滑化、文字体系の確認、字体の変換、言語タグの変換）、pytest で検証する。Whisper のエンジンはインターフェース越しに使い、テストではモックにする（CI に GPU はない）。
- 入出力はコマンドで指定されたローカルのフォルダだけ。書き込むのはコースフォルダの `transcription/` の `*.chunkflow.json` とその一時ファイルだけ。ネットワークに音声や文字起こしを送らない（モデルのダウンロードを除く）。
- 出力する JSON は、`docs/format/` の JSON Schema で検証してから書く。テキストは NFC に正規化する。
- モデルの取得元とリビジョンは設定で固定する。
- 公開する関数に docstring（日本語）を書く。
- 実際の教材の音声や文字起こしをリポジトリに置かない。テスト用の音声は合成するか、ごく短いライセンス上問題のないものを使う。
