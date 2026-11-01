package `is`.xyz.mpv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.preference.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** One metadata decision per source, bounded render confirmation, and event-driven failure downgrade. */
internal class QualityPlaybackController(context: Context, private val selectRenderer: (String) -> Unit) : MPVLib.EventObserver, MPVLib.LogObserver {
    private val app = context.applicationContext
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)
    private var configuration = QualityPreferences.read(app)
    private var managedQuality = configuration.needsGpuNext
    private var preferredGpuNext = configuration.needsGpuNext || prefs.getBoolean("gpu_next", false)
    private var rendererTransitionUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var metadataJob: Job? = null
    private var confirmation: Job? = null
    @Volatile private var generation = 0L
    @Volatile private var attemptNumber = 0L
    @Volatile private var closed = false
    private var decided = false
    private var deciding = false
    private var source: SourceVideo? = null
    private var fallback: QualityFallback? = null
    private var attempted: QualityMode? = null
    private var effective = configuration.base
    private var shaderStatus = "not_requested"
    private var renderer: RenderPassSummary? = null
    private val recordRenderer = OwlDiagnostics.rendererRecorder()
    @Volatile private var logContext = RendererAttemptContext(0,0,null,RendererAttemptPhase.IDLE)
    private var pendingFailure: Pair<RendererAttemptContext,RendererFailure>? = null
    private val failureFlush = Runnable {
        val item = pendingFailure; pendingFailure = null
        if (item != null) downgrade(item.first.source,item.first.attempt,item.second.fixedCode())
    }
    private fun phase(value: RendererAttemptPhase) { logContext = RendererAttemptContext(generation,attemptNumber,attempted,value) }
    private val geometryCheck = Runnable {
        val mode = attempted
        if (!closed && decided && mode?.neural == true && shaderStatus != "model_loading")
            startConfirmation(generation, attemptNumber, mode)
    }
    init {
        MPVLib.addObserver(this); MPVLib.addLogObserver(this); RendererInspection.attach()
        MPVLib.observeProperty("osd-dimensions", MPVLib.MpvFormat.MPV_FORMAT_NONE)
        MPVLib.observeProperty("video-zoom", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
    }
    override fun event(eventId: Int) {
        handler.post {
            if (closed || OwlSession.ending) return@post
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                    generation++; attemptNumber++; metadataJob?.cancel(); confirmation?.cancel(); handler.removeCallbacks(geometryCheck)
                    pendingFailure = null; handler.removeCallbacks(failureFlush); phase(RendererAttemptPhase.IDLE)
                    RendererInspection.reset()
                    decided = false; deciding = false; source = null; fallback = null; attempted = null
                    val previous = configuration
                    configuration = QualityPreferences.read(app)
                    managedQuality = managedQuality || configuration.needsGpuNext || configuration != previous
                    effective = configuration.base; shaderStatus = "not_requested"; renderer = null
                    if (managedQuality) MPVLib.setOptionString("glsl-shaders", "")
                    val wantGpuNext = configuration.needsGpuNext || prefs.getBoolean("gpu_next", false)
                    if (wantGpuNext != preferredGpuNext) {
                        selectRenderer(if (wantGpuNext) "gpu-next,gpu" else "gpu")
                        preferredGpuNext = wantGpuNext
                        rendererTransitionUntil = android.os.SystemClock.elapsedRealtime() + 1_000
                    }
                    report()
                }
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED,
                MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> decideWhenReady()
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    generation++; attemptNumber++; metadataJob?.cancel(); confirmation?.cancel(); attempted = null
                    pendingFailure = null; handler.removeCallbacks(failureFlush); phase(RendererAttemptPhase.IDLE)
                }
            }
        }
    }
    private fun decideWhenReady() {
        if (decided || deciding) return
        deciding = true
        val token = generation
        metadataJob = scope.launch {
            try {
                repeat(20) {
                    if (closed || token != generation) return@launch
                    if (android.os.SystemClock.elapsedRealtime() < rendererTransitionUntil &&
                        MPVLib.getPropertyString("current-vo") != "gpu-next" && preferredGpuNext) { delay(100); return@repeat }
                    val width = MPVLib.getPropertyInt("video-params/w") ?: 0
                    val height = MPVLib.getPropertyInt("video-params/h") ?: 0
                    if (width > 0 && height > 0 && (!configuration.needsGpuNext || MPVLib.getPropertyString("current-vo") != null)) {
                        val gamma = MPVLib.getPropertyString("video-params/gamma")
                        val fps = MPVLib.getPropertyDouble("container-fps")?.takeIf { it > 0 && it.isFinite() }
                        val codec = MPVLib.getPropertyString("video-format").orEmpty()
                            .replace(Regex("[^A-Za-z0-9 ._+()-]"), "?").take(64)
                        val hdr = when (gamma) { null, "", "unknown" -> null; "pq", "hlg" -> true; else -> false }
                        source = SourceVideo(width, height, fps, hdr, codec)
                        fallback = QualityFallback(configuration.candidates(source!!))
                        decided = true
                        applyCandidate(token)
                        return@launch
                    }
                    delay(100)
                }
                shaderStatus = "source_metadata_unavailable"; report()
            } finally { if (token == generation) deciding = false }
        }
    }
    private suspend fun applyCandidate(token: Long) {
        val chain = fallback ?: return
        while (!closed && token == generation) {
            val mode = chain.current ?: run { shaderStatus = "quality_options_failed_playback_retained"; report(); return }
            val rejected = QualityPolicy().rejection(mode, source!!, MPVLib.getPropertyString("current-vo") == "gpu-next")
            if (rejected != null) { chain.fail(rejected); continue }
            attempted = mode; attemptNumber++; RendererInspection.reset()
            val attempt = attemptNumber
            pendingFailure = null; handler.removeCallbacks(failureFlush); phase(RendererAttemptPhase.OPTIONS)
            confirmation?.cancel()
            if (!managedQuality && mode == configuration.base) {
                effective = mode; shaderStatus = "not_requested"; report(); return
            }
            MPVLib.setOptionString("glsl-shaders", "") // overwrite the list; never stack model shaders
            var failedOption: String? = null
            for ((name, value) in QualityPlaybackOptions.options(mode, prefs, mode == configuration.base)) {
                if (MPVLib.setOptionString(name, value) < 0) { failedOption = name; break }
            }
            if (failedOption != null) { chain.fail("option_rejected_" + failedOption); shaderStatus = "option_failed"; report(); continue }
            effective = if (mode.neural) QualityMode.ULTRA else mode
            if (!mode.neural) { shaderStatus = "not_requested"; phase(RendererAttemptPhase.BASELINE); report(); return }
            shaderStatus = "model_loading"; phase(RendererAttemptPhase.MODEL_INSTALL); report()
            val model = NeuralModel.forMode(mode)
            val file = try { NeuralAssets.install(app, model) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { chain.fail("model_integrity_or_install_failed"); shaderStatus = "load_failed"; report(); continue }
            scope.coroutineContext.ensureActive()
            if (closed || token != generation || attempt != attemptNumber) return
            phase(RendererAttemptPhase.SHADER_SUBMITTED)
            if (MPVLib.setOptionString("glsl-shaders", file.absolutePath) < 0) {
                chain.fail("shader_option_rejected"); shaderStatus = "load_failed"; report(); continue
            }
            shaderStatus = "awaiting_render_confirmation"; phase(RendererAttemptPhase.RENDER_CONFIRM); report()
            startConfirmation(token, attempt, mode)
            return
        }
    }
    private fun upscaleRequired(): Boolean? {
        val video = source ?: return null
        val width = MPVLib.getPropertyInt("osd-dimensions/w") ?: return null
        val height = MPVLib.getPropertyInt("osd-dimensions/h") ?: return null
        fun margin(name: String) = MPVLib.getPropertyInt("osd-dimensions/" + name) ?: 0
        return NeuralGeometry.upscaleRequired(video.width, video.height,
            width - margin("ml") - margin("mr"), height - margin("mt") - margin("mb"))
    }
    private fun startConfirmation(token: Long, attempt: Long, mode: QualityMode) {
        confirmation?.cancel()
        confirmation = scope.launch {
            repeat(16) {
                delay(350)
                if (closed || token != generation || attempt != attemptNumber) return@launch
                if (upscaleRequired() == false) {
                    effective = QualityMode.ULTRA; shaderStatus = "bypassed_no_upscale"; report(); return@launch
                }
                RendererInspection.request()
                renderer = RenderPassSummary.parse(RendererInspection.latest())
                val matched = if (mode == QualityMode.AI_MAX) renderer?.neural16 == true else renderer?.neural8 == true
                if (matched) { effective = mode; shaderStatus = "loaded_and_rendered"; phase(RendererAttemptPhase.CONFIRMED); report(); return@launch }
            }
            downgrade(token, attempt, "shader_render_confirmation_failed")
        }
    }
    private fun geometryChanged() {
        if (configuration.neural.isEmpty() && configuration.aiMax.isEmpty()) return
        handler.removeCallbacks(geometryCheck)
        handler.postDelayed(geometryCheck, 450) // fold/rotation/zoom events, never video-frame callbacks
    }
    private fun downgrade(token: Long, attempt: Long, reason: String) {
        if (closed || token != generation || attempt != attemptNumber) return
        attemptNumber++
        confirmation?.cancel(); confirmation = null
        fallback?.fail(reason)
        shaderStatus = "load_failed"; report()
        scope.launch { applyCandidate(token) }
    }
    override fun logMessage(prefix: String, level: Int, text: String) {
        if (closed) return
        val context = logContext
        if ((context.mode ?: QualityMode.BALANCED) < QualityMode.ULTRA || context.phase == RendererAttemptPhase.IDLE) return
        val failure = RendererFailure.classify(prefix,level,text) ?: return
        // The closure only captures fixed enums/numbers; never raw text/prefix/path.
        handler.post {
            if (closed || context.source != generation || context.attempt != attemptNumber) return@post
            recordRenderer(context,failure)
            val previous = pendingFailure
            if (previous == null || failure.detailScore > previous.second.detailScore) pendingFailure = context to failure
            if (previous == null) handler.postDelayed(failureFlush,80) // retain detailed compiler lines after its generic banner
        }
    }
    private fun report() {
        val metadata = source
        val neural = attempted?.neural == true
        val backend = if (!neural) "none" else if (shaderStatus == "loaded_and_rendered") "GPU" else "GPU requested/unconfirmed"
        OwlDiagnostics.qualitySummary(
            "source=" + (metadata?.let { it.width.toString() + "x" + it.height } ?: "n/a") +
            ", source-fps=" + (metadata?.fps ?: "n/a") + ", HDR=" + (metadata?.hdr ?: "unknown") +
            ", codec=" + (metadata?.codec?.ifEmpty { "n/a" } ?: "n/a") +
            ", bucket=" + (metadata?.bucket?.name ?: "unknown") +
            ", base=" + configuration.base.label + ", requested=" + (fallback?.candidates?.firstOrNull()?.label ?: configuration.base.label) +
            ", effective=" + effective.label + ", Neural/AI backend=" + backend +
            ", model=" + (if (neural) "FSRCNNX x2 " + NeuralModel.forMode(attempted!!).channels + " channels" else "none") +
            ", shader=" + shaderStatus + ", bypass=" + (shaderStatus == "bypassed_no_upscale") + ", fallback=" + (fallback?.reasons?.isNotEmpty() == true) +
            ", reasons=" + fallback?.reasons.orEmpty().joinToString("; ") +
            ", renderer-pass-avg-ns=" + (renderer?.averageNs ?: "n/a")
        )
    }
    fun close() {
        if (closed) return
        closed = true; generation++; attemptNumber++; phase(RendererAttemptPhase.IDLE)
        MPVLib.removeObserver(this); MPVLib.removeLogObserver(this); RendererInspection.detach()
        handler.removeCallbacksAndMessages(null); scope.cancel()
    }
    override fun eventProperty(property: String) { if (property == "osd-dimensions") geometryChanged() }
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Boolean) {}
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: Double) { if (property == "video-zoom") geometryChanged() }
}
