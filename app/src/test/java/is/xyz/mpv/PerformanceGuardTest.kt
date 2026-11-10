package `is`.xyz.mpv

import org.junit.Assert.*
import org.junit.Test

class PerformanceGuardTest {
    /** Feeds per-second lost-frame deltas after a baseline sample; returns the second that triggered. */
    private fun run(guard: PerformanceGuard, deltas: List<Long>, start: Long = 0): Int? {
        var total = start
        assertFalse(guard.sample(total, false))
        deltas.forEachIndexed { i, d -> total += d; if (guard.sample(total, false)) return i }
        return null
    }

    @Test fun smoothPlaybackNeverDowngrades() {
        assertNull(run(PerformanceGuard(), List(120) { 0L }))
        assertNull(run(PerformanceGuard(), List(120) { if (it % 7 == 0) 1L else 0L }))
    }

    @Test fun sustainedDropsDowngradeAfterGraceAndThreeBadSeconds() {
        // 3 grace seconds, then 3 consecutive bad seconds -> triggers on index 5.
        assertEquals(5, run(PerformanceGuard(), List(20) { 6L }))
    }

    @Test fun isolatedSpikesDoNotDowngrade() {
        val deltas = List(60) { if (it % 10 == 5) 30L else 0L }
        assertNull(run(PerformanceGuard(), deltas))
    }

    @Test fun twoBadSecondsInFiveAreTolerated() {
        assertNull(run(PerformanceGuard(), listOf(0L, 0L, 0L, 5L, 0L, 5L, 0L, 0L, 0L, 0L)))
    }

    @Test fun pauseSeekAndCounterResetRestartTheGrace() {
        val guard = PerformanceGuard()
        assertFalse(guard.sample(0, false))
        repeat(3) { assertFalse(guard.sample(0, false)) } // grace consumed
        assertFalse(guard.sample(10, false)); assertFalse(guard.sample(20, false))
        assertFalse(guard.sample(20, true)) // paused: window cleared
        assertFalse(guard.sample(30, false)) // new baseline
        repeat(3) { assertFalse(guard.sample(40L + it * 10, false)) } // new grace
        guard.reset()
        assertFalse(guard.sample(5, false)) // counter went backwards / reset: baseline only
        assertFalse(guard.sample(null, false))
    }
}
