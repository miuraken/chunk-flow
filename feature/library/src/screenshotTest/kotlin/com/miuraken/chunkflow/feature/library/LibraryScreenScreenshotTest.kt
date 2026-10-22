package com.miuraken.chunkflow.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.miuraken.chunkflow.core.data.library.ImportError
import com.miuraken.chunkflow.core.designsystem.preview.ChunkFlowPreviews
import com.miuraken.chunkflow.core.designsystem.theme.ChunkFlowTheme
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.LibraryFolderKind
import com.miuraken.chunkflow.core.model.MediaId
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

@PreviewTest
@ChunkFlowPreviews
@Composable
fun LibraryScreenBookmarksTabScreenshot() {
    val jaKo = com.miuraken.chunkflow.core.testing.fixture.SampleMaterials.jaKo
    val longSentence = com.miuraken.chunkflow.core.testing.fixture.SampleMaterials.longSentence
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

// ---- タグとフォルダの色分け（ADR 0013） ----

private fun taggedSampleItem(
    id: String,
    title: String,
    course: String?,
    target: String = "ko",
): AudioItem = SampleLibraries.multipleCourses.first().copy(
    id = MediaId(id),
    title = title,
    course = course,
    languagePair = LanguagePair(target = LanguageTag(target), base = LanguageTag("ja")),
)

private fun sampleFolder(
    path: String,
    target: String,
    kind: LibraryFolderKind,
    colorIndex: Int? = null,
): LibraryCourseFolder = LibraryCourseFolder(
    coursePath = path,
    kind = kind,
    languagePair = LanguagePair(target = LanguageTag(target), base = LanguageTag("ja")),
    colorIndex = colorIndex,
)

/** 対象言語ごとの色（追加された順）と、フォルダに指定した色。実フォルダと仮想フォルダは「仮想」の表示で見分ける。 */
@PreviewTest
@ChunkFlowPreviews
@Composable
fun LibraryScreenFolderColorsScreenshot() {
    val items = listOf(
        taggedSampleItem("ko-1", "ピンズラー 韓国語 01", course = "ピンズラー韓国語 I", target = "ko"),
        taggedSampleItem("ko-2", "ピンズラー 韓国語 02", course = "ピンズラー韓国語 I", target = "ko"),
        taggedSampleItem("en-1", "Episode 1", course = "English Podcast", target = "en"),
        taggedSampleItem("zh-1", "新闻 01", course = "中国語ニュース", target = "zh"),
        taggedSampleItem("es-1", "Lección 1", course = "スペイン語", target = "es"),
    ).map { LibraryItemUiModel(item = it) }
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = items,
                courseFolders = listOf(
                    sampleFolder("ピンズラー韓国語 I", target = "ko", kind = LibraryFolderKind.LOCAL_DIRECTORY),
                    sampleFolder("English Podcast", target = "en", kind = LibraryFolderKind.VIRTUAL_DIRECTORY),
                    sampleFolder("中国語ニュース", target = "zh", kind = LibraryFolderKind.LOCAL_DIRECTORY, colorIndex = 6),
                    sampleFolder("スペイン語", target = "es", kind = LibraryFolderKind.VIRTUAL_DIRECTORY),
                ),
                folderLanguageColorOrdinals = mapOf(
                    LanguageTag("ko") to 0,
                    LanguageTag("en") to 1,
                    LanguageTag("zh-cn") to 2,
                    LanguageTag("es") to 3,
                ),
            ),
            onEvent = {},
        )
    }
}

/** ファイルタブ: タグの絞り込み（N5 を選択）と行のタグ、2 件を選んだ選択モード（上部バーに「タグを編集」）。 */
@PreviewTest
@ChunkFlowPreviews
@Composable
fun LibraryScreenFilesTabTagsScreenshot() {
    val a = taggedSampleItem("a", "ピンズラー 韓国語 01", course = "ピンズラー韓国語 I")
    val b = taggedSampleItem("b", "ピンズラー 韓国語 02", course = "ピンズラー韓国語 I")
    val c = taggedSampleItem("c", "Episode 1", course = "English Podcast", target = "en")
    val items = listOf(
        LibraryItemUiModel(item = a, tags = listOf("N5", "シャドーイング", "旅行", "復習")),
        LibraryItemUiModel(item = b, tags = listOf("N5")),
        LibraryItemUiModel(item = c, tags = listOf("旅行")),
    )
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = items,
                allTags = listOf("N5", "シャドーイング", "旅行", "復習"),
                selectedTab = LibraryViewTab.FILES,
                fileTagFilter = setOf("N5"),
                selectedItemIds = setOf(a.id, b.id),
            ),
            onEvent = {},
        )
    }
}

/** ファイルタブ: 選んだタグをすべて持つファイルがないときの表示（タグの選択を解除する手段を出す）。 */
@PreviewTest
@ChunkFlowPreviews
@Composable
fun LibraryScreenFilesTabTagFilterEmptyScreenshot() {
    val a = taggedSampleItem("a", "ピンズラー 韓国語 01", course = "ピンズラー韓国語 I")
    val b = taggedSampleItem("b", "Episode 1", course = "English Podcast", target = "en")
    ChunkFlowTheme {
        LibraryScreen(
            state = LibraryUiState(
                items = listOf(
                    LibraryItemUiModel(item = a, tags = listOf("N5")),
                    LibraryItemUiModel(item = b, tags = listOf("旅行")),
                ),
                allTags = listOf("N5", "旅行"),
                selectedTab = LibraryViewTab.FILES,
                fileTagFilter = setOf("N5", "旅行"),
            ),
            onEvent = {},
        )
    }
}

/** 選択モードの「タグを編集」ダイアログ（すべて / 一部 / なし のチェック）。 */
@PreviewTest
@ChunkFlowPreviews
@Composable
fun LibraryEditTagsForSelectionDialogScreenshot() {
    ChunkFlowTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceDim),
            contentAlignment = Alignment.Center,
        ) {
            EditTagsForSelectionCard(
                allTags = listOf("N5", "シャドーイング", "旅行", "復習"),
                tagCounts = mapOf("N5" to 3, "シャドーイング" to 1),
                targetCount = 3,
                onConfirm = {},
                onDismiss = {},
                modifier = Modifier
                    .padding(24.dp)
                    .sizeIn(minWidth = 280.dp, maxWidth = 560.dp),
            )
        }
    }
}

/** フォルダの設定のシート: 色見本（指定した色を選択中）と「デフォルトに戻す」。 */
@PreviewTest
@ChunkFlowPreviews
@Composable
fun LibraryEditFolderSheetScreenshot() {
    ChunkFlowTheme {
        Surface {
            EditDirectoryMetadataDialog(
                directoryEdit = PendingDirectoryEdit(
                    currentCourse = "ピンズラー韓国語 I",
                    initialLanguages = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
                    colorIndex = 6,
                ),
                enabledLanguages = listOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ko")),
                onConfirm = { _, _, _, _ -> },
                onDismiss = {},
                onDelete = {},
                languageColorOrdinals = mapOf(LanguageTag("ko") to 0, LanguageTag("en") to 1),
            )
        }
    }
}

/** ファイルの設定のシート: 付いているタグ（✕ で外す）・新しいタグの入力・既存のタグから付ける。 */
@PreviewTest
@ChunkFlowPreviews
@Composable
fun LibraryEditFileSheetScreenshot() {
    ChunkFlowTheme {
        Surface {
            EditItemMetadataDialog(
                item = taggedSampleItem("a", "ピンズラー 韓国語 01", course = "ピンズラー韓国語 I"),
                enabledLanguages = listOf(LanguageTag("ja"), LanguageTag("en"), LanguageTag("ko")),
                onConfirm = { _, _, _, _ -> },
                onDismiss = {},
                onDelete = {},
                initialTags = listOf("N5", "シャドーイング"),
                availableTags = listOf("N5", "シャドーイング", "旅行", "復習"),
            )
        }
    }
}
