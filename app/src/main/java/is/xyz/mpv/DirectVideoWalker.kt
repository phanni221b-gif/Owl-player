package `is`.xyz.mpv

import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/** Read-only traversal; cancellation is checked even for directories without videos. */
internal object DirectVideoWalker {
    private val extensions = setOf("3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "mts", "ts", "webm", "wmv")
    fun isVideo(file: File) = file.extension.lowercase(Locale.ROOT) in extensions
    fun walk(root: File, recursive: Boolean, checkActive: () -> Unit, onVideo: (File) -> Unit) {
        walkWithProgress(root, recursive, checkActive, {}, onVideo)
    }
    fun walkWithProgress(root: File, recursive: Boolean, checkActive: () -> Unit, onDirectory: () -> Unit, onVideo: (File) -> Unit) {
        val pending = ArrayDeque<File>()
        val visited = hashSetOf<String>()
        val rootPath = try { root.canonicalPath } catch (_: java.io.IOException) { return }
        pending.add(root)
        while (pending.isNotEmpty()) {
            checkActive()
            val directory = pending.removeLast()
            val canonical = try { directory.canonicalPath } catch (_: java.io.IOException) { continue }
            if (canonical != rootPath && !canonical.startsWith(rootPath + File.separator)) continue
            if (!visited.add(canonical)) continue
            val children = try { directory.listFiles() } catch (_: SecurityException) { null } ?: continue
            for (child in children) {
                checkActive()
                if (child.isDirectory) {
                    val relative = child.relativeTo(root).invariantSeparatorsPath
                    if (relative.equals("Android/data", true) || relative.startsWith("Android/data/", true) ||
                        relative.equals("Android/obb", true) || relative.startsWith("Android/obb/", true)) continue
                    if (recursive) pending.add(child)
                } else if (child.isFile && isVideo(child)) onVideo(child)
            }
            onDirectory()
        }
    }
}
