package `is`.xyz.mpv

import org.junit.Assert.*
import org.junit.Test

class LifecycleScanStateTest {
    @Test fun createdRequestStartsExactlyOnceAfterForeground() {
        val s = LifecycleScanState()
        assertNull(s.request()); assertEquals(LifecycleScanState.Phase.PENDING, s.phase)
        assertNotNull(s.foreground()); assertNull(s.foreground()); assertEquals(1L, s.active!!.generation)
    }
    @Test fun pendingRequestsCoalesceAndForceEscalatesWithoutLoss() {
        val s = LifecycleScanState()
        repeat(5) { s.request() }; s.request(true); s.request(false)
        assertTrue(s.foreground()!!.force); assertNull(s.foreground())
    }
    @Test fun ordinaryActiveDuplicateDoesNotRestart() {
        val s = LifecycleScanState(); s.foreground(); val first = s.request()!!
        repeat(5) { assertNull(s.request()) }
        assertNull(s.complete(first, 2)); assertEquals(LifecycleScanState.Phase.CONTENT, s.phase)
    }
    @Test fun refreshDuringActiveQueuesOnlyOneForcedFollowup() {
        val s = LifecycleScanState(); s.foreground(); val first = s.request()!!
        repeat(8) { assertNull(s.request(true)) }
        val next = s.complete(first, 1)!!; assertTrue(next.force)
        assertNull(s.complete(next, 1)); assertEquals(2L, next.generation)
    }
    @Test fun stopRevokesOldPublicationAndForegroundResumesOnce() {
        val s = LifecycleScanState(); s.foreground(); val first = s.request(true)!!
        s.background(); assertFalse(s.owns(first)); assertNull(s.complete(first, 2))
        assertEquals(LifecycleScanState.Phase.PENDING, s.phase)
        val next = s.foreground()!!; assertTrue(next.force); assertFalse(s.owns(first)); assertTrue(s.owns(next))
        assertNull(s.foreground()); assertNull(s.complete(first, 0)); assertEquals(next, s.active)
    }
    @Test fun completedScanDoesNotRescanOnOrdinaryForegroundReturn() {
        val s = LifecycleScanState(); s.foreground(); val r = s.request()!!; s.complete(r, 0)
        s.background(); assertNull(s.foreground()); assertEquals(LifecycleScanState.Phase.EMPTY, s.phase)
    }
    @Test fun permissionCallbackAfterResumedStartsImmediately() {
        val s = LifecycleScanState(); assertNull(s.foreground()); assertNotNull(s.request())
    }
    @Test fun cancellationAndFailureNeverLeaveIdleLoadingAndCanRestart() {
        val s = LifecycleScanState(); s.foreground(); val a = s.request()!!; s.cancelled(a)
        assertEquals(LifecycleScanState.Phase.CANCELLED, s.phase); assertNull(s.active)
        val b = s.request()!!; s.complete(b, 0, failed = true)
        assertEquals(LifecycleScanState.Phase.ERROR, s.phase); assertNotNull(s.request(true))
    }
    @Test fun ownershipCancellationBeforeStopRetainsForegroundRetryAndForce() {
        val s = LifecycleScanState(); s.foreground(); val first = s.request(true)!!
        s.cancelled(first); assertEquals(LifecycleScanState.Phase.CANCELLED,s.phase)
        s.background(); val next = s.foreground()!!
        assertTrue(next.force); assertNotEquals(first.generation,next.generation); assertNull(s.foreground())
    }
    @Test fun closeDiscardsDeferredAndActiveSessionWork() {
        val s = LifecycleScanState(); s.request(true); s.close(); assertNull(s.foreground())
        val r = s.request()!!; s.close(); assertFalse(s.owns(r)); assertEquals(LifecycleScanState.Phase.IDLE, s.phase)
    }
}
