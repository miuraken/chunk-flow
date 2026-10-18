package com.miuraken.chunkflow.core.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ストリーミングデコード中の 16kHz モノラル 16-bit PCM を固定長ブロック単位のスライディングウィンドウで保持するバッファ。
 *
 * PCM 全体をメモリに載せず（§7.3.1）、直近の未確定区間と切り出し対象区間のみを保持し、
 * チャンク確定後に不要になった前方ブロックを順次破棄してヒープ使用量を抑える。
 *
 * @property sampleRateHz サンプルレート（Hz、既定 16,000Hz）。
 * @property blockSizeSamples 1 ブロックあたりのサンプル数（既定 1 秒分 = [sampleRateHz]）。
 * @property maxRetainedSamples バッファ内に保持する最大サンプル数（既定 120 秒分）。これを超えた古いブロックは自動破棄される。
 */
class PcmSlidingWindowBuffer(
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val blockSizeSamples: Int = sampleRateHz.coerceAtLeast(1),
    val maxRetainedSamples: Int = (sampleRateHz * DEFAULT_MAX_RETAINED_SECONDS).coerceAtLeast(blockSizeSamples),
) {
    private val lock = Any()
    private val blocks = ArrayDeque<ShortArray>()
    private var lastBlockWriteOffset: Int = 0

    /** 現在バッファの先頭にあるブロックの開始サンプルインデックス（累計サンプル基準）。 */
    var baseSampleIndex: Long = 0L
        private set

    /** これまでに書き込まれた累計サンプル数。 */
    var totalSamplesWritten: Long = 0L
        private set

    /** 現在バッファ内に保持されている有効なサンプル数。 */
    val retainedSampleCount: Int
        get() = synchronized(lock) {
            (totalSamplesWritten - baseSampleIndex).coerceAtLeast(0L).toInt()
        }

    /**
     * 正規化 Float サンプル配列（`-1.0f..1.0f`）を 16-bit [Short] に量子化して末尾へ追加する。
     *
     * 追加後に保持サンプル数が [maxRetainedSamples] を超えた場合、古いブロックから順に自動破棄する。
     */
    fun appendFloats(samples: FloatArray, count: Int = samples.size) {
        val safeCount = count.coerceIn(0, samples.size)
        if (safeCount <= 0) return

        synchronized(lock) {
            for (i in 0 until safeCount) {
                if (blocks.isEmpty() || lastBlockWriteOffset >= blockSizeSamples) {
                    blocks.addLast(ShortArray(blockSizeSamples))
                    lastBlockWriteOffset = 0
                }
                val sample = samples[i]
                val pcmShort = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE.toFloat())
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                blocks.last()[lastBlockWriteOffset] = pcmShort
                lastBlockWriteOffset += 1
                totalSamplesWritten += 1L
            }

            while (totalSamplesWritten - baseSampleIndex > maxRetainedSamples && blocks.isNotEmpty()) {
                val removed = blocks.removeFirst()
                baseSampleIndex += removed.size
                if (blocks.isEmpty()) {
                    lastBlockWriteOffset = 0
                    baseSampleIndex = totalSamplesWritten
                }
            }
        }
    }

    /**
     * `[startMs, min(endMs, startMs + maxDurationMs)]` に対応する 16-bit PCM サンプル配列を切り出して返す。
     *
     * 要求範囲がすでに破棄済みまたは未到達で 1 サンプルも取得できない場合は `null` を返す。
     * 一部のみ保持されている場合は取得可能な重複範囲を切り出して返す。
     */
    fun sliceLeadingPcm(
        startMs: Long,
        endMs: Long,
        maxDurationMs: Long = DEFAULT_MAX_SLICE_DURATION_MS,
    ): ShortArray? {
        if (startMs < 0L || endMs <= startMs || maxDurationMs <= 0L || sampleRateHz <= 0) {
            return null
        }
        val clippedEndMs = min(endMs, startMs + maxDurationMs)
        val requestedStartSample = (startMs * sampleRateHz) / 1_000L
        val requestedEndSample = (clippedEndMs * sampleRateHz) / 1_000L
        if (requestedEndSample <= requestedStartSample) return null

        synchronized(lock) {
            val actualStartSample = max(requestedStartSample, baseSampleIndex)
            val actualEndSample = min(requestedEndSample, totalSamplesWritten)
            if (actualEndSample <= actualStartSample) return null

            val totalToCopy = (actualEndSample - actualStartSample).toInt()
            val out = ShortArray(totalToCopy)
            var written = 0
            var pos = actualStartSample
            while (pos < actualEndSample) {
                val offsetFromBase = pos - baseSampleIndex
                val blockIdx = (offsetFromBase / blockSizeSamples).toInt()
                if (blockIdx !in 0 until blocks.size) break
                val inBlockOffset = (offsetFromBase % blockSizeSamples).toInt()
                val block = blocks[blockIdx]
                val toCopy = min((actualEndSample - pos).toInt(), block.size - inBlockOffset)
                if (toCopy <= 0) break
                block.copyInto(
                    destination = out,
                    destinationOffset = written,
                    startIndex = inBlockOffset,
                    endIndex = inBlockOffset + toCopy,
                )
                written += toCopy
                pos += toCopy
            }
            return if (written == out.size) {
                out
            } else if (written > 0) {
                out.copyOf(written)
            } else {
                null
            }
        }
    }

    /**
     * [keepFromMs] より完全に前（ブロック終端 `<= keepFromSample`）の古いブロックを破棄してメモリを解放する。
     */
    fun discardBeforeMs(keepFromMs: Long) {
        if (keepFromMs <= 0L || sampleRateHz <= 0) return
        val keepFromSample = (keepFromMs * sampleRateHz) / 1_000L
        synchronized(lock) {
            while (blocks.isNotEmpty()) {
                val firstBlockSize = blocks.first().size.toLong()
                val firstBlockEnd = baseSampleIndex + firstBlockSize
                if (firstBlockEnd <= keepFromSample && firstBlockEnd <= totalSamplesWritten) {
                    blocks.removeFirst()
                    baseSampleIndex = firstBlockEnd
                    if (blocks.isEmpty()) {
                        lastBlockWriteOffset = 0
                    }
                } else {
                    break
                }
            }
        }
    }

    /**
     * 保持している全ブロックを破棄して初期状態に戻す。
     */
    fun clear() {
        synchronized(lock) {
            blocks.clear()
            lastBlockWriteOffset = 0
            baseSampleIndex = 0L
            totalSamplesWritten = 0L
        }
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 16_000
        const val DEFAULT_MAX_RETAINED_SECONDS: Int = 120
        const val DEFAULT_MAX_SLICE_DURATION_MS: Long = 2_000L
    }
}
