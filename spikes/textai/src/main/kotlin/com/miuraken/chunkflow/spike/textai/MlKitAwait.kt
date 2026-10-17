package com.miuraken.chunkflow.spike.textai

import com.google.android.gms.tasks.Task
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

// kotlinx-coroutines-play-services / kotlinx-coroutines-guava を足さないための、待ち合わせの小さな補助（依存は増やさない）。

/** 完了したスレッドでそのまま続きを呼ぶ Executor（コルーチンを再開するだけなので軽い）。 */
private val directExecutor = Executor { command -> command.run() }

/**
 * Play services の [Task] の完了を待つ（Translation API、RemoteModelManager）。
 *
 * Task は取り消せないので、コルーチンが取り消されたら結果を捨てるだけにする。
 */
internal suspend fun <T> Task<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener(directExecutor) { task ->
        val error = task.exception
        when {
            error != null -> continuation.resumeWithException(error)
            task.isCanceled -> continuation.cancel()
            else -> continuation.resume(task.result)
        }
    }
}

/**
 * Guava の [ListenableFuture] の完了を待つ（Proofreading API）。
 *
 * コルーチンが取り消されたら Future も取り消す。Future が取り消されたら、待っているコルーチンも取り消す（握りつぶさない）。
 */
internal suspend fun <T> ListenableFuture<T>.awaitResult(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (e: ExecutionException) {
                continuation.resumeWithException(e.cause ?: e)
            } catch (e: java.util.concurrent.CancellationException) {
                continuation.cancel(e)
            }
        },
        directExecutor,
    )
    continuation.invokeOnCancellation { cancel(false) }
}
