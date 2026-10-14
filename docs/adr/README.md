# ADR（Architecture Decision Records）

設計の判断を記録します（設計書 §10.1）。ADR は lead が書きます。スパイクの結果（`docs/spikes/`）をもとにした判断もここに書きます。

- ファイル名: `NNNN-タイトル.md`（4 桁の連番）。
- 状態: 提案 / 承認 / 置き換え（置き換えた ADR の番号を書く）。
- 一度承認した ADR は書き換えず、判断を変えるときは新しい ADR を作って古い方を「置き換え」にします。

| # | タイトル | 状態 |
| --- | --- | --- |
| [0001](0001-module-structure-and-versions.md) | モジュール構成とバージョンの方針（JDK を含む） | 提案 |
| [0002](0002-screenshot-testing.md) | スクリーンショットテストの方式 | 提案 |
| [0003](0003-phase0-ownership-exception.md) | Phase 0 の担当範囲の例外 | 提案 |
| [0004](0004-manifest-check.md) | マニフェストの確認の規則（権限とエクスポート） | 提案 |
