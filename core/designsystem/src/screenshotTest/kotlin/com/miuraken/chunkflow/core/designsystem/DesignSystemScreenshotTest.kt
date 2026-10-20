package com.miuraken.chunkflow.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.android.tools.screenshot.PreviewTest
import com.miuraken.chunkflow.core.designsystem.component.language.LanguageRoleBadgePreview
import com.miuraken.chunkflow.core.designsystem.component.playback.PlaybackControlStripPreview
import com.miuraken.chunkflow.core.designsystem.component.playback.SpeedSheetContentPreview
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.preview.ColorRolesCatalogPreview
import com.miuraken.chunkflow.core.designsystem.preview.LanguagePaletteCatalogPreview
import com.miuraken.chunkflow.core.designsystem.preview.ShapesCatalogPreview
import com.miuraken.chunkflow.core.designsystem.preview.TypographyCatalogPreview

// Compose Preview Screenshot Testing（ADR 0002、§10.5）。デザインシステムの基準画像。
// テーマのカタログはライト / ダークだけ、共通部品は @ChunkFlowPreviews（文字サイズ・画面サイズ・UI 言語を含む）。

// ---- テーマのカタログ ----

@PreviewTest
@PreviewLightDark
@Composable
fun ThemeColorRolesScreenshot() = ColorRolesCatalogPreview()

@PreviewTest
@PreviewLightDark
@Composable
fun ThemeLanguagePaletteScreenshot() = LanguagePaletteCatalogPreview()

@PreviewTest
@PreviewLightDark
@Composable
fun ThemeTypographyScreenshot() = TypographyCatalogPreview()

@PreviewTest
@PreviewLightDark
@Composable
fun ThemeShapesScreenshot() = ShapesCatalogPreview()

// ---- 共通部品 ----

@PreviewTest
@ChunkFlowPreviews
@Composable
fun PlaybackControlStripScreenshot() = PlaybackControlStripPreview()

@PreviewTest
@ChunkFlowPreviews
@Composable
fun SpeedSheetContentScreenshot() = SpeedSheetContentPreview()

@PreviewTest
@ChunkFlowPreviews
@Composable
fun LanguageRoleBadgeScreenshot() = LanguageRoleBadgePreview()
