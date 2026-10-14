# Phase 0 品質ゲート報告

| 項目 | 内容 |
| --- | --- |
| マイルストーン | Phase 0（基盤と技術検証） |
| 実施日 | 2026-10-14 |
| 担当 | `qa` |
| 対象ブランチ | `main`（検証ブランチ: `agent/qa/phase0-gate`） |
| 最終判定 | **PASS** |

---

## 1. Phase 0 完了条件（`design_doc.md` §11）の達成状況

| # | 完了条件（§11） | 状態 | 根拠・確認内容 |
| --- | --- | --- | --- |
| 1 | `./gradlew build` が通る | **達成** | `./gradlew check validateDebugScreenshotTest` および `./gradlew build` がエラー・警告なしで成功（Spotless / ktlint、Android Lint `warningsAsErrors = true`、JVM 単体テスト、スクリーンショットテスト 17 枚、Debug / Release アセンブルを含む） |
| 2 | 空の画面の間を遷移できる | **達成** | `:app` の `ChunkFlowNavDisplay`（Navigation 3）にて `LibraryKey`（初期画面）→ `PlayerKey`（仮の教材を開くボタン）および `SettingsKey`（設定ボタン）への遷移・戻る操作が構成されていることをコードとプレビュー基準画像で確認。実機・エミュレータでのタップ操作手順は [`docs/manual-test.md`](../manual-test.md) §1 に記載 |
| 3 | 形式 v1 の Schema とサンプルがそろっている | **達成** | [`docs/format/chunkflow-transcript-v1.schema.json`](../format/chunkflow-transcript-v1.schema.json)、[`docs/format/README.md`](../format/README.md)、および `docs/format/examples/`（正常系 10 件・異常系 24 件）が揃い、`validate_transcript_examples.py` と `tools/transcriber` の pytest の双方で全件検証済み |
| 4 | スパイクの報告がそろっている | **達成** | S1（[`S1-pipeline-prototype.md`](../spikes/S1-pipeline-prototype.md)、[`S1-real-materials.md`](../spikes/S1-real-materials.md)）、S2（[`S2-playback.md`](../spikes/S2-playback.md)）、S3（[`S3-audio-analysis.md`](../spikes/S3-audio-analysis.md)）および一覧（[`docs/spikes/README.md`](../spikes/README.md)）が揃っている |
| 5 | ADR がそろっている | **達成** | [`docs/adr/0001`](../adr/0001-module-structure-and-versions.md)〜[`0009`](../adr/0009-transcriber-parameters.md) の全 9 件が作成・承認済みであり、結論が `design_doc.md`（Draft v0.6）に反映されている |

---

## 2. 実行した確認（コマンドと結果）

| # | 検証項目 | 実行コマンド | 結果 | 詳細 |
| --- | --- | --- | --- | --- |
| 1 | Android 全体チェック + スクリーンショット検証 | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk" gtimeout 600 ./gradlew check validateDebugScreenshotTest < /dev/null` | **PASS**（exit 0） | 全モジュールの `spotlessCheck`、`lint`、`testDebugUnitTest` / `test`、および `:feature:library:validateDebugScreenshotTest`（17 枚）が成功 |
| 2 | Android 全体ビルド（Debug / Release） | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk" gtimeout 600 ./gradlew build < /dev/null` | **PASS**（exit 0） | `:app`・`:spikes:playback`・`:spikes:audio` を含む全モジュールのビルドが成功 |
| 3 | マージ後のマニフェスト検査（Debug / Release） | `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ANDROID_HOME="$HOME/Library/Android/sdk" gtimeout 300 ./gradlew :app:processDebugMainManifest :app:processReleaseMainManifest < /dev/null && gtimeout 60 uv run --directory tools/transcriber --locked python ../../.github/scripts/check_manifest.py < /dev/null` | **PASS**（exit 0） | `processDebugMainManifest` / `processReleaseMainManifest` / `processDebugManifest` の 3 ファイルすべて `OK`（ADR 0004・設計書 §8.2 準拠） |
| 4 | 文字起こし形式 v1 サンプル検証 | `gtimeout 60 uv run --directory tools/transcriber --locked python ../../.github/scripts/validate_transcript_examples.py < /dev/null` | **PASS**（exit 0） | 正しいサンプル 10 件、不正なサンプル 24 件すべて期待どおり判定（計 34 件 `OK`） |
| 5 | PC ツール（`tools/transcriber`）静的解析 | `gtimeout 120 uv run --directory tools/transcriber --locked ruff check < /dev/null` | **PASS**（exit 0） | `All checks passed!` |
| 6 | PC ツール（`tools/transcriber`）単体テスト | `gtimeout 180 uv run --directory tools/transcriber --locked pytest < /dev/null` | **PASS**（exit 0） | `195 passed, 1 skipped, 1 deselected in 20.65s`（スキップ 1 件は Linux 専用の faster-whisper テスト、deselected 1 件は実モデル `-m model` テスト） |
| 7 | `uv.lock` のパッケージ取得元検査 | `tools/transcriber/uv.lock` 内の全 `source` / URL の検索 | **PASS** | ローカルパッケージ（`editable = "."`）を除く全パッケージが `registry = "https://pypi.org/simple"` および `https://files.pythonhosted.org/...` であり、社内プロキシ URL は一切含まれない |
| 8 | 秘密情報・実教材ファイルの追跡検査 | `git status --short && git ls-files sample_files/` および音声・鍵拡張子の検索 | **PASS** | 音声ファイル（`.mp3` / `.m4a` / `.wav` 等）、`sample_files/`、秘密鍵・認証情報は Git に一切追跡されていない |

---

## 3. 契約・アーキテクチャ・セキュリティ・ADR・スパイクのレビュー結果

### 3.1 契約とデータモデル（`design_doc.md` §6.1・§6.3・§7.4.3）

- **`core:model`**:
  - `MediaId`、`ChunkId`、`EngineId`、`LanguageTag`、`LanguagePair`、`AudioItem`、`Waveform`、`Chunk`、`ChunkOrigin`、`SegmentationParams`、`Transcript`、`WordTiming`、`AnalysisStage`、`StageStatus`、`AnalysisError`、`LoopMode`、`AutoPause`、`AutoPauseScope`、`LanguageFilter`、`PracticeSettings` のすべてが設計書 §6.1 の定義と完全に一致している。
  - 不変条件（`Waveform` の `peak.size == rms.size`、`Chunk` と `LoopMode.AbRange` の `startMs < endMs`）が `init { require(...) }` で保護され、`ModelInvariantsTest` で検証されている。
- **主要インターフェース（§6.3）**:
  - `PlaybackController` / `PositionProvider` / `PlaybackStatus` / `PlaybackError`（`core:playback`）
  - `LibraryRepository` / `ImportProgress` / `ImportError` / `SyncProgress` / `SyncError` / `TranscriptImportResult`（`core:data`）
  - `AnalysisRepository`（`core:data`）
  - `TranscriptParser` / `TranscriptParseResult` / `TranscriptError` / `TranscriptDocument` / `TranscriptSegment`（`core:transcript`）
  - すべて設計書 §6.3 のシグネチャ・型と一致し、日本語 KDoc が付与されている。
- **`core:testing`**:
  - `FakePlaybackController`（`FakePositionProvider` を含む）、`FakeLibraryRepository`、`FakeAnalysisRepository`、`FakeTranscriptParser` と、多言語・各状態のフィクスチャ（`SampleMaterials`、`SampleLibraries`、`PreviewParameterProviders`）が用意され、単体テストで確認されている。
- **`core:audio`**:
  - S3 で検証された 1 パスのストリーミング処理（`PcmDownmix` → `StreamingResampler` → `FrameFeatureExtractor` / `DbfsQuantizer` / `FeatureAccumulator` を束ねる `AnalysisPipeline`）が純 Kotlin で実装され、PCM 全体をメモリに保持しない設計原則（§7.3.1）を満たしている。
- **文字起こし形式 v1（`docs/format/`）**:
  - `chunkflow-transcript-v1.schema.json` と `docs/format/README.md` の意味規則（S1〜S6）が設計書 §7.4.3・§8.1 の上限（ファイル 20MB、セグメント 50,000 件、テキスト 2,000 文字、入れ子深さ 64、ファイル名のパストラバーサル禁止、SHA-256 小文字 64 桁）を網羅している。

### 3.2 アーキテクチャとモジュール境界（`design_doc.md` §5.2、`AGENTS.md` §6）

- **純 Kotlin モジュールの Android 非依存**:
  - `core:model`、`core:audio`、`core:transcript` はいずれも `chunkflow.jvm.library` プラグインを使用し、ソースコード内に `android.*` / `androidx.*` の import が 0 件であることを確認した。
- **UI 状態と再コンポーズ防止の原則**:
  - `PlaybackController` は高頻度の再生位置を `PositionProvider` として分離しており、`feature:*` の各画面は `XxxRoute` と状態を受け取るだけの `XxxScreen` に分離されている（`_agents/rules/compose.md` 準拠）。
- **コルーチン Dispatcher の注入**:
  - `core:data` の `DispatchersModule` で `@Dispatcher(ChunkFlowDispatchers.IO)` / `Default` が提供されている（§5.4）。

### 3.3 セキュリティとプライバシー（`design_doc.md` §8）

- **マニフェスト（権限とエクスポート）**:
  - `app/src/main/AndroidManifest.xml` は最小権限の原則に従い、不要な権限を宣言していない。マージ後のマニフェストでも `.github/scripts/check_manifest.py` により権限とエクスポートされたコンポーネントが ADR 0004 の許可リスト内に収まることが自動検証されている。
- **バックアップ規則（`data_extraction_rules.xml`）**:
  - `cloud-backup` / `device-transfer` ともに `database` ドメインと `file` ドメインの `datastore/` のみを対象とし、大容量かつ著作物を含む `filesDir/media/` と `filesDir/analysis/` を除外している（§8.2、F2.4）。
- **秘密情報・著作物の除外**:
  - `.gitignore` で署名鍵（`*.jks`、`*.keystore` 等）、OAuth クライアント情報、実教材の音声拡張子、`/sample_files/`、Python 仮想環境が除外されており、リポジトリへの混入はない。

### 3.4 ADR・スパイクの整合性と軽微な所見

- `docs/adr/0001`〜`0009` のすべての個別ファイルが「状態: 承認済み」となっており、決定事項が `design_doc.md`（Draft v0.6）§7.2.2・§7.2.4・§7.3.1・§7.4.3・§7.4.4・§7.4.5・§7.4.6・§13 に正しく反映されていることを確認した。
- **軽微な所見（非ブロッカー、`lead` への共有事項）**:
  1. [`docs/adr/README.md`](../adr/README.md) の一覧表（11〜19 行目）の「状態」列が「提案」のままになっている（各 ADR ファイル本文はすべて「承認済み」に更新済み）。次回 `lead` が `docs/adr/` を更新する際にあわせて「承認済み」へ揃えるとよい。
  2. `:feature:library:validateDebugScreenshotTest` 実行時、`@ChunkFlowPreviews` の `name = "UI 言語: 日本語"` / `"UI 言語: 英語"` に含まれるコロンと日本語文字について、プラグインが `Preview name 'UI 言語: 日本語' contains invalid characters. It will be included in the HTML report but ignored in image file names.` という標準エラー出力を出す（テスト自体は成功し、ハッシュ付きのファイル名で 17 枚すべて比較されている）。動作上の問題はないが記録しておく。

---

## 4. M1 以降に引き継ぐ実機確認項目とチューニング参考項目

Phase 0 のスパイク（S1〜S3）は Mac（M3 Pro）および Android エミュレータ（`Pixel_8_Pro`、android-36）で実施された。実機（Pixel 8 クラス）や別環境（NVIDIA GPU 搭載の Linux / Windows 11 WSL2）、人間の操作・判断が必要な項目は以下のとおり整理されており、M1〜M3 の実装・品質ゲートへ引き継ぐ。

### 4.1 実機待ち・人間の作業待ちの計測項目（詳細は [`docs/spikes/README.md`](../spikes/README.md) を参照）

| 区分 | 項目 ID | 概要 | 主な確認時期 |
| --- | --- | --- | --- |
| デコード・時間軸（S3） | D1〜D4, D6 | Pixel 実機での 30 分音声のデコード + 解析速度（目標 60 秒以内）、非同期モードのフレーム欠け有無、ハードウェア / Codec2 デコーダの遅延（529 サンプル差・ヘッダ隠し時の MPEG-1 `+1,728` / MPEG-2 `+1,152` サンプル）、`MediaExtractorCompat` + FLAC の EOS 挙動 | M2（解析パイプライン実装時） |
| シーク・境界・電池・自動ポーズ（S2） | D5, D7〜D12 | Pixel 実機での `IndexSeekDataSource` のシーク誤差と初回遠距離シーク待ち時間、`dynamicScheduling=false` でのループ行き過ぎ（推定値と実音録音での 50ms 未満確認）と再生中の電池消費、画面オフ・Doze 時の自動ポーズ再開精度（`PARTIAL_WAKE_LOCK` の効果）、Bluetooth イヤホンの前 / 次ボタン、AAC シーク後誤差、AudioTrack 出力遅延 | M1（基本再生・電池・シーク）/ M2（ループ・自動ポーズ・イヤホン） |
| Google Drive 連携（S2） | H1, H2 | 実機 SAF の Google Drive プロバイダでの複数選択・JSON の MIME・フォルダ選択可否・オフライン挙動（H1）、および Google Cloud OAuth クライアント登録後の Drive API 認可・`sha256Checksum` 検証（H2） | M1〜M2（SAF 取り込み）/ M3（Drive 手動同期） |
| PC ツール実教材評価・別環境（S1） | H3, H4 | 人間による言語確認表（`*.review.csv`）の記入と正解率算出（H3）、および Linux / WSL2（NVIDIA GPU）での `uv sync`・`pytest`・`faster-whisper` の RTF と結果比較（H4） | M2〜M3（PC ツール・言語フィルタ） |

### 4.2 今後のチューニング参考項目（詳細は `design_doc.md` §13・ADR 0005〜0009 を参照）

1. **文字起こしの品質・速度チューニング（`transcriber`、ADR 0009）**:
   - 交互発話教材（Pimsleur 等）での短いチャンクの平滑化パラメータ調整（`smoothing.low_conf = 0.0`、`neighbor_weight = 0.25`、または平滑化の無効化）。H3 の採点結果をもとに決定する。
   - 英語-ヒンディー語（`en-hi`）で短いヒンディー語が `en`（ローマ字）になる問題への対策（語彙チェックによる再認識、およびコース既定モデルの選定）。
   - 30 分教材での処理時間短縮（言語判定と文字起こしでのエンコーダ出力の再利用、連続する短いチャンクのまとめ処理）。
2. **再生・シーク・解析の実機チューニング（`playback` / `audio-analysis`、ADR 0005〜0008）**:
   - MP3 で `IndexSeeker` を使った際の後半への初回シーク待ち時間の体験確認と、必要に応じたオープン直後のバックグラウンド索引プリフェッチまたは自前 `SeekMap` の検討（ADR 0006）。
   - 単一チャンク / A-B リピートにおける `ClippingConfiguration` + `REPEAT_MODE_ONE` の採否評価（ADR 0007、M2）。
   - Pixel 実機で同期モードのデコードが 30 分 60 秒以内に届かない場合の、`MediaCodec` 非同期モード（出力フレーム数検査付き）の再評価、および FLAC の EOS ガードの実装（ADR 0005、M2）。
   - Pixel 実機での `transcriptOffsetMs` 初期値（ADR 0008）の検証と、±500ms 自動補正（F7.6）との組み合わせ確認（M3）。

---

## 5. 最終判定

- **判定: PASS**
- Phase 0 の完了条件（`design_doc.md` §11）はすべて満たされており、M1 の並行開発に進む準備が整っている。
