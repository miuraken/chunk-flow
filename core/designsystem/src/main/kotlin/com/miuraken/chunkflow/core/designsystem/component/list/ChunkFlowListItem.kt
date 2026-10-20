package com.miuraken.chunkflow.core.designsystem.component.list

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme

/**
 * リスト行（M3 `ListItem` のラッパー。計画書 §3.3、Phase C でライブラリの行に使う）。
 *
 * 「先頭（サムネイル / アイコン）・本文・補足・末尾の操作 1 つ」という M3 のリスト行の型に揃える。
 * 末尾に複数の操作を並べたいときは、末尾を `⋮` の `IconButton` 1 つにしてメニューに収める（調査 §3.1）。
 * 再生の進み具合などは、任意の細い進捗バー（[progress]）を補足の下に描く。
 *
 * 行のクリックや長押しは [modifier] で付ける（`Modifier.combinedClickable(...)` など）。
 * 状態を受け取って描くだけで、feature の UiState や ViewModel は受け取らない。
 *
 * @param headline 本文（1 行目）。
 * @param modifier 一番外側の行に適用する Modifier。
 * @param overline 本文の上に置く小さな見出し（任意）。
 * @param supporting 補足（本文の下。任意。最大 2 行）。
 * @param leadingContent 先頭に置く内容（サムネイル、アイコン、言語バッジなど。任意）。
 * @param trailingContent 末尾に置く内容。`IconButton` 1 つ、または短いテキスト（任意）。
 * @param progress 補足の下に描く細い進捗バーの値（0.0〜1.0）。`null` で描かない。
 *   高頻度に変わる値（再生位置）には使わず、保存済みの進み具合など静的な値に使う。
 * @param progressColor 進捗バーの色。既定は `primary`。
 * @param containerColor 行の背景色。既定は `ListItemDefaults.containerColor`（透明）。
 *   選択状態を示すときは `secondaryContainer` などを渡す。
 */
@Composable
fun ChunkFlowListItem(
    headline: String,
    modifier: Modifier = Modifier,
    overline: String? = null,
    supporting: String? = null,
    leadingContent: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    progress: Float? = null,
    progressColor: Color = MaterialTheme.colorScheme.primary,
    containerColor: Color = ListItemDefaults.containerColor,
) {
    ListItem(
        headlineContent = {
            Text(text = headline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
        overlineContent = overline?.let { text ->
            { Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        supportingContent = if (supporting != null || progress != null) {
            {
                Column {
                    if (supporting != null) {
                        Text(text = supporting, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                                .height(ProgressBarHeight),
                            color = progressColor,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            strokeCap = StrokeCap.Round,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    }
                }
            }
        } else {
            null
        },
        leadingContent = leadingContent,
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = containerColor),
    )
}

/** [ChunkFlowListItem] の進捗バーの太さ。 */
private val ProgressBarHeight = 3.dp

@ChunkFlowPreviews
@Composable
internal fun ChunkFlowListItemPreview() {
    ChunkFlowTheme {
        Surface {
            Column {
                // 1 行（アイコン + 末尾の操作）
                ChunkFlowListItem(
                    headline = "Lesson 12 — At the station",
                    leadingContent = { Icon(ChunkFlowIcons.AudioFile, contentDescription = null) },
                    trailingContent = {
                        IconButton(onClick = {}) { Icon(ChunkFlowIcons.MoreHoriz, contentDescription = "その他") }
                    },
                )
                // 2 行 + 進捗
                ChunkFlowListItem(
                    headline = "Podcast #204 とても長いタイトルは省略記号で切り詰められる",
                    supporting = "12:34 / 45:10 ・ 3 日前",
                    progress = 0.28f,
                    leadingContent = {
                        Box(
                            Modifier
                                .size(40.dp)
                                .padding(0.dp),
                        ) {
                            Icon(ChunkFlowIcons.MusicNote, contentDescription = null, modifier = Modifier.size(40.dp))
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = {}) { Icon(ChunkFlowIcons.Favorite, contentDescription = "お気に入り") }
                    },
                )
                // オーバーライン + 補足 2 行、選択状態
                ChunkFlowListItem(
                    headline = "Shadowing set A",
                    overline = "再生リスト",
                    supporting = "24 ファイル ・ 2 時間 10 分\n最終再生: 昨日",
                    progress = 1f,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    leadingContent = { Icon(ChunkFlowIcons.QueueMusic, contentDescription = null) },
                    trailingContent = { Text("24", style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
}
