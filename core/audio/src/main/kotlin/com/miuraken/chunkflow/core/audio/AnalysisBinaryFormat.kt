package com.miuraken.chunkflow.core.audio

import com.miuraken.chunkflow.core.model.Waveform
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * `features.bin` に保存されたフレーム特徴量データ（§6.4、§7.3.1、§7.3.3）。
 *
 * @property binMs 1 フレーム（ビン）の長さ（ミリ秒、既定 10ms）。
 * @property logEnergyDb フレームごとの対数エネルギー（dBFS）。
 */
class StoredFeatures(
    val binMs: Int,
    val logEnergyDb: FloatArray,
) {
    init {
        require(binMs > 0) { "binMs must be > 0: $binMs" }
    }

    /** フレーム数。 */
    val frameCount: Int get() = logEnergyDb.size

    /** 特徴量全体の時間長（ミリ秒）。 */
    val durationMs: Long get() = logEnergyDb.size.toLong() * binMs
}

/**
 * `waveform.bin` および `features.bin` の独自バイナリ形式の読み書きと厳密な検証を行うユーティリティ（§6.4）。
 *
 * - ヘッダ（16 バイト、ビッグエンディアン）:
 *   1. マジック（4 バイト）: 波形は `"CFWF"`、特徴量は `"CFFE"`
 *   2. フォーマット版（4 バイト `Int`）: [CURRENT_FORMAT_VERSION]
 *   3. `binMs`（4 バイト `Int`）: 1 ビン/フレームのミリ秒（1..1000）
 *   4. 件数 `count`（4 バイト `Int`）: ビン数またはフレーム数（0..[MAX_BIN_COUNT]）
 * - ペイロード:
 *   - `waveform.bin`: `peak`（`count` バイト）+ `rms`（`count` バイト）= `count * 2` バイト
 *   - `features.bin`: `logEnergyDb`（`count` 個の IEEE 754 `Float`、ビッグエンディアン）= `count * 4` バイト
 * - 破損・切り詰め・非対応バージョンのバイト列やファイルに対しては例外を投げずに `null` を返す（§6.4）。
 */
object AnalysisBinaryFormat {

    /** バイナリ形式の現在のバージョン。 */
    const val CURRENT_FORMAT_VERSION: Int = 1

    /** ヘッダのバイト数（マジック 4 + バージョン 4 + binMs 4 + 件数 4 = 16 バイト）。 */
    const val HEADER_SIZE_BYTES: Int = 16

    /** 許容する最大ビン/フレーム数（10ms × 3,600,000 = 10 時間分）。 */
    const val MAX_BIN_COUNT: Int = 3_600_000

    private const val MAX_BIN_MS: Int = 1_000

    // "CFWF" = 0x43465746
    private const val WAVEFORM_MAGIC: Int = 0x43465746

    // "CFFE" = 0x43464645
    private const val FEATURES_MAGIC: Int = 0x43464645

    /**
     * [Waveform] を `waveform.bin` 形式のバイト列にエンコードする。
     */
    fun encodeWaveform(waveform: Waveform): ByteArray {
        val count = waveform.peak.size
        require(waveform.binMs in 1..MAX_BIN_MS) { "Invalid binMs: ${waveform.binMs}" }
        require(count in 0..MAX_BIN_COUNT) { "Invalid bin count: $count" }
        val totalBytes = HEADER_SIZE_BYTES + count * 2
        val buffer = ByteBuffer.allocate(totalBytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(WAVEFORM_MAGIC)
        buffer.putInt(CURRENT_FORMAT_VERSION)
        buffer.putInt(waveform.binMs)
        buffer.putInt(count)
        buffer.put(waveform.peak, 0, count)
        buffer.put(waveform.rms, 0, count)
        return buffer.array()
    }

    /**
     * `waveform.bin` 形式のバイト列 [bytes] を検証して [Waveform] を復元する。
     * ヘッダ不正やサイズ不一致（切り詰め・末尾ゴミ）がある場合は `null` を返す。
     */
    fun decodeWaveform(bytes: ByteArray): Waveform? {
        if (bytes.size < HEADER_SIZE_BYTES) return null
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = buffer.int
            val version = buffer.int
            val binMs = buffer.int
            val count = buffer.int
            if (magic != WAVEFORM_MAGIC) return null
            if (version != CURRENT_FORMAT_VERSION) return null
            if (binMs !in 1..MAX_BIN_MS) return null
            if (count !in 0..MAX_BIN_COUNT) return null

            val expectedSize = HEADER_SIZE_BYTES.toLong() + count.toLong() * 2L
            if (bytes.size.toLong() != expectedSize) return null

            val peak = ByteArray(count)
            val rms = ByteArray(count)
            buffer.get(peak)
            buffer.get(rms)
            Waveform(binMs = binMs, peak = peak, rms = rms)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [StoredFeatures] を `features.bin` 形式のバイト列にエンコードする。
     */
    fun encodeFeatures(features: StoredFeatures): ByteArray = encodeFeatures(
        logEnergyDb = features.logEnergyDb,
        binMs = features.binMs,
    )

    /**
     * フレーム特徴量 [logEnergyDb] と [binMs] を `features.bin` 形式のバイト列にエンコードする。
     */
    fun encodeFeatures(
        logEnergyDb: FloatArray,
        binMs: Int = ChunkSegmenter.DEFAULT_BIN_MS,
    ): ByteArray {
        val count = logEnergyDb.size
        require(binMs in 1..MAX_BIN_MS) { "Invalid binMs: $binMs" }
        require(count in 0..MAX_BIN_COUNT) { "Invalid frame count: $count" }
        val totalBytes = HEADER_SIZE_BYTES + count * 4
        val buffer = ByteBuffer.allocate(totalBytes).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(FEATURES_MAGIC)
        buffer.putInt(CURRENT_FORMAT_VERSION)
        buffer.putInt(binMs)
        buffer.putInt(count)
        for (i in 0 until count) {
            val v = logEnergyDb[i]
            require(v.isFinite()) { "Non-finite feature value at index $i: $v" }
            buffer.putFloat(v)
        }
        return buffer.array()
    }

    /**
     * `features.bin` 形式のバイト列 [bytes] を検証して [StoredFeatures] を復元する。
     * ヘッダ不正・サイズ不一致・`NaN`/`Infinity` が含まれる場合は `null` を返す。
     */
    fun decodeFeatures(bytes: ByteArray): StoredFeatures? {
        if (bytes.size < HEADER_SIZE_BYTES) return null
        return try {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic = buffer.int
            val version = buffer.int
            val binMs = buffer.int
            val count = buffer.int
            if (magic != FEATURES_MAGIC) return null
            if (version != CURRENT_FORMAT_VERSION) return null
            if (binMs !in 1..MAX_BIN_MS) return null
            if (count !in 0..MAX_BIN_COUNT) return null

            val expectedSize = HEADER_SIZE_BYTES.toLong() + count.toLong() * 4L
            if (bytes.size.toLong() != expectedSize) return null

            val energy = FloatArray(count)
            for (i in 0 until count) {
                val value = buffer.float
                if (!value.isFinite()) return null
                energy[i] = value
            }
            StoredFeatures(binMs = binMs, logEnergyDb = energy)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * [waveform] を一時ファイル経由でアトミックに [file] へ書き込む。
     */
    fun writeWaveformAtomic(file: File, waveform: Waveform): Boolean = try {
        val bytes = encodeWaveform(waveform)
        writeBytesAtomic(file, bytes)
    } catch (_: Exception) {
        false
    }

    /**
     * [file] から [Waveform] を読み込む。存在しない場合や検証失敗時は `null` を返す。
     */
    fun readWaveform(file: File): Waveform? {
        val bytes = readBytesSafely(file, maxBytes = HEADER_SIZE_BYTES.toLong() + MAX_BIN_COUNT.toLong() * 2L)
            ?: return null
        return decodeWaveform(bytes)
    }

    /**
     * [features] を一時ファイル経由でアトミックに [file] へ書き込む。
     */
    fun writeFeaturesAtomic(file: File, features: StoredFeatures): Boolean = try {
        val bytes = encodeFeatures(features)
        writeBytesAtomic(file, bytes)
    } catch (_: Exception) {
        false
    }

    /**
     * [file] から [StoredFeatures] を読み込む。存在しない場合や検証失敗時は `null` を返す。
     */
    fun readFeatures(file: File): StoredFeatures? {
        val bytes = readBytesSafely(file, maxBytes = HEADER_SIZE_BYTES.toLong() + MAX_BIN_COUNT.toLong() * 4L)
            ?: return null
        return decodeFeatures(bytes)
    }

    private fun readBytesSafely(file: File, maxBytes: Long): ByteArray? {
        if (!file.exists() || !file.isFile) return null
        return try {
            val length = file.length()
            if (length < HEADER_SIZE_BYTES || length > maxBytes) return null
            file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    private fun writeBytesAtomic(targetFile: File, bytes: ByteArray): Boolean {
        val parent = targetFile.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val tempFile = File(parent, "${targetFile.name}.tmp")
        return try {
            FileOutputStream(tempFile).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (tempFile.renameTo(targetFile)) {
                true
            } else {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                true
            }
        } catch (_: IOException) {
            tempFile.delete()
            false
        }
    }
}
