package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowFolderPalette

/** 色見本を 1 行に並べる数（8 色を 4 色ずつ 2 行にする）。 */
private const val SWATCHES_PER_ROW = 4

/**
 * フォルダの設定のシートの「フォルダの色」欄（ADR 0013）。
 *
 * 決め打ちのパレットの色見本と「デフォルトに戻す」を並べる。色を指定していないとき（[selectedIndex] が `null`）は、
 * 対象言語の既定の色（[defaultIndex]）の見本を選択中として表示する。
 *
 * @param selectedIndex フォルダに指定する色（パレットの番号）。`null` なら対象言語の既定の色。
 * @param defaultIndex 対象言語の既定の色（パレットの番号）。わからなければ `null`。
 * @param onSelect 色を選んだとき（`null` は「デフォルトに戻す」）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolderColorPicker(
    selectedIndex: Int?,
    defaultIndex: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = chunkFlowFolderPalette()
    val effectiveIndex = selectedIndex ?: defaultIndex
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.library_folder_color_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onSelect(null) },
                enabled = selectedIndex != null,
            ) {
                Text(text = stringResource(R.string.library_folder_color_reset))
            }
        }
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            maxItemsInEachRow = SWATCHES_PER_ROW,
        ) {
            palette.containers.forEachIndexed { index, color ->
                val isLanguageDefault = selectedIndex == null && index == defaultIndex
                val swatchCd = if (isLanguageDefault) {
                    stringResource(R.string.library_folder_color_swatch_default_cd, index + 1)
                } else {
                    stringResource(R.string.library_folder_color_swatch_cd, index + 1)
                }
                FolderColorSwatch(
                    color = color,
                    selected = index == effectiveIndex,
                    contentDescription = swatchCd,
                    onClick = { onSelect(index) },
                )
            }
        }
        Text(
            text = stringResource(
                if (selectedIndex == null) {
                    R.string.library_folder_color_default_hint
                } else {
                    R.string.library_folder_color_custom_hint
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 色見本 1 つ（48dp のタッチ領域に、行の背景と同じ色の円を描く）。選択中は枠を太くしてチェックを出す。 */
@Composable
private fun FolderColorSwatch(
    color: Color,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = ChunkFlowIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@ChunkFlowPreviews
@Composable
private fun FolderColorPickerPreview() {
    ChunkFlowTheme {
        Surface {
            FolderColorPicker(
                selectedIndex = 3,
                defaultIndex = 0,
                onSelect = {},
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
