package com.miuraken.chunkflow.feature.player

import com.miuraken.chunkflow.core.model.Waveform
import kotlin.math.max
import kotlin.math.min

/**
 * 表示幅（ピクセル / カラム数）に合わせて max プーリングで縮約した波形データ（§7.3.2、§7.5.4）。
 *
 * 各要素は `0f..1f` に正規化された振幅値。
 *
 * @property peak カラムごとのピーク振幅（`0f..1f`）。
 * @property rms カラムごとの RMS 振幅（`0f..1f`）。
 */
internal class DownsampledWaveform(
    val peak: FloatArray,
    val rms: FloatArray,
) {
    val size: Int get() = peak.size
}

/**
 * [Waveform] を指定した時間範囲・カラム数へ max プーリングで縮約するヘルパー（§7.3.2、§7.5.4）。
 *
 * `(waveform, startMs, endMs, columnCount)` をキーにした LRU キャッシュを持ち、
 * 同じ表示窓での再描画時に配列確保と再計算を避ける。
 */
internal class WaveformDownsampler(
    private val maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES,
) {
    private data class CacheKey(
        val waveformIdentity: Int,
        val binCount: Int,
        val binMs: Int,
        val startMs: Long,
        val endMs: Long,
        val columnCount: Int,
    )

    private val cache = object : LinkedHashMap<CacheKey, DownsampledWaveform>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, DownsampledWaveform>?): Boolean = size > maxCacheEntries
    }

    /**
     * [waveform] の `[startMs, endMs)` 区間を [columnCount] 本のカラムへ max プーリングで縮約する。
     *
     * `[0, waveform.durationMs)` の範囲外（拡大波形の端など）は `0f` で埋める。
     */
    fun downsample(
        waveform: Waveform,
        startMs: Long,
        endMs: Long,
        columnCount: Int,
    ): DownsampledWaveform {
        if (columnCount <= 0 || endMs <= startMs || waveform.peak.isEmpty() || waveform.binMs <= 0) {
            return EMPTY
        }

        val key = CacheKey(
            waveformIdentity = System.identityHashCode(waveform),
            binCount = waveform.peak.size,
            binMs = waveform.binMs,
            startMs = startMs,
            endMs = endMs,
            columnCount = columnCount,
        )
        synchronized(cache) {
            cache[key]?.let { return it }
        }

        val computed = computeMaxPooled(
            waveform = waveform,
            startMs = startMs,
            endMs = endMs,
            columnCount = columnCount,
        )
        synchronized(cache) {
            cache[key] = computed
        }
        return computed
    }

    private fun computeMaxPooled(
        waveform: Waveform,
        startMs: Long,
        endMs: Long,
        columnCount: Int,
    ): DownsampledWaveform {
        val outPeak = FloatArray(columnCount)
        val outRms = FloatArray(columnCount)
        val windowDurationMs = endMs - startMs
        val binMs = waveform.binMs.toLong()
        val totalBins = waveform.peak.size
        val waveformDurationMs = waveform.durationMs
        val srcPeak = waveform.peak
        val srcRms = waveform.rms

        for (col in 0 until columnCount) {
            val colStartMs = startMs + (windowDurationMs * col) / columnCount
            val colEndMs = max(colStartMs + 1L, startMs + (windowDurationMs * (col + 1)) / columnCount)
            if (colEndMs <= 0L || colStartMs >= waveformDurationMs) {
                continue
            }
            val clampedStartMs = colStartMs.coerceIn(0L, waveformDurationMs - 1L)
            val clampedEndMs = colEndMs.coerceIn(clampedStartMs + 1L, waveformDurationMs)

            val firstBin = (clampedStartMs / binMs).toInt().coerceIn(0, totalBins - 1)
            val lastBinExclusive = max(
                firstBin + 1,
                min(totalBins, ((clampedEndMs + binMs - 1L) / binMs).toInt()),
            )

            var maxPeak = 0
            var maxRms = 0
            for (bin in firstBin until lastBinExclusive) {
                val p = srcPeak[bin].toInt() and 0xFF
                val r = srcRms[bin].toInt() and 0xFF
                if (p > maxPeak) maxPeak = p
                if (r > maxRms) maxRms = r
            }
            outPeak[col] = maxPeak / MAX_UNSIGNED_BYTE
            outRms[col] = maxRms / MAX_UNSIGNED_BYTE
        }

        return DownsampledWaveform(peak = outPeak, rms = outRms)
    }

    companion object {
        private const val DEFAULT_MAX_CACHE_ENTRIES: Int = 32
        private const val MAX_UNSIGNED_BYTE: Float = 255f
        private val EMPTY = DownsampledWaveform(FloatArray(0), FloatArray(0))
    }
}
