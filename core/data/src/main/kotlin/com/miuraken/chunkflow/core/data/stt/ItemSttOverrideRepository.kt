package com.miuraken.chunkflow.core.data.stt

import com.miuraken.chunkflow.core.database.dao.ItemSttOverrideDao
import com.miuraken.chunkflow.core.database.entity.toEntity
import com.miuraken.chunkflow.core.database.entity.toExternalModel
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.MediaId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * 教材ごとの文字起こしエンジンの上書き（ADR-0011 §2.3）を読み書きするリポジトリ（§6.3）。
 *
 * 書き込むのはプレイヤーのファイル設定シート、読むのは端末内の文字起こし（`AnalysisRepository`）。
 */
interface ItemSttOverrideRepository {
    /** 教材 [id] の上書きを購読する。上書きがなければ [ItemSttOverride.NONE]。 */
    fun observe(id: MediaId): Flow<ItemSttOverride>

    /** 教材 [id] の上書きを返す。上書きがなければ [ItemSttOverride.NONE]。 */
    suspend fun get(id: MediaId): ItemSttOverride

    /**
     * 教材 [id] の上書きを [override] にする。[ItemSttOverride.isEmpty] なら上書きを消す。
     *
     * 教材が削除済みなら何もしない（外部キー違反にしない）。
     *
     * @return 保存した（または消した）ら true、教材がなければ false。
     */
    suspend fun set(id: MediaId, override: ItemSttOverride): Boolean
}

/** Room（`item_stt_override`）を使う [ItemSttOverrideRepository] の実装。 */
@Singleton
class DefaultItemSttOverrideRepository @Inject constructor(
    private val dao: ItemSttOverrideDao,
) : ItemSttOverrideRepository {
    override fun observe(id: MediaId): Flow<ItemSttOverride> = dao.observeByMediaId(id.value)
        .map { entity -> entity?.toExternalModel() ?: ItemSttOverride.NONE }
        .distinctUntilChanged()

    override suspend fun get(id: MediaId): ItemSttOverride = dao.getByMediaId(id.value)?.toExternalModel() ?: ItemSttOverride.NONE

    override suspend fun set(id: MediaId, override: ItemSttOverride): Boolean = dao.setIfMediaExists(override.toEntity(id))
}
