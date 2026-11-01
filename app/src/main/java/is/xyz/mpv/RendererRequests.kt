package `is`.xyz.mpv

/** One outstanding native read; source resets discard stale data without queueing more requests. */
internal class RendererRequests {
    private var sequence = 0L
    private var epoch = 0L
    private var pending: Long? = null
    private var pendingEpoch = 0L
    private var summary: String? = null
    @Synchronized fun latest() = summary
    @Synchronized fun request(): Long? {
        if (pending != null) return null
        pendingEpoch = epoch
        return (++sequence).also { pending = it }
    }
    @Synchronized fun complete(id: Long, value: String?): Boolean {
        if (pending != id) return false
        pending = null
        if (pendingEpoch != epoch) return false
        summary = value
        return true
    }
    @Synchronized fun reset(newContext: Boolean = false) {
        epoch++; summary = null
        if (newContext) { pending = null; sequence++ }
    }
}
