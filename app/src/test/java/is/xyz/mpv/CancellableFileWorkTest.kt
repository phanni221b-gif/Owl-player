package `is`.xyz.mpv

import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CancellableFileWorkTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun cancelledCopyRetainsSourceAndRemovesIncompleteDestination() {
        val source = temp.newFile("source.bin").apply { writeBytes(ByteArray(4 * 1024 * 1024) { 42 }) }
        val target = File(temp.root, "target.bin")
        var checks = 0
        try { CancellableFileWork.copyThenMove(source, target) { if (++checks == 5) throw CancellationException() }; fail() }
        catch (_: CancellationException) { }
        assertEquals(4L * 1024 * 1024, source.length())
        assertFalse(target.exists())
    }
    @Test fun cancellationAfterCopyRetainsCompleteDestination() {
        val source = temp.newFile("source2.bin").apply { writeText("preserve this"); setLastModified(1_000_000L) }
        val target = File(temp.root, "target2.bin")
        try {
            CancellableFileWork.copyThenMove(source, target) {
                if (target.exists() && target.length() == source.length() && target.lastModified() == source.lastModified()) throw CancellationException()
            }
            fail()
        } catch (_: CancellationException) { }
        assertEquals("preserve this", source.readText())
        assertEquals("preserve this", target.readText())
    }
    @Test fun completedCopyPreservesDataAndDeletesSource() {
        val source = temp.newFolder("source")
        File(source, "video").writeText("keep this")
        val target = File(temp.root, "destination")
        assertTrue(CancellableFileWork.copyThenMove(source, target) {})
        assertFalse(source.exists())
        assertEquals("keep this", File(target, "video").readText())
    }
}
