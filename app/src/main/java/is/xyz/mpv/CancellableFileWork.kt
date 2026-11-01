package `is`.xyz.mpv

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Cross-volume fallback never deletes the source until the complete copy has succeeded. */
internal object CancellableFileWork {
    fun deleteTree(file: File, check: () -> Unit): Boolean {
        check()
        if (!file.exists()) return true
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            for (child in children) if (!deleteTree(child, check)) return false
        }
        check()
        return file.delete()
    }

    fun move(source: File, target: File, check: () -> Unit): Boolean {
        check()
        target.parentFile?.mkdirs()
        if (source.renameTo(target)) return true
        return copyThenMove(source, target, check)
    }

    internal fun copyThenMove(source: File, target: File, check: () -> Unit): Boolean {
        if (target.exists()) return false
        var copyComplete = false
        try {
            copy(source, target, check)
            copyComplete = true
            check()
            // If interrupted during source cleanup, retain the complete destination copy.
            return deleteTree(source, check)
        } catch (cancel: CancellationException) {
            if (!copyComplete) runCatching { deleteTree(target) {} }
            throw cancel
        } catch (_: Exception) {
            if (!copyComplete) deleteTree(target) {}
            return false
        }
    }

    private fun copy(source: File, target: File, check: () -> Unit) {
        check()
        if (source.isDirectory) {
            if (!target.mkdir()) throw IOException("Cannot create destination directory")
            val children = source.listFiles() ?: throw IOException("Source directory unavailable")
            children.forEach { copy(it, File(target, it.name), check) }
        } else {
            if (!target.createNewFile()) throw IOException("Destination already exists")
            source.inputStream().use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    check()
                    output.write(buffer, 0, count)
                }
            } }
            if (target.length() != source.length()) throw IOException("Copy size mismatch")
        }
        target.setLastModified(source.lastModified())
    }
}
