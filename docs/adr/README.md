# ADR（Architecture Decision Records）

設計の判断を記録します（設計書 §10.1）。ADR は lead が書きます。スパイクの結果（`docs/spikes/`）をもとにした判断もここに書きます。

- ファイル名: `NNNN-タイトル.md`（4 桁の連番）。
- 状態: 提案 / 承認 / 置き換え（置き換えた ADR の番号を書く）。
- 一度承認した ADR は書き換えず、判断を変えるときは新しい ADR を作って古い方を「置き換え」にします。

| # | タイトル | 状態 |
| --- | --- | --- |
| [0001](0001-module-structure-and-versions.md) | モジュール構成とバージョンの方針（JDK を含む） | 承認済み |
| [0002](0002-screenshot-testing.md) | スクリーンショットテストの方式 | 承認済み |
| [0003](0003-phase0-ownership-exception.md) | Phase 0 の担当範囲の例外 | 承認済み |
| [0004](0004-manifest-check.md) | マニフェストの確認の規則（権限とエクスポート） | 承認済み |
| [0005](0005-analysis-decoding.md) | 解析のデコード（抽出器・時間軸・MediaCodec の方式・実行方式） | 承認済み |
| [0006](0006-mp3-seeking.md) | MP3 のシーク精度の対策（ヘッダを隠して IndexSeeker を使わせる） | 承認済み |
| [0007](0007-chunk-boundary-timing.md) | チャンク境界の処理の精度（dynamic scheduling を無効にする） | 承認済み |
| [0008](0008-time-axis-alignment.md) | 時間軸の対応（文字起こしの時刻の補正の初期値、シーク後の一定の誤差） | 承認済み |
| [0009](0009-transcriber-parameters.md) | PC ツールのパラメータと、人間のレビュー待ちで保留する判断 | 承認済み |
| [0010](0010-on-device-speech-and-whisper-lid.md) | 端末内の音声認識（SpeechRecognizer）と量子化 Whisper Tiny による 2 言語判定 | 承認済み |
