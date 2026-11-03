package `is`.xyz.mpv

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SeenVideosTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun video(path: String) = MediaVideo("video",path,path,60_000,1280,720)
    @Before fun reset() {
        context.getSharedPreferences("owl_seen_videos",0).edit().clear().commit()
        context.getSharedPreferences("owl_playback_history",0).edit().clear().commit()
    }
    @Test fun newVideosCountDownOnlyAfterSuccessfulLoadIsRecorded() {
        val videos = listOf(video("/storage/a.mp4"),video("/storage/b.mp4"))
        assertEquals(2,SeenVideos.unseenCount(videos,SeenVideos.snapshot(context)))
        SeenVideos.markPlayed(context,"/storage/a.mp4")
        assertEquals(1,SeenVideos.unseenCount(videos,SeenVideos.snapshot(context)))
        SeenVideos.markPlayed(context,"/storage/b.mp4")
        assertEquals(0,SeenVideos.unseenCount(videos,SeenVideos.snapshot(context)))
    }
    @Test fun seenStateSurvivesCompletionAndResumeHistoryRemoval() {
        val video = video("/storage/complete.mp4")
        SeenVideos.markPlayed(context,video.path!!)
        PlaybackHistory.save(context,video,20_000,60_000)
        PlaybackHistory.save(context,video,60_000,60_000)
        assertNull(PlaybackHistory.get(context,video))
        PlaybackHistory.clear(context,video)
        assertEquals(0,SeenVideos.unseenCount(listOf(video),SeenVideos.snapshot(context)))
    }
    @Test fun oldResumeRecordsMigrateWithoutChangingResumePosition() {
        val video = video("/storage/old.mp4")
        PlaybackHistory.save(context,video,20_000,60_000)
        assertEquals(0,SeenVideos.unseenCount(listOf(video),SeenVideos.snapshot(context)))
        assertEquals(20_000L,PlaybackHistory.get(context,video)?.positionMs)
        PlaybackHistory.clear(context,video)
        assertEquals(0,SeenVideos.unseenCount(listOf(video),SeenVideos.snapshot(context)))
    }
    @Test fun fileUriAndFilesystemPlaybackUseTheSameIdentity() {
        val video = video("/storage/a movie.mp4")
        SeenVideos.markPlayed(context,"file:///storage/a%20movie.mp4")
        assertEquals(0,SeenVideos.unseenCount(listOf(video),SeenVideos.snapshot(context)))
    }
    @Test fun contentOnlyVideosAreSupported() {
        val video = MediaVideo("video",null,"content://media/external/video/media/42",60_000,1280,720)
        assertEquals(1,SeenVideos.unseenCount(listOf(video),SeenVideos.snapshot(context)))
        SeenVideos.markPlayed(context,video.uri)
        assertEquals(0,SeenVideos.unseenCount(listOf(video),SeenVideos.snapshot(context)))
    }
    @Test fun duplicateDiscoveryRecordsAreCountedOnce() {
        val video = video("/storage/a.mp4")
        assertEquals(1,SeenVideos.unseenCount(listOf(video,video.copy(uri="content://media/1")),emptySet()))
    }
    @Test fun transientOrNetworkSourcesAreNotPersisted() {
        for (source in listOf("", "fd://4", "memory://playlist", "https://example.com/video")) SeenVideos.markPlayed(context,source)
        assertTrue(SeenVideos.snapshot(context).isEmpty())
    }
    @Test(expected = CancellationException::class) fun largeCountsCooperateWithCancellation() {
        SeenVideos.unseenCount(listOf(video("/storage/a.mp4")),emptySet()) { throw CancellationException() }
    }
}
