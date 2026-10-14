package com.miuraken.chunkflow.spike.playback.measure

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.miuraken.chunkflow.spike.playback.SpikeLog
import java.io.File
import java.nio.ByteBuffer

/**
 * MP3 を再エンコードせずに MP4 コンテナへ詰め替える試み（§12 の対策 (a) を端末だけでできるか）。
 *
 * プラットフォームの `MediaExtractor` で MP3 のフレームを先頭から順に読み（順に読むので時刻はフレーム数から正確に決まる）、
 * `MediaMuxer`（MPEG-4）に書く。`MediaMuxer` が `audio/mpeg` を受け付けるかどうかが要点。
 */
object Mp4Repackager {
    /** 詰め替えの結果。 */
    sealed interface Result {
        /** 成功。[samples] は書いたフレーム数、[lastPtsUs] は最後のフレームの時刻。 */
        data class Ok(val file: File, val samples: Int, val lastPtsUs: Long, val elapsedMs: Long) : Result

        /** 失敗（理由の文字列。スパイクなので例外のメッセージをそのまま持つ）。 */
        data class Failed(val reason: String) : Result
    }

    /** [src] を [dst]（MP4）に詰め替える。 */
    fun repackage(src: File, dst: File): Result {
        val start = System.nanoTime()
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            extractor.setDataSource(src.path)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return Result.Failed("音声トラックがない")
            val format = extractor.getTrackFormat(track)
            SpikeLog.i("[repackage] 入力の形式: $format")
            extractor.selectTrack(track)
            val m = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val outTrack = m.addTrack(format)
            m.start()
            val buffer = ByteBuffer.allocate(64 * 1024)
            val info = MediaCodec.BufferInfo()
            var count = 0
            var lastPts = 0L
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                m.writeSampleData(outTrack, buffer, info)
                lastPts = extractor.sampleTime
                count++
                extractor.advance()
            }
            m.stop()
            Result.Ok(dst, count, lastPts, (System.nanoTime() - start) / 1_000_000)
        } catch (e: IllegalArgumentException) {
            Result.Failed("IllegalArgumentException: ${e.message}")
        } catch (e: IllegalStateException) {
            Result.Failed("IllegalStateException: ${e.message}")
        } catch (e: java.io.IOException) {
            Result.Failed("IOException: ${e.message}")
        } finally {
            extractor.release()
            runCatching { muxer?.release() }
        }
    }
}
