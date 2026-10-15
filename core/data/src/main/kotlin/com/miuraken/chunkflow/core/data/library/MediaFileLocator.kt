package com.miuraken.chunkflow.core.data.library

import android.content.Context
import com.miuraken.chunkflow.core.model.MediaId
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MediaId] から内部ストレージ上の音声ファイル（`filesDir/media/<MediaId>.<ext>`）を解決するインターフェース（§6.4、§8.2）。
 */
fun interface LibraryMediaFileResolver {
    /**
     * 指定した [mediaId] に対応する音声ファイルを返す。存在しない場合は `null` を返す。
     */
    fun resolveAudioFile(mediaId: MediaId): File?
}

/**
 * 教材の音声ファイル（`filesDir/media/`）と解析ファイル（`filesDir/analysis/`）のパスを安全に管理するクラス（§6.4、§8.2）。
 *
 * ファイル名には UUID（[MediaId]）のみを使い、削除・参照時は正規化したパスが管理ディレクトリ配下にあることを検証する。
 */
@Singleton
class MediaFileLocator(
    private val filesDir: File,
) : LibraryMediaFileResolver {

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.filesDir)

    /** 音声ファイルの保存ディレクトリ（`filesDir/media`）。 */
    val mediaDir: File
        get() = File(filesDir, MEDIA_DIR_NAME)

    /** 解析結果ファイルの保存ディレクトリ（`filesDir/analysis`）。 */
    val analysisRootDir: File
        get() = File(filesDir, ANALYSIS_DIR_NAME)

    override fun resolveAudioFile(mediaId: MediaId): File? = resolveAudioFile(mediaId, mimeType = null)

    /**
     * [mediaId]（および任意の [mimeType]）から既存の音声ファイルを探す。
     *
     * ファイルが存在しない場合（バックアップ復元後など、§6.4）は `null` を返す。
     */
    fun resolveAudioFile(mediaId: MediaId, mimeType: String?): File? {
        if (!isSafeIdentifier(mediaId.value)) return null
        val dir = mediaDir
        if (!dir.exists() || !dir.isDirectory) return null

        if (mimeType != null) {
            val ext = extensionForMimeType(mimeType)
            if (ext != null) {
                val candidate = File(dir, "${mediaId.value}.$ext")
                if (candidate.isFile && isChildOf(candidate, dir)) {
                    return candidate
                }
            }
        }

        for (ext in SUPPORTED_AUDIO_EXTENSIONS) {
            val candidate = File(dir, "${mediaId.value}.$ext")
            if (candidate.isFile && isChildOf(candidate, dir)) {
                return candidate
            }
        }
        return null
    }

    /**
     * 取り込み中の一時ファイル（`filesDir/media/<uuid>.tmp`）を返す。
     */
    fun tempFileFor(uuid: String): File {
        require(isSafeIdentifier(uuid)) { "Unsafe temp file identifier" }
        val dir = mediaDir.apply { mkdirs() }
        val target = File(dir, "$uuid.tmp")
        require(isChildOf(target, dir)) { "Temp file path escapes mediaDir" }
        return target
    }

    /**
     * 確定した音声ファイルの保存先（`filesDir/media/<MediaId>.<ext>`）を返す。
     */
    fun audioFileFor(mediaId: MediaId, extension: String): File {
        require(isSafeIdentifier(mediaId.value)) { "Unsafe MediaId" }
        val normalizedExt = extension.lowercase().removePrefix(".")
        require(normalizedExt in SUPPORTED_AUDIO_EXTENSIONS) { "Unsupported audio extension: $extension" }
        val dir = mediaDir.apply { mkdirs() }
        val target = File(dir, "${mediaId.value}.$normalizedExt")
        require(isChildOf(target, dir)) { "Audio file path escapes mediaDir" }
        return target
    }

    /**
     * 取り込んだ文字起こし原本ファイルの保存先（`filesDir/media/<MediaId>.transcript.<ext>`）を返す（§6.4）。
     */
    fun transcriptFileFor(mediaId: MediaId, extension: String = "chunkflow.json"): File {
        require(isSafeIdentifier(mediaId.value)) { "Unsafe MediaId" }
        val normalizedExt = extension.lowercase().removePrefix(".")
        require(normalizedExt in SUPPORTED_TRANSCRIPT_EXTENSIONS) { "Unsupported transcript extension: $extension" }
        val dir = mediaDir.apply { mkdirs() }
        val target = File(dir, "${mediaId.value}.transcript.$normalizedExt")
        require(isChildOf(target, dir)) { "Transcript file path escapes mediaDir" }
        return target
    }

    /**
     * 指定した [mediaId] に関連する音声ファイル・原本文字起こしファイル（`filesDir/media/<id>.*`）および
     * 解析ディレクトリ（`filesDir/analysis/<id>/`）をすべて削除する（§6.4、§8.2）。
     */
    fun deleteMediaFiles(mediaId: MediaId) {
        if (!isSafeIdentifier(mediaId.value)) return
        val mDir = mediaDir
        if (mDir.exists() && mDir.isDirectory) {
            val prefix = "${mediaId.value}."
            mDir.listFiles()?.forEach { file ->
                if ((file.name == mediaId.value || file.name.startsWith(prefix)) && isChildOf(file, mDir)) {
                    file.delete()
                }
            }
        }

        val aRoot = analysisRootDir
        if (aRoot.exists() && aRoot.isDirectory) {
            val itemAnalysisDir = File(aRoot, mediaId.value)
            if (itemAnalysisDir.exists() && isChildOf(itemAnalysisDir, aRoot)) {
                itemAnalysisDir.deleteRecursively()
            }
        }
    }

    /**
     * 指定した [mediaId] の文字起こし原本ファイル（`filesDir/media/<id>.transcript.*`）をすべて削除する。
     */
    fun deleteTranscriptFiles(mediaId: MediaId) {
        if (!isSafeIdentifier(mediaId.value)) return
        val mDir = mediaDir
        if (mDir.exists() && mDir.isDirectory) {
            val prefix = "${mediaId.value}.transcript."
            mDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(prefix) && isChildOf(file, mDir)) {
                    file.delete()
                }
            }
        }
    }

    private fun isChildOf(target: File, parentDir: File): Boolean = try {
        val parentCanonical = parentDir.canonicalFile
        val targetCanonical = target.canonicalFile
        targetCanonical.parentFile == parentCanonical
    } catch (_: Exception) {
        false
    }

    companion object {
        private const val MEDIA_DIR_NAME = "media"
        private const val ANALYSIS_DIR_NAME = "analysis"
        private val SAFE_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,128}$")

        /** サポートする音声ファイルの拡張子一覧（§3 対応形式）。 */
        val SUPPORTED_AUDIO_EXTENSIONS: Set<String> = setOf(
            "mp3",
            "m4a",
            "ogg",
            "opus",
            "flac",
            "wav",
        )

        /** サポートする文字起こし原本ファイルの拡張子一覧（§6.4、§7.4.6）。 */
        val SUPPORTED_TRANSCRIPT_EXTENSIONS: Set<String> = setOf(
            "chunkflow.json",
            "json",
            "srt",
            "vtt",
            "lrc",
        )

        /** 識別子がパストラバーサルを含まない安全な文字列かを確認する（§8.1、§8.2）。 */
        fun isSafeIdentifier(id: String): Boolean = SAFE_ID_REGEX.matches(id)

        /** MIME タイプから保存用の拡張子を求める。非対応の MIME タイプなら `null` を返す。 */
        fun extensionForMimeType(mimeType: String): String? = when (mimeType.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/mp4a-latm", "audio/aac", "audio/x-m4a" -> "m4a"
            "audio/ogg", "audio/vorbis", "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/raw", "audio/wav", "audio/x-wav", "audio/vnd.wave" -> "wav"
            else -> null
        }

        /** MIME タイプをアプリ内部の正規形に揃える。非対応なら `null` を返す。 */
        fun normalizeAudioMimeType(mimeType: String): String? = when (mimeType.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "audio/mpeg"
            "audio/mp4", "audio/mp4a-latm", "audio/aac", "audio/x-m4a" -> "audio/mp4"
            "audio/ogg", "audio/vorbis", "application/ogg" -> "audio/ogg"
            "audio/opus" -> "audio/opus"
            "audio/flac", "audio/x-flac" -> "audio/flac"
            "audio/raw", "audio/wav", "audio/x-wav", "audio/vnd.wave" -> "audio/wav"
            else -> null
        }
    }
}
