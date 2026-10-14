# 0002. スクリーンショットテストの方式

- 状態: 提案
- 日付: 2026-10-14
- 決めた人: lead

## 背景

設計書 §10.5 は、エージェントが IDE の画面を見ずに UI を確かめる手段として、Compose Preview Screenshot Testing
（`com.android.compose.screenshot`）を Phase 0 で試し、問題があれば Roborazzi に切り替える、としている。確認時点でプラグインは alpha 版。

## 決定

**Compose Preview Screenshot Testing（0.0.1-alpha16）を採用する。** Roborazzi には切り替えない。

- 設定（プラグインを使うモジュールごと）:
  - `gradle.properties` の `android.experimental.enableScreenshotTest=true` と、モジュールの
    `android { experimentalProperties["android.experimental.enableScreenshotTest"] = true }` の**両方**が必要（どちらかが欠けると、プラグインの適用時に失敗する）。
  - `plugins { alias(libs.plugins.compose.screenshot) }`、依存は `screenshotTestImplementation(libs.screenshot.validation.api)` と
    `screenshotTestImplementation(libs.androidx.compose.ui.tooling)`。
- テストは `src/screenshotTest/kotlin/` に置き、`@PreviewTest` と `@ChunkFlowPreviews` を付けた Composable 関数にする。
  `@ChunkFlowPreviews` の 1 つの関数から 17 枚の画像（ライト / ダーク、文字サイズ 7 段階、画面サイズ 6 種、UI 言語 2 種）ができる。
- 基準画像は `src/screenshotTestDebug/reference/` に置いてコミットする（§10.5.2）。見た目が変わる更新は人間の承認を得てからマージする（§10.5.3）。
- タスク: `./gradlew updateDebugScreenshotTest`（基準画像の作成・更新）、`./gradlew validateDebugScreenshotTest`（比較。差分は
  `build/reports/screenshotTest/` の HTML レポート）。
- Phase 0 では動作確認として `feature:library` の空の画面に 1 つだけ置いた（ADR 0003）。以後のテストと基準画像は ui の担当。
  他の feature モジュールでも使うときは、上の設定を lead が `build.gradle.kts` に加える（必要になったら convention plugin にまとめる）。

## Phase 0 での確認結果

- `updateDebugScreenshotTest` で 17 枚の基準画像ができ、ライト / ダーク、文字サイズ 2.0 倍、日本語 / 英語の画像を目で確かめた（崩れなし）。
- 直後の `validateDebugScreenshotTest` は成功した。
- 文字列を 1 文字変えて `validateDebugScreenshotTest` を実行すると失敗し、差分が検出されることを確かめた（確かめた後に元に戻した）。

## 理由

- Android 公式の仕組みで、既存の `@Preview` をそのまま使え、Android Studio の差分ビューアとも統合される（AGP 9 と対応する Android Studio が必要。§10.5.4）。
- Roborazzi は Robolectric の上で動き、依存（Robolectric、Roborazzi）が増える。Phase 0 の確認で、公式のプラグインに問題は見つからなかった。

## 影響・リスク

- alpha 版のため、版を上げると設定や出力の形式が変わる可能性がある。上げるときは `validateDebugScreenshotTest` の結果を確かめてから統合する。
- レンダリングはホストの環境（フォントなど）に影響される可能性がある。基準画像は Apple Silicon の macOS で作ったため、
  CI の比較も macOS（`macos-latest`）のジョブで行う。Linux で比較できるかは**未確認**。
- `validateDebugScreenshotTest` は `./gradlew build`（`check`）には含まれない。CI では別のジョブで実行する。
