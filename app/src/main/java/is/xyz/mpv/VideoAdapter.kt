package `is`.xyz.mpv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class VideoAdapter(
    private val onClick: (MediaVideo) -> Unit
) : RecyclerView.Adapter<VideoAdapter.Holder>() {
    private var items: List<MediaVideo> = emptyList()

    fun submit(videos: List<MediaVideo>) {
        items = videos
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.videoTitle)
        private val meta: TextView = view.findViewById(R.id.videoMeta)

        fun bind(item: MediaVideo) {
            title.text = item.title
            val duration = formatDuration(item.durationMs)
            val resolution = if (item.width > 0 && item.height > 0) "${item.width}×${item.height}" else ""
            meta.text = listOf(duration, resolution).filter { it.isNotBlank() }.joinToString("  ·  ")
            itemView.setOnClickListener { onClick(item) }
        }
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
