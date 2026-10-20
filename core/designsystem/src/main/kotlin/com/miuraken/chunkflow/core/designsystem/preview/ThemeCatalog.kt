package com.miuraken.chunkflow.core.designsystem.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowExtendedColors
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowLanguagePalette
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowExtendedColors
import com.miuraken.chunkflow.core.designsystem.theme.chunkFlowLanguagePalette
import com.miuraken.chunkflow.core.designsystem.theme.extendedColorsFor
import com.miuraken.chunkflow.core.designsystem.theme.languagePaletteFor

/*
 * テーマのカタログ（デザインシステムの確認用プレビュー。Phase A）。
 *
 * 画面ではなく、カラーロール・言語パレット・拡張色・Typography・Shapes を一覧するための Composable。
 * スクリーンショットテスト（`src/screenshotTest/`）から `@PreviewTest` で参照する。
 */

/** 色のサンプル 1 枚（背景色 + その上の文字色でラベルを描く）。 */
@Composable
private fun Swatch(
    label: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(container)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 色のサンプル（背景だけ。文字色はテーマの `onSurface`）。 */
@Composable
private fun PlainSwatch(label: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 28.dp)
                .clip(MaterialTheme.shapes.extraSmall)
                .background(color)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall),
        )
        Text(text = label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** `MaterialTheme.colorScheme` の全ロール一覧。 */
@Composable
internal fun ColorRolesCatalog(modifier: Modifier = Modifier) {
    val c: ColorScheme = MaterialTheme.colorScheme
    val rows: List<List<Triple<String, Color, Color>>> = listOf(
        listOf(
            Triple("primary", c.primary, c.onPrimary),
            Triple("primaryContainer", c.primaryContainer, c.onPrimaryContainer),
            Triple("primaryFixed", c.primaryFixed, c.onPrimaryFixed),
            Triple("primaryFixedDim", c.primaryFixedDim, c.onPrimaryFixedVariant),
        ),
        listOf(
            Triple("secondary", c.secondary, c.onSecondary),
            Triple("secondaryContainer", c.secondaryContainer, c.onSecondaryContainer),
            Triple("secondaryFixed", c.secondaryFixed, c.onSecondaryFixed),
            Triple("secondaryFixedDim", c.secondaryFixedDim, c.onSecondaryFixedVariant),
        ),
        listOf(
            Triple("tertiary", c.tertiary, c.onTertiary),
            Triple("tertiaryContainer", c.tertiaryContainer, c.onTertiaryContainer),
            Triple("tertiaryFixed", c.tertiaryFixed, c.onTertiaryFixed),
            Triple("tertiaryFixedDim", c.tertiaryFixedDim, c.onTertiaryFixedVariant),
        ),
        listOf(
            Triple("error", c.error, c.onError),
            Triple("errorContainer", c.errorContainer, c.onErrorContainer),
            Triple("inverseSurface", c.inverseSurface, c.inverseOnSurface),
            Triple("inversePrimary", c.inversePrimary, c.onPrimaryContainer),
        ),
        listOf(
            Triple("surface", c.surface, c.onSurface),
            Triple("surfaceDim", c.surfaceDim, c.onSurface),
            Triple("surfaceBright", c.surfaceBright, c.onSurface),
            Triple("surfaceVariant", c.surfaceVariant, c.onSurfaceVariant),
        ),
        listOf(
            Triple("containerLowest", c.surfaceContainerLowest, c.onSurface),
            Triple("containerLow", c.surfaceContainerLow, c.onSurface),
            Triple("container", c.surfaceContainer, c.onSurface),
            Triple("containerHigh", c.surfaceContainerHigh, c.onSurface),
        ),
        listOf(
            Triple("containerHighest", c.surfaceContainerHighest, c.onSurface),
            Triple("outline", c.outline, c.surface),
            Triple("outlineVariant", c.outlineVariant, c.onSurface),
            Triple("surfaceTint", c.surfaceTint, c.onPrimary),
        ),
    )
    Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text = "Color roles", style = MaterialTheme.typography.titleMedium)
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { (label, container, content) ->
                    Swatch(label = label, container = container, content = content, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 言語パレットと拡張色を折り返しながら並べる。 */
@Composable
private fun PaletteRow(
    title: String,
    primary: Color,
    palette: ChunkFlowLanguagePalette,
    extended: ChunkFlowExtendedColors,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = title, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PlainSwatch("primary", primary)
            Swatch("base badge", palette.baseBadgeContainer, palette.onBaseBadgeContainer, Modifier.width(72.dp))
            PlainSwatch("base row", palette.baseRowBackground)
            PlainSwatch("base active", palette.baseActiveRowBackground)
            PlainSwatch("base loop", palette.baseLoopRowBackground)
            PlainSwatch("base chunk", palette.baseChunkBackground)
            PlainSwatch("base rms", palette.baseWaveformRms)
            PlainSwatch("base peak", palette.baseWaveformPeak)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Swatch("target badge", palette.targetBadgeContainer, palette.onTargetBadgeContainer, Modifier.width(72.dp))
            PlainSwatch("target row", palette.targetRowBackground)
            PlainSwatch("target active", palette.targetActiveRowBackground)
            PlainSwatch("target loop", palette.targetLoopRowBackground)
            PlainSwatch("target chunk", palette.targetChunkBackground)
            PlainSwatch("target rms", palette.targetWaveformRms)
            PlainSwatch("target peak", palette.targetWaveformPeak)
            PlainSwatch("completed", palette.completedProgressIndicator)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Swatch("favorite", extended.favorite, extended.onFavorite, Modifier.width(72.dp))
            Swatch("success", extended.success, extended.onSuccess, Modifier.width(72.dp))
            Swatch("successContainer", extended.successContainer, extended.onSuccessContainer, Modifier.width(96.dp))
            Swatch("wholeFileRepeat", extended.wholeFileRepeat, extended.onWholeFileRepeat, Modifier.width(96.dp))
            Swatch("transcriptCompleted", extended.transcriptCompleted, extended.onTranscriptCompleted, Modifier.width(112.dp))
        }
    }
}

/** 代表的な `primary` に対する言語パレットと拡張色の一覧（現在のテーマ + 3 色）。 */
@Composable
internal fun LanguagePaletteCatalog(darkTheme: Boolean, modifier: Modifier = Modifier) {
    val samples = listOf(
        "赤 #B3261E" to Color(0xFFB3261E),
        "紫 #6750A4" to Color(0xFF6750A4),
        "橙 #E65100" to Color(0xFFE65100),
    )
    Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = "Language palette / extended colors", style = MaterialTheme.typography.titleMedium)
        PaletteRow(
            title = "primary = テーマ（Teal）",
            primary = MaterialTheme.colorScheme.primary,
            palette = chunkFlowLanguagePalette(),
            extended = chunkFlowExtendedColors(),
        )
        samples.forEach { (name, primary) ->
            PaletteRow(
                title = "primary = $name",
                primary = primary,
                palette = languagePaletteFor(primary, darkTheme),
                extended = extendedColorsFor(primary, darkTheme),
            )
        }
    }
}

/** Typography の 15 スタイル一覧。 */
@Composable
internal fun TypographyCatalog(modifier: Modifier = Modifier) {
    val t = MaterialTheme.typography
    val styles: List<Pair<String, TextStyle>> = listOf(
        "displayLarge" to t.displayLarge,
        "displayMedium" to t.displayMedium,
        "displaySmall" to t.displaySmall,
        "headlineLarge" to t.headlineLarge,
        "headlineMedium" to t.headlineMedium,
        "headlineSmall" to t.headlineSmall,
        "titleLarge" to t.titleLarge,
        "titleMedium" to t.titleMedium,
        "titleSmall" to t.titleSmall,
        "bodyLarge" to t.bodyLarge,
        "bodyMedium" to t.bodyMedium,
        "bodySmall" to t.bodySmall,
        "labelLarge" to t.labelLarge,
        "labelMedium" to t.labelMedium,
        "labelSmall" to t.labelSmall,
    )
    Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text = "Typography", style = MaterialTheme.typography.titleMedium)
        styles.forEach { (name, style) ->
            Text(text = "$name あいう Aa", style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Shapes の 5 段階一覧。 */
@Composable
internal fun ShapesCatalog(modifier: Modifier = Modifier) {
    val s = MaterialTheme.shapes
    val shapes: List<Pair<String, Shape>> = listOf(
        "extraSmall 4" to s.extraSmall,
        "small 8" to s.small,
        "medium 12" to s.medium,
        "large 16" to s.large,
        "extraLarge 28" to s.extraLarge,
    )
    Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = "Shapes", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            shapes.forEach { (name, shape) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .border(1.dp, MaterialTheme.colorScheme.primary, shape),
                    )
                    Text(text = name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
internal fun ColorRolesCatalogPreview() {
    ChunkFlowTheme {
        Surface(modifier = Modifier.fillMaxWidth()) { ColorRolesCatalog() }
    }
}

@PreviewLightDark
@Composable
internal fun LanguagePaletteCatalogPreview() {
    ChunkFlowTheme {
        Surface(modifier = Modifier.fillMaxWidth()) { LanguagePaletteCatalog(darkTheme = isSystemInDarkTheme()) }
    }
}

@PreviewLightDark
@Composable
internal fun TypographyCatalogPreview() {
    ChunkFlowTheme {
        Surface(modifier = Modifier.fillMaxWidth()) { TypographyCatalog() }
    }
}

@PreviewLightDark
@Composable
internal fun ShapesCatalogPreview() {
    ChunkFlowTheme {
        Surface(modifier = Modifier.fillMaxWidth()) { ShapesCatalog() }
    }
}
