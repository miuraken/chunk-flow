package com.miuraken.chunkflow.core.data.library

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.data.di.ChunkFlowDispatchers
import com.miuraken.chunkflow.core.data.di.Dispatcher
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.DriveFileDao
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.dao.TranscriptDao
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.DriveFileEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import com.miuraken.chunkflow.core.database.entity.toEntity
import com.miuraken.chunkflow.core.database.entity.toExternalModel
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.drive.DriveBytesDownloadResult
import com.miuraken.chunkflow.core.drive.DriveDownloadResult
import com.miuraken.chunkflow.core.drive.DriveError
import com.miuraken.chunkflow.core.drive.DriveListResult
import com.miuraken.chunkflow.core.drive.DriveRemoteFile
import com.miuraken.chunkflow.core.drive.DriveSyncClient
import com.miuraken.chunkflow.core.drive.DriveUploadResult
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AudioItem
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.DriveFileKind
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.TranscriptExportFormat
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.transcript.TranscriptDocument
import com.miuraken.chunkflow.core.transcript.TranscriptError
import com.miuraken.chunkflow.core.transcript.TranscriptParseResult
import com.miuraken.chunkflow.core.transcript.TranscriptParser
import com.miuraken.chunkflow.core.transcript.TranscriptSegment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * [LibraryRepository] の本番実装（§6.3、§6.4、§7.1、§7.4.6、§8.1、ADR 0006、ADR 0008）。
 *
 * - 取り込むファイルはすべて信頼できない入力として扱い、`content://` スキームの検証、サイズ上限（音声 2GB / 文字起こし 20MB）、
 *   空き容量（ファイルサイズの 1.2 倍以上）、SHA-256 による重複検出、デコーダによる形式検証を通す（§8.1）。
 * - SAF の複数ファイル選択（`OpenMultipleDocuments`）とフォルダ選択（`OpenDocumentTree`）の両方に対応し、
 *   フォルダ名を [AudioItem.course] に記録して `<フォルダ>/transcription/<ファイル名>.chunkflow.json` も自動で紐付ける（§7.1）。
 * - 音声の取り込み完了後、[AnalysisRepository.requestAnalysis] で波形・特徴量・自動分割のジョブを投入する（§7.1 手順 6）。
 * - 文字起こし（`.chunkflow.json` 等）は `audio.sha256` またはファイル名で教材と照合し、前後のパディング（前 150ms / 後 200ms、
 *   隣のチャンクとの無音の中点を越えない）を付けて [ChunkOrigin.IMPORTED] のチャンクとして保存する（§7.4.6）。
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
    private val chunkDao: ChunkDao? = null,
    private val transcriptDao: TranscriptDao? = null,
    private val driveFileDao: DriveFileDao? = null,
    private val driveSyncClient: DriveSyncClient = NoOpDriveSyncClient,
    private val analysisRepository: AnalysisRepository = NoOpAnalysisRepository,
    private val transcriptParser: TranscriptParser = NoOpTranscriptParser,
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
        chunkDao: ChunkDao,
        transcriptDao: TranscriptDao,
        driveFileDao: DriveFileDao,
        driveSyncClient: DriveSyncClient,
        userSettingsRepository: UserSettingsRepository,
        analysisRepository: AnalysisRepository,
        transcriptParser: TranscriptParser,
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
        chunkDao = chunkDao,
        transcriptDao = transcriptDao,
        driveFileDao = driveFileDao,
        driveSyncClient = driveSyncClient,
        analysisRepository = analysisRepository,
        transcriptParser = transcriptParser,
        clock = System::currentTimeMillis,
        usableSpaceBytesProvider = { defaultUsableSpaceBytes(context) },
    )

    private val audioFileStateVersion = MutableStateFlow(0L)

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
        if (uris.isEmpty()) {
            emit(ImportProgress.Done(mediaIds = emptyList(), duplicates = 0, transcriptsAttached = 0))
            return@flow
        }

        // 1. スキームの検証と SAF フォルダ（Tree URI）の展開
        val candidates = mutableListOf<ImportCandidate>()
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
                emit(ImportProgress.Failed(ImportError.UNSUPPORTED_FORMAT))
                return@flow
            }
            if (isTreeUriSafe(uri)) {
                candidates += expandTreeUri(uri)
            } else {
                val meta = queryContentMetadata(uri)
                val course = extractCourseFromDocumentUri(uri)
                candidates += ImportCandidate(
                    uri = uri,
                    meta = meta,
                    course = course,
                )
            }
        }

        if (candidates.isEmpty()) {
            emit(ImportProgress.Done(mediaIds = emptyList(), duplicates = 0, transcriptsAttached = 0))
            return@flow
        }

        val (transcriptCandidates, audioCandidates) = candidates.partition(::isTranscriptCandidate)
        val totalAudio = audioCandidates.size
        val mediaIds = mutableListOf<MediaId>()
        val batchAudioByStemAndCourse = LinkedHashMap<Pair<String?, String>, MediaId>()
        val batchAudioByStem = LinkedHashMap<String, MediaId>()
        var duplicates = 0

        // 2. 音声ファイルの取り込みと解析ジョブの投入（§7.1 手順 1〜6）
        for ((index, candidate) in audioCandidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            val uri = candidate.uri
            val meta = candidate.meta
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

            emit(ImportProgress.Copying(fraction = index.toFloat() / totalAudio.coerceAtLeast(1).toFloat()))

            val tempId = UUID.randomUUID().toString()
            val tempFile = mediaFileLocator.tempFileFor(tempId)
            val outcome = try {
                importSingleFile(
                    uri = uri,
                    meta = meta,
                    course = candidate.course,
                    tempFile = tempFile,
                    fileIndex = index,
                    totalFiles = totalAudio.coerceAtLeast(1),
                    onProgress = { fraction -> emit(ImportProgress.Copying(fraction)) },
                )
            } catch (ce: CancellationException) {
                tempFile.delete()
                throw ce
            }

            val resolvedMediaId = when (outcome) {
                is SingleImportOutcome.Imported -> {
                    mediaIds += outcome.mediaId
                    analysisRepository.requestAnalysis(
                        outcome.mediaId,
                        setOf(AnalysisStage.FEATURES, AnalysisStage.SEGMENTATION),
                    )
                    outcome.mediaId
                }

                is SingleImportOutcome.Duplicate -> {
                    mediaIds += outcome.mediaId
                    duplicates += 1
                    if (outcome.reattachedAudioFile) {
                        analysisRepository.requestAnalysis(
                            outcome.mediaId,
                            setOf(AnalysisStage.FEATURES, AnalysisStage.SEGMENTATION),
                        )
                    }
                    outcome.mediaId
                }

                is SingleImportOutcome.Failed -> {
                    emit(ImportProgress.Failed(outcome.error))
                    return@flow
                }
            }

            val stem = extractFileStem(meta?.displayName ?: uri.lastPathSegment.orEmpty())
            if (stem.isNotEmpty()) {
                batchAudioByStemAndCourse[candidate.course to stem] = resolvedMediaId
                batchAudioByStem[stem] = resolvedMediaId
            }
        }

        // 3. 同じバッチまたはフォルダ内の文字起こしファイルの照合と反映（§7.1 手順 7、§7.4.6）
        var transcriptsAttached = 0
        if (transcriptCandidates.isNotEmpty()) {
            val matchedByMediaId = LinkedHashMap<String, MutableList<Pair<MediaItemEntity, ParsedTranscript>>>()
            for (candidate in transcriptCandidates) {
                currentCoroutineContext().ensureActive()
                val parseOutcome = readAndParseTranscript(candidate.uri, candidate.meta)
                val parsed = (parseOutcome as? InternalTranscriptParseOutcome.Ok)?.parsed ?: continue
                val targetEntity = findMatchingMediaItem(
                    doc = parsed.document,
                    transcriptFileName = parsed.fileName,
                    course = candidate.course,
                    batchByStemAndCourse = batchAudioByStemAndCourse,
                    batchByStem = batchAudioByStem,
                ) ?: continue
                matchedByMediaId.getOrPut(targetEntity.id) { mutableListOf() }
                    .add(targetEntity to parsed)
            }

            for ((_, pairs) in matchedByMediaId) {
                // 同じ音声の JSON が複数ある場合は generator.createdAt が最も新しいものを使う（§7.4.6）
                val winningPair = pairs.maxWithOrNull(
                    compareBy<Pair<MediaItemEntity, ParsedTranscript>> { it.second.createdAt.orEmpty() },
                ) ?: continue
                val latestEntity = mediaItemDao.getById(winningPair.first.id) ?: winningPair.first
                if (attachTranscriptToMediaItem(latestEntity, winningPair.second) != null) {
                    transcriptsAttached += 1
                }
            }
        }

        emit(
            ImportProgress.Done(
                mediaIds = mediaIds.toList(),
                duplicates = duplicates,
                transcriptsAttached = transcriptsAttached,
            ),
        )
    }.flowOn(ioDispatcher)

    override suspend fun importTranscript(uri: Uri): TranscriptImportResult = withContext(ioDispatcher) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@withContext TranscriptImportResult.Invalid(TranscriptError.UNKNOWN_FORMAT)
        }
        val meta = queryContentMetadata(uri)
        val course = extractCourseFromDocumentUri(uri)
        val parseOutcome = readAndParseTranscript(uri, meta)
        val parsed = when (parseOutcome) {
            is InternalTranscriptParseOutcome.Ok -> parseOutcome.parsed

            is InternalTranscriptParseOutcome.Invalid -> {
                return@withContext TranscriptImportResult.Invalid(parseOutcome.error)
            }
        }

        val doc = parsed.document
        val targetEntity = findMatchingMediaItem(
            doc = doc,
            transcriptFileName = parsed.fileName,
            course = course,
            batchByStemAndCourse = emptyMap(),
            batchByStem = emptyMap(),
        ) ?: return@withContext TranscriptImportResult.NoMatchingAudio(
            audioFileName = doc.audioFileName,
        )

        val chunkCount = attachTranscriptToMediaItem(targetEntity, parsed)
            ?: return@withContext TranscriptImportResult.Invalid(TranscriptError.SCHEMA_VIOLATION)
        val docDurationMs = doc.durationMs
        val durationMismatch = docDurationMs != null &&
            abs(targetEntity.durationMs - docDurationMs) >= DURATION_MISMATCH_THRESHOLD_MS
        TranscriptImportResult.Attached(
            mediaId = MediaId(targetEntity.id),
            chunkCount = chunkCount,
            durationMismatch = durationMismatch,
        )
    }

    override suspend fun updateItemMetadata(
        id: MediaId,
        course: String?,
        languages: LanguagePair,
    ) {
        withContext(ioDispatcher) {
            val normalizedCourse = sanitizeCourseName(course).takeIf { it.isNotBlank() }
            mediaItemDao.updateCourseAndLanguagePair(
                id = id.value,
                course = normalizedCourse,
                targetLang = languages.target.value,
                baseLang = languages.base?.value,
            )
        }
    }

    override suspend fun exportLocalTranscriptJson(id: MediaId): Boolean = withContext(ioDispatcher) {
        val entity = mediaItemDao.getById(id.value) ?: return@withContext false
        val jsonBytes = buildLocalTranscriptJsonBytes(entity) ?: return@withContext false
        val localFile = mediaFileLocator.transcriptFileFor(id, "chunkflow.json")
        if (!writeTranscriptBytesAtomically(localFile, jsonBytes)) {
            return@withContext false
        }

        val uploadFileName = "${sanitizeDisplayName(entity.title).ifBlank { entity.id }}.chunkflow.json"
        val uploadCourse = entity.course.orEmpty()
        when (val uploadResult = driveSyncClient.uploadTranscriptJson(uploadCourse, uploadFileName, jsonBytes)) {
            is DriveUploadResult.Success -> {
                val uploaded = uploadResult.file
                val recordedModifiedMs = max(uploaded.modifiedTimeEpochMs, localFile.lastModified())
                driveFileDao?.upsert(
                    DriveFileEntity(
                        driveFileId = uploaded.id,
                        kind = DriveFileKind.TRANSCRIPT.name,
                        course = uploadCourse,
                        modifiedTime = recordedModifiedMs,
                        sha256 = uploaded.sha256Checksum,
                        mediaId = entity.id,
                    ),
                )
            }

            is DriveUploadResult.Failure -> {
                // Drive 未認証やオフライン時でもローカル JSON の書き出し自体は成功として扱う
            }
        }
        true
    }

    override fun syncFromDrive(): Flow<SyncProgress> = flow {
        emit(SyncProgress.Listing)
        val courses = when (val listResult = driveSyncClient.listChunkFlowCourses()) {
            is DriveListResult.Success -> listResult.courses

            is DriveListResult.Failure -> {
                emit(SyncProgress.Failed(mapDriveErrorToSyncError(listResult.error)))
                return@flow
            }
        }

        val audioToDownload = mutableListOf<Pair<String?, DriveRemoteFile>>()
        val transcriptsToDownload = mutableListOf<Pair<String?, DriveRemoteFile>>()

        for (course in courses) {
            currentCoroutineContext().ensureActive()
            val normalizedCourse = sanitizeCourseName(course.courseName).takeIf { it.isNotBlank() }
            for (remoteAudio in course.audioFiles) {
                val remoteSha = remoteAudio.sha256Checksum?.lowercase()?.takeIf { it.length == 64 }
                val existingBySha = remoteSha?.let { mediaItemDao.getBySha256(it) }
                val existingByDriveId = driveFileDao?.getById(remoteAudio.id)?.mediaId?.let { mediaItemDao.getById(it) }
                val existingEntity = existingBySha ?: existingByDriveId
                if (existingEntity != null) {
                    val existingFile = mediaFileLocator.resolveAudioFile(MediaId(existingEntity.id), existingEntity.mimeType)
                    if (existingFile != null && existingFile.exists()) {
                        if (existingEntity.course == null && normalizedCourse != null) {
                            mediaItemDao.updateCourse(existingEntity.id, normalizedCourse)
                        }
                        driveFileDao?.upsert(
                            DriveFileEntity(
                                driveFileId = remoteAudio.id,
                                kind = DriveFileKind.AUDIO.name,
                                course = normalizedCourse.orEmpty(),
                                modifiedTime = remoteAudio.modifiedTimeEpochMs,
                                sha256 = existingEntity.sha256,
                                mediaId = existingEntity.id,
                            ),
                        )
                        continue
                    }
                }
                audioToDownload += normalizedCourse to remoteAudio
            }

            for (remoteTranscript in course.transcriptFiles) {
                val existingRecord = driveFileDao?.getById(remoteTranscript.id)
                if (existingRecord != null && existingRecord.modifiedTime >= remoteTranscript.modifiedTimeEpochMs) {
                    continue
                }
                transcriptsToDownload += normalizedCourse to remoteTranscript
            }
        }

        val totalToDownload = audioToDownload.size + transcriptsToDownload.size
        var doneCount = 0
        var newAudioCount = 0
        var newTranscriptCount = 0
        var failedCount = 0
        val batchAudioByStemAndCourse = LinkedHashMap<Pair<String?, String>, MediaId>()
        val batchAudioByStem = LinkedHashMap<String, MediaId>()
        val syncedTranscriptMediaIds = mutableSetOf<String>()

        if (totalToDownload > 0) {
            emit(SyncProgress.Downloading(done = 0, total = totalToDownload))
        }

        // 1. 新規音声ファイルのダウンロードと取り込み
        for ((courseName, remoteAudio) in audioToDownload) {
            currentCoroutineContext().ensureActive()
            if (remoteAudio.sizeBytes > MAX_AUDIO_SIZE_BYTES ||
                (remoteAudio.sizeBytes > 0L && !hasEnoughDiskSpace(remoteAudio.sizeBytes))
            ) {
                failedCount += 1
                doneCount += 1
                emit(SyncProgress.Downloading(done = doneCount, total = totalToDownload))
                continue
            }

            val tempFile = mediaFileLocator.tempFileFor(UUID.randomUUID().toString())
            val dlResult = try {
                driveSyncClient.downloadToFile(
                    fileId = remoteAudio.id,
                    destFile = tempFile,
                    maxSizeBytes = MAX_AUDIO_SIZE_BYTES,
                )
            } catch (ce: CancellationException) {
                tempFile.delete()
                throw ce
            }

            val dlSuccess = when (dlResult) {
                is DriveDownloadResult.Success -> dlResult

                is DriveDownloadResult.Failure -> {
                    tempFile.delete()
                    failedCount += 1
                    doneCount += 1
                    emit(SyncProgress.Downloading(done = doneCount, total = totalToDownload))
                    continue
                }
            }

            val outcome = importDownloadedFile(
                tempFile = tempFile,
                displayName = remoteAudio.name,
                course = courseName,
                actualSizeBytes = dlSuccess.bytesWritten,
                sha256 = dlSuccess.sha256Hex,
            )

            when (outcome) {
                is SingleImportOutcome.Imported -> {
                    newAudioCount += 1
                    analysisRepository.requestAnalysis(
                        outcome.mediaId,
                        setOf(AnalysisStage.FEATURES, AnalysisStage.SEGMENTATION),
                    )
                    driveFileDao?.upsert(
                        DriveFileEntity(
                            driveFileId = remoteAudio.id,
                            kind = DriveFileKind.AUDIO.name,
                            course = courseName.orEmpty(),
                            modifiedTime = remoteAudio.modifiedTimeEpochMs,
                            sha256 = dlSuccess.sha256Hex,
                            mediaId = outcome.mediaId.value,
                        ),
                    )
                    val stem = extractFileStem(remoteAudio.name)
                    if (stem.isNotEmpty()) {
                        batchAudioByStemAndCourse[courseName to stem] = outcome.mediaId
                        batchAudioByStem[stem] = outcome.mediaId
                    }
                }

                is SingleImportOutcome.Duplicate -> {
                    if (outcome.reattachedAudioFile) {
                        newAudioCount += 1
                        analysisRepository.requestAnalysis(
                            outcome.mediaId,
                            setOf(AnalysisStage.FEATURES, AnalysisStage.SEGMENTATION),
                        )
                    }
                    driveFileDao?.upsert(
                        DriveFileEntity(
                            driveFileId = remoteAudio.id,
                            kind = DriveFileKind.AUDIO.name,
                            course = courseName.orEmpty(),
                            modifiedTime = remoteAudio.modifiedTimeEpochMs,
                            sha256 = dlSuccess.sha256Hex,
                            mediaId = outcome.mediaId.value,
                        ),
                    )
                    val stem = extractFileStem(remoteAudio.name)
                    if (stem.isNotEmpty()) {
                        batchAudioByStemAndCourse[courseName to stem] = outcome.mediaId
                        batchAudioByStem[stem] = outcome.mediaId
                    }
                }

                is SingleImportOutcome.Failed -> {
                    failedCount += 1
                }
            }

            doneCount += 1
            emit(SyncProgress.Downloading(done = doneCount, total = totalToDownload))
        }

        // 2. 新規・更新された文字起こしファイルのダウンロードと反映
        for ((courseName, remoteTranscript) in transcriptsToDownload) {
            currentCoroutineContext().ensureActive()
            if (remoteTranscript.sizeBytes > MAX_TRANSCRIPT_SIZE_BYTES) {
                failedCount += 1
                doneCount += 1
                emit(SyncProgress.Downloading(done = doneCount, total = totalToDownload))
                continue
            }

            val bytes = when (
                val dl = driveSyncClient.downloadBytes(
                    fileId = remoteTranscript.id,
                    maxSizeBytes = MAX_TRANSCRIPT_SIZE_BYTES,
                )
            ) {
                is DriveBytesDownloadResult.Success -> dl.bytes

                is DriveBytesDownloadResult.Failure -> {
                    failedCount += 1
                    doneCount += 1
                    emit(SyncProgress.Downloading(done = doneCount, total = totalToDownload))
                    continue
                }
            }

            val safeName = remoteTranscript.name.substringAfterLast('/').substringAfterLast('\\')
            val parsed = when (val parseRes = transcriptParser.parse(bytes, safeName)) {
                is TranscriptParseResult.Ok -> ParsedTranscript(
                    document = parseRes.document,
                    rawBytes = bytes,
                    fileName = safeName,
                    createdAt = extractCreatedAtIsoString(bytes),
                )

                is TranscriptParseResult.Error -> {
                    failedCount += 1
                    doneCount += 1
                    emit(SyncProgress.Downloading(done = doneCount, total = totalToDownload))
                    continue
                }
            }

            val targetEntity = findMatchingMediaItem(
                doc = parsed.document,
                transcriptFileName = safeName,
                course = courseName,
                batchByStemAndCourse = batchAudioByStemAndCourse,
                batchByStem = batchAudioByStem,
            )
            if (targetEntity == null) {
                failedCount += 1
                doneCount += 1
                emit(SyncProgress.Downloading(done = doneCount, total = totalToDownload))
                continue
            }

            val latestEntity = mediaItemDao.getById(targetEntity.id) ?: targetEntity
            if (attachTranscriptToMediaItem(latestEntity, parsed) != null) {
                newTranscriptCount += 1
                syncedTranscriptMediaIds += latestEntity.id
                val localTranscriptFile = mediaFileLocator.transcriptFileFor(
                    MediaId(latestEntity.id),
                    transcriptExtensionForFileName(safeName),
                )
                val recordedModifiedMs = max(
                    remoteTranscript.modifiedTimeEpochMs,
                    localTranscriptFile.lastModified(),
                )
                driveFileDao?.upsert(
                    DriveFileEntity(
                        driveFileId = remoteTranscript.id,
                        kind = DriveFileKind.TRANSCRIPT.name,
                        course = courseName.orEmpty(),
                        modifiedTime = recordedModifiedMs,
                        sha256 = remoteTranscript.sha256Checksum,
                        mediaId = latestEntity.id,
                    ),
                )
            } else {
                failedCount += 1
            }

            doneCount += 1
            emit(SyncProgress.Downloading(done = doneCount, total = totalToDownload))
        }

        // 3. 端末内で生成・編集された .chunkflow.json の Google Drive への自動アップロード
        var uploadedTranscripts = 0
        val allLocalItems = mediaItemDao.observeAll().first()
        for (item in allLocalItems) {
            currentCoroutineContext().ensureActive()
            if (item.id in syncedTranscriptMediaIds) continue
            if (uploadLocalTranscriptIfNeeded(item)) {
                uploadedTranscripts += 1
            }
        }

        emit(
            SyncProgress.Done(
                newAudio = newAudioCount,
                newTranscripts = newTranscriptCount,
                failed = failedCount,
                uploadedTranscripts = uploadedTranscripts,
            ),
        )
    }.flowOn(ioDispatcher)

    override fun observeMissingAudioIds(): Flow<Set<MediaId>> = combine(
        mediaItemDao.observeAll(),
        audioFileStateVersion,
    ) { entities, _ ->
        entities.mapNotNullTo(linkedSetOf()) { entity ->
            val mediaId = MediaId(entity.id)
            val file = mediaFileLocator.resolveAudioFile(mediaId, entity.mimeType)
            if (file == null || !file.exists()) mediaId else null
        }
    }

    override suspend fun exportTranscriptText(
        id: MediaId,
        format: TranscriptExportFormat,
    ): String? = withContext(ioDispatcher) {
        val analysisChunks = analysisRepository.observeChunks(id).first().sortedBy { it.startMs }
        val analysisTranscripts = analysisRepository.observeTranscripts(id).first()
        val pairsFromAnalysis = analysisChunks.mapNotNull { chunk ->
            val transcript = analysisTranscripts[chunk.id] ?: return@mapNotNull null
            if (transcript.text.isBlank()) null else chunk to transcript
        }
        val pairs: List<Pair<Chunk, Transcript>> = if (pairsFromAnalysis.isNotEmpty()) {
            pairsFromAnalysis
        } else {
            loadActiveChunkTranscriptPairs(id.value).map { (chunkEntity, transcriptEntity) ->
                chunkEntity.toExternalModel() to transcriptEntity.toExternalModel()
            }
        }
        if (pairs.isEmpty()) return@withContext null
        formatTranscriptExport(pairs, format)
    }

    override suspend fun writeExportedTranscriptToUri(
        id: MediaId,
        format: TranscriptExportFormat,
        targetUri: Uri,
    ): Boolean = withContext(ioDispatcher) {
        if (targetUri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@withContext false
        }
        val exportedText = exportTranscriptText(id, format) ?: return@withContext false
        try {
            val outputStream = context.contentResolver.openOutputStream(targetUri, "wt")
                ?: context.contentResolver.openOutputStream(targetUri)
                ?: return@withContext false
            outputStream.use { out ->
                out.write(exportedText.toByteArray(Charsets.UTF_8))
                out.flush()
            }
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: SecurityException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    override suspend fun delete(id: MediaId) {
        withContext(ioDispatcher) {
            // DB の CASCADE 削除（chunks / transcripts / item_state / drive_sync_records）を実行した上で実ファイルを消す
            mediaItemDao.deleteById(id.value)
            mediaFileLocator.deleteMediaFiles(id)
            audioFileStateVersion.update { it + 1L }
        }
    }

    private suspend fun importSingleFile(
        uri: Uri,
        meta: ContentMetadata?,
        course: String?,
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
            if (existingEntity.course == null && !course.isNullOrBlank()) {
                mediaItemDao.update(existingEntity.copy(course = course))
            }
            val existingMediaId = MediaId(existingEntity.id)
            val existingAudioFile = mediaFileLocator.resolveAudioFile(existingMediaId, existingEntity.mimeType)
            if (existingAudioFile != null && existingAudioFile.exists()) {
                tempFile.delete()
                return SingleImportOutcome.Duplicate(
                    mediaId = existingMediaId,
                    reattachedAudioFile = false,
                )
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
            audioFileStateVersion.update { it + 1L }
            return SingleImportOutcome.Duplicate(
                mediaId = existingId,
                reattachedAudioFile = true,
            )
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
            course = course,
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
        audioFileStateVersion.update { it + 1L }
        return SingleImportOutcome.Imported(newMediaId)
    }

    private suspend fun readAndParseTranscript(
        uri: Uri,
        meta: ContentMetadata?,
    ): InternalTranscriptParseOutcome {
        val declaredSize = meta?.sizeBytes?.takeIf { it > 0L }
        if (declaredSize != null && declaredSize > MAX_TRANSCRIPT_SIZE_BYTES) {
            return InternalTranscriptParseOutcome.Invalid(TranscriptError.TOO_LARGE)
        }

        val bytes = readBytesWithLimit(uri, MAX_TRANSCRIPT_SIZE_BYTES)
            ?: return InternalTranscriptParseOutcome.Invalid(TranscriptError.UNKNOWN_FORMAT)
        if (bytes.size.toLong() > MAX_TRANSCRIPT_SIZE_BYTES) {
            return InternalTranscriptParseOutcome.Invalid(TranscriptError.TOO_LARGE)
        }

        val rawFileName = meta?.displayName
            ?: uri.lastPathSegment
            ?: "transcript.chunkflow.json"
        val safeFileName = rawFileName.substringAfterLast('/').substringAfterLast('\\')

        return when (val result = transcriptParser.parse(bytes, safeFileName)) {
            is TranscriptParseResult.Ok -> {
                val createdAt = extractCreatedAtIsoString(bytes)
                InternalTranscriptParseOutcome.Ok(
                    ParsedTranscript(
                        document = result.document,
                        rawBytes = bytes,
                        fileName = safeFileName,
                        createdAt = createdAt,
                    ),
                )
            }

            is TranscriptParseResult.Error -> {
                InternalTranscriptParseOutcome.Invalid(result.error)
            }
        }
    }

    private suspend fun readBytesWithLimit(uri: Uri, maxBytes: Long): ByteArray? {
        val resolver = context.contentResolver
        val inputStream = try {
            resolver.openInputStream(uri)
        } catch (_: SecurityException) {
            return null
        } catch (_: IOException) {
            return null
        } ?: return null

        val out = ByteArrayOutputStream()
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        return try {
            inputStream.use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    if (total > maxBytes) {
                        // 上限超過を検出するため maxBytes + 1 バイト分のダミー配列を返す
                        return ByteArray((maxBytes + 1L).toInt())
                    }
                    out.write(buffer, 0, read)
                }
            }
            out.toByteArray()
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun findMatchingMediaItem(
        doc: TranscriptDocument,
        transcriptFileName: String,
        course: String?,
        batchByStemAndCourse: Map<Pair<String?, String>, MediaId>,
        batchByStem: Map<String, MediaId>,
    ): MediaItemEntity? {
        // 1. audio.sha256 による厳密一致（§7.4.6）
        val sha = doc.audioSha256?.lowercase()?.takeIf { it.isNotBlank() }
        if (sha != null) {
            val bySha = mediaItemDao.getBySha256(sha)
            if (bySha != null) {
                return bySha
            }
        }

        // 2. 同じバッチまたはディレクトリ内のファイル名ステムによる対応付け（<directory>/transcription/<stem>.chunkflow.json 等）
        val candidateStems = buildList {
            doc.audioFileName?.let { add(extractFileStem(it)) }
            add(extractFileStem(transcriptFileName))
        }.filter { it.isNotEmpty() }.distinct()

        for (stem in candidateStems) {
            val batchMediaId = batchByStemAndCourse[course to stem] ?: batchByStem[stem]
            if (batchMediaId != null) {
                mediaItemDao.getById(batchMediaId.value)?.let { return it }
            }
        }

        val allItems = mediaItemDao.observeAll().first()
        for (stem in candidateStems) {
            if (course != null) {
                allItems.firstOrNull {
                    it.course == course && extractFileStem(it.title).equals(stem, ignoreCase = true)
                }?.let { return it }
            }
            allItems.firstOrNull {
                extractFileStem(it.title).equals(stem, ignoreCase = true)
            }?.let { return it }
        }

        return null
    }

    private suspend fun attachTranscriptToMediaItem(
        targetEntity: MediaItemEntity,
        parsed: ParsedTranscript,
    ): Int? {
        val mediaId = MediaId(targetEntity.id)
        val doc = parsed.document
        val nowMs = clock()

        // 原本ファイルを filesDir/media/<MediaId>.transcript.<ext> に保存する（§6.4）
        val ext = transcriptExtensionForFileName(parsed.fileName)
        val transcriptFile = mediaFileLocator.transcriptFileFor(mediaId, ext)
        val savedOk = try {
            transcriptFile.parentFile?.mkdirs()
            FileOutputStream(transcriptFile).use { output ->
                output.write(parsed.rawBytes)
                output.fd.sync()
            }
            true
        } catch (_: IOException) {
            false
        }
        if (!savedOk) return null

        // セグメントに前後のパディング（前 150ms / 後 200ms）を付け、音声長と隣接チャンクの中点でクランプする（§7.3.3 手順 7、§7.4.6）
        val paddedEntries = buildPaddedChunksAndTranscripts(
            mediaId = targetEntity.id,
            audioDurationMs = targetEntity.durationMs,
            doc = doc,
            createdAtMs = nowMs,
        )

        chunkDao?.replaceChunksTransaction(
            mediaId = targetEntity.id,
            origin = ChunkOrigin.IMPORTED.name,
            newChunks = paddedEntries.map { it.first },
            newTranscriptsByIndex = paddedEntries.mapIndexed { index, pair -> index to pair.second }.toMap(),
            preserveAllOldTranscripts = false,
        )

        // JSON に言語ペアがあれば教材の言語ペアを更新する（§7.4.6）
        val languages = doc.languages
        if (languages != null) {
            mediaItemDao.updateLanguagePair(
                id = targetEntity.id,
                targetLang = languages.target.value,
                baseLang = languages.base?.value,
            )
        }

        // 使用するチャンク種別を IMPORTED に切り替える（§7.1 手順 7）
        val updatedRows = itemStateDao.updateChunkSource(
            mediaId = targetEntity.id,
            chunkSource = ChunkOrigin.IMPORTED.name,
            updatedAt = nowMs,
        )
        if (updatedRows == 0) {
            val defaultSpeed = userSettingsRepository.settings.first().defaultSpeed
                .coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED)
            itemStateDao.upsert(
                ItemStateEntity(
                    mediaId = targetEntity.id,
                    positionMs = 0L,
                    speed = defaultSpeed,
                    chunkSource = ChunkOrigin.IMPORTED.name,
                    updatedAt = nowMs,
                ),
            )
        }
        return paddedEntries.size
    }

    private fun buildPaddedChunksAndTranscripts(
        mediaId: String,
        audioDurationMs: Long,
        doc: TranscriptDocument,
        createdAtMs: Long,
    ): List<Pair<ChunkEntity, TranscriptEntity>> {
        if (doc.segments.isEmpty() || audioDurationMs <= 0L) return emptyList()

        // 音声の長さを超えた分は切り詰める（§7.4.6）
        val clampedSegments = doc.segments.mapNotNull { seg ->
            val start = seg.startMs.coerceIn(0L, audioDurationMs)
            val end = seg.endMs.coerceIn(0L, audioDurationMs)
            if (start < end) {
                seg.copy(startMs = start, endMs = end)
            } else {
                null
            }
        }
        if (clampedSegments.isEmpty()) return emptyList()

        return clampedSegments.mapIndexed { index, seg ->
            val prevEndMs = if (index > 0) clampedSegments[index - 1].endMs else null
            val nextStartMs = if (index < clampedSegments.lastIndex) clampedSegments[index + 1].startMs else null

            val minStartMs = if (prevEndMs != null) {
                (prevEndMs + seg.startMs) / 2L
            } else {
                0L
            }
            val maxEndMs = if (nextStartMs != null) {
                (seg.endMs + nextStartMs) / 2L
            } else {
                audioDurationMs
            }

            val paddedStartMs = max(minStartMs, seg.startMs - PRE_PADDING_MS)
                .coerceIn(0L, seg.startMs)
            val paddedEndMs = min(maxEndMs, seg.endMs + POST_PADDING_MS)
                .coerceIn(max(paddedStartMs + 1L, seg.endMs), audioDurationMs)

            val chunkEntity = ChunkEntity(
                id = 0L,
                mediaId = mediaId,
                startMs = paddedStartMs,
                endMs = paddedEndMs,
                origin = ChunkOrigin.IMPORTED.name,
            )

            val normalizedText = Normalizer.normalize(seg.text, Normalizer.Form.NFC)
            val normalizedVariants = seg.textVariants.mapValues { (_, v) ->
                Normalizer.normalize(v, Normalizer.Form.NFC)
            }
            val normalizedWords = seg.words.map { w ->
                w.copy(text = Normalizer.normalize(w.text.trim(), Normalizer.Form.NFC))
            }
            val domainTranscript = Transcript(
                chunkId = ChunkId(0L),
                text = normalizedText,
                textVariants = normalizedVariants,
                language = seg.language,
                languageConfidence = if (seg.language == null) null else seg.languageConfidence,
                words = normalizedWords,
                engine = doc.generator,
                userEdited = false,
            )
            chunkEntity to domainTranscript.toEntity(createdAtEpochMs = createdAtMs)
        }
    }

    /**
     * SAF のフォルダ選択（`OpenDocumentTree`）で渡された Tree URI を展開し、
     * 直下の音声ファイル・サブフォルダ・および `transcription/` フォルダ内の文字起こしファイルを列挙する。
     */
    private fun expandTreeUri(treeUri: Uri): List<ImportCandidate> {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return emptyList()
        val rootDocUri = runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId) }.getOrNull()
        val rootMeta = rootDocUri?.let(::queryContentMetadata)
        val rootCourse = sanitizeCourseName(
            rootMeta?.displayName ?: extractFolderNameFromDocId(rootDocId),
        ).takeIf { it.isNotBlank() }

        val results = mutableListOf<ImportCandidate>()
        collectTreeChildren(
            treeUri = treeUri,
            parentDocId = rootDocId,
            currentCourse = rootCourse,
            depth = 0,
            out = results,
        )
        return results
    }

    private fun collectTreeChildren(
        treeUri: Uri,
        parentDocId: String,
        currentCourse: String?,
        depth: Int,
        out: MutableList<ImportCandidate>,
    ) {
        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        }.getOrNull() ?: return

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )

        val cursor = runCatching {
            context.contentResolver.query(childrenUri, projection, null, null, null)
        }.getOrNull() ?: return

        cursor.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)

            while (c.moveToNext()) {
                val docId = if (idIdx >= 0 && !c.isNull(idIdx)) c.getString(idIdx) else continue
                val displayName = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else null
                val mimeType = if (mimeIdx >= 0 && !c.isNull(mimeIdx)) c.getString(mimeIdx) else null
                val sizeBytes = if (sizeIdx >= 0 && !c.isNull(sizeIdx)) c.getLong(sizeIdx) else null

                if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < MAX_TREE_DEPTH) {
                        val isTranscriptionDir = displayName?.equals(TRANSCRIPTION_DIR_NAME, ignoreCase = true) == true
                        val childCourse = if (isTranscriptionDir) {
                            currentCourse
                        } else {
                            sanitizeCourseName(displayName).takeIf { it.isNotBlank() } ?: currentCourse
                        }
                        collectTreeChildren(
                            treeUri = treeUri,
                            parentDocId = docId,
                            currentCourse = childCourse,
                            depth = depth + 1,
                            out = out,
                        )
                    }
                } else {
                    val docUri = runCatching {
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    }.getOrNull() ?: continue
                    out += ImportCandidate(
                        uri = docUri,
                        meta = ContentMetadata(
                            displayName = displayName,
                            sizeBytes = sizeBytes,
                            mimeType = mimeType,
                        ),
                        course = currentCourse,
                    )
                }
            }
        }
    }

    private fun isTreeUriSafe(uri: Uri): Boolean = runCatching {
        DocumentsContract.isTreeUri(uri)
    }.getOrDefault(false)

    private fun extractCourseFromDocumentUri(uri: Uri): String? {
        val docId = runCatching {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.getDocumentId(uri)
            } else {
                null
            }
        }.getOrNull() ?: return null

        val pathPart = docId.substringAfter(':', docId)
        val segments = pathPart.split('/').filter { it.isNotBlank() }
        if (segments.size < 2) return null
        val parentSegment = segments[segments.size - 2]
        val courseSegment = if (parentSegment.equals(TRANSCRIPTION_DIR_NAME, ignoreCase = true) && segments.size >= 3) {
            segments[segments.size - 3]
        } else {
            parentSegment
        }
        return sanitizeCourseName(courseSegment).takeIf { it.isNotBlank() }
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

    private data class ImportCandidate(
        val uri: Uri,
        val meta: ContentMetadata?,
        val course: String?,
    )

    private data class ParsedTranscript(
        val document: TranscriptDocument,
        val rawBytes: ByteArray,
        val fileName: String,
        val createdAt: String?,
    )

    private sealed interface InternalTranscriptParseOutcome {
        data class Ok(val parsed: ParsedTranscript) : InternalTranscriptParseOutcome
        data class Invalid(val error: TranscriptError) : InternalTranscriptParseOutcome
    }

    private sealed interface SingleImportOutcome {
        data class Imported(val mediaId: MediaId) : SingleImportOutcome
        data class Duplicate(
            val mediaId: MediaId,
            val reattachedAudioFile: Boolean,
        ) : SingleImportOutcome

        data class Failed(val error: ImportError) : SingleImportOutcome
    }

    private sealed interface CopyOutcome {
        data class Success(val bytesWritten: Long, val sha256Hex: String) : CopyOutcome
        data class Failure(val error: ImportError) : CopyOutcome
    }

    private suspend fun importDownloadedFile(
        tempFile: File,
        displayName: String?,
        course: String?,
        actualSizeBytes: Long,
        sha256: String,
    ): SingleImportOutcome {
        if (!hasEnoughDiskSpace(actualSizeBytes)) {
            tempFile.delete()
            return SingleImportOutcome.Failed(ImportError.NO_SPACE)
        }

        val existingEntity = mediaItemDao.getBySha256(sha256)
        if (existingEntity != null) {
            if (existingEntity.course == null && !course.isNullOrBlank()) {
                mediaItemDao.update(existingEntity.copy(course = course))
            }
            val existingMediaId = MediaId(existingEntity.id)
            val existingAudioFile = mediaFileLocator.resolveAudioFile(existingMediaId, existingEntity.mimeType)
            if (existingAudioFile != null && existingAudioFile.exists()) {
                tempFile.delete()
                return SingleImportOutcome.Duplicate(
                    mediaId = existingMediaId,
                    reattachedAudioFile = false,
                )
            }
        }

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

        if (existingEntity != null) {
            val existingId = MediaId(existingEntity.id)
            val targetFile = mediaFileLocator.audioFileFor(existingId, inspected.extension)
            if (!moveOrCopyTempFile(tempFile, targetFile)) {
                tempFile.delete()
                return SingleImportOutcome.Failed(ImportError.IO)
            }
            audioFileStateVersion.update { it + 1L }
            return SingleImportOutcome.Duplicate(
                mediaId = existingId,
                reattachedAudioFile = true,
            )
        }

        val newMediaId = MediaId(UUID.randomUUID().toString())
        val targetFile = mediaFileLocator.audioFileFor(newMediaId, inspected.extension)
        if (!moveOrCopyTempFile(tempFile, targetFile)) {
            tempFile.delete()
            return SingleImportOutcome.Failed(ImportError.IO)
        }

        val settings = userSettingsRepository.settings.first()
        val sanitizedDisplayName = sanitizeDisplayName(displayName)
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
            course = course,
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
        audioFileStateVersion.update { it + 1L }
        return SingleImportOutcome.Imported(newMediaId)
    }

    private suspend fun uploadLocalTranscriptIfNeeded(item: MediaItemEntity): Boolean {
        val mediaId = MediaId(item.id)
        val existingTranscriptRecords = driveFileDao?.getByMediaId(item.id)
            .orEmpty()
            .filter { it.kind == DriveFileKind.TRANSCRIPT.name }
        val latestDriveModifiedMs = existingTranscriptRecords.maxOfOrNull { it.modifiedTime }

        val localJsonFile = mediaFileLocator.transcriptFileFor(mediaId, "chunkflow.json")
        val pairs = loadActiveChunkTranscriptPairs(item.id)
        if (pairs.isEmpty() && !localJsonFile.exists()) {
            return false
        }

        val hasNewerUserEdit = latestDriveModifiedMs != null &&
            pairs.any { (_, transcript) -> transcript.userEdited && transcript.createdAt > latestDriveModifiedMs }
        val hasNewerLocalFile = latestDriveModifiedMs != null &&
            localJsonFile.exists() &&
            localJsonFile.lastModified() > latestDriveModifiedMs
        val shouldUpload = latestDriveModifiedMs == null || hasNewerUserEdit || hasNewerLocalFile
        if (!shouldUpload) {
            return false
        }

        val jsonBytes = if (pairs.isNotEmpty() && (!localJsonFile.exists() || hasNewerUserEdit)) {
            val built = buildLocalTranscriptJsonBytes(item, pairs) ?: return false
            writeTranscriptBytesAtomically(localJsonFile, built)
            built
        } else if (localJsonFile.exists()) {
            runCatching { localJsonFile.readBytes() }.getOrNull() ?: return false
        } else {
            val built = buildLocalTranscriptJsonBytes(item, pairs) ?: return false
            writeTranscriptBytesAtomically(localJsonFile, built)
            built
        }

        val uploadFileName = "${sanitizeDisplayName(item.title).ifBlank { item.id }}.chunkflow.json"
        val uploadCourse = item.course.orEmpty()
        return when (val uploadRes = driveSyncClient.uploadTranscriptJson(uploadCourse, uploadFileName, jsonBytes)) {
            is DriveUploadResult.Success -> {
                val uploaded = uploadRes.file
                val recordedModifiedMs = max(uploaded.modifiedTimeEpochMs, localJsonFile.lastModified())
                driveFileDao?.upsert(
                    DriveFileEntity(
                        driveFileId = uploaded.id,
                        kind = DriveFileKind.TRANSCRIPT.name,
                        course = uploadCourse,
                        modifiedTime = recordedModifiedMs,
                        sha256 = uploaded.sha256Checksum,
                        mediaId = item.id,
                    ),
                )
                true
            }

            is DriveUploadResult.Failure -> false
        }
    }

    private suspend fun loadActiveChunkTranscriptPairs(
        mediaId: String,
    ): List<Pair<ChunkEntity, TranscriptEntity>> {
        val state = itemStateDao.getByMediaId(mediaId)
        val preferredOrigin = state?.chunkSource ?: ChunkOrigin.AUTO.name
        val fallbackOrigin = if (preferredOrigin == ChunkOrigin.IMPORTED.name) {
            ChunkOrigin.AUTO.name
        } else {
            ChunkOrigin.IMPORTED.name
        }

        val primaryChunks = chunkDao?.getByMediaIdAndOrigin(mediaId, preferredOrigin).orEmpty()
        val chunks = if (primaryChunks.isNotEmpty()) {
            primaryChunks
        } else {
            chunkDao?.getByMediaIdAndOrigin(mediaId, fallbackOrigin).orEmpty()
        }
        if (chunks.isEmpty()) return emptyList()

        val chunkIds = chunks.map { it.id }
        val transcriptList = transcriptDao?.getByChunkIds(chunkIds)
            ?: chunkDao?.getTranscriptsByChunkIds(chunkIds)
            ?: emptyList()
        if (transcriptList.isEmpty()) return emptyList()

        val byChunkId = transcriptList.associateBy { it.chunkId }
        return chunks.mapNotNull { chunk ->
            val transcript = byChunkId[chunk.id] ?: return@mapNotNull null
            if (transcript.text.isBlank()) null else chunk to transcript
        }
    }

    private suspend fun buildLocalTranscriptJsonBytes(
        entity: MediaItemEntity,
        preloadedPairs: List<Pair<ChunkEntity, TranscriptEntity>>? = null,
    ): ByteArray? {
        val pairs = preloadedPairs ?: loadActiveChunkTranscriptPairs(entity.id)
        if (pairs.isEmpty()) return null

        val durationMs = entity.durationMs.coerceAtLeast(1L)
        val ext = extensionForMimeType(entity.mimeType)
        val safeTitle = sanitizeDisplayName(entity.title).ifBlank { DEFAULT_UNTITLED_NAME }
        val audioFileName = "$safeTitle.$ext"
        val sha256 = entity.sha256.lowercase()
        val targetLang = entity.targetLang.ifBlank { "en" }
        val baseLang = entity.baseLang?.takeIf { it.isNotBlank() && it != targetLang }
        val engineName = pairs.first().second.engine.ifBlank { "on-device" }
        val createdAtIso = Instant.ofEpochMilli(clock())
            .atOffset(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_INSTANT)

        val validLanguages = setOfNotNull(targetLang, baseLang, "und")
        var prevEndMs = 0L

        val segmentsJson = mutableListOf<String>()
        for ((chunk, transcriptEntity) in pairs) {
            val domainTranscript = transcriptEntity.toExternalModel()
            val startMs = max(prevEndMs, chunk.startMs.coerceIn(0L, durationMs - 1L))
            val endMs = chunk.endMs.coerceIn(startMs + 1L, durationMs)
            if (startMs >= endMs) continue
            prevEndMs = endMs

            val normalizedText = Normalizer.normalize(domainTranscript.text, Normalizer.Form.NFC)
                .take(MAX_EXPORT_SEGMENT_CHARS)
            if (normalizedText.isBlank()) continue

            val lang = domainTranscript.language?.value?.takeIf { it in validLanguages }
            val langProb = domainTranscript.languageConfidence?.takeIf { lang != null && it in 0f..1f }

            val variantsEntries = domainTranscript.textVariants
                .filterKeys { it == "Hans" || it == "Hant" }
                .entries
                .mapNotNull { (k, v) ->
                    val norm = Normalizer.normalize(v, Normalizer.Form.NFC).trim()
                    if (norm.isEmpty()) null else "${jsonString(k)}:${jsonString(norm)}"
                }

            var wordPrevEnd = startMs
            val wordEntries = mutableListOf<String>()
            for (word in domainTranscript.words) {
                val wText = Normalizer.normalize(word.text.trim(), Normalizer.Form.NFC)
                if (wText.isEmpty()) continue
                val wStart = max(wordPrevEnd, word.startMs.coerceIn(startMs, endMs))
                val wEnd = word.endMs.coerceIn(wStart, endMs)
                wordPrevEnd = wEnd
                val probField = word.confidence?.takeIf { it in 0f..1f }?.let { ",\"prob\":$it" }.orEmpty()
                wordEntries += "{\"startMs\":$wStart,\"endMs\":$wEnd,\"text\":${jsonString(wText)}$probField}"
            }

            val segBuilder = StringBuilder()
            segBuilder.append("{\"startMs\":").append(startMs)
            segBuilder.append(",\"endMs\":").append(endMs)
            if (lang != null) {
                segBuilder.append(",\"lang\":").append(jsonString(lang))
            }
            if (langProb != null) {
                segBuilder.append(",\"langProb\":").append(langProb)
            }
            segBuilder.append(",\"text\":").append(jsonString(normalizedText))
            if (variantsEntries.isNotEmpty()) {
                segBuilder.append(",\"textVariants\":{").append(variantsEntries.joinToString(",")).append("}")
            }
            if (wordEntries.isNotEmpty()) {
                segBuilder.append(",\"words\":[").append(wordEntries.joinToString(",")).append("]")
            }
            segBuilder.append("}")
            segmentsJson += segBuilder.toString()
        }

        if (segmentsJson.isEmpty()) return null

        val baseLangField = if (baseLang != null) {
            ",\"base\":${jsonString(baseLang)}"
        } else {
            ""
        }

        val fullJson = buildString {
            append("{")
            append("\"format\":\"chunkflow-transcript\",")
            append("\"version\":1,")
            append("\"audio\":{")
            append("\"fileName\":").append(jsonString(audioFileName)).append(",")
            append("\"sha256\":").append(jsonString(sha256)).append(",")
            append("\"durationMs\":").append(durationMs)
            append("},")
            append("\"languages\":{")
            append("\"target\":").append(jsonString(targetLang))
            append(baseLangField)
            append("},")
            append("\"generator\":{")
            append("\"tool\":\"chunkflow-android\",")
            append("\"engine\":").append(jsonString(engineName)).append(",")
            append("\"model\":\"local\",")
            append("\"createdAt\":").append(jsonString(createdAtIso))
            append("},")
            append("\"segments\":[").append(segmentsJson.joinToString(",")).append("]")
            append("}")
        }
        return fullJson.toByteArray(Charsets.UTF_8)
    }

    private fun writeTranscriptBytesAtomically(targetFile: File, bytes: ByteArray): Boolean = try {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        FileOutputStream(tempFile).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        moveOrCopyTempFile(tempFile, targetFile)
    } catch (_: IOException) {
        false
    }

    private fun formatTranscriptExport(
        pairs: List<Pair<Chunk, Transcript>>,
        format: TranscriptExportFormat,
    ): String = when (format) {
        TranscriptExportFormat.SRT -> {
            pairs.mapIndexed { index, (chunk, transcript) ->
                val seq = index + 1
                val start = formatSrtTimestamp(chunk.startMs)
                val end = formatSrtTimestamp(chunk.endMs)
                val text = transcript.text.trim()
                "$seq\n$start --> $end\n$text"
            }.joinToString("\n\n")
        }

        TranscriptExportFormat.LRC -> {
            pairs.joinToString("\n") { (chunk, transcript) ->
                val tag = formatLrcTimestamp(chunk.startMs)
                val text = transcript.text.trim().replace("\r\n", " ").replace('\n', ' ')
                "[$tag]$text"
            }
        }

        TranscriptExportFormat.TXT -> {
            pairs.joinToString("\n") { (chunk, transcript) ->
                val timeTag = formatTxtTimestamp(chunk.startMs)
                val lang = transcript.language?.value?.takeIf { it.isNotBlank() }
                val text = transcript.text.trim().replace("\r\n", " ").replace('\n', ' ')
                if (lang != null) {
                    "[$timeTag] [${lang.uppercase(Locale.ROOT)}] $text"
                } else {
                    "[$timeTag] $text"
                }
            }
        }
    }

    private fun formatSrtTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val hours = clamped / 3_600_000L
        val minutes = (clamped % 3_600_000L) / 60_000L
        val seconds = (clamped % 60_000L) / 1_000L
        val millis = clamped % 1_000L
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    private fun formatLrcTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalMinutes = clamped / 60_000L
        val seconds = (clamped % 60_000L) / 1_000L
        val centiseconds = (clamped % 1_000L) / 10L
        return String.format(Locale.ROOT, "%02d:%02d.%02d", totalMinutes, seconds, centiseconds)
    }

    private fun formatTxtTimestamp(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val totalMinutes = clamped / 60_000L
        val seconds = (clamped % 60_000L) / 1_000L
        return String.format(Locale.ROOT, "%02d:%02d", totalMinutes, seconds)
    }

    private fun mapDriveErrorToSyncError(error: DriveError): SyncError = when (error) {
        DriveError.AUTH -> SyncError.NOT_AUTHORIZED

        DriveError.FOLDER_NOT_FOUND -> SyncError.FOLDER_NOT_FOUND

        DriveError.NETWORK,
        DriveError.TOO_LARGE,
        DriveError.INVALID_RESPONSE,
        -> SyncError.NETWORK
    }

    private object NoOpDriveSyncClient : DriveSyncClient {
        override suspend fun listChunkFlowCourses(): DriveListResult = DriveListResult.Failure(DriveError.AUTH)
        override suspend fun downloadToFile(
            fileId: String,
            destFile: File,
            maxSizeBytes: Long,
            onProgress: ((Long) -> Unit)?,
        ): DriveDownloadResult = DriveDownloadResult.Failure(DriveError.AUTH)

        override suspend fun downloadBytes(
            fileId: String,
            maxSizeBytes: Long,
        ): DriveBytesDownloadResult = DriveBytesDownloadResult.Failure(DriveError.AUTH)

        override suspend fun uploadTranscriptJson(
            courseName: String,
            fileName: String,
            jsonBytes: ByteArray,
        ): DriveUploadResult = DriveUploadResult.Failure(DriveError.AUTH)
    }

    private object NoOpAnalysisRepository : AnalysisRepository {
        override fun observeStatus(id: MediaId): Flow<Map<AnalysisStage, StageStatus>> = flowOf(emptyMap())
        override fun observeWaveform(id: MediaId): Flow<Waveform?> = flowOf(null)
        override fun observeChunks(id: MediaId): Flow<List<Chunk>> = flowOf(emptyList())
        override fun observeTranscripts(id: MediaId): Flow<Map<ChunkId, Transcript>> = flowOf(emptyMap())
        override suspend fun requestAnalysis(id: MediaId, stages: Set<AnalysisStage>) = Unit
        override suspend fun resegment(id: MediaId, params: SegmentationParams) = Unit
        override suspend fun setChunkSource(id: MediaId, source: ChunkOrigin) = Unit
        override suspend fun setTranscriptOffset(id: MediaId, offsetMs: Int) = Unit
    }

    private object NoOpTranscriptParser : TranscriptParser {
        override fun parse(bytes: ByteArray, fileName: String): TranscriptParseResult = TranscriptParseResult.Error(TranscriptError.UNKNOWN_FORMAT, "NoOpTranscriptParser")
    }

    companion object {
        /** 音声 1 ファイルあたりの上限サイズ: 2 GB（§8.1）。 */
        const val MAX_AUDIO_SIZE_BYTES: Long = 2L * 1024L * 1024L * 1024L

        /** 文字起こし 1 ファイルあたりの上限サイズ: 20 MB（§8.1）。 */
        const val MAX_TRANSCRIPT_SIZE_BYTES: Long = 20L * 1024L * 1024L

        /** 取り込みチャンクの前パディング（ミリ秒、§7.3.3 手順 7、§7.4.6）。 */
        const val PRE_PADDING_MS: Long = 150L

        /** 取り込みチャンクの後パディング（ミリ秒、§7.3.3 手順 7、§7.4.6）。 */
        const val POST_PADDING_MS: Long = 200L

        /** 音声長と文字起こし JSON の durationMs の不一致警告閾値（ミリ秒、§7.4.6: 0.5 秒）。 */
        const val DURATION_MISMATCH_THRESHOLD_MS: Long = 500L

        private const val COPY_BUFFER_BYTES: Int = 64 * 1024
        private const val MAX_TITLE_CODE_POINTS: Int = 200
        private const val DEFAULT_UNTITLED_NAME: String = "Untitled"
        private const val TRANSCRIPTION_DIR_NAME: String = "transcription"
        private const val MAX_TREE_DEPTH: Int = 2
        private const val MAX_EXPORT_SEGMENT_CHARS: Int = 2_000

        private fun extensionForMimeType(mimeType: String): String = when (mimeType.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
            else -> "mp3"
        }

        private fun jsonString(value: String): String = buildString(value.length + 2) {
            append('"')
            for (ch in value) {
                when (ch) {
                    '"' -> append("\\\"")

                    '\\' -> append("\\\\")

                    '\b' -> append("\\b")

                    '\u000C' -> append("\\f")

                    '\n' -> append("\\n")

                    '\r' -> append("\\r")

                    '\t' -> append("\\t")

                    else -> {
                        if (ch.code < 0x20) {
                            append("\\u%04x".format(ch.code))
                        } else {
                            append(ch)
                        }
                    }
                }
            }
            append('"')
        }

        private val CREATED_AT_REGEX = Regex(""""createdAt"\s*:\s*"([^"]+)"""")

        @SuppressLint("UsableSpace")
        private fun defaultUsableSpaceBytes(context: Context): Long = context.filesDir.usableSpace

        private fun isTranscriptCandidate(candidate: ImportCandidate): Boolean {
            val rawName = (candidate.meta?.displayName ?: candidate.uri.lastPathSegment.orEmpty()).lowercase()
            if (rawName.endsWith(".chunkflow.json") ||
                rawName.endsWith(".srt") ||
                rawName.endsWith(".vtt") ||
                rawName.endsWith(".lrc")
            ) {
                return true
            }
            val mime = candidate.meta?.mimeType?.lowercase()
            return mime == "application/json" && rawName.endsWith(".json")
        }

        private fun transcriptExtensionForFileName(fileName: String): String {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".chunkflow.json") -> "chunkflow.json"
                lower.endsWith(".srt") -> "srt"
                lower.endsWith(".vtt") -> "vtt"
                lower.endsWith(".lrc") -> "lrc"
                lower.endsWith(".json") -> "json"
                else -> "chunkflow.json"
            }
        }

        private fun extractCreatedAtIsoString(bytes: ByteArray): String? = runCatching {
            val text = bytes.decodeToString()
            CREATED_AT_REGEX.find(text)?.groupValues?.getOrNull(1)
        }.getOrNull()

        private fun extractFolderNameFromDocId(docId: String): String {
            val pathPart = docId.substringAfter(':', docId)
            return pathPart.trim('/').substringAfterLast('/')
        }

        internal fun extractFileStem(rawName: String): String {
            val fileName = rawName.substringAfterLast('/').substringAfterLast('\\').trim()
            val withoutTranscriptExt = fileName.replace(
                Regex("\\.(chunkflow\\.json|json|srt|vtt|lrc)$", RegexOption.IGNORE_CASE),
                "",
            )
            return sanitizeDisplayName(withoutTranscriptExt).lowercase()
        }

        internal fun sanitizeCourseName(rawCourse: String?): String {
            if (rawCourse.isNullOrBlank()) return ""
            val lastSegment = rawCourse.trim('/').substringAfterLast('/').substringAfterLast('\\')
            val stripped = buildString(lastSegment.length) {
                for (ch in lastSegment) {
                    val isControl = ch.code < 0x20 || ch.code == 0x7F
                    val isPathSep = ch == '/' || ch == '\\'
                    if (!isControl && !isPathSep) {
                        append(ch)
                    }
                }
            }.trim()
            val codePoints = stripped.codePoints().toArray()
            return if (codePoints.size <= MAX_TITLE_CODE_POINTS) {
                stripped
            } else {
                String(codePoints, 0, MAX_TITLE_CODE_POINTS)
            }
        }

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
