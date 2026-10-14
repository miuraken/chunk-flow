package com.miuraken.chunkflow.core.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class TranscriptOffsetAlignerTest {

    @Test
    fun `align_プラス150msの立ち上がりずれを正確に検出する`() {
        // 10秒間 (1,000 フレーム @ 10ms hop)
        // 文字起こしセグメント開始時刻: 1,000ms, 4,000ms, 7,000ms
        // 実際の音声発話開始時刻 (+150ms ずれ): 1,150ms..2,500ms, 4,150ms..5,500ms, 7,150ms..8,500ms
        val hopMs = 10
        val energyDb = FloatArray(1_000) { frame ->
            val timeMs = frame * hopMs
            when (timeMs) {
                in 1_150 until 2_500,
                in 4_150 until 5_500,
                in 7_150 until 8_500,
                -> -15f

                else -> -65f
            }
        }
        val segmentStartMsList = listOf(1_000L, 4_000L, 7_000L)

        val alignedOffsetMs = TranscriptOffsetAligner.align(
            segmentStartMsList = segmentStartMsList,
            energyDb = energyDb,
            hopMs = hopMs,
            initialOffsetMs = 0,
            searchRangeMs = 500,
            stepMs = 10,
        )

        assertEquals(150, alignedOffsetMs)
    }

    @Test
    fun `align_マイナス150msの立ち上がりずれを正確に検出する`() {
        // 文字起こしセグメント開始時刻: 1,500ms, 4,500ms, 7,500ms
        // 実際の音声発話開始時刻 (-150ms ずれ): 1,350ms..2,600ms, 4,350ms..5,600ms, 7,350ms..8,600ms
        val hopMs = 10
        val energyDb = FloatArray(1_000) { frame ->
            val timeMs = frame * hopMs
            when (timeMs) {
                in 1_350 until 2_600,
                in 4_350 until 5_600,
                in 7_350 until 8_600,
                -> -18f

                else -> -70f
            }
        }
        val segmentStartMsList = listOf(1_500L, 4_500L, 7_500L)

        val alignedOffsetMs = TranscriptOffsetAligner.align(
            segmentStartMsList = segmentStartMsList,
            energyDb = energyDb,
            hopMs = hopMs,
            initialOffsetMs = 0,
            searchRangeMs = 500,
            stepMs = 10,
        )

        assertEquals(-150, alignedOffsetMs)
    }

    @Test
    fun `align_初期オフセットを中心に探索し空入力時はinitialOffsetMsを返す`() {
        val emptyResult = TranscriptOffsetAligner.align(
            segmentStartMsList = emptyList(),
            energyDb = FloatArray(100) { -60f },
            initialOffsetMs = 40,
        )
        assertEquals(40, emptyResult)
    }
}
