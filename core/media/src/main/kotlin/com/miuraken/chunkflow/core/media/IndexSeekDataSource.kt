package com.miuraken.chunkflow.core.media

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.RandomAccessFile
import java.net.URI

/**
 * 解析デコード時に MP3 ファイルの先頭フレームにある `Xing` / `Info` / `VBRI` 識別子を 0 でマスクし、
 * ファイル長を [C.LENGTH_UNSET] として返す [DataSource]（§7.3.1、ADR 0005、ADR 0006）。
 *
 * `Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING` と組み合わせることで、再生側（`core:playback`）の ExoPlayer と
 * まったく同じバイト列・同じ抽出器規則で MP3 を読み出し、解析と再生の時間軸を一致させる。
 * 元の音声ファイル自体は一切書き換えない。
 */
@OptIn(UnstableApi::class)
class IndexSeekDataSource(
    private val upstream: DataSource,
) : DataSource by upstream {

    private var position: Long = 0L
    private var mask: LongRange = LongRange.EMPTY

    override fun open(dataSpec: DataSpec): Long {
        position = dataSpec.position
        mask = findHeaderTagRange(dataSpec.uri)
        upstream.open(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val bytesRead = upstream.read(buffer, offset, length)
        if (bytesRead > 0) {
            if (!mask.isEmpty() && position <= mask.last && position + bytesRead > mask.first) {
                for (i in 0 until bytesRead) {
                    if (position + i in mask) {
                        buffer[offset + i] = 0
                    }
                }
            }
            position += bytesRead
        }
        return bytesRead
    }

    override fun close() {
        position = 0L
        mask = LongRange.EMPTY
        upstream.close()
    }

    private fun findHeaderTagRange(uri: Uri): LongRange {
        val file = resolveLocalFile(uri) ?: return LongRange.EMPTY
        if (!file.exists() || !file.isFile) return LongRange.EMPTY
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val scanLength = minOf(raf.length(), SCAN_BYTES.toLong()).toInt()
                if (scanLength < 4) return LongRange.EMPTY
                val head = ByteArray(scanLength)
                raf.readFully(head)
                findHeaderTagRangeInBytes(head)
            }
        } catch (_: Exception) {
            LongRange.EMPTY
        }
    }

    private fun resolveLocalFile(uri: Uri): File? {
        val scheme = uri.scheme
        if (!scheme.isNullOrEmpty() && scheme != "file") return null
        val path = uri.path
        if (!path.isNullOrEmpty()) {
            val candidate = File(path)
            if (candidate.exists()) return candidate
        }
        return runCatching {
            val raw = uri.toString()
            if (raw.startsWith("file:")) {
                File(URI(raw))
            } else {
                File(raw)
            }
        }.getOrNull()
    }

    /** [IndexSeekDataSource] を生成する [DataSource.Factory]。 */
    class Factory(
        private val upstreamFactory: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = IndexSeekDataSource(upstreamFactory.createDataSource())
    }

    companion object {
        private const val SCAN_BYTES: Int = 64 * 1024
        private const val FIRST_FRAME_MAX: Int = 2_000
        private const val TAG_LENGTH: Long = 4L
        private val HEADER_TAGS: List<String> = listOf("Xing", "Info", "VBRI")

        /**
         * 先頭バイト列 [head] から、ID3v2 タグ直後の先頭フレーム内にある `Xing` / `Info` / `VBRI` の
         * 4 バイト範囲を求める。該当しなければ [LongRange.EMPTY] を返す。
         */
        internal fun findHeaderTagRangeInBytes(head: ByteArray): LongRange {
            val start = id3v2Size(head)
            if (start >= head.size) return LongRange.EMPTY
            val text = String(head, Charsets.ISO_8859_1)
            val idx = HEADER_TAGS
                .map { tag -> text.indexOf(tag, start) }
                .filter { it >= 0 }
                .minOrNull()
            return if (idx == null || idx > start + FIRST_FRAME_MAX) {
                LongRange.EMPTY
            } else {
                idx.toLong() until (idx.toLong() + TAG_LENGTH)
            }
        }

        /** 先頭の ID3v2 タグのバイト数を返す。ID3v2 がなければ 0 を返す。 */
        internal fun id3v2Size(head: ByteArray): Int {
            if (head.size < 10) return 0
            if (head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) {
                return 0
            }
            val flags = head[5].toInt() and 0xFF
            val footerSize = if ((flags and 0x10) != 0) 10 else 0
            val size = ((head[6].toInt() and 0x7F) shl 21) or
                ((head[7].toInt() and 0x7F) shl 14) or
                ((head[8].toInt() and 0x7F) shl 7) or
                (head[9].toInt() and 0x7F)
            return 10 + size + footerSize
        }
    }
}
