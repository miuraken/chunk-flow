package com.miuraken.chunkflow.core.data.transcript

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.miuraken.chunkflow.core.data.di.ChunkFlowDispatchers
import com.miuraken.chunkflow.core.data.di.Dispatcher
import com.miuraken.chunkflow.core.data.library.LibraryMediaFileResolver
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.data.library.TranscriptImportResult
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.toEntity
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.transcript.ChunkFlowTranscriptParser
import com.miuraken.chunkflow.core.transcript.TranscriptChunkConverter
import com.miuraken.chunkflow.core.transcript.TranscriptDocument
import com.miuraken.chunkflow.core.transcript.TranscriptError
import com.miuraken.chunkflow.core.transcript.TranscriptParseResult
import com.miuraken.chunkflow.core.transcript.TranscriptParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 複数の文字起こしファイルを一括で取り込んだ結果の集計（§7.4.6）。
 *
 * 同じ音声に対する複数の JSON が含まれていた場合は、`generator.createdAt` が最も新しい 1 件だけを取り込み、
 * 古い重複分は [skippedDuplicateCount] に数える。
 *
 * @property results 実際に取り込みを実行した各ファイルの結果一覧。
 * @property attachedCount 既存の教材への紐付けに成功した件数。
 * @property skippedDuplicateCount 同じ音声に対する複数の JSON のうち、古いためスキップされた件数。
 * @property noMatchingAudioCount 対応する音声がまだ取り込まれていなかった件数。
 * @property invalidCount 形式・Schema・意味の検証で拒否された件数。
 */
data class BatchTranscriptImportSummary(
    val results: List<TranscriptImportResult>,
    val attachedCount: Int = results.count { it is TranscriptImportResult.Attached },
    val skippedDuplicateCount: Int = 0,
    val noMatchingAudioCount: Int = results.count { it is TranscriptImportResult.NoMatchingAudio },
    val invalidCount: Int = results.count { it is TranscriptImportResult.Invalid },
)

/**
 * 文字起こしファイル（`chunkflow-transcript` JSON など）を検証し、既存の教材に対応付けて保存するインターフェース（§6.4、§7.4.6、§8.1）。
 */
interface TranscriptImporter {
    /**
     * 単一の文字起こしファイル URI を取り込み、対応する教材に紐付ける（§7.1、§7.4.6）。
     *
     * @param uri SAF の `content://` URI。それ以外のスキームや 20MB を超える入力は拒否する（§8.1）。
     */
    suspend fun importTranscript(uri: Uri): TranscriptImportResult

    /**
     * 複数の文字起こしファイル URI をまとめて取り込む（§7.4.6）。
     *
     * 同じ音声に対応する JSON が複数ある場合は、`generator.createdAt` が最も新しいものだけを取り込む。
     */
    suspend fun importBatch(uris: List<Uri>): BatchTranscriptImportSummary
}

/**
 * [TranscriptImporter] の本番実装（§6.4、§7.4.6、§8.1、§8.2）。
 *
 * - `content://` スキームのみを受け付け、ストリーム読み込み中に `20MB + 1` バイトで打ち切ることで OOM を防ぐ（§8.1）。
 * - [TranscriptParser] による検証を通過した後にのみ、原本ファイルの保存と DB トランザクションを実行する（§8.2）。
 * - 再取り込み時は [ChunkDao.replaceChunksTransaction] により手動修正済み（`userEdited = true`）の文字起こしを引き継ぐ（§6.4、§7.4.6）。
 */
@Singleton
class DefaultTranscriptImporter(
    @ApplicationContext private val context: Context,
    private val mediaItemDao: MediaItemDao,
    private val chunkDao: ChunkDao,
    private val itemStateDao: ItemStateDao,
    private val libraryMediaFileResolver: LibraryMediaFileResolver,
    @Dispatcher(ChunkFlowDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    private val transcriptParser: TranscriptParser = ChunkFlowTranscriptParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) : TranscriptImporter {

    // TODO(security): 文字起こしファイルの署名による改ざんの検知は行わない。自分の PC と自分の Google Drive の間でだけ受け渡すため。内容が不正な場合の影響は、検証と上限で抑える。

    @Inject
    constructor(
        @ApplicationContext context: Context,
        mediaItemDao: MediaItemDao,
        chunkDao: ChunkDao,
        itemStateDao: ItemStateDao,
        libraryMediaFileResolver: LibraryMediaFileResolver,
        @Dispatcher(ChunkFlowDispatchers.IO) ioDispatcher: CoroutineDispatcher,
    ) : this(
        context = context,
        mediaItemDao = mediaItemDao,
        chunkDao = chunkDao,
        itemStateDao = itemStateDao,
        libraryMediaFileResolver = libraryMediaFileResolver,
        ioDispatcher = ioDispatcher,
        transcriptParser = ChunkFlowTranscriptParser(),
        clock = System::currentTimeMillis,
    )

    override suspend fun importTranscript(uri: Uri): TranscriptImportResult = withContext(ioDispatcher) {
        when (val candidate = readAndParseUri(uri, orderIndex = 0)) {
            is ReadAndParseOutcome.Failed -> TranscriptImportResult.Invalid(candidate.error)
            is ReadAndParseOutcome.Parsed -> attachParsedTranscript(candidate)
        }
    }

    override suspend fun importBatch(uris: List<Uri>): BatchTranscriptImportSummary = withContext(ioDispatcher) {
        if (uris.isEmpty()) {
            return@withContext BatchTranscriptImportSummary(results = emptyList())
        }

        val results = mutableListOf<TranscriptImportResult>()
        val validCandidatesByAudioKey = LinkedHashMap<String, MutableList<ReadAndParseOutcome.Parsed>>()

        for ((index, uri) in uris.withIndex()) {
            currentCoroutineContext().ensureActive()
            when (val outcome = readAndParseUri(uri, orderIndex = index)) {
                is ReadAndParseOutcome.Failed -> {
                    results += TranscriptImportResult.Invalid(outcome.error)
                }

                is ReadAndParseOutcome.Parsed -> {
                    val key = audioGroupingKey(outcome.document, outcome.displayName, index)
                    validCandidatesByAudioKey.getOrPut(key) { mutableListOf() }.add(outcome)
                }
            }
        }

        var skippedDuplicates = 0
        for ((_, group) in validCandidatesByAudioKey) {
            currentCoroutineContext().ensureActive()
            if (group.size > 1) {
                skippedDuplicates += group.size - 1
            }
            val newest = selectNewestCandidate(group)
            results += attachParsedTranscript(newest)
        }

        BatchTranscriptImportSummary(
            results = results,
            skippedDuplicateCount = skippedDuplicates,
        )
    }

    private suspend fun readAndParseUri(uri: Uri, orderIndex: Int): ReadAndParseOutcome {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return ReadAndParseOutcome.Failed(TranscriptError.UNKNOWN_FORMAT)
        }

        val meta = queryContentMetadata(uri)
        val declaredSize = meta.sizeBytes
        if (declaredSize != null && declaredSize > MAX_TRANSCRIPT_BYTES) {
            return ReadAndParseOutcome.Failed(TranscriptError.TOO_LARGE)
        }

        val bytes = when (val readResult = readBoundedBytes(uri)) {
            is BoundedReadOutcome.Ok -> readResult.bytes
            is BoundedReadOutcome.TooLarge -> return ReadAndParseOutcome.Failed(TranscriptError.TOO_LARGE)
            is BoundedReadOutcome.IoError -> return ReadAndParseOutcome.Failed(TranscriptError.UNKNOWN_FORMAT)
        }

        val fileName = meta.displayName.orEmpty()
        return when (val parseResult = transcriptParser.parse(bytes, fileName)) {
            is TranscriptParseResult.Error -> ReadAndParseOutcome.Failed(parseResult.error)

            is TranscriptParseResult.Ok -> {
                val doc = parseResult.document
                val createdAtStr = doc.createdAt ?: ChunkFlowTranscriptParser.extractCreatedAt(bytes)
                val createdAtEpochMs = ChunkFlowTranscriptParser.parseCreatedAtEpochMs(createdAtStr)
                ReadAndParseOutcome.Parsed(
                    bytes = bytes,
                    displayName = meta.displayName,
                    document = doc,
                    createdAt = createdAtStr,
                    createdAtEpochMs = createdAtEpochMs,
                    orderIndex = orderIndex,
                )
            }
        }
    }

    private suspend fun attachParsedTranscript(candidate: ReadAndParseOutcome.Parsed): TranscriptImportResult {
        val document = candidate.document
        val mediaItem = findMatchingMediaItem(document, candidate.displayName)
            ?: return TranscriptImportResult.NoMatchingAudio(document.audioFileName)

        val mediaId = MediaId(mediaItem.id)
        val durationMismatch = document.durationMs?.let { docDuration ->
            abs(mediaItem.durationMs - docDuration) >= DURATION_MISMATCH_THRESHOLD_MS
        } ?: false

        // 原本ファイルを filesDir/media/<MediaId>.transcript.chunkflow.json に保存する（§6.4）
        if (!writeRawTranscriptFile(mediaId, candidate.bytes)) {
            return TranscriptImportResult.Invalid(TranscriptError.SCHEMA_VIOLATION)
        }

        val nowMs = clock()
        val convertedChunks = TranscriptChunkConverter.convert(
            segments = document.segments,
            audioDurationMs = mediaItem.durationMs,
        )

        val chunkEntities = convertedChunks.map { item ->
            ChunkEntity(
                id = 0L,
                mediaId = mediaItem.id,
                startMs = item.startMs,
                endMs = item.endMs,
                origin = ChunkOrigin.IMPORTED.name,
                algorithmVersion = ALGORITHM_VERSION,
            )
        }

        val newTranscriptsByIndex = convertedChunks.mapIndexed { index, item ->
            val seg = item.segment
            index to Transcript(
                chunkId = ChunkId(0L),
                text = seg.text,
                textVariants = seg.textVariants,
                language = seg.language,
                languageConfidence = seg.languageConfidence,
                words = seg.words,
                engine = document.generator,
                userEdited = false,
            ).toEntity(createdAtEpochMs = nowMs)
        }.toMap()

        val insertedChunks = chunkDao.replaceChunksTransaction(
            mediaId = mediaItem.id,
            origin = ChunkOrigin.IMPORTED.name,
            newChunks = chunkEntities,
            newTranscriptsByIndex = newTranscriptsByIndex,
            preserveAllOldTranscripts = false,
        )

        val languages = document.languages
        if (languages != null) {
            mediaItemDao.updateLanguagePair(
                id = mediaItem.id,
                targetLang = languages.target.value,
                baseLang = languages.base?.value,
            )
        }

        val updatedRows = itemStateDao.updateChunkSource(
            mediaId = mediaItem.id,
            chunkSource = ChunkOrigin.IMPORTED.name,
            updatedAt = nowMs,
        )
        if (updatedRows == 0) {
            itemStateDao.upsert(
                ItemStateEntity(
                    mediaId = mediaItem.id,
                    chunkSource = ChunkOrigin.IMPORTED.name,
                    updatedAt = nowMs,
                ),
            )
        }

        return TranscriptImportResult.Attached(
            mediaId = mediaId,
            chunkCount = insertedChunks.size,
            durationMismatch = durationMismatch,
        )
    }

    private suspend fun findMatchingMediaItem(
        document: TranscriptDocument,
        displayName: String?,
    ): MediaItemEntity? {
        val sha256 = document.audioSha256
        if (sha256 != null) {
            return mediaItemDao.getBySha256(sha256)
        }

        val candidateName = (document.audioFileName ?: displayName)
            ?.let(::extractBaseFileName)
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val allItems = mediaItemDao.observeAll().first()
        return allItems.firstOrNull { item ->
            extractBaseFileName(item.title).equals(candidateName, ignoreCase = true)
        }
    }

    private fun writeRawTranscriptFile(mediaId: MediaId, bytes: ByteArray): Boolean {
        if (!MediaFileLocator.isSafeIdentifier(mediaId.value)) return false
        val mediaDir = resolveMediaDirectory(mediaId).apply { mkdirs() }
        val targetFile = File(mediaDir, "${mediaId.value}$RAW_TRANSCRIPT_SUFFIX")
        return try {
            val parentCanonical = mediaDir.canonicalFile
            val targetCanonical = targetFile.canonicalFile
            if (targetCanonical.parentFile != parentCanonical) {
                return false
            }
            targetFile.writeBytes(bytes)
            true
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun resolveMediaDirectory(mediaId: MediaId): File {
        if (libraryMediaFileResolver is MediaFileLocator) {
            return libraryMediaFileResolver.mediaDir
        }
        val parentDir = libraryMediaFileResolver.resolveAudioFile(mediaId)?.parentFile
        if (parentDir != null) {
            return parentDir
        }
        return File(context.filesDir, MEDIA_DIR_NAME)
    }

    private suspend fun readBoundedBytes(uri: Uri): BoundedReadOutcome {
        val resolver = context.contentResolver
        val inputStream = try {
            resolver.openInputStream(uri)
        } catch (_: SecurityException) {
            return BoundedReadOutcome.IoError
        } catch (_: IOException) {
            return BoundedReadOutcome.IoError
        } ?: return BoundedReadOutcome.IoError

        val buffer = ByteArray(READ_BUFFER_BYTES)
        val out = ByteArrayOutputStream()
        var totalBytes = 0

        return try {
            inputStream.use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    totalBytes += read
                    if (totalBytes > MAX_TRANSCRIPT_BYTES) {
                        return BoundedReadOutcome.TooLarge
                    }
                    out.write(buffer, 0, read)
                }
            }
            BoundedReadOutcome.Ok(out.toByteArray())
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            BoundedReadOutcome.IoError
        } catch (_: SecurityException) {
            BoundedReadOutcome.IoError
        }
    }

    private fun queryContentMetadata(uri: Uri): ContentMetadata {
        val resolver = context.contentResolver
        var displayName: String? = null
        var sizeBytes: Long? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                        displayName = cursor.getString(nameIdx)
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        sizeBytes = cursor.getLong(sizeIdx)
                    }
                }
            }
        }
        return ContentMetadata(displayName = displayName, sizeBytes = sizeBytes)
    }

    private fun selectNewestCandidate(
        candidates: List<ReadAndParseOutcome.Parsed>,
    ): ReadAndParseOutcome.Parsed = candidates.maxWith(
        Comparator { a, b ->
            val epochA = a.createdAtEpochMs
            val epochB = b.createdAtEpochMs
            if (epochA != null && epochB != null && epochA != epochB) {
                return@Comparator epochA.compareTo(epochB)
            }
            if (epochA != null && epochB == null) return@Comparator 1
            if (epochA == null && epochB != null) return@Comparator -1

            val strCompare = a.createdAt.orEmpty().compareTo(b.createdAt.orEmpty())
            if (strCompare != 0) return@Comparator strCompare

            a.orderIndex.compareTo(b.orderIndex)
        },
    )

    private fun audioGroupingKey(
        document: TranscriptDocument,
        displayName: String?,
        orderIndex: Int,
    ): String {
        val sha = document.audioSha256
        if (!sha.isNullOrBlank()) {
            return "sha256:$sha"
        }
        val baseName = (document.audioFileName ?: displayName)?.let(::extractBaseFileName)
        if (!baseName.isNullOrBlank()) {
            return "name:${baseName.lowercase()}"
        }
        return "index:$orderIndex"
    }

    private data class ContentMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
    )

    private sealed interface BoundedReadOutcome {
        data class Ok(val bytes: ByteArray) : BoundedReadOutcome
        data object TooLarge : BoundedReadOutcome
        data object IoError : BoundedReadOutcome
    }

    private sealed interface ReadAndParseOutcome {
        data class Parsed(
            val bytes: ByteArray,
            val displayName: String?,
            val document: TranscriptDocument,
            val createdAt: String?,
            val createdAtEpochMs: Long?,
            val orderIndex: Int,
        ) : ReadAndParseOutcome

        data class Failed(val error: TranscriptError) : ReadAndParseOutcome
    }

    companion object {
        /** 文字起こしファイルの最大バイト数: 20 MB（§8.1）。 */
        const val MAX_TRANSCRIPT_BYTES: Int = ChunkFlowTranscriptParser.MAX_FILE_SIZE_BYTES

        /** 教材の長さと文字起こしの `durationMs` の不一致を警告する閾値: 500ms（§7.4.6）。 */
        const val DURATION_MISMATCH_THRESHOLD_MS: Long = 500L

        /** 原本文字起こしファイルの保存名サフィックス（§6.4: `filesDir/media/<MediaId>.transcript.chunkflow.json`）。 */
        const val RAW_TRANSCRIPT_SUFFIX: String = ".transcript.chunkflow.json"

        private const val MEDIA_DIR_NAME: String = "media"
        private const val READ_BUFFER_BYTES: Int = 16 * 1024
        private const val ALGORITHM_VERSION: Int = 1

        internal fun extractBaseFileName(rawName: String): String {
            val fileName = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
            val withoutTranscriptSuffix = when {
                fileName.endsWith(".chunkflow.json", ignoreCase = true) ->
                    fileName.dropLast(".chunkflow.json".length)

                fileName.endsWith(".transcript.json", ignoreCase = true) ->
                    fileName.dropLast(".transcript.json".length)

                else -> fileName
            }
            return withoutTranscriptSuffix.replace(
                Regex("\\.(json|srt|vtt|lrc|mp3|m4a|mp4|ogg|opus|flac|wav|aac)$", RegexOption.IGNORE_CASE),
                "",
            ).trim()
        }
    }
}
