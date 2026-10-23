---
trigger: glob
globs: "*.kt,*.kts"
---

# Kotlin の規約（Chunk Flow）

- スタイルは Kotlin の公式スタイル。整形は Spotless + ktlint に任せ、`./gradlew spotlessApply` で直してからコミットする（Spotless はルートのプロジェクトにだけ設定してあり、`:<module>:spotlessApply` というタスクは無い）。
- `!!` を使わない。null になり得る値は型で表し、呼び出し側で扱う。
- 例外を UI まで投げない。失敗は `sealed interface` の結果型やエラー種別（`AnalysisError`、`ImportError` など。設計書 §6）で返す。`Throwable` を UiState に入れない。
- コルーチン: `GlobalScope` を使わない。Dispatcher は DI で注入する（`Dispatchers.IO` などを直接書かない）。キャンセルを握りつぶさない（`CancellationException` を捕まえたら投げ直す）。
- Flow: Repository は `Flow` を公開し、ViewModel で `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 初期値)` にする。
- 公開する API には KDoc を書く（日本語）。`internal` を積極的に使い、モジュールの外に出す型を最小にする。
- `core:model`、`core:audio`、`core:transcript` では `android.*` と `androidx.*` を import しない。
- Room: スキーマを変えたら、マイグレーションと `MigrationTestHelper` のテストを書く（スキーマは lead の担当）。
- ログ: リリースビルドでファイル名・URI・文字起こしの本文・トークンを出さない。
- テスト: JUnit + kotlinx-coroutines-test（`runTest`）。Flow は Turbine（導入が承認された場合）。テスト名は「条件_期待する結果」の形で、日本語でもよい（バッククォートで囲む）。
