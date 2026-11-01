package `is`.xyz.mpv

import org.junit.Assert.*
import org.junit.Test

class SessionCleanupTest {
    @Test fun taskExitIsIdempotentAndReentrant() {
        val cleanup = SessionCleanup()
        var before = 0; var owner = 0; var after = 0
        cleanup.register("player") { owner++; assertFalse(cleanup.close({}, {})) }
        assertTrue(cleanup.close({ before++ }, { after++ }))
        assertFalse(cleanup.close({ before++ }, { after++ }))
        assertEquals(listOf(1, 1, 1), listOf(before, owner, after))
    }

    @Test fun freshSessionClearsRuntimeButPreservesPersistentValues() {
        val cleanup = SessionCleanup()
        val persistent = mutableMapOf("fastdecode" to "true", "history" to "12345", "sort" to "recent")
        val original = persistent.toMap()
        val runtime = mutableListOf("scan", "thumbnail", "playlist", "diagnostics", "session")
        cleanup.register("runtime") { runtime.clear() }
        cleanup.close({}, {})
        cleanup.restart()
        assertTrue(runtime.isEmpty())
        assertEquals(original, persistent)
        assertFalse(cleanup.closed)
    }

    @Test fun delayedOldServiceCallbackCannotCloseFreshSession() {
        val cleanup = SessionCleanup()
        val old = cleanup.generation
        cleanup.close({}, {}, old)
        cleanup.restart()
        var stopped = false
        cleanup.register("new activity") { stopped = true }
        assertFalse(cleanup.close({}, {}, old))
        assertFalse(stopped)
        assertFalse(cleanup.closed)
        assertTrue(cleanup.close({}, {}, cleanup.generation))
        assertTrue(stopped)
    }

    @Test fun destroyedOwnerIsNotRetainedOrCleanedTwice() {
        val cleanup = SessionCleanup()
        var calls = 0
        cleanup.register("activity") { calls++ }
        cleanup.unregister("activity")
        cleanup.close({}, {})
        assertEquals(0, calls)
    }
}
