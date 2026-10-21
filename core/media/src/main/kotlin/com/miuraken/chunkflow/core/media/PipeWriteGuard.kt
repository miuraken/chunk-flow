package com.miuraken.chunkflow.core.media

import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileDescriptor
import kotlinx.coroutines.delay

/**
 * 音声認識サービスに PCM を流すパイプ（`ParcelFileDescriptor.createPipe()`）の書き込みを、ブロックさせずに進めるための補助。
 *
 * 読み手（`SpeechRecognizer` や ML Kit GenAI）が音声を読まないと、パイプのバッファ（約 64 KB）が埋まったところで
 * `write` が永久にブロックする（2026-10-22 の実機ベンチマークで、`SpeechRecognizer` が読み始めないまま書き込みのスレッドが
 * `write` で止まったのをスレッドダンプで確認した。読まなかった原因は未確認。画面ロック中でも読むことは確かめた）。
 * ブロックした `write` はコルーチンのキャンセルでもタイムアウトでも止まらず、書き込みを子に持つ `coroutineScope` が戻らなくなり、
 * 認識のロックを持ったまま文字起こし全体が止まる。そこで、1 回の書き込みを [PIPE_BUF_BYTES] 以下にし、書く前に必ず
 * [awaitPipeWritable] で「ブロックせずに書ける」ことを確かめる（POSIX では `POLLOUT` なら `PIPE_BUF` 以下の書き込みはブロックしない）。
 */
internal object PipeWriteGuard {
    /** POSIX の `PIPE_BUF`（Linux では 4096 バイト）。これ以下の書き込みは、`POLLOUT` のあとならブロックしない。 */
    const val PIPE_BUF_BYTES: Int = 4_096

    /** 読み手が読まなくなったとみなすまでの時間（ミリ秒）。パイプのバッファ（約 2 秒分）に加えて、この時間だけ待つ。 */
    const val DEFAULT_STALL_TIMEOUT_MS: Long = 3_000L

    /** 書けるようになるのを確かめる間隔（ミリ秒）。 */
    private const val POLL_INTERVAL_MS: Long = 10L

    /**
     * パイプの書き込み側 [fd] に、ブロックせずに書ける（`POLLOUT`）まで待つ。待つあいだは中断できる（[delay] で待つ）。
     *
     * @return 書けるようになったら `true`。読み手が閉じた（`POLLERR`・`POLLHUP`・`POLLNVAL`）とき、
     *   または [timeoutMs] のあいだ書けなかったときは `false`。
     */
    suspend fun awaitPipeWritable(
        fd: FileDescriptor,
        timeoutMs: Long = DEFAULT_STALL_TIMEOUT_MS,
        nowMs: () -> Long = { SystemClock.elapsedRealtime() },
        pollWritable: (FileDescriptor) -> PollResult = ::pollOnce,
    ): Boolean {
        val deadline = nowMs() + timeoutMs
        while (true) {
            when (pollWritable(fd)) {
                PollResult.WRITABLE -> return true
                PollResult.CLOSED -> return false
                PollResult.NOT_READY -> Unit
            }
            if (nowMs() >= deadline) return false
            delay(POLL_INTERVAL_MS)
        }
    }

    /** 1 回だけ（待たずに）書けるかを確かめた結果。 */
    enum class PollResult {
        /** ブロックせずに書ける。 */
        WRITABLE,

        /** まだ書けない（読み手が読んでいない）。 */
        NOT_READY,

        /** 読み手が閉じた・fd が無効になった。 */
        CLOSED,
    }

    private fun pollOnce(fd: FileDescriptor): PollResult {
        val pollFd = StructPollfd().apply {
            this.fd = fd
            events = OsConstants.POLLOUT.toShort()
        }
        val ready = try {
            Os.poll(arrayOf(pollFd), 0)
        } catch (e: ErrnoException) {
            // シグナルで中断されただけなら、次の確認に回す
            return if (e.errno == OsConstants.EINTR) PollResult.NOT_READY else PollResult.CLOSED
        }
        if (ready <= 0) return PollResult.NOT_READY
        val revents = pollFd.revents.toInt()
        return when {
            revents and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0 -> PollResult.CLOSED
            revents and OsConstants.POLLOUT != 0 -> PollResult.WRITABLE
            else -> PollResult.NOT_READY
        }
    }
}
