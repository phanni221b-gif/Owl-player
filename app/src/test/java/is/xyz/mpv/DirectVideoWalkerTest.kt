package `is`.xyz.mpv

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CancellationException

class DirectVideoWalkerTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file(name: String): File = File(temporary.root, name).also { it.parentFile!!.mkdirs(); it.writeText("") }

    @Test fun excludesAppDataAndObbButFindsPartiallyIndexedDirectories() {
        file("Android/data/hidden.mp4")
        file("Android/obb/hidden.mkv")
        file("Movies/a.MP4")
        file("Unindexed/b.mkv")
        file("Unindexed/readme.txt")
        val found = mutableListOf<String>()
        DirectVideoWalker.walk(temporary.root, true, {}, { found += it.relativeTo(temporary.root).invariantSeparatorsPath })
        assertEquals(setOf("Movies/a.MP4", "Unindexed/b.mkv"), found.toSet())
    }

    @Test fun folderRefreshReadsOnlyImmediateFiles() {
        file("a.mp4")
        file("Nested/b.mp4")
        val found = mutableListOf<String>()
        DirectVideoWalker.walk(temporary.root, false, {}, { found += it.name })
        assertEquals(listOf("a.mp4"), found)
    }

    @Test fun cancellationStopsInsideLargeDirectory() {
        repeat(100) { file("movie$it.mp4") }
        var checks = 0
        val found = mutableListOf<File>()
        try {
            DirectVideoWalker.walk(temporary.root, true, { if (++checks > 10) throw CancellationException() }, { found += it })
            fail("Traversal should cancel")
        } catch (_: CancellationException) { }
        assertTrue(found.size < 100)
        assertEquals(11, checks)
    }
}
