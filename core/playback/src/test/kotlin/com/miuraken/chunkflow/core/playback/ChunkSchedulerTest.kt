package com.miuraken.chunkflow.core.playback

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.Bookmark
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguageFilter
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LoopMode
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.Transcript
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ChunkSchedulerTest {

    private lateinit var context: Context
    private lateinit var fakeClock: FakeClock
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var practiceController: PracticeController
    private lateinit var powerManager: PowerManager
    private lateinit var scheduler: ChunkScheduler
    private var fakeElapsedRealtimeMs: Long = 10_000L

    private val chunk1 = Chunk(
        id = ChunkId(1L),
        startMs = 0L,
        endMs = 2_000L,
        origin = ChunkOrigin.IMPORTED,
    )
    private val chunk2 = Chunk(
        id = ChunkId(2L),
        startMs = 2_000L,
        endMs = 4_500L,
        origin = ChunkOrigin.IMPORTED,
    )
    private val chunk3 = Chunk(
        id = ChunkId(3L),
        startMs = 4_500L,
        endMs = 7_000L,
        origin = ChunkOrigin.IMPORTED,
    )
    private val sampleChunks = listOf(chunk1, chunk2, chunk3)

    @BeforeTest
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        fakeClock = FakeClock(false)
        exoPlayer = TestExoPlayerBuilder(context)
            .setClock(fakeClock)
            .build()
        practiceController = PracticeController()
        powerManager = context.getSystemService(PowerManager::class.java)

        val windowDefinition = FakeTimeline.TimelineWindowDefinition(
            1,
            "window-1",
            true,
            false,
            Util.msToUs(30_000L),
        )
        val mediaSource = FakeMediaSource(FakeTimeline(windowDefinition))
        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
        runUntil { exoPlayer.playbackState == Player.STATE_READY }

        scheduler = ChunkScheduler(
            exoPlayer = exoPlayer,
            practiceController = practiceController,
            powerManager = powerManager,
            mainLooper = Looper.getMainLooper(),
            elapsedRealtimeMs = { fakeElapsedRealtimeMs },
        )
    }

    @AfterTest
    fun tearDown() {
        scheduler.release()
        exoPlayer.release()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `AbRange_チャンクが空でもB点到達でA点へシークしメッセージを使い回す`() {
        var seekDiscontinuities = 0
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs == 1_000L) {
                        seekDiscontinuities += 1
                    }
                }
            },
        )

        practiceController.update {
            it.copy(loop = LoopMode.AbRange(startMs = 1_000L, endMs = 2_500L))
        }
        exoPlayer.seekTo(1_000L)
        pumpLoopers()
        seekDiscontinuities = 0

        // 1回目のループ発火 (2_500ms -> 1_000ms)
        exoPlayer.play()
        runUntil { seekDiscontinuities >= 1 }
        pumpLoopers()
        assertEquals(1, seekDiscontinuities)
        assertTrue(exoPlayer.currentPosition in 1_000L..1_500L)

        // 2回目のループ発火（setDeleteAfterDelivery(false) により再設定なしで発火する）
        runUntil { seekDiscontinuities >= 2 }
        pumpLoopers()
        assertEquals(2, seekDiscontinuities)
        assertEquals(0, scheduler.pendingSelfSeekCount)
    }

    @Test
    fun `SingleChunk_指定回数だけチャンク頭へ戻り回数到達後はLoopModeOffになり次へ進む`() {
        scheduler.setChunks(sampleChunks)
        practiceController.update {
            it.copy(loop = LoopMode.SingleChunk(chunkId = chunk1.id, times = 2))
        }

        // times = 2 は合計 2 回再生（1回目終端で先頭へシーク、2回目終端で LoopMode.Off に戻して次チャンクへ進行）
        exoPlayer.play()
        runUntil { scheduler.completedLoopCount == 1 }
        pumpLoopers()

        assertEquals(1, scheduler.completedLoopCount)
        assertEquals(LoopMode.SingleChunk(chunkId = chunk1.id, times = 2), practiceController.settings.value.loop)
        assertEquals(chunk1.id, scheduler.currentChunkId.value)

        // 2回目の終端を通過して chunk2 の領域 (2_200ms) まで再生が進むこと
        runUntil { exoPlayer.currentPosition >= 2_200L }
        pumpLoopers()

        assertEquals(LoopMode.Off, practiceController.settings.value.loop)
        assertEquals(0, scheduler.completedLoopCount)
        assertEquals(chunk2.id, scheduler.currentChunkId.value)
    }

    @Test
    fun `SingleChunk_自身のシークではcompletedLoopCountがリセットされずユーザシークでリセットされる`() {
        scheduler.setChunks(sampleChunks)
        practiceController.update {
            it.copy(loop = LoopMode.SingleChunk(chunkId = chunk1.id, times = 3))
        }

        exoPlayer.play()
        // 1回目のループシーク（ChunkScheduler 自身のシーク）
        runUntil { scheduler.completedLoopCount == 1 }
        pumpLoopers()
        assertEquals(1, scheduler.completedLoopCount)
        assertEquals(0, scheduler.pendingSelfSeekCount)

        // ユーザによる手動シーク -> completedLoopCount が 0 にリセットされる（ADR 0007）
        exoPlayer.seekTo(500L)
        pumpLoopers()
        assertEquals(0, scheduler.completedLoopCount)
    }

    @Test
    fun `ChunkRange_lastChunkId終端でfirstChunkId先頭へ戻り回数到達で解除される`() {
        scheduler.setChunks(sampleChunks)
        practiceController.update {
            it.copy(
                loop = LoopMode.ChunkRange(
                    firstChunkId = chunk1.id,
                    lastChunkId = chunk2.id,
                    times = 2,
                ),
            )
        }

        exoPlayer.play()
        // chunk1 (0..2000) の終端ではシークせず chunk2 (2000..4500) の終端で chunk1.startMs (0) へ戻る
        runUntil { scheduler.completedLoopCount == 1 }
        pumpLoopers()

        assertEquals(1, scheduler.completedLoopCount)
        assertTrue(exoPlayer.currentPosition < 500L)
        assertEquals(chunk1.id, scheduler.currentChunkId.value)

        // 2周目の終端 (4500ms) を通過すると LoopMode.Off に戻り chunk3 (4500..7000) へ進む
        runUntil { exoPlayer.currentPosition >= 4_800L }
        pumpLoopers()

        assertEquals(LoopMode.Off, practiceController.settings.value.loop)
        assertEquals(chunk3.id, scheduler.currentChunkId.value)
    }

    @Test
    fun `AutoPause_Proportionalで再生速度を加味したポーズ時間とWakeLockを設定しタイマー満了で再開する`() {
        scheduler.setChunks(sampleChunks)
        // chunk1.durationMs = 2_000ms, speed = 2.0f -> 実時間 1_000ms * factor 1.5 = 1_500ms (>= minMs 500ms)
        exoPlayer.playbackParameters = PlaybackParameters(2.0f)
        practiceController.update {
            it.copy(
                autoPause = AutoPause.Proportional(factor = 1.5f, minMs = 500L),
                autoPauseScope = AutoPauseScope.ALL_CHUNKS,
            )
        }

        fakeElapsedRealtimeMs = 50_000L
        exoPlayer.play()
        runUntil { scheduler.autoPauseEndsAtMs.value != null }
        pumpLoopers()

        assertFalse(exoPlayer.playWhenReady)
        assertEquals(51_500L, scheduler.autoPauseEndsAtMs.value)
        assertTrue(scheduler.isWakeLockHeld)

        // ポーズ時間 (1_500ms) 経過で自動再開し、WakeLock と autoPauseEndsAtMs が解放される
        shadowOf(Looper.getMainLooper()).idleFor(1_500L, TimeUnit.MILLISECONDS)
        pumpLoopers()

        assertTrue(exoPlayer.playWhenReady)
        assertNull(scheduler.autoPauseEndsAtMs.value)
        assertFalse(scheduler.isWakeLockHeld)
    }

    @Test
    fun `AutoPauseとSingleChunk併用時はチャンク終端でポーズした後に先頭へシークして再開する`() {
        scheduler.setChunks(sampleChunks)
        practiceController.update {
            it.copy(
                loop = LoopMode.SingleChunk(chunkId = chunk2.id, times = 2),
                autoPause = AutoPause.Fixed(pauseMs = 1_000L),
                autoPauseScope = AutoPauseScope.ALL_CHUNKS,
            )
        }

        // chunk2 の先頭 (2_000ms) から開始
        exoPlayer.seekTo(chunk2.startMs)
        pumpLoopers()
        exoPlayer.play()

        // chunk2 終端 (4_500ms) でまず AutoPause がかかる（まだシークしていない）
        runUntil { scheduler.autoPauseEndsAtMs.value != null }
        pumpLoopers()
        assertFalse(exoPlayer.playWhenReady)
        assertTrue(exoPlayer.currentPosition >= chunk2.endMs)

        // 1_000ms 経過で chunk2 先頭 (2_000ms) にシークして再生再開する
        shadowOf(Looper.getMainLooper()).idleFor(1_000L, TimeUnit.MILLISECONDS)
        pumpLoopers()

        assertTrue(exoPlayer.playWhenReady)
        assertEquals(chunk2.id, scheduler.currentChunkId.value)
        assertTrue(exoPlayer.currentPosition in chunk2.startMs..(chunk2.startMs + 300L))
    }

    @Test
    fun `AutoPause_ポーズ中にユーザが手動で再生またはシークするとタイマーとWakeLockが即座に解除される`() {
        scheduler.setChunks(sampleChunks)
        practiceController.update {
            it.copy(
                autoPause = AutoPause.Fixed(pauseMs = 2_000L),
                autoPauseScope = AutoPauseScope.ALL_CHUNKS,
            )
        }

        exoPlayer.play()
        runUntil { scheduler.autoPauseEndsAtMs.value != null }
        pumpLoopers()

        assertNotNull(scheduler.autoPauseEndsAtMs.value)
        assertTrue(scheduler.isWakeLockHeld)

        // ユーザが手動でシーク
        exoPlayer.seekTo(2_500L)
        pumpLoopers()

        assertNull(scheduler.autoPauseEndsAtMs.value)
        assertFalse(scheduler.isWakeLockHeld)
        assertEquals(chunk2.id, scheduler.currentChunkId.value)
    }

    @Test
    fun `AutoPauseScope_AFTER_BASE_LANGUAGEでは母語チャンク終端のみで一時停止する`() {
        scheduler.setChunks(sampleChunks)
        scheduler.setLanguagePair(LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja")))
        scheduler.setTranscripts(
            mapOf(
                chunk1.id to sampleTranscript(chunk1.id, "Hello", "en"),
                chunk2.id to sampleTranscript(chunk2.id, "こんにちは", "ja"),
                chunk3.id to sampleTranscript(chunk3.id, "World", "en"),
            ),
        )
        practiceController.update {
            it.copy(
                autoPause = AutoPause.Fixed(pauseMs = 1_200L),
                autoPauseScope = AutoPauseScope.AFTER_BASE_LANGUAGE,
            )
        }

        // chunk1 (en) の終端 2_000ms では止まらず、chunk2 (ja) の終端 4_500ms で一時停止する
        exoPlayer.play()
        runUntil { scheduler.autoPauseEndsAtMs.value != null }
        pumpLoopers()

        assertEquals(chunk2.id, scheduler.currentChunkId.value)
        assertTrue(exoPlayer.currentPosition >= 4_500L)
        assertNotNull(scheduler.autoPauseEndsAtMs.value)
    }

    @Test
    fun `AutoPauseScope_AFTER_TARGET_LANGUAGEでは学習言語チャンク終端のみで一時停止する`() {
        scheduler.setChunks(sampleChunks)
        scheduler.setLanguagePair(LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja")))
        scheduler.setTranscripts(
            mapOf(
                chunk1.id to sampleTranscript(chunk1.id, "こんにちは", "ja"),
                chunk2.id to sampleTranscript(chunk2.id, "Hello", "en"),
                chunk3.id to sampleTranscript(chunk3.id, "さようなら", "ja"),
            ),
        )
        practiceController.update {
            it.copy(
                autoPause = AutoPause.Fixed(pauseMs = 1_200L),
                autoPauseScope = AutoPauseScope.AFTER_TARGET_LANGUAGE,
            )
        }

        // chunk1 (ja) の終端 2_000ms では止まらず、chunk2 (en) の終端 4_500ms で一時停止する
        exoPlayer.play()
        runUntil { scheduler.autoPauseEndsAtMs.value != null }
        pumpLoopers()

        assertEquals(chunk2.id, scheduler.currentChunkId.value)
        assertTrue(exoPlayer.currentPosition >= 4_500L)
        assertNotNull(scheduler.autoPauseEndsAtMs.value)
    }

    @Test
    fun `LanguageFilter_TARGET_ONLYでは母語チャンクを飛ばして次の学習言語チャンクへシークする`() {
        scheduler.setChunks(sampleChunks)
        scheduler.setLanguagePair(LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja")))
        scheduler.setTranscripts(
            mapOf(
                chunk1.id to sampleTranscript(chunk1.id, "First", "en"),
                chunk2.id to sampleTranscript(chunk2.id, "訳", "ja"),
                chunk3.id to sampleTranscript(chunk3.id, "Third", "en"),
            ),
        )
        practiceController.update {
            it.copy(languageFilter = LanguageFilter.TARGET_ONLY)
        }

        // chunk1 (0..2000, en) 終端に到達すると chunk2 (2000..4500, ja) をスキップし chunk3 (4500..7000, en) へシークする
        exoPlayer.play()
        runUntil { scheduler.currentChunkId.value == chunk3.id }
        pumpLoopers()

        assertEquals(chunk3.id, scheduler.currentChunkId.value)
        assertTrue(exoPlayer.currentPosition >= chunk3.startMs)
    }

    @Test
    fun `BookmarksOnly_ブックマークされたチャンクだけを順次再生し終端から先頭のブックマークチャンクへ戻る`() {
        val mediaId = MediaId("media-bm")
        scheduler.setChunks(sampleChunks)
        scheduler.setBookmarks(
            listOf(
                Bookmark(id = 1L, mediaId = mediaId, positionMs = 500L),
                Bookmark(id = 2L, mediaId = mediaId, positionMs = 4_500L),
            ),
        )
        practiceController.update {
            it.copy(bookmarksOnly = true)
        }

        // chunk1 (0..2000, ブックマーク有) 終端で chunk2 を飛ばして chunk3 (4500..7000, ブックマーク有) へシークする
        exoPlayer.play()
        runUntil { scheduler.currentChunkId.value == chunk3.id }
        pumpLoopers()
        assertEquals(chunk3.id, scheduler.currentChunkId.value)
        assertTrue(exoPlayer.currentPosition >= chunk3.startMs)

        // 最後のブックマークチャンク chunk3 の終端 (7000ms) に達すると先頭のブックマークチャンク chunk1 (0ms) へ戻る
        runUntil { scheduler.currentChunkId.value == chunk1.id && exoPlayer.currentPosition < 1_000L }
        pumpLoopers()
        assertEquals(chunk1.id, scheduler.currentChunkId.value)
        assertTrue(exoPlayer.currentPosition < 1_000L)
    }

    @Test
    fun `BookmarksOnly_LanguageFilterとAutoPause併用時はポーズ後に条件を満たすブックマークチャンクへシークしループする`() {
        val mediaId = MediaId("media-bm-combo")
        scheduler.setChunks(sampleChunks)
        scheduler.setLanguagePair(LanguagePair(target = LanguageTag("en"), base = LanguageTag("ja")))
        scheduler.setTranscripts(
            mapOf(
                chunk1.id to sampleTranscript(chunk1.id, "First", "en"),
                chunk2.id to sampleTranscript(chunk2.id, "二番目", "ja"),
                chunk3.id to sampleTranscript(chunk3.id, "Third", "en"),
            ),
        )
        // 全チャンクにブックマークがあるが TARGET_ONLY (en) のため chunk1 と chunk3 のみが対象になる
        scheduler.setBookmarks(
            listOf(
                Bookmark(id = 1L, mediaId = mediaId, positionMs = 100L),
                Bookmark(id = 2L, mediaId = mediaId, positionMs = 2_500L),
                Bookmark(id = 3L, mediaId = mediaId, positionMs = 5_000L),
            ),
        )
        practiceController.update {
            it.copy(
                bookmarksOnly = true,
                languageFilter = LanguageFilter.TARGET_ONLY,
                autoPause = AutoPause.Fixed(pauseMs = 1_000L),
                autoPauseScope = AutoPauseScope.ALL_CHUNKS,
            )
        }

        exoPlayer.play()
        // chunk1 終端でまず自動ポーズに入る
        runUntil { scheduler.autoPauseEndsAtMs.value != null }
        pumpLoopers()
        assertFalse(exoPlayer.playWhenReady)

        // 1_000ms 経過で chunk2 (ja) を飛ばして chunk3 (en) へシークして再開する
        shadowOf(Looper.getMainLooper()).idleFor(1_000L, TimeUnit.MILLISECONDS)
        pumpLoopers()
        assertTrue(exoPlayer.playWhenReady)
        assertEquals(chunk3.id, scheduler.currentChunkId.value)

        // chunk3 終端でも自動ポーズに入り、解除後に先頭の chunk1 へシークしてループする
        runUntil { scheduler.autoPauseEndsAtMs.value != null }
        pumpLoopers()
        shadowOf(Looper.getMainLooper()).idleFor(1_000L, TimeUnit.MILLISECONDS)
        pumpLoopers()
        assertTrue(exoPlayer.playWhenReady)
        assertEquals(chunk1.id, scheduler.currentChunkId.value)
        assertTrue(exoPlayer.currentPosition < 500L)
    }

    @Test
    fun `RepeatChunkIds_複数チャンクを選択すると選択チャンクだけを時系列の順に再生しリピートONで繰り返しOFFで1周して止まる`() {
        val chunk4 = Chunk(
            id = ChunkId(4L),
            startMs = 7_000L,
            endMs = 9_000L,
            origin = ChunkOrigin.IMPORTED,
        )
        scheduler.setChunks(listOf(chunk1, chunk2, chunk3, chunk4))

        // 停止中に chunk2 と chunk4 を選択し、リピートを ON にしても、再生位置（0ms、chunk1）はその場で動かさない
        practiceController.update {
            it.copy(
                repeatChunkIds = setOf(chunk2.id, chunk4.id),
                repeatCheckedOnly = true,
            )
        }
        pumpLoopers()
        assertTrue(exoPlayer.currentPosition < chunk2.startMs)

        // 再生を始めると、最初の選択チャンク chunk2 へ移る
        exoPlayer.play()
        pumpLoopers()
        assertEquals(chunk2.id, scheduler.currentChunkId.value)
        assertTrue(exoPlayer.currentPosition in chunk2.startMs..(chunk2.startMs + 200L))

        // chunk2 (2000..4500) 終端で chunk3 を飛ばして chunk4 (7000..9000) へシークする
        runUntil { scheduler.currentChunkId.value == chunk4.id }
        pumpLoopers()
        assertEquals(chunk4.id, scheduler.currentChunkId.value)
        assertTrue(exoPlayer.currentPosition >= chunk4.startMs)

        // 最後の選択チャンク chunk4 (7000..9000) 終端に達すると先頭の選択チャンク chunk2 (2000..4500) へ戻る
        runUntil { scheduler.currentChunkId.value == chunk2.id && exoPlayer.currentPosition < 3_000L }
        pumpLoopers()
        assertEquals(chunk2.id, scheduler.currentChunkId.value)

        // 2周目も chunk2 -> chunk4 へ進む
        runUntil { scheduler.currentChunkId.value == chunk4.id }
        pumpLoopers()
        assertEquals(chunk4.id, scheduler.currentChunkId.value)

        // リピートを OFF にすると、選択チャンクを 1 周だけ再生し、最後の選択チャンク chunk4 の終端で一時停止する
        exoPlayer.seekTo(chunk2.startMs)
        pumpLoopers()
        practiceController.update {
            it.copy(repeatCheckedOnly = false)
        }
        pumpLoopers()
        runUntil { scheduler.currentChunkId.value == chunk4.id }
        pumpLoopers()
        runUntil { !exoPlayer.playWhenReady }
        pumpLoopers()
        assertFalse(exoPlayer.playWhenReady)
        assertEquals(chunk4.id, scheduler.currentChunkId.value)
        assertTrue(exoPlayer.currentPosition in (chunk4.endMs - 200L)..(chunk4.endMs + 200L))

        // 選択をすべて外すと通常再生に戻り、chunk2 終端で chunk3 へ進む
        practiceController.update {
            it.copy(repeatChunkIds = emptySet())
        }
        pumpLoopers()
        exoPlayer.seekTo(chunk2.startMs)
        pumpLoopers()
        exoPlayer.play()
        runUntil { scheduler.currentChunkId.value == chunk3.id }
        pumpLoopers()
        assertEquals(chunk3.id, scheduler.currentChunkId.value)
    }

    @Test
    fun `RepeatChunkIds_1つのチャンクだけチェックした場合はそのチャンクを無限ループ再生する`() {
        scheduler.setChunks(sampleChunks)
        practiceController.update {
            it.copy(
                repeatChunkIds = setOf(chunk1.id),
                repeatCheckedOnly = true,
            )
        }
        pumpLoopers()

        var seekToStartCount = 0
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs == chunk1.startMs) {
                        seekToStartCount += 1
                    }
                }
            },
        )

        exoPlayer.play()
        // 3回以上ループしても解除されず無限リピートを継続すること
        runUntil { seekToStartCount >= 3 }
        pumpLoopers()
        assertTrue(seekToStartCount >= 3)
        assertEquals(chunk1.id, scheduler.currentChunkId.value)
        assertEquals(setOf(chunk1.id), practiceController.settings.value.repeatChunkIds)
    }

    @Test
    fun `startSingleChunkPlayback_リピートマークなしの場合は対象チャンクを1回だけ再生して終端で自動停止する`() {
        scheduler.setChunks(sampleChunks)
        // AutoPause が ON でも再開せずそのまま終端で停止することを確認する
        practiceController.update {
            it.copy(
                autoPause = AutoPause.Fixed(pauseMs = 1_000L),
                autoPauseScope = AutoPauseScope.ALL_CHUNKS,
            )
        }
        pumpLoopers()

        assertFalse(exoPlayer.playWhenReady)
        scheduler.startSingleChunkPlayback(chunk2.id)
        pumpLoopers()

        assertTrue(exoPlayer.playWhenReady)
        assertTrue(scheduler.isSingleChunkPlaybackActive)
        assertEquals(chunk2.id, scheduler.currentChunkId.value)
        assertTrue(exoPlayer.currentPosition in chunk2.startMs..(chunk2.startMs + 200L))

        // chunk2 終端 (4_500ms) に達すると 1 回だけで自動停止し、AutoPause タイマーも予約されない
        runUntil { !exoPlayer.playWhenReady }
        pumpLoopers()

        assertFalse(exoPlayer.playWhenReady)
        assertFalse(scheduler.isSingleChunkPlaybackActive)
        assertNull(scheduler.autoPauseEndsAtMs.value)
        assertTrue(exoPlayer.currentPosition in chunk2.endMs..(chunk2.endMs + 200L))
    }

    @Test
    fun `startSingleChunkPlayback_選択中でもリピートがOFFなら対象チャンクを1回だけ再生して終端で停止する`() {
        scheduler.setChunks(sampleChunks)
        practiceController.update {
            it.copy(repeatChunkIds = setOf(chunk1.id, chunk2.id), repeatCheckedOnly = false)
        }
        pumpLoopers()

        scheduler.startSingleChunkPlayback(chunk2.id)
        pumpLoopers()
        assertTrue(exoPlayer.playWhenReady)
        assertEquals(chunk2.id, scheduler.currentChunkId.value)

        runUntil { !exoPlayer.playWhenReady }
        pumpLoopers()
        assertFalse(exoPlayer.playWhenReady)
        assertFalse(scheduler.isSingleChunkPlaybackActive)
        assertTrue(exoPlayer.currentPosition in chunk2.endMs..(chunk2.endMs + 200L))
    }

    @Test
    fun `startSingleChunkPlayback_リピートONで対象チャンクが選択されている場合は他チャンクへ移らずそのチャンクのみを繰り返し解除で停止する`() {
        scheduler.setChunks(sampleChunks)
        practiceController.update {
            it.copy(repeatChunkIds = setOf(chunk1.id, chunk2.id), repeatCheckedOnly = true)
        }
        pumpLoopers()

        var seekToChunk2StartCount = 0
        exoPlayer.addListener(
            object : Player.Listener {
                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs == chunk2.startMs) {
                        seekToChunk2StartCount += 1
                    }
                }
            },
        )

        // 停止中に chunk2 をクリックして単一チャンク再生を開始
        scheduler.startSingleChunkPlayback(chunk2.id)
        pumpLoopers()
        seekToChunk2StartCount = 0

        assertTrue(exoPlayer.playWhenReady)
        assertTrue(scheduler.isSingleChunkPlaybackActive)
        assertEquals(chunk2.id, scheduler.currentChunkId.value)

        // chunk1 にもリピートマークがついているが chunk1 へは移動せず、chunk2 のみを 2 回以上リピートする
        runUntil { seekToChunk2StartCount >= 2 }
        pumpLoopers()
        assertTrue(exoPlayer.playWhenReady)
        assertEquals(chunk2.id, scheduler.currentChunkId.value)
        assertTrue(scheduler.isSingleChunkPlaybackActive)

        // 再生途中に chunk2 のリピートマークを外すと、その周の終端で自動停止する
        practiceController.update {
            it.copy(repeatChunkIds = setOf(chunk1.id))
        }
        pumpLoopers()
        assertTrue(scheduler.isSingleChunkPlaybackActive)

        runUntil { !exoPlayer.playWhenReady }
        pumpLoopers()
        assertFalse(exoPlayer.playWhenReady)
        assertFalse(scheduler.isSingleChunkPlaybackActive)
    }

    @Test
    fun `LanguageFilterとAutoPauseScope_サブタグ付き言語コードzh_cnと主言語コードzhの互換比較が機能し異なる地域サブタグzh_twは区別される`() {
        assertTrue(ChunkScheduler.isSameOrCompatibleLanguage(LanguageTag("zh-cn"), LanguageTag("zh")))
        assertTrue(ChunkScheduler.isSameOrCompatibleLanguage(LanguageTag("zh"), LanguageTag("zh-TW")))
        assertTrue(ChunkScheduler.isSameOrCompatibleLanguage(LanguageTag("zh-CN"), LanguageTag("zh-cn")))
        assertTrue(ChunkScheduler.isSameOrCompatibleLanguage(LanguageTag("ja-JP"), LanguageTag("ja")))
        assertFalse(ChunkScheduler.isSameOrCompatibleLanguage(LanguageTag("zh-cn"), LanguageTag("zh-tw")))
        assertFalse(ChunkScheduler.isSameOrCompatibleLanguage(LanguageTag("zh-cn"), LanguageTag("ja")))

        scheduler.setChunks(sampleChunks)
        // 教材の言語ペアが target = zh-cn, base = ja-JP で、ASR チャンクの言語が zh / ja のケース
        scheduler.setLanguagePair(LanguagePair(target = LanguageTag("zh-cn"), base = LanguageTag("ja-JP")))
        scheduler.setTranscripts(
            mapOf(
                chunk1.id to sampleTranscript(chunk1.id, "你好", "zh"),
                chunk2.id to sampleTranscript(chunk2.id, "こんにちは", "ja"),
                chunk3.id to sampleTranscript(chunk3.id, "谢谢", "zh-CN"),
            ),
        )
        practiceController.update {
            it.copy(languageFilter = LanguageFilter.TARGET_ONLY)
        }

        // chunk1 (zh == zh-cn 互換) の終端で chunk2 (ja) をスキップし chunk3 (zh-CN) へシークする
        exoPlayer.play()
        runUntil { scheduler.currentChunkId.value == chunk3.id }
        pumpLoopers()

        assertEquals(chunk3.id, scheduler.currentChunkId.value)
        assertTrue(exoPlayer.currentPosition >= chunk3.startMs)
    }

    private fun sampleTranscript(
        chunkId: ChunkId,
        text: String,
        lang: String,
    ): Transcript = Transcript(
        chunkId = chunkId,
        text = text,
        language = LanguageTag(lang),
        languageConfidence = 0.99f,
        engine = EngineId("qwen3-asr-1.7b"),
    )

    private fun pumpLoopers() {
        shadowOf(exoPlayer.playbackLooper).idle()
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(exoPlayer.playbackLooper).idle()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun runUntil(
        maxSteps: Int = 500,
        stepMs: Long = 50L,
        predicate: () -> Boolean,
    ) {
        repeat(maxSteps) {
            pumpLoopers()
            if (predicate()) return
            fakeClock.advanceTime(stepMs)
            pumpLoopers()
            if (predicate()) return
        }
        fail("Condition was not met within ${maxSteps * stepMs}ms (currentPosition=${exoPlayer.currentPosition})")
    }
}
