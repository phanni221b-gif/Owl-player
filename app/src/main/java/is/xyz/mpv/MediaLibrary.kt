package `is`.xyz.mpv

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex

object MediaLibrary {
    @Volatile var cache: List<MediaFolder> = emptyList()
        private set
    private val ownership = ScanOwnership()
    private val traversal = Mutex()
    private val primaryQueries = ProviderQueryRunner(2, "OwlPrimaryQuery")
    private val externalQueries = ProviderQueryRunner(2, "OwlExternalQuery")
    private val cacheLock = Any()
    private val directCache = mutableMapOf<String, Pair<Long, List<MediaFolder>>>()
    val scanning: Boolean get() = ownership.isActive()
    private const val PROVIDER_BUDGET_MS = 8_000L

    fun cancelActive() = ownership.cancel()
    fun resetSession() {
        ownership.cancel()
        synchronized(cacheLock) { directCache.clear(); cache = emptyList() }
    }

    suspend fun scan(context: Context, force: Boolean = false,
        onUpdate: (List<MediaFolder>) -> Unit = {}): List<MediaFolder> {
        val ticket = ownership.begin(currentCoroutineContext()[Job]!!)
        val app = context.applicationContext
        try {
            fun publish(folders: List<MediaFolder>) {
                ownership.publish(ticket) { cache = folders; onUpdate(folders) }
            }
            val result = DiscoveryPipeline.discover(
                primary = { queryVolume(app, primaryVolume(), null, false) },
                volumes = { removableVolumes(app) },
                external = { queryVolume(app, it, null, true) },
                reconcile = { emit -> reconcile(app, force, null, ticket, emit) },
                publish = ::publish
            )
            publish(result)
            return result
        } finally { ownership.finish(ticket) }
    }

    suspend fun refreshFolder(context: Context, key: String): MediaFolder? {
        val ticket = ownership.begin(currentCoroutineContext()[Job]!!)
        try {
            // A folder refresh never calls scan() or enumerates unrelated provider volumes.
            val cached = cache.firstOrNull { it.key == key }
            val volume = cached?.videos?.firstNotNullOfOrNull { video ->
                val uri = Uri.parse(video.uri)
                if (uri.scheme == "content" && Build.VERSION.SDK_INT >= 29)
                    runCatching { MediaStore.getVolumeName(uri) }.getOrNull() else null
            } ?: if (key.startsWith("/") && !key.startsWith(Environment.getExternalStorageDirectory().absolutePath + "/") &&
                key.startsWith("/storage/")) key.removePrefix("/storage/").substringBefore('/').lowercase(Locale.ROOT)
                else if (key.contains(':') && !key.startsWith("/")) key.substringBefore(':') else primaryVolume()
            val result = DiscoveryPipeline.folder(key,
                query = { scoped -> queryVolume(context.applicationContext, volume, scoped, volume != primaryVolume()) },
                reconcile = { scoped, emit -> reconcile(context.applicationContext, true, scoped, ticket, emit) })
            ownership.publish(ticket) { cache = cache.filter { it.key != key } + listOfNotNull(result) }
            return result
        } finally { ownership.finish(ticket) }
    }

    private fun primaryVolume() = if (Build.VERSION.SDK_INT >= 29) MediaStore.VOLUME_EXTERNAL_PRIMARY else MediaStore.VOLUME_EXTERNAL

    private suspend fun removableVolumes(context: Context): List<String> =
        withTimeoutOrNull(PROVIDER_BUDGET_MS) {
            externalQueries.query({}) {
                if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context).filter { it != primaryVolume() }
                else emptyList()
            }
        } ?: emptyList()

    private suspend fun queryVolume(context: Context, volume: String, folderKey: String?, external: Boolean): List<MediaFolder> {
        val signal = CancellationSignal()
        val start = SystemClock.elapsedRealtime()
        return try {
            val result = withTimeoutOrNull(PROVIDER_BUDGET_MS) {
                (if (external) externalQueries else primaryQueries).query({ signal.cancel() }) {
                    queryBlocking(context, volume, folderKey, signal)
                }
            }
            Log.i("OwlMediaScan", "volume=" + volume + " ms=" + (SystemClock.elapsedRealtime() - start) +
                " outcome=" + if (result == null) "timeout" else "ok")
            result ?: emptyList()
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (error: Exception) {
            Log.w("OwlMediaScan", "Skipping provider volume " + volume, error)
            emptyList()
        }
    }

    private fun queryBlocking(context: Context, volume: String, folderKey: String?, signal: CancellationSignal): List<MediaFolder> {
        val grouped = linkedMapOf<String, MediaFolder>()
        val indexedPaths = hashSetOf<String>()
        signal.throwIfCanceled()
        val collection = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(volume)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = listOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATA, MediaStore.Video.Media.DURATION, MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED)
            .let { if (Build.VERSION.SDK_INT >= 29) it + MediaStore.Video.Media.RELATIVE_PATH else it }.toTypedArray()
        val storageLabel = if (volume == primaryVolume()) "내부 저장소" else "외장 저장소"
        val selection = folderKey?.let {
            if (it.startsWith("/")) "${MediaStore.Video.Media.DATA} >= ? AND ${MediaStore.Video.Media.DATA} < ?" else "${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        }
        val arguments = folderKey?.let { if (it.startsWith("/")) arrayOf(it + "/", it + "0") else arrayOf(it.substringAfter(':')) }
        context.contentResolver.query(collection, projection, selection, arguments, null, signal)?.use { cursor ->
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
                        signal.throwIfCanceled()
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
        signal.throwIfCanceled()
        return MediaRules.snapshot(grouped)
    }

    private suspend fun reconcile(context: Context, force: Boolean, folderKey: String?, ticket: ScanOwnership.Ticket,
        emit: (List<MediaFolder>) -> Unit) {
        if (Build.VERSION.SDK_INT < 30 || BuildConfig.FLAVOR != "allstorage" || !Environment.isExternalStorageManager()) return
        // Never wait behind a stuck listFiles call. Only actual traversals use this lock.
        if (!traversal.tryLock()) { Log.i("OwlMediaScan", "Direct traversal busy; keeping provider results"); return }
        try { withContext(Dispatchers.IO) {
            val job = currentCoroutineContext()[Job]!!
            job.ensureActive()
            if (folderKey != null) {
                if (folderKey.startsWith("/")) {
                    val grouped = linkedMapOf<String, MediaFolder>()
                    walk(File(folderKey), cache.firstOrNull { it.key == folderKey }?.storageLabel.orEmpty(), grouped, job, false, emit)
                    job.ensureActive()
                    emit(MediaRules.snapshot(grouped))
                    ownership.publish(ticket) { synchronized(cacheLock) { directCache.clear() } }
                }
                return@withContext
            }
            val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return@withContext
            for (volume in manager.storageVolumes.filter { !it.isPrimary }) {
                job.ensureActive()
                try {
                    val root = volume.directory ?: continue
                    if (!root.canRead()) continue
                    val label = volume.getDescription(context)
                    val cached = synchronized(cacheLock) { directCache[root.absolutePath] }
                    if (!force && cached != null && SystemClock.elapsedRealtime() - cached.first < 300_000) {
                        emit(cached.second)
                    } else {
                        val grouped = linkedMapOf<String, MediaFolder>()
                        walk(root, label, grouped, job, true, emit)
                        job.ensureActive()
                        val result = MediaRules.snapshot(grouped)
                        emit(result)
                        ownership.publish(ticket) { synchronized(cacheLock) { directCache[root.absolutePath] = SystemClock.elapsedRealtime() to result } }
                    }
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (error: Exception) { Log.w("OwlMediaScan", "Removable storage unavailable", error) }
            }
        } } finally { traversal.unlock() }
    }

    private fun walk(root: File, label: String, grouped: LinkedHashMap<String, MediaFolder>, job: Job,
        recursive: Boolean, emit: (List<MediaFolder>) -> Unit) {
        val seen = hashSetOf<String>()
        var lastUpdate = SystemClock.elapsedRealtime()
        var additions = 0
        fun publishIfDue() {
            if (SystemClock.elapsedRealtime() - lastUpdate >= 1500 && additions > 0) {
                job.ensureActive()
                emit(MediaRules.snapshot(grouped))
                lastUpdate = SystemClock.elapsedRealtime()
                additions = 0
            }
        }
        DirectVideoWalker.walkWithProgress(root, recursive, { job.ensureActive() }, ::publishIfDue) { child ->
            val parent = child.parentFile ?: root
            MediaRules.add(grouped, parent.absolutePath, if (parent == root) label else parent.name, label,
                MediaVideo(child.name, child.absolutePath, Uri.fromFile(child).toString(), 0, 0, 0,
                    child.length(), child.lastModified() / 1000), seen)
            additions++
            publishIfDue()
        }
    }
}
