---
name: stt-benchmark
description: 端末内音声認識（STT）エンジンの定期ベンチマーク計測・新エンジン評価・言語別推奨エンジン更新およびユーザー通知リビジョン更新を行うスキル。数ヶ月ごとの定期再評価や新しい認識エンジン・言語の追加時に手動で呼び出す。
---

# 端末内 STT ベンチマーク評価と推奨エンジン反映スキル (`stt-benchmark`)

このスキルは、OS 標準エンジン（ML Kit GenAI Speech Recognition / Android `SpeechRecognizer`）のアップデートや、新しいオンデバイス STT モデル（`sherpa-onnx`・Moonshine・Whisper 後継モデルなど）の登場に伴い、**実機ベンチマークを実行し、その結果をアプリの言語設定・自動選択順序・ユーザーへのアップデート通知に反映する**ための手順書です。

> [!IMPORTANT]
> - 自動実行（CI 組み込み）は想定していません。開発者が実機（Pixel 等）を接続して手動で呼び出します。
> - **実際の教材の音声や文字起こしはコミットしないでください**（`AGENTS.md` §10）。すべてのクリップとキャッシュは `tools/stt-bench/.cache/`（gitignore 済み）にのみ配置します。

---

## 1. ベンチマーク実行の全体フロー

### 1.1 前提条件の確認
1. 対象の実機（Pixel 11 / Tensor G5 以降など）が `adb devices` で接続され、**画面ロックが解除・スリープ無効化**されていることを確認する（OS の `SpeechRecognizer` / ML Kit はロック中に失敗または遅延するため）。
2. 新しい STT エンジンを追加する場合は、先に §2 の手順で `SttBenchEngine` と `SttBenchRunner` にハーネスを接続する。

### 1.2 評価用クリップの準備（`tools/stt-bench/`）
1. **実音声コーパスからのクリップ抽出**（Common Voice / FLEURS / MLS / LibriSpeech 等）:
   ```bash
   python3 tools/stt-bench/prepare_real_clips.py --langs ja,en,zh,yue,ko,es,fr,de,it,pt,ru,ar,hi,th,vi,id,tr,nl,pl,sv,el
   ```
   - `tools/stt-bench/.cache/clips/<lang>/` に 16kHz モノラル WAV と `manifest.tsv` が生成される。
2. **Whisper Tiny による難度層化サンプリング（Easy / Medium / Hard の計 10 件など）**:
   - 大量クリップから過小・過大評価を防ぐため、ベースライン（Whisper Tiny 等）で全件を一度走らせ、誤り率の分位点からバランスよく代表クリップを選ぶ:
   ```bash
   python3 tools/stt-bench/select_real_clips.py --langs <対象言語カンマ区切り> --count 10
   ```

### 1.3 実機でのベンチマーク計測
1. `app` のデバッグ APK をインストールし、対象エンジン × 対象言語で計測を実行する:
   ```bash
   JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
     bash tools/stt-bench/run_all.sh \
     --langs ja,en,zh,yue,ko,es,fr,de,it,pt,ru,ar,hi,th,vi,id,tr,nl,pl,sv,el \
     --engines ml_kit,speech_recognizer,sherpa_whisper_tiny,sherpa_whisper_base,sherpa_whisper_small,sherpa_sense_voice,moonshine_tiny
   ```
2. **コンテキスト注入（先行発話プロンプト・語彙バイアス）の検証**:
   - Whisper 系または新規エンジンでコンテキスト注入の効果を測る場合は、`SttBenchReceiver` の `context_hint` エクストラ（`"PROMPT:<先行発話>"` / `"BIAS:<語彙>"` / `"BOTH:<テキスト>"`）を指定して A/B 比較を行う。

### 1.4 採点とレポート集計
1. 各ペアの JSONL 出力を採点し、集約 TSV / Markdown を生成する:
   ```bash
   python3 tools/stt-bench/combine.py \
     --raw-dir tools/stt-bench/.cache/results/<run_id> \
     --clips-dir tools/stt-bench/.cache/clips \
     --out-tsv tools/stt-bench/.cache/results/<run_id>/summary.tsv
   ```
2. 確認すべき主要指標:
   - **誤り率**: 文字誤り率 `CER`（`ja`, `zh`, `yue`, `ko`, `th`）および単語誤り率 `WER`（スペース区切り言語）
   - **処理速度**: `RTF`（実時間比 = 推論時間 / 音声長）と `p50 / p95` レイテンシ（ms）
   - **メモリ増分**: PSS ピーク増分（MB）
   - **完走率**: タイムアウト・モデル未導入・空文字応答を除いた成功率

---

## 2. 新しい STT エンジンを追加するときのチェックリスト

新しいオンデバイス音声認識エンジンを追加する場合は、以下の箇所を順番に実装します。

1. **モデル・列挙型の定義（`core/model`）**:
   - [`SttEngine.kt`](file:///Users/miuraken/src/gogaku-player/core/model/src/main/kotlin/com/miuraken/chunkflow/core/model/SttEngine.kt):
     - `SttEngine` enum と `DownloadableSttModel` enum に新エンジンを追加する。
     - `DownloadableSttModel.supportsLanguage(languageCode: String)` で対応言語を定義する。
   - [`OnDeviceLanguageModelInfo.kt`](file:///Users/miuraken/src/gogaku-player/core/model/src/main/kotlin/com/miuraken/chunkflow/core/model/OnDeviceLanguageModelInfo.kt):
     - `OnDeviceTranscriptionEngine` enum に対応するエントリを追加する。
2. **推論クラスとモデル管理の実装（`core/media`）**:
   - `SherpaOnnxModelManager.kt` にダウンロード URL・SHA-256・展開先ディレクトリを登録する。
   - 推論クラス（例: `SherpaOnnxSenseVoiceTranscriber.kt`、`MoonshineOnnxTranscriber.kt`）を実装し、`OnDeviceSpeechTranscriber.kt` の `executeAttempt` および `buildExtraEngineStatusesByLanguage` に接続する。
3. **ベンチマークハーネスへの接続（`app` Debug）**:
   - `app/src/debug/kotlin/com/miuraken/chunkflow/sttbench/SttBenchEngine.kt` に CLI 名（例: `sense_voice`）を追加し、`SttBenchRunner.kt` でモデル準備と推論呼び出しを実装する。

---

## 3. ベンチマーク知見をアプリの言語設定・推奨設定へ反映する手順

ベンチマーク結果から言語ごとの最適エンジンやサポート状況が変わった場合は、**以下の 5 箇所を必ずセットで更新**してください。

### Step 1: `SupportedLanguageCatalog.kt` のサポートフラグとバッジの更新
[`SupportedLanguageCatalog.kt`](file:///Users/miuraken/src/gogaku-player/core/model/src/main/kotlin/com/miuraken/chunkflow/core/model/SupportedLanguageCatalog.kt) の `ALL_ENTRIES` を更新します。
- `mlKitSupported` / `mlKitMode`: 実機で ML Kit GenAI Speech Recognition が動作したか。
- `speechRecognizerSupported`: 実機で OS 標準のオフライン `SpeechRecognizer` が動作したか（SODA と言語パックが配信されているか）。
- `whisperCode`: Whisper / SenseVoice 等で認識可能な言語コード。

### Step 2: `LanguageSettings.kt` の整合性確認
[`LanguageSettings.kt`](file:///Users/miuraken/src/gogaku-player/core/model/src/main/kotlin/com/miuraken/chunkflow/core/model/LanguageSettings.kt) の `ML_KIT_SPEECH_LANGUAGE_CODES` と `isSttCapable` は `SupportedLanguageCatalog` から導出されています。
- 単体テスト `LanguageSettingsTest` を実行し、新規対応言語が `isSttCapable(tag) == true` になることを確認します。

### Step 3: `SttEngineSelection`（自動フォールバック順序・おすすめモデル・UI 選択肢）の更新
[`SttEngine.kt`](file:///Users/miuraken/src/gogaku-player/core/model/src/main/kotlin/com/miuraken/chunkflow/core/model/SttEngine.kt) 内の `SttEngineSelection` オブジェクトを更新します。
1. **`defaultOrderForLanguage(language)`**:
   - 「自動（おすすめ）」選択時に試すエンジンの優先順位を、ベンチマークの精度・RTF に基づいて更新する。
2. **`recommendedDownloadEngineForLanguage(language)`**:
   - OS 標準エンジンが非対応（または精度が大幅に劣る）言語に対して、設定画面で「おすすめ」バッジを付け、ワンタップ取得の対象にするダウンロード型エンジンを返す。
3. **`selectableEnginesForLanguage(language)`**:
   - その言語で実質的に機能しない（または上位互換があり選択肢に出すと混乱する）エンジンを除外し、有効な選択肢のみを返す。

### Step 4: 推奨エンジン変更リビジョン (`recommendationRevisionFor`) のインクリメント（重要）
ユーザーが手動で「自動」以外のエンジンを設定している言語について、アプリのアップデート後に「推奨エンジンの状況が変わった」ことを通知するため、[`SttEngine.kt`](file:///Users/miuraken/src/gogaku-player/core/model/src/main/kotlin/com/miuraken/chunkflow/core/model/SttEngine.kt) の **`SttEngineSelection.recommendationRevisionFor(language)`** を更新します。

```kotlin
fun recommendationRevisionFor(language: LanguageTag): Int {
    val normCode = SupportedLanguageCatalog.normalizeToModelManagementCode(language.value)
        .ifBlank { language.value.trim().lowercase() }
    return when (normCode) {
        // 新しいベンチマークで推奨エンジンや対応状況が変わった言語のリビジョンを +1 する
        "ja", "zh", "yue" -> 3
        in REV_2_BENCHMARKED_LANGUAGE_CODES -> 2
        else -> 1
    }
}
```

- **仕組み**:
  - ユーザーが言語 `L` を手動で「自動」以外に設定した時点のリビジョン番号が `UserSettings.acknowledgedSttRecommendationRevisions[L]` に保存されています（初期値 `0`）。
  - アップデート後に `recommendationRevisionFor(L) > acknowledgedSttRecommendationRevisions[L]` かつ `sttEngineByLanguage[L] != recommendedOrPrimaryEngineForLanguage(L)` となると、**ライブラリ画面の起動時ダイアログ**および**設定画面（文字起こし設定セクション）の案内カード**に通知が表示されます。
  - ユーザーはワンタップで「自動（おすすめ）に切り替える」か「現在の設定を維持（確認済みとして記録）」を選べます。

### Step 5: ドキュメントと UI 説明文の更新
1. `docs/benchmarks/YYYY-MM-stt-on-device.md` に新しい計測環境・CER/WER・RTF・メモリ比較表と考察を記録する。
2. `feature/settings/src/main/res/values/strings.xml` のモデル説明文（`settings_model_*_desc`）に最新の推奨言語や特徴を反映する。
3. 以下の単体テストと Spotless を実行してすべて通ることを確認する:
   ```bash
   JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
     gtimeout 300 ./gradlew spotlessApply \
     :core:model:test :core:datastore:testDebugUnitTest \
     :core:media:testDebugUnitTest :core:data:testDebugUnitTest \
     :feature:settings:testDebugUnitTest :feature:library:testDebugUnitTest < /dev/null
   ```
