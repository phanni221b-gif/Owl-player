package `is`.xyz.mpv

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.DiffUtil

class FolderAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onClick: (MediaFolder) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : ListAdapter<MediaFolder, FolderAdapter.Holder>(object : DiffUtil.ItemCallback<MediaFolder>() {
    override fun areItemsTheSame(a: MediaFolder, b: MediaFolder) = a.key == b.key
    override fun areContentsTheSame(a: MediaFolder, b: MediaFolder) = a == b
}) {
    private var unseen = emptyMap<String, Int>()
    private var countJob: Job? = null
    private var generation = 0
    fun cancelPending() { generation++; countJob?.cancel() }
    private fun refreshUnseen() {
        val version = ++generation
        countJob?.cancel()
        val snapshot = allItems.toList()
        countJob = scope.launch {
            val counts = withContext(Dispatchers.Default) {
                val seen = withContext(Dispatchers.IO) { SeenVideos.snapshot(context) }
                val workContext = currentCoroutineContext()
                snapshot.associate { folder -> folder.key to SeenVideos.unseenCount(folder.videos, seen) { workContext.ensureActive() } }
            }
            if (version != generation || OwlSession.ending) return@launch
            val changed = (unseen.keys + counts.keys).filter { unseen[it] != counts[it] }.toSet()
            unseen = counts
            currentList.forEachIndexed { index, folder -> if (folder.key in changed) notifyItemChanged(index, "unseen") }
        }
    }
    private var allItems: List<MediaFolder> = emptyList()
    private val visibleItems: List<MediaFolder> get() = currentList
    private var currentQuery: String = ""
    private val selectedKeys = linkedSetOf<String>()

    fun submit(items: List<MediaFolder>) {
        allItems = items
        selectedKeys.retainAll(items.map { it.key }.toSet())
        applyFilter()
        refreshUnseen()
        onSelectionChanged(selectedKeys.size)
    }

    fun filter(query: String) {
        currentQuery = query.trim()
        applyFilter()
    }

    fun isSelectionMode(): Boolean = selectedKeys.isNotEmpty()

    fun selectedItems(): List<MediaFolder> =
        allItems.filter { selectedKeys.contains(it.key) }

    fun clearSelection() {
        if (selectedKeys.isEmpty()) return
        val changed = selectedKeys.toSet()
        selectedKeys.clear()
        updateSelection(changed)
        onSelectionChanged(0)
    }

    fun selectAllVisible() {
        val changed = visibleItems.map { it.key }.filterNot { it in selectedKeys }.toSet()
        selectedKeys.addAll(changed)
        updateSelection(changed)
        onSelectionChanged(selectedKeys.size)
    }

    private fun toggleSelection(item: MediaFolder) {
        if (!selectedKeys.add(item.key)) selectedKeys.remove(item.key)
        updateSelection(setOf(item.key))
        onSelectionChanged(selectedKeys.size)
    }

    private fun applyFilter() {
        val filtered = if (currentQuery.isBlank()) allItems else allItems.filter {
            it.name.contains(currentQuery, ignoreCase = true)
        }
        submitList(filtered)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return Holder(view)
    }

    private fun updateSelection(keys: Set<String>) {
        currentList.forEachIndexed { index, item -> if (item.key in keys) notifyItemChanged(index, "selection") }
    }

    override fun onBindViewHolder(holder: Holder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) super.onBindViewHolder(holder, position, payloads)
        else {
            if (payloads.contains("unseen")) holder.bindUnseen(getItem(position))
            holder.itemView.isActivated = getItem(position).key in selectedKeys
        }
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(visibleItems[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.folderIcon)
        private val title: TextView = view.findViewById(R.id.folderTitle)
        private val count: TextView = view.findViewById(R.id.folderCount)

        private val badge: TextView = view.findViewById(R.id.folderUnseenBadge)

        fun bindUnseen(item: MediaFolder) {
            val value = unseen[item.key] ?: 0
            badge.visibility = if (value > 0) View.VISIBLE else View.GONE
            badge.text = if (value > 99) "99+" else value.toString()
            badge.contentDescription = itemView.context.getString(R.string.folder_unseen_count, value)
        }

        fun bind(item: MediaFolder) {
            bindUnseen(item)
            title.text = item.name
            count.text = if (item.storageLabel.isBlank()) {
                "${item.videos.size} 비디오"
            } else {
                "${item.storageLabel} · ${item.videos.size} 비디오"
            }
            itemView.isActivated = selectedKeys.contains(item.key)
            itemView.setOnClickListener {
                if (isSelectionMode()) toggleSelection(item) else onClick(item)
            }
            itemView.setOnLongClickListener {
                toggleSelection(item)
                true
            }
            icon.contentDescription = item.name
        }
    }
}
