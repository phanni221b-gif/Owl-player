package `is`.xyz.mpv

import java.io.File
import java.util.ArrayDeque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** Only immutable directory batches cross the worker boundary. An uninterruptible syscall cannot publish. */
internal class BoundedVideoWalker(private val budgetMs: Long = 8_000,
    private val workers: ProviderQueryRunner = ProviderQueryRunner(2, "OwlDirectoryRead")) {
    data class Video(val file: File, val size: Long, val modified: Long)
    data class Batch(val canonical: String, val directories: List<File>, val videos: List<Video>,
        val uninspected: List<File>? = null)
    suspend fun walk(root: File, recursive: Boolean, onDirectory: () -> Unit, onVideo: (Video) -> Unit,
        event: (String) -> Unit = {}, inspect: (File) -> Batch? = ::inspectDirectory) {
        val pending = ArrayDeque<File>(); val visited = hashSetOf<String>()
        var rootPath: String? = null
        pending.add(root)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val directory = pending.removeLast()
            val batch = try {
                withTimeoutOrNull(budgetMs) { workers.query({}) { inspect(directory) } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { event("direct directory unavailable; partial retained"); null }
            if (batch == null) { event("direct directory timeout/unavailable; partial retained"); continue }
            currentCoroutineContext().ensureActive()
            if (rootPath == null) rootPath = batch.canonical
            if (batch.canonical != rootPath && !batch.canonical.startsWith(rootPath + File.separator)) continue
            if (!visited.add(batch.canonical)) continue
            fun publish(part: Batch) {
                for (video in part.videos) onVideo(video)
                if (recursive) for (child in part.directories) {
                    val relative = child.relativeTo(root).invariantSeparatorsPath
                    if (relative.equals("Android/data", true) || relative.startsWith("Android/data/", true) ||
                        relative.equals("Android/obb", true) || relative.startsWith("Android/obb/", true)) continue
                    pending.add(child)
                }
                onDirectory()
            }
            if (batch.uninspected == null) publish(batch)
            else for (chunk in batch.uninspected.chunked(128)) {
                currentCoroutineContext().ensureActive()
                val part = try {
                    withTimeoutOrNull(budgetMs) { workers.query({}) { inspectChildren(batch.canonical, chunk) } }
                } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { null }
                currentCoroutineContext().ensureActive()
                if (part != null) publish(part) else event("direct metadata batch timeout/unavailable; partial retained")
            }
        }
    }
    companion object {
        private fun inspectDirectory(directory: File): Batch? {
            val canonical = directory.canonicalPath
            val children = directory.listFiles() ?: return null
            return Batch(canonical, emptyList(), emptyList(), children.toList())
        }
        private fun inspectChildren(canonical: String, children: List<File>): Batch {
            val directories = mutableListOf<File>(); val videos = mutableListOf<Video>()
            for (child in children) {
                if (Thread.currentThread().isInterrupted) throw CancellationException("directory cancelled")
                if (child.isDirectory) directories.add(child)
                else if (child.isFile && DirectVideoWalker.isVideo(child)) videos.add(Video(child, child.length(), child.lastModified()))
            }
            return Batch(canonical, directories, videos)
        }
    }
}
