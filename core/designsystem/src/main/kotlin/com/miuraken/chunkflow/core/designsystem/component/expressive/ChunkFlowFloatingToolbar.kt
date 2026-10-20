package com.miuraken.chunkflow.core.designsystem.component.expressive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme

/**
 * 水平のフローティングツールバー（M3 Expressive の `HorizontalFloatingToolbar` のラッパー。計画書 §3.4）。
 *
 * プレイヤーの「フローティング操作バー」（画面上を動かせる再生操作）の容れ物に使う。
 * ドラッグで動かす処理は呼び出し側が [modifier]（`offset` + `pointerInput`）で付ける（既存実装を再利用）。
 *
 * - [expanded] が `false` のときは [leadingContent] / [trailingContent] を畳み、[content] だけを残す。
 * - 配色は M3 の standard（`surfaceContainer`）。vibrant（`primaryContainer`）は [vibrant] で選ぶ。
 * - Expressive API の OptIn はこのファイルの中だけ。
 *
 * @param expanded 先頭・末尾のコンテンツを表示するかどうか。
 * @param modifier 一番外側のツールバーに適用する Modifier。
 * @param vibrant `true` で `primaryContainer` 系の配色にする。
 * @param leadingContent 先頭（左）に置く内容。[expanded] のときだけ表示。
 * @param trailingContent 末尾（右）に置く内容。[expanded] のときだけ表示。
 * @param content 常に表示する内容（通常は `IconButton` の列）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChunkFlowFloatingToolbar(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    vibrant: Boolean = false,
    leadingContent: (@Composable RowScope.() -> Unit)? = null,
    trailingContent: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    HorizontalFloatingToolbar(
        expanded = expanded,
        modifier = modifier,
        colors = if (vibrant) {
            FloatingToolbarDefaults.vibrantFloatingToolbarColors()
        } else {
            FloatingToolbarDefaults.standardFloatingToolbarColors()
        },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        content = content,
    )
}

@ChunkFlowPreviews
@Composable
internal fun ChunkFlowFloatingToolbarPreview() {
    ChunkFlowTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // 展開（先頭: ドラッグハンドル、末尾: 閉じる）
                ChunkFlowFloatingToolbar(
                    expanded = true,
                    leadingContent = {
                        Icon(ChunkFlowIcons.DragHandle, contentDescription = null)
                    },
                    trailingContent = {
                        IconButton(onClick = {}) { Icon(ChunkFlowIcons.Close, contentDescription = "閉じる") }
                    },
                ) {
                    IconButton(onClick = {}) { Icon(ChunkFlowIcons.SeekBack, contentDescription = "巻き戻し") }
                    IconButton(onClick = {}) { Icon(ChunkFlowIcons.SkipPrevious, contentDescription = "前のチャンク") }
                    IconButton(onClick = {}) { Icon(ChunkFlowIcons.Play, contentDescription = "再生") }
                    IconButton(onClick = {}) { Icon(ChunkFlowIcons.SkipNext, contentDescription = "次のチャンク") }
                    IconButton(onClick = {}) { Icon(ChunkFlowIcons.SeekForward, contentDescription = "早送り") }
                }
                // 畳んだ状態（vibrant）
                ChunkFlowFloatingToolbar(
                    expanded = false,
                    vibrant = true,
                    leadingContent = {
                        Icon(ChunkFlowIcons.DragHandle, contentDescription = null)
                    },
                ) {
                    IconButton(onClick = {}) { Icon(ChunkFlowIcons.Play, contentDescription = "再生") }
                }
            }
        }
    }
}
