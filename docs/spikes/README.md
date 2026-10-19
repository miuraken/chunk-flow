# スパイク（技術検証）

Phase 0 のスパイクの報告と、そこから決めた ADR の対応です。報告（`S*-*.md`）は各担当、この README は lead が書きます。

| スパイク | 担当 | 報告 | 決めた ADR |
| --- | --- | --- | --- |
| S1 PC の文字起こしパイプライン | transcriber | [S1-pipeline-prototype.md](S1-pipeline-prototype.md)（合成音声）、[S1-real-materials.md](S1-real-materials.md)（実際の教材） | [0009](../adr/0009-transcriber-parameters.md) |
| S2 再生エンジン | playback | [S2-playback.md](S2-playback.md) | [0006](../adr/0006-mp3-seeking.md)、[0007](../adr/0007-chunk-boundary-timing.md)、[0008](../adr/0008-time-axis-alignment.md) |
| S3 デコードと時間軸 | audio-analysis | [S3-audio-analysis.md](S3-audio-analysis.md)、[S3-onnx-whisper-lid.md](S3-onnx-whisper-lid.md)（言語判定の推論の ONNX Runtime 化。実機の結果は ADR 0010 §2.6） | [0005](../adr/0005-analysis-decoding.md)、[0008](../adr/0008-time-axis-alignment.md)、[0010](../adr/0010-on-device-speech-and-whisper-lid.md) |

S2・S3 の数値はすべてエミュレータ（Pixel_8_Pro、android-36）で測ったものです。時間軸（シークの誤差、クリックの時刻）とメモリは
出力のタイミングに依存しない測り方ですが、速度とタイミング（ループの行き過ぎ、自動ポーズの遅れ）は参考値です。

## 実機待ち・人間の作業待ちの計測

Pixel 実機（Pixel 8 クラス）がつながったら行う計測と、人間の作業が必要な確認の一覧です。手順は各報告の節を参照してください。
結果が出たら、関係する ADR を更新（または置き換え）し、設計書に反映します。

| # | 項目 | 何を確かめるか | 手順 | 関係する ADR・節 |
| --- | --- | --- | --- | --- |
| D1 | 解析の速度 | 30 分のデコード + 解析が 60 秒以内か（同期 / 非同期、Platform / Compat）。実際の教材（30 分の MP3）での時間とメモリ | S3 §5 | 0005、§3 |
| D2 | 非同期モードの出力の欠け | 1,008 フレームの欠けが再現するか（再現しなければ非同期モードを再評価） | S3 §5 | 0005 |
| D3 | デコーダの遅延 | MP3 の 529 サンプルの差が実機のデコーダでも同じか。48kHz・16kHz の MP3 | S3 §5 | 0005、0008 |
| D4 | FLAC の EOS | `MediaExtractorCompat` + FLAC で EOS にならない問題が実機でも起きるか | S3 §5 | 0005 |
| D5 | MP3 のシーク | ヘッダを隠す DataSource で誤差が一定か。最初の遠いシークの時間（10 分・30 分・60 分） | S2 §7 の 2 | 0006 |
| D6 | ヘッダを隠した MP3 の時間軸 | **エミュレータで完了**（2026-10-14）: 解析と再生は一致、ffmpeg との差は MPEG-1 で +1,728、MPEG-2 / 2.5 で +1,152、ヘッダなしで −529 サンプル。**実機のデコーダでの値は実機待ち**（同じ手順で測れる） | S3 の D6 節 | 0005、0006、0008 |
| D7 | ループの行き過ぎ | `dynamicScheduling` の有無の差、推定値と録音で数える実際の値（50ms 未満か） | S2 §7 の 3〜4 | 0007、§11 の M2 |
| D8 | 電池 | `dynamicScheduling=false` での再生中の電池消費 | 未作成（M1 の実機テストで測る） | 0007 |
| D9 | 自動ポーズ（画面オフ） | CPU スリープによる再開の遅れと、`PARTIAL_WAKE_LOCK` の効果。Doze | S2 §7 の 5 | §7.2.4 |
| D10 | 前 / 次ボタン | Bluetooth イヤホン（または有線のリモコン）の前 / 次でチャンクを移動できるか | S2 §7 の 6 | 0007、§7.2.3 |
| D11 | AAC のシーク後の誤差 | +23.2ms（シーク後だけ）が実機でも同じか | S2 §7 の 2 | 0008 |
| D12 | 耳で聞こえる位置 | AudioTrack の出力遅延を含めた位置とのずれ | 未作成 | 0008 |
| H3 | 言語の確認表（人間） | 実際の教材 3 本の正解率（1 秒以上 / 未満）。平滑化・ヒンディー語の判断の材料 | S1-real-materials §5 | 0009 |
| H4 | NVIDIA の PC（人間） | Linux / WSL2 での `uv sync` と pytest、faster-whisper の RTF、mlx との結果の差、turbo の `yue` の確率 | S1-pipeline-prototype §6 | 0009 |
