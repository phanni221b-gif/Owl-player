package `is`.xyz.mpv

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

data class MediaVideo(
    val title: String,
    val path: String?,
    val uri: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long = 0L,
    val modifiedSeconds: Long = 0L
)

data class MediaFolder(
    val key: String,
    val name: String,
    val videos: MutableList<MediaVideo> = mutableListOf(),
    val storageLabel: String = ""
) {
    val newestModifiedSeconds: Long
        get() = videos.maxOfOrNull { it.modifiedSeconds } ?: 0L
}

object MediaLibrary {
    @Volatile
    var cache: List<MediaFolder> = emptyList()
        private set

    private val videoExtensions = setOf(
        "3gp", "avi", "flv", "m2ts", "m4v", "mkv", "mov", "mp4", "mpeg", "mpg", "mts", "ts", "webm", "wmv"
    )

    fun scan(context: Context): List<MediaFolder> {
        val grouped = linkedMapOf<String, MediaFolder>()
        val resolver = context.contentResolver

        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).toList()
        } else {
            listOf(MediaStore.VOLUME_EXTERNAL)
        }

        for (volume in volumes) {
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(volume)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.RELATIVE_PATH,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED
            )

            val storageLabel = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "저장소"
                volume == MediaStore.VOLUME_EXTERNAL_PRIMARY -> "내부 저장소"
                else -> "외장 저장소"
            }

            try {
                resolver.query(collection, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    val dataCol = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                    val relCol = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
                    val durationCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                    val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                    val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                    val sizeCol = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
                    val modifiedCol = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val id = if (idCol >= 0) cursor.getLong(idCol) else continue
                        val title = if (nameCol >= 0) cursor.getString(nameCol) ?: "Video" else "Video"
                        val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                        val relative = if (relCol >= 0) cursor.getString(relCol) else null

                        if (path != null) {
                            val ext = File(path).extension.lowercase(Locale.ROOT)
                            if (ext.isNotEmpty() && ext !in videoExtensions) continue
                        }

                        val parent = path?.let { File(it).parentFile }
                        val key = parent?.absolutePath ?: "$volume:${relative ?: "Videos"}"
                        val folderName = parent?.name?.takeIf { it.isNotBlank() }
                            ?: relative?.trim('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                            ?: "Videos"

                        val folder = grouped.getOrPut(key) {
                            MediaFolder(key = key, name = folderName, storageLabel = storageLabel)
                        }
                        folder.videos += MediaVideo(
                            title = title,
                            path = path,
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                            width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                            height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                            sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                            modifiedSeconds = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L
                        )
                    }
                }
            } catch (_: SecurityException) {
                // The caller will surface the permission UI; skip inaccessible volumes here.
            } catch (_: IllegalArgumentException) {
                // Some vendors expose a volume before its media provider is ready.
            }
        }

        // MediaStore on some Samsung/USB combinations can lag behind or omit removable media
        // completely. The all-storage build already asks for MANAGE_EXTERNAL_STORAGE, so use that
        // permission to supplement MediaStore with a direct read-only discovery pass over
        // non-primary mounted volumes. Playback still uses the normal mpv path handling.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            BuildConfig.FLAVOR == "allstorage" &&
            Environment.isExternalStorageManager()
        ) {
            addDirectExternalVolumes(context, grouped)
        }

        val result = grouped.values
            .onEach { it.videos.sortBy { video -> video.title.lowercase(Locale.getDefault()) } }
            .sortedBy { it.name.lowercase(Locale.getDefault()) }

        cache = result
        return result
    }

    private fun addDirectExternalVolumes(
        context: Context,
        grouped: LinkedHashMap<String, MediaFolder>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return
        val knownPaths = grouped.values
            .asSequence()
            .flatMap { it.videos.asSequence() }
            .mapNotNull { it.path?.takeIf(String::isNotBlank) }
            .map { File(it).absolutePath }
            .toMutableSet()

        storageManager.storageVolumes
            .asSequence()
            .filter { !it.isPrimary }
            .forEach { volume ->
                val root = try {
                    volume.directory
                } catch (_: Throwable) {
                    null
                } ?: return@forEach

                if (!root.exists() || !root.canRead()) return@forEach

                val label = try {
                    volume.getDescription(context).takeIf { it.isNotBlank() }
                } catch (_: Throwable) {
                    null
                } ?: "외장 저장소"

                scanDirectRoot(root, label, knownPaths, grouped)
            }
    }

    private fun scanDirectRoot(
        root: File,
        storageLabel: String,
        knownPaths: MutableSet<String>,
        grouped: LinkedHashMap<String, MediaFolder>
    ) {
        val pending = ArrayDeque<File>()
        val visited = hashSetOf<String>()
        pending.add(root)

        while (pending.isNotEmpty()) {
            val directory = pending.removeLast()
            val canonical = try {
                directory.canonicalPath
            } catch (_: Throwable) {
                directory.absolutePath
            }
            if (!visited.add(canonical)) continue

            val children = try {
                directory.listFiles()
            } catch (_: Throwable) {
                null
            } ?: continue

            for (child in children) {
                if (child.isDirectory) {
                    if (shouldSkipDirectDirectory(root, child)) continue
                    pending.add(child)
                    continue
                }

                if (!child.isFile) continue
                val extension = child.extension.lowercase(Locale.ROOT)
                if (extension !in videoExtensions) continue

                val absolutePath = child.absolutePath
                if (!knownPaths.add(absolutePath)) continue

                val parent = child.parentFile ?: root
                val key = parent.absolutePath
                val folderName = if (parent.absolutePath == root.absolutePath) {
                    storageLabel
                } else {
                    parent.name.takeIf { it.isNotBlank() } ?: storageLabel
                }
                val folder = grouped.getOrPut(key) {
                    MediaFolder(
                        key = key,
                        name = folderName,
                        storageLabel = storageLabel
                    )
                }
                folder.videos += MediaVideo(
                    title = child.name,
                    path = absolutePath,
                    uri = Uri.fromFile(child).toString(),
                    durationMs = 0L,
                    width = 0,
                    height = 0,
                    sizeBytes = try { child.length() } catch (_: Throwable) { 0L },
                    modifiedSeconds = try { child.lastModified() / 1000L } catch (_: Throwable) { 0L }
                )
            }
        }
    }

    private fun shouldSkipDirectDirectory(root: File, directory: File): Boolean {
        val relative = try {
            directory.relativeTo(root).invariantSeparatorsPath
        } catch (_: Throwable) {
            return false
        }
        return relative.equals("Android/data", ignoreCase = true) ||
            relative.startsWith("Android/data/", ignoreCase = true) ||
            relative.equals("Android/obb", ignoreCase = true) ||
            relative.startsWith("Android/obb/", ignoreCase = true)
    }
}
