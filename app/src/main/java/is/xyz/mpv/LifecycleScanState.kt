package `is`.xyz.mpv

/** Pure main-thread request reducer. A lifecycle callback, never Activity.onStart(), opens execution. */
internal class LifecycleScanState {
    enum class Phase { IDLE, PENDING, LOADING, CONTENT, EMPTY, ERROR, CANCELLED }
    data class Request(val generation: Long, val force: Boolean)
    var phase = Phase.IDLE; private set
    var started = false; private set
    var active: Request? = null; private set
    var pendingForce: Boolean? = null; private set
    private var generation = 0L
    fun request(force: Boolean = false): Request? {
        if (active != null && !force) return null // ordinary duplicate joins the active scan
        pendingForce = (pendingForce ?: false) || force
        if (active == null) phase = Phase.PENDING
        return takePending()
    }
    fun foreground(): Request? { started = true; return takePending() }
    fun background() {
        started = false
        active?.let { pendingForce = (pendingForce ?: false) || it.force }
        active = null // revoke publication before cancellation can deliver callbacks
        if (pendingForce != null) phase = Phase.PENDING
        else if (phase == Phase.LOADING) phase = Phase.CANCELLED
    }
    fun owns(request: Request) = started && active == request
    fun complete(request: Request, count: Int, failed: Boolean = false): Request? {
        if (active != request) return null
        active = null
        phase = if (failed) Phase.ERROR else if (count == 0) Phase.EMPTY else Phase.CONTENT
        return takePending()
    }
    fun cancelled(request: Request) {
        if (active != request) return
        active = null
        // A newer screen can take library ownership before this Activity receives ON_STOP.
        pendingForce = (pendingForce ?: false) || request.force
        phase = Phase.CANCELLED
    }
    fun close() { started = false; active = null; pendingForce = null; phase = Phase.IDLE }
    private fun takePending(): Request? {
        if (!started || active != null) return null
        val force = pendingForce ?: return null
        pendingForce = null
        return Request(++generation, force).also { active = it; phase = Phase.LOADING }
    }
}
