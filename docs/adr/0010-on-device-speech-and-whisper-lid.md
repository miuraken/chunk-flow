# ADR-0010: 端末内の音声認識（SpeechRecognizer）と量子化 Whisper Tiny による 2 言語判定

- **状態**: 承認済み
- **日付**: 2026-10-15
- **担当**: lead
- **関連**: 設計書 §0（D7 / D8）、§1.4、§2、§6.1〜§6.3、§7.4.6、§7.5、付録 C

---

## 1. 背景

初期設計（ADR-0001〜0009、設計書 v0.6）では、文字起こしと言語判定はすべて PC 側の `chunkflow-transcribe`（faster-whisper / mlx-whisper）で実行し、Android アプリは文字起こし JSON（`chunkflow-transcript` 形式）の読み込みとチャンク境界への割り当てのみを行う方針（D7, D8）としていた。

しかし実際の語学学習の運用では、以下のニーズが顕在化した。

1. **文字起こし JSON なしの教材の即時利用**: 音声ファイル単体（MP3 / M4A 等）を端末に取り込んだ直後でも、PC ツールを通さずにチャンク単位のスクリプト確認・言語別フィルタ再生を行いたい。
2. **チャンク単位の言語バッジ切り替えと再文字起こし**: 2 言語（基準言語・学習言語）が混在する教材において、自動判定が誤ったチャンクの言語バッジ（未判定時は `"-"`) をタップして手動でトグルした際、切り替え後の言語で即座に音声認識テキストを更新したい。また、認識結果が不十分なチャンクを長押しメニュー（「再度、文字起こしする」）から強制的に再認識させたい。
3. **再生位置に応じた先読み文字起こし（Lookahead Transcription）**: 音声ファイル全体をインポート時に一括文字起こしすると待ち時間が長くなるため、現在選択・再生しているチャンクから前方に順次バックグラウンドで文字起こしを進める仕組みが必要となった。

---

## 2. 決定事項

PC 側の `chunkflow-transcribe` による高精度な事前文字起こし JSON のインポートを引き続き第一の経路としつつ、Android 端末単体で完結する**オンデバイスのチャンク単位言語判定＋音声認識パイプライン**（`core:media` モジュール内の `OnDeviceSpeechTranscriber`）を正式採用する。

### 2.1 2 段階のオンデバイス推論・認識アーキテクチャ

1. **量子化 Whisper Tiny による 2 言語判定（`WhisperTinyLanguageDetector`）**
   - 推論ランタイム（ONNX Runtime や TFLite 等のネイティブ C++ 共有ライブラリ）を追加せず、**純 Kotlin 実装**の軽量テンソル演算によって Whisper Tiny の Audio Encoder（30 秒ログメルスペクトログラム → 1500 フレーム特徴量）と Text Decoder の 1 ステップ目（`<|startoftranscript|>` 直後の言語トークンロジット）のみを実行する。
   - モデル重みは `core/media/src/main/assets/whisper_tiny_lid_q8q4.bin`（約 10.75 MB、Encoder 重み INT8 量子化・Decoder 埋め込み INT4 量子化）としてアセットに同梱する。
   - 全 99 言語のフル探索ではなく、教材に設定された **2 言語（`base` と `target`）の言語トークンロジットのみを比較**してチャンク言語を決定する。これにより短いチャンク（1 秒未満〜数秒）でも高い識別精度と高速な推論を両立する。
   - さらに音声認識テキストが得られた後は、かな・ハングル・キリル文字・ギリシャ文字・デーヴァナーガリー・声調記号等の書記体系ヒューリスティック（`LanguageHeuristics`）および簡体字・繁体字の語彙プロファイル判定と組み合わせ、音響スコアとテキスト特徴の両面から最終的なチャンク言語を決定する。

2. **Android `SpeechRecognizer`（`EXTRA_AUDIO_SOURCE`）によるチャンク音声認識（`AndroidSpeechRecognizerChunkTranscriber`）**
   - `MediaExtractor` + `MediaCodec` でデコードしたチャンク区間の 16kHz モノラル 16-bit PCM を `ParcelFileDescriptor.createPipe()` 経由で `RecognizerIntent.EXTRA_AUDIO_SOURCE` に流し込み、マイクを経由せずチャンクの PCM データを直接 `SpeechRecognizer` に認識させる。
   - `AndroidManifest.xml` に `android.permission.RECORD_AUDIO` および `<queries>`（`android.speech.RecognitionService`）を宣言する（`SpeechRecognizer` API の仕様上、パイプ入力であっても `RECORD_AUDIO` 権限が要求されるため）。
   - 1 チャンクあたり設定された 2 言語（または指定された単一言語）のロケール（BCP-47 タグ）で認識を実行し、チャンク ID × 言語コードごとのインメモリキャッシュ（`chunkLanguageTextCache`）に認識結果テキストを保持する。

### 2.2 先読み文字起こし（Lookahead）と優先キューによるプリエンプション制御

1. **先読み文字起こし（Lookahead Transcription）**
   - ユーザー設定 `lookaheadTranscriptionEnabled`（デフォルト `true`）が有効な場合、現在選択中または再生中のチャンク位置から後続チャンクへ向かって順次バックグラウンドで文字起こしと言語判定を実行する。
   - 進行状況は `LookaheadTranscriptionStatus`（`isRunning`, `completedChunks`, `totalChunks`, `currentChunkIndex`）として `StateFlow` で UI に公開する。

2. **優先リクエストによる先読みの割り込み（Priority Preemption）**
   - Android の `SpeechRecognizer` はメインスレッドでのバインドと直列実行を前提としており、複数チャンクの同時認識は競合やエラー（`ERROR_BUSY`）を引き起こす。
   - そのため `PriorityRecognitionSlotGate` / `PriorityRecognitionLock` を設け、ユーザーの明示的な操作（言語バッジタップによる `updateChunkLanguage`、または長押しメニュー「再度、文字起こしする」による `retranscribeChunk`）が発生した際は、`priorityWaitingCount` をインクリメントしてバックグラウンドの先読みワーカーを即座に中断・待機させ、ユーザー操作対象のチャンク認識を最優先で実行する。

3. **言語切り替え時のキャッシュ活用と強制再認識の区別**
   - **言語バッジのタップ（`updateChunkLanguage`）**: 未判定（UI 表示 `"-"`) の場合は `base` へ、`base` と `target` の間はトグルで切り替える。切り替え先の言語での認識テキストがインメモリキャッシュに存在する場合は即座にキャッシュテキストを適用し、未キャッシュまたはキャッシュテキストが切り替え先言語の書記体系と矛盾する場合のみ優先キューで `SpeechRecognizer` を実行する。
   - **長押しメニュー「再度、文字起こしする」（`retranscribeChunk`）**: 当該チャンクのキャッシュを破棄し、先読みタスクに割り込んで強制的に再デコード・再認識・言語再判定を実行して DB を更新する。

---

## 3. 影響とトレードオフ

- **メリット**:
  - PC の `chunkflow-transcribe` を実行していない音声ファイルでも、インポート直後からチャンク単位のスクリプト表示・言語フィルタ再生・発音ガイド（ローマ字 / 拼音 / 粤拼等）が機能する。
  - ネイティブ C++ ライブラリ（`.so`）を追加しない純 Kotlin 実装の Whisper Tiny 量子化モデル（約 10.75 MB）と Android 標準 `SpeechRecognizer` の組み合わせにより、APK サイズの肥大化や ABI 互換性問題を最小限に抑えられる。
  - 優先キュー（`PriorityRecognitionLock`）により、バックグラウンドで先読み文字起こしが動いている最中でも、ユーザーの言語バッジ切り替えや再文字起こし操作が待たされずに即応する。
- **トレードオフ / 制約**:
  - `SpeechRecognizer` の利用には `RECORD_AUDIO` 権限の付与と端末側の音声認識エンジン（Google Speech Services 等）が必要であり、権限未付与時や認識サービス非搭載環境では Whisper Tiny LID による音響言語判定のみ、または事前生成 JSON の利用にフォールバックする。
  - 長時間の教材全体を最高精度で文字起こし・単語タイムスタンプ付与する用途では、引き続き PC 側 `chunkflow-transcribe`（faster-whisper / mlx-whisper）が推奨される。
