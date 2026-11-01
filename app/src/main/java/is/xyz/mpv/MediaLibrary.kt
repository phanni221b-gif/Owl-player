package `is`.xyz.mpv

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object MediaLibrary {
    @Volatile
    var cache: List<MediaFolder> = emptyList()
        private set

    private val scanMutex = Mutex()
    @Volatile var scanning = false
        private set
    private val directCache = mutableMapOf<String, Pair<Long, List<MediaFolder>>>()

    suspend fun scan(context: Context, force: Boolean = false,
        onUpdate: (List<MediaFolder>) -> Unit = {}): List<MediaFolder> = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]!!
            scanning = true
            try { scanBlocking(context.applicationContext, job, force, null, onUpdate) }
            finally { scanning = false }
        }
    }

    suspend fun refreshFolder(context: Context, key: String): MediaFolder? = scanMutex.withLock {
        withContext(Dispatchers.IO) {
            val result = scanBlocking(context.applicationContext, currentCoroutineContext()[Job]!!,
                true, key) {}
            result.firstOrNull { it.key == key }
        }
    }

    private fun snapshot(grouped: Map<String, MediaFolder>) = MediaRules.snapshot(grouped)

    private fun scanBlocking(context: Context, job: Job, force: Boolean, folderKey: String?,
        onUpdate: (List<MediaFolder>) -> Unit): List<MediaFolder> {
        val grouped = linkedMapOf<String, MediaFolder>()
        val indexedPaths = hashSetOf<String>()
        val resolver = context.contentResolver

        val volumes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.getExternalVolumeNames(context).toList()
        } else {
            listOf(MediaStore.VOLUME_EXTERNAL)
        }

        for (volume in volumes) {
            job.ensureActive()
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
                resolver.query(collection, projection,
                    folderKey?.let { if (it.startsWith("/")) "${MediaStore.Video.Media.DATA} LIKE ? ESCAPE '\\'" else "${MediaStore.Video.Media.RELATIVE_PATH} = ?" },
                    folderKey?.let { arrayOf(if (it.startsWith("/")) it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%" else it.substringAfter(':')) }, null)?.use { cursor ->
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
                        job.ensureActive()
                        val id = if (idCol >= 0) cursor.getLong(idCol) else continue
                        val title = if (nameCol >= 0) cursor.getString(nameCol) ?: "Video" else "Video"
                        val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                        val relative = if (relCol >= 0) cursor.getString(relCol) else null

                        if (path != null) {
                            val ext = File(path).extension.lowercase(Locale.ROOT)
                            if (ext.isNotEmpty() && !DirectVideoWalker.isVideo(File(path))) continue
                        }

                        val parent = path?.let { File(it).parentFile }
                        val key = parent?.absolutePath ?: "$volume:${relative ?: "Videos"}"
                        if (folderKey != null && key != folderKey) continue
                        val folderName = parent?.name?.takeIf { it.isNotBlank() }
                            ?: relative?.trim('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                            ?: "Videos"

                        MediaRules.add(grouped, key, folderName, storageLabel, MediaVideo(
                            title = title,
                            path = path,
                            uri = ContentUris.withAppendedId(collection, id).toString(),
                            durationMs = if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                            width = if (widthCol >= 0) cursor.getInt(widthCol) else 0,
                            height = if (heightCol >= 0) cursor.getInt(heightCol) else 0,
                            sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                            modifiedSeconds = if (modifiedCol >= 0) cursor.getLong(modifiedCol) else 0L
                        ), indexedPaths)
                    }
                }
            } catch (_: SecurityException) {
                // The caller will surface the permission UI; skip inaccessible volumes here.
            } catch (_: IllegalArgumentException) {
                // Some vendors expose a volume before its media provider is ready.
            }
        }

        job.ensureActive()
        if (folderKey == null) {
            cache = snapshot(grouped)
            onUpdate(cache)
        }

        // Reconcile all removable volumes, including partially indexed ones.
        // Publish MediaStore first; traversal is cancellable and emits immutable snapshots.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            BuildConfig.FLAVOR == "allstorage" &&
            Environment.isExternalStorageManager()
        ) {
            if (folderKey == null) {
                addDirectExternalVolumes(context, grouped, job, force, onUpdate)
            } else if (folderKey.startsWith("/")) {
                val known = grouped.values.flatMap { it.videos }.mapNotNull { it.path }.toMutableSet()
                scanDirectRoot(File(folderKey), cache.firstOrNull { it.key == folderKey }?.storageLabel.orEmpty(), known, grouped, job, false) {}
                directCache.clear()
            }
        }

        job.ensureActive()
        val result = snapshot(grouped)
        cache = if (folderKey == null) result else cache.filter { it.key != folderKey } + result
        return result
    }

    private fun addDirectExternalVolumes(
        context: Context,
        grouped: LinkedHashMap<String, MediaFolder>,
        job: Job, force: Boolean, onUpdate: (List<MediaFolder>) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return
        val knownPaths = grouped.values
            .asSequence()
            .flatMap { it.videos.asSequence() }
            .mapNotNull { it.path?.takeIf { path -> path.isNotBlank() } }
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

                job.ensureActive()

                val label = try {
                    volume.getDescription(context).takeIf { it.isNotBlank() }
                } catch (_: Throwable) {
                    null
                } ?: "외장 저장소"

                val cached = directCache[root.absolutePath]
                if (!force && cached != null && android.os.SystemClock.elapsedRealtime() - cached.first < 300_000L) {
                    cached.second.forEach { folder -> folder.videos.forEach { video ->
                        if (knownPaths.add(video.path ?: video.uri)) {
                            grouped.getOrPut(folder.key) { folder.copy(videos = mutableListOf()) }.videos.add(video)
                        }
                    } }
                } else {
                    scanDirectRoot(root, label, knownPaths, grouped, job, true) {
                        job.ensureActive()
                        cache = snapshot(grouped)
                        onUpdate(cache)
                    }
                    directCache[root.absolutePath] = android.os.SystemClock.elapsedRealtime() to
                        snapshot(grouped).filter { it.key == root.absolutePath || it.key.startsWith(root.absolutePath + "/") }
                }
            }
    }

    private fun scanDirectRoot(
        root: File,
        storageLabel: String,
        knownPaths: MutableSet<String>,
        grouped: LinkedHashMap<String, MediaFolder>, job: Job, recursive: Boolean, onUpdate: () -> Unit
    ) {
        var lastUpdate = android.os.SystemClock.elapsedRealtime()
        DirectVideoWalker.walk(root, recursive, { job.ensureActive() }) { child ->
            val absolutePath = child.absolutePath
            if (absolutePath !in knownPaths) {
                val parent = child.parentFile ?: root
                val key = parent.absolutePath
                val folderName = if (parent.absolutePath == root.absolutePath) storageLabel
                    else parent.name.takeIf { it.isNotBlank() } ?: storageLabel
                MediaRules.add(grouped, key, folderName, storageLabel, MediaVideo(
                    title = child.name, path = absolutePath, uri = Uri.fromFile(child).toString(),
                    durationMs = 0L, width = 0, height = 0,
                    sizeBytes = child.length(), modifiedSeconds = child.lastModified() / 1000L
                ), knownPaths)
            }
            if (android.os.SystemClock.elapsedRealtime() - lastUpdate > 500L) {
                onUpdate()
                lastUpdate = android.os.SystemClock.elapsedRealtime()
            }
        }
    }
}
