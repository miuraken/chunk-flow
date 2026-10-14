package com.miuraken.chunkflow.core.audio

import com.miuraken.chunkflow.core.model.Waveform
import kotlin.math.ceil
import kotlin.math.floor

/**
 * [WaveformReducer] で縮約された表示用の波形データ（§7.3.2）。
 *
 * 各バケットの値は [Waveform] と同じく dBFS（-60〜0dB）を 0..255 に量子化した符号なしバイト。
 *
 * @property startMs 縮約対象の開始時刻（ミリ秒）。
 * @property endMs 縮約対象の終了時刻（ミリ秒）。
 * @property peak バケットごとの最大ピーク値（符号なし 0..255）。
 * @property rms バケットごとの最大 RMS 値（符号なし 0..255）。
 */
class ReducedWaveform(
    val startMs: Long,
    val endMs: Long,
    val peak: ByteArray,
    val rms: ByteArray,
) {
    init {
        require(peak.size == rms.size) { "peak.size (${peak.size}) != rms.size (${rms.size})" }
    }

    /** バケット数。 */
    val size: Int get() = peak.size

    /** バケット [index] のピークを 0.0..1.0 の正規化値として返す。 */
    fun peakFraction(index: Int): Float = (peak[index].toInt() and 0xFF) / 255f

    /** バケット [index] の RMS を 0.0..1.0 の正規化値として返す。 */
    fun rmsFraction(index: Int): Float = (rms[index].toInt() and 0xFF) / 255f
}

/**
 * 固定時間分解能（10ms/ビン）の [Waveform] を、表示範囲 `[startMs, endMs]` と目標バケット数 `targetBins` に合わせて
 * max プーリングで縮約するクラス（§7.3.2）。
 *
 * 縮約結果は `(waveform.hashCode(), startMs, endMs, targetBins)` をキーとする上限付きのスレッドセーフな
 * LRU キャッシュに保持し、同じ範囲・幅の再描画での再計算を避ける。
 *
 * @param maxCacheEntries キャッシュに保持する最大エントリ数。
 */
class WaveformReducer(
    private val maxCacheEntries: Int = DEFAULT_MAX_CACHE_ENTRIES,
) {
    private data class CacheKey(
        val waveformHash: Int,
        val startMs: Long,
        val endMs: Long,
        val targetBins: Int,
    )

    private val lock = Any()
    private val cache = object : LinkedHashMap<CacheKey, ReducedWaveform>(
        maxCacheEntries.coerceAtLeast(1),
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, ReducedWaveform>?): Boolean = size > maxCacheEntries
    }

    init {
        require(maxCacheEntries >= 0) { "maxCacheEntries must be >= 0: $maxCacheEntries" }
    }

    /** 現在キャッシュされているエントリ数。 */
    val cacheSize: Int
        get() = synchronized(lock) { cache.size }

    /** キャッシュをすべて消去する。 */
    fun clearCache() {
        synchronized(lock) {
            cache.clear()
        }
    }

    /**
     * [waveform] の区間 `[startMs, endMs]` を [targetBins] 個のバケットに max プーリングで縮約する。
     *
     * [startMs] が負の場合や [endMs] が [Waveform.durationMs] を超える場合（拡大波形で先頭・末尾付近を
     * 中央表示するときなど）、範囲外のバケットは 0（無音）になる。
     *
     * @param waveform 元の波形データ。
     * @param startMs 表示範囲の開始時刻（ミリ秒）。
     * @param endMs 表示範囲の終了時刻（ミリ秒）。
     * @param targetBins 出力するバケット数（0 以上）。
     */
    fun reduce(
        waveform: Waveform,
        startMs: Long = 0L,
        endMs: Long = waveform.durationMs,
        targetBins: Int,
    ): ReducedWaveform {
        require(targetBins >= 0) { "targetBins must be >= 0: $targetBins" }
        require(endMs >= startMs) { "endMs ($endMs) must be >= startMs ($startMs)" }

        if (targetBins == 0) {
            return ReducedWaveform(startMs, endMs, ByteArray(0), ByteArray(0))
        }

        val key = CacheKey(
            waveformHash = waveform.hashCode(),
            startMs = startMs,
            endMs = endMs,
            targetBins = targetBins,
        )
        if (maxCacheEntries > 0) {
            synchronized(lock) {
                cache[key]?.let { return it }
            }
        }

        val computed = computeMaxPool(waveform, startMs, endMs, targetBins)
        if (maxCacheEntries > 0) {
            synchronized(lock) {
                cache[key] = computed
            }
        }
        return computed
    }

    private fun computeMaxPool(
        waveform: Waveform,
        startMs: Long,
        endMs: Long,
        targetBins: Int,
    ): ReducedWaveform {
        val outPeak = ByteArray(targetBins)
        val outRms = ByteArray(targetBins)
        val srcBins = waveform.peak.size
        val binMs = waveform.binMs
        val windowDurationMs = endMs - startMs
        if (srcBins == 0 || binMs <= 0 || windowDurationMs <= 0L) {
            return ReducedWaveform(startMs, endMs, outPeak, outRms)
        }

        val totalWaveformMs = waveform.durationMs.toDouble()
        val spanMs = windowDurationMs.toDouble()
        val binMsDouble = binMs.toDouble()

        for (b in 0 until targetBins) {
            val bucketStartMs = startMs + (spanMs * b) / targetBins
            val bucketEndMs = startMs + (spanMs * (b + 1)) / targetBins
            if (bucketEndMs <= 0.0 || bucketStartMs >= totalWaveformMs) {
                continue
            }
            val rawFirstBin = floor(bucketStartMs / binMsDouble).toInt()
            val rawLastBinExclusive = maxOf(rawFirstBin + 1, ceil(bucketEndMs / binMsDouble).toInt())
            val firstBin = rawFirstBin.coerceIn(0, srcBins - 1)
            val lastBinExclusive = rawLastBinExclusive.coerceIn(firstBin + 1, srcBins)

            var maxPeak = 0
            var maxRms = 0
            for (i in firstBin until lastBinExclusive) {
                val p = waveform.peak[i].toInt() and 0xFF
                val r = waveform.rms[i].toInt() and 0xFF
                if (p > maxPeak) maxPeak = p
                if (r > maxRms) maxRms = r
            }
            outPeak[b] = maxPeak.toByte()
            outRms[b] = maxRms.toByte()
        }

        return ReducedWaveform(
            startMs = startMs,
            endMs = endMs,
            peak = outPeak,
            rms = outRms,
        )
    }

    companion object {
        /** キャッシュの既定の最大エントリ数。 */
        const val DEFAULT_MAX_CACHE_ENTRIES: Int = 64

        private val defaultInstance = WaveformReducer()

        /** 共有インスタンスを使って [waveform] を縮約する。 */
        fun reduce(
            waveform: Waveform,
            startMs: Long = 0L,
            endMs: Long = waveform.durationMs,
            targetBins: Int,
        ): ReducedWaveform = defaultInstance.reduce(
            waveform = waveform,
            startMs = startMs,
            endMs = endMs,
            targetBins = targetBins,
        )
    }
}
