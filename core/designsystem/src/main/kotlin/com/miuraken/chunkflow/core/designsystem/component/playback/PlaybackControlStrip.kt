package com.miuraken.chunkflow.core.designsystem.component.playback

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.R
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.formatCompactSpeed
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.PracticeSettings
import kotlin.math.abs

/**
 * 再生に関するチップの列（プレイヤーの再生ボタン類のすぐ上、ライブラリのブックマーク再生コントローラーで共有）。
 *
 * 表示項目（左から）:
 * 1. 「1x」（通常再生速度）
 * 2. 「{指定速度}」（短押しで指定速度に切り替え、長押しで速度設定シートを開く）
 * 3. 「リピート」（短押しで ON / OFF、長押しでファイル全体リピートの確認ダイアログ）
 * 4. 「無音スキップ」（再生設定の「チャンク間の無音をスキップ」と同期）
 * 5. 「再生設定」（⚙️ アイコン）
 *
 * 状態を受け取って描くだけで、ViewModel や feature の UiState は受け取らない。
 *
 * @param speed 現在の再生速度。
 * @param customSpeed 「指定速度」チップが表す速度。
 * @param practice リピート / 無音スキップの状態を読み取る練習設定（[PracticeSettings.loop]、
 *   [PracticeSettings.repeatCheckedOnly]、[PracticeSettings.repeatWholeFile]、[PracticeSettings.skipOriginalSilence]）。
 * @param onSetSpeed 速度チップが押されたとき（`1.0f` または [customSpeed]）。
 * @param onOpenSpeedSheet 「指定速度」チップが長押しされたとき。
 * @param onToggleRepeat 「リピート」チップが押されたとき。
 * @param onEnableWholeFileRepeat 確認ダイアログで「Yes」が押されたとき。
 * @param onToggleSkipSilence 「無音スキップ」チップが押されたとき。
 * @param onOpenPlaybackSettings 「再生設定」チップが押されたとき。
 * @param modifier 一番外側の [Row] に適用する Modifier。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaybackControlStrip(
    speed: Float,
    customSpeed: Float,
    practice: PracticeSettings,
    onSetSpeed: (Float) -> Unit,
    onOpenSpeedSheet: () -> Unit,
    onToggleRepeat: () -> Unit,
    onEnableWholeFileRepeat: () -> Unit,
    onToggleSkipSilence: () -> Unit,
    onOpenPlaybackSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isNormalSpeed = abs(speed - 1.0f) < 0.01f
    val isCustomSpeedSelected = !isNormalSpeed
    val isWholeFileRepeat = practice.repeatWholeFile
    val isRepeatOn = isWholeFileRepeat || practice.loop !is LoopMode.Off || practice.repeatCheckedOnly
    val isSkipSilenceOn = practice.skipOriginalSilence
    var showWholeFileRepeatDialog by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 1. 「1x」（通常再生速度）
        FilterChip(
            selected = isNormalSpeed,
            onClick = { onSetSpeed(1.0f) },
            label = {
                Text(
                    text = "1x",
                    fontWeight = if (isNormalSpeed) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            },
        )

        // 2. 「{指定速度}」（短押しで指定速度に切り替え、長押しで速度設定シートを開く）
        // 色は M3 の FilterChip 既定に揃える（未選択: 透明 + 枠線、選択: secondaryContainer。調査 §3.2 C4）。
        val customChipContainerColor = if (isCustomSpeedSelected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            Color.Transparent
        }
        val customChipContentColor = if (isCustomSpeedSelected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
        val customChipBorder = if (isCustomSpeedSelected) {
            null
        } else {
            FilterChipDefaults.filterChipBorder(enabled = true, selected = false)
        }
        Surface(
            shape = FilterChipDefaults.shape,
            color = customChipContainerColor,
            contentColor = customChipContentColor,
            border = customChipBorder,
            modifier = Modifier
                .heightIn(min = FilterChipDefaults.Height)
                .clip(FilterChipDefaults.shape)
                .combinedClickable(
                    onClick = { onSetSpeed(customSpeed) },
                    onLongClick = onOpenSpeedSheet,
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatCompactSpeed(customSpeed),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isCustomSpeedSelected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }

        // 3. 「リピート」（短押しでチャンク・全ファイルリピートの ON/OFF、長押しでファイル全体リピートの確認ダイアログ）
        val repeatChipContainerColor = when {
            isWholeFileRepeat -> wholeFileRepeatContainerColor()
            isRepeatOn -> MaterialTheme.colorScheme.secondaryContainer
            else -> Color.Transparent
        }
        val repeatChipContentColor = when {
            isWholeFileRepeat -> onWholeFileRepeatContainerColor()
            isRepeatOn -> MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        val repeatChipBorder = if (isRepeatOn) {
            null
        } else {
            FilterChipDefaults.filterChipBorder(enabled = true, selected = false)
        }
        Surface(
            shape = FilterChipDefaults.shape,
            color = repeatChipContainerColor,
            contentColor = repeatChipContentColor,
            border = repeatChipBorder,
            modifier = Modifier
                .heightIn(min = FilterChipDefaults.Height)
                .clip(FilterChipDefaults.shape)
                .combinedClickable(
                    onClick = onToggleRepeat,
                    onLongClick = { showWholeFileRepeatDialog = true },
                ),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // ファイル全体リピートは絵文字ではなく Icon で示す（調査 §3.2 C7）。
                if (isWholeFileRepeat) {
                    Icon(
                        imageVector = ChunkFlowIcons.Repeat,
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                }
                Text(
                    text = if (isWholeFileRepeat) {
                        stringResource(R.string.designsystem_playback_chip_repeat_whole_file)
                    } else {
                        stringResource(R.string.designsystem_playback_chip_repeat)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isRepeatOn) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            }
        }

        // 4. 「無音スキップ」（再生設定の「チャンク間の無音をスキップ」と同期）
        FilterChip(
            selected = isSkipSilenceOn,
            onClick = onToggleSkipSilence,
            label = {
                Text(
                    text = stringResource(R.string.designsystem_playback_chip_skip_silence),
                    fontWeight = if (isSkipSilenceOn) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                )
            },
        )

        // 5. 「再生設定」（⚙️ アイコン）
        AssistChip(
            onClick = onOpenPlaybackSettings,
            label = {
                Icon(
                    imageVector = ChunkFlowIcons.Settings,
                    contentDescription = stringResource(R.string.designsystem_playback_chip_playback_settings),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }

    if (showWholeFileRepeatDialog) {
        AlertDialog(
            onDismissRequest = { showWholeFileRepeatDialog = false },
            text = {
                Text(text = stringResource(R.string.designsystem_playback_whole_file_repeat_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWholeFileRepeatDialog = false
                        onEnableWholeFileRepeat()
                    },
                ) {
                    Text(text = stringResource(R.string.designsystem_playback_whole_file_repeat_yes))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showWholeFileRepeatDialog = false },
                ) {
                    Text(text = stringResource(R.string.designsystem_playback_whole_file_repeat_no))
                }
            },
        )
    }
}

@ChunkFlowPreviews
@Composable
internal fun PlaybackControlStripPreview() {
    ChunkFlowTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // すべて OFF（1x）
                PlaybackControlStrip(
                    speed = 1.0f,
                    customSpeed = 1.5f,
                    practice = PracticeSettings(),
                    onSetSpeed = {},
                    onOpenSpeedSheet = {},
                    onToggleRepeat = {},
                    onEnableWholeFileRepeat = {},
                    onToggleSkipSilence = {},
                    onOpenPlaybackSettings = {},
                )
                // 指定速度 + リピート + 無音スキップ ON
                PlaybackControlStrip(
                    speed = 1.5f,
                    customSpeed = 1.5f,
                    practice = PracticeSettings(
                        repeatCheckedOnly = true,
                        skipOriginalSilence = true,
                    ),
                    onSetSpeed = {},
                    onOpenSpeedSheet = {},
                    onToggleRepeat = {},
                    onEnableWholeFileRepeat = {},
                    onToggleSkipSilence = {},
                    onOpenPlaybackSettings = {},
                )
                // ファイル全体リピート ON
                PlaybackControlStrip(
                    speed = 0.75f,
                    customSpeed = 0.75f,
                    practice = PracticeSettings(repeatWholeFile = true),
                    onSetSpeed = {},
                    onOpenSpeedSheet = {},
                    onToggleRepeat = {},
                    onEnableWholeFileRepeat = {},
                    onToggleSkipSilence = {},
                    onOpenPlaybackSettings = {},
                )
            }
        }
    }
}
