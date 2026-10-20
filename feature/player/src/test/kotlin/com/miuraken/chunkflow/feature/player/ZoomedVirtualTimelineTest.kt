package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.AutoPauseScope
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ZoomedVirtualTimelineTest {

    private val languagePair = LanguagePair(
        target = LanguageTag("ko"),
        base = LanguageTag("ja"),
    )

    private val sampleChunks = listOf(
        ChunkUi(
            id = ChunkId(1),
            startMs = 1_000L,
            endMs = 3_000L,
            text = "こんにちは",
            language = LanguageTag("ja"),
        ),
        ChunkUi(
            id = ChunkId(2),
            startMs = 5_000L,
            endMs = 9_000L,
            text = "안녕하세요",
            language = LanguageTag("ko"),
        ),
        ChunkUi(
            id = ChunkId(3),
            startMs = 10_000L,
            endMs = 12_000L,
            text = null,
            language = null,
        ),
    )

    @Test
    fun `skipOriginalSilenceがfalseかつAutoPauseがOffのとき_isIdentityがtrueとなり実メディア時刻と仮想時刻が一致する`() {
        val timeline = buildZoomedVirtualTimeline(
            durationMs = 15_000L,
            chunks = sampleChunks,
            skipOriginalSilence = false,
            autoPause = AutoPause.Off,
        )

        assertTrue(timeline.isIdentity)
        assertEquals(15_000L, timeline.totalVirtualDurationMs)
        assertEquals(1_000L..3_000L, timeline.chunkVirtualRange(0))
        assertEquals(5_000L..9_000L, timeline.chunkVirtualRange(1))
        assertEquals(10_000L..12_000L, timeline.chunkVirtualRange(2))

        for (ms in listOf(0L, 500L, 1_000L, 2_500L, 4_000L, 7_000L, 12_000L, 15_000L)) {
            assertEquals(ms, timeline.mediaToVirtualMs(ms))
            assertEquals(ms, timeline.virtualToMediaMs(ms))
        }
    }

    @Test
    fun `skipOriginalSilenceがtrueのとき_チャンク外の無音区間が仮想タイムライン上で0msに詰められる`() {
        val timeline = buildZoomedVirtualTimeline(
            durationMs = 15_000L,
            chunks = sampleChunks,
            skipOriginalSilence = true,
            autoPause = AutoPause.Off,
        )

        assertFalse(timeline.isIdentity)
        // チャンク長: (3000-1000) + (9000-5000) + (12000-10000) = 2000 + 4000 + 2000 = 8000ms
        assertEquals(8_000L, timeline.totalVirtualDurationMs)
        assertTrue(timeline.segments.none { it is ZoomedTimelineSegment.SilenceGap })
        assertEquals(0L..2_000L, timeline.chunkVirtualRange(0))
        assertEquals(2_000L..6_000L, timeline.chunkVirtualRange(1))
        assertEquals(6_000L..8_000L, timeline.chunkVirtualRange(2))

        // 先頭の無音区間 (0..1000) は仮想時刻 0 に折りたたまれる
        assertEquals(0L, timeline.mediaToVirtualMs(0L))
        assertEquals(0L, timeline.mediaToVirtualMs(500L))
        assertEquals(0L, timeline.mediaToVirtualMs(1_000L))

        // チャンク 0 内部 (1000..3000 -> 0..2000)
        assertEquals(1_200L, timeline.mediaToVirtualMs(2_200L))
        assertEquals(2_000L, timeline.mediaToVirtualMs(3_000L))

        // チャンク 0 と 1 の間の無音区間 (3000..5000) は境界 2000 に折りたたまれる
        assertEquals(2_000L, timeline.mediaToVirtualMs(4_000L))
        assertEquals(2_000L, timeline.mediaToVirtualMs(5_000L))

        // チャンク 1 内部 (5000..9000 -> 2000..6000)
        assertEquals(3_500L, timeline.mediaToVirtualMs(6_500L))

        // 末尾の無音区間 (12000..15000) は末尾 8000 に折りたたまれる
        assertEquals(8_000L, timeline.mediaToVirtualMs(12_000L))
        assertEquals(8_000L, timeline.mediaToVirtualMs(14_000L))

        // 逆変換 virtualToMediaMs
        assertEquals(1_000L, timeline.virtualToMediaMs(0L))
        assertEquals(2_200L, timeline.virtualToMediaMs(1_200L))
        assertEquals(5_000L, timeline.virtualToMediaMs(2_000L))
        assertEquals(6_500L, timeline.virtualToMediaMs(3_500L))
        assertEquals(10_000L, timeline.virtualToMediaMs(6_000L))
        assertEquals(12_000L, timeline.virtualToMediaMs(8_000L))
    }

    @Test
    fun `skipOriginalSilenceがfalseでもAutoPauseが有効なとき_各チャンクの後ろにAutoPauseSegmentが挿入される`() {
        // Fixed(1_500L)
        val fixedTimeline = buildZoomedVirtualTimeline(
            durationMs = 15_000L,
            chunks = sampleChunks,
            skipOriginalSilence = false,
            autoPause = AutoPause.Fixed(1_500L),
        )
        assertFalse(fixedTimeline.isIdentity)
        // 15_000 + 1_500 * 3 = 19_500
        assertEquals(19_500L, fixedTimeline.totalVirtualDurationMs)
        val fixedAutoPauseSegments = fixedTimeline.segments.filterIsInstance<ZoomedTimelineSegment.AutoPauseSegment>()
        assertEquals(3, fixedAutoPauseSegments.size)
        assertEquals(3_000L, fixedAutoPauseSegments[0].virtualStartMs)
        assertEquals(4_500L, fixedAutoPauseSegments[0].virtualEndMs)
        assertEquals(3_000L, fixedAutoPauseSegments[0].mediaStartMs)

        // Proportional(factor = 1.5f) with speed = 2.0f
        // chunk 0: 2000ms / 2.0 * 1.5 = 1500ms
        // chunk 1: 4000ms / 2.0 * 1.5 = 3000ms
        // chunk 2: 2000ms / 2.0 * 1.5 = 1500ms
        val propTimeline = buildZoomedVirtualTimeline(
            durationMs = 15_000L,
            chunks = sampleChunks,
            skipOriginalSilence = false,
            autoPause = AutoPause.Proportional(factor = 1.5f),
            speed = 2.0f,
        )
        val propPauses = propTimeline.segments.filterIsInstance<ZoomedTimelineSegment.AutoPauseSegment>()
        assertEquals(listOf(1_500L, 3_000L, 1_500L), propPauses.map { it.pauseDurationMs })
        assertEquals(15_000L + 6_000L, propTimeline.totalVirtualDurationMs)

        // OriginalSilence(factor = 0.5f)
        // chunk 0 -> chunk 1 silence = 5000 - 3000 = 2000ms -> pause = 1000ms
        // chunk 1 -> chunk 2 silence = 10000 - 9000 = 1000ms -> pause = 500ms
        // chunk 2 -> no next chunk -> pause = 0ms
        val origSilenceTimeline = buildZoomedVirtualTimeline(
            durationMs = 15_000L,
            chunks = sampleChunks,
            skipOriginalSilence = false,
            autoPause = AutoPause.OriginalSilence(factor = 0.5f),
        )
        val origPauses = origSilenceTimeline.segments.filterIsInstance<ZoomedTimelineSegment.AutoPauseSegment>()
        assertEquals(listOf(1_000L, 500L), origPauses.map { it.pauseDurationMs })
        assertEquals(15_000L + 1_500L, origSilenceTimeline.totalVirtualDurationMs)
    }

    @Test
    fun `skipOriginalSilenceがtrueかつAutoPause_OriginalSilenceのとき_元の無音は詰められつつfactor分のAutoPauseSegmentが挿入される`() {
        // チャンク長合計 = 8_000ms
        // 元のチャンク間無音: chunk0->1 = 2_000ms, chunk1->2 = 1_000ms (合計 3_000ms)
        for (factor in listOf(0.25f, 1.0f, 2.0f)) {
            val timeline = buildZoomedVirtualTimeline(
                durationMs = 15_000L,
                chunks = sampleChunks,
                skipOriginalSilence = true,
                autoPause = AutoPause.OriginalSilence(factor = factor),
            )

            assertFalse(timeline.isIdentity)
            assertTrue(timeline.segments.none { it is ZoomedTimelineSegment.SilenceGap })

            val autoPauseSegments = timeline.segments.filterIsInstance<ZoomedTimelineSegment.AutoPauseSegment>()
            assertEquals(2, autoPauseSegments.size)
            val expectedPause0 = (2_000L * factor).toLong()
            val expectedPause1 = (1_000L * factor).toLong()
            assertEquals(expectedPause0, autoPauseSegments[0].pauseDurationMs)
            assertEquals(expectedPause1, autoPauseSegments[1].pauseDurationMs)
            assertEquals(8_000L + expectedPause0 + expectedPause1, timeline.totalVirtualDurationMs)
        }
    }

    @Test
    fun `AutoPauseScope_AFTER_TARGET_LANGUAGEやisSkippedがtrueのチャンクでは自動ポーズ区間が挿入されない`() {
        val chunksWithSkipped = listOf(
            // index 0: 母語 (ja) -> AFTER_TARGET_LANGUAGE では対象外
            sampleChunks[0],
            // index 1: 学習言語 (ko) -> 対象
            sampleChunks[1],
            // index 2: 言語未判定 (null) -> 対象に含める
            sampleChunks[2],
            // index 3: 学習言語 (ko) だが isSkipped = true -> 0ms
            ChunkUi(
                id = ChunkId(4),
                startMs = 12_500L,
                endMs = 14_000L,
                text = "감사합니다",
                language = LanguageTag("ko"),
                isSkipped = true,
            ),
        )

        val timeline = buildZoomedVirtualTimeline(
            durationMs = 15_000L,
            chunks = chunksWithSkipped,
            skipOriginalSilence = true,
            autoPause = AutoPause.Fixed(2_000L),
            autoPauseScope = AutoPauseScope.AFTER_TARGET_LANGUAGE,
            languagePair = languagePair,
        )

        val autoPauseSegments = timeline.segments.filterIsInstance<ZoomedTimelineSegment.AutoPauseSegment>()
        // index 1 (ko) と index 2 (null) のみ自動ポーズが入る
        assertEquals(listOf(1, 2), autoPauseSegments.map { it.chunkIndex })
    }

    @Test
    fun `mediaToVirtualMsとvirtualToMediaMs_自動ポーズ経過時間指定時を含め相互変換が正しい`() {
        val timeline = buildZoomedVirtualTimeline(
            durationMs = 15_000L,
            chunks = sampleChunks,
            skipOriginalSilence = true,
            autoPause = AutoPause.Fixed(2_000L),
        )
        // セグメント構成:
        // Chunk 0: virtual 0..2000 (media 1000..3000)
        // AutoPause 0: virtual 2000..4000 (media 3000)
        // Chunk 1: virtual 4000..8000 (media 5000..9000)
        // AutoPause 1: virtual 8000..10000 (media 9000)
        // Chunk 2: virtual 10000..12000 (media 10000..12000)
        // AutoPause 2: virtual 12000..14000 (media 12000)

        assertEquals(14_000L, timeline.totalVirtualDurationMs)

        // 自動ポーズ経過時間の反映（Chunk 0 の終端 3000ms で 750ms 経過中 -> 仮想時刻 2750ms）
        assertEquals(
            2_750L,
            timeline.mediaToVirtualMs(
                mediaMs = 3_000L,
                activeAutoPauseChunkIndex = 0,
                autoPauseElapsedMs = 750L,
            ),
        )
        // ポーズ長を超える経過時間は AutoPauseSegment の終端 (4000ms) にクランプされる
        assertEquals(
            4_000L,
            timeline.mediaToVirtualMs(
                mediaMs = 3_000L,
                activeAutoPauseChunkIndex = 0,
                autoPauseElapsedMs = 5_000L,
            ),
        )

        // AutoPauseSegment 上の仮想時刻を virtualToMediaMs に渡すとチャンク終端時刻 (chunk.endMs) が返る
        assertEquals(3_000L, timeline.virtualToMediaMs(2_750L))
        assertEquals(9_000L, timeline.virtualToMediaMs(9_200L))
        assertIs<ZoomedTimelineSegment.AutoPauseSegment>(timeline.segmentAtVirtualMs(2_750L))

        // Chunk 1 内部の相互変換
        assertEquals(5_500L, timeline.mediaToVirtualMs(6_500L))
        assertEquals(6_500L, timeline.virtualToMediaMs(5_500L))
    }
}
