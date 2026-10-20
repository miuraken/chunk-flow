package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.Hct

/**
 * `primary` から言語役割カラー（[ChunkFlowLanguagePalette]）を実行時に生成する（計画書 §3.2）。
 *
 * 色相は [ChunkFlowHues] で `primary` に調和させ、各ロールのトーンはライト / ダークで別々に決める
 * （[LanguagePaletteTones]）。行背景・アクティブ行・ループ行・バッジコンテナ・バッジ文字・チャンク面・
 * 波形ピーク・波形 RMS は、すべて 1 つの色相から派生させる。
 */
internal object LanguagePaletteTones {
    /** 行背景・バッジ・チャンク面に使う彩度（色域の上限で自動的に下がる）。 */
    const val SURFACE_CHROMA: Double = 48.0

    /** 波形（ピーク / RMS）に使う彩度。線が細いので高めにする。 */
    const val WAVEFORM_CHROMA: Double = 72.0

    /** 既習（completedProgress*）の彩度。 */
    const val COMPLETED_CHROMA: Double = 48.0

    /** 波形ピークのアルファ（従来の HEX と同じ）。 */
    const val WAVEFORM_PEAK_ALPHA: Float = 0.65f

    // ---- ライト ----
    const val LIGHT_ROW_BACKGROUND: Double = 96.0
    const val LIGHT_ACTIVE_ROW_BACKGROUND: Double = 92.0
    const val LIGHT_LOOP_ROW_BACKGROUND: Double = 94.0
    const val LIGHT_BADGE_CONTAINER: Double = 90.0
    const val LIGHT_ON_BADGE_CONTAINER: Double = 20.0
    const val LIGHT_CHUNK_BACKGROUND: Double = 85.0
    const val LIGHT_WAVEFORM_PEAK: Double = 65.0
    const val LIGHT_WAVEFORM_RMS: Double = 40.0
    const val LIGHT_COMPLETED: Double = 40.0

    // ---- ダーク（行背景は従来どおり、明るい色にアルファを掛ける） ----
    const val DARK_ROW_TINT: Double = 70.0
    const val DARK_ROW_BACKGROUND_ALPHA: Float = 0.14f
    const val DARK_ACTIVE_ROW_BACKGROUND_ALPHA: Float = 0.28f
    const val DARK_LOOP_ROW_BACKGROUND_ALPHA: Float = 0.22f
    const val DARK_BADGE_CONTAINER: Double = 30.0
    const val DARK_ON_BADGE_CONTAINER: Double = 90.0
    const val DARK_CHUNK_BACKGROUND: Double = 40.0
    const val DARK_WAVEFORM_PEAK: Double = 80.0
    const val DARK_WAVEFORM_RMS: Double = 65.0
    const val DARK_COMPLETED: Double = 80.0
}

/** 色相・彩度・トーンから不透明な [Color] を作る。 */
internal fun hctColor(hue: Double, chroma: Double, tone: Double): Color = Color(Hct.from(hue, chroma, tone).toInt())

/**
 * `primary` に調和させた言語役割カラーを生成する。
 *
 * @param primary `MaterialTheme.colorScheme.primary`（ダイナミックカラーまたは代替配色）。
 * @param darkTheme ダークテーマかどうか。
 */
fun languagePaletteFor(primary: Color, darkTheme: Boolean): ChunkFlowLanguagePalette = languagePaletteFor(ChunkFlowHues.from(primary.toArgb()), darkTheme)

internal fun languagePaletteFor(hues: ChunkFlowHues, darkTheme: Boolean): ChunkFlowLanguagePalette = with(LanguagePaletteTones) {
    if (darkTheme) {
        ChunkFlowLanguagePalette(
            baseRowBackground = hctColor(hues.base, SURFACE_CHROMA, DARK_ROW_TINT).copy(alpha = DARK_ROW_BACKGROUND_ALPHA),
            baseActiveRowBackground = hctColor(hues.base, SURFACE_CHROMA, DARK_ROW_TINT).copy(alpha = DARK_ACTIVE_ROW_BACKGROUND_ALPHA),
            baseLoopRowBackground = hctColor(hues.base, SURFACE_CHROMA, DARK_ROW_TINT).copy(alpha = DARK_LOOP_ROW_BACKGROUND_ALPHA),
            baseBadgeContainer = hctColor(hues.base, SURFACE_CHROMA, DARK_BADGE_CONTAINER),
            onBaseBadgeContainer = hctColor(hues.base, SURFACE_CHROMA, DARK_ON_BADGE_CONTAINER),
            baseChunkBackground = hctColor(hues.base, SURFACE_CHROMA, DARK_CHUNK_BACKGROUND),
            baseWaveformPeak = hctColor(hues.base, WAVEFORM_CHROMA, DARK_WAVEFORM_PEAK).copy(alpha = WAVEFORM_PEAK_ALPHA),
            baseWaveformRms = hctColor(hues.base, WAVEFORM_CHROMA, DARK_WAVEFORM_RMS),
            targetRowBackground = hctColor(hues.target, SURFACE_CHROMA, DARK_ROW_TINT).copy(alpha = DARK_ROW_BACKGROUND_ALPHA),
            targetActiveRowBackground = hctColor(hues.target, SURFACE_CHROMA, DARK_ROW_TINT).copy(alpha = DARK_ACTIVE_ROW_BACKGROUND_ALPHA),
            targetLoopRowBackground = hctColor(hues.target, SURFACE_CHROMA, DARK_ROW_TINT).copy(alpha = DARK_LOOP_ROW_BACKGROUND_ALPHA),
            targetBadgeContainer = hctColor(hues.target, SURFACE_CHROMA, DARK_BADGE_CONTAINER),
            onTargetBadgeContainer = hctColor(hues.target, SURFACE_CHROMA, DARK_ON_BADGE_CONTAINER),
            targetChunkBackground = hctColor(hues.target, SURFACE_CHROMA, DARK_CHUNK_BACKGROUND),
            targetWaveformPeak = hctColor(hues.target, WAVEFORM_CHROMA, DARK_WAVEFORM_PEAK).copy(alpha = WAVEFORM_PEAK_ALPHA),
            targetWaveformRms = hctColor(hues.target, WAVEFORM_CHROMA, DARK_WAVEFORM_RMS),
            completedProgressText = hctColor(hues.completed, COMPLETED_CHROMA, DARK_COMPLETED),
            completedProgressIndicator = hctColor(hues.completed, COMPLETED_CHROMA, DARK_COMPLETED),
        )
    } else {
        ChunkFlowLanguagePalette(
            baseRowBackground = hctColor(hues.base, SURFACE_CHROMA, LIGHT_ROW_BACKGROUND),
            baseActiveRowBackground = hctColor(hues.base, SURFACE_CHROMA, LIGHT_ACTIVE_ROW_BACKGROUND),
            baseLoopRowBackground = hctColor(hues.base, SURFACE_CHROMA, LIGHT_LOOP_ROW_BACKGROUND),
            baseBadgeContainer = hctColor(hues.base, SURFACE_CHROMA, LIGHT_BADGE_CONTAINER),
            onBaseBadgeContainer = hctColor(hues.base, SURFACE_CHROMA, LIGHT_ON_BADGE_CONTAINER),
            baseChunkBackground = hctColor(hues.base, SURFACE_CHROMA, LIGHT_CHUNK_BACKGROUND),
            baseWaveformPeak = hctColor(hues.base, WAVEFORM_CHROMA, LIGHT_WAVEFORM_PEAK).copy(alpha = WAVEFORM_PEAK_ALPHA),
            baseWaveformRms = hctColor(hues.base, WAVEFORM_CHROMA, LIGHT_WAVEFORM_RMS),
            targetRowBackground = hctColor(hues.target, SURFACE_CHROMA, LIGHT_ROW_BACKGROUND),
            targetActiveRowBackground = hctColor(hues.target, SURFACE_CHROMA, LIGHT_ACTIVE_ROW_BACKGROUND),
            targetLoopRowBackground = hctColor(hues.target, SURFACE_CHROMA, LIGHT_LOOP_ROW_BACKGROUND),
            targetBadgeContainer = hctColor(hues.target, SURFACE_CHROMA, LIGHT_BADGE_CONTAINER),
            onTargetBadgeContainer = hctColor(hues.target, SURFACE_CHROMA, LIGHT_ON_BADGE_CONTAINER),
            targetChunkBackground = hctColor(hues.target, SURFACE_CHROMA, LIGHT_CHUNK_BACKGROUND),
            targetWaveformPeak = hctColor(hues.target, WAVEFORM_CHROMA, LIGHT_WAVEFORM_PEAK).copy(alpha = WAVEFORM_PEAK_ALPHA),
            targetWaveformRms = hctColor(hues.target, WAVEFORM_CHROMA, LIGHT_WAVEFORM_RMS),
            completedProgressText = hctColor(hues.completed, COMPLETED_CHROMA, LIGHT_COMPLETED),
            completedProgressIndicator = hctColor(hues.completed, COMPLETED_CHROMA, LIGHT_COMPLETED),
        )
    }
}
