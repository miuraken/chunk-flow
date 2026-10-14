# 0004. マニフェストの確認の規則（権限とエクスポート）

- 状態: 承認済み
- 日付: 2026-10-14
- 決めた人: lead

## 背景

設計書 §8.2 / §9 は、マージ後のマニフェストの権限とエクスポートされたコンポーネントが一覧を超えないことを CI で確かめる、としています。
ところが AndroidX のライブラリは、§8.2 の一覧にない宣言をマージで持ち込みます（Phase 0 の時点で確認したもの、または持ち込むことが分かっているもの）。

| 持ち込むもの | 由来 | 内容 |
| --- | --- | --- |
| `com.miuraken.chunkflow.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | androidx.core | `protectionLevel="signature"` の独自権限。`ContextCompat.registerReceiver` の非公開レシーバー用 |
| `androidx.profileinstaller.ProfileInstallReceiver`（exported） | androidx.profileinstaller 1.4.0（Compose から推移的に） | `android:permission="android.permission.DUMP"` で保護。ベースラインプロファイルのインストールの診断用 |
| `androidx.compose.ui.tooling.PreviewActivity`（exported、権限なし） | ui-tooling（`debugImplementation`）。**デバッグビルドだけ** | Android Studio の「プレビューを端末で実行」に使う |
| `androidx.work.impl.diagnostics.DiagnosticsReceiver`（exported） | WorkManager（まだ依存していない） | `android.permission.DUMP` で保護 |
| `android.permission.RECEIVE_BOOT_COMPLETED` | WorkManager（まだ依存していない） | 再起動後の再スケジュール用 |
| `android.permission.ACCESS_NETWORK_STATE` | **Media3（media3-common 1.11.1）が Phase 0 の時点で持ち込んでいる**。WorkManager も持ち込む | 設計書 §9 で許容済み |

## 決定

`.github/scripts/check_manifest.py` の規則を次のとおりにします。

1. 権限は §8.2 の一覧と `ACCESS_NETWORK_STATE`（§9）だけを許す。加えて、`<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
   を許す（signature 保護で他のアプリには与えられず、アプリの能力も増やさないため）。
2. エクスポートは `MainActivity`、`*.PlaybackService`（`com.miuraken.chunkflow` の下）、`androidx.media3.session.MediaButtonReceiver`
   だけを許す。加えて、`android.permission.DUMP`（signature|privileged。shell とシステムだけが持つ）で保護された
   `ProfileInstallReceiver` と `DiagnosticsReceiver` を、名前と権限の両方が一致する場合に限って許す。
   デバッグビルドのマニフェスト（パスに `debug` を含む）に限り、`androidx.compose.ui.tooling.PreviewActivity` も許す
   （リリースビルドに入らないことは、リリースのマニフェストの確認で担保する）。
3. `android:exported` がなくても `intent-filter` があればエクスポートとみなす。`usesCleartextTraffic="true"` は拒否する。
4. 一覧を変えるときは、この ADR を置き換える新しい ADR に理由を書く（AGENTS.md §10）。
5. 対象は本体のアプリ（`:app`）のマージ後のマニフェストだけ。**スパイクのアプリ（`spikes/playback`、`spikes/audio`）は
   本体の権限とエクスポートの制約（§8.2）の外**とし、確認の対象にしない（2026-10-14 追記）。スパイクは検証のための独立したアプリで、
   配布せず、本体のモジュールからも依存しないため。スパイクで使った権限を本体に持ち込むときは、§8.2 の一覧と照らして判断する。

## 未決（人間の判断が必要）

- WorkManager を加える（M2 の解析 Worker）と `RECEIVE_BOOT_COMPLETED` が持ち込まれる。§8.2 の一覧にないため、
  (a) マニフェストで `tools:node="remove"` で取り除く（再起動後の再スケジュールは、次にアプリを開いたときに WorkManager が行う。
  解析は前面で開始する方針（§7.3.4）なので影響は小さい見込み）か、(b) 一覧に加えるかを、WorkManager を加える時点で決める。
  lead の推奨は (a)。
