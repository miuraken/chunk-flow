# 文字起こしの形式 `chunkflow-transcript` v1

PC ツール（`tools/transcriber/`）とアプリ（`core:transcript`）の間の契約です（設計書 §7.4.3、D13）。
アプリと PC ツールが共有するのは、このディレクトリの JSON Schema とサンプルだけです（§5.2）。
このディレクトリは lead の担当です。変更は AGENTS.md §7（設計書 §10.3）の手順で依頼してください。

| ファイル | 内容 |
| --- | --- |
| [`chunkflow-transcript-v1.schema.json`](chunkflow-transcript-v1.schema.json) | JSON Schema（draft 2020-12） |
| [`examples/*.chunkflow.json`](examples/) | 正しいサンプル。読み込み・Schema・意味の検証のすべてに合格する |
| [`examples/invalid/*.chunkflow.json`](examples/invalid/) | 不正なサンプル。1 ファイルに違反は 1 つだけ |
| [`examples/invalid/expectations.json`](examples/invalid/expectations.json) | 不正なサンプルごとの、拒否される段階と `TranscriptError` |

サンプルの文は、このリポジトリのために作った短い例文です（実際の教材は含めない）。`audio.sha256` はダミーの値です。

## 検証の順序

アプリ（`TranscriptParser`）も PC ツールも、次の順に検証します。前の段階で拒否したら、後の段階は行いません。

| 段階 | 内容 | 失敗したときの `TranscriptError` |
| --- | --- | --- |
| 1. 読み込み（parse） | ファイルサイズ 20MB 以下（読みながら確かめる）、UTF-8 として正しい、入れ子の深さ 64 以下、JSON の構文 | サイズ・深さは `TOO_LARGE`、それ以外は `SCHEMA_VIOLATION` |
| 2. 形式と版 | `format` が `"chunkflow-transcript"`、`version` が `1`（Schema の一部だが、先に見て種別を分ける） | `UNKNOWN_FORMAT` / `UNSUPPORTED_VERSION` |
| 3. JSON Schema（schema） | 型・必須・範囲・パターン・件数と文字数の上限 | 件数・文字数の上限は `TOO_LARGE`、負の時刻は `TIME_OUT_OF_RANGE`、それ以外は `SCHEMA_VIOLATION` |
| 4. 意味の検証（semantic） | 下の S1〜S6 | S4 は `TIME_OUT_OF_RANGE`、それ以外は `SCHEMA_VIOLATION` |

- 知らないフィールドは無視します（フィールドの追加は互換）。そのため Schema では `additionalProperties` を制限していません。ただし、知らないフィールドの中身にも段階 1 の上限（サイズと入れ子の深さ）は適用します。
- 上限（§8.1）: ファイル 20MB、セグメント 50,000 件、1 セグメントのテキスト 2,000 文字。**文字数は Unicode のコードポイントで数えます**（JSON Schema の `maxLength` と同じ。Kotlin の `String.length` は UTF-16 の単位なので、`codePointCount` を使う）。
- `generator.createdAt` の `format: date-time` は、JSON Schema では注釈扱いです。同じ規則を `pattern` でも表しています。

## 意味の検証

JSON Schema だけでは表せない規則です。`.github/scripts/validate_transcript_examples.py` の `validate_semantics` が参照実装です。

| # | 規則 |
| --- | --- |
| S1 | `languages.base` があるとき、`languages.target` と異なる |
| S2 | 各セグメントで `startMs < endMs` |
| S3 | セグメントは `startMs` の順に並び、重ならない（前のセグメントの `endMs` ≤ 次の `startMs`。接していてもよい） |
| S4 | 各セグメントの `endMs` ≤ `audio.durationMs`（取り込み時に、実際の音声の長さを超えた分を切り詰めるのは別の処理。§7.4.6） |
| S5 | `lang` は `languages.target` か `languages.base` と完全に一致するか、`null`（省略も `null`） |
| S6 | `words` の各単語で `startMs < endMs`、セグメントの範囲内、`startMs` の順で重ならない |

検証ではなく、書き手（PC ツール）が守る規則:

- テキストは NFC に正規化し、マークアップを含めない。読み手（アプリ）は NFC でないテキストを拒否せず、NFC に正規化して取り込む。
- `text` の字体: 中国語（`zh`）は簡体字、広東語（`yue`）は香港式の繁体字の口語。もう一方の字体は `textVariants`（`Hans` / `Hant`）に入れる。単語には `textVariants` を持たせない。
- `lang` と `langProb` は、判定した場合は常に書く（省略は「判定していない」の意味で `null` と同じに扱う）。
- 単一言語の教材では `languages.base` を省略する（`null` も可）。

## 正しいサンプル

| ファイル | 内容 |
| --- | --- |
| `ja-ko` | 日本語で学ぶ韓国語。words なし |
| `ja-zh-hant` | 日本語で学ぶ中国語。簡体字の本文 + `Hant`、words あり |
| `en-yue-hans` | 英語で学ぶ広東語。香港式の繁体字の口語 + `Hans`、words あり |
| `en-hi` / `en-ru` / `en-vi` / `en-el` | デーヴァナーガリー / キリル文字 / ベトナム語の声調記号（NFC）/ ギリシャ文字 |
| `es-single-language` | 単一言語（`base` を省略）。words あり、`langProb` なし |
| `minimal-null-and-unknown-fields` | `base: null`、`lang` の省略と `null`、知らないフィールド（前方互換）、セグメントが接している |
| `empty-segments` | セグメントが 0 件（無音だけの音声など） |

## 不正なサンプル

拒否される段階ごとに分けます（詳しくは `expectations.json`）。

| 段階 | ファイル | 違反 | `TranscriptError` |
| --- | --- | --- | --- |
| parse | `invalid-utf8` | UTF-8 として不正 | `SCHEMA_VIOLATION` |
| parse | `truncated-json` | JSON が途中で切れている | `SCHEMA_VIOLATION` |
| parse | `deep-nesting` | 知らないフィールドの入れ子が 1,000 段 | `TOO_LARGE` |
| schema | `wrong-format-name` | `format` の誤り | `UNKNOWN_FORMAT` |
| schema | `unsupported-version` | `version: 2` | `UNSUPPORTED_VERSION` |
| schema | `negative-time` | 負の時刻 | `TIME_OUT_OF_RANGE` |
| schema | `non-integer-time` | 時刻が整数でない | `SCHEMA_VIOLATION` |
| schema | `text-too-long` | テキストが 2,001 文字 | `TOO_LARGE` |
| schema | `empty-text` | テキストが空 | `SCHEMA_VIOLATION` |
| schema | `missing-sha256` / `uppercase-sha256` | `audio.sha256` がない / 大文字 | `SCHEMA_VIOLATION` |
| schema | `path-in-file-name` | `audio.fileName` にパス区切り | `SCHEMA_VIOLATION` |
| schema | `lang-prob-out-of-range` | `langProb` が 1.5 | `SCHEMA_VIOLATION` |
| schema | `unknown-text-variant-key` | `textVariants` のキーが `Latn` | `SCHEMA_VIOLATION` |
| schema | `invalid-language-tag` | 言語タグが `Korean` | `SCHEMA_VIOLATION` |
| schema | `missing-segments` | `segments` がない | `SCHEMA_VIOLATION` |
| semantic | `same-target-and-base` | S1 | `SCHEMA_VIOLATION` |
| semantic | `start-equals-end` / `start-after-end` | S2 | `SCHEMA_VIOLATION` |
| semantic | `overlapping-segments` / `unsorted-segments` | S3 | `SCHEMA_VIOLATION` |
| semantic | `segment-exceeds-duration` | S4 | `TIME_OUT_OF_RANGE` |
| semantic | `lang-not-in-pair` | S5 | `SCHEMA_VIOLATION` |
| semantic | `word-outside-segment` | S6 | `SCHEMA_VIOLATION` |

サイズの上限を超えるファイル（20MB 超）とセグメント数の上限を超えるファイル（50,001 件）は、大きすぎるのでリポジトリに置きません。各テストで生成してください。

## 確かめ方

```sh
uv run --no-project --with jsonschema python .github/scripts/validate_transcript_examples.py
```

CI（`.github/workflows/ci.yml`）でも実行します。アプリのパーサのテスト（core:transcript）と PC ツールのテスト（tools/transcriber）も、このディレクトリのサンプルを読みます（§9）。
