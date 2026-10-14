package com.miuraken.chunkflow.core.designsystem.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Chunk Flow の画面で共通して使うベクターアイコン一式。
 */
object ChunkFlowIcons {
    /** 再生アイコン。 */
    val Play: ImageVector by lazy {
        materialIcon(name = "Play") {
            moveTo(8f, 5f)
            verticalLineToRelative(14f)
            lineToRelative(11f, -7f)
            close()
        }
    }

    /** 一時停止アイコン。 */
    val Pause: ImageVector by lazy {
        materialIcon(name = "Pause") {
            moveTo(6f, 19f)
            horizontalLineToRelative(4f)
            lineTo(10f, 5f)
            lineTo(6f, 5f)
            verticalLineToRelative(14f)
            close()
            moveTo(14f, 5f)
            verticalLineToRelative(14f)
            horizontalLineToRelative(4f)
            lineTo(18f, 5f)
            horizontalLineToRelative(-4f)
            close()
        }
    }

    /** 追加（取り込み）アイコン。 */
    val Add: ImageVector by lazy {
        materialIcon(name = "Add") {
            moveTo(19f, 13f)
            horizontalLineToRelative(-6f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-6f)
            lineTo(5f, 13f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(6f)
            lineTo(11f, 5f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(2f)
            close()
        }
    }

    /** 削除（ゴミ箱）アイコン。 */
    val Delete: ImageVector by lazy {
        materialIcon(name = "Delete") {
            moveTo(6f, 19f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(8f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            lineTo(18f, 7f)
            lineTo(6f, 7f)
            verticalLineToRelative(12f)
            close()
            moveTo(19f, 4f)
            horizontalLineToRelative(-3.5f)
            lineToRelative(-1f, -1f)
            horizontalLineToRelative(-5f)
            lineToRelative(-1f, 1f)
            lineTo(5f, 4f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(14f)
            lineTo(19f, 4f)
            close()
        }
    }

    /** 戻る矢印アイコン。 */
    val ArrowBack: ImageVector by lazy {
        materialIcon(name = "ArrowBack") {
            moveTo(20f, 11f)
            lineTo(7.83f, 11f)
            lineToRelative(5.59f, -5.59f)
            lineTo(12f, 4f)
            lineToRelative(-8f, 8f)
            lineToRelative(8f, 8f)
            lineToRelative(1.41f, -1.41f)
            lineTo(7.83f, 13f)
            lineTo(20f, 13f)
            verticalLineToRelative(-2f)
            close()
        }
    }

    /** 閉じるアイコン。 */
    val Close: ImageVector by lazy {
        materialIcon(name = "Close") {
            moveTo(19f, 6.41f)
            lineTo(17.59f, 5f)
            lineTo(12f, 10.59f)
            lineTo(6.41f, 5f)
            lineTo(5f, 6.41f)
            lineTo(10.59f, 12f)
            lineTo(5f, 17.59f)
            lineTo(6.41f, 19f)
            lineTo(12f, 13.41f)
            lineTo(17.59f, 19f)
            lineTo(19f, 17.59f)
            lineTo(13.41f, 12f)
            close()
        }
    }

    /** 巻き戻しアイコン。 */
    val SeekBack: ImageVector by lazy {
        materialIcon(name = "SeekBack") {
            moveTo(11f, 18f)
            lineTo(11f, 6f)
            lineToRelative(-8.5f, 6f)
            lineToRelative(8.5f, 6f)
            close()
            moveTo(11.5f, 12f)
            lineToRelative(8.5f, 6f)
            lineTo(20f, 6f)
            lineToRelative(-8.5f, 6f)
            close()
        }
    }

    /** 早送りアイコン。 */
    val SeekForward: ImageVector by lazy {
        materialIcon(name = "SeekForward") {
            moveTo(4f, 18f)
            lineToRelative(8.5f, -6f)
            lineTo(4f, 6f)
            verticalLineToRelative(12f)
            close()
            moveTo(13f, 6f)
            verticalLineToRelative(12f)
            lineToRelative(8.5f, -6f)
            lineTo(13f, 6f)
            close()
        }
    }

    /** 音声ファイル（音符）アイコン。 */
    val AudioFile: ImageVector by lazy {
        materialIcon(name = "AudioFile") {
            moveTo(12f, 3f)
            verticalLineToRelative(10.55f)
            curveToRelative(-0.59f, -0.34f, -1.27f, -0.55f, -2f, -0.55f)
            curveToRelative(-2.21f, 0f, -4f, 1.79f, -4f, 4f)
            reflectiveCurveToRelative(1.79f, 4f, 4f, 4f)
            reflectiveCurveToRelative(4f, -1.79f, 4f, -4f)
            lineTo(14f, 7f)
            horizontalLineToRelative(4f)
            lineTo(18f, 3f)
            horizontalLineToRelative(-6f)
            close()
        }
    }

    /** 前のチャンクへ移動アイコン。 */
    val SkipPrevious: ImageVector by lazy {
        materialIcon(name = "SkipPrevious") {
            moveTo(6f, 6f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(12f)
            lineTo(6f, 18f)
            close()
            moveTo(9.5f, 12f)
            lineTo(18f, 18f)
            lineTo(18f, 6f)
            close()
        }
    }

    /** 次のチャンクへ移動アイコン。 */
    val SkipNext: ImageVector by lazy {
        materialIcon(name = "SkipNext") {
            moveTo(6f, 18f)
            lineToRelative(8.5f, -6f)
            lineTo(6f, 6f)
            verticalLineToRelative(12f)
            close()
            moveTo(16f, 6f)
            verticalLineToRelative(12f)
            horizontalLineToRelative(2f)
            lineTo(18f, 6f)
            horizontalLineToRelative(-2f)
            close()
        }
    }

    /** リピートアイコン。 */
    val Repeat: ImageVector by lazy {
        materialIcon(name = "Repeat") {
            moveTo(7f, 7f)
            horizontalLineToRelative(10f)
            verticalLineToRelative(3f)
            lineToRelative(4f, -4f)
            lineToRelative(-4f, -4f)
            verticalLineToRelative(3f)
            lineTo(5f, 5f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(2f)
            lineTo(7f, 7f)
            close()
            moveTo(17f, 17f)
            lineTo(7f, 17f)
            verticalLineToRelative(-3f)
            lineToRelative(-4f, 4f)
            lineToRelative(4f, 4f)
            verticalLineToRelative(-3f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(-6f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(4f)
            close()
        }
    }

    /** タイマー（自動ポーズ）アイコン。 */
    val Timer: ImageVector by lazy {
        materialIcon(name = "Timer") {
            moveTo(15f, 1f)
            lineTo(9f, 1f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(6f)
            lineTo(15f, 1f)
            close()
            moveTo(11f, 14f)
            horizontalLineToRelative(2f)
            lineTo(13f, 8f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(6f)
            close()
            moveTo(19.03f, 7.39f)
            lineToRelative(1.42f, -1.42f)
            curveToRelative(-0.43f, -0.51f, -0.9f, -0.99f, -1.41f, -1.41f)
            lineToRelative(-1.42f, 1.42f)
            curveTo(16.07f, 4.74f, 14.12f, 4f, 12f, 4f)
            curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
            reflectiveCurveToRelative(4.02f, 9f, 9f, 9f)
            reflectiveCurveToRelative(9f, -4.03f, 9f, -9f)
            curveToRelative(0f, -2.12f, -0.74f, -4.07f, -1.97f, -5.61f)
            close()
            moveTo(12f, 20f)
            curveToRelative(-3.87f, 0f, -7f, -3.13f, -7f, -7f)
            reflectiveCurveToRelative(3.13f, -7f, 7f, -7f)
            reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
            reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f)
            close()
        }
    }

    /** 表示（目）アイコン。 */
    val Visibility: ImageVector by lazy {
        materialIcon(name = "Visibility") {
            moveTo(12f, 4.5f)
            curveTo(7f, 4.5f, 2.73f, 7.61f, 1f, 12f)
            curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f)
            reflectiveCurveToRelative(9.27f, -3.11f, 11f, -7.5f)
            curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f)
            close()
            moveTo(12f, 17f)
            curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
            reflectiveCurveToRelative(2.24f, -5f, 5f, -5f)
            reflectiveCurveToRelative(5f, 2.24f, 5f, 5f)
            reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f)
            close()
            moveTo(12f, 9f)
            curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
            reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
            reflectiveCurveToRelative(3f, -1.34f, 3f, -3f)
            reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
            close()
        }
    }

    /** 非表示（ブラインドモード）アイコン。 */
    val VisibilityOff: ImageVector by lazy {
        materialIcon(name = "VisibilityOff") {
            moveTo(12f, 7f)
            curveToRelative(2.76f, 0f, 5f, 2.24f, 5f, 5f)
            curveToRelative(0f, 0.65f, -0.13f, 1.26f, -0.36f, 1.83f)
            lineToRelative(2.92f, 2.92f)
            curveToRelative(1.51f, -1.26f, 2.7f, -2.89f, 3.43f, -4.75f)
            curveToRelative(-1.73f, -4.39f, -6f, -7.5f, -11f, -7.5f)
            curveToRelative(-1.4f, 0f, -2.74f, 0.25f, -3.98f, 0.7f)
            lineToRelative(2.16f, 2.16f)
            curveTo(10.74f, 7.13f, 11.35f, 7f, 12f, 7f)
            close()
            moveTo(2f, 4.27f)
            lineToRelative(2.28f, 2.28f)
            lineToRelative(0.46f, 0.46f)
            curveTo(3.08f, 8.3f, 1.78f, 10.02f, 1f, 12f)
            curveToRelative(1.73f, 4.39f, 6f, 7.5f, 11f, 7.5f)
            curveToRelative(1.55f, 0f, 3.03f, -0.3f, 4.38f, -0.84f)
            lineToRelative(0.42f, 0.42f)
            lineTo(19.73f, 22f)
            lineTo(21f, 20.73f)
            lineTo(3.27f, 3f)
            lineTo(2f, 4.27f)
            close()
        }
    }

    /** 調整（パラメータ設定）アイコン。 */
    val Tune: ImageVector by lazy {
        materialIcon(name = "Tune") {
            moveTo(3f, 17f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(-2f)
            lineTo(3f, 17f)
            close()
            moveTo(3f, 5f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(10f)
            lineTo(13f, 5f)
            lineTo(3f, 5f)
            close()
            moveTo(13f, 21f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-8f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(2f)
            close()
            moveTo(7f, 9f)
            verticalLineToRelative(2f)
            lineTo(3f, 11f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(2f)
            lineTo(9f, 9f)
            lineTo(7f, 9f)
            close()
            moveTo(21f, 13f)
            verticalLineToRelative(-2f)
            lineTo(11f, 11f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(10f)
            close()
            moveTo(15f, 9f)
            horizontalLineToRelative(2f)
            lineTo(17f, 7f)
            horizontalLineToRelative(4f)
            lineTo(21f, 5f)
            horizontalLineToRelative(-4f)
            lineTo(17f, 3f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(6f)
            close()
        }
    }

    /** フォルダ（ディレクトリ）アイコン。 */
    val Folder: ImageVector by lazy {
        materialIcon(name = "Folder") {
            moveTo(10f, 4f)
            lineTo(4f, 4f)
            curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
            lineTo(2f, 18f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(16f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            lineTo(22f, 8f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            horizontalLineToRelative(-8f)
            lineToRelative(-2f, -2f)
            close()
        }
    }

    /** 文書（文字起こし JSON）アイコン。 */
    val Description: ImageVector by lazy {
        materialIcon(name = "Description") {
            moveTo(14f, 2f)
            lineTo(6f, 2f)
            curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
            lineTo(4f, 20f)
            curveToRelative(0f, 1.1f, 0.89f, 2f, 1.99f, 2f)
            lineTo(18f, 22f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            lineTo(20f, 8f)
            lineToRelative(-6f, -6f)
            close()
            moveTo(16f, 18f)
            lineTo(8f, 18f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(2f)
            close()
            moveTo(16f, 14f)
            lineTo(8f, 14f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(2f)
            close()
            moveTo(13f, 9f)
            lineTo(13f, 3.5f)
            lineTo(18.5f, 9f)
            lineTo(13f, 9f)
            close()
        }
    }

    /** 展開（下向きシェブロン）アイコン。 */
    val ExpandMore: ImageVector by lazy {
        materialIcon(name = "ExpandMore") {
            moveTo(16.59f, 8.59f)
            lineTo(12f, 13.17f)
            lineTo(7.41f, 8.59f)
            lineTo(6f, 10f)
            lineToRelative(6f, 6f)
            lineToRelative(6f, -6f)
            close()
        }
    }

    /** 折りたたみ（上向きシェブロン）アイコン。 */
    val ExpandLess: ImageVector by lazy {
        materialIcon(name = "ExpandLess") {
            moveTo(12f, 8f)
            lineToRelative(-6f, 6f)
            lineToRelative(1.41f, 1.41f)
            lineTo(12f, 10.83f)
            lineToRelative(4.59f, 4.58f)
            lineTo(18f, 14f)
            close()
        }
    }

    /** 下矢印（現在位置へ戻る）アイコン。 */
    val ArrowDownward: ImageVector by lazy {
        materialIcon(name = "ArrowDownward") {
            moveTo(20f, 12f)
            lineToRelative(-1.41f, -1.41f)
            lineTo(13f, 16.17f)
            lineTo(13f, 4f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(12.17f)
            lineToRelative(-5.58f, -5.59f)
            lineTo(4f, 12f)
            lineToRelative(8f, 8f)
            lineToRelative(8f, -8f)
            close()
        }
    }

    private inline fun materialIcon(
        name: String,
        crossinline block: PathBuilder.() -> Unit,
    ): ImageVector = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).path(
        fill = SolidColor(Color.Black),
        pathBuilder = block,
    ).build()
}
