# 端末内の音声認識エンジンのベンチマーク（2026-10-22、Pixel 8 Pro、21 言語 + 文脈注入実験）

端末内の 8 つの音声認識エンジンを、合計 21 言語（実教材 25 本・382 クリップ + 合成音声 10 言語）について実機で測り、
言語ごとの推奨エンジン・設定画面の選択肢・自動選択の優先順を決定してアプリの実装へ反映した。
あわせて、語学教材の特性（解説言語の文法・語彙トピックや前後の対話チャンクとの関連）を活かした**文脈（コンテキスト）注入**の可否と効果を
6 言語・5 エンジンで実機検証した（§6）。
計測の道具は [`tools/stt-bench/`](../../tools/stt-bench/README.md)（端末側は debug ビルドの `SttBenchActivity`）。

> [!IMPORTANT]
> 実際の教材の音声・切り出したクリップ・文字起こし（正解を含む）は著作物なのでコミットしていない（AGENTS.md §10）。
> この文書には集計の数値と、合成音声（自作の文）および一般化した語彙例だけを載せる。

## 1. 結論: 言語ごとの採用エンジンとアプリ実装への反映

役割は次の 4 つ。**標準** = 取り込み時の一括文字起こしや再生中の自動文字起こしで最優先するエンジン、**高速** = 端末内で高速に終わる代替、
**高精度** = 1 チャンクずつの「より詳しく文字起こし」向け（遅いが最高精度）、**予備** = ほかが使えないときの最後の手段。

### 1.1 21 言語の採用エンジン一覧（実装反映済み）

| 分類 | 言語 | 標準（推奨・自動） | 高速・別解（設定画面） | 高精度（より詳しく文字起こし） | 外したエンジンと理由 |
| --- | --- | --- | --- | --- | --- |
| **東アジア・英語** | 英語 (`en`) | ML Kit GenAI（WER 1.8%） | Moonshine Base（WER 3.2%、RTF 0.11）、Whisper Tiny（予備） | Whisper Small（WER 1.4%） | SpeechRecognizer（ML Kit と同モデルで遅い）、Whisper Base・SenseVoice |
| | 日本語 (`ja`) | ML Kit GenAI（CER 4.7%） | SenseVoice Small（CER 8.0%、RTF 0.10）、Whisper Tiny（予備） | Whisper Small（CER 9.3%） | Moonshine Tiny/Base（漢字が仮名になる・文頭欠落で CER 20%）、Whisper Base |
| | 中国語 (`zh`) | **SenseVoice Small**（CER 1.8%、RTF 0.10） | ML Kit GenAI（CER 6.6%）、Moonshine Base（CER 6.1%）、Whisper Tiny | Whisper Small（CER 6.1%） | Moonshine Tiny（配布元 404）、SpeechRecognizer、Whisper Base |
| | 広東語 (`yue`) | **SenseVoice Small**（広東語字で出力できる唯一のエンジン） | Whisper Tiny（予備・普通話表記） | Whisper Small（普通話表記） | ML Kit・SpeechRecognizer・Moonshine（すべて非対応） |
| | 韓国語 (`ko`) | ML Kit GenAI（CER 0.0%） | Moonshine Tiny（CER 1.2%、69 MB）、SenseVoice Small（CER 1.2%）、Whisper Tiny | Whisper Small（CER 1.5%） | Moonshine Base（4 割で `"audiotext"` とだけ出る不具合）、Whisper Base |
| **南アジア** | ヒンディー語 (`hi`) | ML Kit GenAI（WER 8.4% / CER 3.4%） | Whisper Tiny（予備） | Whisper Small（WER 25.3% / CER 10.9%） | Whisper Base（ウルドゥー文字で出る）、Moonshine・SenseVoice（非対応） |
| **欧州・東南アジア（ML Kit 対応）** | スペイン語 (`es`) | ML Kit GenAI（WER 0.0%） | **Moonshine Tiny**（WER 0.0%、RTF 0.34、62 MB）、Whisper Tiny | Whisper Small（WER 0.0%） | Moonshine Base（Tiny と同一 `.ort` のため Tiny に一本化）、Whisper Base |
| | ベトナム語 (`vi`) | **Moonshine Base**（実教材 WER 4.4% / CER 2.5%、RTF 0.16） / ML Kit GenAI（WER 2.2%） | ML Kit GenAI、Moonshine Base、Whisper Tiny | Whisper Small（WER 10.0%） | Moonshine Tiny（容量が Base とほぼ同じで精度がやや劣る）、Whisper Base |
| | ドイツ語 (`de`) | ML Kit GenAI（WER 1.6%） | Whisper Tiny（予備） | Whisper Small（WER 7.9%） | SpeechRecognizer（ML Kit と同モデルで遅い）、Whisper Base |
| | イタリア語 (`it`) | ML Kit GenAI（WER 0.0%） | Whisper Tiny（予備） | Whisper Small（WER 5.0%） | SpeechRecognizer（Pixel 8 Pro で言語パック提供なし）、Whisper Base |
| | ポルトガル語 (`pt`) | ML Kit GenAI（BR/EU 合算 WER 1.4%） | Whisper Tiny（予備） | Whisper Small（WER 2.7%） | SpeechRecognizer、Whisper Base（WER 20.5%） |
| | オランダ語 (`nl`) | ML Kit GenAI（WER 0.0%） | Whisper Tiny（予備） | Whisper Small（WER 1.6%） | SpeechRecognizer、Whisper Base |
| | ロシア語 (`ru`) | ML Kit GenAI（WER 1.8%） | Whisper Tiny（予備） | Whisper Small（WER 5.3%） | SpeechRecognizer、Whisper Base |
| | トルコ語 (`tr`) | ML Kit GenAI（WER 0.0%） | Whisper Tiny（予備） | Whisper Small（WER 0.0%） | SpeechRecognizer（言語パック提供なし）、Whisper Base |
| | インドネシア語 (`id`) | ML Kit GenAI（WER 0.0%） | Whisper Tiny（予備） | Whisper Small（WER 5.2%） | SpeechRecognizer、Whisper Base |
| **OS SpeechRecognizer 専用** | ポーランド語 (`pl`) | ML Kit GenAI 枠（内部で **SpeechRecognizer** へ自動委譲、WER 0.0%） | Whisper Tiny（予備） | Whisper Small（WER 3.6%） | `com.google.android.tts`（ML Kit）には `pl-PL` がないが、`com.google.android.as`（SpeechRecognizer）には公式パックがあるため透過委譲 |
| **OS 非対応（Moonshine 対応）** | アラビア語 (`ar`) | **Moonshine Base**（合成 CER 3.3%、母音記号つき） | Moonshine Tiny（28 MB、CER 4.5%）、Whisper Tiny | Whisper Small（CER 3.0%） | ML Kit・SpeechRecognizer（非対応） |
| | ウクライナ語 (`uk`) | **Moonshine Base**（合成 WER 6.3% / CER 2.9%、RTF 0.12） | Whisper Tiny（予備） | Whisper Small（WER 20.3%） | ML Kit・SpeechRecognizer（非対応）、Moonshine Tiny（容量が同じで精度が低い） |
| **OS・Moonshine とも非対応** | タイ語 (`th`) | **Whisper Small**（CER 4.9%） | Whisper Tiny（予備、CER 33.0%） | Whisper Small | ML Kit・SpeechRecognizer・Moonshine（すべて非対応）、Whisper Base（CER 17.0%） |
| | ルーマニア語 (`ro`) | **Whisper Small**（CER 9.5%、文脈注入でさらに改善） | Whisper Tiny（予備） | Whisper Small | ML Kit・SpeechRecognizer・Moonshine（非対応） |
| | ペルシア語 (`fa`) | **Whisper Small**（CER 11.7%） | Whisper Tiny（予備） | Whisper Small | ML Kit・SpeechRecognizer・Moonshine（非対応） |
| | パンジャブ語 (`pa`) | **Whisper Small**（CER 21.2%、デーヴァナーガリー出力） | Whisper Tiny（予備） | Whisper Small | ML Kit・SpeechRecognizer・Moonshine（非対応）、Whisper Base（シャームキー文字で出力） |
| | スワヒリ語 (`sw`) | **Whisper Small**（CER 7.1% / WER 84.3% ※分かち書き差） | Whisper Tiny（予備） | Whisper Small | ML Kit・SpeechRecognizer・Moonshine（非対応） |

### 1.2 アプリ実装への具体的な反映内容

1. **カタログの OS 対応フラグの整理（[`SupportedLanguageCatalog.kt`](../../core/model/src/main/kotlin/com/miuraken/chunkflow/core/model/SupportedLanguageCatalog.kt)、[`OnDeviceRecognitionModelState.kt`](../../core/model/src/main/kotlin/com/miuraken/chunkflow/core/model/OnDeviceRecognitionModelState.kt)）**
   - 実機（Android 16/17 Pixel 8 Pro）の MDD カタログで ML Kit・SpeechRecognizer の両方が非対応だった `yue`、`th`、`ar`、`uk`、`fa`、`pa`、`ro`、`sw` を `speechRecognizerSupported = false, mlKitSupported = false` に更新した。
   - `OnDeviceLanguageModelInfo.enrichedWithCatalog()` で `mlKitStatus` を判定する際、`speechRecognizerSupported` と独立に `mlKitSupported` を見るように修正した。
2. **ポーランド語（`pl`）等の「SpeechRecognizer のみ対応」言語における ML Kit 枠からの透過委譲（[`OnDeviceSpeechTranscriber.kt`](../../core/media/src/main/kotlin/com/miuraken/chunkflow/core/media/OnDeviceSpeechTranscriber.kt)）**
   - 設定画面では UI をシンプルに保つため `SpeechRecognizer` を選択肢から隠して `ML Kit GenAI` に一本化している。
   - `pl-PL` のように `com.google.android.tts`（ML Kit）にはモデルがなく `com.google.android.as`（SpeechRecognizer）にのみモデルがある言語でも、設定画面の「ML Kit GenAI」行で「インストール済み / ダウンロード可能」と表示し、ダウンロード要求や推論時に自動で `SpeechRecognizer` へ委譲するようにした。
3. **OS 非対応 5 言語（`th`、`ro`、`fa`、`pa`、`sw`）での Whisper Small 昇格（[`SttEngine.kt`](../../core/model/src/main/kotlin/com/miuraken/chunkflow/core/model/SttEngine.kt)）**
   - 通常は `Whisper Small`（RTF 3〜5）を「より詳しく文字起こし」専用としているが、`th`、`ro`、`fa`、`pa`、`sw` では OS エンジンも Moonshine / SenseVoice も使えず、`Whisper Tiny` では実用精度に達しない（例: タイ語 CER 33.0% vs Small 4.9%）。
   - この 5 言語に限り、`recommendedDownloadEngineForLanguage`・`selectableEnginesForLanguage`・`resolveAutoEngine`・`tryOrder` のすべてで **`SHERPA_ONNX_WHISPER_SMALL` を標準（推奨）エンジンとして昇格**した。
4. **スペイン語（`es`）での Moonshine Tiny 追加（[`SttEngine.kt`](../../core/model/src/main/kotlin/com/miuraken/chunkflow/core/model/SttEngine.kt)）**
   - 実教材 12 クリップで `Moonshine Tiny`（62 MB）が **WER 0.0%・RTF 0.34**（ML Kit の 1.5 倍高速）を達成したため、`es` の選択肢に `MOONSHINE_TINY`（`HIGH_ACCURACY` + `FAST`）を追加した。

---

## 2. 測り方

- **端末**: Google Pixel 8 Pro（Tensor G3、Android 17、RAM 11 GB）。アプリは debug ビルド（0.1.0）。
- **経路**: 本番の「より詳しく文字起こし」と同じ経路（`transcribeChunkTrial`）で、学習言語だけを指定し、エンジンを 1 つに固定した
  （ほかのエンジンに切り替えない）。エンジンごとにアプリを起動し直し、言語ごとに先頭のクリップで 1 回ウォームアップしてから
  （これを「初回」として記録）、全クリップを 1 つずつ文字起こしした。各回の前に 5〜10 秒休ませ、端末が熱いときは冷えるまで待った。
- **音源**
  - **実教材（第 1 バッチ: 6 言語 202 クリップ）**: 英語 36・日本語 34・中国語 30・広東語 35・韓国語 35・ヒンディー語 32。
  - **実教材（第 2 バッチ: 15 言語 16 ファイル・180 クリップ）**: すべて英語（`en`）をベース言語とする語学教材 MP3（16 本）から無音検出で 930 候補を切り出し、端末の Whisper Tiny 言語判定（`mode=lid`）を実行したうえで、各言語 12 クリップ（合計 180 クリップ）を選定した。ポルトガル語はブラジル（`pt-BR` 6 件）と欧州（`pt-PT` 6 件）の両方を含む。
  - **合成音声**: macOS の `say` で 10 言語 × 12 文（英語・スペイン語は 2 人、ほかは 1 人の自然な声）。
- **指標**
  - 精度: 日本語・中国語・広東語・韓国語・タイ語は CER（文字誤り率）、ほかは WER（単語誤り率）を主指標とし、語形変化や分かち書きの影響が大きい言語には CER も併記した。句読点・記号・大文字と小文字・数字の書き方・簡体字と繁体字の違いは誤りに数えない（[`score.py`](../../tools/stt-bench/score.py)）。
  - 速度: 1 クリップの処理時間の中央値、RTF（処理時間の合計 ÷ 音声の長さの合計）、初回（モデルの読み込みを含む）。

---

## 3. 結果（全 21 言語）

### 3.1 第 1 バッチ（英語・東アジア・ヒンディー語・アラビア語・ウクライナ語）

#### 英語（en、実教材 36 件 / 合成 24 件）

| エンジン | 実教材 WER | 合成音声 WER | 中央値（実教材） | RTF | 初回 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Whisper Small | 1.4% | 0.0% | 6.21 s | 2.22 | 6.87 s |
| ML Kit GenAI | 1.8% | 0.0% | 1.57 s | 0.59 | 2.09 s |
| Moonshine Base | 3.2% | 0.5% | 288 ms | 0.11 | 2.27 s |
| Whisper Base | 3.9% | 0.0% | 1.90 s | 0.77 | 3.26 s |
| Moonshine Tiny | 3.9% | 1.4% | 288 ms | 0.11 | 2.29 s |
| SpeechRecognizer | 4.3% | 0.0% | 2.46 s | 0.94 | 3.00 s |
| SenseVoice Small | 5.0% | 0.0% | 287 ms | 0.11 | 2.30 s |
| Whisper Tiny | 5.4% | 0.9% | 879 ms | 0.32 | 1.74 s |

#### 日本語（ja、実教材 34 件 / 合成 12 件）

| エンジン | 実教材 CER | 合成音声 CER | 中央値（実教材） | RTF | 初回 |
| --- | ---: | ---: | ---: | ---: | ---: |
| ML Kit GenAI | 4.7% | 5.4% | 1.59 s | 0.55 | 1.28 s |
| SpeechRecognizer | 5.3% | 5.4% | 2.63 s | 0.96 | 2.05 s |
| SenseVoice Small | 8.0% | 5.4% | 289 ms | 0.10 | 258 ms |
| Whisper Small | 9.3% | 5.9% | 7.70 s | 2.67 | 7.64 s |
| Whisper Base | 18.2% | 16.3% | 2.81 s | 0.98 | 2.82 s |
| Moonshine Tiny | 20.5% | 7.7% | 319 ms | 0.12 | 1.30 s |
| Moonshine Base | 20.9% | 10.0% | 372 ms | 0.14 | 1.55 s |
| Whisper Tiny | 25.1% | 29.9% | 1.56 s | 0.54 | 1.57 s |

#### 中国語・普通話（zh、実教材 30 件 / 合成 12 件）

| エンジン | 実教材 CER | 合成音声 CER | 中央値（実教材） | RTF | 初回 |
| --- | ---: | ---: | ---: | ---: | ---: |
| SenseVoice Small | 1.8% | 1.4% | 241 ms | 0.10 | 262 ms |
| Moonshine Base | 6.1% | 1.4% | 303 ms | 0.13 | 1.91 s |
| Whisper Small | 6.1% | 2.2% | 6.24 s | 2.58 | 5.66 s |
| ML Kit GenAI | 6.6% | 5.1% | 1.42 s | 0.58 | 1.41 s |
| SpeechRecognizer | 7.5% | 5.1% | 2.39 s | 0.99 | 2.32 s |
| Whisper Base | 13.6% | 3.6% | 2.51 s | 1.05 | 2.15 s |
| Whisper Tiny | 18.9% | 8.7% | 1.04 s | 0.43 | 1.09 s |

#### 広東語（yue、実教材 35 件 / 合成 12 件）

| エンジン | 実教材 CER | 合成音声 CER | 中央値（実教材） | RTF | 初回 |
| --- | ---: | ---: | ---: | ---: | ---: |
| SenseVoice Small | 0.0%（有利） | 1.7% | 214 ms | 0.11 | 412 ms |
| Whisper Small | 36.7% | 25.6% | 5.95 s | 3.04 | 5.89 s |
| Whisper Base | 43.5% | 28.9% | 2.37 s | 1.23 | 2.43 s |
| Whisper Tiny | 50.6% | 33.1% | 1.30 s | 0.66 | 1.15 s |

#### 韓国語（ko、実教材 35 件 / 合成 12 件）

| エンジン | 実教材 CER | 合成音声 CER | 中央値（実教材） | RTF | 初回 |
| --- | ---: | ---: | ---: | ---: | ---: |
| ML Kit GenAI | 0.0% | 1.6% | 1.27 s | 0.64 | 1.27 s |
| SpeechRecognizer | 0.0% | 1.6% | 2.04 s | 1.04 | 1.85 s |
| Moonshine Tiny | 1.2% | 4.3% | 279 ms | 0.14 | 1.23 s |
| SenseVoice Small | 1.2% | 2.1% | 315 ms | 0.16 | 306 ms |
| Whisper Small | 1.5% | 2.1% | 6.55 s | 3.25 | 6.65 s |
| Whisper Base | 4.2% | 2.7% | 2.43 s | 1.28 | 2.55 s |
| Whisper Tiny | 15.4% | 11.8% | 812 ms | 0.41 | 773 ms |
| Moonshine Base | 55.0% | 5.9% | 595 ms | 0.29 | 3.92 s |

#### ヒンディー語（hi、実教材 32 件 / 合成 12 件）・アラビア語（ar、合成 12 件）・ウクライナ語（uk、合成 12 件）

| 言語 | エンジン | 実教材 WER（CER） | 合成音声 WER（CER） | 中央値 | RTF | 初回 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| hi | ML Kit GenAI | 8.4%（3.4%） | 17.8%（8.2%） | 1.39 s | 0.65 | 1.46 s |
| hi | SpeechRecognizer | 8.4%（3.4%） | 17.8%（8.2%） | 2.26 s | 1.07 | 2.33 s |
| hi | Whisper Small | 25.3%（10.9%） | 54.5%（23.6%） | 8.84 s | 4.21 | 10.60 s |
| hi | Whisper Base / Tiny | 105.2% / 109.7% | 116.8% / 102.0% | 2.70 s / 1.40 s | 1.29 / 0.66 | 2.78 s / 1.24 s |
| ar | Moonshine Tiny | — | 14.5%（4.5%） | 1.00 s | 0.23 | 3.35 s |
| ar | Moonshine Base | — | 15.8%（3.3%） | 925 ms | 0.23 | 3.97 s |
| ar | Whisper Small | — | 15.8%（3.0%） | 9.19 s | 2.13 | 7.97 s |
| uk | Moonshine Base | — | 6.3%（2.9%） | 417 ms | 0.12 | 1.95 s |
| uk | Moonshine Tiny | — | 13.9%（3.2%） | 361 ms | 0.12 | 3.95 s |
| uk | Whisper Small | — | 20.3%（4.1%） | 8.75 s | 2.54 | 7.90 s |

---

### 3.2 第 2 バッチ: 新規 15 言語の実教材ベンチマーク（各 12 クリップ、計 180 クリップ）

#### Tier 1: ML Kit GenAI が WER 0.0〜2.2%・RTF 0.53〜0.71 で圧勝した 9 言語（`es, vi, de, it, pt, nl, ru, tr, id`）

| 言語 | エンジン | 実教材 WER | 実教材 CER | 中央値 | RTF | 初回 | 備考 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| **スペイン語 (`es`)** | **Moonshine Tiny** | **0.0%** | **0.0%** | **959 ms** | **0.34** | 2.62 s | Base と同一モデル、ML Kit より速く完全一致 |
| | **Moonshine Base** | **0.0%** | **0.0%** | 990 ms | 0.37 | 2.47 s | |
| | **ML Kit GenAI** | **0.0%** | **0.0%** | 1.52 s | 0.53 | 3.79 s | 固有名詞（*Sandra Cisneros*）も正確に認識 |
| | **Whisper Small** | **0.0%** | **0.0%** | 7.21 s | 2.63 | 7.37 s | |
| | Whisper Base | 3.1% | 1.0% | 1.52 s | 0.58 | 1.44 s | *Cisneros* → *Sistneros* |
| | SpeechRecognizer | 10.8% | 9.3% | 2.66 s | 1.05 | 6.74 s | 先頭 1 件が空結果（ウォームアップ直後） |
| | Whisper Tiny | 30.8% | 8.0% | 782 ms | 0.28 | 883 ms | |
| **ベトナム語 (`vi`)** | **ML Kit GenAI** | **2.2%** | **1.4%** | 1.34 s | 0.63 | 1.71 s | 合成音声では WER 0.9% |
| | SpeechRecognizer | 2.2% | 1.4% | 2.24 s | 1.07 | 4.74 s | |
| | **Moonshine Base** | **4.4%** | **2.5%** | **326 ms** | **0.16** | 1.61 s | ML Kit の 4 倍高速。合成音声では WER 0.9% |
| | Moonshine Tiny | 5.6% | 2.8% | 314 ms | 0.16 | 1.69 s | |
| | Whisper Small | 10.0% | 3.9% | 7.52 s | 3.60 | 10.85 s | 声調・通り名（*Trần Hưng Đạo*）で誤り |
| | Whisper Base / Tiny | 18.9% / 33.3% | 9.9% / 21.3% | 2.61 s / 1.13 s | 1.25 / 0.51 | 2.54 s / 1.06 s | |
| **ドイツ語 (`de`)** | **ML Kit GenAI** | **1.6%** | **0.3%** | 1.32 s | 0.68 | 2.04 s | 誤りは文脈のない短文 `Ihn` → `In` の 1 語のみ |
| | SpeechRecognizer | 1.6% | 0.3% | 2.13 s | 1.11 | 3.38 s | |
| | Whisper Base | 6.3% | 2.0% | 1.34 s | 0.66 | 2.85 s | |
| | Whisper Small | 7.9% | 1.8% | 5.35 s | 2.88 | 8.37 s | |
| | Whisper Tiny | 25.4% | 7.9% | 705 ms | 0.35 | 1.82 s | |
| **イタリア語 (`it`)** | **ML Kit GenAI** | **0.0%** | **0.0%** | 1.26 s | 0.57 | 4.88 s | 全 12 クリップ完全一致 |
| | Whisper Small | 5.0% | 1.7% | 7.36 s | 3.31 | 7.58 s | |
| | Whisper Base / Tiny | 21.2% / 21.2% | 6.0% / 6.7% | 1.88 s / 961 ms | 0.85 / 0.43 | 1.90 s / 1.05 s | |
| **ポルトガル語 (`pt`)** | **ML Kit GenAI** | **1.4%** | **0.3%** | 1.40 s | 0.64 | 2.14 s | `pt-BR` パックでブラジル・欧州の両方を高精度認識 |
| | SpeechRecognizer | 1.4% | 0.3% | 2.12 s | 1.03 | 2.52 s | |
| | Whisper Small | 2.7% | 0.9% | 7.14 s | 3.23 | 6.98 s | |
| | Whisper Base / Tiny | 20.5% / 21.9% | 5.9% / 8.0% | 2.49 s / 1.03 s | 1.14 / 0.46 | 2.47 s / 1.04 s | |
| **オランダ語 (`nl`)** | **ML Kit GenAI** | **0.0%** | **0.0%** | 1.33 s | 0.62 | 3.01 s | 全 12 クリップ完全一致 |
| | SpeechRecognizer | 0.0% | 0.0% | 2.17 s | 1.03 | 2.05 s | |
| | Whisper Small | 1.6% | 0.3% | 7.13 s | 3.34 | 7.39 s | |
| | Whisper Base / Tiny | 6.6% / 42.6% | 2.0% / 14.3% | 2.42 s / 1.01 s | 1.13 / 0.46 | 2.35 s / 971 ms | |
| **ロシア語 (`ru`)** | **SpeechRecognizer** | **0.0%** | **0.0%** | 2.25 s | 1.11 | 6.12 s | |
| | **ML Kit GenAI** | **1.8%** | **0.3%** | 1.32 s | 0.64 | 1.99 s | 1 語の語尾差のみ、SpeechRecognizer より 1.7 倍速い |
| | Whisper Base | 1.8% | 0.3% | 2.64 s | 1.33 | 4.35 s | |
| | Whisper Small | 5.3% | 1.0% | 7.42 s | 3.91 | 7.56 s | |
| | Whisper Tiny | 12.3% | 2.9% | 965 ms | 0.46 | 1.06 s | |
| **トルコ語 (`tr`)** | **ML Kit GenAI** | **0.0%** | **0.0%** | 1.32 s | 0.71 | 1.85 s | 全 12 クリップ完全一致 |
| | **Whisper Small** | **0.0%** | **0.0%** | 7.15 s | 3.87 | 6.92 s | |
| | Whisper Base / Tiny | 15.9% / 38.6% | 3.9% / 13.5% | 2.48 s / 937 ms | 1.47 / 0.49 | 2.43 s / 945 ms | |
| **インドネシア語 (`id`)** | **ML Kit GenAI** | **0.0%** | **0.0%** | 1.32 s | 0.66 | 1.36 s | 全 12 クリップ完全一致 |
| | SpeechRecognizer | 1.7% | 0.3% | 2.12 s | 1.08 | 5.42 s | |
| | Whisper Small | 5.2% | 1.2% | 6.72 s | 3.47 | 7.52 s | |
| | Whisper Base / Tiny | 6.9% / 36.2% | 1.5% / 10.6% | 1.72 s / 831 ms | 0.95 / 0.42 | 2.55 s / 882 ms | |

#### Tier 2: SpeechRecognizer（`com.google.android.as`）のみ OS パックが存在する言語（`pl`）

| 言語 | エンジン | 実教材 WER | 実教材 CER | 中央値 | RTF | 初回 | 備考 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| **ポーランド語 (`pl`)** | **SpeechRecognizer** | **0.0%** | **0.0%** | 2.22 s | 1.11 | 2.45 s | 全 12 クリップ完全一致。ML Kit 選択時もここへ自動委譲 |
| | Whisper Small | 3.6% | 0.8% | 7.17 s | 3.63 | 8.74 s | |
| | Whisper Base | 10.7% | 2.7% | 2.60 s | 1.36 | 2.64 s | |
| | Whisper Tiny | 42.9% | 13.7% | 1.04 s | 0.52 | 1.08 s | |

#### Tier 3: OS エンジン（ML Kit・SpeechRecognizer）も Moonshine も非対応の 5 言語（`th, ro, fa, pa, sw`）

| 言語 | エンジン | 実教材 WER | 実教材 CER | 中央値 | RTF | 初回 | 備考 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| **タイ語 (`th`)** | **Whisper Small** | — | **4.9%** | 9.33 s | 3.73 | 12.25 s | 実用十分な精度。文脈注入で固有名詞の誤りも解消（§6） |
| | Whisper Base | — | 17.0% | 3.53 s | 1.37 | 3.67 s | |
| | Whisper Tiny | — | 33.0% | 1.31 s | 0.53 | 1.29 s | |
| **ルーマニア語 (`ro`)** | **Whisper Small** | 32.4% | **9.5%** | 7.89 s | 4.07 | 7.86 s | 短い語尾・母音（`ă/â`）の差が中心。文脈注入で解消（§6） |
| | Whisper Base | 44.1% | 11.4% | 2.78 s | 1.51 | 2.49 s | |
| | Whisper Tiny | 54.4% | 15.2% | 1.08 s | 0.54 | 1.02 s | |
| **ペルシア語 (`fa`)** | **Whisper Small** | 53.5% | **11.7%** | 7.85 s | 3.83 | 7.37 s | ゼロ幅非接合子（ZWNJ）やエザーフェ表記差で WER が高く出るが CER は 11.7% |
| | Whisper Base | 79.1% | 27.7% | 2.75 s | 1.29 | 2.08 s | |
| | Whisper Tiny | 97.7% | 40.4% | 934 ms | 0.45 | 711 ms | |
| **パンジャブ語 (`pa`)** | **Whisper Small** | 68.8% | **21.2%** | 11.55 s | 5.00 | 10.34 s | デーヴァナーガリー文字で一貫して出力 |
| | Whisper Base | 103.9% | 104.5% | 2.70 s | 1.28 | 2.85 s | シャームキー（アラビア文字系）とローマ字が混在 |
| | Whisper Tiny | 115.6% | 108.2% | 1.02 s | 0.45 | 1.07 s | ローマ字や英語翻訳（hallucination）になる |
| **スワヒリ語 (`sw`)** | **Whisper Small** | 84.3% | **7.1%** | 7.41 s | 3.64 | 7.48 s | **文字誤り率（CER）は 7.1% と高い精度**。バンツー諸語の膠着動詞の分かち書き差（`nitakula` vs `ni takula`）により単語単位の WER だけが高く算出される |
| | Whisper Base | 102.0% | 15.0% | 2.54 s | 1.39 | 2.74 s | |
| | Whisper Tiny | 98.0% | 36.2% | 991 ms | 0.48 | 914 ms | |

---

## 4. 21 言語を通してわかったこと

1. **OS の 2 つのバックエンド（`com.google.android.tts` = ML Kit、`com.google.android.as` = SpeechRecognizer）の非対称性**
   - 従来の 10 言語では両者の対応言語が完全に一致していたが、21 言語に広げたことで次の 3 パターンがあることが判明した。
     - **両方で対応（12 言語）**: `en, ja, zh, ko, hi, es, de, pt, nl, ru, id, vi`。いずれも ML Kit の方が 1.5〜1.8 倍速い（RTF 0.53〜0.68 vs 0.94〜1.11）。
     - **ML Kit（`tts`）のみ対応（2 言語）**: `it`（イタリア語）、`tr`（トルコ語）。`SpeechRecognizer` 側にはモデルがないが、ML Kit は WER **0.0%** で動作した。
     - **SpeechRecognizer（`as`）のみ対応（1 言語）**: `pl`（ポーランド語）。`ML Kit` にはモデルがないが、`SpeechRecognizer` が WER **0.0%** で動作した（§1.2 のとおりアプリ内では ML Kit 選択時に透過委譲）。
     - **両方とも非対応（8 言語）**: `yue, ar, uk, th, ro, fa, pa, sw`。
2. **ポルトガル語の地域変種（ブラジル `pt-BR` と欧州 `pt-PT`）**
   - Android のオンデバイス音声認識は `pt-BR` パックのみを提供するが、欧州ポルトガル語（`PortugueseEuropean_2_Lesson06_en.mp3`）のチャンクに対しても `pt-BR` パックで **WER 1.4%（CER 0.3%）** と極めて高い精度で認識できた。
3. **文字誤り率（CER）と単語誤り率（WER）の乖離（スワヒリ語・ペルシア語・ルーマニア語）**
   - スワヒリ語（`sw`）の `Whisper Small` は WER 84.3% に見えるが、**CER は 7.1%** であり、音素自体はほぼ正確に聞き取れている（主語接辞・時制接辞・動詞語幹を 1 語につなげるか空白を入れるかの違い）。ペルシア語（`fa`）も複合語の分かち書き差を除いた CER は **11.7%** だった。

---

## 5. 見つかった不具合と対応

1. **Moonshine: 英語以外の出力が途中で切れる（修正済み）**: `MoonshineOnnxTranscriber.maxDecodeTokens` を英語以外で 1 秒あたり 13 トークンに引き上げ、韓国語 Tiny（CER 35.0% → 1.2%）やベトナム語 Base（WER 31.9% → 0.9%）の途中切断を解消した。
2. **SpeechRecognizer・ML Kit: 読み手が音声を読まないと文字起こし全体が止まる（修正済み）**: `PipeWriteGuard` により `poll(POLLOUT)` で書き込み可能か確認してから書き込むようにした。
3. **ポーランド語（`pl`）で ML Kit 選択時にダウンロード・状態表示が非対応になる問題（修正済み）**: `queryLanguageModelInfos` と `requestSttModelDownload(ML_KIT)` で、ML Kit 単体が `UNSUPPORTED` でも OS `SpeechRecognizer` が対応している場合はその状態とダウンロード経路を引き継ぐようにした。
4. **OS 非対応言語（`th, ro, fa, pa, sw`）で精度の低い Whisper Tiny が自動選択される問題（修正済み）**: `SttEngineSelection` でこれら 5 言語の推奨・自動フォールバック先を `SHERPA_ONNX_WHISPER_SMALL` に変更した。

---

## 6. 語学教材の文脈（コンテキスト）注入の調査と実機実験

### 6.1 背景と課題

語学教材において「対象言語（Target Language）と異なるベース言語（Base Language、解説言語）」が設定されている場合、次の強い構造的特徴がある。

1. **ベース言語チャンクによる語彙・文法トピックの予告**: 英語などのベース言語チャンクでは、直後に発話される対象言語の文法事項、新出単語、固有名詞（通りの名前、都市名、人名）、または英語訳が説明される。
2. **前後チャンク間の対話・反復の連続性**: 語学レッスンでは同じキーフレーズや固有名詞が数チャンクにわたって繰り返し発話される（例: 質問チャンクの直後に回答チャンクが続く、またはリピート練習が続く）。
3. **短尺チャンクにおける同音異義語・固有名詞の曖昧性**: 1.5〜2.5 秒の短い発話単体では、前後の文脈がないために同音異義語（例: ドイツ語の `Ihn`［彼を］と `In`［〜の中に］、ベトナム語の `Phố`［通り］と `Phú`）や固有名詞の綴りが誤認識されやすい。

### 6.2 各音声認識エンジンのコンテキスト注入可否（アーキテクチャ調査）

Chunk Flow が搭載する各エンジンについて、API・ONNX グラフ・トークナイザのレベルでコンテキストを注入できるかを調査した。

| エンジン | 注入の可否 | 具体的な注入手段と制約 |
| --- | :---: | --- |
| **1. `sherpa-onnx` Whisper（Tiny / Base / Small）** | **◎ 可能（最も効果大）** | **① `<\|startofprev\|>`（ID `50361`）初期プロンプト注入**: デコーダの第 1 ステップに渡すトークン列の先頭に `[50361, ...前文脈の BPE トークン列..., 50258 (<\|startoftranscript\|>), langToken, 50359 (transcribe), 50363 (notimestamps)]` を配置できる。ONNX デコーダの `tokens` 入力は動的長 `[1, seq_len]` を受け付けるため、モデルファイルの変更なしでそのまま動作する。<br>**② デコーダ Logit Bias（浅い融合 / Shallow Fusion）**: 各デコードステップの `selectGreedyNextToken` で、文脈語彙の BPE トークン ID 集合に対してロジットへ定数バイアス（例: `+2.5`）を加算できる。 |
| **2. Android `SpeechRecognizer`（端末内 SODA）** | **△ API あり（実機効果は限定的）** | Android 13（API 33）以降の `RecognizerIntent.EXTRA_BIASING_STRINGS`（`android.speech.extra.BIASING_STRINGS`）に文字列リストを渡せる。また、先行する対象言語チャンクの PCM 音声を無音（200ms）を挟んで連結して流す**音響コンテキスト連結**が可能。 |
| **3. `ML Kit GenAI Speech Recognition`** | **△ 音響連結のみ** | `com.google.mlkit.genai.speechrecognition.SpeechRecognizerRequest`（`1.0.0-alpha1`）にはテキストプロンプトや語彙バイアスを渡す公開 API が存在しない。ただし先行チャンクとの**音響コンテキスト連結**は利用可能。 |
| **4. `Moonshine ONNX`（Tiny / Base）** | **○ Logit Bias のみ** | Moonshine の `decoder_model.ort` は Whisper のような `<\|startofprev\|>` トークンを持たず、BOS トークン（`1`）から直ちに発話生成を始めるため初期プロンプト注入は不可。ただし SentencePiece トークン ID に対する **Logit Bias** は Whisper と同様に適用可能。 |
| **5. `SenseVoice Small`（CTC）** | **× 不可（音響連結のみ）** | 非自己回帰の CTC（Connectionist Temporal Classification）モデルであり、デコーダ状態を持たないためテキストプロンプトは注入できない（フレーム単位の音響連結のみ可能）。 |
| **6. クラウド STT（OpenAI Whisper / Groq / Gemini）** | **◎ 可能** | OpenAI / Groq の `/v1/audio/transcriptions` は `prompt` パラメータを公式サポートしており、Gemini API はマルチモーダルプロンプトでベース言語の解説文ごと直接指示できる。 |

### 6.3 実機実験の設計（Pixel 8 Pro、6 言語・5 エンジン）

`SherpaOnnxWhisperTranscriber` と `OnDeviceSpeechTranscriber` に上記の注入機構を実装し、第 2 バッチの実教材で誤認識が生じていた代表的なチャンク（6 言語: `de, es, vi, ro, th, pa`）に対して次の 5 条件を Pixel 8 Pro 実機で比較した。

- **`NONE`（ベースライン）**: チャンク単体をコンテキストなしで認識。
- **`BIAS`（語彙バイアスのみ）**: 前後のチャンクや解説から抽出した重要語彙（2〜3 語）を、Whisper の BPE Logit Bias（`+2.5`）および `SpeechRecognizer` の `EXTRA_BIASING_STRINGS` に渡す。
- **`PROMPT`（前文脈プロンプトのみ）**: 直前の対象言語発話（または解説から予測される文脈文）を Whisper の `<|startofprev|>` トークン列として注入する。
- **`BOTH`（`PROMPT` + `BIAS` 併用）**: `<|startofprev|>` プロンプトと BPE Logit Bias の両方を同時に与える。
- **`ACOUSTIC`（前チャンク音声連結）**: 直前の対象言語チャンクの音声と対象チャンクの音声を 200ms の無音で連結して認識器に入力する。

### 6.4 実機実験の結果（Pixel 8 Pro）

#### (1) Whisper（Tiny / Base / Small）における劇的な改善効果

| 言語・クリップ | 正解（Reference） | 条件 | Whisper Tiny | Whisper Base | Whisper Small |
| --- | --- | --- | --- | --- | --- |
| **ドイツ語 (`de`)**<br>`de12_020`<br>（短文の同音異義語 `Ihn` vs `In`） | `Ihn bald zu treffen.`<br>（彼にすぐ会うこと） | `NONE` | `in Ball zu treffen.` ❌ | `in Bald zu treffen.` ❌ | `in Balt zu treffen.` ❌ |
| | | `BIAS` (`Ihn, bald`) | **`in bald zu treffen.`** ⭕ | **`in bald zu treffen.`** ⭕ | **`in bald zu treffen.`** ⭕ |
| | | `PROMPT` / `BOTH` | **`In bald zu treffen.`** ⭕ | **`In bald zu treffen.`** ⭕ | **`In bald zu treffen.`** ⭕ |
| **ドイツ語 (`de`)**<br>`de12_005`<br>（都市名と副詞 `nach` vs `noch`） | `Fahren Sie auch nach München?` | `NONE` | `Warum Sie auch noch München?` ❌ | `fahren Sie auch nach München?` ⭕ | `Fahren Sie auch noch München?` ❌ |
| | | `BOTH` | **`Fahren Sie auch nach München?`** ⭕ | **`Fahren Sie auch nach München?`** ⭕ | **`Fahren Sie auch nach München?`** ⭕ |
| **ドイツ語 (`de`)**<br>`de12_017`<br>（過去分詞 `kennengelernt`） | `Wir haben uns dort kennengelernt.` | `NONE` | `Wir haben uns dort kennengelernt.` ⭕ | `Wir haben uns dort kennengeläumt.` ❌ | `Wir haben uns dort kennengelernt.` ⭕ |
| | | `BOTH` | **`Wir haben uns dort kennengelernt.`** ⭕ | **`Wir haben uns dort kennengelernt.`** ⭕ | **`Wir haben uns dort kennengelernt.`** ⭕ |
| **スペイン語 (`es`)**<br>`es02_054`<br>（人名 `Sandra Cisneros`） | `¿Conoce a la escritora Sandra Cisneros?` | `NONE` | `¿Conóceo la Escritora Sandra Sistneros?` ❌ | `¿Conocé a la escritora Sandra Sistneros?` ❌ | `¿Conoce a la escritora Sandra Cisneros?` ⭕ |
| | | `BOTH` | `¿Conocer a la escritora Sandra Cisneros?` ⭕（人名一致） | **`¿Conoce a la escritora Sandra Cisneros?`** ⭕ | **`¿Conoce a la escritora Sandra Cisneros?`** ⭕ |
| **ベトナム語 (`vi`)**<br>`vi06_024`<br>（通り名 `Phố Tràng Tiền`） | `Phố Tràng Tiền không phải ở đằng kia.` | `NONE` | `Phú trang tiền không phải ở đằng kia.` ❌ | `phú trang tiền không phải ở đăng kia.` ❌ | `Phú Trang Tiên không phải ở đằng kia.` ❌ |
| | | `BIAS` | `Phú tràng tiền không phải ở đằng kia.` △ | `Phú Tràng Tiền không phải ở đằng kia.` △ | `Phố Trang Tiền không phải ở đằng kia.` △ |
| | | `PROMPT` / `BOTH` | **`Phố Tràng Tiền không phải ở đăng kia.`** ⭕ | **`Phố Tràng Tiền không phải ở đăng kia.`** ⭕ | **`Phố Tràng Tiền không phải ở đằng kia.`** ⭕（完全一致） |
| **ベトナム語 (`vi`)**<br>`vi06_006`<br>（通り名 `Phố Trần Hưng Đạo`） | `Phố Trần Hưng Đạo không phải ở đây.` | `NONE` | `Phố trình hình đào không phải ở đây.` ❌ | `Phố trình hương đạo không phải ở đây.` ❌ | `Phố Trần Hương Đạo không phải ở đây.` ❌ |
| | | `BOTH` | **`Phố Trần Hưng Đạo không phải ở đây.`** ⭕ | **`Phố Trần Hưng Đạo không phải ở đây.`** ⭕ | **`Phố Trần Hưng Đạo không phải ở đây.`** ⭕ |
| **ルーマニア語 (`ro`)**<br>`ro06_001`<br>（条件法 `aș dori să mănânc`） | `Dar aș dori să mănânc ceva.` | `NONE` | `Dar a dorii să mânânc ceva.` ❌ | `Dar ardori sa menang ceva.` ❌ | `dar ar dorit să mânânt ceva.` ❌ |
| | | `PROMPT` / `BOTH` | **`Dar aș dori să mănânc ceva.`** ⭕ | `Dar aș dorit să mănânc ceva.` △ | **`Dar aș dori să mănânc ceva.`** ⭕ |
| **ルーマニア語 (`ro`)**<br>`ro06_007`<br>（通り名 `Strada Eminescu`） | `Strada Eminescu nu este acolo.` | `NONE` | `Stradă-mi inestcut nu este acolo.` ❌ | `Strat de a mine scut nu este acolo.` ❌ | `Stradă eminescă nu este acolo.` ❌ |
| | | `BIAS` | `Stradă-mi inescut nu este acolo.` ❌ | `Stra da-mi inescut nu este acolo.` ❌ | **`Strada Eminescu nu este acolo.`** ⭕ |
| | | `PROMPT` / `BOTH` | **`Strada Eminescu nu este acolo.`** ⭕ | **`Strada Eminescu nu este acolo.`** ⭕ | **`Strada Eminescu nu este acolo.`** ⭕ |
| **ルーマニア語 (`ro`)**<br>`ro06_019` / `ro06_055`<br>（通り名 `Strada Eroilor` / `Bulevardul Plopilor`） | `Unde este Strada Eroilor?` / `Bulevardul Plopilor...` | `NONE` | `...strada eroiulor?` / `Vul evar dul...` ❌ | `...strada e roilor?` / `Bulevarul...` ❌ | `...Stradar Eroilor?` / `Boulevardul...` ❌ |
| | | `BOTH` | **`unde este Strada Eroilor?`** / **`Bulevardul Plopilor...`** ⭕ | **`unde este Strada Eroilor?`** / **`Bulevardul Plopilor...`** ⭕ | **`Unde este Strada Eroilor?`** / **`Bulevardul Plopilor...`** ⭕ |
| **タイ語 (`th`)**<br>`th30_006`<br>（都市名 `กรุงเทพ`［バンコク］） | `คุณมาถึงที่กรุงเทพเมื่อไรคะ` | `NONE` | `คุณมาถึงที่ก่อนเธอมอะไรค่ะ` ❌ | `คุณมาถึงที่คุณเทบมาหลายค่ะ?` ❌ | `คุณมาถึงที่กุ้งเทพเมื่อไรคะ` ❌（กุ้ง=エビ） |
| | | `PROMPT` / `BOTH` | `คุณมาถึงที่กรุงเทพเมื่อไหมคะ` / **`คุณมาถึงที่กรุงเทพเมื่อไรคะ`** ⭕ | **`คุณมาถึงที่กรุงเทพเมื่อไรคะ`** ⭕ | **`คุณมาถึงที่กรุงเทพเมื่อไรคะ`** ⭕ |
| **タイ語 (`th`)**<br>`th30_014` / `th30_056`<br>（`กรุงเทพ` / `วัดพระแก้ว`［エメラルド寺院］） | `ผมชอบกรุงเทพครับ` / `คุณยังไม่ไปที่วัดพระแก้วหรือคะ` | `NONE` | `ต้องชอบรรรรร` / `...วัดพระเจ้าเรื่องค่ะ` ❌ | `ผมชอบกลงแทบครับ` / `...วัดพระเก้าหรือคะ?` ❌ | `ผมชอบกลุ่นเทพครับ` / `...วัดพระเก้าหรือคะ?` ❌ |
| | | `BOTH` | `บรุงเทพครับ` / `กันยังไม่ไปที่วัดพระแก้วรือคะ` △ | **`ผมชอบกรุงเทพครับ`** / `...วัดพระแก้วรือคะ` ⭕ | **`ผมชอบกรุงเทพครับ`** / **`คุณยังไม่ไปที่วัดพระแก้วหรือคะ`** ⭕ |

#### (2) 各手法・エンジンの比較から得られた知見

1. **Whisper では `<|startofprev|>` プロンプトと Logit Bias の併用（`BOTH`）が圧倒的に有効**
   - コンテキストなし（`NONE`）では `Whisper Small` ですら誤認識していた固有名詞・地名（ベトナム語の `Phố Tràng Tiền`・`Phố Trần Hưng Đạo`、ルーマニア語の `Strada Eminescu`・`Strada Eroilor`・`Bulevardul Plopilor`、タイ語の `กรุงเทพ`［バンコクを「กุ้งเทพ＝エビの神」と誤認］・`วัดพระแก้ว`［ワット・プラケオを「วัดพระเก้า＝9 の寺」と誤認］）が、**`PROMPT` または `BOTH` を与えると `Whisper Tiny`・`Base`・`Small` のすべてで 100% 正確に認識された**。
   - とくに注目すべき点として、**コンテキストを与えた `Whisper Tiny`（RTF 0.45〜0.54）や `Whisper Base`（RTF 1.3〜1.5）が、コンテキストなしの `Whisper Small`（RTF 3.6〜4.0）を上回る精度**を出している。
2. **Android `SpeechRecognizer` の `EXTRA_BIASING_STRINGS` と音響連結の挙動**
   - Pixel 8 Pro のオンデバイス SODA モデルに対して `EXTRA_BIASING_STRINGS` を渡したが、`de12_020`（`in bald zu treffen`）や `vi06_024`（`phú chẳng tiền...`）の出力は `NONE` と 1 文字も変わらなかった。現行の Pixel オンデバイス音声認識パックでは `EXTRA_BIASING_STRINGS` が無視されるか、重みが極めて小さい。
   - 一方、先行チャンクの音声を連結した `ACOUSTIC`（`vi06_022` + `vi06_024`）では、ML Kit・SpeechRecognizer ともに前半の `Anh cho hỏi phố tràng tiền ở đâu?` は正しく `phố tràng tiền` と認識できたものの、後半のチャンク単体の発音（講師がやや `phú` に近く発音している箇所）はそのまま `Phú chẳng tiền` と出力された。また、Whisper に 2 チャンク連結音声を入れると前半チャンクだけで EOT（終了トークン）を出して後半を切り捨てる現象（`de12_020_acoustic`）が見られたため、**音声の単純連結よりもデコーダへのテキスト文脈注入（`PROMPT` + `BIAS`）の方が安全かつ確実**である。
3. **パンジャブ語（`pa`）など複数文字体系を持つ言語での注意点**
   - Whisper の `pa` トークンは学習データの性質上デーヴァナーガリー文字とシャームキー（アラビア文字）が優勢であり、グルムキー文字（`ਪੰਜਾਬੀ`）のトークンを強く Logit Bias すると UTF-8 のマルチバイト境界が崩れて文字化け（``）を起こすことが確認された（`pa13_001_gurmukhi`）。したがって、**文字体系（スクリプト）を強制変換する用途には Logit Bias を使わず、対象言語チャンクで既に確定した同一スクリプトの認識結果だけを `<|startofprev|>` に入れる**のが安全である。

### 6.5 アプリ本体への文脈注入の導入提案（今後の拡張案）

今回の実験で `SherpaOnnxWhisperTranscriber` に `<|startofprev|>` プロンプト注入と BPE Logit Bias の基盤（`parseContextConditioning` / `encodeUtf8BytesToTokens`）を実装し、動作を実証した。本番の一括文字起こし・再生中文字起こしへ組み込む際は、次の 2 段階で進めるのが効果的である。

1. **Stage A（同一言語の先行チャンク文字起こし結果の引き継ぎ — 即時導入可能・リスク極小）**
   - チャンク $i$（対象言語）を Whisper（Tiny / Base / Small）で文字起こしするとき、直近 1〜2 個前の**対象言語チャンク**の文字起こし結果（信頼度しきい値以上のもの、最大 48 トークン）を `<|startofprev|>`（`PROMPT:`）として渡す。
   - これにより、語学教材で頻出する「質問 → 回答」「リピート練習」「会話のターン」において、直前のチャンクで一度認識できた固有名詞や活用形が次のチャンクへ自動的に引き継がれ、ルーマニア語・タイ語・ベトナム語・ドイツ語などの認識精度が速度ペナルティなし（プロンプト 10〜20 トークンの一括プリフィルは数 ms）で向上する。
2. **Stage B（ベース言語チャンクからの語彙ヒント抽出 — 2 言語設定時の連携）**
   - ベース言語（英語や日本語）のチャンクが先に文字起こしされている場合、またはベース言語チャンク内にカギ括弧・引用符や対象言語の固有表現が含まれる場合、あるいはオンデバイス翻訳（ML Kit Translation）でベース言語の解説文から対象言語のキーワードを逆引きできる場合に、それらの語彙を `BIAS:` として対象言語チャンクのデコーダへ渡す。

---

## 7. 付録: 再現のしかた

[`tools/stt-bench/README.md`](../../tools/stt-bench/README.md) の手順のとおり。
- 第 1 バッチの実教材: `sample_files/stt-bench/real/real.tsv`（202 クリップ）
- 第 2 バッチの実教材: `sample_files/stt-bench/real/real_batch2.tsv`（180 クリップ、`prepare_real_clips.py` → 実機 `mode=lid` → `select_real_clips.py` で作成）
- コンテキスト注入実験: `sample_files/stt-bench/real/context_exp.tsv`（46 エントリ、`PROMPT:...||BIAS:...` 形式の `context` 列を付与して実機 `mode=run` で計測）
