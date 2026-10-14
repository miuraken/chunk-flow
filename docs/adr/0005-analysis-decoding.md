# 0005. 解析のデコード（抽出器・時間軸・MediaCodec の方式・実行方式）

- 状態: 承認済み
- 日付: 2026-10-14
- 決めた人: lead（スパイク S3 の結果をもとに）
- 根拠: [docs/spikes/S3-audio-analysis.md](../spikes/S3-audio-analysis.md)（エミュレータ Pixel_8_Pro、android-36 での計測）
- 関係する節: 設計書 §7.3.1、§7.3.4、§3（解析性能・メモリ・時刻精度）

## 背景

§7.3.1 は、解析のデコードに `MediaExtractor` と `media3-inspector` の `MediaExtractorCompat` のどちらを使うかを S3 で決める、としていた。
あわせて、解析の時間軸を ExoPlayer の再生位置と 20ms 未満で一致させる方法と、MediaCodec の使い方（同期 / 非同期）も決める必要がある。

## 決定

1. **抽出器は `MediaExtractorCompat`（media3-inspector）を使う。**
   - ExoPlayer と同じ Media3 の抽出器なので、エンコーダ遅延（ギャップレス）の扱いが ExoPlayer と同じになる。
     S3 では、出力サンプル数の累計から求めた時刻と ExoPlayer の再生位置の差が、全形式（MP3 / AAC m4a / ADTS / Opus / Vorbis / FLAC / WAV）で 0.01ms 以下だった。
   - プラットフォームの `MediaExtractor` は、LAME ヘッダ付きの MP3 でエンコーダ遅延を 576 サンプルとして渡すため、ExoPlayer（1,105 サンプル）と
     529 サンプル（44.1kHz で 12ms、22.05kHz で 24ms）ずれた。目標（20ms 未満）を 22.05kHz の MP3 で満たさない。
   - MP3 は、再生と同じく ADR 0006 の DataSource（ヘッダを隠す）を通して読む（`MediaExtractorCompat` に `DataSource.Factory` を渡す）。
     再生と解析で同じバイト列を同じ抽出器で読むことで、時間軸を一致させる。**S3 の D6 で、ヘッダを隠した `MediaExtractorCompat` と同じ DataSource の ExoPlayer の時刻が全ファイルで一致することを確かめた**（差 0、30 分でドリフトなし。エミュレータ）。
   - API: Media3 1.11.1 の `MediaExtractorCompat(ExtractorsFactory, DataSource.Factory)`（ほかに `MediaExtractorCompat(ProgressiveMediaExtractor, DataSource.Factory)`）。クラス全体が `@UnstableApi` なので `@OptIn(UnstableApi::class)` が要る。Media3 を上げるときに API の存続を確かめる。
2. **時刻は「デコーダの出力サンプル数の累計 ÷ サンプルレート」で求め、自前ではトリミングしない。**
   - `MediaExtractorCompat` の `MediaFormat`（`encoder-delay` / `encoder-padding` を含む）をそのまま `MediaCodec.configure()` に渡すと、
     Codec2 のデコーダが先頭と末尾を自分で削る（S3 で m4a・MP3 を確認）。自前でさらに削ると削りすぎになった。
   - **出力バッファの `presentationTimeUs`（PTS）は使わない**（形式・抽出器によって 6〜55ms ずれた）。
   - 16kHz へのリサンプル後の累計（解析で使う時間軸）は、ネイティブのレートの累計との差が 0.04ms 以内だった（`core:audio` の `StreamingResampler`）。
3. **MediaCodec は当面、同期モードで使う**（§7.3.1 のまま）。
   - 非同期モード（`setCallback`）はエミュレータで同期モードの 1.3〜1.8 倍速かったが、1 回だけ出力が 1,008 フレーム欠けた（原因は未確認。
     スパイクのコードの不具合の可能性もある）。欠けると以降の時刻がずれるため、原因がわかるまでは採らない。
   - 実機で同期 / 非同期の速度と欠けの再現を確かめ、目標（30 分を 60 秒以内）に届かない場合に非同期モードを再評価する。
     採る場合は「出力フレーム数と、抽出器が示す長さの整合の検査」を必ず入れる。
4. **`MediaExtractorCompat` で FLAC を読むと EOS にならない問題を回避する。**
   - 最後のサンプルが繰り返し返され、`advance()` が true を返し続けた（プラットフォームの抽出器と ExoPlayer の再生では起きない）。
   - 回避策: **サンプルの時刻が進まない読み出しが続いたら（例: 8 回）EOS とみなす**。それでも末尾に余分な出力（S3 では 0.18 秒）が出るので、
     出力は抽出器が示す長さ（`durationUs`）で打ち切る。Media3 の既知の不具合かは未確認（報告は M2 の実装時に検討する）。
5. **解析は、前面で開始したフォアグラウンドサービス（長時間ワーカーの `setForeground()`）の中で動かす**（§7.3.4 のとおり。変更なし）。
   - S3 で、計測アプリが背面に回るとキャッシュされたアプリとして凍結され、解析が止まった（前面に戻るまで進まない）。
     フォアグラウンドサービス（`mediaProcessing`）の中では止まらなかった。

## 理由

- 時間軸の一致（§3 の 20ms 未満）を、補正値ではなく「同じ抽出器・同じデコーダの規則」で満たせる。形式ごとの補正表を持つ必要がない。
- メモリ: 30 分・60 分（実際の教材を含む）でも追加のヒープは Java +8〜16MB、PSS +8〜23MB で、目標（64MB 未満）を満たした。ストリーミング処理の方針（§7.3.1）は妥当。
- 時間のほとんどはデコーダ（`media.swcodec` プロセス）の待ちで、解析（モノラル化 → 16kHz → ビン・特徴量）は全体の約 5% だった。

## 実機待ち・未確認

- **速度の目標（30 分を 60 秒以内、Pixel 8 クラス）を満たすかは未確認**。エミュレータでは同期モードで 78〜89 秒（負荷の軽い時間帯）、
  ホストの負荷で 4〜5 倍ばらついたので、判断に使えない。
- 529 サンプルの差・FLAC の EOS・非同期モードの欠けが、実機のデコーダでも同じかは未確認。
- ヘッダを隠した MP3 での解析と再生の一致は、エミュレータでは確認済み（D6）。実機のデコーダでも同じかは実機待ち。
- 一覧は [docs/spikes/README.md](../spikes/README.md) の「実機待ちの計測」。
