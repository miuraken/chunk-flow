package com.miuraken.chunkflow.core.media

import java.io.FileDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** パイプの書き込みを待つ補助（[PipeWriteGuard.awaitPipeWritable]）のテスト。 */
class PipeWriteGuardTest {

    private val fd = FileDescriptor()

    @Test
    fun `awaitPipeWritable_書けるならすぐtrue`() = runTest {
        var polls = 0
        val ok = PipeWriteGuard.awaitPipeWritable(
            fd = fd,
            timeoutMs = 1_000L,
            nowMs = { testScheduler.currentTime },
            pollWritable = {
                polls++
                PipeWriteGuard.PollResult.WRITABLE
            },
        )

        assertTrue(ok)
        assertEquals(1, polls)
    }

    @Test
    fun `awaitPipeWritable_読み手が閉じたらfalse`() = runTest {
        val ok = PipeWriteGuard.awaitPipeWritable(
            fd = fd,
            timeoutMs = 1_000L,
            nowMs = { testScheduler.currentTime },
            pollWritable = { PipeWriteGuard.PollResult.CLOSED },
        )

        assertFalse(ok)
    }

    @Test
    fun `awaitPipeWritable_読まれないまま時間が過ぎたらブロックせずfalse`() = runTest {
        val ok = PipeWriteGuard.awaitPipeWritable(
            fd = fd,
            timeoutMs = 3_000L,
            nowMs = { testScheduler.currentTime },
            pollWritable = { PipeWriteGuard.PollResult.NOT_READY },
        )

        assertFalse(ok)
        assertTrue(testScheduler.currentTime >= 3_000L)
    }

    @Test
    fun `awaitPipeWritable_途中で読まれて書けるようになればtrue`() = runTest {
        var polls = 0
        val ok = PipeWriteGuard.awaitPipeWritable(
            fd = fd,
            timeoutMs = 3_000L,
            nowMs = { testScheduler.currentTime },
            pollWritable = {
                polls++
                if (polls < 5) PipeWriteGuard.PollResult.NOT_READY else PipeWriteGuard.PollResult.WRITABLE
            },
        )

        assertTrue(ok)
        assertEquals(5, polls)
    }
}
