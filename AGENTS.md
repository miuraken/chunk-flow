# AGENTS.md — Chunk Flow の開発ルール（全エージェント共通）

このファイルは、このリポジトリで作業するすべての AI エージェントと人間が守るルールです。
各担当の詳しい役割は `_agents/agents/<name>.md`、言語ごとの規約は `_agents/rules/` にあります。

## 1. まず読むもの

- **設計の単一の情報源は [`design_doc.md`](design_doc.md)** です。作業の前に、担当に関係する節を必ず読んでください。
  - 全員: §0（主要な設計判断）、§5（アーキテクチャ）、§6（データモデルとインターフェース）、§8（セキュリティ）、§10（開発プロセス）
  - 担当ごとに読む節は、各エージェントの定義ファイルに書いてあります。
- 設計書と食い違う実装をしないでください。設計書の内容が実装上無理だとわかったら、実装で回避せずに lead に報告します（§7 の契約変更の手順）。
- 設計の判断は `docs/adr/`、スパイク（技術検証）の結果は `docs/spikes/` にあります。

## 2. プロジェクトの概要

- **Chunk Flow**: 語学学習用のオーディオプレイヤー（Android、個人利用）。音声を発話単位の「チャンク」に分け、チャンク単位のリピート・自動ポーズ・言語別の再生を行う。
- **文字起こしは PC で行う**: `tools/transcriber/`（Python + Whisper）が `chunkflow-transcript` 形式の JSON を作り、アプリはそれを取り込む。アプリと PC ツールは `docs/format/` の JSON Schema とサンプルだけでつながる。
- applicationId / ルートパッケージ: `com.miuraken.chunkflow`
- 主要技術: Kotlin、Jetpack Compose + Material 3、Media3（ExoPlayer / Session）、Room、DataStore、WorkManager、Hilt、Navigation 3

## 3. 担当範囲（書き込んでよい場所）

**自分の担当範囲の外のファイルは変更しません。** 読むのは自由です。範囲外の変更が必要なら、担当者（または lead）に、理由と変更案を添えて依頼してください。

| エージェント | 書き込める範囲 |
| --- | --- |
| `lead` | `build-logic/`、`gradle/`、すべての `*.gradle.kts`、リポジトリ直下の設定ファイル（`settings.gradle.kts`、`gradle.properties`、`.github/`、`.editorconfig`、`.gitignore` など）、`app/`、`core/model/`、`core/database/`、`core/datastore/`、`core/testing/`、`core/data/` の共通部分、`docs/`（下の例外を除く）、`AGENTS.md`、`_agents/`、`design_doc.md` |
| `playback` | `core/playback/`、`core/drive/`、`core/data/` の `library` パッケージ、`spikes/playback/`、`docs/spikes/S2-*.md` |
| `audio-analysis` | `core/media/`、`core/audio/`、`core/data/` の `analysis` パッケージ、`spikes/audio/`、`docs/spikes/S3-*.md` |
| `transcriber` | `tools/transcriber/`、`core/transcript/`、`core/data/` の `transcript` パッケージ、`docs/spikes/S1-*.md` |
| `ui` | `feature/`、`core/designsystem/`（スクリーンショットテストの基準画像を含む） |
| `qa` | 各モジュールのテストディレクトリへの**新しいテストファイルの追加**、`docs/manual-test.md`、`docs/qa/` |

- 担当範囲の中にあるテスト（`src/test/`、`src/androidTest/`、`src/screenshotTest/`）は、その担当者が書きます。qa は既存のテストファイルを変更せず、修正が必要なら担当者に依頼します。
- `docs/format/`（文字起こしの形式）は lead の担当です。transcriber も含め、変更は §7 の手順で依頼します。
- `core/data/` の共通部分（DI のモジュール、パッケージをまたぐ取り込み処理の組み立て）は lead が持ちます。
- 担当範囲を変えるときは、lead がこの表と設計書 §10.2 を両方更新します。

## 4. 作業の進め方

- **ブランチ**: エージェントごとに別のブランチ / worktree で作業します。ブランチ名は `agent/<エージェント名>/<短い説明>`（例: `agent/playback/chunk-scheduler`）。`main` に直接コミットしません。
- **作業ツリーを分ける**: 1 つの作業ツリーを編集するエージェントは 1 つだけです。2 つ以上のエージェントが、同じ作業ツリーを同時に編集しません。
  - `main` の作業ツリー（`/Users/miuraken/src/gogaku-player`）を使うのは lead だけです（統合と統合後の確認、lead 自身の作業）。
  - サブエージェントに新しい実装を頼むときは、`invoke_subagent` の `Workspace` に `share`（または `branch`）を指定し、依頼ごとに別の worktree で動かします。`inherit`（親と同じ作業ツリー）では実装させません。担当のエージェント（`ui` など）の代わりに `self` などで起動する場合も同じです。
  - サブエージェントの worktree は、親の最新のコミットから作られます。作業ツリーにしかない変更は引き継がれないので、頼む前に必要な変更をコミットしておきます。
  - コミットするときは、自分の変更だけをパスを指定して `git add <パス>` します（`git add -A` や `git commit -a` は使わない）。
- **統合**: `main` への統合は lead が行います。機能単位で小さく統合します（マイルストーンの最後にまとめない）。
- **コミット**: 小さく、1 つの目的ごとに分けます。メッセージは日本語で、1 行目に「何をしたか」を簡潔に書きます（例: `ChunkScheduler: A-B リピートの境界予約を追加`）。
- **禁止**: `git push --force`、他のエージェントのブランチの変更・削除、`main` の履歴の書き換え、`git push`（push は人間が行う）。
- **報告**: 作業の終わりに、次の形式で報告します。
  1. やったこと（変更したファイルの一覧）
  2. 実行した確認（コマンドと結果。失敗したものも書く）
  3. UI を変えた場合は画面の画像（§9）
  4. 残っている課題、判断が必要なこと、他の担当への依頼
- **人間への依頼は通知でも知らせる**: 人間の操作（実機のロック解除、承認、判断など）が必要なときは、頼むエージェントが会話に書くのに加えて、macOS の通知を出します。サブエージェントや別の会話の応答は、人間の画面に出ていないことがあるためです。通知には、何をしてほしいかを 1 行で書きます。
  - 例: `osascript -e 'display notification "Pixel のロックを解除してください" with title "Chunk Flow（ui）" sound name "Glass"'`

## 5. 実行してよいコマンド

| 種類 | 例 | 誰が |
| --- | --- | --- |
| 担当モジュールのビルドとテスト | `./gradlew :core:playback:testDebugUnitTest`、`./gradlew :core:audio:test` | 全員（自分の担当モジュール） |
| 全体のビルドとチェック | `./gradlew build`、`./gradlew spotlessCheck lint` | lead、qa |
| 依存やビルド設定を変えるタスク | `./gradlew wrapper`、Version Catalog の更新 | lead のみ |
| PC ツール | `uv run --directory tools/transcriber pytest`、`uv run --directory tools/transcriber ruff check` | transcriber、lead、qa |
| Android CLI（UI の確認） | `android studio render-compose-preview`、`android screen capture`、`android layout`、`android run` | ui、qa（§9） |
| 端末の操作 | `adb`、`android emulator` | qa、ui（確認のため）、playback（S2 のため） |
| git | `git status/diff/log/add/commit/switch`、`git worktree` | 全員（自分のブランチ）。統合は lead |

- 長時間かかるコマンドは、止まらないように `gtimeout <秒> <コマンド>` で上限を付けて実行します（例: `gtimeout 900 ./gradlew build`）。
- Gradle は Android Studio の JBR で動かします（Homebrew の JDK はこの Mac では起動できない。ADR 0001）。ターミナルからは `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew ...` のように実行します。
- ネットワークからのインストール（`pip install`、`brew install`、新しい Gradle の依存など）は勝手に行いません。§8 の手順で lead に依頼します。
- リポジトリの外のファイル（`~/.gradle`、`~/.androidrc` などの設定）を変更しません。必要なら人間に依頼します。

## 6. 設計の原則（実装で必ず守ること）

設計書の内容のうち、実装で崩れやすいものを挙げます。

- **再生を最優先する**: 解析や取り込みが、再生の開始や操作を止めてはいけません。
- **再生位置は UiState に入れない**: 高頻度で変わる位置は `PositionProvider` から描画フェーズで読みます（§6.2）。
- **ExoPlayer に触るのは `core:playback` だけ**、**ネットワークに触るのは `core:drive` だけ**。
- **純 Kotlin のモジュール**（`core:model`、`core:audio`、`core:transcript`）に Android の API を持ち込みません。
- **チャンクの作り直しは 1 つのトランザクションで**、挿入 → 文字起こしの付け替え → 古いチャンクの削除の順に行います（§6.4）。
- **Dispatcher は DI で注入**し、テストで差し替えられるようにします。
- **PCM 全体をメモリに載せない**（ストリーミング処理。§7.3.1）。

## 7. 契約（インターフェース）の変更

- **対象**: `core:model`、設計書 §6.3 のインターフェース、Room のスキーマ、モジュール間の依存関係、文字起こしの形式（`docs/format/`）。
- **手順**: 理由と影響範囲を書いて lead に依頼する → lead が変更し、全モジュールのビルドを通す → ADR と設計書を更新する → 各エージェントは最新の `main` を取り込んでから作業を続ける。
- 互換性を壊す変更は、人間の承認を得てから行います。
- 契約が確定するまでは、`core:testing` の Fake を使って開発します。

## 8. 依存の追加

- 新しいライブラリ（Gradle / Python）が必要になったら、**名前・バージョン・用途・ライセンス・サイズ・代替案**を添えて lead に依頼します。
- lead は追加の前に、ライセンス・保守状況・既知の脆弱性を確認します（依存の安全性を確認するツールが使える場合は必ず使う）。
- バージョンはアプリは `gradle/libs.versions.toml`、PC ツールは `tools/transcriber/uv.lock` で固定します。

## 9. UI の確認

UI を変えたときの確認方法は設計書 §10.5 に従います。要点だけ挙げます。

- 画面は「状態を受け取って描くだけの Composable」と、ViewModel をつなぐ Route に分け、前者に `@ChunkFlowPreviews` 付きのプレビューを用意します。
- 変更した画面の画像（ライト / ダーク、文字サイズ 2.0 倍、タブレット）を自分で見て、崩れを直してから報告に添えます。
- 見た目が変わるスクリーンショットの基準画像の更新は、人間の承認を得てからマージします。

## 10. セキュリティとプライバシー

設計書 §8 のルールに従います。特に次の点は必ず守ってください。

- 秘密情報（署名鍵、OAuth のクライアント情報、トークン）をコミットしません。ログにも出しません。
- **実際の教材の音声や文字起こしをコミットしません**（著作権のため）。テスト用の音源はスクリプトで生成します。
- リリースビルドで、ファイル名・URI・文字起こしの本文をログに出しません。
- 取り込むファイル（音声・JSON・SRT など）と Drive からのデータは、すべて信頼できない入力として扱い、サイズの上限と検証を通します。
- 意図的に見送る対策は、コードに `TODO(security): <理由>` を残します。
- 権限を増やすときは、理由を ADR に残します（§8.2 の一覧から増やさない）。

## 11. 完了の定義（DoD）

- 担当モジュールのビルド・単体テスト・Lint（Kotlin は `spotlessCheck` と `lint`、Python は `ruff check`）が通る。
- 新しく公開する API に KDoc（Python は docstring）があり、主要なロジックにテストがある。
- 新しい依存は lead の承認済み。
- UI を変えた場合は §9 を満たしている。
- 秘密情報や実際の教材をコミットしていない。
- 報告（§4）を書いた。

## 12. 書き方の約束

- ドキュメント・ADR・コードのコメント・KDoc は日本語で書きます。識別子（クラス名、関数名、変数名）は英語です。
- 設計書を引用するときは節番号で示します（例: 「§7.2.4 のとおり」）。
- わからないことは推測で埋めず、報告に「未確認」と書きます。
