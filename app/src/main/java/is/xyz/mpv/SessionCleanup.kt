package `is`.xyz.mpv

/** Only runtime hooks are reachable here: no preferences or history store is accepted. */
internal class SessionCleanup {
    private val hooks = linkedMapOf<Any, () -> Unit>()
    var generation = 0L
        private set
    var closed = false
        private set
    fun register(owner: Any, cleanup: () -> Unit) { if (!closed) hooks[owner] = cleanup }
    fun unregister(owner: Any) { hooks.remove(owner) }
    fun restart() { check(hooks.isEmpty()); generation++; closed = false }
    fun close(before: () -> Unit, after: () -> Unit, expectedGeneration: Long? = null): Boolean {
        if (closed || (expectedGeneration != null && expectedGeneration != generation)) return false
        closed = true
        val callbacks = hooks.values.toList()
        hooks.clear()
        try { before() } finally {
            try { callbacks.forEach { runCatching(it) } } finally { after() }
        }
        return true
    }
}
