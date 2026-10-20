package com.miuraken.chunkflow.core.designsystem.component.expressive

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import com.miuraken.chunkflow.core.designsystem.R
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.util.rememberReduceMotion

/** [ChunkFlowPlayPauseButton] の既定サイズ（M3 の大サイズ `FilledIconButton` に相当）。 */
val ChunkFlowPlayPauseButtonDefaultSize: Dp = 80.dp

/**
 * 再生 / 一時停止の大ボタン（M3 Expressive の大サイズ `FilledIconButton` + shape morphing。計画書 §3.4、調査 §3.2 C3）。
 *
 * 停止中は **円（`MaterialShapes.Circle`）**、再生中は **クッキー形（`MaterialShapes.Cookie9Sided`）** になり、
 * 切り替え時に `Morph` で形が連続的に変わる。OS の「アニメーションを削除」（Animator duration scale = 0）のときは
 * 形を即時に切り替える（[rememberReduceMotion]）。
 *
 * @param isPlaying 再生中かどうか（`true` で一時停止アイコン + クッキー形）。
 * @param onClick 押されたとき（再生 ↔ 一時停止）。
 * @param modifier 一番外側のボタンに適用する Modifier。
 * @param onLongClick 任意の長押し（例: フローティング操作バーの表示切替）。
 * @param size ボタンの一辺。48dp 以上を渡す。既定 [ChunkFlowPlayPauseButtonDefaultSize]。
 * @param enabled 操作できるかどうか。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChunkFlowPlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    size: Dp = ChunkFlowPlayPauseButtonDefaultSize,
    enabled: Boolean = true,
) {
    val reduceMotion = rememberReduceMotion()
    val morph = remember {
        Morph(
            start = MaterialShapes.Circle.normalized(),
            end = MaterialShapes.Cookie9Sided.normalized(),
        )
    }
    val progress by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = if (reduceMotion) snap() else MaterialTheme.motionScheme.slowSpatialSpec(),
        label = "playPauseMorph",
    )
    val shape = remember(progress) { MorphShape(morph, progress) }
    val haptic = LocalHapticFeedback.current
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
    val contentDescription = if (isPlaying) {
        stringResource(R.string.designsystem_pause)
    } else {
        stringResource(R.string.designsystem_play)
    }

    FilledIconButton(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .then(longClickModifier),
        enabled = enabled,
        shape = shape,
    ) {
        Icon(
            imageVector = if (isPlaying) ChunkFlowIcons.Pause else ChunkFlowIcons.Play,
            contentDescription = contentDescription,
            modifier = Modifier.size(size * ICON_RATIO),
        )
    }
}

private const val ICON_RATIO = 0.45f

/**
 * [Morph] を進捗 [progress]（0.0〜1.0）で評価した [Shape]。
 * `MaterialShapes` の多角形は 0..1 に正規化されているので、描画サイズに拡大して使う。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private class MorphShape(
    private val morph: Morph,
    private val progress: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress = progress)
        val matrix = Matrix()
        matrix.scale(size.width, size.height)
        path.transform(matrix)
        return Outline.Generic(path)
    }
}

@ChunkFlowPreviews
@Composable
internal fun ChunkFlowPlayPauseButtonPreview() {
    ChunkFlowTheme {
        Surface {
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChunkFlowPlayPauseButton(isPlaying = false, onClick = {})
                ChunkFlowPlayPauseButton(isPlaying = true, onClick = {})
                ChunkFlowPlayPauseButton(isPlaying = false, onClick = {}, enabled = false, size = 56.dp)
            }
        }
    }
}
