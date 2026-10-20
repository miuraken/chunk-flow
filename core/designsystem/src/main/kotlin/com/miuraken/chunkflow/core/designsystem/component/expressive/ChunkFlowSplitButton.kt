package com.miuraken.chunkflow.core.designsystem.component.expressive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.R
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme

/**
 * [ChunkFlowSplitButton] の ▼ メニューの 1 項目。
 *
 * @param label 項目の文言。
 * @param onClick 選ばれたとき。メニューは自動で閉じる。
 * @param icon 任意の先頭アイコン。
 * @param checked `true` のとき末尾にチェックを描く（現在の状態を示す）。
 * @param enabled 選べるかどうか。
 */
data class ChunkFlowSplitButtonMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    val checked: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * 主操作 + ▼ メニューの分割ボタン（M3 Expressive の `SplitButtonLayout` のラッパー。計画書 §3.4、調査 §3.2 C2）。
 *
 * 「短押しで切り替え、長押しで詳細」のように長押しが入口だった操作に、**可視の経路（▼）** を与えるための部品。
 * 主操作ボタンは [checked] でトグルの状態を表せる（[ChunkFlowToggleButtonGroup] と同じ配色: 選択時 `primary`）。
 * 長押しの近道は [onLongClick] で残せる。
 *
 * ▼ を押すと [menuItems] の `DropdownMenu` を開く。`DropdownMenu` では表せない内容（速度シートなど）を開きたいときは
 * [menuItems] を空にして [onMenuClick] を渡す（▼ の押下をそのまま通知する）。
 *
 * @param label 主操作ボタンの文言。
 * @param onClick 主操作ボタンが押されたとき。
 * @param modifier 一番外側の `SplitButtonLayout` に適用する Modifier。
 * @param checked 主操作がトグルで ON のとき `true`（配色と TalkBack の状態説明に反映）。
 * @param enabled 操作できるかどうか（▼ にも適用）。
 * @param icon 主操作ボタンの先頭アイコン。
 * @param onLongClick 主操作ボタンの長押し。
 * @param menuItems ▼ で開くメニューの項目。
 * @param onMenuClick ▼ が押されたときの通知（[menuItems] が空のときの開き先。省略時は何もしない）。
 * @param menuContentDescription ▼ ボタンの読み上げ文言。既定は「{label} のメニュー」。
 * @param compact `true` で左右の余白を詰める（[ChunkFlowToggleButtonGroup] の `compact` と揃える）。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChunkFlowSplitButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    checked: Boolean = false,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onLongClick: (() -> Unit)? = null,
    menuItems: List<ChunkFlowSplitButtonMenuItem> = emptyList(),
    onMenuClick: (() -> Unit)? = null,
    menuContentDescription: String = stringResourceMenuCd(label),
    compact: Boolean = false,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val colors = splitButtonColors(checked)
    val stateText = if (checked) {
        androidx.compose.ui.res.stringResource(R.string.designsystem_state_on)
    } else {
        androidx.compose.ui.res.stringResource(R.string.designsystem_state_off)
    }

    Box(modifier = modifier) {
        SplitButtonLayout(
            leadingButton = {
                val longClickModifier = if (onLongClick != null && enabled) {
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
                SplitButtonDefaults.LeadingButton(
                    onClick = onClick,
                    enabled = enabled,
                    colors = colors,
                    contentPadding = if (compact) CompactContentPadding else SplitButtonDefaults.SmallLeadingButtonContentPadding,
                    modifier = longClickModifier.semantics { stateDescription = stateText },
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(SplitButtonDefaults.LeadingIconSize),
                        )
                        Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    }
                    Text(text = label, maxLines = 1)
                }
            },
            trailingButton = {
                val rotation by animateFloatAsState(
                    targetValue = if (menuExpanded) 180f else 0f,
                    animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                    label = "splitButtonChevron",
                )
                SplitButtonDefaults.TrailingButton(
                    checked = menuExpanded,
                    onCheckedChange = { expanded ->
                        if (menuItems.isEmpty()) {
                            onMenuClick?.invoke()
                        } else {
                            menuExpanded = expanded
                        }
                    },
                    enabled = enabled,
                    colors = colors,
                    contentPadding = if (compact) {
                        CompactTrailingContentPadding
                    } else {
                        SplitButtonDefaults.SmallTrailingButtonContentPadding
                    },
                    modifier = Modifier
                        .then(if (compact) Modifier.width(CompactTrailingButtonWidth) else Modifier)
                        .semantics { contentDescription = menuContentDescription },
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier
                            .size(SplitButtonDefaults.TrailingIconSize)
                            .graphicsLayer { rotationZ = rotation },
                    )
                }
            },
        )
        if (menuItems.isNotEmpty()) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                menuItems.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(item.label) },
                        enabled = item.enabled,
                        leadingIcon = item.icon?.let { vector ->
                            { Icon(imageVector = vector, contentDescription = null) }
                        },
                        trailingIcon = if (item.checked) {
                            { Icon(imageVector = ChunkFlowIcons.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            menuExpanded = false
                            item.onClick()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun stringResourceMenuCd(label: String): String = androidx.compose.ui.res.stringResource(R.string.designsystem_split_button_menu_cd, label)

/** `compact` 時の ▼ ボタンの幅（既定の最小幅より狭くする。タップ領域は 48dp に自動で広がる）。 */
private val CompactTrailingButtonWidth = 36.dp

/** [ChunkFlowToggleButtonGroup]（`ToggleButtonDefaults.colors()`）と揃えた配色。未選択は `surfaceContainer`、選択は `primary`。 */
@Composable
private fun splitButtonColors(checked: Boolean): ButtonColors = if (checked) {
    ButtonDefaults.buttonColors()
} else {
    ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@ChunkFlowPreviews
@Composable
internal fun ChunkFlowSplitButtonPreview() {
    ChunkFlowTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // 未選択（カスタム速度）
                ChunkFlowSplitButton(
                    label = "1.5x",
                    onClick = {},
                    onMenuClick = {},
                )
                // 選択 + アイコン（リピート）
                ChunkFlowSplitButton(
                    label = "リピート",
                    onClick = {},
                    checked = true,
                    icon = ChunkFlowIcons.Repeat,
                    menuItems = listOf(
                        ChunkFlowSplitButtonMenuItem(label = "全体リピート", onClick = {}),
                    ),
                )
                // compact
                ChunkFlowSplitButton(
                    label = "0.75x",
                    onClick = {},
                    checked = true,
                    compact = true,
                    onMenuClick = {},
                )
                // 無効
                ChunkFlowSplitButton(
                    label = "無効",
                    onClick = {},
                    enabled = false,
                    onMenuClick = {},
                )
            }
        }
    }
}
