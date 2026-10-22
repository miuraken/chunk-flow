package com.miuraken.chunkflow.core.database

import androidx.room.Room
import com.miuraken.chunkflow.core.database.entity.CourseFolderEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemTagNameRow
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** タグ（`tag` / `media_item_tag`）とフォルダの色（`course_folder.color_index` / `language_color`）の DAO のテスト。 */
@RunWith(RobolectricTestRunner::class)
class LibraryTagAndFolderColorDaoTest {
    private lateinit var db: ChunkFlowDatabase

    @BeforeTest
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChunkFlowDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert_色の指定がnullなら既存の色を保ちupdateColorIndexで変更と既定に戻すができる`() = runTest {
        val dao = db.courseFolderDao()
        dao.upsert(folder("韓国語", "ko"))
        assertEquals(1, dao.updateColorIndex("韓国語", 3))
        assertEquals(3, dao.getByPath("韓国語")?.colorIndex)

        // 再スキャン・取り込みのように色を指定せずに行を書き直しても、色は保たれる
        dao.upsert(folder("韓国語", "ko", lastScannedAt = 99L))
        assertEquals(3, dao.getByPath("韓国語")?.colorIndex)
        assertEquals(99L, dao.getByPath("韓国語")?.lastScannedAt)

        // 色を指定して書けばその色になる（名前の変更で行を移すときなど）
        dao.upsert(folder("韓国語", "ko").copy(colorIndex = 5))
        assertEquals(5, dao.getByPath("韓国語")?.colorIndex)

        // 既定の色に戻す
        assertEquals(1, dao.updateColorIndex("韓国語", null))
        assertNull(dao.getByPath("韓国語")?.colorIndex)

        // 行がないフォルダは何もしない
        assertEquals(0, dao.updateColorIndex("ない", 1))
    }

    @Test
    fun `upsert_対象言語に初めて使われた順で色の順番を割り当てフォルダを消しても順番は残る`() = runTest {
        val dao = db.courseFolderDao()
        dao.upsert(folder("韓国語", "ko"))
        dao.upsert(folder("中国語", "zh"))
        dao.upsert(folder("韓国語/上級", "KO"))
        dao.upsert(folder("英語", "en"))
        assertEquals(
            listOf("ko" to 0, "zh-cn" to 1, "en" to 2),
            dao.observeLanguageColors().first().map { it.language to it.ordinal },
        )

        dao.deleteByPath("中国語")
        dao.upsert(folder("ドイツ語", "de"))
        dao.upsert(folder("中国語（再作成）", "zh-cn"))
        assertEquals(
            listOf("ko" to 0, "zh-cn" to 1, "en" to 2, "de" to 3),
            dao.observeLanguageColors().first().map { it.language to it.ordinal },
        )
    }

    @Test
    fun `replaceTagsForItem_キーが同じタグは既存の表記にまとめ外して使われなくなったタグは消える`() = runTest {
        insertMediaItem(MEDIA_A, "a")
        insertMediaItem(MEDIA_B, "b")
        val dao = db.tagDao()

        assertTrue(dao.replaceTagsForItem(MEDIA_A, listOf("English", "シャドーイング"), createdAt = 10L))
        assertTrue(dao.replaceTagsForItem(MEDIA_B, listOf("english", "TOEIC"), createdAt = 20L))
        assertEquals(listOf("English", "シャドーイング", "TOEIC"), dao.observeUsedTags().first().map { it.name })
        assertEquals(
            listOf(
                MediaItemTagNameRow(MEDIA_A, "English"),
                MediaItemTagNameRow(MEDIA_B, "English"),
                MediaItemTagNameRow(MEDIA_A, "シャドーイング"),
                MediaItemTagNameRow(MEDIA_B, "TOEIC"),
            ).sortedWith(compareBy({ it.tagName }, { it.mediaId })),
            dao.observeItemTagNames().first().sortedWith(compareBy({ it.tagName }, { it.mediaId })),
        )

        // A から「シャドーイング」を外すと、どのファイルにも付いていないので消える
        assertTrue(dao.replaceTagsForItem(MEDIA_A, listOf("English"), createdAt = 30L))
        assertEquals(listOf("English", "TOEIC"), dao.observeUsedTags().first().map { it.name })
        assertNull(dao.getByKey("シャドーイング"))

        // 削除済みのファイルには書かない（外部キー違反にもならない）
        assertFalse(dao.replaceTagsForItem("00000000-0000-4000-8000-0000000000ff", listOf("English"), createdAt = 40L))
    }

    @Test
    fun `updateTagsForItems_複数のファイルに付け外しでき削除済みのファイルは飛ばし上限を超えて付けない`() = runTest {
        insertMediaItem(MEDIA_A, "a")
        insertMediaItem(MEDIA_B, "b")
        val dao = db.tagDao()
        dao.replaceTagsForItem(MEDIA_A, listOf("English", "初級"), createdAt = 10L)

        dao.updateTagsForItems(
            mediaIds = listOf(MEDIA_A, MEDIA_B, "00000000-0000-4000-8000-0000000000ff"),
            addNames = listOf("TOEIC"),
            removeNames = listOf("初級"),
            maxTagsPerItem = 30,
            createdAt = 20L,
        )
        assertEquals(
            mapOf(MEDIA_A to listOf("English", "TOEIC"), MEDIA_B to listOf("TOEIC")),
            dao.observeItemTagNames().first().groupBy({ it.mediaId }, { it.tagName }),
        )
        assertNull(dao.getByKey("初級"))

        // 付けると外すの両方にあるタグ（English）は外さない。上限（ここでは 2 個）を超える分は、渡した順で後ろから付けない
        dao.updateTagsForItems(
            mediaIds = listOf(MEDIA_A, MEDIA_B),
            addNames = listOf("上級", "english"),
            removeNames = listOf("English"),
            maxTagsPerItem = 2,
            createdAt = 30L,
        )
        assertEquals(
            mapOf(MEDIA_A to listOf("English", "TOEIC"), MEDIA_B to listOf("TOEIC", "上級")),
            dao.observeItemTagNames().first().groupBy({ it.mediaId }, { it.tagName }),
        )
        assertEquals(listOf("English", "TOEIC", "上級"), dao.observeUsedTags().first().map { it.name })
    }

    @Test
    fun `media_itemを消すとmedia_item_tagもCASCADEで消え使われていないタグは一覧に出ない`() = runTest {
        insertMediaItem(MEDIA_A, "a")
        val dao = db.tagDao()
        dao.replaceTagsForItem(MEDIA_A, listOf("English"), createdAt = 10L)

        db.mediaItemDao().deleteById(MEDIA_A)
        assertEquals(emptyList(), dao.observeItemTagNames().first())
        assertEquals(emptyList(), dao.observeUsedTags().first())
        // 行は次にタグを書き換えるときに消える。それまでは同じキーのタグを作り直さずに使う
        assertEquals("English", dao.getByKey("english")?.name)
        assertEquals(1, dao.deleteUnusedTags())
    }

    @Test
    fun `observeItemTagNamesとobserveUsedTags_購読中にファイルを消すと一覧が更新される`() = runBlocking<Unit> {
        insertMediaItem(MEDIA_A, "a")
        val dao = db.tagDao()
        dao.replaceTagsForItem(MEDIA_A, listOf("English"), createdAt = 10L)

        val itemTagEmissions = Channel<List<MediaItemTagNameRow>>(Channel.UNLIMITED)
        val usedTagEmissions = Channel<List<String>>(Channel.UNLIMITED)
        val itemTagJob = launch(Dispatchers.Default) { dao.observeItemTagNames().collect { itemTagEmissions.send(it) } }
        val usedTagJob = launch(Dispatchers.Default) { dao.observeUsedTags().collect { tags -> usedTagEmissions.send(tags.map { it.name }) } }
        try {
            withTimeout(OBSERVE_TIMEOUT_MS) {
                itemTagEmissions.receiveUntil { it.isNotEmpty() }
                usedTagEmissions.receiveUntil { it == listOf("English") }
            }
            db.mediaItemDao().deleteById(MEDIA_A)
            withTimeout(OBSERVE_TIMEOUT_MS) {
                itemTagEmissions.receiveUntil { it.isEmpty() }
                usedTagEmissions.receiveUntil { it.isEmpty() }
            }
        } finally {
            itemTagJob.cancel()
            usedTagJob.cancel()
        }
    }

    /** [predicate] を満たす値が届くまで受け取り、その値を返す。 */
    private suspend fun <T> Channel<T>.receiveUntil(predicate: (T) -> Boolean): T {
        while (true) {
            val value = receive()
            if (predicate(value)) return value
        }
    }

    private fun folder(path: String, targetLang: String, lastScannedAt: Long = 0L): CourseFolderEntity = CourseFolderEntity(
        coursePath = path,
        kind = "VIRTUAL_DIRECTORY",
        targetLang = targetLang,
        baseLang = "ja",
        lastScannedAt = lastScannedAt,
    )

    private suspend fun insertMediaItem(id: String, sha: String) {
        db.mediaItemDao().upsert(
            MediaItemEntity(
                id = id,
                title = "Lesson $sha",
                durationMs = 60_000L,
                mimeType = "audio/mpeg",
                sizeBytes = 1_000L,
                sha256 = sha.repeat(64),
                course = "korean-course",
                targetLang = "ko",
                baseLang = "ja",
                importedAt = 1_000L,
            ),
        )
    }

    private companion object {
        const val MEDIA_A = "00000000-0000-4000-8000-000000000091"
        const val MEDIA_B = "00000000-0000-4000-8000-000000000092"
        const val OBSERVE_TIMEOUT_MS = 10_000L
    }
}
