package com.miuraken.chunkflow.spike.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import java.io.File
import java.nio.ByteBuffer

/** 比較する抽出器の種類。 */
enum class ExtractorKind {
    /** android.media.MediaExtractor（プラットフォームの抽出器） */
    PLATFORM,

    /** media3-inspector の MediaExtractorCompat（ExoPlayer と同じ Media3 の抽出器） */
    COMPAT,

    /**
     * MediaExtractorCompat に、MP3 の Xing / Info / VBRI ヘッダを隠す [IndexSeekDataSource] を渡したもの
     * （ADR 0005 / 0006 の案。ExoPlayer も同じ DataSource と `FLAG_ENABLE_INDEX_SEEKING` で再生する）。
     */
    COMPAT_HIDDEN,
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
        @OptIn(UnstableApi::class)
        fun open(kind: ExtractorKind, context: Context, path: String): SampleExtractor = when (kind) {
            ExtractorKind.PLATFORM -> PlatformExtractor(path)
            ExtractorKind.COMPAT -> CompatExtractor(MediaExtractorCompat(context), path)
            ExtractorKind.COMPAT_HIDDEN -> CompatExtractor(HiddenHeaderMedia.extractorCompat(context), path)
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
private class CompatExtractor(private val ex: MediaExtractorCompat, path: String) : SampleExtractor {
    init {
        ex.setDataSource(Uri.fromFile(File(path)), 0)
    }

    override val trackCount: Int get() = ex.trackCount
    override fun trackFormat(index: Int): MediaFormat = ex.getTrackFormat(index)
    override fun selectTrack(index: Int) = ex.selectTrack(index)
    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int = ex.readSampleData(buffer, offset)
    override val sampleTime: Long get() = ex.sampleTime
    override fun advance(): Boolean = ex.advance()
    override fun close() = ex.release()
}
