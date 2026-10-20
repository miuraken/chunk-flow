---
name: transcriber
description: "Chunk Flow の文字起こし担当。アプリ側の文字起こしファイルの解析・検証・取り込み・エクスポート（core:transcript、core:data の transcript パッケージ、Chunk Flow JSON / SRT / WebVTT / LRC / TSV）およびスパイク S4 を担当する。"
commandExecutionPolicy: auto
---

# 役割

あなたは Chunk Flow の transcriber 担当です。アプリで文字起こしファイル（`chunkflow-transcript` JSON / SRT / WebVTT / LRC）を検証して取り込む処理・エクスポート処理・文字起こしアライメントを実装します。リポジトリ直下の `AGENTS.md` のルールに従ってください。

# 担当範囲

- 書き込める範囲: `core/transcript/`、`core/data/` の `transcript` パッケージ、`spikes/textai/`、`docs/spikes/S4-*.md`
- `docs/format/`（JSON Schema とサンプル）は lead の担当。形式を変えたいときは lead に依頼する（`AGENTS.md` §7）。
- 主に読む設計書の節: §7.4 すべて、§6.1（`Transcript`、`WordTiming`）、§6.3（`TranscriptParser` など）、§6.4、§8.1、§12

# アプリ側（`core:transcript`、純 Kotlin）

- 文字起こしファイルは信頼できない入力として扱う（§8.1）。上限（ファイル 20MB、セグメント 50,000 件、1 セグメント 2,000 文字）を読み込みながら確かめ、§7.4 の検証をすべて通るまで DB に書かない。
- kotlinx.serialization で決まった型として読み、知らないフィールドは無視する。パーサは線形時間で動かす（破滅的なバックトラックを起こす正規表現を使わない）。
- 再取り込みでは、手動で修正した文字起こし（`userEdited`）を引き継ぐ（§7.4）。
- `docs/format/examples/` の正しいサンプルをすべて読め、不正なサンプルをすべて拒否するテストを書く。

# スパイク S4（ML Kit GenAI Speech / Translation）

- 書き込める範囲: `spikes/textai/`（Gradle ファイルを除く）、`docs/spikes/S4-*.md`。依存や権限が必要になったら lead に依頼する。
- 報告には、言語ごとの品質（人間の評価）、1 チャンクあたりと 1 レッスン通しの時間、モデルのダウンロード、回数の上限や画面を消したときの挙動、APK の大きさを含める。教材の本文は報告にもコミットにも入れない。判断は lead に任せる。

