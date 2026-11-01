package `is`.xyz.mpv

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class MediaRulesTest {
    private fun video(path: String?, uri: String = "content://media/1", name: String = "Movie") =
        MediaVideo(name, path, uri, 60_000, 1920, 1080)

    @Test fun groupsByFolderAndKeepsIndexedMetadata() {
        val groups = linkedMapOf<String, MediaFolder>()
        val seen = hashSetOf<String>()
        val indexed = video("/ssd/A/a.mp4")
        MediaRules.add(groups, "/ssd/A", "A", "USB", indexed, seen)
        MediaRules.add(groups, "/ssd/A", "A", "USB", indexed.copy(uri = "file:///ssd/A/a.mp4", durationMs = 0), seen)
        MediaRules.add(groups, "/ssd/B", "B", "USB", video("/ssd/B/b.mp4"), seen)
        assertEquals(2, groups.size)
        assertEquals(listOf(indexed), groups["/ssd/A"]!!.videos)
    }

    @Test fun partialIndexReconciliationAddsMissingFilesOnSameVolume() {
        val groups = linkedMapOf<String, MediaFolder>()
        val seen = hashSetOf<String>()
        MediaRules.add(groups, "/ssd/A", "A", "USB", video("/ssd/A/1.mp4"), seen)
        MediaRules.add(groups, "/ssd/A", "A", "USB", video("/ssd/A/2.mp4"), seen)
        MediaRules.add(groups, "/ssd/Missing", "Missing", "USB", video("/ssd/Missing/3.mp4"), seen)
        assertEquals(3, groups.values.sumOf { it.videos.size })
    }

    @Test fun contentOnlyUrisKeepDistinctIdentities() {
        assertNotEquals(MediaRules.identity(video(null, "content://media/1")), MediaRules.identity(video(null, "content://media/2")))
    }

    @Test fun snapshotSortsWithoutMutatingPreviouslyPublishedLists() {
        val groups = linkedMapOf("b" to MediaFolder("b", "beta", mutableListOf(video("/z", name = "Z"), video("/a", name = "alpha"))),
            "a" to MediaFolder("a", "Alpha"))
        val snapshot = MediaRules.snapshot(groups, Locale.US)
        groups["b"]!!.videos.clear()
        assertEquals(listOf("Alpha", "beta"), snapshot.map { it.name })
        assertEquals(listOf("alpha", "Z"), snapshot[1].videos.map { it.title })
    }

    @Test fun largePlaylistPreservesExactDisplayedOrder() {
        val entries = (5000 downTo 1).map { "/ssd/video " + it + ".mkv" }
        val playlist = MediaRules.playlist(entries)
        assertEquals(entries, playlist.lines().drop(1).filter { it.isNotEmpty() })
        assertTrue(playlist.startsWith("memory://#EXTM3U\n"))
    }

    @Test(expected = IllegalArgumentException::class) fun playlistRejectsInjectedLines() {
        MediaRules.playlist(listOf("/video\n/other"))
    }

    @Test fun historyKeysRemainCompatibleAcrossFileAndContentDiscovery() {
        assertEquals(MediaRules.historyKey("/ssd/a.mp4"), MediaRules.historyKey(MediaRules.identity(video("/ssd/a.mp4", "file:///ssd/a.mp4"))))
        assertEquals(24, MediaRules.historyKey("/ssd/a.mp4").length)
        assertNotEquals(MediaRules.historyKey("/ssd/a.mp4"), MediaRules.historyKey("/ssd/b.mp4"))
    }

    @Test fun resumeBoundaryRulesPreserveExpectedState() {
        assertNull(MediaRules.resumeState(9999, 60_000))
        assertNull(MediaRules.resumeState(50_000, 60_000))
        assertEquals(10_000L to 60_000L, MediaRules.resumeState(10_000, 60_000))
        assertEquals(30_000L to 0L, MediaRules.resumeState(30_000, 0))
    }

    @Test fun defaultsNeverOverwriteExistingTrueFalseOrEmptyValues() {
        val existing = mapOf("video_fastdecode" to true, "gpu_next" to false, "video_debanding" to "")
        val defaults = mapOf<String, Any>("video_fastdecode" to false, "gpu_next" to true,
            "video_debanding" to "gpu", "new_setting" to true)
        assertEquals(mapOf("new_setting" to true), MediaRules.missingDefaults(existing, defaults))
    }
}
