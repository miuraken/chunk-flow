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
    fun `ImportUris_設定ダイアログを経て進捗と完了とエラーを反映しバナー消去でIdleに戻る`() = runTest {
        val item = SampleMaterials.jaKo.item
        val repo = FakeLibraryRepository()
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val fakeUri = StubUri.create()
        val selectedPair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja"))
        repo.importResult = { flowOf(ImportProgress.Copying(0.5f)) }
        viewModel.onEvent(LibraryEvent.ImportUris(listOf(fakeUri)))
        assertSame(fakeUri, viewModel.uiState.value.pendingImportConfig?.uris?.single())
        assertFalse(viewModel.uiState.value.pendingImportConfig?.isDriveSyncFolder ?: true)

        viewModel.onEvent(
            LibraryEvent.ConfirmImportConfig(
                course = "初級韓国語",
                languages = selectedPair,
            ),
        )
        assertNull(viewModel.uiState.value.pendingImportConfig)
        assertEquals(LibraryImportUiState.Copying(0.5f), viewModel.uiState.value.importState)
        assertEquals(1, repo.importedUris.size)
        assertSame(fakeUri, repo.importedUris.single().single())
        assertSame(fakeUri, repo.importedConfigs.single().first.single())
        assertEquals("初級韓国語", repo.importedConfigs.single().second)
        assertEquals(selectedPair, repo.importedConfigs.single().third)

        repo.importResult = {
            flowOf(ImportProgress.Done(mediaIds = listOf(item.id), duplicates = 1, transcriptsAttached = 0))
        }
        viewModel.onEvent(LibraryEvent.ImportUris(listOf(fakeUri)))
        viewModel.onEvent(LibraryEvent.ConfirmImportConfig(course = "初級韓国語", languages = selectedPair))
        assertEquals(
            LibraryImportUiState.Done(addedCount = 1, duplicates = 1),
            viewModel.uiState.value.importState,
        )

        repo.importResult = { flowOf(ImportProgress.Failed(ImportError.UNSUPPORTED_FORMAT)) }
        viewModel.onEvent(LibraryEvent.ImportUris(listOf(fakeUri)))
        viewModel.onEvent(LibraryEvent.ConfirmImportConfig(course = "初級韓国語", languages = selectedPair))
        assertEquals(
            LibraryImportUiState.Error(ImportError.UNSUPPORTED_FORMAT),
            viewModel.uiState.value.importState,
        )

        viewModel.onEvent(LibraryEvent.DismissImportStatus)
        assertEquals(LibraryImportUiState.Idle, viewModel.uiState.value.importState)
    }

    @Test
    fun `SelectDriveSyncFolder_設定ダイアログで同期フォルダとコースと言語ペアを登録して同期を実行する`() = runTest {
        val repo = FakeLibraryRepository()
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val treeUri = StubUri.create()
        val syncPair = LanguagePair(target = LanguageTag("zh"), base = LanguageTag("ja"))
        repo.syncResult = {
            flowOf(SyncProgress.Done(newAudio = 3, newTranscripts = 2, failed = 0, uploadedTranscripts = 0))
        }

        viewModel.onEvent(
            LibraryEvent.SelectDriveSyncFolder(
                directoryName = "ChunkFlowSync",
                treeUri = treeUri,
            ),
        )
        assertEquals("ChunkFlowSync", viewModel.uiState.value.pendingImportConfig?.initialCourse)
        assertEquals(true, viewModel.uiState.value.pendingImportConfig?.isDriveSyncFolder)

        // キャンセル時は同期せず閉じる
        viewModel.onEvent(LibraryEvent.CancelImportConfig)
        assertNull(viewModel.uiState.value.pendingImportConfig)
        assertNull(repo.linkedSyncFolderUri)

        // 再度選択して確定すると syncFromLinkedFolder が呼ばれる
        viewModel.onEvent(
            LibraryEvent.SelectDriveSyncFolder(
                directoryName = "ChunkFlowSync",
                treeUri = treeUri,
            ),
        )
        viewModel.onEvent(
            LibraryEvent.ConfirmImportConfig(
                course = "中国語リスニング",
                languages = syncPair,
            ),
        )
        assertNull(viewModel.uiState.value.pendingImportConfig)
        assertSame(treeUri, repo.linkedSyncFolderUri)
        assertEquals("中国語リスニング", repo.linkedSyncCourse)
        assertEquals(syncPair, repo.linkedSyncLanguages)
        assertEquals(
            DriveSyncUiState.Done(newAudio = 3, newTranscripts = 2, failed = 0, uploadedTranscripts = 0),
            viewModel.uiState.value.syncState,
        )
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

    @Test
    fun `バックアップ復元後の未取り込み音声_missingAudioIdsをisAudioMissingに反映する`() = runTest {
        val item1 = SampleMaterials.jaKo.item
        val item2 = SampleMaterials.jaZh.item
        val repo = FakeLibraryRepository(initialItems = listOf(item1, item2))
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        assertFalse(viewModel.uiState.value.items[0].isAudioMissing)
        assertFalse(viewModel.uiState.value.items[1].isAudioMissing)

        repo.missingAudioIds.value = setOf(item2.id)
        assertFalse(viewModel.uiState.value.items[0].isAudioMissing)
        assertTrue(viewModel.uiState.value.items[1].isAudioMissing)
    }

    @Test
    fun `ディレクトリ内ソートとListened初期スクロール位置_ファイル名昇順で並び最後のListenedの1から2件手前を指す`() = runTest {
        val baseItem = SampleMaterials.jaKo.item
        val item10 = baseItem.copy(id = com.miuraken.chunkflow.core.model.MediaId("m10"), title = "Lesson 10", course = "初級")
        val item2 = baseItem.copy(id = com.miuraken.chunkflow.core.model.MediaId("m2"), title = "Lesson 2", course = "初級")
        val item1 = baseItem.copy(id = com.miuraken.chunkflow.core.model.MediaId("m1"), title = "Lesson 1", course = "初級")
        val item3 = baseItem.copy(id = com.miuraken.chunkflow.core.model.MediaId("m3"), title = "Lesson 3", course = "初級")
        val item4 = baseItem.copy(id = com.miuraken.chunkflow.core.model.MediaId("m4"), title = "Lesson 4", course = "初級")

        val repo = FakeLibraryRepository(
            initialItems = listOf(item10, item2, item1, item3, item4),
            initialItemStates = mapOf(
                item1.id to ItemState(
                    mediaId = item1.id,
                    positionMs = 29_000L,
                    isListened = true,
                    updatedAtEpochMs = 1_000L,
                ),
                item2.id to ItemState(
                    mediaId = item2.id,
                    positionMs = 29_500L,
                    isListened = true,
                    updatedAtEpochMs = 2_000L,
                ),
                item3.id to ItemState(
                    mediaId = item3.id,
                    positionMs = 29_800L,
                    isListened = true,
                    updatedAtEpochMs = 3_000L,
                ),
            ),
        )
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val dir = viewModel.uiState.value.directories.single()
        // ファイル名（タイトル）自然昇順: Lesson 1, Lesson 2, Lesson 3, Lesson 4, Lesson 10
        assertEquals(
            listOf("Lesson 1", "Lesson 2", "Lesson 3", "Lesson 4", "Lesson 10"),
            dir.items.map { it.item.title },
        )
        assertEquals(3, dir.listenedCount)
        assertTrue(dir.items[0].isListened)
        assertEquals(1_000L, dir.items[0].lastAccessedAtEpochMs)
        assertEquals(2, dir.lastAccessedListenedIndex) // Lesson 3 (index 2)
        // 最後の Listened (index 2) の 1 件手前 (index 1 = Lesson 2) から表示開始し、Lesson 2, Lesson 3 の 2 件が上部に残る
        assertEquals(1, dir.initialVisibleItemIndexInDirectory)
        // LazyColumn 内のインデックスはヘッダ (0) + 1 + offset (1) = 2
        assertEquals(2, viewModel.uiState.value.targetScrollLazyIndexForDirectory("初級"))
    }

    @Test
    fun `ディレクトリ一括メタデータ編集_コース名と言語ペアをディレクトリ単位で一括更新できる`() = runTest {
        val item1 = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("d1"),
            course = "旧コース",
        )
        val item2 = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("d2"),
            course = "旧コース",
        )
        val repo = FakeLibraryRepository(initialItems = listOf(item1, item2))
        val playback = FakePlaybackController()
        val settingsRepo = com.miuraken.chunkflow.core.testing.fake.FakeUserSettingsRepository()
        val viewModel = LibraryViewModel(
            libraryRepository = repo,
            playbackController = playback,
            analysisRepository = FakeAnalysisRepository(),
            userSettingsRepository = settingsRepo,
        )
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(LibraryEvent.RequestEditDirectoryMetadata("旧コース"))
        assertEquals("旧コース", viewModel.uiState.value.pendingEditDirectory?.currentCourse)

        viewModel.onEvent(LibraryEvent.CancelEditDirectoryMetadata)
        assertNull(viewModel.uiState.value.pendingEditDirectory)

        val newPair = LanguagePair(target = LanguageTag("zh"), base = LanguageTag("en"))
        viewModel.onEvent(LibraryEvent.RequestEditDirectoryMetadata("旧コース"))
        viewModel.onEvent(
            LibraryEvent.ConfirmEditDirectoryMetadata(
                currentCourse = "旧コース",
                newCourse = "新中国語コース",
                languages = newPair,
            ),
        )
        assertNull(viewModel.uiState.value.pendingEditDirectory)
        assertTrue(viewModel.uiState.value.items.all { it.item.course == "新中国語コース" })
        assertTrue(viewModel.uiState.value.items.all { it.item.languagePair == newPair })
    }

    @Test
    fun `言語選択プルダウン候補_アルファベット順とzh正規化とベース言語なし選択肢が機能する`() {
        assertEquals("zh-cn", normalizeDialogLanguageCode("zh"))
        assertEquals("zh-cn", normalizeDialogLanguageCode("zh-CN"))
        assertEquals("zh-tw", normalizeDialogLanguageCode("zh-TW"))
        assertEquals("ko", normalizeDialogLanguageCode("KO"))

        val targetOptions = buildDialogLanguageOptions(currentCode = "ko", noneLabel = null)
        assertEquals(targetOptions.map { it.code }.sorted(), targetOptions.map { it.code })
        assertTrue(targetOptions.any { it.code == "ko" && it.label == "ko - 韓国語" })
        assertTrue(targetOptions.any { it.code == "zh-cn" && it.label == "zh-cn - 簡体中国語" })
        assertTrue(targetOptions.any { it.code == "zh-tw" && it.label == "zh-tw - 繁体中国語" })

        val baseOptions = buildDialogLanguageOptions(currentCode = "", noneLabel = "なし（単一言語）")
        assertEquals("", baseOptions.first().code)
        assertEquals("なし（単一言語）", baseOptions.first().label)
        assertEquals(
            baseOptions.drop(1).map { it.code }.sorted(),
            baseOptions.drop(1).map { it.code },
        )
    }

    @Test
    fun `階層型ディレクトリ移動_現在ディレクトリ直下のフォルダとファイルのみを表示しNavigateUpで親階層へ戻る`() = runTest {
        val rootFile = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("root-file"),
            title = "Root Audio",
            course = null,
        )
        val dirAFile = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("dir-a-file"),
            title = "Lesson 1",
            course = "韓国語/初級",
        )
        val dirASubFile = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("dir-a-sub-file"),
            title = "Unit 1",
            course = "韓国語/初級/Part1",
        )
        val repo = FakeLibraryRepository(initialItems = listOf(rootFile, dirAFile, dirASubFile))
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // ルート階層: 直下ディレクトリ「韓国語」と直下ファイル「Root Audio」のみ
        assertNull(viewModel.uiState.value.currentDirectoryPath)
        assertEquals(listOf("韓国語"), viewModel.uiState.value.currentDirectories.map { it.displayName })
        assertEquals(listOf("Root Audio"), viewModel.uiState.value.currentFiles.map { it.item.title })

        // 「韓国語」を開く -> 直下ディレクトリ「初級」のみ、直下ファイルなし
        viewModel.onEvent(LibraryEvent.OpenDirectory("韓国語"))
        assertEquals("韓国語", viewModel.uiState.value.currentDirectoryPath)
        assertEquals(listOf("韓国語/初級"), viewModel.uiState.value.currentDirectories.map { it.name })
        assertEquals(listOf("初級"), viewModel.uiState.value.currentDirectories.map { it.displayName })
        assertTrue(viewModel.uiState.value.currentFiles.isEmpty())

        // 「韓国語/初級」を開く -> 直下ディレクトリ「Part1」と直下ファイル「Lesson 1」
        viewModel.onEvent(LibraryEvent.OpenDirectory("韓国語/初級"))
        assertEquals("韓国語/初級", viewModel.uiState.value.currentDirectoryPath)
        assertEquals(listOf("Part1"), viewModel.uiState.value.currentDirectories.map { it.displayName })
        assertEquals(listOf("Lesson 1"), viewModel.uiState.value.currentFiles.map { it.item.title })

        // NavigateUp で「韓国語」へ戻る -> さらに NavigateUp でルートへ戻る
        viewModel.onEvent(LibraryEvent.NavigateUp)
        assertEquals("韓国語", viewModel.uiState.value.currentDirectoryPath)
        viewModel.onEvent(LibraryEvent.NavigateUp)
        assertNull(viewModel.uiState.value.currentDirectoryPath)
    }

    @Test
    fun `ディレクトリ削除と長押し複数選択一括削除_確認ダイアログを経て対象教材がすべて削除される`() = runTest {
        val item1 = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("m1"),
            title = "Track 1",
            course = "CourseA/Sub1",
        )
        val item2 = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("m2"),
            title = "Track 2",
            course = "CourseA/Sub2",
        )
        val item3 = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("m3"),
            title = "Track 3",
            course = "CourseB",
        )
        val item4 = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("m4"),
            title = "Track 4",
            course = null,
        )
        val repo = FakeLibraryRepository(initialItems = listOf(item1, item2, item3, item4))
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // ディレクトリ単体削除確認 -> キャンセル -> 確定削除
        viewModel.onEvent(LibraryEvent.RequestDeleteDirectory("CourseA/Sub1"))
        assertTrue(viewModel.uiState.value.hasPendingDeleteConfirmation)
        assertEquals(listOf(item1), viewModel.uiState.value.pendingDeleteTargetItems)
        viewModel.onEvent(LibraryEvent.CancelDelete)
        assertFalse(viewModel.uiState.value.hasPendingDeleteConfirmation)

        viewModel.onEvent(LibraryEvent.RequestDeleteDirectory("CourseA/Sub1"))
        viewModel.onEvent(LibraryEvent.ConfirmDelete)
        assertEquals(listOf(item2.id, item3.id, item4.id), viewModel.uiState.value.items.map { it.item.id })

        // 長押し複数選択（ディレクトリ CourseA + 単体ファイル m4）-> 一括削除
        viewModel.onEvent(LibraryEvent.ToggleDirectorySelection("CourseA"))
        viewModel.onEvent(LibraryEvent.ToggleItemSelection(item4.id))
        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertEquals(2, viewModel.uiState.value.selectedCount)

        viewModel.onEvent(LibraryEvent.RequestDeleteSelected)
        assertTrue(viewModel.uiState.value.isPendingBatchDelete)
        assertEquals(setOf(item2.id, item4.id), viewModel.uiState.value.pendingDeleteTargetItems.map { it.id }.toSet())

        viewModel.onEvent(LibraryEvent.ConfirmDelete)
        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertEquals(listOf(item3.id), viewModel.uiState.value.items.map { it.item.id })
    }

    @Test
    fun `文字起こし状態_チャンク数に対する文字起こし有無に応じてNONE_PARTIAL_COMPLETEDになる`() = runTest {
        val itemNone = SampleMaterials.jaKo.item.copy(id = com.miuraken.chunkflow.core.model.MediaId("none"))
        val itemPartial = SampleMaterials.jaKo.item.copy(id = com.miuraken.chunkflow.core.model.MediaId("partial"))
        val itemCompleted = SampleMaterials.jaKo.item.copy(id = com.miuraken.chunkflow.core.model.MediaId("completed"))
        val repo = FakeLibraryRepository(initialItems = listOf(itemNone, itemPartial, itemCompleted))
        val analysisRepo = FakeAnalysisRepository()

        val c1 = com.miuraken.chunkflow.core.model.Chunk(
            id = com.miuraken.chunkflow.core.model.ChunkId(1L),
            startMs = 0L,
            endMs = 1_000L,
            origin = com.miuraken.chunkflow.core.model.ChunkOrigin.AUTO,
        )
        val c2 = com.miuraken.chunkflow.core.model.Chunk(
            id = com.miuraken.chunkflow.core.model.ChunkId(2L),
            startMs = 1_000L,
            endMs = 2_000L,
            origin = com.miuraken.chunkflow.core.model.ChunkOrigin.AUTO,
        )
        val t1 = com.miuraken.chunkflow.core.model.Transcript(
            chunkId = c1.id,
            text = "テスト1",
            language = LanguageTag("ja"),
            languageConfidence = 1.0f,
            engine = com.miuraken.chunkflow.core.model.EngineId("manual"),
        )
        val t2 = com.miuraken.chunkflow.core.model.Transcript(
            chunkId = c2.id,
            text = "テスト2",
            language = LanguageTag("ja"),
            languageConfidence = 1.0f,
            engine = com.miuraken.chunkflow.core.model.EngineId("manual"),
        )

        analysisRepo.update(itemNone.id) {
            it.copy(chunks = listOf(c1, c2), transcripts = emptyMap())
        }
        analysisRepo.update(itemPartial.id) {
            it.copy(chunks = listOf(c1, c2), transcripts = mapOf(c1.id to t1))
        }
        analysisRepo.update(itemCompleted.id) {
            it.copy(chunks = listOf(c1, c2), transcripts = mapOf(c1.id to t1, c2.id to t2))
        }

        val viewModel = LibraryViewModel(
            libraryRepository = repo,
            playbackController = FakePlaybackController(),
            analysisRepository = analysisRepo,
        )
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val byId = viewModel.uiState.value.items.associateBy { it.item.id }
        assertEquals(LibraryTranscriptStatus.NONE, byId.getValue(itemNone.id).transcriptStatus)
        assertEquals(LibraryTranscriptStatus.PARTIAL, byId.getValue(itemPartial.id).transcriptStatus)
        assertEquals(LibraryTranscriptStatus.COMPLETED, byId.getValue(itemCompleted.id).transcriptStatus)
    }

    @Test
    fun `ローカルディレクトリ登録と再スキャン_treeUri付きImportDirectoryでlinkLocalDirectoryが呼ばれRescanLocalDirectoriesで再スキャンされる`() = runTest {
        val item = SampleMaterials.jaKo.item
        val repo = FakeLibraryRepository()
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val treeUri = StubUri.create()
        val selectedPair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja"))
        repo.importResult = {
            flowOf(ImportProgress.Done(mediaIds = listOf(item.id), duplicates = 0, transcriptsAttached = 1))
        }

        // 1. treeUri 付きの ImportDirectory を送ると PendingImportConfig.treeUri が保持され、確定時に linkLocalDirectory が呼ばれる
        viewModel.onEvent(
            LibraryEvent.ImportDirectory(
                directoryName = "NHKハングル",
                uris = listOf(treeUri),
                treeUri = treeUri,
            ),
        )
        assertSame(treeUri, viewModel.uiState.value.pendingImportConfig?.treeUri)
        assertEquals("NHKハングル", viewModel.uiState.value.pendingImportConfig?.initialCourse)

        viewModel.onEvent(
            LibraryEvent.ConfirmImportConfig(
                course = "NHKハングル",
                languages = selectedPair,
            ),
        )
        assertEquals(1, repo.linkedLocalDirectories.size)
        assertSame(treeUri, repo.linkedLocalDirectories.single().first)
        assertEquals("NHKハングル", repo.linkedLocalDirectories.single().second)
        assertEquals(selectedPair, repo.linkedLocalDirectories.single().third)
        assertEquals(
            LibraryImportUiState.Done(addedCount = 1, duplicates = 0, transcriptsAttached = 1),
            viewModel.uiState.value.importState,
        )

        // 2. RescanLocalDirectories を送ると rescanLocalDirectories が呼ばれ importState が更新される
        repo.rescanResultCount = 2
        viewModel.onEvent(LibraryEvent.RescanLocalDirectories)
        assertEquals(listOf<String?>(null), repo.rescanRequests)
        assertEquals(
            LibraryImportUiState.Done(addedCount = 2, duplicates = 0, transcriptsAttached = 0),
            viewModel.uiState.value.importState,
        )
    }

    @Test
    fun `仮想フォルダ作成と教材移動_空の仮想フォルダもcurrentDirectoriesに表示されMoveItemsToCourseで移動できる`() = runTest {
        val item1 = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("v1"),
            course = "imported",
        )
        val item2 = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("v2"),
            course = "imported",
        )
        val repo = FakeLibraryRepository(initialItems = listOf(item1, item2))
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // 1. ファイル未選択の状態で ImportUris を呼ぶと既定コース名が "imported" になる
        val fakeUri = StubUri.create()
        viewModel.onEvent(LibraryEvent.ImportUris(listOf(fakeUri)))
        assertEquals("imported", viewModel.uiState.value.pendingImportConfig?.initialCourse)
        viewModel.onEvent(LibraryEvent.CancelImportConfig)

        // 2. CreateVirtualFolder で空の仮想フォルダを作成すると、教材が 0 件でも currentDirectories と directories に表示される
        val folderLang = LanguagePair(target = LanguageTag("zh-cn"), base = LanguageTag("ja"))
        viewModel.onEvent(
            LibraryEvent.CreateVirtualFolder(
                folderName = "中国語会話",
                languages = folderLang,
            ),
        )
        val dirNames = viewModel.uiState.value.currentDirectories.mapNotNull { it.name }
        assertTrue("中国語会話" in dirNames)
        assertEquals(0, viewModel.uiState.value.currentDirectories.first { it.name == "中国語会話" }.items.size)

        // 3. MoveItemsToCourse で教材を新しいフォルダへ移動するとコース名と言語ペアが更新される
        viewModel.onEvent(
            LibraryEvent.MoveItemsToCourse(
                ids = setOf(item1.id),
                targetCourse = "中国語会話",
            ),
        )
        val movedItem = viewModel.uiState.value.items.first { it.item.id == item1.id }.item
        assertEquals("中国語会話", movedItem.course)
        assertEquals(folderLang, movedItem.languagePair)
        assertEquals(1, viewModel.uiState.value.currentDirectories.first { it.name == "中国語会話" }.items.size)
    }

    @Test
    fun `Drive音声のオンデマンドDLとローカル音声キャッシュ削除_DownloadDriveAudioとDeleteLocalAudioCopyでstorageStateが切り替わる`() = runTest {
        val remoteItem = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("drive-1"),
            storageState = com.miuraken.chunkflow.core.model.AudioStorageState.DRIVE_REMOTE_ONLY,
        )
        val repo = FakeLibraryRepository(initialItems = listOf(remoteItem))
        val playback = FakePlaybackController()
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        assertTrue(viewModel.uiState.value.items.single().item.isRemoteOnly)

        // 1. DownloadDriveAudio で DRIVE_DOWNLOADED になる
        viewModel.onEvent(LibraryEvent.DownloadDriveAudio(remoteItem.id))
        assertEquals(
            com.miuraken.chunkflow.core.model.AudioStorageState.DRIVE_DOWNLOADED,
            viewModel.uiState.value.items.single().item.storageState,
        )

        // 2. DeleteLocalAudioCopy で再び DRIVE_REMOTE_ONLY に戻る
        viewModel.onEvent(LibraryEvent.DeleteLocalAudioCopy(remoteItem.id))
        assertEquals(
            com.miuraken.chunkflow.core.model.AudioStorageState.DRIVE_REMOTE_ONLY,
            viewModel.uiState.value.items.single().item.storageState,
        )
    }

    @Test
    fun `buildDialogLanguageOptions_enabledLanguagesが指定されたら有効な言語に絞り込み現在の言語は維持する`() {
        val enabled = listOf(LanguageTag("en"), LanguageTag("ja"), LanguageTag("zh"))
        val filtered = buildDialogLanguageOptions(currentCode = "en", enabledLanguages = enabled)
        assertEquals(listOf("en", "ja", "zh-cn", "zh-tw"), filtered.map { it.code })

        val withUnlistedCurrent = buildDialogLanguageOptions(
            currentCode = "ko",
            noneLabel = "なし",
            enabledLanguages = listOf(LanguageTag("en"), LanguageTag("ja")),
        )
        assertEquals(listOf("", "en", "ja", "ko"), withUnlistedCurrent.map { it.code })
        assertEquals("ko - 韓国語", withUnlistedCurrent.first { it.code == "ko" }.label)
    }

    @Test
    fun `初回起動時の言語オンボーディングと使用言語トグル_CompleteLanguageOnboardingでダイアログが閉じToggleEnabledLanguageで更新される`() = runTest {
        val settingsRepo = com.miuraken.chunkflow.core.testing.fake.FakeUserSettingsRepository(
            com.miuraken.chunkflow.core.model.UserSettings(
                enabledLanguages = setOf(LanguageTag("en"), LanguageTag("ja")),
                hasCompletedLanguageOnboarding = false,
            ),
        )
        val viewModel = LibraryViewModel(
            libraryRepository = FakeLibraryRepository(),
            playbackController = FakePlaybackController(),
            analysisRepository = FakeAnalysisRepository(),
            userSettingsRepository = settingsRepo,
        )
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // 1. hasCompletedLanguageOnboarding = false の間は showLanguageOnboardingDialog = true
        assertTrue(viewModel.uiState.value.showLanguageOnboardingDialog)
        assertEquals(setOf(LanguageTag("en"), LanguageTag("ja")), viewModel.uiState.value.enabledLanguages.toSet())

        // 2. ToggleEnabledLanguage で ko を追加・削除できる（最後の 1 件は OFF にできない）
        viewModel.onEvent(LibraryEvent.ToggleEnabledLanguage(LanguageTag("ko")))
        assertEquals(
            setOf(LanguageTag("en"), LanguageTag("ja"), LanguageTag("ko")),
            viewModel.uiState.value.enabledLanguages.toSet(),
        )
        assertTrue(viewModel.uiState.value.showLanguageOnboardingDialog)
        viewModel.onEvent(LibraryEvent.ToggleEnabledLanguage(LanguageTag("ko")))
        assertEquals(setOf(LanguageTag("en"), LanguageTag("ja")), viewModel.uiState.value.enabledLanguages.toSet())
        viewModel.onEvent(LibraryEvent.ToggleEnabledLanguage(LanguageTag("ja")))
        assertEquals(listOf(LanguageTag("en")), viewModel.uiState.value.enabledLanguages)
        viewModel.onEvent(LibraryEvent.ToggleEnabledLanguage(LanguageTag("en")))
        assertEquals(listOf(LanguageTag("en")), viewModel.uiState.value.enabledLanguages)
        assertTrue(viewModel.uiState.value.showLanguageOnboardingDialog)

        // 3. CompleteLanguageOnboarding で showLanguageOnboardingDialog = false になる
        viewModel.onEvent(LibraryEvent.CompleteLanguageOnboarding)
        assertFalse(viewModel.uiState.value.showLanguageOnboardingDialog)
    }

    @Test
    fun `アプリ再起動時のミニプレイヤー復元_未再生状態でも直近アクセスした教材を復元し再生ボタンで途中から再開できる`() = runTest {
        val item1 = SampleMaterials.jaKo.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("recent-1"),
            title = "Older Lesson",
            durationMs = 30_000L,
        )
        val item2 = SampleMaterials.jaZh.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("recent-2"),
            title = "Latest Lesson",
            durationMs = 45_000L,
        )
        val repo = FakeLibraryRepository(
            initialItems = listOf(item1, item2),
            initialItemStates = mapOf(
                item1.id to ItemState(
                    mediaId = item1.id,
                    positionMs = 5_000L,
                    speed = 1.0f,
                    updatedAtEpochMs = 1_000L,
                ),
                item2.id to ItemState(
                    mediaId = item2.id,
                    positionMs = 18_500L,
                    speed = 1.5f,
                    updatedAtEpochMs = 2_000L,
                ),
            ),
        )
        // playbackStatus.mediaId == null（別日にアプリを起動した直後の状態）
        val playback = FakePlaybackController(initialStatus = PlaybackStatus())
        val viewModel = LibraryViewModel(repo, playback)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val restoredMini = viewModel.uiState.value.miniPlayer
        assertEquals(
            MiniPlayerUiState(
                mediaId = item2.id,
                title = "Latest Lesson",
                isPlaying = false,
                durationMs = 45_000L,
                positionMs = 18_500L,
            ),
            restoredMini,
        )

        // ミニプレイヤーの再生ボタンを押すと open(item2.id) が呼ばれ、保存済み位置と速度を復元して再生開始になる
        viewModel.onEvent(LibraryEvent.ToggleMiniPlayerPlayPause)
        assertEquals(listOf(item2.id), playback.openedMediaIds)
        assertEquals(18_500L, playback.position.positionMs)
        assertEquals(1.5f, playback.status.value.speed)
        assertEquals(true, viewModel.uiState.value.miniPlayer?.isPlaying)
        assertEquals(item2.id, viewModel.uiState.value.miniPlayer?.mediaId)
    }

    @Test
    fun `recentItems_アクセス日時がある教材を新しい順に最大5件返す`() = runTest {
        val base = SampleMaterials.jaKo.item
        val items = (1..7).map { i ->
            base.copy(
                id = com.miuraken.chunkflow.core.model.MediaId("item-$i"),
                title = "Lesson $i",
            )
        }
        // item-1 は未アクセス (updatedAtEpochMs = 0L)、item-2..item-7 はアクセス日時あり
        val states = mapOf(
            items[0].id to ItemState(mediaId = items[0].id, updatedAtEpochMs = 0L),
            items[1].id to ItemState(mediaId = items[1].id, updatedAtEpochMs = 200L),
            items[2].id to ItemState(mediaId = items[2].id, updatedAtEpochMs = 600L),
            items[3].id to ItemState(mediaId = items[3].id, updatedAtEpochMs = 100L),
            items[4].id to ItemState(mediaId = items[4].id, updatedAtEpochMs = 500L),
            items[5].id to ItemState(mediaId = items[5].id, updatedAtEpochMs = 300L),
            items[6].id to ItemState(mediaId = items[6].id, updatedAtEpochMs = 400L),
        )
        val repo = FakeLibraryRepository(
            initialItems = items,
            initialItemStates = states,
        )
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val recent = viewModel.uiState.value.recentItems
        assertEquals(5, recent.size)
        assertEquals(
            listOf("Lesson 3", "Lesson 5", "Lesson 7", "Lesson 6", "Lesson 2"),
            recent.map { it.item.title },
        )
    }

    @Test
    fun `バックグラウンド文字起こし中の教材_isTranscribingがtrueになり完了後はfalseになる`() = runTest {
        val sample = SampleMaterials.jaKo
        val chunks = sample.chunks.map { it.copy(origin = com.miuraken.chunkflow.core.model.ChunkOrigin.AUTO) }
        val firstChunk = chunks.first()
        val partialTranscripts = mapOf(firstChunk.id to sample.transcripts.getValue(firstChunk.id))
        val repo = FakeLibraryRepository(initialItems = listOf(sample.item))
        val analysis = FakeAnalysisRepository().apply {
            update(sample.item.id) {
                FakeAnalysisRepository.Entry(
                    chunks = chunks,
                    transcripts = partialTranscripts,
                    lookaheadStatus = com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus(
                        enabled = true,
                        engineAvailable = true,
                        backgroundEnabled = true,
                        inFlightChunkId = chunks[1].id,
                        queuedChunkIds = chunks.drop(2).map { it.id }.toSet(),
                    ),
                )
            }
        }
        val viewModel = LibraryViewModel(repo, FakePlaybackController(), analysis)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        val partialItem = viewModel.uiState.value.items.single()
        assertEquals(LibraryTranscriptStatus.PARTIAL, partialItem.transcriptStatus)
        assertTrue(partialItem.isTranscribing)
        assertTrue(partialItem.transcriptProgressPercent in 1..99)

        // 全チャンクの文字起こしが完了したら isTranscribing = false になる
        analysis.update(sample.item.id) { entry ->
            entry.copy(
                transcripts = sample.transcripts,
                lookaheadStatus = entry.lookaheadStatus.copy(
                    inFlightChunkId = null,
                    queuedChunkIds = emptySet(),
                ),
            )
        }
        val completedItem = viewModel.uiState.value.items.single()
        assertEquals(LibraryTranscriptStatus.COMPLETED, completedItem.transcriptStatus)
        assertEquals(100, completedItem.transcriptProgressPercent)
        assertFalse(completedItem.isTranscribing)
    }

    @Test
    fun `ファイルビュー_ファイル名検索と対象言語フィルタで全ファイルを絞り込める`() = runTest {
        val base = SampleMaterials.jaKo.item
        val itemKo = base.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("ko-1"),
            title = "Pimsleur Korean 01",
            course = "韓国語 I",
            languagePair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
        )
        val itemEn = base.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("en-1"),
            title = "Business English 02",
            course = "ビジネス英語",
            languagePair = LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja")),
        )
        val itemEs = base.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("es-1"),
            title = "Spanish Conversation 01",
            course = "スペイン語入門",
            languagePair = LanguagePair(target = LanguageTag("es"), base = LanguageTag("ja")),
        )
        val repo = FakeLibraryRepository(initialItems = listOf(itemKo, itemEn, itemEs))
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(LibraryEvent.SelectTab(LibraryViewTab.FILES))
        assertEquals(LibraryViewTab.FILES, viewModel.uiState.value.selectedTab)
        assertEquals(
            listOf(LanguageTag("en"), LanguageTag("es"), LanguageTag("ko")),
            viewModel.uiState.value.availableTargetLanguages,
        )
        assertEquals(3, viewModel.uiState.value.filteredAllFiles.size)

        // 対象言語フィルタで ko のみ絞り込み
        viewModel.onEvent(LibraryEvent.SelectFileTargetLanguageFilter(LanguageTag("ko")))
        assertEquals(
            listOf(itemKo.id),
            viewModel.uiState.value.filteredAllFiles.map { it.item.id },
        )

        // 言語フィルタを解除し、ファイル名キーワードで絞り込み
        viewModel.onEvent(LibraryEvent.SelectFileTargetLanguageFilter(null))
        viewModel.onEvent(LibraryEvent.UpdateFileSearchQuery("business"))
        assertEquals(
            listOf(itemEn.id),
            viewModel.uiState.value.filteredAllFiles.map { it.item.id },
        )
    }

    @Test
    fun `再生リストビュー_お気に入りと次に再生するキューおよびカテゴリの開閉が動作する`() = runTest {
        val base = SampleMaterials.jaKo.item
        val item1 = base.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("item-1"),
            title = "Lesson 1",
        )
        val item2 = base.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("item-2"),
            title = "Lesson 2",
        )
        val states = mapOf(
            item1.id to ItemState(
                mediaId = item1.id,
                positionMs = 15_000L,
                maxPositionMs = 20_000L,
                isListened = true,
                updatedAtEpochMs = 500L,
            ),
            item2.id to ItemState(
                mediaId = item2.id,
                positionMs = 0L,
                maxPositionMs = 0L,
                isListened = false,
                updatedAtEpochMs = 100L,
            ),
        )
        val repo = FakeLibraryRepository(
            initialItems = listOf(item1, item2),
            initialItemStates = states,
        )
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.onEvent(LibraryEvent.SelectTab(LibraryViewTab.PLAYLISTS))
        assertEquals(LibraryViewTab.PLAYLISTS, viewModel.uiState.value.selectedTab)

        // 最近開いたファイル・よくアクセスするファイル
        assertEquals(
            listOf(item1.id, item2.id),
            viewModel.uiState.value.playlistItemsFor(LibraryPlaylistCategory.RECENTLY_OPENED).map { it.item.id },
        )
        assertEquals(
            listOf(item1.id, item2.id),
            viewModel.uiState.value.playlistItemsFor(LibraryPlaylistCategory.FREQUENTLY_ACCESSED).map { it.item.id },
        )

        // お気に入りの追加・解除
        viewModel.onEvent(LibraryEvent.ToggleFavorite(item2.id))
        assertEquals(
            listOf(item2.id),
            viewModel.uiState.value.playlistItemsFor(LibraryPlaylistCategory.FAVORITES).map { it.item.id },
        )

        // 次に再生するキューへの追加（追加順を保持）
        viewModel.onEvent(LibraryEvent.ToggleQueueItem(item2.id))
        viewModel.onEvent(LibraryEvent.ToggleQueueItem(item1.id))
        assertEquals(
            listOf(item2.id, item1.id),
            viewModel.uiState.value.playlistItemsFor(LibraryPlaylistCategory.UP_NEXT_QUEUE).map { it.item.id },
        )

        // カテゴリを開いて NavigateUp でカテゴリ一覧へ戻る
        viewModel.onEvent(LibraryEvent.SelectPlaylistCategory(LibraryPlaylistCategory.FAVORITES))
        assertEquals(LibraryPlaylistCategory.FAVORITES, viewModel.uiState.value.selectedPlaylistCategory)
        viewModel.onEvent(LibraryEvent.NavigateUp)
        assertEquals(null, viewModel.uiState.value.selectedPlaylistCategory)

        // OpenItem でアクセス回数がインクリメントされ、ViewModel 再生成後もすべて保持される
        viewModel.onEvent(LibraryEvent.OpenItem(item2.id))
        viewModel.onEvent(LibraryEvent.OpenItem(item2.id))

        val recreatedViewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { recreatedViewModel.uiState.collect() }
        assertEquals(
            listOf(item2.id),
            recreatedViewModel.uiState.value.playlistItemsFor(LibraryPlaylistCategory.FAVORITES).map { it.item.id },
        )
        assertEquals(
            listOf(item2.id, item1.id),
            recreatedViewModel.uiState.value.playlistItemsFor(LibraryPlaylistCategory.UP_NEXT_QUEUE).map { it.item.id },
        )
        assertEquals(
            listOf(item2.id, item1.id),
            recreatedViewModel.uiState.value.playlistItemsFor(LibraryPlaylistCategory.FREQUENTLY_ACCESSED).map { it.item.id },
        )
    }

    @Test
    fun `選択モードのお気に入り一括トグル_未お気に入りを含む場合はすべてお気に入りに追加し全員お気に入り済みならすべて解除する`() = runTest {
        val base = SampleMaterials.jaKo.item
        val item1 = base.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("item-1"),
            title = "Lesson 1",
            course = "韓国語 I",
        )
        val item2 = base.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("item-2"),
            title = "Lesson 2",
            course = "韓国語 I",
        )
        val item3 = base.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("item-3"),
            title = "Lesson 3",
            course = "英語 I",
        )
        val repo = FakeLibraryRepository(initialItems = listOf(item1, item2, item3))
        val viewModel = LibraryViewModel(repo, FakePlaybackController())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // 最初に item1 だけお気に入りにしておく
        viewModel.onEvent(LibraryEvent.ToggleFavorite(item1.id))
        assertEquals(setOf(item1.id), viewModel.uiState.value.favoriteItemIds)

        // item1（お気に入り済み）と item2（未お気に入り）を複数選択
        viewModel.onEvent(LibraryEvent.ToggleItemSelection(item1.id))
        viewModel.onEvent(LibraryEvent.ToggleItemSelection(item2.id))
        assertFalse(viewModel.uiState.value.areAllSelectedFavorite)

        // 1回目の ToggleFavoriteSelected -> まだお気に入りでない item2 もお気に入りに追加され、両方お気に入りになる
        viewModel.onEvent(LibraryEvent.ToggleFavoriteSelected)
        assertEquals(setOf(item1.id, item2.id), viewModel.uiState.value.favoriteItemIds)
        assertTrue(viewModel.uiState.value.areAllSelectedFavorite)

        // 2回目の ToggleFavoriteSelected -> 選択中のすべてがお気に入り済みなので両方お気に入りから外れる
        viewModel.onEvent(LibraryEvent.ToggleFavoriteSelected)
        assertTrue(viewModel.uiState.value.favoriteItemIds.isEmpty())
        assertFalse(viewModel.uiState.value.areAllSelectedFavorite)

        // ディレクトリ長押し選択時にも配下のファイル（item1, item2）をまとめてお気に入りトグルできる
        viewModel.onEvent(LibraryEvent.ClearSelection)
        viewModel.onEvent(LibraryEvent.ToggleDirectorySelection("韓国語 I"))
        assertEquals(listOf(item1.id, item2.id), viewModel.uiState.value.selectedTargetItemIds)
        assertFalse(viewModel.uiState.value.areAllSelectedFavorite)

        viewModel.onEvent(LibraryEvent.ToggleFavoriteSelected)
        assertEquals(setOf(item1.id, item2.id), viewModel.uiState.value.favoriteItemIds)
        assertTrue(viewModel.uiState.value.areAllSelectedFavorite)
    }
}
