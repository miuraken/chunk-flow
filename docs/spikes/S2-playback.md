# S2: 再生エンジンの技術検証（playback）

- 担当: playback
- 日付: 2026-10-14
- ブランチ: `agent/playback/s2-spike`（main 563c695 から）
- 対象: Media3 1.11.1、エミュレータ `Pixel_8_Pro`（android-36、arm64、`sdk_gphone64_arm64`）
- コード: `spikes/playback/`（計測アプリ）。テスト音源は `spikes/playback/testdata/generate_testdata.py` で合成する（生成物はコミットしない）

> [!IMPORTANT]
> **この報告の数値はすべてエミュレータで測ったもの**です。エミュレータの音声出力（AudioTrack の位置・タイムスタンプ）と CPU のスリープは実機と違うため、
> タイミング（ループの行き過ぎ、自動ポーズの遅れ）は**参考値**です。シークの誤差（時間軸のずれ）は、ExoPlayer とソフトウェアのデコーダ（`c2.android.*`）の
> 動作で決まり、出力のタイミングに依存しない測り方をしているので、実機でも同じになる見込みですが、**実機での確認は未確認**です。
> 実機待ちの項目は §7 にまとめました。

## 0. 結論の要約

| 項目 | 結果（エミュレータ） | 設計への提案 |
| --- | --- | --- |
| 1. PlayerMessage によるループ | **既定の設定では境界の検出が 30〜200ms 遅れる**（Media3 1.11.1 は `dynamicScheduling` が既定で有効で、再生スレッドの起床間隔をレンダラが決めるため）。`experimentalSetDynamicSchedulingEnabled(false)` で **行き過ぎは平均 1〜8ms（最大 24〜67ms）** に下がる。`ClippingConfiguration` + `REPEAT_MODE_ONE` は行き過ぎ 0 | ExoPlayer を `experimentalSetDynamicSchedulingEnabled(false)` で作る（§7.2.2 に追記）。電池への影響は実機で確認 |
| 自分のシーク / ユーザーのシークの区別 | `seekTo()` の呼び出しの中で同期的に `onPositionDiscontinuity` が届く（8/8 回）。件数で数える方式で正しく区別できた | §7.2.4 の方式でよい（フラグではなく件数にする） |
| `setDeleteAfterDelivery(false)` の A-B | 位置を通るたびに再配信される。ユーザーのシークの後も生きている。精度は予約し直す方式と同等 | A-B は使い回す方式で単純にできる |
| 2. MP3 のシーク | **CBR はほぼ正確、VBR + Xing（TOC）は最大 2.5 秒、ヘッダのない VBR は長さを 120 秒と誤認しシークが破綻**。`FLAG_ENABLE_INDEX_SEEKING` は 3 種とも**効果なし**（TOC がない VBR でだけ効く） | **(c) 案の変形**: 読み出し時に VBR/CBR ヘッダを隠し、長さを「不明」として IndexSeeker を使わせる DataSource（試作 `IndexSeekDataSource`）で、3 種とも誤差が**一定**になった（下記）。ファイルを書き換えないので SHA-256 も変わらない |
| シーク後の一定のずれ | シーク後は MP3 が +13.1ms、AAC が +23.2ms で一定（デコーダの遅延のぶん）。先頭から再生したときとは 25ms ほど違う | 形式ごとの固定の補正値で吸収できる（§7.4.6 の時刻の補正、S3 と合わせて決める） |
| 3. 自動ポーズからの再開 | MediaSessionService 上で動作を確認（遅れ 1〜62ms）。**エミュレータは CPU が眠らない**（睡眠 0ms）ので、WakeLock の有無の差は測れない | 実機待ち（計測の仕組みは用意済み） |
| 前 / 次の再割り当て | `cmd media_session dispatch next/previous` がチャンク移動になった。next は引数 `(C.INDEX_UNSET, C.TIME_UNSET)`、previous は `(0, 0)` で届いた。ロック画面のメディア操作にも前 / 次が出た | §7.2.3 のとおりでよい |

## 1. 計測の仕組み

### 1.1 テスト音源（`testdata/generate_testdata.py`）

- `clicks.wav`: 10 分・モノラル・44.1kHz。無音・小さな正弦波・ノイズ（振幅 0.4 未満）の区間をランダムな長さ（0.3〜4 秒）で並べ、VBR のビットレートを大きく揺らす。
  約 6 秒ごとに 99 個の「クリック」を置く。クリック k は 3ms・3kHz・振幅 0.9 のバースト 2 つで、**間隔 `30 + 7k` ms がクリックの番号**を表す
  （シークが大きく外れても、どのクリックを見たかを取り違えない）。クリックの前後 250ms は無音。
- 真の時刻: 生成した PCM に、端末と同じ検出（振幅 0.5 を超えた最初のサンプル）を当てた値を `manifest.json` に書く。
- 符号化（ffmpeg 8 系 + libmp3lame）:

| ファイル | 作り方 | 備考 |
| --- | --- | --- |
| `cbr.mp3` | `-b:a 128k` | Info（LAME）ヘッダあり |
| `cbr_noheader.mp3` | `-b:a 128k -write_xing 0` | ヘッダなし |
| `vbr_xing.mp3` | `-q:a 4` | Xing ヘッダ（TOC あり）。平均 51kbps |
| `vbr_xing_notoc.mp3` | `vbr_xing.mp3` の Xing の TOC を取り除いたもの | 「VBR ヘッダはあるが TOC がない」場合の確認 |
| `vbr_noheader.mp3` | `-q:a 4 -write_xing 0` | ヘッダのない VBR（ffprobe でも長さが 402 秒と誤認される） |
| `aac.m4a` | `-c:a aac -b:a 96k` | 比較用 |
| `vbr_in_mp4.mp4` | `vbr_xing.mp3` を `-c:a copy -f mp4` | §12 の (a) 案（MP4 への詰め替え）の比較 |
| `loop.m4a` | 30 秒。A=10.000s〜B=12.000s が 440Hz、B から 500ms は 10ms ごとの目盛り（1ms のバースト） | ループの計測用。実機での録音で行き過ぎを数えられる |

### 1.2 「プレイヤーが信じている時刻」で音を取り出す（`measure/AudioProbe.kt`）

- `DefaultRenderersFactory.buildAudioSink()` を上書きし、`DefaultAudioSink` を包む `ProbeAudioSink`（`ForwardingAudioSink`）と、
  `DefaultAudioSink` の中の `TapAudioProcessor`（ギャップレスのトリミングの後、速度変更 Sonic の前）を差し込んだ。
- flush（シーク）の後、最初に AudioSink に届いたバッファの PTS を区間の開始時刻とし、Tap を通る n 番目のサンプルを
  「開始 PTS + n / サンプルレート」とみなす。これは AudioSink が再生位置を計算するのと同じ規則なので、
  **クリックを見つけた位置から求めた時刻 = プレイヤーがそのクリックの時刻だと信じている値**になる。
- **誤差 = プレイヤーが信じている時刻 − 真の時刻**。正なら「プレイヤーの時間軸が遅れている」（例: 10.000s のチャンクへ頭出しすると、実際には 10.000s − 誤差 の音が鳴る）。
- 出力のタイミング（AudioTrack、スピーカー）に依存しないので、エミュレータでも実機と同じ値になる見込み（実機での確認は未確認）。
- 速度変更の前で測るので、速度にかかわらずメディア時刻で測れる。

### 1.3 ループの行き過ぎ（`measure/LoopAccuracyTest.kt`）

境界 B ごとに次を記録した（時刻は `elapsedRealtimeNanos`）。

- **再生スレッドで検出**: 同じ位置 B に、再生スレッド（`player.playbackLooper`）で配信される診断用の `PlayerMessage` を置き、
  配信された時点の AudioSink の推定位置 − B。ExoPlayer が境界に気づくまでの遅れ。
- **配信時**: アプリのスレッドに届いた `PlayerMessage` の中で読んだ `currentPosition` − B。
- **停止時（行き過ぎ）**: その後の AudioSink の flush（シーク）/ pause の時点で、AudioSink が推定する「いま鳴っている位置」− B。
  実時間の行き過ぎ = これ ÷ 速度。
- **再開まで**: flush から、次の区間の最初のバッファが AudioSink に届くまでの実時間（音が途切れる時間の下限）。

「鳴っている位置」は AudioTrack の再生位置とレイテンシの推定に基づく ExoPlayer の推定値で、**スピーカーから実際に出た音ではない**。
実際の行き過ぎは、実機で `loop.m4a` を再生して録音し、B の後の目盛りがいくつ聞こえるかで数える（§5）。

## 2. 結果: PlayerMessage によるループ（S2 の 1）

各条件で 8 回（1 回目を除く 7 回で集計）。単位は ms。「行き過ぎ」はメディア時刻、括弧内は実時間。

### 2.1 既定（`dynamicScheduling` 有効。Media3 1.11.1 の既定）

| 方式 | 速度 | 再生スレッドで検出 平均 | 配信時 平均 | 行き過ぎ 平均 | 行き過ぎ 最大 |
| --- | --- | --- | --- | --- | --- |
| A-B 予約し直し | 0.5 | 33.8 | 45.4 | 46.0（92.0） | 93.0（185.9） |
| A-B 使い回し（deleteAfterDelivery=false） | 0.5 | 67.2 | 66.7 | 73.5（147.1） | 161.0（321.9） |
| B で一時停止 | 0.5 | 86.5 | 104.9 | 106.0（212.0） | 172.1（344.2） |
| A-B 予約し直し | 1.0 | 59.6 | 97.6 | 99.3 | 160.8 |
| A-B 使い回し | 1.0 | 58.2 | 63.9 | 73.4 | 228.8 |
| B で一時停止 | 1.0 | 53.1 | 81.6 | 90.5 | 149.0 |
| A-B 予約し直し | 2.0 | 114.0 | 196.7 | 196.1（98.0） | 364.4（182.2） |
| A-B 使い回し | 2.0 | 40.5 | 61.3 | 118.4（59.2） | 183.1（91.6） |
| B で一時停止 | 2.0 | 123.9 | 176.9 | 199.7（99.9） | 347.4（173.7） |

### 2.2 `experimentalSetDynamicSchedulingEnabled(false)`

| 方式 | 速度 | 再生スレッドで検出 平均 | 配信時 平均 | 行き過ぎ 平均 | 行き過ぎ 最大 | 再開まで 平均 |
| --- | --- | --- | --- | --- | --- | --- |
| A-B 予約し直し | 0.5 | 2.5 | 2.7 | 0.7（1.4） | 2.3（4.6） | 33.8 |
| A-B 使い回し | 0.5 | 2.8 | 4.0 | 3.9（7.8） | 13.6（27.3） | 35.6 |
| B で一時停止 | 0.5 | 2.1 | 2.9 | 4.0（8.0） | 9.2（18.4） | — |
| A-B 予約し直し | 1.0 | 4.6 | 6.4 | 2.1 | 24.8 | 99.3 |
| A-B 使い回し | 1.0 | 7.2 | 9.0 | 5.4 | 24.8 | 41.8 |
| B で一時停止 | 1.0 | 4.5 | 4.4 | 5.9 | 10.7 | — |
| A-B 予約し直し | 2.0 | 14.3 | 18.0 | 8.2（4.1） | 47.1（23.6） | 45.1 |
| A-B 使い回し | 2.0 | 14.5 | 23.1 | 8.2（4.1） | 47.1（23.6） | 37.6 |
| B で一時停止 | 2.0 | 13.0 | 21.0 | 24.7（12.3） | 67.0（33.5） | — |

### 2.3 比較: `ClippingConfiguration`（A〜B を切り出す）+ `REPEAT_MODE_ONE`

速度 0.5 / 1.0 / 2.0 倍で各 5 回繰り返し、B の後ろの目盛りが AudioSink に渡った数は **0**（行き過ぎなし）。シークも flush も起きない。

### 2.4 分かったこと

- **遅れの主因は ExoPlayer の再生スレッドの起床間隔**。「再生スレッドで検出」の時点ですでに数十〜百数十 ms 遅れており、
  アプリのスレッドへの受け渡し（配信時 − 検出）と、シークの実行（配信→停止、ほぼ 0.1〜20ms）の寄与は小さい。
  Media3 1.11.1 の `ExoPlayer.Builder` は `dynamicSchedulingEnabled = true` が既定（javap で確認）で、READY の間は
  レンダラの `getDurationToProgressUs()` に従って起床する（音声のバッファが十分なら長く眠る）。
  `false` にすると従来どおり約 10ms ごとに動き、PlayerMessage の配信の遅れは数 ms になった。
- 2.0 倍では、10ms の起床間隔のあいだに進むメディア時刻が 2 倍なので、メディア時刻の行き過ぎが大きくなる（実時間では 0.5 倍と同程度）。
- `setDeleteAfterDelivery(false)` の 1 件を使い回す A-B は、予約し直す方式と同等の精度で、ユーザーのシークの後も配信が続いた（§2.5）。
- AudioSink の推定位置は、エミュレータでは同じ値が続いたり、検出時より小さくなったりした（推定の粒度・ゆらぎ）。実機の値で判断する。
- 本体の目標（M2: 行き過ぎ 50ms 未満）は、エミュレータの推定値では `dynamicScheduling=false` で満たし、既定では満たさない。

### 2.5 自分のシークとユーザーのシークの区別

- ChunkScheduler の `seekTo()` の中で `onPositionDiscontinuity`（SEEK）が**同期的に**届いた（全条件で 8/8 回）。
  ただし、リスナーの中から `seekTo()` を呼んだ場合は、ListenerSet の仕様で通知が後回しになるため、真偽値のフラグではなく
  **「自分のシークの件数」を数えて、届いた SEEK の分だけ減らす**方式にした（`SpikeChunkScheduler.seekInternal`）。
- 使い回す A-B で、ユーザーのシーク（ChunkScheduler を通さない `seekTo`）を 3 回 + 準備前の `seekTo(A)` を 1 回行い、
  ユーザーのシーク 4 回・自分のシーク 2 回・配信 2 回（期待どおり）。B の後ろへのシークの後に A〜B に戻ると、再び B で戻された。

## 3. 結果: MP3 のシーク精度（S2 の 2）

- 各ファイルで、先頭から再生したときの最初のクリック（基準）と、10 個のクリックの「真の時刻 − 500ms」へのシークを測った。
- ExoPlayer は**読み込み済みの範囲（既定の LoadControl で先読み約 50 秒）の中へのシークは、順に読んだときの正確な時刻で行う**。
  2 回目の計測ではシーク先が前のシーク先から 60 秒以上離れるように順序を変えた（1 回目の結果も同じ傾向）。
- `FLAG_ENABLE_INDEX_SEEKING` の有無で比べた。

| ファイル | フラグ | 長さの認識 | 基準（先頭から） | シーク後の誤差（10 回） | 備考 |
| --- | --- | --- | --- | --- | --- |
| `cbr.mp3`（Info ヘッダ） | なし / INDEX | 599,999ms | −12.0 | −18.4 〜 +13.1（位置に比例して変化） | 両フラグで同じ |
| `cbr_noheader.mp3` | なし / INDEX | 600,032ms | +13.1 | +13.0 〜 +13.1 | 両フラグで同じ |
| `vbr_xing.mp3`（TOC あり） | なし / INDEX | 599,999ms | −12.0 | **−158 〜 +2,474.5** | **フラグは効かない**（TOC が優先） |
| `vbr_xing_notoc.mp3` | なし | 599,999ms | −12.0 | シーク不可（`isCurrentMediaItemSeekable=false`、先頭に戻る） | |
| `vbr_xing_notoc.mp3` | INDEX | 599,999ms | −12.0 | **+13.1（10 回とも）** | IndexSeeker。初回バッファまで最大 152ms |
| `vbr_noheader.mp3` | なし / INDEX | **120,551ms**（真値 600,000） | +13.1 | 長さより先へのシーク 6/10 回でクリックが見つからない。1 回は 293 秒ずれた位置へ | **フラグは効かない**（CBR とみなされ、長さが既知のため） |
| `aac.m4a` | — | 600,000ms | 0.0 | **+23.2（10 回とも）** | |
| `vbr_in_mp4.mp4`（ffmpeg で詰め替え） | — | 600,007ms | −12.0 | **+13.1（10 回とも）** | §12 (a) 案 |

### 3.1 対策の試作: `IndexSeekDataSource`（ヘッダを隠して IndexSeeker を使わせる）

`Mp3Extractor` の `FLAG_ENABLE_INDEX_SEEKING` は「VBR ヘッダはあるが TOC がない」か「CBR とみなしたがファイル長がわからない」ときにしか使われない
（API リファレンスと計測の両方で確認）。そこで、**ファイルは書き換えず、読み出すときだけ**

1. 先頭フレームの `Xing` / `Info` / `VBRI` の識別子を 0 で隠し（CBR とみなさせる）、
2. `open()` でファイル長を `C.LENGTH_UNSET` として返す

DataSource（`measure/IndexSeekDataSource.kt`）を作り、`FLAG_ENABLE_INDEX_SEEKING` と組み合わせた。

| ファイル | 基準 | シーク後の誤差（10 回） | 初回バッファまで |
| --- | --- | --- | --- |
| `vbr_xing.mp3` | +39.2 | **+39.2（10 回とも）** | 最初の遠いシーク（576 秒）で 1,182ms、以後 32〜69ms |
| `vbr_noheader.mp3` | +13.1 | **+13.1（10 回とも）** | 最初の遠いシーク 291ms、以後 30〜73ms |
| `cbr.mp3` | +39.2 | **+39.2（10 回とも）** | 最初の遠いシーク 552ms、以後 42〜88ms |

- 3 種とも、先頭から再生したときとシーク後で**同じ一定の値**になった（時間軸がずれない）。
- +39.2 = +13.1（デコーダの遅延）+ 26.1（隠したヘッダのフレーム 1,152 サンプルが無音のフレームとして再生されるぶん）。ヘッダを隠したファイルでは一定なので補正できる。
- 副作用: 長さが「不明」になる（`duration=TIME_UNSET`）。本体では取り込み時の解析で求めた長さを表示に使う必要がある。
  遠い位置への最初のシークは、そこまでファイルを読むので遅い（10 分の VBR で約 1.2 秒。60 分の教材ではさらに長い見込み。実機で要確認）。

### 3.2 端末での MP4 への詰め替え（§12 (a) 案）

- プラットフォームの `MediaMuxer`（MPEG-4）に `audio/mpeg` のトラックを加えると **`IllegalStateException: Failed to add the track to the muxer`** で失敗した（android-36 のエミュレータ）。
- Media3 の `Mp4Muxer`（media3-muxer）の対応コーデックにも MP3 はない（1.11.1 のソースの KDoc で確認: AAC / AMR / Opus / Vorbis / Raw）。
- したがって (a) 案を端末だけで行うには、MP4（`moov` / `stts` / `stsz` / `stco`）を自前で書く必要がある。ffmpeg で作った MP3 入り MP4 は ExoPlayer で正確にシークできた（上の表）。

### 3.3 §12 の対策案の比較（根拠）

| 案 | 精度（エミュレータ） | 端末での実現 | 長所 | 短所 |
| --- | --- | --- | --- | --- |
| (a) MP4 へ詰め替え | 正確（+13.1 一定） | MediaMuxer / Mp4Muxer は MP3 を受け付けない → **自前の MP4 ライタが必要** | 再生時の処理なし、長さも正しい | 実装量、保存するファイルが元と変わる（SHA-256 と別に持つ必要） |
| (b) M4A（AAC）へ変換 | 正確（+23.2 一定） | Media3 Transformer（依存の追加が必要。未検証） | 標準的 | 再エンコードで音質劣化、時間と電池、ファイルが変わる |
| (c) 自前の索引 | 未実装 | 解析のデコードで全フレームの位置を記録し、独自の Extractor / SeekMap を書く | 最初の遠いシークも速い | Extractor の実装量（ギャップレス、ID3 など） |
| (c') **IndexSeekDataSource**（試作） | **正確（一定）** | DataSource の差し替えだけ（約 100 行） | ファイルを書き換えない（SHA-256 もそのまま）。3 種すべてに効く | 長さが不明になる、最初の遠いシークが遅い、Media3 の内部の挙動（ヘッダの扱い）に依存 |

**提案**: VBR（と、念のため CBR も含めたすべての MP3）に (c') を使うのが、実装量と効果の釣り合いが最もよい。
最初の遠いシークの遅さが実機で問題になる場合だけ (c) に進む。CBR の Info ヘッダ付きでも、既定では ±18ms ほど位置に比例してずれる（10 分で約 30ms）ので、MP3 は形式を問わず (c') に通すと、時間軸のずれが一定値だけになる。

### 3.4 シーク後の一定のずれ（MP3 +13.1ms、AAC +23.2ms）

- 先頭から再生したときは、ギャップレスの情報（LAME ヘッダのエンコーダの遅延、MP4 の edit list）でトリミングされるが、シークの後はトリミングされず、
  デコーダの遅延（MP3 は 576 サンプル = 13.1ms、AAC は 1,024 サンプル = 23.2ms）のぶん、時間軸が遅れる。
- LAME ヘッダ付きの MP3 では、先頭からの再生でエンコーダの遅延 + デコーダの遅延（1,105 サンプル）をトリミングするが、`c2.android.mp3.decoder` がデコーダの遅延を
  自分で補っているらしく、−12.0ms（529 サンプルの取りすぎ）になる（推測。実機で要確認）。
- どれも M2 の 50ms 以内だが、チャンクの頭出しは常にシークなので、**シーク後の値（形式ごとの固定値）で補正する**のがよい。S3（audio-analysis）の解析の時間軸と合わせて決めたい。

## 4. 結果: 自動ポーズからの再開・前 / 次の再割り当て（S2 の 3）

### 4.1 仕組み（`service/SpikePlaybackService.kt`）

- §7.2.1 の縮小版: `MediaSessionService` → `MediaSession` → `LearningPlayer`（`ForwardingSimpleBasePlayer`）→ ExoPlayer。境界は `SpikeChunkScheduler`。
- チャンクは `aac.m4a` のクリックの区間（99 個）。各チャンクの終端で一時停止し、`Handler.postDelayed` で N 秒後に再開する。
- 記録: 再開の遅れ（`elapsedRealtime` で、予定の時刻からの差）、**ポーズ中に CPU が眠っていた時間**（`elapsedRealtime` と `uptimeMillis` の進み方の差。
  `Handler.postDelayed` は `uptimeMillis` 基準なので、眠っていた時間だけ再開が遅れる）、画面の状態、Doze、フォアグラウンドサービスの状態。
- `PARTIAL_WAKE_LOCK`（タイムアウト = ポーズ長 + 5 秒）の有無を切り替えられる。ユーザーが途中で再生したらタイマーを取り消す。

### 4.2 エミュレータでの確認（動くことの確認だけ）

| 条件 | 回数 | 遅れ | 睡眠 | FGS |
| --- | --- | --- | --- | --- |
| WakeLock あり、画面オン、ポーズ 3 秒 | 3 | 62 / 6 / 4ms | 0ms | mediaPlayback のまま |
| WakeLock なし、**画面オフ**、ポーズ 3 秒 | 4 | 3 / 3 / 5 / 1ms | 0ms | mediaPlayback のまま |

- エミュレータは CPU が眠らない（睡眠 0ms）ため、WakeLock の有無の差は出ない。**実機待ち**。
- 一時停止の後もフォアグラウンドサービス（mediaPlayback）が維持され、再開でバックグラウンドからの FGS 開始の制限に当たらなかった（§7.2.4 の想定どおり。ポーズ 3 秒の範囲で確認）。

### 4.3 前 / 次ボタン（§7.2.3）

`adb shell cmd media_session dispatch next / previous` で確認した（android-36 では `adb shell media dispatch` は存在しない）。

| 操作 | `handleSeek` に届いた引数 | 現在位置 → 移動先 |
| --- | --- | --- |
| next | command=9（SEEK_TO_NEXT）、`(-1, C.TIME_UNSET)` | 88,136 → 91,700（次のチャンクの頭） |
| next | 同上 | 92,821 → 98,551 |
| previous（チャンクの頭から 2.4 秒） | command=7（SEEK_TO_PREVIOUS）、`(0, 0)` | 100,964 → 98,551（現在のチャンクの頭） |
| previous（頭から 0.3 秒） | 同上 | 98,859 → 91,700（前のチャンク） |

- next の引数は設計書のとおり `C.INDEX_UNSET` / `C.TIME_UNSET` で届いた。previous は `(0, 0)` で届いた。どちらも引数を使わず現在位置から求める方式で正しく動いた。
- `getState()` で前 / 次のコマンドを足すと、単一の音声でもロック画面のメディア操作に前 / 次のボタンが出た。
- LearningPlayer から `exo.seekTo()` したシークは、ChunkScheduler では「ユーザーのシーク」として扱われ、予約し直された。

## 5. 実機待ちの項目と手順

実機がつながったら、次の順に行う（所要 約 20 分 + 画面オフの放置）。

1. `python3 spikes/playback/testdata/generate_testdata.py && spikes/playback/testdata/install_and_push.sh`
2. **シーク**: `adb shell am start -n com.miuraken.chunkflow.spike.playback/.SpikeActivity --es run seek`、続けて `--es run seekfix`（各 3〜5 分）。
   エミュレータと同じ値になるかを確かめる（デコーダが同じなら同じになる見込み）。`IndexSeekDataSource` の最初の遠いシークの時間も見る。
3. **ループ**: `--es run loop`（約 8 分）。`dynamicScheduling` の有無の差が実機でも出るか。
4. **ループの実音での行き過ぎ**: 画面の「ループ」中に、端末のスピーカーの近くでマイク（PC の録音など）で録音し、B の後の目盛り（10ms ごとのクリック）がいくつ聞こえるかを数える
   （音声編集ソフトで波形を見る）。推定値（停止時）と比べる。
5. **自動ポーズ（画面オフ）**: 充電器を外す（`adb shell dumpsys battery unplug` でもよい）→ `--es run autopause_nowl --el pauseMs 5000 --ei cycles 20` → すぐに電源ボタンで画面を消し、3 分放置 → 画面をつけて結果を見る。
   次に `autopause_wl` で同じことをする。`results/autopause-*.csv` の `lateMs`（遅れ）と `sleptMs`（眠っていた時間）を比べる。
   余裕があれば `adb shell dumpsys deviceidle force-idle` で Doze の状態でも行う。
6. **前 / 次**: Bluetooth イヤホン（または有線のリモコン）の前 / 次ボタンで、チャンクの移動になるか（ログの `[learning]`）。
7. 結果を `adb pull /sdcard/Android/data/com.miuraken.chunkflow.spike.playback/files/results/ .` で回収する。

## 6. 設計への提案（lead と依頼者の判断用）

1. **§7.2.2 に追記**: ExoPlayer を `experimentalSetDynamicSchedulingEnabled(false)` で作る。理由: 既定（true）では PlayerMessage の配信が数十〜数百 ms 遅れ、
   M2 の「行き過ぎ 50ms 未満」を満たさない。`@ExperimentalApi`（`@OptIn` が必要。Lint の `UnsafeOptInUsageError`）の API なので、Media3 を上げるときに確認する。電池への影響（再生中の起床が 10ms ごとになる）は実機で確認する。
2. **ループの別案**（判断が必要）: チャンクリピート / A-B を `ClippingConfiguration` + `REPEAT_MODE_ONE` で行うと、行き過ぎは 0 で、ループの継ぎ目の途切れもない。
   ただし、メディアアイテムを差し替えるので、時間軸（`currentPosition` が切り出しの先頭からになる）、自動ポーズとの組み合わせ、通知の表示が複雑になる。
   まずは PlayerMessage + `dynamicScheduling=false` で進め、実機で行き過ぎや継ぎ目の途切れ（再開まで 25〜100ms）が気になる場合に検討する、を提案。
3. **§7.2.4**: 自分のシークの区別は「件数」で行う。A-B は `setDeleteAfterDelivery(false)` の 1 件で実装してよい。
4. **§7.2.2 / §12 の MP3**: `FLAG_ENABLE_INDEX_SEEKING` だけでは 3 種のどれも改善しない（TOC のない VBR だけに効く）。
   **MP3 はすべて `IndexSeekDataSource` 相当（ヘッダを隠す + 長さを不明にする）を通して再生する**ことを提案。長さは取り込み時の解析で求めて DB に持つ（`media_item.duration_ms` は既にある）。
   最初の遠いシークの遅さが実機で問題になれば、解析時に作る索引（(c) 案）へ進む。端末での MP4 への詰め替え（(a)）は MediaMuxer / Mp4Muxer が MP3 を受け付けないため、自前の MP4 ライタが必要になり割に合わない。
5. **時間軸の補正**: シーク後の一定のずれ（MP3 +13.1ms、ヘッダを隠した MP3 +39.2ms、AAC +23.2ms）を形式ごとの固定値として持ち、
   チャンクの頭出し・スクリプトの同期に使う。S3（解析の時間軸）・S1（Whisper の時刻）と合わせて lead が決める。
6. **§7.2.3**: 設計書のとおりで動いた。previous は `(0, 0)` で届くことを追記するとよい。
7. **計測の注意**（スパイク・テストの書き方）: 共用のエミュレータでは、別のアプリが前面に出るとこのアプリが凍結される（ActivityManager の freezer）。
   長い計測は前面サービスの中で行う（`MeasureService`）。`adb shell mkdir` で `Android/data/<pkg>/files/` の下にフォルダを作るとアプリから読めない。

## 7. 未確認のこと

- 実機での全項目（§5）。
- 自動ポーズ中の CPU スリープによる遅れと、WakeLock の効果（エミュレータでは CPU が眠らない）。
- `dynamicScheduling=false` の電池への影響。
- Media3 Transformer での M4A への変換（(b) 案）は試していない（依存の追加が必要）。
- LAME ヘッダ付きの MP3 で先頭からの再生が −12ms になる理由（デコーダの遅延の二重の補正という推測）。
