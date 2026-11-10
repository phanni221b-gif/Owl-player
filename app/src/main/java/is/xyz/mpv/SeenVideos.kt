package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import java.security.MessageDigest

/** Ever-played state is independent of resumable position (which disappears on completion). */
internal object SeenVideos {
    private const val PREFS = "owl_seen_videos"
    fun sourceKey(source: String): String? {
        val identity = when {
            source.startsWith("/") -> source
            source.startsWith("file://") -> Uri.parse(source).path
            source.startsWith("content://") -> source
            else -> null
        }?.takeIf { it.isNotBlank() } ?: return null
        val bytes = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(24) {
            for (index in 0 until 12) {
                val value = bytes[index].toInt() and 255
                append(hex[value ushr 4]); append(hex[value and 15])
            }
        }
    }
    fun markPlayed(context: Context, source: String) {
        val key = sourceKey(source) ?: return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(key, false)) prefs.edit().putBoolean(key, true).apply()
    }
    /** A renamed or moved file keeps its played state. */
    fun moveSource(context: Context, oldSource: String, newSource: String) {
        val old = sourceKey(oldSource) ?: return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(old, false)) markPlayed(context, newSource)
    }
    /** Called off the UI thread, once per folder-list refresh; never during item binding. */
    fun snapshot(context: Context): Set<String> {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.all.filterValues { it == true }.keys.toMutableSet()
        // Import surviving old resume records without changing or clearing them.
        val legacy = app.getSharedPreferences("owl_playback_history", Context.MODE_PRIVATE).all
            .filter { (key, value) -> key.startsWith("p_") && value is Long && value > 0L }
            .keys.map { it.removePrefix("p_") }.filterNot { it in seen }
        if (legacy.isNotEmpty()) {
            val edit = prefs.edit()
            legacy.forEach { edit.putBoolean(it, true); seen.add(it) }
            edit.apply()
        }
        return seen
    }
    fun unseenCount(videos: List<MediaVideo>, seen: Set<String>, checkpoint: () -> Unit = {}): Int {
        var count = 0
        val identities = hashSetOf<String>()
        for (video in videos) {
            checkpoint()
            val key = sourceKey(MediaRules.identity(video)) ?: continue
            if (identities.add(key) && key !in seen) count++
        }
        return count
    }
}
