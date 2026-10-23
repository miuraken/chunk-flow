package com.miuraken.chunkflow.feature.player

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.SttEngine
import com.miuraken.chunkflow.core.model.TranscriptionStep
import com.miuraken.chunkflow.core.playback.PlaybackError
import com.miuraken.chunkflow.core.testing.fake.FakePositionProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials

// Compose Preview Screenshot Testing（ADR 0002、§10.5）。

@PreviewTest
@ChunkFlowPreviews
@Composable
fun PlayerScreenPlayingScreenshot() {
    val sample = SampleMaterials.jaKo
    val item = sample.item
    val chunks = sample.chunks.map { chunk ->
        val transcript = sample.transcripts[chunk.id]
        ChunkUi(
            id = chunk.id,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            text = transcript?.text,
            language = transcript?.language,
            textVariants = transcript?.textVariants.orEmpty(),
            origin = chunk.origin,
        )
    }
    ChunkFlowTheme {
        PlayerScreen(
            state = PlayerUiState(
                mediaId = item.id,
                title = item.title,
                course = item.course,
                languagePair = item.languagePair,
                isPlaying = true,
                durationMs = item.durationMs,
                speed = 1.25f,
                practice = PracticeSettings(
                    loop = LoopMode.SingleChunk(chunks.first().id, times = 3),
                    autoPause = AutoPause.Fixed(1_500L),
                ),
                waveform = sample.waveform,
                chunks = chunks,
                activeChunkIndex = 2,
                analysis = mapOf(
                    AnalysisStage.FEATURES to StageStatus.Done,
                    AnalysisStage.SEGMENTATION to StageStatus.Done,
                ),
                blindMode = BlindMode.OFF,
                chunkSource = ChunkOrigin.IMPORTED,
                availableChunkOrigins = setOf(ChunkOrigin.IMPORTED, ChunkOrigin.AUTO),
            ),
            positionProvider = FakePositionProvider(12_000L),
            onEvent = {},
        )
    }
}

@PreviewTest
@ChunkFlowPreviews
@Composable
fun PlayerScreenBlindModeScreenshot() {
    val sample = SampleMaterials.jaZh
    val item = sample.item
    val chunks = sample.chunks.map { chunk ->
        val transcript = sample.transcripts[chunk.id]
        ChunkUi(
            id = chunk.id,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            text = transcript?.text,
            language = transcript?.language,
            textVariants = transcript?.textVariants.orEmpty(),
            origin = chunk.origin,
        )
    }
    ChunkFlowTheme {
        PlayerScreen(
            state = PlayerUiState(
                mediaId = item.id,
                title = item.title,
                course = item.course,
                languagePair = item.languagePair,
                isPlaying = false,
                durationMs = item.durationMs,
                speed = 1.0f,
                waveform = sample.waveform,
                chunks = chunks,
                activeChunkIndex = 1,
                analysis = mapOf(
                    AnalysisStage.FEATURES to StageStatus.Done,
                    AnalysisStage.SEGMENTATION to StageStatus.Done,
                ),
                blindMode = BlindMode.HIDE_TARGET_ONLY,
                revealedChunkIds = setOfNotNull(chunks.getOrNull(3)?.id),
            ),
            positionProvider = FakePositionProvider(7_000L),
            onEvent = {},
        )
    }
}

@PreviewTest
@ChunkFlowPreviews
@Composable
fun PlayerScreenSourceMissingErrorScreenshot() {
    val item = SampleMaterials.enYue.item
    ChunkFlowTheme {
        PlayerScreen(
            state = PlayerUiState(
                mediaId = item.id,
                title = item.title,
                course = item.course,
                languagePair = item.languagePair,
                isPlaying = false,
                durationMs = item.durationMs,
                speed = 1.0f,
                error = PlaybackError.SOURCE_MISSING,
            ),
            positionProvider = FakePositionProvider(0L),
            onEvent = {},
        )
    }
}

/** 文字起こしの実行中（チャンクリストの下部に進捗のカードが重なる状態）。 */
@PreviewTest
@ChunkFlowPreviews
@Composable
fun PlayerScreenTranscriptionProgressScreenshot() {
    val sample = SampleMaterials.jaKo
    val item = sample.item
    // 先頭の 2 件は文字起こし済み、3・4 件目は言語判定済み・文字起こし未完了、5 件目は未判定にする
    val chunks = sample.chunks.mapIndexed { index, chunk ->
        val transcript = sample.transcripts[chunk.id]
        val isTranscribed = index < 2
        ChunkUi(
            id = chunk.id,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            text = if (isTranscribed) transcript?.text else null,
            language = if (index < 4) transcript?.language else null,
            textVariants = if (isTranscribed) transcript?.textVariants.orEmpty() else emptyMap(),
            origin = chunk.origin,
        )
    }
    ChunkFlowTheme {
        PlayerScreen(
            state = PlayerUiState(
                mediaId = item.id,
                title = item.title,
                course = item.course,
                languagePair = item.languagePair,
                isPlaying = true,
                durationMs = item.durationMs,
                speed = 1.0f,
                waveform = sample.waveform,
                chunks = chunks,
                activeChunkIndex = 0,
                analysis = mapOf(
                    AnalysisStage.FEATURES to StageStatus.Done,
                    AnalysisStage.SEGMENTATION to StageStatus.Done,
                ),
                lookaheadStatus = LookaheadTranscriptionStatus(
                    enabled = true,
                    engineAvailable = true,
                    inFlightChunkId = chunks.getOrNull(2)?.id,
                    queuedChunkIds = chunks.drop(3).map { it.id }.toSet(),
                    completedCount = 2,
                    totalTargetCount = chunks.size,
                    inFlightStep = TranscriptionStep.DETECTING_LANGUAGE,
                    inFlightDetail = "ko / ja",
                ),
            ),
            positionProvider = FakePositionProvider(2_000L),
            onEvent = {},
        )
    }
}

/**
 * ファイルの設定の「言語」タブ: 学習言語とベース言語のプルダウンの下に、言語が選択肢にないときの案内文を出し、
 * 文中の「⚙ 全体設定（言語）」をリンクにする（ライブラリのシートと同じ部品。ボタンと区切り線はない）。
 */
@PreviewTest
@ChunkFlowPreviews
@Composable
fun PlayerFileSettingsLanguageTabScreenshot() {
    ChunkFlowTheme {
        Surface {
            ResegmentSheetContent(
                initialParams = SegmentationParams(),
                useGlobalSegmentation = true,
                languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
                itemSttOverride = ItemSttOverride.NONE,
                sttEngineByLanguage = emptyMap(),
                languageModelInfos = emptyMap(),
                enabledLanguages = listOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ko")),
                onChangeLanguages = {},
                onChangeTargetEngine = {},
                onChangeBaseEngine = {},
                transcriptOffsetMs = 0,
                onAdjustTranscriptOffset = {},
                onAutoAlignTranscriptOffset = {},
                onImportTranscript = {},
                onExportTranscript = {},
                onResetTranscripts = {},
                onOpenDiagnostics = {},
                onToggleUseGlobalSegmentation = {},
                onApply = {},
                onOpenGlobalSettings = {},
                onClose = {},
                initialTabIndex = 0,
            )
        }
    }
}

/**
 * ファイルの設定の「文字起こし」タブ: 内包するフォルダの設定が学習対象言語のエンジンを上書きしているので、
 * チェックボックスと学習対象言語のアコーディオンが「フォルダー設定に従う」になる（ベース言語は「全体設定に従う」）。
 */
@PreviewTest
@ChunkFlowPreviews
@Composable
fun PlayerFileSettingsFollowFolderScreenshot() {
    ChunkFlowTheme {
        Surface {
            ResegmentSheetContent(
                initialParams = SegmentationParams(),
                useGlobalSegmentation = true,
                languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
                itemSttOverride = ItemSttOverride.NONE,
                folderSttOverride = ItemSttOverride(targetEngine = SttEngine.SHERPA_ONNX_SENSE_VOICE),
                sttEngineByLanguage = emptyMap(),
                languageModelInfos = emptyMap(),
                enabledLanguages = listOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ko")),
                onChangeLanguages = {},
                onChangeTargetEngine = {},
                onChangeBaseEngine = {},
                transcriptOffsetMs = 0,
                onAdjustTranscriptOffset = {},
                onAutoAlignTranscriptOffset = {},
                onImportTranscript = {},
                onExportTranscript = {},
                onResetTranscripts = {},
                onOpenDiagnostics = {},
                onToggleUseGlobalSegmentation = {},
                onApply = {},
                onOpenGlobalSettings = {},
                onClose = {},
                initialTabIndex = 1,
            )
        }
    }
}
