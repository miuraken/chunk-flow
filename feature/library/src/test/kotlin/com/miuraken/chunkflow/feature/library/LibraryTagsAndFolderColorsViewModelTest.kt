package com.miuraken.chunkflow.feature.library

import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.LibraryFolderKind
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.testing.fake.FakeLibraryRepository
import com.miuraken.chunkflow.core.testing.fake.FakePlaybackController
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/** ライブラリのタグとフォルダの色分け（ADR 0013）の ViewModel の振る舞い。 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryTagsAndFolderColorsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun item(id: String, title: String = id, course: String? = null, target: String = "ko"): AudioItem = SampleMaterials.jaKo.item.copy(
        id = MediaId(id),
        title = title,
        course = course,
        languagePair = LanguagePair(target = LanguageTag(target), base = LanguageTag("ja")),
    )

    private fun folder(path: String, target: String, colorIndex: Int? = null): LibraryCourseFolder = LibraryCourseFolder(
        coursePath = path,
        kind = LibraryFolderKind.VIRTUAL_DIRECTORY,
        languagePair = LanguagePair(target = LanguageTag(target), base = LanguageTag("ja")),
        colorIndex = colorIndex,
    )

    @Test
    fun `ファイルタブのタグの絞り込み_選んだタグをすべて持つファイルだけを出す`() = runTest {
        val a = item("a", title = "01")
        val b = item("b", title = "02")
        val c = item("c", title = "03")
        val repo = FakeLibraryRepository(
            initialItems = listOf(a, b, c),
            initialItemTags = mapOf(
                a.id to listOf("N5", "旅行"),
                b.id to listOf("N5"),
                c.id to listOf("旅行"),
            ),
        )
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(listOf("N5", "旅行"), viewModel.uiState.value.allTags)
        assertEquals(listOf("N5", "旅行"), viewModel.uiState.value.items.first { it.item.id == a.id }.tags)

        viewModel.onEvent(LibraryEvent.ToggleFileTagFilter("N5"))
        assertEquals(listOf(a.id, b.id), viewModel.uiState.value.filteredAllFiles.map { it.item.id })

        viewModel.onEvent(LibraryEvent.ToggleFileTagFilter("旅行"))
        assertEquals(setOf("N5", "旅行"), viewModel.uiState.value.fileTagFilter)
        assertEquals(listOf(a.id), viewModel.uiState.value.filteredAllFiles.map { it.item.id })

        viewModel.onEvent(LibraryEvent.ToggleFileTagFilter("N5"))
        assertEquals(listOf(a.id, c.id), viewModel.uiState.value.filteredAllFiles.map { it.item.id })

        viewModel.onEvent(LibraryEvent.ClearFileTagFilter)
        assertTrue(viewModel.uiState.value.fileTagFilter.isEmpty())
        assertEquals(3, viewModel.uiState.value.filteredAllFiles.size)
    }

    @Test
    fun `タグの絞り込みで0件_タグだけが原因のときにわかる`() = runTest {
        val a = item("a", title = "Lesson 1")
        val b = item("b", title = "Lesson 2")
        val repo = FakeLibraryRepository(
            initialItems = listOf(a, b),
            initialItemTags = mapOf(a.id to listOf("A"), b.id to listOf("B")),
        )
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(LibraryEvent.ToggleFileTagFilter("A"))
        viewModel.onEvent(LibraryEvent.ToggleFileTagFilter("B"))
        assertTrue(viewModel.uiState.value.filteredAllFiles.isEmpty())
        assertTrue(viewModel.uiState.value.isFileListEmptyBecauseOfTagFilter)

        // 検索語でも 0 件になるときは、タグだけが原因ではない
        viewModel.onEvent(LibraryEvent.UpdateFileSearchQuery("該当なし"))
        assertFalse(viewModel.uiState.value.isFileListEmptyBecauseOfTagFilter)
    }

    @Test
    fun `どのファイルにも付いていないタグは絞り込みから外す`() = runTest {
        val a = item("a")
        val b = item("b")
        val repo = FakeLibraryRepository(
            initialItems = listOf(a, b),
            initialItemTags = mapOf(a.id to listOf("A")),
        )
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(LibraryEvent.ToggleFileTagFilter("A"))
        assertEquals(listOf(a.id), viewModel.uiState.value.filteredAllFiles.map { it.item.id })

        repo.setItemTags(a.id, emptyList())

        assertTrue(viewModel.uiState.value.allTags.isEmpty())
        assertTrue(viewModel.uiState.value.fileTagFilter.isEmpty())
        assertEquals(2, viewModel.uiState.value.filteredAllFiles.size)
    }

    @Test
    fun `selectedTargetTagCounts_選んだファイルとフォルダの中のファイルのタグごとの件数`() = runTest {
        val a = item("a", course = "講座A")
        val b = item("b", course = "講座A")
        val c = item("c", course = "講座B")
        val repo = FakeLibraryRepository(
            initialItems = listOf(a, b, c),
            initialItemTags = mapOf(
                a.id to listOf("N5", "旅行"),
                b.id to listOf("N5"),
                c.id to listOf("復習"),
            ),
        )
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(LibraryEvent.ToggleDirectorySelection("講座A"))

        assertEquals(mapOf("N5" to 2, "旅行" to 1), viewModel.uiState.value.selectedTargetTagCounts)
        assertEquals(2, viewModel.uiState.value.selectedTargetItemIds.size)
    }

    @Test
    fun `UpdateTagsForSelected_選んだファイルとフォルダの中のファイルにまとめて付け外しして選択を解除する`() = runTest {
        val a = item("a", course = "講座A")
        val b = item("b", course = "講座A")
        val c = item("c", course = "講座B")
        val repo = FakeLibraryRepository(
            initialItems = listOf(a, b, c),
            initialItemTags = mapOf(a.id to listOf("旅行"), c.id to listOf("旅行", "復習")),
        )
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(LibraryEvent.ToggleDirectorySelection("講座A"))
        viewModel.onEvent(LibraryEvent.ToggleItemSelection(c.id))
        viewModel.onEvent(LibraryEvent.UpdateTagsForSelected(addTags = setOf("N5"), removeTags = setOf("旅行")))

        assertEquals(
            Triple(setOf(a.id, b.id, c.id), setOf("N5"), setOf("旅行")),
            repo.updateItemTagsRequests.single(),
        )
        assertFalse(viewModel.uiState.value.isSelectionMode)
        val tagsById = viewModel.uiState.value.items.associate { it.item.id to it.tags }
        assertEquals(listOf("N5"), tagsById.getValue(a.id))
        assertEquals(listOf("N5"), tagsById.getValue(b.id))
        assertEquals(listOf("復習", "N5"), tagsById.getValue(c.id))
    }

    @Test
    fun `UpdateTagsForSelected_変更がなければ何もせず選択も残す`() = runTest {
        val a = item("a")
        val repo = FakeLibraryRepository(initialItems = listOf(a))
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(LibraryEvent.ToggleItemSelection(a.id))
        viewModel.onEvent(LibraryEvent.UpdateTagsForSelected(addTags = emptySet(), removeTags = emptySet()))

        assertTrue(repo.updateItemTagsRequests.isEmpty())
        assertTrue(viewModel.uiState.value.isSelectionMode)
    }

    @Test
    fun `ConfirmEditMetadata_タグが変わったときだけ保存する`() = runTest {
        val a = item("a", course = "講座A")
        val repo = FakeLibraryRepository(
            initialItems = listOf(a),
            initialItemTags = mapOf(a.id to listOf("N5")),
        )
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        fun confirm(tags: List<String>?) {
            viewModel.onEvent(LibraryEvent.RequestEditMetadata(a))
            viewModel.onEvent(
                LibraryEvent.ConfirmEditMetadata(
                    id = a.id,
                    course = "講座A",
                    languages = a.languagePair,
                    tags = tags,
                ),
            )
        }

        confirm(tags = listOf("N5"))
        confirm(tags = null)
        assertTrue(repo.setItemTagsRequests.isEmpty())

        confirm(tags = listOf("N5", "シャドーイング"))
        assertEquals(listOf(a.id to listOf("N5", "シャドーイング")), repo.setItemTagsRequests)
        assertEquals(listOf("N5", "シャドーイング"), viewModel.uiState.value.items.single().tags)
    }

    @Test
    fun `フォルダの色_指定がなければ対象言語が使われた順の色_指定があればその色`() = runTest {
        val repo = FakeLibraryRepository(
            initialItems = listOf(item("k1", course = "韓国語", target = "ko"), item("e1", course = "英語", target = "en")),
            initialCourseFolders = listOf(
                folder("韓国語", target = "ko"),
                folder("英語", target = "en"),
                folder("中国語", target = "zh", colorIndex = 5),
            ),
        )
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val state = viewModel.uiState.value
        val colorsByFolder = state.currentDirectories.associate { it.name to it.colorIndex }
        assertEquals(0, colorsByFolder["韓国語"])
        assertEquals(1, colorsByFolder["英語"])
        assertEquals(5, colorsByFolder["中国語"])
        assertEquals(0, state.folderColorIndexFor("韓国語"))
        assertNull(state.folderColorIndexFor(null))
    }

    @Test
    fun `フォルダの色_設定の行がないフォルダは中のファイルの対象言語の色`() = runTest {
        val repo = FakeLibraryRepository(
            initialItems = listOf(item("e1", course = "英語/Unit1", target = "en")),
            initialCourseFolders = listOf(folder("韓国語", target = "ko"), folder("英語", target = "en")),
        )
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        assertEquals(1, viewModel.uiState.value.folderColorIndexFor("英語/Unit1"))
    }

    @Test
    fun `フォルダの設定_開いたときの色をシートに渡し_選んだ色を保存する`() = runTest {
        val repo = FakeLibraryRepository(
            initialItems = listOf(item("k1", course = "韓国語", target = "ko")),
            initialCourseFolders = listOf(folder("韓国語", target = "ko", colorIndex = 2)),
        )
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        val languages = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja"))

        viewModel.onEvent(LibraryEvent.RequestEditDirectoryMetadata("韓国語"))
        assertEquals(2, viewModel.uiState.value.pendingEditDirectory?.colorIndex)

        // 色を変えなければ保存しない
        viewModel.onEvent(
            LibraryEvent.ConfirmEditDirectoryMetadata(currentCourse = "韓国語", newCourse = "韓国語", languages = languages),
        )
        assertTrue(repo.folderColorRequests.isEmpty())

        viewModel.onEvent(LibraryEvent.RequestEditDirectoryMetadata("韓国語"))
        viewModel.onEvent(
            LibraryEvent.ConfirmEditDirectoryMetadata(
                currentCourse = "韓国語",
                newCourse = "韓国語",
                languages = languages,
                colorChoice = LibraryFolderColorChoice.Palette(6),
            ),
        )
        assertEquals("韓国語" to 6, repo.folderColorRequests.last())
        assertEquals(6, viewModel.uiState.value.folderColorIndexFor("韓国語"))

        // 「デフォルトに戻す」で指定を外し、対象言語の色に戻る
        viewModel.onEvent(LibraryEvent.RequestEditDirectoryMetadata("韓国語"))
        viewModel.onEvent(
            LibraryEvent.ConfirmEditDirectoryMetadata(
                currentCourse = "韓国語",
                newCourse = "韓国語",
                languages = languages,
                colorChoice = LibraryFolderColorChoice.LanguageDefault,
            ),
        )
        assertEquals("韓国語" to null, repo.folderColorRequests.last())
        assertEquals(0, viewModel.uiState.value.folderColorIndexFor("韓国語"))
    }

    @Test
    fun `フォルダの設定_名前を変えたときは新しいパスに色を保存する`() = runTest {
        val repo = FakeLibraryRepository(
            initialItems = listOf(item("k1", course = "韓国語", target = "ko")),
            initialCourseFolders = listOf(folder("韓国語", target = "ko")),
        )
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(LibraryEvent.RequestEditDirectoryMetadata("韓国語"))
        viewModel.onEvent(
            LibraryEvent.ConfirmEditDirectoryMetadata(
                currentCourse = "韓国語",
                newCourse = "韓国語講座",
                languages = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
                colorChoice = LibraryFolderColorChoice.Palette(4),
            ),
        )

        assertEquals(listOf<Pair<String, Int?>>("韓国語講座" to 4), repo.folderColorRequests)
        assertEquals(4, viewModel.uiState.value.folderColorIndexFor("韓国語講座"))
    }

    @Test
    fun `LibraryFolderColorChoice_of_開いたときと同じなら変えない`() {
        assertEquals(LibraryFolderColorChoice.Unchanged, LibraryFolderColorChoice.of(initial = null, selected = null))
        assertEquals(LibraryFolderColorChoice.Unchanged, LibraryFolderColorChoice.of(initial = 3, selected = 3))
        assertEquals(LibraryFolderColorChoice.LanguageDefault, LibraryFolderColorChoice.of(initial = 3, selected = null))
        assertEquals(LibraryFolderColorChoice.Palette(1), LibraryFolderColorChoice.of(initial = null, selected = 1))
    }
}
