package com.miuraken.chunkflow.core.designsystem.preview

import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewFontScale
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.tooling.preview.PreviewScreenSizes

/**
 * Chunk Flow で確認する表示条件をまとめたマルチプレビュー注釈（§10.5.1）。
 *
 * - ライト / ダーク（[PreviewLightDark]）
 * - 文字サイズ（[PreviewFontScale]。最大 2.0 倍）
 * - 画面サイズ（[PreviewScreenSizes]。スマートフォン・折りたたみ・タブレットなど）
 * - UI 言語（日本語 / 英語）
 *
 * 画面と主要な部品のプレビューに付ける。スクリーンショットテストでは `@PreviewTest` と一緒に使う。
 */
@PreviewLightDark
@PreviewFontScale
@PreviewScreenSizes
@Preview(name = "UI 言語: 日本語", locale = "ja", showBackground = true)
@Preview(name = "UI 言語: 英語", locale = "en", showBackground = true)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.FUNCTION)
annotation class ChunkFlowPreviews
