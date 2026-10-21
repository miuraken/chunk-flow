package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 上部バーの操作欄の幅を判定するときに、左のボタン（選択解除）とタイトルのために残す幅。 */
private val SelectionActionsReservedWidth = 160.dp

/**
 * 選択モードの上部バーの操作 1 つ分。
 *
 * @property icon アイコン。
 * @property label 名前（文字付きのボタンの文字。アイコンだけのボタンでは読み上げとツールチップに使う）。
 * @property onClick 押したとき。
 * @property iconTint アイコンの色（`null` ならボタンの文字の色）。
 * @property isDestructive 削除などの取り消せない操作かどうか（エラーの色にする）。
 */
internal data class LibrarySelectionAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val iconTint: Color? = null,
    val isDestructive: Boolean = false,
)

/**
 * 選択モードの上部バーの操作ボタン。
 *
 * 幅が足りるとき（タブレットなど）はアイコンと文字のボタン、足りないとき（スマートフォンや文字サイズが大きいとき）は
 * アイコンだけのボタン（長押しで名前のツールチップ、読み上げは名前）にする。上部バーの操作欄はバー全体の幅で測られる
 * ので、左のボタンとタイトルの分として [reservedWidth] を残して判定する。
 */
@Composable
internal fun LibrarySelectionActions(
    actions: List<LibrarySelectionAction>,
    modifier: Modifier = Modifier,
    reservedWidth: Dp = SelectionActionsReservedWidth,
) {
    SubcomposeLayout(modifier = modifier) { constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val labeledPlaceables = subcompose(SelectionActionsSlot.LABELED) {
            LabeledSelectionActionsRow(actions = actions)
        }.map { it.measure(Constraints(maxHeight = looseConstraints.maxHeight)) }
        val labeledWidth = labeledPlaceables.maxOfOrNull { it.width } ?: 0
        val availableWidth = if (constraints.hasBoundedWidth) {
            (constraints.maxWidth - reservedWidth.roundToPx()).coerceAtLeast(0)
        } else {
            Int.MAX_VALUE
        }
        val placeables = if (labeledWidth <= availableWidth) {
            labeledPlaceables
        } else {
            subcompose(SelectionActionsSlot.COMPACT) {
                CompactSelectionActionsRow(actions = actions)
            }.map { it.measure(looseConstraints) }
        }
        val width = placeables.maxOfOrNull { it.width } ?: 0
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            placeables.forEach { it.placeRelative(x = 0, y = (height - it.height) / 2) }
        }
    }
}

private enum class SelectionActionsSlot { LABELED, COMPACT }

/** アイコンと文字のボタンを並べる。 */
@Composable
private fun LabeledSelectionActionsRow(actions: List<LibrarySelectionAction>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        actions.forEach { action ->
            FilledTonalButton(
                onClick = action.onClick,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = if (action.isDestructive) {
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    )
                } else {
                    ButtonDefaults.filledTonalButtonColors()
                },
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = action.iconTint ?: LocalContentColor.current,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = action.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
        }
    }
}

/** アイコンだけのボタン（48dp のタッチ領域、長押しで名前のツールチップ）を並べる。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactSelectionActionsRow(actions: List<LibrarySelectionAction>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        actions.forEach { action ->
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
                tooltip = {
                    PlainTooltip { Text(text = action.label) }
                },
                state = rememberTooltipState(),
            ) {
                FilledTonalIconButton(
                    onClick = action.onClick,
                    colors = if (action.isDestructive) {
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    } else {
                        IconButtonDefaults.filledTonalIconButtonColors()
                    },
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                        tint = action.iconTint ?: LocalContentColor.current,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
