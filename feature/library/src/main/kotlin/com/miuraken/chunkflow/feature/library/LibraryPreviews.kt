package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewParameter
import com.miuraken.chunkflow.core.data.library.ImportError
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.LibraryFolderKind
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.testing.fixture.SampleLibraryPreviewParameterProvider
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials

@ChunkFlowPreviews
@Composable
private fun LibraryScreenPreview(
    @PreviewParameter(SampleLibraryPreviewParameterProvider::class) items: List<AudioItem>,
) {
    val uiItems = items.mapIndexed { index, audioItem ->
        LibraryItemUiModel(
            item = audioItem,
            positionMs = if (index == 0) 12_000L else 0L,
            speed = if (index == 0) 1.25f else 1.0f,
            chunkCount = if (index == 0) 6 else 0,
            hasTranscript = index == 0,
            isListened = index == 0,
            lastAccessedAtEpochMs = if (index == 0) 1_760_000_000_000L else null,
        )
    }
    val miniPlayer = items.firstOrNull()?.let { first ->
        MiniPlayerUiState(
            mediaId = first.id,
            title = first.title,
            isPlaying = true,
            durationMs = first.durationMs,
        )
    }
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = uiItems,
                courseFolders = listOf(
                    LibraryCourseFolder(
                        coursePath = "korean-course",
                        kind = LibraryFolderKind.LOCAL_DIRECTORY,
                        languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
                    ),
                ),
                miniPlayer = miniPlayer,
            ),
            onEvent = {},
        )
    }
}

/** フォルダを開いた状態。95% を超えて聴いた既習の教材は、進捗バーと再生状況が緑系になる。 */
@ChunkFlowPreviews
@Composable
private fun LibraryScreenFolderPreview() {
    val course = "ピンズラー韓国語 I"
    val baseItem = SampleMaterials.jaKo.item
    val uiItems = listOf(
        LibraryItemUiModel(
            item = baseItem.copy(
                id = MediaId("pimsleur-01"),
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
                id = MediaId("pimsleur-02"),
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
                id = MediaId("pimsleur-03"),
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
                id = MediaId("pimsleur-04"),
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
                id = MediaId("hindi-$num"),
                title = "Lesson $num",
                course = "Hindi_1",
                languagePair = LanguagePair(target = LanguageTag("hi"), base = LanguageTag("ja")),
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
                    LibraryCourseFolder(
                        coursePath = course,
                        kind = LibraryFolderKind.LOCAL_DIRECTORY,
                        languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
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

@ChunkFlowPreviews
@Composable
private fun LibraryScreenImportErrorPreview() {
    val first = SampleMaterials.jaKo.item
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = listOf(
                    LibraryItemUiModel(
                        item = first,
                        positionMs = 15_000L,
                        speed = 1.0f,
                        chunkCount = 6,
                        hasTranscript = true,
                    ),
                ),
                importState = LibraryImportUiState.Error(ImportError.UNSUPPORTED_FORMAT),
            ),
            onEvent = {},
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun LibraryScreenImportConfigDialogPreview() {
    val first = SampleMaterials.jaKo.item
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = listOf(
                    LibraryItemUiModel(
                        item = first,
                        positionMs = 15_000L,
                        speed = 1.0f,
                        chunkCount = 6,
                        hasTranscript = true,
                    ),
                ),
                pendingImportConfig = PendingImportConfig(
                    uris = emptyList(),
                    initialCourse = LibraryCourseFolder.DEFAULT_IMPORTED_COURSE,
                    initialLanguages = LanguagePair(
                        target = LanguageTag("ko"),
                        base = LanguageTag("ja"),
                    ),
                ),
            ),
            onEvent = {},
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun LibraryScreenFilesTabPreview() {
    val materials = listOf(SampleMaterials.jaKo, SampleMaterials.longSentence, SampleMaterials.noTranscript)
    val uiItems = materials.mapIndexed { index, material ->
        LibraryItemUiModel(
            item = material.item,
            positionMs = if (index == 0) 12_000L else 0L,
            isListened = index == 0,
            lastAccessedAtEpochMs = if (index == 0) 1_760_000_000_000L else null,
        )
    }
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = uiItems,
                selectedTab = LibraryViewTab.FILES,
                favoriteItemIds = setOf(materials.first().item.id),
            ),
            onEvent = {},
        )
    }
}

@ChunkFlowPreviews
@Composable
private fun LibraryScreenPlaylistsTabPreview() {
    val materials = listOf(SampleMaterials.jaKo, SampleMaterials.longSentence)
    val uiItems = materials.mapIndexed { index, material ->
        LibraryItemUiModel(
            item = material.item,
            positionMs = if (index == 0) 12_000L else 0L,
            isListened = index == 0,
            lastAccessedAtEpochMs = 1_760_000_000_000L,
        )
    }
    val first = materials.first().item
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = uiItems,
                selectedTab = LibraryViewTab.PLAYLISTS,
                favoriteItemIds = setOf(first.id),
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

@ChunkFlowPreviews
@Composable
private fun LibraryScreenBookmarksTabPreview() {
    val jaKo = SampleMaterials.jaKo
    val longSentence = SampleMaterials.longSentence
    val firstBookmarkedChunks = jaKo.chunks.take(3).mapIndexed { idx, chunk ->
        LibraryBookmarkedChunkUiModel(
            chunk = chunk,
            chunkNumber = idx + 1,
            transcript = jaKo.transcripts[chunk.id],
        )
    }
    val secondBookmarkedChunks = longSentence.chunks.take(2).mapIndexed { idx, chunk ->
        LibraryBookmarkedChunkUiModel(
            chunk = chunk,
            chunkNumber = idx + 1,
            transcript = longSentence.transcripts[chunk.id],
        )
    }
    val uiItems = listOf(
        LibraryItemUiModel(
            item = jaKo.item.copy(
                title = "ピンズラー 韓国語 01",
                course = "ピンズラー韓国語 I",
            ),
            chunkCount = jaKo.chunks.size,
            hasTranscript = true,
            transcriptStatus = LibraryTranscriptStatus.COMPLETED,
            transcriptProgressPercent = 100,
        ),
        LibraryItemUiModel(
            item = longSentence.item.copy(
                title = "ニュースリスニング 第3課",
                course = "中級韓国語",
            ),
            chunkCount = longSentence.chunks.size,
            hasTranscript = true,
            transcriptStatus = LibraryTranscriptStatus.PARTIAL,
            transcriptProgressPercent = 60,
        ),
    )
    val activeChunk = firstBookmarkedChunks.first()
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = uiItems,
                selectedTab = LibraryViewTab.BOOKMARKS,
                bookmarkedChunksByMedia = mapOf(
                    jaKo.item.id to firstBookmarkedChunks,
                    longSentence.item.id to secondBookmarkedChunks,
                ),
                expandedBookmarkMediaIds = setOf(jaKo.item.id),
                activePlaybackMediaId = jaKo.item.id,
                activePlaybackChunkId = activeChunk.chunk.id,
                miniPlayer = MiniPlayerUiState(
                    mediaId = jaKo.item.id,
                    title = "ピンズラー 韓国語 01",
                    isPlaying = true,
                    durationMs = jaKo.item.durationMs,
                    positionMs = activeChunk.chunk.startMs,
                ),
            ),
            onEvent = {},
        )
    }
}
