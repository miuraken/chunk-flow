package com.miuraken.chunkflow.core.data.analysis

import android.content.Context
import com.miuraken.chunkflow.core.data.library.MediaFileLocator
import com.miuraken.chunkflow.core.data.stt.ItemSttOverrideRepository
import com.miuraken.chunkflow.core.database.dao.CourseFolderDao
import com.miuraken.chunkflow.core.database.entity.ChunkEntity
import com.miuraken.chunkflow.core.database.entity.CourseFolderEntity
import com.miuraken.chunkflow.core.database.entity.LanguageColorEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriber
import com.miuraken.chunkflow.core.media.OnDeviceChunkTranscriptionResult
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.LanguagePair
import com.miuraken.chunkflow.core.model.LanguageTag
import com.miuraken.chunkflow.core.model.LibraryFolderKind
import com.miuraken.chunkflow.core.model.MediaId
import com.miuraken.chunkflow.core.model.SttEngine
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 端末内の文字起こしに渡す音声認識エンジンの上書き（ファイル個別設定 ＞ フォルダ設定 ＞ 全体設定）を確かめる。
 *
 * フォルダ設定の決め方そのもの（近いフォルダ優先・言語の役割ごとの親フォルダへのフォールバック）は core:model の
 * `LibraryCourseFolderSttOverrideTest` で確かめてある。ここでは [DefaultAnalysisRepository] が、`media_item` の
 * フォルダと言語から決めた上書きを、文字起こし（[OnDeviceChunkTranscriber.transcribeChunk]）に渡すことを確かめる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DefaultAnalysisRepositoryFolderSttOverrideTest {

    private lateinit var context: Context
    private lateinit var transcriptDao: FakeTranscriptDao
    private lateinit var chunkDao: FakeChunkDao
    private lateinit var mediaItemDao: FakeMediaItemDao
    private lateinit var mediaFileLocator: MediaFileLocator
    private lateinit var itemSttOverrideRepository: InMemoryItemSttOverrideRepository
    private lateinit var transcriber: OverrideRecordingTranscriber

    @BeforeTest
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "media").deleteRecursively()
        File(context.filesDir, "analysis").deleteRecursively()

        transcriptDao = FakeTranscriptDao()
        chunkDao = FakeChunkDao(transcriptDao)
        mediaItemDao = FakeMediaItemDao()
        mediaFileLocator = MediaFileLocator(context.filesDir)
        itemSttOverrideRepository = InMemoryItemSttOverrideRepository()
        transcriber = OverrideRecordingTranscriber()
    }

    private fun createRepository(
        testScheduler: TestCoroutineScheduler,
        courseFolderDao: CourseFolderDao?,
    ): DefaultAnalysisRepository = DefaultAnalysisRepository(
        context = context,
        analysisStateDao = FakeAnalysisStateDao(),
        chunkDao = chunkDao,
        transcriptDao = transcriptDao,
        itemStateDao = FakeItemStateDao(),
        mediaItemDao = mediaItemDao,
        mediaFileResolver = mediaFileLocator,
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        onDeviceChunkTranscriber = transcriber,
        bookmarkDao = FakeBookmarkDao(),
        clock = { NOW_EPOCH_MS },
        itemSttOverrideRepository = itemSttOverrideRepository,
        courseFolderDao = courseFolderDao,
    )

    /**
     * フォルダ [course] に入った、学習対象言語 [targetLang]・ベース言語 [baseLang] の教材と、まだ文字起こししていない
     * AUTO チャンク [chunkCount] 個を用意し、チャンクの ID を返す。
     */
    private suspend fun addMedia(
        mediaId: MediaId,
        course: String?,
        targetLang: String = "en",
        baseLang: String? = "ja",
        chunkCount: Int = 1,
    ): List<ChunkId> {
        mediaItemDao.upsert(
            MediaItemEntity(
                id = mediaId.value,
                title = "フォルダの音声認識エンジンのテスト",
                durationMs = chunkCount * 1_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1_024L,
                sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                course = course,
                targetLang = targetLang,
                baseLang = baseLang,
                importedAt = NOW_EPOCH_MS,
            ),
        )
        mediaFileLocator.audioFileFor(mediaId, "mp3").writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        val chunks = (0 until chunkCount).map { index ->
            ChunkEntity(
                mediaId = mediaId.value,
                startMs = index * 1_000L,
                endMs = index * 1_000L + 800L,
                origin = ChunkOrigin.AUTO.name,
            )
        }
        return chunkDao.insertAll(chunks).map(::ChunkId)
    }

    @Test
    fun `retranscribeChunk_教材のフォルダが上書きしていない言語は親フォルダをさかのぼって決めた上書きを渡す`() = runTest {
        val courseFolderDao = RecordingCourseFolderDao(
            folder("語学", target = SttEngine.ML_KIT, base = SttEngine.SPEECH_RECOGNIZER),
            folder("語学/英語", target = SttEngine.SHERPA_ONNX_SENSE_VOICE),
            folder("語学/英語/中級", base = SttEngine.SHERPA_ONNX_WHISPER_BASE),
        )
        val repository = createRepository(testScheduler, courseFolderDao)
        val mediaId = MediaId("media_folder_stt_ancestor")
        val chunkId = addMedia(mediaId, course = "語学/英語/中級/Unit 1").single()

        repository.retranscribeChunk(mediaId, chunkId)

        // 設定の行がない「Unit 1」は飛ばし、ベース言語は 1 つ上の「中級」、学習対象言語は 2 つ上の「英語」の指定を使う
        assertEquals(
            listOf(
                ItemSttOverride(
                    targetEngine = SttEngine.SHERPA_ONNX_SENSE_VOICE,
                    baseEngine = SttEngine.SHERPA_ONNX_WHISPER_BASE,
                ),
            ),
            transcriber.receivedOverrides,
        )
        // 近いフォルダから順に調べ、両方の言語が決まったら、さらに上の「語学」は調べない
        // （強制文字起こしの中で上書きを決め直すことがあるので、重複は除いて比べる）
        assertEquals(
            listOf("語学/英語/中級/Unit 1", "語学/英語/中級", "語学/英語"),
            courseFolderDao.queriedPaths.distinct(),
        )
    }

    @Test
    fun `triggerLookaheadAt_先読みの文字起こしでも親フォルダをさかのぼって決めた上書きをすべてのチャンクに渡す`() = runTest {
        val courseFolderDao = RecordingCourseFolderDao(
            folder("語学", base = SttEngine.SHERPA_ONNX_WHISPER_SMALL),
            folder("語学/英語", target = SttEngine.MOONSHINE_BASE),
        )
        val repository = createRepository(testScheduler, courseFolderDao)
        val mediaId = MediaId("media_folder_stt_lookahead")
        val chunkIds = addMedia(mediaId, course = "語学/英語/Unit 2", chunkCount = 3)

        repository.setLookaheadEnabled(mediaId, true)
        repository.triggerLookaheadAt(mediaId, currentChunkId = chunkIds.first())

        val expected = ItemSttOverride(
            targetEngine = SttEngine.MOONSHINE_BASE,
            baseEngine = SttEngine.SHERPA_ONNX_WHISPER_SMALL,
        )
        assertEquals(List(chunkIds.size) { expected }, transcriber.receivedOverrides)
    }

    @Test
    fun `retranscribeChunk_フォルダの言語と教材の言語が違う役割はそのフォルダの上書きを使わない`() = runTest {
        val courseFolderDao = RecordingCourseFolderDao(
            folder(
                "語学",
                target = SttEngine.SHERPA_ONNX_SENSE_VOICE,
                base = SttEngine.ML_KIT,
                targetLang = "ko",
                baseLang = "ja",
            ),
            folder("語学/ヒンディー語", targetLang = "hi", baseLang = "en"),
        )
        val repository = createRepository(testScheduler, courseFolderDao)

        // 学習対象言語（ko と hi）もベース言語（ja と en）も「語学」と違うので、どちらも全体設定に従う
        val hindiEnglishId = MediaId("media_folder_stt_hi_en")
        val hindiEnglishChunkId = addMedia(
            hindiEnglishId,
            course = "語学/ヒンディー語",
            targetLang = "hi",
            baseLang = "en",
        ).single()
        repository.retranscribeChunk(hindiEnglishId, hindiEnglishChunkId)
        assertEquals(listOf(ItemSttOverride.NONE), transcriber.receivedOverrides)

        // ベース言語（ja）だけ「語学」と同じなら、ベース言語の指定だけ使う
        val hindiJapaneseId = MediaId("media_folder_stt_hi_ja")
        val hindiJapaneseChunkId = addMedia(
            hindiJapaneseId,
            course = "語学/ヒンディー語",
            targetLang = "hi",
            baseLang = "ja",
        ).single()
        repository.retranscribeChunk(hindiJapaneseId, hindiJapaneseChunkId)
        assertEquals(
            listOf(ItemSttOverride.NONE, ItemSttOverride(baseEngine = SttEngine.ML_KIT)),
            transcriber.receivedOverrides,
        )
    }

    @Test
    fun `retranscribeChunk_ファイル個別設定をフォルダの上書きより優先し両方の言語が決まっていればフォルダを調べない`() = runTest {
        val courseFolderDao = RecordingCourseFolderDao(
            folder("語学", target = SttEngine.ML_KIT, base = SttEngine.SPEECH_RECOGNIZER),
        )
        val repository = createRepository(testScheduler, courseFolderDao)

        // ベース言語だけファイル個別に指定したら、学習対象言語はフォルダの指定を使う
        val partialId = MediaId("media_folder_stt_item_partial")
        val partialChunkId = addMedia(partialId, course = "語学/英語").single()
        itemSttOverrideRepository.set(partialId, ItemSttOverride(baseEngine = SttEngine.SHERPA_ONNX_WHISPER_SMALL))
        repository.retranscribeChunk(partialId, partialChunkId)
        val partialExpected = ItemSttOverride(
            targetEngine = SttEngine.ML_KIT,
            baseEngine = SttEngine.SHERPA_ONNX_WHISPER_SMALL,
        )
        assertEquals(listOf(partialExpected), transcriber.receivedOverrides)

        // 両方の言語をファイル個別に指定したら、フォルダは調べない
        courseFolderDao.queriedPaths.clear()
        val fullId = MediaId("media_folder_stt_item_full")
        val fullChunkId = addMedia(fullId, course = "語学/英語").single()
        val fullOverride = ItemSttOverride(
            targetEngine = SttEngine.SPEECH_RECOGNIZER,
            baseEngine = SttEngine.SHERPA_ONNX,
        )
        itemSttOverrideRepository.set(fullId, fullOverride)
        repository.retranscribeChunk(fullId, fullChunkId)
        assertEquals(listOf(partialExpected, fullOverride), transcriber.receivedOverrides)
        assertEquals(emptyList(), courseFolderDao.queriedPaths)
    }

    @Test
    fun `retranscribeChunk_courseFolderDaoがなければファイル個別設定だけを渡す`() = runTest {
        val repository = createRepository(testScheduler, courseFolderDao = null)
        val mediaId = MediaId("media_folder_stt_no_dao")
        val chunkId = addMedia(mediaId, course = "語学/英語").single()
        itemSttOverrideRepository.set(mediaId, ItemSttOverride(targetEngine = SttEngine.MOONSHINE_TINY))

        repository.retranscribeChunk(mediaId, chunkId)

        assertEquals(listOf(ItemSttOverride(targetEngine = SttEngine.MOONSHINE_TINY)), transcriber.receivedOverrides)
    }

    private fun folder(
        path: String,
        target: SttEngine? = null,
        base: SttEngine? = null,
        targetLang: String = "en",
        baseLang: String? = "ja",
    ): CourseFolderEntity = CourseFolderEntity(
        coursePath = path,
        kind = LibraryFolderKind.VIRTUAL_DIRECTORY.name,
        targetLang = targetLang,
        baseLang = baseLang,
        targetSttEngine = target?.name,
        baseSttEngine = base?.name,
    )

    /** 文字起こしに渡された音声認識エンジンの上書きを、呼ばれた順に記録する [OnDeviceChunkTranscriber]。 */
    private class OverrideRecordingTranscriber : OnDeviceChunkTranscriber {
        val receivedOverrides = mutableListOf<ItemSttOverride>()

        override fun isAvailable(): Boolean = true

        override suspend fun transcribeChunk(
            audioFile: File,
            startMs: Long,
            endMs: Long,
            languagePair: LanguagePair?,
            itemSttOverride: ItemSttOverride,
            engineByLanguage: Map<LanguageTag, SttEngine>,
        ): OnDeviceChunkTranscriptionResult? {
            receivedOverrides += itemSttOverride
            return OnDeviceChunkTranscriptionResult(
                text = "チャンク $startMs-$endMs",
                language = languagePair?.target ?: LanguageTag("en"),
                confidence = 0.9f,
            )
        }
    }

    /** 教材ごとのファイル個別設定をメモリに持つ [ItemSttOverrideRepository]。 */
    private class InMemoryItemSttOverrideRepository : ItemSttOverrideRepository {
        private val overrides = MutableStateFlow<Map<MediaId, ItemSttOverride>>(emptyMap())

        override fun observe(id: MediaId): Flow<ItemSttOverride> = overrides.map { it[id] ?: ItemSttOverride.NONE }

        override suspend fun get(id: MediaId): ItemSttOverride = overrides.value[id] ?: ItemSttOverride.NONE

        override suspend fun set(id: MediaId, override: ItemSttOverride): Boolean {
            overrides.update { current -> if (override.isEmpty) current - id else current + (id to override) }
            return true
        }
    }

    /** フォルダの設定の行をメモリに持ち、[getByPath] で問い合わせたパスを順に記録する [CourseFolderDao]。 */
    private class RecordingCourseFolderDao(vararg initialFolders: CourseFolderEntity) : CourseFolderDao {
        private val folders = MutableStateFlow(initialFolders.associateBy { it.coursePath })
        val queriedPaths = mutableListOf<String>()

        override fun observeAll(): Flow<List<CourseFolderEntity>> = folders.map { map ->
            map.values.sortedBy { it.coursePath }
        }

        override suspend fun getAll(): List<CourseFolderEntity> = folders.value.values.sortedBy { it.coursePath }

        override suspend fun getByPath(coursePath: String): CourseFolderEntity? {
            queriedPaths += coursePath
            return folders.value[coursePath]
        }

        override suspend fun getByTreeUri(treeUri: String): CourseFolderEntity? = folders.value.values.firstOrNull { it.treeUri == treeUri }

        override suspend fun upsertRow(folder: CourseFolderEntity) {
            folders.update { it + (folder.coursePath to folder) }
        }

        override suspend fun deleteByPath(coursePath: String) {
            folders.update { it - coursePath }
        }

        override suspend fun updateColorIndex(coursePath: String, colorIndex: Int?): Int = updateRow(coursePath) { row ->
            row.copy(colorIndex = colorIndex)
        }

        override suspend fun updateSttOverride(coursePath: String, targetEngine: String?, baseEngine: String?): Int = updateRow(coursePath) { row ->
            row.copy(targetSttEngine = targetEngine, baseSttEngine = baseEngine)
        }

        override fun observeLanguageColors(): Flow<List<LanguageColorEntity>> = flowOf(emptyList())

        override suspend fun getLanguageColor(language: String): LanguageColorEntity? = null

        override suspend fun getMaxLanguageColorOrdinal(): Int? = null

        override suspend fun insertLanguageColor(entity: LanguageColorEntity): Long = -1L

        private fun updateRow(coursePath: String, transform: (CourseFolderEntity) -> CourseFolderEntity): Int {
            val existing = folders.value[coursePath] ?: return 0
            folders.update { it + (coursePath to transform(existing)) }
            return 1
        }
    }

    private companion object {
        const val NOW_EPOCH_MS: Long = 1_700_000_000_000L
    }
}
