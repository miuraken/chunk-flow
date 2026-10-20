# Chunk Flow

語学学習に特化した Android 向けオーディオプレイヤーです。
教材の音声を発話単位の「チャンク」に自動分割し、**チャンク単位のリピート・自動ポーズ・言語別フィルタ再生・オンデバイス文字起こし＆翻訳・波形上での直感的な編集**を端末単体で完結させます。

- **対象環境**: Android 14（API 34）以上（Pixel 8 以降を基準端末として最適化、スマートフォン 1 ペイン / タブレット・折りたたみ 2 ペイン対応）
- **開発状況**: Phase 0 および M1〜M4 の主要機能すべて実装済み
- **設計の単一の情報源**: [`design_doc.md`](design_doc.md)

---

## 主な機能

### 1. チャンク単位の再生・学習コントロール
- **自動チャンク分割とリアルタイム再分割**: 音声取り込み時に `MediaCodec` でデコードしながら 10ms 単位の波形とフレーム特徴量を抽出し、無音検出（ヒステリシス付き）で発話チャンクを自動生成します。無音長や前後パディングを変えても再デコードなしで即座に再分割できます。
- **チャンクリピート・範囲リピート・A-B リピート**:
  - チャンクごとのチェックボックスによる**選択チャンク連続リピート**、またはリピートボタン 1 タップでの**現在チャンク一時リピート**。
  - リピートボタン長押しによる**ファイル全体リピート（`🔁リピート`）**。
  - 拡大波形の非チャンク領域の長押し＋ドラッグによる自由な **A-B 区間リピート**。
- **元音声の無音スキップ**: チャンクとチャンクの間の無音（非チャンク区間）を自動で飛び越えてテンポよく再生します。
- **自動ポーズ（シャドーイング・瞬間作文）**:
  - チャンク終端で固定秒数（`0.0〜4.0 秒`）またはチャンク長比例（`0.5〜3.0 倍`）のポーズを自動挿入します。
  - 対象を「すべてのチャンク」「学習言語の後のみ（リピーティング・シャドーイング）」「母語の後のみ（瞬間作文）」から選択できます。
- **言語フィルタ再生**: 「両方再生」「母語のみ」「学習言語のみ」「チェック済み（`☑`）のみ」を切り替え、指定したチャンクだけを連続再生します。
- **イヤホン・ロック画面・フローティング操作**: メディア通知や Bluetooth イヤホンの「前へ / 次へ」ボタンをチャンク移動に割り当て、画面オフでもチャンク学習が可能です。画面上にドラッグ移動できるフローティング操作バーも備えています。

### 2. オンデバイス 2 言語判定・マルチエンジン文字起こし・翻訳
PC での事前処理を必要とせず、Android 端末内の AI モデルだけで多言語教材（母語解説＋学習言語発話など）の文字起こしと翻訳を行います。
- **2 段階の言語判定（LID）**:
  1. **音響 LID**: アセット同梱の量子化 Whisper Tiny モデル（ONNX Runtime 推論）により、教材の言語ペア（母語 `base` / 学習言語 `target`）のロジットを高速比較。
  2. **書記体系・語彙プロファイル判定**: かな・ハングル・キリル文字・デーヴァナーガリー・声調記号などの文字体系と簡体字/繁体字の語彙プロファイルを組み合わせ、短いチャンクでも高精度に言語を識別。
- **3 種類のオンデバイス音声認識（STT）エンジン**:
  - **ML Kit GenAI Speech Recognition**（対応言語での既定エンジン）
  - **Android `SpeechRecognizer`**（OS 標準のオンデバイス認識、`EXTRA_AUDIO_SOURCE` パイプ入力）
  - **`sherpa-onnx` SenseVoice**（`zh` / `yue` / `en` / `ja` / `ko` 対応のオフラインモデル、アプリ内ダウンロード管理対応）
  - 言語ごと・ファイルごとにエンジンを指定でき、チャンクごとの「**より詳しく文字起こし…**」ダイアログでは認識言語・エンジン・入力音声速度（`0.5x / 0.75x / 1.0x / 1.5x`）を変えて試行・比較できます。
- **先読み文字起こしと優先割り込み**: 再生位置から前方へ順次文字起こしを進めつつ、ユーザーが言語バッジをタップしたり再文字起こしを指示したチャンクは優先キューで即座に割り込み処理します。
- **多言語表示・読み方ガイド・オンデバイス翻訳**:
  - 学習言語に応じた**読み方ガイド**（ピンイン、粤拼 Jyutping、ローマ字、デーヴァナーガリー IAST、ハングル音写）の表示。
  - **ML Kit On-Device Translation** による学習言語チャンクから母語へのオフデバイス翻訳表示。
  - 中国語（`zh`）・広東語（`yue`）の簡体字 / 繁体字表示切り替え、および**ブラインドモード**（学習言語の本文を隠して読みや翻訳のみ表示、または全マスクしてタップで一時表示）。

### 3. デュアル波形表示とチャンク編集
- **全体波形＋拡大波形（60fps 描画）**: 再生位置を `UiState` に入れず描画フェーズで `PositionProvider` から直接読むことで、再コンポーズを起こさずに滑らかに追従します。言語確定後は母語（青系）・学習言語（ピンク系）のテーマカラーで波形を色分けし、初回描画や再表示時には左から右へプログレッシブにアニメーション描画します。
- **直感的なチャンク編集**:
  - 再生位置での**チャンク分割**（前後の無音自動トリミング付き）。
  - 選択した複数チャンクの**連結（マージ）**、波形上の範囲選択からの**チャンク追加・チャンク解除**。
  - チャンク境界の `±100ms` 微調整、不要チャンクのスキップ指定・削除、文字起こしテキストの手動編集。

### 4. ライブラリ管理・再生リスト・ブックマーク横断再生・入出力
- **4 つのライブラリタブ**:
  - **フォルダ**: 実フォルダ（SAF 参照）と仮想フォルダによる階層管理、フォルダ単位の言語ペア一括設定、埋め込みカバーアート表示、最大到達進捗バー（95% 超で完了緑表示）、前回聴取位置への自動スクロール。
  - **ファイル**: タイトル・フォルダ検索、並び替え（ファイル名自然順・手動ドラッグ順 / アクセス日時 / 追加日時 / 完了度）、学習言語フィルタチップ。
  - **再生リスト**: スマート再生リスト（「最近開いたファイル」「よくアクセスするファイル」「お気に入り」）とユーザー作成再生リスト、ドラッグ＆ドロップによる並び替え。
  - **ブックマーク**: ブックマーク付きチャンクがある教材をアコーディオン形式で一覧し、ライブラリ画面内の専用コントローラーでブックマークチャンクだけを直接連続再生・リピート。
- **文字起こしのインポート・エクスポート**:
  - 標準形式 `chunkflow-transcript` v1（`.chunkflow.json`）による自動保存・読み込みに加え、`SRT` / `WebVTT` / `LRC` の取り込み、および `SRT` / `LRC` / `TSV` / `JSON` 形式でのプレビュー付きエクスポートに対応。

---

## モジュール構成と主要技術

### モジュール一覧

| モジュール | 種類 | 役割 |
| --- | --- | --- |
| `:app` | Android Application | `MainActivity`、Hilt ルート、Navigation 3 による画面遷移と大画面 2 ペイン構成 |
| `:feature:library` | Android Feature (Compose) | ライブラリ画面（フォルダ・ファイル・再生リスト・ブックマーク）、文字起こしエクスポート画面 |
| `:feature:player` | Android Feature (Compose) | プレイヤー画面、全体/拡大波形、スクリプト一覧、チャンク編集、詳細文字起こしダイアログ、ファイル設定シート |
| `:feature:settings` | Android Feature (Compose) | 設定画面（再生設定・チャンク分割・文字起こしエンジン/モデル管理・言語カタログ） |
| `:core:designsystem` | Compose Library | Material 3 テーマ、言語役割別カラーパレット（母語/学習言語）、共通アイコン、プレビュー定義 |
| `:core:model` | Pure Kotlin (JVM) | ドメインモデル（`MediaItem`, `Chunk`, `Waveform`, `LoopMode`, `AutoPauseMode`, `LanguagePair` 等） |
| `:core:playback` | Android Library | `PlaybackService`、Media3 `ExoPlayer` / `MediaSession`、`LearningPlayer`、`ChunkScheduler` |
| `:core:media` | Android Library | `MediaCodec` ストリーミングデコード、MP3 `IndexSeeker`、Whisper Tiny ONNX LID、マルチエンジン STT（ML Kit / `SpeechRecognizer` / `sherpa-onnx`）、ML Kit 翻訳 |
| `:core:audio` | Pure Kotlin (JVM) | 波形ピーク/RMS 集計、無音検出チャンク分割（`SilenceChunkSegmenter`）、時間軸補正 |
| `:core:transcript` | Pure Kotlin (JVM) | `chunkflow-transcript` v1 JSON / SRT / WebVTT / LRC パーサ・検証・エクスポータ、チャンク対応付け |
| `:core:data` | Android Library | リポジトリ実装（`LibraryRepository`, `AnalysisRepository`, `TranscriptRepository`）、`AnalysisWorker` |
| `:core:database` | Android Library (Room) | Room データベース（教材、チャンク、文字起こし、再生リスト、フォルダ設定） |
| `:core:datastore` | Android Library | Preferences DataStore（グローバル設定、カスタム速度、エンジン設定、有効言語一覧） |
| `:core:testing` | Android Library | テスト・Compose Preview 用の Fake 実装とサンプルフィクスチャ |

### 技術スタック
- **UI**: Kotlin 2.4、Jetpack Compose（BOM 2026.09 / Material 3 / Material 3 Adaptive）、Navigation 3
- **再生**: AndroidX Media3 1.11（ExoPlayer、MediaSessionService、カスタム `ForwardingSimpleBasePlayer`）
- **音声解析・オンデバイス AI**:
  - `MediaExtractor` + `MediaCodec`（ストリーミング PCM デコード）
  - ONNX Runtime Android（量子化 Whisper Tiny 2 言語判定）
  - ML Kit GenAI Speech Recognition / ML Kit On-Device Translation
  - Android `SpeechRecognizer`（オンデバイス認識 + `EXTRA_AUDIO_SOURCE`）
  - `sherpa-onnx`（SenseVoice 多言語オフライン音声認識）
  - ICU4J（ピンイン・ローマ字・キリル・デーヴァナーガリー等の音訳と簡繁変換）
- **データ永続化・非同期**: Room 2.8、Preferences DataStore 1.2、WorkManager 2.12、Hilt 2.60、Kotlin Coroutines / Flow、kotlinx.serialization

---

## ビルドとテスト

ビルドには JDK 17 以上が必要です。macOS の開発環境では Android Studio 同梱の JBR を使用します（[ADR 0001](docs/adr/0001-module-structure-and-versions.md)）。

```sh
# コード整形・静的解析・単体テスト・デバッグ APK のビルド
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew spotlessCheck lint testDebugUnitTest assembleDebug

# Compose Preview スクリーンショットテストの検証
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew validateDebugScreenshotTest

# 文字起こし JSON Schema とサンプルの検証
uv run --no-project --with jsonschema python .github/scripts/validate_transcript_examples.py

# 接続中の実機・エミュレータへのインストールと起動
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew :app:installDebug
adb shell am start -n com.miuraken.chunkflow/.MainActivity
```

---

## ドキュメント構成

- [`design_doc.md`](design_doc.md): **設計書（単一の情報源）** — アーキテクチャ、データモデル、再生・解析・オンデバイス AI の詳細設計、およびライブラリ画面・プレイヤー画面・設定画面の完全な UI/ロジック仕様
- [`AGENTS.md`](AGENTS.md): 開発ルール・モジュール担当範囲・完了の定義（DoD）
- [`docs/format/`](docs/format/): 文字起こし形式 `chunkflow-transcript` v1 の JSON Schema と検証用サンプル
- [`docs/adr/`](docs/adr/): Architecture Decision Records（ADR 0001〜0011）
- [`docs/spikes/`](docs/spikes/): 再生・音声解析・ONNX 言語判定・ML Kit 音声認識の技術検証レポート
- [`docs/manual-test.md`](docs/manual-test.md): 実機・エミュレータ向け手動テスト手順書
- [`docs/qa/`](docs/qa/): 各マイルストーン（Phase 0, M1〜M4）の品質ゲート検証記録
