package `is`.xyz.mpv

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class PlaybackHistoryTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun video(path: String) = MediaVideo("v", path, "content://media/external/video/media/1", 600_000, 1280, 720)

    @Before fun reset() {
        context.getSharedPreferences("owl_playback_history", 0).edit().clear().commit()
        context.getSharedPreferences("owl_seen_videos", 0).edit().clear().commit()
    }

    @Test fun playerSavedSourceIsReadByTheBrowserEntry() {
        // mpv's "path" for an Owl playlist entry equals MediaRules.identity(video).
        val v = video("/storage/emulated/0/Movies/a.mp4")
        PlaybackHistory.saveSource(context, MediaRules.identity(v), 120_000, 600_000)
        assertEquals(120_000L, PlaybackHistory.get(context, v)?.positionMs)
        PlaybackHistory.saveSource(context, "file:///storage/emulated/0/Movies/a.mp4", 130_000, 600_000)
        assertEquals(130_000L, PlaybackHistory.get(context, v)?.positionMs)
    }

    @Test fun nearEndOrTooEarlyClearsResume() {
        val v = video("/x/b.mp4")
        PlaybackHistory.saveSource(context, "/x/b.mp4", 120_000, 600_000)
        PlaybackHistory.saveSource(context, "/x/b.mp4", 595_000, 600_000)
        assertNull(PlaybackHistory.get(context, v))
        PlaybackHistory.saveSource(context, "/x/b.mp4", 5_000, 600_000)
        assertNull(PlaybackHistory.get(context, v))
    }

    @Test fun transientSourcesAreIgnored() {
        for (s in listOf("", "fd://3", "memory://#EXTM3U", "https://example.com/v.mp4"))
            PlaybackHistory.saveSource(context, s, 120_000, 600_000)
        assertTrue(context.getSharedPreferences("owl_playback_history", 0).all.isEmpty())
    }

    @Test fun renameKeepsResumeAndSeenState() {
        val old = video("/x/old.mp4")
        val renamed = video("/x/new.mp4")
        PlaybackHistory.save(context, old, 200_000, 600_000)
        SeenVideos.markPlayed(context, "/x/old.mp4")
        PlaybackHistory.moveSource(context, "/x/old.mp4", "/x/new.mp4")
        SeenVideos.moveSource(context, "/x/old.mp4", "/x/new.mp4")
        assertNull(PlaybackHistory.get(context, old))
        assertEquals(200_000L, PlaybackHistory.get(context, renamed)?.positionMs)
        assertEquals(0, SeenVideos.unseenCount(listOf(renamed), SeenVideos.snapshot(context)))
    }
}
