package com.miuraken.chunkflow.spike.textai

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.miuraken.chunkflow.core.transcript.ChunkFlowTranscriptParser
import com.miuraken.chunkflow.core.transcript.CompositeTranscriptParser
import com.miuraken.chunkflow.core.transcript.TranscriptDocument
import com.miuraken.chunkflow.core.transcript.TranscriptError
import com.miuraken.chunkflow.core.transcript.TranscriptParseResult
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** [TranscriptFileReader.read] の結果。 */
internal sealed interface LoadOutcome {
    data class Loaded(val transcript: LoadedTranscript) : LoadOutcome
    data class Failed(val error: LoadError) : LoadOutcome
}

/**
 * ファイル選択（`ACTION_OPEN_DOCUMENT`）で選ばれた文字起こしファイルを読み、`core:transcript` で解析する。
 *
 * ファイルは信頼できない入力として扱う（AGENTS.md §10）。大きさは 20MB（§8.1）を読みながら確かめ、
 * 検証は `core:transcript` のパーサに任せる。ファイル名は形式の判定にだけ使い、画面・CSV・ログには出さない。
 */
internal class TranscriptFileReader(
    private val resolver: ContentResolver,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun read(uri: Uri): LoadOutcome = withContext(ioDispatcher) {
        val meta = queryMeta(uri)
        val declaredSize = meta.size
        if (declaredSize != null && declaredSize > MAX_BYTES) return@withContext LoadOutcome.Failed(LoadError.TOO_LARGE)
        val bytes = when (val read = readCapped(uri)) {
            is ReadBytes.Ok -> read.bytes
            ReadBytes.TooLarge -> return@withContext LoadOutcome.Failed(LoadError.TOO_LARGE)
            ReadBytes.Unreadable -> return@withContext LoadOutcome.Failed(LoadError.UNREADABLE)
        }
        when (val parsed = CompositeTranscriptParser().parse(bytes, meta.displayName.orEmpty())) {
            is TranscriptParseResult.Error -> LoadOutcome.Failed(parsed.error.toLoadError())
            is TranscriptParseResult.Ok -> toLoaded(parsed.document, fileId(bytes))
        }
    }

    private fun toLoaded(document: TranscriptDocument, fileId: String): LoadOutcome {
        if (document.segments.isEmpty()) return LoadOutcome.Failed(LoadError.NO_SEGMENTS)
        val languages = document.languages
        val kind = if (languages != null) {
            FileKind.CHUNKFLOW_JSON
        } else {
            when (document.generator.value) {
                "subtitle-vtt" -> FileKind.VTT
                "subtitle-lrc" -> FileKind.LRC
                else -> FileKind.SRT
            }
        }
        val segments = document.segments.mapIndexed { index, segment ->
            Segment(
                number = index + 1,
                startMs = segment.startMs,
                text = segment.text,
                fileLanguage = segment.language?.value?.let(::primaryLanguage),
            )
        }
        return LoadOutcome.Loaded(
            LoadedTranscript(
                kind = kind,
                fileId = fileId,
                segments = segments,
                fileTarget = languages?.target?.value?.let(::primaryLanguage),
                fileBase = languages?.base?.value?.let(::primaryLanguage),
            ),
        )
    }

    private fun queryMeta(uri: Uri): FileMeta = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use FileMeta(null, null)
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                FileMeta(
                    displayName = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null,
                    size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                )
            } ?: FileMeta(null, null)
    } catch (_: SecurityException) {
        FileMeta(null, null)
    } catch (_: IllegalArgumentException) {
        FileMeta(null, null)
    }

    /** 上限を超えたら読むのをやめる（ファイル全体の大きさを信じない）。 */
    private fun readCapped(uri: Uri): ReadBytes = try {
        val input = resolver.openInputStream(uri) ?: return ReadBytes.Unreadable
        input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_BYTES) return ReadBytes.TooLarge
                out.write(buffer, 0, count)
            }
            ReadBytes.Ok(out.toByteArray())
        }
    } catch (_: FileNotFoundException) {
        ReadBytes.Unreadable
    } catch (_: SecurityException) {
        ReadBytes.Unreadable
    } catch (_: IllegalArgumentException) {
        ReadBytes.Unreadable
    } catch (_: IOException) {
        ReadBytes.Unreadable
    }

    private fun fileId(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val builder = StringBuilder(FILE_ID_BYTES * 2)
        for (index in 0 until FILE_ID_BYTES) {
            val value = digest[index].toInt() and 0xFF
            builder.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return builder.toString()
    }

    private fun TranscriptError.toLoadError(): LoadError = when (this) {
        TranscriptError.UNKNOWN_FORMAT -> LoadError.UNKNOWN_FORMAT
        TranscriptError.UNSUPPORTED_VERSION -> LoadError.UNSUPPORTED_VERSION
        TranscriptError.SCHEMA_VIOLATION -> LoadError.INVALID
        TranscriptError.TOO_LARGE -> LoadError.TOO_LARGE
        TranscriptError.TIME_OUT_OF_RANGE -> LoadError.TIME_OUT_OF_RANGE
    }

    private data class FileMeta(val displayName: String?, val size: Long?)

    private sealed interface ReadBytes {
        data class Ok(val bytes: ByteArray) : ReadBytes
        data object TooLarge : ReadBytes
        data object Unreadable : ReadBytes
    }

    private companion object {
        /** 読み込むファイルの上限（本体と同じ 20MB。§8.1）。 */
        const val MAX_BYTES: Long = ChunkFlowTranscriptParser.MAX_FILE_SIZE_BYTES.toLong()
        const val BUFFER_SIZE = 64 * 1024
        const val FILE_ID_BYTES = 4
        const val HEX_DIGITS = "0123456789abcdef"
    }
}
