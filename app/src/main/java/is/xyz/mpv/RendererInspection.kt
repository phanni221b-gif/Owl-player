package `is`.xyz.mpv

/** Async libmpv NODE request/reply: no synchronous VO timing read on the UI thread. */
internal object RendererInspection : MPVLib.EventObserver {
    private val requests = RendererRequests()
    @Volatile private var active = false
    fun attach() {
        if (!active) { active = true; MPVLib.addObserver(this) }
        requests.reset(newContext = true)
    }
    fun reset() = requests.reset()
    fun latest() = requests.latest()
    fun request() {
        if (!active) return
        val id = requests.request() ?: return
        if (MPVLib.requestRenderPassSummary(id) < 0) requests.complete(id, null)
    }
    fun detach() {
        active = false; requests.reset(newContext = true); MPVLib.removeObserver(this)
    }
    override fun eventProperty(property: String, value: String) {
        if (!active || property != "owl-render-pass-summary") return
        val id = value.substringBefore(',').removePrefix("id=").toLongOrNull() ?: return
        requests.complete(id, value.takeUnless { it.contains("available=0") })
    }
    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Boolean) {}
    override fun eventProperty(property: String, value: Double) {}
    override fun event(eventId: Int) {}
}
