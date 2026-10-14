# 0007. チャンク境界の処理の精度（ExoPlayer の dynamic scheduling を無効にする）

- 状態: 提案（人間の承認待ち）
- 日付: 2026-10-14
- 決めた人: lead（スパイク S2 の結果をもとに）
- 根拠: [docs/spikes/S2-playback.md](../spikes/S2-playback.md) §2、§4（エミュレータでの計測。タイミングは参考値）
- 関係する節: 設計書 §7.2.2、§7.2.3、§7.2.4、§11（M2 の完了条件: ループの行き過ぎ 50ms 未満）

## 背景

§7.2.4（D3）は、ChunkScheduler が ExoPlayer の `PlayerMessage` で次の境界を予約して処理する、としている。S2 では、Media3 1.11.1 の既定
（`dynamicScheduling` が有効。READY の間はレンダラの指示で再生スレッドが長く眠る）だと、**境界の検出そのものが 30〜200ms 遅れ**、
ループの行き過ぎが平均 46〜200ms（最大 365ms。メディア時刻）になった。M2 の目標（50ms 未満）を満たさない。

## 決定

1. **ExoPlayer を `ExoPlayer.Builder.experimentalSetDynamicSchedulingEnabled(false)` で作る。**
   - S2 では、再生スレッドが従来どおり約 10ms ごとに動き、行き過ぎは平均 1〜8ms（最大 24〜67ms。2.0 倍速の一時停止で最大 67ms）になった。
   - `@ExperimentalApi` の API なので、`@OptIn` が必要（Lint の `UnsafeOptInUsageError`）。Media3 を上げるときに、API の存続と既定値を確かめる。
   - **電池への影響**（再生中の起床が 10ms ごとになる）は実機で確かめる。影響が大きい場合は、境界の手前だけ無効にする等の別案を検討する（未検討）。
2. **自分のシークとユーザーのシークの区別は「件数」で行う**（§7.2.4 の「フラグ」を改める）。
   ChunkScheduler の `seekTo()` の中で `onPositionDiscontinuity`（SEEK）は同期的に届いたが、リスナーの中から `seekTo()` を呼ぶと通知が後回しになるため、
   「自分のシークの件数」を数え、届いた SEEK の分だけ減らす。
3. **A-B リピートは `setDeleteAfterDelivery(false)` の `PlayerMessage` を 1 件使い回してよい**（予約し直す方式と同等の精度で、ユーザーのシークの後も配信が続いた）。
4. **単一チャンク / A-B のリピートの改善案として、`ClippingConfiguration`（A〜B の切り出し）+ `REPEAT_MODE_ONE` を M2 の実装時に評価する。**
   S2 では行き過ぎ 0、継ぎ目の途切れもなかった。ただしメディアアイテムを差し替えるので、時間軸（`currentPosition` が切り出しの先頭から数えられる）、
   自動ポーズとの組み合わせ、通知の表示が複雑になる。まずは決定 1 の方式で実装し、実機で行き過ぎや継ぎ目の途切れ（再開まで 25〜100ms）が気になる場合に採る。
5. 前 / 次の再割り当て（§7.2.3）は設計書のとおりで動いた。`next` は `(C.INDEX_UNSET, C.TIME_UNSET)`、`previous` は `(0, 0)` で `handleSeek` に届いた。
   引数を使わず現在位置から移動先を求める方式でよい。

## 実機待ち・未確認

- 実機での行き過ぎ（推定値と、録音で数える実際の値）。`dynamicScheduling` の有無の差が実機でも出るか。電池への影響。
- 自動ポーズ中の CPU スリープによる再開の遅れと、`PARTIAL_WAKE_LOCK` の効果（エミュレータは CPU が眠らないため測れなかった）。
- Bluetooth イヤホンの前 / 次ボタン。
- 一覧は [docs/spikes/README.md](../spikes/README.md)。
