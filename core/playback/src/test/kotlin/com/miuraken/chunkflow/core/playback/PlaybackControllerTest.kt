package com.miuraken.chunkflow.core.playback

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.Util
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.data.library.ImportProgress
import com.miuraken.chunkflow.core.data.library.LibraryRepository
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.data.library.SyncProgress
import com.miuraken.chunkflow.core.data.library.TranscriptImportResult
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.UserSettings
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.transcript.TranscriptError
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlaybackControllerTest {

    private lateinit var context: Context
    private lateinit var mediaFileLocator: MediaFileLocator
    private lateinit var libraryRepository: InMemoryLibraryRepository
    private lateinit var analysisRepository: InMemoryAnalysisRepository
    private lateinit var settingsRepository: InMemoryUserSettingsRepository
    private lateinit var practiceController: PracticeController
    private lateinit var stubPlayer: FakeMedia3Player
    private var nowEpochMs: Long = 1_000L

    private val mediaId1 = MediaId("00000000-0000-4000-8000-000000000001")
    private val mediaId2 = MediaId("00000000-0000-4000-8000-000000000002")

    @BeforeTest
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "media").deleteRecursively()
        mediaFileLocator = MediaFileLocator(context.filesDir)
        libraryRepository = InMemoryLibraryRepository()
        analysisRepository = InMemoryAnalysisRepository()
        settingsRepository = InMemoryUserSettingsRepository()
        practiceController = PracticeController()
        stubPlayer = FakeMedia3Player()
    }

    @AfterTest
    fun tearDown() {
        File(context.filesDir, "media").deleteRecursively()
    }

    @Test
    fun `open_音声ファイルが存在する場合は前回の位置と速度と学習モードでprepareされる`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val item = sampleAudioItem(mediaId1, durationMs = 120_000L)
        val savedState = ItemState(
            mediaId = mediaId1,
            positionMs = 25_000L,
            speed = 1.5f,
            autoPause = AutoPause.Fixed(1_500L),
            autoPauseScope = AutoPauseScope.AFTER_BASE_LANGUAGE,
            languageFilter = LanguageFilter.TARGET_ONLY,
            updatedAtEpochMs = 500L,
        )
        libraryRepository.items.value = listOf(item)
        libraryRepository.itemStates.value = mapOf(mediaId1 to savedState)
        mediaFileLocator.audioFileFor(mediaId1, "mp3").writeBytes(byteArrayOf(1, 2, 3))

        val controller = MediaControllerPlaybackController(
            context = context,
            libraryRepository = libraryRepository,
            userSettingsRepository = settingsRepository,
            practiceController = practiceController,
            mediaFileLocator = mediaFileLocator,
            mainDispatcher = dispatcher,
            analysisRepository = analysisRepository,
            rawPlayerFactory = { stubPlayer },
            clock = { nowEpochMs },
        )

        // 事前にループを設定しておき、open 時に解除されることも確かめる（§7.2.5）
        practiceController.update { it.copy(loop = LoopMode.AbRange(1_000L, 5_000L)) }

        controller.open(mediaId1)
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()

        val status = controller.status.value
        assertEquals(mediaId1, status.mediaId)
        assertEquals(120_000L, status.durationMs)
        assertEquals(1.5f, status.speed)
        assertNull(status.error)
        assertTrue(stubPlayer.prepared)
        assertEquals(25_000L, controller.position.currentPositionMs())

        // ループは解除され、autoPause / languageFilter は復元される
        val practice = controller.practice.value
        assertEquals(LoopMode.Off, practice.loop)
        assertEquals(AutoPause.Fixed(1_500L), practice.autoPause)
        assertEquals(AutoPauseScope.AFTER_BASE_LANGUAGE, practice.autoPauseScope)
        assertEquals(LanguageFilter.TARGET_ONLY, practice.languageFilter)

        controller.release()
    }

    @Test
    fun `open_新規教材かつItemState未保存時はUserSettingsのdefaultAutoPauseが適用されseekToChunkで対象チャンクへ移動する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val item = sampleAudioItem(mediaId1, durationMs = 60_000L)
        libraryRepository.items.value = listOf(item)
        mediaFileLocator.audioFileFor(mediaId1, "mp3").writeBytes(byteArrayOf(1, 2, 3))

        val chunk1 = Chunk(ChunkId(10L), 0L, 4_000L, ChunkOrigin.AUTO)
        val chunk2 = Chunk(ChunkId(20L), 4_000L, 9_500L, ChunkOrigin.AUTO)
        analysisRepository.chunksByMedia.value = mapOf(mediaId1 to listOf(chunk1, chunk2))
        settingsRepository.state.value = UserSettings(defaultAutoPause = AutoPause.Fixed(800L))

        val controller = MediaControllerPlaybackController(
            context = context,
            libraryRepository = libraryRepository,
            userSettingsRepository = settingsRepository,
            practiceController = practiceController,
            mediaFileLocator = mediaFileLocator,
            mainDispatcher = dispatcher,
            analysisRepository = analysisRepository,
            rawPlayerFactory = { stubPlayer },
            clock = { nowEpochMs },
        )

        controller.open(mediaId1)
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()

        assertEquals(AutoPause.Fixed(800L), controller.practice.value.autoPause)
        assertEquals(chunk1.id, controller.status.value.currentChunkId)

        controller.seekToChunk(chunk2.id)
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        advanceUntilIdle()

        assertEquals(4_000L, controller.position.currentPositionMs())
        assertEquals(chunk2.id, controller.status.value.currentChunkId)
        assertTrue(analysisRepository.lookaheadCalls.contains(mediaId1 to chunk1.id))
        assertTrue(analysisRepository.lookaheadCalls.contains(mediaId1 to chunk2.id))

        controller.release()
    }

    @Test
    fun `open_音声ファイルが存在しない場合はSOURCE_MISSINGエラーになる`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val item = sampleAudioItem(mediaId1, durationMs = 90_000L)
        libraryRepository.items.value = listOf(item)
        // ファイルを作らない（バックアップ復元直後を模擬）

        val controller = MediaControllerPlaybackController(
            context = context,
            libraryRepository = libraryRepository,
            userSettingsRepository = settingsRepository,
            practiceController = practiceController,
            mediaFileLocator = mediaFileLocator,
            mainDispatcher = dispatcher,
            analysisRepository = analysisRepository,
            rawPlayerFactory = { stubPlayer },
            clock = { nowEpochMs },
        )

        controller.open(mediaId1)
        advanceUntilIdle()

        val status = controller.status.value
        assertEquals(mediaId1, status.mediaId)
        assertEquals(PlaybackError.SOURCE_MISSING, status.error)
        assertFalse(status.isPlaying)

        controller.release()
    }

    @Test
    fun `再生中の5秒ごとの定期保存と一時停止時のレジューム位置保存が動く`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val item = sampleAudioItem(mediaId1, durationMs = 120_000L)
        libraryRepository.items.value = listOf(item)
        mediaFileLocator.audioFileFor(mediaId1, "mp3").writeBytes(byteArrayOf(1, 2, 3))

        val controller = MediaControllerPlaybackController(
            context = context,
            libraryRepository = libraryRepository,
            userSettingsRepository = settingsRepository,
            practiceController = practiceController,
            mediaFileLocator = mediaFileLocator,
            mainDispatcher = dispatcher,
            analysisRepository = analysisRepository,
            rawPlayerFactory = { stubPlayer },
            clock = { nowEpochMs },
        )

        controller.open(mediaId1)
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        val player = controller.player.value
        assertNotNull(player)
        player.play()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertTrue(controller.status.value.isPlaying)

        // 再生位置を 12,000ms に進め、5 秒経過させる
        stubPlayer.setCurrentPosition(12_000L)
        shadowOf(Looper.getMainLooper()).idle()
        nowEpochMs = 6_000L
        advanceTimeBy(5_000L)
        runCurrent()

        val after5s = libraryRepository.observeItemState(mediaId1).first()
        assertNotNull(after5s)
        assertEquals(12_000L, after5s.positionMs)
        assertEquals(6_000L, after5s.updatedAtEpochMs)

        // さらに位置を 14,500ms に進めて一時停止すると即座に保存される
        stubPlayer.setCurrentPosition(14_500L)
        shadowOf(Looper.getMainLooper()).idle()
        nowEpochMs = 8_500L
        player.pause()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        val afterPause = libraryRepository.observeItemState(mediaId1).first()
        assertNotNull(afterPause)
        assertEquals(14_500L, afterPause.positionMs)
        assertEquals(8_500L, afterPause.updatedAtEpochMs)

        controller.release()
    }

    @Test
    fun `prepareForResumption_最も新しく更新された教材の位置と速度で復元する`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val item1 = sampleAudioItem(mediaId1, durationMs = 100_000L, importedAt = 1_000L)
        val item2 = sampleAudioItem(mediaId2, durationMs = 200_000L, importedAt = 2_000L)
        libraryRepository.items.value = listOf(item1, item2)
        libraryRepository.itemStates.value = mapOf(
            mediaId1 to ItemState(mediaId = mediaId1, positionMs = 30_000L, speed = 1.25f, updatedAtEpochMs = 9_000L),
            mediaId2 to ItemState(mediaId = mediaId2, positionMs = 10_000L, speed = 1.0f, updatedAtEpochMs = 4_000L),
        )
        mediaFileLocator.audioFileFor(mediaId1, "mp3").writeBytes(byteArrayOf(1))
        mediaFileLocator.audioFileFor(mediaId2, "mp3").writeBytes(byteArrayOf(2))

        val controller = MediaControllerPlaybackController(
            context = context,
            libraryRepository = libraryRepository,
            userSettingsRepository = settingsRepository,
            practiceController = practiceController,
            mediaFileLocator = mediaFileLocator,
            mainDispatcher = dispatcher,
            analysisRepository = analysisRepository,
            rawPlayerFactory = { stubPlayer },
            clock = { nowEpochMs },
        )

        val resumed = controller.prepareForResumption()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        assertNotNull(resumed)
        assertEquals(1, resumed.mediaItems.size)
        assertEquals(mediaId1.value, resumed.mediaItems.single().mediaId)
        assertEquals(30_000L, resumed.startPositionMs)
        assertEquals(1.25f, controller.status.value.speed)

        controller.release()
    }

    @Test
    fun `openと再生_ファイルを開いた時点で最終アクセス日時が記録され95パーセント以降を1秒以上再生するとListenedになる`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val item = sampleAudioItem(mediaId1, durationMs = 100_000L)
        val savedState = ItemState(
            mediaId = mediaId1,
            positionMs = 40_000L,
            speed = 1.0f,
            isListened = false,
            updatedAtEpochMs = 500L,
        )
        libraryRepository.items.value = listOf(item)
        libraryRepository.itemStates.value = mapOf(mediaId1 to savedState)
        mediaFileLocator.audioFileFor(mediaId1, "mp3").writeBytes(byteArrayOf(1, 2, 3))

        nowEpochMs = 2_000L
        val controller = MediaControllerPlaybackController(
            context = context,
            libraryRepository = libraryRepository,
            userSettingsRepository = settingsRepository,
            practiceController = practiceController,
            mediaFileLocator = mediaFileLocator,
            mainDispatcher = dispatcher,
            analysisRepository = analysisRepository,
            rawPlayerFactory = { stubPlayer },
            clock = { nowEpochMs },
        )

        controller.open(mediaId1)
        runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        // 開いた時点で前回の位置 (40,000ms) が復元され、最終アクセス日時が 2,000L に更新されていること
        assertEquals(40_000L, controller.position.currentPositionMs())
        val stateOnOpen = libraryRepository.observeItemState(mediaId1).first()
        assertNotNull(stateOnOpen)
        assertEquals(40_000L, stateOnOpen.positionMs)
        assertEquals(2_000L, stateOnOpen.updatedAtEpochMs)
        assertFalse(stateOnOpen.isListened)

        val player = controller.player.value
        assertNotNull(player)
        player.play()
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        // 40% の位置で 2 秒再生しても Listened にはならない
        advanceTimeBy(2_000L)
        runCurrent()
        assertFalse(libraryRepository.observeItemState(mediaId1).first()?.isListened ?: true)

        // 96% (96,000ms) の位置へ移動して 800ms 再生した時点ではまだ 1,000ms 未満なので false
        stubPlayer.setCurrentPosition(96_000L)
        shadowOf(Looper.getMainLooper()).idle()
        advanceTimeBy(800L)
        runCurrent()
        assertFalse(libraryRepository.observeItemState(mediaId1).first()?.isListened ?: true)

        // さらに 200ms 再生して合計 1,000ms に達すると Listened (済) が true になり最終アクセス日時も更新される
        nowEpochMs = 7_500L
        advanceTimeBy(200L)
        runCurrent()

        val listenedState = libraryRepository.observeItemState(mediaId1).first()
        assertNotNull(listenedState)
        assertTrue(listenedState.isListened)
        assertEquals(7_500L, listenedState.updatedAtEpochMs)

        controller.release()
    }

    private fun sampleAudioItem(
        id: MediaId,
        durationMs: Long,
        importedAt: Long = 1_000L,
    ): AudioItem = AudioItem(
        id = id,
        title = "Sample ${id.value.takeLast(4)}",
        durationMs = durationMs,
        mimeType = "audio/mpeg",
        sizeBytes = 10_000L,
        contentSha256 = id.value.replace("-", "").padEnd(64, '0'),
        course = null,
        languagePair = LanguagePair(LanguageTag("en"), LanguageTag("ja")),
        transcriptOffsetMs = 0,
        importedAtEpochMs = importedAt,
    )

    private class FakeMedia3Player : SimpleBasePlayer(Looper.getMainLooper()) {
        private var currentItem: MediaItem? = null
        private var posMs: Long = 0L
        private var playWhenReadyFlag: Boolean = false
        private var playbackStateValue: Int = Player.STATE_IDLE
        private var speedValue: Float = 1.0f
        var prepared: Boolean = false

        fun setCurrentPosition(positionMs: Long) {
            posMs = positionMs
            invalidateState()
        }

        override fun getState(): State {
            val commands = Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_PREPARE,
                    Player.COMMAND_STOP,
                    Player.COMMAND_SET_SPEED_AND_PITCH,
                    Player.COMMAND_SET_MEDIA_ITEM,
                    Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                )
                .build()
            val playlist = currentItem?.let { item ->
                listOf(
                    MediaItemData.Builder(item.mediaId.ifEmpty { "item" })
                        .setMediaItem(item)
                        .setDurationUs(Util.msToUs(120_000L))
                        .build(),
                )
            }.orEmpty()
            return State.Builder()
                .setAvailableCommands(commands)
                .setPlaylist(playlist)
                .setCurrentMediaItemIndex(if (playlist.isEmpty()) C.INDEX_UNSET else 0)
                .setPlayWhenReady(playWhenReadyFlag, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(playbackStateValue)
                .setPlaybackParameters(PlaybackParameters(speedValue))
                .setContentPositionMs { posMs }
                .build()
        }

        override fun handleSetMediaItems(
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<*> {
            currentItem = mediaItems.firstOrNull()
            posMs = if (startPositionMs == C.TIME_UNSET) 0L else startPositionMs.coerceAtLeast(0L)
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        override fun handlePrepare(): ListenableFuture<*> {
            prepared = true
            playbackStateValue = Player.STATE_READY
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            playWhenReadyFlag = playWhenReady
            if (playWhenReady && playbackStateValue == Player.STATE_IDLE) {
                playbackStateValue = Player.STATE_READY
            }
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        override fun handleStop(): ListenableFuture<*> {
            playWhenReadyFlag = false
            playbackStateValue = Player.STATE_IDLE
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
            speedValue = playbackParameters.speed
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> {
            if (positionMs != C.TIME_UNSET) {
                posMs = positionMs.coerceAtLeast(0L)
                invalidateState()
            }
            return Futures.immediateVoidFuture()
        }
    }

    private class InMemoryLibraryRepository : LibraryRepository {
        val items = MutableStateFlow<List<AudioItem>>(emptyList())
        val itemStates = MutableStateFlow<Map<MediaId, ItemState>>(emptyMap())

        override fun observeItems(): Flow<List<AudioItem>> = items
        override fun observeItem(id: MediaId): Flow<AudioItem?> = items.map { list -> list.firstOrNull { it.id == id } }
        override fun observeItemStates(): Flow<Map<MediaId, ItemState>> = itemStates
        override fun observeItemState(id: MediaId): Flow<ItemState?> = itemStates.map { it[id] }

        override suspend fun upsertItemState(state: ItemState) {
            itemStates.update { it + (state.mediaId to state) }
        }

        override suspend fun updatePlaybackPosition(
            id: MediaId,
            positionMs: Long,
            speed: Float,
            updatedAtEpochMs: Long,
        ) {
            itemStates.update { current ->
                val existing = current[id]
                val updated = existing?.copy(
                    positionMs = positionMs,
                    speed = speed,
                    updatedAtEpochMs = updatedAtEpochMs,
                ) ?: ItemState(
                    mediaId = id,
                    positionMs = positionMs,
                    speed = speed,
                    updatedAtEpochMs = updatedAtEpochMs,
                )
                current + (id to updated)
            }
        }

        override suspend fun markItemListened(
            id: MediaId,
            isListened: Boolean,
            updatedAtEpochMs: Long,
        ) {
            itemStates.update { current ->
                val existing = current[id]
                val updated = existing?.copy(
                    isListened = isListened,
                    updatedAtEpochMs = updatedAtEpochMs,
                ) ?: ItemState(
                    mediaId = id,
                    isListened = isListened,
                    updatedAtEpochMs = updatedAtEpochMs,
                )
                current + (id to updated)
            }
        }

        override fun import(uris: List<Uri>): Flow<ImportProgress> = flowOf()
        override suspend fun importTranscript(uri: Uri): TranscriptImportResult = TranscriptImportResult.Invalid(TranscriptError.UNKNOWN_FORMAT)
        override fun syncFromDrive(): Flow<SyncProgress> = flowOf()
        override suspend fun delete(id: MediaId) {}
    }

    private class InMemoryAnalysisRepository : AnalysisRepository {
        val chunksByMedia = MutableStateFlow<Map<MediaId, List<Chunk>>>(emptyMap())
        val transcriptsByMedia = MutableStateFlow<Map<MediaId, Map<ChunkId, Transcript>>>(emptyMap())
        val lookaheadCalls = mutableListOf<Pair<MediaId, ChunkId?>>()

        override fun observeStatus(id: MediaId): Flow<Map<AnalysisStage, StageStatus>> = flowOf(emptyMap())
        override fun observeWaveform(id: MediaId): Flow<Waveform?> = flowOf(null)
        override fun observeChunks(id: MediaId): Flow<List<Chunk>> = chunksByMedia.map { it[id].orEmpty() }
        override fun observeTranscripts(id: MediaId): Flow<Map<ChunkId, Transcript>> = transcriptsByMedia.map { it[id].orEmpty() }
        override suspend fun requestAnalysis(id: MediaId, stages: Set<AnalysisStage>) {}
        override suspend fun resegment(id: MediaId, params: SegmentationParams) {}
        override suspend fun setChunkSource(id: MediaId, source: ChunkOrigin) {}
        override suspend fun setTranscriptOffset(id: MediaId, offsetMs: Int) {}
        override suspend fun triggerLookaheadAt(id: MediaId, currentChunkId: ChunkId?) {
            lookaheadCalls += id to currentChunkId
        }
    }

    private class InMemoryUserSettingsRepository : UserSettingsRepository {
        val state = MutableStateFlow(UserSettings())
        override val settings: Flow<UserSettings> = state
        override suspend fun setDefaultLanguagePair(pair: LanguagePair) {}
        override suspend fun setChineseScript(script: ChineseScript) {}
        override suspend fun setCantoneseScript(script: ChineseScript) {}
        override suspend fun setDefaultSpeed(speed: Float) {}
        override suspend fun setSeekBackMs(seekBackMs: Long) {}
        override suspend fun setSeekForwardMs(seekForwardMs: Long) {}
    }
}
