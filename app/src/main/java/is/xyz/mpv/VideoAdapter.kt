package `is`.xyz.mpv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class VideoAdapter(
    private val onClick: (MediaVideo) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<VideoAdapter.Holder>() {
    private var items: List<MediaVideo> = emptyList()
    private val selectedUris = linkedSetOf<String>()

    fun submit(videos: List<MediaVideo>) {
        items = videos
        selectedUris.retainAll(videos.map { it.uri }.toSet())
        notifyDataSetChanged()
        onSelectionChanged(selectedUris.size)
    }

    fun refreshProgress() = notifyDataSetChanged()

    fun isSelectionMode(): Boolean = selectedUris.isNotEmpty()

    fun selectedItems(): List<MediaVideo> =
        items.filter { selectedUris.contains(it.uri) }

    fun clearSelection() {
        if (selectedUris.isEmpty()) return
        selectedUris.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectAll() {
        selectedUris.addAll(items.map { it.uri })
        notifyDataSetChanged()
        onSelectionChanged(selectedUris.size)
    }

    private fun toggleSelection(item: MediaVideo) {
        if (!selectedUris.add(item.uri)) selectedUris.remove(item.uri)
        notifyDataSetChanged()
        onSelectionChanged(selectedUris.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbnail: ImageView = view.findViewById(R.id.videoThumbnail)
        private val progress: ProgressBar = view.findViewById(R.id.videoProgress)
        private val title: TextView = view.findViewById(R.id.videoTitle)
        private val meta: TextView = view.findViewById(R.id.videoMeta)
        private val resume: TextView = view.findViewById(R.id.videoResume)

        fun bind(item: MediaVideo) {
            title.text = item.title
            val duration = formatDuration(item.durationMs)
            val resolution = formatResolution(item.width, item.height)
            val size = formatSize(item.sizeBytes)
            meta.text = listOf(duration, resolution, size).filter { it.isNotBlank() }.joinToString("  ·  ")

            VideoThumbnailLoader.load(thumbnail, item)

            val history = PlaybackHistory.get(itemView.context, item)
            if (history != null) {
                progress.visibility = View.VISIBLE
                progress.progress = history.progress
                resume.visibility = View.VISIBLE
                resume.text = "이어보기 ${formatDuration(history.positionMs)} / ${formatDuration(history.durationMs)}"
            } else {
                progress.visibility = View.GONE
                progress.progress = 0
                resume.visibility = View.GONE
                resume.text = ""
            }

            itemView.isActivated = selectedUris.contains(item.uri)
            itemView.setOnClickListener {
                if (isSelectionMode()) toggleSelection(item) else onClick(item)
            }
            itemView.setOnLongClickListener {
                toggleSelection(item)
                true
            }
        }
    }

    private fun formatResolution(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        val longSide = maxOf(width, height)
        val shortSide = minOf(width, height)
        return when {
            longSide >= 3800 && shortSide >= 2000 -> "4K"
            longSide >= 2500 && shortSide >= 1400 -> "1440p"
            longSide >= 1900 && shortSide >= 1000 -> "1080p"
            longSide >= 1200 && shortSide >= 700 -> "720p"
            else -> "${width}×${height}"
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024.0) String.format(Locale.US, "%.1f GB", mb / 1024.0)
        else String.format(Locale.US, "%.0f MB", mb)
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return ""
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }
}
