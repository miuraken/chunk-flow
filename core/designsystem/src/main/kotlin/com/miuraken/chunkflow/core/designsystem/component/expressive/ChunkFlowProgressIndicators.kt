package com.miuraken.chunkflow.core.designsystem.component.expressive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme

/**
 * 不確定の読み込み表示（M3 Expressive の `LoadingIndicator` のラッパー。計画書 §3.4）。
 *
 * 文字起こしの準備中・モデルの読み込み中など、進捗の割合がわからない待ち時間に使う。
 * 進捗がわかるときは [ChunkFlowWavyLinearProgress] を使う。
 * 既定サイズは 48dp（`LoadingIndicatorDefaults.ContainerSize`）。
 *
 * @param modifier 一番外側に適用する Modifier。
 * @param color 形の色。既定は `primary`。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChunkFlowLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LoadingIndicator(modifier = modifier, color = color)
}

/**
 * 波形の線形進捗バー（M3 Expressive の `LinearWavyProgressIndicator` のラッパー。計画書 §3.4）。
 *
 * 文字起こし・取り込みなど、割合がわかる進捗に使う。進捗は高頻度で変わり得るため **ラムダで受け取り**、
 * 描画フェーズでだけ読む（コンポーズ中に読まない。§6.2）。
 *
 * @param progress 0.0〜1.0 の進捗を返すラムダ。範囲外は丸められる。
 * @param modifier 一番外側に適用する Modifier（幅は呼び出し側で `fillMaxWidth()` などを指定）。
 * @param color 進捗部分の色。既定は `primary`。
 * @param trackColor 残り部分の色。既定は `secondaryContainer`。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChunkFlowWavyLinearProgress(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.secondaryContainer,
) {
    LinearWavyProgressIndicator(
        progress = { progress().coerceIn(0f, 1f) },
        modifier = modifier,
        color = color,
        trackColor = trackColor,
    )
}

@ChunkFlowPreviews
@Composable
internal fun ChunkFlowProgressIndicatorsPreview() {
    ChunkFlowTheme {
        Surface {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChunkFlowLoadingIndicator()
                    Text("読み込み中", style = MaterialTheme.typography.bodyMedium)
                }
                ChunkFlowWavyLinearProgress(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                ChunkFlowWavyLinearProgress(progress = { 0.35f }, modifier = Modifier.fillMaxWidth())
                ChunkFlowWavyLinearProgress(progress = { 1f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
