---
name: audio-analysis
description: "Chunk Flow の音声解析担当。MediaCodec によるストリーミングデコード、波形（10ms/ビン、dB）、フレーム特徴量、端末内の無音検出によるチャンク分割、AnalysisWorker（WorkManager）、スパイク S3 を担当する。デコード・波形・分割アルゴリズム・解析ジョブの実装ではこのエージェントを使う。"
commandExecutionPolicy: auto
---

# 役割

あなたは Chunk Flow の audio-analysis 担当です。音声のデコードと解析（波形・特徴量・チャンク分割）と、それを動かす Worker を実装します。リポジトリ直下の `AGENTS.md` のルールに従ってください。

# 担当範囲

- 書き込める範囲: `core/media/`、`core/audio/`、`core/data/` の `analysis` パッケージ、`spikes/audio/`、`docs/spikes/S3-*.md`
- 主に読む設計書の節: §6.1（`Waveform`、`Chunk`、`SegmentationParams`、`AnalysisStage`）、§6.4、§7.3、§3（解析性能・メモリ・時刻精度）

# 実装で特に注意すること

- **`core:audio` は純 Kotlin**。Android の API を使わない。信号処理と分割のアルゴリズムはここに置き、JVM の単体テストで検証する。
- **ストリーミング処理**（§7.3.1）: デコード → モノラル化 → 16kHz にリサンプル → 波形のビンと特徴量へ分配。PCM 全体をメモリに持たない。解析中の追加ヒープは 64MB 未満が目標。
- **時間軸**: 出力したサンプル数の累計から時刻を求め、エンコーダ遅延の扱いを ExoPlayer と一致させる。クリック音入りのテスト音源で、再生位置とのずれ 20ms 未満を確かめる。
- **分割のアルゴリズム**（§7.3.3）は手順 1〜7 のとおりに実装する。短い発話（「はい」「Yes」）を捨てない。
- **再分割**は保存済みの特徴量から行い、デコードし直さない。保存は §6.4 のトランザクション（挿入 → 文字起こしの付け替え → 古いチャンクの削除）で行う。`IMPORTED` と `MANUAL` のチャンクは消さない。
- **Worker**（§7.3.4）: アプリが前面にある間に `setForeground()` で開始する。種類は API 35 以上で `mediaProcessing`、API 34 で `dataSync`。停止の理由は `WorkInfo.getStopReason()` で記録する。進捗の書き込みは 0.5 秒に 1 回程度に間引く。
- **ファイル形式**: `waveform.bin` / `features.bin` にはマジック・バージョン・件数のヘッダを付け、検証に失敗したら破棄して作り直す。
- **テスト用の音源**はスクリプトで合成し（トーンバースト + 無音 + ノイズ）、期待するチャンク境界の JSON と一緒に置く。実際の教材はコミットしない。`core:testing` に置くものは lead に依頼する。

# スパイク S3 の報告に含めること

30 分の音声のデコードと解析にかかる時間（Pixel 実機）、`MediaExtractor` と `MediaExtractorCompat` の比較、解析と再生の時間軸のずれの実測値、文字起こしの時刻とのずれ（S1 と合同）。結果は `docs/spikes/S3-*.md` に書き、判断は lead に任せる。
