package com.miuraken.chunkflow.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.miuraken.chunkflow.core.database.entity.MediaItemTagEntity
import com.miuraken.chunkflow.core.database.entity.MediaItemTagNameRow
import com.miuraken.chunkflow.core.database.entity.TagEntity
import com.miuraken.chunkflow.core.model.LibraryTags
import kotlinx.coroutines.flow.Flow

/** 1 つのクエリに渡す ID の上限（SQLite の変数の数の上限より十分小さくする）。 */
private const val MAX_IDS_PER_QUERY: Int = 500

/** `tag` と `media_item_tag` テーブルの DAO（ファイルに付けるタグ）。 */
@Dao
interface TagDao {
    /**
     * 1 件以上のファイルに付いているタグを作成順に購読する（どのファイルにも付いていないタグは含めない）。
     *
     * `media_item` も結合して、ファイルの削除（`ON DELETE CASCADE`）でも購読中の一覧が更新されるようにする。
     */
    @Query(
        "SELECT * FROM tag WHERE EXISTS (" +
            "SELECT 1 FROM media_item_tag INNER JOIN media_item ON media_item.id = media_item_tag.media_id " +
            "WHERE media_item_tag.tag_id = tag.id) " +
            "ORDER BY created_at ASC, id ASC",
    )
    fun observeUsedTags(): Flow<List<TagEntity>>

    /**
     * ファイルとタグ名の対応をすべて、タグの作成順に購読する。
     *
     * `media_item` も結合して、ファイルの削除（`ON DELETE CASCADE`）でも購読中の一覧が更新されるようにする。
     */
    @Query(
        "SELECT media_item_tag.media_id AS media_id, tag.name AS name FROM media_item_tag " +
            "INNER JOIN tag ON tag.id = media_item_tag.tag_id " +
            "INNER JOIN media_item ON media_item.id = media_item_tag.media_id " +
            "ORDER BY tag.created_at ASC, tag.id ASC",
    )
    fun observeItemTagNames(): Flow<List<MediaItemTagNameRow>>

    /** 指定したキー（[LibraryTags.key]）のタグを取得する。 */
    @Query("SELECT * FROM tag WHERE name_key = :nameKey")
    suspend fun getByKey(nameKey: String): TagEntity?

    /** タグを挿入する。同じキーのタグがあれば何もせず `-1` を返す。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    /** 指定したファイルに付いているタグの ID を取得する。 */
    @Query("SELECT tag_id FROM media_item_tag WHERE media_id = :mediaId")
    suspend fun getTagIdsForItem(mediaId: String): List<Long>

    /** ファイルにタグを付ける（付いていれば何もしない）。ファイルがないと外部キー違反になるので、先に確かめる。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItemTags(rows: List<MediaItemTagEntity>)

    /** 指定したファイルから、指定したタグを外す。 */
    @Query("DELETE FROM media_item_tag WHERE media_id = :mediaId AND tag_id IN (:tagIds)")
    suspend fun deleteItemTags(mediaId: String, tagIds: List<Long>)

    /** 指定した教材 ID のうち、`media_item` にあるものを返す。 */
    @Query("SELECT id FROM media_item WHERE id IN (:mediaIds)")
    suspend fun getExistingMediaIds(mediaIds: List<String>): List<String>

    /** どのファイルにも付いていないタグを消す。消した数を返す。 */
    @Query("DELETE FROM tag WHERE NOT EXISTS (SELECT 1 FROM media_item_tag WHERE media_item_tag.tag_id = tag.id)")
    suspend fun deleteUnusedTags(): Int

    /**
     * タグ名に対応するタグを返す（なければ作る）。キー（[LibraryTags.key]）が同じタグは既存のものを使う。
     *
     * @param names [LibraryTags.normalizeName] でそろえた名前（並び順を保って返す。同じキーは 1 つにまとめる）。
     * @param createdAt 新しく作るタグの作成時刻。
     */
    @Transaction
    suspend fun getOrCreateTags(names: List<String>, createdAt: Long): List<TagEntity> {
        val tagsByKey = LinkedHashMap<String, TagEntity>()
        for (name in names) {
            val key = LibraryTags.key(name)
            if (key in tagsByKey) continue
            val tag = getByKey(key) ?: run {
                val newId = insertTag(TagEntity(name = name, nameKey = key, createdAt = createdAt))
                if (newId > 0L) {
                    TagEntity(id = newId, name = name, nameKey = key, createdAt = createdAt)
                } else {
                    getByKey(key)
                }
            }
            if (tag != null) {
                tagsByKey[key] = tag
            }
        }
        return tagsByKey.values.toList()
    }

    /**
     * ファイル 1 件のタグを [names] にそろえる（ない名前のタグは作り、含まれないタグは外す）。
     *
     * どのファイルにも付いていないタグは消す。ファイルが削除済みなら何もしない。
     *
     * @param names [LibraryTags.normalizeName] でそろえた名前。
     * @return ファイルがあって書き込んだら true、ファイルがなければ false。
     */
    @Transaction
    suspend fun replaceTagsForItem(mediaId: String, names: List<String>, createdAt: Long): Boolean {
        if (getExistingMediaIds(listOf(mediaId)).isEmpty()) return false
        val wantedIds = getOrCreateTags(names, createdAt).map { it.id }.toSet()
        val currentIds = getTagIdsForItem(mediaId).toSet()
        val removedIds = currentIds - wantedIds
        if (removedIds.isNotEmpty()) {
            deleteItemTags(mediaId, removedIds.toList())
        }
        val addedIds = wantedIds - currentIds
        if (addedIds.isNotEmpty()) {
            insertItemTags(addedIds.map { tagId -> MediaItemTagEntity(mediaId = mediaId, tagId = tagId) })
        }
        deleteUnusedTags()
        return true
    }

    /**
     * 複数のファイルに [addNames] のタグを付け、[removeNames] のタグを外す（ライブラリの「タグを編集」）。
     *
     * - 同じキーが両方にあるときは付ける。
     * - 削除済みのファイルは飛ばす。1 つのファイルのタグは [maxTagsPerItem] 個までにする（超える分は付けない）。
     * - どのファイルにも付いていないタグは消す。
     *
     * @param addNames [LibraryTags.normalizeName] でそろえた、付けるタグの名前。
     * @param removeNames [LibraryTags.normalizeName] でそろえた、外すタグの名前。
     */
    @Transaction
    suspend fun updateTagsForItems(
        mediaIds: List<String>,
        addNames: List<String>,
        removeNames: List<String>,
        maxTagsPerItem: Int,
        createdAt: Long,
    ) {
        val existingIds = mediaIds.distinct()
            .chunked(MAX_IDS_PER_QUERY)
            .flatMap { chunk -> getExistingMediaIds(chunk) }
        if (existingIds.isEmpty()) return
        val addKeys = addNames.map(LibraryTags::key).toSet()
        val removedTagIds = removeNames
            .map(LibraryTags::key)
            .filter { it !in addKeys }
            .distinct()
            .mapNotNull { key -> getByKey(key)?.id }
        val addedTagIds = getOrCreateTags(addNames, createdAt).map { it.id }
        for (mediaId in existingIds) {
            if (removedTagIds.isNotEmpty()) {
                deleteItemTags(mediaId, removedTagIds)
            }
            if (addedTagIds.isNotEmpty()) {
                val currentIds = getTagIdsForItem(mediaId).toSet()
                val capacity = (maxTagsPerItem - currentIds.size).coerceAtLeast(0)
                val newIds = addedTagIds.filter { it !in currentIds }.take(capacity)
                if (newIds.isNotEmpty()) {
                    insertItemTags(newIds.map { tagId -> MediaItemTagEntity(mediaId = mediaId, tagId = tagId) })
                }
            }
        }
        deleteUnusedTags()
    }
}
