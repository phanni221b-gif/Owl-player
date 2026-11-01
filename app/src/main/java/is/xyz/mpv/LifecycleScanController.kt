package `is`.xyz.mpv

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Android binding. Provider and filesystem cancellation never has to finish before the next request. */
internal class LifecycleScanController<T>(
    private val owner: LifecycleOwner, private val scope: CoroutineScope, private val label: String,
    private val allowed: () -> Boolean,
    private val scan: suspend (Boolean, (List<T>) -> Unit) -> List<T>,
    private val data: (List<T>) -> Unit, private val stateChanged: (LifecycleScanState.Phase) -> Unit
) : DefaultLifecycleObserver {
    private val state = LifecycleScanState()
    private var job: Job? = null
    val running get() = state.active != null
    val completed get() = state.phase == LifecycleScanState.Phase.CONTENT || state.phase == LifecycleScanState.Phase.EMPTY
    private val record = OwlDiagnostics.storageRecorder()
    init { owner.lifecycle.addObserver(this) }
    fun request(force: Boolean = false) {
        if (!allowed()) return
        record(label + " scan requested; force=" + force)
        val merged = state.active != null || state.pendingForce != null
        val start = state.request(force)
        if (merged) record(label + " pending scan merged")
        if (!state.started) record(label + " deferred because lifecycle")
        update(); start?.let(::execute)
    }
    override fun onStart(owner: LifecycleOwner) {
        if (!allowed()) return
        if (state.pendingForce != null) record(label + " foreground retry")
        val start = state.foreground(); update(); start?.let(::execute)
    }
    override fun onStop(owner: LifecycleOwner) { pause("lifecycle stop") }
    override fun onDestroy(owner: LifecycleOwner) { close() }
    fun pause(reason: String) {
        val active = state.active
        state.background(); job?.cancel(); job = null
        if (active != null) record(label + " cancellation; generation=" + active.generation + "; reason=" + reason)
        update()
    }
    fun resumeIfForeground() {
        if (allowed() && owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) onStart(owner)
    }
    fun close() { state.close(); job?.cancel(); job = null; update() }
    private fun update() = stateChanged(state.phase)
    private fun execute(request: LifecycleScanState.Request) {
        record(label + " scan actually started; generation=" + request.generation + "; force=" + request.force)
        job = scope.launch(start = CoroutineStart.LAZY) {
            var count = 0
            var failed = false
            try {
                val found = scan(request.force) { partial ->
                    scope.launch(Dispatchers.Main.immediate) {
                        if (state.owns(request) && allowed()) { data(partial); update() }
                    }
                }
                if (state.owns(request) && allowed()) { count = found.size; data(found) }
                record(label + " scan completed; generation=" + request.generation)
            } catch (cancel: CancellationException) {
                val owned = state.active == request
                state.cancelled(request)
                if (owned) update()
                record(label + " cancellation; generation=" + request.generation + "; reason=coroutine cancelled")
                throw cancel
            } catch (error: Exception) {
                failed = true
                record(label + " scan failed; generation=" + request.generation + "; type=" + error.javaClass.simpleName)
            } finally {
                if (state.active == request) {
                    val next = state.complete(request, count, failed)
                    update(); next?.let(::execute)
                }
            }
        }
        job?.start()
    }
}
