
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

    /** 編集（メタデータ・言語編集）アイコン。 */
    val Edit: ImageVector by lazy {
        materialIcon(name = "Edit") {
            moveTo(3f, 17.25f)
            lineTo(3f, 21f)
            horizontalLineToRelative(3.75f)
            lineTo(17.81f, 9.94f)
            lineToRelative(-3.75f, -3.75f)
            lineTo(3f, 17.25f)
            close()
            moveTo(20.71f, 7.04f)
            curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f)
            lineToRelative(-2.34f, -2.34f)
            curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f)
            lineToRelative(-1.83f, 1.83f)
            lineToRelative(3.75f, 3.75f)
            lineToRelative(1.83f, -1.83f)
            close()
        }
    }

    /** 翻訳・言語フィルタアイコン。 */
    val Translate: ImageVector by lazy {
        materialIcon(name = "Translate") {
            moveTo(12.87f, 15.07f)
            lineToRelative(-2.54f, -2.51f)
            lineToRelative(0.03f, -0.03f)
            curveToRelative(1.74f, -1.94f, 2.98f, -4.17f, 3.71f, -6.53f)
            lineTo(17f, 6f)
            lineTo(17f, 4f)
            horizontalLineToRelative(-7f)
            lineTo(10f, 2f)
            lineTo(8f, 2f)
            verticalLineToRelative(2f)
            lineTo(1f, 4f)
            verticalLineToRelative(1.99f)
            horizontalLineToRelative(11.17f)
            curveTo(11.5f, 7.92f, 10.44f, 9.75f, 9f, 11.35f)
            curveTo(8.07f, 10.32f, 7.3f, 9.19f, 6.69f, 8f)
            horizontalLineToRelative(-2f)
            curveToRelative(0.73f, 1.63f, 1.73f, 3.17f, 2.98f, 4.56f)
            lineToRelative(-5.09f, 5.02f)
            lineTo(4f, 19f)
            lineToRelative(5f, -5f)
            lineToRelative(3.11f, 3.11f)
            lineToRelative(0.76f, -2.04f)
            close()
            moveTo(18.5f, 10f)
            horizontalLineToRelative(-2f)
            lineTo(12f, 22f)
            horizontalLineToRelative(2f)
            lineToRelative(1.12f, -3f)
            horizontalLineToRelative(4.75f)
            lineTo(21f, 22f)
            horizontalLineToRelative(2f)
            lineToRelative(-4.5f, -12f)
            close()
            moveTo(15.88f, 17f)
            lineToRelative(1.62f, -4.33f)
            lineTo(19.12f, 17f)
            horizontalLineToRelative(-3.24f)
            close()
        }
    }

    /** 稲妻（瞬間作文・先読み文字起こし）アイコン。 */
    val Bolt: ImageVector by lazy {
        materialIcon(name = "Bolt") {
            moveTo(11f, 21f)
            horizontalLineToRelative(-1f)
            lineToRelative(1f, -7f)
            lineTo(7.5f, 14f)
            curveToRelative(-0.58f, 0f, -0.57f, -0.32f, -0.38f, -0.66f)
            lineToRelative(0.19f, -0.34f)
            curveTo(8.48f, 10.94f, 10.42f, 7.54f, 13f, 3f)
            horizontalLineToRelative(1f)
            lineToRelative(-1f, 7f)
            horizontalLineToRelative(3.5f)
            curveToRelative(0.49f, 0f, 0.56f, 0.33f, 0.47f, 0.51f)
            lineToRelative(-0.07f, 0.15f)
            curveTo(12.96f, 17.55f, 11f, 21f, 11f, 21f)
            close()
        }
    }

    /** チェック（確定・選択済み）アイコン。 */
    val Check: ImageVector by lazy {
        materialIcon(name = "Check") {
            moveTo(9f, 16.17f)
            lineTo(4.83f, 12f)
            lineToRelative(-1.42f, 1.41f)
            lineTo(9f, 19f)
            lineTo(21f, 7f)
            lineToRelative(-1.41f, -1.41f)
            close()
        }
    }

    /** ブックマーク済み（塗りつぶし）アイコン（F8.1）。 */
    val Bookmark: ImageVector by lazy {
        materialIcon(name = "Bookmark") {
            moveTo(17f, 3f)
            lineTo(7f, 3f)
            curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
            lineTo(5f, 21f)
            lineToRelative(7f, -3f)
            lineToRelative(7f, 3f)
            lineTo(19f, 5f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
        }
    }

    /** ブックマーク未設定（枠線）アイコン（F8.1）。 */
    val BookmarkBorder: ImageVector by lazy {
        materialIcon(name = "BookmarkBorder") {
            moveTo(17f, 3f)
            lineTo(7f, 3f)
            curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
            lineTo(5f, 21f)
            lineToRelative(7f, -3f)
            lineToRelative(7f, 3f)
            lineTo(19f, 5f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(17f, 18f)
            lineToRelative(-5f, -2.18f)
            lineTo(7f, 18f)
            lineTo(7f, 5f)
            horizontalLineToRelative(10f)
            verticalLineToRelative(13f)
            close()
        }
    }

    /** ファイル書き出し・ダウンロードアイコン（F7.5）。 */
    val Download: ImageVector by lazy {
        materialIcon(name = "Download") {
            moveTo(19f, 9f)
            horizontalLineToRelative(-4f)
            lineTo(15f, 3f)
            lineTo(9f, 3f)
            verticalLineToRelative(6f)
            lineTo(5f, 9f)
            lineToRelative(7f, 7f)
            lineToRelative(7f, -7f)
            close()
            moveTo(5f, 18f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(14f)
            verticalLineToRelative(-2f)
            lineTo(5f, 18f)
            close()
        }
    }

    /** 検索アイコン。 */
    val Search: ImageVector by lazy {
        materialIcon(name = "Search") {
            moveTo(15.5f, 14f)
            horizontalLineToRelative(-0.79f)
            lineToRelative(-0.28f, -0.27f)
            curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
            curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f)
            curveTo(5.91f, 3f, 3f, 5.91f, 3f, 9.5f)
            curveTo(3f, 13.09f, 5.91f, 16f, 9.5f, 16f)
            curveToRelative(1.61f, 0f, 3.09f, -0.59f, 4.23f, -1.57f)
            lineToRelative(0.27f, 0.28f)
            verticalLineToRelative(0.79f)
            lineToRelative(5f, 4.99f)
            lineTo(20.49f, 19f)
            lineToRelative(-4.99f, -5f)
            close()
            moveTo(9.5f, 14f)
            curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
            curveTo(5f, 7.01f, 7.01f, 5f, 9.5f, 5f)
            curveTo(11.99f, 5f, 14f, 7.01f, 14f, 9.5f)
            curveTo(14f, 11.99f, 11.99f, 14f, 9.5f, 14f)
            close()
        }
    }

    /** フォルダ追加（+ 付きフォルダ）アイコン。 */
    val CreateNewFolder: ImageVector by lazy {
        materialIcon(name = "CreateNewFolder") {
            moveTo(20f, 6f)
            horizontalLineToRelative(-8f)
            lineToRelative(-2f, -2f)
            lineTo(4f, 4f)
            curveToRelative(-1.11f, 0f, -1.99f, 0.89f, -1.99f, 2f)
            lineTo(2f, 18f)
            curveToRelative(0f, 1.11f, 0.89f, 2f, 2f, 2f)
            horizontalLineToRelative(16f)
            curveToRelative(1.11f, 0f, 2f, -0.89f, 2f, -2f)
            lineTo(22f, 8f)
            curveToRelative(0f, -1.11f, -0.89f, -2f, -2f, -2f)
            close()
            moveTo(19f, 14f)
            horizontalLineToRelative(-3f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-3f)
            horizontalLineToRelative(-3f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(3f)
            lineTo(14f, 9f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(3f)
            verticalLineToRelative(2f)
            close()
        }
    }

    /** ファイル追加（+ 付きドキュメント）アイコン。 */
    val NoteAdd: ImageVector by lazy {
        materialIcon(name = "NoteAdd") {
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
            moveTo(16f, 16f)
            horizontalLineToRelative(-3f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-3f)
            lineTo(8f, 16f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(3f)
            verticalLineToRelative(-3f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(3f)
            horizontalLineToRelative(3f)
            verticalLineToRelative(2f)
            close()
            moveTo(13f, 9f)
            lineTo(13f, 3.5f)
            lineTo(18.5f, 9f)
            lineTo(13f, 9f)
            close()
        }
    }

    /** 履歴（時計）アイコン。 */
    val Schedule: ImageVector by lazy {
        materialIcon(name = "Schedule") {
            moveTo(11.99f, 2f)
            curveTo(6.47f, 2f, 2f, 6.48f, 2f, 12f)
            reflectiveCurveToRelative(4.47f, 10f, 9.99f, 10f)
            curveTo(17.52f, 22f, 22f, 17.52f, 22f, 12f)
            reflectiveCurveTo(17.52f, 2f, 11.99f, 2f)
            close()
            moveTo(12f, 20f)
            curveToRelative(-4.42f, 0f, -8f, -3.58f, -8f, -8f)
            reflectiveCurveToRelative(3.58f, -8f, 8f, -8f)
            reflectiveCurveToRelative(8f, 3.58f, 8f, 8f)
            reflectiveCurveToRelative(-3.58f, 8f, -8f, 8f)
            close()
            moveTo(12.5f, 7f)
            lineTo(11f, 7f)
            verticalLineToRelative(6f)
            lineToRelative(5.25f, 3.15f)
            lineToRelative(0.75f, -1.23f)
            lineToRelative(-4.5f, -2.67f)
            close()
        }
    }

    /** よく再生する・上昇トレンドアイコン。 */
    val TrendingUp: ImageVector by lazy {
        materialIcon(name = "TrendingUp") {
            moveTo(16f, 6f)
            lineToRelative(2.29f, 2.29f)
            lineToRelative(-4.88f, 4.88f)
            lineToRelative(-4f, -4f)
            lineTo(2f, 16.59f)
            lineTo(3.41f, 18f)
            lineToRelative(6f, -6f)
            lineToRelative(4f, 4f)
            lineToRelative(6.3f, -6.29f)
            lineTo(22f, 12f)
            lineTo(22f, 6f)
            close()
        }
    }

    /** お気に入り（星塗りつぶし）アイコン。 */
    val Favorite: ImageVector by lazy {
        materialIcon(name = "Favorite") {
            moveTo(12f, 17.27f)
            lineTo(18.18f, 21f)
            lineToRelative(-1.64f, -7.03f)
            lineTo(22f, 9.24f)
            lineToRelative(-7.19f, -0.61f)
            lineTo(12f, 2f)
            lineTo(9.19f, 8.63f)
            lineTo(2f, 9.24f)
            lineToRelative(5.46f, 4.73f)
            lineTo(5.82f, 21f)
            close()
        }
    }

    /** お気に入り未設定（星枠線）アイコン。 */
    val FavoriteBorder: ImageVector by lazy {
        materialIcon(name = "FavoriteBorder") {
            moveTo(22f, 9.24f)
            lineToRelative(-7.19f, -0.62f)
            lineTo(12f, 2f)
            lineTo(9.19f, 8.63f)
            lineTo(2f, 9.24f)
            lineToRelative(5.46f, 4.73f)
            lineTo(5.82f, 21f)
            lineTo(12f, 17.27f)
            lineTo(18.18f, 21f)
            lineToRelative(-1.63f, -7.03f)
            lineTo(22f, 9.24f)
            close()
            moveTo(12f, 15.4f)
            lineToRelative(-3.76f, 2.27f)
            lineToRelative(1f, -4.28f)
            lineToRelative(-3.32f, -2.88f)
            lineToRelative(4.38f, -0.38f)
            lineTo(12f, 6.1f)
            lineToRelative(1.71f, 4.04f)
            lineToRelative(4.38f, 0.38f)
            lineToRelative(-3.32f, 2.88f)
            lineToRelative(1f, 4.28f)
            lineTo(12f, 15.4f)
            close()
        }
    }

    /** 再生キュー（QueueMusic）アイコン。 */
    val QueueMusic: ImageVector by lazy {
        materialIcon(name = "QueueMusic") {
            moveTo(15f, 6f)
            lineTo(3f, 6f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(12f)
            lineTo(15f, 6f)
            close()
            moveTo(15f, 10f)
            lineTo(3f, 10f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(-2f)
            close()
            moveTo(3f, 16f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(-2f)
            lineTo(3f, 14f)
            verticalLineToRelative(2f)
            close()
            moveTo(17f, 6f)
            verticalLineToRelative(8.18f)
            curveTo(16.69f, 14.07f, 16.35f, 14f, 16f, 14f)
            curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
            reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
            reflectiveCurveToRelative(3f, -1.34f, 3f, -3f)
            lineTo(19f, 8f)
            horizontalLineToRelative(3f)
            lineTo(22f, 6f)
            horizontalLineToRelative(-5f)
            close()
        }
    }

    /** 三点リーダー（横）アイコン。 */
    val MoreHoriz: ImageVector by lazy {
        materialIcon(name = "MoreHoriz") {
            moveTo(6f, 10f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
            reflectiveCurveToRelative(2f, -0.9f, 2f, -2f)
            reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
            close()
            moveTo(18f, 10f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
            reflectiveCurveToRelative(2f, -0.9f, 2f, -2f)
            reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
            close()
            moveTo(12f, 10f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
            reflectiveCurveToRelative(2f, -0.9f, 2f, -2f)
            reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
            close()
        }
    }

    /** ドラッグ並び替え用のハンドルアイコン（2 本の横線）。 */
    val DragHandle: ImageVector by lazy {
        materialIcon(name = "DragHandle") {
            moveTo(20f, 9f)
            lineTo(4f, 9f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(16f)
            lineTo(20f, 9f)
            close()
            moveTo(4f, 15f)
            horizontalLineToRelative(16f)
            verticalLineToRelative(-2f)
            lineTo(4f, 13f)
            verticalLineToRelative(2f)
            close()
        }
    }

    /** プレイリストに追加（PlaylistAdd）アイコン。 */
    val PlaylistAdd: ImageVector by lazy {
        materialIcon(name = "PlaylistAdd") {
            moveTo(14f, 10f)
            lineTo(2f, 10f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(12f)
            verticalLineToRelative(-2f)
            close()
            moveTo(14f, 6f)
            lineTo(2f, 6f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(12f)
            lineTo(14f, 6f)
            close()
            moveTo(18f, 14f)
            verticalLineToRelative(-4f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(4f)
            horizontalLineToRelative(-4f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(4f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-4f)
            horizontalLineToRelative(4f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(-4f)
            close()
            moveTo(2f, 16f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(-2f)
            lineTo(2f, 14f)
            verticalLineToRelative(2f)
            close()
        }
    }

    /** 右向きシェブロン（パンくず区切り）アイコン。 */
    val ChevronRight: ImageVector by lazy {
        materialIcon(name = "ChevronRight") {
            moveTo(10f, 6f)
            lineTo(8.59f, 7.41f)
            lineTo(13.17f, 12f)
            lineToRelative(-4.58f, 4.59f)
            lineTo(10f, 18f)
            lineToRelative(6f, -6f)
            close()
        }
    }

    /** 設定（歯車）アイコン。 */
    val Settings: ImageVector by lazy {
        materialIcon(name = "Settings") {
            moveTo(19.14f, 12.94f)
            curveToRelative(0.04f, -0.3f, 0.06f, -0.61f, 0.06f, -0.94f)
            curveToRelative(0f, -0.32f, -0.02f, -0.64f, -0.07f, -0.94f)
            lineToRelative(2.03f, -1.58f)
            curveToRelative(0.18f, -0.14f, 0.23f, -0.41f, 0.12f, -0.61f)
            lineToRelative(-1.92f, -3.32f)
            curveToRelative(-0.12f, -0.22f, -0.37f, -0.29f, -0.59f, -0.22f)
            lineToRelative(-2.39f, 0.96f)
            curveToRelative(-0.5f, -0.38f, -1.03f, -0.7f, -1.62f, -0.94f)
            lineTo(14.4f, 2.81f)
            curveToRelative(-0.04f, -0.24f, -0.24f, -0.41f, -0.48f, -0.41f)
            horizontalLineToRelative(-3.84f)
            curveToRelative(-0.24f, 0f, -0.43f, 0.17f, -0.47f, 0.41f)
            lineTo(9.25f, 5.35f)
            curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
            lineTo(5.24f, 5.33f)
            curveToRelative(-0.22f, -0.08f, -0.47f, 0f, -0.59f, 0.22f)
            lineTo(2.74f, 8.87f)
            curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f)
            lineToRelative(2.03f, 1.58f)
            curveTo(4.84f, 11.36f, 4.8f, 11.69f, 4.8f, 12f)
            curveToRelative(0f, 0.31f, 0.02f, 0.65f, 0.07f, 0.94f)
            lineToRelative(-2.03f, 1.58f)
            curveToRelative(-0.18f, 0.14f, -0.23f, 0.41f, -0.12f, 0.61f)
            lineToRelative(1.92f, 3.32f)
            curveToRelative(0.12f, 0.22f, 0.37f, 0.29f, 0.59f, 0.22f)
            lineToRelative(2.39f, -0.96f)
            curveToRelative(0.5f, 0.38f, 1.03f, 0.7f, 1.62f, 0.94f)
            lineToRelative(0.36f, 2.54f)
            curveToRelative(0.05f, 0.24f, 0.24f, 0.41f, 0.48f, 0.41f)
            horizontalLineToRelative(3.84f)
            curveToRelative(0.24f, 0f, 0.44f, -0.17f, 0.47f, -0.41f)
            lineToRelative(0.36f, -2.54f)
            curveToRelative(0.59f, -0.24f, 1.13f, -0.56f, 1.62f, -0.94f)
            lineToRelative(2.39f, 0.96f)
            curveToRelative(0.22f, 0.08f, 0.47f, 0f, 0.59f, -0.22f)
            lineToRelative(1.92f, -3.32f)
            curveToRelative(0.12f, -0.22f, 0.07f, -0.47f, -0.12f, -0.61f)
            lineTo(19.14f, 12.94f)
            close()
            moveTo(12f, 15.6f)
            curveToRelative(-1.98f, 0f, -3.6f, -1.62f, -3.6f, -3.6f)
            reflectiveCurveToRelative(1.62f, -3.6f, 3.6f, -3.6f)
            reflectiveCurveToRelative(3.6f, 1.62f, 3.6f, 3.6f)
            reflectiveCurveTo(13.98f, 15.6f, 12f, 15.6f)
            close()
        }
    }

    /** ホーム（ルートフォルダ階層）アイコン。 */
    val Home: ImageVector by lazy {
        materialIcon(name = "Home") {
            moveTo(12f, 5.69f)
            lineToRelative(5f, 4.5f)
            verticalLineTo(18f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-6f)
            horizontalLineTo(9f)
            verticalLineToRelative(6f)
            horizontalLineTo(7f)
            verticalLineToRelative(-7.81f)
            lineToRelative(5f, -4.5f)
            moveTo(12f, 3f)
            lineTo(2f, 12f)
            horizontalLineToRelative(3f)
            verticalLineToRelative(8f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(-6f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(-8f)
            horizontalLineToRelative(3f)
            lineTo(12f, 3f)
            close()
        }
    }

    /** 音声文字起こし（SpeechToText）アイコン。 */
    val SpeechToText: ImageVector by lazy {
        materialIcon(name = "SpeechToText") {
            // 書類の枠線（左・下・右）
            moveTo(11f, 2f)
            horizontalLineTo(6f)
            curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
            verticalLineToRelative(16f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(12f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineToRelative(-5f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(5f)
            horizontalLineTo(6f)
            verticalLineTo(4f)
            horizontalLineToRelative(5f)
            verticalLineTo(2f)
            close()
            // 下部のテキスト行（2本）
            moveTo(8f, 14f)
            horizontalLineToRelative(8f)
            verticalLineToRelative(2f)
            horizontalLineTo(8f)
            close()
            moveTo(8f, 17f)
            horizontalLineToRelative(5f)
            verticalLineToRelative(2f)
            horizontalLineTo(8f)
            close()
            // 右上のマイクカプセル
            moveTo(16.5f, 2f)
            curveTo(15.67f, 2f, 15f, 2.67f, 15f, 3.5f)
            verticalLineToRelative(3f)
            curveTo(15f, 7.33f, 15.67f, 8f, 16.5f, 8f)
            reflectiveCurveTo(18f, 7.33f, 18f, 6.5f)
            verticalLineToRelative(-3f)
            curveTo(18f, 2.67f, 17.33f, 2f, 16.5f, 2f)
            close()
            // マイクの受け皿とスタンド
            moveTo(19f, 6.5f)
            curveToRelative(0f, 1.38f, -1.12f, 2.5f, -2.5f, 2.5f)
            reflectiveCurveTo(14f, 7.88f, 14f, 6.5f)
            horizontalLineToRelative(-1.5f)
            curveToRelative(0f, 1.93f, 1.42f, 3.52f, 3.25f, 3.88f)
            verticalLineTo(12f)
            horizontalLineToRelative(1.5f)
            verticalLineToRelative(-1.62f)
            curveToRelative(1.83f, -0.36f, 3.25f, -1.95f, 3.25f, -3.88f)
            horizontalLineTo(19f)
            close()
        }
    }

    /** 履歴（反時計回り矢印＋時計針）アイコン。 */
    val History: ImageVector by lazy {
        materialIcon(name = "History") {
            moveTo(13f, 3f)
            curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
            horizontalLineTo(1f)
            lineToRelative(3.89f, 3.89f)
            lineToRelative(0.07f, 0.14f)
            lineTo(9f, 12f)
            horizontalLineTo(6f)
            curveToRelative(0f, -3.87f, 3.13f, -7f, 7f, -7f)
            reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
            reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f)
            curveToRelative(-1.93f, 0f, -3.68f, -0.79f, -4.94f, -2.06f)
            lineToRelative(-1.42f, 1.42f)
            curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
            curveToRelative(4.97f, 0f, 9f, -4.03f, 9f, -9f)
            reflectiveCurveTo(17.97f, 3f, 13f, 3f)
            close()
            moveTo(12f, 8f)
            verticalLineToRelative(5f)
            lineToRelative(4.28f, 2.54f)
            lineToRelative(0.72f, -1.21f)
            lineToRelative(-3.5f, -2.08f)
            verticalLineTo(8f)
            horizontalLineTo(12f)
            close()
        }
    }

    /** 連桁付き 8 分音符（♫ / MusicNote）アイコン（デフォルトサムネイル用）。 */
    val MusicNote: ImageVector by lazy {
        materialIcon(name = "MusicNote") {
            moveTo(20f, 3f)
            lineTo(9f, 5.5f)
            verticalLineToRelative(9.55f)
            curveTo(8.41f, 14.71f, 7.73f, 14.5f, 7f, 14.5f)
            curveToRelative(-1.93f, 0f, -3.5f, 1.34f, -3.5f, 3f)
            reflectiveCurveTo(5.07f, 20.5f, 7f, 20.5f)
            reflectiveCurveToRelative(3.5f, -1.34f, 3.5f, -3f)
            verticalLineTo(9.2f)
            lineTo(18f, 7.5f)
            verticalLineToRelative(5.55f)
            curveToRelative(-0.59f, -0.34f, -1.27f, -0.55f, -2f, -0.55f)
            curveToRelative(-1.93f, 0f, -3.5f, 1.34f, -3.5f, 3f)
            reflectiveCurveTo(14.07f, 18.5f, 16f, 18.5f)
            reflectiveCurveToRelative(3.5f, -1.34f, 3.5f, -3f)
            verticalLineTo(3f)
            close()
        }
    }

    /** 右上矢印（ファイル内の該当チャンクへ移動・ArrowOutward）アイコン。 */
    val ArrowOutward: ImageVector by lazy {
        materialIcon(name = "ArrowOutward") {
            moveTo(6f, 6f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(8.59f)
            lineTo(5f, 17.59f)
            lineTo(6.41f, 19f)
            lineTo(16f, 9.41f)
            verticalLineTo(18f)
            horizontalLineToRelative(2f)
            verticalLineTo(6f)
            close()
        }
    }

    /** 警告（Material Symbols の warning 相当）アイコン。⚠️ 絵文字の代替（調査 §3.2 C7）。 */
    val Warning: ImageVector by lazy {
        materialIcon(name = "Warning") {
            moveTo(1f, 21f)
            horizontalLineToRelative(22f)
            lineTo(12f, 2f)
            lineTo(1f, 21f)
            close()
            moveTo(13f, 18f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            close()
            moveTo(13f, 14f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-4f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(4f)
            close()
        }
    }

    /**
     * 無音スキップ（波形の途中が平らになっている）アイコン。Material Symbols に相当するものが無いため独自に定義。
     * `graphic_eq` の 5 本の棒のうち中央を点にしたもの。
     */
    val SkipSilence: ImageVector by lazy {
        materialIcon(name = "SkipSilence") {
            // 左の 2 本
            moveTo(3f, 7f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(10f)
            horizontalLineTo(3f)
            close()
            moveTo(7f, 3f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(18f)
            horizontalLineTo(7f)
            close()
            // 中央の「無音」
            moveTo(11f, 11f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(-2f)
            close()
            // 右の 2 本
            moveTo(15f, 3f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(18f)
            horizontalLineToRelative(-2f)
            close()
            moveTo(19f, 7f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(10f)
            horizontalLineToRelative(-2f)
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
