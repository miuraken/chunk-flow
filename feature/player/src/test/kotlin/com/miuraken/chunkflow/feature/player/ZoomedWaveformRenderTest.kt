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

    @Test
    fun `styleZoomedChunkBackgroundColor_and_styleSkippedWaveformBarColor_スキップチャンクは彩度と不透明度を落としてコントラストを下げる`() {
        val bgOriginal = Color(0xFF1565C0).copy(alpha = 0.28f)
        val bgNormal = styleZoomedChunkBackgroundColor(roleColor = bgOriginal, emphasis = 1f, isSkipped = false)
        val bgSkipped = styleZoomedChunkBackgroundColor(roleColor = bgOriginal, emphasis = 1f, isSkipped = true)

        assertEquals(bgOriginal, bgNormal)
        assertTrue(bgSkipped.alpha < bgNormal.alpha)
        assertEquals(bgOriginal.alpha * SKIPPED_CHUNK_BACKGROUND_ALPHA_FACTOR, bgSkipped.alpha, 0.005f)
        val bgNormalSpread = maxOf(bgNormal.red, bgNormal.green, bgNormal.blue) - minOf(bgNormal.red, bgNormal.green, bgNormal.blue)
        val bgSkippedSpread = maxOf(bgSkipped.red, bgSkipped.green, bgSkipped.blue) - minOf(bgSkipped.red, bgSkipped.green, bgSkipped.blue)
        assertTrue(bgSkippedSpread < bgNormalSpread)

        val barOriginal = Color(0xFF2196F3).copy(alpha = 0.90f)
        val barSkipped = styleSkippedWaveformBarColor(barOriginal)
        assertTrue(barSkipped.alpha < barOriginal.alpha)
        assertEquals(barOriginal.alpha * SKIPPED_CHUNK_WAVEFORM_ALPHA_FACTOR, barSkipped.alpha, 0.005f)
        val barOriginalSpread = maxOf(barOriginal.red, barOriginal.green, barOriginal.blue) - minOf(barOriginal.red, barOriginal.green, barOriginal.blue)
        val barSkippedSpread = maxOf(barSkipped.red, barSkipped.green, barSkipped.blue) - minOf(barSkipped.red, barSkipped.green, barSkipped.blue)
        assertTrue(barSkippedSpread < barOriginalSpread)
    }

    @Test
    fun `computeProgressiveRevealCutoffMs_左から右へ0から1の割合で境界時刻を計算する`() {
        assertEquals(0L, computeProgressiveRevealCutoffMs(startMs = 0L, endMs = 10_000L, revealFraction = 0f))
        assertEquals(2_500L, computeProgressiveRevealCutoffMs(startMs = 0L, endMs = 10_000L, revealFraction = 0.25f))
        assertEquals(5_000L, computeProgressiveRevealCutoffMs(startMs = 0L, endMs = 10_000L, revealFraction = 0.5f))
        assertEquals(10_000L, computeProgressiveRevealCutoffMs(startMs = 0L, endMs = 10_000L, revealFraction = 1f))
        // 拡大波形の表示窓のように負の開始時刻を含む範囲でも線形補間される
        assertEquals(-5_000L, computeProgressiveRevealCutoffMs(startMs = -5_000L, endMs = 25_000L, revealFraction = 0f))
        assertEquals(10_000L, computeProgressiveRevealCutoffMs(startMs = -5_000L, endMs = 25_000L, revealFraction = 0.5f))
        assertEquals(25_000L, computeProgressiveRevealCutoffMs(startMs = -5_000L, endMs = 25_000L, revealFraction = 1f))
    }

    @Test
    fun `computeWaveformTileReusePlan_末尾に波形ビンが追加されたとき末尾のタイルのみ再描画し前半のタイルは再利用する`() {
        val initialPeak = ByteArray(500) { (-80).toByte() }
        val initialRms = ByteArray(500) { (-110).toByte() }
        val waveformPart1 = com.miuraken.chunkflow.core.model.Waveform(
            binMs = 10,
            peak = initialPeak,
            rms = initialRms,
        )
        // 500 ビン -> 750 ビンに伸長（0..5_000ms のデータは不変）
        val extendedPeak = ByteArray(750) { idx -> if (idx < 500) initialPeak[idx] else (-60).toByte() }
        val extendedRms = ByteArray(750) { idx -> if (idx < 500) initialRms[idx] else (-90).toByte() }
        val waveformPart2 = com.miuraken.chunkflow.core.model.Waveform(
            binMs = 10,
            peak = extendedPeak,
            rms = extendedRms,
        )

        val chunks = listOf(
            ChunkColorKey(startMs = 0L, endMs = 2_000L, language = com.miuraken.chunkflow.core.model.LanguageTag("ja")),
            ChunkColorKey(startMs = 2_000L, endMs = 4_500L, language = com.miuraken.chunkflow.core.model.LanguageTag("ko")),
        )

        // 全体幅 1024px (256px タイル x 4 = [0..2500ms, 2500..5000ms, 5000..7500ms, 7500..10000ms])
        val specs1 = computeWaveformTileSpecs(
            waveform = waveformPart1,
            startMs = 0L,
            endMs = 10_000L,
            totalWidthPx = 1024,
            chunkColorKeys = chunks,
            tileWidthPx = 256,
        )
        val specs2 = computeWaveformTileSpecs(
            waveform = waveformPart2,
            startMs = 0L,
            endMs = 10_000L,
            totalWidthPx = 1024,
            chunkColorKeys = chunks,
            tileWidthPx = 256,
        )

        val reusePlan = computeWaveformTileReusePlan(specs1, specs2)
        assertEquals(4, reusePlan.size)
        // 0..2500ms と 2500..5000ms のタイルは再利用、5000..7500ms は再描画、7500..10000ms（どちらも未到達）は再利用
        assertEquals(listOf(true, true, false, true), reusePlan)
    }

    @Test
    fun `computeWaveformTileReusePlan_特定のチャンクの言語または文字起こし状態が変わったとき重なるタイルのみ再描画する`() {
        val peak = ByteArray(1_000) { (-70).toByte() }
        val rms = ByteArray(1_000) { (-100).toByte() }
        val waveform = com.miuraken.chunkflow.core.model.Waveform(
            binMs = 10,
            peak = peak,
            rms = rms,
        )
        val chunksBefore = listOf(
            ChunkColorKey(startMs = 500L, endMs = 2_000L, language = com.miuraken.chunkflow.core.model.LanguageTag("ja"), isTranscribed = true),
            ChunkColorKey(startMs = 3_000L, endMs = 4_500L, language = null, isTranscribed = false),
            ChunkColorKey(startMs = 8_000L, endMs = 9_500L, language = com.miuraken.chunkflow.core.model.LanguageTag("ko"), isTranscribed = true),
        )
        // 2 番目のチャンク（3_000..4_500ms、タイル index=1: 2_500..5_000ms にのみ重なる）の言語・文字起こし完了を更新
        val chunksAfter = listOf(
            chunksBefore[0],
            chunksBefore[1].copy(language = com.miuraken.chunkflow.core.model.LanguageTag("ko"), isTranscribed = true),
            chunksBefore[2],
        )

        val specsBefore = computeWaveformTileSpecs(
            waveform = waveform,
            startMs = 0L,
            endMs = 10_000L,
            totalWidthPx = 1024,
            chunkColorKeys = chunksBefore,
            tileWidthPx = 256,
        )
        val specsAfter = computeWaveformTileSpecs(
            waveform = waveform,
            startMs = 0L,
            endMs = 10_000L,
            totalWidthPx = 1024,
            chunkColorKeys = chunksAfter,
            tileWidthPx = 256,
        )

        val reusePlan = computeWaveformTileReusePlan(specsBefore, specsAfter)
        assertEquals(listOf(true, false, true, true), reusePlan)
    }

    @Test
    fun `chunkColorKeysSliceContentHash_範囲外のチャンク更新ではハッシュが変わらない`() {
        val baseKeys = listOf(
            ChunkColorKey(startMs = 0L, endMs = 5_000L, language = com.miuraken.chunkflow.core.model.LanguageTag("ja"), isTranscribed = true),
            ChunkColorKey(startMs = 40_000L, endMs = 45_000L, language = null, isTranscribed = false),
        )
        val updatedOutsideKeys = listOf(
            baseKeys[0],
            baseKeys[1].copy(language = com.miuraken.chunkflow.core.model.LanguageTag("ko"), isTranscribed = true),
        )
        val updatedInsideKeys = listOf(
            baseKeys[0].copy(isSkipped = true),
            baseKeys[1],
        )

        val localRange = 0L..30_000L
        assertEquals(
            chunkColorKeysSliceContentHash(baseKeys, localRange),
            chunkColorKeysSliceContentHash(updatedOutsideKeys, localRange),
        )
        assertTrue(
            chunkColorKeysSliceContentHash(baseKeys, localRange) !=
                chunkColorKeysSliceContentHash(updatedInsideKeys, localRange),
        )
    }
}
