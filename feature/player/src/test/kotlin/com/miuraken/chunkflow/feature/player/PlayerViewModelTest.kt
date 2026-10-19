package com.miuraken.chunkflow.feature.player

import android.net.StubUri
import com.miuraken.chunkflow.core.data.library.TranscriptImportResult
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.PhoneticGuideKind
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.TranscriptExportFormat
import com.miuraken.chunkflow.core.model.UserSettings
import com.miuraken.chunkflow.core.model.WordTiming
import com.miuraken.chunkflow.core.playback.PlaybackError
import com.miuraken.chunkflow.core.testing.fake.FakeAnalysisRepository
import com.miuraken.chunkflow.core.testing.fake.FakeLibraryRepository
import com.miuraken.chunkflow.core.testing.fake.FakePlaybackController
import com.miuraken.chunkflow.core.testing.fake.FakeUserSettingsRepository
import com.miuraken.chunkflow.core.testing.fixture.SampleMaterials
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
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
    fun `open_教材とレジューム状態と設定を読み込んでPlayerUiStateに反映する`() = runTest {
        val item = SampleMaterials.jaKo.item
        val libraryRepo = FakeLibraryRepository(
            initialItems = listOf(item),
            initialItemStates = mapOf(
                item.id to ItemState(mediaId = item.id, positionMs = 8_000L, speed = 1.25f),
            ),
        )
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository(
            initialSettings = UserSettings(seekBackMs = 2_000L, seekForwardMs = 10_000L),
        )
        val viewModel = PlayerViewModel(libraryRepo, playback, settingsRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.open(item.id)

        val state = viewModel.uiState.value
        assertEquals(listOf(item.id), playback.openedMediaIds)
        assertEquals(item.id, state.mediaId)
        assertEquals(item.title, state.title)
        assertEquals(item.course, state.course)
        assertEquals(item.languagePair, state.languagePair)
        assertEquals(item.durationMs, state.durationMs)
        assertEquals(1.25f, state.speed)
        assertEquals(2_000L, state.seekBackMs)
        assertEquals(10_000L, state.seekForwardMs)
        assertEquals(8_000L, viewModel.positionProvider.currentPositionMs())
    }

    @Test
    fun `再生操作_再生一時停止トグルとシークと巻き戻し早送りが機能する`() = runTest {
        val item = SampleMaterials.jaKo.item // durationMs = 30_000L
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository(
            initialSettings = UserSettings(seekBackMs = 3_000L, seekForwardMs = 5_000L),
        )
        val viewModel = PlayerViewModel(libraryRepo, playback, settingsRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        assertEquals(false, viewModel.uiState.value.isPlaying)
        viewModel.onEvent(PlayerEvent.TogglePlayPause)
        assertEquals(true, viewModel.uiState.value.isPlaying)
        viewModel.onEvent(PlayerEvent.TogglePlayPause)
        assertEquals(false, viewModel.uiState.value.isPlaying)

        viewModel.onEvent(PlayerEvent.SeekTo(10_000L))
        assertEquals(10_000L, viewModel.positionProvider.currentPositionMs())

        viewModel.onEvent(PlayerEvent.SeekBack)
        assertEquals(7_000L, viewModel.positionProvider.currentPositionMs())

        viewModel.onEvent(PlayerEvent.SeekForward)
        assertEquals(12_000L, viewModel.positionProvider.currentPositionMs())

        // 0 未満および durationMs を超えるシークはクランプされる
        viewModel.onEvent(PlayerEvent.SeekTo(-5_000L))
        assertEquals(0L, viewModel.positionProvider.currentPositionMs())
        viewModel.onEvent(PlayerEvent.SeekTo(99_000L))
        assertEquals(30_000L, viewModel.positionProvider.currentPositionMs())
    }

    @Test
    fun `速度変更_プリセットと0_05刻み微調整と上下限クランプと1xトグルが機能する`() = runTest {
        val item = SampleMaterials.jaKo.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val viewModel = PlayerViewModel(libraryRepo, playback, settingsRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        assertEquals(listOf(0.5f, 0.75f, 1.25f, 1.5f, 2.0f), PlayerUiState.SPEED_PRESETS)
        assertEquals(1.0f, viewModel.uiState.value.speed)
        assertEquals(PlayerUiState.DEFAULT_CUSTOM_SPEED, viewModel.uiState.value.customSpeed)

        viewModel.onEvent(PlayerEvent.SetCustomSpeed(1.25f))
        assertEquals(1.25f, viewModel.uiState.value.speed)
        assertEquals(1.25f, viewModel.uiState.value.customSpeed)
        assertEquals(1.25f, settingsRepo.state.value.defaultSpeed)

        viewModel.onEvent(PlayerEvent.AdjustCustomSpeed(0.05f))
        assertEquals(1.3f, viewModel.uiState.value.speed)
        assertEquals(1.3f, viewModel.uiState.value.customSpeed)
        assertEquals(1.3f, settingsRepo.state.value.defaultSpeed)

        // 1.0f に切り替えても customSpeed と設定の既定速度は維持される
        viewModel.onEvent(PlayerEvent.SetSpeed(1.0f))
        assertEquals(1.0f, viewModel.uiState.value.speed)
        assertEquals(1.3f, viewModel.uiState.value.customSpeed)
        assertEquals(1.3f, settingsRepo.state.value.defaultSpeed)

        viewModel.onEvent(PlayerEvent.SetSpeed(1.5f))
        assertEquals(1.5f, viewModel.uiState.value.speed)
        assertEquals(1.5f, viewModel.uiState.value.customSpeed)
        assertEquals(1.5f, settingsRepo.state.value.defaultSpeed)

        viewModel.onEvent(PlayerEvent.AdjustSpeed(0.05f))
        assertEquals(1.55f, viewModel.uiState.value.speed)
        assertEquals(1.55f, viewModel.uiState.value.customSpeed)

        viewModel.onEvent(PlayerEvent.AdjustSpeed(-0.05f))
        assertEquals(1.5f, viewModel.uiState.value.speed)

        viewModel.onEvent(PlayerEvent.SetSpeed(0.1f))
        assertEquals(ItemState.MIN_SPEED, viewModel.uiState.value.speed)

        viewModel.onEvent(PlayerEvent.SetSpeed(3.0f))
        assertEquals(ItemState.MAX_SPEED, viewModel.uiState.value.speed)
    }

    @Test
    fun `エラー反映_PlaybackStatusのエラーがUiStateに反映される`() = runTest {
        val item = SampleMaterials.jaKo.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val viewModel = PlayerViewModel(libraryRepo, playback, settingsRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        playback.statusState.value = playback.statusState.value.copy(error = PlaybackError.SOURCE_MISSING)
        assertEquals(PlaybackError.SOURCE_MISSING, viewModel.uiState.value.error)
    }

    @Test
    fun `チャンクと練習機能_ブラインドモードとループと自動ポーズと再計算が機能する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample)
        }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        assertEquals(sample.chunks.size, viewModel.uiState.value.chunks.size)
        assertEquals(sample.waveform, viewModel.uiState.value.waveform)

        // ブラインドモードとタップ一時表示
        val firstChunkId = sample.chunks.first().id
        viewModel.onEvent(PlayerEvent.SetBlindMode(BlindMode.HIDE_TARGET_ONLY))
        assertEquals(BlindMode.HIDE_TARGET_ONLY, viewModel.uiState.value.blindMode)
        viewModel.onEvent(PlayerEvent.ToggleRevealChunk(firstChunkId))
        assertTrue(firstChunkId in viewModel.uiState.value.revealedChunkIds)
        viewModel.onEvent(PlayerEvent.SetBlindMode(BlindMode.OFF))
        assertTrue(viewModel.uiState.value.revealedChunkIds.isEmpty())

        // 単一チャンク反復と範囲リピートとA-Bリピート
        viewModel.onEvent(PlayerEvent.SetLoopMode(LoopMode.SingleChunk(firstChunkId, times = 3)))
        assertEquals(LoopMode.SingleChunk(firstChunkId, times = 3), viewModel.uiState.value.practice.loop)

        val secondChunkId = sample.chunks[1].id
        viewModel.onEvent(PlayerEvent.SetLoopMode(LoopMode.ChunkRange(firstChunkId, secondChunkId, times = null)))
        assertEquals(
            LoopMode.ChunkRange(firstChunkId, secondChunkId, times = null),
            viewModel.uiState.value.practice.loop,
        )

        viewModel.onEvent(PlayerEvent.SetLoopMode(LoopMode.AbRange(2_000L, 8_000L)))
        assertEquals(LoopMode.AbRange(2_000L, 8_000L), viewModel.uiState.value.practice.loop)

        // 自動ポーズと出どころ切り替えと再計算
        viewModel.onEvent(PlayerEvent.SetAutoPause(AutoPause.Fixed(1_500L)))
        assertEquals(AutoPause.Fixed(1_500L), viewModel.uiState.value.practice.autoPause)

        viewModel.onEvent(PlayerEvent.SetChunkSource(ChunkOrigin.AUTO))
        assertEquals(ChunkOrigin.AUTO, analysisRepo.chunkSources[item.id])

        val newParams = SegmentationParams(minSilenceMs = 450, prePaddingMs = 150, postPaddingMs = 200)
        viewModel.onEvent(PlayerEvent.Resegment(newParams))
        assertEquals(listOf(item.id to newParams), analysisRepo.resegmentRequests)

        // 前後のチャンク移動
        viewModel.onEvent(PlayerEvent.SeekToChunk(secondChunkId))
        assertEquals(sample.chunks[1].startMs, viewModel.positionProvider.currentPositionMs())
        viewModel.onEvent(PlayerEvent.PreviousChunk)
        assertEquals(sample.chunks[0].startMs, viewModel.positionProvider.currentPositionMs())
        viewModel.onEvent(PlayerEvent.NextChunk)
        assertEquals(sample.chunks[1].startMs, viewModel.positionProvider.currentPositionMs())

        // 文字起こし単体取り込み
        val fakeUri = StubUri.create()
        libraryRepo.importTranscriptResult = {
            TranscriptImportResult.Attached(mediaId = item.id, chunkCount = 6, durationMismatch = false)
        }
        viewModel.onEvent(PlayerEvent.ImportTranscript(fakeUri))
        assertEquals(
            TranscriptImportBannerState.Attached(chunkCount = 6, durationMismatch = false),
            viewModel.uiState.value.transcriptImportBanner,
        )
        viewModel.onEvent(PlayerEvent.DismissTranscriptBanner)
        assertNull(viewModel.uiState.value.transcriptImportBanner)
    }

    @Test
    fun `M3_言語フィルタと瞬間作文モードを切り替えられる`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // 言語フィルタ切り替え (ALL -> TARGET_ONLY -> BASE_ONLY)
        viewModel.onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.TARGET_ONLY))
        assertEquals(LanguageFilter.TARGET_ONLY, viewModel.uiState.value.practice.languageFilter)

        viewModel.onEvent(PlayerEvent.SetLanguageFilter(LanguageFilter.BASE_ONLY))
        assertEquals(LanguageFilter.BASE_ONLY, viewModel.uiState.value.practice.languageFilter)

        // 自動ポーズが OFF の状態で瞬間作文モードを ON にすると、AFTER_BASE_LANGUAGE と Proportional(1.0f, 1000L) が有効になる
        assertEquals(AutoPause.Off, viewModel.uiState.value.practice.autoPause)
        viewModel.onEvent(PlayerEvent.ToggleInstantComposition)
        assertEquals(
            AutoPauseScope.AFTER_BASE_LANGUAGE,
            viewModel.uiState.value.practice.autoPauseScope,
        )
        assertEquals(
            AutoPause.Proportional(factor = 1.0f, minMs = 1_000L),
            viewModel.uiState.value.practice.autoPause,
        )

        // もう一度トグルすると ALL_CHUNKS に戻る
        viewModel.onEvent(PlayerEvent.ToggleInstantComposition)
        assertEquals(AutoPauseScope.ALL_CHUNKS, viewModel.uiState.value.practice.autoPauseScope)
    }

    @Test
    fun `M3_チャンク言語ラベルの手動修正と未判定言語の一括設定とローカルJSON書き出しが機能する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val targetChunkId = sample.chunks[0].id
        // 言語を null（未判定 ?）に更新（表示はすぐに切り替わり、保存と書き出しは最後のトグルから 1 秒後）
        viewModel.onEvent(PlayerEvent.UpdateChunkLanguage(targetChunkId, null))
        assertNull(viewModel.uiState.value.chunks[0].language)
        advanceTimeBy(LANGUAGE_TOGGLE_COMMIT_DELAY_MS + 1L)
        assertTrue(viewModel.uiState.value.hasUnknownLanguageChunks)
        assertTrue(viewModel.uiState.value.localTranscriptExported)
        assertTrue(item.id in libraryRepo.exportedTranscriptIds)

        // 未判定の言語をすべて学習言語 (ko) に一括設定
        viewModel.onEvent(PlayerEvent.BulkSetUnknownTranscriptLanguage(LanguageTag("ko")))
        assertEquals(LanguageTag("ko"), viewModel.uiState.value.chunks[0].language)
        assertFalse(viewModel.uiState.value.hasUnknownLanguageChunks)
    }

    @Test
    fun `M3_文字起こしオフセット微調整と自動補正と先読み文字起こしが機能する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample)
            autoAlignedOffsets[item.id] = -120
            update(item.id) { entry ->
                entry.copy(
                    lookaheadStatus = LookaheadTranscriptionStatus(
                        enabled = false,
                        engineAvailable = true,
                        completedCount = 2,
                        totalTargetCount = 6,
                    ),
                )
            }
        }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // 手動オフセット微調整 (-50ms, +10ms)
        viewModel.onEvent(PlayerEvent.AdjustTranscriptOffset(-50))
        assertEquals(-50, viewModel.uiState.value.transcriptOffsetMs)
        assertEquals(-50, analysisRepo.transcriptOffsets[item.id])

        viewModel.onEvent(PlayerEvent.AdjustTranscriptOffset(10))
        assertEquals(-40, viewModel.uiState.value.transcriptOffsetMs)
        assertEquals(-40, analysisRepo.transcriptOffsets[item.id])

        // 音声の立ち上がりに自動補正
        viewModel.onEvent(PlayerEvent.AutoAlignTranscriptOffset)
        assertEquals(-120, viewModel.uiState.value.transcriptOffsetMs)
        assertEquals(-120, analysisRepo.transcriptOffsets[item.id])

        // 音声ファイルを開いた直後は自動で文字起こしを開始せず（enabled == false）、手動で ON/OFF を切り替えられる
        assertFalse(viewModel.uiState.value.lookaheadStatus.enabled)
        assertTrue(viewModel.uiState.value.isTranscriptionCompleted)
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))
        assertTrue(viewModel.uiState.value.lookaheadStatus.enabled)
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(false))
        assertFalse(viewModel.uiState.value.lookaheadStatus.enabled)
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))
        assertTrue(viewModel.uiState.value.lookaheadStatus.enabled)

        val thirdChunkId = sample.chunks[2].id
        playback.statusState.value = playback.statusState.value.copy(currentChunkId = thirdChunkId)
        assertTrue(analysisRepo.lookaheadTriggers.contains(item.id to thirdChunkId))
    }

    @Test
    fun `M4_ブックマーク追加解除とブックマークのみ再生フィルタが機能する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val firstChunkId = sample.chunks[0].id
        assertFalse(viewModel.uiState.value.chunks[0].isBookmarked)

        viewModel.onEvent(PlayerEvent.ToggleBookmark(firstChunkId))
        assertTrue(viewModel.uiState.value.chunks[0].isBookmarked)
        assertEquals(1, viewModel.uiState.value.bookmarks.size)

        viewModel.onEvent(PlayerEvent.ToggleBookmarksOnly)
        assertTrue(viewModel.uiState.value.practice.bookmarksOnly)

        viewModel.onEvent(PlayerEvent.ToggleBookmarksOnly)
        assertFalse(viewModel.uiState.value.practice.bookmarksOnly)

        viewModel.onEvent(PlayerEvent.ToggleBookmark(firstChunkId))
        assertFalse(viewModel.uiState.value.chunks[0].isBookmarked)
        assertTrue(viewModel.uiState.value.bookmarks.isEmpty())
    }

    @Test
    fun `M4_チャンク本文の手動編集とローカルJSON書き戻しが機能する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val secondChunkId = sample.chunks[1].id
        viewModel.onEvent(PlayerEvent.UpdateChunkText(secondChunkId, "修正後の韓国語テキスト"))
        assertEquals("修正後の韓国語テキスト", viewModel.uiState.value.chunks[1].text)
        assertTrue(viewModel.uiState.value.localTranscriptExported)
        assertTrue(item.id in libraryRepo.exportedTranscriptIds)
    }

    @Test
    fun `M4_チャンク分割と次チャンクとの結合と境界微調整が機能する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val initialCount = viewModel.uiState.value.chunks.size
        val firstChunk = viewModel.uiState.value.chunks[0]
        val midMs = (firstChunk.startMs + firstChunk.endMs) / 2L

        // 分割でチャンク数が +1 になる
        viewModel.onEvent(PlayerEvent.SplitChunk(firstChunk.id, midMs))
        assertEquals(initialCount + 1, viewModel.uiState.value.chunks.size)
        assertEquals(midMs, viewModel.uiState.value.chunks[0].endMs)
        assertEquals(midMs, viewModel.uiState.value.chunks[1].startMs)

        // 先頭チャンクを次チャンクと結合すると元のチャンク数に戻る
        val splitFirstId = viewModel.uiState.value.chunks[0].id
        viewModel.onEvent(PlayerEvent.MergeWithNextChunk(splitFirstId))
        assertEquals(initialCount, viewModel.uiState.value.chunks.size)
        assertEquals(firstChunk.endMs, viewModel.uiState.value.chunks[0].endMs)

        // 境界微調整 (+100ms, -100ms)
        val mergedFirst = viewModel.uiState.value.chunks[0]
        viewModel.onEvent(
            PlayerEvent.MoveChunkBoundary(
                chunkId = mergedFirst.id,
                newStartMs = mergedFirst.startMs + 100L,
                newEndMs = mergedFirst.endMs - 100L,
            ),
        )
        assertEquals(mergedFirst.startMs + 100L, viewModel.uiState.value.chunks[0].startMs)
        assertEquals(mergedFirst.endMs - 100L, viewModel.uiState.value.chunks[0].endMs)
    }

    @Test
    fun `M4_字幕エクスポート_SRT_LRC_TXT書き出しとバナー表示消去が機能する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val fakeUri = StubUri.create()
        viewModel.onEvent(PlayerEvent.ExportTranscriptToUri(TranscriptExportFormat.SRT, fakeUri))
        assertEquals(
            TranscriptExportBannerState.Exported(TranscriptExportFormat.SRT),
            viewModel.uiState.value.transcriptExportBanner,
        )
        assertEquals(1, libraryRepo.writtenTranscriptExports.size)
        assertEquals(item.id, libraryRepo.writtenTranscriptExports.single().first)
        assertEquals(TranscriptExportFormat.SRT, libraryRepo.writtenTranscriptExports.single().second)
        assertSame(fakeUri, libraryRepo.writtenTranscriptExports.single().third)

        viewModel.onEvent(PlayerEvent.DismissExportBanner)
        assertNull(viewModel.uiState.value.transcriptExportBanner)
    }

    @Test
    fun `M4_カラオケ単語ハイライト_原文と文字数一致スクリプト変形を対応付けピンイン等はnullにフォールバックする`() {
        val words = listOf(
            WordTiming(startMs = 1_000L, endMs = 1_500L, text = "今天", confidence = 0.9f),
            WordTiming(startMs = 1_500L, endMs = 2_000L, text = "天气", confidence = 0.9f),
            WordTiming(startMs = 2_000L, endMs = 2_600L, text = "很好", confidence = 0.9f),
        )
        val rawHans = "今天天气很好。"
        val displayHant = "今天天氣很好。"
        val displayPinyin = "jīntiān tiānqì hěn hǎo."

        // 1. 原文と一致する場合
        val hansRanges = mapWordsToCharRanges(
            displayText = rawHans,
            rawText = rawHans,
            words = words,
        )
        assertNotNull(hansRanges)
        assertEquals(3, hansRanges.size)
        assertEquals("天气", rawHans.substring(hansRanges[1].startCharIndex, hansRanges[1].endCharIndex))

        // 2. 簡体字 -> 繁体字など文字数が一致するスクリプト変形の場合、文字オフセットで対応付く
        val hantRanges = mapWordsToCharRanges(
            displayText = displayHant,
            rawText = rawHans,
            words = words,
        )
        assertNotNull(hantRanges)
        assertEquals(3, hantRanges.size)
        assertEquals("天氣", displayHant.substring(hantRanges[1].startCharIndex, hantRanges[1].endCharIndex))
        val activeAt1700 = findActiveWordRange(hantRanges, 1_700L)
        assertEquals(hantRanges[1], activeAt1700)
        assertNull(findActiveWordRange(hantRanges, 900L))

        // 3. ピンイン等で文字数が異なり単語テキストが含まれない場合は null を返して安全にフォールバックする
        val pinyinRanges = mapWordsToCharRanges(
            displayText = displayPinyin,
            rawText = rawHans,
            words = words,
        )
        assertNull(pinyinRanges)
    }

    @Test
    fun `チャンク別リピートチェック_ToggleChunkRepeatとSetLoopModeOffでrepeatChunkIdsとisRepeatCheckedが更新される`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val firstChunkId = viewModel.uiState.value.chunks[0].id
        val thirdChunkId = viewModel.uiState.value.chunks[2].id

        viewModel.onEvent(PlayerEvent.ToggleChunkRepeat(firstChunkId))
        viewModel.onEvent(PlayerEvent.ToggleChunkRepeat(thirdChunkId))
        assertEquals(setOf(firstChunkId, thirdChunkId), viewModel.uiState.value.practice.repeatChunkIds)
        assertTrue(viewModel.uiState.value.chunks[0].isRepeatChecked)
        assertFalse(viewModel.uiState.value.chunks[1].isRepeatChecked)
        assertTrue(viewModel.uiState.value.chunks[2].isRepeatChecked)

        // 再トグルで解除
        viewModel.onEvent(PlayerEvent.ToggleChunkRepeat(firstChunkId))
        assertEquals(setOf(thirdChunkId), viewModel.uiState.value.practice.repeatChunkIds)
        assertFalse(viewModel.uiState.value.chunks[0].isRepeatChecked)

        // ToggleRepeat で repeatCheckedOnly の ON / OFF を切り替えられる
        assertFalse(viewModel.uiState.value.practice.repeatCheckedOnly)
        viewModel.onEvent(PlayerEvent.ToggleRepeat)
        assertTrue(viewModel.uiState.value.practice.repeatCheckedOnly)
        viewModel.onEvent(PlayerEvent.ToggleRepeat)
        assertFalse(viewModel.uiState.value.practice.repeatCheckedOnly)

        // LoopMode.Off で repeatChunkIds もクリアされる
        viewModel.onEvent(PlayerEvent.SetLoopMode(LoopMode.Off))
        assertTrue(viewModel.uiState.value.practice.repeatChunkIds.isEmpty())
        assertFalse(viewModel.uiState.value.chunks[2].isRepeatChecked)
    }

    @Test
    fun `読み方ガイド_SetShowTargetRomanizationとChunkUiのresolvePhoneticReadingが機能する`() = runTest {
        val sample = SampleMaterials.jaKo // target = ko, base = ja
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        assertEquals(PhoneticGuideKind.LATIN_TRANSLITERATION, viewModel.uiState.value.targetPhoneticGuideKind)
        assertFalse(viewModel.uiState.value.showTargetRomanization)

        viewModel.onEvent(PlayerEvent.SetShowTargetRomanization(true))
        assertTrue(viewModel.uiState.value.showTargetRomanization)

        // 1. textVariants に明示的なピンインがある場合
        val zhChunk = ChunkUi(
            id = ChunkId(101L),
            startMs = 0L,
            endMs = 1_000L,
            text = "今天天气很好",
            language = LanguageTag("zh"),
            textVariants = mapOf("Latn" to "jīntiān tiānqì hěn hǎo"),
        )
        assertEquals("jīntiān tiānqì hěn hǎo", zhChunk.resolvePhoneticReading())

        // 2. 韓国語ハングルの自動ローマ字転写
        val koChunk = ChunkUi(
            id = ChunkId(102L),
            startMs = 0L,
            endMs = 1_000L,
            text = "안녕하세요",
            language = LanguageTag("ko"),
        )
        assertEquals("annyeonghaseyo", koChunk.resolvePhoneticReading())

        // 3. 日本語かなの自動ローマ字転写
        val jaChunk = ChunkUi(
            id = ChunkId(103L),
            startMs = 0L,
            endMs = 1_000L,
            text = "ありがとう",
            language = LanguageTag("ja"),
        )
        assertEquals("arigatou", jaChunk.resolvePhoneticReading())

        // 4. ヒンディー語デーヴァナーガリーの自動 IAST 転写
        val hiChunk = ChunkUi(
            id = ChunkId(104L),
            startMs = 0L,
            endMs = 1_000L,
            text = "नमस्ते",
            language = LanguageTag("hi"),
        )
        assertEquals("namaste", hiChunk.resolvePhoneticReading())

        // 5. ラテン文字言語 (en) は null を返す
        val enChunk = ChunkUi(
            id = ChunkId(105L),
            startMs = 0L,
            endMs = 1_000L,
            text = "Hello world",
            language = LanguageTag("en"),
        )
        assertNull(enChunk.resolvePhoneticReading())
    }

    @Test
    fun `先読み文字起こし_一時停止中でも未文字起こしチャンクを順次バックグラウンドでトリガーし続ける`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample)
            // 全チャンクの文字起こしを未取得（空マップ）にする
            update(item.id) { entry ->
                entry.copy(transcripts = emptyMap())
            }
        }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))
        assertTrue(analysisRepo.lookaheadTriggers.contains(item.id to sample.chunks[0].id))

        // 一時停止のまま時間が進むと、後続の未文字起こしチャンクに対しても順次 triggerLookaheadAt が呼ばれる
        advanceTimeBy(350L)
        assertTrue(analysisRepo.lookaheadTriggers.contains(item.id to sample.chunks[1].id))

        advanceTimeBy(350L)
        assertTrue(analysisRepo.lookaheadTriggers.contains(item.id to sample.chunks[2].id))

        // OFF にするとループが停止する
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(false))
        val countAfterDisable = analysisRepo.lookaheadTriggers.size
        advanceTimeBy(1_000L)
        assertEquals(countAfterDisable, analysisRepo.lookaheadTriggers.size)
    }

    @Test
    fun `言語バッジトグルと未文字起こしチャンクの言語変更および再音声認識トリガーが機能する`() = runTest {
        val pair = LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja"))

        // 1. nextToggledChunkLanguage: 未検出 (null / "und") -> 学習言語 (ko) -> 母語 (ja) -> 学習言語 (ko)
        assertEquals(LanguageTag("ko"), nextToggledChunkLanguage(null, pair))
        assertEquals(LanguageTag("ko"), nextToggledChunkLanguage(LanguageTag("und"), pair))
        assertEquals(LanguageTag("ja"), nextToggledChunkLanguage(LanguageTag("ko"), pair))
        assertEquals(LanguageTag("ja"), nextToggledChunkLanguage(LanguageTag("ko-KR"), pair))
        assertEquals(LanguageTag("ko"), nextToggledChunkLanguage(LanguageTag("ja"), pair))
        assertEquals(LanguageTag("ko"), nextToggledChunkLanguage(LanguageTag("ja-JP"), pair))

        // 2. matchesLanguageRole: 主言語サブタグ一致（ja-JP == ja, ko-KR == ko）
        assertTrue(matchesLanguageRole(LanguageTag("ko-KR"), LanguageTag("ko")))
        assertTrue(matchesLanguageRole(LanguageTag("ja-JP"), LanguageTag("ja")))
        assertFalse(matchesLanguageRole(LanguageTag("und"), LanguageTag("ko")))
        assertFalse(matchesLanguageRole(null, LanguageTag("ko")))

        // 3. 未文字起こしチャンクに対して UpdateChunkLanguage を呼ぶと、言語タグが更新され先読み音声認識がトリガーされる
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository(UserSettings(lookaheadTranscriptionEnabled = false))
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample)
            update(item.id) { entry -> entry.copy(transcripts = emptyMap()) }
        }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val secondChunkId = sample.chunks[1].id
        assertNull(viewModel.uiState.value.chunks[1].text)
        assertNull(viewModel.uiState.value.chunks[1].language)

        // 先読み有効化してチャンク 1 の言語を ko に変更
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))
        viewModel.onEvent(PlayerEvent.UpdateChunkLanguage(secondChunkId, LanguageTag("ko")))
        assertEquals(LanguageTag("ko"), viewModel.uiState.value.chunks[1].language)
        assertNull(viewModel.uiState.value.chunks[1].text)
        // 文字起こしのやり直しは最後のトグルから 1 秒後に、そのチャンクを起点に行う
        analysisRepo.lookaheadTriggers.clear()
        advanceTimeBy(LANGUAGE_TOGGLE_COMMIT_DELAY_MS + 1L)
        assertTrue(analysisRepo.lookaheadTriggers.contains(item.id to secondChunkId))
    }

    @Test
    fun `一時停止中のチャンク選択_SeekToChunkで対象チャンク先頭へシークし再生開始状態になる`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // 停止中（自動ポーズ待機中を含む）の状態からチャンク 1 をクリック
        playback.statusState.value = playback.statusState.value.copy(
            isPlaying = false,
            autoPauseEndsAtMs = 5_000L,
        )
        assertFalse(viewModel.uiState.value.isPlaying)

        val secondChunk = sample.chunks[1]
        viewModel.onEvent(PlayerEvent.SeekToChunk(secondChunk.id))

        assertEquals(listOf(secondChunk.id), playback.seekedChunkIds)
        assertEquals(secondChunk.startMs, viewModel.positionProvider.currentPositionMs())
        assertEquals(1, viewModel.uiState.value.activeChunkIndex)
        assertTrue(viewModel.uiState.value.isPlaying)
        assertNull(viewModel.uiState.value.autoPauseEndsAtMs)
    }

    @Test
    fun `チャンク再文字起こし_RetranscribeChunkでretranscribeChunkとローカルJSON書き出しが呼ばれる`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))

        val targetChunkId = sample.chunks[1].id
        analysisRepo.lookaheadTriggers.clear()

        viewModel.onEvent(PlayerEvent.RetranscribeChunk(targetChunkId))

        assertEquals(listOf(item.id to targetChunkId), analysisRepo.retranscribeChunkRequests)
        assertTrue(analysisRepo.lookaheadTriggers.contains(item.id to targetChunkId))
        assertTrue(viewModel.uiState.value.localTranscriptExported)
        assertTrue(item.id in libraryRepo.exportedTranscriptIds)
    }

    @Test
    fun `中国語サブタグ_zh_cnとzh_twの字体優先とmatchesLanguageRoleの判定が機能する`() {
        assertEquals(
            com.miuraken.chunkflow.core.model.ChineseScript.HANS,
            explicitChineseScriptForTag("zh-cn"),
        )
        assertEquals(
            com.miuraken.chunkflow.core.model.ChineseScript.HANT,
            explicitChineseScriptForTag("zh-tw"),
        )
        assertNull(explicitChineseScriptForTag("zh"))

        val cnChunk = ChunkUi(
            id = ChunkId(201L),
            startMs = 0L,
            endMs = 1_000L,
            text = "今天天气很好",
            language = LanguageTag("zh-cn"),
            textVariants = mapOf("Hans" to "今天天气很好", "Hant" to "今天天氣很好"),
        )
        val twChunk = cnChunk.copy(id = ChunkId(202L), language = LanguageTag("zh-tw"))

        // zh-cn は設定が HANT でも Hans を優先し、zh-tw は設定が HANS でも Hant を優先する
        assertEquals(
            "今天天气很好",
            cnChunk.resolveDisplayText(
                chineseScript = com.miuraken.chunkflow.core.model.ChineseScript.HANT,
                cantoneseScript = com.miuraken.chunkflow.core.model.ChineseScript.HANT,
            ),
        )
        assertEquals(
            "今天天氣很好",
            twChunk.resolveDisplayText(
                chineseScript = com.miuraken.chunkflow.core.model.ChineseScript.HANS,
                cantoneseScript = com.miuraken.chunkflow.core.model.ChineseScript.HANT,
            ),
        )

        // matchesLanguageRole: zh と zh-cn は一致、zh-cn と zh-tw は不一致
        assertTrue(matchesLanguageRole(LanguageTag("zh"), LanguageTag("zh-cn")))
        assertTrue(matchesLanguageRole(LanguageTag("zh-cn"), LanguageTag("zh-cn")))
        assertFalse(matchesLanguageRole(LanguageTag("zh-tw"), LanguageTag("zh-cn")))
    }

    @Test
    fun `文字起こしの指定教材インポートとリセット削除が機能する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // 1. 教材を開いている状態で ImportTranscript を発火すると importTranscriptForMedia が呼ばれる
        val fakeUri = StubUri.create()
        libraryRepo.importTranscriptResult = {
            TranscriptImportResult.Attached(
                mediaId = item.id,
                chunkCount = 12,
                durationMismatch = false,
            )
        }
        viewModel.onEvent(PlayerEvent.ImportTranscript(fakeUri))
        assertEquals(1, libraryRepo.importedTranscriptsForMedia.size)
        assertEquals(item.id, libraryRepo.importedTranscriptsForMedia.single().first)
        assertSame(fakeUri, libraryRepo.importedTranscriptsForMedia.single().second)
        assertEquals(
            TranscriptImportBannerState.Attached(chunkCount = 12, durationMismatch = false),
            viewModel.uiState.value.transcriptImportBanner,
        )
        assertEquals(ChunkOrigin.IMPORTED, analysisRepo.chunkSources[item.id])

        // 2. ResetTranscripts を発火すると文字起こしがクリアされ、バナーが ResetCompleted になる
        viewModel.onEvent(PlayerEvent.ResetTranscripts)
        assertEquals(listOf(item.id), libraryRepo.resetTranscriptMediaIds)
        assertEquals(ChunkOrigin.AUTO, analysisRepo.chunkSources[item.id])
        assertFalse(viewModel.uiState.value.hasTranscript)
        assertEquals(
            TranscriptExportBannerState.ResetCompleted,
            viewModel.uiState.value.transcriptExportBanner,
        )
    }

    @Test
    fun `チャンク選択と拡大波形シークで優先文字起こしが呼ばれ_2段階スピナー状態が正しく解決される`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // 0. 文字起こしが OFF の間はチャンク選択・シークで prioritizeChunkTranscription を呼ばない
        val chunk1 = sample.chunks[1]
        analysisRepo.prioritizedChunkRequests.clear()
        viewModel.onEvent(PlayerEvent.SeekToChunk(chunk1.id))
        assertTrue(analysisRepo.prioritizedChunkRequests.isEmpty())

        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))

        // 1. 文字起こしが ON のときチャンクリストでチャンクを選択 (SeekToChunk) すると prioritizeChunkTranscription が呼ばれる
        analysisRepo.prioritizedChunkRequests.clear()
        viewModel.onEvent(PlayerEvent.SeekToChunk(chunk1.id))
        assertTrue(analysisRepo.prioritizedChunkRequests.contains(item.id to chunk1.id))

        // 2. 拡大波形でチャンク位置へシーク (SeekTo) すると該当チャンクの prioritizeChunkTranscription が呼ばれる
        val chunk2 = sample.chunks[2]
        analysisRepo.prioritizedChunkRequests.clear()
        viewModel.onEvent(PlayerEvent.SeekTo(chunk2.startMs + 50L))
        assertTrue(analysisRepo.prioritizedChunkRequests.contains(item.id to chunk2.id))

        // 3. resolveChunkTranscriptionStage: 文字起こしデータがないチャンクの 2 段階スピナー状態判定
        val c1 = ChunkId(1L)
        val c2 = ChunkId(2L)
        val c3 = ChunkId(3L)
        val emptyC1 = ChunkUi(id = c1, startMs = 0L, endMs = 1_000L, text = null, language = null)
        val emptyC2 = ChunkUi(id = c2, startMs = 1_000L, endMs = 2_000L, text = null, language = null)
        val emptyC3 = ChunkUi(id = c3, startMs = 2_000L, endMs = 3_000L, text = null, language = null)
        val filledC1 = emptyC1.copy(text = "こんにちは")

        // 文字起こし済みのチャンクは常に IDLE
        assertEquals(
            ChunkTranscriptionStage.IDLE,
            resolveChunkTranscriptionStage(
                chunk = filledC1,
                lookaheadStatus = com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus(
                    enabled = true,
                    inFlightChunkId = c1,
                    queuedChunkIds = setOf(c1),
                ),
            ),
        )

        // 現在文字起こし実行中 (inFlightChunkId == chunkId) のチャンクは TRANSCRIBING（第2段階）
        assertEquals(
            ChunkTranscriptionStage.TRANSCRIBING,
            resolveChunkTranscriptionStage(
                chunk = emptyC1,
                lookaheadStatus = com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus(
                    enabled = false,
                    inFlightChunkId = c1,
                    queuedChunkIds = setOf(c2),
                ),
            ),
        )

        // キュー待ち (queuedChunkIds に含まれる) または先読み有効時の未文字起こしチャンクは WAITING（第1段階）
        assertEquals(
            ChunkTranscriptionStage.WAITING,
            resolveChunkTranscriptionStage(
                chunk = emptyC2,
                lookaheadStatus = com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus(
                    enabled = false,
                    inFlightChunkId = c1,
                    queuedChunkIds = setOf(c2),
                ),
            ),
        )
        assertEquals(
            ChunkTranscriptionStage.WAITING,
            resolveChunkTranscriptionStage(
                chunk = emptyC3,
                lookaheadStatus = com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus(
                    enabled = true,
                    inFlightChunkId = c1,
                    queuedChunkIds = emptySet(),
                ),
            ),
        )

        // 先読み無効かつ実行中でもキュー待ちでもない未文字起こしチャンクは IDLE
        assertEquals(
            ChunkTranscriptionStage.IDLE,
            resolveChunkTranscriptionStage(
                chunk = emptyC3,
                lookaheadStatus = com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus(
                    enabled = false,
                    inFlightChunkId = null,
                    queuedChunkIds = emptySet(),
                ),
            ),
        )
    }

    @Test
    fun `ファイル開閉時の優先解析と文字起こしセッション管理_openで優先解析とセッション開始が呼ばれcloseとBackでセッション終了が呼ばれる`() = runTest {
        val sample1 = SampleMaterials.jaKo
        val sample2 = sample1.copy(item = sample1.item.copy(id = com.miuraken.chunkflow.core.model.MediaId("sample_2")))
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(sample1.item, sample2.item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample1)
            put(sample2)
        }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // 1. sample1 を開くと sample1 のセッションが open=true になり、prioritizeOpenedMediaAnalysis が呼ばれる
        viewModel.open(sample1.item.id)
        assertEquals(setOf(sample1.item.id), analysisRepo.openTranscriptionSessions)
        assertEquals(listOf(sample1.item.id), analysisRepo.prioritizedMediaAnalyses)

        // 2. sample2 に切り替えると sample1 が open=false になり、sample2 が open=true かつ優先解析される
        viewModel.open(sample2.item.id)
        assertEquals(setOf(sample2.item.id), analysisRepo.openTranscriptionSessions)
        assertEquals(listOf(sample1.item.id, sample2.item.id), analysisRepo.prioritizedMediaAnalyses)

        // 3. Back または close で現在のファイルの文字起こしセッションが閉じられる
        viewModel.onEvent(PlayerEvent.Back)
        assertTrue(analysisRepo.openTranscriptionSessions.isEmpty())
    }

    @Test
    fun `言語バッジのトグル_最後のトグルから1秒たつまで保存せず元の言語に戻した場合は文字起こしをやり直さない`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository(UserSettings(lookaheadTranscriptionEnabled = false))
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val chunkId = sample.chunks[0].id // ja「では、聞いてみましょう。」
        suspend fun storedTranscript() = analysisRepo.observeTranscripts(item.id).first().getValue(chunkId)

        // ja -> ko -> ja と素早くトグルすると、表示は切り替わるが保存はまだ行わない
        viewModel.onEvent(PlayerEvent.UpdateChunkLanguage(chunkId, LanguageTag("ko")))
        assertEquals(LanguageTag("ko"), viewModel.uiState.value.chunks[0].language)
        advanceTimeBy(LANGUAGE_TOGGLE_COMMIT_DELAY_MS / 2)
        viewModel.onEvent(PlayerEvent.UpdateChunkLanguage(chunkId, LanguageTag("ja")))
        assertEquals(LanguageTag("ja"), viewModel.uiState.value.chunks[0].language)
        advanceTimeBy(LANGUAGE_TOGGLE_COMMIT_DELAY_MS / 2 + 1L)
        assertFalse(storedTranscript().userEdited)

        // 最後のトグルから 1 秒たっても、保存済みの言語（本文あり）と同じなので何もしない（誤タップの取り消し）
        advanceTimeBy(LANGUAGE_TOGGLE_COMMIT_DELAY_MS)
        assertFalse(storedTranscript().userEdited)
        assertEquals(LanguageTag("ja"), storedTranscript().language)
        assertTrue(libraryRepo.exportedTranscriptIds.isEmpty())

        // 別の言語のまま 1 秒たつと保存し、文字起こしを書き出す
        viewModel.onEvent(PlayerEvent.UpdateChunkLanguage(chunkId, LanguageTag("ko")))
        advanceTimeBy(LANGUAGE_TOGGLE_COMMIT_DELAY_MS + 1L)
        assertTrue(storedTranscript().userEdited)
        assertEquals(LanguageTag("ko"), storedTranscript().language)
        assertTrue(item.id in libraryRepo.exportedTranscriptIds)
    }

    @Test
    fun `チャンクの選択_拡大波形の長押しの選択がチェックボックスと連動し全選択解除ですべて外れる`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val c1 = sample.chunks[1].id
        val c3 = sample.chunks[3].id
        viewModel.onEvent(PlayerEvent.SetChunkSelected(c1, selected = true))
        viewModel.onEvent(PlayerEvent.SetChunkSelected(c3, selected = true))
        assertEquals(setOf(c1, c3), playback.practiceState.value.repeatChunkIds)
        assertEquals(
            listOf(false, true, false, true, false),
            viewModel.uiState.value.chunks.map { it.isRepeatChecked },
        )

        // 同じ値をもう一度送っても変わらず、false で選択が外れる
        viewModel.onEvent(PlayerEvent.SetChunkSelected(c3, selected = true))
        viewModel.onEvent(PlayerEvent.SetChunkSelected(c1, selected = false))
        assertEquals(setOf(c3), playback.practiceState.value.repeatChunkIds)

        viewModel.onEvent(PlayerEvent.ClearChunkSelection)
        assertTrue(playback.practiceState.value.repeatChunkIds.isEmpty())
        assertTrue(viewModel.uiState.value.chunks.none { it.isRepeatChecked })
    }

    @Test
    fun `連結_非連続や異言語の選択も先頭チャンクの言語に合わせて1つに連結し未確定の言語変更も反映する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val chunks = sample.chunks
        // SingleChunk ループ中にチャンクを選択すると、複数選択リピートと干渉しないよう LoopMode.Off に戻る
        viewModel.onEvent(PlayerEvent.SetLoopMode(LoopMode.SingleChunk(chunks[0].id, times = null)))
        assertEquals(LoopMode.SingleChunk(chunks[0].id, times = null), viewModel.uiState.value.practice.loop)

        // 先頭チャンク (chunks[1]: ko) の言語バッジをトグルして未コミット状態で ja に切り替え、非連続の chunks[3] (ko) と選択
        viewModel.onEvent(PlayerEvent.UpdateChunkLanguage(chunks[1].id, LanguageTag("ja")))
        assertEquals(LanguageTag("ja"), viewModel.uiState.value.chunks[1].language)
        viewModel.onEvent(PlayerEvent.SetChunkSelected(chunks[1].id, selected = true))
        assertEquals(LoopMode.Off, viewModel.uiState.value.practice.loop)
        viewModel.onEvent(PlayerEvent.SetChunkSelected(chunks[3].id, selected = true))

        assertTrue(canMergeSelectedChunks(viewModel.uiState.value.chunks))
        viewModel.onEvent(PlayerEvent.MergeSelectedChunks)

        assertEquals(listOf(item.id to listOf(chunks[1].id, chunks[3].id)), analysisRepo.mergeChunksRequests)
        val merged = viewModel.uiState.value.chunks
        // chunks[1]..chunks[3] の 3 件が 1 件に統合されるので 5 -> 3 件になる
        assertEquals(3, merged.size)
        assertEquals(chunks[1].startMs, merged[1].startMs)
        assertEquals(chunks[3].endMs, merged[1].endMs)
        // 先頭チャンク (chunks[1]) の未コミット言語オーバーライド (ja) が反映されている
        assertEquals(LanguageTag("ja"), merged[1].language)
        assertTrue(playback.practiceState.value.repeatChunkIds.isEmpty())
    }

    @Test
    fun `分割_選択がないとき現在位置でチャンクを2つに分け端に近すぎる位置では分けない`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // チャンク 1（4100..6900）の開始から 200ms 未満の位置では分けない
        viewModel.onEvent(PlayerEvent.SeekTo(4_200L))
        viewModel.onEvent(PlayerEvent.SplitChunkAtCurrentPosition)
        assertEquals(5, viewModel.uiState.value.chunks.size)

        // 他のチャンク（chunks[0], chunks[3]）を選択した状態でチャンク 1 を 5000ms で分けると、分割が実行され他チャンクの選択状態が維持される
        val selectedOther0 = viewModel.uiState.value.chunks[0]
        val selectedOther3 = viewModel.uiState.value.chunks[3]
        viewModel.onEvent(PlayerEvent.SetChunkSelected(selectedOther0.id, selected = true))
        viewModel.onEvent(PlayerEvent.SetChunkSelected(selectedOther3.id, selected = true))
        viewModel.onEvent(PlayerEvent.SeekTo(5_000L))
        viewModel.onEvent(PlayerEvent.SplitChunkAtCurrentPosition)
        val chunks = viewModel.uiState.value.chunks
        assertEquals(6, chunks.size)
        assertEquals(4_100L to 5_000L, chunks[1].startMs to chunks[1].endMs)
        assertEquals(5_000L to 6_900L, chunks[2].startMs to chunks[2].endMs)
        assertTrue(chunks[0].isRepeatChecked)
        assertFalse(chunks[1].isRepeatChecked)
        assertFalse(chunks[2].isRepeatChecked)
        assertTrue(chunks[4].isRepeatChecked) // 元の chunks[3]
    }

    @Test
    fun `チャンク解除_DeleteChunkで対象チャンクが解除され他のチャンクの選択状態は維持される`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val c0 = sample.chunks[0]
        val c1 = sample.chunks[1]
        val c2 = sample.chunks[2]
        viewModel.onEvent(PlayerEvent.SetChunkSelected(c0.id, selected = true))
        viewModel.onEvent(PlayerEvent.SetChunkSelected(c1.id, selected = true))
        viewModel.onEvent(PlayerEvent.SetChunkSelected(c2.id, selected = true))

        viewModel.onEvent(PlayerEvent.DeleteChunk(c1.id))

        assertEquals(listOf(item.id to c1.id), analysisRepo.deleteChunkRequests)
        val remaining = viewModel.uiState.value.chunks
        assertEquals(sample.chunks.size - 1, remaining.size)
        assertTrue(remaining.none { it.id == c1.id })
        assertTrue(remaining.first { it.startMs == c0.startMs && it.endMs == c0.endMs }.isRepeatChecked)
        assertTrue(remaining.first { it.startMs == c2.startMs && it.endMs == c2.endMs }.isRepeatChecked)
    }

    @Test
    fun `教材の言語_設定シートで学習言語とベース言語を変えると教材に保存される`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        viewModel.onEvent(PlayerEvent.UpdateItemLanguages(target = LanguageTag("ZH-CN"), base = LanguageTag("en")))
        val expected = LanguagePair(target = LanguageTag("zh-cn"), base = LanguageTag("en"))
        assertEquals(expected, libraryRepo.observeItem(item.id).first()?.languagePair)
        assertEquals(expected, viewModel.uiState.value.languagePair)
        assertEquals(item.course, libraryRepo.observeItem(item.id).first()?.course)

        // ベース言語が学習言語と同じなら「なし」にする
        viewModel.onEvent(PlayerEvent.UpdateItemLanguages(target = LanguageTag("ko"), base = LanguageTag("ko")))
        assertEquals(LanguagePair(target = LanguageTag("ko"), base = null), viewModel.uiState.value.languagePair)
    }

    @Test
    fun `再生設定の開閉_チャンクにチェックを付けてリピートONの状態でcloseして同じファイルを再openしてもチェックとリピートが維持される`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.open(item.id)
        val chunk0 = sample.chunks[0].id
        val chunk2 = sample.chunks[2].id

        // 2 つのチャンクにチェックを付け、リピートを ON にする
        viewModel.onEvent(PlayerEvent.ToggleChunkRepeat(chunk0))
        viewModel.onEvent(PlayerEvent.ToggleChunkRepeat(chunk2))
        viewModel.onEvent(PlayerEvent.ToggleRepeat)

        assertEquals(setOf(chunk0, chunk2), playback.practiceState.value.repeatChunkIds)
        assertTrue(playback.practiceState.value.repeatCheckedOnly)
        assertTrue(viewModel.uiState.value.practice.repeatCheckedOnly)
        assertTrue(viewModel.uiState.value.chunks.first { it.id == chunk0 }.isRepeatChecked)
        assertTrue(viewModel.uiState.value.chunks.first { it.id == chunk2 }.isRepeatChecked)

        // 「再生設定」画面へ遷移して PlayerRoute が一時破棄される（DisposableEffect -> close(item.id)）
        viewModel.close(item.id)
        assertTrue(analysisRepo.openTranscriptionSessions.isEmpty())

        // 「再生設定」画面を閉じて PlayerRoute に戻る（LaunchedEffect -> open(item.id)）
        viewModel.open(item.id)
        assertEquals(setOf(item.id), analysisRepo.openTranscriptionSessions)

        // チェック状態・リピート ON 状態ともに失われず維持され、playbackController.open も再実行されない
        assertEquals(listOf(item.id), playback.openedMediaIds)
        assertEquals(setOf(chunk0, chunk2), playback.practiceState.value.repeatChunkIds)
        assertTrue(playback.practiceState.value.repeatCheckedOnly)
        assertTrue(viewModel.uiState.value.practice.repeatCheckedOnly)
        assertTrue(viewModel.uiState.value.chunks.first { it.id == chunk0 }.isRepeatChecked)
        assertTrue(viewModel.uiState.value.chunks.first { it.id == chunk2 }.isRepeatChecked)
    }

    @Test
    fun `チャンクのスキップ設定と解除_単体および複数一括でSetChunksSkippedがChunkUiに反映される`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val c0 = sample.chunks[0].id
        val c1 = sample.chunks[1].id
        val c3 = sample.chunks[3].id
        assertTrue(viewModel.uiState.value.chunks.none { it.isSkipped })

        // 1. 単体チャンクのスキップ設定・解除
        viewModel.onEvent(PlayerEvent.SetChunksSkipped(setOf(c1), skipped = true))
        assertTrue(viewModel.uiState.value.chunks[1].isSkipped)
        assertFalse(viewModel.uiState.value.chunks[0].isSkipped)

        viewModel.onEvent(PlayerEvent.SetChunksSkipped(setOf(c1), skipped = false))
        assertFalse(viewModel.uiState.value.chunks[1].isSkipped)

        // 2. 複数チャンクの一括スキップ設定・解除
        viewModel.onEvent(PlayerEvent.SetChunksSkipped(setOf(c0, c1, c3), skipped = true))
        assertEquals(
            listOf(true, true, false, true, false),
            viewModel.uiState.value.chunks.map { it.isSkipped },
        )

        viewModel.onEvent(PlayerEvent.SetChunksSkipped(setOf(c0, c3), skipped = false))
        assertEquals(
            listOf(false, true, false, false, false),
            viewModel.uiState.value.chunks.map { it.isSkipped },
        )
    }

    @Test
    fun `順次翻訳_1チャンク以上文字起こし済みなら未完了でも翻訳が実行され再生位置や選択範囲から順次translateChunkが呼ばれる`() = runTest {
        val sample = SampleMaterials.jaKo // chunks: 0(ja), 1(ko), 2(ja), 3(ko), 4(ko)
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample)
            // 先頭チャンクをまだ文字起こし未完了にしておく
            update(item.id) { entry ->
                entry.copy(
                    transcripts = entry.transcripts - sample.chunks[0].id,
                    lookaheadStatus = LookaheadTranscriptionStatus(
                        enabled = true,
                        engineAvailable = true,
                        inFlightChunkId = sample.chunks[0].id,
                    ),
                )
            }
        }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // 1. 全チャンクの文字起こしが未完了 (isAllTranscriptionCompleted = false) でも、1 チャンク以上文字起こし済み (hasTranscript = true) である
        assertTrue(viewModel.uiState.value.isTranslationSupported)
        assertTrue(viewModel.uiState.value.hasTranscript)
        assertFalse(viewModel.uiState.value.isAllTranscriptionCompleted)
        assertTrue(analysisRepo.translatedChunkRequests.isEmpty())

        // 2. 全完了を待たずに再生位置を chunks[3] (ko) に合わせて「翻訳」バッジを ON にすると、現在位置 (chunks[3]) -> 末尾 (chunks[4]) -> 先頭折り返し (chunks[1]) の順で学習対象言語のみ翻訳される
        viewModel.onEvent(PlayerEvent.SeekToChunk(sample.chunks[3].id))
        viewModel.onEvent(PlayerEvent.SetShowTargetTranslation(true))

        assertTrue(viewModel.uiState.value.showTargetTranslation)
        val requestedIds = analysisRepo.translatedChunkRequests.map { it.second }
        assertEquals(
            listOf(sample.chunks[3].id, sample.chunks[4].id, sample.chunks[1].id),
            requestedIds,
        )
        assertEquals("[ja] ${sample.transcripts.getValue(sample.chunks[1].id).text}", viewModel.uiState.value.chunks[1].translatedText)
        assertEquals("[ja] ${sample.transcripts.getValue(sample.chunks[3].id).text}", viewModel.uiState.value.chunks[3].translatedText)
        assertEquals("[ja] ${sample.transcripts.getValue(sample.chunks[4].id).text}", viewModel.uiState.value.chunks[4].translatedText)
        assertNull(viewModel.uiState.value.chunks[0].translatedText) // 母語 (ja) チャンクは翻訳しない
        assertNull(viewModel.uiState.value.translatingChunkId)

        // 4. 複数選択リピート中 (repeatCheckedOnly = true) はチェックされたチャンク群を優先し、スキップ設定されたチャンクは除外する
        viewModel.onEvent(PlayerEvent.SetShowTargetTranslation(false))
        assertFalse(viewModel.uiState.value.showTargetTranslation)
        analysisRepo.translatedChunkRequests.clear()
        analysisRepo.update(item.id) { entry ->
            entry.copy(transcripts = sample.transcripts) // 翻訳テキストをリセット
        }
        // chunks[1] をスキップ設定にし、chunks[4] をチェック選択リピートにする
        viewModel.onEvent(PlayerEvent.SetChunksSkipped(setOf(sample.chunks[1].id), skipped = true))
        viewModel.onEvent(PlayerEvent.SetChunkSelected(sample.chunks[4].id, selected = true))
        viewModel.onEvent(PlayerEvent.ToggleRepeat) // repeatCheckedOnly = true
        viewModel.onEvent(PlayerEvent.SeekToChunk(sample.chunks[0].id))

        viewModel.onEvent(PlayerEvent.SetShowTargetTranslation(true))
        val checkedOrder = analysisRepo.translatedChunkRequests.map { it.second }
        // チェックされた chunks[4] が最優先で翻訳され、次に未チェックの chunks[3] が翻訳される（スキップされた chunks[1] は翻訳されない）
        assertEquals(listOf(sample.chunks[4].id, sample.chunks[3].id), checkedOrder)
    }

    @Test
    fun `resolveChunkTranscriptionStage_文字起こし中と待機中と空結果スキップおよび強制実行を正しく判定する`() {
        val c1 = ChunkUi(id = ChunkId(1L), startMs = 0L, endMs = 1_000L, text = null, language = null)
        val c2 = ChunkUi(id = ChunkId(2L), startMs = 1_000L, endMs = 2_000L, text = null, language = null)
        val c3 = ChunkUi(id = ChunkId(3L), startMs = 2_000L, endMs = 3_000L, text = null, language = null)

        // 1. 通常の先読み実行中: c1 が inFlight -> TRANSCRIBING, c2 が未試行 -> WAITING, c3 が空結果でスキップ済み -> IDLE
        val activeStatus = LookaheadTranscriptionStatus(
            enabled = true,
            engineAvailable = true,
            inFlightChunkId = c1.id,
            queuedChunkIds = setOf(c2.id),
            completedCount = 0,
            totalTargetCount = 3,
            skippedEmptyChunkIds = setOf(c3.id),
        )
        assertEquals(ChunkTranscriptionStage.TRANSCRIBING, resolveChunkTranscriptionStage(c1, activeStatus))
        assertEquals(ChunkTranscriptionStage.WAITING, resolveChunkTranscriptionStage(c2, activeStatus))
        assertEquals(ChunkTranscriptionStage.IDLE, resolveChunkTranscriptionStage(c3, activeStatus))

        // 2. 全チャンク試行完了後 (completedCount >= totalTargetCount かつ inFlight == null) は未取得チャンクも WAITING に残さず IDLE にする
        val finishedStatus = LookaheadTranscriptionStatus(
            enabled = true,
            engineAvailable = true,
            inFlightChunkId = null,
            queuedChunkIds = emptySet(),
            completedCount = 3,
            totalTargetCount = 3,
        )
        assertEquals(ChunkTranscriptionStage.IDLE, resolveChunkTranscriptionStage(c2, finishedStatus))

        // 3. 先読み OFF でも強制文字起こし実行中 (isForcedChunkInFlight = true) のチャンクは TRANSCRIBING を返す
        val forcedStatus = LookaheadTranscriptionStatus(
            enabled = false,
            engineAvailable = true,
            inFlightChunkId = c2.id,
            isForcedChunkInFlight = true,
        )
        assertEquals(ChunkTranscriptionStage.TRANSCRIBING, resolveChunkTranscriptionStage(c2, forcedStatus))
        assertEquals(ChunkTranscriptionStage.IDLE, resolveChunkTranscriptionStage(c1, forcedStatus))
    }

    @Test
    fun `文字起こし中断とリセット_SetLookaheadEnabledFalseおよびResetTranscriptionで進行中状態が即座にクリアされる`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample)
            update(item.id) { entry ->
                entry.copy(
                    lookaheadStatus = LookaheadTranscriptionStatus(
                        enabled = true,
                        engineAvailable = true,
                        inFlightChunkId = sample.chunks[0].id,
                        queuedChunkIds = setOf(sample.chunks[1].id),
                        completedCount = 1,
                        totalTargetCount = 5,
                        isForcedChunkInFlight = true,
                    ),
                )
            }
        }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))
        analysisRepo.update(item.id) { entry ->
            entry.copy(
                lookaheadStatus = LookaheadTranscriptionStatus(
                    enabled = true,
                    engineAvailable = true,
                    inFlightChunkId = sample.chunks[0].id,
                    queuedChunkIds = setOf(sample.chunks[1].id),
                    completedCount = 1,
                    totalTargetCount = 5,
                    isForcedChunkInFlight = true,
                ),
            )
        }

        // 文字起こし OFF にすると inFlightChunkId / queuedChunkIds / isForcedChunkInFlight が即座にクリアされる
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(false))
        val afterDisable = viewModel.uiState.value.lookaheadStatus
        assertFalse(afterDisable.enabled)
        assertNull(afterDisable.inFlightChunkId)
        assertTrue(afterDisable.queuedChunkIds.isEmpty())
        assertFalse(afterDisable.isForcedChunkInFlight)

        // ResetTranscription を呼ぶと resetTranscriptionRequests に記録され状態がクリアされる
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))
        viewModel.onEvent(PlayerEvent.ResetTranscription)
        assertTrue(analysisRepo.resetTranscriptionRequests.contains(item.id))
        assertFalse(viewModel.uiState.value.lookaheadStatus.enabled)
        assertNull(viewModel.uiState.value.lookaheadStatus.inFlightChunkId)
    }

    @Test
    fun `より詳しく文字起こし_ダイアログを開いてエンジンと速度を指定して試行し結果をチャンクに反映できる`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // エンジン並び順と速度選択肢の仕様確認
        assertEquals(
            listOf(
                com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ML_KIT,
                com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER,
                com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.SHERPA_ONNX,
            ),
            DetailedTranscriptionDialogState.ENGINE_OPTIONS,
        )
        assertEquals(
            listOf(0.5f, 0.75f, 1.0f, 1.5f),
            DetailedTranscriptionDialogState.AUDIO_SPEED_OPTIONS,
        )

        // ko は SpeechRecognizer が INSTALLED (ML Kit は UNSUPPORTED)、ja は ML Kit が INSTALLED とする
        analysisRepo.languageModelInfos[LanguageTag("ko")] =
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                language = LanguageTag("ko"),
                mlKitStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.UNSUPPORTED,
                status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
            )
        analysisRepo.languageModelInfos[LanguageTag("ja")] =
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                language = LanguageTag("ja"),
                mlKitStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
                sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
            )

        val targetChunk = sample.chunks[1] // ko チャンク

        // 1. ダイアログを開くとチャンクの言語 (ko) が初期選択され、ko で INSTALLED の最上位エンジン (SpeechRecognizer) が自動選択される
        viewModel.onEvent(PlayerEvent.OpenDetailedTranscription(targetChunk.id))
        val openedDialog = viewModel.uiState.value.detailedTranscriptionDialog
        assertNotNull(openedDialog)
        assertEquals(targetChunk.id, openedDialog.chunkId)
        assertEquals(LanguageTag("ko"), openedDialog.languageOverride)
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER,
            openedDialog.engine,
        )
        assertEquals(1.0f, openedDialog.audioSpeed)
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.UNSUPPORTED,
            openedDialog.engineStatus(
                LanguageTag("ko"),
                com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ML_KIT,
            ),
        )
        val initialChunkText = viewModel.uiState.value.chunks[1].text.orEmpty()
        assertEquals(initialChunkText, openedDialog.resultText)

        // 認識言語を母語 (ja) に切り替えると、ja で INSTALLED の最上位エンジン (ML Kit) に自動切り替わる
        viewModel.onEvent(PlayerEvent.SelectDetailedTranscriptionLanguage(LanguageTag("ja")))
        val afterLangSwitch = viewModel.uiState.value.detailedTranscriptionDialog
        assertNotNull(afterLangSwitch)
        assertEquals(LanguageTag("ja"), afterLangSwitch.languageOverride)
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ML_KIT,
            afterLangSwitch.engine,
        )

        // 未インストールのエンジン (SpeechRecognizer for ja) のモデルダウンロードを要求すると、選択状態になり DOWNLOADING へ遷移する
        viewModel.onEvent(
            PlayerEvent.RequestDetailedTranscriptionModelDownload(
                language = LanguageTag("ja"),
                engine = com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER,
            ),
        )
        val afterDownloadReq = viewModel.uiState.value.detailedTranscriptionDialog
        assertNotNull(afterDownloadReq)
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER,
            afterDownloadReq.engine,
        )
        assertEquals(
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.DOWNLOADING,
            afterDownloadReq.engineStatus(
                LanguageTag("ja"),
                com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER,
            ),
        )
        assertEquals(
            listOf(LanguageTag("ja") to com.miuraken.chunkflow.core.model.SttEngine.SPEECH_RECOGNIZER),
            analysisRepo.requestedSttModelDownloads,
        )

        // チャンクプレビュー再生: 元の速度 (1.25x) を保存して指定速度 (0.5x) で再生し、停止で元の速度に戻る
        playback.setSpeed(1.25f)
        viewModel.onEvent(
            PlayerEvent.PlayDetailedTranscriptionChunkPreview(
                chunkId = targetChunk.id,
                speed = 0.5f,
            ),
        )
        assertTrue(viewModel.uiState.value.detailedTranscriptionDialog?.isPreviewPlaying == true)
        assertTrue(playback.status.value.isPlaying)
        assertEquals(0.5f, playback.status.value.speed)
        assertEquals(targetChunk.id, playback.status.value.currentChunkId)

        viewModel.onEvent(PlayerEvent.StopDetailedTranscriptionChunkPreview)
        assertFalse(viewModel.uiState.value.detailedTranscriptionDialog?.isPreviewPlaying == true)
        assertFalse(playback.status.value.isPlaying)
        assertEquals(1.25f, playback.status.value.speed)

        // 2. エンジンと速度倍率 (1.5x) を指定して試行を実行する
        viewModel.onEvent(
            PlayerEvent.RunDetailedTranscriptionTrial(
                chunkId = targetChunk.id,
                engine = com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ML_KIT,
                languageOverride = LanguageTag("ko"),
                audioSpeed = 1.5f,
            ),
        )
        val afterTrial = viewModel.uiState.value.detailedTranscriptionDialog
        assertNotNull(afterTrial)
        assertFalse(afterTrial.isRunning)
        assertEquals(1, analysisRepo.customTrialRequests.size)
        val trialReq = analysisRepo.customTrialRequests.single()
        assertEquals(targetChunk.id, trialReq.chunkId)
        assertEquals(com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ML_KIT, trialReq.engine)
        assertEquals(LanguageTag("ko"), trialReq.languageOverride)
        assertEquals(1.5f, trialReq.audioSpeed)
        assertNotNull(afterTrial.resultText)

        // 3. 試行結果をチャンクへ反映するとダイアログが閉じ、チャンクのテキストと言語が更新される
        viewModel.onEvent(
            PlayerEvent.ApplyDetailedTranscriptionResult(
                chunkId = targetChunk.id,
                text = "より詳しい文字起こし結果",
                language = LanguageTag("ko"),
            ),
        )
        assertNull(viewModel.uiState.value.detailedTranscriptionDialog)
        val updatedChunk = viewModel.uiState.value.chunks.first { it.id == targetChunk.id }
        assertEquals("より詳しい文字起こし結果", updatedChunk.text)
        assertEquals(LanguageTag("ko"), updatedChunk.language)
    }

    @Test
    fun `文字起こしバッジと進捗ペインの表示制御_実行開始で進捗ペインが自動表示されバッジタップで表示非表示がトグルされ中断ボタンで停止する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository(UserSettings(lookaheadTranscriptionEnabled = false))
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample)
            // 先頭チャンクを未文字起こし・先読み停止状態で開始
            update(item.id) { entry ->
                entry.copy(
                    transcripts = entry.transcripts - sample.chunks[0].id,
                    lookaheadStatus = LookaheadTranscriptionStatus(
                        enabled = false,
                        engineAvailable = true,
                        completedCount = 4,
                        totalTargetCount = 5,
                    ),
                )
            }
        }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(false))

        // 1. 停止状態（未完了・停止中）: バッジは表示され、実行中ではなく、進捗ペインは非表示
        assertTrue(viewModel.uiState.value.isTranscriptionBadgeVisible)
        assertFalse(viewModel.uiState.value.isTranscriptionRunning)
        assertFalse(viewModel.uiState.value.isTranscriptionProgressVisible)

        // 2. 文字起こし開始: 実行中になり、進捗ペインが自動表示される
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))
        analysisRepo.update(item.id) { entry ->
            entry.copy(
                lookaheadStatus = entry.lookaheadStatus.copy(
                    enabled = true,
                    inFlightChunkId = sample.chunks[0].id,
                ),
            )
        }
        assertTrue(viewModel.uiState.value.isTranscriptionBadgeVisible)
        assertTrue(viewModel.uiState.value.isTranscriptionRunning)
        assertTrue(viewModel.uiState.value.isTranscriptionProgressVisible)

        // 3. 実行中に「文字起こし」バッジをタップ (ToggleTranscriptionProgress): 文字起こし自体は停止せず、進捗ペインの表示・非表示だけを切り替える
        viewModel.onEvent(PlayerEvent.ToggleTranscriptionProgress)
        assertTrue(viewModel.uiState.value.isTranscriptionRunning)
        assertTrue(viewModel.uiState.value.lookaheadStatus.enabled)
        assertFalse(viewModel.uiState.value.isTranscriptionProgressVisible)

        viewModel.onEvent(PlayerEvent.ToggleTranscriptionProgress)
        assertTrue(viewModel.uiState.value.isTranscriptionRunning)
        assertTrue(viewModel.uiState.value.isTranscriptionProgressVisible)

        // 4. 進捗ペイン内の「中断」ボタン (ResetTranscription) を押すと文字起こしが停止し、進捗ペインも閉じる
        viewModel.onEvent(PlayerEvent.ResetTranscription)
        assertFalse(viewModel.uiState.value.isTranscriptionRunning)
        assertFalse(viewModel.uiState.value.isTranscriptionProgressVisible)
        assertTrue(viewModel.uiState.value.isTranscriptionBadgeVisible) // 未完了なのでバッジは残る
    }

    @Test
    fun `文字起こしバッジの自動非表示と再表示_全完了で非表示になりテキスト削除や再文字起こしで再表示される`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample)
            update(item.id) { entry ->
                entry.copy(
                    lookaheadStatus = LookaheadTranscriptionStatus(
                        enabled = false,
                        engineAvailable = true,
                        completedCount = sample.chunks.size,
                        totalTargetCount = sample.chunks.size,
                    ),
                )
            }
        }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // 1. 全チャンクの文字起こしが完了しており翻訳も OFF のとき、「文字起こし」バッジは非表示になる
        assertTrue(viewModel.uiState.value.isAllTranscriptionCompleted)
        assertFalse(viewModel.uiState.value.isTranscriptionBadgeVisible)

        // 2. チャンクのテキストを空に編集（削除）すると未文字起こしチャンクが生じるため、「文字起こし」バッジが再表示される
        val firstChunkId = sample.chunks[0].id
        viewModel.onEvent(PlayerEvent.UpdateChunkText(firstChunkId, ""))
        assertFalse(viewModel.uiState.value.isAllTranscriptionCompleted)
        assertTrue(viewModel.uiState.value.isTranscriptionBadgeVisible)

        // テキストを再入力すると再び全完了となりバッジは非表示に戻る
        viewModel.onEvent(PlayerEvent.UpdateChunkText(firstChunkId, "復元したテキスト"))
        assertTrue(viewModel.uiState.value.isAllTranscriptionCompleted)
        assertFalse(viewModel.uiState.value.isTranscriptionBadgeVisible)

        // 3. 単体または複数の強制再文字起こしがキューに入る・実行中になると、「文字起こし」バッジが再表示され進捗ペインも自動表示される
        analysisRepo.update(item.id) { entry ->
            entry.copy(
                lookaheadStatus = entry.lookaheadStatus.copy(
                    inFlightChunkId = firstChunkId,
                    isForcedChunkInFlight = true,
                ),
            )
        }
        assertTrue(viewModel.uiState.value.isTranscriptionRunning)
        assertTrue(viewModel.uiState.value.isTranscriptionBadgeVisible)
        assertTrue(viewModel.uiState.value.isTranscriptionProgressVisible)
    }

    @Test
    fun `詳細文字起こしプレビュー再生_一時停止中に0_5xで再生しても元の速度に巻き戻されず停止時に復元される`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.open(item.id)
        viewModel.onEvent(PlayerEvent.SetSpeed(1.25f))
        assertEquals(1.25f, viewModel.uiState.value.speed)

        val targetChunk = sample.chunks[1]
        viewModel.onEvent(PlayerEvent.OpenDetailedTranscription(targetChunk.id))
        viewModel.onEvent(PlayerEvent.PlayDetailedTranscriptionChunkPreview(targetChunk.id, 0.5f))

        assertEquals(0.5f, playback.status.value.speed)
        assertEquals(0.5f, viewModel.uiState.value.speed)
        assertTrue(viewModel.uiState.value.detailedTranscriptionDialog?.isPreviewPlaying == true)
        assertEquals(0.5f, viewModel.uiState.value.detailedTranscriptionDialog?.audioSpeed)

        viewModel.onEvent(PlayerEvent.StopDetailedTranscriptionChunkPreview)
        assertFalse(viewModel.uiState.value.detailedTranscriptionDialog?.isPreviewPlaying == true)
        assertEquals(1.25f, playback.status.value.speed)
        assertEquals(1.25f, viewModel.uiState.value.speed)
    }

    @Test
    fun `再生ボタン長押しアクション_巻き戻し早送り秒数変更とファイル先頭移動と前後チェック項目移動と次のファイル移動が機能する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item1 = sample.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("track-1"),
            title = "Lesson 2",
            course = "韓国語講座",
        )
        val item2 = sample.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("track-2"),
            title = "Lesson 10",
            course = "韓国語講座",
        )
        val itemOtherCourse = sample.item.copy(
            id = com.miuraken.chunkflow.core.model.MediaId("track-other"),
            title = "Lesson 3",
            course = "別講座",
        )
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item2, item1, itemOtherCourse))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample.copy(item = item1))
            put(sample.copy(item = item2))
        }
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository())
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        viewModel.open(item1.id)
        // 自然順ソートで Lesson 2 の次は Lesson 10 (item2)
        assertEquals(item2.id, viewModel.uiState.value.nextMediaId)

        // 1. 巻き戻し・早送り秒数の変更
        viewModel.onEvent(PlayerEvent.SetSeekBackMs(2_000L))
        viewModel.onEvent(PlayerEvent.SetSeekForwardMs(15_000L))
        assertEquals(2_000L, viewModel.uiState.value.seekBackMs)
        assertEquals(15_000L, viewModel.uiState.value.seekForwardMs)

        // 2. チェック項目への前後移動（チャンク 1 とチャンク 3 を選択）
        val c0 = sample.chunks[0]
        val c1 = sample.chunks[1]
        val c2 = sample.chunks[2]
        val c3 = sample.chunks[3]
        viewModel.onEvent(PlayerEvent.SetChunkSelected(c1.id, true))
        viewModel.onEvent(PlayerEvent.SetChunkSelected(c3.id, true))
        viewModel.onEvent(PlayerEvent.SeekToChunk(c2.id))
        assertEquals(2, viewModel.uiState.value.activeChunkIndex)
        assertTrue(viewModel.uiState.value.hasPreviousCheckedChunk)
        assertTrue(viewModel.uiState.value.hasNextCheckedChunk)

        viewModel.onEvent(PlayerEvent.SeekToPreviousCheckedChunk)
        assertEquals(c1.id, playback.status.value.currentChunkId)
        assertFalse(viewModel.uiState.value.hasPreviousCheckedChunk)
        assertTrue(viewModel.uiState.value.hasNextCheckedChunk)

        viewModel.onEvent(PlayerEvent.SeekToNextCheckedChunk)
        assertEquals(c3.id, playback.status.value.currentChunkId)
        assertTrue(viewModel.uiState.value.hasPreviousCheckedChunk)
        assertFalse(viewModel.uiState.value.hasNextCheckedChunk)

        // 3. ファイル先頭へ移動
        viewModel.onEvent(PlayerEvent.SeekToFileStart)
        assertEquals(c0.id, playback.status.value.currentChunkId)

        // 4. 次のファイルへ移動（Lesson 10 が末尾なのでその次は null）
        viewModel.onEvent(PlayerEvent.OpenNextMediaItem)
        assertEquals(item2.id, viewModel.uiState.value.mediaId)
        assertNull(viewModel.uiState.value.nextMediaId)
    }

    @Test
    fun `手動文字起こし開始と使用言語トグル_open直後は文字起こしが無効でToggleEnabledLanguageで使用言語を更新できる`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository(
            UserSettings(
                lookaheadTranscriptionEnabled = true,
                enabledLanguages = setOf(LanguageTag("en"), LanguageTag("ja")),
            ),
        )
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample)
            update(item.id) { entry -> entry.copy(transcripts = emptyMap()) }
        }
        val viewModel = PlayerViewModel(
            libraryRepo,
            analysisRepo,
            playback,
            settingsRepo,
            com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository(),
        )
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }

        // 1. 教材を open しても自動では文字起こしは開始されず、hasTranscript も false
        viewModel.open(item.id)
        assertFalse(viewModel.uiState.value.lookaheadStatus.enabled)
        assertFalse(viewModel.uiState.value.hasTranscript)
        advanceTimeBy(1_000L)
        assertTrue(analysisRepo.lookaheadTriggers.isEmpty())

        // 2. ToggleEnabledLanguage で使用言語を追加・削除できる（最低 1 言語は維持）
        viewModel.onEvent(PlayerEvent.ToggleEnabledLanguage(LanguageTag("ko")))
        assertEquals(
            setOf(LanguageTag("en"), LanguageTag("ja"), LanguageTag("ko")),
            viewModel.uiState.value.enabledLanguages.toSet(),
        )
        viewModel.onEvent(PlayerEvent.ToggleEnabledLanguage(LanguageTag("en")))
        viewModel.onEvent(PlayerEvent.ToggleEnabledLanguage(LanguageTag("ja")))
        assertEquals(listOf(LanguageTag("ko")), viewModel.uiState.value.enabledLanguages)
        viewModel.onEvent(PlayerEvent.ToggleEnabledLanguage(LanguageTag("ko")))
        assertEquals(listOf(LanguageTag("ko")), viewModel.uiState.value.enabledLanguages)
    }

    @Test
    fun `より詳しく文字起こし_試行結果が空のときモデル未導入や非対応エンジンやマイク権限不足や一般空結果の具体的メッセージを設定する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample)
            customTrialResultStub = null
        }
        val viewModel = PlayerViewModel(
            libraryRepo,
            analysisRepo,
            playback,
            settingsRepo,
            com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository(),
        )
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val targetChunk = sample.chunks[1]

        // 1. モデル未導入 (NOT_INSTALLED) のとき
        analysisRepo.languageModelInfos[LanguageTag("ko")] =
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                language = LanguageTag("ko"),
                mlKitStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.UNSUPPORTED,
                status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
                sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
            )
        viewModel.onEvent(PlayerEvent.OpenDetailedTranscription(targetChunk.id))
        viewModel.onEvent(
            PlayerEvent.RunDetailedTranscriptionTrial(
                chunkId = targetChunk.id,
                engine = com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.SHERPA_ONNX,
                languageOverride = LanguageTag("ko"),
                audioSpeed = 1.0f,
            ),
        )
        assertEquals(
            "選択した言語（ko）の音声認識モデルがダウンロードされていません。モデルをダウンロードしてから再試行してください。",
            viewModel.uiState.value.detailedTranscriptionDialog?.trialEmptyMessage,
        )

        // 2. 非対応エンジン (UNSUPPORTED) のとき
        viewModel.onEvent(
            PlayerEvent.RunDetailedTranscriptionTrial(
                chunkId = targetChunk.id,
                engine = com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ML_KIT,
                languageOverride = LanguageTag("ko"),
                audioSpeed = 1.0f,
            ),
        )
        assertEquals(
            "選択した文字起こしエンジンは言語（ko）に対応していません。別のエンジンを選択してください。",
            viewModel.uiState.value.detailedTranscriptionDialog?.trialEmptyMessage,
        )

        // 3. SpeechRecognizer でマイク権限がないとき (モデル自体は INSTALLED)
        analysisRepo.languageModelInfos[LanguageTag("ko")] =
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                language = LanguageTag("ko"),
                mlKitStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.UNSUPPORTED,
                status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
            )
        viewModel.onEvent(PlayerEvent.OpenDetailedTranscription(targetChunk.id))
        viewModel.onEvent(
            PlayerEvent.RunDetailedTranscriptionTrial(
                chunkId = targetChunk.id,
                engine = com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.ANDROID_SPEECH_RECOGNIZER,
                languageOverride = LanguageTag("ko"),
                audioSpeed = 1.0f,
                hasRecordAudioPermission = false,
            ),
        )
        assertEquals(
            "SpeechRecognizer で認識できませんでした。マイク権限が許可されているか確認するか、ML Kit / sherpa-onnx をお試しください。",
            viewModel.uiState.value.detailedTranscriptionDialog?.trialEmptyMessage,
        )

        // 4. モデル導入済みかつ権限ありでも空結果のとき（無音・BGM や言語不一致の案内）
        viewModel.onEvent(
            PlayerEvent.RunDetailedTranscriptionTrial(
                chunkId = targetChunk.id,
                engine = com.miuraken.chunkflow.core.model.OnDeviceTranscriptionEngine.SHERPA_ONNX,
                languageOverride = LanguageTag("ko"),
                audioSpeed = 1.0f,
                hasRecordAudioPermission = true,
            ),
        )
        assertEquals(
            "音声を認識できませんでした。このチャンクが無音・BGM のみであるか、認識言語（現在: ko）が実際の音声の言語と異なっている可能性があります。認識言語やエンジン・入力速度を変えて再試行してください。",
            viewModel.uiState.value.detailedTranscriptionDialog?.trialEmptyMessage,
        )
    }

    @Test
    fun `文字起こし設定診断状態_同一言語設定と全チャンク空結果完了とrecognitionModelIssuesをUiStateに反映する`() = runTest {
        val sample = SampleMaterials.jaKo
        val sameLangItem = sample.item.copy(
            languagePair = LanguagePair(target = LanguageTag("ja"), base = LanguageTag("ja-JP")),
        )
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(sameLangItem))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample.copy(item = sameLangItem))
            update(sameLangItem.id) { entry ->
                entry.copy(
                    transcripts = emptyMap(),
                    lookaheadStatus = LookaheadTranscriptionStatus(
                        enabled = true,
                        engineAvailable = true,
                        completedCount = sample.chunks.size,
                        totalTargetCount = sample.chunks.size,
                        skippedEmptyChunkIds = sample.chunks.map { it.id }.toSet(),
                        recognitionModelIssues = mapOf(
                            LanguageTag("ja") to com.miuraken.chunkflow.core.model.OnDeviceRecognitionModelState.NOT_INSTALLED,
                        ),
                    ),
                )
            }
        }
        val viewModel = PlayerViewModel(
            libraryRepo,
            analysisRepo,
            playback,
            settingsRepo,
            com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository(),
        )
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(sameLangItem.id)

        val state = viewModel.uiState.value
        assertTrue(state.hasSameTargetAndBaseLanguage)
        assertTrue(state.isAllTranscriptionEmptyAfterCompletion)
        assertFalse(state.isTranscriptionRunning)
        assertEquals(
            mapOf(LanguageTag("ja") to com.miuraken.chunkflow.core.model.OnDeviceRecognitionModelState.NOT_INSTALLED),
            state.recognitionModelIssues,
        )

        // 定期ポーリングコルーチンを終了させるためテスト末尾でモデル問題をクリアする
        analysisRepo.update(sameLangItem.id) { entry ->
            entry.copy(
                lookaheadStatus = entry.lookaheadStatus.copy(recognitionModelIssues = emptyMap()),
            )
        }
    }

    @Test
    fun `バックグラウンド文字起こし切替_SpeechRecognizer使用言語がある場合は警告ダイアログ状態を出しSherpa等のみなら出さない`() = runTest {
        val sample = SampleMaterials.jaKo // target=ko, base=ja
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample)
            languageModelInfos[LanguageTag("ko")] =
                com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                    language = LanguageTag("ko"),
                    status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                    mlKitStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.UNSUPPORTED,
                    sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.NOT_INSTALLED,
                )
            languageModelInfos[LanguageTag("ja")] =
                com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                    language = LanguageTag("ja"),
                    status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                    mlKitStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                    sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                )
        }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // 1. ko が SpeechRecognizer にフォールバックする状態で ON にすると、setBackgroundTranscriptionEnabled(true) が呼ばれ警告状態がセットされる
        viewModel.onEvent(PlayerEvent.SetBackgroundTranscriptionEnabled(true))
        assertEquals(listOf(item.id to true), analysisRepo.backgroundTranscriptionRequests)
        assertTrue(viewModel.uiState.value.lookaheadStatus.backgroundEnabled)
        assertEquals(
            BackgroundSpeechRecognizerWarningState(
                speechRecognizerLanguages = listOf(LanguageTag("ko")),
                sherpaSupportedLanguages = listOf(LanguageTag("ko")),
            ),
            viewModel.uiState.value.backgroundSpeechRecognizerWarning,
        )

        // 2. DismissBackgroundSpeechRecognizerWarning で警告ダイアログが閉じる（backgroundEnabled は true のまま）
        viewModel.onEvent(PlayerEvent.DismissBackgroundSpeechRecognizerWarning)
        assertNull(viewModel.uiState.value.backgroundSpeechRecognizerWarning)
        assertTrue(viewModel.uiState.value.lookaheadStatus.backgroundEnabled)

        // 3. OFF にすると setBackgroundTranscriptionEnabled(false) が呼ばれる
        viewModel.onEvent(PlayerEvent.SetBackgroundTranscriptionEnabled(false))
        assertEquals(listOf(item.id to true, item.id to false), analysisRepo.backgroundTranscriptionRequests)
        assertFalse(viewModel.uiState.value.lookaheadStatus.backgroundEnabled)

        // 4. 両言語とも SHERPA_ONNX / ML_KIT が使われる状態なら ON にしても警告は出ない
        analysisRepo.languageModelInfos[LanguageTag("ko")] =
            com.miuraken.chunkflow.core.model.OnDeviceLanguageModelInfo(
                language = LanguageTag("ko"),
                status = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
                mlKitStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.UNSUPPORTED,
                sherpaModelStatus = com.miuraken.chunkflow.core.model.OnDeviceLanguageModelStatus.INSTALLED,
            )
        viewModel.onEvent(
            PlayerEvent.SetTargetEngineOverride(com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX),
        )
        viewModel.onEvent(PlayerEvent.SetBackgroundTranscriptionEnabled(true))
        assertTrue(viewModel.uiState.value.lookaheadStatus.backgroundEnabled)
        assertNull(viewModel.uiState.value.backgroundSpeechRecognizerWarning)
    }

    @Test
    fun `ファイル個別設定_チャンク分割の全体設定追従チェック切替と個別パラメータ保存および再分割と言語変更時の自動言語判定が動作する`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // 1. 初期状態では「全体設定に従う」(useGlobalSegmentationParams = true) になっている
        assertTrue(viewModel.uiState.value.useGlobalSegmentationParams)

        // 2. チェックを外して個別パラメータで再分割すると customSegmentationParams が保存され自動言語判定が走る
        viewModel.onEvent(PlayerEvent.SetUseGlobalSegmentation(false))
        assertFalse(viewModel.uiState.value.useGlobalSegmentationParams)
        val customParams = SegmentationParams(
            minSilenceMs = 520,
            minSpeechMs = 280,
            prePaddingMs = 110,
            postPaddingMs = 170,
        )
        viewModel.onEvent(PlayerEvent.Resegment(customParams))
        assertEquals(customParams, viewModel.uiState.value.segmentationParams)
        assertEquals(
            customParams,
            analysisRepo.customSegmentationParamsByMedia[item.id],
        )
        assertEquals(listOf(item.id), analysisRepo.languageDetectionOnlyRequests)

        // 3. ItemState に保存された個別設定がある状態で ViewModel を再生成すると useGlobalSegmentationParams = false が復元される
        val libraryRepoWithSavedCustomSeg = FakeLibraryRepository(
            initialItems = listOf(item),
            initialItemStates = mapOf(
                item.id to com.miuraken.chunkflow.core.model.ItemState(
                    mediaId = item.id,
                    customSegmentationParams = customParams,
                ),
            ),
        )
        val recreatedViewModel = PlayerViewModel(
            libraryRepoWithSavedCustomSeg,
            analysisRepo,
            playback,
            settingsRepo,
            itemSttOverrideRepository,
        )
        backgroundScope.launch(testDispatcher) { recreatedViewModel.uiState.collect() }
        recreatedViewModel.open(item.id)
        assertFalse(recreatedViewModel.uiState.value.useGlobalSegmentationParams)
        assertEquals(customParams, recreatedViewModel.uiState.value.segmentationParams)

        // 4. 「全体設定に従う」を ON に戻すと customSegmentationParams がクリアされ全体設定値で再分割される
        recreatedViewModel.onEvent(PlayerEvent.SetUseGlobalSegmentation(true))
        assertTrue(recreatedViewModel.uiState.value.useGlobalSegmentationParams)
        assertNull(analysisRepo.customSegmentationParamsByMedia[item.id])
        assertEquals(SegmentationParams(), recreatedViewModel.uiState.value.segmentationParams)
        assertEquals(listOf(item.id, item.id), analysisRepo.languageDetectionOnlyRequests)

        // 5. ファイルの言語設定を変更すると startLanguageDetectionOnly が自動で呼ばれる
        recreatedViewModel.onEvent(
            PlayerEvent.UpdateItemLanguages(
                target = LanguageTag("en"),
                base = LanguageTag("ja"),
            ),
        )
        assertEquals(listOf(item.id, item.id, item.id), analysisRepo.languageDetectionOnlyRequests)
    }

    @Test
    fun `ファイル個別言語設定_言語設定に従うの括弧内エンジン解決が全体の言語設定値を返す`() {
        val sttEngineByLanguage = mapOf(
            LanguageTag("ja") to com.miuraken.chunkflow.core.model.SttEngine.ML_KIT,
            LanguageTag("ko") to com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX,
        )
        assertEquals(
            com.miuraken.chunkflow.core.model.SttEngine.ML_KIT,
            resolveConfiguredDefaultEngineForLanguage("ja-JP", sttEngineByLanguage),
        )
        assertEquals(
            com.miuraken.chunkflow.core.model.SttEngine.SHERPA_ONNX,
            resolveConfiguredDefaultEngineForLanguage("ko", sttEngineByLanguage),
        )
        // 未設定の言語は対応エンジン（ML Kit 等）のデフォルトにフォールバックする
        assertEquals(
            com.miuraken.chunkflow.core.model.SttEngine.ML_KIT,
            resolveConfiguredDefaultEngineForLanguage("en", sttEngineByLanguage),
        )
    }

    @Test
    fun `言語判定のみ完了とlanguageOverrides自動消去_LID完了時は空結果警告にならずhasChunkLanguageがtrueになりDB本文更新で古いoverrideが消える`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply {
            put(sample)
            // 最初はテキストなし（言語判定のみ完了: completedCount == totalTargetCount, skippedEmptyChunkIds は空, 各チャンクに language のみあり）
            update(item.id) { entry ->
                entry.copy(
                    transcripts = sample.transcripts.mapValues { (_, t) ->
                        t.copy(text = "")
                    },
                    lookaheadStatus = LookaheadTranscriptionStatus(
                        enabled = false,
                        engineAvailable = true,
                        completedCount = sample.chunks.size,
                        totalTargetCount = sample.chunks.size,
                        skippedEmptyChunkIds = emptySet(),
                    ),
                )
            }
        }
        val itemSttOverrideRepository = com.miuraken.chunkflow.core.testing.fake.FakeItemSttOverrideRepository()
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo, itemSttOverrideRepository)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // 1. 言語判定のみ完了時: hasTranscript は false だが hasChunkLanguage は true、isAllTranscriptionEmptyAfterCompletion は false
        val initialState = viewModel.uiState.value
        assertFalse(initialState.hasTranscript)
        assertTrue(initialState.hasChunkLanguage)
        assertFalse(initialState.isAllTranscriptionEmptyAfterCompletion)

        // 2. チャンク 0 (元は ja) の transcript レコードがいったん未作成 (null) の状態で言語を ko に手動変更し、デバウンス完了させる
        val chunk0Id = sample.chunks[0].id
        val originalTranscript0 = sample.transcripts.getValue(chunk0Id)
        analysisRepo.update(item.id) { entry ->
            entry.copy(transcripts = entry.transcripts - chunk0Id)
        }
        viewModel.onEvent(PlayerEvent.UpdateChunkLanguage(chunk0Id, LanguageTag("ko")))
        assertEquals(LanguageTag("ko"), viewModel.uiState.value.chunks.first { it.id == chunk0Id }.language)
        advanceTimeBy(1_100L)
        // DB 側にまだレコードがないため languageOverrides[chunk0Id] = ko が残っている
        assertEquals(LanguageTag("ko"), viewModel.uiState.value.chunks.first { it.id == chunk0Id }.language)

        // 3. その後、文字起こし完了等で DB 側に本文ありの transcript (language = ja) が保存されると、
        // 古い languageOverrides が消去されて DB 側の言語 (ja) と本文が UI に反映される
        analysisRepo.update(item.id) { entry ->
            entry.copy(
                transcripts = entry.transcripts + (
                    chunk0Id to originalTranscript0.copy(
                        text = "自動文字起こしされた日本語テキスト",
                        language = LanguageTag("ja"),
                    )
                    ),
            )
        }

        val updatedChunk0 = viewModel.uiState.value.chunks.first { it.id == chunk0Id }
        assertEquals("自動文字起こしされた日本語テキスト", updatedChunk0.text)
        assertEquals(LanguageTag("ja"), updatedChunk0.language)
        assertTrue(viewModel.uiState.value.hasTranscript)
    }
}
