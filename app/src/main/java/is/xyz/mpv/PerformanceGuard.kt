package `is`.xyz.mpv

/**
 * Detects sustained frame loss while a Neural/AI Max model is confirmed running, so the controller can
 * step down AI Max -> Neural -> Ultra instead of leaving playback stuttering.
 *
 * Fed once per second with mpv's cumulative frame-drop-count + vo-delayed-frame-count. It ignores the
 * first [graceSamples] seconds after a (re)start or seek, counter resets and paused time, and requires
 * at least [minBadSeconds] of the last [windowSeconds] seconds to lose [badFramesPerSecond] frames or more.
 * A single hiccup (seek, notification, app switch) therefore never downgrades.
 */
internal class PerformanceGuard(
    private val windowSeconds: Int = 5,
    private val minBadSeconds: Int = 3,
    private val badFramesPerSecond: Long = 2,
    private val graceSamples: Int = 3
) {
    private var last: Long? = null
    private var grace = graceSamples
    private val recent = ArrayDeque<Long>()

    /** Frames lost in the current window, for diagnostics. */
    val lostInWindow: Long get() = recent.sum()

    fun reset() {
        last = null
        grace = graceSamples
        recent.clear()
    }

    /** [lostTotal] is null when mpv has no value yet. Returns true when a downgrade is warranted. */
    fun sample(lostTotal: Long?, paused: Boolean): Boolean {
        if (lostTotal == null || paused) { reset(); return false }
        val previous = last
        last = lostTotal
        if (previous == null || lostTotal < previous) return false
        if (grace > 0) { grace--; return false }
        recent.addLast(lostTotal - previous)
        while (recent.size > windowSeconds) recent.removeFirst()
        return recent.count { it >= badFramesPerSecond } >= minBadSeconds
    }
}
