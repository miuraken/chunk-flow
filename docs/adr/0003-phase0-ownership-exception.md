# 0003. Phase 0 の担当範囲の例外

- 状態: 承認済み
- 日付: 2026-10-14
- 決めた人: lead（オーケストレーターの依頼による）

## 背景

AGENTS.md §3 では、各エージェントは自分の担当範囲の外のファイルを変更しません。一方で Phase 0（設計書 §11）では、
lead が単独で「`./gradlew build` が通り、空の画面の間を遷移できる」状態を作り、他の担当が Fake を使って並行開発を始められるように
契約（インターフェース）を確定させる必要があります。そのためには、他の担当のモジュールにもファイルを置かなければなりません。

## 決定

Phase 0 の基盤づくり（ブランチ `agent/lead/phase0-foundation`）に限り、lead が次のファイルを作ることを認めます。

| 担当 | 置いたファイル | 内容 |
| --- | --- | --- |
| playback | `core/playback/.../PlaybackController.kt` | §6.3 の `PlaybackController`、`PositionProvider`、`PlaybackStatus`、`PlaybackError` の**宣言だけ** |
| playback | `core/data/.../library/LibraryRepository.kt` | §6.3 の `LibraryRepository` と `ImportProgress` / `SyncProgress` / `TranscriptImportResult` などの**宣言だけ** |
| audio-analysis | `core/data/.../analysis/AnalysisRepository.kt` | §6.3 の `AnalysisRepository` の**宣言だけ** |
| transcriber | `core/transcript/.../TranscriptParser.kt` | §6.3 の `TranscriptParser` と `TranscriptDocument` などの**宣言だけ** |
| ui | `core/designsystem/` | 最小限のテーマ（`ChunkFlowTheme`、代替の配色）と `@ChunkFlowPreviews` |
| ui | `feature/library/`、`feature/player/`、`feature/settings/` | 空の Screen と Route、文字列リソース、雛形のプレビュー、スクリーンショットテストの動作確認用のテスト 1 つと基準画像（`feature/library`） |

- すべての `*.gradle.kts` は、もともと lead の担当です（AGENTS.md §3）。
- 実装（`PlaybackController` の実装、Repository の実装、パーサ、画面の作り込みなど）は各担当が書きます。
- この例外は Phase 0 の統合（`main` へのマージ）までとし、以後は各担当に戻します。上の表のファイルの変更は、
  宣言（契約）の変更なら AGENTS.md §7 の手順で lead に依頼し、雛形の作り込みは各担当が自由に行います。

## 理由

- 契約の宣言を lead が 1 か所で確定させると、並行開発の境界がはっきりする（§6.3、§10.3）。
- 空の画面とテーマがないと、画面遷移とスクリーンショットテストの仕組みを確かめられない（Phase 0 の完了条件）。

## 影響

- ui は、雛形の `LibraryUiState` などを設計書 §6.2 の形（`PlayerUiState` など）に置き換えてよい（雛形は契約ではない）。
- スクリーンショットテストの基準画像は、以後は ui の担当（AGENTS.md §3）。見た目が変わる更新は人間の承認を得る（§10.5.3）。

## スパイクのモジュール（S2 / S3）の載せ方

- `spikes/playback/`（playback）と `spikes/audio/`（audio-analysis）は、それぞれ独立したアプリのモジュールにする
  （applicationId は `com.miuraken.chunkflow.spike.playback` / `.spike.audio`。本体と同じ端末に入れられる）。
- `settings.gradle.kts` は、`spikes/<name>/build.gradle.kts` があるときだけ `:spikes:<name>` を取り込む（すでに追加済み）。
- `build.gradle.kts` は Gradle ファイルなので lead が置く（convention plugin の `chunkflow.android.application` を使い、
  必要な依存（Media3 など）を Version Catalog から加える）。ソースコードと報告（`docs/spikes/`）は各担当が書く。
- 本体のモジュールからスパイクへは依存しない。スパイクは本体の `core:*` に依存してよい。

### 追記（2026-10-14）: スパイクのモジュールの雛形

この例外の範囲として、lead が次を置いた（ブランチ `agent/lead/spike-modules`）。

| モジュール | 置いたもの | 依存 |
| --- | --- | --- |
| `spikes/playback`（S2、playback） | `build.gradle.kts`、空の `SpikeActivity`、マニフェスト、アイコンと文字列のリソース | core:model、activity-compose、material3、Media3 の common / exoplayer / session。`androidTestImplementation` に media3-test-utils |
| `spikes/audio`（S3、audio-analysis） | 同上 | core:model、core:audio、activity-compose、material3、Media3 の common / inspector（`MediaExtractorCompat`）/ exoplayer |

- `build.gradle.kts` 以外（ソース、マニフェスト、リソース）は、以後は各担当が自由に書き換える（雛形は契約ではない）。
- 依存を足すときは、AGENTS.md §8 の手順で lead に依頼する（Robolectric、media3-test-utils-robolectric など）。
- スパイクのマニフェストは本体の権限の制約の外（ADR 0004）。
