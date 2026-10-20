package com.miuraken.chunkflow.core.designsystem.component.expressive

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme

/** [ChunkFlowTransportButton] の既定サイズ（M3 の中サイズ `FilledTonalIconButton`）。 */
val ChunkFlowTransportButtonDefaultSize: Dp = 56.dp

/**
 * トランスポート操作（巻き戻し / 前 / 次 / 早送りなど）の統一ボタン（計画書 §3.4、調査 §3.2 C3）。
 *
 * `FilledTonalIconButton`（48dp 以上）に、任意の `labelSmall` ラベル（例: `-3s`、`prev`）を下に添える。
 * プレイヤーの `TransportTonalButton` とライブラリの `BookmarkSeekStepButton` を置き換える。
 * 秒数などの意味は [contentDescription] に含め、ラベルは読み上げから外す（重複を避ける）。
 * 長押しメニュー（[onLongClick]）は補助的な近道として残す。
 *
 * @param icon アイコン。
 * @param contentDescription 読み上げ文言（必須。例: 「3 秒戻す」）。
 * @param onClick 押されたとき。
 * @param modifier 一番外側（ラベルを含む列）に適用する Modifier。
 * @param onLongClick 任意の長押し。
 * @param label 任意のラベル（`labelSmall`、`onSurfaceVariant`）。
 * @param enabled 操作できるかどうか。
 * @param size ボタンの一辺。48dp 以上を渡す。既定 [ChunkFlowTransportButtonDefaultSize]。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChunkFlowTransportButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    label: String? = null,
    enabled: Boolean = true,
    size: Dp = ChunkFlowTransportButtonDefaultSize,
) {
    val haptic = LocalHapticFeedback.current
    val colors = IconButtonDefaults.filledTonalIconButtonColors()
    val containerColor = if (enabled) colors.containerColor else colors.disabledContainerColor
    val contentColor = if (enabled) colors.contentColor else colors.disabledContentColor
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(color = containerColor)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = if (onLongClick != null) {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongClick()
                        }
                    } else {
                        null
                    },
                )
                .semantics {
                    role = Role.Button
                    this.contentDescription = contentDescription
                },
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(size * ICON_RATIO),
                )
            }
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

private const val ICON_RATIO = 0.43f

@ChunkFlowPreviews
@Composable
internal fun ChunkFlowTransportButtonPreview() {
    ChunkFlowTheme {
        Surface {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                ChunkFlowTransportButton(
                    icon = ChunkFlowIcons.SeekBack,
                    contentDescription = "3 秒戻す",
                    onClick = {},
                    label = "-3s",
                )
                ChunkFlowTransportButton(
                    icon = ChunkFlowIcons.SkipPrevious,
                    contentDescription = "前のチャンク",
                    onClick = {},
                    label = "prev",
                    enabled = false,
                )
                ChunkFlowTransportButton(
                    icon = ChunkFlowIcons.SkipNext,
                    contentDescription = "次のチャンク",
                    onClick = {},
                )
                ChunkFlowTransportButton(
                    icon = ChunkFlowIcons.SeekForward,
                    contentDescription = "5 秒進む",
                    onClick = {},
                    label = "+5s",
                    size = 48.dp,
                )
            }
        }
    }
}
