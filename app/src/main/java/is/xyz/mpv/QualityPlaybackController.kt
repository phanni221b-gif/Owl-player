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
internal class QualityPlaybackController(context: Context) : MPVLib.EventObserver, MPVLib.LogObserver {
    private val app = context.applicationContext
    private val prefs = PreferenceManager.getDefaultSharedPreferences(app)
    private val configuration = QualityPreferences.read(app)
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
    private val geometryCheck = Runnable {
        val mode = attempted
        if (!closed && decided && mode?.neural == true && shaderStatus != "model_loading")
            startConfirmation(generation, attemptNumber, mode)
    }
    init {
        MPVLib.addObserver(this); MPVLib.addLogObserver(this)
        if (configuration.neural.isNotEmpty() || configuration.aiMax.isNotEmpty()) {
            MPVLib.observeProperty("osd-dimensions", MPVLib.MpvFormat.MPV_FORMAT_NONE)
            MPVLib.observeProperty("video-zoom", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        }
    }
    override fun event(eventId: Int) {
        handler.post {
            if (closed || OwlSession.ending) return@post
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                    generation++; attemptNumber++; metadataJob?.cancel(); confirmation?.cancel(); handler.removeCallbacks(geometryCheck)
                    decided = false; deciding = false; source = null; fallback = null; attempted = null
                    effective = configuration.base; shaderStatus = "not_requested"; renderer = null
                    if (configuration.needsGpuNext) MPVLib.setOptionString("glsl-shaders", "")
                    report()
                }
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED,
                MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> decideWhenReady()
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    generation++; attemptNumber++; metadataJob?.cancel(); confirmation?.cancel(); attempted = null
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
            attempted = mode; attemptNumber++
            val attempt = attemptNumber
            confirmation?.cancel()
            if (!configuration.needsGpuNext && mode == configuration.base) {
                effective = mode; shaderStatus = "not_requested"; report(); return
            }
            MPVLib.setOptionString("glsl-shaders", "") // overwrite the list; never stack model shaders
            var failedOption: String? = null
            for ((name, value) in QualityPlaybackOptions.options(mode, prefs, mode == configuration.base)) {
                if (MPVLib.setOptionString(name, value) < 0) { failedOption = name; break }
            }
            if (failedOption != null) { chain.fail("option_rejected_" + failedOption); shaderStatus = "option_failed"; report(); continue }
            effective = if (mode.neural) QualityMode.ULTRA else mode
            if (!mode.neural) { shaderStatus = "not_requested"; report(); return }
            shaderStatus = "model_loading"; report()
            val model = NeuralModel.forMode(mode)
            val file = try { NeuralAssets.install(app, model) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { chain.fail("model_integrity_or_install_failed"); shaderStatus = "load_failed"; report(); continue }
            scope.coroutineContext.ensureActive()
            if (closed || token != generation || attempt != attemptNumber) return
            if (MPVLib.setOptionString("glsl-shaders", file.absolutePath) < 0) {
                chain.fail("shader_option_rejected"); shaderStatus = "load_failed"; report(); continue
            }
            shaderStatus = "awaiting_render_confirmation"; report()
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
                renderer = runCatching { RenderPassSummary.parse(MPVLib.getRenderPassSummary()) }.getOrNull()
                val matched = if (mode == QualityMode.AI_MAX) renderer?.neural16 == true else renderer?.neural8 == true
                if (matched) { effective = mode; shaderStatus = "loaded_and_rendered"; report(); return@launch }
            }
            downgrade(token, attempt, "shader_render_confirmation_failed")
        }
    }
    private fun geometryChanged() {
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
        if (closed || level > MPVLib.MpvLogLevel.MPV_LOG_LEVEL_WARN) return
        val lower = text.lowercase()
        val shaderError = (prefix.contains("gpu") || prefix.contains("libplacebo")) &&
            listOf("shader", "glsl", "framebuffer", "render pass").any { lower.contains(it) } &&
            (level <= MPVLib.MpvLogLevel.MPV_LOG_LEVEL_ERROR || lower.contains("failed") || lower.contains("error"))
        if (!shaderError) return
        val token = generation; val attempt = attemptNumber
        // Raw renderer messages can contain private paths. Keep only a fixed reason code.
        handler.post {
            if ((attempted ?: QualityMode.BALANCED) >= QualityMode.ULTRA)
                downgrade(token, attempt, "renderer_shader_error")
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
        closed = true; generation++; attemptNumber++
        MPVLib.removeObserver(this); MPVLib.removeLogObserver(this)
        handler.removeCallbacksAndMessages(null); scope.cancel()
    }
    override fun eventProperty(property: String) { if (property == "osd-dimensions") geometryChanged() }
    override fun eventProperty(property: String, value: Long) {}
    override fun eventProperty(property: String, value: Boolean) {}
    override fun eventProperty(property: String, value: String) {}
    override fun eventProperty(property: String, value: Double) { if (property == "video-zoom") geometryChanged() }
}
