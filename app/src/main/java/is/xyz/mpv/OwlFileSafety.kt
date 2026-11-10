package `is`.xyz.mpv

import java.io.File

/**
 * Pure guards for destructive browser operations. Owl folders are keyed by the parent directory
 * of their videos, so a video stored directly in a volume root produces a "folder" that IS the
 * volume root. Such directories, their ancestors and the standard shared top-level directories
 * must never be deleted, renamed or moved as a whole.
 */
internal object OwlFileSafety {
    /** Shared directories other apps own or expect at the top level of every volume. */
    private val STANDARD_TOP_LEVEL = setOf(
        "android", "alarms", "audiobooks", "dcim", "documents", "download", "movies", "music",
        "notifications", "pictures", "podcasts", "recordings", "ringtones"
    )

    private fun normalized(file: File): String =
        runCatching { file.canonicalPath }.getOrElse { file.absolutePath }.trimEnd(File.separatorChar)
            .ifEmpty { File.separator }

    fun isProtectedDirectory(dir: File, volumeRoots: Collection<File>): Boolean {
        val path = normalized(dir)
        if (path == File.separator) return true
        val roots = volumeRoots.map(::normalized).toSet()
        for (root in roots) {
            // The root itself and every ancestor (/storage, /storage/emulated, /mnt ...).
            if (root == path || root.startsWith(path + File.separator)) return true
            // A standard directory directly below a volume root (DCIM, Download, Movies ...).
            val parent = File(path).parentFile?.let(::normalized)
            if (parent == root && File(path).name.lowercase() in STANDARD_TOP_LEVEL) return true
        }
        // Well-known mount namespaces even when the volume list is unavailable.
        return path in setOf("/storage", "/storage/emulated", "/sdcard", "/mnt", "/mnt/media_rw",
            "/storage/self", "/storage/self/primary") ||
            Regex("^/storage/emulated/\\d+$").matches(path) ||
            Regex("^/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$").matches(path)
    }

    /** Validates a user-entered file name; returns null when it cannot be used as a single name. */
    fun cleanName(input: String): String? {
        val name = input.trim()
        if (name.isEmpty() || name == "." || name == ".." || name.length > 255) return null
        if (name.any { it == '/' || it == '\\' || it == '\u0000' || it.code < 0x20 }) return null
        return name
    }

    /** Keeps the original extension when the user typed only a new base name. */
    fun videoTargetName(original: File, input: String): String? {
        val name = cleanName(input) ?: return null
        val ext = original.extension
        if (ext.isEmpty() || name.endsWith(".$ext", ignoreCase = true)) return name
        return "$name.$ext"
    }

    /** Videos of [folder] that live directly in [dir]; recursive results belong to other folders. */
    fun directVideos(folder: MediaFolder, dir: File): List<MediaVideo> {
        val target = normalized(dir)
        return folder.videos.filter { video ->
            val parent = video.path?.takeIf { it.isNotBlank() }?.let { File(it).parentFile } ?: return@filter false
            normalized(parent) == target
        }
    }
}
