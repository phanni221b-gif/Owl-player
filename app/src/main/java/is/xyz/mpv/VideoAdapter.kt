package `is`.xyz.mpv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.util.Locale

class VideoAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onClick: (MediaVideo) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<MediaVideo, VideoAdapter.Holder>(object : DiffUtil.ItemCallback<MediaVideo>() {
    override fun areItemsTheSame(a: MediaVideo, b: MediaVideo) = identity(a) == identity(b)
    override fun areContentsTheSame(a: MediaVideo, b: MediaVideo) = a == b
}) {
    private val items: List<MediaVideo> get() = currentList
    private var history = emptyMap<String, PlaybackHistory.Entry?>()
    private var generation = 0
    private var historyJob: Job? = null
    private var thumbnailsPaused = false
    fun pauseThumbnails(recycler: RecyclerView, paused: Boolean) {
        thumbnailsPaused = paused
        for (index in 0 until recycler.childCount) {
            val holder = recycler.getChildViewHolder(recycler.getChildAt(index)) as? Holder ?: continue
            if (paused) holder.cancelThumbnail()
            else if (holder.bindingAdapterPosition != RecyclerView.NO_POSITION) holder.loadThumbnail(getItem(holder.bindingAdapterPosition))
        }
    }
    private val selectedUris = linkedSetOf<String>()

    fun cancelPending() { generation++; historyJob?.cancel() }

    fun submit(videos: List<MediaVideo>) {
        val version = ++generation
        val snapshot = videos.toList()
        historyJob?.cancel()
        selectedUris.retainAll(snapshot.map { identity(it) }.toSet())
        // Do not hold the list behind thousands of preference/history reads.
        submitList(snapshot) { if (version == generation) loadHistory(snapshot, version) }
        onSelectionChanged(selectedUris.size)
    }

    private fun loadHistory(snapshot: List<MediaVideo>, version: Int) {
        historyJob?.cancel()
        historyJob = scope.launch {
            val entries = withContext(Dispatchers.IO) {
                snapshot.associate { currentCoroutineContext().ensureActive(); identity(it) to PlaybackHistory.get(context, it) }
            }
            if (version != generation) return@launch
            val changed = (history.keys + entries.keys).filter { history[it] != entries[it] }.toSet()
            history = entries
            items.forEachIndexed { index, video -> if (identity(video) in changed) notifyItemChanged(index, "progress") }
        }
    }

    fun refreshProgress() = loadHistory(items.toList(), generation)

    fun isSelectionMode(): Boolean = selectedUris.isNotEmpty()

    fun selectedItems(): List<MediaVideo> =
        items.filter { selectedUris.contains(identity(it)) }

    fun clearSelection() {
        if (selectedUris.isEmpty()) return
        val changed = selectedUris.toSet()
        selectedUris.clear()
        updateSelection(changed)
        onSelectionChanged(0)
    }

    fun selectAll() {
        val changed = items.map { identity(it) }.filterNot { it in selectedUris }.toSet()
        selectedUris.addAll(changed)
        updateSelection(changed)
        onSelectionChanged(selectedUris.size)
    }

    private fun toggleSelection(item: MediaVideo) {
        if (!selectedUris.add(identity(item))) selectedUris.remove(identity(item))
        updateSelection(setOf(identity(item)))
        onSelectionChanged(selectedUris.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        return Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false))
    }

    private fun updateSelection(keys: Set<String>) {
        items.forEachIndexed { index, item -> if (identity(item) in keys) notifyItemChanged(index, "selection") }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) super.onBindViewHolder(holder, position, payloads)
        else {
            val item = getItem(position)
            holder.itemView.isActivated = identity(item) in selectedUris
            if ("progress" in payloads) holder.bindProgress(item)
        }
    }

    override fun onViewRecycled(holder: Holder) { holder.cancelThumbnail(); super.onViewRecycled(holder) }
    override fun onViewDetachedFromWindow(holder: Holder) { holder.cancelThumbnail(); super.onViewDetachedFromWindow(holder) }
    override fun onViewAttachedToWindow(holder: Holder) {
        super.onViewAttachedToWindow(holder)
        val index = holder.bindingAdapterPosition
        if (index != RecyclerView.NO_POSITION) holder.loadThumbnail(getItem(index))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbnail: ImageView = view.findViewById(R.id.videoThumbnail)
        private val progress: ProgressBar = view.findViewById(R.id.videoProgress)
        private val title: TextView = view.findViewById(R.id.videoTitle)
        private val meta: TextView = view.findViewById(R.id.videoMeta)
        private val resume: TextView = view.findViewById(R.id.videoResume)

        fun cancelThumbnail() = VideoThumbnailLoader.cancel(thumbnail)
        fun loadThumbnail(item: MediaVideo) { if (!thumbnailsPaused) VideoThumbnailLoader.load(thumbnail, item) }
        fun bindProgress(item: MediaVideo) {
            val history = history[identity(item)]
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

        }

        fun bind(item: MediaVideo) {
            title.text = item.title
            val duration = formatDuration(item.durationMs)
            val resolution = formatResolution(item.width, item.height)
            val size = formatSize(item.sizeBytes)
            meta.text = listOf(duration, resolution, size).filter { it.isNotBlank() }.joinToString("  ·  ")

            loadThumbnail(item)

            bindProgress(item)

            itemView.isActivated = selectedUris.contains(identity(item))
            itemView.setOnClickListener {
                if (isSelectionMode()) toggleSelection(item) else onClick(item)
            }
            itemView.setOnLongClickListener {
                toggleSelection(item)
                true
            }
        }
    }

    companion object {
        fun identity(video: MediaVideo): String = MediaRules.identity(video)
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
