package `is`.xyz.mpv

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import java.io.File

/** Permanent file operations used by Owl's MX-style multi-select UI. */
data class OwlFileOperationResult(
    val successCount: Int,
    val failureCount: Int
)

object OwlFileOperations {
    fun deleteVideos(context: Context, videos: List<MediaVideo>, checkActive: () -> Unit = {}): OwlFileOperationResult {
        var success = 0
        var failed = 0
        videos.forEach { video ->
            checkActive()
            val source = video.path?.takeIf { it.isNotBlank() }?.let(::File)
            val fileDeleted = source?.let { !it.exists() || it.delete() } ?: false
            val providerDeleted = if (!fileDeleted) deleteMediaRow(context, video.uri) else {
                deleteMediaRow(context, video.uri)
                false
            }
            if (fileDeleted || providerDeleted) success++ else failed++
        }
        return OwlFileOperationResult(success, failed)
    }

    fun deleteFolders(context: Context, folders: List<MediaFolder>, checkActive: () -> Unit = {}): OwlFileOperationResult {
        var success = 0
        var failed = 0
        folders.forEach { folder ->
            checkActive()
            val source = folderDirectory(folder)
            val deleted = source?.let { !it.exists() || CancellableFileWork.deleteTree(it, checkActive) } ?: false
            if (deleted) {
                folder.videos.forEach { checkActive(); deleteMediaRow(context, it.uri) }
                success++
            } else {
                failed++
            }
        }
        return OwlFileOperationResult(success, failed)
    }

    fun moveVideos(context: Context, videos: List<MediaVideo>, destinationDir: File, checkActive: () -> Unit = {}): OwlFileOperationResult {
        if (!ensureDirectory(destinationDir)) return OwlFileOperationResult(0, videos.size)
        var success = 0
        var failed = 0
        videos.forEach { video ->
            checkActive()
            val source = video.path?.takeIf { it.isNotBlank() }?.let(::File)
            if (source == null || !source.exists() || !source.isFile) {
                failed++
                return@forEach
            }
            if (sameDirectory(source.parentFile, destinationDir)) {
                success++
                return@forEach
            }
            val target = uniqueTarget(destinationDir, source)
            if (CancellableFileWork.move(source, target, checkActive)) {
                deleteMediaRow(context, video.uri)
                scan(context, listOf(target))
                success++
            } else {
                failed++
            }
        }
        return OwlFileOperationResult(success, failed)
    }

    fun moveFolders(context: Context, folders: List<MediaFolder>, destinationDir: File, checkActive: () -> Unit = {}): OwlFileOperationResult {
        if (!ensureDirectory(destinationDir)) return OwlFileOperationResult(0, folders.size)
        var success = 0
        var failed = 0
        folders.forEach { folder ->
            checkActive()
            val source = folderDirectory(folder)
            if (source == null || !source.exists() || !source.isDirectory) {
                failed++
                return@forEach
            }
            if (sameDirectory(source.parentFile, destinationDir)) {
                success++
                return@forEach
            }
            if (isInside(source, destinationDir)) {
                failed++
                return@forEach
            }
            val target = uniqueTarget(destinationDir, source)
            if (CancellableFileWork.move(source, target, checkActive)) {
                folder.videos.forEach { checkActive(); deleteMediaRow(context, it.uri) }
                val movedMedia = target.walkTopDown()
                    .onEach { checkActive() }
                    .filter { it.isFile && Utils.MEDIA_EXTENSIONS.contains(it.extension.lowercase()) }
                    .toList()
                scan(context, movedMedia)
                success++
            } else {
                failed++
            }
        }
        return OwlFileOperationResult(success, failed)
    }

    private fun folderDirectory(folder: MediaFolder): File? {
        val fromVideo = folder.videos.firstOrNull()?.path
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.parentFile
        if (fromVideo != null) return fromVideo
        val keyFile = File(folder.key)
        return keyFile.takeIf { it.isAbsolute }
    }

    private fun ensureDirectory(dir: File): Boolean =
        (dir.exists() && dir.isDirectory) || dir.mkdirs()

    private fun sameDirectory(a: File?, b: File): Boolean {
        if (a == null) return false
        return runCatching { a.canonicalFile == b.canonicalFile }.getOrDefault(false)
    }

    private fun isInside(sourceDir: File, candidate: File): Boolean {
        return runCatching {
            val sourcePath = sourceDir.canonicalPath.trimEnd(File.separatorChar) + File.separator
            val candidatePath = candidate.canonicalPath.trimEnd(File.separatorChar) + File.separator
            candidatePath.startsWith(sourcePath)
        }.getOrDefault(false)
    }

    private fun uniqueTarget(destinationDir: File, source: File): File {
        var candidate = File(destinationDir, source.name)
        if (!candidate.exists()) return candidate
        var index = 1
        while (candidate.exists()) {
            val name = if (source.isDirectory || source.extension.isBlank()) {
                "${source.name} ($index)"
            } else {
                "${source.nameWithoutExtension} ($index).${source.extension}"
            }
            candidate = File(destinationDir, name)
            index++
        }
        return candidate
    }

    private fun deleteMediaRow(context: Context, uriString: String): Boolean {
        return runCatching {
            context.contentResolver.delete(Uri.parse(uriString), null, null) > 0
        }.getOrDefault(false)
    }

    private fun scan(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        MediaScannerConnection.scanFile(
            context,
            files.map { it.absolutePath }.toTypedArray(),
            null,
            null
        )
    }
}
