package com.miuraken.chunkflow.core.audio

import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.SegmentationParams
import kotlin.math.max
import kotlin.math.min

/** 端末内のチャンク分割アルゴリズムの現在のバージョン（§6.4、§7.3.3）。 */
const val CURRENT_ALGORITHM_VERSION: Int = 1

/**
 * チャンクの前後にパディング（余白）を付与するユーティリティ（§7.3.3 手順 7、§7.4.6）。
 *
 * 前後のパディング（`prePaddingMs` / `postPaddingMs`）を付与する際、`[0, durationMs]` の範囲に収め、
 * かつ隣接するチャンクとの間にある無音区間の中点を越えないようにクランプする。
 */
object ChunkPadding {

    /**
     * 発話区間の一覧 [rawIntervals]（開始・終了ミリ秒のペア）にパディングを適用して [Chunk] のリストを返す。
     *
     * @param rawIntervals パディング前の発話区間（`first = startMs`, `second = endMs`）。
     * @param prePaddingMs 発話の前に含める余白（ミリ秒、0 以上）。
     * @param postPaddingMs 発話の後に含める余白（ミリ秒、0 以上）。
     * @param durationMs 音声全体の長さ（ミリ秒）。
     * @param origin 生成するチャンクの [ChunkOrigin]。
     */
    fun applyPadding(
        rawIntervals: List<Pair<Long, Long>>,
        prePaddingMs: Int,
        postPaddingMs: Int,
        durationMs: Long,
        origin: ChunkOrigin = ChunkOrigin.AUTO,
    ): List<Chunk> {
        if (rawIntervals.isEmpty()) return emptyList()
        val sorted = rawIntervals
            .filter { (start, end) -> start < end }
            .sortedWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
        if (sorted.isEmpty()) return emptyList()

        val maxEndInIntervals = sorted.maxOf { it.second }
        val effectiveDurationMs = if (durationMs > 0L) durationMs else maxEndInIntervals
        if (effectiveDurationMs <= 0L) return emptyList()

        val preMs = prePaddingMs.coerceAtLeast(0).toLong()
        val postMs = postPaddingMs.coerceAtLeast(0).toLong()

        val result = ArrayList<Chunk>(sorted.size)
        for (i in sorted.indices) {
            val rawStart = sorted[i].first.coerceIn(0L, effectiveDurationMs)
            val rawEnd = sorted[i].second.coerceIn(rawStart, effectiveDurationMs)
            if (rawStart >= effectiveDurationMs || rawEnd <= rawStart) continue

            val minAllowedStart = if (i == 0) {
                0L
            } else {
                val prevRawEnd = sorted[i - 1].second.coerceIn(0L, rawStart)
                (prevRawEnd + rawStart) / 2L
            }

            val maxAllowedEnd = if (i == sorted.lastIndex) {
                effectiveDurationMs
            } else {
                val nextRawStart = sorted[i + 1].first.coerceIn(rawEnd, effectiveDurationMs)
                min(effectiveDurationMs, (rawEnd + nextRawStart) / 2L)
            }

            val paddedStart = max(minAllowedStart, rawStart - preMs).coerceIn(0L, rawStart)
            val paddedEnd = min(maxAllowedEnd, rawEnd + postMs)
                .coerceIn(max(rawEnd, paddedStart + 1L), effectiveDurationMs)

            if (paddedStart < paddedEnd) {
                result.add(
                    Chunk(
                        id = ChunkId((result.size + 1).toLong()),
                        startMs = paddedStart,
                        endMs = paddedEnd,
                        origin = origin,
                    ),
                )
            }
        }
        return result
    }

    /**
     * [SegmentationParams] の `prePaddingMs` / `postPaddingMs` を使ってパディングを適用する。
     */
    fun applyPadding(
        rawIntervals: List<Pair<Long, Long>>,
        params: SegmentationParams,
        durationMs: Long,
        origin: ChunkOrigin = ChunkOrigin.AUTO,
    ): List<Chunk> = applyPadding(
        rawIntervals = rawIntervals,
        prePaddingMs = params.prePaddingMs,
        postPaddingMs = params.postPaddingMs,
        durationMs = durationMs,
        origin = origin,
    )
}

/**
 * 端末内の発話区間検出（Silero VAD + エネルギー適応）によるチャンク分割器（§7.3.3 手順 1〜7）。
 *
 * - `speechProbability`（Silero VAD の 10ms ごとの発話確率 `0.0f..1.0f`）が与えられた場合は、
 *   ニューラル VAD の確率ヒステリシスを主判定として発話区間を抽出し、さらにファイル中程（開始 30 秒以降）の
 *   非発話フレームのエネルギー分布から BGM の有無を自動判定（[detectBgmPresence]）してポーズ閾値やパディングを最適化する。
 * - `speechProbability` が `null` の場合は、従来のエネルギー VAD（[EnergyVoiceActivityDetector]）による処理を実行する。
 *
 * @param vad エネルギーベースの発話フレーム検出器（既定は [EnergyVoiceActivityDetector]）。
 * @param defaultBinMs 1 フレーム（ビン）の時間長（ミリ秒、既定 10ms）。
 */
class ChunkSegmenter(
    private val vad: VoiceActivityDetector = EnergyVoiceActivityDetector(),
    private val defaultBinMs: Int = DEFAULT_BIN_MS,
) {
    private data class FrameInterval(
        val startFrame: Int,
        val endFrame: Int,
    ) {
        val frameCount: Int get() = endFrame - startFrame
        fun durationMs(binMs: Int): Int = frameCount * binMs
    }

    /**
     * フレームごとの対数エネルギー [logEnergyDb]（および任意の Silero VAD 発話確率 [speechProbability]）から
     * 自動分割チャンクを生成する。
     *
     * @param logEnergyDb フレームごとの対数エネルギー（dBFS）。
     * @param params 分割パラメータ。
     * @param binMs 1 フレームの長さ（ミリ秒）。
     * @param durationMs 音声全体の長さ（ミリ秒）。省略時は `logEnergyDb.size * binMs`。
     * @param speechProbability フレームごとの発話確率（`0.0f..1.0f`、Silero VAD 出力。省略時はエネルギー VAD）。
     */
    fun segment(
        logEnergyDb: FloatArray,
        params: SegmentationParams = SegmentationParams(),
        binMs: Int = defaultBinMs,
        durationMs: Long = logEnergyDb.size.toLong() * binMs,
        speechProbability: FloatArray? = null,
    ): List<Chunk> {
        require(binMs > 0) { "binMs must be > 0: $binMs" }
        if (logEnergyDb.isEmpty() || durationMs <= 0L) return emptyList()

        val validProbs = speechProbability?.takeIf { it.size == logEnergyDb.size }
        val rawIntervals = extractSpeechIntervalsWithoutPadding(
            logEnergyDb = logEnergyDb,
            params = params,
            binMs = binMs,
            speechProbability = validProbs,
        )
        val hasBgm = validProbs != null && detectBgmPresence(
            logEnergyDb = logEnergyDb,
            speechProbability = validProbs,
            binMs = binMs,
        )
        val effectivePrePaddingMs = if (hasBgm) {
            min(params.prePaddingMs, BGM_MAX_PRE_PADDING_MS)
        } else {
            params.prePaddingMs
        }
        val effectivePostPaddingMs = if (hasBgm) {
            min(params.postPaddingMs, BGM_MAX_POST_PADDING_MS)
        } else {
            params.postPaddingMs
        }
        return ChunkPadding.applyPadding(
            rawIntervals = rawIntervals,
            prePaddingMs = effectivePrePaddingMs,
            postPaddingMs = effectivePostPaddingMs,
            durationMs = durationMs,
            origin = ChunkOrigin.AUTO,
        )
    }

    /**
     * 手順 1〜6 までを実行し、パディング付与前の発話区間（`startMs to endMs`）を返す。
     */
    fun extractSpeechIntervalsWithoutPadding(
        logEnergyDb: FloatArray,
        params: SegmentationParams = SegmentationParams(),
        binMs: Int = defaultBinMs,
        speechProbability: FloatArray? = null,
    ): List<Pair<Long, Long>> {
        require(binMs > 0) { "binMs must be > 0: $binMs" }
        if (logEnergyDb.isEmpty()) return emptyList()

        val validProbs = speechProbability?.takeIf { it.size == logEnergyDb.size }
        if (validProbs != null) {
            return extractSpeechIntervalsWithSilero(
                logEnergyDb = logEnergyDb,
                speechProbability = validProbs,
                params = params,
                binMs = binMs,
            )
        }

        // 手順 1〜3: メディアンフィルタ + 適応閾値 + ヒステリシス
        val vadResult = vad.detect(logEnergyDb, params)
        val smoothed = vadResult.smoothedEnergyDb
        val thresholdDb = vadResult.thresholdDb

        // 手順 4: 連続する発話フレームを抽出し、minSilenceMs 未満の短い無音ギャップを結合する
        val bridged = extractAndBridgeIntervals(
            speechFrames = vadResult.speechFrames,
            minSilenceMs = params.minSilenceMs,
            binMs = binMs,
        )
        if (bridged.isEmpty()) return emptyList()

        // 手順 5: minSpeechMs 未満の短い発話の統合・選別
        val afterShortSpeech = resolveShortSpeechIntervals(
            intervals = bridged,
            rawEnergyDb = logEnergyDb,
            smoothedEnergyDb = smoothed,
            thresholdDb = thresholdDb,
            params = params,
            binMs = binMs,
        )
        if (afterShortSpeech.isEmpty()) return emptyList()

        // 手順 6: maxChunkMs を超える区間の再帰的な谷分割
        val splitIntervals = ArrayList<FrameInterval>(afterShortSpeech.size)
        for (interval in afterShortSpeech) {
            splitLongIntervalRecursive(
                interval = interval,
                smoothedEnergyDb = smoothed,
                thresholdDb = thresholdDb,
                params = params,
                binMs = binMs,
                out = splitIntervals,
            )
        }

        return splitIntervals.map { interval ->
            (interval.startFrame.toLong() * binMs) to (interval.endFrame.toLong() * binMs)
        }
    }

    private fun extractSpeechIntervalsWithSilero(
        logEnergyDb: FloatArray,
        speechProbability: FloatArray,
        params: SegmentationParams,
        binMs: Int,
    ): List<Pair<Long, Long>> {
        val n = logEnergyDb.size
        val smoothedEnergyDb = EnergyVoiceActivityDetector.medianFilter5(logEnergyDb)
        val smoothedProb = medianFilter3(speechProbability)
        val (onsetProb, offsetProb) = computeSileroThresholds(params.thresholdRatio)

        val speechFrames = BooleanArray(n)
        var inSpeech = false
        for (i in 0 until n) {
            val p = smoothedProb[i]
            if (!inSpeech && p >= onsetProb) {
                inSpeech = true
            } else if (inSpeech && p < offsetProb) {
                inSpeech = false
            }
            speechFrames[i] = inSpeech
        }

        val hasBgm = detectBgmPresence(
            logEnergyDb = logEnergyDb,
            speechProbability = speechProbability,
            binMs = binMs,
        )
        val effectiveMinSilenceMs = if (hasBgm) {
            val baseSilenceMs = if (params.minSilenceMs == DEFAULT_PARAMS_MIN_SILENCE_MS) {
                BGM_REFERENCE_MIN_SILENCE_MS
            } else {
                params.minSilenceMs
            }
            (baseSilenceMs * BGM_MIN_SILENCE_SCALE).toInt().coerceAtLeast(MIN_BGM_SILENCE_MS)
        } else {
            params.minSilenceMs
        }

        val bridged = extractAndBridgeIntervals(
            speechFrames = speechFrames,
            minSilenceMs = effectiveMinSilenceMs,
            binMs = binMs,
        )
        if (bridged.isEmpty()) return emptyList()

        val afterShortSpeech = resolveShortSpeechIntervalsSilero(
            intervals = bridged,
            speechProbability = speechProbability,
            smoothedProb = smoothedProb,
            onsetProb = onsetProb,
            effectiveMinSilenceMs = effectiveMinSilenceMs,
            params = params,
            binMs = binMs,
        )
        if (afterShortSpeech.isEmpty()) return emptyList()

        // 複合スコア（発話確率の重み + 平滑化エネルギー dB）を用いて、非発話かつ低エネルギーの谷で長尺区間を分割する
        val compositeValleyScore = FloatArray(n) { i ->
            smoothedProb[i] * SILERO_VALLEY_PROB_WEIGHT_DB + smoothedEnergyDb[i]
        }
        val lowScoreCeiling = offsetProb * SILERO_VALLEY_PROB_WEIGHT_DB - 20f

        val splitIntervals = ArrayList<FrameInterval>(afterShortSpeech.size)
        for (interval in afterShortSpeech) {
            splitLongIntervalRecursive(
                interval = interval,
                smoothedEnergyDb = compositeValleyScore,
                thresholdDb = lowScoreCeiling - EnergyVoiceActivityDetector.HYSTERESIS_ONSET_OFFSET_DB,
                params = params,
                binMs = binMs,
                out = splitIntervals,
            )
        }

        return splitIntervals.map { interval ->
            (interval.startFrame.toLong() * binMs) to (interval.endFrame.toLong() * binMs)
        }
    }

    private fun resolveShortSpeechIntervalsSilero(
        intervals: List<FrameInterval>,
        speechProbability: FloatArray,
        smoothedProb: FloatArray,
        onsetProb: Float,
        effectiveMinSilenceMs: Int,
        params: SegmentationParams,
        binMs: Int,
    ): List<FrameInterval> {
        val list = intervals.toMutableList()
        val standaloneWordMinMs = min(params.minSpeechMs, SILERO_STANDALONE_WORD_MIN_MS)
        val microFragmentMaxGapMs = min(params.mergeGapMs, max(effectiveMinSilenceMs + 120, 350))

        var index = 0
        while (index < list.size) {
            val current = list[index]
            val durationMs = current.durationMs(binMs)
            if (durationMs >= params.minSpeechMs) {
                index++
                continue
            }

            val peakProb = maxPeakProbability(current, speechProbability, smoothedProb)

            // Silero VAD で十分な発話確率（>= onsetProb）かつ 150ms 以上の区間は、
            // 1 音節の単語（「好」「一」「Yes」など）として隣のチャンクへ強制結合せずに独立保持する
            if (durationMs >= standaloneWordMinMs && peakProb >= onsetProb) {
                index++
                continue
            }

            val prevGapMs = if (index > 0) {
                (current.startFrame - list[index - 1].endFrame) * binMs
            } else {
                Int.MAX_VALUE
            }
            val nextGapMs = if (index + 1 < list.size) {
                (list[index + 1].startFrame - current.endFrame) * binMs
            } else {
                Int.MAX_VALUE
            }
            val nearestGapMs = min(prevGapMs, nextGapMs)

            if (nearestGapMs < microFragmentMaxGapMs) {
                if (prevGapMs <= nextGapMs) {
                    val prev = list[index - 1]
                    list[index - 1] = FrameInterval(prev.startFrame, current.endFrame)
                    list.removeAt(index)
                    index = (index - 1).coerceAtLeast(0)
                } else {
                    val next = list[index + 1]
                    list[index + 1] = FrameInterval(current.startFrame, next.endFrame)
                    list.removeAt(index)
                }
            } else if (peakProb < min(0.65f, onsetProb + 0.15f) || durationMs < 100) {
                list.removeAt(index)
            } else {
                index++
            }
        }
        return list
    }

    private fun maxPeakProbability(
        interval: FrameInterval,
        rawProb: FloatArray,
        smoothedProb: FloatArray,
    ): Float {
        var maxP = 0f
        for (f in interval.startFrame until interval.endFrame) {
            val v = max(rawProb[f], smoothedProb[f])
            if (v > maxP) maxP = v
        }
        return maxP
    }

    /**
     * チャンク区間 `[chunkStartMs, chunkEndMs]` を [splitPositionMs] で前後に分割し、
     * それぞれの半区間から前後の無音（または BGM のみの部分）をトリミングした区間リスト（最大 2 件、片側が無音のみなら 1 件）を返す。
     *
     * @param logEnergyDb フレームごとの対数エネルギー（dBFS）。
     * @param chunkStartMs 分割対象チャンクの開始時刻（ミリ秒）。
     * @param chunkEndMs 分割対象チャンクの終了時刻（ミリ秒）。
     * @param splitPositionMs 分割位置の時刻（ミリ秒、`chunkStartMs < splitPositionMs < chunkEndMs`）。
     * @param params 分割・パディングのパラメータ。
     * @param binMs 1 フレームの長さ（ミリ秒）。
     * @param speechProbability フレームごとの発話確率（`0.0f..1.0f`、Silero VAD 出力。省略時はエネルギーベース）。
     * @return トリミング後のチャンク区間（`startMs to endMs`）のリスト（1 件または 2 件）。
     */
    fun splitAndTrimSilencePieces(
        logEnergyDb: FloatArray,
        chunkStartMs: Long,
        chunkEndMs: Long,
        splitPositionMs: Long,
        params: SegmentationParams = SegmentationParams(),
        binMs: Int = defaultBinMs,
        speechProbability: FloatArray? = null,
    ): List<Pair<Long, Long>> {
        require(binMs > 0) { "binMs must be > 0: $binMs" }
        require(chunkStartMs < splitPositionMs && splitPositionMs < chunkEndMs) {
            "splitPositionMs ($splitPositionMs) must be strictly between chunkStartMs ($chunkStartMs) and chunkEndMs ($chunkEndMs)"
        }
        val firstRaw = chunkStartMs to splitPositionMs
        val secondRaw = splitPositionMs to chunkEndMs
        if (logEnergyDb.isEmpty()) {
            return listOf(firstRaw, secondRaw)
        }

        val validProbs = speechProbability?.takeIf { it.size == logEnergyDb.size }
        if (validProbs != null) {
            val smoothedProb = medianFilter3(validProbs)
            val (_, offsetProb) = computeSileroThresholds(params.thresholdRatio)
            val firstTrimmed = trimPieceSilenceWithSileroOrNull(
                rawProb = validProbs,
                smoothedProb = smoothedProb,
                offsetProb = offsetProb,
                pieceStartMs = chunkStartMs,
                pieceEndMs = splitPositionMs,
                isFirstHalf = true,
                binMs = binMs,
            )
            val secondTrimmed = trimPieceSilenceWithSileroOrNull(
                rawProb = validProbs,
                smoothedProb = smoothedProb,
                offsetProb = offsetProb,
                pieceStartMs = splitPositionMs,
                pieceEndMs = chunkEndMs,
                isFirstHalf = false,
                binMs = binMs,
            )
            return when {
                firstTrimmed != null && secondTrimmed != null -> listOf(firstTrimmed, secondTrimmed)
                firstTrimmed != null -> listOf(firstTrimmed)
                secondTrimmed != null -> listOf(secondTrimmed)
                else -> listOf(firstRaw, secondRaw)
            }
        }

        val smoothed = EnergyVoiceActivityDetector.medianFilter5(logEnergyDb)
        val (effectiveThresholdDb, localS) = computeSplitSilenceThresholdDb(
            smoothed = smoothed,
            chunkStartMs = chunkStartMs,
            chunkEndMs = chunkEndMs,
            splitPositionMs = splitPositionMs,
            params = params,
            binMs = binMs,
        )
        val firstTrimmed = trimPieceSilenceOrNull(
            rawEnergyDb = logEnergyDb,
            smoothed = smoothed,
            effectiveThresholdDb = effectiveThresholdDb,
            localS = localS,
            pieceStartMs = chunkStartMs,
            pieceEndMs = splitPositionMs,
            isFirstHalf = true,
            binMs = binMs,
        )
        val secondTrimmed = trimPieceSilenceOrNull(
            rawEnergyDb = logEnergyDb,
            smoothed = smoothed,
            effectiveThresholdDb = effectiveThresholdDb,
            localS = localS,
            pieceStartMs = splitPositionMs,
            pieceEndMs = chunkEndMs,
            isFirstHalf = false,
            binMs = binMs,
        )
        return when {
            firstTrimmed != null && secondTrimmed != null -> listOf(firstTrimmed, secondTrimmed)
            firstTrimmed != null -> listOf(firstTrimmed)
            secondTrimmed != null -> listOf(secondTrimmed)
            else -> listOf(firstRaw, secondRaw)
        }
    }

    /**
     * チャンク区間 `[chunkStartMs, chunkEndMs]` を [splitPositionMs] で前後に分割し、
     * それぞれの半区間から前後の無音部分をトリミングした 2 つの区間（前半と後半の `startMs to endMs`）を返す。
     *
     * 半区間内に発話フレームが 1 つも見つからない場合は、元の半区間をそのまま返す。
     */
    fun splitAndTrimSilence(
        logEnergyDb: FloatArray,
        chunkStartMs: Long,
        chunkEndMs: Long,
        splitPositionMs: Long,
        params: SegmentationParams = SegmentationParams(),
        binMs: Int = defaultBinMs,
        speechProbability: FloatArray? = null,
    ): Pair<Pair<Long, Long>, Pair<Long, Long>> {
        require(binMs > 0) { "binMs must be > 0: $binMs" }
        require(chunkStartMs < splitPositionMs && splitPositionMs < chunkEndMs) {
            "splitPositionMs ($splitPositionMs) must be strictly between chunkStartMs ($chunkStartMs) and chunkEndMs ($chunkEndMs)"
        }
        val firstRaw = chunkStartMs to splitPositionMs
        val secondRaw = splitPositionMs to chunkEndMs
        if (logEnergyDb.isEmpty()) {
            return firstRaw to secondRaw
        }

        val validProbs = speechProbability?.takeIf { it.size == logEnergyDb.size }
        if (validProbs != null) {
            val smoothedProb = medianFilter3(validProbs)
            val (_, offsetProb) = computeSileroThresholds(params.thresholdRatio)
            val firstTrimmed = trimPieceSilenceWithSileroOrNull(
                rawProb = validProbs,
                smoothedProb = smoothedProb,
                offsetProb = offsetProb,
                pieceStartMs = chunkStartMs,
                pieceEndMs = splitPositionMs,
                isFirstHalf = true,
                binMs = binMs,
            )
            val secondTrimmed = trimPieceSilenceWithSileroOrNull(
                rawProb = validProbs,
                smoothedProb = smoothedProb,
                offsetProb = offsetProb,
                pieceStartMs = splitPositionMs,
                pieceEndMs = chunkEndMs,
                isFirstHalf = false,
                binMs = binMs,
            )
            return (firstTrimmed ?: firstRaw) to (secondTrimmed ?: secondRaw)
        }

        val smoothed = EnergyVoiceActivityDetector.medianFilter5(logEnergyDb)
        val (effectiveThresholdDb, localS) = computeSplitSilenceThresholdDb(
            smoothed = smoothed,
            chunkStartMs = chunkStartMs,
            chunkEndMs = chunkEndMs,
            splitPositionMs = splitPositionMs,
            params = params,
            binMs = binMs,
        )
        val firstTrimmed = trimPieceSilenceOrNull(
            rawEnergyDb = logEnergyDb,
            smoothed = smoothed,
            effectiveThresholdDb = effectiveThresholdDb,
            localS = localS,
            pieceStartMs = chunkStartMs,
            pieceEndMs = splitPositionMs,
            isFirstHalf = true,
            binMs = binMs,
        )
        val secondTrimmed = trimPieceSilenceOrNull(
            rawEnergyDb = logEnergyDb,
            smoothed = smoothed,
            effectiveThresholdDb = effectiveThresholdDb,
            localS = localS,
            pieceStartMs = splitPositionMs,
            pieceEndMs = chunkEndMs,
            isFirstHalf = false,
            binMs = binMs,
        )
        return (firstTrimmed ?: firstRaw) to (secondTrimmed ?: secondRaw)
    }

    /**
     * 指定した範囲 `[rangeStartMs, rangeEndMs)` が「無音判定の感度（[SegmentationParams.thresholdRatio]）」に照らして
     * 無音（または BGM のみで発話なし）であるかを判定する。
     *
     * 特徴量が空の場合は、無音とは判定できないため `false` を返す。
     */
    fun isSilenceOnlyInRange(
        logEnergyDb: FloatArray,
        rangeStartMs: Long,
        rangeEndMs: Long,
        params: SegmentationParams = SegmentationParams(),
        binMs: Int = defaultBinMs,
        speechProbability: FloatArray? = null,
    ): Boolean {
        require(binMs > 0) { "binMs must be > 0: $binMs" }
        require(rangeStartMs < rangeEndMs) {
            "rangeStartMs ($rangeStartMs) must be < rangeEndMs ($rangeEndMs)"
        }
        if (logEnergyDb.isEmpty()) {
            return false
        }

        val validProbs = speechProbability?.takeIf { it.size == logEnergyDb.size }
        if (validProbs != null) {
            val smoothedProb = medianFilter3(validProbs)
            val totalFrames = smoothedProb.size
            val startFrame = (rangeStartMs / binMs).toInt().coerceIn(0, totalFrames)
            val endFrame = ((rangeEndMs + binMs - 1L) / binMs).toInt().coerceIn(startFrame, totalFrames)
            if (startFrame >= endFrame) {
                return false
            }
            val (_, offsetProb) = computeSileroThresholds(params.thresholdRatio)
            var localMaxProb = 0f
            for (f in startFrame until endFrame) {
                if (smoothedProb[f] > localMaxProb) {
                    localMaxProb = smoothedProb[f]
                }
            }
            return localMaxProb < offsetProb
        }

        val smoothed = EnergyVoiceActivityDetector.medianFilter5(logEnergyDb)
        val totalFrames = smoothed.size
        val startFrame = (rangeStartMs / binMs).toInt().coerceIn(0, totalFrames)
        val endFrame = ((rangeEndMs + binMs - 1L) / binMs).toInt().coerceIn(startFrame, totalFrames)
        if (startFrame >= endFrame) {
            return false
        }

        val globalSorted = FloatArray(totalFrames) { i ->
            smoothed[i].coerceAtLeast(DbfsQuantizer.FLOOR_DB)
        }.apply { sort() }
        val globalN = EnergyVoiceActivityDetector.percentileSorted(globalSorted, 0.10f)
        val globalS = EnergyVoiceActivityDetector.percentileSorted(globalSorted, 0.90f)
        val ratio = params.thresholdRatio.coerceIn(0f, 1f)
        val thresholdDb = (globalN + ratio * (globalS - globalN)).coerceIn(
            EnergyVoiceActivityDetector.MIN_THRESHOLD_DB,
            EnergyVoiceActivityDetector.MAX_THRESHOLD_DB,
        )

        var localMax = DbfsQuantizer.FLOOR_DB
        for (f in startFrame until endFrame) {
            val e = smoothed[f].coerceAtLeast(DbfsQuantizer.FLOOR_DB)
            if (e > localMax) {
                localMax = e
            }
        }
        if (globalS - globalN < 3f && localMax < -45f) {
            return true
        }
        return localMax < thresholdDb
    }

    /**
     * 指定した範囲 `[rangeStartMs, rangeEndMs]` 内に発話領域がある場合は左右の非発話領域（無音・BGM のみ）をトリミングした区間を返し、
     * 発話領域がない場合（または特徴量が空の場合）は選択範囲 `[rangeStartMs, rangeEndMs]` をそのまま返す。
     */
    fun trimSilenceInRange(
        logEnergyDb: FloatArray,
        rangeStartMs: Long,
        rangeEndMs: Long,
        params: SegmentationParams = SegmentationParams(),
        binMs: Int = defaultBinMs,
        speechProbability: FloatArray? = null,
    ): Pair<Long, Long> {
        require(binMs > 0) { "binMs must be > 0: $binMs" }
        require(rangeStartMs < rangeEndMs) {
            "rangeStartMs ($rangeStartMs) must be < rangeEndMs ($rangeEndMs)"
        }
        val rawRange = rangeStartMs to rangeEndMs
        if (logEnergyDb.isEmpty()) {
            return rawRange
        }

        val validProbs = speechProbability?.takeIf { it.size == logEnergyDb.size }
        if (validProbs != null) {
            val smoothedProb = medianFilter3(validProbs)
            val (_, offsetProb) = computeSileroThresholds(params.thresholdRatio)
            return trimRangeSilenceWithSilero(
                rawProb = validProbs,
                smoothedProb = smoothedProb,
                offsetProb = offsetProb,
                rangeStartMs = rangeStartMs,
                rangeEndMs = rangeEndMs,
                binMs = binMs,
            ) ?: rawRange
        }

        val smoothed = EnergyVoiceActivityDetector.medianFilter5(logEnergyDb)
        val totalFrames = smoothed.size
        val startFrame = (rangeStartMs / binMs).toInt().coerceIn(0, totalFrames)
        val endFrame = ((rangeEndMs + binMs - 1L) / binMs).toInt().coerceIn(startFrame, totalFrames)
        if (startFrame >= endFrame) {
            return rawRange
        }

        val globalSorted = FloatArray(totalFrames) { i ->
            smoothed[i].coerceAtLeast(DbfsQuantizer.FLOOR_DB)
        }.apply { sort() }
        val globalN = EnergyVoiceActivityDetector.percentileSorted(globalSorted, 0.10f)
        val globalS = EnergyVoiceActivityDetector.percentileSorted(globalSorted, 0.90f)
        val ratio = params.thresholdRatio.coerceIn(0f, 1f)
        val globalThresholdDb = (globalN + ratio * (globalS - globalN)).coerceIn(-55f, -25f)

        val localCount = endFrame - startFrame
        val localSorted = FloatArray(localCount) { idx ->
            smoothed[startFrame + idx].coerceAtLeast(DbfsQuantizer.FLOOR_DB)
        }.apply { sort() }
        val localN = EnergyVoiceActivityDetector.percentileSorted(localSorted, 0.10f)
        val localS = EnergyVoiceActivityDetector.percentileSorted(localSorted, 0.90f)
        val localMin = localSorted.first()
        val localMax = localSorted.last()

        val hasSpeech = localMax >= globalThresholdDb ||
            (localMax - localN >= 6f && localMax >= max(globalN + 6f, -50f))
        if (!hasSpeech) {
            return rawRange
        }
        if (localMax - localMin < 4f && localMin >= globalThresholdDb) {
            return rawRange
        }

        val localRatio = max(ratio, 0.35f)
        val localThresholdDb = if (localS - localN >= 5f) {
            (localN + localRatio * (localS - localN)).coerceIn(-55f, max(-55f, localS - 3f))
        } else {
            globalThresholdDb
        }
        val effectiveThresholdDb = max(globalThresholdDb, localThresholdDb).coerceAtMost(localMax - 2f)

        var firstSpeechFrame = -1
        for (f in startFrame until endFrame) {
            if (smoothed[f] >= effectiveThresholdDb) {
                firstSpeechFrame = f
                break
            }
        }
        var lastSpeechFrame = -1
        for (f in (endFrame - 1) downTo startFrame) {
            if (smoothed[f] >= effectiveThresholdDb) {
                lastSpeechFrame = f
                break
            }
        }
        if (firstSpeechFrame < 0 || lastSpeechFrame < firstSpeechFrame) {
            return rawRange
        }

        val rawSpeechStartMs = (firstSpeechFrame.toLong() * binMs).coerceIn(rangeStartMs, rangeEndMs)
        val rawSpeechEndMs = ((lastSpeechFrame + 1).toLong() * binMs).coerceIn(rawSpeechStartMs, rangeEndMs)
        if (rawSpeechEndMs <= rawSpeechStartMs) {
            return rawRange
        }

        val leadingSilenceMs = (rawSpeechStartMs - rangeStartMs).coerceAtLeast(0L)
        val trailingSilenceMs = (rangeEndMs - rawSpeechEndMs).coerceAtLeast(0L)
        val preMs = min(30L, leadingSilenceMs / 4L)
        val postMs = min(30L, trailingSilenceMs / 4L)
        val trimmedStartMs = (rawSpeechStartMs - preMs).coerceIn(rangeStartMs, rawSpeechStartMs)
        val trimmedEndMs = (rawSpeechEndMs + postMs).coerceIn(max(rawSpeechEndMs, trimmedStartMs + 1L), rangeEndMs)
        return trimmedStartMs to trimmedEndMs
    }

    private fun trimRangeSilenceWithSilero(
        rawProb: FloatArray,
        smoothedProb: FloatArray,
        offsetProb: Float,
        rangeStartMs: Long,
        rangeEndMs: Long,
        binMs: Int,
    ): Pair<Long, Long>? {
        val totalFrames = smoothedProb.size
        if (totalFrames == 0) return null
        val startFrame = (rangeStartMs / binMs).toInt().coerceIn(0, totalFrames)
        val endFrame = ((rangeEndMs + binMs - 1L) / binMs).toInt().coerceIn(startFrame, totalFrames)
        if (startFrame >= endFrame) return null

        var localMaxProb = 0f
        for (f in startFrame until endFrame) {
            val p = max(rawProb[f], smoothedProb[f])
            if (p > localMaxProb) localMaxProb = p
        }
        if (localMaxProb < SILERO_NON_SPEECH_PROB_CEILING) {
            return null
        }

        val effectiveProb = min(offsetProb, localMaxProb * 0.55f).coerceIn(SILERO_NON_SPEECH_PROB_CEILING, 0.50f)
        val count = endFrame - startFrame
        val speechFlags = BooleanArray(count) { i ->
            max(rawProb[startFrame + i], smoothedProb[startFrame + i]) >= effectiveProb
        }
        val bridged = extractAndBridgeIntervals(
            speechFrames = speechFlags,
            minSilenceMs = 40,
            binMs = binMs,
        )
        if (bridged.isEmpty()) return null

        val runs = bridged.map { localRun ->
            FrameInterval(
                startFrame = startFrame + localRun.startFrame,
                endFrame = startFrame + localRun.endFrame,
            )
        }.toMutableList()

        if (runs.size >= 2) {
            val maxRunPeak = runs.maxOf { maxPeakProbability(it, rawProb, smoothedProb) }
            while (runs.size >= 2) {
                val first = runs.first()
                if (first.durationMs(binMs) < 60 && maxPeakProbability(first, rawProb, smoothedProb) < maxRunPeak * 0.65f) {
                    runs.removeAt(0)
                } else {
                    break
                }
            }
            while (runs.size >= 2) {
                val last = runs.last()
                if (last.durationMs(binMs) < 60 && maxPeakProbability(last, rawProb, smoothedProb) < maxRunPeak * 0.65f) {
                    runs.removeAt(runs.lastIndex)
                } else {
                    break
                }
            }
        }
        if (runs.isEmpty()) return null

        val firstSpeechFrame = runs.first().startFrame
        val lastSpeechFrame = runs.last().endFrame - 1
        if (lastSpeechFrame < firstSpeechFrame) return null

        val rawSpeechStartMs = (firstSpeechFrame.toLong() * binMs).coerceIn(rangeStartMs, rangeEndMs)
        val rawSpeechEndMs = ((lastSpeechFrame + 1).toLong() * binMs).coerceIn(rawSpeechStartMs, rangeEndMs)
        if (rawSpeechEndMs <= rawSpeechStartMs) return null

        val leadingSilenceMs = (rawSpeechStartMs - rangeStartMs).coerceAtLeast(0L)
        val trailingSilenceMs = (rangeEndMs - rawSpeechEndMs).coerceAtLeast(0L)
        val preMs = min(30L, leadingSilenceMs / 4L)
        val postMs = min(30L, trailingSilenceMs / 4L)
        val trimmedStartMs = (rawSpeechStartMs - preMs).coerceIn(rangeStartMs, rawSpeechStartMs)
        val trimmedEndMs = (rawSpeechEndMs + postMs).coerceIn(max(rawSpeechEndMs, trimmedStartMs + 1L), rangeEndMs)
        return trimmedStartMs to trimmedEndMs
    }

    private fun trimPieceSilenceWithSileroOrNull(
        rawProb: FloatArray,
        smoothedProb: FloatArray,
        offsetProb: Float,
        pieceStartMs: Long,
        pieceEndMs: Long,
        isFirstHalf: Boolean,
        binMs: Int,
    ): Pair<Long, Long>? {
        if (pieceEndMs <= pieceStartMs) return null
        val totalFrames = smoothedProb.size
        if (totalFrames == 0) return null

        val startFrame = (pieceStartMs / binMs).toInt().coerceIn(0, totalFrames)
        val endFrame = ((pieceEndMs + binMs - 1L) / binMs).toInt().coerceIn(startFrame, totalFrames)
        if (startFrame >= endFrame) return null

        var maxProb = 0f
        for (f in startFrame until endFrame) {
            val p = max(rawProb[f], smoothedProb[f])
            if (p > maxProb) maxProb = p
        }
        if (maxProb < offsetProb) {
            return null
        }

        val effectiveProb = min(offsetProb, maxProb * 0.55f).coerceIn(SILERO_NON_SPEECH_PROB_CEILING, 0.50f)
        val pieceFrameCount = endFrame - startFrame
        val pieceSpeech = BooleanArray(pieceFrameCount) { i ->
            max(rawProb[startFrame + i], smoothedProb[startFrame + i]) >= effectiveProb
        }

        val bridgedLocal = extractAndBridgeIntervals(
            speechFrames = pieceSpeech,
            minSilenceMs = 40,
            binMs = binMs,
        )
        if (bridgedLocal.isEmpty()) return null

        val runs = bridgedLocal.mapNotNull { localRun ->
            val globalRun = FrameInterval(
                startFrame = startFrame + localRun.startFrame,
                endFrame = startFrame + localRun.endFrame,
            )
            val durationMs = globalRun.durationMs(binMs)
            val peakP = maxPeakProbability(globalRun, rawProb, smoothedProb)
            if (durationMs < 60 && peakP < offsetProb + 0.15f) {
                null
            } else {
                globalRun
            }
        }.toMutableList()

        if (runs.isEmpty()) return null

        if (runs.size >= 2) {
            val maxRunDurationMs = runs.maxOf { it.durationMs(binMs) }
            if (isFirstHalf) {
                val boundaryRun = runs.last()
                val prevRun = runs[runs.size - 2]
                val boundaryDurationMs = boundaryRun.durationMs(binMs)
                val gapMs = (boundaryRun.startFrame - prevRun.endFrame) * binMs
                if (boundaryDurationMs < 180 &&
                    boundaryDurationMs < maxRunDurationMs * 0.30f &&
                    gapMs >= 80
                ) {
                    runs.removeAt(runs.lastIndex)
                }
            } else {
                val boundaryRun = runs.first()
                val nextRun = runs[1]
                val boundaryDurationMs = boundaryRun.durationMs(binMs)
                val gapMs = (nextRun.startFrame - boundaryRun.endFrame) * binMs
                if (boundaryDurationMs < 180 &&
                    boundaryDurationMs < maxRunDurationMs * 0.30f &&
                    gapMs >= 80
                ) {
                    runs.removeAt(0)
                }
            }
        }

        if (runs.isEmpty()) return null

        val firstSpeechFrame = runs.first().startFrame
        val lastSpeechFrame = runs.last().endFrame - 1
        if (lastSpeechFrame < firstSpeechFrame) return null

        val rawSpeechStartMs = (firstSpeechFrame.toLong() * binMs).coerceIn(pieceStartMs, pieceEndMs)
        val rawSpeechEndMs = ((lastSpeechFrame + 1).toLong() * binMs).coerceIn(rawSpeechStartMs, pieceEndMs)
        if (rawSpeechEndMs <= rawSpeechStartMs) return null

        val leadingSilenceMs = (rawSpeechStartMs - pieceStartMs).coerceAtLeast(0L)
        val trailingSilenceMs = (pieceEndMs - rawSpeechEndMs).coerceAtLeast(0L)
        val preMs = min(30L, leadingSilenceMs / 4L)
        val postMs = min(30L, trailingSilenceMs / 4L)
        val trimmedStartMs = (rawSpeechStartMs - preMs).coerceIn(pieceStartMs, rawSpeechStartMs)
        val trimmedEndMs = (rawSpeechEndMs + postMs).coerceIn(max(rawSpeechEndMs, trimmedStartMs + 1L), pieceEndMs)
        return trimmedStartMs to trimmedEndMs
    }

    private fun computeSplitSilenceThresholdDb(
        smoothed: FloatArray,
        chunkStartMs: Long,
        chunkEndMs: Long,
        splitPositionMs: Long,
        params: SegmentationParams,
        binMs: Int,
    ): Pair<Float, Float> {
        val totalFrames = smoothed.size
        val globalSorted = FloatArray(totalFrames) { i ->
            smoothed[i].coerceAtLeast(DbfsQuantizer.FLOOR_DB)
        }.apply { sort() }
        val globalN = EnergyVoiceActivityDetector.percentileSorted(globalSorted, 0.10f)
        val globalS = EnergyVoiceActivityDetector.percentileSorted(globalSorted, 0.90f)
        val ratio = params.thresholdRatio.coerceIn(0f, 1f)
        val globalThresholdDb = (globalN + ratio * (globalS - globalN)).coerceIn(-55f, -25f)

        val chunkStartFrame = (chunkStartMs / binMs).toInt().coerceIn(0, totalFrames)
        val chunkEndFrame = ((chunkEndMs + binMs - 1L) / binMs).toInt().coerceIn(chunkStartFrame, totalFrames)
        if (chunkStartFrame >= chunkEndFrame) {
            return globalThresholdDb to globalS
        }

        val chunkSorted = FloatArray(chunkEndFrame - chunkStartFrame) { idx ->
            smoothed[chunkStartFrame + idx].coerceAtLeast(DbfsQuantizer.FLOOR_DB)
        }.apply { sort() }
        val localN = EnergyVoiceActivityDetector.percentileSorted(chunkSorted, 0.10f)
        val localS = EnergyVoiceActivityDetector.percentileSorted(chunkSorted, 0.90f)
        val localRatio = max(ratio, 0.35f)
        val localThresholdDb = if (localS - localN >= 5f) {
            (localN + localRatio * (localS - localN)).coerceIn(-55f, max(-55f, localS - 4f))
        } else {
            globalThresholdDb
        }

        val localSilenceCeilingDb = (localS - 15f).coerceIn(-55f, -22f)

        val splitFrame = (splitPositionMs / binMs).toInt().coerceIn(chunkStartFrame, chunkEndFrame - 1)
        val valleyStart = (splitFrame - 3).coerceAtLeast(chunkStartFrame)
        val valleyEnd = (splitFrame + 3).coerceAtMost(chunkEndFrame - 1)
        var splitLocalDb = Float.POSITIVE_INFINITY
        for (f in valleyStart..valleyEnd) {
            val v = smoothed[f].coerceAtLeast(DbfsQuantizer.FLOOR_DB)
            if (v < splitLocalDb) {
                splitLocalDb = v
            }
        }
        val valleyThresholdDb = if (splitLocalDb.isFinite() && localS - splitLocalDb >= 5f) {
            (splitLocalDb + 0.40f * (localS - splitLocalDb)).coerceIn(-55f, max(-55f, localS - 4f))
        } else {
            globalThresholdDb
        }

        val effectiveThresholdDb = maxOf(
            globalThresholdDb,
            localThresholdDb,
            localSilenceCeilingDb,
            valleyThresholdDb,
        ).coerceAtMost(localS - 3f)

        return effectiveThresholdDb to localS
    }

    private fun trimPieceSilenceOrNull(
        rawEnergyDb: FloatArray,
        smoothed: FloatArray,
        effectiveThresholdDb: Float,
        localS: Float,
        pieceStartMs: Long,
        pieceEndMs: Long,
        isFirstHalf: Boolean,
        binMs: Int,
    ): Pair<Long, Long>? {
        if (pieceEndMs <= pieceStartMs) return null
        val totalFrames = smoothed.size
        if (totalFrames == 0) return null

        val startFrame = (pieceStartMs / binMs).toInt().coerceIn(0, totalFrames)
        val endFrame = ((pieceEndMs + binMs - 1L) / binMs).toInt().coerceIn(startFrame, totalFrames)
        if (startFrame >= endFrame) return null

        var maxSmoothed = Float.NEGATIVE_INFINITY
        var maxPeak = Float.NEGATIVE_INFINITY
        for (f in startFrame until endFrame) {
            val s = smoothed[f]
            if (s > maxSmoothed) maxSmoothed = s
            val p = max(rawEnergyDb[f], s)
            if (p > maxPeak) maxPeak = p
        }
        if (maxSmoothed < effectiveThresholdDb) {
            return null
        }

        val onset = effectiveThresholdDb + 1.5f
        val offset = effectiveThresholdDb
        val pieceFrameCount = endFrame - startFrame
        val pieceSpeech = BooleanArray(pieceFrameCount)
        var inSpeech = false
        var anyOnset = false
        for (i in 0 until pieceFrameCount) {
            val e = smoothed[startFrame + i]
            if (!inSpeech && e > onset) {
                inSpeech = true
                anyOnset = true
            } else if (inSpeech && e < offset) {
                inSpeech = false
            }
            pieceSpeech[i] = inSpeech
        }

        if (!anyOnset) {
            if (maxSmoothed >= effectiveThresholdDb && maxPeak >= localS - 8f) {
                for (i in 0 until pieceFrameCount) {
                    pieceSpeech[i] = smoothed[startFrame + i] >= effectiveThresholdDb
                }
            } else {
                return null
            }
        }

        val bridgedLocal = extractAndBridgeIntervals(
            speechFrames = pieceSpeech,
            minSilenceMs = 40,
            binMs = binMs,
        )
        if (bridgedLocal.isEmpty()) return null

        val runs = bridgedLocal.mapNotNull { localRun ->
            val globalRun = FrameInterval(
                startFrame = startFrame + localRun.startFrame,
                endFrame = startFrame + localRun.endFrame,
            )
            val durationMs = globalRun.durationMs(binMs)
            val peakDb = maxPeakEnergyDb(globalRun, rawEnergyDb, smoothed)
            if (durationMs < 60 && peakDb < effectiveThresholdDb + 5f) {
                null
            } else {
                globalRun
            }
        }.toMutableList()

        if (runs.isEmpty()) return null

        if (runs.size >= 2) {
            val maxRunDurationMs = runs.maxOf { it.durationMs(binMs) }
            if (isFirstHalf) {
                val boundaryRun = runs.last()
                val prevRun = runs[runs.size - 2]
                val boundaryDurationMs = boundaryRun.durationMs(binMs)
                val gapMs = (boundaryRun.startFrame - prevRun.endFrame) * binMs
                if (boundaryDurationMs < 180 &&
                    boundaryDurationMs < maxRunDurationMs * 0.30f &&
                    gapMs >= 80
                ) {
                    runs.removeAt(runs.lastIndex)
                }
            } else {
                val boundaryRun = runs.first()
                val nextRun = runs[1]
                val boundaryDurationMs = boundaryRun.durationMs(binMs)
                val gapMs = (nextRun.startFrame - boundaryRun.endFrame) * binMs
                if (boundaryDurationMs < 180 &&
                    boundaryDurationMs < maxRunDurationMs * 0.30f &&
                    gapMs >= 80
                ) {
                    runs.removeAt(0)
                }
            }
        }

        if (runs.isEmpty()) return null

        val firstSpeechFrame = runs.first().startFrame
        val lastSpeechFrame = runs.last().endFrame - 1
        if (lastSpeechFrame < firstSpeechFrame) return null

        val rawSpeechStartMs = (firstSpeechFrame.toLong() * binMs).coerceIn(pieceStartMs, pieceEndMs)
        val rawSpeechEndMs = ((lastSpeechFrame + 1).toLong() * binMs).coerceIn(rawSpeechStartMs, pieceEndMs)
        if (rawSpeechEndMs <= rawSpeechStartMs) {
            return null
        }

        val leadingSilenceMs = (rawSpeechStartMs - pieceStartMs).coerceAtLeast(0L)
        val trailingSilenceMs = (pieceEndMs - rawSpeechEndMs).coerceAtLeast(0L)
        val preMs = min(30L, leadingSilenceMs / 4L)
        val postMs = min(30L, trailingSilenceMs / 4L)
        val trimmedStartMs = (rawSpeechStartMs - preMs).coerceIn(pieceStartMs, rawSpeechStartMs)
        val trimmedEndMs = (rawSpeechEndMs + postMs).coerceIn(max(rawSpeechEndMs, trimmedStartMs + 1L), pieceEndMs)
        return trimmedStartMs to trimmedEndMs
    }

    private fun extractAndBridgeIntervals(
        speechFrames: BooleanArray,
        minSilenceMs: Int,
        binMs: Int,
    ): List<FrameInterval> {
        val rawRuns = ArrayList<FrameInterval>()
        var i = 0
        val n = speechFrames.size
        while (i < n) {
            if (!speechFrames[i]) {
                i++
                continue
            }
            val start = i
            while (i < n && speechFrames[i]) {
                i++
            }
            rawRuns.add(FrameInterval(start, i))
        }
        if (rawRuns.isEmpty()) return emptyList()

        val merged = ArrayList<FrameInterval>(rawRuns.size)
        var current = rawRuns[0]
        for (k in 1 until rawRuns.size) {
            val next = rawRuns[k]
            val silenceMs = (next.startFrame - current.endFrame) * binMs
            if (silenceMs < minSilenceMs) {
                current = FrameInterval(current.startFrame, next.endFrame)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)
        return merged
    }

    private fun resolveShortSpeechIntervals(
        intervals: List<FrameInterval>,
        rawEnergyDb: FloatArray,
        smoothedEnergyDb: FloatArray,
        thresholdDb: Float,
        params: SegmentationParams,
        binMs: Int,
    ): List<FrameInterval> {
        val list = intervals.toMutableList()
        val shortWordKeepThresholdDb = thresholdDb + SHORT_WORD_PEAK_OFFSET_DB

        var index = 0
        while (index < list.size) {
            val current = list[index]
            if (current.durationMs(binMs) >= params.minSpeechMs) {
                index++
                continue
            }

            val prevGapMs = if (index > 0) {
                (current.startFrame - list[index - 1].endFrame) * binMs
            } else {
                Int.MAX_VALUE
            }
            val nextGapMs = if (index + 1 < list.size) {
                (list[index + 1].startFrame - current.endFrame) * binMs
            } else {
                Int.MAX_VALUE
            }
            val nearestGapMs = min(prevGapMs, nextGapMs)

            if (nearestGapMs < params.mergeGapMs) {
                if (prevGapMs <= nextGapMs) {
                    val prev = list[index - 1]
                    list[index - 1] = FrameInterval(prev.startFrame, current.endFrame)
                    list.removeAt(index)
                    index = (index - 1).coerceAtLeast(0)
                } else {
                    val next = list[index + 1]
                    list[index + 1] = FrameInterval(current.startFrame, next.endFrame)
                    list.removeAt(index)
                }
            } else {
                val peakDb = maxPeakEnergyDb(current, rawEnergyDb, smoothedEnergyDb)
                if (peakDb < shortWordKeepThresholdDb) {
                    list.removeAt(index)
                } else {
                    // ピークが T + 6dB 以上の孤立した短い発話（「Yes」「はい」など）は残す
                    index++
                }
            }
        }
        return list
    }

    private fun maxPeakEnergyDb(
        interval: FrameInterval,
        rawEnergyDb: FloatArray,
        smoothedEnergyDb: FloatArray,
    ): Float {
        var maxDb = FrameFeatureExtractor.MIN_DB
        for (f in interval.startFrame until interval.endFrame) {
            val v = max(rawEnergyDb[f], smoothedEnergyDb[f])
            if (v > maxDb) maxDb = v
        }
        return maxDb
    }

    private fun splitLongIntervalRecursive(
        interval: FrameInterval,
        smoothedEnergyDb: FloatArray,
        thresholdDb: Float,
        params: SegmentationParams,
        binMs: Int,
        out: MutableList<FrameInterval>,
    ) {
        if (interval.durationMs(binMs) <= params.maxChunkMs || interval.frameCount < 2) {
            out.add(interval)
            return
        }

        val splitFrame = findBestValleyFrame(
            interval = interval,
            smoothedEnergyDb = smoothedEnergyDb,
            thresholdDb = thresholdDb,
            params = params,
            binMs = binMs,
        )
        if (splitFrame <= interval.startFrame || splitFrame >= interval.endFrame) {
            out.add(interval)
            return
        }

        splitLongIntervalRecursive(
            interval = FrameInterval(interval.startFrame, splitFrame),
            smoothedEnergyDb = smoothedEnergyDb,
            thresholdDb = thresholdDb,
            params = params,
            binMs = binMs,
            out = out,
        )
        splitLongIntervalRecursive(
            interval = FrameInterval(splitFrame, interval.endFrame),
            smoothedEnergyDb = smoothedEnergyDb,
            thresholdDb = thresholdDb,
            params = params,
            binMs = binMs,
            out = out,
        )
    }

    /**
     * 区間 [interval] 内部で最もエネルギーの低い谷フレームを探す（§7.3.3 手順 6）。
     * 150ms 以上の低エネルギー区間があればそれを優先し、なければ 150ms 移動窓の平均エネルギー最小箇所を選ぶ。
     */
    private fun findBestValleyFrame(
        interval: FrameInterval,
        smoothedEnergyDb: FloatArray,
        thresholdDb: Float,
        params: SegmentationParams,
        binMs: Int,
    ): Int {
        val totalFrames = interval.frameCount
        val minMarginFrames = min(
            max(1, params.minSpeechMs / binMs),
            max(1, totalFrames / 4),
        )
        val searchStart = interval.startFrame + minMarginFrames
        val searchEnd = interval.endFrame - minMarginFrames
        if (searchStart >= searchEnd) {
            return (interval.startFrame + interval.endFrame) / 2
        }

        val preferredValleyFrames = max(1, PREFERRED_VALLEY_MS / binMs)
        val lowEnergyCeilingDb = thresholdDb + EnergyVoiceActivityDetector.HYSTERESIS_ONSET_OFFSET_DB

        // 1. 150ms 以上続く低エネルギー区間（<= T + 3dB）があれば、平均エネルギーが最も低い区間の中央付近で分割する
        var bestRunStart = -1
        var bestRunEnd = -1
        var bestRunMeanDb = Float.POSITIVE_INFINITY
        var f = searchStart
        while (f < searchEnd) {
            if (smoothedEnergyDb[f] > lowEnergyCeilingDb) {
                f++
                continue
            }
            val rStart = f
            var sumDb = 0.0
            while (f < searchEnd && smoothedEnergyDb[f] <= lowEnergyCeilingDb) {
                sumDb += smoothedEnergyDb[f]
                f++
            }
            val rLen = f - rStart
            if (rLen >= preferredValleyFrames) {
                val meanDb = (sumDb / rLen).toFloat()
                if (meanDb < bestRunMeanDb) {
                    bestRunMeanDb = meanDb
                    bestRunStart = rStart
                    bestRunEnd = f
                }
            }
        }
        if (bestRunStart >= 0 && bestRunEnd > bestRunStart) {
            return findMinEnergyFrameInRange(smoothedEnergyDb, bestRunStart, bestRunEnd)
        }

        // 2. 150ms の移動窓で平均エネルギーが最小になる窓を探し、その窓内の最小エネルギーフレームで分割する
        if (searchEnd - searchStart >= preferredValleyFrames) {
            var windowSum = 0.0
            for (i in searchStart until searchStart + preferredValleyFrames) {
                windowSum += smoothedEnergyDb[i]
            }
            var bestWinStart = searchStart
            var bestWinSum = windowSum
            for (wStart in (searchStart + 1)..(searchEnd - preferredValleyFrames)) {
                windowSum += smoothedEnergyDb[wStart + preferredValleyFrames - 1] - smoothedEnergyDb[wStart - 1]
                if (windowSum < bestWinSum) {
                    bestWinSum = windowSum
                    bestWinStart = wStart
                }
            }
            return findMinEnergyFrameInRange(
                smoothedEnergyDb = smoothedEnergyDb,
                startFrame = bestWinStart,
                endFrame = bestWinStart + preferredValleyFrames,
            )
        }

        // 3. 探索範囲が 150ms 未満の場合は単純な最小フレームを返す
        return findMinEnergyFrameInRange(smoothedEnergyDb, searchStart, searchEnd)
    }

    private fun findMinEnergyFrameInRange(
        smoothedEnergyDb: FloatArray,
        startFrame: Int,
        endFrame: Int,
    ): Int {
        var minDb = Float.POSITIVE_INFINITY
        for (i in startFrame until endFrame) {
            if (smoothedEnergyDb[i] < minDb) {
                minDb = smoothedEnergyDb[i]
            }
        }
        // 最小値に並ぶフレームが複数ある場合は、その区間の中央を選ぶ
        var firstMin = startFrame
        var lastMin = startFrame
        var found = false
        for (i in startFrame until endFrame) {
            if (smoothedEnergyDb[i] <= minDb + 1e-4f) {
                if (!found) {
                    firstMin = i
                    found = true
                }
                lastMin = i
            }
        }
        return (firstMin + lastMin) / 2
    }

    companion object {
        /** 端末内のチャンク分割アルゴリズムの現在のバージョン（§6.4、§7.3.3）。 */
        const val CURRENT_ALGORITHM_VERSION: Int = com.miuraken.chunkflow.core.audio.CURRENT_ALGORITHM_VERSION

        /** 既定の 1 フレーム（ビン）時間長（10ms、§7.3.1）。 */
        const val DEFAULT_BIN_MS: Int = 10

        /** 短い発話を残すピークエネルギーの閾値オフセット（+6dB、§7.3.3 手順 5）。 */
        const val SHORT_WORD_PEAK_OFFSET_DB: Float = 6f

        /** 長いチャンクを谷分割するときに優先する低エネルギー谷の最小長（150ms、§7.3.3 手順 6）。 */
        const val PREFERRED_VALLEY_MS: Int = 150

        private const val SILERO_NON_SPEECH_PROB_CEILING: Float = 0.20f
        private const val BGM_ENERGY_THRESHOLD_DB: Float = -45.0f
        private const val BGM_ACTIVE_RATIO_THRESHOLD: Float = 0.10f
        private const val BGM_MIN_NON_SPEECH_FRAMES: Int = 20
        private const val DEFAULT_PARAMS_MIN_SILENCE_MS: Int = 500
        private const val BGM_REFERENCE_MIN_SILENCE_MS: Int = 300
        private const val BGM_MIN_SILENCE_SCALE: Float = 0.73f
        private const val MIN_BGM_SILENCE_MS: Int = 150
        private const val BGM_MAX_PRE_PADDING_MS: Int = 80
        private const val BGM_MAX_POST_PADDING_MS: Int = 100
        private const val SILERO_STANDALONE_WORD_MIN_MS: Int = 150
        private const val SILERO_VALLEY_PROB_WEIGHT_DB: Float = 40.0f

        /**
         * `thresholdRatio`（既定 `0.35f`）から Silero VAD 用の発話開始確率 `onsetProb` と発話終了確率 `offsetProb` を算出する。
         */
        internal fun computeSileroThresholds(thresholdRatio: Float): Pair<Float, Float> {
            val ratio = thresholdRatio.coerceIn(0.05f, 0.95f)
            val onsetProb = (0.50f + (ratio - 0.35f) * 0.60f).coerceIn(0.20f, 0.85f)
            val offsetProb = (onsetProb - 0.15f).coerceIn(0.10f, onsetProb - 0.05f)
            return onsetProb to offsetProb
        }

        /**
         * イントロ音楽の影響を避けるため、ファイルの中程（45 秒以上なら開始 30 秒以降の `[30s, min(total, 60s))`）をサンプリングし、
         * Silero VAD が非発話（`< 0.20`）と判定したフレームに有意な音量（`> -45 dBFS`）が存在するかで BGM の有無を自動判定する。
         */
        fun detectBgmPresence(
            logEnergyDb: FloatArray,
            speechProbability: FloatArray,
            binMs: Int = DEFAULT_BIN_MS,
        ): Boolean {
            val n = min(logEnergyDb.size, speechProbability.size)
            if (n <= 0 || binMs <= 0) return false
            val totalMs = n.toLong() * binMs
            val (sampleStartMs, sampleEndMs) = when {
                totalMs >= 45_000L -> 30_000L to min(totalMs, 60_000L)
                totalMs >= 15_000L -> (totalMs * 30L / 100L) to (totalMs * 85L / 100L)
                else -> 0L to totalMs
            }
            val startFrame = (sampleStartMs / binMs).toInt().coerceIn(0, n)
            val endFrame = (sampleEndMs / binMs).toInt().coerceIn(startFrame, n)

            evaluateBgmInFrameRange(logEnergyDb, speechProbability, startFrame, endFrame)?.let { return it }
            if (startFrame > 0 || endFrame < n) {
                evaluateBgmInFrameRange(logEnergyDb, speechProbability, 0, n)?.let { return it }
            }
            return false
        }

        private fun evaluateBgmInFrameRange(
            logEnergyDb: FloatArray,
            speechProbability: FloatArray,
            startFrame: Int,
            endFrame: Int,
        ): Boolean? {
            if (endFrame - startFrame < BGM_MIN_NON_SPEECH_FRAMES) return null
            var nonSpeechCount = 0
            var loudNonSpeechCount = 0
            for (f in startFrame until endFrame) {
                if (speechProbability[f] < SILERO_NON_SPEECH_PROB_CEILING) {
                    nonSpeechCount++
                    if (logEnergyDb[f] > BGM_ENERGY_THRESHOLD_DB) {
                        loudNonSpeechCount++
                    }
                }
            }
            if (nonSpeechCount < BGM_MIN_NON_SPEECH_FRAMES) return null
            val activeRatio = loudNonSpeechCount.toFloat() / nonSpeechCount.toFloat()
            if (activeRatio >= BGM_ACTIVE_RATIO_THRESHOLD) return true

            val nonSpeechEnergies = FloatArray(nonSpeechCount)
            var idx = 0
            for (f in startFrame until endFrame) {
                if (speechProbability[f] < SILERO_NON_SPEECH_PROB_CEILING) {
                    nonSpeechEnergies[idx++] = logEnergyDb[f]
                }
            }
            nonSpeechEnergies.sort()
            val p75 = EnergyVoiceActivityDetector.percentileSorted(nonSpeechEnergies, 0.75f)
            return p75 > BGM_ENERGY_THRESHOLD_DB
        }

        internal fun medianFilter3(input: FloatArray): FloatArray {
            val n = input.size
            if (n == 0) return FloatArray(0)
            if (n == 1) return floatArrayOf(input[0])
            val out = FloatArray(n)
            for (i in 0 until n) {
                val a = input[(i - 1).coerceAtLeast(0)]
                val b = input[i]
                val c = input[(i + 1).coerceAtMost(n - 1)]
                out[i] = max(min(a, b), min(max(a, b), c))
            }
            return out
        }
    }
}
