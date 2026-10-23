package com.miuraken.chunkflow.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.android.tools.screenshot.PreviewTest
import com.miuraken.chunkflow.core.designsystem.component.MissingLanguageHintWithSettingsLinkPreview
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowFloatingToolbarPreview
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowPlayPauseButtonPreview
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowProgressIndicatorsPreview
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowSplitButtonPreview
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowToggleButtonGroupPreview
import com.miuraken.chunkflow.core.designsystem.component.expressive.ChunkFlowTransportButtonPreview
import com.miuraken.chunkflow.core.designsystem.component.feedback.ChunkFlowSnackbarPreview
import com.miuraken.chunkflow.core.designsystem.component.language.LanguageRoleBadgePreview
import com.miuraken.chunkflow.core.designsystem.component.list.ChunkFlowListItemPreview
import com.miuraken.chunkflow.core.designsystem.component.playback.PlaybackControlStripPreview
import com.miuraken.chunkflow.core.designsystem.component.playback.SpeedSheetContentPreview
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.preview.ColorRolesCatalogPreview
import com.miuraken.chunkflow.core.designsystem.preview.FolderPaletteCatalogPreview
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
fun ThemeFolderPaletteScreenshot() = FolderPaletteCatalogPreview()

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

@PreviewTest
@ChunkFlowPreviews
@Composable
fun MissingLanguageHintWithSettingsLinkScreenshot() = MissingLanguageHintWithSettingsLinkPreview()

// ---- Expressive ラッパー（Phase B） ----

@PreviewTest
@ChunkFlowPreviews
@Composable
fun ChunkFlowToggleButtonGroupScreenshot() = ChunkFlowToggleButtonGroupPreview()

@PreviewTest
@ChunkFlowPreviews
@Composable
fun ChunkFlowSplitButtonScreenshot() = ChunkFlowSplitButtonPreview()

@PreviewTest
@ChunkFlowPreviews
@Composable
fun ChunkFlowFloatingToolbarScreenshot() = ChunkFlowFloatingToolbarPreview()

@PreviewTest
@ChunkFlowPreviews
@Composable
fun ChunkFlowProgressIndicatorsScreenshot() = ChunkFlowProgressIndicatorsPreview()

@PreviewTest
@ChunkFlowPreviews
@Composable
fun ChunkFlowPlayPauseButtonScreenshot() = ChunkFlowPlayPauseButtonPreview()

@PreviewTest
@ChunkFlowPreviews
@Composable
fun ChunkFlowTransportButtonScreenshot() = ChunkFlowTransportButtonPreview()

// ---- Snackbar とリスト行（Phase B） ----

@PreviewTest
@ChunkFlowPreviews
@Composable
fun ChunkFlowSnackbarScreenshot() = ChunkFlowSnackbarPreview()

@PreviewTest
@ChunkFlowPreviews
@Composable
fun ChunkFlowListItemScreenshot() = ChunkFlowListItemPreview()
