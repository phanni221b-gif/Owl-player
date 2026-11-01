package `is`.xyz.mpv

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DiscoveryPipelineTest {
    private fun folder(name: String) = MediaFolder(name, name, mutableListOf(MediaVideo(name, "/" + name, "file:///" + name, 0, 0, 0)))

    @Test fun primaryPublishesBeforeVolumeEnumerationAndThirtySecondExternalWait() = runBlocking {
        val internal = listOf(folder("one"), folder("two"), folder("three"))
        val snapshots = mutableListOf<List<MediaFolder>>()
        val externalStarted = CompletableDeferred<Unit>()
        val work = launch {
            DiscoveryPipeline.discover({ internal }, {
                assertEquals(internal.map { it.key }.toSet(), snapshots.first().map { it.key }.toSet()); listOf("usb")
            }, { externalStarted.complete(Unit); delay(30_000); emptyList() }, {}, { snapshots += it })
        }
        withTimeout(1000) { externalStarted.await() }
        assertEquals(3, snapshots.first().size)
        work.cancelAndJoin()
        assertEquals(1, snapshots.size)
    }

    @Test fun externalExceptionPreservesInternalAndReconcilesMissingFiles() = runBlocking {
        val snapshots = mutableListOf<List<MediaFolder>>()
        val result = DiscoveryPipeline.discover({ listOf(folder("internal")) }, { listOf("usb") },
            { throw IllegalArgumentException("USB disconnected") },
            { publish -> publish(listOf(folder("direct"))) }, { snapshots += it })
        assertEquals(setOf("internal", "direct"), result.map { it.key }.toSet())
        assertTrue(snapshots.all { it.any { f -> f.key == "internal" } })
    }

    @Test fun externalTimeoutKeepsInternalAndRunsFallback() = runBlocking {
        val result = DiscoveryPipeline.discover({ listOf(folder("internal")) }, { listOf("usb") },
            { withTimeoutOrNull(20) { delay(30_000); listOf(folder("late")) } ?: emptyList() },
            { it(listOf(folder("fallback"))) }, {})
        assertEquals(setOf("internal", "fallback"), result.map { it.key }.toSet())
    }

    @Test fun folderRefreshInvokesOnlyScopedSources() = runBlocking {
        val calls = mutableListOf<String>()
        val result = DiscoveryPipeline.folder("selected", { key -> calls += "query:" + key; listOf(folder(key)) },
            { key, emit -> calls += "direct:" + key; emit(listOf(folder("other"))) })
        assertEquals(listOf("query:selected", "direct:selected"), calls)
        assertEquals("selected", result?.key)
    }

    @Test fun cancelledAndSupersededOwnersCannotOverwriteCache() {
        val owner = ScanOwnership()
        val old = owner.begin(Job())
        var value = "old"
        val next = owner.begin(Job())
        assertTrue(old.job.isCancelled)
        assertFalse(owner.publish(old) { value = "stale" })
        assertTrue(owner.publish(next) { value = "new" })
        owner.cancel()
        assertFalse(owner.publish(next) { value = "late" })
        assertEquals("new", value)
    }

    @Test fun providerIgnoringCancellationCannotBlockCallerOrPrimaryLane() = runBlocking {
        val external = ProviderQueryRunner(1, "TestExternal")
        val primary = ProviderQueryRunner(1, "TestPrimary")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelDelivered = CountDownLatch(1)
        try {
            val result = withTimeoutOrNull(100) {
                external.query({ cancelDelivered.countDown() }) {
                    entered.countDown()
                    while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                    "late"
                }
            }
            assertNull(result)
            assertTrue(entered.await(100, TimeUnit.MILLISECONDS))
            assertTrue(cancelDelivered.await(1000, TimeUnit.MILLISECONDS))
            assertEquals("internal", withTimeout(1000) { primary.query({}) { "internal" } })
            try { external.query({}) { "should not be queued" }; fail("Saturated provider lane must reject promptly") }
            catch (_: java.util.concurrent.RejectedExecutionException) { }
        } finally { release.countDown() }
    }
}
