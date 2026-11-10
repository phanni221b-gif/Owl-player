package `is`.xyz.mpv

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/**
 * Result of a permanent file operation. [missingCount] are entries whose file no longer existed
 * (already removed elsewhere or volume detached); they are reported separately instead of being
 * counted as a successful deletion. [protectedCount] are refused volume roots / shared directories.
 */
data class OwlFileOperationResult(
    val successCount: Int,
    val failureCount: Int,
    val missingCount: Int = 0,
    val protectedCount: Int = 0
) {
    fun message(action: String): String = buildList {
        add("${successCount}개 $action 완료")
        if (failureCount > 0) add("${failureCount}개 실패")
        if (missingCount > 0) add("${missingCount}개는 파일을 찾을 수 없음")
        if (protectedCount > 0) add("${protectedCount}개는 저장소 보호 폴더라 제외")
    }.joinToString(" · ")
}

/** Media-store side effects, injectable so the file rules are testable without a device. */
internal interface OwlMediaIndex {
    fun deleteRow(uri: String): Boolean
    fun scan(files: List<File>)
}

/** Permanent file operations used by Owl's multi-select UI. Only user-selected videos are touched. */
object OwlFileOperations {
    fun deleteVideos(context: Context, videos: List<MediaVideo>, checkActive: () -> Unit = {}) =
        deleteVideos(index(context), videos, checkActive)

    fun deleteFolders(context: Context, folders: List<MediaFolder>, checkActive: () -> Unit = {}) =
        deleteFolders(index(context), volumeRoots(context), folders, checkActive)

    fun moveVideos(context: Context, videos: List<MediaVideo>, destinationDir: File, checkActive: () -> Unit = {}) =
        moveVideos(index(context), videos, destinationDir, checkActive)

    fun moveFolders(context: Context, folders: List<MediaFolder>, destinationDir: File, checkActive: () -> Unit = {}) =
        moveFolders(index(context), volumeRoots(context), folders, destinationDir, checkActive)

    /** Returns the renamed file, or a Korean user-facing error message. */
    fun renameVideo(context: Context, video: MediaVideo, newName: String): Result<File> =
        renameVideo(index(context), video, newName)

    fun renameFolder(context: Context, folder: MediaFolder, newName: String): Result<File> =
        renameFolder(index(context), volumeRoots(context), folder, newName)

    internal fun deleteVideos(index: OwlMediaIndex, videos: List<MediaVideo>, checkActive: () -> Unit): OwlFileOperationResult {
        var success = 0
        var failed = 0
        var missing = 0
        videos.forEach { video ->
            checkActive()
            when (deleteVideo(index, video)) {
                Outcome.DONE -> success++
                Outcome.MISSING -> missing++
                Outcome.FAILED -> failed++
            }
        }
        return OwlFileOperationResult(success, failed, missing)
    }

    private enum class Outcome { DONE, MISSING, FAILED }

    private fun deleteVideo(index: OwlMediaIndex, video: MediaVideo): Outcome {
        val source = video.path?.takeIf { it.isNotBlank() }?.let(::File)
        if (source == null) {
            // Content-only entry: the provider deletes the actual file it describes.
            return if (index.deleteRow(video.uri)) Outcome.DONE else Outcome.FAILED
        }
        if (!source.exists()) return Outcome.MISSING
        if (!source.isFile) return Outcome.FAILED // never recurse from a video entry
        if (!source.delete()) {
            // Scoped storage: the media provider may be allowed to delete what File cannot.
            index.deleteRow(video.uri)
        } else {
            index.deleteRow(video.uri)
        }
        // Success is what actually happened on storage, not what an API returned.
        return if (source.exists()) Outcome.FAILED else Outcome.DONE
    }

    /**
     * Deletes the videos Owl lists for each folder (direct children only), then removes the directory
     * only if it became empty. Non-video files and subfolders are never deleted, and a volume root or
     * shared top-level directory is never removed even when empty.
     */
    internal fun deleteFolders(index: OwlMediaIndex, roots: List<File>, folders: List<MediaFolder>, checkActive: () -> Unit): OwlFileOperationResult {
        var success = 0
        var failed = 0
        var missing = 0
        folders.forEach { folder ->
            checkActive()
            val dir = folderDirectory(folder)
            val videos = if (dir != null) OwlFileSafety.directVideos(folder, dir) else folder.videos
            val result = deleteVideos(index, videos, checkActive)
            missing += result.missingCount
            if (result.failureCount > 0 || (result.successCount == 0 && result.missingCount == 0 && videos.isNotEmpty())) {
                failed++
                return@forEach
            }
            if (dir != null && dir.isDirectory && !OwlFileSafety.isProtectedDirectory(dir, roots)) {
                if (dir.list()?.isEmpty() == true) dir.delete()
            }
            success++
        }
        return OwlFileOperationResult(success, failed, missing)
    }

    internal fun moveVideos(index: OwlMediaIndex, videos: List<MediaVideo>, destinationDir: File, checkActive: () -> Unit): OwlFileOperationResult {
        if (!ensureDirectory(destinationDir)) return OwlFileOperationResult(0, videos.size)
        var success = 0
        var failed = 0
        var missing = 0
        videos.forEach { video ->
            checkActive()
            val source = video.path?.takeIf { it.isNotBlank() }?.let(::File)
            if (source == null) { failed++; return@forEach }
            if (!source.exists()) { missing++; return@forEach }
            if (!source.isFile) { failed++; return@forEach }
            if (sameDirectory(source.parentFile, destinationDir)) {
                success++
                return@forEach
            }
            val target = uniqueTarget(destinationDir, source)
            if (CancellableFileWork.move(source, target, checkActive)) {
                index.deleteRow(video.uri)
                index.scan(listOf(target))
                success++
            } else {
                failed++
            }
        }
        return OwlFileOperationResult(success, failed, missing)
    }

    internal fun moveFolders(index: OwlMediaIndex, roots: List<File>, folders: List<MediaFolder>, destinationDir: File, checkActive: () -> Unit): OwlFileOperationResult {
        if (!ensureDirectory(destinationDir)) return OwlFileOperationResult(0, folders.size)
        var success = 0
        var failed = 0
        var missing = 0
        var refused = 0
        folders.forEach { folder ->
            checkActive()
            val source = folderDirectory(folder)
            if (source == null) { failed++; return@forEach }
            if (!source.exists()) { missing++; return@forEach }
            if (!source.isDirectory) { failed++; return@forEach }
            if (OwlFileSafety.isProtectedDirectory(source, roots)) { refused++; return@forEach }
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
                folder.videos.forEach { checkActive(); index.deleteRow(it.uri) }
                index.scan(mediaUnder(target, checkActive))
                success++
            } else {
                failed++
            }
        }
        return OwlFileOperationResult(success, failed, missing, refused)
    }

    internal fun renameVideo(index: OwlMediaIndex, video: MediaVideo, newName: String): Result<File> {
        val source = video.path?.takeIf { it.isNotBlank() }?.let(::File)
            ?: return Result.failure(IllegalStateException("이 저장소의 파일은 이름을 바꿀 수 없습니다."))
        if (!source.isFile) return Result.failure(IllegalStateException("파일을 찾을 수 없습니다."))
        val name = OwlFileSafety.videoTargetName(source, newName)
            ?: return Result.failure(IllegalArgumentException("사용할 수 없는 이름입니다."))
        val target = File(source.parentFile, name)
        if (target.name == source.name) return Result.success(source)
        val caseOnly = target.name.equals(source.name, ignoreCase = true)
        if (target.exists() && !caseOnly) return Result.failure(IllegalStateException("같은 이름의 파일이 이미 있습니다."))
        val renamed = if (caseOnly) {
            // Case-insensitive volumes (exFAT/FAT external drives) need an intermediate name.
            val temporary = File(source.parentFile, ".owl-rename-" + System.nanoTime())
            source.renameTo(temporary) && (temporary.renameTo(target) || run { temporary.renameTo(source); false })
        } else source.renameTo(target)
        if (!renamed) return Result.failure(IllegalStateException("이름을 바꾸지 못했습니다. 권한을 확인해주세요."))
        index.deleteRow(video.uri)
        index.scan(listOf(target))
        return Result.success(target)
    }

    internal fun renameFolder(index: OwlMediaIndex, roots: List<File>, folder: MediaFolder, newName: String): Result<File> {
        val source = folderDirectory(folder)?.takeIf { it.isDirectory }
            ?: return Result.failure(IllegalStateException("폴더를 찾을 수 없습니다."))
        if (OwlFileSafety.isProtectedDirectory(source, roots))
            return Result.failure(IllegalStateException("저장소 기본 폴더는 이름을 바꿀 수 없습니다."))
        val name = OwlFileSafety.cleanName(newName)
            ?: return Result.failure(IllegalArgumentException("사용할 수 없는 이름입니다."))
        val target = File(source.parentFile, name)
        if (target.name == source.name) return Result.success(source)
        if (target.exists()) return Result.failure(IllegalStateException("같은 이름의 폴더가 이미 있습니다."))
        if (!source.renameTo(target)) return Result.failure(IllegalStateException("이름을 바꾸지 못했습니다. 권한을 확인해주세요."))
        folder.videos.forEach { index.deleteRow(it.uri) }
        index.scan(mediaUnder(target) {})
        return Result.success(target)
    }

    internal fun folderDirectory(folder: MediaFolder): File? {
        val fromVideo = folder.videos.firstOrNull()?.path
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.parentFile
        if (fromVideo != null) return fromVideo
        val keyFile = File(folder.key)
        return keyFile.takeIf { it.isAbsolute }
    }

    private fun mediaUnder(dir: File, checkActive: () -> Unit): List<File> = dir.walkTopDown()
        .onEach { checkActive() }
        .filter { it.isFile && Utils.MEDIA_EXTENSIONS.contains(it.extension.lowercase()) }
        .toList()

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

    private fun index(context: Context): OwlMediaIndex = object : OwlMediaIndex {
        override fun deleteRow(uri: String): Boolean {
            if (!uri.startsWith("content://")) return false
            return runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) > 0 }.getOrDefault(false)
        }
        override fun scan(files: List<File>) {
            if (files.isEmpty()) return
            MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null, null)
        }
    }

    internal fun volumeRoots(context: Context): List<File> {
        val roots = mutableListOf(Environment.getExternalStorageDirectory())
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                (context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager)?.storageVolumes
                    ?.mapNotNullTo(roots) { it.directory }
            }
        }
        return roots
    }
}
