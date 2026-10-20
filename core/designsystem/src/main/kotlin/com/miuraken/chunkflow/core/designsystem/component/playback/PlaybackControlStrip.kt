package com.miuraken.chunkflow.core.designsystem.component.playback

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.R
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowSplitButton
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowSplitButtonMenuItem
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowToggleButtonGroup
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowToggleButtonItem
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.formatCompactSpeed
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.PracticeSettings
import kotlin.math.abs

/**
 * 再生操作のストリップ（プレイヤーの再生ボタン類のすぐ上、ライブラリのブックマーク再生コントローラーで共有）。
 *
 * M3 Expressive の部品で構成する（計画書 §3.4、調査 §3.2 C1 / C2）。表示項目（左から）:
 * 1. `[1x | {指定速度} ▼]` — 排他の速度選択。「1x」は [ChunkFlowToggleButtonGroup]、指定速度は
 *    [ChunkFlowSplitButton]（短押しで指定速度に切り替え、▼ と長押しで速度設定シート）。
 * 2. `[リピート ▼]` — [ChunkFlowSplitButton]（短押しで ON / OFF、▼ のメニューと長押しで「全体リピート」の確認）。
 * 3. `[無音スキップ]` — [ChunkFlowToggleButtonGroup]（再生設定の「チャンク間の無音をスキップ」と同期）。
 * 4. `[再生設定]` — `IconButton`。
 *
 * 長押しの操作には `TooltipBox` で説明を添える。引数は旧 Chip 版と互換（feature 側の呼び出しは変えない）。
 * 状態を受け取って描くだけで、ViewModel や feature の UiState は受け取らない。
 *
 * @param speed 現在の再生速度。
 * @param customSpeed 「指定速度」ボタンが表す速度。
 * @param practice リピート / 無音スキップの状態を読み取る練習設定（[PracticeSettings.loop]、
 *   [PracticeSettings.repeatCheckedOnly]、[PracticeSettings.repeatWholeFile]、[PracticeSettings.skipOriginalSilence]）。
 * @param onSetSpeed 速度ボタンが押されたとき（`1.0f` または [customSpeed]）。
 * @param onOpenSpeedSheet 「指定速度」の ▼ または長押しで速度設定シートを開くとき。
 * @param onToggleRepeat 「リピート」が押されたとき。
 * @param onEnableWholeFileRepeat 「全体リピート」の確認ダイアログで「Yes」が押されたとき。
 * @param onToggleSkipSilence 「無音スキップ」が押されたとき。
 * @param onOpenPlaybackSettings 「再生設定」が押されたとき。
 * @param modifier 一番外側の [Row] に適用する Modifier。
 */
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
        // 1. 速度（排他）: [1x] + [{指定速度} ▼]
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChunkFlowToggleButtonGroup(
                compact = true,
                items = listOf(
                    ChunkFlowToggleButtonItem(
                        label = "1x",
                        checked = isNormalSpeed,
                        onCheckedChange = { if (it) onSetSpeed(1.0f) },
                    ),
                ),
            )
            LongPressTooltip(text = stringResource(R.string.designsystem_playback_speed_tooltip)) {
                ChunkFlowSplitButton(
                    label = formatCompactSpeed(customSpeed),
                    onClick = { onSetSpeed(customSpeed) },
                    checked = isCustomSpeedSelected,
                    onLongClick = onOpenSpeedSheet,
                    onMenuClick = onOpenSpeedSheet,
                    menuContentDescription = stringResource(R.string.designsystem_playback_speed_menu_cd),
                    compact = true,
                )
            }
        }

        // 2. リピート: [リピート ▼]（▼ と長押しで「全体リピート」）
        LongPressTooltip(text = stringResource(R.string.designsystem_playback_repeat_tooltip)) {
            ChunkFlowSplitButton(
                label = if (isWholeFileRepeat) {
                    stringResource(R.string.designsystem_playback_chip_repeat_whole_file)
                } else {
                    stringResource(R.string.designsystem_playback_chip_repeat)
                },
                onClick = onToggleRepeat,
                checked = isRepeatOn,
                icon = if (isWholeFileRepeat) ChunkFlowIcons.Repeat else null,
                onLongClick = { showWholeFileRepeatDialog = true },
                menuItems = listOf(
                    ChunkFlowSplitButtonMenuItem(
                        label = stringResource(R.string.designsystem_playback_chip_repeat_whole_file),
                        icon = ChunkFlowIcons.Repeat,
                        checked = isWholeFileRepeat,
                        onClick = { showWholeFileRepeatDialog = true },
                    ),
                ),
                menuContentDescription = stringResource(R.string.designsystem_playback_repeat_menu_cd),
                compact = true,
            )
        }

        // 3. 無音スキップ（トグル）
        ChunkFlowToggleButtonGroup(
            compact = true,
            items = listOf(
                ChunkFlowToggleButtonItem(
                    label = stringResource(R.string.designsystem_playback_chip_skip_silence),
                    checked = isSkipSilenceOn,
                    onCheckedChange = { onToggleSkipSilence() },
                ),
            ),
        )

        // 4. 再生設定
        IconButton(onClick = onOpenPlaybackSettings) {
            Icon(
                imageVector = ChunkFlowIcons.Settings,
                contentDescription = stringResource(R.string.designsystem_playback_chip_playback_settings),
                modifier = Modifier.size(24.dp),
            )
        }
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

/** 長押しの説明を `PlainTooltip` で添える（長押し / ホバー / フォーカスで表示）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LongPressTooltip(
    text: String,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
        content = content,
    )
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
