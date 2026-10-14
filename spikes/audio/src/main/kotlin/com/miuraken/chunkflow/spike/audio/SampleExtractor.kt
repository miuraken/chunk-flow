package com.miuraken.chunkflow.spike.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import java.nio.ByteBuffer

/** 比較する抽出器の種類。 */
enum class ExtractorKind {
    /** android.media.MediaExtractor（プラットフォームの抽出器） */
    PLATFORM,

    /** media3-inspector の MediaExtractorCompat（ExoPlayer と同じ Media3 の抽出器） */
    COMPAT,
}

/** MediaExtractor と MediaExtractorCompat の共通部分（S3 で使う範囲だけ）。 */
interface SampleExtractor : AutoCloseable {
    /** トラック数 */
    val trackCount: Int

    /** トラックの形式 */
    fun trackFormat(index: Int): MediaFormat

    /** トラックを選ぶ */
    fun selectTrack(index: Int)

    /** 現在のサンプルを [buffer] に読む。終わりなら -1。 */
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int

    /** 現在のサンプルの時刻（マイクロ秒） */
    val sampleTime: Long

    /** 次のサンプルへ進む */
    fun advance(): Boolean

    /** 抽出器を作る。 */
    companion object {
        /** [kind] の抽出器で [path] を開く。 */
        fun open(kind: ExtractorKind, context: Context, path: String): SampleExtractor = when (kind) {
            ExtractorKind.PLATFORM -> PlatformExtractor(path)
            ExtractorKind.COMPAT -> CompatExtractor(context, path)
        }
    }
}

private class PlatformExtractor(path: String) : SampleExtractor {
    private val ex = MediaExtractor().apply { setDataSource(path) }
    override val trackCount: Int get() = ex.trackCount
    override fun trackFormat(index: Int): MediaFormat = ex.getTrackFormat(index)
    override fun selectTrack(index: Int) = ex.selectTrack(index)
    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int = ex.readSampleData(buffer, offset)
    override val sampleTime: Long get() = ex.sampleTime
    override fun advance(): Boolean = ex.advance()
    override fun close() = ex.release()
}

@OptIn(UnstableApi::class)
private class CompatExtractor(context: Context, path: String) : SampleExtractor {
    private val ex = MediaExtractorCompat(context).apply { setDataSource(path) }
    override val trackCount: Int get() = ex.trackCount
    override fun trackFormat(index: Int): MediaFormat = ex.getTrackFormat(index)
    override fun selectTrack(index: Int) = ex.selectTrack(index)
    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int = ex.readSampleData(buffer, offset)
    override val sampleTime: Long get() = ex.sampleTime
    override fun advance(): Boolean = ex.advance()
    override fun close() = ex.release()
}
