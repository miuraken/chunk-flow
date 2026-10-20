# Material Design 3 準拠に向けた UI/UX 調査と計画

- 作成日: 2026-10-20
- 作成者: lead
- 状態: **調査・考察（実装前）**。人間の判断を待つ。
- 対象: `feature:library`、`feature:player`、`feature:settings`、`core:designsystem`、`:app` の画面遷移
- 関連: 設計書 §7.5（画面仕様）、§10.5（UI の確認）、ADR 0002（スクリーンショットテスト）、`_agents/rules/compose.md`

---

## 0. 要約

| 観点 | 結論 |
| --- | --- |
| 現状の土台 | **Material 3 の上に正しく載っている**。`MaterialTheme` + ダイナミックカラー、M3 コンポーネント（Scaffold / TopAppBar / Chip / Card / BottomSheet / Dialog / Slider / SegmentedButton など）を一貫して使い、`.sp` のハードコードはゼロ、文字列はリソース化、edge-to-edge と予測型「戻る」も対応済み。 |
| 主なずれ | (1) **画面構造**（ライブラリの 4 セクションをタブで表現、ミニプレイヤーとの関係、1 行に操作が多すぎる行）、(2) **コンポーネントの用途外使用**（Chip を再生操作のトグルに多用、`Surface + combinedClickable` の自作チップ、長押しが主な発見経路）、(3) **テーマの未整備**（Typography / Shapes 未定義、`RoundedCornerShape(n.dp)` 直書き 59 箇所、ハードコード色 13 箇所、言語パレットがダイナミックカラーと調和しない）、(4) **モーションがほぼ無い**（アニメーション API の使用 2 箇所）。 |
| 判断 | 「M3 準拠」は **段階的に進められる**。基盤整備（Phase A）は見た目をほぼ変えずに実施でき、画面構造の変更（Phase C）だけが「別のアプリに見える」リスクを持つ。Phase A/B と Phase C/D を分けて評価用 APK を出すことを勧める。 |
| 作業量 | 人手換算で **約 20〜30 人日**（Phase A: 2〜3、B: 5〜8、C: 8〜12、D: 3〜5、各フェーズの基準画像更新と QA を含む）。エージェント並列（ui ×3 + qa）なら実時間 **2〜3 週間**（人間のレビュー待ちを含む）。 |

---

## 1. 調査の方法

1. `core:designsystem` のテーマ定義（`Theme.kt`、`Color.kt`、`ChunkFlowIcons.kt`）を読んだ。
2. `feature/*` の全 Composable（約 15,000 行）を対象に、M3 コンポーネントの使用回数、ハードコード色、`RoundedCornerShape` 直書き、`.sp` 直書き、アニメーション API、`scrollBehavior`、`Snackbar`、絵文字の使用を機械的に数えた。
3. スクリーンショットテストの基準画像（`feature/*/src/screenshotTestDebug/reference/`）で、ライブラリ（フォルダ / ブックマーク）、プレイヤー（再生中）、設定（再生設定）の見た目を確認した。
4. 比較対象は Material Design 3 のガイドライン（m3.material.io）と、Compose Material 3 **1.4.0**（現在の BOM `2026.09.00` に含まれる版。M3 Expressive の API を含む。多くは `@ExperimentalMaterial3ExpressiveApi` で要 OptIn）。

### 1.1 計測結果（抜粋）

| 指標 | 値 | 評価 |
| --- | --- | --- |
| `MaterialTheme.colorScheme` 以外のハードコード色 `Color(0x…)` | **13 箇所**（library 11、player 2） | 要修正 |
| `RoundedCornerShape(n.dp)` の直書き | **59 箇所**（`MaterialTheme.shapes` の使用は 11 箇所） | 要修正 |
| `.sp` の直書き | **0** | 良い |
| `scrollBehavior`（TopAppBar の折りたたみ / 固定） | **0** | 要検討 |
| アニメーション API（`AnimatedVisibility` / `animate*AsState` など） | **2 箇所** | 要改善 |
| `Snackbar` | **0**（通知はすべて自作バナー） | 要検討 |
| 絵文字の UI 文字列 | **3**（`🔁リピート` ×2、`⚠️ 遅れています`） | 要修正 |
| `FilterChip` / `AssistChip` | 60 / 22 | 用途を見直す |
| `TextButton` / `FilledTonalButton` / `OutlinedButton` / `Button` | 63 / 19 / 17 / **0** | 階層を見直す |
| `Checkbox` / `Switch` | 20 / **1** | 設定画面は Switch が標準 |
| `AlertDialog` | 32 | 複雑なものはシート / 全画面へ |
| `NavigationBar` / `NavigationRail` / `NavigationSuiteScaffold` | **0**（セクション切替は `PrimaryTabRow`） | 構造の判断が必要 |
| `ListItem` | **0**（行はすべて自作 `Row` / `Card`） | 要検討 |
| M3 Expressive の API（`ButtonGroup` / `ToggleButton` / `SplitButton` / `FloatingToolbar` / `LoadingIndicator` / `MotionScheme`） | **0** | 任意（Phase D） |

---

## 2. Material Design 3 に沿っている部分

### 2.1 テーマとカラー
- `ChunkFlowTheme` は `dynamicLightColorScheme` / `dynamicDarkColorScheme`（Material You）を既定で使い、プレビューとスクリーンショットテストでは安定した代替配色（Teal / Slate / Amber のトーナルパレット）に切り替える。ダークテーマに対応。
- 画面の大半は `colorScheme` の**ロール**（`primaryContainer`、`secondaryContainer`、`tertiaryContainer`、`errorContainer`、`surfaceContainer*`、`onSurfaceVariant`、`outlineVariant`）で色を取っており、ダイナミックカラーが正しく波及する。
- `surfaceContainerLow/High/Highest` によるトーナル面の階層付けを使っている（M3 の elevation 表現）。

### 2.2 タイポグラフィ
- 文字サイズはすべて `MaterialTheme.typography`（`titleMedium`、`bodyLarge`、`labelMedium` など）から取り、`.sp` の直書きがない。文字サイズ 2.0 倍のプレビュー（`@ChunkFlowPreviews`）で確認する運用になっている。
- 多言語（CJK、ハングル、デーヴァナーガリーなど）に `LocaleList` を付けて字形を正しくしている。

### 2.3 コンポーネント
- 画面骨格: `Scaffold` + `TopAppBar`（戻る / タイトル / アクション）+ `PrimaryTabRow`。
- 選択と入力: `FilterChip`（言語・速度プリセット）、`SegmentedButton`（自動ポーズの対象）、`Slider`、`Checkbox`、`RadioButton`、`ExposedDropdownMenuBox`（ソート）。
- オーバーレイ: `ModalBottomSheet`（速度調整、ファイル設定）、`AlertDialog`（確認・編集）、`DropdownMenu`（長押しメニュー、プリセット）。
- フィードバック: `LinearProgressIndicator` / `CircularProgressIndicator`、`errorContainer` のエラーカード。
- `FloatingActionButton`（再生リスト追加）、`HorizontalDivider`、`Card` / `OutlinedCard`。

### 2.4 レイアウトとプラットフォーム連携
- edge-to-edge とシステムバーのインセット処理、Navigation 3 による予測型「戻る」。
- プレイヤー画面は幅 600dp 以上で 2 ペイン（波形 + 操作 / スクリプト）に自動切替。
- アクセシビリティ: アイコンボタンの `contentDescription`、48dp のタップ領域、波形 Canvas の `semantics`（`progressBarRangeInfo` / `customActions`）、言語バッジは色＋文字（`ja` / `ko`）で判別できる。

### 2.5 プロダクト固有のよい点
- **言語役割のカラーパレット**（母語 = 青、学習言語 = ピンク）を `CompositionLocal` で一元化しており、スクリプト・波形・ライブラリで一貫している。M3 の「カスタムカラー（custom colors）」の正しい置き場所である。

---

## 3. Material Design 3 から大きく外れている部分

優先度は **高**（M3 の原則と明確に矛盾し、見た目や使い勝手に影響）/ **中**（ガイドラインの推奨から外れる）/ **低**（仕上げ）。

### 3.1 画面構造・ナビゲーション

| # | 内容 | 根拠（M3） | 優先度 |
| --- | --- | --- | --- |
| S1 | ライブラリの 4 セクション（フォルダ / ファイル / 再生リスト / ブックマーク）を **`PrimaryTabRow`（上部タブ）** で切り替えている。 | M3 では「3〜5 個の同格な最上位の行き先」は **Navigation bar（下部）**、大画面では **Navigation rail** が標準。タブは「同じ画面内の並列なコンテンツ」向け。`NavigationSuiteScaffold` で幅に応じて自動切替できる。 | 高 |
| S2 | **ミニプレイヤー**がライブラリ下部に常駐し、ブックマークタブでは**フル操作のコントローラーに置き換わる**（§7.5.2 E）。下部ナビゲーションを導入すると位置が競合する。 | YouTube Music 等の M3 準拠アプリでは「ミニプレイヤーは Navigation bar の直上に置き、タップで全画面プレイヤーを展開」が定石。 | 高（S1 と同時に設計） |
| S3 | **ライブラリとプレイヤーのアプリ単位 2 ペイン**は未実装（`ChunkFlowNavDisplay` のコメントに「M4 で加える」と残っている。設計書 §7.5.1 の記述は先行している）。 | M3 の Canonical layout（**List-detail**）。`material3-adaptive` の `ListDetailPaneScaffold` / Navigation 3 の Scene で実現できる。 | 中 |
| S4 | ライブラリのファイル行に **操作が多すぎる**（チェックボックス / ドラッグハンドル、サムネイル、お気に入り星、鉛筆、×、進捗バー、3 段のテキスト）。 | M3 の List item は「先頭 1 要素 + 本文 1〜3 行 + 末尾 1 要素」。追加の操作はオーバーフローメニュー・長押し・スワイプに逃がす。`ListItem` は未使用（0）。 | 高 |
| S5 | フォルダ行が **`Card` 全面に `tertiaryContainer` / `primaryContainer` を塗って**「仮想 / 実フォルダ」を区別している（基準画像ではオレンジ / 水色の帯が並ぶ）。 | M3 では一覧の行は `surface` 系に置き、種別は先頭アイコンや `AssistChip` / `Badge` で示す。強い色面はカードの階層（elevated / filled / outlined）と衝突する。色だけで意味を伝えない原則にも抵触しかける（「Virtual folder」バッジで補っている）。 | 中 |
| S6 | TopAppBar に **`scrollBehavior` がなく**、ライブラリのような一覧画面でも常に固定・小型。 | M3 では一覧画面は `LargeTopAppBar` / `MediumTopAppBar` + `exitUntilCollapsedScrollBehavior`、または `pinnedScrollBehavior` でスクロール時に色を変える。 | 中 |
| S7 | 設定画面は **`Card` に囲んだセクション**に `Checkbox` を並べる形。4 タブ構成。 | M3 の設定は `ListItem`（見出し / 補足 / 末尾の **`Switch`**）の縦一覧が標準。`Checkbox` は複数選択のリスト向け。 | 中 |

### 3.2 コンポーネントの用途と状態表現

| # | 内容 | 根拠（M3） | 優先度 |
| --- | --- | --- | --- |
| C1 | **再生操作ストリップ**（`1x` / `1.5x` / `リピート` / `無音スキップ` / `⚙️`）を `FilterChip` と **`Surface + combinedClickable` の自作チップ**で構成している。設定アイコンは `AssistChip` の `label` にアイコンを入れている。 | Chip は「フィルタ・入力・提案・補助操作」用で、**主要な再生コントロールのトグルには使わない**。M3（Expressive）では `ToggleButton` / `IconToggleButton` / `ButtonGroup`（連結トグル）が該当。アイコンのみなら `IconButton`。 | 高 |
| C2 | 速度チップとリピートチップは **長押しが主要機能の入口**（速度シート、ファイル全体リピート）。前 / 次 / 巻き戻し / 早送りも長押しでメニュー。発見性が低く、TalkBack では到達しにくい。 | M3 は「長押しは補助的な近道。同じ操作に可視の経路を用意する」。候補: `SplitButton`（主操作 + ▼メニュー）、長押しに `TooltipBox` を添える、または設定シートに同じ操作を置く。 | 高 |
| C3 | **トランスポート 5 ボタン**は自作の `TransportTonalButton`（楕円の `Surface` にアイコン + 文字 `-3s` / `prev`）。 | M3 のメディア操作は `IconButton` / `FilledTonalIconButton` / `FilledIconButton`（再生は大サイズ）＋`ButtonGroup`。文字ラベルを入れるなら `Button` 群。Expressive では**再生ボタンの shape morphing**（再生↔一時停止で形が変わる）が推奨例。 | 中 |
| C4 | **未選択チップを `surfaceContainerLowest α0.55` + `onSurfaceVariant α0.62`** で薄く描く独自の「明るさトグル」（`brightnessToggleChipColors`）。 | M3 の State layer は「`onSurface` を 8% / 12% / 16% 重ねる」規則で、**コンテンツ色の透過で無効状態を模倣しない**。未選択が「無効」に見える上、コントラスト比 4.5:1 を割る可能性がある。 | 高 |
| C5 | **ハードコード色 13 箇所**: お気に入り星 `#FFB300`、文字起こし完了の緑 `#19532B`、ファイル全体リピートの青 `#1976D2` + `Color.White`、サムネイル既定色、既定アートワークのグラデーション。 | ダイナミックカラーと調和せず、ダークテーマでの明度も固定。M3 の「custom color」は `ColorScheme` と**調和（harmonize）**させるか、役割色（`tertiary` など）に寄せる。 | 高 |
| C6 | **言語パレット**（青 / ピンク）が固定 HEX。壁紙由来のダイナミックカラーが例えば赤系のとき、学習言語のピンクと `primary` が衝突する。 | M3 の「Custom colors – Harmonization」。`material-color-utilities` の `Blend.harmonize(custom, primary)` で色相を `primary` 側に寄せつつ「青 / ピンク」の認識は保てる。 | 中 |
| C7 | **絵文字**をラベルに使用（`🔁リピート`、`⚠️ 遅れています`）。 | アイコンは `Icon` で描き、`contentDescription` と `tint` を制御する。絵文字はフォント依存で配色・サイズが制御できない。 | 高（容易） |
| C8 | **`Snackbar` 未使用**。取り込み完了・エクスポート完了・エラーをすべて自作のバナー `Card` で表示（閉じるボタン付き）。 | 一時的な結果通知は `Snackbar`（必要なら Action 付き）。持続的な警告（モデル未準備など）は `Card`（`errorContainer` / `tertiaryContainer`）でよい。両者を使い分ける。 | 中 |
| C9 | **`AlertDialog` 32 箇所**のうち、「より詳しく文字起こし…」や言語設定モーダルは縦スクロールを伴う複雑な内容。 | M3 の Dialog は「短い判断」用。複雑な入力は **Full-screen dialog** または **Modal bottom sheet**。 | 中 |
| C10 | **`Button`（Filled）が 0**。確定操作も `TextButton` / `FilledTonalButton`。 | 画面に 1 つの最重要操作（例: エクスポート実行、設定の保存）は `Button`（Filled）でヒエラルキーを示す。 | 低 |
| C11 | 設定画面の `SecondaryScrollableTabRow` 風のタブが**右端で見切れる**（基準画像で `Lang…`）。 | `PrimaryScrollableTabRow` の `edgePadding` と見切れ表示は許容されるが、4 項目なら下部ナビやセクション一覧の方が見通しがよい。 | 低 |

### 3.3 テーマの体系

| # | 内容 | 根拠（M3） | 優先度 |
| --- | --- | --- | --- |
| T1 | `Typography` を**定義していない**（M3 既定のまま）。ブランドとしての書体選択（例: Noto Sans JP / Roboto Flex）がない。 | 既定でも違反ではないが、M3 は `displayLarge`〜`labelSmall` の 15 スタイルをアプリで定義することを想定。Expressive では **Emphasized** スタイル（`displayLargeEmphasized` 等）が追加。 | 低 |
| T2 | `Shapes` を**定義していない**うえ、`RoundedCornerShape(6/8/10/12/16.dp)` の直書きが 59 箇所。角丸の段階がばらつく。 | M3 の Shape scale（`extraSmall 4` / `small 8` / `medium 12` / `large 16` / `extraLarge 28`）を `MaterialTheme.shapes` から参照する。 | 中 |
| T3 | 代替配色（プレビュー用）は 12 ロールしか指定しておらず、`surface*` / `outline` / `error` などは M3 既定。ダイナミックカラー時との差が大きい。 | Material Theme Builder で**全ロール**を生成した `ColorScheme` を置く。 | 低 |
| T4 | `MotionScheme`（Expressive）未使用。NavDisplay の遷移は既定、`AnimatedVisibility` 等は 2 箇所。バナー・チップ・選択状態の出入りが瞬時に切り替わる。 | M3 の Motion（Easing / Duration token）、Expressive の **`MotionScheme.expressive()`**（スプリング）。状態の変化には `animateColorAsState` / `AnimatedVisibility` / `animateContentSize`。 | 中 |
| T5 | アイコンは `ChunkFlowIcons.kt`（1,150 行）に**パスデータを手書き**。Material Symbols の最新グリフ（Outlined / Rounded / Filled の一貫性、ウェイト）とずれるリスク。 | M3 は **Material Symbols**。依存追加（`material-icons-extended` は更新停止、Material Symbols の Compose 配布は要調査）が必要なため lead 判断。 | 低 |

### 3.4 M3 に無い独自 UI（準拠対象外として扱うもの）
- **全体波形 / 拡大波形**（Canvas）: M3 にコンポーネントがない領域。色は `colorScheme` と言語パレットから取っており、現状の方針でよい。選択バッジ・A-B 範囲・再生ヘッドの色をトークン化する程度。
- **フローティング操作バー**（ドラッグ可能）: 独自だが、Expressive の **`HorizontalFloatingToolbar`** が概念的に一致するため置き換え候補（Phase D）。

---

## 4. 準拠のためにしたほうがいいこと（ToDo 一覧）

優先度と、見た目・操作への影響（**影響: 小** = 既存の使い方が変わらない、**大** = 画面構造や操作の場所が変わる）を併記する。工数は人手換算（人日）。

### Phase A: 基盤（見た目をほぼ変えずに済む）

| ID | 作業 | 対応する問題 | 影響 | 工数 |
| --- | --- | --- | --- | --- |
| A1 | `core:designsystem` に `Typography`（15 スタイル）と `Shapes`（5 段階）を定義し、`ChunkFlowTheme` に渡す。 | T1, T2 | 小 | 0.5 |
| A2 | `RoundedCornerShape(n.dp)` 59 箇所を `MaterialTheme.shapes.*` に置換（4→extraSmall、8→small、12→medium、16→large）。 | T2 | 小 | 0.5 |
| A3 | ハードコード色 13 箇所をテーマへ移す: 完了の緑と星の黄は `ChunkFlowLanguagePalette` と同じ仕組みの **`ChunkFlowExtendedColors`**（`favorite` / `success` / `wholeFileRepeat`）に集約し、ライト / ダークを用意。ファイル全体リピートの青は `tertiary` に寄せる。 | C5 | 小 | 0.5 |
| A4 | 拡張色と言語パレットを **`Blend.harmonize`** で `primary` に調和させる（`material-color-utilities` は Compose M3 に同梱の `androidx.compose.material3` 内部ではなく、`com.google.android.material:material` 由来の `MaterialColors.harmonize` か純 Kotlin 実装の移植。依存追加は lead 判断）。 | C6 | 小〜中 | 1.0 |
| A5 | `brightnessToggleChipColors` を廃止し、未選択チップは M3 既定、選択は `secondaryContainer`（FilterChip 既定）に戻す。 | C4 | 小 | 0.5 |
| A6 | 絵文字 3 箇所を `Icon`（`Repeat` / `Warning`）に置換。 | C7 | 小 | 0.2 |
| A7 | 代替配色を Material Theme Builder で全ロール生成して置換（プレビュー基準画像の更新を伴う）。 | T3 | 小 | 0.3 |
| — | **基準画像の更新（約 85 枚 ×3 画面群）と人間の承認、QA** | — | — | 1.0 |
| | **小計** | | | **約 3〜4 人日** |

### Phase B: コンポーネントの適正化（操作の場所は変えない）

| ID | 作業 | 対応する問題 | 影響 | 工数 |
| --- | --- | --- | --- | --- |
| B1 | 再生操作ストリップを `ButtonGroup`（Expressive）または `IconToggleButton` / `FilledTonalIconToggleButton` に置換。`1x` / `カスタム速度` は `SingleChoiceSegmentedButtonRow` でも可。 | C1 | 中 | 1.5 |
| B2 | 長押しの主要機能に可視経路を追加: 速度・リピートは **`SplitButton`**（主操作 + ▼）、前 / 次 / 巻き戻し / 早送りは長押しを残しつつ `TooltipBox`（長押し説明）を付与し、同じメニューを `⋮` からも開けるようにする。 | C2 | 中 | 1.5 |
| B3 | トランスポート 5 ボタンを `FilledIconButton`（再生、大サイズ）+ `FilledTonalIconButton` ×4 に置換。秒数は `contentDescription` と Tooltip へ。ラベルを残すなら `labelSmall` を下に置く。 | C3 | 中 | 1.0 |
| B4 | `SnackbarHost` を `Scaffold` に追加し、取り込み / エクスポート完了・一時的エラーを `Snackbar`（Action: 「開く」）へ移行。持続的な警告バナーは `Card` のまま。 | C8 | 小 | 1.0 |
| B5 | 設定画面を `ListItem` + `Switch` 中心に組み替える（`Checkbox` 20 箇所のうち二値設定は Switch へ）。セクションは見出し + 区切りに変更。 | S7 | 中 | 1.5 |
| B6 | TopAppBar に `scrollBehavior` を付ける（ライブラリ: `MediumTopAppBar` + `exitUntilCollapsed`、プレイヤー / 設定: `pinned`）。 | S6 | 小 | 0.5 |
| B7 | 複雑な `AlertDialog`（詳細文字起こし、言語設定モーダル）を `ModalBottomSheet` または全画面ダイアログへ。 | C9 | 中 | 1.5 |
| B8 | 画面ごとの最重要操作を `Button`（Filled）に昇格（エクスポート実行、適用）。 | C10 | 小 | 0.3 |
| — | 基準画像更新・QA | — | — | 1.0 |
| | **小計** | | | **約 8〜10 人日** |

### Phase C: 画面構造（「別のアプリ」に見え得る変更）

| ID | 作業 | 対応する問題 | 影響 | 工数 |
| --- | --- | --- | --- | --- |
| C-1 | ライブラリの 4 セクションを **`NavigationSuiteScaffold`**（Compact: Navigation bar、Medium 以上: Navigation rail）に移行。選択モードの TopAppBar、各タブの状態保持（`rememberSaveable`）を維持。 | S1 | 大 | 2.5 |
| C-2 | **ミニプレイヤーの再設計**: Navigation bar の直上に配置し、タップで全画面プレイヤーへ。ブックマークタブの専用コントローラーは「ミニプレイヤーの展開状態」に統合するか、ブックマークタブだけ下部に `BottomAppBar` を出すかを決める（**要・人間の判断**）。 | S2 | 大 | 2.5 |
| C-3 | ライブラリの行を **`ListItem`** ベースに再構成: 先頭 = サムネイル、本文 = タイトル / 言語バッジ + 進捗 1 行、末尾 = **1 つ**（お気に入り or ⋮）。鉛筆・× はオーバーフロー / 長押しへ。進捗バーは `supportingContent` 内の細いバーとして残す。 | S4 | 大 | 2.0 |
| C-4 | フォルダ行の全面着色をやめ、`surfaceContainer` 上に先頭アイコンの色と `AssistChip`（仮想）で区別。 | S5 | 中 | 0.5 |
| C-5 | アプリ単位の **List-detail 2 ペイン**（ライブラリ | プレイヤー）を `ListDetailPaneScaffold`（`material3-adaptive`）または Navigation 3 Scene で実装。`:app` の `ChunkFlowNavDisplay` と設計書 §7.5.1 を一致させる。 | S3 | 中（大画面のみ） | 2.0 |
| — | 基準画像更新・QA（画面構造が変わるため手動テスト §2〜§5 の再実施を含む） | — | — | 2.0 |
| | **小計** | | | **約 10〜12 人日** |

### Phase D: モーションと Expressive の仕上げ（任意）

| ID | 作業 | 対応する問題 | 影響 | 工数 |
| --- | --- | --- | --- | --- |
| D1 | `MaterialExpressiveTheme` + `MotionScheme.expressive()` へ切替（`@OptIn(ExperimentalMaterial3ExpressiveApi)`）。 | T4 | 小 | 0.5 |
| D2 | 状態変化のアニメーション: バナーの `AnimatedVisibility`、チップ / 行背景の `animateColorAsState`、リストの `animateItem`、選択モードの TopAppBar 切替に `AnimatedContent`。 | T4 | 小 | 1.0 |
| D3 | 再生ボタンの **shape morphing**（`MaterialShapes` + `Morph`）、文字起こし進捗を `LinearWavyProgressIndicator` / `LoadingIndicator` に。 | C3, T4 | 小 | 1.0 |
| D4 | フローティング操作バーを **`HorizontalFloatingToolbar`** に置換（ドラッグ移動は独自実装を残す）。 | §3.4 | 中 | 1.0 |
| D5 | サムネイル→プレイヤーの **Shared element transition**（Navigation 3 の `SharedTransitionLayout`）。 | T4 | 小 | 1.0 |
| D6 | アイコンを Material Symbols に統一（依存の追加または手書きパスの再生成）。 | T5 | 小 | 1.0 |
| — | 基準画像更新・QA | — | — | 0.5 |
| | **小計** | | | **約 5〜6 人日** |

> [!NOTE]
> Phase A〜D 合計: **約 26〜32 人日**（人手換算）。エージェント実行では、ui 担当を画面ごと（library / player / settings）に並列させられるため、実時間は **2〜3 週間**を見込む。律速は「見た目が変わる基準画像の人間の承認」（AGENTS.md §9）と手動テスト。

---

## 5. アプローチ

### 5.1 進め方の原則
1. **機能は変えず、見た目と操作部品だけを変える**。ViewModel・UiState・イベントは原則そのまま（§6.2 の `PositionProvider` 方針も維持）。これにより既存の単体テストが回帰の網になる。
2. **フェーズごとに評価用 APK を出し、人間が判断する**。Phase A/B（部品の適正化）と Phase C（構造変更）は別々に評価する。Phase C は「導入しない」判断も残す。
3. **設計書の先行**: Phase C の着手前に、設計書 §7.5.2（ライブラリ）と §7.5.3（プレイヤー）を M3 版に書き換えた案を作り、承認を得てから実装する（AGENTS.md §1）。
4. **スクリーンショットテストを味方にする**: 各 Phase の最初に `updateDebugScreenshotTest` で差分一覧を出し、意図した変更だけかを人間が確認する。

### 5.2 ブランチと評価用アプリの出し方
- **統合ブランチ**: `agent/ui/m3-refresh`（lead が作成）。フェーズごとに `agent/ui/m3-a-theme`、`agent/ui/m3-b-components`、`agent/ui/m3-c-structure` を切り、統合ブランチへマージ。`main` へは人間の判断後に lead が統合する。
- **評価用 APK を本番と共存させる**: 統合ブランチの `app/build.gradle.kts` に評価専用の build type（例: `m3eval`、`initWith(debug)`、`applicationIdSuffix = ".m3eval"`、`resValue("string", "app_name", "Chunk Flow M3")`、アイコンの色違い）を追加し、`./gradlew :app:assembleM3eval` で配布する。
  - 利点: 既存の Chunk Flow と**同じ端末に並べて比較**できる。
  - 注意: applicationId が異なるためデータ（Room / DataStore / SAF の永続権限）は共有されない。評価時はフォルダの再取り込みが必要。実教材で比較する場合は同じフォルダを両方から参照する（コピーしないため容量は増えない）。
  - 代替: `main` と同じ applicationId で上書きインストールする（データは引き継げるが、戻すときに DB バージョンに注意）。**データ移行の互換性を保つため、M3 ブランチでは Room スキーマと DataStore のキーを変更しない**ことをルールにする。
- **デバッグ用の比較スイッチは作らない**（新旧 UI を 1 つの APK に同居させると両方のコードを保守することになる）。

### 5.3 役割分担（AGENTS.md §3 に従う）
| 作業 | 担当 |
| --- | --- |
| テーマ（Typography / Shapes / 拡張色 / 調和）、`core:designsystem` の共通部品（`ChunkFlowListItem`、`TransportButtons`、`SnackbarHost` の作法） | ui |
| 各画面の置換（library / player / settings を別 worktree で並列） | ui ×3（`invoke_subagent` の `Workspace=share`） |
| `material3-adaptive` / `adaptive-navigation-suite` / `adaptive-layout`、Material Symbols、色の調和ライブラリの**依存追加**、build type の追加、Navigation 3 の Scene | lead |
| 設計書 §7.5 の M3 版、ADR（「M3 Expressive の採用範囲」「下部ナビゲーションとミニプレイヤー」） | lead |
| 各 Phase の品質ゲート、基準画像差分のレビュー補助、手動テスト | qa |

### 5.4 事前に人間の判断が必要なこと
1. **下部ナビゲーションに移行するか**（S1）。移行する場合、ブックマークタブの専用コントローラー（§7.5.2 E）をどう扱うか（S2）。
2. **言語パレットの固定色を保つか、ダイナミックカラーに調和させるか**（C6）。学習上「青 = 母語、ピンク = 学習言語」の記憶が重要なら、調和の強さを弱める（色相を少しだけ寄せる）。
3. **M3 Expressive（Experimental API）を採用するか**（Phase D、B1 の `ButtonGroup` / `SplitButton`）。安定 API のみで進める場合は B1 を `SegmentedButton` + `IconToggleButton`、B2 を「ボタン + `DropdownMenu`」で代替する。
4. **書体**（T1）。既定の Roboto / システム日本語フォントのままか、Noto Sans JP 等を同梱するか（APK サイズ増）。
5. 評価用 APK の方式（applicationId を分けるか、上書きか。§5.2）。

### 5.5 リスクと対策
| リスク | 対策 |
| --- | --- |
| 構造変更（Phase C）で既存ユーザー（= 本人）の操作記憶が切れる | Phase C は評価 APK で 1〜2 週間使ってから判断。戻せるようブランチを分けたまま保つ。 |
| Expressive API が Experimental で、将来のシグネチャ変更がある | 使用箇所を `core:designsystem` のラッパー（`ChunkFlowToggleButton` など）に閉じ込め、置換点を 1 箇所にする。 |
| 基準画像の大量更新でレビューが形骸化する | Phase ごとに画面単位でコミットを分け、差分画像（`build/reports/screenshotTest/`）を report に添える。 |
| 言語色の調和で母語 / 学習言語が見分けにくくなる | 調和後の 2 色の色相差（HCT の hue 差 ≥ 60°）とコントラスト比をテストで固定する。 |
| 長押し経路を減らしたことで片手操作が悪化する | 長押しは**残す**（削除しない）。可視経路を「追加」するだけにする。 |

---

## 6. 参考: 画面別の所見（基準画像より）

| 画面 | 基準画像 | 所見 |
| --- | --- | --- |
| ライブラリ（フォルダ） | `feature/library/src/screenshotTestDebug/reference/.../LibraryScreenPopulatedScreenshot_Phone_*.png` | 上部タブ、全面着色のフォルダカード、エラーバナー（自作）、ミニプレイヤー。M3 の List + Navigation bar 構成へ寄せる余地が最も大きい。 |
| ライブラリ（ブックマーク） | `.../LibraryScreenBookmarksTabScreenshot_Phone_*.png` | アコーディオンカードは M3 に近い。下部のフル操作コントローラーはプレイヤーと同じ部品（チップ + 楕円ボタン）で、C1〜C4 の対象。 |
| プレイヤー（再生中） | `feature/player/src/screenshotTestDebug/reference/.../PlayerScreenPlayingScreenshot_Phone_*.png` | スクリプト行の言語色・バッジは良い。下部の再生操作ストリップ（チップ）と楕円ボタン群が M3 の部品体系から外れる。波形カードは独自領域として妥当。 |
| 設定（再生設定） | `feature/settings/src/screenshotTestDebug/reference/.../SettingsScreenDefaultScreenshot_Phone_*.png` | `SegmentedButton` / `Slider` は M3 準拠。`Card` 内のチップ群と `Checkbox` は `ListItem` + `Switch` へ。タブが右端で見切れる。 |

---

## 7. 次のアクション（承認後）

1. lead: §5.4 の判断を人間から受け取り、ADR（M3 採用範囲、下部ナビとミニプレイヤー）を書く。
2. lead: `agent/ui/m3-refresh` ブランチと評価用 build type を用意し、必要な依存（`material3-adaptive-navigation-suite`、`material3-adaptive-layout`、色の調和）を追加する。
3. ui: Phase A を実施 → qa のゲート → 評価 APK（A）。
4. ui ×3: Phase B を画面ごとに並列実施 → qa → 評価 APK（A+B）。**ここで一度、人間が使用感を評価**する。
5. 判断に応じて Phase C / D。
