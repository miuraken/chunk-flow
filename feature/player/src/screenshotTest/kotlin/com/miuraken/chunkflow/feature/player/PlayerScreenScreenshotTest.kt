package com.miuraken.chunkflow.feature.player

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.playback.PlaybackError
import com.miuraken.chunkflow.core.testing.fake.FakePositionProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials

// Compose Preview Screenshot Testing（ADR 0002、§10.5）。

@PreviewTest
@ChunkFlowPreviews
@Composable
fun PlayerScreenPlayingScreenshot() {
    val item = SampleMaterials.jaKo.item
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
            ),
            positionProvider = FakePositionProvider(12_000L),
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
