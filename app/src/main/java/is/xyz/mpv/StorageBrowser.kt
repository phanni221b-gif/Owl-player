package `is`.xyz.mpv

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class StorageRoot(
    val label: String,
    val path: String,
    val primary: Boolean
)

enum class BrowserEntryKind { STORAGE, DIRECTORY, VIDEO }

data class BrowserEntry(
    val kind: BrowserEntryKind,
    val name: String,
    val path: String,
    val subtitle: String,
    val storageLabel: String,
    val video: MediaVideo? = null
)

data class BrowserSearchResult(
    val entries: List<BrowserEntry>,
    val truncated: Boolean
)

/** Read-only storage browser helpers. Playback discovery remains owned by MediaLibrary. */
internal object StorageBrowser {
    const val MAX_SEARCH_RESULTS = 2_000

    fun roots(context: Context): List<StorageRoot> {
        val result = linkedMapOf<String, StorageRoot>()
        val primary = canonicalPath(Environment.getExternalStorageDirectory())
        if (primary != null) result[primary] = StorageRoot("내부 저장소", primary, true)

        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.storageVolumes.forEach { volume ->
                val state = runCatching { volume.state }.getOrNull()
                if (state != Environment.MEDIA_MOUNTED && state != Environment.MEDIA_MOUNTED_READ_ONLY) return@forEach
                val directory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { volume.directory }.getOrNull()
                } else null
                val path = canonicalPath(directory ?: if (volume.isPrimary) Environment.getExternalStorageDirectory() else null)
                    ?: return@forEach
                if (!File(path).exists() || !File(path).canRead()) return@forEach
                val label = if (volume.isPrimary) "내부 저장소" else runCatching {
                    volume.getDescription(context).takeIf { it.isNotBlank() }
                }.getOrNull() ?: "외장 저장소"
                result[path] = StorageRoot(label, path, volume.isPrimary)
            }
        }

        return result.values.sortedWith(
            compareByDescending<StorageRoot> { it.primary }
                .thenBy { it.label.lowercase(Locale.getDefault()) }
        )
    }

    fun rootEntries(roots: List<StorageRoot>): List<BrowserEntry> = roots.map {
        BrowserEntry(BrowserEntryKind.STORAGE, it.label, it.path, it.path, it.label)
    }

    fun filterFolders(folders: List<MediaFolder>, rootPath: String?): List<MediaFolder> {
        if (rootPath.isNullOrBlank()) return folders
        return folders.filter { folder ->
            val path = folder.key.takeIf { it.startsWith(File.separator) }
                ?: folder.videos.firstNotNullOfOrNull { it.path }?.let { File(it).parent }
            path != null && containsPath(rootPath, path)
        }
    }

    fun rootForPath(roots: List<StorageRoot>, path: String): StorageRoot? =
        roots.filter { containsPath(it.path, path) }.maxByOrNull { it.path.length }

    suspend fun listDirectory(root: StorageRoot, path: String): List<BrowserEntry> = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        val directory = File(path)
        val canonical = canonicalPath(directory) ?: return@withContext emptyList()
        if (!containsPath(root.path, canonical) || !directory.isDirectory || !directory.canRead()) return@withContext emptyList()
        val children = try { directory.listFiles() } catch (_: SecurityException) { null } ?: return@withContext emptyList()
        val entries = ArrayList<BrowserEntry>(children.size)
        for (child in children) {
            currentCoroutineContext().ensureActive()
            val childPath = canonicalPath(child) ?: continue
            if (!containsPath(root.path, childPath) || restricted(root.path, childPath)) continue
            when {
                child.isDirectory && child.canRead() -> entries += BrowserEntry(
                    BrowserEntryKind.DIRECTORY, child.name.ifBlank { childPath }, childPath,
                    "폴더", root.label
                )
                child.isFile && DirectVideoWalker.isVideo(child) -> entries += videoEntry(child, root, "동영상")
            }
        }
        entries.sortedWith(compareBy<BrowserEntry> { if (it.kind == BrowserEntryKind.VIDEO) 1 else 0 }
            .thenBy { it.name.lowercase(Locale.getDefault()) })
    }

    suspend fun search(roots: List<StorageRoot>, basePath: String?, rawQuery: String): BrowserSearchResult =
        withContext(Dispatchers.IO) {
            val query = rawQuery.trim()
            if (query.isEmpty()) return@withContext BrowserSearchResult(emptyList(), false)
            val targets = if (basePath.isNullOrBlank()) roots else listOfNotNull(rootForPath(roots, basePath))
            val output = ArrayList<BrowserEntry>()
            var truncated = false

            fun add(entry: BrowserEntry): Boolean {
                if (output.size >= MAX_SEARCH_RESULTS) {
                    truncated = true
                    return false
                }
                output += entry
                return true
            }

            for (root in targets) {
                currentCoroutineContext().ensureActive()
                val startPath = if (basePath.isNullOrBlank()) root.path else basePath
                val start = File(startPath)
                val canonicalStart = canonicalPath(start) ?: continue
                if (!containsPath(root.path, canonicalStart) || !start.isDirectory || !start.canRead()) continue
                val pending = ArrayDeque<File>()
                val visited = hashSetOf<String>()
                pending.add(start)

                while (pending.isNotEmpty() && !truncated) {
                    currentCoroutineContext().ensureActive()
                    val directory = pending.removeLast()
                    val directoryPath = canonicalPath(directory) ?: continue
                    if (!containsPath(root.path, directoryPath) || !visited.add(directoryPath)) continue
                    val children = try { directory.listFiles() } catch (_: SecurityException) { null } ?: continue
                    for (child in children) {
                        currentCoroutineContext().ensureActive()
                        val childPath = canonicalPath(child) ?: continue
                        if (!containsPath(root.path, childPath) || restricted(root.path, childPath)) continue
                        if (child.isDirectory && child.canRead()) {
                            if (child.name.contains(query, ignoreCase = true)) {
                                if (!add(BrowserEntry(
                                        BrowserEntryKind.DIRECTORY,
                                        child.name.ifBlank { childPath },
                                        childPath,
                                        relativeSubtitle(root, child.parentFile),
                                        root.label
                                    ))) break
                            }
                            pending.add(child)
                        } else if (child.isFile && DirectVideoWalker.isVideo(child) &&
                            child.name.contains(query, ignoreCase = true)) {
                            if (!add(videoEntry(child, root, relativeSubtitle(root, child.parentFile)))) break
                        }
                    }
                }
                if (truncated) break
            }

            BrowserSearchResult(
                output.sortedWith(compareBy<BrowserEntry> { it.name.lowercase(Locale.getDefault()) }
                    .thenBy { it.path.lowercase(Locale.getDefault()) }),
                truncated
            )
        }

    internal fun containsPath(rootPath: String, candidatePath: String): Boolean {
        val root = rootPath.trimEnd(File.separatorChar)
        val candidate = candidatePath.trimEnd(File.separatorChar)
        return candidate == root || candidate.startsWith(root + File.separator)
    }

    private fun videoEntry(file: File, root: StorageRoot, subtitle: String): BrowserEntry {
        val path = file.absolutePath
        val video = MediaVideo(
            title = file.name,
            path = path,
            uri = Uri.fromFile(file).toString(),
            durationMs = 0L,
            width = 0,
            height = 0,
            sizeBytes = file.length(),
            modifiedSeconds = file.lastModified() / 1000L
        )
        return BrowserEntry(BrowserEntryKind.VIDEO, file.name, path, subtitle, root.label, video)
    }

    private fun relativeSubtitle(root: StorageRoot, parent: File?): String {
        val parentPath = canonicalPath(parent) ?: return root.label
        val relative = if (parentPath == root.path) "" else parentPath.removePrefix(root.path).trimStart(File.separatorChar)
        return if (relative.isBlank()) root.label else "${root.label} · $relative"
    }

    private fun restricted(rootPath: String, candidatePath: String): Boolean {
        val relative = candidatePath.removePrefix(rootPath).trimStart(File.separatorChar)
            .replace(File.separatorChar, '/').lowercase(Locale.ROOT)
        return relative == "android/data" || relative.startsWith("android/data/") ||
            relative == "android/obb" || relative.startsWith("android/obb/")
    }

    private fun canonicalPath(file: File?): String? = if (file == null) null else runCatching {
        file.canonicalPath
    }.getOrNull()
}
