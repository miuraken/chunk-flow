package com.miuraken.chunkflow.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.miuraken.chunkflow.core.designsystem.theme.colorutil.Hct

/**
 * ダイナミックカラーを使わないとき（プレビュー、スクリーンショットテスト）の代替の配色（調査 §3.3 T3）。
 *
 * Material Theme Builder と同じ考え方で、シード色からトーナルパレットを作り、**全ロール**
 * （surface / surfaceContainer* / outline / error / fixed 系を含む）をトーンで決める。
 * 値は A1 で移植した HCT（[Hct]）から生成するので、直書きの HEX を持たない。
 *
 * シード: primary = Teal `#006A6A`、secondary = Slate `#4A6363`、tertiary = Amber `#7A5900`（従来の代替配色と同じ色相・彩度）。
 */
internal object FallbackColorSeeds {
    const val PRIMARY_SEED: Int = 0xFF006A6A.toInt()
    const val SECONDARY_SEED: Int = 0xFF4A6363.toInt()
    const val TERTIARY_SEED: Int = 0xFF7A5900.toInt()

    /** ニュートラル（surface 系）の彩度。M3 の Tonal spot と同じ。 */
    const val NEUTRAL_CHROMA: Double = 6.0

    /** ニュートラルバリアント（outline、surfaceVariant）の彩度。 */
    const val NEUTRAL_VARIANT_CHROMA: Double = 8.0

    /** エラーの色相と彩度（M3 既定）。 */
    const val ERROR_HUE: Double = 25.0
    const val ERROR_CHROMA: Double = 84.0
}

/** 1 つの色相・彩度から、トーンだけを変えた色を取り出すトーナルパレット。 */
internal class TonalPalette(val hue: Double, val chroma: Double) {
    private val cache = HashMap<Int, Color>()

    /** トーン [tone]（0〜100）の色。 */
    fun tone(tone: Int): Color = cache.getOrPut(tone) { hctColor(hue, chroma, tone.toDouble()) }

    companion object {
        /** ARGB の色相と彩度を持つパレット。 */
        fun fromArgb(argb: Int): TonalPalette {
            val hct = Hct.fromInt(argb)
            return TonalPalette(hct.hue, hct.chroma)
        }
    }
}

private val primaryPalette by lazy { TonalPalette.fromArgb(FallbackColorSeeds.PRIMARY_SEED) }
private val secondaryPalette by lazy { TonalPalette.fromArgb(FallbackColorSeeds.SECONDARY_SEED) }
private val tertiaryPalette by lazy { TonalPalette.fromArgb(FallbackColorSeeds.TERTIARY_SEED) }
private val neutralPalette by lazy { TonalPalette(primaryPalette.hue, FallbackColorSeeds.NEUTRAL_CHROMA) }
private val neutralVariantPalette by lazy { TonalPalette(primaryPalette.hue, FallbackColorSeeds.NEUTRAL_VARIANT_CHROMA) }
private val errorPalette by lazy { TonalPalette(FallbackColorSeeds.ERROR_HUE, FallbackColorSeeds.ERROR_CHROMA) }

/** ライトテーマの代替の配色（全ロール）。 */
internal val FallbackLightColorScheme: ColorScheme by lazy {
    val p = primaryPalette
    val s = secondaryPalette
    val t = tertiaryPalette
    val n = neutralPalette
    val nv = neutralVariantPalette
    val e = errorPalette
    lightColorScheme(
        primary = p.tone(40),
        onPrimary = p.tone(100),
        primaryContainer = p.tone(90),
        onPrimaryContainer = p.tone(10),
        inversePrimary = p.tone(80),
        secondary = s.tone(40),
        onSecondary = s.tone(100),
        secondaryContainer = s.tone(90),
        onSecondaryContainer = s.tone(10),
        tertiary = t.tone(40),
        onTertiary = t.tone(100),
        tertiaryContainer = t.tone(90),
        onTertiaryContainer = t.tone(10),
        background = n.tone(98),
        onBackground = n.tone(10),
        surface = n.tone(98),
        onSurface = n.tone(10),
        surfaceVariant = nv.tone(90),
        onSurfaceVariant = nv.tone(30),
        surfaceTint = p.tone(40),
        inverseSurface = n.tone(20),
        inverseOnSurface = n.tone(95),
        error = e.tone(40),
        onError = e.tone(100),
        errorContainer = e.tone(90),
        onErrorContainer = e.tone(10),
        outline = nv.tone(50),
        outlineVariant = nv.tone(80),
        scrim = n.tone(0),
        surfaceBright = n.tone(98),
        surfaceDim = n.tone(87),
        surfaceContainer = n.tone(94),
        surfaceContainerHigh = n.tone(92),
        surfaceContainerHighest = n.tone(90),
        surfaceContainerLow = n.tone(96),
        surfaceContainerLowest = n.tone(100),
        primaryFixed = p.tone(90),
        primaryFixedDim = p.tone(80),
        onPrimaryFixed = p.tone(10),
        onPrimaryFixedVariant = p.tone(30),
        secondaryFixed = s.tone(90),
        secondaryFixedDim = s.tone(80),
        onSecondaryFixed = s.tone(10),
        onSecondaryFixedVariant = s.tone(30),
        tertiaryFixed = t.tone(90),
        tertiaryFixedDim = t.tone(80),
        onTertiaryFixed = t.tone(10),
        onTertiaryFixedVariant = t.tone(30),
    )
}

/** ダークテーマの代替の配色（全ロール）。 */
internal val FallbackDarkColorScheme: ColorScheme by lazy {
    val p = primaryPalette
    val s = secondaryPalette
    val t = tertiaryPalette
    val n = neutralPalette
    val nv = neutralVariantPalette
    val e = errorPalette
    darkColorScheme(
        primary = p.tone(80),
        onPrimary = p.tone(20),
        primaryContainer = p.tone(30),
        onPrimaryContainer = p.tone(90),
        inversePrimary = p.tone(40),
        secondary = s.tone(80),
        onSecondary = s.tone(20),
        secondaryContainer = s.tone(30),
        onSecondaryContainer = s.tone(90),
        tertiary = t.tone(80),
        onTertiary = t.tone(20),
        tertiaryContainer = t.tone(30),
        onTertiaryContainer = t.tone(90),
        background = n.tone(6),
        onBackground = n.tone(90),
        surface = n.tone(6),
        onSurface = n.tone(90),
        surfaceVariant = nv.tone(30),
        onSurfaceVariant = nv.tone(80),
        surfaceTint = p.tone(80),
        inverseSurface = n.tone(90),
        inverseOnSurface = n.tone(20),
        error = e.tone(80),
        onError = e.tone(20),
        errorContainer = e.tone(30),
        onErrorContainer = e.tone(90),
        outline = nv.tone(60),
        outlineVariant = nv.tone(30),
        scrim = n.tone(0),
        surfaceBright = n.tone(24),
        surfaceDim = n.tone(6),
        surfaceContainer = n.tone(12),
        surfaceContainerHigh = n.tone(17),
        surfaceContainerHighest = n.tone(22),
        surfaceContainerLow = n.tone(10),
        surfaceContainerLowest = n.tone(4),
        primaryFixed = p.tone(90),
        primaryFixedDim = p.tone(80),
        onPrimaryFixed = p.tone(10),
        onPrimaryFixedVariant = p.tone(30),
        secondaryFixed = s.tone(90),
        secondaryFixedDim = s.tone(80),
        onSecondaryFixed = s.tone(10),
        onSecondaryFixedVariant = s.tone(30),
        tertiaryFixed = t.tone(90),
        tertiaryFixedDim = t.tone(80),
        onTertiaryFixed = t.tone(10),
        onTertiaryFixedVariant = t.tone(30),
    )
}
