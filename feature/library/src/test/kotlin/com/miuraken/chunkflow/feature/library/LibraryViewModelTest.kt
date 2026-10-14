package com.miuraken.chunkflow.feature.library

import android.net.StubUri
import com.miuraken.chunkflow.core.data.library.ImportError
import com.miuraken.chunkflow.core.data.library.ImportProgress
import com.miuraken.chunkflow.core.data.library.SyncError
import com.miuraken.chunkflow.core.data.library.SyncProgress
import com.miuraken.chunkflow.core.data.library.TranscriptImportResult
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.playback.PlaybackStatus
import com.miuraken.chunkflow.core.testing.fake.FakeAnalysisRepository
import com.miuraken.chunkflow.core.testing.fake.FakeLibraryRepository
import com.miuraken.chunkflow.core.testing.fake.FakePlaybackController
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import com.miuraken.chunkflow.core.transcript.TranscriptError
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `uiState_教材とレジューム位置と速度を合成して公開する`() = runTest {
        val item1 = SampleMaterials.jaKo.item
        val item2 = SampleMaterials.jaZh.item
        val repo = FakeLibraryRepository(
            initialItems = listOf(item1, item2),
            initialItemStates = mapOf(
                item1.id to ItemState(mediaId = item1.id, positionMs = 12_000L, speed = 1.25f),
            ),
        )
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val state = viewModel.uiState.value
        assertEquals(2, state.items.size)
        assertEquals(12_000L, state.items[0].positionMs)
        assertEquals(1.25f, state.items[0].speed)
        assertEquals(0.4f, state.items[0].progressFraction)
        assertEquals(0L, state.items[1].positionMs)
        assertEquals(1.0f, state.items[1].speed)
    }

    @Test
    fun `ImportUris_進捗と完了とエラーを反映しバナー消去でIdleに戻る`() = runTest {
        val item = SampleMaterials.jaKo.item
        val repo = FakeLibraryRepository()
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val fakeUri = StubUri.create()
        repo.importResult = { flowOf(ImportProgress.Copying(0.5f)) }
        viewModel.onEvent(LibraryEvent.ImportUris(listOf(fakeUri)))
        assertEquals(LibraryImportUiState.Copying(0.5f), viewModel.uiState.value.importState)
        assertEquals(1, repo.importedUris.size)
        assertSame(fakeUri, repo.importedUris.single().single())

        repo.importResult = {
            flowOf(ImportProgress.Done(mediaIds = listOf(item.id), duplicates = 1, transcriptsAttached = 0))
        }
        viewModel.onEvent(LibraryEvent.ImportUris(listOf(fakeUri)))
        assertEquals(
            LibraryImportUiState.Done(addedCount = 1, duplicates = 1),
            viewModel.uiState.value.importState,
        )

        repo.importResult = { flowOf(ImportProgress.Failed(ImportError.UNSUPPORTED_FORMAT)) }
        viewModel.onEvent(LibraryEvent.ImportUris(listOf(fakeUri)))
        assertEquals(
            LibraryImportUiState.Error(ImportError.UNSUPPORTED_FORMAT),
            viewModel.uiState.value.importState,
        )

        viewModel.onEvent(LibraryEvent.DismissImportStatus)
        assertEquals(LibraryImportUiState.Idle, viewModel.uiState.value.importState)
    }

    @Test
    fun `ImportTranscriptUri_紐付けと未取り込み音声案内とエラーを反映する`() = runTest {
        val item = SampleMaterials.jaKo.item
        val repo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val fakeUri = StubUri.create()
        repo.importTranscriptResult = {
            TranscriptImportResult.Attached(mediaId = item.id, chunkCount = 6, durationMismatch = false)
        }
        viewModel.onEvent(LibraryEvent.ImportTranscriptUri(fakeUri))
        assertEquals(
            TranscriptImportUiState.Attached(mediaId = item.id, chunkCount = 6, durationMismatch = false),
            viewModel.uiState.value.transcriptImportState,
        )

        repo.importTranscriptResult = {
            TranscriptImportResult.NoMatchingAudio(audioFileName = "lesson01.mp3")
        }
        viewModel.onEvent(LibraryEvent.ImportTranscriptUri(fakeUri))
        assertEquals(
            TranscriptImportUiState.NoMatchingAudio(audioFileName = "lesson01.mp3"),
            viewModel.uiState.value.transcriptImportState,
        )

        repo.importTranscriptResult = {
            TranscriptImportResult.Invalid(error = TranscriptError.SCHEMA_VIOLATION)
        }
        viewModel.onEvent(LibraryEvent.ImportTranscriptUri(fakeUri))
        assertEquals(
            TranscriptImportUiState.Invalid(error = TranscriptError.SCHEMA_VIOLATION),
            viewModel.uiState.value.transcriptImportState,
        )

        viewModel.onEvent(LibraryEvent.DismissImportStatus)
        assertEquals(TranscriptImportUiState.Idle, viewModel.uiState.value.transcriptImportState)
    }

    @Test
    fun `ディレクトリ構成と解析要約_フォルダ単位でグループ化し展開と折りたたみを切り替えられる`() = runTest {
        val sampleKo = SampleMaterials.jaKo
        val sampleZh = SampleMaterials.jaZh
        val repo = FakeLibraryRepository(initialItems = listOf(sampleKo.item, sampleZh.item))
        val playback = FakePlaybackController()
        val analysis = FakeAnalysisRepository().apply {
            put(sampleKo)
            update(sampleZh.item.id) { entry ->
                entry.copy(status = mapOf(AnalysisStage.FEATURES to StageStatus.Running(0.4f)))
            }
        }
        val viewModel = LibraryViewModel(repo, playback, analysis)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val state = viewModel.uiState.value
        assertEquals(2, state.directories.size)
        val koItemModel = state.items.first { it.item.id == sampleKo.item.id }
        assertEquals(sampleKo.chunks.size, koItemModel.chunkCount)
        assertTrue(koItemModel.hasTranscript)
        assertFalse(koItemModel.isAnalyzing)

        val zhItemModel = state.items.first { it.item.id == sampleZh.item.id }
        assertTrue(zhItemModel.isAnalyzing)

        val firstDirName = state.directories.first().name
        assertTrue(state.directories.first().isExpanded)
        viewModel.onEvent(LibraryEvent.ToggleDirectory(firstDirName))
        assertFalse(viewModel.uiState.value.directories.first().isExpanded)
        viewModel.onEvent(LibraryEvent.ToggleDirectory(firstDirName))
        assertTrue(viewModel.uiState.value.directories.first().isExpanded)
    }

    @Test
    fun `削除フロー_確認ダイアログを経て教材を削除しキャンセルもできる`() = runTest {
        val item1 = SampleMaterials.jaKo.item
        val item2 = SampleMaterials.jaZh.item
        val repo = FakeLibraryRepository(initialItems = listOf(item1, item2))
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(LibraryEvent.RequestDelete(item1))
        assertEquals(item1, viewModel.uiState.value.pendingDeleteItem)

        viewModel.onEvent(LibraryEvent.CancelDelete)
        assertNull(viewModel.uiState.value.pendingDeleteItem)
        assertEquals(2, viewModel.uiState.value.items.size)

        viewModel.onEvent(LibraryEvent.RequestDelete(item1))
        viewModel.onEvent(LibraryEvent.ConfirmDelete)
        assertNull(viewModel.uiState.value.pendingDeleteItem)
        assertEquals(listOf(item2.id), viewModel.uiState.value.items.map { it.item.id })
    }

    @Test
    fun `ミニプレイヤー_開いている教材の情報を表示し再生と一時停止を切り替えられる`() = runTest {
        val item = SampleMaterials.jaKo.item
        val repo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController(
            initialStatus = PlaybackStatus(
                mediaId = item.id,
                isPlaying = false,
                durationMs = item.durationMs,
            ),
        )
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val miniPlayer = viewModel.uiState.value.miniPlayer
        assertEquals(
            MiniPlayerUiState(
                mediaId = item.id,
                title = item.title,
                isPlaying = false,
                durationMs = item.durationMs,
            ),
            miniPlayer,
        )

        viewModel.onEvent(LibraryEvent.ToggleMiniPlayerPlayPause)
        assertEquals(true, viewModel.uiState.value.miniPlayer?.isPlaying)

        viewModel.onEvent(LibraryEvent.ToggleMiniPlayerPlayPause)
        assertEquals(false, viewModel.uiState.value.miniPlayer?.isPlaying)
    }

    @Test
    fun `SyncFromDrive_一覧取得から進捗と完了と失敗まで反映しバナー消去でIdleに戻る`() = runTest {
        val repo = FakeLibraryRepository()
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        repo.syncResult = { flowOf(SyncProgress.Listing) }
        viewModel.onEvent(LibraryEvent.SyncFromDrive)
        assertEquals(DriveSyncUiState.Listing, viewModel.uiState.value.syncState)

        repo.syncResult = { flowOf(SyncProgress.Downloading(done = 1, total = 3)) }
        viewModel.onEvent(LibraryEvent.SyncFromDrive)
        assertEquals(
            DriveSyncUiState.Downloading(done = 1, total = 3),
            viewModel.uiState.value.syncState,
        )

        repo.syncResult = {
            flowOf(SyncProgress.Done(newAudio = 2, newTranscripts = 1, failed = 0, uploadedTranscripts = 1))
        }
        viewModel.onEvent(LibraryEvent.SyncFromDrive)
        assertEquals(
            DriveSyncUiState.Done(newAudio = 2, newTranscripts = 1, failed = 0, uploadedTranscripts = 1),
            viewModel.uiState.value.syncState,
        )

        repo.syncResult = { flowOf(SyncProgress.Failed(SyncError.NOT_AUTHORIZED)) }
        viewModel.onEvent(LibraryEvent.SyncFromDrive)
        assertEquals(
            DriveSyncUiState.Error(SyncError.NOT_AUTHORIZED),
            viewModel.uiState.value.syncState,
        )

        viewModel.onEvent(LibraryEvent.DismissSyncStatus)
        assertEquals(DriveSyncUiState.Idle, viewModel.uiState.value.syncState)
    }

    @Test
    fun `メタデータ編集_コース名と言語ペアを更新しキャンセルもできる`() = runTest {
        val item = SampleMaterials.jaKo.item
        val repo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(LibraryEvent.RequestEditMetadata(item))
        assertEquals(item, viewModel.uiState.value.pendingEditItem)

        viewModel.onEvent(LibraryEvent.CancelEditMetadata)
        assertNull(viewModel.uiState.value.pendingEditItem)

        viewModel.onEvent(LibraryEvent.RequestEditMetadata(item))
        val expectedLanguages = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja"))
        viewModel.onEvent(
            LibraryEvent.ConfirmEditMetadata(
                id = item.id,
                course = "中級韓国語講座",
                languages = expectedLanguages,
            ),
        )
        assertNull(viewModel.uiState.value.pendingEditItem)
        val updated = viewModel.uiState.value.items.first().item
        assertEquals("中級韓国語講座", updated.course)
        assertEquals(expectedLanguages, updated.languagePair)
    }
}
