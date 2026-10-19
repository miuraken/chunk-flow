# chunkflow-transcriber（PC の文字起こしツール）

Chunk Flow の教材の音声を PC で文字起こしし、`chunkflow-transcript` v1 の JSON（design_doc.md §7.4.3）を
コースフォルダの `transcription/` に作るツールです。単体のアプリではなく、このリポジトリの
`tools/transcriber/` から uv で実行するスクリプトです（§7.4.4）。

- 推論はすべてローカルで行い、音声や文字起こしを外部に送りません。ネットワークを使うのは、依存の取得と、
  モデル（Whisper、Silero VAD）の初回の取得だけです。
- 書き込むのは、指定したコースフォルダの `transcription/*.chunkflow.json`（とその一時ファイル）だけです。
  音声と `chunkflow.toml` は読むだけです。

> [!WARNING]
> 現在は試作（スパイク S1）です。動作を確認したのは **macOS（Apple Silicon、mlx-whisper）だけ**です。
> **Linux / WSL2（faster-whisper）の手順とエンジンは未検証です。**

## 環境ごとの準備

| 環境 | エンジン | 状態 |
| --- | --- | --- |
| macOS（Apple Silicon） | mlx-whisper（Metal） | 確認済み（M3 Pro） |
| Linux（NVIDIA） | faster-whisper（CUDA 12 / cuDNN 9） | **未検証** |
| Windows 11（NVIDIA） | faster-whisper（WSL2 上の CUDA） | **未検証** |

依存は `uv.lock` でハッシュごと固定しています。**`uv.lock` は公開の PyPI（`https://pypi.org/simple`）を取得元に
しています**（`pyproject.toml` の `[[tool.uv.index]]`。PC ごとの uv の設定より優先されます）。

### macOS（Apple Silicon）

```sh
brew install uv ffmpeg
cd tools/transcriber
uv sync
```

### Linux（NVIDIA）— 未検証

1. NVIDIA のドライバを入れます（`nvidia-smi` で GPU が見えること）。
2. パッケージマネージャで ffmpeg を入れ、uv を入れます（例: Ubuntu なら `sudo apt install ffmpeg`、uv は公式の手順）。
3. 依存を入れます。

   ```sh
   cd tools/transcriber
   uv sync
   ```

4. CUDA 12 の cuBLAS と cuDNN 9 が必要です。システムにない場合は pip のパッケージ（`nvidia-cublas-cu12`、
   `nvidia-cudnn-cu12`）でも入れられます（§7.4.4）。これらは現在の `pyproject.toml` には含めていません
   （必要かどうかを NVIDIA の PC で確かめてから決めます）。
5. GPU がなければ CPU（int8）で動きますが、遅くなります。

### Windows 11（NVIDIA）— 未検証

Windows 側に NVIDIA のドライバを入れ、WSL2（Ubuntu）の中で Linux と同じ手順を行います。Windows 専用の
手順はありません。作業フォルダ（コースフォルダ）は WSL 側のファイルシステム（例: `~/chunkflow/`）に置いて
ください（`/mnt/c` 経由は遅い）。

## 使い方

```sh
cd tools/transcriber
uv run chunkflow-transcribe ~/chunkflow/korean-course
uv run chunkflow-transcribe ~/chunkflow/korean-course ~/chunkflow/cantonese-course
uv run chunkflow-transcribe ~/chunkflow/korean-course/lesson01.mp3 --force
```

```text
chunkflow-transcribe <コースフォルダ（複数可） | 音声ファイル>
    [--target ko] [--base ja] [--model large-v3-turbo] [--detect-model NAME]
    [--engine auto|mlx|faster-whisper] [--force]
    [--schema PATH] [--config PATH] [--model-dir DIR] [-v]
```

- コースフォルダの直下の音声（mp3、m4a、wav、flac、ogg、opus など）だけを処理します（サブフォルダと
  `transcription/` は見ません）。
- JSON がすでにある音声は飛ばします（`--force` で作り直します）。別の版のツールで作った JSON は作り直します。
- 最後に、作成した JSON の一覧と出力先（`<コース>/transcription/`）を表示します。端末内の同じコースフォルダの
  `transcription/` に配置するか、アプリから取り込んでください（§7.4.2）。
- 出力は `docs/format/chunkflow-transcript-v1.schema.json` で検証してから置きます。Schema が見つからない
  場合は警告を出して Schema による検証を飛ばします（形式の規則の確認は常に行います）。

## 設定

### コースの設定（`<コース>/chunkflow.toml`）

```toml
[languages]
target = "yue"   # 学習する言語（Whisper の言語コード。zh-HK などの BCP-47 も可）
base = "en"      # 解説の言語。省略すると単一言語の教材（言語の判定を省く）

[model]
# name = "large-v3-turbo"    # 既定は large-v3-turbo。広東語（yue）を含むコースは large-v3
# detect = "large-v3-turbo"  # 言語の判定に別のモデルを使う場合

[hints.yue]
prompt = "佢哋喺度傾緊偈，我哋一齊聽下啦。"   # 書き起こしの文体を寄せるプロンプト

# 以下は必要なときだけ（既定値は src/chunkflow_transcriber/config.py などを参照）
[vad]
merge_gap_ms = 100
[chunking]
max_ms = 15000
[smoothing]
null_below = 0.6
```

コマンドの引数（`--target` など）が設定より優先します。言語ペアの指定がない場合は、ファイル全体の判定で
上位 2 言語を推定してログに出します（誤りやすいので、指定を推奨します）。

### PC ごとの設定（`~/.config/chunkflow-transcribe/config.toml`）

```toml
engine = "auto"           # auto | mlx | faster-whisper
device = "auto"           # faster-whisper: auto | cuda | cpu
# compute_type = "float16"
# model_dir = "~/models"  # モデルの保存先（既定: Hugging Face のキャッシュ）

# モデルの取得元とリビジョン（40 桁のコミットハッシュ）。既定の登録を上書き・追加する
[models.mlx."large-v3-turbo"]
repo = "mlx-community/whisper-large-v3-turbo"
revision = "a4aaeec0636e6fef84abdcbe3544cb2bf7e9f6fb"
```

既定で登録しているモデル（2026-10-14 に確認したリビジョンに固定）:

| エンジン | 名前 | 取得元 |
| --- | --- | --- |
| mlx | large-v3-turbo | `mlx-community/whisper-large-v3-turbo` @ `a4aaeec0…` |
| mlx | large-v3 | `mlx-community/whisper-large-v3-mlx` @ `49e6aa28…` |
| faster-whisper | large-v3-turbo | `dropbox-dash/faster-whisper-large-v3-turbo` @ `0a363e91…`（未検証） |
| faster-whisper | large-v3 | `Systran/faster-whisper-large-v3` @ `edaa852e…`（未検証） |

Silero VAD のモデルは、PyPI の `silero-vad` 6.2.3 の wheel から `silero_vad.onnx` を取り出して使います
（wheel と ONNX の SHA-256 を確かめます。`src/chunkflow_transcriber/vad.py`）。

## 開発

```sh
uv run ruff format .
uv run ruff check
uv run pytest            # モデルも GPU も使わない（エンジンと VAD は偽物）
uv run pytest -m model   # 実際のモデルを使うテスト（手元でのみ。.work/models にモデルを置く）
```

- 処理の流れは `src/chunkflow_transcriber/pipeline.py`（§7.4.4 の手順 1〜8）。エンジンは
  `engine/base.py` の 2 つの操作（言語の確率 / 言語を指定した文字起こし）だけを持ちます。
- mlx-whisper の言語の確率は、公開されていない内部 API（`mlx_whisper.decoding.detect_language`）で
  得ています。mlx-whisper の版は固定し、`tests/test_mlx_internal_api.py` で関数の存在を確かめます。

### スパイク S1 の計測（合成音声）

```sh
uv run python scripts/make_synthetic.py           # macOS の say と ffmpeg で .work/synthetic/ に作る
uv run python scripts/evaluate.py --models large-v3-turbo large-v3
```

### スパイク S1 の計測（実際の教材）

教材は著作物なので、作業フォルダは**リポジトリの外**に置きます（スクリプトはリポジトリの中を指定すると止まります）。

```sh
uv run python scripts/eval_real.py --work ~/chunkflow-eval \
    --course korean=/path/to/Korean_lesson_ja.mp3:ko:ja --models large-v3-turbo large-v3
# ~/chunkflow-eval/review/<コース>.review.csv の truth の列に正しい言語を記入してから
uv run python scripts/score_review.py ~/chunkflow-eval/review/korean.review.csv
```

生成した音声・JSON・計測結果は `.work/`（.gitignore 済み）に置き、コミットしません。実際の教材の音声や
文字起こしもコミットしません。結果は `docs/spikes/S1-pipeline-prototype.md` にまとめています。
