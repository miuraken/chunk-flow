package com.miuraken.chunkflow.core.designsystem.component.playback

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.R
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.formatCompactSpeed
import com.miuraken.chunkflow.core.model.ItemState
import kotlin.math.abs
import kotlin.math.roundToInt

/** [SpeedSheetContent] の ± ボタンで変える速度の幅（ボタンのラベル `±0.05x` と一致させる）。 */
private const val SPEED_STEP: Float = 0.05f

/**
 * 「指定速度」を調整するボトムシートの中身（プレイヤーとライブラリのブックマーク再生コントローラーで共有）。
 *
 * 上から: （任意で）「ベース言語のみ別の速度で再生する」、タイトルと現在値、`-0.05x` / `+0.05x` ボタン、
 * スライダー、プリセットのチップ、閉じるボタン。
 *
 * @param speed 現在の指定速度。
 * @param presets プリセットのチップに並べる速度の一覧。
 * @param closeLabel 右下のボタンの文言（呼び出し側の画面の語彙に合わせる: 「閉じる」「キャンセル」など）。
 * @param onSetSpeed スライダーまたはプリセットで速度が選ばれたとき。
 * @param onAdjustSpeed ± ボタンが押されたとき（差分 `±0.05f`）。
 * @param onClose 閉じるボタンが押されたとき。
 * @param modifier 一番外側の [Column] に適用する Modifier。
 * @param baseLanguageSpeedEnabled ベース言語のみ別の速度で再生する設定がオンかどうか。
 * @param baseLanguageSpeed ベース言語の再生速度。
 * @param onBaseLanguageSpeedEnabledChange ベース言語速度のオン・オフ切り替え時のコールバック。
 * @param onBaseLanguageSpeedChange ベース言語速度スライダー変更時のコールバック。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedSheetContent(
    speed: Float,
    presets: List<Float>,
    closeLabel: String,
    onSetSpeed: (Float) -> Unit,
    onAdjustSpeed: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    baseLanguageSpeedEnabled: Boolean = false,
    baseLanguageSpeed: Float = 1.0f,
    onBaseLanguageSpeedEnabledChange: ((Boolean) -> Unit)? = null,
    onBaseLanguageSpeedChange: ((Float) -> Unit)? = null,
) {
    val decreaseCd = stringResource(R.string.designsystem_playback_speed_decrease_cd)
    val increaseCd = stringResource(R.string.designsystem_playback_speed_increase_cd)
    val sliderCd = stringResource(R.string.designsystem_playback_speed_slider_cd)
    val showBaseLanguageSpeed =
        onBaseLanguageSpeedEnabledChange != null && onBaseLanguageSpeedChange != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showBaseLanguageSpeed) {
            val baseSpeedLabel = stringResource(R.string.designsystem_playback_base_language_speed_label)
            val baseSliderValue = baseLanguageSpeed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
            val baseSliderState = rememberSliderState(
                value = baseSliderValue,
                steps = ((ItemState.MAX_SPEED - ItemState.MIN_SPEED) / SPEED_STEP).roundToInt() - 1,
                trackRange = ItemState.MIN_SPEED..ItemState.MAX_SPEED,
            )
            LaunchedEffect(baseSliderState, baseSliderValue) {
                if (baseSliderState.value != baseSliderValue) {
                    baseSliderState.value = baseSliderValue
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Checkbox) {
                        onBaseLanguageSpeedEnabledChange?.invoke(!baseLanguageSpeedEnabled)
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = baseLanguageSpeedEnabled,
                        onCheckedChange = null,
                    )
                    Text(
                        text = baseSpeedLabel,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Surface(
                    color = if (baseLanguageSpeedEnabled) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    contentColor = if (baseLanguageSpeedEnabled) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = formatCompactSpeed(baseLanguageSpeed),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            Slider(
                state = baseSliderState,
                enabled = baseLanguageSpeedEnabled,
                onValueChange = { newValue ->
                    baseSliderState.value = newValue
                    onBaseLanguageSpeedChange?.invoke(newValue)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = baseSpeedLabel },
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (showBaseLanguageSpeed) {
                        R.string.designsystem_playback_default_speed_label
                    } else {
                        R.string.designsystem_playback_speed_section_title
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
            )
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = formatCompactSpeed(speed),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onAdjustSpeed(-SPEED_STEP) },
                enabled = speed > ItemState.MIN_SPEED + 0.001f,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = decreaseCd },
            ) {
                Text(text = "-0.05x")
            }
            OutlinedButton(
                onClick = { onAdjustSpeed(SPEED_STEP) },
                enabled = speed < ItemState.MAX_SPEED - 0.001f,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = increaseCd },
            ) {
                Text(text = "+0.05x")
            }
        }

        // 非推奨の Slider(value, onValueChange) ではなく SliderState を使う（material3 1.5）。
        // 外から速度が変わったとき（±0.05 ボタン・プリセット）は LaunchedEffect で state に同期する。
        val sliderValue = speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
        val sliderState = rememberSliderState(
            value = sliderValue,
            // 0.05 刻み（ItemState.MIN_SPEED〜MAX_SPEED）
            steps = ((ItemState.MAX_SPEED - ItemState.MIN_SPEED) / SPEED_STEP).roundToInt() - 1,
            trackRange = ItemState.MIN_SPEED..ItemState.MAX_SPEED,
        )
        LaunchedEffect(sliderState, sliderValue) {
            if (sliderState.value != sliderValue) {
                sliderState.value = sliderValue
            }
        }
        Slider(
            state = sliderState,
            onValueChange = { newValue ->
                sliderState.value = newValue
                onSetSpeed(newValue)
            },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = sliderCd },
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            presets.forEach { preset ->
                val isSelected = abs(speed - preset) < 0.01f
                val labelText = formatCompactSpeed(preset)
                FilterChip(
                    selected = isSelected,
                    onClick = { onSetSpeed(preset) },
                    label = { Text(text = labelText) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onClose) {
                Text(text = closeLabel)
            }
        }
    }
}

@ChunkFlowPreviews
@Composable
internal fun SpeedSheetContentPreview() {
    ChunkFlowTheme {
        Surface {
            SpeedSheetContent(
                speed = 1.25f,
                presets = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f),
                closeLabel = "閉じる",
                onSetSpeed = {},
                onAdjustSpeed = {},
                onClose = {},
            )
        }
    }
}
