package com.miuraken.chunkflow.core.data.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.miuraken.chunkflow.core.data.analysis.AnalysisRepository
import com.miuraken.chunkflow.core.database.dao.ChunkDao
import com.miuraken.chunkflow.core.database.dao.CourseFolderDao
import com.miuraken.chunkflow.core.database.dao.DriveFileDao
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.CourseFolderEntity
import com.miuraken.chunkflow.core.database.entity.DriveFileEntity
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.TranscriptEntity
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.drive.DriveBytesDownloadResult
import com.miuraken.chunkflow.core.drive.DriveCourseFiles
import com.miuraken.chunkflow.core.drive.DriveDownloadResult
import com.miuraken.chunkflow.core.drive.DriveError
import com.miuraken.chunkflow.core.drive.DriveListResult
import com.miuraken.chunkflow.core.drive.DriveRemoteFile
import com.miuraken.chunkflow.core.drive.DriveSyncClient
import com.miuraken.chunkflow.core.drive.DriveUploadResult
import com.miuraken.chunkflow.core.model.AnalysisStage
import com.miuraken.chunkflow.core.model.AudioStorageState
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.DriveFileKind
import com.miuraken.chunkflow.core.model.EngineId
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryFolderKind
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SegmentationParams
import com.miuraken.chunkflow.core.model.StageStatus
import com.miuraken.chunkflow.core.model.Transcript
import com.miuraken.chunkflow.core.model.TranscriptExportFormat
import com.miuraken.chunkflow.core.model.UserSettings
import com.miuraken.chunkflow.core.model.Waveform
import com.miuraken.chunkflow.core.testing.fake.FakeTranscriptParser
import com.miuraken.chunkflow.core.transcript.ChunkFlowTranscriptParser
import com.miuraken.chunkflow.core.transcript.TranscriptDocument
import com.miuraken.chunkflow.core.transcript.TranscriptError
import com.miuraken.chunkflow.core.transcript.TranscriptParseResult
import com.miuraken.chunkflow.core.transcript.TranscriptSegment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultLibraryRepositoryTest {

    private lateinit var context: Context
    private lateinit var itemStateDao: InMemoryItemStateDao
    private lateinit var mediaItemDao: InMemoryMediaItemDao
    private lateinit var chunkDao: InMemoryChunkDao
    private lateinit var driveFileDao: InMemoryDriveFileDao
    private lateinit var courseFolderDao: InMemoryCourseFolderDao
    private lateinit var driveSyncClient: FakeDriveSyncClient
    private lateinit var analysisRepository: RecordingAnalysisRepository
    private lateinit var transcriptParser: FakeTranscriptParser
    private lateinit var settingsRepository: TestUserSettingsRepository
    private lateinit var mediaFileLocator: MediaFileLocator
    private var inspectHandler: (File) -> AudioFormatInfo? = {
        AudioFormatInfo(
            mimeType = "audio/mpeg",
            extension = "mp3",
            durationMs = 120_000L,
            title = null,
            sampleRateHz = 44_100,
            mp3HeaderInfo = Mp3HeaderInfo(
                hasHeader = true,
                samplesPerFrame = 1152,
                encoderDelay = 576,
                sampleRateHz = 44_100,
            ),
        )
    }
    private var currentEpochMs: Long = 1_700_000_000_000L
    private var usableSpaceBytes: Long = 50L * 1024L * 1024L * 1024L

    @BeforeTest
    fun setUp() {
        TestContentProvider.reset()
        Robolectric.buildContentProvider(TestContentProvider::class.java)
            .create(TestContentProvider.AUTHORITY)
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "media").deleteRecursively()
        File(context.filesDir, "analysis").deleteRecursively()
        File(context.filesDir, "drive_sync_link.properties").delete()

        itemStateDao = InMemoryItemStateDao(mediaItemExistsCheck = { id -> mediaItemDao.getById(id) != null })
        chunkDao = InMemoryChunkDao()
        driveFileDao = InMemoryDriveFileDao()
        courseFolderDao = InMemoryCourseFolderDao()
        driveSyncClient = FakeDriveSyncClient()
        mediaItemDao = InMemoryMediaItemDao(
            onCascadeDelete = { mediaId ->
                itemStateDao.deleteByMediaId(mediaId)
                chunkDao.deleteByMediaId(mediaId)
                driveFileDao.deleteByMediaId(mediaId)
            },
        )
        analysisRepository = RecordingAnalysisRepository()
        transcriptParser = FakeTranscriptParser()
        settingsRepository = TestUserSettingsRepository()
        mediaFileLocator = MediaFileLocator(context.filesDir)
    }

    @AfterTest
    fun tearDown() {
        TestContentProvider.reset()
    }

    private fun createRepository(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = UnconfinedTestDispatcher(),
    ): DefaultLibraryRepository = DefaultLibraryRepository(
        context = context,
        mediaItemDao = mediaItemDao,
        itemStateDao = itemStateDao,
        userSettingsRepository = settingsRepository,
        ioDispatcher = dispatcher,
        audioFormatInspector = AudioFormatInspector { file -> inspectHandler(file) },
        mediaFileLocator = mediaFileLocator,
        chunkDao = chunkDao,
        driveFileDao = driveFileDao,
        courseFolderDao = courseFolderDao,
        driveSyncClient = driveSyncClient,
        analysisRepository = analysisRepository,
        transcriptParser = transcriptParser,
        clock = { currentEpochMs },
        usableSpaceBytesProvider = { usableSpaceBytes },
    )

    @Test
    fun `import_正常な音声ファイルを取り込むとUUID名で保存されDBと初期ItemStateが登録され解析が投入される`() = runTest {
        settingsRepository.setDefaultLanguagePair(
            LanguagePair(target = LanguageTag("ko"), base = LanguageTag("ja")),
        )
        settingsRepository.setDefaultSpeed(1.25f)

        val audioBytes = "valid-mp3-audio-payload".encodeToByteArray()
        val uri = registerContent(
            id = "lesson01",
            displayName = "../evil/path/lesson_01.mp3",
            sizeBytes = audioBytes.size.toLong(),
            bytes = audioBytes,
        )

        val repo = createRepository()
        val events = repo.import(listOf(uri)).toList()

        assertTrue(events.first() is ImportProgress.Copying)
        val done = events.last() as ImportProgress.Done
        assertEquals(1, done.mediaIds.size)
        assertEquals(0, done.duplicates)
        assertEquals(0, done.transcriptsAttached)

        val mediaId = done.mediaIds.single()
        val items = repo.observeItems().first()
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals(mediaId, item.id)
        // 表示名のパストラバーサルや拡張子が取り除かれ、安全なタイトルになっていること（§8.1）
        assertEquals("lesson_01", item.title)
        assertEquals(120_000L, item.durationMs)
        assertEquals("audio/mpeg", item.mimeType)
        assertEquals(audioBytes.size.toLong(), item.sizeBytes)
        assertEquals(sha256Hex(audioBytes), item.contentSha256)
        assertEquals(LanguagePair(LanguageTag("ko"), LanguageTag("ja")), item.languagePair)
        // ADR 0008: MPEG-1 (1152) + LAME (576) = 1728 サンプル @ 44.1kHz -> +39ms
        assertEquals(39, item.transcriptOffsetMs)

        // ライブラリ取り込み段階では解析ジョブを自動投入せず、再生画面を開いた時点で開始すること
        assertEquals(
            emptyList<Pair<MediaId, Set<AnalysisStage>>>(),
            analysisRepository.requestedAnalyses,
        )

        // 保存ファイルが UUID.mp3 として filesDir/media/ 配下にのみ存在すること
        val savedFile = mediaFileLocator.resolveAudioFile(mediaId)
        assertNotNull(savedFile)
        assertEquals("${mediaId.value}.mp3", savedFile.name)
        assertEquals(mediaFileLocator.mediaDir.canonicalFile, savedFile.canonicalFile.parentFile)
        assertTrue(savedFile.readBytes().contentEquals(audioBytes))

        // 初期 ItemState が既定速度で作成されていること
        val state = repo.observeItemState(mediaId).first()
        assertNotNull(state)
        assertEquals(0L, state.positionMs)
        assertEquals(1.25f, state.speed)
    }

    @Test
    fun `import_音声と文字起こしJSONを同時に選ぶとSHA256で照合しパディング付きIMPORTEDチャンクと言語ペアを保存する`() = runTest {
        val audioBytes = "audio-with-transcript-payload".encodeToByteArray()
        val audioSha = sha256Hex(audioBytes)
        val audioUri = registerContent("a1", "lesson01.mp3", audioBytes.size.toLong(), audioBytes)

        val oldJsonBytes = """{"createdAt":"2026-10-14T10:00:00Z"}""".encodeToByteArray()
        val newJsonBytes = """{"createdAt":"2026-10-14T12:00:00Z"}""".encodeToByteArray()
        val oldJsonUri = registerContent(
            id = "t_old",
            displayName = "lesson01_old.chunkflow.json",
            sizeBytes = oldJsonBytes.size.toLong(),
            bytes = oldJsonBytes,
            mimeType = "application/json",
        )
        val newJsonUri = registerContent(
            id = "t_new",
            displayName = "lesson01.chunkflow.json",
            sizeBytes = newJsonBytes.size.toLong(),
            bytes = newJsonBytes,
            mimeType = "application/json",
        )

        transcriptParser.result = { bytes, _ ->
            val isNew = bytes.decodeToString().contains("12:00:00Z")
            TranscriptParseResult.Ok(
                TranscriptDocument(
                    audioSha256 = audioSha,
                    audioFileName = "lesson01.mp3",
                    durationMs = 120_000L,
                    languages = LanguagePair(LanguageTag("zh"), LanguageTag("ja")),
                    generator = EngineId(if (isNew) "qwen3-asr-new" else "qwen3-asr-old"),
                    segments = listOf(
                        // 1000..3000 と 3100..5000（間の無音は 100ms なので中点 3050ms でクランプされる）
                        TranscriptSegment(
                            startMs = 1_000L,
                            endMs = 3_000L,
                            language = LanguageTag("ja"),
                            languageConfidence = 0.98f,
                            text = if (isNew) "新しいテキスト" else "古いテキスト",
                            textVariants = emptyMap(),
                            words = emptyList(),
                        ),
                        TranscriptSegment(
                            startMs = 3_100L,
                            endMs = 5_000L,
                            language = LanguageTag("zh"),
                            languageConfidence = 0.95f,
                            text = "你好",
                            textVariants = mapOf("Hant" to "你好"),
                            words = emptyList(),
                        ),
                    ),
                ),
            )
        }

        val repo = createRepository()
        val done = repo.import(listOf(audioUri, oldJsonUri, newJsonUri)).toList().last() as ImportProgress.Done

        assertEquals(1, done.mediaIds.size)
        assertEquals(0, done.duplicates)
        assertEquals(1, done.transcriptsAttached)

        val mediaId = done.mediaIds.single()
        val item = repo.observeItem(mediaId).first()
        assertNotNull(item)
        assertEquals(LanguagePair(LanguageTag("zh"), LanguageTag("ja")), item.languagePair)

        // ItemState の chunkSource が IMPORTED に切り替わっていること
        val state = repo.observeItemState(mediaId).first()
        assertNotNull(state)
        assertEquals(ChunkOrigin.IMPORTED, state.chunkSource)

        // パディング（前 150ms / 後 200ms、隣接チャンクの中点 3050ms でクランプ）が適用されていること
        val importedChunks = chunkDao.getByMediaIdAndOrigin(mediaId.value, ChunkOrigin.IMPORTED.name)
        assertEquals(2, importedChunks.size)
        assertEquals(850L, importedChunks[0].startMs) // 1000 - 150
        assertEquals(3_050L, importedChunks[0].endMs) // min(3000 + 200, (3000 + 3100) / 2) = 3050
        assertEquals(3_050L, importedChunks[1].startMs) // max(3100 - 150, (3000 + 3100) / 2) = 3050
        assertEquals(5_200L, importedChunks[1].endMs) // 5000 + 200

        // createdAt が新しい方の JSON が採用されていること（§7.4.6）
        val transcripts = chunkDao.getTranscriptsByChunkIds(importedChunks.map { it.id })
        assertEquals(2, transcripts.size)
        assertEquals("新しいテキスト", transcripts[0].text)
        assertEquals("qwen3-asr-new", transcripts[0].engine)

        // 原本ファイルが保存されていること
        val savedTranscriptFile = mediaFileLocator.transcriptFileFor(mediaId, "chunkflow.json")
        assertTrue(savedTranscriptFile.exists())
        assertTrue(savedTranscriptFile.readBytes().contentEquals(newJsonBytes))
    }

    @Test
    fun `import_SAFフォルダTreeURIを渡すとフォルダ名をcourseに設定しtranscription配下のJSONも自動で紐付ける`() = runTest {
        val audioBytes = "tree-import-audio-lesson01".encodeToByteArray()
        val jsonBytes = """{"createdAt":"2026-10-14T12:00:00Z"}""".encodeToByteArray()

        val treeUri = registerTreeDirectory(
            rootDocId = "primary:ChunkFlow/korean-course",
            rootDisplayName = "korean-course",
            audioFiles = listOf("lesson01.mp3" to audioBytes),
            transcriptionFiles = listOf("lesson01.chunkflow.json" to jsonBytes),
        )

        transcriptParser.result = { _, _ ->
            TranscriptParseResult.Ok(
                TranscriptDocument(
                    audioSha256 = null, // SHA-256 なしでも <directory>/transcription/<stem>.chunkflow.json から紐付く
                    audioFileName = "lesson01.mp3",
                    durationMs = 120_000L,
                    languages = LanguagePair(LanguageTag("ko"), LanguageTag("ja")),
                    generator = EngineId("whisper-large-v3"),
                    segments = listOf(
                        TranscriptSegment(
                            startMs = 500L,
                            endMs = 2_000L,
                            language = LanguageTag("ko"),
                            languageConfidence = 0.99f,
                            text = "안녕하세요",
                            textVariants = emptyMap(),
                            words = emptyList(),
                        ),
                    ),
                ),
            )
        }

        val repo = createRepository()
        val done = repo.import(listOf(treeUri)).toList().last() as ImportProgress.Done

        assertEquals(1, done.mediaIds.size)
        assertEquals(1, done.transcriptsAttached)

        val mediaId = done.mediaIds.single()
        val item = repo.observeItem(mediaId).first()
        assertNotNull(item)
        assertEquals("korean-course", item.course)
        assertEquals("lesson01", item.title)
        assertEquals(LanguagePair(LanguageTag("ko"), LanguageTag("ja")), item.languagePair)

        val chunks = chunkDao.getByMediaIdAndOrigin(mediaId.value, ChunkOrigin.IMPORTED.name)
        assertEquals(1, chunks.size)
        assertEquals(350L, chunks.single().startMs)
        assertEquals(2_200L, chunks.single().endMs)
    }

    @Test
    fun `importTranscript_単体取り込みでAttachedとNoMatchingAudioとInvalidを正しく返す`() = runTest {
        val audioBytes = "standalone-transcript-test-audio".encodeToByteArray()
        val audioSha = sha256Hex(audioBytes)
        val audioUri = registerContent("sa1", "unit01.mp3", audioBytes.size.toLong(), audioBytes)

        val repo = createRepository()
        val mediaId = (repo.import(listOf(audioUri)).toList().last() as ImportProgress.Done).mediaIds.single()

        // 1. 20MB 超過 -> Invalid(TOO_LARGE)
        val hugeTranscriptUri = registerContent(
            id = "t_huge",
            displayName = "huge.chunkflow.json",
            sizeBytes = DefaultLibraryRepository.MAX_TRANSCRIPT_SIZE_BYTES + 1L,
            bytes = ByteArray(16),
            mimeType = "application/json",
        )
        assertEquals(
            TranscriptImportResult.Invalid(TranscriptError.TOO_LARGE),
            repo.importTranscript(hugeTranscriptUri),
        )

        // 2. 対応する音声が DB にない -> Unmatched
        val unmatchedBytes = "{}".encodeToByteArray()
        val unmatchedUri = registerContent(
            id = "t_unmatched",
            displayName = "other.chunkflow.json",
            sizeBytes = unmatchedBytes.size.toLong(),
            bytes = unmatchedBytes,
            mimeType = "application/json",
        )
        transcriptParser.result = { _, _ ->
            TranscriptParseResult.Ok(
                TranscriptDocument(
                    audioSha256 = "f".repeat(64),
                    audioFileName = "other.mp3",
                    durationMs = 60_000L,
                    languages = LanguagePair(LanguageTag("en"), LanguageTag("ja")),
                    generator = EngineId("qwen3"),
                    segments = emptyList(),
                ),
            )
        }
        assertEquals(
            TranscriptImportResult.NoMatchingAudio(audioFileName = "other.mp3"),
            repo.importTranscript(unmatchedUri),
        )

        // 3. SHA-256 が一致 -> Attached(mediaId)
        val matchedUri = registerContent(
            id = "t_matched",
            displayName = "unit01.chunkflow.json",
            sizeBytes = unmatchedBytes.size.toLong(),
            bytes = unmatchedBytes,
            mimeType = "application/json",
        )
        transcriptParser.result = { _, _ ->
            TranscriptParseResult.Ok(
                TranscriptDocument(
                    audioSha256 = audioSha,
                    audioFileName = "unit01.mp3",
                    durationMs = 120_000L,
                    languages = LanguagePair(LanguageTag("en"), LanguageTag("ja")),
                    generator = EngineId("qwen3"),
                    segments = listOf(
                        TranscriptSegment(
                            startMs = 2_000L,
                            endMs = 4_000L,
                            language = LanguageTag("en"),
                            languageConfidence = 0.9f,
                            text = "Good morning.",
                            textVariants = emptyMap(),
                            words = emptyList(),
                        ),
                    ),
                ),
            )
        }
        assertEquals(
            TranscriptImportResult.Attached(mediaId = mediaId, chunkCount = 1, durationMismatch = false),
            repo.importTranscript(matchedUri),
        )
        assertEquals(ChunkOrigin.IMPORTED, repo.observeItemState(mediaId).first()?.chunkSource)
    }

    @Test
    fun `import_同一SHA256の音声を再取り込みすると重複として扱い一時ファイルを破棄して既存MediaIdを返す`() = runTest {
        val audioBytes = "duplicate-check-payload".encodeToByteArray()
        val uri1 = registerContent("dup1", "first.mp3", audioBytes.size.toLong(), audioBytes)
        val uri2 = registerContent("dup2", "second.mp3", audioBytes.size.toLong(), audioBytes)

        val repo = createRepository()
        val firstDone = repo.import(listOf(uri1)).toList().last() as ImportProgress.Done
        val originalId = firstDone.mediaIds.single()

        val secondDone = repo.import(listOf(uri2)).toList().last() as ImportProgress.Done
        assertEquals(listOf(originalId), secondDone.mediaIds)
        assertEquals(1, secondDone.duplicates)

        // DB には 1 件だけ存在し、一時ファイルが残っていないこと
        assertEquals(1, repo.observeItems().first().size)
        val filesInMediaDir = mediaFileLocator.mediaDir.listFiles().orEmpty()
        assertEquals(1, filesInMediaDir.size)
        assertEquals("${originalId.value}.mp3", filesInMediaDir.single().name)
    }

    @Test
    fun `import_バックアップ復元後のようにDB行だけあり音声ファイルが欠損している場合は重複取り込みで音声ファイルを再結合する`() = runTest {
        val audioBytes = "backup-restore-audio-bytes".encodeToByteArray()
        val uri1 = registerContent("restore1", "lesson.mp3", audioBytes.size.toLong(), audioBytes)
        val repo = createRepository()

        val firstDone = repo.import(listOf(uri1)).toList().last() as ImportProgress.Done
        val mediaId = firstDone.mediaIds.single()

        // レジューム位置を更新したあと、音声ファイルだけを削除してバックアップ復元直後の状態を模擬する（§6.4）
        assertEquals(emptySet(), repo.observeMissingAudioIds().first())
        repo.updatePlaybackPosition(mediaId, positionMs = 45_000L, speed = 1.5f, updatedAtEpochMs = 2_000L)
        val audioFile = mediaFileLocator.resolveAudioFile(mediaId)
        assertNotNull(audioFile)
        assertTrue(audioFile.delete())
        assertNull(mediaFileLocator.resolveAudioFile(mediaId))
        assertEquals(setOf(mediaId), repo.observeMissingAudioIds().first())
        analysisRepository.requestedAnalyses.clear()

        // 同じ音声を再度取り込む
        val uri2 = registerContent("restore2", "lesson_reimport.mp3", audioBytes.size.toLong(), audioBytes)
        val secondDone = repo.import(listOf(uri2)).toList().last() as ImportProgress.Done

        assertEquals(listOf(mediaId), secondDone.mediaIds)
        assertEquals(1, secondDone.duplicates)
        assertEquals(emptySet(), repo.observeMissingAudioIds().first())
        assertEquals(
            emptyList<Pair<MediaId, Set<AnalysisStage>>>(),
            analysisRepository.requestedAnalyses,
        )

        // 既存の MediaId のパスに音声ファイルが復活し、レジューム位置も保持されていること
        val restoredFile = mediaFileLocator.resolveAudioFile(mediaId)
        assertNotNull(restoredFile)
        assertTrue(restoredFile.exists())
        assertTrue(restoredFile.readBytes().contentEquals(audioBytes))
        val state = repo.observeItemState(mediaId).first()
        assertNotNull(state)
        assertEquals(45_000L, state.positionMs)
        assertEquals(1.5f, state.speed)
    }

    @Test
    fun `import_サイズ上限2GBを超えるファイルはスキップしてDoneを返す`() = runTest {
        val overLimitSize = DefaultLibraryRepository.MAX_AUDIO_SIZE_BYTES + 1L
        val uri = registerContent(
            id = "huge",
            displayName = "huge.mp3",
            sizeBytes = overLimitSize,
            bytes = ByteArray(16),
        )
        val repo = createRepository()
        val events = repo.import(listOf(uri)).toList()

        assertEquals(
            listOf<ImportProgress>(
                ImportProgress.Done(
                    mediaIds = emptyList(),
                    duplicates = 0,
                    transcriptsAttached = 0,
                ),
            ),
            events,
        )
        assertTrue(repo.observeItems().first().isEmpty())
    }

    @Test
    fun `import_空き容量がサイズかける1点2未満の場合はNO_SPACEで失敗する`() = runTest {
        val fileSize = 10_000L
        usableSpaceBytes = 11_999L // 10_000 * 6 / 5 = 12_000 未満
        val uri = registerContent(
            id = "nospace",
            displayName = "nospace.mp3",
            sizeBytes = fileSize,
            bytes = ByteArray(fileSize.toInt()),
        )
        val repo = createRepository()
        val events = repo.import(listOf(uri)).toList()

        assertEquals(listOf(ImportProgress.Failed(ImportError.NO_SPACE)), events)
        assertTrue(repo.observeItems().first().isEmpty())
    }

    @Test
    fun `import_非対応形式の場合は一時ファイルを削除してスキップしDoneを返す`() = runTest {
        inspectHandler = { null }
        val bytes = "corrupted-or-unsupported-bytes".encodeToByteArray()
        val uri = registerContent("bad", "corrupted.mp3", bytes.size.toLong(), bytes)

        val repo = createRepository()
        val events = repo.import(listOf(uri)).toList()

        val last = events.last()
        assertEquals(
            ImportProgress.Done(
                mediaIds = emptyList(),
                duplicates = 0,
                transcriptsAttached = 0,
            ),
            last,
        )
        assertTrue(repo.observeItems().first().isEmpty())
        assertTrue(mediaFileLocator.mediaDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `import_content以外のスキームURIは拒否してUNSUPPORTED_FORMATを返す`() = runTest {
        val fileUri = Uri.parse("file:///sdcard/Download/lesson01.mp3")
        val httpUri = Uri.parse("https://example.com/lesson01.mp3")

        val repo = createRepository()
        assertEquals(
            listOf(ImportProgress.Failed(ImportError.UNSUPPORTED_FORMAT)),
            repo.import(listOf(fileUri)).toList(),
        )
        assertEquals(
            listOf(ImportProgress.Failed(ImportError.UNSUPPORTED_FORMAT)),
            repo.import(listOf(httpUri)).toList(),
        )
    }

    @Test
    fun `import_キャンセル時に一時ファイルを削除してCancellationExceptionを再スローする`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repo = createRepository(dispatcher)
        val largeBytes = ByteArray(256 * 1024) { 0x42 }
        val uri = registerContent("cancel", "cancel.mp3", largeBytes.size.toLong(), largeBytes)

        val job = launch(dispatcher) {
            repo.import(listOf(uri)).collect { progress ->
                if (progress is ImportProgress.Copying && progress.fraction != null && progress.fraction > 0f) {
                    throw kotlinx.coroutines.CancellationException("User cancelled")
                }
            }
        }
        testScheduler.advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertTrue(mediaFileLocator.mediaDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `delete_DBの行とmediaファイルとanalysisディレクトリの両方を削除する`() = runTest {
        val bytes = "delete-target-audio".encodeToByteArray()
        val uri = registerContent("del", "delete_me.mp3", bytes.size.toLong(), bytes)
        val repo = createRepository()

        val done = repo.import(listOf(uri)).toList().last() as ImportProgress.Done
        val mediaId = done.mediaIds.single()

        // 文字起こし原本ファイルと解析ディレクトリも作成しておく（§6.4）
        val transcriptFile = mediaFileLocator.transcriptFileFor(mediaId, "chunkflow.json").apply {
            writeText("{}")
        }
        val analysisDir = File(mediaFileLocator.analysisRootDir, mediaId.value).apply {
            mkdirs()
            File(this, "waveform.bin").writeBytes(byteArrayOf(1, 2, 3))
            File(this, "features.bin").writeBytes(byteArrayOf(4, 5, 6))
        }
        assertTrue(transcriptFile.exists())
        assertTrue(analysisDir.exists())

        repo.delete(mediaId)

        assertNull(repo.observeItem(mediaId).first())
        assertNull(repo.observeItemState(mediaId).first())
        assertNull(mediaFileLocator.resolveAudioFile(mediaId))
        assertFalse(transcriptFile.exists())
        assertFalse(analysisDir.exists())
    }

    @Test
    fun `delete後_プレイヤーが位置や聴取済みや学習モードを保存しようとしても行を作らない`() = runTest {
        val bytes = "deleted-while-playing".encodeToByteArray()
        val uri = registerContent("delplay", "deleted_while_playing.mp3", bytes.size.toLong(), bytes)
        val repo = createRepository()
        val mediaId = (repo.import(listOf(uri)).toList().last() as ImportProgress.Done).mediaIds.single()
        repo.updatePlaybackPosition(mediaId, positionMs = 10_000L, speed = 1.0f, updatedAtEpochMs = 1_000L)
        val savedState = assertNotNull(repo.observeItemState(mediaId).first())

        repo.delete(mediaId)

        // 再生中の教材を削除しても、プレイヤーは 5 秒ごとの位置・再生の終わりの聴取済み・学習モードを保存しようとする
        repo.updatePlaybackPosition(mediaId, positionMs = 20_000L, speed = 1.0f, updatedAtEpochMs = 2_000L)
        repo.markItemListened(id = mediaId, isListened = true, updatedAtEpochMs = 3_000L)
        repo.upsertItemState(savedState.copy(positionMs = 30_000L, updatedAtEpochMs = 4_000L))

        assertNull(repo.observeItemState(mediaId).first())
    }

    @Test
    fun `updatePlaybackPositionとupsertItemState_レジューム位置と学習モードが保存され購読できる`() = runTest {
        val bytes = "resume-test-audio".encodeToByteArray()
        val uri = registerContent("res", "resume.mp3", bytes.size.toLong(), bytes)
        val repo = createRepository()

        val mediaId = (repo.import(listOf(uri)).toList().last() as ImportProgress.Done).mediaIds.single()

        repo.updatePlaybackPosition(
            id = mediaId,
            positionMs = 34_560L,
            speed = 1.5f,
            updatedAtEpochMs = 5_000L,
        )
        val updated = repo.observeItemState(mediaId).first()
        assertNotNull(updated)
        assertEquals(34_560L, updated.positionMs)
        assertEquals(1.5f, updated.speed)
        assertEquals(5_000L, updated.updatedAtEpochMs)

        val customState = updated.copy(
            autoPause = AutoPause.Fixed(2_000L),
            updatedAtEpochMs = 6_000L,
        )
        repo.upsertItemState(customState)
        assertEquals(customState, repo.observeItemStates().first()[mediaId])
    }

    @Test
    fun `ADR0008とMp3HeaderParser_各形式とMP3ヘッダから正しい初期オフセットを計算する`() {
        // MPEG-1 44.1kHz + Xing + LAME (1152 + 576 = 1728 -> +39ms)
        val mpeg1WithHeader = AudioFormatInfo(
            mimeType = "audio/mpeg",
            extension = "mp3",
            durationMs = 60_000L,
            sampleRateHz = 44_100,
            mp3HeaderInfo = Mp3HeaderInfo(
                hasHeader = true,
                samplesPerFrame = 1152,
                encoderDelay = 576,
                sampleRateHz = 44_100,
            ),
        )
        assertEquals(39, calculateInitialTranscriptOffsetMs(mpeg1WithHeader))

        // MPEG-2 22.05kHz + Xing + LAME (576 + 576 = 1152 -> +52ms)
        val mpeg2WithHeader = AudioFormatInfo(
            mimeType = "audio/mpeg",
            extension = "mp3",
            durationMs = 60_000L,
            sampleRateHz = 22_050,
            mp3HeaderInfo = Mp3HeaderInfo(
                hasHeader = true,
                samplesPerFrame = 576,
                encoderDelay = 576,
                sampleRateHz = 22_050,
            ),
        )
        assertEquals(52, calculateInitialTranscriptOffsetMs(mpeg2WithHeader))

        // ヘッダなし MP3 44.1kHz (-529 / 44.1 -> -12ms)
        val mpeg1Headerless = AudioFormatInfo(
            mimeType = "audio/mpeg",
            extension = "mp3",
            durationMs = 60_000L,
            sampleRateHz = 44_100,
            mp3HeaderInfo = Mp3HeaderInfo(hasHeader = false, samplesPerFrame = 1152, sampleRateHz = 44_100),
        )
        assertEquals(-12, calculateInitialTranscriptOffsetMs(mpeg1Headerless))

        // ヘッダなし MP3 22.05kHz (-529 / 22.05 -> -24ms)
        val mpeg2Headerless = AudioFormatInfo(
            mimeType = "audio/mpeg",
            extension = "mp3",
            durationMs = 60_000L,
            sampleRateHz = 22_050,
            mp3HeaderInfo = Mp3HeaderInfo(hasHeader = false, samplesPerFrame = 576, sampleRateHz = 22_050),
        )
        assertEquals(-24, calculateInitialTranscriptOffsetMs(mpeg2Headerless))

        // MP3 以外 (AAC m4a -> 0ms)
        val m4aInfo = AudioFormatInfo(
            mimeType = "audio/mp4",
            extension = "m4a",
            durationMs = 60_000L,
            sampleRateHz = 44_100,
            mp3HeaderInfo = null,
        )
        assertEquals(0, calculateInitialTranscriptOffsetMs(m4aInfo))

        // Mp3HeaderParser: ID3v2 + MPEG-1 Layer III + Xing + LAME delay 576
        val syntheticMp3 = buildSyntheticMp3WithXingAndLame(
            mpeg1 = true,
            encoderDelay = 576,
            encoderPadding = 1152,
        )
        val parsed = Mp3HeaderParser.parseBytes(syntheticMp3)
        assertTrue(parsed.hasHeader)
        assertEquals(1152, parsed.samplesPerFrame)
        assertEquals(576, parsed.encoderDelay)
        assertEquals(44_100, parsed.sampleRateHz)
    }

    private fun buildSyntheticMp3WithXingAndLame(
        mpeg1: Boolean,
        encoderDelay: Int,
        encoderPadding: Int,
    ): ByteArray {
        val out = ByteArray(512)
        // ID3v2 header (10 bytes + 20 bytes payload = 30 bytes)
        out[0] = 'I'.code.toByte()
        out[1] = 'D'.code.toByte()
        out[2] = '3'.code.toByte()
        out[3] = 4
        out[9] = 20
        val frameStart = 30
        out[frameStart] = 0xFF.toByte()
        // MPEG-1 Layer III (0xFB) or MPEG-2 Layer III (0xF3)
        out[frameStart + 1] = (if (mpeg1) 0xFB else 0xF3).toByte()
        // Bitrate index 9 (128kbps), srIndex 0 (44.1kHz or 22.05kHz) -> 0x90
        out[frameStart + 2] = 0x90.toByte()
        out[frameStart + 3] = 0x00.toByte()

        val xingPos = frameStart + 36
        "Xing".encodeToByteArray().copyInto(out, xingPos)
        // flags = 0x03 (frames + bytes = 8 bytes)
        out[xingPos + 7] = 0x03
        val lamePos = xingPos + 8 + 8
        "LAME3.100".encodeToByteArray().copyInto(out, lamePos)
        val delayPos = lamePos + 21
        out[delayPos] = ((encoderDelay ushr 4) and 0xFF).toByte()
        out[delayPos + 1] = (((encoderDelay and 0x0F) shl 4) or ((encoderPadding ushr 8) and 0x0F)).toByte()
        out[delayPos + 2] = (encoderPadding and 0xFF).toByte()
        return out
    }

    private fun registerContent(
        id: String,
        displayName: String,
        sizeBytes: Long?,
        bytes: ByteArray,
        mimeType: String = "audio/mpeg",
    ): Uri {
        val uri = Uri.parse("content://${TestContentProvider.AUTHORITY}/$id")
        TestContentProvider.entries[uri] = TestContentProvider.Entry(
            displayName = displayName,
            sizeBytes = sizeBytes,
            bytes = bytes,
            mimeType = mimeType,
        )
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { ByteArrayInputStream(bytes) }
        return uri
    }

    private fun registerTreeDirectory(
        rootDocId: String,
        rootDisplayName: String,
        audioFiles: List<Pair<String, ByteArray>>,
        transcriptionFiles: List<Pair<String, ByteArray>>,
    ): Uri {
        val treeUri = DocumentsContract.buildTreeDocumentUri(TestContentProvider.AUTHORITY, rootDocId)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        TestContentProvider.entries[rootDocUri] = TestContentProvider.Entry(
            displayName = rootDisplayName,
            sizeBytes = null,
            bytes = ByteArray(0),
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
        )

        val rootChildren = mutableListOf<TestContentProvider.ChildRow>()
        for ((name, bytes) in audioFiles) {
            val docId = "$rootDocId/$name"
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            TestContentProvider.entries[docUri] = TestContentProvider.Entry(
                displayName = name,
                sizeBytes = bytes.size.toLong(),
                bytes = bytes,
                mimeType = "audio/mpeg",
            )
            shadowOf(context.contentResolver).registerInputStreamSupplier(docUri) { ByteArrayInputStream(bytes) }
            rootChildren += TestContentProvider.ChildRow(
                documentId = docId,
                displayName = name,
                mimeType = "audio/mpeg",
                sizeBytes = bytes.size.toLong(),
            )
        }

        if (transcriptionFiles.isNotEmpty()) {
            val transDirDocId = "$rootDocId/transcription"
            rootChildren += TestContentProvider.ChildRow(
                documentId = transDirDocId,
                displayName = "transcription",
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                sizeBytes = null,
            )
            val transChildren = mutableListOf<TestContentProvider.ChildRow>()
            for ((name, bytes) in transcriptionFiles) {
                val docId = "$transDirDocId/$name"
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                TestContentProvider.entries[docUri] = TestContentProvider.Entry(
                    displayName = name,
                    sizeBytes = bytes.size.toLong(),
                    bytes = bytes,
                    mimeType = "application/json",
                )
                shadowOf(context.contentResolver).registerInputStreamSupplier(docUri) { ByteArrayInputStream(bytes) }
                transChildren += TestContentProvider.ChildRow(
                    documentId = docId,
                    displayName = name,
                    mimeType = "application/json",
                    sizeBytes = bytes.size.toLong(),
                )
            }
            val transChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, transDirDocId)
            TestContentProvider.treeChildren[transChildrenUri] = transChildren
        }

        val rootChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        TestContentProvider.treeChildren[rootChildrenUri] = rootChildren
        return treeUri
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `updateItemMetadata_コース名と言語ペアがサニタイズされて更新される`() = runTest {
        val audioBytes = "metadata-update-audio".encodeToByteArray()
        val uri = registerContent(
            id = "meta1",
            displayName = "dialogue.mp3",
            sizeBytes = audioBytes.size.toLong(),
            bytes = audioBytes,
        )
        val repo = createRepository()
        val done = repo.import(listOf(uri)).toList().last() as ImportProgress.Done
        val mediaId = done.mediaIds.single()

        repo.updateItemMetadata(
            id = mediaId,
            course = "  ../../Business_Chinese/  ",
            languages = LanguagePair(LanguageTag("zh"), LanguageTag("en")),
        )

        val updated = repo.observeItem(mediaId).first()
        assertNotNull(updated)
        assertEquals("Business_Chinese", updated.course)
        assertEquals(LanguagePair(LanguageTag("zh"), LanguageTag("en")), updated.languagePair)

        repo.updateItemMetadata(
            id = mediaId,
            course = "  Business_Chinese / Unit1  ",
            languages = LanguagePair(LanguageTag("zh"), LanguageTag("en")),
        )
        val updatedSub = repo.observeItem(mediaId).first()
        assertNotNull(updatedSub)
        assertEquals("Business_Chinese/Unit1", updatedSub.course)
    }

    @Test
    fun `import_SRTやVTTやLRCの文字起こしファイルもファイル名ステム一致で紐付けられる`() = runTest {
        val audioBytes = "srt-matching-audio".encodeToByteArray()
        val audioUri = registerContent(
            id = "srt_audio",
            displayName = "unit05.mp3",
            sizeBytes = audioBytes.size.toLong(),
            bytes = audioBytes,
        )
        val srtBytes = "1\n00:00:01,000 --> 00:00:03,000\nBonjour\n".encodeToByteArray()
        val srtUri = registerContent(
            id = "srt_sub",
            displayName = "unit05.srt",
            sizeBytes = srtBytes.size.toLong(),
            bytes = srtBytes,
            mimeType = "application/x-subrip",
        )

        // SRT は audioSha256 と languages が null
        transcriptParser.result = { _, _ ->
            TranscriptParseResult.Ok(
                TranscriptDocument(
                    audioSha256 = null,
                    audioFileName = null,
                    durationMs = null,
                    languages = null,
                    generator = EngineId("srt-import"),
                    segments = listOf(
                        TranscriptSegment(
                            startMs = 1_000L,
                            endMs = 3_000L,
                            language = null,
                            languageConfidence = null,
                            text = "Bonjour",
                            textVariants = emptyMap(),
                            words = emptyList(),
                        ),
                    ),
                ),
            )
        }

        val repo = createRepository()
        val done = repo.import(listOf(audioUri, srtUri)).toList().last() as ImportProgress.Done
        assertEquals(1, done.mediaIds.size)
        assertEquals(1, done.transcriptsAttached)

        val mediaId = done.mediaIds.single()
        val importedChunks = chunkDao.getByMediaIdAndOrigin(mediaId.value, ChunkOrigin.IMPORTED.name)
        assertEquals(1, importedChunks.size)
        assertEquals(850L, importedChunks.single().startMs)
        assertEquals(3_200L, importedChunks.single().endMs)
        assertTrue(mediaFileLocator.transcriptFileFor(mediaId, "srt").exists())
    }

    @Test
    fun `exportLocalTranscriptJson_Roomの文字起こしからスキーマ準拠のJSONを書き出しDriveへアップロードする`() = runTest {
        val audioBytes = "export-json-audio".encodeToByteArray()
        val audioUri = registerContent(
            id = "exp_audio",
            displayName = "lesson_export.mp3",
            sizeBytes = audioBytes.size.toLong(),
            bytes = audioBytes,
        )
        val repo = createRepository()
        val done = repo.import(listOf(audioUri)).toList().last() as ImportProgress.Done
        val mediaId = done.mediaIds.single()
        repo.updateItemMetadata(
            id = mediaId,
            course = "CourseZ",
            languages = LanguagePair(LanguageTag("zh"), LanguageTag("ja")),
        )

        // 端末内文字起こし（AUTO チャンク + TranscriptEntity）を模擬
        val insertedChunks = chunkDao.replaceChunksTransaction(
            mediaId = mediaId.value,
            origin = ChunkOrigin.AUTO.name,
            newChunks = listOf(
                ChunkEntity(id = 0L, mediaId = mediaId.value, startMs = 500L, endMs = 2_500L, origin = ChunkOrigin.AUTO.name),
            ),
            newTranscriptsByIndex = mapOf(
                0 to TranscriptEntity(
                    chunkId = 0L,
                    text = "你好",
                    language = "zh",
                    languageConfidence = 0.95f,
                    engine = "sherpa-onnx-sensevoice",
                    userEdited = true,
                    createdAt = currentEpochMs,
                ),
            ),
        )
        assertEquals(1, insertedChunks.size)

        val exported = repo.exportLocalTranscriptJson(mediaId)
        assertTrue(exported)

        val localJsonFile = mediaFileLocator.transcriptFileFor(mediaId, "chunkflow.json")
        assertTrue(localJsonFile.exists())

        // 生成された JSON が本物の ChunkFlowTranscriptParser の全検証（段階 1〜4）を通過することを確認
        val realParser = ChunkFlowTranscriptParser()
        val parseResult = realParser.parse(localJsonFile.readBytes(), "lesson_export.chunkflow.json")
        assertTrue(parseResult is TranscriptParseResult.Ok, "Exported JSON must pass ChunkFlowTranscriptParser: $parseResult")
        val doc = parseResult.document
        assertEquals(sha256Hex(audioBytes), doc.audioSha256)
        assertEquals(1, doc.segments.size)
        assertEquals("你好", doc.segments.single().text)

        // Drive へのアップロードも実行され、drive_file に記録されていること
        assertEquals(1, driveSyncClient.uploadedFiles.size)
        val uploaded = driveSyncClient.uploadedFiles.single()
        assertEquals("CourseZ", uploaded.first)
        assertEquals("lesson_export.chunkflow.json", uploaded.second)
        val driveRecords = driveFileDao.getByMediaId(mediaId.value)
        assertEquals(1, driveRecords.size)
        assertEquals(DriveFileKind.TRANSCRIPT.name, driveRecords.single().kind)
    }

    @Test
    fun `syncFromDrive_新規音声とJSONをダウンロードしローカルで生成済みの文字起こしをDriveへ自動アップロードする`() = runTest {
        // 1. 事前にローカルで端末内文字起こし済みの教材を 1 件用意する
        val localAudioBytes = "local-only-audio".encodeToByteArray()
        val localUri = registerContent(
            id = "local_1",
            displayName = "local_ep1.mp3",
            sizeBytes = localAudioBytes.size.toLong(),
            bytes = localAudioBytes,
        )
        val repo = createRepository()
        val importDone = repo.import(listOf(localUri)).toList().last() as ImportProgress.Done
        val localMediaId = importDone.mediaIds.single()
        repo.updateItemMetadata(
            id = localMediaId,
            course = "HSK5",
            languages = LanguagePair(LanguageTag("zh"), LanguageTag("ja")),
        )
        chunkDao.replaceChunksTransaction(
            mediaId = localMediaId.value,
            origin = ChunkOrigin.AUTO.name,
            newChunks = listOf(
                ChunkEntity(id = 0L, mediaId = localMediaId.value, startMs = 0L, endMs = 2_000L, origin = ChunkOrigin.AUTO.name),
            ),
            newTranscriptsByIndex = mapOf(
                0 to TranscriptEntity(
                    chunkId = 0L,
                    text = "ローカル文字起こし",
                    language = "ja",
                    languageConfidence = 1.0f,
                    engine = "sherpa-onnx",
                    userEdited = true,
                    createdAt = currentEpochMs,
                ),
            ),
        )

        // 2. Drive 上のコースと新規音声・新規文字起こしを設定する
        val remoteAudioBytes = "remote-drive-audio-payload".encodeToByteArray()
        val remoteAudioSha = sha256Hex(remoteAudioBytes)
        val remoteTranscriptBytes = "{\"format\":\"chunkflow-transcript\",\"version\":1}".encodeToByteArray()

        driveSyncClient.coursesResult = DriveListResult.Success(
            listOf(
                DriveCourseFiles(
                    courseName = "HSK5",
                    audioFiles = listOf(
                        DriveRemoteFile(
                            id = "drive-audio-1",
                            name = "remote_ep2.mp3",
                            mimeType = "audio/mpeg",
                            sizeBytes = remoteAudioBytes.size.toLong(),
                            modifiedTimeEpochMs = 1_700_000_100_000L,
                            sha256Checksum = remoteAudioSha,
                        ),
                    ),
                    transcriptFiles = listOf(
                        DriveRemoteFile(
                            id = "drive-json-1",
                            name = "remote_ep2.chunkflow.json",
                            mimeType = "application/json",
                            sizeBytes = remoteTranscriptBytes.size.toLong(),
                            modifiedTimeEpochMs = 1_700_000_200_000L,
                        ),
                    ),
                ),
            ),
        )
        driveSyncClient.audioPayloads["drive-audio-1"] = remoteAudioBytes
        driveSyncClient.transcriptPayloads["drive-json-1"] = remoteTranscriptBytes

        transcriptParser.result = { _, _ ->
            TranscriptParseResult.Ok(
                TranscriptDocument(
                    audioSha256 = remoteAudioSha,
                    audioFileName = "remote_ep2.mp3",
                    durationMs = 120_000L,
                    languages = LanguagePair(LanguageTag("zh"), LanguageTag("ja")),
                    generator = EngineId("pc-faster-whisper"),
                    segments = listOf(
                        TranscriptSegment(
                            startMs = 1_000L,
                            endMs = 4_000L,
                            language = LanguageTag("zh"),
                            languageConfidence = 0.9f,
                            text = "ドライブからの文字起こし",
                            textVariants = emptyMap(),
                            words = emptyList(),
                        ),
                    ),
                ),
            )
        }

        val syncEvents = repo.syncFromDrive().toList()
        assertEquals(SyncProgress.Listing, syncEvents.first())
        val syncDone = syncEvents.last() as SyncProgress.Done
        assertEquals(1, syncDone.newAudio)
        assertEquals(1, syncDone.newTranscripts)
        assertEquals(0, syncDone.failed)
        assertEquals(1, syncDone.uploadedTranscripts)

        // ローカルの local_ep1 が Drive の HSK5/transcription/local_ep1.chunkflow.json にアップロードされたこと
        assertEquals(1, driveSyncClient.uploadedFiles.size)
        assertEquals("HSK5", driveSyncClient.uploadedFiles.single().first)
        assertEquals("local_ep1.chunkflow.json", driveSyncClient.uploadedFiles.single().second)

        // 2 回目の同期では変更がないため再ダウンロード・再アップロードされないこと
        val secondSyncDone = repo.syncFromDrive().toList().last() as SyncProgress.Done
        assertEquals(0, secondSyncDone.newAudio)
        assertEquals(0, secondSyncDone.newTranscripts)
        assertEquals(0, secondSyncDone.failed)
        assertEquals(0, secondSyncDone.uploadedTranscripts)
    }

    @Test
    fun `exportTranscriptTextとwriteExportedTranscriptToUri_SRTとLRCとTXTを整形しSAFのcontentURIへ書き出す`() = runTest {
        val repo = createRepository()
        val mediaId = MediaId("export-text-media")

        // 文字起こしがない場合は null / false を返すこと
        assertNull(repo.exportTranscriptText(mediaId, TranscriptExportFormat.SRT))

        val c1 = Chunk(id = ChunkId(10L), startMs = 1_250L, endMs = 3_500L, origin = ChunkOrigin.IMPORTED)
        val c2 = Chunk(id = ChunkId(20L), startMs = 65_000L, endMs = 68_400L, origin = ChunkOrigin.IMPORTED)
        analysisRepository.chunksByMedia[mediaId] = listOf(c1, c2)
        analysisRepository.transcriptsByMedia[mediaId] = mapOf(
            c1.id to Transcript(
                chunkId = c1.id,
                text = "Hello world",
                language = LanguageTag("en"),
                languageConfidence = 0.98f,
                engine = EngineId("whisper"),
            ),
            c2.id to Transcript(
                chunkId = c2.id,
                text = "こんにちは",
                language = LanguageTag("ja"),
                languageConfidence = 0.95f,
                engine = EngineId("whisper"),
            ),
        )

        val srt = repo.exportTranscriptText(mediaId, TranscriptExportFormat.SRT)
        assertEquals(
            "1\n00:00:01,250 --> 00:00:03,500\nHello world\n\n2\n00:01:05,000 --> 00:01:08,400\nこんにちは",
            srt,
        )

        val lrc = repo.exportTranscriptText(mediaId, TranscriptExportFormat.LRC)
        assertEquals(
            "[00:01.25]Hello world\n[01:05.00]こんにちは",
            lrc,
        )

        val txt = repo.exportTranscriptText(mediaId, TranscriptExportFormat.TXT)
        assertEquals(
            "[00:01] [EN] Hello world\n[01:05] [JA] こんにちは",
            txt,
        )

        // content:// URI への書き出しと非 content:// スキームの拒否
        val targetUri = Uri.parse("content://${TestContentProvider.AUTHORITY}/exports/out.srt")
        val outStream = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(targetUri, outStream)
        assertTrue(repo.writeExportedTranscriptToUri(mediaId, TranscriptExportFormat.SRT, targetUri))
        assertEquals(srt, outStream.toByteArray().decodeToString())

        val fileUri = Uri.parse("file:///sdcard/out.srt")
        assertFalse(repo.writeExportedTranscriptToUri(mediaId, TranscriptExportFormat.SRT, fileUri))
    }

    @Test
    fun `import_コース名と言語ペアを指定して取り込むとフォルダ名やJSON内の言語よりユーザー指定値が優先される`() = runTest {
        val audioBytes = "custom-course-lang-audio".encodeToByteArray()
        val jsonBytes = """{"createdAt":"2026-10-14T12:00:00Z"}""".encodeToByteArray()
        val treeUri = registerTreeDirectory(
            rootDocId = "primary:ChunkFlow/folder-name-to-override",
            rootDisplayName = "folder-name-to-override",
            audioFiles = listOf("ep01.mp3" to audioBytes),
            transcriptionFiles = listOf("ep01.chunkflow.json" to jsonBytes),
        )

        // JSON 側は ko / ja だが、ユーザー指定は fr / en
        transcriptParser.result = { _, _ ->
            TranscriptParseResult.Ok(
                TranscriptDocument(
                    audioSha256 = null,
                    audioFileName = "ep01.mp3",
                    durationMs = 120_000L,
                    languages = LanguagePair(LanguageTag("ko"), LanguageTag("ja")),
                    generator = EngineId("whisper"),
                    segments = listOf(
                        TranscriptSegment(
                            startMs = 500L,
                            endMs = 2_000L,
                            language = LanguageTag("fr"),
                            languageConfidence = 0.99f,
                            text = "Bonjour",
                            textVariants = emptyMap(),
                            words = emptyList(),
                        ),
                    ),
                ),
            )
        }

        val repo = createRepository()
        val explicitLanguages = LanguagePair(target = LanguageTag("fr"), base = LanguageTag("en"))
        val done = repo.import(
            uris = listOf(treeUri),
            course = "  French_Pod_101  ",
            languages = explicitLanguages,
        ).toList().last() as ImportProgress.Done

        assertEquals(1, done.mediaIds.size)
        assertEquals(1, done.transcriptsAttached)

        val mediaId = done.mediaIds.single()
        val item = repo.observeItem(mediaId).first()
        assertNotNull(item)
        assertEquals("French_Pod_101", item.course)
        assertEquals(explicitLanguages, item.languagePair)
    }

    @Test
    fun `import_読み込めないファイルや非対応ファイルが混在していてもスキップして有効な音声だけを取り込む`() = runTest {
        val validAudioBytes = "valid-batch-audio-payload".encodeToByteArray()
        val unreadableBytes = "unreadable-pdf-or-ds-store".encodeToByteArray()

        val validUri = registerContent(
            id = "batch_valid",
            displayName = "valid_lesson.mp3",
            sizeBytes = validAudioBytes.size.toLong(),
            bytes = validAudioBytes,
        )
        val unreadableUri = registerContent(
            id = "batch_unreadable",
            displayName = "notes.pdf",
            sizeBytes = unreadableBytes.size.toLong(),
            bytes = unreadableBytes,
            mimeType = "application/pdf",
        )
        val missingStreamUri = Uri.parse("content://${TestContentProvider.AUTHORITY}/missing_stream")
        TestContentProvider.entries[missingStreamUri] = TestContentProvider.Entry(
            displayName = "broken.mp3",
            sizeBytes = 128L,
            bytes = ByteArray(0),
            mimeType = "audio/mpeg",
        )
        // missingStreamUri には InputStream を登録しない（openInputStream が null -> ImportError.IO）

        inspectHandler = { file ->
            if (file.readBytes().contentEquals(validAudioBytes)) {
                AudioFormatInfo(
                    mimeType = "audio/mpeg",
                    extension = "mp3",
                    durationMs = 90_000L,
                    sampleRateHz = 44_100,
                )
            } else {
                null
            }
        }

        val repo = createRepository()
        val events = repo.import(listOf(unreadableUri, missingStreamUri, validUri)).toList()
        val done = events.last() as ImportProgress.Done

        assertEquals(1, done.mediaIds.size)
        assertEquals(0, done.duplicates)
        val item = repo.observeItem(done.mediaIds.single()).first()
        assertNotNull(item)
        assertEquals("valid_lesson", item.title)
        assertEquals(1, mediaFileLocator.mediaDir.listFiles().orEmpty().size)
    }

    @Test
    fun `syncFromLinkedFolder_DriveRESTが未認可でも登録したSAFフォルダから音声と文字起こしを同期できる`() = runTest {
        val audioBytes = "linked-folder-audio-01".encodeToByteArray()
        val jsonBytes = """{"createdAt":"2026-10-14T12:00:00Z"}""".encodeToByteArray()
        val treeUri = registerTreeDirectory(
            rootDocId = "primary:Drive/ChunkFlow/Cantonese101",
            rootDisplayName = "Cantonese101",
            audioFiles = listOf("dialog01.mp3" to audioBytes),
            transcriptionFiles = listOf("dialog01.chunkflow.json" to jsonBytes),
        )

        transcriptParser.result = { _, _ ->
            TranscriptParseResult.Ok(
                TranscriptDocument(
                    audioSha256 = sha256Hex(audioBytes),
                    audioFileName = "dialog01.mp3",
                    durationMs = 120_000L,
                    languages = LanguagePair(LanguageTag("yue"), LanguageTag("ja")),
                    generator = EngineId("qwen3-asr"),
                    segments = listOf(
                        TranscriptSegment(
                            startMs = 500L,
                            endMs = 2_500L,
                            language = LanguageTag("yue"),
                            languageConfidence = 0.96f,
                            text = "食咗飯未呀",
                            textVariants = emptyMap(),
                            words = emptyList(),
                        ),
                    ),
                ),
            )
        }

        val repo = createRepository()
        assertFalse(repo.hasLinkedSyncFolder())

        // driveSyncClient.coursesResult は既定で DriveListResult.Failure(DriveError.AUTH)
        val customLangs = LanguagePair(target = LanguageTag("yue"), base = LanguageTag("en"))
        val events = repo.syncFromLinkedFolder(
            treeUri = treeUri,
            course = "Cantonese_Course",
            languages = customLangs,
        ).toList()

        assertTrue(repo.hasLinkedSyncFolder())
        val done = events.last() as SyncProgress.Done
        assertEquals(1, done.newAudio)
        assertEquals(1, done.newTranscripts)
        assertEquals(0, done.failed)

        val items = repo.observeItems().first()
        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("Cantonese_Course", item.course)
        assertEquals(customLangs, item.languagePair)

        // 2 回目は treeUri = null で呼んでも保存済みのリンク設定で同期でき、重複ダウンロードしないこと
        val secondDone = repo.syncFromLinkedFolder().toList().last() as SyncProgress.Done
        assertEquals(0, secondDone.newAudio)
        assertEquals(0, secondDone.newTranscripts)
    }

    @Test
    fun `import_ディレクトリ内の複数ファイルはファイル名の自然昇順に取り込まれ未アクセス2件まで解析がリクエストされアクセス後に補充される`() = runTest {
        val treeUri = registerTreeDirectory(
            rootDocId = "primary:ChunkFlow/NaturalOrderCourse",
            rootDisplayName = "NaturalOrderCourse",
            audioFiles = listOf(
                "lesson10.mp3" to "audio-payload-10".encodeToByteArray(),
                "lesson2.mp3" to "audio-payload-2".encodeToByteArray(),
                "lesson1.mp3" to "audio-payload-1".encodeToByteArray(),
            ),
            transcriptionFiles = emptyList(),
        )

        val repo = createRepository()
        val done = repo.import(listOf(treeUri)).toList().last() as ImportProgress.Done
        assertEquals(3, done.mediaIds.size)

        val importedTitles = done.mediaIds.mapNotNull { id -> repo.observeItem(id).first()?.title }
        assertEquals(listOf("lesson1", "lesson2", "lesson10"), importedTitles)

        // フォルダ取り込み直後は全ファイルの追加のみを行い、自動解析は開始しないこと
        assertEquals(emptyList<MediaId>(), analysisRepository.requestedAnalyses.map { it.first })

        // 再生位置更新だけでは自動補充を行わないこと（再生画面を開いた時点で prioritizeOpenedMediaAnalysis により開始）
        repo.updatePlaybackPosition(
            id = done.mediaIds[0],
            positionMs = 5_000L,
            speed = 1.0f,
            updatedAtEpochMs = 1_800_000_000_000L,
        )
        assertEquals(emptyList<MediaId>(), analysisRepository.requestedAnalyses.map { it.first })
    }

    @Test
    fun `import_ツリー配下のドキュメントURIを30個渡しても900件に多重展開されず30件のみ取り込まれる`() = runTest {
        val rootDocId = "primary:ChunkFlow/Course30"
        val audioFiles = (1..30).map { idx ->
            "track_%02d.mp3".format(idx) to "audio-payload-$idx".encodeToByteArray()
        }
        val treeUri = registerTreeDirectory(
            rootDocId = rootDocId,
            rootDisplayName = "Course30",
            audioFiles = audioFiles,
            transcriptionFiles = emptyList(),
        )
        // LibraryRoute の scanDirectory が返すのと同じく、treeUri から構築した個別ファイルの Document URI 30 個を渡す
        val documentUris = audioFiles.map { (name, _) ->
            DocumentsContract.buildDocumentUriUsingTree(treeUri, "$rootDocId/$name")
        }

        val repo = createRepository()
        val events = repo.import(
            uris = documentUris,
            course = "Course30",
            languages = null,
        ).toList()

        val done = events.last() as ImportProgress.Done
        // 30x30 = 900 件に多重展開されていれば duplicates == 870 になるが、修正後は 30 件ちょうど（重複 0）になること
        assertEquals(30, done.mediaIds.size)
        assertEquals(0, done.duplicates)

        // フォルダ取り込み段階では自動解析を開始せず、まず全ファイルをライブラリに追加すること
        assertEquals(emptyList<MediaId>(), analysisRepository.requestedAnalyses.map { it.first })
    }

    @Test
    fun `updateDirectoryMetadataとmarkItemListened_ディレクトリ単位の一括設定と聴取完了フラグ更新が機能する`() = runTest {
        val uri1 = registerContent("dir_a1", "a1.mp3", 10L, "payload-a1".encodeToByteArray())
        val uri2 = registerContent("dir_a2", "a2.mp3", 10L, "payload-a2".encodeToByteArray())
        val uriUncat = registerContent("dir_u1", "u1.mp3", 10L, "payload-u1".encodeToByteArray())

        val repo = createRepository()
        val doneCourseA = repo.import(
            uris = listOf(uri1, uri2),
            course = "CourseA",
            languages = LanguagePair(LanguageTag("en"), LanguageTag("ja")),
        ).toList().last() as ImportProgress.Done
        val doneUncat = repo.import(
            uris = listOf(uriUncat),
            course = null,
            languages = LanguagePair(LanguageTag("en"), LanguageTag("ja")),
        ).toList().last() as ImportProgress.Done

        // CourseA のコース名と言語ペアを一括更新
        val newLangsB = LanguagePair(LanguageTag("fr"), LanguageTag("en"))
        repo.updateDirectoryMetadata(
            currentCourse = "CourseA",
            newCourse = "  CourseB  ",
            languages = newLangsB,
        )

        for (mediaId in doneCourseA.mediaIds) {
            val item = repo.observeItem(mediaId).first()
            assertNotNull(item)
            assertEquals("CourseB", item.course)
            assertEquals(newLangsB, item.languagePair)
        }

        // 未分類（course == null）の教材を一括更新
        val newLangsC = LanguagePair(LanguageTag("ko"), LanguageTag("ja"))
        repo.updateDirectoryMetadata(
            currentCourse = null,
            newCourse = "CourseC",
            languages = newLangsC,
        )
        val uncatItem = repo.observeItem(doneUncat.mediaIds.single()).first()
        assertNotNull(uncatItem)
        assertEquals("CourseC", uncatItem.course)
        assertEquals(newLangsC, uncatItem.languagePair)

        // markItemListened で聴取完了（済）と最終アクセス日時を更新
        val targetId = doneCourseA.mediaIds.first()
        assertFalse(repo.observeItemState(targetId).first()?.isListened ?: true)
        repo.markItemListened(id = targetId, isListened = true, updatedAtEpochMs = 1_800_000_000_000L)
        val updatedState = repo.observeItemState(targetId).first()
        assertNotNull(updatedState)
        assertTrue(updatedState.isListened)
        assertEquals(1_800_000_000_000L, updatedState.updatedAtEpochMs)
    }

    @Test
    fun `linkLocalDirectoryとrescanLocalDirectories_音声をコピーせずLOCAL_REFERENCEで登録し再スキャンで増減を反映する`() = runTest {
        val audio1Bytes = "local-ref-audio-01".encodeToByteArray()
        val audio2Bytes = "local-ref-audio-02".encodeToByteArray()
        val json1Bytes = """{"createdAt":"2026-10-17T00:00:00Z"}""".encodeToByteArray()
        val rootDocId = "primary:Music/KoreanCourse"

        val treeUri = registerTreeDirectoryWithChunkFlowDir(
            rootDocId = rootDocId,
            rootDisplayName = "KoreanCourse",
            audioFiles = listOf("ep01.mp3" to audio1Bytes),
            chunkFlowFiles = listOf(
                "course.json" to """{"courseName":"KoreanCourse","targetLang":"ko","baseLang":"ja"}""".encodeToByteArray(),
                "ep01.chunkflow.json" to json1Bytes,
            ),
        )

        transcriptParser.result = { _, _ ->
            TranscriptParseResult.Ok(
                TranscriptDocument(
                    audioSha256 = null,
                    audioFileName = "ep01.mp3",
                    durationMs = 120_000L,
                    languages = LanguagePair(LanguageTag("ko"), LanguageTag("ja")),
                    generator = EngineId("whisper"),
                    segments = listOf(
                        TranscriptSegment(
                            startMs = 1_000L,
                            endMs = 3_000L,
                            language = LanguageTag("ko"),
                            languageConfidence = 0.99f,
                            text = "안녕하세요",
                            textVariants = emptyMap(),
                            words = emptyList(),
                        ),
                    ),
                ),
            )
        }

        val repo = createRepository()
        val done = repo.linkLocalDirectory(treeUri).toList().last() as ImportProgress.Done
        assertEquals(1, done.mediaIds.size)
        assertEquals(1, done.transcriptsAttached)

        val ep1Id = done.mediaIds.single()
        val ep1 = assertNotNull(repo.observeItem(ep1Id).first())
        assertEquals("KoreanCourse", ep1.course)
        assertEquals(LanguagePair(LanguageTag("ko"), LanguageTag("ja")), ep1.languagePair)
        assertEquals(AudioStorageState.LOCAL_REFERENCE, ep1.storageState)
        assertNotNull(ep1.sourceUri)

        // filesDir/media に音声コピー（.mp3）が作られていないこと
        assertNull(mediaFileLocator.resolveAudioFile(ep1Id, ep1.mimeType))
        assertEquals(emptySet(), repo.observeMissingAudioIds().first())

        // CourseFolder が LOCAL_DIRECTORY として登録されていること
        val folders = repo.observeCourseFolders().first()
        assertEquals(1, folders.size)
        assertEquals("KoreanCourse", folders.single().coursePath)
        assertEquals(LibraryFolderKind.LOCAL_DIRECTORY, folders.single().kind)

        // フォルダから ep01.mp3 が消え、ep02.mp3 が追加された状態を模擬して rescanLocalDirectories を実行
        val ep1DocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, "$rootDocId/ep01.mp3")
        TestContentProvider.entries.remove(ep1DocUri)
        registerTreeDirectoryWithChunkFlowDir(
            rootDocId = rootDocId,
            rootDisplayName = "KoreanCourse",
            audioFiles = listOf("ep02.mp3" to audio2Bytes),
            chunkFlowFiles = listOf(
                "course.json" to """{"courseName":"KoreanCourse","targetLang":"ko","baseLang":"ja"}""".encodeToByteArray(),
            ),
        )

        val changed = repo.rescanLocalDirectories("KoreanCourse")
        // ep01 削除 (1) + ep02 追加 (1) = 2 件変更
        assertEquals(2, changed)
        val remainingItems = repo.observeItems().first()
        assertEquals(1, remainingItems.size)
        assertEquals("ep02", remainingItems.single().title)
        assertEquals(AudioStorageState.LOCAL_REFERENCE, remainingItems.single().storageState)
    }

    @Test
    fun `createVirtualFolderとmoveItemsToCourse_仮想フォルダを作成し教材を移動するとコースと言語ペアが反映される`() = runTest {
        val audioBytes = "move-course-audio".encodeToByteArray()
        val uri = registerContent("mv1", "lesson.mp3", audioBytes.size.toLong(), audioBytes)
        val repo = createRepository()
        val mediaId = (repo.import(listOf(uri)).toList().last() as ImportProgress.Done).mediaIds.single()

        val targetLangs = LanguagePair(target = LanguageTag("zh"), base = LanguageTag("en"))
        assertTrue(repo.createVirtualFolder("  HSK4/Part1  ", targetLangs))

        val folders = repo.observeCourseFolders().first()
        assertEquals(1, folders.size)
        assertEquals("HSK4/Part1", folders.single().coursePath)
        assertEquals(LibraryFolderKind.VIRTUAL_DIRECTORY, folders.single().kind)
        assertEquals(targetLangs, folders.single().languagePair)

        repo.moveItemsToCourse(setOf(mediaId), "HSK4/Part1")
        val movedItem = assertNotNull(repo.observeItem(mediaId).first())
        assertEquals("HSK4/Part1", movedItem.course)
        assertEquals(targetLangs, movedItem.languagePair)
    }

    @Test
    fun `deleteLocalAudioCopyとdownloadDriveAudio_Drive教材のローカル音声コピーを削除しても未取得状態になり再取得できる`() = runTest {
        val remoteAudioBytes = "drive-ondemand-audio-bytes".encodeToByteArray()
        val remoteAudioSha = sha256Hex(remoteAudioBytes)
        driveSyncClient.coursesResult = DriveListResult.Success(
            listOf(
                DriveCourseFiles(
                    courseName = "DriveCourse",
                    audioFiles = listOf(
                        DriveRemoteFile(
                            id = "drive-audio-ondemand",
                            name = "unit01.mp3",
                            mimeType = "audio/mpeg",
                            sizeBytes = remoteAudioBytes.size.toLong(),
                            modifiedTimeEpochMs = 1_700_000_100_000L,
                            sha256Checksum = remoteAudioSha,
                        ),
                    ),
                    transcriptFiles = emptyList(),
                ),
            ),
        )
        driveSyncClient.audioPayloads["drive-audio-ondemand"] = remoteAudioBytes

        val repo = createRepository()
        repo.syncFromDrive().toList()

        val initialItem = repo.observeItems().first().single()
        val mediaId = initialItem.id
        assertEquals(AudioStorageState.DRIVE_DOWNLOADED, initialItem.storageState)
        assertNotNull(mediaFileLocator.resolveAudioFile(mediaId, initialItem.mimeType))

        // ローカルコピーのみ削除 -> DRIVE_REMOTE_ONLY になり、欠損エラー (observeMissingAudioIds) には入らない
        assertTrue(repo.deleteLocalAudioCopy(mediaId))
        val afterDelete = assertNotNull(repo.observeItem(mediaId).first())
        assertEquals(AudioStorageState.DRIVE_REMOTE_ONLY, afterDelete.storageState)
        assertNull(mediaFileLocator.resolveAudioFile(mediaId, afterDelete.mimeType))
        assertEquals(emptySet(), repo.observeMissingAudioIds().first())

        // オンデマンド再ダウンロード -> DRIVE_DOWNLOADED に戻りローカルファイルが復活する
        assertTrue(repo.downloadDriveAudio(mediaId))
        val afterDownload = assertNotNull(repo.observeItem(mediaId).first())
        assertEquals(AudioStorageState.DRIVE_DOWNLOADED, afterDownload.storageState)
        val restoredFile = assertNotNull(mediaFileLocator.resolveAudioFile(mediaId, afterDownload.mimeType))
        assertTrue(restoredFile.readBytes().contentEquals(remoteAudioBytes))
    }

    private fun registerTreeDirectoryWithChunkFlowDir(
        rootDocId: String,
        rootDisplayName: String,
        audioFiles: List<Pair<String, ByteArray>>,
        chunkFlowFiles: List<Pair<String, ByteArray>>,
    ): Uri {
        val treeUri = DocumentsContract.buildTreeDocumentUri(TestContentProvider.AUTHORITY, rootDocId)
        val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
        TestContentProvider.entries[rootDocUri] = TestContentProvider.Entry(
            displayName = rootDisplayName,
            sizeBytes = null,
            bytes = ByteArray(0),
            mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
        )

        val rootChildren = mutableListOf<TestContentProvider.ChildRow>()
        for ((name, bytes) in audioFiles) {
            val docId = "$rootDocId/$name"
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            TestContentProvider.entries[docUri] = TestContentProvider.Entry(
                displayName = name,
                sizeBytes = bytes.size.toLong(),
                bytes = bytes,
                mimeType = "audio/mpeg",
            )
            shadowOf(context.contentResolver).registerInputStreamSupplier(docUri) { ByteArrayInputStream(bytes) }
            rootChildren += TestContentProvider.ChildRow(
                documentId = docId,
                displayName = name,
                mimeType = "audio/mpeg",
                sizeBytes = bytes.size.toLong(),
            )
        }

        if (chunkFlowFiles.isNotEmpty()) {
            val cfDirDocId = "$rootDocId/.chunkflow"
            rootChildren += TestContentProvider.ChildRow(
                documentId = cfDirDocId,
                displayName = ".chunkflow",
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                sizeBytes = null,
            )
            val cfChildren = mutableListOf<TestContentProvider.ChildRow>()
            for ((name, bytes) in chunkFlowFiles) {
                val docId = "$cfDirDocId/$name"
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                TestContentProvider.entries[docUri] = TestContentProvider.Entry(
                    displayName = name,
                    sizeBytes = bytes.size.toLong(),
                    bytes = bytes,
                    mimeType = "application/json",
                )
                shadowOf(context.contentResolver).registerInputStreamSupplier(docUri) { ByteArrayInputStream(bytes) }
                cfChildren += TestContentProvider.ChildRow(
                    documentId = docId,
                    displayName = name,
                    mimeType = "application/json",
                    sizeBytes = bytes.size.toLong(),
                )
            }
            val cfChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, cfDirDocId)
            TestContentProvider.treeChildren[cfChildrenUri] = cfChildren
        }

        val rootChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootDocId)
        TestContentProvider.treeChildren[rootChildrenUri] = rootChildren
        return treeUri
    }

    private class InMemoryCourseFolderDao : CourseFolderDao {
        private val folders = MutableStateFlow<Map<String, CourseFolderEntity>>(emptyMap())

        override fun observeAll(): Flow<List<CourseFolderEntity>> = folders.map { map ->
            map.values.sortedBy { it.coursePath }
        }

        override suspend fun getAll(): List<CourseFolderEntity> = folders.value.values.sortedBy { it.coursePath }

        override suspend fun getByPath(coursePath: String): CourseFolderEntity? = folders.value[coursePath]

        override suspend fun getByTreeUri(treeUri: String): CourseFolderEntity? = folders.value.values.firstOrNull { it.treeUri == treeUri }

        override suspend fun upsert(folder: CourseFolderEntity) {
            folders.update { it + (folder.coursePath to folder) }
        }

        override suspend fun deleteByPath(coursePath: String) {
            folders.update { it - coursePath }
        }
    }

    private class InMemoryDriveFileDao : DriveFileDao {
        private val records = MutableStateFlow<Map<String, DriveFileEntity>>(emptyMap())

        override suspend fun getAll(): List<DriveFileEntity> = records.value.values.toList()

        override suspend fun getById(driveFileId: String): DriveFileEntity? = records.value[driveFileId]

        override suspend fun getByMediaId(mediaId: String): List<DriveFileEntity> = records.value.values.filter { it.mediaId == mediaId }

        override suspend fun upsert(file: DriveFileEntity) {
            records.update { it + (file.driveFileId to file) }
        }

        override suspend fun upsertAll(files: List<DriveFileEntity>) {
            records.update { current -> current + files.associateBy { it.driveFileId } }
        }

        override suspend fun deleteById(driveFileId: String) {
            records.update { it - driveFileId }
        }

        override suspend fun deleteByMediaIdAndKind(mediaId: String, kind: String) {
            records.update { current ->
                current.filterValues { !(it.mediaId == mediaId && it.kind == kind) }
            }
        }

        fun deleteByMediaId(mediaId: String) {
            records.update { current -> current.filterValues { it.mediaId != mediaId } }
        }
    }

    private class FakeDriveSyncClient : DriveSyncClient {
        var coursesResult: DriveListResult = DriveListResult.Failure(DriveError.AUTH)
        val audioPayloads = mutableMapOf<String, ByteArray>()
        val transcriptPayloads = mutableMapOf<String, ByteArray>()
        val uploadedFiles = mutableListOf<Triple<String, String, ByteArray>>()
        private var uploadCounter = 1

        override suspend fun listChunkFlowCourses(): DriveListResult = coursesResult

        override suspend fun downloadToFile(
            fileId: String,
            destFile: File,
            maxSizeBytes: Long,
            onProgress: ((Long) -> Unit)?,
        ): DriveDownloadResult {
            val bytes = audioPayloads[fileId] ?: return DriveDownloadResult.Failure(DriveError.NETWORK)
            if (bytes.size.toLong() > maxSizeBytes) {
                return DriveDownloadResult.Failure(DriveError.TOO_LARGE)
            }
            destFile.parentFile?.mkdirs()
            destFile.writeBytes(bytes)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return DriveDownloadResult.Success(bytes.size.toLong(), sha)
        }

        override suspend fun downloadBytes(
            fileId: String,
            maxSizeBytes: Long,
        ): DriveBytesDownloadResult {
            val bytes = transcriptPayloads[fileId] ?: return DriveBytesDownloadResult.Failure(DriveError.NETWORK)
            if (bytes.size.toLong() > maxSizeBytes) {
                return DriveBytesDownloadResult.Failure(DriveError.TOO_LARGE)
            }
            return DriveBytesDownloadResult.Success(bytes)
        }

        override suspend fun uploadTranscriptJson(
            courseName: String,
            fileName: String,
            jsonBytes: ByteArray,
        ): DriveUploadResult {
            uploadedFiles += Triple(courseName, fileName, jsonBytes)
            val id = "uploaded-drive-file-${uploadCounter++}"
            return DriveUploadResult.Success(
                DriveRemoteFile(
                    id = id,
                    name = fileName,
                    mimeType = "application/json",
                    sizeBytes = jsonBytes.size.toLong(),
                    modifiedTimeEpochMs = System.currentTimeMillis() + 10_000L,
                ),
            )
        }
    }

    private class InMemoryMediaItemDao(
        private val onCascadeDelete: (String) -> Unit,
    ) : MediaItemDao {
        private val items = MutableStateFlow<Map<String, MediaItemEntity>>(emptyMap())

        override fun observeAll(): Flow<List<MediaItemEntity>> = items.map { map ->
            map.values.sortedWith(compareByDescending<MediaItemEntity> { it.importedAt }.thenBy { it.id })
        }

        override fun observeById(id: String): Flow<MediaItemEntity?> = items.map { it[id] }

        override suspend fun getById(id: String): MediaItemEntity? = items.value[id]

        override suspend fun getBySha256(sha256: String): MediaItemEntity? = items.value.values.firstOrNull { it.sha256 == sha256 }

        override suspend fun upsert(item: MediaItemEntity) {
            items.update { it + (item.id to item) }
        }

        override suspend fun insertOrIgnore(item: MediaItemEntity): Long {
            if (items.value.containsKey(item.id) || items.value.values.any { it.sha256 == item.sha256 }) {
                return -1L
            }
            items.update { it + (item.id to item) }
            return 1L
        }

        override suspend fun update(item: MediaItemEntity) {
            items.update { current ->
                if (current.containsKey(item.id)) current + (item.id to item) else current
            }
        }

        override suspend fun updateTranscriptOffset(id: String, offsetMs: Int) {
            items.update { current ->
                val existing = current[id] ?: return@update current
                current + (id to existing.copy(transcriptOffsetMs = offsetMs))
            }
        }

        override suspend fun updateLanguagePair(id: String, targetLang: String, baseLang: String?) {
            items.update { current ->
                val existing = current[id] ?: return@update current
                current + (id to existing.copy(targetLang = targetLang, baseLang = baseLang))
            }
        }

        override suspend fun updateCourse(id: String, course: String?) {
            items.update { current ->
                val existing = current[id] ?: return@update current
                current + (id to existing.copy(course = course))
            }
        }

        override suspend fun updateCourseAndLanguagePair(
            id: String,
            course: String?,
            targetLang: String,
            baseLang: String?,
        ) {
            items.update { current ->
                val existing = current[id] ?: return@update current
                current + (id to existing.copy(course = course, targetLang = targetLang, baseLang = baseLang))
            }
        }

        override suspend fun updateCourseMetadata(
            oldCourse: String,
            newCourse: String?,
            targetLang: String,
            baseLang: String?,
        ) {
            items.update { current ->
                current.mapValues { (_, item) ->
                    if (item.course == oldCourse) {
                        item.copy(course = newCourse, targetLang = targetLang, baseLang = baseLang)
                    } else {
                        item
                    }
                }
            }
        }

        override suspend fun updateUncategorizedCourseMetadata(
            newCourse: String?,
            targetLang: String,
            baseLang: String?,
        ) {
            items.update { current ->
                current.mapValues { (_, item) ->
                    if (item.course == null) {
                        item.copy(course = newCourse, targetLang = targetLang, baseLang = baseLang)
                    } else {
                        item
                    }
                }
            }
        }

        override suspend fun deleteById(id: String) {
            items.update { it - id }
            onCascadeDelete(id)
        }
    }

    /** @param mediaItemExistsCheck 教材が `media_item` にあるか（本物の DAO と同じく、削除済みの教材へ書かないかを確かめるため）。 */
    private class InMemoryItemStateDao(
        private val mediaItemExistsCheck: suspend (String) -> Boolean = { true },
    ) : ItemStateDao {
        private val states = MutableStateFlow<Map<String, ItemStateEntity>>(emptyMap())

        override fun observeAll(): Flow<List<ItemStateEntity>> = states.map { it.values.toList() }

        override fun observeByMediaId(mediaId: String): Flow<ItemStateEntity?> = states.map { it[mediaId] }

        override suspend fun getByMediaId(mediaId: String): ItemStateEntity? = states.value[mediaId]

        override suspend fun upsert(state: ItemStateEntity) {
            states.update { it + (state.mediaId to state) }
        }

        override suspend fun mediaItemExists(mediaId: String): Boolean = mediaItemExistsCheck(mediaId)

        override suspend fun updateChunkSource(mediaId: String, chunkSource: String, updatedAt: Long): Int {
            val existing = states.value[mediaId] ?: return 0
            states.update { it + (mediaId to existing.copy(chunkSource = chunkSource, updatedAt = updatedAt)) }
            return 1
        }

        override suspend fun getMaxQueuedAt(): Long = states.value.values.maxOfOrNull { it.queuedAt } ?: 0L

        fun deleteByMediaId(mediaId: String) {
            states.update { it - mediaId }
        }
    }

    private class InMemoryChunkDao : ChunkDao {
        private var nextId: Long = 1L
        private val chunks = MutableStateFlow<List<ChunkEntity>>(emptyList())
        private val transcripts = MutableStateFlow<Map<Long, TranscriptEntity>>(emptyMap())

        override fun observeByMediaIdAndOrigin(mediaId: String, origin: String): Flow<List<ChunkEntity>> = chunks.map { list ->
            list.filter { it.mediaId == mediaId && it.origin == origin }
                .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
        }

        override fun observeByMediaId(mediaId: String): Flow<List<ChunkEntity>> = chunks.map { list ->
            list.filter { it.mediaId == mediaId }
                .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })
        }

        override fun observeOriginsByMediaId(mediaId: String): Flow<List<String>> = chunks.map { list -> list.filter { it.mediaId == mediaId }.map { it.origin }.distinct() }

        override suspend fun getByMediaIdAndOrigin(mediaId: String, origin: String): List<ChunkEntity> = chunks.value.filter { it.mediaId == mediaId && it.origin == origin }
            .sortedWith(compareBy<ChunkEntity> { it.startMs }.thenBy { it.id })

        override suspend fun insertAll(chunks: List<ChunkEntity>): List<Long> {
            val ids = mutableListOf<Long>()
            val inserted = chunks.map { chunk ->
                val assignedId = if (chunk.id == 0L) nextId++ else chunk.id
                ids += assignedId
                chunk.copy(id = assignedId)
            }
            this.chunks.update { it + inserted }
            return ids
        }

        override suspend fun getTranscriptsByChunkIds(chunkIds: List<Long>): List<TranscriptEntity> = chunkIds.mapNotNull { transcripts.value[it] }

        override suspend fun upsertTranscripts(transcripts: List<TranscriptEntity>) {
            this.transcripts.update { current ->
                current + transcripts.associateBy { it.chunkId }
            }
        }

        override suspend fun deleteByIds(chunkIds: List<Long>) {
            val idSet = chunkIds.toSet()
            chunks.update { list -> list.filterNot { it.id in idSet } }
            transcripts.update { map -> map.filterKeys { it !in idSet } }
        }

        override suspend fun deleteByMediaIdAndOrigin(mediaId: String, origin: String) {
            val targetIds = chunks.value
                .filter { it.mediaId == mediaId && it.origin == origin }
                .map { it.id }
            deleteByIds(targetIds)
        }

        override suspend fun updateSkippedByIds(mediaId: String, chunkIds: List<Long>, skipped: Boolean) {
            val idSet = chunkIds.toSet()
            chunks.update { list ->
                list.map { chunk ->
                    if (chunk.mediaId == mediaId && chunk.id in idSet) {
                        chunk.copy(isSkipped = skipped)
                    } else {
                        chunk
                    }
                }
            }
        }

        fun deleteByMediaId(mediaId: String) {
            val targetIds = chunks.value.filter { it.mediaId == mediaId }.map { it.id }.toSet()
            chunks.update { list -> list.filterNot { it.mediaId == mediaId } }
            transcripts.update { map -> map.filterKeys { it !in targetIds } }
        }
    }

    private class RecordingAnalysisRepository : AnalysisRepository {
        val requestedAnalyses = mutableListOf<Pair<MediaId, Set<AnalysisStage>>>()
        val chunksByMedia = mutableMapOf<MediaId, List<Chunk>>()
        val transcriptsByMedia = mutableMapOf<MediaId, Map<ChunkId, Transcript>>()

        override fun observeStatus(id: MediaId): Flow<Map<AnalysisStage, StageStatus>> = flowOf(emptyMap())
        override fun observeWaveform(id: MediaId): Flow<Waveform?> = flowOf(null)
        override fun observeChunks(id: MediaId): Flow<List<Chunk>> = flowOf(chunksByMedia[id].orEmpty())
        override fun observeTranscripts(id: MediaId): Flow<Map<ChunkId, Transcript>> = flowOf(transcriptsByMedia[id].orEmpty())

        override suspend fun requestAnalysis(id: MediaId, stages: Set<AnalysisStage>) {
            requestedAnalyses += id to stages
        }

        override suspend fun resegment(id: MediaId, params: SegmentationParams) = Unit
        override suspend fun setChunkSource(id: MediaId, source: ChunkOrigin) = Unit
        override suspend fun setTranscriptOffset(id: MediaId, offsetMs: Int) = Unit
    }

    class TestContentProvider : ContentProvider() {
        data class Entry(
            val displayName: String,
            val sizeBytes: Long?,
            val bytes: ByteArray,
            val mimeType: String,
        )

        data class ChildRow(
            val documentId: String,
            val displayName: String,
            val mimeType: String,
            val sizeBytes: Long?,
        )

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            treeChildren[uri]?.let { rows ->
                val cursor = MatrixCursor(
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                )
                for (row in rows) {
                    cursor.addRow(arrayOf<Any?>(row.documentId, row.displayName, row.mimeType, row.sizeBytes))
                }
                return cursor
            }

            val entry = entries[uri] ?: return null
            val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
            cursor.addRow(arrayOf<Any?>(entry.displayName, entry.sizeBytes))
            return cursor
        }

        override fun getType(uri: Uri): String? = entries[uri]?.mimeType

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? = null

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0

        companion object {
            const val AUTHORITY = "com.miuraken.chunkflow.test.provider"
            val entries = mutableMapOf<Uri, Entry>()
            val treeChildren = mutableMapOf<Uri, List<ChildRow>>()

            fun reset() {
                entries.clear()
                treeChildren.clear()
            }
        }
    }

    private class TestUserSettingsRepository(
        initial: UserSettings = UserSettings(),
    ) : UserSettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<UserSettings> = state

        override suspend fun setDefaultLanguagePair(pair: LanguagePair) {
            state.value = state.value.copy(defaultLanguagePair = pair)
        }

        override suspend fun setChineseScript(script: ChineseScript) {
            state.value = state.value.copy(chineseScript = script)
        }

        override suspend fun setCantoneseScript(script: ChineseScript) {
            state.value = state.value.copy(cantoneseScript = script)
        }

        override suspend fun setDefaultSpeed(speed: Float) {
            state.value = state.value.copy(defaultSpeed = speed)
        }

        override suspend fun setSeekBackMs(seekBackMs: Long) {
            state.value = state.value.copy(seekBackMs = seekBackMs)
        }

        override suspend fun setSeekForwardMs(seekForwardMs: Long) {
            state.value = state.value.copy(seekForwardMs = seekForwardMs)
        }
    }
}
