package com.miuraken.chunkflow.core.designsystem.component.feedback

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.R
import com.miuraken.chunkflow.core.designsystem.icon.ChunkFlowIcons
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme

/**
 * Snackbar のホスト（`SnackbarHost` の薄いラッパー。調査 §3.2 C8、計画書 Phase B B4）。
 *
 * `Scaffold(snackbarHost = { ChunkFlowSnackbarHost(hostState) })` に置き、取り込み完了・エクスポート完了・
 * 一時的なエラーなど **短い結果通知** を `hostState.showSnackbar(message, actionLabel)` で出す。
 * 持続的な警告（モデル未準備など）は従来どおり `Card` で表示し、Snackbar にしない。
 *
 * 配色は M3 既定（`inverseSurface` / `inverseOnSurface`、アクションは `inversePrimary`）。
 * メッセージの文言と表示する契機は feature 側（ViewModel の一回限りのイベント）で決める。
 *
 * @param hostState `remember { SnackbarHostState() }` で作り、ViewModel のイベントから `showSnackbar` を呼ぶ。
 * @param modifier ホストに適用する Modifier。
 */
@Composable
fun ChunkFlowSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data -> ChunkFlowSnackbar(data) },
    )
}

/**
 * [SnackbarData] を描く Snackbar。
 *
 * material3 1.5.0-beta01 の `Snackbar(snackbarData)` は、メッセージが 2 行になるときと `withDismissAction` のときに
 * アクションが本文に重なって描かれる（基準画像で確認）。そのため M3 既定の色・形はそのままに、
 * 本文・アクション・閉じるボタンを `Row` で並べる。material3 側が直ったら `Snackbar(snackbarData)` に戻す。
 */
@Composable
private fun ChunkFlowSnackbar(data: SnackbarData) {
    val visuals = data.visuals
    Snackbar(
        modifier = Modifier.padding(12.dp),
        shape = SnackbarDefaults.shape,
        containerColor = SnackbarDefaults.color,
        contentColor = SnackbarDefaults.contentColor,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = visuals.message,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val actionLabel = visuals.actionLabel
            if (actionLabel != null) {
                TextButton(
                    onClick = { data.performAction() },
                    colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                    modifier = Modifier.padding(start = 8.dp),
                ) {
                    Text(actionLabel)
                }
            }
            if (visuals.withDismissAction) {
                IconButton(
                    onClick = { data.dismiss() },
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = SnackbarDefaults.dismissActionContentColor,
                    ),
                ) {
                    Icon(
                        imageVector = ChunkFlowIcons.Close,
                        contentDescription = stringResource(R.string.designsystem_snackbar_dismiss_cd),
                    )
                }
            }
        }
    }
}

@ChunkFlowPreviews
@Composable
internal fun ChunkFlowSnackbarPreview() {
    ChunkFlowTheme {
        Surface {
            Column(modifier = Modifier.padding(4.dp)) {
                // SnackbarHost は非同期に表示されるため、プレビューでは Snackbar を直接描く
                ChunkFlowSnackbar(PreviewSnackbarData(message = "取り込みが完了しました", actionLabel = "開く"))
                ChunkFlowSnackbar(
                    PreviewSnackbarData(
                        message = "エクスポートに失敗しました",
                        actionLabel = null,
                        withDismissAction = true,
                    ),
                )
                ChunkFlowSnackbar(
                    PreviewSnackbarData(
                        message = "モデルの準備ができていないため、文字起こしを開始できませんでした。設定から確認してください。",
                        actionLabel = "設定",
                    ),
                )
            }
        }
    }
}

/** プレビュー用の静的な [SnackbarData]。 */
private class PreviewSnackbarData(
    message: String,
    actionLabel: String?,
    withDismissAction: Boolean = false,
) : SnackbarData {
    override val visuals: SnackbarVisuals = PreviewSnackbarVisuals(message, actionLabel, withDismissAction)

    override fun performAction() = Unit

    override fun dismiss() = Unit
}

private class PreviewSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    override val withDismissAction: Boolean,
) : SnackbarVisuals {
    override val duration: SnackbarDuration = SnackbarDuration.Short
}
