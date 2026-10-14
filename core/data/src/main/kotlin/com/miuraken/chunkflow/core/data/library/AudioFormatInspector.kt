package com.miuraken.chunkflow.core.data.library

import android.content.Context
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.inspector.MediaExtractorCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * 取り込み時に検証した音声ファイルの形式・メタデータ情報（§7.1、ADR 0006、ADR 0008）。
 *
 * @property mimeType 正規化した MIME タイプ（例: `audio/mpeg`, `audio/mp4`）。
 * @property extension 保存に使うファイル拡張子（`.` なし。`mp3`, `m4a`, `ogg`, `opus`, `flac`, `wav`）。
 * @property durationMs 音声の長さ（ミリ秒、正の値）。
 * @property title メタデータ（ID3 タグ等）に含まれるタイトル。なければ `null`。
 * @property sampleRateHz サンプルレート（Hz、例: 44100, 22050, 48000）。
 * @property mp3HeaderInfo MP3 の場合の先頭フレーム・ヘッダ解析結果。MP3 以外は `null`。
 */
data class AudioFormatInfo(
    val mimeType: String,
    val extension: String,
    val durationMs: Long,
    val title: String? = null,
    val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
    val mp3HeaderInfo: Mp3HeaderInfo? = null,
) {
    companion object {
        /** サンプルレートが取得できない場合のフォールバック値（44.1kHz）。 */
        const val DEFAULT_SAMPLE_RATE_HZ: Int = 44_100
    }
}

/**
 * MP3 の先頭フレーム・VBR/CBR ヘッダ・LAME タグの情報（ADR 0006、ADR 0008）。
 *
 * @property hasHeader 先頭フレームに `Xing` / `Info` / `VBRI` ヘッダがあるか。
 * @property samplesPerFrame 1 フレームあたりのサンプル数（MPEG-1 は 1152、MPEG-2 / 2.5 は 576）。
 * @property encoderDelay LAME タグのエンコーダ遅延（サンプル数）。タグがない場合は LAME 標準の 576。
 * @property sampleRateHz フレームヘッダから読み取ったサンプルレート（Hz）。
 */
data class Mp3HeaderInfo(
    val hasHeader: Boolean,
    val samplesPerFrame: Int = SAMPLES_PER_FRAME_MPEG1,
    val encoderDelay: Int = DEFAULT_LAME_ENCODER_DELAY,
    val sampleRateHz: Int = AudioFormatInfo.DEFAULT_SAMPLE_RATE_HZ,
) {
    companion object {
        /** MPEG-1 Layer III の 1 フレームあたりのサンプル数。 */
        const val SAMPLES_PER_FRAME_MPEG1: Int = 1152

        /** MPEG-2 / 2.5 Layer III の 1 フレームあたりのサンプル数。 */
        const val SAMPLES_PER_FRAME_MPEG2: Int = 576

        /** LAME の標準エンコーダ遅延（サンプル数、ADR 0008）。 */
        const val DEFAULT_LAME_ENCODER_DELAY: Int = 576

        /** ヘッダなし MP3 における ExoPlayer と ffmpeg のサンプル差（ADR 0008）。 */
        const val HEADERLESS_MP3_OFFSET_SAMPLES: Int = -529
    }
}

/**
 * ADR 0008 の決定 2 に従い、音声の形式・MP3 ヘッダ情報から `transcriptOffsetMs` の初期値を計算する。
 *
 * - MP3（先頭フレームに `Xing` / `Info` / `VBRI` ヘッダあり）: `+(1 フレームのサンプル数 + エンコーダ遅延) / sr`
 * - MP3（ヘッダなし）: `-529 / sr`
 * - MP3 以外: `0`
 */
fun calculateInitialTranscriptOffsetMs(info: AudioFormatInfo): Int {
    val isMp3 = info.mimeType == "audio/mpeg" || info.extension == "mp3" || info.mp3HeaderInfo != null
    if (!isMp3) return 0
    val mp3 = info.mp3HeaderInfo ?: Mp3HeaderInfo(hasHeader = false, sampleRateHz = info.sampleRateHz)
    val sr = (if (mp3.sampleRateHz > 0) mp3.sampleRateHz else info.sampleRateHz)
        .coerceAtLeast(1)
    val offsetSamples = if (mp3.hasHeader) {
        mp3.samplesPerFrame + mp3.encoderDelay
    } else {
        Mp3HeaderInfo.HEADERLESS_MP3_OFFSET_SAMPLES
    }
    return (offsetSamples * 1_000.0 / sr.toDouble()).roundToInt()
}

/**
 * 音声ファイルの形式検証とメタデータ抽出を行うインターフェース（§7.1）。
 *
 * テストでは Fake 実装に差し替えられるようにする。
 */
fun interface AudioFormatInspector {
    /**
     * [file] を検査し、対応する音声ファイルであれば [AudioFormatInfo] を返す。
     * 音声トラックがない場合や非対応形式・破損ファイルの場合は `null` を返す。
     */
    suspend fun inspect(file: File): AudioFormatInfo?
}

/**
 * Media3 の [MediaExtractorCompat] と [MediaMetadataRetriever] および [Mp3HeaderParser] を組み合わせた
 * [AudioFormatInspector] の本番実装（§7.1、ADR 0005、ADR 0006、ADR 0008）。
 */
@Singleton
class DefaultAudioFormatInspector @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioFormatInspector {

    @OptIn(UnstableApi::class)
    override suspend fun inspect(file: File): AudioFormatInfo? {
        if (!file.exists() || !file.isFile || file.length() <= 0L) return null

        var rawMime: String? = null
        var sampleRateHz = AudioFormatInfo.DEFAULT_SAMPLE_RATE_HZ
        var extractorDurationMs = 0L

        val extractor = MediaExtractorCompat(context)
        try {
            extractor.setDataSource(Uri.fromFile(file), 0L)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime != null && (mime.startsWith("audio/") || mime == "application/ogg")) {
                    rawMime = mime
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val sr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (sr > 0) sampleRateHz = sr
                    }
                    if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        val durationUs = format.getLong(MediaFormat.KEY_DURATION)
                        if (durationUs > 0L) extractorDurationMs = durationUs / 1_000L
                    }
                    break
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
                // ignore release errors
            }
        }

        val nonNullMime = rawMime ?: return null
        val normalizedMime = MediaFileLocator.normalizeAudioMimeType(nonNullMime) ?: return null
        val extension = MediaFileLocator.extensionForMimeType(normalizedMime) ?: return null

        var retrieverDurationMs = 0L
        var metadataTitle: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retrieverDurationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            metadataTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            // メタデータ取得に失敗しても MediaExtractorCompat の情報があれば続行する
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // ignore
            }
        }

        val durationMs = if (retrieverDurationMs > 0L) retrieverDurationMs else extractorDurationMs
        if (durationMs <= 0L) return null

        val mp3Info = if (normalizedMime == "audio/mpeg") {
            Mp3HeaderParser.parse(file, fallbackSampleRateHz = sampleRateHz)
        } else {
            null
        }
        val effectiveSampleRate = mp3Info?.sampleRateHz ?: sampleRateHz

        return AudioFormatInfo(
            mimeType = normalizedMime,
            extension = extension,
            durationMs = durationMs,
            title = metadataTitle,
            sampleRateHz = effectiveSampleRate,
            mp3HeaderInfo = mp3Info,
        )
    }
}

/**
 * MP3 ファイルの先頭フレームを解析し、`Xing` / `Info` / `VBRI` ヘッダの有無、
 * MPEG バージョンによる 1 フレームのサンプル数（1152 / 576）、および LAME タグのエンコーダ遅延を読み取る（ADR 0006、ADR 0008）。
 */
object Mp3HeaderParser {
    private const val SCAN_BYTES = 64 * 1024
    private const val FIRST_FRAME_SEARCH_MAX = 4_096
    private const val HEADER_TAG_OFFSET_MAX = 2_000
    private val HEADER_TAGS = listOf("Xing", "Info", "VBRI")

    private val SAMPLE_RATES_MPEG1 = intArrayOf(44_100, 48_000, 32_000)
    private val SAMPLE_RATES_MPEG2 = intArrayOf(22_050, 24_000, 16_000)
    private val SAMPLE_RATES_MPEG25 = intArrayOf(11_025, 12_000, 8_000)

    /**
     * MP3 ファイルを読み取って [Mp3HeaderInfo] を返す。
     */
    fun parse(
        file: File,
        fallbackSampleRateHz: Int = AudioFormatInfo.DEFAULT_SAMPLE_RATE_HZ,
    ): Mp3HeaderInfo {
        if (!file.exists() || !file.isFile || file.length() < 4L) {
            return Mp3HeaderInfo(hasHeader = false, sampleRateHz = fallbackSampleRateHz)
        }
        val head = RandomAccessFile(file, "r").use { raf ->
            val len = minOf(raf.length(), SCAN_BYTES.toLong()).toInt()
            ByteArray(len).also { raf.readFully(it) }
        }
        return parseBytes(head, fallbackSampleRateHz)
    }

    /**
     * MP3 の先頭バイト列から [Mp3HeaderInfo] を解析する。
     */
    fun parseBytes(
        head: ByteArray,
        fallbackSampleRateHz: Int = AudioFormatInfo.DEFAULT_SAMPLE_RATE_HZ,
    ): Mp3HeaderInfo {
        val start = id3v2Size(head)
        if (start >= head.size) {
            return Mp3HeaderInfo(hasHeader = false, sampleRateHz = fallbackSampleRateHz)
        }

        val frameHeaderPos = findFirstFrameSync(head, start)
        var samplesPerFrame = Mp3HeaderInfo.SAMPLES_PER_FRAME_MPEG1
        var sampleRateHz = fallbackSampleRateHz
        if (frameHeaderPos >= 0 && frameHeaderPos + 4 <= head.size) {
            val b1 = head[frameHeaderPos + 1].toInt() and 0xFF
            val b2 = head[frameHeaderPos + 2].toInt() and 0xFF
            val versionBits = (b1 ushr 3) and 0x03
            val srIndex = (b2 ushr 2) and 0x03
            samplesPerFrame = if (versionBits == 3) {
                Mp3HeaderInfo.SAMPLES_PER_FRAME_MPEG1
            } else {
                Mp3HeaderInfo.SAMPLES_PER_FRAME_MPEG2
            }
            if (srIndex in 0..2) {
                sampleRateHz = when (versionBits) {
                    3 -> SAMPLE_RATES_MPEG1[srIndex]
                    2 -> SAMPLE_RATES_MPEG2[srIndex]
                    0 -> SAMPLE_RATES_MPEG25[srIndex]
                    else -> fallbackSampleRateHz
                }
            }
        }

        val searchBase = if (frameHeaderPos >= 0) frameHeaderPos else start
        val text = String(head, Charsets.ISO_8859_1)
        val tagPos = HEADER_TAGS
            .map { tag -> text.indexOf(tag, searchBase) }
            .filter { it >= 0 && it <= searchBase + HEADER_TAG_OFFSET_MAX }
            .minOrNull()

        if (tagPos == null) {
            return Mp3HeaderInfo(
                hasHeader = false,
                samplesPerFrame = samplesPerFrame,
                encoderDelay = Mp3HeaderInfo.DEFAULT_LAME_ENCODER_DELAY,
                sampleRateHz = sampleRateHz,
            )
        }

        val tagName = text.substring(tagPos, (tagPos + 4).coerceAtMost(text.length))
        val encoderDelay = if (tagName == "Xing" || tagName == "Info") {
            parseLameEncoderDelay(head, tagPos) ?: Mp3HeaderInfo.DEFAULT_LAME_ENCODER_DELAY
        } else {
            Mp3HeaderInfo.DEFAULT_LAME_ENCODER_DELAY
        }

        return Mp3HeaderInfo(
            hasHeader = true,
            samplesPerFrame = samplesPerFrame,
            encoderDelay = encoderDelay,
            sampleRateHz = sampleRateHz,
        )
    }

    /**
     * 先頭の ID3v2 タグのバイトサイズ（10 バイトヘッダ + 本体 + フッタ）を返す。ID3v2 がなければ 0。
     */
    fun id3v2Size(head: ByteArray): Int {
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

    private fun findFirstFrameSync(head: ByteArray, startOffset: Int): Int {
        val limit = minOf(head.size - 4, startOffset + FIRST_FRAME_SEARCH_MAX)
        for (i in startOffset..limit) {
            val b0 = head[i].toInt() and 0xFF
            val b1 = head[i + 1].toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) continue
            val versionBits = (b1 ushr 3) and 0x03
            val layerBits = (b1 ushr 1) and 0x03
            val b2 = head[i + 2].toInt() and 0xFF
            val bitrateIndex = (b2 ushr 4) and 0x0F
            val srIndex = (b2 ushr 2) and 0x03
            if (versionBits != 1 && layerBits != 0 && bitrateIndex in 1..14 && srIndex != 3) {
                return i
            }
        }
        return -1
    }

    private fun parseLameEncoderDelay(head: ByteArray, xingTagPos: Int): Int? {
        if (xingTagPos + 8 > head.size) return null
        val flags = readBigEndianInt(head, xingTagPos + 4)
        var offset = xingTagPos + 8
        if ((flags and 0x01) != 0) offset += 4 // frames
        if ((flags and 0x02) != 0) offset += 4 // bytes
        if ((flags and 0x04) != 0) offset += 100 // TOC
        if ((flags and 0x08) != 0) offset += 4 // quality

        // LAME タグの先頭から 21..23 バイト目に 12bit の encoderDelay と 12bit の encoderPadding がある
        val delayPos = offset + 21
        if (delayPos + 3 > head.size) return null

        val encoderSignature = String(head, offset, minOf(4, head.size - offset), Charsets.ISO_8859_1)
        val hasKnownEncoderTag = encoderSignature == "LAME" ||
            encoderSignature == "Lavc" ||
            encoderSignature == "Lavf" ||
            encoderSignature == "GOGO"
        if (!hasKnownEncoderTag) return null

        val b0 = head[delayPos].toInt() and 0xFF
        val b1 = head[delayPos + 1].toInt() and 0xFF
        val delay = (b0 shl 4) or ((b1 and 0xF0) ushr 4)
        return if (delay in 0..2_880) delay else null
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int = ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}
