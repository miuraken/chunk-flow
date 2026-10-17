package com.miuraken.chunkflow.feature.player

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.PracticeSettings
import com.miuraken.chunkflow.core.model.StageStatus
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
    // 先頭の 2 件は文字起こし済み、3 件目を文字起こし中、残りは待機中にする
    val chunks = sample.chunks.mapIndexed { index, chunk ->
        val transcript = sample.transcripts[chunk.id]
        val isTranscribed = index < 2
        ChunkUi(
            id = chunk.id,
            startMs = chunk.startMs,
            endMs = chunk.endMs,
            text = if (isTranscribed) transcript?.text else null,
            language = if (isTranscribed) transcript?.language else null,
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
