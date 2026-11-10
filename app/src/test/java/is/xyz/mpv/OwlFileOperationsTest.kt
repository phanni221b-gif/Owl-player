package `is`.xyz.mpv

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class OwlFileOperationsTest {
    @get:Rule val temp = TemporaryFolder()

    private class FakeIndex : OwlMediaIndex {
        val deletedRows = mutableListOf<String>()
        val scanned = mutableListOf<File>()
        override fun deleteRow(uri: String): Boolean { deletedRows += uri; return false }
        override fun scan(files: List<File>) { scanned += files }
    }

    private fun file(parent: File, name: String, bytes: Int = 8) =
        File(parent, name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(bytes)) }

    private fun video(f: File) = MediaVideo(f.name, f.absolutePath, "content://media/external/video/media/" + f.name.hashCode(), 0, 0, 0)

    private fun folderOf(dir: File, vararg videos: File) =
        MediaFolder(dir.absolutePath, dir.name, videos.map(::video).toMutableList())

    @Test fun deletingAFolderThatIsAVolumeRootRemovesOnlyItsListedVideos() {
        val root = temp.newFolder("storage", "emulated", "0")
        val clip = file(root, "clip.mp4")
        val photo = file(File(root, "DCIM/Camera"), "photo.jpg")
        val document = file(root, "notes.pdf")
        val nestedVideo = file(File(root, "Movies"), "other.mkv")

        val result = OwlFileOperations.deleteFolders(FakeIndex(), listOf(root), listOf(folderOf(root, clip)), {})

        assertEquals(1, result.successCount)
        assertFalse(clip.exists())
        assertTrue("photos must survive a video-folder delete", photo.exists())
        assertTrue(document.exists())
        assertTrue("subfolders are separate Owl folders", nestedVideo.exists())
        assertTrue("a volume root is never removed", root.isDirectory)
    }

    @Test fun folderDeleteKeepsNonVideoFilesAndSubfoldersButRemovesEmptiedDirectory() {
        val root = temp.newFolder("vol")
        val mixed = File(root, "Trip")
        val a = file(mixed, "a.mp4"); val b = file(mixed, "b.mkv")
        val photo = file(mixed, "cover.jpg")
        val sub = file(File(mixed, "extra"), "c.mp4")
        val empty = File(root, "Only"); val d = file(empty, "d.mp4")

        val result = OwlFileOperations.deleteFolders(FakeIndex(), listOf(root),
            listOf(folderOf(mixed, a, b), folderOf(empty, d)), {})

        assertEquals(OwlFileOperationResult(2, 0), result)
        assertFalse(a.exists()); assertFalse(b.exists())
        assertTrue(photo.exists()); assertTrue(sub.exists())
        assertTrue("non-empty directory stays", mixed.isDirectory)
        assertFalse("directory emptied by the delete is cleaned up", empty.exists())
    }

    @Test fun standardSharedDirectoryIsNotRemovedEvenWhenEmptied() {
        val root = temp.newFolder("v")
        val movies = File(root, "Movies"); val m = file(movies, "m.mp4")
        OwlFileOperations.deleteFolders(FakeIndex(), listOf(root), listOf(folderOf(movies, m)), {})
        assertFalse(m.exists())
        assertTrue(movies.isDirectory)
    }

    @Test fun missingFilesAreReportedSeparatelyNotAsSuccess() {
        val dir = temp.newFolder("d")
        val present = file(dir, "p.mp4")
        val gone = File(dir, "gone.mp4")
        val result = OwlFileOperations.deleteVideos(FakeIndex(), listOf(video(present), video(gone)), {})
        assertEquals(1, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(1, result.missingCount)
        assertFalse(present.exists())
    }

    @Test fun videoDeleteNeverRecursesIntoADirectoryPath() {
        val dir = temp.newFolder("x")
        val inner = file(File(dir, "looks.mp4"), "keep.bin")
        val result = OwlFileOperations.deleteVideos(FakeIndex(), listOf(video(File(dir, "looks.mp4"))), {})
        assertEquals(1, result.failureCount)
        assertTrue(inner.exists())
    }

    @Test fun protectedDirectoriesCannotBeMoved() {
        val root = temp.newFolder("storage", "emulated", "0")
        val clip = file(root, "clip.mp4")
        val destination = temp.newFolder("dest")
        val result = OwlFileOperations.moveFolders(FakeIndex(), listOf(root), listOf(folderOf(root, clip)), destination, {})
        assertEquals(1, result.protectedCount)
        assertTrue(clip.exists())
        assertEquals(0, destination.list()!!.size)
    }

    @Test fun ordinaryFolderMoveStillWorks() {
        val root = temp.newFolder("r")
        val trip = File(root, "Trip"); val a = file(trip, "a.mp4")
        val destination = temp.newFolder("dest2")
        val index = FakeIndex()
        val result = OwlFileOperations.moveFolders(index, listOf(root), listOf(folderOf(trip, a)), destination, {})
        assertEquals(1, result.successCount)
        assertTrue(File(destination, "Trip/a.mp4").exists())
        assertFalse(trip.exists())
    }

    @Test fun protectedPathRules() {
        val roots = listOf(File("/storage/emulated/0"), File("/storage/1A2B-3C4D"))
        for (path in listOf("/", "/storage", "/storage/emulated", "/storage/emulated/0", "/storage/1A2B-3C4D",
                "/storage/emulated/0/DCIM", "/storage/emulated/0/Download", "/storage/1A2B-3C4D/Movies", "/sdcard"))
            assertTrue(path, OwlFileSafety.isProtectedDirectory(File(path), roots))
        for (path in listOf("/storage/emulated/0/DCIM/Camera", "/storage/emulated/0/Anime", "/storage/1A2B-3C4D/Shows/S1"))
            assertFalse(path, OwlFileSafety.isProtectedDirectory(File(path), roots))
    }

    @Test fun renameKeepsExtensionAndRejectsUnsafeNames() {
        val dir = temp.newFolder("ren")
        val clip = file(dir, "old.mp4")
        val index = FakeIndex()
        val renamed = OwlFileOperations.renameVideo(index, video(clip), "new name").getOrThrow()
        assertEquals("new name.mp4", renamed.name)
        assertTrue(renamed.exists()); assertFalse(clip.exists())
        assertEquals(listOf(renamed), index.scanned)
        for (bad in listOf("", "  ", "..", "a/b", "x\u0000y"))
            assertTrue(bad, OwlFileOperations.renameVideo(index, video(renamed), bad).isFailure)
        file(dir, "taken.mp4")
        assertTrue(OwlFileOperations.renameVideo(index, video(renamed), "taken").isFailure)
        assertTrue(renamed.exists())
    }

    @Test fun caseOnlyRenameIsAllowed() {
        val dir = temp.newFolder("case")
        val clip = file(dir, "movie.mp4")
        val renamed = OwlFileOperations.renameVideo(FakeIndex(), video(clip), "Movie.mp4").getOrThrow()
        assertEquals("Movie.mp4", renamed.name)
        assertTrue(renamed.exists())
    }

    @Test fun protectedFolderRenameIsRefused() {
        val root = temp.newFolder("rr")
        val download = File(root, "Download"); val v = file(download, "v.mp4")
        assertTrue(OwlFileOperations.renameFolder(FakeIndex(), listOf(root), folderOf(download, v), "Other").isFailure)
        assertTrue(v.exists())
        val trip = File(root, "Trip"); val t = file(trip, "t.mp4")
        val renamed = OwlFileOperations.renameFolder(FakeIndex(), listOf(root), folderOf(trip, t), "Holiday").getOrThrow()
        assertTrue(File(renamed, "t.mp4").exists())
    }

    @Test fun resultMessageIsSpecific() {
        assertEquals("2개 삭제 완료 · 1개 실패 · 1개는 파일을 찾을 수 없음",
            OwlFileOperationResult(2, 1, 1).message("삭제"))
    }
}
