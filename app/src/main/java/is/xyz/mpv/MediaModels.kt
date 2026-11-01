package `is`.xyz.mpv

data class MediaVideo(
    val title: String,
    val path: String?,
    val uri: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long = 0L,
    val modifiedSeconds: Long = 0L
)

data class MediaFolder(
    val key: String,
    val name: String,
    val videos: MutableList<MediaVideo> = mutableListOf(),
    val storageLabel: String = ""
) {
    val newestModifiedSeconds: Long
        get() = videos.maxOfOrNull { it.modifiedSeconds } ?: 0L
}

