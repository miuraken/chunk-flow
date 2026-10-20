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
    fun `splitAndTrimSilence_分割後の前半と後半から無音部分がトリミングされパディングで埋まらない`() {
        // 5秒 = 500フレーム (10ms/frame)。ノイズフロア -60dB、発話 -12dB
        val energy = FloatArray(500) { -60f }
        // 元チャンク [500..4500ms] のうち:
        // - 前半の発話: [800..1600ms] (frame 80..160)
        // - 中間の無音: [1600..2600ms] (分割点 2100ms はこの無音区間の中)
        // - 後半の発話: [2600..3900ms] (frame 260..390)
        for (f in 80 until 160) energy[f] = -12f
        for (f in 260 until 390) energy[f] = -12f

        val segmenter = ChunkSegmenter()
        val params = SegmentationParams(prePaddingMs = 150, postPaddingMs = 200)
        val (first, second) = segmenter.splitAndTrimSilence(
            logEnergyDb = energy,
            chunkStartMs = 500L,
            chunkEndMs = 4_500L,
            splitPositionMs = 2_100L,
            params = params,
            binMs = 10,
        )

        // 前半: 発話 [800..1600ms] に最小限のパディング（最大 30ms）-> [770..1630ms]
        assertEquals(770L to 1_630L, first)
        // 後半: 発話 [2600..3900ms] に最小限のパディング（最大 30ms）-> [2570..3930ms]
        assertEquals(2_570L to 3_930L, second)
    }

    @Test
    fun `splitAndTrimSilence_チャンク内部のポーズがマイナス40dBでもファイル全体のデジタル無音に引きずられずトリミングされる`() {
        // ファイル全体 10秒 (1000フレーム): 大部分が -100dB のデジタル無音
        val energy = FloatArray(1_000) { -100f }
        // チャンク [1000..4000ms] のうち:
        // - 前半発話: [1200..2000ms] (-12dB)
        // - チャンク内部ポーズ: [2000..2600ms] (-40dB: 室温ノイズや息継ぎ想定、分割点 2300ms)
        // - 後半発話: [2600..3700ms] (-12dB)
        for (f in 100 until 400) energy[f] = -40f
        for (f in 120 until 200) energy[f] = -12f
        for (f in 260 until 370) energy[f] = -12f

        val segmenter = ChunkSegmenter()
        val pieces = segmenter.splitAndTrimSilencePieces(
            logEnergyDb = energy,
            chunkStartMs = 1_000L,
            chunkEndMs = 4_000L,
            splitPositionMs = 2_300L,
            params = SegmentationParams(prePaddingMs = 150, postPaddingMs = 200),
            binMs = 10,
        )

        assertEquals(2, pieces.size)
        // ポーズ区間 [2000..2600ms] (-40dB) とチャンク端の無音 (-40dB) がトリミングされ、パディングは最大 30ms 以下に抑えられる
        assertTrue(pieces[0].first in 1_170L..1_200L, "first.start=${pieces[0].first}")
        assertTrue(pieces[0].second in 2_000L..2_030L, "first.end=${pieces[0].second}")
        assertTrue(pieces[1].first in 2_570L..2_600L, "second.start=${pieces[1].first}")
        assertTrue(pieces[1].second in 3_700L..3_730L, "second.end=${pieces[1].second}")
    }

    @Test
    fun `splitAndTrimSilencePieces_片側の半区間が無音のみの場合は発話のある1区間のみを返す`() {
        val energy = FloatArray(500) { -60f }
        // 元チャンク [1000..3500ms] のうち、前半 [1000..1800ms] は無音 (-42dB)、後半 [1900..3200ms] のみ発話 (-12dB)
        for (f in 100 until 190) energy[f] = -42f
        for (f in 190 until 320) energy[f] = -12f
        for (f in 320 until 350) energy[f] = -42f

        val segmenter = ChunkSegmenter()
        val piecesLeadingSilence = segmenter.splitAndTrimSilencePieces(
            logEnergyDb = energy,
            chunkStartMs = 1_000L,
            chunkEndMs = 3_500L,
            splitPositionMs = 1_800L,
            binMs = 10,
        )
        assertEquals(1, piecesLeadingSilence.size)
        assertEquals(1_875L to 3_230L, piecesLeadingSilence[0])

        // 逆に後半 [3200..3500ms] の無音を切り落とすために 3250ms で分割した場合も前半の 1 区間のみを返す
        val piecesTrailingSilence = segmenter.splitAndTrimSilencePieces(
            logEnergyDb = energy,
            chunkStartMs = 1_000L,
            chunkEndMs = 3_500L,
            splitPositionMs = 3_250L,
            binMs = 10,
        )
        assertEquals(1, piecesTrailingSilence.size)
        assertEquals(1_870L to 3_212L, piecesTrailingSilence[0])
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

    @Test
    fun `trimSilenceInRange_有音領域がある場合は左右の無音をトリミングし無音のみの場合は全範囲を返す`() {
        val segmenter = ChunkSegmenter()
        // 0..5000ms (500 frames): 1400..2600ms のみ有音 (-15dB)、それ以外は無音 (-65dB)
        val energy = FloatArray(500) { frame ->
            val ms = frame * 10
            if (ms in 1_400 until 2_600) -15f else -65f
        }

        // 左右に無音を含む [1000..3000ms] を選択した場合、有音領域 [1400..2600ms] 前後にトリミングされる
        val (trimmedStart, trimmedEnd) = segmenter.trimSilenceInRange(
            logEnergyDb = energy,
            rangeStartMs = 1_000L,
            rangeEndMs = 3_000L,
            binMs = 10,
        )
        assertTrue(trimmedStart in 1_350L..1_420L, "trimmedStart=$trimmedStart")
        assertTrue(trimmedEnd in 2_580L..2_650L, "trimmedEnd=$trimmedEnd")

        // 無音しかない [3200..4500ms] を選択した場合、選択された全範囲がそのまま返る
        val silentRange = segmenter.trimSilenceInRange(
            logEnergyDb = energy,
            rangeStartMs = 3_200L,
            rangeEndMs = 4_500L,
            binMs = 10,
        )
        assertEquals(3_200L to 4_500L, silentRange)

        // 全体が無音の特徴量でも全範囲がそのまま返る
        val allSilentEnergy = FloatArray(500) { -65f }
        assertEquals(
            1_000L to 2_500L,
            segmenter.trimSilenceInRange(
                logEnergyDb = allSilentEnergy,
                rangeStartMs = 1_000L,
                rangeEndMs = 2_500L,
                binMs = 10,
            ),
        )
    }

    @Test
    fun `isSilenceOnlyInRange_無音判定感度に応じて無音のみかどうかを判定する`() {
        val segmenter = ChunkSegmenter()
        // 0..5000ms: 1000..2000ms は発話 (-12dB)、3000..4000ms は小さなノイズ (-35dB)、それ以外は無音 (-65dB)
        val energy = FloatArray(500) { frame ->
            val ms = frame * 10
            when (ms) {
                in 1_000 until 2_000 -> -12f
                in 3_000 until 4_000 -> -35f
                else -> -65f
            }
        }

        // 有音を含む区間 [800..2200ms] は false
        assertTrue(
            !segmenter.isSilenceOnlyInRange(
                logEnergyDb = energy,
                rangeStartMs = 800L,
                rangeEndMs = 2_200L,
                params = SegmentationParams(thresholdRatio = 0.55f),
                binMs = 10,
            ),
        )

        // 無音のみの区間 [4100..4900ms] は true
        assertTrue(
            segmenter.isSilenceOnlyInRange(
                logEnergyDb = energy,
                rangeStartMs = 4_100L,
                rangeEndMs = 4_900L,
                params = SegmentationParams(thresholdRatio = 0.55f),
                binMs = 10,
            ),
        )

        // 感度 (thresholdRatio) を高くすると小さなノイズ区間 [3000..4000ms] も無音と判定される
        assertTrue(
            segmenter.isSilenceOnlyInRange(
                logEnergyDb = energy,
                rangeStartMs = 3_000L,
                rangeEndMs = 4_000L,
                params = SegmentationParams(thresholdRatio = 0.85f),
                binMs = 10,
            ),
        )
    }

    @Test
    fun `detectBgmPresence_開始30秒以降の非発話フレームに有意な音量がある場合はBGMありと判定しイントロのみの音楽はBGMなしと判定する`() {
        // 60秒 = 6000フレーム (10ms/frame)
        val totalFrames = 6_000
        val speechProb = FloatArray(totalFrames) { frame ->
            // 1秒ごとに 500ms 発話、500ms 非発話
            if ((frame % 100) in 20 until 70) 0.90f else 0.02f
        }

        // ケース1: 開始 0〜25秒だけイントロ音楽 (-22dB) が鳴り、30秒以降の非発話フレームは無音 (-65dB)
        val introOnlyEnergy = FloatArray(totalFrames) { frame ->
            val isSpeech = (frame % 100) in 20 until 70
            when {
                isSpeech -> -14f
                frame < 2_500 -> -22f
                else -> -65f
            }
        }
        assertTrue(
            !ChunkSegmenter.detectBgmPresence(
                logEnergyDb = introOnlyEnergy,
                speechProbability = speechProb,
                binMs = 10,
            ),
            "イントロ音楽のみ（30秒以降は静寂）なら hasBgm = false になるべき",
        )

        // ケース2: 30秒以降の非発話フレームにも常時 BGM (-25dB > -45dB) が鳴っている
        val constantBgmEnergy = FloatArray(totalFrames) { frame ->
            val isSpeech = (frame % 100) in 20 until 70
            if (isSpeech) -12f else -25f
        }
        assertTrue(
            ChunkSegmenter.detectBgmPresence(
                logEnergyDb = constantBgmEnergy,
                speechProbability = speechProb,
                binMs = 10,
            ),
            "30秒以降の非発話区間に BGM があるなら hasBgm = true になるべき",
        )
    }

    @Test
    fun `segment_SileroVADとBGM自動適応により常時BGM下でも220msポーズの短語が巨大チャンク化せず個別に分割される`() {
        // 50秒 = 5000フレーム。常時 -22dBFS の BGM が鳴っているためエネルギー差では無音が検出できない状況を模擬
        val totalFrames = 5_000
        val energy = FloatArray(totalFrames) { -22f }
        val probs = FloatArray(totalFrames) { 0.02f }

        // 35秒付近に 3 つの単語（内 1 つは 180ms の1音節語「好」想定）を 220ms ギャップで配置
        // 単語1: 35000..35600ms (600ms)
        // ポーズ1: 35600..35820ms (220ms < 300ms だが BGM 適応の effectiveMinSilenceMs=219ms 以上)
        // 単語2 (1音節短語): 35820..36000ms (180ms < minSpeechMs 250ms だが >= 150ms なので mergeGapMs=1000ms で強制結合しない)
        // ポーズ2: 36000..36230ms (230ms)
        // 単語3: 36230..36900ms (670ms)
        val wordRangesFrames = listOf(
            3_500 until 3_560,
            3_582 until 3_600,
            3_623 until 3_690,
        )
        for (range in wordRangesFrames) {
            for (f in range) {
                energy[f] = -12f
                probs[f] = 0.92f
            }
        }

        val segmenter = ChunkSegmenter()
        val chunks = segmenter.segment(
            logEnergyDb = energy,
            params = SegmentationParams(
                minSilenceMs = 300,
                minSpeechMs = 250,
                mergeGapMs = 1_000,
                prePaddingMs = 150,
                postPaddingMs = 200,
            ),
            binMs = 10,
            durationMs = 50_000L,
            speechProbability = probs,
        )

        assertEquals(3, chunks.size, "BGM下でも3単語が結合されず3チャンクに分割されるべき: chunks=$chunks")
        // BGMありのときはパディングが pre<=80ms, post<=100ms に抑制される
        assertEquals(35_000L - 80L, chunks[0].startMs)
        assertEquals(36_900L + 100L, chunks[2].endMs)
    }

    @Test
    fun `trimSilenceInRangeとsplitAndTrimSilence_SileroVADにより両端にマイナス20dBFSのBGMが鳴っていても発話区間だけにトリミングされる`() {
        val segmenter = ChunkSegmenter()
        // 0..5000ms (500 frames): 全区間で -20dBFS の大音量 BGM が鳴っており、エネルギーだけでは無音端を全く見分けられない
        val energy = FloatArray(500) { frame ->
            val ms = frame * 10
            if (ms in 1_400 until 2_400) -14f else -20f
        }
        val probs = FloatArray(500) { frame ->
            val ms = frame * 10
            if (ms in 1_400 until 2_400) 0.94f else 0.03f
        }

        // 1. 拡大波形の「チャンク生成」（trimSilenceInRange）: [800..3200ms] を選択しても [1400..2400ms] 前後（±30ms以内）にトリミングされる
        val (trimmedStart, trimmedEnd) = segmenter.trimSilenceInRange(
            logEnergyDb = energy,
            rangeStartMs = 800L,
            rangeEndMs = 3_200L,
            binMs = 10,
            speechProbability = probs,
        )
        assertTrue(trimmedStart in 1_370L..1_400L, "trimmedStart=$trimmedStart")
        assertTrue(trimmedEnd in 2_400L..2_430L, "trimmedEnd=$trimmedEnd")

        // 2. BGM のみの区間 [3300..4500ms] は isSilenceOnlyInRange が true になる
        assertTrue(
            segmenter.isSilenceOnlyInRange(
                logEnergyDb = energy,
                rangeStartMs = 3_300L,
                rangeEndMs = 4_500L,
                binMs = 10,
                speechProbability = probs,
            ),
        )

        // 3. チャンク分割（splitAndTrimSilencePieces）でも BGM 端がトリミングされる
        val probsTwoWords = FloatArray(500) { frame ->
            val ms = frame * 10
            if (ms in 1_000 until 1_700 || ms in 2_500 until 3_400) 0.92f else 0.02f
        }
        val pieces = segmenter.splitAndTrimSilencePieces(
            logEnergyDb = energy,
            chunkStartMs = 600L,
            chunkEndMs = 4_000L,
            splitPositionMs = 2_100L,
            binMs = 10,
            speechProbability = probsTwoWords,
        )
        assertEquals(2, pieces.size)
        assertTrue(pieces[0].first in 970L..1_000L, "first.start=${pieces[0].first}")
        assertTrue(pieces[0].second in 1_700L..1_730L, "first.end=${pieces[0].second}")
        assertTrue(pieces[1].first in 2_470L..2_500L, "second.start=${pieces[1].first}")
        assertTrue(pieces[1].second in 3_400L..3_430L, "second.end=${pieces[1].second}")
    }

    @Test
    fun `SpeechProbabilityInterpolator_32ms窓の確率列を10msビンへ線形補間して0から1にクランプする`() {
        val probs32 = floatArrayOf(0.0f, 1.0f, 0.5f)
        val interpolated = SpeechProbabilityInterpolator.interpolate32msToBins(
            probs32ms = probs32,
            targetFrameCount = 10,
            binMs = 10,
        )
        assertEquals(10, interpolated.size)
        assertTrue(interpolated.all { it in 0.0f..1.0f })
        assertTrue(interpolated[0] < 0.2f)
        assertTrue(interpolated[4] > 0.8f)
    }
}
