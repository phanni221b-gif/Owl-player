package `is`.xyz.mpv

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.junit.Assert.*
import org.junit.Test

class BoundedVideoWalkerTest {
    @Test fun realDirectoryTraversalPreservesRecursiveVideosAndExcludesPrivateDirectories() = runBlocking {
        val root = Files.createTempDirectory("owl-bounded").toFile()
        try {
            File(root, "a.mp4").writeText("a")
            File(root, "nested").mkdirs(); File(root, "nested/b.mkv").writeText("bb")
            File(root, "Android/data/private").mkdirs(); File(root, "Android/data/private/c.mp4").writeText("c")
            val found = mutableListOf<BoundedVideoWalker.Video>()
            BoundedVideoWalker().walk(root, true, {}, { found.add(it) })
            assertEquals(setOf("a.mp4", "b.mkv"), found.map { it.file.name }.toSet())
            assertEquals(3L, found.sumOf { it.size })
        } finally { root.deleteRecursively() }
    }
    @Test fun uninterruptibleDirectoryCannotBlockNextWalkOrPublishLateVideos() = runBlocking {
        val release = CountDownLatch(1); val entered = CountDownLatch(1)
        val worker = ProviderQueryRunner(2, "OwlTestDirectory")
        val walker = BoundedVideoWalker(100, worker)
        val found = mutableListOf<BoundedVideoWalker.Video>()
        try {
            walker.walk(File("blocked"), true, {}, { found.add(it) }, inspect = {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                BoundedVideoWalker.Batch(it.absolutePath, emptyList(), listOf(BoundedVideoWalker.Video(File("late.mp4"), 1, 0)))
            })
            assertTrue(entered.await(1, TimeUnit.SECONDS)); assertTrue(found.isEmpty())
            walker.walk(File("next"), false, {}, { found.add(it) }, inspect = {
                BoundedVideoWalker.Batch(it.absolutePath, emptyList(), listOf(BoundedVideoWalker.Video(File("ok.mp4"), 2, 0)))
            })
            release.countDown(); delay(50)
            assertEquals(listOf("ok.mp4"), found.map { it.file.name })
        } finally { release.countDown() }
    }
    @Test fun cancellationDetachesBlockedReadAndStopsPublication() = runBlocking {
        val release = CountDownLatch(1); val entered = CountDownLatch(1)
        var published = false
        val scan = launch {
            BoundedVideoWalker().walk(File("blocked"), false, {}, { published = true }, inspect = {
                entered.countDown()
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                BoundedVideoWalker.Batch(it.absolutePath, emptyList(), emptyList())
            })
        }
        try {
            while (entered.count > 0) delay(5)
            scan.cancel(); scan.join(); assertTrue(scan.isCompleted); assertFalse(published)
        } finally { release.countDown() }
    }
}
