package com.miuraken.chunkflow.core.data.library

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.miuraken.chunkflow.core.data.di.ChunkFlowDispatchers
import com.miuraken.chunkflow.core.data.di.Dispatcher
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.toEntity
import com.miuraken.chunkflow.core.database.entity.toExternalModel
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.transcript.TranscriptError
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * [LibraryRepository] の本番実装（§6.3、§6.4、§7.1、§8.1、ADR 0006、ADR 0008）。
 *
 * - 取り込むファイルはすべて信頼できない入力として扱い、`content://` スキームの検証、サイズ上限（2GB）、
 *   空き容量（ファイルサイズの 1.2 倍以上）、SHA-256 による重複検出、デコーダによる形式検証を通す（§8.1）。
 * - 表示名はサニタイズして `title` の初期値にのみ使い、ファイルパスには一切使わない（保存名は UUID）。
 * - バックアップからの復元後（§6.4）に同じ音声を取り込み直した場合、DB 上の既存項目に音声ファイルを再結合する。
 */
@Singleton
class DefaultLibraryRepository(
    @ApplicationContext private val context: Context,
    private val mediaItemDao: MediaItemDao,
    private val itemStateDao: ItemStateDao,
    private val userSettingsRepository: UserSettingsRepository,
    @Dispatcher(ChunkFlowDispatchers.IO) private val ioDispatcher: CoroutineDispatcher,
    private val audioFormatInspector: AudioFormatInspector,
    private val mediaFileLocator: MediaFileLocator = MediaFileLocator(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val usableSpaceBytesProvider: () -> Long = { defaultUsableSpaceBytes(context) },
) : LibraryRepository,
    LibraryMediaFileResolver by mediaFileLocator {

    // TODO(security): 保存データのアプリ独自の暗号化は行わない。サンドボックスとファイルベース暗号化で十分と判断する（§8.2）。
    // TODO(security): 取り込みファイルのマルウェアスキャンは行わない。実行されないメディアデータであり、デコーダの堅牢性は OS と Media3 に依存する（§8.2）。

    @Inject
    constructor(
        @ApplicationContext context: Context,
        mediaItemDao: MediaItemDao,
        itemStateDao: ItemStateDao,
        userSettingsRepository: UserSettingsRepository,
        @Dispatcher(ChunkFlowDispatchers.IO) ioDispatcher: CoroutineDispatcher,
        audioFormatInspector: DefaultAudioFormatInspector,
        mediaFileLocator: MediaFileLocator,
    ) : this(
        context = context,
        mediaItemDao = mediaItemDao,
        itemStateDao = itemStateDao,
        userSettingsRepository = userSettingsRepository,
        ioDispatcher = ioDispatcher,
        audioFormatInspector = audioFormatInspector,
        mediaFileLocator = mediaFileLocator,
        clock = System::currentTimeMillis,
        usableSpaceBytesProvider = { defaultUsableSpaceBytes(context) },
    )

    override fun observeItems(): Flow<List<AudioItem>> = mediaItemDao.observeAll().map { entities ->
        entities.map(MediaItemEntity::toExternalModel)
    }

    override fun observeItem(id: MediaId): Flow<AudioItem?> = mediaItemDao.observeById(id.value).map { entity ->
        entity?.toExternalModel()
    }

    override fun observeItemStates(): Flow<Map<MediaId, ItemState>> = itemStateDao.observeAll().map { entities ->
        entities.associate { entity ->
            MediaId(entity.mediaId) to entity.toExternalModel()
        }
    }

    override fun observeItemState(id: MediaId): Flow<ItemState?> = itemStateDao.observeByMediaId(id.value).map { entity ->
        entity?.toExternalModel()
    }

    override suspend fun upsertItemState(state: ItemState) {
        withContext(ioDispatcher) {
            itemStateDao.upsert(state.toEntity())
        }
    }

    override suspend fun updatePlaybackPosition(
        id: MediaId,
        positionMs: Long,
        speed: Float,
        updatedAtEpochMs: Long,
    ) {
        withContext(ioDispatcher) {
            val coercedPosition = positionMs.coerceAtLeast(0L)
            val coercedSpeed = speed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
            itemStateDao.updatePlaybackPosition(
                mediaId = id.value,
                positionMs = coercedPosition,
                speed = coercedSpeed,
                updatedAt = updatedAtEpochMs,
            )
        }
    }

    override fun import(uris: List<Uri>): Flow<ImportProgress> = flow {
        val total = uris.size
        if (total == 0) {
            emit(ImportProgress.Done(mediaIds = emptyList(), duplicates = 0, transcriptsAttached = 0))
            return@flow
        }

        val mediaIds = mutableListOf<MediaId>()
        var duplicates = 0

        for ((index, uri) in uris.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
                emit(ImportProgress.Failed(ImportError.UNSUPPORTED_FORMAT))
                return@flow
            }

            val meta = queryContentMetadata(uri)
            val declaredSize = meta?.sizeBytes?.takeIf { it > 0L }
            if (declaredSize != null) {
                if (declaredSize > MAX_AUDIO_SIZE_BYTES) {
                    emit(ImportProgress.Failed(ImportError.TOO_LARGE))
                    return@flow
                }
                if (!hasEnoughDiskSpace(declaredSize)) {
                    emit(ImportProgress.Failed(ImportError.NO_SPACE))
                    return@flow
                }
            }

            emit(ImportProgress.Copying(fraction = index.toFloat() / total.toFloat()))

            val tempId = UUID.randomUUID().toString()
            val tempFile = mediaFileLocator.tempFileFor(tempId)
            val outcome = try {
                importSingleFile(
                    uri = uri,
                    meta = meta,
                    tempFile = tempFile,
                    fileIndex = index,
                    totalFiles = total,
                    onProgress = { fraction -> emit(ImportProgress.Copying(fraction)) },
                )
            } catch (ce: CancellationException) {
                tempFile.delete()
                throw ce
            }

            when (outcome) {
                is SingleImportOutcome.Imported -> {
                    mediaIds += outcome.mediaId
                }

                is SingleImportOutcome.Duplicate -> {
                    mediaIds += outcome.mediaId
                    duplicates += 1
                }

                is SingleImportOutcome.Failed -> {
                    emit(ImportProgress.Failed(outcome.error))
                    return@flow
                }
            }
        }

        emit(
            ImportProgress.Done(
                mediaIds = mediaIds.toList(),
                duplicates = duplicates,
                transcriptsAttached = 0,
            ),
        )
    }.flowOn(ioDispatcher)

    override suspend fun importTranscript(uri: Uri): TranscriptImportResult = // 文字起こしの取り込みと照合は M3 の担当範囲（§12）。
        TranscriptImportResult.Invalid(TranscriptError.UNKNOWN_FORMAT)

    override fun syncFromDrive(): Flow<SyncProgress> = // Google Drive 同期は M3 の担当範囲（§12）。
        flowOf(SyncProgress.Done(newAudio = 0, newTranscripts = 0, failed = 0))

    override suspend fun delete(id: MediaId) {
        withContext(ioDispatcher) {
            // DB の CASCADE 削除（chunks / transcripts / item_state / drive_sync_records）を実行した上で実ファイルを消す
            mediaItemDao.deleteById(id.value)
            mediaFileLocator.deleteMediaFiles(id)
        }
    }

    private suspend fun importSingleFile(
        uri: Uri,
        meta: ContentMetadata?,
        tempFile: File,
        fileIndex: Int,
        totalFiles: Int,
        onProgress: suspend (Float?) -> Unit,
    ): SingleImportOutcome {
        val declaredSize = meta?.sizeBytes?.takeIf { it > 0L }
        val copyResult = copyAndDigest(
            uri = uri,
            destFile = tempFile,
            declaredSize = declaredSize,
            fileIndex = fileIndex,
            totalFiles = totalFiles,
            onProgress = onProgress,
        )
        val actualSizeBytes = when (copyResult) {
            is CopyOutcome.Success -> copyResult.bytesWritten

            is CopyOutcome.Failure -> {
                tempFile.delete()
                return SingleImportOutcome.Failed(copyResult.error)
            }
        }
        val sha256 = copyResult.sha256Hex

        // ストリーム実測サイズでも空き容量を再確認する（OPEN_ABLE_COLUMNS でサイズが不明だった場合に備える）
        if (declaredSize == null && !hasEnoughDiskSpace(actualSizeBytes)) {
            tempFile.delete()
            return SingleImportOutcome.Failed(ImportError.NO_SPACE)
        }

        // すでに同じ SHA-256 の項目が DB にあるか調べる
        val existingEntity = mediaItemDao.getBySha256(sha256)
        if (existingEntity != null) {
            val existingMediaId = MediaId(existingEntity.id)
            val existingAudioFile = mediaFileLocator.resolveAudioFile(existingMediaId, existingEntity.mimeType)
            if (existingAudioFile != null && existingAudioFile.exists()) {
                tempFile.delete()
                return SingleImportOutcome.Duplicate(existingMediaId)
            }
        }

        // デコーダで実際に開いて対応形式と長さ・メタデータを検証する（§8.1）
        val inspected = try {
            audioFormatInspector.inspect(tempFile)
        } catch (ce: CancellationException) {
            tempFile.delete()
            throw ce
        } catch (_: Exception) {
            null
        }
        if (inspected == null || inspected.durationMs <= 0L) {
            tempFile.delete()
            return SingleImportOutcome.Failed(ImportError.UNSUPPORTED_FORMAT)
        }

        // バックアップ復元後（DB にレコードがあり音声ファイルのみ欠損）の再取り込みなら、既存の MediaId に再結合する（§6.4）
        if (existingEntity != null) {
            val existingId = MediaId(existingEntity.id)
            val targetFile = mediaFileLocator.audioFileFor(existingId, inspected.extension)
            if (!moveOrCopyTempFile(tempFile, targetFile)) {
                tempFile.delete()
                return SingleImportOutcome.Failed(ImportError.IO)
            }
            return SingleImportOutcome.Duplicate(existingId)
        }

        val newMediaId = MediaId(UUID.randomUUID().toString())
        val targetFile = mediaFileLocator.audioFileFor(newMediaId, inspected.extension)
        if (!moveOrCopyTempFile(tempFile, targetFile)) {
            tempFile.delete()
            return SingleImportOutcome.Failed(ImportError.IO)
        }

        val settings = userSettingsRepository.settings.first()
        val sanitizedDisplayName = sanitizeDisplayName(meta?.displayName)
            .ifBlank { DEFAULT_UNTITLED_NAME }
        val resolvedTitle = inspected.title
            ?.let(::sanitizeDisplayName)
            ?.takeIf { it.isNotBlank() }
            ?: sanitizedDisplayName
        val nowMs = clock()
        val initialOffsetMs = calculateInitialTranscriptOffsetMs(inspected)
        val mp3Info = inspected.mp3HeaderInfo

        val entity = MediaItemEntity(
            id = newMediaId.value,
            title = resolvedTitle,
            durationMs = inspected.durationMs,
            mimeType = inspected.mimeType,
            sizeBytes = actualSizeBytes,
            sha256 = sha256,
            course = null,
            targetLang = settings.defaultLanguagePair.target.value,
            baseLang = settings.defaultLanguagePair.base?.value,
            transcriptOffsetMs = initialOffsetMs,
            importedAt = nowMs,
            mp3HasHeader = mp3Info?.hasHeader ?: false,
            mp3SamplesPerFrame = mp3Info?.samplesPerFrame ?: Mp3HeaderInfo.SAMPLES_PER_FRAME_MPEG1,
            mp3EncoderDelay = mp3Info?.encoderDelay ?: Mp3HeaderInfo.DEFAULT_LAME_ENCODER_DELAY,
        )
        val initialState = ItemStateEntity(
            mediaId = newMediaId.value,
            positionMs = 0L,
            speed = settings.defaultSpeed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED),
            updatedAt = nowMs,
        )
        mediaItemDao.upsert(entity)
        itemStateDao.upsert(initialState)
        return SingleImportOutcome.Imported(newMediaId)
    }

    private suspend fun copyAndDigest(
        uri: Uri,
        destFile: File,
        declaredSize: Long?,
        fileIndex: Int,
        totalFiles: Int,
        onProgress: suspend (Float?) -> Unit,
    ): CopyOutcome {
        val resolver = context.contentResolver
        val inputStream = try {
            resolver.openInputStream(uri)
        } catch (_: SecurityException) {
            return CopyOutcome.Failure(ImportError.IO)
        } catch (_: IOException) {
            return CopyOutcome.Failure(ImportError.IO)
        } ?: return CopyOutcome.Failure(ImportError.IO)

        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)

        return try {
            inputStream.use { input ->
                FileOutputStream(destFile).use { output ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        totalBytes += read
                        if (totalBytes > MAX_AUDIO_SIZE_BYTES) {
                            return CopyOutcome.Failure(ImportError.TOO_LARGE)
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        if (declaredSize != null && declaredSize > 0L) {
                            val fileFraction = (totalBytes.toFloat() / declaredSize.toFloat()).coerceIn(0f, 1f)
                            val overall = (fileIndex.toFloat() + fileFraction) / totalFiles.toFloat()
                            onProgress(overall.coerceIn(0f, 1f))
                        }
                    }
                    output.fd.sync()
                }
            }
            if (totalBytes <= 0L) {
                CopyOutcome.Failure(ImportError.UNSUPPORTED_FORMAT)
            } else {
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                CopyOutcome.Success(bytesWritten = totalBytes, sha256Hex = hex)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: SecurityException) {
            CopyOutcome.Failure(ImportError.IO)
        } catch (ioe: IOException) {
            if (ioe.message?.contains("ENOSPC", ignoreCase = true) == true ||
                ioe.message?.contains("No space left", ignoreCase = true) == true
            ) {
                CopyOutcome.Failure(ImportError.NO_SPACE)
            } else {
                CopyOutcome.Failure(ImportError.IO)
            }
        }
    }

    private fun moveOrCopyTempFile(tempFile: File, targetFile: File): Boolean {
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) {
            targetFile.delete()
        }
        if (tempFile.renameTo(targetFile)) {
            return true
        }
        return try {
            tempFile.copyTo(targetFile, overwrite = true)
            tempFile.delete()
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun hasEnoughDiskSpace(fileSizeBytes: Long): Boolean {
        val usable = usableSpaceBytesProvider()
        // 空き容量がファイルサイズの 1.2 倍以上あることを確かめる（§8.1）
        val required = (fileSizeBytes * 6L) / 5L
        return usable >= required
    }

    private fun queryContentMetadata(uri: Uri): ContentMetadata? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val resolver = context.contentResolver
        val mimeType = runCatching { resolver.getType(uri) }.getOrNull()
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
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        sizeBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
        }
        return ContentMetadata(
            displayName = displayName,
            sizeBytes = sizeBytes,
            mimeType = mimeType,
        )
    }

    private data class ContentMetadata(
        val displayName: String?,
        val sizeBytes: Long?,
        val mimeType: String?,
    )

    private sealed interface SingleImportOutcome {
        data class Imported(val mediaId: MediaId) : SingleImportOutcome
        data class Duplicate(val mediaId: MediaId) : SingleImportOutcome
        data class Failed(val error: ImportError) : SingleImportOutcome
    }

    private sealed interface CopyOutcome {
        data class Success(val bytesWritten: Long, val sha256Hex: String) : CopyOutcome
        data class Failure(val error: ImportError) : CopyOutcome
    }

    companion object {
        /** 1 ファイルあたりの上限サイズ: 2 GB（§8.1）。 */
        const val MAX_AUDIO_SIZE_BYTES: Long = 2L * 1024L * 1024L * 1024L

        private const val COPY_BUFFER_BYTES: Int = 64 * 1024
        private const val MAX_TITLE_CODE_POINTS: Int = 200
        private const val DEFAULT_UNTITLED_NAME: String = "Untitled"

        @SuppressLint("UsableSpace")
        private fun defaultUsableSpaceBytes(context: Context): Long = context.filesDir.usableSpace

        /**
         * `DISPLAY_NAME` やタグ文字列からパス区切り文字・制御文字を取り除き、最大 200 文字に切り詰める（§8.1）。
         * 先頭にパス区切りが含まれていた場合は最後のセグメントを取り出し、対応音声拡張子が末尾にあればタイトル用に外す。
         */
        internal fun sanitizeDisplayName(rawName: String?): String {
            if (rawName.isNullOrBlank()) return ""
            val lastSegment = rawName.substringAfterLast('/').substringAfterLast('\\')
            val stripped = buildString(lastSegment.length) {
                for (ch in lastSegment) {
                    val isControl = ch.code < 0x20 || ch.code == 0x7F
                    val isPathSep = ch == '/' || ch == '\\'
                    if (!isControl && !isPathSep) {
                        append(ch)
                    }
                }
            }.trim()
            val withoutExt = stripped.replace(
                Regex("\\.(mp3|m4a|mp4|ogg|opus|flac|wav|aac)$", RegexOption.IGNORE_CASE),
                "",
            ).trim().ifEmpty { stripped }

            val codePoints = withoutExt.codePoints().toArray()
            return if (codePoints.size <= MAX_TITLE_CODE_POINTS) {
                withoutExt
            } else {
                String(codePoints, 0, MAX_TITLE_CODE_POINTS)
            }
        }
    }
}
