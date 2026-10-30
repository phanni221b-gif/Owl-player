package `is`.xyz.mpv

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

object VideoThumbnailLoader {
    private val executor = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    fun load(target: ImageView, video: MediaVideo) {
        val key = video.path?.takeIf { it.isNotBlank() } ?: video.uri
        target.tag = key

        cache.get(key)?.let {
            target.setImageBitmap(it)
            return
        }

        target.setImageResource(R.drawable.ic_play_arrow_black_24dp)
        executor.execute {
            val context = target.context.applicationContext
            val bitmap = try {
                val uri = Uri.parse(video.uri)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.scheme == "content") {
                    try {
                        context.contentResolver.loadThumbnail(uri, Size(320, 180), null)
                    } catch (_: Throwable) {
                        loadFrameWithRetriever(context, video)
                    }
                } else {
                    loadFrameWithRetriever(context, video)
                }
            } catch (_: Throwable) {
                null
            }

            if (bitmap != null) cache.put(key, bitmap)
            target.post {
                if (target.tag == key && bitmap != null) target.setImageBitmap(bitmap)
            }
        }
    }

    private fun loadFrameWithRetriever(context: android.content.Context, video: MediaVideo): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (!video.path.isNullOrBlank()) {
                retriever.setDataSource(video.path)
            } else {
                retriever.setDataSource(context, Uri.parse(video.uri))
            }
            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
        } finally {
            retriever.release()
        }
    }
}
