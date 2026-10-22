package com.miuraken.chunkflow.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.offset
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowFolderPalette
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowFolderPalette
import com.miuraken.chunkflow.core.model.LibraryFolderColors

/** 色見本の円の直径。 */
private val SwatchDiameter = 36.dp

/** 色見本と ▼ のセルの高さ（タッチ領域の高さ）。 */
private val SwatchCellHeight = 48.dp

/** セルの幅の上限。幅の広い画面（タブレットなど）で、見本の間が開きすぎないようにする。 */
private val SwatchCellMaxWidth = 56.dp

/** 1 行のセルの数（色見本 6 個と、1 行目の右端の ▼ 1 個）。2 行目以降も同じ数にして、列を 1 行目とそろえる。 */
private const val CELLS_PER_ROW = LibraryFolderColors.COLLAPSED_SWATCH_COUNT + 1

/**
 * 見本の行を、親の余白の左側へはみ出させる幅。
 *
 * 幅 360dp の画面（シートの中身は 312dp）でも 7 個のセルをそれぞれ 48dp（タッチ領域の最小）にするため、
 * 左に 6dp・右に 18dp はみ出す（312 + 24 = 336 = 7 × 48dp）。1 列目の円の左端が中身の左端にそろい、
 * ▼ の三角の右端が中身の右端にほぼそろう。
 */
private val SwatchRowOverhangStart = 6.dp

/** 見本の行を、親の余白の右側へはみ出させる幅（[SwatchRowOverhangStart] を参照）。 */
private val SwatchRowOverhangEnd = 18.dp

/**
 * フォルダの設定のシートの色の欄（ADR 0013）。
 *
 * ふだんは 1 行に [LibraryFolderColors.COLLAPSED_SWATCH_COUNT] 色を並べ、行の右端の ▼ を押すと
 * [LibraryFolderColors.PALETTE_SIZE] 色（[LibraryFolderColors.EXPANDED_ROW_COUNT] 行）から選べるように広げる。
 *
 * - 見本の並び順は [LibraryFolderColors.orderedPaletteIndices]。基準にする選択は「シートを開いたときの選択」と
 *   「▼ で閉じたときの選択」で、開いている間に色を押しても見本の位置は動かない。隠れている行の色を選んで閉じると、
 *   その色が左上に来て、デフォルトの色も 1 行目に見える。
 * - 選んでいる色（[selectedIndex]。`null` ならデフォルトの色）の見本には、濃いチェックと太い枠を付ける。
 *   デフォルトから変えているときは、デフォルトの色の見本にグレーのチェックを付ける。
 * - デフォルトの色の見本を押すと `onSelect(null)`（対象言語の既定の色に戻す）、ほかの色は `onSelect(番号)`。
 *
 * 幅の狭い画面でも 48dp のタッチ領域を取るため、見本の行は左右に [SwatchRowOverhangStart] と
 * [SwatchRowOverhangEnd] だけはみ出して描く。親はその幅以上の左右の余白を持ち、その範囲を切り抜かないこと。
 *
 * @param selectedIndex フォルダに指定する色（パレットの番号）。`null` なら対象言語の既定の色。
 * @param defaultIndex 対象言語の既定の色（パレットの番号）。わからなければ `null`。
 * @param onSelect 色を選んだとき（`null` はデフォルトの色に戻す）。
 * @param initialExpanded はじめから全色を開いておくかどうか（プレビュー用）。
 */
@Composable
internal fun FolderColorPicker(
    selectedIndex: Int?,
    defaultIndex: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    initialExpanded: Boolean = false,
) {
    val palette = chunkFlowFolderPalette()
    var expanded by rememberSaveable { mutableStateOf(initialExpanded) }
    // 並び順の基準にする選択（シートを開いたときと、▼ で閉じたときに決める）
    var orderBasisIndex by rememberSaveable { mutableStateOf(selectedIndex) }
    val rows = remember(orderBasisIndex, defaultIndex) {
        LibraryFolderColors.orderedPaletteIndices(orderBasisIndex, defaultIndex)
            .chunked(LibraryFolderColors.COLLAPSED_SWATCH_COUNT)
    }
    val effectiveIndex = selectedIndex ?: defaultIndex
    val marksDefault = selectedIndex != null && selectedIndex != defaultIndex
    val onToggleExpanded: () -> Unit = {
        if (expanded) {
            orderBasisIndex = selectedIndex
        }
        expanded = !expanded
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .overhangHorizontally(start = SwatchRowOverhangStart, end = SwatchRowOverhangEnd)
            .selectableGroup(),
    ) {
        FolderColorSwatchRow(
            paletteIndices = rows.firstOrNull().orEmpty(),
            palette = palette,
            effectiveIndex = effectiveIndex,
            defaultIndex = defaultIndex,
            marksDefault = marksDefault,
            onSelect = onSelect,
        ) {
            FolderColorExpandToggle(
                expanded = expanded,
                onToggle = onToggleExpanded,
                modifier = Modifier.weight(1f),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                rows.drop(1).forEach { rowIndices ->
                    FolderColorSwatchRow(
                        paletteIndices = rowIndices,
                        palette = palette,
                        effectiveIndex = effectiveIndex,
                        defaultIndex = defaultIndex,
                        marksDefault = marksDefault,
                        onSelect = onSelect,
                    ) {
                        // 1 行目の ▼ の列。列を 1 行目とそろえるために空けておく
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 色見本 1 行（[CELLS_PER_ROW] 個のセルを同じ幅で並べ、最後のセルに [trailing] を置く）。 */
@Composable
private fun FolderColorSwatchRow(
    paletteIndices: List<Int>,
    palette: ChunkFlowFolderPalette,
    effectiveIndex: Int?,
    defaultIndex: Int?,
    marksDefault: Boolean,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .widthIn(max = SwatchCellMaxWidth * CELLS_PER_ROW)
            .fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        paletteIndices.forEach { index ->
            val isDefault = index == defaultIndex
            FolderColorSwatch(
                color = palette.containerOrNull(index) ?: MaterialTheme.colorScheme.surfaceVariant,
                selected = index == effectiveIndex,
                showsDefaultCheck = marksDefault && isDefault,
                contentDescription = if (isDefault) {
                    stringResource(R.string.library_folder_color_swatch_default_cd, index + 1)
                } else {
                    stringResource(R.string.library_folder_color_swatch_cd, index + 1)
                },
                onClick = { onSelect(if (isDefault) null else index) },
                modifier = Modifier.weight(1f),
            )
        }
        // 色数が 1 行に満たない行も、列を 1 行目とそろえる
        repeat(LibraryFolderColors.COLLAPSED_SWATCH_COUNT - paletteIndices.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
        trailing()
    }
}

/**
 * 色見本 1 つ（行の背景と同じ色の円）。セル全体（高さ 48dp）がタッチ領域。
 *
 * 選択中は枠を太くして濃いチェックを出す。[showsDefaultCheck] のとき（デフォルトから変えているときのデフォルトの色）は、
 * 枠はそのままでグレーのチェックを出す。
 */
@Composable
private fun FolderColorSwatch(
    color: Color,
    selected: Boolean,
    showsDefaultCheck: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(SwatchCellHeight)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = ripple(bounded = false, radius = SwatchCellHeight / 2),
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .widthIn(max = SwatchDiameter)
                .aspectRatio(1f)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected || showsDefaultCheck) {
                Icon(
                    imageVector = ChunkFlowIcons.Check,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 色を全色に広げる / 1 行に戻す ▼（開いているときは上向き）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderColorExpandToggle(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionDescription = stringResource(
        if (expanded) R.string.library_folder_color_collapse_cd else R.string.library_folder_color_expand_cd,
    )
    val stateText = stringResource(
        if (expanded) R.string.library_folder_color_expanded_state else R.string.library_folder_color_collapsed_state,
    )
    Box(
        modifier = modifier
            .height(SwatchCellHeight)
            .clickable(
                interactionSource = null,
                indication = ripple(bounded = false, radius = SwatchCellHeight / 2),
                role = Role.Button,
                onClick = onToggle,
            )
            .semantics {
                contentDescription = actionDescription
                stateDescription = stateText
                if (expanded) {
                    collapse {
                        onToggle()
                        true
                    }
                } else {
                    expand {
                        onToggle()
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
    }
}

/**
 * 子を左右に [start] / [end] だけ広げて測り、広げた分を親の余白にはみ出して描く（自分の幅は変えない）。
 * レイアウトの向き（RTL）では左右を入れ替える。
 */
private fun Modifier.overhangHorizontally(start: Dp, end: Dp): Modifier = layout { measurable, constraints ->
    val startPx = start.roundToPx()
    val endPx = end.roundToPx()
    val placeable = measurable.measure(constraints.offset(horizontal = startPx + endPx))
    val width = (placeable.width - startPx - endPx).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(width, placeable.height) {
        placeable.placeRelative(-startPx, 0)
    }
}

/** 隠れている行の色（番号 9）を選んでシートを開いた状態（左上に来て、デフォルトの色にグレーのチェック）。 */
@ChunkFlowPreviews
@Composable
private fun FolderColorPickerPreview() {
    ChunkFlowTheme {
        Surface {
            FolderColorPicker(
                selectedIndex = 9,
                defaultIndex = 3,
                onSelect = {},
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
    }
}

/** ▼ で 18 色に広げた状態。 */
@ChunkFlowPreviews
@Composable
private fun FolderColorPickerExpandedPreview() {
    ChunkFlowTheme {
        Surface {
            FolderColorPicker(
                selectedIndex = 9,
                defaultIndex = 3,
                onSelect = {},
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                initialExpanded = true,
            )
        }
    }
}
