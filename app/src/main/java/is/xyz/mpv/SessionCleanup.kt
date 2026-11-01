package `is`.xyz.mpv

/** Only runtime hooks are reachable here: no preferences or history store is accepted. */
internal class SessionCleanup {
    private val hooks = linkedMapOf<Any, () -> Unit>()
    var closed = false
        private set
    fun register(owner: Any, cleanup: () -> Unit) { if (!closed) hooks[owner] = cleanup }
    fun unregister(owner: Any) { hooks.remove(owner) }
    fun restart() { check(hooks.isEmpty()); closed = false }
    fun close(before: () -> Unit, after: () -> Unit): Boolean {
        if (closed) return false
        closed = true
        val callbacks = hooks.values.toList()
        hooks.clear()
        try { before() } finally {
            try { callbacks.forEach { runCatching(it) } } finally { after() }
        }
        return true
    }
}
