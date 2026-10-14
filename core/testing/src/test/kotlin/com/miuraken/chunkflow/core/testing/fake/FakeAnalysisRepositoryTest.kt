package com.miuraken.chunkflow.core.testing.fake

import com.miuraken.chunkflow.core.model.Chunk
import com.miuraken.chunkflow.core.model.ChunkId
import com.miuraken.chunkflow.core.model.ChunkOrigin
import com.miuraken.chunkflow.core.model.MediaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

class FakeAnalysisRepositoryTest {
    private val mediaId = MediaId("00000000-0000-4000-8000-000000000099")

    @Test
    fun `observeAvailableChunkOrigins_chunksとchunksByOriginから利用可能な出どころを返す`() = runTest {
        val repo = FakeAnalysisRepository()
        assertEquals(emptySet(), repo.observeAvailableChunkOrigins(mediaId).first())

        val autoChunks = listOf(Chunk(ChunkId(1L), 0L, 1_000L, ChunkOrigin.AUTO))
        val importedChunks = listOf(Chunk(ChunkId(2L), 0L, 900L, ChunkOrigin.IMPORTED))

        repo.update(mediaId) {
            it.copy(
                chunks = importedChunks,
                chunksByOrigin = mapOf(
                    ChunkOrigin.AUTO to autoChunks,
                    ChunkOrigin.IMPORTED to importedChunks,
                ),
            )
        }

        assertEquals(
            setOf(ChunkOrigin.AUTO, ChunkOrigin.IMPORTED),
            repo.observeAvailableChunkOrigins(mediaId).first(),
        )

        repo.setChunkSource(mediaId, ChunkOrigin.AUTO)
        assertEquals(ChunkOrigin.AUTO, repo.chunkSources[mediaId])
        assertEquals(autoChunks, repo.observeChunks(mediaId).first())
    }
}
