package com.miuraken.chunkflow.feature.player

import android.net.StubUri
import com.miuraken.chunkflow.core.data.library.TranscriptImportResult
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LookaheadTranscriptionStatus
import com.miuraken.chunkflow.core.model.LoopMode
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

    @Test
    fun `M3_言語フィルタと瞬間作文モードを切り替えられる`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val targetChunkId = sample.chunks[0].id
        // 言語を null（未判定 ?）に更新
        viewModel.onEvent(PlayerEvent.UpdateChunkLanguage(targetChunkId, null))
        assertNull(viewModel.uiState.value.chunks[0].language)
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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

        // 先読み文字起こしの有効化と現在チャンク変更時のトリガー
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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
}
