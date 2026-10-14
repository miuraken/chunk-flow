package com.miuraken.chunkflow.core.data.library

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.miuraken.chunkflow.core.database.dao.ItemStateDao
import com.miuraken.chunkflow.core.database.dao.MediaItemDao
import com.miuraken.chunkflow.core.database.entity.ItemStateEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.datastore.UserSettingsRepository
import com.miuraken.chunkflow.core.model.AutoPause
import com.miuraken.chunkflow.core.model.ChineseScript
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.UserSettings
import java.io.ByteArrayInputStream
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

        itemStateDao = InMemoryItemStateDao()
        mediaItemDao = InMemoryMediaItemDao(onCascadeDelete = { mediaId -> itemStateDao.deleteByMediaId(mediaId) })
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
        clock = { currentEpochMs },
        usableSpaceBytesProvider = { usableSpaceBytes },
    )

    @Test
    fun `import_正常な音声ファイルを取り込むとUUID名で保存されDBと初期ItemStateが登録される`() = runTest {
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
        repo.updatePlaybackPosition(mediaId, positionMs = 45_000L, speed = 1.5f, updatedAtEpochMs = 2_000L)
        val audioFile = mediaFileLocator.resolveAudioFile(mediaId)
        assertNotNull(audioFile)
        assertTrue(audioFile.delete())
        assertNull(mediaFileLocator.resolveAudioFile(mediaId))

        // 同じ音声を再度取り込む
        val uri2 = registerContent("restore2", "lesson_reimport.mp3", audioBytes.size.toLong(), audioBytes)
        val secondDone = repo.import(listOf(uri2)).toList().last() as ImportProgress.Done

        assertEquals(listOf(mediaId), secondDone.mediaIds)
        assertEquals(1, secondDone.duplicates)

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
    fun `import_サイズ上限2GBを超える場合はTOO_LARGEで失敗する`() = runTest {
        val overLimitSize = DefaultLibraryRepository.MAX_AUDIO_SIZE_BYTES + 1L
        val uri = registerContent(
            id = "huge",
            displayName = "huge.mp3",
            sizeBytes = overLimitSize,
            bytes = ByteArray(16),
        )
        val repo = createRepository()
        val events = repo.import(listOf(uri)).toList()

        assertEquals(listOf(ImportProgress.Failed(ImportError.TOO_LARGE)), events)
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
    fun `import_非対応形式の場合は一時ファイルを削除してUNSUPPORTED_FORMATを返す`() = runTest {
        inspectHandler = { null }
        val bytes = "corrupted-or-unsupported-bytes".encodeToByteArray()
        val uri = registerContent("bad", "corrupted.mp3", bytes.size.toLong(), bytes)

        val repo = createRepository()
        val events = repo.import(listOf(uri)).toList()

        val last = events.last()
        assertEquals(ImportProgress.Failed(ImportError.UNSUPPORTED_FORMAT), last)
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
        val transcriptFile = File(mediaFileLocator.mediaDir, "${mediaId.value}.transcript.json").apply {
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
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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

        override suspend fun deleteById(id: String) {
            items.update { it - id }
            onCascadeDelete(id)
        }
    }

    private class InMemoryItemStateDao : ItemStateDao {
        private val states = MutableStateFlow<Map<String, ItemStateEntity>>(emptyMap())

        override fun observeAll(): Flow<List<ItemStateEntity>> = states.map { it.values.toList() }

        override fun observeByMediaId(mediaId: String): Flow<ItemStateEntity?> = states.map { it[mediaId] }

        override suspend fun getByMediaId(mediaId: String): ItemStateEntity? = states.value[mediaId]

        override suspend fun upsert(state: ItemStateEntity) {
            states.update { it + (state.mediaId to state) }
        }

        override suspend fun updateChunkSource(mediaId: String, chunkSource: String, updatedAt: Long): Int {
            val existing = states.value[mediaId] ?: return 0
            states.update { it + (mediaId to existing.copy(chunkSource = chunkSource, updatedAt = updatedAt)) }
            return 1
        }

        fun deleteByMediaId(mediaId: String) {
            states.update { it - mediaId }
        }
    }

    class TestContentProvider : ContentProvider() {
        data class Entry(
            val displayName: String,
            val sizeBytes: Long?,
            val bytes: ByteArray,
            val mimeType: String,
        )

        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
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

            fun reset() {
                entries.clear()
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
