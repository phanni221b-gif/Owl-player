package `is`.xyz.mpv

import android.content.Context

object PlaybackHistory {
    data class Entry(val positionMs: Long, val durationMs: Long) {
        val progress: Int
            get() = if (durationMs > 0L) ((positionMs * 1000L) / durationMs).toInt().coerceIn(0, 1000) else 0
    }

    private const val PREFS = "owl_playback_history"

    fun get(context: Context, video: MediaVideo): Entry? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = idFor(MediaRules.identity(video))
        val position = prefs.getLong("p_$id", 0L)
        val duration = prefs.getLong("d_$id", video.durationMs)
        if (MediaRules.resumeState(position, duration) == null) return null
        return Entry(position, duration)
    }

    fun save(context: Context, video: MediaVideo, positionMs: Long, durationMs: Long) =
        saveSource(context, MediaRules.identity(video), positionMs, durationMs.takeIf { it > 0L } ?: video.durationMs)

    /**
     * Records a position by playback source. mpv's "path" for an Owl playlist entry is exactly
     * MediaRules.identity(video), so the player can save progress for every entry it plays,
     * including previous playlist items and task removal, without the browser's result callback.
     */
    fun saveSource(context: Context, source: String, positionMs: Long, durationMs: Long) {
        val id = idFor(source) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (MediaRules.resumeState(positionMs, durationMs) == null) {
            prefs.edit().remove("p_$id").remove("d_$id").apply()
            return
        }
        prefs.edit().putLong("p_$id", positionMs).putLong("d_$id", durationMs).apply()
    }

    fun clear(context: Context, video: MediaVideo) {
        val id = idFor(MediaRules.identity(video)) ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove("p_$id").remove("d_$id").apply()
    }

    /** Keeps resume state attached to a renamed/moved file. */
    fun moveSource(context: Context, oldSource: String, newSource: String) {
        val from = idFor(oldSource) ?: return
        val to = idFor(newSource) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains("p_$from")) return
        prefs.edit()
            .putLong("p_$to", prefs.getLong("p_$from", 0L)).putLong("d_$to", prefs.getLong("d_$from", 0L))
            .remove("p_$from").remove("d_$from").apply()
    }

    /** Only local files and content URIs are persisted; network, fd and memory sources are not. */
    private fun idFor(source: String): String? {
        val identity = when {
            source.startsWith("/") || source.startsWith("content://") -> source
            source.startsWith("file://") -> android.net.Uri.parse(source).path
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null
        return MediaRules.historyKey(identity)
    }
}
