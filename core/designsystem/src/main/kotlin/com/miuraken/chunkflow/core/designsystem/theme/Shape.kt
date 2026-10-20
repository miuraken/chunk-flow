package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * M3 の Shape scale（調査 §3.3 T2、計画書 §3.1）。
 *
 * 画面側の `RoundedCornerShape(n.dp)` の直書きは `MaterialTheme.shapes.*` に置き換える
 * （4 → `extraSmall`、8 → `small`、12 → `medium`、16 → `large`、28 → `extraLarge`）。
 */
val ChunkFlowShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
