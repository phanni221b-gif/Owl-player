package `is`.xyz.mpv

import java.security.MessageDigest
import java.util.Locale

/** Pure browser rules, shared by discovery, adapters and regression tests. */
internal object MediaRules {
    fun identity(video: MediaVideo): String = video.path?.takeIf { it.isNotBlank() } ?: video.uri
    fun historyKey(source: String): String = MessageDigest.getInstance("SHA-256")
        .digest(source.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it) }
    fun resumeState(position: Long, duration: Long): Pair<Long, Long>? =
        if (position < 10_000L || (duration > 0 && duration - position <= 10_000L)) null else position to duration
    fun missingDefaults(existing: Map<String, *>, defaults: Map<String, Any>): Map<String, Any> =
        defaults.filterKeys { !existing.containsKey(it) }
    fun snapshot(grouped: Map<String, MediaFolder>, locale: Locale = Locale.getDefault()): List<MediaFolder> =
        grouped.values.map { it.copy(videos = it.videos.sortedBy { v -> v.title.lowercase(locale) }.toMutableList()) }
            .sortedBy { it.name.lowercase(locale) }
    fun add(grouped: MutableMap<String, MediaFolder>, key: String, name: String, label: String, video: MediaVideo, seen: MutableSet<String>) {
        if (!seen.add(identity(video))) return
        val folder = grouped.getOrPut(key) { MediaFolder(key, name, storageLabel = label) }
        folder.videos.add(video)
    }
    fun sortVideos(videos: List<MediaVideo>, mode: Int, locale: Locale = Locale.getDefault()): List<MediaVideo> {
        val byName = compareBy<MediaVideo> { it.title.lowercase(locale) }
        return videos.sortedWith(when (mode) {
            1 -> compareByDescending<MediaVideo> { it.modifiedSeconds }.then(byName)
            2 -> compareByDescending<MediaVideo> { it.durationMs }.then(byName)
            3 -> compareByDescending<MediaVideo> { it.sizeBytes }.then(byName)
            else -> byName
        })
    }
    fun playlist(entries: Iterable<String>): String {
        val text = StringBuilder("memory://#EXTM3U\n")
        for (entry in entries) {
            if (entry.isEmpty()) continue
            require(!entry.startsWith("#")) { "Playlist entry cannot start with #" }
            require(entry.none { it == '\r' || it == '\n' || it == '\u0000' }) { "Playlist entry contains illegal character" }
            text.append(entry).append('\n')
        }
        return text.toString()
    }
}
