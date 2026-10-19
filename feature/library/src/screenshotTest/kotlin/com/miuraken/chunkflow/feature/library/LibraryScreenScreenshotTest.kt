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
            chunkCount = if (index == 0) 6 else 0,
            hasTranscript = index == 0,
            isListened = index == 0,
            isAnalyzing = index == 1,
        )
    }
    val first = SampleLibraries.multipleCourses.first()
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = items,
                courseFolders = listOf(
                    com.miuraken.chunkflow.core.model.LibraryCourseFolder(
                        coursePath = first.course ?: "korean-course",
                        kind = com.miuraken.chunkflow.core.model.LibraryFolderKind.LOCAL_DIRECTORY,
                        languagePair = first.languagePair,
                    ),
                ),
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

@PreviewTest
@ChunkFlowPreviews
@Composable
fun LibraryScreenFolderFilesScreenshot() {
    val course = "ピンズラー韓国語 I"
    val baseItem = SampleLibraries.multipleCourses.first()
    val uiItems = listOf(
        LibraryItemUiModel(
            item = baseItem.copy(
                id = com.miuraken.chunkflow.core.model.MediaId("pimsleur-01"),
                title = "ピンズラー 韓国語 01",
                durationMs = 1_834_000L,
                course = course,
                localFilePath = "preview:cover_pimsleur",
            ),
            positionMs = 1_806_000L,
            maxPositionMs = 1_816_000L,
            hasTranscript = true,
            transcriptStatus = LibraryTranscriptStatus.COMPLETED,
            transcriptProgressPercent = 100,
            isListened = true,
            lastAccessedAtEpochMs = 1_792_338_600_000L,
        ),
        LibraryItemUiModel(
            item = baseItem.copy(
                id = com.miuraken.chunkflow.core.model.MediaId("pimsleur-02"),
                title = "ピンズラー 韓国語 02",
                durationMs = 1_825_000L,
                course = course,
                localFilePath = "preview:cover_book",
            ),
            positionMs = 586_000L,
            maxPositionMs = 949_000L,
            hasTranscript = true,
            transcriptStatus = LibraryTranscriptStatus.PARTIAL,
            transcriptProgressPercent = 82,
            lastAccessedAtEpochMs = 1_792_338_600_000L,
        ),
        LibraryItemUiModel(
            item = baseItem.copy(
                id = com.miuraken.chunkflow.core.model.MediaId("pimsleur-03"),
                title = "ピンズラー 韓国語 03",
                durationMs = 1_710_000L,
                course = course,
                localFilePath = "preview:cover_pimsleur",
            ),
            positionMs = 0L,
            hasTranscript = true,
            transcriptStatus = LibraryTranscriptStatus.PARTIAL,
            transcriptProgressPercent = 35,
            isTranscribing = true,
        ),
        LibraryItemUiModel(
            item = baseItem.copy(
                id = com.miuraken.chunkflow.core.model.MediaId("pimsleur-04"),
                title = "ピンズラー 韓国語 04",
                durationMs = 1_669_000L,
                course = course,
            ),
            positionMs = 0L,
            hasTranscript = false,
            transcriptStatus = LibraryTranscriptStatus.NONE,
        ),
    )
    val recentHindiItems = listOf(1, 3, 7, 8).mapIndexed { idx, num ->
        LibraryItemUiModel(
            item = baseItem.copy(
                id = com.miuraken.chunkflow.core.model.MediaId("hindi-$num"),
                title = "Lesson $num",
                course = "Hindi_1",
                languagePair = com.miuraken.chunkflow.core.model.LanguagePair(
                    target = com.miuraken.chunkflow.core.model.LanguageTag("hi"),
                    base = com.miuraken.chunkflow.core.model.LanguageTag("ja"),
                ),
            ),
            positionMs = (10 - idx) * 60_000L,
            maxPositionMs = (12 - idx) * 60_000L,
            lastAccessedAtEpochMs = 1_792_338_000_000L - idx * 60_000L,
        )
    }
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = uiItems + recentHindiItems,
                currentDirectoryPath = course,
                courseFolders = listOf(
                    com.miuraken.chunkflow.core.model.LibraryCourseFolder(
                        coursePath = course,
                        kind = com.miuraken.chunkflow.core.model.LibraryFolderKind.LOCAL_DIRECTORY,
                        languagePair = baseItem.languagePair,
                    ),
                ),
                miniPlayer = MiniPlayerUiState(
                    mediaId = uiItems.first().item.id,
                    title = "Lesson 1",
                    isPlaying = false,
                    durationMs = 1_852_000L,
                    positionMs = 102_000L,
                    localFilePath = "preview:cover_pimsleur",
                ),
            ),
            onEvent = {},
        )
    }
}

@PreviewTest
@ChunkFlowPreviews
@Composable
fun LibraryScreenFilesTabScreenshot() {
    val items = SampleLibraries.multipleCourses.take(3).mapIndexed { index, item ->
        LibraryItemUiModel(
            item = item,
            positionMs = if (index == 0) 12_000L else 0L,
            maxPositionMs = if (index == 0) 12_000L else 0L,
            speed = if (index == 0) 1.25f else 1.0f,
            chunkCount = if (index == 0) 6 else 0,
            hasTranscript = index == 0,
            transcriptStatus = if (index == 0) LibraryTranscriptStatus.COMPLETED else LibraryTranscriptStatus.NONE,
            transcriptProgressPercent = if (index == 0) 100 else 0,
            isListened = index == 0,
            lastAccessedAtEpochMs = if (index == 0) 1_792_338_600_000L else null,
        )
    }
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = items,
                selectedTab = LibraryViewTab.FILES,
                fileSortOption = LibraryFileSortOption.ACCESS_TIME,
            ),
            onEvent = {},
        )
    }
}
