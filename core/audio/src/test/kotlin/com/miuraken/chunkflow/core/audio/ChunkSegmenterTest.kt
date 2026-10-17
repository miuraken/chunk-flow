package com.miuraken.chunkflow.core.audio

import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.SegmentationParams
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChunkSegmenterTest {

    @Test
    fun `合成信号_トーンバーストと無音とノイズから期待境界との誤差10ms以内で抽出される`() {
        val sampleRate = 16_000
        val durationSec = 6
        val totalSamples = sampleRate * durationSec
        val rnd = Random(42)

        // 背景に微小ホワイトノイズ（約 -50dBFS）を入れ、2 つのトーンバースト（[1000..2200ms], [3100..4500ms]）を配置
        val expectedIntervalsMs = listOf(
            1_000L to 2_200L,
            3_100L to 4_500L,
        )
        val samples = FloatArray(totalSamples) {
            (rnd.nextFloat() * 2f - 1f) * 0.003f
        }
        for ((startMs, endMs) in expectedIntervalsMs) {
            val startSample = (startMs * sampleRate / 1_000L).toInt()
            val endSample = (endMs * sampleRate / 1_000L).toInt()
            for (i in startSample until endSample) {
                val t = i.toDouble() / sampleRate
                samples[i] += (0.5 * sin(2.0 * PI * 440.0 * t)).toFloat()
            }
        }

        val acc = FeatureAccumulator(binMs = 10)
        val extractor = FrameFeatureExtractor(sampleRate = sampleRate, binMs = 10, sink = acc)
        extractor.process(samples, samples.size)
        extractor.finish()

        val segmenter = ChunkSegmenter()
        val rawIntervals = segmenter.extractSpeechIntervalsWithoutPadding(
            logEnergyDb = acc.logEnergyDb(),
            params = SegmentationParams(
                minSilenceMs = 500,
                minSpeechMs = 250,
                prePaddingMs = 0,
                postPaddingMs = 0,
            ),
            binMs = 10,
        )

        assertEquals(expectedIntervalsMs.size, rawIntervals.size)
        for (i in expectedIntervalsMs.indices) {
            val (expStart, expEnd) = expectedIntervalsMs[i]
            val (actStart, actEnd) = rawIntervals[i]
            assertTrue(
                abs(actStart - expStart) <= 10L,
                "Chunk $i startMs expected=$expStart actual=$actStart",
            )
            assertTrue(
                abs(actEnd - expEnd) <= 10L,
                "Chunk $i endMs expected=$expEnd actual=$actEnd",
            )
        }

        // パディング付与後も ±10ms 以内で期待どおり（pre=150ms, post=200ms）になることを確認
        val padded = segmenter.segment(
            logEnergyDb = acc.logEnergyDb(),
            params = SegmentationParams(prePaddingMs = 150, postPaddingMs = 200),
            binMs = 10,
            durationMs = durationSec * 1_000L,
        )
        assertEquals(2, padded.size)
        assertTrue(abs(padded[0].startMs - (1_000L - 150L)) <= 10L)
        assertTrue(abs(padded[0].endMs - (2_200L + 200L)) <= 10L)
        assertTrue(abs(padded[1].startMs - (3_100L - 150L)) <= 10L)
        assertTrue(abs(padded[1].endMs - (4_500L + 200L)) <= 10L)
    }

    @Test
    fun `短い発話の扱い_近くにあれば統合し孤立していても高エネルギーな短語は残し低エネルギーノイズは捨てる`() {
        // 10秒 = 1000 フレーム (10ms/frame)。ノイズフロア -55dB、通常発話 -10dB -> 閾値 T ≒ -41.5dB
        val energy = FloatArray(1_000) { -55f }

        // 1. 通常発話 [500..1500ms] (frame 50..150) + 600ms 後に短い発話 [2100..2250ms] (150ms < minSpeechMs 250ms, gap 600ms < mergeGapMs 1000ms)
        // -> 統合されて [500..2250ms] になる
        for (f in 50 until 150) energy[f] = -10f
        for (f in 210 until 225) energy[f] = -20f

        // 2. 孤立した短い発話（「はい」「Yes」想定）[4000..4150ms] (150ms, 前後と 1.5s 以上離れているがピーク -10dB >= T + 6dB)
        // -> 捨てずに残る
        for (f in 400 until 415) energy[f] = -10f

        // 3. 孤立した微小ノイズ [6000..6100ms] (100ms, ピーク -37dB: T + 3dB は超えて一度反応するが T + 6dB 未満)
        // -> ノイズとして捨てられる
        for (f in 600 until 610) energy[f] = -37f

        // 4. 末尾の通常発話 [8000..9000ms]
        for (f in 800 until 900) energy[f] = -10f

        val segmenter = ChunkSegmenter()
        val raw = segmenter.extractSpeechIntervalsWithoutPadding(
            logEnergyDb = energy,
            params = SegmentationParams(
                minSilenceMs = 500,
                minSpeechMs = 250,
                mergeGapMs = 1_000,
                prePaddingMs = 0,
                postPaddingMs = 0,
            ),
            binMs = 10,
        )

        assertEquals(3, raw.size, "raw=$raw")
        // 1 つ目は短い発話が統合されて 500..2250ms
        assertEquals(500L to 2_250L, raw[0])
        // 2 つ目は孤立した高エネルギー短語「はい」が保持されて 4000..4150ms
        assertEquals(4_000L to 4_150L, raw[1])
        // 3 つ目は微小ノイズが捨てられて 8000..9000ms
        assertEquals(8_000L to 9_000L, raw[2])
    }

    @Test
    fun `15秒を超える長尺発話_150ms以上の低エネルギー谷を優先して再帰的に分割される`() {
        // 22秒 = 2200 フレーム。500ms〜20500ms（20秒 > maxChunkMs 15秒）の連続発話を作る
        val energy = FloatArray(2_200) { -55f }
        for (f in 50 until 2_050) {
            energy[f] = -10f
        }
        // 8000ms (frame 800..803, 30ms) に瞬間的な深いディップ (-35dB) を置くが 150ms 未満
        for (f in 800 until 803) {
            energy[f] = -35f
        }
        // 11000..11200ms (frame 1100..1120, 200ms >= 150ms) に息継ぎの谷 (-28dB、T より上なので手順4では切れない) を置く
        for (f in 1_100 until 1_120) {
            energy[f] = -28f
        }

        val segmenter = ChunkSegmenter()
        val raw = segmenter.extractSpeechIntervalsWithoutPadding(
            logEnergyDb = energy,
            params = SegmentationParams(
                minSilenceMs = 500,
                maxChunkMs = 15_000,
                prePaddingMs = 0,
                postPaddingMs = 0,
            ),
            binMs = 10,
        )

        assertEquals(2, raw.size, "raw=$raw")
        assertEquals(500L, raw[0].first)
        // 分割点が 11000..11200ms の谷の中にあること
        assertTrue(raw[0].second in 11_000L..11_200L, "split=${raw[0].second}")
        assertEquals(raw[0].second, raw[1].first)
        assertEquals(20_500L, raw[1].second)
        assertTrue(raw.all { (s, e) -> e - s <= 15_000L })
    }

    @Test
    fun `パディングのクランプ_0とdurationMsでクランプされ隣接チャンク間の無音の中点を越えない`() {
        // チャンク 1: [50..500ms]（先頭が prePadding 150ms より小さい -> 0ms にクランプ）
        // チャンク 2: [600..1000ms]（チャンク 1 との無音は 500..600ms、中点は 550ms -> 双方が 550ms で止まる）
        // チャンク 3: [1900..1980ms]（durationMs=2000ms に対して postPadding 200ms -> 2000ms にクランプ）
        val chunks = ChunkPadding.applyPadding(
            rawIntervals = listOf(
                50L to 500L,
                600L to 1_000L,
                1_900L to 1_980L,
            ),
            prePaddingMs = 150,
            postPaddingMs = 200,
            durationMs = 2_000L,
            origin = ChunkOrigin.AUTO,
        )

        assertEquals(3, chunks.size)
        assertEquals(0L, chunks[0].startMs)
        assertEquals(550L, chunks[0].endMs)
        assertEquals(550L, chunks[1].startMs)
        assertEquals(1_200L, chunks[1].endMs)
        assertEquals(1_750L, chunks[2].startMs)
        assertEquals(2_000L, chunks[2].endMs)
    }

    @Test
    fun `splitAndTrimSilence_分割後の前半と後半から無音部分がトリミングされ元チャンクと分割境界内に収まる`() {
        // 5秒 = 500フレーム (10ms/frame)。ノイズフロア -60dB、発話 -12dB
        val energy = FloatArray(500) { -60f }
        // 元チャンク [500..4500ms] のうち:
        // - 前半の発話: [800..1600ms] (frame 80..160)
        // - 中間の無音: [1600..2600ms] (分割点 2100ms はこの無音区間の中)
        // - 後半の発話: [2600..3900ms] (frame 260..390)
        for (f in 80 until 160) energy[f] = -12f
        for (f in 260 until 390) energy[f] = -12f

        val segmenter = ChunkSegmenter()
        val params = SegmentationParams(prePaddingMs = 100, postPaddingMs = 150)
        val (first, second) = segmenter.splitAndTrimSilence(
            logEnergyDb = energy,
            chunkStartMs = 500L,
            chunkEndMs = 4_500L,
            splitPositionMs = 2_100L,
            params = params,
            binMs = 10,
        )

        // 前半: 発話 [800..1600ms] に pre=100, post=150 -> [700..1750ms]（500..2100ms の範囲内）
        assertEquals(700L to 1_750L, first)
        // 後半: 発話 [2600..3900ms] に pre=100, post=150 -> [2500..4050ms]（2100..4500ms の範囲内）
        assertEquals(2_500L to 4_050L, second)
    }

    @Test
    fun `splitAndTrimSilence_パディングが元チャンク境界や分割点を超える場合は半区間内にクランプされる`() {
        val energy = FloatArray(400) { -60f }
        // 元チャンク [1000..3000ms]、分割点 2000ms
        // 前半発話: [1050..1950ms] (frame 105..195)、後半発話: [2050..2950ms] (frame 205..295)
        for (f in 105 until 195) energy[f] = -10f
        for (f in 205 until 295) energy[f] = -10f

        val segmenter = ChunkSegmenter()
        val params = SegmentationParams(prePaddingMs = 200, postPaddingMs = 250)
        val (first, second) = segmenter.splitAndTrimSilence(
            logEnergyDb = energy,
            chunkStartMs = 1_000L,
            chunkEndMs = 3_000L,
            splitPositionMs = 2_000L,
            params = params,
            binMs = 10,
        )

        // パディング 200/250ms を足すと 1000ms・2000ms・3000ms を超えるが、それぞれの半区間内にクランプされる
        assertEquals(1_000L to 2_000L, first)
        assertEquals(2_000L to 3_000L, second)
    }

    @Test
    fun `splitAndTrimSilence_無音のみの区間や空特徴量ではチャンクが消滅せず元の半区間にフォールバックする`() {
        val segmenter = ChunkSegmenter()
        val silentEnergy = FloatArray(400) { -65f }
        val (firstSilent, secondSilent) = segmenter.splitAndTrimSilence(
            logEnergyDb = silentEnergy,
            chunkStartMs = 500L,
            chunkEndMs = 3_500L,
            splitPositionMs = 1_800L,
            params = SegmentationParams(prePaddingMs = 150, postPaddingMs = 200),
            binMs = 10,
        )
        assertEquals(500L to 1_800L, firstSilent)
        assertEquals(1_800L to 3_500L, secondSilent)

        val (firstEmpty, secondEmpty) = segmenter.splitAndTrimSilence(
            logEnergyDb = FloatArray(0),
            chunkStartMs = 500L,
            chunkEndMs = 3_500L,
            splitPositionMs = 1_800L,
        )
        assertEquals(500L to 1_800L, firstEmpty)
        assertEquals(1_800L to 3_500L, secondEmpty)
    }
}
