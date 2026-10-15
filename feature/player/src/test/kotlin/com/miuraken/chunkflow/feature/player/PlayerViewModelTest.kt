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

        // 既定で先読み文字起こしが有効（UserSettings.lookaheadTranscriptionEnabled = true）であり、OFF/ON 切り替えと現在チャンク変更時のトリガーが機能する
        assertTrue(viewModel.uiState.value.lookaheadStatus.enabled)
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(false))
        assertFalse(viewModel.uiState.value.lookaheadStatus.enabled)
        assertFalse(settingsRepo.state.value.lookaheadTranscriptionEnabled)
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))
        assertTrue(viewModel.uiState.value.lookaheadStatus.enabled)
        assertTrue(settingsRepo.state.value.lookaheadTranscriptionEnabled)

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

    @Test
    fun `チャンク別リピートチェック_ToggleChunkRepeatとSetLoopModeOffでrepeatChunkIdsとisRepeatCheckedが更新される`() = runTest {
        val sample = SampleMaterials.jaKo
        val item = sample.item
        val libraryRepo = FakeLibraryRepository(initialItems = listOf(item))
        val playback = FakePlaybackController()
        val settingsRepo = FakeUserSettingsRepository()
        val analysisRepo = FakeAnalysisRepository().apply { put(sample) }
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        val secondChunkId = sample.chunks[1].id
        assertNull(viewModel.uiState.value.chunks[1].text)
        assertNull(viewModel.uiState.value.chunks[1].language)

        // 先読み有効化してチャンク 1 の言語を ko に変更
        viewModel.onEvent(PlayerEvent.SetLookaheadEnabled(true))
        analysisRepo.lookaheadTriggers.clear()
        viewModel.onEvent(PlayerEvent.UpdateChunkLanguage(secondChunkId, LanguageTag("ko")))
        assertEquals(LanguageTag("ko"), viewModel.uiState.value.chunks[1].language)
        assertNull(viewModel.uiState.value.chunks[1].text)
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
        backgroundScope.launch(testDispatcher) { viewModel.uiState.collect() }
        viewModel.open(item.id)

        // 1. チャンクリストでチャンクを選択 (SeekToChunk) すると prioritizeChunkTranscription が呼ばれる
        val chunk1 = sample.chunks[1]
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
        val viewModel = PlayerViewModel(libraryRepo, analysisRepo, playback, settingsRepo)
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
}
