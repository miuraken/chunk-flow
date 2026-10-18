# S3 追加: 一括言語判定の Whisper Tiny を ONNX Runtime で動かす（実験）

- **担当**: audio-analysis（lead からの依頼）
- **日付**: 2026-10-18
- **ブランチ**: `agent/audio-analysis/onnx-whisper-lid`（main の 24f1d31 から。main には統合しない実験版）
- **関連**: ADR 0010 §2.1・§2.4・§2.5、`spikes/audio/whisper-lid-onnx/`

一括言語判定（第 1 段階）で使っている純 Kotlin の量子化 Whisper Tiny（`WhisperTinyLanguageDetector`）の推論部分
（エンコーダと融合デコーダ）を、同じ重み（量子化の値もそのまま）から作った ONNX にして、ONNX Runtime（CPU）で動かした。
log-mel の計算・平均プーリング・同系言語の合算と 2 言語の判定・ファイル内の補正は、main と同じ Kotlin のコードを使う。
数値はすべて Mac（M3 Pro、12 コア）の JVM / Python で測った。**実機（Pixel）では測っていない（未確認）。**

## 0. まとめ

1. **結果は main と同じ**: 実際の教材 2 本（764 チャンク）で、Whisper 単体の判定は全チャンクで一致し、
   2 言語の確率の差は最大 9.1e-6、ロジットの差は最大 1.4e-4（演算の順序による丸め誤差）。
   本番と同じ流れ（チャンク → 言語判定 → ファイル内の補正）の判定も全チャンクで main と同じで、
   正解率は main と同じ（en→hi 96.6%、ja→ko 97.9%）。
2. **推論は約 29 倍速い**（Mac の JVM、1 スレッド）: 1 チャンクの推論（エンコーダ + デコーダ）は Kotlin 802.5 ms → ONNX Runtime 27.3 ms。
   log-mel（Kotlin のまま）は約 2 ms。`detectChunkLanguages` を通した 60 チャンクの全体時間は、3 並列で
   16.9 秒 → 0.61 秒（en→hi）、16.8 秒 → 0.60 秒（ja→ko）（§5.4。実機では音声の切り出しの時間が加わる）。
3. **セッションの設定**: CPU、intra-op 1 スレッド、チャンク単位で最大 3 並列（今の設計のまま）。
   「1 件ずつ × 3 スレッド」より「3 並列 × 1 スレッド」の方が速かった（§5.3）。
   `session.disable_quant_qdq=1`（Q8 の DequantizeLinear を読み込み時に float に畳む）は、畳まない場合より速く、推論中のメモリも少なかった。
4. **APK は約 9.7 MB 増える**（ONNX のアセット 11.2 MB を圧縮して 9.68 MB。ONNX Runtime のネイティブライブラリは sherpa-onnx 用にすでに入っている）。
5. **MatMulNBits の accuracy_level=4**（入力を int8 にして計算）は 1.5 倍ほど速いが、ロジットが最大 0.93 ずれ、
   本番相当の判定が en→hi で 8 チャンク変わった（正解率は 96.6% → 97.3%、ja→ko は 97.9% のまま）。lead の方針で既定は 0（float 相当）。
6. **APK を増やさない案（重みを持たないグラフ + .bin を渡す）は使える**: Python と Java の API の両方で、
   重み入りの ONNX と完全に同じ出力になった（§6）。今回は実装していない。

## 1. 作ったもの

| 場所 | 内容 |
| --- | --- |
| `spikes/audio/whisper-lid-onnx/` | `.bin` から ONNX を作る変換スクリプト（`onnx` パッケージを使わず protobuf を組み立てる）、Kotlin の推論を numpy で写した参照実装、確認・速度比較のスクリプト、README |
| `core/media/src/main/assets/whisper_tiny_lid.onnx` | 変換した ONNX（11,245,995 バイト、SHA-256 `1489f006…63c9`。スクリプトで何度作っても同じバイト列） |
| `core/media/.../WhisperLidNeuralEngine.kt` | 推論エンジンのインターフェースと、ONNX Runtime で実行する `OnnxWhisperLidEngine`（セッションの設定・入出力の検証） |
| `core/media/.../WhisperTinyLanguageDetector.kt` | 推論を ONNX Runtime で行い、読み込みや推論に失敗したら純 Kotlin に切り替える。どちらで推論したかと時間の累計（`inferenceStats`） |
| `core/media/.../OnDeviceSpeechTranscriber.kt` | 一括言語判定（`detectChunkLanguages`）の終わりに、推論エンジンと推論時間を診断ログに出す |
| `core/media/src/test/.../WhisperLidOnnxRuntimeTest.kt` | ONNX Runtime と純 Kotlin の一致、切り替え、並列推論、診断ログの単体テスト（10 件、合成音声） |
| `core/media/src/debug/`（`bench/LidBench*.kt`、`AndroidManifest.xml`） | 実機の計測ツール（debug ビルドだけ）。同じチャンクを純 Kotlin と ONNX Runtime で判定し、逐次と 3 並列の時間と判定の一致を出す（§9） |
| `core/media/src/testDebug/.../bench/` | 計測ツールの単体テスト（引数・入力の検証、集計と出力の行、合成音声での流れ。16 件） |

## 2. ONNX モデル

### 2.1 入出力と構成

- 入力 `mel` float32 [1, 300, 80]（Kotlin が計算した正規化 log-mel。時間が先 = Kotlin の `mel300x80` の並びのまま）
- 出力 `logits` float32 [1, 99]、`audio_features` float32 [1, 150, 384]（LayerNorm 後のエンコーダ出力）
- opset ai.onnx 17 + com.microsoft 1、IR version 8。ノード 229 個。

| 演算子 | 数 | 使いどころ |
| --- | --- | --- |
| Gemm | 37 | Q8 の線形層（transB=1）、im2col 後の畳み込み、デコーダのスコア |
| DequantizeLinear | 36 | Q8 の重み（int8、行ごとの scale、axis=0、zero point 0） |
| com.microsoft.MatMulNBits | 16 | Q4 の MLP（bits=4、block_size=64、accuracy_level=0、zero point 省略 = 8、bias 入力あり） |
| LayerNormalization | 20 | pre-LN（eps 1e-5） |
| MatMul / Softmax / Reshape / Transpose / Mul | 15 / 8 / 18 / 16 / 14 | 注意（スケール 1/8 は q に掛ける）、融合デコーダ |
| com.microsoft.Gelu | 10 | conv の後と MLP（erf を使う正確な式） |
| Pad / Slice / Concat | 2 / 6 / 2 | 畳み込みの im2col（0 埋め、stride 1 / 2） |
| ReduceSum / Add / Identity | 4 / 24 / 1 | デコーダのヘッドごとの積の取り出し、残差、出力名 |

初期値（205 個）: int8 36 個 5.44 MB（Q8 の重み）、uint8 16 個 4.72 MB（Q4 の packed）、float32 141 個 1.06 MB
（scale・bias・LayerNorm・位置埋め込み・デコーダ層 0 の定数・ブロック対角のマスク）、int64 12 個（形）。
メタデータに元の `.bin` の SHA-256・言語コードの並び・作り方の選択肢を入れた。アプリは言語コードの並びが `.bin` と違えば使わない。

### 2.2 量子化の値の対応（Kotlin と同じ値になることを確認）

- **Q4 → MatMulNBits は詰め直し不要**: Kotlin の Q4 は [N][K/64][32 バイト]（下位ニブルが偶数列、値 = ニブル − 8）、
  scale は [N][K/64] の順で、MatMulNBits の B・scales・zero point の既定値（8）とそのまま一致した。
  `.bin` の実際の重み（enc0.mlp1 など 3 つ）で、MatMulNBits 1 つだけのグラフと Kotlin の `dequantizeQ4Row` の結果の差は **0**。
  ニブルの順を入れ替えると差は 3.3〜7.5 になる（確認の感度）。
- **Q8**: DequantizeLinear(axis=0) + Gemm(transB=1)。Kotlin の `linearQ8Batch` との差は最大 5.2e-6（和の順序の違い）。
- **畳み込み**: `.bin` の重みの並び [o][k][c]（k=0 が t−1）がそのまま im2col の列の並びになるので、重みを並べ替えない。
  Kotlin の「一定のフレームを使い回す」処理は速くするためだけなので、全フレームを計算しても値は同じ。
- **融合デコーダ**: Kotlin の `runFusedDecoder` と同じ式。ヘッドごとの積（q_h·W_k,h と pooled_h·W_v,h^T）は、
  ブロック対角のマスク [6, 384] を掛けて 1 回の行列積にまとめた（計算量の増加はデコーダの 1 トークン分だけで無視できる）。
- GELU は Kotlin では erf の近似（A&S、誤差 1.5e−7 以下）、ONNX Runtime は erf の実装。LayerNorm は分散の計算方法が違い得る。
  どちらも差は丸め誤差の大きさ。

### 2.3 protobuf の生成と確認

`onnx` パッケージ（と `torch.onnx.export`）はないので、`google.protobuf` の記述子で onnx.proto3 の必要なメッセージを組み立てた
（`onnx_proto.py`。enum は int32、oneof は普通のフィールドとして定義）。定義が正しいことは、ONNX Runtime に同梱のモデル 3 つ
（`onnxruntime/datasets/*.onnx`）を読めること（演算子・属性の型・初期値が読める）と、作ったモデルを ONNX Runtime 1.30（Python）・
1.31（JVM）が読み込み、参照実装と同じ値を出すことで確かめた。

## 3. Kotlin 側

- `WhisperTinyLanguageDetector` の推論（`infer`）は、log-mel を Kotlin で計算したあと、`OnnxWhisperLidEngine` で推論する。
  読み込みや推論に失敗したら純 Kotlin の推論（今までの `runAudioEncoder` + `runFusedDecoder`）で推論する。公開 API
  （`detectLanguagePair` / `analyzeLanguagePair` / `predictAllLanguageLogits`）と前後の処理は変えていない。
- **セッション**: アセットの ONNX はプロセスで 1 つのセッションを共有し、`OrtSession.run` を複数のスレッドから同時に呼ぶ。
  最初の推論のときに読み込む（読み込み中に来たほかのスレッドは読み込みを待つ）。設定は CPU、最適化 ALL、
  intra-op 1・inter-op 1・逐次実行、`session.intra_op.allow_spinning=0`、`session.disable_quant_qdq=1`。
- **切り替え**: ONNX がない・壊れている・入出力の形や言語の並びが違う・ネイティブライブラリを読めない（`LinkageError`）場合は、
  理由を残して純 Kotlin で推論する。推論で例外が出た場合は、そのチャンクを純 Kotlin で推論し、以後はそのプロセスでは
  ONNX Runtime を使わない（失敗を繰り返さない。ほかのスレッドが推論中かもしれないのでセッションは閉じない）。
- **メモリ**: ONNX Runtime で推論する間は、純 Kotlin の重み（約 11 MB の Java ヒープ）を読まない（`.bin` の先頭の
  言語コードと mel フィルタだけを読む）。作業領域の純 Kotlin 用の大きなバッファ（約 3 MB × 3）も、純 Kotlin で推論するまで確保しない。
- **診断ログ**: `detectChunkLanguages` の前後で判定器の推論の累計を取り、終わりに次の 1 行を `recentDiagnostics` に出す（§7）。

## 4. 評価の方法

- 評価データとハーネスは lead の作業ディレクトリのもの（読むだけ）。実際の教材 2 本（Hindi_1_Lesson_16: 371 チャンク、
  Korean_lesson_ja: 393 チャンク）を、アプリと同じチャンク境界・同じ切り出し（チャンクの先頭から最大 2.8 秒、窓 2.0 秒）で判定した。
- 正解は Whisper large-v3 の判定（確かなラベルのチャンクだけ。`score_production.py`）。
- 一時的なハーネス（`LidOnnxEvalHarnessTest`、コミットしない）で、純 Kotlin（`LidEngineMode.KOTLIN_ONLY`）と ONNX Runtime の
  両方で全チャンクを判定し、本番と同じ流れ（`VoiceProfileLanguageTracker` での補正とアンカーの登録）を再現した
  `production_<name>.json` を書いた。純 Kotlin で再現した判定は main の `production_<name>.json` と全チャンクで一致した（ハーネスの確認）。

## 5. 結果

### 5.1 一致（ONNX Runtime と純 Kotlin）

| | en→hi（371） | ja→ko（393） |
| --- | --- | --- |
| 判定できなかったチャンク（無音など）の食い違い | 0 | 0 |
| Whisper 単体の判定の一致 | 371 / 371 | 393 / 393 |
| 2 言語の確率（target）の最大差 | 9.1e-6 | 8.4e-6 |
| pairMass の最大差 | 8.9e-6 | 5.9e-6 |
| 平均ベクトル（384 次元）の最大差 | 1.2e-5 | 1.8e-5 |
| ロジットの最大差（チャンクごとの最大の平均） | 1.4e-4（4.2e-5） | 1.0e-4（3.2e-5） |
| エンコーダ出力の最大差（値の大きさは最大 20 程度） | 1.8e-3 | 2.5e-3 |
| ONNX Runtime で推論した回数 / 純 Kotlin に切り替えた回数（※） | 742 / 0 | 786 / 0 |

※ 各チャンクを 2 回推論した（生の出力の比較と、本番と同じ流れの再現）。純 Kotlin への切り替えは 1 回も起きなかった。

合成音声（6 本）では、ロジットの差は最大 3.6e-5、エンコーダ出力の差は最大 7e-4（Python の参照実装との比較）。
単体テストの許容誤差は、ロジット 2e-3、エンコーダ出力 1e-2、確率と平均ベクトル 1e-3 にした（実測の 10 倍程度）。

### 5.2 精度（本番と同じ流れ）

| 教材 | main | 純 Kotlin（再現） | ONNX Runtime | ONNX Runtime + accuracy_level 4（参考） |
| --- | --- | --- | --- | --- |
| en→hi（確かなラベル 262） | 96.6%（誤り 9） | 96.6%（誤り 9） | **96.6%（誤り 9）** | 97.3%（誤り 7） |
| ja→ko（同 388） | 97.9%（誤り 8） | 97.9%（誤り 8） | **97.9%（誤り 8）** | 97.9%（誤り 8） |

ONNX Runtime 版の本番相当の判定は、全チャンク（371 + 393）で main と同じで、補正後の確率の差は最大 8e-6。
対象言語の再現率（hi 92.9%、ko 96.6%）、1.5 秒未満のチャンクの正解率、確率 0.75 未満の件数もすべて main と同じ。
accuracy_level 4 は、Whisper 単体の判定が en→hi で 3 チャンク、補正後の判定が 8 チャンク変わった（確率の差は最大 0.22）。
ja→ko では判定は変わらなかった（確率の差は最大 0.08）。

### 5.3 速度（1 チャンクの推論）

**JVM（Kotlin の判定器 + onnxruntime-jvm 1.31、実際の教材の 48 チャンク）**。推論はエンコーダ + デコーダだけ、log-mel は別。

| | 純 Kotlin | ONNX Runtime | 倍率 |
| --- | --- | --- | --- |
| 1 チャンクの推論（1 スレッド） | 802.5 ms | **27.3 ms** | 29 倍 |
| log-mel（どちらも同じ Kotlin のコード） | 2.0 ms | 2.0 ms | — |
| 1 チャンクあたりの壁時計の時間（1 / 2 / 3 / 4 スレッドで同時に推論） | 805 / 410 / 278 / 210 ms | 29.4 / 15.1 / 10.1 / 7.9 ms | 27 倍程度 |

**作り方とセッションの設定の比較（Python の onnxruntime 1.30、合成音声の log-mel、中央値）**。「intra」は 1 件を何スレッドで計算するか、
「並列」は 1 つのセッションを何スレッドから同時に呼ぶか。誤差は参照実装（Kotlin の numpy 写し）との最大差。

| 作り方 | DQ を畳む | 1 件・intra 1 | intra 2 | intra 3 | 3 並列 × intra 1（1 件あたり） | ロジットの誤差 | エンコーダ出力の誤差 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| **採用（Q8: DQ + Gemm、Q4: MatMulNBits、accuracy_level 0）** | **する** | **26.3 ms** | 14.9 ms | 11.2 ms | **9.4 ms** | 3.6e-5 | 3.5e-4 |
| 同上 | しない | 27.9 ms | 15.6 ms | 11.7 ms | 9.9 ms | 3.4e-5 | 6.8e-4 |
| MatMulNBits の accuracy_level 4 | する | 17.3 ms | 10.2 ms | 7.4 ms | 6.3 ms | 0.52 | 1.1 |
| 自己注意を com.microsoft.MultiHeadAttention | する | 26.7 ms | 15.1 ms | 11.2 ms | 9.6 ms | 3.8e-5 | 4.2e-4 |
| MatMulNBits の bias 入力を使わず Add | する | 26.9 ms | 15.5 ms | 11.2 ms | 9.9 ms | 3.6e-5 | 3.5e-4 |
| Q8 も MatMulNBits（bits=8） | しない | 29.3 ms | — | — | 10.5 ms | 3.4e-5 | 6.8e-4 |

- 「3 並列 × intra 1」が「1 件ずつ × intra 3」より速い（9.4 ms と 11.2 ms。JVM の全体時間でも同じ傾向、§5.4）。今の設計
  （intra 1、チャンク単位で最大 3 並列）のままにした。
- DequantizeLinear を読み込み時に畳む（`session.disable_quant_qdq=1`）と約 5% 速く、推論中のメモリも少なかった（§5.5）。
- MultiHeadAttention・bias の入れ方・Q8 の MatMulNBits 化は、速くならなかった。

### 5.4 速度（`detectChunkLanguages` を通した全体時間）

lead の E2E ハーネスと同じ条件（各教材の先頭 60 チャンク、本物の判定器とファイル内の補正、ハーネスのデコーダはメモリの PCM を流すだけ）。

| 教材 | エンジン | 逐次 | 2 並列 | 3 並列 |
| --- | --- | --- | --- | --- |
| en→hi | 純 Kotlin（main と同じ） | 47.8 秒 | 24.5 秒（1.95 倍） | 16.9 秒（2.83 倍） |
| en→hi | **ONNX Runtime** | **1.81 秒** | **0.92 秒**（1.97 倍） | **0.61 秒**（2.97 倍） |
| ja→ko | 純 Kotlin（main と同じ） | 47.1 秒 | 24.5 秒（1.92 倍） | 16.8 秒（2.81 倍） |
| ja→ko | **ONNX Runtime** | **1.79 秒** | **0.91 秒**（1.97 倍） | **0.60 秒**（2.96 倍） |

- 3 並列どうしで約 28 倍速い。並列の結果は逐次と全チャンクで同じで、結果はチャンクの順に届いた（両エンジンとも）。
  判定された言語の内訳も両エンジンで同じ（en 33 / hi 27、ja 24 / ko 36）。
- ONNX Runtime を「1 件ずつ × intra 2 / 3 スレッド」にすると 1.06 / 0.91 秒（en→hi）、1.04 / 0.80 秒（ja→ko）で、3 並列 × intra 1 より遅い。
- このハーネスでは音声の切り出しがほぼ 0（60 チャンクで 13〜39 ms）。実機では MediaCodec のデコードの時間が加わり、
  これは ONNX Runtime では速くならないので、実機の全体時間の倍率はこれより小さくなる（未確認）。

### 5.5 読み込み時間・APK・メモリ

- **読み込み時間**（JVM）: ONNX の読み出し 2〜3 ms、セッションの作成 45 ms（2 回目以降 20〜23 ms）。プロセスで最初の 1 回だけ、
  ONNX Runtime のネイティブライブラリの初期化に約 0.8 秒かかった（JVM では jar から取り出して読み込むため。Android では
  APK の `libonnxruntime.so` を読み込むので、短いと見込むが未確認）。診断ログの「ONNX の読み込み」はこれらの合計（JVM で 855〜1,147 ms）。
  参考: 純 Kotlin の `.bin` の読み出しと解析は 3〜14 ms（ONNX Runtime で推論する間は、`.bin` の先頭 256 KB だけを読む）。
- **APK**（`:app:assembleDebug` の APK の中身）: `assets/whisper_tiny_lid.onnx` は 11,245,995 バイトで、Deflate で
  **9,678,244 バイト**として入る（14% 縮む。`.bin` も同じく 9,673,251 バイト）。増えるのはこのアセットだけで、
  ONNX Runtime のネイティブライブラリ（`libonnxruntime.so`、arm64-v8a で 34.2 MB、非圧縮）は main の APK にもすでに入っている
  （文字起こしの sherpa-onnx 形式の Whisper、`SherpaOnnxWhisperTranscriber` のために `core:media` が `onnxruntime-android` に
  すでに依存しているため。依存は追加していない）。§6 の外部データ方式なら、増分は約 50 KB になる。
- **メモリ**（Python の onnxruntime で、別プロセスの RSS の増分を測った参考値。端末では未確認）:

| 設定 | セッション作成後 | 3 並列で推論した後 |
| --- | --- | --- |
| **採用（DQ を畳む）** | +43〜47 MB | **+72〜75 MB** |
| DQ を畳まない | +23〜28 MB | +114〜121 MB |
| Q8 も MatMulNBits（bits=8） | +24〜40 MB | +76〜87 MB |

  畳まない場合は、推論のたびに作る float の重み（DequantizeLinear の出力）を作業領域に持つため、推論中のメモリが多くなる。
  純 Kotlin の推論は Java ヒープに重み約 11 MB + 作業領域 約 3.5 MB × 3。ONNX Runtime で推論する間は、Java ヒープは
  mel フィルタ（64 KB）と作業領域 約 0.4 MB × 3 だけで、重みと作業領域はネイティブのメモリになる。

## 6. APK を増やさない案（重みを持たないグラフ + .bin を渡す）

**使える**（今回は実装していない。lead の方針どおり、確認と手順の整理まで）。

- `build_onnx.py --external-data` で、`.bin` の中のテンソル 191 個を外部データ（`location=whisper_tiny_lid_q8q4.bin`、
  `offset`・`length`）として参照する、重みを持たないグラフを作れる（**51,528 バイト**。形とマスクの小さな定数 14 個だけを持つ）。
  §2.1 のグラフは、初期値がすべて `.bin` の連続した範囲そのものになるように作ってある（Q8 は [out, in] の int8 と行の scale、
  Q4 は packed と scale、畳み込みの重みも並べ替えない）ので、重み入りの版と同じグラフのまま外部データにできる。
- 確かめたこと（いずれも重み入りの ONNX と出力が**完全に同じ**）:

| 方法 | Python（onnxruntime 1.30） | Java（onnxruntime 1.31、JVM） |
| --- | --- | --- |
| `.bin` を同じディレクトリに置き、ファイルのパスから読む | 読める | 未確認（アセットはファイルでないので、アプリでは使いにくい） |
| バイト列から読み、`addExternalInitializers` で `.bin` から切り出したテンソルを渡す | 読める | 読める（191 個のテンソルの用意 3〜10 ms、セッション作成 16〜29 ms） |
| バイト列から読み、`addInitializer` で渡す | 読めない（バイト列から読んだモデルの外部データのパスの検証で失敗） | 未確認（同じ C API なので同じと考えられる） |

Kotlin で実装する場合の手順:

1. ONNX は重みなしの版（約 50 KB）だけをアセットに入れる。`.bin` は今のまま 1 つ（重みの二重持ちがなくなる）。
2. テンソルの名前・型・形・`.bin` の中の位置の表が要る。Kotlin には ONNX の protobuf を読む部品がないので、
   (a) 変換スクリプトが表（例: TSV / JSON）も書き出してアセットに入れる、(b) Kotlin の `parseModelBytes` と同じ順に位置を数える、
   (c) ONNX の `TensorProto` の外部データの部分だけを読む小さなパーサを書く、のどれか。(a) が簡単で確実（確認では (a) を使った）。
3. `.bin` を direct の `ByteBuffer`（リトルエンディアン）に読み、表の範囲を `slice()` して `OnnxTensor.createTensor`
   （float は `asFloatBuffer()`、int8 / uint8 は `OnnxJavaType.INT8` / `UINT8`）で作る。direct バッファなのでコピーしない。
4. `SessionOptions.addExternalInitializers(map)` に渡してから `createSession(graphBytes, options)`。Java の API の説明どおり
   テンソルはセッションにコピーされるので、作成後はテンソルを閉じ、direct バッファも捨ててよい（確認でもそうした）。
5. 注意: `addInitializer`（コピーしない版）は使えない（上の表）。`.bin` と ONNX の表がずれると別の重みで推論してしまうので、
   ONNX のメタデータの `chunkflow.source_sha256` と `.bin` の SHA-256（約 11 MB、端末で数十 ms の見込み・未確認）を照合するか、
   少なくとも大きさとテンソルの数を照合する。読み込みの時間は、`.bin` を一度読む分だけ増える（Mac で数 ms）。

## 7. 実機で比べるときに見る項目

アプリの普段の操作で main と比べるときの見方。同じチャンクを両方のエンジンで決まった手順で測るときは、§9 の計測ツールを使う。

- **ビルドの切り替え**: main（純 Kotlin）とこのブランチ（ONNX Runtime）をそれぞれビルドして入れ替える。このブランチには
  実行中に推論エンジンを切り替える設定はない（ONNX Runtime を使えないときだけ自動で純 Kotlin になる）。
- **同じ条件にする**: 同じ教材・同じチャンク（再分割しない）で、言語判定を消してから一括言語判定をやり直す。端末が熱いと
  1 件ずつになる（「端末の温度が高いか省電力モードのため、言語判定を 1 件ずつ実行」が出たら条件が違う）。
- **診断ログ**（プレイヤー画面の右上の設定アイコン → ファイル設定シート →「診断ログ」）に出る行:

| 行 | main | このブランチ | 見ること |
| --- | --- | --- | --- |
| `言語判定のみを開始 (N チャンク、最大 P 並列)` | 出る | 出る | N と P が同じか |
| `推論エンジン: onnxruntime（N 件、推論 平均 X ms/チャンク、log-mel 平均 Y ms、ONNX の読み込み Z ms）` | 出ない | 出る | X（エンコーダ + デコーダだけの時間。並列で動かした 1 件ごとの時間）、Y、Z（最初の一括判定だけ意味がある） |
| `推論エンジン: kotlin（…）。onnxruntime を使えない: <理由>`（警告） | 出ない | ONNX Runtime を使えなかったときだけ | 理由（ONNX のファイルがない / 読み込みに失敗 (例外名) / ネイティブライブラリを読み込めない / 推論に失敗 (例外名)） |
| `言語判定のみを終了 (n / N チャンク、T 秒、平均 A ms/チャンク)` | 出る | 出る | **T と A を main と比べる**（切り出し・log-mel・推論・補正・保存を含む壁時計の時間） |

- 判定結果は main と同じになるはず（§5.2）。言語バッジの付き方が main と違うチャンクがあれば知らせてほしい。
- ONNX Runtime 版では推論が速くなるので、全体の時間は音声の切り出し（デコード）と log-mel（Kotlin）の時間が占める割合が大きくなる
  （§5.4）。実機での内訳は、診断ログの X・Y と A の差から見積もれる。

## 8. 残っている課題・判断が必要なこと

1. **実機（Pixel）で測っていない**（未確認）。§9 の計測ツールで、純 Kotlin と ONNX Runtime の 1 チャンクの推論時間と、
   実際のデコードを含む全体時間（逐次 / 3 並列）、判定の一致を測ってほしい（qa）。アプリの普段の操作での比べ方は §7。
   ONNX Runtime の ARM64 の CPU カーネル（MLAS の SGEMM・MatMulNBits）は NEON で最適化されているので、Mac と同程度の
   倍率（数十倍）を見込むが、実測が要る。
2. **APK の増分（約 9.7 MB）を受け入れるか**。受け入れない場合は §6 の外部データ方式にする（実装は 1〜2 日の見込み。
   ONNX は約 50 KB になり、重みは `.bin` の 1 つだけになる）。
3. **純 Kotlin の推論を残すか**: このブランチでは切り替え先として残した（重みは使うときだけ読む）。ONNX Runtime を正式に使うなら、
   ネイティブライブラリを読めない端末（ABI の違いなど）のために残すのが安全。残す場合、テスト（一致の確認）も残す。
4. **accuracy_level 4** は Mac で約 1.5 倍速いが、結果が変わる（§5.2）。0 でも十分速いので、採用は実機の数字を見てから判断する。
5. **一括言語判定以外の経路**（第 2 段階の文字起こしの前の言語判定、`detectChunkLanguage`）も同じ判定器を使うので、
   このブランチではそちらも ONNX Runtime になる（結果は同じ）。診断ログの推論エンジンの行は一括言語判定のときだけ出す。
6. **ADR に書く内容の案**（ADR 0010 §2.1 の「推論ランタイムを追加せず純 Kotlin」を改める場合）:
   - 決定: 2 言語判定の Whisper Tiny の推論（エンコーダと融合デコーダ）は ONNX Runtime（CPU、`onnxruntime-android`、
     sherpa-onnx 用にすでに依存している）で行う。モデルは `.bin` と同じ量子化の値の ONNX（Q8: DequantizeLinear + Gemm、
     Q4: MatMulNBits、accuracy_level 0）。log-mel・平均プーリング・同系言語の合算・2 言語の判定・ファイル内の補正は Kotlin のまま。
   - セッション: 1 つを共有、intra-op 1 スレッド、チャンク単位で最大 3 並列（ADR 0010 §2.5 のまま）、
     `session.disable_quant_qdq=1`。
   - 切り替え: 読み込み・推論に失敗したら純 Kotlin で推論し、理由を診断ログに出す。
   - 根拠: 実際の教材 2 本で判定と正解率が main と同じ（§5.1・§5.2）、Mac で推論が約 29 倍速い（§5.3）、実機の結果（未確認）。
   - トレードオフ: APK が約 9.7 MB 増える（外部データ方式なら約 50 KB）。推論中のネイティブのメモリが +72〜75 MB
     （Python での参考値。端末は未確認）。その代わり Java ヒープの重み（約 11 MB）と作業領域（約 9 MB）は使わない（§5.5）。
   - 設計書の §4.2 のライブラリの表（「純 Kotlin 量子化 Whisper Tiny 2 言語判定」）、§7.4.6、付録 C の「NDK / JNI に依存せず」の
     記述も合わせて直す。

## 9. 実機の計測ツール（debug ビルドだけ）

同じチャンクを純 Kotlin と ONNX Runtime の両方で判定し、1 チャンクの推論時間と、`AndroidSpeechRecognizerChunkTranscriber.detectChunkLanguages`
を通した全体時間（本番と同じ `MediaCodecAudioStreamDecoder` での実際のデコードを含む）を、逐次と 3 並列で測る。両エンジンの最終判定の
一致率も出す。新しい依存は使っていない。

- 場所: `core/media/src/debug/`（`bench/LidBenchActivity.kt` ほかと、debug 用の `AndroidManifest.xml`）。`src/debug` は debug の変種で
  だけコンパイル・マージされるので、release には入らない（§9.6）。
- 本番の動作は変えない。エンジンは判定器の `LidEngineMode` で選ぶ（純 Kotlin は `KOTLIN_ONLY`、ONNX Runtime は本番と同じ `AUTO`）。
  - ウォームアップで ONNX Runtime を読み込めなければ `ERROR ONNX_UNAVAILABLE reason=…` を出し、ONNX Runtime の回は計測しない
    （`status=SKIPPED`）。
  - 計測中に純 Kotlin へ切り替わった推論が 1 件でもあれば、その回は `status=FALLBACK` にして、ONNX Runtime の結果として扱わない。
- Activity は adb から起動できるように exported にしたが、`android.permission.DUMP`（adb の shell は持つが、ふつうのアプリは持てない
  権限）を求めるので、ほかのアプリからは起動できない（WorkManager の `DiagnosticsReceiver` などと同じ方法）。
- 計測中は画面を点けたまま（`FLAG_KEEP_SCREEN_ON`）にし、ロック画面の上にも表示する。前面でなくなった回は `bg=true` になる。

### 9.1 手順（qa）

1. このブランチの debug ビルドを入れる（main の debug ビルドの上に入れてよい。debug の署名は同じなので、アプリのデータは残る）。
   このブランチは audio-analysis の作業ツリーで checkout しているので、別の作業ツリーで作る場合は `--detach` を付ける。

   ```sh
   git worktree add --detach <作業ツリー> agent/audio-analysis/onnx-whisper-lid
   cd <作業ツリー>
   JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME=$HOME/Library/Android/sdk \
       gtimeout 1800 ./gradlew :app:installDebug
   ```

2. 入力を置く（`<name>` は英数字・`.`・`_`・`-`）。チャンク境界は lead の評価データの `chunks_<name>.json` をそのまま使う。
   音声は、その評価データの PCM を作った元の音声ファイル（実際の教材。リポジトリには入れない）。

   ```sh
   PKG=com.miuraken.chunkflow
   DIR=/sdcard/Android/data/$PKG/files/lid-bench
   adb shell mkdir -p $DIR
   adb push Hindi_1_Lesson_16.mp3 $DIR/Hindi_1_Lesson_16.mp3
   adb push chunks_Hindi_1_Lesson_16.json $DIR/Hindi_1_Lesson_16.chunks.json
   ```

   `adb push` で `Android/data` に書けない場合は、アプリの内部の `files/lid-bench` に置く（ツールは両方を探す）。

   ```sh
   adb push Hindi_1_Lesson_16.mp3 /data/local/tmp/Hindi_1_Lesson_16.mp3
   adb push chunks_Hindi_1_Lesson_16.json /data/local/tmp/Hindi_1_Lesson_16.chunks.json
   adb shell run-as $PKG sh -c 'mkdir -p files/lid-bench && cp /data/local/tmp/Hindi_1_Lesson_16.* files/lid-bench/'
   ```

3. 端末を準備する。
   - 省電力モードをオフにする（オンだと 3 並列が 1 件ずつになる）。
   - アプリで解析・文字起こし・一括言語判定が動いていないことを、アプリの画面で確かめる（同じプロセスで動くと遅くなる）。
   - 端末が冷えていること（`adb shell dumpsys thermalservice` の `Thermal Status: 0`）。充電の有無は、比べる回どうしでそろえる。

4. 起動する（logcat を消してから）。

   ```sh
   adb logcat -c
   adb shell am start -n $PKG/com.miuraken.chunkflow.core.media.bench.LidBenchActivity \
       --es name Hindi_1_Lesson_16 --es target hi --es base en --ei maxChunks 60
   ```

   韓国語の教材は `--es name Korean_lesson_ja --es target ko --es base ja`。

5. `END` 行が出るまで待つ。60 チャンク・既定の設定で 5〜10 分の見込み（Mac の値からの見込みで、大半は純 Kotlin の回とクールダウン。
   実機は未確認）。画面は点けたままにし、ほかのアプリに切り替えない。計測中にもう一度 `am start` しても無視する（`BUSY` 行）。

6. 結果を読む。すべての行は、入力と同じディレクトリの `<name>-<日時>.log` にも書く（`LOG_FILE` 行にパス）。

   ```sh
   adb logcat -d -v raw -s ChunkFlowLidBench:I \
       | grep -E '^(START|WARMUP|ONNX_LOAD|THERMAL_WAIT|RESULT|DIFF|TABLE|SUMMARY|END|ERROR|WARN|LOG_FILE)'
   adb pull /sdcard/Android/data/$PKG/files/lid-bench/ ./lid-bench-results/
   ```

   内部の `files/lid-bench` に置いた場合は `adb shell run-as $PKG cat files/lid-bench/<name>-<日時>.log`。

### 9.2 引数

| 引数 | 既定 | 内容 |
| --- | --- | --- |
| `--es name` | （必須） | 教材名。入力は `<name>.chunks.json` と `<name>.mp3` |
| `--es target` / `--es base` | （必須） | 学習対象言語と解説の言語（例: `hi` / `en`、`ko` / `ja`） |
| `--ei maxChunks` | 60 | 先頭から使うチャンク数（0 ならすべて） |
| `--ei warmupChunks` | 6 | エンジンごとのウォームアップのチャンク数（0 ならしない） |
| `--ei cooldownSec` | 30 | 各回の前に待つ秒数 |
| `--ei thermalWaitSec` | 300 | クールダウンのあと、温度の状態が MODERATE（2）未満に下がるまで追加で待つ上限の秒数 |
| `--es engines` | `kotlin,onnx` | 計測するエンジンと順番（例: `onnx` だけ、`onnx,kotlin` の順） |
| `--es modes` | `seq,par3` | 各エンジンで計測する進め方と順番 |
| `--es audio` | `<name>.mp3` | 音声のファイル名（m4a などの場合） |

数値の引数は `--es`（文字列）で渡してもよい。正しくない引数は `ERROR 引数が正しくない: …` と使い方（`USAGE` 行）を出して終わる。

### 9.3 計測の流れ

1. エンジンごとにウォームアップする（先頭の `warmupChunks` 件。モデルの読み込みと JIT）。ONNX Runtime は `ONNX_LOAD ok loadMs=…`
   （プロセスで最初の読み込みの時間。ネイティブライブラリの初期化を含む）。
2. エンジン × 進め方の順（既定は 純 Kotlin 逐次 → 純 Kotlin 3 並列 → ONNX Runtime 逐次 → ONNX Runtime 3 並列）に、
   `COOLDOWN`（必要なら `THERMAL_WAIT`）→ `RUN_START` → 計測 → `DIAG`（その回の文字起こし器の診断ログ）→ `RUN_DONE`。
   - 回ごとに新しいデコーダと文字起こし器を作る（ファイル内の補正の状態と、切り出しのキャッシュを持ち越さない）。
   - 推論はツールの中で作る 3 スレッドのプールで行う。逐次の回は、本番の `parallelism=1` と同じく呼び出し側のスレッドで 1 件ずつ。
3. 最後に `RESULT`（各回 1 行。判定の一致を含む）、`DIFF`（判定が違うチャンク）、`TABLE`（表）、`SUMMARY`（進め方ごとの
   純 Kotlin / ONNX Runtime の比）、`END`。

### 9.4 RESULT 行

1 回の計測を 1 行で出す（`key=value` を空白で区切る。`note=` は行の最後で、空白を含むことがある）。例（値は例）:

```text
RESULT name=Hindi_1_Lesson_16 engine=onnx mode=par3 status=OK chunks=60 detected=58 totalMs=1234.5 perChunkMs=20.6 inferN=58 inferMedianMs=31.0 inferMeanMs=32.4 engineMeanMs=28.1 melMeanMs=2.30 nullAnalyses=2 onnxRuns=58 kotlinRuns=0 workers=3 degraded=false cpuMs=4567 thermal=0->1 headroom=0.41->0.47 powerSave=false bg=false agree=1.000 agreeN=60/60 ref=kotlin/par3
```

| 項目 | 内容 |
| --- | --- |
| `status` | `OK` / `FALLBACK`（ONNX Runtime の回で純 Kotlin に切り替わった）/ `NO_INFERENCE` / `SKIPPED`（ウォームアップでそのエンジンを使えなかった。ふつうは ONNX Runtime を読み込めない場合）/ `ERROR`。`OK` 以外の回は比べない |
| `chunks` / `detected` | 計測したチャンク数 / 言語を判定できたチャンク数 |
| `totalMs` / `perChunkMs` | `detectChunkLanguages` の全体時間（音声の切り出し・log-mel・推論・ファイル内の補正）/ 1 チャンクあたり |
| `inferN` / `inferMedianMs` / `inferMeanMs` | チャンクごとの推論時間（`analyzeLanguagePair`: log-mel・推論・平均プーリング。`LanguagePairPrediction.inferenceTimeMs` と同じ範囲をナノ秒で測った値）の件数・中央値・平均。3 並列の回は、同時に動かした 1 件ごとの時間 |
| `engineMeanMs` / `melMeanMs` | 推論（エンコーダ + デコーダ）だけ / log-mel だけの平均（判定器の累計から） |
| `nullAnalyses` | 判定器が結果を返さなかった回数（無音など） |
| `onnxRuns` / `kotlinRuns` | エンジンごとの推論の回数（ONNX Runtime の回で `kotlinRuns` が 0 でなければ `FALLBACK`） |
| `workers` / `degraded` | 実際に同時に推論した数 / 3 並列を指定したが、温度か省電力モードのため 1 件ずつになった |
| `cpuMs` | その回にプロセスが使った CPU 時間（全スレッドの合計。参考） |
| `thermal` / `headroom` | 開始→終了の `PowerManager.currentThermalStatus`（0 = NONE、1 = LIGHT、2 = MODERATE、3 = SEVERE …）/ `getThermalHeadroom(0)`（1.0 で重い制限が始まる目安。取れなければ NaN） |
| `powerSave` / `bg` | 開始時に省電力モードだった / 計測中に画面が前面でなくなった |
| `agree` / `agreeN` / `ref` | 最終判定（ファイル内の補正の後）の一致率 / 件数 / 比べた回。ONNX Runtime の回は同じ進め方の純 Kotlin の回と、純 Kotlin の 3 並列は純 Kotlin の逐次と比べる |
| `note` | 補足（切り替わった理由、例外など） |

### 9.5 結果を見るときの注意

- `status=OK`・`degraded=false`・`bg=false`・`powerSave=false` の回だけを比べる。`thermal` の終わりが 2（MODERATE）以上の回は、
  端末が温まって遅くなっている可能性がある。純 Kotlin の回は温まりやすいので、必要なら `--es engines kotlin --es modes par3`
  のように分けて、冷えてから測り直す。
- 1 チャンクの推論の速さは、逐次の回の `inferMedianMs`（と `engineMeanMs`）で比べる。3 並列の回の値は同時に動かした 1 件ごとの時間。
- 全体時間には音声の切り出し（MediaCodec のデコード）が入る。ONNX Runtime ではこれが占める割合が大きくなる（§5.4）。
- debug ビルドなので、純 Kotlin の推論は release より遅いかもしれない（debuggable なアプリでは ART の一部の最適化が使えない。
  未確認）。ONNX Runtime の推論はネイティブのコードなので、影響は小さい見込み。
- `agree` は Mac では全チャンクで 1.000 だった（§5.2）。1 未満なら `DIFF` 行のチャンクを lead に知らせる。

### 9.6 release に入らないことの確認

`:app:assembleDebug`・`:app:processReleaseMainManifest`・`:core:media:compileReleaseKotlin` のあとで確かめた。

- マージ後のマニフェスト: debug（`app/build/intermediates/merged_manifests/debug/…`）には `LidBenchActivity`（exported、
  `android.permission.DUMP`）があり、release（`merged_manifest/release/processReleaseMainManifest/…`）にはない。
- クラス: `core:media` の debug のコンパイル結果には `…/core/media/bench/` があり、release のコンパイル結果
  （`built_in_kotlinc/release/compileReleaseKotlin/classes`）にはない。debug の APK の dex には `LidBenchActivity` がある。
- 計測ツールの単体テストは `src/testDebug` に置いた（`testReleaseUnitTest` からは見えない debug だけのクラスを使うため）。
