package com.miuraken.chunkflow.feature.library

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.miuraken.chunkflow.core.data.library.ImportError
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.testing.fixture.SampleLibraries

// Compose Preview Screenshot Testing（ADR 0002、§10.5）。
// 基準画像は src/screenshotTestDebug/reference/ に置く。

@PreviewTest
@ChunkFlowPreviews
@Composable
fun LibraryScreenEmptyScreenshot() {
    ChunkFlowTheme {
        LibraryScreen(state = LibraryUiState(), onEvent = {})
    }
}

@PreviewTest
@ChunkFlowPreviews
@Composable
fun LibraryScreenPopulatedScreenshot() {
    val items = SampleLibraries.multipleCourses.take(4).mapIndexed { index, item ->
        LibraryItemUiModel(
            item = item,
            positionMs = if (index == 0) 12_000L else 0L,
            speed = if (index == 0) 1.25f else 1.0f,
        )
    }
    val first = SampleLibraries.multipleCourses.first()
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = items,
                importState = LibraryImportUiState.Error(ImportError.NO_SPACE),
                miniPlayer = MiniPlayerUiState(
                    mediaId = first.id,
                    title = first.title,
                    isPlaying = true,
                    durationMs = first.durationMs,
                ),
            ),
            onEvent = {},
        )
    }
}
