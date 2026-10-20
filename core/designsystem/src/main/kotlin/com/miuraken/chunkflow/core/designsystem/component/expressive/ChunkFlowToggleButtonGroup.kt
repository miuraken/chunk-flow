package com.miuraken.chunkflow.core.designsystem.component.expressive

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme

/**
 * [ChunkFlowToggleButtonGroup] の 1 ボタン分の定義。
 *
 * @param label ボタンのラベル（必須。アイコンだけのときも TalkBack 用に読まれる）。
 * @param checked 選択されているかどうか。
 * @param onCheckedChange 押されたときに新しい選択状態を受け取る。単一選択にするときは呼び出し側で
 *   `true` のときだけ反映する（[ChunkFlowToggleButtonGroupPreview] の 1 段目を参照）。
 * @param icon 任意の先頭アイコン（装飾なので `contentDescription` は付けない）。
 * @param onLongClick 任意の長押し操作。長押しが主要機能の入口にならないよう、同じ操作への可視の経路
 *   （[ChunkFlowSplitButton] の ▼ など）を別に用意する（調査 §3.2 C2）。
 * @param enabled 操作できるかどうか。
 * @param showLabel `false` にするとアイコンだけを描く（[icon] が必須になる）。ラベルは `contentDescription` に回る。
 */
@Immutable
data class ChunkFlowToggleButtonItem(
    val label: String,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
    val icon: ImageVector? = null,
    val onLongClick: (() -> Unit)? = null,
    val enabled: Boolean = true,
    val showLabel: Boolean = true,
)

/**
 * 連結トグル群（M3 Expressive の `ButtonGroup` + `ToggleButton` のラッパー。計画書 §3.4、調査 §3.2 C1）。
 *
 * 再生操作のトグル（速度 / リピート / 無音スキップ）のように、**状態を持つ操作**を並べるときに使う。
 * Chip（フィルタ・入力用）の代わりになる部品で、単一選択（ラジオ）と複数トグルのどちらにも使える。
 * 選択の排他は部品では行わず、[ChunkFlowToggleButtonItem.onCheckedChange] の中で呼び出し側が決める。
 *
 * - 先頭 / 中央 / 末尾のボタンは `ButtonGroupDefaults.connected*ButtonShapes` で角を連結し、
 *   選択時は M3 既定（`primary` / `onPrimary`）の色になる。
 * - 幅が足りないときは、入りきらないボタンを `⋯` のオーバーフローメニューにまとめる（`ButtonGroup` 標準の挙動）。
 * - Expressive API の OptIn はこのファイルの中だけ。feature はこの関数だけを使う。
 *
 * @param items 左から順に並べるボタン。1 つ以上。
 * @param modifier 一番外側の `ButtonGroup` に適用する Modifier。
 * @param compact `true` で左右の余白を詰める（狭い横幅に多くの操作を並べるとき。高さと最小タップ領域は変えない）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChunkFlowToggleButtonGroup(
    items: List<ChunkFlowToggleButtonItem>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    require(items.isNotEmpty()) { "items must not be empty" }
    ButtonGroup(
        overflowIndicator = { menuState -> ButtonGroupDefaults.OverflowIndicator(menuState) },
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        items.forEachIndexed { index, item ->
            customItem(
                buttonGroupContent = {
                    val interactionSource = remember { MutableInteractionSource() }
                    ConnectedToggleButton(
                        item = item,
                        shapes = connectedShapesFor(index = index, count = items.size),
                        interactionSource = interactionSource,
                        compact = compact,
                        modifier = Modifier.animateWidth(interactionSource),
                    )
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        enabled = item.enabled,
                        leadingIcon = if (item.checked) {
                            { Icon(ChunkFlowIcons.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            item.onCheckedChange(!item.checked)
                            menuState.dismiss()
                        },
                    )
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun connectedShapesFor(index: Int, count: Int): ToggleButtonShapes = when {
    count == 1 -> ToggleButtonShapes(
        shape = ToggleButtonDefaults.shape,
        pressedShape = ToggleButtonDefaults.pressedShape,
        checkedShape = ToggleButtonDefaults.checkedShape,
    )

    index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()

    index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()

    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectedToggleButton(
    item: ChunkFlowToggleButtonItem,
    shapes: ToggleButtonShapes,
    interactionSource: MutableInteractionSource,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val onLongClick = item.onLongClick
    val longClickModifier = if (onLongClick != null && item.enabled) {
        // ToggleButton 自体は長押しを受け取らないため、外側で長押しだけを拾う。
        // 長押しが成立すると残りのイベントを消費するので、内側の toggleable のクリックは発火しない。
        Modifier.pointerInput(onLongClick) {
            detectTapGestures(
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            )
        }
    } else {
        Modifier
    }
    val semanticsModifier = if (!item.showLabel) {
        Modifier.semantics { stateDescription = item.label }
    } else {
        Modifier
    }
    ToggleButton(
        checked = item.checked,
        onCheckedChange = item.onCheckedChange,
        modifier = modifier
            .then(longClickModifier)
            .then(semanticsModifier),
        enabled = item.enabled,
        shapes = shapes,
        contentPadding = if (compact) CompactContentPadding else ButtonDefaults.ContentPadding,
        interactionSource = interactionSource,
    ) {
        val icon = item.icon
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = if (item.showLabel) null else item.label,
                modifier = Modifier.size(ToggleButtonDefaults.IconSize),
            )
        }
        if (item.showLabel) {
            if (icon != null) {
                Spacer(Modifier.size(ToggleButtonDefaults.IconSpacing))
            }
            Text(text = item.label, maxLines = 1)
        }
    }
}

/** [ChunkFlowToggleButtonGroup] と [ChunkFlowSplitButton] の `compact` 時の余白（既定 24dp → 12dp）。 */
internal val CompactContentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/** [ChunkFlowSplitButton] の `compact` 時の ▼ の余白。 */
internal val CompactTrailingContentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

@ChunkFlowPreviews
@Composable
internal fun ChunkFlowToggleButtonGroupPreview() {
    ChunkFlowTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 単一選択（速度）: 1x が選択
                ChunkFlowToggleButtonGroup(
                    items = listOf(
                        ChunkFlowToggleButtonItem(label = "1x", checked = true, onCheckedChange = {}),
                        ChunkFlowToggleButtonItem(label = "1.5x", checked = false, onCheckedChange = {}),
                    ),
                )
                // 複数トグル（アイコン付き）: リピート ON、無音スキップ OFF、無効
                ChunkFlowToggleButtonGroup(
                    items = listOf(
                        ChunkFlowToggleButtonItem(
                            label = "リピート",
                            checked = true,
                            onCheckedChange = {},
                            icon = ChunkFlowIcons.Repeat,
                        ),
                        ChunkFlowToggleButtonItem(
                            label = "無音スキップ",
                            checked = false,
                            onCheckedChange = {},
                            icon = ChunkFlowIcons.Bolt,
                        ),
                        ChunkFlowToggleButtonItem(
                            label = "無効",
                            checked = false,
                            onCheckedChange = {},
                            enabled = false,
                        ),
                    ),
                )
                // compact（余白を詰める）
                ChunkFlowToggleButtonGroup(
                    compact = true,
                    items = listOf(
                        ChunkFlowToggleButtonItem(label = "1x", checked = true, onCheckedChange = {}),
                        ChunkFlowToggleButtonItem(label = "1.5x", checked = false, onCheckedChange = {}),
                        ChunkFlowToggleButtonItem(label = "2x", checked = false, onCheckedChange = {}),
                    ),
                )
                // アイコンだけ
                ChunkFlowToggleButtonGroup(
                    items = listOf(
                        ChunkFlowToggleButtonItem(
                            label = "表示",
                            checked = true,
                            onCheckedChange = {},
                            icon = ChunkFlowIcons.Visibility,
                            showLabel = false,
                        ),
                        ChunkFlowToggleButtonItem(
                            label = "非表示",
                            checked = false,
                            onCheckedChange = {},
                            icon = ChunkFlowIcons.VisibilityOff,
                            showLabel = false,
                        ),
                    ),
                )
            }
        }
    }
}
