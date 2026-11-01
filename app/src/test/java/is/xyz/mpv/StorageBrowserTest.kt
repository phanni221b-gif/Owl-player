package `is`.xyz.mpv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageBrowserTest {
    @Test
    fun containsPathDoesNotAcceptSiblingPrefix() {
        assertTrue(StorageBrowser.containsPath("/storage/ABCD", "/storage/ABCD"))
        assertTrue(StorageBrowser.containsPath("/storage/ABCD", "/storage/ABCD/Movies/a.mp4"))
        assertFalse(StorageBrowser.containsPath("/storage/ABCD", "/storage/ABCD2/Movies/a.mp4"))
        assertFalse(StorageBrowser.containsPath("/storage/ABCD", "/storage/ABC"))
    }

    @Test
    fun filterFoldersKeepsOnlySelectedStorageTree() {
        val internal = MediaFolder(
            key = "/storage/emulated/0/Movies",
            name = "Movies",
            videos = mutableListOf(video("/storage/emulated/0/Movies/a.mp4")),
            storageLabel = "내부 저장소"
        )
        val external = MediaFolder(
            key = "/storage/ABCD/Movies",
            name = "Movies",
            videos = mutableListOf(video("/storage/ABCD/Movies/b.mp4")),
            storageLabel = "외장 저장소"
        )

        assertEquals(listOf(internal, external), StorageBrowser.filterFolders(listOf(internal, external), null))
        assertEquals(listOf(internal), StorageBrowser.filterFolders(listOf(internal, external), "/storage/emulated/0"))
        assertEquals(listOf(external), StorageBrowser.filterFolders(listOf(internal, external), "/storage/ABCD"))
    }

    @Test
    fun rootForPathChoosesMostSpecificContainingRoot() {
        val roots = listOf(
            StorageRoot("전체", "/storage", false),
            StorageRoot("USB", "/storage/ABCD", false)
        )
        assertEquals("/storage/ABCD", StorageBrowser.rootForPath(roots, "/storage/ABCD/Movies")?.path)
    }

    private fun video(path: String) = MediaVideo(
        title = path.substringAfterLast('/'),
        path = path,
        uri = "file://$path",
        durationMs = 0L,
        width = 0,
        height = 0
    )
}
