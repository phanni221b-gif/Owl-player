package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.Future

object VideoThumbnailLoader {
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>())
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = (value.allocationByteCount / 1024).coerceAtLeast(1)
    }
    private class Request(val key: String) {
        val targets = mutableListOf<WeakReference<ImageView>>()
        val signal = CancellationSignal()
        var future: Future<*>? = null
    }
    private val requests = mutableMapOf<String, Request>()
    private val failures = object : LruCache<String, Long>(256) {}
    @Volatile var loading = 0
        private set

    @Synchronized fun cancel(target: ImageView) {
        val request = target.tag as? Request ?: return
        target.tag = null
        request.targets.removeAll { it.get() == null || it.get() === target }
        if (request.targets.isEmpty()) {
            request.signal.cancel()
            request.future?.cancel(true)
            (request.future as? Runnable)?.let { executor.remove(it) }
            if (requests[request.key] === request) requests.remove(request.key)
            loading = requests.size
        }
    }

    @Synchronized fun load(target: ImageView, video: MediaVideo) {
        val key = (video.path?.takeIf { it.isNotBlank() } ?: video.uri) +
            ":" + video.modifiedSeconds + ":" + video.sizeBytes
        val previous = target.tag as? Request
        if (previous?.key == key && !previous.signal.isCanceled) return
        cancel(target)
        cache.get(key)?.let { target.setImageBitmap(it); return }
        target.setImageResource(R.drawable.ic_play_arrow_black_24dp)
        if (failures.get(key)?.let { SystemClock.elapsedRealtime() - it < 30_000 } == true) return
        requests[key]?.let { it.targets.add(WeakReference(target)); target.tag = it; return }
        val request = Request(key)
        request.targets.add(WeakReference(target))
        target.tag = request
        requests[key] = request
        loading = requests.size
        val context = target.context.applicationContext
        request.future = executor.submit {
            val bitmap = try {
                request.signal.throwIfCanceled()
                val uri = Uri.parse(video.uri)
                if (Build.VERSION.SDK_INT >= 29 && uri.scheme == "content") {
                    try { context.contentResolver.loadThumbnail(uri, Size(320, 180), request.signal) }
                    catch (_: Exception) {
                        request.signal.throwIfCanceled()
                        loadFrame(context, video)
                    }
                } else loadFrame(context, video)
            } catch (_: Exception) { null }
            main.post { complete(request, bitmap) }
        }
    }

    @Synchronized private fun complete(request: Request, bitmap: Bitmap?) {
        if (requests[request.key] === request) requests.remove(request.key)
        loading = requests.size
        if (request.signal.isCanceled) { bitmap?.recycle(); return }
        if (bitmap != null) cache.put(request.key, bitmap)
        else failures.put(request.key, SystemClock.elapsedRealtime())
        request.targets.forEach { ref -> ref.get()?.let { target ->
            if (target.tag === request) {
                target.tag = null
                if (bitmap != null) target.setImageBitmap(bitmap)
            }
        } }
    }

    private fun loadFrame(context: Context, video: MediaVideo): Bitmap? {
        // Older retrievers cannot decode bounded frames: keep the placeholder.
        if (Build.VERSION.SDK_INT < 27) return null
        val retriever = MediaMetadataRetriever()
        return try {
            if (!video.path.isNullOrBlank()) retriever.setDataSource(video.path)
            else retriever.setDataSource(context, Uri.parse(video.uri))
            retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
        } finally { retriever.release() }
    }
}
