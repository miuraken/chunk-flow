package com.miuraken.chunkflow.core.designsystem.component.playback

import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * ON / OFF のトグルに使う [androidx.compose.material3.FilterChip] のカラー設定。
 *
 * かつては未選択を透過で薄く描く独自配色だったが（調査 §3.2 C4）、M3 の State layer の規則に合わせて
 * **M3 既定の [FilterChipDefaults.filterChipColors]**（未選択: 透明 + 枠線、選択: `secondaryContainer`）を返す。
 *
 * 呼び出し側は `colors` 引数を省略すれば同じ結果になるため、この関数は不要。Phase B の `ButtonGroup` 化で削除する。
 */
@Deprecated(
    message = "M3 既定の配色になった。FilterChip の colors 引数を省略する（FilterChipDefaults.filterChipColors() と同じ）",
    replaceWith = ReplaceWith("FilterChipDefaults.filterChipColors()", "androidx.compose.material3.FilterChipDefaults"),
)
@Composable
fun brightnessToggleChipColors(): SelectableChipColors = FilterChipDefaults.filterChipColors()

/**
 * 全チャンクの文字起こしが完了したことを示すバッジ（緑丸 + 白抜きアイコン）の背景色。
 *
 * テーマに追従しない固定値。ダイナミックカラーに調和させた
 * [com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowExtendedColors.transcriptCompleted]
 * （アイコンは `onTranscriptCompleted`）を使う。
 */
@Deprecated(
    message = "chunkFlowExtendedColors().transcriptCompleted を使う（アイコンは onTranscriptCompleted）",
    replaceWith = ReplaceWith(
        "chunkFlowExtendedColors().transcriptCompleted",
        "com.miuraken.chunkflow.core.designsystem.theme.chunkFlowExtendedColors",
    ),
)
val CompletedTranscriptBadgeColor: Color = Color(0xFF19532B)
