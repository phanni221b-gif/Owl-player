package `is`.xyz.mpv

import android.content.Context
import java.security.MessageDigest

object PlaybackHistory {
    data class Entry(val positionMs: Long, val durationMs: Long) {
        val progress: Int
            get() = if (durationMs > 0L) ((positionMs * 1000L) / durationMs).toInt().coerceIn(0, 1000) else 0
    }

    private const val PREFS = "owl_playback_history"
    private const val MIN_RESUME_MS = 10_000L
    private const val END_MARGIN_MS = 10_000L

    fun get(context: Context, video: MediaVideo): Entry? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = idFor(video)
        val position = prefs.getLong("p_$id", 0L)
        val duration = prefs.getLong("d_$id", video.durationMs)
        if (position < MIN_RESUME_MS) return null
        if (duration > 0L && duration - position <= END_MARGIN_MS) return null
        return Entry(position, duration)
    }

    fun save(context: Context, video: MediaVideo, positionMs: Long, durationMs: Long) {
        val id = idFor(video)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (positionMs < MIN_RESUME_MS || (durationMs > 0L && durationMs - positionMs <= END_MARGIN_MS)) {
            prefs.edit().remove("p_$id").remove("d_$id").apply()
            return
        }
        prefs.edit()
            .putLong("p_$id", positionMs)
            .putLong("d_$id", durationMs.takeIf { it > 0L } ?: video.durationMs)
            .apply()
    }

    fun clear(context: Context, video: MediaVideo) {
        val id = idFor(video)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("p_$id").remove("d_$id").apply()
    }

    private fun idFor(video: MediaVideo): String {
        val source = video.path?.takeIf { it.isNotBlank() } ?: video.uri
        val digest = MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }
}
