package `is`.xyz.mpv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Primary publication precedes even volume enumeration; failures only discard their own source. */
internal object DiscoveryPipeline {
    suspend fun folder(key: String, query: suspend (String) -> List<MediaFolder>,
        reconcile: suspend (String, (List<MediaFolder>) -> Unit) -> Unit): MediaFolder? {
        val grouped = linkedMapOf<String, MediaFolder>()
        val seen = hashSetOf<String>()
        fun merge(folders: List<MediaFolder>) {
            folders.filter { it.key == key }.forEach { f -> f.videos.forEach { v ->
                MediaRules.add(grouped, f.key, f.name, f.storageLabel, v, seen)
            } }
        }
        merge(query(key))
        reconcile(key, ::merge)
        currentCoroutineContext().ensureActive()
        return MediaRules.snapshot(grouped).firstOrNull()
    }

    suspend fun discover(primary: suspend () -> List<MediaFolder>, volumes: suspend () -> List<String>,
        external: suspend (String) -> List<MediaFolder>,
        reconcile: suspend ((List<MediaFolder>) -> Unit) -> Unit,
        publish: (List<MediaFolder>) -> Unit): List<MediaFolder> {
        val grouped = linkedMapOf<String, MediaFolder>()
        val seen = hashSetOf<String>()
        fun merge(folders: List<MediaFolder>) {
            folders.forEach { f -> f.videos.forEach { v -> MediaRules.add(grouped, f.key, f.name, f.storageLabel, v, seen) } }
            publish(MediaRules.snapshot(grouped))
        }
        suspend fun isolated(source: suspend () -> List<MediaFolder>) {
            try { val found = source(); currentCoroutineContext().ensureActive(); merge(found) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Retain all previously published folders. */ }
        }
        isolated(primary)
        val removable = try { volumes() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { emptyList() }
        for (volume in removable) { currentCoroutineContext().ensureActive(); isolated { external(volume) } }
        try { reconcile { merge(it) } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Direct failure cannot remove primary folders. */ }
        currentCoroutineContext().ensureActive()
        return MediaRules.snapshot(grouped)
    }
}
