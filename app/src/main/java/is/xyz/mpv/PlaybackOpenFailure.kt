package `is`.xyz.mpv

/**
 * Decides whether a source that ended before playback started should be reported as unplayable.
 * Playlist containers end without playback by design: Owl opens a folder as one `memory://`
 * playlist that mpv expands and then ends, so it must never be reported (it was in diagnostic.7,
 * naming the playlist's last entry for every video).
 */
internal object PlaybackOpenFailure {
    private val playlistExtensions = setOf("m3u", "m3u8", "pls", "cue", "xspf", "asx", "wpl")

    fun isPlaylistContainer(source: String): Boolean {
        if (source.startsWith("memory://") || source.contains('\n')) return true
        val name = source.substringBefore('?').substringBefore('#').substringAfterLast('/')
        return name.substringAfterLast('.', "").lowercase() in playlistExtensions
    }

    /** Returns the file name to show, or null when nothing should be reported. */
    fun reportable(source: String?, reachedPlayback: Boolean, userChangedSource: Boolean, exiting: Boolean): String? {
        if (source.isNullOrBlank() || reachedPlayback || userChangedSource || exiting) return null
        if (isPlaylistContainer(source)) return null
        return source.substringBefore('?').trimEnd('/').substringAfterLast('/').take(80).ifBlank { null }
    }
}
