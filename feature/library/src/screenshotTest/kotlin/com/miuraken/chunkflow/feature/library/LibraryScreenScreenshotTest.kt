package com.miuraken.chunkflow.feature.library

import androidx.compose.runtime.Composable
import com.android.tools.screenshot.PreviewTest
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.testing.fixture.SampleLibraries

// Compose Preview Screenshot Testing の動作確認用（ADR 0002）。
// 基準画像は src/screenshotTestDebug/reference/ に置く。見た目が変わる更新は人間の承認を得てからマージする（§10.5.3）。

@PreviewTest
@ChunkFlowPreviews
@Composable
fun LibraryScreenEmptyScreenshot() {
    ChunkFlowTheme {
        LibraryScreen(state = LibraryUiState(SampleLibraries.empty), onEvent = {})
    }
}
