package com.miuraken.chunkflow.core.media.bench

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale

/**
 * 計測結果の 1 行（JSON Lines の 1 オブジェクト）を組み立てる。
 *
 * 値は文字列・整数・小数・真偽値・`null` だけを受け付ける（それ以外は文字列にする）。
 */
internal object SttBenchJson {
    /** [fields] を、順番を保ったまま 1 行の JSON オブジェクトにする。 */
    fun line(vararg fields: Pair<String, Any?>): String = buildString {
        append('{')
        fields.forEachIndexed { index, (key, value) ->
            if (index > 0) append(',')
            appendString(key)
            append(':')
            appendValue(value)
        }
        append('}')
    }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")

            is Boolean -> append(value)

            is Int, is Long, is Short, is Byte -> append(value)

            is Float -> appendDouble(value.toDouble())

            is Double -> appendDouble(value)

            is String -> appendString(value)

            is Collection<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }

            else -> appendString(value.toString())
        }
    }

    private fun StringBuilder.appendDouble(value: Double) {
        if (value.isNaN() || value.isInfinite()) {
            append("null")
        } else {
            append(String.format(Locale.ROOT, "%.3f", value))
        }
    }

    private fun StringBuilder.appendString(value: String) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")

                '\\' -> append("\\\\")

                '\n' -> append("\\n")

                '\r' -> append("\\r")

                '\t' -> append("\\t")

                '\b' -> append("\\b")

                '\u000C' -> append("\\f")

                else -> if (c < ' ' || c == '\u2028' || c == '\u2029') {
                    append(String.format(Locale.ROOT, "\\u%04x", c.code))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }
}

/**
 * WAV ファイルのヘッダ（`fmt ` と `data` のチャンク）だけを読み、音声の長さを求める。
 *
 * 計測に使うクリップは信頼できない入力として扱い、チャンクの大きさ・形式を確かめる。
 */
internal object SttBenchWav {
    /** ヘッダとして読むチャンクの数の上限。 */
    private const val MAX_CHUNKS: Int = 64

    /** WAV の形式。 */
    data class Info(
        val sampleRateHz: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val dataBytes: Long,
    ) {
        /** 音声の長さ（ミリ秒）。 */
        val durationMs: Long
            get() {
                val bytesPerSecond = sampleRateHz.toLong() * channels * (bitsPerSample / 8)
                return if (bytesPerSecond <= 0L) 0L else dataBytes * 1_000L / bytesPerSecond
            }
    }

    /** [file] のヘッダを読む。WAV でない・壊れているときは `null`。 */
    fun read(file: File): Info? = try {
        RandomAccessFile(file, "r").use { raf -> readHeader(raf, file.length()) }
    } catch (_: IOException) {
        null
    }

    private fun readHeader(raf: RandomAccessFile, fileLength: Long): Info? {
        if (fileLength < 12L) return null
        val riff = ByteArray(12)
        raf.readFully(riff)
        if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" || String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        var position = 12L
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var formatSeen = false
        repeat(MAX_CHUNKS) {
            if (position + 8L > fileLength) return null
            raf.seek(position)
            val chunkHeader = ByteArray(8)
            raf.readFully(chunkHeader)
            val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize = le32(chunkHeader, 4).toLong() and 0xFFFF_FFFFL
            val bodyStart = position + 8L
            when (chunkId) {
                "fmt " -> {
                    if (chunkSize < 16L || bodyStart + 16L > fileLength) return null
                    val body = ByteArray(16)
                    raf.readFully(body)
                    channels = le16(body, 2)
                    sampleRate = le32(body, 4)
                    bits = le16(body, 14)
                    formatSeen = true
                }

                "data" -> {
                    if (!formatSeen || sampleRate <= 0 || channels <= 0 || bits <= 0 || bits % 8 != 0) return null
                    val available = (fileLength - bodyStart).coerceAtLeast(0L)
                    return Info(
                        sampleRateHz = sampleRate,
                        channels = channels,
                        bitsPerSample = bits,
                        dataBytes = minOf(chunkSize, available),
                    )
                }
            }
            position = bodyStart + chunkSize + (chunkSize and 1L)
        }
        return null
    }

    private fun le16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
