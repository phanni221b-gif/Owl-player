package `is`.xyz.mpv

/**
 * Short-lived in-process launch options used when Owl opens a video from its own folder browser.
 * MPVActivity can also be launched externally, so this state is intentionally optional and reset
 * after the first player initialization.
 */
object OwlLaunchState {
    @Volatile var playlistStartIndex: Int = -1
    @Volatile var resumePositionMs: Long = -1L

    fun consumePlaylistStartIndex(): Int = playlistStartIndex.also { playlistStartIndex = -1 }
    fun consumeResumePositionMs(): Long = resumePositionMs.also { resumePositionMs = -1L }

    fun clear() {
        playlistStartIndex = -1
        resumePositionMs = -1L
    }
}
