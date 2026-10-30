package `is`.xyz.mpv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FolderAdapter(
    private val onClick: (MediaFolder) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<FolderAdapter.Holder>() {
    private var allItems: List<MediaFolder> = emptyList()
    private var visibleItems: List<MediaFolder> = emptyList()
    private var currentQuery: String = ""
    private val selectedKeys = linkedSetOf<String>()

    fun submit(items: List<MediaFolder>) {
        allItems = items
        selectedKeys.retainAll(items.map { it.key }.toSet())
        applyFilter()
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
        selectedKeys.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    fun selectAllVisible() {
        selectedKeys.addAll(visibleItems.map { it.key })
        notifyDataSetChanged()
        onSelectionChanged(selectedKeys.size)
    }

    private fun toggleSelection(item: MediaFolder) {
        if (!selectedKeys.add(item.key)) selectedKeys.remove(item.key)
        notifyDataSetChanged()
        onSelectionChanged(selectedKeys.size)
    }

    private fun applyFilter() {
        visibleItems = if (currentQuery.isBlank()) allItems else allItems.filter {
            it.name.contains(currentQuery, ignoreCase = true)
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_folder, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = visibleItems.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(visibleItems[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.folderIcon)
        private val title: TextView = view.findViewById(R.id.folderTitle)
        private val count: TextView = view.findViewById(R.id.folderCount)

        fun bind(item: MediaFolder) {
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
