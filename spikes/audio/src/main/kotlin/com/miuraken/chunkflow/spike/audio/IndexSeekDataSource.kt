package com.miuraken.chunkflow.spike.audio

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.RandomAccessFile

/**
 * MP3 を Media3 の IndexSeeker（読みながら作る正確な時刻→バイト位置の索引）でシークさせるための DataSource の試作。
 *
 * `Mp3Extractor` は `FLAG_ENABLE_INDEX_SEEKING` を「VBR のヘッダはあるが TOC がない」か「CBR とみなしたがファイル長が
 * わからない」ときにしか使わない。そこで、ファイルは書き換えずに、読み出すときだけ
 * 1. 先頭付近の VBR / CBR ヘッダ（`Xing` / `Info` / `VBRI`）の識別子を 0 で隠し（CBR とみなさせる）、
 * 2. ファイル長を「不明」（[C.LENGTH_UNSET]）として返す。
 * これで、どの種類の MP3 でも IndexSeeker が使われる（§12 の対策 (c) を、自前の索引なしで実現できるかの確認）。
 *
 * 副作用: LAME ヘッダのギャップレス情報（エンコーダの遅延）が使われなくなる。長さは読み終えるまで不明になる
 * （本体では、取り込み時の解析で求めた長さを表示に使う）。
 */
@OptIn(UnstableApi::class)
class IndexSeekDataSource(private val upstream: DataSource) : DataSource by upstream {
    private var position = 0L
    private var mask: LongRange = LongRange.EMPTY

    override fun open(dataSpec: DataSpec): Long {
        position = dataSpec.position
        mask = findHeaderTag(dataSpec.uri)
        upstream.open(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val n = upstream.read(buffer, offset, length)
        if (n > 0) {
            if (!mask.isEmpty() && position <= mask.last && position + n > mask.first) {
                for (i in 0 until n) {
                    if (position + i in mask) buffer[offset + i] = 0
                }
            }
            position += n
        }
        return n
    }

    private fun findHeaderTag(uri: Uri): LongRange {
        val path = uri.path ?: return LongRange.EMPTY
        if (uri.scheme != "file") return LongRange.EMPTY
        return RandomAccessFile(File(path), "r").use { f ->
            val head = ByteArray(minOf(f.length(), SCAN_BYTES.toLong()).toInt())
            f.readFully(head)
            val start = id3Size(head)
            val text = String(head, Charsets.ISO_8859_1)
            val idx = TAGS.map { text.indexOf(it, start) }.filter { it >= 0 }.minOrNull()
            if (idx == null || idx > start + FIRST_FRAME_MAX) LongRange.EMPTY else idx.toLong() until idx + 4L
        }
    }

    private fun id3Size(head: ByteArray): Int {
        if (head.size < 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return 0
        val size = (head[6].toInt() and 0x7F shl 21) or (head[7].toInt() and 0x7F shl 14) or
            (head[8].toInt() and 0x7F shl 7) or (head[9].toInt() and 0x7F)
        return 10 + size
    }

    /** [IndexSeekDataSource] を作る Factory。 */
    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = IndexSeekDataSource(upstream.createDataSource())
    }

    private companion object {
        const val SCAN_BYTES = 64 * 1024
        const val FIRST_FRAME_MAX = 2_000
        val TAGS = listOf("Xing", "Info", "VBRI")
    }
}
