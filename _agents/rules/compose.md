---
trigger: model_decision
description: "Jetpack Compose の画面・UI 部品・プレビュー・スクリーンショットテストを書くとき、または feature/ と core/designsystem/ のコードを変更するときに読む規約。"
---

# Compose の規約（Chunk Flow）

- 画面は `XxxRoute`（ViewModel を受け取り、状態を集めてイベントを渡すだけ）と `XxxScreen(state, onEvent, modifier)`（状態を受け取って描くだけ）に分ける。`XxxScreen` から下の Composable に ViewModel を渡さない。
- 状態の購読は `collectAsStateWithLifecycle()`。
- Composable の引数: 必須の引数 → `modifier: Modifier = Modifier` → 省略できる引数 → 末尾のラムダ。`modifier` は一番外側の要素に 1 回だけ適用する。
- 高頻度で変わる値（再生位置）は、ラムダ（`() -> Long`）や `PositionProvider` で渡し、描画フェーズ（`Modifier.drawWithCache`、`graphicsLayer`、`Canvas`）でだけ読む。コンポーズ中に読まない。
- `LazyColumn` には `key` を指定する（チャンク ID）。
- 文字列は文字列リソースに置く（日本語が既定、英語も用意する）。サイズは `dp` / `sp` で、文字サイズの設定に追従させる。
- 色はテーマ（`MaterialTheme.colorScheme`）から取る。直接の色指定をしない。
- アクセシビリティ: アイコンだけのボタンに `contentDescription`、タップ領域 48dp 以上、色だけで意味を伝えない。独自の操作（波形）には `semantics` で役割と `customActions` を付ける。
- テキストは `Text` でプレーンテキストとして表示する（HTML を解釈しない）。スクリプトの行には言語に合った `localeList` を付ける。
- **プレビュー**: 画面と主要な部品に `@ChunkFlowPreviews` を付け、状態は `core:testing` のプレビュー用フィクスチャ（`PreviewParameterProvider`）から渡す。プレビューの中で ViewModel、Repository、ネットワーク、ファイルを使わない。
- **スクリーンショットテスト**: `src/screenshotTest/` に `@PreviewTest` を付けたプレビューを置く。見た目が変わる基準画像の更新は人間の承認を得てからマージする（設計書 §10.5）。
