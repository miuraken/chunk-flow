package com.miuraken.chunkflow.core.testing.fake

import com.miuraken.chunkflow.core.data.stt.ItemSttOverrideRepository
import com.miuraken.chunkflow.core.model.ItemSttOverride
import com.miuraken.chunkflow.core.model.MediaId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * テストとプレビュー用の [ItemSttOverrideRepository]。
 *
 * 上書きはメモリ上の [overrides] に保持する。[existingMediaIds] が `null` のときはすべての教材があるものとして扱い、
 * 集合を入れると、その中の教材にだけ書き込める（削除済みの教材への書き込みを試すため）。
 */
class FakeItemSttOverrideRepository(
    initialOverrides: Map<MediaId, ItemSttOverride> = emptyMap(),
    var existingMediaIds: Set<MediaId>? = null,
) : ItemSttOverrideRepository {
    /** 現在の上書き（上書きのない教材は含まない）。テストから直接読み書きできる。 */
    val overrides: MutableStateFlow<Map<MediaId, ItemSttOverride>> = MutableStateFlow(initialOverrides)

    /** [set] の呼び出し履歴（呼ばれた順）。 */
    val setRequests: MutableList<Pair<MediaId, ItemSttOverride>> = mutableListOf()

    override fun observe(id: MediaId): Flow<ItemSttOverride> = overrides
        .map { it[id] ?: ItemSttOverride.NONE }
        .distinctUntilChanged()

    override suspend fun get(id: MediaId): ItemSttOverride = overrides.value[id] ?: ItemSttOverride.NONE

    override suspend fun set(id: MediaId, override: ItemSttOverride): Boolean {
        setRequests += id to override
        val known = existingMediaIds
        if (known != null && id !in known) return false
        overrides.update { current ->
            if (override.isEmpty) current - id else current + (id to override)
        }
        return true
    }
}
