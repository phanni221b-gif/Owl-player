package `is`.xyz.mpv

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.metrics.performance.JankStats
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

/** Read-only diagnostics. No polling or frame listener exists while disabled. */
object OwlDiagnostics {
    private const val PREFS = "owl_diagnostics"
    private data class Frames(var total: Long = 0, var jank: Long = 0, var longestNs: Long = 0,
        val states: MutableMap<String, Long> = mutableMapOf())
    private val frames = linkedMapOf<String, Frames>()
    private val samples = java.util.ArrayDeque<String>()
    private val stoppers = linkedSetOf<() -> Unit>()
    fun resetRuntime() {
        stoppers.toList().forEach { it() }; stoppers.clear()
        synchronized(this) { frames.clear(); samples.clear() }
    }
    fun enabled(context: Context) = context.getSharedPreferences(PREFS, 0).getBoolean("enabled", false)
    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", value).apply()
    }

    fun attach(activity: Activity, owner: LifecycleOwner, recycler: RecyclerView? = null,
        playbackReady: (() -> Boolean)? = null) {
        val handler = Handler(Looper.getMainLooper())
        var tracker: JankStats? = null
        var active = false
        val scrolling = java.util.concurrent.atomic.AtomicBoolean(false)
        val screen = activity.javaClass.simpleName
        val scrollListener = object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(view: RecyclerView, state: Int) { scrolling.set(state != RecyclerView.SCROLL_STATE_IDLE) }
        }
        val poll = object : Runnable {
            override fun run() {
                if (!active || !enabled(activity)) return
                if (playbackReady?.invoke() == true) {
                    // Names and types checked against the pinned mpv DOCS/man/input.rst.
                    // Unavailable values remain n/a; no setter/command is used.
                    fun number(key: String) = runCatching { MPVLib.getPropertyDouble(key)?.toString() }.getOrNull() ?: "n/a"
                    fun text(key: String) = runCatching { MPVLib.getPropertyString(key) }.getOrNull() ?: "n/a"
                    fun flag(key: String) = runCatching { MPVLib.getPropertyBoolean(key)?.toString() }.getOrNull() ?: "n/a"
                    val refresh = activity.windowManager.defaultDisplay.refreshRate
                    val line = "t=" + SystemClock.elapsedRealtime() + " ms, Display=" + refresh + " Hz, " +
                        listOf("estimated-vf-fps", "frame-drop-count", "decoder-frame-drop-count", "avsync",
                            "total-avsync-change", "vo-delayed-frame-count", "demuxer-cache-duration")
                            .joinToString(", ") { it + "=" + number(it) } +
                        ", hwdec-current=" + text("hwdec-current") + ", paused-for-cache=" + flag("paused-for-cache")
                    synchronized(this@OwlDiagnostics) {
                        if (samples.size >= 300) samples.removeFirst()
                        samples.addLast(line)
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }
        val stop: () -> Unit = {
            active = false
            handler.removeCallbacks(poll)
            tracker?.isTrackingEnabled = false
            tracker = null
            recycler?.removeOnScrollListener(scrollListener)
        }
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (!enabled(activity)) return
                active = true
                stoppers.add(stop)
                recycler?.addOnScrollListener(scrollListener)
                tracker = JankStats.createAndTrack(activity.window) { data ->
                    val state = listOfNotNull(
                        if (scrolling.get()) "scrolling" else null,
                        if (MediaLibrary.scanning) "media scan" else null,
                        if (VideoThumbnailLoader.loading > 0) "thumbnail loading" else null
                    ).joinToString(" + ").ifEmpty { "idle" }
                    synchronized(this@OwlDiagnostics) {
                        val f = frames.getOrPut(screen) { Frames() }
                        f.total++
                        if (data.isJank) f.jank++
                        f.longestNs = maxOf(f.longestNs, data.frameDurationUiNanos)
                        f.states[state] = (f.states[state] ?: 0L) + 1
                    }
                }
                if (playbackReady != null) handler.post(poll)
            }
            override fun onStop(owner: LifecycleOwner) { stop(); stoppers.remove(stop) }
            override fun onDestroy(owner: LifecycleOwner) { stop(); stoppers.remove(stop) }
        })
    }

    @Synchronized fun report(): String = buildString {
        append("Owl " + BuildConfig.VERSION_NAME + " diagnostics\nUI frame statistics (not video drops):\n")
        frames.forEach { (screen, f) ->
            append(String.format(Locale.US, "%s: frames=%d, jank=%d (%.2f%%), longest=%.2f ms\n",
                screen, f.total, f.jank, if (f.total == 0L) 0.0 else f.jank * 100.0 / f.total, f.longestNs / 1e6))
            append(f.states.toString()).append('\n')
        }
        append("\nLatest playback summary:\n").append(samples.peekLast() ?: "No playback samples yet").append('\n')
        append("\nRead-only playback samples (latest 300):\n")
        samples.forEach { append(it).append('\n') }
    }

    fun showReport(context: Context) {
        val report = report()
        AlertDialog.Builder(context).setTitle("성능 진단")
            .setMessage(report).setPositiveButton("복사") { _, _ ->
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Owl diagnostics", report))
            }.setNeutralButton("기록 초기화") { _, _ -> synchronized(this) { frames.clear(); samples.clear() } }
            .setNegativeButton("닫기", null).show()
    }
}
