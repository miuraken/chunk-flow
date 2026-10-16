# Chunk Flow

## 概要（仕様の要約）

Chunk Flow は、語学学習のリスニング・リピーティング・シャドーイングに特化した Android 向けオーディオプレイヤーです。教材音声を発話単位の「チャンク」に分割し、全体波形と拡大波形の表示、チャンク単位や複数選択でのリピート再生、発話後の自動ポーズ（瞬間作文モードを含む）、学習言語／母語の言語別フィルタ再生、ブラインド表示や発音ガイド（ピンイン・粤拼・ローマ字・IAST 等）を提供します。文字起こしと2言語判定は、PC ツール（Whisper）で生成した JSON や字幕の取り込みに加え、端末内の先読み音声認識にも対応し、取り込み直後から再生を止めずに学習を始められます。

詳細な設計は [`design_doc.md`](design_doc.md)、アーキテクチャ上の意思決定は [`docs/adr/`](docs/adr/)、開発ルールは [`AGENTS.md`](AGENTS.md) を参照してください。

---

## 主要な技術スタック

### Android アプリ

| 分類 | 技術・ライブラリ |
| --- | --- |
| 言語・非同期処理 | Kotlin 2.4、Kotlin Coroutines / Flow、Kotlinx Serialization |
| 対象 SDK | `minSdk 34`（Android 14 以上）、`targetSdk 36`、`compileSdk 37`、Java 17 バイトコード |
| UI | Jetpack Compose（Compose BOM 2026.09）、Material 3、Material 3 Adaptive |
| 画面遷移 | AndroidX Navigation 3 |
| 音声再生・セッション | AndroidX Media3（ExoPlayer、`MediaSessionService`、`ForwardingSimpleBasePlayer`、`PlayerMessage`） |
| 音声解析・端末内認識 | `MediaExtractorCompat` + `MediaCodec`（ストリーミング PCM デコード）、純 Kotlin 量子化 Whisper Tiny（2言語判定）、Android `SpeechRecognizer`（チャンク先読み文字起こし） |
| 永続化・バックグラウンド | Room、DataStore Preferences、WorkManager、アプリ内部ストレージ（`filesDir/media/`） |
| DI | Dagger Hilt + KSP |
| ビルド・品質管理 | Gradle 9.8（Kotlin DSL、Version Catalog、`build-logic` convention plugins）、AGP 9.4、Spotless + ktlint、Android Lint、JUnit 4、Robolectric、Compose Preview Screenshot Testing |

### PC 用文字起こしツール（`tools/transcriber`）

| 分類 | 技術・ライブラリ |
| --- | --- |
| 言語・パッケージ管理 | Python 3.12 以上、`uv` |
| 音声認識エンジン | `mlx-whisper`（macOS Apple Silicon）/ `faster-whisper`（Linux・Windows WSL2 + NVIDIA GPU） |
| 音声区間検出・前処理 | Silero VAD v6（`onnxruntime`）、`ffmpeg`、`numpy` |
| テキスト処理・検証 | OpenCC（中国語・広東語の簡体字／繁体字変換）、`jsonschema`（`chunkflow-transcript` v1 スキーマ検証） |
| テスト・静的解析 | `pytest`、`ruff` |

---

## ディレクトリ構成

```text
.
├── app/                    # アプリのエントリポイント（Application、MainActivity、Navigation 3、DI 組み立て）
├── feature/                # 画面単位の UI モジュール（Compose Screen + ViewModel）
│   ├── library/            #   教材ライブラリ一覧、フォルダ階層表示、取り込み、コース・言語ペア設定
│   ├── player/             #   プレイヤー画面（全体/拡大波形、再生制御、チャンク一覧・編集・スクリプト表示）
│   └── settings/           #   アプリ設定画面（再生・自動ポーズ・チャンク分割・Drive 同期・バックアップ）
├── core/                   # 共通基盤モジュール群
│   ├── model/              #   ドメインモデル（純 Kotlin / JVM、Android 非依存）
│   ├── audio/              #   波形縮約、特徴量計算、適応型無音検出（VAD）、チャンク分割、リサンプラ（純 Kotlin / JVM）
│   ├── transcript/         #   文字起こし（Chunk Flow JSON / SRT / WebVTT / LRC）の解析・検証・発音ガイド変換（純 Kotlin / JVM）
│   ├── media/              #   MediaCodec デコード、メタデータ取得、端末内音声認識・Whisper Tiny 2言語判定
│   ├── playback/           #   PlaybackService、LearningPlayer、ChunkScheduler、PlaybackController
│   ├── drive/              #   Google Drive 手動同期（OAuth 認可、Drive API 読み取り専用クライアント）
│   ├── database/           #   Room データベース（Entity / DAO / Migration）
│   ├── datastore/          #   DataStore Preferences によるユーザー設定の永続化
│   ├── data/               #   Repository 実装（Library / Analysis）、ファイル取り込み、AnalysisWorker
│   ├── designsystem/       #   Material 3 テーマ、言語役割別カラーパレット、アイコン、プレビューアノテーション
│   └── testing/            #   テストおよび Compose Preview 用の Fake 実装・フィクスチャ・合成音源生成
├── build-logic/            # Gradle Convention Plugins（モジュール種別ごとの共通ビルド・Lint・Hilt・Room 設定）
├── gradle/                 # Gradle Wrapper と Version Catalog（libs.versions.toml）
├── tools/
│   └── transcriber/        # PC 用文字起こし CLI ツール（chunkflow-transcribe）
├── docs/
│   ├── adr/                # Architecture Decision Records（設計判断の記録）
│   ├── format/             # chunkflow-transcript JSON Schema とサンプルデータ
│   ├── spikes/             # Phase 0 技術検証（スパイク）の結果レポート
│   └── qa/                 # QA レポート・手動テスト手順
├── spikes/                 # 技術検証用の独立モジュール（通常のビルドからは分離）
├── _agents/                # AI エージェント別の役割定義とコーディング規約（Kotlin / Compose / Python）
├── design_doc.md           # 設計の単一の情報源（Design Doc）
└── AGENTS.md               # 全開発者・AI エージェント共通の開発ルール
```

---

## ビルド上の留意点

1. **Gradle 実行時の JDK（macOS 環境）**
   - 開発用 macOS 環境では、実行制御（Santa）により Homebrew 版 OpenJDK の `jspawnhelper` がブロックされるため、署名済みの **Android Studio 同梱 JBR（JDK 25）** を `JAVA_HOME` に指定して Gradle を実行します（[ADR 0001](docs/adr/0001-module-structure-and-versions.md)）。
   - 実行例:
     ```bash
     JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
     JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest spotlessCheck lint
     ```
   - `jvmToolchain` は使用せず、`compileOptions` / `jvmTarget` / `--release 17` により Java 17 互換のバイトコードを出力します。

2. **Android SDK の要件（`compileSdk 37`）**
   - AndroidX / Compose の最新安定版が AAR メタデータで API 37 を要求するため、`compileSdk = 37`（`targetSdk = 36`、`minSdk = 34`）に設定しています。ビルドには Android SDK Platform 37（`platforms/android-37.0`）がインストールされている必要があります。

3. **モジュールの依存制約と純 Kotlin モジュール**
   - `:core:model`、`:core:audio`、`:core:transcript` は Android SDK に依存しない純 Kotlin（JVM）モジュールです。これらに `android.*` や `androidx.*` の API を持ち込まないでください。
   - ExoPlayer を直接参照するのは `:core:playback` のみ、ネットワーク通信を行うのは `:core:drive` のみです。

4. **コード整形と静的解析（ビルドゲート）**
   - 全モジュールで Android Lint の `warningsAsErrors = true` が有効です。
   - コミット前に `./gradlew spotlessApply` で Kotlin コードの整形（Spotless + ktlint）を行い、`./gradlew spotlessCheck lint` が通ることを確認してください。

5. **PC ツール（`tools/transcriber`）の実行とテスト**
   - Python パッケージの管理には `uv` を使用します（`pip install` は直接使用しません）。
   - 実行例:
     ```bash
     uv run --directory tools/transcriber pytest
     uv run --directory tools/transcriber ruff check
     ```
