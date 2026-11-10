package `is`.xyz.mpv

import org.junit.Assert.*
import org.junit.Test

class PlaybackOpenFailureTest {
    private val folderPlaylist = MediaRules.playlist(listOf("/storage/emulated/0/Movies/a.mp4", "/storage/emulated/0/Movies/zzz-last.mp4"))

    @Test fun ownFolderPlaylistIsNeverReported() {
        // diagnostic.7 regression: every launch showed "cannot play zzz-last.mp4".
        assertTrue(PlaybackOpenFailure.isPlaylistContainer(folderPlaylist))
        assertNull(PlaybackOpenFailure.reportable(folderPlaylist, false, false, false))
    }

    @Test fun playlistFilesAreContainers() {
        for (s in listOf("/sdcard/list.m3u", "/sdcard/LIST.M3U8", "content://x/y/list.pls?x=1"))
            assertNull(s, PlaybackOpenFailure.reportable(s, false, false, false))
    }

    @Test fun realFileThatNeverStartedIsReported() {
        assertEquals("broken.mkv", PlaybackOpenFailure.reportable("/storage/emulated/0/Movies/broken.mkv", false, false, false))
    }

    @Test fun startedSkippedOrExitingIsNotReported() {
        val file = "/storage/emulated/0/Movies/a.mp4"
        assertNull(PlaybackOpenFailure.reportable(file, true, false, false))
        assertNull(PlaybackOpenFailure.reportable(file, false, true, false))
        assertNull(PlaybackOpenFailure.reportable(file, false, false, true))
        assertNull(PlaybackOpenFailure.reportable(null, false, false, false))
    }
}
