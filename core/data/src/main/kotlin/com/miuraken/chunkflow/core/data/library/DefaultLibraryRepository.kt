package com.miuraken.chunkflow.core.data.library

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.data.analysis.DefaultAnalysisRepository
import com.miuraken.chunkflow.core.data.di.ChunkFlowDispatchers
import com.miuraken.chunkflow.core.data.di.Dispatcher
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.CourseFolderDao
import com.miuraken.chunkflow.core.database.dao.DriveFileDao
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.dao.PlaylistDao
import com.miuraken.chunkflow.core.database.dao.TranscriptDao
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.CourseFolderEntity
import com.miuraken.chunkflow.core.database.entity.DriveFileEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.PlaylistEntity
import com.miuraken.chunkflow.core.database.entity.PlaylistItemEntity
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
import com.miuraken.chunkflow.core.model.AudioStorageState
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.DriveFileKind
import com.miuraken.chunkflow.core.model.ItemState
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryCourseFolder
import com.miuraken.chunkflow.core.model.LibraryFolderKind
import com.miuraken.chunkflow.core.model.LibraryPlaylist
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.PlaylistId
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
import com.miuraken.chunkflow.core.transcript.TranscriptTextExporter
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.flow.FlowCollector
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
 *   フォルダ名を [AudioItem.course] に記録する（§7.1）。
 * - 音声の取り込み完了後、[AnalysisRepository.requestAnalysis] で波形・特徴量・自動分割のジョブを投入する（§7.1 手順 6）。
 * - 文字起こし（`.chunkflow.json` 等）はファイル個別設定画面からファイル単位で手動取り込みを行い、前後のパディング（前 150ms / 後 200ms、
 *   隣のチャンクとの無音の中点を越えない）を付けて [ChunkOrigin.IMPORTED] のチャンクとして管理領域に保存する（§7.4.6）。
 */
@SuppressLint("UseKtx")
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
    private val courseFolderDao: CourseFolderDao = NoOpCourseFolderDao,
    private val playlistDao: PlaylistDao = NoOpPlaylistDao,
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
        courseFolderDao: CourseFolderDao,
        playlistDao: PlaylistDao,
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
        courseFolderDao = courseFolderDao,
        playlistDao = playlistDao,
        driveSyncClient = driveSyncClient,
        analysisRepository = analysisRepository,
        transcriptParser = transcriptParser,
        clock = System::currentTimeMillis,
        usableSpaceBytesProvider = { defaultUsableSpaceBytes(context) },
    )

    private val audioFileStateVersion = MutableStateFlow(0L)
    private val scheduledAnalysisMediaIds: MutableSet<MediaId> = ConcurrentHashMap.newKeySet()

    override fun observeItems(): Flow<List<AudioItem>> = mediaItemDao.observeAll().map { entities ->
        entities.map(MediaItemEntity::toExternalModel)
    }

    override fun observeCourseFolders(): Flow<List<LibraryCourseFolder>> = courseFolderDao.observeAll().map { entities ->
        entities.map(CourseFolderEntity::toExternalModel)
    }

    override fun observePlaylists(): Flow<List<LibraryPlaylist>> = combine(
        playlistDao.observePlaylists(),
        playlistDao.observeAllPlaylistItems(),
    ) { playlists, items ->
        val itemsByPlaylist = items.groupBy(
            keySelector = { it.playlistId },
            valueTransform = { MediaId(it.mediaId) },
        )
        playlists.map { entity ->
            entity.toExternalModel(itemIds = itemsByPlaylist[entity.id].orEmpty())
        }
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
            // 再生中に削除された教材の設定は書かない（外部キー違反で落ちないように）
            itemStateDao.upsertIfMediaExists(state.toEntity())
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
            // 再生中に削除された教材の位置は書かない（プレイヤーは削除後も保存しようとする）
            val saved = itemStateDao.updatePlaybackPosition(
                mediaId = id.value,
                positionMs = coercedPosition,
                speed = coercedSpeed,
                updatedAt = updatedAtEpochMs,
            )
            if (saved && coercedPosition > 0L) {
                val course = mediaItemDao.getById(id.value)?.course
                scheduleCourseAnalysisUpToLimit(course)
            }
        }
    }

    override fun import(uris: List<Uri>): Flow<ImportProgress> = import(
        uris = uris,
        course = null,
        languages = null,
    )

    override fun import(
        uris: List<Uri>,
        course: String?,
        languages: LanguagePair?,
    ): Flow<ImportProgress> = flow {
        if (uris.isEmpty()) {
            emit(ImportProgress.Done(mediaIds = emptyList(), duplicates = 0, transcriptsAttached = 0))
            return@flow
        }

        val explicitCourse = sanitizeCoursePath(course).takeIf { it.isNotBlank() }

        // 1. スキームの検証と SAF フォルダ（Tree URI）の展開
        val rawCandidates = mutableListOf<ImportCandidate>()
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
                emit(ImportProgress.Failed(ImportError.UNSUPPORTED_FORMAT))
                return@flow
            }
            if (isTreeUriSafe(uri)) {
                rawCandidates += expandTreeUri(uri, rootCourseOverride = explicitCourse)
            } else {
                val meta = queryContentMetadata(uri)
                val resolvedCourse = extractCourseFromDocumentUri(uri, explicitRootCourse = explicitCourse)
                rawCandidates += ImportCandidate(
                    uri = uri,
                    meta = meta,
                    course = resolvedCourse,
                )
            }
        }

        val candidates = rawCandidates.distinctBy { it.uri }
        if (candidates.isEmpty()) {
            emit(ImportProgress.Done(mediaIds = emptyList(), duplicates = 0, transcriptsAttached = 0))
            return@flow
        }

        // 文字起こしのインポートは再生画面のファイル個別設定画面から手動でのみ行うため、音声取り込み時は無視する
        val (_, rawAudioCandidates) = candidates.partition(::isTranscriptCandidate)
        val audioCandidates = rawAudioCandidates.sortedWith { left, right ->
            naturalFileNameComparator.compare(
                left.meta?.displayName ?: left.uri.lastPathSegment.orEmpty(),
                right.meta?.displayName ?: right.uri.lastPathSegment.orEmpty(),
            )
        }
        val totalAudio = audioCandidates.size
        val mediaIds = mutableListOf<MediaId>()
        var duplicates = 0

        // 2. 音声ファイルの取り込み（§7.1 手順 1〜6）
        // 読み込めない非対応ファイルや破損ファイルはエラーバナーを出さずに無視してスキップする
        for ((index, candidate) in audioCandidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            val uri = candidate.uri
            val meta = candidate.meta
            val declaredSize = meta?.sizeBytes?.takeIf { it > 0L }
            if (declaredSize != null) {
                if (declaredSize > MAX_AUDIO_SIZE_BYTES) {
                    continue
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
                    explicitCourse = explicitCourse,
                    languages = languages,
                    tempFile = tempFile,
                    fileIndex = index,
                    totalFiles = totalAudio.coerceAtLeast(1),
                    onProgress = { fraction -> emit(ImportProgress.Copying(fraction)) },
                )
            } catch (ce: CancellationException) {
                tempFile.delete()
                throw ce
            }

            when (outcome) {
                is SingleImportOutcome.Imported -> {
                    mediaIds += outcome.mediaId
                    scheduleCourseAnalysisUpToLimit(candidate.course)
                }

                is SingleImportOutcome.Duplicate -> {
                    mediaIds += outcome.mediaId
                    duplicates += 1
                    if (outcome.reattachedAudioFile) {
                        handleReattachedAudioAnalysis(outcome.mediaId, candidate.course)
                    }
                }

                is SingleImportOutcome.Failed -> {
                    if (outcome.error == ImportError.NO_SPACE) {
                        emit(ImportProgress.Failed(ImportError.NO_SPACE))
                        return@flow
                    }
                    continue
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

    override fun linkLocalDirectory(
        treeUri: Uri,
        course: String?,
        languages: LanguagePair?,
    ): Flow<ImportProgress> = flow {
        if (treeUri.scheme != ContentResolver.SCHEME_CONTENT) {
            emit(ImportProgress.Failed(ImportError.UNSUPPORTED_FORMAT))
            return@flow
        }

        takePersistableFolderPermission(treeUri)

        val explicitCourse = sanitizeCoursePath(course).takeIf { it.isNotBlank() }
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val rootDocUri = rootDocId?.let {
            runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }.getOrNull()
        }
        val rootMeta = rootDocUri?.let(::queryContentMetadata)
        val courseJsonMeta = if (rootDocId != null) {
            readCourseJsonFromSafDir(treeUri, rootDocId)
        } else {
            null
        }
        val defaultFolderName = sanitizeCourseName(
            rootMeta?.displayName ?: rootDocId?.let(::extractFolderNameFromDocId),
        ).takeIf { it.isNotBlank() }
        val resolvedRootCourse = explicitCourse
            ?: courseJsonMeta?.coursePath
            ?: defaultFolderName
            ?: DEFAULT_IMPORTED_COURSE_NAME

        val defaultLanguages = userSettingsRepository.settings.first().defaultLanguagePair
        val existingFolder = courseFolderDao.getByPath(resolvedRootCourse)
            ?: courseFolderDao.getByTreeUri(treeUri.toString())
        val effectiveLanguages = languages
            ?: courseJsonMeta?.languages
            ?: existingFolder?.toExternalModel()?.languagePair
            ?: defaultLanguages

        val nowMs = clock()
        courseFolderDao.upsert(
            CourseFolderEntity(
                coursePath = resolvedRootCourse,
                kind = LibraryFolderKind.LOCAL_DIRECTORY.name,
                targetLang = effectiveLanguages.target.value,
                baseLang = effectiveLanguages.base?.value,
                treeUri = treeUri.toString(),
                driveFolderId = null,
                lastScannedAt = nowMs,
            ),
        )
        if (rootDocId != null) {
            writeCourseJsonToSafDir(
                treeUri = treeUri,
                dirDocId = rootDocId,
                coursePath = resolvedRootCourse,
                languages = effectiveLanguages,
            )
        }

        val discoveredDirs = mutableListOf<DiscoveredDirectory>()
        val rawCandidates = if (isTreeUriSafe(treeUri)) {
            expandTreeUri(
                treeUri = treeUri,
                rootCourseOverride = resolvedRootCourse,
                discoveredDirs = discoveredDirs,
            )
        } else {
            emptyList()
        }

        for (dir in discoveredDirs) {
            val subCourseJson = readCourseJsonFromSafDir(treeUri, dir.docId)
            val subLanguages = languages ?: subCourseJson?.languages ?: effectiveLanguages
            courseFolderDao.upsert(
                CourseFolderEntity(
                    coursePath = dir.coursePath,
                    kind = LibraryFolderKind.LOCAL_DIRECTORY.name,
                    targetLang = subLanguages.target.value,
                    baseLang = subLanguages.base?.value,
                    treeUri = treeUri.toString(),
                    driveFolderId = null,
                    lastScannedAt = nowMs,
                ),
            )
            writeCourseJsonToSafDir(
                treeUri = treeUri,
                dirDocId = dir.docId,
                coursePath = dir.coursePath,
                languages = subLanguages,
            )
        }

        val candidates = rawCandidates.distinctBy { it.uri }
        if (candidates.isEmpty()) {
            emit(ImportProgress.Done(mediaIds = emptyList(), duplicates = 0, transcriptsAttached = 0))
            return@flow
        }

        val (_, rawAudioCandidates) = candidates.partition(::isTranscriptCandidate)
        val audioCandidates = rawAudioCandidates.sortedWith { left, right ->
            naturalFileNameComparator.compare(
                left.meta?.displayName ?: left.uri.lastPathSegment.orEmpty(),
                right.meta?.displayName ?: right.uri.lastPathSegment.orEmpty(),
            )
        }

        val totalAudio = audioCandidates.size
        val mediaIds = mutableListOf<MediaId>()
        var duplicates = 0

        for ((index, candidate) in audioCandidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            val declaredSize = candidate.meta?.sizeBytes?.takeIf { it > 0L }
            if (declaredSize != null && declaredSize > MAX_AUDIO_SIZE_BYTES) {
                continue
            }

            emit(ImportProgress.Copying(fraction = index.toFloat() / totalAudio.coerceAtLeast(1).toFloat()))

            val folderLangs = candidate.course?.let { courseFolderDao.getByPath(it)?.toExternalModel()?.languagePair }
                ?: effectiveLanguages
            val outcome = linkSingleLocalAudioFile(
                uri = candidate.uri,
                meta = candidate.meta,
                course = candidate.course ?: resolvedRootCourse,
                languages = folderLangs,
                fileIndex = index,
                totalFiles = totalAudio.coerceAtLeast(1),
                onProgress = { fraction -> emit(ImportProgress.Copying(fraction)) },
            )

            when (outcome) {
                is SingleImportOutcome.Imported -> {
                    mediaIds += outcome.mediaId
                    scheduleCourseAnalysisUpToLimit(candidate.course ?: resolvedRootCourse)
                }

                is SingleImportOutcome.Duplicate -> {
                    mediaIds += outcome.mediaId
                    duplicates += 1
                    if (outcome.reattachedAudioFile) {
                        handleReattachedAudioAnalysis(outcome.mediaId, candidate.course ?: resolvedRootCourse)
                    }
                }

                is SingleImportOutcome.Failed -> continue
            }
        }

        audioFileStateVersion.update { it + 1L }
        emit(
            ImportProgress.Done(
                mediaIds = mediaIds.toList(),
                duplicates = duplicates,
                transcriptsAttached = 0,
            ),
        )
    }.flowOn(ioDispatcher)

    override suspend fun rescanLocalDirectories(coursePath: String?): Int = withContext(ioDispatcher) {
        val allLocalFolders = courseFolderDao.getAll()
            .filter { it.kind == LibraryFolderKind.LOCAL_DIRECTORY.name && !it.treeUri.isNullOrBlank() }
        if (allLocalFolders.isEmpty()) return@withContext 0

        val filterCourse = sanitizeCoursePath(coursePath).takeIf { it.isNotBlank() }
        val groupedByTree = allLocalFolders.groupBy { it.treeUri.orEmpty() }
        var changedCount = 0
        val nowMs = clock()

        for ((treeUriStr, foldersForTree) in groupedByTree) {
            currentCoroutineContext().ensureActive()
            if (filterCourse != null) {
                val matchesScope = foldersForTree.any { folder ->
                    folder.coursePath == filterCourse ||
                        folder.coursePath.startsWith("$filterCourse/") ||
                        filterCourse.startsWith("${folder.coursePath}/")
                }
                if (!matchesScope) continue
            }

            val rootFolder = foldersForTree.minByOrNull { it.coursePath.length } ?: continue
            val treeUri = runCatching { Uri.parse(treeUriStr) }.getOrNull() ?: continue
            if (!isTreeUriSafe(treeUri)) continue

            val discoveredDirs = mutableListOf<DiscoveredDirectory>()
            val allCandidates = expandTreeUri(
                treeUri = treeUri,
                rootCourseOverride = rootFolder.coursePath,
                discoveredDirs = discoveredDirs,
            ).distinctBy { it.uri }

            val rootLanguages = rootFolder.toExternalModel().languagePair
            for (dir in discoveredDirs) {
                if (filterCourse == null ||
                    dir.coursePath == filterCourse ||
                    dir.coursePath.startsWith("$filterCourse/")
                ) {
                    val existingSub = courseFolderDao.getByPath(dir.coursePath)
                    val subCourseJson = readCourseJsonFromSafDir(treeUri, dir.docId)
                    val subLanguages = subCourseJson?.languages
                        ?: existingSub?.toExternalModel()?.languagePair
                        ?: rootLanguages
                    courseFolderDao.upsert(
                        CourseFolderEntity(
                            coursePath = dir.coursePath,
                            kind = LibraryFolderKind.LOCAL_DIRECTORY.name,
                            targetLang = subLanguages.target.value,
                            baseLang = subLanguages.base?.value,
                            treeUri = treeUriStr,
                            driveFolderId = null,
                            lastScannedAt = nowMs,
                        ),
                    )
                }
            }

            val scopedCandidates = if (filterCourse == null) {
                allCandidates
            } else {
                allCandidates.filter { candidate ->
                    val c = candidate.course ?: rootFolder.coursePath
                    c == filterCourse || c.startsWith("$filterCourse/")
                }
            }

            val (_, rawAudioCandidates) = scopedCandidates.partition(::isTranscriptCandidate)
            val audioCandidates = rawAudioCandidates.sortedWith { left, right ->
                naturalFileNameComparator.compare(
                    left.meta?.displayName ?: left.uri.lastPathSegment.orEmpty(),
                    right.meta?.displayName ?: right.uri.lastPathSegment.orEmpty(),
                )
            }

            val seenMediaIds = mutableSetOf<String>()
            val seenSourceUris = mutableSetOf<String>()

            for ((index, candidate) in audioCandidates.withIndex()) {
                currentCoroutineContext().ensureActive()
                val declaredSize = candidate.meta?.sizeBytes?.takeIf { it > 0L }
                if (declaredSize != null && declaredSize > MAX_AUDIO_SIZE_BYTES) {
                    continue
                }

                val candidateCourse = candidate.course ?: rootFolder.coursePath
                seenSourceUris += candidate.uri.toString()
                val folderLangs = courseFolderDao.getByPath(candidateCourse)?.toExternalModel()?.languagePair
                    ?: rootLanguages

                val outcome = linkSingleLocalAudioFile(
                    uri = candidate.uri,
                    meta = candidate.meta,
                    course = candidateCourse,
                    languages = folderLangs,
                    fileIndex = index,
                    totalFiles = audioCandidates.size.coerceAtLeast(1),
                    onProgress = {},
                )

                when (outcome) {
                    is SingleImportOutcome.Imported -> {
                        changedCount += 1
                        seenMediaIds += outcome.mediaId.value
                        scheduleCourseAnalysisUpToLimit(candidateCourse)
                    }

                    is SingleImportOutcome.Duplicate -> {
                        seenMediaIds += outcome.mediaId.value
                        if (outcome.reattachedAudioFile) {
                            changedCount += 1
                            handleReattachedAudioAnalysis(outcome.mediaId, candidateCourse)
                        }
                    }

                    is SingleImportOutcome.Failed -> continue
                }
            }

            // フォルダから消失した LOCAL_REFERENCE 教材を DB から削除する
            val scopePrefix = filterCourse ?: rootFolder.coursePath
            val existingItemsInScope = mediaItemDao.observeAll().first().filter { item ->
                val c = item.course ?: return@filter false
                (c == scopePrefix || c.startsWith("$scopePrefix/")) &&
                    item.storageState == AudioStorageState.LOCAL_REFERENCE.name
            }
            for (item in existingItemsInScope) {
                if (item.id !in seenMediaIds && (item.sourceUri == null || item.sourceUri !in seenSourceUris)) {
                    val mediaId = MediaId(item.id)
                    scheduledAnalysisMediaIds.remove(mediaId)
                    mediaItemDao.deleteById(item.id)
                    mediaFileLocator.deleteMediaFiles(mediaId)
                    changedCount += 1
                }
            }

            for (folder in foldersForTree) {
                if (filterCourse == null ||
                    folder.coursePath == filterCourse ||
                    folder.coursePath.startsWith("$filterCourse/")
                ) {
                    courseFolderDao.upsert(folder.copy(lastScannedAt = nowMs))
                }
            }
        }

        audioFileStateVersion.update { it + 1L }
        changedCount
    }

    override suspend fun createVirtualFolder(
        coursePath: String,
        languages: LanguagePair,
    ): Boolean = withContext(ioDispatcher) {
        val normalizedCourse = sanitizeCoursePath(coursePath).takeIf { it.isNotBlank() }
            ?: return@withContext false
        val existing = courseFolderDao.getByPath(normalizedCourse)
        val kind = existing?.kind ?: LibraryFolderKind.VIRTUAL_DIRECTORY.name
        courseFolderDao.upsert(
            CourseFolderEntity(
                coursePath = normalizedCourse,
                kind = kind,
                targetLang = languages.target.value,
                baseLang = languages.base?.value,
                treeUri = existing?.treeUri,
                driveFolderId = existing?.driveFolderId,
                lastScannedAt = existing?.lastScannedAt ?: clock(),
            ),
        )
        true
    }

    override suspend fun moveItemsToCourse(
        mediaIds: Set<MediaId>,
        targetCourse: String,
    ) {
        if (mediaIds.isEmpty()) return
        withContext(ioDispatcher) {
            val normalizedCourse = sanitizeCoursePath(targetCourse).takeIf { it.isNotBlank() }
            val folderConfig = normalizedCourse?.let { courseFolderDao.getByPath(it) }
            for (mediaId in mediaIds) {
                if (folderConfig != null) {
                    mediaItemDao.updateCourseAndLanguagePair(
                        id = mediaId.value,
                        course = normalizedCourse,
                        targetLang = folderConfig.targetLang,
                        baseLang = folderConfig.baseLang,
                    )
                } else {
                    mediaItemDao.updateCourse(mediaId.value, normalizedCourse)
                }
            }
        }
    }

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

    override suspend fun importTranscriptForMedia(id: MediaId, uri: Uri): TranscriptImportResult = withContext(ioDispatcher) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@withContext TranscriptImportResult.Invalid(TranscriptError.UNKNOWN_FORMAT)
        }
        val targetEntity = mediaItemDao.getById(id.value)
            ?: return@withContext TranscriptImportResult.NoMatchingAudio(audioFileName = null)
        val meta = queryContentMetadata(uri)
        val parseOutcome = readAndParseTranscript(uri, meta)
        val parsed = when (parseOutcome) {
            is InternalTranscriptParseOutcome.Ok -> parseOutcome.parsed

            is InternalTranscriptParseOutcome.Invalid -> {
                return@withContext TranscriptImportResult.Invalid(parseOutcome.error)
            }
        }

        val chunkCount = attachTranscriptToMediaItem(targetEntity, parsed)
            ?: return@withContext TranscriptImportResult.Invalid(TranscriptError.SCHEMA_VIOLATION)
        val docDurationMs = parsed.document.durationMs
        val durationMismatch = docDurationMs != null &&
            abs(targetEntity.durationMs - docDurationMs) >= DURATION_MISMATCH_THRESHOLD_MS
        TranscriptImportResult.Attached(
            mediaId = id,
            chunkCount = chunkCount,
            durationMismatch = durationMismatch,
        )
    }

    override suspend fun resetTranscriptsAndDeleteFiles(id: MediaId): Boolean = withContext(ioDispatcher) {
        analysisRepository.clearTranscriptsForMedia(id)
        chunkDao?.deleteByMediaIdAndOrigin(id.value, ChunkOrigin.IMPORTED.name)
        val existingState = itemStateDao.getByMediaId(id.value)
        if (existingState != null && existingState.chunkSource == ChunkOrigin.IMPORTED.name) {
            itemStateDao.updateChunkSource(
                mediaId = id.value,
                chunkSource = ChunkOrigin.AUTO.name,
                updatedAt = clock(),
            )
        }
        mediaFileLocator.deleteTranscriptFiles(id)
        driveFileDao?.deleteByMediaIdAndKind(id.value, DriveFileKind.TRANSCRIPT.name)
        true
    }

    override suspend fun updateItemMetadata(
        id: MediaId,
        course: String?,
        languages: LanguagePair,
    ) {
        withContext(ioDispatcher) {
            val normalizedCourse = sanitizeCoursePath(course).takeIf { it.isNotBlank() }
            mediaItemDao.updateCourseAndLanguagePair(
                id = id.value,
                course = normalizedCourse,
                targetLang = languages.target.value,
                baseLang = languages.base?.value,
            )
        }
    }

    override suspend fun updateDirectoryMetadata(
        currentCourse: String?,
        newCourse: String?,
        languages: LanguagePair,
    ) {
        withContext(ioDispatcher) {
            val normalizedNewCourse = sanitizeCoursePath(newCourse).takeIf { it.isNotBlank() }
            if (currentCourse != null) {
                mediaItemDao.updateCourseMetadata(
                    oldCourse = currentCourse,
                    newCourse = normalizedNewCourse,
                    targetLang = languages.target.value,
                    baseLang = languages.base?.value,
                )
                val prefix = "$currentCourse/"
                val childItems = mediaItemDao.observeAll().first()
                    .filter { it.course?.startsWith(prefix) == true }
                for (child in childItems) {
                    val suffix = child.course.orEmpty().removePrefix(prefix)
                    val updatedChildCourse = if (normalizedNewCourse != null) {
                        "$normalizedNewCourse/$suffix"
                    } else {
                        suffix
                    }
                    mediaItemDao.updateCourseAndLanguagePair(
                        id = child.id,
                        course = updatedChildCourse,
                        targetLang = languages.target.value,
                        baseLang = languages.base?.value,
                    )
                }
                val matchingFolders = courseFolderDao.getAll().filter {
                    it.coursePath == currentCourse || it.coursePath.startsWith(prefix)
                }
                for (folder in matchingFolders) {
                    val updatedPath = if (folder.coursePath == currentCourse) {
                        normalizedNewCourse
                    } else {
                        val suffix = folder.coursePath.removePrefix(prefix)
                        if (normalizedNewCourse != null) "$normalizedNewCourse/$suffix" else suffix
                    }
                    if (updatedPath != folder.coursePath) {
                        courseFolderDao.deleteByPath(folder.coursePath)
                    }
                    if (updatedPath != null) {
                        courseFolderDao.upsert(
                            folder.copy(
                                coursePath = updatedPath,
                                targetLang = languages.target.value,
                                baseLang = languages.base?.value,
                            ),
                        )
                        folder.treeUri?.let(Uri::parse)?.let { treeUri ->
                            val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                            if (rootDocId != null && folder.coursePath == currentCourse) {
                                writeCourseJsonToSafDir(treeUri, rootDocId, updatedPath, languages)
                            }
                        }
                    }
                }
            } else {
                mediaItemDao.updateUncategorizedCourseMetadata(
                    newCourse = normalizedNewCourse,
                    targetLang = languages.target.value,
                    baseLang = languages.base?.value,
                )
            }
        }
    }

    override suspend fun markItemListened(
        id: MediaId,
        isListened: Boolean,
        updatedAtEpochMs: Long,
    ) {
        withContext(ioDispatcher) {
            // 再生中に削除された教材には書かない（再生の終わりで聴取済みにしようとすることがある）
            val saved = itemStateDao.updateListenedStatus(
                mediaId = id.value,
                isListened = isListened,
                updatedAt = updatedAtEpochMs,
            )
            if (saved && isListened) {
                val course = mediaItemDao.getById(id.value)?.course
                scheduleCourseAnalysisUpToLimit(course)
            }
        }
    }

    override suspend fun toggleFavorite(id: MediaId) {
        withContext(ioDispatcher) {
            itemStateDao.toggleFavorite(id.value)
        }
    }

    override suspend fun toggleQueueItem(
        id: MediaId,
        queuedAtEpochMs: Long,
    ) {
        withContext(ioDispatcher) {
            itemStateDao.toggleQueue(
                mediaId = id.value,
                queuedAt = queuedAtEpochMs.coerceAtLeast(1L),
            )
        }
    }

    override suspend fun recordItemAccess(
        id: MediaId,
        accessedAtEpochMs: Long,
    ) {
        withContext(ioDispatcher) {
            val saved = itemStateDao.recordAccess(
                mediaId = id.value,
                accessedAt = accessedAtEpochMs,
            )
            if (saved) {
                val course = mediaItemDao.getById(id.value)?.course
                scheduleCourseAnalysisUpToLimit(course)
            }
        }
    }

    override suspend fun createPlaylist(
        name: String,
        initialMediaIds: List<MediaId>,
    ): PlaylistId? = withContext(ioDispatcher) {
        val trimmedName = sanitizeCourseName(name).ifBlank { return@withContext null }
        val existing = playlistDao.getPlaylists()
        val maxSortOrder = maxOf(
            existing.maxOfOrNull { it.sortOrder } ?: -1,
            PlaylistId.BUILT_IN_IDS.size - 1,
        )
        val playlistId = PlaylistId("user:${UUID.randomUUID()}")
        val now = clock()
        val entity = PlaylistEntity(
            id = playlistId.value,
            name = trimmedName,
            isBuiltIn = false,
            sortOrder = maxSortOrder + 1,
            createdAt = now,
        )
        playlistDao.upsertPlaylist(entity)
        if (initialMediaIds.isNotEmpty()) {
            playlistDao.addItemsToPlaylist(
                playlist = entity,
                mediaIds = initialMediaIds.map { it.value },
                addedAt = now,
            )
        }
        playlistId
    }

    override suspend fun deletePlaylist(id: PlaylistId) {
        if (id in PlaylistId.BUILT_IN_IDS) return
        withContext(ioDispatcher) {
            playlistDao.deleteUserPlaylist(id.value)
        }
    }

    override suspend fun addItemsToPlaylist(
        playlistId: PlaylistId,
        mediaIds: List<MediaId>,
    ) {
        if (mediaIds.isEmpty()) return
        withContext(ioDispatcher) {
            val existing = playlistDao.getPlaylistById(playlistId.value) ?: return@withContext
            playlistDao.addItemsToPlaylist(
                playlist = existing,
                mediaIds = mediaIds.map { it.value },
                addedAt = clock(),
            )
        }
    }

    override suspend fun removeItemFromPlaylist(
        playlistId: PlaylistId,
        mediaId: MediaId,
    ) {
        withContext(ioDispatcher) {
            playlistDao.deletePlaylistItem(playlistId.value, mediaId.value)
        }
    }

    override suspend fun reorderPlaylists(orderedIds: List<PlaylistId>) {
        if (orderedIds.isEmpty()) return
        withContext(ioDispatcher) {
            val existingById = playlistDao.getPlaylists().associateBy { it.id }
            val now = clock()
            val updatedEntities = orderedIds.distinct().mapIndexedNotNull { index, playlistId ->
                val existing = existingById[playlistId.value]
                when {
                    existing != null -> existing.copy(sortOrder = index)

                    playlistId in PlaylistId.BUILT_IN_IDS -> PlaylistEntity(
                        id = playlistId.value,
                        name = "",
                        isBuiltIn = true,
                        sortOrder = index,
                        createdAt = now,
                    )

                    else -> null
                }
            }
            if (updatedEntities.isNotEmpty()) {
                playlistDao.upsertPlaylists(updatedEntities)
            }
        }
    }

    override suspend fun reorderPlaylistItems(
        playlistId: PlaylistId,
        orderedMediaIds: List<MediaId>,
    ) {
        withContext(ioDispatcher) {
            val now = clock()
            val fallbackBuiltIn = if (playlistId in PlaylistId.BUILT_IN_IDS) {
                PlaylistEntity(
                    id = playlistId.value,
                    name = "",
                    isBuiltIn = true,
                    sortOrder = PlaylistId.BUILT_IN_IDS.indexOf(playlistId).coerceAtLeast(0),
                    createdAt = now,
                )
            } else {
                null
            }
            playlistDao.replacePlaylistItemsOrder(
                playlistId = playlistId.value,
                orderedMediaIds = orderedMediaIds.map { it.value },
                updatedAt = now,
                playlistIfMissing = fallbackBuiltIn,
            )
        }
    }

    override suspend fun reorderAllFiles(orderedMediaIds: List<MediaId>) {
        if (orderedMediaIds.isEmpty()) return
        withContext(ioDispatcher) {
            itemStateDao.updateFileSortOrders(orderedMediaIds.map { it.value })
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

            is DriveUploadResult.Failure -> Unit
        }
        true
    }

    override suspend fun hasLinkedSyncFolder(): Boolean = withContext(ioDispatcher) {
        loadLinkedSyncFolderConfig() != null
    }

    override fun syncFromLinkedFolder(
        treeUri: Uri?,
        course: String?,
        languages: LanguagePair?,
    ): Flow<SyncProgress> = flow {
        if (treeUri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            saveLinkedSyncFolderConfig(treeUri, course, languages)
        } else if (course != null || languages != null) {
            loadLinkedSyncFolderConfig()?.let { existing ->
                saveLinkedSyncFolderConfig(
                    treeUri = existing.treeUri,
                    course = course ?: existing.course,
                    languages = languages ?: existing.languages,
                )
            }
        }
        syncFromDrive().collect { emit(it) }
    }.flowOn(ioDispatcher)

    override fun syncFromDrive(): Flow<SyncProgress> = flow {
        emit(SyncProgress.Listing)
        val courses = when (val listResult = driveSyncClient.listChunkFlowCourses()) {
            is DriveListResult.Success -> listResult.courses

            is DriveListResult.Failure -> {
                if (listResult.error == DriveError.AUTH) {
                    val linkedConfig = loadLinkedSyncFolderConfig()
                    if (linkedConfig != null) {
                        syncWithSafTreeUri(linkedConfig)
                        return@flow
                    }
                }
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
                    val isRemoteOnly = existingEntity.storageState == AudioStorageState.DRIVE_REMOTE_ONLY.name
                    if ((existingFile != null && existingFile.exists()) || isRemoteOnly) {
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

        audioToDownload.sortWith { left, right ->
            naturalFileNameComparator.compare(left.second.name, right.second.name)
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
                    scheduleCourseAnalysisUpToLimit(courseName)
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
                        handleReattachedAudioAnalysis(outcome.mediaId, courseName)
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

    override suspend fun downloadDriveAudio(id: MediaId): Boolean = withContext(ioDispatcher) {
        val entity = mediaItemDao.getById(id.value) ?: return@withContext false
        val audioRecord = driveFileDao?.getByMediaId(id.value)
            ?.firstOrNull { it.kind == DriveFileKind.AUDIO.name }
            ?: return@withContext false

        val tempFile = mediaFileLocator.tempFileFor(UUID.randomUUID().toString())
        val dlResult = try {
            driveSyncClient.downloadToFile(
                fileId = audioRecord.driveFileId,
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
                return@withContext false
            }
        }

        if (!hasEnoughDiskSpace(dlSuccess.bytesWritten)) {
            tempFile.delete()
            return@withContext false
        }

        val inspected = try {
            audioFormatInspector.inspect(tempFile)
        } catch (ce: CancellationException) {
            tempFile.delete()
            throw ce
        } catch (_: Exception) {
            null
        }
        val ext = inspected?.extension ?: extensionForMimeType(entity.mimeType)
        val targetFile = mediaFileLocator.audioFileFor(id, ext)
        if (!moveOrCopyTempFile(tempFile, targetFile)) {
            tempFile.delete()
            return@withContext false
        }

        mediaItemDao.upsert(
            entity.copy(
                storageState = AudioStorageState.DRIVE_DOWNLOADED.name,
                localFilePath = null,
            ),
        )
        audioFileStateVersion.update { it + 1L }
        handleReattachedAudioAnalysis(id, entity.course)
        true
    }

    override suspend fun deleteLocalAudioCopy(id: MediaId): Boolean = withContext(ioDispatcher) {
        val entity = mediaItemDao.getById(id.value) ?: return@withContext false
        val hasDriveAudioRecord = driveFileDao?.getByMediaId(id.value)
            ?.any { it.kind == DriveFileKind.AUDIO.name } == true
        val isDriveItem = entity.storageState == AudioStorageState.DRIVE_DOWNLOADED.name ||
            entity.storageState == AudioStorageState.DRIVE_REMOTE_ONLY.name ||
            hasDriveAudioRecord
        if (!isDriveItem) {
            return@withContext false
        }

        val audioFile = mediaFileLocator.resolveAudioFile(id, entity.mimeType)
        if (audioFile != null && audioFile.exists()) {
            audioFile.delete()
        }
        scheduledAnalysisMediaIds.remove(id)
        mediaItemDao.upsert(
            entity.copy(
                storageState = AudioStorageState.DRIVE_REMOTE_ONLY.name,
                localFilePath = null,
            ),
        )
        audioFileStateVersion.update { it + 1L }
        true
    }

    override fun observeMissingAudioIds(): Flow<Set<MediaId>> = combine(
        mediaItemDao.observeAll(),
        audioFileStateVersion,
    ) { entities, _ ->
        entities.mapNotNullTo(linkedSetOf()) { entity ->
            val mediaId = MediaId(entity.id)
            val storageState = runCatching { AudioStorageState.valueOf(entity.storageState) }
                .getOrDefault(AudioStorageState.APP_MANAGED)
            when (storageState) {
                AudioStorageState.DRIVE_REMOTE_ONLY -> null

                AudioStorageState.LOCAL_REFERENCE -> {
                    if (isAudioSourceAvailable(entity)) null else mediaId
                }

                AudioStorageState.APP_MANAGED,
                AudioStorageState.DRIVE_DOWNLOADED,
                -> {
                    val file = mediaFileLocator.resolveAudioFile(mediaId, entity.mimeType)
                    if (file == null || !file.exists()) mediaId else null
                }
            }
        }
    }

    override suspend fun exportTranscriptText(
        id: MediaId,
        format: TranscriptExportFormat,
    ): String? = withContext(ioDispatcher) {
        val entity = mediaItemDao.getById(id.value)
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
        formatTranscriptExport(pairs, format, entity).takeIf { it.isNotBlank() }
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

    override suspend fun writeExportedTranscriptsToDirectory(
        ids: List<MediaId>,
        format: TranscriptExportFormat,
        treeUri: Uri,
    ): Int = withContext(ioDispatcher) {
        if (ids.isEmpty() || !isTreeUriSafe(treeUri)) {
            return@withContext 0
        }
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext 0
        val rootDocUri = runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId) }.getOrNull()
            ?: return@withContext 0
        val mimeType = TranscriptTextExporter.suggestedMimeType(format)

        var writtenCount = 0
        for (id in ids.distinct()) {
            currentCoroutineContext().ensureActive()
            val entity = mediaItemDao.getById(id.value) ?: continue
            val exportedText = exportTranscriptText(id, format) ?: continue
            val baseStem = sanitizeDisplayName(entity.title).ifBlank { entity.id }
            val fileName = TranscriptTextExporter.defaultFileName(baseStem, format)

            val wrote = try {
                val existingFileDocId = findChildDocumentIdByName(
                    treeUri = treeUri,
                    parentDocId = rootDocId,
                    targetName = fileName,
                    requireDirectory = false,
                )
                val targetDocUri = if (existingFileDocId != null) {
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, existingFileDocId)
                } else {
                    DocumentsContract.createDocument(
                        context.contentResolver,
                        rootDocUri,
                        mimeType,
                        fileName,
                    )
                } ?: continue
                val outputStream = context.contentResolver.openOutputStream(targetDocUri, "wt")
                    ?: context.contentResolver.openOutputStream(targetDocUri)
                    ?: continue
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
            } catch (_: IllegalArgumentException) {
                false
            }
            if (wrote) {
                writtenCount += 1
            }
        }
        writtenCount
    }

    override suspend fun delete(id: MediaId) {
        withContext(ioDispatcher) {
            val deletedCourse = mediaItemDao.getById(id.value)?.course
            scheduledAnalysisMediaIds.remove(id)
            // DB の CASCADE 削除（chunks / transcripts / item_state / drive_sync_records）を実行した上で実ファイルを消す
            mediaItemDao.deleteById(id.value)
            mediaFileLocator.deleteMediaFiles(id)
            audioFileStateVersion.update { it + 1L }
            scheduleCourseAnalysisUpToLimit(deletedCourse)
        }
    }

    @Suppress("UnusedParameter")
    private suspend fun handleReattachedAudioAnalysis(mediaId: MediaId, course: String?) {
        scheduledAnalysisMediaIds.remove(mediaId)
    }

    @Suppress("UnusedParameter")
    private suspend fun scheduleCourseAnalysisUpToLimit(course: String?) {
        // ライブラリへの追加・フォルダ取り込み段階では自動解析を開始せず、
        // 再生画面でファイルが選択された時点（prioritizeOpenedMediaAnalysis）で開始する。
    }

    private suspend fun importSingleFile(
        uri: Uri,
        meta: ContentMetadata?,
        course: String?,
        explicitCourse: String? = null,
        languages: LanguagePair? = null,
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
            val updatedCourse = if (explicitCourse != null) {
                course?.takeIf { it.isNotBlank() } ?: explicitCourse
            } else {
                existingEntity.course ?: course?.takeIf { it.isNotBlank() }
            }
            val updatedTargetLang = languages?.target?.value ?: existingEntity.targetLang
            val updatedBaseLang = if (languages != null) languages.base?.value else existingEntity.baseLang
            if (updatedCourse != existingEntity.course ||
                updatedTargetLang != existingEntity.targetLang ||
                updatedBaseLang != existingEntity.baseLang
            ) {
                mediaItemDao.updateCourseAndLanguagePair(
                    id = existingEntity.id,
                    course = updatedCourse,
                    targetLang = updatedTargetLang,
                    baseLang = updatedBaseLang,
                )
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
            val current = mediaItemDao.getById(existingEntity.id) ?: existingEntity
            mediaItemDao.upsert(
                current.copy(
                    storageState = AudioStorageState.DRIVE_DOWNLOADED.name,
                    localFilePath = null,
                ),
            )
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
        val effectiveLanguages = languages ?: settings.defaultLanguagePair
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
            targetLang = effectiveLanguages.target.value,
            baseLang = effectiveLanguages.base?.value,
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
            updatedAt = 0L,
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
        preserveEntityLanguages: Boolean = false,
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

        // JSON に言語ペアがあれば教材の言語ペアを更新する（ただし呼び出し元で言語ペアが明示指定された場合は維持する、§7.4.6）
        val languages = doc.languages
        if (languages != null && !preserveEntityLanguages) {
            mediaItemDao.updateLanguagePair(
                id = targetEntity.id,
                targetLang = languages.target.value,
                baseLang = languages.base?.value,
            )
        }

        // 使用するチャンク種別を IMPORTED に切り替える（§7.1 手順 7）
        // 取り込み時の文字起こし紐付けでは未アクセス状態（existing.updatedAt）を維持する
        val existingState = itemStateDao.getByMediaId(targetEntity.id)
        val preservedUpdatedAt = existingState?.updatedAt ?: 0L
        val updatedRows = itemStateDao.updateChunkSource(
            mediaId = targetEntity.id,
            chunkSource = ChunkOrigin.IMPORTED.name,
            updatedAt = preservedUpdatedAt,
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
                    updatedAt = preservedUpdatedAt,
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
    private data class DiscoveredDirectory(
        val docId: String,
        val coursePath: String,
    )

    private fun expandTreeUri(
        treeUri: Uri,
        rootCourseOverride: String? = null,
        discoveredDirs: MutableList<DiscoveredDirectory>? = null,
    ): List<ImportCandidate> {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return emptyList()
        val rootDocUri = runCatching { DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId) }.getOrNull()
        val rootMeta = rootDocUri?.let(::queryContentMetadata)
        val rootCourse = rootCourseOverride ?: sanitizeCourseName(
            rootMeta?.displayName ?: extractFolderNameFromDocId(rootDocId),
        ).takeIf { it.isNotBlank() }

        val results = mutableListOf<ImportCandidate>()
        collectTreeChildren(
            treeUri = treeUri,
            parentDocId = rootDocId,
            currentCourse = rootCourse,
            depth = 0,
            inMetadataDir = false,
            out = results,
            discoveredDirs = discoveredDirs,
        )
        return results
    }

    private fun collectTreeChildren(
        treeUri: Uri,
        parentDocId: String,
        currentCourse: String?,
        depth: Int,
        inMetadataDir: Boolean,
        out: MutableList<ImportCandidate>,
        discoveredDirs: MutableList<DiscoveredDirectory>?,
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
                        val isMetadataDir = displayName?.equals(TRANSCRIPTION_DIR_NAME, ignoreCase = true) == true ||
                            displayName?.equals(CHUNKFLOW_DIR_NAME, ignoreCase = true) == true
                        val childCourse = if (isMetadataDir) {
                            currentCourse
                        } else {
                            val childSegment = sanitizeCourseName(displayName).takeIf { it.isNotBlank() }
                            when {
                                childSegment == null -> currentCourse
                                currentCourse.isNullOrBlank() -> childSegment
                                else -> "$currentCourse/$childSegment"
                            }
                        }
                        if (!isMetadataDir && !childCourse.isNullOrBlank()) {
                            discoveredDirs?.add(DiscoveredDirectory(docId = docId, coursePath = childCourse))
                        }
                        collectTreeChildren(
                            treeUri = treeUri,
                            parentDocId = docId,
                            currentCourse = childCourse,
                            depth = depth + 1,
                            inMetadataDir = inMetadataDir || isMetadataDir,
                            out = out,
                            discoveredDirs = discoveredDirs,
                        )
                    }
                } else {
                    if (inMetadataDir && displayName?.equals(COURSE_JSON_FILE_NAME, ignoreCase = true) == true) {
                        continue
                    }
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

    /**
     * 純粋なフォルダ（Tree URI）であるかを判定する。
     *
     * `DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)` で生成された個別ファイルの Document URI
     * （`/tree/<rootDocId>/document/<docId>`）に対しても Android の `DocumentsContract.isTreeUri` は `true` を返すため、
     * Document URI を除外しないと 1 ファイルごとに親フォルダ全体が再展開され N×N 件（例: 30×30 = 900 件）の重複が発生する。
     */
    private fun isTreeUriSafe(uri: Uri): Boolean = runCatching {
        val segments = uri.pathSegments
        val isDocumentInTree = segments.size >= 4 &&
            segments[0] == "tree" &&
            segments[2] == "document"
        !isDocumentInTree &&
            !DocumentsContract.isDocumentUri(context, uri) &&
            DocumentsContract.isTreeUri(uri)
    }.getOrDefault(false)

    private fun extractCourseFromDocumentUri(
        uri: Uri,
        explicitRootCourse: String? = null,
    ): String? {
        val docId = runCatching {
            DocumentsContract.getDocumentId(uri)
        }.getOrNull()
        val treeDocId = runCatching {
            DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull()

        if (treeDocId != null && docId != null && docId.startsWith(treeDocId)) {
            val rootCourse = explicitRootCourse
                ?: sanitizeCourseName(extractFolderNameFromDocId(treeDocId)).takeIf { it.isNotBlank() }
            val relativePath = docId.removePrefix(treeDocId).trim('/')
            val relSegments = relativePath.split('/').filter { it.isNotBlank() }
            val subDirSegments = if (relSegments.size >= 2) relSegments.dropLast(1) else emptyList()
            val cleanSubDirs = subDirSegments
                .filterNot {
                    it.equals(TRANSCRIPTION_DIR_NAME, ignoreCase = true) ||
                        it.equals(CHUNKFLOW_DIR_NAME, ignoreCase = true)
                }
                .mapNotNull { seg -> sanitizeCourseName(seg).takeIf { it.isNotBlank() } }
            return (listOfNotNull(rootCourse) + cleanSubDirs)
                .joinToString("/")
                .takeIf { it.isNotBlank() }
        }

        if (explicitRootCourse != null) return explicitRootCourse
        if (docId == null) return null

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
            storageState = AudioStorageState.DRIVE_DOWNLOADED.name,
        )
        val initialState = ItemStateEntity(
            mediaId = newMediaId.value,
            positionMs = 0L,
            speed = settings.defaultSpeed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED),
            updatedAt = 0L,
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
        entity: MediaItemEntity? = null,
    ): String {
        val chunks = pairs.map { it.first }
        val transcripts = pairs.associate { (chunk, transcript) -> chunk.id to transcript }
        return when (format) {
            TranscriptExportFormat.JSON -> {
                val targetLang = entity?.targetLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                val baseLang = entity?.baseLang?.takeIf { it.isNotBlank() }?.let(::LanguageTag)
                val languages = if (targetLang != null) {
                    LanguagePair(target = targetLang, base = baseLang)
                } else {
                    null
                }
                val fileName = entity?.let {
                    val base = sanitizeDisplayName(it.title).ifBlank { it.id }
                    "$base.${extensionForMimeType(it.mimeType)}"
                }
                TranscriptTextExporter.exportJson(
                    chunks = chunks,
                    transcripts = transcripts,
                    sha256 = entity?.sha256,
                    fileName = fileName,
                    durationMs = entity?.durationMs,
                    languages = languages,
                )
            }

            TranscriptExportFormat.SRT,
            TranscriptExportFormat.LRC,
            TranscriptExportFormat.TSV,
            -> TranscriptTextExporter.export(chunks, transcripts, format).trimEnd()
        }
    }

    private data class LinkedSyncFolderConfig(
        val treeUri: Uri,
        val course: String?,
        val languages: LanguagePair?,
    )

    private val linkedSyncConfigFile: File
        get() = File(context.filesDir, LINKED_SYNC_PROPERTIES_FILE_NAME)

    private fun saveLinkedSyncFolderConfig(
        treeUri: Uri,
        course: String?,
        languages: LanguagePair?,
    ) {
        runCatching {
            val props = Properties()
            props.setProperty("treeUri", treeUri.toString())
            sanitizeCourseName(course).takeIf { it.isNotBlank() }?.let {
                props.setProperty("course", it)
            }
            if (languages != null) {
                props.setProperty("targetLang", languages.target.value)
                languages.base?.value?.let { props.setProperty("baseLang", it) }
            }
            linkedSyncConfigFile.parentFile?.mkdirs()
            FileOutputStream(linkedSyncConfigFile).use { out ->
                props.store(out, "ChunkFlow Linked Sync Folder")
            }
        }
    }

    @SuppressLint("UseKtx")
    private fun loadLinkedSyncFolderConfig(): LinkedSyncFolderConfig? = runCatching {
        val file = linkedSyncConfigFile
        if (!file.isFile) return null
        val props = Properties()
        FileInputStream(file).use { input ->
            props.load(input)
        }
        val treeUriStr = props.getProperty("treeUri")?.takeIf { it.isNotBlank() } ?: return null
        val treeUri = Uri.parse(treeUriStr)
        if (treeUri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val course = sanitizeCourseName(props.getProperty("course")).takeIf { it.isNotBlank() }
        val targetLang = props.getProperty("targetLang")?.takeIf { it.isNotBlank() }
        val baseLang = props.getProperty("baseLang")?.takeIf { it.isNotBlank() }
        val languages = if (targetLang != null) {
            runCatching {
                LanguagePair(
                    target = LanguageTag(targetLang),
                    base = baseLang?.let(::LanguageTag),
                )
            }.getOrNull()
        } else {
            null
        }
        LinkedSyncFolderConfig(
            treeUri = treeUri,
            course = course,
            languages = languages,
        )
    }.getOrNull()

    private suspend fun FlowCollector<SyncProgress>.syncWithSafTreeUri(
        config: LinkedSyncFolderConfig,
    ) {
        val explicitCourse = sanitizeCoursePath(config.course).takeIf { it.isNotBlank() }
        val candidates = if (isTreeUriSafe(config.treeUri)) {
            expandTreeUri(config.treeUri, rootCourseOverride = explicitCourse)
        } else {
            emptyList()
        }

        val (transcriptCandidates, rawAudioCandidates) = candidates.partition(::isTranscriptCandidate)
        val audioCandidates = rawAudioCandidates.sortedWith { left, right ->
            naturalFileNameComparator.compare(
                left.meta?.displayName ?: left.uri.lastPathSegment.orEmpty(),
                right.meta?.displayName ?: right.uri.lastPathSegment.orEmpty(),
            )
        }
        val totalToProcess = audioCandidates.size + transcriptCandidates.size
        var doneCount = 0
        var newAudioCount = 0
        var newTranscriptCount = 0
        val batchAudioByStemAndCourse = LinkedHashMap<Pair<String?, String>, MediaId>()
        val batchAudioByStem = LinkedHashMap<String, MediaId>()
        val syncedTranscriptMediaIds = mutableSetOf<String>()

        if (totalToProcess > 0) {
            emit(SyncProgress.Downloading(done = 0, total = totalToProcess))
        }

        // 1. 音声ファイルの取り込み（読み込めないファイルは無視してスキップ）
        for ((index, candidate) in audioCandidates.withIndex()) {
            currentCoroutineContext().ensureActive()
            val uri = candidate.uri
            val meta = candidate.meta
            val declaredSize = meta?.sizeBytes?.takeIf { it > 0L }
            if (declaredSize != null) {
                if (declaredSize > MAX_AUDIO_SIZE_BYTES) {
                    doneCount += 1
                    emit(SyncProgress.Downloading(done = doneCount, total = totalToProcess))
                    continue
                }
                if (!hasEnoughDiskSpace(declaredSize)) {
                    emit(SyncProgress.Failed(SyncError.NO_SPACE))
                    return
                }
            }

            val tempFile = mediaFileLocator.tempFileFor(UUID.randomUUID().toString())
            val outcome = try {
                importSingleFile(
                    uri = uri,
                    meta = meta,
                    course = candidate.course,
                    explicitCourse = explicitCourse,
                    languages = config.languages,
                    tempFile = tempFile,
                    fileIndex = index,
                    totalFiles = audioCandidates.size.coerceAtLeast(1),
                    onProgress = {},
                )
            } catch (ce: CancellationException) {
                tempFile.delete()
                throw ce
            }

            val resolvedMediaId = when (outcome) {
                is SingleImportOutcome.Imported -> {
                    newAudioCount += 1
                    scheduleCourseAnalysisUpToLimit(candidate.course)
                    outcome.mediaId
                }

                is SingleImportOutcome.Duplicate -> {
                    if (outcome.reattachedAudioFile) {
                        newAudioCount += 1
                        handleReattachedAudioAnalysis(outcome.mediaId, candidate.course)
                    }
                    outcome.mediaId
                }

                is SingleImportOutcome.Failed -> {
                    if (outcome.error == ImportError.NO_SPACE) {
                        emit(SyncProgress.Failed(SyncError.NO_SPACE))
                        return
                    }
                    doneCount += 1
                    emit(SyncProgress.Downloading(done = doneCount, total = totalToProcess))
                    continue
                }
            }

            val stem = extractFileStem(meta?.displayName ?: uri.lastPathSegment.orEmpty())
            if (stem.isNotEmpty()) {
                batchAudioByStemAndCourse[candidate.course to stem] = resolvedMediaId
                batchAudioByStem[stem] = resolvedMediaId
            }

            doneCount += 1
            emit(SyncProgress.Downloading(done = doneCount, total = totalToProcess))
        }

        // 2. 文字起こしファイルの照合と反映
        for (candidate in transcriptCandidates) {
            currentCoroutineContext().ensureActive()
            val parseOutcome = readAndParseTranscript(candidate.uri, candidate.meta)
            val parsed = (parseOutcome as? InternalTranscriptParseOutcome.Ok)?.parsed
            if (parsed == null) {
                doneCount += 1
                emit(SyncProgress.Downloading(done = doneCount, total = totalToProcess))
                continue
            }

            val targetEntity = findMatchingMediaItem(
                doc = parsed.document,
                transcriptFileName = parsed.fileName,
                course = candidate.course,
                batchByStemAndCourse = batchAudioByStemAndCourse,
                batchByStem = batchAudioByStem,
            )
            if (targetEntity == null) {
                doneCount += 1
                emit(SyncProgress.Downloading(done = doneCount, total = totalToProcess))
                continue
            }

            val ext = transcriptExtensionForFileName(parsed.fileName)
            val existingTranscriptFile = mediaFileLocator.transcriptFileFor(MediaId(targetEntity.id), ext)
            val alreadySameContent = existingTranscriptFile.exists() &&
                runCatching { existingTranscriptFile.readBytes().contentEquals(parsed.rawBytes) }.getOrDefault(false)
            if (!alreadySameContent) {
                val latestEntity = mediaItemDao.getById(targetEntity.id) ?: targetEntity
                if (
                    attachTranscriptToMediaItem(
                        targetEntity = latestEntity,
                        parsed = parsed,
                        preserveEntityLanguages = config.languages != null,
                    ) != null
                ) {
                    newTranscriptCount += 1
                    syncedTranscriptMediaIds += latestEntity.id
                }
            } else {
                syncedTranscriptMediaIds += targetEntity.id
            }

            doneCount += 1
            emit(SyncProgress.Downloading(done = doneCount, total = totalToProcess))
        }

        // 3. ローカルで生成・編集された .chunkflow.json の SAF リンクフォルダ transcription/ への書き戻し
        var uploadedTranscripts = 0
        val allLocalItems = mediaItemDao.observeAll().first()
        for (item in allLocalItems) {
            currentCoroutineContext().ensureActive()
            if (item.id in syncedTranscriptMediaIds) continue
            if (explicitCourse != null && item.course != null && item.course != explicitCourse) continue
            if (uploadLocalTranscriptToSafTreeIfNeeded(config.treeUri, item)) {
                uploadedTranscripts += 1
            }
        }

        emit(
            SyncProgress.Done(
                newAudio = newAudioCount,
                newTranscripts = newTranscriptCount,
                failed = 0,
                uploadedTranscripts = uploadedTranscripts,
            ),
        )
    }

    private suspend fun uploadLocalTranscriptToSafTreeIfNeeded(
        treeUri: Uri,
        item: MediaItemEntity,
    ): Boolean {
        val mediaId = MediaId(item.id)
        val existingTranscriptRecords = driveFileDao?.getByMediaId(item.id)
            .orEmpty()
            .filter { it.kind == DriveFileKind.TRANSCRIPT.name }
        val latestSyncedMs = existingTranscriptRecords.maxOfOrNull { it.modifiedTime }

        val localJsonFile = mediaFileLocator.transcriptFileFor(mediaId, "chunkflow.json")
        val pairs = loadActiveChunkTranscriptPairs(item.id)
        if (pairs.isEmpty() && !localJsonFile.exists()) {
            return false
        }

        val hasNewerUserEdit = latestSyncedMs != null &&
            pairs.any { (_, transcript) -> transcript.userEdited && transcript.createdAt > latestSyncedMs }
        val hasNewerLocalFile = latestSyncedMs != null &&
            localJsonFile.exists() &&
            localJsonFile.lastModified() > latestSyncedMs
        val shouldUpload = latestSyncedMs == null || hasNewerUserEdit || hasNewerLocalFile
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
        if (!writeTranscriptJsonToSafTree(treeUri, uploadFileName, jsonBytes)) {
            return false
        }

        val recordedModifiedMs = max(clock(), localJsonFile.lastModified())
        driveFileDao?.upsert(
            DriveFileEntity(
                driveFileId = "saf-transcript:${item.id}",
                kind = DriveFileKind.TRANSCRIPT.name,
                course = item.course.orEmpty(),
                modifiedTime = recordedModifiedMs,
                sha256 = null,
                mediaId = item.id,
            ),
        )
        return true
    }

    private fun writeTranscriptJsonToSafTree(
        treeUri: Uri,
        fileName: String,
        jsonBytes: ByteArray,
        preferChunkFlowDir: Boolean = false,
    ): Boolean = runCatching {
        if (!isTreeUriSafe(treeUri)) return false
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri) ?: return false
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId) ?: return false

        val dirName = if (preferChunkFlowDir) CHUNKFLOW_DIR_NAME else TRANSCRIPTION_DIR_NAME
        val transcriptionDocId = findChildDocumentIdByName(
            treeUri = treeUri,
            parentDocId = rootDocId,
            targetName = dirName,
            requireDirectory = true,
        ) ?: run {
            val createdDirUri = DocumentsContract.createDocument(
                context.contentResolver,
                rootDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                dirName,
            ) ?: return false
            DocumentsContract.getDocumentId(createdDirUri)
        }

        val existingFileDocId = findChildDocumentIdByName(
            treeUri = treeUri,
            parentDocId = transcriptionDocId,
            targetName = fileName,
            requireDirectory = false,
        )
        val targetDocUri = if (existingFileDocId != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existingFileDocId)
        } else {
            val transcriptionDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, transcriptionDocId)
            DocumentsContract.createDocument(
                context.contentResolver,
                transcriptionDirUri,
                "application/json",
                fileName,
            )
        } ?: return false

        val outStream = context.contentResolver.openOutputStream(targetDocUri, "wt")
            ?: context.contentResolver.openOutputStream(targetDocUri)
            ?: return false
        outStream.use { out ->
            out.write(jsonBytes)
            out.flush()
        }
        true
    }.getOrDefault(false)

    private fun deleteTranscriptJsonFromSafTree(
        treeUri: Uri,
        fileName: String,
    ): Boolean = runCatching {
        if (!isTreeUriSafe(treeUri)) return false
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri) ?: return false
        val transcriptionDocId = findChildDocumentIdByName(
            treeUri = treeUri,
            parentDocId = rootDocId,
            targetName = TRANSCRIPTION_DIR_NAME,
            requireDirectory = true,
        ) ?: return false
        val existingFileDocId = findChildDocumentIdByName(
            treeUri = treeUri,
            parentDocId = transcriptionDocId,
            targetName = fileName,
            requireDirectory = false,
        ) ?: return false
        val targetDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, existingFileDocId)
            ?: return false
        DocumentsContract.deleteDocument(context.contentResolver, targetDocUri)
    }.getOrDefault(false)

    private fun findChildDocumentIdByName(
        treeUri: Uri,
        parentDocId: String,
        targetName: String,
        requireDirectory: Boolean,
    ): String? = runCatching {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val docId = if (idIdx >= 0 && !c.isNull(idIdx)) c.getString(idIdx) else continue
                val name = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else continue
                val mime = if (mimeIdx >= 0 && !c.isNull(mimeIdx)) c.getString(mimeIdx) else null
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (isDir == requireDirectory && name.equals(targetName, ignoreCase = requireDirectory)) {
                    return docId
                }
            }
        }
        null
    }.getOrNull()

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

    private suspend fun linkSingleLocalAudioFile(
        uri: Uri,
        meta: ContentMetadata?,
        course: String,
        languages: LanguagePair,
        fileIndex: Int,
        totalFiles: Int,
        onProgress: suspend (Float?) -> Unit,
    ): SingleImportOutcome {
        val localFilePath = resolveLocalFilePathFromUri(uri)
        val directFile = localFilePath?.let(::File)?.takeIf { it.isFile && it.canRead() }
        val tempFile = if (directFile == null) {
            mediaFileLocator.tempFileFor("link-${UUID.randomUUID()}")
        } else {
            null
        }

        try {
            val declaredSize = meta?.sizeBytes?.takeIf { it > 0L }
            val copyOutcome = if (directFile != null) {
                digestFileOnly(
                    file = directFile,
                    declaredSize = declaredSize,
                    fileIndex = fileIndex,
                    totalFiles = totalFiles,
                    onProgress = onProgress,
                )
            } else {
                copyAndDigest(
                    uri = uri,
                    destFile = requireNotNull(tempFile),
                    declaredSize = declaredSize,
                    fileIndex = fileIndex,
                    totalFiles = totalFiles,
                    onProgress = onProgress,
                )
            }

            val actualSizeBytes = when (copyOutcome) {
                is CopyOutcome.Success -> copyOutcome.bytesWritten
                is CopyOutcome.Failure -> return SingleImportOutcome.Failed(copyOutcome.error)
            }
            val sha256 = copyOutcome.sha256Hex
            val fileToInspect = directFile ?: requireNotNull(tempFile)

            val inspected = try {
                audioFormatInspector.inspect(fileToInspect)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
            if (inspected == null || inspected.durationMs <= 0L) {
                return SingleImportOutcome.Failed(ImportError.UNSUPPORTED_FORMAT)
            }

            val existingEntity = mediaItemDao.getBySha256(sha256)
            if (existingEntity != null) {
                val wasMissing = !isAudioSourceAvailable(existingEntity)
                mediaItemDao.upsert(
                    existingEntity.copy(
                        course = course,
                        targetLang = languages.target.value,
                        baseLang = languages.base?.value,
                        storageState = AudioStorageState.LOCAL_REFERENCE.name,
                        sourceUri = uri.toString(),
                        localFilePath = localFilePath,
                    ),
                )
                audioFileStateVersion.update { it + 1L }
                return SingleImportOutcome.Duplicate(
                    mediaId = MediaId(existingEntity.id),
                    reattachedAudioFile = wasMissing,
                )
            }

            val newMediaId = MediaId(UUID.randomUUID().toString())
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
                targetLang = languages.target.value,
                baseLang = languages.base?.value,
                transcriptOffsetMs = initialOffsetMs,
                importedAt = nowMs,
                mp3HasHeader = mp3Info?.hasHeader ?: false,
                mp3SamplesPerFrame = mp3Info?.samplesPerFrame ?: Mp3HeaderInfo.SAMPLES_PER_FRAME_MPEG1,
                mp3EncoderDelay = mp3Info?.encoderDelay ?: Mp3HeaderInfo.DEFAULT_LAME_ENCODER_DELAY,
                storageState = AudioStorageState.LOCAL_REFERENCE.name,
                sourceUri = uri.toString(),
                localFilePath = localFilePath,
            )
            val initialState = ItemStateEntity(
                mediaId = newMediaId.value,
                positionMs = 0L,
                speed = settings.defaultSpeed.coerceIn(ItemState.MIN_SPEED, ItemState.MAX_SPEED),
                updatedAt = 0L,
            )
            mediaItemDao.upsert(entity)
            itemStateDao.upsert(initialState)
            audioFileStateVersion.update { it + 1L }
            return SingleImportOutcome.Imported(newMediaId)
        } finally {
            tempFile?.delete()
        }
    }

    private suspend fun digestFileOnly(
        file: File,
        declaredSize: Long?,
        fileIndex: Int,
        totalFiles: Int,
        onProgress: suspend (Float?) -> Unit,
    ): CopyOutcome {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        return try {
            FileInputStream(file).use { input ->
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
                    if (declaredSize != null && declaredSize > 0L) {
                        val fileFraction = (totalBytes.toFloat() / declaredSize.toFloat()).coerceIn(0f, 1f)
                        val overall = (fileIndex.toFloat() + fileFraction) / totalFiles.toFloat()
                        onProgress(overall.coerceIn(0f, 1f))
                    }
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
        } catch (_: Exception) {
            CopyOutcome.Failure(ImportError.IO)
        }
    }

    private fun isAudioSourceAvailable(entity: MediaItemEntity): Boolean {
        val mediaId = MediaId(entity.id)
        val storageState = runCatching { AudioStorageState.valueOf(entity.storageState) }
            .getOrDefault(AudioStorageState.APP_MANAGED)
        return when (storageState) {
            AudioStorageState.DRIVE_REMOTE_ONLY -> false

            AudioStorageState.LOCAL_REFERENCE -> {
                val copiedFile = mediaFileLocator.resolveAudioFile(mediaId, entity.mimeType)
                if (copiedFile != null && copiedFile.isFile) return true
                val localPath = entity.localFilePath?.takeIf { it.isNotBlank() }
                if (localPath != null && File(localPath).isFile) return true
                val sourceUriStr = entity.sourceUri?.takeIf { it.isNotBlank() } ?: return false
                val uri = runCatching { Uri.parse(sourceUriStr) }.getOrNull() ?: return false
                isSourceUriAccessible(uri)
            }

            AudioStorageState.APP_MANAGED,
            AudioStorageState.DRIVE_DOWNLOADED,
            -> mediaFileLocator.resolveAudioFile(mediaId, entity.mimeType)?.isFile == true
        }
    }

    private fun isSourceUriAccessible(uri: Uri): Boolean = runCatching {
        when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> uri.path?.let(::File)?.isFile == true

            ContentResolver.SCHEME_CONTENT -> {
                if (queryContentMetadata(uri) != null) {
                    true
                } else {
                    context.contentResolver.openInputStream(uri)?.use { true } ?: false
                }
            }

            else -> false
        }
    }.getOrDefault(false)

    private fun resolveLocalFilePathFromUri(uri: Uri): String? = runCatching {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.path
        }
        val docId = DocumentsContract.getDocumentId(uri) ?: return null
        if (docId.startsWith("raw:")) {
            return docId.removePrefix("raw:").takeIf { it.isNotBlank() }
        }
        if (uri.authority == "com.android.externalstorage.documents" && docId.startsWith("primary:")) {
            val rel = docId.removePrefix("primary:").trimStart('/')
            if (rel.isNotBlank()) {
                val candidate = File("/storage/emulated/0", rel)
                if (candidate.exists()) return candidate.absolutePath
            }
        }
        null
    }.getOrNull()

    private fun takePersistableFolderPermission(treeUri: Uri) {
        val resolver = context.contentResolver
        val readWriteFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (runCatching { resolver.takePersistableUriPermission(treeUri, readWriteFlags) }.isFailure) {
            runCatching {
                resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    private data class CourseJsonMeta(
        val coursePath: String?,
        val languages: LanguagePair?,
    )

    private fun readCourseJsonFromSafDir(
        treeUri: Uri,
        dirDocId: String,
    ): CourseJsonMeta? = runCatching {
        val chunkflowDirDocId = findChildDocumentIdByName(
            treeUri = treeUri,
            parentDocId = dirDocId,
            targetName = CHUNKFLOW_DIR_NAME,
            requireDirectory = true,
        ) ?: return null
        val courseJsonDocId = findChildDocumentIdByName(
            treeUri = treeUri,
            parentDocId = chunkflowDirDocId,
            targetName = COURSE_JSON_FILE_NAME,
            requireDirectory = false,
        ) ?: return null
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, courseJsonDocId) ?: return null
        val bytes = context.contentResolver.openInputStream(docUri)?.use { it.readBytes() } ?: return null
        val text = bytes.decodeToString()
        val courseName = COURSE_NAME_JSON_REGEX.find(text)?.groupValues?.getOrNull(1)
            ?.let(::sanitizeCoursePath)
            ?.takeIf { it.isNotBlank() }
        val target = TARGET_LANG_JSON_REGEX.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val base = BASE_LANG_JSON_REGEX.find(text)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        val languages = target?.let { t ->
            runCatching { LanguagePair(target = LanguageTag(t), base = base?.let(::LanguageTag)) }.getOrNull()
        }
        if (courseName == null && languages == null) null else CourseJsonMeta(courseName, languages)
    }.getOrNull()

    private fun writeCourseJsonToSafDir(
        treeUri: Uri,
        dirDocId: String,
        coursePath: String,
        languages: LanguagePair,
    ): Boolean = runCatching {
        if (!isTreeUriSafe(treeUri)) return false
        val dirDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, dirDocId) ?: return false
        val chunkflowDirDocId = findChildDocumentIdByName(
            treeUri = treeUri,
            parentDocId = dirDocId,
            targetName = CHUNKFLOW_DIR_NAME,
            requireDirectory = true,
        ) ?: run {
            val createdDirUri = DocumentsContract.createDocument(
                context.contentResolver,
                dirDocUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                CHUNKFLOW_DIR_NAME,
            ) ?: return false
            DocumentsContract.getDocumentId(createdDirUri)
        }

        val existingFileDocId = findChildDocumentIdByName(
            treeUri = treeUri,
            parentDocId = chunkflowDirDocId,
            targetName = COURSE_JSON_FILE_NAME,
            requireDirectory = false,
        )
        val targetDocUri = if (existingFileDocId != null) {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, existingFileDocId)
        } else {
            val chunkflowDirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, chunkflowDirDocId)
            DocumentsContract.createDocument(
                context.contentResolver,
                chunkflowDirUri,
                "application/json",
                COURSE_JSON_FILE_NAME,
            )
        } ?: return false

        val baseField = languages.base?.value?.let { ",\"baseLang\":${jsonString(it)}" }.orEmpty()
        val json = "{\"courseName\":${jsonString(coursePath)},\"targetLang\":${jsonString(languages.target.value)}$baseField}"
        val outStream = context.contentResolver.openOutputStream(targetDocUri, "wt")
            ?: context.contentResolver.openOutputStream(targetDocUri)
            ?: return false
        outStream.use { out ->
            out.write(json.toByteArray(Charsets.UTF_8))
            out.flush()
        }
        true
    }.getOrDefault(false)

    private object NoOpCourseFolderDao : CourseFolderDao {
        override fun observeAll(): Flow<List<CourseFolderEntity>> = flowOf(emptyList())
        override suspend fun getAll(): List<CourseFolderEntity> = emptyList()
        override suspend fun getByPath(coursePath: String): CourseFolderEntity? = null
        override suspend fun getByTreeUri(treeUri: String): CourseFolderEntity? = null
        override suspend fun upsert(folder: CourseFolderEntity) = Unit
        override suspend fun deleteByPath(coursePath: String) = Unit
    }

    private object NoOpPlaylistDao : PlaylistDao {
        override fun observePlaylists(): Flow<List<PlaylistEntity>> = flowOf(emptyList())
        override fun observeAllPlaylistItems(): Flow<List<PlaylistItemEntity>> = flowOf(emptyList())
        override suspend fun getPlaylists(): List<PlaylistEntity> = emptyList()
        override suspend fun getPlaylistById(playlistId: String): PlaylistEntity? = null
        override suspend fun getItemsByPlaylistId(playlistId: String): List<PlaylistItemEntity> = emptyList()
        override suspend fun upsertPlaylist(playlist: PlaylistEntity) = Unit
        override suspend fun upsertPlaylists(playlists: List<PlaylistEntity>) = Unit
        override suspend fun upsertPlaylistItems(items: List<PlaylistItemEntity>) = Unit
        override suspend fun deleteUserPlaylist(playlistId: String): Int = 0
        override suspend fun deletePlaylistItem(playlistId: String, mediaId: String): Int = 0
        override suspend fun deleteAllItemsInPlaylist(playlistId: String) = Unit
        override suspend fun mediaItemExists(mediaId: String): Boolean = false
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
        private const val DEFAULT_IMPORTED_COURSE_NAME: String = "インポート済み"
        private const val TRANSCRIPTION_DIR_NAME: String = "transcription"
        private const val CHUNKFLOW_DIR_NAME: String = ".chunkflow"
        private const val COURSE_JSON_FILE_NAME: String = "course.json"
        private const val LINKED_SYNC_PROPERTIES_FILE_NAME: String = "drive_sync_link.properties"
        private const val MAX_TREE_DEPTH: Int = 6
        private const val MAX_EXPORT_SEGMENT_CHARS: Int = 2_000
        private val COURSE_NAME_JSON_REGEX = Regex(""""courseName"\s*:\s*"([^"]+)"""")
        private val TARGET_LANG_JSON_REGEX = Regex(""""targetLang"\s*:\s*"([^"]+)"""")
        private val BASE_LANG_JSON_REGEX = Regex(""""baseLang"\s*:\s*"([^"]+)"""")

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
                rawName.endsWith(".lrc") ||
                rawName.endsWith(".tsv")
            ) {
                return true
            }
            val mime = candidate.meta?.mimeType?.lowercase()
            return (mime == "application/json" && rawName.endsWith(".json")) ||
                mime == "text/tab-separated-values"
        }

        private fun transcriptExtensionForFileName(fileName: String): String {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".chunkflow.json") -> "chunkflow.json"
                lower.endsWith(".srt") -> "srt"
                lower.endsWith(".vtt") -> "vtt"
                lower.endsWith(".lrc") -> "lrc"
                lower.endsWith(".tsv") -> "tsv"
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
                Regex("\\.(chunkflow\\.json|json|srt|vtt|lrc|tsv)$", RegexOption.IGNORE_CASE),
                "",
            )
            return sanitizeDisplayName(withoutTranscriptExt).lowercase()
        }

        internal val naturalFileNameComparator: Comparator<String> = Comparator(::compareFileNamesNaturally)

        internal fun compareFileNamesNaturally(a: String, b: String): Int {
            var i = 0
            var j = 0
            val lenA = a.length
            val lenB = b.length
            while (i < lenA && j < lenB) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    val startA = i
                    while (i < lenA && a[i].isDigit()) i++
                    val startB = j
                    while (j < lenB && b[j].isDigit()) j++

                    val rawDigitsA = a.substring(startA, i)
                    val rawDigitsB = b.substring(startB, j)
                    val trimmedA = rawDigitsA.trimStart('0')
                    val trimmedB = rawDigitsB.trimStart('0')

                    val lenDiff = trimmedA.length.compareTo(trimmedB.length)
                    if (lenDiff != 0) return lenDiff

                    val valDiff = trimmedA.compareTo(trimmedB)
                    if (valDiff != 0) return valDiff

                    val rawLenDiff = rawDigitsA.length.compareTo(rawDigitsB.length)
                    if (rawLenDiff != 0) return rawLenDiff
                } else {
                    val lowerDiff = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                    if (lowerDiff != 0) return lowerDiff
                    i++
                    j++
                }
            }
            val remainingDiff = (lenA - i).compareTo(lenB - j)
            if (remainingDiff != 0) return remainingDiff
            return a.compareTo(b)
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

        internal fun sanitizeCoursePath(rawCourse: String?): String {
            if (rawCourse.isNullOrBlank()) return ""
            return rawCourse
                .replace('\\', '/')
                .split('/')
                .map { segment -> sanitizeCourseName(segment) }
                .filter { it.isNotBlank() && it != "." && it != ".." }
                .joinToString("/")
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
