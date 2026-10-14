package com.miuraken.chunkflow.feature.player

import android.net.StubUri
import com.miuraken.chunkflow.core.data.library.TranscriptImportResult
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.UserSettings
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
    fun `速度変更_プリセットと0_05刻み微調整と上下限クランプが機能する`() = runTest {
        val item = SampleMaterials.jaKo.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val viewModel = PlayerViewModel(libraryRepo, playback, settingsRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        viewModel.onEvent(PlayerEvent.SetSpeed(1.5f))
        assertEquals(1.5f, viewModel.uiState.value.speed)

        viewModel.onEvent(PlayerEvent.AdjustSpeed(0.05f))
        assertEquals(1.55f, viewModel.uiState.value.speed)

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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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
}
