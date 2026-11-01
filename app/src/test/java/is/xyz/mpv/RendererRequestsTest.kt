package `is`.xyz.mpv

import org.junit.Assert.*
import org.junit.Test
class RendererRequestsTest {
    @Test fun onlyOneReadCanBeOutstanding() {
        val r = RendererRequests(); val id = r.request()!!
        repeat(100) { assertNull(r.request()) }
        assertTrue(r.complete(id,"stats")); assertEquals("stats",r.latest()); assertNotNull(r.request())
    }
    @Test fun sourceResetDiscardsLateDataButKeepsQueueBounded() {
        val r = RendererRequests(); val old = r.request()!!; r.reset()
        assertNull(r.request()); assertFalse(r.complete(old,"stale")); assertNull(r.latest())
        val next = r.request()!!; assertTrue(next>old); assertTrue(r.complete(next,"current"))
        assertFalse(r.complete(old,"stale again")); assertEquals("current",r.latest())
    }
    @Test fun contextTeardownRevokesPendingRepliesAndAllowsNewNativeContext() {
        val r = RendererRequests(); val old = r.request()!!; r.reset(newContext=true)
        val next = r.request()!!; assertFalse(r.complete(old,"dead context")); assertNull(r.request())
        assertTrue(r.complete(next,null)); assertNull(r.latest()); assertNotNull(r.request())
    }
}
