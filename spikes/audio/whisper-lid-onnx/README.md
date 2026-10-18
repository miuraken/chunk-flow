# whisper-lid-onnx: 一括言語判定の Whisper Tiny を ONNX Runtime で動かす（スパイク S3、担当: audio-analysis）

一括言語判定（第 1 段階）で使っている量子化 Whisper Tiny（`core/media/src/main/assets/whisper_tiny_lid_q8q4.bin`、
純 Kotlin の `WhisperTinyLanguageDetector`）の推論部分（エンコーダと融合デコーダ）を、同じ重みのまま ONNX にして
ONNX Runtime（CPU）で動かすための変換スクリプトと確認スクリプト。結果は `docs/spikes/S3-onnx-whisper-lid.md` にまとめる。

## ファイル

| ファイル | 内容 |
| --- | --- |
| `onnx_proto.py` | ONNX の protobuf（onnx.proto3 の必要な部分）を `google.protobuf` の記述子で組み立てる（`onnx` パッケージを使わない） |
| `lid_bin.py` | `.bin` を Kotlin の `parseModelBytes` と同じ順に読む（値と、ファイルの中のオフセット・長さ） |
| `reference.py` | Kotlin の推論（log-mel・エンコーダ・融合デコーダ・平均プーリング）を numpy で写した参照実装 |
| `build_onnx.py` | `.bin` から ONNX を作る |
| `verify_onnx.py` | 演算ごと（特に MatMulNBits の重みの解釈）とモデル全体を参照実装と比べる |
| `bench_onnx.py` | 作り方とセッションの設定の組み合わせごとに、速度と誤差を測る（Mac 用） |

## 実行方法

Python は文字起こしツールの仮想環境を使う（numpy・onnxruntime・protobuf が入っている。新しいパッケージは入れない）。

```sh
PY=/Users/miuraken/src/gogaku-player/tools/transcriber/.venv/bin/python
ASSETS=../../../core/media/src/main/assets   # このディレクトリから見た位置

# ONNX を作る（アプリに入れているもの。何度作っても同じバイト列になる）
$PY build_onnx.py --bin $ASSETS/whisper_tiny_lid_q8q4.bin --out $ASSETS/whisper_tiny_lid.onnx

# 確かめる（protobuf の定義・演算ごと・モデル全体）
$PY verify_onnx.py --bin $ASSETS/whisper_tiny_lid_q8q4.bin --onnx $ASSETS/whisper_tiny_lid.onnx \
    --config session.disable_quant_qdq=1

# 速度と誤差を比べる（作業用のディレクトリに組み合わせごとの ONNX を書く）
$PY bench_onnx.py --bin $ASSETS/whisper_tiny_lid_q8q4.bin --out-dir <作業用ディレクトリ>

# Lint（このディレクトリで実行する。ほかの場所からだと、このディレクトリのモジュールを first-party と判定できない）
RUFF=/Users/miuraken/src/gogaku-player/tools/transcriber/.venv/bin/ruff
$RUFF format --check --line-length 100 --target-version py312 .
$RUFF check --line-length 100 --target-version py312 --select E,F,W,I,B,UP,SIM,RUF \
    --ignore RUF001,RUF002,RUF003,E501 .
```

`build_onnx.py` の選択肢（既定はアプリに入れているもの）:

- `--q4-accuracy-level {0,1,4}`: MatMulNBits の accuracy_level。4 は入力を int8 にして計算する（Mac で約 1.5 倍速いが、
  ロジットが合成音声で最大 0.5、実際の教材で最大 0.9 ずれる。精度の評価は `docs/spikes/S3-onnx-whisper-lid.md`）。
- `--q8 {dq,nbits8}`: Q8 の重みを DequantizeLinear + Gemm にするか、MatMulNBits（bits=8）にするか。
- `--attention {matmul,mha}`: エンコーダの自己注意を標準の演算にするか、com.microsoft.MultiHeadAttention にするか。
- `--no-nbits-bias`: MatMulNBits の bias 入力を使わず、Add で足す。
- `--external-data`: `.bin` の中のテンソルを外部データ（`.bin` のオフセットと長さ）として参照する、重みを持たないグラフを作る。

## グラフ（`whisper_tiny_lid.onnx`）

- 入力 `mel` float32 [1, 300, 80]（Kotlin が計算した正規化 log-mel。時間が先の並び = Kotlin の `mel300x80` のまま）
- 出力 `logits` float32 [1, 99]（言語の並びはメタデータ `chunkflow.language_codes`）、
  `audio_features` float32 [1, 150, 384]（LayerNorm 後のエンコーダ出力）
- opset: ai.onnx 17 + com.microsoft 1、IR version 8。ノード 229 個、初期値 205 個、11,245,995 バイト。
- 中身（すべて 2 次元のテンソルで計算する）:
  - conv1 / conv2: 0 埋めの Pad + Slice（stride 1 / 2）+ Concat で im2col にして Gemm。`.bin` の重みの並び
    [o][k][c]（k=0 が t−1）がそのまま im2col の列の並びになる。GELU は com.microsoft.Gelu（erf を使う正確な式）。
  - Q8 の重み（行ごとの scale）: DequantizeLinear(int8, axis=0) → Gemm(transB=1)。
  - Q4 の重み（64 要素ごとの scale）: com.microsoft.MatMulNBits（bits=4, block_size=64, accuracy_level=0, zero point は省略 = 8,
    bias 入力あり）。`.bin` の packed（[N][K/64][32 バイト]、下位ニブルが偶数列）と scale（[N][K/64]）をそのまま入れる。
  - 自己注意: Reshape・Transpose・MatMul・Softmax（スケール 1/8 は q に掛ける）。LayerNormalization（opset 17、eps 1e-5）。
  - 融合デコーダ: Kotlin の `runFusedDecoder` と同じ式。ヘッドごとの積（q_h·W_k,h と pooled_h·W_v,h^T）は、
    ブロック対角のマスク [6, 384] を掛けて 1 回の行列積にまとめる（ReduceSum で対角のブロックを取り出す）。
- メタデータ: 元の `.bin` の SHA-256（`chunkflow.source_sha256`）、言語コード、作り方の選択肢。

アプリ側のセッションの設定（`OnnxWhisperLidEngine.create`）: CPU、最適化 ALL、intra-op 1 スレッド・inter-op 1・逐次実行、
`session.intra_op.allow_spinning=0`、`session.disable_quant_qdq=1`（Q8 の DequantizeLinear を読み込み時に float の重みへ畳む）。

## 外部データ方式（APK を増やさない案）

`--external-data` で作ったグラフ（約 50 KB）は、191 個のテンソルを `.bin` の位置（オフセットと長さ）で参照し、
14 個の小さな定数（形やマスク）だけを持つ。次を確かめた（結果は重み入りの ONNX と完全に同じ）。

- Python（onnxruntime 1.30）: `.bin` を同じディレクトリに置いてファイルのパスから読む → 読める。
- Python: バイト列から読み、`SessionOptions.add_external_initializers` で `.bin` から切り出したテンソルを渡す → 読める。
- Python: `SessionOptions.add_initializer` で渡す → 読めない（バイト列から読んだモデルの外部データのパスの検証で失敗する）。
- Java（onnxruntime 1.31、JVM）: バイト列から読み、`SessionOptions.addExternalInitializers` で `.bin` の direct バッファを
  `slice()` したテンソルを渡す → 読める（テンソルはセッションにコピーされるので、作成後に閉じてよい）。

アプリでの使い方の案と課題は `docs/spikes/S3-onnx-whisper-lid.md` §6 に書く。

## 実機での計測（debug ビルド）

Pixel などの実機で、純 Kotlin と ONNX Runtime の速度（1 チャンクの推論時間、実際のデコードを含む全体時間の逐次 / 3 並列）と
判定の一致を測る計測ツールを `core/media/src/debug/`（`LidBenchActivity`）に置いた。adb の `am start` で起動する。
入力の置き場所・起動の例・logcat の読み方は `docs/spikes/S3-onnx-whisper-lid.md` §9 に書く。
