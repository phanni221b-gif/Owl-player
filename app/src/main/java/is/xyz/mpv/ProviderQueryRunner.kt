package `is`.xyz.mpv

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellableContinuation
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** No unbounded queue/thread growth, even when a vendor ignores cancellation. */
internal class ProviderQueryRunner(threads: Int, name: String) {
    private val workers = ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(), { runnable -> Thread(runnable, name).apply { isDaemon = true } }).apply {
        allowCoreThreadTimeOut(true)
    }
    suspend fun <T> query(cancel: () -> Unit, block: () -> T): T = suspendCancellableCoroutine { continuation ->
        val recipient = AtomicReference<CancellableContinuation<T>?>(continuation)
        try {
            val future = workers.submit {
                try { val value = block(); recipient.getAndSet(null)?.resume(value) }
                catch (error: Exception) { recipient.getAndSet(null)?.resumeWithException(error) }
            }
            continuation.invokeOnCancellation {
                // A quarantined provider must not retain a canceled Activity continuation.
                recipient.set(null)
                future.cancel(true)
                // CancellationSignal.cancel may itself enter vendor Binder code. Never run it on UI.
                cancelOnWorker(cancel)
            }
        } catch (error: RejectedExecutionException) { recipient.getAndSet(null)?.resumeWithException(error) }
    }
    private fun executeBestEffort(action: () -> Unit) {
        try { workers.execute { runCatching(action) } } catch (_: RejectedExecutionException) { }
    }
    companion object {
        fun cancelOnWorker(cancel: () -> Unit) = cancellationWorkers.executeBestEffort(cancel)
        private val cancellationWorkers by lazy { ProviderQueryRunner(2, "OwlProviderCancel") }
    }
}
