package com.lsl.kotlin_agent_app.media

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

suspend fun <T> ListenableFuture<T>.await(): T {
    return suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            try {
                cancel(true)
            } catch (_: Throwable) {
            }
        }
        addListener(
            {
                try {
                    cont.resume(get())
                } catch (t: Throwable) {
                    cont.resumeWithException(
                        if (t is CancellationException) t else (t.cause ?: t)
                    )
                }
            },
            { runnable -> runnable.run() },
        )
    }
}

