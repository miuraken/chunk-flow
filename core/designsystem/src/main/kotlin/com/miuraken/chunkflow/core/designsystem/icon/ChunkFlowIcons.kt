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
