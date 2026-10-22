package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.miuraken.chunkflow.core.model.LibraryFolderColors

/**
 * ライブラリのフォルダの色分けに使う、決め打ちの薄い色のパレット（ADR 0013）。
 *
 * 色の数は [LibraryFolderColors.PALETTE_SIZE]。どのフォルダに何番の色を使うか（対象言語が初めて使われた順、
 * またはフォルダごとの指定）は `core:model` の [LibraryFolderColors] が決め、ここでは番号ごとの実際の色だけを持つ。
 * 利用者が選んだ色がテーマ（ダイナミックカラー）で変わらないように、色相は `primary` に調和させずに固定し、
 * トーンと彩度だけをライト / ダークで変える。
 *
 * 行の背景（[containers]、[selectedContainers]）は、上に置く文字（`onSurface` / `onSurfaceVariant`）との
 * コントラスト比が 4.5:1 以上になるトーンにしている（`FolderPaletteTest` で固定する）。
 * 画面からは [chunkFlowFolderPalette] で取得する。
 *
 * @property containers フォルダの行と、開いているフォルダの見出しの背景色（番号順）。
 * @property selectedContainers 複数選択で選ばれているフォルダの行の背景色（番号順。[containers] より濃い）。
 */
@Immutable
data class ChunkFlowFolderPalette(
    val containers: List<Color>,
    val selectedContainers: List<Color>,
) {
    /** 番号 [index] の行の背景色。`null` や範囲外の番号なら `null`。 */
    fun containerOrNull(index: Int?): Color? = index?.let { containers.getOrNull(it) }

    /** 番号 [index] の、選択中の行の背景色。`null` や範囲外の番号なら `null`。 */
    fun selectedContainerOrNull(index: Int?): Color? = index?.let { selectedContainers.getOrNull(it) }
}

/** フォルダのパレットの色相・彩度・トーン（ライト / ダーク）。 */
internal object FolderPaletteTones {
    /**
     * 番号順の色相（HCT、度）。最初に使われる番号ほど見分けやすいように、色相環を飛び飛びに並べる
     * （1 行目の 6 色: 青 → 橙 → 緑 → ピンク → 青緑 → 黄、2〜3 行目で合計 18 色）。
     */
    val HUES: List<Double> = listOf(
        255.0,
        65.0,
        145.0,
        350.0,
        195.0,
        100.0,
        300.0,
        25.0,
        225.0,
        45.0,
        170.0,
        325.0,
        82.0,
        278.0,
        122.0,
        5.0,
        210.0,
        240.0,
    )

    // ---- ライト（surfaceContainer と同じくらいの明るさに、薄く色を付ける） ----
    const val LIGHT_CONTAINER_CHROMA: Double = 24.0
    const val LIGHT_CONTAINER_TONE: Double = 92.0
    const val LIGHT_SELECTED_CHROMA: Double = 36.0
    const val LIGHT_SELECTED_TONE: Double = 84.0

    // ---- ダーク（暗い面に、彩度を抑えた色を付ける） ----
    const val DARK_CONTAINER_CHROMA: Double = 16.0
    const val DARK_CONTAINER_TONE: Double = 22.0
    const val DARK_SELECTED_CHROMA: Double = 28.0
    const val DARK_SELECTED_TONE: Double = 32.0
}

/**
 * フォルダのパレットを生成する。
 *
 * @param darkTheme ダークテーマかどうか。
 */
fun folderPaletteFor(darkTheme: Boolean): ChunkFlowFolderPalette = with(FolderPaletteTones) {
    if (darkTheme) {
        ChunkFlowFolderPalette(
            containers = HUES.map { hue -> hctColor(hue, DARK_CONTAINER_CHROMA, DARK_CONTAINER_TONE) },
            selectedContainers = HUES.map { hue -> hctColor(hue, DARK_SELECTED_CHROMA, DARK_SELECTED_TONE) },
        )
    } else {
        ChunkFlowFolderPalette(
            containers = HUES.map { hue -> hctColor(hue, LIGHT_CONTAINER_CHROMA, LIGHT_CONTAINER_TONE) },
            selectedContainers = HUES.map { hue -> hctColor(hue, LIGHT_SELECTED_CHROMA, LIGHT_SELECTED_TONE) },
        )
    }
}

internal val LocalChunkFlowFolderPalette = staticCompositionLocalOf { folderPaletteFor(darkTheme = false) }

/** 現在のテーマ（ライト / ダーク）に応じた [ChunkFlowFolderPalette] を取得する。 */
@Composable
@ReadOnlyComposable
fun chunkFlowFolderPalette(): ChunkFlowFolderPalette = LocalChunkFlowFolderPalette.current
