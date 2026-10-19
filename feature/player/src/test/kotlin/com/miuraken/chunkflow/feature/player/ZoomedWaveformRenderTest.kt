package com.miuraken.chunkflow.feature.player

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ZoomedWaveformRenderTest {
    @Test
    fun `zoomRenderLevelWindowMs_15秒と7点5秒をしきい値に近傍画像の解像度を決める`() {
        assertNull(zoomRenderLevelWindowMs(DEFAULT_ZOOM_WINDOW_MS))
        assertNull(zoomRenderLevelWindowMs(HIGH_RES_ZOOM_THRESHOLD_MS))
        assertEquals(HIGH_RES_ZOOM_THRESHOLD_MS, zoomRenderLevelWindowMs(HIGH_RES_ZOOM_THRESHOLD_MS - 1))
        assertEquals(HIGH_RES_ZOOM_THRESHOLD_MS, zoomRenderLevelWindowMs(FINE_ZOOM_THRESHOLD_MS))
        assertEquals(FINE_ZOOM_THRESHOLD_MS, zoomRenderLevelWindowMs(FINE_ZOOM_THRESHOLD_MS - 1))
        assertEquals(FINE_ZOOM_THRESHOLD_MS, zoomRenderLevelWindowMs(MIN_ZOOM_WINDOW_MS))
    }

    @Test
    fun `localStripLevelMs_全体画像が縮んでいる長い音声では30秒以下の表示でも近傍画像を使う`() {
        assertNull(localStripLevelMs(settledWindowMs = DEFAULT_ZOOM_WINDOW_MS, isFullStripDownscaled = false))
        assertEquals(
            DEFAULT_ZOOM_WINDOW_MS,
            localStripLevelMs(settledWindowMs = DEFAULT_ZOOM_WINDOW_MS, isFullStripDownscaled = true),
        )
        assertNull(localStripLevelMs(settledWindowMs = 60_000L, isFullStripDownscaled = true))
        assertEquals(FINE_ZOOM_THRESHOLD_MS, localStripLevelMs(settledWindowMs = 5_000L, isFullStripDownscaled = true))
        assertEquals(HIGH_RES_ZOOM_THRESHOLD_MS, localStripLevelMs(settledWindowMs = 10_000L, isFullStripDownscaled = false))
    }

    @Test
    fun `localStripRangeFor_中心の前後に解像度の4倍の幅を取り音声の範囲に収める`() {
        val level = FINE_ZOOM_THRESHOLD_MS
        val span = level * LOCAL_STRIP_SPAN_FACTOR
        assertEquals((60_000L - span / 2)..(60_000L + span / 2), localStripRangeFor(60_000L, level, 120_000L))
        // 先頭・末尾では範囲をずらして収める
        assertEquals(0L..span, localStripRangeFor(1_000L, level, 120_000L))
        assertEquals((120_000L - span)..120_000L, localStripRangeFor(119_000L, level, 120_000L))
        // 音声が範囲より短いときは全体
        assertEquals(0L..10_000L, localStripRangeFor(5_000L, level, 10_000L))
    }

    @Test
    fun `zoomedTouchXToMs_中央が現在位置で左右に表示幅の半分ずつ広がる`() {
        assertEquals(10_000L, zoomedTouchXToMs(x = 500f, widthPx = 1_000, centerMs = 10_000L, windowMs = 30_000L, durationMs = 60_000L))
        assertEquals(-5_000L + 10_000L, zoomedTouchXToMs(x = 0f, widthPx = 1_000, centerMs = 10_000L, windowMs = 10_000L, durationMs = 60_000L))
        assertEquals(15_000L, zoomedTouchXToMs(x = 1_000f, widthPx = 1_000, centerMs = 10_000L, windowMs = 10_000L, durationMs = 60_000L))
        // 音声の範囲に収める
        assertEquals(0L, zoomedTouchXToMs(x = 0f, widthPx = 1_000, centerMs = 1_000L, windowMs = 30_000L, durationMs = 60_000L))
        assertEquals(60_000L, zoomedTouchXToMs(x = 1_000f, widthPx = 1_000, centerMs = 59_000L, windowMs = 30_000L, durationMs = 60_000L))
    }

    @Test
    fun `desaturateWaveformColor_アルファを維持しつつRGBの彩度を落とす`() {
        val original = Color(0xFF1565C0).copy(alpha = 0.65f)
        val desaturated = desaturateWaveformColor(original)

        assertEquals(original.alpha, desaturated.alpha, 0.001f)
        val originalSpread = maxOf(original.red, original.green, original.blue) - minOf(original.red, original.green, original.blue)
        val desaturatedSpread = maxOf(desaturated.red, desaturated.green, desaturated.blue) - minOf(desaturated.red, desaturated.green, desaturated.blue)
        assertTrue(desaturatedSpread < originalSpread)
        assertEquals(originalSpread * UNTRANSCRIBED_WAVEFORM_SATURATION, desaturatedSpread, 0.01f)
    }
}
